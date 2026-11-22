package net.nakaymi.etherealchronicle.paper

import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** GUI背景の画像寸法とフォント内コードポイントの対応を検証します。 */
class UiPackAssetsTest {
    @Test
    fun `background glyphs match font mappings and chest dimensions`() {
        val root=File("resourcepack/assets/ec")
        val uiFont=loadJsonAsYaml(File(root,"font/ui.json"))
        val providers=(uiFont["providers"] as List<*>).filterIsInstance<Map<*,*>>()
        val bitmapProviders=providers.filter { it["type"]=="bitmap" }
        val glyphs=bitmapProviders.flatMap { provider ->
            (provider["chars"] as List<*>).filterIsInstance<String>().flatMap { row -> row.codePoints().toArray().toList() }
        }.toSet()
        val expected=mapOf(
            "class" to 0xE100,
            "status" to 0xE101,
            "codex-items" to 0xE102,
            "enhance" to 0xE103,
            "shop" to 0xE104,
            "generic" to 0xE105,
        )

        assertEquals(expected.values.toSet(),glyphs)
        expected.forEach { (kind,codepoint) -> assertEquals(codepoint,UiThemeProvider.backgroundCodepoint(kind)) }

        val expectedSizes=mapOf(
            "bg_class_select.png" to (176 to 132),
            "bg_status.png" to (176 to 222),
            "bg_codex.png" to (176 to 222),
            "bg_upgrade.png" to (176 to 222),
            "bg_shop.png" to (176 to 222),
            "bg_generic.png" to (176 to 222),
        )
        expectedSizes.forEach { (name,size) ->
            val image=ImageIO.read(File(root,"textures/gui/$name"))
            assertEquals(size, image.width to image.height, name)
            var minX=image.width
            var maxX=-1
            var minY=image.height
            var maxY=-1
            for (y in 0 until image.height) for (x in 0 until image.width) {
                if (image.getRGB(x,y) ushr 24 == 0) continue
                minX=minOf(minX,x)
                maxX=maxOf(maxX,x)
                minY=minOf(minY,y)
                maxY=maxOf(maxY,y)
            }
            assertEquals(0 to (image.width-1),minX to maxX,"$name opaque horizontal extent")
            assertEquals(0 to (image.height-1),minY to maxY,"$name opaque vertical extent")
            val provider=bitmapProviders.single { (it["file"] as? String)=="ec:gui/$name" }
            assertEquals(size.second,provider["height"],name)
        }
    }

    @Test
    fun `space font uses documented positive and negative advances`() {
        val spaceFont=loadJsonAsYaml(File("resourcepack/assets/ec/font/space.json"))
        val provider=(spaceFont["providers"] as List<*>).filterIsInstance<Map<*,*>>().single()
        val advances=(provider["advances"] as Map<*,*>).entries.associate { it.key.toString().codePointAt(0) to (it.value as Number).toInt() }

        assertEquals(9,advances.values.filter { it<0 }.toSet().size)
        assertTrue((0xE001..0xE009).all { it in advances })
        assertTrue((0xE011..0xE018).all { it in advances })
        assertEquals(listOf(-1,-2,-4,-8,-16,-32,-64,-128,-256), (0xE001..0xE009).map { advances.getValue(it) })
        assertEquals(listOf(1,2,4,8,16,32,64,128), (0xE011..0xE018).map { advances.getValue(it) })
    }

    private fun loadJsonAsYaml(file: File): Map<*,*> {
        assertTrue(file.isFile,"Missing resource-pack font: ${file.path}")
        return Yaml().load<Any>(file.readText()) as Map<*,*>
    }
}
