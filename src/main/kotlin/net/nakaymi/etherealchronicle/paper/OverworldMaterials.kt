package net.nakaymi.etherealchronicle.paper

import org.bukkit.Material
import org.bukkit.Tag

/** 地形を置換してよい範囲を建物、道路、池で共有します。 */
internal object OverworldMaterials {
    val ground = setOf(
        Material.STONE, Material.DEEPSLATE, Material.DIRT, Material.GRASS_BLOCK, Material.COARSE_DIRT,
        Material.ROOTED_DIRT, Material.PODZOL, Material.MYCELIUM, Material.MUD, Material.CLAY,
        Material.SAND, Material.RED_SAND, Material.GRAVEL, Material.SANDSTONE, Material.RED_SANDSTONE,
        Material.GRANITE, Material.DIORITE, Material.ANDESITE, Material.TUFF, Material.CALCITE,
        Material.MOSS_BLOCK, Material.DIRT_PATH, Material.FARMLAND,
        Material.COAL_ORE, Material.IRON_ORE, Material.COPPER_ORE, Material.GOLD_ORE,
        Material.REDSTONE_ORE, Material.LAPIS_ORE, Material.DIAMOND_ORE, Material.EMERALD_ORE,
        Material.DEEPSLATE_COAL_ORE, Material.DEEPSLATE_IRON_ORE, Material.DEEPSLATE_COPPER_ORE,
        Material.DEEPSLATE_GOLD_ORE, Material.DEEPSLATE_REDSTONE_ORE, Material.DEEPSLATE_LAPIS_ORE,
        Material.DEEPSLATE_DIAMOND_ORE, Material.DEEPSLATE_EMERALD_ORE,
        Material.SNOW_BLOCK, Material.ICE, Material.PACKED_ICE, Material.BLUE_ICE, Material.TERRACOTTA,
        Material.WHITE_TERRACOTTA, Material.ORANGE_TERRACOTTA, Material.YELLOW_TERRACOTTA,
        Material.RED_TERRACOTTA, Material.BROWN_TERRACOTTA, Material.LIGHT_GRAY_TERRACOTTA,
    )
    val plants = setOf(
        Material.SHORT_GRASS, Material.TALL_GRASS, Material.FERN, Material.LARGE_FERN, Material.SNOW,
        Material.VINE, Material.GLOW_LICHEN, Material.BAMBOO, Material.BAMBOO_SAPLING,
        Material.BROWN_MUSHROOM, Material.RED_MUSHROOM, Material.DANDELION, Material.POPPY,
        Material.BLUE_ORCHID, Material.ALLIUM, Material.AZURE_BLUET, Material.OXEYE_DAISY,
        Material.CORNFLOWER, Material.LILY_OF_THE_VALLEY, Material.SUNFLOWER, Material.LILAC,
        Material.ROSE_BUSH, Material.PEONY, Material.DEAD_BUSH, Material.SEAGRASS, Material.TALL_SEAGRASS,
    )

    /** 自然地形だけを対象とし、人工ブロックは保護します。 */
    fun replaceable(type: Material): Boolean = type.isAir || type == Material.WATER || type in ground ||
        type in plants || Tag.LOGS.isTagged(type) || Tag.LEAVES.isTagged(type)
}
