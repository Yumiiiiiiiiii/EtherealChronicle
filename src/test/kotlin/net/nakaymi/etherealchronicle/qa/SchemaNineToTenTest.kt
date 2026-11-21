package net.nakaymi.etherealchronicle.qa

import net.nakaymi.etherealchronicle.core.LifeSkillKind
import net.nakaymi.etherealchronicle.paper.RpgPlugin
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import java.io.File
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Legacy profile migration retains progress and adds current life/enhancement fields. */
class SchemaNineToTenTest {
    @Test
    fun schemaNineJoinsAndIsRewritten() {
        val server = MockBukkit.mock()
        try {
            val plugin = MockBukkit.load(RpgPlugin::class.java)
            val end = System.currentTimeMillis() + 5000
            while (runCatching { plugin.snapshot; true }.getOrDefault(false).not() && System.currentTimeMillis() < end) { server.scheduler.waitAsyncTasksFinished(); server.scheduler.performOneTick(); Thread.sleep(20) }
            val uuid = UUID.nameUUIDFromBytes("legacy-9".toByteArray())
            val dir = File(plugin.dataFolder, "playerdata").also { it.mkdirs() }
            val nl = System.lineSeparator()
            File(dir, "$uuid.yml").writeText(listOf("schema_version: 9", "uuid: $uuid", "class: ec:class_mage", "level: 15", "xp: 12", "currency: 4242", "resource: 50.0",
                "selected_skill: 0", "starter_granted: true", "quests:", "  ec:quest_m01_awakening: {progress: 4, completed: true, objectives: {}, completed_at: 5, repeat_available_at: 9223372036854775807}",
                "skill_levels: {'ec:mage_strike': 2}", "flags: ['ec:flag_ch1_started']", "transactions: []", "daily_purchases: {}", "accessory: null", "pending_rewards: {}",
                "drop_pity: {}", "challenge_records: {}", "selected_title: null", "bestiary: {'ec:enemy_aether_slime': 7}", "discovered_items: ['ec:item_mat_aether_gel']").joinToString(nl))
            val p = QaPlayerMock(server, "Nine", uuid); server.addPlayer(p)
            assertTrue(server.pump(5000) { uuid in plugin.profiles }, "schema 9 profile did not load; online=${p.isOnline}")
            val pr = plugin.profiles.getValue(uuid)
            assertEquals("ec:class_mage", pr.classId); assertEquals(15, pr.level); assertEquals(4242L, pr.currency); assertEquals(7, pr.bestiary["ec:enemy_aether_slime"])
            assertEquals(1, pr.lifeSkills.getValue(LifeSkillKind.FISHING).level, "life fishing should start at 1"); assertEquals(1, pr.lifeSkills.getValue(LifeSkillKind.GATHERING).level)
            assertTrue(pr.fishRecords.isEmpty() && pr.gatheringRecords.isEmpty(), "new records should start empty")
            plugin.persist(pr).join()
            val saved = File(dir, "$uuid.yml").readText()
            val ver = Regex("schema_version: (\\d+)").find(saved)!!.groupValues[1].toInt()
            assertTrue(ver >= 11, "file not rewritten with schema >= 11 (is $ver)"); assertTrue(saved.contains("currency: 4242") && saved.contains("aether_gel"), "progress lost in rewrite")
        } finally { MockBukkit.unmock() }
    }
    private fun org.mockbukkit.mockbukkit.ServerMock.pump(ms: Long, c: () -> Boolean): Boolean { val e = System.currentTimeMillis() + ms; while (System.currentTimeMillis() < e) { scheduler.waitAsyncTasksFinished(); scheduler.performOneTick(); if (c()) return true; Thread.sleep(20) }; return c() }
}
