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
        assertContains(fishingReelAdvice(FishBehavior.TELEGRAPH),"予兆")
    }
    @Test fun `reeling timeout has a visible timeout title`() {
        assertEquals("§c時間切れ！" to "§7魚を逃してしまった…",fishingEscapeTitle(FishingEscapeCause.TIMEOUT))
    }
    @Test fun `default debug fishing selects an accessible fish for the local area`() {
        val world=ContentService(Path.of("src/main/resources/content")).load().world
        assertEquals("ec:fish_sunbeam_trout",defaultDebugFish(world.fishSpecies.values,world.fishingSpots.values,1,"ec_world")?.id)
    }
}
