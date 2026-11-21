package net.nakaymi.etherealchronicle.core

/** YAMLから読み込む依頼条件。すべての条件群を満たしたときに依頼を受注できます。 */
data class Condition(
    val minLevel: Int = 1,
    val hasFlags: Set<String> = emptySet(),
    val notFlags: Set<String> = emptySet(),
    val completed: Set<String> = emptySet(),
    val active: Set<String> = emptySet(),
    val notStarted: Set<String> = emptySet(),
    val classes: Set<String> = emptySet(),
    val any: List<Condition> = emptyList(),
) {
    /** 条件を一括評価します。`any` は子条件のいずれかが成立すれば通過します。 */
    fun matches(p: PlayerProfile, now: Long = System.currentTimeMillis()): Boolean =
        p.level >= minLevel &&
            p.flags.containsAll(hasFlags) &&
            notFlags.none { it in p.flags } &&
            completed.all { id -> p.quests[id]?.let { it.completed || it.completedAt > 0 } == true } &&
            active.all { id -> p.quests[id]?.completed == false } &&
            notStarted.all { id -> p.quests[id]?.let { it.completed && now >= it.repeatAvailableAt } ?: true } &&
            (classes.isEmpty() || p.classId in classes) &&
            (any.isEmpty() || any.any { it.matches(p, now) })
}

enum class ObjectiveKind { KILL, COLLECT, EXPLORE, TALK, CATCH_FISH, GATHER_NODE, ENHANCE_GEAR }

data class Objective(val id: String, val kind: ObjectiveKind, val target: String, val amount: Int, val consume: Boolean = true)
data class ItemReward(val item: String, val amount: Int, val condition: Condition = Condition())
data class Rewards(val xp: Long = 0, val currency: Long = 0, val items: List<ItemReward> = emptyList(), val flags: Set<String> = emptySet())
data class QuestDefinition(
    val id: String,
    val name: String,
    val condition: Condition,
    val objectives: List<Objective>,
    val rewards: Rewards,
    val autoComplete: Boolean,
    val repeatable: Boolean,
    val repeatCooldown: Long,
    val resetSchedule: String? = null,
)

data class DialogueAction(
    val kind: String,
    val target: String,
    val amount: Int = 1,
    val onceFlag: String? = null,
    val destination: Position? = null,
    val requiredFlag: String? = null,
)
data class DialogueChoice(
    val id: String,
    val label: String,
    val condition: Condition,
    val next: String?,
    val flags: Set<String>,
    val clearFlags: Set<String>,
    val actions: List<DialogueAction>,
)
data class DialogueNode(val text: String, val branches: List<Pair<Condition, String>>, val choices: List<DialogueChoice>)
data class DialogueDefinition(val id: String, val start: String, val nodes: Map<String, DialogueNode>, val globalChoices: List<DialogueChoice> = emptyList())

/** ワールド上の固定位置を表す値です。YAML、テレポート、エリア判定で共通利用します。 */
data class Position(val world: String, val x: Double, val y: Double, val z: Double, val yaw: Float = 0f)

/** 座標を含むゲームエリア。 */
data class Area(
    val id: String,
    val name: String,
    val world: String,
    val minX: Double,
    val maxX: Double,
    val minY: Double,
    val maxY: Double,
    val minZ: Double,
    val maxZ: Double,
    val safe: Boolean,
) {
    /** ワールド名と3軸の範囲が一致するかを返します。 */
    fun contains(position: Position): Boolean =
        position.world == world && position.x in minX..maxX && position.y in minY..maxY && position.z in minZ..maxZ
}

data class DungeonWave(val boss: String, val guards: List<String>, val guardOffsets: List<Pair<Int, Int>>, val bossOffset: Pair<Int, Int>)
data class ScoreRank(val rank: String, val maxSeconds: Long, val firstClearFlag: String? = null, val firstClearItems: Map<String, Int> = emptyMap())
enum class TitleConditionType { FLAG, BESTIARY, COLLECTION, FISH_SPECIES_COUNT, LIFE_LEVEL, ENHANCE_LEVEL, CHALLENGE_RANK }

data class TitleProgressCondition(val type: TitleConditionType, val target: String?, val count: Int, val rank: String = "S") {
    /** プロフィール上の条件を評価します。永続化される解除状態は引き続きunlockFlagが正本です。 */
    fun met(profile: PlayerProfile, unlockFlag: String): Boolean = when (type) {
        TitleConditionType.FLAG -> (target ?: unlockFlag) in profile.flags
        TitleConditionType.BESTIARY -> (target?.let(profile.bestiary::get) ?: 0) >= count
        TitleConditionType.COLLECTION -> profile.discoveredItems.size >= count
        TitleConditionType.FISH_SPECIES_COUNT -> profile.fishRecords.values.count { it.count > 0 } >= count
        TitleConditionType.LIFE_LEVEL -> runCatching { LifeSkillKind.valueOf(target ?: "") }.getOrNull()
            ?.let { (profile.lifeSkills[it]?.level ?: 1) >= count } ?: false
        TitleConditionType.ENHANCE_LEVEL -> profile.bestEnhancementLevel >= count
        TitleConditionType.CHALLENGE_RANK -> profile.challengeRecords[target]?.bestRank
            ?.let { challengeRankValue(it) >= challengeRankValue(rank) } ?: false
    }
}

internal fun challengeRankValue(rank: String): Int = when (rank.uppercase()) {
    "S" -> 4
    "A" -> 3
    "B" -> 2
    "C" -> 1
    else -> 0
}

/** 所要時間に合う初回クリアランクのうち、未獲得報酬があるものを返します。 */
fun firstClearRanks(profile: PlayerProfile, ranks: List<ScoreRank>, seconds: Long): List<ScoreRank> =
    ranks.filter { entry -> entry.firstClearFlag != null && seconds <= entry.maxSeconds && entry.firstClearFlag !in profile.flags }

data class TitleAdvancement(val title: String, val description: String, val icon: String, val parent: String?, val frame: String, val condition: TitleProgressCondition)
data class TitleDefinition(val id: String, val name: String, val description: String, val unlockFlag: String, val advancement: TitleAdvancement? = null) {
    /** 従来のflag解除と任意の進捗条件の双方を考慮します。 */
    fun unlocked(profile: PlayerProfile): Boolean = unlockFlag in profile.flags || advancement?.condition?.met(profile, unlockFlag) == true
}

data class DungeonDefinition(
    val id: String,
    val unlockFlag: String,
    val boss: String,
    val guards: List<String>,
    val guardOffsets: List<Pair<Int, Int>>,
    val area: String,
    val palette: List<String>,
    val bossOffset: Pair<Int, Int>,
    val entryMessage: String,
    val clearMessage: String,
    val waves: List<DungeonWave> = emptyList(),
    val scoreRanks: List<ScoreRank> = emptyList(),
)
data class NpcDefinition(
    val id: String,
    val name: String,
    val position: Position,
    val dialogue: String,
    val shop: String?,
    val timeWindows: Set<String> = setOf("ANY"),
    val weather: Set<String> = setOf("ANY"),
)
data class TokenCost(val item: String, val amount: Int)
data class ShopEntry(
    val id: String,
    val item: String,
    val amount: Int,
    val price: Long,
    val tokens: List<TokenCost>,
    val condition: Condition,
    val dailyLimit: Int,
    val tokenDiscountFlag: String?,
    val tokenDiscount: List<TokenCost>,
)
data class ShopDefinition(val id: String, val name: String, val buyback: Boolean, val discountFlag: String?, val discount: Double, val entries: List<ShopEntry>)
data class SpawnPoint(
    val id: String,
    val position: Position,
    val cap: Int,
    val respawnTicks: Long,
    val timeWindows: Set<String> = setOf("ANY"),
    val weather: Set<String> = setOf("ANY"),
)
data class TreasureChestDefinition(val id: String, val position: Position, val onceFlag: String, val items: Map<String, Int>, val currency: Long, val hintParticle: String)

/** 個人宝箱の報酬を保留箱・通貨・once flagへ一度だけ反映します。 */
fun claimTreasure(profile: PlayerProfile, chest: TreasureChestDefinition, currencyCap: Long): Boolean {
    if (chest.onceFlag in profile.flags) return false

    require(chest.currency >= 0 && currencyCap >= 0 && profile.currency <= Long.MAX_VALUE - chest.currency && profile.currency + chest.currency <= currencyCap) {
        "ec.error.currency_cap"
    }
    require(chest.items.values.all { it in 1..64 }) { "ec.error.invalid_content" }
    chest.items.forEach { (item, amount) -> profile.pendingRewards.merge(item, amount, Int::plus) }
    profile.currency += chest.currency
    profile.flags.add(chest.onceFlag)
    return true
}

/** 時刻と天候の宣言条件を検証し、現在のワールド状態との一致を判定します。 */
object WorldSchedule {
    private val validTime = setOf("ANY", "DAY", "NIGHT", "DAWN_DUSK")
    private val validWeather = setOf("ANY", "CLEAR", "RAIN", "THUNDER")

    fun valid(timeWindows: Set<String>, weather: Set<String>): Boolean =
        timeWindows.isNotEmpty() && timeWindows.all { it in validTime } && weather.isNotEmpty() && weather.all { it in validWeather }

    fun matches(timeWindows: Set<String>, weather: Set<String>, time: Long, hasStorm: Boolean, thundering: Boolean): Boolean {
        val dayTime = Math.floorMod(time, 24_000L)
        val timeMatches = "ANY" in timeWindows || timeWindows.any { window ->
            when (window) {
                "DAY" -> dayTime in 1_000L..11_999L
                "NIGHT" -> dayTime in 13_000L..22_999L
                "DAWN_DUSK" -> dayTime < 1_000L || dayTime in 12_000L..12_999L || dayTime >= 23_000L
                else -> false
            }
        }
        val currentWeather = if (thundering) "THUNDER" else if (hasStorm) "RAIN" else "CLEAR"
        return timeMatches && ("ANY" in weather || currentWeather in weather)
    }
}

data class SpecialAttack(
    val id: String,
    val name: String,
    val shape: String,
    val cooldown: Long,
    val telegraph: Long,
    val radius: Double,
    val range: Double,
    val multiplier: Double,
    val minHp: Double,
    val maxHp: Double,
    val effect: String,
    val element: String? = null,
    val effectDuration: Int = 30,
)
data class BossPhase(val id: String, val below: Double, val damage: Double, val interval: Double)
data class EnemyDefinition(
    val id: String,
    val name: String,
    val entity: String,
    val level: Int,
    val hp: Double,
    val attack: Double,
    val defense: Double,
    val element: String,
    val weaknesses: Set<String>,
    val resistances: Set<String>,
    val speed: Double,
    val size: Int,
    val aggro: Double,
    val leash: Double,
    val attackInterval: Long,
    val traits: Set<String>,
    val specials: List<SpecialAttack>,
    val spawns: List<SpawnPoint>,
    val flags: Set<String>,
    val boss: Boolean,
    val phases: List<BossPhase>,
    val enrageTicks: Long,
    val enrageMultiplier: Double,
    val coopHp: Double,
    val coopDamage: Double,
    val visualVariant: String? = null,
)
data class ItemEffect(val kind: String, val amount: Double, val cooldown: Long, val duration: Int, val chance: Double, val element: String)
data class ItemProperties(val bound: Boolean, val questItem: Boolean, val price: Long, val weapon: Double, val lore: String, val effects: List<ItemEffect>)
