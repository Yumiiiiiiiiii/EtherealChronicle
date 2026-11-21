package net.nakaymi.etherealchronicle

import net.nakaymi.etherealchronicle.core.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
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
    @Test fun `state machine casts waits and requires an input during bite window`() {
        val engine=FishingEngine(LifeBalance(),Random(2)); val state=FishingState(fish,waitSeconds=0.2)
        assertEquals(FishingPhase.CASTING,engine.tick(state,0.2,ReelInput.IDLE))
        assertEquals(FishingPhase.WAITING,engine.tick(state,0.2,ReelInput.IDLE))
        assertEquals(FishingPhase.WAITING,engine.tick(state,0.1,ReelInput.IDLE))
        assertEquals(FishingPhase.BITE_ALERT,engine.tick(state,0.1,ReelInput.IDLE))
        assertEquals(FishingPhase.REELING,engine.tick(state,0.1,ReelInput.PULL))
        val escaped=FishingState(fish,phase=FishingPhase.BITE_ALERT,biteWindowLeft=0.1)
        assertEquals(FishingPhase.ESCAPED,engine.tick(escaped,0.1,ReelInput.IDLE))
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
