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
            plan.buildings.forEachIndexed { index, first ->
                val (firstWidth, firstLength) = SchematicPlacer.rotatedDimensions(assets[first.template], first.rotation)
                for (second in plan.buildings.drop(index + 1)) {
                    val (secondWidth, secondLength) = SchematicPlacer.rotatedDimensions(assets[second.template], second.rotation)
                    val gapX = maxOf(0, maxOf(first.origin.x - (second.origin.x + secondWidth - 1), second.origin.x - (first.origin.x + firstWidth - 1)))
                    val gapZ = maxOf(0, maxOf(first.origin.z - (second.origin.z + secondLength - 1), second.origin.z - (first.origin.z + firstLength - 1)))
                    assertTrue(maxOf(gapX, gapZ) >= 7, "Buildings ${first.name} and ${second.name} should have >= 7 clear blocks between footprints")
                }
            }
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

            // 計画〜適用間に起きる具体的な自然遷移（草↔土、水↔氷、降雪、葉の自然消滅）を再現します。
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
    fun `actions safely skip clearance lights and decorations flooded by natural water`() {
        val server = MockBukkit.mock()
        try {
            val world = server.addSimpleWorld("overworld-water-inflow")
            val assets = OverworldAssets.load { SchematicData(1, 1, 1, listOf("minecraft:stone"), intArrayOf(0)) }
            val assembler = OverworldTownAssembler(assets)
            val site = OverworldSite(OverworldSiteIds.ASHWOOD_FOREST, 0, 61, 0, 1, "FOREST")

            // QA #7 Run 2 再現: 低地の LEAF_LITTER や空きマスに水流が流れ込んで WATER になった場合
            world.getBlockAt(0, 61, 0).type = Material.WATER       // roadClearance: LEAF_LITTER -> WATER
            world.getBlockAt(1, 61, 0).type = Material.SHORT_GRASS // 後続の通常クリアランスは継続実行される
            world.getBlockAt(2, 61, 0).type = Material.WATER       // 街灯支柱 (y+1) が浸水 -> 支柱・ランタンともスキップ
            world.getBlockAt(2, 62, 0).type = Material.AIR
            world.getBlockAt(3, 61, 0).type = Material.AIR         // 街灯ランタン (y+2) が浸水 -> 支柱・ランタンともスキップ
            world.getBlockAt(3, 62, 0).type = Material.WATER
            world.getBlockAt(4, 61, 0).type = Material.WATER       // 装飾予定地が浸水 -> スキップ

            val plan = OverworldTownPlan(
                site = site,
                buildings = emptyList(),
                road = emptyList(),
                roadClearance = listOf(
                    OverworldTerrainChange(OverworldPoint(0, 61, 0), Material.LEAF_LITTER, Material.AIR),
                    OverworldTerrainChange(OverworldPoint(1, 61, 0), Material.SHORT_GRASS, Material.AIR),
                ),
                pond = emptyList(),
                lights = listOf(
                    OverworldTerrainChange(OverworldPoint(2, 61, 0), Material.AIR, Material.SPRUCE_FENCE),
                    OverworldTerrainChange(OverworldPoint(2, 62, 0), Material.AIR, Material.LANTERN),
                    OverworldTerrainChange(OverworldPoint(3, 61, 0), Material.AIR, Material.SPRUCE_FENCE),
                    OverworldTerrainChange(OverworldPoint(3, 62, 0), Material.AIR, Material.LANTERN),
                ),
                decorations = listOf(
                    OverworldTerrainChange(OverworldPoint(4, 61, 0), Material.AIR, Material.SNOW),
                ),
                anchors = emptyMap(),
                portals = emptyList(),
                prepared = emptyMap(),
            )

            assembler.actions(world, plan).forEachRemaining { it() }

            assertEquals(Material.WATER, world.getBlockAt(0, 61, 0).type, "Flooded clearance cell must keep WATER")
            assertEquals(Material.AIR, world.getBlockAt(1, 61, 0).type, "Subsequent clearance cells must still run")
            assertEquals(Material.WATER, world.getBlockAt(2, 61, 0).type, "Flooded lantern fence cell must stay WATER")
            assertEquals(Material.AIR, world.getBlockAt(2, 62, 0).type, "Lantern above flooded fence must not float")
            assertEquals(Material.AIR, world.getBlockAt(3, 61, 0).type, "Fence below flooded lantern must be skipped")
            assertEquals(Material.WATER, world.getBlockAt(3, 62, 0).type, "Flooded lantern cell must stay WATER")
            assertEquals(Material.WATER, world.getBlockAt(4, 61, 0).type, "Flooded decoration cell must stay WATER")
        } finally {
            MockBukkit.unmock()
        }
    }

    @Test
    fun `actions still reject artificial hazardous or unexpected terrain changes`() {
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

            // 道路に人工ブロックや非自然な地盤変化（STONE -> DIRT / ICE -> STONE）が起きた場合は拒否し、上書きしない
            world.getBlockAt(0, 63, 0).type = Material.BRICKS
            val roadErr = assertThrows<IllegalStateException> {
                assembler.actions(world, emptyPlan(road = listOf(
                    OverworldTerrainChange(OverworldPoint(0, 63, 0), Material.GRASS_BLOCK, Material.COBBLESTONE),
                ))).forEachRemaining { it() }
            }
            assertTrue(roadErr.message.orEmpty().contains("got BRICKS"), "Unexpected message: ${roadErr.message}")
            assertEquals(Material.BRICKS, world.getBlockAt(0, 63, 0).type)

            world.getBlockAt(7, 63, 0).type = Material.DIRT
            assertThrows<IllegalStateException> {
                assembler.actions(world, emptyPlan(road = listOf(
                    OverworldTerrainChange(OverworldPoint(7, 63, 0), Material.STONE, Material.COBBLESTONE),
                ))).forEachRemaining { it() }
            }
            assertEquals(Material.DIRT, world.getBlockAt(7, 63, 0).type)

            world.getBlockAt(8, 63, 0).type = Material.STONE
            assertThrows<IllegalStateException> {
                assembler.actions(world, emptyPlan(road = listOf(
                    OverworldTerrainChange(OverworldPoint(8, 63, 0), Material.ICE, Material.COBBLESTONE),
                ))).forEachRemaining { it() }
            }
            assertEquals(Material.STONE, world.getBlockAt(8, 63, 0).type)

            // 道路クリアランスに人工ブロックが現れた場合は拒否する
            world.getBlockAt(1, 64, 0).type = Material.OAK_PLANKS
            assertThrows<IllegalStateException> {
                assembler.actions(world, emptyPlan(roadClearance = listOf(
                    OverworldTerrainChange(OverworldPoint(1, 64, 0), Material.SHORT_GRASS, Material.AIR),
                ))).forEachRemaining { it() }
            }
            assertEquals(Material.OAK_PLANKS, world.getBlockAt(1, 64, 0).type)

            // 池に溶岩や非自然な地盤変化（STONE -> DIRT）が現れた場合は拒否する
            world.getBlockAt(2, 63, 0).type = Material.LAVA
            assertThrows<IllegalStateException> {
                assembler.actions(world, emptyPlan(pond = listOf(
                    OverworldTerrainChange(OverworldPoint(2, 63, 0), Material.GRASS_BLOCK, Material.WATER),
                ))).forEachRemaining { it() }
            }
            assertEquals(Material.LAVA, world.getBlockAt(2, 63, 0).type)

            world.getBlockAt(9, 61, 0).type = Material.STONE
            world.getBlockAt(9, 62, 0).type = Material.DIRT
            assertThrows<IllegalStateException> {
                assembler.actions(world, emptyPlan(pond = listOf(
                    OverworldTerrainChange(OverworldPoint(9, 62, 0), Material.STONE, Material.GRAVEL),
                ))).forEachRemaining { it() }
            }
            assertEquals(Material.DIRT, world.getBlockAt(9, 62, 0).type)

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

            // 街灯・装飾位置に人工ブロックや植物が現れた場合は、相方が水でも拒否し上書きしない
            world.getBlockAt(3, 64, 0).type = Material.BRICKS
            world.getBlockAt(3, 65, 0).type = Material.WATER
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

    @Test
    fun `first building falls back to plot candidates when center is obstructed and keeps center when clear`() {
        val server = MockBukkit.mock()
        try {
            val assets = OverworldAssets.load { SchematicData(3, 3, 3, listOf("minecraft:stone"), IntArray(27)) }
            val assembler = OverworldTownAssembler(assets)
            val site = OverworldSite(OverworldSiteIds.VOID_SANCTUM, 0, 63, 0, 1, "DARK_FOREST")

            // QA #9 Run 7 再現: 中心 ±28 がバニラ構造物（DARK_OAK_PLANKS）で埋まっている場合、拠点半径内の空き区画へフォールバックします。
            val obstructed = server.addSimpleWorld("overworld-void-center-blocked")
            for (x in -60..60) for (z in -60..60) {
                obstructed.getBlockAt(x, 62, z).type = Material.STONE
                obstructed.getBlockAt(x, 63, z).type =
                    if (kotlin.math.abs(x) <= 28 && kotlin.math.abs(z) <= 28) Material.DARK_OAK_PLANKS else Material.GRASS_BLOCK
            }
            val fallbackPlanning = assembler.planning(obstructed, site, -4167603268941691095L)
            while (fallbackPlanning.steps.hasNext()) fallbackPlanning.steps.next()
            val fallbackPlan = fallbackPlanning.result()
            val fallbackRepeat = assembler.planning(obstructed, site, -4167603268941691095L)
            while (fallbackRepeat.steps.hasNext()) fallbackRepeat.steps.next()
            val fallbackRepeatPlan = fallbackRepeat.result()
            assertEquals(fallbackPlan.buildings, fallbackRepeatPlan.buildings, "Fallback placement must be deterministic for the same seed")
            assertEquals(fallbackPlan.portals, fallbackRepeatPlan.portals, "Fallback portals must be deterministic for the same seed")

            val entrance = fallbackPlan.buildings.single { it.name == "entrance" }
            assertTrue(fallbackPlan.buildings.any { it.name == "waystone" }, "Expected waystone alongside fallback entrance")
            assertEquals(1, fallbackPlan.portals.size, "Fallback entrance must still register its dungeon portal")
            assertTrue(
                kotlin.math.abs(entrance.origin.x) > 24 || kotlin.math.abs(entrance.origin.z) > 24,
                "Fallback entrance should be placed outside the obstructed center: ${entrance.origin}",
            )
            assembler.actions(obstructed, fallbackPlan).forEachRemaining { it() }
            assertEquals(
                Material.DARK_OAK_PLANKS,
                obstructed.getBlockAt(0, 63, 0).type,
                "Fallback placement must not overwrite obstructed center structure blocks",
            )

            // 中心が正常な自然地表であれば、従来どおり中心近傍（centerCandidates / Rotation.NONE）に配置され、同シードで完全に決定論的です。
            val clear = server.addSimpleWorld("overworld-void-center-clear")
            for (x in -60..60) for (z in -60..60) {
                clear.getBlockAt(x, 62, z).type = Material.STONE
                clear.getBlockAt(x, 63, z).type = Material.GRASS_BLOCK
            }
            val centerPlanning = assembler.planning(clear, site, -4167603268941691095L)
            while (centerPlanning.steps.hasNext()) centerPlanning.steps.next()
            val centerPlan = centerPlanning.result()
            val centerRepeat = assembler.planning(clear, site, -4167603268941691095L)
            while (centerRepeat.steps.hasNext()) centerRepeat.steps.next()
            val centerRepeatPlan = centerRepeat.result()
            assertEquals(centerPlan.buildings, centerRepeatPlan.buildings, "Clear center buildings must be deterministic for the same seed")
            assertEquals(centerPlan.anchors, centerRepeatPlan.anchors, "Clear center anchors must be deterministic for the same seed")
            val centerEntrance = centerPlan.buildings.single { it.name == "entrance" }
            assertEquals(StructureRotation.NONE, centerEntrance.rotation, "Clear center should stay on centerCandidates")
            assertTrue(
                kotlin.math.abs(centerEntrance.origin.x) <= 24 && kotlin.math.abs(centerEntrance.origin.z) <= 24,
                "Clear center entrance should remain near site center: ${centerEntrance.origin}",
            )
        } finally {
            MockBukkit.unmock()
        }
    }

    @Test
    fun `coastal dungeon entrance rotates away from water to place a safe portal exit`() {
        val server = MockBukkit.mock()
        try {
            val world = server.addSimpleWorld("overworld-coastal-citadel")
            // QA #11 再現: 東西に細長い半島 (z in -14..14 のみ陸地、南北は水域) に 27x27 の entrance_glacial_citadel を置き、
            // NONE(南向き)/CLOCKWISE_180(北向き)が水没しても東西向きへ回転フォールバックできることを検証します。
            // z in -13..13 (幅27) のみ陸地、南北は深海（基礎深度18を超える水域）にし、27x27の入口がNONE/180度でポータル退出不可となる状況を作ります。
            for (x in -76..76) for (z in -76..76) {
                if (z in -13..13) {
                    world.getBlockAt(x, 61, z).type = Material.STONE
                    world.getBlockAt(x, 62, z).type = Material.SNOW_BLOCK
                } else {
                    world.getBlockAt(x, 62, z).type = Material.WATER
                }
            }
            val assets = realFootprintAssets()
            val assembler = OverworldTownAssembler(assets)
            val site = OverworldSite(OverworldSiteIds.GLACIAL_CITADEL, 0, 62, 0, 3, "BEACH")
            val planning = assembler.planning(world, site, 8550623988298585625L)
            while (planning.steps.hasNext()) planning.steps.next()
            val plan = planning.result()
            val repeat = assembler.planning(world, site, 8550623988298585625L)
            while (repeat.steps.hasNext()) repeat.steps.next()
            val repeatPlan = repeat.result()
            assertEquals(plan.buildings, repeatPlan.buildings, "Coastal dungeon rotation fallback must be deterministic")
            assertEquals(plan.portals, repeatPlan.portals, "Coastal dungeon portal fallback must be deterministic")
            val entrance = plan.buildings.single { it.name == "entrance" }
            assertTrue(
                entrance.rotation == StructureRotation.CLOCKWISE_90 || entrance.rotation == StructureRotation.COUNTERCLOCKWISE_90,
                "Entrance on E-W peninsula must rotate east/west away from north/south water, got ${entrance.rotation}",
            )
            assertEquals(1, plan.portals.size, "Expected glacial citadel portal to be registered")
            val exit = plan.portals.single().exit
            val exitFloor = world.getBlockAt(exit.x, exit.y - 1, exit.z).type
            assertTrue(exitFloor.isSolid && exitFloor != Material.WATER, "Portal exit must rest on solid dry ground, got $exitFloor at $exit")
        } finally {
            MockBukkit.unmock()
        }
    }

    @Test
    fun `coastal half-water lumina packs at least ten houses via fine-grid fallback with real footprints`() {
        val server = MockBukkit.mock()
        try {
            val world = server.addSimpleWorld("overworld-coastal-lumina-real")
            // 東側 (x > 16) を海域にした狭隘海岸地形でも、実スキーマチック寸法で Lumina 住宅 10 戸 / 5 種以上を確保できることを検証します。
            for (x in -96..96) for (z in -96..96) {
                world.getBlockAt(x, 61, z).type = Material.SANDSTONE
                world.getBlockAt(x, 62, z).type = Material.SAND
                world.getBlockAt(x, 63, z).type = if (x > 16) Material.WATER else Material.SAND
            }
            val assets = realFootprintAssets()
            val assembler = OverworldTownAssembler(assets)
            val site = OverworldSite(OverworldSiteIds.LUMINA_PORT, 0, 63, 0, 3, "BEACH")
            val planning = assembler.planning(world, site, -4167603268941691095L)
            while (planning.steps.hasNext()) planning.steps.next()
            val plan = planning.result()
            val repeat = assembler.planning(world, site, -4167603268941691095L)
            while (repeat.steps.hasNext()) repeat.steps.next()
            assertEquals(plan.buildings, repeat.result().buildings, "Coastal Lumina planning must be deterministic")
            val houses = plan.buildings.filter { it.name.startsWith("house_") }
            val distinct = houses.map { it.template }.toSet().size
            assertTrue(houses.size >= 10, "Expected >= 10 houses on half-water coastal Lumina, got ${houses.size}")
            assertTrue(distinct >= 5, "Expected >= 5 distinct house templates, got $distinct")
        } finally {
            MockBukkit.unmock()
        }
    }

    /** 同梱27スキーマチックの実寸フットプリントを保ちつつ、MockBukkitで全4方向の回転検証ができる単純パレットへ変換します。 */
    private fun realFootprintAssets(): OverworldAssets = OverworldAssets.load { path ->
        val real = SchematicResources.load(path) ?: return@load null
        val height = minOf(3, real.height)
        SchematicData(real.width, height, real.length, listOf("minecraft:stone"), IntArray(real.width * height * real.length))
    }
}
