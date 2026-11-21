package net.nakaymi.etherealchronicle.paper

import net.nakaymi.etherealchronicle.data.ContentService
import org.bukkit.Material
import org.bukkit.inventory.ItemFlag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.PlayerMock
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** QAで確認された進捗背景、クエスト表示名、薬品ツールチップ、職業武器切替を検証します。 */
class UiQAFixesTest {
    @Test
    fun `advancement root uses the modern texture identifier`() {
        val root = AdvancementDatapack.rootJson("Chronicle", "Records")
        assertTrue(root.contains("\"background\":\"minecraft:gui/advancements/backgrounds/adventure\""))
        assertFalse(root.contains("textures/textures/"))
    }

    @Test
    fun `quest objective npc ids resolve to content display names`() {
        val content = ContentService(Path.of("src/main/resources/content")).load()
        assertEquals("受付嬢セリア", questObjectiveLabel("ec:npc_celia", content))
        assertEquals("ec:unknown_target", questObjectiveLabel("ec:unknown_target", content))
    }

    @Test
    fun `custom potion hides vanilla additional tooltip`() {
        val server = MockBukkit.mock()
        try {
            val plugin = MockBukkit.load(RpgPlugin::class.java)
            assertTrue(await(server) { plugin.isEnabled && runCatching { plugin.snapshot }.isSuccess })
            val potion = plugin.createItem("ec:item_con_minor_hp_potion")
            assertEquals(Material.POTION, potion.type)
            assertTrue(ItemFlag.HIDE_ADDITIONAL_TOOLTIP in potion.itemMeta.itemFlags)
        } finally {
            MockBukkit.unmock()
        }
    }

    @Test
    fun `admin class change equips one bound starter weapon and retains the old one`() {
        val server = MockBukkit.mock()
        try {
            val plugin = MockBukkit.load(RpgPlugin::class.java)
            assertTrue(await(server) { plugin.isEnabled && runCatching { plugin.snapshot }.isSuccess })
            val player = PlayerMock(server, "ClassSwap", UUID.nameUUIDFromBytes("class-swap".toByteArray()))
            server.addPlayer(player)
            player.addAttachment(plugin,"etherealchronicle.admin",true)
            assertTrue(await(server) { player.uniqueId in plugin.profiles })

            player.performCommand("ec class ${player.name} ec:class_warrior")
            val warriorWeapon = "ec:item_wpn_rusty_broadsword"
            assertTrue(await(server) { count(plugin, player, warriorWeapon) == 1 })
            assertEquals(warriorWeapon, plugin.identify(player.inventory.itemInMainHand))

            player.performCommand("ec class ${player.name} ec:class_cleric")

            val clericWeapon = "ec:item_wpn_novice_censer"
            assertEquals("ec:class_cleric", plugin.profiles.getValue(player.uniqueId).classId)
            assertEquals(clericWeapon, plugin.identify(player.inventory.itemInMainHand))
            assertEquals(1, count(plugin, player, clericWeapon))
            assertEquals(1, count(plugin, player, warriorWeapon))
            assertTrue(plugin.adventure.bound(player.inventory.contents.filterNotNull().first { plugin.identify(it) == warriorWeapon }))
        } finally {
            MockBukkit.unmock()
        }
    }

    private fun count(plugin: RpgPlugin, player: PlayerMock, id: String): Int =
        player.inventory.contents.filterNotNull().filter { plugin.identify(it) == id }.sumOf { it.amount }

    private fun await(server: ServerMock, condition: () -> Boolean): Boolean {
        repeat(300) {
            server.scheduler.waitAsyncTasksFinished()
            server.scheduler.performOneTick()
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }
}
