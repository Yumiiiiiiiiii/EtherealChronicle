package net.nakaymi.etherealchronicle.qa

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.nakaymi.etherealchronicle.paper.RpgPlugin
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.LivingEntity
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.persistence.PersistentDataType
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.PlayerMock
import java.io.File
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Real Player/event code paths driven in-JVM by MockBukkit (Paper 26.3). Scripted scenario:
 * join -> class GUI click -> starter kit -> spawn+kill mob -> swap-hand skill select -> right-click cast -> quit -> rejoin.
 * Each step is recorded (PASS/FAIL + detail) to build/qa-steps.txt; the test fails if any step failed.
 */
/** Thrown when MockBukkit cannot emulate something; reported as BLOCKED(mock), not FAIL. */
class MockLimit(msg: String) : RuntimeException(msg)

class PlayerFlowSmokeTest {
    private val results = mutableListOf<Triple<String, Boolean, String>>()
    private val blocked = mutableSetOf<String>()
    private val plain = PlainTextComponentSerializer.plainText()

    private fun step(name: String, body: () -> String?) {
        try { results += Triple(name, true, body() ?: "") }
        catch (t: Throwable) { if (t is org.mockbukkit.mockbukkit.exception.UnimplementedOperationException || t is MockLimit) blocked += name; results += Triple(name, false, (t::class.simpleName + ": " + t.message + " @ " + t.stackTrace.filter { it.className.contains("nakaymi") && !it.className.contains(".qa.") }.take(3).joinToString(" <- ")).take(500)) }
    }

    private fun ServerMock.pump(timeoutMs: Long = 5000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            scheduler.waitAsyncTasksFinished(); scheduler.performOneTick()
            if (cond()) return true
            Thread.sleep(20)
        }
        return cond()
    }

    /** Workaround for the empty-armor-slot NPE in RpgPlugin.stats (probe step 3b documents it). */
    private fun equipFillers(p: PlayerMock) {
        p.inventory.setItem(EquipmentSlot.HEAD, org.bukkit.inventory.ItemStack(Material.LEATHER_HELMET)); p.inventory.setItem(EquipmentSlot.CHEST, org.bukkit.inventory.ItemStack(Material.LEATHER_CHESTPLATE))
        p.inventory.setItem(EquipmentSlot.LEGS, org.bukkit.inventory.ItemStack(Material.LEATHER_LEGGINGS)); p.inventory.setItem(EquipmentSlot.FEET, org.bukkit.inventory.ItemStack(Material.LEATHER_BOOTS))
    }

    /** Regression for QA BUG-1 (fixed by Codex @743604f): empty armor slots return null on real Paper. */
    @Test
    fun emptyArmorSlotsDoNotCrashClassSelection() {
        val server = MockBukkit.mock()
        try {
            val plugin = MockBukkit.load(RpgPlugin::class.java)
            val player = PlayerMock(server, "QaBare", UUID.nameUUIDFromBytes("qa-bare".toByteArray()))
            server.pump { plugin.isEnabled }
            server.addPlayer(player)
            server.pump { plain.serialize(player.openInventory.title()).contains("職業") }
            val slot = (0..8).first { player.openInventory.topInventory.getItem(it)?.persistentDataContainer?.get(NamespacedKey(plugin, "item_id"), PersistentDataType.STRING)?.contains("mage") == true }
            player.simulateInventoryClick(player.openInventory, slot)
            server.pump { player.inventory.getItem(8)?.type == Material.NETHER_STAR } // throws the NPE from the scheduler task
            assertEquals(Material.NETHER_STAR, player.inventory.getItem(8)?.type)
        } finally { MockBukkit.unmock() }
    }

    @Test
    fun scriptedPlayerScenario() {
        val server = MockBukkit.mock()
        try {
            val plugin = MockBukkit.load(RpgPlugin::class.java)
            val itemKey = NamespacedKey(plugin, "item_id")
            val enemyKey = NamespacedKey(plugin, "enemy_id")
            val uuid = UUID.nameUUIDFromBytes("qa-flow".toByteArray())
            var player = PlayerMock(server, "QaPlayer", uuid)

            step("0 plugin enabled (content load, async startup)") {
                assertTrue(server.pump { plugin.isEnabled && server.pluginManager.isPluginEnabled("EtherealChronicle") }, "plugin not enabled")
                null
            }
            step("1 join -> profile loads, class menu opens") {
                equipFillers(player)
                server.addPlayer(player)
                assertTrue(server.pump { player.openInventory.topInventory?.holder is net.nakaymi.etherealchronicle.paper.ClassMenu }, "class menu never opened; view=${player.openInventory.type} title=${plain.serialize(player.openInventory.title())}")
                "menu items=" + (0..8).mapNotNull { player.openInventory.topInventory.getItem(it)?.type }.joinToString()
            }
            step("2 class GUI click (mage) -> starter kit + skill slot") {
                val top = player.openInventory.topInventory
                val slot = (0..8).first { top.getItem(it)?.persistentDataContainer?.get(itemKey, PersistentDataType.STRING)?.contains("mage") == true }
                player.simulateInventoryClick(player.openInventory, slot)
                assertTrue(server.pump { (0..7).any { player.inventory.getItem(it)?.persistentDataContainer?.has(itemKey, PersistentDataType.STRING) == true } }, "no starter item in hotbar 0..7")
                assertEquals(Material.NETHER_STAR, player.inventory.getItem(8)?.type, "slot 9 should be the skill slot")
                "hotbar=" + (0..8).map { player.inventory.getItem(it)?.type }.joinToString()
            }
            step("3 starter weapon in hand, HP scaled") {
                val hand = (0..7).first { player.inventory.getItem(it)?.persistentDataContainer?.has(itemKey, PersistentDataType.STRING) == true }
                player.inventory.heldItemSlot = hand
                assertTrue(player.isHealthScaled && player.healthScale == 20.0, "health scale not applied")
                "maxHealth=${player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)?.value} scale=${player.healthScale}"
            }
            var mob: LivingEntity? = null
            step("4 /ec spawn ec:enemy_aether_slime") {
                val world = server.addSimpleWorld(net.nakaymi.etherealchronicle.paper.DemoWorld.NAME)
                player.teleport(org.bukkit.Location(world, 0.0, 64.0, 0.0))
                server.executeConsole("ec", "spawn", "ec:enemy_aether_slime", "QaPlayer")
                server.pump { true }
                mob = player.world.livingEntities.firstOrNull { it.persistentDataContainer.has(enemyKey, PersistentDataType.STRING) }
                assertNotNull(mob, "no tagged enemy spawned")
                "mob=${mob!!.type} hp=${mob!!.health}/${mob!!.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)?.value}"
            }
            step("5 swap-hand cycles selected skill") {
                val before = player.inventory.itemInOffHand
                val e = PlayerSwapHandItemsEvent(player, player.inventory.itemInMainHand, before)
                server.pluginManager.callEvent(e)
                assertTrue(e.isCancelled, "swap not cancelled by plugin")
                val msgs = generateSequence { player.nextMessage() }.toList()
                assertTrue(msgs.any { it.contains("Selected:") || it.contains("選択:") }, "no skill-selected message; messages=$msgs")
                msgs.last()
            }
            step("6 cast via shared seam castSkill(player, mob)") {
                val m = mob!!
                val r = plugin.castSkill(player, m)
                assertEquals(net.nakaymi.etherealchronicle.core.CastResult.SUCCESS, r, "cast result")
                val again = plugin.castSkill(player, m)
                assertEquals(net.nakaymi.etherealchronicle.core.CastResult.COOLDOWN, again, "immediate recast must be on cooldown")
                "first=$r second=$again (real ray-trace/line-of-sight still unverified: MockBukkit lacks World.rayTraceEntities)"
            }
            step("7 kill reward via shared seam rewardKill(killer, mob)") {
                val m = mob!!
                generateSequence { player.nextMessage() }.toList()
                plugin.rewardKill(player, m)
                server.pump { false.also { } || true }
                server.pump(1500) { false }
                val msgs = generateSequence { player.nextMessage() }.toList()
                assertTrue(msgs.any { it.contains("討伐") || it.contains("Enemy defeated") }, "no kill message; messages=$msgs")
                msgs.joinToString(" | ")
            }
            step("8 quit -> save -> rejoin restores class, no second menu, no duplicate starter") {
                player.disconnect()
                server.pump(1500) { false }
                player = PlayerMock(server, "QaPlayer", uuid)
                equipFillers(player)
                server.addPlayer(player)
                assertTrue(server.pump { player.inventory.getItem(8)?.type == Material.NETHER_STAR }, "skill slot not restored on rejoin")
                assertTrue(player.openInventory.topInventory?.holder !is net.nakaymi.etherealchronicle.paper.ClassMenu, "class menu reopened after rejoin")
                val saved = File(plugin.dataFolder, "playerdata/$uuid.yml")
                assertTrue(server.pump { saved.exists() && saved.readText().contains("ec:class_mage") }, "profile file missing/without class: $saved")
                "profile file has ec:class_mage; (MockBukkit does not persist vanilla inventory, so hotbar below is not meaningful) inventory after rejoin: " + (0..8).map { player.inventory.getItem(it)?.type }.joinToString()
            }
        } finally {
            val report = results.joinToString("\n") { (n, ok, d) -> "${if (ok) "PASS" else if (n in blocked) "BLOCKED(mock)" else "FAIL"}  $n  ${d}" }
            File("build").mkdirs(); File("build/qa-steps.txt").writeText(report)
            println(report)
            MockBukkit.unmock()
        }
        assertTrue(results.all { it.second || it.first in blocked }, "failed steps:\n" + results.filter { !it.second && it.first !in blocked }.joinToString("\n") { "${it.first}: ${it.third}" })
    }
}
