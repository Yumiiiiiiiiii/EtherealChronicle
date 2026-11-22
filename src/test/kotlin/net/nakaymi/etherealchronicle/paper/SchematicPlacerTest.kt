package net.nakaymi.etherealchronicle.paper

import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Directional
import org.bukkit.block.structure.StructureRotation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assumptions.assumeTrue
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

    @Test
    fun `all quarter turns map every local coordinate inside rotated dimensions`() {
        val schematic = SchematicData(2, 1, 3, listOf("minecraft:stone"), IntArray(6))
        val expected = mapOf(
            StructureRotation.NONE to (2 to 3),
            StructureRotation.CLOCKWISE_90 to (3 to 2),
            StructureRotation.CLOCKWISE_180 to (2 to 3),
            StructureRotation.COUNTERCLOCKWISE_90 to (3 to 2),
        )
        expected.forEach { (rotation, dimensions) ->
            assertEquals(dimensions, SchematicPlacer.rotatedDimensions(schematic, rotation))
            val points = (0 until schematic.width).flatMap { x -> (0 until schematic.length).map { z ->
                SchematicPlacer.transformLocalPoint(schematic, rotation, x, 0, z)
            } }
            assertEquals(6, points.toSet().size)
            assertEquals(setOf(0, 1, 2, 3, 4, 5), points.map { it.z * dimensions.first + it.x }.toSet())
        }
    }

    @Test
    fun `rotated placement moves coordinates and rotates swapped native stair data`() {
        val server = MockBukkit.mock()
        try {
            val world = server.addSimpleWorld("schematic-rotation")
            val data = SchematicData(2, 1, 1,
                listOf("minecraft:oak_stairs[facing=north,half=bottom,shape=inner_left,waterlogged=false]", "minecraft:gold_block"),
                intArrayOf(0, 1))
            val warnings = mutableListOf<String>()
            SchematicPlacer.place(data, world, 20, 64, 30,
                SchematicPlacementOptions(rotation = StructureRotation.CLOCKWISE_90, materialSwaps = mapOf(Material.OAK_STAIRS to Material.BIRCH_STAIRS))) { message, _ -> warnings += message }
            // MockBukkitのBlockData.rotate未実装環境ではネイティブ回転だけをスキップします。
            assumeTrue(warnings.isEmpty(), "BlockData rotation is unsupported by this MockBukkit version")

            val stair = world.getBlockAt(20, 64, 30)
            assertEquals(Material.BIRCH_STAIRS, stair.type)
            assertEquals(BlockFace.EAST, (stair.blockData as Directional).facing)
            assertEquals(Material.GOLD_BLOCK, world.getBlockAt(20, 64, 31).type)
            assertNotEquals(Material.BIRCH_STAIRS, world.getBlockAt(21, 64, 30).type)
        } finally {
            MockBukkit.unmock()
        }
    }

    @Test
    fun `foundation depth refusal and protected footprint leave world unchanged`() {
        val server = MockBukkit.mock()
        try {
            val world = server.addSimpleWorld("schematic-foundation")
            val data = SchematicData(1, 1, 1, listOf("minecraft:stone"), intArrayOf(0))
            world.getBlockAt(0, 59, 0).type = Material.STONE
            assertThrows(IllegalArgumentException::class.java) {
                SchematicPlacer.prepare(data, world, 0, 64, 0, SchematicPlacementOptions(extendFoundation = true, maxFoundationDepth = 3))
            }
            assertEquals(Material.AIR, world.getBlockAt(0, 61, 0).type)
            assertEquals(Material.AIR, world.getBlockAt(0, 64, 0).type)

            world.getBlockAt(0, 63, 0).type = Material.OAK_PLANKS
            assertThrows(IllegalArgumentException::class.java) {
                SchematicPlacer.prepare(data, world, 0, 64, 0, SchematicPlacementOptions(extendFoundation = true, protectExisting = true))
            }
            assertEquals(Material.OAK_PLANKS, world.getBlockAt(0, 63, 0).type)
            assertEquals(Material.AIR, world.getBlockAt(0, 64, 0).type)
        } finally {
            MockBukkit.unmock()
        }
    }

    @Test
    fun `foundation reaches ground and prepared operations mutate incrementally`() {
        val server = MockBukkit.mock()
        try {
            val world = server.addSimpleWorld("schematic-incremental")
            val data = SchematicData(2, 1, 1, listOf("minecraft:stone"), intArrayOf(0, 0))
            (0..1).forEach { world.getBlockAt(it, 62, 0).type = Material.GRASS_BLOCK }
            val operations = SchematicPlacer.prepare(data, world, 0, 64, 0,
                SchematicPlacementOptions(extendFoundation = true, maxFoundationDepth = 4))
            assertEquals(Material.AIR, world.getBlockAt(0, 63, 0).type)
            operations.next().invoke()
            assertEquals(Material.AIR, world.getBlockAt(0, 63, 0).type, "the first action checks the footing")
            assertEquals(Material.AIR, world.getBlockAt(1, 63, 0).type)
            var count = 1
            while (operations.hasNext()) { operations.next().invoke(); count++ }
            assertEquals(6, count) // 地盤検証2件、支柱2件、建物2件
            assertNotEquals(Material.AIR, world.getBlockAt(0, 63, 0).type)
            assertEquals(Material.STONE, world.getBlockAt(0, 64, 0).type)
            assertEquals(Material.STONE, world.getBlockAt(1, 64, 0).type)
        } finally {
            MockBukkit.unmock()
        }
    }

    @Test
    fun `protected existing footprint block is refused without replacement`() {
        val server = MockBukkit.mock()
        try {
            val world = server.addSimpleWorld("schematic-protected-footprint")
            world.getBlockAt(0, 63, 0).type = Material.GRASS_BLOCK
            world.getBlockAt(0, 64, 0).type = Material.GOLD_BLOCK
            val data = SchematicData(1, 1, 1, listOf("minecraft:stone"), intArrayOf(0))

            assertThrows(IllegalArgumentException::class.java) {
                SchematicPlacer.place(data, world, 0, 64, 0, SchematicPlacementOptions(protectExisting = true))
            }
            assertEquals(Material.GOLD_BLOCK, world.getBlockAt(0, 64, 0).type)
        } finally {
            MockBukkit.unmock()
        }
    }
}
