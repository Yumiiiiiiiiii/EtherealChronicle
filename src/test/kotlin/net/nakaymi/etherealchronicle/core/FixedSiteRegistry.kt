package net.nakaymi.etherealchronicle.core

/** テスト専用: ec_world 座標をそのまま READY 拠点として返す固定レジストリです。 */
class FixedSiteRegistry(
    private val worldName: String = WorldIds.DEMO,
    areas: Collection<Area> = emptyList(),
) : OverworldSiteRegistry {
    private val sites = areas.associate { area ->
        area.id to OverworldSiteAnchor(
            area.id,
            ((area.minX + area.maxX) / 2).toInt(),
            OverworldAnchorProjector.BASE_SURFACE_Y,
            ((area.minZ + area.maxZ) / 2).toInt(),
            "",
            OverworldSiteStatus.READY,
        )
    }
    private val siteList = sites.values.toList()

    override fun activeWorldName(): String = worldName
    override fun isOverworldMode(): Boolean = false
    override fun isReady(): Boolean = true
    override fun siteForArea(areaId: String): OverworldSiteAnchor? = sites[areaId]
    override fun allSites(): List<OverworldSiteAnchor> = siteList
    override fun portalForDungeon(dungeonId: String): OverworldDungeonPortal? = null
    override fun allPortals(): List<OverworldDungeonPortal> = emptyList()
    override fun triggerRepair(): OverworldRepairResult = OverworldRepairResult.UNAVAILABLE
}
