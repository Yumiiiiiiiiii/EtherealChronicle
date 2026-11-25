package net.nakaymi.etherealchronicle.i18n

import com.google.gson.JsonParser
import net.nakaymi.etherealchronicle.core.ContentKeys
import net.nakaymi.etherealchronicle.paper.LangCatalog
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 英語クライアントに日本語が露出しないよう、コンテンツ・UI・リソースパックの翻訳完全性を機械検証します。 */
class LocalizationCompletenessTest {
    private val root: Path = Path.of("").toAbsolutePath()
    private val japanese = Regex("[\\u3040-\\u30FF\\u4E00-\\u9FFF]")

    /** content 配下のYAML の日本語テキストを（翻訳キー, 原文）として列挙します。キー規則はContentKeysと同じです。 */
    private fun contentStrings(): List<Pair<String, String>> {
        val result = ArrayList<Pair<String, String>>()
        val typeByFolder = mapOf(
            "bosses" to "boss", "enemies" to "enemy", "items" to "item", "npcs" to "npc", "quests" to "quest",
            "skills" to "skill", "classes" to "class", "titles" to "title", "shops" to "shop", "fish_species" to "fish",
            "fishing_spots" to "fishing_spot", "gathering_nodes" to "gathering", "chapters" to "chapter", "dialogues" to "dialogue",
        )
        Files.walk(root.resolve("src/main/resources/content")).use { paths ->
            paths.filter { it.toString().endsWith(".yml") }.forEach { file ->
                val folder = file.parent.fileName.toString()
                val data = Yaml().load<Any?>(Files.readString(file)) as? Map<*, *> ?: return@forEach
                val id = data["id"] as? String ?: file.fileName.toString().removeSuffix(".yml")
                val type = typeByFolder[folder]
                fun add(key: String, value: Any?) { if (value is String && japanese.containsMatchIn(value)) result += key to value }
                if (type == null) return@forEach
                val base = ContentKeys.key(type, id, "")
                add(base + "name", data["name"])
                if (folder == "items") add(base + "lore", data["lore"])
                if (folder == "fish_species") add(base + "lore_hint", data["lore_hint"])
                if (folder == "titles") {
                    add(base + "desc", data["description"])
                    val advancement = data["advancement"] as? Map<*, *>
                    add(ContentKeys.key("advancement", id, "title"), advancement?.get("title"))
                    add(ContentKeys.key("advancement", id, "desc"), advancement?.get("description"))
                }
                if (folder == "bosses" || folder == "enemies") (data["specials"] as? List<*>).orEmpty().forEach {
                    val special = it as Map<*, *>
                    add(base + "special.${special["id"]}.name", special["name"])
                }
                if (folder == "chapters") {
                    (data["areas"] as? List<*>).orEmpty().forEach {
                        val area = it as Map<*, *>
                        add(ContentKeys.key("area", area["id"] as String, "name"), area["name"])
                    }
                    (data["dungeon_configs"] as? List<*>).orEmpty().forEach {
                        val dungeon = it as Map<*, *>
                        add(ContentKeys.key("dungeon", dungeon["id"] as String, "entry_message"), dungeon["entry_message"])
                        add(ContentKeys.key("dungeon", dungeon["id"] as String, "clear_message"), dungeon["clear_message"])
                    }
                }
                if (folder == "dialogues") {
                    (data["nodes"] as? Map<*, *>).orEmpty().forEach { (node, value) ->
                        val body = value as Map<*, *>
                        add("$base$node.text", body["text"])
                        (body["choices"] as? List<*>).orEmpty().forEach { choice ->
                            val entry = choice as Map<*, *>
                            add("$base$node.choice.${entry["id"]}", entry["label"])
                        }
                    }
                    (data["global_choices"] as? List<*>).orEmpty().forEach { choice ->
                        val entry = choice as Map<*, *>
                        add("${base}global.choice.${entry["id"]}", entry["label"])
                    }
                }
            }
        }
        return result
    }

    /** YAML内の全スカラーから日本語を含む文字列を集めます（キー規則の取りこぼし検出用）。 */
    private fun japaneseScalars(node: Any?, out: MutableList<String>) {
        when (node) {
            is String -> if (japanese.containsMatchIn(node)) out += node
            is Map<*, *> -> node.values.forEach { japaneseScalars(it, out) }
            is List<*> -> node.forEach { japaneseScalars(it, out) }
        }
    }

    private fun yamlLeaves(text: String): Map<String, String> {
        val yaml = YamlConfiguration.loadConfiguration(text.reader())
        val result = linkedMapOf<String, String>()
        fun walk(section: ConfigurationSection, prefix: String) {
            section.getKeys(false).forEach { key ->
                val path = if (prefix.isEmpty()) key else "$prefix.$key"
                val child = section.getConfigurationSection(key)
                if (child != null) walk(child, path) else section.getString(key)?.let { result[path] = it }
            }
        }
        walk(yaml, "")
        return result
    }

    @Test
    fun `every japanese content string has a clean english translation`() {
        val english = yamlLeaves(Files.readString(root.resolve("src/main/resources/lang/content_en_us.yml")))
        val strings = contentStrings()
        val everyJapanese = ArrayList<String>()
        Files.walk(root.resolve("src/main/resources/content")).use { paths ->
            paths.filter { it.toString().endsWith(".yml") }.forEach { japaneseScalars(Yaml().load<Any?>(Files.readString(it)), everyJapanese) }
        }
        val covered = strings.map { it.second }.toSet()
        val unmapped = everyJapanese.filterNot { it in covered }
        assertTrue(unmapped.isEmpty(), "翻訳キー規則に無い日本語フィールド: ${unmapped.take(5)}")
        assertTrue(strings.size > 1000, "コンテンツ文言の列挙数が少なすぎます: ${strings.size}")
        val missing = strings.filter { (key, _) -> english[key].isNullOrBlank() }.map { it.first }
        assertTrue(missing.isEmpty(), "英訳が欠落: ${missing.take(10)} (計 ${missing.size})")
        val leaked = english.filterValues { japanese.containsMatchIn(it) }.keys
        assertTrue(leaked.isEmpty(), "英語カタログに日本語が残っています: ${leaked.take(10)}")
        val orphaned = english.keys - strings.map { it.first }.toSet()
        assertTrue(orphaned.isEmpty(), "対応する原文が無い余剰キー: ${orphaned.take(10)}")
    }

    @Test
    fun `ui language files have matching keys and english has no japanese`() {
        val ja = yamlLeaves(Files.readString(root.resolve("src/main/resources/lang/ja_jp.yml")))
        val en = yamlLeaves(Files.readString(root.resolve("src/main/resources/lang/en_us.yml")))
        assertEquals(ja.keys, en.keys)
        assertTrue(en.values.none { japanese.containsMatchIn(it) }, "en_us.ymlに日本語: ${en.filterValues { japanese.containsMatchIn(it) }.keys.take(5)}")
    }

    @Test
    fun `resource pack language files are valid matching and english is clean`() {
        fun load(name: String): Map<String, String> {
            val json = JsonParser.parseString(Files.readString(root.resolve("resourcepack/assets/ec/lang/$name.json"))).asJsonObject
            return json.entrySet().associate { it.key to it.value.asString }
        }
        val ja = load("ja_jp")
        val en = load("en_us")
        assertEquals(ja.keys, en.keys)
        assertTrue(en.values.none { japanese.containsMatchIn(it) }, "en_us.jsonに日本語: ${en.filterValues { japanese.containsMatchIn(it) }.keys.take(5)}")
        assertTrue(listOf("ec.advancement.root_title", "ec.advancement.root_description").all { it in en })
        // 共有オブジェクト（NPC・敵・アイテム）の名前と説明行が両言語に揃っていることを確認します。
        val contentEnglish = yamlLeaves(Files.readString(root.resolve("src/main/resources/lang/content_en_us.yml")))
        contentStrings().filter { (key, _) -> Regex("ec\\.content\\.(npc|enemy|boss|gathering|item)\\.[a-z0-9_]+\\.name").matches(key) }
            .forEach { (key, _) -> assertTrue(key in en && key in ja, "パックlangに $key がありません") }
        contentStrings().filter { (key, _) -> Regex("ec\\.content\\.item\\.[a-z0-9_]+\\.lore").matches(key) }.forEach { (key, text) ->
            val lines = maxOf(ContentKeys.wrapJa(text).size, ContentKeys.wrapEn(contentEnglish.getValue(key)).size)
            assertTrue((0 until lines).all { "$key.$it" in en }, "説明行が不足: $key")
            assertTrue("$key.$lines" !in en, "説明行が余剰: $key")
        }
    }

    @Test
    fun `english locales normalize to en_us and japanese locales to ja_jp`() {
        listOf("en", "en_us", "en_GB", "en-AU", "en_ca", "en_nz", "EN_IE").forEach { assertEquals("en_us", LangCatalog.normalizeLocale(it), it) }
        listOf("ja", "ja_jp", "ja_JP", "ja-JP", null).forEach { assertEquals("ja_jp", LangCatalog.normalizeLocale(it), it.toString()) }
    }
}
