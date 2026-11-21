package net.nakaymi.etherealchronicle

import net.nakaymi.etherealchronicle.core.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class EnhancementMathTest {
    private val balance=Balance(20,100.0,1.5,10_000,5_000,10_000.0,100.0,.75,100.0,5,.1,3.0,2.0)
    private fun item(attack:Double,budget:Double)=ItemDefinition("ec:item_test","装備","IRON_SWORD",Rarity.COMMON,1,emptyList(),Stats.ZERO.copy(attack=attack),budget)

    @Test fun `enhancement is three percent per step and respects stat and item budgets`() {
        val roomy=item(10.0,100.0).enhancedStats(5,balance,.03)
        assertEquals(11.5,roomy.attack,1e-6)
        val statCapped=item(99.0,1_000.0).enhancedStats(5,balance,.03)
        assertEquals(100.0,statCapped.attack,1e-6)
        val budgetCapped=item(4.9,5.0)
        val raised=budgetCapped.copy(stats=budgetCapped.enhancedStats(5,balance,.03))
        assertTrue(raised.withinBudget())
        assertTrue(raised.stats.attack > budgetCapped.stats.attack)
    }
}
