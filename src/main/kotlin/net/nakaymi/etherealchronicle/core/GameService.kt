package net.nakaymi.etherealchronicle.core

import net.nakaymi.etherealchronicle.data.*

/** Main-thread owned gameplay operations shared by Paper events and the console simulator. */
class GameService(private val content: () -> ContentSnapshot, val skills: SkillEngine) {
    val quests = QuestEngine(content,Clock { System.currentTimeMillis() })
    fun chooseClass(p: PlayerProfile, id: String) {
        content().classes[id]
        check(p.classId == null) { "職業は既に選択されています。" }
        changeClass(p,id)
        p.flags.add("ec:flag_class_selected")
        val initial = if("ec:quest_m01_awakening" in content().world.quests) "ec:quest_m01_awakening" else "ec:tutorial"
        if(initial !in p.quests) startQuest(p,initial)
    }
    /** Administrative respec preserves level, quests and starter entitlement; resets class skill ranks. */
    fun changeClass(p: PlayerProfile, id: String) {
        val c = content().classes[id]
        p.classId = c.id; p.selectedSkill = 0
        p.resource = if(c.resource == ResourceKind.RAGE) 0.0 else c.stats.resource
        p.skillLevels.clear(); c.skills.forEach { p.skillLevels[it] = 1 }
    }
    fun stats(p: PlayerProfile, equipment: List<ItemDefinition>): Stats {
        val c = p.classId?.let { content().classes.find(it) }
        val base = c?.let { it.stats + it.growth * (p.level-1).toDouble() } ?: Stats()
        val s = equipment.filter { it.canEquip(p) }.fold(base) { acc, item -> acc + item.stats }
        val b = content().balance
        return Stats(s.hp.coerceIn(1.0,minOf(b.statCap,content().world.tuning.playerHpCap)),s.resource.coerceIn(0.0,b.statCap),s.attack.coerceIn(0.0,b.statCap),s.defense.coerceIn(0.0,b.statCap),s.crit.coerceIn(0.0,b.critCap),s.critDamage.coerceIn(1.0,b.critDamageCap),s.elementResist.coerceIn(0.0,content().world.tuning.elementResCap),s.skillPower.coerceIn(0.0,b.statCap))
    }
    fun kill(p: PlayerProfile, enemyId: String, inventory: InventoryAccess = MemoryInventory(), xpAward: Long? = null, currencyAward: Long? = null, rollDrops: Boolean = true): Map<String,Int> {
        val c = content()
        val enemy = c.documents[enemyId] ?: error("魔物が見つかりません。")
        require(enemy.string("type") in setOf("enemy","boss"))
        c.balance.grantXp(p,xpAward ?: enemy.long("xp")); rewardCurrency(p,currencyAward ?: enemy.long("currency"))
        p.flags.addAll(c.world.enemies[enemyId]?.flags ?: emptySet())
        quests.event(p,ObjectiveKind.KILL,enemyId,1,inventory)
        if(!rollDrops) return emptyMap()
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
    fun startQuest(p: PlayerProfile,id: String) = quests.start(p,id)
    fun completeQuest(p: PlayerProfile,id: String,inventory: InventoryAccess = MemoryInventory()) = quests.complete(p,id,inventory)
    private fun rewardCurrency(p: PlayerProfile, amount: Long) {
        val gain = amount.coerceIn(0,content().balance.currencyGainCap)
        p.currency = (p.currency.coerceAtMost(content().world.tuning.maxCurrency)+gain).coerceAtMost(content().world.tuning.maxCurrency)
    }
}








