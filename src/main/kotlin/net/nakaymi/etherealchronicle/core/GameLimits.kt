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
    val PARTY_INVITE_MILLIS = java.time.Duration.ofMinutes(1).toMillis()
    val DEFAULT_QUEST_REPEAT_MILLIS = java.time.Duration.ofDays(1).toMillis()
    val MILLIS_PER_DAY = java.util.concurrent.TimeUnit.DAYS.toMillis(1)
}
