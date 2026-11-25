package net.nakaymi.etherealchronicle.qa

import net.nakaymi.etherealchronicle.core.WorldIds
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.nakaymi.etherealchronicle.paper.RpgPlugin
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerRespawnEvent
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Adventure paths under MockBukkit: NPC click -> dialogue GUI, shop click types, rapid clicks, drag, respawn, quit mid-transaction. */
class AdventurePathsTest {
    private val plain = PlainTextComponentSerializer.plainText()
    private val out = mutableListOf<Triple<String, Boolean, String>>()

    private fun rec(n: String, body: () -> String?) {
        try { out += Triple(n, true, body() ?: "") }
        catch (t0: Throwable) {
            val t = if (t0 is java.lang.reflect.InvocationTargetException && t0.cause != null) t0.cause!! else t0
            val where = t.stackTrace.filter { it.className.contains("nakaymi") && !it.className.contains(".qa.") }.take(3).joinToString(" <- ")
            out += Triple(n, false, (t::class.simpleName + ": " + t.message + " @ " + where).take(450))
        }
    }

    private fun ServerMock.pump(timeoutMs: Long = 5000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            scheduler.performOneTick()
            if (cond()) return true
            Thread.sleep(20)
        }
        return cond()
    }

    private fun equipFillers(p: PlayerMock) {
        p.inventory.setItem(EquipmentSlot.HEAD, ItemStack(Material.LEATHER_HELMET)); p.inventory.setItem(EquipmentSlot.CHEST, ItemStack(Material.LEATHER_CHESTPLATE))
        p.inventory.setItem(EquipmentSlot.LEGS, ItemStack(Material.LEATHER_LEGGINGS)); p.inventory.setItem(EquipmentSlot.FEET, ItemStack(Material.LEATHER_BOOTS))
    }

    @Test
    fun npcShopAndTransactionPaths() {
        val server = MockBukkit.mock()
        try {
            val plugin = MockBukkit.load(RpgPlugin::class.java)
            server.pump { plugin.isReadyForGameplay }
            val world = QaWorldMock.add(server, WorldIds.DEMO)
            val uuid = UUID.nameUUIDFromBytes("qa-shop".toByteArray())
            val player = PlayerMock(server, "QaShopper", uuid)
            equipFillers(player); server.addPlayer(player)
            val itemKey = NamespacedKey(plugin, "item_id"); val entryKey = NamespacedKey(plugin, "entry_id"); val npcKey = NamespacedKey(plugin, "npc_id")
            fun titleOf() = plain.serialize(player.openInventory.title())
            server.pump { player.openInventory.topInventory?.holder is net.nakaymi.etherealchronicle.paper.ClassMenu }
            val slot = (0..8).first { player.openInventory.topInventory.getItem(it)?.persistentDataContainer?.get(itemKey, PersistentDataType.STRING)?.contains("mage") == true }
            player.simulateInventoryClick(player.openInventory, slot)
            server.pump { player.inventory.getItem(8)?.type == Material.NETHER_STAR }
            val adv = RpgPlugin::class.java.getDeclaredField("adventure").also { it.isAccessible = true }.get(plugin)
            val showShop = adv.javaClass.getMethod("showShop", Player::class.java, String::class.java)
            val profile = plugin.profiles.getValue(uuid)
            profile.currency = 1000; profile.level = 20
            fun potions() = (0..35).sumOf { i -> player.inventory.getItem(i)?.takeIf { it.persistentDataContainer.get(itemKey, PersistentDataType.STRING) == "ec:item_con_minor_hp_potion" }?.amount ?: 0 }
            rec("admin flag command denies non-admin and persists set/clear") {
                val flag = "ec:flag_sanctum_unlocked"
                player.isOp = false
                player.performCommand("ec flag ${player.name} $flag set")
                assertFalse(flag in profile.flags, "non-admin changed flag")
                player.isOp = true
                player.performCommand("ec flag ${player.name} $flag set")
                assertTrue(flag in profile.flags, "set did not add flag")
                assertTrue(server.pump { player.nextMessage()?.let { it.contains("設定") || it.contains("was set") } == true }, "set confirmation missing")
                plugin.persist(profile).join()
                val saved = File(plugin.dataFolder, "playerdata/${uuid}.yml").readText()
                assertTrue(saved.contains(flag), "flag missing from saved profile")
                player.performCommand("ec flag ${player.name} $flag clear")
                assertFalse(flag in profile.flags, "clear did not remove flag")
                assertTrue(server.pump { player.nextMessage()?.let { it.contains("解除") || it.contains("cleared") } == true }, "clear confirmation missing")
                player.isOp = false
                "non-admin denied; flag set persisted and cleared"
            }
            rec("claim requested during a locked delivery waits and runs once after unlock") {
                val claims=adv.javaClass.getMethod("claimPending",Player::class.java)
                val lockType=Class.forName("net.nakaymi.etherealchronicle.paper.AdventureController\$LockToken")
                val constructor=lockType.getDeclaredConstructor(UUID::class.java).also { it.isAccessible=true }
                val release=adv.javaClass.getDeclaredMethod("releaseLock",lockType,Boolean::class.javaPrimitiveType).also { it.isAccessible=true }
                profile.pendingRewards["ec:item_con_minor_hp_potion"]=1
                val lock=adv.javaClass.getDeclaredField("locks").also { it.isAccessible=true }.get(adv) as MutableMap<UUID,Any>
                val token=constructor.newInstance(uuid)
                lock[uuid]=token
                claims.invoke(adv,player)
                assertEquals(0,potions())
                val newerToken=constructor.newInstance(uuid)
                lock[uuid]=newerToken
                release.invoke(adv,token,true)
                assertTrue(adv.javaClass.getMethod("locked",UUID::class.java).invoke(adv,uuid) as Boolean,"stale token released a newer transaction")
                release.invoke(adv,newerToken,true)
                assertTrue(server.pump { potions()==1 },"queued claim did not deliver")
                assertTrue(server.pump { profile.pendingRewards.isEmpty() },"claim persistence did not finish")
                claims.invoke(adv,player); server.pump(500) { false }
                assertEquals(1,potions(),"queued claim duplicated")
                "pending claim queued during lock, delivered exactly once after unlock"
            }
            rec("preferred sell source slot does not consume an earlier duplicate stack") {
                val id="ec:item_mat_aether_gel"
                player.inventory.clear()
                player.inventory.setItem(0,plugin.createItem(id,3).apply { itemMeta=itemMeta.apply {
                    persistentDataContainer.set(NamespacedKey(plugin,"enhancement_level"),PersistentDataType.INTEGER,4)
                } })
                player.inventory.setItem(1,plugin.createItem(id,2))
                val inventory=net.nakaymi.etherealchronicle.paper.PaperInventoryAccess(player,plugin::identify,plugin::createItem,persistRemoval=false)
                assertTrue(inventory.exchangeFromSlot(1,mapOf(id to 1),emptyMap()))
                assertEquals(3,player.inventory.getItem(0)?.amount,"the earlier, enhanced copy was consumed")
                assertEquals(1,player.inventory.getItem(1)?.amount,"the clicked source stack was not consumed")
                "consumed from clicked slot 1 only"
            }
            fun entrySlot(id: String) = (0..53).first { player.openInventory.topInventory.getItem(it)?.persistentDataContainer?.get(entryKey, PersistentDataType.STRING) == id }
            val pipLocation = org.bukkit.Location(world, -6.0, 65.0, 16.0)
            fun openShop() {
                player.teleport(pipLocation)
                showShop.invoke(adv, player, "ec:shop_lumina_general")
            }

            rec("A NPC click opens dialogue GUI") {
                // reloadNpcs() itself hits an unimplemented MockBukkit call (Entity flags) -> place the NPC entity by hand with the same PDC tag.
                val npc = world.spawn(org.bukkit.Location(world, 0.0, 65.0, 0.0), org.bukkit.entity.Villager::class.java)
                npc.persistentDataContainer.set(npcKey, PersistentDataType.STRING, "ec:npc_pip")
                val npcs = listOf(npc)
                val ev = PlayerInteractEntityEvent(player, npc, EquipmentSlot.HAND)
                server.pluginManager.callEvent(ev)
                assertTrue(ev.isCancelled, "NPC interact not cancelled")
                assertTrue(server.pump { (player.openInventory.topInventory?.holder as? net.nakaymi.etherealchronicle.paper.RpgMenu)?.kind == "dialogue" }, "dialogue GUI not open; title=${titleOf()}")
                "npcs=${npcs.size} dialogue choices=" + (9..30).count { player.openInventory.topInventory.getItem(it) != null }
            }
            rec("B shop: left-click buys exactly one, currency debited once") {
                openShop()
                val before = profile.currency; val had = potions()
                player.simulateInventoryClick(player.openInventory, ClickType.LEFT, entrySlot("minor_hp_potion_convenience"))
                assertTrue(server.pump { potions() == had + 1 }, "no potion delivered")
                assertEquals(before - 28, profile.currency, "currency debit")
                "currency $before -> ${profile.currency}, potions $had -> ${potions()}"
            }
            rec("C rapid clicks (left, double, left) while transaction pending buy only once") {
                if ((player.openInventory.topInventory?.holder as? net.nakaymi.etherealchronicle.paper.RpgMenu)?.kind != "shop") openShop()
                val idx = entrySlot("minor_hp_potion_convenience")
                val before = profile.currency; val had = potions()
                player.simulateInventoryClick(player.openInventory, ClickType.LEFT, idx)
                player.simulateInventoryClick(player.openInventory, ClickType.DOUBLE_CLICK, idx)
                player.simulateInventoryClick(player.openInventory, ClickType.LEFT, idx)
                server.pump(3000) { false }
                assertEquals(1, potions() - had, "bought ${potions() - had} potions from 3 rapid clicks")
                assertEquals(before - 28, profile.currency, "currency after rapid clicks")
                "3 rapid clicks -> ${potions() - had} potion(s), currency $before -> ${profile.currency}"
            }
            rec("D number-key / middle / drop / swap-offhand / drag in shop never buy or move items") {
                if ((player.openInventory.topInventory?.holder as? net.nakaymi.etherealchronicle.paper.RpgMenu)?.kind != "shop") openShop()
                val top = player.openInventory.topInventory; val idx = entrySlot("minor_hp_potion_convenience")
                val before = profile.currency; val had = potions()
                listOf(ClickType.NUMBER_KEY, ClickType.MIDDLE, ClickType.DROP, ClickType.CONTROL_DROP, ClickType.SWAP_OFFHAND).forEach { ct -> runCatching { player.simulateInventoryClick(player.openInventory, ct, idx) } }
                val drag = InventoryDragEvent(player.openInventory, ItemStack(Material.AIR), top.getItem(idx)!!, false, mapOf(idx to top.getItem(idx)!!))
                server.pluginManager.callEvent(drag)
                server.pump(1500) { false }
                assertTrue(drag.isCancelled, "drag in shop not cancelled")
                assertEquals(had, potions(), "potions changed by non-buy clicks"); assertEquals(before, profile.currency, "currency changed by non-buy clicks")
                "inventory/currency unchanged"
            }
            rec("E insufficient funds rejected without side effects") {
                if ((player.openInventory.topInventory?.holder as? net.nakaymi.etherealchronicle.paper.RpgMenu)?.kind != "shop") openShop()
                profile.currency = 5
                val had = potions()
                val shopEntry = entrySlot("minor_hp_potion_convenience")
                while(player.nextMessage()!=null) { }
                val buySlot = adv.javaClass.getDeclaredMethod("buyShopSlot",Player::class.java,String::class.java,String::class.java)
                    .also { it.isAccessible=true }
                val entryId=player.openInventory.topInventory.getItem(shopEntry)!!.itemMeta.persistentDataContainer.get(entryKey,PersistentDataType.STRING)
                plugin.handleInvUiAction(player) { buySlot.invoke(adv,player,"ec:shop_lumina_general",entryId) }
                server.pump(1000) { false }
                assertEquals(had, potions()); assertEquals(5, profile.currency)
                val message=player.nextMessage()?.toString().orEmpty()
                assertTrue(message.contains("所持金が足りません") || message.contains("You do not have enough currency."),"localized low-currency feedback missing: $message")
                assertFalse(message.contains("ec.error.currency_low"),"internal key leaked to the player")
                message
            }
            rec("F respawn restores skill slot and keeps profile") {
                val classBefore = profile.classId; val lvl = profile.level
                player.inventory.setItem(8, null)
                server.pluginManager.callEvent(PlayerRespawnEvent(player, player.location, false, false))
                assertTrue(server.pump { player.inventory.getItem(8)?.type == Material.NETHER_STAR }, "skill slot not restored on respawn")
                assertEquals(classBefore, profile.classId); assertEquals(lvl, profile.level)
                null
            }
            rec("H open shop rejects buy after moving 24 blocks away before currency check and closes menu") {
                openShop()
                assertEquals("shop", (player.openInventory.topInventory?.holder as? net.nakaymi.etherealchronicle.paper.RpgMenu)?.kind, "shop menu did not open near Pip")
                profile.currency = 0
                val had = potions()
                val idx = entrySlot("minor_hp_potion_convenience")
                val view = player.openInventory
                while (player.nextMessage() != null) { }
                player.setLocation(org.bukkit.Location(world, -6.0, 65.0, 40.0))
                player.simulateInventoryClick(view, ClickType.LEFT, idx)
                server.pump(800) { false }
                assertEquals(had, potions(), "out-of-range shop buy mutated potions")
                assertEquals(0, profile.currency, "out-of-range shop buy mutated currency")
                assertFalse((player.openInventory.topInventory?.holder as? net.nakaymi.etherealchronicle.paper.RpgMenu)?.kind == "shop", "out-of-range shop menu stayed open after buy")
                val msg = player.nextMessage()?.toString().orEmpty()
                assertTrue(msg.contains("Move closer to the shop.") || msg.contains("お店の近くに移動"), "missing shop_too_far feedback on buy: $msg")
                assertFalse(msg.contains("currency") || msg.contains("所持金"), "buy reached currency check instead of distance guard: $msg")
                "buy rejected at 24 blocks before currency check; menu closed"
            }
            rec("I open shop rejects right-click sell after moving 24 blocks away and closes menu") {
                profile.currency = 100
                openShop()
                val gelId = "ec:item_mat_aether_gel"
                player.inventory.setItem(10, plugin.createItem(gelId, 2))
                val view = player.openInventory
                val rawGelSlot = (view.topInventory.size until view.countSlots()).first { r ->
                    view.getItem(r)?.persistentDataContainer?.get(itemKey, PersistentDataType.STRING) == gelId
                }
                while (player.nextMessage() != null) { }
                player.setLocation(org.bukkit.Location(world, -6.0, 65.0, 40.0))
                player.simulateInventoryClick(view, ClickType.RIGHT, rawGelSlot)
                server.pump(800) { false }
                assertEquals(2, player.inventory.getItem(10)?.amount, "out-of-range sell consumed item")
                assertEquals(100, profile.currency, "out-of-range sell credited currency")
                assertFalse((player.openInventory.topInventory?.holder as? net.nakaymi.etherealchronicle.paper.RpgMenu)?.kind == "shop", "out-of-range shop menu stayed open after sell")
                val msg = player.nextMessage()?.toString().orEmpty()
                assertTrue(msg.contains("Move closer to the shop.") || msg.contains("お店の近くに移動"), "missing shop_too_far feedback on sell: $msg")
                "sell rejected at 24 blocks; menu closed"
            }
            rec("J showShop rejects cross-world access without opening menu") {
                val otherWorld = server.addSimpleWorld("qa_other_world")
                player.closeInventory()
                player.teleport(org.bukkit.Location(otherWorld, -6.0, 65.0, 16.0))
                while (player.nextMessage() != null) { }
                showShop.invoke(adv, player, "ec:shop_lumina_general")
                assertFalse((player.openInventory.topInventory?.holder as? net.nakaymi.etherealchronicle.paper.RpgMenu)?.kind == "shop", "cross-world showShop opened menu")
                val msg = player.nextMessage()?.toString().orEmpty()
                assertTrue(msg.contains("Move closer to the shop.") || msg.contains("お店の近くに移動"), "missing shop_too_far feedback cross-world: $msg")
                player.teleport(pipLocation)
                "cross-world showShop rejected cleanly"
            }
            rec("G quit during pending transaction: currency consistent with file, no item loss") {
                profile.currency = 1000; plugin.persist(profile).get()
                if ((player.openInventory.topInventory?.holder as? net.nakaymi.etherealchronicle.paper.RpgMenu)?.kind != "shop") openShop()
                val idx = entrySlot("minor_hp_potion_convenience"); val had = potions()
                player.simulateInventoryClick(player.openInventory, ClickType.LEFT, idx)
                player.disconnect()
                server.pump(2500) { false }
                val saved = File(plugin.dataFolder, "playerdata/$uuid.yml").readText()
                val gold = Regex("currency: (\\d+)").find(saved)!!.groupValues[1].toLong()
                val delivered = potions() - had
                assertTrue(gold == 1000L || gold == 972L, "persisted currency $gold is neither 1000 (before) nor 972 (after)")
                val pendingPersisted = saved.contains("pending_rewards") && !saved.contains("pending_rewards: {}") && saved.contains("ec:item_con_minor_hp_potion")
                if (gold == 972L && delivered == 0) assertTrue(pendingPersisted, "LOSS: currency debited and persisted (972) but the potion is neither delivered nor recorded in pending_rewards")
                if (gold == 972L && delivered == 0) {
                    // rejoin and claim: must deliver exactly once
                    val again = PlayerMock(server, "QaShopper", uuid); equipFillers(again); server.addPlayer(again)
                    assertTrue(server.pump { uuid in plugin.profiles }, "profile not reloaded on rejoin")
                    fun potions2() = (0..35).sumOf { i -> again.inventory.getItem(i)?.takeIf { it.persistentDataContainer.get(itemKey, PersistentDataType.STRING) == "ec:item_con_minor_hp_potion" }?.amount ?: 0 }
                    again.performCommand("ec claim"); server.pump(2500) { potions2() >= 1 }
                    assertEquals(1, potions2(), "claim must deliver exactly the pending potion")
                    again.performCommand("ec claim"); server.pump(1500) { false }
                    assertEquals(1, potions2(), "second claim duplicated the reward")
                    return@rec "persisted currency=$gold, pending_rewards recorded, claim after rejoin delivered 1 (second claim 0)"
                }
                "persisted currency=$gold delivered=$delivered"
            }
        } finally {
            val report = out.joinToString("\n") { (n, ok, d) -> "${if (ok) "PASS" else "FAIL"}  $n  $d" }
            File("build").mkdirs(); File("build/qa-adventure-steps.txt").writeText(report); println(report)
            MockBukkit.unmock()
        }
        // Step G documents known BUG-5 (quit mid-transaction loses the purchase); it only fails the build with -Dqa.strict=true.
        val strict = System.getProperty("qa.strict") != null
        val blocking = out.filter { !it.second }
        assertTrue(blocking.isEmpty(), "failed:\n" + blocking.joinToString("\n") { "${it.first}: ${it.third}" })
    }
}
