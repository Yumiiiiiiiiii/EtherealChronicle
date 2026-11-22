package net.nakaymi.etherealchronicle.paper

import net.kyori.adventure.text.Component
import net.nakaymi.etherealchronicle.core.recordChallenge
import net.nakaymi.etherealchronicle.core.firstClearRanks
import net.nakaymi.etherealchronicle.core.returnToMailbox
import net.nakaymi.etherealchronicle.core.OverworldDungeonInstanceLayout
import net.nakaymi.etherealchronicle.core.DungeonInstanceCenter
import net.nakaymi.etherealchronicle.core.canAcknowledgeDungeonReturn
import net.nakaymi.etherealchronicle.data.DungeonReturnRecord
import net.nakaymi.etherealchronicle.data.DungeonReturnStore
import org.bukkit.*
import org.bukkit.entity.Player
import org.bukkit.event.*
import org.bukkit.event.player.*
import java.util.UUID

/** 制限数付きの複数ダンジョンinstance、wave、帰還地点を管理します。 */
class DungeonController(private val plugin: RpgPlugin,private val adventure: AdventureController,private val enemies: EnemyController): Listener {
    private data class ArenaPalette(val floor: Material, val wall: Material, val inner: Material) {
        companion object {
            fun resolve(names: List<String>) = ArenaPalette(
                Material.valueOf(names[DungeonArenaLayout.OUTER_FLOOR_PALETTE_INDEX]),
                Material.valueOf(names[DungeonArenaLayout.WALL_PALETTE_INDEX]),
                Material.valueOf(names[DungeonArenaLayout.INNER_FLOOR_PALETTE_INDEX]),
            )
        }
    }
    data class Instance(val slot: Int,val definition: net.nakaymi.etherealchronicle.core.DungeonDefinition,val members: MutableSet<UUID>,val returns: Map<UUID,Location>,val started: Long,var boss: UUID?=null,var completed: Boolean=false,var waveIndex: Int=0,val entities: MutableSet<UUID> = mutableSetOf(),val startedAtMillis: Long=monotonicMillis(),val overworld: Boolean=false)
    private val instances=mutableMapOf<Int,Instance>()
    private var tick=0L
    private val builtPalette=mutableMapOf<Int,List<String>>()
    private val loadedSchematics=mutableMapOf<String,SchematicData>()
    private val schematicSlots=mutableSetOf<Int>()
    private val overworldLayout = OverworldDungeonInstanceLayout()
    private val returnStore = DungeonReturnStore(plugin.dataFolder.toPath().resolve("dungeon-returns.json"))
    private val pendingReturns = mutableMapOf<UUID, DungeonReturnRecord>()
    private val reservedSlots = mutableSetOf<Int>()
    private val enteringPlayers = mutableSetOf<UUID>()
    private var journalReady = false
    private val journalWaiters = mutableListOf<() -> Unit>()
    private var placement: OverworldDungeonPlacement? = null
    private var scanner: OverworldDungeonScanner? = null
    private val placedOperations = mutableMapOf<Int, OverworldDungeonPlacement.Operation>()
    init {
        plugin.server.pluginManager.registerEvents(this,plugin)
        adventure.enterDungeon={ player,id -> enter(player,id) }
        returnStore.readAll().whenComplete { records, error -> plugin.sync {
            if (error != null) plugin.logger.severe("Dungeon return journal unavailable: ${error.message}")
            else {
                pendingReturns.putAll(records)
                journalReady = true
                journalWaiters.toList().forEach { it() }
                journalWaiters.clear()
            }
        } }
    }
    fun activeCount() = instances.size + reservedSlots.size

    fun isJournalReady(): Boolean = journalReady

    /** 帰還記録が読み込まれるまで入場・ログイン時の転送を保留します。 */
    fun whenJournalReady(action: () -> Unit) {
        if (!plugin.activeWorldProjection.registry.isOverworldMode() || journalReady) action()
        else journalWaiters += action
    }

    /** 未確定記録の所有者は初回スポーン処理の対象から外します。 */
    fun hasPendingReturn(id: UUID): Boolean = plugin.activeWorldProjection.registry.isOverworldMode() &&
        (!journalReady || id in pendingReturns)
    /** リスポーン処理の振り分けに、現在の参加状態を同期的に公開します。 */
    fun containsPlayer(id: UUID): Boolean = instances.values.any { id in it.members }
    internal fun currentWave(slot:Int) = instances[slot]?.waveIndex
    internal fun currentBoss(slot:Int) = instances[slot]?.boss
    /** 参加者・解放条件・instance枠を検証してダンジョンへ入場させます。 */
    fun enter(player: Player,dungeonId: String = "ec:dungeon_void_sanctum") {
        if (plugin.activeWorldProjection.registry.isOverworldMode()) {
            enterOverworld(player, dungeonId)
            return
        }
        val definition=plugin.snapshot.world.dungeons[dungeonId] ?: error("ec.error.dungeon_missing: $dungeonId")
        require(instances.values.none { player.uniqueId in it.members }) { "ec.error.dungeon_already_inside" }
        val party=adventure.parties.party(player.uniqueId)
        require(party == null || party.leader == player.uniqueId) { "ec.error.party_leader_required" }
        val players=adventure.parties.members(player.uniqueId).map { Bukkit.getPlayer(it) ?: error("ec.error.party_not_online") }
        require(players.all { p -> p.world == player.world && p.location.isWithinBlocks(player.location,PaperDistance.DUNGEON_PARTY_GATHER_BLOCKS) && plugin.profiles[p.uniqueId]?.classId != null }) { "ec.error.party_gather" }
        require(players.all { definition.unlockFlag in plugin.profiles.getValue(it.uniqueId).flags }) { "ec.error.dungeon_unlock_required" }
        val world=Bukkit.getWorld(DemoWorld.NAME) ?: error("ec.error.world_unavailable")
        val slot=(0 until plugin.snapshot.world.tuning.instanceCap).firstOrNull { it !in instances } ?: error("ec.error.dungeon_full")
        val instance=Instance(slot,definition,players.map { it.uniqueId }.toMutableSet(),players.associate { it.uniqueId to it.location.clone() },tick)
        val origin=DungeonArenaLayout.originForSlot(slot); val x=origin.x; val z=origin.z
        val schematicPlaced = placeDungeonSchematic(world, definition.id, x, z)
        if(!schematicPlaced && dungeonPaletteNeedsBuild(builtPalette[slot],definition.palette)) {
            val palette = ArenaPalette.resolve(definition.palette)
            for(dx in -DungeonArenaLayout.PLATFORM_RADIUS_BLOCKS..DungeonArenaLayout.PLATFORM_RADIUS_BLOCKS) for(dz in -DungeonArenaLayout.PLATFORM_RADIUS_BLOCKS..DungeonArenaLayout.PLATFORM_RADIUS_BLOCKS) {
            val edge=kotlin.math.abs(dx) == DungeonArenaLayout.PLATFORM_RADIUS_BLOCKS || kotlin.math.abs(dz) == DungeonArenaLayout.PLATFORM_RADIUS_BLOCKS
            val center=kotlin.math.abs(dx) <= DungeonArenaLayout.INNER_FLOOR_RADIUS_BLOCKS && kotlin.math.abs(dz) <= DungeonArenaLayout.INNER_FLOOR_RADIUS_BLOCKS
            world.getBlockAt(x+dx,DungeonArenaLayout.FLOOR_Y,z+dz).type=if(center) palette.inner else palette.floor
            if(edge) for(y in DungeonArenaLayout.WALL_BOTTOM_Y..DungeonArenaLayout.WALL_TOP_Y) world.getBlockAt(x+dx,y,z+dz).type=palette.wall
            }
            builtPalette[slot]=definition.palette.toList()
        }
        instances[slot]=instance
        try { spawnWave(instance,world,x,z) }
        catch(error:Exception) { close(instance); throw error }
        players.forEach { p -> plugin.profiles[p.uniqueId]?.let { profile -> plugin.game.quests.event(profile,net.nakaymi.etherealchronicle.core.ObjectiveKind.EXPLORE,definition.area,1,plugin.inventory(p),false); plugin.markDirty(profile.uuid) } }
        players.forEach { it.teleport(Location(world,x.toDouble(),DungeonArenaLayout.PLAYER_SPAWN_Y.toDouble(),(z+DungeonArenaLayout.PLAYER_SPAWN_Z_OFFSET).toDouble())); it.sendMessage(plugin.message(it,"ec.dungeon.entry", "message" to definition.entryMessage)) }
    }

    /** 地上入口から空中枠を全域検査し、帰還記録の永続化後だけ転送します。 */
    private fun enterOverworld(player: Player, dungeonId: String) {
        val registry = plugin.activeWorldProjection.registry
        check(registry.isReady() && journalReady) { "ec.error.world_unavailable" }
        val portal = registry.portalForDungeon(dungeonId) ?: error("ec.error.world_unavailable")
        check(registry.siteForArea(portal.areaId)?.status == net.nakaymi.etherealchronicle.core.OverworldSiteStatus.READY) { "ec.error.world_unavailable" }
        val definition = plugin.snapshot.world.dungeons[dungeonId] ?: error("ec.error.dungeon_missing: $dungeonId")
        val party = adventure.parties.party(player.uniqueId)
        require(party == null || party.leader == player.uniqueId) { "ec.error.party_leader_required" }
        val players = adventure.parties.members(player.uniqueId).map { Bukkit.getPlayer(it) ?: error("ec.error.party_not_online") }
        require(players.all { p -> p.world == player.world && p.location.isWithinBlocks(player.location, PaperDistance.DUNGEON_PARTY_GATHER_BLOCKS) && plugin.profiles[p.uniqueId]?.classId != null }) { "ec.error.party_gather" }
        require(players.all { definition.unlockFlag in plugin.profiles.getValue(it.uniqueId).flags }) { "ec.error.dungeon_unlock_required" }
        require(players.none { it.uniqueId in pendingReturns || it.uniqueId in enteringPlayers || containsPlayer(it.uniqueId) }) { "ec.error.dungeon_already_inside" }
        val world = Bukkit.getWorld(registry.activeWorldName()) ?: error("ec.error.world_unavailable")
        check(player.world == world && overworldLayout.fitsHeight(world.minHeight, world.maxHeight)) { "ec.error.world_unavailable" }
        val path = WorldSchematicLayout.dungeonPath(dungeonId) ?: error("ec.error.dungeon_missing: $dungeonId")
        val schematic = loadedSchematics[path] ?: SchematicResources.load(path)?.also { loadedSchematics[path] = it }
            ?: error("ec.error.world_unavailable")
        check(schematic.width == OverworldDungeonInstanceLayout.ARENA_WIDTH &&
            schematic.height == OverworldDungeonInstanceLayout.ARENA_HEIGHT &&
            schematic.length == OverworldDungeonInstanceLayout.ARENA_WIDTH) { "ec.error.world_unavailable" }
        val candidates = (0 until plugin.snapshot.world.tuning.instanceCap.coerceAtMost(OverworldDungeonInstanceLayout.MAX_CANDIDATE_SLOTS))
            .filter { it !in reservedSlots && it !in instances && pendingReturns.values.none { record -> record.slotX == overworldLayout.center(it).x && record.slotZ == overworldLayout.center(it).z } }
        check(candidates.isNotEmpty()) { "ec.error.dungeon_full" }
        val currentScanner = scanner ?: OverworldDungeonScanner(plugin, world).also { scanner = it }
        val currentPlacement = placement ?: OverworldDungeonPlacement(plugin, world, currentScanner).also { placement = it }
        val returns = players.associate { member ->
            member.uniqueId to Location(world, portal.exitX + 0.5, portal.exitY.toDouble(),
                portal.exitZ + 0.5, member.location.yaw, member.location.pitch)
        }
        check(returns.values.all { destination -> world.isChunkLoaded(destination.blockX shr 4, destination.blockZ shr 4) &&
            isSafeReturn(destination) }) { "ec.error.world_unavailable" }
        enteringPlayers.addAll(returns.keys)

        fun finishEntry() { enteringPlayers.removeAll(returns.keys) }

        /** 占有済み枠は全域検査で除き、次の候補へ進めます。 */
        fun scanCandidate(index: Int) {
            if (index >= candidates.size) {
                finishEntry()
                player.sendMessage(plugin.message(player, "ec.error.dungeon_full"))
                return
            }
            val slot = candidates[index]
            reservedSlots += slot
            val bounds = overworldLayout.bounds(slot)
            lateinit var scanOperation: OverworldDungeonScanner.Operation
            scanOperation = currentScanner.scan(bounds, SCAN_BUDGET) { result, failure ->
                scanOperation.release()
                if (failure != null || result != net.nakaymi.etherealchronicle.core.DungeonBoxScanResult.EMPTY) {
                    reservedSlots.remove(slot)
                    if (failure != null) plugin.logger.warning("Dungeon slot scan failed: ${failure.message}")
                    scanCandidate(index + 1)
                    return@scan
                }
                // 配置は二度目の検査と書き込み直前の空気確認を必須とします。
                lateinit var operation: OverworldDungeonPlacement.Operation
                operation = currentPlacement.place(bounds, schematic, SCAN_BUDGET) { success, error ->
                    if (!success) {
                        finishEntry()
                        if (error != null) plugin.logger.warning("Dungeon placement retained for inspection: ${error.message}")
                        return@place
                    }
                    placedOperations[slot] = operation
                    if (!plugin.isEnabled || !registry.isReady() || registry.portalForDungeon(dungeonId) != portal ||
                        players.any { !it.isOnline || it.uniqueId in pendingReturns || it.world != world }) {
                        finishEntry()
                        return@place
                    }
                    val center = overworldLayout.center(slot)
                    val records = returns.mapValues { (_, location) -> DungeonReturnRecord(dungeonId, world.name,
                        location.x, location.y, location.z, location.yaw, location.pitch, center.x, center.y, center.z) }
                    returnStore.putAll(records).whenComplete { _, writeError -> plugin.sync {
                        if (writeError != null) {
                            finishEntry()
                            plugin.logger.severe("Dungeon return journal write failed: ${writeError.message}")
                            return@sync
                        }
                        pendingReturns.putAll(records)
                        finishEntry()
                        if (players.any { !it.isOnline || it.world != world } || !registry.isReady() ||
                            registry.portalForDungeon(dungeonId) != portal || returns.values.any { !isSafeReturn(it) }) return@sync
                        val instance = Instance(slot, definition, players.map { it.uniqueId }.toMutableSet(), returns, tick, overworld = true)
                        instances[slot] = instance
                        try { spawnWave(instance, world, center.x, center.z) }
                        catch (error: Exception) { plugin.logger.warning("Dungeon wave failed: ${error.message}"); close(instance); return@sync }
                        players.forEach { member ->
                            plugin.profiles[member.uniqueId]?.let { profile ->
                                plugin.game.quests.event(profile, net.nakaymi.etherealchronicle.core.ObjectiveKind.EXPLORE,
                                    definition.area, 1, plugin.inventory(member), false)
                                plugin.markDirty(profile.uuid)
                            }
                            if (member.teleport(Location(world, center.x + 0.5, center.y.toDouble() + 1.0,
                                    center.z + DungeonArenaLayout.PLAYER_SPAWN_Z_OFFSET + 0.5))) {
                                member.sendMessage(plugin.message(member, "ec.dungeon.entry", "message" to definition.entryMessage))
                            } else leave(member)
                        }
                    } }
                }
            }
        }
        scanCandidate(0)
    }

    /** 対応する同梱アリーナがあればslot区画を初期化して配置します。 */
    private fun placeDungeonSchematic(world: World, dungeonId: String, centerX: Int, centerZ: Int): Boolean {
        val path = WorldSchematicLayout.dungeonPath(dungeonId) ?: return false
        val schematic = loadedSchematics[path] ?: runCatching { SchematicResources.load(path) }
            .onFailure { plugin.logger.warning("Could not read dungeon schematic $path: ${it.message}") }
            .getOrNull()?.also { loadedSchematics[path] = it } ?: return false
        if (schematic.width != DungeonArenaLayout.SCHEMATIC_WIDTH_BLOCKS ||
            schematic.height != DungeonArenaLayout.SCHEMATIC_HEIGHT_BLOCKS ||
            schematic.length != DungeonArenaLayout.SCHEMATIC_LENGTH_BLOCKS
        ) {
            plugin.logger.warning("Dungeon schematic has unexpected dimensions: $path (${schematic.width}x${schematic.height}x${schematic.length})")
            return false
        }
        val x = centerX - DungeonArenaLayout.SCHEMATIC_ORIGIN_OFFSET_BLOCKS
        val y = DungeonArenaLayout.SCHEMATIC_ORIGIN_Y
        val z = centerZ - DungeonArenaLayout.SCHEMATIC_ORIGIN_OFFSET_BLOCKS
        clearSchematicArena(world, x, y, z)
        return runCatching {
            SchematicPlacer.place(schematic, world, x, y, z, skipAir = false) { message, error ->
                plugin.logger.warning(if (error == null) message else "$message: ${error.message}")
            }
            schematicSlots += (centerX - DungeonArenaLayout.FIRST_CENTER_X) / DungeonArenaLayout.SLOT_SPACING_BLOCKS
            true
        }.getOrElse { error ->
            clearSchematicArena(world, x, y, z)
            plugin.logger.warning("Could not place dungeon schematic $path; using procedural fallback: ${error.message}")
            false
        }
    }

    /** インスタンス専用の立方領域を空にして、前回の内容を残しません。 */
    private fun clearSchematicArena(world: World, originX: Int, originY: Int, originZ: Int) {
        for (y in 0 until DungeonArenaLayout.SCHEMATIC_HEIGHT_BLOCKS) {
            for (z in 0 until DungeonArenaLayout.SCHEMATIC_LENGTH_BLOCKS) {
                for (x in 0 until DungeonArenaLayout.SCHEMATIC_WIDTH_BLOCKS) {
                    world.getBlockAt(originX + x, originY + y, originZ + z).setType(Material.AIR, false)
                }
            }
        }
    }
    private fun spawnWave(instance:Instance,world:World,x:Int,z:Int) {
        val definition=instance.definition
        val wave=definition.waves.getOrNull(instance.waveIndex)
        val bossId=wave?.boss ?: definition.boss
        val bossOffset=wave?.bossOffset ?: definition.bossOffset
        val guards=wave?.guards ?: definition.guards
        val offsets=wave?.guardOffsets ?: definition.guardOffsets
        val spawned=mutableListOf<org.bukkit.entity.LivingEntity>()
        val spawnY = if (instance.overworld) overworldLayout.center(instance.slot).y + 1.0 else DungeonArenaLayout.ENTITY_SPAWN_Y.toDouble()
        try {
            val boss=enemies.spawn(bossId,Location(world,(x+bossOffset.first).toDouble(),spawnY,(z+bossOffset.second).toDouble()),"instance:${instance.slot}:wave:${instance.waveIndex}",1,instance.members.toSet()) ?: error("ec.error.spawn_cap")
            spawned += boss
            guards.zip(offsets).forEach { (guard,offset) ->
                val entity=enemies.spawn(guard,Location(world,x+offset.first.toDouble(),spawnY,z+offset.second.toDouble()),"instance:${instance.slot}:wave:${instance.waveIndex}:guards",guards.size.coerceAtLeast(1),instance.members.toSet())
                if(entity == null) error("ec.error.guard_spawn_failed")
                spawned += entity
            }
        } catch(error:Exception) { spawned.forEach { it.remove() }; throw error }
        instance.entities.clear(); instance.entities.addAll(spawned.map { it.uniqueId }); instance.boss=spawned.first().uniqueId
    }
    fun defeated(id: UUID) {
        val instance=instances.values.firstOrNull { it.boss == id && !it.completed } ?: return
        val waveCount=instance.definition.waves.size.coerceAtLeast(1)
        if(instance.waveIndex+1 < waveCount) {
            instance.entities.forEach { Bukkit.getEntity(it)?.remove() }
            instance.waveIndex++
            val world=instanceWorld(instance) ?: return
            val (x,z)=instanceCenter(instance)
            try {
                spawnWave(instance,world,x,z)
                instance.members.forEach { member -> Bukkit.getPlayer(member)?.let { player -> player.sendMessage(plugin.message(player,"ec.dungeon.wave", "current" to instance.waveIndex+1, "total" to waveCount)) } }
            } catch(error:Exception) {
                plugin.logger.warning("Challenge wave could not spawn: ${error.message}")
                instance.members.toList().forEach { Bukkit.getPlayer(it)?.let(::leave) }
            }
            return
        }
        instance.completed=true
        instance.entities.forEach { Bukkit.getEntity(it)?.remove() }
        val seconds = MinecraftTicks.millisToWholeSeconds(monotonicMillis() - instance.startedAtMillis)
        val ranks=instance.definition.scoreRanks
        if(ranks.isNotEmpty()) {
            val rank=net.nakaymi.etherealchronicle.core.challengeRank(seconds,ranks)
            instance.members.forEach { member ->
                var best: net.nakaymi.etherealchronicle.core.ChallengeRecord? = null
                var hasFirstReward=false
                adventure.profileTransaction(member,{ profile ->
                    val (updatedBest,_)=profile.recordChallenge(instance.definition.id,seconds,rank)
                    best=updatedBest
                    val firstRewards=firstClearRanks(profile,ranks,seconds)
                    hasFirstReward=firstRewards.isNotEmpty()
                    val itemRewards=firstRewards.flatMap { it.firstClearItems.entries }
                        .groupBy({ it.key },{ it.value }).mapValues { (_,amounts) -> amounts.sum() }
                    val flags=firstRewards.mapNotNull { it.firstClearFlag }.toSet()
                    // 初回報酬flagとmailboxをクリア記録と同じ保存へ含めます。
                    profile.returnToMailbox(itemRewards)
                    profile.flags.addAll(flags)
                }) {
                    val player=Bukkit.getPlayer(member)?.takeIf { it.isOnline } ?: return@profileTransaction
                    best?.let { record ->
                        player.sendMessage(plugin.message(player,"ec.dungeon.result", "message" to instance.definition.clearMessage, "seconds" to seconds, "rank" to rank, "best_seconds" to record.bestSeconds, "best_rank" to record.bestRank))
                    }
                    if(hasFirstReward) {
                        adventure.claimPending(player)
                        player.sendMessage(plugin.message(player,"ec.dungeon.first_rank_reward"))
                    }
                }
            }
        } else instance.members.forEach { member -> Bukkit.getPlayer(member)?.let { it.sendMessage(plugin.message(it,"ec.dungeon.clear", "message" to instance.definition.clearMessage)) } }
    }
    fun leave(player: Player) {
        val instance=instances.values.firstOrNull { player.uniqueId in it.members } ?: return
        // ジャーナルは転送直後に削除しない。地上位置の再読込を次のログインで確認します。
        instance.returns[player.uniqueId]?.let { destination -> if(destination.world != null) player.teleport(destination) }
        if (instance.overworld && player.isOnline) player.sendMessage(plugin.message(player, "ec.dungeon.relogin_required"))
        instance.members.remove(player.uniqueId)
        if(instance.members.isEmpty()) close(instance)
    }
    private fun close(instance: Instance) {
        enemies.removeParticipants(instance.returns.keys)
        if (instance.overworld) {
            placedOperations.remove(instance.slot)?.release()
            instances.remove(instance.slot)
            return
        }
        if (instance.slot in schematicSlots) {
            val origin = DungeonArenaLayout.originForSlot(instance.slot)
            Bukkit.getWorld(DemoWorld.NAME)?.let { world ->
                clearSchematicArena(
                    world,
                    origin.x - DungeonArenaLayout.SCHEMATIC_ORIGIN_OFFSET_BLOCKS,
                    DungeonArenaLayout.SCHEMATIC_ORIGIN_Y,
                    origin.z - DungeonArenaLayout.SCHEMATIC_ORIGIN_OFFSET_BLOCKS,
                )
            }
            schematicSlots.remove(instance.slot)
        }
        instances.remove(instance.slot)
    }
    fun recover(player: Player) {
        if (plugin.activeWorldProjection.registry.isOverworldMode()) {
            recoverOverworld(player)
            return
        }
        if(player.world.name == DemoWorld.NAME && player.x >= DungeonArenaLayout.RECOVERY_MIN_COORDINATE && player.z >= DungeonArenaLayout.RECOVERY_MIN_COORDINATE && instances.values.none { player.uniqueId in it.members }) player.teleport(player.world.spawnLocation)
    }

    /** Minecraft が読み戻した地上位置を検証し、上空残留時は保存先へ戻します。 */
    private fun recoverOverworld(player: Player) {
        val record = pendingReturns[player.uniqueId] ?: return
        val world = Bukkit.getWorld(record.world) ?: return
        val original = player.location.clone()
        val chunkX = original.blockX shr 4
        val chunkZ = original.blockZ shr 4
        original.world.getChunkAtAsync(chunkX, chunkZ).whenComplete { _, error -> plugin.sync {
            if (!player.isOnline || error != null || player.location != original) return@sync
            val safe = original.world == world && isSafeReturn(original)
            val acknowledged = canAcknowledgeDungeonReturn(
                DungeonInstanceCenter(record.slotX, record.slotY, record.slotZ), original.x, original.y, original.z,
                original.world == world, safe,
            )
            if (acknowledged) {
                returnStore.removeAll(setOf(player.uniqueId)).whenComplete { _, removeError -> plugin.sync {
                    if (removeError == null) pendingReturns.remove(player.uniqueId)
                    else plugin.logger.warning("Dungeon return acknowledgement failed: ${removeError.message}")
                } }
            } else {
                val destination = Location(world, record.x, record.y, record.z, record.yaw, record.pitch)
                world.getChunkAtAsync(destination.blockX shr 4, destination.blockZ shr 4).whenComplete { _, loadError -> plugin.sync {
                    if (loadError == null && player.isOnline && player.location == original) {
                        val safeDestination = destination.takeIf(::isSafeReturn) ?: plugin.luminaSpawn()
                        safeDestination?.let(player::teleport)
                    }
                } }
            }
        } }
    }

    private fun instanceWorld(instance: Instance): World? = Bukkit.getWorld(
        if (instance.overworld) plugin.activeWorldProjection.worldName() else DemoWorld.NAME,
    )

    private fun instanceCenter(instance: Instance): Pair<Int, Int> = if (instance.overworld) {
        overworldLayout.center(instance.slot).let { it.x to it.z }
    } else DungeonArenaLayout.originForSlot(instance.slot).let { it.x to it.z }

    /** チャンク読込済みの足場と頭上二段を確認します。 */
    private fun isSafeReturn(location: Location): Boolean {
        val world = location.world ?: return false
        if (location.blockY <= world.minHeight || location.blockY + 1 >= world.maxHeight ||
            !world.worldBorder.isInside(location)) return false
        val ground = location.clone().subtract(0.0, 1.0, 0.0).block.type
        return location.block.type.isAir && location.clone().add(0.0, 1.0, 0.0).block.type.isAir &&
            ground.isSolid && ground !in setOf(Material.MAGMA_BLOCK, Material.CACTUS, Material.CAMPFIRE, Material.SOUL_CAMPFIRE)
    }
    fun tick() {
        tick+=PaperTaskIntervals.PLAYER_UPDATE_TICKS
        instances.values.toList().filter { tick-it.started >= plugin.snapshot.world.tuning.instanceTimeout }.forEach { instance ->
            instance.members.toList().forEach { Bukkit.getPlayer(it)?.let { p -> p.sendMessage(plugin.message(p,"ec.dungeon.timeout")); leave(p) } }; close(instance)
        }
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) fun teleport(e: PlayerTeleportEvent) {
        val instance=instances.values.firstOrNull { e.player.uniqueId in it.members } ?: return
        val to=e.to
        val inside = if (instance.overworld) {
            val bounds = overworldLayout.bounds(instance.slot)
            to.world?.name == plugin.activeWorldProjection.worldName() &&
                to.x >= bounds.minX && to.x < bounds.maxX + 1 && to.y >= bounds.minY && to.y < bounds.maxY + 1 &&
                to.z >= bounds.minZ && to.z < bounds.maxZ + 1
        } else to.world?.name == DemoWorld.NAME && DungeonArenaLayout.isInsideBounds(to,instance.slot)
        if(!inside) {
            instance.members.remove(e.player.uniqueId); if(instance.members.isEmpty()) close(instance)
        }
    }
    @EventHandler fun quit(e: PlayerQuitEvent) = leave(e.player)
    @EventHandler fun respawn(e: PlayerRespawnEvent) { val instance=instances.values.firstOrNull { e.player.uniqueId in it.members } ?: return; e.respawnLocation=instance.returns[e.player.uniqueId] ?: e.respawnLocation; instance.members.remove(e.player.uniqueId); if(instance.members.isEmpty()) close(instance) }
    @EventHandler fun move(e: PlayerMoveEvent) {
        val instance = instances.values.firstOrNull { e.player.uniqueId in it.members }
        if (instance?.overworld == true && e.to.y < overworldLayout.bounds(instance.slot).minY) {
            leave(e.player)
        } else if (e.player.world.name == DemoWorld.NAME && e.to.y < DungeonArenaLayout.VOID_RECOVERY_Y) {
            e.player.teleport(e.player.world.spawnLocation)
            leave(e.player)
        }
    }
    fun shutdown() {
        instances.values.toList().forEach { instance -> instance.members.toList().forEach { Bukkit.getPlayer(it)?.let(::leave) }; close(instance) }
        placement?.close()
        scanner?.close()
        returnStore.close()
    }

    private companion object { const val SCAN_BUDGET = 4_096 }
}


