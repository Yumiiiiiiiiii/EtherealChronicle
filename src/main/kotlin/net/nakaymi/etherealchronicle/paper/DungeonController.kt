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
    data class Instance(val slot: Int,val definition: net.nakaymi.etherealchronicle.core.DungeonDefinition,val members: MutableSet<UUID>,val returns: Map<UUID,Location>,val started: Long,var boss: UUID?=null,var completed: Boolean=false,var waveIndex: Int=0,val entities: MutableSet<UUID> = mutableSetOf(),val startedAtMillis: Long=System.currentTimeMillis())
    private val instances=mutableMapOf<Int,Instance>()
    private var tick=0L
    private val builtPalette=mutableMapOf<Int,List<String>>()
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
        require(players.all { p -> p.world == player.world && p.location.distanceSquared(player.location) <= 32*32 && plugin.profiles[p.uniqueId]?.classId != null }) { "ec.error.party_gather" }
        require(players.all { definition.unlockFlag in plugin.profiles.getValue(it.uniqueId).flags }) { "ec.error.dungeon_unlock_required" }
        val world=Bukkit.getWorld(DemoWorld.NAME) ?: error("ec.error.world_unavailable")
        val slot=(0 until plugin.snapshot.world.tuning.instanceCap).firstOrNull { it !in instances } ?: error("ec.error.dungeon_full")
        val instance=Instance(slot,definition,players.map { it.uniqueId }.toMutableSet(),players.associate { it.uniqueId to it.location.clone() },tick)
        val x=1024+slot*192; val z=1024
        if(dungeonPaletteNeedsBuild(builtPalette[slot],definition.palette)) {
            for(dx in -24..24) for(dz in -24..24) {
            val edge=kotlin.math.abs(dx) == 24 || kotlin.math.abs(dz) == 24
            val center=kotlin.math.abs(dx) <= 2 && kotlin.math.abs(dz) <= 2
            world.getBlockAt(x+dx,64,z+dz).type=Material.valueOf(definition.palette[if(center) 2 else 0])
            if(edge) for(y in 65..68) world.getBlockAt(x+dx,y,z+dz).type=Material.valueOf(definition.palette[1])
            }
            builtPalette[slot]=definition.palette.toList()
        }
        instances[slot]=instance
        try { spawnWave(instance,world,x,z) }
        catch(error:Exception) { close(instance); throw error }
        players.forEach { p -> plugin.profiles[p.uniqueId]?.let { profile -> plugin.game.quests.event(profile,net.nakaymi.etherealchronicle.core.ObjectiveKind.EXPLORE,definition.area,1,plugin.inventory(p),false); plugin.markDirty(profile.uuid) } }
        players.forEach { it.teleport(Location(world,x.toDouble(),65.0,z-12.0)); it.sendMessage(plugin.message(it,"ec.dungeon.entry", "message" to definition.entryMessage)) }
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
            val boss=enemies.spawn(bossId,Location(world,(x+bossOffset.first).toDouble(),65.0,(z+bossOffset.second).toDouble()),"instance:${instance.slot}:wave:${instance.waveIndex}",1,instance.members.toSet()) ?: error("ec.error.spawn_cap")
            spawned += boss
            guards.zip(offsets).forEach { (guard,offset) ->
                val entity=enemies.spawn(guard,Location(world,x+offset.first.toDouble(),65.0,z+offset.second.toDouble()),"instance:${instance.slot}:wave:${instance.waveIndex}:guards",guards.size.coerceAtLeast(1),instance.members.toSet())
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
            val x=1024+instance.slot*192; val z=1024
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
        val seconds=((System.currentTimeMillis()-instance.startedAtMillis)/1000).coerceAtLeast(0)
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
        instances.remove(instance.slot)
    }
    fun recover(player: Player) {
        if(player.world.name == DemoWorld.NAME && player.x >= 900 && player.z >= 900 && instances.values.none { player.uniqueId in it.members }) player.teleport(player.world.spawnLocation)
    }
    fun tick() {
        tick+=20
        instances.values.toList().filter { tick-it.started >= plugin.snapshot.world.tuning.instanceTimeout }.forEach { instance ->
            instance.members.toList().forEach { Bukkit.getPlayer(it)?.let { p -> p.sendMessage(plugin.message(p,"ec.dungeon.timeout")); leave(p) } }; close(instance)
        }
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) fun teleport(e: PlayerTeleportEvent) {
        val instance=instances.values.firstOrNull { e.player.uniqueId in it.members } ?: return
        val centerX=1024+instance.slot*192
        val to=e.to
        if(to.world?.name != DemoWorld.NAME || kotlin.math.abs(to.x-centerX) > 30 || kotlin.math.abs(to.z-1024) > 30) {
            instance.members.remove(e.player.uniqueId); if(instance.members.isEmpty()) close(instance)
        }
    }
    @EventHandler fun quit(e: PlayerQuitEvent) = leave(e.player)
    @EventHandler fun respawn(e: PlayerRespawnEvent) { val instance=instances.values.firstOrNull { e.player.uniqueId in it.members } ?: return; e.respawnLocation=instance.returns[e.player.uniqueId] ?: e.respawnLocation; instance.members.remove(e.player.uniqueId); if(instance.members.isEmpty()) close(instance) }
    @EventHandler fun move(e: PlayerMoveEvent) { if(e.player.world.name == DemoWorld.NAME && e.to.y < 0) { e.player.teleport(e.player.world.spawnLocation); leave(e.player) } }
    fun shutdown() { instances.values.toList().forEach { instance -> instance.members.toList().forEach { Bukkit.getPlayer(it)?.let(::leave) }; close(instance) } }
}


