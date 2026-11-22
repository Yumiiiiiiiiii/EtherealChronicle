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
        val settings = ResourcePackSettings.from(config)
        assertEquals(5_000, settings.writeDeadlineMillis)
        assertEquals("game_port", settings.mode)
    }

    @Test
    fun `invalid pack settings disable only pack delivery`() {
        val config = YamlConfiguration()
        config.set("resource_pack.mode", "typo")
        var warned = false

        val settings = ResourcePackSettings.loadSafely(config) { warned = true }

        assertTrue(warned)
        assertFalse(settings.enabled)
        assertFalse(settings.required)
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
    fun `first join waits only when a required pack can be delivered`() {
        assertTrue(RequiredPackGatePolicy.shouldWait(true, true, "embedded_http", false))
        assertFalse(RequiredPackGatePolicy.shouldWait(true, false, "embedded_http", false))
        assertFalse(RequiredPackGatePolicy.shouldWait(true, true, "external_managed", false))
        assertFalse(RequiredPackGatePolicy.shouldWait(true, true, "embedded_http", true))
        assertFalse(RequiredPackGatePolicy.shouldWait(false, true, "embedded_http", false))
    }

    @Test
    fun `lock listener is only needed for an enforceable locally delivered pack`() {
        assertTrue(RequiredPackGatePolicy.shouldRegisterLockListener(true, true, "embedded_http"))
        assertTrue(RequiredPackGatePolicy.shouldRegisterLockListener(true, true, "game_port"))
        assertFalse(RequiredPackGatePolicy.shouldRegisterLockListener(false, true, "embedded_http"))
        assertFalse(RequiredPackGatePolicy.shouldRegisterLockListener(true, false, "embedded_http"))
        assertFalse(RequiredPackGatePolicy.shouldRegisterLockListener(true, true, "external_managed"))
    }

    @Test
    fun `required status decision table handles all states and bypass`() {
        val cases = listOf(
            org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.ACCEPTED to RequiredPackStatusAction.EXTEND,
            org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.DOWNLOADED to RequiredPackStatusAction.EXTEND,
            org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED to RequiredPackStatusAction.COMPLETE,
            org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.DECLINED to RequiredPackStatusAction.DISCONNECT,
            org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.FAILED_DOWNLOAD to RequiredPackStatusAction.DISCONNECT,
            org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.INVALID_URL to RequiredPackStatusAction.DISCONNECT,
            org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.FAILED_RELOAD to RequiredPackStatusAction.DISCONNECT,
            org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.DISCARDED to RequiredPackStatusAction.DISCONNECT,
        )
        cases.forEach { (eventStatus, expected) ->
            assertEquals(expected, RequiredPackGatePolicy.statusAction(eventStatus, true, false, true), eventStatus.name)
            assertEquals(
                RequiredPackStatusAction.COMPLETE,
                RequiredPackGatePolicy.statusAction(eventStatus, true, true, true),
                "bypass: ${eventStatus.name}",
            )
            assertEquals(
                if (eventStatus == org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED ||
                    eventStatus in RequiredPackGatePolicy.terminalStatuses
                ) {
                    RequiredPackStatusAction.COMPLETE
                } else {
                    RequiredPackStatusAction.IGNORE
                },
                RequiredPackGatePolicy.statusAction(eventStatus, false, false, true),
                "optional: ${eventStatus.name}",
            )
        }
        assertEquals(
            RequiredPackStatusAction.IGNORE,
            RequiredPackGatePolicy.statusAction(
                org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.ACCEPTED,
                true,
                false,
                false,
            ),
        )
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

    @Test
    fun `embedded endpoint rejects a loopback public fallback for a public bind`() {
        assertFalse(ResourcePackEndpointPolicy.canAdvertise("0.0.0.0", "127.0.0.1"))
        assertFalse(ResourcePackEndpointPolicy.canAdvertise("0.0.0.0", "localhost"))
        assertTrue(ResourcePackEndpointPolicy.canAdvertise("127.0.0.1", "127.0.0.1"))
        assertTrue(ResourcePackEndpointPolicy.canAdvertise("0.0.0.0", "packs.example.test"))
        assertFalse(ResourcePackEndpointPolicy.canAdvertise("0.0.0.0", "0.0.0.0"))
        assertFalse(ResourcePackEndpointPolicy.canAdvertise("0.0.0.0", "::ffff:127.0.0.1"))
        assertTrue(ResourcePackEndpointPolicy.canAdvertise("127.0.0.1", "localhost"))
    }

    @Test
    fun `turning pack off overrides an existing applied state`() {
        val off = ResourcePackPolicy.decide(
            configuredRequired = false,
            deliveryAvailable = true,
            preference = PackPreference.OFF,
            bypass = false,
        )

        assertFalse(off.send)
        assertFalse(off.required)
    }

    @Test
    fun `off transition removes an applied pack but preserves it when request remains active`() {
        assertTrue(shouldRemoveAppliedPack(wasLoaded = true, shouldSend = false))
        assertFalse(shouldRemoveAppliedPack(wasLoaded = false, shouldSend = false))
        assertFalse(shouldRemoveAppliedPack(wasLoaded = true, shouldSend = true))
    }

    @Test
    fun `external pack intermediate statuses preserve applied visuals`() {
        assertEquals(PackVisualTransition.LOADED, ManagedPackPolicy.visualTransition("SUCCESSFULLY_LOADED"))
        assertEquals(PackVisualTransition.KEEP, ManagedPackPolicy.visualTransition("ACCEPTED"))
        assertEquals(PackVisualTransition.KEEP, ManagedPackPolicy.visualTransition("DOWNLOADED"))
        assertEquals(PackVisualTransition.UNLOADED, ManagedPackPolicy.visualTransition("FAILED_RELOAD"))
        assertEquals(PackVisualTransition.UNLOADED, ManagedPackPolicy.visualTransition("DECLINED"))
    }

    @Test
    fun `required pack failure messages distinguish decline download and apply`() {
        assertEquals(
            "ec.resource_pack.required_declined",
            requiredFailureMessageKey(org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.DECLINED),
        )
        assertEquals(
            "ec.resource_pack.required_download_failed",
            requiredFailureMessageKey(org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.FAILED_DOWNLOAD),
        )
        assertEquals(
            "ec.resource_pack.required_download_failed",
            requiredFailureMessageKey(org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.INVALID_URL),
        )
        assertEquals(
            "ec.resource_pack.required_apply_failed",
            requiredFailureMessageKey(org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.FAILED_RELOAD),
        )
    }
}
