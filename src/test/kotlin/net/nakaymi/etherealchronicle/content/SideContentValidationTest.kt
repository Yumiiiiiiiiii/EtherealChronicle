package net.nakaymi.etherealchronicle.content

import net.nakaymi.etherealchronicle.core.Balance
import net.nakaymi.etherealchronicle.core.BossState
import net.nakaymi.etherealchronicle.core.CastResult
import net.nakaymi.etherealchronicle.core.Clock
import net.nakaymi.etherealchronicle.core.EnemyDefinition
import net.nakaymi.etherealchronicle.core.GameService
import net.nakaymi.etherealchronicle.core.GearSlot
import net.nakaymi.etherealchronicle.core.ItemDefinition
import net.nakaymi.etherealchronicle.core.MemoryInventory
import net.nakaymi.etherealchronicle.core.ObjectiveKind
import net.nakaymi.etherealchronicle.core.PlayerProfile
import net.nakaymi.etherealchronicle.core.QuestEngine
import net.nakaymi.etherealchronicle.core.Rarity
import net.nakaymi.etherealchronicle.core.ResourceKind
import net.nakaymi.etherealchronicle.core.SkillEngine
import net.nakaymi.etherealchronicle.core.Stats
import net.nakaymi.etherealchronicle.core.TradeEngine
import net.nakaymi.etherealchronicle.data.ContentService
import net.nakaymi.etherealchronicle.data.ContentSnapshot
import net.nakaymi.etherealchronicle.data.string
import net.nakaymi.etherealchronicle.data.strings
import org.bukkit.Material
import org.bukkit.entity.EntityType
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.UUID
import kotlin.math.sqrt
import kotlin.test.*

/**
 * Validates the horizontal content expansion ("蒼穹の冒険者録" / ec:chapter_side_chronicles):
 * - 3 new classes (Paladin, Cleric, Assassin), 12 skills, 24 class gear items, 12 side/crafting/challenge items
 * - 18 side quests (regional stories, branching choices, escort-style, 2 hidden, 2 gathering, 2 daily, 1 weekly, 1 challenge)
 * - 4 new NPCs, 2 new shops (exchange + artisan forge crafting), 1 challenge dungeon + Lv20 boss (Aether Chimera)
 * - Full ec validate checks, repeatable cooldown checks, and combat balance simulation for all 3 new classes.
 */
class SideContentValidationTest {
    private val contentRoot = Path.of("src/main/resources/content")
    private fun loadBundled(): ContentSnapshot = ContentService(contentRoot).load()

    @Test
    fun `new roles parse optional icons and party support plus taunt fields`() {
        val snapshot=loadBundled()
        assertEquals("SHIELD",snapshot.classes["ec:class_paladin"].icon)
        assertEquals("END_CRYSTAL",snapshot.classes["ec:class_cleric"].icon)
        val mend=snapshot.skills["ec:cleric_mend"]
        assertTrue(mend.partyHeal && mend.partyDefenseBuff)
        assertTrue(mend.allyRadius > 0)
        val bash=snapshot.skills["ec:paladin_bash"]
        assertTrue(bash.tauntTicks > 0 && bash.threatMultiplier > 1.0)
    }

    @Test
    fun `crucible challenge loads three waves and time rank thresholds`() {
        val definition=loadBundled().world.dungeons.getValue("ec:dungeon_aether_crucible")
        assertEquals(3,definition.waves.size)
        assertEquals("ec:enemy_void_sentinel",definition.waves.first().boss)
        assertEquals("ec:boss_aether_chimera",definition.waves.last().boss)
        assertEquals("S",net.nakaymi.etherealchronicle.core.challengeRank(180,definition.scoreRanks))
        assertEquals("A",net.nakaymi.etherealchronicle.core.challengeRank(181,definition.scoreRanks))
        assertEquals("C",net.nakaymi.etherealchronicle.core.challengeRank(1000,definition.scoreRanks))
    }

    private val newClasses = listOf(
        "ec:class_paladin" to "ec:paladin",
        "ec:class_cleric" to "ec:cleric",
        "ec:class_assassin" to "ec:assassin",
    )

    private val sideQuests = listOf(
        "ec:quest_side_01_port_smugglers",
        "ec:quest_side_02_mira_moonherb",
        "ec:quest_side_03_elena_escort",
        "ec:quest_side_04_garudo_whetstone",
        "ec:quest_side_05_daren_lost_pick",
        "ec:quest_side_06_tomas_supply_escort",
        "ec:quest_side_07_lyra_astral_survey",
        "ec:quest_side_08_nora_glacier_ecology",
        "ec:quest_side_09_nora_cavern_escort",
        "ec:quest_side_10_borka_masterwork",
        "ec:quest_side_h01_forgotten_diary",
        "ec:quest_side_h02_frozen_shrine",
        "ec:quest_side_g01_sky_fishing",
        "ec:quest_side_g02_starlight_herb",
        "ec:quest_side_d01_daily_hunt",
        "ec:quest_side_d02_daily_frost",
        "ec:quest_side_w01_weekly_bounty",
        "ec:quest_side_c01_crucible_gauntlet",
    )

    private val newNpcs = listOf(
        "ec:npc_ren",
        "ec:npc_kaede",
        "ec:npc_soren",
        "ec:npc_nora",
    )

    private val newItems = listOf(
        // Paladin (8)
        "ec:item_wpn_recruit_mace",
        "ec:item_wpn_ashwood_warhammer",
        "ec:item_wpn_mineguard_halberd",
        "ec:item_wpn_aether_bastion_mace",
        "ec:item_wpn_sovereign_aegis_hammer",
        "ec:item_off_iron_kite_shield",
        "ec:item_off_astral_tower_shield",
        "ec:item_arm_paladin_plate",
        // Cleric (8)
        "ec:item_wpn_novice_censer",
        "ec:item_wpn_starblossom_crozier",
        "ec:item_wpn_prism_scepter",
        "ec:item_wpn_celestial_prayer_staff",
        "ec:item_wpn_astral_benediction_rod",
        "ec:item_off_prayer_rosary",
        "ec:item_off_sanctified_chalice",
        "ec:item_arm_cleric_vestment",
        // Assassin (8)
        "ec:item_wpn_iron_dirk",
        "ec:item_wpn_thorn_twinblade",
        "ec:item_wpn_obsidian_stiletto",
        "ec:item_wpn_void_fang_dagger",
        "ec:item_wpn_eclipse_phantom_blade",
        "ec:item_off_parrying_dagger",
        "ec:item_off_shadow_smoke_vial",
        "ec:item_arm_assassin_shroud",
        // Side / crafting / challenge (12)
        "ec:item_mat_sky_salmon",
        "ec:item_mat_starlight_lotus",
        "ec:item_mat_artisan_whetstone",
        "ec:item_mat_crucible_medal",
        "ec:item_mat_chronicle_seal",
        "ec:item_q_sealed_diary",
        "ec:item_acc_veteran_emblem",
        "ec:item_acc_crucible_champion_crest",
        "ec:item_arm_artisan_greaves",
        "ec:item_con_sky_salmon_grill",
        "ec:item_con_starlight_elixir",
        "ec:item_con_whetstone_tonic",
    )

    @Test
    fun `ec validate equivalent - zero duplicate YAML ids and all Paper enums resolve cleanly`() {
        val yaml = Yaml()
        val seenIds = mutableMapOf<String, Path>()
        Files.walk(contentRoot).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".yml") }.forEach { path ->
                val raw = yaml.load<Any>(Files.readString(path))
                assertTrue(raw is Map<*, *>, "YAML root must be map: $path")
                val id = raw["id"]?.toString() ?: fail("Missing id in $path")
                val prev = seenIds.putIfAbsent(id, path)
                assertNull(prev, "Duplicate id '$id' in $path and $prev")
            }
        }

        val snap = loadBundled()
        snap.items.all().forEach { item ->
            val matName = snap.documents.getValue(item.id).string("material")
            assertNotNull(Material.matchMaterial(matName), "Invalid Paper Material '$matName' on ${item.id}")
        }
        snap.world.enemies.values.forEach { mob ->
            val entName = snap.documents.getValue(mob.id).string("entity")
            assertNotNull(runCatching { EntityType.valueOf(entName) }.getOrNull(), "Invalid EntityType '$entName' on ${mob.id}")
        }
        snap.world.npcs.values.forEach { npc ->
            val entName = snap.documents.getValue(npc.id).string("entity")
            assertNotNull(runCatching { EntityType.valueOf(entName) }.getOrNull(), "Invalid EntityType '$entName' on ${npc.id}")
        }
        val pityCap = (snap.documents["ec:balance"]!!["drop_pity_attempt_cap"] as Number).toInt()
        snap.drops.all().forEach { dt ->
            dt.entries.forEach { entry ->
                assertNotNull(snap.items.find(entry.item), "Unknown drop item ${entry.item} in ${dt.id}")
                entry.pityAfter?.let { pity ->
                    assertTrue(pity in 1..pityCap, "pity_after $pity out of range in ${dt.id}/${entry.item}")
                }
            }
        }
    }

    @Test
    fun `3 new classes, 12 skills, 36 items, and Vulcan class_select integration are valid`() {
        val snap = loadBundled()
        assertEquals(6, snap.classes.all().size)

        val vulcan = snap.world.dialogues.getValue("ec:dlg_vulcan_hub")
        val vulcanSelectedClasses = vulcan.nodes.values
            .flatMap { it.choices }
            .flatMap { it.actions }
            .filter { it.kind == "SELECT_CLASS" }
            .map { it.target }
            .toSet()

        newClasses.forEach { (canonical, alias) ->
            val cls = snap.classes[canonical]
            assertEquals(canonical, cls.id)
            assertEquals(canonical, snap.classes[alias].id)
            assertEquals(4, cls.skills.size, "$canonical must have 4 skills")
            assertTrue(cls.starterItems.isNotEmpty(), "$canonical must have starter item")
            cls.skills.forEach { sid ->
                val skill = snap.skills[sid]
                assertTrue(skill.cooldownMillis > 0, "Skill $sid cooldown must be positive")
                assertTrue(skill.multiplier > 0, "Skill $sid multiplier must be positive")
            }
            assertTrue(canonical in vulcanSelectedClasses, "$canonical missing from vulcan_hub SELECT_CLASS")

            val classGear = snap.items.all().filter { canonical in it.classes }
            assertEquals(8, classGear.size, "$canonical must have 8 dedicated gear items")
            assertEquals(Rarity.entries.toSet(), classGear.map { it.rarity }.toSet(), "$canonical gear must span all 5 rarities")
        }

        // All 36 new items exist, respect level cap and rarity budgets
        newItems.forEach { id ->
            val item = snap.items.find(id) ?: fail("Missing side-content item $id")
            assertTrue(item.minLevel in 1..snap.balance.levelCap, "$id minLevel ${item.minLevel} out of cap")
            if (item.slot != GearSlot.NONE) {
                assertTrue(item.withinBudget(), "Item $id exceeds rarity budget")
            }
        }
    }

    @Test
    fun `side chapter, 18 quests, 11 flags, 4 NPCs, 2 shops, and challenge dungeon wire cleanly`() {
        val snap = loadBundled()
        val world = snap.world
        val sideChapter = world.chapters["ec:chapter_side_chronicles"] ?: fail("Missing ec:chapter_side_chronicles")

        assertEquals(sideQuests.toSet(), sideChapter.strings("quests").toSet())
        assertEquals(newNpcs.toSet(), sideChapter.strings("npcs").toSet())
        assertEquals(listOf("ec:dungeon_aether_crucible"), sideChapter.strings("dungeons"))
        assertEquals(11, sideChapter.strings("flags").size)

        // Challenge dungeon config
        val crucible = world.dungeons["ec:dungeon_aether_crucible"] ?: fail("Missing ec:dungeon_aether_crucible")
        assertEquals("ec:flag_side_crucible_unlocked", crucible.unlockFlag)
        assertEquals("ec:boss_aether_chimera", crucible.boss)
        assertEquals(listOf("ec:enemy_void_sentinel", "ec:enemy_glacial_knight"), crucible.guards)
        assertEquals("ec:area_void_sanctum", crucible.area)

        // Challenge boss virtual HP, 3 phases, elemental specials, and coop scaling
        val chimera = world.enemies["ec:boss_aether_chimera"] ?: fail("Missing ec:boss_aether_chimera")
        assertTrue(chimera.boss)
        assertEquals(20, chimera.level)
        assertEquals(88000.0, chimera.hp)
        assertTrue(chimera.spawns.isEmpty(), "Instanced challenge boss must have empty spawn_points")
        assertEquals(3, chimera.phases.size)
        assertTrue("ec:flag_side_chimera_defeated" in chimera.flags)
        assertTrue(chimera.specials.any { it.effect == "FREEZE" && it.effectDuration > 0 })
        val bossState = BossState(chimera, partySize = 4, started = 0L)
        assertTrue(bossState.maximumHp > chimera.hp)
        assertEquals(chimera.phases[0].id, bossState.phase()?.id)
        bossState.hp = bossState.maximumHp * 0.50
        assertEquals(chimera.phases[1].id, bossState.phase()?.id)
        bossState.hp = bossState.maximumHp * 0.20
        assertEquals(chimera.phases[2].id, bossState.phase()?.id)
        assertTrue(bossState.damageScale(chimera.enrageTicks) > bossState.damageScale(0L))

        // Dialogue wiring & flag coverage
        val declaredFlags = world.chapters.values.flatMap { it.strings("flags") }.toSet()
        val sideFlags = sideChapter.strings("flags").toSet()
        val started = mutableSetOf<String>()
        val completed = mutableSetOf<String>()
        val setFlags = mutableSetOf<String>()

        world.dialogues.values.forEach { dlg ->
            dlg.nodes.values.forEach { node ->
                node.choices.forEach { choice ->
                    setFlags += choice.flags
                    choice.actions.forEach { action ->
                        if (action.kind == "START_QUEST") started += action.target
                        if (action.kind == "COMPLETE_QUEST") completed += action.target
                        if (action.kind == "GIVE_ITEM") action.onceFlag?.let { setFlags += it }
                    }
                }
            }
        }
        world.quests.values.forEach { q -> setFlags += q.rewards.flags }
        world.enemies.values.forEach { e -> setFlags += e.flags }

        sideFlags.forEach { f ->
            assertTrue(f in setFlags, "Declared side flag $f is never set")
        }

        val drops = snap.drops.all().flatMap { dt -> dt.entries.map { it.item } }.toSet()
        val rewards = world.quests.values.flatMap { q -> q.rewards.items.map { it.item } }.toSet()
        val shopItems = world.shops.values.flatMap { s -> s.entries.map { it.item } }.toSet()

        sideQuests.forEach { qid ->
            val q = world.quests[qid] ?: fail("Missing quest $qid")
            assertTrue(qid in started, "Side quest $qid has no START_QUEST dialogue action")
            assertTrue(qid in completed, "Side quest $qid has no COMPLETE_QUEST dialogue action")
            assertTrue(q.rewards.xp <= snap.balance.xpGainCap, "$qid XP exceeds cap")
            assertTrue(q.rewards.currency <= snap.balance.currencyGainCap, "$qid currency exceeds cap")
            q.rewards.flags.forEach { assertTrue(it in declaredFlags, "Undeclared flag $it in $qid") }
            q.objectives.forEach { obj ->
                when (obj.kind) {
                    ObjectiveKind.KILL -> assertTrue(obj.target in world.enemies, "Unknown KILL target ${obj.target}")
                    ObjectiveKind.COLLECT -> assertTrue(
                        obj.target in drops || obj.target in rewards || obj.target in shopItems,
                        "COLLECT target ${obj.target} in $qid has no source"
                    )
                    ObjectiveKind.EXPLORE -> assertTrue(obj.target in world.areas, "Unknown EXPLORE area ${obj.target}")
                    ObjectiveKind.TALK -> assertTrue(obj.target in world.npcs, "Unknown TALK npc ${obj.target}")
                }
            }
        }

        // Repeatable cooldown assertions (gathering 12h, daily 24h, weekly 7d, challenge 24h)
        assertEquals(43_200_000L, world.quests.getValue("ec:quest_side_g01_sky_fishing").repeatCooldown)
        assertEquals(43_200_000L, world.quests.getValue("ec:quest_side_g02_starlight_herb").repeatCooldown)
        assertEquals(86_400_000L, world.quests.getValue("ec:quest_side_d01_daily_hunt").repeatCooldown)
        assertEquals(86_400_000L, world.quests.getValue("ec:quest_side_d02_daily_frost").repeatCooldown)
        assertEquals(604_800_000L, world.quests.getValue("ec:quest_side_w01_weekly_bounty").repeatCooldown)
        assertEquals(86_400_000L, world.quests.getValue("ec:quest_side_c01_crucible_gauntlet").repeatCooldown)

        // Shop size <= 45 and token crafting execution
        val exchange = world.shops.getValue("ec:shop_chronicle_exchange")
        val forge = world.shops.getValue("ec:shop_artisan_forge")
        assertTrue(exchange.entries.size <= 45)
        assertTrue(forge.entries.size <= 45)

        val trade = TradeEngine { snap }
        val crafter = PlayerProfile(UUID.randomUUID(), level = 20, flags = mutableSetOf("ec:flag_side_chimera_defeated"))
        val bag = MemoryInventory(
            mutableMapOf(
                "ec:item_mat_artisan_whetstone" to 10,
                "ec:item_mat_crucible_medal" to 10,
                "ec:item_mat_starshard_fragment" to 30,
                "ec:item_mat_sky_salmon" to 4,
            ),
            capacity = 100,
        )
        val craftFood = trade.buy(crafter, "ec:shop_artisan_forge", "cook_salmon_grill", bag, "craft-1", 1)
        assertEquals(mapOf("ec:item_mat_sky_salmon" to 2), craftFood.remove)
        assertEquals(mapOf("ec:item_con_sky_salmon_grill" to 2), craftFood.add)
        assertTrue(bag.exchange(craftFood.remove, craftFood.add))
        trade.commit(crafter, craftFood)

        val craftLeg = trade.buy(crafter, "ec:shop_artisan_forge", "forge_sovereign_aegis_hammer", bag, "craft-2", 1)
        assertEquals(mapOf("ec:item_wpn_sovereign_aegis_hammer" to 1), craftLeg.add)
    }

    @Test
    fun `all 18 side quests complete end-to-end and repeatable cooldowns enforce gating`() {
        val snap = loadBundled()
        var nowMs = 1_000L
        val clock = Clock { nowMs }
        val questEngine = QuestEngine({ snap }, clock)
        val world = snap.world

        val p = PlayerProfile(
            UUID.randomUUID(),
            classId = "ec:class_paladin",
            level = 20,
            flags = mutableSetOf(
                "ec:flag_class_selected",
                "ec:flag_ch1_started",
                "ec:flag_ashwood_cleared",
                "ec:flag_mine_unlocked",
                "ec:flag_iron_grinder_defeated",
                "ec:flag_stargazer_unlocked",
                "ec:flag_ch1_completed",
                "ec:flag_ch2_started",
                "ec:flag_ch2_cavern_unlocked",
                "ec:flag_ch2_colossus_defeated",
                "ec:flag_side_crucible_unlocked",
            ),
        )
        val inv = MemoryInventory(capacity = 2304)

        for (qid in sideQuests) {
            val q = world.quests.getValue(qid)
            questEngine.start(p, qid)
            q.objectives.forEach { obj ->
                when (obj.kind) {
                    ObjectiveKind.KILL -> repeat(obj.amount) { questEngine.event(p, ObjectiveKind.KILL, obj.target, 1, inv) }
                    ObjectiveKind.COLLECT -> assertTrue(inv.exchange(emptyMap(), mapOf(obj.target to obj.amount)))
                    else -> questEngine.event(p, obj.kind, obj.target, 1, inv)
                }
            }
            assertTrue(questEngine.ready(p, qid, inv), "$qid should be ready")
            questEngine.complete(p, qid, inv)
            assertTrue(p.quests.getValue(qid).completed, "$qid should be completed")
        }

        // Verify title flags & unique rewards were granted
        assertTrue("ec:flag_title_truth_seeker" in p.flags)
        assertTrue("ec:flag_title_frost_walker" in p.flags)
        assertTrue("ec:flag_title_crucible_victor" in p.flags)
        assertTrue(inv.count("ec:item_arm_artisan_greaves") >= 1)
        assertTrue(inv.count("ec:item_acc_veteran_emblem") >= 1)

        // Verify daily quest blocks immediate restart, then allows restart after 24h
        val dailyId = "ec:quest_side_d01_daily_hunt"
        assertTrue(runCatching { questEngine.start(p, dailyId) }.isFailure, "Daily quest should reject restart before 24h cooldown")
        nowMs += 86_400_000L + 1L
        questEngine.start(p, dailyId)
        assertFalse(p.quests.getValue(dailyId).completed, "Daily quest should reset after 24h cooldown")
    }

    @Test
    fun `combat balance simulation for 3 new classes against Lv18 Void Warden and Lv20 Aether Chimera`() {
        val snap = loadBundled()

        fun bestLoadout(classId: String, level: Int, maxRarity: Rarity): List<ItemDefinition> {
            val dummy = PlayerProfile(UUID.randomUUID(), classId = classId, level = level)
            val slots = listOf(GearSlot.HAND, GearSlot.OFFHAND, GearSlot.HEAD, GearSlot.CHEST, GearSlot.LEGS, GearSlot.FEET, GearSlot.ACCESSORY)
            val eligible = snap.items.all().filter {
                it.id.startsWith("ec:item_") && it.slot in slots && it.canEquip(dummy) && it.rarity.ordinal <= maxRarity.ordinal
            }
            return slots.mapNotNull { slot ->
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

        fun expectedDamage(balance: Balance, stats: Stats, targetDef: Double, mult: Double, weapon: Double): Double {
            val critChance = stats.crit.coerceIn(0.0, balance.critCap)
            val nonCrit = balance.damage(stats, targetDef, mult, weapon, critical = false)
            val crit = balance.damage(stats, targetDef, mult, weapon, critical = true)
            return nonCrit * (1.0 - critChance) + crit * critChance
        }

        fun simulateBoss(classId: String, level: Int, boss: EnemyDefinition, partySize: Int, maxRarity: Rarity): Pair<Boolean, Double> {
            var nowMs = 0L
            val clock = Clock { nowMs }
            val skillEngine = SkillEngine(clock, snap.balance)
            val game = GameService({ snap }, skillEngine)
            val profile = PlayerProfile(UUID.randomUUID(), level = level)
            game.changeClass(profile, classId)
            val classDef = snap.classes[classId]
            val gear = bestLoadout(classId, level, maxRarity)
            val stats = game.stats(profile, gear)
            val weaponDmg = gear.firstOrNull { it.slot == GearSlot.HAND }?.let { snap.world.itemProperties[it.id]?.weapon ?: 0.0 } ?: 0.0

            val bossState = BossState(boss, partySize = partySize, started = 0L)
            var playerHp = stats.hp
            var defBuff = 0.0
            var defBuffEndMs = 0L
            var nextPotionMs = 0L
            var nextBasicMs = 0L
            var nextSkillMs = 0L
            var nextRegenMs = 1000L
            var nextEnemyAttackTick = boss.attackInterval.coerceAtLeast(10L)
            val specialNextTick = mutableMapOf<String, Long>()

            val skills = classDef.skills.map { snap.skills[it] }
            val dmgSkills = skills.sortedByDescending { it.multiplier }
            val healSkills = skills.filter { it.heal > 0.0 }.sortedByDescending { it.heal }

            var elapsedMs = 0L
            while (elapsedMs < 300_000L && bossState.hp > 0.0 && playerHp > 0.0) {
                nowMs = elapsedMs
                val tick = elapsedMs / 50L
                if (elapsedMs >= defBuffEndMs) defBuff = 0.0
                if (elapsedMs >= nextRegenMs) {
                    val moving = classDef.resource == ResourceKind.FOCUS && ((elapsedMs / 1000L) % 2L == 1L)
                    game.regenerate(profile, stats.resource, moving)
                    nextRegenMs += 1000L
                }

                var castAny = false
                if (elapsedMs >= nextSkillMs) {
                    val ordered = if (playerHp <= stats.hp * 0.65) (healSkills + dmgSkills).distinct() else dmgSkills
                    for (skill in ordered) {
                        if (skillEngine.cast(profile, skill) == CastResult.SUCCESS) {
                            val mult = skillEngine.multiplier(profile, skill) * stats.skillPower
                            val dmg = expectedDamage(snap.balance, stats, boss.defense, mult, weaponDmg) * partySize
                            bossState.hp = (bossState.hp - dmg).coerceAtLeast(0.0)
                            if (skill.heal > 0.0) {
                                playerHp = (playerHp + stats.hp * skill.heal).coerceAtMost(stats.hp)
                            }
                            if (skill.defenseBuff > 0.0) {
                                defBuff = maxOf(defBuff, skill.defenseBuff)
                                defBuffEndMs = elapsedMs + skill.buffTicks * 50L
                            }
                            game.onHit(profile, stats.resource)
                            castAny = true
                            nextSkillMs = elapsedMs + snap.balance.skillGcdMillis
                            break
                        }
                    }
                }
                if (!castAny && elapsedMs >= nextBasicMs && bossState.hp > 0.0) {
                    val dmg = expectedDamage(snap.balance, stats, boss.defense, 1.0, weaponDmg) * partySize
                    bossState.hp = (bossState.hp - dmg).coerceAtLeast(0.0)
                    game.onHit(profile, stats.resource)
                    nextBasicMs = elapsedMs + 550L
                }

                val effDef = stats.defense + defBuff
                val partyMitigation = if (partySize > 1) 1.0 / sqrt(partySize.toDouble()) else 1.0
                if (bossState.hp > 0.0 && tick >= nextEnemyAttackTick) {
                    val incoming = snap.balance.damage(Stats(attack = boss.attack), effDef, bossState.damageScale(tick), 0.0, false) * partyMitigation
                    playerHp -= incoming
                    val intervalScale = bossState.phase()?.interval ?: 1.0
                    nextEnemyAttackTick = tick + (boss.attackInterval * intervalScale).toLong().coerceAtLeast(10L)
                }
                if (bossState.hp > 0.0) {
                    val hpRatio = bossState.hp / bossState.maximumHp
                    for (sp in boss.specials) {
                        if (hpRatio in sp.minHp..sp.maxHp && tick >= specialNextTick.getOrDefault(sp.id, sp.cooldown / 2L)) {
                            val incoming = snap.balance.damage(Stats(attack = boss.attack), effDef, sp.multiplier * bossState.damageScale(tick), 0.0, false) * 0.30
                            playerHp -= incoming
                            specialNextTick[sp.id] = tick + sp.cooldown
                            break
                        }
                    }
                }
                if (playerHp > 0.0 && playerHp <= stats.hp * 0.55 && elapsedMs >= nextPotionMs) {
                    playerHp = (playerHp + 520.0).coerceAtMost(stats.hp)
                    nextPotionMs = elapsedMs + 8000L
                }
                elapsedMs += 100L
            }
            return (bossState.hp <= 0.0 && playerHp > 0.0) to (elapsedMs / 1000.0)
        }

        val voidWarden = snap.world.enemies.getValue("ec:boss_void_warden")
        val chimera = snap.world.enemies.getValue("ec:boss_aether_chimera")

        val report = StringBuilder("\n=== NEW CLASSES COMBAT SIMULATION ===\n")
        newClasses.forEach { (cid, _) ->
            val (vwWon, vwSec) = simulateBoss(cid, 18, voidWarden, partySize = 1, maxRarity = Rarity.EPIC)
            val (chWon, chSec) = simulateBoss(cid, 20, chimera, partySize = 4, maxRarity = Rarity.LEGENDARY)
            report.appendLine(
                String.format(
                    Locale.US,
                    "%-18s | VoidWarden Lv18 Solo(EPIC): %s %.1fs | AetherChimera Lv20 4P(LEG): %s %.1fs",
                    cid,
                    if (vwWon) "WIN" else "DIE",
                    vwSec,
                    if (chWon) "WIN" else "DIE",
                    chSec,
                )
            )
            assertTrue(vwWon, "$cid failed Lv18 Void Warden solo (${vwSec}s)")
            assertTrue(vwSec in 45.0..170.0, "$cid Lv18 Void Warden TTK out of balanced range: ${vwSec}s")
            assertTrue(chWon, "$cid failed Lv20 Aether Chimera 4P (${chSec}s)")
            assertTrue(chSec in 45.0..180.0, "$cid Lv20 Aether Chimera 4P TTK out of balanced range: ${chSec}s")
        }
        println(report.toString())
    }
}
