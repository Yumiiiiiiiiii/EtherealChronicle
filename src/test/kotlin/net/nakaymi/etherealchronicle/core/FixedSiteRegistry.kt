package net.nakaymi.etherealchronicle.core

/**
 * テスト専用: エリア中心をそのままアンカーにする READY 固定レジストリです（投影は恒等変換）。
 * ダンジョン入口は所属エリア中心の東隣（出口は中心）に置き、広場スポーンも中心に置きます。
 */
class FixedSiteRegistry(
    private val worldName: String = WorldIds.DEMO,
    areas: Collection<Area> = emptyList(),
    dungeons: Collection<DungeonDefinition> = emptyList(),
) : OverworldSiteRegistry {
    private val dungeonsByArea = dungeons.groupBy { it.area }
    private val sites = areas.associate { area ->
        val x = ((area.minX + area.maxX) / 2).toInt()
        val z = ((area.minZ + area.maxZ) / 2).toInt()
        val y = OverworldAnchorProjector.BASE_SURFACE_Y
        val dungeon = dungeonsByArea[area.id]?.first()
        area.id to OverworldSiteAnchor(
            area.id, x, y, z, "", OverworldSiteStatus.READY,
            dungeonId = dungeon?.id,
            portalCenterX = dungeon?.let { x + PORTAL_OFFSET }, portalCenterY = dungeon?.let { y + 1 }, portalCenterZ = dungeon?.let { z },
            portalExitX = dungeon?.let { x }, portalExitY = dungeon?.let { y }, portalExitZ = dungeon?.let { z },
            plazaSpawnX = x, plazaSpawnY = y, plazaSpawnZ = z,
        )
    }
    private val siteList = sites.values.toList()
    /** 同じエリアに複数のダンジョンがあっても、それぞれ別の入口を東側へずらして持ちます。 */
    private val portals = dungeonsByArea.flatMap { (areaId, list) ->
        val site = sites[areaId] ?: return@flatMap emptyList()
        list.mapIndexed { index, dungeon ->
            OverworldDungeonPortal(dungeon.id, areaId, site.centerX + PORTAL_OFFSET + index * 2, site.surfaceY + 1, site.centerZ, site.centerX, site.surfaceY, site.centerZ)
        }
    }

    override fun activeWorldName(): String = worldName
    override fun isReady(): Boolean = true
    override fun siteForArea(areaId: String): OverworldSiteAnchor? = sites[areaId]
    override fun allSites(): List<OverworldSiteAnchor> = siteList
    override fun portalForDungeon(dungeonId: String): OverworldDungeonPortal? = portals.firstOrNull { it.dungeonId == dungeonId }
    override fun allPortals(): List<OverworldDungeonPortal> = portals
    override fun triggerRepair(): OverworldRepairResult = OverworldRepairResult.UNAVAILABLE

    private companion object { const val PORTAL_OFFSET = 4 }
}
