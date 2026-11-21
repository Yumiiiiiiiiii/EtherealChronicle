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
    private val logger: Logger
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
        val resolvers = placeholders.map { (name, value) -> Placeholder.unparsed(name, value?.toString() ?: "") }
        return MiniMessage.miniMessage().deserialize(template, *resolvers.toTypedArray())
    }

    /** テストと用語一覧生成用に、翻訳キーから日本語・英語を返します。 */
    internal fun entries(): Map<String, Pair<String?, String?>> =
        (japanese.keys + english.keys).associateWith { key -> japanese[key] to english[key] }

    private fun normalize(locale: String?): String = when (locale?.lowercase(Locale.ROOT)?.replace('-', '_')) {
        "en_us", "en", "en_gb", "en_ca", "en_au" -> "en_us"
        else -> "ja_jp"
    }

    companion object {
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
                logger
            )
        }

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
