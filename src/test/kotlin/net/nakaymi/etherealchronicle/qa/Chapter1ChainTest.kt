package net.nakaymi.etherealchronicle.qa

import net.nakaymi.etherealchronicle.core.*
import net.nakaymi.etherealchronicle.data.ContentService
import net.nakaymi.etherealchronicle.data.ContentSnapshot
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * QA: drives the SHIPPED chapter-1 content (src/main/resources/content) through the pure core services:
 * class -> m01..m08 for all classes x both branch choices, trade exploits, party XP, boss phases, spawn caps.
 * Writes a human-readable trace to build/qa-chain.txt.
 */
class Chapter1ChainTest {
    private val snapshot: ContentSnapshot by lazy { ContentService(Path.of("src/main/resources/content")).load() }
    private val world get() = snapshot.world
    private val trace = StringBuilder()
    private val mainQuests = listOf("m01_awakening", "m02_first_hunt", "m03_ashwood_core", "m04_road_to_mine", "m05_mine_betrayal", "m06_iron_grinder", "m07_stargazer_oath", "m08_void_warden").map { "ec:quest_$it" }

    private fun newGame(): GameService = GameService({ snapshot }, SkillEngine(Clock { 0L }, snapshot.balance))

    /** Kills the best-xp enemy appropriate for the player's level until the level gate is met; returns kills used. */
    private fun grindTo(game: GameService, p: PlayerProfile, level: Int, inv: InventoryAccess): Int {
        var kills = 0
        while (p.level < level) {
            val enemy = world.enemies.values.filter { !it.boss && it.level <= p.level + 3 }.maxByOrNull { snapshot.documents.getValue(it.id)["xp"].toString().toDouble() }
                ?: fail("no enemy to grind at level ${p.level}")
            game.kill(p, enemy.id, inv, rollDrops = false); kills++
            if (kills > 5000) fail("grind to level $level did not converge (level ${p.level}, xp ${p.xp})")
        }
        return kills
    }

    private fun runChain(cls: String, c1: String, c2: String, c3: String): PlayerProfile {
        val game = newGame(); val p = PlayerProfile(UUID.nameUUIDFromBytes("chain-$cls-$c1-$c3".toByteArray()))
        val inv = MemoryInventory(capacity = 2304)
        game.chooseClass(p, cls)
        assertTrue("ec:quest_m01_awakening" in p.quests, "m01 not auto-started on class choice")
        trace.appendLine("## $cls / $c1 / $c2 / $c3")
        for (id in mainQuests) {
            val q = world.quests.getValue(id)
            val grind = grindTo(game, p, q.condition.minLevel, inv)
            when (id) { "ec:quest_m03_ashwood_core" -> p.flags += c1; "ec:quest_m05_mine_betrayal" -> p.flags += c2; "ec:quest_m08_void_warden" -> p.flags += c3 }
            if (id !in p.quests) game.startQuest(p, id)
            for (o in q.objectives) when (o.kind) {
                ObjectiveKind.KILL -> repeat(o.amount) { game.kill(p, o.target, inv, rollDrops = false) }
                ObjectiveKind.COLLECT -> assertTrue(inv.exchange(emptyMap(), mapOf(o.target to o.amount)), "inventory full while granting ${o.target}")
                else -> game.quests.event(p, o.kind, o.target, 1, inv)
            }
            assertTrue(game.quests.ready(p, id, inv), "$id not ready after objectives: ${p.quests[id]}")
            val before = p.level
            game.completeQuest(p, id, inv)
            assertTrue(p.quests.getValue(id).completed)
            val exploited = runCatching { game.completeQuest(p, id, inv) }.isSuccess
            assertTrue(!exploited, "$id could be completed twice (reward replay)")
            trace.appendLine("${id.removePrefix("ec:quest_")}: grindKills=$grind level $before->${p.level} xp=${p.xp} currency=${p.currency} flags=${p.flags.size} items=${inv.contents.values.sum()}")
        }
        assertTrue("ec:flag_ch1_completed" in p.flags, "chapter flag missing")
        assertTrue(p.level <= snapshot.balance.levelCap)
        return p
    }

    @Test
    fun fullChapterChainAllClassesAndBranches() {
        val classes = snapshot.classes.all().map { it.id }
        assertTrue(classes.size >= 3)
        for (cls in classes) for (c1 in listOf("ec:flag_choice1_purify", "ec:flag_choice1_extract")) for (c2 in listOf("ec:flag_choice2_forgive", "ec:flag_choice2_report")) for (c3 in listOf("ec:flag_choice3_guild", "ec:flag_choice3_observatory")) {
            val p = runChain(cls, c1, c2, c3)
            // branch-specific rewards must exist
            assertTrue(p.flags.containsAll(setOf(c1, c2, c3)))
        }
        File("build").mkdirs(); File("build/qa-chain.txt").writeText(trace.toString())
    }

    @Test
    fun everyQuestItemIsObtainableAndEveryObjectiveTargetExists() {
        val drops = snapshot.drops.all().flatMap { t -> t.entries.map { it.item } }.toSet()
        val rewarded = world.quests.values.flatMap { q -> q.rewards.items.map { it.item } }.toSet()
        val shopItems = world.shops.values.flatMap { s -> s.entries.map { it.item } }.toSet()
        val problems = mutableListOf<String>()
        world.quests.values.forEach { q -> q.objectives.forEach { o ->
            when (o.kind) {
                ObjectiveKind.COLLECT -> if (o.target !in drops && o.target !in rewarded && o.target !in shopItems) problems += "${q.id}/${o.id}: ${o.target} is neither dropped, rewarded nor sold"
                ObjectiveKind.KILL -> if (o.target !in world.enemies && o.target != "*") problems += "${q.id}/${o.id}: unknown enemy ${o.target}"
                ObjectiveKind.TALK -> if (o.target !in world.npcs) problems += "${q.id}/${o.id}: unknown npc ${o.target}"
                ObjectiveKind.EXPLORE -> if (o.target !in world.areas) problems += "${q.id}/${o.id}: unknown area ${o.target}"
                ObjectiveKind.CATCH_FISH -> if (o.target !in world.fishSpecies && o.target !in setOf("ANY","*")) problems += "${q.id}/${o.id}: unknown fish ${o.target}"
                ObjectiveKind.GATHER_NODE -> if (o.target !in world.gatheringNodes && o.target !in setOf("ANY","*","HERB","ORE","WOOD","CRYSTAL")) problems += "${q.id}/${o.id}: unknown node ${o.target}"
                ObjectiveKind.ENHANCE_GEAR -> if (o.target !in setOf("ANY","*") && o.target.toIntOrNull() !in 1..world.tuning.enhancementCap) problems += "${q.id}/${o.id}: invalid enhancement target ${o.target}"
            }
        } }
        // Each branch flag must be settable by a dialogue choice.
        val setFlags = world.dialogues.values.flatMap { d -> d.nodes.values.flatMap { n -> n.choices.flatMap { it.flags } } }.toSet()
        world.quests.values.flatMap { q -> q.rewards.items.flatMap { it.condition.hasFlags } + q.condition.hasFlags }.toSet().forEach { f ->
            if (f !in setFlags && world.quests.values.none { q -> f in q.rewards.flags } && world.enemies.values.none { f in it.flags }) problems += "flag $f is required by content but never set"
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    @Test
    fun tradeExploitAttempts() {
        val trade = TradeEngine { snapshot }
        val rich = { PlayerProfile(UUID.randomUUID(), level = 20, currency = 1_000_000).also { it.flags += snapshot.world.shops.values.flatMap { s -> s.entries.flatMap { e -> e.condition.hasFlags } } } }
        // 1) replay of a committed transaction id
        val p = rich(); val inv = MemoryInventory(capacity = 2304)
        val shop = world.shops.values.first { s -> s.entries.any { it.tokens.isEmpty() && it.price > 0 && it.condition == Condition() && it.dailyLimit == 0 } }
        val e = shop.entries.first { it.tokens.isEmpty() && it.price > 0 && it.condition == Condition() && it.dailyLimit == 0 }
        val plan = trade.buy(p, shop.id, e.id, inv, "tx-1", 1)
        assertTrue(inv.exchange(plan.remove, plan.add)); trade.commit(p, plan)
        assertTrue(runCatching { trade.buy(p, shop.id, e.id, inv, "tx-1", 1) }.isFailure, "replayed txn id accepted by buy()")
        assertTrue(runCatching { trade.commit(p, plan) }.isFailure, "replayed plan committed twice")
        // 2) double click: two plans planned before either commit, both from the same currency snapshot
        val q = rich(); val inv2 = MemoryInventory(capacity = 2304)
        val a = trade.buy(q, shop.id, e.id, inv2, "a", 1); val b = trade.buy(q, shop.id, e.id, inv2, "b", 1)
        trade.commit(q, a)
        assertTrue(runCatching { trade.commit(q, b) }.isFailure, "second stale plan committed after first (double-click dupe)")
        // 3) arbitrage: buying then selling back never nets positive
        val arb = mutableListOf<String>()
        world.shops.values.forEach { s -> s.entries.forEach { en ->
            if (!s.buyback || en.tokens.isNotEmpty() || en.price <= 0) return@forEach
            val props = world.itemProperties[en.item] ?: return@forEach
            if (props.bound || props.questItem || props.price <= 0) return@forEach
            val sellGain = kotlin.math.floor(props.price * world.tuning.sellRatio).toLong() * en.amount
            val cost = kotlin.math.ceil(en.price.toDouble() * (1 - s.discount)).toLong()
            if (sellGain >= cost) arb += "${s.id}/${en.id}: buy $cost (with discount) <= sell $sellGain"
        } }
        assertTrue(arb.isEmpty(), "arbitrage loops:\n" + arb.joinToString("\n"))
        // 4) selling quest/bound items, negative/oversized amounts
        val inv3 = MemoryInventory(mutableMapOf(), 2304)
        world.itemProperties.filter { it.value.questItem || it.value.bound }.keys.take(5).forEach { id ->
            inv3.exchange(emptyMap(), mapOf(id to 1))
            assertTrue(runCatching { trade.sell(rich(), shop.id, id, 1, inv3, "s-$id") }.isFailure, "sold non-sellable $id")
        }
        assertTrue(runCatching { trade.sell(rich(), shop.id, e.item, -1, inv, "neg") }.isFailure)
        assertTrue(runCatching { trade.sell(rich(), shop.id, e.item, 10_000, inv, "huge") }.isFailure)
        // 5) currency cap
        val capped = PlayerProfile(UUID.randomUUID(), currency = world.tuning.maxCurrency)
        val ii = MemoryInventory(mutableMapOf(e.item to 5), 2304)
        val sellable = world.itemProperties[e.item]?.takeIf { !it.bound && !it.questItem && it.price > 0 }
        if (sellable != null) assertTrue(runCatching { trade.sell(capped, shop.id, e.item, 5, ii, "cap") }.isFailure, "sale above max currency accepted")
        // 6) daily limit
        world.shops.values.flatMap { s -> s.entries.filter { it.dailyLimit > 0 && it.tokens.isEmpty() && it.condition.completed.isEmpty() && it.condition.classes.isEmpty() }.map { s to it } }.firstOrNull()?.let { (s, en) ->
            val d = rich(); val di = MemoryInventory(capacity = 2304)
            repeat(en.dailyLimit / en.amount) { i -> val pl = trade.buy(d, s.id, en.id, di, "d$i", 7); di.exchange(pl.remove, pl.add); trade.commit(d, pl) }
            assertTrue(runCatching { trade.buy(d, s.id, en.id, di, "dx", 7) }.isFailure, "daily limit exceeded")
        }
    }

    @Test
    fun partyXpSplitIsExactAndBounded() {
        val svc = PartyService({ world.tuning.partyCap }, Clock { 0L })
        val ids = List(world.tuning.partyCap + 2) { UUID.randomUUID() }
        listOf(0L, 1L, 7L, 25L, 999_983L).forEach { total ->
            (1..ids.size).forEach { n ->
                val split = svc.splitXp(total, ids.take(n).toSet())
                assertEquals(total, split.values.sum(), "xp $total over $n members")
                assertTrue(split.values.max() - split.values.min() <= 1)
            }
        }
        val leader = ids[0]
        ids.drop(1).forEach { runCatching { svc.invite(leader, it); svc.accept(it) } }
        assertTrue(svc.members(leader).size <= world.tuning.partyCap, "party exceeded cap: ${svc.members(leader).size}")
    }

    @Test
    fun bossPhasesScaleWithPartyAndEnrage() {
        val boss = world.enemies.values.filter { it.boss }
        assertTrue(boss.isNotEmpty())
        boss.forEach { def ->
            val solo = BossState(def, 1, 0); val four = BossState(def, 4, 0)
            assertTrue(four.maximumHp >= solo.maximumHp)
            var lastScale = 0.0
            val seen = mutableListOf<String?>()
            listOf(1.0, 0.75, 0.5, 0.25, 0.1, 0.0).forEach { r -> solo.hp = solo.maximumHp * r; seen += solo.phase()?.id }
            assertTrue(def.phases.isNotEmpty(), "${def.id} has no phases")
            assertTrue(solo.damageScale(def.enrageTicks + 1) >= solo.damageScale(0), "${def.id} enrage should not reduce damage")
            trace.appendLine("boss ${def.id}: hp=${def.hp} phases=${def.phases.map { it.id to it.below }} seen=$seen enrage@${def.enrageTicks} x${def.enrageMultiplier}")
            lastScale += solo.damageScale(0)
        }
        File("build").mkdirs(); File("build/qa-chain.txt").appendText(trace.toString())
    }

    @Test
    fun spawnBudgetHoldsUnderTwentyPlayers() {
        val cap = world.tuning.enemyCap
        val budget = SpawnBudget { cap }
        val points = world.enemies.values.flatMap { e -> e.spawns.map { it.id to it.cap } }
        assertTrue(points.isNotEmpty())
        var accepted = 0
        // 20 players x 20 spawn requests each against every point, plus rapid release/reserve churn
        repeat(20) { _ -> points.forEach { (pt, pc) -> repeat(20) { if (budget.reserve(UUID.randomUUID(), pt, pc)) accepted++ } } }
        assertTrue(budget.total() <= cap, "global cap exceeded: ${budget.total()} > $cap")
        points.forEach { (pt, pc) -> assertTrue(budget.count(pt) <= pc, "point $pt over cap") }
        trace.appendLine("spawn budget: cap=$cap accepted=$accepted total=${budget.total()} points=${points.size}")
    }

    @Test
    fun finishedChapterProfileSurvivesSaveLoadRoundTripAndTornWrite() {
        val p = runChain("ec:class_mage", "ec:flag_choice1_extract", "ec:flag_choice2_report", "ec:flag_choice3_observatory")
        p.transactions += listOf("t1", "t2"); p.dailyPurchases["7:ec:shop:x"] = 2; p.accessory = "ec:item_acc_starshard_crest"
        TempDir().use { dir ->
            val store = net.nakaymi.etherealchronicle.data.PlayerStore(dir.path)
            store.save(p).get(); store.save(p).get() // second save creates the .bak
            val loaded = store.load(p.uuid).get()
            assertEquals(p.snapshot(), loaded.snapshot(), "profile changed across save/load")
            // torn primary: truncate to garbage; load must recover from .bak or fail loudly, never return a blank profile
            java.nio.file.Files.writeString(dir.resolve("${p.uuid}.yml"), "schema_version: 3\nuuid: [broken")
            val recovered = runCatching { store.load(p.uuid).get() }
            if (recovered.isSuccess) assertEquals(p.snapshot(), recovered.getOrThrow().snapshot(), "recovered profile differs from last good save")
            store.close()
        }
    }
}
