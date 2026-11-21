package net.nakaymi.etherealchronicle.paper

import net.kyori.adventure.text.Component
import net.nakaymi.etherealchronicle.core.*
import org.bukkit.Bukkit
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.entity.ItemDisplay
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.player.PlayerAnimationEvent
import org.bukkit.event.player.PlayerFishEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.persistence.PersistentDataType
import java.util.UUID
import kotlin.math.abs
import kotlin.random.Random

/** Completely custom, bounded fishing sessions. Vanilla bobbers and rewards are suppressed. */
class FishingController(private val plugin:RpgPlugin,private val random:Random=Random.Default):Listener {
    private data class Session(
        val state:FishingState,val spot:FishingSpotDefinition,val rod:TackleDefinition,val castOrigin:Location,
        val water:Location,val bobber:UUID,val bar:BossBar,var input:ReelInput=ReelInput.IDLE,var lastInput:Long=0,
        var bitePending:Boolean=false,var bait:String?=null,var baitCountBefore:Int=0,var softThrottle:Boolean=false,var pullRemaining:Double=0.0
    )
    private val engine=FishingEngine(plugin.snapshot.world.lifeBalance,random)
    private val sessions=mutableMapOf<UUID,Session>()
    private val bobberKey=NamespacedKey(plugin,"fish_bobber")
    private val capacity=plugin.snapshot.world.lifeBalance.maxFishingSessions
    private val castGate=ActionGate(Clock { System.currentTimeMillis() })
    private val catchHistory=mutableMapOf<Pair<UUID,String>,ArrayDeque<Long>>()
    private val disabledUntil=mutableMapOf<Pair<UUID,String>,Long>()
    private val macroDetectors=mutableMapOf<UUID,FishingMacroDetector>()
    private var task:org.bukkit.scheduler.BukkitTask?=null

    init {
        plugin.server.pluginManager.registerEvents(this,plugin)
        task=plugin.server.scheduler.runTaskTimer(plugin,Runnable { tick() },2,2)
    }
    fun active(id:UUID)=id in sessions
    fun debugStatus(id:UUID):String = sessions[id]?.let { s ->
        "釣りセッション phase=${s.state.phase} 魚=${s.state.fish.id} 進捗=${s.state.progress.toInt()}% 張力=${s.state.tension.toInt()}% BossBar=${s.bar.players.any { it.uniqueId==id }}"
    } ?: "釣りセッションなし"
    fun debugCancel(player:org.bukkit.entity.Player):Boolean = cancel(player,"デバッグ釣りを終了しました。")
    fun debugStart(player:org.bukkit.entity.Player,speciesId:String?):String {
        require(player.world.name==DemoWorld.NAME && DemoWorld.isOwned(player.world)) { "管理対象ワールドで実行してください。" }
        require(plugin.profiles[player.uniqueId]!=null) { "プレイヤーデータを読み込み中です。" }
        require(!active(player.uniqueId)) { "既に釣り中です。" }
        val definitions=plugin.snapshot.world
        val rod=plugin.identify(player.inventory.itemInMainHand)?.let { id -> definitions.tackle.values.firstOrNull { it.item==id && it.kind==TackleKind.ROD } }
            ?: definitions.tackle.values.firstOrNull { it.kind==TackleKind.ROD } ?: error("釣り竿定義がありません。")
        val fish=speciesId?.let { definitions.fishSpecies[it] ?: error("魚種が見つかりません: $it") }
            ?: definitions.fishSpecies.values.firstOrNull() ?: error("魚種定義がありません。")
        val spot=definitions.fishingSpots.values.firstOrNull { it.world==player.world.name && (it.id in fish.habitats || it.area in fish.habitats) }
            ?: definitions.fishingSpots.values.firstOrNull { it.world==player.world.name } ?: error("このワールドの釣り場がありません。")
        require(sessions.size<capacity) { "釣りセッション数の上限です。" }
        val now=System.currentTimeMillis(); val bobberAt=player.location.clone().add(0.0,1.0,0.0)
        val bobber=player.world.spawn(bobberAt,ItemDisplay::class.java) { display ->
            display.setItemStack(org.bukkit.inventory.ItemStack(Material.HEART_OF_THE_SEA)); display.isPersistent=false; display.isInvulnerable=true
            display.persistentDataContainer.set(bobberKey,PersistentDataType.STRING,player.uniqueId.toString())
        }
        val bar=Bukkit.createBossBar("§bデバッグ釣り: ${fish.name}",BarColor.BLUE,BarStyle.SOLID).apply { addPlayer(player); progress=0.0 }
        val state=FishingState(fish,phase=FishingPhase.REELING,waitSeconds=0.0,tensionStability=rod.tensionStability,sweetSpotBonus=rod.sweetSpotBonus)
        sessions[player.uniqueId]=Session(state,spot,rod,player.location.clone(),bobberAt,bobber.uniqueId,bar)
        return "デバッグ釣りを開始しました: ${fish.id} (左クリックでいなし、右クリックで引く)。"
    }
    fun selectBait(profile:PlayerProfile,value:String):String {
        val normalized=when(value.lowercase()) { "auto" -> "AUTO"; "none" -> "NONE"; else -> value }
        require(normalized in setOf("AUTO","NONE") || plugin.snapshot.world.tackle.values.any { it.item==normalized && it.kind==TackleKind.BAIT }) { "餌が見つかりません。" }
        profile.fishingBait=normalized
        return when(normalized) { "AUTO" -> "餌を自動選択にしました。"; "NONE" -> "餌を使わない設定にしました。"; else -> "使用する餌を設定しました: ${plugin.snapshot.items[normalized].name}" }
    }
    fun close() {
        task?.cancel(); task=null
        sessions.values.toList().forEach { cleanup(it) }
        sessions.clear(); catchHistory.clear(); disabledUntil.clear(); macroDetectors.clear()
    }
    fun reload() { close(); task=plugin.server.scheduler.runTaskTimer(plugin,Runnable { tick() },2,2) }
    private fun cleanup(session:Session) { session.bar.removeAll(); Bukkit.getEntity(session.bobber)?.remove() }
    private fun cancel(player:org.bukkit.entity.Player,message:String?=null):Boolean {
        val session=sessions.remove(player.uniqueId) ?: return false
        cleanup(session)
        if(message!=null && player.isOnline) player.sendMessage(Component.text(message))
        return true
    }

    @EventHandler(priority=EventPriority.LOWEST)
    fun suppressVanilla(e:PlayerFishEvent) {
        val customRod=plugin.identify(e.player.inventory.itemInMainHand)?.let { id -> plugin.snapshot.world.tackle.values.any { it.item==id && it.kind==TackleKind.ROD } }==true
        if(e.player.world.name==DemoWorld.NAME && DemoWorld.isOwned(e.player.world) || customRod) e.isCancelled=true
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false)
    fun interact(e:PlayerInteractEvent) {
        if(e.hand!=EquipmentSlot.HAND) return
        val player=e.player
        val id=plugin.identify(player.inventory.itemInMainHand) ?: return
        val rod=plugin.snapshot.world.tackle.values.firstOrNull { it.item==id && it.kind==TackleKind.ROD } ?: return
        if(e.action==Action.LEFT_CLICK_AIR || e.action==Action.LEFT_CLICK_BLOCK) {
            val session=sessions[player.uniqueId]
            if(session?.state?.phase==FishingPhase.REELING) { e.isCancelled=true; pulse(player,session,ReelInput.BRACE) }
            return
        }
        if(!e.action.isRightClick) return
        e.isCancelled=true
        if(sessions.containsKey(player.uniqueId)) {
            val session=sessions[player.uniqueId] ?: return
            when(session.state.phase) {
                FishingPhase.BITE_ALERT -> pulse(player,session,ReelInput.PULL)
                FishingPhase.REELING -> pulse(player,session,ReelInput.PULL)
                else -> cancel(player,"釣りを中断しました。")
            }
            return
        }
        cast(player,rod)
    }
    private fun cast(player:org.bukkit.entity.Player,rod:TackleDefinition) {
        if(player.world.name!=DemoWorld.NAME || !DemoWorld.isOwned(player.world)) { player.sendMessage(Component.text("釣りは管理された冒険ワールドでのみ行えます。")); return }
        val profile=plugin.profiles[player.uniqueId] ?: return
        if(profile.classId==null || plugin.adventure.locked(profile.uuid)) { player.sendMessage(Component.text("冒険データを準備中です。")); return }
        val balance=plugin.snapshot.world.lifeBalance
        if(sessions.size>=capacity) { player.sendMessage(Component.text("釣り人が多く、竿を出せません。少し待ってください。")); return }
        if(profile.lifeSkills.getValue(LifeSkillKind.FISHING).level<rod.minLifeLevel) { player.sendMessage(Component.text("釣りレベルが足りません。")); return }
        if(!castGate.accept(player.uniqueId,balance.castCooldownMillis)) { player.sendMessage(Component.text("次の釣りまで少しお待ちください。")); return }
        val eye=player.eyeLocation
        val hit=player.world.rayTraceBlocks(eye,eye.direction,balance.castRange,FluidCollisionMode.ALWAYS,true)?.hitBlock
        if(hit==null || !(hit.type==Material.WATER || hit.blockData is org.bukkit.block.data.Waterlogged && (hit.blockData as org.bukkit.block.data.Waterlogged).isWaterlogged) || !hit.getRelative(0,1,0).type.isAir) {
            player.sendMessage(Component.text("水面を狙って竿を投げてください。")); return
        }
        val target=hit.location.clone().add(0.5,0.85,0.5)
        val distance=player.location.distance(target)
        if(distance !in 2.0..balance.castRange) { player.sendMessage(Component.text("水面が近すぎるか、遠すぎます。")); return }
        val spot=plugin.snapshot.world.fishingSpots.values.firstOrNull { s ->
            s.world==player.world.name && s.center.let { center -> val x=target.x-center.x; val z=target.z-center.z; x*x+z*z<=s.radius*s.radius }
        } ?: run { player.sendMessage(Component.text("この水辺には魚影が見当たりません。")); return }
        val p=profile.lifeSkills.getValue(LifeSkillKind.FISHING)
        val bait=chooseBait(player,profile)
        val weather=when { player.world.isThundering -> "THUNDER"; player.world.hasStorm() -> "RAIN"; else -> "CLEAR" }
        val eligible=engine.eligible(plugin.snapshot.world.fishSpecies.values,spot,rod.tier,p.level,player.world.time,weather)
        val recent=catchHistory[player.uniqueId to spot.id]?.count { System.currentTimeMillis()-it<balance.fatigueWindowSeconds*1000 } ?: 0
        if(System.currentTimeMillis()<(disabledUntil[player.uniqueId to spot.id] ?: 0)) { player.sendMessage(Component.text("魚影が薄くなりました。別の釣り場へ移動してください。")); return }
        val candidates=if(recent>=balance.fatigueHardThreshold) emptyList() else eligible.filter { fish ->
            if(recent>=balance.fatigueSoftThreshold) fish.rarity !in setOf(Rarity.EPIC,Rarity.LEGENDARY) else true
        }
        val baitDef=bait?.let { id -> plugin.snapshot.world.tackle.values.firstOrNull { it.item==id } }
        val macroConfig=plugin.snapshot.world.lifeBalance
        val detector=macroDetectors.getOrPut(player.uniqueId) { FishingMacroDetector() }
        val macro=detector.suspicious(macroConfig.macroMinSamples,macroConfig.macroResponseDeviationMillis,macroConfig.macroCastIntervalDeviationMillis)
        val weighted=candidates.map { fish ->
            val weight=(baitDef?.rarityWeights?.get(fish.rarity.name) ?: if(baitDef==null && fish.rarity>=Rarity.RARE) 0.35 else 1.0) *
                (if(recent>=balance.fatigueSoftThreshold && fish.rarity>=Rarity.RARE) 0.7 else 1.0) *
                (if(macro && fish.rarity>=Rarity.RARE) macroConfig.macroRareWeightMultiplier else 1.0) *
                if(baitDef!=null && baitDef.preferredTags.any { it in fish.tags }) baitDef.preferredTagMultiplier else 1.0
            fish.copy(baseWeight=fish.baseWeight*weight)
        }
        val fish=engine.choose(weighted) ?: run { player.sendMessage(Component.text("この竿と釣り場では狙える魚がいません。")); return }
        val wait=(2.5+random.nextDouble()*6.0)*(1-(rod.waitReduction+(baitDef?.waitReduction ?: 0.0)).coerceAtMost(0.5))*(if(recent>=balance.fatigueSoftThreshold) 1.4 else 1.0)
        val state=FishingState(fish,waitSeconds=wait,tensionStability=rod.tensionStability,sweetSpotBonus=rod.sweetSpotBonus)
        val bobber=player.world.spawn(target,ItemDisplay::class.java) { display ->
            display.setItemStack(org.bukkit.inventory.ItemStack(Material.HEART_OF_THE_SEA))
            display.isPersistent=false; display.isInvulnerable=true
            display.persistentDataContainer.set(bobberKey,PersistentDataType.STRING,player.uniqueId.toString())
        }
        val bar=Bukkit.createBossBar("釣り場を探っています…",BarColor.BLUE,BarStyle.SOLID).apply { addPlayer(player); progress=0.0 }
        sessions[player.uniqueId]=Session(state,spot,rod,player.location.clone(),target,bobber.uniqueId,bar,bait=bait,baitCountBefore=bait?.let { plugin.inventory(player).count(it) } ?: 0,softThrottle=macro)
        detector.cast(System.currentTimeMillis())
        player.playSound(target,org.bukkit.Sound.ENTITY_FISHING_BOBBER_SPLASH,0.5f,1.4f)
        player.sendActionBar(Component.text("§b釣り糸を垂らしました。再度右クリックで回収できます。"))
    }
    private fun pulse(player:org.bukkit.entity.Player,session:Session,input:ReelInput) {
        val now=System.currentTimeMillis()
        if(now-session.lastInput<150) return
        session.lastInput=now; session.input=input
        val detector=macroDetectors.getOrPut(player.uniqueId) { FishingMacroDetector() }
        detector.pull(now)
        val balance=plugin.snapshot.world.lifeBalance
        if(detector.suspicious(balance.macroMinSamples,balance.macroResponseDeviationMillis,balance.macroCastIntervalDeviationMillis) && !session.softThrottle) { session.softThrottle=true; player.sendMessage(Component.text("機械的な操作間隔を検出しました。希少魚の出現率を調整します。")) }
    }
    @EventHandler fun swing(e:PlayerAnimationEvent) {
        val s=sessions[e.player.uniqueId] ?: return
        if(s.state.phase==FishingPhase.REELING && e.animationType==org.bukkit.event.player.PlayerAnimationType.ARM_SWING) pulse(e.player,s,ReelInput.BRACE)
    }
    @EventHandler fun held(e:PlayerItemHeldEvent) { if(active(e.player.uniqueId)) cancel(e.player,"竿を持ち替えたため釣りを中断しました。") }
    @EventHandler fun swap(e:PlayerSwapHandItemsEvent) { if(active(e.player.uniqueId)) cancel(e.player,"釣りを中断しました。") }
    @EventHandler fun quit(e:PlayerQuitEvent) { cancel(e.player) }
    @EventHandler fun death(e:PlayerDeathEvent) { cancel(e.entity,"倒れたため釣りを中断しました。") }
    @EventHandler(ignoreCancelled=true) fun damage(e:EntityDamageEvent) { (e.entity as? org.bukkit.entity.Player)?.let { if(active(it.uniqueId)) cancel(it,"戦闘により釣りを中断しました。") } }
    @EventHandler fun moved(e:org.bukkit.event.player.PlayerMoveEvent) {
        val session=sessions[e.player.uniqueId] ?: return
        if(e.from.world!=e.to.world || e.to.distanceSquared(session.castOrigin)>25.0 || e.to.distanceSquared(session.water)>225.0) cancel(e.player,"釣り場から離れたため中断しました。")
    }
    @EventHandler fun chunk(e:ChunkLoadEvent) {
        e.chunk.entities.filter { it.persistentDataContainer.has(bobberKey,PersistentDataType.STRING) }.forEach { ent ->
            if(sessions.values.none { it.bobber==ent.uniqueId }) ent.remove()
        }
    }

    private fun beginBite(player:org.bukkit.entity.Player,s:Session) {
        if(s.bitePending) return
        val bait=s.bait
        if(bait==null) { s.state.phase=FishingPhase.BITE_ALERT; showBite(player); return }
        s.bait=bait; s.baitCountBefore=plugin.inventory(player).count(bait); s.bitePending=true
        try {
            plugin.adventure.transaction(player,{ inventory -> require(inventory.exchange(mapOf(bait to 1),emptyMap())) { "餌を持っていません。" } }) {
                if(sessions[player.uniqueId]===s) { s.bitePending=false; s.state.phase=FishingPhase.BITE_ALERT; showBite(player) }
            }
        } catch(error:Exception) { s.bitePending=false; s.state.phase=FishingPhase.BITE_ALERT; player.sendMessage(Component.text("餌を消費できませんでした。")); showBite(player) }
    }
    private fun chooseBait(player:org.bukkit.entity.Player,profile:PlayerProfile):String? {
        if(profile.fishingBait=="NONE") return null
        val inventory=plugin.inventory(player)
        if(profile.fishingBait!="AUTO") {
            if(inventory.count(profile.fishingBait)<=0) { player.sendMessage(Component.text("選択中の餌を持っていません。")); return null }
            return profile.fishingBait
        }
        val baitItems=plugin.snapshot.world.tackle.values.filter { it.kind==TackleKind.BAIT }.associateBy { it.item }
        val offhand=plugin.identify(player.inventory.itemInOffHand)
        if(offhand in baitItems && inventory.count(offhand!!)>0) return offhand
        for(slot in 0..8) {
            val id=plugin.identify(player.inventory.getItem(slot))
            if(id in baitItems && inventory.count(id!!)>0) return id
        }
        return null
    }
    private fun showBite(player:org.bukkit.entity.Player) {
        macroDetectors.getOrPut(player.uniqueId) { FishingMacroDetector() }.bite(System.currentTimeMillis())
        player.sendTitle("§e⚡ ヒット！","§f右クリックで合わせろ！",0,18,6)
        player.playSound(player.location,org.bukkit.Sound.ENTITY_FISHING_BOBBER_SPLASH,1f,1.2f)
    }
    private fun tick() {
        castGate.cleanup(); val now=System.currentTimeMillis()
        disabledUntil.entries.removeIf { it.value<=now }
        catchHistory.values.forEach { it.removeIf { ts -> now-ts>plugin.snapshot.world.lifeBalance.fatigueWindowSeconds*1000 } }
        sessions.toMap().forEach { (id,s) ->
            val player=Bukkit.getPlayer(id)
            if(player==null || !player.isOnline) { sessions.remove(id)?.let(::cleanup); return@forEach }
            val stack=plugin.identify(player.inventory.itemInMainHand)
            if(stack!=s.rod.item || player.world.name!=s.water.world.name) { cancel(player,"竿を持ち替えたため釣りを中断しました。"); return@forEach }
            if(plugin.adventure.locked(id)) return@forEach
            val phaseBefore=s.state.phase
            if(s.bitePending) {
                // The durable transaction owns the item. Its success callback advances the session;
                // if it rolls back and releases the lock, no bait was consumed and retry stays safe.
                if(!plugin.adventure.locked(id) && plugin.inventory(player).count(s.bait ?: "")>=s.baitCountBefore) {
                    s.bitePending=false; s.state.phase=FishingPhase.BITE_ALERT; showBite(player)
                } else return@forEach
            }
            val buffered=s.input; s.input=ReelInput.IDLE
            val input=when {
                buffered==ReelInput.BRACE -> { s.pullRemaining=0.0; ReelInput.BRACE }
                buffered==ReelInput.PULL -> { s.pullRemaining=0.35; ReelInput.PULL }
                s.pullRemaining>0.0 -> { s.pullRemaining=(s.pullRemaining-0.1).coerceAtLeast(0.0); ReelInput.PULL }
                else -> ReelInput.IDLE
            }
            val phase=engine.tick(s.state,0.1,input)
            if(phase==FishingPhase.BITE_ALERT && phaseBefore==FishingPhase.WAITING) beginBite(player,s)
            if(phase!=phaseBefore) {
                when(phase) {
                    FishingPhase.CAUGHT -> reward(player,s)
                    FishingPhase.ESCAPED -> { player.sendTitle("§c逃げられた…","§7糸が切れてしまった",0,18,6); cancel(player) }
                    else -> Unit
                }
            }
            if(sessions[id]===s) {
                val behavior=when(s.state.behavior) { FishBehavior.CALM -> "安定"; FishBehavior.TELEGRAPH -> "予告"; FishBehavior.THRASH -> "暴れ"; FishBehavior.DIVE -> "潜り" }
                s.bar.setTitle(if(s.state.phase==FishingPhase.REELING) "§b格闘中 [$behavior] 進捗 ${s.state.progress.toInt()}%" else "§b魚影を探しています…")
                s.bar.progress=(s.state.progress/100).coerceIn(0.0,1.0)
                s.bar.color=when(s.state.behavior) { FishBehavior.DIVE -> BarColor.BLUE; FishBehavior.THRASH -> BarColor.RED; FishBehavior.TELEGRAPH -> BarColor.YELLOW; else -> BarColor.GREEN }
                player.sendActionBar(Component.text("§b張力 ${s.state.tension.toInt()}%  |  進捗 ${s.state.progress.toInt()}%  右:引く / 左:いなす"))
            }
        }
    }
    private fun reward(player:org.bukkit.entity.Player,s:Session) {
        val profile=plugin.profiles[player.uniqueId] ?: run { cancel(player); return }
        val catch=engine.catch(s.state,s.rod.sizeBonus,profile.lifeSkills.getValue(LifeSkillKind.FISHING).level)
        cancel(player)
        try {
            plugin.adventure.transaction(player,{ inventory ->
                require(inventory.exchange(emptyMap(),mapOf(catch.species.item to 1)))
                engine.record(profile,catch)
                LifeSkillEngine(plugin.snapshot.world.lifeBalance).grant(profile.lifeSkills.getValue(LifeSkillKind.FISHING),catch.species.lifeXp)
                plugin.snapshot.balance.grantXp(profile,catch.species.adventurerXp)
                val score=engine.score(profile)
                plugin.snapshot.world.lifeBalance.fishingMilestones.filter { score>=it.threshold }.forEach { profile.flags.add(it.flag) }
                val key=player.uniqueId to s.spot.id
                val history=catchHistory.getOrPut(key) { ArrayDeque() }; history.addLast(System.currentTimeMillis())
                val fatigue=plugin.snapshot.world.lifeBalance
                if(history.size>fatigue.fatigueHardThreshold) disabledUntil[key]=System.currentTimeMillis()+fatigue.fatigueCooldownSeconds*1000
            },success={
                plugin.syncTitleAdvancements(player,profile)
                val isNew=profile.fishRecords[catch.species.id]?.count==1
                player.sendTitle("§b★ 釣り上げた！","§f${catch.species.name} ${catch.sizeCm}cm ${when(catch.crown){Crown.GOLD->"§6[金冠]";Crown.SILVER->"§7[銀冠]";else->""}} ${if(isNew) "§aNEW!" else ""}",0,18,6)
                player.playSound(player.location,org.bukkit.Sound.ENTITY_PLAYER_LEVELUP,0.5f,1.6f)
                plugin.markDirty(profile.uuid)
            })
        } catch(error:Exception) { player.sendMessage(Component.text("魚の受け取りに失敗しました。所持品を整理して再度お試しください。")) }
    }
}
