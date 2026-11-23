package net.nakaymi.etherealchronicle.data

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.math.BigDecimal
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/** クラッシュ後にプレイヤーを地上へ戻すための独立した保存データです。 */
data class DungeonReturnRecord(
    val dungeonId: String,
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val pitch: Float,
    val slotX: Int,
    val slotY: Int,
    val slotZ: Int,
) {
    init {
        require(dungeonId.isNotBlank() && world.isNotBlank())
        require(x.isFinite() && y.isFinite() && z.isFinite() && yaw.isFinite() && pitch.isFinite())
    }
}

/** 帰還ジャーナルおよびバックアップの双方が破損し、退避されたことを示す例外です。 */
class DungeonReturnJournalCorruptedException(
    message: String,
    cause: Throwable? = null,
    val quarantinedFiles: List<Path> = emptyList(),
) : IllegalStateException(message, cause)

/**
 * playerdata の schema と独立し、帰還先を単一 IO キューで原子的に保存します。
 * 主ファイル破損時は `.bak` へ自動フォールバックし、二重破損時は破損ファイルを退避して失敗を返します。
 */
class DungeonReturnStore(
    private val file: Path,
    private val onWarning: (String) -> Unit = {},
) : AutoCloseable {
    private val lifecycleLock = Any()
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, WORKER_THREAD_NAME).apply { isDaemon = true }
    }
    private val gson = GsonBuilder().disableHtmlEscaping().create()
    private var records: Map<UUID, DungeonReturnRecord>? = null
    private var corrupted = false
    @Volatile
    private var closed = false

    /** 永続化済みの帰還先を非同期で取得します。close() 後は失敗済み Future を返します。 */
    fun readAll(): CompletableFuture<Map<UUID, DungeonReturnRecord>> =
        submit { load().toMap() }

    /** パーティ全員の帰還先を一括保存し、成功後にのみ入場できます。 */
    fun putAll(entries: Map<UUID, DungeonReturnRecord>): CompletableFuture<Void> {
        val snapshot = entries.toMap()
        return submitVoid {
            val current = load()
            require(snapshot.keys.none { it in current }) { "Player already has a pending dungeon return" }
            val updated = current + snapshot
            write(updated)
            records = updated
        }
    }

    /** 帰還完了後に記録を削除します。保存失敗時は元の記録を残します。 */
    fun removeAll(ids: Set<UUID>): CompletableFuture<Void> {
        val snapshot = ids.toSet()
        return submitVoid {
            val updated = load() - snapshot
            write(updated)
            records = updated
        }
    }

    private fun <T> submit(task: () -> T): CompletableFuture<T> = synchronized(lifecycleLock) {
        if (closed) {
            return CompletableFuture.failedFuture(IllegalStateException("Dungeon return store is closed"))
        }
        try {
            CompletableFuture.supplyAsync(task, executor)
        } catch (rejected: RejectedExecutionException) {
            CompletableFuture.failedFuture(IllegalStateException("Dungeon return store is closed", rejected))
        }
    }

    private fun submitVoid(task: () -> Unit): CompletableFuture<Void> =
        submit(task).thenApply { null }

    private fun load(): Map<UUID, DungeonReturnRecord> {
        check(!corrupted) { "Dungeon return journal is disabled due to corruption" }
        records?.let { return it }
        val backup = backupPath()
        if (!Files.exists(file)) {
            return emptyMap<UUID, DungeonReturnRecord>().also { records = it }
        }
        val primaryError = try {
            return parseFile(file).also { records = it }
        } catch (error: Exception) {
            error
        }
        if (Files.exists(backup)) {
            val backupError = try {
                val recovered = parseFile(backup)
                val quarantinedPrimary = quarantineFile(file)
                runCatching { Files.copy(backup, file, StandardCopyOption.REPLACE_EXISTING) }
                    .onFailure { copyError ->
                        onWarning("Restoring primary dungeon return journal from backup failed: ${copyError.message}")
                    }
                onWarning(
                    "Primary dungeon return journal was corrupted (${primaryError.message}); " +
                        "restored from backup ${backup.fileName}" +
                        (quarantinedPrimary?.let { " and quarantined primary to ${it.fileName}" } ?: ""),
                )
                return recovered.also { records = it }
            } catch (error: Exception) {
                error
            }
            val quarantined = listOfNotNull(quarantineFile(file), quarantineFile(backup))
            corrupted = true
            val message = "Both primary and backup dungeon return journals are corrupted: " +
                "primary=${primaryError.message}, backup=${backupError.message}"
            onWarning("$message; quarantined=${quarantined.joinToString { it.fileName.toString() }}")
            throw DungeonReturnJournalCorruptedException(message, backupError, quarantined)
        }
        val quarantined = listOfNotNull(quarantineFile(file))
        corrupted = true
        val message = "Dungeon return journal is corrupted and no backup is available: ${primaryError.message}"
        onWarning("$message; quarantined=${quarantined.joinToString { it.fileName.toString() }}")
        throw DungeonReturnJournalCorruptedException(message, primaryError, quarantined)
    }

    private fun backupPath(): Path = file.resolveSibling(file.fileName.toString() + ".bak")

    private fun quarantineFile(source: Path): Path? {
        if (!Files.exists(source)) return null
        val stamp = System.currentTimeMillis()
        var candidate = source.resolveSibling("${source.fileName}.corrupt-$stamp")
        var suffix = 1
        while (Files.exists(candidate)) {
            candidate = source.resolveSibling("${source.fileName}.corrupt-$stamp-$suffix")
            suffix++
        }
        return runCatching {
            Files.move(source, candidate, StandardCopyOption.REPLACE_EXISTING)
            candidate
        }.getOrElse { moveError ->
            onWarning("Could not quarantine corrupted journal ${source.fileName}: ${moveError.message}")
            null
        }
    }

    private fun parseFile(path: Path): Map<UUID, DungeonReturnRecord> {
        val result = linkedMapOf<UUID, DungeonReturnRecord>()
        Files.newBufferedReader(path, StandardCharsets.UTF_8).use { reader ->
            JsonReader(reader).use { json ->
                if (json.peek() != JsonToken.BEGIN_OBJECT) {
                    error("Invalid dungeon return journal: expected JSON object")
                }
                json.beginObject()
                while (json.hasNext()) {
                    val key = json.nextName()
                    val id = UUID.fromString(key)
                    require(id !in result) { "Duplicate player UUID in dungeon return journal: $id" }
                    result[id] = readRecord(json)
                }
                json.endObject()
                if (json.peek() != JsonToken.END_DOCUMENT) {
                    error("Unexpected trailing content in dungeon return journal")
                }
            }
        }
        return result
    }

    private fun readRecord(json: JsonReader): DungeonReturnRecord {
        if (json.peek() != JsonToken.BEGIN_OBJECT) {
            error("Invalid dungeon return record: expected JSON object")
        }
        val fields = linkedMapOf<String, JsonElement>()
        json.beginObject()
        while (json.hasNext()) {
            val name = json.nextName()
            require(name !in fields) { "Duplicate field '$name' in dungeon return record" }
            fields[name] = readPrimitive(json, name)
        }
        json.endObject()

        fun string(name: String): String {
            val element = fields[name] as? JsonPrimitive ?: error("Missing $name")
            require(element.isString) { "Invalid $name" }
            return element.asString
        }

        fun number(name: String): BigDecimal {
            val element = fields[name] as? JsonPrimitive ?: error("Missing $name")
            require(element.isNumber) { "Invalid $name" }
            return element.asBigDecimal
        }

        fun integer(name: String): Int = runCatching { number(name).intValueExact() }
            .getOrElse { error("Invalid $name") }

        return DungeonReturnRecord(
            dungeonId = string("dungeonId"),
            world = string("world"),
            x = number("x").toDouble(),
            y = number("y").toDouble(),
            z = number("z").toDouble(),
            yaw = number("yaw").toFloat(),
            pitch = number("pitch").toFloat(),
            slotX = integer("slotX"),
            slotY = integer("slotY"),
            slotZ = integer("slotZ"),
        )
    }

    private fun readPrimitive(json: JsonReader, fieldName: String): JsonPrimitive = when (json.peek()) {
        JsonToken.STRING -> JsonPrimitive(json.nextString())
        JsonToken.NUMBER -> JsonPrimitive(BigDecimal(json.nextString()))
        else -> error("Invalid value type for $fieldName")
    }

    private fun write(updated: Map<UUID, DungeonReturnRecord>) {
        file.parent?.let(Files::createDirectories)
        val temporary = file.resolveSibling(file.fileName.toString() + ".tmp")
        val backup = backupPath()
        val root = JsonObject()
        updated.forEach { (id, row) ->
            val entry = JsonObject().apply {
                addProperty("dungeonId", row.dungeonId)
                addProperty("world", row.world)
                addProperty("x", row.x)
                addProperty("y", row.y)
                addProperty("z", row.z)
                addProperty("yaw", row.yaw)
                addProperty("pitch", row.pitch)
                addProperty("slotX", row.slotX)
                addProperty("slotY", row.slotY)
                addProperty("slotZ", row.slotZ)
            }
            root.add(id.toString(), entry)
        }
        val payload = gson.toJson(root) + "\n"
        Files.writeString(
            temporary,
            payload,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )
        FileChannel.open(temporary, StandardOpenOption.WRITE).use { it.force(true) }
        if (Files.exists(file)) Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING)
        Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    /** 新しい操作を受け付けず、投入済みの保存を短時間待機してから IO スレッドを閉じます。 */
    override fun close() {
        synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            executor.shutdown()
        }
        try {
            if (!executor.awaitTermination(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                executor.shutdownNow()
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            executor.shutdownNow()
        }
    }

    private companion object {
        const val WORKER_THREAD_NAME = "ec-dungeon-return-io"
        const val CLOSE_TIMEOUT_SECONDS = 5L
    }
}
