package net.nakaymi.etherealchronicle.paper

import net.nakaymi.etherealchronicle.data.ContentSnapshot

/** クエスト目的の内部IDを、プレイヤーに読めるコンテンツ表示名へ変換します。 */
internal fun questObjectiveLabel(target:String,content:ContentSnapshot):String =
    content.world.npcs[target]?.name
        ?: content.items.find(target)?.name
        ?: content.world.enemies[target]?.name
        ?: content.world.areas[target]?.name
        ?: content.world.fishSpecies[target]?.name
        ?: content.world.gatheringNodes[target]?.name
        ?: target
