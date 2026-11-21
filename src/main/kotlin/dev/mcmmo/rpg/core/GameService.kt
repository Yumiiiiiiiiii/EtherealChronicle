package dev.mcmmo.rpg.core

import dev.mcmmo.rpg.data.*

/** Main-thread owned gameplay operations shared by Paper events and the console simulator. */
class GameService(private val content: () -> ContentSnapshot, val skills: SkillEngine) {
    fun chooseClass(p: PlayerProfile, id: String) {
        check(p.classId == null) { "職業は既に選択されています。" }
        changeClass(p,id); startQuest(p,"ec:tutorial")
    }
    /** Administrative respec preserves level, quests and starter entitlement; resets class skill ranks. */
    fun changeClass(p: PlayerProfile, id: String) {
        val c = content().classes[id]
        p.classId = id; p.selectedSkill = 0
        p.resource = if(c.resource == ResourceKind.RAGE) 0.0 else c.stats.resource
        p.skillLevels.clear(); c.skills.forEach { p.skillLevels[it] = 1 }
    }
    fun stats(p: PlayerProfile, equipment: List<ItemDefinition>): Stats {
        val base = p.classId?.let { content().classes.find(it)?.stats } ?: Stats()
        val s = equipment.filter { it.canEquip(p) }.fold(base) { acc, item -> acc + item.stats }
        val b = content().balance
        return Stats(s.hp.coerceIn(1.0,b.statCap),s.resource.coerceIn(0.0,b.statCap),s.attack.coerceIn(0.0,b.statCap),s.defense.coerceIn(0.0,b.statCap),s.crit.coerceIn(0.0,b.critCap),s.critDamage.coerceIn(1.0,b.critDamageCap),s.elementResist.coerceIn(0.0,1.0),s.skillPower.coerceIn(0.0,b.statCap))
    }
    fun kill(p: PlayerProfile, enemyId: String): Map<String,Int> {
        val c = content()
        val enemy = c.documents[enemyId] ?: error("魔物が見つかりません。")
        require(enemy.string("type") in setOf("enemy","boss"))
        c.balance.grantXp(p,enemy.long("xp")); rewardCurrency(p,enemy.long("currency"))
        p.quests.filterValues { !it.completed }.forEach { (id,progress) ->
            val q = c.documents[id] ?: return@forEach
            if(q["kill_target"] == null || q["kill_target"] == enemyId) {
                progress.progress = (progress.progress+1).coerceAtMost(q.int("required_kills"))
                if(progress.progress >= q.int("required_kills")) completeQuest(p,id)
            }
        }
        return (enemy["droptable"] as? String)?.let { c.drops[it].roll(kotlin.random.Random.Default) } ?: emptyMap()
    }
    fun onHit(p: PlayerProfile, maximum: Double = p.classId?.let { content().classes[it].stats.resource } ?: 0.0) {
        val c = p.classId?.let { content().classes.find(it) } ?: return
        p.resource = (p.resource+c.hitGain).coerceAtMost(maximum)
    }
    fun regenerate(p: PlayerProfile, maximum: Double, moving: Boolean) {
        val c = p.classId?.let { content().classes.find(it) } ?: return
        if(c.resource != ResourceKind.FOCUS || !moving) p.resource = (p.resource+c.regeneration).coerceIn(0.0,maximum)
    }
    fun startQuest(p: PlayerProfile, id: String) {
        require(content().documents[id]?.get("type") == "quest") { "依頼が見つかりません。" }
        p.quests.putIfAbsent(id,QuestProgress())
    }
    fun completeQuest(p: PlayerProfile, id: String) {
        val progress = p.quests[id] ?: error("依頼を受注していません。")
        val q = content().documents[id] ?: error("依頼が見つかりません。")
        require(!progress.completed && progress.progress >= q.int("required_kills")) { "依頼の達成条件を満たしていません。" }
        content().balance.grantXp(p,q.long("xp")); rewardCurrency(p,q.long("currency"))
        progress.completed = true
    }
    private fun rewardCurrency(p: PlayerProfile, amount: Long) {
        val gain = amount.coerceIn(0,content().balance.currencyGainCap)
        p.currency = p.currency.coerceAtMost(Long.MAX_VALUE-gain) + gain
    }
}
