package net.nakaymi.etherealchronicle.core

import java.util.UUID

data class Party(val leader: UUID,val members: MutableSet<UUID>)
/** 招待期限、leader、参加上限、XP分配、周回loot順を管理します。 */
class PartyService(private val cap: () -> Int,private val clock: Clock) {
    private val membership=mutableMapOf<UUID,Party>()
    private val invites=mutableMapOf<UUID,Pair<UUID,Long>>()
    fun party(player: UUID) = membership[player]
    fun members(player: UUID): Set<UUID> = membership[player]?.members?.toSet() ?: setOf(player)
    fun invite(leader: UUID,target: UUID) {
        require(leader != target && target !in membership)
        val party=membership[leader]
        require(party == null || party.leader == leader)
        require((party?.members?.size ?: 1) < cap())
        invites[target]=leader to (clock.millis()+GameDurations.PARTY_INVITE_MILLIS)
    }
    fun accept(target: UUID) {
        val (leader,expires)=invites.remove(target) ?: error("ec.error.party_invite_missing")
        require(clock.millis() <= expires && target !in membership)
        val party=membership[leader] ?: Party(leader,mutableSetOf(leader)).also { membership[leader]=it }
        require(party.members.size < cap())
        party.members.add(target); membership[target]=party
    }
    fun leave(player: UUID) {
        val party=membership.remove(player) ?: return
        party.members.remove(player)
        if(party.leader == player || party.members.size <= 1) party.members.forEach { membership.remove(it) }
        invites.entries.removeIf { it.value.first == player || it.key == player }
    }
    fun splitXp(total: Long,eligible: Set<UUID>): Map<UUID,Long> {
        require(total >= 0)
        val sorted=eligible.sortedBy(UUID::toString)
        if(sorted.isEmpty()) return emptyMap()
        val share=total/sorted.size; val remainder=total%sorted.size
        return sorted.mapIndexed { i,id -> id to share+(if(i < remainder) 1 else 0) }.toMap()
    }
    fun lootRecipient(eligible: Set<UUID>,sequence: Long): UUID? {
        val sorted=eligible.sortedBy(UUID::toString)
        return if(sorted.isEmpty()) null else sorted[Math.floorMod(sequence,sorted.size.toLong()).toInt()]
    }
}
/** 出現地点ごとの上限とサーバー全体上限を同時に守る純粋な予約台帳です。 */
/** 出現個体の地点別・全体予約数を保持し、上限を超えるspawnを拒否します。 */
class SpawnBudget(private val globalCap: () -> Int) {
    private val owners=mutableMapOf<UUID,String>()
    fun reserve(entity: UUID,point: String,pointCap: Int): Boolean {
        if(entity in owners || owners.size >= globalCap() || owners.values.count { it == point } >= pointCap) return false
        owners[entity]=point; return true
    }
    fun release(entity: UUID) { owners.remove(entity) }
    fun count(point: String) = owners.values.count { it == point }
    fun total() = owners.size
}
/** 仮想体力と協力人数補正、フェーズ・enrage時間を管理します。 */
data class BossState(val definition: EnemyDefinition,val partySize: Int,val started: Long) {
    val maximumHp = definition.hp*(1+(partySize.coerceAtLeast(1)-1)*definition.coopHp)
    var hp = maximumHp
    fun phase() = definition.phases.lastOrNull { hp/maximumHp <= it.below }
    fun damageScale(tick: Long): Double = (1+(partySize.coerceAtLeast(1)-1)*definition.coopDamage)*(phase()?.damage ?: 1.0)*
        (if(tick-started >= definition.enrageTicks) definition.enrageMultiplier else 1.0)
}

/** Slimeの大きさ等でPaperが属性最大値を変えても、敵HPの正本は仮想HPです。 */
fun displayedHealth(hp: Double, virtualMaximum: Double, liveMaximum: Double): Double {
    require(virtualMaximum.isFinite() && virtualMaximum > 0 && liveMaximum.isFinite() && liveMaximum > 0)
    if(hp <= 0) return 0.0
    return (hp/virtualMaximum*liveMaximum).coerceIn(minOf(BossHealth.MIN_VISIBLE_HEALTH,liveMaximum),liveMaximum)
}

/** Paper側の体力表示で0除算や丸め消失を避ける最小値です。 */
object BossHealth {
    const val MIN_VISIBLE_HEALTH = 0.1
}
