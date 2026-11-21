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
            plugin.codexClick(player.simulateInventoryClick(player.openInventory,46))
            assertTrue((player.openInventory.topInventory?.holder as? RpgMenu)?.kind == "codex-areas")
            plugin.codexClick(player.simulateInventoryClick(player.openInventory,49))
            assertTrue((player.openInventory.topInventory?.holder as? RpgMenu)?.kind == "codex-items")
        } finally { MockBukkit.unmock() }
    }

    @Test fun `discovered item codex turns pages without gaps`() {
        val server=MockBukkit.mock()
        try {
            val plugin=MockBukkit.load(RpgPlugin::class.java)
            val player=PlayerMock(server,"CodexPager",UUID.randomUUID()); server.addPlayer(player)
            assertTrue(server.pump { player.uniqueId in plugin.profiles })
            val profile=plugin.profiles.getValue(player.uniqueId)
            val expected=plugin.snapshot.items.all().sortedBy { it.id }.take(46)
            player.isOp=true
            val command=object:org.bukkit.command.Command("ec") {
                override fun execute(sender:org.bukkit.command.CommandSender,label:String,args:Array<out String>)=true
            }
            plugin.onCommand(player,command,"ec",arrayOf("give",player.name,expected.first().id,"1"))
            assertTrue(expected.first().id in profile.discoveredItems,"admin give should expose granted items in the codex")
            profile.discoveredItems.addAll(expected.drop(1).map { it.id })

            plugin.openCodexItems(player)
            assertEquals("codex-items",(player.openInventory.topInventory.holder as RpgMenu).kind)
            assertTrue(player.openInventory.topInventory.getItem(53)!=null,"next page button is missing")
            plugin.codexClick(player.simulateInventoryClick(player.openInventory,53))
            assertEquals("codex-items",(player.openInventory.topInventory.holder as RpgMenu).kind)
            val secondPageIds=player.openInventory.topInventory.getItem(0)?.itemMeta?.lore()?.map { it.toString() }.orEmpty()
            assertTrue(secondPageIds.any { it.contains(expected[45].id) },"the next item did not appear on page 2: $secondPageIds")
            assertTrue(player.openInventory.topInventory.getItem(45)!=null,"previous page button is missing")
            assertEquals(null,player.openInventory.topInventory.getItem(53),"last page must not expose next")
            plugin.codexClick(player.simulateInventoryClick(player.openInventory,45))
            assertTrue(player.openInventory.topInventory.getItem(53)!=null,"returning to page 1 should restore next")
        } finally { MockBukkit.unmock() }
    }
}
