package net.nakaymi.etherealchronicle.paper

import net.nakaymi.etherealchronicle.core.InventoryAccess
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/** メインスレッド上の所持品取引境界です。変更後cloneを検証してから実inventoryへ反映します。 */
class PaperInventoryAccess(private val player: Player,private val identify: (ItemStack?) -> String?,private val create: (String,Int) -> ItemStack,
    private val persistRemoval: Boolean = true,private val onConsume: () -> Unit = {}): InventoryAccess {
    private class Plan(val afterRemoval: Array<ItemStack?>, val finalContents: Array<ItemStack?>)
    override fun count(item: String): Int = player.inventory.storageContents.filterNotNull().filter { identify(it) == item }.sumOf { it.amount }
    private fun plan(remove: Map<String,Int>,add: Map<String,Int>,preferredSlot: Int? = null): Plan? {
        if (preferredSlot != null && preferredSlot !in 0..35) return null
        val slots: Array<ItemStack?> = player.inventory.storageContents.map { it?.clone() }.toTypedArray()
        for((id,n) in remove) {
            if(n < 0) return null
            var remaining=n
            val order = if (preferredSlot != null && preferredSlot in slots.indices) listOf(preferredSlot) + slots.indices.filter { it != preferredSlot } else slots.indices.toList()
            for(i in order) {
                val stack=slots[i] ?: continue
                if(identify(stack) == id) { val take=minOf(remaining,stack.amount); stack.amount-=take; remaining-=take; if(stack.amount == 0) slots[i]=null }
            }
            if(remaining != 0) return null
        }
        val afterRemoval = if (remove.isEmpty()) emptyArray() else slots.map { it?.clone() }.toTypedArray()
        for((id,n) in add) {
            if(n < 0) return null
            var remaining=n; val example=create(id,1)
            for(i in slots.indices) {
                val stack=slots[i] ?: continue
                if(stack.isSimilar(example)) { val amount=minOf(remaining,stack.maxStackSize-stack.amount); stack.amount+=amount; remaining-=amount }
            }
            for(i in slots.indices) {
                if(remaining == 0) break
                if(i != 8 && (slots[i] == null || slots[i]!!.type.isAir)) { val amount=minOf(remaining,example.maxStackSize); slots[i]=create(id,amount); remaining-=amount }
            }
            if(remaining != 0) return null
        }
        return Plan(afterRemoval, slots)
    }
    override fun canExchange(remove: Map<String,Int>,add: Map<String,Int>) = plan(remove,add) != null
    override fun canExchangeFromSlot(slot: Int?,remove: Map<String,Int>,add: Map<String,Int>) = plan(remove,add,slot) != null
    override fun exchange(remove: Map<String,Int>,add: Map<String,Int>): Boolean = exchangeFromSlot(null,remove,add)

    override fun exchangeFromSlot(slot: Int?,remove: Map<String,Int>,add: Map<String,Int>): Boolean {
        val plan=plan(remove,add,slot) ?: return false
        if(remove.isNotEmpty()) {
            player.inventory.storageContents=plan.afterRemoval
            if(persistRemoval) {
                // 支払い前に消費状態をプレイヤーデータへ保存し、通常切断時の素材復活を抑えます。
                player.saveData()
                onConsume()
            }
        }
        player.inventory.storageContents=plan.finalContents
        return true
    }
}
