package net.nakaymi.etherealchronicle.paper

import net.nakaymi.etherealchronicle.core.*
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.command.CommandSender
import java.util.UUID

/** サーバー実体を使わずゲームサービスを検査する管理用仮想プレイヤー操作です。 */
internal class SimulationCommandController(private val plugin: RpgPlugin) {
    /** 仮想プロフィールへ指定操作を適用し、結果を送信者へ返します。 */
    fun run(sender: CommandSender, args: List<String>) {
        require(args.size >= 3 && args[0] == "sim") { "/ec debug sim <操作> <name> [引数] — join/class/kill/skill/accept/event/complete/dialogue/choice/give/inventory/currency/level/flag/buy/sell/party/spawn/hit/dump/save/load" }
        val operation = args[1]; val name = args[2]
        val uuid = UUID.nameUUIDFromBytes("ec:simulation:$name".toByteArray())
        if(operation == "join") {
            require(name !in plugin.simulated) { "既に参加しています。" }
            plugin.simulated[name] = PlayerProfile(uuid)
            plugin.simSessions[name] = SimulationSession({ plugin.snapshot },plugin.game)
        } else if(operation == "load") {
            plugin.simStore.load(uuid).whenComplete { p,error -> plugin.sync {
                if(error != null) sender.sendMessage(Component.text("SIM load 失敗: ${error.message}"))
                else { plugin.simulated[name] = p; plugin.simSessions[name]=SimulationSession({ plugin.snapshot },plugin.game); sender.sendMessage(Component.text("SIM load $name: $p (仮想持ち物はリセット)")) }
            } }; return
        } else {
            val p = plugin.simulated[name] ?: error("先に sim join を実行してください。")
            val session=plugin.simSessions.getOrPut(name) { SimulationSession({ plugin.snapshot },plugin.game) }
            when(operation) {
                "class" -> { plugin.game.chooseClass(p,args[3]); session.starter(p) }
                "party" -> {
                    val parties=plugin.adventure.parties
                    when(args.getOrNull(3)) {
                        "invite" -> parties.invite(p.uuid,(plugin.simulated[args.getOrNull(4)] ?: error("相手が未参加です。")).uuid)
                        "accept" -> parties.accept(p.uuid)
                        "leave" -> parties.leave(p.uuid)
                        "split" -> sender.sendMessage(Component.text("SIM XP配分=${parties.splitXp(args[4].toLong(),parties.members(p.uuid))}"))
                    }
                    sender.sendMessage(Component.text("SIM party=${parties.members(p.uuid)}"))
                }
                "spawn" -> {
                    val world=plugin.server.getWorld("ec_world") ?: error("専用ワールドがありません。")
                    val id=args[3]; val definition=plugin.snapshot.world.enemies[id] ?: error("魔物が見つかりません。")
                    val pos=definition.spawns.firstOrNull()?.position
                    val location=pos?.let { Location(world,it.x,it.y,it.z) } ?: world.spawnLocation
                    val mob=plugin.enemies.spawn(id,location,"sim:$name:$id",1,setOf(p.uuid),pinForSimulation=true) ?: error("同じ魔物が既に存在するか、出現上限に達しています。")
                    sender.sendMessage(Component.text("SIM spawn=${mob.uniqueId} hp=${plugin.enemies.state(mob.uniqueId)?.boss?.hp}"))
                }
                "hit" -> {
                    val entity=UUID.fromString(args[3]); val damage=args[4].toDouble(); require(damage.isFinite() && damage > 0 && damage <= plugin.snapshot.balance.damageCap) { "ダメージは0より大きく ${plugin.snapshot.balance.damageCap} 以下で指定してください。" }
                    val state=plugin.enemies.state(entity) ?: error("対象が見つかりません。")
                    require(state.boss.hp > 0) { "対象は討伐済みです。" }
                    require(state.participants.isEmpty() || p.uuid in state.participants) { "参加者が一致しません。" }
                    val killed=plugin.enemies.applyVirtualDamage(entity,p.uuid,damage)
                    if(killed) sender.sendMessage(Component.text("SIM ${session.execute(p,"kill",listOf(state.boss.definition.id))}"))
                    sender.sendMessage(Component.text("SIM hit hp=${state.boss.hp} killed=$killed phase=${state.boss.phase()?.id} (実Playerの討伐報酬は発生しません)"))
                }
                "despawn" -> { plugin.enemies.removeSimulation(p.uuid); sender.sendMessage(Component.text("SIM 出現対象とチャンク保持を解除しました。")) }
                "skill" -> {
                    val c = plugin.snapshot.classes[p.classId ?: error("職業未選択")]
                    val skill = plugin.snapshot.skills[args.getOrNull(3) ?: c.skills[p.selectedSkill]]
                    require(skill.id in c.skills)
                    sender.sendMessage(Component.text("SIM cast: ${plugin.game.skills.cast(p,skill)}"))
                }
                "save" -> { plugin.simStore.save(p).whenComplete { _,error -> plugin.sync { sender.sendMessage(Component.text(if(error == null) "SIM save 成功 $name" else "SIM save 失敗: ${error.message}")) } }; return }
                "dump" -> Unit
                else -> sender.sendMessage(Component.text("SIM ${session.execute(p,operation,args.drop(3))}"))
            }
        }
        sender.sendMessage(Component.text("SIM $operation $name: ${plugin.simulated[name]}"))

    }
}

