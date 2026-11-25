package net.nakaymi.etherealchronicle.qa

import net.nakaymi.etherealchronicle.paper.RpgMenu
import net.nakaymi.etherealchronicle.paper.RpgPlugin
import org.bukkit.NamespacedKey
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.ItemStack
import org.bukkit.Material
import org.bukkit.persistence.PersistentDataType
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import java.io.File
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** BUG-7 re-verification: missing inputs, GUI click spam, reload mid-flight, result item removed (refund exactly once), quit then rejoin. Report: build/qa-enhance-flight.txt */
class EnhanceFlightTest {
    private val out = mutableListOf<String>()
    private fun rec(n: String, body: () -> String?) { try { out += "PASS  $n  ${body() ?: ""}" } catch (t: Throwable) { out += (if (t is org.mockbukkit.mockbukkit.exception.UnimplementedOperationException) "BLOCKED(mock)" else "FAIL") + "  $n  ${(t::class.simpleName + ": " + t.message + " @ " + t.stackTrace.take(3).joinToString(" <- ")).take(700)}" } }
    private fun ServerMock.pump(timeoutMs: Long = 4000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        // waitAsyncTasksFinished() spins forever with MockBukkit's repeating plugin tasks during /ec reload.
        while (System.currentTimeMillis() < end) { scheduler.performOneTick(); if (cond()) return true; Thread.sleep(20) }
        return cond()
    }

    @Test
    fun enhancementFlights() {
        val server = MockBukkit.mock()
        try {
            val world = net.nakaymi.etherealchronicle.qa.QaWorldMock.add(server, net.nakaymi.etherealchronicle.core.WorldIds.DEMO)
            File(world.worldFolder, ".etherealchronicle-built").writeText("v4\n")
            val plugin = MockBukkit.load(RpgPlugin::class.java)
            val mat = "ec:item_mat_artisan_whetstone"; val weapon = "ec:item_wpn_rusty_broadsword"
            val lvKey = NamespacedKey(plugin, "enhancement_level")
            fun newPlayer(name: String): Pair<QaPlayerMock, net.nakaymi.etherealchronicle.core.PlayerProfile> {
                val id = UUID.nameUUIDFromBytes("ef-$name".toByteArray()); val pl = QaPlayerMock(server, name, id); server.addPlayer(pl)
                val loaded = server.pump { id in plugin.profiles }
                check(loaded) { "プロフィール読込待機がタイムアウト: online=${pl.isOnline}, enabled=${plugin.isEnabled}, onlinePlayers=${server.onlinePlayers.map { it.name }}, profiles=${plugin.profiles.keys}, messages=${pl.nextMessage()}" }
                val pr = plugin.profiles.getValue(id)
                pr.classId = "ec:class_warrior"; pr.level = 20; pr.currency = 10_000
                pl.inventory.setItemInMainHand(plugin.createItem(weapon)); return pl to pr
            }
            fun mats(pl: QaPlayerMock) = pl.inventory.storageContents.filterNotNull().sumOf { if (plugin.identify(it) == mat) it.amount else 0 }
            fun lvl(pl: QaPlayerMock) = pl.inventory.itemInMainHand.itemMeta?.persistentDataContainer?.get(lvKey, PersistentDataType.INTEGER) ?: 0
            fun anyEnhanced(pl: QaPlayerMock) = pl.inventory.storageContents.filterNotNull().any { (it.itemMeta?.persistentDataContainer?.get(lvKey, PersistentDataType.INTEGER) ?: 0) > 0 }
            fun menu(pl: QaPlayerMock) = pl.openInventory.topInventory?.holder as RpgMenu

            rec("F1 no whetstone: rejected, currency untouched, weapon not upgraded") {
                val (pl, pr) = newPlayer("NoMat")
                plugin.openEnhancement(pl); runCatching { plugin.enhance(pl, menu(pl)) }; server.pump(1200) { false }
                assertEquals(10_000L, pr.currency); assertEquals(0, lvl(pl)); "nothing happened"
            }
            rec("F2 not enough gold: rejected, whetstone kept, weapon not upgraded") {
                val (pl, pr) = newPlayer("NoGold"); pl.inventory.addItem(plugin.createItem(mat, 3)); pr.currency = 50
                plugin.openEnhancement(pl); runCatching { plugin.enhance(pl, menu(pl)) }; server.pump(1200) { false }
                assertEquals(50L, pr.currency); assertEquals(3, mats(pl)); assertEquals(0, lvl(pl)); "nothing consumed"
            }
            rec("F3 GUI click spam on the anvil slot (5 LEFT/double/shift clicks) upgrades once per completed transaction") {
                val (pl, pr) = newPlayer("Spam"); pl.inventory.addItem(plugin.createItem(mat, 5))
                plugin.openEnhancement(pl); val v = pl.openInventory
                listOf(ClickType.LEFT, ClickType.DOUBLE_CLICK, ClickType.LEFT, ClickType.SHIFT_LEFT, ClickType.LEFT).forEach { runCatching { pl.simulateInventoryClick(v, it, 13) } }
                server.pump(3000) { false }
                assertEquals(1, lvl(pl), "level after click spam"); assertEquals(4, mats(pl), "whetstones after spam"); assertEquals(9_900L, pr.currency); "level=1, 1 whetstone, 100 G"
            }
            rec("F4 result item removed before completion (dropped into a chest): costs refunded exactly once, no enhanced item left") {
                val (pl, pr) = newPlayer("Refund"); pl.inventory.addItem(plugin.createItem(mat, 2)); val c0 = pr.currency
                plugin.openEnhancement(pl); plugin.enhance(pl, menu(pl))
                pl.inventory.setItemInMainHand(null)                       // the upgraded weapon leaves the inventory before the async completion
                server.pump(3000) { false }
                assertEquals(c0, pr.currency, "currency not refunded"); assertEquals(2, mats(pl) + (pr.pendingRewards[mat] ?: 0), "whetstone not refunded (inv=${mats(pl)} pending=${pr.pendingRewards})")
                val c1 = pr.currency; val m1 = mats(pl) + (pr.pendingRewards[mat] ?: 0); server.pump(1500) { false }
                assertEquals(c1, pr.currency, "refund applied twice (currency)"); assertEquals(m1, mats(pl) + (pr.pendingRewards[mat] ?: 0), "refund applied twice (whetstone)")
                assertTrue(!anyEnhanced(pl), "an enhanced item still exists"); "refunded once"
            }
            rec("F5 /ec reload right after the click: upgrade and debit stay consistent") {
                val (pl, pr) = newPlayer("Reload"); pl.inventory.addItem(plugin.createItem(mat, 2)); val c0 = pr.currency
                plugin.openEnhancement(pl); plugin.enhance(pl, menu(pl)); server.executeConsole("ec", "reload"); server.pump(3000) { false }
                val debited = pr.currency != c0
                assertTrue(debited == (lvl(pl) == 1), "inconsistent: currency $c0->${pr.currency}, level ${lvl(pl)}, whetstones ${mats(pl)}"); "currency ${pr.currency}, level ${lvl(pl)}"
            }
            rec("F6 quit right after the click then rejoin: exactly one debit, upgraded item kept (token record cleaned)") {
                val (pl, pr) = newPlayer("Quitter"); pl.inventory.addItem(plugin.createItem(mat, 2)); val c0 = pr.currency
                plugin.openEnhancement(pl); plugin.enhance(pl, menu(pl)); pl.disconnect(); server.pump(2000) { false }
                val saved = File(plugin.dataFolder, "playerdata/${pl.uniqueId}.yml").readText()
                val gold = Regex("currency: (\\d+)").find(saved)!!.groupValues[1].toLong()
                val back = QaPlayerMock(server, "Quitter", pl.uniqueId); back.inventory.setItemInMainHand(pl.inventory.itemInMainHand)   // the mock does not persist inventories: carry the item over
                server.addPlayer(back)
                check(server.pump(3000) { pl.uniqueId in plugin.profiles }) { "再接続プロフィール読込がタイムアウト" }
                server.pump(1500) { false }
                val pr2 = plugin.profiles.getValue(pl.uniqueId)
                assertTrue(gold == c0 - 100 || (gold == c0 && lvl(pl) == 0), "saved gold $gold with level ${lvl(pl)} (start $c0)")
                assertEquals(gold, pr2.currency, "currency changed again on rejoin (double debit/refund)"); "gold=$gold level=${lvl(pl)} after rejoin currency=${pr2.currency}"
            }
            rec("F7 persisted item (serialize round trip) keeps +level and a second +1 continues from it (no reset)") {
                val (pl, _) = newPlayer("Persist"); pl.inventory.addItem(plugin.createItem(mat, 3))
                plugin.openEnhancement(pl); plugin.enhance(pl, menu(pl)); server.pump(2500) { lvl(pl) == 1 }; server.pump(1000) { false }   // let the pending persist finish and release the transaction lock
                val revived = ItemStack.deserializeBytes(pl.inventory.itemInMainHand.serializeAsBytes()); pl.inventory.setItemInMainHand(revived)
                plugin.openEnhancement(pl); plugin.enhance(pl, menu(pl)); server.pump(2500) { lvl(pl) == 2 }
                assertEquals(2, lvl(pl)); "+1 survived serialization and continued to +2"
            }
        } finally {
            File("build").mkdirs(); File("build/qa-enhance-flight.txt").writeText(out.joinToString(System.lineSeparator())); println(out.joinToString(System.lineSeparator())); MockBukkit.unmock()
        }
        assertTrue(out.none { it.startsWith("FAIL") }, out.filter { it.startsWith("FAIL") }.joinToString(System.lineSeparator()))
    }
}
