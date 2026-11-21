package net.nakaymi.etherealchronicle.qa

import net.nakaymi.etherealchronicle.paper.DemoGenerator
import net.nakaymi.etherealchronicle.paper.DemoWorld
import net.nakaymi.etherealchronicle.paper.RpgPlugin
import org.bukkit.Material
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DemoWorldMigrationTest {
    @Test
    fun `chapter two surface generation and owned v1 migration preserve existing blocks`() {
        assertEquals(Material.SNOW_BLOCK,DemoGenerator.surfaceAt(480,12))
        assertEquals(Material.BLUE_ICE,DemoGenerator.surfaceAt(480,0))
        assertEquals(Material.SNOW_BLOCK,DemoGenerator.surfaceAt(600,10))
        assertEquals(Material.BLUE_ICE,DemoGenerator.surfaceAt(768,0))
        assertEquals(Material.PACKED_ICE,DemoGenerator.surfaceAt(700,3))

        val server=MockBukkit.mock()
        try {
            val plugin=MockBukkit.load(RpgPlugin::class.java)
            val world=server.getWorld(DemoWorld.NAME) ?: server.addSimpleWorld(DemoWorld.NAME)
            val folder=Files.createTempDirectory("ec-owned-v1")
            Files.createDirectories(folder)
            Files.writeString(folder.resolve(".etherealchronicle-owned"),"test-owned\n")
            Files.writeString(folder.resolve(".etherealchronicle-built"),"v1\n")
            world.getBlockAt(384,64,0).type=Material.GOLD_BLOCK // existing Chapter 1 owner build
            world.getBlockAt(488,65,0).type=Material.DIAMOND_BLOCK // player edit at a new landmark footprint

            assertTrue(DemoWorld.migrateToV2(plugin,world,folder))
            assertEquals("v2",Files.readString(folder.resolve(".etherealchronicle-built")).trim())
            assertEquals(Material.GOLD_BLOCK,world.getBlockAt(384,64,0).type,"Chapter 1 was overwritten")
            assertEquals(Material.DIAMOND_BLOCK,world.getBlockAt(488,65,0).type,"player block was overwritten")
            assertEquals(Material.PRISMARINE_BRICKS,world.getBlockAt(472,65,-8).type,"outpost landmark missing")

            world.getBlockAt(472,65,-7).type=Material.EMERALD_BLOCK
            assertTrue(DemoWorld.migrateToV2(plugin,world,folder))
            assertEquals(Material.EMERALD_BLOCK,world.getBlockAt(472,65,-7).type,"v2 boot re-applied migration")

        } finally { MockBukkit.unmock() }
    }

    @Test
    fun `existing unowned ec world is never changed`() {
        val server=MockBukkit.mock()
        try {
            val world=server.addSimpleWorld(DemoWorld.NAME)
            val block=world.getBlockAt(480,64,0); block.type=Material.GOLD_BLOCK
            val plugin=MockBukkit.load(RpgPlugin::class.java)
            val folder=Files.createTempDirectory("ec-unowned")
            assertTrue(!DemoWorld.migrateToV2(plugin,world,folder))
            assertEquals(Material.GOLD_BLOCK,block.type)
        } finally { MockBukkit.unmock() }
    }
}
