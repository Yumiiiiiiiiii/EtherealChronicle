package net.nakaymi.etherealchronicle.content

import net.nakaymi.etherealchronicle.core.Balance
import net.nakaymi.etherealchronicle.core.BossState
import net.nakaymi.etherealchronicle.core.CastResult
import net.nakaymi.etherealchronicle.core.Clock
import net.nakaymi.etherealchronicle.core.DialogueEngine
import net.nakaymi.etherealchronicle.core.EnemyDefinition
import net.nakaymi.etherealchronicle.core.GameService
import net.nakaymi.etherealchronicle.core.GearSlot
import net.nakaymi.etherealchronicle.core.ItemDefinition
import net.nakaymi.etherealchronicle.core.MemoryInventory
import net.nakaymi.etherealchronicle.core.ObjectiveKind
import net.nakaymi.etherealchronicle.core.PlayerProfile
import net.nakaymi.etherealchronicle.core.Position
import net.nakaymi.etherealchronicle.core.QuestEngine
import net.nakaymi.etherealchronicle.core.Rarity
import net.nakaymi.etherealchronicle.core.ResourceKind
import net.nakaymi.etherealchronicle.core.SkillEngine
import net.nakaymi.etherealchronicle.core.Stats
import net.nakaymi.etherealchronicle.core.TitleConditionType
import net.nakaymi.etherealchronicle.core.TradeEngine
import net.nakaymi.etherealchronicle.core.WorldSchedule
import net.nakaymi.etherealchronicle.core.challengeRank
import net.nakaymi.etherealchronicle.core.enhancedStats
import net.nakaymi.etherealchronicle.core.equipTitle
import net.nakaymi.etherealchronicle.core.syncTitleUnlockFlags
import net.nakaymi.etherealchronicle.data.ContentService
import net.nakaymi.etherealchronicle.data.ContentSnapshot
import net.nakaymi.etherealchronicle.data.string
import net.nakaymi.etherealchronicle.data.strings
import net.nakaymi.etherealchronicle.paper.AdvancementDatapack
import org.bukkit.Material
import org.bukkit.entity.EntityType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import java.util.UUID
import kotlin.math.floor
import kotlin.test.*

/**
 * Validates the Early-Game Replayable Content Pack ("ルミナ見習い冒険録" / ec:chapter_early_adventures):
 * - 19 new items (17 slot:NONE materials/tackle/fish/dishes + 2 Lv4/Lv7 accessories within budget)
 * - 2 new tackle defs, 5 new early fish species (incl. DAWN_DUSK & NIGHT+RAIN), 4 new gathering nodes (6 coords)
 * - 4 new NPCs (2 hub + 2 hidden), 4 dialogues, 2 shops (rookie board + novice arena)
 * - 12 new quests (3 DAILY, 1 WEEKLY, 1 4h repeatable, 2 collection atlas, 1 +1..+3 enhancement guide, 2 arena, 2 hidden)
 * - 1 early 3-wave timed mini-challenge dungeon (ec:dungeon_novice_arena) + Lv7 instanced boss (ec:boss_ashwood_alpha)
 * - 6 new titles with advancement trees covering FLAG, BESTIARY, and COLLECTION conditions
 */
class EarlyGameContentValidationTest {
    @TempDir
    lateinit var tempDir: Path

    private val contentRoot = Path.of("src/main/resources/content")
    private fun loadBundled(): ContentSnapshot = ContentService(contentRoot).load()

    @Test
    fun `early hidden caches and scheduled appearances are shipped and schema-valid`() {
        val world=loadBundled().world
        val declaredFlags=world.chapters.values.flatMap { it.strings("flags") }.toSet()
        assertEquals(3,world.treasureChests.size)
        assertTrue(world.treasureChests.values.all { it.onceFlag in declaredFlags && it.items.isNotEmpty() })
        assertEquals(3,world.treasureChests.values.map { it.onceFlag }.distinct().size)

        val nightNpc=world.npcs.getValue("ec:npc_luna")
        assertEquals(setOf("NIGHT"),nightNpc.timeWindows)
        assertTrue(WorldSchedule.matches(nightNpc.timeWindows,nightNpc.weather,18000,false,false))
        assertFalse(WorldSchedule.matches(nightNpc.timeWindows,nightNpc.weather,6000,false,false))
        val rainNpc=world.npcs.getValue("ec:npc_moonshower_herbalist")
        assertTrue(WorldSchedule.matches(rainNpc.timeWindows,rainNpc.weather,0,true,false))
        assertFalse(WorldSchedule.matches(rainNpc.timeWindows,rainNpc.weather,0,false,false))

        val rareSpawn=world.enemies.getValue("ec:enemy_moss_goblin").spawns.single { it.id=="moonshower_rare_1" }
        assertEquals(1,rareSpawn.cap)
        assertEquals(setOf("NIGHT"),rareSpawn.timeWindows)
        assertEquals(setOf("RAIN","THUNDER"),rareSpawn.weather)
        assertTrue(world.areas.getValue("ec:area_ashwood_forest").contains(rareSpawn.position))
    }

    private val earlyNoneItems = listOf(
        "ec:item_mat_rookie_crest",
        "ec:item_mat_whetstone_shard",
        "ec:item_mat_alpha_fang",
        "ec:item_mat_river_pebble_ore",
        "ec:item_mat_dew_moss",
        "ec:item_mat_amber_sap",
        "ec:item_mat_hermit_scroll",
        "ec:item_tool_rod_willow",
        "ec:item_bait_dew_grub",
        "ec:item_fish_harbor_goby",
        "ec:item_fish_moss_loach",
        "ec:item_fish_dawn_minnow",
        "ec:item_fish_rain_eel",
        "ec:item_fish_amber_koi",
        "ec:item_con_goby_fritter",
        "ec:item_con_loach_broth",
        "ec:item_con_amber_koi_glaze",
    )

    private val earlyGearItems = listOf(
        "ec:item_acc_rookie_charm",
        "ec:item_acc_alpha_fang_talisman",
    )

    private val earlyQuests = listOf(
        "ec:quest_early_d01_slime_patrol",
        "ec:quest_early_d02_forest_bounty",
        "ec:quest_early_d03_supply_gather",
        "ec:quest_early_w01_ashwood_campaign",
        "ec:quest_early_r01_spider_silk_rush",
        "ec:quest_early_c01_port_atlas",
        "ec:quest_early_c02_ashwood_atlas",
        "ec:quest_early_e01_first_temper",
        "ec:quest_early_t01_arena_trial",
        "ec:quest_early_t02_arena_daily",
        "ec:quest_early_h01_luna_twilight_catch",
        "ec:quest_early_h02_baldo_grove_secret",
    )

    private val earlyNpcs = listOf(
        "ec:npc_leila",
        "ec:npc_rook",
        "ec:npc_luna",
        "ec:npc_baldo",
    )

    private val earlyShops = listOf(
        "ec:shop_rookie_board",
        "ec:shop_novice_arena",
    )

    private val earlyTitles = listOf(
        "ec:title_rookie_slayer",
        "ec:title_ashwood_hunter",
        "ec:title_early_collector",
        "ec:title_lumina_curator",
        "ec:title_novice_gladiator",
        "ec:title_grove_secret_finder",
    )

    private val earlyFish = listOf(
        "ec:fish_harbor_goby",
        "ec:fish_moss_loach",
        "ec:fish_dawn_minnow",
        "ec:fish_rain_eel",
        "ec:fish_amber_koi",
    )

    private val earlyNodes = listOf(
        "ec:node_port_pebble_ore",
        "ec:node_ashwood_dew_moss",
        "ec:node_ashwood_amber_tree",
        "ec:node_ashwood_surface_ore",
    )

    @Test
    fun `zero duplicate YAML ids, valid Paper enums, item budgets, and global enemy cap`() {
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
        assertEquals(19, earlyNoneItems.size + earlyGearItems.size)

        earlyNoneItems.forEach { id ->
            val item = snap.items.find(id) ?: fail("Missing early item $id")
            assertEquals(GearSlot.NONE, item.slot, "$id must be slot NONE")
            val mat = snap.documents.getValue(id).string("material")
            assertNotNull(Material.matchMaterial(mat), "Invalid Paper Material '$mat' on $id")
        }

        earlyGearItems.forEach { id ->
            val item = snap.items.find(id) ?: fail("Missing early gear $id")
            assertEquals(GearSlot.ACCESSORY, item.slot)
            assertTrue(item.minLevel in 1..8, "$id minLevel ${item.minLevel} must stay in Lv1..8")
            assertTrue(item.rarity.ordinal <= Rarity.RARE.ordinal, "$id rarity must stay <= RARE")
            assertTrue(item.withinBudget(), "$id exceeds rarity budget")
            val mat = snap.documents.getValue(id).string("material")
            assertNotNull(Material.matchMaterial(mat), "Invalid Paper Material '$mat' on $id")

            // Verify +1..+3 enhancement scaling stays strictly monotonic and positive
            val baseStats = item.stats
            val plus1 = item.enhancedStats(1, snap.balance, snap.world.tuning.enhancementRate)
            val plus3 = item.enhancedStats(3, snap.balance, snap.world.tuning.enhancementRate)
            assertTrue(plus1.hp > baseStats.hp && plus3.hp > plus1.hp)
            assertTrue(plus3.defense > plus1.defense)
        }

        // Dishes carry valid consumable effects
        listOf("ec:item_con_goby_fritter", "ec:item_con_loach_broth", "ec:item_con_amber_koi_glaze").forEach { id ->
            val props = snap.world.itemProperties[id] ?: fail("Missing itemProperties for $id")
            assertTrue(props.effects.isNotEmpty())
            assertTrue(props.effects.all { it.kind in setOf("HEAL", "RESOURCE", "CLEANSE", "DEFENSE_BUFF") })
        }

        // Entity types valid on all new NPCs & boss
        (earlyNpcs + "ec:boss_ashwood_alpha").forEach { id ->
            val ent = snap.documents.getValue(id).string("entity")
            assertNotNull(runCatching { EntityType.valueOf(ent) }.getOrNull(), "Invalid EntityType '$ent' on $id")
        }

        // Global enemy spawn cap stays within balance limit and instanced boss consumes 0 cap
        val alpha = snap.world.enemies.getValue("ec:boss_ashwood_alpha")
        assertTrue(alpha.boss && alpha.spawns.isEmpty())
        val totalSpawnCap = snap.world.enemies.values.sumOf { mob -> mob.spawns.sumOf { it.cap } }
        assertTrue(totalSpawnCap <= 100, "Total spawn cap $totalSpawnCap exceeds global_enemy_cap 100")
    }

    @Test
    fun `chapter wiring, non-overlapping NPC and gathering coordinates, life schemas, and arena dungeon config`() {
        val snap = loadBundled()
        val world = snap.world
        val chapter = world.chapters["ec:chapter_early_adventures"] ?: fail("Missing ec:chapter_early_adventures")

        assertEquals(earlyQuests.toSet(), chapter.strings("quests").toSet())
        assertEquals((earlyNpcs+"ec:npc_moonshower_herbalist").toSet(), chapter.strings("npcs").toSet())
        assertEquals(listOf("ec:dungeon_novice_arena"), chapter.strings("dungeons"))
        assertEquals(19, chapter.strings("flags").size)

        // All NPCs across the entire game occupy unique (world, x, z) coordinates
        val npcCoords = mutableSetOf<Triple<String, Int, Int>>()
        world.npcs.values.forEach { npc ->
            val key = Triple(npc.position.world, npc.position.x.toInt(), npc.position.z.toInt())
            assertTrue(npcCoords.add(key), "NPC ${npc.id} collides at $key")
        }
        earlyNpcs.forEach { nid ->
            val npc = world.npcs[nid] ?: fail("Missing NPC $nid")
            assertTrue(npc.dialogue in world.dialogues, "Missing dialogue for $nid")
        }
        assertTrue(world.areas.getValue("ec:area_lumina_port").contains(world.npcs.getValue("ec:npc_luna").position))
        assertTrue(world.areas.getValue("ec:area_ashwood_forest").contains(world.npcs.getValue("ec:npc_baldo").position))

        // All gathering nodes across the entire game occupy unique (world, x, z) coordinates
        val nodeCoords = world.gatheringNodes.values.flatMap { it.locations }.map { loc ->
            Triple(loc.world, loc.x, loc.z)
        }
        assertEquals(nodeCoords.size, nodeCoords.distinct().size, "Gathering node coordinate collision detected")
        earlyNodes.forEach { nid ->
            val node = world.gatheringNodes[nid] ?: fail("Missing node $nid")
            assertTrue(node.locations.isNotEmpty())
            node.locations.forEach { loc ->
                val pos = Position(loc.world, loc.x.toDouble(), loc.y.toDouble(), loc.z.toDouble())
                assertTrue(world.areas.getValue(loc.area).contains(pos), "${loc.id} outside ${loc.area}")
            }
        }

        // All 5 new early fish and 2 new tackle exist & keep sunbeam_trout as easiest Lv1 COMMON fish
        earlyFish.forEach { fid ->
            val fish = world.fishSpecies[fid] ?: fail("Missing fish $fid")
            assertTrue(fish.habitats.all { it in world.areas })
            if (fish.rarity == Rarity.COMMON && fish.minFishingLevel <= 1) {
                assertTrue(fish.minigame.difficulty >= 1.25, "$fid difficulty would displace defaultDebugFish")
            }
        }
        assertNotNull(world.tackle["ec:tackle_rod_willow"])
        assertNotNull(world.tackle["ec:tackle_bait_dew_grub"])

        // Novice arena 3-wave config & S/A/B/C rank thresholds
        val arena = world.dungeons["ec:dungeon_novice_arena"] ?: fail("Missing ec:dungeon_novice_arena")
        assertEquals("ec:flag_early_trial_unlocked", arena.unlockFlag)
        assertEquals(3, arena.waves.size)
        assertEquals("ec:enemy_thorn_wolf", arena.waves[0].boss)
        assertEquals("ec:enemy_spore_spider", arena.waves[1].boss)
        assertEquals("ec:boss_ashwood_alpha", arena.waves[2].boss)
        assertEquals("S", challengeRank(120, arena.scoreRanks))
        assertEquals("A", challengeRank(121, arena.scoreRanks))
        assertEquals("B", challengeRank(330, arena.scoreRanks))
        assertEquals("C", challengeRank(600, arena.scoreRanks))

        // Dialogues resolve cleanly across fresh and progressed profiles
        val dialogueEngine = DialogueEngine { snap }
        val profiles = listOf(
            PlayerProfile(UUID.randomUUID(), level = 1),
            PlayerProfile(
                UUID.randomUUID(),
                level = 7,
                flags = mutableSetOf(
                    "ec:flag_ch1_started",
                    "ec:flag_early_trial_unlocked",
                    "ec:flag_early_trial_cleared",
                    "ec:flag_early_luna_found",
                    "ec:flag_early_baldo_found",
                ),
            ),
        )
        listOf("ec:dlg_leila_board", "ec:dlg_rook_arena", "ec:dlg_luna_hidden", "ec:dlg_baldo_hermit").forEach { did ->
            val dlg = world.dialogues.getValue(did)
            profiles.forEach { profile ->
                val (nodeId, node) = dialogueEngine.node(profile, did)
                assertTrue(nodeId in dlg.nodes)
                assertTrue(node.text.isNotBlank())
            }
        }
    }

    @Test
    fun `early shops prevent gold and token-craft arbitrage and execute crafting trades cleanly`() {
        val snap = loadBundled()
        val world = snap.world
        val sellRatio = (snap.documents["ec:balance"]!!["sell_ratio"] as Number).toDouble()

        earlyShops.forEach { sid ->
            val shop = world.shops[sid] ?: fail("Missing shop $sid")
            assertTrue(shop.entries.size in 1..45)
            shop.entries.forEach { entry ->
                val outSell = floor(world.itemProperties.getValue(entry.item).price * sellRatio) * entry.amount
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
                        "Token-craft arbitrage in $sid/${entry.id}: outSell=$outSell >= tokenSellSum=$tokenSellSum",
                    )
                }
            }
        }

        // Prevent market-buy-to-turn-in arbitrage on repeatable COLLECT quests (d03 & r01)
        val boardShop = world.shops.getValue("ec:shop_rookie_board")
        fun marketPrice(itemId: String): Long =
            boardShop.entries.first { it.item == itemId && it.tokens.isEmpty() }.price

        listOf("ec:quest_early_d03_supply_gather", "ec:quest_early_r01_spider_silk_rush").forEach { qid ->
            val q = world.quests.getValue(qid)
            val inputCost = q.objectives.filter { it.kind == ObjectiveKind.COLLECT }.sumOf { marketPrice(it.target) * it.amount }
            val rewardLiquidation = q.rewards.currency + q.rewards.items.sumOf { r ->
                floor(world.itemProperties.getValue(r.item).price * sellRatio).toLong() * r.amount
            }
            assertTrue(
                inputCost > rewardLiquidation,
                "Turn-in arbitrage on $qid: inputCost=$inputCost <= rewardLiquidation=$rewardLiquidation",
            )
        }

        // End-to-end TradeEngine verification: whetstone craft, willow rod craft, alpha talisman exchange
        val trade = TradeEngine { snap }
        val p = PlayerProfile(UUID.randomUUID(), level = 7, currency = 2000)
        val bag = MemoryInventory(
            mutableMapOf(
                "ec:item_mat_whetstone_shard" to 4,
                "ec:item_mat_river_pebble_ore" to 2,
                "ec:item_mat_starwood_log" to 2,
                "ec:item_mat_amber_sap" to 1,
                "ec:item_mat_alpha_fang" to 4,
                "ec:item_mat_rookie_crest" to 3,
            ),
            capacity = 100,
        )

        val craftStone = trade.buy(p, "ec:shop_rookie_board", "craft_early_whetstone", bag, "early-tx-1", 1)
        assertTrue(bag.exchange(craftStone.remove, craftStone.add))
        trade.commit(p, craftStone)
        assertEquals(1, bag.count("ec:item_mat_artisan_whetstone"))

        val craftRod = trade.buy(p, "ec:shop_rookie_board", "craft_rod_willow", bag, "early-tx-2", 1)
        assertTrue(bag.exchange(craftRod.remove, craftRod.add))
        trade.commit(p, craftRod)
        assertEquals(1, bag.count("ec:item_tool_rod_willow"))

        val buyTalisman = trade.buy(p, "ec:shop_novice_arena", "exchange_alpha_talisman", bag, "early-tx-3", 1)
        assertTrue(bag.exchange(buyTalisman.remove, buyTalisman.add))
        trade.commit(p, buyTalisman)
        assertEquals(1, bag.count("ec:item_acc_alpha_fang_talisman"))
    }

    @Test
    fun `all 12 quests complete end-to-end, obey DAILY WEEKLY and 4h resets, and unlock all 6 advancement titles`() {
        val snap = loadBundled()
        val world = snap.world

        val started = mutableSetOf<String>()
        val completed = mutableSetOf<String>()
        world.dialogues.values.forEach { dlg ->
            dlg.nodes.values.forEach { node ->
                node.choices.forEach { choice ->
                    choice.actions.forEach { act ->
                        if (act.kind == "START_QUEST") started += act.target
                        if (act.kind == "COMPLETE_QUEST") completed += act.target
                    }
                }
            }
        }

        val drops = snap.drops.all().flatMap { it.entries.map { e -> e.item } }.toSet()
        val rewards = world.quests.values.flatMap { it.rewards.items.map { r -> r.item } }.toSet()
        val shopItems = world.shops.values.flatMap { it.entries.map { e -> e.item } }.toSet()

        earlyQuests.forEach { qid ->
            val q = world.quests[qid] ?: fail("Missing quest $qid")
            assertTrue(qid in started, "$qid missing START_QUEST")
            assertTrue(qid in completed, "$qid missing COMPLETE_QUEST")
            assertTrue(q.rewards.xp <= snap.balance.xpGainCap)
            assertTrue(q.rewards.currency <= snap.balance.currencyGainCap)
            q.objectives.forEach { obj ->
                if (obj.kind == ObjectiveKind.COLLECT) {
                    assertTrue(
                        obj.target in drops || obj.target in rewards || obj.target in shopItems,
                        "COLLECT target ${obj.target} in $qid has no source",
                    )
                }
            }
        }

        val tuning = world.tuning
        val zone = ZoneId.of(tuning.resetZone)
        fun epochMs(dt: LocalDateTime) = dt.atZone(zone).toInstant().toEpochMilli()

        val day0 = LocalDate.of(2026, 11, 18) // Wednesday
        var nowMs = epochMs(day0.atTime(tuning.dailyResetHour, 5))
        val engine = QuestEngine({ snap }, Clock { nowMs })
        val player = PlayerProfile(
            UUID.randomUUID(),
            classId = "ec:class_warrior",
            level = 8,
            flags = mutableSetOf(
                "ec:flag_ch1_started",
                "ec:flag_early_trial_unlocked",
                "ec:flag_early_luna_found",
                "ec:flag_early_baldo_found",
            ),
        )
        val inv = MemoryInventory(capacity = 500)

        for (qid in earlyQuests) {
            val q = world.quests.getValue(qid)
            engine.start(player, qid)
            q.objectives.forEach { obj ->
                when (obj.kind) {
                    ObjectiveKind.KILL -> repeat(obj.amount) { engine.event(player, ObjectiveKind.KILL, obj.target, 1, inv) }
                    ObjectiveKind.COLLECT -> assertTrue(inv.exchange(emptyMap(), mapOf(obj.target to obj.amount)))
                    else -> engine.event(player, obj.kind, obj.target, 1, inv)
                }
            }
            assertTrue(engine.ready(player, qid, inv), "$qid should be ready")
            engine.complete(player, qid, inv)
            assertTrue(player.quests.getValue(qid).completed, "$qid should be completed")
        }

        // Verify 4h repeatable cooldown (r01)
        val repId = "ec:quest_early_r01_spider_silk_rush"
        nowMs += 14_400_000L - 1_000L
        assertTrue(runCatching { engine.start(player, repId) }.isFailure, "4h repeatable should reject restart 1s early")
        nowMs += 2_000L
        engine.start(player, repId)
        assertFalse(player.quests.getValue(repId).completed)

        // Verify DAILY calendar reset boundary
        val beforeDaily = LocalDateTime.of(day0.plusDays(1), LocalTime.of(tuning.dailyResetHour, 0)).minusSeconds(1)
        val atDaily = LocalDateTime.of(day0.plusDays(1), LocalTime.of(tuning.dailyResetHour, 0))
        listOf(
            "ec:quest_early_d01_slime_patrol",
            "ec:quest_early_d02_forest_bounty",
            "ec:quest_early_d03_supply_gather",
            "ec:quest_early_t02_arena_daily",
        ).forEach { dailyId ->
            nowMs = epochMs(beforeDaily)
            assertTrue(runCatching { engine.start(player, dailyId) }.isFailure, "$dailyId should block before DAILY boundary")
            nowMs = epochMs(atDaily)
            engine.start(player, dailyId)
            assertFalse(player.quests.getValue(dailyId).completed, "$dailyId should reset at DAILY boundary")
        }

        // Verify WEEKLY calendar reset boundary
        val nextMonday = day0.with(TemporalAdjusters.next(DayOfWeek.valueOf(tuning.weeklyResetDay)))
        val beforeWeekly = LocalDateTime.of(nextMonday, LocalTime.of(tuning.dailyResetHour, 0)).minusSeconds(1)
        val atWeekly = LocalDateTime.of(nextMonday, LocalTime.of(tuning.dailyResetHour, 0))
        nowMs = epochMs(beforeWeekly)
        assertTrue(runCatching { engine.start(player, "ec:quest_early_w01_ashwood_campaign") }.isFailure)
        nowMs = epochMs(atWeekly)
        engine.start(player, "ec:quest_early_w01_ashwood_campaign")
        assertFalse(player.quests.getValue("ec:quest_early_w01_ashwood_campaign").completed)

        // Verify all 6 early titles (FLAG, BESTIARY, COLLECTION) unlock via syncTitleUnlockFlags and install to datapack
        player.bestiary["ec:enemy_aether_slime"] = 10
        player.bestiary["ec:enemy_corrupted_treant"] = 3
        player.discoveredItems.addAll(snap.items.all().take(35).map { it.id })
        val newlyUnlocked = player.syncTitleUnlockFlags(world.titles)
        earlyTitles.forEach { tid ->
            val title = world.titles.getValue(tid)
            assertNotNull(title.advancement, "$tid must define advancement")
            assertTrue(title.unlockFlag in player.flags, "$tid unlockFlag ${title.unlockFlag} missing after sync")
            assertTrue(title.unlockFlag in newlyUnlocked, "$tid should unlock cleanly via advancement condition")
            assertTrue(player.equipTitle(tid, world.titles), "$tid failed to equip")
        }
        assertEquals(
            setOf(TitleConditionType.FLAG, TitleConditionType.BESTIARY, TitleConditionType.COLLECTION),
            earlyTitles.map { world.titles.getValue(it).advancement!!.condition.type }.toSet(),
        )

        val install = AdvancementDatapack.install(tempDir.resolve("world"), world.titles.values, "エセリアルクロニクル", "冒険の記録", "エセリアルクロニクル: 称号進捗")
        assertTrue(install.changed)
        earlyTitles.forEach { tid ->
            val fileName = tid.substringAfter(':') + ".json"
            assertTrue(Files.exists(install.directory.resolve("data/ec/advancement/chronicle/$fileName")), "Missing $fileName")
        }
    }

    @Test
    fun `solo combat balance simulation for Lv7 boss Ashwood Alpha across all 6 classes`() {
        val snap = loadBundled()
        val alpha = snap.world.enemies.getValue("ec:boss_ashwood_alpha")

        fun bestEarlyLoadout(classId: String, level: Int): List<ItemDefinition> {
            val dummy = PlayerProfile(UUID.randomUUID(), classId = classId, level = level)
            val slots = listOf(GearSlot.HAND, GearSlot.OFFHAND, GearSlot.HEAD, GearSlot.CHEST, GearSlot.LEGS, GearSlot.FEET, GearSlot.ACCESSORY)
            val eligible = snap.items.all().filter {
                it.id.startsWith("ec:item_") && it.slot in slots && it.canEquip(dummy) && it.rarity.ordinal <= Rarity.RARE.ordinal
            }
            return slots.mapNotNull { slot ->
                eligible.filter { it.slot == slot }.maxWithOrNull(
                    compareBy<ItemDefinition>(
                        { it.rarity.ordinal },
                        { it.minLevel },
                        { snap.world.itemProperties[it.id]?.weapon ?: 0.0 },
                        { it.stats.attack + it.stats.defense + it.stats.hp / 10.0 },
                    ),
                )
            }
        }

        fun expectedDamage(balance: Balance, stats: Stats, targetDef: Double, mult: Double, weapon: Double): Double {
            val critChance = stats.crit.coerceIn(0.0, balance.critCap)
            val nonCrit = balance.damage(stats, targetDef, mult, weapon, critical = false)
            val crit = balance.damage(stats, targetDef, mult, weapon, critical = true)
            return nonCrit * (1.0 - critChance) + crit * critChance
        }

        fun simulateSolo(classId: String, level: Int, boss: EnemyDefinition): Pair<Boolean, Double> {
            var nowMs = 0L
            val clock = Clock { nowMs }
            val skillEngine = SkillEngine(clock, snap.balance)
            val game = GameService({ snap }, skillEngine)
            val profile = PlayerProfile(UUID.randomUUID(), level = level)
            game.changeClass(profile, classId)
            val classDef = snap.classes[classId]
            val gear = bestEarlyLoadout(classId, level)
            val stats = game.stats(profile, gear)
            val weaponDmg = gear.firstOrNull { it.slot == GearSlot.HAND }?.let { snap.world.itemProperties[it.id]?.weapon ?: 0.0 } ?: 0.0

            val bossState = BossState(boss, partySize = 1, started = 0L)
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
            while (elapsedMs < 180_000L && bossState.hp > 0.0 && playerHp > 0.0) {
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
                            val dmg = expectedDamage(snap.balance, stats, boss.defense, mult, weaponDmg)
                            bossState.hp = (bossState.hp - dmg).coerceAtLeast(0.0)
                            if (skill.heal > 0.0) playerHp = (playerHp + stats.hp * skill.heal).coerceAtMost(stats.hp)
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
                    val dmg = expectedDamage(snap.balance, stats, boss.defense, 1.0, weaponDmg)
                    bossState.hp = (bossState.hp - dmg).coerceAtLeast(0.0)
                    game.onHit(profile, stats.resource)
                    nextBasicMs = elapsedMs + 550L
                }

                val effDef = stats.defense + defBuff
                if (bossState.hp > 0.0 && tick >= nextEnemyAttackTick) {
                    val incoming = snap.balance.damage(Stats(attack = boss.attack), effDef, bossState.damageScale(tick), 0.0, false)
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
                // Early potion / dish recovery (e.g., Loach Broth 70HP / Minor Potion 80HP on 10s CD)
                if (playerHp > 0.0 && playerHp <= stats.hp * 0.60 && elapsedMs >= nextPotionMs) {
                    playerHp = (playerHp + 80.0).coerceAtMost(stats.hp)
                    nextPotionMs = elapsedMs + 10_000L
                }
                elapsedMs += 100L
            }
            return (bossState.hp <= 0.0 && playerHp > 0.0) to (elapsedMs / 1000.0)
        }

        val report = StringBuilder("\n=== ASHWOOD ALPHA (Lv7 SOLO) SIMULATION ===\n")
        snap.classes.all().forEach { cls ->
            val (won, sec) = simulateSolo(cls.id, 7, alpha)
            report.appendLine(String.format(Locale.US, "%-18s | AshwoodAlpha Lv7 Solo: %s %.1fs", cls.id, if (won) "WIN" else "DIE", sec))
            assertTrue(won, "${cls.id} failed Lv7 Ashwood Alpha solo (${sec}s)\n$report")
            assertTrue(sec in 25.0..95.0, "${cls.id} Lv7 Ashwood Alpha TTK out of range: ${sec}s\n$report")
        }
        println(report.toString())
    }
}
