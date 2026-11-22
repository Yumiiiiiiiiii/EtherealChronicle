package net.nakaymi.etherealchronicle.paper

import net.nakaymi.etherealchronicle.core.OverworldDungeonPortal
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 回転済み開口部の内外判定が中心球ではなく保存済み範囲に従うことを確認します。 */
class DungeonEntranceBoundsTest {
    @Test
    fun `entrance includes all saved portal blocks and excludes adjacent blocks`() {
        val portal = OverworldDungeonPortal("ec:dungeon_void_sanctum", "ec:area_void_sanctum",
            10, 74, -5, 12, 70, -8,
            minX = 9, minY = 72, minZ = -5, maxX = 11, maxY = 76, maxZ = -5)

        assertTrue(isInsidePortal(portal, 9.5, 72.0, -4.5))
        assertTrue(isInsidePortal(portal, 11.99, 76.99, -4.01))
        assertFalse(isInsidePortal(portal, 8.99, 74.0, -4.5))
        assertFalse(isInsidePortal(portal, 10.0, 77.0, -4.5))
        assertFalse(isInsidePortal(portal, 10.0, 74.0, -3.99))
    }
}
