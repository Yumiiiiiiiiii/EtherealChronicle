package net.nakaymi.etherealchronicle.paper

import org.bukkit.*
import org.bukkit.generator.ChunkGenerator
import org.bukkit.generator.WorldInfo
import java.nio.file.Files
import java.util.Random

class DemoGenerator: ChunkGenerator() {
    override fun generateNoise(info: WorldInfo,random: Random,chunkX: Int,chunkZ: Int,data: ChunkData) {
        for(x in 0..15) for(z in 0..15) {
            val wx=chunkX*16+x; val wz=chunkZ*16+z
            val island=(0..4).any { kotlin.math.abs(wx-it*96) <= 36 && kotlin.math.abs(wz) <= 36 }
            if(island || wx in -36..420 && kotlin.math.abs(wz) <= 3) {
                data.setBlock(x,62,z,Material.BEDROCK); data.setBlock(x,63,z,Material.STONE)
                data.setBlock(x,64,z,if(wx in 60..132) Material.GRASS_BLOCK else if(wx in 156..228) Material.DEEPSLATE_BRICKS else Material.SMOOTH_STONE)
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
object DemoWorld {
    const val NAME="ec_world"
    fun ensure(plugin: RpgPlugin): World? {
        val folder=plugin.server.worldContainer.toPath().resolve(NAME)
        val marker=folder.resolve(".etherealchronicle-owned")
        // Never load or alter an existing user world merely because its name matches.
        if(Files.exists(folder) && !Files.exists(marker)) {
            plugin.logger.severe("ec_world は既存の未管理ワールドです。地形を変更しません。別名に移動して再起動してください。")
            return null
        }
        val existing=Bukkit.getWorld(NAME)
        if(existing != null) return existing.takeIf { Files.exists(marker) }
        val world=WorldCreator(NAME).generator(DemoGenerator()).generateStructures(false).createWorld() ?: return null
        Files.writeString(marker,"EtherealChronicle demo world; never delete this marker while the world is in use.\n")
        world.setSpawnLocation(0,65,0)
        if(!Files.exists(folder.resolve(".etherealchronicle-built"))) {
            build(world)
            Files.writeString(folder.resolve(".etherealchronicle-built"),"v1\n")
        }
        return world
    }
    private fun build(world: World) {
        // Bounded, one-time structures on freshly generated islands, never user terrain.
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
}

