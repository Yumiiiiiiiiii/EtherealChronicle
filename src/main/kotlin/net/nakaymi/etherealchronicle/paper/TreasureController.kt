package net.nakaymi.etherealchronicle.paper

import net.nakaymi.etherealchronicle.core.TreasureChestDefinition
import net.nakaymi.etherealchronicle.core.claimTreasure
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Interaction
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.persistence.PersistentDataType

/** 宣言座標に宝箱表示を置き、once flag と報酬を AdventureController の取引 mailbox で確定します。 */
class TreasureController(private val plugin: RpgPlugin) : Listener {
    private val key = NamespacedKey(plugin, "treasure_id")
    private val entities = mutableMapOf<String, MutableList<java.util.UUID>>()
    private val spawning = mutableSetOf<String>()
    private var chestsByChunk: Map<Pair<Int, Int>, List<TreasureChestDefinition>> = emptyMap()

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    /** 定義済み宝箱表示だけを再配置します。 */
    fun reload() {
        entities.values.flatten().forEach { Bukkit.getEntity(it)?.remove() }
        entities.clear()
        val chests = plugin.snapshot.world.treasureChests.values.mapNotNull { chest ->
            plugin.activeWorldProjection.projector.projectHorizontal(chest.position)?.let { chest.copy(position = it) }
        }
        chestsByChunk = chests.groupBy { chunkCoordinates(it.position.x, it.position.z) }
        chests.forEach(::spawn)
    }

    /**
     * 拠点変更時に影響エリアの宝箱表示だけを再配置し、無関係な完成拠点の表示を維持します。
     * `changedAreaIds == null` の場合は全体を再構築します。
     */
    fun refreshWorldAnchors(changedAreaIds: Set<String>? = null) {
        if (changedAreaIds == null) {
            reload()
            return
        }
        if (changedAreaIds.isEmpty()) return
        val projector = plugin.activeWorldProjection.projector
        plugin.snapshot.world.treasureChests.values.forEach { source ->
            val areaId = projector.sourceAreaId(source.position)
            if (areaId == null || areaId in changedAreaIds) {
                entities.remove(source.id)?.forEach { Bukkit.getEntity(it)?.remove() }
            }
        }
        val chests = plugin.snapshot.world.treasureChests.values.mapNotNull { chest ->
            projector.projectHorizontal(chest.position)?.let { chest.copy(position = it) }
        }
        chestsByChunk = chests.groupBy { chunkCoordinates(it.position.x, it.position.z) }
        chests.forEach { chest ->
            val hasValid = entities[chest.id]?.any { Bukkit.getEntity(it)?.isValid == true } == true
            if (!hasValid) spawn(chest)
        }
    }

    private fun spawn(chest: TreasureChestDefinition) {
        if (!spawning.add(chest.id)) return
        try {
            val source = plugin.snapshot.world.treasureChests[chest.id] ?: return
            val position = plugin.projectedPosition(source.position) ?: return
            val world = Bukkit.getWorld(position.world) ?: return
            if (!plugin.isActiveWorld(world)) return
            val (chunkX, chunkZ) = chunkCoordinates(chest.position.x, chest.position.z)
            // 再読込時に未ロード chunk を同期ロードせず、後続の ChunkLoadEvent に配置を任せます。
            if (!world.isChunkLoaded(chunkX, chunkZ)) return
            val loc = Location(world, position.x, position.y, position.z)
            // 対象の設定済み chunk だけ調べ、ワールド全体の entity 走査を避けます。
            world.getChunkAt(loc).entities
                .filter { it.persistentDataContainer.get(key, PersistentDataType.STRING) == chest.id }
                .forEach { it.remove() }
            val display = world.spawn(loc, BlockDisplay::class.java) {
                it.block = Material.CHEST.createBlockData()
                it.isPersistent = false
                it.isInvulnerable = true
                it.persistentDataContainer.set(key, PersistentDataType.STRING, chest.id)
            }
            val hitbox = world.spawn(loc.clone().add(0.5, 0.0, 0.5), Interaction::class.java) {
                it.interactionWidth = 0.9f
                it.interactionHeight = 0.9f
                it.isResponsive = true
                it.isPersistent = false
                it.isInvulnerable = true
                it.persistentDataContainer.set(key, PersistentDataType.STRING, chest.id)
            }
            entities[chest.id] = mutableListOf(display.uniqueId, hitbox.uniqueId)
            world.spawnParticle(
                runCatching { Particle.valueOf(chest.hintParticle) }.getOrDefault(Particle.END_ROD),
                loc.clone().add(TreasureVisualTuning.HINT_CENTER_X_BLOCKS, TreasureVisualTuning.HINT_HEIGHT_BLOCKS, TreasureVisualTuning.HINT_CENTER_Z_BLOCKS),
                TreasureVisualTuning.PARTICLE_COUNT,
                TreasureVisualTuning.PARTICLE_SPREAD_X_BLOCKS,
                TreasureVisualTuning.PARTICLE_SPREAD_Y_BLOCKS,
                TreasureVisualTuning.PARTICLE_SPREAD_Z_BLOCKS,
                TreasureVisualTuning.PARTICLE_SPEED,
            )
        } finally {
            spawning.remove(chest.id)
        }
    }

    @EventHandler
    fun chunkLoad(event: org.bukkit.event.world.ChunkLoadEvent) {
        if (!plugin.isActiveWorld(event.world)) return
        chestsByChunk[event.chunk.x to event.chunk.z].orEmpty().forEach { chest ->
            if (entities[chest.id]?.any { Bukkit.getEntity(it)?.isValid == true } != true) spawn(chest)
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun interact(event: PlayerInteractEntityEvent) {
        if (event.hand != org.bukkit.inventory.EquipmentSlot.HAND) return
        val id = event.rightClicked.persistentDataContainer.get(key, PersistentDataType.STRING) ?: return
        event.isCancelled = true
        val player = event.player
        val profile = plugin.profiles[player.uniqueId] ?: return
        val chest = plugin.snapshot.world.treasureChests[id] ?: return
        if (chest.onceFlag in profile.flags) {
            player.sendMessage(plugin.message(player, "ec.treasure.empty"))
            return
        }
        if (plugin.adventure.locked(profile.uuid)) {
            player.sendMessage(plugin.message(player, "ec.treasure.busy"))
            return
        }
        try {
            plugin.adventure.transaction(player, { _ ->
                require(claimTreasure(profile, chest, plugin.snapshot.balance.currencyGainCap)) { "ec.treasure.empty" }
            }) {
                player.sendMessage(plugin.message(player, "ec.treasure.opened", "currency" to chest.currency))
            }
        } catch (error: Exception) {
            val messageKey = error.message?.takeIf { it == "ec.treasure.empty" } ?: "ec.treasure.failed"
            player.sendMessage(plugin.message(player, messageKey))
        }
    }

    fun close() {
        entities.values.flatten().forEach { Bukkit.getEntity(it)?.remove() }
        entities.clear()
    }
}

/** 負の小数座標も Minecraft と同じ床関数で chunk へ割り当てます。 */
internal fun chunkCoordinates(x: Double, z: Double): Pair<Int, Int> =
    ChunkCoordinates.fromPosition(x, z).let { it.x to it.z }
