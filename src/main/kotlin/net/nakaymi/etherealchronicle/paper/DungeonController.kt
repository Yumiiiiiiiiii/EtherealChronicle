package net.nakaymi.etherealchronicle.paper

import net.kyori.adventure.text.Component
import net.nakaymi.etherealchronicle.core.recordChallenge
import net.nakaymi.etherealchronicle.core.firstClearRanks
import net.nakaymi.etherealchronicle.core.returnToMailbox
import org.bukkit.*
import org.bukkit.entity.Player
import org.bukkit.event.*
import org.bukkit.event.player.*
import java.util.UUID

/** 制限数付きの複数ダンジョンinstance、wave、帰還地点を管理します。 */
class DungeonController(private val plugin: RpgPlugin,private val adventure: AdventureController,private val enemies: EnemyController): Listener {
    private data class ArenaPalette(val floor: Material, val wall: Material, val inner: Material) {
        companion object {
            fun resolve(names: List<String>) = ArenaPalette(
                Material.valueOf(names[DungeonArenaLayout.OUTER_FLOOR_PALETTE_INDEX]),
                Material.valueOf(names[DungeonArenaLayout.WALL_PALETTE_INDEX]),
                Material.valueOf(names[DungeonArenaLayout.INNER_FLOOR_PALETTE_INDEX]),
            )
        }
    }
    data class Instance(val slot: Int,val definition: net.nakaymi.etherealchronicle.core.DungeonDefinition,val members: MutableSet<UUID>,val returns: Map<UUID,Location>,val started: Long,var boss: UUID?=null,var completed: Boolean=false,var waveIndex: Int=0,val entities: MutableSet<UUID> = mutableSetOf(),val startedAtMillis: Long=monotonicMillis())
    private val instances=mutableMapOf<Int,Instance>()
    private var tick=0L
    private val builtPalette=mutableMapOf<Int,List<String>>()
    private val loadedSchematics=mutableMapOf<String,SchematicData>()
    private val schematicSlots=mutableSetOf<Int>()
    init { plugin.server.pluginManager.registerEvents(this,plugin); adventure.enterDungeon={ player,id -> enter(player,id) } }
    fun activeCount() = instances.size
    internal fun currentWave(slot:Int) = instances[slot]?.waveIndex
    internal fun currentBoss(slot:Int) = instances[slot]?.boss
    /** 参加者・解放条件・instance枠を検証してダンジョンへ入場させます。 */
    fun enter(player: Player,dungeonId: String = "ec:dungeon_void_sanctum") {
        val definition=plugin.snapshot.world.dungeons[dungeonId] ?: error("ec.error.dungeon_missing: $dungeonId")
        require(instances.values.none { player.uniqueId in it.members }) { "ec.error.dungeon_already_inside" }
        val party=adventure.parties.party(player.uniqueId)
        require(party == null || party.leader == player.uniqueId) { "ec.error.party_leader_required" }
        val players=adventure.parties.members(player.uniqueId).map { Bukkit.getPlayer(it) ?: error("ec.error.party_not_online") }
        require(players.all { p -> p.world == player.world && p.location.isWithinBlocks(player.location,PaperDistance.DUNGEON_PARTY_GATHER_BLOCKS) && plugin.profiles[p.uniqueId]?.classId != null }) { "ec.error.party_gather" }
        require(players.all { definition.unlockFlag in plugin.profiles.getValue(it.uniqueId).flags }) { "ec.error.dungeon_unlock_required" }
        val world=Bukkit.getWorld(DemoWorld.NAME) ?: error("ec.error.world_unavailable")
        val slot=(0 until plugin.snapshot.world.tuning.instanceCap).firstOrNull { it !in instances } ?: error("ec.error.dungeon_full")
        val instance=Instance(slot,definition,players.map { it.uniqueId }.toMutableSet(),players.associate { it.uniqueId to it.location.clone() },tick)
        val origin=DungeonArenaLayout.originForSlot(slot); val x=origin.x; val z=origin.z
        val schematicPlaced = placeDungeonSchematic(world, definition.id, x, z)
        if(!schematicPlaced && dungeonPaletteNeedsBuild(builtPalette[slot],definition.palette)) {
            val palette = ArenaPalette.resolve(definition.palette)
            for(dx in -DungeonArenaLayout.PLATFORM_RADIUS_BLOCKS..DungeonArenaLayout.PLATFORM_RADIUS_BLOCKS) for(dz in -DungeonArenaLayout.PLATFORM_RADIUS_BLOCKS..DungeonArenaLayout.PLATFORM_RADIUS_BLOCKS) {
            val edge=kotlin.math.abs(dx) == DungeonArenaLayout.PLATFORM_RADIUS_BLOCKS || kotlin.math.abs(dz) == DungeonArenaLayout.PLATFORM_RADIUS_BLOCKS
            val center=kotlin.math.abs(dx) <= DungeonArenaLayout.INNER_FLOOR_RADIUS_BLOCKS && kotlin.math.abs(dz) <= DungeonArenaLayout.INNER_FLOOR_RADIUS_BLOCKS
            world.getBlockAt(x+dx,DungeonArenaLayout.FLOOR_Y,z+dz).type=if(center) palette.inner else palette.floor
            if(edge) for(y in DungeonArenaLayout.WALL_BOTTOM_Y..DungeonArenaLayout.WALL_TOP_Y) world.getBlockAt(x+dx,y,z+dz).type=palette.wall
            }
            builtPalette[slot]=definition.palette.toList()
        }
        instances[slot]=instance
        try { spawnWave(instance,world,x,z) }
        catch(error:Exception) { close(instance); throw error }
        players.forEach { p -> plugin.profiles[p.uniqueId]?.let { profile -> plugin.game.quests.event(profile,net.nakaymi.etherealchronicle.core.ObjectiveKind.EXPLORE,definition.area,1,plugin.inventory(p),false); plugin.markDirty(profile.uuid) } }
        players.forEach { it.teleport(Location(world,x.toDouble(),DungeonArenaLayout.PLAYER_SPAWN_Y.toDouble(),(z+DungeonArenaLayout.PLAYER_SPAWN_Z_OFFSET).toDouble())); it.sendMessage(plugin.message(it,"ec.dungeon.entry", "message" to definition.entryMessage)) }
    }

    /** 対応する同梱アリーナがあればslot区画を初期化して配置します。 */
    private fun placeDungeonSchematic(world: World, dungeonId: String, centerX: Int, centerZ: Int): Boolean {
        val path = WorldSchematicLayout.dungeonPath(dungeonId) ?: return false
        val schematic = loadedSchematics[path] ?: runCatching { SchematicResources.load(path) }
            .onFailure { plugin.logger.warning("Could not read dungeon schematic $path: ${it.message}") }
            .getOrNull()?.also { loadedSchematics[path] = it } ?: return false
        if (schematic.width != DungeonArenaLayout.SCHEMATIC_WIDTH_BLOCKS ||
            schematic.height != DungeonArenaLayout.SCHEMATIC_HEIGHT_BLOCKS ||
            schematic.length != DungeonArenaLayout.SCHEMATIC_LENGTH_BLOCKS
        ) {
            plugin.logger.warning("Dungeon schematic has unexpected dimensions: $path (${schematic.width}x${schematic.height}x${schematic.length})")
            return false
        }
        val x = centerX - DungeonArenaLayout.SCHEMATIC_ORIGIN_OFFSET_BLOCKS
        val y = DungeonArenaLayout.SCHEMATIC_ORIGIN_Y
        val z = centerZ - DungeonArenaLayout.SCHEMATIC_ORIGIN_OFFSET_BLOCKS
        clearSchematicArena(world, x, y, z)
        return runCatching {
            SchematicPlacer.place(schematic, world, x, y, z, skipAir = false) { message, error ->
                plugin.logger.warning(if (error == null) message else "$message: ${error.message}")
            }
            schematicSlots += (centerX - DungeonArenaLayout.FIRST_CENTER_X) / DungeonArenaLayout.SLOT_SPACING_BLOCKS
            true
        }.getOrElse { error ->
            clearSchematicArena(world, x, y, z)
            plugin.logger.warning("Could not place dungeon schematic $path; using procedural fallback: ${error.message}")
            false
        }
    }

    /** インスタンス専用の立方領域を空にして、前回の内容を残しません。 */
    private fun clearSchematicArena(world: World, originX: Int, originY: Int, originZ: Int) {
        for (y in 0 until DungeonArenaLayout.SCHEMATIC_HEIGHT_BLOCKS) {
            for (z in 0 until DungeonArenaLayout.SCHEMATIC_LENGTH_BLOCKS) {
                for (x in 0 until DungeonArenaLayout.SCHEMATIC_WIDTH_BLOCKS) {
                    world.getBlockAt(originX + x, originY + y, originZ + z).setType(Material.AIR, false)
                }
            }
        }
    }
    private fun spawnWave(instance:Instance,world:World,x:Int,z:Int) {
        val definition=instance.definition
        val wave=definition.waves.getOrNull(instance.waveIndex)
        val bossId=wave?.boss ?: definition.boss
        val bossOffset=wave?.bossOffset ?: definition.bossOffset
        val guards=wave?.guards ?: definition.guards
        val offsets=wave?.guardOffsets ?: definition.guardOffsets
        val spawned=mutableListOf<org.bukkit.entity.LivingEntity>()
        try {
            val boss=enemies.spawn(bossId,Location(world,(x+bossOffset.first).toDouble(),DungeonArenaLayout.ENTITY_SPAWN_Y.toDouble(),(z+bossOffset.second).toDouble()),"instance:${instance.slot}:wave:${instance.waveIndex}",1,instance.members.toSet()) ?: error("ec.error.spawn_cap")
            spawned += boss
            guards.zip(offsets).forEach { (guard,offset) ->
                val entity=enemies.spawn(guard,Location(world,x+offset.first.toDouble(),DungeonArenaLayout.ENTITY_SPAWN_Y.toDouble(),z+offset.second.toDouble()),"instance:${instance.slot}:wave:${instance.waveIndex}:guards",guards.size.coerceAtLeast(1),instance.members.toSet())
                if(entity == null) error("ec.error.guard_spawn_failed")
                spawned += entity
            }
        } catch(error:Exception) { spawned.forEach { it.remove() }; throw error }
        instance.entities.clear(); instance.entities.addAll(spawned.map { it.uniqueId }); instance.boss=spawned.first().uniqueId
    }
    fun defeated(id: UUID) {
        val instance=instances.values.firstOrNull { it.boss == id && !it.completed } ?: return
        val waveCount=instance.definition.waves.size.coerceAtLeast(1)
        if(instance.waveIndex+1 < waveCount) {
            instance.entities.forEach { Bukkit.getEntity(it)?.remove() }
            instance.waveIndex++
            val world=Bukkit.getWorld(DemoWorld.NAME) ?: return
            val origin=DungeonArenaLayout.originForSlot(instance.slot); val x=origin.x; val z=origin.z
            try {
                spawnWave(instance,world,x,z)
                instance.members.forEach { member -> Bukkit.getPlayer(member)?.let { player -> player.sendMessage(plugin.message(player,"ec.dungeon.wave", "current" to instance.waveIndex+1, "total" to waveCount)) } }
            } catch(error:Exception) {
                plugin.logger.warning("Challenge wave could not spawn: ${error.message}")
                instance.members.toList().forEach { Bukkit.getPlayer(it)?.let(::leave) }
            }
            return
        }
        instance.completed=true
        instance.entities.forEach { Bukkit.getEntity(it)?.remove() }
        val seconds = MinecraftTicks.millisToWholeSeconds(monotonicMillis() - instance.startedAtMillis)
        val ranks=instance.definition.scoreRanks
        if(ranks.isNotEmpty()) {
            val rank=net.nakaymi.etherealchronicle.core.challengeRank(seconds,ranks)
            instance.members.forEach { member ->
                var best: net.nakaymi.etherealchronicle.core.ChallengeRecord? = null
                var hasFirstReward=false
                adventure.profileTransaction(member,{ profile ->
                    val (updatedBest,_)=profile.recordChallenge(instance.definition.id,seconds,rank)
                    best=updatedBest
                    val firstRewards=firstClearRanks(profile,ranks,seconds)
                    hasFirstReward=firstRewards.isNotEmpty()
                    val itemRewards=firstRewards.flatMap { it.firstClearItems.entries }
                        .groupBy({ it.key },{ it.value }).mapValues { (_,amounts) -> amounts.sum() }
                    val flags=firstRewards.mapNotNull { it.firstClearFlag }.toSet()
                    // 初回報酬flagとmailboxをクリア記録と同じ保存へ含めます。
                    profile.returnToMailbox(itemRewards)
                    profile.flags.addAll(flags)
                }) {
                    val player=Bukkit.getPlayer(member)?.takeIf { it.isOnline } ?: return@profileTransaction
                    best?.let { record ->
                        player.sendMessage(plugin.message(player,"ec.dungeon.result", "message" to instance.definition.clearMessage, "seconds" to seconds, "rank" to rank, "best_seconds" to record.bestSeconds, "best_rank" to record.bestRank))
                    }
                    if(hasFirstReward) {
                        adventure.claimPending(player)
                        player.sendMessage(plugin.message(player,"ec.dungeon.first_rank_reward"))
                    }
                }
            }
        } else instance.members.forEach { member -> Bukkit.getPlayer(member)?.let { it.sendMessage(plugin.message(it,"ec.dungeon.clear", "message" to instance.definition.clearMessage)) } }
    }
    fun leave(player: Player) {
        val instance=instances.values.firstOrNull { player.uniqueId in it.members } ?: return
        instance.returns[player.uniqueId]?.let { destination -> if(destination.world != null) player.teleport(destination) }
        instance.members.remove(player.uniqueId)
        if(instance.members.isEmpty()) close(instance)
    }
    private fun close(instance: Instance) {
        enemies.removeParticipants(instance.returns.keys)
        if (instance.slot in schematicSlots) {
            val origin = DungeonArenaLayout.originForSlot(instance.slot)
            Bukkit.getWorld(DemoWorld.NAME)?.let { world ->
                clearSchematicArena(
                    world,
                    origin.x - DungeonArenaLayout.SCHEMATIC_ORIGIN_OFFSET_BLOCKS,
                    DungeonArenaLayout.SCHEMATIC_ORIGIN_Y,
                    origin.z - DungeonArenaLayout.SCHEMATIC_ORIGIN_OFFSET_BLOCKS,
                )
            }
            schematicSlots.remove(instance.slot)
        }
        instances.remove(instance.slot)
    }
    fun recover(player: Player) {
        if(player.world.name == DemoWorld.NAME && player.x >= DungeonArenaLayout.RECOVERY_MIN_COORDINATE && player.z >= DungeonArenaLayout.RECOVERY_MIN_COORDINATE && instances.values.none { player.uniqueId in it.members }) player.teleport(player.world.spawnLocation)
    }
    fun tick() {
        tick+=PaperTaskIntervals.PLAYER_UPDATE_TICKS
        instances.values.toList().filter { tick-it.started >= plugin.snapshot.world.tuning.instanceTimeout }.forEach { instance ->
            instance.members.toList().forEach { Bukkit.getPlayer(it)?.let { p -> p.sendMessage(plugin.message(p,"ec.dungeon.timeout")); leave(p) } }; close(instance)
        }
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) fun teleport(e: PlayerTeleportEvent) {
        val instance=instances.values.firstOrNull { e.player.uniqueId in it.members } ?: return
        val to=e.to
        if(to.world?.name != DemoWorld.NAME || !DungeonArenaLayout.isInsideBounds(to,instance.slot)) {
            instance.members.remove(e.player.uniqueId); if(instance.members.isEmpty()) close(instance)
        }
    }
    @EventHandler fun quit(e: PlayerQuitEvent) = leave(e.player)
    @EventHandler fun respawn(e: PlayerRespawnEvent) { val instance=instances.values.firstOrNull { e.player.uniqueId in it.members } ?: return; e.respawnLocation=instance.returns[e.player.uniqueId] ?: e.respawnLocation; instance.members.remove(e.player.uniqueId); if(instance.members.isEmpty()) close(instance) }
    @EventHandler fun move(e: PlayerMoveEvent) { if(e.player.world.name == DemoWorld.NAME && e.to.y < DungeonArenaLayout.VOID_RECOVERY_Y) { e.player.teleport(e.player.world.spawnLocation); leave(e.player) } }
    fun shutdown() { instances.values.toList().forEach { instance -> instance.members.toList().forEach { Bukkit.getPlayer(it)?.let(::leave) }; close(instance) } }
}


