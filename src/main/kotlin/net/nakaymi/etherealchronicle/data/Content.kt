package net.nakaymi.etherealchronicle.data

import net.nakaymi.etherealchronicle.core.*
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

/** 安定IDと旧ID aliasから読み取り専用コンテンツを引くregistryです。 */
class ContentRegistry<T>(entries: Map<String,T>, private val aliases: Map<String,String> = emptyMap()) {
    private val entries = entries.toMap()
    operator fun get(id: String): T = entries[aliases[id] ?: id] ?: error("Unknown content: $id")
    /** aliasを正規IDへ解決し、該当定義を返します。 */
    fun find(id: String) = entries[aliases[id] ?: id]
    /** registry内の正規定義だけを返します。aliasは重複しません。 */
    fun all() = entries.values
}
/** 一回のreloadでまとめて入れ替える、型付きコンテンツ一式です。 */
data class ContentSnapshot(val balance: Balance, val classes: ContentRegistry<ClassDefinition>, val skills: ContentRegistry<SkillDefinition>, val items: ContentRegistry<ItemDefinition>, val drops: ContentRegistry<DropTable>, val documents: Map<String,Map<String,Any?>>,val sources: Map<String,Path> = emptyMap()) {
    val world = WorldContent(documents)
}
class ContentException(val problems: List<String>): IllegalArgumentException(problems.joinToString("\n"))
fun interface ContentMigration { fun migrate(doc: Map<String,Any?>): Map<String,Any?> }
/** YAML文書を読み、検証済みContentSnapshotを原子的に公開します。 */
/** YAML読込・migrate・validateを行い、成功時だけ現行snapshotを差し替えます。 */
class ContentService(private val root: Path, private val migration: ContentMigration = ContentMigration(ProfileContentMigration::migrateClassDocument)) {
    private val current = AtomicReference<ContentSnapshot>()
    /** 現在公開中の不変snapshotを取得します。 */
    fun snapshot() = checkNotNull(current.get())
    /** 読込・検証を完了してから単一AtomicReferenceで全定義を切り替えます。 */
    fun reload() = load().also { current.set(it) }
    fun install(snapshot: ContentSnapshot) { current.set(snapshot) }
    /** YAMLを文書単位に読み、schema移行後の全参照・範囲を検証します。 */
    fun load(): ContentSnapshot {
        val errors = mutableListOf<String>()
        val docs = linkedMapOf<String,Map<String,Any?>>()
        val sources = linkedMapOf<String,Path>()
        val yaml = Yaml(SafeConstructor(LoaderOptions().apply { isAllowDuplicateKeys = false; maxAliasesForCollections = 20 }))
        Files.walk(root).use { paths -> paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".yml") }.sorted().forEach { path ->
            try {
                val raw = Files.newBufferedReader(path).use { yaml.load<Any>(it) } as? Map<*,*> ?: error("ec.error.invalid_content")
                val doc = migration.migrate(raw.entries.associate { it.key.toString() to it.value })
                require(doc.int("schema_version") == 1) { "Unsupported schema_version" }
                val id = doc.string("id")
                require(Regex("ec:[a-z][a-z0-9_]*").matches(id)) { "Invalid id $id" }
                if (docs.putIfAbsent(id,doc) != null) errors += "$path [$id]: Duplicate id"
                sources[id]=path
                val numericErrors=mutableListOf<String>(); numbers(doc,numericErrors)
                errors.addAll(numericErrors.map { "$path [$id]: $it" })
            } catch(e: Exception) { errors += "$path: ${e.message}" }
        } }
        // 旧職業ファイルに旧スターターIDが残る場合だけ移行し、サーバー独自アイテムは保持します。
        docs.replaceAll { owner,doc ->
          try {
            if(doc["type"] != "class") doc else doc + ("starter_items" to doc.strings("starter_items").map { id ->
                if(id in docs) id else ProfileContentMigration.oldItems[id]?.takeIf { it in docs } ?: id
            })
          } catch(e: Exception) { errors += "${sources[owner]} [$owner]: ${e.message}"; doc }
        }
        val aliases = linkedMapOf<String,String>()
        docs.forEach { (id,d) -> try { d.strings("aliases").forEach { alias ->
            if(alias in docs || aliases.putIfAbsent(alias,id) != null) errors += "ec.error.invalid_content: $alias"
        } } catch(e: Exception) { errors += "${sources[id]} [$id]: ${e.message}" } }
        val declarations = docs.values.filter { it["type"] == "chapter" }.flatMap { d ->
          try {
            d.strings("flags")+d.strings("dungeons")+d.maps("areas").map { it.string("id") }
          } catch(e: Exception) { val id=d["id"]; errors += "${sources[id]} [$id]: ${e.message}"; emptyList() }
        }
        if(declarations.distinct().size != declarations.size) errors += "ec.error.invalid_content: declarations"
        val knownIds = docs.keys + aliases.keys + declarations + "ec:chronicle/root"
        val refTypes = mapOf("skills" to "skill","starter_items" to "item","classes" to "class","item" to "item","class" to "class","enemy" to "enemy","boss" to "boss","guards" to "enemy","quest" to "quest","dialogue" to "dialogue","chapter" to "chapter","droptable" to "droptable","shop" to "shop","npc" to "npc","kill_target" to "enemy","enhancement_material" to "item")
        // 表示用のリソースキーはコンテンツIDではなく、専用の形式検証で扱います。
        val visualResourceKeys = setOf("item_model", "variant", "bettermodel_id", "biome")
        val listRefs = setOf("skills","starter_items","classes","requires","guards")
        fun refs(v: Any?, key: String = "", inWave: Boolean = false) { when(v) {
            is Map<*,*> -> v.forEach { (k,x) ->
                val name = k.toString()
                if(name in listRefs && x !is List<*>) errors += "ec.error.invalid_content: $name"
                if(name in refTypes && name !in listRefs && x !is String) errors += "ec.error.invalid_content: $name"
                refs(x,name,inWave || name == "waves")
            }
            is List<*> -> v.forEach { refs(it,key,inWave) }
            is String -> {
                val expected = refTypes[key]?.takeUnless { key == "boss" }
                val actualType = docs[aliases[v] ?: v]?.get("type")
                if(key == "boss" && (actualType !in setOf("enemy", "boss") || !v.startsWith("ec:"))) errors += "ec.error.invalid_content: $key -> $v (enemy/boss)"
                else if(expected != null && (actualType != expected || !v.startsWith("ec:"))) errors += "ec.error.invalid_content: $key -> $v ($expected)"
                else if(key != "id" && key !in visualResourceKeys && v.startsWith("ec:") && v !in knownIds) errors += "ec.error.invalid_content: $key -> $v"
            }
            else -> if(key in refTypes) errors += "ec.error.invalid_content: $key"
        } }
        docs.forEach { (id,doc) ->
            val start=errors.size; refs(doc)
            for(index in start until errors.size) errors[index]="${sources[id]} [$id]: ${errors[index]}"
        }
        if (errors.isNotEmpty()) throw ContentException(errors)
        var parsingId: String? = null
        try {
            fun typed(type: String) = docs.values.filter { it.string("type") == type }
            val balances = typed("balance")
            require(balances.size == 1) { "ec.error.invalid_content: balance" }
            val b = balances.single()
            parsingId=b.string("id")
            val balance = Balance(b.int("level_cap"),b.num("xp_base"),b.num("xp_exponent"),b.long("xp_gain_cap"),b.long("currency_gain_cap"),b.num("damage_cap"),b.num("stat_cap"),b.num("crit_cap"),b.num("defense_scale"),b.int("skill_level_cap"),b.num("skill_growth"),b.num("crit_damage_cap"),b.num("element_cap"),b.text("xp_curve","POWER"),b.number("xp_linear",45.0),b.number("xp_quadratic",18.0),b.whole("xp_rounding",10),b.whole("skill_gcd_ms",800),b.integer("drop_pity_attempt_cap",100))
            require(balance.levelCap in 1..1000 && balance.xpBase > 0 && balance.xpExponent in 0.0..10.0 && balance.defenseScale > 0 && balance.statCap >= 1 && balance.damageCap > 0 && balance.skillLevelCap in 1..100 && balance.critDamageCap >= 1 && balance.elementCap >= 1 && balance.xpGainCap > 0 && balance.currencyGainCap > 0 && balance.xpCurve in setOf("POWER","POLYNOMIAL") && balance.xpRounding > 0 && balance.dropPityAttemptCap in 1..10000)
            val supported = setOf("balance","class","skill","item","enemy","boss","npc","quest","dialogue","chapter","droptable","shop","title","life_balance","tackle","fishing_spot","fish_species","gathering_node","treasure_chest")
            require(docs.values.all { it.string("type") in supported }) { "Unknown content type" }
            val skills = typed("skill").associate { d -> parsingId=d.string("id"); d.string("id") to SkillDefinition(d.string("id"),d.string("name"),d.num("cost"),d.long("cooldown_ms"),d.num("multiplier"),d.num("range"),d.text("element","PHYSICAL"),d.number("radius",0.0),d.number("heal",0.0),d.number("defense_buff",0.0),d.integer("buff_ticks",0),d.bool("party_heal",false),d.bool("party_defense_buff",false),d.number("ally_radius",0.0),d.integer("taunt_ticks",0),d.number("threat_multiplier",1.0)) }
            val classes = typed("class").associate { d -> parsingId=d.string("id"); d.string("id") to ClassDefinition(d.string("id"),d.string("name"),ResourceKind.valueOf(d.string("resource")),d.stats(),d.num("regeneration"),d.num("hit_gain"),d.strings("skills"),d.strings("starter_items"),(d["growth"] ?: emptyMap<String,Any?>()).asMapping().let { mapOf("stats" to it).stats(Stats.ZERO) },d["icon"] as? String,d["item_model"] as? String) }
            classes.values.forEach { c -> parsingId=c.id; require(c.skills.size in 1..4 && c.skills.distinct().size == c.skills.size && c.stats.hp in 1.0..balance.statCap && c.stats.resource in 1.0..balance.statCap && c.skills.all { id -> skills[id]?.cost?.let { it <= c.stats.resource } == true } && (c.itemModel == null || c.itemModel.matches(Regex("[a-z0-9_.-]+:[a-z0-9_./-]+")))) { "Invalid class skills/stats or item_model" } }
            val items = typed("item").associate { d -> parsingId=d.string("id"); d.string("id") to ItemDefinition(d.string("id"),d.string("name"),d.string("material"),Rarity.valueOf(d.string("rarity")),d.int("min_level"),d.strings("classes").map { aliases[it] ?: it },d.stats(Stats.ZERO),d.num("budget"),b.mapping("rarities").mapping(d.string("rarity")).num("budget_multiplier"),b.mapping("rarities").mapping(d.string("rarity")).int("affixes"),GearSlot.valueOf(d["slot"] as? String ?: "HAND"),d["item_model"] as? String) }
            items.values.forEach { i -> parsingId=i.id; require(i.withinBudget() && i.minLevel in 1..balance.levelCap && i.affixes in 0..4 && i.budgetMultiplier > 0 && i.classes.all { (aliases[it] ?: it) in classes } && (i.itemModel == null || i.itemModel.matches(Regex("[a-z0-9_.-]+:[a-z0-9_./-]+")))) { "Item budget/level invalid" } }
            val drops = typed("droptable").associate { d -> parsingId=d.string("id"); d.string("id") to DropTable(d.string("id"),(d["entries"] as List<*>).map { raw ->
                @Suppress("UNCHECKED_CAST") val e = raw as Map<String,Any?>
                DropEntry(e.string("item"),e.num("chance"),e.int("min"),e.int("max"),if("pity_after" in e) e.int("pity_after") else null).also { require(it.min in 1..64 && it.max in it.min..64 && (it.pityAfter == null || it.pityAfter in 1..balance.dropPityAttemptCap)) }
            }) }
            drops.values.forEach { t -> parsingId=t.id; require(t.entries.map { it.item }.distinct().size == t.entries.size && t.entries.all { it.item in items }) { "Duplicate/invalid drop item" } }
            classes.values.forEach { c -> parsingId=c.id; require(c.starterItems.size in 1..8 && c.starterItems.all { it in items }) { "Invalid starter items" } }
            skills.values.forEach { s -> parsingId=s.id; require(s.range in 0.1..128.0 && s.multiplier in 0.0..balance.statCap && s.cooldownMillis in 1..3_600_000 && s.radius in 0.0..32.0 && s.heal >= 0 && s.defenseBuff >= 0 && s.buffTicks in 0..72000 && s.allyRadius in 0.0..64.0 && s.tauntTicks in 0..72000 && s.threatMultiplier in 1.0..100.0) { "ec.error.invalid_content" } }
            (typed("enemy")+typed("boss")).forEach { d ->
                parsingId=d.string("id")
                require(d.num("hp") in 1.0..balance.statCap) { "hp=${d["hp"]} ec.error.invalid_content (balance.stat_cap=${balance.statCap})" }
                require(d.num("attack") <= balance.statCap && d.num("defense") <= balance.statCap && d.int("level") in 1..balance.levelCap) { "attack/defense/level ec.error.invalid_content" }
                d.long("xp"); d.long("currency"); d.string("entity")
            }
            typed("quest").filter { it["objectives"] == null }.forEach { d -> parsingId=d.string("id"); d.long("xp"); d.long("currency"); require(d.int("required_kills") >= 1) }
            parsingId=null
            return ContentSnapshot(balance,ContentRegistry(classes,aliases),ContentRegistry(skills),ContentRegistry(items),ContentRegistry(drops),docs.toMap(),sources)
        } catch(e: Exception) {
            val id=Regex("ec:[a-z0-9_]+").find(e.message.orEmpty())?.value ?: parsingId
            throw ContentException(listOf("${sources[id] ?: root} [${id ?: "registry"}]: ${e.message ?: "Invalid content"}"))
        }
    }
    private fun numbers(v: Any?, errors: MutableList<String>, parent: String = "") {
        when(v) {
            is Map<*,*> -> v.forEach { (k,x) ->
                if (x is Number && (!x.toDouble().isFinite() || (x.toDouble() < 0 && parent !in setOf("stats","growth") && k !in setOf("x","y","z","yaw","min_x","max_x","min_y","max_y","min_z","max_z")) || (k in setOf("chance","crit","crit_cap","element_resist") && kotlin.math.abs(x.toDouble()) > 1))) errors += "Invalid number $k"
                numbers(x,errors,k.toString())
            }
            is List<*> -> v.forEach { numbers(it,errors) }
        }
    }
}
fun Map<String,Any?>.string(k: String) = this[k] as? String ?: error("Missing string $k")
fun Map<String,Any?>.num(k: String) = (this[k] as? Number)?.toDouble() ?: error("Missing number $k")
fun Map<String,Any?>.int(k: String): Int = long(k).also { require(it in Int.MIN_VALUE..Int.MAX_VALUE) { "ec.error.invalid_content: $k" } }.toInt()
fun Map<String,Any?>.long(k: String): Long = (this[k] as? Number)?.toString()?.toLongOrNull() ?: error("ec.error.invalid_content: $k")
fun Map<String,Any?>.strings(k: String): List<String> = if(k !in this) emptyList() else (this[k] as? List<*> ?: error("ec.error.invalid_content: $k")).map { it as? String ?: error("ec.error.invalid_content: $k") }
@Suppress("UNCHECKED_CAST")
fun Map<String,Any?>.stats(d: Stats = Stats()): Stats {
    val s = if("stats" in this) mapping("stats") else emptyMap()
    fun n(k: String, v: Double) = if(k in s) s.num(k) else v
    return Stats(n("hp",d.hp),n("resource",d.resource),n("attack",d.attack),n("defense",d.defense),n("crit",d.crit),n("crit_damage",d.critDamage),n("element_resist",d.elementResist),n("skill_power",d.skillPower))
}


@Suppress("UNCHECKED_CAST")
fun Map<String,Any?>.mapping(k: String) = this[k] as? Map<String,Any?> ?: error("Missing mapping $k")












