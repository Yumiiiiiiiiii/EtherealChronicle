package net.nakaymi.etherealchronicle.paper

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Schematic配置の島・橋・ダンジョン原点が仕様からずれないことを確認します。 */
class WorldSchematicLayoutTest {
    @Test
    fun `island and bridge placements follow chapter spacing`() {
        assertEquals(9, WorldSchematicLayout.ISLANDS.size)
        WorldSchematicLayout.ISLANDS.forEachIndexed { index, placement ->
            assertEquals("schematics/islands/%02d_%s.schem".format(index, ISLAND_NAMES[index]), placement.path)
            assertEquals(index * 96 - 42, placement.x)
            assertEquals(34, placement.y)
            assertEquals(-42, placement.z)
        }

        assertEquals(8, WorldSchematicLayout.BRIDGES.size)
        WorldSchematicLayout.BRIDGES.forEachIndexed { index, placement ->
            assertEquals(if (index < 4) "schematics/bridges/bridge_ch1.schem" else "schematics/bridges/bridge_ch2.schem", placement.path)
            assertEquals(index * 96 + 38, placement.x)
            assertEquals(48, placement.y)
            assertEquals(-6, placement.z)
            assertTrue(placement.skipAir)
        }
    }

    @Test
    fun `dungeon ids map to documented schematic names`() {
        assertEquals("schematics/dungeons/dungeon_novice_arena.schem", WorldSchematicLayout.dungeonPath("ec:dungeon_novice_arena"))
        assertEquals("schematics/dungeons/dungeon_void_sanctum.schem", WorldSchematicLayout.dungeonPath("ec:dungeon_void_sanctum"))
        assertEquals("schematics/dungeons/dungeon_aether_crucible.schem", WorldSchematicLayout.dungeonPath("ec:dungeon_aether_crucible"))
        assertEquals("schematics/dungeons/dungeon_glacial_citadel.schem", WorldSchematicLayout.dungeonPath("ec:dungeon_glacial_citadel"))
        assertEquals(null, WorldSchematicLayout.dungeonPath("ec:dungeon_missing"))
    }

    companion object {
        private val ISLAND_NAMES = listOf(
            "lumina_port", "ashwood_forest", "weeping_mine", "stargazer_hill", "void_sanctum",
            "frostpeak_outpost", "glacier_ridge", "crystalline_cavern", "glacial_citadel",
        )
    }
}
