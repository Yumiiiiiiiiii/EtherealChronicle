package net.nakaymi.etherealchronicle.paper

import net.nakaymi.etherealchronicle.core.OverworldSite
import org.bukkit.Material
import org.bukkit.block.structure.StructureRotation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockbukkit.mockbukkit.MockBukkit

/** 同梱schematicの読込と、計画失敗時にワールドを変更しないことを検証します。 */
class OverworldTownAssemblerTest {
    @Test
    fun `bundled overworld schematics all load`() {
        val assets = OverworldAssets.load()
        val names = listOf("civic_market_plaza", "civic_harbor_pier", "entrance_novice_arena",
            "house_l_shaped_manor", "landmark_ashwood_camp", "prop_waystone")
        assertTrue(names.all { assets[it].width > 0 && assets[it].height > 0 && assets[it].length > 0 })
    }

    @Test
    fun `planning failure leaves the world unchanged`() {
        val server = MockBukkit.mock()
        try {
            val world = server.addSimpleWorld("overworld-town-plan")
            val site = OverworldSite("ec:area_lumina_port", 0, 64, 0, 1, "PLAINS")
            for (x in -80..80) for (z in -80..80) {
                world.getBlockAt(x, 61, z).type = Material.GRASS_BLOCK
                world.getBlockAt(x, 62, z).type = Material.GRASS_BLOCK
                if (x >= 30) world.getBlockAt(x, 63, z).type = Material.GRASS_BLOCK
            }
            for (x in 38..58) for (z in -3..3) world.getBlockAt(x, 63, z).type = Material.WATER

            val assets = OverworldAssets.load { SchematicData(3, 3, 3, listOf("minecraft:stone"), IntArray(27)) }
            val assembler = OverworldTownAssembler(assets)
            val sampleY = world.getHighestBlockYAt(-12, -12, org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES)
            val sample = runCatching { SchematicPlacer.prepare(assets["civic_market_plaza"], world, -13, sampleY, -13,
                SchematicPlacementOptions(rotation = StructureRotation.NONE, extendFoundation = true, clearTreeCanopyAbove = true, protectExisting = true)) }
            assertTrue(sample.isSuccess, "fixture footing should validate at y=$sampleY: ${sample.exceptionOrNull()?.message}")
            val planning = assembler.planning(world, site, 8675309L)
            val untouched = world.getBlockAt(0, 62, 0).type
            assertTrue(planning.steps.hasNext())
            planning.steps.next()
            assertEquals(untouched, world.getBlockAt(0, 62, 0).type, "planning may inspect but must not place blocks")
            assertThrows<IllegalArgumentException> { while (planning.steps.hasNext()) planning.steps.next() }
            assertEquals(untouched, world.getBlockAt(0, 62, 0).type, "failed planning must not mutate the world")
        } finally {
            MockBukkit.unmock()
        }
    }
}
