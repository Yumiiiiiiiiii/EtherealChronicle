package net.nakaymi.etherealchronicle.paper

import net.nakaymi.etherealchronicle.core.DungeonBoxScanResult
import net.nakaymi.etherealchronicle.core.DungeonInstanceBounds
import net.nakaymi.etherealchronicle.core.OverworldDungeonInstanceLayout
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask

/** 空き確認後の配置を分割し、各書き込み直前に既存ブロックを再確認します。 */
internal class OverworldDungeonPlacement(
    private val plugin: Plugin,
    private val world: World,
    private val scanner: OverworldDungeonScanner,
) {
    private val operations = mutableSetOf<Operation>()
    private var closed = false

    /** 完了後もチャンクを保持するため、利用終了時に必ず release を呼びます。 */
    inner class Operation internal constructor(
        private val bounds: DungeonInstanceBounds,
        private val schematic: SchematicData,
        private val budget: Int,
        private val complete: (Boolean, Throwable?) -> Unit,
    ) {
        private var scan: OverworldDungeonScanner.Operation? = null
        private var task: BukkitTask? = null
        private var released = false
        private var finished = false
        private var placed = 0

        internal fun start() {
            require(schematic.width == OverworldDungeonInstanceLayout.ARENA_WIDTH &&
                schematic.height == OverworldDungeonInstanceLayout.ARENA_HEIGHT &&
                schematic.length == OverworldDungeonInstanceLayout.ARENA_WIDTH)
            val states = schematic.palette.map { state -> if (isAir(state)) null else Bukkit.createBlockData(state) }
            scan = scanner.scan(bounds, budget) { result, error ->
                if (released) return@scan
                if (result != DungeonBoxScanResult.EMPTY) {
                    finish(false, error)
                    return@scan
                }
                var cursor = 0
                task = plugin.server.scheduler.runTaskTimer(plugin, Runnable {
                    try {
                        val end = (cursor.toLong() + budget).coerceAtMost(DungeonInstanceBounds.BLOCK_COUNT.toLong()).toInt()
                        while (cursor < end) {
                            val point = bounds.position(cursor)
                            check(world.isChunkLoaded(point.x shr 4, point.z shr 4)) { "Dungeon chunk unexpectedly unloaded" }
                            val block = world.getBlockAt(point.x, point.y, point.z)
                            check(block.type.isAir) { "Dungeon candidate changed during placement" }
                            val state = states[schematic.paletteIndexAt(cursor)]
                            if (state != null) {
                                block.setBlockData(state, false)
                                placed++
                            }
                            cursor++
                        }
                        if (cursor == DungeonInstanceBounds.BLOCK_COUNT) finish(true, null)
                    } catch (failure: Exception) {
                        finish(false, failure)
                    }
                }, 1L, 1L)
            }
        }

        private fun finish(success: Boolean, error: Throwable?) {
            if (released || finished) return
            finished = true
            task?.cancel()
            if (!success) release()
            complete(success, error)
        }

        /** 所有権を確証できない設置物は消さず、管理者へ残存を通知します。 */
        fun release() {
            if (released) return
            released = true
            task?.cancel()
            scan?.release()
            operations.remove(this)
            if (placed > 0) {
                plugin.logger.warning("Dungeon blocks retained at ${bounds.minX},${bounds.minY},${bounds.minZ}; inspect before manual cleanup.")
            }
        }
    }

    /** 主スレッドから呼び、処理対象と時間当たりの作業量を固定します。 */
    fun place(bounds: DungeonInstanceBounds, schematic: SchematicData, budget: Int, complete: (Boolean, Throwable?) -> Unit): Operation {
        check(!closed)
        require(budget > 0)
        val operation = Operation(bounds, schematic, budget, complete)
        operations += operation
        try {
            operation.start()
        } catch (failure: Exception) {
            operation.release()
            throw failure
        }
        return operation
    }

    /** 未完了処理を停止し、所有チャンクを解放します。 */
    fun close() {
        closed = true
        operations.toList().forEach { it.release() }
    }
}
