package net.nakaymi.etherealchronicle.paper

import net.nakaymi.etherealchronicle.core.OverworldSite
import net.nakaymi.etherealchronicle.core.OverworldSiteIds
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.plugin.Plugin
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.logging.Level

/** 探索、建設journalとtick予算付き配置をつなぎます。起動・停止はメインスレッドから呼びます。 */
class OverworldWorldService internal constructor(
    private val plugin: Plugin,
    private val world: World,
    private val loadAssets: () -> OverworldAssets,
) : AutoCloseable {
    constructor(plugin: Plugin, world: World) : this(plugin, world, { OverworldAssets.load() })
    private val store = OverworldLayoutStore(world.worldFolder.toPath())
    private val worldId = world.uid
    private val worldName = world.name
    private val worldSeed = world.seed
    private val io = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "ec-overworld-io").apply { isDaemon = true } }
    private val tickets = mutableListOf<Chunk>()
    private var active: CompletableFuture<OverworldLayout>? = null
    private var assets: OverworldAssets? = null
    @Volatile private var layout: OverworldLayout? = null
    @Volatile private var closed = false
    private var siteSearch: OverworldSiteSearch? = null

    /** 起動済み状態の読み出しと新規ワールドの事前検証を開始します。 */
    fun start(onSiteReady: (OverworldLayout) -> Unit = {}): CompletableFuture<OverworldLayout> {
        checkAvailable()
        val result = CompletableFuture<OverworldLayout>()
        active = result
        CompletableFuture.supplyAsync({
            val existing = store.load(worldId, worldName, worldSeed)
            val loaded = if (existing?.status == OverworldBuildStatus.COMPLETED) null else loadAssets()
            existing to loaded
        }, io).whenComplete { loaded, failure -> main(result) {
            if (failure != null) return@main fail(result, failure)
            val (existing, loadedAssets) = loaded
            loadedAssets?.validatePalettes()
            assets = loadedAssets
            layout = existing
            when (existing?.status) {
                OverworldBuildStatus.COMPLETED -> finish(result, existing)
                OverworldBuildStatus.IN_PROGRESS -> {
                    plugin.logger.warning("Overworld generation was interrupted; automatic rebuilding is disabled. An administrator must inspect and request repair.")
                    finish(result, existing)
                }
                null -> OverworldSiteSearch(plugin, world).also { siteSearch = it }.start().whenComplete { sites, error -> main(result) {
                    siteSearch = null
                    if (error != null) return@main fail(result, error)
                    val initial = OverworldLayout(worldId = worldId.toString(), worldName = worldName, seed = worldSeed,
                        status = OverworldBuildStatus.IN_PROGRESS, sites = sites.map { OverworldSiteLayout(it, OverworldSiteStatus.PENDING) })
                    save(initial, result) { generateNext(initial, result, onSiteReady) }
                } }
            }
        } }
        return result
    }

    /** 管理者による明示的な復旧を開始し、保留中の拠点のみ再配置します。 */
    fun repair(onSiteReady: (OverworldLayout) -> Unit = {}): CompletableFuture<OverworldLayout> {
        checkAvailable()
        val current = checkNotNull(layout) { "No overworld journal is loaded" }
        check(current.status == OverworldBuildStatus.IN_PROGRESS) { "Overworld generation is already complete" }
        check(current.sites.none { it.status == OverworldSiteStatus.GENERATING || it.status == OverworldSiteStatus.FAILED }) {
            "Interrupted site requires administrator inspection and explicit cleanup confirmation before repair"
        }
        checkNotNull(assets) { "Required overworld schematics are unavailable" }
        val result = CompletableFuture<OverworldLayout>()
        active = result
        // 中断した敷地は自動破壊せず再検査し、既存建築が障害になれば復旧を止めます。
        try {
            generateNext(current, result, onSiteReady)
        } catch (error: Throwable) {
            if (error is OutOfMemoryError) throw error
            fail(result, error)
        }
        return result
    }

    /** 管理者が中断敷地を手動復旧した後、次回repair対象として記録します。 */
    fun acknowledgeCleanup(siteId: String): CompletableFuture<OverworldLayout> {
        checkAvailable()
        val current = checkNotNull(layout) { "No overworld journal is loaded" }
        check(current.status == OverworldBuildStatus.IN_PROGRESS)
        val site = current.sites.singleOrNull { it.site.id == siteId } ?: error("Unknown overworld site: $siteId")
        check(site.status == OverworldSiteStatus.GENERATING || site.status == OverworldSiteStatus.FAILED) { "Site does not require cleanup" }
        val reset = replaceSite(current, site.copy(status = OverworldSiteStatus.PENDING, anchors = emptyMap()))
            .copy(portals = current.portals.filterNot { it.areaId == siteId })
        val result = CompletableFuture<OverworldLayout>()
        active = result
        save(reset, result) { finish(result, reset) }
        return result
    }

    /** 読込済みの進捗を参照します。 */
    fun current(): OverworldLayout? = layout

    /** 公開操作はメインスレッドで直列に実行し、停止後の再開を防ぎます。 */
    private fun checkAvailable() {
        check(Bukkit.isPrimaryThread()) { "Overworld service must be called on the primary thread" }
        check(!closed) { "Overworld service is closed" }
        check(active == null) { "Overworld operation is already active" }
    }

    /** 次の未完成拠点へ進み、全拠点が揃った場合だけ完了マーカーを書きます。 */
    private fun generateNext(current: OverworldLayout, result: CompletableFuture<OverworldLayout>, onSiteReady: (OverworldLayout) -> Unit) {
        val next = current.sites.firstOrNull { it.status != OverworldSiteStatus.READY }
        if (next == null) {
            val complete = current.copy(status = OverworldBuildStatus.COMPLETED)
            save(complete, result, mark = true) {
                plugin.logger.info("Overworld generation complete: 9/9 sites ready")
                finish(result, complete)
            }
            return
        }
        val readyCount = current.sites.count { it.status == OverworldSiteStatus.READY }
        plugin.logger.info(
            "Preparing overworld site (${readyCount + 1}/${current.sites.size}): ${next.site.id} at (${next.site.x}, ${next.site.y}, ${next.site.z})",
        )
        loadChunks(next.site, result) { planSite(current, next, result, onSiteReady) }
    }

    /** 地形計画を分割実行し、成功した計画だけを建設ジャーナルへ渡します。 */
    private fun planSite(current: OverworldLayout, next: OverworldSiteLayout, result: CompletableFuture<OverworldLayout>, onSiteReady: (OverworldLayout) -> Unit) {
        val assembler = OverworldTownAssembler(checkNotNull(assets))
        advancePlanning(assembler.planning(world, next.site, worldSeed), result) { plan ->
            val generating = replaceSite(current, next.copy(status = OverworldSiteStatus.GENERATING, anchors = plan.anchors))
                .copy(portals = current.portals.filterNot { it.areaId == next.site.id } + plan.portals)
            save(generating, result) {
                applyOperations(assembler.actions(world, plan), result) {
                    finishSite(generating, next, plan, result, onSiteReady)
                }
            }
        }
    }

    /** 配置終了後のスポーンを検証し、READY 保存と共有スポーン反映を順に行います。 */
    private fun finishSite(generating: OverworldLayout, next: OverworldSiteLayout, plan: OverworldTownPlan,
        result: CompletableFuture<OverworldLayout>, onSiteReady: (OverworldLayout) -> Unit,
    ) {
        val spawn = if (next.site.id == OverworldSiteIds.LUMINA_PORT) findPlazaSpawn(plan.anchors) else null
        val anchors = if (spawn == null) plan.anchors else plan.anchors + ("spawn" to spawn)
        val ready = replaceSite(generating, next.copy(status = OverworldSiteStatus.READY, anchors = anchors))
        if (spawn != null) check(isSafeSpawn(spawn)) { "Lumina spawn became unsafe before READY save" }
        flushWorldBeforeReady()
        save(ready, result) {
            if (spawn != null && (!isSafeSpawn(spawn) || !world.setSpawnLocation(Location(world, spawn.x + 0.5, spawn.y.toDouble(), spawn.z + 0.5, 0f, 0f)))) {
                val failed = replaceSite(ready, next.copy(status = OverworldSiteStatus.FAILED, anchors = anchors))
                save(failed, result) { fail(result, IllegalStateException("Could not set Lumina spawn")) }
                return@save
            }
            val readyCount = ready.sites.count { it.status == OverworldSiteStatus.READY }
            plugin.logger.info(
                "Overworld site ready ($readyCount/${ready.sites.size}): ${next.site.id} " +
                    "(buildings=${plan.buildings.size}, portals=${plan.portals.size})",
            )
            releaseTickets()
            onSiteReady(ready)
            generateNext(ready, result, onSiteReady)
        }
    }

    /** 広場の床上から、足元が安全で頭上に余裕がある地点を中央優先で選びます。 */
    private fun findPlazaSpawn(anchors: Map<String, OverworldPoint>): OverworldPoint {
        val min = anchors.getValue("plaza_min")
        val max = anchors.getValue("plaza_max")
        val center = anchors.getValue("plaza_center")
        return (min.x..max.x).flatMap { x -> (min.z..max.z).map { z -> OverworldPoint(x, center.y, z) } }
            .sortedBy { point -> kotlin.math.abs(point.x - center.x) + kotlin.math.abs(point.z - center.z) }
            .firstOrNull(::isSafeSpawn) ?: error("No safe spawn in Lumina plaza")
    }

    /** サイトを READY 記録する前にワールドの更新済みチャンクをディスクへ反映します。 */
    private fun flushWorldBeforeReady() {
        try {
            world.save(true)
        } catch (error: UnsupportedOperationException) {
            if (!plugin.server.javaClass.name.startsWith("org.mockbukkit.")) throw error
        }
    }

    /** 地面・足元・頭上を検証し、危険ブロックと水中へのスポーンを避けます。 */
    private fun isSafeSpawn(point: OverworldPoint): Boolean {
        if (point.y <= world.minHeight || point.y + 1 >= world.maxHeight) return false
        val floor = world.getBlockAt(point.x, point.y - 1, point.z).type
        return floor.isSolid && floor !in unsafeSpawnFloors && world.getBlockAt(point.x, point.y, point.z).type.isAir &&
            world.getBlockAt(point.x, point.y + 1, point.z).type.isAir
    }

    /** 敷地候補の検証を tick 時間予算と上限ステップの範囲内で進めます。 */
    private fun advancePlanning(planning: OverworldTownPlanning, result: CompletableFuture<OverworldLayout>, done: (OverworldTownPlan) -> Unit) {
        try {
            val deadline = System.nanoTime() + PLANNING_TIME_BUDGET_NANOS
            var steps = 0
            while (steps < MAX_PLANNING_STEPS_PER_TICK && (steps == 0 || System.nanoTime() < deadline)) {
                if (!planning.steps.hasNext()) { done(planning.result()); return }
                planning.steps.next()
                steps++
            }
            main(result, 1L) { advancePlanning(planning, result, done) }
        } catch (error: Throwable) {
            if (error is OutOfMemoryError) throw error
            fail(result, error)
        }
    }

    /** 少数のチャンクを並列取得し、完了コールバック内で即時チケット確保して建設中保持します。 */
    private fun loadChunks(site: OverworldSite, result: CompletableFuture<OverworldLayout>, done: () -> Unit) {
        val radius = OverworldTownAssembler.loadRadius(site.id)
        val minX = (site.x - radius) shr CHUNK_SHIFT
        val maxX = (site.x + radius) shr CHUNK_SHIFT
        val minZ = (site.z - radius) shr CHUNK_SHIFT
        val maxZ = (site.z + radius) shr CHUNK_SHIFT
        val positions = (minX..maxX).flatMap { x -> (minZ..maxZ).map { z -> x to z } }.iterator()
        var inFlight = 0
        var pumpScheduled = false
        var completed = false
        lateinit var pump: () -> Unit
        fun schedulePump() {
            if (pumpScheduled || completed || result.isDone || closed) return
            pumpScheduled = true
            main(result, 1L) {
                pumpScheduled = false
                pump()
            }
        }
        pump = {
            if (!result.isDone && !closed && !completed) {
                if (!positions.hasNext() && inFlight == 0) {
                    completed = true
                    done()
                } else {
                    while (inFlight < CHUNK_REQUEST_WINDOW && positions.hasNext()) {
                        val (x, z) = positions.next()
                        inFlight++
                        fun request(attempt: Int) {
                            world.getChunkAtAsync(x, z, true).whenComplete { chunk, error ->
                                val handle: () -> Unit = {
                                    if (error != null) {
                                        fail(result, error)
                                    } else if (chunk == null || !world.isChunkLoaded(x, z)) {
                                        if (attempt < MAX_CHUNK_RETRIES) main(result, 1L) { request(attempt + 1) }
                                        else fail(result, IllegalStateException("Overworld chunk repeatedly unloaded at $x,$z"))
                                    } else {
                                        if (chunk.addPluginChunkTicket(plugin)) tickets += chunk
                                        inFlight--
                                        schedulePump()
                                    }
                                }
                                if (Bukkit.isPrimaryThread()) {
                                    if (!result.isDone && !closed) {
                                        try { handle() } catch (t: Throwable) {
                                            if (t is OutOfMemoryError) throw t
                                            fail(result, t)
                                        }
                                    }
                                } else {
                                    main(result, 0L, handle)
                                }
                            }
                        }
                        request(0)
                    }
                }
            }
        }
        pump()
    }

    /** 毎tickの配置操作を時間予算（2ms）と列操作上限で制限し、TPS スパイクを防ぎます。 */
    private fun applyOperations(operations: Iterator<() -> Unit>, result: CompletableFuture<OverworldLayout>, done: () -> Unit) {
        fun tick() {
            try {
                val deadline = System.nanoTime() + OPERATIONS_TIME_BUDGET_NANOS
                var ops = 0
                while (ops < MAX_OPERATIONS_PER_TICK && (ops == 0 || System.nanoTime() < deadline)) {
                    if (!operations.hasNext()) { done(); return }
                    operations.next().invoke()
                    ops++
                }
                main(result, 1L, ::tick)
            } catch (error: Throwable) {
                if (error is OutOfMemoryError) throw error
                fail(result, error)
            }
        }
        tick()
    }

    private fun replaceSite(current: OverworldLayout, updated: OverworldSiteLayout): OverworldLayout =
        current.copy(sites = current.sites.map { if (it.site.id == updated.site.id) updated else it })

    /** 同期書込を別スレッドへ移し、保存完了後にのみ次の変更へ進みます。 */
    private fun save(next: OverworldLayout, result: CompletableFuture<OverworldLayout>, mark: Boolean = false, done: () -> Unit) {
        CompletableFuture.runAsync({ store.save(next); if (mark) store.markCompleted() }, io).whenComplete { _, error -> main(result) {
            if (error != null) return@main fail(result, error)
            layout = next
            done()
        } }
    }

    /** 非同期完了処理をメインスレッドへ戻し、例外時のチケット解放へつなぎます。 */
    private fun main(result: CompletableFuture<OverworldLayout>, delay: Long = 0, action: () -> Unit) {
        if (result.isDone || closed) return
        try {
            val task = Runnable { if (!result.isDone && !closed) try { action() } catch (error: Throwable) {
                if (error is OutOfMemoryError) throw error
                fail(result, error)
            } }
            if (delay == 0L) plugin.server.scheduler.runTask(plugin, task) else plugin.server.scheduler.runTaskLater(plugin, task, delay)
        } catch (error: Throwable) {
            if (error is OutOfMemoryError) throw error
            // スケジューラ停止中に非同期コールバックが到着しても、そこでBukkitへ触れません。
            if (Bukkit.isPrimaryThread()) fail(result, error) else result.completeExceptionally(error)
        }
    }

    private fun finish(result: CompletableFuture<OverworldLayout>, value: OverworldLayout) {
        active = null
        result.complete(value)
    }

    private fun fail(result: CompletableFuture<OverworldLayout>, error: Throwable) {
        releaseTickets()
        active = null
        plugin.logger.log(Level.SEVERE, "Overworld generation stopped without marking completion", error)
        result.completeExceptionally(error)
    }

    private fun releaseTickets() {
        tickets.forEach { it.removePluginChunkTicket(plugin) }
        tickets.clear()
    }

    /** 保持中チャンクと非同期I/Oを閉じます。 */
    override fun close() {
        check(Bukkit.isPrimaryThread()) { "Overworld service must close on the primary thread" }
        closed = true
        siteSearch?.cancel()
        siteSearch = null
        releaseTickets()
        active?.completeExceptionally(IllegalStateException("Overworld service stopped"))
        active = null
        io.shutdown()
        if (!io.awaitTermination(IO_CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            plugin.logger.warning("Overworld journal writer did not stop within the close timeout")
        }
    }

    private companion object {
        const val CHUNK_SHIFT = 4
        /** 1 tick あたりの計画時間予算（2ms）と上限ステップ数です。 */
        const val PLANNING_TIME_BUDGET_NANOS = 2_000_000L
        const val MAX_PLANNING_STEPS_PER_TICK = 256
        /** 列単位の配置操作は1操作で複数ブロックを書くため、時間予算（2ms）と上限64列で制限します。 */
        const val OPERATIONS_TIME_BUDGET_NANOS = 2_000_000L
        const val MAX_OPERATIONS_PER_TICK = 64
        const val IO_CLOSE_TIMEOUT_SECONDS = 2L
        const val MAX_CHUNK_RETRIES = 5
        const val CHUNK_REQUEST_WINDOW = 8
        val unsafeSpawnFloors = setOf(Material.MAGMA_BLOCK, Material.CACTUS, Material.CAMPFIRE, Material.SOUL_CAMPFIRE)
    }
}
