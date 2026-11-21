package net.nakaymi.etherealchronicle.core

import java.util.UUID
import kotlin.math.pow
import kotlin.random.Random

/** 装備・職業・効果を合算するゲーム内ステータス値です。 */
data class Stats(val hp: Double = 100.0, val resource: Double = 100.0, val attack: Double = 10.0,
    val defense: Double = 0.0, val crit: Double = 0.05, val critDamage: Double = 1.5,
    val elementResist: Double = 0.0, val skillPower: Double = 1.0) {
    operator fun plus(other: Stats) = Stats(hp + other.hp, resource + other.resource, attack + other.attack,
        defense + other.defense, crit + other.crit, critDamage + other.critDamage,
        elementResist + other.elementResist, skillPower + other.skillPower)
    companion object { val ZERO = Stats(0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0) }
}

operator fun Stats.times(factor: Double) = Stats(hp*factor,resource*factor,attack*factor,defense*factor,crit*factor,critDamage*factor,elementResist*factor,skillPower*factor)
/** 成長・ダメージ計算の設定値と、インフレを抑える上限を保持します。 */
data class Balance(val levelCap: Int, val xpBase: Double, val xpExponent: Double, val xpGainCap: Long,
    val currencyGainCap: Long, val damageCap: Double, val statCap: Double, val critCap: Double,
    val defenseScale: Double, val skillLevelCap: Int, val skillGrowth: Double, val critDamageCap: Double, val elementCap: Double, val xpCurve: String = "POWER", val xpLinear: Double = 45.0, val xpQuadratic: Double = 18.0, val xpRounding: Long = 10, val skillGcdMillis: Long = 800, val dropPityAttemptCap: Int = 100) {
    /** 指定レベルから次のレベルに必要な経験値を返します。 */
    fun requiredXp(level: Int): Long {
        val l=level.coerceIn(1,levelCap)
        return (if(xpCurve == "POLYNOMIAL") (kotlin.math.round((xpBase+xpLinear*(l-1)+xpQuadratic*(l-1.0)*(l-1))/xpRounding)*xpRounding).toLong() else (xpBase*l.toDouble().pow(xpExponent)).toLong()).coerceAtLeast(1)
    }
    /** 防御・会心・属性補正を適用し、設定済みdamage cap内に収めます。 */
    fun damage(stats: Stats, defense: Double, multiplier: Double, weapon: Double, critical: Boolean, element: Double = 1.0): Double {
        val base = stats.attack.coerceIn(0.0, statCap) * multiplier.coerceAtLeast(0.0) + weapon.coerceIn(0.0, statCap)
        return (base * defenseScale / (defenseScale + defense.coerceIn(0.0, statCap)) *
            (if (critical) stats.critDamage.coerceIn(1.0, critDamageCap) else 1.0) * element.coerceIn(0.0, elementCap)).coerceIn(0.0, damageCap)
    }
    /** 一度の獲得量とレベル上限を守って経験値を反映し、上がったレベル数を返します。 */
    fun grantXp(player: PlayerProfile, amount: Long): Int {
        require(amount >= 0)
        val gain = amount.coerceAtMost(xpGainCap)
        // Long加算のオーバーフローを避けてから、1イベントあたりの獲得上限を適用します。
        player.xp = player.xp.coerceAtMost(Long.MAX_VALUE - gain) + gain
        val before = player.level
        while (player.level < levelCap && player.xp >= requiredXp(player.level)) {
            player.xp -= requiredXp(player.level); player.level++
        }
        if (player.level == levelCap) player.xp = 0
        return player.level - before
    }
}

enum class ResourceKind { RAGE, FOCUS, MANA }
data class ClassDefinition(val id: String, val name: String, val resource: ResourceKind, val stats: Stats,
    val regeneration: Double, val hitGain: Double, val skills: List<String>, val starterItems: List<String>, val growth: Stats = Stats.ZERO, val icon: String? = null, val itemModel: String? = null)
data class SkillDefinition(val id: String, val name: String, val cost: Double, val cooldownMillis: Long,
    val multiplier: Double, val range: Double, val element: String = "PHYSICAL", val radius: Double = 0.0, val heal: Double = 0.0, val defenseBuff: Double = 0.0, val buffTicks: Int = 0,
    val partyHeal: Boolean = false, val partyDefenseBuff: Boolean = false, val allyRadius: Double = 0.0, val tauntTicks: Int = 0, val threatMultiplier: Double = 1.0)
fun interface Clock { fun millis(): Long }
enum class CastResult { SUCCESS, COOLDOWN, RESOURCE, INVALID_LEVEL, INVALID_TARGET }
/** スキルのコスト、個別cooldown、全体GCDを純粋ロジックで判定します。 */
class SkillEngine(private val clock: Clock, private var balance: Balance) {
    /** リロード後の計算設定を反映します。 */
    fun updateBalance(value: Balance) { balance = value }
    private val ready = mutableMapOf<Pair<UUID, String>, Long>()
    private val globalReady = mutableMapOf<UUID,Long>()
    fun remaining(player: UUID, skill: String) = (maxOf(ready[player to skill] ?: 0,globalReady[player] ?: 0) - clock.millis()).coerceAtLeast(0)
    /** 成功時だけresourceを消費し、個別cooldownとglobal GCDを同時に開始します。 */
    fun cast(player: PlayerProfile, skill: SkillDefinition): CastResult {
        val level = player.skillLevels.getOrDefault(skill.id, 1)
        if (level !in 1..balance.skillLevelCap) return CastResult.INVALID_LEVEL
        if (remaining(player.uuid, skill.id) > 0) return CastResult.COOLDOWN
        if (player.resource < skill.cost) return CastResult.RESOURCE
        player.resource -= skill.cost
        ready[player.uuid to skill.id] = clock.millis() + skill.cooldownMillis
        globalReady[player.uuid] = clock.millis() + balance.skillGcdMillis
        return CastResult.SUCCESS
    }
    /** スキルレベルに応じた倍率を返します。 */
    fun multiplier(player: PlayerProfile, skill: SkillDefinition) = skill.multiplier *
        (1 + (player.skillLevels.getOrDefault(skill.id, 1).coerceIn(1, balance.skillLevelCap) - 1) * balance.skillGrowth)
    /** 期限切れの個別cooldownと全体GCDを破棄します。 */
    fun cleanup() { val now = clock.millis(); ready.entries.removeIf { it.value <= now }; globalReady.entries.removeIf { it.value <= now } }
}
/** 基本攻撃など同一プレイヤーの短時間連打を抑える時間ゲートです。 */
class ActionGate(private val clock: Clock) {
    private val ready=mutableMapOf<UUID,Long>()
    /** 指定間隔を満たした攻撃だけを受理します。 */
    fun accept(id: UUID,interval: Long): Boolean {
        require(interval > 0)
        val now=clock.millis()
        if(now < ready.getOrDefault(id,0)) return false
        ready[id]=now+interval; return true
    }
    /** 期限切れの攻撃ゲートを破棄します。 */
    fun cleanup() { val now=clock.millis(); ready.entries.removeIf { it.value <= now } }
}

enum class Rarity(val displayKey: String) { COMMON("ec.rarity.common"), UNCOMMON("ec.rarity.uncommon"), RARE("ec.rarity.rare"), EPIC("ec.rarity.epic"), LEGENDARY("ec.rarity.legendary") }
enum class GearSlot { HAND, OFFHAND, HEAD, CHEST, LEGS, FEET, ACCESSORY, NONE }
/** YAMLの装備定義と、装備条件・stat budgetの検証規則です。 */
data class ItemDefinition(val id: String, val name: String, val material: String, val rarity: Rarity,
    val minLevel: Int, val classes: List<String>, val stats: Stats, val budget: Double, val budgetMultiplier: Double = 1.0, val affixes: Int = 0, val slot: GearSlot = GearSlot.HAND, val itemModel: String? = null) {
    /** 装備レベルとクラス条件を満たすかを返します。 */
    fun canEquip(player: PlayerProfile) = player.level >= minLevel && (classes.isEmpty() || player.classId in classes)
    /** 強化倍率を含む能力値が定義済みbudget内かを判定します。 */
    fun withinBudget() = stats.hp / 10 + stats.resource / 10 + stats.attack + stats.defense + stats.crit * 100 +
        stats.critDamage * 10 + stats.elementResist * 100 + stats.skillPower * 10 <= budget * budgetMultiplier
}
/** 上限を越えない範囲で、budget内に収まる強化後ステータスを計算します。 */
fun ItemDefinition.enhancedStats(level:Int,balance:Balance,rate:Double):Stats {
    require(level in 0..5 && rate in 0.0..0.1)
    fun candidate(factor:Double)=Stats(
        (stats.hp*factor).coerceAtMost(balance.statCap),(stats.resource*factor).coerceAtMost(balance.statCap),
        (stats.attack*factor).coerceAtMost(balance.statCap),(stats.defense*factor).coerceAtMost(balance.statCap),
        (stats.crit*factor).coerceAtMost(balance.critCap),if(stats.critDamage == 0.0) 0.0 else (stats.critDamage*factor).coerceAtMost(balance.critDamageCap),
        (stats.elementResist*factor).coerceAtMost(balance.elementCap),(stats.skillPower*factor).coerceAtMost(balance.statCap))
    val wanted=1.0+level*rate
    var low=1.0; var high=wanted
    repeat(40) { val mid=(low+high)/2; if(copy(stats=candidate(mid)).withinBudget()) low=mid else high=mid }
    return candidate(low)
}
data class DropEntry(val item: String, val chance: Double, val min: Int, val max: Int, val pityAfter: Int? = null)
/** 重み付きドロップとプロフィール別pityを反映した結果を返します。 */
data class DropTable(val id: String, val entries: List<DropEntry>) {
    fun roll(random: Random, pity: MutableMap<String,Int> = mutableMapOf(), pityCap: Int = 100): Map<String, Int> = buildMap {
        require(pityCap in 1..10000)
        this@DropTable.entries.forEach { entry ->
            val chanceSuccess=random.nextDouble() < entry.chance
            if(entry.pityAfter == null) {
                if(chanceSuccess) put(entry.item,getOrDefault(entry.item,0)+random.nextInt(entry.min,entry.max+1))
                return@forEach
            }
            val key="$id/${entry.item}"
            require(entry.pityAfter in 1..pityCap) { "pity_after must be 1..$pityCap" }
            val success=chanceSuccess
            // pityはテーブル・アイテム別に保存し、成功か保証発動で0へ戻します。
            val misses=if(success) 0 else (pity.getOrDefault(key,0)+1).coerceAtMost(pityCap)
            pity[key]=misses
            if(success || misses >= entry.pityAfter) {
                pity[key]=0
                put(entry.item,getOrDefault(entry.item,0)+random.nextInt(entry.min,entry.max+1))
            }
        }
    }
}
data class ChallengeRecord(var bestSeconds: Long,var bestRank: String)
/** UUIDごとに保存するゲーム状態です。可変コレクションはsnapshot時に複製します。 */
data class PlayerProfile(val uuid: UUID, var classId: String? = null, var level: Int = 1, var xp: Long = 0,
    var currency: Long = 0, var resource: Double = 0.0, var selectedSkill: Int = 0,
    var starterGranted: Boolean = false, val quests: MutableMap<String, QuestProgress> = mutableMapOf(),
    val skillLevels: MutableMap<String, Int> = mutableMapOf(), val flags: MutableSet<String> = mutableSetOf(),
    val dailyPurchases: MutableMap<String, Int> = mutableMapOf(), val transactions: MutableList<String> = mutableListOf(), var accessory: String? = null, val pendingRewards: MutableMap<String,Int> = mutableMapOf(), val dropPity: MutableMap<String,Int> = mutableMapOf(),val challengeRecords: MutableMap<String,ChallengeRecord> = mutableMapOf(),var selectedTitle:String? = null,val bestiary:MutableMap<String,Int> = mutableMapOf(),val discoveredItems:MutableSet<String> = mutableSetOf(),
    val lifeSkills: MutableMap<LifeSkillKind,LifeSkillProgress> = LifeSkillKind.entries.associateWith { LifeSkillProgress() }.toMutableMap(),
    val fishRecords: MutableMap<String,FishRecord> = mutableMapOf(), val gatheringRecords: MutableMap<String,Int> = mutableMapOf(), val gatheringReadyAt: MutableMap<String,Long> = mutableMapOf(), var fishingBait: String = "AUTO", var bestEnhancementLevel:Int = 0, var resourcePackPreference:String = "AUTO") {
    /** 非同期保存が同時進行中の更新を読み取らないよう、mutable値を独立したコピーにします。 */
    fun snapshot() = copy(
        skillLevels = skillLevels.toMutableMap(),
        quests = quests.mapValues { it.value.copy(objectives = it.value.objectives.toMutableMap()) }.toMutableMap(),
        flags = flags.toMutableSet(),
        dailyPurchases = dailyPurchases.toMutableMap(),
        transactions = transactions.toMutableList(),
        pendingRewards = pendingRewards.toMutableMap(),
        dropPity = dropPity.toMutableMap(),
        challengeRecords = challengeRecords.mapValues { it.value.copy() }.toMutableMap(),
        bestiary = bestiary.toMutableMap(),
        discoveredItems = discoveredItems.toMutableSet(),
        lifeSkills = lifeSkills.mapValues { it.value.copy() }.toMutableMap(),
        fishRecords = fishRecords.mapValues { it.value.copy() }.toMutableMap(),
        gatheringRecords = gatheringRecords.toMutableMap(),
        gatheringReadyAt = gatheringReadyAt.toMutableMap(),
    )
}
data class QuestProgress(var progress: Int = 0, var completed: Boolean = false, val objectives: MutableMap<String,Int> = mutableMapOf(), var completedAt: Long = 0, var repeatAvailableAt: Long = Long.MAX_VALUE, var resetPeriod: String? = null)

/** ランク閾値を最良から順に評価し、該当がなければ最低ランクを返します。 */
fun challengeRank(seconds: Long,ranks: List<ScoreRank>): String = ranks.firstOrNull { seconds <= it.maxSeconds }?.rank ?: ranks.lastOrNull()?.rank ?: "C"
/** ダンジョン記録を更新し、最速記録だったかを返します。 */
fun PlayerProfile.recordChallenge(dungeon:String,seconds:Long,rank:String): Pair<ChallengeRecord,Boolean> {
    require(seconds >= 0 && rank in setOf("S","A","B","C"))
    val old=challengeRecords[dungeon]
    val updated=old == null || seconds < old.bestSeconds
    if(updated) challengeRecords[dungeon]=ChallengeRecord(seconds,rank)
    return challengeRecords.getValue(dungeon) to updated
}
/** 解放条件を満たした称号だけを装備状態にします。 */
fun PlayerProfile.equipTitle(id:String?,titles:Map<String,TitleDefinition>):Boolean {
    if(id != null && titles[id]?.unlocked(this) != true) return false
    selectedTitle=id
    return true
}
/** 進捗条件で解放した称号を永続flagへ同期し、今回追加されたflagを返します。 */
fun PlayerProfile.syncTitleUnlockFlags(titles:Map<String,TitleDefinition>):Set<String> {
    val newlyUnlocked=titles.values.filter { it.unlocked(this) }.map { it.unlockFlag }.filter(flags::add).toSet()
    return newlyUnlocked
}
fun PlayerProfile.recordEnemyKill(enemy:String,areas:Map<String,Area>,definitions:Map<String,EnemyDefinition>):Set<String> {
    bestiary[enemy]=(bestiary.getOrDefault(enemy,0)+1).coerceAtMost(1_000_000)
    val unlocked=mutableSetOf<String>()
    areas.values.forEach { area ->
        val all=definitions.values.filter { e -> e.spawns.any { s -> area.contains(Position(s.position.world,s.position.x,s.position.y,s.position.z)) } }.map { it.id }.toSet()
        if(all.isEmpty()) return@forEach
        val found=all.count { (bestiary[it] ?: 0) > 0 }
        val percent=found*100/all.size
        listOf(25,50,75,100).filter { percent >= it }.forEach { milestone ->
            val flag="ec:flag_codex_${area.id.removePrefix("ec:area_")}_$milestone"
            if(flags.add(flag)) unlocked+=flag
        }
    }
    return unlocked
}
/** アイテム図鑑にIDを一度だけ登録します。 */
fun PlayerProfile.discoverItem(id:String):Boolean = discoveredItems.add(id)











