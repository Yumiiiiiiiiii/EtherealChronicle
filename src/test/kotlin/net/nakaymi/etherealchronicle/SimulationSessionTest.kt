package net.nakaymi.etherealchronicle

import net.nakaymi.etherealchronicle.core.*
import net.nakaymi.etherealchronicle.data.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*

class SimulationSessionTest {
    private val content=ContentService(Path.of("src/main/resources/content")).load()
    private val game=GameService({content},SkillEngine(Clock { 1000 },content.balance))
    private fun session()=SimulationSession({content},game)
    @Test fun `console shop shares plans and rejects replay without inventory mutation`() {
        val s=session(); val p=PlayerProfile(UUID.randomUUID()); s.execute(p,"currency",listOf("100"))
        s.execute(p,"buy",listOf("ec:shop_lumina_general","minor_hp_potion_convenience","tx"))
        assertEquals(72,p.currency); assertEquals(1,s.inventory.count("ec:item_con_minor_hp_potion"))
        assertFails { s.execute(p,"buy",listOf("ec:shop_lumina_general","minor_hp_potion_convenience","tx")) }
        assertEquals(72,p.currency); assertEquals(1,s.inventory.count("ec:item_con_minor_hp_potion"))
        s.execute(p,"sell",listOf("ec:shop_lumina_general","ec:item_con_minor_hp_potion","1","sell"))
        assertEquals(79,p.currency); assertEquals(0,s.inventory.count("ec:item_con_minor_hp_potion"))
        assertContains(assertFails { s.execute(p,"sell",listOf("ec:shop_lumina_general","ec:item_con_minor_hp_potion","1","sell")) }.message!!,"処理済み")
        assertContains(assertFails { s.execute(p,"sell",listOf("ec:shop_lumina_general","ec:item_con_minor_hp_potion","999","oversize")) }.message!!,"数量")
    }
    @Test fun `console dialogue acceptance objective sequence and rewards use chapter engines`() {
        val s=session(); val p=PlayerProfile(UUID.randomUUID())
        s.execute(p,"dialogue",listOf("ec:dlg_celia_hub")); s.execute(p,"choice",listOf("register"))
        assertEquals(1,s.inventory.count("ec:item_q_guild_badge"))
        game.chooseClass(p,"ec:warrior"); s.starter(p)
        val before=s.inventory.contents.toMap(); s.starter(p); assertEquals(before,s.inventory.contents)
        for(npc in listOf("celia","vulcan","garudo","vulcan")) s.execute(p,"event",listOf("TALK","ec:npc_$npc"))
        s.execute(p,"complete",listOf("ec:quest_m01_awakening"))
        assertTrue("ec:flag_ch1_started" in p.flags); assertEquals(3,s.inventory.count("ec:item_con_minor_hp_potion"))
        val gold=p.currency; assertFails { s.execute(p,"complete",listOf("ec:quest_m01_awakening")) }; assertEquals(gold,p.currency)
    }
    @Test fun `failed dialogue actions roll flags and bag back together`() {
        val doc=mapOf<String,Any?>("id" to "ec:rollback", "type" to "dialogue", "start" to "root", "nodes" to mapOf("root" to mapOf(
            "choices" to listOf(mapOf("id" to "fail", "label" to "失敗", "set_flags" to listOf("ec:changed"), "actions" to listOf(
                mapOf("kind" to "GIVE_ITEM", "target" to "ec:item_con_minor_hp_potion", "once_flag" to "ec:given"),
                mapOf("kind" to "COMPLETE_QUEST", "target" to "ec:quest_m01_awakening")))))))
        val c=content.copy(documents=content.documents+("ec:rollback" to doc))
        val s=SimulationSession({c},GameService({c},SkillEngine(Clock { 1000 },c.balance))); val p=PlayerProfile(UUID.randomUUID())
        s.execute(p,"dialogue",listOf("ec:rollback"))
        val before=p.snapshot(); val bag=s.inventory.contents.toMap()
        assertFails { s.execute(p,"choice",listOf("fail")) }
        assertEquals(before,p); assertEquals(bag,s.inventory.contents)
    }
}
