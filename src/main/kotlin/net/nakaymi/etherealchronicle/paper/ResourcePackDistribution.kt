package net.nakaymi.etherealchronicle.paper

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
        require(mode in setOf("embedded_http", "external_url", "game_port"))
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

/** 固定URLだけを返し、部分HTTPリクエストにも有限時間で応答を打ち切るHTTPサーバーです。 */
class EmbeddedPackServer(
    private val endpoint: ResourcePackEndpoint,
    private val artifact: ResourcePackArtifact,
    private val maxConcurrent: Int,
    private val maxPerIp: Int = maxConcurrent.coerceAtMost(4),
    private val idleReadTimeoutMillis: Int = 500,
    private val headerDeadlineMillis: Int = 1_500,
) : AutoCloseable {
    init {
        require(maxConcurrent in 1..128)
        require(maxPerIp in 1..maxConcurrent)
        require(idleReadTimeoutMillis in 100..10_000)
        require(headerDeadlineMillis in idleReadTimeoutMillis..30_000)
    }

    private val running = AtomicInteger(1)
    private val activeConnections = AtomicInteger()
    private val perIpConnections = ConcurrentHashMap<String, AtomicInteger>()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val executor = ThreadPoolExecutor(
        maxConcurrent,
        maxConcurrent,
        0,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(maxConcurrent),
        { task -> Thread(task, "ec-resource-pack-http").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )
    private val serverSocket = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(endpoint.bindHost, endpoint.port), maxConcurrent)
    }
    private val acceptThread = Thread(::acceptConnections, "ec-resource-pack-accept").apply {
        isDaemon = true
        start()
    }

    /** 接続数を制限してから、各ソケットをタイムアウト付きワーカーへ渡します。 */
    private fun acceptConnections() {
        while (running.get() == 1) {
            val socket = try {
                serverSocket.accept()
            } catch (_: IOException) {
                if (running.get() == 1) continue else return
            }
            val ip = socket.inetAddress.hostAddress ?: "unknown"
            val ipCounter = perIpConnections.computeIfAbsent(ip) { AtomicInteger() }
            val total = activeConnections.incrementAndGet()
            val forIp = ipCounter.incrementAndGet()
            if (total > maxConcurrent || forIp > maxPerIp) {
                releaseConnection(socket, ip, ipCounter)
                continue
            }
            sockets += socket
            try {
                executor.execute { handle(socket, ip, ipCounter) }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                releaseConnection(socket, ip, ipCounter)
            }
        }
    }

    /** ヘッダー合計期限と無通信期限の両方を守り、固定パックだけを返します。 */
    private fun handle(socket: Socket, ip: String, ipCounter: AtomicInteger) {
        try {
            socket.tcpNoDelay = true
            val request = readRequest(socket)
            val (method, target) = request
            val isPack = target == "/pack/${artifact.sha1}.zip"
            if (method !in setOf("GET", "HEAD") || !isPack) {
                writeResponse(socket, 404, "Not Found", null, headOnly = true)
            } else {
                writeResponse(socket, 200, "OK", artifact.bytes, headOnly = method == "HEAD")
            }
        } catch (_: IOException) {
            // 無効な入力・遅いヘッダーは応答を待たず接続を閉じます。
        } finally {
            releaseConnection(socket, ip, ipCounter)
        }
    }

    /** HTTP/1.xリクエスト行とヘッダーを、サイズ・総時間を制限して読みます。 */
    private fun readRequest(socket: Socket): Pair<String, String> {
        val input = BufferedInputStream(socket.getInputStream())
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(headerDeadlineMillis.toLong())
        var totalBytes = 0
        fun readLine(): String {
            val line = ByteArrayOutputStream()
            var previous = -1
            while (true) {
                val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).toInt()
                if (remaining <= 0) throw java.net.SocketTimeoutException("HTTP header deadline exceeded")
                socket.soTimeout = minOf(idleReadTimeoutMillis, remaining.coerceAtLeast(1))
                val current = input.read()
                if (current < 0) throw IOException("Request ended before headers")
                totalBytes++
                if (totalBytes > MAX_HEADER_BYTES) throw IOException("HTTP headers exceeded limit")
                if (previous == '\r'.code && current == '\n'.code) {
                    val bytes = line.toByteArray()
                    if (bytes.isNotEmpty()) return bytes.copyOf(bytes.size - 1).toString(Charsets.ISO_8859_1)
                    return ""
                }
                line.write(current)
                previous = current
            }
        }

        val requestLine = readLine().split(' ', limit = 3)
        if (requestLine.size != 3 || !requestLine[2].startsWith("HTTP/1.")) throw IOException("Invalid HTTP request line")
        var headerCount = 0
        while (readLine().isNotEmpty()) {
            headerCount++
            if (headerCount > MAX_HEADERS) throw IOException("Too many HTTP headers")
        }
        return requestLine[0] to requestLine[1]
    }

    /** HTTP応答に必要な最小ヘッダーだけを書き、本文はGETにだけ付けます。 */
    private fun writeResponse(socket: Socket, status: Int, reason: String, body: ByteArray?, headOnly: Boolean) {
        val bytes = body ?: ByteArray(0)
        val output = BufferedOutputStream(socket.getOutputStream())
        val contentType = if (status == 200) "Content-Type: application/zip\r\n" else ""
        output.write("HTTP/1.1 $status $reason\r\n$contentType".toByteArray(Charsets.ISO_8859_1))
        output.write("Cache-Control: public, max-age=3600, immutable\r\n".toByteArray(Charsets.ISO_8859_1))
        output.write("X-Content-Type-Options: nosniff\r\n".toByteArray(Charsets.ISO_8859_1))
        output.write("Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        if (!headOnly && bytes.isNotEmpty()) output.write(bytes)
        output.flush()
    }

    /** ソケット・グローバル枠・送信元IP枠を必ず同時に解放します。 */
    private fun releaseConnection(socket: Socket, ip: String, ipCounter: AtomicInteger) {
        if (!sockets.remove(socket) && socket.isClosed) return
        runCatching { socket.close() }
        activeConnections.updateAndGet { (it - 1).coerceAtLeast(0) }
        if (ipCounter.decrementAndGet() <= 0) perIpConnections.remove(ip, ipCounter)
    }

    override fun close() {
        if (running.getAndSet(0) == 0) return
        runCatching { serverSocket.close() }
        sockets.toList().forEach { runCatching { it.close() } }
        executor.shutdownNow()
    }

    private companion object {
        const val MAX_HEADER_BYTES = 4_096
        const val MAX_HEADERS = 64
    }
}
