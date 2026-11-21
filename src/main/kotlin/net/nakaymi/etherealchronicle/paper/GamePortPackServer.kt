package net.nakaymi.etherealchronicle.paper

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.ByteToMessageDecoder
import net.kyori.adventure.key.Key
import org.bukkit.plugin.java.JavaPlugin
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Paperの新規接続へ軽量な先頭バイト判定を追加し、HTTPパック要求だけを処理します。 */
class GamePortPackServer(
    private val plugin: JavaPlugin,
    private val endpoint: ResourcePackEndpoint,
    private val artifact: ResourcePackArtifact,
    maxConnections: Int,
    maxPerIp: Int,
    maxHeaderBytes: Int,
    headerDeadlineMillis: Int,
    idleReadTimeoutMillis: Int,
    bandwidthKbPerSecond: Int,
    private val onFailure: (Exception) -> Unit,
) : AutoCloseable {
    private val limiter = PackConnectionLimiter(maxConnections, maxPerIp, bandwidthKbPerSecond)
    private val listenerKey = Key.key("etherealchronicle", "resource_pack")
    private val attachedChannels = ConcurrentHashMap.newKeySet<Channel>()
    private val listenerClass = Class.forName("io.papermc.paper.network.ChannelInitializeListener")
    private val holderClass = Class.forName("io.papermc.paper.network.ChannelInitializeListenerHolder")
    private val failed = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val listener = java.lang.reflect.Proxy.newProxyInstance(
        listenerClass.classLoader,
        arrayOf(listenerClass),
    ) { _, method, args ->
        if (method.name == "afterInitChannel") {
            val channel = args?.firstOrNull() as? Channel ?: return@newProxyInstance null
            if (!closed.get() && !failed.get() && channel.isActive && attachedChannels.add(channel)) {
                try {
                    val sniffer = PackRequestSniffer(
                        artifact,
                        limiter,
                        maxHeaderBytes,
                        headerDeadlineMillis,
                        idleReadTimeoutMillis,
                    )
                    channel.pipeline().addFirst(HANDLER_NAME, sniffer)
                    channel.closeFuture().addListener { attachedChannels.remove(channel) }
                } catch (error: Exception) {
                    attachedChannels.remove(channel)
                    if (failed.compareAndSet(false, true)) {
                        plugin.logger.warning("Could not attach the game-port request sniffer; disabling it without rejecting Minecraft connections: ${error.message}")
                        close()
                        onFailure(error)
                    }
                }
            }
        }
        null
    }

    init {
        require(maxHeaderBytes in 256..16_384)
        require(headerDeadlineMillis in 500..10_000)
        require(idleReadTimeoutMillis in 100..headerDeadlineMillis)
        require(unsupportedProxies().isEmpty()) {
            "Game-port pack delivery is not supported behind proxy plugins: ${unsupportedProxies().joinToString()}"
        }
        holderClass.getMethod("addListener", Key::class.java, listenerClass).invoke(null, listenerKey, listener)
        plugin.logger.info("Experimental resource-pack HTTP sniffing enabled on the game port; Paper's internal channel-initialization hook is in use.")
    }

    /** Paperの内部Netty listener登録を解除し、進行中のHTTP要求だけを閉じます。 */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { holderClass.getMethod("removeListener", Key::class.java).invoke(null, listenerKey) }
        attachedChannels.toList().forEach { channel ->
            val sniffer = channel.pipeline().get(HANDLER_NAME) as? PackRequestSniffer ?: return@forEach
            if (sniffer.isHttpRequest) channel.close()
            else runCatching { channel.pipeline().remove(HANDLER_NAME) }
        }
        attachedChannels.clear()
    }

    private fun unsupportedProxies(): List<String> = detectUnsupportedProxies(
        bungeeCordEnabled = runCatching {
            org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                plugin.server.worldContainer.toPath().resolve("spigot.yml").toFile(),
            ).getBoolean("settings.bungeecord", false)
        }.getOrDefault(false),
        velocityEnabled = runCatching {
            org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                plugin.server.worldContainer.toPath().resolve("config/paper-global.yml").toFile(),
            ).getBoolean("proxies.velocity.enabled", false)
        }.getOrDefault(false),
        tcpShieldEnabled = plugin.server.pluginManager.isPluginEnabled("TCPShield"),
    )

    companion object {
        private const val HANDLER_NAME = "etherealchronicle_game_port_pack"
    }
}

/** GET/HEADの固定パスだけを読み、Minecraftパケットはそのまま次のハンドラーへ渡します。 */
internal class PackRequestSniffer(
    private val artifact: ResourcePackArtifact,
    private val limiter: PackConnectionLimiter,
    private val maxHeaderBytes: Int,
    private val headerDeadlineMillis: Int,
    private val idleReadTimeoutMillis: Int,
) : ByteToMessageDecoder() {
    private enum class State { SNIFFING, MINECRAFT, HTTP, CLOSED }

    private val sniffPrefix = ByteArray(5)
    private var sniffPrefixSize = 0
    private val request = ByteArrayOutputStream(256)
    private var headerTerminatorWindow = 0
    private var state = State.SNIFFING
    private var httpLeaseIp: String? = null
    private var headerDeadline: java.util.concurrent.ScheduledFuture<*>? = null
    private var idleTimeout: java.util.concurrent.ScheduledFuture<*>? = null
    private val released = AtomicBoolean(false)
    private var recognizedHttp = false
    val isHttpRequest: Boolean get() = recognizedHttp

    override fun decode(context: ChannelHandlerContext, input: ByteBuf, output: MutableList<Any>) {
        if (state == State.MINECRAFT) {
            if (input.isReadable) output += input.readRetainedSlice(input.readableBytes())
            return
        }
        if (state == State.CLOSED) {
            input.skipBytes(input.readableBytes())
            return
        }
        if (state == State.HTTP) resetIdleTimeout(context)

        while (input.isReadable() && state == State.SNIFFING) {
            val firstByte = input.getUnsignedByte(input.readerIndex()).toInt()
            if (sniffPrefixSize == 0 && firstByte != 'G'.code && firstByte != 'H'.code) {
                passThroughMinecraft(context, input, output)
                return
            }
            if (sniffPrefixSize == 0) resetIdleTimeout(context)
            sniffPrefix[sniffPrefixSize++] = input.readByte()
            val isGetPrefix = sniffPrefix.matchesPrefix(sniffPrefixSize, GET_PREFIX)
            val isHeadPrefix = sniffPrefix.matchesPrefix(sniffPrefixSize, HEAD_PREFIX)
            if (!isGetPrefix && !isHeadPrefix) {
                passBufferedMinecraft(context, input, output)
                return
            }
            if (sniffPrefix.matches(GET_PREFIX) || sniffPrefix.matches(HEAD_PREFIX)) {
                val ip = ((context.channel().remoteAddress() as? InetSocketAddress)?.address?.hostAddress).orEmpty().ifBlank { "unknown" }
                if (!limiter.acquire(ip)) {
                    reject(context)
                    return
                }
                httpLeaseIp = ip
                recognizedHttp = true
                request.write(sniffPrefix, 0, sniffPrefixSize)
                state = State.HTTP
                cancelIdleTimeout()
                startHeaderTimeout(context)
                context.channel().closeFuture().addListener { releaseIpLease() }
                resetIdleTimeout(context)
            }
        }

        while (input.isReadable() && state == State.HTTP) {
            val nextByte = input.readUnsignedByte().toInt()
            request.write(nextByte)
            headerTerminatorWindow = ((headerTerminatorWindow shl 8) or nextByte) and TERMINATOR_MASK
            if (request.size() > maxHeaderBytes) {
                reject(context)
                return
            }
            if (request.size() >= 4 && headerTerminatorWindow == HEADER_TERMINATOR) {
                cancelTimeouts()
                respond(context)
                return
            }
        }
    }

    private fun passThroughMinecraft(context: ChannelHandlerContext, input: ByteBuf, output: MutableList<Any>) {
        state = State.MINECRAFT
        cancelTimeouts()
        if (input.isReadable) output += input.readRetainedSlice(input.readableBytes())
        removeAfterDecode(context)
    }

    private fun passBufferedMinecraft(context: ChannelHandlerContext, input: ByteBuf, output: MutableList<Any>) {
        state = State.MINECRAFT
        cancelTimeouts()
        output += Unpooled.wrappedBuffer(sniffPrefix.copyOf(sniffPrefixSize))
        if (input.isReadable) output += input.readRetainedSlice(input.readableBytes())
        removeAfterDecode(context)
    }

    private fun removeAfterDecode(context: ChannelHandlerContext) {
        context.executor().execute {
            if (context.pipeline().context(this) != null) context.pipeline().remove(this)
        }
    }

    private fun startHeaderTimeout(context: ChannelHandlerContext) {
        headerDeadline = context.executor().schedule({ reject(context) }, headerDeadlineMillis.toLong(), TimeUnit.MILLISECONDS)
    }

    private fun resetIdleTimeout(context: ChannelHandlerContext) {
        idleTimeout?.cancel(false)
        idleTimeout = context.executor().schedule({ reject(context) }, idleReadTimeoutMillis.toLong(), TimeUnit.MILLISECONDS)
    }

    private fun cancelIdleTimeout() {
        idleTimeout?.cancel(false)
        idleTimeout = null
    }

    private fun reject(context: ChannelHandlerContext) {
        if (state == State.CLOSED) return
        state = State.CLOSED
        cancelTimeouts()
        releaseIpLease()
        context.close()
    }

    private fun respond(context: ChannelHandlerContext) {
        val lines = request.toString(StandardCharsets.ISO_8859_1).split("\r\n")
        val parts = lines.firstOrNull()?.split(' ', limit = 3).orEmpty()
        val method = parts.getOrNull(0)
        val path = parts.getOrNull(1)
        val httpVersion = parts.getOrNull(2)
        val allowed = method in setOf("GET", "HEAD") && path == "/pack/${artifact.sha1}.zip" && httpVersion in setOf("HTTP/1.0", "HTTP/1.1")
        val status = if (allowed) 200 else 404
        val body = if (allowed) artifact.bytes else ByteArray(0)
        val reason = if (allowed) "OK" else "Not Found"
        val contentType = if (allowed) "Content-Type: application/zip\r\n" else ""
        val headers = "HTTP/1.1 $status $reason\r\n$contentType" +
            "Cache-Control: public, max-age=3600, immutable\r\n" +
            "X-Content-Type-Options: nosniff\r\n" +
            "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        state = State.CLOSED
        context.writeAndFlush(Unpooled.copiedBuffer(headers, StandardCharsets.ISO_8859_1)).addListener { result ->
            if (!result.isSuccess || method != "GET" || status != 200) {
                context.close()
            } else {
                streamBody(context, body, parts[0], 0)
            }
        }
    }

    private fun streamBody(context: ChannelHandlerContext, body: ByteArray, method: String, offset: Int) {
        if (!context.channel().isActive) return
        if (offset >= body.size) {
            context.close()
            return
        }
        val count = minOf(BODY_CHUNK_BYTES, body.size - offset)
        val delay = limiter.reserveDelayNanos(count)
        val send = Runnable {
            if (!context.channel().isActive) return@Runnable
            context.writeAndFlush(Unpooled.wrappedBuffer(body, offset, count)).addListener { result ->
                if (result.isSuccess) streamBody(context, body, method, offset + count) else context.close()
            }
        }
        if (delay <= 0) send.run()
        else context.executor().schedule(send, delay, TimeUnit.NANOSECONDS)
    }

    private fun cancelTimeouts() {
        headerDeadline?.cancel(false)
        idleTimeout?.cancel(false)
        headerDeadline = null
        idleTimeout = null
    }

    override fun channelInactive(context: ChannelHandlerContext) {
        cancelTimeouts()
        releaseIpLease()
        super.channelInactive(context)
    }

    override fun handlerRemoved0(context: ChannelHandlerContext) {
        cancelTimeouts()
        releaseIpLease()
        super.handlerRemoved0(context)
    }

    private fun releaseIpLease() {
        if (!released.compareAndSet(false, true)) return
        httpLeaseIp?.let(limiter::release)
        httpLeaseIp = null
    }

    private fun ByteArray.matchesPrefix(length: Int, value: ByteArray): Boolean =
        length <= value.size && (0 until length).all { this[it] == value[it] }

    private fun ByteArray.matches(value: ByteArray): Boolean =
        size >= value.size && value.indices.all { this[it] == value[it] }

    private companion object {
        const val BODY_CHUNK_BYTES = 16 * 1024
        const val HEADER_TERMINATOR = 0x0D0A0D0A
        const val TERMINATOR_MASK = 0xFFFFFFFF.toInt()
        val GET_PREFIX = "GET ".toByteArray(StandardCharsets.US_ASCII)
        val HEAD_PREFIX = "HEAD ".toByteArray(StandardCharsets.US_ASCII)
    }
}

/** NAT共有の同一IP接続数を制限し、接続IPをまたいだ単一の帯域予算を適用します。 */
internal class PackConnectionLimiter(
    maxConnections: Int,
    private val maxPerIp: Int,
    private val bandwidthKbPerSecond: Int,
) {
    private val maxConnections = maxConnections.also { require(it in 1..128) }
    private var active = 0
    private val perIp = mutableMapOf<String, Int>()
    /** 全接続で共有する帯域予約。IP枠の解放・再取得でも予算をリセットしません。 */
    private val globalNextSendNanos = AtomicLong()

    init {
        require(maxPerIp in 1..maxConnections)
        require(bandwidthKbPerSecond in 64..16_384)
    }

    @Synchronized
    fun acquire(ip: String): Boolean {
        val local = perIp[ip] ?: 0
        if (active >= maxConnections || local >= maxPerIp) return false
        active++
        perIp[ip] = local + 1
        return true
    }

    @Synchronized
    fun release(ip: String) {
        val local = perIp[ip] ?: return
        active = (active - 1).coerceAtLeast(0)
        if (local <= 1) {
            perIp.remove(ip)
        } else {
            perIp[ip] = local - 1
        }
    }

    fun reserveDelayNanos(bytes: Int): Long {
        val duration = bytes.toLong() * NANOS_PER_SECOND / (bandwidthKbPerSecond.toLong() * 1024)
        val now = System.nanoTime()
        val start = globalNextSendNanos.getAndUpdate { previous -> maxOf(previous, now) + duration }
        return (start - now).coerceAtLeast(0)
    }

    private companion object { const val NANOS_PER_SECOND = 1_000_000_000L }
}

/** プロキシ設定とTCPShieldの組み合わせから同一ポート配信の非対応環境を判定します。 */
internal fun detectUnsupportedProxies(
    bungeeCordEnabled: Boolean,
    velocityEnabled: Boolean,
    tcpShieldEnabled: Boolean,
): List<String> = buildList {
    if (bungeeCordEnabled) add("BungeeCord/Waterfall")
    if (velocityEnabled) add("Velocity")
    if (tcpShieldEnabled) add("TCPShield")
}
