package net.nakaymi.etherealchronicle.paper

import net.nakaymi.etherealchronicle.core.OverworldSite
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
    private data class Request(val name: String, val template: String, val required: Boolean = true)
    private data class Rect(val minX: Int, val maxX: Int, val minZ: Int, val maxZ: Int) {
        fun overlaps(other: Rect): Boolean = minX <= other.maxX && maxX >= other.minX && minZ <= other.maxZ && maxZ >= other.minZ
    }

    /** 全必須建物の敷地を確定し、書き込み前に基礎と敷地衝突を検証します。 */
    fun planning(world: World, site: OverworldSite, seed: Long): OverworldTownPlanning {
        var result: OverworldTownPlan? = null
        val steps = sequence {
        val random = Random(seed xor site.id.hashCode().toLong())
        val requests = requests(site.id, random)
        val snow = site.id in coldSites
        val road = roads(world, site, random)
        val occupied = mutableListOf<Rect>()
        val buildings = mutableListOf<OverworldBuildingPlan>()
        val anchors = linkedMapOf<String, OverworldPoint>()
        val portals = mutableListOf<OverworldPortal>()
        val prepared = mutableMapOf<String, Iterator<() -> Unit>>()
        requests.forEachIndexed { index, request ->
            val schematic = assets[request.template]
            val candidates = if (index == 0) centerCandidates(site) else plotCandidates(site, random)
            var building: OverworldBuildingPlan? = null
            var bestRect: Rect? = null
            var bestRank = Int.MAX_VALUE
            var bestPreparation: SchematicPreparation? = null
            val waterFacility = request.name == "pier" || request.name == "lighthouse"
            for (candidate in candidates) {
                // 1候補ごとに制御を返し、大量の区画探索を1 tickに詰め込みません。
                yield(Unit)
                val (cx, cz, rotation) = candidate
                val (width, length) = SchematicPlacer.rotatedDimensions(schematic, rotation)
                val ox = cx - width / 2
                val oz = cz - length / 2
                val rect = Rect(ox - 2, ox + width + 1, oz - 2, oz + length + 1)
                if (!withinSite(site, rect.minX, rect.minZ) || !withinSite(site, rect.minX, rect.maxZ) ||
                    !withinSite(site, rect.maxX, rect.minZ) || !withinSite(site, rect.maxX, rect.maxZ)) continue
                if (occupied.any(rect::overlaps)) continue
                if (index > 0 && road.any { it.x in rect.minX..rect.maxX && it.z in rect.minZ..rect.maxZ }) continue
                val y = OverworldTerrain.surfaceY(world, cx, cz)
                val swaps = palette(random, snow)
                val options = options(rotation, swaps, snow)
                val preparation = runCatching { SchematicPlacer.prepareStepped(schematic, world, ox, y, oz, options) }.getOrNull() ?: continue
                var valid = true
                while (true) {
                    val hasNext = try { preparation.steps.hasNext() } catch (_: Exception) { valid = false; false }
                    if (!hasNext) break
                    preparation.steps.next()
                    yield(Unit)
                }
                if (!valid) continue
                val candidateBuilding = OverworldBuildingPlan(request.name, request.template, OverworldPoint(ox, y, oz), rotation, swaps, snow)
                val dungeon = dungeonIds[request.template]
                if (dungeon != null && runCatching { portal(world, site.id, dungeon, schematic, candidateBuilding, door(schematic, candidateBuilding)) }.isFailure) continue
                val heights = listOf(ox to oz, ox + width - 1 to oz, ox to oz + length - 1, ox + width - 1 to oz + length - 1)
                    .map { (px, pz) -> OverworldTerrain.surfaceY(world, px, pz) }
                val relief = heights.max() - heights.min()
                val waterDistance = if (waterFacility) nearbyWaterDistance(world, cx, cz) else 0
                val rank = relief * 4 + waterDistance - if (request.name == "lighthouse") y / 8 else 0
                if (rank < bestRank) {
                    bestRank = rank
                    bestRect = rect
                    building = candidateBuilding
                    bestPreparation = preparation
                }
                if ((!waterFacility && (index > 0 || relief == 0)) || (waterFacility && waterDistance <= 8 && relief <= 2)) break
            }
            if (building == null) {
                check(!request.required) { "No grounded plot for ${request.name} at ${site.id}" }
            } else {
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
                dungeonIds[request.template]?.let { dungeon -> portals += portal(world, site.id, dungeon, schematic, building, door) }
            }
        }
        if (site.id == "ec:area_lumina_port") {
            val houses = buildings.filter { it.name.startsWith("house_") }
            require(houses.size >= 10 && houses.map { it.template }.toSet().size >= 5) { "Insufficient distinct grounded plots for ${site.id}" }
        }
        if (site.id == "ec:area_frostpeak_outpost") require(buildings.count { it.name.startsWith("house_") } >= 5) {
            "Insufficient grounded plots for ${site.id}"
        }
        // 広場や建物の床を後から道で上書きしません。
        val clearRoad = road.filterNot { p -> occupied.any { p.x in it.minX..it.maxX && p.z in it.minZ..it.maxZ } }.toMutableSet()
        buildings.forEach { building ->
            val entry = anchors.getValue(building.name)
            val nearest = road.minByOrNull { abs(it.x - entry.x) + abs(it.z - entry.z) } ?: return@forEach
            val (width, length) = SchematicPlacer.rotatedDimensions(assets[building.template], building.rotation)
            val own = Rect(building.origin.x - 2, building.origin.x + width + 1, building.origin.z - 2, building.origin.z + length + 1)
            val footprint = Rect(building.origin.x, building.origin.x + width - 1, building.origin.z, building.origin.z + length - 1)
            var x = entry.x
            var z = entry.z
            while (x != nearest.x || z != nearest.z) {
                if (x != nearest.x) x += if (x < nearest.x) 1 else -1 else z += if (z < nearest.z) 1 else -1
                if (x in footprint.minX..footprint.maxX && z in footprint.minZ..footprint.maxZ) continue
                if (occupied.any { it != own && x in it.minX..it.maxX && z in it.minZ..it.maxZ }) continue
                clearRoad += OverworldPoint(x, OverworldTerrain.surfaceY(world, x, z), z)
            }
        }
        val pier = buildings.firstOrNull { it.name == "pier" }
        val (pondCenter, pond) = planPond(world, site, occupied, clearRoad, random, snow, anchors["pier"], pier?.rotation)
        anchors["fishing_water"] = pondCenter
        val heightsByPoint = clearRoad.associate { (it.x to it.z) to it.y }
        val roadChanges = clearRoad.mapIndexed { index, point ->
            val before = world.getBlockAt(point.x, point.y, point.z).type
            require(before in poolGround || before == Material.WATER) { "Road is obstructed at ${point.x},${point.y},${point.z}" }
            val uphill = listOf(Triple(1, 0, BlockFace.EAST), Triple(-1, 0, BlockFace.WEST), Triple(0, 1, BlockFace.SOUTH), Triple(0, -1, BlockFace.NORTH))
                .firstOrNull { (dx, dz, _) -> heightsByPoint[point.x + dx to point.z + dz] == point.y + 1 }?.third
            val after = when {
                before == Material.WATER -> Material.SPRUCE_PLANKS
                uphill != null -> Material.COBBLESTONE_STAIRS
                index % 7 == 0 -> Material.GRAVEL
                else -> Material.COBBLESTONE
            }
            OverworldTerrainChange(point, before, after, uphill.takeIf { after == Material.COBBLESTONE_STAIRS })
        }
        val roadClearance = roadChanges.flatMap { change ->
            val p = change.position
            (p.y + 1..minOf(p.y + 3, world.maxHeight - 1)).mapNotNull { y ->
                val before = world.getBlockAt(p.x, y, p.z).type
                if (before.isAir) null else {
                    require(Tag.LOGS.isTagged(before) || Tag.LEAVES.isTagged(before) || before in pathPlants) {
                        "Road clearance is obstructed at ${p.x},$y,${p.z}"
                    }
                    OverworldTerrainChange(OverworldPoint(p.x, y, p.z), before, Material.AIR)
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
        val lights = if (site.id in townSites) roadChanges.filterIndexed { index, _ -> index % 48 == 0 }.flatMap { roadChange ->
            val p = roadChange.position
            val position = listOf(2 to 0, -2 to 0, 0 to 2, 0 to -2).firstNotNullOfOrNull { (dx, dz) ->
                val x = p.x + dx
                val z = p.z + dz
                if (!withinSite(site, x, z)) return@firstNotNullOfOrNull null
                if (clearRoad.any { it.x == x && it.z == z } || pond.any { it.position.x == x && it.position.z == z } ||
                    occupied.any { x in it.minX..it.maxX && z in it.minZ..it.maxZ }) return@firstNotNullOfOrNull null
                val y = OverworldTerrain.surfaceY(world, x, z)
                if (y + 2 >= world.maxHeight || !world.getBlockAt(x, y, z).type.isSolid ||
                    !world.getBlockAt(x, y + 1, z).type.isAir || !world.getBlockAt(x, y + 2, z).type.isAir) null else OverworldPoint(x, y, z)
            }
            if (position == null) emptyList() else listOf(
                OverworldTerrainChange(OverworldPoint(position.x, position.y + 1, position.z), Material.AIR, Material.SPRUCE_FENCE),
                OverworldTerrainChange(OverworldPoint(position.x, position.y + 2, position.z), Material.AIR, Material.LANTERN))
        } else emptyList()
        val decorations = decorations(world, site, occupied, clearRoad, pond, snow).filterNot { decoration ->
            lights.any { it.position == decoration.position }
        }
        result = OverworldTownPlan(site, buildings, roadChanges, roadClearance, pond, lights, decorations, anchors, portals, prepared)
        }.iterator()
        return OverworldTownPlanning(steps) { checkNotNull(result) { "Town planning is incomplete" } }
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
            check(block.type == change.before || block.type.isAir) { "Road clearance changed at ${p.x},${p.y},${p.z}" }
            block.setType(Material.AIR, false)
        } }
        plan.road.forEach { change ->
            yield {
                val p = change.position
                val block = world.getBlockAt(p.x, p.y, p.z)
                check(block.type == change.before || block.type == change.after) { "Road terrain changed at ${p.x},${p.y},${p.z}" }
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
            check(block.type == change.before || block.type == change.after) { "Fishing pool terrain changed at ${p.x},${p.y},${p.z}" }
            block.setType(change.after, false)
        } }
        plan.lights.forEach { change -> yield {
            val p = change.position
            val block = world.getBlockAt(p.x, p.y, p.z)
            check(block.type.isAir || block.type == change.after) { "Lantern location changed at ${p.x},${p.y},${p.z}" }
            block.setType(change.after, false)
        } }
        plan.decorations.forEach { change -> yield {
            val p = change.position
            val block = world.getBlockAt(p.x, p.y, p.z)
            check(block.type.isAir || block.type == change.after) { "Decoration location changed at ${p.x},${p.y},${p.z}" }
            block.setType(change.after, false)
        } }
    }.iterator()

    /** 雪や水晶の目印は建物・道・池を避け、空きブロックにだけ加えます。 */
    private fun decorations(world: World, site: OverworldSite, occupied: List<Rect>, road: Set<OverworldPoint>, pond: List<OverworldTerrainChange>, snow: Boolean): List<OverworldTerrainChange> {
        if (!snow && site.id != "ec:area_void_sanctum" && site.id != "ec:area_crystalline_cavern") return emptyList()
        val offsets = listOf(-30 to -30, -30 to 0, -30 to 30, 0 to -30, 0 to 30, 30 to -30, 30 to 0, 30 to 30)
        return offsets.mapIndexedNotNull { index, (dx, dz) ->
            val x = site.x + dx
            val z = site.z + dz
            if (occupied.any { x in it.minX..it.maxX && z in it.minZ..it.maxZ } || road.any { it.x == x && it.z == z } ||
                pond.any { it.position.x == x && it.position.z == z }) return@mapIndexedNotNull null
            val y = OverworldTerrain.surfaceY(world, x, z)
            if (y + 1 >= world.maxHeight || !world.getBlockAt(x, y, z).type.isSolid || !world.getBlockAt(x, y + 1, z).type.isAir) return@mapIndexedNotNull null
            val material = when (site.id) {
                "ec:area_void_sanctum" -> if (index % 2 == 0) Material.OBSIDIAN else Material.AMETHYST_BLOCK
                "ec:area_crystalline_cavern" -> if (index % 2 == 0) Material.AMETHYST_BLOCK else Material.PACKED_ICE
                else -> Material.SNOW
            }
            OverworldTerrainChange(OverworldPoint(x, y + 1, z), Material.AIR, material)
        }
    }

    /** 他の敷地と重ならない平坦地を探し、池の全ブロックを先に検査します。 */
    private fun planPond(world: World, site: OverworldSite, occupied: List<Rect>, road: Set<OverworldPoint>, random: Random, snow: Boolean, near: OverworldPoint?, facing: StructureRotation?): Pair<OverworldPoint, List<OverworldTerrainChange>> {
        val radius = if (near != null) 4 else 2
        val shuffled = (-60..60 step 12).flatMap { dx -> (-60..60 step 12).map { dz -> site.x + dx to site.z + dz } }.shuffled(random)
        val candidates = if (near == null || facing == null) shuffled else {
            val (dx, dz) = frontDirection(facing)
            shuffled.sortedBy { (x, z) ->
                val along = (x - near.x) * dx + (z - near.z) * dz
                val across = abs((x - near.x) * dz - (z - near.z) * dx)
                abs(along) + across * 3 + if (along < 0) 1000 else 0
            }
        }
        return candidates.firstNotNullOfOrNull { (cx, cz) ->
            val bounds = Rect(cx - radius - 1, cx + radius + 1, cz - radius - 1, cz + radius + 1)
            if (!withinSite(site, bounds.minX, bounds.minZ) || !withinSite(site, bounds.minX, bounds.maxZ) ||
                !withinSite(site, bounds.maxX, bounds.minZ) || !withinSite(site, bounds.maxX, bounds.maxZ)) return@firstNotNullOfOrNull null
            if (occupied.any(bounds::overlaps) || road.any { it.x in bounds.minX..bounds.maxX && it.z in bounds.minZ..bounds.maxZ }) return@firstNotNullOfOrNull null
            val cells = (-radius..radius).flatMap { dx -> (-radius..radius).mapNotNull { dz -> if (dx * dx + dz * dz <= radius * radius + 1) cx + dx to cz + dz else null } }
            val heights = cells.map { (x, z) -> OverworldTerrain.surfaceY(world, x, z) }
            if (heights.max() - heights.min() > 1) return@firstNotNullOfOrNull null
            val y = heights.min()
            val changes = mutableListOf<OverworldTerrainChange>()
            for ((cell, top) in cells.zip(heights)) {
                val (x, z) = cell
                for (py in y - 1..top) {
                    val before = world.getBlockAt(x, py, z).type
                    if (before !in poolGround && !before.isAir && before != Material.WATER) return@firstNotNullOfOrNull null
                    val after = when {
                        py < y -> if (snow) Material.PACKED_ICE else Material.GRAVEL
                        py == y -> Material.WATER
                        else -> Material.AIR
                    }
                    changes += OverworldTerrainChange(OverworldPoint(x, py, z), before, after)
                }
            }
            OverworldPoint(cx, y, cz) to changes
        } ?: error("No safe fishing pool site for ${site.id}")
    }

    /** 地域別の構成と個別住宅をシード順で選びます。 */
    private fun requests(id: String, random: Random): List<Request> {
        fun request(name: String, template: String, required: Boolean = true) = Request(name, template, required)
        val temperate = listOf("house_timber_small_a", "house_timber_small_b", "house_coastal_cottage", "house_l_shaped_manor", "house_stone_bakery", "house_alchemist_tower", "house_snow_cabin_a", "house_snow_longhouse")
        fun homes(templates: List<String>, count: Int): List<Request> {
            val shuffled = templates.shuffled(random)
            return List(count) { i -> request("house_$i", if (i < shuffled.size) shuffled[i] else templates[random.nextInt(templates.size)], false) }
        }
        return when (id) {
            "ec:area_lumina_port" -> listOf(request("plaza", "civic_market_plaza"), request("guild_hall", "civic_guild_hall"), request("blacksmith", "civic_blacksmith_forge"), request("tavern", "civic_tavern_inn"), request("lighthouse", "civic_lighthouse"), request("pier", "civic_harbor_pier"), request("watchtower", "civic_watchtower"), request("waystone", "prop_waystone"), request("well", "prop_well"), request("stall", "prop_stall_a", false)) + homes(temperate, 10 + random.nextInt(5))
            "ec:area_frostpeak_outpost" -> listOf(request("command_hall", "civic_frost_command_hall"), request("watchtower", "civic_watchtower"), request("watchtower_2", "civic_watchtower"), request("plaza", "civic_market_plaza"), request("waystone", "prop_waystone"), request("well", "prop_well", false)) + homes(listOf("house_snow_cabin_a", "house_snow_longhouse", "house_timber_small_a", "house_timber_small_b"), 5 + random.nextInt(4))
            "ec:area_ashwood_forest" -> listOf(request("camp", "landmark_ashwood_camp"), request("entrance", "entrance_novice_arena"), request("waystone", "prop_waystone")) + homes(temperate.take(3), 2)
            "ec:area_weeping_mine" -> listOf(request("mine", "landmark_mine_headframe"), request("waystone", "prop_waystone")) + homes(listOf("house_timber_small_b", "house_stone_bakery"), 2)
            "ec:area_stargazer_hill" -> listOf(request("observatory", "landmark_stargazer_observatory"), request("entrance", "entrance_aether_crucible"), request("waystone", "prop_waystone"), request("tower", "house_alchemist_tower"))
            "ec:area_void_sanctum" -> listOf(request("entrance", "entrance_void_sanctum"), request("waystone", "prop_waystone"))
            "ec:area_glacier_ridge", "ec:area_crystalline_cavern" -> listOf(request("watchtower", "civic_watchtower"), request("cabin", "house_snow_cabin_a"), request("waystone", "prop_waystone"))
            "ec:area_glacial_citadel" -> listOf(request("entrance", "entrance_glacial_citadel"), request("watchtower", "civic_watchtower"), request("watchtower_2", "civic_watchtower"), request("waystone", "prop_waystone"))
            else -> error("Unknown overworld site: $id")
        }
    }

    private data class Candidate(val x: Int, val z: Int, val rotation: StructureRotation)

    /** Tier 3の120ブロック間隔でも、隣接拠点の建築が重ならない半径に収めます。 */
    private fun withinSite(site: OverworldSite, x: Int, z: Int): Boolean {
        val dx = x.toLong() - site.x
        val dz = z.toLong() - site.z
        return dx * dx + dz * dz <= 59L * 59
    }

    /** 灯台と桟橋は近くの自然水域を優先し、なければ人工の池へフォールバックします。 */
    private fun nearbyWaterDistance(world: World, x: Int, z: Int): Int {
        val directions = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1, 1 to 1, 1 to -1, -1 to 1, -1 to -1)
        for (distance in listOf(8, 16, 24)) for ((dx, dz) in directions) {
            val px = x + dx * distance
            val pz = z + dz * distance
            val y = OverworldTerrain.surfaceY(world, px, pz)
            if (world.getBlockAt(px, y, pz).type == Material.WATER) return distance
        }
        return 64
    }

    /** 重要施設は中心近くを小刻みに探索します。 */
    private fun centerCandidates(site: OverworldSite): List<Candidate> =
        (-24..24 step 4).flatMap { dx -> (-24..24 step 4).map { dz -> Candidate(site.x + dx, site.z + dz, StructureRotation.NONE) } }
            .sortedBy { abs(it.x - site.x) + abs(it.z - site.z) }

    /** 敷地候補を四方向に並べ、入口を中心側へ向けます。 */
    private fun plotCandidates(site: OverworldSite, random: Random): List<Candidate> =
        (-60..60 step 12).flatMap { dx -> (-60..60 step 12).mapNotNull { dz ->
            if (abs(dx) < 12 && abs(dz) < 12) return@mapNotNull null
            val rotation = if (abs(dx) > abs(dz)) {
                if (dx > 0) StructureRotation.CLOCKWISE_90 else StructureRotation.COUNTERCLOCKWISE_90
            } else if (dz > 0) StructureRotation.CLOCKWISE_180 else StructureRotation.NONE
            Candidate(site.x + dx, site.z + dz, rotation)
        } }.shuffled(random)

    /** 自然地形の表面に沿う4本の緩やかな曲線を作ります。 */
    private fun roads(world: World, site: OverworldSite, random: Random): List<OverworldPoint> {
        val points = linkedSetOf<OverworldPoint>()
        val length = 35 + random.nextInt(21)
        for ((dx, dz) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
            var bend = 0
            for (step in 0..length) {
                if (step > 0 && step % 9 == 0) bend = (bend + random.nextInt(3) - 1).coerceIn(-3, 3)
                for (side in -1..1) {
                    val x = site.x + dx * step + dz * (bend + side)
                    val z = site.z + dz * step + dx * (bend + side)
                    points += OverworldPoint(x, OverworldTerrain.surfaceY(world, x, z), z)
                }
            }
        }
        return points.toList()
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

    private fun door(data: SchematicData, building: OverworldBuildingPlan): OverworldPoint {
        val p = SchematicPlacer.transformLocalPoint(data, building.rotation, data.width / 2, minOf(1, data.height - 1), data.length - 1)
        return OverworldPoint(building.origin.x + p.x, building.origin.y + p.y, building.origin.z + p.z)
    }

    private fun portal(world: World, areaId: String, dungeonId: String, data: SchematicData, building: OverworldBuildingPlan, door: OverworldPoint): OverworldPortal {
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
        val exitY = OverworldTerrain.surfaceY(world, exitX, exitZ) + 1
        require(exitY + 1 < world.maxHeight && world.getBlockAt(exitX, exitY - 1, exitZ).type.isSolid) { "Unsafe portal exit" }
        for (y in exitY..exitY + 1) {
            val type = world.getBlockAt(exitX, y, exitZ).type
            require(type.isAir || Tag.LOGS.isTagged(type) || Tag.LEAVES.isTagged(type) || type in pathPlants) { "Obstructed portal exit" }
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

    private companion object {
        val coldSites = setOf("ec:area_frostpeak_outpost", "ec:area_glacier_ridge", "ec:area_crystalline_cavern", "ec:area_glacial_citadel")
        val townSites = setOf("ec:area_lumina_port", "ec:area_frostpeak_outpost")
        val poolGround = setOf(Material.GRASS_BLOCK, Material.DIRT, Material.COARSE_DIRT, Material.PODZOL, Material.STONE,
            Material.SAND, Material.GRAVEL, Material.CLAY, Material.SNOW_BLOCK, Material.SNOW, Material.ICE, Material.PACKED_ICE, Material.MUD)
        val pathPlants = setOf(Material.SHORT_GRASS, Material.TALL_GRASS, Material.FERN, Material.LARGE_FERN, Material.SNOW)
        val dungeonIds = mapOf("entrance_novice_arena" to "ec:dungeon_novice_arena", "entrance_void_sanctum" to "ec:dungeon_void_sanctum", "entrance_aether_crucible" to "ec:dungeon_aether_crucible", "entrance_glacial_citadel" to "ec:dungeon_glacial_citadel")
    }
}
