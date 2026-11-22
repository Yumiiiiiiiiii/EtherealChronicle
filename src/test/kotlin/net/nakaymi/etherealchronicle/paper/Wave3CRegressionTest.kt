package net.nakaymi.etherealchronicle.paper

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.nio.file.Files

/** Wave 3C の非同期所有権チェックを純粋テストで固定します。 */
class Wave3CRegressionTest {
    @Test
    fun `classless players can reopen selection after dialog recovery`() {
        assertTrue(classSelectionCanReopen(null))
        assertFalse(classSelectionCanReopen("ec:class_warrior"))
    }

    @Test
    fun `custom plugin data layout falls back to configured world container`() {
        val root = Files.createTempDirectory("ec-server-root")
        val pluginData = root.resolve("custom-data")
        val worlds = root.resolve("worlds")
        Files.createDirectories(pluginData)
        Files.createDirectories(worlds)
        try {
            assertEquals(worlds, serverRootFromDataFolder(pluginData, worlds))
            Files.createFile(root.resolve("spigot.yml"))
            assertEquals(root, serverRootFromDataFolder(root.resolve("plugins/EtherealChronicle"), worlds))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `treasure chunk lookup floors negative fractional coordinates`() {
        assertEquals(-1 to 0, chunkCoordinates(-0.25, 15.75))
        assertEquals(-2 to -1, chunkCoordinates(-16.01, -0.01))
    }

    @Test
    fun `pack write deadline permits slow but progressing clients`() {
        assertTrue(resourcePackWriteDeadlineMillis(20 * 1024 * 1024, 5_000) >= 150_000)
    }

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
