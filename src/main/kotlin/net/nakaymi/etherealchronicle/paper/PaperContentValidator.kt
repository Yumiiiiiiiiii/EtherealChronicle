package net.nakaymi.etherealchronicle.paper

import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.entity.EntityType

/** Paper固有のenum値を読込時に検証し、ゲーム中のvalueOf失敗を防ぎます。 */
internal object PaperContentValidator {
    /** YAMLのアイテム材質が実際にitemとして使えるかを検証します。 */
    fun itemMaterial(name: String) {
        require(Material.valueOf(name).isItem) { "material must be an item: $name" }
    }

    /** YAMLのブロック材質が実際に配置可能かを検証します。 */
    fun blockMaterial(name: String) {
        require(Material.valueOf(name).isBlock) { "material must be a block: $name" }
    }

    /** YAMLのparticle名を列挙値へ解決し、未知値を拒否します。 */
    fun particle(name: String) { Particle.valueOf(name) }

    /** NPC の村人職業・見た目が Paper の実レジストリに存在するか確認します。 */
    fun villagerAppearance(profession: String?, type: String?) {
        profession?.let { require(villagerProfession(it) != null) { "unknown villager profession: $it" } }
        type?.let { require(villagerType(it) != null) { "unknown villager type: $it" } }
    }

    /** 職業キー（大文字小文字不問）から村人職業を引きます。 */
    fun villagerProfession(name: String): org.bukkit.entity.Villager.Profession? =
        org.bukkit.Registry.VILLAGER_PROFESSION.get(org.bukkit.NamespacedKey.minecraft(name.lowercase()))

    /** 見た目キー（大文字小文字不問）から村人のバイオーム型を引きます。 */
    fun villagerType(name: String): org.bukkit.entity.Villager.Type? =
        org.bukkit.Registry.VILLAGER_TYPE.get(org.bukkit.NamespacedKey.minecraft(name.lowercase()))

    /** 敵のentity名が有効な生物種別かを確認します。 */
    fun livingEntity(name: String) {
        require(EntityType.valueOf(name).isAlive) { "entity must be living: $name" }
    }

    /** YAMLで指定されたwolf variantがPaperの実レジストリに存在するか確認します。 */
    fun wolfVariant(name: String, entityType: String?) {
        require(entityType == "WOLF") { "wolf variant requires entity: WOLF" }
        val key = NamespacedKey.fromString(name)
            ?: throw IllegalArgumentException("invalid wolf variant key: $name")
        val registered = Registry.WOLF_VARIANT.get(key) != null
        val bundledCustom = key.namespace == "ec" &&
            PaperContentValidator::class.java.getResourceAsStream(
                "/datapacks/etherealchronicle_core/data/ec/wolf_variant/${key.key}.json",
            )?.use { true } == true
        require(registered || bundledCustom) { "unknown wolf variant: $name" }
    }

    /** ダンジョン建築処理が参照する最低3色分のpaletteを要求します。 */
    fun dungeonPalette(palette: List<String>) {
        require(palette.size >= 3) { "dungeon palette must contain at least 3 materials" }
        palette.forEach(::blockMaterial)
    }
}
