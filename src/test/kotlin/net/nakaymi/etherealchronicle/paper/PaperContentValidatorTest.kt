package net.nakaymi.etherealchronicle.paper

import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import kotlin.test.assertFailsWith

/** 実行時に使うPaper列挙名を起動前に検証する共通境界を確認します。 */
class PaperContentValidatorTest {
    @Test
    fun `invalid material particle and entity names fail validation`() {
        MockBukkit.mock()
        try {
            assertFailsWith<IllegalArgumentException> { PaperContentValidator.itemMaterial("NOT_A_MATERIAL") }
            assertFailsWith<IllegalArgumentException> { PaperContentValidator.particle("NOT_A_PARTICLE") }
            assertFailsWith<IllegalArgumentException> { PaperContentValidator.livingEntity("NOT_AN_ENTITY") }
            assertFailsWith<IllegalArgumentException> { PaperContentValidator.wolfVariant("not a key", "WOLF") }
            assertFailsWith<IllegalArgumentException> { PaperContentValidator.wolfVariant("ec:ashwood_alpha", "ZOMBIE") }
            assertFailsWith<IllegalArgumentException> { PaperContentValidator.dungeonPalette(listOf("STONE", "DIRT")) }
        } finally { MockBukkit.unmock() }
    }

    @Test
    fun `valid content enum values are accepted`() {
        MockBukkit.mock()
        try {
            PaperContentValidator.itemMaterial("DIAMOND")
            PaperContentValidator.blockMaterial("STONE")
            PaperContentValidator.particle("END_ROD")
            PaperContentValidator.livingEntity("ZOMBIE")
            PaperContentValidator.wolfVariant("ec:ashwood_alpha", "WOLF")
            PaperContentValidator.dungeonPalette(listOf("STONE", "DIRT", "DEEPSLATE"))
        } finally { MockBukkit.unmock() }
    }
}
