package net.nakaymi.etherealchronicle.paper

import org.bukkit.event.Event
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.entity.PlayerMock
import java.util.UUID
import kotlin.test.assertEquals
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
            assertTrue(shouldProcessFishingInteract(event.useItemInHand(), event.hand))
            assertTrue(!shouldProcessFishingInteract(Event.Result.DENY, event.hand))
            assertTrue(!shouldProcessFishingInteract(Event.Result.DEFAULT, EquipmentSlot.OFF_HAND))
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

    private fun interaction(player: PlayerMock) = PlayerInteractEvent(
        player,
        Action.RIGHT_CLICK_AIR,
        null,
        null,
        org.bukkit.block.BlockFace.SELF,
        EquipmentSlot.HAND,
    )
}
