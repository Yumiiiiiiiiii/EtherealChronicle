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
    fun `declared hub and chapter two landmarks retain representative blocks`() {
        val server = MockBukkit.mock()
        try {
            val world = server.addSimpleWorld(DemoWorld.NAME)
            DemoWorld.build(world)
            DemoWorld.buildChapter2(world)

            assertEquals(Material.QUARTZ_BRICKS, world.getBlockAt(0, 64, 0).type)
            assertEquals(Material.OAK_LEAVES, world.getBlockAt(84, 65, 12).type)
            assertEquals(Material.OAK_LEAVES, world.getBlockAt(108, 65, 3).type)
            assertEquals(Material.BLUE_ICE, DemoGenerator.surfaceAt(432, 0))
            assertEquals(Material.PRISMARINE_BRICKS, world.getBlockAt(488, 65, 8).type)
        } finally {
            MockBukkit.unmock()
        }
    }

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

    @Test
    fun `fresh owned build starts at v2 without claiming a migration`() {
        val folder = Files.createTempDirectory("ec-fresh-v2")
        Files.writeString(folder.resolve(".etherealchronicle-owned"), "test-owned\n")
        DemoWorld.markFreshBuild(folder)
        assertEquals("v2", Files.readString(folder.resolve(".etherealchronicle-built")).trim())
        val server=MockBukkit.mock()
        try {
            val plugin=MockBukkit.load(RpgPlugin::class.java)
            val world=server.addSimpleWorld(DemoWorld.NAME)
            assertTrue(DemoWorld.migrateToV2(plugin,world,folder))
            assertEquals("v2", Files.readString(folder.resolve(".etherealchronicle-built")).trim())
            assertEquals(Material.AIR, world.getBlockAt(480,65,0).type, "fresh world must not run the v1 migration builder")
        } finally { MockBukkit.unmock() }
    }

    @Test
    fun `v2 life pools migrate once and preserve non air player edits`() {
        val server=MockBukkit.mock()
        try {
            val world=server.addSimpleWorld(DemoWorld.NAME)
            val plugin=MockBukkit.load(RpgPlugin::class.java)
            val folder=Files.createTempDirectory("ec-owned-v2")
            Files.writeString(folder.resolve(".etherealchronicle-owned"),"test-owned\n")
            Files.writeString(folder.resolve(".etherealchronicle-built"),"v2\n")
            world.getBlockAt(10,65,28).type=Material.GOLD_BLOCK
            assertTrue(DemoWorld.migrateToV3(plugin,world,folder))
            assertEquals("v3",Files.readString(folder.resolve(".etherealchronicle-built")).trim())
            assertEquals(Material.GOLD_BLOCK,world.getBlockAt(10,65,28).type)
            assertEquals(Material.WATER,world.getBlockAt(11,65,28).type)
            assertEquals(Material.PACKED_ICE,world.getBlockAt(13,65,28).type)
            assertTrue(!DemoWorld.migrateToV3(plugin,world,folder))
            assertEquals(Material.WATER,world.getBlockAt(11,65,28).type)
        } finally { MockBukkit.unmock() }
    }

    @Test
    fun `v3 secret spring migration is idempotent and only fills air`() {
        val server=MockBukkit.mock()
        try {
            val world=server.addSimpleWorld(DemoWorld.NAME)
            val plugin=MockBukkit.load(RpgPlugin::class.java)
            val folder=Files.createTempDirectory("ec-owned-v3")
            Files.writeString(folder.resolve(".etherealchronicle-owned"),"test-owned\n")
            Files.writeString(folder.resolve(".etherealchronicle-built"),"v3\n")
            world.getBlockAt(127,65,-24).type=Material.DIAMOND_BLOCK
            assertTrue(DemoWorld.migrateToV4(plugin,world,folder))
            assertEquals("v4",Files.readString(folder.resolve(".etherealchronicle-built")).trim())
            assertEquals(Material.WATER,world.getBlockAt(124,65,-24).type)
            assertEquals(Material.PACKED_ICE,world.getBlockAt(121,65,-24).type)
            assertEquals(Material.DIAMOND_BLOCK,world.getBlockAt(127,65,-24).type)
            assertTrue(!DemoWorld.migrateToV4(plugin,world,folder))
            assertEquals(Material.WATER,world.getBlockAt(124,65,-24).type)
        } finally { MockBukkit.unmock() }
    }

    @Test
    fun `v4 structure migration leaves occupied cells intact and advances once`() {
        val server=MockBukkit.mock()
        try {
            val world=server.addSimpleWorld(DemoWorld.NAME)
            val plugin=MockBukkit.load(RpgPlugin::class.java)
            val folder=Files.createTempDirectory("ec-owned-v4")
            Files.writeString(folder.resolve(".etherealchronicle-owned"),"test-owned\n")
            Files.writeString(folder.resolve(".etherealchronicle-built"),"v4\n")
            val playerBlock=world.getBlockAt(26,66,28)
            playerBlock.type=Material.EMERALD_BLOCK

            assertTrue(DemoWorld.migrateToV5(plugin,world,folder))
            assertEquals("v5",Files.readString(folder.resolve(".etherealchronicle-built")).trim())
            assertEquals(Material.EMERALD_BLOCK,playerBlock.type)
            assertTrue(!DemoWorld.migrateToV5(plugin,world,folder))
        } finally { MockBukkit.unmock() }
    }

    @Test
    fun `v4 fixture places the v5 structure once and records the migrated marker`() {
        val server=MockBukkit.mock()
        try {
            val world=server.addSimpleWorld(DemoWorld.NAME)
            val plugin=MockBukkit.load(RpgPlugin::class.java)
            val folder=Files.createTempDirectory("ec-owned-v4-clear")
            Files.writeString(folder.resolve(".etherealchronicle-owned"),"test-owned\n")
            Files.writeString(folder.resolve(".etherealchronicle-built"),"v4\n")
            var placements=0

            assertTrue(DemoWorld.migrateToV5(plugin,world,folder) { _, target, x, y, z ->
                placements++
                target.getBlockAt(x,y,z).type=Material.STONE_BRICKS
                true
            })
            assertEquals("v5",Files.readString(folder.resolve(".etherealchronicle-built")).trim())
            assertEquals(Material.STONE_BRICKS,world.getBlockAt(24,65,28).type)
            assertTrue(!DemoWorld.migrateToV5(plugin,world,folder))
            assertEquals(1,placements,"the saved v5 marker must prevent a second placement")
            assertEquals(Material.STONE_BRICKS,world.getBlockAt(24,65,28).type)
        } finally { MockBukkit.unmock() }
    }
}
