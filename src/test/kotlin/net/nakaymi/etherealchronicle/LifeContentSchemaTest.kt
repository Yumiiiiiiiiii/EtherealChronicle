package net.nakaymi.etherealchronicle

import net.nakaymi.etherealchronicle.data.ContentException
import net.nakaymi.etherealchronicle.data.ContentService
import net.nakaymi.etherealchronicle.core.NodeKind
import net.nakaymi.etherealchronicle.core.TackleKind
import net.nakaymi.etherealchronicle.core.PlayerProfile
import net.nakaymi.etherealchronicle.core.LifeSkillKind
import net.nakaymi.etherealchronicle.core.LifeSkillProgress
import net.nakaymi.etherealchronicle.core.FishRecord
import net.nakaymi.etherealchronicle.core.Crown
import net.nakaymi.etherealchronicle.data.PlayerStore
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import java.util.UUID

class LifeContentSchemaTest {
    private fun fixture(): Path {
        val root=Files.createTempDirectory("ec-life-content")
        val source=Path.of("src/main/resources/content")
        Files.walk(source).use { paths -> paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".yml") }.forEach { file ->
            val target=root.resolve(source.relativize(file).toString())
            target.parent.createDirectories(); Files.copy(file,target)
        } }
        val rootChapter=root.resolve("chapters/life_professions.yml")
        rootChapter.writeText(Files.readString(rootChapter).replace("  - ec:flag_title_master_gatherer", "  - ec:flag_title_master_gatherer\n  - ec:flag_test_life"))
        root.resolve("tackle").createDirectories()
        root.resolve("fishing_spots").createDirectories()
        root.resolve("fish_species").createDirectories()
        root.resolve("gathering_nodes").createDirectories()
        root.resolve("tackle/test_rod.yml").writeText("""
            schema_version: 1
            id: ec:tackle_test_rod
            type: tackle
            item: ec:item_tool_rod_oak
            kind: ROD
            tier: 1
            min_life_level: 1
        """.trimIndent())
        root.resolve("fishing_spots/test_spot.yml").writeText("""
            schema_version: 1
            id: ec:spot_test
            type: fishing_spot
            name: テスト釣り場
            area: ec:area_lumina_port
            world: ec_world
            center: {x: 10, y: 64, z: 28}
            radius: 12
            default_for_area: false
            tags: [PORT, FRESHWATER]
        """.trimIndent())
        root.resolve("fish_species/test_fish.yml").writeText("""
            schema_version: 1
            id: ec:fish_test
            type: fish_species
            name: 試験魚
            item: ec:item_fish_silver_sardine
            rarity: COMMON
            tags: [PORT]
            habitats: [ec:area_lumina_port]
            time_windows: [ANY]
            weather: [ANY]
            min_rod_tier: 1
            min_fishing_level: 1
            base_weight: 10
            min_cm: 10
            max_cm: 40
            silver_crown_cm: 34
            gold_crown_cm: 39
            life_xp: 10
            adventurer_xp: 5
            minigame: {bite_window_ms: 1800, difficulty: 1.2, sweet_width: 34, progress_per_sec: 22, tension_decay_per_sec: 22, thrash_chance_per_sec: 0.14, dive_chance_per_sec: 0.05}
        """.trimIndent())
        root.resolve("gathering_nodes/test_node.yml").writeText("""
            schema_version: 1
            id: ec:node_test_herb
            type: gathering_node
            name: 試験薬草
            node_kind: HERB
            display_material: SWEET_BERRY_BUSH
            depleted_material: DEAD_BUSH
            required_tool_kind: SICKLE
            min_tool_tier: 1
            min_gathering_level: 1
            channel_ms: 2200
            respawn_seconds: 90
            per_player_respawn: true
            life_xp: 12
            adventurer_xp: 5
            yields: [{item: ec:item_mat_ash_leaf, min: 1, max: 2, chance: 1.0}]
            rare_yields: [{item: ec:item_mat_starlight_lotus, min: 1, max: 1, chance: 0.1, pity_after: 12}]
            locations: [{id: herb, area: ec:area_lumina_port, world: ec_world, x: 10, y: 65, z: 28}]
        """.trimIndent())
        return root
    }

    @Test
    fun `loads all life profession schemas with typed references`() {
        val baseline=ContentService(Path.of("src/main/resources/content")).load().world
        val content=ContentService(fixture()).load().world
        assertEquals(20,content.lifeBalance.levelCap)
        assertEquals(2,content.lifeBalance.fishingMilestones.size)
        assertEquals(TackleKind.ROD,content.tackle.getValue("ec:tackle_test_rod").kind)
        assertEquals("ec:area_lumina_port",content.fishingSpots.getValue("ec:spot_test").area)
        assertEquals("ec:item_fish_silver_sardine",content.fishSpecies.getValue("ec:fish_test").item)
        assertEquals(NodeKind.HERB,content.gatheringNodes.getValue("ec:node_test_herb").kind)
        assertEquals(baseline.tackle.size+1,content.tackle.size)
        assertEquals(baseline.fishingSpots.size+1,content.fishingSpots.size)
        assertEquals(baseline.fishSpecies.size+1,content.fishSpecies.size)
        assertEquals(baseline.gatheringNodes.size+1,content.gatheringNodes.size)
        assertEquals(baseline.gatheringNodes.values.sumOf { it.locations.size }+1,content.gatheringNodes.values.sumOf { it.locations.size })
        assertEquals(8,content.lifeBalance.macroMinSamples)
        assertEquals(18.0,content.lifeBalance.macroResponseDeviationMillis)
        assertEquals(120.0,content.lifeBalance.macroCastIntervalDeviationMillis)
        assertEquals(0.1,content.lifeBalance.macroRareWeightMultiplier)
    }

    @Test
    fun `rejects out of range tool and dangling fish item with owning file diagnostics`() {
        val root=fixture()
        val tackle=root.resolve("tackle/test_rod.yml")
        Files.writeString(tackle,Files.readString(tackle).replace("tier: 1","tier: 4"))
        val error=assertFailsWith<ContentException> { ContentService(root).load() }
        assertTrue(error.message.orEmpty().contains("test_rod.yml"), error.message.orEmpty())
        assertTrue(error.message.orEmpty().contains("ec:tackle_test_rod"), error.message.orEmpty())
    }

    @Test
    fun `rejects dangling fish item references`() {
        val root=fixture()
        val fish=root.resolve("fish_species/test_fish.yml")
        Files.writeString(fish,Files.readString(fish).replace("ec:item_fish_silver_sardine","ec:item_missing_fish"))
        val error=assertFailsWith<ContentException> { ContentService(root).load() }
        assertTrue(error.message.orEmpty().contains("ec:fish_test"), error.message.orEmpty())
        assertTrue(error.message.orEmpty().contains("ec:item_missing_fish"), error.message.orEmpty())
    }

    @Test
    fun `migrates schema nine and atomically round trips life skill records`() {
        val directory=Files.createTempDirectory("ec-life-player")
        PlayerStore(directory).use { store ->
            val migration=store.migrate(mapOf("schema_version" to 9))
        assertEquals(13,migration["schema_version"])
            assertEquals(0,migration["best_enhancement_level"])
            assertEquals("AUTO",migration["fishing_bait"])
            assertTrue((migration["life_skills"] as Map<*,*>).containsKey("FISHING"))
            val profile=PlayerProfile(UUID.randomUUID())
            profile.lifeSkills[LifeSkillKind.FISHING]=LifeSkillProgress(7,153)
            profile.fishRecords["ec:fish_silver_sardine"]=FishRecord(12,83.5,Crown.GOLD)
            profile.gatheringRecords["ec:node_hill_crystal_1"]=4
            profile.gatheringReadyAt["ec:node_hill_crystal_1"]=123456789
            profile.fishingBait="ec:item_bait_aether_lure"
            profile.bestEnhancementLevel=4
            store.save(profile).join()
            val loaded=store.load(profile.uuid).join()
            assertEquals(profile.lifeSkills,loaded.lifeSkills)
            assertEquals(profile.fishRecords,loaded.fishRecords)
            assertEquals(profile.gatheringRecords,loaded.gatheringRecords)
            assertEquals(profile.gatheringReadyAt,loaded.gatheringReadyAt)
            assertEquals(profile.fishingBait,loaded.fishingBait)
            assertEquals(profile.bestEnhancementLevel,loaded.bestEnhancementLevel)
        }
    }
}
