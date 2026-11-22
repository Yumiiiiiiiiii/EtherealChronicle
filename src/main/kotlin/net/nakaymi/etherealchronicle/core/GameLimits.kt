package net.nakaymi.etherealchronicle.core

/** ゲーム全体で共有するプロフィール・取引の上限です。 */
object GameLimits {
    const val MAX_ENHANCEMENT_LEVEL = 5
    const val MAX_TRANSACTION_HISTORY = 128
    const val MAX_SKILL_LEVEL = 100
    const val MAX_LIFE_SKILL_LEVEL = 100
    const val MAX_RECORD_COUNT = 1_000_000
    const val MAX_FISH_RECORDS = 500
    const val MAX_GATHERING_RECORDS = 500
    const val MAX_GATHERING_RESPAWN_ENTRIES = 2_000
    const val MAX_PENDING_REWARD = 1_000_000
    const val MAX_DAILY_PURCHASE_COUNT = 1_000_000
    const val MAX_DROP_PITY_ATTEMPTS = 10_000
    const val MAX_GATHER_ATTEMPTS = 10_000
    const val MAX_DIALOGUE_BRANCH_DEPTH = 32
    const val ENHANCEMENT_BUDGET_SEARCH_STEPS = 40
    const val MAX_GATHERING_RESPAWN_SECONDS = 86_400L
}

/** balance YAMLの任意項目が省略されたときの互換既定値です。 */
object BalanceDefaults {
    const val XP_LINEAR = 45.0
    const val XP_QUADRATIC = 18.0
    const val XP_ROUNDING = 10L
    const val SKILL_GCD_MILLIS = 800L
    const val DROP_PITY_ATTEMPT_CAP = 100
    const val ENHANCEMENT_RATE_CAP = 0.1
    const val CURRENCY_GAIN_CAP = 1_000_000L
}

/** YAMLで上書きされない共通ステータスの便宜既定値です。 */
object StatDefaults {
    const val HP = 100.0
    const val RESOURCE = 100.0
    const val ATTACK = 10.0
    const val CRITICAL_CHANCE = 0.05
    const val CRITICAL_DAMAGE = 1.5
}

/** 釣り・採取バランスYAMLの省略可能項目に使う互換既定値です。 */
object LifeBalanceDefaults {
    const val LEVEL_CAP = 20
    const val XP_BASE = 80L
    const val XP_EXPONENT = 1.45
    const val CAST_RANGE_BLOCKS = 12.0
    const val CAST_COOLDOWN_MILLIS = 1_500L
    const val MAX_FISHING_SESSIONS = 100
    const val FATIGUE_WINDOW_SECONDS = 600L
    const val FATIGUE_SOFT_THRESHOLD = 12
    const val FATIGUE_HARD_THRESHOLD = 20
    const val FATIGUE_COOLDOWN_SECONDS = 300L
    const val REELING_TIMEOUT_SECONDS = 45L
    const val SLACK_FAIL_SECONDS = 2.5
    const val PARTY_GATHER_RARE_BONUS = 0.08
    const val MACRO_MIN_SAMPLES = 8
    const val MACRO_HISTORY_CAPACITY = 12
    const val MACRO_RESPONSE_DEVIATION_MILLIS = 18.0
    const val MACRO_CAST_INTERVAL_DEVIATION_MILLIS = 120.0
    const val MACRO_RARE_WEIGHT_MULTIPLIER = 0.1
}

/** ワールドtuning YAMLの省略可能項目に対する既存互換値です。 */
object WorldTuningDefaults {
    const val SELL_RATIO = 0.3
    const val MAX_CURRENCY = 100_000_000L
    const val TRANSACTION_AMOUNT_CAP = 64
    const val PARTY_SIZE_CAP = 4
    const val PARTY_XP_RADIUS_BLOCKS = 32.0
    const val GLOBAL_ENEMY_CAP = 100
    const val DUNGEON_INSTANCE_CAP = 8
    const val DUNGEON_INSTANCE_TIMEOUT_TICKS = 36_000L
    const val WEAKNESS_MULTIPLIER = 1.25
    const val RESISTANCE_MULTIPLIER = 0.8
    const val PLAYER_HP_CAP = 1_024.0
    const val BASIC_ATTACK_COOLDOWN_MILLIS = 550L
    const val ELEMENT_RESISTANCE_CAP = 0.5
    const val DAILY_RESET_HOUR = 0
    const val ENHANCEMENT_CAP = GameLimits.MAX_ENHANCEMENT_LEVEL
    const val ENHANCEMENT_RATE = 0.03
    const val ENHANCEMENT_BASE_CURRENCY = 100L
    const val ENHANCEMENT_MATERIAL = "ec:item_mat_artisan_whetstone"
    const val RESET_ZONE = "UTC"
    const val WEEKLY_RESET_DAY = "MONDAY"
}

/** ワールドtuning値のサーバー安全上限です。 */
object WorldTuningLimits {
    const val MAX_SELL_RATIO = 0.49
    const val MAX_CURRENCY = Long.MAX_VALUE / 2
    const val MAX_TRANSACTION_AMOUNT = ContentLimits.MAX_ITEM_STACK
    const val MAX_PARTY_SIZE = 8
    const val MAX_PARTY_XP_RADIUS_BLOCKS = 128.0
    const val MAX_GLOBAL_ENEMY_CAP = 1_000
    const val MAX_DUNGEON_INSTANCES = 32
    const val MAX_PLAYER_HP = 1_024.0
    const val MAX_ELEMENT_RESISTANCE = 0.9
    const val MAX_DAILY_RESET_HOUR = 23
    const val MIN_BASIC_ATTACK_COOLDOWN_MILLIS = 100L
    const val MAX_BASIC_ATTACK_COOLDOWN_MILLIS = 5_000L
    const val MAX_ENHANCEMENT_BASE_CURRENCY = 1_000_000L
}

/** 敵のYAMLで省略可能な値を集め、loader間の既定値差を防ぎます。 */
object EnemyContentDefaults {
    const val SPECIAL_COOLDOWN_TICKS = 200L
    const val SPECIAL_TELEGRAPH_TICKS = 26L
    const val SPECIAL_RADIUS_BLOCKS = 3.0
    const val SPECIAL_RANGE_BLOCKS = 12.0
    const val SPECIAL_DAMAGE_MULTIPLIER = 1.5
    const val SPECIAL_MIN_HP_RATIO = 0.0
    const val SPECIAL_MAX_HP_RATIO = 1.0
    const val SPAWN_POINT_CAP = 1
    const val SPAWN_RESPAWN_TICKS = 200L
    const val SPEED_BLOCKS_PER_TICK = 0.23
    const val SIZE = 1
    const val AGGRO_RADIUS_BLOCKS = 12.0
    const val LEASH_RADIUS_BLOCKS = 24.0
    const val ATTACK_INTERVAL_TICKS = 40L
    const val ENRAGE_TICKS = 7_200L
    const val ENRAGE_MULTIPLIER = 1.5
    const val COOP_HP_PER_ADDITIONAL_PLAYER = 0.75
    const val COOP_DAMAGE_PER_ADDITIONAL_PLAYER = 0.04
    const val SLOW_EFFECT_TICKS = 60
    const val POISON_EFFECT_TICKS = 100
    const val DEFAULT_EFFECT_TICKS = 30
}

/** YAML読込時に適用する構造上の安全上限です。 */
object ContentLimits {
    const val MAX_YAML_ALIASES = 20
    const val MAX_DECLARED_LEVEL = 1_000
    const val MAX_ITEM_STACK = 64
    const val MAX_ITEM_AFFIXES = 4
    const val MAX_CLASS_SKILLS = 4
    const val MAX_STARTER_ITEMS = 8
    const val MAX_SHOP_ENTRIES = 45
    const val MAX_ENEMY_SPAWN_POINT_CAP = 100
    const val MAX_ENEMY_SIZE = 16
    const val MAX_SKILL_COOLDOWN_MILLIS = 3_600_000L
    const val MAX_EFFECT_DURATION_TICKS = 72_000
    const val MAX_SPECIAL_ATTACK_DURATION_TICKS = 200
    const val MAX_SPECIAL_ATTACK_RADIUS_BLOCKS = 32.0
    const val MAX_SPECIAL_ATTACK_RANGE_BLOCKS = 128.0
    const val MAX_SKILL_RANGE_BLOCKS = 128.0
    const val MAX_SKILL_RADIUS_BLOCKS = 32.0
    const val MAX_SKILL_ALLY_RADIUS_BLOCKS = 64.0
    const val MAX_PUBLIC_TITLE_CHARACTERS = 80
    const val MAX_PUBLIC_DESCRIPTION_CHARACTERS = 240
    const val DUNGEON_PALETTE_SIZE = 3
    const val MAX_TACKLE_TIER = 3
    const val MAX_SKILL_THREAT_MULTIPLIER = 100.0
    const val MIN_GATHERING_CHANNEL_MILLIS = 250L
}

/** 生活系計算式の固定係数を集め、コンテンツ値と実装定数を区別します。 */
object GatheringFormula {
    const val TOOL_TIER_RARE_BONUS = 0.05
    const val LIFE_LEVEL_RARE_BONUS = 0.005
    const val MAX_RARE_BONUS = 0.45
    const val MAX_RARE_CHANCE = 0.95
    const val MAX_PARTY_BONUS = 0.25
}

/** 装備budget計算の重みを名前で表し、YAMLのbudget値との式を読みやすくします。 */
object ItemBudgetWeights {
    const val HP_PER_POINT = 10.0
    const val RESOURCE_PER_POINT = 10.0
    const val CRIT = 100.0
    const val CRIT_DAMAGE = 10.0
    const val ELEMENT_RESIST = 100.0
    const val SKILL_POWER = 10.0
}

/** 図鑑称号を解除する進捗率の基準です。 */
object CodexMilestones {
    val completionPercentages = listOf(25, 50, 75, 100)
}

/** 進捗率を計算・表示するときの共通尺度です。 */
object PercentageScale {
    const val MAX_PERCENT = 100
}

/** ゲーム内の固定暦期間です。暦日リセットは別途ゾーン付きResetPeriodsを使います。 */
object GameDurations {
    const val MILLIS_PER_SECOND = 1_000L
    val PARTY_INVITE_MILLIS = java.time.Duration.ofMinutes(1).toMillis()
    val DEFAULT_QUEST_REPEAT_MILLIS = java.time.Duration.ofDays(1).toMillis()
}
