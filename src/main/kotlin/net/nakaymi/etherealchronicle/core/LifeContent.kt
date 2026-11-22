package net.nakaymi.etherealchronicle.core

import kotlin.math.pow

/** 生活スキルの保存・成長カテゴリです。 */
enum class LifeSkillKind { FISHING, GATHERING }
enum class TackleKind { ROD, BAIT, PICKAXE, SICKLE, HATCHET, EXTRACTOR }
enum class NodeKind { ORE, HERB, WOOD, CRYSTAL }
enum class Crown { NONE, SILVER, GOLD }

data class LifeMilestone(val threshold: Int, val uniqueNodes: Int = 0, val flag: String)
data class LifeSkillProgress(var level: Int = 1, var xp: Long = 0)
data class FishRecord(var count: Int = 0, var maxCm: Double = 0.0, var crown: Crown = Crown.NONE)
/** 釣り・採取の全体上限、疲労、経験値、pityをまとめた設定です。 */
data class LifeBalance(
    val levelCap: Int = LifeBalanceDefaults.LEVEL_CAP,
    val xpBase: Long = LifeBalanceDefaults.XP_BASE,
    val xpExponent: Double = LifeBalanceDefaults.XP_EXPONENT,
    val castRange: Double = LifeBalanceDefaults.CAST_RANGE_BLOCKS,
    val castCooldownMillis: Long = LifeBalanceDefaults.CAST_COOLDOWN_MILLIS,
    val maxFishingSessions: Int = LifeBalanceDefaults.MAX_FISHING_SESSIONS,
    val fatigueWindowSeconds: Long = LifeBalanceDefaults.FATIGUE_WINDOW_SECONDS,
    val fatigueSoftThreshold: Int = LifeBalanceDefaults.FATIGUE_SOFT_THRESHOLD,
    val fatigueHardThreshold: Int = LifeBalanceDefaults.FATIGUE_HARD_THRESHOLD,
    val fatigueCooldownSeconds: Long = LifeBalanceDefaults.FATIGUE_COOLDOWN_SECONDS,
    val reelingTimeoutSeconds: Long = LifeBalanceDefaults.REELING_TIMEOUT_SECONDS,
    val slackFailSeconds: Double = LifeBalanceDefaults.SLACK_FAIL_SECONDS,
    val partyGatherRareBonus: Double = LifeBalanceDefaults.PARTY_GATHER_RARE_BONUS,
    val fishingMilestones: List<LifeMilestone> = emptyList(),
    val gatheringMilestones: List<LifeMilestone> = emptyList(),
    val macroMinSamples: Int = LifeBalanceDefaults.MACRO_MIN_SAMPLES,
    val macroResponseDeviationMillis: Double = LifeBalanceDefaults.MACRO_RESPONSE_DEVIATION_MILLIS,
    val macroCastIntervalDeviationMillis: Double = LifeBalanceDefaults.MACRO_CAST_INTERVAL_DEVIATION_MILLIS,
    val macroRareWeightMultiplier: Double = LifeBalanceDefaults.MACRO_RARE_WEIGHT_MULTIPLIER
)
/** 釣具・餌の効果と装備条件を表します。 */
data class TackleDefinition(
    val id: String,
    val item: String,
    val kind: TackleKind,
    val tier: Int,
    val minLifeLevel: Int,
    val waitReduction: Double = 0.0,
    val sweetSpotBonus: Double = 0.0,
    val tensionStability: Double = 1.0,
    val sizeBonus: Double = 0.0,
    val channelSpeedBonus: Double = 0.0,
    val rareChanceBonus: Double = 0.0,
    val rarityWeights: Map<String, Double> = emptyMap(),
    val preferredTags: Set<String> = emptySet(),
    val preferredTagMultiplier: Double = 1.0
)
data class FishingSpotDefinition(val id: String, val name: String, val area: String, val world: String, val center: Position, val radius: Double, val defaultForArea: Boolean, val tags: Set<String>, val priority: Int = 0, val unlockFlag: String? = null)
fun selectFishingSpot(spots:Collection<FishingSpotDefinition>,world:String,x:Double,z:Double,flags:Set<String>):FishingSpotDefinition? =
    spots.asSequence().filter { s -> s.world==world && (s.unlockFlag==null || s.unlockFlag in flags) && s.center.let { center -> val dx=x-center.x; val dz=z-center.z; dx*dx+dz*dz<=s.radius*s.radius } }
        .sortedWith(compareByDescending<FishingSpotDefinition> { it.priority }.thenBy { it.radius }.thenBy { it.id }).firstOrNull()
data class FishMinigame(val biteWindowMillis: Long, val difficulty: Double, val sweetWidth: Double, val progressPerSecond: Double, val tensionDecayPerSecond: Double, val thrashChancePerSecond: Double, val diveChancePerSecond: Double)
/** 釣り場条件、サイズ記録、報酬、ミニゲームを持つ魚種です。 */
data class FishSpeciesDefinition(
    val id: String, val name: String, val item: String, val rarity: Rarity, val tags: Set<String>, val habitats: Set<String>,
    val timeWindows: Set<String>, val weather: Set<String>, val minRodTier: Int, val minFishingLevel: Int, val baseWeight: Double,
    val minCm: Double, val maxCm: Double, val silverCrownCm: Double, val goldCrownCm: Double, val lifeXp: Long,
    val adventurerXp: Long, val minigame: FishMinigame, val loreHint: String
)
data class NodeYield(val item: String, val min: Int, val max: Int, val chance: Double, val pityAfter: Int? = null)
data class GatheringLocation(val id: String, val area: String, val world: String, val x: Int, val y: Int, val z: Int)
/** 必要道具・再出現・収穫表・配置を持つ採取ノードです。 */
data class GatheringNodeDefinition(
    val id: String, val name: String, val kind: NodeKind, val displayMaterial: String, val depletedMaterial: String,
    val toolKind: TackleKind, val minToolTier: Int, val minGatheringLevel: Int, val channelMillis: Long,
    val respawnSeconds: Long, val perPlayerRespawn: Boolean, val lifeXp: Long, val adventurerXp: Long,
    val yields: List<NodeYield>, val rareYields: List<NodeYield>, val locations: List<GatheringLocation>
)

/** 生活スキルの必要経験値と上限付きレベル成長を計算します。 */
class LifeSkillEngine(private val balance: LifeBalance) {
    /** 現在レベルから次のレベルに必要な経験値を返します。 */
    fun requiredXp(level: Int): Long {
        val safe=level.coerceIn(1,balance.levelCap)
        return (balance.xpBase.toDouble()*safe.toDouble().pow(balance.xpExponent)).toLong().coerceAtLeast(1)
    }
    /** 加算オーバーフローを避け、上限までのレベル上昇数を返します。 */
    fun grant(progress: LifeSkillProgress,amount: Long): Int {
        require(amount >= 0)
        val before=progress.level
        progress.xp=(progress.xp.coerceAtMost(Long.MAX_VALUE-amount)+amount)
        while(progress.level < balance.levelCap && progress.xp >= requiredXp(progress.level)) {
            progress.xp-=requiredXp(progress.level); progress.level++
        }
        if(progress.level >= balance.levelCap) progress.xp=0
        return progress.level-before
    }
}

data class GatherResult(val item: String,val amount: Int,val rare: Boolean)
/** 採取条件、抽選、pity、個人再出現を副作用範囲を限定して処理します。 */
class GatheringEngine(private val random: kotlin.random.Random = kotlin.random.Random.Default) {
    /** ノードの道具種・tier・生活レベルの条件を判定します。 */
    fun canHarvest(node: GatheringNodeDefinition,toolKind: TackleKind,toolTier: Int,lifeLevel: Int): Boolean =
        toolKind == node.toolKind && toolTier >= node.minToolTier && lifeLevel >= node.minGatheringLevel

    /** 稀少報酬のpityをノード・アイテムごとに保存します。成功時は0へ戻し、パーティ補正は確率のみへ加えて抽選回数を増やしません。 */
    fun roll(node: GatheringNodeDefinition,toolTier: Int,lifeLevel: Int,partyNearby: Boolean,partyBonus: Double,
             pity: MutableMap<String,Int> = mutableMapOf(),attemptCap: Int = 100): List<GatherResult> {
        require(toolTier in 1..3 && lifeLevel >= 1 && partyBonus in 0.0..0.25)
        require(attemptCap in 1..GameLimits.MAX_GATHER_ATTEMPTS)
        val bonus=if(partyNearby) partyBonus else 0.0
        return buildList {
            node.yields.forEach { y -> if(random.nextDouble() < y.chance) add(GatherResult(y.item,random.nextInt(y.min,y.max+1),false)) }
            val rareBoost=((toolTier-node.minToolTier).coerceAtLeast(0)*GatheringFormula.TOOL_TIER_RARE_BONUS+
                (lifeLevel-1)*GatheringFormula.LIFE_LEVEL_RARE_BONUS+bonus).coerceAtMost(GatheringFormula.MAX_RARE_BONUS)
            node.rareYields.forEach { y ->
                val key="gathering:${node.id}:${y.item}"
                val attempts=pity.getOrDefault(key,0).coerceIn(0,attemptCap)
                val chance=(y.chance+rareBoost).coerceAtMost(GatheringFormula.MAX_RARE_CHANCE)
                val guaranteed=y.pityAfter != null && attempts >= y.pityAfter-1
                val success=guaranteed || random.nextDouble() < chance
                pity[key]=if(success) 0 else (attempts+1).coerceAtMost(attemptCap)
                if(success) add(GatherResult(y.item,random.nextInt(y.min,y.max+1),true))
            }
        }
    }
    /** 指定プレイヤーの採取地点が再出現時刻に達したかを返します。 */
    fun readyAt(profile: PlayerProfile,nodeId: String,nowMillis: Long) = (profile.gatheringReadyAt[nodeId] ?: 0L) <= nowMillis
    /** 再出現可能な地点を予約し、成功した場合だけ状態を更新します。 */
    fun reserve(profile: PlayerProfile,locationKey: String,respawnSeconds: Long,nowMillis: Long): Boolean {
        require(respawnSeconds in 1..GameLimits.MAX_GATHERING_RESPAWN_SECONDS && nowMillis >= 0)
        if(!readyAt(profile,locationKey,nowMillis)) return false
        profile.gatheringReadyAt[locationKey]=nowMillis+respawnSeconds*GameDurations.MILLIS_PER_SECOND
        return true
    }
}
