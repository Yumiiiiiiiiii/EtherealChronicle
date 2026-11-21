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
    fun open(player: Player, source: Inventory, title: Component, menuKind: String, onClick: (Int, ClickType) -> Unit) {
        val grid = InventoryGrid.forInventorySize(source.size)
        val gui = Gui.empty(grid.width, grid.height)
        source.contents.take(grid.slotCount).forEachIndexed { slot, original ->
            val stack = original?.takeUnless { it.type.isAir }?.clone() ?: return@forEachIndexed
            theme.itemModel(player,menuKind,slot,stack.type,itemModel(stack))?.let { key -> stack.editMeta { it.setItemModel(key) } }
            val item = Item.builder()
                .setItemProvider(ItemBuilder(stack))
                .addClickHandler { click -> onClick(slot, click.clickType()) }
                .build()
            gui.setItem(slot, item)
        }
        Window.builder()
            .setViewer(player)
            .setTitle(theme.title(player,title,menuKind))
            .setUpperGui(gui)
            .build()
            .open()
    }
}

/** InvUIへ渡すチェスト画面の列数・行数を表します。 */
internal data class InventoryGrid(val width: Int, val height: Int) {
    val slotCount: Int get() = width * height

    companion object {
        /** Bukkitの9列インベントリをInvUIの幅・高さ順へ変換します。 */
        fun forInventorySize(size: Int): InventoryGrid {
            val height = (size / 9).coerceIn(1, 6)
            return InventoryGrid(width = 9, height = height)
        }
    }
}
