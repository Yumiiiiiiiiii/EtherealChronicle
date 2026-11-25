package net.nakaymi.etherealchronicle.paper

import net.nakaymi.etherealchronicle.core.WorldSchedule
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.entity.Entity
import org.bukkit.entity.Villager
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.persistence.PersistentDataType
import java.util.UUID

/** 時刻・天候条件付き NPC の spawn、chunk 読込、終了時解放を担当します。 */
internal class NpcController(private val plugin: RpgPlugin) : Listener {
    private val npcKey = NamespacedKey(plugin, "npc_id")
    private val entities = mutableMapOf<String, UUID>()

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    /** 指定 entity から NPC ID を読み取ります。 */
    fun id(entity: Entity): String? = entity.persistentDataContainer.get(npcKey, PersistentDataType.STRING)

    /** 管理 NPC なら安全地帯ダメージ抑止に使える true を返します。 */
    fun isManaged(entity: Entity): Boolean = entity.persistentDataContainer.has(npcKey, PersistentDataType.STRING)

    /** 既存 NPC を消して、現行コンテンツ・時刻・天候に合わせ直します。 */
    fun reload() {
        removeEntities()
        reconcile()
    }

    /**
     * 拠点変更時に影響エリアの NPC だけを再配置します。
     * `changedAreaIds == null` の場合は全 NPC を再構築します。
     */
    fun refreshWorldAnchors(changedAreaIds: Set<String>? = null) {
        if (changedAreaIds == null) {
            reload()
            return
        }
        if (changedAreaIds.isEmpty()) return
        val projector = plugin.activeWorldProjection.projector
        plugin.snapshot.world.npcs.values.forEach { definition ->
            val areaId = projector.sourceAreaId(definition.position)
            if (areaId == null || areaId in changedAreaIds) {
                entities.remove(definition.id)?.let(Bukkit::getEntity)?.remove()
            }
        }
        reconcile()
    }

    /** 現在の NPC 定義だけを確認し、出現条件に合わせて配置・撤去します。 */
    fun tick() = reconcile()

    /** 所有 entity を解放し、内部 index を空にします。 */
    fun close() = removeEntities()

    private fun removeEntities() {
        entities.values.forEach { Bukkit.getEntity(it)?.remove() }
        entities.clear()
    }

    private fun reconcile() {
        plugin.snapshot.world.npcs.values.forEach { definition ->
            val position = plugin.projectedPosition(definition.position)
            val world = position?.let { Bukkit.getWorld(it.world) }
            val shouldExist = world != null && WorldSchedule.matches(
                definition.timeWindows,
                definition.weather,
                world.time,
                world.hasStorm(),
                world.isThundering,
            )
            val existing = entities[definition.id]?.let(Bukkit::getEntity)

            if (!shouldExist) {
                existing?.remove()
                entities.remove(definition.id)
                return@forEach
            }
            if (existing?.isValid == true) return@forEach

            entities.remove(definition.id)
            if (!plugin.isActiveWorld(world)) return@forEach
            val projected = position ?: return@forEach
            val location = Location(
                world,
                projected.x,
                projected.y,
                projected.z,
                projected.yaw,
                0f,
            )
            // Chunk を読み込まず、ロード済み地点だけを管理することで TPS とワールド状態を守ります。
            val chunk = ChunkCoordinates.fromBlocks(location.blockX, location.blockZ)
            if (!npcChunkMaySpawn(world.isChunkLoaded(chunk.x, chunk.z))) return@forEach
            val entity = world.spawn(location, Villager::class.java)
            entity.persistentDataContainer.set(npcKey, PersistentDataType.STRING, definition.id)
            entity.customName(plugin.contentTranslatable("npc", definition.id, "name", definition.name))
            entity.isCustomNameVisible = true
            entity.isPersistent = false
            entity.isInvulnerable = true
            entity.setAI(false)
            entity.isCollidable = false
            entity.isSilent = true
            entities[definition.id] = entity.uniqueId
        }
    }

    @EventHandler
    fun onChunkLoad(event: ChunkLoadEvent) {
        event.chunk.entities.forEach { entity ->
            val id = id(entity) ?: return@forEach
            if (entities[id] != entity.uniqueId) entity.remove()
        }
    }
}
