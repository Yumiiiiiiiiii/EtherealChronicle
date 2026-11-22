package net.nakaymi.etherealchronicle.paper

import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Event
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.EntityTargetLivingEntityEvent
import org.bukkit.event.entity.FoodLevelChangeEvent
import org.bukkit.event.entity.ProjectileLaunchEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerPortalEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.plugin.Plugin
import java.util.UUID

/**
 * 必須パック未適用者の操作を他のゲームリスナーより先に止めます。
 *
 * 配信が必須かつ利用可能な場合だけ登録し、待機者がいない時は各イベントを即時に抜けます。
 */
internal class RequiredPackLockListener(
    private val plugin: Plugin,
    private val isLocked: (UUID) -> Boolean,
    private val hasWaiters: () -> Boolean,
) : Listener {
    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    private fun lock(player: Player): Boolean = hasWaiters() && isLocked(player.uniqueId)

    /** ブロック座標またはワールドが変わる移動だけを止めます。 */
    @EventHandler(priority = EventPriority.LOWEST)
    fun movement(event: org.bukkit.event.player.PlayerMoveEvent) {
        if (!lock(event.player)) return
        val destination = event.to ?: return
        if (event.from.world != destination.world) {
            event.isCancelled = true
            return
        }
        if (destination.y < event.from.y) {
            // Bukkitの飛行検査を起こさないよう、操作待ち中でも重力による落下だけ許可します。
            if (destination.x != event.from.x || destination.z != event.from.z) {
                event.to = destination.clone().apply { x = event.from.x; z = event.from.z }
            }
            return
        }
        if (event.from.blockX != destination.blockX ||
            event.from.blockY != destination.blockY ||
            event.from.blockZ != destination.blockZ
        ) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun command(event: PlayerCommandPreprocessEvent) {
        if (lock(event.player)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun inventoryOpen(event: InventoryOpenEvent) {
        val player = event.player as? Player ?: return
        if (lock(player)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun inventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        if (lock(player)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun inventoryDrag(event: InventoryDragEvent) {
        val player = event.whoClicked as? Player ?: return
        if (lock(player)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun interact(event: PlayerInteractEvent) {
        if (!lock(event.player)) return
        event.isCancelled = true
        event.setUseItemInHand(Event.Result.DENY)
        event.setUseInteractedBlock(Event.Result.DENY)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun interactEntity(event: PlayerInteractEntityEvent) {
        if (lock(event.player)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun interactAtEntity(event: PlayerInteractAtEntityEvent) {
        if (lock(event.player)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun hotbar(event: PlayerItemHeldEvent) {
        if (lock(event.player)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun offhand(event: PlayerSwapHandItemsEvent) {
        if (lock(event.player)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun drop(event: PlayerDropItemEvent) {
        if (lock(event.player)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun breakBlock(event: BlockBreakEvent) {
        if (lock(event.player)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun placeBlock(event: BlockPlaceEvent) {
        if (lock(event.player)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun portal(event: PlayerPortalEvent) {
        if (lock(event.player)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun pickup(event: EntityPickupItemEvent) {
        val player = event.entity as? Player ?: return
        if (lock(player)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun projectileLaunch(event: ProjectileLaunchEvent) {
        val shooter = event.entity.shooter as? Player ?: return
        if (lock(shooter)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun target(event: EntityTargetLivingEntityEvent) {
        val target = event.target as? Player ?: return
        if (lock(target)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun food(event: FoodLevelChangeEvent) {
        val player = event.entity as? Player ?: return
        if (lock(player)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun damage(event: EntityDamageEvent) {
        if (!hasWaiters()) return
        if (event.entity is Player && isLocked(event.entity.uniqueId)) {
            event.isCancelled = true
            return
        }
        val byEntity = event as? EntityDamageByEntityEvent ?: return
        val damager = byEntity.damager
        val attacker = if (damager is Projectile) damager.shooter as? Player else damager as? Player
        if (attacker != null && isLocked(attacker.uniqueId)) event.isCancelled = true
    }
}
