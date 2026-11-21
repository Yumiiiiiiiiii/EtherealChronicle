package net.nakaymi.etherealchronicle

import net.nakaymi.etherealchronicle.core.*
import net.nakaymi.etherealchronicle.data.ContentService
import net.nakaymi.etherealchronicle.paper.AdvancementDatapack
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*

class TitleAdvancementTest {
    @TempDir lateinit var directory:Path

    @Test fun `flag bestiary and collection conditions unlock titles into durable profile flags`() {
        val flag=TitleDefinition("ec:title_flag","Flag title","", "ec:flag_title_flag",TitleAdvancement("Flag","","BOOK",null,"TASK",TitleProgressCondition(TitleConditionType.FLAG,"ec:flag_source",1)))
        val bestiary=TitleDefinition("ec:title_hunter","Hunter","", "ec:flag_title_hunter",TitleAdvancement("Hunter","","BOW",null,"TASK",TitleProgressCondition(TitleConditionType.BESTIARY,"ec:enemy_slime",3)))
        val collector=TitleDefinition("ec:title_collector","Collector","", "ec:flag_title_collector",TitleAdvancement("Collector","","CHEST",null,"GOAL",TitleProgressCondition(TitleConditionType.COLLECTION,null,4)))
        val titles=mapOf(flag.id to flag,bestiary.id to bestiary,collector.id to collector)
        val profile=PlayerProfile(UUID.randomUUID())
        profile.flags += "ec:flag_source"
        profile.bestiary["ec:enemy_slime"]=2
        profile.discoveredItems.addAll(setOf("ec:item_a","ec:item_b","ec:item_c"))
        assertEquals(setOf(flag.unlockFlag),profile.syncTitleUnlockFlags(titles))
        assertFalse(profile.equipTitle(bestiary.id,titles))
        profile.bestiary["ec:enemy_slime"]=3
        assertEquals(setOf(bestiary.unlockFlag),profile.syncTitleUnlockFlags(titles))
        profile.discoveredItems += "ec:item_d"
        assertEquals(setOf(collector.unlockFlag),profile.syncTitleUnlockFlags(titles))
        assertTrue(profile.equipTitle(bestiary.id,titles))
        assertTrue(profile.equipTitle(collector.id,titles))
        assertTrue(profile.syncTitleUnlockFlags(titles).isEmpty())
    }

    @Test fun `all shipped title definitions have advancement-ready data`() {
        val content=ContentService(Path.of("src/main/resources/content")).load()
        val advancementTitles=content.world.titles.values.filter { it.advancement != null }
        assertTrue(advancementTitles.size >= 6)
        assertEquals(content.world.titles.size,advancementTitles.size)
        advancementTitles.forEach { title ->
            val adv=assertNotNull(title.advancement)
            when(adv.condition.type) {
                TitleConditionType.FLAG -> assertTrue(adv.condition.target?.startsWith("ec:") == true)
                TitleConditionType.BESTIARY -> assertTrue(adv.condition.target in content.world.enemies)
                TitleConditionType.COLLECTION -> assertNull(adv.condition.target)
                TitleConditionType.FISH_SPECIES_COUNT,TitleConditionType.ENHANCE_LEVEL -> assertNull(adv.condition.target)
                TitleConditionType.LIFE_LEVEL -> assertTrue(adv.condition.target in LifeSkillKind.entries.map { it.name })
                TitleConditionType.CHALLENGE_RANK -> assertTrue(adv.condition.target in content.world.dungeons)
            }
            assertTrue(adv.condition.count >= 1)
        }
    }

    @Test fun `datapack installation preserves collisions emits valid json and is idempotent`() {
        val packs=directory.resolve("world/datapacks")
        val userPack=packs.resolve("etherealchronicle_advancements")
        Files.createDirectories(userPack)
        Files.writeString(userPack.resolve("do-not-touch.txt"),"server-owned")
        val parent=title("ec:title_parent","親称号","minecraft:adventure/root")
        val child=title("ec:title_child","称号 \"星\"","ec:title_parent",description="改行\nつき")
        val first=AdvancementDatapack.install(directory.resolve("world"),listOf(parent,child))
        assertNotEquals(userPack,first.directory)
        assertEquals("server-owned",Files.readString(userPack.resolve("do-not-touch.txt")))
        val root=readJson(first.directory.resolve("data/ec/advancement/chronicle/root.json"))
        assertEquals("EtherealChronicle",root.mapping("display").string("title"))
        val childJson=readJson(first.directory.resolve("data/ec/advancement/chronicle/title_child.json"))
        assertEquals("ec:chronicle/title_parent",childJson.string("parent"))
        assertEquals("称号 \"星\"",childJson.mapping("display").string("title"))
        assertEquals("改行\nつき",childJson.mapping("display").string("description"))
        assertTrue(first.changed)
        val second=AdvancementDatapack.install(directory.resolve("world"),listOf(parent,child))
        assertFalse(second.changed)
        val third=AdvancementDatapack.install(directory.resolve("world"),listOf(parent))
        assertTrue(third.changed)
        assertFalse(Files.exists(third.directory.resolve("data/ec/advancement/chronicle/title_child.json")))
        val metadata=readJson(third.directory.resolve("pack.mcmeta")).mapping("pack")
        assertEquals(listOf(121,0),metadata["min_format"])
        assertEquals(listOf(121,0),metadata["max_format"])
    }

    @Test fun `Paper per-dimension folder installs datapack beside level dat`() {
        val level=directory.resolve("server/world")
        val dimension=level.resolve("dimensions/minecraft/overworld")
        Files.createDirectories(dimension)
        Files.writeString(level.resolve("level.dat"),"fixture")
        val installation=AdvancementDatapack.install(dimension,listOf(title("ec:title_root","根称号","minecraft:adventure/root")))
        assertEquals(level.resolve("datapacks/etherealchronicle_advancements"),installation.directory)
        assertTrue(Files.exists(installation.directory.resolve("pack.mcmeta")))
        assertFalse(Files.exists(dimension.resolve("datapacks/etherealchronicle_advancements")))
    }

    private fun title(id:String,name:String,parent:String?,description:String="説明") = TitleDefinition(id,name,description,"ec:flag_${id.substringAfter(':')}",TitleAdvancement(name,description,"NETHER_STAR",parent,"TASK",TitleProgressCondition(TitleConditionType.FLAG,"ec:flag_${id.substringAfter(':')}",1)))

    @Suppress("UNCHECKED_CAST")
    private fun readJson(path:Path):Map<String,Any?> = Yaml().load<Any>(Files.readString(path)) as Map<String,Any?>

    private fun Map<String,Any?>.mapping(key:String)=this[key] as Map<String,Any?>
    private fun Map<String,Any?>.string(key:String)=this[key] as String
}
