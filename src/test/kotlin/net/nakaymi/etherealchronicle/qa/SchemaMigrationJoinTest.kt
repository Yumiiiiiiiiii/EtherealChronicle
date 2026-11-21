package net.nakaymi.etherealchronicle.qa

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.nakaymi.etherealchronicle.paper.RpgPlugin
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import java.io.File
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A real join with a legacy schema-5 playerdata file: progress is kept, the file is rewritten as schema 9, the class menu does not reappear. */
class SchemaMigrationJoinTest {
    private fun ServerMock.pump(timeoutMs: Long = 5000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { scheduler.waitAsyncTasksFinished(); scheduler.performOneTick(); if (cond()) return true; Thread.sleep(20) }
        return cond()
    }

    @Test
    fun schemaFiveProfileJoinsAndIsRewrittenAsNine() {
        val server = MockBukkit.mock()
        try {
            val plugin = MockBukkit.load(RpgPlugin::class.java)
            server.pump { runCatching { plugin.snapshot; true }.getOrDefault(false) }
            val uuid = UUID.nameUUIDFromBytes("legacy-5".toByteArray())
            val dir = File(plugin.dataFolder, "playerdata").also { it.mkdirs() }
            val lf = System.lineSeparator()
            File(dir, "$uuid.yml").writeText(listOf(
                "schema_version: 5", "uuid: $uuid", "class: ec:class_warrior", "level: 12", "xp: 345", "currency: 7777", "resource: 10.0",
                "selected_skill: 1", "starter_granted: true",
                "quests:", "  ec:quest_m01_awakening: {progress: 4, completed: true, objectives: {}, completed_at: 5, repeat_available_at: 9223372036854775807}",
                "  ec:quest_m02_first_hunt: {progress: 2, completed: false, objectives: {kill_slimes: 2}, completed_at: 0, repeat_available_at: 9223372036854775807}",
                "skill_levels: {'ec:warrior_strike': 3}", "flags: ['ec:flag_class_selected', 'ec:flag_ch1_started']",
                "transactions: [tx-old]", "daily_purchases: {}", "accessory: null", "pending_rewards: {'ec:item_con_minor_hp_potion': 2}",
                "drop_pity: {'ec:drop_crystal_bat/ec:item_wpn_crystal_recurve': 17}").joinToString(lf))
            val p = org.mockbukkit.mockbukkit.entity.PlayerMock(server, "Legacy", uuid)
            p.inventory.setItem(org.bukkit.inventory.EquipmentSlot.HEAD, ItemStack(Material.LEATHER_HELMET)); p.inventory.setItem(org.bukkit.inventory.EquipmentSlot.CHEST, ItemStack(Material.LEATHER_CHESTPLATE))
            p.inventory.setItem(org.bukkit.inventory.EquipmentSlot.LEGS, ItemStack(Material.LEATHER_LEGGINGS)); p.inventory.setItem(org.bukkit.inventory.EquipmentSlot.FEET, ItemStack(Material.LEATHER_BOOTS))
            server.addPlayer(p)
            assertTrue(server.pump { uuid in plugin.profiles }, "legacy profile did not load (player kicked? online=${p.isOnline})")
            val prof = plugin.profiles.getValue(uuid)
            assertEquals("ec:class_warrior", prof.classId); assertEquals(12, prof.level); assertEquals(345L, prof.xp); assertEquals(7777L, prof.currency)
            assertTrue(prof.quests.getValue("ec:quest_m01_awakening").completed); assertEquals(2, prof.quests.getValue("ec:quest_m02_first_hunt").objectives["kill_slimes"])
            assertEquals(3, prof.skillLevels["ec:warrior_strike"]); assertTrue("ec:flag_ch1_started" in prof.flags); assertTrue("tx-old" in prof.transactions)
            // the mailbox may already have been delivered on join: either still pending, or in the inventory
            val key = org.bukkit.NamespacedKey(plugin, "item_id")
            val owned = (0..35).sumOf { i -> p.inventory.getItem(i)?.takeIf { it.persistentDataContainer.get(key, org.bukkit.persistence.PersistentDataType.STRING) == "ec:item_con_minor_hp_potion" }?.amount ?: 0 }
            fun ownedNow() = (0..35).sumOf { i -> p.inventory.getItem(i)?.takeIf { it.persistentDataContainer.get(key, org.bukkit.persistence.PersistentDataType.STRING) == "ec:item_con_minor_hp_potion" }?.amount ?: 0 }
            server.pump(3000) { ownedNow() + (prof.pendingRewards["ec:item_con_minor_hp_potion"] ?: 0) >= 2 }
            val owned2 = ownedNow()
            assertEquals(2, (prof.pendingRewards["ec:item_con_minor_hp_potion"] ?: 0) + owned2, "mailbox rewards lost (pending=${prof.pendingRewards} owned=$owned2)")
            assertEquals(17, prof.dropPity["ec:drop_crystal_bat/ec:item_wpn_crystal_recurve"])
            assertEquals(null, prof.selectedTitle, "new field selected_title should default to null")
            assertTrue(prof.bestiary.isEmpty() && prof.discoveredItems.isEmpty() && prof.challengeRecords.isEmpty(), "new schema-9 fields should start empty")
            assertTrue(!PlainTextComponentSerializer.plainText().serialize(p.openInventory.title()).contains("職業"), "class menu reopened for a profile with a class")
            plugin.persist(prof).join()
            val saved = File(dir, "$uuid.yml").readText()
            assertTrue(saved.contains("schema_version: 10"), "file not rewritten as schema 10:$lf$saved")
            assertTrue(saved.contains("currency: 7777") && saved.contains("crystal_recurve': 17") && saved.contains("kill_slimes"), "progress lost in rewrite")
        } finally { MockBukkit.unmock() }
    }
}
