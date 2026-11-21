package net.nakaymi.etherealchronicle.paper

import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.structure.Mirror
import org.bukkit.block.structure.StructureRotation
import org.bukkit.util.BlockVector
import java.util.Random

/** Paper StructureManagerで同梱NBTを読み、空き範囲だけへ配置します。 */
object DemoStructureTemplate {
    const val RESOURCE = "structures/lumina_waystone.nbt"
    private const val WIDTH = 5
    private const val HEIGHT = 4
    private const val DEPTH = 1

    /** 構造物の全セルが空気の場合だけ配置して、プレイヤーの建築を保護します。 */
    fun placeWhenClear(plugin: RpgPlugin, world: World, x: Int, y: Int, z: Int): Boolean {
        if (!clearFootprint(WIDTH, HEIGHT, DEPTH) { dx, dy, dz -> world.getBlockAt(x + dx, y + dy, z + dz).type.isAir }) {
            return false
        }
        val template = plugin.getResource(RESOURCE)?.use { input ->
            plugin.server.structureManager.loadStructure(input)
        } ?: error("Bundled structure resource is missing: $RESOURCE")
        val size = template.size
        require(size.blockX == WIDTH && size.blockY == HEIGHT && size.blockZ == DEPTH) {
            "Unexpected structure dimensions for $RESOURCE: ${size.blockX}x${size.blockY}x${size.blockZ}"
        }
        template.place(
            Location(world, x.toDouble(), y.toDouble(), z.toDouble()),
            false,
            StructureRotation.NONE,
            Mirror.NONE,
            0,
            1.0f,
            Random(0L),
        )
        return true
    }

    /** サイズとセル判定を分離して、移行が上書きを避ける条件を純粋に検証します。 */
    internal fun clearFootprint(width: Int, height: Int, depth: Int, isAir: (Int, Int, Int) -> Boolean): Boolean {
        if (width !in 1..48 || height !in 1..48 || depth !in 1..48) return false
        for (dx in 0 until width) for (dy in 0 until height) for (dz in 0 until depth) {
            if (!isAir(dx, dy, dz)) return false
        }
        return true
    }
}
