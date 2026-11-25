package net.nakaymi.etherealchronicle.paper

import net.nakaymi.etherealchronicle.core.Area
import net.nakaymi.etherealchronicle.core.Position
import org.bukkit.World
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.entity.Monster

/** 安全地帯の敵対Mob走査範囲（水平・垂直）。 */
private const val HOSTILE_SCAN_HORIZONTAL_BLOCKS = 40.0
private const val HOSTILE_SCAN_VERTICAL_BLOCKS = 128.0

private val vanillaHostileSpawnReasons=setOf("NATURAL","CHUNK_GEN","REINFORCEMENTS","PATROL","VILLAGE_INVASION","RAID","TRIAL_SPAWNER","DEFAULT")

internal fun shouldSuppressVanillaHostileSpawn(reason:String,hostile:Boolean,position:Position,areas:Collection<Area>):Boolean =
    hostile && reason in vanillaHostileSpawnReasons && areas.any { it.safe && it.contains(position) }

internal fun shouldRemoveExistingSafeHostile(hostile:Boolean,rpgTagged:Boolean,position:Position,areas:Collection<Area>):Boolean =
    hostile && !rpgTagged && areas.any { it.safe && it.contains(position) }

internal fun enteredSafeArea(from:Position,to:Position,areas:Collection<Area>):Boolean =
    areas.none { it.safe && it.contains(from) } && areas.any { it.safe && it.contains(to) }

internal fun shouldSendClassHud(gatheringActive:Boolean,fishingActive:Boolean)=!gatheringActive && !fishingActive

internal fun disableVanillaHostileSpawns(world:World) {
    world.setAllowMonsterSpawning(false)
}

/** 安全地帯に入った周囲だけを走査し、RPG 管理外の敵対 Mob を除去します。 */
internal fun clearProjectedSafeHostiles(plugin: RpgPlugin, location: Location) {
    val world = location.world
    if (!plugin.isManagedWorld(world)) return
    val areas = plugin.safeAreas(world.name)
    if (areas.isEmpty()) return
    val enemyKey = NamespacedKey(plugin, "enemy_id")
    world.getNearbyEntities(location, HOSTILE_SCAN_HORIZONTAL_BLOCKS,
        HOSTILE_SCAN_VERTICAL_BLOCKS, HOSTILE_SCAN_HORIZONTAL_BLOCKS)
        .filterIsInstance<Monster>().forEach { entity ->
        val at = entity.location
        if (shouldRemoveExistingSafeHostile(true, entity.persistentDataContainer.has(enemyKey),
                Position(world.name, at.x, at.y, at.z), areas)) entity.remove()
    }
}
