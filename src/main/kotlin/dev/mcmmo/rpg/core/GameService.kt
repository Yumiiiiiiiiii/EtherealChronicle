package dev.mcmmo.rpg.core

import dev.mcmmo.rpg.data.*

/** Main-thread owned gameplay operations shared by Paper events and the console simulator. */
class GameService(private val content: () -> ContentSnapshot, val skills: SkillEngine) {
    fun chooseClass(p: PlayerProfile, id: String) {
        check(p.classId == null) { "Class already selected" }
        val c = content().classes[id]
        p.classId = id
        p.resource = if(c.resource == ResourceKind.RAGE) 0.0 else c.stats.resource
        p.tutorialQuest = "ec:tutorial"
        c.skills.forEach { p.skillLevels[it] = 1 }
    }
    fun stats(p: PlayerProfile, equipment: List<ItemDefinition>): Stats {
        val base = p.classId?.let { content().classes.find(it)?.stats } ?: Stats()
        val s = equipment.filter { it.canEquip(p) }.fold(base) { acc, item -> acc + item.stats }
        val b = content().balance
        return Stats(s.hp.coerceIn(1.0,b.statCap),s.resource.coerceIn(0.0,b.statCap),s.attack.coerceIn(0.0,b.statCap),s.defense.coerceIn(0.0,b.statCap),s.crit.coerceIn(0.0,b.critCap),s.critDamage.coerceIn(1.0,b.critDamageCap),s.elementResist.coerceIn(0.0,1.0),s.skillPower.coerceIn(0.0,b.statCap))
    }
    fun kill(p: PlayerProfile, enemyId: String): Map<String,Int> {
        val c = content()
        val enemy = c.documents[enemyId] ?: error("Unknown enemy")
        require(enemy.string("type") in setOf("enemy","boss"))
        c.balance.grantXp(p,enemy.long("xp"))
        rewardCurrency(p,enemy.long("currency"))

        if (p.tutorialQuest == "ec:tutorial") completeQuest(p,"ec:tutorial")
        return (enemy["droptable"] as? String)?.let { c.drops[it].roll(kotlin.random.Random.Default) } ?: emptyMap()
    }
    fun onHit(p: PlayerProfile) {
        val c = p.classId?.let { content().classes.find(it) } ?: return
        p.resource = (p.resource+c.hitGain).coerceAtMost(c.stats.resource)
    }
    fun regenerate(p: PlayerProfile, maximum: Double, moving: Boolean) {
        val c = p.classId?.let { content().classes.find(it) } ?: return
        if(c.resource != ResourceKind.FOCUS || !moving) p.resource = (p.resource+c.regeneration).coerceIn(0.0,maximum)
    }
    fun completeQuest(p: PlayerProfile, id: String) {
        require(p.tutorialQuest == id) { "Quest is not active" }
        val q = content().documents[id] ?: error("Unknown quest")
        require(q.string("type") == "quest")
        content().balance.grantXp(p,q.long("xp")); rewardCurrency(p,q.long("currency"))
        p.tutorialQuest = "ec:tutorial_complete"
    }
    private fun rewardCurrency(p: PlayerProfile, amount: Long) {
        val gain = amount.coerceIn(0,content().balance.currencyGainCap)
        p.currency = p.currency.coerceAtMost(Long.MAX_VALUE-gain) + gain
    }
}

