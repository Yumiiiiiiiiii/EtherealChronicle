package dev.mcmmo.rpg.data

import dev.mcmmo.rpg.core.PlayerProfile
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.nio.channels.FileChannel
import java.nio.file.*
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One IO queue serializes loads and saves, including reconnects. Callers supply detached snapshots. */
class PlayerStore(private val directory: Path): AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r,"ec-player-io") }
    init { Files.createDirectories(directory) }
    fun load(uuid: UUID): CompletableFuture<PlayerProfile> = CompletableFuture.supplyAsync({
        val file = directory.resolve("$uuid.yml")
        if (!Files.exists(file)) PlayerProfile(uuid) else try { read(file,uuid) } catch(primary: Exception) {
            val backup = directory.resolve("$uuid.yml.bak")
            if (Files.exists(backup)) read(backup,uuid) else throw primary
        }
    },executor)
    fun save(profile: PlayerProfile): CompletableFuture<Void> {
        val detached = profile.snapshot()
        return CompletableFuture.runAsync({ write(detached) },executor)
    }
    private fun read(file: Path, uuid: UUID): PlayerProfile {
        val yaml = Yaml(SafeConstructor(LoaderOptions().apply { isAllowDuplicateKeys = false }))
        @Suppress("UNCHECKED_CAST")
        val raw = Files.newBufferedReader(file).use { yaml.load<Any>(it) } as Map<String,Any?>
        val d = migrate(raw)
        require(UUID.fromString(d.string("uuid")) == uuid) { "UUID mismatch" }
        val p = PlayerProfile(uuid,d["class"] as? String,d.int("level"),d.long("xp"),d.long("currency"),d.num("resource"),d.int("selected_skill"),d["starter_granted"] as Boolean,d["tutorial_quest"] as? String)
        require(p.level >= 1 && p.xp >= 0 && p.currency >= 0 && p.resource.isFinite() && p.resource >= 0 && p.selectedSkill in 0..3)
        (d["skill_levels"] as? Map<*,*>)?.forEach { (k,v) -> p.skillLevels[k as String] = (v as Number).toInt().also { require(it >= 1) } }
        return p
    }
    fun migrate(raw: Map<String,Any?>): Map<String,Any?> {
        val version = (raw["schema_version"] as? Number)?.toInt() ?: 0
        require(version in 0..1) { "Unsupported player schema $version" }
        return if (version == 1) raw else mapOf("schema_version" to 1,"level" to 1,"xp" to 0,"currency" to 0,
            "resource" to 0,"selected_skill" to 0,"starter_granted" to false,"skill_levels" to emptyMap<String,Int>()) + raw + ("schema_version" to 1)
    }
    private fun write(p: PlayerProfile) {
        val target = directory.resolve("${p.uuid}.yml")
        val tmp = directory.resolve("${p.uuid}.yml.tmp")
        val values = linkedMapOf<String,Any?>("schema_version" to 1,"uuid" to p.uuid.toString(),"class" to p.classId,
            "level" to p.level,"xp" to p.xp,"currency" to p.currency,"resource" to p.resource,"selected_skill" to p.selectedSkill,
            "starter_granted" to p.starterGranted,"tutorial_quest" to p.tutorialQuest,"skill_levels" to p.skillLevels)
        Files.newBufferedWriter(tmp,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING).use { Yaml().dump(values,it) }
        FileChannel.open(tmp,StandardOpenOption.WRITE).use { it.force(true) }
        if (Files.exists(target)) {
            val backupTmp = directory.resolve("${p.uuid}.yml.bak.tmp")
            Files.copy(target,backupTmp,StandardCopyOption.REPLACE_EXISTING)
            Files.move(backupTmp,directory.resolve("${p.uuid}.yml.bak"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        }
        Files.move(tmp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
    }
    override fun close() {
        executor.shutdown()
        check(executor.awaitTermination(30,TimeUnit.SECONDS)) { "Player IO did not finish within 30 seconds" }
    }
}
