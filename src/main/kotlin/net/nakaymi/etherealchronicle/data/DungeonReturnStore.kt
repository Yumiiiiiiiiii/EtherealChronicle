package net.nakaymi.etherealchronicle.data

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
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

/** playerdata の schema と独立し、帰還先を単一 IO キューで原子的に保存します。 */
class DungeonReturnStore(private val file: Path) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "ec-dungeon-return-io") }
    private var records: Map<UUID, DungeonReturnRecord>? = null

    /** 永続化済みの帰還先を非同期で取得します。 */
    fun readAll(): CompletableFuture<Map<UUID, DungeonReturnRecord>> =
        CompletableFuture.supplyAsync({ load().toMap() }, executor)

    /** パーティ全員の帰還先を一括保存し、成功後にのみ入場できます。 */
    fun putAll(entries: Map<UUID, DungeonReturnRecord>): CompletableFuture<Void> {
        val snapshot = entries.toMap()
        return CompletableFuture.runAsync({
            val current = load()
            require(snapshot.keys.none { it in current }) { "Player already has a pending dungeon return" }
            val updated = current + snapshot
            write(updated)
            records = updated
        }, executor)
    }

    /** 帰還完了後に記録を削除します。保存失敗時は元の記録を残します。 */
    fun removeAll(ids: Set<UUID>): CompletableFuture<Void> {
        val snapshot = ids.toSet()
        return CompletableFuture.runAsync({
            val updated = load() - snapshot
            write(updated)
            records = updated
        }, executor)
    }

    private fun load(): Map<UUID, DungeonReturnRecord> {
        records?.let { return it }
        if (!Files.exists(file)) return emptyMap<UUID, DungeonReturnRecord>().also { records = it }
        val yaml = Yaml(SafeConstructor(LoaderOptions().apply { isAllowDuplicateKeys = false }))
        val root = Files.newBufferedReader(file).use { yaml.load<Any?>(it) } as? Map<*, *>
            ?: error("Invalid dungeon return journal")
        return root.entries.associate { (key, value) ->
            val id = UUID.fromString(key as? String ?: error("Invalid player UUID"))
            val row = value as? Map<*, *> ?: error("Invalid dungeon return record")
            fun string(name: String) = row[name] as? String ?: error("Missing $name")
            fun number(name: String) = row[name] as? Number ?: error("Missing $name")
            fun integer(name: String): Int = number(name).toString().toIntOrNull() ?: error("Invalid $name")
            id to DungeonReturnRecord(string("dungeonId"), string("world"), number("x").toDouble(),
                number("y").toDouble(), number("z").toDouble(), number("yaw").toFloat(), number("pitch").toFloat(),
                integer("slotX"), integer("slotY"), integer("slotZ"))
        }.also { records = it }
    }

    private fun write(updated: Map<UUID, DungeonReturnRecord>) {
        file.parent?.let(Files::createDirectories)
        val temporary = file.resolveSibling(file.fileName.toString() + ".tmp")
        val backup = file.resolveSibling(file.fileName.toString() + ".bak")
        val json = updated.entries.joinToString(",", prefix = "{", postfix = "}\n") { (id, row) ->
            "${quote(id.toString())}:{\"dungeonId\":${quote(row.dungeonId)},\"world\":${quote(row.world)}," +
                "\"x\":${row.x},\"y\":${row.y},\"z\":${row.z},\"yaw\":${row.yaw},\"pitch\":${row.pitch}," +
                "\"slotX\":${row.slotX},\"slotY\":${row.slotY},\"slotZ\":${row.slotZ}}"
        }
        Files.writeString(temporary, json, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
        FileChannel.open(temporary, StandardOpenOption.WRITE).use { it.force(true) }
        if (Files.exists(file)) Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING)
        Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                in '\u0000'..'\u001f' -> append("\\u%04x".format(char.code))
                else -> append(char)
            }
        }
        append('"')
    }

    /** 進行中の保存を完了してから IO スレッドを終了します。 */
    override fun close() {
        executor.shutdown()
        check(executor.awaitTermination(30, TimeUnit.SECONDS)) { "Dungeon return IO did not finish" }
    }
}
