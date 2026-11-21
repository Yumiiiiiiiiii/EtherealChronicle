package net.nakaymi.etherealchronicle.paper

import net.kyori.adventure.text.Component
import net.nakaymi.etherealchronicle.core.*
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Interaction
import org.bukkit.entity.TextDisplay
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.persistence.PersistentDataType
import org.joml.Vector3f
import java.util.UUID
import kotlin.math.floor

internal fun gatheringBlockDisplayTranslation()=Vector3f(-0.5f,0f,-0.5f)

/** Bounded, per-player harvesting channels. Vanilla terrain and drops remain untouched. */
class GatheringController(private val plugin: RpgPlugin): Listener {
    private data class Site(val node: GatheringNodeDefinition,val location: GatheringLocation)
    private data class Channel(val site: Site,val started: Long,val duration: Long,val origin: Location,val bar: BossBar)
    private val engine=GatheringEngine()
    private val sites=plugin.snapshot.world.gatheringNodes.values.flatMap { node -> node.locations.map { Site(node,it) } }
        // Players may click either the ground tile or the visual's block coordinate.
        // Both resolve through this bounded coordinate index (two keys per configured node).
        .flatMap { site -> listOf(
            key(site.location.world,site.location.x,site.location.y-1,site.location.z) to site,
            key(site.location.world,site.location.x,site.location.y,site.location.z) to site
        ) }.toMap()
    private val sitesById=sites.values.distinctBy(::siteKey).associateBy(::siteKey)
    private val channels=mutableMapOf<UUID,Channel>()
    private val lastAttempt=mutableMapOf<UUID,String>()
    private val displays=mutableMapOf<String,List<UUID>>()
    private val displayKey=NamespacedKey(plugin,"gather_node")
    private val hitboxKey=NamespacedKey(plugin,"gather_node_hitbox")
    private val limit=100
    private var task: org.bukkit.scheduler.BukkitTask?=null

    init {
        plugin.server.pluginManager.registerEvents(this,plugin)
        task=plugin.server.scheduler.runTaskTimer(plugin,Runnable { tick() },2,2)
        renderAll()
    }

    fun active(player: UUID)=player in channels
    fun debugStatus(player:UUID):String=channels[player]?.let { c ->
        val progress=((System.currentTimeMillis()-c.started).toDouble()/c.duration).coerceIn(0.0,1.0)
        "採取中 node=${c.site.node.id}/${c.site.location.id} progress=${(progress*100).toInt()}% BossBar=${c.bar.players.any { it.uniqueId==player }} visible=${c.bar.isVisible} duration=${c.duration}ms"
    } ?: "採取チャンネルなし last=${lastAttempt[player] ?: "まだ採取操作がありません"}"
    fun debugStatus(player:org.bukkit.entity.Player):String {
        val active=debugStatus(player.uniqueId)
        val profile=plugin.profiles[player.uniqueId]
        val held=plugin.identify(player.inventory.itemInMainHand) ?: "vanilla:${player.inventory.itemInMainHand.type}"
        val tackle=plugin.snapshot.world.tackle.values.firstOrNull { it.item==held }
        val nearest=sites.values.distinctBy(::siteKey).filter { it.location.world==player.world.name }.minByOrNull { site ->
            player.location.distanceSquared(Location(player.world,site.location.x+0.5,site.location.y.toDouble(),site.location.z+0.5))
        }
        val nearestText=nearest?.let { site ->
            val distance=kotlin.math.sqrt(player.location.distanceSquared(Location(player.world,site.location.x+0.5,site.location.y.toDouble(),site.location.z+0.5)))
            "${site.node.id}/${site.location.id} distance=${"%.2f".format(distance)} expected=${site.node.toolKind}/T${site.node.minToolTier}/採取Lv${site.node.minGatheringLevel} cooldown=${profile?.gatheringReadyAt?.get(siteKey(site))?.let { maxOf(0,it-System.currentTimeMillis()) } ?: 0}ms"
        } ?: "なし"
        val owned=runCatching { player.world.name==DemoWorld.NAME && DemoWorld.isOwned(player.world) }.getOrDefault(false)
        return "$active world=${player.world.name} owned=$owned pos=${player.location.blockX},${player.location.blockY},${player.location.blockZ} profile=${profile!=null} gatheringLv=${profile?.lifeSkills?.get(LifeSkillKind.GATHERING)?.level ?: "?"} held=$held tackle=${tackle?.kind ?: "なし"}/T${tackle?.tier ?: 0} nearest=$nearestText lock=${profile?.let { plugin.adventure.locked(it.uuid) } ?: false}"
    }
    fun interrupt(player: UUID) { Bukkit.getPlayer(player)?.let { cancel(it,"戦闘により採取を中断しました。") } }
    fun close() {
        task?.cancel(); task=null
        channels.values.forEach { it.bar.removeAll() }
        channels.clear()
        displays.values.flatten().forEach { Bukkit.getEntity(it)?.remove() }
        displays.clear()
    }
    fun reload() { close(); renderAll(); task=plugin.server.scheduler.runTaskTimer(plugin,Runnable { tick() },2,2) }

    private fun key(world:String,x:Int,y:Int,z:Int)="$world:$x,$y,$z"
    private fun siteKey(site: Site)="${site.node.id}/${site.location.id}"
    private fun location(site: Site)=Bukkit.getWorld(site.location.world)?.let { Location(it,site.location.x+0.5,site.location.y.toDouble(),site.location.z+0.5) }

    private fun renderAll() {
        // A single unavailable or failing world/display must not abort plugin startup or
        // prevent player profiles and the rest of the adventure systems from loading.
        sites.values.forEach { site ->
            runCatching { renderSite(site) }.onFailure { error ->
                plugin.logger.warning("採取ノード表示を配置できません (${site.node.id}/${site.location.id}): ${error.message}")
            }
        }
    }
    private fun renderSite(site:Site) {
        val at=location(site) ?: return
        val world=at.world
        if(world.name!=DemoWorld.NAME || !DemoWorld.isOwned(world)) return
        val k=siteKey(site)
        if(displays[k]?.all { Bukkit.getEntity(it)?.isValid==true }==true) return
        displays.remove(k)?.forEach { Bukkit.getEntity(it)?.remove() }
        val block=world.getBlockAt(site.location.x,site.location.y,site.location.z)
        val display=world.spawn(at,BlockDisplay::class.java) { entity ->
            entity.block=Material.valueOf(site.node.displayMaterial).createBlockData()
            entity.transformation=org.bukkit.util.Transformation(gatheringBlockDisplayTranslation(),org.joml.Quaternionf(),Vector3f(1f,1f,1f),org.joml.Quaternionf())
            entity.isPersistent=false; entity.isInvulnerable=true
            entity.persistentDataContainer.set(displayKey,PersistentDataType.STRING,k)
        }
        val label=world.spawn(at.clone().add(0.0,1.15,0.0),TextDisplay::class.java) { entity ->
            entity.text(Component.text("§a[採取] §f${site.node.name} §7(Lv.${site.node.minGatheringLevel})"))
            entity.isPersistent=false; entity.isInvulnerable=true
            entity.persistentDataContainer.set(displayKey,PersistentDataType.STRING,k)
        }
        val hitbox=world.spawn(at,Interaction::class.java) { entity ->
            entity.interactionWidth=1.1f; entity.interactionHeight=1.1f
            entity.isPersistent=false; entity.isInvulnerable=true
            entity.persistentDataContainer.set(hitboxKey,PersistentDataType.STRING,k)
        }
        displays[k]=listOf(display.uniqueId,label.uniqueId,hitbox.uniqueId)
        if(!block.type.isAir && block.type!=Material.WATER) plugin.logger.fine("採取ノード表示位置が空気ではありません: $k")
    }

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    fun breakBlock(e: BlockBreakEvent) {
        val pos=e.block.location
        if(isProtected(pos)) { e.isCancelled=true; e.player.sendMessage(Component.text("採取地点の地形は変更できません。道具で採取してください。")) }
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    fun place(e: BlockPlaceEvent) { if(isProtected(e.block.location)) e.isCancelled=true }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    fun explode(e: EntityExplodeEvent) { e.blockList().removeIf { isProtected(it.location) } }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    fun pistonExtend(e: BlockPistonExtendEvent) { if(e.blocks.any { isProtected(it.location) }) e.isCancelled=true }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    fun pistonRetract(e: BlockPistonRetractEvent) { if(e.blocks.any { isProtected(it.location) }) e.isCancelled=true }
    @EventHandler fun chunk(e: ChunkLoadEvent) {
        e.chunk.entities.filter { it.persistentDataContainer.has(displayKey,PersistentDataType.STRING) }.forEach { entity ->
            if(displays.values.flatten().none { it == entity.uniqueId }) entity.remove()
        }
        sites.values.filter { it.location.world==e.world.name && it.location.x shr 4==e.chunk.x && it.location.z shr 4==e.chunk.z }.forEach(::renderSite)
    }
    private fun isProtected(location: Location): Boolean {
        if(location.world.name != "ec_world") return false
        val x=location.blockX; val y=location.blockY; val z=location.blockZ
        return sites.values.any { s -> s.location.world==location.world.name && kotlin.math.abs(s.location.x-x)<=1 && kotlin.math.abs(s.location.y-y)<=1 && kotlin.math.abs(s.location.z-z)<=1 }
    }

    @EventHandler(priority=EventPriority.HIGHEST)
    fun interact(e: PlayerInteractEvent) {
        if(e.hand != EquipmentSlot.HAND || e.action != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return
        val block=e.clickedBlock ?: return
        val site=sites[key(block.world.name,block.x,block.y,block.z)]
        if(site==null) {
            val nearby=sites.values.distinctBy(::siteKey).filter { it.location.world==block.world.name }.minByOrNull { (it.location.x-block.x)*(it.location.x-block.x)+(it.location.z-block.z)*(it.location.z-block.z) }
            if(nearby!=null && kotlin.math.abs(nearby.location.y-block.y)<=2 && (nearby.location.x-block.x)*(nearby.location.x-block.x)+(nearby.location.z-block.z)*(nearby.location.z-block.z)<=4) {
                e.isCancelled=true
                reject(e.player,"採取地点 ${nearby.node.name} の表示または足元を右クリックしてください。")
            }
            return
        }
        e.isCancelled=true
        begin(e.player,site)
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    fun interactDisplay(e: PlayerInteractEntityEvent) {
        val k=e.rightClicked.persistentDataContainer.get(hitboxKey,PersistentDataType.STRING) ?: return
        val site=sitesById[k] ?: run { reject(e.player,"この採取地点の設定を読み込めませんでした。管理者にお知らせください。"); return }
        e.isCancelled=true
        begin(e.player,site)
    }
    private fun begin(player: org.bukkit.entity.Player,site: Site) {
        if(player.world.name!=DemoWorld.NAME || !DemoWorld.isOwned(player.world)) { reject(player,"管理対象の採取エリアでのみ採取できます。"); return }
        val profile=plugin.profiles[player.uniqueId] ?: run { reject(player,"プレイヤーデータを読み込み中です。少し待ってからお試しください。"); return }
        if(plugin.adventure.locked(profile.uuid)) { reject(player,"取引処理中は採取できません。"); return }
        if(channels.containsKey(player.uniqueId)) { reject(player,"採取中です。完了までお待ちください。"); return }
        if(channels.size>=limit) { reject(player,"採取が混み合っています。少し待ってください。"); return }
        val def=site.node
        val toolId=plugin.identify(player.inventory.itemInMainHand)
        val tool=toolId?.let { id -> plugin.snapshot.world.tackle.values.find { it.item==id && it.kind in setOf(TackleKind.PICKAXE,TackleKind.SICKLE,TackleKind.HATCHET,TackleKind.EXTRACTOR) } }
        val lifeLevel=profile.lifeSkills[LifeSkillKind.GATHERING]?.level ?: 1
        if(tool==null) { reject(player,"${def.name}の採取には${def.toolKind}専用道具が必要です。"); return }
        if(tool.kind!=def.toolKind) { reject(player,"${def.name}には${def.toolKind}が必要です（現在:${tool.kind}）。"); return }
        if(tool.tier<def.minToolTier) { reject(player,"道具の段階が足りません。T${def.minToolTier}以上が必要です。"); return }
        if(lifeLevel<def.minGatheringLevel) { reject(player,"採取レベルが足りません。採取Lv.${def.minGatheringLevel}以上が必要です。"); return }
        if(lifeLevel<tool.minLifeLevel) { reject(player,"この道具を使うには生活スキルLv.${tool.minLifeLevel}以上が必要です。"); return }
        if(!engine.canHarvest(def,tool.kind,tool.tier,lifeLevel)) {
            reject(player,"この採取地点の条件を満たしていません。"); return
        }
        val now=System.currentTimeMillis(); val k=siteKey(site)
        if(!engine.readyAt(profile,k,now)) { reject(player,"この採取地点は再生中です。あと ${(profile.gatheringReadyAt[k]!!-now+999)/1000}秒お待ちください。"); return }
        val origin=player.location.clone(); val duration=(def.channelMillis*(1-tool.channelSpeedBonus)).toLong().coerceAtLeast(250)
        val bar=Bukkit.createBossBar("採取中: ${def.name}",BarColor.GREEN,BarStyle.SOLID).apply { addPlayer(player); progress=0.08 }
        channels[player.uniqueId]=Channel(site,now,duration,origin,bar)
        lastAttempt[player.uniqueId]="開始成功 node=${site.node.id}/${site.location.id}"
        player.sendActionBar(Component.text("§a採取中… 動かずにお待ちください"))
        player.sendMessage(Component.text("${def.name}の採取を開始しました。完了まで動かずにお待ちください。"))
    }

    @EventHandler fun moved(e: PlayerMoveEvent) {
        val channel=channels[e.player.uniqueId] ?: return
        val to=e.to
        if(e.from.world != to.world || e.from.distanceSquared(to) > 0.09) cancel(e.player,"移動したため採取を中断しました。")
    }
    @EventHandler fun quit(e: PlayerQuitEvent) { cancel(e.player,null); lastAttempt.remove(e.player.uniqueId) }
    @EventHandler fun death(e: PlayerDeathEvent) { cancel(e.entity,"倒れたため採取を中断しました。") }
    @EventHandler(ignoreCancelled=true) fun damage(e: EntityDamageEvent) { (e.entity as? org.bukkit.entity.Player)?.let { interrupt(it.uniqueId) } }
    @EventHandler fun held(e: PlayerItemHeldEvent) { if(active(e.player.uniqueId)) cancel(e.player,"道具を持ち替えたため採取を中断しました。") }
    private fun cancel(player: org.bukkit.entity.Player,message:String?):Boolean {
        val channel=channels.remove(player.uniqueId) ?: return false
        channel.bar.removeAll()
        lastAttempt[player.uniqueId]=message ?: "採取チャンネルを終了しました。"
        if(message!=null && player.isOnline) player.sendMessage(Component.text(message))
        return true
    }
    private fun reject(player:org.bukkit.entity.Player,message:String) {
        lastAttempt[player.uniqueId]=message
        if(lastAttempt.size>1000) lastAttempt.keys.firstOrNull()?.let(lastAttempt::remove)
        plugin.logger.fine("GATHER ${player.name}: $message")
        if(player.isOnline) player.sendMessage(Component.text(message))
    }
    private fun tick() {
        val now=System.currentTimeMillis()
        channels.toMap().forEach { (id,channel) ->
            val player=Bukkit.getPlayer(id)
            if(player==null || !player.isOnline) { channels.remove(id)?.bar?.removeAll(); return@forEach }
            if(plugin.profiles[id]==null || plugin.adventure.locked(id)) { cancel(player,"採取を中断しました。"); return@forEach }
            val elapsed=now-channel.started; val ratio=(elapsed.toDouble()/channel.duration).coerceIn(0.0,1.0)
            channel.bar.progress=ratio; player.sendActionBar(Component.text("§a採取中 ${floor(ratio*100).toInt()}% §7${channel.site.node.name}"))
            if(elapsed>=channel.duration) complete(player,channel)
        }
    }
    private fun nearbyParty(player: org.bukkit.entity.Player): Boolean {
        val members=plugin.adventure.parties.members(player.uniqueId)
        return members.any { it!=player.uniqueId && Bukkit.getPlayer(it)?.let { other -> other.isOnline && other.world==player.world && other.location.distanceSquared(player.location)<=256.0 }==true }
    }
    private fun complete(player: org.bukkit.entity.Player,channel: Channel) {
        cancel(player,null)
        val profile=plugin.profiles[player.uniqueId] ?: return
        val site=channel.site; val node=site.node; val k=siteKey(site); val now=System.currentTimeMillis()
        val toolId=plugin.identify(player.inventory.itemInMainHand)
        val tackle=plugin.snapshot.world.tackle.values.firstOrNull { it.item==toolId } ?: return
        val pity=profile.dropPity.toMutableMap()
        val result=engine.roll(node,tackle.tier,profile.lifeSkills[LifeSkillKind.GATHERING]?.level ?: 1,nearbyParty(player),plugin.snapshot.world.lifeBalance.partyGatherRareBonus,pity,plugin.snapshot.balance.dropPityAttemptCap)
        try {
            plugin.adventure.transaction(player,{ inv ->
                require(engine.reserve(profile,k,node.respawnSeconds,now)) { "この採取地点は再生中です。" }
                profile.dropPity.clear(); profile.dropPity.putAll(pity)
                result.groupBy { it.item }.forEach { (item,stacks) -> require(inv.exchange(emptyMap(),mapOf(item to stacks.sumOf { it.amount }))) }
                val progress=profile.lifeSkills.getValue(LifeSkillKind.GATHERING)
                LifeSkillEngine(plugin.snapshot.world.lifeBalance).grant(progress,node.lifeXp)
                profile.gatheringRecords[node.id]=(profile.gatheringRecords[node.id] ?: 0)+1
                plugin.snapshot.balance.grantXp(profile,node.adventurerXp)
                plugin.game.quests.event(profile,ObjectiveKind.GATHER_NODE,node.id,1,inv,alsoTargets=setOf(node.kind.name))
                val total=profile.gatheringRecords.values.sum()
                plugin.snapshot.world.lifeBalance.gatheringMilestones.filter { total>=it.threshold && profile.gatheringRecords.size>=it.uniqueNodes }.forEach { profile.flags.add(it.flag) }
            },success={
                plugin.syncTitleAdvancements(player,profile)
                player.sendMessage(Component.text("採取しました: ${result.joinToString { "${it.item} ×${it.amount}" }.ifEmpty { "空振り" }} / 採取経験値 ${node.lifeXp}"))
                plugin.markDirty(profile.uuid)
            })
        } catch(error: Exception) { player.sendMessage(Component.text("採取を完了できませんでした: ${error.message}")) }
    }
}
