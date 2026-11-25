package net.nakaymi.etherealchronicle.paper

import net.nakaymi.etherealchronicle.core.GameException
import net.nakaymi.etherealchronicle.core.OverworldSiteStatus
import net.nakaymi.etherealchronicle.core.Position
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.command.CommandSender

/**
 * 実サーバー上でプレイヤー無しに主要な前提（拠点・ポータル・スキーマ・各地点の投影）を検査する自己診断です。
 * `/ec selftest`（管理者・コンソール可）で実行し、結果を送信者とログ（`EC_SELFTEST_PASS` / `EC_SELFTEST_FAIL`）へ出します。
 * チャンクは非同期で読み込み、メインスレッドを止めません。
 */
internal class SelfTestController(private val plugin: RpgPlugin) {
    /** 1 件の検査結果です。 */
    private data class Check(val name: String, val ok: Boolean, val detail: String)

    /** 投影を確認する地点です。area が null なら座標から所属を自動解決します。 */
    private data class Target(val label: String, val position: Position, val area: String?, val allowWater: Boolean = false, val mustBeStandable: Boolean = false)

    private var running = false

    /** 診断を開始します。多重実行は拒否します。 */
    fun run(sender: CommandSender) {
        require(sender.hasPermission(ADMIN_PERMISSION)) { "ec.command.no_permission" }
        if (running) throw GameException("ec.error.transaction_busy")
        running = true
        sender.sendMessage(plugin.message(sender, "ec.selftest.start"))
        val checks = mutableListOf<Check>()
        try {
            checks += checkSites()
            checks += checkPortals()
            checks += checkDungeonSchematics()
        } catch (error: Exception) {
            checks += Check("static", false, error.message.orEmpty())
        }
        val targets = collectTargets()
        checkTargets(targets) { failures ->
            checks += Check("positions", failures.isEmpty(), if (failures.isEmpty()) "${targets.size} targets" else
                "${failures.size}/${targets.size} failed: " + failures.take(MAX_LISTED_FAILURES).joinToString())
            checkPortalExits { exits ->
                checks += exits
                finish(sender, checks)
            }
        }
    }

    /** 全拠点が READY であることを確認します。 */
    private fun checkSites(): Check {
        val sites = plugin.activeWorldProjection.registry.allSites()
        val notReady = sites.filter { it.status != OverworldSiteStatus.READY }.map { it.areaId }
        return Check("sites", sites.isNotEmpty() && notReady.isEmpty(), "${sites.size - notReady.size}/${sites.size} ready" +
            if (notReady.isEmpty()) "" else " (not ready: ${notReady.joinToString()})")
    }

    /** 全ダンジョンに入口ポータルがあり、拠点が READY であることを確認します。 */
    private fun checkPortals(): Check {
        val registry = plugin.activeWorldProjection.registry
        val missing = plugin.snapshot.world.dungeons.keys.filter { id ->
            val portal = registry.portalForDungeon(id)
            portal == null || registry.siteForArea(portal.areaId)?.status != OverworldSiteStatus.READY
        }
        return Check("portals", missing.isEmpty(), if (missing.isEmpty()) "${plugin.snapshot.world.dungeons.size} dungeons" else "missing: ${missing.joinToString()}")
    }

    /** 同梱ダンジョンアリーナが存在し、全ブロック状態が現行サーバーで有効であることを確認します。 */
    private fun checkDungeonSchematics(): Check {
        val problems = mutableListOf<String>()
        plugin.snapshot.world.dungeons.keys.forEach { id ->
            val path = WorldSchematicLayout.dungeonPath(id)
            val schematic = path?.let(SchematicResources::load)
            if (schematic == null) { problems += "$id: schematic missing"; return@forEach }
            schematic.palette.forEach { state ->
                if (runCatching { Bukkit.createBlockData(state) }.isFailure) problems += "$id: invalid $state"
            }
        }
        return Check("dungeon-schematics", problems.isEmpty(), if (problems.isEmpty()) "ok" else problems.take(MAX_LISTED_FAILURES).joinToString())
    }

    /** NPC・敵スポーン・宝箱・採集地点・釣り場を、投影対象としてまとめます。 */
    private fun collectTargets(): List<Target> {
        val world = plugin.snapshot.world
        return buildList {
            world.npcs.values.forEach { add(Target("npc:${it.id}", it.position, null, mustBeStandable = true)) }
            world.enemies.values.forEach { enemy -> enemy.spawns.forEach { add(Target("enemy:${enemy.id}", it.position, null)) } }
            world.treasureChests.values.forEach { add(Target("chest:${it.id}", it.position, null)) }
            world.gatheringNodes.values.forEach { node ->
                node.locations.forEach { add(Target("gather:${node.id}/${it.id}", Position(it.world, it.x.toDouble(), it.y.toDouble(), it.z.toDouble()), it.area)) }
            }
            world.fishingSpots.values.forEach { add(Target("fish:${it.id}", it.center, it.area, allowWater = true)) }
        }
    }

    /** チャンク単位でまとめて非同期に読み込み、各地点が地表へ投影できることを順に確認します。 */
    private fun checkTargets(targets: List<Target>, done: (List<String>) -> Unit) {
        val projector = plugin.activeWorldProjection.projector
        val world = Bukkit.getWorld(plugin.activeWorldProjection.worldName())
        val failures = mutableListOf<String>()
        val byChunk = linkedMapOf<ChunkCoordinates, MutableList<Target>>()
        targets.forEach { target ->
            val horizontal = if (target.area == null) projector.projectHorizontal(target.position) else projector.projectHorizontal(target.position, target.area)
            if (horizontal == null) failures += "${target.label}(unprojectable)"
            else byChunk.getOrPut(ChunkCoordinates.fromPosition(horizontal.x, horizontal.z)) { mutableListOf() } += target
        }
        if (world == null) { done(failures + "world unavailable"); return }
        val queue = ArrayDeque(byChunk.entries.toList())
        fun next() {
            val entry = queue.removeFirstOrNull() ?: return done(failures)
            plugin.withChunk(world, entry.key.x, entry.key.z) { failure ->
                entry.value.forEach { target ->
                    val projected = if (failure != null) null else plugin.projectedPosition(target.position, target.area, target.allowWater)
                    if (projected == null) failures += target.label
                    else if (target.mustBeStandable && !canStand(world, projected)) failures += "${target.label}(buried@${projected.x.toInt()},${projected.y.toInt()},${projected.z.toInt()} floor=${world.getBlockAt(projected.x.toInt(), projected.y.toInt() - 1, projected.z.toInt()).type} feet=${world.getBlockAt(projected.x.toInt(), projected.y.toInt(), projected.z.toInt()).type} head=${world.getBlockAt(projected.x.toInt(), projected.y.toInt() + 1, projected.z.toInt()).type})"
                }
                next()
            }
        }
        next()
    }

    /** NPC の実際の出現位置（埋まっていれば近くへずらした位置）が立てる場所かを返します。 */
    private fun canStand(world: org.bukkit.World, p: Position): Boolean {
        val at = Location(world, p.x, p.y, p.z)
        return isStandable(world, at.blockX, at.blockY, at.blockZ) || settleNpcLocation(world, at) != null
    }

    /** 各ポータルの出口チャンクを非同期で読み込み、読込中に安全な足場であることを確認します。 */
    private fun checkPortalExits(done: (Check) -> Unit) {
        val world = Bukkit.getWorld(plugin.activeWorldProjection.worldName()) ?: return done(Check("portal-exits", false, "world unavailable"))
        val unsafe = mutableListOf<String>()
        val queue = ArrayDeque(plugin.activeWorldProjection.registry.allPortals())
        fun next() {
            val portal = queue.removeFirstOrNull() ?: return done(Check("portal-exits", unsafe.isEmpty(), if (unsafe.isEmpty()) "ok" else "unsafe: ${unsafe.joinToString()}"))
            plugin.withChunk(world, portal.exitX shr 4, portal.exitZ shr 4) { failure ->
                val safe = failure == null && SafeStandingSpot.isSafe(Location(world, portal.exitX + 0.5, portal.exitY.toDouble(), portal.exitZ + 0.5))
                if (!safe) unsafe += portal.dungeonId
                next()
            }
        }
        next()
    }

    /** 結果を送信者とログへ出します。 */
    private fun finish(sender: CommandSender, checks: List<Check>) {
        running = false
        checks.forEach { c ->
            sender.sendMessage(plugin.message(sender, "ec.selftest.check",
                "status" to (if (c.ok) "PASS" else "FAIL"), "name" to c.name, "detail" to c.detail))
        }
        val failed = checks.count { !it.ok }
        sender.sendMessage(plugin.message(sender, "ec.selftest.summary", "passed" to (checks.size - failed), "total" to checks.size))
        plugin.logger.info(if (failed == 0) "EC_SELFTEST_PASS ${checks.size}" else "EC_SELFTEST_FAIL $failed/${checks.size}")
    }

    private companion object {
        const val ADMIN_PERMISSION = "etherealchronicle.admin"
        const val MAX_LISTED_FAILURES = 8
    }
}
