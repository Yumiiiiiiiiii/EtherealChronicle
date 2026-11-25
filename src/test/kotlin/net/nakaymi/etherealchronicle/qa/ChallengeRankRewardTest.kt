package net.nakaymi.etherealchronicle.qa

import net.nakaymi.etherealchronicle.core.*
import net.nakaymi.etherealchronicle.data.*
import net.nakaymi.etherealchronicle.paper.RpgPlugin
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.PlayerMock
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*

class ChallengeRankRewardTest {
    private fun ServerMock.pump(timeout:Long=5000,ready:()->Boolean):Boolean {
        val until=System.currentTimeMillis()+timeout
        while(System.currentTimeMillis()<until) { scheduler.performOneTick(); if(ready()) return true; Thread.sleep(20) }
        return ready()
    }

    @Test fun `first rank reward is delivered once through the normal durable transaction`() {
        val server=MockBukkit.mock()
        try {
            val plugin=MockBukkit.load(RpgPlugin::class.java)
            assertTrue(server.pump { runCatching { plugin.snapshot.world.dungeons.isNotEmpty() }.getOrDefault(false) })
            val player=PlayerMock(server,"RankReward",UUID.randomUUID()); server.addPlayer(player)
            assertTrue(server.pump { player.uniqueId in plugin.profiles })
            val profile=PlayerProfile(player.uniqueId,classId="ec:class_paladin",level=20,resource=100.0,flags=mutableSetOf("ec:flag_side_crucible_unlocked"))
            plugin.profiles[player.uniqueId]=profile
            val base=plugin.snapshot
            val chapterId="ec:chapter_side_chronicles"
            val chapter=base.documents.getValue(chapterId)
            val configs=chapter.maps("dungeon_configs").map { config -> if(config.string("id")!="ec:dungeon_aether_crucible") config else {
                val ranks=config.maps("score_ranks").map { rank -> if(rank.string("rank")!="S") rank else rank+mapOf("first_clear_flag" to "ec:flag_crucible_first_s","first_clear_items" to listOf(mapOf("item" to "ec:item_mat_aether_gel","amount" to 2))) }
                config+mapOf("score_ranks" to ranks)
            } }
            val changed=chapter+mapOf("dungeon_configs" to configs,"flags" to (chapter.strings("flags")+"ec:flag_crucible_first_s"))
            val service=RpgPlugin::class.java.getDeclaredField("content").also { it.isAccessible=true }.get(plugin) as ContentService
            service.install(base.copy(documents=base.documents+mapOf(chapterId to changed)))
            val world=net.nakaymi.etherealchronicle.qa.QaWorldMock.add(server, net.nakaymi.etherealchronicle.core.WorldIds.DEMO)
            player.teleport(world.spawnLocation)
            plugin.dungeons.enter(player,"ec:dungeon_aether_crucible")
            repeat(3) { wave ->
                assertTrue(server.qaPump { plugin.dungeons.currentBoss(0) != null }, "missing wave boss $wave")
                val boss=plugin.dungeons.currentBoss(0)!!
                plugin.dungeons.defeated(boss)
            }
            assertTrue(server.pump { "ec:flag_crucible_first_s" in profile.flags && player.inventory.storageContents.filterNotNull().filter { plugin.identify(it)=="ec:item_mat_aether_gel" }.sumOf { it.amount }>=2 })
            assertEquals(2,player.inventory.storageContents.filterNotNull().filter { plugin.identify(it)=="ec:item_mat_aether_gel" }.sumOf { it.amount })
            assertTrue(profile.challengeRecords.containsKey("ec:dungeon_aether_crucible"))
        } finally { MockBukkit.unmock() }
    }
}
