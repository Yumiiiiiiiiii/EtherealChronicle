package net.nakaymi.etherealchronicle.paper

import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.entity.PlayerMock
import java.util.UUID
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 必須パックのロックが他リスナーより先に適用され、待機なしでは短絡することを検証します。 */
class RequiredPackLockListenerTest {
    @Test
    fun eventLockRunsBeforeNormalHandlersAndSkipsWhenNoWaiters() {
        val server = MockBukkit.mock()
        try {
            val plugin = MockBukkit.load(RpgPlugin::class.java)
            org.bukkit.event.HandlerList.unregisterAll(plugin as org.bukkit.plugin.Plugin)
            val player = PlayerMock(server, "PackWaiter", UUID.randomUUID())
            server.addPlayer(player)
            var waiting = true
            var downstreamSawActiveClick = false
            RequiredPackLockListener(plugin, { waiting }, { waiting })
            server.pluginManager.registerEvents(object : Listener {
                @EventHandler(ignoreCancelled = true)
                fun onInteract(event: PlayerInteractEvent) {
                    downstreamSawActiveClick = true
                }
            }, plugin)

            val blocked = interaction(player)
            blocked.isCancelled = false
            server.pluginManager.callEvent(blocked)
            assertTrue(blocked.isCancelled)
            assertFalse(downstreamSawActiveClick, "a NORMAL listener must not process the click")

            waiting = false
            val allowed = interaction(player)
            allowed.isCancelled = false
            server.pluginManager.callEvent(allowed)
            assertFalse(allowed.isCancelled)
            assertTrue(downstreamSawActiveClick, "events must pass through when the waiter set is empty")
        } finally {
            MockBukkit.unmock()
        }
    }

    @Test
    fun itemPickupIsCancelledForAWaitingPlayer() {
        val server = MockBukkit.mock()
        try {
            val plugin = MockBukkit.load(RpgPlugin::class.java)
            val player = PlayerMock(server, "PackPickup", UUID.randomUUID())
            server.addPlayer(player)
            RequiredPackLockListener(plugin, { true }, { true })
            val item = player.world.dropItem(player.location, ItemStack(Material.STONE))
            val event = org.bukkit.event.entity.EntityPickupItemEvent(player, item, 0)

            server.pluginManager.callEvent(event)

            assertTrue(event.isCancelled)
        } finally {
            MockBukkit.unmock()
        }
    }

    private fun interaction(player: PlayerMock) = PlayerInteractEvent(
        player,
        Action.RIGHT_CLICK_AIR,
        null,
        null,
        org.bukkit.block.BlockFace.SELF,
        EquipmentSlot.HAND,
    )

}
