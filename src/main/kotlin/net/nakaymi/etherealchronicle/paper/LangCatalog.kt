package net.nakaymi.etherealchronicle.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/** プレイヤー言語に合わせて翻訳キーをMiniMessageへ変換します。 */
class LangCatalog internal constructor(
    private val configuredLanguage: String,
    private val japanese: Map<String, String>,
    private val english: Map<String, String>,
    private val logger: Logger,
    /** content 配下のYAML の日本語テキストを上書きする英語カタログ（lang/content_en_us.yml）。 */
    private val contentEnglish: Map<String, String> = emptyMap()
) {
    private val warnedMissing = ConcurrentHashMap.newKeySet<String>()

    /** クライアント言語または明示設定を選び、欠落時は日本語とキー名で診断します。 */
    fun component(clientLocale: String?, key: String, placeholders: Map<String, Any?> = emptyMap()): Component {
        val selected = if (configuredLanguage == "auto") normalize(clientLocale) else configuredLanguage
        val template = when (selected) {
            "en_us" -> english[key] ?: japanese[key]
            else -> japanese[key]
        }
        if (template == null) {
            if (warnedMissing.add(key)) logger.warning("Missing language key: $key")
            return Component.text(key)
        }
        val resolvers = placeholders.map { (name, value) ->
            if (value is Component) Placeholder.component(name, value) else Placeholder.unparsed(name, value?.toString() ?: "")
        }
        return MiniMessage.miniMessage().deserialize(template, *resolvers.toTypedArray())
    }

    /** 設定またはクライアントlocaleから、使用する言語コード（en_us / ja_jp）を決めます。 */
    fun resolveLocale(clientLocale: String?): String =
        if (configuredLanguage == "auto") normalize(clientLocale) else configuredLanguage

    /**
     * コンテンツ（アイテム名・NPC名・会話など）を指定localeで返します。
     * 日本語は content 配下のYAML の原文（fallbackJa）が正本で、英語は content_en_us.yml を引き、欠落時は原文へ戻します。
     */
    fun contentText(clientLocale: String?, key: String, fallbackJa: String): String =
        if (resolveLocale(clientLocale) == "en_us") contentEnglish[key] ?: fallbackJa else fallbackJa

    /** [contentText] をそのままComponent化します（MiniMessageは解釈しません）。 */
    fun contentComponent(clientLocale: String?, key: String, fallbackJa: String): Component =
        Component.text(contentText(clientLocale, key, fallbackJa))

    /** 共有表示用の翻訳可能Component。リソースパック未適用のクライアントは fallback を表示します。 */
    fun translatable(key: String, fallbackJa: String): Component = Component.translatable(key, fallbackJa)

    /** テスト用: 英語コンテンツカタログのキー一覧。 */
    internal fun contentEnglishEntries(): Map<String, String> = contentEnglish

    /** 検証診断などに埋め込まれたec.*エラーキーだけを選択言語へ展開します。 */
    fun diagnostic(clientLocale: String?, text: String): Component {
        val selected = if (configuredLanguage == "auto") normalize(clientLocale) else configuredLanguage
        val catalog = if (selected == "en_us") english else japanese
        val translated = ERROR_KEY.replace(text) { match ->
            val key = match.groupValues[1]
            val template = catalog[key] ?: japanese[key] ?: key.also {
                if (warnedMissing.add(key)) logger.warning("Missing language key: $key")
            }
            val detail = match.groups[2]?.value?.takeIf(String::isNotBlank) ?: return@replace template
            if (DETAIL_PLACEHOLDER.containsMatchIn(template)) {
                // replacement callback keeps '$' and '\\' in diagnostic details literal.
                DETAIL_PLACEHOLDER.replace(template) { detail }
            } else "$template: $detail"
        }
        return Component.text(translated)
    }

    /** テストと用語一覧生成用に、翻訳キーから日本語・英語を返します。 */
    internal fun entries(): Map<String, Pair<String?, String?>> =
        (japanese.keys + english.keys).associateWith { key -> japanese[key] to english[key] }

    private fun normalize(locale: String?): String = normalizeLocale(locale)

    companion object {
        /** あらゆる英語locale（en, en_us, en_gb, en_au …）を en_us へ、それ以外は ja_jp へ寄せます。 */
        fun normalizeLocale(locale: String?): String {
            val code = locale?.lowercase(Locale.ROOT)?.replace('-', '_') ?: return "ja_jp"
            return if (code == "en" || code.startsWith("en_")) "en_us" else "ja_jp"
        }
        private val ERROR_KEY = Regex("(ec(?:\\.[a-z0-9_]+)+)(?::\\s*([^\\n]+))?")
        private val DETAIL_PLACEHOLDER = Regex("<(?!/?(?:aqua|black|blue|dark_aqua|dark_blue|dark_gray|dark_green|dark_purple|dark_red|gold|gray|green|italic|light_purple|red|reset|white|yellow|bold|underlined|strikethrough|obfuscated|newline|b|i|u|st|obf|br)(?=>)|#[0-9a-fA-F]{6}(?=>))([a-zA-Z][a-zA-Z0-9_]*)>")
        internal fun detailPlaceholderMatches(template: String): List<String> = DETAIL_PLACEHOLDER.findAll(template).map { it.groupValues[1] }.toList()
        /** 言語ファイルを読み込みます。既存サーバー編集は初期化側のhash管理で保護します。 */
        fun load(
            dataFolder: Path,
            configuredLanguage: String,
            logger: Logger,
            bundledDefaults: Map<String, ByteArray> = emptyMap()
        ): LangCatalog {
            val languageFolder = dataFolder.resolve("lang")
            return LangCatalog(
                normalizeSetting(configuredLanguage),
                read(languageFolder.resolve("ja_jp.yml"), bundledDefaults["lang/ja_jp.yml"]),
                read(languageFolder.resolve("en_us.yml"), bundledDefaults["lang/en_us.yml"]),
                logger,
                read(languageFolder.resolve("content_en_us.yml"), bundledDefaults["lang/content_en_us.yml"])
            )
        }

        /** 起動待ちコマンドで使う同梱翻訳を、ファイルIOなしで初期化します。 */
        fun fromBundledDefaults(
            configuredLanguage: String,
            logger: Logger,
            bundledDefaults: Map<String, ByteArray>,
        ): LangCatalog = LangCatalog(
            normalizeSetting(configuredLanguage),
            readBundled(bundledDefaults["lang/ja_jp.yml"]),
            readBundled(bundledDefaults["lang/en_us.yml"]),
            logger,
            readBundled(bundledDefaults["lang/content_en_us.yml"]),
        )

        private fun normalizeSetting(value: String): String = when (value.lowercase(Locale.ROOT)) {
            "auto", "ja_jp", "en_us" -> value.lowercase(Locale.ROOT)
            else -> "auto"
        }

        private fun read(path: Path, bundledDefault: ByteArray?): Map<String, String> {
            val yaml = when {
                Files.exists(path) -> YamlConfiguration.loadConfiguration(path.toFile())
                bundledDefault != null -> YamlConfiguration.loadConfiguration(
                    bundledDefault.toString(Charsets.UTF_8).reader()
                )
                else -> return emptyMap()
            }
            val result = linkedMapOf<String, String>()
            collect(yaml, "", result)
            return result
        }

        private fun readBundled(bytes: ByteArray?): Map<String, String> {
            if (bytes == null) return emptyMap()
            val yaml = YamlConfiguration.loadConfiguration(bytes.toString(Charsets.UTF_8).reader())
            return linkedMapOf<String, String>().also { collect(yaml, "", it) }
        }

        private fun collect(section: ConfigurationSection, prefix: String, output: MutableMap<String, String>) {
            section.getKeys(false).forEach { child ->
                val path = if (prefix.isEmpty()) child else "$prefix.$child"
                val nested = section.getConfigurationSection(child)
                if (nested != null) collect(nested, path, output)
                else section.getString(child)?.let { output[path] = it }
            }
        }
    }
}
