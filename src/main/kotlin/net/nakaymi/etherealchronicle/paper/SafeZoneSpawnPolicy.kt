package net.nakaymi.etherealchronicle.paper

import net.nakaymi.etherealchronicle.core.Area
import net.nakaymi.etherealchronicle.core.Position
import org.bukkit.World

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
