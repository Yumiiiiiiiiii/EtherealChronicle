package net.nakaymi.etherealchronicle

import net.nakaymi.etherealchronicle.core.*
import net.nakaymi.etherealchronicle.data.PlayerStore
import net.nakaymi.etherealchronicle.data.ContentService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import kotlin.random.Random
import kotlin.test.*

class DropPityTest {
    @TempDir lateinit var temp: Path
    @Test fun `consecutive misses are capped and threshold guarantees then resets`() {
        val table=DropTable("ec:boss_drop",listOf(DropEntry("ec:rare",0.0,1,1,pityAfter=3)))
        val pity=mutableMapOf<String,Int>()
        assertTrue(table.roll(Random(1),pity,5).isEmpty()); assertEquals(1,pity.values.single())
        assertTrue(table.roll(Random(2),pity,5).isEmpty()); assertEquals(2,pity.values.single())
        assertEquals(mapOf("ec:rare" to 1),table.roll(Random(3),pity,5)); assertEquals(0,pity.values.single())
        repeat(8) { table.roll(Random(it),pity,5) }; assertTrue(pity.values.all { it in 0..5 })
        assertFails { table.roll(Random(0),pity,2) } // threshold cannot exceed the configured balance cap
    }
    @Test fun `natural success resets pity and non-pity entries do not create counters`() {
        val pity=mutableMapOf("ec:t/ec:rare" to 2)
        assertEquals(mapOf("ec:rare" to 1),DropTable("ec:t",listOf(DropEntry("ec:rare",1.0,1,1,3))).roll(Random(0),pity,5))
        assertEquals(0,pity.getValue("ec:t/ec:rare"))
        val other=mutableMapOf<String,Int>(); DropTable("ec:common",listOf(DropEntry("ec:ore",1.0,1,1))).roll(Random(0),other,5)
        assertTrue(other.isEmpty())
    }
    @Test fun `pity survives schema five save reload and schema four migration`() {
        val store=PlayerStore(temp); val p=PlayerProfile(UUID.randomUUID()); p.dropPity["ec:drop/ec:rare"]=9
        store.use { it.save(p).join(); val loaded=it.load(p.uuid).join(); assertEquals(9,loaded.dropPity["ec:drop/ec:rare"]); assertEquals(5,it.migrate(mapOf("schema_version" to 4))["schema_version"]) }
    }
    @Test fun `party members without loot assignment do not advance pity`() {
        val content=ContentService(Path.of("src/main/resources/content")).load()
        val game=GameService({content},SkillEngine(Clock { 0 },content.balance)); val p=PlayerProfile(UUID.randomUUID())
        game.kill(p,"ec:boss_void_warden",rollDrops=false)
        assertTrue(p.dropPity.isEmpty())
    }
}
