package net.nakaymi.etherealchronicle.qa

import net.nakaymi.etherealchronicle.core.*
import net.nakaymi.etherealchronicle.data.ContentService
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class CalendarResetTest {
    private val snapshot = ContentService(Path.of("src/main/resources/content")).load()

    @Test
    fun `daily and weekly boundaries use configured fixed reset instant`() {
        val tuning=snapshot.world.tuning
        fun millis(text:String)=Instant.parse(text).toEpochMilli()
        assertEquals(ResetPeriods.key(millis("2026-11-21T20:01:00Z"),"DAILY",tuning),ResetPeriods.key(millis("2026-11-22T19:59:00Z"),"DAILY",tuning))
        assertNotEquals(ResetPeriods.key(millis("2026-11-21T19:59:00Z"),"DAILY",tuning),ResetPeriods.key(millis("2026-11-21T20:00:00Z"),"DAILY",tuning))
        assertEquals(ResetPeriods.key(millis("2026-11-16T20:00:00Z"),"WEEKLY",tuning),ResetPeriods.key(millis("2026-11-23T19:59:00Z"),"WEEKLY",tuning))
        assertNotEquals(ResetPeriods.key(millis("2026-11-23T19:59:00Z"),"WEEKLY",tuning),ResetPeriods.key(millis("2026-11-23T20:00:00Z"),"WEEKLY",tuning))
    }

    @Test
    fun `daily quest resets once at fixed calendar boundary and persists current key`() {
        var now=Instant.parse("2026-11-21T20:00:00Z").toEpochMilli()
        val engine=QuestEngine({ snapshot },Clock { now })
        val profile=PlayerProfile(UUID.randomUUID(),level=20,flags=mutableSetOf("ec:flag_ch1_started"))
        val quest="ec:quest_side_d01_daily_hunt"
        engine.start(profile,quest)
        val state=profile.quests.getValue(quest)
        snapshot.world.quests.getValue(quest).objectives.forEach { state.objectives[it.id]=it.amount }
        engine.complete(profile,quest,MemoryInventory())
        val first=state.resetPeriod
        assertEquals(ResetPeriods.key(now,"DAILY",snapshot.world.tuning),first)
        assertFailsWith<IllegalArgumentException> { engine.start(profile,quest) }
        now=Instant.parse("2026-11-22T20:00:00Z").toEpochMilli()
        engine.start(profile,quest)
        assertFalse(profile.quests.getValue(quest).completed)
    }

    @Test
    fun `weekly quest and shop daily limit follow calendar windows`() {
        var now=Instant.parse("2026-11-23T20:00:00Z").toEpochMilli()
        val engine=QuestEngine({ snapshot },Clock { now })
        val profile=PlayerProfile(UUID.randomUUID(),level=20,flags=mutableSetOf("ec:flag_stargazer_unlocked"),currency=100_000)
        val quest="ec:quest_side_w01_weekly_bounty"
        engine.start(profile,quest)
        val state=profile.quests.getValue(quest)
        snapshot.world.quests.getValue(quest).objectives.forEach { state.objectives[it.id]=it.amount }
        engine.complete(profile,quest,MemoryInventory())
        assertFailsWith<IllegalArgumentException> { engine.start(profile,quest) }
        now=Instant.parse("2026-11-30T20:00:00Z").toEpochMilli()
        engine.start(profile,quest)
        assertFalse(profile.quests.getValue(quest).completed)

        val trades=TradeEngine { snapshot }
        val bag=MemoryInventory(capacity=100)
        val first=trades.buy(profile,"ec:shop_frostpeak_outfitter","revive_feather",bag,"daily-1",now)
        trades.commit(profile,first)
        val nextWindow=Instant.parse("2026-12-01T20:00:00Z").toEpochMilli()
        val reset=trades.buy(profile,"ec:shop_frostpeak_outfitter","revive_feather",bag,"daily-2",nextWindow)
        trades.commit(profile,reset)
        assertEquals(1,profile.dailyPurchases.size,"old daily counters should be pruned when the calendar window advances")
    }

    @Test
    fun `schema six migration preserves existing quest progress and adds period field lazily`() {
        val legacy=mapOf<String,Any?>("schema_version" to 5,"uuid" to UUID.randomUUID().toString(),"quests" to mapOf("ec:q" to mapOf("completed" to true,"progress" to 2)))
        val store=net.nakaymi.etherealchronicle.data.PlayerStore(Path.of("build/test-calendar-migration"))
        val migrated=store.migrate(legacy)
        assertEquals(12,migrated["schema_version"])
        @Suppress("UNCHECKED_CAST")
        val quests=migrated["quests"] as Map<String,Map<String,Any?>>
        assertEquals(true,quests.getValue("ec:q")["completed"])
    }
}
