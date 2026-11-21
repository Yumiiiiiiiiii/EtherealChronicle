package net.nakaymi.etherealchronicle.paper

import net.nakaymi.etherealchronicle.core.*
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.*
import org.bukkit.entity.*
import org.bukkit.event.*
import org.bukkit.event.entity.*
import org.bukkit.event.inventory.*
import org.bukkit.event.player.*
import org.bukkit.inventory.*
import org.bukkit.persistence.PersistentDataType
import java.util.UUID

/** NPC会話、依頼、ショップ、パーティ、保留報酬、装備GUIをPaperへ接続します。 */
class AdventureController(private val plugin: RpgPlugin): Listener {
    val parties=PartyService({ plugin.snapshot.world.tuning.partyCap },Clock { System.currentTimeMillis() })
    private val dialogues=DialogueEngine { plugin.snapshot }
    private val trades=TradeEngine { plugin.snapshot }
    private val locks=mutableSetOf<UUID>()
    private val entryKey=NamespacedKey(plugin,"entry_id")
    private val mm=MiniMessage.miniMessage()
    private val npcs=NpcController(plugin)
    private val dialogueSessions=mutableMapOf<UUID,Pair<String,String>>()
    var enterDungeon: (Player,String) -> Unit = { p,id -> p.sendMessage(Component.text("ダンジョンを準備しています: $id")) }
    init { plugin.server.pluginManager.registerEvents(this,plugin) }
    /** 非同期保存を含むプロフィール取引中かを返します。 */
    fun locked(id: UUID) = id in locks
    /** 時刻・天候条件に応じたNPCの再読込を専用controllerへ委譲します。 */
    fun reloadNpcs() = npcs.reload()
    /** 管理対象NPCのspawn entityを解放します。 */
    fun close() = npcs.close()
    /** NPCの時間・天候条件とオンライン依頼進行を更新します。 */
    fun tick() {
        npcs.tick()
        val now=System.nanoTime()/1_000_000
        consumableReady.entries.removeIf { it.value <= now }
        plugin.server.onlinePlayers.forEach { player ->
            val p=plugin.profiles[player.uniqueId] ?: return@forEach
            if(player.uniqueId in locks) return@forEach
            val pos=Position(player.world.name,player.x,player.y,player.z)
            val inventory=plugin.inventory(player)
            val before=p.snapshot()
            try {
                plugin.snapshot.world.areas.values.filter { it.contains(pos) }.forEach { area -> plugin.game.quests.event(p,ObjectiveKind.EXPLORE,area.id,1,inventory) }
                plugin.game.quests.event(p,ObjectiveKind.COLLECT,"",1,inventory)
                if(before != p) { plugin.markDirty(p.uuid); if(before.quests.values.count { it.completed } != p.quests.values.count { it.completed }) plugin.save(p) }
            } catch(e: Exception) { plugin.logger.warning("依頼更新失敗 ${p.uuid}: ${e.message}") }
        }
    }
    private fun message(player: Player,text: String) = player.sendMessage(Component.text(text))
    private fun teleportArea(player: Player,id: String,destination: Position?) {
        val area=plugin.snapshot.world.areas[id] ?: error("転送先エリアが見つかりません: $id")
        require(area.world == DemoWorld.NAME) { "管理ワールド以外へは転送できません。" }
        val world=Bukkit.getWorld(area.world) ?: error("転送先ワールドがありません。")
        val x=(area.minX+area.maxX)/2.0; val z=(area.minZ+area.maxZ)/2.0
        val target=destination ?: Position(area.world,x,(world.getHighestBlockYAt(x.toInt(),z.toInt())+1).toDouble(),z)
        require(area.contains(target)) { "転送座標は対象エリア内で指定してください。" }
        player.teleport(org.bukkit.Location(world,target.x,target.y,target.z,target.yaw,0f))
        message(player,"${area.name}へ転送しました。")
    }
    @EventHandler(ignoreCancelled=true) fun interact(e: PlayerInteractEntityEvent) {
        if(e.hand != EquipmentSlot.HAND) return
        val id=npcs.id(e.rightClicked) ?: return
        e.isCancelled=true
        val p=plugin.profiles[e.player.uniqueId] ?: return
        if(locked(p.uuid)) return
        plugin.game.quests.event(p,ObjectiveKind.TALK,id,1,plugin.inventory(e.player)); plugin.markDirty(p.uuid)
        val npc=plugin.snapshot.world.npcs[id] ?: return
        showDialogue(e.player,npc.dialogue)
    }
    /** 条件付き会話選択肢をGUIとして表示します。 */
    fun showDialogue(player: Player,id: String,node: String? = null) {
        val p=plugin.profiles[player.uniqueId] ?: return
        val (at,definition)=dialogues.node(p,id,node)
        dialogueSessions[p.uuid]=id to at
        val menu=RpgMenu("dialogue",id,at).inventory
        menu.setItem(4,ItemStack(Material.WRITTEN_BOOK).apply { itemMeta=itemMeta.apply {
            displayName(Component.text("お話")); lore(definition.text.chunked(28).map { mm.deserialize(it) })
        } })
        definition.choices.take(45).forEachIndexed { index,c -> menu.setItem(9+index,ItemStack(Material.PAPER).apply { itemMeta=itemMeta.apply {
            displayName(mm.deserialize(c.label)); persistentDataContainer.set(entryKey,PersistentDataType.STRING,c.id)
        } }) }
        player.sendMessage(mm.deserialize(definition.text))
        player.openInventory(menu)
    }
    /** 条件を満たす商品だけをショップ画面に表示します。 */
    fun showShop(player: Player,id: String) {
        val p=plugin.profiles[player.uniqueId] ?: return
        val shop=plugin.snapshot.world.shops[id] ?: error("店が見つかりません。")
        val menu=RpgMenu("shop",id).inventory
        shop.entries.forEachIndexed { index,e ->
            if(e.condition.matches(p)) menu.setItem(index,plugin.createItem(e.item,e.amount).apply { itemMeta=itemMeta.apply {
                val extra=listOf(Component.text("価格 ${e.price}G / 左クリックで購入"),Component.text("素材: ${e.tokens.joinToString { "${it.item} ×${it.amount}" }}"),Component.text("売却: 持ち物の品を右クリック"))
                lore((lore() ?: emptyList())+extra); persistentDataContainer.set(entryKey,PersistentDataType.STRING,e.id)
            } })
        }
        player.openInventory(menu); message(player,"所持金: ${p.currency}G")
    }
    /** 装備可能なアクセサリー一覧を表示します。 */
    fun equipment(player: Player) {
        val p=plugin.profiles[player.uniqueId] ?: return
        val menu=RpgMenu("equipment","").inventory
        player.inventory.storageContents.filterNotNull().mapNotNull { plugin.item(it) }.filter { it.slot == GearSlot.ACCESSORY && it.canEquip(p) }.distinctBy { it.id }.take(54).forEachIndexed { i,item ->
            menu.setItem(i,plugin.createItem(item.id).apply { itemMeta=itemMeta.apply { persistentDataContainer.set(entryKey,PersistentDataType.STRING,item.id) } })
        }
        player.openInventory(menu)
    }
    @EventHandler(priority=EventPriority.LOWEST) fun click(e: InventoryClickEvent) {
        val player=e.whoClicked as? Player ?: return
        if(locked(player.uniqueId)) { e.isCancelled=true; return }
        val menu=e.view.topInventory.holder as? RpgMenu ?: return
        e.isCancelled=true
        val p=plugin.profiles[player.uniqueId] ?: return
        try {
            val entry=e.currentItem?.itemMeta?.persistentDataContainer?.get(entryKey,PersistentDataType.STRING)
            when(menu.kind) {
                "dialogue" -> {
                    if(e.rawSlot !in 9..53 || entry == null || dialogueSessions[p.uuid] != menu.contentId to menu.nodeId) return
                    val choice=dialogues.choice(p,menu.contentId,menu.nodeId,entry)
                    val after=mutableListOf<() -> Unit>()
                    transaction(player,{ inventory ->
                        p.flags.removeAll(choice.clearFlags); p.flags.addAll(choice.flags)
                        choice.actions.forEach { a -> when(a.kind) {
                            "START_QUEST" -> plugin.game.startQuest(p,a.target)
                            "COMPLETE_QUEST" -> plugin.game.completeQuest(p,a.target,inventory)
                            "SELECT_CLASS" -> plugin.game.chooseClass(p,a.target)
                            "OPEN_SHOP" -> after.add { showShop(player,a.target) }
                            "ENTER_DUNGEON" -> after.add { enterDungeon(player,a.target) }
                            "TELEPORT_AREA" -> {
                                require(a.requiredFlag == null || a.requiredFlag in p.flags) { "転送条件を満たしていません。" }
                                after.add { teleportArea(player,a.target,a.destination) }
                            }
                            "GIVE_ITEM" -> if(a.onceFlag == null || a.onceFlag !in p.flags) {
                                require(a.onceFlag != null) { "一度限りの配布フラグがありません。" }
                                require(inventory.exchange(emptyMap(),mapOf(a.target to a.amount)))
                                p.flags.add(a.onceFlag)
                            }
                        } }
                    }) {
                        if(after.isNotEmpty()) after.forEach { it() }
                        else if(!choice.next.isNullOrEmpty()) showDialogue(player,menu.contentId,choice.next)
                        else player.closeInventory()
                        plugin.ensureStarter(player)
                    }
                }
                "shop" -> {
                    if(e.rawSlot in 0..53 && entry != null && e.isLeftClick) {
                        val plan=trades.buy(p,menu.contentId,entry,plugin.inventory(player),UUID.randomUUID().toString(),System.currentTimeMillis())
                        transaction(player,{ inv -> require(inv.exchange(plan.remove,plan.add)); trades.commit(p,plan) }) { showShop(player,menu.contentId) }
                    } else if(e.clickedInventory == player.inventory && e.isRightClick) {
                        val id=plugin.identify(e.currentItem) ?: return
                        val plan=trades.sell(p,menu.contentId,id,1,plugin.inventory(player),UUID.randomUUID().toString())
                        transaction(player,{ inv -> require(inv.exchange(plan.remove,plan.add)); trades.commit(p,plan) }) { showShop(player,menu.contentId) }
                    }
                }
                "equipment" -> if(entry != null && e.rawSlot in 0..53) {
                    require(plugin.inventory(player).count(entry) > 0 && plugin.snapshot.items[entry].canEquip(p))
                    p.accessory=entry; plugin.save(p); player.closeInventory(); message(player,"装飾品を装備しました。")
                }
            }
        } catch(error: Exception) { message(player,"操作できません: ${error.message}") }
    }
    @EventHandler(priority=EventPriority.LOWEST) fun drag(e: InventoryDragEvent) {
        if(e.view.topInventory.holder is RpgMenu || locked(e.whoClicked.uniqueId)) e.isCancelled=true
    }
    @EventHandler(ignoreCancelled=true) fun drop(e: PlayerDropItemEvent) {
        if(locked(e.player.uniqueId) || bound(e.itemDrop.itemStack)) { e.isCancelled=true; message(e.player,"このアイテムは手放せません。") }
    }
    @EventHandler(ignoreCancelled=true) fun tradeBound(e: InventoryClickEvent) {
        if(e.whoClicked !is Player) return
        if(e.currentItem?.type?.name?.endsWith("BUNDLE") == true && bound(e.cursor) || e.cursor.type.name.endsWith("BUNDLE") && bound(e.currentItem)) { e.isCancelled=true; return }
        val external=e.view.topInventory.type !in setOf(InventoryType.CRAFTING,InventoryType.CREATIVE)
        if(external && (bound(e.currentItem) || bound(e.cursor) || e.click == ClickType.SWAP_OFFHAND && bound((e.whoClicked as Player).inventory.itemInOffHand) || e.hotbarButton >= 0 && bound((e.whoClicked as Player).inventory.getItem(e.hotbarButton))) || (e.click == ClickType.DROP || e.click == ClickType.CONTROL_DROP) && bound(e.currentItem)) e.isCancelled=true
    }
    @EventHandler(ignoreCancelled=true) fun boundEntity(e: PlayerInteractEntityEvent) {
        if(e.rightClicked is ItemFrame || e.rightClicked is ArmorStand || e.rightClicked is Allay) {
            val stack=if(e.hand == EquipmentSlot.OFF_HAND) e.player.inventory.itemInOffHand else e.player.inventory.itemInMainHand
            if(bound(stack) || locked(e.player.uniqueId)) e.isCancelled=true
        }
    }
    @EventHandler(ignoreCancelled=true) fun boundPlace(e: org.bukkit.event.block.BlockPlaceEvent) { if(bound(e.itemInHand)) e.isCancelled=true }
    @EventHandler fun craft(e: PrepareItemCraftEvent) { if(e.inventory.matrix.any(::bound)) e.inventory.result=null }
    @EventHandler(ignoreCancelled=true) fun dragBound(e: InventoryDragEvent) { if(bound(e.oldCursor) && e.rawSlots.any { it < e.view.topInventory.size }) e.isCancelled=true }
    @EventHandler(ignoreCancelled=true) fun pickup(e: EntityPickupItemEvent) { if(e.entity.uniqueId in locks) e.isCancelled=true }
    @EventHandler fun quit(e: PlayerQuitEvent) { beforeQuit(e.player); parties.leave(e.player.uniqueId); dialogueSessions.remove(e.player.uniqueId) }
    @EventHandler fun death(e: PlayerDeathEvent) {
        val kept=e.drops.filter(::bound)
        e.drops.removeAll(kept.toSet()); e.itemsToKeep.addAll(kept)
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) fun safe(e: EntityDamageEvent) {
        if(npcs.isManaged(e.entity)) { e.isCancelled=true; return }
        val p=e.entity as? Player ?: return
        if(plugin.snapshot.world.areas.values.any { it.safe && it.contains(Position(p.world.name,p.x,p.y,p.z)) }) e.isCancelled=true
    }
    private val consumableReady=mutableMapOf<Pair<UUID,String>,Long>()
    /** 消耗品を既存取引規則に沿って消費し、定義済み効果を適用します。 */
    fun consume(player: Player,stack: ItemStack): Boolean {
        val id=plugin.identify(stack) ?: return false
        val properties=plugin.snapshot.world.itemProperties[id] ?: return false
        if(properties.effects.any { it.kind == "REVIVE" }) { message(player,reviveUseNotice()); return true }
        if(plugin.snapshot.items[id].slot != GearSlot.NONE) return false
        val effects=properties.effects.filter { it.kind in setOf("HEAL","RESOURCE","CLEANSE","RETURN","DEFENSE_BUFF") }
        if(effects.isEmpty()) return false
        val p=plugin.profiles[player.uniqueId] ?: return true
        val now=System.nanoTime()/1_000_000
        if(now < consumableReady.getOrDefault(p.uuid to id,0)) { message(player,"まだ使用できません。"); return true }
        try {
            transaction(player,{ inventory ->
                require(inventory.exchange(mapOf(id to 1),emptyMap()))
                effects.filter { it.kind == "RESOURCE" }.forEach { p.resource=(p.resource+it.amount).coerceAtMost(plugin.effectiveStats(player,p).resource) }
            },persistConsumption=false) {
                effects.forEach { effect -> when(effect.kind) {
                    "HEAL" -> player.health=(player.health+effect.amount).coerceAtMost(player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)!!.value)
                    "CLEANSE" -> { player.removePotionEffect(org.bukkit.potion.PotionEffectType.POISON); player.removePotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS) }
                    "RETURN" -> Bukkit.getWorld(DemoWorld.NAME)?.let { player.teleport(it.spawnLocation) }
                    "DEFENSE_BUFF" -> plugin.defenseBuff(player,effect.amount,effect.duration)
                } }
                consumableReady[p.uuid to id]=now+(effects.maxOfOrNull { it.cooldown } ?: 0)
            }
        } catch(error: Exception) { message(player,"使用できません: ${error.message}") }
        return true
    }
    private val scheduled=mutableSetOf<UUID>()
    private val waiting=mutableMapOf<UUID,ArrayDeque<() -> Unit>>()
    /** 同じUUIDの保存ロックが解放された後に実行する処理をFIFOへ追加します。 */
    fun whenAvailable(id: UUID,action: () -> Unit) {
        waiting.getOrPut(id) { ArrayDeque() }.addLast(action); resume(id)
    }
    /** ロック・接続状態を確認し、待機中の処理をメインスレッドで一つ実行します。 */
    fun resume(id: UUID) {
        if(id in scheduled || locked(id) || Bukkit.getPlayer(id)?.isOnline != true || id !in plugin.profiles) return
        val queue=waiting[id] ?: return
        val action=queue.removeFirstOrNull() ?: return
        if(queue.isEmpty()) waiting.remove(id)
        scheduled.add(id)
        plugin.sync {
            scheduled.remove(id)
            if(locked(id)) { waiting.getOrPut(id) { ArrayDeque() }.addFirst(action); return@sync }
            try { action() } catch(error: Exception) { plugin.logger.warning("保留報酬の処理失敗 $id: ${error.message}") }
            if(!locked(id)) resume(id)
        }
    }
    private fun unlock(id: UUID) { locks.remove(id); resume(id) }
    /** shutdown前に残っているロックまたは保留callbackを検出します。 */
    fun hasTransactions() = locks.isNotEmpty() || scheduled.isNotEmpty() || waiting.values.any { it.isNotEmpty() } || delivering.isNotEmpty()
    /** ID付きアイテムのbound / quest_item状態を調べます。 */
    fun bound(stack: ItemStack?): Boolean = plugin.identify(stack)?.let { plugin.snapshot.world.itemProperties[it]?.let { p -> p.bound || p.questItem } } == true
    private val delivering=mutableSetOf<UUID>()
    private data class Delivery(val profile: PlayerProfile,val output: Map<String,Int>)
    private val deliveries=mutableMapOf<UUID,Delivery>()
    /** 切断前に受取途中の出力をメールボックスへ戻します。 */
    fun beforeQuit(player: Player) {
        deliveries.remove(player.uniqueId)?.let { claim ->
            claim.profile.pendingRewards.putAll(claim.output)
            // 同一IOキュー上のクリア保存より後へ戻し保存を積み、切断時の二重配布を防ぎます。
            plugin.persist(claim.profile)
        }
        delivering.remove(player.uniqueId)
    }
    /** disable時に受取途中の報酬をprofiles上へ戻してから保存させます。 */
    fun prepareShutdown() {
        deliveries.values.forEach { claim -> claim.profile.pendingRewards.putAll(claim.output) }
        deliveries.clear(); delivering.clear()
    }
    /** 永続メールボックスをロック付きで一度だけ受け取ります。 */
    fun claimPending(player: Player) {
        val p=plugin.profiles[player.uniqueId] ?: return
        if(p.pendingRewards.isEmpty()) return
        // 前回接続の保存callbackがロック保持中でも、再接続側のclaimを待ち行列へ回します。
        if(p.uuid in locks) {
            whenAvailable(p.uuid) { if(player.isOnline && plugin.profiles[p.uuid] === p) claimPending(player) }
            return
        }
        if(!locks.add(p.uuid)) return
        deliverLocked(player,p) {}
    }
    private fun deliverLocked(player: Player,p: PlayerProfile,success: () -> Unit) {
        if(p.pendingRewards.isEmpty()) { plugin.syncTitleAdvancements(player,p); unlock(p.uuid); success(); return }
        val output=p.pendingRewards.toMap(); val inventory=plugin.inventory(player)
        if(!inventory.canExchange(emptyMap(),output)) {
            message(player,"報酬を保管しています。持ち物に空きを作り /ec claim で受け取ってください。")
            plugin.syncTitleAdvancements(player,p); unlock(p.uuid); success(); return
        }
        // Paperアイテムを配る前に永続メールボックス権利を消費し、クラッシュ窓を損失側へ寄せます。
        val claim=Delivery(p,output); deliveries[p.uuid]=claim
        delivering.add(p.uuid); p.pendingRewards.clear()
        plugin.persist(p).whenComplete { _,error -> plugin.sync {
            try {
                // quitでメールボックスへ戻されたclaimは配布しません。二重発行を防ぐ判定です。
                if(deliveries[p.uuid] !== claim) return@sync
                if(error != null) {
                    if(plugin.profiles[p.uuid] === p) { p.pendingRewards.putAll(output); plugin.save(p) }
                    if(player.isOnline) message(player,"受取内容を保存できませんでした。/ec claim で再試行してください。")
                } else if(player.isOnline && plugin.profiles[p.uuid] === p) {
                    if(!inventory.exchange(emptyMap(),output)) {
                        p.pendingRewards.putAll(output); plugin.save(p); message(player,"報酬は保管されています。/ec claim で受け取ってください。")
                    }
                    plugin.syncTitleAdvancements(player,p)
                    success()
                } else plugin.logger.warning("報酬の受取確定直後に切断: ${p.uuid} $output。管理者回復が必要です。")
            } finally { deliveries.remove(p.uuid); delivering.remove(p.uuid); unlock(p.uuid) }
        } }
    }
    /** 消費物を先に保存し、プロフィール状態を永続化してから出力報酬を配布します。 */
    fun transaction(player: Player,mutate: (InventoryAccess) -> Unit,persistConsumption: Boolean = true,beforePersist: () -> Unit = {},success: () -> Unit = {}) {
        val p=plugin.profiles[player.uniqueId] ?: return
        require(locks.add(p.uuid)) { "取引の処理中です。" }
        val before=p.snapshot(); val inventory=plugin.inventory(player,persistConsumption); val staged=StagedInventory(inventory)
        val beforeStorage: Array<ItemStack?> = player.inventory.storageContents.map { it?.clone() }.toTypedArray()
        try {
            mutate(staged)
            staged.add.keys.forEach(p::discoverItem)
            require(inventory.canExchange(staged.remove,staged.add)) { "持ち物に空きがありません。" }
            require(inventory.exchange(staged.remove,emptyMap()))
            staged.add.forEach { (id,n) -> p.pendingRewards.merge(id,n,Int::plus) }
            beforePersist()
            plugin.persist(p).whenComplete { _,error -> plugin.sync {
                try {
                    if(error != null) {
                        val sameSession=plugin.profiles[p.uuid] === p
                        val offlineWithoutReconnect=!player.isOnline && Bukkit.getPlayer(p.uuid)?.isOnline != true
                        if(sameSession || offlineWithoutReconnect) {
                            plugin.rollbackEnhancement(p.uuid)
                            p.restore(before); player.inventory.storageContents=beforeStorage
                            runCatching { player.saveData() }.onFailure { plugin.logger.warning("切断後の持ち物復旧保存に失敗: ${p.uuid}: ${it.message}") }
                            plugin.save(p)
                            if(player.isOnline) message(player,"保存に失敗したため取引を戻しました。")
                        }
                        plugin.logger.severe("取引の保存に失敗しました ${p.uuid}: ${error.message}")
                    } else if(player.isOnline && plugin.profiles[p.uuid] === p) {
                        deliverLocked(player,p,success)
                        return@sync
                    } // Offline output remains in the durable pending reward mailbox.
                } finally { if(player.uniqueId !in delivering) unlock(p.uuid) }            } }
        } catch(error: Exception) {
            plugin.rollbackEnhancement(p.uuid)
            p.restore(before); player.inventory.storageContents=beforeStorage
            runCatching { player.saveData() }
            unlock(p.uuid); throw error
        }
    }
}







