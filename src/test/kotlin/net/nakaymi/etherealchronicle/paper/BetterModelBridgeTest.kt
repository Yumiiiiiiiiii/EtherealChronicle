package net.nakaymi.etherealchronicle.paper

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BetterModelBridgeTest {
    @Test
    fun `optional model mappings accept only stable content ids and safe names`() {
        val mappings = BetterModelBridge.configuredModels(
            mapOf(
                "ec:enemy_thorn_wolf" to "thorn_wolf",
                "ec:boss_ashwood_alpha" to "forest.alpha-01",
                "not-an-id" to "ignored",
                "ec:enemy_slime" to "../unsafe",
                "ec:enemy_empty" to null,
            ),
        )

        assertEquals(
            mapOf("ec:enemy_thorn_wolf" to "thorn_wolf", "ec:boss_ashwood_alpha" to "forest.alpha-01"),
            mappings,
        )
    }
}
