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
import java.nio.file.Files
import java.nio.file.Path
import java.lang.reflect.Proxy
import java.util.UUID

/** 起動時の必須asset欠落と中断journalの安全な手動復旧制御を検証します。 */
class OverworldWorldServiceTest {
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
