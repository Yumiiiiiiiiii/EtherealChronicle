package net.nakaymi.etherealchronicle

import net.nakaymi.etherealchronicle.core.Balance
import net.nakaymi.etherealchronicle.core.BalanceDefaults
import net.nakaymi.etherealchronicle.core.LifeBalance
import net.nakaymi.etherealchronicle.core.LifeBalanceDefaults
import net.nakaymi.etherealchronicle.core.StatDefaults
import net.nakaymi.etherealchronicle.core.Stats
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** balanceの省略値と共通stat既定値が単一定義から供給されることを確認します。 */
class GameLimitsTest {
    @Test
    fun `balance and stat defaults are centralized without changing their values`() {
        val balance = Balance(
            levelCap = 10,
            xpBase = 1.0,
            xpExponent = 1.0,
            xpGainCap = 10,
            currencyGainCap = 10,
            damageCap = 10.0,
            statCap = 10.0,
            critCap = 1.0,
            defenseScale = 1.0,
            skillLevelCap = 10,
            skillGrowth = 0.0,
            critDamageCap = 2.0,
            elementCap = 1.0,
        )

        assertEquals(BalanceDefaults.XP_LINEAR, balance.xpLinear)
        assertEquals(BalanceDefaults.XP_QUADRATIC, balance.xpQuadratic)
        assertEquals(BalanceDefaults.XP_ROUNDING, balance.xpRounding)
        assertEquals(BalanceDefaults.SKILL_GCD_MILLIS, balance.skillGcdMillis)
        assertEquals(BalanceDefaults.DROP_PITY_ATTEMPT_CAP, balance.dropPityAttemptCap)
        assertEquals(StatDefaults.HP, Stats().hp)
        assertEquals(StatDefaults.RESOURCE, Stats().resource)
        assertEquals(StatDefaults.ATTACK, Stats().attack)
        assertEquals(LifeBalanceDefaults.LEVEL_CAP, LifeBalance().levelCap)
        assertEquals(LifeBalanceDefaults.CAST_COOLDOWN_MILLIS, LifeBalance().castCooldownMillis)
    }
}
