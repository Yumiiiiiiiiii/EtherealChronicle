package net.nakaymi.etherealchronicle.qa

import net.nakaymi.etherealchronicle.paper.RpgPlugin
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import java.util.UUID
import kotlin.test.assertTrue

class LifeDebugCommandTest {
    private fun ServerMock.pump(timeoutMs:Long=4000,condition:()->Boolean):Boolean {
        val end=System.currentTimeMillis()+timeoutMs
        while(System.currentTimeMillis()<end) { scheduler.waitAsyncTasksFinished(); scheduler.performOneTick(); if(condition()) return true; Thread.sleep(20) }
        return condition()
    }

    @Test fun `admin console can inspect fishing and gathering runtime state`() {
        val server=MockBukkit.mock()
        try {
            val plugin=MockBukkit.load(RpgPlugin::class.java)
            assertTrue(server.pump { runCatching { plugin.fishing; plugin.gathering; true }.getOrDefault(false) },"controllers were not ready")
            val player=QaPlayerMock(server,"LifeDebug",UUID.nameUUIDFromBytes("life-debug".toByteArray()))
            server.addPlayer(player); assertTrue(server.pump { player.uniqueId in plugin.profiles },"profile was not loaded")
            val command=plugin.getCommand("ec")!!
            assertTrue(plugin.onCommand(server.consoleSender,command,"ec",arrayOf("debug","fish","status",player.name)))
            assertTrue(plugin.onCommand(server.consoleSender,command,"ec",arrayOf("debug","gather","status",player.name)))
        } finally { MockBukkit.unmock() }
    }
}
