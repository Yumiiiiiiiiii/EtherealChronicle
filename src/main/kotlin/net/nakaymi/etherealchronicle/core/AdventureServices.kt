package net.nakaymi.etherealchronicle.core

import net.nakaymi.etherealchronicle.data.*
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.floor

/** YAML item IDを数えて差分交換する、Paper非依存の所持品取引境界です。 */
interface InventoryAccess {
    fun count(item: String): Int
    fun canExchange(remove: Map<String,Int>, add: Map<String,Int>): Boolean
    fun exchange(remove: Map<String,Int>, add: Map<String,Int>): Boolean
    /** 指定した所持品スロットを優先して消費できるか調べます。 */
    fun canExchangeFromSlot(slot: Int?, remove: Map<String,Int>, add: Map<String,Int>): Boolean = canExchange(remove, add)
    /** 指定スロットを優先して差分交換します。 */
    fun exchangeFromSlot(slot: Int?, remove: Map<String,Int>, add: Map<String,Int>): Boolean = exchange(remove, add)
}
/** ユニットテストとコンソールsim向けの容量制限付き仮想所持品です。 */
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
/** objective条件、消費アイテム、依頼報酬をプロフィール上で進行します。 */
class QuestEngine(private val content: () -> ContentSnapshot, private val clock: Clock) {
    /** 前提条件を満たす依頼を開始し、progress状態を初期化します。 */
    fun start(p: PlayerProfile,id: String) {
        val q = content().world.quests[id] ?: error("ec.error.quest_missing")
        require(q.condition.matches(p)) { "ec.error.quest_prerequisite" }
        val old = p.quests[id]
        if(old != null) {
            val now=clock.millis()
            val eligible=if(q.resetSchedule != null) old.completed && if(old.resetPeriod != null) old.resetPeriod != ResetPeriods.key(now,q.resetSchedule,content().world.tuning) else now-old.completedAt >= q.repeatCooldown
                else old.completed && q.repeatable && now-old.completedAt >= q.repeatCooldown
            require(eligible) { "ec.error.quest_active" }
        }
        p.quests[id] = QuestProgress(completedAt=old?.completedAt ?: 0)
    }
    private fun progress(p: PlayerProfile,q: QuestDefinition,o: Objective,inventory: InventoryAccess): Int =
        if(o.kind == ObjectiveKind.COLLECT) inventory.count(o.target).coerceAtMost(o.amount) else p.quests[q.id]?.objectives?.get(o.id) ?: 0
    /** 全objectiveが揃ったかを所持品状態と合わせて確認します。 */
    fun ready(p: PlayerProfile,id: String,inventory: InventoryAccess): Boolean {
        val q = content().world.quests[id] ?: return false
        val state = p.quests[id] ?: return false
        val consume=q.objectives.filter { it.kind == ObjectiveKind.COLLECT && it.consume }.groupBy { it.target }.mapValues { (_,objectives) -> objectives.sumOf { it.amount.toLong() } }
        return !state.completed && consume.all { (item,n) -> inventory.count(item).toLong() >= n } && q.objectives.all { progress(p,q,it,inventory) >= it.amount }
    }
    /** 対象イベントを一致するobjectiveへ加算し、必要なら自動完了させます。 */
    fun event(p: PlayerProfile,kind: ObjectiveKind,target: String,amount: Int,inventory: InventoryAccess,allowAutoComplete: Boolean = true,alsoTargets:Set<String> = emptySet()) {
        require(amount > 0)
        p.quests.filterValues { !it.completed }.keys.toList().forEach { id ->
            val q = content().world.quests[id] ?: return@forEach
            val o = q.objectives.firstOrNull { progress(p,q,it,inventory) < it.amount }
            val targetMatches=o?.let {
                when(kind) {
                    ObjectiveKind.ENHANCE_GEAR -> {
                        val required=it.target.toIntOrNull(); val achieved=target.toIntOrNull()
                        it.target == "ANY" || it.target == "*" || (required != null && achieved != null && achieved >= required)
                    }
                    else -> it.target == target || it.target == "*" || it.target == "ANY" || it.target in alsoTargets
                }
            } ?: false
            if(o != null && o.kind == kind && targetMatches) {
                val state = p.quests.getValue(id)
                state.objectives[o.id] = (state.objectives.getOrDefault(o.id,0).toLong()+amount).coerceAtMost(o.amount.toLong()).toInt()
                state.progress = state.objectives.values.sum()
            }
            if(allowAutoComplete && q.autoComplete && ready(p,id,inventory)) {
                try { complete(p,id,inventory) } catch(_: IllegalArgumentException) { /* ready quests remain claimable when storage is full */ }
            }
        }
    }
    /** 自動完了対象のうち条件を満たした依頼を安定順で返します。 */
    fun readyAutoComplete(p: PlayerProfile, inventory: InventoryAccess): List<String> =
        p.quests.keys.filter { id -> content().world.quests[id]?.let { it.autoComplete && ready(p, id, inventory) } == true }
    /** 空き不足の依頼を保留しつつ、同じイベントで完了可能な他の自動依頼を処理します。 */
    fun completeReadyAuto(p: PlayerProfile, inventory: InventoryAccess): List<String> = buildList {
        readyAutoComplete(p, inventory).forEach { id ->
            try {
                complete(p, id, inventory)
                add(id)
            } catch (error: IllegalArgumentException) {
                if (error.message != "ec.error.inventory_exchange") throw error
            }
        }
    }
    /** 報酬と消費物を一度だけ反映し、repeat/reset状態を記録します。 */
    fun complete(p: PlayerProfile,id: String,inventory: InventoryAccess) {
        val q = content().world.quests[id] ?: error("ec.error.quest_missing")
        require(ready(p,id,inventory)) { "ec.error.quest_incomplete" }
        val remove = mutableMapOf<String,Int>(); val add = mutableMapOf<String,Int>()
        q.objectives.filter { it.kind == ObjectiveKind.COLLECT && it.consume }.forEach { remove.merge(it.target,it.amount,Int::plus) }
        q.rewards.items.filter { it.condition.matches(p) }.forEach { add.merge(it.item,it.amount,Int::plus) }
        require(inventory.exchange(remove,add)) { "ec.error.inventory_exchange" }
        val state=p.quests.getValue(id); state.completed=true; state.completedAt=clock.millis()
        state.resetPeriod=q.resetSchedule?.let { ResetPeriods.key(clock.millis(),it,content().world.tuning) }
        state.repeatAvailableAt=if(q.repeatable) clock.millis()+q.repeatCooldown else Long.MAX_VALUE
        content().balance.grantXp(p,q.rewards.xp)
        val cap=content().world.tuning.maxCurrency
        p.currency=(p.currency.coerceAtMost(cap)+q.rewards.currency.coerceAtMost(content().balance.currencyGainCap)).coerceAtMost(cap)
        p.flags.addAll(q.rewards.flags)
    }
}
/** flag・プロフィール条件と分岐に従って会話ノード・選択肢を解決します。 */
class DialogueEngine(private val content: () -> ContentSnapshot) {
    fun node(p: PlayerProfile,id: String,start: String? = null): Pair<String,DialogueNode> {
        val dialogue=content().world.dialogues[id] ?: error("ec.error.dialogue_missing")
        var at=start ?: dialogue.start; val visited=mutableSetOf<String>()
        repeat(32) {
            require(visited.add(at)) { "ec.error.dialogue_cycle" }
            val node=dialogue.nodes[at] ?: error("ec.error.dialogue_node_missing")
            val next=node.branches.firstOrNull { it.first.matches(p) }?.second
            if(next == null) return at to node.copy(choices=(node.choices+dialogue.globalChoices).filter { it.condition.matches(p) })
            at=next
        }
        error("ec.error.dialogue_too_deep")
    }
    fun choice(p: PlayerProfile,id: String,node: String,choice: String): DialogueChoice =
        node(p,id,node).second.choices.firstOrNull { it.id == choice } ?: error("ec.error.dialogue_choice")
}
data class TradePlan(val id: String,val currencyBefore: Long,val currencyAfter: Long,val remove: Map<String,Int>,val add: Map<String,Int>,val dailyKey: String?,val dailyCount: Int,val sourceSlot: Int? = null)
/** 取引計画の成功またはプレイヤーに返す想定内の拒否理由です。 */
sealed interface TradePlanResult {
    data class Ready(val plan: TradePlan) : TradePlanResult
    data class Rejected(val messageKey: String) : TradePlanResult
}
/** 二重実行IDと上限を確認し、所持品を変更せず取引planを作成します。 */
class TradeEngine(private val content: () -> ContentSnapshot) {
    /** 通常の購入条件不成立を例外ではなく言語キーとして返します。 */
    fun planBuy(p: PlayerProfile,shopId: String,entryId: String,inventory: InventoryAccess,transaction: String,day: Long): TradePlanResult =
        planResult { buy(p,shopId,entryId,inventory,transaction,day) }

    /** 通常の売却条件不成立を例外ではなく言語キーとして返します。 */
    fun planSell(p: PlayerProfile,shopId: String,item: String,amount: Int,inventory: InventoryAccess,transaction: String,sourceSlot: Int? = null): TradePlanResult =
        planResult { sell(p,shopId,item,amount,inventory,transaction,sourceSlot) }

    fun buy(p: PlayerProfile,shopId: String,entryId: String,inventory: InventoryAccess,transaction: String,day: Long): TradePlan {
        require(transaction !in p.transactions) { "ec.error.transaction_replayed" }
        val c=content(); val shop=c.world.shops[shopId] ?: error("ec.error.shop_missing")
        val e=shop.entries.firstOrNull { it.id == entryId } ?: error("ec.error.shop_entry_missing")
        require(e.condition.matches(p)) { "ec.error.shop_condition" }
        require(e.amount <= c.world.tuning.transactionCap) { "ec.error.shop_amount_cap" }
        val cost=ceil(e.price.toDouble()*(if(shop.discountFlag in p.flags) 1-shop.discount else 1.0)).toLong()
        require(cost >= 0 && p.currency >= cost) { "ec.error.currency_low" }
        val key="${ResetPeriods.key(day,"DAILY",c.world.tuning)}:$shopId:$entryId"; val purchased=p.dailyPurchases.getOrDefault(key,0)
        require(e.dailyLimit == 0 || purchased+e.amount <= e.dailyLimit) { "ec.error.daily_limit" }
        val remove=e.tokens.associate { it.item to it.amount }.toMutableMap()
        if(e.tokenDiscountFlag in p.flags) e.tokenDiscount.forEach { remove[it.item]=(remove.getOrDefault(it.item,0)-it.amount).coerceAtLeast(0) }
        val add=mapOf(e.item to e.amount)
        require(inventory.canExchange(remove,add)) { "ec.error.materials_or_space" }
        return TradePlan(transaction,p.currency,p.currency-cost,remove,add,key,purchased+e.amount)
    }
    fun sell(p: PlayerProfile,shopId: String,item: String,amount: Int,inventory: InventoryAccess,transaction: String,sourceSlot: Int? = null): TradePlan {
        val c=content()
        require(transaction !in p.transactions) { "ec.error.transaction_replayed" }
        require(amount in 1..c.world.tuning.transactionCap) { "ec.error.quantity_range" }
        require(c.world.shops[shopId]?.buyback == true) { "ec.error.shop_no_buyback" }
        val properties=c.world.itemProperties[item] ?: error("ec.error.item_missing")
        require(!properties.bound && !properties.questItem && properties.price > 0) { "ec.error.item_unsellable" }
        val gain=floor(properties.price.toDouble()*c.world.tuning.sellRatio).toLong()*amount
        require(gain <= c.balance.currencyGainCap && p.currency <= c.world.tuning.maxCurrency-gain) { "ec.error.currency_cap" }
        require(inventory.canExchangeFromSlot(sourceSlot,mapOf(item to amount),emptyMap())) { "ec.error.sell_items_missing" }
        return TradePlan(transaction,p.currency,p.currency+gain,mapOf(item to amount),emptyMap(),null,0,sourceSlot)
    }
    /** 事前計算planが古くないことを確認してprofile側の通貨・日次数を確定します。 */
    fun commit(p: PlayerProfile,plan: TradePlan) {
        require(plan.id !in p.transactions && p.currency == plan.currencyBefore) { "ec.error.transaction_changed" }
        p.currency=plan.currencyAfter
        plan.dailyKey?.let { key ->
            val period=key.substringBefore(":ec:")
            p.dailyPurchases.keys.removeIf { it.startsWith("D:") && !it.startsWith("$period:") }
            p.dailyPurchases[key]=plan.dailyCount
        }
        p.transactions += plan.id
        while(p.transactions.size > 128) p.transactions.removeAt(0)
    }

    private fun planResult(create: () -> TradePlan): TradePlanResult = try {
        TradePlanResult.Ready(create())
    } catch (error: Exception) {
        val key = error.message?.let(TRADE_ERROR_KEY::matchEntire)?.groupValues?.get(1)
            ?: throw error
        TradePlanResult.Rejected(key)
    }

    private companion object {
        val TRADE_ERROR_KEY = Regex("^(ec\\.error\\.[a-z0-9_]+)(?::.*)?$")
    }
}

data class WorldTuning(val sellRatio: Double,val maxCurrency: Long,val transactionCap: Int,val partyCap: Int,val partyRadius: Double,
    val enemyCap: Int,val instanceCap: Int,val instanceTimeout: Long,val weakness: Double,val resistance: Double,val playerHpCap: Double,val basicAttackCooldown: Long,val elementResCap: Double,
    val resetZone: String = "UTC",val dailyResetHour: Int = 0,val weeklyResetDay: String = "MONDAY",val enhancementCap:Int=5,val enhancementRate:Double=.03,val enhancementBaseCurrency:Long=100,val enhancementMaterial:String="ec:item_mat_artisan_whetstone")
fun Map<String,Any?>.tuning() = WorldTuning(number("sell_ratio",0.3),whole("max_currency",100_000_000),integer("transaction_amount_cap",64),integer("party_size_cap",4),number("party_xp_radius",32.0),integer("global_enemy_cap",100),integer("instance_cap",8),whole("instance_timeout_ticks",36000),number("weakness_multiplier",1.25),number("resistance_multiplier",0.8),number("player_hp_cap",1024.0),whole("basic_attack_cooldown_ms",550),number("element_res_cap",0.5),text("calendar_reset_zone","UTC"),integer("daily_reset_hour",0),text("weekly_reset_day","MONDAY"),integer("enhancement_cap",5),number("enhancement_rate",0.03),whole("enhancement_base_currency",100),text("enhancement_material","ec:item_mat_artisan_whetstone")).also {
    require(it.sellRatio in 0.0..0.49 && it.maxCurrency in 1..Long.MAX_VALUE/2 && it.transactionCap in 1..64 && it.partyCap in 1..8 && it.partyRadius in 1.0..128.0 && it.enemyCap in 1..1000 && it.instanceCap in 1..32 && it.instanceTimeout > 0 && it.playerHpCap in 1.0..1024.0 && it.basicAttackCooldown in 100..5000 && it.elementResCap in 0.0..0.9 && it.dailyResetHour in 0..23 && it.weeklyResetDay in java.time.DayOfWeek.entries.map { day -> day.name } && it.enhancementCap in 1..5 && it.enhancementRate in 0.0..0.1 && it.enhancementBaseCurrency in 0..1_000_000 && it.enhancementMaterial.startsWith("ec:item_"))
    java.time.ZoneId.of(it.resetZone)
}

object ResetPeriods {
    fun key(epochMillis: Long,schedule: String,tuning: WorldTuning): String {
        require(schedule in setOf("DAILY","WEEKLY")) { "ec.error.reset_schedule" }
        val zone=java.time.ZoneId.of(tuning.resetZone)
        val now=java.time.Instant.ofEpochMilli(epochMillis).atZone(zone)
        val date=now.toLocalDate().minusDays(if(now.hour < tuning.dailyResetHour) 1 else 0)
        return if(schedule == "DAILY") "D:$date" else {
            val start=date.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.valueOf(tuning.weeklyResetDay)))
            "W:$start"
        }
    }
}

/** 依頼・会話の複数アイテム操作を一時化し、すべて検証してから一度に確定します。 */
/** 複数操作の追加・消費差分を保持し、取引の単一commit境界を作ります。 */
class StagedInventory(private val base: InventoryAccess): InventoryAccess {
    val remove=mutableMapOf<String,Int>()
    val add=mutableMapOf<String,Int>()
    var preferredRemovalSlot: Int? = null
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
    override fun canExchangeFromSlot(slot: Int?, remove: Map<String,Int>, add: Map<String,Int>): Boolean {
        if(remove.any { (id,n) -> count(id) < n }) return false
        val (r,a)=proposed(remove,add)
        return base.canExchangeFromSlot(slot,r,a)
    }
    override fun exchangeFromSlot(slot: Int?, remove: Map<String,Int>, add: Map<String,Int>): Boolean {
        if(!canExchangeFromSlot(slot,remove,add)) return false
        val (r,a)=proposed(remove,add)
        this.remove.clear(); this.remove.putAll(r); this.add.clear(); this.add.putAll(a)
        preferredRemovalSlot=slot
        return true
    }
    override fun exchange(remove: Map<String,Int>,add: Map<String,Int>): Boolean {
        if(!canExchange(remove,add)) return false
        val (r,a)=proposed(remove,add); this.remove.clear(); this.remove.putAll(r); this.add.clear(); this.add.putAll(a); return true
    }
}
fun PlayerProfile.restore(other: PlayerProfile) {
    classId=other.classId; level=other.level; xp=other.xp; currency=other.currency; resource=other.resource; selectedSkill=other.selectedSkill; starterGranted=other.starterGranted; accessory=other.accessory
    selectedTitle=other.selectedTitle; fishingBait=other.fishingBait; bestEnhancementLevel=other.bestEnhancementLevel
    skillLevels.clear(); skillLevels.putAll(other.skillLevels); flags.clear(); flags.addAll(other.flags)
    quests.clear(); quests.putAll(other.snapshot().quests); dailyPurchases.clear(); dailyPurchases.putAll(other.dailyPurchases); transactions.clear(); transactions.addAll(other.transactions); pendingRewards.clear(); pendingRewards.putAll(other.pendingRewards); dropPity.clear(); dropPity.putAll(other.dropPity)
    challengeRecords.clear(); challengeRecords.putAll(other.snapshot().challengeRecords)
    bestiary.clear(); bestiary.putAll(other.bestiary); discoveredItems.clear(); discoveredItems.addAll(other.discoveredItems)
    lifeSkills.clear(); lifeSkills.putAll(other.snapshot().lifeSkills)
    fishRecords.clear(); fishRecords.putAll(other.snapshot().fishRecords)
    gatheringRecords.clear(); gatheringRecords.putAll(other.gatheringRecords); gatheringReadyAt.clear(); gatheringReadyAt.putAll(other.gatheringReadyAt)
}
