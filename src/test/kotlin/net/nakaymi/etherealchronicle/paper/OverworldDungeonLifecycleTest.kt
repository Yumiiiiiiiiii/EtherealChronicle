package net.nakaymi.etherealchronicle.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.nakaymi.etherealchronicle.core.ActiveWorldProjection
import net.nakaymi.etherealchronicle.core.DungeonSlotState
import net.nakaymi.etherealchronicle.core.OverworldDungeonInstanceLayout
import net.nakaymi.etherealchronicle.core.OverworldDungeonPortal
import net.nakaymi.etherealchronicle.core.OverworldRepairResult
import net.nakaymi.etherealchronicle.core.OverworldSiteAnchor
import net.nakaymi.etherealchronicle.core.OverworldSiteRegistry
import net.nakaymi.etherealchronicle.core.OverworldSiteStatus
import net.nakaymi.etherealchronicle.core.Position
import net.nakaymi.etherealchronicle.core.selectFishingSpot
import net.nakaymi.etherealchronicle.data.DungeonOwnedSlot
import net.nakaymi.etherealchronicle.data.DungeonReturnRecord
import net.nakaymi.etherealchronicle.data.DungeonReturnStore
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
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
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import kotlin.math.floor

/**
 * オーバーワールド統合ダンジョンの主要回帰テストです。
 * スロット再利用・撤去・タイムアウト・破損ジャーナル非ブロック・視点回転復旧・移動凍結タイムアウト・権限境界に加え、
 * 切断・テレポート離脱・孤立スロット回収・配置途中ロールバック・強制避難・水面投影・採取保護索引を検証します。
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
            val preparingMsg = plain.serialize(
                plugin.message(player, "ec.dungeon.preparing", "name" to plugin.contentName(player, "ec:dungeon_novice_arena")),
            )
            val failureMsg = plain.serialize(plugin.message(player, "ec.error.dungeon_full"))
            val observed = mutableListOf<String>()
            assertTrue(
                server.pump(10_000) {
                    observed += generateSequence { player.nextComponentMessage() }.map(plain::serialize).toList()
                    failureMsg in observed
                },
                "Placement failure must notify the player rather than silently aborting (observed=$observed)",
            )
            assertTrue(preparingMsg in observed, "Expected preparing notice before failure (observed=$observed)")
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

    @Test
    fun `respawn after quitting at the death screen returns the player to the entrance and clears the pending record`() {
        val server = MockBukkit.mock()
        try {
            val (plugin, world, dungeons) = setupOverworldPlugin(server)
            val player = joinPlayer(server, plugin, world, "DeathScreenQuit")
            dungeons.enter(player, "ec:dungeon_novice_arena")
            assertTrue(server.pump(10_000) { dungeons.containsPlayer(player.uniqueId) }, "Player did not enter dungeon")

            // 死亡画面のまま切断: インスタンスから外れるが、帰還記録は残る
            dungeons.quit(PlayerQuitEvent(player, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED))
            assertFalse(dungeons.containsPlayer(player.uniqueId))
            assertTrue(dungeons.hasPendingReturn(player.uniqueId))

            // 再接続後に「復活」するとき、記録した戻り先（入口の出口）へ復活し、記録が解除される
            val respawn = PlayerRespawnEvent(player, player.location, false, false)
            dungeons.respawn(respawn)
            assertTrue(respawn.respawnLocation.y < 100.0, "respawn must leave the sky arena: ${respawn.respawnLocation}")
            assertTrue(
                server.pump(10_000) { !dungeons.hasPendingReturn(player.uniqueId) },
                "pending return record was not cleared on respawn",
            )
        } finally {
            MockBukkit.unmock()
        }
    }

    @Test
    fun `overworld quit protects slot until reconnect rescue then cleans up and reuses slot zero`() {
        val server = MockBukkit.mock()
        try {
            val (plugin, world, dungeons) = setupOverworldPlugin(server)
            val player = joinPlayer(server, plugin, world, "QuitRescue")
            val layout = OverworldDungeonInstanceLayout(baseX = 512, baseY = 220, baseZ = 512, spacing = 128)
            val slot0Center = layout.center(0)
            while (player.nextComponentMessage() != null) { /* 既存メッセージをクリア */ }

            // 入場開始時に準備メッセージが通知されること
            dungeons.enter(player, "ec:dungeon_novice_arena")
            val expectedPrep = plain.serialize(
                plugin.message(player, "ec.dungeon.preparing", "name" to plugin.contentName(player, "ec:dungeon_novice_arena")),
            )
            val initialMsgs = generateSequence { player.nextComponentMessage() }.map(plain::serialize).toList()
            assertTrue(
                initialMsgs.any { it == expectedPrep },
                "Expected preparing message '$expectedPrep', got $initialMsgs",
            )
            assertTrue(server.pump(10_000) { dungeons.containsPlayer(player.uniqueId) }, "Player did not enter dungeon")
            assertEquals(DungeonSlotState.ACTIVE, dungeons.slotState(0))
            assertEquals(Material.STONE_BRICKS, world.getBlockAt(slot0Center.x, slot0Center.y, slot0Center.z).type)

            // 切断時は床を残したまま PROTECTED 状態へ移行し、未帰還記録がある間は再利用・撤去しないこと
            dungeons.quit(PlayerQuitEvent(player, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED))
            assertFalse(dungeons.containsPlayer(player.uniqueId))
            assertEquals(DungeonSlotState.PROTECTED, dungeons.slotState(0))
            assertEquals(0, dungeons.activeCount(), "PROTECTED slot with offline player must not block activeCount/reload")
            assertTrue(dungeons.hasPendingReturn(player.uniqueId))
            assertEquals(
                Material.STONE_BRICKS,
                world.getBlockAt(slot0Center.x, slot0Center.y, slot0Center.z).type,
                "Floor blocks must stay intact while return record is pending",
            )

            // 再接続救出で最後の帰還記録が消えた瞬間に自動撤去が走り、スロット 0 が再利用可能になること
            dungeons.recover(player)
            assertTrue(
                server.pump(10_000) {
                    player.location.y < 100.0 &&
                        !dungeons.hasPendingReturn(player.uniqueId) &&
                        dungeons.slotState(0) == null &&
                        dungeons.activeCount() == 0
                },
                "Reconnect rescue did not clean up slot 0 (loc=${player.location}, state=${dungeons.slotState(0)})",
            )
            assertTrue(
                world.getBlockAt(slot0Center.x, slot0Center.y, slot0Center.z).type.isAir,
                "Slot 0 floor was not cleared to AIR after reconnect rescue",
            )

            // 再入場でスロット 0 が再利用されること
            dungeons.enter(player, "ec:dungeon_novice_arena")
            assertTrue(server.pump(10_000) { dungeons.containsPlayer(player.uniqueId) }, "Player could not re-enter after quit rescue")
            assertTrue(
                kotlin.math.abs(player.location.x - slot0Center.x) < 30.0,
                "Expected slot 0 reuse after quit cleanup, but player landed at ${player.location}",
            )
        } finally {
            MockBukkit.unmock()
        }
    }

    @Test
    fun `safe ground teleport exit acknowledges return immediately and cleans arena blocks`() {
        val server = MockBukkit.mock()
        try {
            val (plugin, world, dungeons) = setupOverworldPlugin(server)
            val player = joinPlayer(server, plugin, world, "SafeTeleportExit")
            val layout = OverworldDungeonInstanceLayout(baseX = 512, baseY = 220, baseZ = 512, spacing = 128)
            val slot0Center = layout.center(0)

            dungeons.enter(player, "ec:dungeon_novice_arena")
            assertTrue(server.pump(10_000) { dungeons.containsPlayer(player.uniqueId) }, "Player did not enter dungeon")
            while (player.nextComponentMessage() != null) { /* 既存メッセージをクリア */ }

            // 地上安全座標へ外部テレポートで離脱しても、即座に帰還記録が解除されアリーナが撤去されること
            val from = player.location.clone()
            val safeGround = Location(world, 40.5, 65.0, 40.5)
            player.teleport(safeGround)
            dungeons.teleport(PlayerTeleportEvent(player, from, safeGround, PlayerTeleportEvent.TeleportCause.PLUGIN))

            assertTrue(
                server.pump(10_000) {
                    !dungeons.containsPlayer(player.uniqueId) &&
                        !dungeons.hasPendingReturn(player.uniqueId) &&
                        dungeons.slotState(0) == null &&
                        dungeons.activeCount() == 0
                },
                "Safe teleport exit did not clear record and clean slot 0 (pending=${dungeons.hasPendingReturn(player.uniqueId)}, state=${dungeons.slotState(0)})",
            )
            assertTrue(
                world.getBlockAt(slot0Center.x, slot0Center.y, slot0Center.z).type.isAir,
                "Slot 0 blocks were not cleaned after safe ground teleport exit",
            )
            val reloginPrompt = plain.serialize(plugin.message(player, "ec.dungeon.relogin_required"))
            val msgs = generateSequence { player.nextComponentMessage() }.map(plain::serialize).toList()
            assertFalse(msgs.any { it == reloginPrompt }, "Safe ground teleport must not require relogin: $msgs")
        } finally {
            MockBukkit.unmock()
        }
    }

    @Test
    fun `startup automatically cleans orphaned owned slot with no pending return records`() {
        val server = MockBukkit.mock()
        try {
            val layout = OverworldDungeonInstanceLayout(baseX = 512, baseY = 220, baseZ = 512, spacing = 128)
            val slot0Center = layout.center(0)
            val slot0Bounds = layout.bounds(0)
            val (plugin, world, dungeons) = setupOverworldPlugin(
                server,
                beforeControllerInit = { target ->
                    val w = server.getWorld("overworld_lifecycle") as AsyncChunkWorldMock
                    val cx = slot0Center.x shr 4
                    val cz = slot0Center.z shr 4
                    w.generatedChunks += cx to cz
                    w.loadChunk(cx, cz)
                    w.getBlockAt(slot0Center.x, slot0Center.y, slot0Center.z).type = Material.STONE_BRICKS
                    w.getBlockAt(slot0Center.x + 1, slot0Center.y, slot0Center.z).type = Material.STONE_BRICKS

                    val journal = target.dataFolder.toPath().resolve("dungeon-returns.json")
                    DungeonReturnStore(journal).use { store ->
                        store.putOwnedSlot(
                            DungeonOwnedSlot(
                                slot = 0,
                                world = w.name,
                                dungeonId = "ec:dungeon_novice_arena",
                                minX = slot0Bounds.minX,
                                minY = slot0Bounds.minY,
                                minZ = slot0Bounds.minZ,
                            ),
                        ).get(5, TimeUnit.SECONDS)
                    }
                },
            )

            assertTrue(
                server.pump(10_000) { dungeons.slotState(0) == null && dungeons.activeCount() == 0 },
                "Startup did not finish cleaning orphaned owned slot 0 (state=${dungeons.slotState(0)})",
            )
            assertTrue(world.getBlockAt(slot0Center.x, slot0Center.y, slot0Center.z).type.isAir)
            assertTrue(world.getBlockAt(slot0Center.x + 1, slot0Center.y, slot0Center.z).type.isAir)

            // ジャーナルからも ownedSlot が削除されていること
            val journal = plugin.dataFolder.toPath().resolve("dungeon-returns.json")
            val persisted = DungeonReturnStore(journal).use { it.readState().get(5, TimeUnit.SECONDS) }
            assertTrue(persisted.ownedSlots.isEmpty(), "Expected ownedSlots to be empty after orphan cleanup, got ${persisted.ownedSlots}")

            // スロット 0 が直ちに再利用可能であること
            val player = joinPlayer(server, plugin, world, "OrphanReuse")
            dungeons.enter(player, "ec:dungeon_novice_arena")
            assertTrue(server.pump(10_000) { dungeons.containsPlayer(player.uniqueId) })
            assertTrue(
                kotlin.math.abs(player.location.x - slot0Center.x) < 30.0,
                "Expected orphan-cleaned slot 0 to be reused, got ${player.location}",
            )
        } finally {
            MockBukkit.unmock()
        }
    }

    @Test
    fun `mid-placement failure rolls back placed blocks and releases slot zero before succeeding on slot one`() {
        val server = MockBukkit.mock()
        try {
            val (plugin, world, dungeons) = setupOverworldPlugin(server)
            val player = joinPlayer(server, plugin, world, "MidPlaceRollback")
            val layout = OverworldDungeonInstanceLayout(baseX = 512, baseY = 220, baseZ = 512, spacing = 128)
            val slot0Bounds = layout.bounds(0)
            val slot0Center = layout.center(0)
            val slot1Center = layout.center(1)

            dungeons.enter(player, "ec:dungeon_novice_arena")

            // スロット 0 の配置が 1 tick 分進んだ直後（placed > 0）に後半セルへ障害物を差し込んで途中失敗させる
            assertTrue(
                server.pump(10_000) {
                    world.getBlockAt(slot0Bounds.minX, slot0Center.y, slot0Bounds.minZ).type == Material.STONE_BRICKS
                },
                "Slot 0 placement did not begin writing blocks",
            )
            assertTrue(
                world.getBlockAt(slot0Bounds.maxX, slot0Center.y, slot0Bounds.maxZ).type.isAir,
                "Expected trailing floor cell to still be air after first placement tick",
            )
            world.getBlockAt(slot0Bounds.maxX, slot0Center.y, slot0Bounds.maxZ).type = Material.OBSIDIAN

            // スロット 0 の既配置ブロックがロールバックされ、スロット 1 で入場成功すること
            assertTrue(
                server.pump(10_000) { dungeons.containsPlayer(player.uniqueId) },
                "Player did not fall back to slot 1 after mid-placement failure",
            )
            assertTrue(
                kotlin.math.abs(player.location.x - slot1Center.x) < 30.0,
                "Expected fallback to slot 1 (${slot1Center.x}), got ${player.location}",
            )
            assertTrue(
                world.getBlockAt(slot0Bounds.minX, slot0Center.y, slot0Bounds.minZ).type.isAir,
                "Mid-placement rollback did not clear previously placed slot 0 blocks",
            )
            assertEquals(
                Material.OBSIDIAN,
                world.getBlockAt(slot0Bounds.maxX, slot0Center.y, slot0Bounds.maxZ).type,
                "Rollback must not destroy pre-existing obstacle block",
            )
            assertNull(dungeons.slotState(0), "Rolled-back slot 0 must be released")
            assertEquals(1, dungeons.activeCount())
        } finally {
            MockBukkit.unmock()
        }
    }

    @Test
    fun `timeout evacuation retries and escalates to forced safe fallback when exit chunk stalls`() {
        val server = MockBukkit.mock()
        try {
            var now = 10_000_000L
            val (plugin, world, dungeons) = setupOverworldPlugin(server, clock = { now })
            val player = joinPlayer(server, plugin, world, "TimeoutEscalate")

            dungeons.enter(player, "ec:dungeon_novice_arena")
            assertTrue(server.pump(10_000) { dungeons.containsPlayer(player.uniqueId) }, "Player did not enter dungeon")

            // 帰還先チャンク (0, 0) の非同期読込を停滞させ、通常帰還が失敗し続ける状況を再現する
            world.stalledChunks += 0 to 0

            while (player.nextComponentMessage() != null) { /* 既存メッセージをクリア */ }
            val timeoutMsg = plain.serialize(plugin.message(player, "ec.dungeon.timeout"))
            val maxTicks = ((plugin.snapshot.world.tuning.instanceTimeout / PaperTaskIntervals.PLAYER_UPDATE_TICKS) + 5).toInt()
            var timedOut = false
            for (i in 0 until maxTicks) {
                dungeons.tick()
                val msgs = generateSequence { player.nextComponentMessage() }.map(plain::serialize).toList()
                if (timeoutMsg in msgs) {
                    timedOut = true
                    break
                }
            }
            assertTrue(timedOut, "Expected ec.dungeon.timeout notice on attempt 1")
            // 1 回目は通常帰還のチャンク応答待ちでまだ脱出できない
            assertTrue(dungeons.containsPlayer(player.uniqueId), "Expected player to still be awaiting stalled exit chunk on attempt 1")

            // 離脱タイムアウト超過後に 2 回目（再試行: +40 ticks）でもチャンクが停滞し続けるため脱出できない
            now += 11_000L
            repeat(2) { dungeons.tick() }
            assertTrue(dungeons.containsPlayer(player.uniqueId), "Expected attempt 2 retry to still stall on exit chunk")

            // 3 回目（さらに +40 ticks）で強制安全フォールバックへエスカレーションして救出・撤去されること
            now += 11_000L
            repeat(2) { dungeons.tick() }
            assertTrue(
                server.pump(10_000) {
                    !dungeons.containsPlayer(player.uniqueId) &&
                        dungeons.activeCount() == 0 &&
                        dungeons.slotState(0) == null
                },
                "Timeout retry escalation did not force-evacuate player and release slot 0",
            )
            assertTrue(player.location.y < 100.0, "Player remained stranded in sky after forced fallback: ${player.location}")
        } finally {
            MockBukkit.unmock()
        }
    }

    @Test
    fun `double-corrupted journal still rescues player logging in inside sky dungeon slot`() {
        val server = MockBukkit.mock()
        try {
            val (plugin, world, dungeons) = setupOverworldPlugin(server, beforeControllerInit = { target ->
                val journal = target.dataFolder.toPath().resolve("dungeon-returns.json")
                val backup = target.dataFolder.toPath().resolve("dungeon-returns.json.bak")
                Files.createDirectories(journal.parent)
                Files.writeString(journal, "{corrupt-primary")
                Files.writeString(backup, "{corrupt-backup")
            })
            assertTrue(dungeons.isJournalReady())
            assertTrue(dungeons.isJournalFailed())

            val stranded = joinPlayer(server, plugin, world, "CorruptStranded")
            stranded.teleport(Location(world, 512.5, 221.0, 512.5))
            dungeons.recover(stranded)

            assertTrue(
                server.pump(5000) { stranded.location.y < 100.0 },
                "Stranded sky player was not rescued when journal failed: ${stranded.location}",
            )
        } finally {
            MockBukkit.unmock()
        }
    }

    @Test
    fun `resolveProjectionSurfaceY rejects water lava and submerged ground unless allowWater is true for fishing`() {
        val server = MockBukkit.mock()
        try {
            val (plugin, world, _) = setupOverworldPlugin(server)
            val waterX = 30
            val waterZ = 30
            world.generatedChunks += (waterX shr 4) to (waterZ shr 4)
            world.loadChunk(waterX shr 4, waterZ shr 4)

            // 1. 水面: 通常投影は拒否、釣り用 allowWater = true は水面 + 1 を返す
            world.getBlockAt(waterX, 60, waterZ).type = Material.STONE
            for (y in 61..64) world.getBlockAt(waterX, y, waterZ).type = Material.WATER
            for (y in 65..70) world.getBlockAt(waterX, y, waterZ).type = Material.AIR
            assertThrows(IllegalStateException::class.java) {
                plugin.resolveProjectionSurfaceY(world, waterX, waterZ)
            }
            assertEquals(65, plugin.resolveProjectionSurfaceY(world, waterX, waterZ, allowWater = true))

            // projectedPosition でも allowWater = false は null、allowWater = true は y = 65.0 を返す
            val luminaArea = plugin.snapshot.world.areas.getValue("ec:area_lumina_port")
            val areaCenterX = (luminaArea.minX + luminaArea.maxX) / 2.0
            val areaCenterZ = (luminaArea.minZ + luminaArea.maxZ) / 2.0
            val sourcePos = Position(luminaArea.world, areaCenterX + (waterX - 10), 65.0, areaCenterZ + (waterZ - 10))
            assertNull(
                plugin.projectedPosition(sourcePos, "ec:area_lumina_port", allowWater = false),
                "Normal projection must reject water surface",
            )
            val waterProjected = plugin.projectedPosition(sourcePos, "ec:area_lumina_port", allowWater = true)
            assertNotNull(waterProjected, "Fishing projection with allowWater=true must succeed on water")
            assertEquals(65.0, waterProjected!!.y)

            // 2. 溶岩面: allowWater の有無に関わらず常に拒否
            val lavaX = 31
            world.getBlockAt(lavaX, 60, waterZ).type = Material.STONE
            for (y in 61..64) world.getBlockAt(lavaX, y, waterZ).type = Material.LAVA
            assertThrows(IllegalStateException::class.java) {
                plugin.resolveProjectionSurfaceY(world, lavaX, waterZ, allowWater = false)
            }
            assertThrows(IllegalStateException::class.java) {
                plugin.resolveProjectionSurfaceY(world, lavaX, waterZ, allowWater = true)
            }

            // 3. 樹冠直下が水没している固体地表: 通常投影は拒否、allowWater = true は水面 + 1 を返す
            val submergedX = 32
            world.getBlockAt(submergedX, 60, waterZ).type = Material.STONE
            world.getBlockAt(submergedX, 61, waterZ).type = Material.WATER
            world.getBlockAt(submergedX, 62, waterZ).type = Material.OAK_LEAVES
            assertThrows(IllegalStateException::class.java) {
                plugin.resolveProjectionSurfaceY(world, submergedX, waterZ, allowWater = false)
            }
            assertEquals(62, plugin.resolveProjectionSurfaceY(world, submergedX, waterZ, allowWater = true))
        } finally {
            MockBukkit.unmock()
        }
    }

    @Test
    fun `gathering controller registers pending sites on ChunkLoadEvent and protects cross-chunk neighbors`() {
        val server = MockBukkit.mock()
        try {
            val (plugin, world, _) = setupOverworldPlugin(server)
            val targetPoint = plugin.snapshot.world.gatheringNodes.values
                .flatMap { node -> node.locations.map { node to it } }
                .first { it.second.area == "ec:area_lumina_port" }
            val point = targetPoint.second
            val baseHorizontal = plugin.activeWorldProjection.projector.projectHorizontal(
                Position(point.world, point.x.toDouble(), point.y.toDouble(), point.z.toDouble()),
                point.area,
            )
            assertNotNull(baseHorizontal, "Gathering point must project horizontally in overworld mode")

            // 対象採取地点がチャンク境界 (x = 63, z = 63: チャンク (3, 3) の東端・南端) に乗るようアンカーを調整する
            val desiredX = 63
            val desiredZ = 63
            val shiftX = desiredX - floor(baseHorizontal!!.x).toInt()
            val shiftZ = desiredZ - floor(baseHorizontal.z).toInt()
            val shiftedAnchor = OverworldSiteAnchor(
                areaId = "ec:area_lumina_port",
                centerX = 10 + shiftX,
                surfaceY = 65,
                centerZ = 10 + shiftZ,
                biomeKey = "PLAINS",
                status = OverworldSiteStatus.READY,
                plazaSpawnX = 10 + shiftX,
                plazaSpawnY = 65,
                plazaSpawnZ = 10 + shiftZ,
            )
            val shiftedRegistry = object : OverworldSiteRegistry {
                override fun activeWorldName(): String = world.name
                override fun isReady(): Boolean = true
                override fun siteForArea(areaId: String): OverworldSiteAnchor? = shiftedAnchor.takeIf { it.areaId == areaId }
                override fun allSites(): List<OverworldSiteAnchor> = listOf(shiftedAnchor)
                override fun portalForDungeon(dungeonId: String): OverworldDungeonPortal? = null
                override fun allPortals(): List<OverworldDungeonPortal> = emptyList()
                override fun triggerRepair(): OverworldRepairResult = OverworldRepairResult.UNAVAILABLE
            }
            plugin.activeWorldProjection = ActiveWorldProjection(shiftedRegistry, plugin.snapshot.world.areas.values) { x, z ->
                plugin.resolveProjectionSurfaceY(world, x, z)
            }

            // チャンク (3, 3) が未ロードの状態で GatheringController を再構築し、pendingPointsByChunk に積ませる
            val targetChunkX = desiredX shr 4
            val targetChunkZ = desiredZ shr 4
            assertFalse(world.isChunkLoaded(targetChunkX, targetChunkZ), "Target chunk must be unloaded before reload")
            plugin.gathering.reload()
            assertFalse(
                plugin.gathering.isProtected(Location(world, desiredX.toDouble(), 65.0, desiredZ.toDouble())),
                "Unloaded gathering point must not be registered before ChunkLoadEvent",
            )

            // チャンク (3, 3) を生成・ロードして ChunkLoadEvent を発火すると登録され、チャンク跨ぎ隣接セル (64, 65, 63) も保護されること
            prepareSafeGround(world, desiredX, 64, desiredZ, radius = 1)
            plugin.gathering.chunk(ChunkLoadEvent(world.getChunkAt(targetChunkX, targetChunkZ), false))
            assertTrue(
                plugin.gathering.isProtected(Location(world, desiredX.toDouble(), 65.0, desiredZ.toDouble())),
                "Gathering point was not registered on ChunkLoadEvent",
            )
            assertTrue(
                plugin.gathering.isProtected(Location(world, (desiredX + 1).toDouble(), 65.0, desiredZ.toDouble())),
                "Cross-chunk adjacent block (64, 65, 63) must be protected by chunk-indexed isProtected",
            )
            assertFalse(
                plugin.gathering.isProtected(Location(world, (desiredX + 3).toDouble(), 65.0, desiredZ.toDouble())),
                "Distant block must not be protected",
            )
        } finally {
            MockBukkit.unmock()
        }
    }

    @Test
    fun `startup orphan cleanup skips blocks when placementStarted is false and limits removal to schematic non-air cells when true`() {
        val server = MockBukkit.mock()
        try {
            val layout = OverworldDungeonInstanceLayout(baseX = 512, baseY = 220, baseZ = 512, spacing = 128)
            val slot0Center = layout.center(0)
            val slot0Bounds = layout.bounds(0)
            val slot1Center = layout.center(1)
            val slot1Bounds = layout.bounds(1)
            val (plugin, world, dungeons) = setupOverworldPlugin(
                server,
                beforeControllerInit = { target ->
                    val w = server.getWorld("overworld_lifecycle") as AsyncChunkWorldMock
                    // スロット 0: placementStarted = false -> 既存地形ブロックを一切削らず所有記録だけ削除されるべき
                    val c0x = slot0Center.x shr 4
                    val c0z = slot0Center.z shr 4
                    w.generatedChunks += c0x to c0z
                    w.loadChunk(c0x, c0z)
                    w.getBlockAt(slot0Center.x, slot0Center.y, slot0Center.z).type = Material.OAK_PLANKS

                    // スロット 1: placementStarted = true -> スキーマの床セル (y=slot1Center.y) のみ撤去し、スキーマ外セル (y=slot1Center.y+5) は保持されるべき
                    val c1x = slot1Center.x shr 4
                    val c1z = slot1Center.z shr 4
                    w.generatedChunks += c1x to c1z
                    w.loadChunk(c1x, c1z)
                    w.getBlockAt(slot1Center.x, slot1Center.y, slot1Center.z).type = Material.STONE_BRICKS
                    w.getBlockAt(slot1Center.x, slot1Center.y + 5, slot1Center.z).type = Material.DIAMOND_BLOCK

                    val journal = target.dataFolder.toPath().resolve("dungeon-returns.json")
                    DungeonReturnStore(journal).use { store ->
                        store.putOwnedSlot(
                            DungeonOwnedSlot(
                                slot = 0,
                                world = w.name,
                                dungeonId = "ec:dungeon_novice_arena",
                                minX = slot0Bounds.minX,
                                minY = slot0Bounds.minY,
                                minZ = slot0Bounds.minZ,
                                placementStarted = false,
                            ),
                        ).get(5, TimeUnit.SECONDS)
                        store.putOwnedSlot(
                            DungeonOwnedSlot(
                                slot = 1,
                                world = w.name,
                                dungeonId = "ec:dungeon_novice_arena",
                                minX = slot1Bounds.minX,
                                minY = slot1Bounds.minY,
                                minZ = slot1Bounds.minZ,
                                placementStarted = true,
                            ),
                        ).get(5, TimeUnit.SECONDS)
                    }
                },
            )

            assertTrue(
                server.pump(10_000) {
                    dungeons.slotState(0) == null && dungeons.slotState(1) == null && dungeons.activeCount() == 0
                },
                "Startup reconciliation did not complete for slots 0 and 1",
            )
            // スロット 0 (placementStarted = false) は自然地形・既存建築を破壊しないこと
            assertEquals(
                Material.OAK_PLANKS,
                world.getBlockAt(slot0Center.x, slot0Center.y, slot0Center.z).type,
                "Orphan slot with placementStarted=false must not destroy existing blocks",
            )
            // スロット 1 (placementStarted = true) はスキーマ床だけ撤去し、スキーマ外のブロックは保持すること
            assertTrue(
                world.getBlockAt(slot1Center.x, slot1Center.y, slot1Center.z).type.isAir,
                "Orphan slot with placementStarted=true must clear schematic floor blocks",
            )
            assertEquals(
                Material.DIAMOND_BLOCK,
                world.getBlockAt(slot1Center.x, slot1Center.y + 5, slot1Center.z).type,
                "Orphan slot cleanup must preserve non-schematic blocks inside slot bounds",
            )

            val journal = plugin.dataFolder.toPath().resolve("dungeon-returns.json")
            val persisted = DungeonReturnStore(journal).use { it.readState().get(5, TimeUnit.SECONDS) }
            assertTrue(persisted.ownedSlots.isEmpty(), "Both reconciled ownedSlots should be removed from journal")
        } finally {
            MockBukkit.unmock()
        }
    }

    @Test
    fun `admin dungeon release enforces permission, rejects active slots, releases protected slots for reuse, and recovers after journal corruption`() {
        val server = MockBukkit.mock()
        try {
            val (plugin, world, dungeons) = setupOverworldPlugin(server)
            val commands = SimulationCommandController(plugin)
            val layout = OverworldDungeonInstanceLayout(baseX = 512, baseY = 220, baseZ = 512, spacing = 128)
            val slot0Center = layout.center(0)

            val regular = joinPlayer(server, plugin, world, "ReleaseUser")
            regular.isOp = false
            val admin = joinPlayer(server, plugin, world, "ReleaseAdmin")
            admin.isOp = true

            // 1. 非管理者は拒否されること
            val noPerm = assertThrows(IllegalArgumentException::class.java) {
                commands.releaseDungeonSlot(regular, listOf("dungeon", "release", "0"))
            }
            assertEquals("ec.command.no_permission", noPerm.message)

            // 2. 稼働中 (ACTIVE) のスロットは解放拒否され、アリーナもプレイヤーも影響を受けないこと
            dungeons.enter(regular, "ec:dungeon_novice_arena")
            assertTrue(server.pump(10_000) { dungeons.containsPlayer(regular.uniqueId) })
            assertEquals(DungeonSlotState.ACTIVE, dungeons.slotState(0))
            while (admin.nextComponentMessage() != null) { /* 既存メッセージをクリア */ }
            commands.releaseDungeonSlot(admin, listOf("dungeon", "release", "0"))
            val activeExpected = plain.serialize(plugin.message(admin, "ec.dungeon.release_active", "slot" to 0))
            val activeMsgs = generateSequence { admin.nextComponentMessage() }.map(plain::serialize).toList()
            assertTrue(activeExpected in activeMsgs, "Expected release_active notice '$activeExpected', got $activeMsgs")
            assertTrue(dungeons.containsPlayer(regular.uniqueId))
            assertEquals(Material.STONE_BRICKS, world.getBlockAt(slot0Center.x, slot0Center.y, slot0Center.z).type)

            // 3. 切断後に PROTECTED となったスロットを管理者が解放すると撤去・記録削除され、再利用可能になること
            dungeons.quit(PlayerQuitEvent(regular, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED))
            assertEquals(DungeonSlotState.PROTECTED, dungeons.slotState(0))
            assertTrue(dungeons.hasPendingReturn(regular.uniqueId))
            assertEquals(listOf(0), dungeons.releasableSlots())

            while (admin.nextComponentMessage() != null) { /* 既存メッセージをクリア */ }
            commands.releaseDungeonSlot(admin, listOf("dungeon", "release", "0"))
            val startedExpected = plain.serialize(plugin.message(admin, "ec.dungeon.release_started", "slot" to 0))
            val startedMsgs = generateSequence { admin.nextComponentMessage() }.map(plain::serialize).toList()
            assertTrue(startedExpected in startedMsgs, "Expected release_started notice '$startedExpected', got $startedMsgs")

            assertTrue(
                server.pump(10_000) {
                    dungeons.slotState(0) == null &&
                        !dungeons.hasPendingReturn(regular.uniqueId) &&
                        dungeons.activeCount() == 0
                },
                "Manual release did not finish cleaning protected slot 0 (state=${dungeons.slotState(0)})",
            )
            assertTrue(
                world.getBlockAt(slot0Center.x, slot0Center.y, slot0Center.z).type.isAir,
                "Manual release did not clear arena floor blocks to AIR",
            )

            // スロット 0 が即座に再利用できること
            dungeons.enter(admin, "ec:dungeon_novice_arena")
            assertTrue(server.pump(10_000) { dungeons.containsPlayer(admin.uniqueId) })
            assertTrue(
                kotlin.math.abs(admin.location.x - slot0Center.x) < 30.0,
                "Expected manually released slot 0 to be reused, got ${admin.location}",
            )
        } finally {
            MockBukkit.unmock()
        }
    }

    @Test
    fun `admin dungeon release can manually clear leftover sky blocks after journal double corruption`() {
        val server = MockBukkit.mock()
        try {
            val layout = OverworldDungeonInstanceLayout(baseX = 512, baseY = 220, baseZ = 512, spacing = 128)
            val slot0Center = layout.center(0)
            val (plugin, world, dungeons) = setupOverworldPlugin(server, beforeControllerInit = { target ->
                val journal = target.dataFolder.toPath().resolve("dungeon-returns.json")
                val backup = target.dataFolder.toPath().resolve("dungeon-returns.json.bak")
                Files.createDirectories(journal.parent)
                Files.writeString(journal, "{corrupt-primary")
                Files.writeString(backup, "{corrupt-backup")
            })
            assertTrue(dungeons.isJournalFailed())
            val cx = slot0Center.x shr 4
            val cz = slot0Center.z shr 4
            world.generatedChunks += cx to cz
            world.loadChunk(cx, cz)
            world.getBlockAt(slot0Center.x, slot0Center.y, slot0Center.z).type = Material.STONE_BRICKS

            val admin = joinPlayer(server, plugin, world, "CorruptAdmin")
            admin.isOp = true
            SimulationCommandController(plugin).releaseDungeonSlot(admin, listOf("dungeon", "release", "0"))
            assertTrue(
                server.pump(10_000) {
                    dungeons.slotState(0) == null && world.getBlockAt(slot0Center.x, slot0Center.y, slot0Center.z).type.isAir
                },
                "Manual release after journal corruption did not clear leftover slot 0 blocks",
            )
        } finally {
            MockBukkit.unmock()
        }
    }

    @Test
    fun `projectedPosition negatively caches failed surface lookups until anchors refresh`() {
        val server = MockBukkit.mock()
        try {
            val (plugin, world, _) = setupOverworldPlugin(server)
            var surfaceLookups = 0
            val registry = plugin.activeWorldProjection.registry
            plugin.activeWorldProjection = ActiveWorldProjection(registry, plugin.snapshot.world.areas.values) { x, z ->
                surfaceLookups++
                plugin.resolveProjectionSurfaceY(world, x, z)
            }

            val waterX = 35
            val waterZ = 35
            world.generatedChunks += (waterX shr 4) to (waterZ shr 4)
            world.loadChunk(waterX shr 4, waterZ shr 4)
            world.getBlockAt(waterX, 60, waterZ).type = Material.STONE
            for (y in 61..64) world.getBlockAt(waterX, y, waterZ).type = Material.WATER
            for (y in 65..70) world.getBlockAt(waterX, y, waterZ).type = Material.AIR

            val luminaArea = plugin.snapshot.world.areas.getValue("ec:area_lumina_port")
            val areaCenterX = (luminaArea.minX + luminaArea.maxX) / 2.0
            val areaCenterZ = (luminaArea.minZ + luminaArea.maxZ) / 2.0
            val sourcePos = Position(luminaArea.world, areaCenterX + (waterX - 10), 65.0, areaCenterZ + (waterZ - 10))

            val previousLevel = plugin.logger.level
            val records = mutableListOf<LogRecord>()
            val handler = object : Handler() {
                override fun publish(record: LogRecord) { records += record }
                override fun flush() = Unit
                override fun close() = Unit
            }
            plugin.logger.level = Level.FINE
            plugin.logger.addHandler(handler)
            try {
                // 1 回目は失敗して負キャッシュされ、ログは WARN ではなく FINE で 1 回だけ記録される
                assertNull(plugin.projectedPosition(sourcePos, "ec:area_lumina_port", allowWater = false))
                assertEquals(1, surfaceLookups, "First failed projection should invoke surfaceLookup once")

                // 2 回目以降は負キャッシュにより surfaceLookup を再計算しないこと
                assertNull(plugin.projectedPosition(sourcePos, "ec:area_lumina_port", allowWater = false))
                assertNull(plugin.projectedPosition(sourcePos, "ec:area_lumina_port", allowWater = false))
                assertEquals(1, surfaceLookups, "Negative cache must prevent repeated surfaceLookup recomputation")

                val projectionLogs = records.filter { it.message.orEmpty().startsWith("Could not project position (") }
                assertEquals(1, projectionLogs.size, "Expected single projection diagnostic log, got $projectionLogs")
                assertEquals(Level.FINE, projectionLogs.single().level, "Failed surface projection should log at FINE rather than WARNING")
            } finally {
                plugin.logger.removeHandler(handler)
                plugin.logger.level = previousLevel
            }

            // 同一座標でも allowWater = true は別キーとして成功すること
            val fishingPos = plugin.projectedPosition(sourcePos, "ec:area_lumina_port", allowWater = true)
            assertNotNull(fishingPos)
            assertEquals(65.0, fishingPos!!.y)

            // 地形を固体へ直してもアンカー更新までは負キャッシュが効き、refreshWorldAnchors() でクリアされて投影が成功すること
            for (y in 61..64) world.getBlockAt(waterX, y, waterZ).type = Material.STONE
            assertNull(plugin.projectedPosition(sourcePos, "ec:area_lumina_port", allowWater = false))
            plugin.refreshWorldAnchors()
            val recovered = plugin.projectedPosition(sourcePos, "ec:area_lumina_port", allowWater = false)
            assertNotNull(recovered, "refreshWorldAnchors must clear negative projection cache")
            assertEquals(65.0, recovered!!.y)
        } finally {
            MockBukkit.unmock()
        }
    }

    @Test
    fun `FishingController projectedSpots prefers fishing_water anchor over relative offset in overworld mode`() {
        val server = MockBukkit.mock()
        try {
            val anchoredSite = OverworldSiteAnchor(
                areaId = "ec:area_lumina_port",
                centerX = 10,
                surfaceY = 65,
                centerZ = 10,
                biomeKey = "SNOWY_BEACH",
                status = OverworldSiteStatus.READY,
                plazaSpawnX = 10,
                plazaSpawnY = 65,
                plazaSpawnZ = 10,
                fishingWaterX = 48,
                fishingWaterY = 63,
                fishingWaterZ = -24,
            )
            val (plugin, world, _) = setupOverworldPlugin(server, customSites = listOf(anchoredSite))

            val spots = plugin.fishing.projectedSpots()
            val luminaSpot = spots.firstOrNull { it.id == "ec:spot_lumina_pier" }
            assertNotNull(luminaSpot, "Expected default Lumina fishing spot to resolve via fishing_water anchor")
            assertEquals(world.name, luminaSpot!!.world)
            assertEquals(Position(world.name, 48.5, 63.0, -23.5), luminaSpot.center)

            // 建設済み池（fishing_water）へのキャストが凍結海シードでもヒットすること
            val selected = selectFishingSpot(spots, world.name, 48.5, -23.5, emptySet())
            assertEquals("ec:spot_lumina_pier", selected?.id)
        } finally {
            MockBukkit.unmock()
        }
    }
}
