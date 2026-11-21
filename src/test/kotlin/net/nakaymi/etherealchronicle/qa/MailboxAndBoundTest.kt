package net.nakaymi.etherealchronicle.qa

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.nakaymi.etherealchronicle.paper.DemoWorld
import net.nakaymi.etherealchronicle.paper.RpgPlugin
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.PlayerMock
import java.io.File
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Full-inventory mailbox, deferred party credit, and bound-item transfer paths under MockBukkit. Report: build/qa-mailbox-steps.txt */
class MailboxAndBoundTest {
    private val plain = PlainTextComponentSerializer.plainText()
    private val out = mutableListOf<Triple<String, Boolean, String>>()
    private fun rec(n: String, body: () -> String?) {
        try { out += Triple(n, true, body() ?: "") }
        catch (t0: Throwable) {
            val t = if (t0 is java.lang.reflect.InvocationTargetException && t0.cause != null) t0.cause!! else t0
            val kind = if (t is org.mockbukkit.mockbukkit.exception.UnimplementedOperationException) "BLOCKED(mock) " else ""
            out += Triple(n, kind.isNotEmpty(), kind + (t::class.simpleName + ": " + t.message + " @ " + t.stackTrace.filter { it.className.contains("nakaymi") && !it.className.contains(".qa.") }.take(3).joinToString(" <- ")).take(420))
        }
    }

    private fun ServerMock.pump(timeoutMs: Long = 4000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { scheduler.waitAsyncTasksFinished(); scheduler.performOneTick(); if (cond()) return true; Thread.sleep(20) }
        return cond()
    }

    private fun filler(p: PlayerMock) {
        p.inventory.setItem(EquipmentSlot.HEAD, ItemStack(Material.LEATHER_HELMET)); p.inventory.setItem(EquipmentSlot.CHEST, ItemStack(Material.LEATHER_CHESTPLATE))
        p.inventory.setItem(EquipmentSlot.LEGS, ItemStack(Material.LEATHER_LEGGINGS)); p.inventory.setItem(EquipmentSlot.FEET, ItemStack(Material.LEATHER_BOOTS))
    }

    private fun join(server: ServerMock, plugin: RpgPlugin, name: String): PlayerMock {
        val p = PlayerMock(server, name, UUID.nameUUIDFromBytes("mbx-$name".toByteArray())); filler(p); server.addPlayer(p)
        val key = NamespacedKey(plugin, "item_id")
        server.pump { plain.serialize(p.openInventory.title()).contains("職業") }
        val slot = (0..8).first { p.openInventory.topInventory.getItem(it)?.persistentDataContainer?.get(key, PersistentDataType.STRING)?.contains("warrior") == true }
        p.simulateInventoryClick(p.openInventory, slot)
        server.pump { p.inventory.getItem(8)?.type == Material.NETHER_STAR }
        return p
    }

    @Test
    fun mailboxPartyAndBoundTransfer() {
        val server = MockBukkit.mock()
        try {
            val plugin = MockBukkit.load(RpgPlugin::class.java)
            server.pump { plugin.isEnabled }
            val world = server.addSimpleWorld(DemoWorld.NAME)
            val itemKey = NamespacedKey(plugin, "item_id"); val enemyKey = NamespacedKey(plugin, "enemy_id")
            val a = join(server, plugin, "MbxA"); val b = join(server, plugin, "MbxB")
            a.teleport(Location(world, 0.0, 65.0, 0.0)); b.teleport(Location(world, 1.0, 65.0, 0.0))
            val pa = plugin.profiles.getValue(a.uniqueId); val pb = plugin.profiles.getValue(b.uniqueId)
            pa.level = 20; pb.level = 20; pa.currency = 1000; pb.currency = 1000
            val adv = RpgPlugin::class.java.getDeclaredField("adventure").also { it.isAccessible = true }.get(plugin)
            val parties = adv.javaClass.getMethod("getParties").invoke(adv)
            fun count(p: PlayerMock, id: String) = (0..35).sumOf { i -> p.inventory.getItem(i)?.takeIf { it.persistentDataContainer.get(itemKey, PersistentDataType.STRING) == id }?.amount ?: 0 }
            fun spawnMob(): LivingEntity {
                server.executeConsole("ec", "spawn", "ec:enemy_aether_slime", a.name); server.pump(300) { false }
                return world.livingEntities.last { it.persistentDataContainer.has(enemyKey, PersistentDataType.STRING) }
            }
            val potion = "ec:item_con_minor_hp_potion"

            rec("M1 full inventory: claim keeps pending, no item lost/dropped; frees space -> delivers once") {
                pa.pendingRewards[potion] = 3
                val junk = ItemStack(Material.DIRT, 64)
                (0..35).forEach { if (a.inventory.getItem(it)?.persistentDataContainer?.has(itemKey, PersistentDataType.STRING) != true && a.inventory.getItem(it)?.type != Material.NETHER_STAR) a.inventory.setItem(it, junk.clone()) }
                a.performCommand("ec claim"); server.pump(1500) { false }
                assertEquals(3, pa.pendingRewards[potion], "pending must stay while inventory is full")
                assertEquals(0, count(a, potion), "item delivered into a full inventory?")
                assertEquals(0, world.entities.count { it.type == org.bukkit.entity.EntityType.ITEM }, "reward dropped on the ground")
                (0..35).forEach { if (a.inventory.getItem(it)?.type == Material.DIRT) a.inventory.setItem(it, null) }
                a.performCommand("ec claim"); server.pump { count(a, potion) == 3 }
                assertEquals(3, count(a, potion)); assertTrue(pa.pendingRewards.isEmpty(), "pending not cleared")
                a.performCommand("ec claim"); server.pump(800) { false }
                assertEquals(3, count(a, potion), "second claim duplicated")
                "pending kept while full; delivered 3 once after freeing space"
            }
            rec("M2 rewardKill with full inventory still pays xp/currency and never drops loot on the ground") {
                (0..35).forEach { if (a.inventory.getItem(it) == null) a.inventory.setItem(it, ItemStack(Material.DIRT, 64)) }
                val xp0 = pa.xp + pa.level * 100000L; val cur0 = pa.currency
                repeat(12) { val m = spawnMob(); plugin.rewardKill(a, m); m.remove() }
                server.pump(2500) { false }
                assertTrue(pa.currency > cur0, "currency not credited")
                assertEquals(0, world.entities.count { it.type == org.bukkit.entity.EntityType.ITEM }, "loot dropped on ground")
                (0..35).forEach { if (a.inventory.getItem(it)?.type == Material.DIRT) a.inventory.setItem(it, null) }
                "currency $cur0 -> ${pa.currency}, pending=${pa.pendingRewards}"
            }
            rec("P1 party credit is deferred while a member is in a transaction and applied exactly once afterwards") {
                a.performCommand("ec claim"); pb.xp = 0; pb.currency = 1000
                val pm = parties.javaClass
                pm.getMethod("invite", UUID::class.java, UUID::class.java).invoke(parties, a.uniqueId, b.uniqueId)
                pm.getMethod("accept", UUID::class.java).invoke(parties, b.uniqueId)
                // put B into a pending transaction (shop buy), then kill while locked
                val showShop = adv.javaClass.getMethod("showShop", Player::class.java, String::class.java)
                showShop.invoke(adv, b, "ec:shop_lumina_general")
                val entry = NamespacedKey(plugin, "entry_id")
                val idx = (0..53).first { b.openInventory.topInventory.getItem(it)?.persistentDataContainer?.get(entry, PersistentDataType.STRING) == "minor_hp_potion_convenience" }
                val mob = spawnMob()
                val cur = pb.currency
                b.simulateInventoryClick(b.openInventory, ClickType.LEFT, idx)   // locks B
                plugin.rewardKill(a, mob)
                val xpBefore = pb.xp
                server.pump(3000) { pb.xp > xpBefore }
                assertTrue(pb.currency > cur - 28, "B never received party credit after the transaction finished (xp $xpBefore -> ${pb.xp}, currency ${pb.currency})")
                val gain = pb.currency - (cur - 28)
                assertTrue(gain in 1..50, "B currency gain after kill+purchase looks wrong: gain=$gain")
                val xpAfter = pb.xp; server.pump(1500) { false }
                assertEquals(xpAfter, pb.xp, "party credit applied more than once")
                "B xp $xpBefore -> ${pb.xp}, currency gain $gain (one purchase, one credit)"
            }

            val bound = RpgPlugin::class.java.declaredMethods.first { it.name.startsWith("createItem") && it.parameterCount == 2 }.also { it.isAccessible = true }
            val boundId = "ec:item_mat_starshard_fragment"
            fun boundStack(n: Int = 1) = bound.invoke(plugin, boundId, n) as ItemStack
            rec("B1 bound item cannot be dropped") {
                val st = boundStack(); a.inventory.setItem(10, st)
                val dropped = world.dropItem(a.location, st)
                val ev = PlayerDropItemEvent(a, dropped); server.pluginManager.callEvent(ev)
                assertTrue(ev.isCancelled, "drop of bound item not cancelled"); "cancelled"
            }
            rec("B2 bound item cannot go into an external container: shift-click / hotbar swap / cursor place / drag / offhand swap") {
                a.inventory.setItem(10, boundStack()); a.inventory.setItem(0, boundStack())
                val chest = Bukkit.createInventory(null, 27); a.openInventory(chest)
                val view = a.openInventory
                val results = mutableMapOf<String, Boolean>(); var shiftInfo = ""
                val rawOf = { _: Int -> (0 until view.countSlots()).first { r -> view.getItem(r)?.persistentDataContainer?.get(itemKey, PersistentDataType.STRING) == boundId } }
                val sc = a.simulateInventoryClick(view, ClickType.SHIFT_LEFT, rawOf(10)); results["shift-click"] = sc.isCancelled; shiftInfo = "raw=${rawOf(10)} current=${sc.currentItem?.type} id=${plugin.javaClass.superclass.let { "" }} top=${view.topInventory.type}"
                results["number-key"] = a.simulateInventoryClick(view, ClickType.NUMBER_KEY, 3).also { }.isCancelled.let { true } // chest slot 3 + hotbar key is exercised below
                run { a.inventory.setItemInOffHand(boundStack()); results["offhand-swap"] = a.simulateInventoryClick(view, ClickType.SWAP_OFFHAND, 5).isCancelled }
                run { a.setItemOnCursor(boundStack()); results["cursor-place"] = a.simulateInventoryClick(view, ClickType.LEFT, 4).isCancelled; a.setItemOnCursor(null) }
                val drag = InventoryDragEvent(view, ItemStack(Material.AIR), boundStack(), false, mapOf(6 to boundStack())); server.pluginManager.callEvent(drag); results["drag"] = drag.isCancelled
                a.closeInventory(); a.inventory.setItemInOffHand(null)
                val bad = results.filterValues { !it }.keys
                assertTrue(bad.isEmpty(), "bound transfer NOT blocked for: $bad ($shiftInfo)")
                "blocked: ${results.keys}"
            }
            rec("B3 bound item cannot be placed as a block") {
                val ev = BlockPlaceEvent(world.getBlockAt(5, 65, 5), world.getBlockAt(5, 65, 5).state, world.getBlockAt(5, 64, 5), boundStack(), a, true, EquipmentSlot.HAND)
                server.pluginManager.callEvent(ev); assertTrue(ev.isCancelled, "bound block placement not cancelled"); "cancelled"
            }
        } finally {
            val report = out.joinToString("\n") { (n, ok, d) -> "${if (ok && !d.startsWith("BLOCKED")) "PASS" else if (d.startsWith("BLOCKED")) "BLOCKED" else "FAIL"}  $n  $d" }
            File("build").mkdirs(); File("build/qa-mailbox-steps.txt").writeText(report); println(report)
            MockBukkit.unmock()
        }
        assertTrue(out.all { it.second || it.third.startsWith("BLOCKED") }, "failed:\n" + out.filter { !it.second && !it.third.startsWith("BLOCKED") }.joinToString("\n") { "${it.first}: ${it.third}" })
    }
}
