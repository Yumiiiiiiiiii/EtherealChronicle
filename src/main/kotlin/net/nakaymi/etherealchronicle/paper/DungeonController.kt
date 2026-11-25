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
import net.nakaymi.etherealchronicle.core.isInsideDungeonSlotMargin
import net.nakaymi.etherealchronicle.core.recordChallenge
import net.nakaymi.etherealchronicle.core.returnToMailbox
import net.nakaymi.etherealchronicle.data.DungeonOwnedSlot
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

/** 管理者によるダンジョンスロット手動解放コマンドの結果です。 */
internal enum class DungeonSlotReleaseResult {
    CLEANING,
    RELEASED,
    ACTIVE,
    CLEANING_IN_PROGRESS,
    NOT_FOUND,
}

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
        var timingOut: Boolean = false,
        val unreturnedMembers: MutableSet<UUID> = members.toMutableSet(),
        var timeoutRetryAttempts: Int = 0,
        var lastTimeoutRetryTick: Long = Long.MIN_VALUE,
    )

    /** オーバーワールド入場フローの非同期ステップ間で共有するセッション情報です。 */
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
        var consecutiveOccupiedSlots: Int = 0,
    )

    private val instances = mutableMapOf<Int, Instance>()
    private val memberToSlot = mutableMapOf<UUID, Int>()
    private var tick = 0L
    private val loadedSchematics = mutableMapOf<String, SchematicData>()
    private val slotTracker = DungeonSlotTracker()
    private val pendingReturns = mutableMapOf<UUID, DungeonReturnRecord>()
    private val ownedSlots = mutableMapOf<Int, DungeonOwnedSlot>()
    private val retainedPlacedCells = mutableMapOf<Int, IntArray>()
    private val pendingReturnFreezeSince = mutableMapOf<UUID, Long>()
    private val recoveringPlayers = mutableSetOf<UUID>()
    private val enteringPlayers = mutableSetOf<UUID>()
    private val leavingPlayers = mutableMapOf<UUID, Long>()
    private val leaveAttemptTokens = mutableMapOf<UUID, Long>()
    private var leaveAttemptSequence = 0L
    private val journalWaiters = mutableListOf<() -> Unit>()
    private val placedOperations = mutableMapOf<Int, OverworldDungeonPlacement.Operation>()
    private val cleaningHandles = mutableMapOf<Int, OverworldDungeonPlacement.CleanupHandle>()
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
        initializeOverworldRuntime()
    }

    /** 帰還ジャーナル・所有スロット回収・空中レイアウト検証を初期化します。 */
    private fun initializeOverworldRuntime() {
        Bukkit.getWorld(plugin.activeWorldProjection.registry.activeWorldName())?.let { world ->
            resolveOverworldLayout(world, logValidation = true)
        }
        val store = DungeonReturnStore(
            file = plugin.dataFolder.toPath().resolve(RETURN_JOURNAL_FILE),
            onWarning = { message -> plugin.logger.warning(message) },
        )
        returnStore = store
        store.readState().whenComplete { state, error ->
            plugin.sync {
                if (error != null) {
                    plugin.logger.severe(
                        "Dungeon return journal unavailable; overworld dungeon entry disabled: ${error.message}",
                    )
                    journalFailed = true
                    journalReady = true
                    drainJournalWaiters()
                } else {
                    pendingReturns.putAll(state.records)
                    ownedSlots.putAll(state.ownedSlots)
                    reconcileOwnedSlotsOnStartup()
                    journalReady = true
                    drainJournalWaiters()
                }
            }
        }
    }

    /** 起動時に永続化された所有スロットを点検し、未帰還記録ありは保護、未書込は記録削除のみ、配置済み孤立は自動撤去します。 */
    private fun reconcileOwnedSlotsOnStartup() {
        if (shuttingDown || !plugin.isEnabled) return
        ownedSlots.values.toList().forEach { owned ->
            if (hasPendingReturnForOwnedSlot(owned)) {
                slotTracker.protect(owned.slot)
            } else if (!owned.placementStarted) {
                removeOwnedSlotAndRelease(owned.slot)
            } else {
                startOwnedSlotCleanup(owned.slot)
            }
        }
    }

    private fun drainJournalWaiters() {
        val waiters = journalWaiters.toList()
        journalWaiters.clear()
        waiters.forEach { it() }
    }

    /** 稼働中・予約中・撤去中のアクティブなダンジョン枠数を二重計上なしで返します。 */
    fun activeCount(): Int = (instances.keys + slotTracker.activeSlots()).size

    /** 指定スロットのオーバーワールド状態（テスト・診断用）を返します。 */
    internal fun slotState(slot: Int): DungeonSlotState? = slotTracker.stateOf(slot)

    /** 帰還ジャーナルの読込（または失敗確定）が完了しているかを返します。 */
    fun isJournalReady(): Boolean = journalReady

    /** 帰還ジャーナル破損によりオーバーワールドダンジョン入場が無効化されているかを返します。 */
    fun isJournalFailed(): Boolean = journalFailed

    /** 帰還記録が読み込まれるまで入場・ログイン時の転送を保留します。 */
    fun whenJournalReady(action: () -> Unit) {
        if (journalReady) action()
        else journalWaiters += action
    }

    /** 未確定記録の所有者は初回スポーン処理の対象から外します。 */
    fun hasPendingReturn(id: UUID): Boolean = !journalReady || id in pendingReturns

    /** リスポーン処理の振り分けに、現在の参加状態を O(1) で公開します。 */
    fun containsPlayer(id: UUID): Boolean = id in memberToSlot

    private fun instanceForPlayer(id: UUID): Instance? = memberToSlot[id]?.let { instances[it] }

    internal fun currentWave(slot: Int) = instances[slot]?.waveIndex
    internal fun currentBoss(slot: Int) = instances[slot]?.boss

    /** テストや管理診断用に現在のオーバーワールドレイアウトを返します。 */
    internal fun currentOverworldLayout(): OverworldDungeonInstanceLayout = overworldLayout

    /** 参加者・解放条件・instance枠を検証してダンジョンへ入場させます。 */
    fun enter(player: Player, dungeonId: String = DEFAULT_DUNGEON_ID) = enterOverworld(player, dungeonId)

    /** 地上入口から空中枠を全域検査し、所有証跡と帰還記録の永続化後だけ転送します。 */
    private fun enterOverworld(player: Player, dungeonId: String) {
        val session = prepareOverworldEntry(player, dungeonId)
        enteringPlayers.addAll(session.returns.keys)
        session.players.forEach { member ->
            member.sendMessage(
                plugin.message(member, "ec.dungeon.preparing", "name" to plugin.contentName(member, session.dungeonId)),
            )
        }
        tryCandidate(session, 0)
    }

    /** プレイヤーが入口ポータルから遠い（入口周辺のチャンクが未ロードになり得る）かを返します。 */
    private fun isFarFromPortal(player: Player, portal: OverworldDungeonPortal): Boolean {
        val dx = player.location.x - portal.centerX
        val dz = player.location.z - portal.centerZ
        return player.world.name != plugin.activeWorldProjection.worldName() ||
            dx * dx + dz * dz > PORTAL_NEAR_BLOCKS * PORTAL_NEAR_BLOCKS
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
                slot !in ownedSlots &&
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
            // 入口から遠いと出口チャンクが未ロードで安全確認に失敗します。原因が分かるよう専用メッセージにします。
            if (isFarFromPortal(player, portal)) "ec.error.dungeon_too_far" else "ec.error.world_unavailable"
        }
        val (currentScanner, currentPlacement) = ensureOverworldTools(world)
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

    private fun ensureOverworldTools(world: World): Pair<OverworldDungeonScanner, OverworldDungeonPlacement> {
        val currentScanner = scanner ?: OverworldDungeonScanner(plugin, world).also { scanner = it }
        val currentPlacement = placement ?: OverworldDungeonPlacement(plugin, world, currentScanner).also { placement = it }
        return currentScanner to currentPlacement
    }

    /** 候補スロットを先頭から原子的に予約し、空域検査と配置を順次進めます。 */
    private fun tryCandidate(session: OverworldEntrySession, index: Int) {
        if (!plugin.isEnabled || shuttingDown) {
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
        if (slot in instances || slot in ownedSlots || hasPendingReturnAtSlot(overworldLayout, slot)) {
            slotTracker.release(slot)
            tryCandidate(session, index + 1)
            return
        }
        val bounds = overworldLayout.bounds(slot)
        try {
            session.scanner.scan(bounds, SCAN_BUDGET) { scanOperation, result, failure ->
                handleScanResult(session, index, slot, bounds, scanOperation, result, failure)
            }
        } catch (error: Exception) {
            slotTracker.release(slot)
            plugin.logger.warning("Dungeon slot scan could not start for slot $slot: ${error.message}")
            tryCandidate(session, index + 1)
        }
    }

    /** スキャン結果を受け、空き枠なら所有証跡を永続化してから配置工程へ引き継ぎます。 */
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
            if (result == DungeonBoxScanResult.OCCUPIED) {
                session.consecutiveOccupiedSlots++
                if (session.consecutiveOccupiedSlots == CONSECUTIVE_OCCUPIED_WARN_THRESHOLD) {
                    plugin.logger.warning(
                        "Overworld dungeon entry encountered ${session.consecutiveOccupiedSlots} consecutive OCCUPIED slots " +
                            "in '${session.world.name}' (baseY=${overworldLayout.baseY}); check terrain clearance or leftover blocks.",
                    )
                }
            } else if (failure != null) {
                plugin.logger.warning("Dungeon slot scan failed for slot $slot: ${failure.message}")
            }
            tryCandidate(session, index + 1)
            return
        }
        session.consecutiveOccupiedSlots = 0
        if (!isEntryStillValid(session)) {
            scanOperation.release()
            slotTracker.release(slot)
            finishEntry(session)
            notifyEntryFailure(session, "ec.error.world_unavailable")
            return
        }
        val store = returnStore
        if (store == null || journalFailed) {
            scanOperation.release()
            slotTracker.release(slot)
            finishEntry(session)
            notifyEntryFailure(session, "ec.error.world_unavailable")
            return
        }
        val owned = DungeonOwnedSlot(
            slot = slot,
            world = session.world.name,
            dungeonId = session.dungeonId,
            minX = bounds.minX,
            minY = bounds.minY,
            minZ = bounds.minZ,
            placementStarted = false,
        )
        store.putOwnedSlot(owned).whenComplete { _, writeError ->
            plugin.sync {
                if (writeError != null || shuttingDown || !plugin.isEnabled) {
                    scanOperation.release()
                    slotTracker.release(slot)
                    if (writeError != null) {
                        plugin.logger.severe("Dungeon owned slot journal write failed for slot $slot: ${writeError.message}")
                    }
                    finishEntry(session)
                    notifyEntryFailure(session, "ec.error.world_unavailable")
                    return@sync
                }
                ownedSlots[slot] = owned
                if (!isEntryStillValid(session)) {
                    scanOperation.release()
                    finishEntry(session)
                    removeOwnedSlotAndRelease(slot)
                    notifyEntryFailure(session, "ec.error.world_unavailable")
                    return@sync
                }
                startPlacement(session, index, slot, bounds, scanOperation)
            }
        }
    }

    private fun startPlacement(
        session: OverworldEntrySession,
        index: Int,
        slot: Int,
        bounds: DungeonInstanceBounds,
        scanOperation: OverworldDungeonScanner.Operation,
    ) {
        try {
            session.placement.place(
                bounds = bounds,
                schematic = session.schematic,
                scanOperation = scanOperation,
                beforeFirstWrite = { proceed -> markPlacementStarted(slot, proceed) },
            ) { placementOperation, success, error ->
                handlePlacementResult(session, index, slot, placementOperation, success, error)
            }
        } catch (error: Exception) {
            scanOperation.release()
            plugin.logger.warning("Dungeon placement could not start for slot $slot: ${error.message}")
            removeOwnedSlotAndRelease(slot) {
                tryCandidate(session, index + 1)
            }
        }
    }

    /** 事前チャンク検証の通過後・最初のブロック書込直前に placementStarted=true を永続化します。 */
    private fun markPlacementStarted(slot: Int, proceed: (Boolean) -> Unit) {
        if (shuttingDown || !plugin.isEnabled || journalFailed) {
            proceed(false)
            return
        }
        val current = ownedSlots[slot]
        val store = returnStore
        if (current == null || store == null) {
            proceed(false)
            return
        }
        val started = current.copy(placementStarted = true)
        store.putOwnedSlot(started).whenComplete { _, error ->
            plugin.sync {
                if (error != null || shuttingDown || !plugin.isEnabled || ownedSlots[slot] != current) {
                    if (error != null) {
                        plugin.logger.severe("Failed to mark dungeon placement started for slot $slot: ${error.message}")
                    }
                    proceed(false)
                } else {
                    ownedSlots[slot] = started
                    proceed(true)
                }
            }
        }
    }

    /** スキーマ配置完了後に帰還ジャーナルを永続化し、途中失敗時は配置済みセルを撤去して次候補へ進みます。 */
    private fun handlePlacementResult(
        session: OverworldEntrySession,
        index: Int,
        slot: Int,
        operation: OverworldDungeonPlacement.Operation,
        success: Boolean,
        error: Throwable?,
    ) {
        if (!success) {
            if (error != null) {
                plugin.logger.warning("Dungeon placement failed for slot $slot: ${error.message}")
            }
            if (operation.placedCount() > 0 && !shuttingDown && plugin.isEnabled) {
                rollbackFailedPlacement(session, index, slot, operation)
            } else {
                operation.release(warnIfRetained = false)
                if (!shuttingDown && plugin.isEnabled) {
                    removeOwnedSlotAndRelease(slot) {
                        tryCandidate(session, index + 1)
                    }
                } else {
                    slotTracker.release(slot)
                    finishEntry(session)
                }
            }
            return
        }
        placedOperations[slot] = operation
        if (!isEntryStillValid(session)) {
            finishEntry(session)
            cleanupPlacedSlot(slot)
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
            cleanupPlacedSlot(slot)
            notifyEntryFailure(session, "ec.error.world_unavailable")
            return
        }
        store.putAll(records).whenComplete { _, writeError ->
            plugin.sync {
                handleReturnPersisted(session, slot, center, records, writeError)
            }
        }
    }

    /** 配置途中で失敗したスロットの配置済みセルを CLEANING 状態で撤去してから次候補へ進みます。 */
    private fun rollbackFailedPlacement(
        session: OverworldEntrySession,
        index: Int,
        slot: Int,
        operation: OverworldDungeonPlacement.Operation,
    ) {
        if (!slotTracker.beginCleaning(slot)) {
            operation.release(warnIfRetained = true)
            slotTracker.protect(slot)
            tryCandidate(session, index + 1)
            return
        }
        cleaningHandles[slot] = operation
        operation.clear { cleared, clearError ->
            cleaningHandles.remove(slot)
            if (cleared) {
                removeOwnedSlotAndRelease(slot) {
                    tryCandidate(session, index + 1)
                }
            } else {
                if (clearError != null) {
                    plugin.logger.warning("Dungeon placement rollback failed for slot $slot: ${clearError.message}")
                }
                slotTracker.protect(slot)
                tryCandidate(session, index + 1)
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
            cleanupPlacedSlot(slot)
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
            cleanupPlacedSlot(slot)
            notifyEntryFailure(session, "ec.error.world_unavailable")
            return
        }
        if (!slotTracker.activate(slot)) {
            plugin.logger.warning("Dungeon slot $slot could not activate from state ${slotTracker.stateOf(slot)}.")
            clearReturnRecords(records.keys, notifyOnFailure = false)
            cleanupPlacedSlot(slot)
            notifyEntryFailure(session, "ec.error.world_unavailable")
            return
        }
        val instance = Instance(
            slot = slot,
            definition = session.definition,
            members = session.players.map { it.uniqueId }.toMutableSet(),
            returns = session.returns,
            started = tick,
        )
        instances[slot] = instance
        instance.members.forEach { memberToSlot[it] = slot }
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
                center.z + PLAYER_SPAWN_Z_OFFSET + 0.5,
            )
            val transferred = runCatching { member.teleport(spawnLocation) }.getOrDefault(false)
            if (transferred) {
                member.sendMessage(
                    plugin.message(member, "ec.dungeon.entry", "message" to plugin.contentText(member, "dungeon", session.definition.id, "entry_message", session.definition.entryMessage)),
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
            !shuttingDown &&
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

    private fun hasPendingReturnForOwnedSlot(owned: DungeonOwnedSlot): Boolean {
        val centerX = owned.minX + OverworldDungeonInstanceLayout.ARENA_RADIUS
        val centerY = owned.minY + OverworldDungeonInstanceLayout.FLOOR_OFFSET
        val centerZ = owned.minZ + OverworldDungeonInstanceLayout.ARENA_RADIUS
        return pendingReturns.values.any { record ->
            record.world == owned.world &&
                record.slotX == centerX &&
                record.slotY == centerY &&
                record.slotZ == centerZ
        }
    }

    private fun findOwnedSlotForRecord(record: DungeonReturnRecord): Int? =
        ownedSlots.values.firstOrNull { owned ->
            owned.world == record.world &&
                owned.minX + OverworldDungeonInstanceLayout.ARENA_RADIUS == record.slotX &&
                owned.minY + OverworldDungeonInstanceLayout.FLOOR_OFFSET == record.slotY &&
                owned.minZ + OverworldDungeonInstanceLayout.ARENA_RADIUS == record.slotZ
        }?.slot

    /** 設定値とワールドボーダー・高さ制限・地形クリアランスに合わせて空中レイアウトを解決します。 */
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
            } else if (!resolved.hasTerrainClearance()) {
                plugin.logger.warning(
                    "Overworld dungeon layout baseY=${resolved.baseY} is below recommended minimum " +
                        "${OverworldDungeonInstanceLayout.RECOMMENDED_MIN_BASE_Y} in '${world.name}'; " +
                        "sky arenas may collide with tall terrain.",
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

    private fun spawnWave(instance: Instance, world: World, x: Int, z: Int) {
        val definition = instance.definition
        val wave = definition.waves.getOrNull(instance.waveIndex)
        val bossId = wave?.boss ?: definition.boss
        val bossOffset = wave?.bossOffset ?: definition.bossOffset
        val guards = wave?.guards ?: definition.guards
        val offsets = wave?.guardOffsets ?: definition.guardOffsets
        val spawned = mutableListOf<org.bukkit.entity.LivingEntity>()
        val spawnY = overworldLayout.center(instance.slot).y + 1.0
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
                                "message" to plugin.contentText(player, "dungeon", instance.definition.id, "clear_message", instance.definition.clearMessage),
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
                    it.sendMessage(plugin.message(it, "ec.dungeon.clear", "message" to plugin.contentText(it, "dungeon", instance.definition.id, "clear_message", instance.definition.clearMessage)))
                }
            }
        }
    }

    /** プレイヤーをダンジョンから帰還させます。 */
    fun leave(player: Player) {
        leaveInternal(player, forceFallback = false)
    }

    private fun leaveInternal(player: Player, forceFallback: Boolean) {
        val instance = instanceForPlayer(player.uniqueId) ?: return
        leaveOverworld(player, instance, forceFallback)
    }

    /** 地上チャンクを非同期で確保し、安全な地上帰還の完了後に非同期で帰還記録を解除します。 */
    private fun leaveOverworld(player: Player, instance: Instance, forceFallback: Boolean) {
        val id = player.uniqueId
        val now = clock()
        val inFlightSince = leavingPlayers[id]
        if (inFlightSince != null && now - inFlightSince < LEAVE_TIMEOUT_MILLIS) return
        leavingPlayers[id] = now
        val token = ++leaveAttemptSequence
        leaveAttemptTokens[id] = token
        val destination = instance.returns[id]
        val world = destination?.world ?: instanceWorld(instance) ?: run {
            clearLeavingState(id)
            return
        }
        val targetChunkX = (destination?.blockX ?: world.spawnLocation.blockX) shr 4
        val targetChunkZ = (destination?.blockZ ?: world.spawnLocation.blockZ) shr 4
        plugin.withChunk(world, targetChunkX, targetChunkZ) { failure ->
            if (leaveAttemptTokens[id] != token) return@withChunk
            if (instances[instance.slot] !== instance || id !in instance.members) {
                clearLeavingState(id)
                return@withChunk
            }
            if (!player.isOnline) {
                detach(instance, id)
                return@withChunk
            }
            val safeTarget = when {
                failure == null && destination != null && SafeStandingSpot.isSafe(destination) -> destination
                forceFallback -> resolveForcedSafeFallback(world)
                else -> null
            }
            if (safeTarget == null) {
                clearLeavingState(id)
                player.sendMessage(plugin.message(player, "ec.error.teleport_destination"))
                return@withChunk
            }
            val teleported = runCatching { player.teleport(safeTarget) }.getOrDefault(false) ||
                (forceFallback && runCatching { player.teleport(resolveForcedSafeFallback(world)) }.getOrDefault(false))
            if (!teleported) {
                clearLeavingState(id)
                player.sendMessage(plugin.message(player, "ec.error.teleport_destination"))
                return@withChunk
            }
            instance.unreturnedMembers.remove(id)
            detach(instance, id)
            clearReturnRecords(setOf(id), notifyOnFailure = true)
        }
    }

    /** タイムアウト再試行上限超過時に、停滞中チャンク待機を上書きして安全地表へ強制帰還させます。 */
    private fun forceEvacuateOverworldPlayer(player: Player, instance: Instance) {
        val id = player.uniqueId
        val world = instanceWorld(instance) ?: player.world
        val hub = plugin.luminaSpawn()
        if (hub != null) {
            clearLeavingState(id)
            if (runCatching { player.teleport(hub) }.getOrDefault(false)) {
                instance.unreturnedMembers.remove(id)
                detach(instance, id)
                clearReturnRecords(setOf(id), notifyOnFailure = true)
                return
            }
        }
        val spawn = world.spawnLocation
        val token = ++leaveAttemptSequence
        leavingPlayers[id] = clock()
        leaveAttemptTokens[id] = token
        plugin.withChunk(world, spawn.blockX shr 4, spawn.blockZ shr 4) { _ ->
            if (leaveAttemptTokens[id] != token) return@withChunk
            clearLeavingState(id)
            if (instances[instance.slot] !== instance || id !in instance.members) return@withChunk
            if (!player.isOnline) {
                detach(instance, id)
                return@withChunk
            }
            val fallback = resolveForcedSafeFallback(world)
            if (runCatching { player.teleport(fallback) }.getOrDefault(false)) {
                instance.unreturnedMembers.remove(id)
                detach(instance, id)
                clearReturnRecords(setOf(id), notifyOnFailure = true)
            } else {
                player.sendMessage(plugin.message(player, "ec.error.teleport_destination"))
            }
        }
    }

    /** ルミナ港広場・安全なワールドスポーン・最高安全乾燥地表の順で強制帰還先を解決します。 */
    private fun resolveForcedSafeFallback(world: World): Location {
        plugin.luminaSpawn()?.let { return it }
        val spawn = world.spawnLocation
        if (world.isChunkLoaded(spawn.blockX shr 4, spawn.blockZ shr 4)) {
            if (SafeStandingSpot.isSafe(spawn)) return spawn
            val surfaceY = runCatching {
                plugin.resolveProjectionSurfaceY(world, spawn.blockX, spawn.blockZ, allowWater = false)
            }.getOrNull()
            if (surfaceY != null) {
                val candidate = Location(world, spawn.blockX + 0.5, surfaceY.toDouble(), spawn.blockZ + 0.5)
                if (SafeStandingSpot.isSafe(candidate)) return candidate
            }
        }
        return spawn
    }

    private fun clearLeavingState(id: UUID) {
        leavingPlayers.remove(id)
        leaveAttemptTokens.remove(id)
    }

    /** 地上帰還完了後に永続ジャーナルから対象プレイヤーの帰還記録を非同期削除し、最後の記録なら枠を撤去します。 */
    private fun clearReturnRecords(ids: Set<UUID>, notifyOnFailure: Boolean) {
        if (ids.isEmpty()) return
        val snapshotRecords = ids.mapNotNull { pendingReturns[it] }
        val store = returnStore ?: run {
            ids.forEach {
                pendingReturns.remove(it)
                pendingReturnFreezeSince.remove(it)
            }
            maybeCleanupOwnedSlotsForRecords(snapshotRecords)
            return
        }
        store.removeAll(ids).whenComplete { _, removeError ->
            plugin.sync {
                if (removeError == null) {
                    val removed = ids.mapNotNull { id ->
                        pendingReturnFreezeSince.remove(id)
                        pendingReturns.remove(id)
                    }
                    maybeCleanupOwnedSlotsForRecords(removed.ifEmpty { snapshotRecords })
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

    private fun maybeCleanupOwnedSlotsForRecords(records: List<DungeonReturnRecord>) {
        if (shuttingDown || !plugin.isEnabled || records.isEmpty()) return
        val affectedSlots = records.mapNotNull(::findOwnedSlotForRecord).toSet()
        affectedSlots.forEach { slot ->
            val owned = ownedSlots[slot] ?: return@forEach
            if (slot in instances) return@forEach
            if (slotTracker.stateOf(slot) == DungeonSlotState.CLEANING) return@forEach
            if (hasPendingReturnForOwnedSlot(owned)) return@forEach
            startOwnedSlotCleanup(slot)
        }
    }

    private fun detach(instance: Instance, id: UUID) {
        instance.members.remove(id)
        memberToSlot.remove(id, instance.slot)
        clearLeavingState(id)
        if (instance.members.isEmpty()) close(instance)
    }

    private fun close(instance: Instance) {
        if (instances.remove(instance.slot) !== instance) return
        instance.members.forEach { memberToSlot.remove(it, instance.slot) }
        instance.returns.keys.forEach { memberToSlot.remove(it, instance.slot) }
        instance.entities.forEach { Bukkit.getEntity(it)?.remove() }
        instance.entities.clear()
        instance.boss = null
        enemies.removeParticipants(instance.returns.keys)
        if (instance.unreturnedMembers.isEmpty() && !shuttingDown && plugin.isEnabled) {
            cleanupPlacedSlot(instance.slot)
        } else if (!shuttingDown && plugin.isEnabled) {
            protectSlotForPendingReturns(instance.slot)
        } else {
            abandonSlotOnShutdown(instance.slot)
        }
    }

    /** 稼働中・予約中に保持していた配置ハンドルを使って非空気セルを撤去し、所有証跡と枠を解放します。 */
    private fun cleanupPlacedSlot(slot: Int) {
        val operation = placedOperations.remove(slot)
        if (operation == null) {
            if (slot in ownedSlots && !shuttingDown && plugin.isEnabled) {
                startOwnedSlotCleanup(slot)
            } else {
                slotTracker.release(slot)
            }
            return
        }
        if (shuttingDown || !plugin.isEnabled) {
            operation.release(warnIfRetained = false)
            slotTracker.release(slot)
            return
        }
        if (!slotTracker.beginCleaning(slot)) {
            operation.release(warnIfRetained = false)
            return
        }
        retainedPlacedCells.remove(slot)
        cleaningHandles[slot] = operation
        operation.clear { cleared, error ->
            cleaningHandles.remove(slot)
            if (cleared) {
                removeOwnedSlotAndRelease(slot)
            } else {
                if (error != null) {
                    plugin.logger.warning("Dungeon arena cleanup failed for slot $slot: ${error.message}")
                }
                slotTracker.protect(slot)
            }
        }
    }

    /** 未帰還メンバーが残るスロットは床を壊さず PROTECTED に保ち、非空気座標を保持してチケットのみ解放します。 */
    private fun protectSlotForPendingReturns(slot: Int) {
        val operation = placedOperations.remove(slot)
        if (operation != null) {
            val cells = operation.placedCellIndices()
            if (cells.isNotEmpty()) retainedPlacedCells[slot] = cells
            operation.release(warnIfRetained = false)
        }
        slotTracker.protect(slot)
    }

    /** 保護解除後または起動時の孤立した所有スロットを非同期撤去し、完了後に所有証跡と枠を解放します。 */
    private fun startOwnedSlotCleanup(slot: Int) {
        if (shuttingDown || !plugin.isEnabled) return
        val owned = ownedSlots[slot] ?: run {
            slotTracker.release(slot)
            return
        }
        val activeOp = placedOperations.remove(slot)
        if (activeOp != null) {
            if (!slotTracker.tryBeginCleaning(slot)) {
                activeOp.release(warnIfRetained = false)
                return
            }
            retainedPlacedCells.remove(slot)
            cleaningHandles[slot] = activeOp
            activeOp.clear { cleared, error ->
                cleaningHandles.remove(slot)
                if (cleared) {
                    removeOwnedSlotAndRelease(slot)
                } else {
                    if (error != null) {
                        plugin.logger.warning("Dungeon arena cleanup failed for slot $slot: ${error.message}")
                    }
                    slotTracker.protect(slot)
                }
            }
            return
        }
        if (!owned.placementStarted) {
            removeOwnedSlotAndRelease(slot)
            return
        }
        val world = Bukkit.getWorld(owned.world)
        if (world == null) {
            slotTracker.protect(slot)
            plugin.logger.warning("World '${owned.world}' unavailable for owned dungeon slot $slot cleanup; keeping slot protected.")
            return
        }
        if (!slotTracker.tryBeginCleaning(slot)) return
        val bounds = DungeonInstanceBounds(owned.minX, owned.minY, owned.minZ)
        val retained = retainedPlacedCells.remove(slot)
        val cellIndices = retained ?: resolveSchematicNonAirCellIndices(owned.dungeonId)
        val (_, currentPlacement) = ensureOverworldTools(world)
        try {
            val handle = currentPlacement.clearBounds(bounds, cellIndices) { cleared, error ->
                cleaningHandles.remove(slot)
                if (cleared) {
                    removeOwnedSlotAndRelease(slot)
                } else {
                    if (error != null) {
                        plugin.logger.warning("Dungeon owned slot cleanup failed for slot $slot: ${error.message}")
                    }
                    if (retained != null) retainedPlacedCells[slot] = retained
                    slotTracker.protect(slot)
                }
            }
            cleaningHandles[slot] = handle
        } catch (error: Exception) {
            if (retained != null) retainedPlacedCells[slot] = retained
            slotTracker.protect(slot)
            plugin.logger.warning("Could not start owned dungeon slot cleanup for slot $slot: ${error.message}")
        }
    }

    /** 起動時孤立回収の対象をスキーマの非空気セルのみに限定するため、dungeonId から非空気セル配列を再導出します。 */
    private fun resolveSchematicNonAirCellIndices(dungeonId: String): IntArray? {
        val path = WorldSchematicLayout.dungeonPath(dungeonId) ?: return null
        val schematic = loadedSchematics[path] ?: runCatching { SchematicResources.load(path) }
            .onFailure { plugin.logger.warning("Could not load dungeon schematic $path for cleanup: ${it.message}") }
            .getOrNull()?.also { loadedSchematics[path] = it } ?: return null
        if (schematic.width != OverworldDungeonInstanceLayout.ARENA_WIDTH ||
            schematic.height != OverworldDungeonInstanceLayout.ARENA_HEIGHT ||
            schematic.length != OverworldDungeonInstanceLayout.ARENA_WIDTH
        ) {
            return null
        }
        return OverworldDungeonPlacement.nonAirCellIndices(schematic)
    }

    /** 管理者補完向けに、非稼働の保護中・所有済みスロット番号一覧を返します。 */
    internal fun releasableSlots(): List<Int> {
        val candidates = mutableSetOf<Int>()
        candidates += ownedSlots.keys
        for (slot in 0 until OverworldDungeonInstanceLayout.MAX_CANDIDATE_SLOTS) {
            if (slotTracker.stateOf(slot) == DungeonSlotState.PROTECTED) candidates += slot
        }
        candidates.removeAll(instances.keys)
        candidates.removeIf { slot ->
            val state = slotTracker.stateOf(slot)
            state == DungeonSlotState.ACTIVE || state == DungeonSlotState.RESERVED || state == DungeonSlotState.CLEANING
        }
        return candidates.sorted()
    }

    /**
     * 管理者コマンドから非稼働スロットの保護状態・残留所有証跡・未帰還記録を安全に解放・撤去します。
     * ジャーナル二重破損時（journalFailed）でも指定スロットの残骸撤去を実行できます。
     */
    internal fun releaseProtectedSlot(slot: Int): DungeonSlotReleaseResult {
        if (slot in instances) return DungeonSlotReleaseResult.ACTIVE
        val state = slotTracker.stateOf(slot)
        if (state == DungeonSlotState.ACTIVE || state == DungeonSlotState.RESERVED) {
            return DungeonSlotReleaseResult.ACTIVE
        }
        if (state == DungeonSlotState.CLEANING) {
            return DungeonSlotReleaseResult.CLEANING_IN_PROGRESS
        }

        val owned = ownedSlots[slot]
        val worldName = owned?.world ?: plugin.activeWorldProjection.worldName()
        val world = Bukkit.getWorld(worldName)
        if (world != null) resolveOverworldLayout(world, logValidation = false)
        val slotCenter = if (owned != null) {
            DungeonInstanceCenter(
                x = owned.minX + OverworldDungeonInstanceLayout.ARENA_RADIUS,
                y = owned.minY + OverworldDungeonInstanceLayout.FLOOR_OFFSET,
                z = owned.minZ + OverworldDungeonInstanceLayout.ARENA_RADIUS,
            )
        } else {
            overworldLayout.center(slot)
        }
        val staleReturnIds = pendingReturns.entries
            .filter { (_, record) ->
                record.world == worldName &&
                    record.slotX == slotCenter.x &&
                    record.slotY == slotCenter.y &&
                    record.slotZ == slotCenter.z
            }
            .map { it.key }
            .toSet()
        val isProtected = state == DungeonSlotState.PROTECTED
        if (owned == null && staleReturnIds.isEmpty() && !isProtected && !journalFailed) {
            return DungeonSlotReleaseResult.NOT_FOUND
        }

        // 解放対象スロットにオンラインプレイヤーが残っていれば床を消す前に安全な地上へ退避します。
        if (world != null) {
            val fallback = lazy { resolveForcedSafeFallback(world) }
            world.players.forEach { online ->
                if (online.isOnline && isInsideDungeonSlotMargin(slotCenter, online.location.x, online.location.y, online.location.z)) {
                    online.teleport(fallback.value)
                }
            }
        }

        if (staleReturnIds.isNotEmpty()) {
            staleReturnIds.forEach { id ->
                pendingReturns.remove(id)
                pendingReturnFreezeSince.remove(id)
                recoveringPlayers.remove(id)
            }
            val store = returnStore
            if (store != null && !journalFailed) {
                store.removeAll(staleReturnIds).whenComplete { _, error ->
                    if (error != null) {
                        plugin.sync {
                            plugin.logger.warning(
                                "Failed to remove stale dungeon return records during manual release of slot $slot: ${error.message}",
                            )
                        }
                    }
                }
            }
        }

        if (owned != null) {
            if (!owned.placementStarted && placedOperations[slot] == null) {
                removeOwnedSlotAndRelease(slot)
                return DungeonSlotReleaseResult.RELEASED
            }
            startOwnedSlotCleanup(slot)
            return if (slotTracker.stateOf(slot) == DungeonSlotState.CLEANING) {
                DungeonSlotReleaseResult.CLEANING
            } else {
                DungeonSlotReleaseResult.RELEASED
            }
        }

        if (world == null || shuttingDown || !plugin.isEnabled) {
            retainedPlacedCells.remove(slot)
            slotTracker.release(slot)
            return DungeonSlotReleaseResult.RELEASED
        }
        if (!slotTracker.tryBeginCleaning(slot)) {
            return DungeonSlotReleaseResult.CLEANING_IN_PROGRESS
        }
        val bounds = overworldLayout.bounds(slot)
        val retained = retainedPlacedCells.remove(slot)
        val (_, currentPlacement) = ensureOverworldTools(world)
        try {
            val handle = currentPlacement.clearBounds(bounds, retained) { cleared, error ->
                cleaningHandles.remove(slot)
                if (cleared) {
                    slotTracker.release(slot)
                } else {
                    if (error != null) {
                        plugin.logger.warning("Manual cleanup failed for dungeon slot $slot: ${error.message}")
                    }
                    if (retained != null) retainedPlacedCells[slot] = retained
                    slotTracker.protect(slot)
                }
            }
            cleaningHandles[slot] = handle
            return DungeonSlotReleaseResult.CLEANING
        } catch (error: Exception) {
            if (retained != null) retainedPlacedCells[slot] = retained
            slotTracker.protect(slot)
            plugin.logger.warning("Could not start manual cleanup for dungeon slot $slot: ${error.message}")
            return DungeonSlotReleaseResult.RELEASED
        }
    }

    /** 撤去完了後に永続ジャーナルから所有証跡を除去し、完了後にスロットを完全に解放します。 */
    private fun removeOwnedSlotAndRelease(slot: Int, onDone: () -> Unit = {}) {
        val store = returnStore
        if (store == null || journalFailed || slot !in ownedSlots) {
            ownedSlots.remove(slot)
            retainedPlacedCells.remove(slot)
            slotTracker.release(slot)
            onDone()
            return
        }
        store.removeOwnedSlot(slot).whenComplete { _, error ->
            plugin.sync {
                if (error != null) {
                    plugin.logger.warning("Failed to remove owned dungeon slot $slot from journal: ${error.message}")
                }
                ownedSlots.remove(slot)
                retainedPlacedCells.remove(slot)
                slotTracker.release(slot)
                onDone()
            }
        }
    }

    /** シャットダウン時は永続化された所有証跡を残したままチケットとメモリ状態のみ解放します。 */
    private fun abandonSlotOnShutdown(slot: Int) {
        placedOperations.remove(slot)?.release(warnIfRetained = false)
        cleaningHandles.remove(slot)?.release()
        slotTracker.release(slot)
    }

    /** ログイン時に残存ダンジョン記録を確認し、必要に応じて安全地上へ救出します。 */
    fun recover(player: Player) {
        recoverOverworld(player)
    }

    /**
     * 再ログイン時に現在位置（視点回転に左右されない）で地上安全を確認し、
     * 上空残留時は保存先または安全なフォールバックへ救出して記録を解除します。
     * ジャーナル二重破損などで記録が無い場合も、空中スロット内ログインを検知して救出します。
     */
    private fun recoverOverworld(player: Player) {
        val id = player.uniqueId
        val record = pendingReturns[id]
        if (record == null) {
            recoverStrandedOverworldPlayer(player)
            return
        }
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
                    else -> resolveForcedSafeFallback(world)
                }
                if (runCatching { player.teleport(rescueTarget) }.getOrDefault(false)) {
                    clearReturnRecords(setOf(id), notifyOnFailure = false)
                } else {
                    plugin.requestHubSpawn(player)
                }
            }
        }
    }

    /** 帰還記録が無い状態でも、設定済み空中ダンジョン枠内に残留してログインしたプレイヤーを安全地上へ救出します。 */
    private fun recoverStrandedOverworldPlayer(player: Player) {
        val id = player.uniqueId
        if (containsPlayer(id) || id in enteringPlayers) return
        val world = player.world
        if (world.name != plugin.activeWorldProjection.worldName()) return
        val layout = resolveOverworldLayout(world, logValidation = false)
        val location = player.location
        val insideAnySlot = (0 until OverworldDungeonInstanceLayout.MAX_CANDIDATE_SLOTS).any { slot ->
            val center = runCatching { layout.center(slot) }.getOrNull() ?: return@any false
            isInsideDungeonSlotMargin(center, location.x, location.y, location.z)
        }
        if (!insideAnySlot) return
        if (!recoveringPlayers.add(id)) return
        plugin.logger.warning(
            "Rescuing player ${player.name} ($id) stranded in overworld dungeon airspace without a pending return record.",
        )
        val hub = plugin.luminaSpawn()
        if (hub != null) {
            recoveringPlayers.remove(id)
            if (!runCatching { player.teleport(hub) }.getOrDefault(false)) {
                plugin.requestHubSpawn(player)
            }
            return
        }
        val spawn = world.spawnLocation
        plugin.withChunk(world, spawn.blockX shr 4, spawn.blockZ shr 4) { _ ->
            recoveringPlayers.remove(id)
            if (!player.isOnline || containsPlayer(id)) return@withChunk
            val fallback = resolveForcedSafeFallback(world)
            if (!runCatching { player.teleport(fallback) }.getOrDefault(false)) {
                plugin.requestHubSpawn(player)
            }
        }
    }

    private fun instanceWorld(instance: Instance): World? = Bukkit.getWorld(plugin.activeWorldProjection.worldName())

    private fun instanceCenter(instance: Instance): Pair<Int, Int> =
        overworldLayout.center(instance.slot).let { it.x to it.z }

    /** 制限時間超過インスタンスの全メンバーを安全に帰還させ、失敗時は再試行と強制フォールバックを行います。 */
    fun tick() {
        tick += PaperTaskIntervals.PLAYER_UPDATE_TICKS
        val now = clock()
        val expiredLeaves = leavingPlayers.entries
            .filter { (_, startedAt) -> now - startedAt >= LEAVE_TIMEOUT_MILLIS }
            .map { it.key }
        expiredLeaves.forEach(::clearLeavingState)

        val timeout = plugin.snapshot.world.tuning.instanceTimeout
        instances.values.toList()
            .filter { tick - it.started >= timeout }
            .forEach { instance ->
                if (!instance.timingOut) {
                    instance.timingOut = true
                    instance.lastTimeoutRetryTick = tick
                    instance.timeoutRetryAttempts = 1
                    attemptTimeoutEvacuation(instance, notify = true, forceImmediateFallback = false)
                    return@forEach
                }
                if (tick - instance.lastTimeoutRetryTick < TIMEOUT_RETRY_INTERVAL_TICKS) return@forEach
                instance.lastTimeoutRetryTick = tick
                instance.timeoutRetryAttempts++
                val escalateFallback = instance.timeoutRetryAttempts >= MAX_TIMEOUT_RETRY_ATTEMPTS
                attemptTimeoutEvacuation(instance, notify = false, forceImmediateFallback = escalateFallback)
            }
    }

    private fun attemptTimeoutEvacuation(instance: Instance, notify: Boolean, forceImmediateFallback: Boolean) {
        val members = instance.members.toList()
        if (members.isEmpty()) {
            close(instance)
            return
        }
        members.forEach { memberId ->
            val player = Bukkit.getPlayer(memberId)
            if (player == null || !player.isOnline) {
                detach(instance, memberId)
            } else if (forceImmediateFallback) {
                forceEvacuateOverworldPlayer(player, instance)
            } else {
                if (notify) player.sendMessage(plugin.message(player, "ec.dungeon.timeout"))
                leaveInternal(player, forceFallback = true)
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun teleport(e: PlayerTeleportEvent) {
        val id = e.player.uniqueId
        if (id in leavingPlayers) return
        val instance = instanceForPlayer(id) ?: return
        val to = e.to
        val bounds = overworldLayout.bounds(instance.slot)
        val inside = to.world?.name == plugin.activeWorldProjection.worldName() &&
            to.x >= bounds.minX && to.x < bounds.maxX + 1 &&
            to.y >= bounds.minY && to.y < bounds.maxY + 1 &&
            to.z >= bounds.minZ && to.z < bounds.maxZ + 1
        if (!inside) {
            val sameWorld = to.world?.name == plugin.activeWorldProjection.worldName()
            val toSafe = sameWorld &&
                to.world != null &&
                to.world.isChunkLoaded(to.blockX shr 4, to.blockZ shr 4) &&
                SafeStandingSpot.isSafe(to)
            val slotCenter = overworldLayout.center(instance.slot)
            if (canAcknowledgeDungeonReturn(slotCenter, to.x, to.y, to.z, sameWorld, toSafe)) {
                instance.unreturnedMembers.remove(id)
                detach(instance, id)
                clearReturnRecords(setOf(id), notifyOnFailure = true)
                return
            }
            e.player.sendMessage(plugin.message(e.player, "ec.dungeon.relogin_required"))
            detach(instance, id)
        }
    }

    @EventHandler
    fun quit(e: PlayerQuitEvent) {
        val id = e.player.uniqueId
        pendingReturnFreezeSince.remove(id)
        recoveringPlayers.remove(id)
        val instance = instanceForPlayer(id) ?: return
        detach(instance, id)
    }

    /** 戻り先チャンクを非同期で読み込み、復活が済んだ後に安全であれば戻り先へ移動させます。 */
    private fun teleportToReturnLater(player: Player, destination: Location) {
        val world = destination.world ?: return
        plugin.withChunk(world, destination.blockX shr 4, destination.blockZ shr 4) { failure ->
            if (failure != null) return@withChunk
            // 復活処理（リスポーン画面の解除）が終わるのを少し待ってから移動します。
            plugin.server.scheduler.runTaskLater(plugin, Runnable {
                if (player.isOnline && !player.isDead && SafeStandingSpot.isSafe(destination)) player.teleport(destination)
            }, RESPAWN_RETURN_DELAY_TICKS)
        }
    }

    @EventHandler
    fun respawn(e: PlayerRespawnEvent) {
        val id = e.player.uniqueId
        val instance = instanceForPlayer(id) ?: return
        val destination = instance.returns[id]
        val safeNow = destination != null && SafeStandingSpot.isSafe(destination)
        e.respawnLocation = if (safeNow) destination!! else resolveForcedSafeFallback(e.player.world)
        // 戻り先チャンクが未ロードなら、まず安全な場所へ復活させ、読込後に本来の戻り先へ移します。
        if (destination != null && !safeNow) teleportToReturnLater(e.player, destination)
        instance.unreturnedMembers.remove(id)
        detach(instance, id)
        plugin.sync {
            if (e.player.isOnline) clearReturnRecords(setOf(id), notifyOnFailure = true)
        }
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
                val targetWorld = Bukkit.getWorld(pending.world) ?: e.player.world
                e.to = resolveForcedSafeFallback(targetWorld)
                clearReturnRecords(setOf(id), notifyOnFailure = false)
                return
            }
            // ログイン直後に救出が終わるまで、残存スロットからの落下を止めます。
            e.to = e.from
            return
        }
        val instance = instanceForPlayer(id)
        if (instance != null && e.to.y < overworldLayout.bounds(instance.slot).minY) {
            val center = overworldLayout.center(instance.slot)
            e.to = Location(e.player.world, center.x + 0.5, center.y + 2.0, center.z + 0.5)
            leave(e.player)
        }
    }

    /** プラグイン停止時に全インスタンス・進行中操作・IO ストアを安全に閉じます。 */
    fun shutdown() {
        shuttingDown = true
        instances.values.toList().forEach { instance ->
            instance.members.toList().forEach { id ->
                detach(instance, id)
            }
            close(instance)
        }
        memberToSlot.clear()
        placedOperations.values.toList().forEach { it.release(warnIfRetained = false) }
        placedOperations.clear()
        cleaningHandles.values.toList().forEach { it.release() }
        cleaningHandles.clear()
        retainedPlacedCells.clear()
        slotTracker.clear()
        pendingReturnFreezeSince.clear()
        recoveringPlayers.clear()
        enteringPlayers.clear()
        leavingPlayers.clear()
        leaveAttemptTokens.clear()
        placement?.close()
        scanner?.close()
        returnStore?.close()
    }

    private companion object {
        const val DEFAULT_DUNGEON_ID = "ec:dungeon_void_sanctum"
        /** 入場時の出現位置を、アリーナ中心から北（-Z）へずらす距離です。 */
        const val PLAYER_SPAWN_Z_OFFSET = -12
        /** リスポーン完了を待ってから戻り先へ移すまでの tick 数です。 */
        const val RESPAWN_RETURN_DELAY_TICKS = 5L
        /** この距離を超えると「入口へ向かう」案内を出します（入口チャンクの未ロード判定用）。 */
        const val PORTAL_NEAR_BLOCKS = 48.0
        const val RETURN_JOURNAL_FILE = "dungeon-returns.json"
        const val SCAN_BUDGET = 4_096
        const val RECOVERY_FREEZE_TIMEOUT_MILLIS = 10_000L
        const val LEAVE_TIMEOUT_MILLIS = 10_000L
        const val TIMEOUT_RETRY_INTERVAL_TICKS = 40L
        const val MAX_TIMEOUT_RETRY_ATTEMPTS = 3
        const val CONSECUTIVE_OCCUPIED_WARN_THRESHOLD = 3
    }
}
