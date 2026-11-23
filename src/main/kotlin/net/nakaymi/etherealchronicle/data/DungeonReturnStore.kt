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

/** プラグイン自身が空中へ配置し、撤去責務を保持しているスロットの永続証跡です。 */
data class DungeonOwnedSlot(
    val slot: Int,
    val world: String,
    val dungeonId: String,
    val minX: Int,
    val minY: Int,
    val minZ: Int,
) {
    init {
        require(slot >= 0) { "Owned dungeon slot must be non-negative" }
        require(world.isNotBlank() && dungeonId.isNotBlank())
    }
}

/** 帰還記録と所有済み配置スロットをまとめて保持するジャーナル状態です。 */
data class DungeonReturnJournalState(
    val records: Map<UUID, DungeonReturnRecord> = emptyMap(),
    val ownedSlots: Map<Int, DungeonOwnedSlot> = emptyMap(),
)

/** 帰還ジャーナルおよびバックアップの双方が破損し、退避されたことを示す例外です。 */
class DungeonReturnJournalCorruptedException(
    message: String,
    cause: Throwable? = null,
    val quarantinedFiles: List<Path> = emptyList(),
) : IllegalStateException(message, cause)

/**
 * playerdata の schema と独立し、帰還先と所有済み配置スロットを単一 IO キューで原子的に保存します。
 * 主ファイル破損時は `.bak` へ自動フォールバックし、二重破損時は破損ファイルを退避（最新 5 件まで保持）して失敗を返します。
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
    private var state: DungeonReturnJournalState? = null
    private var corrupted = false
    @Volatile
    private var closed = false

    /** 永続化済みの帰還先と所有済み配置スロットを非同期で取得します。 */
    fun readState(): CompletableFuture<DungeonReturnJournalState> =
        submit {
            val loaded = load()
            DungeonReturnJournalState(loaded.records.toMap(), loaded.ownedSlots.toMap())
        }

    /** 永続化済みの帰還先を非同期で取得します。close() 後は失敗済み Future を返します。 */
    fun readAll(): CompletableFuture<Map<UUID, DungeonReturnRecord>> =
        submit { load().records.toMap() }

    /** パーティ全員の帰還先を一括保存し、成功後にのみ入場できます。 */
    fun putAll(entries: Map<UUID, DungeonReturnRecord>): CompletableFuture<Void> {
        val snapshot = entries.toMap()
        return submitVoid {
            val current = load()
            require(snapshot.keys.none { it in current.records }) { "Player already has a pending dungeon return" }
            val updated = current.copy(records = current.records + snapshot)
            write(updated)
            state = updated
        }
    }

    /** 帰還完了後に記録を削除します。保存失敗時は元の記録を残します。 */
    fun removeAll(ids: Set<UUID>): CompletableFuture<Void> {
        val snapshot = ids.toSet()
        return submitVoid {
            val current = load()
            val updated = current.copy(records = current.records - snapshot)
            write(updated)
            state = updated
        }
    }

    /** 自身が配置する空中スロットの所有証跡を永続化します。 */
    fun putOwnedSlot(owned: DungeonOwnedSlot): CompletableFuture<Void> =
        submitVoid {
            val current = load()
            val updated = current.copy(ownedSlots = current.ownedSlots + (owned.slot to owned))
            write(updated)
            state = updated
        }

    /** 撤去完了後に所有済みスロット証跡を削除します。 */
    fun removeOwnedSlot(slot: Int): CompletableFuture<Void> =
        submitVoid {
            val current = load()
            if (slot !in current.ownedSlots) return@submitVoid
            val updated = current.copy(ownedSlots = current.ownedSlots - slot)
            write(updated)
            state = updated
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

    private fun load(): DungeonReturnJournalState {
        check(!corrupted) { "Dungeon return journal is disabled due to corruption" }
        state?.let { return it }
        val backup = backupPath()
        if (!Files.exists(file)) {
            return DungeonReturnJournalState().also { state = it }
        }
        val primaryError = try {
            return parseFile(file).also { state = it }
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
                return recovered.also { state = it }
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
            pruneQuarantinedFiles(source)
            candidate
        }.getOrElse { moveError ->
            onWarning("Could not quarantine corrupted journal ${source.fileName}: ${moveError.message}")
            null
        }
    }

    private fun pruneQuarantinedFiles(source: Path) {
        val parent = source.parent ?: return
        val prefix = "${source.fileName}.corrupt-"
        runCatching {
            val matching = Files.list(parent).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) && it.fileName.toString().startsWith(prefix) }
                    .toList()
            }
            if (matching.size <= MAX_QUARANTINED_FILES) return
            val ordered = matching.sortedWith(
                compareByDescending<Path> { parseQuarantineOrder(it.fileName.toString().removePrefix(prefix)).first }
                    .thenByDescending { parseQuarantineOrder(it.fileName.toString().removePrefix(prefix)).second }
                    .thenByDescending { it.fileName.toString() },
            )
            ordered.drop(MAX_QUARANTINED_FILES).forEach { stale ->
                Files.deleteIfExists(stale)
            }
        }.onFailure { error ->
            onWarning("Could not prune stale quarantined journals for ${source.fileName}: ${error.message}")
        }
    }

    private fun parseQuarantineOrder(suffix: String): Pair<Long, Int> {
        val parts = suffix.split('-', limit = 2)
        val stamp = parts[0].toLongOrNull() ?: Long.MIN_VALUE
        val tieBreaker = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return stamp to tieBreaker
    }

    private fun parseFile(path: Path): DungeonReturnJournalState {
        Files.newBufferedReader(path, StandardCharsets.UTF_8).use { reader ->
            JsonReader(reader).use { json ->
                if (json.peek() != JsonToken.BEGIN_OBJECT) {
                    error("Invalid dungeon return journal: expected JSON object")
                }
                json.beginObject()
                if (!json.hasNext()) {
                    json.endObject()
                    if (json.peek() != JsonToken.END_DOCUMENT) {
                        error("Unexpected trailing content in dungeon return journal")
                    }
                    return DungeonReturnJournalState()
                }
                val firstKey = json.nextName()
                val parsed = if (firstKey in V2_ROOT_KEYS) {
                    parseV2Object(json, firstKey)
                } else {
                    parseV1Object(json, firstKey)
                }
                json.endObject()
                if (json.peek() != JsonToken.END_DOCUMENT) {
                    error("Unexpected trailing content in dungeon return journal")
                }
                return parsed
            }
        }
    }

    private fun parseV1Object(json: JsonReader, firstKey: String): DungeonReturnJournalState {
        val records = linkedMapOf<UUID, DungeonReturnRecord>()
        val firstId = UUID.fromString(firstKey)
        records[firstId] = readRecord(json)
        while (json.hasNext()) {
            val key = json.nextName()
            val id = UUID.fromString(key)
            require(id !in records) { "Duplicate player UUID in dungeon return journal: $id" }
            records[id] = readRecord(json)
        }
        return DungeonReturnJournalState(records = records)
    }

    private fun parseV2Object(json: JsonReader, firstKey: String): DungeonReturnJournalState {
        val seenKeys = mutableSetOf<String>()
        var schemaVersion: Int? = null
        var records: Map<UUID, DungeonReturnRecord> = emptyMap()
        var ownedSlots: Map<Int, DungeonOwnedSlot> = emptyMap()

        fun handleField(key: String) {
            require(seenKeys.add(key)) { "Duplicate root key '$key' in dungeon return journal" }
            when (key) {
                KEY_SCHEMA_VERSION -> {
                    val value = readPrimitive(json, key)
                    require(value.isNumber) { "Invalid $key" }
                    schemaVersion = runCatching { value.asBigDecimal.intValueExact() }
                        .getOrElse { error("Invalid $key") }
                    require(schemaVersion == SCHEMA_VERSION) { "Unsupported dungeon return journal schema: $schemaVersion" }
                }
                KEY_RECORDS -> records = readRecordsMap(json)
                KEY_OWNED_SLOTS -> ownedSlots = readOwnedSlotsMap(json)
                else -> error("Unknown root key '$key' in dungeon return journal")
            }
        }

        handleField(firstKey)
        while (json.hasNext()) {
            handleField(json.nextName())
        }
        return DungeonReturnJournalState(records = records, ownedSlots = ownedSlots)
    }

    private fun readRecordsMap(json: JsonReader): Map<UUID, DungeonReturnRecord> {
        if (json.peek() != JsonToken.BEGIN_OBJECT) {
            error("Invalid dungeon return records: expected JSON object")
        }
        val result = linkedMapOf<UUID, DungeonReturnRecord>()
        json.beginObject()
        while (json.hasNext()) {
            val key = json.nextName()
            val id = UUID.fromString(key)
            require(id !in result) { "Duplicate player UUID in dungeon return journal: $id" }
            result[id] = readRecord(json)
        }
        json.endObject()
        return result
    }

    private fun readOwnedSlotsMap(json: JsonReader): Map<Int, DungeonOwnedSlot> {
        if (json.peek() != JsonToken.BEGIN_OBJECT) {
            error("Invalid dungeon ownedSlots: expected JSON object")
        }
        val result = linkedMapOf<Int, DungeonOwnedSlot>()
        json.beginObject()
        while (json.hasNext()) {
            val key = json.nextName()
            val slotKey = key.toIntOrNull() ?: error("Invalid owned slot key: $key")
            require(slotKey !in result) { "Duplicate owned slot key in dungeon return journal: $slotKey" }
            val owned = readOwnedSlot(json)
            require(owned.slot == slotKey) { "Mismatched owned slot key $slotKey != ${owned.slot}" }
            result[slotKey] = owned
        }
        json.endObject()
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

    private fun readOwnedSlot(json: JsonReader): DungeonOwnedSlot {
        if (json.peek() != JsonToken.BEGIN_OBJECT) {
            error("Invalid dungeon owned slot: expected JSON object")
        }
        val fields = linkedMapOf<String, JsonElement>()
        json.beginObject()
        while (json.hasNext()) {
            val name = json.nextName()
            require(name !in fields) { "Duplicate field '$name' in dungeon owned slot" }
            fields[name] = readPrimitive(json, name)
        }
        json.endObject()

        fun string(name: String): String {
            val element = fields[name] as? JsonPrimitive ?: error("Missing $name")
            require(element.isString) { "Invalid $name" }
            return element.asString
        }

        fun integer(name: String): Int {
            val element = fields[name] as? JsonPrimitive ?: error("Missing $name")
            require(element.isNumber) { "Invalid $name" }
            return runCatching { element.asBigDecimal.intValueExact() }
                .getOrElse { error("Invalid $name") }
        }

        return DungeonOwnedSlot(
            slot = integer("slot"),
            world = string("world"),
            dungeonId = string("dungeonId"),
            minX = integer("minX"),
            minY = integer("minY"),
            minZ = integer("minZ"),
        )
    }

    private fun readPrimitive(json: JsonReader, fieldName: String): JsonPrimitive = when (json.peek()) {
        JsonToken.STRING -> JsonPrimitive(json.nextString())
        JsonToken.NUMBER -> JsonPrimitive(BigDecimal(json.nextString()))
        else -> error("Invalid value type for $fieldName")
    }

    private fun write(updated: DungeonReturnJournalState) {
        file.parent?.let(Files::createDirectories)
        val temporary = file.resolveSibling(file.fileName.toString() + ".tmp")
        val backup = backupPath()
        val root = JsonObject().apply {
            addProperty(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
        }
        val recordsObject = JsonObject()
        updated.records.forEach { (id, row) ->
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
            recordsObject.add(id.toString(), entry)
        }
        root.add(KEY_RECORDS, recordsObject)
        val ownedSlotsObject = JsonObject()
        updated.ownedSlots.forEach { (slot, owned) ->
            val entry = JsonObject().apply {
                addProperty("slot", owned.slot)
                addProperty("world", owned.world)
                addProperty("dungeonId", owned.dungeonId)
                addProperty("minX", owned.minX)
                addProperty("minY", owned.minY)
                addProperty("minZ", owned.minZ)
            }
            ownedSlotsObject.add(slot.toString(), entry)
        }
        root.add(KEY_OWNED_SLOTS, ownedSlotsObject)
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

    companion object {
        const val SCHEMA_VERSION = 2
        const val MAX_QUARANTINED_FILES = 5
        private const val KEY_SCHEMA_VERSION = "schemaVersion"
        private const val KEY_RECORDS = "records"
        private const val KEY_OWNED_SLOTS = "ownedSlots"
        private val V2_ROOT_KEYS = setOf(KEY_SCHEMA_VERSION, KEY_RECORDS, KEY_OWNED_SLOTS)
        private const val WORKER_THREAD_NAME = "ec-dungeon-return-io"
        private const val CLOSE_TIMEOUT_SECONDS = 5L
    }
}
