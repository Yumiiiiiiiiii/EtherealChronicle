package net.nakaymi.etherealchronicle.paper

import net.nakaymi.etherealchronicle.data.ContentService
import org.bukkit.NamespacedKey
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Path

class WolfVariantResolutionTest {
    @Test
    fun `authored custom wolf ids map to the exact registry keys`() {
        val content = ContentService(Path.of("src/main/resources/content")).load()
        val expected = mapOf(
            "ec:enemy_thorn_wolf" to "ec:thorn_wolf",
            "ec:enemy_frost_wolf" to "ec:frost_wolf",
            "ec:boss_ashwood_alpha" to "ec:ashwood_alpha",
        )

        expected.forEach { (enemyId, variantId) ->
            assertEquals(variantId, content.world.enemies[enemyId]?.visualVariant, enemyId)
            assertEquals(NamespacedKey.fromString(variantId), EnemyController.wolfVariantKey(variantId))
        }
        assertNull(EnemyController.wolfVariantKey("not a key"))
    }
}
