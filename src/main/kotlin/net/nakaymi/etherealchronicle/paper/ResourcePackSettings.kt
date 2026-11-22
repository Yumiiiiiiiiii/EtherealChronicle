package net.nakaymi.etherealchronicle.paper

import org.bukkit.configuration.ConfigurationSection
import java.util.UUID

/** リソースパック配信とHTTP入力検証で共有する上限・既定値です。 */
internal object ResourcePackLimits {
    const val BYTES_PER_KIB = 1_024L
    const val MIN_PORT = 1_024
    const val MAX_PORT = 65_535
    const val MIN_TIMEOUT_MILLIS = 100
    const val MAX_IDLE_TIMEOUT_MILLIS = 10_000
    const val MAX_HEADER_DEADLINE_MILLIS = 30_000
    const val MIN_WRITE_DEADLINE_MILLIS = 500
    const val MAX_WRITE_DEADLINE_MILLIS = 30_000
    const val MIN_GAME_PORT_HEADER_DEADLINE_MILLIS = 500
    const val MAX_GAME_PORT_HEADER_DEADLINE_MILLIS = 10_000
    const val MIN_CONNECTIONS = 1
    const val MAX_CONNECTIONS = 128
    const val MIN_HEADER_BYTES = 256
    const val MAX_HEADER_BYTES = 16_384
    const val MIN_BANDWIDTH_KB_PER_SECOND = 64
    const val MAX_BANDWIDTH_KB_PER_SECOND = 16_384
    const val MAX_REQUIRED_TIMEOUT_SECONDS = 600
    const val MIN_REQUIRED_TIMEOUT_SECONDS = 10
}

/** 設定・コンストラクタ間で値が食い違わないための配信既定値です。 */
internal object ResourcePackDefaults {
    const val REQUIRED_TIMEOUT_SECONDS = 120
    const val HTTP_PORT = 25_580
    const val HTTP_CONNECTIONS = 32
    const val HTTP_PER_IP = 4
    const val HTTP_IDLE_TIMEOUT_MILLIS = 500
    const val HTTP_HEADER_DEADLINE_MILLIS = 1_500
    const val HTTP_WRITE_DEADLINE_MILLIS = 5_000
    const val GAME_PORT_CONNECTIONS = 16
    const val GAME_PORT_PER_IP = 2
    const val GAME_PORT_HEADER_BYTES = 4_096
    const val GAME_PORT_HEADER_DEADLINE_MILLIS = 2_000
    const val GAME_PORT_IDLE_TIMEOUT_MILLIS = 500
    const val GAME_PORT_BANDWIDTH_KB_PER_SECOND = 1_024
}

/** HTTP応答コードを用途名で扱い、配信経路間の表現を揃えます。 */
internal object HttpStatus {
    const val OK = 200
    const val NOT_FOUND = 404
}

/** リソースパック設定を起動時に一度だけ読み、既定値と入力範囲を集約します。 */
internal data class ResourcePackSettings(
    val enabled: Boolean,
    val required: Boolean,
    val requiredTimeoutSeconds: Int,
    val debug: Boolean,
    val mode: String,
    val expectedPackIdText: String,
    val expectedPackId: UUID?,
    val invalidExpectedPackId: Boolean,
    val gamePortEnabled: Boolean,
    val bindHost: String,
    val publicHost: String,
    val port: Int,
    val maxConcurrent: Int,
    val maxPerIp: Int,
    val idleReadTimeoutMillis: Int,
    val headerDeadlineMillis: Int,
    val writeDeadlineMillis: Int,
    val externalUrl: String,
    val externalSha1: String,
    val gamePortMaxConnections: Int,
    val gamePortMaxPerIp: Int,
    val gamePortMaxHeaderBytes: Int,
    val gamePortHeaderDeadlineMillis: Int,
    val gamePortIdleTimeoutMillis: Int,
    val gamePortBandwidthKbPerSecond: Int,
) {
    init {
        require(mode in setOf("embedded_http", "external_url", "external_managed", "game_port"))
        require(requiredTimeoutSeconds in ResourcePackLimits.MIN_REQUIRED_TIMEOUT_SECONDS..ResourcePackLimits.MAX_REQUIRED_TIMEOUT_SECONDS)
        require(port in ResourcePackLimits.MIN_PORT..ResourcePackLimits.MAX_PORT)
        require(maxConcurrent in ResourcePackLimits.MIN_CONNECTIONS..ResourcePackLimits.MAX_CONNECTIONS && maxPerIp in ResourcePackLimits.MIN_CONNECTIONS..maxConcurrent)
        require(idleReadTimeoutMillis in ResourcePackLimits.MIN_TIMEOUT_MILLIS..ResourcePackLimits.MAX_IDLE_TIMEOUT_MILLIS)
        require(headerDeadlineMillis in idleReadTimeoutMillis..ResourcePackLimits.MAX_HEADER_DEADLINE_MILLIS)
        require(writeDeadlineMillis in ResourcePackLimits.MIN_WRITE_DEADLINE_MILLIS..ResourcePackLimits.MAX_WRITE_DEADLINE_MILLIS)
        require(gamePortMaxConnections in ResourcePackLimits.MIN_CONNECTIONS..ResourcePackLimits.MAX_CONNECTIONS && gamePortMaxPerIp in ResourcePackLimits.MIN_CONNECTIONS..gamePortMaxConnections)
        require(gamePortMaxHeaderBytes in ResourcePackLimits.MIN_HEADER_BYTES..ResourcePackLimits.MAX_HEADER_BYTES)
        require(gamePortHeaderDeadlineMillis in ResourcePackLimits.MIN_GAME_PORT_HEADER_DEADLINE_MILLIS..ResourcePackLimits.MAX_GAME_PORT_HEADER_DEADLINE_MILLIS)
        require(gamePortIdleTimeoutMillis in ResourcePackLimits.MIN_TIMEOUT_MILLIS..gamePortHeaderDeadlineMillis)
        require(gamePortBandwidthKbPerSecond in ResourcePackLimits.MIN_BANDWIDTH_KB_PER_SECOND..ResourcePackLimits.MAX_BANDWIDTH_KB_PER_SECOND)
    }

    companion object {
        /** 設定ミスを通知し、パック機能だけを止めてプラグイン起動を継続します。 */
        fun loadSafely(config: ConfigurationSection, onFailure: (Throwable) -> Unit): ResourcePackSettings =
            runCatching { from(config) }.getOrElse { error ->
                onFailure(error)
                safeDisabled()
            }

        /** typoなどで設定読込に失敗した場合に配信と必須化を安全に止めます。 */
        fun safeDisabled(): ResourcePackSettings = ResourcePackSettings(
            enabled = false,
            required = false,
            requiredTimeoutSeconds = ResourcePackDefaults.REQUIRED_TIMEOUT_SECONDS,
            debug = false,
            mode = "embedded_http",
            expectedPackIdText = "",
            expectedPackId = null,
            invalidExpectedPackId = false,
            gamePortEnabled = false,
            bindHost = "127.0.0.1",
            publicHost = "",
            port = ResourcePackDefaults.HTTP_PORT,
            maxConcurrent = ResourcePackDefaults.HTTP_CONNECTIONS,
            maxPerIp = ResourcePackDefaults.HTTP_PER_IP,
            idleReadTimeoutMillis = ResourcePackDefaults.HTTP_IDLE_TIMEOUT_MILLIS,
            headerDeadlineMillis = ResourcePackDefaults.HTTP_HEADER_DEADLINE_MILLIS,
            writeDeadlineMillis = ResourcePackDefaults.HTTP_WRITE_DEADLINE_MILLIS,
            externalUrl = "",
            externalSha1 = "",
            gamePortMaxConnections = ResourcePackDefaults.GAME_PORT_CONNECTIONS,
            gamePortMaxPerIp = ResourcePackDefaults.GAME_PORT_PER_IP,
            gamePortMaxHeaderBytes = ResourcePackDefaults.GAME_PORT_HEADER_BYTES,
            gamePortHeaderDeadlineMillis = ResourcePackDefaults.GAME_PORT_HEADER_DEADLINE_MILLIS,
            gamePortIdleTimeoutMillis = ResourcePackDefaults.GAME_PORT_IDLE_TIMEOUT_MILLIS,
            gamePortBandwidthKbPerSecond = ResourcePackDefaults.GAME_PORT_BANDWIDTH_KB_PER_SECOND,
        )

        /** Bukkitの設定ツリーを型付き設定へ変換します。 */
        fun from(config: ConfigurationSection): ResourcePackSettings {
            val expectedPackIdText = config.getString("resource_pack.expected_pack_id", "")?.trim().orEmpty()
            val expectedPackId = expectedPackIdText.takeIf(String::isNotBlank)
                ?.let { value -> runCatching { UUID.fromString(value) }.getOrNull() }
            val maxConcurrent = config.getInt("resource_pack.embedded_http.max_concurrent", ResourcePackDefaults.HTTP_CONNECTIONS)

            return ResourcePackSettings(
                enabled = config.getBoolean("resource_pack.enabled", true),
                required = config.getBoolean("resource_pack.required", true),
                requiredTimeoutSeconds = config.getInt("resource_pack.required_timeout_seconds", ResourcePackDefaults.REQUIRED_TIMEOUT_SECONDS).coerceIn(ResourcePackLimits.MIN_REQUIRED_TIMEOUT_SECONDS, ResourcePackLimits.MAX_REQUIRED_TIMEOUT_SECONDS),
                debug = config.getBoolean("resource_pack.debug", false),
                mode = config.getString("resource_pack.mode", "embedded_http") ?: "embedded_http",
                expectedPackIdText = expectedPackIdText,
                expectedPackId = expectedPackId,
                invalidExpectedPackId = expectedPackIdText.isNotBlank() && expectedPackId == null,
                gamePortEnabled = config.getBoolean("resource_pack.same_port_netty.enabled", false),
                bindHost = config.getString("resource_pack.embedded_http.bind_host", "0.0.0.0") ?: "0.0.0.0",
                publicHost = config.getString("resource_pack.embedded_http.public_host", "") ?: "",
                port = config.getInt("resource_pack.embedded_http.port", ResourcePackDefaults.HTTP_PORT),
                maxConcurrent = maxConcurrent,
                maxPerIp = config.getInt("resource_pack.embedded_http.max_per_ip", maxConcurrent.coerceAtMost(ResourcePackDefaults.HTTP_PER_IP)),
                idleReadTimeoutMillis = config.getInt("resource_pack.embedded_http.idle_read_timeout_ms", ResourcePackDefaults.HTTP_IDLE_TIMEOUT_MILLIS),
                headerDeadlineMillis = config.getInt("resource_pack.embedded_http.header_deadline_ms", ResourcePackDefaults.HTTP_HEADER_DEADLINE_MILLIS),
                writeDeadlineMillis = config.getInt("resource_pack.embedded_http.write_deadline_ms", ResourcePackDefaults.HTTP_WRITE_DEADLINE_MILLIS),
                externalUrl = config.getString("resource_pack.external_url", "") ?: "",
                externalSha1 = config.getString("resource_pack.external_sha1", "") ?: "",
                gamePortMaxConnections = config.getInt("resource_pack.same_port_netty.max_concurrent", ResourcePackDefaults.GAME_PORT_CONNECTIONS),
                gamePortMaxPerIp = config.getInt("resource_pack.same_port_netty.max_per_ip", ResourcePackDefaults.GAME_PORT_PER_IP),
                gamePortMaxHeaderBytes = config.getInt("resource_pack.same_port_netty.max_header_bytes", ResourcePackDefaults.GAME_PORT_HEADER_BYTES),
                gamePortHeaderDeadlineMillis = config.getInt("resource_pack.same_port_netty.header_deadline_ms", ResourcePackDefaults.GAME_PORT_HEADER_DEADLINE_MILLIS),
                gamePortIdleTimeoutMillis = config.getInt("resource_pack.same_port_netty.idle_timeout_ms", ResourcePackDefaults.GAME_PORT_IDLE_TIMEOUT_MILLIS),
                gamePortBandwidthKbPerSecond = config.getInt("resource_pack.same_port_netty.bandwidth_kb_per_sec", ResourcePackDefaults.GAME_PORT_BANDWIDTH_KB_PER_SECOND),
            )
        }
    }
}

/** 組み込みHTTPの公開先がローカル限定にならないかを判定します。 */
internal object ResourcePackEndpointPolicy {
    fun canAdvertise(bindHost: String, publicHost: String): Boolean {
        if (isWildcard(publicHost)) return false
        return isLoopback(bindHost) || !isLoopback(publicHost)
    }

    private fun isLoopback(host: String): Boolean {
        val normalized = host.trim().removePrefix("[").removeSuffix("]").lowercase()
        val mappedIpv4 = normalized.removePrefix("::ffff:")
        return normalized == "localhost" || normalized.endsWith(".localhost") || normalized == "::1" ||
            mappedIpv4.startsWith("127.")
    }

    private fun isWildcard(host: String): Boolean = host.trim().removePrefix("[").removeSuffix("]")
        .lowercase() in setOf("0.0.0.0", "::", "*")
}
