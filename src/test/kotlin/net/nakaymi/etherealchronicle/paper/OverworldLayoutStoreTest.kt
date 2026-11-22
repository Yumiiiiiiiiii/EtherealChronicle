package net.nakaymi.etherealchronicle.paper

import net.nakaymi.etherealchronicle.core.OverworldSite
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** ワールド生成journalの原子的な保存形式と状態検証を確認します。 */
class OverworldLayoutStoreTest {
    @TempDir lateinit var folder: Path

    @Test
    fun `layout and completion marker survive a round trip`() {
        val store = OverworldLayoutStore(folder)
        val expected = completedLayout()
        assertNull(store.load(UUID.fromString(expected.worldId), expected.worldName, expected.seed))

        store.save(expected)
        store.markCompleted()

        assertEquals(expected, store.load(UUID.fromString(expected.worldId), expected.worldName, expected.seed))
        assertEquals("overworld-v1\n", Files.readString(folder.resolve(".etherealchronicle-owned")))
        store.markCompleted()
        assertEquals("overworld-v1\n", Files.readString(folder.resolve(".etherealchronicle-owned")))
    }

    @Test
    fun `corrupt journal and journal for a different world are rejected`() {
        val store = OverworldLayoutStore(folder)
        val layout = inProgressLayout()
        store.save(layout)
        assertThrows<IllegalArgumentException> { store.load(UUID.randomUUID(), layout.worldName, layout.seed) }

        Files.writeString(folder.resolve(".etherealchronicle-overworld.json"), "{broken")
        assertThrows<RuntimeException> { store.load(UUID.fromString(layout.worldId), layout.worldName, layout.seed) }
    }

    @Test
    fun `completed state cannot contain pending sites or missing portal records`() {
        val store = OverworldLayoutStore(folder)
        val good = completedLayout()
        assertThrows<IllegalArgumentException> { store.save(good.copy(sites = good.sites.dropLast(1) + good.sites.last().copy(status = OverworldSiteStatus.PENDING))) }
        assertThrows<IllegalArgumentException> { store.save(good.copy(portals = good.portals.dropLast(1))) }
    }

    private fun inProgressLayout() = layout(OverworldBuildStatus.IN_PROGRESS, OverworldSiteStatus.PENDING, emptyList())

    private fun completedLayout(): OverworldLayout {
        val portals = listOf("novice_arena" to "ec:area_ashwood_forest", "void_sanctum" to "ec:area_void_sanctum",
            "aether_crucible" to "ec:area_stargazer_hill", "glacial_citadel" to "ec:area_glacial_citadel").mapIndexed { index, (id, siteId) ->
            val siteIndex = SITE_IDS.indexOf(siteId)
            val x = siteIndex * 200
            val z = siteIndex * 210
            OverworldPortal("ec:dungeon_$id", siteId, OverworldPoint(x, 70, z), OverworldPoint(x + 1, 74, z + 1), OverworldPoint(x, 71, z + 2))
        }
        return layout(OverworldBuildStatus.COMPLETED, OverworldSiteStatus.READY, portals)
    }

    private fun layout(status: OverworldBuildStatus, siteStatus: OverworldSiteStatus, portals: List<OverworldPortal>) = OverworldLayout(
        worldId = WORLD_ID.toString(), worldName = "journal-test", seed = 123456L, status = status,
        sites = SITE_IDS.mapIndexed { index, id -> OverworldSiteLayout(OverworldSite(id, index * 200, 70, index * 210, 1, "PLAINS"), siteStatus) },
        portals = portals,
    )

    private companion object {
        val WORLD_ID: UUID = UUID.fromString("cf898788-7a92-4a91-bb2f-d36b7c0f11f1")
        val SITE_IDS = listOf("ec:area_lumina_port", "ec:area_ashwood_forest", "ec:area_weeping_mine", "ec:area_stargazer_hill",
            "ec:area_void_sanctum", "ec:area_frostpeak_outpost", "ec:area_glacier_ridge", "ec:area_crystalline_cavern", "ec:area_glacial_citadel")
    }
}
