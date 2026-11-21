package net.nakaymi.etherealchronicle

import net.nakaymi.etherealchronicle.core.PlayerProfile
import net.nakaymi.etherealchronicle.data.PlayerStore
import net.nakaymi.etherealchronicle.paper.ResourcePackArtifact
import net.nakaymi.etherealchronicle.paper.ResourcePackEndpoint
import net.nakaymi.etherealchronicle.paper.EmbeddedPackServer
import net.nakaymi.etherealchronicle.paper.InventoryGrid
import net.nakaymi.etherealchronicle.paper.UiThemeProvider
import xyz.xenondevs.invui.gui.Gui
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipFile

class WorldUiFoundationTest {
    @Test
    fun `invui adapter uses nine columns and inventory row count`() {
        assertEquals(InventoryGrid(width = 9, height = 1), InventoryGrid.forInventorySize(9))
        assertEquals(InventoryGrid(width = 9, height = 3), InventoryGrid.forInventorySize(27))
        assertEquals(InventoryGrid(width = 9, height = 6), InventoryGrid.forInventorySize(54))
        assertEquals(54, InventoryGrid.forInventorySize(54).slotCount)
        assertNotNull(Gui.empty(InventoryGrid.forInventorySize(54).width, InventoryGrid.forInventorySize(54).height))
    }

    @Test
    fun `generated pack is a reproducible distributable archive with matching hash`() {
        val zipResource = javaClass.getResourceAsStream("/resourcepack/etherealchronicle-resourcepack.zip")
        val hashResource = javaClass.getResourceAsStream("/resourcepack/etherealchronicle-resourcepack.sha1")
        assertNotNull(zipResource)
        assertNotNull(hashResource)
        val artifact = ResourcePackArtifact.from(zipResource!!.use { it.readBytes() })
        assertEquals(artifact.sha1, hashResource!!.bufferedReader().use { it.readText().trim() })
        val temp = Files.createTempFile("ec-resource-pack", ".zip")
        try {
            Files.write(temp, artifact.bytes)
            ZipFile(temp.toFile()).use { zip ->
                assertNotNull(zip.getEntry("pack.mcmeta"))
                assertNotNull(zip.getEntry("pack.png"))
                assertNotNull(zip.getEntry("assets/ec/textures/entity/wolf/thorn_wolf.png"))
                assertNull(zip.getEntry("README.md"))
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    @Test
    fun `embedded pack endpoint only serves exact immutable artifact path`() {
        val port = ServerSocket(0).use { it.localPort }
        val artifact = ResourcePackArtifact.from("test-pack".toByteArray())
        val endpoint = ResourcePackEndpoint("127.0.0.1", "127.0.0.1", port, "embedded_http", "")
        val server = EmbeddedPackServer(endpoint, artifact, 2)
        try {
            val client = HttpClient.newHttpClient()
            val uri = endpoint.url(artifact.sha1)
            val get = client.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofByteArray())
            assertEquals(200, get.statusCode())
            assertArrayEquals(artifact.bytes, get.body())
            assertEquals(artifact.sha1, ResourcePackArtifact.from(get.body()).sha1)
            val head = client.send(HttpRequest.newBuilder(uri).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding())
            assertEquals(200, head.statusCode())
            val traversal = client.send(HttpRequest.newBuilder(URI(uri.toString() + "?path=../private")).GET().build(), HttpResponse.BodyHandlers.discarding())
            assertEquals(404, traversal.statusCode())
            val wrongPath = client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/pack/other.zip")).GET().build(), HttpResponse.BodyHandlers.discarding())
            assertEquals(404, wrongPath.statusCode())
            val post = client.send(HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding())
            assertEquals(404, post.statusCode())
        } finally {
            server.close()
        }
        assertThrows(Exception::class.java) {
            HttpClient.newHttpClient().send(HttpRequest.newBuilder(endpoint.url(artifact.sha1)).GET().build(), HttpResponse.BodyHandlers.discarding())
        }
    }

    @Test
    fun `endpoint rejects unsafe host mode sha and protocol values`() {
        assertThrows(IllegalArgumentException::class.java) { ResourcePackEndpoint("0.0.0.0", "host/path", 25580, "embedded_http", "") }
        assertThrows(IllegalArgumentException::class.java) { ResourcePackEndpoint("0.0.0.0", "example.com", 25580, "same_port_netty", "") }
        assertThrows(IllegalArgumentException::class.java) { ResourcePackEndpoint("0.0.0.0", "example.com", 25580, "external_url", "http://example.com/pack.zip") }
        assertThrows(IllegalArgumentException::class.java) { ResourcePackEndpoint("0.0.0.0", "example.com", 25580, "embedded_http", "").url("../secret") }
        assertThrows(IllegalArgumentException::class.java) { ResourcePackEndpoint("0.0.0.0", "example.com", 999, "embedded_http", "") }
    }

    @Test
    fun `custom item models fall back until pack load is confirmed`() {
        assertNull(UiThemeProvider.modelKey(false, "ec:ui_class_warrior"))
        assertEquals(org.bukkit.NamespacedKey.fromString("ec:ui_class_warrior"), UiThemeProvider.modelKey(true, "ec:ui_class_warrior"))
        assertNull(UiThemeProvider.modelKey(true, null))
    }

    @Test
    fun `bundled datapack contains all wolf variants and biome definitions`() {
        val loader = javaClass.classLoader
        val root = "datapacks/etherealchronicle_core/"
        val packPath = Files.createTempFile("ec-variant-pack", ".zip")
        Files.write(packPath, requireNotNull(loader.getResourceAsStream("resourcepack/etherealchronicle-resourcepack.zip")).use { it.readBytes() })
        assertNotNull(loader.getResourceAsStream(root + "pack.mcmeta")?.use { it.readBytes() })
        try {
            ZipFile(packPath.toFile()).use { pack ->
                listOf(
                    "ui_class_warrior", "ui_class_archer", "ui_class_mage", "ui_class_assassin", "ui_class_cleric", "ui_class_paladin",
                    "acc_alpha_fang_talisman", "acc_azure_core_signet", "acc_crucible_champion_crest", "acc_void_heart_ring",
                    "off_cryo_grimoire", "off_sanctified_chalice", "tool_rod_astral", "wpn_aether_chronicle_staff",
                    "wpn_astral_benediction_rod", "wpn_astral_piercer", "wpn_azure_sovereign_blade", "wpn_borealis_bow",
                    "wpn_cryo_eternity_staff", "wpn_eclipse_phantom_blade", "wpn_sovereign_aegis_hammer", "wpn_void_cleaver",
                ).forEach { model -> assertNotNull(pack.getEntry("assets/ec/items/$model.json"), "missing item model $model") }
                val variants = listOf("thorn_wolf", "frost_wolf", "ashwood_alpha")
                variants.forEach { variant ->
                    val path = "${root}data/ec/wolf_variant/$variant.json"
                    val json = requireNotNull(loader.getResourceAsStream(path)).bufferedReader().use { it.readText() }
                    assertTrue(json.contains("baby_assets"))
                    listOf("", "_tame", "_angry", "_baby", "_tame_baby", "_angry_baby").forEach { suffix ->
                        assertNotNull(pack.getEntry("assets/ec/textures/entity/wolf/$variant$suffix.png"))
                    }
                }
            }
        } finally {
            Files.deleteIfExists(packPath)
        }
        val biomes = listOf("lumina_sky", "ashwood_grove", "weeping_mine", "stargazer_hill", "void_sanctum", "frostpeak_expanse")
        biomes.forEach { biome ->
            val path = "${root}data/ec/worldgen/biome/$biome.json"
            val json = requireNotNull(loader.getResourceAsStream(path)).bufferedReader().use { it.readText() }
            assertTrue(json.contains("effects"))
            assertTrue(json.contains("\"carvers\": []"), "$biome must use the 26.3 carver-list schema")
        }
    }

    @Test
    fun `player preference migrates and round trips in schema twelve`() {
        val directory = Files.createTempDirectory("ec-pack-profile")
        val store = PlayerStore(directory)
        try {
            val schema11 = store.migrate(mapOf("schema_version" to 11, "resource_pack_preference" to "OFF"))
            assertEquals(12, schema11["schema_version"])
            assertEquals("OFF", schema11["resource_pack_preference"])
            val defaulted = store.migrate(mapOf("schema_version" to 11))
            assertEquals("AUTO", defaulted["resource_pack_preference"])
            val profile = PlayerProfile(UUID.randomUUID()).apply { resourcePackPreference = "ON" }
            store.save(profile).join()
            val loaded = store.load(profile.uuid).join()
            assertEquals("ON", loaded.resourcePackPreference)
            assertTrue(Files.readString(directory.resolve("${profile.uuid}.yml")).contains("schema_version: 12"))
        } finally {
            store.close()
            directory.toFile().deleteRecursively()
        }
    }
}
