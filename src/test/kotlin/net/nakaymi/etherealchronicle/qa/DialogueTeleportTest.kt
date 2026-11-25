package net.nakaymi.etherealchronicle.qa

import net.nakaymi.etherealchronicle.core.WorldIds
import net.nakaymi.etherealchronicle.paper.ClassMenu
import net.nakaymi.etherealchronicle.paper.RpgPlugin
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.event.inventory.ClickType
import org.bukkit.persistence.PersistentDataType
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.PlayerMock
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DialogueTeleportTest {
    private fun ServerMock.pump(timeoutMs: Long = 5000,cond: () -> Boolean): Boolean {
        val end=System.currentTimeMillis()+timeoutMs
        while(System.currentTimeMillis()<end) {
            scheduler.performOneTick()
            if(cond()) return true
            Thread.sleep(20)
        }
        return cond()
    }

    @Test
    fun `global airship dialogue choices teleport only after chapter two starts`() {
        val server=MockBukkit.mock()
        try {
            val world=QaWorldMock.add(server, WorldIds.DEMO)
            val plugin=MockBukkit.load(RpgPlugin::class.java)
            val player=PlayerMock(server,"Airship",UUID.nameUUIDFromBytes("airship".toByteArray()))
            server.addPlayer(player)
            val itemKey=NamespacedKey(plugin,"item_id")
            assertTrue(server.pump { player.openInventory.topInventory?.holder is ClassMenu })
            val classSlot=(0..8).first { player.openInventory.topInventory.getItem(it)?.persistentDataContainer?.get(itemKey,PersistentDataType.STRING)?.contains("warrior")==true }
            player.simulateInventoryClick(player.openInventory,classSlot)
            assertTrue(server.pump { player.inventory.getItem(8)?.type==Material.NETHER_STAR })
            val profile=plugin.profiles.getValue(player.uniqueId)
            val entryKey=NamespacedKey(plugin,"entry_id")
            fun select(id: String) {
                val slot=(9..53).first { player.openInventory.topInventory.getItem(it)?.persistentDataContainer?.get(entryKey,PersistentDataType.STRING)==id }
                player.simulateInventoryClick(player.openInventory,ClickType.LEFT,slot)
            }
            plugin.adventure.showDialogue(player,"ec:dlg_freya_hub")
            assertFalse((9..53).any { player.openInventory.topInventory.getItem(it)?.persistentDataContainer?.get(entryKey,PersistentDataType.STRING)=="airship_to_frostpeak" })

            profile.flags += "ec:flag_ch2_started"
            player.teleport(org.bukkit.Location(world,18.0,65.0,-12.0))
            plugin.adventure.showDialogue(player,"ec:dlg_freya_hub")
            select("airship_to_frostpeak")
            assertTrue(server.pump { kotlin.math.abs(player.x-480.0)<0.5 && kotlin.math.abs(player.z)<0.5 },"Freya airship did not reach the outpost")

            plugin.adventure.showDialogue(player,"ec:dlg_orphe_hub")
            select("airship_to_lumina")
            assertTrue(server.pump { kotlin.math.abs(player.x-18.0)<0.5 && kotlin.math.abs(player.z+12.0)<0.5 },"Orphe airship did not return to Lumina")
            assertEquals(WorldIds.DEMO,player.world.name)
        } finally { MockBukkit.unmock() }
    }
}
