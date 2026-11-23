package net.nakaymi.etherealchronicle.core

import kotlin.math.abs
import kotlin.math.floor

/** 同一ワールドの空中ダンジョンインスタンス枠と高さ・ワールド境界適合性を管理します。 */
data class OverworldDungeonInstanceLayout(
    val baseX: Int = DEFAULT_BASE_X,
    val baseY: Int = DEFAULT_BASE_Y,
    val baseZ: Int = DEFAULT_BASE_Z,
    val spacing: Int = DEFAULT_SPACING,
) {
    init {
        require(spacing >= ARENA_WIDTH) { "Dungeon instance spacing is smaller than the arena" }
    }

    /** スロット間の重複と整数オーバーフローを避けて床中心位置を返します。 */
    fun center(slot: Int): DungeonInstanceCenter {
        require(slot in 0 until MAX_CANDIDATE_SLOTS) { "Dungeon instance slot is outside the candidate range" }
        val x = Math.addExact(baseX, Math.multiplyExact(slot, spacing))
        require(x.toLong() - ARENA_RADIUS >= -WORLD_LIMIT && x.toLong() + ARENA_RADIUS <= WORLD_LIMIT)
        require(baseZ.toLong() - ARENA_RADIUS >= -WORLD_LIMIT && baseZ.toLong() + ARENA_RADIUS <= WORLD_LIMIT)
        return DungeonInstanceCenter(x, baseY, baseZ)
    }

    /** schematic 全体がワールドの高さ制限 `[minHeight, maxHeight)` 内に収まるか確認します。 */
    fun fitsHeight(minHeight: Int, maxHeight: Int): Boolean {
        val bottom = baseY.toLong() - FLOOR_OFFSET
        return bottom >= minHeight && bottom + ARENA_HEIGHT <= maxHeight
    }

    /** 通常地形や山岳との衝突リスクを抑える推奨高度以上かを返します。 */
    fun hasTerrainClearance(): Boolean = baseY >= RECOMMENDED_MIN_BASE_Y

    /** 指定スロットの 4 隅がワールドボーダー内に収まるか確認します。 */
    fun fitsBorder(slot: Int, borderCenterX: Double, borderCenterZ: Double, borderSize: Double): Boolean {
        if (!borderCenterX.isFinite() || !borderCenterZ.isFinite() || !borderSize.isFinite() || borderSize <= 0.0) {
            return false
        }
        val b = runCatching { bounds(slot) }.getOrNull() ?: return false
        val half = borderSize / 2.0
        val minBorderX = borderCenterX - half
        val maxBorderX = borderCenterX + half
        val minBorderZ = borderCenterZ - half
        val maxBorderZ = borderCenterZ + half
        return b.minX.toDouble() >= minBorderX &&
            (b.maxX + 1).toDouble() <= maxBorderX &&
            b.minZ.toDouble() >= minBorderZ &&
            (b.maxZ + 1).toDouble() <= maxBorderZ
    }

    /** ワールドボーダー内に収まる候補スロット番号を昇順で返します。 */
    fun fittingSlots(
        slotCount: Int,
        borderCenterX: Double,
        borderCenterZ: Double,
        borderSize: Double,
    ): List<Int> {
        val capped = slotCount.coerceIn(0, MAX_CANDIDATE_SLOTS)
        return (0 until capped).filter { fitsBorder(it, borderCenterX, borderCenterZ, borderSize) }
    }

    /** 使用中枠を除外し、既存ブロックのない最初の枠を選びます。 */
    fun findAvailableSlot(occupied: Set<Int>, candidates: IntRange, isEmpty: (DungeonInstanceCenter) -> Boolean): Int? {
        val first = candidates.first.coerceAtLeast(0)
        val last = candidates.last.coerceAtMost(MAX_CANDIDATE_SLOTS - 1)
        if (candidates.isEmpty() || first > last) return null
        return (first..last).firstOrNull { slot -> slot !in occupied && isEmpty(center(slot)) }
    }

    /** 検査・配置・撤去のすべてで同じ範囲と走査順を使います。 */
    fun bounds(slot: Int): DungeonInstanceBounds {
        val center = center(slot)
        return DungeonInstanceBounds(center.x - ARENA_RADIUS, Math.subtractExact(center.y, FLOOR_OFFSET), center.z - ARENA_RADIUS)
    }

    companion object {
        /** 既定の配置原点 X（通常プレイ圏から十分離れた遠方空域）。 */
        const val DEFAULT_BASE_X = 100_000

        /** 既定の床 Y 座標（地表建築や山岳の上空、かつ Y=320 上限に余裕がある高さ）。 */
        const val DEFAULT_BASE_Y = 220

        /** 地形・山岳との衝突リスクを避けるための推奨最小床 Y 座標。 */
        const val RECOMMENDED_MIN_BASE_Y = 160

        /** 既定の配置原点 Z（通常プレイ圏から十分離れた遠方空域）。 */
        const val DEFAULT_BASE_Z = 100_000

        /** 既定のスロット間隔（チャンク 16 本分、アリーナ幅 55 に対して十分な離隔）。 */
        const val DEFAULT_SPACING = 256

        /** 同時候補スロットの上限数。 */
        const val MAX_CANDIDATE_SLOTS = 16

        /** 同梱ダンジョン schematic の水平幅（X/Z 共通）。 */
        const val ARENA_WIDTH = 55

        /** アリーナ中心から外周までの水平半径（(55 - 1) / 2 = 27）。 */
        const val ARENA_RADIUS = 27

        /** 同梱ダンジョン schematic の高さ（Y ブロック数）。 */
        const val ARENA_HEIGHT = 34

        /**
         * 床中心 Y から schematic 原点 Y までの下方向オフセット。
         * 専用ワールドの `FLOOR_Y(64) - SCHEMATIC_ORIGIN_Y(54) = 10` に一致させています。
         */
        const val FLOOR_OFFSET = 10

        /** 帰還確認時にスロット近傍とみなす追加水平マージン（ブロック数）。 */
        const val RECOVERY_HORIZONTAL_MARGIN_BLOCKS = 16.0

        /** 帰還確認時にスロット下方とみなす追加垂直マージン（ブロック数）。 */
        const val RECOVERY_VERTICAL_MARGIN_BLOCKS = 16.0

        /** 小規模ワールドボーダーへ自動収容する際の外周安全余白（ブロック数）。 */
        const val BORDER_SAFETY_MARGIN_BLOCKS = 64

        /** Minecraft ワールド座標の絶対上限。 */
        private const val WORLD_LIMIT = 29_999_999L

        /**
         * 設定値またはワールドボーダー・高さ制限に合わせて安全なレイアウトを決定します。
         * 明示設定が未指定で既定の遠方座標がボーダー外となる場合は、ボーダー内の端へ自動調整します。
         */
        fun resolveForWorld(
            configuredBaseX: Int? = null,
            configuredBaseY: Int? = null,
            configuredBaseZ: Int? = null,
            configuredSpacing: Int? = null,
            borderCenterX: Double,
            borderCenterZ: Double,
            borderSize: Double,
            minHeight: Int,
            maxHeight: Int,
            slotCount: Int = MAX_CANDIDATE_SLOTS,
        ): OverworldDungeonInstanceLayout {
            val spacing = (configuredSpacing ?: DEFAULT_SPACING).coerceAtLeast(ARENA_WIDTH)
            val resolvedY = resolveY(configuredBaseY, minHeight, maxHeight)
            val explicitHorizontal = configuredBaseX != null || configuredBaseZ != null
            val initial = OverworldDungeonInstanceLayout(
                baseX = configuredBaseX ?: DEFAULT_BASE_X,
                baseY = resolvedY,
                baseZ = configuredBaseZ ?: DEFAULT_BASE_Z,
                spacing = spacing,
            )
            val cappedSlots = slotCount.coerceIn(1, MAX_CANDIDATE_SLOTS)
            if (explicitHorizontal || initial.fittingSlots(cappedSlots, borderCenterX, borderCenterZ, borderSize).size == cappedSlots) {
                return initial
            }
            if (!borderCenterX.isFinite() || !borderCenterZ.isFinite() || !borderSize.isFinite() || borderSize < ARENA_WIDTH) {
                return initial
            }
            val half = borderSize / 2.0
            val usableHalf = (half - ARENA_RADIUS - BORDER_SAFETY_MARGIN_BLOCKS).coerceAtLeast(half - ARENA_RADIUS - 1.0)
            val minCenterX = floor(borderCenterX - usableHalf).toInt()
            val maxCenterX = floor(borderCenterX + usableHalf).toInt()
            val maxCenterZ = floor(borderCenterZ + usableHalf).toInt()
            val availableWidth = (maxCenterX - minCenterX).coerceAtLeast(0)
            val adjustedSpacing = if (cappedSlots > 1 && availableWidth > 0) {
                val perSlot = availableWidth / (cappedSlots - 1)
                if (perSlot >= ARENA_WIDTH) minOf(spacing, perSlot) else spacing
            } else spacing
            val spanX = (cappedSlots - 1).toLong() * adjustedSpacing
            val candidateBaseX = if (spanX <= availableWidth) {
                (maxCenterX - spanX.toInt()).coerceAtLeast(minCenterX)
            } else {
                minCenterX
            }
            val candidateBaseZ = maxCenterZ.coerceIn(floor(borderCenterZ - half + ARENA_RADIUS + 1.0).toInt(), maxCenterZ)
            val candidate = runCatching {
                OverworldDungeonInstanceLayout(
                    baseX = candidateBaseX,
                    baseY = resolvedY,
                    baseZ = candidateBaseZ,
                    spacing = adjustedSpacing,
                )
            }.getOrNull() ?: return initial
            return if (candidate.fittingSlots(cappedSlots, borderCenterX, borderCenterZ, borderSize).size >=
                initial.fittingSlots(cappedSlots, borderCenterX, borderCenterZ, borderSize).size
            ) candidate else initial
        }

        private fun resolveY(configuredBaseY: Int?, minHeight: Int, maxHeight: Int): Int {
            val baseY = configuredBaseY ?: DEFAULT_BASE_Y
            val bottom = baseY.toLong() - FLOOR_OFFSET
            if (bottom >= minHeight && bottom + ARENA_HEIGHT <= maxHeight) return baseY
            if (configuredBaseY != null || maxHeight - minHeight < ARENA_HEIGHT) return baseY
            val minValidY = minHeight + FLOOR_OFFSET
            val maxValidY = maxHeight - (ARENA_HEIGHT - FLOOR_OFFSET)
            return baseY.coerceIn(minValidY, maxValidY)
        }
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
        const val BLOCK_COUNT = OverworldDungeonInstanceLayout.ARENA_WIDTH *
            OverworldDungeonInstanceLayout.ARENA_WIDTH *
            OverworldDungeonInstanceLayout.ARENA_HEIGHT
    }
}

/** 検査対象の整数ブロック座標です。 */
data class DungeonBlockPosition(val x: Int, val y: Int, val z: Int)

/** オーバーワールド空中ダンジョンスロットのライフサイクル状態です。 */
enum class DungeonSlotState {
    /** 空きスキャン・スキーマ配置・帰還記録永続化のための予約中。 */
    RESERVED,

    /** プレイヤーが攻略中の稼働インスタンス。 */
    ACTIVE,

    /** 未帰還メンバーまたは帰還記録の完了待ちのため床を残して再利用を禁止中。 */
    PROTECTED,

    /** 通常終了・最後の帰還確認・孤立回収に伴うブロック撤去（AIR 戻し）を実行中。 */
    CLEANING,
}

/**
 * オーバーワールド空中スロットの状態遷移（`RESERVED` → `ACTIVE` → `PROTECTED` / `CLEANING` → 解放）を一元管理します。
 * 予約・稼働・保護・撤去を同一マップで管理することで、二重計上や同時入場時の同一スロット競合を防ぎます。
 */
class DungeonSlotTracker {
    private val states = mutableMapOf<Int, DungeonSlotState>()

    /** 指定スロットの現在状態を返します。空きなら null です。 */
    fun stateOf(slot: Int): DungeonSlotState? = states[slot]

    /** 予約・稼働・撤去中のアクティブなスロット数を二重計上なしで返します（静的な PROTECTED は含めません）。 */
    fun activeCount(): Int = states.values.count { it != DungeonSlotState.PROTECTED }

    /** 予約・稼働・撤去中のアクティブなスロット番号のスナップショットを返します。 */
    fun activeSlots(): Set<Int> = states.filterValues { it != DungeonSlotState.PROTECTED }.keys.toSet()

    /** 現在占有されている全スロット番号（PROTECTED を含む）のスナップショットを返します。 */
    fun occupiedSlots(): Set<Int> = states.keys.toSet()

    /** 空きスロットを原子的に `RESERVED` として確保します。既に占有中なら false を返します。 */
    fun tryReserve(slot: Int): Boolean {
        if (slot in states) return false
        states[slot] = DungeonSlotState.RESERVED
        return true
    }

    /**
     * 配置と帰還記録が完了した `RESERVED` スロットを `ACTIVE` へ遷移させます。
     * 予約状態でない場合は例外を投げずに false を返します。
     */
    fun activate(slot: Int): Boolean {
        if (states[slot] != DungeonSlotState.RESERVED) return false
        states[slot] = DungeonSlotState.ACTIVE
        return true
    }

    /**
     * 未帰還メンバーが残るスロットを `PROTECTED` として保護し、再利用を防ぎます。
     * 既に撤去中（`CLEANING`）の場合は false を返します。
     */
    fun protect(slot: Int): Boolean {
        if (states[slot] == DungeonSlotState.CLEANING) return false
        states[slot] = DungeonSlotState.PROTECTED
        return true
    }

    /** 占有中スロットを `CLEANING` へ遷移させます。未占有または既に撤去中なら false です。 */
    fun beginCleaning(slot: Int): Boolean {
        val current = states[slot] ?: return false
        if (current == DungeonSlotState.CLEANING) return false
        states[slot] = DungeonSlotState.CLEANING
        return true
    }

    /**
     * 起動時の孤立スロット回収など、未追跡スロットも含めて `CLEANING` へ遷移させます。
     * 既に `CLEANING` の場合のみ false を返します。
     */
    fun tryBeginCleaning(slot: Int): Boolean {
        if (states[slot] == DungeonSlotState.CLEANING) return false
        states[slot] = DungeonSlotState.CLEANING
        return true
    }

    /** 失敗・中断・撤去完了時にスロットを完全に解放して再利用可能にします。 */
    fun release(slot: Int) {
        states.remove(slot)
    }

    /** シャットダウン時に全スロット状態を破棄します。 */
    fun clear() {
        states.clear()
    }
}

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
    val horizontalMargin = OverworldDungeonInstanceLayout.ARENA_RADIUS +
        OverworldDungeonInstanceLayout.RECOVERY_HORIZONTAL_MARGIN_BLOCKS
    val nearSlot = abs(x - slot.x) <= horizontalMargin && abs(z - slot.z) <= horizontalMargin
    val belowInstance = y < slot.y.toDouble() -
        OverworldDungeonInstanceLayout.FLOOR_OFFSET -
        OverworldDungeonInstanceLayout.RECOVERY_VERTICAL_MARGIN_BLOCKS
    return !nearSlot && belowInstance
}

/** 指定座標が空中ダンジョンスロット（およびその保護マージン）内に留まっているかを判定します。 */
fun isInsideDungeonSlotMargin(
    slot: DungeonInstanceCenter,
    x: Double,
    y: Double,
    z: Double,
): Boolean {
    if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return false
    val horizontalMargin = OverworldDungeonInstanceLayout.ARENA_RADIUS +
        OverworldDungeonInstanceLayout.RECOVERY_HORIZONTAL_MARGIN_BLOCKS
    val minY = slot.y.toDouble() -
        OverworldDungeonInstanceLayout.FLOOR_OFFSET -
        OverworldDungeonInstanceLayout.RECOVERY_VERTICAL_MARGIN_BLOCKS
    return abs(x - slot.x) <= horizontalMargin &&
        abs(z - slot.z) <= horizontalMargin &&
        y >= minY
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
