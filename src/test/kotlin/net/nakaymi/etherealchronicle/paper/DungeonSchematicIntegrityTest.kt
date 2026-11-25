package net.nakaymi.etherealchronicle.paper

import net.nakaymi.etherealchronicle.core.OverworldDungeonInstanceLayout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 同梱ダンジョンアリーナSchematicが存在し、配置処理が前提とする寸法・ブロック状態書式を満たすことを検証します。 */
class DungeonSchematicIntegrityTest {
    private val dungeonIds = listOf(
        "ec:dungeon_novice_arena", "ec:dungeon_void_sanctum", "ec:dungeon_aether_crucible", "ec:dungeon_glacial_citadel",
    )

    @Test
    fun `every bundled dungeon arena matches the instance layout and has valid block states`() {
        val blockState = Regex("""minecraft:[a-z0-9_]+(\[[a-z0-9_=,]+])?""")
        dungeonIds.forEach { id ->
            val path = requireNotNull(WorldSchematicLayout.dungeonPath(id)) { "no path for $id" }
            val schematic = SchematicResources.load(path)
            assertNotNull(schematic, "missing schematic resource: $path")
            schematic!!
            assertEquals(OverworldDungeonInstanceLayout.ARENA_WIDTH, schematic.width, "$path width")
            assertEquals(OverworldDungeonInstanceLayout.ARENA_HEIGHT, schematic.height, "$path height")
            assertEquals(OverworldDungeonInstanceLayout.ARENA_WIDTH, schematic.length, "$path length")
            assertTrue(schematic.palette.isNotEmpty(), "$path palette is empty")
            schematic.palette.forEach { state -> assertTrue(blockState.matches(state), "$path invalid blockstate: $state") }
        }
    }
}
