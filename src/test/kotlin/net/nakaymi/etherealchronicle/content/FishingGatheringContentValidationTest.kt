package net.nakaymi.etherealchronicle.content

import net.nakaymi.etherealchronicle.core.Clock
import net.nakaymi.etherealchronicle.core.DialogueEngine
import net.nakaymi.etherealchronicle.core.GearSlot
import net.nakaymi.etherealchronicle.core.MemoryInventory
import net.nakaymi.etherealchronicle.core.ObjectiveKind
import net.nakaymi.etherealchronicle.core.PlayerProfile
import net.nakaymi.etherealchronicle.core.QuestEngine
import net.nakaymi.etherealchronicle.core.Rarity
import net.nakaymi.etherealchronicle.core.TradeEngine
import net.nakaymi.etherealchronicle.core.equipTitle
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
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import kotlin.math.floor
import kotlin.test.*

/**
 * Validates the Fishing & Gathering content pack ("蒼穹の釣師と採集匠" / ec:chapter_life_professions):
 * - 34 new items (6 fishing rods/baits, 6 gathering tools, 9 fish species across rarities, 8 gathered materials, 5 dishes/elixirs)
 * - 2 new NPCs (ec:npc_minato, ec:npc_hana), 2 dialogues, 2 shops (angler guild + gatherer exchange)
 * - 8 new quests (6 progression + 2 calendar-reset DAILY quests), 3 new titles, 5 declared flags
 * - Zero duplicate YAML IDs, valid Paper enums, zero gold/token/quest arbitrage, non-overlapping NPC coordinates,
 *   full QuestEngine & DAILY calendar boundary verification, and Title unlock/equip verification.
 */
class FishingGatheringContentValidationTest {
    private val contentRoot = Path.of("src/main/resources/content")
    private fun loadBundled(): ContentSnapshot = ContentService(contentRoot).load()

    private val lifeToolsAndBaits = listOf(
        "ec:item_tool_rod_oak",
        "ec:item_tool_rod_starwood",
        "ec:item_tool_rod_astral",
        "ec:item_bait_earthworm",
        "ec:item_bait_aether_lure",
        "ec:item_bait_frost_krill",
        "ec:item_tool_pick_iron",
        "ec:item_tool_pick_astral",
        "ec:item_tool_sickle_silver",
        "ec:item_tool_sickle_moon",
        "ec:item_tool_hatchet_iron",
        "ec:item_tool_extractor_aether",
    )

    private val newFishItems = listOf(
        "ec:item_fish_silver_sardine",
        "ec:item_fish_sunbeam_trout",
        "ec:item_fish_iron_catfish",
        "ec:item_fish_moon_carp",
        "ec:item_fish_glacier_smelt",
        "ec:item_fish_milkyway_dace",
        "ec:item_fish_blizzard_shark",
        "ec:item_fish_abyssal_angler",
        "ec:item_fish_celestial_oarfish",
    )

    private val newGatheredMaterials = listOf(
        "ec:item_mat_ash_leaf",
        "ec:item_mat_starwood_log",
        "ec:item_mat_stariron_ore",
        "ec:item_mat_starwood_resin",
        "ec:item_mat_azure_gem_ore",
        "ec:item_mat_frostbloom_herb",
        "ec:item_mat_moondew_herb",
        "ec:item_mat_aether_crystal_core",
    )

    private val newDishesAndElixirs = listOf(
        "ec:item_con_sardine_skewers",
        "ec:item_con_trout_herb_soup",
        "ec:item_con_moon_carp_sashimi",
        "ec:item_con_frost_seafood_pot",
        "ec:item_con_moondew_draught",
    )

    private val lifeQuests = listOf(
        "ec:quest_life_f01_angler_intro",
        "ec:quest_life_f02_forest_stream",
        "ec:quest_life_f03_abyssal_catch",
        "ec:quest_life_d01_daily_angler",
        "ec:quest_life_g01_gatherer_intro",
        "ec:quest_life_g02_mine_vein_survey",
        "ec:quest_life_g03_master_harvest",
        "ec:quest_life_d02_daily_gatherer",
    )

    private val lifeTitles = listOf(
        "ec:title_sky_angler" to "ec:flag_title_sky_angler",
        "ec:title_abyssal_fisher" to "ec:flag_title_abyssal_fisher",
        "ec:title_master_gatherer" to "ec:flag_title_master_gatherer",
    )

    private val lifeNpcs = listOf("ec:npc_minato", "ec:npc_hana")
    private val lifeShops = listOf("ec:shop_angler_guild", "ec:shop_gatherer_exchange")

    @Test
    fun `ec validate equivalent - zero duplicate YAML ids, valid Paper enums, and all 34 life items stay slot NONE`() {
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
        val allNewItems = lifeToolsAndBaits + newFishItems + newGatheredMaterials + newDishesAndElixirs
        assertEquals(34, allNewItems.size)

        allNewItems.forEach { id ->
            val item = snap.items.find(id) ?: fail("Missing life-profession item $id")
            assertEquals(GearSlot.NONE, item.slot, "Life item $id must use slot NONE so combat loadouts stay isolated")
            assertTrue(item.minLevel in 1..snap.balance.levelCap, "$id minLevel ${item.minLevel} out of cap")
            val matName = snap.documents.getValue(id).string("material")
            assertNotNull(Material.matchMaterial(matName), "Invalid Paper Material '$matName' on $id")
        }

        // Fish catalog (existing Sky Salmon + 9 new fish) spans all 5 rarities
        val allFishRarities = (newFishItems + "ec:item_mat_sky_salmon").map { snap.items[it].rarity }.toSet()
        assertEquals(Rarity.entries.toSet(), allFishRarities, "Fish catalog must span all 5 rarities")

        // Every dish/elixir has at least one valid consumable effect
        newDishesAndElixirs.forEach { id ->
            val props = snap.world.itemProperties[id] ?: fail("Missing itemProperties for $id")
            assertTrue(props.effects.isNotEmpty(), "Consumable $id must define at least one effect")
            assertTrue(
                props.effects.all { it.kind in setOf("HEAL", "RESOURCE", "CLEANSE", "DEFENSE_BUFF") },
                "Consumable $id contains unexpected effect kind"
            )
        }

        // NPC entity types valid
        lifeNpcs.forEach { nid ->
            val entName = snap.documents.getValue(nid).string("entity")
            assertNotNull(runCatching { EntityType.valueOf(entName) }.getOrNull(), "Invalid EntityType '$entName' on $nid")
        }
    }

    @Test
    fun `chapter declaration, NPC placement, dialogue wiring, and titles are valid and non-overlapping`() {
        val snap = loadBundled()
        val world = snap.world
        val chapter = world.chapters["ec:chapter_life_professions"] ?: fail("Missing ec:chapter_life_professions")

        assertEquals(lifeQuests.toSet(), chapter.strings("quests").toSet())
        assertEquals(lifeNpcs.toSet(), chapter.strings("npcs").toSet())
        assertEquals(5, chapter.strings("flags").size)

        // NPCs sit inside Lumina Port and do not share exact block coordinates with any other NPC
        val lumina = world.areas.getValue("ec:area_lumina_port")
        val allNpcCoords = mutableSetOf<Triple<String, Int, Int>>()
        world.npcs.values.forEach { npc ->
            val key = Triple(npc.position.world, npc.position.x.toInt(), npc.position.z.toInt())
            assertTrue(allNpcCoords.add(key), "NPC ${npc.id} collides with another NPC at $key")
        }
        lifeNpcs.forEach { nid ->
            val npc = world.npcs[nid] ?: fail("Missing NPC $nid")
            assertTrue(lumina.contains(npc.position), "$nid outside ec:area_lumina_port")
            assertTrue(npc.dialogue in world.dialogues, "$nid dialogue missing")
            assertNotNull(npc.shop?.let(world.shops::get), "$nid shop missing")
        }

        // Titles exist and link to declared flags
        val declaredFlags = world.chapters.values.flatMap { it.strings("flags") }.toSet()
        lifeTitles.forEach { (tid, flag) ->
            val title = world.titles[tid] ?: fail("Missing title $tid")
            assertEquals(flag, title.unlockFlag)
            assertTrue(flag in declaredFlags, "Title flag $flag not declared in any chapter")
        }

        // Dialogue resolves cleanly across fresh, enrolled, and endgame player profiles
        val dialogueEngine = DialogueEngine { snap }
        val testProfiles = listOf(
            PlayerProfile(UUID.randomUUID(), level = 1),
            PlayerProfile(UUID.randomUUID(), level = 5, flags = mutableSetOf("ec:flag_ch1_started", "ec:flag_life_angler_enrolled", "ec:flag_life_gatherer_enrolled")),
            PlayerProfile(UUID.randomUUID(), level = 18, flags = mutableSetOf("ec:flag_ch1_started", "ec:flag_ch2_started", "ec:flag_life_angler_enrolled", "ec:flag_life_gatherer_enrolled")),
        )
        listOf("ec:dlg_minato_hub", "ec:dlg_hana_hub").forEach { did ->
            val dlg = world.dialogues.getValue(did)
            testProfiles.forEach { profile ->
                val (nodeId, node) = dialogueEngine.node(profile, did)
                assertTrue(nodeId in dlg.nodes)
                assertTrue(node.text.isNotBlank())
            }
        }
    }

    @Test
    fun `fishing and gathering shops prevent gold and token-craft arbitrage and execute trades cleanly`() {
        val snap = loadBundled()
        val world = snap.world
        val sellRatio = (snap.documents["ec:balance"]!!["sell_ratio"] as Number).toDouble()

        lifeShops.forEach { sid ->
            val shop = world.shops[sid] ?: fail("Missing shop $sid")
            assertTrue(shop.entries.size in 1..45, "Shop $sid size ${shop.entries.size} out of 1..45")
            shop.entries.forEach { entry ->
                val outProps = world.itemProperties.getValue(entry.item)
                val outSell = floor(outProps.price * sellRatio) * entry.amount
                if (entry.price > 0) {
                    val effBuy = entry.price * (1.0 - shop.discount)
                    assertTrue(outSell < effBuy, "Gold arbitrage in $sid/${entry.id}: sell=$outSell >= buy=$effBuy")
                } else {
                    assertTrue(entry.tokens.isNotEmpty(), "Free entry $sid/${entry.id} must require tokens")
                    val tokenSellSum = entry.tokens.sumOf { t ->
                        floor(world.itemProperties.getValue(t.item).price * sellRatio) * t.amount
                    }
                    assertTrue(
                        outSell < tokenSellSum,
                        "Token-craft arbitrage in $sid/${entry.id}: outputSell=$outSell >= tokenSellSum=$tokenSellSum"
                    )
                }
            }
        }

        // End-to-end TradeEngine execution: buy rod/bait, cook dish, refine whetstone
        val trade = TradeEngine { snap }
        val p = PlayerProfile(UUID.randomUUID(), level = 15, currency = 5000)
        val bag = MemoryInventory(
            mutableMapOf(
                "ec:item_fish_silver_sardine" to 4,
                "ec:item_mat_stariron_ore" to 4,
                "ec:item_mat_azure_gem_ore" to 2,
            ),
            capacity = 100,
        )

        val buyRod = trade.buy(p, "ec:shop_angler_guild", "buy_rod_oak", bag, "life-tx-1", 1)
        assertTrue(bag.exchange(buyRod.remove, buyRod.add))
        trade.commit(p, buyRod)
        assertEquals(1, bag.count("ec:item_tool_rod_oak"))

        val cookSkewers = trade.buy(p, "ec:shop_angler_guild", "cook_sardine_skewers", bag, "life-tx-2", 1)
        assertEquals(mapOf("ec:item_fish_silver_sardine" to 2), cookSkewers.remove)
        assertEquals(mapOf("ec:item_con_sardine_skewers" to 1), cookSkewers.add)
        assertTrue(bag.exchange(cookSkewers.remove, cookSkewers.add))
        trade.commit(p, cookSkewers)

        val refineWhetstone = trade.buy(p, "ec:shop_gatherer_exchange", "refine_artisan_whetstone", bag, "life-tx-3", 1)
        assertEquals(mapOf("ec:item_mat_artisan_whetstone" to 1), refineWhetstone.add)
        assertTrue(bag.exchange(refineWhetstone.remove, refineWhetstone.add))
        trade.commit(p, refineWhetstone)
    }

    @Test
    fun `all 8 life quests wire to dialogues, stay within caps, unlock titles, and obey DAILY calendar reset`() {
        val snap = loadBundled()
        val world = snap.world

        val started = mutableSetOf<String>()
        val completed = mutableSetOf<String>()
        world.dialogues.values.forEach { dlg ->
            dlg.nodes.values.forEach { n ->
                n.choices.forEach { c ->
                    c.actions.forEach { a ->
                        if (a.kind == "START_QUEST") started += a.target
                        if (a.kind == "COMPLETE_QUEST") completed += a.target
                    }
                }
            }
        }

        val drops = snap.drops.all().flatMap { it.entries.map { e -> e.item } }.toSet()
        val rewards = world.quests.values.flatMap { it.rewards.items.map { r -> r.item } }.toSet()
        val shopItems = world.shops.values.flatMap { it.entries.map { e -> e.item } }.toSet()

        lifeQuests.forEach { qid ->
            val q = world.quests[qid] ?: fail("Missing quest $qid")
            assertTrue(qid in started, "$qid missing START_QUEST action")
            assertTrue(qid in completed, "$qid missing COMPLETE_QUEST action")
            assertTrue(q.rewards.xp <= snap.balance.xpGainCap, "$qid XP exceeds cap")
            assertTrue(q.rewards.currency <= snap.balance.currencyGainCap, "$qid currency exceeds cap")
            q.objectives.forEach { obj ->
                if (obj.kind == ObjectiveKind.COLLECT) {
                    assertTrue(
                        obj.target in drops || obj.target in rewards || obj.target in shopItems,
                        "COLLECT target ${obj.target} in $qid has no obtainable source"
                    )
                }
            }
        }

        // Prevent shop-buy-to-daily-turn-in gold arbitrage on both DAILY quests
        listOf(
            "ec:quest_life_d01_daily_angler" to ("ec:shop_angler_guild" to "market_silver_sardine"),
            "ec:quest_life_d02_daily_gatherer" to ("ec:shop_gatherer_exchange" to "market_ash_leaf"),
        ).forEach { (qid, shopRef) ->
            val q = world.quests.getValue(qid)
            assertEquals("DAILY", q.resetSchedule, "$qid must use DAILY calendar reset")
            val collectAmount = q.objectives.first { it.kind == ObjectiveKind.COLLECT }.amount
            val shopEntry = world.shops.getValue(shopRef.first).entries.first { it.id == shopRef.second }
            assertTrue(
                shopEntry.price * collectAmount > q.rewards.currency,
                "Daily quest $qid currency reward ${q.rewards.currency} must stay below market purchase cost ${shopEntry.price * collectAmount}"
            )
        }

        // Execute full progression chain + title equip + UTC 20:00 DAILY calendar boundary
        val tuning = world.tuning
        val zone = ZoneId.of(tuning.resetZone)
        fun epochMs(dt: LocalDateTime) = dt.atZone(zone).toInstant().toEpochMilli()

        val day0 = LocalDate.of(2026, 11, 20)
        var nowMs = epochMs(day0.atTime(tuning.dailyResetHour, 5))
        val engine = QuestEngine({ snap }, Clock { nowMs })
        val player = PlayerProfile(
            UUID.randomUUID(),
            classId = "ec:class_warrior",
            level = 16,
            flags = mutableSetOf("ec:flag_ch1_started", "ec:flag_ch2_started"),
        )
        val inv = MemoryInventory(capacity = 500)

        for (qid in lifeQuests) {
            val q = world.quests.getValue(qid)
            engine.start(player, qid)
            q.objectives.forEach { obj ->
                when (obj.kind) {
                    ObjectiveKind.COLLECT -> assertTrue(inv.exchange(emptyMap(), mapOf(obj.target to obj.amount)))
                    else -> engine.event(player, obj.kind, obj.target, 1, inv)
                }
            }
            assertTrue(engine.ready(player, qid, inv), "$qid should be ready")
            engine.complete(player, qid, inv)
            assertTrue(player.quests.getValue(qid).completed, "$qid should be completed")
        }

        // All 3 new titles are unlocked and equippable
        lifeTitles.forEach { (tid, flag) ->
            assertTrue(flag in player.flags, "Expected title unlock flag $flag")
            assertTrue(player.equipTitle(tid, world.titles), "Expected title $tid to equip cleanly")
            assertEquals(tid, player.selectedTitle)
        }

        // Verify DAILY reset boundary on both new daily quests
        val beforeBoundary = LocalDateTime.of(day0.plusDays(1), LocalTime.of(tuning.dailyResetHour, 0)).minusSeconds(1)
        val atBoundary = LocalDateTime.of(day0.plusDays(1), LocalTime.of(tuning.dailyResetHour, 0))
        listOf("ec:quest_life_d01_daily_angler", "ec:quest_life_d02_daily_gatherer").forEach { dailyId ->
            nowMs = epochMs(beforeBoundary)
            assertTrue(runCatching { engine.start(player, dailyId) }.isFailure, "$dailyId should reject restart 1s before reset")
            nowMs = epochMs(atBoundary)
            engine.start(player, dailyId)
            assertFalse(player.quests.getValue(dailyId).completed, "$dailyId should reset at calendar boundary")
        }
    }
}
