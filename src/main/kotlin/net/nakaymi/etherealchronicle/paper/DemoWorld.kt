package net.nakaymi.etherealchronicle.paper

import net.nakaymi.etherealchronicle.core.Position
import net.nakaymi.etherealchronicle.core.Area
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
            val island=(0..8).firstOrNull { kotlin.math.abs(wx-it*96) <= 36 && kotlin.math.abs(wz) <= 36 }
            if(island != null) return when(island) {
                in 0..4 -> if(wx in 60..132) Material.GRASS_BLOCK else if(wx in 156..228) Material.DEEPSLATE_BRICKS else Material.SMOOTH_STONE
                else -> when {
                    kotlin.math.abs(wx-island*96) <= 2 && kotlin.math.abs(wz) <= 2 -> Material.BLUE_ICE
                    kotlin.math.abs(wz) <= 3 -> Material.PACKED_ICE
                    else -> Material.SNOW_BLOCK
                }
            }
            if(wx in -36..420 && kotlin.math.abs(wz) <= 3) return Material.SMOOTH_STONE
            if(wx in 421..804 && kotlin.math.abs(wz) <= 3) return if(kotlin.math.abs(wz)==0 && wx%48==0) Material.BLUE_ICE else Material.PACKED_ICE
            return null
        }
    }
    override fun generateNoise(info: WorldInfo,random: Random,chunkX: Int,chunkZ: Int,data: ChunkData) {
        for(x in 0..15) for(z in 0..15) {
            val wx=chunkX*16+x; val wz=chunkZ*16+z
            val surface=surfaceAt(wx,wz)
            if(surface != null) {
                data.setBlock(x,62,z,Material.BEDROCK)
                data.setBlock(x,63,z,if(wx >= 444) Material.PACKED_ICE else Material.STONE)
                data.setBlock(x,64,z,surface)
            }
        }
    }
    override fun shouldGenerateNoise() = false
    override fun shouldGenerateSurface() = false
    override fun shouldGenerateCaves() = false
    override fun shouldGenerateDecorations() = false
    override fun shouldGenerateMobs() = false
    override fun shouldGenerateStructures() = false
    override fun getFixedSpawnLocation(world: World,random: Random) = Location(world,0.5,65.0,0.5)
}
/** 所有マーカー付きec_worldの生成・安全な追補移行・スポーン制御を担当します。 */
object DemoWorld {
    const val NAME="ec_world"
    fun luminaSpawn(world: World) = Location(world,0.5,65.0,0.5)
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
            existing.setSpawnLocation(0,65,0)
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
        world.setSpawnLocation(0,65,0)
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
        val nearby=world.getNearbyEntities(location,40.0,128.0,40.0)
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
                    val placed=placeStructure(plugin,world,24,65,28)
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
        val cx=124; val cz=-24
        for(dx in -3..3) for(dz in -3..3) {
            val edge=kotlin.math.abs(dx)==3 || kotlin.math.abs(dz)==3
        // 既存地形の一段上へ建て、空気ブロックに限ることで稼働中ワールドの建築を保護します。
            val block=world.getBlockAt(cx+dx,65,cz+dz)
            if(block.type.isAir) block.setType(if(edge) Material.PACKED_ICE else Material.WATER,false)
        }
    }
    internal fun buildLifeProfessionPools(world:World) {
        val centers=listOf(10 to 28,92 to 30,190 to 28,280 to 30,380 to 28,480 to 30,570 to 30,670 to 30)
        fun fillIfAir(x:Int,y:Int,z:Int,material:Material) {
            val block=world.getBlockAt(x,y,z)
            if(block.type.isAir) block.setType(material,false)
        }
        for((cx,cz) in centers) for(dx in -3..3) for(dz in -3..3) {
            val edge=kotlin.math.abs(dx)==3 || kotlin.math.abs(dz)==3
            if(edge) fillIfAir(cx+dx,65,cz+dz,Material.PACKED_ICE)
            else fillIfAir(cx+dx,65,cz+dz,Material.WATER)
        }
    }
    private fun build(world: World) {
        // 新規生成島に限定した小規模構造物です。プレイヤーが編集した既存地形は置換しません。
        for(center in listOf(0,96,192,288,384)) {
            for(x in center-12..center+12) for(z in -12..12) if((kotlin.math.abs(x-center) == 12 || kotlin.math.abs(z) == 12) && kotlin.math.abs(x-center) > 2 && kotlin.math.abs(z) > 2) {
                world.getBlockAt(x,65,z).type=if(center == 96) Material.OAK_LEAVES else Material.STONE_BRICK_WALL
            }
            for(x in center-4..center+4) for(z in -4..4) world.getBlockAt(x,64,z).type=if(center == 0) Material.QUARTZ_BRICKS else Material.POLISHED_ANDESITE
            for(dx in listOf(-8,8)) for(z in listOf(-8,8)) {
                world.getBlockAt(center+dx,65,z).type=Material.OAK_FENCE
                world.getBlockAt(center+dx,66,z).type=Material.LANTERN
            }
        }
        for(x in -20..20) for(z in 16..26) {
            world.getBlockAt(x,64,z).type=Material.OAK_PLANKS
            if(x % 10 == 0 && z in listOf(16,26)) for(y in 65..68) world.getBlockAt(x,y,z).type=Material.OAK_LOG
        }
        for(center in listOf(88,104)) for(y in 65..69) world.getBlockAt(center,y,20).type=Material.OAK_LOG
        for(center in listOf(88,104)) for(x in center-2..center+2) for(z in 18..22) world.getBlockAt(x,70,z).type=Material.OAK_LEAVES
        for(x in 184..200) for(y in 65..70) if(x in listOf(184,200) || y == 70) world.getBlockAt(x,y,-16).type=Material.DEEPSLATE_BRICKS
        for(x in 282..294) for(z in -6..6) if((x-288)*(x-288)+z*z <= 36) world.getBlockAt(x,64,z).type=Material.CALCITE
        world.getBlockAt(288,65,0).type=Material.LODESTONE
        world.getBlockAt(384,65,0).type=Material.CRYING_OBSIDIAN
    }
    private fun buildChapter2(world: World) {
        // 追加範囲の空きだけを橋で埋め、既存ブロックやプレイヤー建築は置き換えません。
        for(x in 421..804) for(z in -3..3) {
            val floor=world.getBlockAt(x,64,z)
            if(floor.type.isAir) floor.type=if(z==0 && x%48==0) Material.BLUE_ICE else Material.PACKED_ICE
            val support=world.getBlockAt(x,63,z)
            if(support.type.isAir) support.type=Material.PACKED_ICE
        }
        fun placeIfAir(x: Int,y: Int,z: Int,material: Material) {
            val block=world.getBlockAt(x,y,z)
            if(block.type.isAir) block.type=material
        }
        // 小規模な4つの目印を追加します。島の地表より上の空気箇所だけを変更します。
        for(x in 472..488) for(z in -8..8) if(kotlin.math.abs(x-480)==8 || kotlin.math.abs(z)==8) placeIfAir(x,65,z,Material.PRISMARINE_BRICKS)
        for(y in 65..72) placeIfAir(576,y,0,Material.BLUE_ICE)
        for(y in 65..68) { placeIfAir(667,y,0,Material.PACKED_ICE); placeIfAir(677,y,0,Material.PACKED_ICE) }
        for(x in 667..677) placeIfAir(x,69,0,Material.PRISMARINE_BRICKS)
        for(cx in listOf(756,780)) for(cz in listOf(-12,12)) for(y in 65..72) placeIfAir(cx,y,cz,Material.PRISMARINE_BRICKS)
    }
}


