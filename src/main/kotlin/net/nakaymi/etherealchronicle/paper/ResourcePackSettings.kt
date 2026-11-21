package net.nakaymi.etherealchronicle.paper

import org.bukkit.configuration.ConfigurationSection
import java.util.UUID

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
        require(requiredTimeoutSeconds in 10..600)
        require(port in 1024..65535)
        require(maxConcurrent in 1..128 && maxPerIp in 1..maxConcurrent)
        require(idleReadTimeoutMillis in 100..10_000)
        require(headerDeadlineMillis in idleReadTimeoutMillis..30_000)
        require(writeDeadlineMillis in 500..30_000)
        require(gamePortMaxConnections in 1..128 && gamePortMaxPerIp in 1..gamePortMaxConnections)
        require(gamePortMaxHeaderBytes in 256..16_384)
        require(gamePortHeaderDeadlineMillis in 500..10_000)
        require(gamePortIdleTimeoutMillis in 100..gamePortHeaderDeadlineMillis)
        require(gamePortBandwidthKbPerSecond in 64..16_384)
    }

    companion object {
        /** Bukkitの設定ツリーを型付き設定へ変換します。 */
        fun from(config: ConfigurationSection): ResourcePackSettings {
            val expectedPackIdText = config.getString("resource_pack.expected_pack_id", "")?.trim().orEmpty()
            val expectedPackId = expectedPackIdText.takeIf(String::isNotBlank)
                ?.let { value -> runCatching { UUID.fromString(value) }.getOrNull() }
            val maxConcurrent = config.getInt("resource_pack.embedded_http.max_concurrent", 32)

            return ResourcePackSettings(
                enabled = config.getBoolean("resource_pack.enabled", true),
                required = config.getBoolean("resource_pack.required", true),
                requiredTimeoutSeconds = config.getInt("resource_pack.required_timeout_seconds", 120).coerceIn(10, 600),
                debug = config.getBoolean("resource_pack.debug", false),
                mode = config.getString("resource_pack.mode", "embedded_http") ?: "embedded_http",
                expectedPackIdText = expectedPackIdText,
                expectedPackId = expectedPackId,
                invalidExpectedPackId = expectedPackIdText.isNotBlank() && expectedPackId == null,
                gamePortEnabled = config.getBoolean("resource_pack.same_port_netty.enabled", false),
                bindHost = config.getString("resource_pack.embedded_http.bind_host", "0.0.0.0") ?: "0.0.0.0",
                publicHost = config.getString("resource_pack.embedded_http.public_host", "") ?: "",
                port = config.getInt("resource_pack.embedded_http.port", 25580),
                maxConcurrent = maxConcurrent,
                maxPerIp = config.getInt("resource_pack.embedded_http.max_per_ip", maxConcurrent.coerceAtMost(4)),
                idleReadTimeoutMillis = config.getInt("resource_pack.embedded_http.idle_read_timeout_ms", 500),
                headerDeadlineMillis = config.getInt("resource_pack.embedded_http.header_deadline_ms", 1_500),
                writeDeadlineMillis = config.getInt("resource_pack.embedded_http.write_deadline_ms", 5_000),
                externalUrl = config.getString("resource_pack.external_url", "") ?: "",
                externalSha1 = config.getString("resource_pack.external_sha1", "") ?: "",
                gamePortMaxConnections = config.getInt("resource_pack.same_port_netty.max_concurrent", 16),
                gamePortMaxPerIp = config.getInt("resource_pack.same_port_netty.max_per_ip", 2),
                gamePortMaxHeaderBytes = config.getInt("resource_pack.same_port_netty.max_header_bytes", 4_096),
                gamePortHeaderDeadlineMillis = config.getInt("resource_pack.same_port_netty.header_deadline_ms", 2_000),
                gamePortIdleTimeoutMillis = config.getInt("resource_pack.same_port_netty.idle_timeout_ms", 500),
                gamePortBandwidthKbPerSecond = config.getInt("resource_pack.same_port_netty.bandwidth_kb_per_sec", 1_024),
            )
        }
    }
}

/** 組み込みHTTPの公開先がローカル限定にならないかを判定します。 */
internal object ResourcePackEndpointPolicy {
    fun canAdvertise(bindHost: String, publicHost: String): Boolean =
        isLoopback(bindHost) || !isLoopback(publicHost)

    private fun isLoopback(host: String): Boolean {
        val normalized = host.trim().removePrefix("[").removeSuffix("]").lowercase()
        return normalized == "localhost" || normalized.endsWith(".localhost") || normalized == "::1" ||
            normalized.startsWith("127.")
    }
}
