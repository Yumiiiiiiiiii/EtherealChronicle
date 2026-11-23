package net.nakaymi.etherealchronicle.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** ダンジョン枠の上限、座標範囲、ワールド境界解決、状態遷移と帰還判定の契約を確認します。 */
class OverworldDungeonInstanceLayoutTest {
    @Test
    fun `candidate slots are capped at sixteen and occupied slots are skipped`() {
        val layout = OverworldDungeonInstanceLayout()
        val checkedSlots = mutableListOf<Int>()

        assertEquals(null, layout.findAvailableSlot(emptySet(), 0..20) {
            checkedSlots += (it.x - layout.baseX) / layout.spacing
            false
        })
        assertEquals((0..15).toList(), checkedSlots)
        assertEquals(15, layout.findAvailableSlot((0 until 15).toSet(), 0..20) { true })
        assertEquals(null, layout.findAvailableSlot((0 until 16).toSet(), 0..20) { true })
        assertEquals(16, OverworldDungeonInstanceLayout.MAX_CANDIDATE_SLOTS)
        assertThrows(IllegalArgumentException::class.java) { layout.center(16) }
        assertThrows(IllegalArgumentException::class.java) { layout.center(-1) }
    }

    @Test
    fun `spacing and world-coordinate arithmetic reject overlap and overflow`() {
        assertThrows(IllegalArgumentException::class.java) { OverworldDungeonInstanceLayout(spacing = 54) }
        assertThrows(IllegalArgumentException::class.java) {
            OverworldDungeonInstanceLayout(baseX = Int.MAX_VALUE - 30).center(0)
        }
        assertThrows(ArithmeticException::class.java) {
            OverworldDungeonInstanceLayout(spacing = Int.MAX_VALUE).center(2)
        }
        assertThrows(ArithmeticException::class.java) { DungeonInstanceBounds(Int.MAX_VALUE - 20, 0, 0) }
        assertThrows(ArithmeticException::class.java) { DungeonInstanceBounds(0, Int.MAX_VALUE - 20, 0) }
    }

    @Test
    fun `height fit includes the bottom and exclusive maximum boundary`() {
        val layout = OverworldDungeonInstanceLayout(baseY = 220)

        assertTrue(layout.fitsHeight(minHeight = 210, maxHeight = 244))
        assertFalse(layout.fitsHeight(minHeight = 211, maxHeight = 244))
        assertFalse(layout.fitsHeight(minHeight = 210, maxHeight = 243))
        assertTrue(layout.fitsHeight(minHeight = 0, maxHeight = 320))
    }

    @Test
    fun `worldborder check and resolveForWorld adapt base coordinates to small borders`() {
        val defaultLayout = OverworldDungeonInstanceLayout()
        assertFalse(defaultLayout.fitsBorder(0, borderCenterX = 0.0, borderCenterZ = 0.0, borderSize = 2_000.0))
        assertEquals(emptyList<Int>(), defaultLayout.fittingSlots(16, 0.0, 0.0, 2_000.0))
        assertEquals((0..15).toList(), defaultLayout.fittingSlots(16, 0.0, 0.0, 500_000.0))

        // 小さいワールド境界では境界内に収まる候補座標が自動選択される
        val adapted = OverworldDungeonInstanceLayout.resolveForWorld(
            borderCenterX = 0.0,
            borderCenterZ = 0.0,
            borderSize = 2_000.0,
            minHeight = -64,
            maxHeight = 320,
        )
        assertTrue(adapted.fittingSlots(16, 0.0, 0.0, 2_000.0).isNotEmpty())
        assertTrue(adapted.fitsBorder(0, 0.0, 0.0, 2_000.0))

        // 明示設定が境界内に収まる場合はそのまま採用される
        val configured = OverworldDungeonInstanceLayout.resolveForWorld(
            configuredBaseX = -400,
            configuredBaseY = 215,
            configuredBaseZ = 300,
            configuredSpacing = 128,
            borderCenterX = 0.0,
            borderCenterZ = 0.0,
            borderSize = 2_000.0,
            minHeight = -64,
            maxHeight = 320,
        )
        assertEquals(-400, configured.baseX)
        assertEquals(215, configured.baseY)
        assertEquals(300, configured.baseZ)
        assertEquals(128, configured.spacing)
    }

    @Test
    fun `slot tracker enforces RESERVED to ACTIVE to CLEANING to release without leaks or double reservation`() {
        val tracker = DungeonSlotTracker()
        assertEquals(0, tracker.activeCount())
        assertTrue(tracker.tryReserve(0))
        // 同時予約は拒否される
        assertFalse(tracker.tryReserve(0))
        assertEquals(DungeonSlotState.RESERVED, tracker.stateOf(0))
        assertEquals(setOf(0), tracker.occupiedSlots())
        assertEquals(1, tracker.activeCount())

        // RESERVED -> ACTIVE -> CLEANING -> 解放 -> 再利用
        tracker.activate(0)
        assertEquals(DungeonSlotState.ACTIVE, tracker.stateOf(0))
        assertEquals(1, tracker.activeCount())
        assertTrue(tracker.beginCleaning(0))
        assertEquals(DungeonSlotState.CLEANING, tracker.stateOf(0))
        assertFalse(tracker.tryReserve(0))
        tracker.release(0)
        assertEquals(null, tracker.stateOf(0))
        assertEquals(0, tracker.activeCount())
        assertTrue(tracker.tryReserve(0))

        // placement 失敗・中断時の RESERVED からの直接解放
        tracker.release(0)
        assertEquals(0, tracker.activeCount())
        assertThrows(IllegalStateException::class.java) { tracker.activate(0) }
        assertFalse(tracker.beginCleaning(0))
    }

    @Test
    fun `bounds use floor origin and arithmetic chunk ranges for negative coordinates`() {
        val bounds = DungeonInstanceBounds(minX = -56, minY = -10, minZ = -31)

        assertEquals(-2, bounds.maxX)
        assertEquals(23, bounds.maxY)
        assertEquals(23, bounds.maxZ)
        assertEquals(-4..-1, bounds.chunkX)
        assertEquals(-2..1, bounds.chunkZ)
        assertEquals(DungeonInstanceBounds(99_973, 210, 99_973), OverworldDungeonInstanceLayout().bounds(0))
    }

    @Test
    fun `block positions traverse x then z then y without skipping boundaries`() {
        val bounds = DungeonInstanceBounds(minX = -55, minY = 7, minZ = -17)
        val width = OverworldDungeonInstanceLayout.ARENA_WIDTH
        val layerSize = width * width

        assertEquals(DungeonBlockPosition(-55, 7, -17), bounds.position(0))
        assertEquals(DungeonBlockPosition(-54, 7, -17), bounds.position(1))
        assertEquals(DungeonBlockPosition(-1, 7, -17), bounds.position(width - 1))
        assertEquals(DungeonBlockPosition(-55, 7, -16), bounds.position(width))
        assertEquals(DungeonBlockPosition(-55, 8, -17), bounds.position(layerSize))
        assertEquals(DungeonBlockPosition(-1, 40, 37), bounds.position(DungeonInstanceBounds.BLOCK_COUNT - 1))
        assertThrows(IllegalArgumentException::class.java) { bounds.position(-1) }
        assertThrows(IllegalArgumentException::class.java) { bounds.position(DungeonInstanceBounds.BLOCK_COUNT) }
    }

    @Test
    fun `return acknowledgement requires a safe distant ground position below the slot`() {
        val slot = OverworldDungeonInstanceLayout().center(0)
        assertTrue(canAcknowledgeDungeonReturn(slot, 120.5, 70.0, -30.5, true, true))
        assertFalse(canAcknowledgeDungeonReturn(slot, slot.x + 2.0, 70.0, slot.z + 2.0, true, true))
        assertFalse(canAcknowledgeDungeonReturn(slot, 120.5, slot.y.toDouble(), -30.5, true, true))
        assertFalse(canAcknowledgeDungeonReturn(slot, 120.5, 70.0, -30.5, false, true))
        assertFalse(canAcknowledgeDungeonReturn(slot, 120.5, 70.0, -30.5, true, false))
    }
}
