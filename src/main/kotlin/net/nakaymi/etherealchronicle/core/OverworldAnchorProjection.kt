package net.nakaymi.etherealchronicle.core

import kotlin.math.floor

/** ワールド運用モードの設定値と種別を一元管理します。 */
enum class WorldMode(val configValue: String) {
    OVERWORLD("overworld"),
    ;

    companion object {
        private const val LEGACY_DEDICATED = "dedicated_ec_world"

        /** config.yml の `world.mode` を検証してモードへ変換します。 */
        fun fromConfig(value: String?): WorldMode {
            val normalized = value ?: OVERWORLD.configValue
            // 廃止した浮島モードの旧設定値は overworld へ読み替えます。
            if (normalized == LEGACY_DEDICATED) return OVERWORLD
            return entries.firstOrNull { it.configValue == normalized }
                ?: throw IllegalArgumentException("Unknown world.mode: $normalized")
        }
    }
}

/** 拠点の建設状態。READY 以外はゲームプレイに公開しません。 */
enum class OverworldSiteStatus { PENDING, GENERATING, READY, FAILED }

/** オーバーワールド修復コマンドの受付結果です。 */
enum class OverworldRepairResult { STARTED, UNAVAILABLE }

/** 手動片付け確認コマンドの受付結果です。 */
enum class OverworldCleanupAckResult { ACKNOWLEDGING, UNAVAILABLE }

/** オーバーワールドに確定した拠点と任意の入口座標です。 */
data class OverworldSiteAnchor(
    val areaId: String,
    val centerX: Int,
    val surfaceY: Int,
    val centerZ: Int,
    val biomeKey: String,
    val status: OverworldSiteStatus,
    val dungeonId: String? = null,
    val portalCenterX: Int? = null,
    val portalCenterY: Int? = null,
    val portalCenterZ: Int? = null,
    val portalExitX: Int? = null,
    val portalExitY: Int? = null,
    val portalExitZ: Int? = null,
    val plazaSpawnX: Int? = null,
    val plazaSpawnY: Int? = null,
    val plazaSpawnZ: Int? = null,
    val fishingWaterX: Int? = null,
    val fishingWaterY: Int? = null,
    val fishingWaterZ: Int? = null,
)

/** 入場検知に使う入口中心と、地上への帰還先です。 */
data class OverworldDungeonPortal(
    val dungeonId: String,
    val areaId: String,
    val centerX: Int,
    val centerY: Int,
    val centerZ: Int,
    val exitX: Int,
    val exitY: Int,
    val exitZ: Int,
    val minX: Int = centerX,
    val minY: Int = centerY,
    val minZ: Int = centerZ,
    val maxX: Int = centerX,
    val maxY: Int = centerY,
    val maxZ: Int = centerZ,
)

/** 建設実装とゲームプレイを分離する読み取り・復旧ポートです。 */
interface OverworldSiteRegistry {
    fun activeWorldName(): String
    fun isOverworldMode(): Boolean
    fun isReady(): Boolean
    fun siteForArea(areaId: String): OverworldSiteAnchor?
    fun allSites(): List<OverworldSiteAnchor>
    fun portalForDungeon(dungeonId: String): OverworldDungeonPortal?
    fun allPortals(): List<OverworldDungeonPortal>
    fun triggerRepair(): OverworldRepairResult

    /** 管理者が現地の手動片付けを確認した後だけ、該当拠点の保留状態を解除します。 */
    fun acknowledgeCleanup(areaId: String): OverworldCleanupAckResult = OverworldCleanupAckResult.UNAVAILABLE
}

/** 建設サービス未登録時は未完成として扱い、推測した座標への配置を防ぎます。 */
class PendingOverworldSiteRegistry(
    private val worldName: String,
    areas: Collection<Area>,
) : OverworldSiteRegistry {
    private val sites = areas.associate { area ->
        area.id to OverworldSiteAnchor(area.id, 0, 0, 0, "", OverworldSiteStatus.PENDING)
    }
    private val siteList = sites.values.toList()

    override fun activeWorldName(): String = worldName
    override fun isOverworldMode(): Boolean = true
    override fun isReady(): Boolean = false
    override fun siteForArea(areaId: String): OverworldSiteAnchor? = sites[areaId]
    override fun allSites(): List<OverworldSiteAnchor> = siteList
    override fun portalForDungeon(dungeonId: String): OverworldDungeonPortal? = null
    override fun allPortals(): List<OverworldDungeonPortal> = emptyList()
    override fun triggerRepair(): OverworldRepairResult = OverworldRepairResult.UNAVAILABLE
}

/** ワールド切替と投影機能を一つの依存としてコントローラーへ渡します。 */
class ActiveWorldProjection(
    val registry: OverworldSiteRegistry,
    areas: Collection<Area>,
    surfaceYResolver: (Int, Int) -> Int,
) {
    val projector = OverworldAnchorProjector(areas, registry, surfaceYResolver)

    /** 現在のゲームプレイ対象ワールド名を返します。 */
    fun worldName(): String = registry.activeWorldName()
}

/** YAML の基準エリアから相対座標を拠点へ移し、地表高さに合わせます。 */
class OverworldAnchorProjector(
    areas: Collection<Area>,
    private val registry: OverworldSiteRegistry,
    private val surfaceYResolver: (Int, Int) -> Int,
) {
    private val areas = areas.associateBy { it.id }
    private val areasBySourceWorld = areas.groupBy { it.world }
    private var cachedSitesRef: List<OverworldSiteAnchor>? = null
    private var cachedSitesSnapshot: List<OverworldSiteAnchor>? = null
    private var cachedProjectedAreas: List<Area> = emptyList()
    private var cachedProjectedAreaById: Map<String, Area> = emptyMap()
    private val horizontalCache = mutableMapOf<Pair<Position, String?>, Position?>()

    /** アンカー一覧の変化を検知したときだけ水平投影・エリア投影キャッシュを更新します。 */
    private fun ensureCacheFresh() {
        if (!registry.isOverworldMode()) {
            if (cachedSitesSnapshot == null) {
                val list = areas.values.toList()
                cachedProjectedAreas = list
                cachedProjectedAreaById = areas
                cachedSitesSnapshot = emptyList()
            }
            return
        }
        val currentSites = registry.allSites()
        if (cachedSitesRef === currentSites) return
        if (cachedSitesSnapshot == currentSites) {
            cachedSitesRef = currentSites
            return
        }
        val projected = areas.values.mapNotNull(::computeProjectedArea)
        cachedProjectedAreas = projected
        cachedProjectedAreaById = projected.associateBy { it.id }
        horizontalCache.clear()
        cachedSitesRef = currentSites
        cachedSitesSnapshot = currentSites.toList()
    }

    /** 高さの取得前に水平座標だけ求め、呼び出し側がチャンク状態を検査できるようにします。 */
    fun projectHorizontal(position: Position, areaId: String): Position? {
        if (!registry.isOverworldMode()) return position
        ensureCacheFresh()
        val key = position to areaId
        if (key in horizontalCache) return horizontalCache[key]
        val computed = computeHorizontal(position, areaId)
        horizontalCache[key] = computed
        return computed
    }

    private fun computeHorizontal(position: Position, areaId: String): Position? {
        val area = areas[areaId] ?: return null
        val site = registry.siteForArea(areaId)?.takeIf { it.status == OverworldSiteStatus.READY } ?: return null
        if (position.world != area.world) return null
        val x = site.centerX + position.x - (area.minX + area.maxX) / 2.0
        val z = site.centerZ + position.z - (area.minZ + area.maxZ) / 2.0
        return Position(registry.activeWorldName(), x, position.y, z, position.yaw)
    }

    /** 明示されたエリアに属する位置を地表へ投影します。 */
    fun project(position: Position, areaId: String): Position? {
        val horizontal = projectHorizontal(position, areaId) ?: return null
        if (!registry.isOverworldMode()) return horizontal
        return horizontal.copy(y = surfaceYResolver(floor(horizontal.x).toInt(), floor(horizontal.z).toInt()).toDouble())
    }

    /** エリア指定のない定義は基準範囲内を優先し、範囲外なら最寄り中心へ割り当てます。 */
    fun projectHorizontal(position: Position): Position? {
        if (!registry.isOverworldMode()) return position
        ensureCacheFresh()
        val key = position to null
        if (key in horizontalCache) return horizontalCache[key]
        val area = resolveSourceArea(position) ?: run {
            horizontalCache[key] = null
            return null
        }
        val computed = computeHorizontal(position, area.id)
        horizontalCache[key] = computed
        return computed
    }

    /** エリア未指定の基準座標が属するエリア ID を解決します。 */
    fun sourceAreaId(position: Position): String? = resolveSourceArea(position)?.id

    private fun resolveSourceArea(position: Position): Area? {
        val candidates = areasBySourceWorld[position.world].orEmpty()
        return candidates.firstOrNull { position.x in it.minX..it.maxX && position.z in it.minZ..it.maxZ }
            ?: candidates.minByOrNull { distanceSquared(position.x, position.z, (it.minX + it.maxX) / 2.0, (it.minZ + it.maxZ) / 2.0) }
    }

    /** エリアが明示されていない座標も地表へ投影します。 */
    fun project(position: Position): Position? {
        val horizontal = projectHorizontal(position) ?: return null
        if (!registry.isOverworldMode()) return horizontal
        return horizontal.copy(y = surfaceYResolver(floor(horizontal.x).toInt(), floor(horizontal.z).toInt()).toDouble())
    }

    /** NPC の会話定義を保ったまま配置座標を投影します。 */
    fun projectNpc(npc: NpcDefinition): NpcDefinition? = project(npc.position)?.let { npc.copy(position = it) }

    /** 敵スポナーの出現条件を保ったまま位置を投影します。 */
    fun projectSpawn(spawn: SpawnPoint): SpawnPoint? = project(spawn.position)?.let { spawn.copy(position = it) }

    /** Overworld の既定釣り場に建設済み池アンカーがある場合は、その水面の中心座標を返します。 */
    fun fishingWaterCenter(spot: FishingSpotDefinition): Position? {
        if (!registry.isOverworldMode() || !spot.defaultForArea) return null
        val area = areas[spot.area] ?: return null
        if (spot.world != area.world || spot.center.world != area.world) return null
        val site = registry.siteForArea(spot.area)?.takeIf { it.status == OverworldSiteStatus.READY } ?: return null
        val x = site.fishingWaterX ?: return null
        val y = site.fishingWaterY ?: return null
        val z = site.fishingWaterZ ?: return null
        return Position(registry.activeWorldName(), x + 0.5, y.toDouble(), z + 0.5, spot.center.yaw)
    }

    /** 釣り場のエリアを使い、ワールド名と中心を同時に更新します。 */
    fun projectFishingSpot(spot: FishingSpotDefinition): FishingSpotDefinition? =
        (fishingWaterCenter(spot) ?: project(spot.center, spot.area))
            ?.let { spot.copy(world = it.world, center = it) }

    /** 採取地点の識別子を保ち、完成した拠点だけを配置します。 */
    fun projectGatheringLocation(location: GatheringLocation): GatheringLocation? =
        project(Position(location.world, location.x.toDouble(), location.y.toDouble(), location.z.toDouble()), location.area)
            ?.let { location.copy(world = it.world, x = floor(it.x).toInt(), y = floor(it.y).toInt(), z = floor(it.z).toInt()) }

    /** 個人宝箱の報酬定義を変えずに表示位置だけ移動します。 */
    fun projectTreasure(chest: TreasureChestDefinition): TreasureChestDefinition? =
        project(chest.position)?.let { chest.copy(position = it) }

    /** エリア境界を平行移動し、高さを拠点の地表差だけ移動します。 */
    fun projectArea(area: Area): Area? {
        if (!registry.isOverworldMode()) return area
        ensureCacheFresh()
        return cachedProjectedAreaById[area.id] ?: computeProjectedArea(area)
    }

    private fun computeProjectedArea(area: Area): Area? {
        val site = registry.siteForArea(area.id)?.takeIf { it.status == OverworldSiteStatus.READY } ?: return null
        val dx = site.centerX - (area.minX + area.maxX) / 2.0
        val dz = site.centerZ - (area.minZ + area.maxZ) / 2.0
        val dy = site.surfaceY - BASE_SURFACE_Y
        return area.copy(
            world = registry.activeWorldName(),
            minX = area.minX + dx,
            maxX = area.maxX + dx,
            minY = area.minY + dy,
            maxY = area.maxY + dy,
            minZ = area.minZ + dz,
            maxZ = area.maxZ + dz,
        )
    }

    /** 指定地点が実際に含まれる投影済みエリアだけを返します。 */
    fun areasAt(position: Position): List<Area> {
        ensureCacheFresh()
        return cachedProjectedAreas.filter { it.contains(position) }
    }

    /** 所属エリアがなければ、同じワールドの完成済み拠点から最寄りを返します。 */
    fun nearestArea(position: Position): Area? {
        ensureCacheFresh()
        return cachedProjectedAreas.firstOrNull { it.contains(position) }
            ?: cachedProjectedAreas.asSequence()
                .filter { it.world == position.world }
                .minByOrNull { distanceSquared(position.x, position.z, (it.minX + it.maxX) / 2.0, (it.minZ + it.maxZ) / 2.0) }
    }

    private fun distanceSquared(x: Double, z: Double, cx: Double, cz: Double): Double =
        (x - cx) * (x - cx) + (z - cz) * (z - cz)

    companion object {
        /** ec_world の基準地表高さ（DemoWorldLayout.SURFACE_Y と同じ 65）。 */
        const val BASE_SURFACE_Y = 65
    }
}
