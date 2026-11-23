package net.nakaymi.etherealchronicle.paper

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.nakaymi.etherealchronicle.core.ActiveWorldProjection
import net.nakaymi.etherealchronicle.core.OverworldDungeonInstanceLayout
import net.nakaymi.etherealchronicle.core.OverworldDungeonPortal
import net.nakaymi.etherealchronicle.core.OverworldRepairResult
import net.nakaymi.etherealchronicle.core.OverworldSiteAnchor
import net.nakaymi.etherealchronicle.core.OverworldSiteRegistry
import net.nakaymi.etherealchronicle.core.OverworldSiteStatus
import net.nakaymi.etherealchronicle.data.DungeonReturnRecord
import net.nakaymi.etherealchronicle.data.DungeonReturnStore
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.PlayerMock
import org.mockbukkit.mockbukkit.world.WorldMock
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * オーバーワールド統合ダンジョンの主要回帰テストです。
 * スロット再利用・撤去・タイムアウト・破損ジャーナル非ブロック・視点回転復旧・移動凍結タイムアウト・権限境界を検証します。
 */
class OverworldDungeonLifecycleTest {
    private val plain = PlainTextComponentSerializer.plainText()

    /** MockBukkit で未実装の getChunkAtAsync / chunkTicket を Paper 相当の挙動で補うテストワールドです。 */
    private class AsyncChunkWorldMock(name: String) : WorldMock(Material.AIR, -64, 320, 0) {
        private val tickets = mutableMapOf<Pair<Int, Int>, MutableSet<Plugin>>()
        val generatedChunks = mutableSetOf<Pair<Int, Int>>()
        val stalledChunks = mutableSetOf<Pair<Int, Int>>()

        init {
            this.name = name
        }

        override fun getChunkAtAsync(x: Int, z: Int, gen: Boolean, urgent: Boolean): CompletableFuture<Chunk> {
            val key = x to z
            if (key in stalledChunks) {
                return CompletableFuture()
            }
            if (!gen && !isChunkLoaded(x, z) && key !in generatedChunks) {
                return CompletableFuture.completedFuture(null)
            }
            generatedChunks += key
            val chunk = getChunkAt(x, z)
            loadChunk(x, z)
            return CompletableFuture.completedFuture(chunk)
        }

        override fun addPluginChunkTicket(x: Int, z: Int, plugin: Plugin): Boolean {
            return tickets.getOrPut(x to z) { mutableSetOf() }.add(plugin)
        }

        override fun removePluginChunkTicket(x: Int, z: Int, plugin: Plugin): Boolean {
            val set = tickets[x to z] ?: return false
            val removed = set.remove(plugin)
            if (set.isEmpty()) tickets.remove(x to z)
            return removed
        }
    }

    /** MockBukkit が未実装な装飾 block state を避けつつ、実寸 55x34x55 の石レンガ床アリーナを生成します。 */
    private fun testArenaSchematic(): SchematicData {
        val w = OverworldDungeonInstanceLayout.ARENA_WIDTH
        val h = OverworldDungeonInstanceLayout.ARENA_HEIGHT
        val blocks = IntArray(w * h * w)
        val floorLocalY = OverworldDungeonInstanceLayout.FLOOR_OFFSET
        for (z in 0 until w) {
            for (x in 0 until w) {
                blocks[(floorLocalY * w + z) * w + x] = 1
            }
        }
        return SchematicData(w, h, w, listOf("minecraft:air", "minecraft:stone_bricks"), blocks)
    }

    @Suppress("UNCHECKED_CAST")
    private fun seedTestSchematic(controller: DungeonController, schematic: SchematicData = testArenaSchematic()) {
        val field = DungeonController::class.java.getDeclaredField("loadedSchematics").apply { isAccessible = true }
        val map = field.get(controller) as MutableMap<String, SchematicData>
        map[WorldSchematicLayout.dungeonPath("ec:dungeon_novice_arena")!!] = schematic
    }

    private fun ServerMock.pump(timeoutMs: Long = 10_000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            repeat(5) {
                scheduler.performOneTick()
                if (cond()) return true
            }
            Thread.sleep(2)
        }
        return cond()
    }

    private fun prepareSafeGround(world: AsyncChunkWorldMock, centerX: Int, groundY: Int, centerZ: Int, radius: Int = 4) {
        for (x in (centerX - radius)..(centerX + radius)) {
            for (z in (centerZ - radius)..(centerZ + radius)) {
                val cx = x shr 4
                val cz = z shr 4
                world.generatedChunks += cx to cz
                world.loadChunk(cx, cz)
                world.getBlockAt(x, groundY, z).type = Material.STONE
                world.getBlockAt(x, groundY + 1, z).type = Material.AIR
                world.getBlockAt(x, groundY + 2, z).type = Material.AIR
            }
        }
    }

    private fun setupOverworldPlugin(
        server: ServerMock,
        worldName: String = "overworld_lifecycle",
        baseX: Int = 512,
        baseY: Int = 220,
        baseZ: Int = 512,
        spacing: Int = 128,
        clock: () -> Long = System::currentTimeMillis,
        customSites: List<OverworldSiteAnchor>? = null,
        registryReadyOverride: Boolean? = null,
        seedSchematic: Boolean = true,
        beforeControllerInit: (RpgPlugin) -> Unit = {},
    ): Triple<RpgPlugin, AsyncChunkWorldMock, DungeonController> {
        val plugin = MockBukkit.load(RpgPlugin::class.java)
        assertTrue(server.pump { plugin.isReadyForGameplay }, "RpgPlugin did not become ready")
        val world = AsyncChunkWorldMock(worldName)
        server.addWorld(world)
        prepareSafeGround(world, 10, 64, 10)
        prepareSafeGround(world, 40, 64, 40)

        val portal = OverworldDungeonPortal(
            dungeonId = "ec:dungeon_novice_arena",
            areaId = "ec:area_lumina_port",
            centerX = 14,
            centerY = 66,
            centerZ = 10,
            exitX = 10,
            exitY = 65,
            exitZ = 10,
        )
        val sites = customSites ?: listOf(
            OverworldSiteAnchor(
                areaId = "ec:area_lumina_port",
                centerX = 10,
                surfaceY = 65,
                centerZ = 10,
                biomeKey = "PLAINS",
                status = OverworldSiteStatus.READY,
                dungeonId = portal.dungeonId,
                portalCenterX = portal.centerX,
                portalCenterY = portal.centerY,
                portalCenterZ = portal.centerZ,
                portalExitX = portal.exitX,
                portalExitY = portal.exitY,
                portalExitZ = portal.exitZ,
                plazaSpawnX = 10,
                plazaSpawnY = 65,
                plazaSpawnZ = 10,
            ),
        )
        val registry = object : OverworldSiteRegistry {
            private val byArea = sites.associateBy { it.areaId }
            override fun activeWorldName(): String = world.name
            override fun isOverworldMode(): Boolean = true
            override fun isReady(): Boolean = registryReadyOverride ?: sites.all { it.status == OverworldSiteStatus.READY }
            override fun siteForArea(areaId: String): OverworldSiteAnchor? = byArea[areaId]
            override fun allSites(): List<OverworldSiteAnchor> = sites
            override fun portalForDungeon(dungeonId: String): OverworldDungeonPortal? =
                portal.takeIf { it.dungeonId == dungeonId }
            override fun allPortals(): List<OverworldDungeonPortal> = listOf(portal)
            override fun triggerRepair(): OverworldRepairResult = OverworldRepairResult.UNAVAILABLE
        }
        plugin.activeWorldProjection = ActiveWorldProjection(registry, plugin.snapshot.world.areas.values) { x, z ->
            plugin.resolveProjectionSurfaceY(world, x, z)
        }
        plugin.config.set("world.dungeon.base_x", baseX)
        plugin.config.set("world.dungeon.base_y", baseY)
        plugin.config.set("world.dungeon.base_z", baseZ)
        plugin.config.set("world.dungeon.spacing", spacing)

        val adventure = RpgPlugin::class.java.getDeclaredField("adventure").apply { isAccessible = true }.get(plugin) as AdventureController
        val enemies = RpgPlugin::class.java.getDeclaredField("enemies").apply { isAccessible = true }.get(plugin) as EnemyController
        plugin.dungeons.shutdown()
        beforeControllerInit(plugin)
        val controller = DungeonController(plugin, adventure, enemies, clock)
        if (seedSchematic) seedTestSchematic(controller)
        plugin.dungeons = controller
        assertTrue(server.pump { controller.isJournalReady() }, "Return journal did not become ready")
        return Triple(plugin, world, controller)
    }

    private fun joinPlayer(server: ServerMock, plugin: RpgPlugin, world: World, name: String): PlayerMock {
        val player = PlayerMock(server, name, UUID.nameUUIDFromBytes("ow-$name".toByteArray()))
        server.addPlayer(player)
        assertTrue(server.pump { player.uniqueId in plugin.profiles }, "Profile did not load for $name")
        val noviceUnlock = plugin.snapshot.world.dungeons.getValue("ec:dungeon_novice_arena").unlockFlag
        plugin.profiles.getValue(player.uniqueId).apply {
            classId = "ec:class_warrior"
            level = 20
            if (noviceUnlock.isNotBlank()) flags += noviceUnlock
        }
        player.teleport(Location(world, 10.5, 65.0, 10.5))
        return player
    }

    @Test
    fun `overworld entry does not double-count activeCount, normal leave clears arena blocks and reuses slot zero`() {
        val server = MockBukkit.mock()
        try {
            val (plugin, world, dungeons) = setupOverworldPlugin(server)
            val player = joinPlayer(server, plugin, world, "SlotReuse")
            val layout = OverworldDungeonInstanceLayout(baseX = 512, baseY = 220, baseZ = 512, spacing = 128)
            val slot0Center = layout.center(0)
            val slot0Bounds = layout.bounds(0)

            // 1回目入場
            dungeons.enter(player, "ec:dungeon_novice_arena")
            assertTrue(server.pump(10_000) { dungeons.containsPlayer(player.uniqueId) }, "Player did not enter dungeon")
            // reservedSlots と instances の二重計上がないこと
            assertEquals(1, dungeons.activeCount())
            // 空中アリーナが配置されていること（床面に石レンガが存在）
            assertEquals(Material.STONE_BRICKS, world.getBlockAt(slot0Center.x, slot0Center.y, slot0Center.z).type)

            // 通常退出 -> 安全帰還・アリーナ撤去・スロット解放
            dungeons.leave(player)
            assertTrue(
                server.pump(10_000) { dungeons.activeCount() == 0 && !dungeons.containsPlayer(player.uniqueId) },
                "Dungeon did not close and clean up after normal leave (activeCount=${dungeons.activeCount()})",
            )
            assertTrue(player.location.y < 100.0, "Player was not returned to ground: ${player.location}")
            // 撤去後はスロット 0 範囲が AIR に戻っていること
            val remainingNonAir = (slot0Bounds.minX..slot0Bounds.maxX).any { x ->
                (slot0Bounds.minY..slot0Bounds.maxY).any { y ->
                    !world.getBlockAt(x, y, slot0Center.z).type.isAir
                }
            }
            assertFalse(remainingNonAir, "Slot 0 blocks were not cleared to AIR on normal close")

            // 2回目入場 -> 通常退出後に再ログイン不要で即再入場でき、スロット 0 が再利用されること
            dungeons.enter(player, "ec:dungeon_novice_arena")
            assertTrue(server.pump(10_000) { dungeons.containsPlayer(player.uniqueId) }, "Player could not re-enter after normal leave")
            assertEquals(1, dungeons.activeCount())
            assertTrue(
                kotlin.math.abs(player.location.x - slot0Center.x) < 30.0,
                "Expected slot 0 to be reused, but player was placed at ${player.location}",
            )
        } finally {
            MockBukkit.unmock()
        }
    }

    @Test
    fun `concurrent entries reserve distinct slots and timeout returns everyone without stranding`() {
        val server = MockBukkit.mock()
        try {
            val (plugin, world, dungeons) = setupOverworldPlugin(server)
            val p1 = joinPlayer(server, plugin, world, "SoloA")
            val p2 = joinPlayer(server, plugin, world, "SoloB")

            // 同時入場を同一 tick 内で要求しても別々のスロットを原子的予約すること
            dungeons.enter(p1, "ec:dungeon_novice_arena")
            dungeons.enter(p2, "ec:dungeon_novice_arena")
            assertTrue(
                server.pump(10_000) { dungeons.containsPlayer(p1.uniqueId) && dungeons.containsPlayer(p2.uniqueId) },
                "Both concurrent entries should succeed",
            )
            assertEquals(2, dungeons.activeCount())
            assertTrue(
                kotlin.math.abs(p1.location.x - p2.location.x) >= 100.0,
                "Concurrent entries collided on the same slot: p1=${p1.location}, p2=${p2.location}",
            )

            // タイムアウト経過後に tick() で全員が取り残されず地上へ帰還し、枠も撤去解放されること
            val ticksNeeded = ((plugin.snapshot.world.tuning.instanceTimeout / PaperTaskIntervals.PLAYER_UPDATE_TICKS) + 2).toInt()
            repeat(ticksNeeded) { dungeons.tick() }
            assertTrue(
                server.pump(10_000) {
                    dungeons.activeCount() == 0 && !dungeons.containsPlayer(p1.uniqueId) && !dungeons.containsPlayer(p2.uniqueId)
                },
                "Timeout did not cleanly return players and release slots (active=${dungeons.activeCount()})",
            )
            assertTrue(p1.location.y < 100.0, "p1 stranded in sky after timeout: ${p1.location}")
            assertTrue(p2.location.y < 100.0, "p2 stranded in sky after timeout: ${p2.location}")
        } finally {
            MockBukkit.unmock()
        }
    }

    @Test
    fun `placement failure across all slots releases reservations and notifies the player`() {
        val server = MockBukkit.mock()
        try {
            // seedSchematic = false にすると MockBukkit 未対応ブロック状態で全スロット配置失敗パスを検証できる
            val (plugin, world, dungeons) = setupOverworldPlugin(server, seedSchematic = false)
            val player = joinPlayer(server, plugin, world, "PlaceFail")
            while (player.nextComponentMessage() != null) { /* 既存メッセージをクリア */ }

            dungeons.enter(player, "ec:dungeon_novice_arena")
            assertTrue(
                server.pump(10_000) {
                    val msgs = generateSequence { player.nextComponentMessage() }.map(plain::serialize).toList()
                    msgs.isNotEmpty()
                },
                "Placement failure must notify the player rather than silently aborting",
            )
            assertEquals(0, dungeons.activeCount(), "All reserved slots must be released after placement failures")
            assertFalse(dungeons.containsPlayer(player.uniqueId))
        } finally {
            MockBukkit.unmock()
        }
    }

    @Test
    fun `corrupted return journal and backup do not block login and safely disable dungeon entry`() {
        val server = MockBukkit.mock()
        try {
            val (plugin, world, dungeons) = setupOverworldPlugin(server, beforeControllerInit = { target ->
                val journal = target.dataFolder.toPath().resolve("dungeon-returns.json")
                val backup = target.dataFolder.toPath().resolve("dungeon-returns.json.bak")
                Files.createDirectories(journal.parent)
                Files.writeString(journal, "{corrupt-primary")
                Files.writeString(backup, "{corrupt-backup")
            })
            var loginUnblocked = false
            dungeons.whenJournalReady { loginUnblocked = true }
            assertTrue(loginUnblocked, "Corrupted journal must unblock journalWaiters for normal login")

            // 破損ファイルが退避されていること
            val quarantined = Files.list(plugin.dataFolder.toPath()).use { stream ->
                stream.filter { it.fileName.toString().contains(".corrupt-") }.toList()
            }
            assertEquals(2, quarantined.size)

            // 通常ログインは機能しつつ、ダンジョン入場のみ fail-closed で拒否されること
            val player = joinPlayer(server, plugin, world, "NormalLogin")
            val refusal = assertThrows(IllegalStateException::class.java) {
                dungeons.enter(player, "ec:dungeon_novice_arena")
            }
            assertEquals("ec.error.world_unavailable", refusal.message)
            assertEquals(0, dungeons.activeCount())
            assertFalse(dungeons.containsPlayer(player.uniqueId))
        } finally {
            MockBukkit.unmock()
        }
    }

    @Test
    fun `recoverOverworld succeeds after camera rotation and pending move freeze times out to safe fallback`() {
        val server = MockBukkit.mock()
        try {
            var now = 5_000_000L
            val rotatedId = UUID.nameUUIDFromBytes("ow-CamRotate".toByteArray())
            val stuckId = UUID.nameUUIDFromBytes("ow-StuckFreeze".toByteArray())

            val (plugin, world, dungeons) = setupOverworldPlugin(
                server,
                clock = { now },
                beforeControllerInit = { target ->
                    val journal = target.dataFolder.toPath().resolve("dungeon-returns.json")
                    DungeonReturnStore(journal).use { store ->
                        store.putAll(
                            mapOf(
                                rotatedId to DungeonReturnRecord(
                                    dungeonId = "ec:dungeon_novice_arena",
                                    world = "overworld_lifecycle",
                                    x = 10.5,
                                    y = 65.0,
                                    z = 10.5,
                                    yaw = 0f,
                                    pitch = 0f,
                                    slotX = 512,
                                    slotY = 220,
                                    slotZ = 512,
                                ),
                                stuckId to DungeonReturnRecord(
                                    dungeonId = "ec:dungeon_novice_arena",
                                    world = "overworld_lifecycle",
                                    x = 10.5,
                                    y = 65.0,
                                    z = 10.5,
                                    yaw = 0f,
                                    pitch = 0f,
                                    slotX = 640,
                                    slotY = 220,
                                    slotZ = 512,
                                ),
                            ),
                        ).get(5, TimeUnit.SECONDS)
                    }
                },
            )

            // 1. ログイン直後に視点回転しても帰還救出がスキップされないこと
            val rotated = PlayerMock(server, "CamRotate", rotatedId)
            server.addPlayer(rotated)
            assertTrue(server.pump { rotated.uniqueId in plugin.profiles })
            rotated.teleport(Location(world, 512.5, 221.0, 512.5, 0f, 0f))
            dungeons.recover(rotated)
            // 直後に視点（yaw/pitch）だけ回転させる
            rotated.teleport(Location(world, 512.5, 221.0, 512.5, 145f, -35f))
            assertTrue(
                server.pump(5000) { rotated.location.y < 100.0 && !dungeons.hasPendingReturn(rotated.uniqueId) },
                "Camera rotation skipped recoverOverworld: loc=${rotated.location}, pending=${dungeons.hasPendingReturn(rotated.uniqueId)}",
            )

            // 2. チャンク応答が停滞して pending 凍結が 10 秒を超えたらフォールバック先へ送ってデッドロックを解除すること
            val skyPos = Location(world, 640.5, 221.0, 512.5)
            world.stalledChunks += (skyPos.blockX shr 4) to (skyPos.blockZ shr 4)
            val stuck = PlayerMock(server, "StuckFreeze", stuckId)
            stuck.teleport(skyPos)
            server.addPlayer(stuck)
            assertTrue(server.pump { stuck.uniqueId in plugin.profiles })
            stuck.teleport(skyPos)
            val firstMove = PlayerMoveEvent(stuck, skyPos, skyPos.clone().add(1.0, 0.0, 0.0))
            dungeons.move(firstMove)
            assertEquals(skyPos, firstMove.to, "Initial pending move must freeze player")

            now += 11_000L
            val afterTimeoutMove = PlayerMoveEvent(stuck, skyPos, skyPos.clone().add(1.0, 0.0, 0.0))
            dungeons.move(afterTimeoutMove)
            assertTrue(afterTimeoutMove.to.y < 100.0, "Freeze timeout must redirect move to safe ground: ${afterTimeoutMove.to}")
            assertTrue(
                server.pump(5000) { !dungeons.hasPendingReturn(stuck.uniqueId) },
                "Freeze timeout did not clear pending return record",
            )
        } finally {
            MockBukkit.unmock()
        }
    }

    @Test
    fun `travel enforces admin permission and rejects in-dungeon use, sites distinguishes FAILED from PENDING, and surfaceY skips canopy`() {
        val server = MockBukkit.mock()
        try {
            val mixedSites = listOf(
                OverworldSiteAnchor(
                    areaId = "ec:area_lumina_port",
                    centerX = 10,
                    surfaceY = 65,
                    centerZ = 10,
                    biomeKey = "PLAINS",
                    status = OverworldSiteStatus.READY,
                    dungeonId = "ec:dungeon_novice_arena",
                    portalCenterX = 14,
                    portalCenterY = 66,
                    portalCenterZ = 10,
                    portalExitX = 10,
                    portalExitY = 65,
                    portalExitZ = 10,
                    plazaSpawnX = 10,
                    plazaSpawnY = 65,
                    plazaSpawnZ = 10,
                ),
                OverworldSiteAnchor(
                    areaId = "ec:area_ashwood_forest",
                    centerX = 0,
                    surfaceY = 0,
                    centerZ = 0,
                    biomeKey = "",
                    status = OverworldSiteStatus.PENDING,
                ),
                OverworldSiteAnchor(
                    areaId = "ec:area_weeping_mine",
                    centerX = 0,
                    surfaceY = 0,
                    centerZ = 0,
                    biomeKey = "",
                    status = OverworldSiteStatus.FAILED,
                ),
            )
            val (plugin, world, dungeons) = setupOverworldPlugin(
                server,
                customSites = mixedSites,
                registryReadyOverride = true,
            )
            val commands = SimulationCommandController(plugin)

            // 1. 非管理者の /ec travel は拒否される
            val regular = joinPlayer(server, plugin, world, "RegularUser")
            regular.isOp = false
            val noPerm = assertThrows(IllegalArgumentException::class.java) {
                commands.travel(regular, "lumina_port")
            }
            assertEquals("ec.command.no_permission", noPerm.message)

            // 2. 管理者でもダンジョン内からは /ec travel できない
            val admin = joinPlayer(server, plugin, world, "AdminUser")
            admin.isOp = true
            dungeons.enter(admin, "ec:dungeon_novice_arena")
            assertTrue(server.pump(10_000) { dungeons.containsPlayer(admin.uniqueId) })
            val insideError = assertThrows(IllegalArgumentException::class.java) {
                commands.travel(admin, "lumina_port")
            }
            assertEquals("ec.error.dungeon_already_inside", insideError.message)

            // ダンジョン外に出れば管理者は /ec travel できる
            dungeons.leave(admin)
            assertTrue(server.pump(10_000) { dungeons.activeCount() == 0 && !dungeons.containsPlayer(admin.uniqueId) })
            admin.teleport(Location(world, 40.5, 65.0, 40.5))
            commands.travel(admin, "lumina_port")
            assertTrue(
                server.pump(5000) { kotlin.math.abs(admin.location.x - 10.5) < 2.0 },
                "Admin travel did not teleport to Lumina: ${admin.location}",
            )

            // 3. /ec sites が FAILED と PENDING を明確に区別して表示する
            while (admin.nextComponentMessage() != null) { /* 既存メッセージをクリア */ }
            commands.sites(admin, listOf("sites"))
            val siteLines = generateSequence { admin.nextComponentMessage() }.map(plain::serialize).toList()
            val pendingLine = siteLines.first { it.contains("ec:area_ashwood_forest") }
            val failedLine = siteLines.first { it.contains("ec:area_weeping_mine") }
            assertTrue(pendingLine != failedLine)
            assertFalse(
                failedLine.contains(pendingLine.substringAfter(": ")),
                "FAILED line must be visually distinct from PENDING: pending='$pendingLine', failed='$failedLine'",
            )

            // 4. resolveProjectionSurfaceY が樹冠の葉・丸太の上ではなく地表を返すこと
            val tx = 25
            val tz = 25
            world.getBlockAt(tx, 64, tz).type = Material.GRASS_BLOCK
            world.getBlockAt(tx, 65, tz).type = Material.OAK_LOG
            world.getBlockAt(tx, 66, tz).type = Material.OAK_LOG
            world.getBlockAt(tx, 67, tz).type = Material.OAK_LEAVES
            world.getBlockAt(tx, 68, tz).type = Material.OAK_LEAVES
            assertEquals(65, plugin.resolveProjectionSurfaceY(world, tx, tz))
        } finally {
            MockBukkit.unmock()
        }
    }
}
