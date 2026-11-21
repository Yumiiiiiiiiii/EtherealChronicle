package net.nakaymi.etherealchronicle

import net.nakaymi.etherealchronicle.core.*
import net.nakaymi.etherealchronicle.data.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.*
import java.util.UUID
import kotlin.test.*

class RegressionTest {
    @TempDir lateinit var temp: Path
    private fun bundled(): Map<String,ByteArray> {
        val root=Path.of("src/main/resources")
        return Files.walk(root.resolve("content")).use { paths -> paths.filter { Files.isRegularFile(it) }.toList().associate { "content/"+root.resolve("content").relativize(it).joinToString("/") to Files.readAllBytes(it) } }
    }
    @Test fun `removed shipped files retire by canonical hash while edited orphans are preserved`() {
        val old=mapOf("content/items/old.yml" to "id: ec:old\n".toByteArray(),"content/items/custom.yml" to "id: ec:custom\n".toByteArray())
        DefaultContentInstaller.install(temp,old)
        Files.writeString(temp.resolve("content/items/old.yml"),"\uFEFFid: ec:old\r\n")
        Files.writeString(temp.resolve("content/items/custom.yml"),"id: ec:edited\n")
        val result=DefaultContentInstaller.retire(temp,emptySet())
        assertEquals(1,result.retired.size); assertEquals(listOf("content/items/custom.yml"),result.preserved)
        assertFalse(Files.exists(temp.resolve("content/items/old.yml"))); assertTrue(Files.exists(temp.resolve("content/items/custom.yml")))
        assertTrue(Files.walk(temp.resolve("retired-content")).use { paths -> paths.anyMatch { it.fileName.toString() == "old.yml" } })
    }
    @Test fun `upgrade refreshes only unchanged foundation defaults and archives previous bytes`() {
        val oldRoot=Path.of("src/test/resources/foundation-content")
        val old=Files.walk(oldRoot).use { paths -> paths.filter { Files.isRegularFile(it) }.toList().associate { "content/"+oldRoot.relativize(it).joinToString("/") to Files.readAllBytes(it) } }
        val known=javaClass.getResourceAsStream("/foundation-content.properties")!!.use { DefaultContentInstaller.manifest(it.readBytes()) }
        assertEquals(old.mapValues { DefaultContentInstaller.hash(it.value) },known)
        val bundled=bundled(); old.forEach { (name,bytes) -> val target=temp.resolve(name); Files.createDirectories(target.parent); Files.write(target,bytes) }
        val edited=temp.resolve("content/classes/warrior.yml"); Files.writeString(edited,Files.readString(edited)+"# server edit\n")
        val result=DefaultContentInstaller.installTracked(temp,bundled,known)
        assertTrue("content/balance/default.yml" in result.updated)
        assertTrue("content/classes/warrior.yml" in result.changed)
        assertContentEquals(bundled.getValue("content/balance/default.yml"),Files.readAllBytes(temp.resolve("content/balance/default.yml")))
        assertTrue(Files.walk(temp.resolve("content-history")).use { paths -> paths.anyMatch { it.fileName.toString()=="default.yml" } })
        assertTrue(Files.readString(edited).contains("# server edit"))
    }
    @Test fun `foundation upgrade retires seven defaults and reports exact invalid server file before fallback`() {
        val oldRoot=Path.of("src/test/resources/foundation-content")
        val old=Files.walk(oldRoot).use { paths -> paths.filter { Files.isRegularFile(it) }.toList().associate { "content/"+oldRoot.relativize(it).joinToString("/") to Files.readAllBytes(it) } }
        // Models a foundation installation that predates the manifest feature.
        old.forEach { (name,bytes) -> val path=temp.resolve(name); Files.createDirectories(path.parent); Files.write(path,bytes) }
        val current=bundled()
        val legacy=javaClass.getResourceAsStream("/retired-content.properties")!!.use { DefaultContentInstaller.manifest(it.readBytes()) }
        assertEquals(7,DefaultContentInstaller.retire(temp,current.keys,legacy).retired.size)
        DefaultContentInstaller.install(temp,current)
        val balance=temp.resolve("content/balance/default.yml")
        Files.writeString(balance,Files.readString(balance).replace("stat_cap: 100000","stat_cap: 10000"))
        val loaded=ContentBootstrap.initialize(temp,current)
        assertTrue(loaded.usedFallback,loaded.problem); assertTrue(loaded.problem!!.contains(".yml"),loaded.problem); assertTrue(loaded.problem.contains("ec:"),loaded.problem); assertTrue(loaded.problem.contains("hp="),loaded.problem)
        assertEquals(ContentService(Path.of("src/main/resources/content")).load().world.enemies.getValue("ec:boss_void_warden").hp,loaded.service.snapshot().world.enemies.getValue("ec:boss_void_warden").hp)
        // validate/reload still point to disk; editing it makes the same service recover.
        Files.write(balance,current.getValue("content/balance/default.yml"))
        loaded.service.reload(); assertEquals(3,loaded.service.snapshot().classes.all().size)
    }
    @Test fun `global skill cooldown blocks cycling burst without spending and basic gate rejects spam`() {
        val c=ContentService(Path.of("src/main/resources/content")).load(); var time=0L
        val engine=SkillEngine(Clock { time },c.balance.copy(skillGcdMillis=800)); val p=PlayerProfile(UUID.randomUUID(),resource=100.0)
        assertEquals(CastResult.SUCCESS,engine.cast(p,c.skills["ec:mage_strike"]))
        assertEquals(CastResult.COOLDOWN,engine.cast(p,c.skills["ec:mage_burst"])); assertEquals(90.0,p.resource)
        time=800; assertEquals(CastResult.SUCCESS,engine.cast(p,c.skills["ec:mage_burst"])); assertEquals(70.0,p.resource)
        val gate=ActionGate(Clock { time }); assertEquals(1,(1..20).count { gate.accept(p.uuid,550) })
        time+=550; assertTrue(gate.accept(p.uuid,550))
        assertTrue(c.balance.damage(Stats.ZERO,85.0,0.0,115.0,false,1.25) < 100.0)
    }
    @Test fun `schema four mailbox and alias migrations round trip with quit restoration ordered after clearing`() {
        val c=ContentService(Path.of("src/main/resources/content")).load(); val p=PlayerProfile(UUID.randomUUID(),classId="ec:mage",starterGranted=true)
        p.quests["ec:tutorial"]=QuestProgress(1,true)
        ProfileContentMigration.apply(p,c)
        assertEquals("ec:class_mage",p.classId); assertFalse("ec:tutorial" in p.quests); assertTrue("ec:quest_m01_awakening" in p.quests)
        p.pendingRewards["ec:item_con_minor_hp_potion"]=2
        PlayerStore(temp).use { store ->
            store.save(p).join()
            val output=p.pendingRewards.toMap(); p.pendingRewards.clear(); store.save(p)
            // Same sequence used by beforeQuit: restore before enqueueing the final quit snapshot.
            p.pendingRewards.putAll(output); store.save(p)
            assertEquals(output,store.load(p.uuid).join().pendingRewards)
        }
    }
    @Test fun `all eight main quests can complete through real objective reward and branch services`() {
        val c=ContentService(Path.of("src/main/resources/content")).load(); val game=GameService({ c },SkillEngine(Clock { 0 },c.balance)); val bag=MemoryInventory()
        val p=PlayerProfile(UUID.randomUUID(),level=20); game.chooseClass(p,"ec:class_mage")
        val main=c.world.chapters.getValue("ec:chapter_1").strings("quests").filter { it.startsWith("ec:quest_m") }
        for(id in main) {
            if(id !in p.quests) game.startQuest(p,id)
            val q=c.world.quests.getValue(id)
            q.objectives.forEach { o -> when(o.kind) {
                ObjectiveKind.KILL -> repeat(o.amount) { game.kill(p,o.target,bag).forEach { (item,n) -> bag.exchange(emptyMap(),mapOf(item to n)) } }
                ObjectiveKind.COLLECT -> { val missing=(o.amount-bag.count(o.target)).coerceAtLeast(0); bag.exchange(emptyMap(),mapOf(o.target to missing)) }
                else -> game.quests.event(p,o.kind,o.target,o.amount,bag)
            } }
            val choice=when {
                id.contains("m03_") -> DialogueEngine { c }.node(p,"ec:dlg_elena_hub").let { (node,_) -> DialogueEngine { c }.choice(p,"ec:dlg_elena_hub",node,"choose_purify") }
                id.contains("m05_") -> DialogueEngine { c }.node(p,"ec:dlg_daren_hub").let { (node,_) -> DialogueEngine { c }.choice(p,"ec:dlg_daren_hub",node,"forgive_daren") }
                id.contains("m08_") -> DialogueEngine { c }.node(p,"ec:dlg_vulcan_hub").let { (node,_) -> DialogueEngine { c }.choice(p,"ec:dlg_vulcan_hub",node,"choose_observatory_research") }
                else -> null
            }
            choice?.let { p.flags.addAll(it.flags) }
            assertTrue(game.quests.ready(p,id,bag),id); game.completeQuest(p,id,bag)
            assertFails { game.completeQuest(p,id,bag) }
        }
        assertEquals(8,main.count { p.quests.getValue(it).completed }); assertTrue("ec:flag_ch1_completed" in p.flags)
        assertTrue("ec:flag_choice3_observatory" in p.flags)
        assertEquals(81435,(1..19).sumOf(c.balance::requiredXp))
    }
}


