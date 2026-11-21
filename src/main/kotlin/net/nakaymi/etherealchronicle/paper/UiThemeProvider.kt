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
class UiThemeProvider(
    private val plugin: JavaPlugin,
    private val packs: ResourcePackController,
    private val mode: ThemeMode = ThemeMode.ICONS_ONLY,
) {
    /** 接続中のプレイヤーがパックを適用済みか返します。 */
    fun hasPack(player: Player): Boolean = packs.hasResourcePack(player.uniqueId)

    /** パック適用済みなら画面種別に合う背景グリフをタイトルへ重ねます。 */
    fun title(player: Player, localizedTitle: Component, menuKind: String = "generic"): Component =
        title(hasPack(player),localizedTitle,menuKind,mode)

    /** パック適用済みならカスタムアイコンを使い、未適用ならバニラ素材のまま返します。 */
    fun icon(player: Player, fallback: Material, model: String?): ItemStack = ItemStack(fallback).also { stack ->
        modelKey(player,model)?.let { key -> stack.editMeta { it.setItemModel(key) } }
    }

    /** 内容アイテムのモデル、またはGUI操作用の同梱モデルを返します。 */
    fun itemModel(player: Player, menuKind: String, slot: Int, material: Material, contentModel: String?): NamespacedKey? =
        itemModel(hasPack(player),menuKind,slot,material,contentModel,mode)

    /** 表示時点で確認済みのクライアントだけにカスタムモデルキーを付けます。 */
    fun modelKey(player: Player, model: String?): NamespacedKey? = modelKey(hasPack(player),model)

    companion object {
        private val uiFont = Key.key("ec","ui")
        private val spaceFont = Key.key("ec","space")
        private val plainText = PlainTextComponentSerializer.plainText()
        private val spaceAdvances = listOf(128,64,32,16,8,4,2,1)
        private val defaultFont = Key.key("minecraft", "default")

        /** 設定値を安全な表示モードへ変換します。不正値では背景を使わない方式に戻します。 */
        fun mode(value: String?): ThemeMode = when (value?.trim()?.lowercase()) {
            "full" -> ThemeMode.FULL
            "off" -> ThemeMode.OFF
            else -> ThemeMode.ICONS_ONLY
        }

        /** テスト・フォールバック表示用に、言語テキストへ必要な背景グリフを合成します。 */
        internal fun title(
            hasPack: Boolean,
            localizedTitle: Component,
            menuKind: String,
            mode: ThemeMode = ThemeMode.FULL,
        ): Component {
            if (!hasPack || mode != ThemeMode.FULL) return localizedTitle
            val glyph = backgroundGlyph(menuKind)
            val width = approximateTitleWidth(localizedTitle)
            val sidePadding = ((BACKGROUND_WIDTH - width) / 2).coerceAtLeast(0)
            return Component.text(glyph).font(uiFont)
                .append(Component.text(spaces(-BACKGROUND_WIDTH + sidePadding)).font(spaceFont))
                // 子要素へ背景フォントが継承されると通常のタイトル文字が豆腐になるため標準フォントを明示します。
                .append(localizedTitle.font(defaultFont))
                .append(Component.text(spaces(sidePadding)).font(spaceFont))
        }

        /** メニュー固有のボタンモデルを選び、未適用クライアントへは送信しません。 */
        internal fun itemModel(
            hasPack: Boolean,
            menuKind: String,
            slot: Int,
            material: Material,
            contentModel: String?,
            mode: ThemeMode = ThemeMode.FULL,
        ): NamespacedKey? {
            if (!hasPack || mode == ThemeMode.OFF) return null
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

        /** 背景資産の検証テストで、画面種別に対応するコードポイントを参照します。 */
        internal fun backgroundCodepoint(menuKind: String): Int = backgroundGlyph(menuKind).codePointAt(0)

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

/** GUIテーマの安全度を選びます。通常運用ではアイコンだけの安定表示を使います。 */
enum class ThemeMode { FULL, ICONS_ONLY, OFF }
