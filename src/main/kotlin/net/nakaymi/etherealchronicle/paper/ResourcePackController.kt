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
    private val required = settings.getBoolean("resource_pack.required", false)
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
    }

    /** プレイヤー設定と配信可能状態を確認し、Adventure ResourcePackRequestを送信します。 */
    fun send(player: org.bukkit.entity.Player, preference: PackPreference = PackPreference.AUTO) {
        preferences[player.uniqueId] = preference
        val deliveryEndpoint = if (gamePortFailed.get()) endpointFallback else endpoint
        if (preference == PackPreference.OFF || !enabled || deliveryEndpoint == null || artifact == null) {
            loadedPlayers -= player.uniqueId
            plugin.refreshPackItemModels(player, false)
            return
        }
        val hash = externalSha1.ifBlank { artifact.sha1 }
        val info = ResourcePackInfo.resourcePackInfo(requestId, deliveryEndpoint.url(hash), hash)
        val prompt = plugin.message(player, "ec.resource_pack.prompt")
        player.sendResourcePacks(ResourcePackRequest.resourcePackRequest().packs(info).required(required).prompt(prompt).replace(false).build())
    }

    /** 状態が成功適用で、かつ利用者設定がOFFでない場合だけリソパ表現を許可します。 */
    fun hasResourcePack(playerId: UUID): Boolean = playerId in loadedPlayers && preferences[playerId] != PackPreference.OFF

    @EventHandler fun onStatus(event: PlayerResourcePackStatusEvent) {
        if (event.id != requestId) return
        if (event.status == PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED) {
            loadedPlayers += event.player.uniqueId
            plugin.refreshPackItemModels(event.player, true)
        } else if (event.status in terminalStatuses) {
            loadedPlayers -= event.player.uniqueId
            plugin.refreshPackItemModels(event.player, false)
        }
    }

    @EventHandler fun onQuit(event: PlayerQuitEvent) {
        loadedPlayers -= event.player.uniqueId
        preferences -= event.player.uniqueId
    }

    override fun close() {
        gamePortServer?.close()
        httpServer?.close()
        loadedPlayers.clear()
        preferences.clear()
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

/** プレイヤー単位の自動・強制ON・強制OFFをプロファイルへ保存します。 */
enum class PackPreference { AUTO, ON, OFF }
