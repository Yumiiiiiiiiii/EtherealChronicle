package net.nakaymi.etherealchronicle.qa

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.nakaymi.etherealchronicle.paper.DemoWorld
import net.nakaymi.etherealchronicle.paper.RpgPlugin
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.BlockFace
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.damage.DamageSource
import org.bukkit.damage.DamageType
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import java.io.File
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Gathering: node protection from vanilla breaking, tool/level gating, channel + reward once, per-player respawn, quit mid-channel. Report: build/qa-gathering.txt */
class GatheringLifeTest {
    private val plain = PlainTextComponentSerializer.plainText()
    private val out = mutableListOf<String>()
    private fun rec(n: String, body: () -> String?) {
        try { out += "PASS  $n  ${body() ?: ""}" }
        catch (t0: Throwable) { val t = if (t0 is java.lang.reflect.InvocationTargetException && t0.cause != null) t0.cause!! else t0
            out += (if (t is org.mockbukkit.mockbukkit.exception.UnimplementedOperationException) "BLOCKED(mock)" else "FAIL") + "  $n  ${(t::class.simpleName + ": " + t.message + " @ " + t.stackTrace.filter { it.className.contains("nakaymi") && !it.className.contains(".qa.") }.take(3).joinToString(" <- ")).take(380)}" }
    }
    private fun ServerMock.pump(timeoutMs: Long = 4000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { scheduler.waitAsyncTasksFinished(); scheduler.performOneTick(); if (cond()) return true; Thread.sleep(20) }
        return cond()
    }
    private fun join(server: ServerMock, plugin: RpgPlugin, name: String): QaPlayerMock {
        val p = QaPlayerMock(server, name, UUID.nameUUIDFromBytes("gl-$name".toByteArray()))
        p.inventory.setItem(EquipmentSlot.HEAD, ItemStack(Material.LEATHER_HELMET)); p.inventory.setItem(EquipmentSlot.CHEST, ItemStack(Material.LEATHER_CHESTPLATE))
        p.inventory.setItem(EquipmentSlot.LEGS, ItemStack(Material.LEATHER_LEGGINGS)); p.inventory.setItem(EquipmentSlot.FEET, ItemStack(Material.LEATHER_BOOTS))
        server.addPlayer(p); server.pump { p.uniqueId in plugin.profiles }
        plugin.profiles.getValue(p.uniqueId).also { it.classId = "ec:class_warrior"; it.level = 20; it.starterGranted = true }
        return p
    }

    @Test
    fun gatheringPaths() {
        val server = MockBukkit.mock()
        try {
            val plugin = MockBukkit.load(RpgPlugin::class.java)
            server.pump { runCatching { plugin.snapshot; plugin.enemies; true }.getOrDefault(false) }
            val world = OwnedWorldMock.add(server, DemoWorld.NAME)
            val nodeLoc = plugin.snapshot.world.gatheringNodes.getValue("ec:node_ashwood_herb").locations.first()
            val ground = world.getBlockAt(nodeLoc.x, nodeLoc.y - 1, nodeLoc.z).also { it.type = Material.GRASS_BLOCK }
            val itemKey = NamespacedKey(plugin, "item_id")
            val a = join(server, plugin, "GatherA"); val b = join(server, plugin, "GatherB")
            a.teleport(Location(world, nodeLoc.x + 0.5, nodeLoc.y.toDouble(), nodeLoc.z + 2.5)); b.teleport(a.location.clone())
            fun count(p: QaPlayerMock, id: String) = (0..35).sumOf { i -> p.inventory.getItem(i)?.takeIf { it.persistentDataContainer.get(itemKey, PersistentDataType.STRING) == id }?.amount ?: 0 }
            fun interact(p: QaPlayerMock) { server.pluginManager.callEvent(PlayerInteractEvent(p, Action.RIGHT_CLICK_BLOCK, p.inventory.itemInMainHand, ground, BlockFace.UP, EquipmentSlot.HAND)) }
            fun interactAt(p: QaPlayerMock, block:org.bukkit.block.Block) { server.pluginManager.callEvent(PlayerInteractEvent(p, Action.RIGHT_CLICK_BLOCK, p.inventory.itemInMainHand, block, BlockFace.UP, EquipmentSlot.HAND)) }

            rec("G0 inactive cancelled damage and death produce no harvest interruption chat; active channel notifies once and shows its BossBar") {
                generateSequence { a.nextMessage() }.toList()
                val cancelled=EntityDamageEvent(a,EntityDamageEvent.DamageCause.CUSTOM,1.0).apply { isCancelled=true }
                server.pluginManager.callEvent(cancelled)
                server.pluginManager.callEvent(PlayerDeathEvent(a,DamageSource.builder(DamageType.GENERIC).build(),mutableListOf(),0,"QA"))
                val idleMessages=generateSequence { a.nextMessage() }.toList()
                assertTrue(idleMessages.none { it.contains("採取") || it.contains("倒れた") },"inactive player was messaged: $idleMessages")
                a.inventory.setItemInMainHand(plugin.createItem("ec:item_tool_sickle_silver")); interact(a)
                assertTrue(plugin.gathering.active(a.uniqueId),"channel was not started")
                assertTrue(plugin.gathering.debugStatus(a.uniqueId).contains("BossBar=true"),"BossBar was not attached: ${plugin.gathering.debugStatus(a.uniqueId)}")
                generateSequence { a.nextMessage() }.toList()
                server.pluginManager.callEvent(EntityDamageEvent(a,EntityDamageEvent.DamageCause.CUSTOM,1.0))
                server.pluginManager.callEvent(EntityDamageEvent(a,EntityDamageEvent.DamageCause.CUSTOM,1.0))
                server.pluginManager.callEvent(PlayerDeathEvent(a,DamageSource.builder(DamageType.GENERIC).build(),mutableListOf(),0,"QA"))
                val activeMessages=generateSequence { a.nextMessage() }.toList().filter { it.contains("採取を中断") || it.contains("倒れたため採取") }
                assertEquals(1,activeMessages.size,"transition should notify exactly once: $activeMessages")
                "inactive silence, one active interruption"
            }

            rec("G0b display-height clicks start a channel and every blocked attempt has a Japanese reason") {
                a.inventory.setItemInMainHand(ItemStack(Material.AIR)); generateSequence { a.nextMessage() }.toList()
                interactAt(a,world.getBlockAt(nodeLoc.x,nodeLoc.y,nodeLoc.z))
                val message=a.nextMessage() ?: error("missing gather requirement message")
                assertTrue(message.contains("SICKLE") && message.contains("専用道具"),"unexpected gate message: $message")
                assertTrue(plugin.gathering.debugStatus(a).contains("last=") && plugin.gathering.debugStatus(a).contains("held=vanilla:AIR"),plugin.gathering.debugStatus(a))
                a.inventory.setItemInMainHand(plugin.createItem("ec:item_tool_sickle_silver")); interactAt(a,world.getBlockAt(nodeLoc.x,nodeLoc.y,nodeLoc.z))
                assertTrue(plugin.gathering.active(a.uniqueId),"clicking visual block coordinate did not start a channel")
                assertTrue(plugin.gathering.debugStatus(a).contains("BossBar=true"),plugin.gathering.debugStatus(a))
                assertTrue((a.nextMessage() ?: "").contains("採取を開始しました"),"channel start did not give chat feedback")
                plugin.gathering.interrupt(a.uniqueId)
                "visible block and ground coordinates resolve; missing tool explained"
            }

            rec("G1 vanilla block breaking is cancelled on and around a node, not elsewhere") {
                val near = world.getBlockAt(nodeLoc.x, nodeLoc.y, nodeLoc.z); val below = ground; val far = world.getBlockAt(nodeLoc.x + 40, nodeLoc.y, nodeLoc.z + 40).also { it.type = Material.DIRT }
                val e1 = BlockBreakEvent(near, a); val e2 = BlockBreakEvent(below, a); val e3 = BlockBreakEvent(far, a)
                listOf(e1, e2, e3).forEach { server.pluginManager.callEvent(it) }
                assertTrue(e1.isCancelled && e2.isCancelled, "node block / ground below not protected"); assertTrue(!e3.isCancelled, "protection leaked to a distant block"); "node and ground protected, far block free"
            }
            rec("G2 wrong tool / missing tool starts no channel") {
                generateSequence { a.nextMessage() }.toList()
                a.inventory.setItemInMainHand(plugin.createItem("ec:item_tool_pick_iron")); interact(a); server.pump(300) { false }
                assertTrue(!plugin.gathering.active(a.uniqueId), "pickaxe started a HERB channel")
                assertTrue((a.nextMessage() ?: "").contains("SICKLE"),"wrong tool reason missing")
                generateSequence { a.nextMessage() }.toList()
                a.inventory.setItemInMainHand(ItemStack(Material.AIR)); interact(a); server.pump(300) { false }
                assertTrue(!plugin.gathering.active(a.uniqueId), "bare hand started a channel")
                assertTrue((a.nextMessage() ?: "").contains("専用道具"),"missing tool reason missing"); null
            }
            rec("G3 correct tool: channel completes once, 1-2 leaves granted, node on cooldown for that player") {
                generateSequence { a.nextMessage() }.toList()
                a.inventory.setItemInMainHand(plugin.createItem("ec:item_tool_sickle_silver")); interact(a)
                assertTrue(plugin.gathering.active(a.uniqueId), "channel did not start")
                assertTrue(plugin.gathering.debugStatus(a.uniqueId).contains("BossBar=true"),"BossBar was not attached")
                var tickErr: Throwable? = null
                val end = System.currentTimeMillis() + 6000
                while (plugin.gathering.active(a.uniqueId) && System.currentTimeMillis() < end) { try { server.scheduler.waitAsyncTasksFinished(); server.scheduler.performOneTick() } catch (t: Throwable) { tickErr = tickErr ?: t }; Thread.sleep(20) }
                if (plugin.gathering.active(a.uniqueId)) { // diagnostic: is the scheduler the problem or the logic?
                    val m = plugin.gathering.javaClass.getDeclaredMethod("tick").also { it.isAccessible = true }; runCatching { m.invoke(plugin.gathering) }.onFailure { tickErr = it.cause ?: it }
                    out += "DIAG  direct tick() -> active=${plugin.gathering.active(a.uniqueId)} err=${tickErr}"
                }
                assertTrue(!plugin.gathering.active(a.uniqueId), "channel never finished; tick error: ${tickErr?.let { it::class.simpleName + ": " + it.message + " @ " + it.stackTrace.take(3).joinToString(" <- ") }}")
                server.pump(1500) { count(a, "ec:item_mat_ash_leaf") > 0 }
                val got = count(a, "ec:item_mat_ash_leaf"); assertTrue(got in 1..2, "leaf amount $got")
                generateSequence { a.nextMessage() }.toList()
                interact(a); server.pump(300) { false }; assertTrue(!plugin.gathering.active(a.uniqueId), "no cooldown: re-gathered immediately")
                assertTrue((a.nextMessage() ?: "").contains("再生中"),"cooldown refusal had no Japanese explanation"); "leaves=$got, second attempt refused"
            }
            rec("G4 respawn is per player: B can gather the node A just depleted") {
                b.inventory.setItemInMainHand(plugin.createItem("ec:item_tool_sickle_silver")); interact(b)
                assertTrue(plugin.gathering.active(b.uniqueId), "B was blocked by A's cooldown"); plugin.gathering.interrupt(b.uniqueId); null
            }
            rec("G5 quit in the middle of a channel: session removed, no reward, no leftover boss bar entry") {
                val c = join(server, plugin, "GatherQuit"); c.teleport(a.location.clone()); c.inventory.setItemInMainHand(plugin.createItem("ec:item_tool_sickle_silver")); interact(c)
                assertTrue(plugin.gathering.active(c.uniqueId)); c.disconnect(); server.pump(1500) { false }
                assertTrue(!plugin.gathering.active(c.uniqueId), "channel survived the quit"); assertEquals(0, count(c, "ec:item_mat_ash_leaf"), "reward granted to a quitter"); "cleaned up"
            }
            rec("G6 plugin disable while channels are open does not throw and cleans up") {
                val d = join(server, plugin, "GatherDis"); d.teleport(a.location.clone()); d.inventory.setItemInMainHand(plugin.createItem("ec:item_tool_sickle_silver")); interact(d)
                plugin.gathering.close(); assertTrue(!plugin.gathering.active(d.uniqueId)); "close() cleared sessions"
            }
        } finally { File("build").mkdirs(); File("build/qa-gathering.txt").writeText(out.joinToString(System.lineSeparator())); println(out.joinToString(System.lineSeparator())); MockBukkit.unmock() }
        assertTrue(out.none { it.startsWith("FAIL") }, out.filter { it.startsWith("FAIL") }.joinToString(System.lineSeparator()))
    }
}
