package net.nakaymi.etherealchronicle.qa

import net.nakaymi.etherealchronicle.paper.RpgMenu
import net.nakaymi.etherealchronicle.paper.RpgPlugin
import org.bukkit.NamespacedKey
import org.bukkit.persistence.PersistentDataType
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.PlayerMock
import java.util.UUID
import kotlin.test.*

class EnhancementGuiTest {
    private fun ServerMock.pump(timeout:Long=5000,ready:()->Boolean):Boolean {
        val until=System.currentTimeMillis()+timeout
        while(System.currentTimeMillis()<until) { scheduler.performOneTick(); if(ready()) return true; Thread.sleep(20) }
        return ready()
    }
    @Test fun `enhancement consumes material and currency once then persists item level`() {
        val server=MockBukkit.mock()
        try {
            val world=OwnedWorldMock.add(server, net.nakaymi.etherealchronicle.core.WorldIds.DEMO)
            java.io.File(world.worldFolder, ".etherealchronicle-built").writeText("v4\n")
            val plugin=MockBukkit.load(RpgPlugin::class.java)
            val player=QaPlayerMock(server,"Smith",UUID.randomUUID()); server.addPlayer(player)
            assertTrue(server.pump { player.uniqueId in plugin.profiles })
            val profile=plugin.profiles.getValue(player.uniqueId)
            profile.classId="ec:class_warrior"; profile.level=20; profile.currency=1000
            player.inventory.setItemInMainHand(plugin.createItem("ec:item_wpn_rusty_broadsword"))
            player.inventory.addItem(plugin.createItem("ec:item_mat_artisan_whetstone"))
            plugin.openEnhancement(player)
            val menu=player.openInventory.topInventory?.holder as RpgMenu
            assertEquals("enhance",menu.kind)
            val before=plugin.effectiveStats(player,profile).attack
            plugin.enhance(player,menu)
            val key=NamespacedKey(plugin,"enhancement_level")
            assertTrue(server.pump { player.inventory.itemInMainHand.itemMeta.persistentDataContainer.get(key,PersistentDataType.INTEGER) == 1 })
            assertEquals(900,profile.currency)
            assertEquals(0,player.inventory.storageContents.filterNotNull().sumOf { stack -> if(plugin.identify(stack)=="ec:item_mat_artisan_whetstone") stack.amount else 0 })
            assertTrue(plugin.effectiveStats(player,profile).attack > before)
            plugin.persist(profile).join()
            assertEquals(1,player.inventory.itemInMainHand.itemMeta.persistentDataContainer.get(key,PersistentDataType.INTEGER))
        } finally { MockBukkit.unmock() }
    }
}
