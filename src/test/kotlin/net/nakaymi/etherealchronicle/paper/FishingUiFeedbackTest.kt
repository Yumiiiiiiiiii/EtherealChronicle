package net.nakaymi.etherealchronicle.paper

import net.nakaymi.etherealchronicle.core.FishBehavior
import net.nakaymi.etherealchronicle.core.FishingEscapeCause
import net.nakaymi.etherealchronicle.data.ContentService
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals

class FishingUiFeedbackTest {
    @Test fun `action bar only recommends bracing for a dive and gives calm advice otherwise`() {
        assertContains(fishingReelAdvice(FishBehavior.DIVE),"左クリック")
        assertContains(fishingReelAdvice(FishBehavior.THRASH),"手を離して")
        assertContains(fishingReelAdvice(FishBehavior.CALM),"右クリック")
        assertContains(fishingReelAdvice(FishBehavior.CALM,10.0),"張力が低い")
        assertContains(fishingReelAdvice(FishBehavior.CALM,80.0),"張力が高い")
        assertContains(fishingReelAdvice(FishBehavior.TELEGRAPH),"予兆")
    }
    @Test fun `reeling timeout has a visible timeout title`() {
        assertEquals("§c時間切れ！" to "§7魚を逃してしまった…",fishingEscapeTitle(FishingEscapeCause.TIMEOUT))
        assertContains(fishingEscapeMessage(FishingEscapeCause.TIMEOUT),"制限時間")
    }
    @Test fun `every fishing escape cause has a title and a chat fallback`() {
        FishingEscapeCause.entries.forEach { cause ->
            val (title,subtitle)=fishingEscapeTitle(cause)
            assertContains(title,"§")
            assertContains(subtitle,"§")
            assertContains(fishingEscapeMessage(cause),"。");
        }
    }
    @Test fun `every fishing end reason has a structured diagnostic line`() {
        FishingEndReason.entries.forEach { reason ->
            val line=fishingEndLogLine("QA",reason,"ec:fish_sunbeam_trout",42,61)
            assertContains(line,"FISH QA: 終了 reason=${reason.name}")
            assertContains(line,"progress=42% tension=61%")
        }
    }
    @Test fun `default debug fishing selects an accessible fish for the local area`() {
        val world=ContentService(Path.of("src/main/resources/content")).load().world
        assertEquals("ec:fish_sunbeam_trout",defaultDebugFish(world.fishSpecies.values,world.fishingSpots.values,1,"ec_world")?.id)
    }
}
