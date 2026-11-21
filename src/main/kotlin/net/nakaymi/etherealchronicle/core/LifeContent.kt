package net.nakaymi.etherealchronicle.core

enum class LifeSkillKind { FISHING, GATHERING }
enum class TackleKind { ROD, BAIT, PICKAXE, SICKLE, HATCHET, EXTRACTOR }
enum class NodeKind { ORE, HERB, WOOD, CRYSTAL }
enum class Crown { NONE, SILVER, GOLD }

data class LifeMilestone(val threshold: Int, val uniqueNodes: Int = 0, val flag: String)
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
    val gatheringMilestones: List<LifeMilestone> = emptyList()
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
