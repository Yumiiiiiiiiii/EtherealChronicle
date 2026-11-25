package net.nakaymi.etherealchronicle.paper

import net.nakaymi.etherealchronicle.core.FixedSiteRegistry
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext

/** world.mode 未指定のテストへ、固定座標の拠点レジストリ（建設なし）を自動で差し込みます。 */
class TestWorldFixtureExtension : BeforeAllCallback {
    override fun beforeAll(context: ExtensionContext) {
        TestWorldFixture.registryFactory = { areas, dungeons -> FixedSiteRegistry(areas = areas, dungeons = dungeons) }
    }
}
