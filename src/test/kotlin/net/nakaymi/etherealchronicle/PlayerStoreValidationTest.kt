package net.nakaymi.etherealchronicle

import net.nakaymi.etherealchronicle.data.boundedInt
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** 保存YAMLの整数範囲違反を該当field名つきで報告することを確認します。 */
class PlayerStoreValidationTest {
    @Test
    fun `bounded int reports invalid field names`() {
        assertEquals(0, null.boundedInt(0..5, "enhancement", 0))
        assertEquals(5, 5L.boundedInt(0..5, "enhancement"))
        assertEquals("Out-of-range integer field: enhancement", assertFailsWith<IllegalArgumentException> {
            6.boundedInt(0..5, "enhancement")
        }.message)
    }
}
