package net.nakaymi.etherealchronicle.paper

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.joml.Vector3f

class GatheringDisplayGeometryTest {
    @Test fun `display transform centers the visible block over its clickable ground tile`() {
        assertEquals(Vector3f(-0.5f,0f,-0.5f),gatheringBlockDisplayTranslation())
    }
}
