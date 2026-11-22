package net.nakaymi.etherealchronicle

import net.nakaymi.etherealchronicle.core.GameException
import net.nakaymi.etherealchronicle.core.requireGame
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** 翻訳キーを例外メッセージの解析なしで扱えることを確認します。 */
class GameExceptionTest {
    @Test
    fun `failed game requirement retains a typed key and arguments`() {
        val failure = assertFailsWith<GameException> { requireGame(false, "ec.error.currency_low") }

        assertEquals("ec.error.currency_low", failure.messageKey)
        assertEquals("ec.error.currency_low", failure.message)
        assertEquals(emptyMap(), failure.arguments)
    }
}
