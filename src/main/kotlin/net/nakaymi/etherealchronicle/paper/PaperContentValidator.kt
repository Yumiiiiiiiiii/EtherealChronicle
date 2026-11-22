package net.nakaymi.etherealchronicle.paper

import org.bukkit.Material
import org.bukkit.Particle
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

    /** 敵のentity名が有効な生物種別かを確認します。 */
    fun livingEntity(name: String) {
        require(EntityType.valueOf(name).isAlive) { "entity must be living: $name" }
    }
}
