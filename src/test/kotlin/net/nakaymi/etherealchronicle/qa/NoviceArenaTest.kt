package net.nakaymi.etherealchronicle.qa

import net.nakaymi.etherealchronicle.core.WorldIds
import net.nakaymi.etherealchronicle.paper.RpgPlugin
import org.bukkit.Location
import org.bukkit.entity.Player
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import kotlin.test.assertTrue

/** Novice arena (early content): flag gate, entry, leave cleanup, re-entry. Waves/ranks are covered by Codex's engine tests. */
class NoviceArenaTest {
    @Test
    fun arenaGateEnterLeaveReenter() {
        val server = MockBukkit.mock()
        try {
            val plugin = MockBukkit.load(RpgPlugin::class.java)
            val end = System.currentTimeMillis() + 5000
            while (runCatching { plugin.snapshot; plugin.dungeons; true }.isFailure && System.currentTimeMillis() < end) { server.scheduler.performOneTick(); Thread.sleep(20) }
            seedArenaSchematic(plugin.dungeons, "ec:dungeon_novice_arena")
            assertTrue(server.qaPump { plugin.dungeons.isJournalReady() }, "return journal did not become ready")
            val world = QaWorldMock.add(server, WorldIds.DEMO)
            val p = QaPlayerMock(server, "ArenaA", java.util.UUID.nameUUIDFromBytes("arena-a".toByteArray())); server.addPlayer(p)
            val until = System.currentTimeMillis() + 5000
            while (p.uniqueId !in plugin.profiles && System.currentTimeMillis() < until) { server.scheduler.performOneTick(); Thread.sleep(20) }
            val pr = plugin.profiles.getValue(p.uniqueId); pr.classId = "ec:class_warrior"; pr.level = 10; pr.starterGranted = true
            p.teleport(Location(world, 90.0, 65.0, 0.0))
            val d = plugin.dungeons
            val enter = d.javaClass.getMethod("enter", Player::class.java, String::class.java); val leave = d.javaClass.getMethod("leave", Player::class.java); val active = d.javaClass.getMethod("activeCount")
            runCatching { enter.invoke(d, p, "ec:dungeon_novice_arena") }
            assertTrue((active.invoke(d) as Int) == 0, "arena entered without ec:flag_early_trial_unlocked")
            pr.flags += "ec:flag_early_trial_unlocked"
            enter.invoke(d, p, "ec:dungeon_novice_arena"); assertTrue(server.qaPump { plugin.dungeons.containsPlayer(p.uniqueId) && (active.invoke(d) as Int) == 1 && p.location.y > 150.0 }, "arena not created with the flag")
            leave.invoke(d, p); assertTrue(server.qaPump { !plugin.dungeons.containsPlayer(p.uniqueId) && (active.invoke(d) as Int) == 0 && p.location.y < 100.0 }, "arena instance left behind")
            enter.invoke(d, p, "ec:dungeon_novice_arena"); assertTrue(server.qaPump { plugin.dungeons.containsPlayer(p.uniqueId) && (active.invoke(d) as Int) == 1 }, "re-entry failed"); leave.invoke(d, p)
        } finally { MockBukkit.unmock() }
    }
}
