package net.nakaymi.etherealchronicle.qa

import net.nakaymi.etherealchronicle.paper.DemoWorld
import net.nakaymi.etherealchronicle.paper.ClassMenu
import net.nakaymi.etherealchronicle.paper.RpgPlugin
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.LivingEntity
import org.bukkit.persistence.PersistentDataType
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.PlayerMock
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DungeonRoutingTest {
    private fun ServerMock.pump(timeoutMs: Long = 5000, cond: () -> Boolean): Boolean {
        val end=System.currentTimeMillis()+timeoutMs
        while(System.currentTimeMillis()<end) {
            scheduler.performOneTick()
            if(cond()) return true
            Thread.sleep(20)
        }
        return cond()
    }

    @Test
    fun `command routes to selected dungeon and uses its boss guards palette and return point`() {
        val server=MockBukkit.mock()
        try {
            val plugin=MockBukkit.load(RpgPlugin::class.java)
            assertTrue(server.pump { plugin.isReadyForGameplay })
            val world=server.addSimpleWorld(DemoWorld.NAME)
            val player=PlayerMock(server,"DungeonRoute",UUID.nameUUIDFromBytes("dungeon-route".toByteArray()))
            server.addPlayer(player)
            val itemKey=NamespacedKey(plugin,"item_id")
            assertTrue(server.pump { player.openInventory.topInventory?.holder is ClassMenu })
            val classSlot=(0..8).first { player.openInventory.topInventory.getItem(it)?.persistentDataContainer?.get(itemKey,PersistentDataType.STRING)?.contains("warrior")==true }
            player.simulateInventoryClick(player.openInventory,classSlot)
            assertTrue(server.pump { player.inventory.getItem(8)?.type==Material.NETHER_STAR })
            val profile=plugin.profiles.getValue(player.uniqueId)
            val home=player.location.clone().apply { x=460.0; y=65.0; z=0.0 }
            player.teleport(home)
            profile.flags += "ec:flag_ch2_citadel_unlocked"
            player.performCommand("ec dungeon enter ec:dungeon_glacial_citadel")
            assertTrue(server.pump { player.x in 1023.0..1025.0 && player.z in 1011.0..1013.0 },"did not enter the glacial instance")
            assertEquals(Material.CALCITE,world.getBlockAt(1024,64,1024).type,"glacial schematic center floor")
            val spawned=world.livingEntities.filterIsInstance<LivingEntity>().mapNotNull { entity -> plugin.enemies.state(entity.uniqueId)?.boss?.definition?.id }
            assertEquals(1,spawned.count { it=="ec:boss_cryo_archon" },"Cryo Archon count")
            assertEquals(2,spawned.count { it=="ec:enemy_glacial_knight" },"glacial guard count")
            player.performCommand("ec dungeon leave")
            assertTrue(server.pump { kotlin.math.abs(player.x-460.0)<1.0 },"return location not restored")
            assertEquals(DemoWorld.NAME,player.world.name)
        } finally { MockBukkit.unmock() }
    }
}
