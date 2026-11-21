package net.nakaymi.etherealchronicle.qa

import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Controllable [Clock] for cooldown / expiry / autosave tests. Starts at a fixed instant, only moves when told. */
class FakeClock(start: Instant = Instant.parse("2026-01-01T00:00:00Z"), private val zone: ZoneId = ZoneOffset.UTC) : Clock() {
    @Volatile
    private var now: Instant = start

    override fun getZone(): ZoneId = zone
    override fun withZone(zone: ZoneId): Clock = FakeClock(now, zone)
    override fun instant(): Instant = now

    fun advance(d: Duration) { now = now.plus(d) }
    fun advanceMillis(ms: Long) = advance(Duration.ofMillis(ms))
    fun advanceSeconds(s: Long) = advance(Duration.ofSeconds(s))
    /** One Minecraft tick = 50 ms. */
    fun advanceTicks(t: Long) = advanceMillis(t * 50)
    fun set(i: Instant) { now = i }
}

/**
 * Builds YAML content text for the data loader/validator tests without a YAML library.
 * Content layout rules (AGENTS.md): top-level `schema_version`, stable snake_case/namespaced `id`s.
 *
 * ```
 * ContentYaml.file(schemaVersion = 1, "items" to listOf(item("ec:wooden_sword", "type" to "weapon")))
 * ```
 */
object ContentYaml {
    fun file(schemaVersion: Int = 1, vararg top: Pair<String, Any?>): String =
        render(linkedMapOf<String, Any?>("schema_version" to schemaVersion).apply { top.forEach { put(it.first, it.second) } })

    /** An entry with a mandatory id plus arbitrary fields. */
    fun entry(id: String, vararg fields: Pair<String, Any?>): Map<String, Any?> =
        linkedMapOf<String, Any?>("id" to id).apply { fields.forEach { put(it.first, it.second) } }

    fun render(root: Map<String, Any?>): String = buildString { writeMap(root, 0) }

    private fun StringBuilder.writeMap(m: Map<*, *>, indent: Int) {
        for ((k, v) in m) {
            append(" ".repeat(indent)).append(k).append(':')
            writeValue(v, indent)
        }
    }

    private fun StringBuilder.writeValue(v: Any?, indent: Int) {
        when (v) {
            is Map<*, *> -> if (v.isEmpty()) append(" {}\n") else { append('\n'); writeMap(v, indent + 2) }
            is List<*> -> if (v.isEmpty()) append(" []\n") else {
                append('\n')
                for (e in v) {
                    append(" ".repeat(indent + 2)).append('-')
                    when (e) {
                        is Map<*, *> -> if (e.isEmpty()) append(" {}\n") else {
                            // first key inline after "- ", rest aligned
                            val sb = StringBuilder().also { it.writeMap(e, indent + 4) }
                            append(' ').append(sb.toString().trimStart())
                        }
                        else -> { append(' ').append(scalar(e)).append('\n') }
                    }
                }
            }
            else -> append(' ').append(scalar(v)).append('\n')
        }
    }

    private fun scalar(v: Any?): String = when (v) {
        null -> "null"
        is Number, is Boolean -> v.toString()
        else -> "\"" + v.toString().replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
    }
}

/** Temp directory that is removed on [close]; use `use { }` for per-test playerdata/content folders. */
class TempDir(prefix: String = "ec-qa-") : AutoCloseable {
    val path: Path = Files.createTempDirectory(prefix)
    fun resolve(name: String): Path = path.resolve(name)
    fun write(rel: String, text: String): Path =
        path.resolve(rel).also { Files.createDirectories(it.parent); Files.writeString(it, text) }
    override fun close() { path.toFile().deleteRecursively() }
}

/** Deterministic [java.util.Random] source so crit/drop tests are repeatable. */
fun seededRandom(seed: Long = 12345L): java.util.Random = java.util.Random(seed)

/** Fixed fake player UUIDs. */
object FakePlayers {
    val ALICE: java.util.UUID = java.util.UUID.fromString("00000000-0000-0000-0000-00000000a11c")
    val BOB: java.util.UUID = java.util.UUID.fromString("00000000-0000-0000-0000-0000000000b0")
}

