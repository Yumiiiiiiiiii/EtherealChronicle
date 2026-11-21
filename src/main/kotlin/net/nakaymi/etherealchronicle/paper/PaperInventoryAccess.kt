package net.nakaymi.etherealchronicle.paper

import net.nakaymi.etherealchronicle.core.InventoryAccess
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/** Main-thread inventory transaction port. Clones are validated before replacing live storage. */
class PaperInventoryAccess(private val player: Player,private val identify: (ItemStack?) -> String?,private val create: (String,Int) -> ItemStack,
    private val onConsume: () -> Unit = {}): InventoryAccess {
    override fun count(item: String): Int = player.inventory.storageContents.filterNotNull().filter { identify(it) == item }.sumOf { it.amount }
    private fun preview(remove: Map<String,Int>,add: Map<String,Int>): Array<ItemStack?>? {
        val slots: Array<ItemStack?> = player.inventory.storageContents.map { it?.clone() }.toTypedArray()
        for((id,n) in remove) {
            if(n < 0) return null
            var remaining=n
            for(i in slots.indices) {
                val stack=slots[i] ?: continue
                if(identify(stack) == id) { val take=minOf(remaining,stack.amount); stack.amount-=take; remaining-=take; if(stack.amount == 0) slots[i]=null }
            }
            if(remaining != 0) return null
        }
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
        return slots
    }
    override fun canExchange(remove: Map<String,Int>,add: Map<String,Int>) = preview(remove,add) != null
    override fun exchange(remove: Map<String,Int>,add: Map<String,Int>): Boolean {
        val after=preview(remove,add) ?: return false
        if(remove.isNotEmpty()) {
            player.inventory.storageContents=preview(remove,emptyMap()) ?: return false
            player.saveData() // lead-approved, rare item-consuming action; persist removal before currency payout
            onConsume()
        }
        player.inventory.storageContents=after
        return true
    }
}
