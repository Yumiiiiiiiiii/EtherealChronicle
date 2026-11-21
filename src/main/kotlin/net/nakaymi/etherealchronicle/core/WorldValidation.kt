package net.nakaymi.etherealchronicle.core

import net.nakaymi.etherealchronicle.data.*

/** Semantic references whose type depends on the enclosing objective/action/condition. */
internal fun WorldContent.validateWorldReferences(documents: Map<String,Map<String,Any?>>) {
    val aliases=documents.flatMap { (id,d) -> d.strings("aliases").map { it to id } }.toMap()
    fun ref(target: String, types: Set<String>, field: String) {
        require(documents[aliases[target] ?: target]?.get("type") in types) { "$field -> $target: 参照先は ${types.joinToString("/")} が必要です。" }
    }
    fun condition(c: Condition) {
        require(c.minLevel in 1..1000) { "condition.min_level が範囲外です。" }
        (c.completed+c.active+c.notStarted).forEach { ref(it,setOf("quest"),"condition.quest") }
        c.classes.forEach { ref(it,setOf("class"),"condition.classes") }
        c.any.forEach(::condition)
    }
    fun check(id: String, action: () -> Unit) { try { action() } catch(e: Exception) { throw IllegalArgumentException("$id: ${e.message}",e) } }
    quests.forEach { (id,q) -> check(id) {
        condition(q.condition)
        require(q.rewards.xp >= 0 && q.rewards.currency >= 0 && q.repeatCooldown >= 0) { "報酬または再受注時間が不正です。" }
        q.objectives.forEach { o -> when(o.kind) {
            ObjectiveKind.KILL -> if(o.target != "*") ref(o.target,setOf("enemy","boss"),"objectives.KILL")
            ObjectiveKind.COLLECT -> ref(o.target,setOf("item"),"objectives.COLLECT")
            ObjectiveKind.TALK -> ref(o.target,setOf("npc"),"objectives.TALK")
            ObjectiveKind.EXPLORE -> require(o.target in areas) { "objectives.EXPLORE -> ${o.target}: エリアが必要です。" }
            ObjectiveKind.CATCH_FISH -> if(o.target !in setOf("ANY","*")) ref(o.target,setOf("fish_species"),"objectives.CATCH_FISH")
            ObjectiveKind.GATHER_NODE -> if(o.target !in setOf("ANY","*","HERB","ORE","WOOD","CRYSTAL")) ref(o.target,setOf("gathering_node"),"objectives.GATHER_NODE")
            ObjectiveKind.ENHANCE_GEAR -> require(o.target in setOf("ANY","*") || (o.target.toIntOrNull() ?: -1) in 1..tuning.enhancementCap) { "objectives.ENHANCE_GEAR は ANY または強化段階 1..${tuning.enhancementCap} で指定してください。" }
        } }
        q.rewards.items.forEach { require(it.amount in 1..64) { "rewards.items.amount は1..64です。" }; ref(it.item,setOf("item"),"rewards.items"); condition(it.condition) }
    } }
    dialogues.forEach { (id,d) -> check(id) { (d.nodes.values.toList()+DialogueNode("",emptyList(),d.globalChoices)).forEach { n ->
        n.branches.forEach { condition(it.first) }
        n.choices.forEach { c -> condition(c.condition); c.actions.forEach { a -> when(a.kind) {
            "START_QUEST","COMPLETE_QUEST" -> ref(a.target,setOf("quest"),"actions.${a.kind}")
            "SELECT_CLASS" -> ref(a.target,setOf("class"),"actions.SELECT_CLASS")
            "OPEN_SHOP" -> ref(a.target,setOf("shop"),"actions.OPEN_SHOP")
            "GIVE_ITEM" -> { ref(a.target,setOf("item"),"actions.GIVE_ITEM"); require(a.onceFlag != null) { "GIVE_ITEM.once_flag が必要です。" } }
            "ENTER_DUNGEON" -> require(chapters.values.any { a.target in it.strings("dungeons") }) { "actions.ENTER_DUNGEON -> ${a.target}: ダンジョンが必要です。" }
            "TELEPORT_AREA" -> {
                val area=areas[a.target] ?: error("actions.TELEPORT_AREA -> ${a.target}: エリアが必要です。")
                require(area.world == "ec_world") { "actions.TELEPORT_AREA は管理ワールド内のエリアに限ります。" }
                a.destination?.let { require(area.contains(it)) { "actions.TELEPORT_AREA.destination は対象エリア内で指定してください。" } }
                a.requiredFlag?.let { flag -> require(chapters.values.any { flag in it.strings("flags") }) { "actions.TELEPORT_AREA.required_flag が章に定義されていません。" } }
            }
        } } }
    } } }
    shops.forEach { (id,s) -> check(id) { s.entries.forEach { e ->
        ref(e.item,setOf("item"),"entries.item"); condition(e.condition)
        listOf(e.tokens,e.tokenDiscount).forEach { costs ->
            require(costs.map { it.item }.distinct().size == costs.size) { "entries.tokens: 同じ素材を複数回指定できません。" }
            costs.forEach { ref(it.item,setOf("item"),"entries.tokens"); require(it.amount > 0) }
        }
    } } }
    npcs.forEach { (id,n) -> check(id) { ref(n.dialogue,setOf("dialogue"),"dialogue"); n.shop?.let { ref(it,setOf("shop"),"shop") } } }
    enemies.forEach { (id,e) -> check(id) {
        require(e.speed in 0.0..2.0 && e.size in 1..16 && e.aggro in 0.0..128.0 && e.leash in 0.0..256.0 && e.attackInterval > 0 && e.enrageTicks > 0 && e.enrageMultiplier > 0) { "speed/size/aggro/leash/attack_interval/enrage が範囲外です。" }
        require(e.specials.map { it.id }.distinct().size == e.specials.size && e.specials.all { it.multiplier in 0.0..100.0 }) { "specials.id/multiplier が不正です。" }
    } }
    chapters.forEach { (id,d) -> check(id) {
        mapOf("quests" to "quest","npcs" to "npc").forEach { (field,type) -> d.strings(field).forEach { ref(it,setOf(type),field) } }
    } }
}
