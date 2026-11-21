package net.nakaymi.etherealchronicle.qa

import net.nakaymi.etherealchronicle.core.*
import net.nakaymi.etherealchronicle.data.ContentService
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Path
import java.util.UUID
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Fishing/gathering invariants over the shipped content: 100-angler tick load, macro detector, record persistence, no buy->sell arbitrage on life items. */
class FishingInvariantsTest {
    private val snapshot by lazy { ContentService(Path.of("src/main/resources/content")).load() }
    private val world get() = snapshot.world

    @Test
    fun hundredActiveAnglersTickCostIsTiny() {
        val engine = FishingEngine(world.lifeBalance, Random(7)); val species = world.fishSpecies.values.toList()
        assertTrue(species.isNotEmpty())
        val states = List(100) { FishingState(species[it % species.size]) }
        val inputs = ReelInput.entries
        val t0 = System.nanoTime(); var ticks = 0
        repeat(600) { step -> states.forEachIndexed { i, s -> if (s.phase !in setOf(FishingPhase.CAUGHT, FishingPhase.ESCAPED, FishingPhase.CANCELLED)) { engine.tick(s, 0.1, inputs[(step + i) % inputs.size]); ticks++ } else { s.phase = FishingPhase.WAITING } } }
        val ms = (System.nanoTime() - t0) / 1e6
        File("build").mkdirs(); File("build/qa-fishing-perf.txt").writeText("100 anglers x 600 ticks (=60 s of game time at 10 Hz): ${"%.1f".format(ms)} ms total, ${"%.4f".format(ms / 600)} ms per 10 Hz step, $ticks engine ticks")
        assertTrue(ms / 600 < 5.0, "engine cost per step ${ms / 600} ms for 100 anglers")
    }

    @Test
    fun macroDetectorFlagsMetronomicAndSinglePullBotsButNotHumans() {
        // pulls per bite: the detector measures reaction time (bite->first pull) and the spacing between consecutive pulls
        fun run(reaction: (Int) -> Long, spacing: (Int, Int) -> Long, pulls: Int): Boolean {
            val d = FishingMacroDetector(); var t = 0L
            repeat(12) { c -> d.bite(t); var at = t + reaction(c); d.pull(at); repeat(pulls - 1) { k -> at += spacing(c, k); d.pull(at) }; t = at + 8_000 }
            return d.suspicious()
        }
        assertTrue(run({ 300 }, { _, _ -> 400 }, 4), "identical reaction time and pull spacing must be suspicious")
        val rnd = Random(3)
        assertTrue(!run({ 250L + rnd.nextInt(0, 700) }, { _, _ -> 200L + rnd.nextInt(0, 500) }, 4), "a varied human rhythm was flagged")
        val singlePullBot = run({ 300 }, { _, _ -> 0 }, 1)
        assertTrue(singlePullBot,"constant reaction across one pull per bite must be suspicious")

        val periodicCastBot=FishingMacroDetector(); var t=0L
        repeat(12) { i -> periodicCastBot.cast(t); periodicCastBot.bite(t+1000); periodicCastBot.pull(t+1300+(i%2)*35); t+=5000+(i%2) }
        assertTrue(periodicCastBot.suspicious(8,15.0,10.0),"stable cast cadence plus unusually fixed reactions must be suspicious")
        File("build").mkdirs(); File("build/qa-macro.txt").writeText("single-pull constant-reaction bot flagged=$singlePullBot; periodic-cast bot flagged=${periodicCastBot.suspicious(8,15.0,10.0)}")
    }

    @Test
    fun fishRecordsKeepPersonalBestSizeAndCrownAcrossSaveLoad() {
        val engine = FishingEngine(world.lifeBalance, Random(11)); val sp = world.fishSpecies.values.first()
        val p = PlayerProfile(UUID.randomUUID(), classId = "ec:class_warrior")
        engine.record(p, FishCatch(sp, 50.0, Crown.NONE)); engine.record(p, FishCatch(sp, 80.0, Crown.SILVER)); engine.record(p, FishCatch(sp, 60.0, Crown.NONE))
        val rec = p.fishRecords.getValue(sp.id)
        assertEquals(3, rec.count); assertEquals(80.0, rec.maxCm, "smaller later catch must not lower the record"); assertEquals(Crown.SILVER, rec.crown, "crown must not be downgraded")
        engine.record(p, FishCatch(sp, 90.0, Crown.GOLD)); assertEquals(Crown.GOLD, p.fishRecords.getValue(sp.id).crown)
        TempDir().use { dir ->
            val store = net.nakaymi.etherealchronicle.data.PlayerStore(dir.path); store.save(p).get()
            val back = store.load(p.uuid).get(); store.close()
            assertEquals(p.fishRecords, back.fishRecords, "fish records changed across save/load")
        }
    }

    @Test
    fun noBuyThenSellArbitrageOnAnyShopAfterLifeContent() {
        val tuning = world.tuning; val bad = mutableListOf<String>()
        world.shops.values.forEach { s -> if (!s.buyback) return@forEach
            s.entries.forEach { e ->
                val props = world.itemProperties[e.item] ?: return@forEach
                if (props.bound || props.questItem || props.price <= 0 || e.tokens.isNotEmpty() || e.price <= 0) return@forEach
                val gain = kotlin.math.floor(props.price * tuning.sellRatio).toLong() * e.amount
                val cost = kotlin.math.ceil(e.price.toDouble() * (1 - s.discount)).toLong()
                if (gain >= cost) bad += "${s.id}/${e.id}: sell $gain >= buy $cost"
            }
        }
        assertTrue(bad.isEmpty(), bad.joinToString("\n"))
        // selling caught fish / gathered materials can never exceed the configured cap per transaction
        assertTrue(tuning.sellRatio < 0.5, "sell ratio ${tuning.sellRatio} allows break-even trades")
    }
}
