package net.nakaymi.etherealchronicle.paper

import net.kyori.adventure.text.Component
import net.nakaymi.etherealchronicle.core.TreasureChestDefinition
import net.nakaymi.etherealchronicle.core.claimTreasure
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Interaction
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.persistence.PersistentDataType

/** 宣言座標に宝箱表示を置き、once flagと報酬をAdventureControllerの取引mailboxで確定します。 */
class TreasureController(private val plugin:RpgPlugin):Listener {
    private val key=NamespacedKey(plugin,"treasure_id")
    private val entities=mutableMapOf<String,MutableList<java.util.UUID>>()
    private val spawning=mutableSetOf<String>()
    init { plugin.server.pluginManager.registerEvents(this,plugin) }

    /** 定義済み宝箱表示だけを再配置します。 */
    fun reload() {
        entities.values.flatten().forEach { Bukkit.getEntity(it)?.remove() }; entities.clear()
        plugin.snapshot.world.treasureChests.values.forEach(::spawn)
    }

    private fun spawn(chest:TreasureChestDefinition) {
        if(!spawning.add(chest.id)) return
        try {
        val world=Bukkit.getWorld(chest.position.world) ?: return
        if(world.name!=DemoWorld.NAME || !DemoWorld.isOwned(world)) return
        val loc=Location(world,chest.position.x,chest.position.y,chest.position.z)
        // 対象の設定済みchunkだけ調べ、ワールド全体のentity走査を避けます。
        world.getChunkAt(loc).entities.filter { it.persistentDataContainer.get(key,PersistentDataType.STRING)==chest.id }.forEach { it.remove() }
        val display=world.spawn(loc,BlockDisplay::class.java) { it.block=Material.CHEST.createBlockData(); it.isPersistent=false; it.isInvulnerable=true; it.persistentDataContainer.set(key,PersistentDataType.STRING,chest.id) }
        val hitbox=world.spawn(loc.clone().add(0.5,0.0,0.5),Interaction::class.java) { it.interactionWidth=0.9f; it.interactionHeight=0.9f; it.isResponsive=true; it.isPersistent=false; it.isInvulnerable=true; it.persistentDataContainer.set(key,PersistentDataType.STRING,chest.id) }
        entities[chest.id]=mutableListOf(display.uniqueId,hitbox.uniqueId)
        world.spawnParticle(runCatching { Particle.valueOf(chest.hintParticle) }.getOrDefault(Particle.END_ROD),loc.clone().add(0.5,1.1,0.5),3,0.15,0.2,0.15,0.01)
        } finally { spawning.remove(chest.id) }
    }

    @EventHandler
    fun chunkLoad(event:org.bukkit.event.world.ChunkLoadEvent) {
        if(event.world.name!=DemoWorld.NAME) return
        plugin.snapshot.world.treasureChests.values.filter { chest ->
            val x=chest.position.x.toInt() shr 4; val z=chest.position.z.toInt() shr 4
            x==event.chunk.x && z==event.chunk.z
        }.forEach { chest ->
            if(entities[chest.id]?.any { Bukkit.getEntity(it)?.isValid==true } != true) spawn(chest)
        }
    }

    @EventHandler(ignoreCancelled=true)
    fun interact(event:PlayerInteractEntityEvent) {
        if(event.hand!=org.bukkit.inventory.EquipmentSlot.HAND) return
        val id=event.rightClicked.persistentDataContainer.get(key,PersistentDataType.STRING) ?: return
        event.isCancelled=true
        val player=event.player
        val profile=plugin.profiles[player.uniqueId] ?: return
        val chest=plugin.snapshot.world.treasureChests[id] ?: return
        if(chest.onceFlag in profile.flags) { player.sendMessage(plugin.message(player,"ec.treasure.empty")); return }
        if(plugin.adventure.locked(profile.uuid)) { player.sendMessage(plugin.message(player,"ec.treasure.busy")); return }
        try {
            plugin.adventure.transaction(player,{ _ ->
                require(claimTreasure(profile,chest,plugin.snapshot.balance.currencyGainCap)) { "ec.treasure.empty" }
            }) {
                player.sendMessage(plugin.message(player,"ec.treasure.opened", "currency" to chest.currency))
            }
        } catch(error:Exception) {
            val key=error.message?.takeIf { it=="ec.treasure.empty" } ?: "ec.treasure.failed"
            player.sendMessage(plugin.message(player,key))
        }
    }

    fun close() { entities.values.flatten().forEach { Bukkit.getEntity(it)?.remove() }; entities.clear() }
}
