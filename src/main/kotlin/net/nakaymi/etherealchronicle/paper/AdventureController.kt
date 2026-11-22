package net.nakaymi.etherealchronicle.paper

import net.nakaymi.etherealchronicle.core.*
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import io.papermc.paper.dialog.Dialog
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.type.DialogType
import net.kyori.adventure.text.event.ClickCallback
import org.bukkit.*
import org.bukkit.entity.*
import org.bukkit.event.*
import org.bukkit.event.entity.*
import org.bukkit.event.inventory.*
import org.bukkit.event.player.*
import org.bukkit.inventory.*
import org.bukkit.persistence.PersistentDataType
import java.util.UUID
import java.time.Duration

/** NPC会話、依頼、ショップ、パーティ、保留報酬、装備GUIをPaperへ接続します。 */
class AdventureController(private val plugin: RpgPlugin): Listener {
    val parties=PartyService({ plugin.snapshot.world.tuning.partyCap },Clock { System.currentTimeMillis() })
    private val dialogues=DialogueEngine { plugin.snapshot }
    private val trades=TradeEngine { plugin.snapshot }
    private val locks=mutableMapOf<UUID,LockToken>()
    /** 保存callbackごとの所有権を識別し、古い処理による後続ロック解放を防ぎます。 */
    internal data class LockToken(val playerId: UUID)
    private val entryKey=NamespacedKey(plugin,"entry_id")
    private val mm=MiniMessage.miniMessage()
    private val npcs=NpcController(plugin)
    private val dialogueSessions=mutableMapOf<UUID,Pair<String,String>>()
    private val invUiShops=mutableMapOf<UUID,String>()
    var enterDungeon: (Player,String) -> Unit = { p,id -> p.sendMessage(plugin.message(p,"ec.dungeon.preparing", "name" to plugin.contentName(p,id))) }
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
            try {
                transaction(player,{ staged ->
                    plugin.snapshot.world.areas.values.filter { it.contains(pos) }.forEach { area ->
                        plugin.game.quests.event(p,ObjectiveKind.EXPLORE,area.id,1,staged,allowAutoComplete=false)
                    }
                    plugin.game.quests.event(p,ObjectiveKind.COLLECT,"",1,staged,allowAutoComplete=false)
                    plugin.game.quests.readyAutoComplete(p,staged).forEach { quest -> plugin.game.completeQuest(p,quest,staged) }
                },skipIfUnchanged=true)
            } catch(e: Exception) { plugin.logger.warning("Quest progress update failed ${p.uuid}: ${e.message}") }
        }
    }
    private fun message(player: Player,text: String) = player.sendMessage(Component.text(text))
    private fun message(player: Player,component: Component) = player.sendMessage(component)
    private fun teleportArea(player: Player,id: String,destination: Position?) {
        val area=plugin.snapshot.world.areas[id] ?: error("ec.error.area_missing")
        require(area.world == DemoWorld.NAME) { "ec.error.teleport_world" }
        val world=Bukkit.getWorld(area.world) ?: error("ec.error.world_missing")
        val x=(area.minX+area.maxX)/2.0; val z=(area.minZ+area.maxZ)/2.0
        val target=destination ?: Position(area.world,x,(world.getHighestBlockYAt(x.toInt(),z.toInt())+1).toDouble(),z)
        require(area.contains(target)) { "ec.error.teleport_destination" }
        player.teleport(org.bukkit.Location(world,target.x,target.y,target.z,target.yaw,0f))
        player.sendMessage(plugin.message(player,"ec.area.teleported", "name" to area.name))
    }
    @EventHandler(ignoreCancelled=true) fun interact(e: PlayerInteractEntityEvent) {
        if(e.hand != EquipmentSlot.HAND) return
        val id=npcs.id(e.rightClicked) ?: return
        e.isCancelled=true
        val p=plugin.profiles[e.player.uniqueId] ?: return
        if(locked(p.uuid)) return
        val npc=plugin.snapshot.world.npcs[id] ?: return
        transaction(e.player,{ inventory ->
            plugin.game.quests.event(p,ObjectiveKind.TALK,id,1,inventory,allowAutoComplete=false)
            plugin.game.quests.readyAutoComplete(p,inventory).forEach { quest -> plugin.game.completeQuest(p,quest,inventory) }
        },skipIfUnchanged=true) { showDialogue(e.player,npc.dialogue) }
    }
    /** 条件付き会話選択肢をGUIとして表示します。 */
    fun showDialogue(player: Player,id: String,node: String? = null) {
        val p=plugin.profiles[player.uniqueId] ?: return
        val (at,definition)=dialogues.node(p,id,node)
        dialogueSessions[p.uuid]=id to at
        val menu=RpgMenu("dialogue",id,at,plugin.message(player,"ec.menu.dialogue")).inventory
        menu.setItem(4,ItemStack(Material.WRITTEN_BOOK).apply { itemMeta=itemMeta.apply {
            displayName(plugin.message(player,"ec.dialogue.talk")); lore(definition.text.chunked(28).map { mm.deserialize(it) })
        } })
        definition.choices.take(45).forEachIndexed { index,c -> menu.setItem(9+index,ItemStack(Material.PAPER).apply { itemMeta=itemMeta.apply {
            displayName(mm.deserialize(c.label)); persistentDataContainer.set(entryKey,PersistentDataType.STRING,c.id)
        } }) }
        player.sendMessage(mm.deserialize(definition.text))
        if (plugin.paperUiEnabled) showDialogueDialog(player,id,at,definition)
        else player.openInventory(menu)
    }
    /** Paper Dialogを主表示にし、選択アクションは既存の取引処理へ戻します。 */
    private fun showDialogueDialog(player: Player,id: String,node: String,definition: DialogueNode) {
        val buttons = definition.choices.map { choice ->
            ActionButton.builder(mm.deserialize(choice.label)).width(200)
                .action(DialogAction.customClick({ _, audience ->
                    (audience as? Player)?.let { selected ->
                        runCatching { selectDialogueChoice(selected,id,node,choice.id) }
                            .onFailure { selected.sendMessage(plugin.message(selected,"ec.command.failed", "reason" to plugin.diagnostic(selected,it.message.orEmpty()))) }
                    }
                }, ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(2)).build()))
                .build()
        }
        val close = ActionButton.builder(plugin.message(player,"ec.dialogue.close")).width(200).build()
        val dialog = Dialog.create { builder ->
            builder.empty()
                .base(DialogBase.builder(plugin.message(player,"ec.menu.dialogue"))
                    .body(listOf(DialogBody.plainMessage(mm.deserialize(definition.text))))
                    .canCloseWithEscape(true)
                    .build())
                .type(DialogType.multiAction(buttons, close, 2))
        }
        player.showDialog(dialog)
    }
    /** 条件を満たす商品だけをショップ画面に表示します。 */
    fun showShop(player: Player,id: String) {
        val p=plugin.profiles[player.uniqueId] ?: return
        val shop=plugin.snapshot.world.shops[id] ?: error("ec.error.shop_missing")
        val menu=RpgMenu("shop",id,title=plugin.message(player,"ec.menu.shop")).inventory
        shop.entries.forEachIndexed { index,e ->
            if(e.condition.matches(p)) menu.setItem(index,plugin.createItem(e.item,e.amount).apply { itemMeta=itemMeta.apply {
                val extra=listOf(plugin.message(player,"ec.shop.price_buy", "price" to e.price),plugin.message(player,"ec.shop.tokens", "items" to e.tokens.joinToString { "${it.item} ×${it.amount}" }),plugin.message(player,"ec.shop.sell_hint"))
                lore((lore() ?: emptyList())+extra); persistentDataContainer.set(entryKey,PersistentDataType.STRING,e.id)
            } })
        }
        val migrated = plugin.openRpgMenu(player, menu) { slot, click -> if (click.isLeftClick) buyShopSlot(player,id,slot) }
        if (migrated) invUiShops[player.uniqueId] = id else invUiShops.remove(player.uniqueId)
        player.sendMessage(plugin.message(player,"ec.shop.currency", "currency" to p.currency))
    }
    /** 装備可能なアクセサリー一覧を表示します。 */
    fun equipment(player: Player) {
        val p=plugin.profiles[player.uniqueId] ?: return
        val menu=RpgMenu("equipment","",title=plugin.message(player,"ec.menu.equipment")).inventory
        player.inventory.storageContents.filterNotNull().mapNotNull { plugin.item(it) }.filter { it.slot == GearSlot.ACCESSORY && it.canEquip(p) }.distinctBy { it.id }.take(54).forEachIndexed { i,item ->
            menu.setItem(i,plugin.createItem(item.id).apply { itemMeta=itemMeta.apply { persistentDataContainer.set(entryKey,PersistentDataType.STRING,item.id) } })
        }
        plugin.openRpgMenu(player, menu) { slot, _ ->
            menu.getItem(slot)?.itemMeta?.persistentDataContainer?.get(entryKey,PersistentDataType.STRING)?.let { equipAccessory(player,it) }
        }
    }
    @EventHandler(priority=EventPriority.LOWEST) fun click(e: InventoryClickEvent) {
        val player=e.whoClicked as? Player ?: return
        if(locked(player.uniqueId)) { e.isCancelled=true; return }
        val menu=e.view.topInventory.holder as? RpgMenu
        val invUiShopId = invUiShops[player.uniqueId]
        if (invUiShopId != null && e.clickedInventory == player.inventory && e.isRightClick) {
            e.isCancelled = true
            runCatching { sellFromInventory(player, invUiShopId, e.currentItem, e.slot) }
                .onFailure { player.sendMessage(plugin.message(player,"ec.command.failed", "reason" to plugin.diagnostic(player,it.message.orEmpty()))) }
            return
        }
        if (menu == null) return
        e.isCancelled=true
        val p=plugin.profiles[player.uniqueId] ?: return
        try {
            val entry=e.currentItem?.itemMeta?.persistentDataContainer?.get(entryKey,PersistentDataType.STRING)
            when(menu.kind) {
                "dialogue" -> {
                    if(e.rawSlot !in 9..53 || entry == null || dialogueSessions[p.uuid] != menu.contentId to menu.nodeId) return
                    selectDialogueChoice(player,menu.contentId,menu.nodeId,entry)
                }
                "shop" -> {
                    if(e.rawSlot in 0..53 && entry != null && e.isLeftClick) {
                        val shopEntry = plugin.snapshot.world.shops[menu.contentId]?.entries?.indexOfFirst { it.id == entry } ?: -1
                        if (shopEntry >= 0) buyShopSlot(player,menu.contentId,shopEntry)
                    } else if(e.clickedInventory == player.inventory && e.isRightClick) {
                        sellFromInventory(player,menu.contentId,e.currentItem,e.slot)
                    }
                }
                "equipment" -> if(entry != null && e.rawSlot in 0..53) {
                    equipAccessory(player,entry)
                }
            }
        } catch(error: Exception) {
            val reason=plugin.diagnostic(player,error.message.orEmpty())
            player.sendMessage(plugin.message(player,"ec.command.failed", "reason" to reason))
        }
    }
    @EventHandler fun closeInvUiShop(event: InventoryCloseEvent) {
        invUiShops.remove(event.player.uniqueId)
    }
    @EventHandler fun quitInvUiShop(event: PlayerQuitEvent) {
        invUiShops.remove(event.player.uniqueId)
    }
    private fun buyShopSlot(player: Player, shopId: String, slot: Int) {
        val profile = plugin.profiles[player.uniqueId] ?: return
        val shop = plugin.snapshot.world.shops[shopId] ?: return
        val entry = shop.entries.getOrNull(slot)?.takeIf { it.condition.matches(profile) } ?: return
        val plan = when (val result = trades.planBuy(profile,shopId,entry.id,plugin.inventory(player),UUID.randomUUID().toString(),System.currentTimeMillis())) {
            is TradePlanResult.Ready -> result.plan
            is TradePlanResult.Rejected -> {
                player.sendMessage(plugin.diagnostic(player,result.messageKey))
                return
            }
        }
        runExpectedTrade(player) {
            transaction(player,{ inventory ->
                require(inventory.exchange(plan.remove,plan.add)) { "ec.error.materials_or_space" }
                trades.commit(profile,plan)
            }) { showShop(player,shopId) }
        }
    }
    private fun selectDialogueChoice(player: Player,dialogueId: String,nodeId: String,choiceId: String) {
        val profile = plugin.profiles[player.uniqueId] ?: return
        if (dialogueSessions[profile.uuid] != dialogueId to nodeId) return
        val choice = dialogues.choice(profile,dialogueId,nodeId,choiceId)
        val after = mutableListOf<() -> Unit>()
        transaction(player,{ inventory ->
            profile.flags.removeAll(choice.clearFlags)
            profile.flags.addAll(choice.flags)
            choice.actions.forEach { action ->
                when(action.kind) {
                    "START_QUEST" -> plugin.game.startQuest(profile,action.target)
                    "COMPLETE_QUEST" -> plugin.game.completeQuest(profile,action.target,inventory)
                    "SELECT_CLASS" -> plugin.game.chooseClass(profile,action.target)
                    "OPEN_SHOP" -> after.add { showShop(player,action.target) }
                    "ENTER_DUNGEON" -> after.add { enterDungeon(player,action.target) }
                    "TELEPORT_AREA" -> {
                        require(action.requiredFlag == null || action.requiredFlag in profile.flags) { "ec.error.teleport_condition" }
                        after.add { teleportArea(player,action.target,action.destination) }
                    }
                    "GIVE_ITEM" -> if(action.onceFlag == null || action.onceFlag !in profile.flags) {
                        require(action.onceFlag != null) { "ec.error.reward_once_flag" }
                        require(inventory.exchange(emptyMap(),mapOf(action.target to action.amount)))
                        profile.flags.add(action.onceFlag)
                    }
                }
            }
        }) {
            if(after.isNotEmpty()) after.forEach { it() }
            else if(!choice.next.isNullOrEmpty()) showDialogue(player,dialogueId,choice.next)
            else player.closeInventory()
            plugin.ensureStarter(player)
        }
    }
    private fun sellFromInventory(player: Player, shopId: String, stack: ItemStack?, sourceSlot: Int) {
        val profile = plugin.profiles[player.uniqueId] ?: return
        val id = plugin.identify(stack) ?: return
        val plan = when (val result = trades.planSell(profile,shopId,id,1,plugin.inventory(player),UUID.randomUUID().toString(),sourceSlot)) {
            is TradePlanResult.Ready -> result.plan
            is TradePlanResult.Rejected -> {
                player.sendMessage(plugin.diagnostic(player,result.messageKey))
                return
            }
        }
        runExpectedTrade(player) {
            transaction(player,{ inventory ->
                require(inventory.exchangeFromSlot(plan.sourceSlot,plan.remove,plan.add)) { "ec.error.sell_items_missing" }
                trades.commit(profile,plan)
            }) { showShop(player,shopId) }
        }
    }
    /** 取引中に起きる想定内の状態変化は言語メッセージへ変換し、UIライブラリまで例外を漏らしません。 */
    private fun runExpectedTrade(player: Player, action: () -> Unit) {
        try {
            action()
        } catch (error: Exception) {
            val key = error.message?.takeIf { it.startsWith("ec." + "error.") }
            if (key == null) throw error
            plugin.logger.fine("Trade rejected for ${player.uniqueId}: $key")
            player.sendMessage(plugin.diagnostic(player,key))
        }
    }
    private fun equipAccessory(player: Player, id: String) {
        val profile = plugin.profiles[player.uniqueId] ?: return
        require(plugin.inventory(player).count(id) > 0 && plugin.snapshot.items[id].canEquip(profile))
        profile.accessory = id
        plugin.save(profile)
        player.closeInventory()
        player.sendMessage(plugin.message(player,"ec.equipment.accessory_equipped"))
    }
    @EventHandler(priority=EventPriority.LOWEST) fun drag(e: InventoryDragEvent) {
        if(e.view.topInventory.holder is RpgMenu || locked(e.whoClicked.uniqueId)) e.isCancelled=true
    }
    @EventHandler(ignoreCancelled=true) fun drop(e: PlayerDropItemEvent) {
        if(locked(e.player.uniqueId) || bound(e.itemDrop.itemStack)) { e.isCancelled=true; e.player.sendMessage(plugin.message(e.player,"ec.item.cannot_drop")) }
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
        if(now < consumableReady.getOrDefault(p.uuid to id,0)) { player.sendMessage(plugin.message(player,"ec.error.consumable_cooldown")); return true }
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
        } catch(error: Exception) {
            val reason=plugin.diagnostic(player,error.message.orEmpty())
            player.sendMessage(plugin.message(player,"ec.command.failed", "reason" to reason))
        }
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
            try { action() } catch(error: Exception) { plugin.logger.warning("Pending reward processing failed $id: ${error.message}") }
            if(!locked(id)) resume(id)
        }
    }
    private fun acquireLock(id: UUID): LockToken? = if (id in locks) null else LockToken(id).also { locks[id]=it }
    private fun releaseLock(token: LockToken, resumeQueue: Boolean = true) {
        if (locks[token.playerId] !== token) return
        locks.remove(token.playerId)
        if (resumeQueue) resume(token.playerId)
    }
    /** 取引ロックを所有者単位で扱い、例外時だけ自動解放します。 */
    internal fun <T> withTxLock(id: UUID, action: (LockToken) -> T): T? {
        val token=acquireLock(id) ?: return null
        return try { action(token) } catch(error: Throwable) { releaseLock(token); throw error }
    }
    /** inventoryを伴わない報酬・記録更新も同じプロフィールロックと永続化境界で確定します。 */
    fun profileTransaction(id: UUID, mutate: (PlayerProfile) -> Unit, success: () -> Unit = {}) {
        val profile=plugin.profiles[id] ?: return
        if (locked(id)) { whenAvailable(id) { profileTransaction(id,mutate,success) }; return }
        val token=acquireLock(id) ?: return
        val before=profile.snapshot()
        try {
            mutate(profile)
            plugin.persist(profile).whenComplete { _,error -> plugin.sync {
                try {
                    if (error != null) {
                        profile.restore(before)
                        plugin.save(profile)
                        plugin.logger.severe("Profile transaction failed $id: ${error.message}")
                    } else success()
                } finally { releaseLock(token) }
            } }
        } catch (error: Throwable) {
            profile.restore(before)
            releaseLock(token)
            throw error
        }
    }
    /** shutdown前に残っているロックまたは保留callbackを検出します。 */
    fun hasTransactions() = locks.isNotEmpty() || scheduled.isNotEmpty() || waiting.values.any { it.isNotEmpty() } || delivering.isNotEmpty()
    /** ID付きアイテムのbound / quest_item状態を調べます。 */
    fun bound(stack: ItemStack?): Boolean = plugin.identify(stack)?.let { plugin.snapshot.world.itemProperties[it]?.let { p -> p.bound || p.questItem } } == true
    private val delivering=mutableSetOf<UUID>()
    private data class Delivery(val profile: PlayerProfile,val output: Map<String,Int>,val token: LockToken)
    private val deliveries=mutableMapOf<UUID,Delivery>()
    /** 切断前に受取途中の出力をメールボックスへ戻します。 */
    fun beforeQuit(player: Player) {
        deliveries.remove(player.uniqueId)?.let { claim ->
            claim.profile.returnToMailbox(claim.output)
            // 同一IOキュー上のクリア保存より後へ戻し保存を積み、切断時の二重配布を防ぎます。
            plugin.persist(claim.profile)
            releaseLock(claim.token)
        }
        delivering.remove(player.uniqueId)
    }
    /** disable時に受取途中の報酬をprofiles上へ戻してから保存させます。 */
    fun prepareShutdown() {
        deliveries.values.forEach { claim -> claim.profile.returnToMailbox(claim.output); releaseLock(claim.token,false) }
        deliveries.clear(); delivering.clear(); locks.clear()
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
        withTxLock(p.uuid) { token -> deliverLocked(player,p,token) {} }
    }
    private fun deliverLocked(player: Player,p: PlayerProfile,token: LockToken,success: () -> Unit) {
        if(p.pendingRewards.isEmpty()) { plugin.syncTitleAdvancements(player,p); releaseLock(token); success(); return }
        val output=p.pendingRewards.toMap(); val inventory=plugin.inventory(player)
        if(!inventory.canExchange(emptyMap(),output)) {
            message(player,plugin.message(player,"ec.mailbox.inventory_full"))
            plugin.syncTitleAdvancements(player,p); releaseLock(token); success(); return
        }
        // Paperアイテムを配る前に永続メールボックス権利を消費し、クラッシュ窓を損失側へ寄せます。
        val claim=Delivery(p,output,token); deliveries[p.uuid]=claim
        delivering.add(p.uuid); p.pendingRewards.clear()
        plugin.persist(p).whenComplete { _,error -> plugin.sync {
            try {
                // quitでメールボックスへ戻されたclaimは配布しません。二重発行を防ぐ判定です。
                if(deliveries[p.uuid] !== claim) return@sync
                if(error != null) {
                    if(plugin.profiles[p.uuid] === p) { p.returnToMailbox(output); plugin.save(p) }
                    if(player.isOnline) message(player,plugin.message(player,"ec.mailbox.save_retry"))
                } else if(player.isOnline && plugin.profiles[p.uuid] === p) {
                    if(!inventory.exchange(emptyMap(),output)) {
                        p.returnToMailbox(output); plugin.save(p); message(player,plugin.message(player,"ec.mailbox.inventory_full"))
                    }
                    plugin.syncTitleAdvancements(player,p)
                    success()
                } else plugin.logger.warning("Player disconnected immediately after reward claim persistence: ${p.uuid} $output; admin recovery may be required.")
            } finally { deliveries.remove(p.uuid); delivering.remove(p.uuid); releaseLock(token) }
        } }
    }
    /** 消費物を先に保存し、プロフィール状態を永続化してから出力報酬を配布します。 */
    fun transaction(player: Player,mutate: (InventoryAccess) -> Unit,persistConsumption: Boolean = true,beforePersist: () -> Unit = {},skipIfUnchanged: Boolean = false,failure: (Throwable) -> Unit = {},success: () -> Unit = {}) {
        val p=plugin.profiles[player.uniqueId] ?: return
        val token=acquireLock(p.uuid) ?: error("ec.error.transaction_busy")
        val before=p.snapshot(); val inventory=plugin.inventory(player,persistConsumption); val staged=StagedInventory(inventory)
        val beforeStorage: Array<ItemStack?> = player.inventory.storageContents.map { it?.clone() }.toTypedArray()
        try {
            mutate(staged)
            if(skipIfUnchanged && before == p && staged.remove.isEmpty() && staged.add.isEmpty()) {
                releaseLock(token); success(); return
            }
            staged.add.keys.forEach(p::discoverItem)
            require(inventory.canExchange(staged.remove,staged.add)) { "ec.error.inventory_full" }
            require(inventory.exchangeFromSlot(staged.preferredRemovalSlot,staged.remove,emptyMap()))
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
                            runCatching { player.saveData() }.onFailure { plugin.logger.warning("Failed to persist inventory rollback after disconnect: ${p.uuid}: ${it.message}") }
                            plugin.save(p)
                            if(player.isOnline) player.sendMessage(plugin.message(player,"ec.error.transaction_rolled_back"))
                        }
                        plugin.logger.severe("Transaction persistence failed ${p.uuid}: ${error.message}")
                        if (player.isOnline) failure(error)
                    } else if(player.isOnline && plugin.profiles[p.uuid] === p) {
                        deliverLocked(player,p,token,success)
                        return@sync
                    } // Offline output remains in the durable pending reward mailbox.
                } finally {
                    if (deliveries[p.uuid]?.token !== token) releaseLock(token)
                }
            } }
        } catch(error: Exception) {
            plugin.rollbackEnhancement(p.uuid)
            p.restore(before); player.inventory.storageContents=beforeStorage
            runCatching { player.saveData() }
            releaseLock(token); throw error
        }
    }
}







