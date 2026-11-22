package net.nakaymi.etherealchronicle.paper

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Wave 3C の非同期所有権チェックを純粋テストで固定します。 */
class Wave3CRegressionTest {
    @Test
    fun `older mailbox callback cannot remove a newer claim`() {
        val oldClaim = String(charArrayOf('c', 'l', 'a', 'i', 'm'))
        val newClaim = String(charArrayOf('c', 'l', 'a', 'i', 'm'))

        assertFalse(isCurrentDelivery(newClaim, oldClaim))
        assertTrue(isCurrentDelivery(newClaim, newClaim))
    }

    @Test
    fun `profile rollback never overwrites an online replacement session`() {
        assertFalse(ProfileTransactionGuard.shouldRollback(sameSession = false, currentSessionOnline = true))
        assertTrue(ProfileTransactionGuard.shouldRollback(sameSession = false, currentSessionOnline = false))
        assertTrue(ProfileTransactionGuard.shouldRollback(sameSession = true, currentSessionOnline = true))
        assertFalse(ProfileTransactionGuard.shouldRunSuccess(sameSession = false))
    }
}
