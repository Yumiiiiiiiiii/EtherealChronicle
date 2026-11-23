package net.nakaymi.etherealchronicle.paper

import net.nakaymi.etherealchronicle.core.OverworldSite
import net.nakaymi.etherealchronicle.core.OverworldSiteIds
import org.bukkit.Material
import org.bukkit.block.structure.StructureRotation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockbukkit.mockbukkit.MockBukkit

/** 同梱schematicの読込、拠点半径不変条件と計画成功・失敗時の安全性を検証します。 */
class OverworldTownAssemblerTest {
    @Test
    fun `bundled overworld schematics all load`() {
        val paths = mutableListOf<String>()
        val assets = OverworldAssets.load { path -> paths += path; SchematicResources.load(path) }
        assertEquals(27, assets.names().size)
        assertEquals(27, paths.distinct().size)
        assertTrue(paths.all { path -> path.substringAfterLast('/').substringBeforeLast('.') in assets.names() })
        val blockState = Regex("^minecraft:[a-z0-9_]+(\\[[a-z0-9_=,]+])?$")
        assertTrue(assets.names().all { name -> assets[name].palette.all { it.matches(blockState) } })
        assertTrue(assets.names().all { assets[it].width > 0 && assets[it].height > 0 && assets[it].length > 0 })
    }

    @Test
    fun `site plot and load radii preserve non-overlap and chunk buffer invariants`() {
        val sites = listOf(
            OverworldSiteIds.LUMINA_PORT,
            OverworldSiteIds.ASHWOOD_FOREST,
            OverworldSiteIds.WEEPING_MINE,
            OverworldSiteIds.STARGAZER_HILL,
            OverworldSiteIds.VOID_SANCTUM,
            OverworldSiteIds.FROSTPEAK_OUTPOST,
            OverworldSiteIds.GLACIER_RIDGE,
            OverworldSiteIds.CRYSTALLINE_CAVERN,
            OverworldSiteIds.GLACIAL_CITADEL,
        )
        sites.forEach { id ->
            val plot = OverworldTownAssembler.plotRadius(id)
            val load = OverworldTownAssembler.loadRadius(id)
            assertTrue(load >= plot + 24, "loadRadius($id)=$load must exceed plotRadius=$plot by at least 24 blocks")
        }
        // Tier 3 最小間隔 150 でも、Lumina+Frostpeak（最小距離 600）を除く任意の隣接拠点の建築半径が重ならないことを保証します。
        for (i in sites.indices) for (j in i + 1 until sites.size) {
            val a = sites[i]
            val b = sites[j]
            val minDistance = if ((a == OverworldSiteIds.LUMINA_PORT && b == OverworldSiteIds.FROSTPEAK_OUTPOST) ||
                (b == OverworldSiteIds.LUMINA_PORT && a == OverworldSiteIds.FROSTPEAK_OUTPOST)
            ) 600 else 150
            assertTrue(
                OverworldTownAssembler.plotRadius(a) + OverworldTownAssembler.plotRadius(b) <= minDistance,
                "$a and $b plot radii would overlap at distance $minDistance",
            )
        }
    }

    @Test
    fun `rotated entrance has outward portal exit`() {
        val server = MockBukkit.mock()
        try {
            val world = server.addSimpleWorld("overworld-portals")
            for (x in -10..20) for (z in -10..20) world.getBlockAt(x, 63, z).type = Material.GRASS_BLOCK
            val data = SchematicData(5, 5, 5, listOf("minecraft:stone"), IntArray(125))
            val assembler = OverworldTownAssembler(OverworldAssets.load { data })
            val directions = mapOf(StructureRotation.NONE to (0 to 2), StructureRotation.CLOCKWISE_90 to (-2 to 0),
                StructureRotation.CLOCKWISE_180 to (0 to -2), StructureRotation.COUNTERCLOCKWISE_90 to (2 to 0))
            directions.forEach { (rotation, offset) ->
                val building = OverworldBuildingPlan("entrance", "entrance_novice_arena", OverworldPoint(2, 64, 2), rotation, emptyMap(), false)
                val door = assembler.door(data, building)
                val portal = assembler.portal(world, "ec:area_ashwood_forest", "ec:dungeon_novice_arena", data, building, door)
                val expectedDoor = SchematicPlacer.transformLocalPoint(data, rotation, data.width / 2, 1, data.length - 1)
                assertEquals(building.origin.x + expectedDoor.x, door.x)
                assertEquals(building.origin.z + expectedDoor.z, door.z)
                assertEquals(door.x + offset.first, portal.exit.x)
                assertEquals(door.z + offset.second, portal.exit.z)
                assertTrue(portal.min.x <= portal.max.x && portal.min.z <= portal.max.z)
            }
        } finally { MockBukkit.unmock() }
    }

    @Test
    fun `non town planning succeeds without fishing pool`() {
        val server = MockBukkit.mock()
        try {
            val world = server.addSimpleWorld("overworld-non-town")
            for (x in -75..75) for (z in -75..75) {
                world.getBlockAt(x, 62, z).type = Material.STONE
                world.getBlockAt(x, 63, z).type = Material.GRASS_BLOCK
            }
            val data = SchematicData(1, 1, 1, listOf("minecraft:stone"), intArrayOf(0))
            val assembler = OverworldTownAssembler(OverworldAssets.load { data })
            val site = OverworldSite("ec:area_weeping_mine", 0, 63, 0, 1, "PLAINS")
            val planning = assembler.planning(world, site, 42L)
            while (planning.steps.hasNext()) planning.steps.next()
            val plan = planning.result()
            assertTrue(plan.buildings.any { it.name == "mine" })
            assertTrue(plan.pond.isEmpty())
            assertTrue("fishing_water" !in plan.anchors)
        } finally { MockBukkit.unmock() }
    }

    @Test
    fun `lumina town planning succeeds on flat terrain with distinct houses`() {
        val server = MockBukkit.mock()
        try {
            val world = server.addSimpleWorld("overworld-lumina-ok")
            for (x in -96..96) for (z in -96..96) {
                world.getBlockAt(x, 61, z).type = Material.STONE
                world.getBlockAt(x, 62, z).type = Material.GRASS_BLOCK
            }
            val assets = OverworldAssets.load { SchematicData(3, 3, 3, listOf("minecraft:stone"), IntArray(27)) }
            val assembler = OverworldTownAssembler(assets)
            val site = OverworldSite(OverworldSiteIds.LUMINA_PORT, 0, 62, 0, 1, "PLAINS")
            val planning = assembler.planning(world, site, 2697034697951946031L)
            while (planning.steps.hasNext()) planning.steps.next()
            val plan = planning.result()
            val houses = plan.buildings.filter { it.name.startsWith("house_") }
            assertTrue(houses.size >= 10, "Expected at least 10 Lumina houses, got ${houses.size}")
            assertTrue(houses.map { it.template }.toSet().size >= 5, "Expected at least 5 distinct house templates")
            assertTrue("plaza_center" in plan.anchors)
        } finally { MockBukkit.unmock() }
    }

    @Test
    fun `planning failure leaves the world unchanged and reports rejection diagnostics`() {
        val server = MockBukkit.mock()
        try {
            val world = server.addSimpleWorld("overworld-town-plan")
            val site = OverworldSite("ec:area_lumina_port", 0, 64, 0, 1, "PLAINS")
            for (x in -96..96) for (z in -96..96) {
                world.getBlockAt(x, 61, z).type = Material.GRASS_BLOCK
                world.getBlockAt(x, 62, z).type = Material.GRASS_BLOCK
                // 中心広場周辺以外を人工ブロックで塞ぎ、必須施設または住宅が確定的に配置不能な環境を作ります。
                if (kotlin.math.abs(x) > 10 || kotlin.math.abs(z) > 10) {
                    world.getBlockAt(x, 63, z).type = Material.BRICKS
                }
            }

            val assets = OverworldAssets.load { SchematicData(3, 3, 3, listOf("minecraft:stone"), IntArray(27)) }
            val assembler = OverworldTownAssembler(assets)
            val sampleY = world.getHighestBlockYAt(0, 0, org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES)
            val sample = runCatching { SchematicPlacer.prepare(assets["civic_market_plaza"], world, -1, sampleY, -1,
                SchematicPlacementOptions(rotation = StructureRotation.NONE, extendFoundation = true, clearTreeCanopyAbove = true, protectExisting = true)) }
            assertTrue(sample.isSuccess, "fixture footing should validate at y=$sampleY: ${sample.exceptionOrNull()?.message}")
            val planning = assembler.planning(world, site, 8675309L)
            val untouched = world.getBlockAt(0, 62, 0).type
            assertTrue(planning.steps.hasNext())
            planning.steps.next()
            assertEquals(untouched, world.getBlockAt(0, 62, 0).type, "planning may inspect but must not place blocks")
            val failure = assertThrows<IllegalStateException> { while (planning.steps.hasNext()) planning.steps.next() }
            assertTrue(failure.message.orEmpty().contains("terrain="), "Failure should include rejection diagnostics: ${failure.message}")
            assertEquals(untouched, world.getBlockAt(0, 62, 0).type, "failed planning must not mutate the world")
        } finally {
            MockBukkit.unmock()
        }
    }
}
