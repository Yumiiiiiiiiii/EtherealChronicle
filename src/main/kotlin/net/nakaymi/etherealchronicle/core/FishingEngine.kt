package net.nakaymi.etherealchronicle.core

import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

enum class FishingPhase { CASTING, WAITING, BITE_ALERT, REELING, CAUGHT, ESCAPED, CANCELLED }
enum class FishBehavior { CALM, TELEGRAPH, THRASH, DIVE }
enum class ReelInput { IDLE, PULL, BRACE }
data class FishingState(
    val fish: FishSpeciesDefinition,
    var phase: FishingPhase = FishingPhase.CASTING,
    var elapsedSeconds: Double = 0.0,
    var biteWindowLeft: Double = fish.minigame.biteWindowMillis/1000.0,
    var progress: Double = 0.0,
    var tension: Double = 32.0,
    var sweetCenter: Double = 50.0,
    var behavior: FishBehavior = FishBehavior.CALM,
    var behaviorSeconds: Double = 3.0,
    var slackSeconds: Double = 0.0,
    var diveCooldownSeconds: Double = 0.0,
    var thrashTelegraphSeconds: Double = 0.0,
    var waitSeconds: Double = 4.0,
    var tensionStability: Double = 1.0,
    var sweetSpotBonus: Double = 0.0
)
data class FishCatch(val species: FishSpeciesDefinition,val sizeCm: Double,val crown: Crown)

class FishingMacroDetector(private val capacity:Int=12) {
    private val responses=ArrayDeque<Long>()
    private val intervals=ArrayDeque<Long>()
    private val castIntervals=ArrayDeque<Long>()
    private var biteStarted:Long?=null
    private var lastPull:Long?=null
    private var lastCast:Long?=null
    init { require(capacity in 8..64) }
    fun cast(nowMillis:Long) {
        lastCast?.let { add(castIntervals,nowMillis-it) }
        lastCast=nowMillis
    }
    fun bite(nowMillis:Long) { biteStarted=nowMillis }
    fun pull(nowMillis:Long) {
        biteStarted?.let { add(responses,nowMillis-it) }; biteStarted=null
        lastPull?.let { add(intervals,nowMillis-it) }; lastPull=nowMillis
    }
    fun suspicious(minSamples:Int=8,responseDeviationMillis:Double=18.0,castIntervalDeviationMillis:Double=120.0):Boolean {
        require(minSamples in 6..capacity && responseDeviationMillis in 1.0..100.0 && castIntervalDeviationMillis in 10.0..1000.0)
        val fixedReaction=responses.size>=minSamples && deviation(responses)<=responseDeviationMillis
        val fixedPullCadence=responses.size>=minSamples && intervals.size>=minSamples &&
            deviation(intervals)<=responseDeviationMillis && deviation(responses)<=responseDeviationMillis*2
        val fixedCastCadence=responses.size>=minSamples && castIntervals.size>=minSamples-1 &&
            deviation(castIntervals)<=castIntervalDeviationMillis && deviation(responses)<=responseDeviationMillis*2
        return fixedReaction || fixedPullCadence || fixedCastCadence
    }
    private fun add(values:ArrayDeque<Long>,value:Long) { if(value<0) return; values.addLast(value); while(values.size>capacity) values.removeFirst() }
    private fun deviation(values:Collection<Long>):Double {
        val mean=values.average()
        return kotlin.math.sqrt(values.sumOf { (it-mean)*(it-mean) }/values.size)
    }
}

/** Pure state machine and reward selection for the custom fishing loop. */
class FishingEngine(private val balance: LifeBalance,private val random: Random = Random.Default) {
    fun eligible(species: Collection<FishSpeciesDefinition>,spot: FishingSpotDefinition,rodTier:Int,lifeLevel:Int,time:Long,weather:String):List<FishSpeciesDefinition> =
        species.filter { fish ->
            fish.minRodTier<=rodTier && fish.minFishingLevel<=lifeLevel &&
                (spot.id in fish.habitats || spot.area in fish.habitats) &&
                ("ANY" in fish.timeWindows || (time in 0..11999 && "DAY" in fish.timeWindows) || (time in 12000..13500 || time in 22500..23999) && "DAWN_DUSK" in fish.timeWindows || time in 13501..22499 && "NIGHT" in fish.timeWindows) &&
                ("ANY" in fish.weather || weather in fish.weather)
        }
    fun choose(candidates:List<FishSpeciesDefinition>):FishSpeciesDefinition? {
        val total=candidates.sumOf { it.baseWeight }
        if(total<=0.0) return null
        var n=random.nextDouble()*total
        for(fish in candidates) { n-=fish.baseWeight; if(n<=0) return fish }
        return candidates.last()
    }
    fun tick(state:FishingState,dt:Double,input:ReelInput):FishingPhase {
        require(dt in 0.0..1.0)
        require(state.tensionStability in 0.5..1.2 && state.sweetSpotBonus in 0.0..15.0)
        if(state.phase in setOf(FishingPhase.CAUGHT,FishingPhase.ESCAPED,FishingPhase.CANCELLED)) return state.phase
        when(state.phase) {
            FishingPhase.CASTING -> { state.elapsedSeconds+=dt; if(state.elapsedSeconds>=0.4) { state.phase=FishingPhase.WAITING; state.elapsedSeconds=0.0 } }
            FishingPhase.WAITING -> { state.elapsedSeconds+=dt; if(state.elapsedSeconds>=state.waitSeconds) { state.phase=FishingPhase.BITE_ALERT; state.biteWindowLeft=state.fish.minigame.biteWindowMillis/1000.0 } }
            FishingPhase.BITE_ALERT -> {
                if(input==ReelInput.PULL) { state.phase=FishingPhase.REELING; state.elapsedSeconds=0.0 }
                else { state.biteWindowLeft-=dt; if(state.biteWindowLeft<=0) state.phase=FishingPhase.ESCAPED }
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
            state.behavior=FishBehavior.THRASH; state.behaviorSeconds=1.2+random.nextDouble()
        } else if(state.behaviorSeconds<=0) {
            state.behavior=when {
                random.nextDouble()<mini.diveChancePerSecond && state.diveCooldownSeconds<=0 -> FishBehavior.DIVE
                random.nextDouble()<mini.thrashChancePerSecond -> FishBehavior.TELEGRAPH
                else -> FishBehavior.CALM
            }
            state.behaviorSeconds=when(state.behavior) { FishBehavior.CALM -> 2.5+random.nextDouble()*1.5; FishBehavior.TELEGRAPH -> 0.4; FishBehavior.THRASH -> 1.2+random.nextDouble(); FishBehavior.DIVE -> 1.0+random.nextDouble()*0.6 }
            if(state.behavior==FishBehavior.DIVE) state.diveCooldownSeconds=4.0
        }
        state.sweetCenter=(state.sweetCenter+(random.nextDouble()-0.5)*mini.difficulty*dt*8).coerceIn(12.0,88.0)
        when {
            input==ReelInput.BRACE && state.behavior==FishBehavior.DIVE -> { state.tension-=15; state.progress+=6; state.behavior=FishBehavior.CALM; state.behaviorSeconds=2.0 }
            state.behavior==FishBehavior.DIVE -> { state.tension+=12*state.tensionStability*dt; state.progress-=3*dt }
            input==ReelInput.PULL -> {
                val wild=if(state.behavior==FishBehavior.THRASH) 2.4 else 1.0
                state.tension+=(7.0*wild*state.tensionStability)*dt
                val inSweet=kotlin.math.abs(state.tension-state.sweetCenter)<=(mini.sweetWidth+state.sweetSpotBonus).coerceAtMost(46.0)/2
                state.progress+=mini.progressPerSecond*(if(state.behavior==FishBehavior.THRASH) 0.3 else if(inSweet) 1.0 else 0.28)*dt
            }
            else -> { state.tension-=mini.tensionDecayPerSecond*dt; state.progress-=2.5*dt }
        }
        state.tension=state.tension.coerceIn(0.0,100.0); state.progress=state.progress.coerceIn(0.0,100.0)
        state.slackSeconds=if(state.tension<=2.0) state.slackSeconds+dt else 0.0
        when {
            state.progress>=100 -> state.phase=FishingPhase.CAUGHT
            state.tension>=100 || state.slackSeconds>=balance.slackFailSeconds || state.elapsedSeconds>=balance.reelingTimeoutSeconds -> state.phase=FishingPhase.ESCAPED
        }
    }
    fun catch(state:FishingState,rodSizeBonus:Double,lifeLevel:Int):FishCatch {
        require(state.phase==FishingPhase.CAUGHT && rodSizeBonus in 0.0..0.15 && lifeLevel>=1)
        val fish=state.fish; val mean=(fish.minCm+fish.maxCm)/2; val sigma=(fish.maxCm-fish.minCm)/5.2
        val u1=random.nextDouble().coerceAtLeast(1e-12); val u2=random.nextDouble()
        val gaussian=sqrt(-2*ln(u1))*kotlin.math.cos(2*Math.PI*u2)
        val bonus=(rodSizeBonus+(lifeLevel-1)*0.002).coerceAtMost(0.06)
        val size=(mean+gaussian*sigma)*(1+bonus)
        val cm=(size.coerceIn(fish.minCm,fish.maxCm)*10).toInt()/10.0
        return FishCatch(fish,cm,when { cm>=fish.goldCrownCm -> Crown.GOLD; cm>=fish.silverCrownCm -> Crown.SILVER; else -> Crown.NONE })
    }
    fun record(profile:PlayerProfile,catch:FishCatch) {
        val record=profile.fishRecords.getOrPut(catch.species.id) { FishRecord() }
        record.count=(record.count+1).coerceAtMost(1_000_000)
        if(catch.sizeCm>=record.maxCm) { record.maxCm=catch.sizeCm; record.crown=catch.crown }
    }
    fun score(profile:PlayerProfile):Int {
        val species=profile.fishRecords.values.count { it.count>0 }
        val silver=profile.fishRecords.values.sumOf { if(it.crown in setOf(Crown.SILVER,Crown.GOLD)) 1 else 0 }
        val gold=profile.fishRecords.values.count { it.crown==Crown.GOLD }
        val total=profile.fishRecords.values.sumOf { it.count }
        return (species*100+silver*150+gold*400+minOf(1000,total*2)).coerceAtMost(Int.MAX_VALUE)
    }
}
