package net.nakaymi.etherealchronicle.paper

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

/** Bukkitを起動せずSponge v2/v3 NBTとVarIntデータを検証します。 */
class SchematicLoaderTest {
    @Test
    fun `v2 root schematic decodes multi byte palette indices and y z x order`() {
        val schematic = loadFixture("synthetic_v2.schem")

        assertEquals(2, schematic.width)
        assertEquals(2, schematic.height)
        assertEquals(2, schematic.length)
        assertEquals("minecraft:oak_planks", schematic.blockStateAt(0, 0, 0))
        assertEquals("minecraft:air", schematic.blockStateAt(1, 0, 0))
        assertEquals("minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]", schematic.blockStateAt(0, 0, 1))
        assertEquals("minecraft:air", schematic.blockStateAt(0, 1, 0))
        val exposedCopy = schematic.blocks
        exposedCopy[0] = 0
        assertEquals("minecraft:oak_planks", schematic.blockStateAt(0, 0, 0))
    }

    @Test
    fun `v3 nested Blocks palette and data are supported`() {
        val schematic = loadFixture("synthetic_v3.schem")

        assertEquals("minecraft:oak_planks", schematic.blockStateAt(0, 0, 0))
        assertEquals("minecraft:stone", schematic.blockStateAt(1, 0, 1))
    }

    @Test
    fun `v2 Schematic compound nesting is supported`() {
        val schematic = loadFixture("synthetic_v2_nested.schem")
        assertEquals(2, schematic.width)
        assertEquals("minecraft:stone", schematic.blockStateAt(0, 0, 0))
        assertEquals("minecraft:air", schematic.blockStateAt(1, 0, 0))
    }

    @Test
    fun `corrupt gzip and invalid coordinates are rejected`() {
        assertThrows(Exception::class.java) { SchematicLoader.load(ByteArrayInputStream(byteArrayOf(1, 2, 3))) }
        assertThrows(IllegalArgumentException::class.java) { loadFixture("invalid_palette_reference.schem") }
        val schematic = loadFixture("synthetic_v2.schem")
        assertThrows(IllegalArgumentException::class.java) { schematic.blockStateAt(2, 0, 0) }
    }

    @Test
    fun `air helper recognizes vanilla air and structure void states with properties`() {
        assertTrue(isAir("minecraft:air"))
        assertTrue(isAir("minecraft:cave_air[foo=bar]"))
        assertTrue(isAir("minecraft:void_air"))
        assertTrue(isAir("minecraft:structure_void"))
        assertFalse(isAir("minecraft:stone"))
    }

    private fun loadFixture(name: String): SchematicData =
        SchematicLoader.load(requireNotNull(javaClass.getResourceAsStream("/schematics/$name")))
}
