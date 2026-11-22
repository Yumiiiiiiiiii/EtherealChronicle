package net.nakaymi.etherealchronicle.paper

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.World
import org.bukkit.block.data.BlockData
import org.bukkit.block.structure.StructureRotation

/** 建物の回転、材質と斜面への基礎を指定します。 */
data class SchematicPlacementOptions(
    val rotation: StructureRotation = StructureRotation.NONE,
    val skipAir: Boolean = false,
    val materialSwaps: Map<Material, Material> = emptyMap(),
    val extendFoundation: Boolean = false,
    val foundationMaterials: List<Material> = listOf(Material.COBBLESTONE, Material.STONE_BRICKS, Material.MOSSY_STONE_BRICKS),
    val maxFoundationDepth: Int = 18,
    val clearTreeCanopyAbove: Boolean = false,
    val protectExisting: Boolean = false,
) {
    init {
        require(maxFoundationDepth in 0..512)
        require(foundationMaterials.isNotEmpty() && foundationMaterials.all { it.isSolid })
    }
}

/** 回転後のスキーマチック内の座標です。 */
data class SchematicPoint(val x: Int, val y: Int, val z: Int)

/** パレットを一度だけ解決し、物理更新なしで配置します。 */
object SchematicPlacer {
    /** 回転後の横幅と奥行きを返します。 */
    fun rotatedDimensions(schematic: SchematicData, rotation: StructureRotation): Pair<Int, Int> = when (rotation) {
        StructureRotation.CLOCKWISE_90, StructureRotation.COUNTERCLOCKWISE_90 -> schematic.length to schematic.width
        else -> schematic.width to schematic.length
    }

    /** 原点を回転後の境界の最小座標としたローカル座標へ変換します。 */
    fun transformLocalPoint(schematic: SchematicData, rotation: StructureRotation, lx: Int, ly: Int, lz: Int): SchematicPoint {
        require(lx in 0 until schematic.width && ly in 0 until schematic.height && lz in 0 until schematic.length)
        return when (rotation) {
            StructureRotation.NONE -> SchematicPoint(lx, ly, lz)
            StructureRotation.CLOCKWISE_90 -> SchematicPoint(schematic.length - 1 - lz, ly, lx)
            StructureRotation.CLOCKWISE_180 -> SchematicPoint(schematic.width - 1 - lx, ly, schematic.length - 1 - lz)
            StructureRotation.COUNTERCLOCKWISE_90 -> SchematicPoint(lz, ly, schematic.width - 1 - lx)
        }
    }

    /** 旧呼び出し元との互換性を保って配置します。 */
    fun place(schematic: SchematicData, world: World, originX: Int, originY: Int, originZ: Int, skipAir: Boolean,
        warning: (String, Throwable?) -> Unit = ::defaultWarning,
    ) = place(schematic, world, originX, originY, originZ, SchematicPlacementOptions(skipAir = skipAir), warning)

    /** 配置前に全基礎の接地を確認し、不適地ではブロックを一切変更しません。 */
    fun place(schematic: SchematicData, world: World, originX: Int, originY: Int, originZ: Int, options: SchematicPlacementOptions,
        warning: (String, Throwable?) -> Unit = ::defaultWarning,
    ) {
        prepare(schematic, world, originX, originY, originZ, options, warning).forEach { it() }
    }

    /** メインスレッドで事前検証し、tickごとに上限付きで実行できる配置手順を返します。 */
    fun prepare(schematic: SchematicData, world: World, originX: Int, originY: Int, originZ: Int, options: SchematicPlacementOptions,
        warning: (String, Throwable?) -> Unit = ::defaultWarning,
    ): Iterator<() -> Unit> {
        require(originY >= world.minHeight && originY.toLong() + schematic.height <= world.maxHeight.toLong())
        val states = schematic.palette
        val structureVoid = states.map { it.substringBefore('[') == "minecraft:structure_void" }
        val air = states.map(::isAir)
        val blockData = states.map { state -> resolveState(state, options, warning) }
        require(!options.extendFoundation || blockData.all { it != null }) { "Cannot validate foundation with an unresolved palette" }
        val foundations = if (options.extendFoundation) foundationColumns(schematic, world, originX, originY, originZ, options, blockData) else emptyList()

        return sequence<() -> Unit> {
            if (options.clearTreeCanopyAbove) {
                val (width, length) = rotatedDimensions(schematic, options.rotation)
                for (x in 0 until width) for (z in 0 until length) for (y in originY until minOf(world.maxHeight, originY + schematic.height + 8)) {
                    yield {
                        val block = world.getBlockAt(originX + x, y, originZ + z)
                        if (Tag.LOGS.isTagged(block.type) || Tag.LEAVES.isTagged(block.type)) block.setType(Material.AIR, false)
                    }
                }
            }
            foundations.forEach { column ->
                for (y in column.bottom until originY) {
                    val material = options.foundationMaterials[Math.floorMod(column.x * 31 + y * 17 + column.z, options.foundationMaterials.size)]
                    yield {
                        val block = world.getBlockAt(column.x, y, column.z)
                        require(!options.protectExisting || isNaturalOrReplaceable(block.type)) { "Foundation changed at ${column.x},$y,${column.z}" }
                        block.setType(material, false)
                    }
                }
            }
            for (y in 0 until schematic.height) for (z in 0 until schematic.length) for (x in 0 until schematic.width) {
                val paletteIndex = schematic.paletteIndexAt(y * schematic.length * schematic.width + z * schematic.width + x)
                if (structureVoid[paletteIndex] || (options.skipAir && air[paletteIndex])) continue
                val data = blockData[paletteIndex] ?: continue
                val point = transformLocalPoint(schematic, options.rotation, x, y, z)
                yield {
                    val block = world.getBlockAt(originX + point.x, originY + y, originZ + point.z)
                    require(!options.protectExisting || isNaturalOrReplaceable(block.type) || block.blockData == data) {
                        "Schematic footprint obstructed at ${block.x},${block.y},${block.z}"
                    }
                    if (!air[paletteIndex] || !block.type.isAir) block.setBlockData(data, false)
                }
            }
        }.iterator()
    }

    private data class FoundationColumn(val x: Int, val z: Int, val bottom: Int)

    /** 支柱の全列を先に検証し、建造物や地盤のない場所を変更前に拒否します。 */
    private fun foundationColumns(schematic: SchematicData, world: World, ox: Int, oy: Int, oz: Int,
        options: SchematicPlacementOptions, data: List<BlockData?>,
    ): List<FoundationColumn> {
        val columns = mutableListOf<FoundationColumn>()
        for (z in 0 until schematic.length) for (x in 0 until schematic.width) {
            val material = data[schematic.paletteIndexAt(z * schematic.width + x)]?.material ?: continue
            if (!material.isSolid || material == Material.STRUCTURE_VOID) continue
            val point = transformLocalPoint(schematic, options.rotation, x, 0, z)
            val wx = ox + point.x
            val wz = oz + point.z
            val lowest = maxOf(world.minHeight, oy - options.maxFoundationDepth - 1)
            val ground = (oy - 1 downTo lowest).firstOrNull { y ->
                val type = world.getBlockAt(wx, y, wz).type
                if (type in naturalGround) return@firstOrNull true
                require(type.isAir || type == Material.WATER || Tag.LOGS.isTagged(type) || Tag.LEAVES.isTagged(type) || type in naturalPlants) {
                    "Obstructed foundation at $wx,$y,$wz: $type"
                }
                false
            }
            require(ground != null) { "No solid foundation within ${options.maxFoundationDepth} blocks at $wx,$wz" }
            columns += FoundationColumn(wx, wz, ground + 1)
        }
        require(columns.isNotEmpty()) { "Schematic has no solid foundation blocks" }
        return columns
    }

    private val naturalGround = setOf(
        Material.STONE, Material.DEEPSLATE, Material.DIRT, Material.GRASS_BLOCK, Material.COARSE_DIRT,
        Material.ROOTED_DIRT, Material.PODZOL, Material.MYCELIUM, Material.MUD, Material.CLAY,
        Material.SAND, Material.RED_SAND, Material.GRAVEL, Material.SANDSTONE, Material.RED_SANDSTONE,
        Material.GRANITE, Material.DIORITE, Material.ANDESITE, Material.TUFF, Material.CALCITE,
        Material.SNOW_BLOCK, Material.ICE, Material.PACKED_ICE, Material.BLUE_ICE, Material.TERRACOTTA,
        Material.WHITE_TERRACOTTA, Material.ORANGE_TERRACOTTA, Material.YELLOW_TERRACOTTA,
        Material.RED_TERRACOTTA, Material.BROWN_TERRACOTTA, Material.LIGHT_GRAY_TERRACOTTA,
    )
    private val naturalPlants = setOf(Material.SHORT_GRASS, Material.TALL_GRASS, Material.FERN, Material.LARGE_FERN, Material.SNOW)

    /** ブロック種別から自然物と判定できないものは、既存建造物の可能性があるため保護します。 */
    private fun isNaturalOrReplaceable(type: Material): Boolean =
        type.isAir || type == Material.WATER || type in naturalGround || type in naturalPlants || Tag.LOGS.isTagged(type) || Tag.LEAVES.isTagged(type)

    /** 元の状態を解決し、共通プロパティを移した後で向きを回転します。 */
    private fun resolveState(state: String, options: SchematicPlacementOptions, warning: (String, Throwable?) -> Unit): BlockData? =
        runCatching {
            val original = runCatching { Bukkit.createBlockData(state) }.recoverCatching { error ->
                val baseId = state.substringBefore('[')
                if (baseId == state) throw error
                warning("Unknown schematic blockstate: $state", error)
                Bukkit.createBlockData(baseId)
            }.getOrThrow()
            val target = options.materialSwaps[original.material]
            val result = if (target == null) original else Bukkit.createBlockData(target).also { original.copyTo(it) }
            if (options.rotation != StructureRotation.NONE) result.rotate(options.rotation)
            result
        }.onFailure { warning("Cannot prepare schematic blockstate: $state", it) }.getOrNull()

    private fun defaultWarning(message: String, error: Throwable?) {
        Bukkit.getLogger().warning(if (error == null) message else "$message: ${error.message}")
    }
}

/** Schematicリソースをクラスパスから取得し、配置前に一度だけデコードします。 */
internal object SchematicResources {
    private val completeWorldSet by lazy { WorldSchematicLayout.ISLANDS.all { exists(it.path) } && WorldSchematicLayout.BRIDGES.all { exists(it.path) } }
    fun exists(path: String): Boolean = SchematicResources::class.java.classLoader.getResource(path) != null
    fun load(path: String): SchematicData? = SchematicResources::class.java.classLoader.getResourceAsStream(path)?.use(SchematicLoader::load)
    fun hasCompleteWorldSet(): Boolean = completeWorldSet
}
