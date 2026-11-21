package dev.mcmmo.rpg

import dev.mcmmo.rpg.core.*
import dev.mcmmo.rpg.data.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.*
import java.util.UUID
import kotlin.random.Random
import kotlin.test.*

class FoundationTest {
    @TempDir lateinit var temp: Path
    private fun content(): ContentService {
        val root = Path.of("src/main/resources/content")
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
        assertEquals("ec:tutorial_complete",p.tutorialQuest)
        assertFailsWith<IllegalArgumentException> { game.completeQuest(p,"ec:tutorial") }
        val weapon = c.items["ec:warrior_starter"]
        assertTrue(weapon.withinBudget()); assertTrue(weapon.canEquip(p))
        assertFalse(c.items["ec:mage_starter"].canEquip(p))
        assertEquals(13.0,game.stats(p,listOf(weapon)).attack)
    }
    @Test fun `save loads detached snapshots backup and legacy migration`() {
        val uuid = UUID.randomUUID()
        PlayerStore(temp).use { store ->
            val p = PlayerProfile(uuid,classId="ec:mage",level=3,xp=50,currency=20,resource=33.0,starterGranted=true,tutorialQuest="ec:tutorial")
            p.skillLevels["ec:mage_strike"] = 2
            val first = store.save(p); p.level = 4; first.join()
            assertEquals(3,store.load(uuid).join().level)
            store.save(p).join(); assertEquals(p,store.load(uuid).join())
            Files.writeString(temp.resolve("$uuid.yml"),"corrupt: [")
            assertEquals(3,store.load(uuid).join().level)
            assertEquals(1,store.migrate(mapOf("uuid" to uuid.toString()))["schema_version"])
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
}
