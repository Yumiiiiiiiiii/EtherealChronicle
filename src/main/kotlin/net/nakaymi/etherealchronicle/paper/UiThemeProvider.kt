package net.nakaymi.etherealchronicle.paper

import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin

/** リソースパックの適用状態に応じてGUIの背景・アイコンと安全な代替表示を組み立てます。 */
class UiThemeProvider(private val plugin: JavaPlugin, private val packs: ResourcePackController) {
    /** 接続中のプレイヤーがパックを適用済みか返します。 */
    fun hasPack(player: Player): Boolean = packs.hasResourcePack(player.uniqueId)

    /** パック適用済みなら画面種別に合う背景グリフをタイトルへ重ねます。 */
    fun title(player: Player, localizedTitle: Component, menuKind: String = "generic"): Component =
        title(hasPack(player),localizedTitle,menuKind)

    /** パック適用済みならカスタムアイコンを使い、未適用ならバニラ素材のまま返します。 */
    fun icon(player: Player, fallback: Material, model: String?): ItemStack = ItemStack(fallback).also { stack ->
        modelKey(player,model)?.let { key -> stack.editMeta { it.setItemModel(key) } }
    }

    /** 内容アイテムのモデル、またはGUI操作用の同梱モデルを返します。 */
    fun itemModel(player: Player, menuKind: String, slot: Int, material: Material, contentModel: String?): NamespacedKey? =
        itemModel(hasPack(player),menuKind,slot,material,contentModel)

    /** 表示時点で確認済みのクライアントだけにカスタムモデルキーを付けます。 */
    fun modelKey(player: Player, model: String?): NamespacedKey? = modelKey(hasPack(player),model)

    companion object {
        private val uiFont = Key.key("ec","ui")
        private val spaceFont = Key.key("ec","space")
        private val plainText = PlainTextComponentSerializer.plainText()
        private val spaceAdvances = listOf(128,64,32,16,8,4,2,1)

        /** テスト・フォールバック表示用に、言語テキストへ必要な背景グリフを合成します。 */
        internal fun title(hasPack: Boolean, localizedTitle: Component, menuKind: String): Component {
            if (!hasPack) return localizedTitle
            val glyph = backgroundGlyph(menuKind)
            val width = approximateTitleWidth(localizedTitle)
            val sidePadding = ((BACKGROUND_WIDTH - width) / 2).coerceAtLeast(0)
            return Component.text(glyph).font(uiFont)
                .append(Component.text(spaces(-BACKGROUND_WIDTH + sidePadding)).font(spaceFont))
                .append(localizedTitle)
                .append(Component.text(spaces(sidePadding)).font(spaceFont))
        }

        /** メニュー固有のボタンモデルを選び、未適用クライアントへは送信しません。 */
        internal fun itemModel(
            hasPack: Boolean,
            menuKind: String,
            slot: Int,
            material: Material,
            contentModel: String?,
        ): NamespacedKey? {
            if (!hasPack) return null
            val model = contentModel ?: when {
                menuKind.startsWith("codex") && slot in 47..49 -> "ec:ui_tab_codex"
                menuKind.startsWith("codex") && slot == 53 && material == Material.ARROW -> "ec:ui_btn_next"
                menuKind.startsWith("codex") && material == Material.ARROW -> "ec:ui_btn_prev"
                menuKind == "enhance" && slot == 13 -> "ec:ui_btn_confirm"
                else -> null
            }
            return modelKey(true,model)
        }

        /** パック未適用時はカスタムitem_modelを一切送らず、バニラ素材へ戻します。 */
        fun modelKey(hasPack: Boolean, model: String?): NamespacedKey? {
            if (!hasPack || model.isNullOrBlank()) return null
            return NamespacedKey.fromString(model)
        }

        private fun backgroundGlyph(menuKind: String): String = when {
            menuKind == "class" -> "\uE100"
            menuKind == "equipment" || menuKind == "status" -> "\uE101"
            menuKind.startsWith("codex") -> "\uE102"
            menuKind == "enhance" -> "\uE103"
            menuKind == "shop" -> "\uE104"
            else -> "\uE105"
        }

        private fun approximateTitleWidth(title: Component): Int = plainText.serialize(title).codePoints()
            .toArray()
            .sumOf { codePoint -> if (Character.UnicodeScript.of(codePoint) in WIDE_SCRIPTS) 8 else 6 }
            .coerceIn(1,BACKGROUND_WIDTH - 1)

        private fun spaces(offset: Int): String {
            if (offset == 0) return ""
            var remaining = kotlin.math.abs(offset)
            val base = if (offset < 0) 0xE001 else 0xE011
            return buildString {
                spaceAdvances.forEachIndexed { index, advance ->
                    while (remaining >= advance) {
                        appendCodePoint(base + (spaceAdvances.lastIndex - index))
                        remaining -= advance
                    }
                }
            }
        }

        private const val BACKGROUND_WIDTH = 176
        private val WIDE_SCRIPTS = setOf(Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA)
    }
}
