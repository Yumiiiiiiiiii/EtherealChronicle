package net.nakaymi.etherealchronicle.qa

import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.PlayerMock
import java.util.UUID

/** PlayerMock whose saveData() is a no-op: MockBukkit leaves it unimplemented, production code calls it after inventory transactions. */
class QaPlayerMock(server: ServerMock, name: String, uuid: UUID = UUID.randomUUID()) : PlayerMock(server, name, uuid) {
    var saveDataCalls = 0
    override fun saveData() { saveDataCalls++ }
}

/** WorldMock whose getWorldFolder() works (MockBukkit leaves it unimplemented) so DemoWorld.isOwned can see an ownership marker. */
class OwnedWorldMock(worldName: String, private val folder: java.io.File, owned: Boolean = true) : org.mockbukkit.mockbukkit.world.WorldMock(org.bukkit.Material.GRASS_BLOCK, 4) {
    init {
        setName(worldName); folder.mkdirs()
        if (owned) java.io.File(folder, ".etherealchronicle-owned").writeText("qa")
    }
    override fun getWorldFolder(): java.io.File = folder
    companion object {
        fun add(server: ServerMock, name: String, owned: Boolean = true): OwnedWorldMock =
            OwnedWorldMock(name, java.nio.file.Files.createTempDirectory("qa-world-").toFile(), owned).also { server.addWorld(it) }
    }
}
