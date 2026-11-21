package net.nakaymi.etherealchronicle

import net.nakaymi.etherealchronicle.core.PlayerProfile
import net.nakaymi.etherealchronicle.core.TitleDefinition
import net.nakaymi.etherealchronicle.core.equipTitle
import net.nakaymi.etherealchronicle.data.PlayerStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*

class TitleSystemTest {
    @TempDir lateinit var directory:Path

    @Test fun `titles require unlock flags and selected title survives schema migration and save`() {
        val titles=mapOf("ec:title_truth_seeker" to TitleDefinition("ec:title_truth_seeker","真実を追う者","日記を調査した","ec:flag_title_truth_seeker"))
        val profile=PlayerProfile(UUID.randomUUID())
        assertFalse(profile.equipTitle("ec:title_truth_seeker",titles))
        assertNull(profile.selectedTitle)
        profile.flags += "ec:flag_title_truth_seeker"
        assertTrue(profile.equipTitle("ec:title_truth_seeker",titles))
        assertEquals("ec:title_truth_seeker",profile.selectedTitle)
        assertTrue(profile.equipTitle(null,titles))
        assertNull(profile.selectedTitle)
        profile.equipTitle("ec:title_truth_seeker",titles)

        PlayerStore(directory).use { store ->
        assertEquals(12,store.migrate(mapOf("schema_version" to 8))["schema_version"])
            store.save(profile).join()
            val loaded=store.load(profile.uuid).join()
            assertEquals("ec:title_truth_seeker",loaded.selectedTitle)
            assertEquals(profile.flags,loaded.flags)
        }
    }
}
