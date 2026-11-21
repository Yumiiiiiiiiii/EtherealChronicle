package net.nakaymi.etherealchronicle

import net.nakaymi.etherealchronicle.core.*
import net.nakaymi.etherealchronicle.data.PlayerStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*

class CodexSystemTest {
    @TempDir lateinit var directory:Path

    private fun enemy()=EnemyDefinition(
        id="ec:enemy_test",name="試験魔物",entity="ZOMBIE",level=1,hp=10.0,attack=1.0,defense=0.0,
        element="PHYSICAL",weaknesses=setOf("FIRE"),resistances=emptySet(),speed=.2,size=1,aggro=8.0,leash=16.0,
        attackInterval=40,traits=emptySet(),specials=emptyList(),spawns=listOf(SpawnPoint("test",Position("ec_world",5.0,65.0,5.0),1,20)),
        flags=emptySet(),boss=false,phases=emptyList(),enrageTicks=100,enrageMultiplier=1.5,coopHp=.5,coopDamage=.1)

    @Test fun `enemy discovery increments kills and raises each newly crossed area milestone once`() {
        val area=Area("ec:area_test","試験地域","ec_world",0.0,10.0,60.0,80.0,0.0,10.0,false)
        val profile=PlayerProfile(UUID.randomUUID())
        val unlocked=profile.recordEnemyKill("ec:enemy_test",mapOf(area.id to area),mapOf("ec:enemy_test" to enemy()))
        assertEquals(1,profile.bestiary["ec:enemy_test"])
        assertEquals(setOf(25,50,75,100).map { "ec:flag_codex_test_$it" }.toSet(),unlocked)
        assertTrue(profile.recordEnemyKill("ec:enemy_test",mapOf(area.id to area),mapOf("ec:enemy_test" to enemy())).isEmpty())
        assertEquals(2,profile.bestiary["ec:enemy_test"])
    }

    @Test fun `bestiary and item discoveries survive atomic player save and schema migration`() {
        val p=PlayerProfile(UUID.randomUUID())
        p.bestiary["ec:enemy_test"]=12
        assertTrue(p.discoverItem("ec:item_test")); assertFalse(p.discoverItem("ec:item_test"))
        PlayerStore(directory).use { store ->
            assertEquals(10,store.migrate(mapOf("schema_version" to 8))["schema_version"])
            store.save(p).join()
            val loaded=store.load(p.uuid).join()
            assertEquals(12,loaded.bestiary["ec:enemy_test"])
            assertEquals(setOf("ec:item_test"),loaded.discoveredItems)
        }
    }
}
