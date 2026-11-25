package net.nakaymi.etherealchronicle.paper

import net.nakaymi.etherealchronicle.data.ContentSnapshot

/** クエスト目的の内部IDを、プレイヤーに読めるコンテンツ表示名へ変換します。 */
internal fun questObjectiveLabel(
    target:String,
    content:ContentSnapshot,
    unknownLabel:String,
    localize:(type:String,id:String,ja:String)->String = { _,_,ja -> ja },
):String =
    content.world.npcs[target]?.let { localize("npc",target,it.name) }
        ?: content.items.find(target)?.let { localize("item",target,it.name) }
        ?: content.world.enemies[target]?.let { localize(if(it.boss) "boss" else "enemy",target,it.name) }
        ?: content.world.areas[target]?.let { localize("area",target,it.name) }
        ?: content.world.fishSpecies[target]?.let { localize("fish",target,it.name) }
        ?: content.world.gatheringNodes[target]?.let { localize("gathering",target,it.name) }
        ?: unknownLabel
