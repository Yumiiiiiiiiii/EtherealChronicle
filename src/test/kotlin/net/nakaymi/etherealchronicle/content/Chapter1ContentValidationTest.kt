package net.nakaymi.etherealchronicle.content

import net.nakaymi.etherealchronicle.core.GearSlot
import net.nakaymi.etherealchronicle.core.ObjectiveKind
import net.nakaymi.etherealchronicle.core.Rarity
import net.nakaymi.etherealchronicle.data.ContentService
import net.nakaymi.etherealchronicle.data.string
import net.nakaymi.etherealchronicle.data.strings
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.math.floor
import kotlin.test.*

/**
 * Loads every bundled YAML under src/main/resources/content through the real ContentService
 * and verifies Chapter 1 schema compliance, cross-references, spatial bounds, economy invariants,
 * and quest reachability.
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

        // Chapter 1 declaration counts
        val ch1 = world.chapters["ec:chapter_1"] ?: fail("Missing ec:chapter_1")
        assertEquals(5, world.areas.size)
        assertEquals(10, world.npcs.size)
        assertEquals(10, world.dialogues.size)
        assertEquals(5, world.shops.size)
        assertEquals(20, ch1.strings("quests").size)
        assertTrue(world.quests.size >= 20)
        assertTrue(snap.items.all().size >= 56)
        assertTrue(world.enemies.values.count { !it.boss } >= 14)
        assertEquals(2, world.enemies.values.count { it.boss })
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
        val declaredFlags = ch1.strings("flags").toSet()
        val declaredDungeons = ch1.strings("dungeons").toSet()
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
    fun `boss definitions have valid virtual HP, descending phases, and kill flags`() {
        val snap = loadBundled()
        val world = snap.world
        val warden = world.enemies["ec:boss_void_warden"] ?: fail("Missing ec:boss_void_warden")
        val grinder = world.enemies["ec:boss_iron_grinder"] ?: fail("Missing ec:boss_iron_grinder")

        assertTrue(warden.boss && grinder.boss)
        assertEquals(3, warden.phases.size)
        assertEquals(3, grinder.phases.size)
        assertEquals(34000.0, warden.hp)
        assertEquals(7800.0, grinder.hp)
        assertTrue("ec:flag_void_warden_defeated" in warden.flags)
        assertTrue("ec:flag_iron_grinder_defeated" in grinder.flags)
    }
}
