package net.nakaymi.etherealchronicle.core

/** 同一ワールドのインスタンス枠と高さを検証可能な値として管理します。 */
data class OverworldDungeonInstanceLayout(
    val baseX: Int = 100_000,
    val baseY: Int = 220,
    val baseZ: Int = 100_000,
    val spacing: Int = 256,
) {
    init {
        require(spacing >= ARENA_WIDTH) { "Dungeon instance spacing is smaller than the arena" }
    }

    /** スロット間の重複と整数オーバーフローを避けて中心位置を返します。 */
    fun center(slot: Int): DungeonInstanceCenter {
        require(slot in 0 until MAX_CANDIDATE_SLOTS) { "Dungeon instance slot is outside the candidate range" }
        val x = Math.addExact(baseX, Math.multiplyExact(slot, spacing))
        require(x.toLong() - ARENA_RADIUS >= -WORLD_LIMIT && x.toLong() + ARENA_RADIUS <= WORLD_LIMIT)
        require(baseZ.toLong() - ARENA_RADIUS >= -WORLD_LIMIT && baseZ.toLong() + ARENA_RADIUS <= WORLD_LIMIT)
        return DungeonInstanceCenter(x, baseY, baseZ)
    }

    /** schematic 全体がワールドの高さ制限内に収まるか確認します。 */
    fun fitsHeight(minHeight: Int, maxHeight: Int): Boolean {
        val bottom = baseY.toLong() - FLOOR_OFFSET
        return bottom >= minHeight && bottom + ARENA_HEIGHT <= maxHeight
    }

    /** 使用中枠を除外し、既存ブロックのない最初の枠を選びます。 */
    fun findAvailableSlot(occupied: Set<Int>, candidates: IntRange, isEmpty: (DungeonInstanceCenter) -> Boolean): Int? {
        val first = candidates.first.coerceAtLeast(0)
        val last = candidates.last.coerceAtMost(MAX_CANDIDATE_SLOTS - 1)
        if (candidates.isEmpty() || first > last) return null
        return (first..last).firstOrNull { slot -> slot !in occupied && isEmpty(center(slot)) }
    }

    /** 検査・配置の双方で同じ範囲と走査順を使います。 */
    fun bounds(slot: Int): DungeonInstanceBounds {
        val center = center(slot)
        return DungeonInstanceBounds(center.x - ARENA_RADIUS, Math.subtractExact(center.y, FLOOR_OFFSET), center.z - ARENA_RADIUS)
    }

    companion object {
        const val MAX_CANDIDATE_SLOTS = 16
        const val ARENA_WIDTH = 55
        const val ARENA_RADIUS = 27
        const val ARENA_HEIGHT = 34
        const val FLOOR_OFFSET = 10
        private const val WORLD_LIMIT = 29_999_999L
    }
}

/** ダンジョンの床中心を表します。 */
data class DungeonInstanceCenter(val x: Int, val y: Int, val z: Int)

/** schematic 全体のブロック座標とチャンク範囲を表します。 */
data class DungeonInstanceBounds(val minX: Int, val minY: Int, val minZ: Int) {
    val maxX: Int = Math.addExact(minX, OverworldDungeonInstanceLayout.ARENA_WIDTH - 1)
    val maxY: Int = Math.addExact(minY, OverworldDungeonInstanceLayout.ARENA_HEIGHT - 1)
    val maxZ: Int = Math.addExact(minZ, OverworldDungeonInstanceLayout.ARENA_WIDTH - 1)
    val chunkX: IntRange get() = (minX shr 4)..(maxX shr 4)
    val chunkZ: IntRange get() = (minZ shr 4)..(maxZ shr 4)

    /** Sponge schematic と同じ y→z→x 順で単一ブロックを取り出します。 */
    fun position(index: Int): DungeonBlockPosition {
        require(index in 0 until BLOCK_COUNT)
        val width = OverworldDungeonInstanceLayout.ARENA_WIDTH
        return DungeonBlockPosition(minX + index % width, minY + index / (width * width), minZ + index / width % width)
    }

    companion object {
        const val BLOCK_COUNT = OverworldDungeonInstanceLayout.ARENA_WIDTH * OverworldDungeonInstanceLayout.ARENA_WIDTH * OverworldDungeonInstanceLayout.ARENA_HEIGHT
    }
}

/** 検査対象の整数ブロック座標です。 */
data class DungeonBlockPosition(val x: Int, val y: Int, val z: Int)

/**
 * 再ログイン時に安全な地上が読み戻されたときだけ、帰還記録の削除を許可します。
 * 足場の安全性はチャンク読込済みの呼び出し側が確認します。
 */
fun canAcknowledgeDungeonReturn(
    slot: DungeonInstanceCenter,
    x: Double,
    y: Double,
    z: Double,
    sameWorld: Boolean,
    safeGround: Boolean,
): Boolean {
    if (!sameWorld || !safeGround || !x.isFinite() || !y.isFinite() || !z.isFinite()) return false
    val horizontalMargin = OverworldDungeonInstanceLayout.ARENA_RADIUS + 16.0
    val nearSlot = kotlin.math.abs(x - slot.x) <= horizontalMargin && kotlin.math.abs(z - slot.z) <= horizontalMargin
    val belowInstance = y < slot.y.toDouble() - OverworldDungeonInstanceLayout.FLOOR_OFFSET - 16.0
    return !nearSlot && belowInstance
}

/** 既存ブロックの検査結果を保持し、各 tick の検査数を制限します。 */
class DungeonBoxScan(private val bounds: DungeonInstanceBounds) {
    var examined: Int = 0
        private set
    var result: DungeonBoxScanResult = DungeonBoxScanResult.IN_PROGRESS
        private set

    /** 指定件数だけ進め、最初の非空気で直ちに検査を打ち切ります。 */
    fun advance(budget: Int, isAir: (DungeonBlockPosition) -> Boolean): DungeonBoxScanResult {
        require(budget > 0)
        if (result != DungeonBoxScanResult.IN_PROGRESS) return result
        val end = (examined.toLong() + budget).coerceAtMost(DungeonInstanceBounds.BLOCK_COUNT.toLong()).toInt()
        while (examined < end) {
            if (!isAir(bounds.position(examined))) {
                result = DungeonBoxScanResult.OCCUPIED
                return result
            }
            examined++
        }
        if (examined == DungeonInstanceBounds.BLOCK_COUNT) result = DungeonBoxScanResult.EMPTY
        return result
    }
}

/** 全域検査の途中、空き、占有を区別します。 */
enum class DungeonBoxScanResult { IN_PROGRESS, EMPTY, OCCUPIED }
