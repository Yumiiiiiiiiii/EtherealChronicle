package net.nakaymi.etherealchronicle.core

import net.nakaymi.etherealchronicle.data.*

data class Condition(val minLevel: Int = 1, val hasFlags: Set<String> = emptySet(), val notFlags: Set<String> = emptySet(),
    val completed: Set<String> = emptySet(), val active: Set<String> = emptySet(), val notStarted: Set<String> = emptySet(),
    val classes: Set<String> = emptySet(), val any: List<Condition> = emptyList()) {
    fun matches(p: PlayerProfile, now: Long = System.currentTimeMillis()): Boolean = p.level >= minLevel && p.flags.containsAll(hasFlags) && notFlags.none { it in p.flags } &&
        completed.all { p.quests[it]?.let { q -> q.completed || q.completedAt > 0 } == true } && active.all { p.quests[it]?.completed == false } && notStarted.all { p.quests[it]?.let { q -> q.completed && now >= q.repeatAvailableAt } ?: true } &&
        (classes.isEmpty() || p.classId in classes) && (any.isEmpty() || any.any { it.matches(p,now) })
}
enum class ObjectiveKind { KILL, COLLECT, EXPLORE, TALK }
data class Objective(val id: String, val kind: ObjectiveKind, val target: String, val amount: Int, val consume: Boolean = true)
data class ItemReward(val item: String, val amount: Int, val condition: Condition = Condition())
data class Rewards(val xp: Long = 0, val currency: Long = 0, val items: List<ItemReward> = emptyList(), val flags: Set<String> = emptySet())
data class QuestDefinition(val id: String, val name: String, val condition: Condition, val objectives: List<Objective>,
    val rewards: Rewards, val autoComplete: Boolean, val repeatable: Boolean, val repeatCooldown: Long, val resetSchedule: String? = null)
data class DialogueAction(val kind: String, val target: String, val amount: Int = 1, val onceFlag: String? = null, val destination: Position? = null, val requiredFlag: String? = null)
data class DialogueChoice(val id: String, val label: String, val condition: Condition, val next: String?, val flags: Set<String>, val clearFlags: Set<String>, val actions: List<DialogueAction>)
data class DialogueNode(val text: String, val branches: List<Pair<Condition,String>>, val choices: List<DialogueChoice>)
data class DialogueDefinition(val id: String, val start: String, val nodes: Map<String,DialogueNode>, val globalChoices: List<DialogueChoice> = emptyList())
data class Position(val world: String, val x: Double, val y: Double, val z: Double, val yaw: Float = 0f)
data class Area(val id: String, val name: String, val world: String, val minX: Double, val maxX: Double, val minY: Double, val maxY: Double, val minZ: Double, val maxZ: Double, val safe: Boolean) {
    fun contains(p: Position) = p.world == world && p.x in minX..maxX && p.y in minY..maxY && p.z in minZ..maxZ
}
data class DungeonWave(val boss: String, val guards: List<String>, val guardOffsets: List<Pair<Int,Int>>, val bossOffset: Pair<Int,Int>)
data class ScoreRank(val rank: String,val maxSeconds: Long)
enum class TitleConditionType { FLAG, BESTIARY, COLLECTION }
data class TitleProgressCondition(val type:TitleConditionType,val target:String?,val count:Int) {
    fun met(profile:PlayerProfile,unlockFlag:String):Boolean = when(type) {
        TitleConditionType.FLAG -> (target ?: unlockFlag) in profile.flags
        TitleConditionType.BESTIARY -> (target?.let(profile.bestiary::get) ?: 0) >= count
        TitleConditionType.COLLECTION -> profile.discoveredItems.size >= count
    }
}
data class TitleAdvancement(val title:String,val description:String,val icon:String,val parent:String?,val frame:String,val condition:TitleProgressCondition)
data class TitleDefinition(val id: String,val name: String,val description: String,val unlockFlag: String,val advancement:TitleAdvancement? = null) {
    fun unlocked(profile:PlayerProfile) = unlockFlag in profile.flags || advancement?.condition?.met(profile,unlockFlag) == true
}
data class DungeonDefinition(val id: String, val unlockFlag: String, val boss: String, val guards: List<String>, val guardOffsets: List<Pair<Int,Int>>, val area: String, val palette: List<String>, val bossOffset: Pair<Int,Int>, val entryMessage: String, val clearMessage: String,val waves: List<DungeonWave> = emptyList(),val scoreRanks: List<ScoreRank> = emptyList())
data class NpcDefinition(val id: String, val name: String, val position: Position, val dialogue: String, val shop: String?)
data class TokenCost(val item: String, val amount: Int)
data class ShopEntry(val id: String, val item: String, val amount: Int, val price: Long, val tokens: List<TokenCost>, val condition: Condition, val dailyLimit: Int, val tokenDiscountFlag: String?, val tokenDiscount: List<TokenCost>)
data class ShopDefinition(val id: String, val name: String, val buyback: Boolean, val discountFlag: String?, val discount: Double, val entries: List<ShopEntry>)
data class SpawnPoint(val id: String, val position: Position, val cap: Int, val respawnTicks: Long)
data class SpecialAttack(val id: String, val name: String, val shape: String, val cooldown: Long, val telegraph: Long, val radius: Double,
    val range: Double, val multiplier: Double, val minHp: Double, val maxHp: Double, val effect: String, val element: String? = null, val effectDuration: Int = 30)
data class BossPhase(val id: String, val below: Double, val damage: Double, val interval: Double)
data class EnemyDefinition(val id: String, val name: String, val entity: String, val level: Int, val hp: Double, val attack: Double, val defense: Double,
    val element: String, val weaknesses: Set<String>, val resistances: Set<String>, val speed: Double, val size: Int, val aggro: Double, val leash: Double,
    val attackInterval: Long, val traits: Set<String>, val specials: List<SpecialAttack>, val spawns: List<SpawnPoint>, val flags: Set<String>,
    val boss: Boolean, val phases: List<BossPhase>, val enrageTicks: Long, val enrageMultiplier: Double, val coopHp: Double, val coopDamage: Double)
data class ItemEffect(val kind: String, val amount: Double, val cooldown: Long, val duration: Int, val chance: Double, val element: String)
data class ItemProperties(val bound: Boolean, val questItem: Boolean, val price: Long, val weapon: Double, val lore: String, val effects: List<ItemEffect>)

class WorldContent(documents: Map<String,Map<String,Any?>>) {
    val tuning = documents.values.single { it["type"] == "balance" }.tuning()
    val quests = linkedMapOf<String,QuestDefinition>()
    val dialogues = linkedMapOf<String,DialogueDefinition>()
    val npcs = linkedMapOf<String,NpcDefinition>()
    val shops = linkedMapOf<String,ShopDefinition>()
    val areas = linkedMapOf<String,Area>()
    val dungeons = linkedMapOf<String,DungeonDefinition>()
    val titles = linkedMapOf<String,TitleDefinition>()
    val enemies = linkedMapOf<String,EnemyDefinition>()
    val itemProperties = linkedMapOf<String,ItemProperties>()
    val chapters = documents.filterValues { it["type"] == "chapter" }
    init {
        documents.forEach { (id,d) ->
          try {
            when(d["type"]) {
                "quest" -> {
                    val objectives = d.maps("objectives").map { Objective(it.string("id"),ObjectiveKind.valueOf(it.string("kind")),it.string("target"),it.integer("amount",1),it.bool("consume",true)) }
                        .ifEmpty { listOf(Objective("legacy_kills",ObjectiveKind.KILL,d["kill_target"] as? String ?: "*",d.integer("required_kills",1))) }
                    require(objectives.map { it.id }.distinct().size == objectives.size && objectives.all { it.amount in 1..1_000_000 }) { "$id: 目標IDまたは数量が不正です。" }
                    val rewards = if(d["rewards"] != null) d.mapping("rewards").rewards() else Rewards(d.whole("xp",0),d.whole("currency",0))
                    val reset=d["reset_schedule"] as? String
                    require(reset == null || reset in setOf("DAILY","WEEKLY")) { "reset_schedule は DAILY または WEEKLY を指定してください。" }
                    quests[id] = QuestDefinition(id,d.string("name"),d.condition(),objectives,rewards,d.bool("auto_complete",d["objectives"] == null),d.bool("repeatable",false),d.whole("repeat_cooldown_ms",86_400_000),reset)
                }
                "dialogue" -> {
                    fun parseChoice(c: Map<String,Any?>) = DialogueChoice(c.string("id"),c.string("label"),c.condition(),c["next"] as? String,c.strings("set_flags").toSet(),c.strings("clear_flags").toSet(),c.maps("actions").map { a ->
                        DialogueAction(a.string("kind"),a.string("target"),a.integer("amount",1),a["once_flag"] as? String,a["destination"]?.let { it.asMapping().position() },a["required_flag"] as? String)
                    })
                    val nodes = d.mapping("nodes").mapValues { (_,raw) ->
                        val n = raw.asMapping()
                        DialogueNode(n.text("text",""),n.maps("branches").map { it.condition() to it.string("next") },n.maps("choices").map(::parseChoice))
                    }
                    val globalChoices=d.maps("global_choices").map(::parseChoice)
                    val allChoiceIds=globalChoices.map { it.id }+nodes.values.flatMap { it.choices.map(DialogueChoice::id) }
                    require(globalChoices.map { it.id }.distinct().size == globalChoices.size && allChoiceIds.distinct().size == allChoiceIds.size) { "$id: 共通会話選択肢IDが重複しています。" }
                    val start = d.string("start")
                    require(start in nodes && nodes.isNotEmpty()) { "$id: 開始ノードがありません。" }
                    nodes.values.forEach { n ->
                        require(n.choices.map { it.id }.distinct().size == n.choices.size)
                        require(n.branches.all { it.second in nodes } && n.choices.all { it.next.isNullOrEmpty() || it.next in nodes }) { "$id: 会話ノード参照が不正です。" }
                        require(n.choices.flatMap { it.actions }.all { it.kind in setOf("START_QUEST","COMPLETE_QUEST","SELECT_CLASS","OPEN_SHOP","ENTER_DUNGEON","GIVE_ITEM","TELEPORT_AREA") && it.amount in 1..64 })
                    }
                    dialogues[id] = DialogueDefinition(id,start,nodes,globalChoices)
                }
                "npc" -> npcs[id] = NpcDefinition(id,d.string("name"),d.mapping("location").position(),d.string("dialogue"),d["shop"] as? String)
                "title" -> {
                    val unlockFlag=d.string("unlock_flag")
                    val advancement=d["advancement"]?.asMapping()?.let { a ->
                        val raw=a["condition"]?.asMapping() ?: emptyMap()
                        val type=TitleConditionType.valueOf(raw.text("type","FLAG").uppercase())
                        val target=when(type) {
                            TitleConditionType.FLAG -> raw.text("target",unlockFlag)
                            TitleConditionType.BESTIARY -> raw.string("target")
                            TitleConditionType.COLLECTION -> null
                        }
                        val condition=TitleProgressCondition(type,target,raw.integer("count",1))
                        val definition=TitleAdvancement(a.text("title",d.string("name")),a.text("description",d.text("description","")),a.string("icon"),a["parent"] as? String,a.text("frame","TASK").uppercase(),condition)
                        require(definition.title.isNotBlank() && definition.title.length <= 80 && definition.description.length <= 240 && definition.icon.matches(Regex("[A-Z][A-Z0-9_]*")) && (definition.parent == null || definition.parent.matches(Regex("[a-z0-9_.-]+:[a-z0-9_./-]+"))) && definition.frame in setOf("TASK","CHALLENGE","GOAL") && condition.count in 1..1_000_000 && (type == TitleConditionType.COLLECTION || target?.startsWith("ec:") == true)) { "$id: 進捗の表示または条件が不正です。" }
                        definition
                    }
                    titles[id] = TitleDefinition(id,d.string("name"),d.text("description",""),unlockFlag,advancement)
                }
                "shop" -> {
                    val entries = d.maps("entries").map { e -> ShopEntry(e.string("id"),e.string("item"),e.integer("amount",1),e.whole("price",0),e.tokens("tokens"),e.condition(),e.integer("daily_limit",0),e["token_discount_flag"] as? String,e.tokens("token_discount")) }
                    require(entries.size in 1..45 && entries.map { it.id }.distinct().size == entries.size && entries.all { it.amount in 1..64 && it.price >= 0 && it.dailyLimit >= 0 && it.tokens.all { t -> t.amount > 0 } })
                    val discount = d.number("discount",0.0); require(discount in 0.0..0.5)
                    shops[id] = ShopDefinition(id,d.string("name"),d.bool("buyback",true),d["discount_flag"] as? String,discount,entries)
                }
                "chapter" -> {
                    d.maps("areas").forEach { a ->
                        val area = Area(a.string("id"),a.string("name"),a.string("world"),a.num("min_x"),a.num("max_x"),a.num("min_y"),a.num("max_y"),a.num("min_z"),a.num("max_z"),a.bool("safe",false))
                        require(area.minX <= area.maxX && area.minY <= area.maxY && area.minZ <= area.maxZ && area.id !in areas)
                        areas[area.id] = area
                    }
                    d.maps("dungeon_configs").forEach { config ->
                        val id=config.string("id")
                        val guards=config.strings("guards")
                        val offsets=config.maps("guard_offsets").map { it.int("x") to it.int("z") }
                        val bossOffset=config.mapping("boss_offset").let { it.int("x") to it.int("z") }
                        val waves=config.maps("waves").map { wave ->
                            val bossOffset=wave.mapping("boss_offset").let { it.int("x") to it.int("z") }
                            val guards=wave.strings("guards"); val offsets=wave.maps("guard_offsets").map { it.int("x") to it.int("z") }
                            DungeonWave(wave.string("boss"),guards,offsets,bossOffset)
                        }
                        val ranks=config.maps("score_ranks").map { ScoreRank(it.string("rank"),it.whole("max_seconds",0)) }
                        val definition=DungeonDefinition(id,config.string("unlock_flag"),config.string("boss"),guards,offsets,config.string("area"),config.strings("palette"),bossOffset,config.string("entry_message"),config.string("clear_message"),waves,ranks)
                        require(id in d.strings("dungeons") && id !in dungeons && definition.unlockFlag in d.strings("flags") && definition.guards.size == definition.guardOffsets.size && definition.palette.size == 3)
                        require(definition.waves.all { it.guardOffsets.size == it.guards.size } && definition.scoreRanks.all { it.rank in setOf("S","A","B","C") && it.maxSeconds > 0 } && definition.scoreRanks.zipWithNext().all { (a,b) -> a.maxSeconds <= b.maxSeconds })
                        dungeons[id]=definition
                    }
                }
                "enemy","boss" -> {
                    val specials = d.maps("specials").map { a -> SpecialAttack(a.string("id"),a.string("name"),a.text("shape","CIRCLE"),a.whole("cooldown_ticks",200),a.whole("telegraph_ticks",26),a.number("radius",3.0),a.number("range",12.0),a.number("multiplier",1.5),a.number("min_hp_ratio",0.0),a.number("max_hp_ratio",1.0),a.text("effect","NONE"),a["element"] as? String,a.integer("effect_duration_ticks",when(a.text("effect","NONE")) { "SLOW" -> 60; "POISON" -> 100; else -> 30 })) }
                    require(specials.all { it.shape in setOf("CIRCLE","LINE","CONE","TARGET") && it.cooldown > 0 && it.telegraph > 0 && it.radius in 0.1..32.0 && it.range in 0.1..128.0 && it.minHp in 0.0..it.maxHp && it.maxHp <= 1.0 && it.effect in setOf("NONE","SLOW","POISON","KNOCKUP","FREEZE") && (it.element == null || it.element in setOf("PHYSICAL","FIRE","ICE","LIGHTNING","AETHER","VOID")) && it.effectDuration in 1..200 })
                    val spawns = d.maps("spawn_points").map { p -> SpawnPoint(p.string("id"),p.position(),p.integer("cap",1),p.whole("respawn_ticks",200)) }
                    require(spawns.all { it.cap in 1..100 && it.respawnTicks > 0 } && spawns.map { it.id }.distinct().size == spawns.size)
                    val phases = d.maps("phases").map { p -> BossPhase(p.string("id"),p.num("below_hp_ratio"),p.number("damage_multiplier",1.0),p.number("interval_multiplier",1.0)) }
                    require(phases.all { it.below in 0.0..1.0 && it.damage > 0 && it.interval > 0 } && phases.zipWithNext().all { it.first.below > it.second.below })
                    enemies[id] = EnemyDefinition(id,d.string("name"),d.string("entity"),d.int("level"),d.num("hp"),d.num("attack"),d.num("defense"),d.text("element","PHYSICAL"),d.strings("weaknesses").toSet(),d.strings("resistances").toSet(),d.number("speed",0.23),d.integer("size",1),d.number("aggro_range",12.0),d.number("leash_range",24.0),d.whole("attack_interval_ticks",40),d.strings("traits").toSet(),specials,spawns,d.strings("set_flags_on_kill").toSet(),d["type"] == "boss",phases,d.whole("enrage_ticks",7200),d.number("enrage_multiplier",1.5),d.number("coop_hp_per_player",0.75),d.number("coop_damage_per_player",0.04))
                }
                "item" -> itemProperties[id] = ItemProperties(d.bool("bound",false),d.bool("quest_item",false),d.whole("price",0),d.number("weapon",0.0),d.text("lore",""),d.maps("effects").map { e ->
                    ItemEffect(e.string("kind"),e.number("amount",0.0),e.whole("cooldown_ms",0),e.integer("duration_ticks",0),e.number("chance",1.0),e.text("element","PHYSICAL")).also { require(it.kind in setOf("HEAL","RESOURCE","CLEANSE","RETURN","REVIVE","ATTACK_PROC","DEFENSE_BUFF") && it.chance in 0.0..1.0) }
                })
            }
          } catch(error: Exception) { throw IllegalArgumentException("$id: ${error.message}",error) }
        }
        require(chapters.values.flatMap { it.strings("dungeons") }.all { it in dungeons }) { "章のダンジョン定義が不足しています。" }
        dungeons.values.forEach { dungeon ->
            require(dungeon.area in areas && dungeon.boss in enemies && enemies[dungeon.boss]?.boss == true && dungeon.guards.all { enemies[it] != null } && dungeon.waves.all { wave -> wave.boss in enemies && wave.guards.all { it in enemies } }) { "${dungeon.id}: ダンジョン参照が不正です。" }
            require(dungeon.unlockFlag.startsWith("ec:flag_") && dungeon.palette.all { it.matches(Regex("[A-Z_]+")) }) { "${dungeon.id}: ダンジョン設定が不正です。" }
        }
        titles.values.forEach { title ->
            require(title.unlockFlag.matches(Regex("ec:flag_[a-z0-9_]+"))) { "${title.id}: unlock_flag が不正です。" }
            val advancement=title.advancement ?: return@forEach
            if(advancement.parent?.startsWith("ec:title_") == true) require(titles[advancement.parent]?.advancement != null) { "${title.id}: 親称号進捗が存在しません: ${advancement.parent}" }
            if(advancement.condition.type == TitleConditionType.BESTIARY) require(advancement.condition.target in enemies) { "${title.id}: 図鑑進捗の魔物が存在しません: ${advancement.condition.target}" }
        }
        validateWorldReferences(documents)
    }
    fun enemiesInArea(areaId:String):List<EnemyDefinition> {
        val area=areas[areaId] ?: return emptyList()
        return enemies.values.filter { enemy -> enemy.spawns.any { spawn -> area.contains(spawn.position) } }.sortedBy { it.id }
    }
}
@Suppress("UNCHECKED_CAST") fun Any?.asMapping(): Map<String,Any?> = this as? Map<String,Any?> ?: error("マッピングが必要です。")
fun Map<String,Any?>.maps(k: String): List<Map<String,Any?>> = if(k !in this) emptyList() else (this[k] as? List<*> ?: error("リストが必要です: $k")).map { it.asMapping() }
fun Map<String,Any?>.number(k: String,d: Double) = if(k in this) num(k) else d
fun Map<String,Any?>.integer(k: String,d: Int) = if(k in this) int(k) else d
fun Map<String,Any?>.whole(k: String,d: Long) = if(k in this) long(k) else d
fun Map<String,Any?>.text(k: String,d: String) = this[k] as? String ?: d
fun Map<String,Any?>.bool(k: String,d: Boolean) = if(k in this) this[k] as? Boolean ?: error("真偽値が必要です: $k") else d
fun Map<String,Any?>.position() = Position(string("world"),num("x"),num("y"),num("z"),number("yaw",0.0).toFloat())
fun Map<String,Any?>.condition(): Condition = if("condition" in this) mapping("condition").conditionValue() else Condition()
fun Map<String,Any?>.conditionValue(): Condition = Condition(integer("min_level",1),strings("has_flags").toSet(),strings("not_flags").toSet(),strings("completed_quests").toSet(),strings("active_quests").toSet(),strings("not_started_quests").toSet(),strings("classes").toSet(),maps("any").map { it.conditionValue() })
fun Map<String,Any?>.rewards() = Rewards(whole("xp",0),whole("currency",0),maps("items").map { ItemReward(it.string("item"),it.integer("amount",1),it.condition()) },strings("set_flags").toSet())
fun Map<String,Any?>.tokens(key: String) = maps(key).map { TokenCost(it.string("item"),it.int("amount")) }


