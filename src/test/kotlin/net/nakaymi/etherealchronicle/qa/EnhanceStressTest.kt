package net.nakaymi.etherealchronicle.qa

import net.nakaymi.etherealchronicle.paper.RpgMenu
import net.nakaymi.etherealchronicle.paper.RpgPlugin
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import java.io.File
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** /ec enhance: +1..+5, cost accounting, cap, rapid double-activation, item swap and quit mid-transaction. Report: build/qa-enhance-steps.txt */
class EnhanceStressTest {
    private val out = mutableListOf<String>()
    private fun rec(n: String, body: () -> String?) {
        try { out += "PASS  $n  ${body() ?: ""}" } catch (t: Throwable) { out += "FAIL  $n  ${(t::class.simpleName + ": " + t.message).take(380)}" }
    }
    private fun ServerMock.pump(timeoutMs: Long = 4000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { scheduler.performOneTick(); if (cond()) return true; Thread.sleep(20) }
        return cond()
    }

    @Test
    fun enhancementScenarios() {
        val server = MockBukkit.mock()
        try {
            val world=net.nakaymi.etherealchronicle.qa.QaWorldMock.add(server, net.nakaymi.etherealchronicle.core.WorldIds.DEMO)
            val plugin = MockBukkit.load(RpgPlugin::class.java)
            val uuid = UUID.nameUUIDFromBytes("enh".toByteArray())
            val player = QaPlayerMock(server, "Smithy", uuid); server.addPlayer(player)
            server.pump { uuid in plugin.profiles }
            val p = plugin.profiles.getValue(uuid)
            p.classId = "ec:class_warrior"; p.level = 20; p.currency = 100_000
            val lvKey = NamespacedKey(plugin, "enhancement_level")
            val mat = "ec:item_mat_artisan_whetstone"
            fun level() = player.inventory.itemInMainHand.itemMeta.persistentDataContainer.get(lvKey, PersistentDataType.INTEGER) ?: 0
            fun mats() = player.inventory.storageContents.filterNotNull().sumOf { if (plugin.identify(it) == mat) it.amount else 0 }
            fun open() { plugin.openEnhancement(player) }
            fun menu() = player.openInventory.topInventory?.holder as RpgMenu
            player.inventory.setItemInMainHand(plugin.createItem("ec:item_wpn_rusty_broadsword"))
            player.inventory.addItem(plugin.createItem(mat, 10))

            rec("E1 +1..+5: each step consumes exactly 1 material and 100*n currency, stat never decreases") {
                var lastAtk = plugin.effectiveStats(player, p).attack; val log = mutableListOf<String>()
                for (n in 1..5) {
                    val c0 = p.currency; val m0 = mats()
                    open(); plugin.enhance(player, menu())
                    assertTrue(server.pump { level() == n && !plugin.adventure.locked(uuid) }, "level +$n was not persisted/unlocked (is ${level()})")
                    assertEquals(c0 - 100L * n, p.currency, "currency at +$n"); assertEquals(m0 - 1, mats(), "material at +$n")
                    val atk = plugin.effectiveStats(player, p).attack; assertTrue(atk >= lastAtk, "attack dropped at +$n"); lastAtk = atk
                    log += "+$n atk=${"%.2f".format(atk)}"
                }
                log.joinToString(", ")
            }
            rec("E2 cap: +6 is refused and consumes nothing") {
                val c0 = p.currency; val m0 = mats()
                runCatching { open(); plugin.enhance(player, menu()) }; server.pump(800) { false }
                assertEquals(5, level()); assertEquals(c0, p.currency); assertEquals(m0, mats()); "stays +5, nothing consumed"
            }
            rec("E3 item keeps its enhancement across a serialize/deserialize round trip (restart persistence of the PDC)") {
                val bytes = player.inventory.itemInMainHand.serializeAsBytes(); val back = ItemStack.deserializeBytes(bytes)
                assertEquals(5, back.itemMeta.persistentDataContainer.get(lvKey, PersistentDataType.INTEGER)); "+5 kept; identify=${plugin.identify(back)}"
            }
            rec("E4 two activations of the same menu back-to-back enhance only once") {
                player.inventory.setItemInMainHand(plugin.createItem("ec:item_wpn_rusty_broadsword")); player.inventory.addItem(plugin.createItem(mat, 5))
                val c0 = p.currency; val m0 = mats()
                open(); val m = menu(); plugin.enhance(player, m); runCatching { plugin.enhance(player, m) }; runCatching { plugin.enhance(player, m) }
                server.pump(2500) { false }
                assertEquals(1, level(), "level after 3 activations"); assertEquals(m0 - 1, mats(), "materials"); assertEquals(c0 - 100L, p.currency, "currency")
                "level=1, 1 material, 100 G"
            }
            rec("E5 swapping the held item while the enhancement is in flight: nothing is lost silently") {
                player.inventory.setItemInMainHand(plugin.createItem("ec:item_wpn_rusty_broadsword")); player.inventory.addItem(plugin.createItem(mat, 5))
                val c0 = p.currency; val m0 = mats()
                open(); plugin.enhance(player, menu())
                val other = plugin.createItem("ec:item_wpn_rusty_broadsword"); player.inventory.setItemInMainHand(other)   // swap before the async persist completes
                server.pump(2500) { false }
                assertEquals(c0,p.currency,"currency refunded when the result item was replaced")
                assertEquals(m0,mats(),"whetstone refunded when the result item was replaced")
                assertEquals(0,level(),"replacement item must not receive an upgrade for free")
                "costs refunded"
            }
            rec("E6 quit right after enhance (before the persist completes): consumption and result stay consistent") {
                player.inventory.setItemInMainHand(plugin.createItem("ec:item_wpn_rusty_broadsword")); player.inventory.addItem(plugin.createItem(mat, 5))
                p.currency = 5000; val m0 = mats()
                open(); plugin.enhance(player, menu())
                player.disconnect(); server.pump(2500) { false }
                val saved = File(plugin.dataFolder, "playerdata/$uuid.yml").readText()
                val gold = Regex("currency: (\\d+)").find(saved)!!.groupValues[1].toLong()
                val applied = level() == 1
                assertTrue(gold == 4900L && applied && mats() == m0-1, "quit must save both the 100 G/one whetstone debit and +1 item; got gold=$gold applied=$applied material=${mats()}/$m0")
                "gold=$gold applied=$applied material=${mats()}"
            }
            rec("E7 plugin disable during enhancement flushes the upgraded item and matching debit") {
                val shutdownId=UUID.nameUUIDFromBytes("enh-shutdown".toByteArray())
                val shutdownPlayer=QaPlayerMock(server,"ShutdownSmith",shutdownId); server.addPlayer(shutdownPlayer)
                server.pump { shutdownId in plugin.profiles }
                val shutdownProfile=plugin.profiles.getValue(shutdownId)
                shutdownProfile.classId="ec:class_warrior"; shutdownProfile.level=20; shutdownProfile.currency=5000
                shutdownPlayer.inventory.setItemInMainHand(plugin.createItem("ec:item_wpn_rusty_broadsword"))
                shutdownPlayer.inventory.addItem(plugin.createItem(mat,1))
                openEnhancementForShutdown(plugin,shutdownPlayer)
                val shutdownMenu=shutdownPlayer.openInventory.topInventory?.holder as RpgMenu
                plugin.enhance(shutdownPlayer,shutdownMenu)
                server.pluginManager.disablePlugin(plugin)
                val saved=File(plugin.dataFolder,"playerdata/$shutdownId.yml").readText()
                val gold=Regex("currency: (\\d+)").find(saved)!!.groupValues[1].toLong()
                val upgraded=shutdownPlayer.inventory.itemInMainHand.itemMeta.persistentDataContainer.get(lvKey,PersistentDataType.INTEGER) == 1
                assertTrue(gold==4900L && upgraded,"disable must flush matching debit and +1 result; gold=$gold upgraded=$upgraded")
                "gold=$gold upgraded=$upgraded"
            }
        } finally {
            File("build").mkdirs(); File("build/qa-enhance-steps.txt").writeText(out.joinToString(System.lineSeparator())); println(out.joinToString(System.lineSeparator()))
            MockBukkit.unmock()
        }
        assertTrue(out.none { it.startsWith("FAIL") },out.filter { it.startsWith("FAIL") }.joinToString(System.lineSeparator()))
    }
}

private fun openEnhancementForShutdown(plugin:RpgPlugin,player:QaPlayerMock) {
    plugin.openEnhancement(player)
}
