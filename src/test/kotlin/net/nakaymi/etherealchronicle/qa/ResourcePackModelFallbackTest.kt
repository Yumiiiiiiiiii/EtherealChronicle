package net.nakaymi.etherealchronicle.qa

import net.nakaymi.etherealchronicle.paper.RpgPlugin
import org.bukkit.NamespacedKey
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.entity.PlayerMock
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** リソースパック拒否・切替時に所持品が欠損モデルにならないことを検証します。 */
class ResourcePackModelFallbackTest {
    @Test
    fun `item models are removed when pack is unavailable and restored after acceptance`() {
        val server = MockBukkit.mock()
        try {
            val plugin = MockBukkit.load(RpgPlugin::class.java)
            val readyBy = System.currentTimeMillis() + 5000
            var contentReady = runCatching { plugin.snapshot; true }.getOrDefault(false)
            while (System.currentTimeMillis() < readyBy && !contentReady) {
                server.scheduler.waitAsyncTasksFinished()
                server.scheduler.performOneTick()
                Thread.sleep(20)
                contentReady = runCatching { plugin.snapshot; true }.getOrDefault(false)
            }
            kotlin.test.assertTrue(contentReady, "content was not ready")
            val player = PlayerMock(server, "PackModel", UUID.randomUUID())
            server.addPlayer(player)
            val item = plugin.createItem("ec:item_wpn_void_cleaver")
            player.inventory.setItem(0, item)

            plugin.refreshPackItemModels(player, false)
            assertNull(player.inventory.getItem(0)!!.itemMeta.itemModel, "拒否時はバニラ表示へ戻します")

            plugin.refreshPackItemModels(player, true)
            assertEquals(NamespacedKey.fromString("ec:wpn_void_cleaver"), player.inventory.getItem(0)!!.itemMeta.itemModel)

            plugin.refreshPackItemModels(player, false)
            assertNull(player.inventory.getItem(0)!!.itemMeta.itemModel, "後から拒否した場合も既存装備からモデル指定を外します")
        } finally {
            MockBukkit.unmock()
        }
    }
}
