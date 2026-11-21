package net.nakaymi.etherealchronicle

import net.nakaymi.etherealchronicle.core.displayedHealth
import org.junit.jupiter.api.Test
import kotlin.test.*

class VirtualHealthTest {
    @Test fun `slime size attribute reset scales virtual health into live range`() {
        assertEquals(4.0*130/140,displayedHealth(130.0,140.0,4.0),0.000001)
        for(maximum in listOf(0.05,1.0,4.0,20.0,1024.0)) {
            assertTrue(displayedHealth(130.0,140.0,maximum) in 0.0..maximum)
            assertEquals(maximum,displayedHealth(140.0,140.0,maximum))
            assertEquals(0.0,displayedHealth(0.0,140.0,maximum))
        }
    }
    @Test fun `boss display remains bounded through damage phases and lethal hit`() {
        for(hp in listOf(55000.0,45000.0,20000.0,1.0,0.0)) assertTrue(displayedHealth(hp,55000.0,20.0) in 0.0..20.0)
        assertEquals(20.0,displayedHealth(100.0,50.0,20.0))
    }
}
