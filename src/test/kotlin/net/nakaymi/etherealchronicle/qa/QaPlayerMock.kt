package net.nakaymi.etherealchronicle.qa

import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.PlayerMock
import java.util.UUID

/** PlayerMock whose saveData() is a no-op: MockBukkit leaves it unimplemented, production code calls it after inventory transactions. */
class QaPlayerMock(server: ServerMock, name: String, uuid: UUID = UUID.randomUUID()) : PlayerMock(server, name, uuid) {
    var saveDataCalls = 0
    override fun saveData() { saveDataCalls++ }
}

/** テスト用ワールド: 全チャンクを読み込み済みとして扱い、非同期チャンク取得・チャンクチケットも即時成功させます（MockBukkit 未実装分の補完）。 */
class QaWorldMock(worldName: String) : org.mockbukkit.mockbukkit.world.WorldMock(org.bukkit.Material.STONE, -64, 320, 64) {
    init { setName(worldName) }
    override fun isChunkLoaded(x: Int, z: Int): Boolean = true
    override fun getChunkAtAsync(x: Int, z: Int, gen: Boolean, urgent: Boolean): java.util.concurrent.CompletableFuture<org.bukkit.Chunk> =
        java.util.concurrent.CompletableFuture.completedFuture(getChunkAt(x, z))
    override fun addPluginChunkTicket(x: Int, z: Int, plugin: org.bukkit.plugin.Plugin): Boolean = true
    override fun removePluginChunkTicket(x: Int, z: Int, plugin: org.bukkit.plugin.Plugin): Boolean = true
    companion object {
        /** 既に同名ワールドがあればそれを返し、無ければ追加します。 */
        fun add(server: ServerMock, name: String): QaWorldMock =
            (server.getWorld(name) as? QaWorldMock) ?: QaWorldMock(name).also { server.addWorld(it) }
    }
}

/** 非同期のダンジョン入退場（チャンク確保→次tick処理）が終わるまで tick を進め、条件成立で true を返します。 */
fun ServerMock.qaPump(timeoutMs: Long = 10_000, cond: () -> Boolean): Boolean {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) {
        repeat(5) { scheduler.performOneTick(); if (cond()) return true }
        Thread.sleep(2)
    }
    return cond()
}

/**
 * MockBukkit が扱えない装飾ブロック状態を避けるため、実アリーナの代わりに床だけの 55x34x55 Schematic を
 * ダンジョン制御へ差し込みます（配置・退出・スロット管理の経路は本番と同じです）。
 */
fun seedArenaSchematic(controller: net.nakaymi.etherealchronicle.paper.DungeonController, dungeonId: String, floorState: String = "minecraft:stone_bricks") {
    val w = net.nakaymi.etherealchronicle.core.OverworldDungeonInstanceLayout.ARENA_WIDTH
    val h = net.nakaymi.etherealchronicle.core.OverworldDungeonInstanceLayout.ARENA_HEIGHT
    val blocks = IntArray(w * h * w)
    val floorY = net.nakaymi.etherealchronicle.core.OverworldDungeonInstanceLayout.FLOOR_OFFSET
    for (z in 0 until w) for (x in 0 until w) blocks[(floorY * w + z) * w + x] = 1
    val data = net.nakaymi.etherealchronicle.paper.SchematicData(w, h, w, listOf("minecraft:air", floorState), blocks)
    val field = net.nakaymi.etherealchronicle.paper.DungeonController::class.java.getDeclaredField("loadedSchematics").apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    (field.get(controller) as MutableMap<String, net.nakaymi.etherealchronicle.paper.SchematicData>)[net.nakaymi.etherealchronicle.paper.WorldSchematicLayout.dungeonPath(dungeonId)!!] = data
}
