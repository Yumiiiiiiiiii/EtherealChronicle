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

/** 体が収まる（衝突しない、または薄い雪・カーペットのように立てる）ブロックかを返します。 */
private fun isBodyClear(type: org.bukkit.Material): Boolean =
    !type.isCollidable || type == org.bukkit.Material.SNOW || type.name.endsWith("_CARPET")

/** 足元が固体で、足・頭の位置が通過可能（埋まっていない）なら true。 */
internal fun isStandable(world: org.bukkit.World, x: Int, y: Int, z: Int): Boolean =
    y > world.minHeight && y + 1 < world.maxHeight &&
        world.getBlockAt(x, y - 1, z).type.isSolid && isBodyClear(world.getBlockAt(x, y, z).type) && isBodyClear(world.getBlockAt(x, y + 1, z).type)

/**
 * 地形や建物に埋まった NPC を救うため、指定地点から上方向（次に周囲 3 マスまでの水平）へ探索し、
 * 最初に立てる位置を返します。見つからなければ null（元の位置をそのまま使う）。
 */
internal fun settleNpcLocation(world: org.bukkit.World, location: Location): Location? {
    val bx = location.blockX; val by = location.blockY; val bz = location.blockZ
    fun column(x: Int, z: Int, fromY: Int): Location? {
        for (y in fromY..by + NPC_SETTLE_UP_BLOCKS) {
            if (isStandable(world, x, y, z)) return Location(world, x + 0.5, y.toDouble(), z + 0.5, location.yaw, location.pitch)
        }
        return null
    }
    column(bx, bz, by)?.let { return it }
    for (r in 1..NPC_SETTLE_RADIUS_BLOCKS) {
        for (dx in -r..r) for (dz in -r..r) {
            if (maxOf(kotlin.math.abs(dx), kotlin.math.abs(dz)) != r) continue
            column(bx + dx, bz + dz, by - NPC_SETTLE_DOWN_BLOCKS)?.let { return it }
        }
    }
    return null
}

private const val NPC_SETTLE_UP_BLOCKS = 8
private const val NPC_SETTLE_DOWN_BLOCKS = 2
private const val NPC_SETTLE_RADIUS_BLOCKS = 3

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

    /** `onlyChunk` を指定すると、そのチャンクに立つ NPC だけを対象にします（チャンク読込直後に即座に出現させるため）。 */
    private fun reconcile(onlyChunk: ChunkCoordinates? = null) {
        plugin.snapshot.world.npcs.values.forEach { definition ->
            val position = plugin.projectedPosition(definition.position)
            if (onlyChunk != null && (position == null || ChunkCoordinates.fromPosition(position.x, position.z) != onlyChunk)) return@forEach
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
            var location = Location(
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
            // 地形・建物に埋まっていれば、近くの立てる位置へずらします（ガイドの目的地とは数ブロック内でずれ得る）。
            if (!isStandable(world, location.blockX, location.blockY, location.blockZ)) settleNpcLocation(world, location)?.let { location = it }
            val entity = world.spawn(location, Villager::class.java)
            entity.persistentDataContainer.set(npcKey, PersistentDataType.STRING, definition.id)
            entity.customName(plugin.contentTranslatable("npc", definition.id, "name", definition.name))
            entity.isCustomNameVisible = true
            entity.isPersistent = false
            entity.isInvulnerable = true
            entity.setAI(false)
            // 職業・バイオーム型・レベルで「それっぽい」見た目にします（データは NPC の YAML から）。
            definition.profession?.let { PaperContentValidator.villagerProfession(it)?.let(entity::setProfession) }
            definition.villagerType?.let { PaperContentValidator.villagerType(it)?.let(entity::setVillagerType) }
            entity.villagerLevel = definition.villagerLevel
            entity.villagerExperience = 0
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
        // 到着を最大 1 秒（tick 周期）待たせないよう、読み込まれたチャンクの NPC をすぐ出現させます。
        if (plugin.isActiveWorld(event.world)) {
            val chunk = ChunkCoordinates(event.chunk.x, event.chunk.z)
            // チャンク読込イベント中の entity 追加を避け、次 tick に出現させます。
            plugin.server.scheduler.runTask(plugin, Runnable { reconcile(chunk) })
        }
    }
}
