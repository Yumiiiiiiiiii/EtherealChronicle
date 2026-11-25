package net.nakaymi.etherealchronicle.qa

import net.nakaymi.etherealchronicle.paper.RpgPlugin
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.damage.DamageSource
import org.bukkit.damage.DamageType
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.PlayerMock
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ManualClientFindingsTest {
    private fun ServerMock.pump(timeoutMs: Long = 5000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            scheduler.performOneTick()
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }

    @Test
    fun `new player joins and respawns at Lumina and every class starter is bound`() {
        val server = MockBukkit.mock()
        try {
            val plugin = MockBukkit.load(RpgPlugin::class.java)
            assertTrue(server.pump { plugin.isReadyForGameplay && runCatching { plugin.snapshot }.isSuccess })
            val world = server.getWorld(net.nakaymi.etherealchronicle.core.WorldIds.DEMO) ?: server.addSimpleWorld(net.nakaymi.etherealchronicle.core.WorldIds.DEMO)
            val player = PlayerMock(server, "LuminaQA", UUID.nameUUIDFromBytes("lumina-qa".toByteArray()))
            server.addPlayer(player)

            assertTrue(server.pump { player.world == world && player.location.distanceSquared(org.bukkit.Location(world,0.5,65.0,0.5)) < 0.01 }, "first join did not move to Lumina")
            val respawn = PlayerRespawnEvent(player, player.location, false, false)
            server.pluginManager.callEvent(respawn)
            assertEquals(world, respawn.respawnLocation.world)
            assertTrue(respawn.respawnLocation.distanceSquared(org.bukkit.Location(world,0.5,65.0,0.5)) < 0.01, "respawn did not use Lumina spawn")

            val definitions = plugin.snapshot.world.itemProperties
            plugin.snapshot.classes.all().forEach { classDefinition ->
                classDefinition.starterItems.forEach { starterId ->
                    assertTrue(definitions[starterId]?.bound == true, "${classDefinition.id} starter $starterId must be bound")
                }
            }
            val starterWeapon = plugin.createItem("ec:item_wpn_iron_dirk")
            val death = PlayerDeathEvent(player, DamageSource.builder(DamageType.GENERIC).build(), mutableListOf(starterWeapon), 0, "QA death")
            server.pluginManager.callEvent(death)
            assertTrue(death.drops.isEmpty(), "death must not drop items")
            assertTrue(death.keepInventory && death.keepLevel, "death must keep inventory and level")
        } finally {
            MockBukkit.unmock()
        }
    }

    @Test
    fun `duplicate admin spawn reports a Japanese cap message`() {
        val server = MockBukkit.mock()
        try {
            val plugin = MockBukkit.load(RpgPlugin::class.java)
            assertTrue(server.pump { plugin.isReadyForGameplay && runCatching { plugin.snapshot }.isSuccess })
            val world = server.getWorld(net.nakaymi.etherealchronicle.core.WorldIds.DEMO) ?: server.addSimpleWorld(net.nakaymi.etherealchronicle.core.WorldIds.DEMO)
            val player = PlayerMock(server, "SpawnQA", UUID.nameUUIDFromBytes("spawn-qa".toByteArray()))
            player.addAttachment(plugin, "etherealchronicle.admin", true)
            server.addPlayer(player)
            server.pump { plugin.profiles.containsKey(player.uniqueId) }
            player.teleport(org.bukkit.Location(world,0.5,65.0,0.5))

            assertTrue(player.performCommand("ec spawn ec:enemy_aether_slime ${player.name}"))
            assertTrue(server.pump { player.nextMessage()?.let { it.contains("魔物を召喚しました") || it.contains("Enemy spawned") } == true }, "first spawn did not complete")
            assertTrue(player.performCommand("ec spawn ec:enemy_aether_slime ${player.name}"))
            assertTrue(server.pump { player.nextMessage()?.let { it.contains("同じ魔物") || it.contains("already exists or the spawn limit") } == true }, "duplicate spawn did not explain the cap")
        } finally {
            MockBukkit.unmock()
        }
    }
}
