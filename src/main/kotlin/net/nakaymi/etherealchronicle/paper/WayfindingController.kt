package net.nakaymi.etherealchronicle.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import net.nakaymi.etherealchronicle.core.Area
import net.nakaymi.etherealchronicle.core.InventoryAccess
import net.nakaymi.etherealchronicle.core.NpcDefinition
import net.nakaymi.etherealchronicle.core.ObjectiveKind
import net.nakaymi.etherealchronicle.core.PlayerProfile
import net.nakaymi.etherealchronicle.core.Position
import net.nakaymi.etherealchronicle.core.QuestDefinition
import net.nakaymi.etherealchronicle.core.QuestProgress
import net.nakaymi.etherealchronicle.data.ContentSnapshot
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.scoreboard.Criteria
import org.bukkit.scoreboard.DisplaySlot
import org.bukkit.scoreboard.Scoreboard
import java.time.Duration
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** 次に取るべき行動種別（クエスト進行・報告・新規受注）を表します。 */
internal enum class GuideActionKind {
    TALK,
    KILL,
    COLLECT,
    EXPLORE,
    CATCH_FISH,
    GATHER_NODE,
    ENHANCE_GEAR,
    REPORT,
    ACCEPT,
}

/** プレイヤーへ案内する次の目標情報です。 */
internal data class GuideTarget(
    val questId: String,
    val questName: String,
    val actionKind: GuideActionKind,
    val targetId: String,
    val targetName: String,
    val progress: Int,
    val amount: Int,
    val rawPosition: Position?,
    val isNpcTarget: Boolean,
    val isRecommendedNext: Boolean = false,
)

/** クエスト進行通知の差分検知に使う軽量スナップショットです。 */
internal data class QuestProgressSnapshot(
    val active: Map<String, Map<String, Int>>,
    val ready: Set<String>,
    val completed: Set<String>,
)

/** 次の目的地解決、サイドバー追跡、方角ガイド、光の道しるべ、エリア到着バナーを管理します。 */
internal class WayfindingController(private val plugin: RpgPlugin) {
    private val lastAreaByPlayer = mutableMapOf<UUID, String?>()
    private val scoreboards = mutableMapOf<UUID, Scoreboard>()
    private val lastRenderedLines = mutableMapOf<UUID, List<String>>()
    private val guideCooldownUntil = mutableMapOf<UUID, Long>()
    private val trailCooldownUntil = mutableMapOf<UUID, Long>()

    /** 毎秒のプレイヤーループからエリア到着バナー・サイドバー・NPC目印パーティクルを更新します。 */
    fun tickPlayer(player: Player, profile: PlayerProfile) {
        val currentArea = currentArea(player)
        updateAreaBanner(player, currentArea)

        val inv = plugin.inventory(player, persistRemoval = false)
        val guide = resolveGuide(profile, plugin.snapshot, inv, player.location) { type, id, ja ->
            plugin.contentText(player, type, id, "name", ja)
        }
        val targetLoc = guide?.rawPosition?.let { resolveWorldLocation(it) }

        if (plugin.paperUiEnabled) {
            updateScoreboard(player, currentArea, guide, targetLoc)
            if (guide != null && guide.isNpcTarget && targetLoc != null && targetLoc.world == player.world) {
                val distSq = player.location.distanceSquared(targetLoc)
                if (distSq <= NPC_BEACON_RANGE_SQUARED) {
                    spawnNpcBeacon(player, targetLoc, readyToReport = guide.actionKind == GuideActionKind.REPORT)
                }
            }
        }
    }

    /** `/ec guide` またはスキルスロット右クリック時に現在地・目標・方角と光の道しるべを表示します。 */
    fun showGuide(player: Player, profile: PlayerProfile, spawnTrail: Boolean = true, throttled: Boolean = false) {
        val now = System.currentTimeMillis()
        if (throttled) {
            val readyAt = guideCooldownUntil[player.uniqueId] ?: 0L
            if (now < readyAt) return
            guideCooldownUntil[player.uniqueId] = now + GUIDE_COOLDOWN_MILLIS
        }

        val area = currentArea(player)
        val areaName = area?.let { plugin.contentText(player, "area", it.id, "name", it.name) }
            ?: plugin.message(player, "ec.area.unknown").plainText()
        val inv = plugin.inventory(player, persistRemoval = false)
        val guide = resolveGuide(profile, plugin.snapshot, inv, player.location) { type, id, ja ->
            plugin.contentText(player, type, id, "name", ja)
        }

        player.sendMessage(plugin.message(player, "ec.guide.header"))
        player.sendMessage(plugin.message(player, "ec.guide.current_area", "area" to areaName))

        if (guide == null) {
            player.sendMessage(plugin.message(player, "ec.guide.no_target"))
            return
        }

        val questKey = if (guide.isRecommendedNext) "ec.guide.next_available" else "ec.guide.active_quest"
        player.sendMessage(plugin.message(player, questKey, "name" to guide.questName))
        val objectiveText = formatObjective(player, guide.actionKind, guide.targetName)
        player.sendMessage(
            plugin.message(
                player,
                "ec.guide.objective_line",
                "objective" to objectiveText,
                "progress" to guide.progress,
                "amount" to guide.amount,
            ),
        )

        val targetLoc = guide.rawPosition?.let { resolveWorldLocation(it) }
        if (targetLoc != null && targetLoc.world == player.world) {
            val dx = targetLoc.x - player.location.x
            val dz = targetLoc.z - player.location.z
            val distance = sqrt(dx * dx + dz * dz).roundToInt()
            val arrow = relativeArrow(player.location.yaw, dx, dz)
            val direction = plugin.message(player, cardinalDirectionKey(dx, dz))
            player.sendMessage(
                plugin.message(
                    player,
                    "ec.guide.location_line",
                    "arrow" to arrow,
                    "direction" to direction,
                    "distance" to distance,
                    "x" to targetLoc.blockX,
                    "y" to targetLoc.blockY,
                    "z" to targetLoc.blockZ,
                ),
            )
            if (spawnTrail && distance >= 2) {
                spawnBreadcrumbTrail(player, targetLoc)
                player.sendMessage(plugin.message(player, "ec.guide.trail_hint"))
            }
        }
    }

    /** 目標種別に応じた動詞付きの目標文言を生成します。 */
    fun formatObjective(player: Player, kind: GuideActionKind, targetName: String): Component =
        plugin.message(player, verbKey(kind), "target" to targetName)

    /** 目標種別（ObjectiveKind）に応じた動詞付きの目標文言を生成します。 */
    fun formatObjective(player: Player, kind: ObjectiveKind, targetName: String): Component =
        formatObjective(player, toActionKind(kind), targetName)

    /** 依頼進行前のスナップショットを取得します。 */
    fun captureQuestSnapshot(profile: PlayerProfile, inventory: InventoryAccess): QuestProgressSnapshot {
        val active = mutableMapOf<String, Map<String, Int>>()
        val ready = mutableSetOf<String>()
        val completed = mutableSetOf<String>()
        profile.quests.forEach { (id, progress) ->
            if (progress.completed) {
                completed += id
            } else {
                active[id] = progress.objectives.toMap()
                if (plugin.game.quests.ready(profile, id, inventory)) ready += id
            }
        }
        return QuestProgressSnapshot(active, ready, completed)
    }

    /** 依頼進行の差分を検出し、進捗・報告可能通知・効果音を再生します。 */
    fun notifyQuestDelta(player: Player, profile: PlayerProfile, before: QuestProgressSnapshot, inventory: InventoryAccess) {
        val snapshot = plugin.snapshot
        val unknownTarget = plugin.message(player, "ec.quest.unknown_target").plainText()
        var playedProgressSound = false
        var playedReadySound = false

        profile.quests.forEach { (id, progress) ->
            val def = snapshot.world.quests[id] ?: return@forEach
            if (progress.completed) {
                if (id !in before.completed) {
                    playedReadySound = true
                }
                return@forEach
            }

            val questName = plugin.contentText(player, "quest", id, "name", def.name)
            val prevObj = before.active[id].orEmpty()
            def.objectives.forEach { obj ->
                val nowCount = objectiveCount(obj.kind, obj.id, obj.target, obj.amount, progress, inventory)
                val prevCount = prevObj[obj.id] ?: 0
                if (nowCount > prevCount) {
                    val targetName = questObjectiveLabel(obj.target, snapshot, unknownTarget) { type, targetId, ja ->
                        plugin.contentText(player, type, targetId, "name", ja)
                    }
                    val objText = formatObjective(player, obj.kind, targetName)
                    player.sendMessage(
                        plugin.message(
                            player,
                            "ec.quest.progress_update",
                            "name" to questName,
                            "objective" to objText,
                            "progress" to nowCount.coerceAtMost(obj.amount),
                            "amount" to obj.amount,
                        ),
                    )
                    playedProgressSound = true
                }
            }

            val isNowReady = plugin.game.quests.ready(profile, id, inventory)
            if (isNowReady && id !in before.ready) {
                val turnInNpc = findTurnInNpc(id, snapshot)?.let { plugin.contentText(player, "npc", it.id, "name", it.name) }
                    ?: unknownTarget
                player.sendMessage(
                    plugin.message(
                        player,
                        "ec.quest.ready_notice",
                        "name" to questName,
                        "npc" to turnInNpc,
                    ),
                )
                playedReadySound = true
            }
        }

        runCatching {
            when {
                playedReadySound -> player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 0.55f, 1.35f)
                playedProgressSound -> player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.25f)
            }
        }
    }

    /** 未完了クエスト報告時に残っている目標を親切に案内します。 */
    fun sendIncompleteHints(player: Player, profile: PlayerProfile, questId: String) {
        val snapshot = plugin.snapshot
        val def = snapshot.world.quests[questId] ?: return
        val progress = profile.quests[questId] ?: return
        val inv = plugin.inventory(player, persistRemoval = false)
        val unknownTarget = plugin.message(player, "ec.quest.unknown_target").plainText()
        def.objectives.forEach { obj ->
            val current = objectiveCount(obj.kind, obj.id, obj.target, obj.amount, progress, inv)
            if (current < obj.amount) {
                val targetName = questObjectiveLabel(obj.target, snapshot, unknownTarget) { type, targetId, ja ->
                    plugin.contentText(player, type, targetId, "name", ja)
                }
                val objText = formatObjective(player, obj.kind, targetName)
                player.sendMessage(
                    plugin.message(
                        player,
                        "ec.quest.incomplete_hint",
                        "objective" to objText,
                        "progress" to current,
                        "amount" to obj.amount,
                    ),
                )
            }
        }
    }

    /** プレイヤーから目的地方向へ金色の道しるべパーティクルを数秒間表示します（連続呼び出しを抑制）。 */
    fun spawnBreadcrumbTrail(player: Player, target: Location) {
        if (!plugin.isEnabled || target.world != player.world) return
        val now = System.currentTimeMillis()
        if (now < (trailCooldownUntil[player.uniqueId] ?: 0L)) return
        trailCooldownUntil[player.uniqueId] = now + TRAIL_COOLDOWN_MILLIS
        val dust = Particle.DustOptions(Color.fromRGB(255, 215, 70), 1.15f)
        for (pulse in 0 until 3) {
            runCatching {
                plugin.server.scheduler.runTaskLater(
                    plugin,
                    Runnable {
                        if (!player.isOnline || player.world != target.world) return@Runnable
                        val origin = player.location.clone().add(0.0, 0.9, 0.0)
                        val delta = target.toVector().subtract(origin.toVector()).setY(0.0)
                        val dist = delta.length()
                        if (dist < 1.5) return@Runnable
                        val dir = delta.normalize()
                        val maxLen = dist.coerceAtMost(14.0)
                        var step = 1.5
                        while (step <= maxLen) {
                            val pt = origin.clone().add(dir.clone().multiply(step))
                            player.spawnParticle(Particle.DUST, pt, 2, 0.08, 0.08, 0.08, 0.0, dust)
                            step += 1.25
                        }
                        player.playSound(player.location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.35f, 1.35f)
                    },
                    pulse * 15L,
                )
            }
        }
    }

    /** 言語切替時などに次回tickでサイドバー表示を確実に再描画します。 */
    fun invalidateDisplay(playerId: UUID) {
        lastRenderedLines.remove(playerId)
    }

    /** プレイヤー切断時に追跡状態とサイドボード参照を解放します。 */
    fun clearPlayer(playerId: UUID) {
        lastAreaByPlayer.remove(playerId)
        val board = scoreboards.remove(playerId)
        lastRenderedLines.remove(playerId)
        guideCooldownUntil.remove(playerId)
        trailCooldownUntil.remove(playerId)
        if (board != null) {
            runCatching {
                val player = Bukkit.getPlayer(playerId)
                if (player != null && player.scoreboard === board) {
                    player.scoreboard = Bukkit.getScoreboardManager().mainScoreboard
                }
            }
        }
    }

    /** プラグイン終了時に全プレイヤーのサイドボードをメインへ戻しキャッシュをクリアします。 */
    fun close() {
        scoreboards.keys.toList().forEach(::clearPlayer)
        lastAreaByPlayer.clear()
        scoreboards.clear()
        lastRenderedLines.clear()
        guideCooldownUntil.clear()
        trailCooldownUntil.clear()
    }

    private fun currentArea(player: Player): Area? {
        val worldName = player.world.name
        return plugin.snapshot.world.areas.values
            .mapNotNull(plugin.activeWorldProjection.projector::projectArea)
            .firstOrNull { it.contains(worldName, player.x, player.y, player.z) }
    }

    private fun updateAreaBanner(player: Player, area: Area?) {
        val previousId = lastAreaByPlayer[player.uniqueId]
        val currentId = area?.id
        if (previousId == currentId) return
        lastAreaByPlayer[player.uniqueId] = currentId
        if (area == null) return

        val areaName = plugin.contentText(player, "area", area.id, "name", area.name)
        val subtitle = if (area.safe) {
            plugin.message(player, "ec.area.enter_safe")
        } else {
            val enemies = plugin.snapshot.world.enemiesByArea[area.id].orEmpty()
            val minLv = enemies.minOfOrNull { it.level } ?: 1
            val maxLv = enemies.maxOfOrNull { it.level } ?: minLv
            plugin.message(player, "ec.area.enter_field", "min" to minLv, "max" to maxLv)
        }
        runCatching {
            player.showTitle(
                Title.title(
                    Component.text(areaName),
                    subtitle,
                    Title.Times.times(Duration.ofMillis(350), Duration.ofMillis(2000), Duration.ofMillis(650)),
                ),
            )
            player.playSound(player.location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.45f, if (area.safe) 1.2f else 0.85f)
        }
    }

    private fun updateScoreboard(player: Player, area: Area?, guide: GuideTarget?, targetLoc: Location?) {
        val areaName = area?.let { plugin.contentText(player, "area", it.id, "name", it.name) }
            ?: plugin.message(player, "ec.area.unknown").plainText()
        val lines = mutableListOf<String>()
        lines += plugin.message(player, "ec.guide.scoreboard_area", "area" to areaName).plainText()
        if (guide != null) {
            lines += "✦ ${guide.questName}".take(35)
            val obj = formatObjective(player, guide.actionKind, guide.targetName).plainText()
            lines += "▸ $obj (${guide.progress}/${guide.amount})".take(35)
            if (targetLoc != null && targetLoc.world == player.world) {
                val dx = targetLoc.x - player.location.x
                val dz = targetLoc.z - player.location.z
                val dist = sqrt(dx * dx + dz * dz).roundToInt()
                val arrow = relativeArrow(player.location.yaw, dx, dz)
                lines += "$arrow ${dist}m (${targetLoc.blockX}, ${targetLoc.blockZ})"
            }
        }
        lines += plugin.message(player, "ec.guide.scoreboard_controls").plainText()

        if (lastRenderedLines[player.uniqueId] == lines) return
        lastRenderedLines[player.uniqueId] = lines

        runCatching {
            val manager = Bukkit.getScoreboardManager()
            val board = scoreboards.getOrPut(player.uniqueId) { manager.newScoreboard }
            val title = plugin.message(player, "ec.guide.scoreboard_title")
            val objective = board.getObjective("ec_guide")?.also { it.displayName(title) }
                ?: board.registerNewObjective("ec_guide", Criteria.DUMMY, title).apply {
                    displaySlot = DisplaySlot.SIDEBAR
                }
            board.entries.toList().forEach(board::resetScores)
            val seen = mutableSetOf<String>()
            lines.forEachIndexed { idx, raw ->
                var entry = raw.ifBlank { " " }.take(35)
                while (!seen.add(entry) && entry.length < 40) {
                    entry += " "
                }
                objective.getScore(entry).score = lines.size - idx
            }
            if (player.scoreboard !== board) {
                player.scoreboard = board
            }
        }
    }

    private fun spawnNpcBeacon(player: Player, npcLoc: Location, readyToReport: Boolean) {
        runCatching {
            val color = if (readyToReport) Color.fromRGB(95, 245, 130) else Color.fromRGB(255, 215, 65)
            val dust = Particle.DustOptions(color, 1.3f)
            val above = npcLoc.clone().add(0.0, 2.35, 0.0)
            player.spawnParticle(Particle.DUST, above, 5, 0.18, 0.18, 0.18, 0.0, dust)
            player.spawnParticle(Particle.END_ROD, above.clone().add(0.0, 0.2, 0.0), 1, 0.05, 0.08, 0.05, 0.01)
        }
    }

    private fun resolveWorldLocation(raw: Position): Location? {
        val projected = plugin.projectedPosition(raw) ?: plugin.activeWorldProjection.projector.projectHorizontal(raw) ?: raw
        val world = Bukkit.getWorld(projected.world) ?: return null
        return Location(world, projected.x, projected.y, projected.z)
    }

    companion object {
        private const val NPC_BEACON_RANGE_SQUARED = 48.0 * 48.0
        private const val GUIDE_COOLDOWN_MILLIS = 1_500L
        private const val TRAIL_COOLDOWN_MILLIS = 1_500L

        internal fun toActionKind(kind: ObjectiveKind): GuideActionKind = when (kind) {
            ObjectiveKind.TALK -> GuideActionKind.TALK
            ObjectiveKind.KILL -> GuideActionKind.KILL
            ObjectiveKind.COLLECT -> GuideActionKind.COLLECT
            ObjectiveKind.EXPLORE -> GuideActionKind.EXPLORE
            ObjectiveKind.CATCH_FISH -> GuideActionKind.CATCH_FISH
            ObjectiveKind.GATHER_NODE -> GuideActionKind.GATHER_NODE
            ObjectiveKind.ENHANCE_GEAR -> GuideActionKind.ENHANCE_GEAR
        }

        internal fun verbKey(kind: GuideActionKind): String = when (kind) {
            GuideActionKind.TALK -> "ec.quest.verb_talk"
            GuideActionKind.KILL -> "ec.quest.verb_kill"
            GuideActionKind.COLLECT -> "ec.quest.verb_collect"
            GuideActionKind.EXPLORE -> "ec.quest.verb_explore"
            GuideActionKind.CATCH_FISH -> "ec.quest.verb_fish"
            GuideActionKind.GATHER_NODE -> "ec.quest.verb_gather"
            GuideActionKind.ENHANCE_GEAR -> "ec.quest.verb_enhance"
            GuideActionKind.REPORT -> "ec.quest.verb_report"
            GuideActionKind.ACCEPT -> "ec.quest.verb_accept"
        }

        /** プレイヤーの視線Yawに対する相対方角矢印（↑↗→↘↓↙←↖）を返します。 */
        internal fun relativeArrow(playerYawDegrees: Float, dx: Double, dz: Double): String {
            // Minecraft yaw: 0 = South (+Z), 90 = West (-X), 180 = North (-Z), 270 = East (+X)
            val targetYaw = Math.toDegrees(atan2(-dx, dz))
            var diff = (targetYaw - playerYawDegrees) % 360.0
            if (diff < 0) diff += 360.0
            val sector = ((diff + 22.5) / 45.0).toInt() % 8
            return when (sector) {
                0 -> "↑"
                1 -> "↗"
                2 -> "→"
                3 -> "↘"
                4 -> "↓"
                5 -> "↙"
                6 -> "←"
                else -> "↖"
            }
        }

        /** 8方位の翻訳キーを返します。 */
        internal fun cardinalDirectionKey(dx: Double, dz: Double): String {
            // North = -Z, East = +X, South = +Z, West = -X
            val angle = (Math.toDegrees(atan2(dx, -dz)) + 360.0) % 360.0
            val sector = ((angle + 22.5) / 45.0).toInt() % 8
            return when (sector) {
                0 -> "ec.guide.dir_n"
                1 -> "ec.guide.dir_ne"
                2 -> "ec.guide.dir_e"
                3 -> "ec.guide.dir_se"
                4 -> "ec.guide.dir_s"
                5 -> "ec.guide.dir_sw"
                6 -> "ec.guide.dir_w"
                else -> "ec.guide.dir_nw"
            }
        }

        /** 指定クエストの報告担当NPCを会話定義から逆引きします。 */
        internal fun findTurnInNpc(questId: String, snapshot: ContentSnapshot): NpcDefinition? {
            for (dialogue in snapshot.world.dialogues.values) {
                val completes = dialogue.nodes.values.any { node ->
                    node.choices.any { choice ->
                        choice.actions.any { it.kind == "COMPLETE_QUEST" && it.target == questId }
                    }
                }
                if (completes) {
                    snapshot.world.npcs.values.firstOrNull { it.dialogue == dialogue.id }?.let { return it }
                }
            }
            return null
        }

        /** 指定クエストの受注担当NPCを会話定義から逆引きします。 */
        internal fun findStarterNpc(questId: String, snapshot: ContentSnapshot): NpcDefinition? {
            for (dialogue in snapshot.world.dialogues.values) {
                val starts = dialogue.nodes.values.any { node ->
                    node.choices.any { choice ->
                        choice.actions.any { it.kind == "START_QUEST" && it.target == questId }
                    }
                }
                if (starts) {
                    snapshot.world.npcs.values.firstOrNull { it.dialogue == dialogue.id }?.let { return it }
                }
            }
            return null
        }

        /** 進行中の依頼、または次に受注できるおすすめ依頼からガイド目標を解決します。 */
        internal fun resolveGuide(
            profile: PlayerProfile,
            snapshot: ContentSnapshot,
            inventory: InventoryAccess,
            playerLocation: Location? = null,
            translate: (String, String, String) -> String = { _, _, ja -> ja },
        ): GuideTarget? {
            val activeEntries = profile.quests.entries
                .filter { !it.value.completed }
                .mapNotNull { (id, progress) -> snapshot.world.quests[id]?.let { it to progress } }
                .sortedWith(compareBy({ questPriority(it.first.id) }, { it.first.id }))

            for ((quest, progress) in activeEntries) {
                val questName = translate("quest", quest.id, quest.name)
                val incomplete = quest.objectives.firstOrNull { obj ->
                    objectiveCount(obj.kind, obj.id, obj.target, obj.amount, progress, inventory) < obj.amount
                }
                if (incomplete != null) {
                    val count = objectiveCount(incomplete.kind, incomplete.id, incomplete.target, incomplete.amount, progress, inventory)
                    val targetName = questObjectiveLabel(incomplete.target, snapshot, incomplete.target, translate)
                    val (pos, isNpc) = resolveObjectivePosition(incomplete.kind, incomplete.target, snapshot, playerLocation)
                    return GuideTarget(
                        questId = quest.id,
                        questName = questName,
                        actionKind = toActionKind(incomplete.kind),
                        targetId = incomplete.target,
                        targetName = targetName,
                        progress = count,
                        amount = incomplete.amount,
                        rawPosition = pos,
                        isNpcTarget = isNpc,
                    )
                }
                val turnInNpc = findTurnInNpc(quest.id, snapshot)
                return GuideTarget(
                    questId = quest.id,
                    questName = questName,
                    actionKind = GuideActionKind.REPORT,
                    targetId = turnInNpc?.id ?: quest.id,
                    targetName = turnInNpc?.let { translate("npc", it.id, it.name) } ?: questName,
                    progress = 1,
                    amount = 1,
                    rawPosition = turnInNpc?.position,
                    isNpcTarget = turnInNpc != null,
                )
            }

            // アクティブ依頼がない場合は、次に受注可能なメイン/導入依頼を提案する
            val nextQuest = findNextAvailableQuest(profile, snapshot) ?: return null
            val starterNpc = findStarterNpc(nextQuest.id, snapshot) ?: return null
            return GuideTarget(
                questId = nextQuest.id,
                questName = translate("quest", nextQuest.id, nextQuest.name),
                actionKind = GuideActionKind.ACCEPT,
                targetId = starterNpc.id,
                targetName = translate("npc", starterNpc.id, starterNpc.name),
                progress = 0,
                amount = 1,
                rawPosition = starterNpc.position,
                isNpcTarget = true,
                isRecommendedNext = true,
            )
        }

        internal fun findNextAvailableQuest(profile: PlayerProfile, snapshot: ContentSnapshot): QuestDefinition? =
            snapshot.world.quests.values
                .filter { q ->
                    q.id !in profile.quests &&
                        q.condition.matches(profile) &&
                        findStarterNpc(q.id, snapshot) != null
                }
                .sortedWith(compareBy({ questPriority(it.id) }, { it.id }))
                .firstOrNull()

        private fun questPriority(questId: String): Int = when {
            questId.startsWith("ec:quest_m") -> 0
            questId.startsWith("ec:quest_c2_m") -> 1
            questId.startsWith("ec:quest_s") -> 2
            questId.startsWith("ec:quest_early") -> 3
            else -> 4
        }

        private fun objectiveCount(
            kind: ObjectiveKind,
            objectiveId: String,
            target: String,
            amount: Int,
            progress: QuestProgress,
            inventory: InventoryAccess,
        ): Int = if (kind == ObjectiveKind.COLLECT) {
            inventory.count(target).coerceAtMost(amount)
        } else {
            progress.objectives.getOrDefault(objectiveId, 0).coerceAtMost(amount)
        }

        private fun resolveObjectivePosition(
            kind: ObjectiveKind,
            target: String,
            snapshot: ContentSnapshot,
            playerLocation: Location?,
        ): Pair<Position?, Boolean> {
            fun nearest(candidates: List<Position>): Position? {
                if (candidates.isEmpty()) return null
                val loc = playerLocation ?: return candidates.first()
                return candidates.minByOrNull { pos ->
                    val dx = pos.x - loc.x
                    val dz = pos.z - loc.z
                    dx * dx + dz * dz
                }
            }
            return when (kind) {
                ObjectiveKind.TALK -> snapshot.world.npcs[target]?.position to true
                ObjectiveKind.KILL -> nearest(snapshot.world.enemies[target]?.spawns?.map { it.position }.orEmpty()) to false
                ObjectiveKind.EXPLORE -> snapshot.world.areas[target]?.let { area ->
                    Position(area.world, (area.minX + area.maxX) / 2.0, 64.0, (area.minZ + area.maxZ) / 2.0)
                } to false
                ObjectiveKind.GATHER_NODE -> {
                    val direct = snapshot.world.gatheringNodes[target]?.locations?.map {
                        Position(it.world, it.x.toDouble(), it.y.toDouble(), it.z.toDouble())
                    }.orEmpty()
                    val fallback = if (direct.isNotEmpty()) direct else snapshot.world.gatheringNodes.values.flatMap { node ->
                        node.locations.map { Position(it.world, it.x.toDouble(), it.y.toDouble(), it.z.toDouble()) }
                    }
                    nearest(fallback) to false
                }
                ObjectiveKind.CATCH_FISH -> {
                    val fish = snapshot.world.fishSpecies[target]
                    val spots = snapshot.world.fishingSpots.values
                        .filter { spot -> fish == null || spot.area in fish.habitats }
                        .map { it.center }
                    nearest(spots) to false
                }
                ObjectiveKind.COLLECT -> {
                    val droppingEnemies = snapshot.world.enemies.values.filter { enemy ->
                        val dropTableId = snapshot.documents[enemy.id]?.get("droptable") as? String ?: return@filter false
                        snapshot.drops.find(dropTableId)?.entries?.any { it.item == target } == true
                    }
                    val enemyPos = nearest(droppingEnemies.flatMap { it.spawns.map { s -> s.position } })
                    if (enemyPos != null) return enemyPos to false
                    val shopNpc = snapshot.world.npcs.values.firstOrNull { npc ->
                        val shopId = npc.shop ?: return@firstOrNull false
                        snapshot.world.shops[shopId]?.entries?.any { it.item == target } == true
                    }
                    if (shopNpc != null) return shopNpc.position to true
                    null to false
                }
                ObjectiveKind.ENHANCE_GEAR -> snapshot.world.npcs["ec:npc_garudo"]?.position to true
            }
        }
    }
}
