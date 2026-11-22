package net.nakaymi.etherealchronicle.paper

import net.nakaymi.etherealchronicle.core.Position
import net.nakaymi.etherealchronicle.core.Area
import net.nakaymi.etherealchronicle.core.WorldIds
import org.bukkit.*
import org.bukkit.entity.Monster
import org.bukkit.generator.ChunkGenerator
import org.bukkit.generator.WorldInfo
import java.nio.file.Files
import java.util.Random

/** 管理ワールドの浮島・橋を計算だけで生成し、既存のユーザーワールドに触れません。 */
class DemoGenerator: ChunkGenerator() {
    companion object {
        fun surfaceAt(wx: Int,wz: Int): Material? {
            val island=(0 until DemoWorldLayout.ISLAND_COUNT).firstOrNull { kotlin.math.abs(wx-DemoWorldLayout.FIRST_ISLAND_CENTER_X-it*DemoWorldLayout.ISLAND_CENTER_SPACING) <= DemoWorldLayout.ISLAND_HALF_WIDTH && kotlin.math.abs(wz) <= DemoWorldLayout.ISLAND_HALF_WIDTH }
            if(island != null) return when(island) {
                in 0 until DemoWorldLayout.FIRST_CHAPTER_ISLAND_COUNT -> when (wx) {
                    in DemoWorldLayout.GRASS_ISLAND_X_BAND -> Material.GRASS_BLOCK
                    in DemoWorldLayout.DEEPSLATE_ISLAND_X_BAND -> Material.DEEPSLATE_BRICKS
                    else -> Material.SMOOTH_STONE
                }
                else -> when {
                    kotlin.math.abs(wx-island*DemoWorldLayout.ISLAND_CENTER_SPACING) <= DemoWorldLayout.ICE_ISLAND_CENTER_RADIUS_BLOCKS && kotlin.math.abs(wz) <= DemoWorldLayout.ICE_ISLAND_CENTER_RADIUS_BLOCKS -> Material.BLUE_ICE
                    kotlin.math.abs(wz) <= 3 -> Material.PACKED_ICE
                    else -> Material.SNOW_BLOCK
                }
            }
            if(wx in -DemoWorldLayout.ISLAND_HALF_WIDTH..DemoWorldLayout.FIRST_CHAPTER_BRIDGE_END_X && kotlin.math.abs(wz) <= DemoWorldLayout.BRIDGE_HALF_WIDTH) return Material.SMOOTH_STONE
            if(wx in DemoWorldLayout.SECOND_CHAPTER_BRIDGE_START_X..DemoWorldLayout.SECOND_CHAPTER_BRIDGE_END_X && kotlin.math.abs(wz) <= DemoWorldLayout.BRIDGE_HALF_WIDTH) return if(wz==0 && wx%DemoWorldLayout.BRIDGE_ICE_ACCENT_INTERVAL_BLOCKS==0) Material.BLUE_ICE else Material.PACKED_ICE
            return null
        }
    }
    override fun generateNoise(info: WorldInfo,random: Random,chunkX: Int,chunkZ: Int,data: ChunkData) {
        for(x in 0..15) for(z in 0..15) {
            val wx=chunkX*16+x; val wz=chunkZ*16+z
            val surface=surfaceAt(wx,wz)
            if(surface != null) {
                data.setBlock(x,DemoWorldLayout.UNDERGROUND_Y,z,Material.BEDROCK)
                data.setBlock(x,DemoWorldLayout.SUPPORT_Y,z,if(wx >= DemoWorldLayout.SECOND_CHAPTER_MATERIAL_START_X) Material.PACKED_ICE else Material.STONE)
                data.setBlock(x,DemoWorldLayout.GROUND_Y,z,surface)
            }
        }
    }
    override fun shouldGenerateNoise() = false
    override fun shouldGenerateSurface() = false
    override fun shouldGenerateCaves() = false
    override fun shouldGenerateDecorations() = false
    override fun shouldGenerateMobs() = false
    override fun shouldGenerateStructures() = false
    override fun getFixedSpawnLocation(world: World,random: Random) = Location(world,0.5,DemoWorldLayout.SURFACE_Y.toDouble(),0.5)
}
/** 所有マーカー付きec_worldの生成・安全な追補移行・スポーン制御を担当します。 */
object DemoWorld {
    const val NAME=WorldIds.DEMO
    fun luminaSpawn(world: World) = Location(world,0.5,DemoWorldLayout.SURFACE_Y.toDouble(),0.5)
    fun isOwned(world:World):Boolean = Files.exists(world.worldFolder.toPath().resolve(".etherealchronicle-owned"))
    /** 未所有ワールドを変更せず、管理ワールドのみ必要な地形versionへ進めます。 */
    fun ensure(plugin: RpgPlugin): World? {
        val existing=Bukkit.getWorld(NAME)
        if(existing != null) {
            val marker=existing.worldFolder.toPath().resolve(".etherealchronicle-owned")
            if(!Files.exists(marker)) {
                plugin.logger.severe("ec_world exists without an ownership marker; leaving terrain unchanged: ${existing.worldFolder}")
                return null
            }
            existing.setSpawnLocation(0,DemoWorldLayout.SURFACE_Y,0)
            migrateToV2(plugin,existing,existing.worldFolder.toPath())
            migrateToV3(plugin,existing,existing.worldFolder.toPath())
            migrateToV4(plugin,existing,existing.worldFolder.toPath())
            migrateToV5(plugin,existing,existing.worldFolder.toPath())
            enforceHubSpawnSafety(plugin,existing)
            return existing
        }
        val candidates=mutableListOf(plugin.server.worldContainer.toPath().resolve(NAME))
        plugin.server.worlds.filter { it.environment == World.Environment.NORMAL && it.name != NAME }.forEach { base ->
            candidates.add(base.worldFolder.toPath().resolve("dimensions/minecraft/$NAME"))
        }
        if(candidates.any { Files.exists(it) && !Files.exists(it.resolve(".etherealchronicle-owned")) }) {
            plugin.logger.severe("ec_world is an unmanaged existing world; terrain will not be changed. Check its name or storage path.")
            return null
        }
        val world=WorldCreator(NAME).generator(DemoGenerator()).generateStructures(false).createWorld() ?: return null
        // Paper 26ではdimension worldFolderがlevel内にあるため、実際のworldFolderを所有確認の基準にします。
        val folder=world.worldFolder.toPath()
        Files.createDirectories(folder)
        val marker=folder.resolve(".etherealchronicle-owned")
        Files.writeString(marker,"EtherealChronicle demo world; never delete this marker while the world is in use.\n")
        world.setSpawnLocation(0,DemoWorldLayout.SURFACE_Y,0)
        enforceHubSpawnSafety(plugin,world)
        if(!Files.exists(folder.resolve(".etherealchronicle-built"))) {
            build(world); buildChapter2(world); buildLifeProfessionPools(world); buildSecretSpring(world)
            markFreshBuild(folder,"v4")
        plugin.logger.info("Created ec_world (terrain v4).")
        }
        migrateToV2(plugin,world,world.worldFolder.toPath())
        migrateToV3(plugin,world,world.worldFolder.toPath())
        migrateToV4(plugin,world,world.worldFolder.toPath())
        migrateToV5(plugin,world,world.worldFolder.toPath())
        return world
    }
    private fun enforceHubSpawnSafety(plugin:RpgPlugin,world:World) {
        // Bukkitの自然spawn抑止はワールド全体へ適用します。動物設定は変えず、RPG敵のCUSTOM spawnは維持します。
        disableVanillaHostileSpawns(world)
        plugin.logger.info("Disabled vanilla hostile spawns in ec_world (allowMonsters=${world.allowMonsters}).")
        val safeAreas=plugin.snapshot.world.areas.values.filter { it.safe && it.world==world.name }
        val enemyKey=NamespacedKey(plugin,"enemy_id")
        val cleared=removeSafeAreaHostiles(world,world.entities,safeAreas,enemyKey)
        if(cleared.isNotEmpty()) plugin.logger.info("Removed ${cleared.size} vanilla hostile mobs from safe zones; natural hostile spawning is disabled in ec_world.")
    }
    fun clearNearbySafeHostiles(plugin:RpgPlugin,world:World,location:Location):Int {
        if(world.name!=NAME || !runCatching { isOwned(world) }.getOrDefault(false)) return 0
        val areas=plugin.snapshot.world.areas.values.filter { it.safe && it.world==world.name }
        val nearby=world.getNearbyEntities(location,DemoWorldLayout.HOSTILE_SCAN_HORIZONTAL_BLOCKS,DemoWorldLayout.HOSTILE_SCAN_VERTICAL_BLOCKS,DemoWorldLayout.HOSTILE_SCAN_HORIZONTAL_BLOCKS)
        return removeSafeAreaHostiles(world,nearby,areas,NamespacedKey(plugin,"enemy_id")).size
    }
    private fun removeSafeAreaHostiles(world:World,entities:Iterable<org.bukkit.entity.Entity>,areas:Collection<Area>,enemyKey:NamespacedKey):List<Monster> {
        val cleared=entities.filterIsInstance<Monster>().filter { entity ->
            shouldRemoveExistingSafeHostile(true,entity.persistentDataContainer.has(enemyKey),Position(world.name,entity.location.x,entity.location.y,entity.location.z),areas)
        }
        cleared.forEach { it.remove() }
        return cleared
    }
    internal fun migrateToV2(plugin: RpgPlugin,world: World,folder: java.nio.file.Path): Boolean {
        if(!Files.exists(folder.resolve(".etherealchronicle-owned"))) return false
        val marker=folder.resolve(".etherealchronicle-built")
        if(!Files.exists(marker)) {
            plugin.logger.warning("Managed ec_world has no terrain version marker; migration skipped to protect existing blocks: $marker")
            return false
        }
        when(Files.readString(marker).trim()) {
            "v1" -> {
                buildChapter2(world)
                Files.writeString(marker,"v2\n")
                plugin.logger.info("Migrated ec_world terrain v1 to v2; Chapter 1 terrain was preserved.")
                return true
            }
            "v2", "v3", "v4", "v5" -> return true
            else -> { plugin.logger.warning("Unknown ec_world terrain version; no changes made: $marker"); return false }
        }
    }
    internal fun markFreshBuild(folder: java.nio.file.Path) {
        Files.writeString(folder.resolve(".etherealchronicle-built"),"v2\n")
    }
    internal fun markFreshBuild(folder: java.nio.file.Path,version:String) {
        require(version in setOf("v2","v3","v4","v5"))
        Files.writeString(folder.resolve(".etherealchronicle-built"),"$version\n")
    }
    internal fun migrateToV3(plugin:RpgPlugin,world:World,folder:java.nio.file.Path):Boolean {
        if(!Files.exists(folder.resolve(".etherealchronicle-owned"))) return false
        val marker=folder.resolve(".etherealchronicle-built")
        if(!Files.exists(marker)) return false
        when(Files.readString(marker).trim()) {
            "v2" -> {
                buildLifeProfessionPools(world)
                Files.writeString(marker,"v3\n")
                plugin.logger.info("Added fishing spots to ec_world (v2->v3); existing blocks were preserved.")
                return true
            }
            "v3", "v4", "v5" -> return false
            else -> { plugin.logger.warning("ec_world v2 is required before upgrading terrain to v3: $marker"); return false }
        }
    }
    internal fun migrateToV4(plugin:RpgPlugin,world:World,folder:java.nio.file.Path):Boolean {
        if(!Files.exists(folder.resolve(".etherealchronicle-owned"))) return false
        val marker=folder.resolve(".etherealchronicle-built")
        if(!Files.exists(marker)) return false
        when(Files.readString(marker).trim()) {
            "v3" -> {
                buildSecretSpring(world)
                Files.writeString(marker,"v4\n")
                plugin.logger.info("Added hidden fishing pools to ec_world (v3->v4); existing blocks were preserved.")
                return true
            }
            "v4", "v5" -> return false
            else -> { plugin.logger.warning("ec_world v3 is required before upgrading terrain to v4: $marker"); return false }
        }
    }
    /** v4所有ワールドに同梱NBTの小さな道標を安全に追加し、完了versionを記録します。 */
    internal fun migrateToV5(
        plugin:RpgPlugin,
        world:World,
        folder:java.nio.file.Path,
        placeStructure: (RpgPlugin,World,Int,Int,Int) -> Boolean = DemoStructureTemplate::placeWhenClear,
    ):Boolean {
        if(!Files.exists(folder.resolve(".etherealchronicle-owned"))) return false
        val marker=folder.resolve(".etherealchronicle-built")
        if(!Files.exists(marker)) return false
        when(Files.readString(marker).trim()) {
            "v4" -> {
                try {
                    val placed=placeStructure(plugin,world,DemoWorldLayout.WAYSTONE_X,DemoWorldLayout.WAYSTONE_Y,DemoWorldLayout.WAYSTONE_Z)
                    Files.writeString(marker,"v5\n")
                    plugin.logger.info(if(placed) "Added the Lumina waystone structure (v4->v5)." else "Kept the occupied Lumina waystone area unchanged (v4->v5).")
                    return true
                } catch(error:Exception) {
                    plugin.logger.warning("Could not migrate ec_world v4 to v5; the version marker was preserved: ${error.message}")
                    return false
                }
            }
            "v5" -> return false
            else -> { plugin.logger.warning("ec_world v4 is required before upgrading terrain to v5: $marker"); return false }
        }
    }
    internal fun buildSecretSpring(world:World) {
        val cx=DemoWorldLayout.SECRET_POOL_CENTER_X; val cz=DemoWorldLayout.SECRET_POOL_CENTER_Z
        for(dx in -DemoWorldLayout.LIFE_POOL_RADIUS..DemoWorldLayout.LIFE_POOL_RADIUS) for(dz in -DemoWorldLayout.LIFE_POOL_RADIUS..DemoWorldLayout.LIFE_POOL_RADIUS) {
            val edge=kotlin.math.abs(dx)==DemoWorldLayout.LIFE_POOL_RADIUS || kotlin.math.abs(dz)==DemoWorldLayout.LIFE_POOL_RADIUS
        // 既存地形の一段上へ建て、空気ブロックに限ることで稼働中ワールドの建築を保護します。
            val block=world.getBlockAt(cx+dx,DemoWorldLayout.SURFACE_Y,cz+dz)
            if(block.type.isAir) block.setType(if(edge) Material.PACKED_ICE else Material.WATER,false)
        }
    }
    internal fun buildLifeProfessionPools(world:World) {
        val centers=DemoWorldLayout.LIFE_POOL_CENTERS
        fun fillIfAir(x:Int,y:Int,z:Int,material:Material) {
            val block=world.getBlockAt(x,y,z)
            if(block.type.isAir) block.setType(material,false)
        }
        for((cx,cz) in centers) for(dx in -DemoWorldLayout.LIFE_POOL_RADIUS..DemoWorldLayout.LIFE_POOL_RADIUS) for(dz in -DemoWorldLayout.LIFE_POOL_RADIUS..DemoWorldLayout.LIFE_POOL_RADIUS) {
            val edge=kotlin.math.abs(dx)==DemoWorldLayout.LIFE_POOL_RADIUS || kotlin.math.abs(dz)==DemoWorldLayout.LIFE_POOL_RADIUS
            if(edge) fillIfAir(cx+dx,DemoWorldLayout.SURFACE_Y,cz+dz,Material.PACKED_ICE)
            else fillIfAir(cx+dx,DemoWorldLayout.SURFACE_Y,cz+dz,Material.WATER)
        }
    }
    private fun build(world: World) {
        // 新規生成島に限定した小規模構造物です。プレイヤーが編集した既存地形は置換しません。
        for(plaza in DemoWorldLayout.HUB_PLAZAS) {
            val center=plaza.centerX
            for(x in center-DemoWorldLayout.HUB_EDGE_RADIUS..center+DemoWorldLayout.HUB_EDGE_RADIUS) for(z in -DemoWorldLayout.HUB_EDGE_RADIUS..DemoWorldLayout.HUB_EDGE_RADIUS) if((kotlin.math.abs(x-center) == DemoWorldLayout.HUB_EDGE_RADIUS || kotlin.math.abs(z) == DemoWorldLayout.HUB_EDGE_RADIUS) && kotlin.math.abs(x-center) > DungeonArenaLayout.INNER_FLOOR_RADIUS_BLOCKS && kotlin.math.abs(z) > DungeonArenaLayout.INNER_FLOOR_RADIUS_BLOCKS) {
                world.getBlockAt(x,DemoWorldLayout.SURFACE_Y,z).type=Material.valueOf(plaza.wallMaterialName)
            }
            for(x in center-DemoWorldLayout.HUB_FLOOR_RADIUS..center+DemoWorldLayout.HUB_FLOOR_RADIUS) for(z in -DemoWorldLayout.HUB_FLOOR_RADIUS..DemoWorldLayout.HUB_FLOOR_RADIUS) world.getBlockAt(x,DemoWorldLayout.GROUND_Y,z).type=Material.valueOf(plaza.floorMaterialName)
        for(dx in listOf(-DemoWorldLayout.PILLAR_OFFSET_BLOCKS,DemoWorldLayout.PILLAR_OFFSET_BLOCKS)) for(z in listOf(-DemoWorldLayout.PILLAR_OFFSET_BLOCKS,DemoWorldLayout.PILLAR_OFFSET_BLOCKS)) {
            world.getBlockAt(center+dx,DemoWorldLayout.SURFACE_Y,z).type=Material.OAK_FENCE
            world.getBlockAt(center+dx,DemoWorldLayout.SURFACE_Y+1,z).type=Material.LANTERN
            }
        }
        for(x in DemoWorldLayout.BOARDWALK_MIN_X..DemoWorldLayout.BOARDWALK_MAX_X) for(z in DemoWorldLayout.BOARDWALK_MIN_Z..DemoWorldLayout.BOARDWALK_MAX_Z) {
            world.getBlockAt(x,DemoWorldLayout.GROUND_Y,z).type=Material.OAK_PLANKS
            if(x % DemoWorldLayout.BOARDWALK_POST_INTERVAL_BLOCKS == 0 && z in listOf(DemoWorldLayout.BOARDWALK_MIN_Z,DemoWorldLayout.BOARDWALK_MAX_Z)) for(y in DemoWorldLayout.SURFACE_Y..DemoWorldLayout.BOARDWALK_POST_TOP_Y) world.getBlockAt(x,y,z).type=Material.OAK_LOG
        }
        DemoWorldLayout.HUB_TREE_TRUNKS.forEach { trunk ->
            trunk.yRange.forEach { y -> world.getBlockAt(trunk.x, y, trunk.z).type = trunk.material }
        }
        DemoWorldLayout.HUB_TREE_CANOPIES.forEach { canopy ->
            for (x in canopy.centerX - canopy.halfWidth..canopy.centerX + canopy.halfWidth) {
                for (z in canopy.centerZ - canopy.halfWidth..canopy.centerZ + canopy.halfWidth) {
                    world.getBlockAt(x, canopy.y, z).type = canopy.material
                }
            }
        }
        DemoWorldLayout.LEGACY_LANDMARK_OUTLINES.forEach { outline ->
            for (x in outline.xRange) for (y in outline.yRange) {
                if (x == outline.xRange.first || x == outline.xRange.last || y == outline.yRange.last) {
                    world.getBlockAt(x, y, outline.zRange.first).type = outline.material
                }
            }
        }
        DemoWorldLayout.LEGACY_LANDMARK_DISKS.forEach { disk ->
            for (x in disk.centerX - disk.radius..disk.centerX + disk.radius) {
                for (z in disk.centerZ - disk.radius..disk.centerZ + disk.radius) {
                    val dx = x - disk.centerX
                    val dz = z - disk.centerZ
                    if (dx * dx + dz * dz <= disk.radius * disk.radius) {
                        world.getBlockAt(x, disk.y, z).type = disk.material
                    }
                }
            }
        }
        DemoWorldLayout.LEGACY_LANDMARK_POINTS.forEach { point ->
            world.getBlockAt(point.x, point.y, point.z).type = point.material
        }
    }
    private fun buildChapter2(world: World) {
        // 追加範囲の空きだけを橋で埋め、既存ブロックやプレイヤー建築は置き換えません。
        for(x in DemoWorldLayout.SECOND_CHAPTER_BRIDGE_START_X..DemoWorldLayout.SECOND_CHAPTER_BRIDGE_END_X) for(z in -DemoWorldLayout.BRIDGE_HALF_WIDTH..DemoWorldLayout.BRIDGE_HALF_WIDTH) {
            val floor=world.getBlockAt(x,DemoWorldLayout.GROUND_Y,z)
            if(floor.type.isAir) floor.type=if(z==0 && x%DemoWorldLayout.BRIDGE_ICE_ACCENT_INTERVAL_BLOCKS==0) Material.BLUE_ICE else Material.PACKED_ICE
            val support=world.getBlockAt(x,DemoWorldLayout.SUPPORT_Y,z)
            if(support.type.isAir) support.type=Material.PACKED_ICE
        }
        fun placeIfAir(x: Int,y: Int,z: Int,material: Material) {
            val block=world.getBlockAt(x,y,z)
            if(block.type.isAir) block.type=material
        }
        // 小規模な4つの目印を追加します。島の地表より上の空気箇所だけを変更します。
        for (x in DemoWorldLayout.CHAPTER_TWO_SHRINE_CENTER_X - DemoWorldLayout.CHAPTER_TWO_SHRINE_RADIUS_BLOCKS..DemoWorldLayout.CHAPTER_TWO_SHRINE_CENTER_X + DemoWorldLayout.CHAPTER_TWO_SHRINE_RADIUS_BLOCKS) {
            for (z in DemoWorldLayout.CHAPTER_TWO_SHRINE_CENTER_Z - DemoWorldLayout.CHAPTER_TWO_SHRINE_RADIUS_BLOCKS..DemoWorldLayout.CHAPTER_TWO_SHRINE_CENTER_Z + DemoWorldLayout.CHAPTER_TWO_SHRINE_RADIUS_BLOCKS) {
                val onEdge = kotlin.math.abs(x - DemoWorldLayout.CHAPTER_TWO_SHRINE_CENTER_X) == DemoWorldLayout.CHAPTER_TWO_SHRINE_RADIUS_BLOCKS || kotlin.math.abs(z - DemoWorldLayout.CHAPTER_TWO_SHRINE_CENTER_Z) == DemoWorldLayout.CHAPTER_TWO_SHRINE_RADIUS_BLOCKS
                if (onEdge) placeIfAir(x, DemoWorldLayout.SURFACE_Y, z, Material.PRISMARINE_BRICKS)
            }
        }
        DemoWorldLayout.CHAPTER_TWO_COLUMNS.forEach { column ->
            column.yRange.forEach { y -> placeIfAir(column.x, y, column.z, column.material) }
        }
        DemoWorldLayout.CHAPTER_TWO_TOP_BEAMS.forEach { beam ->
            for (x in beam.xRange) for (z in beam.zRange) for (y in beam.yRange) {
                placeIfAir(x, y, z, beam.material)
            }
        }
        DemoWorldLayout.CHAPTER_TWO_PYLONS.forEach { (x, z) ->
            for (y in DemoWorldLayout.SURFACE_Y..DemoWorldLayout.CHAPTER_TWO_PYLON_TOP_Y) {
                placeIfAir(x, y, z, Material.PRISMARINE_BRICKS)
            }
        }
    }
}
