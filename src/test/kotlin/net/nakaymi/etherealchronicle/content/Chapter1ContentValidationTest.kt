package net.nakaymi.etherealchronicle.content

import net.nakaymi.etherealchronicle.core.BossState
import net.nakaymi.etherealchronicle.core.DialogueEngine
import net.nakaymi.etherealchronicle.core.GearSlot
import net.nakaymi.etherealchronicle.core.MemoryInventory
import net.nakaymi.etherealchronicle.core.ObjectiveKind
import net.nakaymi.etherealchronicle.core.PlayerProfile
import net.nakaymi.etherealchronicle.core.Rarity
import net.nakaymi.etherealchronicle.core.TradeEngine
import net.nakaymi.etherealchronicle.data.ContentService
import net.nakaymi.etherealchronicle.data.string
import net.nakaymi.etherealchronicle.data.strings
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.UUID
import kotlin.math.floor
import kotlin.test.*

/**
 * Loads every bundled YAML under src/main/resources/content through the real ContentService
 * and verifies Chapter 1 schema compliance, cross-references, spatial bounds, economy invariants,
 * dialogue/trade runtime behavior, boss scaling, and quest reachability.
 */
class Chapter1ContentValidationTest {
    private fun loadBundled() = ContentService(Path.of("src/main/resources/content")).load()

    @Test
    fun `all bundled YAMLs load cleanly through real ContentService and meet Chapter 1 targets`() {
        val snap = loadBundled()
        val world = snap.world

        // Classes (3 canonical bible IDs + legacy aliases)
        assertEquals(3, snap.classes.all().size)
        listOf(
            "ec:class_warrior" to "ec:warrior",
            "ec:class_archer" to "ec:archer",
            "ec:class_mage" to "ec:mage",
        ).forEach { (canonical, alias) ->
            assertEquals(canonical, snap.classes[canonical].id)
            assertEquals(canonical, snap.classes[alias].id)
            assertTrue(snap.classes[canonical].starterItems.isNotEmpty())
        }

        // Chapter 1 declaration counts (multi-chapter safe on global registries)
        val ch1 = world.chapters["ec:chapter_1"] ?: fail("Missing ec:chapter_1")
        assertEquals(5, (ch1["areas"] as List<*>).size)
        assertEquals(10, ch1.strings("npcs").size)
        assertEquals(20, ch1.strings("quests").size)
        assertTrue(world.areas.size >= 5)
        assertTrue(world.npcs.size >= 10)
        assertTrue(world.dialogues.size >= 10)
        assertTrue(world.shops.size >= 5)
        assertTrue(world.quests.size >= 20)
        assertTrue(snap.items.all().size >= 56)
        assertTrue(world.enemies.values.count { !it.boss } >= 14)
        assertTrue(world.enemies.values.count { it.boss } >= 2)
        assertTrue(snap.drops.all().size >= 16)

        // Equipment coverage across 3 classes & 5 rarities
        val ch1Gear = snap.items.all().filter { it.id.startsWith("ec:item_") && it.slot != GearSlot.NONE }
        assertTrue(ch1Gear.size >= 24, "Expected >=24 equipment items, got ${ch1Gear.size}")
        assertEquals(Rarity.entries.toSet(), ch1Gear.map { it.rarity }.toSet())
        listOf("ec:class_warrior", "ec:class_archer", "ec:class_mage").forEach { cid ->
            assertTrue(ch1Gear.count { cid in it.classes } >= 6, "Expected >=6 gear items for $cid")
        }
        ch1Gear.forEach { item ->
            assertTrue(item.withinBudget(), "Item ${item.id} exceeds rarity budget")
        }
    }

    @Test
    fun `quest objectives and dialogue actions resolve to valid typed targets with zero soft-locks`() {
        val snap = loadBundled()
        val world = snap.world
        val ch1 = world.chapters["ec:chapter_1"]!!
        val declaredFlags = world.chapters.values.flatMap { it.strings("flags") }.toSet()
        val declaredDungeons = world.chapters.values.flatMap { it.strings("dungeons") }.toSet()
        val ch1Quests = ch1.strings("quests").toSet()

        val droppedItems = snap.drops.all().flatMap { dt -> dt.entries.map { it.item } }.toSet()
        val questRewardItems = world.quests.values.flatMap { q -> q.rewards.items.map { it.item } }.toSet()
        val dialogueGiftItems = mutableSetOf<String>()
        val startedQuests = mutableSetOf<String>()
        val completedQuests = mutableSetOf<String>()

        world.dialogues.values.forEach { dlg ->
            dlg.nodes.values.forEach { node ->
                node.choices.forEach { choice ->
                    choice.flags.forEach { assertTrue(it in declaredFlags, "Undeclared flag $it in ${dlg.id}") }
                    choice.clearFlags.forEach { assertTrue(it in declaredFlags, "Undeclared clear flag $it in ${dlg.id}") }
                    choice.actions.forEach { action ->
                        when (action.kind) {
                            "START_QUEST" -> {
                                assertTrue(action.target in world.quests, "Unknown quest ${action.target} in ${dlg.id}")
                                startedQuests += action.target
                            }
                            "COMPLETE_QUEST" -> {
                                assertTrue(action.target in world.quests, "Unknown quest ${action.target} in ${dlg.id}")
                                completedQuests += action.target
                            }
                            "SELECT_CLASS" -> assertNotNull(snap.classes.find(action.target), "Unknown class ${action.target}")
                            "OPEN_SHOP" -> assertTrue(action.target in world.shops, "Unknown shop ${action.target}")
                            "ENTER_DUNGEON" -> assertTrue(action.target in declaredDungeons, "Undeclared dungeon ${action.target}")
                            "GIVE_ITEM" -> {
                                assertNotNull(snap.items.find(action.target), "Unknown item ${action.target}")
                                assertNotNull(action.onceFlag, "GIVE_ITEM in ${dlg.id}/${choice.id} must declare once_flag")
                                assertTrue(action.onceFlag in declaredFlags, "Undeclared once_flag ${action.onceFlag}")
                                dialogueGiftItems += action.target
                            }
                            else -> fail("Unsupported action kind ${action.kind}")
                        }
                    }
                }
            }
        }

        val obtainableItems = droppedItems + questRewardItems + dialogueGiftItems

        ch1Quests.forEach { qid ->
            val q = world.quests[qid] ?: fail("Chapter 1 quest $qid missing")
            assertTrue(qid in startedQuests, "Quest $qid is never started by any NPC dialogue")
            assertTrue(qid in completedQuests, "Quest $qid is never completed by any NPC dialogue")
            assertTrue(q.rewards.xp <= snap.balance.xpGainCap, "Quest $qid XP exceeds xp_gain_cap")
            assertTrue(q.rewards.currency <= snap.balance.currencyGainCap, "Quest $qid currency exceeds currency_gain_cap")
            q.rewards.flags.forEach { assertTrue(it in declaredFlags, "Undeclared reward flag $it in $qid") }
            q.objectives.forEach { obj ->
                when (obj.kind) {
                    ObjectiveKind.KILL -> assertTrue(obj.target in world.enemies, "Unknown KILL target ${obj.target} in $qid")
                    ObjectiveKind.COLLECT -> {
                        assertNotNull(snap.items.find(obj.target), "Unknown COLLECT target ${obj.target} in $qid")
                        assertTrue(obj.target in obtainableItems, "COLLECT target ${obj.target} in $qid has no drop/reward/gift source")
                    }
                    ObjectiveKind.EXPLORE -> assertTrue(obj.target in world.areas, "Unknown EXPLORE area ${obj.target} in $qid")
                    ObjectiveKind.TALK -> assertTrue(obj.target in world.npcs, "Unknown TALK npc ${obj.target} in $qid")
                }
            }
        }
    }

    @Test
    fun `areas do not overlap, NPCs and spawn points sit within declared areas, and spawn caps stay safe`() {
        val snap = loadBundled()
        val world = snap.world
        val areas = world.areas.values.toList()

        // Area width <= 80 and pairwise non-overlapping
        areas.forEach { a ->
            assertTrue(a.maxX - a.minX <= 80.0, "Area ${a.id} X width exceeds 80")
            assertTrue(a.maxZ - a.minZ <= 80.0, "Area ${a.id} Z width exceeds 80")
        }
        for (i in areas.indices) {
            for (j in i + 1 until areas.size) {
                val a = areas[i]
                val b = areas[j]
                val overlapX = a.minX <= b.maxX && b.minX <= a.maxX
                val overlapZ = a.minZ <= b.maxZ && b.minZ <= a.maxZ
                assertFalse(overlapX && overlapZ, "Areas ${a.id} and ${b.id} overlap")
            }
        }

        // NPCs inside declared areas and wired to valid dialogues/shops
        world.npcs.values.forEach { npc ->
            val doc = snap.documents[npc.id]!!
            val area = world.areas[doc.string("area")] ?: fail("Unknown area for ${npc.id}")
            assertTrue(area.contains(npc.position), "NPC ${npc.id} at ${npc.position} outside ${area.id}")
            assertTrue(npc.dialogue in world.dialogues, "Missing dialogue ${npc.dialogue} for ${npc.id}")
            npc.shop?.let { assertTrue(it in world.shops, "Missing shop $it for ${npc.id}") }
        }

        // Enemy & boss spawn points inside declared areas and total cap <= global_enemy_cap
        var totalCap = 0
        world.enemies.values.filter { it.spawns.isNotEmpty() }.forEach { mob ->
            mob.spawns.forEach { sp ->
                totalCap += sp.cap
                assertTrue(areas.any { it.contains(sp.position) }, "Spawn ${mob.id}/${sp.id} outside all declared areas")
            }
        }
        val globalCap = (snap.documents["ec:balance"]!!["global_enemy_cap"] as Number).toInt()
        assertTrue(totalCap <= globalCap, "Total spawn cap $totalCap exceeds global_enemy_cap $globalCap")
    }

    @Test
    fun `shops prevent buy-sell arbitrage and bound or quest items are non-sellable`() {
        val snap = loadBundled()
        val world = snap.world
        val sellRatio = (snap.documents["ec:balance"]!!["sell_ratio"] as Number).toDouble()

        world.itemProperties.forEach { (id, props) ->
            if (props.bound || props.questItem) {
                assertEquals(0L, props.price, "Bound/quest item $id must have price 0 (non-sellable)")
            }
        }

        world.shops.values.forEach { shop ->
            shop.entries.forEach { entry ->
                val props = world.itemProperties[entry.item] ?: fail("Missing item props for ${entry.item}")
                if (entry.price > 0) {
                    val effectiveBuy = entry.price * (1.0 - shop.discount)
                    val sellValue = floor(props.price * sellRatio) * entry.amount
                    assertTrue(
                        sellValue < effectiveBuy,
                        "Arbitrage detected in ${shop.id}/${entry.id}: sell=$sellValue >= discountedBuy=$effectiveBuy"
                    )
                } else {
                    assertTrue(entry.tokens.isNotEmpty(), "Zero-gold entry ${shop.id}/${entry.id} must require tokens")
                }
            }
        }
    }

    @Test
    fun `trade engine applies choice discounts and token discount subtraction accurately`() {
        val snap = loadBundled()
        val trade = TradeEngine { snap }

        // Choice 1A: Apothecary 10% discount
        val p1 = PlayerProfile(UUID.randomUUID(), currency = 1000)
        val bag1 = MemoryInventory(capacity = 50)
        val fullPrice = trade.buy(p1, "ec:shop_lumina_apothecary", "antidote_herb", bag1, "tx-1", 1)
        assertEquals(1000 - 40, fullPrice.currencyAfter)
        p1.flags.add("ec:flag_choice1_purify")
        val discounted = trade.buy(p1, "ec:shop_lumina_apothecary", "antidote_herb", bag1, "tx-2", 1)
        assertEquals(1000 - 36, discounted.currencyAfter)

        // Choice 3B: Stargazer Relic Observatory discount subtracts 5 Starshard Fragments only
        val p3 = PlayerProfile(UUID.randomUUID(), currency = 0, flags = mutableSetOf("ec:flag_void_warden_defeated"))
        val tokenBag = MemoryInventory(
            mutableMapOf(
                "ec:item_mat_void_core_essence" to 10,
                "ec:item_mat_starshard_fragment" to 40,
            ),
            capacity = 100,
        )
        val ringNoDiscount = trade.buy(p3, "ec:shop_stargazer_relic", "void_heart_ring", tokenBag, "tx-ring-base", 1)
        assertEquals(
            mapOf("ec:item_mat_void_core_essence" to 4, "ec:item_mat_starshard_fragment" to 25),
            ringNoDiscount.remove,
        )
        val cleaverNoDiscount = trade.buy(p3, "ec:shop_stargazer_relic", "void_cleaver", tokenBag, "tx-cleaver-base", 1)
        assertEquals(
            mapOf("ec:item_mat_void_core_essence" to 5, "ec:item_mat_starshard_fragment" to 30),
            cleaverNoDiscount.remove,
        )

        p3.flags.add("ec:flag_choice3_observatory")
        val ringDiscounted = trade.buy(p3, "ec:shop_stargazer_relic", "void_heart_ring", tokenBag, "tx-ring-disc", 1)
        assertEquals(
            mapOf("ec:item_mat_void_core_essence" to 4, "ec:item_mat_starshard_fragment" to 20),
            ringDiscounted.remove,
        )
        val cleaverDiscounted = trade.buy(p3, "ec:shop_stargazer_relic", "void_cleaver", tokenBag, "tx-cleaver-disc", 1)
        assertEquals(
            mapOf("ec:item_mat_void_core_essence" to 5, "ec:item_mat_starshard_fragment" to 25),
            cleaverDiscounted.remove,
        )
    }

    @Test
    fun `dialogue engine resolves all NPCs across chapter states without cycles or broken links`() {
        val snap = loadBundled()
        val dialogueEngine = DialogueEngine { snap }

        val profiles = listOf(
            PlayerProfile(UUID.randomUUID(), level = 1),
            PlayerProfile(
                UUID.randomUUID(),
                classId = "ec:class_warrior",
                level = 5,
                flags = mutableSetOf("ec:flag_class_selected", "ec:flag_ch1_started"),
            ),
            PlayerProfile(
                UUID.randomUUID(),
                classId = "ec:class_archer",
                level = 15,
                flags = mutableSetOf(
                    "ec:flag_class_selected",
                    "ec:flag_ch1_started",
                    "ec:flag_ashwood_cleared",
                    "ec:flag_choice1_purify",
                    "ec:flag_mine_unlocked",
                    "ec:flag_choice2_forgive",
                    "ec:flag_iron_grinder_defeated",
                    "ec:flag_stargazer_unlocked",
                    "ec:flag_sanctum_unlocked",
                ),
            ),
            PlayerProfile(
                UUID.randomUUID(),
                classId = "ec:class_mage",
                level = 19,
                flags = mutableSetOf(
                    "ec:flag_class_selected",
                    "ec:flag_ch1_started",
                    "ec:flag_ashwood_cleared",
                    "ec:flag_choice1_extract",
                    "ec:flag_mine_unlocked",
                    "ec:flag_choice2_report",
                    "ec:flag_iron_grinder_defeated",
                    "ec:flag_stargazer_unlocked",
                    "ec:flag_sanctum_unlocked",
                    "ec:flag_void_warden_defeated",
                    "ec:flag_choice3_observatory",
                    "ec:flag_ch1_completed",
                ),
            ),
        )

        snap.world.dialogues.values.forEach { dlg ->
            profiles.forEach { profile ->
                val (nodeId, node) = dialogueEngine.node(profile, dlg.id)
                assertTrue(nodeId in dlg.nodes, "Resolved invalid node $nodeId in ${dlg.id}")
                assertTrue(node.text.isNotBlank(), "Blank text in ${dlg.id}/$nodeId")
                node.choices.forEach { choice ->
                    choice.next?.let { nextId ->
                        assertTrue(nextId in dlg.nodes, "Broken next link $nextId in ${dlg.id}/$nodeId/${choice.id}")
                    }
                }
            }
        }
    }

    @Test
    fun `boss definitions have valid virtual HP, descending phases, kill flags, and coop scaling`() {
        val snap = loadBundled()
        val world = snap.world
        val warden = world.enemies["ec:boss_void_warden"] ?: fail("Missing ec:boss_void_warden")
        val grinder = world.enemies["ec:boss_iron_grinder"] ?: fail("Missing ec:boss_iron_grinder")

        assertTrue(warden.boss && grinder.boss)
        assertEquals(3, warden.phases.size)
        assertEquals(3, grinder.phases.size)
        assertEquals(55000.0, warden.hp)
        assertEquals(15000.0, grinder.hp)
        assertTrue("ec:flag_void_warden_defeated" in warden.flags)
        assertTrue("ec:flag_iron_grinder_defeated" in grinder.flags)

        // Verify real BossState scaling & phase transitions for 4-player party
        val wardenState = BossState(warden, partySize = 4, started = 0L)
        assertTrue(wardenState.maximumHp > warden.hp, "4-player boss HP should scale above solo HP")
        assertEquals(warden.phases.first().id, wardenState.phase()?.id)
        wardenState.hp = wardenState.maximumHp * 0.50
        assertEquals(warden.phases[1].id, wardenState.phase()?.id)
        wardenState.hp = wardenState.maximumHp * 0.20
        assertEquals(warden.phases[2].id, wardenState.phase()?.id)
        assertTrue(
            wardenState.damageScale(warden.enrageTicks) > wardenState.damageScale(0L),
            "Enrage tick should raise damage scale"
        )
    }
}
