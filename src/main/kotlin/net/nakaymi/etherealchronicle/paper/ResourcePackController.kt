package net.nakaymi.etherealchronicle.paper

import net.kyori.adventure.resource.ResourcePackInfo
import net.kyori.adventure.resource.ResourcePackRequest
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerResourcePackStatusEvent
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** 同梱リソースパックを起動・配信し、クライアントの適用状態をUUID単位で保持します。 */
class ResourcePackController(private val plugin: RpgPlugin) : Listener, AutoCloseable {
    private val settings = plugin.config
    private val enabled = settings.getBoolean("resource_pack.enabled", true)
    private val required = settings.getBoolean("resource_pack.required", true)
    private val mode = settings.getString("resource_pack.mode", "embedded_http") ?: "embedded_http"
    private val gamePortEnabled = settings.getBoolean("resource_pack.same_port_netty.enabled", false)
    private val bindHost = settings.getString("resource_pack.embedded_http.bind_host", "0.0.0.0") ?: "0.0.0.0"
    private val configuredHost = settings.getString("resource_pack.embedded_http.public_host", "") ?: ""
    private val port = settings.getInt("resource_pack.embedded_http.port", 25580)
    private val maxConcurrent = settings.getInt("resource_pack.embedded_http.max_concurrent", 32)
    private val maxPerIp = settings.getInt("resource_pack.embedded_http.max_per_ip", 4)
    private val idleReadTimeoutMillis = settings.getInt("resource_pack.embedded_http.idle_read_timeout_ms", 500)
    private val headerDeadlineMillis = settings.getInt("resource_pack.embedded_http.header_deadline_ms", 1_500)
    private val externalUrl = settings.getString("resource_pack.external_url", "") ?: ""
    private val externalSha1 = settings.getString("resource_pack.external_sha1", "") ?: ""
    private val gamePortMaxConnections = settings.getInt("resource_pack.same_port_netty.max_concurrent", 16)
    private val gamePortMaxPerIp = settings.getInt("resource_pack.same_port_netty.max_per_ip", 2)
    private val gamePortMaxHeaderBytes = settings.getInt("resource_pack.same_port_netty.max_header_bytes", 4_096)
    private val gamePortHeaderDeadlineMillis = settings.getInt("resource_pack.same_port_netty.header_deadline_ms", 2_000)
    private val gamePortIdleTimeoutMillis = settings.getInt("resource_pack.same_port_netty.idle_timeout_ms", 500)
    private val gamePortBandwidthKbPerSecond = settings.getInt("resource_pack.same_port_netty.bandwidth_kb_per_sec", 1_024)
    private val preferences = mutableMapOf<UUID, PackPreference>()
    private val loadedPlayers = mutableSetOf<UUID>()
    private val requestId = UUID.nameUUIDFromBytes("etherealchronicle-resource-pack-v1".toByteArray())
    private data class PendingRequest(val id: UUID, val required: Boolean)
    private val pendingRequests = mutableMapOf<UUID, PendingRequest>()
    private val artifact: ResourcePackArtifact? = if (enabled) loadArtifact() else null
    private val configuredEndpoint: ResourcePackEndpoint? = artifact?.let {
        runCatching {
            require(externalSha1.isBlank() || externalSha1.matches(Regex("[a-f0-9]{40}")))
            if (mode == "game_port" && !gamePortEnabled) {
                plugin.logger.warning("Game-port resource-pack mode is opt-in; enable resource_pack.same_port_netty.enabled. Falling back to HTTPS external_url when configured.")
                if (externalUrl.isNotBlank()) createEndpoint("external_url") else null
            } else createEndpoint(mode)
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
            idleReadTimeoutMillis, headerDeadlineMillis)
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

    init {
        if (enabled && artifact != null) plugin.server.pluginManager.registerEvents(this, plugin)
        if (required && !isDeliveryAvailable()) warnRequiredUnavailable()
    }

    /** プレイヤー設定と配信可能状態を確認し、Adventure ResourcePackRequestを送信します。 */
    fun send(player: org.bukkit.entity.Player, preference: PackPreference = PackPreference.AUTO) {
        preferences[player.uniqueId] = preference
        val deliveryEndpoint = if (gamePortFailed.get()) endpointFallback else endpoint
        val bypass = player.hasPermission("etherealchronicle.pack.bypass")
        val deliveryAvailable=isDeliveryAvailable()
        if (required && !deliveryAvailable) warnRequiredUnavailable()
        val decision = ResourcePackPolicy.decide(required,deliveryAvailable,preference,bypass)
        if (!decision.send || deliveryEndpoint == null || artifact == null) {
            pendingRequests.remove(player.uniqueId)
            loadedPlayers -= player.uniqueId
            plugin.refreshPackItemModels(player, false)
            return
        }
        val hash = advertisedPackSha1(externalSha1,artifact.sha1)
        val info = ResourcePackInfo.resourcePackInfo(requestId, deliveryEndpoint.url(hash), hash)
        val promptKey = if (decision.required) "ec.resource_pack.required_prompt" else "ec.resource_pack.prompt"
        val prompt = plugin.message(player, promptKey)
        pendingRequests[player.uniqueId] = PendingRequest(requestId,decision.required)
        // 再接続時は毎回要求し、同一IDに対して変わったSHA-1を送ってキャッシュの更新判定を促します。
        player.sendResourcePacks(ResourcePackRequest.resourcePackRequest().packs(info).required(decision.required).prompt(prompt).replace(false).build())
    }

    /** 状態が成功適用で、かつ利用者設定がOFFでない場合だけリソパ表現を許可します。 */
    fun hasResourcePack(playerId: UUID): Boolean = playerId in loadedPlayers && preferences[playerId] != PackPreference.OFF

    @EventHandler fun onStatus(event: PlayerResourcePackStatusEvent) {
        val request = pendingRequests[event.player.uniqueId]?.takeIf { it.id == event.id } ?: return
        if (event.status == PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED) {
            pendingRequests.remove(event.player.uniqueId)
            loadedPlayers += event.player.uniqueId
            plugin.refreshPackItemModels(event.player, true)
        } else if (event.status in terminalStatuses) {
            pendingRequests.remove(event.player.uniqueId)
            loadedPlayers -= event.player.uniqueId
            plugin.refreshPackItemModels(event.player, false)
            if (required && !isDeliveryAvailable()) warnRequiredUnavailable()
            if (ResourcePackPolicy.disconnectOnFailure(request.required && isDeliveryAvailable(),event.status in terminalStatuses) && event.player.isOnline) {
                event.player.kick(plugin.message(event.player,"ec.resource_pack.required_declined"))
            }
        }
    }

    @EventHandler fun onQuit(event: PlayerQuitEvent) {
        loadedPlayers -= event.player.uniqueId
        preferences -= event.player.uniqueId
        pendingRequests -= event.player.uniqueId
    }

    override fun close() {
        gamePortServer?.close()
        httpServer?.close()
        loadedPlayers.clear()
        preferences.clear()
        pendingRequests.clear()
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

    /** 配信設定が壊れている時に一度だけ翻訳済みの安全解除警告を記録します。 */
    private fun warnRequiredUnavailable() {
        if(requiredWarningLogged.compareAndSet(false,true)) {
            plugin.logger.warning(plain(plugin.message(null,"ec.resource_pack.required_unavailable")))
        }
    }

    private fun plain(component: net.kyori.adventure.text.Component): String =
        net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(component)

    private companion object {
        val terminalStatuses = setOf(
            PlayerResourcePackStatusEvent.Status.DECLINED,
            PlayerResourcePackStatusEvent.Status.FAILED_DOWNLOAD,
            PlayerResourcePackStatusEvent.Status.INVALID_URL,
            PlayerResourcePackStatusEvent.Status.FAILED_RELOAD,
            PlayerResourcePackStatusEvent.Status.DISCARDED,
        )
    }
}

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
