package net.nakaymi.etherealchronicle.qa

import net.nakaymi.etherealchronicle.core.Balance
import net.nakaymi.etherealchronicle.core.BossState
import net.nakaymi.etherealchronicle.core.CastResult
import net.nakaymi.etherealchronicle.core.Clock
import net.nakaymi.etherealchronicle.core.EnemyDefinition
import net.nakaymi.etherealchronicle.core.GameService
import net.nakaymi.etherealchronicle.core.GearSlot
import net.nakaymi.etherealchronicle.core.ItemDefinition
import net.nakaymi.etherealchronicle.core.ObjectiveKind
import net.nakaymi.etherealchronicle.core.PlayerProfile
import net.nakaymi.etherealchronicle.core.Rarity
import net.nakaymi.etherealchronicle.core.ResourceKind
import net.nakaymi.etherealchronicle.core.SkillEngine
import net.nakaymi.etherealchronicle.core.Stats
import net.nakaymi.etherealchronicle.data.ContentService
import net.nakaymi.etherealchronicle.data.ContentSnapshot
import net.nakaymi.etherealchronicle.data.long
import net.nakaymi.etherealchronicle.data.strings
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.Locale
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.assertTrue

/**
 * Full Chapter 1 balance simulator driven by the real core combat/XP/drop engines
 * and bundled YAML definitions under src/main/resources/content.
 */
class BalanceSimulationTest {
    private val snap: ContentSnapshot = ContentService(Path.of("src/main/resources/content")).load()
    private val classes = listOf("ec:class_warrior", "ec:class_archer", "ec:class_mage")

    private data class CombatOutcome(
        val classId: String,
        val level: Int,
        val enemyId: String,
        val enemyLevel: Int,
        val partySize: Int,
        val won: Boolean,
        val ttkSec: Double,
        val dps: Double,
        val damageTaken: Double,
        val playerMaxHp: Double,
        val hpLostPct: Double,
        val potionsUsed: Int,
        val enraged: Boolean,
    )

    private fun bestLoadout(
        classId: String,
        level: Int,
        maxRarity: Rarity,
    ): List<ItemDefinition> {
        val dummy = PlayerProfile(UUID.randomUUID(), classId = classId, level = level)
        val gearSlots = listOf(
            GearSlot.HAND,
            GearSlot.OFFHAND,
            GearSlot.HEAD,
            GearSlot.CHEST,
            GearSlot.LEGS,
            GearSlot.FEET,
            GearSlot.ACCESSORY,
        )
        val eligible = snap.items.all().filter {
            it.id.startsWith("ec:item_") &&
                it.slot in gearSlots &&
                it.canEquip(dummy) &&
                it.rarity.ordinal <= maxRarity.ordinal
        }
        return gearSlots.mapNotNull { slot ->
            eligible.filter { it.slot == slot }.maxWithOrNull(
                compareBy<ItemDefinition>(
                    { it.rarity.ordinal },
                    { it.minLevel },
                    { snap.world.itemProperties[it.id]?.weapon ?: 0.0 },
                    { it.stats.attack + it.stats.defense + it.stats.hp / 10.0 },
                )
            )
        }
    }

    private fun maxRarityForLevel(level: Int, allowLegendary: Boolean = false): Rarity = when {
        allowLegendary && level >= 18 -> Rarity.LEGENDARY
        level >= 14 -> Rarity.EPIC
        level >= 9 -> Rarity.RARE
        level >= 5 -> Rarity.UNCOMMON
        else -> Rarity.COMMON
    }

    private fun expectedDamage(
        balance: Balance,
        stats: Stats,
        targetDefense: Double,
        multiplier: Double,
        weaponDamage: Double,
        elementMod: Double = 1.0,
    ): Double {
        val critChance = stats.crit.coerceIn(0.0, balance.critCap)
        val nonCrit = balance.damage(stats, targetDefense, multiplier, weaponDamage, critical = false, element = elementMod)
        val crit = balance.damage(stats, targetDefense, multiplier, weaponDamage, critical = true, element = elementMod)
        return nonCrit * (1.0 - critChance) + crit * critChance
    }

    /**
     * Simulates 1v1 or party combat using real GameService.stats, SkillEngine, Balance.damage, and BossState.
     * Assumes a realistic 600ms basic-attack cadence (post-HIGH-01 fix) and 70% dodge rate on telegraphed AoEs.
     */
    private fun simulateFight(
        classId: String,
        level: Int,
        enemy: EnemyDefinition,
        partySize: Int = 1,
        allowLegendary: Boolean = false,
        dodgeRate: Double = 0.70,
        allowPotions: Boolean = true,
        globalSkillCooldownMs: Long = 0L,
        maxSeconds: Int = 600,
    ): CombatOutcome {
        var nowMs = 0L
        val clock = Clock { nowMs }
        val skillEngine = SkillEngine(clock, snap.balance)
        val game = GameService({ snap }, skillEngine)
        val profile = PlayerProfile(UUID.randomUUID(), level = level)
        game.changeClass(profile, classId)
        val classDef = snap.classes[classId]
        val gear = bestLoadout(classId, level, maxRarityForLevel(level, allowLegendary))
        val stats = game.stats(profile, gear)
        val weaponDmg = gear.firstOrNull { it.slot == GearSlot.HAND }
            ?.let { snap.world.itemProperties[it.id]?.weapon ?: 0.0 } ?: 0.0

        val bossState = BossState(enemy, partySize = partySize, started = 0L)
        var playerHp = stats.hp
        var totalDamageTaken = 0.0
        var totalDamageDealt = 0.0
        var potionsUsed = 0
        var nextPotionMs = 0L
        val potionHeal = when {
            level >= 14 -> 520.0
            level >= 7 -> 260.0
            else -> 110.0
        }

        val skillsByPriority = classDef.skills.map { snap.skills[it] }.sortedByDescending { it.multiplier }
        var nextBasicAttackMs = 0L
        var nextSkillCastMs = 0L
        var nextEnemyAttackTick = enemy.attackInterval.coerceAtLeast(10L)
        val specialNextTick = mutableMapOf<String, Long>()
        var nextRegenMs = 1000L

        val stepMs = 100L
        var elapsedMs = 0L
        var enraged = false

        while (elapsedMs < maxSeconds * 1000L && bossState.hp > 0.0 && playerHp > 0.0) {
            nowMs = elapsedMs
            val tick = elapsedMs / 50L
            if (enemy.boss && tick >= enemy.enrageTicks) enraged = true

            // 1. Resource regen every 1s (archers moving ~50% of time)
            if (elapsedMs >= nextRegenMs) {
                val moving = classDef.resource == ResourceKind.FOCUS && ((elapsedMs / 1000L) % 2L == 1L)
                game.regenerate(profile, stats.resource, moving)
                nextRegenMs += 1000L
            }

            // 2. Player casts highest-priority available skill (respecting optional globalSkillCooldownMs)
            var castAny = false
            if (elapsedMs >= nextSkillCastMs) {
                for (skill in skillsByPriority) {
                    if (skillEngine.cast(profile, skill) == CastResult.SUCCESS) {
                        val mult = skillEngine.multiplier(profile, skill) * stats.skillPower
                        val dmg = expectedDamage(snap.balance, stats, enemy.defense, mult, weaponDmg) * partySize
                        bossState.hp = (bossState.hp - dmg).coerceAtLeast(0.0)
                        totalDamageDealt += dmg
                        game.onHit(profile, stats.resource)
                        castAny = true
                        nextSkillCastMs = elapsedMs + globalSkillCooldownMs
                        break
                    }
                }
            }

            // 3. Player basic attack (600ms cadence; matches RpgPlugin.hit multiplying by stats.skillPower)
            if (!castAny && elapsedMs >= nextBasicAttackMs && bossState.hp > 0.0) {
                val dmg = expectedDamage(snap.balance, stats, enemy.defense, 1.0 * stats.skillPower, weaponDmg) * partySize
                bossState.hp = (bossState.hp - dmg).coerceAtLeast(0.0)
                totalDamageDealt += dmg
                game.onHit(profile, stats.resource)
                nextBasicAttackMs = elapsedMs + 600L
            }

            // 4. Enemy basic attack (split aggro slightly in party: focused member takes ~1/sqrt(partySize))
            val partyMitigation = if (partySize > 1) 1.0 / sqrt(partySize.toDouble()) else 1.0
            if (bossState.hp > 0.0 && tick >= nextEnemyAttackTick) {
                val scale = bossState.damageScale(tick)
                val incoming = snap.balance.damage(Stats(attack = enemy.attack), stats.defense, scale, 0.0, critical = false) * partyMitigation
                playerHp -= incoming
                totalDamageTaken += incoming
                val intervalScale = bossState.phase()?.interval ?: 1.0
                nextEnemyAttackTick = tick + (enemy.attackInterval * intervalScale).toLong().coerceAtLeast(10L)
            }

            // 5. Enemy telegraphed special attacks (expected damage accounting for dodgeRate)
            if (bossState.hp > 0.0) {
                val hpRatio = bossState.hp / bossState.maximumHp
                for (sp in enemy.specials) {
                    if (hpRatio in sp.minHp..sp.maxHp && tick >= specialNextTick.getOrDefault(sp.id, sp.cooldown / 2L)) {
                        val scale = sp.multiplier * bossState.damageScale(tick)
                        val incoming = snap.balance.damage(Stats(attack = enemy.attack), stats.defense, scale, 0.0, critical = false) * (1.0 - dodgeRate)
                        playerHp -= incoming
                        totalDamageTaken += incoming
                        specialNextTick[sp.id] = tick + sp.cooldown
                        break
                    }
                }
            }

            // 6. Player uses potion when HP <= 55% (8s cooldown)
            if (allowPotions && playerHp > 0.0 && playerHp <= stats.hp * 0.55 && elapsedMs >= nextPotionMs) {
                playerHp = (playerHp + potionHeal).coerceAtMost(stats.hp)
                potionsUsed++
                nextPotionMs = elapsedMs + 8000L
            }

            elapsedMs += stepMs
        }

        val sec = (elapsedMs / 1000.0).coerceAtLeast(0.1)
        return CombatOutcome(
            classId = classId,
            level = level,
            enemyId = enemy.id,
            enemyLevel = enemy.level,
            partySize = partySize,
            won = bossState.hp <= 0.0 && playerHp > 0.0,
            ttkSec = sec,
            dps = totalDamageDealt / sec,
            damageTaken = totalDamageTaken,
            playerMaxHp = stats.hp,
            hpLostPct = (totalDamageTaken / stats.hp) * 100.0,
            potionsUsed = potionsUsed,
            enraged = enraged,
        )
    }

    @Test
    fun `simulate class power scaling lv1 to lv20`() {
        val sb = StringBuilder()
        sb.appendLine("\n================ CLASS POWER BY LEVEL (LV1..20) ================")
        val game = GameService({ snap }, SkillEngine(Clock { 0L }, snap.balance))
        for (lv in listOf(1, 5, 10, 12, 15, 18, 20)) {
            val row = classes.joinToString(" | ") { cid ->
                val p = PlayerProfile(UUID.randomUUID(), level = lv)
                game.changeClass(p, cid)
                val gear = bestLoadout(cid, lv, maxRarityForLevel(lv, allowLegendary = lv >= 20))
                val s = game.stats(p, gear)
                val wpn = gear.firstOrNull { it.slot == GearSlot.HAND }
                    ?.let { snap.world.itemProperties[it.id]?.weapon ?: 0.0 } ?: 0.0
                val shortClass = cid.removePrefix("ec:class_").take(3).uppercase()
                "$shortClass: HP=${"%.0f".format(Locale.US, s.hp)} ATK=${"%.0f".format(Locale.US, s.attack)} WPN=${"%.0f".format(Locale.US, wpn)} DEF=${"%.0f".format(Locale.US, s.defense)} CRIT=${"%.0f".format(Locale.US, s.crit * 100)}% SP=${"%.2f".format(Locale.US, s.skillPower)}"
            }
            sb.appendLine("Lv%2d: %s".format(Locale.US, lv, row))
        }
        println(sb.toString())
        assertTrue(classes.size == 3)
    }

    @Test
    fun `simulate chapter 1 combat TTK, XP progression, economy, and drop expectations`() {
        val sb = StringBuilder()
        sb.appendLine("================ CHAPTER 1 BALANCE SIMULATION REPORT ================")

        // 1. XP curve check
        sb.appendLine("\n--- 1. XP CURVE (balance/default.yml: xp_base=${snap.balance.xpBase}, xp_exponent=${snap.balance.xpExponent}) ---")
        var cumXp = 0L
        for (lv in 1..19) {
            val req = snap.balance.requiredXp(lv)
            cumXp += req
            if (lv in setOf(1, 4, 5, 9, 10, 12, 14, 15, 16, 17, 18, 19)) {
                sb.appendLine("Lv %2d -> %2d: req=%6d XP | cumulative to Lv%2d = %7d XP".format(Locale.US, lv, lv + 1, req, lv + 1, cumXp))
            }
        }

        // 2. Class power & regular enemy TTK at enemy's target level (current 0ms GCD vs 1000ms GCD sensitivity)
        sb.appendLine("\n--- 2. REGULAR/ELITE ENEMIES TTK & SURVIVABILITY AT TARGET LEVEL ---")
        val ch1Enemies = snap.world.enemies.values
            .filter { !it.boss && it.id.startsWith("ec:enemy_") }
            .sortedBy { it.level }

        for (enemy in ch1Enemies) {
            val line = classes.joinToString(" | ") { cid ->
                val res = simulateFight(cid, enemy.level, enemy, allowPotions = false)
                val shortClass = cid.removePrefix("ec:class_").take(3).uppercase()
                "$shortClass: TTK=${"%.1f".format(Locale.US, res.ttkSec)}s DPS=${"%.0f".format(Locale.US, res.dps)} DmgIn=${"%.0f".format(Locale.US, res.damageTaken)}(${"%.0f".format(Locale.US, res.hpLostPct)}%) ${if (res.won) "WIN" else "DIE"}"
            }
            sb.appendLine("%-28s (Lv%2d HP=%5.0f ATK=%4.0f DEF=%3.0f): %s".format(Locale.US, enemy.id, enemy.level, enemy.hp, enemy.attack, enemy.defense, line))
        }

        // 3. Boss fights (solo & 4-player, pre-clear Epic gear vs post-clear Legendary gear)
        sb.appendLine("\n--- 3. BOSS ENCOUNTERS (70% telegraph dodge, potions enabled) ---")
        val ironGrinder = snap.world.enemies.getValue("ec:boss_iron_grinder")
        val voidWarden = snap.world.enemies.getValue("ec:boss_void_warden")

        data class BossScenario(val label: String, val boss: EnemyDefinition, val level: Int, val party: Int, val legendary: Boolean, val gcdMs: Long = 0L)
        val bossScenarios = listOf(
            BossScenario("Iron Grinder Lv12 Solo (Rare)    ", ironGrinder, 12, 1, false, 0L),
            BossScenario("Iron Grinder Lv12 4P   (Rare)    ", ironGrinder, 12, 4, false, 0L),
            BossScenario("Void Warden  Lv15 Solo (Epic)    ", voidWarden, 15, 1, false, 0L),
            BossScenario("Void Warden  Lv16 Solo (Epic)    ", voidWarden, 16, 1, false, 0L),
            BossScenario("Void Warden  Lv18 Solo (Epic)    ", voidWarden, 18, 1, false, 0L),
            BossScenario("Void Warden  Lv18 Solo (1s GCD)  ", voidWarden, 18, 1, false, 1000L),
            BossScenario("Void Warden  Lv16 4P   (Epic)    ", voidWarden, 16, 4, false, 0L),
            BossScenario("Void Warden  Lv18 4P   (Epic)    ", voidWarden, 18, 4, false, 0L),
            BossScenario("Void Warden  Lv18 Solo (Leg)     ", voidWarden, 18, 1, true, 0L),
        )
        for (sc in bossScenarios) {
            val line = classes.joinToString(" | ") { cid ->
                val res = simulateFight(cid, sc.level, sc.boss, partySize = sc.party, allowLegendary = sc.legendary, globalSkillCooldownMs = sc.gcdMs)
                val shortClass = cid.removePrefix("ec:class_").take(3).uppercase()
                "$shortClass: ${if (res.won) "WIN" else "DIE"} ${"%.1f".format(Locale.US, res.ttkSec)}s Pot=${res.potionsUsed} DmgIn=${"%.0f".format(Locale.US, res.damageTaken)}${if (res.enraged) " ENRAGE" else ""}"
            }
            sb.appendLine("%-34s -> %s".format(Locale.US, sc.label, line))
        }

        // 4. Chapter 1 Quest + Kill XP Progression & Gold Income
        sb.appendLine("\n--- 4. CHAPTER 1 QUEST & KILL XP PROGRESSION (REAL Balance.grantXp) ---")
        val ch1 = snap.world.chapters.getValue("ec:chapter_1")
        val allQuests = ch1.strings("quests").map { snap.world.quests.getValue(it) }
        val mainPreBoss = allQuests.filter { it.id.startsWith("ec:quest_m0") && it.id != "ec:quest_m08_void_warden" }
        val mainAll = allQuests.filter { it.id.startsWith("ec:quest_m0") }
        val sideEarlyMid = allQuests.filter { it.id in setOf("ec:quest_s01_lumina_tour", "ec:quest_s02_slime_gel", "ec:quest_s03_wolf_pack", "ec:quest_s04_spider_silk", "ec:quest_s05_lost_scout", "ec:quest_s06_garudo_forge", "ec:quest_s07_golem_bounty") }
        val sideAll = allQuests.filter { it.id.startsWith("ec:quest_s") }

        fun dropTableForEnemy(enemyId: String): String? = snap.documents[enemyId]?.get("droptable") as? String

        fun simulateJourney(label: String, questIds: List<String>, extraMobKillsPerZone: Int): Pair<PlayerProfile, Long> {
            val p = PlayerProfile(UUID.randomUUID(), classId = "ec:class_warrior", level = 1)
            var totalXpEarned = 0L
            var totalGoldEarned = 0L
            for (qid in questIds) {
                val q = snap.world.quests.getValue(qid)
                for (obj in q.objectives) {
                    when (obj.kind) {
                        ObjectiveKind.KILL -> {
                            val doc = snap.documents[obj.target] ?: continue
                            val mobXp = doc.long("xp") * obj.amount
                            val mobGold = doc.long("currency") * obj.amount
                            repeat(obj.amount) { snap.balance.grantXp(p, doc.long("xp")) }
                            totalXpEarned += mobXp
                            totalGoldEarned += mobGold
                        }
                        ObjectiveKind.COLLECT -> {
                            val dropper = snap.world.enemies.values.firstOrNull { e ->
                                dropTableForEnemy(e.id)?.let { dtId -> snap.drops[dtId].entries.any { it.item == obj.target } } == true
                            }
                            if (dropper != null) {
                                val entry = snap.drops[dropTableForEnemy(dropper.id)!!].entries.first { it.item == obj.target }
                                val expectedPerKill = entry.chance * (entry.min + entry.max) / 2.0
                                val estKills = ceil(obj.amount / expectedPerKill).toInt().coerceAtMost(40)
                                val doc = snap.documents.getValue(dropper.id)
                                repeat(estKills) { snap.balance.grantXp(p, doc.long("xp")) }
                                totalXpEarned += doc.long("xp") * estKills
                                totalGoldEarned += doc.long("currency") * estKills
                            }
                        }
                        else -> Unit
                    }
                }
                snap.balance.grantXp(p, q.rewards.xp)
                totalXpEarned += q.rewards.xp
                totalGoldEarned += q.rewards.currency
            }
            if (extraMobKillsPerZone > 0) {
                val representative = listOf("ec:enemy_thorn_wolf", "ec:enemy_void_miner", "ec:enemy_star_wisp", "ec:enemy_void_sentinel")
                representative.forEach { eid ->
                    val doc = snap.documents.getValue(eid)
                    repeat(extraMobKillsPerZone) { snap.balance.grantXp(p, doc.long("xp")) }
                    totalXpEarned += doc.long("xp") * extraMobKillsPerZone
                    totalGoldEarned += doc.long("currency") * extraMobKillsPerZone
                }
            }
            p.currency = totalGoldEarned
            sb.appendLine("%-40s -> Lv%2d (rem %5d/%5d XP) | TotalXP=%6d | TotalGold=%6dG".format(
                Locale.US, label, p.level, p.xp, snap.balance.requiredXp(p.level), totalXpEarned, totalGoldEarned
            ))
            return p to totalXpEarned
        }

        val (mainPreProfile, _) = simulateJourney("A1. Main M01-M07 only (pre-boss)", mainPreBoss.map { it.id }, 0)
        val (mainAllProfile, _) = simulateJourney("A2. Main M01-M08 only (post-boss)", mainAll.map { it.id }, 0)
        val (typicalPreProfile, _) = simulateJourney("B1. Main M01-M07 + 7 early/mid Sides", (mainPreBoss + sideEarlyMid).map { it.id }, 0)
        val (compPreProfile, _) = simulateJourney("C1. All 19 quests before M08", (mainPreBoss + sideAll).map { it.id }, 0)
        val (compAllProfile, _) = simulateJourney("C2. All 20 quests (post-M08)", (mainAll + sideAll).map { it.id }, 0)
        val (compPlusGrind, _) = simulateJourney("C3. All 20 quests + 25 kills/zone", (mainAll + sideAll).map { it.id }, 25)

        // 5. XP/hr & Gold/hr by Zone (using Warrior simulated TTK + 4s travel/respawn overhead)
        sb.appendLine("\n--- 5. ZONE FARMING RATES (XP/hr & Gold/hr, Warrior @ target Lv, +4s travel) ---")
        val zoneMobs = listOf(
            "Ashwood (Lv2)" to "ec:enemy_aether_slime",
            "Ashwood (Lv4)" to "ec:enemy_thorn_wolf",
            "Mine    (Lv8)" to "ec:enemy_void_miner",
            "Mine   (Lv10)" to "ec:enemy_obsidian_golem",
            "Hill   (Lv14)" to "ec:enemy_star_wisp",
            "Sanctum(Lv17)" to "ec:enemy_void_sentinel",
        )
        for ((zone, eid) in zoneMobs) {
            val mob = snap.world.enemies.getValue(eid)
            val doc = snap.documents.getValue(eid)
            val res = simulateFight("ec:class_warrior", mob.level, mob, allowPotions = false)
            val killsPerHr = 3600.0 / (res.ttkSec + 4.0)
            val xpPerHr = killsPerHr * doc.long("xp")
            val goldPerHr = killsPerHr * doc.long("currency")
            sb.appendLine("%-15s %-26s TTK=%4.1fs -> %5.0f kills/hr | %7.0f XP/hr | %6.0f G/hr".format(
                Locale.US, zone, eid, res.ttkSec, killsPerHr, xpPerHr, goldPerHr
            ))
        }

        // 6. Gold Sinks vs Income
        sb.appendLine("\n--- 6. ECONOMY: GOLD SINKS VS INCOME (WARRIOR FULL SHOP UPGRADE PATH) ---")
        val gearUpgradeIds = listOf(
            "ec:item_arm_lumina_helm",
            "ec:item_off_oak_buckler",
            "ec:item_wpn_ashwood_blade",
            "ec:item_arm_ashwood_boots",
            "ec:item_arm_miner_greaves",
            "ec:item_wpn_minebreaker_greatsword",
            "ec:item_off_obsidian_aegis",
        )
        val gearSink = gearUpgradeIds.sumOf { snap.world.itemProperties.getValue(it).price }
        val consumableSink = 15 * 25L + 20 * 85L + 15 * 210L + 5 * 55L + 5 * 130L + 3 * 380L
        val totalSink = gearSink + consumableSink
        sb.appendLine("Shop gear upgrades (Lv1-12 full set): %dG | Estimated consumables across Ch1: %dG | Total Sinks: %dG".format(
            Locale.US, gearSink, consumableSink, totalSink
        ))
        sb.appendLine("Income (Main-only)=%dG | (Typical)=%dG | (All 20 Quests)=%dG | (All + 25 kills/zone)=%dG".format(
            Locale.US, mainAllProfile.currency, typicalPreProfile.currency, compAllProfile.currency, compPlusGrind.currency
        ))

        // 7. Monte Carlo Drop Rate Expectations (N = 1000 seeded trials per table)
        sb.appendLine("\n--- 7. MONTE CARLO DROP EXPECTATIONS (N=1000, Random(42)) ---")
        val rng = Random(42)
        val trials = 1000
        val targets = listOf(
            Triple("ec:drop_aether_slime", "ec:item_mat_aether_gel", 8),
            Triple("ec:drop_spore_spider", "ec:item_mat_venom_gland", 6),
            Triple("ec:drop_void_miner", "ec:item_mat_iron_scrap", 6),
            Triple("ec:drop_void_miner", "ec:item_mat_void_dust", 6),
            Triple("ec:drop_star_wisp", "ec:item_mat_starshard_fragment", 32),
            Triple("ec:drop_iron_grinder", "ec:item_wpn_minebreaker_greatsword", 1),
            Triple("ec:drop_void_warden", "ec:item_mat_void_core_essence", 5),
            Triple("ec:drop_void_warden", "ec:item_mat_starshard_fragment", 30),
            Triple("ec:drop_void_warden", "ec:item_wpn_void_cleaver", 1),
        )
        for ((dtId, itemId, goal) in targets) {
            val dt = snap.drops[dtId]
            var totalAmount = 0L
            repeat(trials) {
                totalAmount += dt.roll(rng)[itemId] ?: 0
            }
            val avgPerKill = totalAmount.toDouble() / trials
            val estKills = if (avgPerKill > 0) goal / avgPerKill else Double.POSITIVE_INFINITY
            sb.appendLine("%-26s -> %-32s goal=%2d | avg/kill=%.3f | expected kills=%.1f".format(
                Locale.US, dtId, itemId, goal, avgPerKill, estKills
            ))
        }

        println(sb.toString())

        assertTrue(ch1Enemies.size >= 14, "Expected >=14 Chapter 1 regular/elite enemies")
        assertTrue(mainPreProfile.level >= 5, "Main quest journey should progress well past Lv1")
        assertTrue(compAllProfile.level >= 10, "Completionist journey should progress into mid-late Chapter 1")
        assertTrue(compPlusGrind.currency > 0L, "Gold economy simulation should produce positive income")
    }
}
