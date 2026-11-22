package net.nakaymi.etherealchronicle.paper

import java.util.UUID

/** Wave 3 のPaper境界にある表示・所有権判定を、副作用から分離して検証可能にします。 */
internal class ClassConfirmationTracker {
    private val confirming = mutableSetOf<UUID>()

    /** すでに確認画面を表示中ならfalseを返し、二重表示を防ぎます。 */
    fun begin(playerId: UUID): Boolean = confirming.add(playerId)

    /** 確認操作またはキャンセル後に状態を解除します。 */
    fun finish(playerId: UUID) { confirming.remove(playerId) }

    /** 確認Dialogが現在表示中か返します。 */
    fun isActive(playerId: UUID): Boolean = playerId in confirming
}

/** Paper DialogのmultiActionを選択肢のない定義で作らないよう判定します。 */
internal fun paperDialogueHasChoices(choiceCount: Int): Boolean = choiceCount > 0

/** instance枠で前回と異なるパレットが必要かを比較します。 */
internal fun dungeonPaletteNeedsBuild(previous: List<String>?, requested: List<String>): Boolean = previous != requested

/** 未ロード地点へNPCを生成してチャンクを強制ロードしないための判定です。 */
internal fun npcChunkMaySpawn(isLoaded: Boolean): Boolean = isLoaded

/** インスタンス対象外のプレイヤーへ範囲攻撃を漏らさないための判定です。 */
internal fun enemyAllowsPlayer(playerId: UUID, participants: Set<UUID>): Boolean = participants.isEmpty() || playerId in participants

/** 非表示条件の空きを詰め、表示slotと購入対象の対応を維持します。 */
internal fun <T> compactVisibleShopEntries(entries: List<T>, visible: (T) -> Boolean): List<T> = entries.filter(visible)
