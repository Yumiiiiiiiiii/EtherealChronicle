package net.nakaymi.etherealchronicle.paper

import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.kyori.adventure.text.format.TextColor
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin

/** リソースパックの適用状態に応じてGUIの背景・アイコンと安全な代替表示を組み立てます。 */
class UiThemeProvider(
    private val plugin: JavaPlugin,
    private val packs: ResourcePackController,
    mode: ThemeMode = ThemeMode.ICONS_ONLY,
) {
    private val modeSetting = ThemeModeSetting(mode)

    /** `/ec reload` が読み直した表示設定を、既存UIプロバイダーへ反映します。 */
    fun updateMode(mode: ThemeMode) = modeSetting.update(mode)

    /** 接続中のプレイヤーがパックを適用済みか返します。 */
    fun hasPack(player: Player): Boolean = packs.hasResourcePack(player.uniqueId)

    /** パック適用済みなら画面種別に合う背景グリフをタイトルへ重ねます。 */
    fun title(player: Player, localizedTitle: Component, menuKind: String = "generic"): Component =
        title(hasPack(player),localizedTitle,menuKind,modeSetting.current)

    /** パック適用済みならカスタムアイコンを使い、未適用ならバニラ素材のまま返します。 */
    fun icon(player: Player, fallback: Material, model: String?): ItemStack = ItemStack(fallback).also { stack ->
        modelKey(player,model)?.let { key -> stack.editMeta { it.setItemModel(key) } }
    }

    /** 内容アイテムのモデル、またはGUI操作用の同梱モデルを返します。 */
    fun itemModel(player: Player, menuKind: String, slot: Int, material: Material, contentModel: String?): NamespacedKey? =
        itemModel(hasPack(player),menuKind,slot,material,contentModel,modeSetting.current)

    /** 表示時点で確認済みのクライアントだけにカスタムモデルキーを付けます。 */
    fun modelKey(player: Player, model: String?): NamespacedKey? =
        if (modeSetting.current == ThemeMode.OFF) null else modelKey(hasPack(player),model)

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
            return Component.text(spaces(TITLE_LEFT_OFFSET)).font(spaceFont)
                .append(Component.text(glyph).font(uiFont).color(NamedTextColor.WHITE))
                .append(Component.text(spaces(-(BACKGROUND_WIDTH + 1) + sidePadding)).font(spaceFont))
                // 子要素へ背景フォントが継承されると通常のタイトル文字が豆腐になるため標準フォントを明示します。
                .append(localizedTitle.font(defaultFont).color(TextColor.color(0xF5E6C8)))
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
            val model = contentModel ?: UI_MODEL_RULES.firstNotNullOfOrNull { rule ->
                rule.model.takeIf { rule.matches(menuKind,slot,material) }
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

        private const val BACKGROUND_WIDTH = ChestLayout.BACKGROUND_WIDTH_PIXELS
        internal const val TITLE_LEFT_OFFSET = -8
        private val WIDE_SCRIPTS = setOf(Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA)
        private data class UiModelRule(val model: String, val matches: (String,Int,Material) -> Boolean)
        private val UI_MODEL_RULES = listOf(
            UiModelRule("ec:ui_tab_codex") { kind,slot,_ -> kind.startsWith("codex") && slot in CodexLayout.FISH_TAB_SLOT..CodexLayout.ITEM_TAB_SLOT },
            UiModelRule("ec:ui_btn_next") { kind,slot,material -> kind.startsWith("codex") && slot==CodexLayout.NEXT_PAGE_SLOT && material==Material.ARROW },
            UiModelRule("ec:ui_btn_prev") { kind,_,material -> kind.startsWith("codex") && material==Material.ARROW },
            UiModelRule("ec:ui_btn_confirm") { kind,slot,_ -> kind=="enhance" && slot==EnhanceLayout.CONFIRM_SLOT },
        )
    }
}

/** GUIテーマの安全度を選びます。通常運用ではアイコンだけの安定表示を使います。 */
enum class ThemeMode { FULL, ICONS_ONLY, OFF }

/** GUIのテーマ設定を保持し、管理者の設定再読込を既存画面へ反映します。 */
internal class ThemeModeSetting(initial: ThemeMode) {
    @Volatile
    var current: ThemeMode = initial
        private set

    fun update(mode: ThemeMode) {
        current = mode
    }
}
