package net.nakaymi.etherealchronicle.qa

import net.nakaymi.etherealchronicle.paper.RpgPlugin
import org.bukkit.potion.PotionEffectType
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.entity.PlayerMock
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** ICE telegraph metadata and the FREEZE special effect (applySpecialEffect) on the shipped Cryo Archon data. */
class IceFreezeTest {
    @Test
    fun freezeEffectAppliesFreezeTicksAndSlowAndOtherEffectsDoNot() {
        val server = MockBukkit.mock()
        try {
            val plugin = MockBukkit.load(RpgPlugin::class.java)
            val end = System.currentTimeMillis() + 5000
            while (runCatching { plugin.snapshot; plugin.enemies }.isFailure && System.currentTimeMillis() < end) { server.scheduler.performOneTick(); Thread.sleep(20) }
            val archon = plugin.snapshot.world.enemies.getValue("ec:boss_cryo_archon")
            val specials = archon.specials
            val freeze = specials.firstOrNull { it.effect == "FREEZE" } ?: error("Cryo Archon has no FREEZE special")
            assertTrue(freeze.telegraph > 0, "FREEZE special has no telegraph")
            assertTrue(freeze.element == "ICE" || freeze.element == null, "unexpected element ${freeze.element}")
            val controller = plugin.enemies
            val p = PlayerMock(server, "IceA", UUID.nameUUIDFromBytes("ice-a".toByteArray())); server.addPlayer(p)
            assertEquals(0, p.freezeTicks)
            controller.applySpecialEffect(p, freeze)
            assertTrue(p.freezeTicks >= freeze.effectDuration && freeze.effectDuration > 0, "freezeTicks=${p.freezeTicks} expected >= ${freeze.effectDuration}")
            val slow = p.getPotionEffect(PotionEffectType.SLOWNESS)
            assertTrue(slow != null && slow.amplifier >= 1, "FREEZE should also root/slow the player: $slow")
            // a non-freeze special must not freeze
            val q = PlayerMock(server, "IceB", UUID.nameUUIDFromBytes("ice-b".toByteArray())); server.addPlayer(q)
            val plain = specials.first { it.effect != "FREEZE" }
            controller.applySpecialEffect(q, plain)
            assertEquals(0, q.freezeTicks, "non-FREEZE special '${plain.effect}' froze the player")
        } finally { MockBukkit.unmock() }
    }
}
