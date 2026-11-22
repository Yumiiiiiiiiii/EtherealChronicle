package net.nakaymi.etherealchronicle.paper

import net.nakaymi.etherealchronicle.core.OverworldDungeonPortal
import net.nakaymi.etherealchronicle.core.OverworldSiteAnchor
import net.nakaymi.etherealchronicle.core.OverworldSiteRegistry
import net.nakaymi.etherealchronicle.core.PendingOverworldSiteRegistry
import net.nakaymi.etherealchronicle.core.Area
import net.nakaymi.etherealchronicle.core.OverworldSiteStatus as GameSiteStatus
import org.bukkit.World

/** 建設サービスの永続化済み配置を、ゲームプレイの読み取り契約へ変換します。 */
internal class EngineOverworldBootstrap(
    private val plugin: RpgPlugin,
    world: World,
    areas: Collection<Area>,
) : OverworldBootstrapPort {
    private val service = OverworldWorldService(plugin, world)
    private var onChanged: (() -> Unit)? = null
    override val registry: OverworldSiteRegistry = EngineSiteRegistry(world.name, areas, service) { onChanged?.invoke() }

    /** READY 通知と既存ジャーナル読込完了の双方で投影を更新します。 */
    override fun start(onAnchorsChanged: () -> Unit) {
        onChanged = onAnchorsChanged
        service.start { onAnchorsChanged() }.whenComplete { _, failure ->
            if (failure == null) plugin.sync(onAnchorsChanged)
            else plugin.logger.severe("Overworld construction failed: ${failure.message}")
        }
    }

    /** Paper メインスレッド上で建設処理を終了します。 */
    override fun close() = service.close()
}

/** 未完成地点を公開せず、保存された座標と入口だけを投影します。 */
internal class EngineSiteRegistry(
    private val worldName: String,
    areas: Collection<Area>,
    private val service: OverworldWorldService,
    private val onChanged: () -> Unit,
) : OverworldSiteRegistry {
    private val pending = PendingOverworldSiteRegistry(worldName, areas)
    override fun activeWorldName(): String = worldName
    override fun isOverworldMode(): Boolean = true
    override fun isReady(): Boolean = service.current()?.status == OverworldBuildStatus.COMPLETED

    override fun siteForArea(areaId: String): OverworldSiteAnchor? =
        service.current()?.let { layout -> layout.sites.firstOrNull { it.site.id == areaId }
            ?.toGameAnchor(layout.portals.firstOrNull { it.areaId == areaId }) } ?: pending.siteForArea(areaId)

    override fun allSites(): List<OverworldSiteAnchor> = service.current()?.let { layout ->
        layout.sites.map { site -> site.toGameAnchor(layout.portals.firstOrNull { it.areaId == site.site.id }) }
    } ?: pending.allSites()

    override fun portalForDungeon(dungeonId: String): OverworldDungeonPortal? =
        service.current()?.portals?.firstOrNull { it.dungeonId == dungeonId }
            ?.takeIf { siteForArea(it.areaId)?.status == GameSiteStatus.READY }?.toGamePortal()

    override fun allPortals(): List<OverworldDungeonPortal> =
        service.current()?.portals?.mapNotNull { portal ->
            portal.takeIf { siteForArea(it.areaId)?.status == GameSiteStatus.READY }?.toGamePortal()
        }.orEmpty()

    /** 修復を主スレッドで開始し、各 READY と完了時に再投影します。 */
    override fun triggerRepair(): String = runCatching {
        service.repair { onChanged() }.whenComplete { _, failure ->
            if (failure == null) onChanged()
        }
        "STARTED"
    }.getOrElse { "UNAVAILABLE" }

    /** 手動片付け後の明示的な管理者操作でのみ再生成可能にします。 */
    override fun acknowledgeCleanup(areaId: String): String = runCatching {
        service.acknowledgeCleanup(areaId).whenComplete { _, failure ->
            if (failure == null) onChanged()
        }
        "ACKNOWLEDGING"
    }.getOrElse { "UNAVAILABLE" }

}

/** 保存済みのルミナ足元座標と入口をゲーム側の単一拠点へ写します。 */
internal fun OverworldSiteLayout.toGameAnchor(portal: OverworldPortal?): OverworldSiteAnchor {
    val spawn = anchors["spawn"]
    return OverworldSiteAnchor(
        areaId = site.id, centerX = site.x, surfaceY = site.y, centerZ = site.z,
        biomeKey = site.biome, status = GameSiteStatus.valueOf(status.name),
        dungeonId = portal?.dungeonId,
        portalCenterX = portal?.let { (it.min.x + it.max.x) / 2 },
        portalCenterY = portal?.let { (it.min.y + it.max.y) / 2 },
        portalCenterZ = portal?.let { (it.min.z + it.max.z) / 2 },
        portalExitX = portal?.exit?.x, portalExitY = portal?.exit?.y, portalExitZ = portal?.exit?.z,
        plazaSpawnX = spawn?.x, plazaSpawnY = spawn?.y, plazaSpawnZ = spawn?.z,
    )
}

/** 直方体入口の中心と地上の安全出口を転送判定へ渡します。 */
internal fun OverworldPortal.toGamePortal(): OverworldDungeonPortal = OverworldDungeonPortal(
    dungeonId, areaId, (min.x + max.x) / 2, (min.y + max.y) / 2, (min.z + max.z) / 2,
    exit.x, exit.y, exit.z, min.x, min.y, min.z, max.x, max.y, max.z,
)
