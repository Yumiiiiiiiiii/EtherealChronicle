package net.nakaymi.etherealchronicle.paper

import net.nakaymi.etherealchronicle.data.ContentService
import org.bukkit.Material
import org.bukkit.NamespacedKey
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.kyori.adventure.key.Key
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
    fun `invui title and controls use pack art only after confirmed load`() {
        val fallback=Component.text("Codex")
        val plain=PlainTextComponentSerializer.plainText()
        val fallbackTitle=UiThemeProvider.title(false,fallback,"codex-areas")
        val packedTitle=UiThemeProvider.title(true,fallback,"codex-areas")

        assertEquals(fallback,fallbackTitle)
        assertTrue(plain.serialize(packedTitle).contains('\uE102'))
        assertEquals(NamespacedKey.fromString("ec:ui_tab_codex"),UiThemeProvider.itemModel(true,"codex-areas",47,Material.FISHING_ROD,null))
        assertEquals(NamespacedKey.fromString("ec:ui_btn_next"),UiThemeProvider.itemModel(true,"codex-areas",53,Material.ARROW,null))
        assertEquals(NamespacedKey.fromString("ec:ui_btn_confirm"),UiThemeProvider.itemModel(true,"enhance",13,Material.ANVIL,null))
        assertEquals(NamespacedKey.fromString("ec:wpn_astral_piercer"),UiThemeProvider.itemModel(true,"shop",1,Material.DIAMOND_SWORD,"ec:wpn_astral_piercer"))
        assertEquals(null,UiThemeProvider.itemModel(false,"codex-areas",47,Material.FISHING_ROD,null))
    }

    @Test
    fun `safe theme modes avoid background glyphs unless full mode is explicit`() {
        val title=Component.text("Enhancement")
        val plain=PlainTextComponentSerializer.plainText()

        assertEquals(ThemeMode.ICONS_ONLY,UiThemeProvider.mode(null))
        assertEquals(ThemeMode.ICONS_ONLY,UiThemeProvider.mode("unknown"))
        assertEquals(title,UiThemeProvider.title(true,title,"enhance",ThemeMode.ICONS_ONLY))
        assertTrue(plain.serialize(UiThemeProvider.title(true,title,"enhance",ThemeMode.FULL)).contains('\uE103'))
        assertEquals(null,UiThemeProvider.itemModel(true,"enhance",13,Material.ANVIL,null,ThemeMode.OFF))
        assertEquals(NamespacedKey.fromString("ec:ui_btn_confirm"),UiThemeProvider.itemModel(true,"enhance",13,Material.ANVIL,null,ThemeMode.ICONS_ONLY))

        val fullTitle=UiThemeProvider.title(true,title,"enhance",ThemeMode.FULL)
        assertEquals(Key.key("ec", "space"), fullTitle.font())
        assertEquals(Key.key("ec", "ui"), fullTitle.children()[0].font())
        assertEquals(Key.key("minecraft","default"),fullTitle.children()[2].font())
    }

    @Test
    fun `theme mode setting updates live provider state on reload`() {
        val setting = ThemeModeSetting(ThemeMode.ICONS_ONLY)
        assertEquals(ThemeMode.ICONS_ONLY, setting.current)

        setting.update(UiThemeProvider.mode("full"))
        assertEquals(ThemeMode.FULL, setting.current)
        setting.update(UiThemeProvider.mode("off"))
        assertEquals(ThemeMode.OFF, setting.current)
    }

    @Test
    fun `class dialog tracker can be cleared on quit and escape remains available`() {
        val tracker = ClassConfirmationTracker()
        val id = UUID.randomUUID()
        assertTrue(tracker.begin(id))
        assertTrue(tracker.isActive(id))
        tracker.finish(id)
        assertFalse(tracker.isActive(id))
    }

    @Test
    fun `advancement root uses the modern texture identifier`() {
        val root = AdvancementDatapack.rootJson("Chronicle", "Records")
        assertTrue(root.contains("\"background\":\"minecraft:gui/advancements/backgrounds/adventure\""))
        assertFalse(root.contains("textures/textures/"))
    }

    @Test
    fun `quest objective npc ids resolve to content display names`() {
        val content = ContentService(Path.of("src/main/resources/content")).load()
        assertEquals("受付嬢セリア", questObjectiveLabel("ec:npc_celia", content,"Unknown objective"))
        assertEquals("Unknown objective", questObjectiveLabel("ec:unknown_target", content,"Unknown objective"))
    }

    @Test
    fun `enhancement material uses content name instead of id`() {
        val content=ContentService(Path.of("src/main/resources/content")).load()
        val material=content.items.find("ec:item_mat_artisan_whetstone")
        assertEquals("名匠の研磨砥石",material?.name)
        assertTrue(material?.name != "ec:item_mat_artisan_whetstone")
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
    fun `quest name and localized status are sent as separate language components`() {
        val server=MockBukkit.mock()
        try {
            val plugin=MockBukkit.load(RpgPlugin::class.java)
            val player=PlayerMock(server,"QuestLocale",UUID.randomUUID())
            server.addPlayer(player)
            assertTrue(await(server) { player.uniqueId in plugin.profiles })
            val profile=plugin.profiles.getValue(player.uniqueId)
            plugin.game.chooseClass(profile,"ec:class_warrior")
            while(player.nextMessage()!=null) { }

            player.performCommand("ec quest")
            val messages=generateSequence { player.nextMessage() }.toList()
            val questName=plugin.snapshot.world.quests.getValue("ec:quest_m01_awakening").name

            assertTrue(messages.any { it==questName },"content-owned quest name should remain a separate line")
            assertTrue(messages.any { it=="Status: In progress" },"English status should come from the language catalog")
            assertTrue(messages.none { it.contains("$questName:") },"a Japanese content name must not share a line with an English status")
        } finally {
            MockBukkit.unmock()
        }
    }

    @Test
    fun `enhancement menu lore shows translated whetstone name`() {
        val server=MockBukkit.mock()
        try {
            val plugin=MockBukkit.load(RpgPlugin::class.java)
            val player=PlayerMock(server,"EnhanceLocale",UUID.randomUUID())
            server.addPlayer(player)
            assertTrue(await(server) { player.uniqueId in plugin.profiles })
            val profile=plugin.profiles.getValue(player.uniqueId)
            plugin.game.chooseClass(profile,"ec:class_warrior")
            profile.classId="ec:class_warrior"
            player.inventory.setItemInMainHand(plugin.createItem("ec:item_wpn_rusty_broadsword"))

            plugin.openEnhancement(player)
            val lore=player.openInventory.topInventory.getItem(13)!!.itemMeta.lore().orEmpty()
                .joinToString(" ") { PlainTextComponentSerializer.plainText().serialize(it) }
            val materialName=plugin.snapshot.items.get("ec:item_mat_artisan_whetstone").name

            assertTrue(lore.contains(materialName),"enhancement lore should use the material display name: $lore")
            assertFalse(lore.contains("ec:item_mat_artisan_whetstone"),"internal item id leaked into lore: $lore")
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
            assertTrue(await(server) { player.uniqueId in plugin.profiles })
            val command = object : org.bukkit.command.Command("ec") {
                override fun execute(sender: org.bukkit.command.CommandSender, label: String, args: Array<out String>) = true
            }

            plugin.onCommand(server.consoleSender, command, "ec", arrayOf("class", player.name, "ec:class_warrior"))
            val warriorWeapon = "ec:item_wpn_rusty_broadsword"
            assertTrue(await(server) { count(plugin, player, warriorWeapon) == 1 }, "starter weapon was not granted; class=${plugin.profiles[player.uniqueId]?.classId}, messages=${player.nextMessage()}")
            assertEquals(warriorWeapon, plugin.identify(player.inventory.itemInMainHand))

            plugin.onCommand(server.consoleSender, command, "ec", arrayOf("class", player.name, "ec:class_cleric"))

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
            server.scheduler.performOneTick()
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }
}
