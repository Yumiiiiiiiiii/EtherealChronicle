package net.nakaymi.etherealchronicle.paper

import net.nakaymi.etherealchronicle.core.FishBehavior
import net.nakaymi.etherealchronicle.core.FishingEscapeCause
import net.nakaymi.etherealchronicle.core.FishSpeciesDefinition
import net.nakaymi.etherealchronicle.core.FishingSpotDefinition

internal enum class FishingEndReason { CAUGHT, ESCAPED_BITE, LINE_SNAP, LINE_SLACK, TIMEOUT, CANCELLED, QUIT, DEATH, DAMAGE, ROD_CHANGED, LEFT_SPOT, WORLD_UNLOAD, REWARD_FAILED }

internal fun fishingEndLogLine(subject:String,reason:FishingEndReason,fishId:String,progress:Int,tension:Int):String =
    "FISH $subject: 終了 reason=${reason.name} fish=$fishId progress=${progress}% tension=${tension}%"

internal fun fishingReelAdvice(behavior:FishBehavior,tension:Double=50.0):String = when(behavior) {
    FishBehavior.CALM -> when {
        tension<=18.0 -> "ec.fishing.low_tension"
        tension>=70.0 -> "ec.fishing.high_tension"
        else -> "ec.fishing.pull"
    }
    FishBehavior.TELEGRAPH -> "ec.fishing.telegraph"
    FishBehavior.THRASH -> "ec.fishing.thrash"
    FishBehavior.DIVE -> "ec.fishing.dive"
}

internal fun fishingEscapeTitle(cause:FishingEscapeCause?):Pair<String,String> = when(cause) {
    FishingEscapeCause.BITE_MISSED -> "ec.fishing.title_missed" to "ec.fishing.subtitle_next_bite"
    FishingEscapeCause.OVER_TENSION -> "ec.fishing.title_line_snap" to "ec.fishing.subtitle_lower_tension"
    FishingEscapeCause.LINE_SLACK -> "ec.fishing.title_escape" to "ec.fishing.subtitle_keep_tension"
    FishingEscapeCause.TIMEOUT -> "ec.fishing.title_timeout" to "ec.fishing.subtitle_timeout"
    null -> "ec.fishing.title_escape" to "ec.fishing.subtitle_retry"
}

internal fun fishingEscapeMessage(cause:FishingEscapeCause?):String = when(cause) {
    FishingEscapeCause.BITE_MISSED -> "ec.fishing.title_missed"
    FishingEscapeCause.OVER_TENSION -> "ec.fishing.title_line_snap"
    FishingEscapeCause.LINE_SLACK -> "ec.fishing.title_escape"
    FishingEscapeCause.TIMEOUT -> "ec.fishing.title_timeout"
    null -> "ec.fishing.title_escape"
}

internal fun defaultDebugFish(species:Collection<FishSpeciesDefinition>,spots:Collection<FishingSpotDefinition>,fishingLevel:Int,world:String):FishSpeciesDefinition? {
    val candidates=species.filter { it.minFishingLevel<=fishingLevel }
        .sortedWith(compareBy<FishSpeciesDefinition>({it.rarity.ordinal},{it.minigame.difficulty},{it.id}))
    return candidates.firstOrNull { fish -> spots.any { spot ->
        spot.world==world && (spot.id in fish.habitats || spot.area in fish.habitats)
    } } ?: candidates.firstOrNull()
}
