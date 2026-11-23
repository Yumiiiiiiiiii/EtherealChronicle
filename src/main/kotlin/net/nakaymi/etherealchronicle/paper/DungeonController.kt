package net.nakaymi.etherealchronicle.paper

import net.nakaymi.etherealchronicle.core.DungeonBoxScanResult
import net.nakaymi.etherealchronicle.core.DungeonDefinition
import net.nakaymi.etherealchronicle.core.DungeonInstanceBounds
import net.nakaymi.etherealchronicle.core.DungeonInstanceCenter
import net.nakaymi.etherealchronicle.core.DungeonSlotState
import net.nakaymi.etherealchronicle.core.DungeonSlotTracker
import net.nakaymi.etherealchronicle.core.ObjectiveKind
import net.nakaymi.etherealchronicle.core.OverworldDungeonInstanceLayout
import net.nakaymi.etherealchronicle.core.OverworldDungeonPortal
import net.nakaymi.etherealchronicle.core.OverworldSiteStatus
import net.nakaymi.etherealchronicle.core.canAcknowledgeDungeonReturn
import net.nakaymi.etherealchronicle.core.firstClearRanks
import net.nakaymi.etherealchronicle.core.recordChallenge
import net.nakaymi.etherealchronicle.core.returnToMailbox
import net.nakaymi.etherealchronicle.data.DungeonReturnRecord
import net.nakaymi.etherealchronicle.data.DungeonReturnStore
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.player.PlayerTeleportEvent
import java.util.UUID

/** 制限数付きの複数ダンジョン instance、wave、帰還地点、空中スロットのライフサイクルを管理します。 */
class DungeonController internal constructor(
    private val plugin: RpgPlugin,
    private val adventure: AdventureController,
    private val enemies: EnemyController,
    private val clock: () -> Long,
) : Listener {
    constructor(
        plugin: RpgPlugin,
        adventure: AdventureController,
        enemies: EnemyController,
    ) : this(plugin, adventure, enemies, ::monotonicMillis)

    private data class ArenaPalette(val floor: Material, val wall: Material, val inner: Material) {
        companion object {
            fun resolve(names: List<String>) = ArenaPalette(
                Material.valueOf(names[DungeonArenaLayout.OUTER_FLOOR_PALETTE_INDEX]),
                Material.valueOf(names[DungeonArenaLayout.WALL_PALETTE_INDEX]),
                Material.valueOf(names[DungeonArenaLayout.INNER_FLOOR_PALETTE_INDEX]),
            )
        }
    }

    /** 稼働中ダンジョンインスタンスの状態と参加者を保持します。 */
    data class Instance(
        val slot: Int,
        val definition: DungeonDefinition,
        val members: MutableSet<UUID>,
        val returns: Map<UUID, Location>,
        val started: Long,
        var boss: UUID? = null,
        var completed: Boolean = false,
        var waveIndex: Int = 0,
        val entities: MutableSet<UUID> = mutableSetOf(),
        val startedAtMillis: Long = monotonicMillis(),
        val overworld: Boolean = false,
        var timingOut: Boolean = false,
        val unreturnedMembers: MutableSet<UUID> = if (overworld) members.toMutableSet() else mutableSetOf(),
    )

    /** オーバーワールド入場フローの非同期ステップ間で共有する不変セッション情報です。 */
    private data class OverworldEntrySession(
        val leader: Player,
        val dungeonId: String,
        val definition: DungeonDefinition,
        val portal: OverworldDungeonPortal,
        val world: World,
        val schematic: SchematicData,
        val players: List<Player>,
        val returns: Map<UUID, Location>,
        val candidates: List<Int>,
        val scanner: OverworldDungeonScanner,
        val placement: OverworldDungeonPlacement,
    )

    private val instances = mutableMapOf<Int, Instance>()
    private var tick = 0L
    private val builtPalette = mutableMapOf<Int, List<String>>()
    private val loadedSchematics = mutableMapOf<String, SchematicData>()
    private val schematicSlots = mutableSetOf<Int>()
    private val slotTracker = DungeonSlotTracker()
    private val pendingReturns = mutableMapOf<UUID, DungeonReturnRecord>()
    private val pendingReturnFreezeSince = mutableMapOf<UUID, Long>()
    private val recoveringPlayers = mutableSetOf<UUID>()
    private val enteringPlayers = mutableSetOf<UUID>()
    private val leavingPlayers = mutableSetOf<UUID>()
    private val journalWaiters = mutableListOf<() -> Unit>()
    private val placedOperations = mutableMapOf<Int, OverworldDungeonPlacement.Operation>()
    private val cleaningOperations = mutableMapOf<Int, OverworldDungeonPlacement.Operation>()
    private var overworldLayout: OverworldDungeonInstanceLayout = OverworldDungeonInstanceLayout()
    private var overworldLayoutWorldName: String? = null
    private var returnStore: DungeonReturnStore? = null
    private var journalReady = false
    private var journalFailed = false
    private var shuttingDown = false
    private var placement: OverworldDungeonPlacement? = null
    private var scanner: OverworldDungeonScanner? = null

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
        adventure.enterDungeon = { player, id -> enter(player, id) }
        if (plugin.activeWorldProjection.registry.isOverworldMode()) {
            initializeOverworldRuntime()
        } else {
            journalReady = true
        }
    }

    /** オーバーワールド稼働時のみ帰還ジャーナルと空中レイアウト検証を遅延初期化します。 */
    private fun initializeOverworldRuntime() {
        Bukkit.getWorld(plugin.activeWorldProjection.registry.activeWorldName())?.let { world ->
            resolveOverworldLayout(world, logValidation = true)
        }
        val store = DungeonReturnStore(
            file = plugin.dataFolder.toPath().resolve(RETURN_JOURNAL_FILE),
            onWarning = { message -> plugin.logger.warning(message) },
        )
        returnStore = store
        store.readAll().whenComplete { records, error ->
            plugin.sync {
                if (error != null) {
                    plugin.logger.severe(
                        "Dungeon return journal unavailable; overworld dungeon entry disabled: ${error.message}",
                    )
                    journalFailed = true
                    journalReady = true
                    drainJournalWaiters()
                } else {
                    pendingReturns.putAll(records)
                    journalReady = true
                    drainJournalWaiters()
                }
            }
        }
    }

    private fun drainJournalWaiters() {
        val waiters = journalWaiters.toList()
        journalWaiters.clear()
        waiters.forEach { it() }
    }

    /** 稼働中・予約中・撤去中の全ダンジョン枠数を二重計上なしで返します。 */
    fun activeCount(): Int = (instances.keys + slotTracker.occupiedSlots()).size

    /** 指定スロットのオーバーワールド状態（テスト・診断用）を返します。 */
    internal fun slotState(slot: Int): DungeonSlotState? = slotTracker.stateOf(slot)

    /** 帰還ジャーナルの読込（または失敗確定）が完了しているかを返します。 */
    fun isJournalReady(): Boolean = journalReady

    /** 帰還ジャーナル破損によりオーバーワールドダンジョン入場が無効化されているかを返します。 */
    fun isJournalFailed(): Boolean = journalFailed

    /** 帰還記録が読み込まれるまで入場・ログイン時の転送を保留します。 */
    fun whenJournalReady(action: () -> Unit) {
        if (!plugin.activeWorldProjection.registry.isOverworldMode() || journalReady) action()
        else journalWaiters += action
    }

    /** 未確定記録の所有者は初回スポーン処理の対象から外します。 */
    fun hasPendingReturn(id: UUID): Boolean = plugin.activeWorldProjection.registry.isOverworldMode() &&
        (!journalReady || id in pendingReturns)

    /** リスポーン処理の振り分けに、現在の参加状態を同期的に公開します。 */
    fun containsPlayer(id: UUID): Boolean = instances.values.any { id in it.members }

    internal fun currentWave(slot: Int) = instances[slot]?.waveIndex
    internal fun currentBoss(slot: Int) = instances[slot]?.boss

    /** テストや管理診断用に現在のオーバーワールドレイアウトを返します。 */
    internal fun currentOverworldLayout(): OverworldDungeonInstanceLayout = overworldLayout

    /** 参加者・解放条件・instance枠を検証してダンジョンへ入場させます。 */
    fun enter(player: Player, dungeonId: String = DEFAULT_DUNGEON_ID) {
        if (plugin.activeWorldProjection.registry.isOverworldMode()) {
            enterOverworld(player, dungeonId)
            return
        }
        val definition = plugin.snapshot.world.dungeons[dungeonId] ?: error("ec.error.dungeon_missing: $dungeonId")
        require(instances.values.none { player.uniqueId in it.members }) { "ec.error.dungeon_already_inside" }
        val party = adventure.parties.party(player.uniqueId)
        require(party == null || party.leader == player.uniqueId) { "ec.error.party_leader_required" }
        val players = adventure.parties.members(player.uniqueId).map {
            Bukkit.getPlayer(it) ?: error("ec.error.party_not_online")
        }
        require(players.all { p ->
            p.world == player.world &&
                p.location.isWithinBlocks(player.location, PaperDistance.DUNGEON_PARTY_GATHER_BLOCKS) &&
                plugin.profiles[p.uniqueId]?.classId != null
        }) { "ec.error.party_gather" }
        require(players.all { definition.unlockFlag in plugin.profiles.getValue(it.uniqueId).flags }) {
            "ec.error.dungeon_unlock_required"
        }
        val world = Bukkit.getWorld(DemoWorld.NAME) ?: error("ec.error.world_unavailable")
        val slot = (0 until plugin.snapshot.world.tuning.instanceCap).firstOrNull { it !in instances }
            ?: error("ec.error.dungeon_full")
        val instance = Instance(
            slot = slot,
            definition = definition,
            members = players.map { it.uniqueId }.toMutableSet(),
            returns = players.associate { it.uniqueId to it.location.clone() },
            started = tick,
        )
        val origin = DungeonArenaLayout.originForSlot(slot)
        val x = origin.x
        val z = origin.z
        val schematicPlaced = placeDungeonSchematic(world, definition.id, x, z)
        if (!schematicPlaced && dungeonPaletteNeedsBuild(builtPalette[slot], definition.palette)) {
            val palette = ArenaPalette.resolve(definition.palette)
            for (dx in -DungeonArenaLayout.PLATFORM_RADIUS_BLOCKS..DungeonArenaLayout.PLATFORM_RADIUS_BLOCKS) {
                for (dz in -DungeonArenaLayout.PLATFORM_RADIUS_BLOCKS..DungeonArenaLayout.PLATFORM_RADIUS_BLOCKS) {
                    val edge = kotlin.math.abs(dx) == DungeonArenaLayout.PLATFORM_RADIUS_BLOCKS ||
                        kotlin.math.abs(dz) == DungeonArenaLayout.PLATFORM_RADIUS_BLOCKS
                    val center = kotlin.math.abs(dx) <= DungeonArenaLayout.INNER_FLOOR_RADIUS_BLOCKS &&
                        kotlin.math.abs(dz) <= DungeonArenaLayout.INNER_FLOOR_RADIUS_BLOCKS
                    world.getBlockAt(x + dx, DungeonArenaLayout.FLOOR_Y, z + dz).type =
                        if (center) palette.inner else palette.floor
                    if (edge) {
                        for (y in DungeonArenaLayout.WALL_BOTTOM_Y..DungeonArenaLayout.WALL_TOP_Y) {
                            world.getBlockAt(x + dx, y, z + dz).type = palette.wall
                        }
                    }
                }
            }
            builtPalette[slot] = definition.palette.toList()
        }
        instances[slot] = instance
        try {
            spawnWave(instance, world, x, z)
        } catch (error: Exception) {
            close(instance)
            throw error
        }
        players.forEach { p ->
            plugin.profiles[p.uniqueId]?.let { profile ->
                plugin.game.quests.event(
                    profile,
                    ObjectiveKind.EXPLORE,
                    definition.area,
                    1,
                    plugin.inventory(p),
                    false,
                )
                plugin.markDirty(profile.uuid)
            }
        }
        players.forEach { member ->
            member.teleport(
                Location(
                    world,
                    x.toDouble(),
                    DungeonArenaLayout.PLAYER_SPAWN_Y.toDouble(),
                    (z + DungeonArenaLayout.PLAYER_SPAWN_Z_OFFSET).toDouble(),
                ),
            )
            member.sendMessage(plugin.message(member, "ec.dungeon.entry", "message" to definition.entryMessage))
        }
    }

    /** 地上入口から空中枠を全域検査し、帰還記録の永続化後だけ転送します。 */
    private fun enterOverworld(player: Player, dungeonId: String) {
        val session = prepareOverworldEntry(player, dungeonId)
        enteringPlayers.addAll(session.returns.keys)
        tryCandidate(session, 0)
    }

    /** オーバーワールド入場の前提条件（ジャーナル・権限・座標・境界・候補スロット）を一括検証します。 */
    private fun prepareOverworldEntry(player: Player, dungeonId: String): OverworldEntrySession {
        val registry = plugin.activeWorldProjection.registry
        check(registry.isReady() && journalReady && !journalFailed && returnStore != null) {
            "ec.error.world_unavailable"
        }
        val portal = registry.portalForDungeon(dungeonId) ?: error("ec.error.world_unavailable")
        check(registry.siteForArea(portal.areaId)?.status == OverworldSiteStatus.READY) {
            "ec.error.world_unavailable"
        }
        val definition = plugin.snapshot.world.dungeons[dungeonId] ?: error("ec.error.dungeon_missing: $dungeonId")
        val party = adventure.parties.party(player.uniqueId)
        require(party == null || party.leader == player.uniqueId) { "ec.error.party_leader_required" }
        val players = adventure.parties.members(player.uniqueId).map {
            Bukkit.getPlayer(it) ?: error("ec.error.party_not_online")
        }
        require(players.all { p ->
            p.world == player.world &&
                p.location.isWithinBlocks(player.location, PaperDistance.DUNGEON_PARTY_GATHER_BLOCKS) &&
                plugin.profiles[p.uniqueId]?.classId != null
        }) { "ec.error.party_gather" }
        require(players.all { definition.unlockFlag in plugin.profiles.getValue(it.uniqueId).flags }) {
            "ec.error.dungeon_unlock_required"
        }
        require(players.none {
            it.uniqueId in pendingReturns || it.uniqueId in enteringPlayers || containsPlayer(it.uniqueId)
        }) { "ec.error.dungeon_already_inside" }
        val world = Bukkit.getWorld(registry.activeWorldName()) ?: error("ec.error.world_unavailable")
        val layout = resolveOverworldLayout(world, logValidation = false)
        check(player.world == world && layout.fitsHeight(world.minHeight, world.maxHeight)) {
            "ec.error.world_unavailable"
        }
        val path = WorldSchematicLayout.dungeonPath(dungeonId) ?: error("ec.error.dungeon_missing: $dungeonId")
        val schematic = loadedSchematics[path]
            ?: SchematicResources.load(path)?.also { loadedSchematics[path] = it }
            ?: error("ec.error.world_unavailable")
        check(
            schematic.width == OverworldDungeonInstanceLayout.ARENA_WIDTH &&
                schematic.height == OverworldDungeonInstanceLayout.ARENA_HEIGHT &&
                schematic.length == OverworldDungeonInstanceLayout.ARENA_WIDTH,
        ) { "ec.error.world_unavailable" }
        val cap = plugin.snapshot.world.tuning.instanceCap
            .coerceAtMost(OverworldDungeonInstanceLayout.MAX_CANDIDATE_SLOTS)
        val border = world.worldBorder
        val fitting = layout.fittingSlots(cap, border.center.x, border.center.z, border.size)
        check(fitting.isNotEmpty()) { "ec.error.world_unavailable" }
        val candidates = fitting.filter { slot ->
            slotTracker.stateOf(slot) == null &&
                slot !in instances &&
                !hasPendingReturnAtSlot(layout, slot)
        }
        check(candidates.isNotEmpty()) { "ec.error.dungeon_full" }
        val returns = players.associate { member ->
            member.uniqueId to Location(
                world,
                portal.exitX + 0.5,
                portal.exitY.toDouble(),
                portal.exitZ + 0.5,
                member.location.yaw,
                member.location.pitch,
            )
        }
        check(returns.values.all { destination -> SafeStandingSpot.isSafe(destination) }) {
            "ec.error.world_unavailable"
        }
        val currentScanner = scanner ?: OverworldDungeonScanner(plugin, world).also { scanner = it }
        val currentPlacement = placement ?: OverworldDungeonPlacement(plugin, world).also { placement = it }
        return OverworldEntrySession(
            leader = player,
            dungeonId = dungeonId,
            definition = definition,
            portal = portal,
            world = world,
            schematic = schematic,
            players = players,
            returns = returns,
            candidates = candidates,
            scanner = currentScanner,
            placement = currentPlacement,
        )
    }

    /** 候補スロットを先頭から原子的に予約し、空域検査と配置を順次進めます。 */
    private fun tryCandidate(session: OverworldEntrySession, index: Int) {
        if (!plugin.isEnabled) {
            finishEntry(session)
            return
        }
        if (index >= session.candidates.size) {
            finishEntry(session)
            notifyEntryFailure(session, "ec.error.dungeon_full")
            return
        }
        val slot = session.candidates[index]
        if (!slotTracker.tryReserve(slot)) {
            tryCandidate(session, index + 1)
            return
        }
        if (slot in instances || hasPendingReturnAtSlot(overworldLayout, slot)) {
            slotTracker.release(slot)
            tryCandidate(session, index + 1)
            return
        }
        val bounds = overworldLayout.bounds(slot)
        lateinit var scanOperation: OverworldDungeonScanner.Operation
        try {
            scanOperation = session.scanner.scan(bounds, SCAN_BUDGET) { result, failure ->
                handleScanResult(session, index, slot, bounds, scanOperation, result, failure)
            }
        } catch (error: Exception) {
            slotTracker.release(slot)
            plugin.logger.warning("Dungeon slot scan could not start for slot $slot: ${error.message}")
            tryCandidate(session, index + 1)
        }
    }

    /** スキャン結果を受け、空き枠ならチャンクチケットを配置工程へ引き継ぎます。 */
    private fun handleScanResult(
        session: OverworldEntrySession,
        index: Int,
        slot: Int,
        bounds: DungeonInstanceBounds,
        scanOperation: OverworldDungeonScanner.Operation,
        result: DungeonBoxScanResult?,
        failure: Throwable?,
    ) {
        if (failure != null || result != DungeonBoxScanResult.EMPTY) {
            scanOperation.release()
            slotTracker.release(slot)
            if (failure != null) {
                plugin.logger.warning("Dungeon slot scan failed for slot $slot: ${failure.message}")
            }
            tryCandidate(session, index + 1)
            return
        }
        if (!isEntryStillValid(session)) {
            scanOperation.release()
            slotTracker.release(slot)
            finishEntry(session)
            notifyEntryFailure(session, "ec.error.world_unavailable")
            return
        }
        lateinit var placementOperation: OverworldDungeonPlacement.Operation
        try {
            placementOperation = session.placement.place(
                bounds = bounds,
                schematic = session.schematic,
                scanOperation = scanOperation,
            ) { success, error ->
                handlePlacementResult(session, index, slot, placementOperation, success, error)
            }
        } catch (error: Exception) {
            scanOperation.release()
            slotTracker.release(slot)
            plugin.logger.warning("Dungeon placement could not start for slot $slot: ${error.message}")
            tryCandidate(session, index + 1)
        }
    }

    /** スキーマ配置完了後に帰還ジャーナルを永続化し、失敗時は枠を解放して次候補へ進みます。 */
    private fun handlePlacementResult(
        session: OverworldEntrySession,
        index: Int,
        slot: Int,
        operation: OverworldDungeonPlacement.Operation,
        success: Boolean,
        error: Throwable?,
    ) {
        if (!success) {
            slotTracker.release(slot)
            if (error != null) {
                plugin.logger.warning("Dungeon placement failed for slot $slot: ${error.message}")
            }
            tryCandidate(session, index + 1)
            return
        }
        placedOperations[slot] = operation
        if (!isEntryStillValid(session)) {
            finishEntry(session)
            cleanupOrAbandonSlot(slot)
            notifyEntryFailure(session, "ec.error.world_unavailable")
            return
        }
        val center = overworldLayout.center(slot)
        val records = session.returns.mapValues { (_, location) ->
            DungeonReturnRecord(
                dungeonId = session.dungeonId,
                world = session.world.name,
                x = location.x,
                y = location.y,
                z = location.z,
                yaw = location.yaw,
                pitch = location.pitch,
                slotX = center.x,
                slotY = center.y,
                slotZ = center.z,
            )
        }
        val store = returnStore
        if (store == null || journalFailed) {
            finishEntry(session)
            cleanupOrAbandonSlot(slot)
            notifyEntryFailure(session, "ec.error.world_unavailable")
            return
        }
        store.putAll(records).whenComplete { _, writeError ->
            plugin.sync {
                handleReturnPersisted(session, slot, center, records, writeError)
            }
        }
    }

    /** 帰還記録の永続化完了後、枠を ACTIVE 化してウェーブ生成とプレイヤー転送を行います。 */
    private fun handleReturnPersisted(
        session: OverworldEntrySession,
        slot: Int,
        center: DungeonInstanceCenter,
        records: Map<UUID, DungeonReturnRecord>,
        writeError: Throwable?,
    ) {
        if (writeError != null) {
            finishEntry(session)
            cleanupOrAbandonSlot(slot)
            plugin.logger.severe("Dungeon return journal write failed: ${writeError.message}")
            notifyEntryFailure(session, "ec.error.world_unavailable")
            return
        }
        pendingReturns.putAll(records)
        finishEntry(session)
        if (!isEntryStillValid(session, checkPending = false) ||
            session.returns.values.any { !SafeStandingSpot.isSafe(it) }
        ) {
            clearReturnRecords(records.keys, notifyOnFailure = false)
            cleanupOrAbandonSlot(slot)
            notifyEntryFailure(session, "ec.error.world_unavailable")
            return
        }
        slotTracker.activate(slot)
        val instance = Instance(
            slot = slot,
            definition = session.definition,
            members = session.players.map { it.uniqueId }.toMutableSet(),
            returns = session.returns,
            started = tick,
            overworld = true,
        )
        instances[slot] = instance
        try {
            spawnWave(instance, session.world, center.x, center.z)
        } catch (error: Exception) {
            plugin.logger.warning("Dungeon wave failed: ${error.message}")
            instance.unreturnedMembers.clear()
            clearReturnRecords(records.keys, notifyOnFailure = false)
            notifyEntryFailure(session, "ec.error.world_unavailable")
            close(instance)
            return
        }
        session.players.forEach { member ->
            plugin.profiles[member.uniqueId]?.let { profile ->
                runCatching {
                    plugin.game.quests.event(
                        profile,
                        ObjectiveKind.EXPLORE,
                        session.definition.area,
                        1,
                        plugin.inventory(member),
                        false,
                    )
                    plugin.markDirty(profile.uuid)
                }.onFailure { plugin.logger.warning("Dungeon exploration progress failed: ${it.message}") }
            }
            val spawnLocation = Location(
                session.world,
                center.x + 0.5,
                center.y.toDouble() + 1.0,
                center.z + DungeonArenaLayout.PLAYER_SPAWN_Z_OFFSET + 0.5,
            )
            val transferred = runCatching { member.teleport(spawnLocation) }.getOrDefault(false)
            if (transferred) {
                member.sendMessage(
                    plugin.message(member, "ec.dungeon.entry", "message" to session.definition.entryMessage),
                )
            } else {
                instance.unreturnedMembers.remove(member.uniqueId)
                clearReturnRecords(setOf(member.uniqueId), notifyOnFailure = false)
                member.sendMessage(plugin.message(member, "ec.error.teleport_destination"))
                detach(instance, member.uniqueId)
            }
        }
    }

    private fun isEntryStillValid(session: OverworldEntrySession, checkPending: Boolean = true): Boolean {
        val registry = plugin.activeWorldProjection.registry
        return plugin.isEnabled &&
            !journalFailed &&
            registry.isReady() &&
            registry.portalForDungeon(session.dungeonId) == session.portal &&
            session.players.all { member ->
                member.isOnline &&
                    member.world == session.world &&
                    (!checkPending || member.uniqueId !in pendingReturns)
            }
    }

    private fun finishEntry(session: OverworldEntrySession) {
        enteringPlayers.removeAll(session.returns.keys)
    }

    private fun notifyEntryFailure(session: OverworldEntrySession, messageKey: String) {
        val recipients = session.players.filter { it.isOnline }.ifEmpty {
            listOfNotNull(session.leader.takeIf { it.isOnline })
        }
        recipients.forEach { recipient -> recipient.sendMessage(plugin.message(recipient, messageKey)) }
    }

    private fun hasPendingReturnAtSlot(layout: OverworldDungeonInstanceLayout, slot: Int): Boolean {
        val center = layout.center(slot)
        return pendingReturns.values.any { record -> record.slotX == center.x && record.slotZ == center.z }
    }

    /** 設定値とワールドボーダー・高さ制限に合わせて空中レイアウトを解決し、必要時に警告を出します。 */
    private fun resolveOverworldLayout(world: World, logValidation: Boolean): OverworldDungeonInstanceLayout {
        if (overworldLayoutWorldName == world.name && !logValidation) return overworldLayout
        val cfg = plugin.config
        val configuredBaseX = if (cfg.isInt("world.dungeon.base_x")) cfg.getInt("world.dungeon.base_x") else null
        val configuredBaseY = if (cfg.isInt("world.dungeon.base_y")) cfg.getInt("world.dungeon.base_y") else null
        val configuredBaseZ = if (cfg.isInt("world.dungeon.base_z")) cfg.getInt("world.dungeon.base_z") else null
        val configuredSpacing = if (cfg.isInt("world.dungeon.spacing")) cfg.getInt("world.dungeon.spacing") else null
        val border = world.worldBorder
        val cap = plugin.snapshot.world.tuning.instanceCap
            .coerceIn(1, OverworldDungeonInstanceLayout.MAX_CANDIDATE_SLOTS)
        val resolved = OverworldDungeonInstanceLayout.resolveForWorld(
            configuredBaseX = configuredBaseX,
            configuredBaseY = configuredBaseY,
            configuredBaseZ = configuredBaseZ,
            configuredSpacing = configuredSpacing,
            borderCenterX = border.center.x,
            borderCenterZ = border.center.z,
            borderSize = border.size,
            minHeight = world.minHeight,
            maxHeight = world.maxHeight,
            slotCount = cap,
        )
        overworldLayout = resolved
        overworldLayoutWorldName = world.name
        if (logValidation) {
            if (!resolved.fitsHeight(world.minHeight, world.maxHeight)) {
                plugin.logger.warning(
                    "Overworld dungeon layout baseY=${resolved.baseY} does not fit world '${world.name}' " +
                        "height range [${world.minHeight}, ${world.maxHeight}).",
                )
            }
            val fitting = resolved.fittingSlots(cap, border.center.x, border.center.z, border.size)
            if (fitting.isEmpty()) {
                plugin.logger.warning(
                    "No overworld dungeon slots fit inside world border of '${world.name}' " +
                        "(size=${border.size}, center=${border.center.x},${border.center.z}).",
                )
            } else if (fitting.size < cap) {
                plugin.logger.warning(
                    "Only ${fitting.size}/$cap overworld dungeon slots fit inside world border of '${world.name}'.",
                )
            }
            if (configuredBaseX == null && configuredBaseZ == null &&
                (resolved.baseX != OverworldDungeonInstanceLayout.DEFAULT_BASE_X ||
                    resolved.baseZ != OverworldDungeonInstanceLayout.DEFAULT_BASE_Z)
            ) {
                plugin.logger.info(
                    "Adjusted overworld dungeon layout for '${world.name}' world border: " +
                        "base=(${resolved.baseX}, ${resolved.baseY}, ${resolved.baseZ}), spacing=${resolved.spacing}.",
                )
            }
        }
        return resolved
    }

    /** 対応する同梱アリーナがあれば slot 区画を初期化して配置します。 */
    private fun placeDungeonSchematic(world: World, dungeonId: String, centerX: Int, centerZ: Int): Boolean {
        val path = WorldSchematicLayout.dungeonPath(dungeonId) ?: return false
        val schematic = loadedSchematics[path] ?: runCatching { SchematicResources.load(path) }
            .onFailure { plugin.logger.warning("Could not read dungeon schematic $path: ${it.message}") }
            .getOrNull()?.also { loadedSchematics[path] = it } ?: return false
        if (schematic.width != DungeonArenaLayout.SCHEMATIC_WIDTH_BLOCKS ||
            schematic.height != DungeonArenaLayout.SCHEMATIC_HEIGHT_BLOCKS ||
            schematic.length != DungeonArenaLayout.SCHEMATIC_LENGTH_BLOCKS
        ) {
            plugin.logger.warning(
                "Dungeon schematic has unexpected dimensions: $path " +
                    "(${schematic.width}x${schematic.height}x${schematic.length})",
            )
            return false
        }
        val x = centerX - DungeonArenaLayout.SCHEMATIC_ORIGIN_OFFSET_BLOCKS
        val y = DungeonArenaLayout.SCHEMATIC_ORIGIN_Y
        val z = centerZ - DungeonArenaLayout.SCHEMATIC_ORIGIN_OFFSET_BLOCKS
        clearSchematicArena(world, x, y, z)
        return runCatching {
            SchematicPlacer.place(schematic, world, x, y, z, skipAir = false) { message, error ->
                plugin.logger.warning(if (error == null) message else "$message: ${error.message}")
            }
            schematicSlots += (centerX - DungeonArenaLayout.FIRST_CENTER_X) / DungeonArenaLayout.SLOT_SPACING_BLOCKS
            true
        }.getOrElse { error ->
            clearSchematicArena(world, x, y, z)
            plugin.logger.warning(
                "Could not place dungeon schematic $path; using procedural fallback: ${error.message}",
            )
            false
        }
    }

    /** インスタンス専用の立方領域を空にして、前回の内容を残しません。 */
    private fun clearSchematicArena(world: World, originX: Int, originY: Int, originZ: Int) {
        for (y in 0 until DungeonArenaLayout.SCHEMATIC_HEIGHT_BLOCKS) {
            for (z in 0 until DungeonArenaLayout.SCHEMATIC_LENGTH_BLOCKS) {
                for (x in 0 until DungeonArenaLayout.SCHEMATIC_WIDTH_BLOCKS) {
                    world.getBlockAt(originX + x, originY + y, originZ + z).setType(Material.AIR, false)
                }
            }
        }
    }

    private fun spawnWave(instance: Instance, world: World, x: Int, z: Int) {
        val definition = instance.definition
        val wave = definition.waves.getOrNull(instance.waveIndex)
        val bossId = wave?.boss ?: definition.boss
        val bossOffset = wave?.bossOffset ?: definition.bossOffset
        val guards = wave?.guards ?: definition.guards
        val offsets = wave?.guardOffsets ?: definition.guardOffsets
        val spawned = mutableListOf<org.bukkit.entity.LivingEntity>()
        val spawnY = if (instance.overworld) {
            overworldLayout.center(instance.slot).y + 1.0
        } else {
            DungeonArenaLayout.ENTITY_SPAWN_Y.toDouble()
        }
        try {
            val boss = enemies.spawn(
                bossId,
                Location(world, (x + bossOffset.first).toDouble(), spawnY, (z + bossOffset.second).toDouble()),
                "instance:${instance.slot}:wave:${instance.waveIndex}",
                1,
                instance.members.toSet(),
            ) ?: error("ec.error.spawn_cap")
            spawned += boss
            guards.zip(offsets).forEach { (guard, offset) ->
                val entity = enemies.spawn(
                    guard,
                    Location(world, x + offset.first.toDouble(), spawnY, z + offset.second.toDouble()),
                    "instance:${instance.slot}:wave:${instance.waveIndex}:guards",
                    guards.size.coerceAtLeast(1),
                    instance.members.toSet(),
                )
                if (entity == null) error("ec.error.guard_spawn_failed")
                spawned += entity
            }
        } catch (error: Exception) {
            spawned.forEach { it.remove() }
            throw error
        }
        instance.entities.clear()
        instance.entities.addAll(spawned.map { it.uniqueId })
        instance.boss = spawned.first().uniqueId
    }

    /** ボス撃破時に次ウェーブの展開またはクリア報酬の付与を行います。 */
    fun defeated(id: UUID) {
        val instance = instances.values.firstOrNull { it.boss == id && !it.completed } ?: return
        val waveCount = instance.definition.waves.size.coerceAtLeast(1)
        if (instance.waveIndex + 1 < waveCount) {
            instance.entities.forEach { Bukkit.getEntity(it)?.remove() }
            instance.waveIndex++
            val world = instanceWorld(instance) ?: return
            val (x, z) = instanceCenter(instance)
            try {
                spawnWave(instance, world, x, z)
                instance.members.forEach { member ->
                    Bukkit.getPlayer(member)?.let { player ->
                        player.sendMessage(
                            plugin.message(
                                player,
                                "ec.dungeon.wave",
                                "current" to instance.waveIndex + 1,
                                "total" to waveCount,
                            ),
                        )
                    }
                }
            } catch (error: Exception) {
                plugin.logger.warning("Challenge wave could not spawn: ${error.message}")
                instance.members.toList().forEach { Bukkit.getPlayer(it)?.let { p -> leave(p) } }
            }
            return
        }
        instance.completed = true
        instance.entities.forEach { Bukkit.getEntity(it)?.remove() }
        instance.entities.clear()
        instance.boss = null
        val seconds = MinecraftTicks.millisToWholeSeconds(clock() - instance.startedAtMillis)
        val ranks = instance.definition.scoreRanks
        if (ranks.isNotEmpty()) {
            val rank = net.nakaymi.etherealchronicle.core.challengeRank(seconds, ranks)
            instance.members.forEach { member ->
                var best: net.nakaymi.etherealchronicle.core.ChallengeRecord? = null
                var hasFirstReward = false
                adventure.profileTransaction(member, { profile ->
                    val (updatedBest, _) = profile.recordChallenge(instance.definition.id, seconds, rank)
                    best = updatedBest
                    val firstRewards = firstClearRanks(profile, ranks, seconds)
                    hasFirstReward = firstRewards.isNotEmpty()
                    val itemRewards = firstRewards.flatMap { it.firstClearItems.entries }
                        .groupBy({ it.key }, { it.value })
                        .mapValues { (_, amounts) -> amounts.sum() }
                    val flags = firstRewards.mapNotNull { it.firstClearFlag }.toSet()
                    // 初回報酬 flag と mailbox をクリア記録と同じ保存へ含めます。
                    profile.returnToMailbox(itemRewards)
                    profile.flags.addAll(flags)
                }) {
                    val player = Bukkit.getPlayer(member)?.takeIf { it.isOnline } ?: return@profileTransaction
                    best?.let { record ->
                        player.sendMessage(
                            plugin.message(
                                player,
                                "ec.dungeon.result",
                                "message" to instance.definition.clearMessage,
                                "seconds" to seconds,
                                "rank" to rank,
                                "best_seconds" to record.bestSeconds,
                                "best_rank" to record.bestRank,
                            ),
                        )
                    }
                    if (hasFirstReward) {
                        adventure.claimPending(player)
                        player.sendMessage(plugin.message(player, "ec.dungeon.first_rank_reward"))
                    }
                }
            }
        } else {
            instance.members.forEach { member ->
                Bukkit.getPlayer(member)?.let {
                    it.sendMessage(plugin.message(it, "ec.dungeon.clear", "message" to instance.definition.clearMessage))
                }
            }
        }
    }

    /** プレイヤーをダンジョンから帰還させます。 */
    fun leave(player: Player) {
        leaveInternal(player, forceFallback = false)
    }

    private fun leaveInternal(player: Player, forceFallback: Boolean) {
        val instance = instances.values.firstOrNull { player.uniqueId in it.members } ?: return
        if (instance.overworld) {
            leaveOverworld(player, instance, forceFallback)
            return
        }
        instance.returns[player.uniqueId]?.let { destination ->
            if (destination.world != null) player.teleport(destination)
        }
        instance.members.remove(player.uniqueId)
        if (instance.members.isEmpty()) close(instance)
    }

    /** 地上チャンクを非同期で確保し、安全な地上帰還の完了後に非同期で帰還記録を解除します。 */
    private fun leaveOverworld(player: Player, instance: Instance, forceFallback: Boolean) {
        val id = player.uniqueId
        if (!leavingPlayers.add(id)) return
        val destination = instance.returns[id]
        val world = destination?.world ?: instanceWorld(instance) ?: run {
            leavingPlayers.remove(id)
            return
        }
        val targetChunkX = (destination?.blockX ?: world.spawnLocation.blockX) shr 4
        val targetChunkZ = (destination?.blockZ ?: world.spawnLocation.blockZ) shr 4
        plugin.withChunk(world, targetChunkX, targetChunkZ) { failure ->
            if (instances[instance.slot] !== instance || id !in instance.members) {
                leavingPlayers.remove(id)
                return@withChunk
            }
            if (!player.isOnline) {
                detach(instance, id)
                return@withChunk
            }
            val safeTarget = when {
                failure == null && destination != null && SafeStandingSpot.isSafe(destination) -> destination
                forceFallback -> plugin.luminaSpawn() ?: world.spawnLocation
                else -> null
            }
            if (safeTarget == null) {
                leavingPlayers.remove(id)
                player.sendMessage(plugin.message(player, "ec.error.teleport_destination"))
                return@withChunk
            }
            val teleported = runCatching { player.teleport(safeTarget) }.getOrDefault(false) ||
                (forceFallback && runCatching { player.teleport(world.spawnLocation) }.getOrDefault(false))
            if (!teleported) {
                leavingPlayers.remove(id)
                player.sendMessage(plugin.message(player, "ec.error.teleport_destination"))
                return@withChunk
            }
            instance.unreturnedMembers.remove(id)
            detach(instance, id)
            clearReturnRecords(setOf(id), notifyOnFailure = true)
        }
    }

    /** 地上帰還完了後に永続ジャーナルから対象プレイヤーの帰還記録を非同期削除します。 */
    private fun clearReturnRecords(ids: Set<UUID>, notifyOnFailure: Boolean) {
        if (ids.isEmpty()) return
        val store = returnStore ?: run {
            ids.forEach {
                pendingReturns.remove(it)
                pendingReturnFreezeSince.remove(it)
            }
            return
        }
        store.removeAll(ids).whenComplete { _, removeError ->
            plugin.sync {
                if (removeError == null) {
                    ids.forEach {
                        pendingReturns.remove(it)
                        pendingReturnFreezeSince.remove(it)
                    }
                } else {
                    plugin.logger.warning("Dungeon return record cleanup failed: ${removeError.message}")
                    if (notifyOnFailure) {
                        ids.forEach { id ->
                            Bukkit.getPlayer(id)?.takeIf { it.isOnline }?.let { online ->
                                online.sendMessage(plugin.message(online, "ec.dungeon.relogin_required"))
                            }
                        }
                    }
                }
            }
        }
    }

    private fun detach(instance: Instance, id: UUID) {
        instance.members.remove(id)
        leavingPlayers.remove(id)
        if (instance.members.isEmpty()) close(instance)
    }

    private fun close(instance: Instance) {
        if (instances.remove(instance.slot) !== instance) return
        instance.entities.forEach { Bukkit.getEntity(it)?.remove() }
        instance.entities.clear()
        instance.boss = null
        enemies.removeParticipants(instance.returns.keys)
        if (instance.overworld) {
            if (instance.unreturnedMembers.isEmpty() && !shuttingDown && plugin.isEnabled) {
                cleanupOrAbandonSlot(instance.slot)
            } else {
                abandonSlot(instance.slot)
            }
            return
        }
        if (instance.slot in schematicSlots) {
            val origin = DungeonArenaLayout.originForSlot(instance.slot)
            Bukkit.getWorld(DemoWorld.NAME)?.let { world ->
                clearSchematicArena(
                    world,
                    origin.x - DungeonArenaLayout.SCHEMATIC_ORIGIN_OFFSET_BLOCKS,
                    DungeonArenaLayout.SCHEMATIC_ORIGIN_Y,
                    origin.z - DungeonArenaLayout.SCHEMATIC_ORIGIN_OFFSET_BLOCKS,
                )
            }
            schematicSlots.remove(instance.slot)
        }
    }

    /** 自身が配置した空中アリーナを CLEANING 状態で非同期分割撤去し、完了後に枠を解放します。 */
    private fun cleanupOrAbandonSlot(slot: Int) {
        val operation = placedOperations.remove(slot)
        if (operation == null || shuttingDown || !plugin.isEnabled) {
            operation?.release()
            slotTracker.release(slot)
            return
        }
        if (!slotTracker.beginCleaning(slot)) {
            operation.release()
            slotTracker.release(slot)
            return
        }
        cleaningOperations[slot] = operation
        operation.clear { cleared, error ->
            cleaningOperations.remove(slot)
            slotTracker.release(slot)
            if (!cleared && error != null) {
                plugin.logger.warning("Dungeon arena cleanup failed for slot $slot: ${error.message}")
            }
        }
    }

    /** クラッシュ・未帰還切断・終了時は残存ブロックを壊さずチケットとスロット状態だけ解放します。 */
    private fun abandonSlot(slot: Int) {
        placedOperations.remove(slot)?.release()
        cleaningOperations.remove(slot)?.release()
        slotTracker.release(slot)
    }

    /** ログイン時に残存ダンジョン記録を確認し、必要に応じて安全地上へ救出します。 */
    fun recover(player: Player) {
        if (plugin.activeWorldProjection.registry.isOverworldMode()) {
            recoverOverworld(player)
            return
        }
        if (player.world.name == DemoWorld.NAME &&
            player.x >= DungeonArenaLayout.RECOVERY_MIN_COORDINATE &&
            player.z >= DungeonArenaLayout.RECOVERY_MIN_COORDINATE &&
            instances.values.none { player.uniqueId in it.members }
        ) {
            player.teleport(player.world.spawnLocation)
        }
    }

    /**
     * 再ログイン時に現在位置（視点回転に左右されない）で地上安全を確認し、
     * 上空残留時は保存先または安全なフォールバックへ救出して記録を解除します。
     */
    private fun recoverOverworld(player: Player) {
        val id = player.uniqueId
        val record = pendingReturns[id] ?: return
        val world = Bukkit.getWorld(record.world) ?: return
        if (!recoveringPlayers.add(id)) return
        pendingReturnFreezeSince.putIfAbsent(id, clock())
        val initialLocation = player.location
        val currentWorld = initialLocation.world ?: run {
            recoveringPlayers.remove(id)
            return
        }
        plugin.withChunk(currentWorld, initialLocation.blockX shr 4, initialLocation.blockZ shr 4) { error ->
            if (!player.isOnline || pendingReturns[id] !== record) {
                recoveringPlayers.remove(id)
                return@withChunk
            }
            val current = player.location
            val sameWorld = current.world == world
            val safe = error == null && sameWorld && SafeStandingSpot.isSafe(current)
            val slotCenter = DungeonInstanceCenter(record.slotX, record.slotY, record.slotZ)
            val acknowledged = canAcknowledgeDungeonReturn(
                slotCenter,
                current.x,
                current.y,
                current.z,
                sameWorld,
                safe,
            )
            if (acknowledged) {
                recoveringPlayers.remove(id)
                clearReturnRecords(setOf(id), notifyOnFailure = false)
                return@withChunk
            }
            val destination = Location(world, record.x, record.y, record.z, record.yaw, record.pitch)
            plugin.withChunk(world, destination.blockX shr 4, destination.blockZ shr 4) { loadError ->
                recoveringPlayers.remove(id)
                if (!player.isOnline || pendingReturns[id] !== record) return@withChunk
                val latest = player.location
                val latestSafe = latest.world == world && SafeStandingSpot.isSafe(latest)
                if (canAcknowledgeDungeonReturn(
                        slotCenter,
                        latest.x,
                        latest.y,
                        latest.z,
                        latest.world == world,
                        latestSafe,
                    )
                ) {
                    clearReturnRecords(setOf(id), notifyOnFailure = false)
                    return@withChunk
                }
                val rescueTarget = when {
                    loadError == null && SafeStandingSpot.isSafe(destination) -> destination
                    else -> plugin.luminaSpawn() ?: world.spawnLocation
                }
                if (runCatching { player.teleport(rescueTarget) }.getOrDefault(false)) {
                    clearReturnRecords(setOf(id), notifyOnFailure = false)
                } else {
                    plugin.requestHubSpawn(player)
                }
            }
        }
    }

    private fun instanceWorld(instance: Instance): World? = Bukkit.getWorld(
        if (instance.overworld) plugin.activeWorldProjection.worldName() else DemoWorld.NAME,
    )

    private fun instanceCenter(instance: Instance): Pair<Int, Int> = if (instance.overworld) {
        overworldLayout.center(instance.slot).let { it.x to it.z }
    } else {
        DungeonArenaLayout.originForSlot(instance.slot).let { it.x to it.z }
    }

    /** 制限時間超過インスタンスの全メンバーを安全に帰還させ、全員完了後にのみ close します。 */
    fun tick() {
        tick += PaperTaskIntervals.PLAYER_UPDATE_TICKS
        val timeout = plugin.snapshot.world.tuning.instanceTimeout
        instances.values.toList()
            .filter { !it.timingOut && tick - it.started >= timeout }
            .forEach { instance ->
                instance.timingOut = true
                val members = instance.members.toList()
                if (members.isEmpty()) {
                    close(instance)
                    return@forEach
                }
                members.forEach { memberId ->
                    val player = Bukkit.getPlayer(memberId)
                    if (player != null && player.isOnline) {
                        player.sendMessage(plugin.message(player, "ec.dungeon.timeout"))
                        leaveInternal(player, forceFallback = true)
                    } else {
                        detach(instance, memberId)
                    }
                }
            }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun teleport(e: PlayerTeleportEvent) {
        if (e.player.uniqueId in leavingPlayers) return
        val instance = instances.values.firstOrNull { e.player.uniqueId in it.members } ?: return
        val to = e.to
        val inside = if (instance.overworld) {
            val bounds = overworldLayout.bounds(instance.slot)
            to.world?.name == plugin.activeWorldProjection.worldName() &&
                to.x >= bounds.minX && to.x < bounds.maxX + 1 &&
                to.y >= bounds.minY && to.y < bounds.maxY + 1 &&
                to.z >= bounds.minZ && to.z < bounds.maxZ + 1
        } else {
            to.world?.name == DemoWorld.NAME && DungeonArenaLayout.isInsideBounds(to, instance.slot)
        }
        if (!inside) {
            if (instance.overworld) {
                e.player.sendMessage(plugin.message(e.player, "ec.dungeon.relogin_required"))
            }
            detach(instance, e.player.uniqueId)
        }
    }

    @EventHandler
    fun quit(e: PlayerQuitEvent) {
        val id = e.player.uniqueId
        pendingReturnFreezeSince.remove(id)
        recoveringPlayers.remove(id)
        val instance = instances.values.firstOrNull { id in it.members } ?: return
        if (instance.overworld) detach(instance, id) else leave(e.player)
    }

    @EventHandler
    fun respawn(e: PlayerRespawnEvent) {
        val id = e.player.uniqueId
        val instance = instances.values.firstOrNull { id in it.members } ?: return
        val destination = instance.returns[id]
        if (instance.overworld) {
            val target = if (destination != null && SafeStandingSpot.isSafe(destination)) {
                destination
            } else {
                plugin.luminaSpawn() ?: e.player.world.spawnLocation
            }
            e.respawnLocation = target
            instance.unreturnedMembers.remove(id)
            detach(instance, id)
            plugin.sync {
                if (e.player.isOnline) clearReturnRecords(setOf(id), notifyOnFailure = true)
            }
            return
        }
        e.respawnLocation = destination ?: e.respawnLocation
        detach(instance, id)
    }

    @EventHandler
    fun move(e: PlayerMoveEvent) {
        val id = e.player.uniqueId
        val pending = pendingReturns[id]
        if (pending != null &&
            e !is PlayerTeleportEvent &&
            e.player.world.name == pending.world &&
            kotlin.math.abs(e.from.x - pending.slotX) <=
            OverworldDungeonInstanceLayout.ARENA_RADIUS + OverworldDungeonInstanceLayout.RECOVERY_HORIZONTAL_MARGIN_BLOCKS &&
            kotlin.math.abs(e.from.z - pending.slotZ) <=
            OverworldDungeonInstanceLayout.ARENA_RADIUS + OverworldDungeonInstanceLayout.RECOVERY_HORIZONTAL_MARGIN_BLOCKS
        ) {
            val startedAt = pendingReturnFreezeSince.getOrPut(id) { clock() }
            if (clock() - startedAt >= RECOVERY_FREEZE_TIMEOUT_MILLIS) {
                plugin.logger.warning(
                    "Dungeon return recovery timed out for $id after ${RECOVERY_FREEZE_TIMEOUT_MILLIS}ms; " +
                        "teleporting to safe fallback.",
                )
                pendingReturnFreezeSince.remove(id)
                recoveringPlayers.remove(id)
                val fallback = plugin.luminaSpawn()
                    ?: Bukkit.getWorld(pending.world)?.spawnLocation
                    ?: e.player.world.spawnLocation
                e.to = fallback
                clearReturnRecords(setOf(id), notifyOnFailure = false)
                return
            }
            // ログイン直後に救出が終わるまで、残存スロットからの落下を止めます。
            e.to = e.from
            return
        }
        val instance = instances.values.firstOrNull { id in it.members }
        if (instance?.overworld == true && e.to.y < overworldLayout.bounds(instance.slot).minY) {
            val center = overworldLayout.center(instance.slot)
            e.to = Location(e.player.world, center.x + 0.5, center.y + 2.0, center.z + 0.5)
            leave(e.player)
        } else if (e.player.world.name == DemoWorld.NAME && e.to.y < DungeonArenaLayout.VOID_RECOVERY_Y) {
            e.player.teleport(e.player.world.spawnLocation)
            leave(e.player)
        }
    }

    /** プラグイン停止時に全インスタンス・進行中操作・IO ストアを安全に閉じます。 */
    fun shutdown() {
        shuttingDown = true
        instances.values.toList().forEach { instance ->
            instance.members.toList().forEach { id ->
                if (instance.overworld) detach(instance, id) else Bukkit.getPlayer(id)?.let(::leave)
            }
            close(instance)
        }
        placedOperations.values.toList().forEach { it.release() }
        placedOperations.clear()
        cleaningOperations.values.toList().forEach { it.release() }
        cleaningOperations.clear()
        slotTracker.clear()
        pendingReturnFreezeSince.clear()
        recoveringPlayers.clear()
        enteringPlayers.clear()
        leavingPlayers.clear()
        placement?.close()
        scanner?.close()
        returnStore?.close()
    }

    private companion object {
        const val DEFAULT_DUNGEON_ID = "ec:dungeon_void_sanctum"
        const val RETURN_JOURNAL_FILE = "dungeon-returns.json"
        const val SCAN_BUDGET = 4_096
        const val RECOVERY_FREEZE_TIMEOUT_MILLIS = 10_000L
    }
}
