package dev.mcmmo.rpg.core

import java.util.UUID
import kotlin.math.pow
import kotlin.random.Random

data class Stats(val hp: Double = 100.0, val resource: Double = 100.0, val attack: Double = 10.0,
    val defense: Double = 0.0, val crit: Double = 0.05, val critDamage: Double = 1.5,
    val elementResist: Double = 0.0, val skillPower: Double = 1.0) {
    operator fun plus(other: Stats) = Stats(hp + other.hp, resource + other.resource, attack + other.attack,
        defense + other.defense, crit + other.crit, critDamage + other.critDamage,
        elementResist + other.elementResist, skillPower + other.skillPower)
    companion object { val ZERO = Stats(0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0) }
}

data class Balance(val levelCap: Int, val xpBase: Double, val xpExponent: Double, val xpGainCap: Long,
    val currencyGainCap: Long, val damageCap: Double, val statCap: Double, val critCap: Double,
    val defenseScale: Double, val skillLevelCap: Int, val skillGrowth: Double, val critDamageCap: Double, val elementCap: Double) {
    fun requiredXp(level: Int): Long = (xpBase * level.coerceIn(1, levelCap).toDouble().pow(xpExponent)).toLong().coerceAtLeast(1)
    fun damage(stats: Stats, defense: Double, multiplier: Double, weapon: Double, critical: Boolean, element: Double = 1.0): Double {
        val base = stats.attack.coerceIn(0.0, statCap) * multiplier.coerceAtLeast(0.0) + weapon.coerceIn(0.0, statCap)
        return (base * defenseScale / (defenseScale + defense.coerceIn(0.0, statCap)) *
            (if (critical) stats.critDamage.coerceIn(1.0, critDamageCap) else 1.0) * element.coerceIn(0.0, elementCap)).coerceIn(0.0, damageCap)
    }
    fun grantXp(player: PlayerProfile, amount: Long): Int {
        require(amount >= 0)
        val gain = amount.coerceAtMost(xpGainCap)
        player.xp = player.xp.coerceAtMost(Long.MAX_VALUE - gain) + gain
        val before = player.level
        while (player.level < levelCap && player.xp >= requiredXp(player.level)) {
            player.xp -= requiredXp(player.level); player.level++
        }
        if (player.level == levelCap) player.xp = 0
        return player.level - before
    }
}

enum class ResourceKind { RAGE, FOCUS, MANA }
data class ClassDefinition(val id: String, val name: String, val resource: ResourceKind, val stats: Stats,
    val regeneration: Double, val hitGain: Double, val skills: List<String>, val starterItems: List<String>)
data class SkillDefinition(val id: String, val name: String, val cost: Double, val cooldownMillis: Long,
    val multiplier: Double, val range: Double)
fun interface Clock { fun millis(): Long }
enum class CastResult { SUCCESS, COOLDOWN, RESOURCE, INVALID_LEVEL }
class SkillEngine(private val clock: Clock, private var balance: Balance) {
    fun updateBalance(value: Balance) { balance = value }
    private val ready = mutableMapOf<Pair<UUID, String>, Long>()
    fun remaining(player: UUID, skill: String) = ((ready[player to skill] ?: 0) - clock.millis()).coerceAtLeast(0)
    fun cast(player: PlayerProfile, skill: SkillDefinition): CastResult {
        val level = player.skillLevels.getOrDefault(skill.id, 1)
        if (level !in 1..balance.skillLevelCap) return CastResult.INVALID_LEVEL
        if (remaining(player.uuid, skill.id) > 0) return CastResult.COOLDOWN
        if (player.resource < skill.cost) return CastResult.RESOURCE
        player.resource -= skill.cost
        ready[player.uuid to skill.id] = clock.millis() + skill.cooldownMillis
        return CastResult.SUCCESS
    }
    fun multiplier(player: PlayerProfile, skill: SkillDefinition) = skill.multiplier *
        (1 + (player.skillLevels.getOrDefault(skill.id, 1).coerceIn(1, balance.skillLevelCap) - 1) * balance.skillGrowth)
    fun cleanup() { val now = clock.millis(); ready.entries.removeIf { it.value <= now } }
}

enum class Rarity { COMMON, UNCOMMON, RARE, EPIC, LEGENDARY }
data class ItemDefinition(val id: String, val name: String, val material: String, val rarity: Rarity,
    val minLevel: Int, val classes: List<String>, val stats: Stats, val budget: Double, val budgetMultiplier: Double = 1.0, val affixes: Int = 0) {
    fun canEquip(player: PlayerProfile) = player.level >= minLevel && (classes.isEmpty() || player.classId in classes)
    fun withinBudget() = stats.hp / 10 + stats.resource / 10 + stats.attack + stats.defense + stats.crit * 100 +
        stats.critDamage * 10 + stats.elementResist * 100 + stats.skillPower * 10 <= budget * budgetMultiplier
}
data class DropEntry(val item: String, val chance: Double, val min: Int, val max: Int)
data class DropTable(val id: String, val entries: List<DropEntry>) {
    fun roll(random: Random): Map<String, Int> = buildMap {
        this@DropTable.entries.forEach { if (random.nextDouble() < it.chance) put(it.item, getOrDefault(it.item, 0) + random.nextInt(it.min, it.max + 1)) }
    }
}
data class PlayerProfile(val uuid: UUID, var classId: String? = null, var level: Int = 1, var xp: Long = 0,
    var currency: Long = 0, var resource: Double = 0.0, var selectedSkill: Int = 0,
    var starterGranted: Boolean = false, var tutorialQuest: String? = null,
    val skillLevels: MutableMap<String, Int> = mutableMapOf()) {
    fun snapshot() = copy(skillLevels = skillLevels.toMutableMap())
}




