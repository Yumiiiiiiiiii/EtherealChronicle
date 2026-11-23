package net.nakaymi.etherealchronicle.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
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
import org.bukkit.event.Event
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

/** 有界の独自釣りセッションを管理し、バニラの浮きと釣果報酬を抑止します。 */
class FishingController(private val plugin:RpgPlugin,private val random:Random=Random.Default):Listener {
    internal fun projectedSpots(): List<FishingSpotDefinition> = plugin.snapshot.world.fishingSpots.values.mapNotNull { spot ->
        val projected = plugin.activeWorldProjection.projector.fishingWaterCenter(spot)
            ?: plugin.projectedPosition(spot.center, spot.area, allowWater = true)
        projected?.let { spot.copy(world = it.world, center = it) }
    }

    /** 拠点変更時に旧座標の釣りセッションを終了します。 */
    fun refreshWorldAnchors(changedAreaIds: Set<String>? = null) {
        if (changedAreaIds == null) {
            reload()
            return
        }
        if (changedAreaIds.isEmpty()) return
        sessions.entries.filter { it.value.spot.area in changedAreaIds }.map { it.key }.forEach { id ->
            Bukkit.getPlayer(id)?.let { cancel(it, "ec.fishing.line_left", FishingEndReason.WORLD_UNLOAD) }
                ?: sessions.remove(id)?.let { cleanup(it) }
        }
    }
    private data class Session(
        val state:FishingState,val spot:FishingSpotDefinition,val rod:TackleDefinition,val castOrigin:Location,
        val water:Location,val bobber:UUID,val bar:BossBar,var input:ReelInput=ReelInput.IDLE,var lastInput:Long=0,
        var bitePending:Boolean=false,var bait:String?=null,var baitCountBefore:Int=0,var softThrottle:Boolean=false,var pullRemaining:Double=0.0
    )
    private var engine=FishingEngine(plugin.snapshot.world.lifeBalance,random)
    private val sessions=mutableMapOf<UUID,Session>()
    private val bobberKey=NamespacedKey(plugin,"fish_bobber")
    private var capacity=plugin.snapshot.world.lifeBalance.maxFishingSessions
    private val castGate=ActionGate(Clock { monotonicMillis() })
    private val catchHistory=mutableMapOf<Pair<UUID,String>,ArrayDeque<Long>>()
    private val disabledUntil=mutableMapOf<Pair<UUID,String>,Long>()
    private val macroDetectors=mutableMapOf<UUID,FishingMacroDetector>()
    private val pullBufferSeconds=0.25

    private fun logEnd(subject:String,fishId:String,state:FishingState,reason:FishingEndReason) {
        plugin.logger.info(fishingEndLogLine(subject,reason,fishId,state.progress.toInt(),state.tension.toInt()))
    }
    private var task:org.bukkit.scheduler.BukkitTask?=null

    init {
        plugin.server.pluginManager.registerEvents(this,plugin)
        task=plugin.server.scheduler.runTaskTimer(plugin,Runnable { tick() },PaperTaskIntervals.FISHING_UPDATE_TICKS,PaperTaskIntervals.FISHING_UPDATE_TICKS)
    }
    /** プレイヤーが釣りセッションを保持しているかを返します。 */
    fun active(id:UUID)=id in sessions
    /** 進行状態とBossBar接続を管理用に読み取れる形式で返します。 */
    fun debugStatus(id:UUID):String = sessions[id]?.let { s ->
        plugin.message(Bukkit.getPlayer(id)?.locale()?.toString(), "ec.debug.fishing_active", mapOf(
            "phase" to s.state.phase,
            "action" to s.state.behavior,
            "elapsed" to "%.1f".format(s.state.elapsedSeconds),
            "limit" to plugin.snapshot.world.lifeBalance.reelingTimeoutSeconds,
            "fish" to s.state.fish.name,
            "progress" to s.state.progress.toInt(),
            "tension" to s.state.tension.toInt(),
            "bossbar" to (s.bar.players.any { it.uniqueId==id })
        )).let(::plain)
    } ?: plain(plugin.message(Bukkit.getPlayer(id)?.locale()?.toString(), "ec.debug.fishing_inactive"))
    /** 管理者用の仮想釣りを終了し、通常のcleanup経路を通します。 */
    fun debugCancel(player:org.bukkit.entity.Player):Boolean = cancel(player,"ec.fishing.debug_cancel")
    /** 通常ミニゲームと同じセッション・表示を指定魚種で開始します。 */
    fun debugStart(player:org.bukkit.entity.Player,speciesId:String?):String {
        require(plugin.isManagedWorld(player.world)) { "ec.error.fishing_world" }
        val profile=plugin.profiles[player.uniqueId] ?: error("ec.error.fishing_profile")
        require(!active(player.uniqueId)) { "ec.error.fishing_active" }
        val definitions=plugin.snapshot.world
        val rod=plugin.identify(player.inventory.itemInMainHand)?.let { id -> definitions.tackle.values.firstOrNull { it.item==id && it.kind==TackleKind.ROD } }
            ?: definitions.tackle.values.firstOrNull { it.kind==TackleKind.ROD } ?: error("ec.error.fishing_rod")
        val fish=speciesId?.let { definitions.fishSpecies[it] ?: error("ec.error.fishing_species: $it") }
            ?: defaultDebugFish(definitions.fishSpecies.values,projectedSpots(),profile.lifeSkills.getValue(LifeSkillKind.FISHING).level,player.world.name)
            ?: error("ec.error.fishing_no_species")
        val spots=projectedSpots()
        val spot=spots.firstOrNull { it.world==player.world.name && (it.id in fish.habitats || it.area in fish.habitats) }
            ?: spots.firstOrNull { it.world==player.world.name } ?: error("ec.error.fishing_spot")
        require(sessions.size<capacity) { "ec.error.fishing_capacity" }
        val now=monotonicMillis(); val bobberAt=player.location.clone().add(0.0,1.0,0.0)
        val bobber=player.world.spawn(bobberAt,ItemDisplay::class.java) { display ->
            display.setItemStack(org.bukkit.inventory.ItemStack(Material.HEART_OF_THE_SEA)); display.isPersistent=false; display.isInvulnerable=true
            display.persistentDataContainer.set(bobberKey,PersistentDataType.STRING,player.uniqueId.toString())
        }
        val bar=Bukkit.createBossBar(plain(plugin.message(player,"ec.fishing.debug_bossbar", "fish" to fish.name)),BarColor.BLUE,BarStyle.SOLID).apply { addPlayer(player); progress=0.03 }
        val state=FishingState(fish,phase=FishingPhase.REELING,waitSeconds=0.0,tensionStability=rod.tensionStability,sweetSpotBonus=rod.sweetSpotBonus)
        sessions[player.uniqueId]=Session(state,spot,rod,player.location.clone(),bobberAt,bobber.uniqueId,bar)
        return plain(plugin.message(player,"ec.fishing.debug_started", "fish" to fish.name))
    }
    /** 次回の釣りで使う餌の設定を検証して保存します。 */
    fun selectBait(profile:PlayerProfile,value:String):String {
        val normalized=when(value.lowercase()) { "auto" -> "AUTO"; "none" -> "NONE"; else -> value }
        require(normalized in setOf("AUTO","NONE") || plugin.snapshot.world.tackle.values.any { it.item==normalized && it.kind==TackleKind.BAIT }) { "ec.error.fishing_bait" }
        profile.fishingBait=normalized
        return normalized
    }
    /** 釣りタスク、表示、セッション履歴を終了時に解放します。 */
    fun close() {
        task?.cancel(); task=null
        sessions.toMap().forEach { (id,session) ->
            logEnd(Bukkit.getPlayer(id)?.name ?: id.toString(),session.state.fish.id,session.state,FishingEndReason.WORLD_UNLOAD)
            cleanup(session)
        }
        sessions.clear(); catchHistory.clear(); disabledUntil.clear(); macroDetectors.clear()
    }
    /** コンテンツ変更に合わせて釣りタスクを再生成します。 */
    fun reload() {
        close()
        engine=FishingEngine(plugin.snapshot.world.lifeBalance,random)
        capacity=plugin.snapshot.world.lifeBalance.maxFishingSessions
            task=plugin.server.scheduler.runTaskTimer(plugin,Runnable { tick() },PaperTaskIntervals.FISHING_UPDATE_TICKS,PaperTaskIntervals.FISHING_UPDATE_TICKS)
    }
    private fun cleanup(session:Session) { session.bar.removeAll(); Bukkit.getEntity(session.bobber)?.remove() }
    private fun plain(component:Component):String = component.plainText()
    private fun cancel(player:org.bukkit.entity.Player,messageKey:String?=null,reason:FishingEndReason=FishingEndReason.CANCELLED):Boolean {
        val session=sessions.remove(player.uniqueId) ?: return false
        cleanup(session)
        logEnd(player.name,session.state.fish.id,session.state,reason)
        if(messageKey!=null && player.isOnline) player.sendMessage(plugin.message(player,messageKey))
        return true
    }

    /** バニラ釣果を止め、独自釣り状態機械だけを有効にします。 */
    @EventHandler(priority=EventPriority.LOWEST)
    fun suppressVanilla(e:PlayerFishEvent) {
        val customRod=plugin.identify(e.player.inventory.itemInMainHand)?.let { id -> plugin.snapshot.world.tackle.values.any { it.item==id && it.kind==TackleKind.ROD } }==true
        if(!plugin.activeWorldProjection.registry.isOverworldMode() && plugin.isManagedWorld(e.player.world) || customRod) e.isCancelled=true
    }
    /** 竿の右・左クリックを釣り開始またはミニゲーム入力へ変換します。 */
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false)
    fun interact(e:PlayerInteractEvent) {
        // Paperは空中クリックでブロック使用をDENYにするため、手のアイテム使用だけを確認します。
        if (!shouldProcessFishingInteract(e.action, e.useInteractedBlock(), e.useItemInHand(), e.hand)) return
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
                else -> cancel(player,"ec.fishing.cancel")
            }
            return
        }
        cast(player,rod)
    }
    private fun cast(player:org.bukkit.entity.Player,rod:TackleDefinition) {
        if(!plugin.isManagedWorld(player.world)) { player.sendMessage(plugin.message(player,"ec.fishing.world_only")); return }
        val profile=plugin.profiles[player.uniqueId] ?: return
        if(profile.classId==null || plugin.adventure.locked(profile.uuid)) { player.sendMessage(plugin.message(player,"ec.fishing.profile_loading")); return }
        val balance=plugin.snapshot.world.lifeBalance
        if(sessions.size>=capacity) { player.sendMessage(plugin.message(player,"ec.fishing.capacity")); return }
        if(profile.lifeSkills.getValue(LifeSkillKind.FISHING).level<rod.minLifeLevel) { player.sendMessage(plugin.message(player,"ec.fishing.level_low")); return }
        if(!castGate.accept(player.uniqueId,balance.castCooldownMillis)) { player.sendMessage(plugin.message(player,"ec.fishing.cooldown")); return }
        val eye=player.eyeLocation
        val hit=player.world.rayTraceBlocks(eye,eye.direction,balance.castRange,FluidCollisionMode.ALWAYS,true)?.hitBlock
        if(hit==null || !(hit.type==Material.WATER || hit.blockData is org.bukkit.block.data.Waterlogged && (hit.blockData as org.bukkit.block.data.Waterlogged).isWaterlogged) || !hit.getRelative(0,1,0).type.isAir) {
            player.sendMessage(plugin.message(player,"ec.fishing.aim_water")); return
        }
        val target=hit.location.clone().add(0.5,0.85,0.5)
        val distance=player.location.distance(target)
        if(distance !in PaperDistance.MIN_FISH_CAST_DISTANCE_BLOCKS..balance.castRange) { player.sendMessage(plugin.message(player,"ec.fishing.distance")); return }
        val spot=selectFishingSpot(projectedSpots(),player.world.name,target.x,target.z,profile.flags)
            ?: run { player.sendMessage(plugin.message(player,"ec.fishing.no_shadow")); return }
        val p=profile.lifeSkills.getValue(LifeSkillKind.FISHING)
        val bait=chooseBait(player,profile)
        val weather=when { player.world.isThundering -> "THUNDER"; player.world.hasStorm() -> "RAIN"; else -> "CLEAR" }
        val eligible=engine.eligible(plugin.snapshot.world.fishSpecies.values,spot,rod.tier,p.level,player.world.time,weather)
        val recent=catchHistory[player.uniqueId to spot.id]?.count { monotonicMillis()-it<MinecraftTicks.secondsToMillis(balance.fatigueWindowSeconds) } ?: 0
        if(monotonicMillis()<(disabledUntil[player.uniqueId to spot.id] ?: 0)) { player.sendMessage(plugin.message(player,"ec.fishing.tired")); return }
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
        val fish=engine.choose(weighted) ?: run { player.sendMessage(plugin.message(player,"ec.fishing.no_eligible_fish")); return }
        val wait=(FishingPhysics.MIN_CAST_WAIT_SECONDS+random.nextDouble()*FishingPhysics.CAST_WAIT_VARIANCE_SECONDS)*(1-(rod.waitReduction+(baitDef?.waitReduction ?: 0.0)).coerceAtMost(0.5))*(if(recent>=balance.fatigueSoftThreshold) FishingPhysics.FATIGUE_WAIT_MULTIPLIER else 1.0)
        val state=FishingState(fish,waitSeconds=wait,tensionStability=rod.tensionStability,sweetSpotBonus=rod.sweetSpotBonus)
        val bobber=player.world.spawn(target,ItemDisplay::class.java) { display ->
            display.setItemStack(org.bukkit.inventory.ItemStack(Material.HEART_OF_THE_SEA))
            display.isPersistent=false; display.isInvulnerable=true
            display.persistentDataContainer.set(bobberKey,PersistentDataType.STRING,player.uniqueId.toString())
        }
        val bar=Bukkit.createBossBar(plain(plugin.message(player,"ec.fishing.bossbar_search")),BarColor.BLUE,BarStyle.SOLID).apply { addPlayer(player); progress=0.03 }
        sessions[player.uniqueId]=Session(state,spot,rod,player.location.clone(),target,bobber.uniqueId,bar,bait=bait,baitCountBefore=bait?.let { plugin.inventory(player).count(it) } ?: 0,softThrottle=macro)
        detector.cast(monotonicMillis())
        player.playSound(target,org.bukkit.Sound.ENTITY_FISHING_BOBBER_SPLASH,0.5f,1.4f)
        player.sendActionBar(plugin.message(player,"ec.fishing.cast_started"))
    }
    private fun pulse(player:org.bukkit.entity.Player,session:Session,input:ReelInput) {
        val now=monotonicMillis()
        if(now-session.lastInput<FishingPhysics.MIN_INPUT_INTERVAL_MILLIS) return
        session.lastInput=now; session.input=input
        val detector=macroDetectors.getOrPut(player.uniqueId) { FishingMacroDetector() }
        detector.pull(now)
        val balance=plugin.snapshot.world.lifeBalance
        if(detector.suspicious(balance.macroMinSamples,balance.macroResponseDeviationMillis,balance.macroCastIntervalDeviationMillis) && !session.softThrottle) { session.softThrottle=true; player.sendMessage(plugin.message(player,"ec.fishing.macro_detected")) }
    }
    @EventHandler fun swing(e:PlayerAnimationEvent) {
        val s=sessions[e.player.uniqueId] ?: return
        if(s.state.phase==FishingPhase.REELING && e.animationType==org.bukkit.event.player.PlayerAnimationType.ARM_SWING) pulse(e.player,s,ReelInput.BRACE)
    }
    @EventHandler fun held(e:PlayerItemHeldEvent) { if(active(e.player.uniqueId)) cancel(e.player,"ec.fishing.rod_changed",FishingEndReason.ROD_CHANGED) }
    @EventHandler fun swap(e:PlayerSwapHandItemsEvent) { if(active(e.player.uniqueId)) cancel(e.player,"ec.fishing.hand_swap",FishingEndReason.CANCELLED) }
    @EventHandler fun quit(e:PlayerQuitEvent) { cancel(e.player,"ec.fishing.quit",FishingEndReason.QUIT) }
    @EventHandler fun death(e:PlayerDeathEvent) { cancel(e.entity,"ec.fishing.death",FishingEndReason.DEATH) }
    @EventHandler(ignoreCancelled=true) fun damage(e:EntityDamageEvent) { (e.entity as? org.bukkit.entity.Player)?.let { if(active(it.uniqueId)) cancel(it,"ec.fishing.damage",FishingEndReason.DAMAGE) } }
    @EventHandler fun moved(e:org.bukkit.event.player.PlayerMoveEvent) {
        val session=sessions[e.player.uniqueId] ?: return
        if(e.from.world!=e.to.world || !e.to.isWithinBlocks(session.castOrigin,PaperDistance.FISH_CAST_ORIGIN_BLOCKS) || !e.to.isWithinBlocks(session.water,PaperDistance.FISH_WATER_SPOT_BLOCKS)) cancel(e.player,"ec.fishing.line_left",FishingEndReason.LEFT_SPOT)
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
            plugin.adventure.transaction(player,{ inventory -> require(inventory.exchange(mapOf(bait to 1),emptyMap())) { "ec.error.fishing_bait_missing" } },success={
                if(sessions[player.uniqueId]===s) { s.bitePending=false; s.state.phase=FishingPhase.BITE_ALERT; showBite(player) }
            }, failure={ _ ->
                if(sessions[player.uniqueId]===s) cancel(player,"ec.fishing.bait_failed",FishingEndReason.CANCELLED)
            })
        } catch(error:Exception) {
            if(sessions[player.uniqueId]===s) cancel(player,"ec.fishing.bait_failed",FishingEndReason.CANCELLED)
        }
    }
    private fun chooseBait(player:org.bukkit.entity.Player,profile:PlayerProfile):String? {
        if(profile.fishingBait=="NONE") return null
        val inventory=plugin.inventory(player)
        if(profile.fishingBait!="AUTO") {
            if(inventory.count(profile.fishingBait)<=0) { player.sendMessage(plugin.message(player,"ec.fishing.selected_bait_missing")); return null }
            return profile.fishingBait
        }
        val baitItems=plugin.snapshot.world.tackle.values.filter { it.kind==TackleKind.BAIT }.associateBy { it.item }
        val offhand=plugin.identify(player.inventory.itemInOffHand)
        if(offhand in baitItems && inventory.count(offhand!!)>0) return offhand
        for(slot in PlayerInventoryLayout.HOTBAR_SLOT_RANGE) {
            val id=plugin.identify(player.inventory.getItem(slot))
            if(id in baitItems && inventory.count(id!!)>0) return id
        }
        return null
    }
    private fun showBite(player:org.bukkit.entity.Player) {
        macroDetectors.getOrPut(player.uniqueId) { FishingMacroDetector() }.bite(monotonicMillis())
        player.showTitle(Title.title(plugin.message(player,"ec.fishing.bite_title"),plugin.message(player,"ec.fishing.bite_subtitle")))
        player.playSound(player.location,org.bukkit.Sound.ENTITY_FISHING_BOBBER_SPLASH,1f,1.2f)
    }
    private fun tick() {
        castGate.cleanup(); val now=monotonicMillis()
        disabledUntil.entries.removeIf { it.value<=now }
        catchHistory.values.forEach { it.removeIf { ts -> now-ts>MinecraftTicks.secondsToMillis(plugin.snapshot.world.lifeBalance.fatigueWindowSeconds) } }
        sessions.toMap().forEach { (id,s) ->
            val player=Bukkit.getPlayer(id)
            if(player==null || !player.isOnline) {
                sessions.remove(id)?.let { logEnd(id.toString(),it.state.fish.id,it.state,FishingEndReason.QUIT); cleanup(it) }
                return@forEach
            }
            val stack=plugin.identify(player.inventory.itemInMainHand)
            if(stack!=s.rod.item || player.world.name!=s.water.world.name) { cancel(player,"ec.fishing.rod_changed",FishingEndReason.ROD_CHANGED); return@forEach }
            if(plugin.adventure.locked(id)) return@forEach
            val phaseBefore=s.state.phase
            // 餌消費の完了・失敗callbackだけが保留中フラグを解除します。
            if (!shouldAdvanceFishingSession(s.bitePending)) return@forEach
            val buffered=s.input; s.input=ReelInput.IDLE
            val input=when {
                buffered==ReelInput.BRACE -> { s.pullRemaining=0.0; ReelInput.BRACE }
                buffered==ReelInput.PULL -> { s.pullRemaining=pullBufferSeconds; ReelInput.PULL }
                s.pullRemaining>0.0 -> { s.pullRemaining=(s.pullRemaining-FishingStepTuning.SECONDS_PER_UPDATE).coerceAtLeast(0.0); ReelInput.PULL }
                else -> ReelInput.IDLE
            }
            val phase=engine.tick(s.state,FishingStepTuning.SECONDS_PER_UPDATE,input)
            if(phase==FishingPhase.BITE_ALERT && phaseBefore==FishingPhase.WAITING) beginBite(player,s)
            if(phase!=phaseBefore) {
                when(phase) {
                    FishingPhase.CAUGHT -> reward(player,s)
                    FishingPhase.ESCAPED -> {
                        val cause=s.state.escapeCause
                        val (titleKey,subtitleKey)=fishingEscapeTitle(cause)
                        val messageKey=fishingEscapeMessage(cause)
                        cancel(player,messageKey,when(cause) {
                            FishingEscapeCause.TIMEOUT -> FishingEndReason.TIMEOUT
                            FishingEscapeCause.OVER_TENSION -> FishingEndReason.LINE_SNAP
                            FishingEscapeCause.LINE_SLACK -> FishingEndReason.LINE_SLACK
                            FishingEscapeCause.BITE_MISSED -> FishingEndReason.ESCAPED_BITE
                            null -> FishingEndReason.CANCELLED
                        })
        // 他イベントがチャットやActionBarを更新しても、終了理由が必ず一度は見えるようにします。
                        player.showTitle(Title.title(plugin.message(player,titleKey),plugin.message(player,subtitleKey)))
                    }
                    else -> Unit
                }
            }
            if(sessions[id]===s) {
                val behaviorKey=when(s.state.behavior) { FishBehavior.CALM -> "ec.fishing.behavior_calm"; FishBehavior.TELEGRAPH -> "ec.fishing.behavior_telegraph"; FishBehavior.THRASH -> "ec.fishing.behavior_thrash"; FishBehavior.DIVE -> "ec.fishing.behavior_dive" }
                val barTitle=if(s.state.phase==FishingPhase.REELING) plugin.message(player,"ec.fishing.bossbar_reeling","behavior" to plain(plugin.message(player,behaviorKey)),"progress" to s.state.progress.toInt()) else plugin.message(player,"ec.fishing.bossbar_search")
                s.bar.setTitle(plain(barTitle))
        s.bar.progress=(s.state.progress/FishingPhysics.MAX_PROGRESS).coerceIn(0.0,1.0)
                s.bar.color=when(s.state.behavior) { FishBehavior.DIVE -> BarColor.BLUE; FishBehavior.THRASH -> BarColor.RED; FishBehavior.TELEGRAPH -> BarColor.YELLOW; else -> BarColor.GREEN }
                val status=plugin.message(player,"ec.fishing.actionbar_status","tension" to s.state.tension.toInt(),"progress" to s.state.progress.toInt())
                player.sendActionBar(status.append(Component.space()).append(plugin.message(player,fishingReelAdvice(s.state.behavior,s.state.tension))))
            }
        }
    }
    private fun reward(player:org.bukkit.entity.Player,s:Session) {
        val profile=plugin.profiles[player.uniqueId] ?: run { cancel(player,"ec.fishing.profile_missing",FishingEndReason.REWARD_FAILED); return }
        val catch=engine.catch(s.state,s.rod.sizeBonus,profile.lifeSkills.getValue(LifeSkillKind.FISHING).level)
        cancel(player,"ec.fishing.catch_pending",FishingEndReason.CAUGHT)
        try {
            plugin.adventure.transaction(player,{ inventory ->
                require(inventory.exchange(emptyMap(),mapOf(catch.species.item to 1)))
                engine.record(profile,catch)
                LifeSkillEngine(plugin.snapshot.world.lifeBalance).grant(profile.lifeSkills.getValue(LifeSkillKind.FISHING),catch.species.lifeXp)
                plugin.snapshot.balance.grantXp(profile,catch.species.adventurerXp)
                plugin.game.quests.event(profile,ObjectiveKind.CATCH_FISH,catch.species.id,1,inventory)
                val score=engine.score(profile)
                plugin.snapshot.world.lifeBalance.fishingMilestones.filter { score>=it.threshold }.forEach { profile.flags.add(it.flag) }
                val key=player.uniqueId to s.spot.id
                val history=catchHistory.getOrPut(key) { ArrayDeque() }; history.addLast(monotonicMillis())
                val fatigue=plugin.snapshot.world.lifeBalance
                if(history.size>fatigue.fatigueHardThreshold) disabledUntil[key]=monotonicMillis()+MinecraftTicks.secondsToMillis(fatigue.fatigueCooldownSeconds)
            },success={
                plugin.syncTitleAdvancements(player,profile)
                val isNew=profile.fishRecords[catch.species.id]?.count==1
                val crownKey=when(catch.crown) { Crown.GOLD -> "ec.fishing.crown_gold"; Crown.SILVER -> "ec.fishing.crown_silver"; else -> null }
                var subtitle=Component.text("${catch.species.name} ${catch.sizeCm}cm")
                crownKey?.let { subtitle=subtitle.append(Component.space()).append(plugin.message(player,it)) }
                if(isNew) subtitle=subtitle.append(Component.space()).append(plugin.message(player,"ec.fishing.new_record"))
                player.showTitle(Title.title(plugin.message(player,"ec.fishing.catch_title"),subtitle))
                player.playSound(player.location,org.bukkit.Sound.ENTITY_PLAYER_LEVELUP,0.5f,1.6f)
                plugin.markDirty(profile.uuid)
            })
        } catch(error:Exception) {
            plugin.logger.warning("FISH ${player.name}: ended reason=${FishingEndReason.REWARD_FAILED.name} fish=${catch.species.id} error=${error.message}")
            player.sendMessage(plugin.message(player,"ec.fishing.reward_failed"))
            player.showTitle(Title.title(plugin.message(player,"ec.fishing.title_reward_failed"),plugin.message(player,"ec.fishing.subtitle_inventory")))
        }
    }
}

/** 空中クリックのblock DENYは許容し、アイテム使用DENYとオフハンドだけを除外します。 */
internal fun shouldProcessFishingInteract(
    action: Action,
    blockUse: Event.Result,
    itemUse: Event.Result,
    hand: EquipmentSlot?,
): Boolean = itemUse != Event.Result.DENY &&
    hand == EquipmentSlot.HAND &&
    (action != Action.RIGHT_CLICK_BLOCK || blockUse != Event.Result.DENY)

/** 餌の永続化が終わるまで、釣り状態機械を二重進行させない判定です。 */
internal fun shouldAdvanceFishingSession(bitePending: Boolean): Boolean = !bitePending
