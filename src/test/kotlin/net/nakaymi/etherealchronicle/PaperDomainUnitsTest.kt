package net.nakaymi.etherealchronicle

import net.nakaymi.etherealchronicle.paper.*
import org.bukkit.Location
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit

class PaperDomainUnitsTest {
    @Test
    fun `time conversions preserve tick and countdown semantics`() {
        assertEquals(1_000L,MinecraftTicks.ticksToMillis(MinecraftTicks.TICKS_PER_SECOND))
        assertEquals(1L,MinecraftTicks.millisToSecondsCeil(950))
        assertEquals(1L,MinecraftTicks.millisToSecondsCeil(1_000))
        assertEquals(2L,MinecraftTicks.millisToSecondsCeil(1_001))
        assertEquals(0L,MinecraftTicks.millisToSecondsCeil(0))
        assertEquals(1L,MinecraftTicks.millisToWholeSeconds(1_999))
    }

    @Test
    fun `block range helper compares squared distance without changing range boundary`() {
        val server = MockBukkit.mock()
        try {
            val world = server.addSimpleWorld("units")
            val origin = Location(world, 0.0, 0.0, 0.0)
            assertTrue(origin.isWithinBlocks(Location(world, 3.0, 4.0, 0.0), 5.0))
            assertFalse(origin.isWithinBlocks(Location(world, 3.0, 4.01, 0.0), 5.0))
            assertFalse(origin.isWithinBlocks(Location(world, 6.0, 0.0, 0.0), 5.0))
        } finally {
            MockBukkit.unmock()
        }
    }

    @Test
    fun `chunk and inventory layout helpers express negative coordinates and reserved slot`() {
        assertEquals(ChunkCoordinates(-1,-2),ChunkCoordinates.fromBlocks(-1,-17))
        assertEquals(ChunkCoordinates(-1,-1),ChunkCoordinates.fromPosition(-0.01,-16.0))
        assertTrue(PlayerInventoryLayout.isUsableStorageSlot(0))
        assertTrue(PlayerInventoryLayout.isUsableStorageSlot(PlayerInventoryLayout.LAST_STORAGE_SLOT))
        assertTrue(PlayerInventoryLayout.isSkillSlot(PlayerInventoryLayout.SKILL_HOTBAR_SLOT))
        assertFalse(PlayerInventoryLayout.isUsableStorageSlot(PlayerInventoryLayout.SKILL_HOTBAR_SLOT))
        assertFalse(PlayerInventoryLayout.isUsableStorageSlot(PlayerInventoryLayout.LAST_STORAGE_SLOT+1))
    }

    @Test
    fun `screen layouts share valid chest slot dimensions`() {
        assertEquals(9,InventoryGrid.forInventorySize(ChestLayout.CLASS_MENU_SLOTS).slotCount)
        assertEquals(54,InventoryGrid.forInventorySize(ChestLayout.DOUBLE_CHEST_SLOTS).slotCount)
        assertEquals(9,ClassSelectionLayout.MAX_CLASS_OPTIONS)
        assertTrue(CodexLayout.NEXT_PAGE_SLOT < ChestLayout.DOUBLE_CHEST_SLOTS)
        assertEquals(45,DialogueLayout.MAX_VISIBLE_CHOICES)
    }

    @Test
    fun `dungeon arena slots have isolated boundaries`() {
        assertEquals(1_024, DungeonArenaLayout.centerX(0))
        assertEquals(1_216, DungeonArenaLayout.centerX(1))
        assertEquals(DungeonArenaLayout.Origin(1_216, 1_024), DungeonArenaLayout.originForSlot(1))
        assertTrue(DungeonArenaLayout.contains(1_054.0, 1_054.0, 0))
        assertFalse(DungeonArenaLayout.contains(1_054.01, 1_024.0, 0))
        assertFalse(DungeonArenaLayout.contains(1_216.0, 1_024.0, 0))
        assertEquals(listOf(0, 96, 192, 288, 384), DemoWorldLayout.HUB_PLAZAS.map { it.centerX })
        assertEquals(65, DemoWorldLayout.SURFACE_Y)
    }
}
