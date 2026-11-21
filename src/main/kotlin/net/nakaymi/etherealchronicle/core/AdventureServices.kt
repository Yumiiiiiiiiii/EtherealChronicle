package net.nakaymi.etherealchronicle.core

import net.nakaymi.etherealchronicle.data.*
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.floor

interface InventoryAccess {
    fun count(item: String): Int
    fun canExchange(remove: Map<String,Int>, add: Map<String,Int>): Boolean
    fun exchange(remove: Map<String,Int>, add: Map<String,Int>): Boolean
}
class MemoryInventory(val contents: MutableMap<String,Int> = mutableMapOf(), private val capacity: Int = Int.MAX_VALUE): InventoryAccess {
    override fun count(item: String) = contents.getOrDefault(item,0)
    private fun after(remove: Map<String,Int>,add: Map<String,Int>): Map<String,Int>? {
        val copy = contents.toMutableMap()
        if(remove.any { (id,n) -> n < 0 || count(id) < n } || add.any { it.value < 0 }) return null
        remove.forEach { (id,n) -> copy[id] = copy.getOrDefault(id,0)-n }
        add.forEach { (id,n) -> val before=copy.getOrDefault(id,0); if(n > Int.MAX_VALUE-before) return null; copy[id]=before+n }
        return copy.filterValues { it > 0 }.takeIf { it.values.sumOf(Int::toLong) <= capacity }
    }
    override fun canExchange(remove: Map<String,Int>,add: Map<String,Int>) = after(remove,add) != null
    override fun exchange(remove: Map<String,Int>,add: Map<String,Int>): Boolean {
        val result=after(remove,add) ?: return false
        contents.clear(); contents.putAll(result); return true
    }
}
class QuestEngine(private val content: () -> ContentSnapshot, private val clock: Clock) {
    fun start(p: PlayerProfile,id: String) {
        val q = content().world.quests[id] ?: error("依頼が見つかりません。")
        require(q.condition.matches(p)) { "依頼の受注条件を満たしていません。" }
        val old = p.quests[id]
        if(old != null) require(old.completed && q.repeatable && clock.millis()-old.completedAt >= q.repeatCooldown) { "この依頼は受注済みです。" }
        p.quests[id] = QuestProgress()
    }
    private fun progress(p: PlayerProfile,q: QuestDefinition,o: Objective,inventory: InventoryAccess): Int =
        if(o.kind == ObjectiveKind.COLLECT) inventory.count(o.target).coerceAtMost(o.amount) else p.quests[q.id]?.objectives?.get(o.id) ?: 0
    fun ready(p: PlayerProfile,id: String,inventory: InventoryAccess): Boolean {
        val q = content().world.quests[id] ?: return false
        val state = p.quests[id] ?: return false
        return !state.completed && q.objectives.all { progress(p,q,it,inventory) >= it.amount }
    }
    fun event(p: PlayerProfile,kind: ObjectiveKind,target: String,amount: Int,inventory: InventoryAccess) {
        require(amount > 0)
        p.quests.filterValues { !it.completed }.keys.toList().forEach { id ->
            val q = content().world.quests[id] ?: return@forEach
            val o = q.objectives.firstOrNull { progress(p,q,it,inventory) < it.amount }
            if(o != null && o.kind == kind && (o.target == target || o.target == "*")) {
                val state = p.quests.getValue(id)
                state.objectives[o.id] = (state.objectives.getOrDefault(o.id,0).toLong()+amount).coerceAtMost(o.amount.toLong()).toInt()
                state.progress = state.objectives.values.sum()
            }
            if(q.autoComplete && ready(p,id,inventory)) {
                try { complete(p,id,inventory) } catch(_: IllegalArgumentException) { /* ready quests remain claimable when storage is full */ }
            }
        }
    }
    fun complete(p: PlayerProfile,id: String,inventory: InventoryAccess) {
        val q = content().world.quests[id] ?: error("依頼が見つかりません。")
        require(ready(p,id,inventory)) { "依頼の達成条件を満たしていません。" }
        val remove = mutableMapOf<String,Int>(); val add = mutableMapOf<String,Int>()
        q.objectives.filter { it.kind == ObjectiveKind.COLLECT && it.consume }.forEach { remove.merge(it.target,it.amount,Int::plus) }
        q.rewards.items.filter { it.condition.matches(p) }.forEach { add.merge(it.item,it.amount,Int::plus) }
        require(inventory.exchange(remove,add)) { "必要品が不足しているか、持ち物に空きがありません。" }
        val state=p.quests.getValue(id); state.completed=true; state.completedAt=clock.millis()
        content().balance.grantXp(p,q.rewards.xp)
        val cap=content().world.tuning.maxCurrency
        p.currency=(p.currency.coerceAtMost(cap)+q.rewards.currency.coerceAtMost(content().balance.currencyGainCap)).coerceAtMost(cap)
        p.flags.addAll(q.rewards.flags)
    }
}
class DialogueEngine(private val content: () -> ContentSnapshot) {
    fun node(p: PlayerProfile,id: String,start: String? = null): Pair<String,DialogueNode> {
        val dialogue=content().world.dialogues[id] ?: error("会話が見つかりません。")
        var at=start ?: dialogue.start; val visited=mutableSetOf<String>()
        repeat(32) {
            require(visited.add(at)) { "会話の分岐が循環しています。" }
            val node=dialogue.nodes[at] ?: error("会話ノードが見つかりません。")
            val next=node.branches.firstOrNull { it.first.matches(p) }?.second
            if(next == null) return at to node.copy(choices=node.choices.filter { it.condition.matches(p) })
            at=next
        }
        error("会話の分岐が長すぎます。")
    }
    fun choice(p: PlayerProfile,id: String,node: String,choice: String): DialogueChoice =
        node(p,id,node).second.choices.firstOrNull { it.id == choice } ?: error("この選択肢は選べません。")
}
data class TradePlan(val id: String,val currencyBefore: Long,val currencyAfter: Long,val remove: Map<String,Int>,val add: Map<String,Int>,val dailyKey: String?,val dailyCount: Int)
class TradeEngine(private val content: () -> ContentSnapshot) {
    fun buy(p: PlayerProfile,shopId: String,entryId: String,inventory: InventoryAccess,transaction: String,day: Long): TradePlan {
        require(transaction !in p.transactions) { "処理済みの取引です。" }
        val c=content(); val shop=c.world.shops[shopId] ?: error("店が見つかりません。")
        val e=shop.entries.firstOrNull { it.id == entryId } ?: error("商品が見つかりません。")
        require(e.condition.matches(p) && e.amount <= c.world.tuning.transactionCap)
        val cost=ceil(e.price.toDouble()*(if(shop.discountFlag in p.flags) 1-shop.discount else 1.0)).toLong()
        require(cost >= 0 && p.currency >= cost) { "所持金が足りません。" }
        val key="$day:$shopId:$entryId"; val purchased=p.dailyPurchases.getOrDefault(key,0)
        require(e.dailyLimit == 0 || purchased+e.amount <= e.dailyLimit) { "本日の購入上限に達しています。" }
        val remove=e.tokens.associate { it.item to it.amount }.toMutableMap()
        if(e.tokenDiscountFlag in p.flags) e.tokenDiscount.forEach { remove[it.item]=(remove.getOrDefault(it.item,0)-it.amount).coerceAtLeast(0) }
        val add=mapOf(e.item to e.amount)
        require(inventory.canExchange(remove,add)) { "必要な素材が不足しているか、持ち物に空きがありません。" }
        return TradePlan(transaction,p.currency,p.currency-cost,remove,add,key,purchased+e.amount)
    }
    fun sell(p: PlayerProfile,shopId: String,item: String,amount: Int,inventory: InventoryAccess,transaction: String): TradePlan {
        val c=content(); require(transaction !in p.transactions && amount in 1..c.world.tuning.transactionCap)
        require(c.world.shops[shopId]?.buyback == true) { "この店は買取できません。" }
        val properties=c.world.itemProperties[item] ?: error("アイテムが見つかりません。")
        require(!properties.bound && !properties.questItem && properties.price > 0) { "このアイテムは売れません。" }
        val gain=floor(properties.price.toDouble()*c.world.tuning.sellRatio).toLong()*amount
        require(gain <= c.balance.currencyGainCap && p.currency <= c.world.tuning.maxCurrency-gain) { "所持金の上限を超えます。" }
        require(inventory.canExchange(mapOf(item to amount),emptyMap())) { "売却する品が足りません。" }
        return TradePlan(transaction,p.currency,p.currency+gain,mapOf(item to amount),emptyMap(),null,0)
    }
    fun commit(p: PlayerProfile,plan: TradePlan) {
        require(plan.id !in p.transactions && p.currency == plan.currencyBefore) { "取引の状態が変化しました。" }
        p.currency=plan.currencyAfter
        plan.dailyKey?.let { p.dailyPurchases[it]=plan.dailyCount }
        p.transactions += plan.id
        while(p.transactions.size > 128) p.transactions.removeAt(0)
    }
}

data class WorldTuning(val sellRatio: Double,val maxCurrency: Long,val transactionCap: Int,val partyCap: Int,val partyRadius: Double,
    val enemyCap: Int,val instanceCap: Int,val instanceTimeout: Long,val weakness: Double,val resistance: Double)
fun Map<String,Any?>.tuning() = WorldTuning(number("sell_ratio",0.3),whole("max_currency",100_000_000),integer("transaction_amount_cap",64),integer("party_size_cap",4),number("party_xp_radius",32.0),integer("global_enemy_cap",100),integer("instance_cap",8),whole("instance_timeout_ticks",36000),number("weakness_multiplier",1.25),number("resistance_multiplier",0.8)).also {
    require(it.sellRatio in 0.0..0.49 && it.maxCurrency in 1..Long.MAX_VALUE/2 && it.transactionCap in 1..64 && it.partyCap in 1..8 && it.partyRadius in 1.0..128.0 && it.enemyCap in 1..1000 && it.instanceCap in 1..32 && it.instanceTimeout > 0)
}

/** Stages multiple quest/dialogue inventory effects, allowing one validated commit boundary. */
class StagedInventory(private val base: InventoryAccess): InventoryAccess {
    val remove=mutableMapOf<String,Int>()
    val add=mutableMapOf<String,Int>()
    override fun count(item: String) = base.count(item)-remove.getOrDefault(item,0)+add.getOrDefault(item,0)
    private fun proposed(r: Map<String,Int>,a: Map<String,Int>): Pair<Map<String,Int>,Map<String,Int>> {
        require((r.values+a.values).all { it >= 0 })
        val removals=remove.toMutableMap(); val additions=add.toMutableMap()
        r.forEach { (id,n) -> removals.merge(id,n,Int::plus) }; a.forEach { (id,n) -> additions.merge(id,n,Int::plus) }
        (removals.keys+additions.keys).forEach { id -> val common=minOf(removals.getOrDefault(id,0),additions.getOrDefault(id,0)); removals[id]=removals.getOrDefault(id,0)-common; additions[id]=additions.getOrDefault(id,0)-common }
        return removals.filterValues { it > 0 } to additions.filterValues { it > 0 }
    }
    override fun canExchange(remove: Map<String,Int>,add: Map<String,Int>): Boolean {
        if(remove.any { (id,n) -> count(id) < n }) return false
        val (r,a)=proposed(remove,add); return base.canExchange(r,a)
    }
    override fun exchange(remove: Map<String,Int>,add: Map<String,Int>): Boolean {
        if(!canExchange(remove,add)) return false
        val (r,a)=proposed(remove,add); this.remove.clear(); this.remove.putAll(r); this.add.clear(); this.add.putAll(a); return true
    }
}
fun PlayerProfile.restore(other: PlayerProfile) {
    classId=other.classId; level=other.level; xp=other.xp; currency=other.currency; resource=other.resource; selectedSkill=other.selectedSkill; starterGranted=other.starterGranted; accessory=other.accessory
    skillLevels.clear(); skillLevels.putAll(other.skillLevels); flags.clear(); flags.addAll(other.flags)
    quests.clear(); quests.putAll(other.snapshot().quests); dailyPurchases.clear(); dailyPurchases.putAll(other.dailyPurchases); transactions.clear(); transactions.addAll(other.transactions)
}

