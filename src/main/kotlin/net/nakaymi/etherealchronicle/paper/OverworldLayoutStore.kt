package net.nakaymi.etherealchronicle.paper

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import net.nakaymi.etherealchronicle.core.OverworldSite
import net.nakaymi.etherealchronicle.core.OverworldSiteIds
import net.nakaymi.etherealchronicle.core.OverworldDungeonIds
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.AtomicMoveNotSupportedException
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
        val layout = try {
            val root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).asJsonObject
            validateJsonShape(root)
            gson.fromJson(root, OverworldLayout::class.java).also(::validate)
        } catch (error: RuntimeException) {
            throw IllegalArgumentException("Invalid overworld journal: $file", error)
        }
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
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    /** 所有マーカーは完了後にのみ作り、既存マーカーは上書きしません。 */
    fun markCompleted() {
        val marker = worldFolder.resolve(".etherealchronicle-owned")
        if (!Files.exists(marker)) Files.writeString(marker, "overworld-v1\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)
    }

    /** Gson の非 null 宣言を信用せず、デシリアライズ前に必須フィールドを確認します。 */
    private fun validateJsonShape(root: com.google.gson.JsonObject) {
        fun fields(value: com.google.gson.JsonObject, vararg names: String) {
            names.forEach { name -> require(value.has(name) && !value.get(name).isJsonNull) { "Missing journal field: $name" } }
        }
        fields(root, "version", "worldId", "worldName", "seed", "status", "sites", "portals")
        root.getAsJsonArray("sites").forEach { element ->
            val site = element.asJsonObject
            fields(site, "site", "status", "anchors")
            fields(site.getAsJsonObject("site"), "id", "x", "y", "z", "tier", "biome")
            site.getAsJsonObject("anchors").entrySet().forEach { (_, point) ->
                fields(point.asJsonObject, "x", "y", "z")
            }
        }
        root.getAsJsonArray("portals").forEach { element ->
            val portal = element.asJsonObject
            fields(portal, "dungeonId", "areaId", "min", "max", "exit")
            listOf("min", "max", "exit").forEach { fields(portal.getAsJsonObject(it), "x", "y", "z") }
        }
    }

    /** 破損したjournalを新規ワールド扱いせず、再生成を確実に止めます。 */
    private fun validate(layout: OverworldLayout) {
        require(layout.version == "overworld-v1") { "Unsupported overworld journal version" }
        require(runCatching { UUID.fromString(layout.worldId) }.isSuccess && layout.worldName.isNotBlank())
        require(layout.status in OverworldBuildStatus.entries)
        require(layout.sites.size == OverworldSiteIds.all.size && layout.sites.map { it.site.id }.toSet() == OverworldSiteIds.all)
        require(layout.sites.all { it.status in OverworldSiteStatus.entries && it.site.tier in 1..3 && it.site.biome.isNotBlank() && it.anchors.size <= 128 })
        require(layout.portals.size <= 4 && layout.portals.map { it.dungeonId }.distinct().size == layout.portals.size)
        require(layout.portals.all { portal ->
            val site = layout.sites.singleOrNull { it.site.id == portal.areaId }?.site
            site != null && OverworldDungeonIds.areas[portal.dungeonId] == portal.areaId &&
                portal.min.x <= portal.max.x && portal.min.y <= portal.max.y && portal.min.z <= portal.max.z &&
                listOf(portal.min, portal.max, portal.exit).all { point ->
                    kotlin.math.abs(point.x.toLong() - site.x) <= PORTAL_RADIUS && kotlin.math.abs(point.z.toLong() - site.z) <= PORTAL_RADIUS && point.y in MIN_VALID_Y..MAX_VALID_Y
                }
        })
        // READY 済みのダンジョン入口は、未完了ジャーナルでもポータル記録を失ってはなりません。
        val readyAreas = layout.sites.filter { it.status == OverworldSiteStatus.READY }.mapTo(HashSet()) { it.site.id }
        require(OverworldDungeonIds.areas.all { (dungeon, area) ->
            area !in readyAreas || layout.portals.any { it.dungeonId == dungeon && it.areaId == area }
        }) { "READY dungeon site is missing its portal" }
        if (layout.status == OverworldBuildStatus.COMPLETED) {
            require(layout.sites.all { it.status == OverworldSiteStatus.READY } && layout.portals.size == 4)
            val lumina = layout.sites.single { it.site.id == OverworldSiteIds.LUMINA_PORT }
            require(lumina.anchors.keys.containsAll(setOf("spawn", "plaza_min", "plaza_max", "plaza_center")))
            require(OverworldDungeonIds.areas.all { (dungeon, area) -> layout.portals.any { it.dungeonId == dungeon && it.areaId == area } })
        }
    }

    private companion object {
        const val MAX_BYTES = 4 * 1024 * 1024
        const val PORTAL_RADIUS = 72
        const val MIN_VALID_Y = -2048
        const val MAX_VALID_Y = 2048
    }
}
