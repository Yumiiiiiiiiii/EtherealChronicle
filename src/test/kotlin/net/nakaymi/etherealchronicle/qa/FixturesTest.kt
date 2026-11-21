package net.nakaymi.etherealchronicle.qa

import java.nio.file.Files
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FixturesTest {
    @Test
    fun fakeClockOnlyMovesWhenAdvanced() {
        val c = FakeClock()
        val t0 = c.instant()
        assertEquals(t0, c.instant())
        c.advanceTicks(20)
        assertEquals(Duration.ofSeconds(1), Duration.between(t0, c.instant()))
        assertEquals(c.millis(), c.instant().toEpochMilli())
    }

    @Test
    fun contentYamlRendersScalarsListsAndNestedMaps() {
        val yaml = ContentYaml.file(
            1,
            "items" to listOf(
                ContentYaml.entry("ec:wooden_sword", "rarity" to "common", "stats" to mapOf("atk" to 3), "tags" to listOf("a", "b")),
                ContentYaml.entry("ec:bow", "name" to "弓 \"test\""),
            ),
            "empty" to emptyList<Any>(),
        )
        assertTrue(yaml.startsWith("schema_version: 1\n"))
        assertTrue("- id: \"ec:wooden_sword\"" in yaml, yaml)
        assertTrue("    stats:\n      atk: 3" in yaml, yaml)
        assertTrue("name: \"弓 \\\"test\\\"\"" in yaml, yaml)
        assertTrue("empty: []" in yaml, yaml)
    }

    @Test
    fun tempDirIsRemovedOnClose() {
        val p = TempDir().use { d -> d.write("a/b.yml", "x: 1"); assertTrue(Files.exists(d.resolve("a/b.yml"))); d.path }
        assertFalse(Files.exists(p))
    }

    @Test
    fun seededRandomIsRepeatable() {
        assertEquals(seededRandom().nextInt(), seededRandom().nextInt())
    }
}

