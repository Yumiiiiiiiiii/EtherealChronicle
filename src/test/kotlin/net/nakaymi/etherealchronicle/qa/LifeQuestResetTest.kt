package net.nakaymi.etherealchronicle.qa

import net.nakaymi.etherealchronicle.core.*
import net.nakaymi.etherealchronicle.data.ContentService
import net.nakaymi.etherealchronicle.data.strings
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertTrue

/** Every repeatable quest with a reset schedule (incl. the life_* daily quests): unavailable 1 s before the boundary, available at it, objectives reachable. */
class LifeQuestResetTest {
    private val snapshot by lazy { ContentService(Path.of("src/main/resources/content")).load() }

    @Test
    fun everyScheduledQuestRepeatsOnlyAtItsBoundary() {
        val world = snapshot.world; val tuning = world.tuning; val zone = java.time.ZoneId.of(tuning.resetZone)
        val scheduled = world.quests.values.filter { it.resetSchedule != null }
        assertTrue(scheduled.any { it.id.contains("life_") }, "no scheduled life quest found")
        fun millis(t: java.time.LocalDateTime) = t.atZone(zone).toInstant().toEpochMilli()
        var now = 0L
        val engine = QuestEngine({ snapshot }, Clock { now })
        val game = GameService({ snapshot }, SkillEngine(Clock { 0L }, snapshot.balance))
        val hour = tuning.dailyResetHour
        val wed = java.time.LocalDate.of(2026, 11, 18)
        val problems = mutableListOf<String>()
        for (q in scheduled) {
            val p = PlayerProfile(UUID.randomUUID(), level = 20).also { it.classId = "ec:class_warrior"; it.flags += world.chapters.values.flatMap { c -> c.strings("flags") }
                world.quests.keys.filter { id -> Regex("ec:quest_(ch2_)?m[0-9][0-9]_.*").matches(id) }.forEach { id -> it.quests[id] = QuestProgress(completed = true, completedAt = 1) } }
            fun cycle(): Boolean {
                val inv = MemoryInventory(capacity = 10_000)
                if (runCatching { engine.start(p, q.id) }.isFailure) return false
                for (o in q.objectives) when (o.kind) {
                    ObjectiveKind.KILL -> repeat(o.amount) { game.kill(p, o.target, inv, rollDrops = false) }
                    ObjectiveKind.COLLECT -> inv.exchange(emptyMap(), mapOf(o.target to o.amount))
                    else -> engine.event(p, o.kind, o.target, 1, inv)
                }
                return engine.ready(p, q.id, inv) && runCatching { engine.complete(p, q.id, inv) }.isSuccess
            }
            val firstAt = if (q.resetSchedule == "DAILY") wed.atTime(hour, 5) else wed.atTime(hour, 5)
            now = millis(firstAt); if (!cycle()) { problems += "${q.id}: first completion failed"; continue }
            val boundary = if (q.resetSchedule == "DAILY") wed.plusDays(1).atTime(hour, 0)
            else wed.with(java.time.temporal.TemporalAdjusters.next(java.time.DayOfWeek.valueOf(tuning.weeklyResetDay))).atTime(hour, 0)
            now = millis(boundary.minusSeconds(1)); if (cycle()) problems += "${q.id}: repeated before the ${q.resetSchedule} boundary"
            now = millis(boundary); if (!cycle()) problems += "${q.id}: not available again at the ${q.resetSchedule} boundary"
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }
}
