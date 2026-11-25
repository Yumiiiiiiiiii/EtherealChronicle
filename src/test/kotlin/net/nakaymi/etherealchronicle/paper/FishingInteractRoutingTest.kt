package net.nakaymi.etherealchronicle.paper

import net.nakaymi.etherealchronicle.core.WorldIds
import net.nakaymi.etherealchronicle.qa.QaWorldMock
import org.bukkit.event.Event
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.Location
import org.bukkit.inventory.EquipmentSlot
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.entity.PlayerMock
import org.mockbukkit.mockbukkit.ServerMock
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Paperの空中クリックはブロック使用DENYでも釣りイベントへ届くことを確認します。 */
class FishingInteractRoutingTest {
    @Test
    fun `block denial does not suppress a usable rod`() {
        val server = MockBukkit.mock()
        try {
            val player = PlayerMock(server, "FishingClick", UUID.randomUUID())
            val event = interaction(player)
            event.setUseInteractedBlock(Event.Result.DENY)
            event.setUseItemInHand(Event.Result.DEFAULT)

            assertTrue(event.isCancelled)
            assertTrue(shouldProcessFishingInteract(event.action, event.useInteractedBlock(), event.useItemInHand(), event.hand))
            assertFalse(shouldProcessFishingInteract(Action.RIGHT_CLICK_BLOCK, Event.Result.DENY, Event.Result.DEFAULT, EquipmentSlot.HAND))
            assertFalse(shouldProcessFishingInteract(event.action, event.useInteractedBlock(), Event.Result.DENY, event.hand))
            assertFalse(shouldProcessFishingInteract(event.action, event.useInteractedBlock(), Event.Result.DEFAULT, EquipmentSlot.OFF_HAND))
        } finally {
            MockBukkit.unmock()
        }
    }

    @Test
    fun `required pack gate explicitly denies item use`() {
        val server = MockBukkit.mock()
        try {
            val plugin = MockBukkit.load(RpgPlugin::class.java)
            val player = PlayerMock(server, "FishingLocked", UUID.randomUUID())
            server.addPlayer(player)
            RequiredPackLockListener(plugin, { true }, { true })
            val event = PlayerInteractEvent(
                player,
                Action.RIGHT_CLICK_AIR,
                player.inventory.itemInMainHand,
                null,
                org.bukkit.block.BlockFace.SELF,
                EquipmentSlot.HAND,
            )

            server.pluginManager.callEvent(event)

            assertEquals(Event.Result.DENY, event.useItemInHand())
            assertEquals(Event.Result.DENY, event.useInteractedBlock())
        } finally {
            MockBukkit.unmock()
        }
    }

    @Test
    fun `right click air with block use denied reaches active fishing controller`() {
        val server = MockBukkit.mock()
        try {
            val plugin = MockBukkit.load(RpgPlugin::class.java)
            assertTrue(await(server) { runCatching { plugin.fishing }.isSuccess })
            val world = QaWorldMock.add(server, WorldIds.DEMO)
            val player = PlayerMock(server, "FishingControllerClick", UUID.randomUUID())
            server.addPlayer(player)
            assertTrue(await(server) { player.uniqueId in plugin.profiles })
            player.teleport(Location(world, 0.5, 65.0, 0.5))
            val rod = plugin.snapshot.world.tackle.values.first { it.kind == net.nakaymi.etherealchronicle.core.TackleKind.ROD }
            player.inventory.setItemInMainHand(plugin.createItem(rod.item))
            plugin.fishing.debugStart(player, null)

            val event = interaction(player, player.inventory.itemInMainHand).apply {
                setUseInteractedBlock(Event.Result.DENY)
                setUseItemInHand(Event.Result.DEFAULT)
            }
            server.pluginManager.callEvent(event)

            assertTrue(plugin.fishing.active(player.uniqueId))
            assertEquals(Event.Result.DENY, event.useItemInHand(), "the active controller must consume the rod click")
        } finally {
            MockBukkit.unmock()
        }
    }

    private fun await(server: ServerMock, condition: () -> Boolean): Boolean {
        repeat(300) {
            server.scheduler.performOneTick()
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    private fun interaction(player: PlayerMock, item: org.bukkit.inventory.ItemStack? = null) = PlayerInteractEvent(
        player,
        Action.RIGHT_CLICK_AIR,
        item,
        null,
        org.bukkit.block.BlockFace.SELF,
        EquipmentSlot.HAND,
    )
}
