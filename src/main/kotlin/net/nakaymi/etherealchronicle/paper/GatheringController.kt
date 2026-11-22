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

/** 表示の原点をクリック可能な採取タイルの中心に合わせます。 */
internal fun gatheringBlockDisplayTranslation()=Vector3f(-0.5f,0f,-0.5f)

/** ノード別の採取チャネル・再出現・仮想表示を管理し、バニラ地形を変更しません。 */
class GatheringController(private val plugin: RpgPlugin): Listener {
    private data class Site(val node: GatheringNodeDefinition,val location: GatheringLocation)
    private data class Channel(val site: Site,val started: Long,val duration: Long,val origin: Location,val bar: BossBar)
    private val engine=GatheringEngine()
    private var sites=buildSites()
    private var sitesById=sites.values.distinctBy(::siteKey).associateBy(::siteKey)
    private var sitesByChunk=sitesById.values.groupBy { (it.location.x shr 4) to (it.location.z shr 4) }
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

    /** 対象プレイヤーが採取チャネル中かを返します。 */
    fun active(player: UUID)=player in channels
    /** 状態、進捗、BossBarの接続先を管理ログ用に返します。 */
    fun debugStatus(player:UUID):String {
        val locale = Bukkit.getPlayer(player)?.locale()?.toString()
        val channel = channels[player]
        if (channel != null) {
            val progress = ((monotonicMillis() - channel.started).toDouble() / channel.duration).coerceIn(0.0, 1.0)
            return plugin.message(locale, "ec.debug.gathering_active", mapOf(
                "node" to "${channel.site.node.id}/${channel.site.location.id}",
                "progress" to (progress * 100).toInt(),
                "bossbar" to channel.bar.players.any { it.uniqueId == player },
                "visible" to channel.bar.isVisible,
                "duration" to channel.duration
            )).let(::plain)
        }
        val last = lastAttempt[player]
        val lastText = last?.takeIf { it.startsWith("ec.") }?.let { plain(plugin.message(locale, it)) }
            ?: last ?: plain(plugin.message(locale, "ec.debug.none"))
        return plain(plugin.message(locale, "ec.debug.gathering_inactive", mapOf("last" to lastText)))
    }
    /** プレイヤー周辺の装備・条件・距離・ロックを併記した採取診断を返します。 */
    fun debugStatus(player:org.bukkit.entity.Player):String {
        val active=debugStatus(player.uniqueId)
        val profile=plugin.profiles[player.uniqueId]
        val held=plugin.identify(player.inventory.itemInMainHand) ?: "vanilla:${player.inventory.itemInMainHand.type}"
        val tackle=plugin.snapshot.world.tackle.values.firstOrNull { it.item==held }
        val nearest=sitesById.values.filter { it.location.world==player.world.name }.minByOrNull { site ->
            player.location.distanceSquared(Location(player.world,site.location.x+0.5,site.location.y.toDouble(),site.location.z+0.5))
        }
        val nearestText=nearest?.let { site ->
            val distance=kotlin.math.sqrt(player.location.distanceSquared(Location(player.world,site.location.x+0.5,site.location.y.toDouble(),site.location.z+0.5)))
            plugin.message(player,"ec.debug.gathering_nearest",
                "site" to "${site.node.id}/${site.location.id}", "distance" to "%.2f".format(distance),
                "kind" to site.node.toolKind, "tier" to site.node.minToolTier, "level" to site.node.minGatheringLevel,
                "cooldown" to (profile?.gatheringReadyAt?.get(siteKey(site))?.let { maxOf(0,it-System.currentTimeMillis()) } ?: 0)
            ).let(::plain)
        } ?: plain(plugin.message(player,"ec.debug.none"))
        val owned=runCatching { player.world.name==DemoWorld.NAME && DemoWorld.isOwned(player.world) }.getOrDefault(false)
        return plugin.message(player,"ec.debug.gathering_details",
            "active" to active, "world" to player.world.name, "owned" to owned,
            "x" to player.location.blockX, "y" to player.location.blockY, "z" to player.location.blockZ,
            "profile" to (profile!=null), "level" to (profile?.lifeSkills?.get(LifeSkillKind.GATHERING)?.level ?: "?"),
            "held" to held, "tool" to "${tackle?.kind ?: "none"}/T${tackle?.tier ?: 0}",
            "nearest" to nearestText, "locked" to (profile?.let { plugin.adventure.locked(it.uuid) } ?: false)
        ).let(::plain)
    }
    private fun plain(component:Component):String = component.plainText()
    /** 戦闘ダメージを受けた採取者だけを中断します。 */
    fun interrupt(player: UUID) { Bukkit.getPlayer(player)?.let { cancel(it,"ec.gathering.cancel_damage") } }
    /** タスク、チャネルBossBar、管理表示を解除します。 */
    fun close() {
        task?.cancel(); task=null
        channels.values.forEach { it.bar.removeAll() }
        channels.clear()
        displays.values.flatten().forEach { Bukkit.getEntity(it)?.remove() }
        displays.clear()
    }
    /** コンテンツ再読込後に表示と短周期タスクを作り直します。 */
    fun reload() {
        close()
        sites=buildSites()
        sitesById=sites.values.distinctBy(::siteKey).associateBy(::siteKey)
        sitesByChunk=sitesById.values.groupBy { (it.location.x shr 4) to (it.location.z shr 4) }
        renderAll()
        task=plugin.server.scheduler.runTaskTimer(plugin,Runnable { tick() },2,2)
    }

    private fun key(world:String,x:Int,y:Int,z:Int)="$world:$x,$y,$z"
    private fun siteKey(site: Site)="${site.node.id}/${site.location.id}"
    private fun buildSites() = plugin.snapshot.world.gatheringNodes.values.flatMap { node -> node.locations.map { Site(node,it) } }
        .flatMap { site -> listOf(
            key(site.location.world,site.location.x,site.location.y-1,site.location.z) to site,
            key(site.location.world,site.location.x,site.location.y,site.location.z) to site
        ) }.toMap()
    private fun location(site: Site)=Bukkit.getWorld(site.location.world)?.let { Location(it,site.location.x+0.5,site.location.y.toDouble(),site.location.z+0.5) }

    private fun renderAll() {
        // 一地点のワールド・表示失敗を局所化し、起動やプロフィール読込を巻き込みません。
        sitesById.values.forEach { site ->
            runCatching { renderSite(site) }.onFailure { error ->
                plugin.logger.warning("Could not render gathering node ${site.node.id}/${site.location.id}: ${error.message}")
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
            entity.text(plugin.message(Bukkit.getConsoleSender(), "ec.gathering.node_label", "name" to site.node.name, "level" to site.node.minGatheringLevel))
            entity.isPersistent=false; entity.isInvulnerable=true
            entity.persistentDataContainer.set(displayKey,PersistentDataType.STRING,k)
        }
        val hitbox=world.spawn(at,Interaction::class.java) { entity ->
            entity.interactionWidth=1.1f; entity.interactionHeight=1.1f
            entity.isPersistent=false; entity.isInvulnerable=true
            entity.persistentDataContainer.set(hitboxKey,PersistentDataType.STRING,k)
        }
        displays[k]=listOf(display.uniqueId,label.uniqueId,hitbox.uniqueId)
        if(!block.type.isAir && block.type!=Material.WATER) plugin.logger.fine("Gathering node display location is obstructed: $k")
    }

    /** 採取地点のバニラブロック破壊を抑止します。 */
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    fun breakBlock(e: BlockBreakEvent) {
        val pos=e.block.location
        if(isProtected(pos)) { e.isCancelled=true; e.player.sendMessage(plugin.message(e.player,"ec.gathering.protected_terrain")) }
    }
    /** コンテンツで管理する採取地点へのブロック設置を禁止します。 */
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    fun place(e: BlockPlaceEvent) { if(isProtected(e.block.location)) e.isCancelled=true }
    /** 爆発による管理地点のブロック破壊だけを対象から除外します。 */
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    fun explode(e: EntityExplodeEvent) { e.blockList().removeIf { isProtected(it.location) } }
    /** 採取地点のブロックを押すピストンを抑止します。 */
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    fun pistonExtend(e: BlockPistonExtendEvent) { if(e.blocks.any { isProtected(it.location) }) e.isCancelled=true }
    /** 採取地点へ引き戻すピストンを抑止します。 */
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    fun pistonRetract(e: BlockPistonRetractEvent) { if(e.blocks.any { isProtected(it.location) }) e.isCancelled=true }
    /** チャンク単位で管理表示を除去・復元します。ワールド全体は走査しません。 */
    @EventHandler fun chunk(e: ChunkLoadEvent) {
        e.chunk.entities.filter {
            it.persistentDataContainer.has(displayKey,PersistentDataType.STRING) ||
                it.persistentDataContainer.has(hitboxKey,PersistentDataType.STRING)
        }.forEach { entity ->
            if(displays.values.flatten().none { it == entity.uniqueId }) entity.remove()
        }
        sitesByChunk[e.chunk.x to e.chunk.z].orEmpty().filter { it.location.world==e.world.name }.forEach(::renderSite)
    }
    private fun isProtected(location: Location): Boolean {
        if(location.world.name != WorldIds.DEMO) return false
        val x=location.blockX; val y=location.blockY; val z=location.blockZ
        return sitesById.values.any { s -> s.location.world==location.world.name && kotlin.math.abs(s.location.x-x)<=1 && kotlin.math.abs(s.location.y-y)<=1 && kotlin.math.abs(s.location.z-z)<=1 }
    }

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    /** 事前生成した座標indexだけを参照し、採取チャネル開始条件を確認します。 */
    fun interact(e: PlayerInteractEvent) {
        if(e.hand != EquipmentSlot.HAND || e.action != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return
        val block=e.clickedBlock ?: return
        val site=sites[key(block.world.name,block.x,block.y,block.z)]
        if(site==null) {
            val nearby=sitesById.values.filter { it.location.world==block.world.name }.minByOrNull { (it.location.x-block.x)*(it.location.x-block.x)+(it.location.z-block.z)*(it.location.z-block.z) }
            if(nearby!=null && kotlin.math.abs(nearby.location.y-block.y)<=2 && (nearby.location.x-block.x)*(nearby.location.x-block.x)+(nearby.location.z-block.z)*(nearby.location.z-block.z)<=4) {
                e.isCancelled=true
                reject(e.player,"ec.gathering.click_site", "name" to nearby.node.name)
            }
            return
        }
        e.isCancelled=true
        begin(e.player,site)
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    /** 可視ノードに重ねたInteraction entityからのクリックを同じ座標経路へ送ります。 */
    fun interactDisplay(e: PlayerInteractEntityEvent) {
        val k=e.rightClicked.persistentDataContainer.get(hitboxKey,PersistentDataType.STRING) ?: return
        val site=sitesById[k] ?: run { reject(e.player,"ec.gathering.site_unavailable"); return }
        e.isCancelled=true
        begin(e.player,site)
    }
    private fun begin(player: org.bukkit.entity.Player,site: Site) {
        if(player.world.name!=DemoWorld.NAME || !DemoWorld.isOwned(player.world)) { reject(player,"ec.gathering.world_only"); return }
        val profile=plugin.profiles[player.uniqueId] ?: run { reject(player,"ec.gathering.profile_loading"); return }
        if(plugin.adventure.locked(profile.uuid)) { reject(player,"ec.gathering.transaction_locked"); return }
        if(channels.containsKey(player.uniqueId)) { reject(player,"ec.gathering.already_gathering"); return }
        if(channels.size>=limit) { reject(player,"ec.gathering.capacity"); return }
        val def=site.node
        val toolId=plugin.identify(player.inventory.itemInMainHand)
        val tool=toolId?.let { id -> plugin.snapshot.world.tackle.values.find { it.item==id && it.kind in setOf(TackleKind.PICKAXE,TackleKind.SICKLE,TackleKind.HATCHET,TackleKind.EXTRACTOR) } }
        val lifeLevel=profile.lifeSkills[LifeSkillKind.GATHERING]?.level ?: 1
        if(tool==null) { reject(player,"ec.gathering.tool_required", "name" to def.name, "kind" to def.toolKind); return }
        if(tool.kind!=def.toolKind) { reject(player,"ec.gathering.tool_wrong", "name" to def.name, "required" to def.toolKind, "current" to tool.kind); return }
        if(tool.tier<def.minToolTier) { reject(player,"ec.gathering.tool_tier_low", "tier" to def.minToolTier); return }
        if(lifeLevel<def.minGatheringLevel) { reject(player,"ec.gathering.level_low", "level" to def.minGatheringLevel); return }
        if(lifeLevel<tool.minLifeLevel) { reject(player,"ec.gathering.tool_level_low", "level" to tool.minLifeLevel); return }
        if(!engine.canHarvest(def,tool.kind,tool.tier,lifeLevel)) {
            reject(player,"ec.gathering.requirements"); return
        }
        val now=System.currentTimeMillis(); val k=siteKey(site)
        if(!engine.readyAt(profile,k,now)) { reject(player,"ec.gathering.respawning", "seconds" to (profile.gatheringReadyAt[k]!!-now+999)/1000); return }
        val origin=player.location.clone(); val duration=(def.channelMillis*(1-tool.channelSpeedBonus)).toLong().coerceAtLeast(250)
        val barTitle=net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
            .serialize(plugin.message(player,"ec.gathering.bossbar", "name" to def.name))
        val bar=Bukkit.createBossBar(barTitle,BarColor.GREEN,BarStyle.SOLID).apply { addPlayer(player); progress=0.08 }
        channels[player.uniqueId]=Channel(site,monotonicMillis(),duration,origin,bar)
        lastAttempt[player.uniqueId]="ec.gathering.channel_started"
        player.sendActionBar(plugin.message(player,"ec.gathering.actionbar_start"))
        player.sendMessage(plugin.message(player,"ec.gathering.start_notice", "name" to def.name))
    }

    @EventHandler fun moved(e: PlayerMoveEvent) {
        val channel=channels[e.player.uniqueId] ?: return
        val to=e.to
        if(e.from.world != to.world || e.from.distanceSquared(to) > 0.09) cancel(e.player,"ec.gathering.cancel_move")
    }
    @EventHandler fun quit(e: PlayerQuitEvent) { cancel(e.player,null); lastAttempt.remove(e.player.uniqueId) }
    @EventHandler fun death(e: PlayerDeathEvent) { cancel(e.entity,"ec.gathering.cancel_death") }
    @EventHandler(ignoreCancelled=true) fun damage(e: EntityDamageEvent) { (e.entity as? org.bukkit.entity.Player)?.let { interrupt(it.uniqueId) } }
    @EventHandler fun held(e: PlayerItemHeldEvent) { if(active(e.player.uniqueId)) cancel(e.player,"ec.gathering.cancel_tool") }
    private fun cancel(player: org.bukkit.entity.Player,messageKey:String?):Boolean {
        val channel=channels.remove(player.uniqueId) ?: return false
        channel.bar.removeAll()
        lastAttempt[player.uniqueId]=messageKey ?: "ec.gathering.channel_ended"
        if(messageKey!=null && player.isOnline) player.sendMessage(plugin.message(player,messageKey))
        return true
    }
    private fun reject(player:org.bukkit.entity.Player,key:String,vararg values:Pair<String,Any?>) {
        // 診断表示にもその時点のプレースホルダーを反映し、未展開のキーを残しません。
        val rendered = plain(plugin.message(player, key, *values))
        lastAttempt[player.uniqueId]=rendered
        if(lastAttempt.size>1000) lastAttempt.keys.firstOrNull()?.let(lastAttempt::remove)
        plugin.logger.fine("GATHER ${player.name}: $key")
        if(player.isOnline) player.sendMessage(net.kyori.adventure.text.Component.text(rendered))
    }
    private fun tick() {
        val now=monotonicMillis()
        channels.toMap().forEach { (id,channel) ->
            val player=Bukkit.getPlayer(id)
            if(player==null || !player.isOnline) { channels.remove(id)?.bar?.removeAll(); return@forEach }
            if(plugin.profiles[id]==null || plugin.adventure.locked(id)) { cancel(player,"ec.gathering.cancel_generic"); return@forEach }
            val elapsed=now-channel.started; val ratio=(elapsed.toDouble()/channel.duration).coerceIn(0.0,1.0)
            channel.bar.progress=ratio; player.sendActionBar(plugin.message(player,"ec.gathering.actionbar_progress", "percent" to floor(ratio*100).toInt(), "name" to channel.site.node.name))
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
                require(engine.reserve(profile,k,node.respawnSeconds,now)) { "ec.gathering.respawned" }
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
                val items=result.joinToString { "${plugin.contentName(player,it.item)} ×${it.amount}" }.ifEmpty { plain(plugin.message(player,"ec.gathering.reward_empty")) }
                player.sendMessage(plugin.message(player,"ec.gathering.reward", "items" to items, "xp" to node.lifeXp))
                plugin.markDirty(profile.uuid)
            })
        } catch(error: Exception) {
            player.sendMessage(plugin.message(player,"ec.gathering.reward_failed", "reason" to plugin.diagnostic(player,error.message.orEmpty())))
        }
    }
}
