package net.nakaymi.etherealchronicle.paper

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class GamePortPackServerTest {
    @Test
    fun `minecraft protocol bytes pass through without being consumed`() {
        val artifact = ResourcePackArtifact.from("pack".toByteArray())
        val channel = sniffer(artifact)
        val packet = byteArrayOf(0x02, 0x00, 0x00)

        channel.writeInbound(Unpooled.wrappedBuffer(packet))

        val forwarded = channel.readInbound<ByteBuf>()
        assertNotNull(forwarded)
        val actual = ByteArray(forwarded!!.readableBytes())
        forwarded.readBytes(actual)
        forwarded.release()
        assertArrayEquals(packet, actual)
        assertNull(channel.pipeline().get(PACK_HANDLER))
        channel.finishAndReleaseAll()
    }

    @Test
    fun `fixed path get returns pack bytes with matching sha1`() {
        val artifact = ResourcePackArtifact.from("same-port-test-pack".toByteArray())
        val channel = sniffer(artifact)
        val request = "GET /pack/${artifact.sha1}.zip HTTP/1.1\r\nHost: localhost\r\n\r\n"

        channel.writeInbound(Unpooled.copiedBuffer(request, StandardCharsets.US_ASCII))
        channel.runPendingTasks()
        channel.runScheduledPendingTasks()

        val response = drainOutbound(channel)
        assertTrue(response.startsWith("HTTP/1.1 200 OK"))
        val body = response.substringAfter("\r\n\r\n").toByteArray(StandardCharsets.ISO_8859_1)
        assertEquals(artifact.sha1, ResourcePackArtifact.from(body).sha1)
        assertArrayEquals(artifact.bytes, body)
        channel.finishAndReleaseAll()
    }

    @Test
    fun `oversized and stalled http headers close the channel`() {
        val artifact = ResourcePackArtifact.from("pack".toByteArray())
        val oversized = sniffer(artifact, maxHeaderBytes = 256)
        oversized.writeInbound(Unpooled.copiedBuffer("GET /${"x".repeat(300)}", StandardCharsets.US_ASCII))
        assertFalse(oversized.isOpen)
        oversized.finishAndReleaseAll()

        val stalled = sniffer(artifact, idleTimeoutMillis = 50)
        stalled.writeInbound(Unpooled.copiedBuffer("GET ", StandardCharsets.US_ASCII))
        Thread.sleep(120)
        stalled.runScheduledPendingTasks()
        assertFalse(stalled.isOpen)
        stalled.finishAndReleaseAll()
    }

    @Test
    fun `per ip connection cap rejects excess leases`() {
        val limiter = PackConnectionLimiter(maxConnections = 2, maxPerIp = 1, bandwidthKbPerSecond = 1024)

        assertTrue(limiter.acquire("192.0.2.10"))
        assertFalse(limiter.acquire("192.0.2.10"))
        assertTrue(limiter.acquire("192.0.2.11"))
        limiter.release("192.0.2.10")
        limiter.release("192.0.2.11")
    }

    @Test
    fun `concurrent connection attempts cannot exceed the per ip cap`() {
        val limiter = PackConnectionLimiter(maxConnections = 16, maxPerIp = 2, bandwidthKbPerSecond = 1024)
        val pool = Executors.newFixedThreadPool(16)
        try {
            val results = pool.invokeAll((1..16).map { Callable { limiter.acquire("198.51.100.7") } })
                .count { it.get() }
            assertEquals(2, results)
            limiter.release("198.51.100.7")
            limiter.release("198.51.100.7")
        } finally {
            pool.shutdownNow()
        }
    }

    private fun sniffer(
        artifact: ResourcePackArtifact,
        maxHeaderBytes: Int = 4096,
        idleTimeoutMillis: Int = 500,
    ) = EmbeddedChannel(
        PackRequestSniffer(
            artifact = artifact,
            limiter = PackConnectionLimiter(4, 2, 1024),
            maxHeaderBytes = maxHeaderBytes,
            headerDeadlineMillis = 1000,
            idleReadTimeoutMillis = idleTimeoutMillis,
        ),
    )

    private fun drainOutbound(channel: EmbeddedChannel): String {
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val buffer = channel.readOutbound<ByteBuf>() ?: break
            val bytes = ByteArray(buffer.readableBytes())
            buffer.readBytes(bytes)
            buffer.release()
            out.write(bytes)
        }
        return out.toByteArray().toString(StandardCharsets.ISO_8859_1)
    }

    private companion object {
        const val PACK_HANDLER = "etherealchronicle_game_port_pack"
    }
}
