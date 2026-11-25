package net.nakaymi.etherealchronicle.paper

import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.UUID

/** ボスHPバー。視聴者ごとの言語でタイトルを描画するため、Adventure BossBarをプレイヤー単位で保持します。 */
class BossBarViews {
    private val bars = HashMap<UUID, BossBar>()

    /** 視聴者集合を同期し、各プレイヤーの言語のタイトルと共通の進捗を反映します。 */
    fun sync(viewers: Collection<Player>, progress: Float, title: (Player) -> Component) {
        val ids = viewers.mapTo(HashSet()) { it.uniqueId }
        bars.entries.removeIf { (id, bar) ->
            (id !in ids).also { gone -> if (gone) Bukkit.getPlayer(id)?.hideBossBar(bar) }
        }
        viewers.forEach { player ->
            val bar = bars.getOrPut(player.uniqueId) {
                BossBar.bossBar(title(player), progress, BossBar.Color.PURPLE, BossBar.Overlay.NOTCHED_10).also(player::showBossBar)
            }
            bar.name(title(player)); bar.progress(progress)
        }
    }

    /** 全視聴者からバーを外します。 */
    fun clear() {
        bars.forEach { (id, bar) -> Bukkit.getPlayer(id)?.hideBossBar(bar) }
        bars.clear()
    }

    /** 診断用: 現在の視聴者数。 */
    val viewerCount: Int get() = bars.size
}
