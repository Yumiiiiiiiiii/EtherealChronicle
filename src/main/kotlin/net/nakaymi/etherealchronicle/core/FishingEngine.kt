package net.nakaymi.etherealchronicle.core

import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/** 釣りミニゲームの固定物理係数です。魚種ごとの難度はYAML定義を優先します。 */
object FishingPhysics {
    const val CALM_BEHAVIOR_MIN_SECONDS = 2.5
    const val CALM_BEHAVIOR_VARIANCE_SECONDS = 1.5
    const val TELEGRAPH_BEHAVIOR_SECONDS = 0.4
    const val THRASH_BEHAVIOR_MIN_SECONDS = 1.2
    const val THRASH_BEHAVIOR_VARIANCE_SECONDS = 1.0
    const val DIVE_BEHAVIOR_MIN_SECONDS = 1.0
    const val DIVE_BEHAVIOR_VARIANCE_SECONDS = 0.6
    const val DIVE_COOLDOWN_SECONDS = 4.0
    const val DIVE_BRACE_RECOVERY_SECONDS = 2.0
    const val MIN_CAST_WAIT_SECONDS = 2.5
    const val CAST_WAIT_VARIANCE_SECONDS = 6.0
    const val FATIGUE_WAIT_MULTIPLIER = 1.4
    const val MIN_INPUT_INTERVAL_MILLIS = 150L
    const val START_TENSION = 32.0
    const val START_SWEET_CENTER = 50.0
    const val CALM_ADVICE_TENSION = START_SWEET_CENTER
    const val LOW_TENSION_ADVICE_THRESHOLD = 18.0
    const val HIGH_TENSION_ADVICE_THRESHOLD = 70.0
    const val START_BEHAVIOR_SECONDS = 3.0
    const val START_WAIT_SECONDS = 4.0
    const val CASTING_SECONDS = 0.4
    const val MAX_STEP_SECONDS = 1.0
    const val MIN_STABILITY = 0.5
    const val MAX_STABILITY = 1.2
    const val MAX_SWEET_BONUS = 15.0
    const val MIN_MACRO_SAMPLES = 6
    const val MIN_HISTORY_CAPACITY = 8
    const val MAX_HISTORY_CAPACITY = 64
    const val MAX_RESPONSE_DEVIATION_MS = 100.0
    const val MIN_RESPONSE_DEVIATION_MS = 1.0
    const val MIN_CAST_DEVIATION_MS = 10.0
    const val MAX_CAST_DEVIATION_MS = 1_000.0
    const val CAST_RESPONSE_DEVIATION_FACTOR = 2.0
    const val MIN_SWEET_CENTER = 12.0
    const val MAX_SWEET_CENTER = 88.0
    const val SWEET_CENTER_WANDER_PER_SECOND = 8.0
    const val THRASH_TENSION_MULTIPLIER = 2.4
    const val PULL_TENSION_PER_SECOND = 7.0
    const val DIVE_TENSION_PER_SECOND = 12.0
    const val BRACE_TENSION_REDUCTION = 15.0
    const val BRACE_PROGRESS_GAIN = 6.0
    const val DIVE_PROGRESS_LOSS_PER_SECOND = 3.0
    const val THRASH_PROGRESS_MULTIPLIER = 0.3
    const val OUTSIDE_SWEET_PROGRESS_MULTIPLIER = 0.28
    const val IDLE_PROGRESS_LOSS_PER_SECOND = 2.5
    const val MAX_TENSION = 100.0
    const val MAX_PROGRESS = 100.0
    const val TELEGRAPH_THRASH_MIN_SECONDS = 1.2
    const val TELEGRAPH_THRASH_VARIANCE_SECONDS = 1.0
    const val MAX_SWEET_REGION_WIDTH = 46.0
    const val MAX_ROD_SIZE_BONUS = 0.15
    const val SLACK_TENSION_THRESHOLD = 2.0
    const val SIZE_GAUSSIAN_SIGMA_DIVISOR = 5.2
    const val MIN_GAUSSIAN_SAMPLE = 1e-12
    const val LIFE_LEVEL_SIZE_BONUS = 0.002
    const val MAX_SIZE_BONUS = 0.06
    const val SIZE_ROUNDING_SCALE = 10
    const val MAX_FISH_SIZE_CM = 10_000.0
}

/** 魚影の記録点数を算出する重みを一箇所で定義します。 */
object FishingScoring {
    const val SPECIES_POINTS = 100
    const val SILVER_CROWN_POINTS = 150
    const val GOLD_CROWN_POINTS = 400
    const val CATCH_POINTS = 2
    const val CATCH_POINT_CAP = 1_000
}

enum class FishingPhase { CASTING, WAITING, BITE_ALERT, REELING, CAUGHT, ESCAPED, CANCELLED }
enum class FishingEscapeCause { BITE_MISSED, OVER_TENSION, LINE_SLACK, TIMEOUT }
enum class FishBehavior { CALM, TELEGRAPH, THRASH, DIVE }
enum class ReelInput { IDLE, PULL, BRACE }
data class FishingState(
    val fish: FishSpeciesDefinition,
    var phase: FishingPhase = FishingPhase.CASTING,
    var escapeCause: FishingEscapeCause? = null,
    var elapsedSeconds: Double = 0.0,
        var biteWindowLeft: Double = fish.minigame.biteWindowMillis/GameDurations.MILLIS_PER_SECOND.toDouble(),
    var progress: Double = 0.0,
    var tension: Double = FishingPhysics.START_TENSION,
    var sweetCenter: Double = FishingPhysics.START_SWEET_CENTER,
    var behavior: FishBehavior = FishBehavior.CALM,
    var behaviorSeconds: Double = FishingPhysics.START_BEHAVIOR_SECONDS,
    var slackSeconds: Double = 0.0,
    var diveCooldownSeconds: Double = 0.0,
    var thrashTelegraphSeconds: Double = 0.0,
    var waitSeconds: Double = FishingPhysics.START_WAIT_SECONDS,
    var tensionStability: Double = 1.0,
    var sweetSpotBonus: Double = 0.0
)
data class FishCatch(val species: FishSpeciesDefinition,val sizeCm: Double,val crown: Crown)

/** 応答時間・pull間隔・cast間隔の有界履歴から周期botらしさを判定します。 */
class FishingMacroDetector(private val capacity:Int=LifeBalanceDefaults.MACRO_HISTORY_CAPACITY) {
    private val responses=ArrayDeque<Long>()
    private val intervals=ArrayDeque<Long>()
    private val castIntervals=ArrayDeque<Long>()
    private var biteStarted:Long?=null
    private var lastPull:Long?=null
    private var lastCast:Long?=null
    init { require(capacity in FishingPhysics.MIN_HISTORY_CAPACITY..FishingPhysics.MAX_HISTORY_CAPACITY) }
    fun cast(nowMillis:Long) {
        lastCast?.let { add(castIntervals,nowMillis-it) }
        lastCast=nowMillis
    }
    fun bite(nowMillis:Long) { biteStarted=nowMillis }
    fun pull(nowMillis:Long) {
        biteStarted?.let { add(responses,nowMillis-it) }; biteStarted=null
        lastPull?.let { add(intervals,nowMillis-it) }; lastPull=nowMillis
    }
    fun suspicious(minSamples:Int=LifeBalanceDefaults.MACRO_MIN_SAMPLES,
                   responseDeviationMillis:Double=LifeBalanceDefaults.MACRO_RESPONSE_DEVIATION_MILLIS,
                   castIntervalDeviationMillis:Double=LifeBalanceDefaults.MACRO_CAST_INTERVAL_DEVIATION_MILLIS):Boolean {
        require(minSamples in FishingPhysics.MIN_MACRO_SAMPLES..capacity &&
            responseDeviationMillis in FishingPhysics.MIN_RESPONSE_DEVIATION_MS..FishingPhysics.MAX_RESPONSE_DEVIATION_MS &&
            castIntervalDeviationMillis in FishingPhysics.MIN_CAST_DEVIATION_MS..FishingPhysics.MAX_CAST_DEVIATION_MS)
        val fixedReaction=responses.size>=minSamples && deviation(responses)<=responseDeviationMillis
        val fixedPullCadence=responses.size>=minSamples && intervals.size>=minSamples &&
            deviation(intervals)<=responseDeviationMillis && deviation(responses)<=responseDeviationMillis*FishingPhysics.CAST_RESPONSE_DEVIATION_FACTOR
        val fixedCastCadence=responses.size>=minSamples && castIntervals.size>=minSamples-1 &&
            deviation(castIntervals)<=castIntervalDeviationMillis && deviation(responses)<=responseDeviationMillis*FishingPhysics.CAST_RESPONSE_DEVIATION_FACTOR
        return fixedReaction || fixedPullCadence || fixedCastCadence
    }
    private fun add(values:ArrayDeque<Long>,value:Long) { if(value<0) return; values.addLast(value); while(values.size>capacity) values.removeFirst() }
    private fun deviation(values:Collection<Long>):Double {
        val mean=values.average()
        return kotlin.math.sqrt(values.sumOf { (it-mean)*(it-mean) }/values.size)
    }
}

/** 独自釣りの状態遷移、魚種抽選、記録更新を行うPaper非依存エンジンです。 */
/** 釣り候補の適格性、重み抽選、ミニゲーム、サイズ記録を計算します。 */
class FishingEngine(private val balance: LifeBalance,private val random: Random = Random.Default) {
    fun eligible(species: Collection<FishSpeciesDefinition>,spot: FishingSpotDefinition,rodTier:Int,lifeLevel:Int,time:Long,weather:String):List<FishSpeciesDefinition> =
        species.filter { fish ->
            fish.minRodTier<=rodTier && fish.minFishingLevel<=lifeLevel &&
                (spot.id in fish.habitats || spot.area in fish.habitats) &&
                WorldSchedule.matchesTimeWindow(fish.timeWindows, time) &&
                WorldSchedule.matchesWeather(fish.weather, weather)
        }
    fun choose(candidates:List<FishSpeciesDefinition>):FishSpeciesDefinition? {
        val total=candidates.sumOf { it.baseWeight }
        if(total<=0.0) return null
        var n=random.nextDouble()*total
        for(fish in candidates) { n-=fish.baseWeight; if(n<=0) return fish }
        return candidates.last()
    }
    fun tick(state:FishingState,dt:Double,input:ReelInput):FishingPhase {
        require(dt in 0.0..FishingPhysics.MAX_STEP_SECONDS)
        require(state.tensionStability in FishingPhysics.MIN_STABILITY..FishingPhysics.MAX_STABILITY &&
            state.sweetSpotBonus in 0.0..FishingPhysics.MAX_SWEET_BONUS)
        if(state.phase in setOf(FishingPhase.CAUGHT,FishingPhase.ESCAPED,FishingPhase.CANCELLED)) return state.phase
        when(state.phase) {
            FishingPhase.CASTING -> { state.elapsedSeconds+=dt; if(state.elapsedSeconds>=FishingPhysics.CASTING_SECONDS) { state.phase=FishingPhase.WAITING; state.elapsedSeconds=0.0 } }
            FishingPhase.WAITING -> { state.elapsedSeconds+=dt; if(state.elapsedSeconds>=state.waitSeconds) { state.phase=FishingPhase.BITE_ALERT; state.biteWindowLeft=state.fish.minigame.biteWindowMillis/GameDurations.MILLIS_PER_SECOND.toDouble() } }
            FishingPhase.BITE_ALERT -> {
                if(input==ReelInput.PULL) { state.phase=FishingPhase.REELING; state.elapsedSeconds=0.0 }
                else { state.biteWindowLeft-=dt; if(state.biteWindowLeft<=0) { state.escapeCause=FishingEscapeCause.BITE_MISSED; state.phase=FishingPhase.ESCAPED } }
            }
            FishingPhase.REELING -> reel(state,dt,input)
            else -> Unit
        }
        return state.phase
    }
    private fun reel(state:FishingState,dt:Double,input:ReelInput) {
        val mini=state.fish.minigame
        state.elapsedSeconds+=dt; state.behaviorSeconds-=dt; state.diveCooldownSeconds=(state.diveCooldownSeconds-dt).coerceAtLeast(0.0)
        if(state.behavior==FishBehavior.TELEGRAPH && state.behaviorSeconds<=0) {
            state.behavior=FishBehavior.THRASH; state.behaviorSeconds=FishingPhysics.TELEGRAPH_THRASH_MIN_SECONDS+random.nextDouble()*FishingPhysics.TELEGRAPH_THRASH_VARIANCE_SECONDS
        } else if(state.behaviorSeconds<=0) {
            state.behavior=when {
                random.nextDouble()<mini.diveChancePerSecond && state.diveCooldownSeconds<=0 -> FishBehavior.DIVE
                random.nextDouble()<mini.thrashChancePerSecond -> FishBehavior.TELEGRAPH
                else -> FishBehavior.CALM
            }
        state.behaviorSeconds=when(state.behavior) {
            FishBehavior.CALM -> FishingPhysics.CALM_BEHAVIOR_MIN_SECONDS + random.nextDouble() * FishingPhysics.CALM_BEHAVIOR_VARIANCE_SECONDS
            FishBehavior.TELEGRAPH -> FishingPhysics.TELEGRAPH_BEHAVIOR_SECONDS
            FishBehavior.THRASH -> FishingPhysics.THRASH_BEHAVIOR_MIN_SECONDS + random.nextDouble() * FishingPhysics.THRASH_BEHAVIOR_VARIANCE_SECONDS
            FishBehavior.DIVE -> FishingPhysics.DIVE_BEHAVIOR_MIN_SECONDS + random.nextDouble() * FishingPhysics.DIVE_BEHAVIOR_VARIANCE_SECONDS
        }
        if(state.behavior==FishBehavior.DIVE) state.diveCooldownSeconds=FishingPhysics.DIVE_COOLDOWN_SECONDS
        }
        state.sweetCenter=(state.sweetCenter+(random.nextDouble()-0.5)*mini.difficulty*dt*FishingPhysics.SWEET_CENTER_WANDER_PER_SECOND)
            .coerceIn(FishingPhysics.MIN_SWEET_CENTER,FishingPhysics.MAX_SWEET_CENTER)
        when {
            input==ReelInput.BRACE && state.behavior==FishBehavior.DIVE -> { state.tension-=FishingPhysics.BRACE_TENSION_REDUCTION; state.progress+=FishingPhysics.BRACE_PROGRESS_GAIN; state.behavior=FishBehavior.CALM; state.behaviorSeconds=FishingPhysics.DIVE_BRACE_RECOVERY_SECONDS }
            state.behavior==FishBehavior.DIVE -> { state.tension+=FishingPhysics.DIVE_TENSION_PER_SECOND*state.tensionStability*dt; state.progress-=FishingPhysics.DIVE_PROGRESS_LOSS_PER_SECOND*dt }
            input==ReelInput.PULL -> {
                val wild=if(state.behavior==FishBehavior.THRASH) FishingPhysics.THRASH_TENSION_MULTIPLIER else 1.0
                state.tension+=(FishingPhysics.PULL_TENSION_PER_SECOND*wild*state.tensionStability)*dt
                val inSweet=kotlin.math.abs(state.tension-state.sweetCenter)<=(mini.sweetWidth+state.sweetSpotBonus).coerceAtMost(FishingPhysics.MAX_SWEET_REGION_WIDTH)/2
                state.progress+=mini.progressPerSecond*(if(state.behavior==FishBehavior.THRASH) FishingPhysics.THRASH_PROGRESS_MULTIPLIER else if(inSweet) 1.0 else FishingPhysics.OUTSIDE_SWEET_PROGRESS_MULTIPLIER)*dt
            }
            else -> { state.tension-=mini.tensionDecayPerSecond*dt; state.progress-=FishingPhysics.IDLE_PROGRESS_LOSS_PER_SECOND*dt }
        }
        state.tension=state.tension.coerceIn(0.0,FishingPhysics.MAX_TENSION); state.progress=state.progress.coerceIn(0.0,FishingPhysics.MAX_PROGRESS)
        state.slackSeconds=if(state.tension<=FishingPhysics.SLACK_TENSION_THRESHOLD) state.slackSeconds+dt else 0.0
        when {
            state.progress>=FishingPhysics.MAX_PROGRESS -> state.phase=FishingPhase.CAUGHT
            state.tension>=FishingPhysics.MAX_TENSION -> { state.escapeCause=FishingEscapeCause.OVER_TENSION; state.phase=FishingPhase.ESCAPED }
            state.slackSeconds>=balance.slackFailSeconds -> { state.escapeCause=FishingEscapeCause.LINE_SLACK; state.phase=FishingPhase.ESCAPED }
            state.elapsedSeconds>=balance.reelingTimeoutSeconds -> { state.escapeCause=FishingEscapeCause.TIMEOUT; state.phase=FishingPhase.ESCAPED }
        }
    }
    fun catch(state:FishingState,rodSizeBonus:Double,lifeLevel:Int):FishCatch {
        require(state.phase==FishingPhase.CAUGHT && rodSizeBonus in 0.0..FishingPhysics.MAX_ROD_SIZE_BONUS && lifeLevel>=1)
        val fish=state.fish; val mean=(fish.minCm+fish.maxCm)/2; val sigma=(fish.maxCm-fish.minCm)/FishingPhysics.SIZE_GAUSSIAN_SIGMA_DIVISOR
        val u1=random.nextDouble().coerceAtLeast(FishingPhysics.MIN_GAUSSIAN_SAMPLE); val u2=random.nextDouble()
        val gaussian=sqrt(-2*ln(u1))*kotlin.math.cos(2*Math.PI*u2)
        val bonus=(rodSizeBonus+(lifeLevel-1)*FishingPhysics.LIFE_LEVEL_SIZE_BONUS).coerceAtMost(FishingPhysics.MAX_SIZE_BONUS)
        val size=(mean+gaussian*sigma)*(1+bonus)
        val cm=(size.coerceIn(fish.minCm,fish.maxCm)*FishingPhysics.SIZE_ROUNDING_SCALE).toInt()/FishingPhysics.SIZE_ROUNDING_SCALE.toDouble()
        return FishCatch(fish,cm,when { cm>=fish.goldCrownCm -> Crown.GOLD; cm>=fish.silverCrownCm -> Crown.SILVER; else -> Crown.NONE })
    }
    fun record(profile:PlayerProfile,catch:FishCatch) {
        val record=profile.fishRecords.getOrPut(catch.species.id) { FishRecord() }
        record.count=(record.count+1).coerceAtMost(GameLimits.MAX_RECORD_COUNT)
        if(catch.sizeCm>=record.maxCm) { record.maxCm=catch.sizeCm; record.crown=catch.crown }
    }
    fun score(profile:PlayerProfile):Int {
        val species=profile.fishRecords.values.count { it.count>0 }
        val silver=profile.fishRecords.values.sumOf { if(it.crown in setOf(Crown.SILVER,Crown.GOLD)) 1 else 0 }
        val gold=profile.fishRecords.values.count { it.crown==Crown.GOLD }
        val total=profile.fishRecords.values.sumOf { it.count }
        return (species*FishingScoring.SPECIES_POINTS+silver*FishingScoring.SILVER_CROWN_POINTS+
            gold*FishingScoring.GOLD_CROWN_POINTS+minOf(FishingScoring.CATCH_POINT_CAP,total*FishingScoring.CATCH_POINTS)).coerceAtMost(Int.MAX_VALUE)
    }
}
