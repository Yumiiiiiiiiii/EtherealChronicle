package net.nakaymi.etherealchronicle.paper

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder

/** 選択した職業IDをInventoryClickEventから回収するGUI holderです。 */
class ClassMenu(title: Component) : InventoryHolder {
    private val menu = Bukkit.createInventory(this, 9, title)

    override fun getInventory(): Inventory = menu
}

/** GUI種別と対象IDをクリックイベント側へ渡すInventory holderです。 */
class RpgMenu(val kind: String, val contentId: String, val nodeId: String = "", val title: Component) : InventoryHolder {
    private val menu = Bukkit.createInventory(this, 54, title)

    override fun getInventory(): Inventory = menu
}
