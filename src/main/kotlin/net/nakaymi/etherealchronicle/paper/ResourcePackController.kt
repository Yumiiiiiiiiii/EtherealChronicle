package net.nakaymi.etherealchronicle.paper

import net.kyori.adventure.resource.ResourcePackInfo
import net.kyori.adventure.resource.ResourcePackRequest
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerResourcePackStatusEvent
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** 同梱リソースパックを起動・配信し、クライアントの適用状態をUUID単位で保持します。 */
class ResourcePackController(private val plugin: RpgPlugin) : Listener, AutoCloseable {
    private val settings = ResourcePackSettings.loadSafely(plugin.config) { error ->
        plugin.logger.warning("Invalid resource-pack settings; delivery and required mode are disabled: ${error.message}")
    }
    private val enabled = settings.enabled
    private val required = settings.required
    private val requiredTimeoutSeconds = settings.requiredTimeoutSeconds
    private val debug = settings.debug
    private val mode = settings.mode
    private val externalManaged = settings.mode == "external_managed"
    private val expectedPackIdText = settings.expectedPackIdText
    private val expectedPackId = settings.expectedPackId
    private val invalidExpectedPackId = settings.invalidExpectedPackId
    private val gamePortEnabled = settings.gamePortEnabled
    private val bindHost = settings.bindHost
    private val configuredHost = settings.publicHost
    private val port = settings.port
    private val maxConcurrent = settings.maxConcurrent
    private val maxPerIp = settings.maxPerIp
    private val idleReadTimeoutMillis = settings.idleReadTimeoutMillis
    private val headerDeadlineMillis = settings.headerDeadlineMillis
    private val writeDeadlineMillis = settings.writeDeadlineMillis
    private val externalUrl = settings.externalUrl
    private val externalSha1 = settings.externalSha1
    private val gamePortMaxConnections = settings.gamePortMaxConnections
    private val gamePortMaxPerIp = settings.gamePortMaxPerIp
    private val gamePortMaxHeaderBytes = settings.gamePortMaxHeaderBytes
    private val gamePortHeaderDeadlineMillis = settings.gamePortHeaderDeadlineMillis
    private val gamePortIdleTimeoutMillis = settings.gamePortIdleTimeoutMillis
    private val gamePortBandwidthKbPerSecond = settings.gamePortBandwidthKbPerSecond
    private val preferences = mutableMapOf<UUID, PackPreference>()
    private val loadedPlayers = mutableSetOf<UUID>()
    /** 必須設定で個人OFFを上書きして適用したプレイヤーを区別します。 */
    private val enforcedLoadedPlayers = mutableSetOf<UUID>()
    private val requestId = UUID.nameUUIDFromBytes("etherealchronicle-resource-pack-v1".toByteArray())
    private data class PendingRequest(val id: UUID, val required: Boolean)
    private val pendingRequests = mutableMapOf<UUID, PendingRequest>()
    private val observedManagedStatuses = mutableMapOf<UUID, String>()
    /** 接続中の必須パック適用処理を一つのセッションとして管理します。 */
    private class JoinState(
        var dispatchTask: BukkitTask? = null,
        var timeoutTask: BukkitTask? = null,
        val waiters: MutableList<() -> Unit> = mutableListOf(),
        var extensions: Int = 0,
        var lastStatus: PlayerResourcePackStatusEvent.Status? = null,
    )
    private val joins = mutableMapOf<UUID, JoinState>()
    private val artifact: ResourcePackArtifact? = if (enabled && !externalManaged) loadArtifact() else null
    private val configuredEndpoint: ResourcePackEndpoint? = if (externalManaged) null else artifact?.let {
        runCatching {
            require(externalSha1.isBlank() || externalSha1.matches(Regex("[a-f0-9]{40}")))
            if (mode == "game_port" && !gamePortEnabled) {
                plugin.logger.warning("Game-port resource-pack mode is opt-in; enable resource_pack.same_port_netty.enabled. Falling back to HTTPS external_url when configured.")
                if (externalUrl.isNotBlank()) createEndpoint("external_url") else null
            } else createEndpoint(mode).also { endpoint ->
                if (mode != "external_url") {
                    val effectiveBindHost = if (mode == "game_port") plugin.server.ip.ifBlank { "0.0.0.0" } else endpoint.bindHost
                    require(ResourcePackEndpointPolicy.canAdvertise(effectiveBindHost, endpoint.publicHost)) {
                        "Resource pack public_host resolves to loopback while bind_host is non-loopback; configure public_host."
                    }
                }
            }
        }.onFailure { plugin.logger.warning("Resource pack distribution is disabled by invalid configuration: ${it.message}") }
            .getOrNull()
    }
    private val endpointFallback = if (configuredEndpoint?.mode == "game_port" && externalUrl.isNotBlank()) {
        runCatching { createEndpoint("external_url") }
            .onFailure { plugin.logger.warning("Could not configure HTTPS pack fallback: ${it.message}") }
            .getOrNull()
    } else null
    private val gamePortFailed = AtomicBoolean(false)
    private val requiredWarningLogged = AtomicBoolean(false)
    private val httpServer = if (configuredEndpoint?.mode == "embedded_http") runCatching {
        EmbeddedPackServer(requireNotNull(configuredEndpoint), requireNotNull(artifact), maxConcurrent, maxPerIp,
            idleReadTimeoutMillis, headerDeadlineMillis, writeDeadlineMillis)
    }.onFailure { plugin.logger.warning("Resource pack HTTP service failed to start: ${it.message}") }.getOrNull() else null
    private val gamePortServer = if (enabled && configuredEndpoint?.mode == "game_port" && gamePortEnabled) runCatching {
        GamePortPackServer(
            plugin,
            requireNotNull(configuredEndpoint),
            requireNotNull(artifact),
            gamePortMaxConnections,
            gamePortMaxPerIp,
            gamePortMaxHeaderBytes,
            gamePortHeaderDeadlineMillis,
            gamePortIdleTimeoutMillis,
            gamePortBandwidthKbPerSecond,
            onFailure = { gamePortFailed.set(true) },
        )
    }.onFailure {
        plugin.logger.warning(
            "Game-port pack interception was disabled without changing game networking: ${it.message}. " +
                if (externalUrl.isNotBlank()) "Using external_url fallback." else "No pack endpoint will be advertised.",
        )
    }.getOrNull() else null
    private val endpoint = configuredEndpoint?.takeIf {
        (it.mode != "embedded_http" || httpServer != null) && (it.mode != "game_port" || gamePortServer != null)
    } ?: endpointFallback
    private val bundledInfo: ResourcePackBuildInfo? by lazy {
        artifact?.let { ResourcePackBuildInfo(it.sha1, it.bytes.size.toLong()) } ?: runCatching {
            val bytes = requireNotNull(plugin.getResource("resourcepack/etherealchronicle-resourcepack.zip")).use { it.readBytes() }
            val expected = requireNotNull(plugin.getResource("resourcepack/etherealchronicle-resourcepack.sha1"))
                .bufferedReader().use { it.readLine().trim() }
            ResourcePackBuildInfo(expected, bytes.size.toLong())
        }.getOrNull()
    }

    init {
        if (externalManaged || enabled && artifact != null) plugin.server.pluginManager.registerEvents(this, plugin)
        if (externalManaged && invalidExpectedPackId) plugin.logger.warning("resource_pack.expected_pack_id is not a UUID; external pack status events will be ignored.")
        if (required && !externalManaged && !isDeliveryAvailable()) warnRequiredUnavailable()
    }

    /** 必須ゲートのイベントリスナーを登録する必要がある設定か返します。 */
    fun shouldRegisterLockListener(): Boolean = RequiredPackGatePolicy.shouldRegisterLockListener(
        required, isDeliveryAvailable(), mode,
    )

    /** プレイヤー設定と配信可能状態を確認し、Adventure ResourcePackRequestを送信します。 */
    fun send(player: org.bukkit.entity.Player, preference: PackPreference = PackPreference.AUTO) {
        // 外部管理モードでは別プラグインやserver.propertiesが唯一の送信元です。
        if (!ManagedPackPolicy.sendsPack(mode)) return
        preferences[player.uniqueId] = preference
        // 接続後の次tickに必須要求を出すまでは、プロフィール読込側からの早すぎる再送を止めます。
        if (joins[player.uniqueId]?.dispatchTask != null) return
        val pendingRequired = pendingRequests[player.uniqueId]?.required == true
        val deliveryEndpoint = if (gamePortFailed.get()) endpointFallback else endpoint
        val bypass = player.hasPermission("etherealchronicle.pack.bypass")
        val deliveryAvailable = isDeliveryAvailable()
        if (required && !deliveryAvailable) warnRequiredUnavailable()
        val decision = ResourcePackPolicy.decide(required, deliveryAvailable, preference, bypass)
        if (!decision.send || deliveryEndpoint == null || artifact == null) {
            pendingRequests.remove(player.uniqueId)
            val wasLoaded = loadedPlayers.remove(player.uniqueId)
            enforcedLoadedPlayers.remove(player.uniqueId)
            if (shouldRemoveAppliedPack(wasLoaded, decision.send)) runCatching { player.removeResourcePacks(requestId) }
            plugin.refreshPackItemModels(player, false)
            if (isAwaitingRequiredPack(player.uniqueId)) releaseRequiredWait(player.uniqueId, continueJoin = true)
            return
        }
        if (pendingRequired) return
        if (player.uniqueId in loadedPlayers) {
            if (isAwaitingRequiredPack(player.uniqueId)) releaseRequiredWait(player.uniqueId, continueJoin = true)
            return
        }
        val hash = advertisedPackSha1(externalSha1,artifact.sha1)
        val info = ResourcePackInfo.resourcePackInfo(requestId, deliveryEndpoint.url(hash), hash)
        val promptKey = if (decision.required) "ec.resource_pack.required_prompt" else "ec.resource_pack.prompt"
        val prompt = plugin.message(player, promptKey)
        pendingRequests[player.uniqueId] = PendingRequest(requestId,decision.required)
        // 再接続時は毎回要求し、同一IDに対して変わったSHA-1を送ってキャッシュの更新判定を促します。
        try {
            player.sendResourcePacks(ResourcePackRequest.resourcePackRequest().packs(info).required(decision.required).prompt(prompt).replace(false).build())
            val currentBypass = player.hasPermission("etherealchronicle.pack.bypass")
            debugLog { "request sent player=${player.uniqueId} id=$requestId required=${decision.required} mode=$mode bypass=$currentBypass sha1=$hash" }
            if (decision.required && isAwaitingRequiredPack(player.uniqueId)) scheduleRequiredTimeout(player)
            else if (isAwaitingRequiredPack(player.uniqueId)) releaseRequiredWait(player.uniqueId, continueJoin = true)
        } catch (error: Exception) {
            pendingRequests.remove(player.uniqueId)
            plugin.logger.warning("Resource pack request failed for ${player.uniqueId}: ${error.message}")
            val mustDisconnect = decision.required &&
                !player.hasPermission("etherealchronicle.pack.bypass") &&
                isDeliveryAvailable()
            val waiting = isAwaitingRequiredPack(player.uniqueId)
            releaseRequiredWait(player.uniqueId, continueJoin = !mustDisconnect)
            if (mustDisconnect && waiting && player.isOnline) {
                player.kick(plugin.message(player, "ec.resource_pack.required_apply_failed"))
            }
        }
    }

    /** 接続直後に操作を保留し、次tickで必須要求を送り適用結果を待ちます。 */
    fun beginRequiredJoin(player: org.bukkit.entity.Player): Boolean {
        val bypass = player.hasPermission("etherealchronicle.pack.bypass")
        val deliveryAvailable = isDeliveryAvailable()
        debugLog { "join eligibility player=${player.uniqueId} required=$required deliveryAvailable=$deliveryAvailable mode=$mode bypass=$bypass" }
        if (!RequiredPackGatePolicy.shouldWait(required, deliveryAvailable, mode, bypass)) return false
        val playerId = player.uniqueId
        if (playerId in joins) return true
        val state = JoinState()
        joins[playerId] = state
        // 送信前の例外でも待機が永久化しないよう、ゲート開始時点で期限を設定します。
        scheduleRequiredTimeout(player)
        state.dispatchTask = plugin.server.scheduler.runTaskLater(plugin, Runnable {
            if (joins[playerId] !== state || !isAwaitingRequiredPack(playerId)) return@Runnable
            state.dispatchTask = null
            if (!player.isOnline) {
                releaseRequiredWait(playerId, continueJoin = false)
                return@Runnable
            }
            debugLog { "dispatching required request after join tick player=$playerId" }
            try {
                send(player, preferences[playerId] ?: PackPreference.AUTO)
            } catch (error: Exception) {
                debugLog { "required request dispatch failed player=$playerId reason=${error.message}" }
                pendingRequests.remove(playerId)
                val mustDisconnect = !player.hasPermission("etherealchronicle.pack.bypass") &&
                    required && isDeliveryAvailable()
                releaseRequiredWait(playerId, continueJoin = !mustDisconnect)
                if (mustDisconnect && player.isOnline) {
                    player.kick(plugin.message(player, "ec.resource_pack.required_apply_failed"))
                }
            }
        }, 1L)
        debugLog { "required pack gate started player=$playerId mode=$mode timeout=${requiredTimeoutSeconds}s" }
        player.sendMessage(plugin.message(player, "ec.resource_pack.waiting"))
        return true
    }

    /** 必須パック適用後にプロフィール反映や初期メニューを続行します。 */
    fun whenRequiredPackReady(player: org.bukkit.entity.Player, continuation: () -> Unit) {
        val state = joins[player.uniqueId]
        if (state == null) {
            continuation()
            return
        }
        state.waiters += continuation
    }

    /** 必須パック未適用でゲーム操作を許可しないプレイヤーか返します。 */
    fun isAwaitingRequiredPack(playerId: UUID): Boolean = playerId in joins

    /** 実イベント結合テストで要求送信待ちと状態応答を区別します。 */
    internal fun hasPendingRequest(playerId: UUID): Boolean = playerId in pendingRequests

    /** 実参加フローのプロフィール反映継続がゲートへ登録済みか確認します。 */
    internal fun hasJoinContinuation(playerId: UUID): Boolean = joins[playerId]?.waiters?.isNotEmpty() == true

    /** 待機中プレイヤーがいない高頻度イベントを O(1) で短絡します。 */
    fun hasWaiters(): Boolean = joins.isNotEmpty()

    private fun scheduleRequiredTimeout(player: org.bukkit.entity.Player) {
        val playerId = player.uniqueId
        val state = joins[playerId] ?: return
        state.timeoutTask?.cancel()
        val ticks = RequiredPackGatePolicy.timeoutTicks(requiredTimeoutSeconds)
        state.timeoutTask = plugin.server.scheduler.runTaskLater(plugin, Runnable {
            if (joins[playerId] !== state) return@Runnable
            state.timeoutTask = null
            if (player.hasPermission("etherealchronicle.pack.bypass")) {
                debugLog { "required timeout bypassed after permission update player=$playerId" }
                releaseRequiredWait(playerId, continueJoin = true)
                return@Runnable
            }
            if (!isAwaitingRequiredPack(playerId)) return@Runnable
            if (state.extensions < RequiredPackGatePolicy.MAX_TIMEOUT_EXTENSIONS &&
                state.lastStatus in RequiredPackGatePolicy.progressStatuses
            ) {
                state.extensions++
                debugLog { "required timeout extended player=$playerId extension=${state.extensions}" }
                scheduleRequiredTimeout(player)
                return@Runnable
            }
            debugLog { "required request timed out player=$playerId after ${requiredTimeoutSeconds}s" }
            releaseRequiredWait(playerId, continueJoin = false)
            if (player.isOnline) player.kick(plugin.message(player, "ec.resource_pack.required_timeout"))
        }, ticks)
    }

    private fun releaseRequiredWait(playerId: UUID, continueJoin: Boolean) {
        val state = joins.remove(playerId) ?: return
        state.dispatchTask?.cancel()
        state.timeoutTask?.cancel()
        val callbacks = state.waiters.toList()
        state.waiters.clear()
        if (continueJoin) callbacks.forEach { it() }
    }

    /** 状態が成功適用で、かつ利用者設定がOFFでない場合だけリソパ表現を許可します。 */
    fun hasResourcePack(playerId: UUID): Boolean = playerId in loadedPlayers &&
        (externalManaged || preferences[playerId] != PackPreference.OFF || playerId in enforcedLoadedPlayers)

    /** ログイン直後に届いた状態イベントを、プロフィール読込後に反映します。 */
    fun applyObservedStatus(player: org.bukkit.entity.Player, profile: net.nakaymi.etherealchronicle.core.PlayerProfile) {
        if (!externalManaged) return
        observedManagedStatuses.remove(player.uniqueId)?.let { applyManagedStatus(player, profile, it) }
    }

    /** 管理者へ、JARに格納された生成済みパックのハッシュとサイズを返します。 */
    internal fun buildInfo(): ResourcePackBuildInfo? {
        return bundledInfo
    }

    /** 配布要求を送らず状態だけ監視する設定か返します。 */
    fun isExternallyManaged(): Boolean = externalManaged

    @EventHandler fun onStatus(event: PlayerResourcePackStatusEvent) {
        if (externalManaged) {
            handleManagedStatus(event)
            return
        }
        val request = pendingRequests[event.player.uniqueId]?.takeIf { it.id == event.id } ?: return
        debugLog { "request status player=${event.player.uniqueId} id=${event.id} status=${event.status}" }
        val action = RequiredPackGatePolicy.statusAction(
            event.status,
            request.required,
            event.player.hasPermission("etherealchronicle.pack.bypass"),
            isAwaitingRequiredPack(event.player.uniqueId),
        )
        if (action == RequiredPackStatusAction.EXTEND) {
            joins[event.player.uniqueId]?.lastStatus = event.status
            scheduleRequiredTimeout(event.player)
            return
        }
        if (action == RequiredPackStatusAction.COMPLETE &&
            event.status != PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED &&
            event.status !in RequiredPackGatePolicy.terminalStatuses
        ) {
            releaseRequiredWait(event.player.uniqueId, continueJoin = true)
            return
        }
        if (event.status == PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED) {
            joins[event.player.uniqueId]?.lastStatus = event.status
            pendingRequests.remove(event.player.uniqueId)
            loadedPlayers += event.player.uniqueId
            if (request.required) enforcedLoadedPlayers += event.player.uniqueId
            else enforcedLoadedPlayers -= event.player.uniqueId
            debugLog { "request loaded player=${event.player.uniqueId} id=${event.id}" }
            plugin.refreshPackItemModels(event.player, true)
            releaseRequiredWait(event.player.uniqueId, continueJoin = true)
        } else if (event.status in RequiredPackGatePolicy.terminalStatuses) {
            joins[event.player.uniqueId]?.lastStatus = event.status
            pendingRequests.remove(event.player.uniqueId)
            loadedPlayers -= event.player.uniqueId
            enforcedLoadedPlayers -= event.player.uniqueId
            debugLog { "request failed player=${event.player.uniqueId} id=${event.id} status=${event.status}" }
            plugin.refreshPackItemModels(event.player, false)
            if (required && !isDeliveryAvailable()) warnRequiredUnavailable()
            val mustDisconnect = action == RequiredPackStatusAction.DISCONNECT &&
                ManagedPackPolicy.disconnectsOnFailure(mode, request.required && isDeliveryAvailable(), event.status in RequiredPackGatePolicy.terminalStatuses)
            releaseRequiredWait(event.player.uniqueId, continueJoin = !mustDisconnect)
            if (mustDisconnect && event.player.isOnline) {
                event.player.kick(plugin.message(event.player, requiredFailureMessageKey(event.status)))
            }
        }
    }

    @EventHandler fun onQuit(event: PlayerQuitEvent) {
        loadedPlayers -= event.player.uniqueId
        enforcedLoadedPlayers -= event.player.uniqueId
        preferences -= event.player.uniqueId
        pendingRequests -= event.player.uniqueId
        observedManagedStatuses -= event.player.uniqueId
        releaseRequiredWait(event.player.uniqueId, continueJoin = false)
    }

    override fun close() {
        gamePortServer?.close()
        httpServer?.close()
        loadedPlayers.clear()
        enforcedLoadedPlayers.clear()
        preferences.clear()
        pendingRequests.clear()
        observedManagedStatuses.clear()
        joins.keys.toList().forEach { releaseRequiredWait(it, continueJoin = false) }
    }

    private fun loadArtifact(): ResourcePackArtifact? = runCatching {
        val bytes = requireNotNull(plugin.getResource("resourcepack/etherealchronicle-resourcepack.zip")) {
            "Generated resource pack is missing from the plugin jar."
        }.use { it.readBytes() }
        val expected = requireNotNull(plugin.getResource("resourcepack/etherealchronicle-resourcepack.sha1"))
            .bufferedReader().use { it.readLine().trim() }
        ResourcePackArtifact(bytes, expected)
    }.onFailure { plugin.logger.severe("Could not load bundled resource pack: ${it.message}") }.getOrNull()

    private fun createEndpoint(modeOverride: String = mode): ResourcePackEndpoint = ResourcePackEndpoint(
        bindHost = bindHost,
        publicHost = configuredHost.ifBlank { plugin.server.ip.takeIf(String::isNotBlank) ?: "127.0.0.1" },
        port = if (modeOverride == "game_port") plugin.server.port else port,
        mode = modeOverride,
        externalUrl = externalUrl,
    )

    private fun isDeliveryAvailable(): Boolean {
        val deliveryEndpoint = if (gamePortFailed.get()) endpointFallback else endpoint
        return ResourcePackPolicy.deliveryAvailable(enabled,artifact != null,deliveryEndpoint?.mode,externalSha1)
    }

    /** 他の配信元の結果を記録し、適用成功したクライアントだけにリソパUIを使います。 */
    private fun handleManagedStatus(event: PlayerResourcePackStatusEvent) {
        if (invalidExpectedPackId || !ManagedPackPolicy.matches(expectedPackId, event.id)) return
        val status = event.status.name
        val profile = plugin.profiles[event.player.uniqueId]
        if (profile == null) {
            observedManagedStatuses[event.player.uniqueId] = status
            return
        }
        applyManagedStatus(event.player, profile, status)
    }

    private fun applyManagedStatus(
        player: org.bukkit.entity.Player,
        profile: net.nakaymi.etherealchronicle.core.PlayerProfile,
        status: String,
    ) {
        if (ManagedPackPolicy.recordStatus(profile, status)) plugin.save(profile)
        when (ManagedPackPolicy.visualTransition(status)) {
            PackVisualTransition.LOADED -> {
                loadedPlayers += player.uniqueId
                plugin.refreshPackItemModels(player, true)
            }
            PackVisualTransition.UNLOADED -> {
                loadedPlayers -= player.uniqueId
                plugin.refreshPackItemModels(player, false)
            }
            PackVisualTransition.KEEP -> Unit
        }
    }

    /** 配信設定が壊れている時に一度だけ翻訳済みの安全解除警告を記録します。 */
    private fun warnRequiredUnavailable() {
        if(requiredWarningLogged.compareAndSet(false,true)) {
            plugin.logger.warning(plain(plugin.message(null,"ec.resource_pack.required_unavailable")))
        }
    }

    private fun plain(component: net.kyori.adventure.text.Component): String = component.plainText()

    private inline fun debugLog(message: () -> String) {
        if (debug) plugin.logger.info("Resource pack debug: ${message()}")
    }

}

/** ビルド済みZIPの識別情報を管理者コマンドへ渡します。 */
internal data class ResourcePackBuildInfo(val sha1: String, val sizeBytes: Long)

/** OFF変更時は、すでに適用済みの同じ要求だけをクライアントから除去します。 */
internal fun shouldRemoveAppliedPack(wasLoaded: Boolean, shouldSend: Boolean): Boolean = wasLoaded && !shouldSend

/** 外部配信のイベント監視で、送信・ID照合・適用状態の規則を集約します。 */
internal object ManagedPackPolicy {
    fun sendsPack(mode: String): Boolean = mode != "external_managed"
    fun matches(expectedId: UUID?, eventId: UUID): Boolean = expectedId == null || expectedId == eventId
    fun isLoaded(status: String): Boolean = status == "SUCCESSFULLY_LOADED"
    fun visualTransition(status: String): PackVisualTransition = when (status) {
        "SUCCESSFULLY_LOADED" -> PackVisualTransition.LOADED
        "DECLINED", "FAILED_DOWNLOAD", "INVALID_URL", "FAILED_RELOAD", "DISCARDED" -> PackVisualTransition.UNLOADED
        else -> PackVisualTransition.KEEP
    }
    fun disconnectsOnFailure(mode: String, requestRequired: Boolean, terminalFailure: Boolean): Boolean =
        mode != "external_managed" && ResourcePackPolicy.disconnectOnFailure(requestRequired, terminalFailure)

    /** 最後に観測した同意/拒否状態をプロフィールへ記録し、変更時だけ保存を促します。 */
    fun recordStatus(profile: net.nakaymi.etherealchronicle.core.PlayerProfile, status: String): Boolean {
        require(status.matches(Regex("[A-Z_]{1,48}")))
        if (profile.resourcePackStatus == status) return false
        profile.resourcePackStatus = status
        return true
    }
}

/** 外部パックの中間通知は表示状態を維持し、成功/終端失敗だけを反映します。 */
internal enum class PackVisualTransition { LOADED, UNLOADED, KEEP }

/** 必須パックの終了状態に応じて利用者へ原因別の案内を返します。 */
internal fun requiredFailureMessageKey(status: PlayerResourcePackStatusEvent.Status): String = when (status) {
    PlayerResourcePackStatusEvent.Status.DECLINED -> "ec.resource_pack.required_declined"
    PlayerResourcePackStatusEvent.Status.FAILED_DOWNLOAD,
    PlayerResourcePackStatusEvent.Status.INVALID_URL -> "ec.resource_pack.required_download_failed"
    else -> "ec.resource_pack.required_apply_failed"
}

/** 必須パック待機の開始条件とタイムアウトを安全な範囲へ正規化します。 */
internal object RequiredPackGatePolicy {
    const val MAX_TIMEOUT_EXTENSIONS = 3
    const val MIN_TIMEOUT_SECONDS = 10
    const val MAX_TIMEOUT_SECONDS = 600
    val progressStatuses = setOf(
        PlayerResourcePackStatusEvent.Status.ACCEPTED,
        PlayerResourcePackStatusEvent.Status.DOWNLOADED,
    )
    val terminalStatuses = setOf(
        PlayerResourcePackStatusEvent.Status.DECLINED,
        PlayerResourcePackStatusEvent.Status.FAILED_DOWNLOAD,
        PlayerResourcePackStatusEvent.Status.INVALID_URL,
        PlayerResourcePackStatusEvent.Status.FAILED_RELOAD,
        PlayerResourcePackStatusEvent.Status.DISCARDED,
    )

    /** 必須ゲートに対する各クライアント状態イベントの処理を決めます。 */
    fun statusAction(
        status: PlayerResourcePackStatusEvent.Status,
        requestRequired: Boolean,
        bypass: Boolean,
        waiting: Boolean,
    ): RequiredPackStatusAction = when {
        status == PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED -> RequiredPackStatusAction.COMPLETE
        waiting && bypass -> RequiredPackStatusAction.COMPLETE
        waiting && requestRequired && status in progressStatuses -> RequiredPackStatusAction.EXTEND
        waiting && requestRequired && status in terminalStatuses -> RequiredPackStatusAction.DISCONNECT
        waiting && status in terminalStatuses -> RequiredPackStatusAction.COMPLETE
        else -> RequiredPackStatusAction.IGNORE
    }

    /** 待機ロック用リスナーが必要な配信構成か判定します。 */
    fun shouldRegisterLockListener(required: Boolean, deliveryAvailable: Boolean, mode: String): Boolean =
        required && deliveryAvailable && mode != "external_managed"

    fun shouldWait(required: Boolean, deliveryAvailable: Boolean, mode: String, bypass: Boolean): Boolean =
        required && deliveryAvailable && ManagedPackPolicy.sendsPack(mode) && !bypass

    fun timeoutTicks(seconds: Int): Long =
        MinecraftTicks.secondsToTicks(seconds.coerceIn(MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS).toLong())
}

/** パック状態イベントをゲートへ適用する純粋な処理分類です。 */
internal enum class RequiredPackStatusAction { COMPLETE, EXTEND, DISCONNECT, IGNORE }

/** 配信不能時は必須指定を弱め、運用設定で利用者全員を締め出さない判定です。 */
internal data class ResourcePackDecision(val send: Boolean, val required: Boolean) {
    val disconnectOnFailure: Boolean get() = required
}

/** 必須設定・配信可否・個人設定・bypass権限から要求内容を純粋に決定します。 */
internal object ResourcePackPolicy {
    /** 外部URLではSHA-1が不足すると配布を利用可能とみなしません。 */
    fun deliveryAvailable(enabled: Boolean, artifactAvailable: Boolean, endpointMode: String?, sha1: String): Boolean =
        enabled && artifactAvailable && endpointMode != null &&
            (endpointMode != "external_url" || sha1.matches(Regex("[a-f0-9]{40}")))

    fun decide(configuredRequired: Boolean, deliveryAvailable: Boolean, preference: PackPreference, bypass: Boolean): ResourcePackDecision {
        val enforce = configuredRequired && deliveryAvailable && !bypass
        return ResourcePackDecision(
            send = deliveryAvailable && (preference != PackPreference.OFF || enforce),
            required = enforce,
        )
    }

    /** 必須要求が拒否・失敗した時だけ標準の切断案内を表示します。 */
    fun disconnectOnFailure(requestRequired: Boolean, terminalFailure: Boolean): Boolean = requestRequired && terminalFailure
}

/** 外部SHA-1が空の場合はJAR内に同梱したZIPの検証済みハッシュを使います。 */
internal fun advertisedPackSha1(externalSha1: String, bundledSha1: String): String = externalSha1.ifBlank { bundledSha1 }

/** プレイヤー単位の自動・強制ON・強制OFFをプロファイルへ保存します。 */
enum class PackPreference { AUTO, ON, OFF }
