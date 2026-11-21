package net.nakaymi.etherealchronicle.qa

import net.nakaymi.etherealchronicle.core.*
import net.nakaymi.etherealchronicle.data.ContentService
import net.nakaymi.etherealchronicle.data.strings
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** All side quests over shipped content (every class x branch variant, fixpoint over prerequisites) and DAILY/WEEKLY repeat boundaries with a fake clock. */
class SideQuestAndResetTest {
    private val snapshot by lazy { ContentService(Path.of("src/main/resources/content")).load() }
    private val world get() = snapshot.world

    private fun isMain(id: String) = Regex("ec:quest_(ch2_)?m[0-9][0-9]_.*").matches(id)

    private fun mainDone(p: PlayerProfile) {
        world.quests.keys.filter { isMain(it) }.forEach { p.quests[it] = QuestProgress(completed = true, completedAt = 1) }
    }

    @Test
    fun everySideQuestIsCompletableInSomeClassAndBranchVariant() {
        val sides = world.quests.keys.filter { !isMain(it) }
        assertTrue(sides.size >= 30, "expected >= 30 side/daily/weekly quests, found ${sides.size}")
        val doneAnywhere = mutableSetOf<String>(); val report = StringBuilder()
        val declared = world.chapters.values.flatMap { it.strings("flags") }.toSet()
        for (cls in snapshot.classes.all().map { it.id }) for (variant in 0..1) {
            val game = GameService({ snapshot }, SkillEngine(Clock { 0L }, snapshot.balance))
            val now = 1_000_000_000L; var t = now
            val engine = QuestEngine({ snapshot }, Clock { t })
            val p = PlayerProfile(UUID.nameUUIDFromBytes("side-$cls-$variant".toByteArray()), level = 20)
            p.classId = cls; mainDone(p)
            // all declared chapter flags except the mutually exclusive branch pairs: pick variant 0 -> first option, 1 -> second option
            val exclusive = listOf("choice1_purify" to "choice1_extract", "choice2_forgive" to "choice2_report", "choice3_guild" to "choice3_observatory", "ch2_choice4_stabilize" to "ch2_choice4_overload", "ch2_choice5_anchor" to "ch2_choice5_resonance")
            val banned = exclusive.map { "ec:flag_" + (if (variant == 0) it.second else it.first) }.toSet()
            p.flags += declared.filter { it !in banned }
            val inv = MemoryInventory(capacity = 10_000)
            var progress = true
            while (progress) {
                progress = false
                for (id in sides) {
                    val q = world.quests.getValue(id)
                    if (p.quests[id]?.completed == true || !q.condition.matches(p)) continue
                    runCatching { engine.start(p, id) }.onFailure { continue }
                    for (o in q.objectives) when (o.kind) {
                        ObjectiveKind.KILL -> repeat(o.amount) { game.kill(p, o.target, inv, rollDrops = false) }
                        ObjectiveKind.COLLECT -> inv.exchange(emptyMap(), mapOf(o.target to o.amount))
                        else -> engine.event(p, o.kind, o.target, 1, inv)
                    }
                    if (engine.ready(p, id, inv) && runCatching { engine.complete(p, id, inv) }.isSuccess) { doneAnywhere += id; progress = true }
                }
            }
            report.appendLine("$cls variant$variant -> ${p.quests.count { (k, v) -> !isMain(k) && v.completed }}/${sides.size} side quests")
        }
        File("build").mkdirs(); File("build/qa-sidequests.txt").writeText(report.toString())
        val never = sides - doneAnywhere
        assertTrue(never.isEmpty(), "side quests never completable: $never\n$report")
    }

    @Test
    fun dailyAndWeeklyQuestsRepeatOnlyAfterTheResetBoundary() {
        val tuning = world.tuning; val zone = java.time.ZoneId.of(tuning.resetZone)
        val daily = world.quests.values.first { it.resetSchedule == "DAILY" }; val weekly = world.quests.values.first { it.resetSchedule == "WEEKLY" }
        fun millis(local: String) = java.time.LocalDateTime.parse(local).atZone(zone).toInstant().toEpochMilli()
        var now = 0L
        val engine = QuestEngine({ snapshot }, Clock { now })
        val game = GameService({ snapshot }, SkillEngine(Clock { 0L }, snapshot.balance))
        fun cycle(p: PlayerProfile, q: QuestDefinition): Boolean {
            val inv = MemoryInventory(capacity = 10_000)
            if (runCatching { engine.start(p, q.id) }.isFailure) return false
            for (o in q.objectives) when (o.kind) {
                ObjectiveKind.KILL -> repeat(o.amount) { game.kill(p, o.target, inv, rollDrops = false) }
                ObjectiveKind.COLLECT -> inv.exchange(emptyMap(), mapOf(o.target to o.amount))
                else -> engine.event(p, o.kind, o.target, 1, inv)
            }
            return engine.ready(p, q.id, inv) && runCatching { engine.complete(p, q.id, inv) }.isSuccess
        }
        fun fresh(): PlayerProfile = PlayerProfile(UUID.randomUUID(), level = 20).also { it.classId = "ec:class_warrior"; mainDone(it); it.flags += world.chapters.values.flatMap { c -> c.strings("flags") } }
        val hour = tuning.dailyResetHour
        val day0 = java.time.LocalDate.of(2026, 11, 18) // a Wednesday
        val beforeReset = java.time.LocalDateTime.of(day0.plusDays(1), java.time.LocalTime.of(hour, 0)).minusSeconds(1)
        val atReset = java.time.LocalDateTime.of(day0.plusDays(1), java.time.LocalTime.of(hour, 0))

        val pd = fresh(); now = millis(day0.atTime(hour, 0).plusMinutes(5).toString())
        assertTrue(cycle(pd, daily), "first daily completion failed")
        now = millis(beforeReset.toString()); assertTrue(!cycle(pd, daily), "daily repeated one second before the reset boundary")
        now = millis(atReset.toString()); assertTrue(cycle(pd, daily), "daily not available again exactly at the reset boundary")

        val pw = fresh(); val monday = java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.valueOf(tuning.weeklyResetDay))
        val nextWeek = day0.with(monday).let { if (it <= day0) it.plusWeeks(1) else it }
        now = millis(day0.atTime(hour, 0).plusMinutes(5).toString())
        assertTrue(cycle(pw, weekly), "first weekly completion failed")
        now = millis(nextWeek.atTime(hour, 0).minusSeconds(1).toString()); assertTrue(!cycle(pw, weekly), "weekly repeated before the weekly boundary")
        now = millis(nextWeek.atTime(hour, 0).toString()); assertTrue(cycle(pw, weekly), "weekly not available at the weekly boundary")
        // the daily period key moves exactly once per day
        val k1 = ResetPeriods.key(millis(beforeReset.toString()), "DAILY", tuning); val k2 = ResetPeriods.key(millis(atReset.toString()), "DAILY", tuning)
        assertTrue(k1 != k2, "period key did not change at the boundary: $k1 / $k2")
        assertEquals(k2, ResetPeriods.key(millis(atReset.plusHours(23).toString()), "DAILY", tuning))
    }
}
