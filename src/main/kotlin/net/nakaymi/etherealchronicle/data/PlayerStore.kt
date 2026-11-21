package net.nakaymi.etherealchronicle.data

import net.nakaymi.etherealchronicle.core.*

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
    private class FutureSchema(message: String): IllegalArgumentException(message)
    fun load(uuid: UUID): CompletableFuture<PlayerProfile> = CompletableFuture.supplyAsync({
        Files.createDirectories(directory)
        val file = directory.resolve("$uuid.yml")
        if (!Files.exists(file)) PlayerProfile(uuid) else try { read(file,uuid) } catch(primary: Exception) {
            if (primary is FutureSchema) throw primary
            val backup = directory.resolve("$uuid.yml.bak")
            if (Files.exists(backup)) {
                val recovered = read(backup,uuid)
                Files.move(file,directory.resolve("$uuid.yml.corrupt-${System.currentTimeMillis()}"),StandardCopyOption.REPLACE_EXISTING)
                Files.copy(backup,file,StandardCopyOption.REPLACE_EXISTING)
                recovered
            } else throw primary
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
        val p = PlayerProfile(uuid,d["class"] as? String,d.int("level"),d.long("xp"),d.long("currency"),d.num("resource"),d.int("selected_skill"),d["starter_granted"] as Boolean)
        p.bestEnhancementLevel=d.integer("best_enhancement_level",0).also { require(it in 0..5) }
        require(p.level >= 1 && p.xp >= 0 && p.currency >= 0 && p.resource.isFinite() && p.resource >= 0 && p.selectedSkill in 0..3)
        (d["skill_levels"] as? Map<*,*>)?.forEach { (k,v) -> p.skillLevels[k as String] = (v as Number).toInt().also { require(it >= 1) } }
        d.mapping("quests").forEach { (id,value) ->
            @Suppress("UNCHECKED_CAST") val q = value as Map<String,Any?>
            p.quests[id] = QuestProgress(q.int("progress").also { require(it >= 0) },q["completed"] as Boolean,
                (q["objectives"] ?: emptyMap<String,Any?>()).asMapping().mapValues { (_,n) -> (n as Number).toInt().also { require(it >= 0) } }.toMutableMap(),q.whole("completed_at",0),q.whole("repeat_available_at",Long.MAX_VALUE),q["reset_period"] as? String)
        }
        p.flags.addAll(d.strings("flags")); p.transactions.addAll(d.strings("transactions").takeLast(128))
        (d["daily_purchases"] ?: emptyMap<String,Any?>()).asMapping().forEach { (id,n) -> p.dailyPurchases[id]=(n as Number).toInt().also { require(it >= 0) } }
        p.accessory=d["accessory"] as? String
        (d["drop_pity"] ?: emptyMap<String,Any?>()).asMapping().forEach { (id,n) -> p.dropPity[id]=(n as Number).toInt().also { require(it in 0..10000) } }
        (d["pending_rewards"] ?: emptyMap<String,Any?>()).asMapping().forEach { (id,n) -> p.pendingRewards[id]=(n as Number).toInt().also { require(it in 1..1_000_000) } }
        (d["challenge_records"] ?: emptyMap<String,Any?>()).asMapping().forEach { (id,value) ->
            val record=value.asMapping(); val seconds=record.whole("best_seconds",-1)
            val rank=record.string("best_rank"); require(seconds >= 0 && rank in setOf("S","A","B","C"))
            p.challengeRecords[id]=ChallengeRecord(seconds,rank)
        }
        p.selectedTitle=d["selected_title"] as? String
        d.mapping("bestiary").forEach { (id,n) -> p.bestiary[id]=(n as Number).toInt().also { require(it in 0..1_000_000) } }
        p.discoveredItems.addAll(d.strings("discovered_items"))
        p.fishingBait=d.text("fishing_bait","AUTO").also { require(it=="AUTO" || it=="NONE" || it.matches(Regex("ec:item_bait_[a-z0-9_]+"))) }
        d.mapping("life_skills").forEach { (kind,value) ->
            val skill=LifeSkillKind.valueOf(kind)
            val progress=value.asMapping()
            val level=progress.int("level"); val xp=progress.long("xp")
            require(level in 1..100 && xp >= 0)
            p.lifeSkills[skill]=LifeSkillProgress(level,xp)
        }
        d.mapping("fish_records").forEach { (id,value) ->
            val record=value.asMapping(); val count=record.int("count"); val size=record.num("max_cm"); val crown=Crown.valueOf(record.string("crown"))
            require(count in 0..1_000_000 && size.isFinite() && size in 0.0..10_000.0)
            p.fishRecords[id]=FishRecord(count,size,crown)
        }
        d.mapping("gathering_records").forEach { (id,n) -> p.gatheringRecords[id]=(n as Number).toInt().also { require(it in 0..1_000_000) } }
        d.mapping("gathering_ready_at").forEach { (id,n) -> p.gatheringReadyAt[id]=(n as Number).toString().toLongOrNull()?.also { require(it >= 0) } ?: error("採取時刻が不正です: $id") }
        require(p.fishRecords.size <= 500 && p.gatheringRecords.size <= 500 && p.gatheringReadyAt.size <= 2000)
        return p
    }
    fun migrate(raw: Map<String,Any?>): Map<String,Any?> {
        val version = (raw["schema_version"] as? Number)?.toInt() ?: 0
        if(version !in 0..11) throw FutureSchema("Unsupported player schema $version")
        val lifeDefaults=mapOf("life_skills" to mapOf("FISHING" to mapOf("level" to 1,"xp" to 0),"GATHERING" to mapOf("level" to 1,"xp" to 0)),"fish_records" to emptyMap<String,Any?>(),"gathering_records" to emptyMap<String,Int>(),"gathering_ready_at" to emptyMap<String,Long>(),"fishing_bait" to "AUTO")
        if(version == 11) return raw
        if(version == 10) return raw + mapOf("best_enhancement_level" to 0,"schema_version" to 11)
        if(version == 9) return (raw + lifeDefaults + mapOf("schema_version" to 10)) + mapOf("best_enhancement_level" to 0,"schema_version" to 11)
        if(version == 8) return ((raw + mapOf("schema_version" to 9,"bestiary" to emptyMap<String,Int>(),"discovered_items" to emptyList<String>())) + lifeDefaults + mapOf("schema_version" to 10)) + mapOf("best_enhancement_level" to 0,"schema_version" to 11)
        if(version == 7) return ((raw + mapOf("schema_version" to 9,"selected_title" to null,"bestiary" to emptyMap<String,Int>(),"discovered_items" to emptyList<String>())) + lifeDefaults + mapOf("schema_version" to 10)) + mapOf("best_enhancement_level" to 0,"schema_version" to 11)
        if(version == 6) return ((raw + mapOf("schema_version" to 9,"challenge_records" to emptyMap<String,Any?>(),"selected_title" to null,"bestiary" to emptyMap<String,Int>(),"discovered_items" to emptyList<String>())) + lifeDefaults + mapOf("schema_version" to 10)) + mapOf("best_enhancement_level" to 0,"schema_version" to 11)
        if(version == 5) return ((raw + mapOf("schema_version" to 9,"challenge_records" to emptyMap<String,Any?>(),"selected_title" to null,"bestiary" to emptyMap<String,Int>(),"discovered_items" to emptyList<String>())) + lifeDefaults + mapOf("schema_version" to 10)) + mapOf("best_enhancement_level" to 0,"schema_version" to 11)
        if(version == 4) return ((raw + mapOf("schema_version" to 9,"drop_pity" to emptyMap<String,Int>(),"challenge_records" to emptyMap<String,Any?>(),"selected_title" to null,"bestiary" to emptyMap<String,Int>(),"discovered_items" to emptyList<String>())) + lifeDefaults + mapOf("schema_version" to 10)) + mapOf("best_enhancement_level" to 0,"schema_version" to 11)
        if(version == 3) return ((raw + mapOf("schema_version" to 9,"pending_rewards" to emptyMap<String,Int>(),"drop_pity" to emptyMap<String,Int>(),"challenge_records" to emptyMap<String,Any?>(),"selected_title" to null,"bestiary" to emptyMap<String,Int>(),"discovered_items" to emptyList<String>())) + lifeDefaults + mapOf("schema_version" to 10)) + mapOf("best_enhancement_level" to 0,"schema_version" to 11)
        if(version == 2) return ((raw + mapOf("schema_version" to 9,"flags" to emptyList<String>(),"transactions" to emptyList<String>(),"daily_purchases" to emptyMap<String,Int>(),"drop_pity" to emptyMap<String,Int>(),"challenge_records" to emptyMap<String,Any?>(),"selected_title" to null,"bestiary" to emptyMap<String,Int>(),"discovered_items" to emptyList<String>())) + lifeDefaults + mapOf("schema_version" to 10)) + mapOf("best_enhancement_level" to 0,"schema_version" to 11)
        val v1 = if (version == 1) raw else mapOf("level" to 1,"xp" to 0,"currency" to 0,
            "resource" to 0,"selected_skill" to 0,"starter_granted" to false,"skill_levels" to emptyMap<String,Int>()) + raw
        val tutorial = v1["tutorial_quest"] as? String
        val quests = if(tutorial == null) emptyMap() else mapOf("ec:tutorial" to mapOf("progress" to (if(tutorial == "ec:tutorial_complete") 1 else 0),"completed" to (tutorial == "ec:tutorial_complete")))
        return ((v1.filterKeys { it != "tutorial_quest" } + mapOf("schema_version" to 9,"quests" to quests,"flags" to emptyList<String>(),"transactions" to emptyList<String>(),"daily_purchases" to emptyMap<String,Int>(),"drop_pity" to emptyMap<String,Int>(),"challenge_records" to emptyMap<String,Any?>(),"selected_title" to null,"bestiary" to emptyMap<String,Int>(),"discovered_items" to emptyList<String>())) + lifeDefaults + mapOf("schema_version" to 10)) + mapOf("best_enhancement_level" to 0,"schema_version" to 11)
    }
    private fun write(p: PlayerProfile) {
        Files.createDirectories(directory)
        val target = directory.resolve("${p.uuid}.yml")
        val tmp = directory.resolve("${p.uuid}.yml.tmp")
        val values = linkedMapOf<String,Any?>("schema_version" to 11,"uuid" to p.uuid.toString(),"class" to p.classId,
            "level" to p.level,"xp" to p.xp,"currency" to p.currency,"resource" to p.resource,"selected_skill" to p.selectedSkill,
            "starter_granted" to p.starterGranted,"quests" to p.quests.mapValues { (_,q) -> mapOf("progress" to q.progress,"completed" to q.completed,"objectives" to q.objectives,"completed_at" to q.completedAt,"repeat_available_at" to q.repeatAvailableAt,"reset_period" to q.resetPeriod) },"skill_levels" to p.skillLevels,"flags" to p.flags.toList(),"transactions" to p.transactions,"daily_purchases" to p.dailyPurchases,"accessory" to p.accessory,"pending_rewards" to p.pendingRewards,"drop_pity" to p.dropPity,"challenge_records" to p.challengeRecords.mapValues { (_,record) -> mapOf("best_seconds" to record.bestSeconds,"best_rank" to record.bestRank) },"selected_title" to p.selectedTitle,"bestiary" to p.bestiary,"discovered_items" to p.discoveredItems.toList(),
            "life_skills" to p.lifeSkills.mapKeys { it.key.name }.mapValues { (_,progress) -> mapOf("level" to progress.level,"xp" to progress.xp) },"fish_records" to p.fishRecords.mapValues { (_,record) -> mapOf("count" to record.count,"max_cm" to record.maxCm,"crown" to record.crown.name) },"gathering_records" to p.gatheringRecords,"gathering_ready_at" to p.gatheringReadyAt,"fishing_bait" to p.fishingBait,"best_enhancement_level" to p.bestEnhancementLevel)
        Files.newBufferedWriter(tmp,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING).use { Yaml().dump(values,it) }
        FileChannel.open(tmp,StandardOpenOption.WRITE).use { it.force(true) }
        if (Files.exists(target)) {
            val backupTmp = directory.resolve("${p.uuid}.yml.bak.tmp")
            Files.copy(target,backupTmp,StandardCopyOption.REPLACE_EXISTING)
            Files.move(backupTmp,directory.resolve("${p.uuid}.yml.bak"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        }
        Files.move(tmp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
    }
    fun shutdown() { executor.shutdown() }
    override fun close() {
        executor.shutdown()
        check(executor.awaitTermination(30,TimeUnit.SECONDS)) { "Player IO did not finish within 30 seconds" }
    }
}






