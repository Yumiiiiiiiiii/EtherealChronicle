package net.nakaymi.etherealchronicle

import net.nakaymi.etherealchronicle.core.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.random.Random

class GatheringEngineTest {
    private fun node(pityAfter: Int = 3) = GatheringNodeDefinition(
        "ec:node_test", "試験鉱脈", NodeKind.ORE, "IRON_ORE", "COBBLESTONE", TackleKind.PICKAXE,
        1, 1, 1000, 10, true, 10, 5,
        listOf(NodeYield("ec:item_mat_iron_scrap",1,1,1.0)),
        listOf(NodeYield("ec:item_mat_stariron_ore",1,1,0.0,pityAfter)), emptyList()
    )

    @Test fun `eligibility requires matching tool tier and life level`() {
        val engine=GatheringEngine(Random(1)); val n=node()
        assertTrue(engine.canHarvest(n,TackleKind.PICKAXE,1,1))
        assertFalse(engine.canHarvest(n,TackleKind.SICKLE,3,20))
        assertFalse(engine.canHarvest(n,TackleKind.PICKAXE,0,20))
        assertFalse(engine.canHarvest(n,TackleKind.PICKAXE,3,0))
    }

    @Test fun `rare gathering yield is pity guaranteed and counter resets`() {
        val engine=GatheringEngine(Random(5)); val pity=mutableMapOf("gathering:ec:node_test:ec:item_mat_stariron_ore" to 2)
        val result=engine.roll(node(3),1,1,false,0.08,pity)
        assertTrue(result.any { it.rare && it.item == "ec:item_mat_stariron_ore" })
        assertEquals(0,pity["gathering:ec:node_test:ec:item_mat_stariron_ore"])
    }

    @Test fun `personal respawn reservation is exactly once and persisted on profile`() {
        val engine=GatheringEngine(Random(1)); val p=PlayerProfile(UUID.randomUUID())
        assertTrue(engine.reserve(p,"ec:node_test/loc_a",60,1000))
        assertFalse(engine.reserve(p,"ec:node_test/loc_a",60,1001))
        assertFalse(engine.readyAt(p,"ec:node_test/loc_a",60_999))
        assertTrue(engine.readyAt(p,"ec:node_test/loc_a",61_000))
        assertEquals(61_000,p.snapshot().gatheringReadyAt["ec:node_test/loc_a"])
    }

    @Test fun `party bonus is bounded and affects only rare chance`() {
        val node=node(10_000)
        val baseline=GatheringEngine(Random(12)).roll(node,1,1,false,0.08).single { !it.rare }
        val withParty=GatheringEngine(Random(12)).roll(node,1,1,true,0.08).single { !it.rare }
        assertEquals(baseline,withParty)
        assertThrows(IllegalArgumentException::class.java) { GatheringEngine(Random(1)).roll(node,1,1,true,0.5) }
    }
}
