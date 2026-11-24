package net.nakaymi.etherealchronicle.core

import net.nakaymi.etherealchronicle.core.OverworldSite
import net.nakaymi.etherealchronicle.core.OverworldSiteLocator
import net.nakaymi.etherealchronicle.core.SiteTerrain
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** シード探索の再現性、フォールバック段階と地点間隔を固定します。 */
class OverworldSiteLocatorTest {
    @Test
    fun `water preference wins equally flat Lumina candidates`() {
        val search = OverworldSiteLocator.Search(25L)
        var preferred: Pair<Int, Int>? = null
        repeat(24) { index ->
            val point = search.nextCandidate()!!
            if (index == 10) preferred = point
            search.accept(SiteTerrain(70, "PLAINS", true, relief = 0, nearbyWater = index == 10))
        }
        assertEquals(preferred, search.selected().single().let { it.x to it.z })
    }

    @Test
    fun `peekCandidates previews upcoming points without mutating search state`() {
        val search = OverworldSiteLocator.Search(77L)
        val preview = search.peekCandidates(4)
        assertEquals(4, preview.size)
        assertEquals(preview, search.peekCandidates(4))
        assertTrue(search.peekCandidates(0).isEmpty())
        preview.forEach { expected ->
            assertEquals(expected, search.nextCandidate())
            search.accept(SiteTerrain(70, "PLAINS", true, relief = 0))
        }
        val nextPreview = search.peekCandidates(2)
        assertEquals(2, nextPreview.size)
        assertEquals(nextPreview.first(), search.nextCandidate())
    }

    @Test
    fun `multiple seeds remain deterministic with mixed regional biomes`() {
        for (seed in listOf(0L, 3L, 4815162342L)) {
            assertEquals(locateBySiteTerrain(seed), locateBySiteTerrain(seed))
        }
    }

    @Test
    fun `mixed terrain can select different fallback tiers in one layout`() {
        val search = OverworldSiteLocator.Search(33L)
        while (search.nextCandidate() != null) {
            val terrain = when (search.currentSiteId) {
                OverworldSiteIds.LUMINA_PORT -> SiteTerrain(70, "PLAINS", true, relief = 1)
                OverworldSiteIds.ASHWOOD_FOREST -> SiteTerrain(160, "DESERT", true)
                else -> SiteTerrain(170, "DESERT", true)
            }
            search.accept(terrain)
        }
        assertEquals(1, search.result()[0].tier)
        assertEquals(2, search.result()[1].tier)
        assertTrue(search.result().drop(2).all { it.tier == 3 })
    }

    @Test
    fun `tier one stops sampling after enough strong candidates`() {
        val search = OverworldSiteLocator.Search(123L)
        var samples = 0
        while (search.currentSiteId == "ec:area_lumina_port") {
            search.nextCandidate()
            search.accept(SiteTerrain(70, "PLAINS", dryLand = true, relief = 0, nearbyWater = true))
            samples++
        }
        assertEquals(24, samples)
    }

    @Test
    fun `search rejects out of order samples and incomplete result`() {
        val search = OverworldSiteLocator.Search(1L)
        assertThrows<IllegalStateException> { search.accept(SiteTerrain(70, "PLAINS", true)) }
        assertThrows<IllegalStateException> { search.result() }
        search.nextCandidate()
        assertThrows<IllegalStateException> { search.nextCandidate() }
    }

    @Test
    fun `height and relief boundaries select fallback tiers`() {
        assertTrue(locate(10L, SiteTerrain(145, "PLAINS", true, relief = 9)).all { it.tier == 2 })
        assertTrue(locate(11L, SiteTerrain(166, "PLAINS", true)).all { it.tier == 3 })
    }

    @Test
    fun `seed deterministically selects nine tier one sites with spacing and cold grouping`() {
        val first = locateBySiteTerrain(4815162342L)
        val second = locateBySiteTerrain(4815162342L)

        assertEquals(first, second)
        assertEquals(9, first.size)
        assertTrue(first.all { it.tier == 1 })
        assertEquals(9, first.map { it.id }.toSet().size)
        first.indices.forEach { left ->
            (left + 1 until first.size).forEach { right ->
                val a = first[left]
                val b = first[right]
                val distanceSquared = (a.x.toLong() - b.x) * (a.x - b.x) + (a.z.toLong() - b.z) * (a.z - b.z)
                assertTrue(distanceSquared >= 160L * 160, "${a.id} and ${b.id} are too close")
            }
        }
        val cold = first.filter { it.id in setOf("ec:area_frostpeak_outpost", "ec:area_glacier_ridge", "ec:area_crystalline_cavern") }
        cold.forEach { a -> cold.filter { it != a }.forEach { b ->
            val d2 = (a.x.toLong() - b.x) * (a.x - b.x) + (a.z.toLong() - b.z) * (a.z - b.z)
            assertTrue(d2 in 180L * 180..480L * 480, "cold sites must remain within the frost region")
        } }
    }

    @Test
    fun `tier two and three are selected only after stricter terrain rules fail`() {
        assertTrue(locate(7L, SiteTerrain(160, "DESERT", dryLand = true)).all { it.tier == 2 })
        assertTrue(locate(8L, SiteTerrain(170, "DESERT", dryLand = true)).all { it.tier == 3 })
    }

    @Test
    fun `all ocean candidates fail without returning a partial layout`() {
        val search = OverworldSiteLocator.Search(99L)
        assertThrows<IllegalStateException> {
            while (true) {
                if (search.nextCandidate() == null) break
                search.accept(SiteTerrain(70, "DEEP_OCEAN", dryLand = false))
            }
        }
    }

    @Test
    fun `non-lumina sites avoid sea-level beach in tier two and keep it only as tier three last resort`() {
        val search = OverworldSiteLocator.Search(8550623988298585625L)
        var citadelSamples = 0
        while (search.nextCandidate() != null) {
            val siteId = search.currentSiteId
            val terrain = when (siteId) {
                OverworldSiteIds.GLACIAL_CITADEL -> {
                    citadelSamples++
                    if (citadelSamples % 2 == 0) SiteTerrain(67, "FOREST", dryLand = true, relief = 4, nearbyWater = false)
                    else SiteTerrain(62, "BEACH", dryLand = true, relief = 0, nearbyWater = true)
                }
                else -> SiteTerrain(70, preferredBiome(siteId), dryLand = true, relief = 1)
            }
            search.accept(terrain)
        }
        val citadel = search.result().single { it.id == OverworldSiteIds.GLACIAL_CITADEL }
        assertEquals(2, citadel.tier, "Inland y=67 FOREST should win in Tier 2 over y=62 BEACH")
        assertEquals("FOREST", citadel.biome)
        assertEquals(67, citadel.y)

        // 全候補が y=62 BEACH のみの極端な多島海シードでは Tier 3 で最終フォールバックできることを確認します。
        val beachOnly = OverworldSiteLocator.Search(8550623988298585625L)
        while (beachOnly.nextCandidate() != null) {
            val siteId = beachOnly.currentSiteId
            val terrain = if (siteId == OverworldSiteIds.GLACIAL_CITADEL) {
                SiteTerrain(62, "BEACH", dryLand = true, relief = 0, nearbyWater = true)
            } else {
                SiteTerrain(70, preferredBiome(siteId), dryLand = true, relief = 1)
            }
            beachOnly.accept(terrain)
        }
        val fallbackCitadel = beachOnly.result().single { it.id == OverworldSiteIds.GLACIAL_CITADEL }
        assertEquals(3, fallbackCitadel.tier)
        assertEquals("BEACH", fallbackCitadel.biome)
    }

    @Test
    fun `lumina prefers inland plains over flat beach and does not early-stop on beach`() {
        val search = OverworldSiteLocator.Search(4528502914240555028L)
        var index = 0
        while (search.currentSiteId == OverworldSiteIds.LUMINA_PORT) {
            search.nextCandidate()
            // 先頭 40 候補が全て BEACH でも早期打ち切りせず、後続の PLAINS を採用します。
            val terrain = if (index < 40) {
                SiteTerrain(64, "BEACH", dryLand = true, relief = 0, nearbyWater = true)
            } else {
                SiteTerrain(68, "PLAINS", dryLand = true, relief = 2, nearbyWater = false)
            }
            search.accept(terrain)
            index++
        }
        val lumina = search.selected().single()
        assertEquals(1, lumina.tier)
        assertEquals("PLAINS", lumina.biome)
    }

    private fun preferredBiome(siteId: String): String = when (siteId) {
        OverworldSiteIds.LUMINA_PORT -> "PLAINS"
        OverworldSiteIds.ASHWOOD_FOREST -> "FOREST"
        OverworldSiteIds.WEEPING_MINE -> "WINDSWEPT_HILLS"
        OverworldSiteIds.STARGAZER_HILL -> "MEADOW"
        OverworldSiteIds.VOID_SANCTUM -> "DARK_FOREST"
        else -> "SNOWY_PLAINS"
    }

    private fun locate(seed: Long, terrain: SiteTerrain): List<OverworldSite> {
        val search = OverworldSiteLocator.Search(seed)
        while (search.nextCandidate() != null) search.accept(terrain)
        return search.result()
    }

    private fun locateBySiteTerrain(seed: Long): List<OverworldSite> {
        val search = OverworldSiteLocator.Search(seed)
        val biomeBySite = mapOf(
            "ec:area_lumina_port" to "PLAINS", "ec:area_ashwood_forest" to "FOREST",
            "ec:area_weeping_mine" to "WINDSWEPT_HILLS", "ec:area_stargazer_hill" to "MEADOW",
            "ec:area_void_sanctum" to "DARK_FOREST", "ec:area_frostpeak_outpost" to "SNOWY_PLAINS",
            "ec:area_glacier_ridge" to "SNOWY_PLAINS", "ec:area_crystalline_cavern" to "SNOWY_PLAINS",
            "ec:area_glacial_citadel" to "SNOWY_PLAINS",
        )
        while (search.nextCandidate() != null) {
            search.accept(SiteTerrain(70, biomeBySite.getValue(search.currentSiteId), dryLand = true, relief = 2))
        }
        return search.result()
    }
}
