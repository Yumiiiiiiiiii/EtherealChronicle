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
        val q = content().world.quests[id] ?: throw GameException("ec.error.quest_missing")
        requireGame(q.condition.matches(p), "ec.error.quest_prerequisite")
        val old = p.quests[id]
        if(old != null) {
            val now=clock.millis()
            val eligible=if(q.resetSchedule != null) old.completed && if(old.resetPeriod != null) old.resetPeriod != ResetPeriods.key(now,q.resetSchedule,content().world.tuning) else now-old.completedAt >= q.repeatCooldown
                else old.completed && q.repeatable && now-old.completedAt >= q.repeatCooldown
            requireGame(eligible, "ec.error.quest_active")
        }
        p.quests[id] = QuestProgress(completedAt=old?.completedAt ?: 0)
    }
    private fun progress(p: PlayerProfile,q: QuestDefinition,o: Objective,inventory: InventoryAccess): Int =
        if(o.kind == ObjectiveKind.COLLECT) inventory.count(o.target).coerceAtMost(o.amount) else p.quests[q.id]?.objectives?.get(o.id) ?: 0
    /** インベントリを複製する前に、探索または自動達成を見込める依頼だけを選びます。 */
    fun mayNeedPeriodicUpdate(p: PlayerProfile, areaIds: Set<String>): Boolean =
        p.quests.any { (id, state) ->
            if (state.completed) return@any false
            val quest = content().world.quests[id] ?: return@any false
            val hasPendingExploration = quest.objectives.withIndex().any { (index, objective) ->
                objective.kind == ObjectiveKind.EXPLORE &&
                    areaIds.isNotEmpty() &&
                    (objective.target in areaIds || objective.target == "*" || objective.target == "ANY") &&
                    progress(p, quest, objective, EmptyInventory) < objective.amount &&
                    quest.objectives.take(index).all { earlier ->
                        earlier.kind == ObjectiveKind.COLLECT ||
                            progress(p, quest, earlier, EmptyInventory) >= earlier.amount
                    }
            }
            hasPendingExploration || quest.autoComplete && quest.objectives
                .filter { it.kind != ObjectiveKind.COLLECT }
                .all { progress(p, quest, it, EmptyInventory) >= it.amount }
        }

    /** 毎秒の保存取引が必要な進行中依頼だけを読み取り判定します。 */
    fun needsPeriodicUpdate(p: PlayerProfile, inventory: InventoryAccess, areaIds: Set<String>): Boolean {
        if (!mayNeedPeriodicUpdate(p, areaIds)) return false
        return p.quests.any { (id, state) ->
            if (state.completed) return@any false
            val quest = content().world.quests[id] ?: return@any false
            val nextObjective = quest.objectives.firstOrNull { progress(p, quest, it, inventory) < it.amount }
            (quest.autoComplete && ready(p, id, inventory)) || nextObjective?.let { objective ->
                objective.kind == ObjectiveKind.EXPLORE &&
                    areaIds.isNotEmpty() &&
                    (objective.target in areaIds || objective.target == "*" || objective.target == "ANY")
            } == true
        }
    }
    private object EmptyInventory : InventoryAccess {
        override fun count(item: String): Int = 0
        override fun canExchange(remove: Map<String, Int>, add: Map<String, Int>): Boolean = false
        override fun exchange(remove: Map<String, Int>, add: Map<String, Int>): Boolean = false
        override fun canExchangeFromSlot(slot: Int?, remove: Map<String, Int>, add: Map<String, Int>): Boolean = false
        override fun exchangeFromSlot(slot: Int?, remove: Map<String, Int>, add: Map<String, Int>): Boolean = false
    }
    /** 全objectiveが揃ったかを所持品状態と合わせて確認します。 */
    fun ready(p: PlayerProfile,id: String,inventory: InventoryAccess): Boolean {
        val q = content().world.quests[id] ?: return false
        val state = p.quests[id] ?: return false
        val consume=q.objectives.filter { it.kind == ObjectiveKind.COLLECT && it.consume }.groupBy { it.target }.mapValues { (_,objectives) -> objectives.sumOf { it.amount.toLong() } }
        return !state.completed && consume.all { (item,n) -> inventory.count(item).toLong() >= n } && q.objectives.all { progress(p,q,it,inventory) >= it.amount }
    }
    /** 対象イベントを一致するobjectiveへ加算し、必要なら自動完了させます。 */
    fun event(p: PlayerProfile,kind: ObjectiveKind,target: String,amount: Int,inventory: InventoryAccess,allowAutoComplete: Boolean = true,alsoTargets:Set<String> = emptySet()): Boolean {
        require(amount > 0)
        var changed = false
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
                changed = true
            }
            if(allowAutoComplete && q.autoComplete && ready(p,id,inventory)) {
                try { complete(p,id,inventory) } catch (error: GameException) {
                    if (error.messageKey != "ec.error.inventory_exchange") throw error
                }
                if(p.quests[id]?.completed == true) changed = true
            }
        }
        return changed
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
            } catch (error: GameException) {
                if (error.messageKey != "ec.error.inventory_exchange") throw error
            }
        }
    }
    /** 報酬と消費物を一度だけ反映し、repeat/reset状態を記録します。 */
    fun complete(p: PlayerProfile,id: String,inventory: InventoryAccess) {
        val q = content().world.quests[id] ?: throw GameException("ec.error.quest_missing")
        requireGame(ready(p,id,inventory), "ec.error.quest_incomplete")
        val remove = mutableMapOf<String,Int>(); val add = mutableMapOf<String,Int>()
        q.objectives.filter { it.kind == ObjectiveKind.COLLECT && it.consume }.forEach { remove.merge(it.target,it.amount,Int::plus) }
        q.rewards.items.filter { it.condition.matches(p) }.forEach { add.merge(it.item,it.amount,Int::plus) }
        requireGame(inventory.exchange(remove,add), "ec.error.inventory_exchange")
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
        val dialogue=content().world.dialogues[id] ?: throw GameException("ec.error.dialogue_missing")
        var at=start ?: dialogue.start; val visited=mutableSetOf<String>()
        repeat(GameLimits.MAX_DIALOGUE_BRANCH_DEPTH) {
            requireGame(visited.add(at), "ec.error.dialogue_cycle")
            val node=dialogue.nodes[at] ?: throw GameException("ec.error.dialogue_node_missing")
            val next=node.branches.firstOrNull { it.first.matches(p) }?.second
            if(next == null) return at to node.copy(choices=(node.choices+dialogue.globalChoices).filter { it.condition.matches(p) })
            at=next
        }
        throw GameException("ec.error.dialogue_too_deep")
    }
    fun choice(p: PlayerProfile,id: String,node: String,choice: String): DialogueChoice =
        node(p,id,node).second.choices.firstOrNull { it.id == choice } ?: throw GameException("ec.error.dialogue_choice")
}
data class TradePlan(val id: String,val currencyBefore: Long,val currencyAfter: Long,val remove: Map<String,Int>,val add: Map<String,Int>,val dailyKey: String?,val dailyCount: Int,val sourceSlot: Int? = null)
/** 取引計画の成功またはプレイヤーに返す想定内の拒否理由です。 */
sealed interface TradePlanResult {
    data class Ready(val plan: TradePlan) : TradePlanResult
    data class Rejected(val messageKey: String) : TradePlanResult
}
/** プレイヤーへ返す想定内の失敗理由を、制御フロー用例外として型で保持します。 */
class GameException(val messageKey: String, val arguments: Map<String, Any?> = emptyMap()) : IllegalArgumentException(messageKey)

/** 条件不成立時に翻訳キーを持つゲーム例外を送出します。 */
internal fun requireGame(condition: Boolean, key: String) {
    if (!condition) throw GameException(key)
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
        requireGame(transaction !in p.transactions, "ec.error.transaction_replayed")
        val c=content(); val shop=c.world.shops[shopId] ?: throw GameException("ec.error.shop_missing")
        val e=shop.entries.firstOrNull { it.id == entryId } ?: throw GameException("ec.error.shop_entry_missing")
        requireGame(e.condition.matches(p), "ec.error.shop_condition")
        requireGame(e.amount <= c.world.tuning.transactionCap, "ec.error.shop_amount_cap")
        val cost=ceil(e.price.toDouble()*(if(shop.discountFlag in p.flags) 1-shop.discount else 1.0)).toLong()
        requireGame(cost >= 0 && p.currency >= cost, "ec.error.currency_low")
        val key="${ResetPeriods.key(day,"DAILY",c.world.tuning)}:$shopId:$entryId"; val purchased=p.dailyPurchases.getOrDefault(key,0)
        requireGame(e.dailyLimit == 0 || purchased+e.amount <= e.dailyLimit, "ec.error.daily_limit")
        val remove=e.tokens.associate { it.item to it.amount }.toMutableMap()
        if(e.tokenDiscountFlag in p.flags) e.tokenDiscount.forEach { remove[it.item]=(remove.getOrDefault(it.item,0)-it.amount).coerceAtLeast(0) }
        val add=mapOf(e.item to e.amount)
        requireGame(inventory.canExchange(remove,add), "ec.error.materials_or_space")
        return TradePlan(transaction,p.currency,p.currency-cost,remove,add,key,purchased+e.amount)
    }
    fun sell(p: PlayerProfile,shopId: String,item: String,amount: Int,inventory: InventoryAccess,transaction: String,sourceSlot: Int? = null): TradePlan {
        val c=content()
        requireGame(transaction !in p.transactions, "ec.error.transaction_replayed")
        requireGame(amount in 1..c.world.tuning.transactionCap, "ec.error.quantity_range")
        requireGame(c.world.shops[shopId]?.buyback == true, "ec.error.shop_no_buyback")
        val properties=c.world.itemProperties[item] ?: throw GameException("ec.error.item_missing")
        requireGame(!properties.bound && !properties.questItem && properties.price > 0, "ec.error.item_unsellable")
        val gain=floor(properties.price.toDouble()*c.world.tuning.sellRatio).toLong()*amount
        requireGame(gain <= c.balance.currencyGainCap && p.currency <= c.world.tuning.maxCurrency-gain, "ec.error.currency_cap")
        requireGame(inventory.canExchangeFromSlot(sourceSlot,mapOf(item to amount),emptyMap()), "ec.error.sell_items_missing")
        return TradePlan(transaction,p.currency,p.currency+gain,mapOf(item to amount),emptyMap(),null,0,sourceSlot)
    }
    /** 事前計算planが古くないことを確認してprofile側の通貨・日次数を確定します。 */
    fun commit(p: PlayerProfile,plan: TradePlan) {
        requireGame(plan.id !in p.transactions && p.currency == plan.currencyBefore, "ec.error.transaction_changed")
        p.currency=plan.currencyAfter
        plan.dailyKey?.let { key ->
            val period=key.substringBefore(":ec:")
            p.dailyPurchases.keys.removeIf { it.startsWith("D:") && !it.startsWith("$period:") }
            p.dailyPurchases[key]=plan.dailyCount
        }
        p.transactions += plan.id
        while(p.transactions.size > GameLimits.MAX_TRANSACTION_HISTORY) p.transactions.removeAt(0)
    }

    private fun planResult(create: () -> TradePlan): TradePlanResult = try {
        TradePlanResult.Ready(create())
    } catch (error: GameException) {
        TradePlanResult.Rejected(error.messageKey)
    }
}

data class WorldTuning(
    val sellRatio: Double = WorldTuningDefaults.SELL_RATIO,
    val maxCurrency: Long = WorldTuningDefaults.MAX_CURRENCY,
    val transactionCap: Int = WorldTuningDefaults.TRANSACTION_AMOUNT_CAP,
    val partyCap: Int = WorldTuningDefaults.PARTY_SIZE_CAP,
    val partyRadius: Double = WorldTuningDefaults.PARTY_XP_RADIUS_BLOCKS,
    val enemyCap: Int = WorldTuningDefaults.GLOBAL_ENEMY_CAP,
    val instanceCap: Int = WorldTuningDefaults.DUNGEON_INSTANCE_CAP,
    val instanceTimeout: Long = WorldTuningDefaults.DUNGEON_INSTANCE_TIMEOUT_TICKS,
    val weakness: Double = WorldTuningDefaults.WEAKNESS_MULTIPLIER,
    val resistance: Double = WorldTuningDefaults.RESISTANCE_MULTIPLIER,
    val playerHpCap: Double = WorldTuningDefaults.PLAYER_HP_CAP,
    val basicAttackCooldown: Long = WorldTuningDefaults.BASIC_ATTACK_COOLDOWN_MILLIS,
    val elementResCap: Double = WorldTuningDefaults.ELEMENT_RESISTANCE_CAP,
    val resetZone: String = WorldTuningDefaults.RESET_ZONE,
    val dailyResetHour: Int = WorldTuningDefaults.DAILY_RESET_HOUR,
    val weeklyResetDay: String = WorldTuningDefaults.WEEKLY_RESET_DAY,
    val enhancementCap: Int = WorldTuningDefaults.ENHANCEMENT_CAP,
    val enhancementRate: Double = WorldTuningDefaults.ENHANCEMENT_RATE,
    val enhancementBaseCurrency: Long = WorldTuningDefaults.ENHANCEMENT_BASE_CURRENCY,
    val enhancementMaterial: String = WorldTuningDefaults.ENHANCEMENT_MATERIAL,
)

fun Map<String,Any?>.tuning() = WorldTuning(
    sellRatio = number("sell_ratio", WorldTuningDefaults.SELL_RATIO),
    maxCurrency = whole("max_currency", WorldTuningDefaults.MAX_CURRENCY),
    transactionCap = integer("transaction_amount_cap", WorldTuningDefaults.TRANSACTION_AMOUNT_CAP),
    partyCap = integer("party_size_cap", WorldTuningDefaults.PARTY_SIZE_CAP),
    partyRadius = number("party_xp_radius", WorldTuningDefaults.PARTY_XP_RADIUS_BLOCKS),
    enemyCap = integer("global_enemy_cap", WorldTuningDefaults.GLOBAL_ENEMY_CAP),
    instanceCap = integer("instance_cap", WorldTuningDefaults.DUNGEON_INSTANCE_CAP),
    instanceTimeout = whole("instance_timeout_ticks", WorldTuningDefaults.DUNGEON_INSTANCE_TIMEOUT_TICKS),
    weakness = number("weakness_multiplier", WorldTuningDefaults.WEAKNESS_MULTIPLIER),
    resistance = number("resistance_multiplier", WorldTuningDefaults.RESISTANCE_MULTIPLIER),
    playerHpCap = number("player_hp_cap", WorldTuningDefaults.PLAYER_HP_CAP),
    basicAttackCooldown = whole("basic_attack_cooldown_ms", WorldTuningDefaults.BASIC_ATTACK_COOLDOWN_MILLIS),
    elementResCap = number("element_res_cap", WorldTuningDefaults.ELEMENT_RESISTANCE_CAP),
    resetZone = text("calendar_reset_zone", WorldTuningDefaults.RESET_ZONE),
    dailyResetHour = integer("daily_reset_hour", WorldTuningDefaults.DAILY_RESET_HOUR),
    weeklyResetDay = text("weekly_reset_day", WorldTuningDefaults.WEEKLY_RESET_DAY),
    enhancementCap = integer("enhancement_cap", WorldTuningDefaults.ENHANCEMENT_CAP),
    enhancementRate = number("enhancement_rate", WorldTuningDefaults.ENHANCEMENT_RATE),
    enhancementBaseCurrency = whole("enhancement_base_currency", WorldTuningDefaults.ENHANCEMENT_BASE_CURRENCY),
    enhancementMaterial = text("enhancement_material", WorldTuningDefaults.ENHANCEMENT_MATERIAL),
).also {
    require(it.sellRatio in 0.0..WorldTuningLimits.MAX_SELL_RATIO && it.maxCurrency in 1..WorldTuningLimits.MAX_CURRENCY && it.transactionCap in 1..WorldTuningLimits.MAX_TRANSACTION_AMOUNT && it.partyCap in 1..WorldTuningLimits.MAX_PARTY_SIZE && it.partyRadius in 1.0..WorldTuningLimits.MAX_PARTY_XP_RADIUS_BLOCKS && it.enemyCap in 1..WorldTuningLimits.MAX_GLOBAL_ENEMY_CAP && it.instanceCap in 1..WorldTuningLimits.MAX_DUNGEON_INSTANCES && it.instanceTimeout > 0 && it.playerHpCap in 1.0..WorldTuningLimits.MAX_PLAYER_HP && it.basicAttackCooldown in WorldTuningLimits.MIN_BASIC_ATTACK_COOLDOWN_MILLIS..WorldTuningLimits.MAX_BASIC_ATTACK_COOLDOWN_MILLIS && it.elementResCap in 0.0..WorldTuningLimits.MAX_ELEMENT_RESISTANCE && it.dailyResetHour in 0..WorldTuningLimits.MAX_DAILY_RESET_HOUR && it.weeklyResetDay in java.time.DayOfWeek.entries.map { day -> day.name } && it.enhancementCap in 1..GameLimits.MAX_ENHANCEMENT_LEVEL && it.enhancementRate in 0.0..WorldTuningLimits.MAX_ENHANCEMENT_RATE && it.enhancementBaseCurrency in 0..WorldTuningLimits.MAX_ENHANCEMENT_BASE_CURRENCY && it.enhancementMaterial.startsWith("ec:item_"))
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
