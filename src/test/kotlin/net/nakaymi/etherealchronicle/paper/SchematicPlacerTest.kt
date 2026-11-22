package net.nakaymi.etherealchronicle.paper

import org.bukkit.Material
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit

/** 配置器のair・structure_void契約をPaper互換のテストワールドで確認します。 */
class SchematicPlacerTest {
    @Test
    fun `structure void always preserves blocks and skip air controls clearing`() {
        val server = MockBukkit.mock()
        try {
            val world = server.addSimpleWorld("schematic-place")
            val data = SchematicData(
                width = 3,
                height = 1,
                length = 1,
                palette = listOf("minecraft:air", "minecraft:structure_void", "minecraft:stone"),
                blocks = intArrayOf(0, 1, 2),
            )
            (0..2).forEach { world.getBlockAt(it, 64, 0).type = Material.DIAMOND_BLOCK }

            SchematicPlacer.place(data, world, 0, 64, 0, skipAir = false)
            assertEquals(Material.AIR, world.getBlockAt(0, 64, 0).type)
            assertEquals(Material.DIAMOND_BLOCK, world.getBlockAt(1, 64, 0).type)
            assertEquals(Material.STONE, world.getBlockAt(2, 64, 0).type)

            world.getBlockAt(0, 64, 0).type = Material.GOLD_BLOCK
            SchematicPlacer.place(data, world, 0, 64, 0, skipAir = true)
            assertEquals(Material.GOLD_BLOCK, world.getBlockAt(0, 64, 0).type)
            assertEquals(Material.DIAMOND_BLOCK, world.getBlockAt(1, 64, 0).type)
        } finally {
            MockBukkit.unmock()
        }
    }

    @Test
    fun `unknown palette states warn and are skipped`() {
        val server = MockBukkit.mock()
        try {
            val world = server.addSimpleWorld("schematic-unknown")
            world.getBlockAt(0, 64, 0).type = Material.GOLD_BLOCK
            val schematic = SchematicData(1, 1, 1, listOf("minecraft:not_a_real_block"), intArrayOf(0))
            var warnings = 0

            SchematicPlacer.place(schematic, world, 0, 64, 0, skipAir = false) { _, _ -> warnings++ }

            assertEquals(1, warnings)
            assertEquals(Material.GOLD_BLOCK, world.getBlockAt(0, 64, 0).type)
        } finally {
            MockBukkit.unmock()
        }
    }
}
