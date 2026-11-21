package net.nakaymi.etherealchronicle.core

import kotlin.math.pow

enum class LifeSkillKind { FISHING, GATHERING }
enum class TackleKind { ROD, BAIT, PICKAXE, SICKLE, HATCHET, EXTRACTOR }
enum class NodeKind { ORE, HERB, WOOD, CRYSTAL }
enum class Crown { NONE, SILVER, GOLD }

data class LifeMilestone(val threshold: Int, val uniqueNodes: Int = 0, val flag: String)
data class LifeSkillProgress(var level: Int = 1, var xp: Long = 0)
data class FishRecord(var count: Int = 0, var maxCm: Double = 0.0, var crown: Crown = Crown.NONE)
data class LifeBalance(
    val levelCap: Int = 20,
    val xpBase: Long = 80,
    val xpExponent: Double = 1.45,
    val castRange: Double = 12.0,
    val castCooldownMillis: Long = 1500,
    val maxFishingSessions: Int = 100,
    val fatigueWindowSeconds: Long = 600,
    val fatigueSoftThreshold: Int = 12,
    val fatigueHardThreshold: Int = 20,
    val fatigueCooldownSeconds: Long = 300,
    val reelingTimeoutSeconds: Long = 45,
    val slackFailSeconds: Double = 2.5,
    val partyGatherRareBonus: Double = 0.08,
    val fishingMilestones: List<LifeMilestone> = emptyList(),
    val gatheringMilestones: List<LifeMilestone> = emptyList(),
    val macroMinSamples: Int = 8,
    val macroResponseDeviationMillis: Double = 18.0,
    val macroCastIntervalDeviationMillis: Double = 120.0,
    val macroRareWeightMultiplier: Double = 0.1
)
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
data class FishingSpotDefinition(val id: String, val name: String, val area: String, val world: String, val center: Position, val radius: Double, val defaultForArea: Boolean, val tags: Set<String>)
data class FishMinigame(val biteWindowMillis: Long, val difficulty: Double, val sweetWidth: Double, val progressPerSecond: Double, val tensionDecayPerSecond: Double, val thrashChancePerSecond: Double, val diveChancePerSecond: Double)
data class FishSpeciesDefinition(
    val id: String, val name: String, val item: String, val rarity: Rarity, val tags: Set<String>, val habitats: Set<String>,
    val timeWindows: Set<String>, val weather: Set<String>, val minRodTier: Int, val minFishingLevel: Int, val baseWeight: Double,
    val minCm: Double, val maxCm: Double, val silverCrownCm: Double, val goldCrownCm: Double, val lifeXp: Long,
    val adventurerXp: Long, val minigame: FishMinigame, val loreHint: String
)
data class NodeYield(val item: String, val min: Int, val max: Int, val chance: Double, val pityAfter: Int? = null)
data class GatheringLocation(val id: String, val area: String, val world: String, val x: Int, val y: Int, val z: Int)
data class GatheringNodeDefinition(
    val id: String, val name: String, val kind: NodeKind, val displayMaterial: String, val depletedMaterial: String,
    val toolKind: TackleKind, val minToolTier: Int, val minGatheringLevel: Int, val channelMillis: Long,
    val respawnSeconds: Long, val perPlayerRespawn: Boolean, val lifeXp: Long, val adventurerXp: Long,
    val yields: List<NodeYield>, val rareYields: List<NodeYield>, val locations: List<GatheringLocation>
)

class LifeSkillEngine(private val balance: LifeBalance) {
    fun requiredXp(level: Int): Long {
        val safe=level.coerceIn(1,balance.levelCap)
        return (balance.xpBase.toDouble()*safe.toDouble().pow(balance.xpExponent)).toLong().coerceAtLeast(1)
    }
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
class GatheringEngine(private val random: kotlin.random.Random = kotlin.random.Random.Default) {
    fun canHarvest(node: GatheringNodeDefinition,toolKind: TackleKind,toolTier: Int,lifeLevel: Int): Boolean =
        toolKind == node.toolKind && toolTier >= node.minToolTier && lifeLevel >= node.minGatheringLevel

    /**
     * Rare pity is stored in the existing profile dropPity map. A failed rare roll
     * advances its own node/item counter; a success (including pity) resets it.
     * This is per recipient, so party proximity changes chance but never multiplies
     * the number of attempts or rewards.
     */
    fun roll(node: GatheringNodeDefinition,toolTier: Int,lifeLevel: Int,partyNearby: Boolean,partyBonus: Double,
             pity: MutableMap<String,Int> = mutableMapOf(),attemptCap: Int = 100): List<GatherResult> {
        require(toolTier in 1..3 && lifeLevel >= 1 && partyBonus in 0.0..0.25)
        require(attemptCap in 1..10_000)
        val bonus=if(partyNearby) partyBonus else 0.0
        return buildList {
            node.yields.forEach { y -> if(random.nextDouble() < y.chance) add(GatherResult(y.item,random.nextInt(y.min,y.max+1),false)) }
            val rareBoost=((toolTier-node.minToolTier).coerceAtLeast(0)*0.05+(lifeLevel-1)*0.005+bonus).coerceAtMost(0.45)
            node.rareYields.forEach { y ->
                val key="gathering:${node.id}:${y.item}"
                val attempts=pity.getOrDefault(key,0).coerceIn(0,attemptCap)
                val chance=(y.chance+rareBoost).coerceAtMost(0.95)
                val guaranteed=y.pityAfter != null && attempts >= y.pityAfter-1
                val success=guaranteed || random.nextDouble() < chance
                pity[key]=if(success) 0 else (attempts+1).coerceAtMost(attemptCap)
                if(success) add(GatherResult(y.item,random.nextInt(y.min,y.max+1),true))
            }
        }
    }
    fun readyAt(profile: PlayerProfile,nodeId: String,nowMillis: Long) = (profile.gatheringReadyAt[nodeId] ?: 0L) <= nowMillis
    fun reserve(profile: PlayerProfile,locationKey: String,respawnSeconds: Long,nowMillis: Long): Boolean {
        require(respawnSeconds in 1..86_400 && nowMillis >= 0)
        if(!readyAt(profile,locationKey,nowMillis)) return false
        profile.gatheringReadyAt[locationKey]=nowMillis+respawnSeconds*1000
        return true
    }
}
