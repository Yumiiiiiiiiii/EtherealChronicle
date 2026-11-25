package net.nakaymi.etherealchronicle.paper

import net.nakaymi.etherealchronicle.core.EnemyDefinition
import net.nakaymi.etherealchronicle.core.SkillDefinition
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.entity.Display
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.util.Vector
import java.util.UUID
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** 攻撃命中時の視覚・聴覚フィードバック、浮遊ダメージ表示、通常敵ターゲットHPバーを管理します。 */
internal class CombatFeedback(private val plugin: RpgPlugin) {
    private data class TargetBarEntry(val bar: BossBar, var expiresAtMillis: Long)
    private data class FloatingIndicator(val entity: TextDisplay, var remainingTicks: Int)

    private val targetBars = mutableMapOf<UUID, TargetBarEntry>()
    private val indicators = mutableListOf<FloatingIndicator>()

    /** プレイヤーの通常攻撃またはスキル攻撃が敵へ命中した際の演出を再生します。 */
    fun recordHit(
        attacker: Player,
        target: LivingEntity,
        enemyDef: EnemyDefinition?,
        remainingHp: Double,
        maxHp: Double,
        damage: Double,
        critical: Boolean,
        element: String,
        elementMod: Double,
        isSkill: Boolean,
        killed: Boolean,
    ) {
        val hitLoc = target.location.clone().add(0.0, (target.height * 0.65).coerceIn(0.5, 2.2), 0.0)

        // 1. Vanillaの被弾赤フラッシュを復元（virtualDamageでキャンセルされているため）
        runCatching { target.playHurtAnimation(attacker.location.yaw) }

        // 2. 軽いノックバック（KNOCKBACK_RESIST持ちや討伐時は抑止）
        if (!killed && (enemyDef == null || "KNOCKBACK_RESIST" !in enemyDef.traits)) {
            val push = target.location.toVector().subtract(attacker.location.toVector()).setY(0.0)
            if (push.lengthSquared() > 0.0001) {
                val strength = if (isSkill) 0.28 else 0.22
                val impulse = push.normalize().multiply(strength).setY(0.10)
                runCatching { target.velocity = target.velocity.add(impulse) }
            }
        }

        // 3. 攻撃者・周囲への効果音
        runCatching {
            when {
                critical -> attacker.playSound(attacker.location, Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.85f, 1.15f)
                elementMod > 1.0 -> {
                    attacker.playSound(attacker.location, Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.8f, 1.15f)
                    attacker.playSound(attacker.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.55f, 1.55f)
                }
                elementMod < 1.0 -> attacker.playSound(attacker.location, Sound.BLOCK_ANVIL_LAND, 0.25f, 1.7f)
                isSkill -> attacker.playSound(attacker.location, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.8f, 1.1f)
                else -> attacker.playSound(attacker.location, Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.75f, 1.0f)
            }
            target.world.playSound(hitLoc, Sound.ENTITY_GENERIC_HURT, 0.65f, if (killed) 0.85f else 1.0f)
            if (killed) {
                attacker.playSound(attacker.location, Sound.ENTITY_ARROW_HIT_PLAYER, 0.6f, 1.45f)
            }
        }

        // 4. ヒットパーティクル
        runCatching {
            val world = target.world
            world.spawnParticle(Particle.DAMAGE_INDICATOR, hitLoc, if (critical) 6 else 3, 0.25, 0.25, 0.25, 0.05)
            if (critical) {
                world.spawnParticle(Particle.CRIT, hitLoc, 10, 0.35, 0.35, 0.35, 0.12)
            }
            if (!element.equals("PHYSICAL", ignoreCase = true)) {
                val dust = Particle.DustOptions(EnemyController.telegraphColor(element), 1.15f)
                world.spawnParticle(Particle.DUST, hitLoc, 8, 0.35, 0.35, 0.35, 0.0, dust)
            }
            if (killed) {
                world.spawnParticle(Particle.POOF, hitLoc, 8, 0.3, 0.3, 0.3, 0.04)
            }
        }

        // 5. 浮遊ダメージ数値（実PaperサーバーでのみTextDisplayを生成）
        if (plugin.paperUiEnabled) {
            spawnDamageIndicator(attacker, target, damage, critical, elementMod)
        }

        // 6. 非ボス敵のターゲットHPバー更新（ボスは専用BossBarを持つため重複させない）
        if (enemyDef == null || !enemyDef.boss) {
            val enemyName = enemyDef?.let {
                plugin.contentText(attacker, if (it.boss) "boss" else "enemy", it.id, "name", it.name)
            } ?: target.name
            updateTargetBar(attacker, enemyName, remainingHp, maxHp, killed)
        }
    }

    /** スキル発動時の視覚・聴覚エフェクトを再生します。 */
    fun recordSkillCast(caster: Player, skill: SkillDefinition, target: LivingEntity?) {
        runCatching {
            val sound = when {
                skill.heal > 0 || skill.defenseBuff > 0 -> Sound.ENTITY_EXPERIENCE_ORB_PICKUP
                skill.element.equals("FIRE", ignoreCase = true) -> Sound.ITEM_FIRECHARGE_USE
                skill.element.equals("ICE", ignoreCase = true) -> Sound.BLOCK_GLASS_BREAK
                skill.element.equals("LIGHTNING", ignoreCase = true) -> Sound.ENTITY_LIGHTNING_BOLT_IMPACT
                skill.element.equals("VOID", ignoreCase = true) -> Sound.ENTITY_ENDERMAN_TELEPORT
                skill.element.equals("AETHER", ignoreCase = true) -> Sound.BLOCK_BEACON_ACTIVATE
                else -> Sound.ENTITY_PLAYER_ATTACK_SWEEP
            }
            val volume = if (skill.element.equals("LIGHTNING", ignoreCase = true)) 0.35f else 0.7f
            caster.world.playSound(caster.location, sound, volume, 1.15f)
        }

        runCatching {
            val color = if (skill.heal > 0) Color.fromRGB(110, 245, 145) else EnemyController.telegraphColor(skill.element)
            val dust = Particle.DustOptions(color, 1.2f)
            val world = caster.world
            if (target != null && target.world == world) {
                drawBeam(world, caster.eyeLocation.clone().add(0.0, -0.25, 0.0), target.location.clone().add(0.0, target.height * 0.6, 0.0), dust)
                if (skill.radius > 0.0) {
                    drawRing(world, target.location.clone().add(0.0, 0.25, 0.0), skill.radius.coerceAtMost(8.0), dust)
                }
            } else {
                drawRing(world, caster.location.clone().add(0.0, 0.35, 0.0), 1.8, dust)
            }
        }
    }

    /** 弓・杖などの遠距離通常攻撃時の発射演出を再生します。 */
    fun recordRangedBasicShot(shooter: Player, targetPoint: Location, isBow: Boolean) {
        runCatching {
            val sound = if (isBow) Sound.ENTITY_ARROW_SHOOT else Sound.ENTITY_BLAZE_SHOOT
            shooter.world.playSound(shooter.location, sound, 0.7f, if (isBow) 1.15f else 1.4f)
            val start = shooter.eyeLocation.clone().add(0.0, -0.15, 0.0)
            val dust = Particle.DustOptions(
                if (isBow) Color.fromRGB(240, 235, 200) else Color.fromRGB(130, 220, 255),
                0.85f,
            )
            drawBeam(shooter.world, start, targetPoint, dust)
        }
    }

    /** 定期tickで期限切れのターゲットHPバーと浮遊ダメージ表示を片付けます。 */
    fun tick() {
        val now = System.currentTimeMillis()
        val expiredBars = targetBars.entries.filter { (_, entry) -> entry.expiresAtMillis <= now }.map { it.key }
        expiredBars.forEach { clearPlayer(it) }

        val iterator = indicators.iterator()
        while (iterator.hasNext()) {
            val indicator = iterator.next()
            if (!indicator.entity.isValid || indicator.remainingTicks <= 0) {
                runCatching { indicator.entity.remove() }
                iterator.remove()
            } else {
                indicator.remainingTicks--
                runCatching { indicator.entity.teleport(indicator.entity.location.add(0.0, 0.08, 0.0)) }
            }
        }
    }

    /** プレイヤー退出時にターゲットHPバーを解放します。 */
    fun clearPlayer(playerId: UUID) {
        targetBars.remove(playerId)?.bar?.removeAll()
    }

    /** プラグイン停止時に全BossBarと浮遊ダメージ表示を削除します。 */
    fun close() {
        targetBars.values.forEach { it.bar.removeAll() }
        targetBars.clear()
        indicators.forEach { runCatching { it.entity.remove() } }
        indicators.clear()
    }

    private fun spawnDamageIndicator(
        attacker: Player,
        target: LivingEntity,
        damage: Double,
        critical: Boolean,
        elementMod: Double,
    ) {
        if (indicators.size >= MAX_ACTIVE_INDICATORS) {
            indicators.removeFirstOrNull()?.let { runCatching { it.entity.remove() } }
        }
        val key = when {
            critical -> "ec.combat.hit_crit"
            elementMod > 1.0 -> "ec.combat.hit_weak"
            elementMod < 1.0 -> "ec.combat.hit_resist"
            else -> "ec.combat.hit_normal"
        }
        val text = plugin.message(attacker, key, "damage" to damage.roundToInt().coerceAtLeast(1))
        val jitterX = (Random.nextDouble() - 0.5) * 0.65
        val jitterZ = (Random.nextDouble() - 0.5) * 0.65
        val spawnLoc = target.location.clone().add(jitterX, (target.height + 0.35).coerceIn(1.1, 3.0), jitterZ)
        runCatching {
            val display = target.world.spawn(spawnLoc, TextDisplay::class.java) { td ->
                td.text(text)
                td.billboard = Display.Billboard.CENTER
                td.isShadowed = true
                td.isSeeThrough = false
                td.isPersistent = false
                td.isVisibleByDefault = false
                td.backgroundColor = Color.fromARGB(0, 0, 0, 0)
            }
            attacker.showEntity(plugin, display)
            indicators += FloatingIndicator(display, INDICATOR_LIFETIME_TICKS)
        }
    }

    private fun updateTargetBar(
        attacker: Player,
        enemyName: String,
        remainingHp: Double,
        maxHp: Double,
        killed: Boolean,
    ) {
        val ratio = if (maxHp > 0.0) (remainingHp / maxHp).coerceIn(0.0, 1.0) else 0.0
        val color = when {
            ratio > 0.5 -> BarColor.GREEN
            ratio > 0.2 -> BarColor.YELLOW
            else -> BarColor.RED
        }
        val title = plugin.message(
            attacker,
            "ec.combat.target_hp",
            "name" to enemyName,
            "hp" to remainingHp.roundToInt().coerceAtLeast(0),
            "max_hp" to maxHp.roundToInt().coerceAtLeast(1),
        ).plainText()

        val existing = targetBars[attacker.uniqueId]
        val bar = existing?.bar ?: Bukkit.createBossBar(title, color, BarStyle.SEGMENTED_10).also {
            it.addPlayer(attacker)
        }
        bar.setTitle(title)
        bar.color = color
        bar.progress = ratio
        if (attacker !in bar.players) bar.addPlayer(attacker)

        val ttl = if (killed) KILLED_BAR_TTL_MILLIS else TARGET_BAR_TTL_MILLIS
        targetBars[attacker.uniqueId] = TargetBarEntry(bar, System.currentTimeMillis() + ttl)
    }

    private fun drawBeam(world: org.bukkit.World, start: Location, end: Location, dust: Particle.DustOptions) {
        val delta: Vector = end.toVector().subtract(start.toVector())
        val dist = delta.length().coerceAtMost(24.0)
        if (dist < 0.2) return
        val steps = (dist * 2.5).toInt().coerceIn(3, 28)
        val stepVec = delta.normalize().multiply(dist / steps)
        val cursor = start.clone()
        for (i in 1..steps) {
            cursor.add(stepVec)
            world.spawnParticle(Particle.DUST, cursor, 1, 0.02, 0.02, 0.02, 0.0, dust)
        }
    }

    private fun drawRing(world: org.bukkit.World, center: Location, radius: Double, dust: Particle.DustOptions) {
        val points = 16
        for (i in 0 until points) {
            val angle = (2.0 * Math.PI * i) / points
            val p = center.clone().add(cos(angle) * radius, 0.0, sin(angle) * radius)
            world.spawnParticle(Particle.DUST, p, 1, 0.0, 0.0, 0.0, 0.0, dust)
        }
    }

    companion object {
        private const val TARGET_BAR_TTL_MILLIS = 3_500L
        private const val KILLED_BAR_TTL_MILLIS = 900L
        private const val INDICATOR_LIFETIME_TICKS = 4 // Called every 4 ticks (enemy tick) -> ~16 ticks total
        private const val MAX_ACTIVE_INDICATORS = 40
    }
}
