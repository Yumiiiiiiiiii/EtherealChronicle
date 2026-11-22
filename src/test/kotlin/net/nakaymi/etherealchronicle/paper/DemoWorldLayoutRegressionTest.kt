package net.nakaymi.etherealchronicle.paper

import org.bukkit.Material
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit

/** 手続き生成へ戻った場合の代表ランドマーク座標を固定します。 */
class DemoWorldLayoutRegressionTest {
    @Test
    fun `fallback build keeps hub plaza bridge and shrine landmarks`() {
        val server = MockBukkit.mock()
        try {
            val world = server.addSimpleWorld(DemoWorld.NAME)
            world.getBlockAt(432, DemoWorldLayout.GROUND_Y, 0).type = Material.AIR

            DemoWorld.build(world)
            DemoWorld.buildChapter2(world)

            assertEquals(Material.QUARTZ_BRICKS, world.getBlockAt(0, 64, 0).type)
            assertEquals(Material.OAK_LEAVES, world.getBlockAt(100, 65, 12).type)
            assertEquals(Material.BLUE_ICE, world.getBlockAt(432, 64, 0).type)
            assertEquals(Material.PRISMARINE_BRICKS, world.getBlockAt(472, 65, 0).type)
        } finally {
            MockBukkit.unmock()
        }
    }
}
