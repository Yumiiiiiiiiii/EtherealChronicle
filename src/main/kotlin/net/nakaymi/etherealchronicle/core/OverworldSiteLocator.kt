package net.nakaymi.etherealchronicle.core

import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** 地形取得の実装をPaperから分離するための観測値です。 */
data class SiteTerrain(val y: Int, val biome: String, val dryLand: Boolean, val relief: Int = 0, val nearbyWater: Boolean = false)

/** 決定済みの地表中心、バイオームと採用したフォールバック段階です。 */
data class OverworldSite(val id: String, val x: Int, val y: Int, val z: Int, val tier: Int, val biome: String)

/** 限定された候補をシード順に評価し、条件を満たす拠点を決定します。 */
object OverworldSiteLocator {
    private data class Spec(val id: String, val minRadius: Int, val maxRadius: Int, val biomes: Set<String>, val frostRelative: Boolean = false)
    private val specs = listOf(
        Spec("ec:area_lumina_port", 0, 384, setOf("PLAINS", "MEADOW", "BEACH", "RIVER", "SUNFLOWER_PLAINS")),
        Spec("ec:area_ashwood_forest", 220, 650, setOf("FOREST", "BIRCH_FOREST", "DARK_FOREST", "TAIGA", "FLOWER_FOREST", "OLD_GROWTH_BIRCH_FOREST")),
        Spec("ec:area_weeping_mine", 320, 880, setOf("WINDSWEPT_HILLS", "STONY_PEAKS", "MEADOW", "SAVANNA_PLATEAU", "WINDSWEPT_GRAVELLY_HILLS")),
        Spec("ec:area_stargazer_hill", 400, 1020, setOf("MEADOW", "CHERRY_GROVE", "WINDSWEPT_HILLS", "SUNFLOWER_PLAINS")),
        Spec("ec:area_void_sanctum", 500, 1200, setOf("DARK_FOREST", "BADLANDS", "SWAMP", "PALE_GARDEN", "JAGGED_PEAKS", "WINDSWEPT_GRAVELLY_HILLS")),
        Spec("ec:area_frostpeak_outpost", 600, 1500, setOf("SNOWY_PLAINS", "SNOWY_TAIGA", "GROVE", "TAIGA")),
        Spec("ec:area_glacier_ridge", 180, 480, setOf("SNOWY_PLAINS", "SNOWY_TAIGA", "GROVE", "FROZEN_PEAKS", "JAGGED_PEAKS", "SNOWY_SLOPES"), true),
        Spec("ec:area_crystalline_cavern", 180, 480, setOf("SNOWY_PLAINS", "SNOWY_TAIGA", "GROVE", "FROZEN_PEAKS", "JAGGED_PEAKS", "SNOWY_SLOPES"), true),
        Spec("ec:area_glacial_citadel", 180, 480, setOf("SNOWY_PLAINS", "SNOWY_TAIGA", "GROVE", "FROZEN_PEAKS", "JAGGED_PEAKS", "SNOWY_SLOPES"), true),
    )
    private const val CANDIDATE_COUNT = 256
    private val coldSiteIds = setOf("ec:area_frostpeak_outpost", "ec:area_glacier_ridge", "ec:area_crystalline_cavern")

    /** 地形照会を注入して同じseedから同じ順序と結果を再現します。 */
    fun locate(seed: Long, terrainAt: (Int, Int) -> SiteTerrain): List<OverworldSite> {
        val search = Search(seed)
        while (true) {
            val (x, z) = search.nextCandidate() ?: break
            search.accept(terrainAt(x, z))
        }
        return search.result()
    }

    /** 呼び出し側がチャンクを非同期取得し、照会回数をtickごとに制限できる探索状態です。 */
    class Search(private val seed: Long) {
        private val sites = mutableListOf<OverworldSite>()
        private var center = 0 to 0
        private var candidates = candidatePoints(seed, specs.first(), center)
        private val samples = mutableListOf<Triple<Int, Int, SiteTerrain>>()
        private var awaiting: Pair<Int, Int>? = null

        /** 次に必要な座標を一つだけ予約し、完了時はnullを返します。 */
        fun nextCandidate(): Pair<Int, Int>? {
            check(awaiting == null) { "Previous terrain sample has not been supplied" }
            if (sites.size == specs.size) return null
            return candidates[samples.size].also { awaiting = it }
        }

        /** 予約した座標の地形を記録し、全候補の比較後に次の地点へ進みます。 */
        fun accept(sample: SiteTerrain) {
            val (x, z) = checkNotNull(awaiting) { "No terrain sample was requested" }
            awaiting = null
            samples += Triple(x, z, sample)
            if (samples.size != candidates.size) return
            val rule = specs[sites.size]
            val chosen = (1..3).firstNotNullOfOrNull { tier ->
                samples.asSequence().filter { (sx, sz, terrain) -> eligible(terrain, tier, rule.biomes) &&
                    separated(sx, sz, sites, if (tier == 3) 120 else 160) && (!rule.frostRelative || nearColdSites(sx, sz, sites)) }
                    .maxByOrNull { (_, sz, terrain) -> score(terrain, rule, sz - center.second) }
                    ?.let { (sx, sz, terrain) -> OverworldSite(rule.id, sx, terrain.y, sz, tier, terrain.biome) }
            } ?: error("No dry site found for ${rule.id}; world generation was not started")
            sites += chosen
            if (sites.size == specs.size) return
            val next = specs[sites.size]
            center = if (next.frostRelative) sites.first { it.id == "ec:area_frostpeak_outpost" }.let { it.x to it.z }
                else sites.first().let { it.x to it.z }
            candidates = candidatePoints(seed, next, center)
            samples.clear()
        }

        /** 全地点の選択が完了したときだけ防御コピーを返します。 */
        fun result(): List<OverworldSite> {
            check(sites.size == specs.size) { "Site search is incomplete" }
            return sites.toList()
        }
    }

    /** 円環内へ面積が偏らない候補を並べ、毎回同じ順序を得ます。 */
    private fun candidatePoints(seed: Long, rule: Spec, center: Pair<Int, Int>): List<Pair<Int, Int>> {
        val random = Random(seed xor rule.id.hashCode().toLong())
        return List(CANDIDATE_COUNT) {
            val radius = sqrt(random.nextDouble() * (rule.maxRadius.toDouble() * rule.maxRadius - rule.minRadius.toDouble() * rule.minRadius) + rule.minRadius.toDouble() * rule.minRadius)
            val angle = random.nextDouble() * 2 * PI
            center.first + (cos(angle) * radius).roundToInt() to center.second + (sin(angle) * radius).roundToInt()
        }
    }

    /** 各段階で水面・海洋を除き、高度と優先バイオームを判定します。 */
    private fun eligible(sample: SiteTerrain, tier: Int, preferred: Set<String>): Boolean {
        if (!sample.dryLand || sample.biome.uppercase().contains("OCEAN")) return false
        return when (tier) {
            1 -> sample.y in 63..145 && sample.relief <= 8 && sample.biome.uppercase() in preferred
            2 -> sample.y in 62..165
            else -> sample.y in 62..180
        }
    }

    /** 平面距離の二乗で既決地点との最小間隔を検証します。 */
    private fun separated(x: Int, z: Int, existing: List<OverworldSite>, min: Int): Boolean = existing.all {
        val dx = x.toLong() - it.x
        val dz = z.toLong() - it.z
        dx * dx + dz * dz >= min.toLong() * min
    }

    /** 氷雪地域の地点同士を近すぎず離れすぎない範囲に収めます。 */
    private fun nearColdSites(x: Int, z: Int, existing: List<OverworldSite>): Boolean = existing.filter { it.id in coldSiteIds }.all {
        val dx = x.toLong() - it.x
        val dz = z.toLong() - it.z
        dx * dx + dz * dz in 180L * 180..480L * 480
    }

    /** 平坦性を重視し、港の水辺と北部の雪原に小さな優先点を加えます。 */
    private fun score(sample: SiteTerrain, rule: Spec, relativeZ: Int): Int =
        -sample.relief.coerceAtLeast(0) * 10 +
            (if (sample.biome.uppercase() in rule.biomes) 100 else 0) +
            (if (rule.id == "ec:area_lumina_port" && sample.nearbyWater) 30 else 0) +
            (if (rule.id == "ec:area_frostpeak_outpost" && relativeZ < 0) 10 else 0)
}
