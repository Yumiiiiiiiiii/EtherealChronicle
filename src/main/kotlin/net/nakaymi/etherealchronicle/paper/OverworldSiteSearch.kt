package net.nakaymi.etherealchronicle.paper

import net.nakaymi.etherealchronicle.core.OverworldSite
import net.nakaymi.etherealchronicle.core.OverworldSiteLocator
import net.nakaymi.etherealchronicle.core.SiteTerrain
import org.bukkit.Bukkit
import org.bukkit.HeightMap
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.World
import org.bukkit.plugin.Plugin
import java.util.Locale
import java.util.concurrent.CompletableFuture

/** Paperの非同期チャンク取得と並列先読み・決定順の地形サンプリングを接続します。 */
internal class OverworldSiteSearch(private val plugin: Plugin, private val world: World) {
    private val search = OverworldSiteLocator.Search(world.seed)
    private val result = CompletableFuture<List<OverworldSite>>()
    private val sampled = mutableMapOf<Pair<Int, Int>, SiteTerrain>()
    private val inFlight = mutableSetOf<Pair<Int, Int>>()
    private var siteEpoch = 0
    private var pumpScheduled = false

    /** メインスレッドから探索を開始し、少数のチャンクを並列先読みしつつ決定順で地形を判定します。 */
    fun start(): CompletableFuture<List<OverworldSite>> {
        plugin.logger.info("Overworld site search started for world '${world.name}' (seed=${world.seed})")
        pump()
        return result
    }

    /** サービス終了後の非同期コールバックが探索を再開しないよう停止します。 */
    fun cancel() { result.cancel(false) }

    /** 完了済みサンプルを決定順で受理し、不足分を非同期先読みウィンドウへ補充します。 */
    private fun pump() {
        if (result.isDone) return
        try {
            while (!result.isDone) {
                val next = search.peekCandidates(1).firstOrNull()
                if (next == null) {
                    runCatching { search.result() }.onSuccess(result::complete).onFailure(result::completeExceptionally)
                    return
                }
                val terrain = sampled.remove(next) ?: break
                val prevCount = search.selected().size
                val candidate = search.nextCandidate() ?: break
                check(candidate == next) { "Site search candidate order diverged" }
                search.accept(terrain)
                val after = search.selected()
                if (after.size > prevCount) {
                    val chosen = after.last()
                    plugin.logger.info(
                        "Overworld site located (${after.size}/9): ${chosen.id} at (${chosen.x}, ${chosen.y}, ${chosen.z}) " +
                            "tier=${chosen.tier} biome=${chosen.biome}",
                    )
                    siteEpoch++
                    sampled.clear()
                    inFlight.clear()
                    if (search.peekCandidates(1).isEmpty()) {
                        runCatching { search.result() }.onSuccess(result::complete).onFailure(result::completeExceptionally)
                        return
                    }
                }
            }
            val epoch = siteEpoch
            val upcoming = search.peekCandidates(CHUNK_PREFETCH_WINDOW)
            for (point in upcoming) {
                if (result.isDone || epoch != siteEpoch) return
                if (inFlight.size >= CHUNK_PREFETCH_WINDOW) break
                if (point in sampled || !inFlight.add(point)) continue
                loadCandidate(point.first, point.second, epoch, 0)
            }
        } catch (error: Exception) {
            result.completeExceptionally(error)
        }
    }

    private fun schedulePump() {
        if (pumpScheduled || result.isDone) return
        pumpScheduled = true
        try {
            plugin.server.scheduler.runTaskLater(plugin, Runnable {
                pumpScheduled = false
                pump()
            }, 1L)
        } catch (error: Exception) {
            pumpScheduled = false
            result.completeExceptionally(error)
        }
    }

    /** 非同期読込の完了コールバック内で即時に地形を読み取り、次候補への進行のみ次tickへ送ります。 */
    private fun loadCandidate(x: Int, z: Int, epoch: Int, attempt: Int) {
        if (result.isDone || epoch != siteEpoch) return
        val cx = x shr 4
        val cz = z shr 4
        val point = x to z
        world.getChunkAtAsync(cx, cz, true).whenComplete { chunk, failure ->
            if (result.isDone) return@whenComplete
            val handle: () -> Unit = {
                if (!result.isDone && epoch == siteEpoch) {
                    try {
                        if (failure != null) {
                            result.completeExceptionally(failure)
                        } else if (chunk == null || !world.isChunkLoaded(cx, cz)) {
                            if (attempt < MAX_CHUNK_RETRIES) {
                                plugin.server.scheduler.runTaskLater(
                                    plugin,
                                    Runnable {
                                        if (!result.isDone && epoch == siteEpoch) loadCandidate(x, z, epoch, attempt + 1)
                                    },
                                    1L,
                                )
                            } else {
                                plugin.logger.warning(
                                    "Skipping unloaded overworld site candidate at $x,$z after ${MAX_CHUNK_RETRIES + 1} attempts",
                                )
                                sampled[point] = SiteTerrain(y = world.minHeight, biome = "UNAVAILABLE", dryLand = false)
                                inFlight.remove(point)
                                schedulePump()
                            }
                        } else {
                            sampled[point] = sample(x, z)
                            inFlight.remove(point)
                            schedulePump()
                        }
                    } catch (error: Exception) {
                        result.completeExceptionally(error)
                    }
                }
            }
            if (Bukkit.isPrimaryThread()) {
                handle()
            } else {
                try {
                    plugin.server.scheduler.runTask(plugin, Runnable { handle() })
                } catch (error: Exception) {
                    result.completeExceptionally(error)
                }
            }
        }
    }

    /** 隣接チャンクを同期生成しないよう、地形差・水辺・自然地表の判定は同一チャンク内で測定します。 */
    internal fun sample(x: Int, z: Int): SiteTerrain {
        val originX = (x shr 4) shl 4
        val originZ = (z shr 4) shl 4
        val points = listOf(x to z, originX + 2 to originZ + 2, originX + 13 to originZ + 2,
            originX + 2 to originZ + 13, originX + 13 to originZ + 13)
        val heights = points.map { (px, pz) -> OverworldTerrain.surfaceY(world, px, pz) }
        val surfaces = points.zip(heights).map { (point, y) -> world.getBlockAt(point.first, y, point.second).type }
        val surface = surfaces.first()
        val water = surfaces.any { it == Material.WATER }
        // バニラ構造物（森の洋館・村・前哨基地・遺跡）や溶岩・マグマが混ざるチャンクは候補から除外します。
        val naturalDryLand = surface.isSolid &&
            surface in OverworldMaterials.ground &&
            surface !in nonDryGround &&
            surfaces.all(OverworldMaterials::replaceable)
        return SiteTerrain(
            y = heights.first(),
            biome = world.getBiome(x, heights.first(), z).key.key.uppercase(Locale.ROOT),
            dryLand = naturalDryLand,
            relief = heights.max() - heights.min(),
            nearbyWater = water,
        )
    }

    private companion object {
        const val MAX_CHUNK_RETRIES = 5
        const val CHUNK_PREFETCH_WINDOW = 8
        val nonDryGround = setOf(Material.ICE, Material.PACKED_ICE, Material.BLUE_ICE)
    }
}

/** 樹冠や幹の頂点ではなく、その下の地面を基準に施設の高さを決めます。 */
internal object OverworldTerrain {
    fun surfaceY(world: World, x: Int, z: Int): Int {
        var y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES)
        while (y > world.minHeight) {
            val type = world.getBlockAt(x, y, z).type
            if (!Tag.LOGS.isTagged(type) && !Tag.LEAVES.isTagged(type)) break
            y--
        }
        return y
    }
}
