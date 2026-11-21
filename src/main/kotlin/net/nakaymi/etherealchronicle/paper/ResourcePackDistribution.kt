package net.nakaymi.etherealchronicle.paper

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** HTTP配信へ渡す同梱リソースパックをハッシュと共に保持します。 */
data class ResourcePackArtifact(val bytes: ByteArray, val sha1: String) {
    init {
        require(sha1.matches(Regex("[a-f0-9]{40}")))
        require(MessageDigest.getInstance("SHA-1").digest(bytes).toHex() == sha1)
    }

    companion object {
        fun from(bytes: ByteArray): ResourcePackArtifact = ResourcePackArtifact(
            bytes,
            MessageDigest.getInstance("SHA-1").digest(bytes).toHex(),
        )

        private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    }
}

/** 設定文字列を検証して、プレイヤーへ送信するURLと配信モードを決定します。 */
data class ResourcePackEndpoint(val bindHost: String, val publicHost: String, val port: Int, val mode: String, val externalUrl: String) {
    init {
        require(mode in setOf("embedded_http", "external_url"))
        require(port in 1024..65535)
        require(bindHost.isNotBlank() && bindHost.none(Char::isISOControl))
        require(publicHost.isNotBlank() && publicHost.none { it.isWhitespace() || it.isISOControl() || it == '/' || it == '\\' })
        if (mode == "external_url") require(URI(externalUrl).let { it.scheme == "https" && it.host != null })
    }

    fun url(hash: String): URI {
        require(hash.matches(Regex("[a-f0-9]{40}")))
        return if (mode == "external_url") URI(externalUrl) else URI("http", null, publicHost, port, "/pack/$hash.zip", null, null)
    }
}

/** 固定URLのみをGET/HEADで返す有界HTTPサーバーです。 */
class EmbeddedPackServer(
    private val endpoint: ResourcePackEndpoint,
    private val artifact: ResourcePackArtifact,
    private val maxConcurrent: Int,
) : AutoCloseable {
    init { require(maxConcurrent in 1..128) }
    private val permits = Semaphore(maxConcurrent)
    private val executor = ThreadPoolExecutor(maxConcurrent, maxConcurrent, 0, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(maxConcurrent * 4), { task -> Thread(task, "ec-resource-pack-http").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy())
    private val server = HttpServer.create(InetSocketAddress(endpoint.bindHost, endpoint.port), 0).apply {
        setExecutor(this@EmbeddedPackServer.executor)
        createContext("/") { exchange -> respond(exchange) }
        start()
    }

    private fun respond(exchange: HttpExchange) {
        if (!permits.tryAcquire()) {
            exchange.close()
            return
        }
        try {
            if (exchange.requestMethod !in setOf("GET", "HEAD") || exchange.requestURI.rawPath != "/pack/${artifact.sha1}.zip" ||
                exchange.requestURI.rawQuery != null || exchange.requestURI.rawFragment != null) {
                exchange.sendResponseHeaders(404, -1)
                return
            }
            exchange.responseHeaders.set("Content-Type", "application/zip")
            exchange.responseHeaders.set("Cache-Control", "public, max-age=3600, immutable")
            exchange.responseHeaders.set("X-Content-Type-Options", "nosniff")
            exchange.responseHeaders.set("Content-Length", artifact.bytes.size.toString())
            if (exchange.requestMethod == "HEAD") {
                exchange.sendResponseHeaders(200, -1)
            } else {
                exchange.sendResponseHeaders(200, artifact.bytes.size.toLong())
                exchange.responseBody.use { it.write(artifact.bytes) }
            }
        } finally {
            exchange.close()
            permits.release()
        }
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }
}
