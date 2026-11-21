package net.nakaymi.etherealchronicle.data

import net.nakaymi.etherealchronicle.core.*

/** Content-ID migrations are separate from the physical player-file schema. */
object ProfileContentMigration {
    private val oldClasses=mapOf("ec:warrior" to "ec:class_warrior","ec:archer" to "ec:class_archer","ec:mage" to "ec:class_mage")
    val oldItems=mapOf("ec:warrior_starter" to "ec:item_wpn_rusty_broadsword","ec:archer_starter" to "ec:item_wpn_hunter_shortbow","ec:mage_starter" to "ec:item_wpn_apprentice_wand","ec:field_ration" to "ec:item_con_lumina_stew")
    fun apply(p: PlayerProfile,content: ContentSnapshot) {
        p.classId?.let { old -> p.classId=content.classes.find(old)?.id ?: oldClasses[old]?.takeIf { content.classes.find(it) != null } ?: old }
        p.level=p.level.coerceIn(1,content.balance.levelCap)
        p.skillLevels.replaceAll { _,rank -> rank.coerceIn(1,content.balance.skillLevelCap) }
        if(p.classId != null) p.flags.add("ec:flag_class_selected")
        if("ec:quest_m01_awakening" in content.world.quests) {
            p.quests.remove("ec:tutorial")
            if(p.classId != null) p.quests.putIfAbsent("ec:quest_m01_awakening",QuestProgress())
        }
        p.quests.forEach { (_,q) -> if(q.objectives.isEmpty() && q.progress > 0) q.objectives["legacy_kills"]=q.progress }
    }
    fun migrateClassDocument(d: Map<String,Any?>): Map<String,Any?> {
        if(d["type"] != "class") return d
        val old=d["id"] as? String ?: return d
        val canonical=oldClasses[old] ?: return d
        return d+mapOf("id" to canonical,"aliases" to (d.strings("aliases")+old).distinct())
    }
}
