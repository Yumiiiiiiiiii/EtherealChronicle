package net.nakaymi.etherealchronicle.paper

import net.nakaymi.etherealchronicle.core.DungeonBoxScan
import net.nakaymi.etherealchronicle.core.DungeonBoxScanResult
import net.nakaymi.etherealchronicle.core.DungeonInstanceBounds
import org.bukkit.World
import org.bukkit.Location
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import java.util.concurrent.CompletableFuture

/** 非同期で読み込んだチャンクを保持し、配置候補の全ブロックを分割して検査します。 */
internal class OverworldDungeonScanner(private val plugin: Plugin, private val world: World) {
    private val ticketReferences = mutableMapOf<Pair<Int, Int>, Int>()
    private val operations = mutableSetOf<Operation>()
    @Volatile private var closed = false

    /** 検査中と完了後のチャンク保持を呼び出し側で明示的に解放します。 */
    inner class Operation internal constructor(
        private val bounds: DungeonInstanceBounds,
        private val budget: Int,
        private val complete: (DungeonBoxScanResult?, Throwable?) -> Unit,
    ) {
        private val acquired = mutableListOf<Pair<Int, Int>>()
        private var task: BukkitTask? = null
        private var finished = false
        private var released = false

        internal fun start() {
            val positions = bounds.chunkX.flatMap { x -> bounds.chunkZ.map { z -> x to z } }
            val loads = positions.map { (x, z) -> world.getChunkAtAsync(x, z) }
            CompletableFuture.allOf(*loads.toTypedArray()).whenComplete { _, error ->
                if (!plugin.isEnabled || closed) return@whenComplete
                try {
                    plugin.server.scheduler.runTask(plugin, Runnable {
                        if (released || closed) return@Runnable
                        if (error != null) return@Runnable finish(null, error)
                        try {
                            for (position in positions) {
                                check(world.isChunkLoaded(position.first, position.second)) { "Dungeon chunk unloaded during preparation" }
                                acquire(position)
                                acquired += position
                            }
                            val scan = DungeonBoxScan(bounds)
                            task = plugin.server.scheduler.runTaskTimer(plugin, Runnable {
                                try {
                                    val result = scan.advance(budget) { point ->
                                        check(world.isChunkLoaded(point.x shr 4, point.z shr 4)) { "Dungeon chunk unexpectedly unloaded" }
                                        world.getBlockAt(point.x, point.y, point.z).type.isAir
                                    }
                                    if (result != DungeonBoxScanResult.IN_PROGRESS) finish(result, null)
                                } catch (failure: Exception) {
                                    finish(null, failure)
                                }
                            }, 1L, 1L)
                        } catch (failure: Exception) {
                            finish(null, failure)
                        }
                    })
                } catch (_: IllegalStateException) {
                    // 無効化中はワールド操作も完了通知も行いません。
                }
            }
        }

        private fun finish(result: DungeonBoxScanResult?, failure: Throwable?) {
            if (finished || released) return
            finished = true
            task?.cancel()
            if (result != DungeonBoxScanResult.EMPTY) release()
            complete(result, failure)
        }

        /** 検査を取り消し、保持中のチケットをすべて解放します。 */
        fun release() {
            if (released) return
            released = true
            task?.cancel()
            acquired.forEach(::releaseTicket)
            acquired.clear()
            operations.remove(this)
        }
    }

    /** 検査はメインスレッドで開始し、各回のブロック数を budget 以下に抑えます。 */
    fun scan(bounds: DungeonInstanceBounds, budget: Int, complete: (DungeonBoxScanResult?, Throwable?) -> Unit): Operation {
        check(!closed)
        require(budget > 0)
        require(bounds.minY >= world.minHeight && bounds.maxY < world.maxHeight) { "Dungeon candidate is outside world height" }
        val border = world.worldBorder
        require(listOf(bounds.minX.toDouble(), bounds.maxX + 1.0).all { x ->
            listOf(bounds.minZ.toDouble(), bounds.maxZ + 1.0).all { z ->
                border.isInside(Location(world, x, bounds.minY.toDouble(), z))
            }
        }) { "Dungeon candidate is outside world border" }
        val operation = Operation(bounds, budget, complete)
        operations += operation
        try {
            operation.start()
        } catch (failure: Exception) {
            operation.release()
            throw failure
        }
        return operation
    }

    private fun acquire(position: Pair<Int, Int>) {
        val count = ticketReferences[position] ?: 0
        if (count == 0) check(world.addPluginChunkTicket(position.first, position.second, plugin)) { "Dungeon chunk ticket unavailable" }
        ticketReferences[position] = count + 1
    }

    private fun releaseTicket(position: Pair<Int, Int>) {
        val count = ticketReferences.getValue(position)
        if (count == 1) {
            ticketReferences.remove(position)
            world.removePluginChunkTicket(position.first, position.second, plugin)
        } else ticketReferences[position] = count - 1
    }

    /** 停止時は未完了の検査とチャンク保持を解除します。 */
    fun close() {
        closed = true
        operations.toList().forEach { it.release() }
    }
}
