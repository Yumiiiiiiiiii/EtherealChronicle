package net.nakaymi.etherealchronicle.paper

import net.nakaymi.etherealchronicle.core.OverworldDungeonPortal
import net.nakaymi.etherealchronicle.core.OverworldSiteRegistry
import net.nakaymi.etherealchronicle.core.OverworldSiteStatus
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import java.util.UUID

/** 地上入口への徒歩進入だけを検出し、繰り返し入場と案内を抑制します。 */
class DungeonEntranceListener(
    private val plugin: RpgPlugin,
    private val registry: OverworldSiteRegistry,
    private val dungeons: DungeonController,
) : Listener {
    private val cooldowns = mutableMapOf<UUID, Long>()

    init { plugin.server.pluginManager.registerEvents(this, plugin) }

    /** 完成済み入口へ外側から入った場合だけ入場処理を呼びます。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun move(event: PlayerMoveEvent) {
        if (event is PlayerTeleportEvent || !registry.isOverworldMode()) return
        val from = event.from
        val to = event.to
        if (to.world.name != registry.activeWorldName() || from.world != to.world) return
        if (from.x == to.x && from.y == to.y && from.z == to.z) return
        val now = monotonicMillis()
        if (now < (cooldowns[event.player.uniqueId] ?: 0L)) return
        val portal = registry.allPortals().firstOrNull { candidate ->
            registry.siteForArea(candidate.areaId)?.status == OverworldSiteStatus.READY &&
                isInsidePortal(candidate, to.x, to.y, to.z) && !isInsidePortal(candidate, from.x, from.y, from.z)
        } ?: return
        cooldowns[event.player.uniqueId] = now + COOLDOWN_MILLIS
        val player = event.player
        // 移動イベントの処理中に teleport すると移動先に上書きされ得るため次 tick に入場します。
        plugin.server.scheduler.runTask(plugin, Runnable {
            val location = player.location
            if (!player.isOnline || location.world.name != registry.activeWorldName() ||
                !isInsidePortal(portal, location.x, location.y, location.z) ||
                registry.siteForArea(portal.areaId)?.status != OverworldSiteStatus.READY ||
                registry.portalForDungeon(portal.dungeonId) != portal) return@Runnable
            try {
                dungeons.enter(player, portal.dungeonId)
            } catch (error: Exception) {
                val reason = plugin.diagnostic(player, error.message ?: "ec.error.dungeon_missing")
                player.sendActionBar(reason)
                player.sendMessage(reason)
            }
        })
    }

    /** 退出したプレイヤーの一時状態を解放します。 */
    @EventHandler
    fun quit(event: PlayerQuitEvent) { cooldowns.remove(event.player.uniqueId) }

    private companion object { const val COOLDOWN_MILLIS = 3_000L }
}

/** 開口部中心から半径 2.75 ブロックの球内かを判定します。 */
internal fun isInsidePortal(portal: OverworldDungeonPortal, x: Double, y: Double, z: Double): Boolean {
    val dx = x - portal.centerX
    val dy = y - portal.centerY
    val dz = z - portal.centerZ
    return dx * dx + dy * dy + dz * dz <= 2.75 * 2.75
}
