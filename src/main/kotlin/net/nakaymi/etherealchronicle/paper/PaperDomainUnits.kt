package net.nakaymi.etherealchronicle.paper

import org.bukkit.Location
import kotlin.math.floor

/** Minecraftのtickと実時間を相互変換する共通窓口です。 */
object MinecraftTicks {
    const val TICKS_PER_SECOND = 20L
    const val MILLIS_PER_TICK = 50L
    private const val MILLIS_PER_SECOND = 1_000L

    fun ticksToMillis(ticks: Long): Long = ticks * MILLIS_PER_TICK
    fun secondsToTicks(seconds: Long): Long = seconds * TICKS_PER_SECOND
    fun secondsToMillis(seconds: Long): Long = seconds * MILLIS_PER_SECOND
    fun millisToSeconds(millis: Long): Double = millis / MILLIS_PER_SECOND.toDouble()

    /** 経過時間を切り捨てて整数秒へ変換し、既存の順位判定を保ちます。 */
    fun millisToWholeSeconds(millis: Long): Long = millis / MILLIS_PER_SECOND

    /** 残り時間を切り上げて秒表示にし、表示上の早期ゼロを防ぎます。 */
    fun millisToSecondsCeil(millis: Long): Long =
        if (millis <= 0) 0 else 1 + (millis - 1) / MILLIS_PER_SECOND
}

/** 同一ワールド内の距離を平方根なしで判定するPaper拡張です。 */
fun Location.isWithinBlocks(other: Location, blocks: Double): Boolean =
    world == other.world && distanceSquared(other) <= blocks * blocks

/** ゲーム内で距離の意味が定まっている半径をまとめます。 */
object PaperDistance {
    const val SHOP_INTERACTION_BLOCKS = 8.0
    const val DUNGEON_PARTY_GATHER_BLOCKS = 32.0
    const val ENEMY_SPAWN_VISIBILITY_BLOCKS = 64.0
    const val GATHER_CANCEL_MOVE_BLOCKS = 0.3
    const val PARTY_GATHER_BONUS_BLOCKS = 16.0
    const val FISH_CAST_ORIGIN_BLOCKS = 5.0
    const val FISH_WATER_SPOT_BLOCKS = 15.0
    const val ENEMY_PACK_ASSIST_BLOCKS = 8.0
}

/** レイ・トレースと敵の近接範囲に使う物理判定寸法です。 */
object CombatGeometry {
    const val SKILL_TARGET_HITBOX_BLOCKS = 0.8
    const val MELEE_ATTACK_DISTANCE_SQUARED = 8.0
}

/** 各種座標表現から負座標にも正しいチャンク座標を生成します。 */
data class ChunkCoordinates(val x: Int, val z: Int) {
    companion object {
        fun fromBlocks(x: Int, z: Int) = ChunkCoordinates(x shr 4, z shr 4)
        fun fromPosition(x: Double, z: Double) = ChunkCoordinates(floor(x).toInt() shr 4, floor(z).toInt() shr 4)
    }
}

/** プレイヤーインベントリ内のスキル枠と通常収納枠の所有境界です。 */
object PlayerInventoryLayout {
    const val SKILL_HOTBAR_SLOT = 8
    const val FIRST_HOTBAR_SLOT = 0
    const val LAST_STARTER_HOTBAR_SLOT = 7
    const val LAST_STORAGE_SLOT = 35
    val STARTER_HOTBAR_RANGE = FIRST_HOTBAR_SLOT..LAST_STARTER_HOTBAR_SLOT
    val STORAGE_SLOT_RANGE = FIRST_HOTBAR_SLOT..LAST_STORAGE_SLOT

    fun isSkillSlot(slot: Int) = slot == SKILL_HOTBAR_SLOT
    fun isUsableStorageSlot(slot: Int) = slot in STORAGE_SLOT_RANGE && !isSkillSlot(slot)
}

/** チェスト系画面で共有する物理スロット寸法です。 */
object ChestLayout {
    const val COLUMNS = 9
    const val CLASS_MENU_SLOTS = COLUMNS
    const val DOUBLE_CHEST_ROWS = 6
    const val DOUBLE_CHEST_SLOTS = COLUMNS * DOUBLE_CHEST_ROWS
    const val DOUBLE_CHEST_CONTENT_SLOTS = 45
    const val BACKGROUND_WIDTH_PIXELS = 176
}

/** 職業選択の枠と確認ボタンを同じ定義から組み立てます。 */
object ClassSelectionLayout {
    const val MAX_CLASS_OPTIONS = ChestLayout.CLASS_MENU_SLOTS
    const val CONFIRM_BUTTON_WIDTH_PIXELS = 200
}

/** 図鑑の項目領域と固定フッター配置です。 */
object CodexLayout {
    const val PREVIOUS_PAGE_SLOT = 45
    const val BACK_SLOT = 46
    const val FISH_TAB_SLOT = 47
    const val GATHERING_TAB_SLOT = 48
    const val ITEM_TAB_SLOT = 49
    const val PAGE_LABEL_SLOT = 50
    const val NEXT_PAGE_SLOT = 53
    const val MAX_VISIBLE_ENTRIES = ChestLayout.DOUBLE_CHEST_CONTENT_SLOTS
}

/** 依頼会話の本体・選択肢位置とPaper Dialog幅を定義します。 */
object DialogueLayout {
    const val TEXT_SLOT = 4
    const val FIRST_CHOICE_SLOT = ChestLayout.COLUMNS
    const val CHOICE_BUTTON_WIDTH_PIXELS = 200
    const val TEXT_WRAP_CHARACTERS = 28
    const val MAX_VISIBLE_CHOICES = ChestLayout.DOUBLE_CHEST_SLOTS - FIRST_CHOICE_SLOT
}

/** 強化画面で入力対象と確定操作の位置を共有します。 */
object EnhanceLayout {
    const val TARGET_ITEM_SLOT = 4
    const val CONFIRM_SLOT = 13
}

