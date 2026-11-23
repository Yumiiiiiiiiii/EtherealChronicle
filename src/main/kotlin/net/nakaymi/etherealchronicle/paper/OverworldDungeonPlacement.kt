package net.nakaymi.etherealchronicle.paper

import net.nakaymi.etherealchronicle.core.DungeonBoxScanResult
import net.nakaymi.etherealchronicle.core.DungeonInstanceBounds
import net.nakaymi.etherealchronicle.core.OverworldDungeonInstanceLayout
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask

/**
 * スキャン済みチャンクチケットを引き継いでダンジョン schematic を時間予算付きで分割配置し、
 * 通常終了時には自身が配置した範囲を非同期分割で AIR へ撤去します。
 */
internal class OverworldDungeonPlacement(
    private val plugin: Plugin,
    private val world: World,
) {
    private val operations = mutableSetOf<Operation>()
    private var closed = false

    /** 完了後もチャンクチケットを保持するため、利用終了時に必ず clear または release を呼びます。 */
    inner class Operation internal constructor(
        private val bounds: DungeonInstanceBounds,
        private val schematic: SchematicData,
        private val scanOperation: OverworldDungeonScanner.Operation,
        private val maxBlocksPerTick: Int,
        private val timeBudgetNanos: Long,
        private val complete: (Boolean, Throwable?) -> Unit,
    ) {
        private var task: BukkitTask? = null
        private var released = false
        private var finished = false
        private var clearing = false
        private var placed = 0

        /** 既にワールドへ書き込んだ非空気ブロック数を返します。 */
        fun placedCount(): Int = placed

        internal fun start() {
            require(
                schematic.width == OverworldDungeonInstanceLayout.ARENA_WIDTH &&
                    schematic.height == OverworldDungeonInstanceLayout.ARENA_HEIGHT &&
                    schematic.length == OverworldDungeonInstanceLayout.ARENA_WIDTH,
            )
            val states = schematic.palette.map { state -> if (isAir(state)) null else Bukkit.createBlockData(state) }
            scanOperation.ensureAllChunksReadyForPlacement(VERIFY_BLOCK_BUDGET) { result, error ->
                if (released || closed) return@ensureAllChunksReadyForPlacement
                if (error != null || result != DungeonBoxScanResult.EMPTY) {
                    finish(false, error)
                    return@ensureAllChunksReadyForPlacement
                }
                var cursor = 0
                task = plugin.server.scheduler.runTaskTimer(plugin, Runnable {
                    try {
                        val deadline = System.nanoTime() + timeBudgetNanos
                        var processed = 0
                        while (cursor < DungeonInstanceBounds.BLOCK_COUNT && processed < maxBlocksPerTick) {
                            val point = bounds.position(cursor)
                            check(world.isChunkLoaded(point.x shr 4, point.z shr 4)) {
                                "Dungeon chunk unexpectedly unloaded"
                            }
                            val block = world.getBlockAt(point.x, point.y, point.z)
                            check(block.type.isAir) { "Dungeon candidate changed during placement" }
                            val state = states[schematic.paletteIndexAt(cursor)]
                            if (state != null) {
                                block.setBlockData(state, false)
                                placed++
                            }
                            cursor++
                            processed++
                            if (processed >= MIN_BLOCKS_PER_TICK && System.nanoTime() >= deadline) break
                        }
                        if (cursor == DungeonInstanceBounds.BLOCK_COUNT) finish(true, null)
                    } catch (failure: Exception) {
                        finish(false, failure)
                    }
                }, PaperTaskIntervals.NEXT_TICK_DELAY, PaperTaskIntervals.NEXT_TICK_DELAY)
            }
        }

        /**
         * 通常終了後、自身が配置した範囲の非空気ブロックを時間予算付きで AIR へ戻し、完了後にチケットを解放します。
         * クラッシュ後の不確かな残骸には呼ばず、この操作ハンドルが保持する範囲だけに限定します。
         */
        fun clear(onCleared: (Boolean, Throwable?) -> Unit) {
            if (released || closed) {
                onCleared(false, IllegalStateException("Dungeon placement operation already released"))
                return
            }
            if (clearing) return
            clearing = true
            task?.cancel()
            task = null
            var cursor = 0
            task = plugin.server.scheduler.runTaskTimer(plugin, Runnable {
                try {
                    val deadline = System.nanoTime() + timeBudgetNanos
                    var processed = 0
                    while (cursor < DungeonInstanceBounds.BLOCK_COUNT && processed < maxBlocksPerTick) {
                        val point = bounds.position(cursor)
                        check(world.isChunkLoaded(point.x shr 4, point.z shr 4)) {
                            "Dungeon chunk unexpectedly unloaded during cleanup"
                        }
                        val block = world.getBlockAt(point.x, point.y, point.z)
                        if (!block.type.isAir) {
                            block.setType(Material.AIR, false)
                        }
                        cursor++
                        processed++
                        if (processed >= MIN_BLOCKS_PER_TICK && System.nanoTime() >= deadline) break
                    }
                    if (cursor == DungeonInstanceBounds.BLOCK_COUNT) {
                        placed = 0
                        release()
                        onCleared(true, null)
                    }
                } catch (failure: Exception) {
                    release()
                    onCleared(false, failure)
                }
            }, PaperTaskIntervals.NEXT_TICK_DELAY, PaperTaskIntervals.NEXT_TICK_DELAY)
        }

        private fun finish(success: Boolean, error: Throwable?) {
            if (released || finished) return
            finished = true
            task?.cancel()
            task = null
            if (!success) release()
            complete(success, error)
        }

        /** 撤去せずにチャンクチケットだけを解放します。未撤去ブロックが残る場合は警告を出します。 */
        fun release() {
            if (released) return
            released = true
            task?.cancel()
            task = null
            scanOperation.release()
            operations.remove(this)
            if (placed > 0) {
                plugin.logger.warning(
                    "Dungeon blocks retained at ${bounds.minX},${bounds.minY},${bounds.minZ}; inspect before manual cleanup.",
                )
            }
        }
    }

    /** 主スレッドから呼び、スキャン済みチケットを引き継いで時間予算付き配置を開始します。 */
    fun place(
        bounds: DungeonInstanceBounds,
        schematic: SchematicData,
        scanOperation: OverworldDungeonScanner.Operation,
        maxBlocksPerTick: Int = DEFAULT_BLOCK_BUDGET,
        timeBudgetNanos: Long = DEFAULT_TIME_BUDGET_NANOS,
        complete: (Boolean, Throwable?) -> Unit,
    ): Operation {
        check(!closed)
        require(maxBlocksPerTick > 0)
        require(timeBudgetNanos > 0L)
        val operation = Operation(bounds, schematic, scanOperation, maxBlocksPerTick, timeBudgetNanos, complete)
        operations += operation
        try {
            operation.start()
        } catch (failure: Exception) {
            operation.release()
            throw failure
        }
        return operation
    }

    /** 未完了処理を停止し、所有チャンクチケットを解放します。 */
    fun close() {
        closed = true
        operations.toList().forEach { it.release() }
    }

    companion object {
        /** 1 tick あたりの配置・撤去の壁時計時間予算（2ms: ライト再計算スパイク抑止用）。 */
        const val DEFAULT_TIME_BUDGET_NANOS = 2_000_000L

        /** 1 tick あたりの最大処理ブロック数上限。 */
        const val DEFAULT_BLOCK_BUDGET = 1_024

        /** 新規生成チャンク分の空き確認に使う 1 tick あたりの走査上限。 */
        private const val VERIFY_BLOCK_BUDGET = 4_096

        /** 遅い tick でも必ず前進させる最小処理ブロック数。 */
        private const val MIN_BLOCKS_PER_TICK = 1
    }
}
