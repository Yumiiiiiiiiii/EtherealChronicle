package net.nakaymi.etherealchronicle.qa

import net.nakaymi.etherealchronicle.core.CastResult
import net.nakaymi.etherealchronicle.core.PlayerProfile
import net.nakaymi.etherealchronicle.paper.RpgPlugin
import org.bukkit.Material
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Phantom
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.PlayerMock
import java.util.UUID
import kotlin.test.*

class SupportSkillTest {
    private fun ServerMock.pump(timeout:Long=5000,ready:()->Boolean):Boolean {
        val until=System.currentTimeMillis()+timeout
        while(System.currentTimeMillis()<until) { scheduler.performOneTick(); if(ready()) return true; Thread.sleep(20) }
        return ready()
    }
    @Test
    fun `party support affects only nearby members and paladin taunt pins mob target`() {
        val server=MockBukkit.mock()
        try {
            val plugin=MockBukkit.load(RpgPlugin::class.java)
            assertTrue(server.pump { runCatching { plugin.snapshot.classes.all().isNotEmpty() }.getOrDefault(false) })
            val world=server.getWorld(net.nakaymi.etherealchronicle.core.WorldIds.DEMO) ?: server.addSimpleWorld(net.nakaymi.etherealchronicle.core.WorldIds.DEMO)
            val healer=PlayerMock(server,"Support",UUID.randomUUID()); val ally=PlayerMock(server,"Ally",UUID.randomUUID()); val outsider=PlayerMock(server,"Outsider",UUID.randomUUID())
            server.addPlayer(healer); server.addPlayer(ally); server.addPlayer(outsider)
            assertTrue(server.pump { healer.uniqueId in plugin.profiles && ally.uniqueId in plugin.profiles && outsider.uniqueId in plugin.profiles })
            healer.teleport(org.bukkit.Location(world,900.0,65.0,900.0)); ally.teleport(org.bukkit.Location(world,902.0,65.0,900.0)); outsider.teleport(org.bukkit.Location(world,930.0,65.0,900.0))
            val hp=PlayerProfile(healer.uniqueId); val ap=PlayerProfile(ally.uniqueId); val op=PlayerProfile(outsider.uniqueId)
            plugin.profiles[healer.uniqueId]=hp; plugin.profiles[ally.uniqueId]=ap; plugin.profiles[outsider.uniqueId]=op
            plugin.game.changeClass(hp,"ec:class_cleric"); hp.resource=130.0
            ap.classId="ec:class_paladin"; op.classId="ec:class_paladin"
            healer.inventory.setItemInMainHand(plugin.createItem("ec:item_wpn_novice_censer"))
            ally.getAttribute(Attribute.MAX_HEALTH)!!.baseValue=200.0; outsider.getAttribute(Attribute.MAX_HEALTH)!!.baseValue=200.0
            healer.health=70.0; ally.health=50.0; outsider.health=50.0
            plugin.adventure.parties.invite(healer.uniqueId,ally.uniqueId); plugin.adventure.parties.accept(ally.uniqueId)
            val allyDefense=plugin.effectiveStats(ally,ap).defense; val outsiderDefense=plugin.effectiveStats(outsider,op).defense
            hp.selectedSkill=1 // cleric_mend
            assertEquals(CastResult.SUCCESS,plugin.castSkill(healer,null))
            assertTrue(ally.health > 50.0,"party member was not healed")
            assertEquals(50.0,outsider.health,"non-party player was healed")
            assertTrue(plugin.effectiveStats(ally,ap).defense > allyDefense,"party defense buff missing")
            assertEquals(outsiderDefense,plugin.effectiveStats(outsider,op).defense,"buff escaped party radius")

            val tank=PlayerMock(server,"Tank",UUID.randomUUID()); server.addPlayer(tank)
            assertTrue(server.pump { tank.uniqueId in plugin.profiles })
            tank.teleport(org.bukkit.Location(world,900.0,65.0,900.0))
            val tp=PlayerProfile(tank.uniqueId); plugin.profiles[tank.uniqueId]=tp; plugin.game.changeClass(tp,"ec:class_paladin"); tp.resource=100.0
            tank.inventory.setItemInMainHand(plugin.createItem("ec:item_wpn_recruit_mace"))
            val mob=plugin.enemies.spawn("ec:enemy_aether_slime",org.bukkit.Location(world,903.0,65.0,900.0),"test:taunt",1,pinForSimulation=false)!!
            tp.selectedSkill=0
            assertEquals(CastResult.SUCCESS,plugin.castSkill(tank,null))
            assertEquals(tank.uniqueId,plugin.enemies.state(mob.uniqueId)!!.tauntTarget)
            assertTrue(plugin.enemies.state(mob.uniqueId)!!.tauntUntil > 0)
        } finally { MockBukkit.unmock() }
    }
}
