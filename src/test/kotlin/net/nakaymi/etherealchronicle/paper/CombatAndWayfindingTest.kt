package net.nakaymi.etherealchronicle.paper

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.nakaymi.etherealchronicle.core.MemoryInventory
import net.nakaymi.etherealchronicle.core.ObjectiveKind
import net.nakaymi.etherealchronicle.core.PlayerProfile
import net.nakaymi.etherealchronicle.data.ContentService
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.LivingEntity
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.persistence.PersistentDataType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.PlayerMock
import java.nio.file.Path
import java.util.UUID

/** 戦闘フィードバック、遠距離通常攻撃（弓・杖）、クエスト道案内（/ec guide & WayfindingController）を検証します。 */
class CombatAndWayfindingTest {
    private val plain = PlainTextComponentSerializer.plainText()

    private fun ServerMock.pump(timeoutMs: Long = 5000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            scheduler.performOneTick()
            if (cond()) return true
            Thread.sleep(20)
        }
        return cond()
    }

    @Test
    fun `wayfinding resolves sequential m01 objectives report NPC and recommended next quest`() {
        val snapshot = ContentService(Path.of("src/main/resources/content")).load()
        val game = net.nakaymi.etherealchronicle.core.GameService(
            { snapshot },
            net.nakaymi.etherealchronicle.core.SkillEngine(net.nakaymi.etherealchronicle.core.Clock { 0L }, snapshot.balance),
        )
        val profile = PlayerProfile(UUID.randomUUID())
        val inv = MemoryInventory(capacity = 100)

        game.chooseClass(profile, "ec:class_archer")
        val first = WayfindingController.resolveGuide(profile, snapshot, inv)
        assertNotNull(first)
        assertEquals("ec:quest_m01_awakening", first!!.questId)
        assertEquals(GuideActionKind.TALK, first.actionKind)
        assertEquals("ec:npc_celia", first.targetId)
        assertTrue(first.isNpcTarget)
        assertNotNull(first.rawPosition)

        // Complete all TALK objectives of m01 -> should switch to REPORT at Vulcan
        game.quests.event(profile, ObjectiveKind.TALK, "ec:npc_celia", 1, inv)
        game.quests.event(profile, ObjectiveKind.TALK, "ec:npc_vulcan", 1, inv)
        game.quests.event(profile, ObjectiveKind.TALK, "ec:npc_garudo", 1, inv)
        game.quests.event(profile, ObjectiveKind.TALK, "ec:npc_vulcan", 1, inv)
        val report = WayfindingController.resolveGuide(profile, snapshot, inv)
        assertNotNull(report)
        assertEquals(GuideActionKind.REPORT, report!!.actionKind)
        assertEquals("ec:npc_vulcan", report.targetId)

        // Complete m01 -> should recommend next available main quest (m02) at Vulcan
        game.completeQuest(profile, "ec:quest_m01_awakening", inv)
        val next = WayfindingController.resolveGuide(profile, snapshot, inv)
        assertNotNull(next)
        assertTrue(next!!.isRecommendedNext)
        assertEquals("ec:quest_m02_first_hunt", next.questId)
        assertEquals(GuideActionKind.ACCEPT, next.actionKind)
        assertEquals("ec:npc_vulcan", next.targetId)
    }

    @Test
    fun `directional arrow and cardinal keys match Minecraft yaw convention`() {
        // Player facing South (yaw = 0 -> +Z)
        assertEquals("↑", WayfindingController.relativeArrow(0f, 0.0, 10.0))
        assertEquals("↓", WayfindingController.relativeArrow(0f, 0.0, -10.0))
        assertEquals("←", WayfindingController.relativeArrow(0f, 10.0, 0.0))
        assertEquals("→", WayfindingController.relativeArrow(0f, -10.0, 0.0))

        // Player facing North (yaw = 180 -> -Z)
        assertEquals("↑", WayfindingController.relativeArrow(180f, 0.0, -10.0))
        assertEquals("→", WayfindingController.relativeArrow(180f, 10.0, 0.0))
        assertEquals("←", WayfindingController.relativeArrow(180f, -10.0, 0.0))

        // Cardinal directions
        assertEquals("ec.guide.dir_n", WayfindingController.cardinalDirectionKey(0.0, -10.0))
        assertEquals("ec.guide.dir_e", WayfindingController.cardinalDirectionKey(10.0, 0.0))
        assertEquals("ec.guide.dir_s", WayfindingController.cardinalDirectionKey(0.0, 10.0))
        assertEquals("ec.guide.dir_w", WayfindingController.cardinalDirectionKey(-10.0, 0.0))
    }

    @Test
    fun `archer receives controls guide skill slot lore guide command and combat feedback without errors`() {
        val server = MockBukkit.mock()
        try {
            val plugin = MockBukkit.load(RpgPlugin::class.java)
            assertTrue(server.pump { plugin.isReadyForGameplay })
            val world = server.getWorld(net.nakaymi.etherealchronicle.core.WorldIds.DEMO) ?: server.addSimpleWorld(net.nakaymi.etherealchronicle.core.WorldIds.DEMO)
            val player = PlayerMock(server, "ArcherGuideQA", UUID.nameUUIDFromBytes("archer-guide-qa".toByteArray()))
            player.addAttachment(plugin, "etherealchronicle.admin", true)
            server.addPlayer(player)

            assertTrue(server.pump { player.openInventory.topInventory?.holder is ClassMenu })
            val itemKey = NamespacedKey(plugin, "item_id")
            val enemyKey = NamespacedKey(plugin, "enemy_id")
            val archerSlot = (0..8).first {
                player.openInventory.topInventory.getItem(it)?.itemMeta?.persistentDataContainer
                    ?.get(itemKey, PersistentDataType.STRING) == "ec:class_archer"
            }
            player.simulateInventoryClick(player.openInventory, archerSlot)
            assertTrue(server.pump { player.inventory.getItem(8)?.type == Material.NETHER_STAR })

            // Skill slot has helpful controls lore
            val skillStar = player.inventory.getItem(8)!!
            val loreLines = skillStar.itemMeta.lore()?.map(plain::serialize).orEmpty()
            assertTrue(loreLines.size >= 3, "Expected 3+ control hint lines on skill slot, got: $loreLines")

            // Drain initial welcome messages and verify guide command works
            val welcomeMsgs = generateSequence { player.nextMessage() }.toList()
            assertTrue(welcomeMsgs.any { it.contains("/ec guide") }, "Expected controls/guide hint on class select: $welcomeMsgs")

            assertTrue(player.performCommand("ec guide"))
            val guideMsgs = generateSequence { player.nextMessage() }.toList()
            assertTrue(guideMsgs.any { it.contains("ec guide") || it.contains("Adventure Guide") || it.contains("冒険ガイド") }, "Expected guide header: $guideMsgs")

            // Right-clicking slot 9 (not sneaking) shows guide; sneak+right-click cycles skill
            player.inventory.heldItemSlot = 8
            player.isSneaking = false
            val guideClick = PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR, skillStar, null, org.bukkit.block.BlockFace.SELF, EquipmentSlot.HAND)
            server.pluginManager.callEvent(guideClick)
            assertTrue(guideClick.isCancelled)
            val clickMsgs = generateSequence { player.nextMessage() }.toList()
            assertTrue(clickMsgs.any { it.contains("Adventure Guide") || it.contains("冒険ガイド") }, "Right-clicking skill star should show guide: $clickMsgs")

            // Spawn enemy and verify basicAttack + virtualDamage + CombatFeedback execute cleanly
            player.teleport(Location(world, 5.0, 64.0, 5.0))
            val bowSlot = (0..7).first { player.inventory.getItem(it)?.type == Material.BOW }
            player.inventory.heldItemSlot = bowSlot
            assertTrue(player.performCommand("ec spawn ec:enemy_thorn_wolf ${player.name}"))
            val mob = player.world.livingEntities.firstOrNull { it.persistentDataContainer.has(enemyKey, PersistentDataType.STRING) }
            assertNotNull(mob)
            val beforeHp = plugin.enemies.state(mob!!.uniqueId)!!.boss.hp
            assertTrue(plugin.basicAttack(player, mob))
            val afterHp = plugin.enemies.state(mob.uniqueId)?.boss?.hp ?: 0.0
            assertTrue(afterHp < beforeHp, "Basic attack did not reduce enemy virtual HP ($beforeHp -> $afterHp)")

            // Left-click with bow triggers rangedBasicAttack path cleanly without cancelling unrelated events
            val leftClick = PlayerInteractEvent(player, Action.LEFT_CLICK_AIR, player.inventory.itemInMainHand, null, org.bukkit.block.BlockFace.SELF, EquipmentSlot.HAND)
            server.pluginManager.callEvent(leftClick)
            assertTrue(leftClick.useItemInHand() != org.bukkit.event.Event.Result.DENY)
        } finally {
            MockBukkit.unmock()
        }
    }
}
