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

/** 永続プロフィールの世代番号を名前で管理し、保存形式の変更箇所を明示します。 */
internal object PlayerSchema {
    const val INITIAL = 0
    const val V1 = 1
    const val V2 = 2
    const val V3 = 3
    const val V4 = 4
    const val V5 = 5
    const val V6 = 6
    const val V7 = 7
    const val V8 = 8
    const val CODEX = 9
    const val LIFE_SKILLS = 10
    const val ENHANCEMENT = 11
    const val PACK_PREFERENCES = 12
    const val CURRENT = 13
}

/** YAML整数を指定範囲で読み、壊れた保存データのフィールド名を明示します。 */
internal fun Any?.boundedInt(range: IntRange, field: String, default: Int? = null): Int {
    val raw = this ?: default ?: error("Missing integer field: $field")
    val number = (raw as? Number)?.toInt() ?: error("Invalid integer field: $field")
    require(number in range) { "Out-of-range integer field: $field" }
    return number
}

/** 読込・保存・再接続を単一IOキューで直列化し、呼び出し側snapshotを原子的に書き込みます。 */
/** UUIDごとのprofile YAMLを単一非同期キューで移行・読込・原子的保存します。 */
class PlayerStore(private val directory: Path): AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r,"ec-player-io") }
    private class FutureSchema(message: String): IllegalArgumentException(message)
    /** backup復旧とschema移行をIO executorで行い、プロフィールを返します。 */
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
    /** detached snapshotを一時ファイル・backup・atomic replaceの順に保存します。 */
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
        p.bestEnhancementLevel=d["best_enhancement_level"].boundedInt(0..GameLimits.MAX_ENHANCEMENT_LEVEL,"best_enhancement_level",0)
        require(p.level >= 1 && p.xp >= 0 && p.currency >= 0 && p.resource.isFinite() && p.resource >= 0 && p.selectedSkill in 0 until ContentLimits.MAX_CLASS_SKILLS)
        (d["skill_levels"] as? Map<*,*>)?.forEach { (k,v) -> p.skillLevels[k as String] = v.boundedInt(1..1_000_000,"skill_levels.$k") }
        d.mapping("quests").forEach { (id,value) ->
            @Suppress("UNCHECKED_CAST") val q = value as Map<String,Any?>
            p.quests[id] = QuestProgress(q["progress"].boundedInt(0..GameLimits.MAX_RECORD_COUNT,"quests.$id.progress"),q["completed"] as Boolean,
                (q["objectives"] ?: emptyMap<String,Any?>()).asMapping().mapValues { (objective,n) -> n.boundedInt(0..GameLimits.MAX_RECORD_COUNT,"quests.$id.objectives.$objective") }.toMutableMap(),q.whole("completed_at",0),q.whole("repeat_available_at",Long.MAX_VALUE),q["reset_period"] as? String)
        }
        p.flags.addAll(d.strings("flags")); p.transactions.addAll(d.strings("transactions").takeLast(GameLimits.MAX_TRANSACTION_HISTORY))
        (d["daily_purchases"] ?: emptyMap<String,Any?>()).asMapping().forEach { (id,n) -> p.dailyPurchases[id]=n.boundedInt(0..GameLimits.MAX_DAILY_PURCHASE_COUNT,"daily_purchases.$id") }
        p.accessory=d["accessory"] as? String
        (d["drop_pity"] ?: emptyMap<String,Any?>()).asMapping().forEach { (id,n) -> p.dropPity[id]=n.boundedInt(0..GameLimits.MAX_DROP_PITY_ATTEMPTS,"drop_pity.$id") }
        (d["pending_rewards"] ?: emptyMap<String,Any?>()).asMapping().forEach { (id,n) -> p.pendingRewards[id]=n.boundedInt(1..GameLimits.MAX_PENDING_REWARD,"pending_rewards.$id") }
        (d["challenge_records"] ?: emptyMap<String,Any?>()).asMapping().forEach { (id,value) ->
            val record=value.asMapping(); val seconds=record.whole("best_seconds",-1)
            val rank=record.string("best_rank"); require(seconds >= 0 && rank in setOf("S","A","B","C"))
            p.challengeRecords[id]=ChallengeRecord(seconds,rank)
        }
        p.selectedTitle=d["selected_title"] as? String
        p.resourcePackPreference=d.text("resource_pack_preference","AUTO").also { require(it in setOf("AUTO","ON","OFF")) }
        p.resourcePackStatus=d.text("resource_pack_status","UNKNOWN").also { require(it.matches(Regex("[A-Z_]{1,48}"))) }
        d.mapping("bestiary").forEach { (id,n) -> p.bestiary[id]=n.boundedInt(0..GameLimits.MAX_RECORD_COUNT,"bestiary.$id") }
        p.discoveredItems.addAll(d.strings("discovered_items"))
        p.fishingBait=d.text("fishing_bait","AUTO").also { require(it=="AUTO" || it=="NONE" || it.matches(Regex("ec:item_bait_[a-z0-9_]+"))) }
        d.mapping("life_skills").forEach { (kind,value) ->
            val skill=LifeSkillKind.valueOf(kind)
            val progress=value.asMapping()
            val level=progress.int("level"); val xp=progress.long("xp")
            require(level in 1..GameLimits.MAX_LIFE_SKILL_LEVEL && xp >= 0)
            p.lifeSkills[skill]=LifeSkillProgress(level,xp)
        }
        d.mapping("fish_records").forEach { (id,value) ->
            val record=value.asMapping(); val count=record.int("count"); val size=record.num("max_cm"); val crown=Crown.valueOf(record.string("crown"))
            require(count in 0..GameLimits.MAX_RECORD_COUNT && size.isFinite() && size in 0.0..FishingPhysics.MAX_FISH_SIZE_CM)
            p.fishRecords[id]=FishRecord(count,size,crown)
        }
        d.mapping("gathering_records").forEach { (id,n) -> p.gatheringRecords[id]=n.boundedInt(0..GameLimits.MAX_RECORD_COUNT,"gathering_records.$id") }
        d.mapping("gathering_ready_at").forEach { (id,n) -> p.gatheringReadyAt[id]=(n as Number).toString().toLongOrNull()?.also { require(it >= 0) } ?: error("ec.error.invalid_content: gathering_ready_at.$id") }
        require(p.fishRecords.size <= GameLimits.MAX_FISH_RECORDS && p.gatheringRecords.size <= GameLimits.MAX_GATHERING_RECORDS && p.gatheringReadyAt.size <= GameLimits.MAX_GATHERING_RESPAWN_ENTRIES)
        return p
    }
    /** 旧schemaを順に現行schemaへ変換し、不足fieldを初期化します。 */
    fun migrate(raw: Map<String,Any?>): Map<String,Any?> {
        val version=(raw["schema_version"] as? Number)?.toInt() ?: 0
        if(version !in PlayerSchema.INITIAL..PlayerSchema.CURRENT) throw FutureSchema("Unsupported player schema $version")
        if(version == PlayerSchema.CURRENT) return raw
        val migrated=if(version == PlayerSchema.PACK_PREFERENCES) raw else migrateToSchema11(raw)
        return migrated + mapOf("schema_version" to PlayerSchema.CURRENT,
            "resource_pack_preference" to (migrated["resource_pack_preference"] ?: "AUTO"),
            "resource_pack_status" to (migrated["resource_pack_status"] ?: "UNKNOWN"))
    }

    /** schema 11以前の個別移行をそのまま順に適用します。 */
    private fun migrateToSchema11(raw: Map<String,Any?>): Map<String,Any?> {
        val version = (raw["schema_version"] as? Number)?.toInt() ?: 0
        if(version !in PlayerSchema.INITIAL..PlayerSchema.ENHANCEMENT) throw FutureSchema("Unsupported player schema $version")
        val lifeDefaults=mapOf("life_skills" to mapOf("FISHING" to mapOf("level" to 1,"xp" to 0),"GATHERING" to mapOf("level" to 1,"xp" to 0)),"fish_records" to emptyMap<String,Any?>(),"gathering_records" to emptyMap<String,Int>(),"gathering_ready_at" to emptyMap<String,Long>(),"fishing_bait" to "AUTO")
        if(version == PlayerSchema.ENHANCEMENT) return raw
        if(version == PlayerSchema.LIFE_SKILLS) return raw + mapOf("best_enhancement_level" to 0,"schema_version" to PlayerSchema.ENHANCEMENT)
        if(version == PlayerSchema.CODEX) return (raw + lifeDefaults + mapOf("schema_version" to PlayerSchema.LIFE_SKILLS)) + mapOf("best_enhancement_level" to 0,"schema_version" to PlayerSchema.ENHANCEMENT)
        if(version == PlayerSchema.V8) return ((raw + mapOf("schema_version" to PlayerSchema.CODEX,"bestiary" to emptyMap<String,Int>(),"discovered_items" to emptyList<String>())) + lifeDefaults + mapOf("schema_version" to PlayerSchema.LIFE_SKILLS)) + mapOf("best_enhancement_level" to 0,"schema_version" to PlayerSchema.ENHANCEMENT)
        if(version == PlayerSchema.V7) return ((raw + mapOf("schema_version" to PlayerSchema.CODEX,"selected_title" to null,"bestiary" to emptyMap<String,Int>(),"discovered_items" to emptyList<String>())) + lifeDefaults + mapOf("schema_version" to PlayerSchema.LIFE_SKILLS)) + mapOf("best_enhancement_level" to 0,"schema_version" to PlayerSchema.ENHANCEMENT)
        if(version == PlayerSchema.V6) return ((raw + mapOf("schema_version" to PlayerSchema.CODEX,"challenge_records" to emptyMap<String,Any?>(),"selected_title" to null,"bestiary" to emptyMap<String,Int>(),"discovered_items" to emptyList<String>())) + lifeDefaults + mapOf("schema_version" to PlayerSchema.LIFE_SKILLS)) + mapOf("best_enhancement_level" to 0,"schema_version" to PlayerSchema.ENHANCEMENT)
        if(version == PlayerSchema.V5) return ((raw + mapOf("schema_version" to PlayerSchema.CODEX,"challenge_records" to emptyMap<String,Any?>(),"selected_title" to null,"bestiary" to emptyMap<String,Int>(),"discovered_items" to emptyList<String>())) + lifeDefaults + mapOf("schema_version" to PlayerSchema.LIFE_SKILLS)) + mapOf("best_enhancement_level" to 0,"schema_version" to PlayerSchema.ENHANCEMENT)
        if(version == PlayerSchema.V4) return ((raw + mapOf("schema_version" to PlayerSchema.CODEX,"drop_pity" to emptyMap<String,Int>(),"challenge_records" to emptyMap<String,Any?>(),"selected_title" to null,"bestiary" to emptyMap<String,Int>(),"discovered_items" to emptyList<String>())) + lifeDefaults + mapOf("schema_version" to PlayerSchema.LIFE_SKILLS)) + mapOf("best_enhancement_level" to 0,"schema_version" to PlayerSchema.ENHANCEMENT)
        if(version == PlayerSchema.V3) return ((raw + mapOf("schema_version" to PlayerSchema.CODEX,"pending_rewards" to emptyMap<String,Int>(),"drop_pity" to emptyMap<String,Int>(),"challenge_records" to emptyMap<String,Any?>(),"selected_title" to null,"bestiary" to emptyMap<String,Int>(),"discovered_items" to emptyList<String>())) + lifeDefaults + mapOf("schema_version" to PlayerSchema.LIFE_SKILLS)) + mapOf("best_enhancement_level" to 0,"schema_version" to PlayerSchema.ENHANCEMENT)
        if(version == PlayerSchema.V2) return ((raw + mapOf("schema_version" to PlayerSchema.CODEX,"flags" to emptyList<String>(),"transactions" to emptyList<String>(),"daily_purchases" to emptyMap<String,Int>(),"drop_pity" to emptyMap<String,Int>(),"challenge_records" to emptyMap<String,Any?>(),"selected_title" to null,"bestiary" to emptyMap<String,Int>(),"discovered_items" to emptyList<String>())) + lifeDefaults + mapOf("schema_version" to PlayerSchema.LIFE_SKILLS)) + mapOf("best_enhancement_level" to 0,"schema_version" to PlayerSchema.ENHANCEMENT)
        val v1 = if (version == PlayerSchema.V1) raw else mapOf("level" to 1,"xp" to 0,"currency" to 0,
            "resource" to 0,"selected_skill" to 0,"starter_granted" to false,"skill_levels" to emptyMap<String,Int>()) + raw
        val tutorial = v1["tutorial_quest"] as? String
        val quests = if(tutorial == null) emptyMap() else mapOf("ec:tutorial" to mapOf("progress" to (if(tutorial == "ec:tutorial_complete") 1 else 0),"completed" to (tutorial == "ec:tutorial_complete")))
        return ((v1.filterKeys { it != "tutorial_quest" } + mapOf("schema_version" to PlayerSchema.CODEX,"quests" to quests,"flags" to emptyList<String>(),"transactions" to emptyList<String>(),"daily_purchases" to emptyMap<String,Int>(),"drop_pity" to emptyMap<String,Int>(),"challenge_records" to emptyMap<String,Any?>(),"selected_title" to null,"bestiary" to emptyMap<String,Int>(),"discovered_items" to emptyList<String>())) + lifeDefaults + mapOf("schema_version" to PlayerSchema.LIFE_SKILLS)) + mapOf("best_enhancement_level" to 0,"schema_version" to PlayerSchema.ENHANCEMENT)
    }
    private fun write(p: PlayerProfile) {
        Files.createDirectories(directory)
        val target = directory.resolve("${p.uuid}.yml")
        val tmp = directory.resolve("${p.uuid}.yml.tmp")
        val values = linkedMapOf<String,Any?>("schema_version" to PlayerSchema.CURRENT,"uuid" to p.uuid.toString(),"class" to p.classId,
            "level" to p.level,"xp" to p.xp,"currency" to p.currency,"resource" to p.resource,"selected_skill" to p.selectedSkill,
            "starter_granted" to p.starterGranted,"quests" to p.quests.mapValues { (_,q) -> mapOf("progress" to q.progress,"completed" to q.completed,"objectives" to q.objectives,"completed_at" to q.completedAt,"repeat_available_at" to q.repeatAvailableAt,"reset_period" to q.resetPeriod) },"skill_levels" to p.skillLevels,"flags" to p.flags.toList(),"transactions" to p.transactions,"daily_purchases" to p.dailyPurchases,"accessory" to p.accessory,"pending_rewards" to p.pendingRewards,"drop_pity" to p.dropPity,"challenge_records" to p.challengeRecords.mapValues { (_,record) -> mapOf("best_seconds" to record.bestSeconds,"best_rank" to record.bestRank) },"selected_title" to p.selectedTitle,"bestiary" to p.bestiary,"discovered_items" to p.discoveredItems.toList(),
            "life_skills" to p.lifeSkills.mapKeys { it.key.name }.mapValues { (_,progress) -> mapOf("level" to progress.level,"xp" to progress.xp) },"fish_records" to p.fishRecords.mapValues { (_,record) -> mapOf("count" to record.count,"max_cm" to record.maxCm,"crown" to record.crown.name) },"gathering_records" to p.gatheringRecords,"gathering_ready_at" to p.gatheringReadyAt,"fishing_bait" to p.fishingBait,"best_enhancement_level" to p.bestEnhancementLevel,"resource_pack_preference" to p.resourcePackPreference,"resource_pack_status" to p.resourcePackStatus)
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






