package net.nakaymi.etherealchronicle.core

import net.nakaymi.etherealchronicle.data.ContentSnapshot
import java.util.UUID

/** Console-only virtual inventory. Profile persistence is separate; bags deliberately reset on load. */
class SimulationSession(private val content: () -> ContentSnapshot, private val game: GameService) {
    val inventory = MemoryInventory()
    private val dialogues = DialogueEngine(content)
    private val trades = TradeEngine(content)
    private var conversation: Pair<String,String>? = null

    /** Roll back both stores if a dialogue action or transaction fails. */
    private fun atomic(p: PlayerProfile, operation: () -> String): String {
        val before=p.snapshot(); val bag=inventory.contents.toMap(); val oldConversation=conversation
        return try { operation() } catch(e: Exception) {
            p.restore(before); inventory.contents.clear(); inventory.contents.putAll(bag); conversation=oldConversation; throw e
        }
    }
    fun execute(p: PlayerProfile, operation: String, args: List<String>): String = atomic(p) {
        fun arg(i: Int) = args.getOrNull(i) ?: error("引数が不足しています: $operation")
        when(operation) {
            "inventory" -> inventory.contents.toString()
            "give" -> {
                val id=arg(0); require(content().items.find(id) != null) { "アイテムが見つかりません: $id" }
                val amount=args.getOrNull(1)?.toIntOrNull() ?: if(args.size > 1) error("数量は1..64の整数で指定してください。") else 1
                require(amount in 1..64) { "数量は1..64で指定してください。" }
                require(inventory.exchange(emptyMap(),mapOf(id to amount))) { "持ち物の上限を超えます。" }; inventory.contents.toString()
            }
            "currency" -> { val n=arg(0).toLong(); require(n in 0..content().world.tuning.maxCurrency); p.currency=n; "所持金=$n" }
            "level" -> { val n=arg(0).toInt(); require(n in 1..content().balance.levelCap); p.level=n; p.xp=0; "レベル=$n" }
            "flag" -> { if(args.getOrNull(1) == "clear") p.flags.remove(arg(0)) else p.flags.add(arg(0)); p.flags.toString() }
            "accept" -> { game.startQuest(p,arg(0)); "依頼受注=${arg(0)}" }
            "quest", "complete" -> { game.completeQuest(p,arg(0),inventory); "依頼達成=${arg(0)}" }
            "event" -> { game.quests.event(p,ObjectiveKind.valueOf(arg(0).uppercase()),arg(1),args.getOrNull(2)?.toInt() ?: 1,inventory); p.quests.toString() }
            "kill" -> { val drops=game.kill(p,arg(0),inventory); require(inventory.exchange(emptyMap(),drops)); "戦利品=$drops" }
            "dialogue" -> { val id=arg(0); val node=dialogues.node(p,id,args.getOrNull(1)); conversation=id to node.first; "${node.first}: ${node.second.text} 選択肢=${node.second.choices.map { it.id }}" }
            "choice" -> {
                val (id,node)=conversation ?: error("先に dialogue を開始してください。")
                val choice=dialogues.choice(p,id,node,arg(0)); val effects=mutableListOf<String>()
                p.flags.removeAll(choice.clearFlags); p.flags.addAll(choice.flags)
                choice.actions.forEach { a -> when(a.kind) {
                    "START_QUEST" -> game.startQuest(p,a.target)
                    "COMPLETE_QUEST" -> game.completeQuest(p,a.target,inventory)
                    "SELECT_CLASS" -> { game.chooseClass(p,a.target); starter(p) }
                    "GIVE_ITEM" -> if(a.onceFlag !in p.flags) {
                        require(a.onceFlag != null && inventory.exchange(emptyMap(),mapOf(a.target to a.amount))); p.flags.add(a.onceFlag)
                    }
                    "OPEN_SHOP", "ENTER_DUNGEON" -> effects.add("${a.kind}:${a.target} (画面・移動は実行しません)")
                } }
                conversation=choice.next?.takeIf { it.isNotEmpty() }?.let { id to dialogues.node(p,id,it).first }
                "会話=${conversation ?: "終了"} 効果=$effects"
            }
            "buy", "sell" -> {
                val tx=args.getOrNull(if(operation == "buy") 2 else 3) ?: UUID.randomUUID().toString()
                val plan=if(operation == "buy") trades.buy(p,arg(0),arg(1),inventory,tx,System.currentTimeMillis()/86_400_000)
                    else trades.sell(p,arg(0),arg(1),arg(2).toInt(),inventory,tx)
                require(inventory.exchange(plan.remove,plan.add)); trades.commit(p,plan); "取引=$tx 所持金=${p.currency} 持ち物=${inventory.contents}"
            }
            else -> error("不明なシミュレーション操作: $operation")
        }
    }
    fun starter(p: PlayerProfile) {
        if(p.starterGranted) return
        val items=content().classes[p.classId ?: error("職業未選択")].starterItems.groupingBy { it }.eachCount()
        require(inventory.exchange(emptyMap(),items)); p.starterGranted=true
    }
}
