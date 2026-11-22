package net.nakaymi.etherealchronicle

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

    private fun locate(seed: Long, terrain: SiteTerrain): List<OverworldSite> {
        val search = OverworldSiteLocator.Search(seed)
        while (search.nextCandidate() != null) search.accept(terrain)
        return search.result()
    }

    private fun locateBySiteTerrain(seed: Long): List<OverworldSite> {
        val preferred = listOf("PLAINS", "FOREST", "WINDSWEPT_HILLS", "MEADOW", "DARK_FOREST", "SNOWY_PLAINS", "SNOWY_PLAINS", "SNOWY_PLAINS", "SNOWY_PLAINS")
        val search = OverworldSiteLocator.Search(seed)
        var candidateIndex = 0
        while (search.nextCandidate() != null) {
            search.accept(SiteTerrain(70, preferred[candidateIndex / 256], dryLand = true, relief = 2))
            candidateIndex++
        }
        return search.result()
    }
}
