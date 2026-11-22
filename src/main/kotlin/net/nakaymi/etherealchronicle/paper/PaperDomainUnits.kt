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
    fun millisToWholeSeconds(millis: Long): Long = (millis / MILLIS_PER_SECOND).coerceAtLeast(0)

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
    if (blocks < 0.0 || currentWorld != other.world) return false
    return distanceSquared(other) <= blocks * blocks
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

/** インスタンスダンジョンの区画間隔、建築寸法、保護境界を一元管理します。 */
internal object DungeonArenaLayout {
    const val FIRST_CENTER_X = 1_024
    const val CENTER_Z = 1_024
    const val SLOT_SPACING_BLOCKS = 192
    const val PLATFORM_RADIUS_BLOCKS = 24
    const val INNER_FLOOR_RADIUS_BLOCKS = 2
    const val OUTER_FLOOR_PALETTE_INDEX = 0
    const val WALL_PALETTE_INDEX = 1
    const val INNER_FLOOR_PALETTE_INDEX = 2
    const val FLOOR_Y = 64
    const val PLAYER_SPAWN_Y = 65
    const val ENTITY_SPAWN_Y = 65
    const val WALL_BOTTOM_Y = 65
    const val WALL_TOP_Y = 68
    const val PLAYER_SPAWN_Z_OFFSET = -12
    const val BOUNDARY_RADIUS_BLOCKS = 30.0
    const val RECOVERY_MIN_COORDINATE = 900.0
    const val VOID_RECOVERY_Y = 0.0

    data class Origin(val x: Int, val z: Int)

    fun centerX(slot: Int): Int = FIRST_CENTER_X + slot * SLOT_SPACING_BLOCKS

    fun originForSlot(slot: Int): Origin = Origin(centerX(slot), CENTER_Z)

    fun contains(x: Double, z: Double, slot: Int): Boolean =
        kotlin.math.abs(x - centerX(slot)) <= BOUNDARY_RADIUS_BLOCKS &&
            kotlin.math.abs(z - CENTER_Z) <= BOUNDARY_RADIUS_BLOCKS

    fun isInsideBounds(location: Location, slot: Int): Boolean = contains(location.x, location.z, slot)
}

/** 新規ec_worldの高さと島・広場を宣言的に記述します。 */
internal object DemoWorldLayout {
    const val CHUNK_SIZE_BLOCKS = ChunkCoordinates.CHUNK_SIZE_BLOCKS
    const val UNDERGROUND_Y = 62
    const val SUPPORT_Y = 63
    const val GROUND_Y = 64
    const val SURFACE_Y = 65
    const val FIRST_ISLAND_CENTER_X = 0
    const val ISLAND_CENTER_SPACING = 96
    const val ISLAND_HALF_WIDTH = 36
    const val ISLAND_COUNT = 9
    const val FIRST_CHAPTER_ISLAND_COUNT = 5
    const val ICE_ISLAND_CENTER_RADIUS_BLOCKS = 2
    const val BRIDGE_ICE_ACCENT_INTERVAL_BLOCKS = 48
    val GRASS_ISLAND_X_BAND = 60..132
    val DEEPSLATE_ISLAND_X_BAND = 156..228
    const val HUB_EDGE_RADIUS = 12
    const val HUB_GATE_HALF_WIDTH_BLOCKS = 2
    const val HUB_FLOOR_RADIUS = 4
    const val SECOND_CHAPTER_BRIDGE_START_X = 421
    const val SECOND_CHAPTER_BRIDGE_END_X = 804
    const val FIRST_CHAPTER_BRIDGE_END_X = 420
    const val BRIDGE_HALF_WIDTH = 3
    const val ICE_ISLAND_OUTER_LANE_HALF_WIDTH = BRIDGE_HALF_WIDTH
    const val LIFE_POOL_RADIUS = 3
    const val SECRET_POOL_CENTER_X = 124
    const val SECRET_POOL_CENTER_Z = -24
    const val SECOND_CHAPTER_MATERIAL_START_X = 444
    const val HOSTILE_SCAN_HORIZONTAL_BLOCKS = 40.0
    const val HOSTILE_SCAN_VERTICAL_BLOCKS = 128.0
    const val WAYSTONE_X = 24
    const val WAYSTONE_Y = SURFACE_Y
    const val WAYSTONE_Z = 28
    const val PILLAR_OFFSET_BLOCKS = 8
    const val BOARDWALK_MIN_X = -20
    const val BOARDWALK_MAX_X = 20
    const val BOARDWALK_MIN_Z = 16
    const val BOARDWALK_MAX_Z = 26
    const val BOARDWALK_POST_INTERVAL_BLOCKS = 10
    const val BOARDWALK_POST_TOP_Y = 68
    const val CHAPTER_TWO_PYLON_TOP_Y = 72

    /** 単一座標上に縦積みする構造物です。 */
    data class Column(val x: Int, val z: Int, val yRange: IntRange, val material: Material)
    /** 中心からの幅を持つ水平な樹冠などを表します。 */
    data class Canopy(val centerX: Int, val centerZ: Int, val halfWidth: Int, val y: Int, val material: Material)
    /** 範囲の外周または上端だけを埋める枠です。 */
    data class Outline(val xRange: IntRange, val zRange: IntRange, val yRange: IntRange, val material: Material)
    /** 指定範囲を隙間なく埋める小規模な構造物です。 */
    data class SolidBox(val xRange: IntRange, val zRange: IntRange, val yRange: IntRange, val material: Material)
    /** 円形の地面装飾です。 */
    data class Disk(val centerX: Int, val centerZ: Int, val y: Int, val radius: Int, val material: Material)
    /** 単独のランドマークブロックです。 */
    data class BlockPoint(val x: Int, val y: Int, val z: Int, val material: Material)

    data class Plaza(val centerX: Int, val wallMaterial: Material, val floorMaterial: Material)

    val HUB_PLAZAS = listOf(
        Plaza(0, Material.STONE_BRICK_WALL, Material.QUARTZ_BRICKS),
        Plaza(96, Material.OAK_LEAVES, Material.POLISHED_ANDESITE),
        Plaza(192, Material.STONE_BRICK_WALL, Material.POLISHED_ANDESITE),
        Plaza(288, Material.STONE_BRICK_WALL, Material.POLISHED_ANDESITE),
        Plaza(384, Material.STONE_BRICK_WALL, Material.POLISHED_ANDESITE),
    )

    val LIFE_POOL_CENTERS = listOf(10 to 28, 92 to 30, 190 to 28, 280 to 30, 380 to 28, 480 to 30, 570 to 30, 670 to 30)

    val HUB_TREE_TRUNKS = listOf(88, 104).map { Column(it, 20, 65..69, Material.OAK_LOG) }
    val HUB_TREE_CANOPIES = listOf(88, 104).map { Canopy(it, 20, 2, 70, Material.OAK_LEAVES) }
    val LEGACY_LANDMARK_OUTLINES = listOf(Outline(184..200, -16..-16, 65..70, Material.DEEPSLATE_BRICKS))
    val LEGACY_LANDMARK_DISKS = listOf(Disk(288, 0, GROUND_Y, 6, Material.CALCITE))
    val LEGACY_LANDMARK_POINTS = listOf(
        BlockPoint(288, SURFACE_Y, 0, Material.LODESTONE),
        BlockPoint(384, SURFACE_Y, 0, Material.CRYING_OBSIDIAN),
    )
    val CHAPTER_TWO_COLUMNS = listOf(
        Column(576, 0, 65..72, Material.BLUE_ICE),
        Column(667, 0, 65..68, Material.PACKED_ICE),
        Column(677, 0, 65..68, Material.PACKED_ICE),
    )
    val CHAPTER_TWO_TOP_BEAMS = listOf(SolidBox(667..677, 0..0, 69..69, Material.PRISMARINE_BRICKS))
    val CHAPTER_TWO_PYLONS = listOf(756, 780).flatMap { x -> listOf(-12, 12).map { z -> x to z } }
    const val CHAPTER_TWO_SHRINE_CENTER_X = 480
    const val CHAPTER_TWO_SHRINE_CENTER_Z = 0
    const val CHAPTER_TWO_SHRINE_RADIUS_BLOCKS = 8
}

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
