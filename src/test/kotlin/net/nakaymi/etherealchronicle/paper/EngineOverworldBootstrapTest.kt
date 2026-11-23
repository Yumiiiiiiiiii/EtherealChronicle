package net.nakaymi.etherealchronicle.paper

import net.nakaymi.etherealchronicle.core.OverworldCleanupAckResult
import net.nakaymi.etherealchronicle.core.OverworldRepairResult
import net.nakaymi.etherealchronicle.core.OverworldSite
import net.nakaymi.etherealchronicle.core.OverworldSiteStatus as GameSiteStatus
import org.bukkit.World
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.UUID

/** 建設ジャーナルからゲーム側アンカー・入口への座標変換とレジストリのキャッシュ契約を確認します。 */
class EngineOverworldBootstrapTest {
    @Test
    fun `ready Lumina spawn uses persisted foot block without changing the world spawn`() {
        val site = OverworldSite("ec:area_lumina_port", 120, 68, -80, 1, "PLAINS")
        val layout = OverworldSiteLayout(
            site,
            OverworldSiteStatus.READY,
            mapOf("spawn" to OverworldPoint(123, 69, -77)),
        )

        val anchor = layout.toGameAnchor(null)

        assertEquals(GameSiteStatus.READY, anchor.status)
        assertEquals(123, anchor.plazaSpawnX)
        assertEquals(69, anchor.plazaSpawnY)
        assertEquals(-77, anchor.plazaSpawnZ)
        assertEquals(120, anchor.centerX)
    }

    @Test
    fun `portal keeps rotated entrance center and safe exit`() {
        val portal = OverworldPortal(
            "ec:dungeon_void_sanctum",
            "ec:area_void_sanctum",
            OverworldPoint(-6, 72, 21),
            OverworldPoint(-4, 76, 21),
            OverworldPoint(-8, 70, 24),
        )
        val site = OverworldSiteLayout(
            OverworldSite(portal.areaId, -5, 70, 21, 2, "DARK_FOREST"),
            OverworldSiteStatus.READY,
        )

        val anchor = site.toGameAnchor(portal)
        val gamePortal = portal.toGamePortal()

        assertEquals(-5, gamePortal.centerX)
        assertEquals(74, gamePortal.centerY)
        assertEquals(-8, gamePortal.exitX)
        assertEquals(70, gamePortal.exitY)
        assertEquals(24, gamePortal.exitZ)
        assertEquals(gamePortal.dungeonId, anchor.dungeonId)
        assertEquals(gamePortal.centerX, anchor.portalCenterX)
    }

    @Test
    fun `EngineSiteRegistry caches immutable site and portal snapshots and returns enum results on unavailable repair`() {
        val server = MockBukkit.mock()
        try {
            val plugin = MockBukkit.load(RpgPlugin::class.java)
            val until = System.currentTimeMillis() + 5000
            while (System.currentTimeMillis() < until && !plugin.isReadyForGameplay) {
                server.scheduler.performOneTick()
                Thread.sleep(10)
            }
            val folder = Files.createTempDirectory("engine-registry-cache")
            val world = Proxy.newProxyInstance(
                World::class.java.classLoader,
                arrayOf(World::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "getWorldFolder" -> folder.toFile()
                    "getUID" -> UUID.nameUUIDFromBytes("engine-registry-cache".toByteArray())
                    "getName" -> "engine-registry-cache"
                    "getSeed" -> 42L
                    else -> error("Unexpected world call: ${method.name}")
                }
            } as World
            val layout = OverworldLayout(
                worldId = world.uid.toString(),
                worldName = world.name,
                seed = world.seed,
                status = OverworldBuildStatus.IN_PROGRESS,
                sites = listOf(
                    "ec:area_lumina_port", "ec:area_ashwood_forest", "ec:area_weeping_mine",
                    "ec:area_stargazer_hill", "ec:area_void_sanctum", "ec:area_frostpeak_outpost",
                    "ec:area_glacier_ridge", "ec:area_crystalline_cavern", "ec:area_glacial_citadel",
                ).mapIndexed { index, id ->
                    val status = if (index == 0) OverworldSiteStatus.READY else OverworldSiteStatus.GENERATING
                    OverworldSiteLayout(OverworldSite(id, index * 200, 70, index * 200, 1, "PLAINS"), status)
                },
            )
            OverworldLayoutStore(folder).save(layout)
            val service = OverworldWorldService(plugin, world) {
                OverworldAssets.load { SchematicData(1, 1, 1, listOf("minecraft:stone"), intArrayOf(0)) }
            }
            try {
                val started = service.start()
                val startDeadline = System.currentTimeMillis() + 5000
                while (System.currentTimeMillis() < startDeadline && !started.isDone) {
                    server.scheduler.performOneTick()
                    Thread.sleep(10)
                }
                val registry = EngineSiteRegistry(
                    worldName = world.name,
                    areas = plugin.snapshot.world.areas.values,
                    service = service,
                    logger = plugin.logger,
                    runSync = { action -> plugin.sync(action) },
                    onChanged = {},
                )
                val firstSites = registry.allSites()
                val secondSites = registry.allSites()
                val firstPortals = registry.allPortals()
                val secondPortals = registry.allPortals()

                // layout 参照が変わらない限り同一スナップショットを返し、毎呼び出し再生成しない
                assertSame(firstSites, secondSites)
                assertSame(firstPortals, secondPortals)
                assertEquals(GameSiteStatus.READY, registry.siteForArea("ec:area_lumina_port")?.status)
                // GENERATING が残っているため repair は UNAVAILABLE enum を返す
                assertEquals(OverworldRepairResult.UNAVAILABLE, registry.triggerRepair())
                // 存在しないエリアへの cleanup 確認は UNAVAILABLE enum を返す
                assertEquals(OverworldCleanupAckResult.UNAVAILABLE, registry.acknowledgeCleanup("ec:area_nonexistent"))
            } finally {
                service.close()
            }
        } finally {
            MockBukkit.unmock()
        }
    }
}
