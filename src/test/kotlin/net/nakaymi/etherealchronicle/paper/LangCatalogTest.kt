package net.nakaymi.etherealchronicle.paper

import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Logger
import net.nakaymi.etherealchronicle.data.DefaultContentInstaller
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertTrue

/** 翻訳リソースの同期、プレースホルダー、利用一覧の鮮度を検証します。 */
class LangCatalogTest {
    /** 言語設定、未知localeの日本語フォールバック、値のunparsed置換を確認します。 */
    @Test
    fun selectsLanguageAndEscapesPlaceholderValues() {
        val catalog = LangCatalog(
            "auto",
            mapOf("ec.test.greeting" to "こんにちは、<name>"),
            mapOf("ec.test.greeting" to "Hello, <name>"),
            Logger.getLogger("LangCatalogTest")
        )
        val japanese = catalog.component("fr_fr", "ec.test.greeting", mapOf("name" to "<red>旅人"))
        val english = catalog.component("en_US", "ec.test.greeting", mapOf("name" to "<red>traveler"))

        assertTrue(japanese.toString().contains("こんにちは"))
        assertTrue(japanese.toString().contains("<red>旅人"))
        assertTrue(english.toString().contains("Hello"))
        assertTrue(english.toString().contains("<red>traveler"))
    }

    /** 言語ファイルは独立manifestを使い、編集済み同梱ファイルを上書きしないことを確認します。 */
    @Test
    fun languageInstallerKeepsServerEditsAndSeparateManifest() {
        val root = Files.createTempDirectory("ec-lang-install")
        val initial = mapOf("lang/ja_jp.yml" to "first".toByteArray())
        DefaultContentInstaller.installTracked(root, initial, manifestFile = "lang-manifest.properties")
        Files.writeString(root.resolve("lang/ja_jp.yml"), "server edit")

        val result = DefaultContentInstaller.installTracked(
            root,
            mapOf("lang/ja_jp.yml" to "second".toByteArray()),
            manifestFile = "lang-manifest.properties"
        )

        assertEquals(listOf("lang/ja_jp.yml"), result.changed)
        assertEquals("server edit", Files.readString(root.resolve("lang/ja_jp.yml")))
        assertTrue(Files.exists(root.resolve("lang-manifest.properties")))
        assertFalse(Files.exists(root.resolve("content-manifest.properties")))
    }

    @Test
    fun bundledLanguagesAndGeneratedReferenceStayInSync() {
        val root = Path.of("").toAbsolutePath()
        val temporaryData = Files.createTempDirectory("ec-language-test")
        val languageDirectory = Files.createDirectories(temporaryData.resolve("lang"))
        listOf("ja_jp.yml", "en_us.yml").forEach { name ->
            val bytes = javaClass.getResourceAsStream("/lang/$name")!!.use { it.readBytes() }
            Files.write(languageDirectory.resolve(name), bytes)
        }

        val catalog = LangCatalog.load(temporaryData, "auto", Logger.getLogger("LangCatalogTest"))
        val entries = catalog.entries()
        assertTrue(entries.values.all { (japanese, english) -> japanese != null && english != null })
        assertEquals(
            entries.mapValues { (_, values) -> placeholders(values.first!!) },
            entries.mapValues { (_, values) -> placeholders(values.second!!) }
        )

        val references = linkedMapOf<String, MutableSet<String>>()
        Files.walk(root.resolve("src/main/kotlin")).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }.forEach { source ->
                val text = Files.readString(source)
                KEY.findAll(text).forEach { match ->
                    references.getOrPut(match.groupValues[1]) { sortedSetOf() }
                        .add(root.relativize(source).toString().replace('\\', '/'))
                }
            }
        }
        assertEquals(entries.keys, references.keys, "翻訳キーはコードで全て使用し、参照先の定義を同梱してください")

        val rows = entries.toSortedMap().map { (key, values) ->
            val langs = values.first!! to values.second!!
            "| `$key` | ${escape(langs.first)} | ${escape(langs.second)} | ${references.getValue(key).joinToString("<br>")} | ${placeholders(langs.first).joinToString(", ") { "`<$it>`" }} |"
        }
        val expected = (DOC_HEADER + rows + DOC_FOOTER).joinToString("\n", postfix = "\n")
        assertEquals(expected, Files.readString(root.resolve("docs/LANGUAGE.md")), "docs/LANGUAGE.md を再生成してください")
    }

    private fun placeholders(value: String): Set<String> = PLACEHOLDER.findAll(value).map { it.groupValues[1] }.toSortedSet()
    private fun escape(value: String): String = value.replace("|", "\\|").replace("\n", "<br>")

    private companion object {
        val KEY = Regex("\\\"(ec(?:\\.[a-z0-9_]+)+)\\\"")
        val PLACEHOLDER = Regex("<(?!aqua|black|blue|dark_aqua|dark_blue|dark_gray|dark_green|dark_purple|dark_red|gold|gray|green|italic|light_purple|red|reset|white|yellow)([a-zA-Z][a-zA-Z0-9_]*)>")
        val DOC_HEADER = listOf(
            "# 言語ファイルと翻訳キー",
            "",
            "表示文言はMiniMessage形式です。`plugins/EtherealChronicle/lang/ja_jp.yml` と `en_us.yml` は初回配置後、サーバー編集を優先します。未編集の同梱ファイルだけ次の配布版へ更新します。",
            "",
            "設定 `language: auto` はプレイヤーのクライアント言語に追従し、未対応言語は日本語を選びます。`ja_jp` / `en_us` を指定するとサーバー全体で固定します。存在しないキーは日本語、それも無い場合はキー名を表示し、警告はキーごとに一度だけ記録します。",
            "",
            "| キー | 日本語 | English | 使用箇所 | プレースホルダ |",
            "| --- | --- | --- | --- | --- |"
        )
        val DOC_FOOTER = listOf(
            "",
            "## コンテンツYAMLの多言語化案",
            "将来は `name_key` / `description_key` を追加し、同じ `ec.*` 翻訳表を参照します。既存の `name` / `description` は後方互換のフォールバックとして維持し、コンテンツschemaと保存形式を一括変更しない段階導入にします。"
        )
    }
}
