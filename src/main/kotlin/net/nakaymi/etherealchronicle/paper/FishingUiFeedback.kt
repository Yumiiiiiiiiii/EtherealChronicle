package net.nakaymi.etherealchronicle.paper

import net.nakaymi.etherealchronicle.core.FishBehavior
import net.nakaymi.etherealchronicle.core.FishingEscapeCause
import net.nakaymi.etherealchronicle.core.FishSpeciesDefinition
import net.nakaymi.etherealchronicle.core.FishingSpotDefinition

internal enum class FishingEndReason { CAUGHT, ESCAPED_BITE, LINE_SNAP, LINE_SLACK, TIMEOUT, CANCELLED, QUIT, DEATH, DAMAGE, ROD_CHANGED, LEFT_SPOT, WORLD_UNLOAD, REWARD_FAILED }

internal fun fishingEndLogLine(subject:String,reason:FishingEndReason,fishId:String,progress:Int,tension:Int):String =
    "FISH $subject: 終了 reason=${reason.name} fish=$fishId progress=${progress}% tension=${tension}%"

internal fun fishingReelAdvice(behavior:FishBehavior):String = when(behavior) {
    FishBehavior.CALM -> "右クリックで引く"
    FishBehavior.TELEGRAPH -> "予兆中：次の動きを見る"
    FishBehavior.THRASH -> "暴れ中：手を離して張力を逃がす"
    FishBehavior.DIVE -> "潜り！左クリックでいなす"
}

internal fun fishingEscapeTitle(cause:FishingEscapeCause?):Pair<String,String> = when(cause) {
    FishingEscapeCause.BITE_MISSED -> "§eアタリを逃した！" to "§7次のヒットを待とう"
    FishingEscapeCause.OVER_TENSION -> "§c糸が切れた！" to "§7張力を下げよう"
    FishingEscapeCause.LINE_SLACK -> "§c魚に逃げられた…" to "§7張力を保とう"
    FishingEscapeCause.TIMEOUT -> "§c時間切れ！" to "§7魚を逃してしまった…"
    null -> "§c魚に逃げられた…" to "§7次は張力を調整しよう"
}

internal fun fishingEscapeMessage(cause:FishingEscapeCause?):String = when(cause) {
    FishingEscapeCause.BITE_MISSED -> "アタリを逃しました。次のヒットを待ちましょう。"
    FishingEscapeCause.OVER_TENSION -> "糸が切れました。張力が高すぎます。"
    FishingEscapeCause.LINE_SLACK -> "魚に逃げられました。張力を保ちましょう。"
    FishingEscapeCause.TIMEOUT -> "制限時間を過ぎたため、魚を逃しました。"
    null -> "魚に逃げられました。"
}

internal fun defaultDebugFish(species:Collection<FishSpeciesDefinition>,spots:Collection<FishingSpotDefinition>,fishingLevel:Int,world:String):FishSpeciesDefinition? {
    val candidates=species.filter { it.minFishingLevel<=fishingLevel }
        .sortedWith(compareBy<FishSpeciesDefinition>({it.rarity.ordinal},{it.minigame.difficulty},{it.id}))
    return candidates.firstOrNull { fish -> spots.any { spot ->
        spot.world==world && (spot.id in fish.habitats || spot.area in fish.habitats)
    } } ?: candidates.firstOrNull()
}
