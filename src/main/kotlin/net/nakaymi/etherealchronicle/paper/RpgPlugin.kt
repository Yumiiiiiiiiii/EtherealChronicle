package net.nakaymi.etherealchronicle.paper

import net.nakaymi.etherealchronicle.core.*
import net.nakaymi.etherealchronicle.core.OverworldSiteStatus as GameSiteStatus
import net.nakaymi.etherealchronicle.data.*
import net.kyori.adventure.text.Component
import net.nakaymi.etherealchronicle.core.ContentKeys
import org.bukkit.event.player.PlayerLocaleChangeEvent
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.*
import org.bukkit.attribute.Attribute
import org.bukkit.command.*
import org.bukkit.entity.*
import org.bukkit.event.*
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.*
import org.bukkit.event.inventory.*
import org.bukkit.event.player.*
import org.bukkit.inventory.*
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/** Paper APIとpure core/dataサービスを初期化し、プレイヤーイベントを接続するプラグイン入口です。 */
open class RpgPlugin: JavaPlugin(), Listener, CommandExecutor {
    private var ready = false
    private val paperValidationWaiters = ConcurrentHashMap.newKeySet<CompletableFuture<Unit>>()
    private val paperInventoryUi by lazy { !server.javaClass.name.startsWith("org.mockbukkit.") }
    internal val paperUiEnabled get() = paperInventoryUi
    internal val isReadyForGameplay: Boolean get() = ready
    private val codexController by lazy { CodexController(this) }
    private val simulationCommands by lazy { SimulationCommandController(this) }
    private val selfTest by lazy { SelfTestController(this) }
    private val combatFeedback by lazy { CombatFeedback(this) }
    private val wayfinding by lazy { WayfindingController(this) }
    private data class PendingHitMeta(val critical: Boolean, val element: String, val elementMod: Double, val isSkill: Boolean)
    private val pendingHitMeta = mutableMapOf<Pair<UUID, UUID>, PendingHitMeta>()
    private var overworldBootstrap: OverworldBootstrapPort? = null
    internal lateinit var activeWorldProjection: ActiveWorldProjection
    private data class ProjectionCacheKey(val position: Position, val areaId: String?, val allowWater: Boolean)
    private val projectedPositions = mutableMapOf<ProjectionCacheKey, Position>()
    private val failedProjectionPositions = mutableSetOf<ProjectionCacheKey>()
    private var appliedReadyAnchors: Map<String, OverworldSiteAnchor> = emptyMap()
    private val invUiInventoryAdapter by lazy { InvUiInventoryAdapter(uiTheme) { stack -> item(stack)?.itemModel } }
    internal lateinit var adventure: AdventureController
    internal lateinit var enemies: EnemyController
    internal lateinit var dungeons: DungeonController
    internal lateinit var gathering: GatheringController
    internal lateinit var fishing: FishingController
    internal lateinit var treasures: TreasureController
    internal lateinit var resourcePacks: ResourcePackController
    private lateinit var uiTheme: UiThemeProvider
    private lateinit var classSelectionUi: ClassSelectionUi
    private lateinit var content: ContentService
    @Volatile private lateinit var language: LangCatalog
    private lateinit var store: PlayerStore
    internal lateinit var game: GameService
    internal val profiles = mutableMapOf<UUID,PlayerProfile>()
    private val sessions = mutableMapOf<UUID,UUID>()
    private val pendingHubSpawns = mutableSetOf<UUID>()
    private val hubLoads = mutableMapOf<UUID, UUID>()
    private val choosing = mutableSetOf<UUID>()
    private val pendingClassMenuReopen = mutableSetOf<UUID>()
    private val lastMoved = mutableMapOf<UUID,Int>()
    private val dirty = mutableSetOf<UUID>()
    private var autosaveSeconds = 0
    private var safeAreaSnapshot: ContentSnapshot? = null
    private var safeAreasByWorld: Map<String, List<Area>> = emptyMap()
    internal lateinit var simStore: PlayerStore
    internal val simulated = mutableMapOf<String,PlayerProfile>()
    internal val simSessions = mutableMapOf<String,SimulationSession>()
    private data class EnhancementFlight(val token:String,val material:String,val currency:Long)
    private val enhancementFlights=mutableMapOf<UUID,EnhancementFlight>()
    private var enhancementLoreLanguage: LangCatalog? = null
    private var enhancementLoreLines: Set<String> = emptySet()
    private var enhancementLoreCap = -1
    private var advancementWarningLogged=false
    private lateinit var itemKey: NamespacedKey
    private lateinit var enhancementKey: NamespacedKey
    private lateinit var enhancementTransactionKey: NamespacedKey
    private lateinit var enemyKey: NamespacedKey
    private lateinit var slotKey: NamespacedKey
    private lateinit var starterClassKey: NamespacedKey
    /** 現在の不変コンテンツSnapshotをControllerへ公開します。 */
    internal val snapshot get() = content.snapshot()
    /** 建設サービスの有無に関わらず、安全な座標投影を初期化します。 */
    private fun initializeWorldProjection() {
        if (config.getString("world.mode")?.trim()?.lowercase() == WorldMode.LEGACY_DEDICATED) {
            logger.warning("world.mode '${WorldMode.LEGACY_DEDICATED}' (floating islands) was removed; using 'overworld' instead. Towns are generated into '${config.getString("world.overworld_name", "world")}'.")
        }
        WorldMode.fromConfig(config.getString("world.mode"))
        // world.mode 未指定のテストだけ、固定座標の疑似レジストリ（TestWorldFixture）を使えます。
        val fixtureRegistry = TestWorldFixture.registryFactory?.takeIf { !config.isSet("world.mode") }
        fixtureProjection = fixtureRegistry != null
        val registry = if (fixtureRegistry != null) fixtureRegistry(snapshot.world.areas.values, snapshot.world.dungeons.values) else {
            val worldName = config.getString("world.overworld_name", "world") ?: "world"
            overworldBootstrap = server.getWorld(worldName)?.let { EngineOverworldBootstrap(this, it, snapshot.world.areas.values) }
            overworldBootstrap?.registry ?: PendingOverworldSiteRegistry(
                worldName, snapshot.world.areas.values,
            ).also { logger.warning("Overworld construction service is unavailable; sites remain pending.") }
        }
        rebuildWorldProjection(registry)
        appliedReadyAnchors = currentReadyAnchors()
    }

    /** テスト用レジストリ使用中は、地表探索せず基準高さ固定の投影にします。 */
    private var fixtureProjection = false

    private fun rebuildWorldProjection(registry: OverworldSiteRegistry = activeWorldProjection.registry) {
        projectedPositions.clear()
        failedProjectionPositions.clear()
        safeAreaSnapshot = null
        activeWorldProjection = ActiveWorldProjection(registry, snapshot.world.areas.values) { x, z ->
            if (fixtureProjection) return@ActiveWorldProjection OverworldAnchorProjector.BASE_SURFACE_Y
            val world = server.getWorld(registry.activeWorldName()) ?: error("ec.error.world_unavailable")
            check(world.isChunkLoaded(x shr 4, z shr 4)) { "Projection chunk is not loaded" }
            resolveProjectionSurfaceY(world, x, z)
        }
    }

    /**
     * Engine の OverworldTerrain.surfaceY を基準に葉・丸太の上を避けて地表 Y+1 を返します。
     * 通常投影（allowWater = false）では水面・溶岩面・水没地表を固体地表として採用しません。
     */
    internal fun resolveProjectionSurfaceY(world: World, x: Int, z: Int, allowWater: Boolean = false): Int {
        var y = runCatching { OverworldTerrain.surfaceY(world, x, z) }
            .getOrElse { world.getHighestBlockYAt(x, z) }
        while (y >= world.minHeight) {
            val type = world.getBlockAt(x, y, z).type
            val isTree = runCatching { Tag.LOGS.isTagged(type) || Tag.LEAVES.isTagged(type) }.getOrDefault(false)
            if (isTree) {
                y--
                continue
            }
            if (type == Material.WATER) {
                check(allowWater) { "Water surface is not valid solid ground" }
                break
            }
            if (type == Material.LAVA) {
                error("Lava surface is not valid ground")
            }
            if (type.isSolid) {
                if (!allowWater && y + 1 < world.maxHeight) {
                    val above = world.getBlockAt(x, y + 1, z).type
                    check(above != Material.WATER && above != Material.LAVA) {
                        "Submerged surface is not valid solid ground"
                    }
                }
                break
            }
            y--
        }
        check(y >= world.minHeight && y + 2 < world.maxHeight) { "No usable surface" }
        return y + 1
    }

    private fun currentReadyAnchors(): Map<String, OverworldSiteAnchor> =
        activeWorldProjection.registry.allSites()
            .filter { it.status == GameSiteStatus.READY }
            .associateBy { it.areaId }

    /** 建設通知を受けた後、メインスレッドで変更のあった拠点だけを各コントローラーへ反映します。 */
    internal fun refreshWorldAnchors() {
        val currentReady = currentReadyAnchors()
        val changedAreaIds = (appliedReadyAnchors.keys + currentReady.keys)
            .filterTo(mutableSetOf()) { areaId -> appliedReadyAnchors[areaId] != currentReady[areaId] }
        appliedReadyAnchors = currentReady
        rebuildWorldProjection()
        if (changedAreaIds.isNotEmpty()) {
            if (::adventure.isInitialized) adventure.refreshWorldAnchors(changedAreaIds)
            if (::enemies.isInitialized) enemies.refreshWorldAnchors(changedAreaIds)
            if (::gathering.isInitialized) gathering.refreshWorldAnchors(changedAreaIds)
            if (::fishing.isInitialized) fishing.refreshWorldAnchors(changedAreaIds)
            if (::treasures.isInitialized) treasures.refreshWorldAnchors(changedAreaIds)
        }
        pendingHubSpawns.toList().forEach { id -> server.getPlayer(id)?.let(::requestHubSpawn) }
    }

    /** 非同期チャンク読込後にチャンクを保持したままメインスレッドで後続処理を実行する共通ヘルパーです。 */
    internal fun withChunk(
        world: World,
        chunkX: Int,
        chunkZ: Int,
        generate: Boolean = true,
        action: (Throwable?) -> Unit,
    ) {
        world.getChunkAtAsync(chunkX, chunkZ, generate).whenComplete { chunk, failure ->
            val pinned = failure == null && chunk != null && Bukkit.isPrimaryThread() && isEnabled &&
                runCatching { chunk.addPluginChunkTicket(this) }.getOrDefault(false)
            if (!isEnabled) {
                if (pinned) runCatching { chunk.removePluginChunkTicket(this) }
                return@whenComplete
            }
            try {
                server.scheduler.runTask(this, Runnable {
                    try {
                        if (isEnabled) action(failure)
                    } finally {
                        if (pinned) runCatching { chunk.removePluginChunkTicket(this) }
                    }
                })
            } catch (_: org.bukkit.plugin.IllegalPluginAccessException) {
                if (pinned) runCatching { chunk.removePluginChunkTicket(this) }
            }
        }
    }

    /** 未ロードチャンクを同期ロードせず、利用可能な配置だけを返します。釣り場のみ allowWater = true を指定します。 */
    internal fun projectedPosition(position: Position, areaId: String? = null, allowWater: Boolean = false): Position? {
        val cacheKey = ProjectionCacheKey(position, areaId, allowWater)
        projectedPositions[cacheKey]?.let { return it }
        if (cacheKey in failedProjectionPositions) return null
        val projection = activeWorldProjection.projector
        val horizontal = if (areaId == null) projection.projectHorizontal(position) else projection.projectHorizontal(position, areaId)
        horizontal ?: return null
        val world = server.getWorld(horizontal.world) ?: return null
        val chunk = ChunkCoordinates.fromPosition(horizontal.x, horizontal.z)
        if (!world.isChunkLoaded(chunk.x, chunk.z)) return null
        val result = runCatching {
            if (allowWater) {
                val bx = kotlin.math.floor(horizontal.x).toInt()
                val bz = kotlin.math.floor(horizontal.z).toInt()
                horizontal.copy(y = resolveProjectionSurfaceY(world, bx, bz, allowWater = true).toDouble())
            } else if (areaId == null) {
                projection.project(position)
            } else {
                projection.project(position, areaId)
            }
        }
        result.onFailure { error ->
            if (failedProjectionPositions.add(cacheKey)) {
                logger.fine(
                    "Could not project position (${horizontal.world} ${horizontal.x},${horizontal.z}, " +
                        "area=${areaId ?: "auto"}, allowWater=$allowWater): ${error.message}",
                )
            }
        }
        return result.getOrNull()?.also { projectedPositions[cacheKey] = it }
    }

    /** ショップ担当NPCとの距離・ワールド一致を検証し、利用不可なら言語キーを返します。 */
    internal fun shopAccessError(player: Player, shopId: String): String? {
        val npcs = snapshot.world.npcs.values.filter { it.shop == shopId }
        if (npcs.isEmpty()) return "ec.error.shop_npc_missing"
        var sawTooFar = false
        val maxDistanceSquared = PaperDistance.SHOP_INTERACTION_BLOCKS * PaperDistance.SHOP_INTERACTION_BLOCKS
        for (npc in npcs) {
            val projected = projectedPosition(npc.position)
            if (projected != null) {
                if (projected.world == player.world.name &&
                    player.location.isWithinBlocks(
                        Location(player.world, projected.x, projected.y, projected.z),
                        PaperDistance.SHOP_INTERACTION_BLOCKS,
                    )
                ) return null
                sawTooFar = true
                continue
            }
            val horizontal = activeWorldProjection.projector.projectHorizontal(npc.position) ?: continue
            val dx = player.location.x - horizontal.x
            val dz = player.location.z - horizontal.z
            if (horizontal.world != player.world.name || dx * dx + dz * dz > maxDistanceSquared) {
                sawTooFar = true
            }
        }
        return if (sawTooFar) "ec.error.shop_too_far" else "ec.error.world_unavailable"
    }

    /** 対象ワールドだけをゲームコントローラーに公開します。 */
    internal fun isActiveWorld(world: World): Boolean = world.name == activeWorldProjection.worldName()

    /** RPG が管理する対象ワールドかどうかを返します。 */
    internal fun isManagedWorld(world: World): Boolean = isActiveWorld(world)

    /** 読み込み済みの安全なルミナ港広場を返し、未準備なら移動を行いません。 */
    internal fun luminaSpawn(): Location? {
        val world = server.getWorld(activeWorldProjection.worldName()) ?: return null
        val site = activeWorldProjection.registry.siteForArea("ec:area_lumina_port")
            ?.takeIf { it.status == GameSiteStatus.READY } ?: return null
        val spawnX = site.plazaSpawnX ?: return null
        val spawnY = site.plazaSpawnY ?: return null
        val spawnZ = site.plazaSpawnZ ?: return null
        val location = Location(world, spawnX + 0.5, spawnY.toDouble(), spawnZ + 0.5)
        return location.takeIf(SafeStandingSpot::isSafe)
    }

    /** 初回接続時に広場のチャンクを非同期確保し、準備が整ってから安全な地点へ移します。 */
    internal fun requestHubSpawn(player: Player) {
        val id = player.uniqueId
        pendingHubSpawns.add(id)
        val site = activeWorldProjection.registry.siteForArea("ec:area_lumina_port")
            ?.takeIf { it.status == GameSiteStatus.READY } ?: return
        val spawnX = site.plazaSpawnX ?: return
        val spawnZ = site.plazaSpawnZ ?: return
        val world = server.getWorld(activeWorldProjection.worldName()) ?: return
        val session = sessions[id] ?: return
        if (hubLoads[id] == session) return
        hubLoads[id] = session
        withChunk(world, spawnX shr 4, spawnZ shr 4) { error ->
            if (hubLoads[id] == session) hubLoads.remove(id)
            if (!player.isOnline || player.isDead || sessions[id] != session || id !in pendingHubSpawns) return@withChunk
            if (error != null) { logger.warning("Could not load hub spawn chunk: ${error.message}"); return@withChunk }
            val destination = luminaSpawn() ?: return@withChunk
            if (player.teleport(destination)) pendingHubSpawns.remove(id)
        }
    }
    /** コンテンツ版ごとに安全地帯をワールド別へ索引化します。 */
    internal fun safeAreas(world: String): List<Area> {
        val current = snapshot
        if (safeAreaSnapshot !== current) {
            safeAreasByWorld = current.world.areas.values.filter { it.safe }
                .mapNotNull(activeWorldProjection.projector::projectArea).groupBy { it.world }
            safeAreaSnapshot = current
        }
        return safeAreasByWorld[world].orEmpty()
    }
    /** クライアントlocaleに合わせて翻訳済みComponentを生成します。 */
    internal fun message(player: Player, key: String, vararg values: Pair<String, Any?>): Component =
        language.component(player.locale().toString(), key, values.toMap())
    /** プレイヤー以外の管理コマンドは設定言語、または日本語へフォールバックします。 */
    internal fun message(sender: CommandSender, key: String, vararg values: Pair<String, Any?>): Component =
        language.component((sender as? Player)?.locale()?.toString(), key, values.toMap())
    /** 指定localeのデバッグ表示を翻訳し、対象がオンラインでない場合も設定言語へ戻します。 */
    internal fun message(locale: String?, key: String, vararg values: Pair<String, Any?>): Component =
        language.component(locale, key, values.toMap())
    /** map形式のプレースホルダーを指定localeで翻訳します。 */
    internal fun message(locale: String?, key: String, values: Map<String, Any?>): Component =
        language.component(locale, key, values)
    /** 例外診断中の安定キーを翻訳し、ファイル名やIDなどの診断情報を保ちます。 */
    internal fun diagnostic(sender: CommandSender, text: String): Component =
        language.diagnostic((sender as? Player)?.locale()?.toString(),text)
    /** 型付きゲーム例外のキーと引数を、利用者の言語へ変換します。 */
    internal fun diagnostic(sender: CommandSender, error: GameException): Component =
        language.component((sender as? Player)?.locale()?.toString(), error.messageKey, error.arguments)
    /** コンテンツ文言を指定localeで返します。日本語はYAML原文、英語はlang/content_en_us.ymlが正本です。 */
    internal fun contentText(locale: String?, type: String, id: String, field: String, fallbackJa: String): String =
        language.contentText(locale, ContentKeys.key(type, id, field), fallbackJa)
    /** プレイヤーのlocaleでコンテンツ文言を返します。 */
    internal fun contentText(player: Player, type: String, id: String, field: String, fallbackJa: String): String =
        contentText(player.locale().toString(), type, id, field, fallbackJa)
    /** NPCや敵など全員が見える表示向けに、クライアント側で翻訳されるComponentを返します（パック未適用時はlocale文言）。 */
    internal fun contentTranslatable(type: String, id: String, field: String, fallbackJa: String, locale: String? = null): Component =
        Component.translatable(ContentKeys.key(type, id, field), contentText(locale, type, id, field, fallbackJa))
    /** 言語設定を考慮したlocaleコード（en_us / ja_jp）。 */
    internal fun localeKey(player: Player): String = language.resolveLocale(player.locale().toString())
    private fun plain(component: Component): String = component.plainText()
    /** クラスの資源種別を表示用の翻訳へ変換します。 */
    private fun resourceName(player: Player,kind: ResourceKind): Component = message(player, when(kind) {
        ResourceKind.RAGE -> "ec.resource.rage"
        ResourceKind.FOCUS -> "ec.resource.focus"
        ResourceKind.MANA -> "ec.resource.mana"
    })
    internal fun persist(profile: PlayerProfile) = store.save(profile)
    internal fun identify(stack: ItemStack?) = stack?.itemMeta?.persistentDataContainer?.get(itemKey,PersistentDataType.STRING)
    internal fun inventory(player: Player,persistRemoval: Boolean = true): PaperInventoryAccess = PaperInventoryAccess(player,::identify,{ id,amount -> createItemForPlayer(id,amount,player) },persistRemoval) { logger.fine("Persisted consumed transaction items: ${player.uniqueId}") }
    internal fun markDirty(player: UUID) { dirty.add(player) }
    /** リソースパック状態に合わせて所持品のモデル指定を付け外しし、未対応クライアントの欠損表示を防ぎます。 */
    internal fun refreshPackItemModels(player: Player, hasPack: Boolean) {
        fun refresh(stack: ItemStack?) {
            val held = stack ?: return
            val definition = item(held) ?: return
            val model = UiThemeProvider.modelKey(hasPack, definition.itemModel)
            held.editMeta { it.setItemModel(model) }
        }
        player.inventory.contents.forEach(::refresh)
        player.inventory.armorContents.forEach(::refresh)
        refresh(player.inventory.itemInOffHand)
    }
    /** YAMLを非同期検証した後、メインスレッドで各ゲームControllerを登録します。 */
    override fun onEnable() {
        saveDefaultConfig()
        itemKey = NamespacedKey(this,"item_id"); enhancementKey=NamespacedKey(this,"enhancement_level"); enhancementTransactionKey=NamespacedKey(this,"enhancement_transaction"); enemyKey = NamespacedKey(this,"enemy_id"); slotKey = NamespacedKey(this,"skill_slot"); starterClassKey=NamespacedKey(this,"starter_class")
        val languageDefaults = listOf("ja_jp.yml", "en_us.yml", "content_en_us.yml").associate { name ->
            "lang/$name" to getResource("lang/$name")!!.use { it.readBytes() }
        }
        language = LangCatalog.fromBundledDefaults(config.getString("language", "auto") ?: "auto", logger, languageDefaults)
        // Paperプラグインはライフサイクル登録のみを使い、MockBukkitではテスト用コマンドを登録します。
        if (paperInventoryUi) registerPaperCommand()
        else getCommand("ec")?.setExecutor(this) ?: registerMockCommand()
        server.scheduler.runTaskAsynchronously(this,Runnable {
            try {
                val oldLanguageDefaults = getResource("language-defaults.properties")
                    ?.use { DefaultContentInstaller.manifest(it.readBytes()) } ?: emptyMap()
                val languageInstall = DefaultContentInstaller.installTracked(
                    dataFolder.toPath(), languageDefaults, oldLanguageDefaults, "lang-manifest.properties"
                )
                languageInstall.updated.forEach { logger.info("Updated unchanged bundled language file: $it") }
                languageInstall.changed.forEach { logger.info("Preserving server-edited language file: $it") }
                val loadedLanguage = LangCatalog.load(dataFolder.toPath(), config.getString("language", "auto") ?: "auto", logger)
                val bundled = getResource("content-index.txt")!!.bufferedReader().use { reader ->
                    reader.readLines().filter { it.isNotBlank() }.associate { path ->
                        val clean = path.removePrefix("\uFEFF")
                        clean to getResource(clean)!!.use { it.readBytes() }
                    }
                }
                val legacy=getResource("retired-content.properties")?.use { DefaultContentInstaller.manifest(it.readBytes()) } ?: emptyMap()
                val oldDefaults=getResource("foundation-content.properties")?.use { DefaultContentInstaller.manifest(it.readBytes()) } ?: emptyMap()
                val retired=DefaultContentInstaller.retire(dataFolder.toPath(),bundled.keys,legacy)
                retired.retired.forEach { logger.info("Moved retired bundled content: $it") }
                retired.preserved.forEach { logger.warning("Preserved edited retired content; review its definition: $it") }
                val installation=DefaultContentInstaller.installTracked(dataFolder.toPath(),bundled,oldDefaults)
                installation.updated.forEach { name -> logger.info("Updated unchanged bundled content (old copy saved to content-history): $name") }
                installation.changed.forEach { name ->
                    logger.info("Preserving server-edited content differing from bundled defaults: $name")
                }
                listOf("balance","classes","skills","items","enemies","bosses","npcs","quests","dialogues","chapters","droptables","shops","titles","tackle","fishing_spots","fish_species","gathering_nodes","treasure_chests").forEach { java.nio.file.Files.createDirectories(dataFolder.toPath().resolve("content").resolve(it)) }
                val startup=ContentBootstrap.initialize(dataFolder.toPath(),bundled,::validatePaperContent)
                content=startup.service
                if(startup.usedFallback) logger.warning("Invalid server content; starting with bundled defaults and keeping /ec validate and /ec reload available: ${startup.problem}")
                store = PlayerStore(dataFolder.toPath().resolve("playerdata"))
                simStore = PlayerStore(dataFolder.toPath().resolve("simulation"))
                sync {
                    language = loadedLanguage
                    game = GameService(content::snapshot,SkillEngine(Clock(::monotonicMillis),content.snapshot().balance))
                    resourcePacks = ResourcePackController(this)
                    if (resourcePacks.shouldRegisterLockListener()) {
                        RequiredPackLockListener(this, resourcePacks::isAwaitingRequiredPack, resourcePacks::hasWaiters)
                    }
                    uiTheme = UiThemeProvider(
                        this,
                        resourcePacks,
                        UiThemeProvider.mode(config.getString("ui.theme.mode", "icons_only")),
                    )
                    classSelectionUi = ClassSelectionUi(
                        theme=uiTheme,
                        title={ p -> message(p,"ec.class.choose") },
                        confirmationTitle={ p,c -> message(p,"ec.class.confirm_title","class" to contentText(p,"class",c.id,"name",c.name)) },
                        confirmationText={ p,c -> message(p,"ec.class.confirm_body","class" to contentText(p,"class",c.id,"name",c.name)) },
                        className={ p,c -> contentText(p,"class",c.id,"name",c.name) },
                        classLore={ p,c ->
                            val skills=c.skills.mapNotNull(content.snapshot().skills::find).joinToString(" / ") { contentText(p,"skill",it.id,"name",it.name) }
                            listOf(
                                message(p,"ec.class.selection.role","resource" to resourceName(p,c.resource)),
                                message(p,"ec.class.selection.stats","hp" to c.stats.hp.toInt(),"attack" to c.stats.attack.toInt(),"defense" to c.stats.defense.toInt()),
                                message(p,"ec.class.selection.skills","skills" to skills),
                            )
                        },
                        confirmLabel={ p -> message(p,"ec.class.confirm_start") },
                        cancelLabel={ p -> message(p,"ec.class.confirm_cancel") },
                        onSelected={ p,id -> profiles[p.uniqueId]?.let { starter(p,it,id) } },
                        onCancel={ p -> menu(p) },
                        onClose={ p -> scheduleClassMenuReopen(p) },
                    )
                    if (paperInventoryUi) xyz.xenondevs.invui.InvUI.getInstance().setPlugin(this)
                    initializeWorldProjection()
                    adventure=AdventureController(this)
                    enemies=EnemyController(this,adventure)
                    dungeons=DungeonController(this,adventure,enemies)
                    try {
                        adventure.reloadNpcs()
                    }
                    catch(error: Exception) { logger.severe("Could not reload NPCs during startup; foundation features remain enabled: ${error.message}") }
                    gathering=GatheringController(this)
                    fishing=FishingController(this)
                    treasures=TreasureController(this).also { it.reload() }
                    DungeonEntranceListener(this, activeWorldProjection.registry, dungeons)
                    overworldBootstrap?.start { sync { refreshWorldAnchors() } }
                    runCatching { reloadAdvancementPack() }.onFailure { advancementWarning(it) }
                    server.scheduler.runTaskTimer(this,Runnable { enemies.tick() },PaperTaskIntervals.ENEMY_UPDATE_TICKS,PaperTaskIntervals.ENEMY_UPDATE_TICKS)
                    ready = true
                    server.pluginManager.registerEvents(this,this)
                    if (!paperInventoryUi) getCommand("ec")?.setExecutor(this)
                    server.scheduler.runTaskTimer(this,Runnable { tick() },PaperTaskIntervals.PLAYER_UPDATE_TICKS,PaperTaskIntervals.PLAYER_UPDATE_TICKS)
                    server.onlinePlayers.forEach { join(it) }
                    logger.info("Adventure systems are ready.")
                }
            } catch(e: Exception) {
                logger.severe("Failed to load content: ${e.message}")
                sync { server.pluginManager.disablePlugin(this) }
            }
        })
    }
    /** Controllerを停止してから保留状態を直列保存し、タスクとプロフィールを閉じます。 */
    override fun onDisable() {
        ready = false
        paperValidationWaiters.forEach { it.completeExceptionally(IllegalStateException("Plugin disabled during Paper content validation")) }
        enhancementFlights.keys.toList().forEach { id ->
            val player=server.getPlayer(id)
            val profile=profiles[id]
            if(player != null && profile != null) settleEnhancement(player,profile)
            else enhancementFlights.remove(id)
        }
        if(::adventure.isInitialized) adventure.prepareShutdown()
        if(::fishing.isInitialized) runCatching { fishing.close() }.onFailure { logger.severe("Fishing shutdown failed: ${it.message}") }
        if(::gathering.isInitialized) runCatching { gathering.close() }.onFailure { logger.severe("Gathering shutdown failed: ${it.message}") }
        if(::dungeons.isInitialized) runCatching { dungeons.shutdown() }.onFailure { logger.severe("Dungeon shutdown failed: ${it.message}") }
        if(::enemies.isInitialized) runCatching { enemies.close() }.onFailure { logger.severe("Enemy shutdown failed: ${it.message}") }
        if(::adventure.isInitialized) runCatching { adventure.close() }.onFailure { logger.severe("NPC shutdown failed: ${it.message}") }
        if(::treasures.isInitialized) runCatching { treasures.close() }.onFailure { logger.severe("Treasure shutdown failed: ${it.message}") }
        if(::resourcePacks.isInitialized) runCatching { resourcePacks.close() }.onFailure { logger.severe("Resource pack shutdown failed: ${it.message}") }
        runCatching { combatFeedback.close() }.onFailure { logger.severe("Combat feedback shutdown failed: ${it.message}") }
        runCatching { wayfinding.close() }.onFailure { logger.severe("Wayfinding shutdown failed: ${it.message}") }
        runCatching { overworldBootstrap?.close() }.onFailure { logger.severe("Overworld shutdown failed: ${it.message}") }
        server.scheduler.cancelTasks(this)
        if (::store.isInitialized) {
            profiles.values.forEach { store.save(it) }
            if (::simStore.isInitialized) runCatching { simStore.close() }.onFailure { logger.severe("Simulation store shutdown failed: ${it.message}") }
            runCatching { store.close() }.onFailure { logger.severe("Player store shutdown failed: ${it.message}") }
        }
        profiles.clear(); sessions.clear()
    }
    /** async callbackのPaper操作を有効なプラグイン期間中だけメインスレッドへ戻します。 */
    internal fun sync(action: () -> Unit) {
        if(!isEnabled) return
        try { server.scheduler.runTask(this,Runnable { if(isEnabled) action() }) }
        catch(_: org.bukkit.plugin.IllegalPluginAccessException) { /* disabled between the check and scheduling */ }
    }
    /** 保存失敗時にdirty状態を戻し、次回autosaveで再試行します。 */
    internal fun save(p: PlayerProfile) { store.save(p).exceptionally { logger.severe("Save failed ${p.uuid}: ${it.message}"); sync { if(p.uuid in profiles) dirty.add(p.uuid) }; null } }
    /** 接続世代tokenで古い非同期loadを捨て、完了したプロフィールだけを反映します。 */
    internal fun join(player: Player) {
        if (!ready) return
        val token = UUID.randomUUID(); sessions[player.uniqueId] = token
        // 接続直後から適用結果まで操作を止め、要求送信後のプロフィール処理も待機させます。
        resourcePacks.beginRequiredJoin(player)
        player.sendMessage(message(player,"ec.startup.loading"))
        store.load(player.uniqueId).whenComplete { profile,error -> sync {
            if(sessions[player.uniqueId] != token || !player.isOnline) return@sync
            if(error != null) { logger.severe("Profile load failed: ${error.message}"); player.kick(message(player,"ec.profile.read_failed")); return@sync }
            val snapshot = content.snapshot()
            ProfileContentMigration.apply(profile,snapshot)
            if(profile.classId != null && snapshot.classes.find(profile.classId!!) == null) { player.kick(message(player,"ec.profile.class_data_missing")); return@sync }
            profile.level = profile.level.coerceIn(1,snapshot.balance.levelCap)
            profile.skillLevels.replaceAll { _,level -> level.coerceIn(1,snapshot.balance.skillLevelCap) }
            profile.classId = profile.classId?.let { snapshot.classes[it].id }
            for(slot in 0 until player.inventory.size) {
                val old=player.inventory.getItem(slot); val id=identify(old) ?: continue
                profile.discoverItem(id)
                if(snapshot.items.find(id) == null) ProfileContentMigration.oldItems[id]?.takeIf { snapshot.items.find(it) != null }?.let { player.inventory.setItem(slot,createItem(it,old!!.amount)) }
            }
            resourcePacks.send(player,runCatching { PackPreference.valueOf(profile.resourcePackPreference) }.getOrDefault(PackPreference.AUTO))
            resourcePacks.applyObservedStatus(player,profile)
            resourcePacks.whenRequiredPackReady(player) {
                if (!player.isOnline || sessions[player.uniqueId] != token) return@whenRequiredPackReady
                profiles[player.uniqueId] = profile
                finishLoadedJoin(player,profile)
            }
        } }
    }

    /** 必須パック適用後に、ワールド移動・HUD装備・職業メニューを開始します。 */
    private fun finishLoadedJoin(player: Player, profile: PlayerProfile) {
        if (::dungeons.isInitialized && !dungeons.isJournalReady()) {
            dungeons.whenJournalReady {
                if (player.isOnline && profiles[player.uniqueId] === profile) finishLoadedJoin(player, profile)
            }
            return
        }
        syncTitleAdvancements(player,profile)
        if(::dungeons.isInitialized) dungeons.recover(player)
        if ((!player.hasPlayedBefore() || profile.classId == null) &&
            !(::dungeons.isInitialized && dungeons.hasPendingReturn(player.uniqueId))) requestHubSpawn(player)
        clearProjectedSafeHostiles(this,player.location)
        if(profile.classId == null || !profile.starterGranted) menu(player) else {
            skillSlot(player); applyStats(player,profile)
            adventure.whenAvailable(player.uniqueId) { if(player.isOnline && profiles[player.uniqueId] === profile) adventure.claimPending(player) }
        }
    }
    @EventHandler fun onJoin(e: PlayerJoinEvent) = join(e.player)
    /** クライアント言語が切り替わったら、スキルスロット名と称号表示を新しい言語で作り直します。 */
    @EventHandler fun onLocaleChange(e: PlayerLocaleChangeEvent) {
        val player=e.player
        server.scheduler.runTask(this,Runnable {
            if(!player.isOnline) return@Runnable
            val profile=profiles[player.uniqueId] ?: return@Runnable
            val slot=player.inventory.getItem(PlayerInventoryLayout.SKILL_HOTBAR_SLOT)
            if(isSkill(slot)) slot!!.editMeta {
                it.displayName(message(player,"ec.profile.skill_slot"))
                it.lore(listOf(
                    message(player,"ec.skill_slot.lore_cycle"),
                    message(player,"ec.skill_slot.lore_cast"),
                    message(player,"ec.skill_slot.lore_guide"),
                ))
            }
            applyTitle(player,profile)
            wayfinding.invalidateDisplay(player.uniqueId)
        })
    }

    @EventHandler fun onQuit(e: PlayerQuitEvent) {
        simulationCommands.cancelTravel(e.player.uniqueId)
        pendingHubSpawns.remove(e.player.uniqueId); hubLoads.remove(e.player.uniqueId)
        if(::adventure.isInitialized) adventure.beforeQuit(e.player)
        if(ready) codexController.clear(e.player.uniqueId)
        sessions.remove(e.player.uniqueId); choosing.remove(e.player.uniqueId)
        pendingClassMenuReopen.remove(e.player.uniqueId)
        if(ready) classSelectionUi.clear(e.player.uniqueId)
        profiles.remove(e.player.uniqueId)?.let { profile -> settleEnhancement(e.player,profile); save(profile) }
        lastMoved.remove(e.player.uniqueId); dirty.remove(e.player.uniqueId)
        combatFeedback.clearPlayer(e.player.uniqueId); wayfinding.clearPlayer(e.player.uniqueId)

    }
    private fun menu(player: Player) {
        if (paperInventoryUi) {
            classSelectionUi.open(player,content.snapshot().classes.all().toList())
            return
        }
        // MockBukkitはCraftItemStackを提供しないため、イベント回帰テストだけ旧API表示にします。
        val inventory = ClassMenu(message(player,"ec.class.choose")).inventory
        content.snapshot().classes.all().take(ClassSelectionLayout.MAX_CLASS_OPTIONS).forEachIndexed { index, definition ->
            val icon = definition.icon?.let(Material::valueOf) ?: when (definition.resource) {
                ResourceKind.RAGE -> Material.IRON_SWORD
                ResourceKind.FOCUS -> Material.BOW
                ResourceKind.MANA -> Material.BLAZE_ROD
            }
            inventory.setItem(index, ItemStack(icon).apply {
                itemMeta = itemMeta.apply {
                    displayName(Component.text(contentText(player,"class",definition.id,"name",definition.name)))
                    persistentDataContainer.set(itemKey, PersistentDataType.STRING, definition.id)
                }
            })
        }
        player.openInventory(inventory)
    }

    /** クラス画面のclose通知を次tickへ遅らせ、InvUI差し替え中の同tick再入を防ぎます。 */
    private fun scheduleClassMenuReopen(player: Player) {
        val id = player.uniqueId
        if (!pendingClassMenuReopen.add(id)) return
        try {
            server.scheduler.runTaskLater(this, Runnable {
                pendingClassMenuReopen.remove(id)
                if (player.isOnline && profiles[id]?.classId == null && id !in choosing &&
                    !classSelectionUi.isConfirming(id) && !classSelectionUi.isOpen(id)
                ) {
                    menu(player)
                }
}, PaperTaskIntervals.NEXT_TICK_DELAY)
        } catch (_: org.bukkit.plugin.IllegalPluginAccessException) {
            pendingClassMenuReopen.remove(id)
        }
    }
    @EventHandler fun click(e: InventoryClickEvent) {
        val p = e.whoClicked as? Player ?: return
        if(e.view.topInventory.holder is ClassMenu) {
            e.isCancelled = true
            if(e.rawSlot !in 0 until ChestLayout.CLASS_MENU_SLOTS) return
            val id = e.currentItem?.itemMeta?.persistentDataContainer?.get(itemKey,PersistentDataType.STRING) ?: return
            val profile = profiles[p.uniqueId] ?: return
            if(profile.classId != null && profile.classId != id) return
            starter(p,profile,id); return
        }
        if(p.uniqueId in choosing || e.slot == PlayerInventoryLayout.SKILL_HOTBAR_SLOT && e.clickedInventory == p.inventory && isSkill(p.inventory.getItem(PlayerInventoryLayout.SKILL_HOTBAR_SLOT)) || e.hotbarButton == PlayerInventoryLayout.SKILL_HOTBAR_SLOT || isSkill(e.currentItem)) e.isCancelled = true
    }
    @EventHandler fun drag(e: InventoryDragEvent) {
        if(e.view.topInventory.holder is ClassMenu || e.rawSlots.any { e.view.convertSlot(it) == PlayerInventoryLayout.SKILL_HOTBAR_SLOT && it >= e.view.topInventory.size }) e.isCancelled = true
    }
    @EventHandler fun closeMenu(e: InventoryCloseEvent) {
        val p = e.player as? Player ?: return
        if(e.inventory.holder is ClassMenu && profiles[p.uniqueId]?.starterGranted == false && p.uniqueId !in choosing) scheduleClassMenuReopen(p)
        else if(profiles[p.uniqueId]?.starterGranted == true) sync { if(p.isOnline) skillSlot(p) }
    }
    internal fun ensureStarter(player: Player) { profiles[player.uniqueId]?.takeIf { it.classId != null && !it.starterGranted }?.let { starter(player,it,it.classId!!) } }
    private fun starter(p: Player, profile: PlayerProfile, id: String) {
        if(!choosing.add(p.uniqueId)) return
        val c = content.snapshot().classes[id]
        if(c.starterItems.size > p.inventory.storageContents.take(PlayerInventoryCapacities.STARTER_HOTBAR).count { it == null } ) {
            choosing.remove(p.uniqueId); p.sendMessage(message(p,"ec.profile.inventory_space")); return
        }
        if(profile.classId == null) game.chooseClass(profile,id)
        profile.starterGranted = true
        // アイテムを配る前に権利消費を保存し、切断・再起動時の二重配布を防ぎます。
        store.save(profile).whenComplete { _,error -> sync {
            choosing.remove(p.uniqueId)
            if(error != null) { profile.starterGranted = false; p.sendMessage(message(p,"ec.profile.save_retry")); return@sync }
            if(!p.isOnline || profiles[p.uniqueId] !== profile) return@sync
            val deferredToMailbox = c.starterItems.size > PlayerInventoryLayout.STARTER_HOTBAR_RANGE.count { p.inventory.getItem(it) == null }
            if(deferredToMailbox) {
                // 先行保存済みのstarterGrantedを維持し、枠不足分は耐久メールボックスへ戻します。
                val counts = c.starterItems.groupingBy { it }.eachCount()
                profile.returnToMailbox(counts)
                counts.keys.forEach(profile::discoverItem)
                save(profile)
                p.sendMessage(message(p,"ec.profile.starter_space"))
            } else {
                c.starterItems.forEach { item ->
                    val slot = PlayerInventoryLayout.STARTER_HOTBAR_RANGE.first { p.inventory.getItem(it) == null }
                    p.inventory.setItem(slot,createStarterItem(item,id,p))
                    profile.discoverItem(item)
                }
                save(profile)
            }
            skillSlot(p); applyStats(p,profile); p.health=p.getAttribute(Attribute.MAX_HEALTH)!!.value
            p.closeInventory()
            if (deferredToMailbox) adventure.claimPending(p)
            else {
                p.sendMessage(message(p,"ec.profile.welcome"))
                p.sendMessage(message(p,"ec.tutorial.controls"))
                wayfinding.showGuide(p, profile, spawnTrail = true)
            }
        } }
    }
    internal fun createItem(id: String, amount: Int = 1): ItemStack = createItemInternal(id, amount, null)

    /** 適用済みリソースパックに合わせたアイテム表現を生成します。 */
    internal fun createItemForPlayer(id: String, amount: Int, packRecipient: Player): ItemStack =
        createItemInternal(id, amount, packRecipient)

    private fun createItemInternal(id: String, amount: Int, packRecipient: Player?): ItemStack {
        val d = content.snapshot().items[id]
        return ItemStack(Material.valueOf(d.material),amount).apply { itemMeta = itemMeta.apply {
            // 共有表示はクライアント言語で解決できるtranslatableにし、パック未適用時の代替文言は受取人のlocaleにします。
            val locale=packRecipient?.locale()?.toString()
            val rarity=Component.translatable(d.rarity.displayKey, plain(language.component(locale,d.rarity.displayKey)))
            displayName(contentTranslatable("item",id,"name",d.name,locale).decoration(TextDecoration.ITALIC,false))
            val requiredFallback=plain(language.component(locale,"ec.item.required_level", mapOf("level" to "%1\$s", "rarity" to "%2\$s")))
            lore(listOf(Component.translatable("ec.item.required_level",requiredFallback,Component.text(d.minLevel),rarity)))
            snapshot.world.itemProperties[id]?.lore?.takeIf { it.isNotBlank() }?.let { text -> lore((lore() ?: emptyList())+itemLoreLines(id,text,locale)) }
            persistentDataContainer.set(itemKey,PersistentDataType.STRING,id)
            val hasPack=packRecipient?.let { ::resourcePacks.isInitialized && resourcePacks.hasResourcePack(it.uniqueId) } == true
            UiThemeProvider.modelKey(hasPack,d.itemModel)?.let(::setItemModel)
            if(d.material == "POTION") addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ADDITIONAL_TOOLTIP)
        } }
    }
    /** 説明文を行キー付きtranslatableで返し、言語が変わっても行が揃うよう日英の最大行数で出力します。 */
    private fun itemLoreLines(id: String, text: String, locale: String?): List<Component> {
        val english=language.contentText("en",ContentKeys.key("item",id,"lore"),text)
        val count=maxOf(ContentKeys.wrapJa(text).size,ContentKeys.wrapEn(english).size)
        val own=if(language.resolveLocale(locale)=="en_us") ContentKeys.wrapEn(english) else ContentKeys.wrapJa(text)
        return (0 until count).map { index -> Component.translatable(ContentKeys.key("item",id,"lore.$index"),own.getOrElse(index){""}) }
    }
    /** 初期支給品の所属クラスをPDCへ記録し、切替時に既存品と重複付与を区別します。 */
    private fun createStarterItem(id:String,classId:String,player:Player):ItemStack = createItemForPlayer(id,1,player).also { stack ->
        stack.itemMeta=stack.itemMeta.apply { persistentDataContainer.set(starterClassKey,PersistentDataType.STRING,classId) }
    }
    /** クラス切替時に対象クラスの初期武器を手元へ移し、余剰は保持したまま二重付与を避けます。 */
    private fun reconcileStarterWeapon(player:Player,profile:PlayerProfile,oldClassId:String?,newClass:ClassDefinition):Boolean {
        val newWeapon=newClass.starterItems.firstOrNull() ?: return false
        val inventory=player.inventory
        val heldSlot=inventory.heldItemSlot
        val ownedSlot=(0 until inventory.storageContents.size).firstOrNull { slot ->
            !PlayerInventoryLayout.isSkillSlot(slot) && identify(inventory.getItem(slot)) == newWeapon
        }
        if(ownedSlot != null) {
            val held=inventory.getItem(heldSlot)
            inventory.setItem(heldSlot,inventory.getItem(ownedSlot))
            inventory.setItem(ownedSlot,held)
            inventory.getItem(heldSlot)?.let { switched ->
                switched.itemMeta=switched.itemMeta.apply { persistentDataContainer.set(starterClassKey,PersistentDataType.STRING,newClass.id) }
            }
            profile.discoverItem(newWeapon)
            return false
        }

        val held=inventory.getItem(heldSlot)
        val heldId=identify(held)
        val oldStarterIds=oldClassId?.let(snapshot.classes::find)?.starterItems.orEmpty().toSet()
        val canReplaceHeld=held == null || heldId in oldStarterIds
        val destination=if(canReplaceHeld) heldSlot else
            PlayerInventoryLayout.STORAGE_SLOT_RANGE.firstOrNull { PlayerInventoryLayout.isUsableStorageSlot(it) && inventory.getItem(it) == null }
        if(destination == null) {
            profile.pendingRewards.merge(newWeapon,1,Int::plus)
            profile.discoverItem(newWeapon)
            return true
        }

        if(destination == heldSlot && held != null && heldId != null) {
            val spare=PlayerInventoryLayout.STORAGE_SLOT_RANGE.firstOrNull { PlayerInventoryLayout.isUsableStorageSlot(it) && it != heldSlot && inventory.getItem(it) == null }
            if(spare != null) inventory.setItem(spare,held)
            else profile.pendingRewards.merge(heldId,held.amount,Int::plus)
        }
        inventory.setItem(destination,createStarterItem(newWeapon,newClass.id,player))
        profile.discoverItem(newWeapon)
        return false
    }
    internal fun item(stack: ItemStack?): ItemDefinition? = stack?.itemMeta?.persistentDataContainer?.get(itemKey,PersistentDataType.STRING)?.let { content.snapshot().items.find(it) }
    /** コンテンツIDを画面用の名前へ変換し、未登録時も内部IDを露出しません。 */
    internal fun contentName(player: Player, id: String): String = snapshot.items.find(id)?.let { contentText(player,"item",id,"name",it.name) }
        ?: (snapshot.documents[id]?.get("name") as? String)?.let { ja ->
            val type=when(val raw=snapshot.documents[id]?.get("type") as? String) { "fish_species" -> "fish"; "gathering_node" -> "gathering"; null -> "item"; else -> raw }
            contentText(player,type,id,"name",ja)
        }
        ?: plain(message(player, "ec.content.unknown_name"))
    private fun isSkill(stack: ItemStack?) = stack?.itemMeta?.persistentDataContainer?.has(slotKey,PersistentDataType.BYTE) == true
    private fun skillSlot(p: Player) {
        val current = p.inventory.getItem(PlayerInventoryLayout.SKILL_HOTBAR_SLOT)
        if(!isSkill(current)) {
            if(current != null && adventure.bound(current)) {
                val free=PlayerInventoryLayout.STORAGE_SLOT_RANGE.firstOrNull { PlayerInventoryLayout.isUsableStorageSlot(it) && p.inventory.getItem(it) == null }
                if(free == null) { p.sendMessage(message(p,"ec.profile.skill_slot_space")); return }
                p.inventory.setItem(free,current)
            } else if(current != null) p.world.dropItemNaturally(p.location,current)
            p.inventory.setItem(PlayerInventoryLayout.SKILL_HOTBAR_SLOT,ItemStack(Material.NETHER_STAR).apply { itemMeta = itemMeta.apply {
                displayName(message(p,"ec.profile.skill_slot"))
                lore(listOf(
                    message(p,"ec.skill_slot.lore_cycle"),
                    message(p,"ec.skill_slot.lore_cast"),
                    message(p,"ec.skill_slot.lore_guide"),
                ))
                persistentDataContainer.set(slotKey,PersistentDataType.BYTE,1)
            } })
        } else {
            current!!.editMeta {
                it.displayName(message(p,"ec.profile.skill_slot"))
                it.lore(listOf(
                    message(p,"ec.skill_slot.lore_cycle"),
                    message(p,"ec.skill_slot.lore_cast"),
                    message(p,"ec.skill_slot.lore_guide"),
                ))
            }
        }
    }
    private fun equippedDefinitions(p: Player,profile: PlayerProfile): List<ItemDefinition> {
        val equipped: List<Pair<GearSlot,ItemStack?>> = listOf(GearSlot.HEAD to p.inventory.helmet,GearSlot.CHEST to p.inventory.chestplate,GearSlot.LEGS to p.inventory.leggings,GearSlot.FEET to p.inventory.boots,GearSlot.HAND to p.inventory.itemInMainHand,GearSlot.OFFHAND to p.inventory.itemInOffHand)
        val definitions=equipped.mapNotNull { (slot,stack) -> item(stack)?.takeIf { it.slot == slot }?.let { def -> def.copy(stats=def.enhancedStats(stack?.itemMeta?.persistentDataContainer?.get(enhancementKey,PersistentDataType.INTEGER) ?: 0,snapshot.balance,snapshot.world.tuning.enhancementRate)) } }.toMutableList()
        profile.accessory?.let { id -> if(inventory(p).count(id) > 0) snapshot.items.find(id)?.takeIf { it.slot == GearSlot.ACCESSORY }?.let(definitions::add) }
        return definitions
    }
    internal fun effectiveStats(p: Player,profile: PlayerProfile): Stats {
        val base=game.stats(profile,equippedDefinitions(p,profile))
        val buff=defenseBuffs[p.uniqueId]?.takeIf { it.second > monotonicMillis() }?.first ?: 0.0
        return base.copy(defense=(base.defense+buff).coerceAtMost(snapshot.balance.statCap))
    }
    private fun applyStats(p: Player, profile: PlayerProfile, calculated: Stats = effectiveStats(p,profile)) {
        val max = calculated.hp.coerceIn(1.0,content.snapshot().balance.statCap)
        p.getAttribute(Attribute.MAX_HEALTH)?.baseValue = max
        p.isHealthScaled = true; p.healthScale = PaperHealthDisplay.VANILLA_HEARTS_SCALE
        if(p.health > max) p.health = max
    }
    private fun hasEnhancementToken(player:Player,token:String):Boolean = player.inventory.contents.filterNotNull().any { stack ->
        stack.itemMeta.persistentDataContainer.get(enhancementTransactionKey,PersistentDataType.STRING) == token
    }
    /** 周辺取引がロールバックした場合に、強化保留記録を解除します。 */
    internal fun rollbackEnhancement(id:UUID) { enhancementFlights.remove(id) }
    /** 保留強化の対象装備を見失った場合に、消費素材・通貨を一度だけ返却します。 */
    private fun settleEnhancement(player:Player,profile:PlayerProfile):Boolean {
        val flight=enhancementFlights.remove(profile.uuid) ?: return true
        if(hasEnhancementToken(player,flight.token)) return true
        profile.currency=(profile.currency+flight.currency).coerceAtMost(snapshot.world.tuning.maxCurrency)
        val restored=runCatching { inventory(player).exchange(emptyMap(),mapOf(flight.material to 1)) }.getOrDefault(false)
        if(restored) {
            profile.discoverItem(flight.material)
            runCatching { player.saveData() }.onFailure { logger.warning("Failed to persist refunded enhancement items for ${profile.uuid}: ${it.message}") }
        } else profile.pendingRewards.merge(flight.material,1,Int::plus)
        save(profile)
        return false
    }
    private fun advancementWarning(error:Throwable) {
        if(advancementWarningLogged) return
        advancementWarningLogged=true
        logger.warning("Could not register title advancement data pack; profile unlock flags remain authoritative: ${error.message}")
    }
    /** 所有するデータパックだけを生成し、PaperのDatapackManagerで進捗定義を再読込します。 */
    private fun reloadAdvancementPack() {
        val world=server.worlds.firstOrNull { it.environment==World.Environment.NORMAL && it.name!=WorldIds.DEMO }
            ?: server.worlds.firstOrNull() ?: error("No world is available for the advancement data pack.")
        val rootTitle=plain(language.component(null,"ec.advancement.root_title"))
        val rootDescription=plain(language.component(null,"ec.advancement.root_description"))
        val packDescription=plain(language.component(null,"ec.advancement.pack_description"))
        val installation=AdvancementDatapack.install(world.worldFolder.toPath(),content.snapshot().world.titles.values,rootTitle,rootDescription,packDescription)
        val manager=server.datapackManager
        manager.refreshPacks()
        val datapack=manager.getPack(installation.packName) ?: error("Advancement data pack was not detected: ${installation.packName}")
        val enabled=datapack.isEnabled
        if(!enabled) datapack.isEnabled=true else if(installation.changed) server.reloadData()
        server.updateResources()
        server.onlinePlayers.forEach { player -> profiles[player.uniqueId]?.let { syncTitleAdvancements(player,it) } }
    }
    /** 永続プロフィールflagを正本とし、Minecraft進捗の付与・取消を同期します。 */
    internal fun syncTitleAdvancements(player:Player,profile:PlayerProfile) {
        val newlyUnlocked=profile.syncTitleUnlockFlags(snapshot.world.titles)
        if(newlyUnlocked.isNotEmpty()) save(profile)
        try {
            val root=server.getAdvancement(NamespacedKey("ec","chronicle/root")) ?: return
            val rootProgress=player.getAdvancementProgress(root)
            rootProgress.remainingCriteria.firstOrNull()?.let(rootProgress::awardCriteria)
            snapshot.world.titles.values.forEach { title ->
                if(title.advancement == null) return@forEach
                val advancement=server.getAdvancement(NamespacedKey("ec","chronicle/${title.id.substringAfter(':')}")) ?: return@forEach
                val progress=player.getAdvancementProgress(advancement)
                val criterion="profile_condition_met"
                if(title.unlockFlag in profile.flags) {
                    if(criterion in progress.remainingCriteria) progress.awardCriteria(criterion)
                } else if(criterion in progress.awardedCriteria) progress.revokeCriteria(criterion)
            }
        } catch(error:Exception) { advancementWarning(error) }
    }
    private fun applyTitle(player:Player,profile:PlayerProfile) {
        val title=profile.selectedTitle?.let(snapshot.world.titles::get)?.takeIf { it.unlockFlag in profile.flags }?.let { contentText(player,"title",it.id,"name",it.name) }
        val display=if(title == null) Component.text(player.name) else Component.text("[$title] ${player.name}")
        player.displayName(display); player.playerListName(display)
    }
    internal fun openCodex(player: Player) = codexController.openCodex(player)
    internal fun openCodexArea(player: Player, areaId: String) = codexController.openCodexArea(player, areaId)
    internal fun openCodexItems(player: Player) = codexController.openCodexItems(player)
    internal fun openCodexFish(player: Player) = codexController.openCodexFish(player)
    internal fun openCodexGathering(player: Player) = codexController.openCodexGathering(player)
    /** MockBukkitでは旧Inventory経路を残し、Paperでは同じ内容をInvUIへ描画します。 */
    internal fun openRpgMenu(player: Player, inventory: Inventory, onClose: () -> Unit = {}, onClick: (Int, org.bukkit.event.inventory.ClickType) -> Unit = { _, _ -> }): Boolean {
        if (!paperInventoryUi) {
            player.openInventory(inventory)
            return false
        }
        val holder = inventory.holder as? RpgMenu ?: return false
        invUiInventoryAdapter.open(player, inventory, holder.title, holder.kind, { slot, click ->
            handleInvUiAction(player) { onClick(slot,click) }
        }, onClose)
        return true
    }
    /** InvUIのコールバック例外を画面操作の失敗として処理し、PaperのERRORログへ漏らしません。 */
    internal fun handleInvUiAction(player: Player, action: () -> Unit) {
        try {
            action()
        } catch (error: Exception) {
            logger.fine("InvUI action rejected for ${player.uniqueId}: ${error.message}")
            player.sendMessage(message(player,"ec.command.failed", "reason" to diagnostic(player,error.message.orEmpty())))
        }
    }
    /** 図鑑の画面移動をInvUI/旧Inventoryのどちらからも同じ処理へ接続します。 */
    internal fun openCodexMenu(player: Player, inventory: Inventory) {
        val holder = inventory.holder as? RpgMenu ?: return
        openRpgMenu(player, inventory) { slot, _ -> codexAction(player,holder.kind,holder.contentId,slot) }
    }
    internal fun openEnhancement(player:Player) {
        val stack=player.inventory.itemInMainHand
        val definition=item(stack) ?: error("ec.error.enhance_hold_gear")
        require(stack.amount == 1 && definition.slot in setOf(GearSlot.HAND,GearSlot.HEAD,GearSlot.CHEST,GearSlot.LEGS,GearSlot.FEET)) { "ec.error.enhance_one_gear" }
        require(definition.canEquip(profiles[player.uniqueId] ?: error("ec.error.profile_loading"))) { "ec.error.equipment_requirements" }
        val level=stack.itemMeta.persistentDataContainer.get(enhancementKey,PersistentDataType.INTEGER) ?: 0
        val tuning=snapshot.world.tuning
        val menu=RpgMenu("enhance",definition.id,level.toString(),message(player,"ec.menu.enhance")).inventory
        menu.setItem(EnhanceLayout.TARGET_ITEM_SLOT,stack.clone())
        menu.setItem(EnhanceLayout.CONFIRM_SLOT,ItemStack(Material.ANVIL).apply { itemMeta=itemMeta.apply {
            displayName(message(player,"ec.enhance.button", "level" to (level+1)))
            lore(listOf(message(player,"ec.enhance.material", "item" to contentName(player,tuning.enhancementMaterial)),message(player,"ec.enhance.cost", "cost" to tuning.enhancementBaseCurrency*(level+1)),message(player,"ec.enhance.cap", "level" to tuning.enhancementCap)))
        } })
        openRpgMenu(player, menu) { slot, _ -> if (slot == EnhanceLayout.CONFIRM_SLOT) runCatching { enhance(player, menu.holder as RpgMenu) }.onFailure { player.sendMessage(message(player,"ec.command.failed", "reason" to diagnostic(player,it.message.orEmpty()))) } }
    }
    internal fun enhance(player:Player,menu:RpgMenu) {
        val p=profiles[player.uniqueId] ?: error("ec.error.profile_loading")
        val stack=player.inventory.itemInMainHand
        require(identify(stack) == menu.contentId) { "ec.error.enhance_item_changed" }
        val definition=item(stack) ?: error("ec.error.enhance_item_missing")
        val current=stack.itemMeta.persistentDataContainer.get(enhancementKey,PersistentDataType.INTEGER) ?: 0
        require(current == menu.nodeId.toInt()) { "ec.error.enhance_level_changed" }
        val tuning=snapshot.world.tuning
        require(current < tuning.enhancementCap) { "ec.error.enhance_cap" }
        val cost=Math.multiplyExact(tuning.enhancementBaseCurrency,(current+1).toLong())
        val next=definition.enhancedStats(current+1,snapshot.balance,tuning.enhancementRate)
        val now=definition.enhancedStats(current,snapshot.balance,tuning.enhancementRate)
        require(next != now) { "ec.error.enhance_budget_cap" }
        val flight=EnhancementFlight(UUID.randomUUID().toString(),tuning.enhancementMaterial,cost)
        adventure.transaction(player,{ inventory ->
            require(p.currency >= cost) { "ec.error.currency_low" }
            require(inventory.exchange(mapOf(tuning.enhancementMaterial to 1),emptyMap())) { "ec.error.enhance_material_missing" }
            p.currency -= cost
            p.bestEnhancementLevel=maxOf(p.bestEnhancementLevel,current+1)
            game.quests.event(p,ObjectiveKind.ENHANCE_GEAR,(current+1).toString(),1,inventory)
        },beforePersist={
            val held=player.inventory.itemInMainHand
            require(identify(held) == definition.id && (held.itemMeta.persistentDataContainer.get(enhancementKey,PersistentDataType.INTEGER) ?: 0) == current) { "ec.error.enhance_item_changed_no_cost" }
            val meta=held.itemMeta
            meta.persistentDataContainer.set(enhancementKey,PersistentDataType.INTEGER,current+1)
            meta.persistentDataContainer.set(enhancementTransactionKey,PersistentDataType.STRING,flight.token)
            val enhancementLore = enhancementLoreLines(language, tuning.enhancementCap)
            val lore = meta.lore() ?: emptyList()
            meta.lore(lore.filterNot { plain(it) in enhancementLore } + message(player,"ec.enhance.level_lore", "level" to (current+1)))
            held.itemMeta=meta; player.inventory.setItemInMainHand(held)
            player.saveData()
            enhancementFlights[player.uniqueId]=flight
        },success={
            if(settleEnhancement(player,p)) {
                syncTitleAdvancements(player,p)
                applyStats(player,p)
                player.sendMessage(message(player,"ec.enhance.success", "level" to (current+1)))
            } else player.sendMessage(message(player,"ec.enhance.refunded"))
        })
    }
    /** 言語・強化上限が変わった時だけ既存強化loreを再生成します。 */
    private fun enhancementLoreLines(catalog: LangCatalog, cap: Int): Set<String> {
        if (enhancementLoreLanguage !== catalog || enhancementLoreCap != cap) {
            enhancementLoreLines = (1..cap).flatMap { level ->
                listOf("ja_jp", "en_us").map { locale ->
                    plain(catalog.component(locale, "ec.enhance.level_lore", mapOf("level" to level)))
                }
            }.toSet()
            enhancementLoreLanguage = catalog
            enhancementLoreCap = cap
        }
        return enhancementLoreLines
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false) fun codexClick(e:InventoryClickEvent) {
        val player=e.whoClicked as? Player ?: return
        val menu=e.view.topInventory.holder as? RpgMenu ?: return
        if(!menu.kind.startsWith("codex-") && menu.kind != "enhance") return
        e.isCancelled=true
        if(e.rawSlot !in 0 until e.view.topInventory.size) return
        if(menu.kind == "enhance") { if(e.rawSlot == EnhanceLayout.CONFIRM_SLOT) runCatching { enhance(player,menu) }.onFailure { player.sendMessage(message(player,"ec.command.failed", "reason" to diagnostic(player,it.message.orEmpty()))) }; return }
        codexAction(player,menu.kind,menu.contentId,e.rawSlot)
    }
    private fun codexAction(player: Player, kind: String, contentId:String, slot: Int) {
        when(slot) {
            CodexLayout.PREVIOUS_PAGE_SLOT -> codexController.turnPage(player,kind,contentId,-1)
            CodexLayout.BACK_SLOT -> if(kind!="codex-areas") openCodex(player)
            CodexLayout.FISH_TAB_SLOT -> openCodexFish(player)
            CodexLayout.GATHERING_TAB_SLOT -> openCodexGathering(player)
            CodexLayout.ITEM_TAB_SLOT -> openCodexItems(player)
            CodexLayout.NEXT_PAGE_SLOT -> codexController.turnPage(player,kind,contentId,1)
            else -> if(kind=="codex-areas") codexController.openAreaFromSlot(player,slot)
        }
    }
    private fun tick() {
        game.skills.cleanup()
        val now=monotonicMillis(); procReady.entries.removeIf { it.value <= now }; defenseBuffs.entries.removeIf { it.value.second <= now }; basicAttacks.cleanup()
        if(::adventure.isInitialized) adventure.tick()
        if(::dungeons.isInitialized) dungeons.tick()
        if(::gathering.isInitialized) { /* its bounded 2-tick controller owns the life-skill HUD */ }
        server.onlinePlayers.forEach { p ->
          try {
            val profile = profiles[p.uniqueId] ?: return@forEach
            if(resourcePacks.isAwaitingRequiredPack(p.uniqueId)) return@forEach
            val c = profile.classId?.let { content.snapshot().classes.find(it) } ?: return@forEach
            if(adventure.locked(p.uniqueId)) return@forEach
            val gatheringActive=::gathering.isInitialized && gathering.active(p.uniqueId)
            val fishingActive=::fishing.isInitialized && fishing.active(p.uniqueId)
            val s = effectiveStats(p,profile); applyStats(p,profile,s); applyTitle(p,profile)
            val beforeResource = profile.resource
            val movedRecently = lastMoved[p.uniqueId]?.let { Bukkit.getCurrentTick() - it < PlayerMovementTuning.MOVED_RECENTLY_TICKS } ?: false
            game.regenerate(profile,s.resource,movedRecently)
            if(profile.resource != beforeResource) dirty.add(p.uniqueId)
            profile.selectedSkill = profile.selectedSkill.coerceIn(0,c.skills.lastIndex)
            val skill = c.skills[profile.selectedSkill]
            wayfinding.tickPlayer(p, profile)
            if(shouldSendClassHud(gatheringActive,fishingActive)) p.sendActionBar(message(p,"ec.hud.main", "hp" to p.health.toInt(), "max_hp" to s.hp.toInt(), "resource_name" to resourceName(p,c.resource), "resource" to profile.resource.toInt(), "max_resource" to s.resource.toInt(), "skill" to content.snapshot().skills[skill].let { contentText(p,"skill",it.id,"name",it.name) }, "cooldown" to "%.1f".format(MinecraftTicks.millisToSeconds(game.skills.remaining(p.uniqueId,skill))), "party" to adventure.parties.members(p.uniqueId).size))
          } catch(error: Exception) { logger.warning("HUD update failed ${p.uniqueId}: ${error.message}") }
        }
            if(++autosaveSeconds >= PaperTaskIntervals.PROFILE_AUTOSAVE_SECONDS) {
            autosaveSeconds = 0; dirty.toList().forEach { profiles[it]?.let(::save) }; dirty.clear()
        }
    }
    private fun cycle(p: Player) {
        val profile = profiles[p.uniqueId] ?: return
        val c = profile.classId?.let { content.snapshot().classes.find(it) } ?: return
        profile.selectedSkill = (profile.selectedSkill+1)%c.skills.size; dirty.add(p.uniqueId)
        p.sendMessage(message(p,"ec.skill.selected", "skill" to content.snapshot().skills[c.skills[profile.selectedSkill]].let { contentText(p,"skill",it.id,"name",it.name) }))
    }
    @EventHandler fun swap(e: PlayerSwapHandItemsEvent) { if(::fishing.isInitialized && fishing.active(e.player.uniqueId)) { e.isCancelled=true; return }; e.isCancelled = true; cycle(e.player) }
    @EventHandler fun drop(e: PlayerDropItemEvent) { if(isSkill(e.itemDrop.itemStack) || e.player.uniqueId !in profiles) e.isCancelled = true }
    @EventHandler fun interact(e: PlayerInteractEvent) {
        if(e.hand != EquipmentSlot.HAND) return
        val p = e.player
        if(e.action.isLeftClick) {
            if(e.useItemInHand() == Event.Result.DENY) return
            val profile = profiles[p.uniqueId] ?: return
            if(adventure.locked(p.uniqueId)) return
            val held = p.inventory.itemInMainHand
            val weapon = item(held)?.takeIf { it.slot == GearSlot.HAND } ?: return
            if(held.type in RANGED_BASIC_WEAPON_MATERIALS) {
                if(!weapon.canEquip(profile)) { p.sendMessage(message(p,"ec.skill.weapon_requirements")); return }
                rangedBasicAttack(p, held.type == Material.BOW || held.type == Material.CROSSBOW)
            }
            return
        }
        if(!e.action.isRightClick) return
        val profile = profiles[p.uniqueId] ?: run { e.isCancelled = true; return }
        if(p.inventory.heldItemSlot == PlayerInventoryLayout.SKILL_HOTBAR_SLOT && isSkill(p.inventory.itemInMainHand)) {
            e.isCancelled = true
            if(p.isSneaking) cycle(p) else wayfinding.showGuide(p, profile, spawnTrail = true, throttled = true)
            return
        }
        if(e.clickedBlock?.type?.isInteractable == true && !p.isSneaking) return
        if(adventure.locked(p.uniqueId)) { e.isCancelled=true; return }
        if(adventure.consume(p,p.inventory.itemInMainHand)) { e.isCancelled=true; return }
        val weapon = item(p.inventory.itemInMainHand)?.takeIf { it.slot == GearSlot.HAND } ?: return
        e.isCancelled = true
        if(!weapon.canEquip(profile)) { p.sendMessage(message(p,"ec.skill.weapon_requirements")); return }
        val c = profile.classId?.let { content.snapshot().classes.find(it) } ?: return
        val skill = content.snapshot().skills[c.skills[profile.selectedSkill.coerceIn(0,c.skills.lastIndex)]]
        val target = p.world.rayTraceEntities(p.eyeLocation,p.eyeLocation.direction,skill.range,CombatGeometry.SKILL_TARGET_HITBOX_BLOCKS) { it is LivingEntity && it !is Player && it.persistentDataContainer.has(enemyKey) }?.hitEntity as? LivingEntity
        if(target != null && !p.hasLineOfSight(target)) { p.sendMessage(message(p,"ec.skill.obstructed")); return }
        val result=castSkill(p,target)
        if(result != CastResult.SUCCESS) p.sendMessage(message(p,when(result) { CastResult.RESOURCE -> "ec.skill.resource_low"; CastResult.INVALID_TARGET -> "ec.skill.target_missing"; else -> "ec.skill.cooldown" }))
    }
    /** 弓・杖などの遠距離武器で左クリックした際、照準先の敵へRPG通常攻撃を発射します。 */
    internal fun rangedBasicAttack(player: Player, isBow: Boolean): Boolean {
        val profile = profiles[player.uniqueId] ?: return false
        if(profile.classId == null || adventure.locked(profile.uuid)) return false
        val range = if (isBow) 16.0 else 12.0
        val trace = runCatching {
            player.world.rayTraceEntities(player.eyeLocation, player.eyeLocation.direction, range, CombatGeometry.SKILL_TARGET_HITBOX_BLOCKS) {
                it is LivingEntity && it !is Player && it.persistentDataContainer.has(enemyKey)
            }
        }.getOrNull()
        val target = trace?.hitEntity as? LivingEntity
        if (target != null && !player.hasLineOfSight(target)) return false
        if (!basicAttacks.accept(profile.uuid, snapshot.world.tuning.basicAttackCooldown)) return false
        val endPoint = target?.location?.clone()?.add(0.0, target.height * 0.6, 0.0)
            ?: player.eyeLocation.clone().add(player.eyeLocation.direction.multiply(range.coerceAtMost(10.0)))
        combatFeedback.recordRangedBasicShot(player, endPoint, isBow)
        if (target != null) hit(player, target, 1.0)
        return target != null
    }
    /** 実イベントとテストが共有するスキル経路です。照準対象を渡しray traceを分離できます。 */
    fun castSkill(player: Player,target: LivingEntity?): CastResult {
        val profile=profiles[player.uniqueId] ?: return CastResult.INVALID_TARGET
        if(adventure.locked(profile.uuid)) return CastResult.COOLDOWN
        val weapon=item(player.inventory.itemInMainHand)?.takeIf { it.slot == GearSlot.HAND && it.canEquip(profile) } ?: return CastResult.INVALID_TARGET
        val c=profile.classId?.let { snapshot.classes.find(it) } ?: return CastResult.INVALID_TARGET
        val skill=snapshot.skills[c.skills[profile.selectedSkill.coerceIn(0,c.skills.lastIndex)]]
        if(target == null && skill.heal <= 0 && skill.defenseBuff <= 0 && skill.tauntTicks <= 0) return CastResult.INVALID_TARGET
        if(target != null && (!target.location.isWithinBlocks(player.location,skill.range) || !target.persistentDataContainer.has(enemyKey))) return CastResult.INVALID_TARGET
        if(target != null && enemies.state(target.uniqueId)?.participants?.let { it.isNotEmpty() && player.uniqueId !in it } == true) return CastResult.INVALID_TARGET
        val result=game.skills.cast(profile,skill)
        if(result != CastResult.SUCCESS) return result
        if(skill.heal > 0 || skill.defenseBuff > 0) {
            val recipientIds=if((skill.partyHeal || skill.partyDefenseBuff) && skill.allyRadius > 0) adventure.parties.members(player.uniqueId) else setOf(player.uniqueId)
            val recipients=player.world.players.filter { recipient ->
                recipient.uniqueId == player.uniqueId ||
                    (recipient.uniqueId in recipientIds && recipient.location.isWithinBlocks(player.location, skill.allyRadius))
            }
            recipients.forEach { recipient ->
                val recipientProfile=profiles[recipient.uniqueId] ?: return@forEach
                if(skill.heal > 0 && (recipient.uniqueId == player.uniqueId || skill.partyHeal)) recipient.health=(recipient.health+effectiveStats(recipient,recipientProfile).hp*skill.heal).coerceAtMost(recipient.getAttribute(Attribute.MAX_HEALTH)!!.value)
                if(skill.defenseBuff > 0 && (recipient.uniqueId == player.uniqueId || skill.partyDefenseBuff)) defenseBuffs[recipient.uniqueId]=skill.defenseBuff to (monotonicMillis()+MinecraftTicks.ticksToMillis(skill.buffTicks.toLong()))
            }
        }
        if(skill.tauntTicks > 0) enemies.taunt(player,skill.radius.coerceAtLeast(skill.allyRadius).coerceAtLeast(skill.range),skill.tauntTicks,skill.threatMultiplier)
        if(target != null) {
            val targets=if(skill.radius > 0) {
                target.getNearbyEntities(skill.radius,skill.radius,skill.radius)
                    .filterIsInstance<LivingEntity>()
                    .filter { candidate ->
                        candidate.persistentDataContainer.has(enemyKey) &&
                            enemyAllowsPlayer(player.uniqueId,enemies.state(candidate.uniqueId)?.participants.orEmpty())
                    } + target
            } else listOf(target)
            val uniqueTargets=targets.distinctBy { it.uniqueId }
            uniqueTargets.forEach { hit(player,it,game.skills.multiplier(profile,skill),skill.element,useSkillPower=true) }
        }
        combatFeedback.recordSkillCast(player, skill, target)
        dirty.add(player.uniqueId); return result
    }
    internal fun defenseBuff(player: Player,amount: Double,ticks: Int) { defenseBuffs[player.uniqueId]=amount to (monotonicMillis()+MinecraftTicks.ticksToMillis(ticks.toLong())) }
    private val defenseBuffs=mutableMapOf<UUID,Pair<Double,Long>>()
    private val procReady=mutableMapOf<Triple<UUID,String,Int>,Long>()
    internal fun procEffects(player: Player,target: LivingEntity) {
        val profile=profiles[player.uniqueId] ?: return
        if(!proccing.add(player.uniqueId)) return
        try {
        val equipped=equippedDefinitions(player,profile).filter { it.canEquip(profile) }
        val now=monotonicMillis()
        equipped.forEach { item -> snapshot.world.itemProperties[item.id]?.effects?.forEachIndexed { index,effect ->
            if(effect.kind !in setOf("ATTACK_PROC","DEFENSE_BUFF","HEAL","RESOURCE")) return@forEachIndexed
            val key=Triple(player.uniqueId,item.id,index)
            if(now < procReady.getOrDefault(key,0) || kotlin.random.Random.nextDouble() >= effect.chance) return@forEachIndexed
            procReady[key]=now+effect.cooldown.coerceAtLeast(1)
            if(effect.kind == "ATTACK_PROC" && !target.isDead) hit(player,target,0.0,effect.element,effect.amount)
            if(effect.kind == "DEFENSE_BUFF") defenseBuff(player,effect.amount,effect.duration)
            if(effect.kind == "HEAL") player.health=(player.health+effect.amount).coerceAtMost(player.getAttribute(Attribute.MAX_HEALTH)!!.value)
            if(effect.kind == "RESOURCE") { profile.resource=(profile.resource+effect.amount).coerceAtMost(effectiveStats(player,profile).resource); dirty.add(profile.uuid) }
        } }
        } finally { proccing.remove(player.uniqueId) }
    }
    private val casting = mutableSetOf<UUID>()
    private val proccing = mutableSetOf<UUID>()
    private val basicAttacks=ActionGate(Clock(::monotonicMillis))
    private fun hit(p: Player,target: LivingEntity,multiplier: Double,element: String = "PHYSICAL",flat: Double? = null,useSkillPower: Boolean = false) {
        val profile = profiles[p.uniqueId] ?: return
        val d = target.persistentDataContainer.get(enemyKey,PersistentDataType.STRING)?.let { content.snapshot().documents[it] } ?: return
        val s = effectiveStats(p,profile)
        val enemy=snapshot.world.enemies[d.string("id")]
        val elementMod=when { element in (enemy?.weaknesses ?: emptySet()) -> snapshot.world.tuning.weakness; element in (enemy?.resistances ?: emptySet()) -> snapshot.world.tuning.resistance; else -> 1.0 }
        val critical = flat == null && kotlin.random.Random.nextDouble() < s.crit.coerceIn(0.0,content.snapshot().balance.critCap)
        val amount = content.snapshot().balance.damage(if(flat == null) s else Stats.ZERO,d.num("defense"),multiplier*(if(useSkillPower) s.skillPower else 1.0),flat ?: snapshot.world.itemProperties[item(p.inventory.itemInMainHand)?.id]?.weapon ?: 0.0,critical,elementMod)
        val metaKey = p.uniqueId to target.uniqueId
        pendingHitMeta[metaKey] = PendingHitMeta(critical, element, elementMod, useSkillPower)
        casting.add(p.uniqueId)
        try { target.damage(amount,p) } finally { casting.remove(p.uniqueId); pendingHitMeta.remove(metaKey) }
    }
    internal fun tickCombatFeedback() = combatFeedback.tick()
    internal fun recordCombatHit(attacker: Player, target: LivingEntity, enemyDef: EnemyDefinition?, remainingHp: Double, maxHp: Double, damage: Double, killed: Boolean) {
        val meta = pendingHitMeta[attacker.uniqueId to target.uniqueId]
        combatFeedback.recordHit(
            attacker = attacker,
            target = target,
            enemyDef = enemyDef,
            remainingHp = remainingHp,
            maxHp = maxHp,
            damage = damage,
            critical = meta?.critical == true,
            element = meta?.element ?: "PHYSICAL",
            elementMod = meta?.elementMod ?: 1.0,
            isSkill = meta?.isSkill == true,
            killed = killed,
        )
    }
    internal fun captureQuestSnapshot(profile: PlayerProfile, inventory: InventoryAccess): QuestProgressSnapshot =
        wayfinding.captureQuestSnapshot(profile, inventory)
    internal fun notifyQuestDelta(player: Player, profile: PlayerProfile, before: QuestProgressSnapshot) =
        wayfinding.notifyQuestDelta(player, profile, before, inventory(player, persistRemoval = false))
    internal fun sendIncompleteQuestHints(player: Player, profile: PlayerProfile, questId: String) =
        wayfinding.sendIncompleteHints(player, profile, questId)
    internal fun onQuestStarted(player: Player, profile: PlayerProfile) {
        runCatching { player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.3f) }
        wayfinding.showGuide(player, profile, spawnTrail = true)
    }
    internal fun onQuestCompleted(player: Player, profile: PlayerProfile) {
        runCatching { player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.0f) }
        wayfinding.showGuide(player, profile, spawnTrail = false)
    }
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true) fun damage(e: EntityDamageByEntityEvent) {
        val target = e.entity as? LivingEntity ?: return
        val attacker = (e.damager as? Player) ?: ((e.damager as? Projectile)?.shooter as? Player)
        if(attacker != null && attacker.uniqueId !in casting && target.persistentDataContainer.has(enemyKey)) {
            e.isCancelled = true
            basicAttack(attacker,target)
        } else if(attacker != null && attacker.uniqueId in casting) {
            setRpgDamage(e,e.damage)
        } else if(target is Player && e.damager.persistentDataContainer.has(enemyKey)) {
            val profile = profiles[target.uniqueId] ?: run { e.isCancelled = true; return }
            val d = content.snapshot().documents[e.damager.persistentDataContainer.get(enemyKey,PersistentDataType.STRING)] ?: return
            setRpgDamage(e,content.snapshot().balance.damage(Stats(attack=d.num("attack")),effectiveStats(target,profile).defense,if(::enemies.isInitialized) enemies.multiplier(e.damager.uniqueId) else 1.0,0.0,false))
        }
    }
    /** 実プレイヤー攻撃と同じゲート・ダメージ計算を、攻撃packetなしで呼び出せます。 */
    fun basicAttack(player: Player,target: LivingEntity): Boolean {
        val profile=profiles[player.uniqueId] ?: return false
        if(profile.classId == null || adventure.locked(profile.uuid) || !target.persistentDataContainer.has(enemyKey)) return false
        if(item(player.inventory.itemInMainHand)?.canEquip(profile) == false) return false
        if(!basicAttacks.accept(profile.uuid,snapshot.world.tuning.basicAttackCooldown)) return false
        hit(player,target,1.0); return true
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) fun guardian(e: EntityDamageEvent) {
        val player=e.entity as? Player ?: return
        if(e.finalDamage < player.health) return
        val profile=profiles[player.uniqueId] ?: return
        val now=monotonicMillis()
        for(item in equippedDefinitions(player,profile).filter { it.canEquip(profile) }) {
            for((index,effect) in snapshot.world.itemProperties[item.id]?.effects.orEmpty().withIndex()) {
                if(effect.kind != "REVIVE") continue
                val key=Triple(player.uniqueId,item.id,index)
                if(now < procReady.getOrDefault(key,0) || kotlin.random.Random.nextDouble() >= effect.chance) continue
                procReady[key]=now+effect.cooldown.coerceAtLeast(1); e.isCancelled=true
                player.health=effect.amount.coerceIn(1.0,player.getAttribute(Attribute.MAX_HEALTH)!!.value)
                player.sendMessage(message(player,"ec.combat.revive")); return
            }
        }
    }
    @Suppress("DEPRECATION")
    private fun setRpgDamage(e: EntityDamageByEntityEvent, amount: Double) {
        e.damage = amount
        EntityDamageEvent.DamageModifier.entries.filter { it != EntityDamageEvent.DamageModifier.BASE }.forEach { modifier ->
            if(e.isApplicable(modifier)) e.setDamage(modifier,if(modifier == EntityDamageEvent.DamageModifier.ABSORPTION) -minOf(amount,(e.entity as LivingEntity).absorptionAmount) else 0.0)
        }
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) fun successfulHit(e: EntityDamageByEntityEvent) {
        val p = (e.damager as? Player) ?: ((e.damager as? Projectile)?.shooter as? Player) ?: return
        if(e.finalDamage > 0 && e.entity.persistentDataContainer.has(enemyKey)) profiles[p.uniqueId]?.let { game.onHit(it,effectiveStats(p,it).resource); dirty.add(p.uniqueId) }
    }
    @EventHandler fun move(e: PlayerMoveEvent) {
        if(e.from.world != e.to.world || e.from.distanceSquared(e.to) > 0.0) lastMoved[e.player.uniqueId] = Bukkit.getCurrentTick()
        if (e.from.world == e.to.world && e.from.blockX == e.to.blockX && e.from.blockY == e.to.blockY && e.from.blockZ == e.to.blockZ) return
        val safeAreas=safeAreas(e.to.world.name)
        val to=e.to
        if(enteredSafeArea(Position(e.from.world.name,e.from.x,e.from.y,e.from.z),Position(to.world.name,to.x,to.y,to.z),safeAreas)) {
            clearProjectedSafeHostiles(this,to)
        }
    }
    @EventHandler fun split(e: SlimeSplitEvent) { if(e.entity.persistentDataContainer.has(enemyKey)) e.isCancelled = true }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    fun suppressVanillaHostilesInSafeAreas(e:CreatureSpawnEvent) {
        val world=e.location.world
        if(!isManagedWorld(world) || e.entity !is Monster || e.spawnReason.name=="CUSTOM") return
        val location=e.location
        val position=Position(world.name,location.x,location.y,location.z)
        if(shouldSuppressVanillaHostileSpawn(e.spawnReason.name,true,position,safeAreas(world.name))) e.isCancelled=true
    }
    private fun spawnEnemy(player: Player,id: String) { require(enemies.spawn(id,player.location) != null) { "ec.error.spawn_duplicate_or_cap" } }
    @EventHandler fun death(e: EntityDeathEvent) {
        if(!e.entity.persistentDataContainer.has(enemyKey)) return
        e.drops.clear(); e.droppedExp=0
        val killer=e.entity.killer ?: if(::enemies.isInitialized) enemies.killer(e.entity.uniqueId) else null
        if(killer != null) rewardKill(killer,e.entity)
        if(::dungeons.isInitialized) dungeons.defeated(e.entity.uniqueId)
    }
    /** 実死亡イベントと共有する報酬経路で、MockBukkitのkiller取得に依存しません。 */
    fun rewardKill(killer: Player,entity: LivingEntity) {
        val id=entity.persistentDataContainer.get(enemyKey,PersistentDataType.STRING) ?: return
        val definition=snapshot.documents[id] ?: return
        val eligible=adventure.parties.members(killer.uniqueId).mapNotNull(Bukkit::getPlayer).filter { p ->
            p.world == entity.world && p.location.isWithinBlocks(entity.location,snapshot.world.tuning.partyRadius) && p.uniqueId in profiles
        }
        val ids=eligible.map { it.uniqueId }.toSet(); val xp=adventure.parties.splitXp(definition.long("xp"),ids); val currency=adventure.parties.splitXp(definition.long("currency"),ids)
        val loot=adventure.parties.lootRecipient(ids,lootSequence++)
        eligible.forEach { member ->
            val playerId=member.uniqueId
            adventure.whenAvailable(playerId) {
                val player=Bukkit.getPlayer(playerId) ?: return@whenAvailable
                val profile=profiles[playerId] ?: return@whenAvailable
                var codexUnlocks=emptySet<String>()
                var drops=emptyMap<String,Int>()
                val beforeQuests = wayfinding.captureQuestSnapshot(profile, inventory(player, persistRemoval = false))
                adventure.transaction(player,{ inv ->
                    codexUnlocks=profile.recordEnemyKillIndexed(id,snapshot.world.areas,snapshot.world.enemiesByArea)
                    drops=game.kill(profile,id,inv,xp[playerId] ?: 0,currency[playerId] ?: 0,playerId == loot)
                    drops.forEach { (item,amount) -> profile.pendingRewards.merge(item,amount,Int::plus) }
                }) {
                    syncTitleAdvancements(player,profile)
                    player.sendMessage(message(player,"ec.combat.enemy_defeated", "level" to profile.level, "xp" to profile.xp))
                    wayfinding.notifyQuestDelta(player, profile, beforeQuests, inventory(player, persistRemoval = false))
                    if(codexUnlocks.isNotEmpty()) player.sendMessage(message(player,"ec.codex.new_discoveries", "items" to codexUnlocks.joinToString { contentName(player,it) }))
                }
            }
        }
    }
    private var lootSequence=0L
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) fun itemPickup(e:EntityPickupItemEvent) {
        val player=e.entity as? Player ?: return
        val profile=profiles[player.uniqueId] ?: return
        identify(e.item.itemStack)?.let { if(profile.discoverItem(it)) save(profile) }
    }
    @EventHandler(priority=EventPriority.LOWEST) fun respawn(e: PlayerRespawnEvent) {
        val inDungeon = ::dungeons.isInitialized && dungeons.containsPlayer(e.player.uniqueId)
        val hub = luminaSpawn()
        if (!inDungeon && hub != null) e.respawnLocation = hub
        else if (!inDungeon) sync {
            if (e.player.isOnline) requestHubSpawn(e.player)
        }
        sync { skillSlot(e.player); profiles[e.player.uniqueId]?.let { applyStats(e.player,it); e.player.health=e.player.getAttribute(Attribute.MAX_HEALTH)!!.value } }
    }
    /** Paper Registry参照をメインスレッドへ集約し、起動時とreloadの安全性を揃えます。 */
    private fun validatePaperContent(c: ContentSnapshot) {
        if (server.isPrimaryThread()) {
            validatePaperContentNow(c)
            return
        }
        check(isEnabled) { "Plugin disabled before Paper content validation" }
        val result = CompletableFuture<Unit>()
        paperValidationWaiters.add(result)
        try {
            check(isEnabled) { "Plugin disabled before Paper content validation" }
            server.scheduler.runTask(this, Runnable {
                if (!isEnabled) {
                    result.completeExceptionally(IllegalStateException("Plugin disabled before Paper content validation"))
                } else {
                    runCatching { validatePaperContentNow(c) }
                        .onSuccess { result.complete(Unit) }
                        .onFailure(result::completeExceptionally)
                }
            })
            try {
                result.get(15, TimeUnit.SECONDS)
            } catch (error: ExecutionException) {
                throw (error.cause ?: error)
            }
        } finally {
            paperValidationWaiters.remove(result)
        }
    }

    /** Bukkit/Paperのenum・Registry定義を現在のスレッドで検証します。 */
    private fun validatePaperContentNow(c: ContentSnapshot) {
        fun validate(id: String, action: () -> Unit) { try { action() } catch(e: Exception) { throw IllegalArgumentException("${c.sources[id]} [$id]: ${e.message}",e) } }
        fun validateEnum(action: () -> Unit) {
            try { action() } catch(error: Exception) {
                throw IllegalArgumentException("ec.error.invalid_content: ${error.message}", error)
            }
        }
        c.items.all().forEach { item -> validate(item.id) { validateEnum { PaperContentValidator.itemMaterial(item.material) } } }
        c.classes.all().filter { it.icon != null }.forEach { definition -> validate(definition.id) { validateEnum { PaperContentValidator.itemMaterial(definition.icon!!) } } }
        c.world.titles.values.filter { it.advancement != null }.forEach { title -> validate(title.id) { validateEnum { PaperContentValidator.itemMaterial(title.advancement!!.icon) } } }
        c.world.gatheringNodes.values.forEach { node -> validate(node.id) { validateEnum { PaperContentValidator.blockMaterial(node.displayMaterial); PaperContentValidator.blockMaterial(node.depletedMaterial) } } }
        c.world.treasureChests.values.forEach { chest -> validate(chest.id) { validateEnum { PaperContentValidator.particle(chest.hintParticle) } } }
        c.world.dungeons.values.forEach { dungeon -> validate(dungeon.id) { validateEnum { PaperContentValidator.dungeonPalette(dungeon.palette) } } }
        c.world.enemies.values.filter { it.visualVariant != null }.forEach { enemy ->
            val entityType=c.documents[enemy.id]?.get("entity") as? String
            validate(enemy.id) { validateEnum { PaperContentValidator.wolfVariant(enemy.visualVariant!!, entityType) } }
        }
        c.documents.values.filter { it["type"] in setOf("enemy","boss") }.forEach { d -> validate(d.string("id")) { validateEnum { PaperContentValidator.livingEntity(d.string("entity")) } } }
    }
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        return executeCommand(sender,args)
    }
    /** Paperのコマンドライフサイクルへ、Bukkitコマンドと同じ処理を登録します。 */
    private fun registerPaperCommand() {
        lifecycleManager.registerEventHandler(io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents.COMMANDS) { event ->
            event.registrar().register("ec", object : io.papermc.paper.command.brigadier.BasicCommand {
                override fun execute(source: io.papermc.paper.command.brigadier.CommandSourceStack, args: Array<out String>) {
                    executeCommand(source.sender, args)
                }

                override fun suggest(
                    source: io.papermc.paper.command.brigadier.CommandSourceStack,
                    args: Array<out String>,
                ): Collection<String> = commandCompletions(source.sender, args)
            })
        }
    }
    /** plugin.ymlを読まないテストサーバー用に同じコマンド処理を接続します。 */
    private fun registerMockCommand() {
        val commandMap = server.javaClass.getMethod("getCommandMap").invoke(server) as org.bukkit.command.CommandMap
        val command = object : Command("ec") {
            override fun execute(sender: CommandSender, label: String, args: Array<out String>): Boolean = executeCommand(sender, args)
            override fun tabComplete(sender: CommandSender, alias: String, args: Array<out String>): List<String> = commandCompletions(sender, args)
        }
        command.setDescription("EtherealChronicle commands")
        commandMap.register(name, command)
    }

    /** ECコマンドの利用者向け操作と管理対象を補完します。 */
    internal fun commandCompletions(sender: CommandSender, args: Array<out String>): List<String> {
        if (!ready) return emptyList()
        val prefix = args.lastOrNull().orEmpty()
        val rootCommands = listOf("class", "guide", "quest", "party", "equipment", "dungeon", "shop", "claim", "title", "codex", "enhance", "fish", "gather", "ui", "sites")
        val adminCommands = listOf("reload", "validate", "travel", "selftest")
        val candidates = when {
            args.size <= 1 -> rootCommands + adminCommands.takeIf { sender.hasPermission("etherealchronicle.admin") }.orEmpty()
            args.firstOrNull() == "sites" && args.size == 2 && sender.hasPermission("etherealchronicle.admin") -> listOf("repair", "acknowledge-cleanup")
            args.firstOrNull() == "sites" && args.size == 3 && args[1] == "acknowledge-cleanup" && sender.hasPermission("etherealchronicle.admin") ->
                snapshot.world.areas.keys.map { it.removePrefix("ec:area_") }
            args.firstOrNull() == "travel" && args.size == 2 && sender.hasPermission("etherealchronicle.admin") ->
                snapshot.world.areas.keys.map { it.removePrefix("ec:area_") }
            args.firstOrNull() == "dungeon" && args.size == 2 ->
                listOf("enter", "leave", "records") + listOf("release").takeIf { sender.hasPermission("etherealchronicle.admin") }.orEmpty()
            args.firstOrNull() == "dungeon" && args.size == 3 && args[1] == "release" && sender.hasPermission("etherealchronicle.admin") ->
                if (::dungeons.isInitialized) dungeons.releasableSlots().map { it.toString() } else emptyList()
            args.firstOrNull() == "class" && sender.hasPermission("etherealchronicle.admin") && args.size == 2 -> server.onlinePlayers.map { it.name }
            args.firstOrNull() == "class" && sender.hasPermission("etherealchronicle.admin") && args.size == 3 -> snapshot.classes.all().map { it.id }
            else -> emptyList()
        }
        return candidates.filter { it.startsWith(prefix, ignoreCase = true) }
    }

    private fun executeCommand(sender: CommandSender, args: Array<out String>): Boolean {
        if(!ready) {
            sender.sendMessage(message(sender,"ec.startup.starting"))
            return true
        }
        if (args.firstOrNull() in setOf("sites", "travel", "selftest") ||
            (args.firstOrNull() == "dungeon" && args.getOrNull(1) == "release")
        ) {
            try {
                when (args[0]) {
                    "sites" -> simulationCommands.sites(sender, args.toList())
                    "selftest" -> selfTest.run(sender)
                    "dungeon" -> simulationCommands.releaseDungeonSlot(sender, args.toList())
                    else -> {
                        require(sender.hasPermission("etherealchronicle.admin")) { "ec.command.no_permission" }
                        require(args.size == 2) { "ec.sites.travel_usage" }
                        simulationCommands.travel(sender as? Player ?: error("ec.error.player_required"), args[1])
                    }
                }
            } catch (error: Exception) {
                sender.sendMessage(message(sender, "ec.command.failed", "reason" to diagnostic(sender, error.message.orEmpty())))
            }
            return true
        }
        val isPlayerCommand = args.firstOrNull() in setOf("guide","quest","party","equipment","dungeon","shop","claim","title","codex","enhance","fish","gather","ui") ||
            args.firstOrNull() == "class" && args.size == 1
        if (args.firstOrNull() == "class" && args.size == 1 && sender !is Player) {
            sender.sendMessage(message(sender, "ec.error.player_required"))
            return true
        }
        if(sender is Player && isPlayerCommand) {
            try { playerCommand(sender,args.toList()) } catch(error: Exception) {
                val reason=error.message?.let { diagnostic(sender,it) } ?: Component.empty()
                sender.sendMessage(message(sender,"ec.command.failed", "reason" to reason))
            }
            return true
        }
        if(!sender.hasPermission("etherealchronicle.admin")) { sender.sendMessage(message(sender,"ec.command.no_permission")); return true }
        try {
            when(args.firstOrNull()) {
                "reload", "validate" -> {
                    val reload = args[0] == "reload"
                    CompletableFuture.supplyAsync { content.load() }.whenComplete { snapshot,error -> sync {
                        if(error != null) sender.sendMessage(message(sender,"ec.command.validation_failed", "reason" to diagnostic(sender,error.cause?.message ?: error.message.orEmpty())))
                        else {
                            val paperValidationError=runCatching { validatePaperContent(snapshot) }.exceptionOrNull()
                            if(paperValidationError != null) {
                                sender.sendMessage(message(sender,"ec.command.validation_failed", "reason" to diagnostic(sender,paperValidationError.cause?.message ?: paperValidationError.message.orEmpty())))
                                return@sync
                            }
                            if(reload) {
                                if(adventure.hasTransactions() || choosing.isNotEmpty() || dungeons.activeCount() > 0) { sender.sendMessage(message(sender,"ec.command.reload_busy")); return@sync }
                                val all = profiles.values + simulated.values
                                if(all.any { p -> p.classId?.let { snapshot.classes.find(it) == null } == true || p.quests.any { (id,q) -> !q.completed && snapshot.documents[id]?.get("type") != "quest" } }) {
                                    sender.sendMessage(message(sender,"ec.command.reload_content_in_use")); return@sync
                                }
                                content.install(snapshot); game.skills.updateBalance(snapshot.balance)
                                reloadConfig()
                                val languageSetting=config.getString("language","auto") ?: "auto"
                                CompletableFuture.supplyAsync { LangCatalog.load(dataFolder.toPath(),languageSetting,logger) }
                                    .whenComplete { loadedLanguage,languageError -> sync {
                                        if(languageError == null && isEnabled) {
                                            language=loadedLanguage
                                            runCatching { reloadAdvancementPack() }.onFailure { advancementWarning(it) }
                                            sender.sendMessage(message(sender,"ec.command.reload_success"))
                                        } else if(languageError != null) {
                                            logger.warning("Language reload failed: ${languageError.message}")
                                            sender.sendMessage(message(sender,"ec.command.validation_failed", "reason" to diagnostic(sender,languageError.message.orEmpty())))
                                        }
                                    } }
                                uiTheme.updateMode(UiThemeProvider.mode(config.getString("ui.theme.mode", "icons_only")))
                                all.forEach { p -> p.level=p.level.coerceAtMost(snapshot.balance.levelCap); if(p.level == snapshot.balance.levelCap) p.xp=0; p.skillLevels.replaceAll { _,level -> level.coerceIn(1,snapshot.balance.skillLevelCap) } }
                                dirty.addAll(profiles.keys)
                                rebuildWorldProjection()
                                appliedReadyAnchors = currentReadyAnchors()
                                enemies.close(); adventure.reloadNpcs()
                                if(::gathering.isInitialized) gathering.reload()
                                if(::fishing.isInitialized) fishing.reload()
                                if(::treasures.isInitialized) treasures.reload()
                            }
                            if(!reload) {
                                logger.info("EC_CONTENT_VALIDATION_SUCCESS")
                                sender.sendMessage(message(sender,"ec.command.validate_success"))
                            }
                        }
                    } }
                }
                "world" -> {
                    val p=(args.getOrNull(1)?.let(server::getPlayerExact) ?: sender as? Player) ?: error("ec.error.player_required")
                    p.teleport(luminaSpawn() ?: error("ec.error.world_unavailable"))
                }
                "currency" -> {
                    require(args.size == 3) { "ec.error.usage: /ec currency <player> <balance>" }
                    val p=server.getPlayerExact(args[1]) ?: error("ec.error.player_missing")
                    val profile=profiles[p.uniqueId] ?: error("ec.error.profile_loading")
                    require(!adventure.locked(profile.uuid)); val balance=args[2].toLong(); require(balance in 0..snapshot.world.tuning.maxCurrency)
                    profile.currency=balance; save(profile); logger.info("Admin currency adjustment: ${sender.name} -> ${p.name} balance=$balance")
                    sender.sendMessage(message(sender,"ec.command.currency_set"))
                }
                "flag" -> {
                    require(args.size in 3..4) { "ec.error.usage: /ec flag <player> <flag> [set|clear]" }
                    val p=server.getPlayerExact(args[1]) ?: error("ec.error.player_missing")
                    val profile=profiles[p.uniqueId] ?: error("ec.error.profile_loading")
                    require(!adventure.locked(profile.uuid)) { "ec.error.transaction_busy" }
                    val flag=args[2]; require(Regex("ec:flag_[a-z][a-z0-9_]*").matches(flag)) { "ec.error.flag_format" }
                    val operation=args.getOrNull(3) ?: "set"
                    require(operation in setOf("set","clear")) { "ec.error.flag_operation" }
                    val declared=snapshot.world.chapters.values.flatMap { it.strings("flags") }.toSet()
                    require(flag in declared || flag in profile.flags) { "ec.error.flag_not_declared: $flag" }
                    val changed=if(operation == "set") profile.flags.add(flag) else profile.flags.remove(flag)
                    save(profile)
                    if(changed) syncTitleAdvancements(p,profile)
                    logger.info("Admin flag update: ${sender.name} -> ${p.name} $flag $operation")
                    sender.sendMessage(message(sender,if(changed) "ec.command.flag_changed" else "ec.command.flag_unchanged", "flag" to flag, "operation" to message(sender,if(operation == "set") "ec.command.flag_set" else "ec.command.flag_clear")))
                }
                "pack" -> {
                    require(args.size == 2 && args[1] == "info") { "ec.resource_pack.pack_info_usage" }
                    val info = resourcePacks.buildInfo() ?: error("ec.resource_pack.pack_info_missing")
                    sender.sendMessage(message(sender,"ec.resource_pack.pack_info", "sha1" to info.sha1, "size" to info.sizeBytes))
                }
                "admin" -> {
                    require(args.size == 5 && args[1] == "lifeskill") { "ec.error.usage: /ec admin lifeskill <player> <FISHING|GATHERING> <level>" }
                    val p=server.getPlayerExact(args[2]) ?: error("ec.error.player_missing")
                    val profile=profiles[p.uniqueId] ?: error("ec.error.profile_loading")
                    require(!adventure.locked(profile.uuid)) { "ec.error.transaction_busy" }
                    val kind=LifeSkillKind.valueOf(args[3].uppercase()); val level=args[4].toInt()
                    require(level in 1..snapshot.world.lifeBalance.levelCap) { "ec.error.life_level_range: ${snapshot.world.lifeBalance.levelCap}" }
                    profile.lifeSkills[kind]=LifeSkillProgress(level,0); save(profile)
                    sender.sendMessage(message(sender,"ec.command.life_level_set", "kind" to message(sender,if(kind == LifeSkillKind.FISHING) "ec.life.fishing" else "ec.life.gathering"), "level" to level))
                }
                "spawn" -> {
                    require(args.size == 3) { "ec.error.usage: /ec spawn <enemy> <player>" }
                    val p = server.getPlayerExact(args[2]) ?: error("ec.error.player_missing")
                    spawnEnemy(p,args[1]); sender.sendMessage(message(sender,"ec.command.enemy_spawned"))
                }
                "starter" -> {
                    require(args.size == 2) { "ec.error.usage: /ec starter <player>" }
                    val p = server.getPlayerExact(args[1]) ?: error("ec.error.player_missing")
                    val profile = profiles[p.uniqueId] ?: error("ec.error.profile_loading")
                    require(p.uniqueId !in choosing && profile.classId != null && !adventure.locked(p.uniqueId))
                    profile.starterGranted = false; starter(p,profile,profile.classId!!)
                }
                "give" -> {
                    require(args.size in 3..4) { "ec.error.usage: /ec give <player> <item> [amount]" }
                    val p = server.getPlayerExact(args[1]) ?: error("ec.error.player_missing")
                    val profile = profiles[p.uniqueId] ?: error("ec.error.profile_loading")
                    val amount = args.getOrNull(3)?.toInt() ?: 1
                    require(amount in 1..ContentLimits.MAX_ITEM_STACK)
                    require(!adventure.locked(p.uniqueId))
                    require(inventory(p).exchange(emptyMap(),mapOf(args[2] to amount))) { "ec.error.inventory_full" }
                    profile.discoverItem(args[2]); save(profile)
                    sender.sendMessage(message(sender,"ec.command.item_given"))
                }
                "setlevel" -> {
                    require(args.size == 3)
                    val p = server.getPlayerExact(args[1]) ?: error("ec.error.player_missing")
                    val profile = profiles[p.uniqueId] ?: error("ec.error.profile_loading")
                    require(!adventure.locked(profile.uuid))
                    val level = args[2].toInt(); require(level in 1..content.snapshot().balance.levelCap)
                    profile.level = level; profile.xp = 0; save(profile)
                    syncTitleAdvancements(p,profile)
                    sender.sendMessage(message(sender,"ec.command.level_set"))
                }
                "class" -> {
                    require(args.size == 3) { "ec.error.usage: /ec class <player> <classId>" }
                    val p = server.getPlayerExact(args[1]) ?: error("ec.error.player_missing")
                    val profile = profiles[p.uniqueId] ?: error("ec.error.profile_loading")
                    val c = content.snapshot().classes[args[2]]
                    require(!adventure.locked(profile.uuid))
                    val oldClassId=profile.classId
                    if(oldClassId != c.id && profile.starterGranted) {
                        require(choosing.add(p.uniqueId)) { "ec.error.class_busy" }
                        val queued=try { reconcileStarterWeapon(p,profile,oldClassId,c) }
                        finally { choosing.remove(p.uniqueId) }
                        game.changeClass(profile,c.id); save(profile); applyStats(p,profile)
                        if(queued) p.sendMessage(message(p,"ec.class.weapon_queued"))
                    } else {
                        game.changeClass(profile,c.id); save(profile); applyStats(p,profile)
                        if(!profile.starterGranted) starter(p,profile,c.id)
                    }
                    sender.sendMessage(message(sender,"ec.command.class_set"))
                }
                "debug" -> debug(sender,args.drop(1))
                else -> sender.sendMessage(message(sender,"ec.command.help"))
            }
        } catch(e: Exception) {
            val reason=e.message?.let { diagnostic(sender,it) } ?: Component.empty()
            sender.sendMessage(message(sender,"ec.command.failed", "reason" to reason))
        }
        return true
    }
    private fun playerCommand(player: Player,args: List<String>) {
        val p=profiles[player.uniqueId] ?: error("ec.error.profile_loading")
        require(!adventure.locked(p.uuid)) { "ec.error.transaction_busy" }
        when(args[0]) {
            "class" -> {
                require(classSelectionCanReopen(p.classId)) { "ec.error.class_already_selected" }
                menu(player)
            }
            "guide" -> wayfinding.showGuide(player, p, spawnTrail = true)
            "codex" -> openCodex(player)
            "ui" -> {
                require(args.getOrNull(1) == "pack") { "ec.resource_pack.usage" }
                if(resourcePacks.isExternallyManaged()) {
                    player.sendMessage(message(player,"ec.resource_pack.external_managed"))
                    return
                }
                val preference = when (args.getOrNull(2)?.lowercase()) {
                    "auto" -> PackPreference.AUTO
                    "on" -> PackPreference.ON
                    "off" -> PackPreference.OFF
                    else -> error("ec.resource_pack.usage")
                }
                p.resourcePackPreference = preference.name
                save(p)
                resourcePacks.send(player, preference)
                val labelKey = when (preference) {
                    PackPreference.AUTO -> "ec.resource_pack.preference_auto"
                    PackPreference.ON -> "ec.resource_pack.preference_on"
                    PackPreference.OFF -> "ec.resource_pack.preference_off"
                }
                player.sendMessage(message(player,"ec.resource_pack.preference_set", "preference" to message(player,labelKey)))
            }
            "enhance" -> openEnhancement(player)
            "fish" -> {
                if(args.getOrNull(1)=="bait") {
                    val value=args.getOrNull(2) ?: error("ec.error.usage: /ec fish bait <item_id|auto|none>")
                    val selected=fishing.selectBait(p,value); save(p)
                    val response=when(selected) {
                        "AUTO" -> message(player,"ec.fishing.bait_enabled_auto")
                        "NONE" -> message(player,"ec.fishing.bait_disabled")
                        else -> message(player,"ec.fishing.bait_selected","name" to contentName(player,selected))
                    }
                    player.sendMessage(response)
                } else {
                    val life=p.lifeSkills.getValue(LifeSkillKind.FISHING); val required=LifeSkillEngine(snapshot.world.lifeBalance).requiredXp(life.level)
                    val bait=when(p.fishingBait){"AUTO"->message(player,"ec.fishing.bait_auto");"NONE"->message(player,"ec.fishing.bait_none");else->Component.text(contentName(player,p.fishingBait))}
                    player.sendMessage(message(player,"ec.life.fishing_status", "level" to life.level, "xp" to life.xp, "required" to required, "score" to FishingEngine(snapshot.world.lifeBalance).score(p), "found" to p.fishRecords.values.count { it.count>0 }, "total" to snapshot.world.fishSpecies.size, "bait" to bait))
                    player.sendMessage(message(player,"ec.life.fishing_bait_help"))
                }
            }
            "gather" -> {
                val life=p.lifeSkills.getValue(LifeSkillKind.GATHERING); val required=LifeSkillEngine(snapshot.world.lifeBalance).requiredXp(life.level)
                player.sendMessage(message(player,"ec.life.gathering_status", "level" to life.level, "xp" to life.xp, "required" to required, "count" to p.gatheringRecords.values.sum(), "found" to p.gatheringRecords.count { it.value>0 }, "total" to snapshot.world.gatheringNodes.size))
            }
            "title" -> {
                syncTitleAdvancements(player,p)
                val id=args.getOrNull(1)
                if(id == null) {
            snapshot.world.titles.values.forEach { title -> player.sendMessage(message(player,"ec.title.list_entry", "status" to if(title.unlockFlag in p.flags) "✓" else "○", "name" to contentText(player,"title",title.id,"name",title.name), "equipped" to if(title.id == p.selectedTitle) message(player,"ec.title.equipped_mark") else Component.empty())) }
                    player.sendMessage(message(player,"ec.title.help"))
                } else {
                    val selected=if(id == "none") null else id
                    require(p.equipTitle(selected,snapshot.world.titles)) { "ec.title.locked" }
                    save(p); applyTitle(player,p)
                    player.sendMessage(message(player,if(selected == null) "ec.title.cleared" else "ec.title.equipped", "name" to selected?.let { snapshot.world.titles.getValue(it).let { t -> contentText(player,"title",t.id,"name",t.name) } }))
                }
            }
            "claim" -> adventure.claimPending(player)
            "equipment" -> adventure.equipment(player)
            "dungeon" -> when(args.getOrNull(1)) {
                "leave" -> dungeons.leave(player)
                "records" -> if(p.challengeRecords.isEmpty()) player.sendMessage(message(player,"ec.dungeon.no_records")) else p.challengeRecords.forEach { (id,record) -> player.sendMessage(message(player,"ec.dungeon.record", "name" to contentName(player,id), "seconds" to record.bestSeconds, "rank" to record.bestRank)) }
                "enter" -> dungeons.enter(player,args.getOrNull(2) ?: "ec:dungeon_void_sanctum")
                null -> dungeons.enter(player)
                else -> dungeons.enter(player,args[1])
            }
            "shop" -> {
                val id=args.getOrNull(1) ?: error("ec.error.usage: /ec shop <id>")
                shopAccessError(player, id)?.let { throw GameException(it) }
                adventure.showShop(player,id)
            }
            "quest" -> when(args.getOrNull(1)) {
                "start" -> { require(player.hasPermission("etherealchronicle.admin")) { "ec.error.quest_npc_start" }; game.startQuest(p,args.getOrNull(2) ?: error("ec.error.usage: /ec quest start <id>")); save(p); player.sendMessage(message(player,"ec.quest.accepted")) }
                "complete" -> { require(player.hasPermission("etherealchronicle.admin")) { "ec.error.quest_npc_complete" }; adventure.transaction(player,{ inv -> game.completeQuest(p,args.getOrNull(2) ?: error("ec.error.usage: /ec quest complete <id>"),inv) }) { player.sendMessage(message(player,"ec.quest.completed")) } }
                else -> {
                    if(p.quests.isEmpty()) player.sendMessage(message(player,"ec.quest.none_active"))
                    p.quests.forEach { (id,q) ->
                        val definition=snapshot.world.quests[id]
                        // 依頼名はYAMLの表示データ、進行状態はLang文言として分け、1行での言語混在を防ぎます。
                        player.sendMessage(message(player,"ec.quest.name", "name" to (definition?.let { contentText(player,"quest",id,"name",it.name) } ?: plain(message(player,"ec.quest.unknown_name")))))
                        player.sendMessage(message(player,"ec.quest.status", "status" to message(player,when { q.completed -> "ec.quest.status_complete"; game.quests.ready(p,id,inventory(player)) -> "ec.quest.status_ready"; else -> "ec.quest.status_active" })))
                        if(!q.completed) definition?.objectives?.forEach { o -> player.sendMessage(message(player,"ec.quest.objective_progress", "target" to wayfinding.formatObjective(player, o.kind, questObjectiveLabel(o.target,snapshot,plain(message(player,"ec.quest.unknown_target"))) { type,targetId,ja -> contentText(player,type,targetId,"name",ja) }), "progress" to (if(o.kind == ObjectiveKind.COLLECT) inventory(player).count(o.target).coerceAtMost(o.amount) else q.objectives.getOrDefault(o.id,0)), "amount" to o.amount)) }
                    }
                }
            }
            "party" -> when(args.getOrNull(1)) {
                "invite" -> {
                    val target=args.getOrNull(2)?.let(server::getPlayerExact) ?: error("ec.error.party_member_missing")
                    adventure.parties.invite(p.uuid,target.uniqueId)
                    target.sendMessage(message(target,"ec.party.invite_received", "name" to player.name)); player.sendMessage(message(player,"ec.party.invite_sent", "name" to target.name))
                }
                "accept" -> { adventure.parties.accept(p.uuid); player.sendMessage(message(player,"ec.party.joined")) }
                "leave" -> { dungeons.leave(player); adventure.parties.leave(p.uuid); player.sendMessage(message(player,"ec.party.left")) }
                else -> player.sendMessage(message(player,"ec.party.members", "members" to adventure.parties.members(p.uuid).joinToString { Bukkit.getPlayer(it)?.name ?: plain(message(player,"ec.party.offline")) }))
            }
        }
    }
    private fun debug(sender: CommandSender, args: List<String>) {
        if(args.firstOrNull()=="fish") {
            val action=args.getOrNull(1) ?: error("ec.error.usage: /ec debug fish <start|status|cancel> <player> [species-id]")
            val playerName=args.getOrNull(2) ?: error("ec.error.usage: /ec debug fish $action <player> [species-id]")
            val player=server.getPlayerExact(playerName) ?: error("ec.error.player_missing: $playerName")
            val message=when(action) {
                "start" -> fishing.debugStart(player,args.getOrNull(3))
                "status" -> fishing.debugStatus(player.uniqueId)
                "cancel" -> plain(message(player,if(fishing.debugCancel(player)) "ec.fishing.debug_cancel" else "ec.fishing.no_session"))
                else -> error("ec.error.usage: /ec debug fish <start|status|cancel> <player> [species-id]")
            }
            sender.sendMessage(message(sender,"ec.debug.fish_prefix","name" to player.name,"detail" to message)); return
        }
        if(args.firstOrNull()=="gather") {
            require(args.getOrNull(1)=="status") { "ec.error.usage: /ec debug gather status <player>" }
            val playerName=args.getOrNull(2) ?: error("ec.error.usage: /ec debug gather status <player>")
            val player=server.getPlayerExact(playerName) ?: error("ec.error.player_missing: $playerName")
            sender.sendMessage(message(sender,"ec.debug.gather_prefix","name" to player.name,"detail" to gathering.debugStatus(player))); return
        }
        simulationCommands.run(sender, args)
    }
    companion object {
        internal val RANGED_BASIC_WEAPON_MATERIALS = setOf(
            Material.BOW,
            Material.CROSSBOW,
            Material.BLAZE_ROD,
            Material.BREEZE_ROD,
            Material.STICK,
        )
    }
}

