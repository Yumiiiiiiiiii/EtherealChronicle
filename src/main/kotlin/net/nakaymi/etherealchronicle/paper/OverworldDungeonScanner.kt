package net.nakaymi.etherealchronicle.paper

import net.nakaymi.etherealchronicle.core.DungeonBoxScan
import net.nakaymi.etherealchronicle.core.DungeonBoxScanResult
import net.nakaymi.etherealchronicle.core.DungeonInstanceBounds
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask

/**
 * 既存チャンクのみを非同期読み込みして配置候補の全ブロックを分割検査します。
 * 未生成チャンクは検査段階では生成せず、配置確定時にのみ生成してチャンクチケットを引き継ぎます。
 */
internal class OverworldDungeonScanner(private val plugin: Plugin, private val world: World) {
    private val ticketReferences = mutableMapOf<Pair<Int, Int>, Int>()
    private val operations = mutableSetOf<Operation>()
    @Volatile
    private var closed = false

    /** 検査中および配置・撤去完了までのチャンク保持チケットを所有する操作ハンドルです。 */
    inner class Operation internal constructor(
        internal val bounds: DungeonInstanceBounds,
        private val budget: Int,
        private val complete: (Operation, DungeonBoxScanResult?, Throwable?) -> Unit,
    ) {
        private val allPositions: List<Pair<Int, Int>> = bounds.chunkX.flatMap { x -> bounds.chunkZ.map { z -> x to z } }
        private val acquired = linkedSetOf<Pair<Int, Int>>()
        private var task: BukkitTask? = null
        private var finished = false
        private var released = false

        internal fun start() {
            if (allPositions.isEmpty()) {
                scheduleMain { if (!released && !closed) finish(DungeonBoxScanResult.EMPTY, null) }
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
                                        "Dungeon chunk unloaded during preparation"
                                    }
                                    if (acquired.add(pos)) acquireTicket(pos)
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
                                scheduleMain { if (!released && !closed) finish(null, failure) }
                            } else {
                                remaining--
                                if (remaining == 0) {
                                    scheduleMain {
                                        if (failed || released || closed) return@scheduleMain
                                        if (acquired.isEmpty()) {
                                            // 未生成チャンクのみの空域は不要な生成を避け、そのまま空きと判定します。
                                            finish(DungeonBoxScanResult.EMPTY, null)
                                            return@scheduleMain
                                        }
                                        startScanTimer()
                                    }
                                }
                            }
                        }
                    }
                    runOnPrimaryImmediatelyOrSchedule(handle)
                }
            }
        }

        private fun startScanTimer() {
            val scan = DungeonBoxScan(bounds)
            task = plugin.server.scheduler.runTaskTimer(plugin, Runnable {
                try {
                    val result = scan.advance(budget) { point ->
                        val chunkPos = (point.x shr 4) to (point.z shr 4)
                        if (chunkPos !in acquired) return@advance true
                        check(world.isChunkLoaded(chunkPos.first, chunkPos.second)) {
                            "Dungeon chunk unexpectedly unloaded"
                        }
                        world.getBlockAt(point.x, point.y, point.z).type.isAir
                    }
                    if (result != DungeonBoxScanResult.IN_PROGRESS) finish(result, null)
                } catch (failure: Exception) {
                    finish(null, failure)
                }
            }, PaperTaskIntervals.NEXT_TICK_DELAY, PaperTaskIntervals.NEXT_TICK_DELAY)
        }

        /**
         * 配置確定後、未生成だったチャンクだけを生成・チケット確保し、その新規チャンク部分のみ空き確認します。
         * 全チャンクが検査段階で読込済みだった場合は再ロード・再スキャンなしで即座に完了します。
         */
        internal fun ensureAllChunksReadyForPlacement(
            verifyBudget: Int,
            onReady: (DungeonBoxScanResult?, Throwable?) -> Unit,
        ) {
            if (released || closed) {
                onReady(null, IllegalStateException("Dungeon scan operation already released"))
                return
            }
            val missing = allPositions.filter { it !in acquired }
            if (missing.isEmpty()) {
                onReady(DungeonBoxScanResult.EMPTY, null)
                return
            }
            var remaining = missing.size
            var failed = false
            for (pos in missing) {
                world.getChunkAtAsync(pos.first, pos.second, true).whenComplete { chunk, error ->
                    if (!plugin.isEnabled || closed) return@whenComplete
                    val handle: () -> Unit = {
                        if (!failed && !released && !closed) {
                            val failure: Throwable? = if (error != null) {
                                error
                            } else {
                                try {
                                    check(chunk != null && world.isChunkLoaded(pos.first, pos.second)) {
                                        "Dungeon chunk unloaded during placement preparation"
                                    }
                                    if (acquired.add(pos)) acquireTicket(pos)
                                    null
                                } catch (caught: Exception) {
                                    caught
                                }
                            }
                            if (failure != null) {
                                failed = true
                                releaseAcquiredTickets()
                                scheduleMain { if (!released && !closed) onReady(null, failure) }
                            } else {
                                remaining--
                                if (remaining == 0) {
                                    scheduleMain {
                                        if (failed || released || closed) return@scheduleMain
                                        val missingSet = missing.toSet()
                                        val verifyScan = DungeonBoxScan(bounds)
                                        task = plugin.server.scheduler.runTaskTimer(plugin, Runnable {
                                            try {
                                                val result = verifyScan.advance(verifyBudget) { point ->
                                                    val chunkPos = (point.x shr 4) to (point.z shr 4)
                                                    if (chunkPos !in missingSet) return@advance true
                                                    check(world.isChunkLoaded(chunkPos.first, chunkPos.second)) {
                                                        "Dungeon chunk unexpectedly unloaded"
                                                    }
                                                    world.getBlockAt(point.x, point.y, point.z).type.isAir
                                                }
                                                if (result != DungeonBoxScanResult.IN_PROGRESS) {
                                                    task?.cancel()
                                                    task = null
                                                    onReady(result, null)
                                                }
                                            } catch (scanFailure: Exception) {
                                                task?.cancel()
                                                task = null
                                                onReady(null, scanFailure)
                                            }
                                        }, PaperTaskIntervals.NEXT_TICK_DELAY, PaperTaskIntervals.NEXT_TICK_DELAY)
                                    }
                                }
                            }
                        }
                    }
                    runOnPrimaryImmediatelyOrSchedule(handle)
                }
            }
        }

        private fun runOnPrimaryImmediatelyOrSchedule(action: () -> Unit) {
            if (Bukkit.isPrimaryThread()) {
                action()
            } else {
                scheduleMain(action)
            }
        }

        private fun scheduleMain(action: () -> Unit) {
            try {
                plugin.server.scheduler.runTask(plugin, Runnable { action() })
            } catch (_: IllegalStateException) {
                // 無効化中はワールド操作も完了通知も行いません。
            }
        }

        private fun releaseAcquiredTickets() {
            acquired.forEach(::releaseTicket)
            acquired.clear()
        }

        private fun finish(result: DungeonBoxScanResult?, failure: Throwable?) {
            if (finished || released) return
            finished = true
            task?.cancel()
            task = null
            if (result != DungeonBoxScanResult.EMPTY) release()
            complete(this, result, failure)
        }

        /** 検査を取り消し、保持中のチケットをすべて解放します。 */
        fun release() {
            if (released) return
            released = true
            task?.cancel()
            task = null
            releaseAcquiredTickets()
            operations.remove(this)
        }
    }

    /** 検査はメインスレッドで開始し、各回のブロック数を budget 以下に抑えます。 */
    fun scan(
        bounds: DungeonInstanceBounds,
        budget: Int,
        complete: (Operation, DungeonBoxScanResult?, Throwable?) -> Unit,
    ): Operation {
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

    internal fun acquireTicket(position: Pair<Int, Int>) {
        val count = ticketReferences[position] ?: 0
        if (count == 0) check(world.addPluginChunkTicket(position.first, position.second, plugin)) { "Dungeon chunk ticket unavailable" }
        ticketReferences[position] = count + 1
    }

    internal fun releaseTicket(position: Pair<Int, Int>) {
        val count = ticketReferences[position] ?: return
        if (count <= 1) {
            ticketReferences.remove(position)
            world.removePluginChunkTicket(position.first, position.second, plugin)
        } else {
            ticketReferences[position] = count - 1
        }
    }

    /** 停止時は未完了の検査とチャンク保持を解除します。 */
    fun close() {
        closed = true
        operations.toList().forEach { it.release() }
    }
}
