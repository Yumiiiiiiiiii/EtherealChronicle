package net.nakaymi.etherealchronicle.core

import java.util.UUID

data class Party(val leader: UUID,val members: MutableSet<UUID>)
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
        invites[target]=leader to (clock.millis()+60_000)
    }
    fun accept(target: UUID) {
        val (leader,expires)=invites.remove(target) ?: error("招待がありません。")
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
/** Pure accounting prevents a spawn point and the whole server exceeding their caps. */
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
data class BossState(val definition: EnemyDefinition,val partySize: Int,val started: Long) {
    val maximumHp = definition.hp*(1+(partySize.coerceAtLeast(1)-1)*definition.coopHp)
    var hp = maximumHp
    fun phase() = definition.phases.lastOrNull { hp/maximumHp <= it.below }
    fun damageScale(tick: Long): Double = (1+(partySize.coerceAtLeast(1)-1)*definition.coopDamage)*(phase()?.damage ?: 1.0)*
        (if(tick-started >= definition.enrageTicks) definition.enrageMultiplier else 1.0)
}
