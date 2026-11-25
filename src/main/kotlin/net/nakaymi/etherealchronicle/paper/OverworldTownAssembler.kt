package net.nakaymi.etherealchronicle.paper

import net.nakaymi.etherealchronicle.core.OverworldSite
import net.nakaymi.etherealchronicle.core.OverworldSiteIds
import net.nakaymi.etherealchronicle.core.OverworldDungeonIds
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.World
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Directional
import org.bukkit.block.structure.StructureRotation
import java.util.Random
import kotlin.math.abs

/** 個別スキーマチックの読込済みデータを保持します。 */
internal class OverworldAssets private constructor(private val data: Map<String, SchematicData>) {
    operator fun get(name: String): SchematicData = data.getValue(name)
    fun names(): Set<String> = data.keys

    /** 全パレットをメインスレッドで検証し、無効なアセットによる途中失敗を防ぎます。 */
    fun validatePalettes() {
        data.forEach { (name, schematic) ->
            val palette = schematic.palette.map { state ->
                runCatching { Bukkit.createBlockData(state).material }.getOrElse { error("Invalid block state in overworld schematic: $name ($state)") }
            }
            require((0 until schematic.length).any { z -> (0 until schematic.width).any { x ->
                val material = palette[schematic.paletteIndexAt(z * schematic.width + x)]
                material.isSolid && material != Material.STRUCTURE_VOID
            } }) { "No solid foundation in overworld schematic: $name" }
        }
    }

    companion object {
        private val categories = mapOf(
            "houses" to listOf("house_timber_small_a", "house_timber_small_b", "house_coastal_cottage", "house_l_shaped_manor", "house_stone_bakery", "house_alchemist_tower", "house_snow_cabin_a", "house_snow_longhouse"),
            "civic" to listOf("civic_guild_hall", "civic_blacksmith_forge", "civic_tavern_inn", "civic_lighthouse", "civic_market_plaza", "civic_harbor_pier", "civic_frost_command_hall", "civic_watchtower"),
            "props" to listOf("prop_well", "prop_stall_a", "prop_stall_b", "prop_waystone"),
            "landmarks" to listOf("landmark_ashwood_camp", "landmark_mine_headframe", "landmark_stargazer_observatory"),
            "entrances" to listOf("entrance_novice_arena", "entrance_void_sanctum", "entrance_aether_crucible", "entrance_glacial_citadel"),
        )

        /** 全27ファイルをワールド変更前に検証します。 */
        fun load(loader: (String) -> SchematicData? = SchematicResources::load): OverworldAssets {
            val all = linkedMapOf<String, SchematicData>()
            categories.forEach { (category, names) -> names.forEach { name ->
                val path = "schematics/overworld/$category/$name.schem"
                val schematic = loader(path) ?: error("Missing required overworld schematic: $path")
                require(schematic.width <= 64 && schematic.length <= 64 && schematic.height <= 64) { "Oversized overworld schematic: $path" }
                all[name] = schematic
            } }
            return OverworldAssets(all)
        }
    }
}

/** 配置先と回転を固定した建物です。 */
internal data class OverworldBuildingPlan(
    val name: String, val template: String, val origin: OverworldPoint, val rotation: StructureRotation,
    val swaps: Map<Material, Material>, val snow: Boolean,
)

/** 事前に検査した一つの地形変更です。 */
internal data class OverworldTerrainChange(val position: OverworldPoint, val before: Material, val after: Material, val facing: BlockFace? = null)

/** 変更前に確定した建物・街路と公開アンカーです。 */
internal data class OverworldTownPlan(
    val site: OverworldSite,
    val buildings: List<OverworldBuildingPlan>,
    val road: List<OverworldTerrainChange>,
    val roadClearance: List<OverworldTerrainChange>,
    val pond: List<OverworldTerrainChange>,
    val lights: List<OverworldTerrainChange>,
    val decorations: List<OverworldTerrainChange>,
    val anchors: Map<String, OverworldPoint>,
    val portals: List<OverworldPortal>,
    val prepared: Map<String, Iterator<() -> Unit>>,
)

/** tickごとに進める敷地探索と、完了後の結果取得をまとめます。 */
internal class OverworldTownPlanning(val steps: Iterator<Unit>, val result: () -> OverworldTownPlan)

/** シードによる建物選択と回転を、現地地形の事前検証後に適用します。 */
internal class OverworldTownAssembler(private val assets: OverworldAssets) {
    private val surfaceCache = HashMap<Long, Int>()

    /** 同一拠点の高さ照会を座標ごとに一度に抑えます。 */
    private fun surfaceY(world: World, x: Int, z: Int): Int = surfaceCache.getOrPut(columnKey(x, z)) {
        OverworldTerrain.surfaceY(world, x, z)
    }

    private fun columnKey(x: Int, z: Int): Long = (x.toLong() shl 32) xor (z.toLong() and 0xffffffffL)

    private data class Request(
        val name: String,
        val template: String,
        val required: Boolean = true,
        val fallbacks: List<String> = emptyList(),
    )
    private data class Rect(val minX: Int, val maxX: Int, val minZ: Int, val maxZ: Int) {
        fun overlaps(other: Rect): Boolean = minX <= other.maxX && maxX >= other.minX && minZ <= other.maxZ && maxZ >= other.minZ
    }
    private class RejectionStats {
        var outOfBounds = 0
        var overlap = 0
        var road = 0
        var water = 0
        var terrain = 0
        var portal = 0
        var lastFailure: String? = null
        fun summary(): String = "bounds=$outOfBounds overlap=$overlap road=$road water=$water terrain=$terrain portal=$portal" +
            (lastFailure?.let { " last='$it'" } ?: "")
    }

    /** 全必須建物の敷地を確定し、書き込み前に基礎と敷地衝突を検証します。 */
    fun planning(world: World, site: OverworldSite, seed: Long): OverworldTownPlanning {
        // 同じ assembler を再利用しても、前の敷地や変更前の地形の高さを持ち越しません。
        surfaceCache.clear()
        var result: OverworldTownPlan? = null
        val steps = sequence {
            val random = Random(seed xor site.id.hashCode().toLong())
            val requests = requests(site.id, random)
            val snow = site.id in OverworldSiteIds.cold
            val roadPoints = linkedSetOf<OverworldPoint>()
            for (point in roads(world, site, random)) {
                roadPoints += point
                yield(Unit)
            }
            val road = roadPoints.toList()
            val selected = planBuildings(world, site, seed, random, snow, road, requests)
            val (occupied, buildings, anchors, portals, prepared) = selected
            val clearRoad = connectRoads(world, road, occupied, buildings, anchors)
            val clearRoadColumns = clearRoad.mapTo(HashSet()) { columnKey(it.x, it.z) }
            val pier = buildings.firstOrNull { it.name == "pier" }
            var pool: Pair<OverworldPoint, List<OverworldTerrainChange>>? = null
            if (site.id in OverworldSiteIds.towns) {
                for (candidate in planPond(world, site, occupied, clearRoad, random, snow, anchors["pier"], pier?.rotation)) {
                    yield(Unit)
                    if (candidate != null) { pool = candidate; break }
                }
            }
            val pond = pool?.second.orEmpty()
            val pondColumns = pond.mapTo(HashSet()) { columnKey(it.position.x, it.position.z) }
            if (pool != null) {
                anchors["fishing_water"] = pool.first
            } else if (site.id in OverworldSiteIds.towns) {
                val naturalWater = findNaturalFishingWater(world, site, anchors["pier"])
                if (naturalWater != null) {
                    anchors["fishing_water"] = naturalWater
                    Bukkit.getLogger().info(
                        "No safe fishing pool found for ${site.id}; anchored fishing_water to nearby natural water at ${naturalWater.x},${naturalWater.y},${naturalWater.z}",
                    )
                } else {
                    Bukkit.getLogger().warning("No safe fishing pool or open natural water found for ${site.id}; continuing without a pool")
                }
            }
            val (roadChanges, roadClearance) = planRoads(world, clearRoad, portals)
            yield(Unit)
            val lights = planLights(world, site, roadChanges, clearRoadColumns, pondColumns, occupied)
            yield(Unit)
            val decorations = decorations(world, site, occupied, clearRoadColumns, pondColumns, snow).filterNot { decoration ->
                lights.any { it.position == decoration.position }
            }
            result = OverworldTownPlan(site, buildings, roadChanges, roadClearance, pond, lights, decorations, anchors, portals, prepared)
        }.iterator()
        return OverworldTownPlanning(steps) { checkNotNull(result) { "Town planning is incomplete" } }
    }

    /** 道・池・建物と重ならない場所へ街灯を配置します。 */
    private fun planLights(world: World, site: OverworldSite, roadChanges: List<OverworldTerrainChange>,
        clearRoadColumns: Set<Long>, pondColumns: Set<Long>, occupied: List<Rect>): List<OverworldTerrainChange> {
        return if (site.id in OverworldSiteIds.towns) roadChanges.filterIndexed { index, _ -> index % 48 == 0 }.flatMap { roadChange ->
            val p = roadChange.position
            val position = listOf(2 to 0, -2 to 0, 0 to 2, 0 to -2).firstNotNullOfOrNull { (dx, dz) ->
                val x = p.x + dx
                val z = p.z + dz
                if (!withinSite(site, x, z)) return@firstNotNullOfOrNull null
                if (columnKey(x, z) in clearRoadColumns || columnKey(x, z) in pondColumns ||
                    occupied.any { x in it.minX..it.maxX && z in it.minZ..it.maxZ }) return@firstNotNullOfOrNull null
                val y = surfaceY(world, x, z)
                if (y + 2 >= world.maxHeight || !world.getBlockAt(x, y, z).type.isSolid ||
                    !world.getBlockAt(x, y + 1, z).type.isAir || !world.getBlockAt(x, y + 2, z).type.isAir) null else OverworldPoint(x, y, z)
            }
            if (position == null) emptyList() else listOf(
                OverworldTerrainChange(OverworldPoint(position.x, position.y + 1, position.z), Material.AIR, Material.SPRUCE_FENCE),
                OverworldTerrainChange(OverworldPoint(position.x, position.y + 2, position.z), Material.AIR, Material.LANTERN))
        } else emptyList()
    }

    private data class RoadPlan(val road: List<OverworldTerrainChange>, val clearance: List<OverworldTerrainChange>)

    /** 路面と頭上空間を分割検査し、障害セルは書込対象から外します。 */
    private suspend fun SequenceScope<Unit>.planRoads(world: World, clearRoad: Set<OverworldPoint>,
        portals: List<OverworldPortal>): RoadPlan {
        val heightsByPoint = clearRoad.associate { (it.x to it.z) to it.y }
        val roadChanges = mutableListOf<OverworldTerrainChange>()
        clearRoad.forEachIndexed { index, point ->
            yield(Unit)
            val before = world.getBlockAt(point.x, point.y, point.z).type
            if (before !in OverworldMaterials.ground && before != Material.WATER) return@forEachIndexed
            if ((point.y + 1..minOf(point.y + 3, world.maxHeight - 1)).any { y ->
                val overhead = world.getBlockAt(point.x, y, point.z).type
                !overhead.isAir && !Tag.LOGS.isTagged(overhead) && !Tag.LEAVES.isTagged(overhead) && overhead !in OverworldMaterials.plants
            }) return@forEachIndexed
            val uphill = listOf(Triple(1, 0, BlockFace.EAST), Triple(-1, 0, BlockFace.WEST), Triple(0, 1, BlockFace.SOUTH), Triple(0, -1, BlockFace.NORTH))
                .firstOrNull { (dx, dz, _) -> heightsByPoint[point.x + dx to point.z + dz] == point.y + 1 }?.third
            val after = when {
                before == Material.WATER -> Material.SPRUCE_PLANKS
                uphill != null -> Material.COBBLESTONE_STAIRS
                index % 7 == 0 -> Material.GRAVEL
                else -> Material.COBBLESTONE
            }
            roadChanges += OverworldTerrainChange(point, before, after, uphill.takeIf { after == Material.COBBLESTONE_STAIRS })
        }
        val roadClearance = roadChanges.flatMap { change ->
            val p = change.position
            (p.y + 1..minOf(p.y + 3, world.maxHeight - 1)).mapNotNull { y ->
                val before = world.getBlockAt(p.x, y, p.z).type
                if (before.isAir) null else {
                    if (Tag.LOGS.isTagged(before) || Tag.LEAVES.isTagged(before) || before in OverworldMaterials.plants)
                        OverworldTerrainChange(OverworldPoint(p.x, y, p.z), before, Material.AIR) else null
                }
            }
        }.toMutableList()
        portals.forEach { portal ->
            val exit = portal.exit
            for (y in exit.y..exit.y + 1) {
                val before = world.getBlockAt(exit.x, y, exit.z).type
                if (!before.isAir) roadClearance += OverworldTerrainChange(OverworldPoint(exit.x, y, exit.z), before, Material.AIR)
            }
        }
        return RoadPlan(roadChanges, roadClearance)
    }

    /** 建物の床を避け、各入口から最寄りの道へ接続します。 */
    private fun connectRoads(world: World, road: List<OverworldPoint>, occupied: List<Rect>,
        buildings: List<OverworldBuildingPlan>, anchors: Map<String, OverworldPoint>): MutableSet<OverworldPoint> {
        // 広場や建物の床を後から道で上書きしません。
        val clearRoad = road.filterNot { p -> occupied.any { p.x in it.minX..it.maxX && p.z in it.minZ..it.maxZ } }.toMutableSet()
        buildings.forEach { building ->
            val entry = anchors.getValue(building.name)
            val nearest = road.minByOrNull { abs(it.x - entry.x) + abs(it.z - entry.z) } ?: return@forEach
            val (width, length) = SchematicPlacer.rotatedDimensions(assets[building.template], building.rotation)
            val own = Rect(building.origin.x - BUILDING_MARGIN, building.origin.x + width - 1 + BUILDING_MARGIN,
                building.origin.z - BUILDING_MARGIN, building.origin.z + length - 1 + BUILDING_MARGIN)
            val footprint = Rect(building.origin.x, building.origin.x + width - 1, building.origin.z, building.origin.z + length - 1)
            var x = entry.x
            var z = entry.z
            while (x != nearest.x || z != nearest.z) {
                if (x != nearest.x) x += if (x < nearest.x) 1 else -1 else z += if (z < nearest.z) 1 else -1
                if (x in footprint.minX..footprint.maxX && z in footprint.minZ..footprint.maxZ) continue
                if (occupied.any { it != own && x in it.minX..it.maxX && z in it.minZ..it.maxZ }) continue
                clearRoad += OverworldPoint(x, surfaceY(world, x, z), z)
            }
        }
        return clearRoad
    }

    private data class BuildingSelection(
        val occupied: MutableList<Rect>,
        val buildings: MutableList<OverworldBuildingPlan>,
        val anchors: MutableMap<String, OverworldPoint>,
        val portals: MutableList<OverworldPortal>,
        val prepared: MutableMap<String, Iterator<() -> Unit>>,
    )

    /** 候補を段階的に検査し、建物と入口の確定結果を返します。 */
    private suspend fun SequenceScope<Unit>.planBuildings(world: World, site: OverworldSite, seed: Long, random: Random, snow: Boolean,
        road: List<OverworldPoint>, requests: List<Request>,
    ): BuildingSelection {
        val roadColumns = road.mapTo(HashSet()) { columnKey(it.x, it.z) }
        val occupied = mutableListOf<Rect>()
        val buildings = mutableListOf<OverworldBuildingPlan>()
        val anchors = linkedMapOf<String, OverworldPoint>()
        val portals = mutableListOf<OverworldPortal>()
        val prepared = mutableMapOf<String, Iterator<() -> Unit>>()
        val totalStats = RejectionStats()
        requests.forEachIndexed { index, request ->
            val templatesToTry = (listOf(request.template) + request.fallbacks).distinct()
            val isDungeonEntrance = templatesToTry.any { it in dungeonIds }
            val allowFineFallback = request.required ||
                (request.name.startsWith("house_") && needsMoreTargetHouses(site.id, buildings))
            val candidateBatches: Sequence<List<Candidate>> = if (index == 0) {
                sequence {
                    yield(centerCandidates(site))
                    // ダンジョン入口の正面（NONE）が水辺・崖でポータル退出不可な場合、中心候補の残り3方向を先に試します。
                    if (isDungeonEntrance) yield(rotatedCenterCandidates(site))
                    // 中心 ±24 がバニラ構造物や地形障害で全滅した場合のみ、独立 RNG を使って拠点半径内の敷地候補へ中心近傍順でフォールバックします。
                    val fallbackRandom = Random(seed xor site.id.hashCode().toLong() xor FALLBACK_RANDOM_SALT)
                    val primaryPlots = plotCandidates(site, fallbackRandom).sortedBy { abs(it.x - site.x) + abs(it.z - site.z) }
                    yield(primaryPlots)
                    if (isDungeonEntrance) yield(alternateRotations(primaryPlots))
                    val fineRandom = Random(seed xor site.id.hashCode().toLong() xor FINE_GRID_RANDOM_SALT)
                    val finePlots = finePlotCandidates(site, fineRandom).sortedBy { abs(it.x - site.x) + abs(it.z - site.z) }
                    yield(finePlots)
                    if (isDungeonEntrance) yield(alternateRotations(finePlots))
                }
            } else {
                sequence {
                    val primaryPlots = plotCandidates(site, random)
                    yield(primaryPlots)
                    if (isDungeonEntrance) yield(alternateRotations(primaryPlots))
                    // 海岸・狭隘地形で 8 ブロック格子に収まらない必須施設や目標未達の住宅だけ、独立 RNG の 4 ブロック細密格子へフォールバックします。
                    if (allowFineFallback) {
                        val fineRandom = Random(seed xor site.id.hashCode().toLong() xor FINE_GRID_RANDOM_SALT xor index.toLong())
                        val finePlots = finePlotCandidates(site, fineRandom)
                        yield(finePlots)
                        if (isDungeonEntrance) yield(alternateRotations(finePlots))
                    }
                }
            }
            var building: OverworldBuildingPlan? = null
            var bestRect: Rect? = null
            var bestPreparation: SchematicPreparation? = null
            var chosenTemplate = request.template
            val reqStats = RejectionStats()
            val waterFacility = request.name == "pier" || request.name == "lighthouse"

            for ((batchIndex, candidates) in candidateBatches.withIndex()) {
                // フォールバック段階 (batchIndex > 0) では独立 RNG を使い、後続の建物・池の乱数列を動かしません。
                val paletteRandom = if (batchIndex == 0) {
                    random
                } else {
                    Random(seed xor site.id.hashCode().toLong() xor FALLBACK_PALETTE_SALT xor (index.toLong() shl 8) xor batchIndex.toLong())
                }
                for (template in templatesToTry) {
                    val schematic = assets[template]
                    var bestRank = Int.MAX_VALUE
                    var validFound = 0
                    for (candidate in candidates) {
                        // 1候補ごとに制御を返し、大量の区画探索を1 tickに詰め込みません。
                        yield(Unit)
                        val (cx, cz, rotation) = candidate
                        val (width, length) = SchematicPlacer.rotatedDimensions(schematic, rotation)
                        val ox = cx - width / 2
                        val oz = cz - length / 2
                        val finePlot = index > 0 && allowFineFallback && (
                            (cx - site.x).mod(PLOT_GRID_STEP) != 0 || (cz - site.z).mod(PLOT_GRID_STEP) != 0
                        )
                        val margin = if (finePlot) FINE_BUILDING_MARGIN else BUILDING_MARGIN
                        val rect = Rect(
                            ox - margin,
                            ox + width - 1 + margin,
                            oz - margin,
                            oz + length - 1 + margin,
                        )
                        if (!withinSite(site, rect.minX, rect.minZ) || !withinSite(site, rect.minX, rect.maxZ) ||
                            !withinSite(site, rect.maxX, rect.minZ) || !withinSite(site, rect.maxX, rect.maxZ)) {
                            reqStats.outOfBounds++
                            continue
                        }
                        if (occupied.any(rect::overlaps)) {
                            reqStats.overlap++
                            continue
                        }
                        if (index > 0 && containsColumn(rect, roadColumns)) {
                            reqStats.road++
                            continue
                        }
                        val y = surfaceY(world, cx, cz)
                        if (!waterFacility && world.getBlockAt(cx, y, cz).type == Material.WATER) {
                            reqStats.water++
                            continue
                        }
                        // ダンジョン入口は重い足跡検証の前に O(1) でポータル退出先の安全性を検査し、水辺向きの候補を即座に除外します。
                        val dungeon = dungeonIds[template]
                        if (dungeon != null) {
                            val portalProbe = OverworldBuildingPlan(request.name, template, OverworldPoint(ox, y, oz), rotation, emptyMap(), snow)
                            try {
                                portal(world, site.id, dungeon, schematic, portalProbe, door(schematic, portalProbe))
                            } catch (failure: IllegalArgumentException) {
                                reqStats.portal++
                                reqStats.lastFailure = failure.message
                                continue
                            }
                        }
                        val swaps = palette(paletteRandom, snow)
                        val options = options(rotation, swaps, snow)
                        val preparation = try {
                            SchematicPlacer.prepareStepped(schematic, world, ox, y, oz, options)
                        } catch (failure: IllegalArgumentException) {
                            reqStats.terrain++
                            reqStats.lastFailure = failure.message
                            continue
                        }
                        var valid = true
                        while (true) {
                            val hasNext = try { preparation.steps.hasNext() } catch (failure: IllegalArgumentException) {
                                reqStats.terrain++
                                reqStats.lastFailure = failure.message
                                valid = false
                                false
                            }
                            if (!hasNext) break
                            preparation.steps.next()
                            yield(Unit)
                        }
                        if (!valid) continue
                        val candidateBuilding = OverworldBuildingPlan(request.name, template, OverworldPoint(ox, y, oz), rotation, swaps, snow)
                        val heights = listOf(ox to oz, ox + width - 1 to oz, ox to oz + length - 1, ox + width - 1 to oz + length - 1)
                            .map { (px, pz) -> surfaceY(world, px, pz) }
                        val relief = heights.max() - heights.min()
                        val waterDistance = if (waterFacility) nearbyWaterDistance(world, site, cx, cz) else 0
                        val rank = relief * RELIEF_WEIGHT + waterDistance - if (request.name == "lighthouse") y / LIGHTHOUSE_HEIGHT_DIVISOR else 0
                        validFound++
                        if (rank < bestRank) {
                            bestRank = rank
                            bestRect = rect
                            building = candidateBuilding
                            bestPreparation = preparation
                            chosenTemplate = template
                        }
                        if ((!waterFacility && (relief <= 2 || validFound >= MAX_VALID_SAMPLES_PER_BUILDING)) ||
                            (waterFacility && waterDistance <= WATERFRONT_DISTANCE && relief <= WATERFRONT_RELIEF)) break
                    }
                    if (building != null) break
                }
                if (building != null) break
            }

            totalStats.outOfBounds += reqStats.outOfBounds
            totalStats.overlap += reqStats.overlap
            totalStats.road += reqStats.road
            totalStats.water += reqStats.water
            totalStats.terrain += reqStats.terrain
            totalStats.portal += reqStats.portal
            if (reqStats.lastFailure != null) totalStats.lastFailure = reqStats.lastFailure

            if (building == null) {
                check(!request.required) {
                    "No grounded plot for ${request.name} (${request.template}) at ${site.id}: ${reqStats.summary()}"
                }
            } else {
                val schematic = assets[chosenTemplate]
                occupied += checkNotNull(bestRect)
                buildings += building
                prepared[building.name] = checkNotNull(bestPreparation).result()
                val door = door(schematic, building)
                anchors[request.name] = door
                if (request.name == "plaza") {
                    val (width, length) = SchematicPlacer.rotatedDimensions(schematic, building.rotation)
                    anchors["plaza_min"] = building.origin
                    anchors["plaza_max"] = OverworldPoint(building.origin.x + width - 1, building.origin.y, building.origin.z + length - 1)
                    val center = SchematicPlacer.transformLocalPoint(schematic, building.rotation, schematic.width / 2, minOf(1, schematic.height - 1), schematic.length / 2)
                    anchors["plaza_center"] = OverworldPoint(building.origin.x + center.x, building.origin.y + center.y, building.origin.z + center.z)
                }
                dungeonIds[chosenTemplate]?.let { dungeon -> portals += portal(world, site.id, dungeon, schematic, building, door) }
            }
        }
        if (site.id == OverworldSiteIds.LUMINA_PORT) {
            val houses = buildings.filter { it.name.startsWith("house_") }
            val distinct = houses.map { it.template }.toSet().size
            require(houses.size >= LUMINA_MIN_HOUSES && distinct >= LUMINA_MIN_DISTINCT_HOUSES) {
                "Insufficient distinct grounded plots for ${site.id}: placed=${houses.size} distinct=$distinct " +
                    "(required>=$LUMINA_MIN_HOUSES/$LUMINA_MIN_DISTINCT_HOUSES) rejections=[${totalStats.summary()}]"
            }
            if (houses.size < LUMINA_TARGET_HOUSES || distinct < LUMINA_TARGET_DISTINCT_HOUSES) {
                Bukkit.getLogger().warning(
                    "Overworld town ${site.id} placed ${houses.size} houses ($distinct distinct; target $LUMINA_TARGET_HOUSES/$LUMINA_TARGET_DISTINCT_HOUSES): ${totalStats.summary()}",
                )
            }
        }
        if (site.id == OverworldSiteIds.FROSTPEAK_OUTPOST) {
            val houses = buildings.count { it.name.startsWith("house_") }
            require(houses >= FROSTPEAK_MIN_HOUSES) {
                "Insufficient grounded plots for ${site.id}: placed=$houses (required>=$FROSTPEAK_MIN_HOUSES) " +
                    "rejections=[${totalStats.summary()}]"
            }
            if (houses < FROSTPEAK_TARGET_HOUSES) {
                Bukkit.getLogger().warning(
                    "Overworld town ${site.id} placed $houses houses (target $FROSTPEAK_TARGET_HOUSES): ${totalStats.summary()}",
                )
            }
        }
        return BuildingSelection(occupied, buildings, anchors, portals, prepared)
    }

    /** 配置手順を遅延生成し、呼び出し側が1 tickの操作数を制御できるようにします。 */
    fun actions(world: World, plan: OverworldTownPlan): Iterator<() -> Unit> = sequence<() -> Unit> {
        plan.buildings.forEach { building ->
            val iterator = plan.prepared.getValue(building.name)
            while (iterator.hasNext()) yield(iterator.next())
        }
        plan.roadClearance.forEach { change -> yield {
            val p = change.position
            val block = world.getBlockAt(p.x, p.y, p.z)
            // 計画〜適用間に自然水が流入した低地セルは、水流を壊さず生成も止めずにスキップします。
            if (block.type == Material.WATER) return@yield
            check(block.type == change.before || isNaturalClearance(block.type)) {
                "Road clearance changed at ${p.x},${p.y},${p.z}: expected ${change.before}, got ${block.type}"
            }
            block.setType(Material.AIR, false)
        } }
        plan.road.forEach { change ->
            yield {
                val p = change.position
                val block = world.getBlockAt(p.x, p.y, p.z)
                check(block.type == change.before || block.type == change.after || isNaturalTerrainDrift(change.before, block.type)) {
                    "Road terrain changed at ${p.x},${p.y},${p.z}: expected ${change.before}, got ${block.type}"
                }
                block.setType(change.after, false)
                change.facing?.let { facing ->
                    val data = block.blockData as Directional
                    data.facing = facing
                    block.setBlockData(data, false)
                }
            }
        }
        plan.pond.forEach { change -> yield {
            val p = change.position
            val block = world.getBlockAt(p.x, p.y, p.z)
            val isBottom = change.after == Material.GRAVEL || change.after == Material.PACKED_ICE
            check(block.type == change.before || block.type == change.after || isNaturalPondDrift(change.before, block.type)) {
                "Fishing pool terrain changed at ${p.x},${p.y},${p.z}: expected ${change.before}, got ${block.type}"
            }
            if (isBottom) {
                check(isStableSolid(world, p.x, p.y - 1, p.z)) {
                    "Fishing pool support changed below ${p.x},${p.y},${p.z}"
                }
            }
            block.setType(change.after, false)
        } }
        plan.lights.forEach { change -> yield {
            val p = change.position
            val block = world.getBlockAt(p.x, p.y, p.z)
            if (block.type == Material.WATER) return@yield
            check(isNaturalOpenSpace(block.type) || block.type == change.after) {
                "Lantern location changed at ${p.x},${p.y},${p.z}: expected ${change.before}, got ${block.type}"
            }
            // 支柱またはランタンの相方セルへ自然水が流入した場合は、浮遊ランタンや水中支柱を残さず一式スキップします。
            if ((change.after == Material.SPRUCE_FENCE && p.y + 1 < world.maxHeight && world.getBlockAt(p.x, p.y + 1, p.z).type == Material.WATER) ||
                (change.after == Material.LANTERN && p.y - 1 >= world.minHeight && world.getBlockAt(p.x, p.y - 1, p.z).type == Material.WATER)
            ) return@yield
            block.setType(change.after, false)
        } }
        plan.decorations.forEach { change -> yield {
            val p = change.position
            val block = world.getBlockAt(p.x, p.y, p.z)
            if (block.type == Material.WATER) return@yield
            check(isNaturalOpenSpace(block.type) || block.type == change.after) {
                "Decoration location changed at ${p.x},${p.y},${p.z}: expected ${change.before}, got ${block.type}"
            }
            block.setType(change.after, false)
        } }
    }.iterator()

    /** 計画〜適用間にバニラ仕様で起こり得る具体的な自然遷移ペア（草↔土・菌糸↔土・耕地/道→土・水↔氷）のみ許容します。 */
    private fun isNaturalTerrainDrift(before: Material, actual: Material): Boolean = when (before) {
        Material.GRASS_BLOCK, Material.MYCELIUM -> actual == Material.DIRT
        Material.DIRT -> actual == Material.GRASS_BLOCK || actual == Material.MYCELIUM
        Material.FARMLAND, Material.DIRT_PATH -> actual == Material.DIRT || actual == Material.GRASS_BLOCK
        Material.WATER -> actual == Material.ICE
        Material.ICE -> actual == Material.WATER
        else -> false
    }

    private fun isNaturalClearance(type: Material): Boolean =
        type.isAir || Tag.LOGS.isTagged(type) || Tag.LEAVES.isTagged(type) || type in OverworldMaterials.plants ||
            Tag.FLOWERS.isTagged(type) || Tag.SAPLINGS.isTagged(type) || Tag.CAVE_VINES.isTagged(type)

    private fun isNaturalPondDrift(before: Material, actual: Material): Boolean =
        isNaturalTerrainDrift(before, actual) || (before.isAir && actual == Material.SNOW)

    private fun isNaturalOpenSpace(type: Material): Boolean =
        type.isAir || type == Material.SNOW

    /** 雪や水晶の目印は建物・道・池を避け、空きブロックにだけ加えます。 */
    private fun decorations(world: World, site: OverworldSite, occupied: List<Rect>, road: Set<Long>, pond: Set<Long>, snow: Boolean): List<OverworldTerrainChange> {
        if (!snow && site.id != OverworldSiteIds.VOID_SANCTUM && site.id != OverworldSiteIds.CRYSTALLINE_CAVERN) return emptyList()
        val offsets = listOf(-30 to -30, -30 to 0, -30 to 30, 0 to -30, 0 to 30, 30 to -30, 30 to 0, 30 to 30)
        return offsets.mapIndexedNotNull { index, (dx, dz) ->
            val x = site.x + dx
            val z = site.z + dz
            if (occupied.any { x in it.minX..it.maxX && z in it.minZ..it.maxZ } || columnKey(x, z) in road ||
                columnKey(x, z) in pond) return@mapIndexedNotNull null
            val y = surfaceY(world, x, z)
            if (y + 1 >= world.maxHeight || !world.getBlockAt(x, y, z).type.isSolid || !world.getBlockAt(x, y + 1, z).type.isAir) return@mapIndexedNotNull null
            val material = when (site.id) {
                OverworldSiteIds.VOID_SANCTUM -> if (index % 2 == 0) Material.OBSIDIAN else Material.AMETHYST_BLOCK
                OverworldSiteIds.CRYSTALLINE_CAVERN -> if (index % 2 == 0) Material.AMETHYST_BLOCK else Material.PACKED_ICE
                else -> Material.SNOW
            }
            OverworldTerrainChange(OverworldPoint(x, y + 1, z), Material.AIR, material)
        }
    }

    /** 砂・砂利でも下に空洞がなく非重力固体で支えられていれば、隣接更新による崩落は起きません。 */
    private fun isStableSolid(world: World, x: Int, y: Int, z: Int): Boolean {
        if (y < world.minHeight) return false
        val type = world.getBlockAt(x, y, z).type
        if (!type.isSolid) return false
        if (type != Material.GRAVEL && type != Material.SAND && type != Material.RED_SAND) return true
        var py = y - 1
        repeat(GRAVITY_SUPPORT_DEPTH) {
            if (py < world.minHeight) return false
            val below = world.getBlockAt(x, py, z).type
            if (!below.isSolid) return false
            if (below != Material.GRAVEL && below != Material.SAND && below != Material.RED_SAND) return true
            py--
        }
        return false
    }

    /** 他の敷地と重ならない平坦地を探し、池の全ブロックを先に検査します。大型池が置けない場合は小型へ縮小します。 */
    private fun planPond(world: World, site: OverworldSite, occupied: List<Rect>, road: Set<OverworldPoint>, random: Random, snow: Boolean, near: OverworldPoint?, facing: StructureRotation?): Sequence<Pair<OverworldPoint, List<OverworldTerrainChange>>?> {
        val radii = if (near != null) listOf(4, 2) else listOf(2)
        val roadColumns = road.mapTo(HashSet()) { columnKey(it.x, it.z) }
        return radii.asSequence().flatMap { radius ->
            val limit = plotRadius(site.id) - radius - 2
            val grid = (-limit..limit step POND_GRID_STEP)
            val shuffled = grid.flatMap { dx -> grid.map { dz -> site.x + dx to site.z + dz } }.shuffled(random)
            val candidates = if (near == null || facing == null) shuffled else {
                val (dx, dz) = frontDirection(facing)
                shuffled.sortedBy { (x, z) ->
                    val along = (x - near.x) * dx + (z - near.z) * dz
                    val across = abs((x - near.x) * dz - (z - near.z) * dx)
                    abs(along) + across * 3 + if (along < 0) 1000 else 0
                }
            }
            candidates.asSequence().map { (cx, cz) ->
                val bounds = Rect(cx - radius - 1, cx + radius + 1, cz - radius - 1, cz + radius + 1)
                if (!withinSite(site, bounds.minX, bounds.minZ) || !withinSite(site, bounds.minX, bounds.maxZ) ||
                    !withinSite(site, bounds.maxX, bounds.minZ) || !withinSite(site, bounds.maxX, bounds.maxZ)) return@map null
                if (occupied.any(bounds::overlaps) || containsColumn(bounds, roadColumns)) return@map null
                val cells = (-radius..radius).flatMap { dx -> (-radius..radius).mapNotNull { dz -> if (dx * dx + dz * dz <= radius * radius + 1) cx + dx to cz + dz else null } }
                val heights = cells.map { (x, z) -> surfaceY(world, x, z) }
                if (heights.max() - heights.min() > 1) return@map null
                val y = heights.min()
                // 外周の地表と底面が安定した固体でない場所は水が横や下へ漏れるため除外します。
                for (x in bounds.minX..bounds.maxX) for (z in bounds.minZ..bounds.maxZ) {
                    if (x !in cx - radius..cx + radius || z !in cz - radius..cz + radius) {
                        if (surfaceY(world, x, z) < y || !isStableSolid(world, x, y, z)) return@map null
                    }
                }
                if (cells.any { (x, z) -> !isStableSolid(world, x, y - 2, z) }) return@map null
                val changes = mutableListOf<OverworldTerrainChange>()
                for ((cell, top) in cells.zip(heights)) {
                    val (x, z) = cell
                    for (py in y - 1..top) {
                        val before = world.getBlockAt(x, py, z).type
                        if (before !in OverworldMaterials.ground && !before.isAir && before != Material.WATER) return@map null
                        val after = when {
                            py < y -> if (snow) Material.PACKED_ICE else Material.GRAVEL
                            py == y -> Material.WATER
                            else -> Material.AIR
                        }
                        changes += OverworldTerrainChange(OverworldPoint(x, py, z), before, after)
                    }
                }
                OverworldPoint(cx, y, cz) to changes
            }
        }
    }

    /** 人工池を配置できなかった町でも釣れるよう、桟橋または拠点中心の近傍から開いた自然水面を探します。 */
    private suspend fun SequenceScope<Unit>.findNaturalFishingWater(
        world: World,
        site: OverworldSite,
        near: OverworldPoint?,
    ): OverworldPoint? {
        val originX = near?.x ?: site.x
        val originZ = near?.z ?: site.z
        val maxOffset = loadRadius(site.id) - 4
        for (radius in 2..NATURAL_WATER_SEARCH_RADIUS step 2) {
            yield(Unit)
            for (dx in -radius..radius step 2) {
                for (dz in -radius..radius step 2) {
                    if (abs(dx) != radius && abs(dz) != radius) continue
                    val px = originX + dx
                    val pz = originZ + dz
                    if (abs(px - site.x) > maxOffset || abs(pz - site.z) > maxOffset) continue
                    val y = surfaceY(world, px, pz)
                    if (y + 1 >= world.maxHeight) continue
                    if (world.getBlockAt(px, y, pz).type == Material.WATER &&
                        world.getBlockAt(px, y + 1, pz).type.isAir
                    ) {
                        return OverworldPoint(px, y, pz)
                    }
                }
            }
        }
        return null
    }

    /** 地域別の構成と個別住宅をシード順で選びます。 */
    private fun requests(id: String, random: Random): List<Request> {
        fun request(name: String, template: String, required: Boolean = true, fallbacks: List<String> = emptyList()) =
            Request(name, template, required, fallbacks)
        val temperate = listOf("house_timber_small_a", "house_timber_small_b", "house_coastal_cottage", "house_l_shaped_manor", "house_stone_bakery", "house_alchemist_tower", "house_snow_cabin_a", "house_snow_longhouse")
        val smallTemperate = listOf("house_timber_small_a", "house_timber_small_b", "house_alchemist_tower", "house_coastal_cottage")
        val smallSnow = listOf("house_snow_cabin_a", "house_timber_small_a", "house_timber_small_b")
        fun homes(templates: List<String>, count: Int, fallbacks: List<String>): List<Request> {
            val shuffled = templates.shuffled(random)
            return List(count) { i ->
                val template = if (i < shuffled.size) shuffled[i] else templates[random.nextInt(templates.size)]
                // 大型テンプレートが置けず小型フォールバックへ落ちた場合も、同一種への集中を避けて種類数を保ちます。
                val rotatedFallbacks = if (fallbacks.isEmpty()) emptyList() else {
                    val shift = i % fallbacks.size
                    fallbacks.drop(shift) + fallbacks.take(shift)
                }
                request("house_$i", template, false, rotatedFallbacks)
            }
        }
        return when (id) {
            OverworldSiteIds.LUMINA_PORT -> listOf(
                request("plaza", "civic_market_plaza"),
                request("guild_hall", "civic_guild_hall"),
                request("blacksmith", "civic_blacksmith_forge"),
                request("tavern", "civic_tavern_inn"),
                request("lighthouse", "civic_lighthouse"),
                request("pier", "civic_harbor_pier"),
                request("watchtower", "civic_watchtower"),
                request("waystone", "prop_waystone"),
                request("well", "prop_well"),
                request("stall", "prop_stall_a", false),
            ) + homes(temperate, 12 + random.nextInt(4), smallTemperate)
            OverworldSiteIds.FROSTPEAK_OUTPOST -> listOf(
                request("command_hall", "civic_frost_command_hall"),
                request("watchtower", "civic_watchtower"),
                request("watchtower_2", "civic_watchtower"),
                request("plaza", "civic_market_plaza"),
                request("waystone", "prop_waystone"),
                request("well", "prop_well", false),
            ) + homes(listOf("house_snow_cabin_a", "house_snow_longhouse", "house_timber_small_a", "house_timber_small_b"), 6 + random.nextInt(3), smallSnow)
            OverworldSiteIds.ASHWOOD_FOREST -> listOf(request("camp", "landmark_ashwood_camp"), request("entrance", "entrance_novice_arena"), request("waystone", "prop_waystone")) + homes(temperate.take(3), 2, smallTemperate)
            OverworldSiteIds.WEEPING_MINE -> listOf(request("mine", "landmark_mine_headframe"), request("waystone", "prop_waystone")) + homes(listOf("house_timber_small_b", "house_stone_bakery"), 2, smallTemperate)
            OverworldSiteIds.STARGAZER_HILL -> listOf(request("observatory", "landmark_stargazer_observatory"), request("entrance", "entrance_aether_crucible"), request("waystone", "prop_waystone"), request("tower", "house_alchemist_tower"))
            OverworldSiteIds.VOID_SANCTUM -> listOf(request("entrance", "entrance_void_sanctum"), request("waystone", "prop_waystone"))
            OverworldSiteIds.GLACIER_RIDGE, OverworldSiteIds.CRYSTALLINE_CAVERN -> listOf(request("watchtower", "civic_watchtower"), request("cabin", "house_snow_cabin_a"), request("waystone", "prop_waystone"))
            OverworldSiteIds.GLACIAL_CITADEL -> listOf(request("entrance", "entrance_glacial_citadel"), request("watchtower", "civic_watchtower"), request("watchtower_2", "civic_watchtower"), request("waystone", "prop_waystone"))
            else -> error("Unknown overworld site: $id")
        }
    }

    private data class Candidate(val x: Int, val z: Int, val rotation: StructureRotation)

    /** 区画内に既存の道があるか座標集合で調べます。 */
    private fun containsColumn(rect: Rect, columns: Set<Long>): Boolean =
        (rect.minX..rect.maxX).any { x -> (rect.minZ..rect.maxZ).any { z -> columnKey(x, z) in columns } }

    /** 拠点規模に応じた建築半径を返し、Tier 3の最小間隔150でも隣接拠点と重ならない範囲に収めます。 */
    private fun withinSite(site: OverworldSite, x: Int, z: Int): Boolean {
        val radius = plotRadius(site.id).toLong()
        val dx = x.toLong() - site.x
        val dz = z.toLong() - site.z
        return dx * dx + dz * dz <= radius * radius
    }

    /** 灯台と桟橋は近くの自然水域を優先し、保持済みチャンク範囲内だけで探索します。 */
    private fun nearbyWaterDistance(world: World, site: OverworldSite, x: Int, z: Int): Int {
        val maxOffset = loadRadius(site.id) - 4
        val directions = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1, 1 to 1, 1 to -1, -1 to 1, -1 to -1)
        for (distance in listOf(8, 16, 24)) for ((dx, dz) in directions) {
            val px = x + dx * distance
            val pz = z + dz * distance
            if (abs(px - site.x) > maxOffset || abs(pz - site.z) > maxOffset) continue
            val y = surfaceY(world, px, pz)
            if (world.getBlockAt(px, y, pz).type == Material.WATER) return distance
        }
        return 64
    }

    /** 目標住宅数または種類数が未達の町でのみ、細密格子フォールバックを許可します。 */
    private fun needsMoreTargetHouses(siteId: String, buildings: List<OverworldBuildingPlan>): Boolean = when (siteId) {
        OverworldSiteIds.LUMINA_PORT -> {
            val houses = buildings.filter { it.name.startsWith("house_") }
            houses.size < LUMINA_TARGET_HOUSES || houses.map { it.template }.toSet().size < LUMINA_TARGET_DISTINCT_HOUSES
        }
        OverworldSiteIds.FROSTPEAK_OUTPOST ->
            buildings.count { it.name.startsWith("house_") } < FROSTPEAK_TARGET_HOUSES
        else -> false
    }

    /** 重要施設は中心近くを小刻みに探索します。 */
    private fun centerCandidates(site: OverworldSite): List<Candidate> =
        (-24..24 step 4).flatMap { dx -> (-24..24 step 4).map { dz -> Candidate(site.x + dx, site.z + dz, StructureRotation.NONE) } }
            .sortedBy { abs(it.x - site.x) + abs(it.z - site.z) }

    /** 中心候補で標準向き（NONE）が失敗したダンジョン入口向けに、残り3方向を中心近傍順で生成します。 */
    private fun rotatedCenterCandidates(site: OverworldSite): List<Candidate> =
        (-24..24 step 4).flatMap { dx ->
            (-24..24 step 4).flatMap { dz ->
                nonDefaultRotations.map { rotation -> Candidate(site.x + dx, site.z + dz, rotation) }
            }
        }.sortedBy { abs(it.x - site.x) + abs(it.z - site.z) }

    /** 各敷地候補について、既定の向き以外の3方向を順に試すフォールバック候補を生成します。 */
    private fun alternateRotations(base: List<Candidate>): List<Candidate> = base.flatMap { candidate ->
        allRotations.filter { it != candidate.rotation }.map { rotation -> candidate.copy(rotation = rotation) }
    }

    /** 敷地候補を四方向に並べ、入口を中心側へ向けます。 */
    private fun plotCandidates(site: OverworldSite, random: Random): List<Candidate> {
        val limit = (plotRadius(site.id) / PLOT_GRID_STEP) * PLOT_GRID_STEP
        return (-limit..limit step PLOT_GRID_STEP).flatMap { dx ->
            (-limit..limit step PLOT_GRID_STEP).mapNotNull { dz ->
                if (abs(dx) < 12 && abs(dz) < 12) return@mapNotNull null
                val rotation = if (abs(dx) > abs(dz)) {
                    if (dx > 0) StructureRotation.CLOCKWISE_90 else StructureRotation.COUNTERCLOCKWISE_90
                } else if (dz > 0) StructureRotation.CLOCKWISE_180 else StructureRotation.NONE
                Candidate(site.x + dx, site.z + dz, rotation)
            }
        }.shuffled(random)
    }

    /** 粗い8ブロック格子で配置できなかった施設向けに、既検査点を除いた4ブロック細密格子候補を生成します。 */
    private fun finePlotCandidates(site: OverworldSite, random: Random): List<Candidate> {
        val limit = (plotRadius(site.id) / PLOT_FINE_GRID_STEP) * PLOT_FINE_GRID_STEP
        return (-limit..limit step PLOT_FINE_GRID_STEP).flatMap { dx ->
            (-limit..limit step PLOT_FINE_GRID_STEP).mapNotNull { dz ->
                if (dx % PLOT_GRID_STEP == 0 && dz % PLOT_GRID_STEP == 0) return@mapNotNull null
                val x = site.x + dx
                val z = site.z + dz
                if (!withinSite(site, x, z)) return@mapNotNull null
                val rotation = if (abs(dx) > abs(dz)) {
                    if (dx > 0) StructureRotation.CLOCKWISE_90 else StructureRotation.COUNTERCLOCKWISE_90
                } else if (dz > 0) StructureRotation.CLOCKWISE_180 else StructureRotation.NONE
                Candidate(x, z, rotation)
            }
        }.shuffled(random)
    }

    /** 自然地形の表面に沿う4本の緩やかな曲線を作ります。 */
    private fun roads(world: World, site: OverworldSite, random: Random): Sequence<OverworldPoint> = sequence {
        val baseLength = if (site.id == OverworldSiteIds.LUMINA_PORT) 48 else 35
        val length = baseLength + random.nextInt(21)
        for ((dx, dz) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
            var bend = 0
            for (step in 0..length) {
                if (step > 0 && step % 9 == 0) bend = (bend + random.nextInt(3) - 1).coerceIn(-3, 3)
                for (side in -2..2) {
                    val x = site.x + dx * step + dz * (bend + side)
                    val z = site.z + dz * step + dx * (bend + side)
                    yield(OverworldPoint(x, surfaceY(world, x, z), z))
                }
            }
        }
    }

    private fun options(rotation: StructureRotation, swaps: Map<Material, Material>, snow: Boolean) = SchematicPlacementOptions(
        rotation = rotation, materialSwaps = swaps, extendFoundation = true, clearTreeCanopyAbove = true, protectExisting = true,
        foundationMaterials = if (snow) listOf(Material.DEEPSLATE_BRICKS, Material.COBBLESTONE) else listOf(Material.COBBLESTONE, Material.STONE_BRICKS, Material.MOSSY_STONE_BRICKS),
    )

    private fun palette(random: Random, snow: Boolean): Map<Material, Material> = when (random.nextInt(3)) {
        0 -> mapOf(Material.RED_WOOL to if (snow) Material.LIGHT_BLUE_WOOL else Material.BLUE_WOOL)
        1 -> mapOf(Material.SPRUCE_PLANKS to Material.DARK_OAK_PLANKS, Material.SPRUCE_STAIRS to Material.DARK_OAK_STAIRS, Material.SPRUCE_SLAB to Material.DARK_OAK_SLAB)
        else -> emptyMap()
    }

    /** 回転後の正面入口をワールド座標に変換します。 */
    internal fun door(data: SchematicData, building: OverworldBuildingPlan): OverworldPoint {
        val p = SchematicPlacer.transformLocalPoint(data, building.rotation, data.width / 2, minOf(1, data.height - 1), data.length - 1)
        return OverworldPoint(building.origin.x + p.x, building.origin.y + p.y, building.origin.z + p.z)
    }

    /** スキーマティック内の門と建物外の退出先を対応付けます。 */
    internal fun portal(world: World, areaId: String, dungeonId: String, data: SchematicData, building: OverworldBuildingPlan, door: OverworldPoint): OverworldPortal {
        val p = SchematicPlacer.transformLocalPoint(data, building.rotation, data.width / 2, minOf(1, data.height - 1), data.length / 3)
        val cx = building.origin.x + p.x
        val cy = building.origin.y + p.y
        val cz = building.origin.z + p.z
        val horizontalX = building.rotation == StructureRotation.NONE || building.rotation == StructureRotation.CLOCKWISE_180
        val min = OverworldPoint(cx - if (horizontalX) 1 else 0, cy, cz - if (horizontalX) 0 else 1)
        val max = OverworldPoint(cx + if (horizontalX) 1 else 0, minOf(building.origin.y + data.height - 1, cy + 4), cz + if (horizontalX) 0 else 1)
        val direction = frontDirection(building.rotation)
        val exitX = door.x + direction.first * 2
        val exitZ = door.z + direction.second * 2
        val exitY = surfaceY(world, exitX, exitZ) + 1
        require(exitY + 1 < world.maxHeight && world.getBlockAt(exitX, exitY - 1, exitZ).type.isSolid) { "Unsafe portal exit" }
        for (y in exitY..exitY + 1) {
            val type = world.getBlockAt(exitX, y, exitZ).type
            require(type.isAir || Tag.LOGS.isTagged(type) || Tag.LEAVES.isTagged(type) || type in OverworldMaterials.plants) { "Obstructed portal exit" }
        }
        return OverworldPortal(dungeonId, areaId, min, max, OverworldPoint(exitX, exitY, exitZ))
    }

    /** ローカル南向き正面の、回転後の水平ベクトルを返します。 */
    private fun frontDirection(rotation: StructureRotation): Pair<Int, Int> = when (rotation) {
            StructureRotation.NONE -> 0 to 1
            StructureRotation.CLOCKWISE_90 -> -1 to 0
            StructureRotation.CLOCKWISE_180 -> 0 to -1
            StructureRotation.COUNTERCLOCKWISE_90 -> 1 to 0
    }

    internal companion object {
        /** 拠点規模別の建築配置半径です（Tier 3最小間隔150でも隣接拠点と重なりません）。 */
        fun plotRadius(siteId: String): Int = when (siteId) {
            OverworldSiteIds.LUMINA_PORT -> 86
            OverworldSiteIds.FROSTPEAK_OUTPOST -> 72
            else -> 64
        }

        /** 建設時にチャンクチケットで保持する半径です（水域探索や道路接続を含む）。 */
        fun loadRadius(siteId: String): Int = when (siteId) {
            OverworldSiteIds.LUMINA_PORT -> 112
            OverworldSiteIds.FROSTPEAK_OUTPOST -> 96
            else -> 88
        }

        private const val BUILDING_MARGIN = 3
        private const val FINE_BUILDING_MARGIN = 2
        private const val PLOT_GRID_STEP = 8
        private const val PLOT_FINE_GRID_STEP = 4
        private const val POND_GRID_STEP = 10
        private const val GRAVITY_SUPPORT_DEPTH = 8
        private const val NATURAL_WATER_SEARCH_RADIUS = 48
        private const val MAX_VALID_SAMPLES_PER_BUILDING = 4
        private const val LUMINA_TARGET_HOUSES = 10
        private const val LUMINA_TARGET_DISTINCT_HOUSES = 5
        private const val LUMINA_MIN_HOUSES = 6
        private const val LUMINA_MIN_DISTINCT_HOUSES = 4
        private const val FROSTPEAK_TARGET_HOUSES = 5
        private const val FROSTPEAK_MIN_HOUSES = 3
        private const val RELIEF_WEIGHT = 4
        private const val LIGHTHOUSE_HEIGHT_DIVISOR = 8
        private const val WATERFRONT_DISTANCE = 8
        private const val WATERFRONT_RELIEF = 2
        private const val FALLBACK_RANDOM_SALT = 0x517cc1b727220a95L
        private const val FINE_GRID_RANDOM_SALT = 0x2545f4914f6cdd1dL
        private const val FALLBACK_PALETTE_SALT = 0x6c62272e07bb0142L
        private val allRotations = listOf(
            StructureRotation.NONE,
            StructureRotation.CLOCKWISE_90,
            StructureRotation.CLOCKWISE_180,
            StructureRotation.COUNTERCLOCKWISE_90,
        )
        private val nonDefaultRotations = listOf(
            StructureRotation.CLOCKWISE_90,
            StructureRotation.CLOCKWISE_180,
            StructureRotation.COUNTERCLOCKWISE_90,
        )
        private val dungeonIds = mapOf("entrance_novice_arena" to OverworldDungeonIds.NOVICE_ARENA, "entrance_void_sanctum" to OverworldDungeonIds.VOID_SANCTUM, "entrance_aether_crucible" to OverworldDungeonIds.AETHER_CRUCIBLE, "entrance_glacial_citadel" to OverworldDungeonIds.GLACIAL_CITADEL)
    }
}
