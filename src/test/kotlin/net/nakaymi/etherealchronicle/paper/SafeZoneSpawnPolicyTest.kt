package net.nakaymi.etherealchronicle.paper

import net.nakaymi.etherealchronicle.data.ContentService
import net.nakaymi.etherealchronicle.core.Position
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SafeZoneSpawnPolicyTest {
    private val areas=ContentService(Path.of("src/main/resources/content")).load().world.areas.values
    @Test fun `natural hostile spawns are blocked only inside safe zones and custom RPG spawns pass`() {
        assertTrue(shouldSuppressVanillaHostileSpawn("NATURAL",true,Position("ec_world",0.0,65.0,0.0),areas))
        assertTrue(shouldSuppressVanillaHostileSpawn("CHUNK_GEN",true,Position("ec_world",480.0,65.0,0.0),areas))
        assertFalse(shouldSuppressVanillaHostileSpawn("NATURAL",true,Position("ec_world",80.0,65.0,0.0),areas))
        assertFalse(shouldSuppressVanillaHostileSpawn("CUSTOM",true,Position("ec_world",0.0,65.0,0.0),areas))
        assertFalse(shouldSuppressVanillaHostileSpawn("NATURAL",false,Position("ec_world",0.0,65.0,0.0),areas))
    }
    @Test fun `class HUD yields action bar to gathering and fishing sessions`() {
        assertTrue(shouldSendClassHud(false,false))
        assertFalse(shouldSendClassHud(true,false))
        assertFalse(shouldSendClassHud(false,true))
        assertFalse(shouldSendClassHud(true,true))
    }
}
