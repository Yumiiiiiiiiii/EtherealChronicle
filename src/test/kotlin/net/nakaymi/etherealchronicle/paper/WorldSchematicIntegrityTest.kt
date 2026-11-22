package net.nakaymi.etherealchronicle.paper

import net.nakaymi.etherealchronicle.data.ContentService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.math.roundToInt

/** 同梱Schematicが存在するビルドで配置内容とYAML地点の接地条件を検証します。 */
class WorldSchematicIntegrityTest {
    @Test
    fun `bundled schematic set is complete and compatible with world content`() {
        val worldPaths = WorldSchematicLayout.ISLANDS + WorldSchematicLayout.BRIDGES
        val presentWorldPaths = worldPaths.filter { SchematicResources.exists(it.path) }
        if (presentWorldPaths.isEmpty()) {
            assertFalse(SchematicResources.hasCompleteWorldSet(), "without builder assets, the documented procedural fallback must remain active")
            return
        }
        assertEquals(worldPaths.size, presentWorldPaths.size, "ship all islands and bridges together")

        val schematics = worldPaths.associate { placement ->
            placement.path to requireNotNull(SchematicResources.load(placement.path))
        }
        val states = schematics.values.flatMap { it.palette }.distinct()
        states.forEach { state -> assertTrue(BLOCK_STATE.matches(state), "invalid Minecraft blockstate: $state") }

        val content = ContentService(Path.of("src/main/resources/content")).load().world
        val standingLocations = buildList {
            addAll(content.npcs.values.map { it.position })
            content.enemies.values.forEach { enemy -> addAll(enemy.spawns.map { it.position }) }
            content.gatheringNodes.values.forEach { node ->
                addAll(node.locations.map { location ->
                    net.nakaymi.etherealchronicle.core.Position(location.world, location.x.toDouble(), location.y.toDouble(), location.z.toDouble())
                })
            }
            addAll(content.treasureChests.values.map { it.position })
        }.filter { it.world == DemoWorld.NAME }
        standingLocations.forEach { position ->
            val island = islandAt(position.x)
            val schematic = schematics.getValue(WorldSchematicLayout.ISLANDS[island].path)
            val (x, y, z) = localCoordinates(position.x, 64.0, position.z, island)
            assertTrue(x in 0 until schematic.width && z in 0 until schematic.length, "content location outside island bounds: $position")
            assertSolid(schematic.blockStateAt(x, y, z), "missing floor for content at $position")
            (y + 1..y + 3).forEach { above ->
                if (above < schematic.height) assertTrue(isAir(schematic.blockStateAt(x, above, z)), "insufficient headroom at $position")
            }
        }
        content.fishingSpots.values.forEach { spot ->
            val island = islandAt(spot.center.x)
            val schematic = schematics.getValue(WorldSchematicLayout.ISLANDS[island].path)
            val (centerX, centerY, centerZ) = localCoordinates(spot.center.x, spot.center.y, spot.center.z, island)
            val hasWater = (-2..2).any { dz ->
                (-2..2).any { dx ->
                    (centerY - 1..centerY + 1).any { y ->
                        val x = centerX + dx
                        val z = centerZ + dz
                        x in 0 until schematic.width && y in 0 until schematic.height && z in 0 until schematic.length &&
                            schematic.blockStateAt(x, y, z).substringBefore('[') == "minecraft:water"
                    }
                }
            }
            assertTrue(hasWater, "fishing spot ${spot.id} has no nearby water")
        }

        val dungeonIds = listOf("ec:dungeon_novice_arena", "ec:dungeon_void_sanctum", "ec:dungeon_aether_crucible", "ec:dungeon_glacial_citadel")
        dungeonIds.forEach { dungeonId ->
            val path = requireNotNull(WorldSchematicLayout.dungeonPath(dungeonId))
            if (!SchematicResources.exists(path)) return@forEach
            val arena = requireNotNull(SchematicResources.load(path))
            assertEquals(DungeonArenaLayout.SCHEMATIC_WIDTH_BLOCKS, arena.width, path)
            assertEquals(DungeonArenaLayout.SCHEMATIC_HEIGHT_BLOCKS, arena.height, path)
            assertEquals(DungeonArenaLayout.SCHEMATIC_LENGTH_BLOCKS, arena.length, path)
            arena.palette.forEach { state -> assertTrue(BLOCK_STATE.matches(state), "invalid blockstate in $path: $state") }
        }

        WorldSchematicLayout.BRIDGES.forEachIndexed { bridgeIndex, placement ->
            val bridge = schematics.getValue(placement.path)
            assertTrue(bridge.width > 0 && bridge.length > 0)
            val sourceCenter = bridgeIndex * WorldSchematicLayout.ISLAND_SPACING_BLOCKS
            for (worldX in sourceCenter + WorldSchematicLayout.BRIDGE_X_OFFSET_BLOCKS until sourceCenter + WorldSchematicLayout.ISLAND_SPACING_BLOCKS - WorldSchematicLayout.ISLAND_HALF_WIDTH_BLOCKS) {
                val localX = worldX - placement.x
                val localY = 64 - placement.y
                val localZ = -placement.z
                assertTrue(localX in 0 until bridge.width && localY in 0 until bridge.height && localZ in 0 until bridge.length)
                assertSolid(bridge.blockStateAt(localX, localY, localZ), "bridge $bridgeIndex is not walkable at x=$worldX")
            }
        }
    }

    private fun islandAt(x: Double): Int = ((x / WorldSchematicLayout.ISLAND_SPACING_BLOCKS).roundToInt()).coerceIn(0, WorldSchematicLayout.ISLANDS.lastIndex)

    private fun localCoordinates(x: Double, y: Double, z: Double, island: Int): Triple<Int, Int, Int> {
        val origin = WorldSchematicLayout.ISLANDS[island]
        return Triple((x - origin.x).toInt(), (y - origin.y).toInt(), (z - origin.z).toInt())
    }

    private fun assertSolid(state: String, message: String) = assertFalse(isAir(state), message)

    companion object {
        private val BLOCK_STATE = Regex("minecraft:[a-z0-9_]+(?:\\[[a-z0-9_]+=[a-z0-9_.,-]+(?:,[a-z0-9_]+=[a-z0-9_.,-]+)*])?")
    }
}
