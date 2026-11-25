package net.nakaymi.etherealchronicle.qa

import net.nakaymi.etherealchronicle.core.WorldIds
import net.nakaymi.etherealchronicle.core.PlayerProfile
import net.nakaymi.etherealchronicle.core.recordChallenge
import net.nakaymi.etherealchronicle.paper.RpgPlugin
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.PlayerMock
import java.util.UUID
import kotlin.test.*

class ChallengeDungeonTest {
    private fun ServerMock.pump(timeout:Long=5000,ready:()->Boolean):Boolean {
        val until=System.currentTimeMillis()+timeout
        while(System.currentTimeMillis()<until) { scheduler.performOneTick(); if(ready()) return true; Thread.sleep(20) }
        return ready()
    }
    @Test
    fun `crucible advances through three waves records time rank and personal best`() {
        val server=MockBukkit.mock()
        try {
            val plugin=MockBukkit.load(RpgPlugin::class.java)
            assertTrue(server.pump { runCatching { plugin.snapshot.world.dungeons.isNotEmpty() }.getOrDefault(false) })
            val player=PlayerMock(server,"Crucible",UUID.randomUUID()); server.addPlayer(player)
            assertTrue(server.pump { player.uniqueId in plugin.profiles })
            val world=QaWorldMock.add(server, WorldIds.DEMO)
            player.teleport(world.spawnLocation)
            val profile=PlayerProfile(player.uniqueId,classId="ec:class_paladin",level=20,resource=100.0,flags=mutableSetOf("ec:flag_side_crucible_unlocked"))
            plugin.profiles[player.uniqueId]=profile

            plugin.dungeons.enter(player,"ec:dungeon_aether_crucible")
            assertTrue(server.qaPump { plugin.dungeons.currentWave(0) == 0 }, "crucible instance did not start")
            fun defeatCurrent(expected:String) {
                assertTrue(server.qaPump { plugin.dungeons.currentBoss(0) != null }, "wave boss missing")
                val bossId=plugin.dungeons.currentBoss(0)!!
                assertEquals(expected,plugin.enemies.state(bossId)?.boss?.definition?.id)
                plugin.dungeons.defeated(bossId)
            }
            defeatCurrent("ec:enemy_void_sentinel")
            assertTrue(server.qaPump { plugin.dungeons.currentWave(0) == 1 })
            defeatCurrent("ec:enemy_glacial_knight")
            assertTrue(server.qaPump { plugin.dungeons.currentWave(0) == 2 })
            defeatCurrent("ec:boss_aether_chimera")
            val record=profile.challengeRecords.getValue("ec:dungeon_aether_crucible")
            assertEquals("S",record.bestRank)
            val oldSeconds=record.bestSeconds

            // A slower clear reports its rank but cannot replace the saved best.
            val (best,updated)=profile.recordChallenge("ec:dungeon_aether_crucible",oldSeconds+200,"A")
            assertFalse(updated)
            assertEquals("S",best.bestRank)
            assertTrue(profile.recordChallenge("ec:dungeon_aether_crucible",oldSeconds,"S").second.not())
            assertEquals(oldSeconds,profile.challengeRecords.getValue("ec:dungeon_aether_crucible").bestSeconds)
            plugin.persist(profile).join()
            "3 waves cleared; ${record.bestSeconds}s rank ${record.bestRank} persisted"
        } finally { MockBukkit.unmock() }
    }
}
