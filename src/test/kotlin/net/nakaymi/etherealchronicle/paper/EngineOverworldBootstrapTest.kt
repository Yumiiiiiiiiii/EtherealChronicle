package net.nakaymi.etherealchronicle.paper

import net.nakaymi.etherealchronicle.core.OverworldSite
import net.nakaymi.etherealchronicle.core.OverworldSiteStatus as GameSiteStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** 建設ジャーナルからゲーム側アンカー・入口への座標変換を確認します。 */
class EngineOverworldBootstrapTest {
    @Test
    fun `ready Lumina spawn uses persisted foot block without changing the world spawn`() {
        val site = OverworldSite("ec:area_lumina_port", 120, 68, -80, 1, "PLAINS")
        val layout = OverworldSiteLayout(site, OverworldSiteStatus.READY,
            mapOf("spawn" to OverworldPoint(123, 69, -77)))

        val anchor = layout.toGameAnchor(null)

        assertEquals(GameSiteStatus.READY, anchor.status)
        assertEquals(123, anchor.plazaSpawnX)
        assertEquals(69, anchor.plazaSpawnY)
        assertEquals(-77, anchor.plazaSpawnZ)
        assertEquals(120, anchor.centerX)
    }

    @Test
    fun `portal keeps rotated entrance center and safe exit`() {
        val portal = OverworldPortal("ec:dungeon_void_sanctum", "ec:area_void_sanctum",
            OverworldPoint(-6, 72, 21), OverworldPoint(-4, 76, 21), OverworldPoint(-8, 70, 24))
        val site = OverworldSiteLayout(OverworldSite(portal.areaId, -5, 70, 21, 2, "DARK_FOREST"),
            OverworldSiteStatus.READY)

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
}
