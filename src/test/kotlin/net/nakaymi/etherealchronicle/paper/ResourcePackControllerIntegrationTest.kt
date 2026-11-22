package net.nakaymi.etherealchronicle.paper

import org.bukkit.event.HandlerList
import org.bukkit.event.player.PlayerResourcePackStatusEvent
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.PlayerMock
import java.util.UUID
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** リソースパックControllerの実イベントと待機解除・個人OFF上書きを検証します。 */
class ResourcePackControllerIntegrationTest {
    @Test
    fun requiredRequestCompletesWaiterAndOverridesSavedOffPreference() {
        val server = MockBukkit.mock()
        try {
            val plugin = MockBukkit.load(RpgPlugin::class.java)
            awaitControllers(server, plugin)
            HandlerList.unregisterAll(plugin as org.bukkit.plugin.Plugin)
            plugin.config.set("resource_pack.mode", "external_url")
            plugin.config.set("resource_pack.external_url", "https://packs.example.test/pack.zip")
            plugin.config.set("resource_pack.external_sha1", "a".repeat(40))
            plugin.config.set("resource_pack.required", true)
            val controller = ResourcePackController(plugin)
            val player = PlayerMock(server, "PackIntegration", UUID.randomUUID())
            server.addPlayer(player)
            var continued = false

            assertTrue(controller.beginRequiredJoin(player))
            controller.whenRequiredPackReady(player) { continued = true }
            // OFFでも必須要求は送信し、成功後はUIでパック適用済みとして扱います。
            controller.send(player, PackPreference.OFF)
            server.scheduler.performOneTick()
            val packId = UUID.nameUUIDFromBytes("etherealchronicle-resource-pack-v1".toByteArray())
            server.pluginManager.callEvent(
                PlayerResourcePackStatusEvent(player, packId, PlayerResourcePackStatusEvent.Status.ACCEPTED),
            )
            assertTrue(controller.isAwaitingRequiredPack(player.uniqueId))
            server.pluginManager.callEvent(
                PlayerResourcePackStatusEvent(player, packId, PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED),
            )

            assertFalse(controller.isAwaitingRequiredPack(player.uniqueId))
            assertTrue(continued)
            assertTrue(controller.hasResourcePack(player.uniqueId))
            controller.close()
        } finally {
            MockBukkit.unmock()
        }
    }

    @Test
    fun declineReleasesWaiterWithoutSavingUnloadedProfile() {
        val server = MockBukkit.mock()
        try {
            val plugin = MockBukkit.load(RpgPlugin::class.java)
            awaitControllers(server, plugin)
            HandlerList.unregisterAll(plugin as org.bukkit.plugin.Plugin)
            plugin.config.set("resource_pack.mode", "external_url")
            plugin.config.set("resource_pack.external_url", "https://packs.example.test/pack.zip")
            plugin.config.set("resource_pack.external_sha1", "b".repeat(40))
            plugin.config.set("resource_pack.required", true)
            val controller = ResourcePackController(plugin)
            val player = PlayerMock(server, "PackDecline", UUID.randomUUID())
            server.addPlayer(player)
            var continued = false

            controller.beginRequiredJoin(player)
            controller.whenRequiredPackReady(player) { continued = true }
            server.scheduler.performOneTick()
            val packId = UUID.nameUUIDFromBytes("etherealchronicle-resource-pack-v1".toByteArray())
            server.pluginManager.callEvent(
                PlayerResourcePackStatusEvent(player, packId, PlayerResourcePackStatusEvent.Status.DECLINED),
            )

            assertFalse(controller.isAwaitingRequiredPack(player.uniqueId))
            assertFalse(continued)
            assertFalse(player.isOnline)
            assertFalse(plugin.profiles.containsKey(player.uniqueId))
            controller.close()
        } finally {
            MockBukkit.unmock()
        }
    }

    private fun awaitControllers(server: ServerMock, plugin: RpgPlugin) {
        repeat(500) {
            if (runCatching { plugin.fishing; plugin.resourcePacks }.isSuccess) return
            server.scheduler.waitAsyncTasksFinished()
            server.scheduler.performOneTick()
            Thread.sleep(10)
        }
        error("Plugin controllers did not initialize")
    }
}
