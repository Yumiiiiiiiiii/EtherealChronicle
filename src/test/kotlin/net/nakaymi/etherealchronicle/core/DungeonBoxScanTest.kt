package net.nakaymi.etherealchronicle.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 箱領域の走査予算、網羅性、停止状態の挙動を確認します。 */
class DungeonBoxScanTest {
    @Test
    fun `each advance respects its budget and complete scan covers every block once`() {
        val bounds = DungeonInstanceBounds(minX = -40, minY = 12, minZ = -24)
        val scan = DungeonBoxScan(bounds)
        val seen = HashSet<DungeonBlockPosition>(DungeonInstanceBounds.BLOCK_COUNT)
        val budget = 997
        var callbackCount = 0

        while (scan.result == DungeonBoxScanResult.IN_PROGRESS) {
            val before = scan.examined
            val callsBefore = callbackCount
            val result = scan.advance(budget) {
                callbackCount++
                assertTrue(seen.add(it), "duplicate block coordinate: $it")
                true
            }
            assertEquals(result, scan.result)
            assertTrue(callbackCount - callsBefore <= budget)
            assertTrue(scan.examined - before <= budget)
            assertEquals(minOf(before + budget, DungeonInstanceBounds.BLOCK_COUNT), scan.examined)
            assertEquals(
                if (scan.examined == DungeonInstanceBounds.BLOCK_COUNT) DungeonBoxScanResult.EMPTY else DungeonBoxScanResult.IN_PROGRESS,
                result,
            )
        }

        assertEquals(DungeonInstanceBounds.BLOCK_COUNT, callbackCount)
        assertEquals(DungeonInstanceBounds.BLOCK_COUNT, seen.size)
        assertEquals(DungeonInstanceBounds.BLOCK_COUNT, scan.examined)
        assertEquals(DungeonBoxScanResult.EMPTY, scan.result)
    }

    @Test
    fun `occupied block stops immediately and terminal state ignores later callbacks`() {
        val scan = DungeonBoxScan(DungeonInstanceBounds(0, 0, 0))
        var calls = 0

        assertEquals(DungeonBoxScanResult.OCCUPIED, scan.advance(100) {
            calls++
            calls < 4
        })
        assertEquals(4, calls)
        assertEquals(3, scan.examined)
        assertEquals(DungeonBoxScanResult.OCCUPIED, scan.advance(100) { error("terminal scan invoked callback") })
        assertEquals(4, calls)
    }

    @Test
    fun `non-positive budget is rejected before any callback`() {
        val scan = DungeonBoxScan(DungeonInstanceBounds(0, 0, 0))
        var calls = 0

        assertThrows(IllegalArgumentException::class.java) { scan.advance(0) { calls++; true } }
        assertThrows(IllegalArgumentException::class.java) { scan.advance(-1) { calls++; true } }
        assertEquals(0, calls)
        assertEquals(0, scan.examined)
    }

    @Test
    fun `callback failure leaves current coordinate pending for retry`() {
        val scan = DungeonBoxScan(DungeonInstanceBounds(0, 0, 0))
        val failure = IllegalStateException("world lookup failed")

        val thrown = assertThrows(IllegalStateException::class.java) {
            scan.advance(5) { throw failure }
        }
        assertTrue(thrown === failure)
        assertEquals(0, scan.examined)
        assertEquals(DungeonBoxScanResult.IN_PROGRESS, scan.result)
        assertEquals(DungeonBoxScanResult.IN_PROGRESS, scan.advance(1) { true })
        assertEquals(1, scan.examined)
    }

    @Test
    fun `empty terminal state is stable and does not invoke callback again`() {
        val scan = DungeonBoxScan(DungeonInstanceBounds(0, 0, 0))
        var calls = 0

        while (scan.result == DungeonBoxScanResult.IN_PROGRESS) {
            scan.advance(DungeonInstanceBounds.BLOCK_COUNT) { calls++; true }
        }
        val completedCalls = calls

        assertEquals(DungeonBoxScanResult.EMPTY, scan.advance(1) { calls++; false })
        assertEquals(completedCalls, calls)
    }
}
