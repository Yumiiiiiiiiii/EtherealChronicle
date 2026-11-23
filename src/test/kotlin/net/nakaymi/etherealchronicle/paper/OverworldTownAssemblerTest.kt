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

    @Test
    fun `lumina town planning builds a fishing pond on beach sand supported by sandstone`() {
        val server = MockBukkit.mock()
        try {
            val world = server.addSimpleWorld("overworld-lumina-beach")
            // Frostpeak より広い Lumina の plotRadius(86) 全体を砂 + 砂岩支持で敷設します。
            for (x in -90..90) for (z in -90..90) {
                world.getBlockAt(x, 61, z).type = Material.SANDSTONE
                world.getBlockAt(x, 62, z).type = Material.SAND
                world.getBlockAt(x, 63, z).type = Material.SAND
            }
            val assets = OverworldAssets.load { SchematicData(1, 1, 1, listOf("minecraft:stone"), intArrayOf(0)) }
            val assembler = OverworldTownAssembler(assets)
            val site = OverworldSite(OverworldSiteIds.LUMINA_PORT, 0, 63, 0, 1, "BEACH")
            val planning = assembler.planning(world, site, 4528502914240555028L)
            while (planning.steps.hasNext()) planning.steps.next()
            val plan = planning.result()
            assertTrue(plan.pond.isNotEmpty(), "Expected supported BEACH sand terrain to allow a fishing pond")
            val waterAnchor = plan.anchors["fishing_water"]
            assertTrue(waterAnchor != null, "Expected fishing_water anchor on BEACH terrain")
            assertTrue(
                plan.pond.any { it.position == waterAnchor && it.after == Material.WATER },
                "Expected fishing_water anchor $waterAnchor to match a pond water block",
            )
        } finally {
            MockBukkit.unmock()
        }
    }

    @Test
    fun `town planning skips pond over hollow ground and falls back to open natural water`() {
        val server = MockBukkit.mock()
        try {
            val world = server.addSimpleWorld("overworld-frostpeak-natural-water")
            // 池底面 y - 2 (61) は既定の AIR のままにし、人工池を安全スキップさせる
            for (x in -76..76) for (z in -76..76) {
                world.getBlockAt(x, 62, z).type = Material.STONE
                world.getBlockAt(x, 63, z).type = Material.GRASS_BLOCK
            }
            // 北側を凍結海、南東側を開水面にして、開水面だけが選ばれることを検証する
            for (x in -30..30) for (z in -40..-16) {
                world.getBlockAt(x, 63, z).type = Material.ICE
            }
            for (x in 16..44) for (z in 16..44) {
                world.getBlockAt(x, 63, z).type = Material.WATER
            }
            val assets = OverworldAssets.load { SchematicData(1, 1, 1, listOf("minecraft:stone"), intArrayOf(0)) }
            val assembler = OverworldTownAssembler(assets)
            val site = OverworldSite(OverworldSiteIds.FROSTPEAK_OUTPOST, 0, 63, 0, 1, "SNOWY_PLAINS")
            val planning = assembler.planning(world, site, 123456789L)
            while (planning.steps.hasNext()) planning.steps.next()
            val plan = planning.result()
            assertTrue(plan.pond.isEmpty(), "Expected pond to be skipped over hollow y-2 ground")
            val waterAnchor = plan.anchors["fishing_water"]
            assertTrue(waterAnchor != null, "Expected fallback to nearby open natural water")
            assertEquals(
                Material.WATER,
                world.getBlockAt(waterAnchor!!.x, waterAnchor.y, waterAnchor.z).type,
                "Fallback fishing_water must point to open WATER",
            )
            assertTrue(
                world.getBlockAt(waterAnchor.x, waterAnchor.y + 1, waterAnchor.z).type.isAir,
                "Fallback fishing_water must have AIR above it",
            )
        } finally {
            MockBukkit.unmock()
        }
    }

    @Test
    fun `actions tolerate natural terrain drift between planning and applying`() {
        val server = MockBukkit.mock()
        try {
            val world = server.addSimpleWorld("overworld-natural-drift")
            val assets = OverworldAssets.load { SchematicData(1, 1, 1, listOf("minecraft:stone"), intArrayOf(0)) }
            val assembler = OverworldTownAssembler(assets)
            val site = OverworldSite(OverworldSiteIds.ASHWOOD_FOREST, 0, 63, 0, 1, "FOREST")

            // 計画〜適用間に起きる自然変化（草↔土、水↔氷、降雪、葉の自然消滅）を再現します。
            world.getBlockAt(0, 63, 0).type = Material.DIRT          // GRASS_BLOCK -> DIRT (羊採食・影枯れ)
            world.getBlockAt(1, 63, 0).type = Material.GRASS_BLOCK   // DIRT -> GRASS_BLOCK (草伝播)
            world.getBlockAt(2, 63, 0).type = Material.ICE           // WATER -> ICE (凍結)
            world.getBlockAt(3, 63, 0).type = Material.WATER         // ICE -> WATER (融解)
            world.getBlockAt(0, 64, 0).type = Material.SNOW          // SHORT_GRASS -> SNOW (降雪)
            world.getBlockAt(1, 64, 0).type = Material.AIR           // OAK_LEAVES -> AIR (葉消滅)
            world.getBlockAt(5, 61, 0).type = Material.STONE         // 池底面の支持層 (y - 2)
            world.getBlockAt(5, 62, 0).type = Material.DIRT          // 池底面: GRASS_BLOCK -> DIRT
            world.getBlockAt(5, 63, 0).type = Material.DIRT          // 池水面: GRASS_BLOCK -> DIRT
            world.getBlockAt(6, 63, 0).type = Material.SNOW          // 池水面: AIR -> SNOW
            world.getBlockAt(8, 64, 0).type = Material.SNOW          // 街灯予定地: AIR -> SNOW
            world.getBlockAt(10, 64, 0).type = Material.SNOW         // 装飾予定地: AIR -> SNOW

            val plan = OverworldTownPlan(
                site = site,
                buildings = emptyList(),
                road = listOf(
                    OverworldTerrainChange(OverworldPoint(0, 63, 0), Material.GRASS_BLOCK, Material.COBBLESTONE),
                    OverworldTerrainChange(OverworldPoint(1, 63, 0), Material.DIRT, Material.GRAVEL),
                    OverworldTerrainChange(OverworldPoint(2, 63, 0), Material.WATER, Material.SPRUCE_PLANKS),
                    OverworldTerrainChange(OverworldPoint(3, 63, 0), Material.ICE, Material.COBBLESTONE),
                ),
                roadClearance = listOf(
                    OverworldTerrainChange(OverworldPoint(0, 64, 0), Material.SHORT_GRASS, Material.AIR),
                    OverworldTerrainChange(OverworldPoint(1, 64, 0), Material.OAK_LEAVES, Material.AIR),
                ),
                pond = listOf(
                    OverworldTerrainChange(OverworldPoint(5, 62, 0), Material.GRASS_BLOCK, Material.GRAVEL),
                    OverworldTerrainChange(OverworldPoint(5, 63, 0), Material.GRASS_BLOCK, Material.WATER),
                    OverworldTerrainChange(OverworldPoint(6, 63, 0), Material.AIR, Material.WATER),
                ),
                lights = listOf(
                    OverworldTerrainChange(OverworldPoint(8, 64, 0), Material.AIR, Material.SPRUCE_FENCE),
                ),
                decorations = listOf(
                    OverworldTerrainChange(OverworldPoint(10, 64, 0), Material.AIR, Material.AMETHYST_BLOCK),
                ),
                anchors = emptyMap(),
                portals = emptyList(),
                prepared = emptyMap(),
            )

            assembler.actions(world, plan).forEachRemaining { it() }

            assertEquals(Material.COBBLESTONE, world.getBlockAt(0, 63, 0).type)
            assertEquals(Material.GRAVEL, world.getBlockAt(1, 63, 0).type)
            assertEquals(Material.SPRUCE_PLANKS, world.getBlockAt(2, 63, 0).type)
            assertEquals(Material.COBBLESTONE, world.getBlockAt(3, 63, 0).type)
            assertEquals(Material.AIR, world.getBlockAt(0, 64, 0).type)
            assertEquals(Material.AIR, world.getBlockAt(1, 64, 0).type)
            assertEquals(Material.GRAVEL, world.getBlockAt(5, 62, 0).type)
            assertEquals(Material.WATER, world.getBlockAt(5, 63, 0).type)
            assertEquals(Material.WATER, world.getBlockAt(6, 63, 0).type)
            assertEquals(Material.SPRUCE_FENCE, world.getBlockAt(8, 64, 0).type)
            assertEquals(Material.AMETHYST_BLOCK, world.getBlockAt(10, 64, 0).type)
        } finally {
            MockBukkit.unmock()
        }
    }

    @Test
    fun `actions still reject artificial hazardous or seal-breaking block changes`() {
        val server = MockBukkit.mock()
        try {
            val world = server.addSimpleWorld("overworld-artificial-reject")
            val assets = OverworldAssets.load { SchematicData(1, 1, 1, listOf("minecraft:stone"), intArrayOf(0)) }
            val assembler = OverworldTownAssembler(assets)
            val site = OverworldSite(OverworldSiteIds.ASHWOOD_FOREST, 0, 63, 0, 1, "FOREST")

            fun emptyPlan(
                road: List<OverworldTerrainChange> = emptyList(),
                roadClearance: List<OverworldTerrainChange> = emptyList(),
                pond: List<OverworldTerrainChange> = emptyList(),
                lights: List<OverworldTerrainChange> = emptyList(),
                decorations: List<OverworldTerrainChange> = emptyList(),
            ) = OverworldTownPlan(site, emptyList(), road, roadClearance, pond, lights, decorations, emptyMap(), emptyList(), emptyMap())

            // 道路に人工ブロックが置かれた場合は拒否し、上書きしない
            world.getBlockAt(0, 63, 0).type = Material.BRICKS
            val roadErr = assertThrows<IllegalStateException> {
                assembler.actions(world, emptyPlan(road = listOf(
                    OverworldTerrainChange(OverworldPoint(0, 63, 0), Material.GRASS_BLOCK, Material.COBBLESTONE),
                ))).forEachRemaining { it() }
            }
            assertTrue(roadErr.message.orEmpty().contains("got BRICKS"), "Unexpected message: ${roadErr.message}")
            assertEquals(Material.BRICKS, world.getBlockAt(0, 63, 0).type)

            // 道路クリアランスに人工ブロックが現れた場合は拒否する
            world.getBlockAt(1, 64, 0).type = Material.OAK_PLANKS
            assertThrows<IllegalStateException> {
                assembler.actions(world, emptyPlan(roadClearance = listOf(
                    OverworldTerrainChange(OverworldPoint(1, 64, 0), Material.SHORT_GRASS, Material.AIR),
                ))).forEachRemaining { it() }
            }
            assertEquals(Material.OAK_PLANKS, world.getBlockAt(1, 64, 0).type)

            // 池に溶岩が現れた場合は拒否する
            world.getBlockAt(2, 63, 0).type = Material.LAVA
            assertThrows<IllegalStateException> {
                assembler.actions(world, emptyPlan(pond = listOf(
                    OverworldTerrainChange(OverworldPoint(2, 63, 0), Material.GRASS_BLOCK, Material.WATER),
                ))).forEachRemaining { it() }
            }
            assertEquals(Material.LAVA, world.getBlockAt(2, 63, 0).type)

            // 池の底面予定地が空気・植物へ変化した場合は封水保護のため拒否する
            world.getBlockAt(5, 61, 0).type = Material.STONE
            world.getBlockAt(5, 62, 0).type = Material.AIR
            assertThrows<IllegalStateException> {
                assembler.actions(world, emptyPlan(pond = listOf(
                    OverworldTerrainChange(OverworldPoint(5, 62, 0), Material.GRASS_BLOCK, Material.GRAVEL),
                ))).forEachRemaining { it() }
            }
            assertEquals(Material.AIR, world.getBlockAt(5, 62, 0).type)

            // 池の底面直下 (y - 2) が空洞化した場合も封水保護のため拒否する
            world.getBlockAt(6, 61, 0).type = Material.AIR
            world.getBlockAt(6, 62, 0).type = Material.DIRT
            val supportErr = assertThrows<IllegalStateException> {
                assembler.actions(world, emptyPlan(pond = listOf(
                    OverworldTerrainChange(OverworldPoint(6, 62, 0), Material.GRASS_BLOCK, Material.GRAVEL),
                ))).forEachRemaining { it() }
            }
            assertTrue(supportErr.message.orEmpty().contains("support changed"), "Unexpected message: ${supportErr.message}")
            assertEquals(Material.DIRT, world.getBlockAt(6, 62, 0).type)

            // 街灯・装飾位置に人工ブロックや植物が現れた場合は拒否し、上書きしない
            world.getBlockAt(3, 64, 0).type = Material.BRICKS
            assertThrows<IllegalStateException> {
                assembler.actions(world, emptyPlan(lights = listOf(
                    OverworldTerrainChange(OverworldPoint(3, 64, 0), Material.AIR, Material.SPRUCE_FENCE),
                ))).forEachRemaining { it() }
            }
            assertEquals(Material.BRICKS, world.getBlockAt(3, 64, 0).type)

            world.getBlockAt(4, 64, 0).type = Material.SHORT_GRASS
            assertThrows<IllegalStateException> {
                assembler.actions(world, emptyPlan(decorations = listOf(
                    OverworldTerrainChange(OverworldPoint(4, 64, 0), Material.AIR, Material.AMETHYST_BLOCK),
                ))).forEachRemaining { it() }
            }
            assertEquals(Material.SHORT_GRASS, world.getBlockAt(4, 64, 0).type)
        } finally {
            MockBukkit.unmock()
        }
    }
}
