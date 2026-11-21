package net.nakaymi.etherealchronicle.paper

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.zip.GZIPInputStream

class DemoStructureTemplateTest {
    @Test
    fun `bundled waystone is a compressed structure nbt with expected materials`() {
        val bytes = requireNotNull(javaClass.classLoader.getResourceAsStream(DemoStructureTemplate.RESOURCE)).use { it.readBytes() }
        assertEquals(0x1f, bytes[0].toInt() and 0xff)
        assertEquals(0x8b, bytes[1].toInt() and 0xff)
        val decoded = GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
        val nbtText = decoded.toString(Charsets.ISO_8859_1)

        assertTrue(nbtText.contains("DataVersion"))
        assertTrue(nbtText.contains("stone_bricks"))
        assertTrue(nbtText.contains("lantern"))
    }

    @Test
    fun `structure safety check requires every target cell to be air`() {
        assertTrue(DemoStructureTemplate.clearFootprint(5, 4, 1) { _, _, _ -> true })
        assertFalse(DemoStructureTemplate.clearFootprint(5, 4, 1) { x, y, _ -> x != 2 || y != 1 })
        assertFalse(DemoStructureTemplate.clearFootprint(49, 1, 1) { _, _, _ -> true })
    }
}
