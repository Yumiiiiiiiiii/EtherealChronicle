package net.nakaymi.etherealchronicle.qa

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.nakaymi.etherealchronicle.paper.RpgPlugin
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.PlayerMock
import java.io.File
import java.util.UUID
import kotlin.test.assertTrue

/** Ch1 sanctum and Ch2 glacial citadel coexisting; unlock-flag gating. Report: build/qa-dungeon-steps.txt (BLOCKED(mock) = MockBukkit cannot emulate it). */
class DungeonCoexistTest {
    private val plain = PlainTextComponentSerializer.plainText()
    private val out = mutableListOf<String>()
    private fun rec(n: String, body: () -> String?) {
        try { out += "PASS  $n  ${body() ?: ""}" }
        catch (t0: Throwable) {
            val t = if (t0 is java.lang.reflect.InvocationTargetException && t0.cause != null) t0.cause!! else t0
            val kind = if (t is org.mockbukkit.mockbukkit.exception.UnimplementedOperationException) "BLOCKED(mock)" else "FAIL"
            out += "$kind  $n  ${(t::class.simpleName + ": " + t.message + " @ " + t.stackTrace.filter { it.className.contains("nakaymi") && !it.className.contains(".qa.") }.take(3).joinToString(" <- ")).take(380)}"
        }
    }
    private fun ServerMock.pump(timeoutMs: Long = 4000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { scheduler.performOneTick(); if (cond()) return true; Thread.sleep(20) }
        return cond()
    }
    private fun join(server: ServerMock, plugin: RpgPlugin, name: String): PlayerMock {
        val p = PlayerMock(server, name, UUID.nameUUIDFromBytes("dg-$name".toByteArray()))
        p.inventory.setItem(EquipmentSlot.HEAD, ItemStack(Material.LEATHER_HELMET)); p.inventory.setItem(EquipmentSlot.CHEST, ItemStack(Material.LEATHER_CHESTPLATE))
        p.inventory.setItem(EquipmentSlot.LEGS, ItemStack(Material.LEATHER_LEGGINGS)); p.inventory.setItem(EquipmentSlot.FEET, ItemStack(Material.LEATHER_BOOTS))
        server.addPlayer(p)
        val key = NamespacedKey(plugin, "item_id")
        server.pump { plain.serialize(p.openInventory.title()).contains("職業") }
        val slot = (0..8).first { p.openInventory.topInventory.getItem(it)?.persistentDataContainer?.get(key, PersistentDataType.STRING)?.contains("warrior") == true }
        p.simulateInventoryClick(p.openInventory, slot); server.pump { p.inventory.getItem(8)?.type == Material.NETHER_STAR }
        return p
    }

    @Test
    fun sanctumAndCitadel() {
        val server = MockBukkit.mock()
        try {
            val plugin = MockBukkit.load(RpgPlugin::class.java); server.pump { plugin.isReadyForGameplay }
            val world = server.addSimpleWorld(net.nakaymi.etherealchronicle.core.WorldIds.DEMO)
            val a = join(server, plugin, "DgA"); val b = join(server, plugin, "DgB")
            a.teleport(Location(world, 0.0, 65.0, 0.0)); b.teleport(Location(world, 5.0, 65.0, 5.0))
            val pa = plugin.profiles.getValue(a.uniqueId); val pb = plugin.profiles.getValue(b.uniqueId)
            pa.level = 20; pb.level = 20
            val dungeons = RpgPlugin::class.java.getDeclaredField("dungeons").also { it.isAccessible = true }.get(plugin)
            val enter = dungeons.javaClass.getMethod("enter", Player::class.java, String::class.java)
            val leave = dungeons.javaClass.getMethod("leave", Player::class.java)
            val active = dungeons.javaClass.getMethod("activeCount")
            fun msgs(p: PlayerMock) = generateSequence { p.nextMessage() }.toList().joinToString(" | ")

            rec("D1 flag gate: citadel refused without ec:flag_ch2_citadel_unlocked, sanctum refused without ec:flag_sanctum_unlocked") {
                var refused = 0
                for (id in listOf("ec:dungeon_glacial_citadel", "ec:dungeon_void_sanctum")) {
                    val r = runCatching { enter.invoke(dungeons, a, id) }
                    if (r.isFailure || (active.invoke(dungeons) as Int) == 0) refused++
                }
                assertTrue((active.invoke(dungeons) as Int) == 0, "a dungeon instance was created without the unlock flags")
                assertTrue(refused == 2, "refused=$refused"); "both refused, 0 instances (${msgs(a).take(120)})"
            }
            rec("D2 with both flags, A enters the sanctum and B enters the citadel at the same time (independent instances)") {
                pa.flags += "ec:flag_sanctum_unlocked"; pb.flags += "ec:flag_ch2_citadel_unlocked"
                enter.invoke(dungeons, a, "ec:dungeon_void_sanctum"); enter.invoke(dungeons, b, "ec:dungeon_glacial_citadel")
                server.pump(500) { false }
                assertTrue((active.invoke(dungeons) as Int) == 2, "expected 2 instances, got ${active.invoke(dungeons)}")
                "instances=2; A world=${a.world.name} B world=${b.world.name}"
            }
            rec("D3 leave cleans up each instance; re-entering works (no leftover state)") {
                leave.invoke(dungeons, a); leave.invoke(dungeons, b); server.pump(500) { false }
                assertTrue((active.invoke(dungeons) as Int) == 0, "instances left: ${active.invoke(dungeons)}")
                pa.flags += "ec:flag_ch2_citadel_unlocked"
                enter.invoke(dungeons, a, "ec:dungeon_glacial_citadel"); assertTrue((active.invoke(dungeons) as Int) == 1)
                leave.invoke(dungeons, a); assertTrue((active.invoke(dungeons) as Int) == 0); "clean"
            }
            rec("D4 unknown dungeon id is rejected") {
                val before = active.invoke(dungeons) as Int
                runCatching { enter.invoke(dungeons, a, "ec:dungeon_nope") }
                assertTrue((active.invoke(dungeons) as Int) == before, "unknown id created an instance"); null
            }
        } finally {
            File("build").mkdirs(); File("build/qa-dungeon-steps.txt").writeText(out.joinToString("\n")); println(out.joinToString("\n"))
            MockBukkit.unmock()
        }
        assertTrue(out.none { it.startsWith("FAIL") }, out.filter { it.startsWith("FAIL") }.joinToString("\n"))
    }

    @Test
    fun airshipBothDirections() {
        val server = MockBukkit.mock(); val res = mutableListOf<String>()
        try {
            val plugin = MockBukkit.load(RpgPlugin::class.java); server.pump { plugin.isReadyForGameplay }
            val world = server.addSimpleWorld(net.nakaymi.etherealchronicle.core.WorldIds.DEMO)
            val a = join(server, plugin, "AirA"); a.teleport(Location(world, 0.0, 65.0, 0.0))
            val pa = plugin.profiles.getValue(a.uniqueId); pa.level = 20
            val adv = RpgPlugin::class.java.getDeclaredField("adventure").also { it.isAccessible = true }.get(plugin)
            val show = adv.javaClass.methods.first { it.name == "showDialogue" && it.parameterCount == 3 }
            val entryKey = NamespacedKey(plugin, "entry_id")
            fun choiceSlot(id: String): Int? = (9..53).firstOrNull { a.openInventory.topInventory.getItem(it)?.persistentDataContainer?.get(entryKey, PersistentDataType.STRING) == id }
            fun open(d: String) { show.invoke(adv, a, d, null); server.pump(300) { false } }
            fun step(n: String, body: () -> String) { try { res += "PASS  $n  ${body()}" } catch (t: Throwable) { res += "FAIL  $n  ${t::class.simpleName}: ${t.message}".take(300) } }
            step("A1 before ec:flag_ch2_started the airship choice is not offered (Freya)") { open("ec:dlg_freya_hub"); assertTrue(choiceSlot("airship_to_frostpeak") == null, "airship choice offered too early"); "hidden" }
            step("A2 after the flag: Lumina -> Frostpeak teleports to (480,65,0)") {
                pa.flags += "ec:flag_ch2_started"; open("ec:dlg_freya_hub")
                val slot = choiceSlot("airship_to_frostpeak") ?: error("choice missing after flag")
                a.simulateInventoryClick(a.openInventory, org.bukkit.event.inventory.ClickType.LEFT, slot); server.pump(2500) { a.location.x > 400 }
                assertTrue(a.location.x > 400, "not teleported: ${a.location}"); "at ${a.location.x},${a.location.y},${a.location.z}"
            }
            step("A3 return Frostpeak -> Lumina (Orphe) teleports to (18,65,-12)") {
                open("ec:dlg_orphe_hub"); val slot = choiceSlot("airship_to_lumina") ?: error("return choice missing")
                a.simulateInventoryClick(a.openInventory, org.bukkit.event.inventory.ClickType.LEFT, slot); server.pump(2500) { a.location.x < 100 }
                assertTrue(a.location.x in 10.0..30.0, "not teleported back: ${a.location}"); "at ${a.location.x},${a.location.y},${a.location.z}"
            }
            step("A4 clearing the flag removes both choices again") { pa.flags -= "ec:flag_ch2_started"; open("ec:dlg_orphe_hub"); assertTrue(choiceSlot("airship_to_lumina") == null, "return choice still offered"); "hidden" }
        } finally { File("build").mkdirs(); File("build/qa-airship-steps.txt").writeText(res.joinToString(System.lineSeparator())); println(res.joinToString(System.lineSeparator())); MockBukkit.unmock() }
        assertTrue(res.none { it.startsWith("FAIL") }, res.filter { it.startsWith("FAIL") }.joinToString(System.lineSeparator()))
    }
}
