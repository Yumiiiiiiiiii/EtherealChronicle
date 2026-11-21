package dev.mcmmo.rpg.data

import dev.mcmmo.rpg.core.*
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

class ContentRegistry<T>(entries: Map<String,T>) {
    private val entries = entries.toMap()
    operator fun get(id: String): T = entries[id] ?: error("Unknown content: $id")
    fun find(id: String) = entries[id]
    fun all() = entries.values
}
data class ContentSnapshot(val balance: Balance, val classes: ContentRegistry<ClassDefinition>, val skills: ContentRegistry<SkillDefinition>, val items: ContentRegistry<ItemDefinition>, val drops: ContentRegistry<DropTable>, val documents: Map<String,Map<String,Any?>>)
class ContentException(val problems: List<String>): IllegalArgumentException(problems.joinToString("\n"))
fun interface ContentMigration { fun migrate(doc: Map<String,Any?>): Map<String,Any?> }
class ContentService(private val root: Path, private val migration: ContentMigration = ContentMigration { it }) {
    private val current = AtomicReference<ContentSnapshot>()
    fun snapshot() = checkNotNull(current.get())
    fun reload() = load().also { current.set(it) }
    fun install(snapshot: ContentSnapshot) { current.set(snapshot) }
    fun load(): ContentSnapshot {
        val errors = mutableListOf<String>()
        val docs = linkedMapOf<String,Map<String,Any?>>()
        val yaml = Yaml(SafeConstructor(LoaderOptions().apply { isAllowDuplicateKeys = false; maxAliasesForCollections = 20 }))
        Files.walk(root).use { paths -> paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".yml") }.sorted().forEach { path ->
            try {
                val raw = Files.newBufferedReader(path).use { yaml.load<Any>(it) } as Map<*,*>
                val doc = migration.migrate(raw.entries.associate { it.key.toString() to it.value })
                require(doc.int("schema_version") == 1) { "Unsupported schema_version" }
                val id = doc.string("id")
                require(Regex("ec:[a-z][a-z0-9_]*").matches(id)) { "Invalid id $id" }
                if (docs.putIfAbsent(id,doc) != null) errors += "Duplicate id $id"
                numbers(doc,errors)
            } catch(e: Exception) { errors += "$path: ${e.message}" }
        } }
        fun refs(v: Any?, key: String = "") { when(v) {
            is Map<*,*> -> v.forEach { (k,x) -> refs(x,k.toString()) }
            is List<*> -> v.forEach { refs(it,key) }
            is String -> if (key in setOf("skills","starter_items","classes","item","class","enemy","boss","quest","dialogue","chapter","droptable","shop","npc","requires") && v.startsWith("ec:") && v !in docs) errors.add("Dangling $key: $v")
        } }
        docs.values.forEach { refs(it) }
        if (errors.isNotEmpty()) throw ContentException(errors)
        try {
            fun typed(type: String) = docs.values.filter { it.string("type") == type }
            val b = typed("balance").single()
            val balance = Balance(b.int("level_cap"),b.num("xp_base"),b.num("xp_exponent"),b.long("xp_gain_cap"),b.long("currency_gain_cap"),b.num("damage_cap"),b.num("stat_cap"),b.num("crit_cap"),b.num("defense_scale"),b.int("skill_level_cap"),b.num("skill_growth"),b.num("crit_damage_cap"),b.num("element_cap"))
            require(balance.levelCap in 1..1000 && balance.xpBase > 0 && balance.defenseScale > 0 && balance.statCap > 0 && balance.damageCap > 0 && balance.skillLevelCap > 0)
            val skills = typed("skill").associate { d -> d.string("id") to SkillDefinition(d.string("id"),d.string("name"),d.num("cost"),d.long("cooldown_ms"),d.num("multiplier"),d.num("range")) }
            val classes = typed("class").associate { d -> d.string("id") to ClassDefinition(d.string("id"),d.string("name"),ResourceKind.valueOf(d.string("resource")),d.stats(),d.num("regeneration"),d.num("hit_gain"),d.strings("skills"),d.strings("starter_items")) }
            require(classes.values.all { it.skills.size in 1..4 && it.stats.hp > 0 && it.stats.resource > 0 })
            val items = typed("item").associate { d -> d.string("id") to ItemDefinition(d.string("id"),d.string("name"),d.string("material"),Rarity.valueOf(d.string("rarity")),d.int("min_level"),d.strings("classes"),d.stats(Stats.ZERO),d.num("budget"),b.mapping("rarities").mapping(d.string("rarity")).num("budget_multiplier"),b.mapping("rarities").mapping(d.string("rarity")).int("affixes")) }
            require(items.values.all { it.withinBudget() && it.minLevel in 1..balance.levelCap }) { "Item budget/level invalid" }
            val drops = typed("droptable").associate { d -> d.string("id") to DropTable(d.string("id"),(d["entries"] as List<*>).map { raw ->
                @Suppress("UNCHECKED_CAST") val e = raw as Map<String,Any?>
                DropEntry(e.string("item"),e.num("chance"),e.int("min"),e.int("max")).also { require(it.min in 1..64 && it.max in it.min..64) }
            }) }
            return ContentSnapshot(balance,ContentRegistry(classes),ContentRegistry(skills),ContentRegistry(items),ContentRegistry(drops),docs.toMap())
        } catch(e: Exception) { throw ContentException(listOf(e.message ?: "Invalid content")) }
    }
    private fun numbers(v: Any?, errors: MutableList<String>) {
        when(v) {
            is Map<*,*> -> v.forEach { (k,x) ->
                if (x is Number && (!x.toDouble().isFinite() || x.toDouble() < 0 || (k in setOf("chance","crit","crit_cap","element_resist") && x.toDouble() > 1))) errors += "Invalid number $k"
                numbers(x,errors)
            }
            is List<*> -> v.forEach { numbers(it,errors) }
        }
    }
}
fun Map<String,Any?>.string(k: String) = this[k] as? String ?: error("Missing string $k")
fun Map<String,Any?>.num(k: String) = (this[k] as? Number)?.toDouble() ?: error("Missing number $k")
fun Map<String,Any?>.int(k: String) = num(k).also { require(it % 1 == 0.0) }.toInt()
fun Map<String,Any?>.long(k: String) = num(k).also { require(it % 1 == 0.0) }.toLong()
fun Map<String,Any?>.strings(k: String) = (this[k] as? List<*>)?.map { it as String } ?: emptyList()
@Suppress("UNCHECKED_CAST")
fun Map<String,Any?>.stats(d: Stats = Stats()): Stats {
    val s = this["stats"] as? Map<String,Any?> ?: emptyMap()
    fun n(k: String, v: Double) = (s[k] as? Number)?.toDouble() ?: v
    return Stats(n("hp",d.hp),n("resource",d.resource),n("attack",d.attack),n("defense",d.defense),n("crit",d.crit),n("crit_damage",d.critDamage),n("element_resist",d.elementResist),n("skill_power",d.skillPower))
}


@Suppress("UNCHECKED_CAST")
fun Map<String,Any?>.mapping(k: String) = this[k] as? Map<String,Any?> ?: error("Missing mapping $k")

