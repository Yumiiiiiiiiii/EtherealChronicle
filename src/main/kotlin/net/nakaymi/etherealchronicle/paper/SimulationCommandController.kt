package net.nakaymi.etherealchronicle.paper

import net.nakaymi.etherealchronicle.core.*
import net.nakaymi.etherealchronicle.core.OverworldSiteStatus as GameSiteStatus
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.UUID

/** サーバー実体を使わずゲームサービスを検査する管理用仮想プレイヤー操作です。 */
internal class SimulationCommandController(private val plugin: RpgPlugin) {
    private val pendingTravel = mutableMapOf<UUID, UUID>()
    /** 切断後の古いチャンク読込結果が再接続者を移動しないようにします。 */
    fun cancelTravel(playerId: UUID) { pendingTravel.remove(playerId) }
    /** 拠点と入口の準備状態を表示し、管理者だけに修復を許可します。 */
    fun sites(sender: CommandSender, args: List<String>) {
        val registry = plugin.activeWorldProjection.registry
        if (args.getOrNull(1) == "repair") {
            require(sender.hasPermission("etherealchronicle.admin")) { "ec.command.no_permission" }
            require(args.size == 2) { "ec.error.usage: /ec sites repair" }
            sender.sendMessage(plugin.message(sender,
                if (registry.triggerRepair() == "STARTED") "ec.sites.repair_started" else "ec.sites.repair_unavailable"))
            return
        }
        if (args.getOrNull(1) == "acknowledge-cleanup") {
            require(sender.hasPermission("etherealchronicle.admin")) { "ec.command.no_permission" }
            require(args.size == 3) { "ec.error.usage: /ec sites acknowledge-cleanup <area_id>" }
            val areaId = resolveSiteId(args[2]) ?: error("ec.error.area_missing")
            sender.sendMessage(plugin.message(sender,
                if (registry.acknowledgeCleanup(areaId) == "ACKNOWLEDGING") "ec.sites.cleanup_acknowledging"
                else "ec.sites.repair_unavailable"))
            return
        }
        require(args.size == 1) { "ec.error.usage: /ec sites [repair|acknowledge-cleanup <area_id>]" }
        registry.allSites().forEach { site ->
            val where = if (site.status == GameSiteStatus.PENDING) plugin.message(sender, "ec.sites.pending_position") else "${site.centerX}, ${site.surfaceY}, ${site.centerZ}"
            val biome = site.biomeKey.ifBlank { plugin.message(sender, "ec.sites.unknown_biome").plainText() }
            val statusKey = when (site.status) {
                GameSiteStatus.PENDING -> "ec.sites.status_pending"
                GameSiteStatus.GENERATING -> "ec.sites.status_generating"
                GameSiteStatus.READY -> "ec.sites.status_ready"
                GameSiteStatus.FAILED -> "ec.sites.status_failed"
            }
            sender.sendMessage(plugin.message(sender, "ec.sites.site", "id" to site.areaId, "position" to where,
                "biome" to biome, "status" to plugin.message(sender, statusKey)))
        }
        registry.allPortals().forEach { portal ->
            sender.sendMessage(plugin.message(sender, "ec.sites.portal", "id" to portal.dungeonId,
                "entrance" to "${portal.centerX}, ${portal.centerY}, ${portal.centerZ}",
                "exit" to "${portal.exitX}, ${portal.exitY}, ${portal.exitZ}"))
        }
    }

    /** 地点 ID の短縮形を解決します。曖昧な省略形は受け付けません。 */
    fun resolveSiteId(input: String): String? {
        val short = input.lowercase().removePrefix("ec:").removePrefix("area_")
        val matches = plugin.snapshot.world.areas.keys.filter { it.removePrefix("ec:area_") == short }
        return matches.singleOrNull()
    }

    /** チャンクを非同期で読み込み、地表と頭上を検査してから移動します。 */
    fun travel(player: Player, input: String) {
        require(player.uniqueId !in pendingTravel) { "ec.error.transaction_busy" }
        val areaId = resolveSiteId(input) ?: error("ec.error.area_missing")
        val area = plugin.snapshot.world.areas.getValue(areaId)
        val source = Position(area.world, (area.minX + area.maxX) / 2, 65.0, (area.minZ + area.maxZ) / 2)
        val horizontal = plugin.activeWorldProjection.projector.projectHorizontal(source, areaId) ?: error("ec.error.world_unavailable")
        val world = plugin.server.getWorld(horizontal.world) ?: error("ec.error.world_unavailable")
        val chunk = ChunkCoordinates.fromPosition(horizontal.x, horizontal.z)
        val token = UUID.randomUUID()
        pendingTravel[player.uniqueId] = token
        world.getChunkAtAsync(chunk.x, chunk.z).whenComplete { _, failure -> plugin.sync {
            if (!player.isOnline || player.isDead || pendingTravel[player.uniqueId] != token) return@sync
            pendingTravel.remove(player.uniqueId)
            val target = if (failure == null) plugin.projectedPosition(source, areaId) else null
            if (target == null || target.world != world.name) { player.sendMessage(plugin.message(player, "ec.error.world_unavailable")); return@sync }
            val location = Location(world, target.x + 0.5, target.y, target.z + 0.5)
            val ground = location.clone().subtract(0.0, 1.0, 0.0).block.type
            val safe = world.worldBorder.isInside(location) && location.block.type.isAir &&
                location.clone().add(0.0, 1.0, 0.0).block.type.isAir && ground.isSolid &&
                ground !in setOf(org.bukkit.Material.MAGMA_BLOCK, org.bukkit.Material.CACTUS, org.bukkit.Material.CAMPFIRE, org.bukkit.Material.SOUL_CAMPFIRE)
            if (!safe || !player.teleport(location)) player.sendMessage(plugin.message(player, "ec.error.teleport_destination"))
            else player.sendMessage(plugin.message(player, "ec.sites.travel_success", "name" to area.name))
        } }
    }
    /** 仮想プロフィールへ指定操作を適用し、結果を送信者へ返します。 */
    fun run(sender: CommandSender, args: List<String>) {
        require(args.size >= 3 && args[0] == "sim") { "ec.error.sim_usage" }
        val operation = args[1]; val name = args[2]
        val uuid = UUID.nameUUIDFromBytes("ec:simulation:$name".toByteArray())
        if(operation == "join") {
            require(name !in plugin.simulated) { "ec.error.sim_exists" }
            plugin.simulated[name] = PlayerProfile(uuid)
            plugin.simSessions[name] = SimulationSession({ plugin.snapshot },plugin.game)
        } else if(operation == "load") {
            plugin.simStore.load(uuid).whenComplete { p,error -> plugin.sync {
                if(error != null) sender.sendMessage(plugin.diagnostic(sender, "ec.error.sim_load_failed: ${error.message}"))
                else { plugin.simulated[name] = p; plugin.simSessions[name]=SimulationSession({ plugin.snapshot },plugin.game); sender.sendMessage(plugin.message(sender, "ec.sim.loaded", "name" to name, "profile" to p)) }
            } }; return
        } else {
            val p = plugin.simulated[name] ?: error("ec.error.sim_join_first")
            val session=plugin.simSessions.getOrPut(name) { SimulationSession({ plugin.snapshot },plugin.game) }
            when(operation) {
                "class" -> { plugin.game.chooseClass(p,args[3]); session.starter(p) }
                "party" -> {
                    val parties=plugin.adventure.parties
                    when(args.getOrNull(3)) {
                        "invite" -> parties.invite(p.uuid,(plugin.simulated[args.getOrNull(4)] ?: error("ec.error.sim_peer_missing")).uuid)
                        "accept" -> parties.accept(p.uuid)
                        "leave" -> parties.leave(p.uuid)
                        "split" -> sender.sendMessage(plugin.message(sender, "ec.sim.party_split", "shares" to parties.splitXp(args[4].toLong(),parties.members(p.uuid))))
                    }
                    sender.sendMessage(plugin.message(sender, "ec.sim.party", "members" to parties.members(p.uuid)))
                }
                "spawn" -> {
                    val world=plugin.server.getWorld(plugin.activeWorldProjection.worldName()) ?: error("ec.error.world_missing")
                    val id=args[3]; val definition=plugin.snapshot.world.enemies[id] ?: error("ec.error.enemy_missing: $id")
                    val pos=definition.spawns.firstOrNull()?.position?.let { plugin.projectedPosition(it) }
                    val location=pos?.let { Location(world,it.x,it.y,it.z) } ?: world.spawnLocation
                    val mob=plugin.enemies.spawn(id,location,"sim:$name:$id",1,setOf(p.uuid),pinForSimulation=true) ?: error("ec.error.spawn_unavailable")
                    sender.sendMessage(plugin.message(sender, "ec.sim.spawn", "uuid" to mob.uniqueId, "hp" to plugin.enemies.state(mob.uniqueId)?.boss?.hp))
                }
                "hit" -> {
                    val entity=UUID.fromString(args[3]); val damage=args[4].toDouble(); require(damage.isFinite() && damage > 0 && damage <= plugin.snapshot.balance.damageCap) { "ec.error.damage_range: ${plugin.snapshot.balance.damageCap}" }
                    val state=plugin.enemies.state(entity) ?: error("ec.error.target_missing")
                    require(state.boss.hp > 0) { "ec.error.target_defeated" }
                    require(state.participants.isEmpty() || p.uuid in state.participants) { "ec.error.target_participant" }
                    val killed=plugin.enemies.applyVirtualDamage(entity,p.uuid,damage)
                    if(killed) simulationResult(sender, session.execute(p,"kill",listOf(state.boss.definition.id)))
                    sender.sendMessage(plugin.message(sender, "ec.sim.hit", "hp" to state.boss.hp, "killed" to killed, "phase" to (state.boss.phase()?.id ?: "none")))
                }
                "despawn" -> { plugin.enemies.removeSimulation(p.uuid); sender.sendMessage(plugin.message(sender, "ec.sim.despawned")) }
                "skill" -> {
                    val c = plugin.snapshot.classes[p.classId ?: error("ec.error.class_missing")]
                    val skill = plugin.snapshot.skills[args.getOrNull(3) ?: c.skills[p.selectedSkill]]
                    require(skill.id in c.skills)
                    sender.sendMessage(plugin.message(sender, "ec.sim.cast", "result" to plugin.game.skills.cast(p,skill)))
                }
                "save" -> { plugin.simStore.save(p).whenComplete { _,error -> plugin.sync { sender.sendMessage(if(error == null) plugin.message(sender, "ec.sim.saved", "name" to name) else plugin.message(sender, "ec.error.sim_save_failed", "reason" to (error.message ?: "unknown"))) } }; return }
                "dump" -> Unit
                else -> simulationResult(sender, session.execute(p,operation,args.drop(3)))
            }
        }
        sender.sendMessage(plugin.message(sender, "ec.sim.operation", "operation" to operation, "name" to name, "profile" to (plugin.simulated[name]?.toString() ?: "missing")))
    }

    private fun simulationResult(sender: CommandSender, result: String) {
        val parts = result.split('|', limit = 2)
        if (parts.size != 2 || !parts[0].startsWith("ec" + ".sim.")) {
            sender.sendMessage(plugin.message(sender, "ec.sim.result", "result" to result))
            return
        }
        val values = parts[1].split(';').mapNotNull { field ->
            val separator = field.indexOf('=')
            if (separator < 1) null else field.substring(0, separator) to field.substring(separator + 1)
        }.toTypedArray()
        sender.sendMessage(plugin.message(sender, parts[0], *values))
    }
}
