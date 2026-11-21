package net.nakaymi.etherealchronicle.paper

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder

/** 選択した職業IDをInventoryClickEventから回収するGUI holderです。 */
class ClassMenu : InventoryHolder {
    private val menu = Bukkit.createInventory(this, 9, Component.text("職業を選んでください"))

    override fun getInventory(): Inventory = menu
}

/** GUI種別と対象IDをクリックイベント側へ渡すInventory holderです。 */
class RpgMenu(val kind: String, val contentId: String, val nodeId: String = "") : InventoryHolder {
    private val menu = Bukkit.createInventory(this, 54, Component.text(titleFor(kind)))

    override fun getInventory(): Inventory = menu

    private fun titleFor(kind: String): String = when (kind) {
        "dialogue" -> "会話"
        "shop" -> "お店"
        "codex-areas" -> "冒険者の図鑑 — エリア"
        "codex-area" -> "冒険者の図鑑 — 魔物"
        "codex-items" -> "冒険者の図鑑 — アイテム"
        "codex-fish" -> "生活図鑑 — 魚類"
        "codex-gathering" -> "生活図鑑 — 採取"
        else -> "装備"
    }
}
