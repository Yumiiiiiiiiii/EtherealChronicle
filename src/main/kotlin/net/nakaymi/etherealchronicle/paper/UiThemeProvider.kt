package net.nakaymi.etherealchronicle.paper

import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin

/** リソースパック成功状態を唯一の判断元にして、カスタム見た目とバニラ表示を切り替えます。 */
class UiThemeProvider(private val plugin: JavaPlugin, private val packs: ResourcePackController) {
    fun hasPack(player: Player): Boolean = packs.hasResourcePack(player.uniqueId)

    fun title(player: Player, localizedTitle: Component): Component = localizedTitle

    /** 表示時点で確認済みのクライアントだけにカスタムモデルキーを付けます。 */
    fun modelKey(player: Player, model: String?): NamespacedKey? = modelKey(hasPack(player), model)

    fun icon(player: Player, fallback: Material, model: String?): ItemStack = ItemStack(fallback).also { stack ->
        val key = modelKey(player, model) ?: return@also
        stack.editMeta { it.setItemModel(key) }
    }

    companion object {
        /** リソパ未適用時はカスタムitem_modelを一切送らず、バニラ素材へ戻します。 */
        fun modelKey(hasPack: Boolean, model: String?): NamespacedKey? {
            if (!hasPack || model.isNullOrBlank()) return null
            return NamespacedKey.fromString(model)
        }
    }
}
