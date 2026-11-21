package net.nakaymi.etherealchronicle.content

import net.nakaymi.etherealchronicle.core.BossState
import net.nakaymi.etherealchronicle.core.Clock
import net.nakaymi.etherealchronicle.core.DialogueEngine
import net.nakaymi.etherealchronicle.core.GameService
import net.nakaymi.etherealchronicle.core.GearSlot
import net.nakaymi.etherealchronicle.core.MemoryInventory
import net.nakaymi.etherealchronicle.core.ObjectiveKind
import net.nakaymi.etherealchronicle.core.PlayerProfile
import net.nakaymi.etherealchronicle.core.SkillEngine
import net.nakaymi.etherealchronicle.core.TradeEngine
import net.nakaymi.etherealchronicle.data.ContentService
import net.nakaymi.etherealchronicle.data.string
import net.nakaymi.etherealchronicle.data.strings
import org.bukkit.Material
import org.bukkit.entity.EntityType
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*

/**
 * Validates Chapter 2 ("凍てつく蒼穹の星片" / ec:chapter_2) bundled content end-to-end:
 * - Full ContentService load (ec validate equivalent) + Paper Material/EntityType enum checks
 * - Zero duplicate YAML IDs across all bundled files and zero dangling cross-references
 * - Item rarity budgets, level caps, pity_after bounds, and non-sellable bound/quest rules
 * - Chapter 2 shop Choice 4A gold discount and Choice 5B legendary token discount
 * - Chapter 2 boss virtual HP, phase transitions, coop scaling, and enrage
 * - Full Chapter 2 quest chain simulation across all 3 classes x Choice 4A/4B x Choice 5A/5B
 */
class Chapter2ContentValidationTest {
    private val contentRoot = Path.of("src/main/resources/content")
    private fun loadBundled() = ContentService(contentRoot).load()

    private val ch2MainQuests = listOf(
        "ec:quest_ch2_m01_northward",
        "ec:quest_ch2_m02_frost_beacons",
        "ec:quest_ch2_m03_frozen_conduit",
        "ec:quest_ch2_m04_cavern_infiltration",
        "ec:quest_ch2_m05_frost_colossus",
        "ec:quest_ch2_m06_citadel_gate",
        "ec:quest_ch2_m07_cryo_archon",
    )

    private val ch2SideQuests = listOf(
        "ec:quest_ch2_s01_frost_pelt",
        "ec:quest_ch2_s02_ice_resonance",
        "ec:quest_ch2_s03_defector_ledger",
        "ec:quest_ch2_s04_citadel_echo",
    )

    private val ch2Npcs = listOf(
        "ec:npc_freya",
        "ec:npc_orphe",
        "ec:npc_borka",
        "ec:npc_seraphina",
        "ec:npc_vesper",
    )

    private val ch2Enemies = listOf(
        "ec:enemy_frost_wolf",
        "ec:enemy_glacier_wraith",
        "ec:enemy_cryo_cultist",
        "ec:enemy_glacial_knight",
    )

    private val ch2Bosses = listOf(
        "ec:boss_frost_colossus",
        "ec:boss_cryo_archon",
    )

    private val ch2Items = listOf(
        "ec:item_wpn_glacier_greatsword",
        "ec:item_wpn_frostwind_longbow",
        "ec:item_wpn_rime_scepter",
        "ec:item_wpn_azure_sovereign_blade",
        "ec:item_wpn_borealis_bow",
        "ec:item_wpn_cryo_eternity_staff",
        "ec:item_arm_frostpeak_helm",
        "ec:item_arm_glacier_treads",
        "ec:item_arm_frostguard_cuirass",
        "ec:item_arm_snowstalker_coat",
        "ec:item_arm_cryomancer_mantle",
        "ec:item_off_frostguard_bulwark",
        "ec:item_off_glacier_quiver",
        "ec:item_off_cryo_grimoire",
        "ec:item_acc_glacier_ward_pendant",
        "ec:item_acc_frostbreaker_signet",
        "ec:item_acc_azure_core_signet",
        "ec:item_con_frostguard_brew",
        "ec:item_con_aether_crystal_tonic",
        "ec:item_con_glacier_feast",
        "ec:item_mat_frost_pelt",
        "ec:item_mat_cryo_crystal",
        "ec:item_mat_glacial_shard",
        "ec:item_mat_glacial_core_essence",
        "ec:item_q_cult_frost_cipher",
        "ec:item_q_frozen_seal_key",
        "ec:item_q_azure_core_remnant",
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
        // Validate Paper Material enum on every item document
        snap.items.all().forEach { item ->
            val doc = snap.documents[item.id] ?: fail("Missing doc for ${item.id}")
            val matName = doc.string("material")
            assertNotNull(Material.matchMaterial(matName), "Invalid Paper Material '$matName' on ${item.id}")
        }
        // Validate Paper EntityType enum on every enemy/boss and NPC
        snap.world.enemies.values.forEach { mob ->
            val doc = snap.documents[mob.id] ?: fail("Missing doc for ${mob.id}")
            val entName = doc.string("entity")
            assertNotNull(runCatching { EntityType.valueOf(entName) }.getOrNull(), "Invalid EntityType '$entName' on ${mob.id}")
        }
        snap.world.npcs.values.forEach { npc ->
            val doc = snap.documents[npc.id] ?: fail("Missing doc for ${npc.id}")
            val entName = doc.string("entity")
            assertNotNull(runCatching { EntityType.valueOf(entName) }.getOrNull(), "Invalid EntityType '$entName' on ${npc.id}")
        }
        // Validate pity_after bounds on all drop tables
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
    fun `chapter 2 declarations, items, enemies, bosses, shop, and balance caps are valid`() {
        val snap = loadBundled()
        val world = snap.world
        val ch2 = world.chapters["ec:chapter_2"] ?: fail("Missing ec:chapter_2")

        assertEquals(4, (ch2["areas"] as List<*>).size)
        assertEquals(ch2Npcs.toSet(), ch2.strings("npcs").toSet())
        assertEquals((ch2MainQuests + ch2SideQuests).toSet(), ch2.strings("quests").toSet())
        assertEquals(listOf("ec:dungeon_glacial_citadel"), ch2.strings("dungeons"))
        assertEquals(14, ch2.strings("flags").size)

        // All 27 Chapter 2 items exist, respect level cap and rarity budget
        ch2Items.forEach { id ->
            val item = snap.items.find(id) ?: fail("Missing Chapter 2 item $id")
            assertTrue(item.minLevel in 1..snap.balance.levelCap, "Item $id minLevel ${item.minLevel} out of cap")
            if (item.slot != GearSlot.NONE) {
                assertTrue(item.withinBudget(), "Item $id exceeds rarity budget")
            }
        }

        // Enemies & bosses within level/stat/xp/currency caps
        val statCap = (snap.documents["ec:balance"]!!["stat_cap"] as Number).toDouble()
        (ch2Enemies + ch2Bosses).forEach { id ->
            val mob = world.enemies[id] ?: fail("Missing Chapter 2 mob $id")
            val mobDoc = snap.documents[id] ?: fail("Missing doc for $id")
            val dropTableId = mobDoc.string("droptable")
            assertTrue(mob.level in 16..snap.balance.levelCap, "Mob $id level ${mob.level} out of range")
            assertTrue(mob.hp <= statCap, "Mob $id HP ${mob.hp} exceeds stat_cap $statCap")
            assertNotNull(snap.drops.find(dropTableId), "Missing droptable $dropTableId for $id")
            assertTrue(mob.spawns.isNotEmpty(), "Mob $id must declare spawn_points")
        }
        ch2Enemies.forEach { assertFalse(world.enemies[it]!!.boss) }
        ch2Bosses.forEach { assertTrue(world.enemies[it]!!.boss) }

        // Shop exists and links to Borka
        val shop = world.shops["ec:shop_frostpeak_outfitter"] ?: fail("Missing ec:shop_frostpeak_outfitter")
        assertEquals(17, shop.entries.size)
        assertEquals("ec:shop_frostpeak_outfitter", world.npcs["ec:npc_borka"]?.shop)
    }

    @Test
    fun `all 11 chapter 2 quests are wired into dialogues with zero unreachable quests or flags`() {
        val snap = loadBundled()
        val world = snap.world
        val declaredFlags = world.chapters.values.flatMap { it.strings("flags") }.toSet()
        val ch2Flags = world.chapters["ec:chapter_2"]!!.strings("flags").toSet()

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

        // Every declared Chapter 2 flag is actually set somewhere
        ch2Flags.forEach { f ->
            assertTrue(f in setFlags, "Declared Chapter 2 flag $f is never set by any dialogue, quest, or enemy")
        }

        val drops = snap.drops.all().flatMap { dt -> dt.entries.map { it.item } }.toSet()
        val rewards = world.quests.values.flatMap { q -> q.rewards.items.map { it.item } }.toSet()
        val shopItems = world.shops.values.flatMap { s -> s.entries.map { it.item } }.toSet()

        (ch2MainQuests + ch2SideQuests).forEach { qid ->
            val q = world.quests[qid] ?: fail("Missing quest $qid")
            assertTrue(qid in started, "Chapter 2 quest $qid has no START_QUEST dialogue action")
            assertTrue(qid in completed, "Chapter 2 quest $qid has no COMPLETE_QUEST dialogue action")
            assertTrue(q.rewards.xp <= snap.balance.xpGainCap, "$qid XP exceeds cap")
            assertTrue(q.rewards.currency <= snap.balance.currencyGainCap, "$qid currency exceeds cap")
            q.rewards.flags.forEach { assertTrue(it in declaredFlags, "Undeclared flag $it in $qid") }
            q.objectives.forEach { obj ->
                when (obj.kind) {
                    ObjectiveKind.KILL -> assertTrue(obj.target in world.enemies, "Unknown KILL target ${obj.target}")
                    ObjectiveKind.COLLECT -> assertTrue(
                        obj.target in drops || obj.target in rewards || obj.target in shopItems,
                        "COLLECT target ${obj.target} in $qid has no drop/reward/shop source"
                    )
                    ObjectiveKind.EXPLORE -> assertTrue(obj.target in world.areas, "Unknown EXPLORE area ${obj.target}")
                    ObjectiveKind.TALK -> assertTrue(obj.target in world.npcs, "Unknown TALK npc ${obj.target}")
                }
            }
        }
    }

    @Test
    fun `chapter 2 shop applies Choice 4A gold discount and Choice 5B legendary token discount`() {
        val snap = loadBundled()
        val trade = TradeEngine { snap }

        // Choice 4A: 10% gold discount at Frostpeak Outfitter
        val pGold = PlayerProfile(UUID.randomUUID(), level = 18, currency = 10000)
        val bag = MemoryInventory(capacity = 50)
        val baseBuy = trade.buy(pGold, "ec:shop_frostpeak_outfitter", "frostguard_brew", bag, "ch2-gold-1", 1)
        assertEquals(10000 - 260, baseBuy.currencyAfter)
        pGold.flags += "ec:flag_ch2_choice4_stabilize"
        val discBuy = trade.buy(pGold, "ec:shop_frostpeak_outfitter", "frostguard_brew", bag, "ch2-gold-2", 1)
        assertEquals(10000 - 234, discBuy.currencyAfter)

        // Choice 5B: -5 glacial_shard token discount on Lv20 legendaries
        val pTok = PlayerProfile(UUID.randomUUID(), level = 20, flags = mutableSetOf("ec:flag_ch2_archon_defeated"))
        val tokBag = MemoryInventory(
            mutableMapOf(
                "ec:item_mat_glacial_core_essence" to 10,
                "ec:item_mat_glacial_shard" to 60,
            ),
            capacity = 100,
        )
        val baseBlade = trade.buy(pTok, "ec:shop_frostpeak_outfitter", "azure_sovereign_blade", tokBag, "ch2-tok-1", 1)
        assertEquals(
            mapOf("ec:item_mat_glacial_core_essence" to 5, "ec:item_mat_glacial_shard" to 30),
            baseBlade.remove,
        )
        pTok.flags += "ec:flag_ch2_choice5_resonance"
        val discBlade = trade.buy(pTok, "ec:shop_frostpeak_outfitter", "azure_sovereign_blade", tokBag, "ch2-tok-2", 1)
        assertEquals(
            mapOf("ec:item_mat_glacial_core_essence" to 5, "ec:item_mat_glacial_shard" to 25),
            discBlade.remove,
        )
        val discSignet = trade.buy(pTok, "ec:shop_frostpeak_outfitter", "azure_core_signet", tokBag, "ch2-tok-3", 1)
        assertEquals(
            mapOf("ec:item_mat_glacial_core_essence" to 4, "ec:item_mat_glacial_shard" to 20),
            discSignet.remove,
        )
    }

    @Test
    fun `chapter 2 bosses scale virtual HP, phases, coop multipliers, and enrage properly`() {
        val snap = loadBundled()
        val colossus = snap.world.enemies["ec:boss_frost_colossus"] ?: fail("Missing ec:boss_frost_colossus")
        val archon = snap.world.enemies["ec:boss_cryo_archon"] ?: fail("Missing ec:boss_cryo_archon")

        assertEquals(28000.0, colossus.hp)
        assertEquals(76000.0, archon.hp)
        assertEquals(3, colossus.phases.size)
        assertEquals(3, archon.phases.size)
        assertTrue("ec:flag_ch2_colossus_defeated" in colossus.flags)
        assertTrue("ec:flag_ch2_archon_defeated" in archon.flags)

        listOf(colossus, archon).forEach { boss ->
            val state = BossState(boss, partySize = 4, started = 0L)
            assertTrue(state.maximumHp > boss.hp, "${boss.id} 4p HP should exceed solo HP")
            assertEquals(boss.phases[0].id, state.phase()?.id)
            state.hp = state.maximumHp * 0.50
            assertEquals(boss.phases[1].id, state.phase()?.id)
            state.hp = state.maximumHp * 0.20
            assertEquals(boss.phases[2].id, state.phase()?.id)
            assertTrue(state.damageScale(boss.enrageTicks) > state.damageScale(0L))
        }
    }

    @Test
    fun `chapter 2 dialogues resolve cleanly across all story states`() {
        val snap = loadBundled()
        val dialogueEngine = DialogueEngine { snap }
        val ch2Dialogues = listOf(
            "ec:dlg_freya_hub",
            "ec:dlg_orphe_hub",
            "ec:dlg_borka_hub",
            "ec:dlg_seraphina_hub",
            "ec:dlg_vesper_hub",
        )
        val states = listOf(
            PlayerProfile(UUID.randomUUID(), level = 16, flags = mutableSetOf("ec:flag_ch1_completed", "ec:flag_choice3_guild")),
            PlayerProfile(UUID.randomUUID(), level = 16, flags = mutableSetOf("ec:flag_ch1_completed", "ec:flag_choice3_observatory")),
            PlayerProfile(
                UUID.randomUUID(),
                level = 17,
                flags = mutableSetOf("ec:flag_ch1_completed", "ec:flag_ch2_started", "ec:flag_ch2_ridge_secured"),
                quests = mutableMapOf("ec:quest_ch2_m03_frozen_conduit" to net.nakaymi.etherealchronicle.core.QuestProgress()),
            ),
            PlayerProfile(
                UUID.randomUUID(),
                level = 19,
                flags = mutableSetOf(
                    "ec:flag_ch1_completed",
                    "ec:flag_ch2_started",
                    "ec:flag_ch2_choice4_stabilize",
                    "ec:flag_ch2_cavern_unlocked",
                    "ec:flag_ch2_colossus_defeated",
                    "ec:flag_ch2_citadel_unlocked",
                    "ec:flag_ch2_archon_defeated",
                ),
                quests = mutableMapOf("ec:quest_ch2_m07_cryo_archon" to net.nakaymi.etherealchronicle.core.QuestProgress()),
            ),
            PlayerProfile(
                UUID.randomUUID(),
                level = 20,
                flags = mutableSetOf(
                    "ec:flag_ch1_completed",
                    "ec:flag_ch2_started",
                    "ec:flag_ch2_choice4_overload",
                    "ec:flag_ch2_cavern_unlocked",
                    "ec:flag_ch2_colossus_defeated",
                    "ec:flag_ch2_citadel_unlocked",
                    "ec:flag_ch2_archon_defeated",
                    "ec:flag_ch2_choice5_resonance",
                    "ec:flag_ch2_completed",
                ),
            ),
        )
        ch2Dialogues.forEach { dlgId ->
            val dlg = snap.world.dialogues[dlgId] ?: fail("Missing dialogue $dlgId")
            states.forEach { profile ->
                val (nodeId, node) = dialogueEngine.node(profile, dlgId)
                assertTrue(nodeId in dlg.nodes)
                assertTrue(node.text.isNotBlank())
                node.choices.forEach { c ->
                    c.next?.let { assertTrue(it in dlg.nodes, "Broken next link $it in $dlgId/$nodeId/${c.id}") }
                }
            }
        }
    }

    @Test
    fun `full chapter 2 quest chain completes across all 3 classes and Choice 4 and 5 branches`() {
        val snap = loadBundled()
        val world = snap.world
        val classes = listOf("ec:class_warrior", "ec:class_archer", "ec:class_mage")
        val choice4Options = listOf("ec:flag_ch2_choice4_stabilize", "ec:flag_ch2_choice4_overload")
        val choice5Options = listOf("ec:flag_ch2_choice5_anchor", "ec:flag_ch2_choice5_resonance")

        for (cls in classes) {
            for (c4 in choice4Options) {
                for (c5 in choice5Options) {
                    val game = GameService({ snap }, SkillEngine(Clock { 0L }, snap.balance))
                    val p = PlayerProfile(
                        UUID.randomUUID(),
                        classId = cls,
                        level = 16,
                        flags = mutableSetOf("ec:flag_class_selected", "ec:flag_ch1_completed", "ec:flag_choice3_guild"),
                    )
                    val inv = MemoryInventory(capacity = 2304)

                    // Run main quests m01..m07
                    for (qid in ch2MainQuests) {
                        val q = world.quests.getValue(qid)
                        while (p.level < q.condition.minLevel) {
                            game.kill(p, "ec:enemy_frost_wolf", inv, rollDrops = false)
                        }
                        if (qid == "ec:quest_ch2_m03_frozen_conduit") p.flags += c4
                        if (qid == "ec:quest_ch2_m07_cryo_archon") p.flags += c5
                        game.startQuest(p, qid)
                        q.objectives.forEach { obj ->
                            when (obj.kind) {
                                ObjectiveKind.KILL -> repeat(obj.amount) { game.kill(p, obj.target, inv, rollDrops = false) }
                                ObjectiveKind.COLLECT -> assertTrue(inv.exchange(emptyMap(), mapOf(obj.target to obj.amount)))
                                else -> game.quests.event(p, obj.kind, obj.target, 1, inv)
                            }
                        }
                        assertTrue(game.quests.ready(p, qid, inv), "$qid not ready for $cls/$c4/$c5")
                        game.completeQuest(p, qid, inv)
                        assertTrue(p.quests.getValue(qid).completed)
                        assertTrue(runCatching { game.completeQuest(p, qid, inv) }.isFailure, "Double complete allowed on $qid")
                    }

                    // Verify Choice 4, Choice 5, class offhand, and completion flags
                    assertTrue("ec:flag_ch2_completed" in p.flags)
                    assertTrue(p.level <= snap.balance.levelCap)
                    val expectedAcc = if (c4 == "ec:flag_ch2_choice4_stabilize") {
                        "ec:item_acc_glacier_ward_pendant"
                    } else {
                        "ec:item_acc_frostbreaker_signet"
                    }
                    assertTrue(inv.count(expectedAcc) >= 1, "Missing Choice 4 accessory $expectedAcc")

                    val expectedOffhand = when (cls) {
                        "ec:class_warrior" -> "ec:item_off_frostguard_bulwark"
                        "ec:class_archer" -> "ec:item_off_glacier_quiver"
                        else -> "ec:item_off_cryo_grimoire"
                    }
                    assertTrue(inv.count(expectedOffhand) >= 1, "Missing class offhand $expectedOffhand")

                    // Verify side quest s01 + branch-exclusive s02/s03 + repeatable s04
                    val branchSide = if (c4 == "ec:flag_ch2_choice4_stabilize") {
                        "ec:quest_ch2_s02_ice_resonance"
                    } else {
                        "ec:quest_ch2_s03_defector_ledger"
                    }
                    val lockedSide = if (c4 == "ec:flag_ch2_choice4_stabilize") {
                        "ec:quest_ch2_s03_defector_ledger"
                    } else {
                        "ec:quest_ch2_s02_ice_resonance"
                    }
                    assertTrue(runCatching { game.startQuest(p, lockedSide) }.isFailure, "Opposite branch side quest $lockedSide should be locked")

                    for (sid in listOf("ec:quest_ch2_s01_frost_pelt", branchSide, "ec:quest_ch2_s04_citadel_echo")) {
                        val sq = world.quests.getValue(sid)
                        game.startQuest(p, sid)
                        sq.objectives.forEach { obj ->
                            when (obj.kind) {
                                ObjectiveKind.KILL -> repeat(obj.amount) { game.kill(p, obj.target, inv, rollDrops = false) }
                                ObjectiveKind.COLLECT -> assertTrue(inv.exchange(emptyMap(), mapOf(obj.target to obj.amount)))
                                else -> game.quests.event(p, obj.kind, obj.target, 1, inv)
                            }
                        }
                        assertTrue(game.quests.ready(p, sid, inv), "$sid not ready")
                        game.completeQuest(p, sid, inv)
                        assertTrue(p.quests.getValue(sid).completed)
                    }
                }
            }
        }
    }
}
