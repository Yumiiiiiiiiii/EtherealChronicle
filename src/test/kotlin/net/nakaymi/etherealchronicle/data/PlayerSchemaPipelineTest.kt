package net.nakaymi.etherealchronicle.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** 全ての旧保存世代を順次マイグレーションし、既存値を保持する契約を確認します。 */
class PlayerSchemaPipelineTest {
    @Test
    fun `every supported old schema migrates to current with defaults and user values intact`(@TempDir directory: Path) {
        PlayerStore(directory).use { store ->
            (PlayerSchema.INITIAL until PlayerSchema.CURRENT).forEach { version ->
                val migrated = store.migrate(mapOf("schema_version" to version, "currency" to 777))
                assertEquals(PlayerSchema.CURRENT, migrated["schema_version"], "schema $version")
                assertEquals(777, migrated["currency"], "schema $version")
                if (version < PlayerSchema.LIFE_SKILLS) assertTrue("life_skills" in migrated, "schema $version")
                if (version == PlayerSchema.V3) assertTrue("pending_rewards" in migrated, "schema $version")
                assertTrue("resource_pack_status" in migrated, "schema $version")
            }
        }
    }

    @Test
    fun `legacy tutorial completion survives the sequential migration`(@TempDir directory: Path) {
        PlayerStore(directory).use { store ->
            val migrated = store.migrate(mapOf("schema_version" to PlayerSchema.V1, "tutorial_quest" to "ec:tutorial_complete"))
            @Suppress("UNCHECKED_CAST")
            val quests = migrated.getValue("quests") as Map<String, Map<String, Any?>>
            assertEquals(1, quests.getValue("ec:tutorial").getValue("progress"))
            assertEquals(true, quests.getValue("ec:tutorial").getValue("completed"))
        }
    }
}
