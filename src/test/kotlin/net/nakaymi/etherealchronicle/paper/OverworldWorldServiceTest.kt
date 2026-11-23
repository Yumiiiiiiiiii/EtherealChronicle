package net.nakaymi.etherealchronicle.paper

import net.nakaymi.etherealchronicle.core.DungeonBoxScanResult
import net.nakaymi.etherealchronicle.core.DungeonInstanceBounds
import net.nakaymi.etherealchronicle.core.OverworldSite
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.WorldBorder
import org.bukkit.block.Biome
import org.bukkit.block.Block
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

/** 起動時の必須asset欠落と中断journalの安全な手動復旧制御、実Paperのチャンクアンロード耐性を検証します。 */
class OverworldWorldServiceTest {
    @Test
    fun `close releases held tickets and rejects new starts`() {
        MockBukkit.mock()
        try {
            val plugin = MockBukkit.createMockPlugin()
            val world = mockWorld(Files.createTempDirectory("overworld-close"), "overworld-close")
            val service = OverworldWorldService(plugin, world)
            var removed = 0
            val chunk = Proxy.newProxyInstance(Chunk::class.java.classLoader, arrayOf(Chunk::class.java)) { _, method, _ ->
                when (method.name) {
                    "removePluginChunkTicket" -> { removed++; true }
                    else -> error("Unexpected chunk call: ${method.name}")
                }
            } as Chunk
            val field = OverworldWorldService::class.java.getDeclaredField("tickets")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            (field.get(service) as MutableList<Chunk>).add(chunk)
            service.close()
            assertEquals(1, removed)
            assertThrows<IllegalStateException> { service.start() }
        } finally { MockBukkit.unmock() }
    }

    @Test
    fun `Lumina spawn requires safe floor and two air blocks`() {
        val server = MockBukkit.mock()
        try {
            val plugin = MockBukkit.createMockPlugin()
            val world = server.addSimpleWorld("lumina-spawn-safety")
            val service = OverworldWorldService(plugin, world)
            try {
                val method = OverworldWorldService::class.java.getDeclaredMethod("isSafeSpawn", OverworldPoint::class.java)
                method.isAccessible = true
                val point = OverworldPoint(0, 65, 0)
                fun safe() = method.invoke(service, point) as Boolean
                world.getBlockAt(0, 64, 0).type = Material.STONE
                assertTrue(safe())
                world.getBlockAt(0, 64, 0).type = Material.MAGMA_BLOCK
                assertTrue(!safe())
                world.getBlockAt(0, 64, 0).type = Material.STONE
                world.getBlockAt(0, 66, 0).type = Material.STONE
                assertTrue(!safe())
            } finally { service.close() }
        } finally { MockBukkit.unmock() }
    }

    @Test
    fun `site search cancellation stops an outstanding chunk request`() {
        MockBukkit.mock()
        try {
            val plugin = MockBukkit.createMockPlugin()
            val world = Proxy.newProxyInstance(World::class.java.classLoader, arrayOf(World::class.java)) { _, method, _ ->
                when (method.name) {
                    "getName" -> "overworld-cancel"
                    "getSeed" -> 42L
                    "getChunkAtAsync" -> CompletableFuture<Chunk>()
                    else -> error("Unexpected search access: ${method.name}")
                }
            } as World
            val search = OverworldSiteSearch(plugin, world)
            val result = search.start()
            search.cancel()
            assertTrue(result.isCancelled)
        } finally { MockBukkit.unmock() }
    }

    @Test
    fun `site search samples chunk immediately before next-tick unload and skips unloadable candidates`() {
        val server = MockBukkit.mock()
        try {
            val plugin = MockBukkit.createMockPlugin()
            val pending = ArrayDeque<Pair<Pair<Int, Int>, CompletableFuture<Chunk>>>()
            val loadedDuringCallback = mutableSetOf<Pair<Int, Int>>()
            var firstCoord: Pair<Int, Int>? = null
            var maxInFlight = 0
            val grassBlock = blockStub(Material.GRASS_BLOCK)
            val world = Proxy.newProxyInstance(World::class.java.classLoader, arrayOf(World::class.java)) { _, method, args ->
                when (method.name) {
                    "getName" -> "overworld-unload"
                    "getSeed" -> 12345L
                    "getMinHeight" -> -64
                    "getChunkAtAsync" -> {
                        val pos = (args[0] as Int) to (args[1] as Int)
                        if (firstCoord == null) firstCoord = pos
                        val future = CompletableFuture<Chunk>()
                        pending.addLast(pos to future)
                        if (pending.size > maxInFlight) maxInFlight = pending.size
                        future
                    }
                    "isChunkLoaded" -> ((args[0] as Int) to (args[1] as Int)) in loadedDuringCallback
                    "getHighestBlockYAt" -> 70
                    "getBlockAt" -> grassBlock
                    "getBiome" -> Biome.PLAINS
                    else -> error("Unexpected search world access: ${method.name}")
                }
            } as World

            val result = OverworldSiteSearch(plugin, world).start()
            var ticks = 0
            while (!result.isDone && ticks < 5000) {
                // 逆順（LIFO）に完了させても決定順で受理されることも併せて検証します。
                val batch = pending.toList().reversed()
                pending.clear()
                for ((pos, future) in batch) {
                    val chunk = chunkStub(pos.first, pos.second)
                    // 最初の候補だけ永続的にアンロード状態を再現し、他は完了コールバック中のみ読込済み（実Paper挙動）にします。
                    if (pos != firstCoord) loadedDuringCallback += pos
                    future.complete(chunk)
                    // 完了直後に一時チケットが失効し、次tickにはアンロードされる実Paper挙動を再現します。
                    loadedDuringCallback.remove(pos)
                }
                server.scheduler.performOneTick()
                ticks++
            }
            assertTrue(result.isDone, "Site search should finish within tick limit")
            assertFalse(result.isCompletedExceptionally, "Site search must survive immediate post-callback unload")
            assertTrue(maxInFlight > 1, "Site search should pipeline multiple async chunk requests")
            assertEquals(9, result.join().size)
        } finally { MockBukkit.unmock() }
    }

    @Test
    fun `loadChunks pins chunks immediately inside completion callback and invokes done once`() {
        val server = MockBukkit.mock()
        try {
            val plugin = MockBukkit.createMockPlugin()
            val folder = Files.createTempDirectory("overworld-load-chunks")
            val pending = ArrayDeque<Pair<Pair<Int, Int>, CompletableFuture<Chunk>>>()
            val transientLoaded = mutableSetOf<Pair<Int, Int>>()
            val pinned = mutableSetOf<Pair<Int, Int>>()
            val removed = AtomicInteger()
            val world = Proxy.newProxyInstance(World::class.java.classLoader, arrayOf(World::class.java)) { _, method, args ->
                when (method.name) {
                    "getWorldFolder" -> folder.toFile()
                    "getUID" -> UUID.nameUUIDFromBytes("overworld-load-chunks".toByteArray())
                    "getName" -> "overworld-load-chunks"
                    "getSeed" -> 99L
                    "getChunkAtAsync" -> {
                        val pos = (args[0] as Int) to (args[1] as Int)
                        val future = CompletableFuture<Chunk>()
                        pending.addLast(pos to future)
                        future
                    }
                    "isChunkLoaded" -> {
                        val pos = (args[0] as Int) to (args[1] as Int)
                        pos in transientLoaded || pos in pinned
                    }
                    else -> error("Unexpected world access: ${method.name}")
                }
            } as World

            val service = OverworldWorldService(plugin, world) { fixtureAssets() }
            try {
                val method = OverworldWorldService::class.java.getDeclaredMethod(
                    "loadChunks",
                    OverworldSite::class.java,
                    CompletableFuture::class.java,
                    Function0::class.java,
                )
                method.isAccessible = true
                val opResult = CompletableFuture<OverworldLayout>()
                val doneCount = AtomicInteger()
                val site = OverworldSite(SITE_IDS.first(), 0, 70, 0, 1, "PLAINS")
                method.invoke(service, site, opResult, { doneCount.incrementAndGet(); Unit })

                var ticks = 0
                while (doneCount.get() == 0 && !opResult.isDone && ticks < 2000) {
                    // 同一tick内で全in-flightチャンクを一斉完了させ、done()の二重呼出と次tickアンロードを同時に検証します。
                    val batch = pending.toList()
                    pending.clear()
                    for ((pos, future) in batch) {
                        val chunk = chunkStub(pos.first, pos.second, onAdd = { pinned.add(pos) }, onRemove = {
                            pinned.remove(pos)
                            removed.incrementAndGet()
                            true
                        })
                        transientLoaded += pos
                        future.complete(chunk)
                        transientLoaded.remove(pos)
                    }
                    server.scheduler.performOneTick()
                    ticks++
                }
                // 余剰tickを進めても done() が二重発火しないことを確認します。
                repeat(5) { server.scheduler.performOneTick() }
                assertFalse(opResult.isCompletedExceptionally, "loadChunks must pin chunks before next-tick unload")
                assertEquals(1, doneCount.get(), "loadChunks must invoke done exactly once")
                assertTrue(pinned.isNotEmpty(), "loadChunks must retain plugin chunk tickets until released")
            } finally {
                service.close()
            }
            assertTrue(pinned.isEmpty(), "close must release all pinned chunk tickets")
            assertTrue(removed.get() > 0)
        } finally { MockBukkit.unmock() }
    }

    @Test
    fun `dungeon scanner pins each chunk immediately upon completion before sibling chunks finish`() {
        val server = MockBukkit.mock()
        try {
            val plugin = MockBukkit.createMockPlugin()
            val pending = ArrayDeque<Pair<Pair<Int, Int>, CompletableFuture<Chunk>>>()
            val transientLoaded = mutableSetOf<Pair<Int, Int>>()
            val pinned = mutableSetOf<Pair<Int, Int>>()
            val airBlock = blockStub(Material.AIR)
            val border = Proxy.newProxyInstance(WorldBorder::class.java.classLoader, arrayOf(WorldBorder::class.java)) { _, method, _ ->
                when (method.name) {
                    "isInside" -> true
                    else -> error("Unexpected border call: ${method.name}")
                }
            } as WorldBorder
            val world = Proxy.newProxyInstance(World::class.java.classLoader, arrayOf(World::class.java)) { _, method, args ->
                when (method.name) {
                    "getMinHeight" -> -64
                    "getMaxHeight" -> 320
                    "getWorldBorder" -> border
                    "getChunkAtAsync" -> {
                        val pos = (args[0] as Int) to (args[1] as Int)
                        val future = CompletableFuture<Chunk>()
                        pending.addLast(pos to future)
                        future
                    }
                    "isChunkLoaded" -> {
                        val pos = (args[0] as Int) to (args[1] as Int)
                        pos in transientLoaded || pos in pinned
                    }
                    "addPluginChunkTicket" -> pinned.add((args[0] as Int) to (args[1] as Int))
                    "removePluginChunkTicket" -> pinned.remove((args[0] as Int) to (args[1] as Int))
                    "getBlockAt" -> airBlock
                    else -> error("Unexpected dungeon world access: ${method.name}")
                }
            } as World

            val scanner = OverworldDungeonScanner(plugin, world)
            try {
                val bounds = DungeonInstanceBounds(minX = 1000, minY = 260, minZ = 1000)
                var scanResult: DungeonBoxScanResult? = null
                var scanError: Throwable? = null
                val operation = scanner.scan(bounds, 65536) { _, result, error ->
                    scanResult = result
                    scanError = error
                }
                // チャンクを1tickに1個ずつ時間差で完了させ、先行チャンクが兄弟待ちの間にアンロードされないことを検証します。
                assertTrue(pending.size > 1, "Dungeon bounds should span multiple chunks")
                while (pending.isNotEmpty()) {
                    val (pos, future) = pending.removeFirst()
                    transientLoaded += pos
                    future.complete(chunkStub(pos.first, pos.second))
                    transientLoaded.remove(pos)
                    assertTrue(pos in pinned, "Chunk $pos must be pinned immediately inside whenComplete")
                    server.scheduler.performOneTick()
                }
                repeat(20) {
                    if (scanResult != null || scanError != null) return@repeat
                    server.scheduler.performOneTick()
                }
                assertNull(scanError, "Scan should succeed without chunk unload error: $scanError")
                assertEquals(DungeonBoxScanResult.EMPTY, scanResult)
                operation.release()
                assertTrue(pinned.isEmpty(), "release must drop all dungeon chunk tickets")
            } finally {
                scanner.close()
            }
        } finally { MockBukkit.unmock() }
    }

    @Test
    fun `service close cancels search and releases retained chunk tickets`() {
        MockBukkit.mock()
        try {
            val plugin = MockBukkit.createMockPlugin()
            val folder = Files.createTempDirectory("overworld-close")
            val world = mockWorld(folder, "overworld-close")
            val service = OverworldWorldService(plugin, world) { fixtureAssets() }
            val search = OverworldSiteSearch(plugin, world)
            val removed = AtomicInteger()
            val chunk = Proxy.newProxyInstance(Chunk::class.java.classLoader, arrayOf(Chunk::class.java)) { _, method, _ ->
                when (method.name) {
                    "removePluginChunkTicket" -> { removed.incrementAndGet(); true }
                    else -> error("Unexpected chunk access: ${method.name}")
                }
            } as Chunk
            OverworldWorldService::class.java.getDeclaredField("siteSearch").apply { isAccessible = true; set(service, search) }
            @Suppress("UNCHECKED_CAST")
            val tickets = OverworldWorldService::class.java.getDeclaredField("tickets").apply { isAccessible = true }
                .get(service) as MutableList<Chunk>
            tickets += chunk
            service.close()
            assertTrue(search.start().isCancelled)
            assertEquals(1, removed.get())
            assertTrue(tickets.isEmpty())
        } finally { MockBukkit.unmock() }
    }

    @Test
    fun `completed journal skips asset loading and duplicate start is refused`() {
        val server = MockBukkit.mock()
        try {
            val plugin = MockBukkit.createMockPlugin()
            val folder = Files.createTempDirectory("overworld-completed")
            val world = mockWorld(folder, "overworld-completed")
            val current = journal(world, OverworldSiteStatus.READY).copy(status = OverworldBuildStatus.COMPLETED,
                sites = SITE_IDS.mapIndexed { index, id ->
                    OverworldSiteLayout(OverworldSite(id, index * 200, 70, index * 200, 1, "PLAINS"), OverworldSiteStatus.READY,
                        if (index == 0) mapOf("spawn" to OverworldPoint(0, 71, 0), "plaza_min" to OverworldPoint(-2, 70, -2),
                            "plaza_max" to OverworldPoint(2, 70, 2), "plaza_center" to OverworldPoint(0, 71, 0)) else emptyMap())
                }, portals = listOf(
                    OverworldPortal("ec:dungeon_novice_arena", "ec:area_ashwood_forest", OverworldPoint(200, 70, 200), OverworldPoint(201, 73, 201), OverworldPoint(200, 71, 202)),
                    OverworldPortal("ec:dungeon_aether_crucible", "ec:area_stargazer_hill", OverworldPoint(600, 70, 600), OverworldPoint(601, 73, 601), OverworldPoint(600, 71, 602)),
                    OverworldPortal("ec:dungeon_void_sanctum", "ec:area_void_sanctum", OverworldPoint(800, 70, 800), OverworldPoint(801, 73, 801), OverworldPoint(800, 71, 802)),
                    OverworldPortal("ec:dungeon_glacial_citadel", "ec:area_glacial_citadel", OverworldPoint(1600, 70, 1600), OverworldPoint(1601, 73, 1601), OverworldPoint(1600, 71, 1602)),
                ))
            OverworldLayoutStore(folder).save(current)
            var loads = 0
            val service = OverworldWorldService(plugin, world) { loads++; fixtureAssets() }
            try {
                val result = service.start()
                assertThrows<IllegalStateException> { service.start() }
                pump(server) { result.isDone }
                assertEquals(current, result.join())
                assertEquals(0, loads)
            } finally { service.close() }
        } finally { MockBukkit.unmock() }
    }

    @Test
    fun `missing schematic assets fail startup before any world mutation`() {
        val server = MockBukkit.mock()
        try {
            val plugin = MockBukkit.createMockPlugin()
            val folder = Files.createTempDirectory("overworld-assets-missing")
            val world = mockWorld(folder, "overworld-assets-missing")
            val service = OverworldWorldService(plugin, world) { OverworldAssets.load { null } }
            try {
                val result = service.start()
                pump(server) { result.isDone }
                assertTrue(result.isCompletedExceptionally)
                assertNull(service.current())
                assertTrue(Files.list(folder).use { !it.findAny().isPresent }, "asset failure must not create a journal")
            } finally {
                service.close()
            }
        } finally {
            MockBukkit.unmock()
        }
    }

    @Test
    fun `in progress journal is loaded without automatic rebuild and repair refuses generating site`() {
        val server = MockBukkit.mock()
        try {
            val plugin = MockBukkit.createMockPlugin()
            val folder = Files.createTempDirectory("overworld-recovery")
            val world = mockWorld(folder, "overworld-recovery")
            val initial = journal(world, OverworldSiteStatus.GENERATING)
            OverworldLayoutStore(folder).save(initial)
            var assetLoads = 0
            val service = OverworldWorldService(plugin, world) {
                assetLoads++
                fixtureAssets()
            }
            try {
                val result = service.start()
                pump(server) { result.isDone }
                assertEquals(initial, result.join())
                assertEquals(initial, service.current())
                assertEquals(1, assetLoads)
                assertThrows<IllegalStateException> { service.repair() }
                val reset = service.acknowledgeCleanup(SITE_IDS[1])
                pump(server) { reset.isDone }
                assertEquals(OverworldSiteStatus.PENDING, reset.join().sites[1].status)
                val repair = service.repair()
                assertTrue(repair.isCompletedExceptionally, "fixture world rejects chunk access after repair starts")
            } finally {
                service.close()
            }
        } finally {
            MockBukkit.unmock()
        }
    }

    private fun blockStub(material: Material): Block = Proxy.newProxyInstance(
        Block::class.java.classLoader, arrayOf(Block::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getType" -> material
            else -> error("Unexpected block access: ${method.name}")
        }
    } as Block

    private fun chunkStub(
        cx: Int,
        cz: Int,
        onAdd: () -> Boolean = { true },
        onRemove: () -> Boolean = { true },
    ): Chunk = Proxy.newProxyInstance(
        Chunk::class.java.classLoader, arrayOf(Chunk::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getX" -> cx
            "getZ" -> cz
            "addPluginChunkTicket" -> onAdd()
            "removePluginChunkTicket" -> onRemove()
            else -> error("Unexpected chunk access: ${method.name}")
        }
    } as Chunk

    private fun fixtureAssets() = OverworldAssets.load {
        SchematicData(1, 1, 1, listOf("minecraft:stone"), intArrayOf(0))
    }

    private fun mockWorld(folder: Path, name: String): World = Proxy.newProxyInstance(
        World::class.java.classLoader, arrayOf(World::class.java),
    ) { _, method, _ -> when (method.name) {
        "getWorldFolder" -> folder.toFile()
        "getUID" -> UUID.nameUUIDFromBytes(name.toByteArray())
        "getName" -> name
        "getSeed" -> 314159L
        else -> error("Unexpected world access in non-generating service path: ${method.name}")
    } } as World

    private fun journal(world: World, interrupted: OverworldSiteStatus): OverworldLayout {
        val states = listOf(OverworldSiteStatus.PENDING, interrupted) + List(7) { OverworldSiteStatus.PENDING }
        return OverworldLayout(
            worldId = world.uid.toString(), worldName = world.name, seed = world.seed,
            status = OverworldBuildStatus.IN_PROGRESS,
            sites = SITE_IDS.mapIndexed { index, id -> OverworldSiteLayout(OverworldSite(id, index * 200, 70, index * 200, 1, "PLAINS"), states[index]) },
        )
    }

    private fun pump(server: ServerMock, ready: () -> Boolean) {
        val until = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < until && !ready()) {
            server.scheduler.performOneTick()
            Thread.sleep(10)
        }
        check(ready()) { "Overworld service future did not complete" }
    }

    private companion object {
        val SITE_IDS = listOf("ec:area_lumina_port", "ec:area_ashwood_forest", "ec:area_weeping_mine", "ec:area_stargazer_hill",
            "ec:area_void_sanctum", "ec:area_frostpeak_outpost", "ec:area_glacier_ridge", "ec:area_crystalline_cavern", "ec:area_glacial_citadel")
    }
}
