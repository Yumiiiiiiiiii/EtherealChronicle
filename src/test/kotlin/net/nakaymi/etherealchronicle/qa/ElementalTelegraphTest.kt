package net.nakaymi.etherealchronicle.qa

import net.nakaymi.etherealchronicle.paper.EnemyController
import net.nakaymi.etherealchronicle.paper.RpgPlugin
import org.bukkit.Color
import org.bukkit.potion.PotionEffectType
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.PlayerMock
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ElementalTelegraphTest {
    private fun ServerMock.pump(timeoutMs: Long = 5000,cond: () -> Boolean): Boolean {
        val end=System.currentTimeMillis()+timeoutMs
        while(System.currentTimeMillis()<end) {
            scheduler.performOneTick()
            if(cond()) return true
            Thread.sleep(20)
        }
        return cond()
    }

    @Test
    fun `telegraphs use elemental palette and ice prison applies short root freeze`() {
        assertEquals(Color.fromRGB(150,235,255),EnemyController.telegraphColor("ICE"))
        assertEquals(Color.fromRGB(185,90,255),EnemyController.telegraphColor("VOID"))
        assertNotEquals(EnemyController.telegraphColor("ICE"),EnemyController.telegraphColor("VOID"))
        val server=MockBukkit.mock()
        try {
            val plugin=MockBukkit.load(RpgPlugin::class.java)
            assertTrue(server.pump { plugin.isReadyForGameplay })
            val player=PlayerMock(server,"Frozen",UUID.randomUUID())
            server.addPlayer(player)
            val prison=plugin.snapshot.world.enemies.getValue("ec:boss_cryo_archon").specials.single { it.id=="cryo_prison" }
            assertEquals("ICE",prison.element)
            assertEquals("FREEZE",prison.effect)
            plugin.enemies.applySpecialEffect(player,prison)
            assertEquals(60,player.freezeTicks)
            val slow=player.getPotionEffect(PotionEffectType.SLOWNESS)
            assertTrue(slow != null && slow.amplifier == 10 && slow.duration == 60,"freeze must briefly root the target")
        } finally { MockBukkit.unmock() }
    }
}
