package net.nakaymi.etherealchronicle.qa

import net.nakaymi.etherealchronicle.paper.RpgMenu
import net.nakaymi.etherealchronicle.paper.RpgPlugin
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.PlayerMock
import java.util.UUID
import kotlin.test.*

class CodexGuiTest {
    private fun ServerMock.pump(timeout:Long=5000,ready:()->Boolean):Boolean {
        val until=System.currentTimeMillis()+timeout
        while(System.currentTimeMillis()<until) { scheduler.waitAsyncTasksFinished(); scheduler.performOneTick(); if(ready()) return true; Thread.sleep(20) }
        return ready()
    }
    @Test fun `codex command opens area item and enemy detail pages`() {
        val server=MockBukkit.mock()
        try {
            val plugin=MockBukkit.load(RpgPlugin::class.java)
            val player=PlayerMock(server,"CodexReader",UUID.randomUUID()); server.addPlayer(player)
            assertTrue(server.pump { player.uniqueId in plugin.profiles })
            val command = object : org.bukkit.command.Command("ec") {
                override fun execute(sender: org.bukkit.command.CommandSender, label: String, args: Array<out String>) = true
            }
            assertTrue(plugin.onCommand(player,command,"ec",arrayOf("codex")))
            assertTrue((player.openInventory.topInventory?.holder as? RpgMenu)?.kind == "codex-areas")
            assertTrue(plugin.snapshot.world.areas.isNotEmpty())
            plugin.codexClick(player.simulateInventoryClick(player.openInventory,0))
            assertTrue((player.openInventory.topInventory?.holder as? RpgMenu)?.kind == "codex-area")
            plugin.codexClick(player.simulateInventoryClick(player.openInventory,53))
            assertTrue((player.openInventory.topInventory?.holder as? RpgMenu)?.kind == "codex-areas")
            plugin.codexClick(player.simulateInventoryClick(player.openInventory,53))
            assertTrue((player.openInventory.topInventory?.holder as? RpgMenu)?.kind == "codex-items")
        } finally { MockBukkit.unmock() }
    }
}
