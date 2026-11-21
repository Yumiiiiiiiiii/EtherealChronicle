package net.nakaymi.etherealchronicle

import net.nakaymi.etherealchronicle.data.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.*
import kotlin.test.*

class WorldDiagnosticsTest {
    @TempDir lateinit var root: Path
    private fun invalid(file: String, before: String, after: String): String {
        val source=Path.of("src/main/resources/content")
        Files.walk(source).use { paths -> paths.filter { Files.isRegularFile(it) }.forEach {
            val dest=root.resolve(source.relativize(it)); Files.createDirectories(dest.parent); Files.copy(it,dest)
        } }
        val target=root.resolve(file); val text=Files.readString(target); require(before in text)
        Files.writeString(target,text.replace(before,after))
        val error=assertFailsWith<ContentException> { ContentService(root).load() }
        assertContains(error.message!!,target.toString()); return error.message!!
    }
    @Test fun `objective reference must match objective kind`() {
        val message=invalid("quests/m01_awakening.yml","target: 'ec:npc_celia'","target: 'ec:item_con_minor_hp_potion'")
        assertContains(message,"ec:quest_m01_awakening"); assertContains(message,"objectives.TALK")
    }
    @Test fun `condition quest reference cannot point at an item`() {
        val message=invalid("dialogues/celia_hub.yml","completed_quests: ['ec:quest_m01_awakening']","completed_quests: ['ec:item_con_minor_hp_potion']")
        assertContains(message,"ec:dlg_celia_hub"); assertContains(message,"condition.quest")
    }
    @Test fun `malformed objective list is rejected with source rather than using legacy defaults`() {
        val message=invalid("quests/m01_awakening.yml","objectives:","objectives: invalid\nunused_objectives:")
        assertContains(message,"ec:quest_m01_awakening"); assertContains(message,"objectives")
    }
    @Test fun `stat strings are rejected instead of silently replaced with defaults`() {
        val message=invalid("classes/warrior.yml","attack: 14","attack: invalid")
        assertContains(message,"ec:class_warrior"); assertContains(message,"attack")
    }
}
