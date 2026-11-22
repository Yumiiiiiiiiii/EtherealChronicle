package net.nakaymi.etherealchronicle.core

/** ゲーム全体で共有するプロフィール・取引の上限です。 */
object GameLimits {
    const val MAX_ENHANCEMENT_LEVEL = 5
    const val MAX_TRANSACTION_HISTORY = 128
    const val MAX_PROFILE_LEVEL = 1_000
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
    const val MACRO_RESPONSE_DEVIATION_MILLIS = 18.0
    const val MACRO_CAST_INTERVAL_DEVIATION_MILLIS = 120.0
    const val MACRO_RARE_WEIGHT_MULTIPLIER = 0.1
}

/** YAML読込時に適用する構造上の安全上限です。 */
object ContentLimits {
    const val MAX_YAML_ALIASES = 20
    const val MAX_DECLARED_LEVEL = 1_000
    const val MAX_ITEM_STACK = 64
    const val MAX_ITEM_AFFIXES = 4
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

/** ゲーム内の固定暦期間です。暦日リセットは別途ゾーン付きResetPeriodsを使います。 */
object GameDurations {
    const val MILLIS_PER_SECOND = 1_000L
    val PARTY_INVITE_MILLIS = java.time.Duration.ofMinutes(1).toMillis()
    val DEFAULT_QUEST_REPEAT_MILLIS = java.time.Duration.ofDays(1).toMillis()
    val MILLIS_PER_DAY = java.util.concurrent.TimeUnit.DAYS.toMillis(1)
}
