package net.nakaymi.etherealchronicle

import net.nakaymi.etherealchronicle.core.*
import net.nakaymi.etherealchronicle.data.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.*
import java.util.UUID
import kotlin.random.Random
import kotlin.test.*

class FoundationTest {
    @TempDir lateinit var temp: Path
    private fun content(): ContentService {
        val root = Path.of("src/test/resources/foundation-content")
        Files.walk(root).use { paths -> paths.filter { Files.isRegularFile(it) }.forEach {
            val dst = temp.resolve(root.relativize(it)); Files.createDirectories(dst.parent); Files.copy(it,dst)
        } }
        return ContentService(temp).also { it.reload() }
    }
    @Test fun `damage follows defense and critical formula and clamps extreme attacks`() {
        val b = content().snapshot().balance
        assertEquals(10.0,b.damage(Stats(attack=20.0),100.0,1.0,0.0,false),0.0001)
        assertEquals(15.0,b.damage(Stats(attack=20.0),100.0,1.0,0.0,true),0.0001)
        assertEquals(b.damageCap,b.damage(Stats(attack=1e9),0.0,100.0,1e9,true))
    }
    @Test fun `xp crosses levels with remainder and stops at cap`() {
        val b = content().snapshot().balance; val p = PlayerProfile(UUID.randomUUID())
        assertEquals(1,b.grantXp(p,150)); assertEquals(2,p.level); assertEquals(50,p.xp)
        repeat(100) { b.grantXp(p,Long.MAX_VALUE) }
        assertEquals(b.levelCap,p.level); assertEquals(0,p.xp)
        assertFailsWith<IllegalArgumentException> { b.grantXp(p,-1) }
    }
    @Test fun `cooldown cost and levels reject without spending twice`() {
        val c = content().snapshot(); var now = 100L
        val engine = SkillEngine(Clock { now },c.balance)
        val p = PlayerProfile(UUID.randomUUID(),resource=100.0)
        val skill = c.skills["ec:mage_strike"]
        assertEquals(CastResult.SUCCESS,engine.cast(p,skill)); assertEquals(90.0,p.resource)
        assertEquals(CastResult.COOLDOWN,engine.cast(p,skill)); assertEquals(90.0,p.resource)
        now += skill.cooldownMillis
        p.resource = 0.0; assertEquals(CastResult.RESOURCE,engine.cast(p,skill))
        p.skillLevels[skill.id] = 999; assertEquals(CastResult.INVALID_LEVEL,engine.cast(p,skill))
    }
    @Test fun `drop boundaries and seeded rolls are deterministic`() {
        val table = DropTable("ec:test",listOf(DropEntry("ec:always",1.0,2,2),DropEntry("ec:never",0.0,1,1)))
        assertEquals(mapOf("ec:always" to 2),table.roll(Random(12)))
        assertEquals(table.roll(Random(4)),table.roll(Random(4)))
    }
    @Test fun `validation detects duplicate dangling range and future version while retaining snapshot`() {
        val service = content(); val original = service.snapshot()
        val extra = temp.resolve("invalid.yml")
        val cases = listOf(
            "schema_version: 1\nid: ec:warrior\ntype: quest",
            "schema_version: 1\nid: ec:bad\ntype: quest\nquest: ec:missing",
            "schema_version: 1\nid: ec:bad\ntype: quest\nchance: 2",
            "schema_version: 2\nid: ec:bad\ntype: quest")
        cases.forEach { Files.writeString(extra,it); assertFailsWith<ContentException> { service.reload() }; assertSame(original,service.snapshot()) }
    }
    @Test fun `class selection combat rewards and tutorial are shared service behavior`() {
        val c = content().snapshot(); val game = GameService({ c },SkillEngine(Clock { 0 },c.balance))
        val p = PlayerProfile(UUID.randomUUID())
        game.chooseClass(p,"ec:warrior"); assertEquals(0.0,p.resource)
        assertFailsWith<IllegalStateException> { game.chooseClass(p,"ec:mage") }
        game.kill(p,"ec:forest_slime")
        assertEquals(2,p.level); assertEquals(25,p.xp); assertEquals(12,p.currency)
        assertTrue(p.quests["ec:tutorial"]!!.completed)
        assertFailsWith<IllegalArgumentException> { game.completeQuest(p,"ec:tutorial") }
        val weapon = c.items["ec:warrior_starter"]
        assertTrue(weapon.withinBudget()); assertTrue(weapon.canEquip(p))
        assertFalse(c.items["ec:mage_starter"].canEquip(p))
        assertEquals(c.classes[p.classId!!].stats.attack+c.classes[p.classId!!].growth.attack*(p.level-1)+weapon.stats.attack,game.stats(p,listOf(weapon)).attack)
    }
    @Test fun `save loads detached snapshots backup and legacy migration`() {
        val uuid = UUID.randomUUID()
        PlayerStore(temp).use { store ->
            val p = PlayerProfile(uuid,classId="ec:mage",level=3,xp=50,currency=20,resource=33.0,starterGranted=true,quests=mutableMapOf("ec:tutorial" to QuestProgress()))
            p.challengeRecords["ec:dungeon_aether_crucible"]=ChallengeRecord(275,"A")
            p.skillLevels["ec:mage_strike"] = 2
            val first = store.save(p); p.level = 4; first.join()
            assertEquals(3,store.load(uuid).join().level)
            store.save(p).join(); assertEquals(p,store.load(uuid).join())
            Files.writeString(temp.resolve("$uuid.yml"),"corrupt: [")
            assertEquals(3,store.load(uuid).join().level)
        assertEquals(12,store.migrate(mapOf("uuid" to uuid.toString()))["schema_version"])
            assertEquals(emptyMap<String,Int>(),store.migrate(mapOf("schema_version" to 4))["drop_pity"])
            assertFailsWith<IllegalArgumentException> { store.migrate(mapOf("schema_version" to 99)) }
        }
    }
    @Test fun `serialized quit save precedes reconnect load`() {
        PlayerStore(temp).use { store ->
            val p = PlayerProfile(UUID.randomUUID(),level=7)
            store.save(p)
            assertEquals(7,store.load(p.uuid).join().level)
        }
    }
    @Test fun `shipped content index matches every bundled yaml`() {
        val expected = Files.walk(Path.of("src/main/resources/content")).use { paths -> paths.filter { Files.isRegularFile(it) }.map { "content/"+Path.of("src/main/resources/content").relativize(it).toString().replace(92.toChar(),'/') }.toList().sorted() }
        val actual = javaClass.getResourceAsStream("/content-index.txt")!!.bufferedReader().use { it.readLines().filter(String::isNotBlank).sorted() }
        assertEquals(expected,actual)
        assertEquals(3,content().snapshot().classes.all().size)
    }
    @Test fun `wrong type bare reference and incomplete enemy are rejected`() {
        val service = content()
        val warrior = temp.resolve("classes/warrior.yml")
        val original = Files.readString(warrior)
        listOf("mage_strike","ec:mage_starter").forEach { bad ->
            Files.writeString(warrior,original.replace("ec:warrior_strike",bad))
            assertFailsWith<ContentException> { service.load() }
        }
        Files.writeString(warrior,original)
        val enemy = temp.resolve("enemies/forest_slime.yml")
        Files.writeString(enemy,Files.readString(enemy).replace("hp: 30\n",""))
        assertFailsWith<ContentException> { service.load() }
    }
    @Test fun `content migration hook runs before schema checks`() {
        content()
        val warrior = temp.resolve("classes/warrior.yml")
        Files.writeString(warrior,Files.readString(warrior).replace("schema_version: 1","schema_version: 0"))
        val migrated = ContentService(temp,ContentMigration { it + ("schema_version" to 1) }).load()
        assertEquals("戦士",migrated.classes["ec:warrior"].name)
    }
    @Test fun `quest objective count is persisted and rewards only once`() {
        content()
        val quest = temp.resolve("quests/tutorial.yml")
        Files.writeString(quest,Files.readString(quest).replace("required_kills: 1","required_kills: 2"))
        val c = ContentService(temp).load(); val game = GameService({ c },SkillEngine(Clock { 0 },c.balance))
        val p = PlayerProfile(UUID.randomUUID()); game.chooseClass(p,"ec:mage")
        game.kill(p,"ec:forest_slime"); assertFalse(p.quests["ec:tutorial"]!!.completed); assertEquals(1,p.quests["ec:tutorial"]!!.progress)
        game.kill(p,"ec:forest_slime"); assertTrue(p.quests["ec:tutorial"]!!.completed); assertEquals(14,p.currency)
        game.kill(p,"ec:forest_slime"); assertEquals(16,p.currency)
    }
    @Test fun `resource mechanisms distinguish focus mana and rage and respect gear maximum`() {
        val c = content().snapshot(); val game = GameService({ c },SkillEngine(Clock { 0 },c.balance))
        val p = PlayerProfile(UUID.randomUUID()); game.chooseClass(p,"ec:archer"); p.resource=0.0
        game.regenerate(p,120.0,true); assertEquals(0.0,p.resource)
        game.regenerate(p,120.0,false); assertEquals(5.0,p.resource)
        game.changeClass(p,"ec:mage"); p.resource=0.0
        game.regenerate(p,120.0,true); assertEquals(8.0,p.resource)
        game.changeClass(p,"ec:warrior"); p.resource=110.0
        game.onHit(p,120.0); assertEquals(120.0,p.resource)
        game.regenerate(p,120.0,false); assertEquals(120.0,p.resource)
    }
    @Test fun `stat sums clamp at balance caps and XP gain saturates safely`() {
        val c = content().snapshot(); val game = GameService({ c },SkillEngine(Clock { 0 },c.balance))
        val p = PlayerProfile(UUID.randomUUID()); game.chooseClass(p,"ec:mage")
        val gear = ItemDefinition("ec:huge","huge","STICK",Rarity.COMMON,1,emptyList(),Stats(hp=1e10,resource=1e10,attack=1e10,crit=1.0),1e20)
        val s = game.stats(p,listOf(gear)); assertEquals(c.world.tuning.playerHpCap,s.hp); assertEquals(c.balance.statCap,s.attack); assertEquals(c.balance.critCap,s.crit)
        c.balance.grantXp(p,Long.MAX_VALUE)
        assertEquals(c.balance.xpGainCap,p.xp+(1 until p.level).sumOf(c.balance::requiredXp))
        p.xp=Long.MAX_VALUE; c.balance.grantXp(p,Long.MAX_VALUE); assertEquals(c.balance.levelCap,p.level)
    }
    @Test fun `backup remains valid after recovery save and future schemas are preserved`() {
        val uuid = UUID.randomUUID()
        PlayerStore(temp).use { store ->
            val p = PlayerProfile(uuid,level=3); store.save(p).join(); store.save(p).join()
            val path = temp.resolve("$uuid.yml")
            Files.writeString(path,"broken: [")
            val recovered = store.load(uuid).join(); recovered.level=4; store.save(recovered).join()
            Files.writeString(path,"broken: [")
            assertEquals(3,store.load(uuid).join().level)
            Files.writeString(path,"schema_version: 99\nuuid: $uuid")
            assertFails { store.load(uuid).join() }
            assertTrue(Files.readString(path).contains("schema_version: 99"))
            val migrated = store.migrate(mapOf("schema_version" to 1,"tutorial_quest" to "ec:tutorial_complete"))
            assertEquals(true,migrated.mapping("quests").mapping("ec:tutorial")["completed"])
        }
    }
    @Test fun `default installation preserves server edits and installs newly added files`() {
        val bundled = mapOf("content/test/a.yml" to "original".toByteArray())
        assertTrue(DefaultContentInstaller.install(temp,bundled).isEmpty())
        val target = temp.resolve("content/test/a.yml"); Files.writeString(target,"custom")
        assertEquals(listOf("content/test/a.yml"),DefaultContentInstaller.install(temp,bundled+("content/test/b.yml" to "new".toByteArray())))
        assertEquals("custom",Files.readString(target)); assertEquals("new",Files.readString(temp.resolve("content/test/b.yml")))
    }
    @Test fun `currency persistence keeps long precision and nested quest snapshots detach`() {
        PlayerStore(temp).use { store ->
            val p = PlayerProfile(UUID.randomUUID(),currency=9_007_199_254_740_993L,quests=mutableMapOf("ec:tutorial" to QuestProgress(1,false)))
            val saving = store.save(p); p.quests["ec:tutorial"]!!.progress=100; saving.join()
            val loaded = store.load(p.uuid).join()
            assertEquals(9_007_199_254_740_993L,loaded.currency); assertEquals(1,loaded.quests["ec:tutorial"]!!.progress)
        }
    }
}










