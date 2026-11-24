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
import java.net.Socket
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipFile
import org.yaml.snakeyaml.Yaml

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
                assertNotNull(zip.getEntry("assets/ec/textures/item/transparent_slot.png"))
                assertNotNull(zip.getEntry("assets/ec/lang/en_us.json"))
                assertNotNull(zip.getEntry("assets/ec/lang/ja_jp.json"))
                val transparent = zip.getInputStream(zip.getEntry("assets/ec/textures/item/transparent_slot.png")).use { it.readBytes() }
                assertTrue(transparent.size > 32)
                assertEquals(16, java.nio.ByteBuffer.wrap(transparent, 16, 4).int)
                assertEquals(16, java.nio.ByteBuffer.wrap(transparent, 20, 4).int)
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
    fun `partial header flood is timed out and does not block a valid download`() {
        val port = ServerSocket(0).use { it.localPort }
        val artifact = ResourcePackArtifact.from("flood-test-pack".toByteArray())
        val endpoint = ResourcePackEndpoint("127.0.0.1", "127.0.0.1", port, "embedded_http", "")
        val server = EmbeddedPackServer(endpoint, artifact, maxConcurrent = 32, maxPerIp = 4,
            idleReadTimeoutMillis = 250, headerDeadlineMillis = 700)
        val stalledClients = mutableListOf<Socket>()
        try {
            repeat(100) {
                runCatching {
                    Socket().apply {
                        connect(InetSocketAddress("127.0.0.1", port), 500)
                        getOutputStream().write("GET /pack/${artifact.sha1}.zip HTTP/1.1\r\nHost:".toByteArray())
                        stalledClients += this
                    }
                }
            }
            Thread.sleep(400)
            val response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(endpoint.url(artifact.sha1)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray(),
            )
            assertEquals(200, response.statusCode())
            assertArrayEquals(artifact.bytes, response.body())
        } finally {
            stalledClients.forEach { runCatching { it.close() } }
            server.close()
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
    fun `game port endpoint uses the minecraft port and fixed sha path`() {
        val endpoint = ResourcePackEndpoint("0.0.0.0", "play.example.net", 25565, "game_port", "")

        assertEquals("http://play.example.net:25565/pack/${"a".repeat(40)}.zip", endpoint.url("a".repeat(40)).toString())
        assertEquals(false, org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
            javaClass.classLoader.getResourceAsStream("config.yml")!!.bufferedReader(),
        ).getBoolean("resource_pack.same_port_netty.enabled"))
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
            assertEquals(13, schema11["schema_version"])
            assertEquals("OFF", schema11["resource_pack_preference"])
            val defaulted = store.migrate(mapOf("schema_version" to 11))
            assertEquals("AUTO", defaulted["resource_pack_preference"])
            val profile = PlayerProfile(UUID.randomUUID()).apply { resourcePackPreference = "ON" }
            store.save(profile).join()
            val loaded = store.load(profile.uuid).join()
            assertEquals("ON", loaded.resourcePackPreference)
            assertTrue(Files.readString(directory.resolve("${profile.uuid}.yml")).contains("schema_version: 13"))
        } finally {
            store.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `resource pack items models fonts and textures have no missing non-atlas or orphaned assets`() {
        val loader = javaClass.classLoader
        val zipBytes = requireNotNull(loader.getResourceAsStream("resourcepack/etherealchronicle-resourcepack.zip"))
            .use { it.readBytes() }
        val temp = Files.createTempFile("ec-resource-pack-integrity", ".zip")
        try {
            Files.write(temp, zipBytes)
            ZipFile(temp.toFile()).use { zip ->
                val entries = zip.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toSet()
                val yaml = Yaml()
                val usedTextures = mutableSetOf<String>()

                fun assertValidPng(path: String, owner: String) {
                    assertTrue(path in entries, "Missing PNG '$path' referenced by $owner")
                    val bytes = zip.getInputStream(zip.getEntry(path)).use { it.readBytes() }
                    assertTrue(bytes.size >= 24, "PNG '$path' is truncated (${bytes.size} bytes)")
                    val magic = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
                    assertArrayEquals(magic, bytes.copyOfRange(0, 8), "Invalid PNG header in '$path'")
                    val width = java.nio.ByteBuffer.wrap(bytes, 16, 4).int
                    val height = java.nio.ByteBuffer.wrap(bytes, 20, 4).int
                    assertTrue(width in 1..2048 && height in 1..2048, "Invalid PNG dimensions ${width}x${height} in '$path'")
                }

                // 1. Validate every item definition -> model JSON
                val itemDefs = entries.filter { it.startsWith("assets/ec/items/") && it.endsWith(".json") }
                assertTrue(itemDefs.isNotEmpty(), "Expected item definitions in resource pack")
                itemDefs.forEach { itemPath ->
                    @Suppress("UNCHECKED_CAST")
                    val doc = yaml.load<Map<String, Any?>>(zip.getInputStream(zip.getEntry(itemPath)).bufferedReader())
                    @Suppress("UNCHECKED_CAST")
                    val modelObj = doc["model"] as? Map<String, Any?>
                    val modelRef = modelObj?.get("model") as? String
                    assertNotNull(modelRef, "Missing model.model in $itemPath")
                    if (modelRef!!.startsWith("ec:")) {
                        val target = "assets/ec/models/${modelRef.removePrefix("ec:")}.json"
                        assertTrue(target in entries, "Missing model '$target' referenced by $itemPath")
                    }
                }

                // 2. Validate every model JSON -> parent & default atlas textures (item/ or block/)
                val models = entries.filter { it.startsWith("assets/ec/models/") && it.endsWith(".json") }
                assertTrue(models.isNotEmpty(), "Expected models in resource pack")
                models.forEach { modelPath ->
                    @Suppress("UNCHECKED_CAST")
                    val doc = yaml.load<Map<String, Any?>>(zip.getInputStream(zip.getEntry(modelPath)).bufferedReader())
                    val parent = doc["parent"] as? String
                    if (parent != null && parent.startsWith("ec:")) {
                        val target = "assets/ec/models/${parent.removePrefix("ec:")}.json"
                        assertTrue(target in entries, "Missing parent model '$target' referenced by $modelPath")
                    }
                    @Suppress("UNCHECKED_CAST")
                    val textures = doc["textures"] as? Map<String, Any?> ?: emptyMap()
                    textures.forEach { (layer, refObj) ->
                        val ref = refObj as? String ?: return@forEach
                        if (ref.startsWith("ec:")) {
                            val rel = ref.removePrefix("ec:")
                            assertTrue(
                                rel.startsWith("item/") || rel.startsWith("block/"),
                                "Model '$modelPath' layer '$layer' references non-atlas texture '$ref' (must be under item/ or block/)",
                            )
                            val pngPath = "assets/ec/textures/$rel.png"
                            assertValidPng(pngPath, modelPath)
                            usedTextures += pngPath
                        }
                    }
                }

                // 3. Validate every font JSON bitmap provider texture
                val fonts = entries.filter { it.startsWith("assets/ec/font/") && it.endsWith(".json") }
                assertTrue(fonts.isNotEmpty(), "Expected font definitions in resource pack")
                fonts.forEach { fontPath ->
                    @Suppress("UNCHECKED_CAST")
                    val doc = yaml.load<Map<String, Any?>>(zip.getInputStream(zip.getEntry(fontPath)).bufferedReader())
                    @Suppress("UNCHECKED_CAST")
                    val providers = doc["providers"] as? List<Map<String, Any?>> ?: emptyList()
                    providers.forEach { provider ->
                        if (provider["type"] == "bitmap") {
                            val fileRef = provider["file"] as? String
                            assertNotNull(fileRef, "Missing bitmap file in $fontPath")
                            if (fileRef!!.startsWith("ec:")) {
                                val pngPath = "assets/ec/textures/${fileRef.removePrefix("ec:")}"
                                assertValidPng(pngPath, fontPath)
                                usedTextures += pngPath
                            }
                        }
                    }
                }

                // 4. Validate bundled datapack wolf variant textures and detect orphaned PNGs
                val wolfVariants = listOf("thorn_wolf", "frost_wolf", "ashwood_alpha")
                val wolfRegex = Regex("ec:entity/wolf/[a-z0-9_]+")
                wolfVariants.forEach { variant ->
                    val json = requireNotNull(loader.getResourceAsStream("datapacks/etherealchronicle_core/data/ec/wolf_variant/$variant.json"))
                        .bufferedReader().use { it.readText() }
                    wolfRegex.findAll(json).forEach { match ->
                        val pngPath = "assets/ec/textures/${match.value.removePrefix("ec:")}.png"
                        assertValidPng(pngPath, "wolf_variant/$variant.json")
                        usedTextures += pngPath
                    }
                }
                val allPngs = entries.filter { it.startsWith("assets/ec/textures/") && it.endsWith(".png") }.toSet()
                val orphaned = (allPngs - usedTextures).sorted()
                assertTrue(orphaned.isEmpty(), "Orphaned textures in resource pack: $orphaned")

                // 5. Validate every item_model referenced by bundled content YAML exists in assets/ec/items/
                val contentIndex = requireNotNull(loader.getResourceAsStream("content-index.txt"))
                    .bufferedReader().use { it.readLines().filter(String::isNotBlank) }
                val itemModelRegex = Regex("""item_model:\s*['"]?(ec:[a-z0-9_]+)['"]?""")
                contentIndex.forEach { contentPath ->
                    val clean = contentPath.removePrefix("﻿")
                    val text = requireNotNull(loader.getResourceAsStream(clean)).bufferedReader().use { it.readText() }
                    itemModelRegex.findAll(text).forEach { match ->
                        val modelId = match.groupValues[1].removePrefix("ec:")
                        val itemDef = "assets/ec/items/$modelId.json"
                        assertTrue(itemDef in entries, "Missing resource pack item definition '$itemDef' for content '$clean'")
                    }
                }
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }
}
