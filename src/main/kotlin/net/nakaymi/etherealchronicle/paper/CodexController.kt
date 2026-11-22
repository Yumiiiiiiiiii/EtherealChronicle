package net.nakaymi.etherealchronicle.paper

import net.nakaymi.etherealchronicle.core.Area
import net.nakaymi.etherealchronicle.core.PlayerProfile
import net.nakaymi.etherealchronicle.core.PercentageScale
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack

/** プレイヤーの討伐・収集記録を、ゲーム内図鑑画面へ描画します。 */
internal class CodexController(private val plugin: RpgPlugin) {
    private data class PageState(val key: String, val page: Int, val pageCount: Int)
    private val pageStates = mutableMapOf<java.util.UUID,PageState>()

    private fun areaCodexPercent(profile: net.nakaymi.etherealchronicle.core.PlayerProfile, area: Area): Pair<Int, Int> {
        val enemies = plugin.snapshot.world.enemiesInArea(area.id)
        val found = enemies.count { (profile.bestiary[it.id] ?: 0) > 0 }
        return found to enemies.size
    }
    private fun plain(component:Component):String = component.plainText()

    /** エリアごとの魔物発見状況を表示します。 */
    fun openCodex(player:Player) {
        val profile=plugin.profiles[player.uniqueId] ?: return
        val menu=RpgMenu("codex-areas","",title=plugin.message(player,"ec.menu.codex_areas")).inventory
        val page=page(player,"codex-areas","",plugin.snapshot.world.areas.values.sortedBy { it.name })
        page.items.forEachIndexed { slot,area ->
            val (found,total)=areaCodexPercent(profile,area)
            menu.setItem(slot,ItemStack(Material.FILLED_MAP).apply { itemMeta=itemMeta.apply {
                displayName(Component.text(area.name)); lore(listOf(plugin.message(player,"ec.codex.found_ratio","found" to found,"total" to total,"percent" to if(total==0) 0 else found*PercentageScale.MAX_PERCENT/total)))
            } })
        }
        codexFooter(player,menu,page,false)
        plugin.openCodexMenu(player, menu)
    }
    /** 指定エリアの魔物・討伐数・ドロップを表示します。 */
    fun openCodexArea(player:Player,areaId:String) {
        val profile=plugin.profiles[player.uniqueId] ?: return
        val area=plugin.snapshot.world.areas[areaId] ?: return openCodex(player)
        val menu=RpgMenu("codex-area",areaId,title=plugin.message(player,"ec.menu.codex_area")).inventory
        val page=page(player,"codex-area",areaId,plugin.snapshot.world.enemiesInArea(areaId))
        page.items.forEachIndexed { slot,enemy ->
            val count=profile.bestiary[enemy.id] ?: 0
            val discovered=count > 0
            val drops=plugin.snapshot.documents[enemy.id]?.get("droptable") as? String
            val dropNames=drops?.let(plugin.snapshot.drops::find)?.entries?.map { plugin.contentName(player,it.item) }.orEmpty()
            menu.setItem(slot,ItemStack(if(discovered) Material.BLAZE_POWDER else Material.GRAY_DYE).apply { itemMeta=itemMeta.apply {
                displayName(if(discovered) Component.text(enemy.name) else plugin.message(player,"ec.codex.undiscovered_enemy"))
                val weaknesses=enemy.weaknesses.joinToString { weakness -> plain(plugin.message(player, weaknessNameKey(weakness))) }
                    .ifEmpty { plain(plugin.message(player,"ec.codex.no_weakness")) }
                lore(if(discovered) listOf(plugin.message(player,"ec.codex.defeated_count","count" to count),plugin.message(player,"ec.codex.weaknesses","values" to weaknesses),plugin.message(player,"ec.codex.drops","values" to dropNames.joinToString())) else listOf(plugin.message(player,"ec.codex.search_area")))
            } })
        }
        codexFooter(player,menu,page,true)
        plugin.openCodexMenu(player, menu)
    }
    private fun weaknessNameKey(value: String): String = when (value.uppercase()) {
        "PHYSICAL" -> "ec.codex.element.physical"
        "FIRE" -> "ec.codex.element.fire"
        "ICE" -> "ec.codex.element.ice"
        "LIGHTNING" -> "ec.codex.element.lightning"
        "AETHER" -> "ec.codex.element.aether"
        "VOID" -> "ec.codex.element.void"
        else -> "ec.codex.element.unknown"
    }
    /** 発見済みアイテムを一覧表示します。 */
    fun openCodexItems(player:Player) {
        val profile=plugin.profiles[player.uniqueId] ?: return
        val menu=RpgMenu("codex-items","",title=plugin.message(player,"ec.menu.codex_items")).inventory
        val page=page(player,"codex-items","",profile.discoveredItems.sorted())
        page.items.forEachIndexed { slot,id ->
            val name=plugin.contentName(player,id)
            val lore=plugin.snapshot.documents[id]?.get("lore") as? String
            menu.setItem(slot,ItemStack(Material.PAPER).apply { itemMeta=itemMeta.apply {
                displayName(Component.text(name))
                lore(if(lore.isNullOrBlank()) null else listOf(Component.text(lore)))
            } })
        }
        codexFooter(player,menu,page,true)
        plugin.openCodexMenu(player, menu)
    }
    /** 魚種の発見数とサイズ記録を表示します。 */
    fun openCodexFish(player:Player) {
        val profile=plugin.profiles[player.uniqueId] ?: return
        val menu=RpgMenu("codex-fish","",title=plugin.message(player,"ec.menu.codex_fish")).inventory
        val page=page(player,"codex-fish","",plugin.snapshot.world.fishSpecies.values.sortedBy { it.name })
        page.items.forEachIndexed { slot,fish ->
            val record=profile.fishRecords[fish.id]; val discovered=record?.count?.let { it>0 }==true
            menu.setItem(slot,ItemStack(if(discovered) Material.COD else Material.GRAY_DYE).apply { itemMeta=itemMeta.apply {
                displayName(if(discovered) Component.text(fish.name) else plugin.message(player,"ec.codex.undiscovered_fish"))
                lore(if(record!=null) listOf(plugin.message(player,"ec.codex.catch_count","count" to record.count),plugin.message(player,"ec.codex.maximum_size","size" to record.maxCm,"crown" to record.crown),Component.text(fish.loreHint)) else listOf(plugin.message(player,"ec.codex.seek_fishing_spot")))
            } })
        }
        codexFooter(player,menu,page,true); plugin.openCodexMenu(player, menu)
    }
    /** 採取ノードごとの発見と採取回数を表示します。 */
    fun openCodexGathering(player:Player) {
        val profile=plugin.profiles[player.uniqueId] ?: return
        val menu=RpgMenu("codex-gathering","",title=plugin.message(player,"ec.menu.codex_gathering")).inventory
        val page=page(player,"codex-gathering","",plugin.snapshot.world.gatheringNodes.values.sortedBy { it.name })
        page.items.forEachIndexed { slot,node ->
            val count=profile.gatheringRecords[node.id] ?: 0
            menu.setItem(slot,ItemStack(if(count>0) Material.IRON_ORE else Material.GRAY_DYE).apply { itemMeta=itemMeta.apply {
                displayName(if(count>0) Component.text(node.name) else plugin.message(player,"ec.codex.undiscovered_gathering"))
                lore(if(count>0) listOf(plugin.message(player,"ec.codex.gathering_count","count" to count),plugin.message(player,"ec.codex.gathering_kind","kind" to node.kind,"tier" to node.minToolTier)) else listOf(plugin.message(player,"ec.codex.seek_gathering_node")))
            } })
        }
        codexFooter(player,menu,page,true); plugin.openCodexMenu(player, menu)
    }

    /** 前後ページ・カテゴリ切替・エリア一覧へ戻る操作を下段にまとめます。 */
    private fun codexFooter(player:Player,menu:Inventory,page:CodexPagination.Slice<*>,showBack:Boolean) {
        if(page.page>0) menu.setItem(CodexLayout.PREVIOUS_PAGE_SLOT,button(Material.ARROW,plugin.message(player,"ec.codex.previous_page")))
        if(showBack) menu.setItem(CodexLayout.BACK_SLOT,button(Material.ARROW,plugin.message(player,"ec.codex.back_areas")))
        menu.setItem(CodexLayout.FISH_TAB_SLOT,button(Material.FISHING_ROD,plugin.message(player,"ec.codex.tab_fish")))
        menu.setItem(CodexLayout.GATHERING_TAB_SLOT,button(Material.IRON_PICKAXE,plugin.message(player,"ec.codex.tab_gathering")))
        menu.setItem(CodexLayout.ITEM_TAB_SLOT,button(Material.BOOK,plugin.message(player,"ec.codex.tab_items")))
        menu.setItem(CodexLayout.PAGE_LABEL_SLOT,button(Material.PAPER,plugin.message(player,"ec.codex.page","page" to (page.page+1),"pages" to page.pageCount)))
        if(page.page+1<page.pageCount) menu.setItem(CodexLayout.NEXT_PAGE_SLOT,button(Material.ARROW,plugin.message(player,"ec.codex.next_page")))
    }

    private fun button(material:Material,label:Component)=ItemStack(material).apply {
        itemMeta=itemMeta.apply { displayName(label) }
    }

    private fun <T> page(player:Player,kind:String,contentId:String,items:List<T>):CodexPagination.Slice<T> {
        val key="$kind:$contentId"
        val requested=pageStates[player.uniqueId]?.takeIf { it.key==key }?.page ?: 0
        return CodexPagination.slice(items,requested).also { pageStates[player.uniqueId]=PageState(key,it.page,it.pageCount) }
    }

    /** 指定図鑑のページ番号を更新し、同じ一覧を再描画します。 */
    fun turnPage(player:Player,kind:String,contentId:String,direction:Int) {
        val state=pageStates[player.uniqueId] ?: return
        if(state.key!="$kind:$contentId") return
        val next=CodexPagination.move(state.page,direction,state.pageCount)
        if(next==state.page) return
        pageStates[player.uniqueId]=state.copy(page=next)
        when(kind) {
            "codex-areas" -> openCodex(player)
            "codex-area" -> openCodexArea(player,contentId)
            "codex-items" -> openCodexItems(player)
            "codex-fish" -> openCodexFish(player)
            "codex-gathering" -> openCodexGathering(player)
        }
    }

    /** 現在表示中のエリア一覧ページから、スロット位置をエリアIDへ解決します。 */
    fun openAreaFromSlot(player: Player, slot: Int) {
        val state = pageStates[player.uniqueId]?.takeIf { it.key == "codex-areas:" } ?: return
        val index = CodexPagination.itemIndex(state.page,slot) ?: return
        val area = plugin.snapshot.world.areas.values.sortedBy { it.name }.getOrNull(index) ?: return
        openCodexArea(player, area.id)
    }

    /** 切断時に図鑑の一時ページ状態を破棄します。 */
    fun clear(playerId:java.util.UUID) { pageStates.remove(playerId) }

}




