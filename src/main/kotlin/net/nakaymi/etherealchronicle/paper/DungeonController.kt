package net.nakaymi.etherealchronicle.paper

import net.kyori.adventure.text.Component
import org.bukkit.*
import org.bukkit.entity.Player
import org.bukkit.event.*
import org.bukkit.event.player.*
import java.util.UUID

class DungeonController(private val plugin: RpgPlugin,private val adventure: AdventureController,private val enemies: EnemyController): Listener {
    data class Instance(val slot: Int,val members: MutableSet<UUID>,val returns: Map<UUID,Location>,val started: Long,var boss: UUID?=null,var completed: Boolean=false)
    private val instances=mutableMapOf<Int,Instance>()
    private var tick=0L
    private val built=mutableSetOf<Int>()
    init { plugin.server.pluginManager.registerEvents(this,plugin); adventure.enterDungeon=::enter }
    fun activeCount() = instances.size
    fun enter(player: Player) {
        require(instances.values.none { player.uniqueId in it.members }) { "既にダンジョン内です。" }
        val party=adventure.parties.party(player.uniqueId)
        require(party == null || party.leader == player.uniqueId) { "リーダーが入場してください。" }
        val players=adventure.parties.members(player.uniqueId).map { Bukkit.getPlayer(it) ?: error("全員が参加してから入場してください。") }
        require(players.all { p -> p.world == player.world && p.location.distanceSquared(player.location) <= 32*32 && plugin.profiles[p.uniqueId]?.classId != null }) { "仲間全員が近くに集まってください。" }
        if(plugin.snapshot.world.chapters.isNotEmpty()) require(players.all { "ec:flag_sanctum_unlocked" in plugin.profiles.getValue(it.uniqueId).flags }) { "仲間全員が聖堂への道を開放してください。" }
        val world=Bukkit.getWorld(DemoWorld.NAME) ?: error("専用ワールドの準備ができていません。")
        val slot=(0 until plugin.snapshot.world.tuning.instanceCap).firstOrNull { it !in instances } ?: error("聖堂が満員です。しばらくお待ちください。")
        val bossId=plugin.snapshot.world.enemies.values.firstOrNull { it.id == "ec:boss_void_warden" }?.id ?: error("聖堂のボスが未設定です。")
        val instance=Instance(slot,players.map { it.uniqueId }.toMutableSet(),players.associate { it.uniqueId to it.location.clone() },tick)
        val x=1024+slot*192; val z=1024
        if(built.add(slot)) for(dx in -24..24) for(dz in -24..24) {
            world.getBlockAt(x+dx,64,z+dz).type=Material.DEEPSLATE_TILES
            if(kotlin.math.abs(dx) == 24 || kotlin.math.abs(dz) == 24) for(y in 65..68) world.getBlockAt(x+dx,y,z+dz).type=Material.POLISHED_BLACKSTONE_BRICKS
        }
        val boss=enemies.spawn(bossId,Location(world,x.toDouble(),65.0,z+10.0),"instance:$slot",1,instance.members.toSet()) ?: error("魔物の出現上限に達しています。")
        instance.boss=boss.uniqueId; instances[slot]=instance
        if("ec:enemy_void_sentinel" in plugin.snapshot.world.enemies) for(dx in listOf(-8,8)) enemies.spawn("ec:enemy_void_sentinel",Location(world,x+dx.toDouble(),65.0,z-3.0),"instance:$slot:sentinels",2,instance.members.toSet())
        players.forEach { p -> plugin.profiles[p.uniqueId]?.let { profile -> plugin.game.quests.event(profile,net.nakaymi.etherealchronicle.core.ObjectiveKind.EXPLORE,"ec:area_void_sanctum",1,plugin.inventory(p),false); plugin.markDirty(profile.uuid) } }
        players.forEach { it.teleport(Location(world,x.toDouble(),65.0,z-12.0)); it.sendMessage(Component.text("虚核の聖堂へ入場しました。/ec dungeon leave で帰還できます。")) }
    }
    fun defeated(id: UUID) { instances.values.firstOrNull { it.boss == id }?.let { it.completed=true; it.members.forEach { member -> Bukkit.getPlayer(member)?.sendMessage(Component.text("虚核の守護者を撃破しました！帰還して報告しましょう。")) } } }
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
            instance.members.toList().forEach { Bukkit.getPlayer(it)?.let { p -> p.sendMessage(Component.text("聖堂の制限時間を迎えました。")); leave(p) } }; close(instance)
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


