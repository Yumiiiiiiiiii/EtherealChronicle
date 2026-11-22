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
        require(slot >= 0) { "Dungeon instance slot must be non-negative" }
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
    fun findAvailableSlot(occupied: Set<Int>, candidates: IntRange, isEmpty: (DungeonInstanceCenter) -> Boolean): Int? =
        candidates.firstOrNull { slot -> slot >= 0 && slot !in occupied && isEmpty(center(slot)) }

    private companion object {
        const val ARENA_WIDTH = 55
        const val ARENA_RADIUS = 27
        const val ARENA_HEIGHT = 34
        const val FLOOR_OFFSET = 10
        const val WORLD_LIMIT = 29_999_999L
    }
}

/** ダンジョンの床中心を表します。 */
data class DungeonInstanceCenter(val x: Int, val y: Int, val z: Int)
