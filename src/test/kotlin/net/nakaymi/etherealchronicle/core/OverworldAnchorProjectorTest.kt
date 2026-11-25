package net.nakaymi.etherealchronicle.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** オーバーワールド拠点投影・キャッシュ更新・非READY除外・ワールドモード変換を検証します。 */
class OverworldAnchorProjectorTest {
    @Test
    fun `world mode parser maps valid strings, default null, and rejects unknown values`() {
        assertEquals(WorldMode.OVERWORLD, WorldMode.fromConfig("overworld"))
        assertEquals(WorldMode.OVERWORLD, WorldMode.fromConfig("dedicated_ec_world"))  // 旧設定値の読み替え
        assertEquals(WorldMode.OVERWORLD, WorldMode.fromConfig(null))
        assertThrows(IllegalArgumentException::class.java) { WorldMode.fromConfig("invalid_mode") }
    }

    @Test
    fun `projector maps explicit and inferred area positions, excludes non-READY sites, and shifts area bounds`() {
        val lumina = Area("ec:area_lumina_port", "Lumina", WorldIds.DEMO, -20.0, 20.0, 50.0, 90.0, -20.0, 20.0, safe = true)
        val forest = Area("ec:area_ashwood_forest", "Ashwood", WorldIds.DEMO, 80.0, 140.0, 50.0, 90.0, -30.0, 30.0, safe = false)
        val mine = Area("ec:area_weeping_mine", "Mine", WorldIds.DEMO, 200.0, 260.0, 50.0, 90.0, -30.0, 30.0, safe = false)
        val registry = MutableOverworldRegistry(
            worldName = "world",
            sites = listOf(
                OverworldSiteAnchor(lumina.id, centerX = 1000, surfaceY = 72, centerZ = -500, biomeKey = "PLAINS", status = OverworldSiteStatus.READY),
                OverworldSiteAnchor(forest.id, centerX = 1400, surfaceY = 80, centerZ = -400, biomeKey = "FOREST", status = OverworldSiteStatus.PENDING),
                OverworldSiteAnchor(mine.id, centerX = 1800, surfaceY = 60, centerZ = -300, biomeKey = "STONY_PEAKS", status = OverworldSiteStatus.FAILED),
            ),
        )
        var surfaceCalls = 0
        val projector = OverworldAnchorProjector(listOf(lumina, forest, mine), registry) { x, z ->
            surfaceCalls++
            if (x == 1005 && z == -496) 74 else 70
        }

        // 水平投影は surfaceYResolver を呼ばない
        val horizontal = projector.projectHorizontal(Position(WorldIds.DEMO, 5.5, 65.0, 4.25, 90f), lumina.id)
        assertEquals(Position("world", 1005.5, 65.0, -495.75, 90f), horizontal)
        assertEquals(0, surfaceCalls)

        // 地表投影は floor(x), floor(z) で地表高さを解決
        val projected = projector.project(Position(WorldIds.DEMO, 5.5, 65.0, 4.25, 90f), lumina.id)
        assertEquals(Position("world", 1005.5, 74.0, -495.75, 90f), projected)
        assertEquals(1, surfaceCalls)

        // PENDING / FAILED は null で除外される
        assertNull(projector.projectHorizontal(Position(WorldIds.DEMO, 110.0, 65.0, 0.0), forest.id))
        assertNull(projector.project(Position(WorldIds.DEMO, 230.0, 65.0, 0.0), mine.id))
        assertNull(projector.projectArea(forest))
        assertNull(projector.projectArea(mine))

        // ソースワールド不一致は null
        assertNull(projector.projectHorizontal(Position("other_world", 0.0, 65.0, 0.0), lumina.id))

        // エリア未指定の座標は基準範囲内なら該当エリア、範囲外なら最寄り中心へ解決される
        assertEquals(lumina.id, projector.sourceAreaId(Position(WorldIds.DEMO, 10.0, 65.0, -5.0)))
        assertEquals(lumina.id, projector.sourceAreaId(Position(WorldIds.DEMO, 25.0, 65.0, 0.0)))
        assertEquals(forest.id, projector.sourceAreaId(Position(WorldIds.DEMO, 75.0, 65.0, 0.0)))
        assertNull(projector.projectHorizontal(Position(WorldIds.DEMO, 75.0, 65.0, 0.0))) // 最寄りの forest は PENDING

        // projectArea は center と surfaceY - BASE_SURFACE_Y の差分で平行移動される
        val projectedLumina = projector.projectArea(lumina)!!
        assertEquals("world", projectedLumina.world)
        assertEquals(980.0, projectedLumina.minX)
        assertEquals(1020.0, projectedLumina.maxX)
        assertEquals(57.0, projectedLumina.minY) // 50 + (72 - 65)
        assertEquals(97.0, projectedLumina.maxY) // 90 + (72 - 65)
        assertEquals(-520.0, projectedLumina.minZ)
        assertEquals(-480.0, projectedLumina.maxZ)

        // areasAt と nearestArea
        assertEquals(listOf(projectedLumina), projector.areasAt(Position("world", 1000.0, 72.0, -500.0)))
        assertEquals(emptyList<Area>(), projector.areasAt(Position("world", 1200.0, 72.0, -500.0)))
        assertEquals(projectedLumina, projector.nearestArea(Position("world", 1200.0, 72.0, -500.0)))
        assertNull(projector.nearestArea(Position("other_world", 1000.0, 72.0, -500.0)))
    }

    @Test
    fun `projector invalidates caches when a site transitions from PENDING to READY`() {
        val lumina = Area("ec:area_lumina_port", "Lumina", WorldIds.DEMO, -20.0, 20.0, 50.0, 90.0, -20.0, 20.0, safe = true)
        val forest = Area("ec:area_ashwood_forest", "Ashwood", WorldIds.DEMO, 80.0, 140.0, 50.0, 90.0, -30.0, 30.0, safe = false)
        val registry = MutableOverworldRegistry(
            worldName = "world",
            sites = listOf(
                OverworldSiteAnchor(lumina.id, 100, 65, 100, "PLAINS", OverworldSiteStatus.READY),
                OverworldSiteAnchor(forest.id, 0, 0, 0, "", OverworldSiteStatus.PENDING),
            ),
        )
        val projector = OverworldAnchorProjector(listOf(lumina, forest), registry) { _, _ -> 68 }

        assertNull(projector.projectHorizontal(Position(WorldIds.DEMO, 110.0, 65.0, 0.0), forest.id))
        assertEquals(lumina.id, projector.nearestArea(Position("world", 500.0, 68.0, 500.0))?.id)

        // forest が READY に遷移したらキャッシュが自動更新される
        registry.update(
            listOf(
                OverworldSiteAnchor(lumina.id, 100, 65, 100, "PLAINS", OverworldSiteStatus.READY),
                OverworldSiteAnchor(forest.id, 500, 70, 500, "FOREST", OverworldSiteStatus.READY),
            ),
        )

        val projectedForestPos = projector.projectHorizontal(Position(WorldIds.DEMO, 110.0, 65.0, 0.0), forest.id)
        assertEquals(Position("world", 500.0, 65.0, 500.0), projectedForestPos)
        assertEquals(forest.id, projector.nearestArea(Position("world", 500.0, 70.0, 500.0))?.id)
    }

    @Test
    fun `domain entity projection helpers preserve metadata and dedicated mode is identity`() {
        val lumina = Area("ec:area_lumina_port", "Lumina", WorldIds.DEMO, -20.0, 20.0, 50.0, 90.0, -20.0, 20.0, safe = true)
        val registry = MutableOverworldRegistry(
            worldName = "world",
            sites = listOf(OverworldSiteAnchor(lumina.id, 300, 70, -200, "PLAINS", OverworldSiteStatus.READY)),
        )
        val projector = OverworldAnchorProjector(listOf(lumina), registry) { _, _ -> 71 }

        val npc = NpcDefinition("ec:npc_test", "Guide", Position(WorldIds.DEMO, 2.0, 65.0, -3.0, 45f), "ec:dlg", "ec:shop")
        val spawn = SpawnPoint("ec:sp_test", Position(WorldIds.DEMO, -4.0, 65.0, 6.0), cap = 3, respawnTicks = 100)
        val spot = FishingSpotDefinition("ec:fish", "Pier", lumina.id, WorldIds.DEMO, Position(WorldIds.DEMO, 1.0, 65.0, 2.0), 8.0, true, setOf("port"))
        val gather = GatheringLocation("ec:g1", lumina.id, WorldIds.DEMO, 3, 65, -4)
        val chest = TreasureChestDefinition("ec:chest", Position(WorldIds.DEMO, 5.0, 65.0, 5.0), "ec:flag", mapOf("ec:potion" to 1), 100L, "HAPPY_VILLAGER")

        assertEquals(Position("world", 302.0, 71.0, -203.0, 45f), projector.projectNpc(npc)?.position)
        assertEquals(Position("world", 296.0, 71.0, -194.0), projector.projectSpawn(spawn)?.position)
        val projectedSpot = projector.projectFishingSpot(spot)!!
        assertEquals("world", projectedSpot.world)
        assertEquals(Position("world", 301.0, 71.0, -198.0), projectedSpot.center)
        assertEquals(GatheringLocation("ec:g1", lumina.id, "world", 303, 71, -204), projector.projectGatheringLocation(gather))
        assertEquals(Position("world", 305.0, 71.0, -195.0), projector.projectTreasure(chest)?.position)

        val dedicated = OverworldAnchorProjector(listOf(lumina), FixedSiteRegistry(WorldIds.DEMO, listOf(lumina))) { _, _ -> 99 }
        assertEquals(npc, dedicated.projectNpc(npc))
        assertEquals(lumina, dedicated.projectArea(lumina))
    }

    @Test
    fun `fishingWaterCenter prefers constructed pond anchor for default READY spots and falls back otherwise`() {
        val lumina = Area("ec:area_lumina_port", "Lumina", WorldIds.DEMO, -20.0, 20.0, 50.0, 90.0, -20.0, 20.0, safe = true)
        val forest = Area("ec:area_ashwood_forest", "Ashwood", WorldIds.DEMO, 80.0, 140.0, 50.0, 90.0, -30.0, 30.0, safe = false)
        val registry = MutableOverworldRegistry(
            worldName = "world",
            sites = listOf(
                OverworldSiteAnchor(
                    areaId = lumina.id,
                    centerX = 300,
                    surfaceY = 70,
                    centerZ = -200,
                    biomeKey = "SNOWY_BEACH",
                    status = OverworldSiteStatus.READY,
                    fishingWaterX = 318,
                    fishingWaterY = 63,
                    fishingWaterZ = -182,
                ),
                OverworldSiteAnchor(
                    areaId = forest.id,
                    centerX = 500,
                    surfaceY = 72,
                    centerZ = 100,
                    biomeKey = "FOREST",
                    status = OverworldSiteStatus.PENDING,
                    fishingWaterX = 512,
                    fishingWaterY = 71,
                    fishingWaterZ = 112,
                ),
            ),
        )
        var surfaceCalls = 0
        val projector = OverworldAnchorProjector(listOf(lumina, forest), registry) { _, _ ->
            surfaceCalls++
            71
        }

        val defaultSpot = FishingSpotDefinition(
            "ec:spot_lumina_pier", "Pier", lumina.id, WorldIds.DEMO,
            Position(WorldIds.DEMO, 10.0, 64.0, 28.0), 14.0, true, setOf("PORT"),
        )
        val hiddenSpot = FishingSpotDefinition(
            "ec:spot_hidden", "Hidden", lumina.id, WorldIds.DEMO,
            Position(WorldIds.DEMO, 1.0, 65.0, 2.0), 4.0, false, setOf("HIDDEN"),
        )
        val pendingSpot = FishingSpotDefinition(
            "ec:spot_ashwood_stream", "Stream", forest.id, WorldIds.DEMO,
            Position(WorldIds.DEMO, 92.0, 64.0, 30.0), 14.0, true, setOf("FOREST"),
        )
        val mismatchedWorldSpot = defaultSpot.copy(world = "other_world", center = defaultSpot.center.copy(world = "other_world"))

        // 既定釣り場は fishing_water を優先し、surfaceYResolver を呼ばない
        val projectedDefault = projector.projectFishingSpot(defaultSpot)!!
        assertEquals("world", projectedDefault.world)
        assertEquals(Position("world", 318.5, 63.0, -181.5), projectedDefault.center)
        assertEquals(0, surfaceCalls)

        // defaultForArea = false の隠し釣り場は相対投影を維持する
        assertNull(projector.fishingWaterCenter(hiddenSpot))
        val projectedHidden = projector.projectFishingSpot(hiddenSpot)!!
        assertEquals(Position("world", 301.0, 71.0, -198.0), projectedHidden.center)
        assertEquals(1, surfaceCalls)

        // PENDING 拠点やワールド不一致では返さない
        assertNull(projector.fishingWaterCenter(pendingSpot))
        assertNull(projector.projectFishingSpot(pendingSpot))
        assertNull(projector.fishingWaterCenter(mismatchedWorldSpot))

        // dedicated_ec_world モードでは従来どおり無変換
        val dedicated = OverworldAnchorProjector(listOf(lumina), FixedSiteRegistry(WorldIds.DEMO, listOf(lumina))) { _, _ -> 99 }
        assertNull(dedicated.fishingWaterCenter(defaultSpot))
        assertEquals(defaultSpot, dedicated.projectFishingSpot(defaultSpot))
    }

    private class MutableOverworldRegistry(
        private val worldName: String,
        sites: List<OverworldSiteAnchor>,
    ) : OverworldSiteRegistry {
        private var currentList: List<OverworldSiteAnchor> = sites.toList()
        private var byArea: Map<String, OverworldSiteAnchor> = currentList.associateBy { it.areaId }

        fun update(sites: List<OverworldSiteAnchor>) {
            currentList = sites.toList()
            byArea = currentList.associateBy { it.areaId }
        }

        override fun activeWorldName(): String = worldName
        override fun isOverworldMode(): Boolean = true
        override fun isReady(): Boolean = currentList.all { it.status == OverworldSiteStatus.READY }
        override fun siteForArea(areaId: String): OverworldSiteAnchor? = byArea[areaId]
        override fun allSites(): List<OverworldSiteAnchor> = currentList
        override fun portalForDungeon(dungeonId: String): OverworldDungeonPortal? = null
        override fun allPortals(): List<OverworldDungeonPortal> = emptyList()
        override fun triggerRepair(): OverworldRepairResult = OverworldRepairResult.UNAVAILABLE
    }
}
