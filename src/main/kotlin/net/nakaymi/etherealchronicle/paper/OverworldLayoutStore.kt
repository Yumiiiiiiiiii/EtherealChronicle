package net.nakaymi.etherealchronicle.paper

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import net.nakaymi.etherealchronicle.core.OverworldSite
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

/** 生成の中断と完了を区別し、意図しない再配置を防ぎます。 */
enum class OverworldBuildStatus { IN_PROGRESS, COMPLETED }

/** 各拠点の生成状態です。 */
enum class OverworldSiteStatus { PENDING, GENERATING, READY, FAILED }

/** ワールド内の整数ブロック座標です。 */
data class OverworldPoint(val x: Int, val y: Int, val z: Int)

/** ポータルの回転後の直方体範囲と安全な退出先です。 */
data class OverworldPortal(val dungeonId: String, val areaId: String, val min: OverworldPoint, val max: OverworldPoint, val exit: OverworldPoint)

/** 拠点の選定結果と建物、道標などの配置結果です。 */
data class OverworldSiteLayout(val site: OverworldSite, val status: OverworldSiteStatus, val anchors: Map<String, OverworldPoint> = emptyMap())

/** サーバー再起動後も同じアンカーを使うための生成ジャーナルです。 */
data class OverworldLayout(
    val version: String = "overworld-v1",
    val worldId: String,
    val worldName: String,
    val seed: Long,
    val status: OverworldBuildStatus,
    val sites: List<OverworldSiteLayout>,
    val portals: List<OverworldPortal> = emptyList(),
)

/** 完了前の状態を含むJSONを一時ファイルから原子的に更新します。I/Oは呼び出し側で非同期実行してください。 */
class OverworldLayoutStore(private val worldFolder: Path) {
    private val file = worldFolder.resolve(".etherealchronicle-overworld.json")
    private val gson = GsonBuilder().setPrettyPrinting().create()

    /** ファイルがなければnull、破損や別ワールドの場合は例外を返します。 */
    fun load(worldId: UUID, worldName: String, seed: Long): OverworldLayout? {
        if (!Files.exists(file)) return null
        require(Files.size(file) <= MAX_BYTES) { "Overworld journal exceeds size limit: $file" }
        val root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).asJsonObject
        val layout = gson.fromJson(root, OverworldLayout::class.java) ?: error("Empty overworld journal")
        validate(layout)
        require(layout.worldId == worldId.toString() && layout.worldName == worldName && layout.seed == seed) {
            "Overworld journal belongs to a different world or seed"
        }
        return layout
    }

    /** 一時ファイルをflushしてから置換し、途中失敗時も直前のジャーナルを保ちます。 */
    fun save(layout: OverworldLayout) {
        validate(layout)
        Files.createDirectories(worldFolder)
        val bytes = gson.toJson(layout).toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_BYTES)
        val temporary = Files.createTempFile(worldFolder, ".etherealchronicle-overworld-", ".tmp")
        try {
            FileChannel.open(temporary, StandardOpenOption.WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    /** 所有マーカーは完了後にのみ作り、既存マーカーは上書きしません。 */
    fun markCompleted() {
        val marker = worldFolder.resolve(".etherealchronicle-owned")
        if (!Files.exists(marker)) Files.writeString(marker, "overworld-v1\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)
    }

    /** 破損したjournalを新規ワールド扱いせず、再生成を確実に止めます。 */
    private fun validate(layout: OverworldLayout) {
        require(layout.version == "overworld-v1") { "Unsupported overworld journal version" }
        require(runCatching { UUID.fromString(layout.worldId) }.isSuccess && layout.worldName.isNotBlank())
        require(layout.status in OverworldBuildStatus.entries)
        require(layout.sites.size == SITE_IDS.size && layout.sites.map { it.site.id }.toSet() == SITE_IDS)
        require(layout.sites.all { it.status in OverworldSiteStatus.entries && it.site.tier in 1..3 && it.site.biome.isNotBlank() && it.anchors.size <= 128 })
        require(layout.portals.size <= 4 && layout.portals.map { it.dungeonId }.distinct().size == layout.portals.size)
        require(layout.portals.all { portal ->
            val site = layout.sites.singleOrNull { it.site.id == portal.areaId }?.site
            site != null && DUNGEON_AREAS[portal.dungeonId] == portal.areaId &&
                portal.min.x <= portal.max.x && portal.min.y <= portal.max.y && portal.min.z <= portal.max.z &&
                listOf(portal.min, portal.max, portal.exit).all { point ->
                    kotlin.math.abs(point.x.toLong() - site.x) <= 72 && kotlin.math.abs(point.z.toLong() - site.z) <= 72 && point.y in -2048..2048
                }
        })
        if (layout.status == OverworldBuildStatus.COMPLETED) {
            require(layout.sites.all { it.status == OverworldSiteStatus.READY } && layout.portals.size == 4)
        }
    }

    private companion object {
        const val MAX_BYTES = 4 * 1024 * 1024
        val SITE_IDS = setOf("ec:area_lumina_port", "ec:area_ashwood_forest", "ec:area_weeping_mine", "ec:area_stargazer_hill",
            "ec:area_void_sanctum", "ec:area_frostpeak_outpost", "ec:area_glacier_ridge", "ec:area_crystalline_cavern", "ec:area_glacial_citadel")
        val DUNGEON_AREAS = mapOf("ec:dungeon_novice_arena" to "ec:area_ashwood_forest", "ec:dungeon_aether_crucible" to "ec:area_stargazer_hill",
            "ec:dungeon_void_sanctum" to "ec:area_void_sanctum", "ec:dungeon_glacial_citadel" to "ec:area_glacial_citadel")
    }
}
