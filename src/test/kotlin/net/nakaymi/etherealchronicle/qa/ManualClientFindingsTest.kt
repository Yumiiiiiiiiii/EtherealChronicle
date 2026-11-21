package net.nakaymi.etherealchronicle.qa

import net.nakaymi.etherealchronicle.paper.DemoWorld
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
            scheduler.waitAsyncTasksFinished()
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
            assertTrue(server.pump { plugin.isEnabled && runCatching { plugin.snapshot }.isSuccess })
            val world = server.getWorld(DemoWorld.NAME) ?: server.addSimpleWorld(DemoWorld.NAME)
            val player = PlayerMock(server, "LuminaQA", UUID.nameUUIDFromBytes("lumina-qa".toByteArray()))
            server.addPlayer(player)

            assertTrue(server.pump { player.world == world && player.location.distanceSquared(DemoWorld.luminaSpawn(world)) < 0.01 }, "first join did not move to Lumina")
            val respawn = PlayerRespawnEvent(player, player.location, false, false)
            server.pluginManager.callEvent(respawn)
            assertEquals(world, respawn.respawnLocation.world)
            assertTrue(respawn.respawnLocation.distanceSquared(DemoWorld.luminaSpawn(world)) < 0.01, "respawn did not use Lumina spawn")

            val definitions = plugin.snapshot.world.itemProperties
            plugin.snapshot.classes.all().forEach { classDefinition ->
                classDefinition.starterItems.forEach { starterId ->
                    assertTrue(definitions[starterId]?.bound == true, "${classDefinition.id} starter $starterId must be bound")
                }
            }
            val starterWeapon = plugin.createItem("ec:item_wpn_iron_dirk")
            val death = PlayerDeathEvent(player, DamageSource.builder(DamageType.GENERIC).build(), mutableListOf(starterWeapon), 0, "QA death")
            server.pluginManager.callEvent(death)
            assertTrue(death.drops.isEmpty(), "bound starter weapon remained in death drops")
            assertTrue(death.itemsToKeep.any { it.itemMeta.persistentDataContainer.get(org.bukkit.NamespacedKey(plugin, "item_id"), org.bukkit.persistence.PersistentDataType.STRING) == "ec:item_wpn_iron_dirk" }, "bound starter weapon was not kept")
        } finally {
            MockBukkit.unmock()
        }
    }

    @Test
    fun `duplicate admin spawn reports a Japanese cap message`() {
        val server = MockBukkit.mock()
        try {
            val plugin = MockBukkit.load(RpgPlugin::class.java)
            assertTrue(server.pump { plugin.isEnabled && runCatching { plugin.snapshot }.isSuccess })
            val world = server.getWorld(DemoWorld.NAME) ?: server.addSimpleWorld(DemoWorld.NAME)
            val player = PlayerMock(server, "SpawnQA", UUID.nameUUIDFromBytes("spawn-qa".toByteArray()))
            player.addAttachment(plugin, "etherealchronicle.admin", true)
            server.addPlayer(player)
            server.pump { plugin.profiles.containsKey(player.uniqueId) }
            player.teleport(DemoWorld.luminaSpawn(world))

            assertTrue(player.performCommand("ec spawn ec:enemy_aether_slime ${player.name}"))
            assertTrue(server.pump { player.nextMessage()?.contains("魔物を召喚しました") == true }, "first spawn did not complete")
            assertTrue(player.performCommand("ec spawn ec:enemy_aether_slime ${player.name}"))
            assertTrue(server.pump { player.nextMessage()?.contains("同じ魔物が既に存在するか、出現上限に達しています") == true }, "duplicate spawn did not explain the cap")
        } finally {
            MockBukkit.unmock()
        }
    }
}
