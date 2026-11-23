package net.nakaymi.etherealchronicle.paper

import net.nakaymi.etherealchronicle.core.OverworldSite
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.bukkit.World
import org.bukkit.Material
import org.bukkit.Chunk
import java.nio.file.Files
import java.nio.file.Path
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

/** 起動時の必須asset欠落と中断journalの安全な手動復旧制御を検証します。 */
class OverworldWorldServiceTest {
    @Test
    fun `close releases held tickets and rejects new starts`() {
        MockBukkit.mock()
        try {
            val plugin = MockBukkit.createMockPlugin()
            val world = mockWorld(Files.createTempDirectory("overworld-close"), "overworld-close")
            val service = OverworldWorldService(plugin, world)
            var removed = 0
            val chunk = Proxy.newProxyInstance(Chunk::class.java.classLoader, arrayOf(Chunk::class.java)) { _, method, _ ->
                when (method.name) {
                    "removePluginChunkTicket" -> { removed++; true }
                    else -> error("Unexpected chunk call: ${method.name}")
                }
            } as Chunk
            val field = OverworldWorldService::class.java.getDeclaredField("tickets")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            (field.get(service) as MutableList<Chunk>).add(chunk)
            service.close()
            assertEquals(1, removed)
            assertThrows<IllegalStateException> { service.start() }
        } finally { MockBukkit.unmock() }
    }

    @Test
    fun `Lumina spawn requires safe floor and two air blocks`() {
        val server = MockBukkit.mock()
        try {
            val plugin = MockBukkit.createMockPlugin()
            val world = server.addSimpleWorld("lumina-spawn-safety")
            val service = OverworldWorldService(plugin, world)
            try {
                val method = OverworldWorldService::class.java.getDeclaredMethod("isSafeSpawn", OverworldPoint::class.java)
                method.isAccessible = true
                val point = OverworldPoint(0, 65, 0)
                fun safe() = method.invoke(service, point) as Boolean
                world.getBlockAt(0, 64, 0).type = Material.STONE
                assertTrue(safe())
                world.getBlockAt(0, 64, 0).type = Material.MAGMA_BLOCK
                assertTrue(!safe())
                world.getBlockAt(0, 64, 0).type = Material.STONE
                world.getBlockAt(0, 66, 0).type = Material.STONE
                assertTrue(!safe())
            } finally { service.close() }
        } finally { MockBukkit.unmock() }
    }

    @Test
    fun `site search cancellation stops an outstanding chunk request`() {
        val server = MockBukkit.mock()
        try {
            val plugin = MockBukkit.createMockPlugin()
            val world = Proxy.newProxyInstance(World::class.java.classLoader, arrayOf(World::class.java)) { _, method, _ ->
                when (method.name) {
                    "getSeed" -> 42L
                    "getChunkAtAsync" -> CompletableFuture<org.bukkit.Chunk>()
                    else -> error("Unexpected search access: ${method.name}")
                }
            } as World
            val search = OverworldSiteSearch(plugin, world)
            val result = search.start()
            search.cancel()
            assertTrue(result.isCancelled)
        } finally { MockBukkit.unmock() }
    }

    @Test
    fun `service close cancels search and releases retained chunk tickets`() {
        MockBukkit.mock()
        try {
            val plugin = MockBukkit.createMockPlugin()
            val folder = Files.createTempDirectory("overworld-close")
            val world = mockWorld(folder, "overworld-close")
            val service = OverworldWorldService(plugin, world) { fixtureAssets() }
            val search = OverworldSiteSearch(plugin, world)
            val removed = AtomicInteger()
            val chunk = Proxy.newProxyInstance(org.bukkit.Chunk::class.java.classLoader, arrayOf(org.bukkit.Chunk::class.java)) { _, method, _ ->
                when (method.name) {
                    "removePluginChunkTicket" -> { removed.incrementAndGet(); true }
                    else -> error("Unexpected chunk access: ${method.name}")
                }
            } as org.bukkit.Chunk
            OverworldWorldService::class.java.getDeclaredField("siteSearch").apply { isAccessible = true; set(service, search) }
            @Suppress("UNCHECKED_CAST")
            val tickets = OverworldWorldService::class.java.getDeclaredField("tickets").apply { isAccessible = true }
                .get(service) as MutableList<org.bukkit.Chunk>
            tickets += chunk
            service.close()
            assertTrue(search.start().isCancelled)
            assertEquals(1, removed.get())
            assertTrue(tickets.isEmpty())
        } finally { MockBukkit.unmock() }
    }

    @Test
    fun `completed journal skips asset loading and duplicate start is refused`() {
        val server = MockBukkit.mock()
        try {
            val plugin = MockBukkit.createMockPlugin()
            val folder = Files.createTempDirectory("overworld-completed")
            val world = mockWorld(folder, "overworld-completed")
            val current = journal(world, OverworldSiteStatus.READY).copy(status = OverworldBuildStatus.COMPLETED,
                sites = SITE_IDS.mapIndexed { index, id ->
                    OverworldSiteLayout(OverworldSite(id, index * 200, 70, index * 200, 1, "PLAINS"), OverworldSiteStatus.READY,
                        if (index == 0) mapOf("spawn" to OverworldPoint(0, 71, 0), "plaza_min" to OverworldPoint(-2, 70, -2),
                            "plaza_max" to OverworldPoint(2, 70, 2), "plaza_center" to OverworldPoint(0, 71, 0)) else emptyMap())
                }, portals = listOf(
                    OverworldPortal("ec:dungeon_novice_arena", "ec:area_ashwood_forest", OverworldPoint(200, 70, 200), OverworldPoint(201, 73, 201), OverworldPoint(200, 71, 202)),
                    OverworldPortal("ec:dungeon_aether_crucible", "ec:area_stargazer_hill", OverworldPoint(600, 70, 600), OverworldPoint(601, 73, 601), OverworldPoint(600, 71, 602)),
                    OverworldPortal("ec:dungeon_void_sanctum", "ec:area_void_sanctum", OverworldPoint(800, 70, 800), OverworldPoint(801, 73, 801), OverworldPoint(800, 71, 802)),
                    OverworldPortal("ec:dungeon_glacial_citadel", "ec:area_glacial_citadel", OverworldPoint(1600, 70, 1600), OverworldPoint(1601, 73, 1601), OverworldPoint(1600, 71, 1602)),
                ))
            OverworldLayoutStore(folder).save(current)
            var loads = 0
            val service = OverworldWorldService(plugin, world) { loads++; fixtureAssets() }
            try {
                val result = service.start()
                assertThrows<IllegalStateException> { service.start() }
                pump(server) { result.isDone }
                assertEquals(current, result.join())
                assertEquals(0, loads)
            } finally { service.close() }
        } finally { MockBukkit.unmock() }
    }

    @Test
    fun `missing schematic assets fail startup before any world mutation`() {
        val server = MockBukkit.mock()
        try {
            val plugin = MockBukkit.createMockPlugin()
            val folder = Files.createTempDirectory("overworld-assets-missing")
            val world = mockWorld(folder, "overworld-assets-missing")
            val service = OverworldWorldService(plugin, world) { OverworldAssets.load { null } }
            try {
                val result = service.start()
                pump(server) { result.isDone }
                assertTrue(result.isCompletedExceptionally)
                assertNull(service.current())
                assertTrue(Files.list(folder).use { !it.findAny().isPresent }, "asset failure must not create a journal")
            } finally {
                service.close()
            }
        } finally {
            MockBukkit.unmock()
        }
    }

    @Test
    fun `in progress journal is loaded without automatic rebuild and repair refuses generating site`() {
        val server = MockBukkit.mock()
        try {
            val plugin = MockBukkit.createMockPlugin()
            val folder = Files.createTempDirectory("overworld-recovery")
            val world = mockWorld(folder, "overworld-recovery")
            val initial = journal(world, OverworldSiteStatus.GENERATING)
            OverworldLayoutStore(folder).save(initial)
            var assetLoads = 0
            val service = OverworldWorldService(plugin, world) {
                assetLoads++
                fixtureAssets()
            }
            try {
                val result = service.start()
                pump(server) { result.isDone }
                assertEquals(initial, result.join())
                assertEquals(initial, service.current())
                assertEquals(1, assetLoads)
                assertThrows<IllegalStateException> { service.repair() }
                val reset = service.acknowledgeCleanup(SITE_IDS[1])
                pump(server) { reset.isDone }
                assertEquals(OverworldSiteStatus.PENDING, reset.join().sites[1].status)
                val repair = service.repair()
                assertTrue(repair.isCompletedExceptionally, "fixture world rejects chunk access after repair starts")
            } finally {
                service.close()
            }
        } finally {
            MockBukkit.unmock()
        }
    }

    private fun fixtureAssets() = OverworldAssets.load {
        SchematicData(1, 1, 1, listOf("minecraft:stone"), intArrayOf(0))
    }

    private fun mockWorld(folder: Path, name: String): World = Proxy.newProxyInstance(
        World::class.java.classLoader, arrayOf(World::class.java),
    ) { _, method, _ -> when (method.name) {
        "getWorldFolder" -> folder.toFile()
        "getUID" -> UUID.nameUUIDFromBytes(name.toByteArray())
        "getName" -> name
        "getSeed" -> 314159L
        else -> error("Unexpected world access in non-generating service path: ${method.name}")
    } } as World

    private fun journal(world: World, interrupted: OverworldSiteStatus): OverworldLayout {
        val states = listOf(OverworldSiteStatus.PENDING, interrupted) + List(7) { OverworldSiteStatus.PENDING }
        return OverworldLayout(
            worldId = world.uid.toString(), worldName = world.name, seed = world.seed,
            status = OverworldBuildStatus.IN_PROGRESS,
            sites = SITE_IDS.mapIndexed { index, id -> OverworldSiteLayout(OverworldSite(id, index * 200, 70, index * 200, 1, "PLAINS"), states[index]) },
        )
    }

    private fun pump(server: ServerMock, ready: () -> Boolean) {
        val until = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < until && !ready()) {
            server.scheduler.performOneTick()
            Thread.sleep(10)
        }
        check(ready()) { "Overworld service future did not complete" }
    }

    private companion object {
        val SITE_IDS = listOf("ec:area_lumina_port", "ec:area_ashwood_forest", "ec:area_weeping_mine", "ec:area_stargazer_hill",
            "ec:area_void_sanctum", "ec:area_frostpeak_outpost", "ec:area_glacier_ridge", "ec:area_crystalline_cavern", "ec:area_glacial_citadel")
    }
}
