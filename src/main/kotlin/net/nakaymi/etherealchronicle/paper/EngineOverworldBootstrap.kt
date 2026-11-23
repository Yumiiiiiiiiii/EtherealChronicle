package net.nakaymi.etherealchronicle.paper

import net.nakaymi.etherealchronicle.core.Area
import net.nakaymi.etherealchronicle.core.OverworldCleanupAckResult
import net.nakaymi.etherealchronicle.core.OverworldDungeonPortal
import net.nakaymi.etherealchronicle.core.OverworldRepairResult
import net.nakaymi.etherealchronicle.core.OverworldSiteAnchor
import net.nakaymi.etherealchronicle.core.OverworldSiteRegistry
import net.nakaymi.etherealchronicle.core.PendingOverworldSiteRegistry
import net.nakaymi.etherealchronicle.core.OverworldSiteStatus as GameSiteStatus
import org.bukkit.World
import java.util.logging.Logger

/** 建設サービスの永続化済み配置を、ゲームプレイの読み取り契約へ変換します。 */
internal class EngineOverworldBootstrap(
    private val plugin: RpgPlugin,
    world: World,
    areas: Collection<Area>,
) : OverworldBootstrapPort {
    private val service = OverworldWorldService(plugin, world)
    private var onChanged: (() -> Unit)? = null
    override val registry: OverworldSiteRegistry = EngineSiteRegistry(
        worldName = world.name,
        areas = areas,
        service = service,
        logger = plugin.logger,
        runSync = { action -> plugin.sync(action) },
        onChanged = { onChanged?.invoke() },
    )

    /** READY 通知と既存ジャーナル読込完了の双方で投影を更新します。 */
    override fun start(onAnchorsChanged: () -> Unit) {
        onChanged = onAnchorsChanged
        service.start { plugin.sync(onAnchorsChanged) }.whenComplete { _, failure ->
            if (failure == null) plugin.sync(onAnchorsChanged)
            else plugin.logger.severe("Overworld construction failed: ${failure.message}")
        }
    }

    /** Paper メインスレッド上で建設処理を終了します。 */
    override fun close() = service.close()
}

/**
 * 未完成地点を公開せず、保存された座標と入口だけを投影します。
 * `service.current()` の参照同一性で不変スナップショットをキャッシュし、移動イベントごとの再計算を防ぎます。
 */
internal class EngineSiteRegistry(
    private val worldName: String,
    areas: Collection<Area>,
    private val service: OverworldWorldService,
    private val logger: Logger,
    private val runSync: (() -> Unit) -> Unit,
    private val onChanged: () -> Unit,
) : OverworldSiteRegistry {
    private data class RegistrySnapshot(
        val ready: Boolean,
        val allSites: List<OverworldSiteAnchor>,
        val sitesByArea: Map<String, OverworldSiteAnchor>,
        val allPortals: List<OverworldDungeonPortal>,
        val portalsByDungeon: Map<String, OverworldDungeonPortal>,
    )

    private val pending = PendingOverworldSiteRegistry(worldName, areas)
    private var cachedLayout: OverworldLayout? = null
    private var cachedSnapshot: RegistrySnapshot? = null

    /** 最新の永続化レイアウトを参照同一性でキャッシュし、変換済みスナップショットを返します。 */
    private fun snapshot(): RegistrySnapshot? {
        val current = service.current() ?: return null
        if (cachedLayout !== current) {
            val portalsByArea = current.portals.associateBy { it.areaId }
            val sites = current.sites.map { site -> site.toGameAnchor(portalsByArea[site.site.id]) }
            val sitesByArea = sites.associateBy { it.areaId }
            val readyPortals = current.portals
                .filter { portal -> sitesByArea[portal.areaId]?.status == GameSiteStatus.READY }
                .map { it.toGamePortal() }
            cachedSnapshot = RegistrySnapshot(
                ready = current.status == OverworldBuildStatus.COMPLETED,
                allSites = sites,
                sitesByArea = sitesByArea,
                allPortals = readyPortals,
                portalsByDungeon = readyPortals.associateBy { it.dungeonId },
            )
            cachedLayout = current
        }
        return cachedSnapshot
    }

    override fun activeWorldName(): String = worldName
    override fun isOverworldMode(): Boolean = true
    override fun isReady(): Boolean = snapshot()?.ready == true

    override fun siteForArea(areaId: String): OverworldSiteAnchor? =
        snapshot()?.sitesByArea?.get(areaId) ?: pending.siteForArea(areaId)

    override fun allSites(): List<OverworldSiteAnchor> =
        snapshot()?.allSites ?: pending.allSites()

    override fun portalForDungeon(dungeonId: String): OverworldDungeonPortal? =
        snapshot()?.portalsByDungeon?.get(dungeonId)

    override fun allPortals(): List<OverworldDungeonPortal> =
        snapshot()?.allPortals.orEmpty()

    /** 修復を主スレッドで開始し、各 READY と完了時にメインスレッドで再投影します。 */
    override fun triggerRepair(): OverworldRepairResult = runCatching {
        service.repair { runSync(onChanged) }.whenComplete { _, failure ->
            if (failure == null) runSync(onChanged)
            else logger.warning("Overworld site repair failed: ${failure.message}")
        }
        OverworldRepairResult.STARTED
    }.getOrElse { error ->
        logger.warning("Overworld site repair unavailable: ${error.message}")
        OverworldRepairResult.UNAVAILABLE
    }

    /** 手動片付け後の明示的な管理者操作でのみ再生成可能にします。 */
    override fun acknowledgeCleanup(areaId: String): OverworldCleanupAckResult = runCatching {
        service.acknowledgeCleanup(areaId).whenComplete { _, failure ->
            if (failure == null) runSync(onChanged)
            else logger.warning("Overworld cleanup acknowledgement failed for $areaId: ${failure.message}")
        }
        OverworldCleanupAckResult.ACKNOWLEDGING
    }.getOrElse { error ->
        logger.warning("Overworld cleanup acknowledgement unavailable for $areaId: ${error.message}")
        OverworldCleanupAckResult.UNAVAILABLE
    }
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
