package net.nakaymi.etherealchronicle.paper

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/** Sponge schematic の寸法・palette・ブロック配置を保持します。配列は外部へコピーして公開します。 */
class SchematicData(width: Int, height: Int, length: Int, palette: List<String>, blocks: IntArray) {
    val width: Int = width
    val height: Int = height
    val length: Int = length
    val palette: List<String> = palette.toList()
    private val blockIndices: IntArray = blocks.copyOf()
    val blocks: IntArray get() = blockIndices.copyOf()

    init {
        require(width > 0 && height > 0 && length > 0)
        require(width <= SchematicLimits.MAX_DIMENSION && height <= SchematicLimits.MAX_DIMENSION && length <= SchematicLimits.MAX_DIMENSION)
        require(blockCount(width, height, length) == blocks.size)
        require(this.palette.isNotEmpty() && blockIndices.all { it in this.palette.indices })
    }

    /** Spongeの y→z→x 並びに従って、座標のブロック状態を返します。 */
    fun blockStateAt(x: Int, y: Int, z: Int): String {
        require(x in 0 until width && y in 0 until height && z in 0 until length)
        val index = y * (length * width) + z * width + x
        return palette[paletteIndexAt(index)]
    }

    internal fun paletteIndexAt(index: Int): Int = blockIndices[index]

    companion object {
        internal fun blockCount(width: Int, height: Int, length: Int): Int =
            Math.multiplyExact(Math.multiplyExact(width, height), length)
    }
}

/** 無視する空気系状態をSponge palette表記で判定します。 */
fun isAir(state: String): Boolean = state.substringBefore('[') in SchematicLimits.AIR_BLOCKS

/** 圧縮・NBT・VarIntを検証し、Sponge v2/v3 schematicをBukkit非依存で読み込みます。 */
object SchematicLoader {
    /** GZIP NBTを読み、palette順とSponge標準座標順のまま不変データへ変換します。 */
    fun load(input: InputStream): SchematicData {
        val decompressed = GZIPInputStream(input).use { gzip ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(SchematicLimits.READ_BUFFER_BYTES)
            var total = 0
            while (true) {
                val count = gzip.read(buffer)
                if (count < 0) break
                total = Math.addExact(total, count)
                require(total <= SchematicLimits.MAX_UNCOMPRESSED_BYTES) { "Schematic NBT exceeds size limit" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val root = DataInputStream(ByteArrayInputStream(decompressed)).use(::readRootCompound)
        val schematic = (root["Schematic"] as? Map<*, *>)?.stringMap() ?: root
        val width = schematic.number("Width").toInt()
        val height = schematic.number("Height").toInt()
        val length = schematic.number("Length").toInt()
        require(width in 1..SchematicLimits.MAX_DIMENSION && height in 1..SchematicLimits.MAX_DIMENSION && length in 1..SchematicLimits.MAX_DIMENSION) {
            "Invalid schematic dimensions: ${width}x${height}x$length"
        }
        val blockCount = SchematicData.blockCount(width, height, length)
        require(blockCount <= SchematicLimits.MAX_BLOCKS) { "Schematic block count exceeds limit" }
        val blocksTag = schematic["Blocks"] as? Map<*, *>
        val paletteTag = blocksTag?.get("Palette") ?: schematic["Palette"]
        val blockData = blocksTag?.get("Data") ?: schematic["BlockData"]
        val palette = (paletteTag as? Map<*, *>)?.entries
            ?.associate { (state, index) -> (state as? String ?: error("Invalid schematic palette state")) to (index as? Number)?.toInt().orInvalid("palette index") }
            ?: error("Missing schematic palette")
        require(palette.isNotEmpty() && palette.size <= SchematicLimits.MAX_PALETTE_STATES) { "Invalid schematic palette size" }
        val paletteByIndex = arrayOfNulls<String>(palette.size)
        palette.forEach { (state, index) ->
            require(index in paletteByIndex.indices && paletteByIndex[index] == null) { "Invalid or duplicate schematic palette index" }
            paletteByIndex[index] = state
        }
        require(paletteByIndex.all { it != null }) { "Non-contiguous schematic palette" }
        val bytes = blockData as? ByteArray ?: error("Missing schematic block data")
        val indices = decodeVarInts(bytes, blockCount)
        require(indices.all { it in paletteByIndex.indices }) { "Schematic block references missing palette state" }
        return SchematicData(width, height, length, paletteByIndex.map { it!! }, indices)
    }

    private fun readRootCompound(input: DataInputStream): Map<String, Any?> {
        require(input.readUnsignedByte() == NbtTag.COMPOUND) { "Schematic root is not a compound" }
        input.readUTF() // root name is not semantically significant
        return readCompoundPayload(input, 0)
    }

    private fun readPayload(input: DataInputStream, type: Int, depth: Int): Any? {
        require(depth <= SchematicLimits.MAX_NESTING_DEPTH) { "NBT nesting limit exceeded" }
        return when (type) {
            NbtTag.BYTE -> input.readByte()
            NbtTag.SHORT -> input.readShort()
            NbtTag.INT -> input.readInt()
            NbtTag.LONG -> input.readLong()
            NbtTag.FLOAT -> input.readFloat()
            NbtTag.DOUBLE -> input.readDouble()
            NbtTag.BYTE_ARRAY -> ByteArray(readArrayLength(input, Byte.SIZE_BYTES)) { input.readByte() }
            NbtTag.STRING -> input.readUTF()
            NbtTag.LIST -> {
                val elementType = input.readUnsignedByte()
                val size = readArrayLength(input, 1)
                require(elementType in NbtTag.BYTE..NbtTag.LONG_ARRAY || size == 0) { "Invalid NBT list type" }
                List(size) { readPayload(input, elementType, depth + 1) }
            }
            NbtTag.COMPOUND -> readCompoundPayload(input, depth + 1)
            NbtTag.INT_ARRAY -> IntArray(readArrayLength(input, Int.SIZE_BYTES)) { input.readInt() }
            NbtTag.LONG_ARRAY -> LongArray(readArrayLength(input, Long.SIZE_BYTES)) { input.readLong() }
            else -> error("Unsupported NBT tag: $type")
        }
    }

    private fun readCompoundPayload(input: DataInputStream, depth: Int): Map<String, Any?> {
        require(depth <= SchematicLimits.MAX_NESTING_DEPTH) { "NBT nesting limit exceeded" }
        val values = linkedMapOf<String, Any?>()
        while (true) {
            val type = input.readUnsignedByte()
            if (type == NbtTag.END) return values
            require(type in NbtTag.BYTE..NbtTag.LONG_ARRAY) { "Unsupported NBT tag: $type" }
            val name = input.readUTF()
            require(name !in values) { "Duplicate NBT key: $name" }
            values[name] = readPayload(input, type, depth + 1)
        }
    }

    private fun readLength(input: DataInputStream): Int = input.readInt().also {
        require(it in 0..SchematicLimits.MAX_NBT_ARRAY_ELEMENTS) { "Invalid NBT collection length: $it" }
    }

    private fun readArrayLength(input: DataInputStream, minimumBytesPerEntry: Int): Int = readLength(input).also { size ->
        require(size.toLong() * minimumBytesPerEntry <= input.available()) { "Truncated NBT collection" }
    }

    private fun decodeVarInts(bytes: ByteArray, expectedCount: Int): IntArray {
        val result = IntArray(expectedCount)
        var byteIndex = 0
        for (blockIndex in result.indices) {
            var value = 0
            var shift = 0
            while (true) {
                require(byteIndex < bytes.size && shift < Int.SIZE_BITS) { "Truncated or oversized schematic VarInt" }
                val next = bytes[byteIndex++].toInt() and 0xff
                value = value or ((next and 0x7f) shl shift)
                if (next and 0x80 == 0) break
                shift += 7
            }
            require(value >= 0) { "Negative schematic palette index" }
            result[blockIndex] = value
        }
        require(byteIndex == bytes.size) { "Unexpected trailing schematic block data" }
        return result
    }

    private fun Map<*, *>.stringMap(): Map<String, Any?> = entries.associate { (key, value) ->
        (key as? String ?: error("Invalid NBT compound key")) to value
    }

    private fun Map<String, Any?>.number(key: String): Number = this[key] as? Number ?: error("Missing numeric schematic field: $key")
    private fun Number?.orInvalid(field: String): Int = this?.toInt() ?: error("Invalid schematic $field")
}

/** NBTと展開後データの上限を集約し、悪意ある圧縮入力からメモリを守ります。 */
internal object SchematicLimits {
    const val MAX_DIMENSION = 512
    const val MAX_BLOCKS = 32 * 1024 * 1024
    const val MAX_PALETTE_STATES = 65_536
    const val MAX_UNCOMPRESSED_BYTES = 160 * 1024 * 1024
    const val MAX_NBT_ARRAY_ELEMENTS = MAX_UNCOMPRESSED_BYTES
    const val READ_BUFFER_BYTES = 8 * 1024
    const val MAX_NESTING_DEPTH = 64
    val AIR_BLOCKS = setOf("minecraft:air", "minecraft:cave_air", "minecraft:void_air", "minecraft:structure_void")
}

private object NbtTag {
    const val END = 0
    const val BYTE = 1
    const val SHORT = 2
    const val INT = 3
    const val LONG = 4
    const val FLOAT = 5
    const val DOUBLE = 6
    const val BYTE_ARRAY = 7
    const val STRING = 8
    const val LIST = 9
    const val COMPOUND = 10
    const val INT_ARRAY = 11
    const val LONG_ARRAY = 12
}
