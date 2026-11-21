package net.nakaymi.etherealchronicle.qa

import net.nakaymi.etherealchronicle.core.*
import net.nakaymi.etherealchronicle.data.ContentService
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** Chapter 1 -> Chapter 2 over the shipped content for every branch combination (4A/4B x 5A/5B); asserts branch-specific rewards. */
class Chapter2ChainTest {
    private val snapshot by lazy { ContentService(Path.of("src/main/resources/content")).load() }
    private val ids = (listOf("m01_awakening", "m02_first_hunt", "m03_ashwood_core", "m04_road_to_mine", "m05_mine_betrayal", "m06_iron_grinder", "m07_stargazer_oath", "m08_void_warden") +
        listOf("ch2_m01_northward", "ch2_m02_frost_beacons", "ch2_m03_frozen_conduit", "ch2_m04_cavern_infiltration", "ch2_m05_frost_colossus", "ch2_m06_citadel_gate", "ch2_m07_cryo_archon")).map { "ec:quest_$it" }

    private fun run(c4: String, c5: String): Pair<PlayerProfile, MemoryInventory> {
        val game = GameService({ snapshot }, SkillEngine(Clock { 0L }, snapshot.balance))
        val p = PlayerProfile(UUID.nameUUIDFromBytes("ch2-$c4-$c5".toByteArray())); val inv = MemoryInventory(capacity = 4000)
        game.chooseClass(p, "ec:class_mage")
        for (id in ids) {
            val q = snapshot.world.quests.getValue(id)
            var guard = 0
            while (p.level < q.condition.minLevel) {
                val e = snapshot.world.enemies.values.filter { !it.boss && it.level <= p.level + 4 }.maxByOrNull { snapshot.documents.getValue(it.id)["xp"].toString().toDouble() } ?: fail("no grind target")
                game.kill(p, e.id, inv, rollDrops = false); if (++guard > 5000) fail("grind did not converge at level ${p.level}")
            }
            when (id) { "ec:quest_m03_ashwood_core" -> p.flags += "ec:flag_choice1_extract"; "ec:quest_m05_mine_betrayal" -> p.flags += "ec:flag_choice2_report"; "ec:quest_m08_void_warden" -> p.flags += "ec:flag_choice3_observatory"
                "ec:quest_ch2_m03_frozen_conduit" -> p.flags += c4; "ec:quest_ch2_m07_cryo_archon" -> p.flags += c5 }
            if (id !in p.quests) game.startQuest(p, id)
            for (o in q.objectives) when (o.kind) {
                ObjectiveKind.KILL -> repeat(o.amount) { game.kill(p, o.target, inv, rollDrops = false) }
                ObjectiveKind.COLLECT -> assertTrue(inv.exchange(emptyMap(), mapOf(o.target to o.amount)))
                else -> game.quests.event(p, o.kind, o.target, 1, inv)
            }
            assertTrue(game.quests.ready(p, id, inv), "$id not ready: ${p.quests[id]}")
            game.completeQuest(p, id, inv)
            assertTrue(runCatching { game.completeQuest(p, id, inv) }.isFailure, "$id completed twice")
        }
        return p to inv
    }

    @Test
    fun chapterTwoAllBranches() {
        val shards = mutableMapOf<Pair<String, String>, Int>()
        for (c4 in listOf("ec:flag_ch2_choice4_stabilize", "ec:flag_ch2_choice4_overload")) for (c5 in listOf("ec:flag_ch2_choice5_anchor", "ec:flag_ch2_choice5_resonance")) {
            val (p, inv) = run(c4, c5)
            assertTrue("ec:flag_ch2_completed" in p.flags && "ec:flag_ch1_completed" in p.flags, "completion flags missing for $c4/$c5")
            assertTrue("ec:flag_ch2_citadel_unlocked" in p.flags, "citadel flag missing")
            assertTrue(p.level <= snapshot.balance.levelCap)
            val a = if (c4.endsWith("stabilize")) "ec:item_acc_glacier_ward_pendant" else "ec:item_acc_frostbreaker_signet"
            val b = if (c4.endsWith("stabilize")) "ec:item_acc_frostbreaker_signet" else "ec:item_acc_glacier_ward_pendant"
            assertEquals(1, inv.count(a), "$c4: expected ${a}"); assertEquals(0, inv.count(b), "$c4: got the other branch's accessory")
            // m07 grants 1 core essence to everyone (+1 for "anchor") and 10 shards for "resonance"
            if (c5.endsWith("anchor")) { assertEquals(2, inv.count("ec:item_mat_glacial_core_essence"), "anchor essence"); shards[c4 to "anchor"] = inv.count("ec:item_mat_glacial_shard") }
            else { assertEquals(1, inv.count("ec:item_mat_glacial_core_essence"), "resonance essence"); shards[c4 to "resonance"] = inv.count("ec:item_mat_glacial_shard") }
        }
        listOf("ec:flag_ch2_choice4_stabilize", "ec:flag_ch2_choice4_overload").forEach { c4 -> assertEquals(shards.getValue(c4 to "anchor") + 10, shards.getValue(c4 to "resonance"), "resonance must add exactly 10 shards") }
    }

    @Test
    fun chapterTwoContentReachability() {
        val drops = snapshot.drops.all().flatMap { t -> t.entries.map { it.item } }.toSet()
        val rewarded = snapshot.world.quests.values.flatMap { q -> q.rewards.items.map { it.item } }.toSet()
        val sold = snapshot.world.shops.values.flatMap { s -> s.entries.map { it.item } }.toSet()
        val bad = snapshot.world.quests.values.filter { it.id.contains("ch2") }.flatMap { q -> q.objectives.filter { it.kind == ObjectiveKind.COLLECT && it.target !in drops && it.target !in rewarded && it.target !in sold }.map { "${q.id}: ${it.target} unobtainable" } }
        assertTrue(bad.isEmpty(), bad.joinToString("\n"))
    }
}
