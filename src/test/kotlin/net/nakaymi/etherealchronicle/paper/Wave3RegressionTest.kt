package net.nakaymi.etherealchronicle.paper

import java.util.UUID
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Wave 3で修正したUI境界・診断文字列・ページ解決を純粋ロジックで回帰確認します。 */
class Wave3RegressionTest {
    @Test
    fun confirmationTrackingPreventsDuplicateAndCanBeReleased() {
        val tracker = ClassConfirmationTracker()
        val player = UUID.randomUUID()
        assertTrue(tracker.begin(player))
        assertTrue(tracker.isActive(player))
        assertFalse(tracker.begin(player))
        tracker.finish(player)
        assertFalse(tracker.isActive(player))
        assertTrue(tracker.begin(player))
    }

    @Test
    fun paperDialogRequiresAtLeastOneChoice() {
        assertFalse(paperDialogueHasChoices(0))
        assertTrue(paperDialogueHasChoices(1))
    }

    @Test
    fun inventoryGridRejectsNonChestSizes() {
        assertEquals(InventoryGrid(9, 1), InventoryGrid.forInventorySize(9))
        assertEquals(InventoryGrid(9, 6), InventoryGrid.forInventorySize(54))
        assertFails { InventoryGrid.forInventorySize(10) }
        assertFails { InventoryGrid.forInventorySize(63) }
    }

    @Test
    fun filteredShopEntriesArePackedWithoutChangingTheirIdentity() {
        data class Entry(val id: String, val visible: Boolean)
        val shown = compactVisibleShopEntries(
            listOf(Entry("first",true),Entry("locked",false),Entry("last",true))
        ) { it.visible }
        assertEquals(listOf("first","last"),shown.map { it.id })
    }

    @Test
    fun languageDiagnosticTreatsReplacementAsLiteralAndKeepsMiniMessageTags() {
        val catalog = LangCatalog(
            "en_us",
            mapOf("ec.error.test" to "<red>失敗 <reason></red>"),
            mapOf("ec.error.test" to "<red>Failed <reason></red>"),
            Logger.getLogger("Wave3RegressionTest"),
        )
        val detail = "ec.error.test: " + '$' + "path" + '\\' + "fragment <blue>text</blue>"
        val rendered = catalog.diagnostic("en_us", detail).toString()
        assertTrue(rendered.contains('$' + "path"))
        assertTrue(rendered.contains("\\fragment"))
        assertTrue(rendered.contains("<blue>text</blue>"))
        assertTrue(rendered.contains("<red>") && rendered.contains("</red>"))
    }

    @Test
    fun codexPageSlotResolvesAbsoluteAreaIndex() {
        assertEquals(45, CodexPagination.itemIndex(1, 0))
        assertEquals(89, CodexPagination.itemIndex(1, 44))
        assertNull(CodexPagination.itemIndex(1, 45))
        assertNull(CodexPagination.itemIndex(-1, 0))
    }

    @Test
    fun repeatedSlotRebuildsWhenDungeonPaletteChanges() {
        assertTrue(dungeonPaletteNeedsBuild(null, listOf("STONE", "GLASS", "GOLD_BLOCK")))
        assertFalse(dungeonPaletteNeedsBuild(listOf("STONE", "GLASS", "GOLD_BLOCK"), listOf("STONE", "GLASS", "GOLD_BLOCK")))
        assertTrue(dungeonPaletteNeedsBuild(listOf("STONE", "GLASS", "GOLD_BLOCK"), listOf("ICE", "PACKED_ICE", "BLUE_ICE")))
    }

    @Test
    fun npcsOnlySpawnIntoLoadedChunksAndInstanceEnemiesStayScoped() {
        assertFalse(npcChunkMaySpawn(false))
        assertTrue(npcChunkMaySpawn(true))
        val owner = UUID.randomUUID()
        val outsider = UUID.randomUUID()
        assertTrue(enemyAllowsPlayer(owner, emptySet()))
        assertTrue(enemyAllowsPlayer(owner, setOf(owner)))
        assertFalse(enemyAllowsPlayer(outsider, setOf(owner)))
    }

    @Test
    fun fullThemeTitleIncludesEightPixelLeftOffset() {
        assertEquals(-8, UiThemeProvider.TITLE_LEFT_OFFSET)
    }

    private fun assertFails(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected validation to fail")
        } catch (_: IllegalArgumentException) {
            // invalid size is rejected before InvUI receives the grid
        }
    }
}
