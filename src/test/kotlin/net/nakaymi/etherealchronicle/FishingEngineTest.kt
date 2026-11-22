package net.nakaymi.etherealchronicle

import net.nakaymi.etherealchronicle.core.*
import net.nakaymi.etherealchronicle.data.ContentService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.UUID
import kotlin.random.Random

class FishingEngineTest {
    private val fish=FishSpeciesDefinition("ec:fish_test","試験魚","ec:item_fish_silver_sardine",Rarity.COMMON,setOf("PORT"),setOf("ec:area_lumina_port"),setOf("ANY"),setOf("ANY"),1,1,10.0,10.0,40.0,34.0,39.0,10,5,FishMinigame(1800,1.2,34.0,22.0,22.0,0.14,0.05),"")
    private val spot=FishingSpotDefinition("ec:spot_test","港","ec:area_lumina_port","ec_world",Position("ec_world",1.0,64.0,1.0),12.0,true,setOf("PORT"))
    @Test fun `fish selection respects habitat rod level time and weather`() {
        val engine=FishingEngine(LifeBalance(),Random(1))
        assertEquals(listOf(fish),engine.eligible(listOf(fish),spot,1,1,6000,"CLEAR"))
        assertTrue(engine.eligible(listOf(fish),spot,0,1,6000,"CLEAR").isEmpty())
        assertTrue(engine.eligible(listOf(fish.copy(minFishingLevel=2)),spot,1,1,6000,"CLEAR").isEmpty())
        assertTrue(engine.eligible(listOf(fish.copy(weather=setOf("RAIN"))),spot,1,1,6000,"CLEAR").isEmpty())
    }
    @Test fun `fish time eligibility uses the shared schedule boundaries and wraps day length`() {
        val engine=FishingEngine(LifeBalance(),Random(17))
        val cases=listOf(
            "DAY" to listOf(999L to false,1_000L to true,11_999L to true,12_000L to false),
            "DAWN_DUSK" to listOf(999L to true,1_000L to false,12_000L to true,12_999L to true,13_000L to false,22_999L to false,23_000L to true,23_999L to true),
            "NIGHT" to listOf(12_999L to false,13_000L to true,22_999L to true,23_000L to false),
        )
        cases.forEach { (window,boundaries) ->
            val candidate=fish.copy(timeWindows=setOf(window))
            boundaries.forEach { (time,expected) ->
                assertEquals(expected,engine.eligible(listOf(candidate),spot,1,1,time,"CLEAR").isNotEmpty(),"$window at $time")
                assertEquals(expected,engine.eligible(listOf(candidate),spot,1,1,time+WorldSchedule.DAY_LENGTH_TICKS,"CLEAR").isNotEmpty(),"wrapped $window at $time")
            }
        }
        assertEquals(engine.eligible(listOf(fish.copy(timeWindows=setOf("DAY"))),spot,1,1,1_000,"CLEAR"),
            engine.eligible(listOf(fish.copy(timeWindows=setOf("DAY"))),spot,1,1,-23_000,"CLEAR"))
    }
    @Test fun `state machine casts waits and requires an input during bite window`() {
        val engine=FishingEngine(LifeBalance(),Random(2)); val state=FishingState(fish,waitSeconds=0.2)
        assertEquals(FishingPhase.CASTING,engine.tick(state,0.2,ReelInput.IDLE))
        assertEquals(FishingPhase.WAITING,engine.tick(state,0.2,ReelInput.IDLE))
        assertEquals(FishingPhase.WAITING,engine.tick(state,0.1,ReelInput.IDLE))
        assertEquals(FishingPhase.BITE_ALERT,engine.tick(state,0.1,ReelInput.IDLE))
        assertEquals(FishingPhase.REELING,engine.tick(state,0.1,ReelInput.PULL))
        val escaped=FishingState(fish,phase=FishingPhase.BITE_ALERT,biteWindowLeft=0.1)
        assertEquals(FishingPhase.ESCAPED,engine.tick(escaped,0.1,ReelInput.IDLE))
        assertEquals(FishingEscapeCause.BITE_MISSED,escaped.escapeCause)
    }
    @Test fun `reel timeout has a distinct failure cause`() {
        val engine=FishingEngine(LifeBalance(reelingTimeoutSeconds=1),Random(3))
        val state=FishingState(fish,phase=FishingPhase.REELING,behaviorSeconds=10.0,tension=50.0)
        assertEquals(FishingPhase.ESCAPED,engine.tick(state,1.0,ReelInput.IDLE))
        assertEquals(FishingEscapeCause.TIMEOUT,state.escapeCause)
    }
    @Test fun `adaptive 300ms and 420ms pull rhythms can catch the beginner debug fish`() {
        val world=ContentService(Path.of("src/main/resources/content")).load().world
        val balance=world.lifeBalance
        val fish=world.fishSpecies.getValue("ec:fish_sunbeam_trout")
        val results=listOf(0.25,0.30,0.35,0.42).associateWith { cadence ->
            (1L..20L).count { seed ->
                val engine=FishingEngine(balance,Random(seed))
                val state=FishingState(fish,phase=FishingPhase.REELING,tensionStability=1.0)
                var nextClick=0.0; var pullUntil=0.0
                repeat((balance.reelingTimeoutSeconds*10).toInt()) { step ->
                    if(state.phase!=FishingPhase.REELING) return@repeat
                    val now=step*0.1
                    val input=when(state.behavior) {
                        FishBehavior.DIVE -> { pullUntil=now; nextClick=now+cadence; ReelInput.BRACE }
                        FishBehavior.THRASH -> { pullUntil=now; ReelInput.IDLE }
                        FishBehavior.CALM, FishBehavior.TELEGRAPH -> {
                            if(state.tension<65.0 && now>=nextClick) { nextClick=now+cadence; pullUntil=now+0.25 }
                            if(now<pullUntil) ReelInput.PULL else ReelInput.IDLE
                        }
                    }
                    engine.tick(state,0.1,input)
                }
                state.phase==FishingPhase.CAUGHT
            }
        }
        java.io.File("build/fishing-cadence.txt").writeText(results.entries.joinToString { "${(it.key*1000).toInt()}ms=${it.value}/20" })
        assertTrue(results.values.all { it>=16 },"catch results by cadence: $results")
    }
    @Test fun `catch records maximum size crown and score`() {
        val engine=FishingEngine(LifeBalance(),Random(8)); val state=FishingState(fish,phase=FishingPhase.CAUGHT)
        val catch=engine.catch(state,0.0,1)
        assertTrue(catch.sizeCm in fish.minCm..fish.maxCm)
        val p=PlayerProfile(UUID.randomUUID()); engine.record(p,catch); engine.record(p,catch)
        assertEquals(2,p.fishRecords.getValue(fish.id).count)
        assertEquals(catch.sizeCm,p.fishRecords.getValue(fish.id).maxCm)
        assertTrue(engine.score(p)>=102)
    }
    @Test fun `macro detector flags unnaturally even response and pull timing`() {
        val detector=FishingMacroDetector()
        repeat(10) { i -> val base=i*1000L; detector.bite(base); detector.pull(base+500); detector.pull(base+700) }
        assertTrue(detector.suspicious())
        val human=FishingMacroDetector()
        repeat(10) { i -> val base=i*1000L; human.bite(base); human.pull(base+200+i*70L); human.pull(base+350+i*40L) }
        assertFalse(human.suspicious())
    }
    @Test fun `macro detector retains reaction timing across bites and records cast cadence`() {
        val detector=FishingMacroDetector(); var at=0L
        repeat(10) { detector.cast(at); detector.bite(at+1000); detector.pull(at+1300); at+=5000 }
        assertTrue(detector.suspicious())
        val human=FishingMacroDetector(); var time=0L
        val reactions=listOf(110L,420L,230L,870L,330L,690L,150L,520L,980L,280L)
        reactions.forEachIndexed { i,reaction -> human.cast(time); human.bite(time+400); human.pull(time+400+reaction); time+=3500+i*211L }
        assertFalse(human.suspicious())
    }
    @Test fun `transaction rollback restores persisted life profession mutations`() {
        val before=PlayerProfile(UUID.randomUUID())
        val changed=before.snapshot()
        changed.lifeSkills[LifeSkillKind.FISHING]=LifeSkillProgress(3,10)
        changed.fishRecords[fish.id]=FishRecord(1,30.0,Crown.NONE)
        changed.gatheringRecords["ec:node_test"]=4
        changed.gatheringReadyAt["ec:node_test/site"]=1234
        changed.fishingBait="ec:item_bait_aether_lure"
        before.restore(changed)
        val rollback=before.snapshot()
        rollback.lifeSkills[LifeSkillKind.FISHING]=LifeSkillProgress()
        rollback.fishRecords.clear(); rollback.gatheringRecords.clear(); rollback.gatheringReadyAt.clear(); rollback.fishingBait="AUTO"
        before.restore(rollback)
        assertEquals(LifeSkillProgress(),before.lifeSkills.getValue(LifeSkillKind.FISHING))
        assertTrue(before.fishRecords.isEmpty() && before.gatheringRecords.isEmpty() && before.gatheringReadyAt.isEmpty())
        assertEquals("AUTO",before.fishingBait)
    }
}
