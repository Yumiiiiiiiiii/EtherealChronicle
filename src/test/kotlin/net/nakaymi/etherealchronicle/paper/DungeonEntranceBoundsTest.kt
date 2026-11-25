package net.nakaymi.etherealchronicle.paper

import net.nakaymi.etherealchronicle.core.OverworldDungeonPortal
import net.nakaymi.etherealchronicle.core.OverworldRepairResult
import net.nakaymi.etherealchronicle.core.OverworldSiteAnchor
import net.nakaymi.etherealchronicle.core.OverworldSiteRegistry
import net.nakaymi.etherealchronicle.core.OverworldSiteStatus as GameSiteStatus
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.event.player.PlayerMoveEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit

/** 回転済み開口部の内外判定、入口リスナーの早期終了・次tick再検証、安全足場判定を確認します。 */
class DungeonEntranceBoundsTest {
    @Test
    fun `entrance includes all saved portal blocks and excludes adjacent blocks`() {
        val portal = OverworldDungeonPortal(
            "ec:dungeon_void_sanctum",
            "ec:area_void_sanctum",
            10,
            74,
            -5,
            12,
            70,
            -8,
            minX = 9,
            minY = 72,
            minZ = -5,
            maxX = 11,
            maxY = 76,
            maxZ = -5,
        )

        assertTrue(isInsidePortal(portal, 9.5, 72.0, -4.5))
        assertTrue(isInsidePortal(portal, 11.99, 76.99, -4.01))
        assertFalse(isInsidePortal(portal, 8.99, 74.0, -4.5))
        assertFalse(isInsidePortal(portal, 10.0, 77.0, -4.5))
        assertFalse(isInsidePortal(portal, 10.0, 74.0, -3.99))
    }

    @Test
    fun `entrance listener skips same-block camera rotation, verifies same portal next tick, and enforces cooldown`() {
        val server = MockBukkit.mock()
        try {
            val plugin = MockBukkit.load(RpgPlugin::class.java)
            val until = System.currentTimeMillis() + 5000
            while (System.currentTimeMillis() < until && !plugin.isReadyForGameplay) {
                server.scheduler.performOneTick()
                Thread.sleep(10)
            }
            val world = server.addSimpleWorld("portal-test")
            val portalA = OverworldDungeonPortal(
                "ec:dungeon_void_sanctum", "ec:area_void_sanctum",
                10, 72, 10, 10, 70, 6,
                minX = 9, minY = 70, minZ = 10, maxX = 11, maxY = 74, maxZ = 10,
            )
            val portalB = OverworldDungeonPortal(
                "ec:dungeon_glacial_citadel", "ec:area_glacial_citadel",
                20, 72, 10, 20, 70, 6,
                minX = 19, minY = 70, minZ = 10, maxX = 21, maxY = 74, maxZ = 10,
            )
            var portalListCalls = 0
            val registry = object : OverworldSiteRegistry {
                private val sites = mapOf(
                    portalA.areaId to OverworldSiteAnchor(portalA.areaId, 10, 70, 10, "PLAINS", GameSiteStatus.READY, portalA.dungeonId),
                    portalB.areaId to OverworldSiteAnchor(portalB.areaId, 20, 70, 10, "SNOWY_SLOPES", GameSiteStatus.READY, portalB.dungeonId),
                )
                override fun activeWorldName(): String = world.name
                override fun isReady(): Boolean = true
                override fun siteForArea(areaId: String): OverworldSiteAnchor? = sites[areaId]
                override fun allSites(): List<OverworldSiteAnchor> = sites.values.toList()
                override fun portalForDungeon(dungeonId: String): OverworldDungeonPortal? = listOf(portalA, portalB).firstOrNull { it.dungeonId == dungeonId }
                override fun allPortals(): List<OverworldDungeonPortal> {
                    portalListCalls++
                    return listOf(portalA, portalB)
                }
                override fun triggerRepair(): OverworldRepairResult = OverworldRepairResult.UNAVAILABLE
            }
            var now = 10_000L
            val entered = mutableListOf<String>()
            val listener = DungeonEntranceListener(
                plugin = plugin,
                registry = registry,
                onEnter = { _, dungeonId -> entered += dungeonId },
                clock = { now },
                registerListener = false,
            )
            val player = server.addPlayer("PortalWalker")

            // 1. 同一ブロック内での視点回転だけは早期 return し、ポータル一覧すら参照しない
            val insideA = Location(world, 10.5, 71.0, 10.5, 0f, 0f)
            val rotatedA = Location(world, 10.5, 71.0, 10.5, 90f, 15f)
            player.teleport(rotatedA)
            listener.move(PlayerMoveEvent(player, insideA, rotatedA))
            assertEquals(0, portalListCalls)
            server.scheduler.performOneTick()
            assertEquals(emptyList<String>(), entered)

            // 2. ポータル A に入った直後に次 tick 前に別ポータル B へ移動した場合は入場しない
            val outside = Location(world, 10.5, 71.0, 8.5)
            player.teleport(insideA)
            listener.move(PlayerMoveEvent(player, outside, insideA))
            player.teleport(Location(world, 20.5, 71.0, 10.5))
            server.scheduler.performOneTick()
            assertEquals(emptyList<String>(), entered)

            // 3. ポータル A に留まれば次 tick で入場が発火し、クールダウン中の再入場は抑止される
            now += 3_500L
            player.teleport(insideA)
            listener.move(PlayerMoveEvent(player, outside, insideA))
            server.scheduler.performOneTick()
            assertEquals(listOf(portalA.dungeonId), entered)

            listener.move(PlayerMoveEvent(player, outside, insideA))
            server.scheduler.performOneTick()
            assertEquals(listOf(portalA.dungeonId), entered)

            // クールダウン経過後は再び入場可能
            now += 3_000L
            listener.move(PlayerMoveEvent(player, outside, insideA))
            server.scheduler.performOneTick()
            assertEquals(listOf(portalA.dungeonId, portalA.dungeonId), entered)
        } finally {
            MockBukkit.unmock()
        }
    }

    @Test
    fun `SafeStandingSpot validates chunk loaded, solid safe ground, and open head-feet space`() {
        val server = MockBukkit.mock()
        try {
            val world = server.addSimpleWorld("safe-spot")
            // 未読込チャンクは同期ロードせず安全外として扱う
            assertFalse(SafeStandingSpot.isSafe(Location(world, 0.5, 65.0, 0.5)))

            world.loadChunk(0, 0)
            fun setColumn(x: Int, below: Material, feet: Material = Material.AIR, head: Material = Material.AIR): Location {
                world.getBlockAt(x, 64, 0).type = below
                world.getBlockAt(x, 65, 0).type = feet
                world.getBlockAt(x, 66, 0).type = head
                return Location(world, x + 0.5, 65.0, 0.5)
            }

            assertTrue(SafeStandingSpot.isSafe(setColumn(0, Material.STONE)))
            assertFalse(SafeStandingSpot.isSafe(setColumn(1, Material.AIR)))
            assertFalse(SafeStandingSpot.isSafe(setColumn(2, Material.WATER)))
            assertFalse(SafeStandingSpot.isSafe(setColumn(3, Material.LAVA)))
            assertFalse(SafeStandingSpot.isSafe(setColumn(4, Material.MAGMA_BLOCK)))
            assertFalse(SafeStandingSpot.isSafe(setColumn(5, Material.CAMPFIRE)))
            assertFalse(SafeStandingSpot.isSafe(setColumn(6, Material.CACTUS)))
            assertFalse(SafeStandingSpot.isSafe(setColumn(7, Material.STONE, feet = Material.COBBLESTONE)))
            assertFalse(SafeStandingSpot.isSafe(setColumn(8, Material.STONE, head = Material.OAK_LEAVES)))
        } finally {
            MockBukkit.unmock()
        }
    }
}
