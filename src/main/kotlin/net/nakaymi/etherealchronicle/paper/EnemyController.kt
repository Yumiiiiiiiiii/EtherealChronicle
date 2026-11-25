package net.nakaymi.etherealchronicle.paper

import net.nakaymi.etherealchronicle.core.*
import org.bukkit.*
import org.bukkit.attribute.Attribute
import org.bukkit.boss.BarColor
import net.kyori.adventure.text.Component
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.entity.*
import org.bukkit.event.*
import org.bukkit.event.entity.*
import org.bukkit.event.world.*
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import java.util.UUID
import kotlin.math.*

/** 敵AI・telegraphの固定係数を意図ごとにまとめます。 */
private object EnemyBehaviorTuning {
    const val SIMULATION_STEP_TICKS = PaperTaskIntervals.ENEMY_UPDATE_TICKS
    const val BASE_MOB_HEALTH = 20.0
    const val KNOCKBACK_RESISTANCE = 0.7
    const val FLEE_HP_RATIO = 0.25
    const val FLEE_CYCLE_TICKS = 80L
    const val FLEE_ACTIVE_TICKS = 40L
    const val MIN_MOVEMENT_DISTANCE_SQUARED = 0.0001
    const val FLEE_VERTICAL_SPEED = 0.1
    const val MIN_ATTACK_INTERVAL_TICKS = 10L
    const val FREEZE_SLOWNESS_AMPLIFIER = 10
    const val TELEGRAPH_PARTICLE_COUNT = 24
    val CONE_ANGLE_RADIANS = Math.PI / 2
    const val CONE_CENTER_OFFSET = 0.5
    const val TELEGRAPH_HEIGHT_BLOCKS = 0.15
    const val ATTACK_VERTICAL_TOLERANCE_BLOCKS = 4.0
    val ATTACK_FACING_HALF_ANGLE_RADIANS = Math.PI / 4
    const val POISON_EFFECT_TICKS = 60
    const val SLOW_EFFECT_TICKS = 40
    val CIRCLE_STEP_RADIANS = Math.PI / 12
    const val DUST_SIZE = 1.3f
    const val TAUNT_MAX_RADIUS_BLOCKS = 64.0
    const val TAUNT_MAX_DURATION_TICKS = 72_000
    const val TAUNT_MAX_THREAT_MULTIPLIER = 100.0

    val ELEMENT_COLORS = mapOf(
        "ICE" to Color.fromRGB(150, 235, 255),
        "VOID" to Color.fromRGB(185, 90, 255),
        "FIRE" to Color.fromRGB(255, 105, 35),
        "LIGHTNING" to Color.fromRGB(255, 235, 80),
        "AETHER" to Color.fromRGB(100, 255, 205),
    )
    val DEFAULT_TELEGRAPH_COLOR = Color.fromRGB(255, 65, 65)
}

/** YAML定義の魔物、virtual HP、脅威、特殊攻撃、出現上限を管理します。 */
class EnemyController(private val plugin: RpgPlugin,private val adventure: AdventureController): Listener {
    companion object {
        /** namespaced variant IDをPaperのWolf variantへ解決します。 */
        internal fun wolfVariantKey(variantId: String): NamespacedKey? = NamespacedKey.fromString(variantId)

        internal fun resolveWolfVariant(variantId: String): Wolf.Variant? {
            val key = wolfVariantKey(variantId) ?: return null
            return Registry.WOLF_VARIANT.get(key)
        }

        internal fun telegraphColor(element: String): Color = EnemyBehaviorTuning.ELEMENT_COLORS[element.uppercase()]
            ?: EnemyBehaviorTuning.DEFAULT_TELEGRAPH_COLOR
    }
    data class MobState(val entity: LivingEntity,val home: Location,val point: String,val boss: BossState,val participants: Set<UUID>,val bar: BossBarViews?,var target: UUID?=null,var nextAttack: Long=0,val specials: MutableMap<String,Long> = mutableMapOf(),val pinned: Boolean=false,val threat: MutableMap<UUID,Double> = mutableMapOf(),var tauntTarget: UUID?=null,var tauntUntil: Long=0)
    private data class Warning(val owner: UUID,val attack: SpecialAttack,val location: Location,val direction: org.bukkit.util.Vector,val due: Long)
    private val key=NamespacedKey(plugin,"enemy_id")
    private val betterModel=BetterModelBridge(plugin)
    private val states=mutableMapOf<UUID,MobState>()
    private val attackers=mutableMapOf<UUID,UUID>()
    private val budget=SpawnBudget { plugin.snapshot.world.tuning.enemyCap }
    private val nextSpawn=mutableMapOf<String,Long>()
    private val warnings=mutableListOf<Warning>()
    private val dealing=mutableMapOf<UUID,Double>()
    private val missingVariantWarnings=mutableSetOf<String>()
    private var tick=0L
    init { plugin.server.pluginManager.registerEvents(this,plugin) }
    fun state(id: UUID) = states[id]
    fun activeCount() = budget.total()
    fun killer(id: UUID): Player? = attackers[id]?.let(Bukkit::getPlayer)
    fun multiplier(id: UUID) = dealing[id] ?: states[id]?.boss?.damageScale(tick) ?: 1.0
    fun spawn(id: String,location: Location,point: String="admin:$id",cap: Int=1,participants: Set<UUID> = emptySet(),pinForSimulation: Boolean=false): LivingEntity? {
        val d=plugin.snapshot.world.enemies[id] ?: error("ec.error.enemy_missing: $id")
        if(budget.count(point) >= cap || budget.total() >= plugin.snapshot.world.tuning.enemyCap) return null
        val world=location.world ?: return null
        if(!plugin.isActiveWorld(world)) error("ec.error.world_owned_only")
        if(pinForSimulation) location.chunk.addPluginChunkTicket(plugin)
        val entity=world.spawnEntity(location,EntityType.valueOf(d.entity)) as LivingEntity
        if(!budget.reserve(entity.uniqueId,point,cap)) { entity.remove(); return null }
        entity.persistentDataContainer.set(key,PersistentDataType.STRING,id)
        d.visualVariant?.let { variantId ->
            if (entity is Wolf) {
                val variant = resolveWolfVariant(variantId)
                if (variant != null) entity.variant = variant
                else if (missingVariantWarnings.add(variantId)) plugin.logger.warning("Configured wolf variant is unavailable: $variantId")
            }
        }
        entity.customName(plugin.contentTranslatable(if(d.boss) "boss" else "enemy", d.id, "name", d.name)); entity.isCustomNameVisible=true
        if(entity is Slime) entity.size=d.size
        // Slimeサイズ設定後に表示用最大体力を再設定します。setSizeが属性値を変更するためです。
        entity.getAttribute(Attribute.MAX_HEALTH)?.baseValue=EnemyBehaviorTuning.BASE_MOB_HEALTH
        entity.health=requireNotNull(entity.getAttribute(Attribute.MAX_HEALTH)).value
        entity.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue=d.speed
        if("KNOCKBACK_RESIST" in d.traits) entity.getAttribute(Attribute.KNOCKBACK_RESISTANCE)?.baseValue=EnemyBehaviorTuning.KNOCKBACK_RESISTANCE
        if(entity is PiglinAbstract) entity.isImmuneToZombification=true
        entity.isPersistent=false
        if(pinForSimulation) { entity.removeWhenFarAway=false; entity.setAI(false) }
        val boss=BossState(d,participants.size.coerceAtLeast(1),tick)
        val bar=if(d.boss) BossBarViews() else null
        states[entity.uniqueId]=MobState(entity,location.clone(),point,boss,participants,bar,pinned=pinForSimulation)
        betterModel.attach(entity,id)
        return entity
    }
    fun tick() {
        tick+=EnemyBehaviorTuning.SIMULATION_STEP_TICKS
        states.values.toList().forEach { state ->
            val entity=state.entity
            if(entity.isDead) { remove(entity.uniqueId,false); return@forEach }
            if(!entity.isValid) return@forEach
            if(state.pinned) return@forEach // console検証対象はプレイヤー不在でもvirtual HPを保ちます。
            val d=state.boss.definition
            val eligible=entity.world.players.filter { p -> p.isOnline && !p.isDead &&
                !plugin.resourcePacks.isAwaitingRequiredPack(p.uniqueId) &&
                p.gameMode !in setOf(GameMode.CREATIVE,GameMode.SPECTATOR) && !isSafe(p) && plugin.profiles[p.uniqueId]?.classId != null &&
                (state.participants.isEmpty() || p.uniqueId in state.participants) && p.location.isWithinBlocks(entity.location,d.aggro) }
            if(tick >= state.tauntUntil) state.tauntTarget=null
            val target=eligible.firstOrNull { it.uniqueId == state.tauntTarget }
                ?: eligible.maxWithOrNull(compareBy<Player> { state.threat[it.uniqueId] ?: 0.0 }.thenByDescending { -it.location.distanceSquared(entity.location) })
                    ?.takeIf { (state.threat[it.uniqueId] ?: 0.0) > 0 }
                ?: eligible.minByOrNull { it.location.distanceSquared(entity.location) }
            state.bar?.let { bar ->
                val phase=d.phases.indexOf(state.boss.phase()).coerceAtLeast(0)+1
                val enraged=tick-state.boss.started >= d.enrageTicks
                // 視聴者ごとの言語でボス名・フェーズ・激昂表示を組み立てます（Adventure BossBar）。
                bar.sync(eligible,(state.boss.hp/state.boss.maximumHp).coerceIn(0.0,1.0).toFloat()) { viewer ->
                    plugin.message(viewer,"ec.boss.bossbar_title","name" to plugin.contentText(viewer,"boss",d.id,"name",d.name),
                        "hp" to state.boss.hp.toInt(),"max_hp" to state.boss.maximumHp.toInt(),"phase" to phase,
                        "enrage" to if(enraged) plugin.message(viewer,"ec.boss.bossbar_enrage") else Component.empty())
                }
            }
            if(!entity.location.isWithinBlocks(state.home, d.leash)) {
                entity.teleport(state.home); state.boss.hp=state.boss.maximumHp; state.target=null; if(entity is Mob) entity.target=null
                warnings.removeIf { it.owner == entity.uniqueId }; return@forEach
            }
            if(target == null) { if(entity is Mob) entity.target=null; return@forEach }
            state.target=target.uniqueId
            if(entity is Mob) entity.target=target
            if("FLEE" in d.traits && state.boss.hp/state.boss.maximumHp < EnemyBehaviorTuning.FLEE_HP_RATIO && tick%EnemyBehaviorTuning.FLEE_CYCLE_TICKS < EnemyBehaviorTuning.FLEE_ACTIVE_TICKS) {
                val away=entity.location.toVector().subtract(target.location.toVector())
                if(away.lengthSquared() > EnemyBehaviorTuning.MIN_MOVEMENT_DISTANCE_SQUARED) entity.velocity=away.normalize().multiply(d.speed).setY(EnemyBehaviorTuning.FLEE_VERTICAL_SPEED)
            }
            if(entity.location.distanceSquared(target.location) <= CombatGeometry.MELEE_ATTACK_DISTANCE_SQUARED && tick >= state.nextAttack) {
                attack(state,target,1.0)
                state.nextAttack=tick+(d.attackInterval*(state.boss.phase()?.interval ?: 1.0)).toLong().coerceAtLeast(EnemyBehaviorTuning.MIN_ATTACK_INTERVAL_TICKS)
            }
            for(special in d.specials) {
                val ratio=state.boss.hp/state.boss.maximumHp
                if(ratio !in special.minHp..special.maxHp || tick < state.specials.getOrDefault(special.id,0)) continue
                val location=if(special.shape == "CIRCLE" || special.shape == "TARGET") target.location.clone() else entity.location.clone()
                val direction=target.location.toVector().subtract(entity.location.toVector()).setY(0.0)
                if(direction.lengthSquared() > 0) direction.normalize()
                warnings += Warning(entity.uniqueId,special,location,direction,tick+special.telegraph)
                state.specials[special.id]=tick+special.cooldown
                val element=special.element ?: d.element
                val cue=when(element) { "ICE" -> "ec.boss.telegraph_ice"; "VOID" -> "ec.boss.telegraph_void"; else -> "ec.boss.telegraph_generic" }
                target.sendMessage(plugin.message(target,"ec.boss.telegraph", "attack" to plugin.contentText(target,if(d.boss) "boss" else "enemy",d.id,"special.${special.id}.name",special.name), "cue" to plugin.message(target,cue)))
                    break // 1 scheduler stepにつきtelegraphを1つだけ開始します。
            }
        }
        warnings.toList().forEach { warning ->
            val state=states[warning.owner]
            if(state == null || !state.entity.isValid) { warnings.remove(warning); return@forEach }
            draw(warning)
            if(tick >= warning.due) {
                warning.location.world!!.players.filter { !it.isDead &&
                    !plugin.resourcePacks.isAwaitingRequiredPack(it.uniqueId) &&
                    it.gameMode !in setOf(GameMode.CREATIVE,GameMode.SPECTATOR) && !isSafe(it) &&
                    (state.participants.isEmpty() || it.uniqueId in state.participants) && hits(warning,it.location)
                }.forEach { p ->
                    attack(state,p,warning.attack.multiplier)
                    applySpecialEffect(p,warning.attack)
                }
                warnings.remove(warning)
            }
        }
        plugin.snapshot.world.enemies.values.forEach { d -> d.spawns.forEach { point ->
            val id="${d.id}/${point.id}"
            val position=plugin.projectedPosition(point.position) ?: return@forEach
            val world=Bukkit.getWorld(position.world) ?: return@forEach
            val chunk=ChunkCoordinates.fromPosition(position.x,position.z)
            if(!plugin.isActiveWorld(world) || tick < nextSpawn.getOrDefault(id,0) || !world.isChunkLoaded(chunk.x,chunk.z)) return@forEach
            if(!WorldSchedule.matches(point.timeWindows,point.weather,world.time,world.hasStorm(),world.isThundering)) return@forEach
            val spawnLocation=Location(world,position.x,position.y,position.z)
            if(world.players.none { it.location.isWithinBlocks(spawnLocation,PaperDistance.ENEMY_SPAWN_VISIBILITY_BLOCKS) }) return@forEach
            if(budget.count(id) < point.cap) spawn(d.id,spawnLocation,id,point.cap)
            nextSpawn[id]=tick+point.respawnTicks
        } }
    }
    private fun isSafe(player: Player) = plugin.safeAreas(player.world.name).any { it.contains(Position(player.world.name,player.x,player.y,player.z)) }
    private fun attack(state: MobState,player: Player,multiplier: Double) {
        dealing[state.entity.uniqueId]=multiplier*state.boss.damageScale(tick)
        try { player.damage(state.boss.definition.attack*multiplier,state.entity) } finally { dealing.remove(state.entity.uniqueId) }
            if("POISON" in state.boss.definition.traits) player.addPotionEffect(PotionEffect(PotionEffectType.POISON,EnemyBehaviorTuning.POISON_EFFECT_TICKS,0))
            if("SLOW" in state.boss.definition.traits) player.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS,EnemyBehaviorTuning.SLOW_EFFECT_TICKS,0))
    }
    private fun hits(w: Warning,location: Location): Boolean {
            if(location.world != w.location.world || abs(location.y-w.location.y) > EnemyBehaviorTuning.ATTACK_VERTICAL_TOLERANCE_BLOCKS) return false
        val delta=location.toVector().subtract(w.location.toVector()).setY(0.0)
        return when(w.attack.shape) {
            "CIRCLE","TARGET" -> delta.lengthSquared() <= w.attack.radius*w.attack.radius
            "LINE" -> { val forward=delta.dot(w.direction); forward in 0.0..w.attack.range && delta.clone().subtract(w.direction.clone().multiply(forward)).lengthSquared() <= w.attack.radius*w.attack.radius }
            else -> delta.lengthSquared() <= w.attack.range*w.attack.range && (delta.lengthSquared() == 0.0 || delta.normalize().dot(w.direction) >= cos(EnemyBehaviorTuning.ATTACK_FACING_HALF_ANGLE_RADIANS))
        }
    }
    private fun draw(w: Warning) {
        val world=w.location.world ?: return
        val element=w.attack.element ?: states[w.owner]?.boss?.definition?.element ?: "PHYSICAL"
        val dust=Particle.DustOptions(telegraphColor(element),EnemyBehaviorTuning.DUST_SIZE)
        for(i in 0 until EnemyBehaviorTuning.TELEGRAPH_PARTICLE_COUNT) {
            val point=when(w.attack.shape) {
                "LINE" -> w.location.clone().add(w.direction.clone().multiply(w.attack.range*i/EnemyBehaviorTuning.TELEGRAPH_PARTICLE_COUNT))
                "CONE" -> w.location.clone().add(w.direction.clone().rotateAroundY((i/(EnemyBehaviorTuning.TELEGRAPH_PARTICLE_COUNT-1.0)-EnemyBehaviorTuning.CONE_CENTER_OFFSET)*EnemyBehaviorTuning.CONE_ANGLE_RADIANS).multiply(w.attack.range))
                else -> w.location.clone().add(cos(i*EnemyBehaviorTuning.CIRCLE_STEP_RADIANS)*w.attack.radius,EnemyBehaviorTuning.TELEGRAPH_HEIGHT_BLOCKS,sin(i*EnemyBehaviorTuning.CIRCLE_STEP_RADIANS)*w.attack.radius)
            }
            world.spawnParticle(Particle.DUST,point,1,0.0,0.0,0.0,0.0,dust)
        }
    }
    internal fun applySpecialEffect(player: Player,attack: SpecialAttack) {
        when(attack.effect) {
            "SLOW" -> player.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS,attack.effectDuration,0))
            "POISON" -> player.addPotionEffect(PotionEffect(PotionEffectType.POISON,attack.effectDuration,0))
            "KNOCKUP" -> player.velocity=player.velocity.setY(0.6)
            "FREEZE" -> {
                player.freezeTicks=maxOf(player.freezeTicks,attack.effectDuration)
                player.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS,attack.effectDuration,EnemyBehaviorTuning.FREEZE_SLOWNESS_AMPLIFIER,true,true,true))
            }
        }
    }
    /** 近距離の管理対象魔物へ期限付き挑発を付けます。全ワールドentity走査は行いません。 */
    fun taunt(player: Player,radius: Double,ticks: Int,threatMultiplier: Double): Int {
        require(radius in 0.1..EnemyBehaviorTuning.TAUNT_MAX_RADIUS_BLOCKS && ticks in 1..EnemyBehaviorTuning.TAUNT_MAX_DURATION_TICKS && threatMultiplier in 1.0..EnemyBehaviorTuning.TAUNT_MAX_THREAT_MULTIPLIER)
        var affected=0
        states.values.forEach { state ->
            if(state.entity.world != player.world || !state.entity.location.isWithinBlocks(player.location, radius) ||
                state.participants.isNotEmpty() && player.uniqueId !in state.participants) return@forEach
            val highest=state.threat.values.maxOrNull() ?: 1.0
            state.threat[player.uniqueId]=maxOf(state.threat[player.uniqueId] ?: 0.0,highest*threatMultiplier)
            state.tauntTarget=player.uniqueId; state.tauntUntil=maxOf(state.tauntUntil,tick+ticks)
            if(state.entity is Mob) state.entity.target=player
            affected++
        }
        return affected
    }
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) fun vanillaAttack(e: EntityDamageByEntityEvent) {
        val owner=(e.damager as? Projectile)?.shooter as? Entity ?: e.damager
        if(owner.uniqueId in states && owner.uniqueId !in dealing) e.isCancelled=true
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) fun virtualDamage(e: EntityDamageEvent) {
        val state=states[e.entity.uniqueId] ?: return
        e.isCancelled=true
        val attacker=(e as? EntityDamageByEntityEvent)?.damager?.let { it as? Player ?: ((it as? Projectile)?.shooter as? Player) }
        if(attacker == null || state.participants.isNotEmpty() && attacker.uniqueId !in state.participants) return
        if(e.finalDamage <= 0) return
        plugin.profiles[attacker.uniqueId]?.let { plugin.game.onHit(it,plugin.effectiveStats(attacker,it).resource); plugin.markDirty(it.uuid) }
        if("PACK" in state.boss.definition.traits) states.values.filter { it.boss.definition.id == state.boss.definition.id && it.entity.world == state.entity.world && it.entity.location.isWithinBlocks(state.entity.location,PaperDistance.ENEMY_PACK_ASSIST_BLOCKS) }.forEach { if(it.entity is Mob) it.entity.target=attacker }
        applyVirtualDamage(state.entity.uniqueId,attacker.uniqueId,e.finalDamage)
        if(!state.entity.isDead) plugin.procEffects(attacker,state.entity)
    }
    /** console simulationと実ダメージイベントが共有するvirtual HP・討伐経路です。 */
    fun applyVirtualDamage(entity: UUID,attacker: UUID,amount: Double): Boolean {
        val state=states[entity] ?: return false
        require(amount.isFinite() && amount > 0)
        if(state.participants.isNotEmpty() && attacker !in state.participants) return false
        attackers[entity]=attacker
        state.threat[attacker]=state.threat.getOrDefault(attacker,0.0)+amount
        state.boss.hp=(state.boss.hp-amount.coerceAtMost(plugin.snapshot.balance.damageCap)).coerceAtLeast(0.0)
        val killed=state.boss.hp <= 0
        val liveMaximum=requireNotNull(state.entity.getAttribute(Attribute.MAX_HEALTH)).value
        state.entity.health=displayedHealth(state.boss.hp,state.boss.maximumHp,liveMaximum)
        return killed
    }
    @EventHandler(priority=EventPriority.MONITOR) fun death(e: EntityDeathEvent) { remove(e.entity.uniqueId,false) }
    @EventHandler fun removed(e: EntityRemoveEvent) { remove(e.entity.uniqueId,false) }
    @EventHandler(ignoreCancelled=true) fun transform(e: EntityTransformEvent) { if(e.entity.uniqueId in states) e.isCancelled=true }
    @EventHandler(ignoreCancelled=true) fun portal(e: EntityPortalEvent) { if(e.entity.uniqueId in states) e.isCancelled=true }
    @EventHandler fun unload(e: ChunkUnloadEvent) { e.chunk.entities.filter { it.uniqueId in states }.forEach { remove(it.uniqueId,true) } }
    @EventHandler fun load(e: ChunkLoadEvent) { e.chunk.entities.filter { it.persistentDataContainer.has(key) && it.uniqueId !in states }.forEach(Entity::remove) }
    private fun remove(id: UUID,removeEntity: Boolean) {
        val state=states.remove(id) ?: return
        betterModel.detach(id)
        state.bar?.clear(); if(removeEntity) state.entity.remove(); budget.release(id); attackers.remove(id); warnings.removeIf { it.owner == id }
        if(state.pinned && states.values.none { it.pinned && it.home.chunk == state.home.chunk }) state.home.chunk.removePluginChunkTicket(plugin)
    }
    fun removeSimulation(participant: UUID) { states.filterValues { it.pinned && participant in it.participants }.keys.toList().forEach { remove(it,true) } }
    fun removeParticipants(participants: Set<UUID>) { states.filterValues { it.participants == participants }.keys.toList().forEach { remove(it,true) } }
    /** フィールド敵だけを撤去し、ダンジョンや管理者による敵は維持します。 */
    fun refreshWorldAnchors(changedAreaIds: Set<String>? = null) {
        if (changedAreaIds == null) {
            val fieldPoints = nextSpawn.keys.toSet() + plugin.snapshot.world.enemies.values.flatMap { enemy -> enemy.spawns.map { "${enemy.id}/${it.id}" } }
            states.filterValues { it.point in fieldPoints }.keys.toList().forEach { remove(it,true) }
            nextSpawn.clear()
            return
        }
        if (changedAreaIds.isEmpty()) return
        val projector = plugin.activeWorldProjection.projector
        val allKnownPoints = mutableSetOf<String>()
        val affectedPoints = mutableSetOf<String>()
        plugin.snapshot.world.enemies.values.forEach { enemy ->
            enemy.spawns.forEach { spawn ->
                val pointId = "${enemy.id}/${spawn.id}"
                allKnownPoints += pointId
                val areaId = projector.sourceAreaId(spawn.position)
                if (areaId == null || areaId in changedAreaIds) affectedPoints += pointId
            }
        }
        nextSpawn.keys.filter { it !in allKnownPoints }.forEach { affectedPoints += it }
        states.filterValues { it.point in affectedPoints }.keys.toList().forEach { remove(it,true) }
        affectedPoints.forEach(nextSpawn::remove)
    }
    fun close() { states.keys.toList().forEach { remove(it,true) }; betterModel.close() }
}



