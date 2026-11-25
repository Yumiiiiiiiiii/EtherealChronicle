package net.nakaymi.etherealchronicle.qa

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.nakaymi.etherealchronicle.core.WorldIds
import net.nakaymi.etherealchronicle.paper.RpgPlugin
import org.bukkit.damage.DamageSource
import org.bukkit.damage.DamageType
import org.bukkit.event.entity.PlayerDeathEvent
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 死亡ペナルティ: アイテム・経験値は失わず、所持ゴールドだけを失う（0 のときは何も起きない）。 */
class DeathPenaltyTest {
    private val plain = PlainTextComponentSerializer.plainText()

    private fun death(player: QaPlayerMock, drops: MutableList<org.bukkit.inventory.ItemStack>): PlayerDeathEvent =
        PlayerDeathEvent(player, DamageSource.builder(DamageType.GENERIC).build(), drops, 5, "QA death")

    private fun setup(server: ServerMock, name: String): Pair<RpgPlugin, QaPlayerMock> {
        QaWorldMock.add(server, WorldIds.DEMO)
        val plugin = MockBukkit.load(RpgPlugin::class.java)
        assertTrue(server.qaPump { plugin.isReadyForGameplay }, "plugin not ready")
        val player = QaPlayerMock(server, name, UUID.nameUUIDFromBytes(name.toByteArray()))
        server.addPlayer(player)
        assertTrue(server.qaPump { player.uniqueId in plugin.profiles }, "profile not loaded")
        return plugin to player
    }

    @Test
    fun `death keeps items and level and removes all gold with a notice`() {
        val server = MockBukkit.mock()
        try {
            val (plugin, player) = setup(server, "DeathGold")
            val profile = plugin.profiles.getValue(player.uniqueId).apply { currency = 500 }
            val drops = mutableListOf(plugin.createItem("ec:item_wpn_iron_dirk"))
            val event = death(player, drops)
            generateSequence { player.nextMessage() }.toList()   // 参加時メッセージを捨てる
            server.pluginManager.callEvent(event)
            assertTrue(server.qaPump { profile.currency == 0L }, "gold was not removed")
            assertTrue(event.keepInventory && event.keepLevel, "inventory/level must be kept")
            assertTrue(event.drops.isEmpty() && event.droppedExp == 0, "no drops / exp orbs")
            assertTrue(server.qaPump { generateSequence { player.nextMessage() }.any { it.contains("lost 500 gold") } }, "notice missing")
        } finally { MockBukkit.unmock() }
    }

    @Test
    fun `death with no gold changes nothing and sends no notice`() {
        val server = MockBukkit.mock()
        try {
            val (plugin, player) = setup(server, "DeathNoGold")
            val profile = plugin.profiles.getValue(player.uniqueId).apply { currency = 0 }
            generateSequence { player.nextMessage() }.toList()
            val event = death(player, mutableListOf())
            server.pluginManager.callEvent(event)
            server.qaPump(500) { false }
            assertEquals(0L, profile.currency)
            assertTrue(event.keepInventory)
            assertFalse(generateSequence { player.nextMessage() }.any { it.contains("lost") && it.contains("gold") }, "unexpected notice")
        } finally { MockBukkit.unmock() }
    }

    @Test
    fun `gold loss is applied when the death happens during a held transaction lock once it is released`() {
        val server = MockBukkit.mock()
        try {
            val (plugin, player) = setup(server, "DeathLocked")
            val profile = plugin.profiles.getValue(player.uniqueId).apply { currency = 300 }
            val adventure = RpgPlugin::class.java.getDeclaredField("adventure").apply { isAccessible = true }.get(plugin)
            val acquire = adventure.javaClass.getDeclaredMethod("acquireLock", UUID::class.java).apply { isAccessible = true }
            val release = adventure.javaClass.declaredMethods.first { it.name == "releaseLock" && it.parameterCount == 2 && !it.isSynthetic }.apply { isAccessible = true }
            val token = acquire.invoke(adventure, player.uniqueId)!!
            server.pluginManager.callEvent(death(player, mutableListOf()))
            server.qaPump(300) { false }
            assertEquals(300L, profile.currency, "gold must not change while locked")
            release.invoke(adventure, token, true)
            (adventure.javaClass.getMethod("resume", UUID::class.java)).invoke(adventure, player.uniqueId)
            assertTrue(server.qaPump { profile.currency == 0L }, "gold not removed after unlock")
        } finally { MockBukkit.unmock() }
    }
}
