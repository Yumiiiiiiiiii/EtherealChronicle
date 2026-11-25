package net.nakaymi.etherealchronicle.paper

import org.bukkit.Location
import org.bukkit.Material
import kotlin.math.floor

/** Minecraftのtickと実時間を相互変換する共通窓口です。 */
internal object MinecraftTicks {
    const val TICKS_PER_SECOND = 20L
    const val MILLIS_PER_TICK = 50L
    const val MILLIS_PER_SECOND = 1_000L

    fun ticksToMillis(ticks: Long): Long = ticks * MILLIS_PER_TICK
    fun secondsToTicks(seconds: Long): Long = seconds * TICKS_PER_SECOND
    fun secondsToMillis(seconds: Long): Long = seconds * MILLIS_PER_SECOND
    fun millisToSeconds(millis: Long): Double = millis / MILLIS_PER_SECOND.toDouble()

    /** 経過時間を切り捨てて整数秒へ変換し、既存の順位判定を保ちます。 */
    fun millisToWholeSeconds(millis: Long): Long = if (millis <= 0) 0 else millis / MILLIS_PER_SECOND

    /** 残り時間を切り上げて秒表示にし、表示上の早期ゼロを防ぎます。 */
    fun millisToSecondsCeil(millis: Long): Long =
        if (millis <= 0) 0 else 1 + (millis - 1) / MILLIS_PER_SECOND
}

/** Controllerの周期をtick単位で表し、タスク登録箇所の裸の間隔値を避けます。 */
internal object PaperTaskIntervals {
    const val NEXT_TICK_DELAY = 1L
    const val ENEMY_UPDATE_TICKS = 10L
    const val PLAYER_UPDATE_TICKS = MinecraftTicks.TICKS_PER_SECOND
    const val GATHERING_UPDATE_TICKS = 2L
    const val FISHING_UPDATE_TICKS = GATHERING_UPDATE_TICKS
    const val PROFILE_AUTOSAVE_SECONDS = 60
}

/** Dialog操作callbackを実際のクラス選択操作中に失効させない寿命です。 */
internal object UiCallbackLifetimes {
    val CLASS_SELECTION = java.time.Duration.ofHours(24)
    val DIALOGUE_CHOICE = java.time.Duration.ofMinutes(2)
}

/** 採取操作の履歴とチャネル表示の初期状態です。 */
internal object GatheringSessionTuning {
    const val MAX_ACTIVE_ATTEMPT_RECORDS = 1_000
    const val CHANNEL_BOSSBAR_START_PROGRESS = 0.08
}

/** 実装周期とシミュレーションの物理ステップ幅を結び付けます。 */
internal object FishingStepTuning {
    const val SECONDS_PER_UPDATE = PaperTaskIntervals.FISHING_UPDATE_TICKS * MinecraftTicks.MILLIS_PER_TICK / 1_000.0
}

/** 移動イベント後の短い猶予期間を表すゲームtick数です。 */
internal object PlayerMovementTuning {
    const val MOVED_RECENTLY_TICKS = MinecraftTicks.TICKS_PER_SECOND
}

/** 初期装備をスキル枠を避けて置くホットバーの容量です。 */
internal object PlayerInventoryCapacities {
    const val STARTER_HOTBAR = PlayerInventoryLayout.LAST_STARTER_HOTBAR_SLOT + 1
}

/** 採取表示entityの寸法と高さを揃え、当たり判定と見た目のずれを防ぎます。 */
internal object GatheringDisplayGeometry {
    const val TEXT_HEIGHT_BLOCKS = 1.15
    const val INTERACTION_WIDTH_BLOCKS = 1.1f
    const val INTERACTION_HEIGHT_BLOCKS = 1.1f
    const val CLICK_VERTICAL_TOLERANCE_BLOCKS = 2
    const val CLICK_HORIZONTAL_RADIUS_BLOCKS = 2
}

/** サーバー全体で同時進行できる採取チャネルの防御上限です。 */
internal object GatheringControllerLimits {
    const val MAX_CONCURRENT_CHANNELS = 100
}

/** PaperのhealthScaleは見た目のハート数であり、プレイヤーの実HP上限ではありません。 */
internal object PaperHealthDisplay {
    const val VANILLA_HEARTS_SCALE = 20.0
}

/** 同一ワールド内の距離を平方根なしで判定するPaper拡張です。 */
internal fun Location.isWithinBlocks(other: Location, blocks: Double): Boolean {
    val currentWorld = world ?: return false
    if (!(blocks >= 0.0) || other.world == null || currentWorld != other.world) return false
    return distanceSquared(other) <= blocks * blocks
}

/** プレイヤーが安全に立脚できる地上足場かを一貫して判定します。 */
internal object SafeStandingSpot {
    /** 足場として不適切な危険ブロック群（毎判定の Set 生成を避けるため定数化）。 */
    private val HAZARDOUS_GROUND = setOf(
        Material.MAGMA_BLOCK,
        Material.CACTUS,
        Material.CAMPFIRE,
        Material.SOUL_CAMPFIRE,
        Material.LAVA,
        Material.SWEET_BERRY_BUSH,
        Material.POWDER_SNOW,
    )

    /** チャンク読込済みの足元・頭上空間、固体足場、危険ブロック、ワールド境界を検証します。 */
    fun isSafe(location: Location): Boolean {
        val world = location.world ?: return false
        val blockX = location.blockX
        val blockY = location.blockY
        val blockZ = location.blockZ
        if (!world.isChunkLoaded(blockX shr 4, blockZ shr 4)) return false
        if (blockY <= world.minHeight || blockY + 1 >= world.maxHeight) return false
        if (!world.worldBorder.isInside(location)) return false
        val feet = world.getBlockAt(blockX, blockY, blockZ).type
        val head = world.getBlockAt(blockX, blockY + 1, blockZ).type
        val ground = world.getBlockAt(blockX, blockY - 1, blockZ).type
        return feet.isAir && head.isAir && ground.isSolid && ground !in HAZARDOUS_GROUND
    }
}

/** ゲーム内で距離の意味が定まっている半径をまとめます。 */
internal object PaperDistance {
    const val MIN_FISH_CAST_DISTANCE_BLOCKS = 2.0
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
internal object CombatGeometry {
    const val SKILL_TARGET_HITBOX_BLOCKS = 0.8
    const val MELEE_ATTACK_DISTANCE_SQUARED = 8.0
}

/** 各種座標表現から負座標にも正しいチャンク座標を生成します。 */
internal data class ChunkCoordinates(val x: Int, val z: Int) {
    companion object {
        const val CHUNK_SIZE_BLOCKS = 16
        private const val CHUNK_COORDINATE_SHIFT = 4

        fun fromBlocks(x: Int, z: Int) = ChunkCoordinates(x shr CHUNK_COORDINATE_SHIFT, z shr CHUNK_COORDINATE_SHIFT)
        fun fromPosition(x: Double, z: Double) = ChunkCoordinates(floor(x).toInt() shr CHUNK_COORDINATE_SHIFT, floor(z).toInt() shr CHUNK_COORDINATE_SHIFT)
    }
}

/** プレイヤーインベントリ内のスキル枠と通常収納枠の所有境界です。 */
internal object PlayerInventoryLayout {
    const val SKILL_HOTBAR_SLOT = 8
    const val FIRST_HOTBAR_SLOT = 0
    const val HOTBAR_SLOT_COUNT = 9
    const val LAST_STARTER_HOTBAR_SLOT = 7
    const val LAST_STORAGE_SLOT = 35
    val STARTER_HOTBAR_RANGE = FIRST_HOTBAR_SLOT..LAST_STARTER_HOTBAR_SLOT
    val HOTBAR_SLOT_RANGE = FIRST_HOTBAR_SLOT until HOTBAR_SLOT_COUNT
    val STORAGE_SLOT_RANGE = FIRST_HOTBAR_SLOT..LAST_STORAGE_SLOT
    val USABLE_STORAGE_SLOTS = STORAGE_SLOT_RANGE.filterNot(::isSkillSlot)

    fun isSkillSlot(slot: Int) = slot == SKILL_HOTBAR_SLOT
    fun isUsableStorageSlot(slot: Int) = slot in STORAGE_SLOT_RANGE && !isSkillSlot(slot)
}

/** チェスト系画面で共有する物理スロット寸法です。 */
internal object ChestLayout {
    const val COLUMNS = 9
    const val CLASS_MENU_SLOTS = COLUMNS
    const val DOUBLE_CHEST_ROWS = 6
    const val DOUBLE_CHEST_SLOTS = COLUMNS * DOUBLE_CHEST_ROWS
    const val DOUBLE_CHEST_CONTENT_SLOTS = 45
    const val BACKGROUND_WIDTH_PIXELS = 176
}

/** ダンジョンアリーナSchematicのresource対応を定義します。 */
internal object WorldSchematicLayout {
    /** Dungeon IDから対応するアリーナresourceを引きます。 */
    fun dungeonPath(id: String): String? = when (id) {
        "ec:dungeon_novice_arena" -> "schematics/dungeons/dungeon_novice_arena.schem"
        "ec:dungeon_void_sanctum" -> "schematics/dungeons/dungeon_void_sanctum.schem"
        "ec:dungeon_aether_crucible" -> "schematics/dungeons/dungeon_aether_crucible.schem"
        "ec:dungeon_glacial_citadel" -> "schematics/dungeons/dungeon_glacial_citadel.schem"
        else -> null
    }
}

/** クラスパスSchematicと配置原点、およびair上書き方針を表します。 */
internal data class SchematicPlacement(val path: String, val x: Int, val y: Int, val z: Int, val skipAir: Boolean = false)

/** 職業選択の枠と確認ボタンを同じ定義から組み立てます。 */
internal object ClassSelectionLayout {
    const val MAX_CLASS_OPTIONS = ChestLayout.CLASS_MENU_SLOTS
    const val CONFIRM_BUTTON_WIDTH_PIXELS = 200
}

/** 図鑑の項目領域と固定フッター配置です。 */
internal object CodexLayout {
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
internal object DialogueLayout {
    const val TEXT_SLOT = 4
    const val FIRST_CHOICE_SLOT = ChestLayout.COLUMNS
    const val CHOICE_BUTTON_WIDTH_PIXELS = 200
    const val TEXT_WRAP_CHARACTERS = 28
    const val MAX_VISIBLE_CHOICES = ChestLayout.DOUBLE_CHEST_SLOTS - FIRST_CHOICE_SLOT
}

/** 強化画面で入力対象と確定操作の位置を共有します。 */
internal object EnhanceLayout {
    const val TARGET_ITEM_SLOT = 4
    const val CONFIRM_SLOT = 13
}

/** 隠し宝箱の演出を、表示位置と粒子密度を保ったまままとめます。 */
internal object TreasureVisualTuning {
    const val PARTICLE_COUNT = 3
    const val HINT_CENTER_X_BLOCKS = 0.5
    const val HINT_HEIGHT_BLOCKS = 1.1
    const val HINT_CENTER_Z_BLOCKS = 0.5
    const val PARTICLE_SPREAD_X_BLOCKS = 0.15
    const val PARTICLE_SPREAD_Y_BLOCKS = 0.2
    const val PARTICLE_SPREAD_Z_BLOCKS = 0.15
    const val PARTICLE_SPEED = 0.01
}
