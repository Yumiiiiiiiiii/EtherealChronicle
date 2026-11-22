package net.nakaymi.etherealchronicle.paper

import org.bukkit.Bukkit
import org.bukkit.World

/** Schematic paletteを一度だけ解決して、物理更新なしでワールドへ安全に配置します。 */
object SchematicPlacer {
    /** 空気は任意で保持し、structure_voidは常に既存ブロックを保護します。 */
    fun place(
        schematic: SchematicData,
        world: World,
        originX: Int,
        originY: Int,
        originZ: Int,
        skipAir: Boolean,
        warning: (String, Throwable?) -> Unit = { message, error ->
            Bukkit.getLogger().warning(if (error == null) message else "$message: ${error.message}")
        },
    ) {
        val states = schematic.palette
        val structureVoid = states.map { it.substringBefore('[') == "minecraft:structure_void" }
        val air = states.map(::isAir)
        val blockData = states.map { state ->
            runCatching { Bukkit.createBlockData(state) }.onFailure { warning("Unknown schematic blockstate: $state", it) }.getOrNull()
        }
        for (y in 0 until schematic.height) {
            for (z in 0 until schematic.length) {
                for (x in 0 until schematic.width) {
                    val paletteIndex = schematic.paletteIndexAt(y * schematic.length * schematic.width + z * schematic.width + x)
                    if (structureVoid[paletteIndex]) continue
                    if (skipAir && air[paletteIndex]) continue
                    val data = blockData[paletteIndex] ?: continue
                    world.getBlockAt(originX + x, originY + y, originZ + z).setBlockData(data, false)
                }
            }
        }
    }
}

/** Schematicリソースをクラスパスから取得し、配置前に一度だけデコードします。 */
internal object SchematicResources {
    private val completeWorldSet by lazy { WorldSchematicLayout.ISLANDS.all { exists(it.path) } && WorldSchematicLayout.BRIDGES.all { exists(it.path) } }

    fun exists(path: String): Boolean = SchematicResources::class.java.classLoader.getResource(path) != null

    fun load(path: String): SchematicData? = SchematicResources::class.java.classLoader.getResourceAsStream(path)?.use(SchematicLoader::load)

    fun hasCompleteWorldSet(): Boolean = completeWorldSet
}
