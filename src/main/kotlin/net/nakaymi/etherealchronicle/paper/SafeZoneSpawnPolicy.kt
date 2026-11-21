package net.nakaymi.etherealchronicle.paper

import net.nakaymi.etherealchronicle.core.Area
import net.nakaymi.etherealchronicle.core.Position

private val vanillaHostileSpawnReasons=setOf("NATURAL","CHUNK_GEN","REINFORCEMENTS","PATROL","VILLAGE_INVASION","RAID","TRIAL_SPAWNER","DEFAULT")

internal fun shouldSuppressVanillaHostileSpawn(reason:String,hostile:Boolean,position:Position,areas:Collection<Area>):Boolean =
    hostile && reason in vanillaHostileSpawnReasons && areas.any { it.safe && it.contains(position) }

internal fun shouldSendClassHud(gatheringActive:Boolean,fishingActive:Boolean)=!gatheringActive && !fishingActive
