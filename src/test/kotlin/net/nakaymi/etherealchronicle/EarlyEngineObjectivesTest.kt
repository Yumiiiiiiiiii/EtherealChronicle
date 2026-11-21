package net.nakaymi.etherealchronicle

import net.nakaymi.etherealchronicle.core.*
import net.nakaymi.etherealchronicle.data.ContentService
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*

class EarlyEngineObjectivesTest {
    private fun bundled()=ContentService(Path.of("src/main/resources/content")).load()

    private fun extendedSnapshot():net.nakaymi.etherealchronicle.data.ContentSnapshot {
        val base=bundled()
        fun quest(id:String,kind:String,target:String,amount:Int)=mapOf(
            "schema_version" to 1,"id" to id,"type" to "quest","name" to id,
            "objectives" to listOf(mapOf("id" to "goal","kind" to kind,"target" to target,"amount" to amount)),
            "rewards" to emptyMap<String,Any?>(),"auto_complete" to false,
        )
        fun title(id:String,condition:Map<String,Any?>)=mapOf(
            "schema_version" to 1,"id" to id,"type" to "title","name" to id,"unlock_flag" to "ec:flag_${id.substringAfter(':')}",
            "advancement" to mapOf("icon" to "BOOK","condition" to condition),
        )
        val extra=mapOf(
            "ec:quest_test_catch" to quest("ec:quest_test_catch","CATCH_FISH","ec:fish_harbor_goby",2),
            "ec:quest_test_gather" to quest("ec:quest_test_gather","GATHER_NODE","HERB",1),
            "ec:quest_test_enhance" to quest("ec:quest_test_enhance","ENHANCE_GEAR","3",2),
            "ec:title_test_fish" to title("ec:title_test_fish",mapOf("type" to "FISH_SPECIES_COUNT","count" to 2)),
            "ec:title_test_life" to title("ec:title_test_life",mapOf("type" to "LIFE_LEVEL","target" to "FISHING","count" to 3)),
            "ec:title_test_enhance" to title("ec:title_test_enhance",mapOf("type" to "ENHANCE_LEVEL","count" to 3)),
            "ec:title_test_rank" to title("ec:title_test_rank",mapOf("type" to "CHALLENGE_RANK","target" to "ec:dungeon_novice_arena","rank" to "A")),
        )
        return base.copy(documents=base.documents+extra)
    }

    @Test fun `new quest objectives parse validate progress and complete without changing collect semantics`() {
        val content=extendedSnapshot(); val world=content.world
        assertEquals(ObjectiveKind.CATCH_FISH,world.quests.getValue("ec:quest_test_catch").objectives.single().kind)
        assertEquals(ObjectiveKind.GATHER_NODE,world.quests.getValue("ec:quest_test_gather").objectives.single().kind)
        assertEquals(ObjectiveKind.ENHANCE_GEAR,world.quests.getValue("ec:quest_test_enhance").objectives.single().kind)
        var now=100L; val engine=QuestEngine({content},Clock { now++ }); val p=PlayerProfile(UUID.randomUUID()); val bag=MemoryInventory()
        listOf("ec:quest_test_catch","ec:quest_test_gather","ec:quest_test_enhance").forEach { engine.start(p,it) }
        engine.event(p,ObjectiveKind.CATCH_FISH,"ec:fish_harbor_goby",1,bag)
        assertFalse(engine.ready(p,"ec:quest_test_catch",bag))
        engine.event(p,ObjectiveKind.CATCH_FISH,"ec:fish_harbor_goby",1,bag)
        engine.event(p,ObjectiveKind.GATHER_NODE,"ec:node_ashwood_dew_moss",1,bag,alsoTargets=setOf("HERB"))
        engine.event(p,ObjectiveKind.ENHANCE_GEAR,"2",1,bag)
        assertEquals(0,p.quests.getValue("ec:quest_test_enhance").objectives.getOrDefault("goal",0),"must ignore enhancements below the requested tier")
        engine.event(p,ObjectiveKind.ENHANCE_GEAR,"3",1,bag)
        engine.event(p,ObjectiveKind.ENHANCE_GEAR,"4",1,bag)
        listOf("ec:quest_test_catch","ec:quest_test_gather","ec:quest_test_enhance").forEach { id ->
            assertTrue(engine.ready(p,id,bag),"$id should be ready")
            engine.complete(p,id,bag)
            assertTrue(p.quests.getValue(id).completed)
        }
    }

    @Test fun `new objective and title target types are schema validated`() {
        val base=bundled()
        val badFish=mapOf("schema_version" to 1,"id" to "ec:quest_bad_fish","type" to "quest","name" to "bad",
            "objectives" to listOf(mapOf("id" to "x","kind" to "CATCH_FISH","target" to "ec:item_fish_harbor_goby","amount" to 1)),"rewards" to emptyMap<String,Any?>())
        val wrongRank=mapOf("schema_version" to 1,"id" to "ec:title_bad_rank","type" to "title","name" to "bad","unlock_flag" to "ec:flag_bad_rank",
            "advancement" to mapOf("icon" to "BOOK","condition" to mapOf("type" to "CHALLENGE_RANK","target" to "ec:quest_early_t01_arena_trial","rank" to "S")))
        val message=assertFails { base.copy(documents=base.documents+mapOf("ec:quest_bad_fish" to badFish)).world }
        assertContains(message.message.orEmpty(),"CATCH_FISH")
        val rankMessage=assertFails { base.copy(documents=base.documents+mapOf("ec:title_bad_rank" to wrongRank)).world }
        assertContains(rankMessage.message.orEmpty(),"ダンジョン")
    }

    @Test fun `new title conditions unlock from fish species life level enhancement and challenge rank`() {
        val content=extendedSnapshot(); val titles=content.world.titles
        val p=PlayerProfile(UUID.randomUUID())
        assertFalse(titles.getValue("ec:title_test_fish").unlocked(p))
        p.fishRecords["ec:fish_harbor_goby"]=FishRecord(1,25.0,Crown.NONE)
        p.fishRecords["ec:fish_moss_loach"]=FishRecord(1,32.0,Crown.NONE)
        p.lifeSkills[LifeSkillKind.FISHING]=LifeSkillProgress(3,0)
        p.bestEnhancementLevel=3
        p.challengeRecords["ec:dungeon_novice_arena"]=ChallengeRecord(200,"B")
        assertFalse(titles.getValue("ec:title_test_rank").unlocked(p),"rank B must not meet rank A")
        p.challengeRecords["ec:dungeon_novice_arena"]!!.bestRank="S"
        val unlocked=p.syncTitleUnlockFlags(titles)
        listOf("ec:title_test_fish","ec:title_test_life","ec:title_test_enhance","ec:title_test_rank").forEach { id ->
            val title=titles.getValue(id)
            assertTrue(title.unlocked(p),"$id should unlock")
            assertTrue(title.unlockFlag in unlocked)
            assertTrue(p.equipTitle(id,titles))
        }
        assertTrue(p.syncTitleUnlockFlags(titles).isEmpty(),"already unlocked flags must remain idempotent")
    }
}
