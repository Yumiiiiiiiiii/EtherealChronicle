package net.nakaymi.etherealchronicle

import net.nakaymi.etherealchronicle.core.*
import net.nakaymi.etherealchronicle.data.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*

class AdventureServicesTest {
    private fun base() = ContentService(Path.of("src/test/resources/foundation-content")).load()
    private fun map(vararg pairs: Pair<String,Any?>) = mapOf(*pairs)
    private fun chapterContent(): ContentSnapshot {
        val b=base()
        val quest=map("id" to "ec:q","type" to "quest","name" to "試験", "condition" to map("has_flags" to listOf("ec:ready")),
            "objectives" to listOf(map("id" to "kill","kind" to "KILL","target" to "ec:forest_slime","amount" to 2),map("id" to "collect","kind" to "COLLECT","target" to "ec:field_ration","amount" to 2)),
            "rewards" to map("xp" to 25,"currency" to 20,"set_flags" to listOf("ec:done")),"repeatable" to true,"repeat_cooldown_ms" to 1000,"auto_complete" to true)
        val dialogue=map("id" to "ec:d","type" to "dialogue","start" to "root","nodes" to map(
            "root" to map("text" to "通常", "branches" to listOf(map("condition" to map("has_flags" to listOf("ec:done")),"next" to "done")),
                "choices" to listOf(map("id" to "yes","label" to "はい","condition" to map("has_flags" to listOf("ec:ready")),"set_flags" to listOf("ec:choice")))),
            "done" to map("text" to "達成")))
        val shop=map("id" to "ec:s","type" to "shop","name" to "店","discount_flag" to "ec:discount","discount" to 0.1,
            "entries" to listOf(map("id" to "bread","item" to "ec:field_ration","amount" to 1,"price" to 100,"daily_limit" to 2),
                map("id" to "token","item" to "ec:warrior_starter","amount" to 1,"tokens" to listOf(map("item" to "ec:field_ration","amount" to 2)))))
        return b.copy(documents=b.documents+mapOf("ec:q" to quest,"ec:d" to dialogue,"ec:s" to shop,"ec:field_ration" to (b.documents.getValue("ec:field_ration")+("price" to 100))))
    }
    @Test fun `quest prerequisites sequence collection consumption repeat cooldown and reward once`() {
        val c=chapterContent(); var now=100L; val q=QuestEngine({ c },Clock { now }); val p=PlayerProfile(UUID.randomUUID())
        val inventory=MemoryInventory(mutableMapOf("ec:field_ration" to 2))
        assertFails { q.start(p,"ec:q") }; p.flags.add("ec:ready"); q.start(p,"ec:q")
        q.event(p,ObjectiveKind.KILL,"ec:forest_slime",1,inventory,allowAutoComplete=false); assertFalse(q.ready(p,"ec:q",inventory))
        q.event(p,ObjectiveKind.KILL,"ec:forest_slime",1,inventory,allowAutoComplete=false); assertTrue(q.ready(p,"ec:q",inventory))
        q.complete(p,"ec:q",inventory); assertEquals(0,inventory.count("ec:field_ration")); assertEquals(20,p.currency); assertTrue("ec:done" in p.flags)
        assertFails { q.complete(p,"ec:q",inventory) }; assertFails { q.start(p,"ec:q") }
        now+=1000; q.start(p,"ec:q"); assertFalse(p.quests.getValue("ec:q").completed)
    }
    @Test fun `periodic quest precheck skips unchanged profiles and detects ready automatic rewards`() {
        val content=chapterContent()
        val quests=QuestEngine({ content },Clock { 1_000 })
        val profile=PlayerProfile(UUID.randomUUID())
        val inventory=MemoryInventory(mutableMapOf("ec:field_ration" to 2))

        assertFalse(quests.needsPeriodicUpdate(profile,inventory,emptySet()))
        profile.flags.add("ec:ready")
        quests.start(profile,"ec:q")
        assertFalse(quests.mayNeedPeriodicUpdate(profile,emptySet()))
        assertFalse(quests.needsPeriodicUpdate(profile,inventory,emptySet()))
        quests.event(profile,ObjectiveKind.KILL,"ec:forest_slime",2,inventory,allowAutoComplete=false)

        assertTrue(quests.mayNeedPeriodicUpdate(profile,emptySet()))
        assertTrue(quests.needsPeriodicUpdate(profile,inventory,emptySet()))
    }
    @Test fun `dialogue conditions filter choices and redirect flags without mutating state`() {
        val c=chapterContent(); val d=DialogueEngine { c }; val p=PlayerProfile(UUID.randomUUID())
        assertTrue(d.node(p,"ec:d").second.choices.isEmpty())
        p.flags.add("ec:ready"); assertEquals("yes",d.choice(p,"ec:d","root","yes").id)
        assertFalse("ec:choice" in p.flags)
        p.flags.add("ec:done"); assertEquals("done",d.node(p,"ec:d").first)
        assertFails { d.choice(p,"ec:d","root","yes") }
    }
    @Test fun `trades validate capacity tokens limits and duplicate ids before commit`() {
        val c=chapterContent(); val trade=TradeEngine { c }; val p=PlayerProfile(UUID.randomUUID(),currency=1000)
        val full=MemoryInventory(capacity=0)
        assertFails { trade.buy(p,"ec:s","bread",full,"a",1) }; assertEquals(1000,p.currency)
        val bag=MemoryInventory(capacity=10); p.flags.add("ec:discount")
        val a=trade.buy(p,"ec:s","bread",bag,"a",1); assertEquals(910,a.currencyAfter)
        assertTrue(bag.exchange(a.remove,a.add)); trade.commit(p,a)
        assertFails { trade.commit(p,a) }; assertFails { trade.buy(p,"ec:s","bread",bag,"a",1) }
        val b=trade.buy(p,"ec:s","bread",bag,"b",1); bag.exchange(b.remove,b.add); trade.commit(p,b)
        assertFails { trade.buy(p,"ec:s","bread",bag,"c",1) }
        val token=trade.buy(p,"ec:s","token",bag,"t",1); assertTrue(bag.exchange(token.remove,token.add)); trade.commit(p,token)
        assertEquals(0,bag.count("ec:field_ration")); assertEquals(1,bag.count("ec:warrior_starter"))
        val sellBag=MemoryInventory(mutableMapOf("ec:field_ration" to 1)); val sell=trade.sell(p,"ec:s","ec:field_ration",1,sellBag,"sell")
        assertEquals(p.currency+30,sell.currencyAfter)
        assertFails { trade.sell(p,"ec:s","ec:field_ration",2,sellBag,"x") }
    }
    @Test fun `party invitations cap expiry exit and xp conserve total`() {
        var now=0L; val party=PartyService({ 2 },Clock { now }); val a=UUID.randomUUID(); val b=UUID.randomUUID(); val c=UUID.randomUUID()
        party.invite(a,b); party.accept(b); assertEquals(setOf(a,b),party.members(a))
        assertFails { party.invite(a,c) }; assertEquals(101,party.splitXp(101,setOf(a,b)).values.sum())
        assertEquals(party.lootRecipient(setOf(a,b),0),party.lootRecipient(setOf(a,b),2))
        party.leave(a); assertEquals(setOf(b),party.members(b))
        party.invite(a,c); now=60_001; assertFails { party.accept(c) }
    }
    @Test fun `spawn accounting enforces global and per point caps and releases slots`() {
        val budget=SpawnBudget { 2 }; val a=UUID.randomUUID(); val b=UUID.randomUUID(); val c=UUID.randomUUID()
        assertTrue(budget.reserve(a,"a",1)); assertFalse(budget.reserve(b,"a",1)); assertTrue(budget.reserve(b,"b",1)); assertFalse(budget.reserve(c,"c",1))
        budget.release(a); assertTrue(budget.reserve(c,"a",1)); assertEquals(2,budget.total())
    }
    @Test fun `boss scaling phases and enrage use virtual hp`() {
        val d=base().world.enemies.getValue("ec:forest_slime").copy(hp=34000.0,boss=true,phases=listOf(BossPhase("one",1.0,1.0,1.0),BossPhase("two",0.5,1.5,0.8)),enrageTicks=100,enrageMultiplier=2.0)
        val boss=BossState(d,4,0); assertEquals(110500.0,boss.maximumHp)
        assertEquals(1.12,boss.damageScale(0),0.0001); boss.hp=boss.maximumHp*0.4
        assertEquals("two",boss.phase()!!.id); assertEquals(3.36,boss.damageScale(100),0.0001)
    }
}

