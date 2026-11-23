package net.nakaymi.etherealchronicle.core

/** ジャーナル、探索、建設で共有する地域とダンジョンの安定IDです。 */
object OverworldSiteIds {
    const val LUMINA_PORT = "ec:area_lumina_port"
    const val ASHWOOD_FOREST = "ec:area_ashwood_forest"
    const val WEEPING_MINE = "ec:area_weeping_mine"
    const val STARGAZER_HILL = "ec:area_stargazer_hill"
    const val VOID_SANCTUM = "ec:area_void_sanctum"
    const val FROSTPEAK_OUTPOST = "ec:area_frostpeak_outpost"
    const val GLACIER_RIDGE = "ec:area_glacier_ridge"
    const val CRYSTALLINE_CAVERN = "ec:area_crystalline_cavern"
    const val GLACIAL_CITADEL = "ec:area_glacial_citadel"

    val all = setOf(LUMINA_PORT, ASHWOOD_FOREST, WEEPING_MINE, STARGAZER_HILL, VOID_SANCTUM,
        FROSTPEAK_OUTPOST, GLACIER_RIDGE, CRYSTALLINE_CAVERN, GLACIAL_CITADEL)
    val cold = setOf(FROSTPEAK_OUTPOST, GLACIER_RIDGE, CRYSTALLINE_CAVERN, GLACIAL_CITADEL)
    val towns = setOf(LUMINA_PORT, FROSTPEAK_OUTPOST)
    val frostCluster = setOf(FROSTPEAK_OUTPOST, GLACIER_RIDGE, CRYSTALLINE_CAVERN)
}

/** ダンジョン入口と所属地域を検証するための固定対応です。 */
object OverworldDungeonIds {
    const val NOVICE_ARENA = "ec:dungeon_novice_arena"
    const val AETHER_CRUCIBLE = "ec:dungeon_aether_crucible"
    const val VOID_SANCTUM = "ec:dungeon_void_sanctum"
    const val GLACIAL_CITADEL = "ec:dungeon_glacial_citadel"
    val areas = mapOf(NOVICE_ARENA to OverworldSiteIds.ASHWOOD_FOREST,
        AETHER_CRUCIBLE to OverworldSiteIds.STARGAZER_HILL,
        VOID_SANCTUM to OverworldSiteIds.VOID_SANCTUM,
        GLACIAL_CITADEL to OverworldSiteIds.GLACIAL_CITADEL)
}
