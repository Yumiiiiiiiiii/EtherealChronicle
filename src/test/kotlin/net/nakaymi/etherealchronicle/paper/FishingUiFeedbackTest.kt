package net.nakaymi.etherealchronicle.paper

import net.nakaymi.etherealchronicle.core.FishBehavior
import net.nakaymi.etherealchronicle.core.FishingEscapeCause
import net.nakaymi.etherealchronicle.data.ContentService
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FishingUiFeedbackTest {
    @Test fun `action bar advice maps each fishing behavior to a translation key`() {
        assertEquals("ec.fishing.dive",fishingReelAdvice(FishBehavior.DIVE))
        assertEquals("ec.fishing.thrash",fishingReelAdvice(FishBehavior.THRASH))
        assertEquals("ec.fishing.pull",fishingReelAdvice(FishBehavior.CALM))
        assertEquals("ec.fishing.low_tension",fishingReelAdvice(FishBehavior.CALM,10.0))
        assertEquals("ec.fishing.high_tension",fishingReelAdvice(FishBehavior.CALM,80.0))
        assertEquals("ec.fishing.telegraph",fishingReelAdvice(FishBehavior.TELEGRAPH))
    }
    @Test fun `reeling timeout has a visible timeout title`() {
        assertEquals("ec.fishing.title_timeout" to "ec.fishing.subtitle_timeout",fishingEscapeTitle(FishingEscapeCause.TIMEOUT))
        assertEquals("ec.fishing.title_timeout",fishingEscapeMessage(FishingEscapeCause.TIMEOUT))
    }
    @Test fun `every fishing escape cause has a title and a chat fallback`() {
        FishingEscapeCause.entries.forEach { cause ->
            val (title,subtitle)=fishingEscapeTitle(cause)
            assertContains(title,"ec.fishing.")
            assertContains(subtitle,"ec.fishing.")
            assertContains(fishingEscapeMessage(cause),"ec.fishing.")
        }
    }
    @Test fun `every fishing end reason has a structured diagnostic line`() {
        FishingEndReason.entries.forEach { reason ->
            val line=fishingEndLogLine("QA",reason,"ec:fish_sunbeam_trout",42,61)
            assertContains(line,"FISH QA: ended reason=${reason.name}")
            assertContains(line,"progress=42% tension=61%")
        }
    }
    @Test fun `pending bait persistence pauses rather than replays the bite`() {
        assertFalse(shouldAdvanceFishingSession(true))
        assertTrue(shouldAdvanceFishingSession(false))
    }
    @Test fun `default debug fishing selects an accessible fish for the local area`() {
        val world=ContentService(Path.of("src/main/resources/content")).load().world
        assertEquals("ec:fish_sunbeam_trout",defaultDebugFish(world.fishSpecies.values,world.fishingSpots.values,1,"ec_world")?.id)
    }
}
