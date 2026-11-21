package net.nakaymi.etherealchronicle.paper

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** 必須パックの既定値、配信不能時の安全解除、拒否と運用bypassを検証します。 */
class ResourcePackRequiredPolicyTest {
    @Test
    fun `shipped configuration requires packs but keeps stable gui theme`() {
        val config=YamlConfiguration.loadConfiguration(javaClass.classLoader.getResourceAsStream("config.yml")!!.bufferedReader())
        val plugin=YamlConfiguration.loadConfiguration(javaClass.classLoader.getResourceAsStream("plugin.yml")!!.bufferedReader())

        assertTrue(config.getBoolean("resource_pack.required"))
        assertEquals(120, config.getInt("resource_pack.required_timeout_seconds"))
        assertEquals("icons_only",config.getString("ui.theme.mode"))
        assertEquals("op",plugin.getString("permissions.etherealchronicle.pack.bypass.default"))
    }

    @Test
    fun `required request overrides off preference and disconnects after rejection`() {
        val decision=ResourcePackPolicy.decide(
            configuredRequired=true,
            deliveryAvailable=true,
            preference=PackPreference.OFF,
            bypass=false,
        )

        assertTrue(decision.send)
        assertTrue(decision.required)
        assertTrue(ResourcePackPolicy.disconnectOnFailure(decision.disconnectOnFailure,true))
        assertFalse(ResourcePackPolicy.disconnectOnFailure(decision.disconnectOnFailure,false))
    }

    @Test
    fun `missing distribution disables enforcement without locking out players`() {
        val decision=ResourcePackPolicy.decide(
            configuredRequired=true,
            deliveryAvailable=false,
            preference=PackPreference.AUTO,
            bypass=false,
        )

        assertFalse(decision.send)
        assertFalse(decision.required)
        assertFalse(ResourcePackPolicy.disconnectOnFailure(decision.disconnectOnFailure,true))
    }

    @Test
    fun `external delivery requires its configured sha before it can be required`() {
        assertFalse(ResourcePackPolicy.deliveryAvailable(true,true,"external_url",""))
        assertFalse(ResourcePackPolicy.deliveryAvailable(true,true,"external_url","bad-hash"))
        assertTrue(ResourcePackPolicy.deliveryAvailable(true,true,"external_url","a".repeat(40)))
        assertTrue(ResourcePackPolicy.deliveryAvailable(true,true,"game_port",""))
        assertFalse(ResourcePackPolicy.deliveryAvailable(true,false,"game_port",""))
    }

    @Test
    fun `op bypass can decline while optional requests remain available`() {
        val bypass=ResourcePackPolicy.decide(true,true,PackPreference.AUTO,bypass=true)
        val bypassOff=ResourcePackPolicy.decide(true,true,PackPreference.OFF,bypass=true)

        assertTrue(bypass.send)
        assertFalse(bypass.required)
        assertTrue(bypassOff.send.not())
        assertFalse(bypassOff.required)
        assertFalse(ResourcePackPolicy.disconnectOnFailure(bypass.required,true))
    }

    @Test
    fun `required pack gate blocks gameplay until success and releases exactly once`() {
        val gate = RequiredPackGate()
        val player = java.util.UUID.randomUUID()

        assertTrue(gate.begin(player))
        assertTrue(gate.isWaiting(player))
        assertFalse(gate.begin(player))
        assertTrue(gate.release(player))
        assertFalse(gate.isWaiting(player))
        assertFalse(gate.release(player))
    }

    @Test
    fun `first join waits only when a required pack can be delivered`() {
        assertTrue(RequiredPackGatePolicy.shouldWait(true, true, "embedded_http", false))
        assertFalse(RequiredPackGatePolicy.shouldWait(true, false, "embedded_http", false))
        assertFalse(RequiredPackGatePolicy.shouldWait(true, true, "external_managed", false))
        assertFalse(RequiredPackGatePolicy.shouldWait(true, true, "embedded_http", true))
        assertFalse(RequiredPackGatePolicy.shouldWait(false, true, "embedded_http", false))
    }

    @Test
    fun `required timeout seconds are bounded and converted to ticks`() {
        assertEquals(2_400L, RequiredPackGatePolicy.timeoutTicks(120))
        assertEquals(200L, RequiredPackGatePolicy.timeoutTicks(0))
        assertEquals(12_000L, RequiredPackGatePolicy.timeoutTicks(900))
    }

    @Test
    fun `changed bundled hashes are advertised on the next request`() {
        val first=advertisedPackSha1("","a".repeat(40))
        val next=advertisedPackSha1("","b".repeat(40))
        assertNotEquals(first,next)
        assertEquals("c".repeat(40),advertisedPackSha1("c".repeat(40),"a".repeat(40)))
    }
}
