package net.nakaymi.etherealchronicle.paper

import net.nakaymi.etherealchronicle.core.DungeonBoxScanResult
import net.nakaymi.etherealchronicle.core.DungeonInstanceBounds
import net.nakaymi.etherealchronicle.core.OverworldDungeonInstanceLayout
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.data.BlockData
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask

/**
 * スキャン済みチャンクチケットを引き継いでダンジョン schematic の非空気ブロックを時間予算付きで分割配置し、
 * 終了時・失敗ロールバック時・孤立スロット回収時に非同期分割で AIR へ撤去します。
 */
internal class OverworldDungeonPlacement(
    private val plugin: Plugin,
    private val world: World,
    private val scanner: OverworldDungeonScanner,
) {
    /** 撤去処理の停止・チケット解放を統一的に扱うハンドルです。 */
    interface CleanupHandle {
        fun release()
    }

    private class NonAirCell(val index: Int, val blockData: BlockData)

    private val operations = mutableSetOf<Operation>()
    private val boundsClearOperations = mutableSetOf<BoundsClearOperation>()
    @Volatile
    private var closed = false

    /** 完了後もチャンクチケットを保持するため、利用終了時に必ず clear または release を呼びます。 */
    inner class Operation internal constructor(
        internal val bounds: DungeonInstanceBounds,
        private val schematic: SchematicData,
        private val scanOperation: OverworldDungeonScanner.Operation,
        private val maxBlocksPerTick: Int,
        private val timeBudgetNanos: Long,
        private val beforeFirstWrite: ((Boolean) -> Unit) -> Unit,
        private val complete: (Operation, Boolean, Throwable?) -> Unit,
    ) : CleanupHandle {
        private var task: BukkitTask? = null
        private var released = false
        private var finished = false
        private var clearing = false
        private val clearCallbacks = mutableListOf<(Boolean, Throwable?) -> Unit>()
        private var nonAirCells: List<NonAirCell> = emptyList()
        private var placed = 0

        /** 既にワールドへ書き込んだ非空気ブロック数を返します。 */
        fun placedCount(): Int = placed

        /** 既に配置した非空気セルのインデックス配列スナップショットを返します。 */
        fun placedCellIndices(): IntArray = IntArray(placed) { nonAirCells[it].index }

        internal fun start() {
            require(
                schematic.width == OverworldDungeonInstanceLayout.ARENA_WIDTH &&
                    schematic.height == OverworldDungeonInstanceLayout.ARENA_HEIGHT &&
                    schematic.length == OverworldDungeonInstanceLayout.ARENA_WIDTH,
            )
            val states = schematic.palette.map { state -> if (isAir(state)) null else Bukkit.createBlockData(state) }
            val cells = ArrayList<NonAirCell>()
            for (index in 0 until DungeonInstanceBounds.BLOCK_COUNT) {
                val state = states[schematic.paletteIndexAt(index)] ?: continue
                cells += NonAirCell(index, state)
            }
            nonAirCells = cells
            scanOperation.ensureAllChunksReadyForPlacement(VERIFY_BLOCK_BUDGET) { result, error ->
                if (released || closed) return@ensureAllChunksReadyForPlacement
                if (error != null || result != DungeonBoxScanResult.EMPTY) {
                    finish(false, error)
                    return@ensureAllChunksReadyForPlacement
                }
                if (nonAirCells.isEmpty()) {
                    finish(true, null)
                    return@ensureAllChunksReadyForPlacement
                }
                beforeFirstWrite { allowed ->
                    if (released || closed) return@beforeFirstWrite
                    if (!allowed) {
                        finish(false, null)
                        return@beforeFirstWrite
                    }
                    startWriteLoop()
                }
            }
        }

        private fun startWriteLoop() {
            var cursor = 0
            task = plugin.server.scheduler.runTaskTimer(plugin, Runnable {
                    try {
                        val deadline = System.nanoTime() + timeBudgetNanos
                        var processed = 0
                        while (cursor < nonAirCells.size && processed < maxBlocksPerTick) {
                            val cell = nonAirCells[cursor]
                            val point = bounds.position(cell.index)
                            check(world.isChunkLoaded(point.x shr 4, point.z shr 4)) {
                                "Dungeon chunk unexpectedly unloaded"
                            }
                            val block = world.getBlockAt(point.x, point.y, point.z)
                            check(block.type.isAir) { "Dungeon candidate changed during placement" }
                            block.setBlockData(cell.blockData, false)
                            placed++
                            cursor++
                            processed++
                            if (processed >= MIN_BLOCKS_PER_TICK && System.nanoTime() >= deadline) break
                        }
                        if (cursor == nonAirCells.size) finish(true, null)
                } catch (failure: Exception) {
                    finish(false, failure)
                }
            }, PaperTaskIntervals.NEXT_TICK_DELAY, PaperTaskIntervals.NEXT_TICK_DELAY)
        }

        /**
         * 配置した非空気セルだけを時間予算付きで AIR へ戻し、完了後にチケットを解放します。
         * 既に撤去中であれば追加コールバックをキューに積み、完了時にまとめて通知します。
         */
        fun clear(onCleared: (Boolean, Throwable?) -> Unit) {
            if (released || closed) {
                onCleared(false, IllegalStateException("Dungeon placement operation already released"))
                return
            }
            clearCallbacks += onCleared
            if (clearing) return
            clearing = true
            task?.cancel()
            task = null
            val targetCount = placed
            if (targetCount == 0) {
                release(warnIfRetained = false)
                notifyCleared(true, null)
                return
            }
            var cursor = 0
            task = plugin.server.scheduler.runTaskTimer(plugin, Runnable {
                try {
                    val deadline = System.nanoTime() + timeBudgetNanos
                    var processed = 0
                    while (cursor < targetCount && processed < maxBlocksPerTick) {
                        val point = bounds.position(nonAirCells[cursor].index)
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
                    if (cursor == targetCount) {
                        placed = 0
                        release(warnIfRetained = false)
                        notifyCleared(true, null)
                    }
                } catch (failure: Exception) {
                    release(warnIfRetained = true)
                    notifyCleared(false, failure)
                }
            }, PaperTaskIntervals.NEXT_TICK_DELAY, PaperTaskIntervals.NEXT_TICK_DELAY)
        }

        private fun notifyCleared(cleared: Boolean, error: Throwable?) {
            val callbacks = clearCallbacks.toList()
            clearCallbacks.clear()
            callbacks.forEach { callback -> callback(cleared, error) }
        }

        private fun finish(success: Boolean, error: Throwable?) {
            if (released || finished) return
            finished = true
            task?.cancel()
            task = null
            // 配置途中で失敗し placed > 0 の場合は呼び出し側で clear() ロールバックできるようチケットを保持します。
            if (!success && placed == 0) release(warnIfRetained = false)
            complete(this, success, error)
        }

        /** 撤去せずにチャンクチケットだけを解放します。 */
        override fun release() {
            release(warnIfRetained = true)
        }

        /** 撤去せずにチャンクチケットだけを解放します。必要時のみ未撤去ブロック残留の警告を出します。 */
        fun release(warnIfRetained: Boolean) {
            if (released) return
            released = true
            task?.cancel()
            task = null
            scanOperation.release()
            operations.remove(this)
            if (warnIfRetained && placed > 0) {
                plugin.logger.warning(
                    "Dungeon blocks retained at ${bounds.minX},${bounds.minY},${bounds.minZ}; inspect before manual cleanup.",
                )
            }
        }
    }

    /**
     * 保護解除後や起動時の孤立スロット回収のため、既存チャンクのみを非同期読込して
     * 非空気セル一覧（または範囲フォールバック）を AIR へ撤去します。
     */
    inner class BoundsClearOperation internal constructor(
        private val bounds: DungeonInstanceBounds,
        private val cellIndices: IntArray?,
        private val maxBlocksPerTick: Int,
        private val timeBudgetNanos: Long,
        private val onCleared: (Boolean, Throwable?) -> Unit,
    ) : CleanupHandle {
        private val allPositions: List<Pair<Int, Int>> = bounds.chunkX.flatMap { x -> bounds.chunkZ.map { z -> x to z } }
        private val acquired = linkedSetOf<Pair<Int, Int>>()
        private var task: BukkitTask? = null
        private var released = false
        private var finished = false

        internal fun start() {
            if (allPositions.isEmpty()) {
                scheduleMain { if (!released && !closed) finish(true, null) }
                return
            }
            var remaining = allPositions.size
            var failed = false
            for (pos in allPositions) {
                world.getChunkAtAsync(pos.first, pos.second, false).whenComplete { chunk, error ->
                    if (!plugin.isEnabled || closed) return@whenComplete
                    val handle: () -> Unit = {
                        if (!failed && !released && !closed) {
                            val failure: Throwable? = if (error != null) {
                                error
                            } else if (chunk != null) {
                                try {
                                    check(world.isChunkLoaded(pos.first, pos.second)) {
                                        "Dungeon chunk unloaded during bounds cleanup"
                                    }
                                    if (acquired.add(pos)) scanner.acquireTicket(pos)
                                    null
                                } catch (caught: Exception) {
                                    caught
                                }
                            } else {
                                null
                            }
                            if (failure != null) {
                                failed = true
                                releaseAcquiredTickets()
                                scheduleMain { if (!released && !closed) finish(false, failure) }
                            } else {
                                remaining--
                                if (remaining == 0) {
                                    scheduleMain {
                                        if (failed || released || closed) return@scheduleMain
                                        if (acquired.isEmpty()) {
                                            finish(true, null)
                                            return@scheduleMain
                                        }
                                        val totalCount = cellIndices?.size ?: DungeonInstanceBounds.BLOCK_COUNT
                                        if (totalCount == 0) {
                                            finish(true, null)
                                            return@scheduleMain
                                        }
                                        var cursor = 0
                                        task = plugin.server.scheduler.runTaskTimer(plugin, Runnable {
                                            try {
                                                val deadline = System.nanoTime() + timeBudgetNanos
                                                var processed = 0
                                                while (cursor < totalCount && processed < maxBlocksPerTick) {
                                                    val blockIndex = cellIndices?.get(cursor) ?: cursor
                                                    val point = bounds.position(blockIndex)
                                                    val chunkPos = (point.x shr 4) to (point.z shr 4)
                                                    if (chunkPos in acquired) {
                                                        check(world.isChunkLoaded(chunkPos.first, chunkPos.second)) {
                                                            "Dungeon chunk unexpectedly unloaded during bounds cleanup"
                                                        }
                                                        val block = world.getBlockAt(point.x, point.y, point.z)
                                                        if (!block.type.isAir) {
                                                            block.setType(Material.AIR, false)
                                                        }
                                                    }
                                                    cursor++
                                                    processed++
                                                    if (processed >= MIN_BLOCKS_PER_TICK && System.nanoTime() >= deadline) break
                                                }
                                                if (cursor == totalCount) finish(true, null)
                                            } catch (clearFailure: Exception) {
                                                finish(false, clearFailure)
                                            }
                                        }, PaperTaskIntervals.NEXT_TICK_DELAY, PaperTaskIntervals.NEXT_TICK_DELAY)
                                    }
                                }
                            }
                        }
                    }
                    if (Bukkit.isPrimaryThread()) {
                        handle()
                    } else {
                        scheduleMain(handle)
                    }
                }
            }
        }

        private fun scheduleMain(action: () -> Unit) {
            try {
                plugin.server.scheduler.runTask(plugin, Runnable { action() })
            } catch (_: IllegalStateException) {
                // 無効化中は完了通知を行いません。
            }
        }

        private fun releaseAcquiredTickets() {
            acquired.forEach(scanner::releaseTicket)
            acquired.clear()
        }

        private fun finish(cleared: Boolean, error: Throwable?) {
            if (finished || released) return
            finished = true
            release()
            onCleared(cleared, error)
        }

        override fun release() {
            if (released) return
            released = true
            task?.cancel()
            task = null
            releaseAcquiredTickets()
            boundsClearOperations.remove(this)
        }
    }

    /** 主スレッドから呼び、スキャン済みチケットを引き継いで時間予算付き配置を開始します。 */
    fun place(
        bounds: DungeonInstanceBounds,
        schematic: SchematicData,
        scanOperation: OverworldDungeonScanner.Operation,
        maxBlocksPerTick: Int = DEFAULT_BLOCK_BUDGET,
        timeBudgetNanos: Long = DEFAULT_TIME_BUDGET_NANOS,
        beforeFirstWrite: ((Boolean) -> Unit) -> Unit = { proceed -> proceed(true) },
        complete: (Operation, Boolean, Throwable?) -> Unit,
    ): Operation {
        check(!closed)
        require(maxBlocksPerTick > 0)
        require(timeBudgetNanos > 0L)
        val operation = Operation(bounds, schematic, scanOperation, maxBlocksPerTick, timeBudgetNanos, beforeFirstWrite, complete)
        operations += operation
        try {
            operation.start()
        } catch (failure: Exception) {
            operation.release(warnIfRetained = false)
            throw failure
        }
        return operation
    }

    /**
     * 所有済みスロットの残骸を既存チャンク限定で非同期撤去します。
     * `cellIndices` が指定されていれば非空気セルのみ、null なら範囲全体を AIR へ戻します。
     */
    fun clearBounds(
        bounds: DungeonInstanceBounds,
        cellIndices: IntArray? = null,
        maxBlocksPerTick: Int = DEFAULT_BLOCK_BUDGET,
        timeBudgetNanos: Long = DEFAULT_TIME_BUDGET_NANOS,
        onCleared: (Boolean, Throwable?) -> Unit,
    ): CleanupHandle {
        check(!closed)
        require(maxBlocksPerTick > 0)
        require(timeBudgetNanos > 0L)
        val operation = BoundsClearOperation(bounds, cellIndices, maxBlocksPerTick, timeBudgetNanos, onCleared)
        boundsClearOperations += operation
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
        operations.toList().forEach { it.release(warnIfRetained = false) }
        boundsClearOperations.toList().forEach { it.release() }
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

        /** schematic 内の非空気セルのブロックインデックス配列を返します。 */
        internal fun nonAirCellIndices(schematic: SchematicData): IntArray {
            require(
                schematic.width == OverworldDungeonInstanceLayout.ARENA_WIDTH &&
                    schematic.height == OverworldDungeonInstanceLayout.ARENA_HEIGHT &&
                    schematic.length == OverworldDungeonInstanceLayout.ARENA_WIDTH,
            )
            val nonAirPalette = BooleanArray(schematic.palette.size) { index -> !isAir(schematic.palette[index]) }
            val indices = IntArray(DungeonInstanceBounds.BLOCK_COUNT)
            var count = 0
            for (index in 0 until DungeonInstanceBounds.BLOCK_COUNT) {
                val paletteIndex = schematic.paletteIndexAt(index)
                if (paletteIndex in nonAirPalette.indices && nonAirPalette[paletteIndex]) {
                    indices[count++] = index
                }
            }
            return indices.copyOf(count)
        }
    }
}
