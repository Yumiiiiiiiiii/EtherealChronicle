package net.nakaymi.etherealchronicle.paper

import net.nakaymi.etherealchronicle.core.PlayerProfile
import net.nakaymi.etherealchronicle.data.PlayerStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 外部プラグイン配信時の二重送信防止、状態絞り込み、プロフィール移行を検証します。 */
class ExternalManagedPackTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `external managed mode only observes status and never requests a second pack`() {
        assertFalse(ManagedPackPolicy.sendsPack("external_managed"))
        assertTrue(ManagedPackPolicy.sendsPack("external_url"))
        assertTrue(ManagedPackPolicy.isLoaded("SUCCESSFULLY_LOADED"))
        assertFalse(ManagedPackPolicy.isLoaded("ACCEPTED"))
        assertFalse(ManagedPackPolicy.disconnectsOnFailure("external_managed", true, true))
    }

    @Test
    fun `optional pack id filters status events`() {
        val expected = UUID.randomUUID()
        assertTrue(ManagedPackPolicy.matches(null, UUID.randomUUID()))
        assertTrue(ManagedPackPolicy.matches(expected, expected))
        assertFalse(ManagedPackPolicy.matches(expected, UUID.randomUUID()))
    }

    @Test
    fun `accept and decline status changes are recorded once`() {
        val profile = PlayerProfile(UUID.randomUUID())
        assertTrue(ManagedPackPolicy.recordStatus(profile, "ACCEPTED"))
        assertEquals("ACCEPTED", profile.resourcePackStatus)
        assertFalse(ManagedPackPolicy.recordStatus(profile, "ACCEPTED"))
        assertTrue(ManagedPackPolicy.recordStatus(profile, "DECLINED"))
        assertEquals("DECLINED", profile.resourcePackStatus)
    }

    @Test
    fun `schema 12 gains a default status and status is saved`() {
        PlayerStore(directory).use { store ->
            val migrated = store.migrate(mapOf("schema_version" to 12, "resource_pack_preference" to "OFF"))
            assertEquals(13, migrated["schema_version"])
            assertEquals("OFF", migrated["resource_pack_preference"])
            assertEquals("UNKNOWN", migrated["resource_pack_status"])

            val profile = PlayerProfile(UUID.randomUUID(), resourcePackStatus = "DECLINED")
            store.save(profile).join()
            assertEquals("DECLINED", store.load(profile.uuid).join().resourcePackStatus)
        }
    }
}
