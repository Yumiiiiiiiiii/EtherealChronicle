package net.nakaymi.etherealchronicle

import net.nakaymi.etherealchronicle.data.PlayerStore
import net.nakaymi.etherealchronicle.data.boundedInt
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.UUID
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

    @Test
    fun `stored skill levels above the former hardcoded cap load successfully`() {
        val directory = Files.createTempDirectory("ec-player-store-skill-cap")
        val uuid = UUID.randomUUID()
        PlayerStore(directory).use { store ->
            val profile = net.nakaymi.etherealchronicle.core.PlayerProfile(uuid)
            profile.skillLevels["ec:skill_test"] = 150

            store.save(profile).get()
            assertEquals(150, store.load(uuid).get().skillLevels["ec:skill_test"])
        }
    }
}
