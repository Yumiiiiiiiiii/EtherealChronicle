package net.nakaymi.etherealchronicle.core

import net.nakaymi.etherealchronicle.data.ContentSnapshot
import java.util.UUID

/** コンソール検証用の仮想持ち物と会話状態です。保存対象はプロフィールのみで、バッグはロード時に再作成します。 */
class SimulationSession(private val content: () -> ContentSnapshot, private val game: GameService) {
    val inventory = MemoryInventory()
    private val dialogues = DialogueEngine(content)
    private val trades = TradeEngine(content)
    private var conversation: Pair<String,String>? = null

    /** 会話処理が失敗した場合、プロフィール・仮想バッグ・会話位置をまとめて戻します。 */
    private fun atomic(p: PlayerProfile, operation: () -> String): String {
        val before=p.snapshot(); val bag=inventory.contents.toMap(); val oldConversation=conversation
        return try { operation() } catch(e: Exception) {
            p.restore(before); inventory.contents.clear(); inventory.contents.putAll(bag); conversation=oldConversation; throw e
        }
    }
    /** 同じcoreサービスを使って仮想プロフィールの操作を実行します。 */
    fun execute(p: PlayerProfile, operation: String, args: List<String>): String = atomic(p) {
        fun arg(i: Int) = args.getOrNull(i) ?: error("ec.error.argument_missing: $operation")
        when(operation) {
            "inventory" -> inventory.contents.toString()
            "give" -> {
                val id=arg(0); require(content().items.find(id) != null) { "ec.error.item_missing: $id" }
                val amount=args.getOrNull(1)?.toIntOrNull() ?: if(args.size > 1) error("ec.error.quantity_range") else 1
        require(amount in 1..ContentLimits.MAX_ITEM_STACK) { "ec.error.quantity_range" }
                require(inventory.exchange(emptyMap(),mapOf(id to amount))) { "ec.error.inventory_full" }; inventory.contents.toString()
            }
            "currency" -> { val n=arg(0).toLong(); require(n in 0..content().world.tuning.maxCurrency); p.currency=n; "ec.sim.currency|amount=$n" }
            "level" -> { val n=arg(0).toInt(); require(n in 1..content().balance.levelCap); p.level=n; p.xp=0; "ec.sim.level|level=$n" }
            "flag" -> { if(args.getOrNull(1) == "clear") p.flags.remove(arg(0)) else p.flags.add(arg(0)); p.flags.toString() }
            "accept" -> { game.startQuest(p,arg(0)); "ec.sim.quest_started|quest=${arg(0)}" }
            "quest", "complete" -> { game.completeQuest(p,arg(0),inventory); "ec.sim.quest_completed|quest=${arg(0)}" }
            "event" -> { game.quests.event(p,ObjectiveKind.valueOf(arg(0).uppercase()),arg(1),args.getOrNull(2)?.toInt() ?: 1,inventory); p.quests.toString() }
            "kill" -> { val drops=game.kill(p,arg(0),inventory); require(inventory.exchange(emptyMap(),drops)); "ec.sim.loot|items=$drops" }
            "dialogue" -> { val id=arg(0); val node=dialogues.node(p,id,args.getOrNull(1)); conversation=id to node.first; "ec.sim.dialogue|node=${node.first};text=${node.second.text};choices=${node.second.choices.map { it.id }}" }
            "choice" -> {
                val (id,node)=conversation ?: error("ec.error.dialogue_missing")
                val choice=dialogues.choice(p,id,node,arg(0)); val effects=mutableListOf<String>()
                p.flags.removeAll(choice.clearFlags); p.flags.addAll(choice.flags)
                choice.actions.forEach { a -> when(a.kind) {
                    "START_QUEST" -> game.startQuest(p,a.target)
                    "COMPLETE_QUEST" -> game.completeQuest(p,a.target,inventory)
                    "SELECT_CLASS" -> { game.chooseClass(p,a.target); starter(p) }
                    "GIVE_ITEM" -> if(a.onceFlag !in p.flags) {
                        require(a.onceFlag != null && inventory.exchange(emptyMap(),mapOf(a.target to a.amount))); p.flags.add(a.onceFlag)
                    }
                    "OPEN_SHOP", "ENTER_DUNGEON", "TELEPORT_AREA" -> effects.add("${a.kind}:${a.target} (not executed by simulation)")
                } }
                conversation=choice.next?.takeIf { it.isNotEmpty() }?.let { id to dialogues.node(p,id,it).first }
                "ec.sim.choice|conversation=${conversation ?: "closed"};effects=$effects"
            }
            "buy", "sell" -> {
                val tx=args.getOrNull(if(operation == "buy") 2 else 3) ?: UUID.randomUUID().toString()
                val plan=if(operation == "buy") trades.buy(p,arg(0),arg(1),inventory,tx,System.currentTimeMillis())
                    else trades.sell(p,arg(0),arg(1),arg(2).toInt(),inventory,tx)
                require(inventory.exchange(plan.remove,plan.add)); trades.commit(p,plan); "ec.sim.transaction|id=$tx;currency=${p.currency};inventory=${inventory.contents}"
            }
            else -> error("ec.error.sim_operation_unknown: $operation")
        }
    }
    /** 初回選択後のスターター権利を仮想バッグへ反映します。 */
    fun starter(p: PlayerProfile) {
        if(p.starterGranted) return
        val items=content().classes[p.classId ?: error("ec.error.class_missing")].starterItems.groupingBy { it }.eachCount()
        require(inventory.exchange(emptyMap(),items)); p.starterGranted=true
    }
}
