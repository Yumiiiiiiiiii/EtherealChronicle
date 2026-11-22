package net.nakaymi.etherealchronicle.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/** ダンジョン帰還ジャーナルの一括保存、再起動と失敗時保持を確認します。 */
class DungeonReturnStoreTest {
    @Test
    fun `party return records persist atomically across store restart`(@TempDir directory: Path) {
        val file = directory.resolve("returns.yml")
        val records = linkedMapOf(
            UUID.randomUUID() to record("ec:dungeon_novice_arena", slotX = 100, slotZ = -200),
            UUID.randomUUID() to record("ec:dungeon_void_sanctum", slotX = 356, slotZ = -200),
        )

        DungeonReturnStore(file).use { store ->
            store.putAll(records).await()
            assertEquals(records, store.readAll().await())
            assertTrue(Files.exists(file))
            assertTrue(Files.exists(file.resolveSibling("returns.yml.bak")).not())
            assertTrue(Files.exists(file.resolveSibling("returns.yml.tmp")).not())
        }

        DungeonReturnStore(file).use { restarted ->
            assertEquals(records, restarted.readAll().await())
            restarted.removeAll(records.keys.take(1).toSet()).await()
        }

        DungeonReturnStore(file).use { restartedAgain ->
            assertEquals(records.keys.drop(1).associateWith(records::get), restartedAgain.readAll().await())
        }
        assertTrue(Files.exists(file.resolveSibling("returns.yml.bak")))
    }

    @Test
    fun `existing pending player cannot be overwritten by a later party batch`(@TempDir directory: Path) {
        val file = directory.resolve("returns.yml")
        val player = UUID.randomUUID()
        val firstRecord = record("ec:dungeon_novice_arena", slotX = 100, slotZ = 100)
        val otherRecord = record("ec:dungeon_void_sanctum", slotX = 500, slotZ = 100)

        DungeonReturnStore(file).use { store ->
            store.putAll(mapOf(player to firstRecord)).await()
            assertThrows(ExecutionException::class.java) {
                store.putAll(mapOf(player to otherRecord)).await()
            }
            assertEquals(mapOf(player to firstRecord), store.readAll().await())
        }

        DungeonReturnStore(file).use { restarted ->
            assertEquals(mapOf(player to firstRecord), restarted.readAll().await())
        }
    }

    @Test
    fun `invalid journal data fails closed without returning partial records`(@TempDir directory: Path) {
        val file = directory.resolve("returns.yml")
        Files.writeString(file, "not: [a valid journal")

        DungeonReturnStore(file).use { store ->
            assertThrows(ExecutionException::class.java) { store.readAll().await() }
        }
        assertEquals("not: [a valid journal", Files.readString(file))
    }

    @Test
    fun `failed write retains old records in memory and after restart`(@TempDir directory: Path) {
        val file = directory.resolve("returns.yml")
        val player = UUID.randomUUID()
        val existing = record("ec:dungeon_novice_arena", slotX = 100, slotZ = 100)
        val temporary = file.resolveSibling("returns.yml.tmp")

        DungeonReturnStore(file).use { store ->
            store.putAll(mapOf(player to existing)).await()
            Files.createDirectory(temporary)

            assertThrows(ExecutionException::class.java) { store.removeAll(setOf(player)).await() }
            assertEquals(mapOf(player to existing), store.readAll().await())
        }

        Files.delete(temporary)
        DungeonReturnStore(file).use { restarted ->
            assertEquals(mapOf(player to existing), restarted.readAll().await())
        }
    }

    private fun record(dungeonId: String, slotX: Int, slotZ: Int) = DungeonReturnRecord(
        dungeonId = dungeonId,
        world = "world",
        x = 12.5,
        y = 70.0,
        z = -8.25,
        yaw = 90.0f,
        pitch = 0.0f,
        slotX = slotX,
        slotY = 220,
        slotZ = slotZ,
    )

    private fun <T> CompletableFuture<T>.await(): T = get(5, TimeUnit.SECONDS)
}
