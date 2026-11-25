package net.nakaymi.etherealchronicle.paper

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Schematic配置のダンジョン原点が仕様からずれないことを確認します。 */
class WorldSchematicLayoutTest {
    @Test
    fun `dungeon ids map to documented schematic names`() {
        assertEquals("schematics/dungeons/dungeon_novice_arena.schem", WorldSchematicLayout.dungeonPath("ec:dungeon_novice_arena"))
        assertEquals("schematics/dungeons/dungeon_void_sanctum.schem", WorldSchematicLayout.dungeonPath("ec:dungeon_void_sanctum"))
        assertEquals("schematics/dungeons/dungeon_aether_crucible.schem", WorldSchematicLayout.dungeonPath("ec:dungeon_aether_crucible"))
        assertEquals("schematics/dungeons/dungeon_glacial_citadel.schem", WorldSchematicLayout.dungeonPath("ec:dungeon_glacial_citadel"))
        assertEquals(null, WorldSchematicLayout.dungeonPath("ec:dungeon_missing"))
    }

}
