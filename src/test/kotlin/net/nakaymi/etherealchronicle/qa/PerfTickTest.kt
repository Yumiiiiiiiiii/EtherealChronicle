package net.nakaymi.etherealchronicle.qa

import net.nakaymi.etherealchronicle.paper.RpgPlugin
import org.bukkit.Material
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.entity.PlayerMock
import java.io.File
import java.util.UUID
import kotlin.test.assertTrue

/** Rough plugin-tick cost with 20 classed virtual players (MockBukkit; not a substitute for spark on a real server). */
class PerfTickTest {
    @Test
    fun twentyPlayersOneMinuteOfTicks() {
        val server = MockBukkit.mock()
        try {
            val plugin = MockBukkit.load(RpgPlugin::class.java)
            val end = System.currentTimeMillis() + 5000
            while (!plugin.isEnabled && System.currentTimeMillis() < end) { server.scheduler.waitAsyncTasksFinished(); server.scheduler.performOneTick(); Thread.sleep(20) }
            val players = (1..20).map { i ->
                PlayerMock(server, "Perf$i", UUID.nameUUIDFromBytes("perf-$i".toByteArray())).also { p ->
                    p.inventory.setItem(EquipmentSlot.HEAD, ItemStack(Material.LEATHER_HELMET)); p.inventory.setItem(EquipmentSlot.CHEST, ItemStack(Material.LEATHER_CHESTPLATE))
                    p.inventory.setItem(EquipmentSlot.LEGS, ItemStack(Material.LEATHER_LEGGINGS)); p.inventory.setItem(EquipmentSlot.FEET, ItemStack(Material.LEATHER_BOOTS))
                    server.addPlayer(p)
                }
            }
            val until = System.currentTimeMillis() + 8000
            while (players.any { it.uniqueId !in plugin.profiles } && System.currentTimeMillis() < until) { server.scheduler.waitAsyncTasksFinished(); server.scheduler.performOneTick(); Thread.sleep(20) }
            players.forEach { p -> plugin.profiles[p.uniqueId]?.let { it.classId = "ec:class_mage"; it.starterGranted = true; it.resource = 50.0 } }
            val loaded = players.count { it.uniqueId in plugin.profiles }
            val ticks = 1200 // 60 s of game time -> 60 plugin tick() runs
            val t0 = System.nanoTime()
            repeat(ticks) { server.scheduler.performOneTick() }
            val totalMs = (System.nanoTime() - t0) / 1e6
            val perTick = totalMs / ticks
            val line = "players=$loaded ticks=$ticks total=${"%.1f".format(totalMs)}ms avg/tick=${"%.3f".format(perTick)}ms (plugin tick() runs every 20 ticks)"
            File("build").mkdirs(); File("build/qa-perf.txt").writeText(line); println(line)
            assertTrue(loaded == 20, "only $loaded/20 profiles loaded")
            assertTrue(perTick < 5.0, "plugin work averaged ${perTick}ms per server tick with 20 players (budget 50ms/tick total): $line")
        } finally { MockBukkit.unmock() }
    }
}
