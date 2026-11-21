package net.nakaymi.etherealchronicle.qa

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.nakaymi.etherealchronicle.paper.DemoWorld
import net.nakaymi.etherealchronicle.paper.RpgPlugin
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.damage.DamageSource
import org.bukkit.damage.DamageType
import org.bukkit.entity.LivingEntity
import org.bukkit.event.entity.EntityDamageEvent
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

/** /ec flag, REVIVE accessory (lethal only), party pity accounting, reconnect race. Report: build/qa-flag-revive-steps.txt */
class FlagReviveReconnectTest {
    private val plain = PlainTextComponentSerializer.plainText()
    private val out = mutableListOf<Triple<String, Boolean, String>>()
    private fun rec(n: String, body: () -> String?) {
        try { out += Triple(n, true, body() ?: "") }
        catch (t: Throwable) { out += Triple(n, false, (t::class.simpleName + ": " + t.message + " @ " + t.stackTrace.filter { it.className.contains("nakaymi") && !it.className.contains(".qa.") }.take(3).joinToString(" <- ")).take(420)) }
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
        val p = PlayerMock(server, name, UUID.nameUUIDFromBytes("frr-$name".toByteArray())); filler(p); server.addPlayer(p)
        val key = NamespacedKey(plugin, "item_id")
        server.pump { plain.serialize(p.openInventory.title()).contains("職業") }
        val slot = (0..8).first { p.openInventory.topInventory.getItem(it)?.persistentDataContainer?.get(key, PersistentDataType.STRING)?.contains("warrior") == true }
        p.simulateInventoryClick(p.openInventory, slot)
        server.pump { p.inventory.getItem(8)?.type == Material.NETHER_STAR }
        return p
    }

    @Test
    fun flagReviveReconnectAndPartyPity() {
        val server = MockBukkit.mock()
        try {
            val plugin = MockBukkit.load(RpgPlugin::class.java)
            server.pump { plugin.isEnabled }
            val world = server.addSimpleWorld(DemoWorld.NAME)
            val enemyKey = NamespacedKey(plugin, "enemy_id"); val itemKey = NamespacedKey(plugin, "item_id")
            val a = join(server, plugin, "FrrA"); val b = join(server, plugin, "FrrB")
            // far away from every chapter area: the Lumina safe zone would cancel all damage events
            a.teleport(Location(world, 900.0, 65.0, 900.0)); b.teleport(Location(world, 901.0, 65.0, 900.0))
            val pa = plugin.profiles.getValue(a.uniqueId); val pb = plugin.profiles.getValue(b.uniqueId)

            rec("F1 /ec flag: non-op rejected, op can set/clear a chapter flag, bad id and undeclared flag rejected") {
                a.isOp = false; a.performCommand("ec flag FrrA ec:flag_sanctum_unlocked"); server.pump(300) { false }
                assertTrue("ec:flag_sanctum_unlocked" !in pa.flags, "non-op set a flag")
                a.isOp = true
                a.performCommand("ec flag FrrA ec:flag_sanctum_unlocked"); assertTrue("ec:flag_sanctum_unlocked" in pa.flags, "set failed")
                a.performCommand("ec flag FrrA ec:flag_sanctum_unlocked clear"); assertTrue("ec:flag_sanctum_unlocked" !in pa.flags, "clear failed")
                val before = pa.flags.toSet()
                a.performCommand("ec flag FrrA BAD_FLAG"); a.performCommand("ec flag FrrA ec:flag_not_in_any_chapter")
                a.performCommand("ec flag FrrA ec:flag_sanctum_unlocked frob")
                assertEquals(before, pa.flags.toSet(), "invalid flag operations changed the profile")
                a.isOp = false; "set/clear ok; invalid rejected"
            }

            rec("R1 REVIVE ring revives only on a lethal hit, once per cooldown") {
                val ring = "ec:item_acc_void_heart_ring"
                pa.level = 20; pa.accessory = ring
                val create = RpgPlugin::class.java.declaredMethods.first { it.name.startsWith("createItem") && it.parameterCount == 2 }.also { it.isAccessible = true }
                a.inventory.setItem(20, create.invoke(plugin, ring, 1) as ItemStack)
                fun hurt(d: Double): EntityDamageEvent {
                    val ev = EntityDamageEvent(a, EntityDamageEvent.DamageCause.ENTITY_ATTACK, DamageSource.builder(DamageType.MOB_ATTACK).build(), d)
                    server.pluginManager.callEvent(ev); return ev
                }
                a.health = a.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)!!.value
                val nonLethal = hurt(1.0); assertTrue(!nonLethal.isCancelled, "non-lethal damage triggered the revive")
                val lethal = hurt(100000.0); assertTrue(lethal.isCancelled, "lethal damage not intercepted by REVIVE ring")
                assertTrue(a.health >= 1.0, "health after revive = ${a.health}")
                val again = hurt(100000.0); assertTrue(!again.isCancelled, "second lethal hit inside cooldown must not revive")
                "revived at hp=${a.health}; second lethal inside cooldown not intercepted"
            }

            rec("R2 REVIVE ring does nothing without the item in the inventory") {
                pb.level = 20; pb.accessory = "ec:item_acc_void_heart_ring"
                val ev = EntityDamageEvent(b, EntityDamageEvent.DamageCause.ENTITY_ATTACK, DamageSource.builder(DamageType.MOB_ATTACK).build(), 100000.0)
                server.pluginManager.callEvent(ev); assertTrue(!ev.isCancelled, "revive fired although the ring is not owned"); null
            }

            rec("P1 party kills: pity counts one chosen-recipient roll per kill and persists") {
                val parties=plugin.adventure.parties
                parties.invite(a.uniqueId,b.uniqueId); parties.accept(b.uniqueId)
                val pityKey="ec:drop_crystal_bat/ec:item_wpn_crystal_recurve"
                val itemId="ec:item_wpn_crystal_recurve"
                fun ownedRare(p: PlayerMock,profile: net.nakaymi.etherealchronicle.core.PlayerProfile) =
                    (profile.pendingRewards[itemId] ?: 0) + p.inventory.storageContents.sumOf { stack -> if(plugin.identify(stack)==itemId) stack!!.amount else 0 }
                pa.dropPity.clear(); pb.dropPity.clear(); pa.pendingRewards.clear(); pb.pendingRewards.clear()
                val kills=12
                val sequence=RpgPlugin::class.java.getDeclaredField("lootSequence").also { it.isAccessible=true }
                repeat(kills) { index ->
                    val seq=sequence.getLong(plugin)
                    val selected=parties.lootRecipient(setOf(a.uniqueId,b.uniqueId),seq)!!
                    val selectedProfile=if(selected==a.uniqueId) pa else pb
                    val otherProfile=if(selected==a.uniqueId) pb else pa
                    val selectedBefore=selectedProfile.dropPity[pityKey] ?: 0
                    val otherBefore=otherProfile.dropPity[pityKey] ?: 0
                    val dropsBefore=ownedRare(a,pa)+ownedRare(b,pb)
                    val m=world.spawn(a.location,org.bukkit.entity.Phantom::class.java)
                    m.persistentDataContainer.set(enemyKey,PersistentDataType.STRING,"ec:enemy_crystal_bat")
                    plugin.rewardKill(a,m); m.remove()
                    assertTrue(server.pump(10000) { !plugin.adventure.hasTransactions() },"party rewards did not drain at kill ${index+1}")
                    val dropsAfter=ownedRare(a,pa)+ownedRare(b,pb)
                    val delta=dropsAfter-dropsBefore
                    assertTrue(delta in 0..1,"exactly one loot roll is expected per party kill, got $delta at ${index+1}")
                    val selectedAfter=selectedProfile.dropPity[pityKey] ?: 0
                    val otherAfter=otherProfile.dropPity[pityKey] ?: 0
                    if(delta == 1) assertEquals(0,selectedAfter,"successful rare drop must reset only its recipient's pity")
                    else assertEquals(selectedBefore+1,selectedAfter,"one unsuccessful kill must add exactly one miss")
                    assertEquals(otherBefore,otherAfter,"non-recipient pity must remain unchanged")
                }
                val sum=(pa.dropPity[pityKey] ?: 0)+(pb.dropPity[pityKey] ?: 0)
                val info="kills=$kills currency=${pa.currency}+${pb.currency} pity a=${pa.dropPity[pityKey]} b=${pb.dropPity[pityKey]} misses=$sum"
                assertEquals(kills*43L,pa.currency+pb.currency,"all tagged rewardKill calls must grant currency; $info")
                info
            }

            rec("C1 reconnect race: quit and rejoin before the first load/save finished keeps latest state, one session") {
                pa.currency = 4321; pa.level = 7
                a.disconnect()
                val again = PlayerMock(server, "FrrA", a.uniqueId); filler(again); server.addPlayer(again)   // immediately, no pump in between
                assertTrue(server.pump(5000) { a.uniqueId in plugin.profiles }, "profile not available after fast rejoin")
                val p2 = plugin.profiles.getValue(a.uniqueId)
                assertTrue(again.isOnline, "player was kicked on fast rejoin")
                assertEquals(4321L, p2.currency, "currency lost across fast reconnect"); assertEquals(7, p2.level, "level lost across fast reconnect")
                val saved = File(plugin.dataFolder, "playerdata/${a.uniqueId}.yml").readText()
                assertTrue(saved.contains("currency: 4321"), "file does not contain the latest currency")
                "state kept; online=${again.isOnline} profiles=${plugin.profiles.size}"
            }

            rec("C2 three rapid quit/rejoin cycles never duplicate the profile entry or lose the class") {
                var cur: PlayerMock = server.getPlayer("FrrA") as PlayerMock
                repeat(3) { cur.disconnect(); cur = PlayerMock(server, "FrrA", a.uniqueId).also { filler(it); server.addPlayer(it) } }
                assertTrue(server.pump(5000) { a.uniqueId in plugin.profiles }, "profile missing")
                server.pump(1500) { false }
                assertEquals("ec:class_warrior", plugin.profiles.getValue(a.uniqueId).classId, "class lost")
                assertTrue(plain.serialize(cur.openInventory.title()) != "職業を選んでください", "class menu reopened after reconnect cycles")
                "class kept; no menu"
            }
        } finally {
            val report = out.joinToString("\n") { (n, ok, d) -> "${if (ok) "PASS" else "FAIL"}  $n  $d" }
            File("build").mkdirs(); File("build/qa-flag-revive-steps.txt").writeText(report); println(report)
            MockBukkit.unmock()
        }
        assertTrue(out.all { it.second }, "failed:\n" + out.filter { !it.second }.joinToString("\n") { "${it.first}: ${it.third}" })
    }
}
