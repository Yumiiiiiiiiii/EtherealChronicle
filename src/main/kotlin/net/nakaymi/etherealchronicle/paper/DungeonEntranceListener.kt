package net.nakaymi.etherealchronicle.paper

import net.nakaymi.etherealchronicle.core.OverworldDungeonPortal
import net.nakaymi.etherealchronicle.core.OverworldSiteRegistry
import net.nakaymi.etherealchronicle.core.OverworldSiteStatus
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import java.util.UUID

/**
 * 地上入口への徒歩進入だけを検出し、繰り返し入場と案内を抑制します。
 * ブロック座標が変わらない視点移動や微動は先頭で弾き、次 tick で入口一致を再検証してから入場させます。
 */
class DungeonEntranceListener internal constructor(
    private val plugin: RpgPlugin,
    private val registry: OverworldSiteRegistry,
    private val onEnter: (Player, String) -> Unit,
    private val clock: () -> Long = ::monotonicMillis,
    registerListener: Boolean = true,
) : Listener {
    constructor(
        plugin: RpgPlugin,
        registry: OverworldSiteRegistry,
        dungeons: DungeonController,
    ) : this(plugin, registry, dungeons::enter, ::monotonicMillis, true)

    private val cooldowns = mutableMapOf<UUID, Long>()

    init {
        if (registerListener) plugin.server.pluginManager.registerEvents(this, plugin)
    }

    /** 完成済み入口へ外側から徒歩で入った場合だけ、次 tick で入場処理を呼びます。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun move(event: PlayerMoveEvent) {
        if (event is PlayerTeleportEvent || !registry.isOverworldMode() || !registry.isReady()) return
        val from = event.from
        val to = event.to
        if (from.world != to.world) return
        // 視点変更や同一ブロック内の微移動ではポータル走査自体を行いません。
        if (from.blockX == to.blockX && from.blockY == to.blockY && from.blockZ == to.blockZ) return
        if (to.world.name != registry.activeWorldName()) return
        val now = clock()
        if (now < (cooldowns[event.player.uniqueId] ?: 0L)) return
        val portal = registry.allPortals().firstOrNull { candidate ->
            isInsidePortal(candidate, to.x, to.y, to.z) && !isInsidePortal(candidate, from.x, from.y, from.z)
        } ?: return
        if (registry.siteForArea(portal.areaId)?.status != OverworldSiteStatus.READY) return
        cooldowns[event.player.uniqueId] = now + COOLDOWN_MILLIS
        val player = event.player
        // 移動イベントの処理中に teleport すると移動先に上書きされ得るため次 tick に入場します。
        plugin.server.scheduler.runTask(plugin, Runnable {
            val location = player.location
            val currentPortal = registry.portalForDungeon(portal.dungeonId)
            if (!player.isOnline || !registry.isReady() || location.world.name != registry.activeWorldName() ||
                !isInsidePortal(portal, location.x, location.y, location.z) ||
                registry.siteForArea(portal.areaId)?.status != OverworldSiteStatus.READY ||
                !isSamePortal(currentPortal, portal)
            ) return@Runnable
            try {
                onEnter(player, portal.dungeonId)
            } catch (error: Exception) {
                val reason = plugin.diagnostic(player, error.message ?: "ec.error.dungeon_missing")
                player.sendActionBar(reason)
                player.sendMessage(reason)
            }
        })
    }

    /** 退出したプレイヤーのクールダウン状態を解放します。 */
    @EventHandler
    fun quit(event: PlayerQuitEvent) {
        cooldowns.remove(event.player.uniqueId)
    }

    internal companion object {
        /** 同一プレイヤーの連続ポータル進入・連打通知を抑えるクールダウン（3秒）。 */
        const val COOLDOWN_MILLIS = 3_000L
    }
}

/** 建設ジャーナルの開口部直方体へ足元が入ったかを判定します。 */
internal fun isInsidePortal(portal: OverworldDungeonPortal, x: Double, y: Double, z: Double): Boolean {
    return x >= portal.minX && x < portal.maxX + 1.0 &&
        y >= portal.minY && y < portal.maxY + 1.0 &&
        z >= portal.minZ && z < portal.maxZ + 1.0
}

/** 次 tick 再検証時に、対象ポータルの識別子・出口・範囲が変わっていないかを明示的に確認します。 */
internal fun isSamePortal(current: OverworldDungeonPortal?, expected: OverworldDungeonPortal): Boolean =
    current != null &&
        current.dungeonId == expected.dungeonId &&
        current.areaId == expected.areaId &&
        current.exitX == expected.exitX &&
        current.exitY == expected.exitY &&
        current.exitZ == expected.exitZ &&
        current.minX == expected.minX &&
        current.minY == expected.minY &&
        current.minZ == expected.minZ &&
        current.maxX == expected.maxX &&
        current.maxY == expected.maxY &&
        current.maxZ == expected.maxZ
