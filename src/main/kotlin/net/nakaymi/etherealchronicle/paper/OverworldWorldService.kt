package net.nakaymi.etherealchronicle.paper

import net.nakaymi.etherealchronicle.core.OverworldSite
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.plugin.Plugin
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

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
    private var closed = false

    /** 起動済み状態の読み出しと新規ワールドの事前検証を開始します。 */
    fun start(onSiteReady: (OverworldLayout) -> Unit = {}): CompletableFuture<OverworldLayout> {
        check(Bukkit.isPrimaryThread() && !closed && active == null)
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
                null -> OverworldSiteSearch(plugin, world).start().whenComplete { sites, error -> main(result) {
                    if (error != null) return@main fail(result, error)
                    val initial = OverworldLayout(worldId = worldId.toString(), worldName = worldName, seed = worldSeed,
                        status = OverworldBuildStatus.IN_PROGRESS, sites = sites.map { OverworldSiteLayout(it, OverworldSiteStatus.PENDING) })
                    save(initial, result) { generateNext(initial, result, onSiteReady) }
                } }
            }
        } }
        return result
    }

    /** 管理者による明示的な復旧を開始し、完了済みの拠点を再配置しません。 */
    fun repair(onSiteReady: (OverworldLayout) -> Unit = {}): CompletableFuture<OverworldLayout> {
        check(Bukkit.isPrimaryThread() && !closed && active == null)
        val current = checkNotNull(layout) { "No overworld journal is loaded" }
        check(current.status == OverworldBuildStatus.IN_PROGRESS) { "Overworld generation is already complete" }
        check(current.sites.none { it.status == OverworldSiteStatus.GENERATING || it.status == OverworldSiteStatus.FAILED }) {
            "Interrupted site requires administrator inspection and explicit cleanup confirmation before repair"
        }
        val loadedAssets = checkNotNull(assets) { "Required overworld schematics are unavailable" }
        val result = CompletableFuture<OverworldLayout>()
        active = result
        // 中断した敷地は自動破壊せず再検査し、既存建築が障害になれば復旧を止めます。
        assets = loadedAssets
        generateNext(current, result, onSiteReady)
        return result
    }

    /** 管理者が中断敷地を手動復旧した後、次回repair対象として記録します。 */
    fun acknowledgeCleanup(siteId: String): CompletableFuture<OverworldLayout> {
        check(Bukkit.isPrimaryThread() && !closed && active == null)
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
        loadChunks(next.site, result) {
                val assembler = OverworldTownAssembler(checkNotNull(assets))
                val planning = assembler.planning(world, next.site, worldSeed)
                advancePlanning(planning, result) { plan ->
                    val withAnchors = replaceSite(current, next.copy(status = OverworldSiteStatus.GENERATING, anchors = plan.anchors))
                        .copy(portals = current.portals.filterNot { it.areaId == next.site.id } + plan.portals)
                    save(withAnchors, result) {
                        val operations = assembler.actions(world, plan)
                        applyOperations(operations, result) {
                            val spawn = if (next.site.id == "ec:area_lumina_port") findPlazaSpawn(plan.anchors) else null
                            val finalAnchors = if (spawn == null) plan.anchors else plan.anchors + ("spawn" to spawn)
                            val ready = replaceSite(withAnchors, next.copy(status = OverworldSiteStatus.READY, anchors = finalAnchors))
                            if (spawn != null && (!isSafeSpawn(spawn) || !world.setSpawnLocation(Location(world, spawn.x + 0.5, spawn.y.toDouble(), spawn.z + 0.5, 0f, 0f)))) {
                                val failed = replaceSite(withAnchors, next.copy(status = OverworldSiteStatus.FAILED, anchors = finalAnchors))
                                return@applyOperations save(failed, result) { fail(result, IllegalStateException("Could not set a safe Lumina spawn")) }
                            }
                            save(ready, result) {
                                releaseTickets()
                                onSiteReady(ready)
                                generateNext(ready, result, onSiteReady)
                            }
                        }
                    }
                }
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

    /** 地面・足元・頭上を検証し、危険ブロックと水中へのスポーンを避けます。 */
    private fun isSafeSpawn(point: OverworldPoint): Boolean {
        if (point.y <= world.minHeight || point.y + 1 >= world.maxHeight) return false
        val floor = world.getBlockAt(point.x, point.y - 1, point.z).type
        return floor.isSolid && floor !in unsafeSpawnFloors && world.getBlockAt(point.x, point.y, point.z).type.isAir &&
            world.getBlockAt(point.x, point.y + 1, point.z).type.isAir
    }

    /** 敷地候補の検証を1 tickに1回ずつ実行します。 */
    private fun advancePlanning(planning: OverworldTownPlanning, result: CompletableFuture<OverworldLayout>, done: (OverworldTownPlan) -> Unit) {
        try {
            repeat(PLANNING_STEPS_PER_TICK) {
                if (!planning.steps.hasNext()) { done(planning.result()); return }
                planning.steps.next()
            }
            main(result, 1L) { advancePlanning(planning, result, done) }
        } catch (error: Exception) {
            fail(result, error)
        }
    }

    /** 一度に一つのチャンクだけ要求し、建設中は明示的に保持します。 */
    private fun loadChunks(site: OverworldSite, result: CompletableFuture<OverworldLayout>, done: () -> Unit) {
        val minX = (site.x - SITE_RADIUS) shr 4
        val maxX = (site.x + SITE_RADIUS) shr 4
        val minZ = (site.z - SITE_RADIUS) shr 4
        val maxZ = (site.z + SITE_RADIUS) shr 4
        val positions = (minX..maxX).flatMap { x -> (minZ..maxZ).map { z -> x to z } }.iterator()
        fun next() {
            if (result.isDone) return
            if (!positions.hasNext()) { done(); return }
            val (x, z) = positions.next()
            fun request() {
                world.getChunkAtAsync(x, z, true).whenComplete { chunk, error -> main(result, 1L) {
                    if (error != null) return@main fail(result, error)
                    if (!world.isChunkLoaded(x, z)) { request(); return@main }
                    if (chunk.addPluginChunkTicket(plugin)) tickets += chunk
                    next()
                } }
            }
            request()
        }
        next()
    }

    /** 毎tickの処理数を制限し、例外時はjournalを未完了のまま残します。 */
    private fun applyOperations(operations: Iterator<() -> Unit>, result: CompletableFuture<OverworldLayout>, done: () -> Unit) {
        fun tick() {
            try {
                repeat(OPERATIONS_PER_TICK) {
                    if (!operations.hasNext()) { done(); return }
                    operations.next().invoke()
                }
                main(result, 1L, ::tick)
            } catch (error: Exception) {
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

    private fun main(result: CompletableFuture<OverworldLayout>, delay: Long = 0, action: () -> Unit) {
        if (result.isDone || closed) return
        try {
            val task = Runnable { if (!result.isDone && !closed) try { action() } catch (error: Exception) { fail(result, error) } }
            if (delay == 0L) plugin.server.scheduler.runTask(plugin, task) else plugin.server.scheduler.runTaskLater(plugin, task, delay)
        } catch (error: Exception) {
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
        plugin.logger.severe("Overworld generation stopped without marking completion: ${error.message}")
        result.completeExceptionally(error)
    }

    private fun releaseTickets() {
        tickets.forEach { it.removePluginChunkTicket(plugin) }
        tickets.clear()
    }

    /** 保持中チャンクと非同期I/Oを閉じます。 */
    override fun close() {
        check(Bukkit.isPrimaryThread())
        closed = true
        releaseTickets()
        active?.completeExceptionally(IllegalStateException("Overworld service stopped"))
        active = null
        io.shutdown()
    }

    private companion object {
        const val SITE_RADIUS = 112
        const val PLANNING_STEPS_PER_TICK = 32
        const val OPERATIONS_PER_TICK = 500
        val unsafeSpawnFloors = setOf(Material.MAGMA_BLOCK, Material.CACTUS, Material.CAMPFIRE, Material.SOUL_CAMPFIRE)
    }
}
