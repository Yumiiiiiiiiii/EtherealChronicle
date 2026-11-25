package net.nakaymi.etherealchronicle.paper

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/** 全同梱Schematicのドア接地・壁枠・両開きヒンジ対称性・ランタン/松明の宙浮きゼロを検証します。 */
class SchematicBuildQualityTest {
    private fun loadAllSchematics(): List<Pair<String, SchematicData>> {
        val root = Path.of("src/main/resources/schematics")
        if (!Files.exists(root)) return emptyList()
        return Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".schem") }
                .sorted()
                .map { path ->
                    val rel = "schematics/" + root.relativize(path).toString().replace('\\', '/')
                    rel to requireNotNull(SchematicResources.load(rel)) { "Failed to load $rel" }
                }
                .toList()
        }
    }

    private fun blockId(state: String): String = state.substringBefore('[')

    private fun props(state: String): Map<String, String> {
        val start = state.indexOf('[')
        if (start < 0 || !state.endsWith(']')) return emptyMap()
        return state.substring(start + 1, state.length - 1)
            .split(',')
            .filter { '=' in it }
            .associate {
                val idx = it.indexOf('=')
                it.substring(0, idx) to it.substring(idx + 1)
            }
    }

    private fun isDoor(state: String): Boolean {
        val id = blockId(state)
        return id.endsWith("_door") && !id.endsWith("trapdoor")
    }

    private fun isSolidSupport(state: String): Boolean = !isAir(state) && blockId(state) != "minecraft:water" && blockId(state) != "minecraft:lava"

    @Test
    fun `all bundled schematics have properly framed doors symmetrical hinges and zero floating lights`() {
        val schematics = loadAllSchematics()
        assertTrue(schematics.isNotEmpty(), "Expected bundled schematics under src/main/resources/schematics")

        val issues = mutableListOf<String>()

        for ((name, data) in schematics) {
            fun stateAt(x: Int, y: Int, z: Int): String {
                if (x !in 0 until data.width || y !in 0 until data.height || z !in 0 until data.length) {
                    return "minecraft:air"
                }
                return data.blockStateAt(x, y, z)
            }

            val lowerDoors = mutableMapOf<Triple<Int, Int, Int>, Pair<String, Map<String, String>>>()

            for (y in 0 until data.height) {
                for (z in 0 until data.length) {
                    for (x in 0 until data.width) {
                        val state = stateAt(x, y, z)
                        if (isAir(state)) continue
                        val id = blockId(state)
                        val p = props(state)

                        if (isDoor(state)) {
                            val half = p["half"]
                            if (half == "lower") {
                                lowerDoors[Triple(x, y, z)] = id to p
                                // 1. Matching upper half directly above
                                val above = stateAt(x, y + 1, z)
                                val aboveP = props(above)
                                if (blockId(above) != id || aboveP["half"] != "upper" || aboveP["facing"] != p["facing"] || aboveP["hinge"] != p["hinge"]) {
                                    issues += "$name door($x,$y,$z) lower missing matching upper: above=$above"
                                }
                                // 2. Solid floor directly underneath
                                val below = stateAt(x, y - 1, z)
                                if (!isSolidSupport(below)) {
                                    issues += "$name door($x,$y,$z) has non-solid floor: $below"
                                }
                            } else if (half == "upper") {
                                val below = stateAt(x, y - 1, z)
                                if (blockId(below) != id || props(below)["half"] != "lower") {
                                    issues += "$name door($x,$y,$z) orphan upper half: below=$below"
                                }
                            }
                        } else if (id == "minecraft:lantern" || id == "minecraft:soul_lantern") {
                            val hanging = p["hanging"] == "true"
                            if (hanging) {
                                val above = stateAt(x, y + 1, z)
                                if (isAir(above)) {
                                    issues += "$name hanging lantern($x,$y,$z) has air above"
                                }
                            } else {
                                val below = stateAt(x, y - 1, z)
                                if (isAir(below)) {
                                    issues += "$name standing lantern($x,$y,$z) has air below"
                                }
                            }
                        } else if (id == "minecraft:torch" || id == "minecraft:soul_torch") {
                            val below = stateAt(x, y - 1, z)
                            if (isAir(below)) {
                                issues += "$name floor torch($x,$y,$z) has air below"
                            }
                        } else if (id == "minecraft:wall_torch" || id == "minecraft:soul_wall_torch") {
                            val (dx, dz) = when (p["facing"]) {
                                "north" -> 0 to 1
                                "south" -> 0 to -1
                                "east" -> -1 to 0
                                "west" -> 1 to 0
                                else -> 0 to 0
                            }
                            if (dx != 0 || dz != 0) {
                                val behind = stateAt(x + dx, y, z + dz)
                                if (isAir(behind)) {
                                    issues += "$name wall torch($x,$y,$z) facing=${p["facing"]} has air behind"
                                }
                            }
                        }
                    }
                }
            }

            // 3. Verify every lower door is framed by at least one side wall (or partner is framed) and double-door hinges are symmetrical
            for ((pos, entry) in lowerDoors) {
                val (x, y, z) = pos
                val (id, p) = entry
                val facing = p["facing"] ?: continue
                val (sideDx, sideDz) = if (facing == "north" || facing == "south") 1 to 0 else 0 to 1
                val leftPos = Triple(x - sideDx, y, z - sideDz)
                val rightPos = Triple(x + sideDx, y, z + sideDz)
                val leftState = stateAt(leftPos.first, leftPos.second, leftPos.third)
                val rightState = stateAt(rightPos.first, rightPos.second, rightPos.third)
                val leftIsDoor = leftPos in lowerDoors
                val rightIsDoor = rightPos in lowerDoors

                val hasFrame = (!isAir(leftState) && !leftIsDoor) || (!isAir(rightState) && !rightIsDoor)
                if (!hasFrame) {
                    issues += "$name door($x,$y,$z) facing=$facing is unframed on both sides ($leftState / $rightState)"
                }

                // Check symmetrical hinge on double doors (check once from the smaller coordinate side)
                if (rightIsDoor) {
                    val partner = lowerDoors.getValue(rightPos)
                    if (partner.first == id && partner.second["facing"] == facing) {
                        val expectedLeftHinge = when (facing) {
                            "north", "west" -> "left"
                            else -> "right"
                        }
                        val expectedRightHinge = if (expectedLeftHinge == "left") "right" else "left"
                        if (p["hinge"] != expectedLeftHinge || partner.second["hinge"] != expectedRightHinge) {
                            issues += "$name double door($x,$y,$z)+(${rightPos.first},${rightPos.second},${rightPos.third}) facing=$facing has asymmetrical hinges (${p["hinge"]},${partner.second["hinge"]}), expected ($expectedLeftHinge,$expectedRightHinge)"
                        }
                    }
                }
            }
        }

        assertTrue(issues.isEmpty(), "Schematic build quality issues (${issues.size}):\n" + issues.joinToString("\n"))
    }
}
