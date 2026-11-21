package net.nakaymi.etherealchronicle.qa

import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.PlayerMock
import java.util.UUID

/** PlayerMock whose saveData() is a no-op: MockBukkit leaves it unimplemented, production code calls it after inventory transactions. */
class QaPlayerMock(server: ServerMock, name: String, uuid: UUID = UUID.randomUUID()) : PlayerMock(server, name, uuid) {
    var saveDataCalls = 0
    override fun saveData() { saveDataCalls++ }
}
