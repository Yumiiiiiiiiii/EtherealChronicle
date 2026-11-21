package net.nakaymi.etherealchronicle

import net.nakaymi.etherealchronicle.core.*
import net.nakaymi.etherealchronicle.data.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*

class EarlyP1WorldTest {
    @TempDir lateinit var temp:Path
    private fun bundled()=ContentService(Path.of("src/main/resources/content")).load()

    @Test fun `schedule conditions support day night dusk and exact weather`() {
        assertTrue(WorldSchedule.matches(setOf("NIGHT"),setOf("RAIN"),18000,true,false))
        assertFalse(WorldSchedule.matches(setOf("NIGHT"),setOf("THUNDER"),18000,true,false))
        assertTrue(WorldSchedule.matches(setOf("DAWN_DUSK"),setOf("ANY"),23500,false,false))
        assertFalse(WorldSchedule.matches(setOf("DAWN_DUSK"),setOf("ANY"),6000,false,false))
        assertTrue(WorldSchedule.matches(setOf("ANY"),setOf("THUNDER"),6000,true,true))
        assertFalse(WorldSchedule.valid(setOf("MIDNIGHT"),setOf("CLEAR")))
    }

    @Test fun `NPC enemy and fishing spot schedules parse with defaults and reject invalid values`() {
        val base=bundled()
        val npcId=base.documents.values.first { it["type"]=="npc" }.string("id")
        val npc=base.documents.getValue(npcId)+mapOf("time_windows" to listOf("NIGHT"),"weather" to listOf("RAIN","THUNDER"))
        val spotId=base.documents.values.first { it["type"]=="fishing_spot" }.string("id")
        val spot=base.documents.getValue(spotId)+mapOf("priority" to 25,"unlock_flag" to "ec:flag_early_board_registered")
        val enemyId=base.documents.values.first { it["type"]=="enemy" && it["spawn_points"] is List<*> }.string("id")
        val enemy=base.documents.getValue(enemyId)+mapOf("spawn_points" to listOf(mapOf("id" to "night_rain","world" to "ec_world","x" to 10,"y" to 65,"z" to 10,"time_windows" to listOf("NIGHT"),"weather" to listOf("RAIN"))))
        val changed=base.copy(documents=base.documents+mapOf(npcId to npc,spotId to spot,enemyId to enemy)).world
        assertEquals(setOf("NIGHT"),changed.npcs.getValue(npcId).timeWindows)
        assertEquals(setOf("RAIN","THUNDER"),changed.npcs.getValue(npcId).weather)
        assertEquals(25,changed.fishingSpots.getValue(spotId).priority)
        assertEquals("ec:flag_early_board_registered",changed.fishingSpots.getValue(spotId).unlockFlag)
        assertEquals(setOf("NIGHT"),changed.enemies.getValue(enemyId).spawns.single().timeWindows)
        assertFails { base.copy(documents=base.documents+mapOf(npcId to npc+mapOf("time_windows" to listOf("MIDNIGHT")))).world }
    }

    @Test fun `fishing spot selection honors flags then priority radius and stable id`() {
        val base=FishingSpotDefinition("ec:spot_public","Public","ec:area_lumina_port","ec_world",Position("ec_world",10.0,64.0,10.0),20.0,true,setOf("PORT"))
        val secret=base.copy(id="ec:spot_secret",priority=5,unlockFlag="ec:flag_secret")
        val compact=base.copy(id="ec:spot_compact",priority=5,radius=4.0)
        assertEquals(base,selectFishingSpot(listOf(secret,base),"ec_world",10.0,10.0,emptySet()))
        assertEquals(secret,selectFishingSpot(listOf(base,secret),"ec_world",10.0,10.0,setOf("ec:flag_secret")))
        assertEquals(compact,selectFishingSpot(listOf(base,compact),"ec_world",10.0,10.0,emptySet()))
        assertNull(selectFishingSpot(listOf(secret),"ec_world",10.0,10.0,emptySet()))
        val hidden=bundled().world.fishingSpots.getValue("ec:spot_luna_hidden_spring")
        assertEquals(25,hidden.priority)
        assertEquals("ec:flag_early_luna_found",hidden.unlockFlag)
        assertTrue(selectFishingSpot(listOf(hidden),"ec_world",124.5,-23.5,emptySet())==null)
        assertEquals(hidden,selectFishingSpot(listOf(hidden),"ec_world",124.5,-23.5,setOf("ec:flag_early_luna_found")))
    }

    @Test fun `treasure claim stages one durable reward and is idempotent`() {
        val p=PlayerProfile(UUID.randomUUID(),currency=30)
        val chest=TreasureChestDefinition("ec:chest_test",Position("ec_world",2.0,65.0,2.0),"ec:flag_chest_test",mapOf("ec:item_mat_aether_gel" to 2),50,"END_ROD")
        assertTrue(claimTreasure(p,chest,100))
        assertEquals(80,p.currency)
        assertEquals(2,p.pendingRewards["ec:item_mat_aether_gel"])
        assertFalse(claimTreasure(p,chest,100))
        assertEquals(80,p.currency)
        assertEquals(2,p.pendingRewards["ec:item_mat_aether_gel"])
        assertFailsWith<IllegalArgumentException> { claimTreasure(PlayerProfile(UUID.randomUUID(),currency=60),chest,100) }
    }

    @Test fun `treasure schema checks managed area item reference and reward ranges`() {
        val base=bundled()
        val chest=mapOf("schema_version" to 1,"id" to "ec:treasure_test","type" to "treasure_chest",
            "location" to mapOf("world" to "ec_world","x" to 10.0,"y" to 65.0,"z" to 28.0),
            "once_flag" to "ec:flag_early_board_registered","items" to mapOf("ec:item_mat_aether_gel" to 2),"currency" to 5)
        val loaded=base.copy(documents=base.documents+mapOf("ec:treasure_test" to chest)).world.treasureChests.getValue("ec:treasure_test")
        assertEquals(2,loaded.items["ec:item_mat_aether_gel"])
        assertEquals(5,loaded.currency)
        val invalid=chest+mapOf("items" to mapOf("ec:skill_warrior_1" to 1))
        assertFails { base.copy(documents=base.documents+mapOf("ec:treasure_test" to invalid)).world }
    }

    @Test fun `content loader accepts the treasure type and rejects dangling reward items`() {
        val source=Path.of("src/main/resources/content")
        Files.walk(source).use { paths -> paths.filter(Files::isRegularFile).forEach { file ->
            val target=temp.resolve(source.relativize(file)); Files.createDirectories(target.parent); Files.copy(file,target)
        } }
        val folder=temp.resolve("treasure_chests"); Files.createDirectories(folder)
        val yaml="""schema_version: 1
id: ec:treasure_hidden_spring
type: treasure_chest
world: ec_world
x: 124
y: 65
z: -24
once_flag: ec:flag_early_board_registered
items: {ec:item_mat_aether_gel: 2}
currency: 25
hint_particle: END_ROD
"""
        val file=folder.resolve("hidden.yml"); Files.writeString(file,yaml)
        val loaded=ContentService(temp).load()
        assertEquals(25,loaded.world.treasureChests.getValue("ec:treasure_hidden_spring").currency)
        Files.writeString(file,yaml.replace("ec:item_mat_aether_gel: 2","ec:skill_unknown: 2"))
        assertFails { ContentService(temp).load().world }
    }

    @Test fun `first clear thresholds award each qualifying rank only once`() {
        val p=PlayerProfile(UUID.randomUUID())
        val ranks=listOf(
            ScoreRank("S",60,"ec:flag_s",mapOf("ec:item_mat_aether_gel" to 2)),
            ScoreRank("A",90,"ec:flag_a",mapOf("ec:item_mat_aether_gel" to 1)),
            ScoreRank("B",120,"ec:flag_b"),
            ScoreRank("C",180,"ec:flag_c"),
        )
        assertEquals(listOf("S","A","B","C"),firstClearRanks(p,ranks,55).map { it.rank })
        p.flags.add("ec:flag_s")
        assertEquals(listOf("A","B","C"),firstClearRanks(p,ranks,80).map { it.rank })
        p.flags.addAll(setOf("ec:flag_a","ec:flag_b","ec:flag_c"))
        assertTrue(firstClearRanks(p,ranks,55).isEmpty())
    }

    @Test fun `chapter score rank rewards parse and validate item references`() {
        val base=bundled()
        val chapterId="ec:chapter_early_adventures"
        val chapter=base.documents.getValue(chapterId)
        val configs=chapter.maps("dungeon_configs").map { config ->
            if(config.string("id")!="ec:dungeon_novice_arena") config else {
                val ranks=config.maps("score_ranks").map { rank -> if(rank.string("rank")!="S") rank else rank+mapOf("first_clear_flag" to "ec:flag_early_s_rank","first_clear_items" to listOf(mapOf("item" to "ec:item_mat_aether_gel","amount" to 2))) }
                config+mapOf("score_ranks" to ranks)
            }
        }
        val changed=chapter+mapOf("dungeon_configs" to configs,"flags" to (chapter.strings("flags")+"ec:flag_early_s_rank"))
        val world=base.copy(documents=base.documents+mapOf(chapterId to changed)).world
        assertEquals("ec:flag_early_s_rank",world.dungeons.getValue("ec:dungeon_novice_arena").scoreRanks.first().firstClearFlag)
        assertEquals(2,world.dungeons.getValue("ec:dungeon_novice_arena").scoreRanks.first().firstClearItems["ec:item_mat_aether_gel"])
        val invalidRanks=configs.map { config -> if(config.string("id")!="ec:dungeon_novice_arena") config else config+mapOf("score_ranks" to config.maps("score_ranks").map { rank -> if(rank.string("rank")!="S") rank else rank+mapOf("first_clear_items" to listOf(mapOf("item" to "ec:skill_bad","amount" to 1))) }) }
        val invalid=chapter+mapOf("dungeon_configs" to invalidRanks,"flags" to (chapter.strings("flags")+"ec:flag_early_s_rank"))
        assertFails { base.copy(documents=base.documents+mapOf(chapterId to invalid)).world }
        val noFlagRanks=configs.map { config -> if(config.string("id")!="ec:dungeon_novice_arena") config else config+mapOf("score_ranks" to config.maps("score_ranks").map { rank -> if(rank.string("rank")!="S") rank else rank+mapOf("first_clear_items" to listOf(mapOf("item" to "ec:item_mat_aether_gel","amount" to 1))) }) }
        assertFails { base.copy(documents=base.documents+mapOf(chapterId to chapter+mapOf("dungeon_configs" to noFlagRanks))).world }
    }
}
