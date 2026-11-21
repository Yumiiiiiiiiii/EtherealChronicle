package net.nakaymi.etherealchronicle.paper

import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.Inventory
import xyz.xenondevs.invui.gui.Gui
import xyz.xenondevs.invui.item.Item
import xyz.xenondevs.invui.item.ItemBuilder
import xyz.xenondevs.invui.window.Window

/** 既存のスロット配置を保ったまま、Paper実行時の画面表示をInvUIへ渡します。 */
internal class InvUiInventoryAdapter(
    private val theme: UiThemeProvider,
    private val itemModel: (org.bukkit.inventory.ItemStack) -> String?,
) {
    /** 既存スロットとクリック位置を保って画面を開きます。 */
    fun open(player: Player, source: Inventory, title: Component, onClick: (Int, ClickType) -> Unit) {
        val rows = (source.size / 9).coerceIn(1, 6)
        val gui = Gui.empty(rows, 9)
        source.contents.take(rows * 9).forEachIndexed { slot, original ->
            val stack = original?.takeUnless { it.type.isAir }?.clone() ?: return@forEachIndexed
            theme.modelKey(player, itemModel(stack))?.let { key -> stack.editMeta { it.setItemModel(key) } }
            val item = Item.builder()
                .setItemProvider(ItemBuilder(stack))
                .addClickHandler { click -> onClick(slot, click.clickType()) }
                .build()
            gui.setItem(slot, item)
        }
        Window.builder()
            .setViewer(player)
            .setTitle(theme.title(player, title))
            .setUpperGui(gui)
            .build()
            .open()
    }
}
