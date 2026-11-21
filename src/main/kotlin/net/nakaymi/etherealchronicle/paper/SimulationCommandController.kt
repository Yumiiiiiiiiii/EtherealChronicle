package net.nakaymi.etherealchronicle.paper

import net.nakaymi.etherealchronicle.core.*
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.command.CommandSender
import java.util.UUID

/** サーバー実体を使わずゲームサービスを検査する管理用仮想プレイヤー操作です。 */
internal class SimulationCommandController(private val plugin: RpgPlugin) {
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
                    val world=plugin.server.getWorld("ec_world") ?: error("ec.error.world_missing")
                    val id=args[3]; val definition=plugin.snapshot.world.enemies[id] ?: error("ec.error.enemy_missing: $id")
                    val pos=definition.spawns.firstOrNull()?.position
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
