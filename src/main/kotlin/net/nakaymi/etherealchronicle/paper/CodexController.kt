package net.nakaymi.etherealchronicle.paper

import net.nakaymi.etherealchronicle.core.Area
import net.nakaymi.etherealchronicle.core.PlayerProfile
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack

/** プレイヤーの討伐・収集記録を、ゲーム内図鑑画面へ描画します。 */
internal class CodexController(private val plugin: RpgPlugin) {
    private fun areaCodexPercent(profile: net.nakaymi.etherealchronicle.core.PlayerProfile, area: Area): Pair<Int, Int> {
        val enemies = plugin.snapshot.world.enemiesInArea(area.id)
        val found = enemies.count { (profile.bestiary[it.id] ?: 0) > 0 }
        return found to enemies.size
    }

    /** エリアごとの魔物発見状況を表示します。 */
    fun openCodex(player:Player) {
        val profile=plugin.profiles[player.uniqueId] ?: return
        val menu=RpgMenu("codex-areas","").inventory
        plugin.snapshot.world.areas.values.sortedBy { it.name }.take(45).forEachIndexed { slot,area ->
            val (found,total)=areaCodexPercent(profile,area)
            menu.setItem(slot,ItemStack(Material.FILLED_MAP).apply { itemMeta=itemMeta.apply {
                displayName(Component.text(area.name)); lore(listOf(Component.text("発見: $found/$total (${if(total==0) 0 else found*100/total}%)")))
            } })
        }
        codexTabs(menu)
        player.openInventory(menu)
    }
    /** 指定エリアの魔物・討伐数・ドロップを表示します。 */
    fun openCodexArea(player:Player,areaId:String) {
        val profile=plugin.profiles[player.uniqueId] ?: return
        val area=plugin.snapshot.world.areas[areaId] ?: return openCodex(player)
        val menu=RpgMenu("codex-area",areaId).inventory
        plugin.snapshot.world.enemiesInArea(areaId).take(45).forEachIndexed { slot,enemy ->
            val count=profile.bestiary[enemy.id] ?: 0
            val discovered=count > 0
            val drops=plugin.snapshot.documents[enemy.id]?.get("droptable") as? String
            val dropIds=drops?.let(plugin.snapshot.drops::find)?.entries?.map { it.item }.orEmpty()
            menu.setItem(slot,ItemStack(if(discovered) Material.BLAZE_POWDER else Material.GRAY_DYE).apply { itemMeta=itemMeta.apply {
                displayName(Component.text(if(discovered) enemy.name else "未発見の魔物"))
                lore(if(discovered) listOf(Component.text("討伐数: $count"),Component.text("弱点: ${enemy.weaknesses.joinToString().ifEmpty { "なし" }}"),Component.text("ドロップ: ${dropIds.joinToString()}")) else listOf(Component.text("このエリアで魔物を探しましょう。")))
            } })
        }
        menu.setItem(53,ItemStack(Material.ARROW).apply { itemMeta=itemMeta.apply { displayName(Component.text("エリア一覧へ戻る")) } }); codexTabs(menu)
        player.openInventory(menu)
    }
    /** 発見済みアイテムを一覧表示します。 */
    fun openCodexItems(player:Player) {
        val profile=plugin.profiles[player.uniqueId] ?: return
        val menu=RpgMenu("codex-items","").inventory
        profile.discoveredItems.sorted().take(45).forEachIndexed { slot,id ->
            val name=plugin.snapshot.documents[id]?.get("name") as? String ?: id
            menu.setItem(slot,ItemStack(Material.PAPER).apply { itemMeta=itemMeta.apply { displayName(Component.text(name)); lore(listOf(Component.text(id))) } })
        }
        menu.setItem(53,ItemStack(Material.ARROW).apply { itemMeta=itemMeta.apply { displayName(Component.text("エリア一覧へ戻る")) } }); codexTabs(menu)
        player.openInventory(menu)
    }
    private fun codexTabs(menu:Inventory) {
        val holder=menu.holder as? RpgMenu
        if(holder?.kind in setOf("codex-area","codex-items","codex-fish","codex-gathering")) menu.setItem(50,ItemStack(Material.ARROW).apply { itemMeta=itemMeta.apply { displayName(Component.text("エリア図鑑へ戻る")) } })
        menu.setItem(51,ItemStack(Material.FISHING_ROD).apply { itemMeta=itemMeta.apply { displayName(Component.text("魚類図鑑")) } })
        menu.setItem(52,ItemStack(Material.IRON_PICKAXE).apply { itemMeta=itemMeta.apply { displayName(Component.text("採取図鑑")) } })
        menu.setItem(53,ItemStack(Material.BOOK).apply { itemMeta=itemMeta.apply { displayName(Component.text("発見したアイテム")) } })
    }
    /** 魚種の発見数とサイズ記録を表示します。 */
    fun openCodexFish(player:Player) {
        val profile=plugin.profiles[player.uniqueId] ?: return
        val menu=RpgMenu("codex-fish","").inventory
        plugin.snapshot.world.fishSpecies.values.sortedBy { it.name }.take(45).forEachIndexed { slot,fish ->
            val record=profile.fishRecords[fish.id]; val discovered=record?.count?.let { it>0 }==true
            menu.setItem(slot,ItemStack(if(discovered) Material.COD else Material.GRAY_DYE).apply { itemMeta=itemMeta.apply {
                displayName(Component.text(if(discovered) fish.name else "未発見の魚"))
                lore(if(record!=null) listOf(Component.text("釣果: ${record.count}匹"),Component.text("最大: ${record.maxCm}cm / ${record.crown}"),Component.text(fish.loreHint)) else listOf(Component.text("釣り場で竿を出して探しましょう。")))
            } })
        }
        codexTabs(menu); player.openInventory(menu)
    }
    /** 採取ノードごとの発見と採取回数を表示します。 */
    fun openCodexGathering(player:Player) {
        val profile=plugin.profiles[player.uniqueId] ?: return
        val menu=RpgMenu("codex-gathering","").inventory
        plugin.snapshot.world.gatheringNodes.values.sortedBy { it.name }.take(45).forEachIndexed { slot,node ->
            val count=profile.gatheringRecords[node.id] ?: 0
            menu.setItem(slot,ItemStack(if(count>0) Material.IRON_ORE else Material.GRAY_DYE).apply { itemMeta=itemMeta.apply {
                displayName(Component.text(if(count>0) node.name else "未発見の採取地"))
                lore(if(count>0) listOf(Component.text("採取回数: $count"),Component.text("種別: ${node.kind} / 必要道具 T${node.minToolTier}")) else listOf(Component.text("専用道具で採取地を調べましょう。")))
            } })
        }
        codexTabs(menu); player.openInventory(menu)
    }

}




