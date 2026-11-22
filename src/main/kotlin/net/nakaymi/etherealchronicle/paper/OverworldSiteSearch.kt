package net.nakaymi.etherealchronicle.paper

import net.nakaymi.etherealchronicle.core.OverworldSite
import net.nakaymi.etherealchronicle.core.OverworldSiteLocator
import net.nakaymi.etherealchronicle.core.SiteTerrain
import org.bukkit.HeightMap
import org.bukkit.Material
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

    /** 次のチャンクが生成・読込できてからメインスレッドで地形を読みます。 */
    private fun advance() {
        if (result.isDone) return
        val point = runCatching { search.nextCandidate() }.getOrElse { result.completeExceptionally(it); return }
        if (point == null) {
            runCatching { search.result() }.onSuccess(result::complete).onFailure(result::completeExceptionally)
            return
        }
        val (x, z) = point
        world.getChunkAtAsync(x shr 4, z shr 4, true).whenComplete { _, failure ->
            if (failure != null) {
                result.completeExceptionally(failure)
            } else {
                try {
                    plugin.server.scheduler.runTaskLater(plugin, Runnable {
                        if (result.isDone) return@Runnable
                        try {
                            check(world.isChunkLoaded(x shr 4, z shr 4)) { "Site sampling chunk unloaded" }
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
        val heights = points.map { (px, pz) -> world.getHighestBlockYAt(px, pz, HeightMap.MOTION_BLOCKING_NO_LEAVES) }
        val surface = world.getBlockAt(x, heights.first(), z).type
        val water = points.zip(heights).any { (point, y) -> world.getBlockAt(point.first, y, point.second).type == Material.WATER }
        return SiteTerrain(
            y = heights.first(),
            biome = world.getBiome(x, 68, z).key.key.uppercase(Locale.ROOT),
            dryLand = surface.isSolid && surface != Material.ICE && surface != Material.PACKED_ICE && surface != Material.BLUE_ICE,
            relief = heights.max() - heights.min(),
            nearbyWater = water,
        )
    }
}
