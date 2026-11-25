package net.nakaymi.etherealchronicle.paper

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.World
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.Directional
import org.bukkit.block.data.MultipleFacing
import org.bukkit.block.data.Orientable
import org.bukkit.block.data.Rail
import org.bukkit.block.data.Rotatable
import org.bukkit.block.data.type.RedstoneWire
import org.bukkit.block.data.type.Wall
import org.bukkit.block.structure.StructureRotation

private const val DEFAULT_FOUNDATION_DEPTH = 18
private const val MAX_FOUNDATION_DEPTH = 512

/** 建物の回転、材質と斜面への基礎を指定します。 */
data class SchematicPlacementOptions(
    val rotation: StructureRotation = StructureRotation.NONE,
    val skipAir: Boolean = false,
    val materialSwaps: Map<Material, Material> = emptyMap(),
    val extendFoundation: Boolean = false,
    val foundationMaterials: List<Material> = listOf(Material.COBBLESTONE, Material.STONE_BRICKS, Material.MOSSY_STONE_BRICKS),
    val maxFoundationDepth: Int = DEFAULT_FOUNDATION_DEPTH,
    val clearTreeCanopyAbove: Boolean = false,
    val protectExisting: Boolean = false,
) {
    init {
        require(maxFoundationDepth in 0..MAX_FOUNDATION_DEPTH)
        require(foundationMaterials.isNotEmpty() && foundationMaterials.all { it.isSolid })
    }
}

/** 回転後のスキーマチック内の座標です。 */
data class SchematicPoint(val x: Int, val y: Int, val z: Int)

/** 基礎の事前確認を分割実行し、完了後に配置手順を取得します。 */
internal class SchematicPreparation(val steps: Iterator<Unit>, val result: () -> Iterator<() -> Unit>)

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
        val preparation = prepareStepped(schematic, world, originX, originY, originZ, options, warning)
        preparation.steps.forEach { }
        return preparation.result()
    }

    /** 接地確認と足跡検査を列単位で進め、全列に成功するまで配置手順を公開しません。 */
    internal fun prepareStepped(schematic: SchematicData, world: World, originX: Int, originY: Int, originZ: Int, options: SchematicPlacementOptions,
        warning: (String, Throwable?) -> Unit = ::defaultWarning,
    ): SchematicPreparation {
        require(originY >= world.minHeight && originY.toLong() + schematic.height <= world.maxHeight.toLong())
        val states = schematic.palette
        val structureVoid = states.map { it.substringBefore('[') == "minecraft:structure_void" }
        val air = states.map(::isAir)
        val blockData = states.map { state -> resolveState(state, options, warning) }
        require(!(options.extendFoundation || options.protectExisting) || blockData.all { it != null }) {
            "Cannot validate protected placement with an unresolved palette"
        }
        val foundations = mutableListOf<FoundationColumn>()
        var complete = false
        val steps = sequence {
            if (options.extendFoundation) {
                for (z in 0 until schematic.length) for (x in 0 until schematic.width) {
                    yield(Unit)
                    val material = blockData[schematic.paletteIndexAt(z * schematic.width + x)]?.material ?: continue
                    if (!material.isSolid || material == Material.STRUCTURE_VOID) continue
                    foundations += foundationColumn(schematic, world, originX, originY, originZ, options, x, z)
                }
                require(foundations.isNotEmpty()) { "Schematic has no solid foundation blocks" }
            }
            // 建物の全列を変更前に調べ、後続セルの障害物で部分建築を残さないようにします（列単位でyieldし計画時間を抑えます）。
            if (options.protectExisting) for (z in 0 until schematic.length) for (x in 0 until schematic.width) {
                yield(Unit)
                val basePoint = transformLocalPoint(schematic, options.rotation, x, 0, z)
                val wx = originX + basePoint.x
                val wz = originZ + basePoint.z
                for (y in 0 until schematic.height) {
                    val index = schematic.paletteIndexAt(y * schematic.length * schematic.width + z * schematic.width + x)
                    if (structureVoid[index] || (options.skipAir && air[index])) continue
                    val target = blockData[index] ?: error("Unresolved protected schematic palette")
                    val block = world.getBlockAt(wx, originY + y, wz)
                    require(OverworldMaterials.replaceable(block.type) || block.blockData == target) {
                        "Schematic footprint obstructed at ${block.x},${block.y},${block.z}: ${block.type}"
                    }
                }
            }
            complete = true
        }.iterator()

        return SchematicPreparation(steps) {
            check(complete) { "Schematic foundation validation is incomplete" }
            sequence<() -> Unit> {
                foundations.forEach { column ->
                    yield {
                        require(world.getBlockAt(column.x, column.bottom - 1, column.z).type in OverworldMaterials.ground) {
                            "Foundation ground changed at ${column.x},${column.bottom - 1},${column.z}"
                        }
                    }
                }
                if (options.clearTreeCanopyAbove) {
                    val (width, length) = rotatedDimensions(schematic, options.rotation)
                    for (x in 0 until width) for (z in 0 until length) {
                        yield {
                            for (y in originY until minOf(world.maxHeight, originY + schematic.height + CANOPY_CLEARANCE)) {
                                val block = world.getBlockAt(originX + x, y, originZ + z)
                                if (Tag.LOGS.isTagged(block.type) || Tag.LEAVES.isTagged(block.type)) block.setType(Material.AIR, false)
                            }
                        }
                    }
                }
                foundations.forEach { column ->
                    for (y in column.bottom until originY) {
                        val material = options.foundationMaterials[(column.x * 31 + y * 17 + column.z).mod(options.foundationMaterials.size)]
                        yield {
                            val block = world.getBlockAt(column.x, y, column.z)
                            require(!options.protectExisting || OverworldMaterials.replaceable(block.type)) { "Foundation changed at ${column.x},$y,${column.z}" }
                            block.setType(material, false)
                        }
                    }
                }
                for (z in 0 until schematic.length) for (x in 0 until schematic.width) {
                    // 下半分を先に置いてから上半分を置き、ドアのペア状態を保ちます。
                    val placements = (0 until schematic.height).mapNotNull { y ->
                        val index = schematic.paletteIndexAt(y * schematic.length * schematic.width + z * schematic.width + x)
                        if (structureVoid[index] || (options.skipAir && air[index])) return@mapNotNull null
                        val data = blockData[index] ?: return@mapNotNull null
                        val point = transformLocalPoint(schematic, options.rotation, x, y, z)
                        if (air[index] && world.getBlockAt(originX + point.x, originY + y, originZ + point.z).type.isAir) return@mapNotNull null
                        Placement(point, data, air[index])
                    }
                    if (placements.isEmpty()) continue
                    yield {
                        placements.forEach { (point, data, isAir) ->
                            val block = world.getBlockAt(originX + point.x, originY + point.y, originZ + point.z)
                            require(!options.protectExisting || OverworldMaterials.replaceable(block.type) || block.blockData == data) {
                                "Schematic footprint obstructed at ${block.x},${block.y},${block.z}"
                            }
                            if (!isAir || !block.type.isAir) block.setBlockData(data, false)
                        }
                    }
                }
            }.iterator()
        }
    }

    private data class FoundationColumn(val x: Int, val z: Int, val bottom: Int)
    private data class Placement(val point: SchematicPoint, val data: BlockData, val isAir: Boolean)

    /** 支柱の全列を先に検証し、建造物や地盤のない場所を変更前に拒否します。 */
    private fun foundationColumn(schematic: SchematicData, world: World, ox: Int, oy: Int, oz: Int,
        options: SchematicPlacementOptions, x: Int, z: Int,
    ): FoundationColumn {
        val point = transformLocalPoint(schematic, options.rotation, x, 0, z)
        val wx = ox + point.x
        val wz = oz + point.z
        val lowest = maxOf(world.minHeight, oy - options.maxFoundationDepth - 1)
        val ground = (oy - 1 downTo lowest).firstOrNull { y ->
            val type = world.getBlockAt(wx, y, wz).type
            if (type in OverworldMaterials.ground) return@firstOrNull true
            require(OverworldMaterials.replaceable(type)) {
                "Obstructed foundation at $wx,$y,$wz: $type"
            }
            false
        }
        require(ground != null) { "No solid foundation within ${options.maxFoundationDepth} blocks at $wx,$wz" }
        return FoundationColumn(wx, wz, ground + 1)
    }

    private const val CANOPY_CLEARANCE = 8

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
            if (options.rotation != StructureRotation.NONE) rotateBlockData(result, options.rotation)
            result
        }.onFailure { warning("Cannot prepare schematic blockstate: $state", it) }.getOrNull()

    /** ? Paper ???? BlockData.rotate ?????MockBukkit ???????????????????????? */
    private fun rotateBlockData(data: BlockData, rotation: StructureRotation) {
        try {
            data.rotate(rotation)
        } catch (error: RuntimeException) {
            val isUnimplemented = error is UnsupportedOperationException ||
                error.javaClass.name == "org.mockbukkit.mockbukkit.exception.UnimplementedOperationException"
            val isMockBukkit = runCatching { Bukkit.getServer().javaClass.name.startsWith("org.mockbukkit.") }.getOrDefault(false)
            val hasOrientation = data is Directional || data is Orientable || data is Rotatable ||
                data is MultipleFacing || data is Rail || data is RedstoneWire || data is Wall
            if (!isUnimplemented || !isMockBukkit || hasOrientation) throw error
        }
    }

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
