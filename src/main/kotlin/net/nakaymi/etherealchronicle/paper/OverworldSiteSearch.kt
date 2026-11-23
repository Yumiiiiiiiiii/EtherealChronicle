package net.nakaymi.etherealchronicle.paper

import net.nakaymi.etherealchronicle.core.OverworldSite
import net.nakaymi.etherealchronicle.core.OverworldSiteLocator
import net.nakaymi.etherealchronicle.core.SiteTerrain
import org.bukkit.HeightMap
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.World
import org.bukkit.plugin.Plugin
import java.util.Locale
import java.util.concurrent.CompletableFuture

/** Paperの非同期チャンク取得とtick単位の地形サンプリングを接続します。 */
internal class OverworldSiteSearch(private val plugin: Plugin, private val world: World) {
    private val search = OverworldSiteLocator.Search(world.seed)
    private val result = CompletableFuture<List<OverworldSite>>()

    /** メインスレッドから探索を開始し、1 tickに最大1チャンクの地形を判定します。 */
    fun start(): CompletableFuture<List<OverworldSite>> {
        advance()
        return result
    }

    /** サービス終了後の非同期コールバックが探索を再開しないよう停止します。 */
    fun cancel() { result.cancel(false) }

    /** 次のチャンクが生成・読込できてからメインスレッドで地形を読みます。 */
    private fun advance() {
        if (result.isDone) return
        val point = runCatching { search.nextCandidate() }.getOrElse { result.completeExceptionally(it); return }
        if (point == null) {
            runCatching { search.result() }.onSuccess(result::complete).onFailure(result::completeExceptionally)
            return
        }
        val (x, z) = point
        loadCandidate(x, z, 0)
    }

    /** 読込直後にアンロードされたチャンクだけを上限付きで再要求します。 */
    private fun loadCandidate(x: Int, z: Int, attempt: Int) {
        if (result.isDone) return
        world.getChunkAtAsync(x shr 4, z shr 4, true).whenComplete { _, failure ->
            if (result.isDone) return@whenComplete
            if (failure != null) {
                result.completeExceptionally(failure)
            } else {
                try {
                    plugin.server.scheduler.runTaskLater(plugin, Runnable {
                        if (result.isDone) return@Runnable
                        try {
                            if (!world.isChunkLoaded(x shr 4, z shr 4)) {
                                if (attempt < MAX_CHUNK_RETRIES) loadCandidate(x, z, attempt + 1)
                                else result.completeExceptionally(IllegalStateException("Site sampling chunk repeatedly unloaded at $x,$z"))
                                return@Runnable
                            }
                            search.accept(sample(x, z))
                            advance()
                        } catch (error: Exception) {
                            result.completeExceptionally(error)
                        }
                    }, 1L)
                } catch (error: Exception) {
                    result.completeExceptionally(error)
                }
            }
        }
    }

    /** 隣接チャンクを同期生成しないよう、地形差と水辺は同一チャンク内で測定します。 */
    private fun sample(x: Int, z: Int): SiteTerrain {
        val originX = (x shr 4) shl 4
        val originZ = (z shr 4) shl 4
        val points = listOf(x to z, originX + 2 to originZ + 2, originX + 13 to originZ + 2,
            originX + 2 to originZ + 13, originX + 13 to originZ + 13)
        val heights = points.map { (px, pz) -> OverworldTerrain.surfaceY(world, px, pz) }
        val surface = world.getBlockAt(x, heights.first(), z).type
        val water = points.zip(heights).any { (point, y) -> world.getBlockAt(point.first, y, point.second).type == Material.WATER }
        return SiteTerrain(
            y = heights.first(),
            biome = world.getBiome(x, heights.first(), z).key.key.uppercase(Locale.ROOT),
            dryLand = surface.isSolid && surface != Material.ICE && surface != Material.PACKED_ICE && surface != Material.BLUE_ICE,
            relief = heights.max() - heights.min(),
            nearbyWater = water,
        )
    }

    private companion object { const val MAX_CHUNK_RETRIES = 5 }
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
