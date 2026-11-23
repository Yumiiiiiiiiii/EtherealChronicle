package net.nakaymi.etherealchronicle.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
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

/** ダンジョン帰還ジャーナルの一括保存、所有スロット永続化、v1 後方互換、破損退避上限と終了後挙動を確認します。 */
class DungeonReturnStoreTest {
    @Test
    fun `party return records and owned slots persist atomically across store restart`(@TempDir directory: Path) {
        val file = directory.resolve("returns.yml")
        val records = linkedMapOf(
            UUID.randomUUID() to record("ec:dungeon_novice_arena", slotX = 100, slotZ = -200),
            UUID.randomUUID() to record("ec:dungeon_void_sanctum", slotX = 356, slotZ = -200),
        )
        val owned0 = DungeonOwnedSlot(
            slot = 0,
            world = "world",
            dungeonId = "ec:dungeon_novice_arena",
            minX = 73,
            minY = 210,
            minZ = -227,
        )

        DungeonReturnStore(file).use { store ->
            store.putOwnedSlot(owned0).await()
            store.putAll(records).await()
            assertEquals(records, store.readAll().await())
            val state = store.readState().await()
            assertEquals(records, state.records)
            assertEquals(mapOf(0 to owned0), state.ownedSlots)
            assertTrue(Files.exists(file))
            assertTrue(Files.exists(file.resolveSibling("returns.yml.tmp")).not())
        }

        DungeonReturnStore(file).use { restarted ->
            val state = restarted.readState().await()
            assertEquals(records, state.records)
            assertEquals(mapOf(0 to owned0), state.ownedSlots)
            restarted.removeAll(records.keys.take(1).toSet()).await()
            restarted.removeOwnedSlot(0).await()
        }

        DungeonReturnStore(file).use { restartedAgain ->
            val state = restartedAgain.readState().await()
            assertEquals(records.keys.drop(1).associateWith(records::get), state.records)
            assertEquals(emptyMap<Int, DungeonOwnedSlot>(), state.ownedSlots)
        }
        assertTrue(Files.exists(file.resolveSibling("returns.yml.bak")))
    }

    @Test
    fun `legacy v1 journal format loads cleanly and upgrades to v2 on write`(@TempDir directory: Path) {
        val file = directory.resolve("returns.json")
        val player = UUID.randomUUID()
        val expected = record("ec:dungeon_novice_arena", slotX = 512, slotZ = 512)
        Files.writeString(
            file,
            """{"$player":{"dungeonId":"ec:dungeon_novice_arena","world":"world","x":12.5,"y":70.0,"z":-8.25,"yaw":90.0,"pitch":0.0,"slotX":512,"slotY":220,"slotZ":512}}""",
        )

        val owned = DungeonOwnedSlot(0, "world", "ec:dungeon_novice_arena", 485, 210, 485)
        DungeonReturnStore(file).use { store ->
            val loaded = store.readState().await()
            assertEquals(mapOf(player to expected), loaded.records)
            assertEquals(emptyMap<Int, DungeonOwnedSlot>(), loaded.ownedSlots)
            store.putOwnedSlot(owned).await()
        }

        DungeonReturnStore(file).use { restarted ->
            val loaded = restarted.readState().await()
            assertEquals(mapOf(player to expected), loaded.records)
            assertEquals(mapOf(0 to owned), loaded.ownedSlots)
        }
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
    fun `invalid journal data without backup quarantines file and fails closed`(@TempDir directory: Path) {
        val file = directory.resolve("returns.yml")
        Files.writeString(file, "not: [a valid journal")
        val warnings = mutableListOf<String>()

        DungeonReturnStore(file, warnings::add).use { store ->
            val failure = assertThrows(ExecutionException::class.java) { store.readAll().await() }
            val cause = assertInstanceOf(DungeonReturnJournalCorruptedException::class.java, failure.cause)
            assertEquals(1, cause.quarantinedFiles.size)
            assertEquals("not: [a valid journal", Files.readString(cause.quarantinedFiles.single()))
            assertFalse(Files.exists(file))
            assertTrue(warnings.isNotEmpty())
            // 同一インスタンス内では以降の書き込みも fail-closed のまま拒否される
            assertThrows(ExecutionException::class.java) {
                store.putAll(mapOf(UUID.randomUUID() to record("ec:dungeon_novice_arena", 100, 100))).await()
            }
        }
    }

    @Test
    fun `quarantined corrupt files are capped to latest five`(@TempDir directory: Path) {
        val file = directory.resolve("returns.json")
        repeat(7) { attempt ->
            Files.writeString(file, "{corrupt-$attempt")
            DungeonReturnStore(file).use { store ->
                assertThrows(ExecutionException::class.java) { store.readState().await() }
            }
        }
        val quarantined = Files.list(directory).use { stream ->
            stream.filter { it.fileName.toString().startsWith("returns.json.corrupt-") }.toList()
        }
        assertEquals(DungeonReturnStore.MAX_QUARANTINED_FILES, quarantined.size)
        val contents = quarantined.map(Files::readString).toSet()
        assertTrue("{corrupt-6" in contents, "Latest quarantined file must be kept: $contents")
        assertFalse("{corrupt-0" in contents, "Oldest quarantined file must be pruned: $contents")
    }

    @Test
    fun `corrupted primary journal falls back to valid backup and restores primary`(@TempDir directory: Path) {
        val file = directory.resolve("returns.yml")
        val backup = file.resolveSibling("returns.yml.bak")
        val p1 = UUID.randomUUID()
        val p2 = UUID.randomUUID()
        val r1 = record("ec:dungeon_novice_arena", slotX = 100, slotZ = 100)
        val r2 = record("ec:dungeon_void_sanctum", slotX = 356, slotZ = 100)

        DungeonReturnStore(file).use { store ->
            store.putAll(mapOf(p1 to r1)).await()
            // 2回目の書き込みで r1 のみの状態が .bak に退避される
            store.putAll(mapOf(p2 to r2)).await()
        }
        assertTrue(Files.exists(backup))
        Files.writeString(file, "{broken json")

        val warnings = mutableListOf<String>()
        DungeonReturnStore(file, warnings::add).use { recovered ->
            assertEquals(mapOf(p1 to r1), recovered.readAll().await())
            assertTrue(warnings.any { it.contains("restored from backup") })
        }
        // 主ファイルが .bak から復旧され、破損ファイルも退避されている
        assertTrue(Files.exists(file))
        val quarantined = Files.list(directory).use { stream ->
            stream.filter { it.fileName.toString().startsWith("returns.yml.corrupt-") }.toList()
        }
        assertEquals(1, quarantined.size)
        assertEquals("{broken json", Files.readString(quarantined.single()))
    }

    @Test
    fun `corrupted primary and backup both quarantine and fail closed`(@TempDir directory: Path) {
        val file = directory.resolve("returns.yml")
        val backup = file.resolveSibling("returns.yml.bak")
        Files.writeString(file, "{corrupt primary")
        Files.writeString(backup, "{corrupt backup")
        val warnings = mutableListOf<String>()

        DungeonReturnStore(file, warnings::add).use { store ->
            val failure = assertThrows(ExecutionException::class.java) { store.readAll().await() }
            val cause = assertInstanceOf(DungeonReturnJournalCorruptedException::class.java, failure.cause)
            assertEquals(2, cause.quarantinedFiles.size)
            assertEquals(setOf("{corrupt primary", "{corrupt backup"), cause.quarantinedFiles.map(Files::readString).toSet())
            assertFalse(Files.exists(file))
            assertFalse(Files.exists(backup))
            assertTrue(warnings.isNotEmpty())
        }
    }

    @Test
    fun `operations after close return failed CompletableFuture without throwing synchronously`(@TempDir directory: Path) {
        val file = directory.resolve("returns.yml")
        val store = DungeonReturnStore(file)
        store.close()
        // 二重 close も安全に無視される
        store.close()

        val readFuture = store.readAll()
        val readStateFuture = store.readState()
        val putFuture = store.putAll(mapOf(UUID.randomUUID() to record("ec:dungeon_novice_arena", 100, 100)))
        val removeFuture = store.removeAll(setOf(UUID.randomUUID()))
        val putOwnedFuture = store.putOwnedSlot(DungeonOwnedSlot(0, "world", "ec:dungeon_novice_arena", 0, 210, 0))
        val removeOwnedFuture = store.removeOwnedSlot(0)

        assertTrue(readFuture.isCompletedExceptionally)
        assertTrue(readStateFuture.isCompletedExceptionally)
        assertTrue(putFuture.isCompletedExceptionally)
        assertTrue(removeFuture.isCompletedExceptionally)
        assertTrue(putOwnedFuture.isCompletedExceptionally)
        assertTrue(removeOwnedFuture.isCompletedExceptionally)
    }

    @Test
    fun `strict json validation rejects trailing content and non-integer slot coordinates`(@TempDir directory: Path) {
        val player = UUID.randomUUID()
        val trailingFile = directory.resolve("trailing.yml")
        Files.writeString(trailingFile, "{} trailing")
        DungeonReturnStore(trailingFile).use { store ->
            assertThrows(ExecutionException::class.java) { store.readAll().await() }
        }

        val floatSlotFile = directory.resolve("float-slot.yml")
        Files.writeString(
            floatSlotFile,
            """{"$player":{"dungeonId":"ec:d","world":"world","x":1.0,"y":65.0,"z":1.0,"yaw":0.0,"pitch":0.0,"slotX":10.5,"slotY":220,"slotZ":10}}""",
        )
        DungeonReturnStore(floatSlotFile).use { store ->
            assertThrows(ExecutionException::class.java) { store.readAll().await() }
        }
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
