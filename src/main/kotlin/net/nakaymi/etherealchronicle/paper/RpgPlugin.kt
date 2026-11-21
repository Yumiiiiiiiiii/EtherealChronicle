package net.nakaymi.etherealchronicle.paper

import net.nakaymi.etherealchronicle.core.*
import net.nakaymi.etherealchronicle.data.*
import net.kyori.adventure.text.Component
import org.bukkit.*
import org.bukkit.attribute.Attribute
import org.bukkit.command.*
import org.bukkit.entity.*
import org.bukkit.event.*
import org.bukkit.event.entity.*
import org.bukkit.event.inventory.*
import org.bukkit.event.player.*
import org.bukkit.inventory.*
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.concurrent.CompletableFuture

class ClassMenu: InventoryHolder {
    private val menu = Bukkit.createInventory(this,9,Component.text("職業を選んでください"))
    override fun getInventory() = menu
}

open class RpgPlugin: JavaPlugin(), Listener, CommandExecutor {
    private var ready = false
    internal lateinit var adventure: AdventureController
    internal lateinit var enemies: EnemyController
    internal lateinit var dungeons: DungeonController
    internal lateinit var gathering: GatheringController
    internal lateinit var fishing: FishingController
    internal lateinit var treasures: TreasureController
    private lateinit var content: ContentService
    private lateinit var store: PlayerStore
    internal lateinit var game: GameService
    internal val profiles = mutableMapOf<UUID,PlayerProfile>()
    private val sessions = mutableMapOf<UUID,UUID>()
    private val choosing = mutableSetOf<UUID>()
    private val lastMoved = mutableMapOf<UUID,Int>()
    private val dirty = mutableSetOf<UUID>()
    private var autosaveSeconds = 0
    private lateinit var simStore: PlayerStore
    private val simulated = mutableMapOf<String,PlayerProfile>()
    private val simSessions = mutableMapOf<String,SimulationSession>()
    private data class EnhancementFlight(val token:String,val material:String,val currency:Long)
    private val enhancementFlights=mutableMapOf<UUID,EnhancementFlight>()
    private var advancementWarningLogged=false
    private lateinit var itemKey: NamespacedKey
    private lateinit var enhancementKey: NamespacedKey
    private lateinit var enhancementTransactionKey: NamespacedKey
    private lateinit var enemyKey: NamespacedKey
    private lateinit var slotKey: NamespacedKey
    internal val snapshot get() = content.snapshot()
    internal fun persist(profile: PlayerProfile) = store.save(profile)
    internal fun identify(stack: ItemStack?) = stack?.itemMeta?.persistentDataContainer?.get(itemKey,PersistentDataType.STRING)
    internal fun inventory(player: Player,persistRemoval: Boolean = true): PaperInventoryAccess = PaperInventoryAccess(player,::identify,::createItem,persistRemoval) { logger.fine("取引の消費アイテムを保存しました: ${player.uniqueId}") }
    internal fun markDirty(player: UUID) { dirty.add(player) }
    override fun onEnable() {
        itemKey = NamespacedKey(this,"item_id"); enhancementKey=NamespacedKey(this,"enhancement_level"); enhancementTransactionKey=NamespacedKey(this,"enhancement_transaction"); enemyKey = NamespacedKey(this,"enemy_id"); slotKey = NamespacedKey(this,"skill_slot")
        getCommand("ec")!!.setExecutor(this) // commands must reply while asynchronous content startup runs
        server.scheduler.runTaskAsynchronously(this,Runnable {
            try {
                val bundled = getResource("content-index.txt")!!.bufferedReader().use { reader ->
                    reader.readLines().filter { it.isNotBlank() }.associate { path ->
                        val clean = path.removePrefix("\uFEFF")
                        clean to getResource(clean)!!.use { it.readBytes() }
                    }
                }
                val legacy=getResource("retired-content.properties")?.use { DefaultContentInstaller.manifest(it.readBytes()) } ?: emptyMap()
                val oldDefaults=getResource("foundation-content.properties")?.use { DefaultContentInstaller.manifest(it.readBytes()) } ?: emptyMap()
                val retired=DefaultContentInstaller.retire(dataFolder.toPath(),bundled.keys,legacy)
                retired.retired.forEach { logger.info("旧配布コンテンツを退避しました: $it") }
                retired.preserved.forEach { logger.warning("編集済みの旧配布ファイルを保持しました。定義を確認してください: $it") }
                val installation=DefaultContentInstaller.installTracked(dataFolder.toPath(),bundled,oldDefaults)
                installation.updated.forEach { name -> logger.info("未編集の旧配布コンテンツを更新しました (旧版はcontent-historyへ保存): $name") }
                installation.changed.forEach { name ->
                    logger.info("サーバー編集を優先します: $name（配布版と異なります。更新時は必須項目を比較してください）")
                }
                listOf("balance","classes","skills","items","enemies","bosses","npcs","quests","dialogues","chapters","droptables","shops","titles","tackle","fishing_spots","fish_species","gathering_nodes","treasure_chests").forEach { java.nio.file.Files.createDirectories(dataFolder.toPath().resolve("content").resolve(it)) }
                val startup=ContentBootstrap.initialize(dataFolder.toPath(),bundled,::validatePaperContent)
                content=startup.service
                if(startup.usedFallback) logger.warning("サーバーのコンテンツに不備があります。配布版で起動し /ec validate と /ec reload を有効にします: ${startup.problem}")
                store = PlayerStore(dataFolder.toPath().resolve("playerdata"))
                simStore = PlayerStore(dataFolder.toPath().resolve("simulation"))
                sync {
                    game = GameService(content::snapshot,SkillEngine(Clock { System.nanoTime()/1_000_000 },content.snapshot().balance))
                    adventure=AdventureController(this)
                    enemies=EnemyController(this,adventure)
                    dungeons=DungeonController(this,adventure,enemies)
                    try { if(DemoWorld.ensure(this) != null) adventure.reloadNpcs() }
                    catch(error: Exception) { logger.severe("専用ワールドを準備できませんでした: ${error.message}。基盤機能のみ有効です。") }
                    gathering=GatheringController(this)
                    fishing=FishingController(this)
                    treasures=TreasureController(this).also { it.reload() }
                    runCatching { reloadAdvancementPack() }.onFailure { advancementWarning(it) }
                    server.scheduler.runTaskTimer(this,Runnable { enemies.tick() },10,10)
                    ready = true
                    server.pluginManager.registerEvents(this,this)
                    getCommand("ec")!!.setExecutor(this)
                    server.scheduler.runTaskTimer(this,Runnable { tick() },20,20)
                    server.onlinePlayers.forEach { join(it) }
                    logger.info("冒険システムの準備が完了しました。")
                }
            } catch(e: Exception) {
                logger.severe("コンテンツの読み込みに失敗: ${e.message}")
                sync { server.pluginManager.disablePlugin(this) }
            }
        })
    }
    override fun onDisable() {
        ready = false
        enhancementFlights.keys.toList().forEach { id ->
            val player=server.getPlayer(id)
            val profile=profiles[id]
            if(player != null && profile != null) settleEnhancement(player,profile)
            else enhancementFlights.remove(id)
        }
        if(::adventure.isInitialized) adventure.prepareShutdown()
        if(::fishing.isInitialized) runCatching { fishing.close() }.onFailure { logger.severe("釣り機能の終了処理に失敗: ${it.message}") }
        if(::gathering.isInitialized) runCatching { gathering.close() }.onFailure { logger.severe("採取機能の終了処理に失敗: ${it.message}") }
        if(::dungeons.isInitialized) runCatching { dungeons.shutdown() }.onFailure { logger.severe("聖堂の終了処理に失敗: ${it.message}") }
        if(::enemies.isInitialized) runCatching { enemies.close() }.onFailure { logger.severe("魔物の終了処理に失敗: ${it.message}") }
        if(::adventure.isInitialized) runCatching { adventure.close() }.onFailure { logger.severe("NPCの終了処理に失敗: ${it.message}") }
        if(::treasures.isInitialized) runCatching { treasures.close() }.onFailure { logger.severe("宝箱の終了処理に失敗: ${it.message}") }
        server.scheduler.cancelTasks(this)
        if (::store.isInitialized) {
            profiles.values.forEach { store.save(it) }
            if (::simStore.isInitialized) runCatching { simStore.close() }.onFailure { logger.severe("シミュレーション保存失敗: ${it.message}") }
            runCatching { store.close() }.onFailure { logger.severe("終了時のデータ保存失敗: ${it.message}") }
        }
        profiles.clear(); sessions.clear()
    }
    internal fun sync(action: () -> Unit) {
        if(!isEnabled) return
        try { server.scheduler.runTask(this,Runnable { if(isEnabled) action() }) }
        catch(_: org.bukkit.plugin.IllegalPluginAccessException) { /* disabled between the check and scheduling */ }
    }
    internal fun save(p: PlayerProfile) { store.save(p).exceptionally { logger.severe("保存失敗 ${p.uuid}: ${it.message}"); sync { if(p.uuid in profiles) dirty.add(p.uuid) }; null } }
    private fun join(player: Player) {
        val token = UUID.randomUUID(); sessions[player.uniqueId] = token
        player.sendMessage(Component.text("冒険データを読み込んでいます…"))
        store.load(player.uniqueId).whenComplete { profile,error -> sync {
            if(sessions[player.uniqueId] != token || !player.isOnline) return@sync
            if(error != null) { logger.severe("読み込み失敗: ${error.message}"); player.kick(Component.text("冒険データを読み込めませんでした。管理者にご連絡ください。")); return@sync }
            val snapshot = content.snapshot()
            ProfileContentMigration.apply(profile,snapshot)
            if(profile.classId != null && snapshot.classes.find(profile.classId!!) == null) { player.kick(Component.text("職業データが見つかりません。管理者にご連絡ください。")); return@sync }
            profile.level = profile.level.coerceIn(1,snapshot.balance.levelCap)
            profile.skillLevels.replaceAll { _,level -> level.coerceIn(1,snapshot.balance.skillLevelCap) }
            profile.classId = profile.classId?.let { snapshot.classes[it].id }
            for(slot in 0 until player.inventory.size) {
                val old=player.inventory.getItem(slot); val id=identify(old) ?: continue
                profile.discoverItem(id)
                if(snapshot.items.find(id) == null) ProfileContentMigration.oldItems[id]?.takeIf { snapshot.items.find(it) != null }?.let { player.inventory.setItem(slot,createItem(it,old!!.amount)) }
            }
            profiles[player.uniqueId] = profile
            syncTitleAdvancements(player,profile)
            if(::dungeons.isInitialized) dungeons.recover(player)
            if(!player.hasPlayedBefore() || profile.classId == null) Bukkit.getWorld(DemoWorld.NAME)?.let { player.teleport(DemoWorld.luminaSpawn(it)) }
            Bukkit.getWorld(DemoWorld.NAME)?.takeIf { player.world==it }?.let { DemoWorld.clearNearbySafeHostiles(this,it,player.location) }
            if(profile.classId == null || !profile.starterGranted) menu(player) else {
                skillSlot(player); applyStats(player,profile)
                adventure.whenAvailable(player.uniqueId) { if(player.isOnline && profiles[player.uniqueId] === profile) adventure.claimPending(player) }
            }
        } }
    }
    @EventHandler fun onJoin(e: PlayerJoinEvent) = join(e.player)
    @EventHandler fun onQuit(e: PlayerQuitEvent) {
        if(::adventure.isInitialized) adventure.beforeQuit(e.player)
        sessions.remove(e.player.uniqueId); choosing.remove(e.player.uniqueId)
        profiles.remove(e.player.uniqueId)?.let { profile -> settleEnhancement(e.player,profile); save(profile) }
        lastMoved.remove(e.player.uniqueId); dirty.remove(e.player.uniqueId)

    }
    private fun menu(player: Player) {
        val menu = ClassMenu().inventory
        content.snapshot().classes.all().take(9).forEachIndexed { index,c ->
            val icon=c.icon?.let(Material::valueOf) ?: when(c.resource) { ResourceKind.RAGE -> Material.IRON_SWORD; ResourceKind.FOCUS -> Material.BOW; ResourceKind.MANA -> Material.BLAZE_ROD }
            menu.setItem(index,ItemStack(icon).apply {
                itemMeta = itemMeta.apply { displayName(Component.text(c.name)); persistentDataContainer.set(itemKey,PersistentDataType.STRING,c.id) }
            })
        }
        player.openInventory(menu)
    }
    @EventHandler fun click(e: InventoryClickEvent) {
        val p = e.whoClicked as? Player ?: return
        if(e.view.topInventory.holder is ClassMenu) {
            e.isCancelled = true
            if(e.rawSlot !in 0..8) return
            val id = e.currentItem?.itemMeta?.persistentDataContainer?.get(itemKey,PersistentDataType.STRING) ?: return
            val profile = profiles[p.uniqueId] ?: return
            if(profile.classId != null && profile.classId != id) return
            starter(p,profile,id); return
        }
        if(p.uniqueId in choosing || e.slot == 8 && e.clickedInventory == p.inventory && isSkill(p.inventory.getItem(8)) || e.hotbarButton == 8 || isSkill(e.currentItem)) e.isCancelled = true
    }
    @EventHandler fun drag(e: InventoryDragEvent) {
        if(e.view.topInventory.holder is ClassMenu || e.rawSlots.any { e.view.convertSlot(it) == 8 && it >= e.view.topInventory.size }) e.isCancelled = true
    }
    @EventHandler fun closeMenu(e: InventoryCloseEvent) {
        val p = e.player as? Player ?: return
        if(e.inventory.holder is ClassMenu && profiles[p.uniqueId]?.starterGranted == false && p.uniqueId !in choosing) sync { if(p.isOnline) menu(p) }
        else if(profiles[p.uniqueId]?.starterGranted == true) sync { if(p.isOnline) skillSlot(p) }
    }
    internal fun ensureStarter(player: Player) { profiles[player.uniqueId]?.takeIf { it.classId != null && !it.starterGranted }?.let { starter(player,it,it.classId!!) } }
    private fun starter(p: Player, profile: PlayerProfile, id: String) {
        if(!choosing.add(p.uniqueId)) return
        val c = content.snapshot().classes[id]
        if(c.starterItems.size > p.inventory.storageContents.take(8).count { it == null } ) {
            choosing.remove(p.uniqueId); p.sendMessage(Component.text("持ち物に空きを作ってください。")); return
        }
        if(profile.classId == null) game.chooseClass(profile,id)
        profile.starterGranted = true
        // Persist entitlement consumption before inventory mutation to prevent repeat grants after crashes.
        store.save(profile).whenComplete { _,error -> sync {
            choosing.remove(p.uniqueId)
            if(error != null) { profile.starterGranted = false; p.sendMessage(Component.text("保存できませんでした。もう一度お試しください。")); return@sync }
            if(!p.isOnline || profiles[p.uniqueId] !== profile) return@sync
            if(c.starterItems.size > (0..7).count { p.inventory.getItem(it) == null }) { p.sendMessage(Component.text("持ち物の空きがなくなりました。管理者にスターター装備の再付与を依頼してください。")); return@sync }
            c.starterItems.forEach { item -> val slot = (0..7).first { p.inventory.getItem(it) == null }; p.inventory.setItem(slot,createItem(item)); profile.discoverItem(item) }
            save(profile)
            skillSlot(p); applyStats(p,profile); p.health=p.getAttribute(Attribute.MAX_HEALTH)!!.value
            p.closeInventory(); p.sendMessage(Component.text("ようこそ！受付嬢セリアに話しかけて冒険を始めましょう。"))
        } }
    }
    internal fun createItem(id: String, amount: Int = 1): ItemStack {
        val d = content.snapshot().items[id]
        return ItemStack(Material.valueOf(d.material),amount).apply { itemMeta = itemMeta.apply {
            displayName(Component.text(d.name)); lore(listOf(Component.text("必要レベル: ${d.minLevel} / ${d.rarity.displayName}")))
            snapshot.world.itemProperties[id]?.lore?.takeIf { it.isNotBlank() }?.let { text -> lore((lore() ?: emptyList())+text.chunked(28).map { Component.text(it) }) }
            persistentDataContainer.set(itemKey,PersistentDataType.STRING,id)
        } }
    }
    internal fun item(stack: ItemStack?): ItemDefinition? = stack?.itemMeta?.persistentDataContainer?.get(itemKey,PersistentDataType.STRING)?.let { content.snapshot().items.find(it) }
    private fun isSkill(stack: ItemStack?) = stack?.itemMeta?.persistentDataContainer?.has(slotKey,PersistentDataType.BYTE) == true
    private fun skillSlot(p: Player) {
        if(!isSkill(p.inventory.getItem(8))) {
            val old = p.inventory.getItem(8)
            if(old != null && adventure.bound(old)) {
                val free=(0 until p.inventory.storageContents.size).firstOrNull { it != 8 && p.inventory.getItem(it) == null }
                if(free == null) { p.sendMessage(Component.text("9番のアイテムを空きに移して持ち物を閉じてください。保護品は地面に捨てません。")); return }
                p.inventory.setItem(free,old)
            } else if(old != null) p.world.dropItemNaturally(p.location,old)
            p.inventory.setItem(8,ItemStack(Material.NETHER_STAR).apply { itemMeta = itemMeta.apply {
                displayName(Component.text("スキルスロット")); persistentDataContainer.set(slotKey,PersistentDataType.BYTE,1)
            } })
        }
    }
    private fun equippedDefinitions(p: Player,profile: PlayerProfile): List<ItemDefinition> {
        val equipped: List<Pair<GearSlot,ItemStack?>> = listOf(GearSlot.HEAD to p.inventory.helmet,GearSlot.CHEST to p.inventory.chestplate,GearSlot.LEGS to p.inventory.leggings,GearSlot.FEET to p.inventory.boots,GearSlot.HAND to p.inventory.itemInMainHand,GearSlot.OFFHAND to p.inventory.itemInOffHand)
        val definitions=equipped.mapNotNull { (slot,stack) -> item(stack)?.takeIf { it.slot == slot }?.let { def -> def.copy(stats=def.enhancedStats(stack?.itemMeta?.persistentDataContainer?.get(enhancementKey,PersistentDataType.INTEGER) ?: 0,snapshot.balance,snapshot.world.tuning.enhancementRate)) } }.toMutableList()
        profile.accessory?.let { id -> if(inventory(p).count(id) > 0) snapshot.items.find(id)?.takeIf { it.slot == GearSlot.ACCESSORY }?.let(definitions::add) }
        return definitions
    }
    internal fun effectiveStats(p: Player,profile: PlayerProfile): Stats {
        val base=game.stats(profile,equippedDefinitions(p,profile))
        val buff=defenseBuffs[p.uniqueId]?.takeIf { it.second > System.nanoTime()/1_000_000 }?.first ?: 0.0
        return base.copy(defense=(base.defense+buff).coerceAtMost(snapshot.balance.statCap))
    }
    private fun applyStats(p: Player, profile: PlayerProfile) {
        val max = effectiveStats(p,profile).hp.coerceIn(1.0,content.snapshot().balance.statCap)
        p.getAttribute(Attribute.MAX_HEALTH)?.baseValue = max
        p.isHealthScaled = true; p.healthScale = 20.0
        if(p.health > max) p.health = max
    }
    private fun hasEnhancementToken(player:Player,token:String):Boolean = player.inventory.contents.filterNotNull().any { stack ->
        stack.itemMeta.persistentDataContainer.get(enhancementTransactionKey,PersistentDataType.STRING) == token
    }
    /** Removes the in-flight reconciliation record when the surrounding transaction rolls back. */
    internal fun rollbackEnhancement(id:UUID) { enhancementFlights.remove(id) }
    /** If the output left inventory before completion, refund its costs exactly once. */
    private fun settleEnhancement(player:Player,profile:PlayerProfile):Boolean {
        val flight=enhancementFlights.remove(profile.uuid) ?: return true
        if(hasEnhancementToken(player,flight.token)) return true
        profile.currency=(profile.currency+flight.currency).coerceAtMost(snapshot.world.tuning.maxCurrency)
        val restored=runCatching { inventory(player).exchange(emptyMap(),mapOf(flight.material to 1)) }.getOrDefault(false)
        if(restored) {
            profile.discoverItem(flight.material)
            runCatching { player.saveData() }.onFailure { logger.warning("強化素材返却の保存に失敗: ${profile.uuid}: ${it.message}") }
        } else profile.pendingRewards.merge(flight.material,1,Int::plus)
        save(profile)
        return false
    }
    private fun advancementWarning(error:Throwable) {
        if(advancementWarningLogged) return
        advancementWarningLogged=true
        logger.warning("称号進捗データパックを登録できませんでした。称号は冒険プロフィールの解放フラグで利用できます: ${error.message}")
    }
    /** Data-pack files are generated only inside an owned directory; Paper's supported DatapackManager reloads them. */
    private fun reloadAdvancementPack() {
        val world=server.worlds.firstOrNull { it.environment==World.Environment.NORMAL && it.name!=DemoWorld.NAME }
            ?: server.worlds.firstOrNull() ?: error("進捗データパックを置くワールドがありません。")
        val installation=AdvancementDatapack.install(world.worldFolder.toPath(),content.snapshot().world.titles.values)
        val manager=server.datapackManager
        manager.refreshPacks()
        val datapack=manager.getPack(installation.packName) ?: error("データパックが検出されません: ${installation.packName}")
        val enabled=datapack.isEnabled
        if(!enabled) datapack.isEnabled=true else if(installation.changed) server.reloadData()
        server.updateResources()
        server.onlinePlayers.forEach { player -> profiles[player.uniqueId]?.let { syncTitleAdvancements(player,it) } }
    }
    /** Profile flags remain authoritative; data-pack progress is awarded or revoked to match them. */
    internal fun syncTitleAdvancements(player:Player,profile:PlayerProfile) {
        val newlyUnlocked=profile.syncTitleUnlockFlags(snapshot.world.titles)
        if(newlyUnlocked.isNotEmpty()) save(profile)
        try {
            val root=server.getAdvancement(NamespacedKey("ec","chronicle/root")) ?: return
            val rootProgress=player.getAdvancementProgress(root)
            rootProgress.remainingCriteria.firstOrNull()?.let(rootProgress::awardCriteria)
            snapshot.world.titles.values.forEach { title ->
                if(title.advancement == null) return@forEach
                val advancement=server.getAdvancement(NamespacedKey("ec","chronicle/${title.id.substringAfter(':')}")) ?: return@forEach
                val progress=player.getAdvancementProgress(advancement)
                val criterion="profile_condition_met"
                if(title.unlockFlag in profile.flags) {
                    if(criterion in progress.remainingCriteria) progress.awardCriteria(criterion)
                } else if(criterion in progress.awardedCriteria) progress.revokeCriteria(criterion)
            }
        } catch(error:Exception) { advancementWarning(error) }
    }
    private fun applyTitle(player:Player,profile:PlayerProfile) {
        val title=profile.selectedTitle?.let(snapshot.world.titles::get)?.takeIf { it.unlockFlag in profile.flags }?.name
        val display=if(title == null) Component.text(player.name) else Component.text("[$title] ${player.name}")
        player.displayName(display); player.playerListName(display)
    }
    private fun areaCodexPercent(profile:PlayerProfile,area:Area):Pair<Int,Int> {
        val enemies=snapshot.world.enemiesInArea(area.id)
        val found=enemies.count { (profile.bestiary[it.id] ?: 0) > 0 }
        return found to enemies.size
    }
    internal fun openCodex(player:Player) {
        val profile=profiles[player.uniqueId] ?: return
        val menu=RpgMenu("codex-areas","").inventory
        snapshot.world.areas.values.sortedBy { it.name }.take(45).forEachIndexed { slot,area ->
            val (found,total)=areaCodexPercent(profile,area)
            menu.setItem(slot,ItemStack(Material.FILLED_MAP).apply { itemMeta=itemMeta.apply {
                displayName(Component.text(area.name)); lore(listOf(Component.text("発見: $found/$total (${if(total==0) 0 else found*100/total}%)")))
            } })
        }
        codexTabs(menu)
        player.openInventory(menu)
    }
    internal fun openCodexArea(player:Player,areaId:String) {
        val profile=profiles[player.uniqueId] ?: return
        val area=snapshot.world.areas[areaId] ?: return openCodex(player)
        val menu=RpgMenu("codex-area",areaId).inventory
        snapshot.world.enemiesInArea(areaId).take(45).forEachIndexed { slot,enemy ->
            val count=profile.bestiary[enemy.id] ?: 0
            val discovered=count > 0
            val drops=snapshot.documents[enemy.id]?.get("droptable") as? String
            val dropIds=drops?.let(snapshot.drops::find)?.entries?.map { it.item }.orEmpty()
            menu.setItem(slot,ItemStack(if(discovered) Material.BLAZE_POWDER else Material.GRAY_DYE).apply { itemMeta=itemMeta.apply {
                displayName(Component.text(if(discovered) enemy.name else "未発見の魔物"))
                lore(if(discovered) listOf(Component.text("討伐数: $count"),Component.text("弱点: ${enemy.weaknesses.joinToString().ifEmpty { "なし" }}"),Component.text("ドロップ: ${dropIds.joinToString()}")) else listOf(Component.text("このエリアで魔物を探しましょう。")))
            } })
        }
        menu.setItem(53,ItemStack(Material.ARROW).apply { itemMeta=itemMeta.apply { displayName(Component.text("エリア一覧へ戻る")) } }); codexTabs(menu)
        player.openInventory(menu)
    }
    internal fun openCodexItems(player:Player) {
        val profile=profiles[player.uniqueId] ?: return
        val menu=RpgMenu("codex-items","").inventory
        profile.discoveredItems.sorted().take(45).forEachIndexed { slot,id ->
            val name=snapshot.documents[id]?.get("name") as? String ?: id
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
    internal fun openCodexFish(player:Player) {
        val profile=profiles[player.uniqueId] ?: return
        val menu=RpgMenu("codex-fish","").inventory
        snapshot.world.fishSpecies.values.sortedBy { it.name }.take(45).forEachIndexed { slot,fish ->
            val record=profile.fishRecords[fish.id]; val discovered=record?.count?.let { it>0 }==true
            menu.setItem(slot,ItemStack(if(discovered) Material.COD else Material.GRAY_DYE).apply { itemMeta=itemMeta.apply {
                displayName(Component.text(if(discovered) fish.name else "未発見の魚"))
                lore(if(record!=null) listOf(Component.text("釣果: ${record.count}匹"),Component.text("最大: ${record.maxCm}cm / ${record.crown}"),Component.text(fish.loreHint)) else listOf(Component.text("釣り場で竿を出して探しましょう。")))
            } })
        }
        codexTabs(menu); player.openInventory(menu)
    }
    internal fun openCodexGathering(player:Player) {
        val profile=profiles[player.uniqueId] ?: return
        val menu=RpgMenu("codex-gathering","").inventory
        snapshot.world.gatheringNodes.values.sortedBy { it.name }.take(45).forEachIndexed { slot,node ->
            val count=profile.gatheringRecords[node.id] ?: 0
            menu.setItem(slot,ItemStack(if(count>0) Material.IRON_ORE else Material.GRAY_DYE).apply { itemMeta=itemMeta.apply {
                displayName(Component.text(if(count>0) node.name else "未発見の採取地"))
                lore(if(count>0) listOf(Component.text("採取回数: $count"),Component.text("種別: ${node.kind} / 必要道具 T${node.minToolTier}")) else listOf(Component.text("専用道具で採取地を調べましょう。")))
            } })
        }
        codexTabs(menu); player.openInventory(menu)
    }
    internal fun openEnhancement(player:Player) {
        val stack=player.inventory.itemInMainHand
        val definition=item(stack) ?: error("強化できる冒険者装備を手に持ってください。")
        require(stack.amount == 1 && definition.slot in setOf(GearSlot.HAND,GearSlot.HEAD,GearSlot.CHEST,GearSlot.LEGS,GearSlot.FEET)) { "装備品を1個だけ手に持ってください。" }
        require(definition.canEquip(profiles[player.uniqueId] ?: error("プロフィールを読み込み中です。"))) { "この装備は現在の職業・レベルでは使用できません。" }
        val level=stack.itemMeta.persistentDataContainer.get(enhancementKey,PersistentDataType.INTEGER) ?: 0
        val tuning=snapshot.world.tuning
        val menu=RpgMenu("enhance",definition.id,level.toString()).inventory
        menu.setItem(4,stack.clone())
        menu.setItem(13,ItemStack(Material.ANVIL).apply { itemMeta=itemMeta.apply {
            displayName(Component.text("+${level+1} に強化"))
            lore(listOf(Component.text("必要素材: ${tuning.enhancementMaterial} ×1"),Component.text("費用: ${tuning.enhancementBaseCurrency*(level+1)} G"),Component.text("上限: +${tuning.enhancementCap}")))
        } })
        player.openInventory(menu)
    }
    internal fun enhance(player:Player,menu:RpgMenu) {
        val p=profiles[player.uniqueId] ?: error("プロフィールを読み込み中です。")
        val stack=player.inventory.itemInMainHand
        require(identify(stack) == menu.contentId) { "強化対象の装備が変わりました。もう一度開いてください。" }
        val definition=item(stack) ?: error("強化対象の装備が見つかりません。")
        val current=stack.itemMeta.persistentDataContainer.get(enhancementKey,PersistentDataType.INTEGER) ?: 0
        require(current == menu.nodeId.toInt()) { "強化段階が更新されています。もう一度開いてください。" }
        val tuning=snapshot.world.tuning
        require(current < tuning.enhancementCap) { "強化上限です。" }
        val cost=Math.multiplyExact(tuning.enhancementBaseCurrency,(current+1).toLong())
        val next=definition.enhancedStats(current+1,snapshot.balance,tuning.enhancementRate)
        val now=definition.enhancedStats(current,snapshot.balance,tuning.enhancementRate)
        require(next != now) { "装備のステータス予算上限に達しています。" }
        val flight=EnhancementFlight(UUID.randomUUID().toString(),tuning.enhancementMaterial,cost)
        adventure.transaction(player,{ inventory ->
            require(p.currency >= cost) { "所持金が足りません。" }
            require(inventory.exchange(mapOf(tuning.enhancementMaterial to 1),emptyMap())) { "強化素材が足りません。" }
            p.currency -= cost
            p.bestEnhancementLevel=maxOf(p.bestEnhancementLevel,current+1)
            game.quests.event(p,ObjectiveKind.ENHANCE_GEAR,(current+1).toString(),1,inventory)
        },beforePersist={
            val held=player.inventory.itemInMainHand
            require(identify(held) == definition.id && (held.itemMeta.persistentDataContainer.get(enhancementKey,PersistentDataType.INTEGER) ?: 0) == current) { "強化対象の装備が変わりました。費用は発生しません。" }
            val meta=held.itemMeta
            meta.persistentDataContainer.set(enhancementKey,PersistentDataType.INTEGER,current+1)
            meta.persistentDataContainer.set(enhancementTransactionKey,PersistentDataType.STRING,flight.token)
            meta.lore((meta.lore() ?: emptyList()).filterNot { it.toString().contains("強化段階") }+Component.text("強化段階 +${current+1}"))
            held.itemMeta=meta; player.inventory.setItemInMainHand(held)
            player.saveData()
            enhancementFlights[player.uniqueId]=flight
        },success={
            if(settleEnhancement(player,p)) {
                syncTitleAdvancements(player,p)
                applyStats(player,p)
                player.sendMessage(Component.text("装備を +${current+1} に強化しました。"))
            } else player.sendMessage(Component.text("強化対象が持ち物から見つからず、費用を返却しました。"))
        })
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false) fun codexClick(e:InventoryClickEvent) {
        val player=e.whoClicked as? Player ?: return
        val menu=e.view.topInventory.holder as? RpgMenu ?: return
        if(!menu.kind.startsWith("codex-") && menu.kind != "enhance") return
        e.isCancelled=true
        if(e.rawSlot !in 0 until e.view.topInventory.size) return
        if(menu.kind == "enhance") { if(e.rawSlot == 13) runCatching { enhance(player,menu) }.onFailure { player.sendMessage(Component.text("強化できません: ${it.message}")) }; return }
        when(menu.kind) {
            "codex-areas" -> when(e.rawSlot) { 51 -> openCodexFish(player); 52 -> openCodexGathering(player); 53 -> openCodexItems(player); else -> snapshot.world.areas.values.sortedBy { it.name }.getOrNull(e.rawSlot)?.let { openCodexArea(player,it.id) } }
            "codex-area","codex-items" -> when(e.rawSlot) { 50,53 -> openCodex(player); 51 -> openCodexFish(player); 52 -> openCodexGathering(player) }
            "codex-fish" -> when(e.rawSlot) { 50,51 -> openCodex(player); 52 -> openCodexGathering(player); 53 -> openCodexItems(player) }
            "codex-gathering" -> when(e.rawSlot) { 50,52 -> openCodex(player); 51 -> openCodexFish(player); 53 -> openCodexItems(player) }
        }
    }
    private fun tick() {
        game.skills.cleanup()
        val now=System.nanoTime()/1_000_000; procReady.entries.removeIf { it.value <= now }; defenseBuffs.entries.removeIf { it.value.second <= now }; basicAttacks.cleanup()
        if(::adventure.isInitialized) adventure.tick()
        if(::dungeons.isInitialized) dungeons.tick()
        if(::gathering.isInitialized) { /* its bounded 2-tick controller owns the life-skill HUD */ }
        server.onlinePlayers.forEach { p ->
          try {
            val profile = profiles[p.uniqueId] ?: return@forEach
            syncTitleAdvancements(p,profile)
            val c = profile.classId?.let { content.snapshot().classes.find(it) } ?: return@forEach
            if(adventure.locked(p.uniqueId)) return@forEach
            val gatheringActive=::gathering.isInitialized && gathering.active(p.uniqueId)
            val fishingActive=::fishing.isInitialized && fishing.active(p.uniqueId)
            val s = effectiveStats(p,profile); applyStats(p,profile); applyTitle(p,profile)
            val beforeResource = profile.resource
            game.regenerate(profile,s.resource,Bukkit.getCurrentTick()-(lastMoved[p.uniqueId] ?: Int.MIN_VALUE/2) < 20)
            if(profile.resource != beforeResource) dirty.add(p.uniqueId)
            profile.selectedSkill = profile.selectedSkill.coerceIn(0,c.skills.lastIndex)
            val skill = c.skills[profile.selectedSkill]
            if(shouldSendClassHud(gatheringActive,fishingActive)) p.sendActionBar(Component.text("HP ${p.health.toInt()}/${s.hp.toInt()}  ${when(c.resource){ResourceKind.RAGE -> "怒り";ResourceKind.FOCUS -> "集中";ResourceKind.MANA -> "マナ"}} ${profile.resource.toInt()}/${s.resource.toInt()}  ${content.snapshot().skills[skill].name} CD ${game.skills.remaining(p.uniqueId,skill)/1000.0}秒  仲間 ${adventure.parties.members(p.uniqueId).size}人"))
          } catch(error: Exception) { logger.warning("HUD更新失敗 ${p.uniqueId}: ${error.message}") }
        }
        if(++autosaveSeconds >= 60) {
            autosaveSeconds = 0; dirty.toList().forEach { profiles[it]?.let(::save) }; dirty.clear()
        }
    }
    private fun cycle(p: Player) {
        val profile = profiles[p.uniqueId] ?: return
        val c = profile.classId?.let { content.snapshot().classes.find(it) } ?: return
        profile.selectedSkill = (profile.selectedSkill+1)%c.skills.size; dirty.add(p.uniqueId)
        p.sendMessage(Component.text("選択: ${content.snapshot().skills[c.skills[profile.selectedSkill]].name}"))
    }
    @EventHandler fun swap(e: PlayerSwapHandItemsEvent) { if(::fishing.isInitialized && fishing.active(e.player.uniqueId)) { e.isCancelled=true; return }; e.isCancelled = true; cycle(e.player) }
    @EventHandler fun drop(e: PlayerDropItemEvent) { if(isSkill(e.itemDrop.itemStack) || e.player.uniqueId !in profiles) e.isCancelled = true }
    @EventHandler fun interact(e: PlayerInteractEvent) {
        if(e.hand != EquipmentSlot.HAND || !e.action.isRightClick) return
        val p = e.player; val profile = profiles[p.uniqueId] ?: run { e.isCancelled = true; return }
        if(p.isSneaking && p.inventory.heldItemSlot == 8) { e.isCancelled = true; cycle(p); return }
        if(e.clickedBlock?.type?.isInteractable == true && !p.isSneaking) return
        if(adventure.locked(p.uniqueId)) { e.isCancelled=true; return }
        if(adventure.consume(p,p.inventory.itemInMainHand)) { e.isCancelled=true; return }
        val weapon = item(p.inventory.itemInMainHand)?.takeIf { it.slot == GearSlot.HAND } ?: return
        e.isCancelled = true
        if(!weapon.canEquip(profile)) { p.sendMessage(Component.text("この装備の条件を満たしていません。")); return }
        val c = profile.classId?.let { content.snapshot().classes.find(it) } ?: return
        val skill = content.snapshot().skills[c.skills[profile.selectedSkill.coerceIn(0,c.skills.lastIndex)]]
        val target = p.world.rayTraceEntities(p.eyeLocation,p.eyeLocation.direction,skill.range,0.8) { it is LivingEntity && it !is Player && it.persistentDataContainer.has(enemyKey) }?.hitEntity as? LivingEntity
        if(target != null && !p.hasLineOfSight(target)) { p.sendMessage(Component.text("魔物との間に障害物があります。")); return }
        val result=castSkill(p,target)
        if(result != CastResult.SUCCESS) p.sendMessage(Component.text(when(result) { CastResult.RESOURCE -> "リソースが足りません。"; CastResult.INVALID_TARGET -> "射程内に魔物がいません。"; else -> "まだ使用できません。" }))
    }
    /** Shared actual/MockBukkit cast path; targeting can be supplied without world ray tracing. */
    fun castSkill(player: Player,target: LivingEntity?): CastResult {
        val profile=profiles[player.uniqueId] ?: return CastResult.INVALID_TARGET
        if(adventure.locked(profile.uuid)) return CastResult.COOLDOWN
        val weapon=item(player.inventory.itemInMainHand)?.takeIf { it.slot == GearSlot.HAND && it.canEquip(profile) } ?: return CastResult.INVALID_TARGET
        val c=profile.classId?.let { snapshot.classes.find(it) } ?: return CastResult.INVALID_TARGET
        val skill=snapshot.skills[c.skills[profile.selectedSkill.coerceIn(0,c.skills.lastIndex)]]
        if(target == null && skill.heal <= 0 && skill.defenseBuff <= 0 && skill.tauntTicks <= 0) return CastResult.INVALID_TARGET
        if(target != null && (target.world != player.world || target.location.distanceSquared(player.location) > skill.range*skill.range || !target.persistentDataContainer.has(enemyKey))) return CastResult.INVALID_TARGET
        if(target != null && enemies.state(target.uniqueId)?.participants?.let { it.isNotEmpty() && player.uniqueId !in it } == true) return CastResult.INVALID_TARGET
        val result=game.skills.cast(profile,skill)
        if(result != CastResult.SUCCESS) return result
        if(skill.heal > 0 || skill.defenseBuff > 0) {
            val recipientIds=if((skill.partyHeal || skill.partyDefenseBuff) && skill.allyRadius > 0) adventure.parties.members(player.uniqueId) else setOf(player.uniqueId)
            val recipients=player.world.players.filter { it.uniqueId in recipientIds && it.location.distanceSquared(player.location) <= skill.allyRadius*skill.allyRadius || it.uniqueId == player.uniqueId }
            recipients.forEach { recipient ->
                val recipientProfile=profiles[recipient.uniqueId] ?: return@forEach
                if(skill.heal > 0 && (recipient.uniqueId == player.uniqueId || skill.partyHeal)) recipient.health=(recipient.health+effectiveStats(recipient,recipientProfile).hp*skill.heal).coerceAtMost(recipient.getAttribute(Attribute.MAX_HEALTH)!!.value)
                if(skill.defenseBuff > 0 && (recipient.uniqueId == player.uniqueId || skill.partyDefenseBuff)) defenseBuffs[recipient.uniqueId]=skill.defenseBuff to (System.nanoTime()/1_000_000+skill.buffTicks*50L)
            }
        }
        if(skill.tauntTicks > 0) enemies.taunt(player,skill.radius.coerceAtLeast(skill.allyRadius).coerceAtLeast(skill.range),skill.tauntTicks,skill.threatMultiplier)
        if(target != null) {
            val targets=if(skill.radius > 0) target.getNearbyEntities(skill.radius,skill.radius,skill.radius).filterIsInstance<LivingEntity>().filter { it.persistentDataContainer.has(enemyKey) }+target else listOf(target)
            targets.forEach { hit(player,it,game.skills.multiplier(profile,skill),skill.element,useSkillPower=true) }
        }
        dirty.add(player.uniqueId); return result
    }
    internal fun defenseBuff(player: Player,amount: Double,ticks: Int) { defenseBuffs[player.uniqueId]=amount to (System.nanoTime()/1_000_000+ticks*50L) }
    private val defenseBuffs=mutableMapOf<UUID,Pair<Double,Long>>()
    private val procReady=mutableMapOf<Triple<UUID,String,Int>,Long>()
    internal fun procEffects(player: Player,target: LivingEntity) {
        val profile=profiles[player.uniqueId] ?: return
        if(!proccing.add(player.uniqueId)) return
        try {
        val equipped=equippedDefinitions(player,profile).filter { it.canEquip(profile) }
        val now=System.nanoTime()/1_000_000
        equipped.forEach { item -> snapshot.world.itemProperties[item.id]?.effects?.forEachIndexed { index,effect ->
            if(effect.kind !in setOf("ATTACK_PROC","DEFENSE_BUFF","HEAL","RESOURCE")) return@forEachIndexed
            val key=Triple(player.uniqueId,item.id,index)
            if(now < procReady.getOrDefault(key,0) || kotlin.random.Random.nextDouble() >= effect.chance) return@forEachIndexed
            procReady[key]=now+effect.cooldown.coerceAtLeast(1)
            if(effect.kind == "ATTACK_PROC" && !target.isDead) hit(player,target,0.0,effect.element,effect.amount)
            if(effect.kind == "DEFENSE_BUFF") defenseBuff(player,effect.amount,effect.duration)
            if(effect.kind == "HEAL") player.health=(player.health+effect.amount).coerceAtMost(player.getAttribute(Attribute.MAX_HEALTH)!!.value)
            if(effect.kind == "RESOURCE") { profile.resource=(profile.resource+effect.amount).coerceAtMost(effectiveStats(player,profile).resource); dirty.add(profile.uuid) }
        } }
        } finally { proccing.remove(player.uniqueId) }
    }
    private val casting = mutableSetOf<UUID>()
    private val proccing = mutableSetOf<UUID>()
    private val basicAttacks=ActionGate(Clock { System.nanoTime()/1_000_000 })
    private fun hit(p: Player,target: LivingEntity,multiplier: Double,element: String = "PHYSICAL",flat: Double? = null,useSkillPower: Boolean = false) {
        val profile = profiles[p.uniqueId] ?: return
        val d = target.persistentDataContainer.get(enemyKey,PersistentDataType.STRING)?.let { content.snapshot().documents[it] } ?: return
        val s = effectiveStats(p,profile)
        val enemy=snapshot.world.enemies[d.string("id")]
        val elementMod=when { element in (enemy?.weaknesses ?: emptySet()) -> snapshot.world.tuning.weakness; element in (enemy?.resistances ?: emptySet()) -> snapshot.world.tuning.resistance; else -> 1.0 }
        val amount = content.snapshot().balance.damage(if(flat == null) s else Stats.ZERO,d.num("defense"),multiplier*(if(useSkillPower) s.skillPower else 1.0),flat ?: snapshot.world.itemProperties[item(p.inventory.itemInMainHand)?.id]?.weapon ?: 0.0,flat == null && kotlin.random.Random.nextDouble() < s.crit.coerceIn(0.0,content.snapshot().balance.critCap),elementMod)
        casting.add(p.uniqueId)
        try { target.damage(amount,p) } finally { casting.remove(p.uniqueId) }
    }
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true) fun damage(e: EntityDamageByEntityEvent) {
        val target = e.entity as? LivingEntity ?: return
        val attacker = (e.damager as? Player) ?: ((e.damager as? Projectile)?.shooter as? Player)
        if(attacker != null && attacker.uniqueId !in casting && target.persistentDataContainer.has(enemyKey)) {
            e.isCancelled = true
            basicAttack(attacker,target)
        } else if(attacker != null && attacker.uniqueId in casting) {
            setRpgDamage(e,e.damage)
        } else if(target is Player && e.damager.persistentDataContainer.has(enemyKey)) {
            val profile = profiles[target.uniqueId] ?: run { e.isCancelled = true; return }
            val d = content.snapshot().documents[e.damager.persistentDataContainer.get(enemyKey,PersistentDataType.STRING)] ?: return
            setRpgDamage(e,content.snapshot().balance.damage(Stats(attack=d.num("attack")),effectiveStats(target,profile).defense,if(::enemies.isInitialized) enemies.multiplier(e.damager.uniqueId) else 1.0,0.0,false))
        }
    }
    /** Same gate/damage path as the actual player event, callable without a client attack packet. */
    fun basicAttack(player: Player,target: LivingEntity): Boolean {
        val profile=profiles[player.uniqueId] ?: return false
        if(profile.classId == null || adventure.locked(profile.uuid) || !target.persistentDataContainer.has(enemyKey)) return false
        if(item(player.inventory.itemInMainHand)?.canEquip(profile) == false) return false
        if(!basicAttacks.accept(profile.uuid,snapshot.world.tuning.basicAttackCooldown)) return false
        hit(player,target,1.0); return true
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) fun guardian(e: EntityDamageEvent) {
        val player=e.entity as? Player ?: return
        if(e.finalDamage < player.health) return
        val profile=profiles[player.uniqueId] ?: return
        val now=System.nanoTime()/1_000_000
        for(item in equippedDefinitions(player,profile).filter { it.canEquip(profile) }) {
            for((index,effect) in snapshot.world.itemProperties[item.id]?.effects.orEmpty().withIndex()) {
                if(effect.kind != "REVIVE") continue
                val key=Triple(player.uniqueId,item.id,index)
                if(now < procReady.getOrDefault(key,0) || kotlin.random.Random.nextDouble() >= effect.chance) continue
                procReady[key]=now+effect.cooldown.coerceAtLeast(1); e.isCancelled=true
                player.health=effect.amount.coerceIn(1.0,player.getAttribute(Attribute.MAX_HEALTH)!!.value)
                player.sendMessage(Component.text("装備の加護が命を守りました！")); return
            }
        }
    }
    @Suppress("DEPRECATION")
    private fun setRpgDamage(e: EntityDamageByEntityEvent, amount: Double) {
        e.damage = amount
        EntityDamageEvent.DamageModifier.entries.filter { it != EntityDamageEvent.DamageModifier.BASE }.forEach { modifier ->
            if(e.isApplicable(modifier)) e.setDamage(modifier,if(modifier == EntityDamageEvent.DamageModifier.ABSORPTION) -minOf(amount,(e.entity as LivingEntity).absorptionAmount) else 0.0)
        }
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) fun successfulHit(e: EntityDamageByEntityEvent) {
        val p = (e.damager as? Player) ?: ((e.damager as? Projectile)?.shooter as? Player) ?: return
        if(e.finalDamage > 0 && e.entity.persistentDataContainer.has(enemyKey)) profiles[p.uniqueId]?.let { game.onHit(it,effectiveStats(p,it).resource); dirty.add(p.uniqueId) }
    }
    @EventHandler fun move(e: PlayerMoveEvent) {
        if(e.from.world != e.to.world || e.from.distanceSquared(e.to) > 0.0) lastMoved[e.player.uniqueId] = Bukkit.getCurrentTick()
        val safeAreas=snapshot.world.areas.values
        val to=e.to
        if(enteredSafeArea(Position(e.from.world.name,e.from.x,e.from.y,e.from.z),Position(to.world.name,to.x,to.y,to.z),safeAreas)) {
            DemoWorld.clearNearbySafeHostiles(this,to.world,to)
        }
    }
    @EventHandler fun split(e: SlimeSplitEvent) { if(e.entity.persistentDataContainer.has(enemyKey)) e.isCancelled = true }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    fun suppressVanillaHostilesInSafeAreas(e:CreatureSpawnEvent) {
        val world=e.location.world
        if(world.name!=DemoWorld.NAME || e.entity !is Monster || e.spawnReason.name=="CUSTOM") return
        if(!runCatching { DemoWorld.isOwned(world) }.getOrDefault(false)) return
        val location=e.location
        val position=Position(world.name,location.x,location.y,location.z)
        if(shouldSuppressVanillaHostileSpawn(e.spawnReason.name,true,position,snapshot.world.areas.values)) e.isCancelled=true
    }
    private fun spawnEnemy(player: Player,id: String) { require(enemies.spawn(id,player.location) != null) { "同じ魔物が既に存在するか、出現上限に達しています。" } }
    @EventHandler fun death(e: EntityDeathEvent) {
        if(!e.entity.persistentDataContainer.has(enemyKey)) return
        e.drops.clear(); e.droppedExp=0
        val killer=e.entity.killer ?: if(::enemies.isInitialized) enemies.killer(e.entity.uniqueId) else null
        if(killer != null) rewardKill(killer,e.entity)
        if(::dungeons.isInitialized) dungeons.defeated(e.entity.uniqueId)
    }
    /** QA seam shared with the actual death handler; avoids relying on mock getKiller(). */
    fun rewardKill(killer: Player,entity: LivingEntity) {
        val id=entity.persistentDataContainer.get(enemyKey,PersistentDataType.STRING) ?: return
        val definition=snapshot.documents[id] ?: return
        val eligible=adventure.parties.members(killer.uniqueId).mapNotNull(Bukkit::getPlayer).filter { p ->
            p.world == entity.world && p.location.distanceSquared(entity.location) <= snapshot.world.tuning.partyRadius*snapshot.world.tuning.partyRadius && p.uniqueId in profiles
        }
        val ids=eligible.map { it.uniqueId }.toSet(); val xp=adventure.parties.splitXp(definition.long("xp"),ids); val currency=adventure.parties.splitXp(definition.long("currency"),ids)
        val loot=adventure.parties.lootRecipient(ids,lootSequence++)
        eligible.forEach { member ->
            val playerId=member.uniqueId
            adventure.whenAvailable(playerId) {
                val player=Bukkit.getPlayer(playerId) ?: return@whenAvailable
                val profile=profiles[playerId] ?: return@whenAvailable
                var codexUnlocks=emptySet<String>()
                var drops=emptyMap<String,Int>()
                adventure.transaction(player,{ inv ->
                    codexUnlocks=profile.recordEnemyKill(id,snapshot.world.areas,snapshot.world.enemies)
                    drops=game.kill(profile,id,inv,xp[playerId] ?: 0,currency[playerId] ?: 0,playerId == loot)
                    drops.forEach { (item,amount) -> profile.pendingRewards.merge(item,amount,Int::plus) }
                }) {
                    player.sendMessage(Component.text("魔物を討伐！ レベル ${profile.level} / 経験値 ${profile.xp}"))
                    if(codexUnlocks.isNotEmpty()) player.sendMessage(Component.text("図鑑の発見率が上がりました: ${codexUnlocks.joinToString()}"))
                }
            }
        }
    }    private var lootSequence=0L
    @EventHandler fun playerDeath(e: PlayerDeathEvent) { e.drops.removeIf(::isSkill) }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) fun itemPickup(e:EntityPickupItemEvent) {
        val player=e.entity as? Player ?: return
        val profile=profiles[player.uniqueId] ?: return
        identify(e.item.itemStack)?.let { if(profile.discoverItem(it)) save(profile) }
    }
    @EventHandler(priority=EventPriority.LOWEST) fun respawn(e: PlayerRespawnEvent) {
        Bukkit.getWorld(DemoWorld.NAME)?.let { e.respawnLocation=DemoWorld.luminaSpawn(it) }
        sync { skillSlot(e.player); profiles[e.player.uniqueId]?.let { applyStats(e.player,it); e.player.health=e.player.getAttribute(Attribute.MAX_HEALTH)!!.value } }
    }
    private fun validatePaperContent(c: ContentSnapshot) {
        fun validate(id: String, action: () -> Unit) { try { action() } catch(e: Exception) { throw IllegalArgumentException("${c.sources[id]} [$id]: ${e.message}",e) } }
        c.items.all().forEach { item -> validate(item.id) { require(Material.valueOf(item.material).isItem) { "material はアイテムである必要があります。" } } }
        c.classes.all().filter { it.icon != null }.forEach { definition -> validate(definition.id) { require(Material.valueOf(definition.icon!!).isItem) { "icon はアイテム素材である必要があります。" } } }
        c.world.titles.values.filter { it.advancement != null }.forEach { title -> validate(title.id) { require(Material.valueOf(title.advancement!!.icon).isItem) { "進捗 icon はアイテム素材である必要があります。" } } }
        c.world.gatheringNodes.values.forEach { node -> validate(node.id) { require(Material.valueOf(node.displayMaterial).isBlock && Material.valueOf(node.depletedMaterial).isBlock) { "採取ノードの表示素材はブロックである必要があります。" } } }
        c.documents.values.filter { it["type"] in setOf("enemy","boss") }.forEach { d -> validate(d.string("id")) { require(EntityType.valueOf(d.string("entity")).isAlive) { "entity は生物である必要があります。" } } }
    }
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if(!ready) { sender.sendMessage(Component.text("冒険システムは起動中です。しばらくお待ちください。")); return true }
        if(sender is Player && args.firstOrNull() in setOf("quest","party","equipment","dungeon","shop","claim","title","codex","enhance","fish","gather")) {
            try { playerCommand(sender,args.toList()) } catch(error: Exception) { sender.sendMessage(Component.text("操作できません: ${error.message}")) }
            return true
        }
        if(!sender.hasPermission("etherealchronicle.admin")) { sender.sendMessage(Component.text("権限がありません。")); return true }
        try {
            when(args.firstOrNull()) {
                "reload", "validate" -> {
                    val reload = args[0] == "reload"
                    CompletableFuture.supplyAsync { content.load().also(::validatePaperContent) }.whenComplete { snapshot,error -> sync {
                        if(error != null) sender.sendMessage(Component.text("検証失敗: ${error.cause?.message ?: error.message}"))
                        else {
                            if(reload) {
                                if(adventure.hasTransactions() || choosing.isNotEmpty() || dungeons.activeCount() > 0) { sender.sendMessage(Component.text("取引・職業選択・聖堂攻略中は再読み込みできません。")); return@sync }
                                val all = profiles.values + simulated.values
                                if(all.any { p -> p.classId?.let { snapshot.classes.find(it) == null } == true || p.quests.any { (id,q) -> !q.completed && snapshot.documents[id]?.get("type") != "quest" } }) {
                                    sender.sendMessage(Component.text("使用中の職業または依頼を削除する変更は再読み込みできません。")); return@sync
                                }
                                content.install(snapshot); game.skills.updateBalance(snapshot.balance)
                                all.forEach { p -> p.level=p.level.coerceAtMost(snapshot.balance.levelCap); if(p.level == snapshot.balance.levelCap) p.xp=0; p.skillLevels.replaceAll { _,level -> level.coerceIn(1,snapshot.balance.skillLevelCap) } }
                                dirty.addAll(profiles.keys)
                                runCatching { reloadAdvancementPack() }.onFailure { advancementWarning(it) }
                                enemies.close(); adventure.reloadNpcs()
                                if(::gathering.isInitialized) gathering.reload()
                                if(::fishing.isInitialized) fishing.reload()
                    if(::treasures.isInitialized) treasures.reload()
                            }
                            sender.sendMessage(Component.text(if(reload) "コンテンツを再読み込みしました。" else "コンテンツ検証成功。"))
                        }
                    } }
                }
                "world" -> {
                    val p=(args.getOrNull(1)?.let(server::getPlayerExact) ?: sender as? Player) ?: error("プレイヤーを指定してください。")
                    p.teleport(Bukkit.getWorld(DemoWorld.NAME)?.spawnLocation ?: error("管理対象ワールドがありません。"))
                }
                "currency" -> {
                    require(args.size == 3) { "/ec currency <player> <balance>" }
                    val p=server.getPlayerExact(args[1]) ?: error("プレイヤーが見つかりません。")
                    val profile=profiles[p.uniqueId] ?: error("読み込み中です。")
                    require(!adventure.locked(profile.uuid)); val balance=args[2].toLong(); require(balance in 0..snapshot.world.tuning.maxCurrency)
                    profile.currency=balance; save(profile); logger.info("管理者回復: ${sender.name} -> ${p.name} 所持金 $balance")
                    sender.sendMessage(Component.text("所持金を設定しました。"))
                }
                "flag" -> {
                    require(args.size in 3..4) { "/ec flag <player> <flag> [set|clear]" }
                    val p=server.getPlayerExact(args[1]) ?: error("プレイヤーが見つかりません。")
                    val profile=profiles[p.uniqueId] ?: error("プロフィールを読み込み中です。")
                    require(!adventure.locked(profile.uuid)) { "取引処理中のため変更できません。" }
                    val flag=args[2]; require(Regex("ec:flag_[a-z][a-z0-9_]*").matches(flag)) { "フラグIDの形式が正しくありません。" }
                    val operation=args.getOrNull(3) ?: "set"
                    require(operation in setOf("set","clear")) { "操作は set または clear を指定してください。" }
                    val declared=snapshot.world.chapters.values.flatMap { it.strings("flags") }.toSet()
                    require(flag in declared || flag in profile.flags) { "章に定義されていないフラグです: $flag" }
                    val changed=if(operation == "set") profile.flags.add(flag) else profile.flags.remove(flag)
                    save(profile)
                    logger.info("管理者フラグ操作: ${sender.name} -> ${p.name} $flag $operation")
                    sender.sendMessage(Component.text(if(changed) "フラグを${if(operation == "set") "設定" else "解除"}しました: $flag" else "フラグは既に${if(operation == "set") "設定" else "解除"}済みです: $flag"))
                }
                "admin" -> {
                    require(args.size == 5 && args[1] == "lifeskill") { "/ec admin lifeskill <player> <FISHING|GATHERING> <level>" }
                    val p=server.getPlayerExact(args[2]) ?: error("プレイヤーが見つかりません。")
                    val profile=profiles[p.uniqueId] ?: error("プロフィールを読み込み中です。")
                    require(!adventure.locked(profile.uuid)) { "取引処理中のため変更できません。" }
                    val kind=LifeSkillKind.valueOf(args[3].uppercase()); val level=args[4].toInt()
                    require(level in 1..snapshot.world.lifeBalance.levelCap) { "生活スキルレベルは1..${snapshot.world.lifeBalance.levelCap}で指定してください。" }
                    profile.lifeSkills[kind]=LifeSkillProgress(level,0); save(profile)
                    sender.sendMessage(Component.text("${kind.name} をレベル $level に設定しました。"))
                }
                "spawn" -> {
                    require(args.size == 3) { "/ec spawn <enemy> <player>" }
                    val p = server.getPlayerExact(args[2]) ?: error("プレイヤーが見つかりません。")
                    spawnEnemy(p,args[1]); sender.sendMessage(Component.text("魔物を召喚しました。"))
                }
                "starter" -> {
                    require(args.size == 2) { "/ec starter <player>" }
                    val p = server.getPlayerExact(args[1]) ?: error("プレイヤーが見つかりません。")
                    val profile = profiles[p.uniqueId] ?: error("読み込み中です。")
                    require(p.uniqueId !in choosing && profile.classId != null && !adventure.locked(p.uniqueId))
                    profile.starterGranted = false; starter(p,profile,profile.classId!!)
                }
                "give" -> {
                    require(args.size in 3..4) { "/ec give <player> <item> [amount]" }
                    val p = server.getPlayerExact(args[1]) ?: error("プレイヤーが見つかりません。")
                    val amount = args.getOrNull(3)?.toInt() ?: 1
                    require(amount in 1..64)
                    require(!adventure.locked(p.uniqueId))
                    require(inventory(p).exchange(emptyMap(),mapOf(args[2] to amount))) { "持ち物に空きがありません。" }
                    sender.sendMessage(Component.text("アイテムを付与しました。"))
                }
                "setlevel" -> {
                    require(args.size == 3)
                    val p = server.getPlayerExact(args[1]) ?: error("プレイヤーが見つかりません。")
                    val profile = profiles[p.uniqueId] ?: error("読み込み中です。")
                    require(!adventure.locked(profile.uuid))
                    val level = args[2].toInt(); require(level in 1..content.snapshot().balance.levelCap)
                    profile.level = level; profile.xp = 0; save(profile)
                    sender.sendMessage(Component.text("レベルを変更しました。"))
                }
                "class" -> {
                    require(args.size == 3)
                    val p = server.getPlayerExact(args[1]) ?: error("プレイヤーが見つかりません。")
                    val profile = profiles[p.uniqueId] ?: error("読み込み中です。")
                    val c = content.snapshot().classes[args[2]]
                    require(!adventure.locked(profile.uuid))
                    game.changeClass(profile,c.id); save(profile); applyStats(p,profile)
                    sender.sendMessage(Component.text("職業を変更しました。"))
                }
                "debug" -> debug(sender,args.drop(1))
                else -> sender.sendMessage(Component.text("/ec reload | validate | give <player> <item> [amount] | setlevel <player> <level> | class <player> <id> | flag <player> <flag> [set|clear] | debug sim …"))
            }
        } catch(e: Exception) { sender.sendMessage(Component.text("操作失敗: ${e.message}")) }
        return true
    }
    private fun playerCommand(player: Player,args: List<String>) {
        val p=profiles[player.uniqueId] ?: error("読み込み中です。")
        require(!adventure.locked(p.uuid)) { "取引の処理中です。" }
        when(args[0]) {
            "codex" -> openCodex(player)
            "enhance" -> openEnhancement(player)
            "fish" -> {
                if(args.getOrNull(1)=="bait") {
                    val value=args.getOrNull(2) ?: error("/ec fish bait <item_id|auto|none>")
                    val text=fishing.selectBait(p,value); save(p); player.sendMessage(Component.text(text))
                } else {
                    val life=p.lifeSkills.getValue(LifeSkillKind.FISHING); val required=LifeSkillEngine(snapshot.world.lifeBalance).requiredXp(life.level)
                    val bait=when(p.fishingBait){"AUTO"->"自動";"NONE"->"なし";else->p.fishingBait}
                    player.sendMessage(Component.text("釣り Lv.${life.level} / 経験値 ${life.xp}/$required / スコア ${FishingEngine(snapshot.world.lifeBalance).score(p)} / 発見 ${p.fishRecords.values.count { it.count>0 }}/${snapshot.world.fishSpecies.size}種 / 餌 $bait"))
                    player.sendMessage(Component.text("/ec fish bait <item_id|auto|none>"))
                }
            }
            "gather" -> {
                val life=p.lifeSkills.getValue(LifeSkillKind.GATHERING); val required=LifeSkillEngine(snapshot.world.lifeBalance).requiredXp(life.level)
                player.sendMessage(Component.text("採取 Lv.${life.level} / 経験値 ${life.xp}/$required / 累計 ${p.gatheringRecords.values.sum()}回 / 発見 ${p.gatheringRecords.count { it.value>0 }}/${snapshot.world.gatheringNodes.size}種"))
            }
            "title" -> {
                syncTitleAdvancements(player,p)
                val id=args.getOrNull(1)
                if(id == null) {
                    snapshot.world.titles.values.forEach { title -> player.sendMessage(Component.text("${if(title.unlockFlag in p.flags) "✓" else "○"} ${title.id}: ${title.name}${if(title.id == p.selectedTitle) " [装備中]" else ""}")) }
                    player.sendMessage(Component.text("/ec title <称号ID|none> で表示を切り替えます。"))
                } else {
                    val selected=if(id == "none") null else id
                    require(p.equipTitle(selected,snapshot.world.titles)) { "称号をまだ解放していません。" }
                    save(p); applyTitle(player,p)
                    player.sendMessage(Component.text(if(selected == null) "称号表示を解除しました。" else "称号を装備しました: ${snapshot.world.titles.getValue(selected).name}"))
                }
            }
            "claim" -> adventure.claimPending(player)
            "equipment" -> adventure.equipment(player)
            "dungeon" -> when(args.getOrNull(1)) {
                "leave" -> dungeons.leave(player)
                "records" -> if(p.challengeRecords.isEmpty()) player.sendMessage(Component.text("試練の記録はまだありません。")) else p.challengeRecords.forEach { (id,record) -> player.sendMessage(Component.text("$id: ${record.bestSeconds}秒 / ランク${record.bestRank}")) }
                "enter" -> dungeons.enter(player,args.getOrNull(2) ?: "ec:dungeon_void_sanctum")
                null -> dungeons.enter(player)
                else -> dungeons.enter(player,args[1])
            }
            "shop" -> {
                val id=args.getOrNull(1) ?: error("/ec shop <id>")
                val npc=snapshot.world.npcs.values.firstOrNull { it.shop == id } ?: error("この店を開くNPCが見つかりません。")
                require(npc.position.world == player.world.name && Location(player.world,npc.position.x,npc.position.y,npc.position.z).distanceSquared(player.location) <= 64) { "お店の近くに移動してください。" }
                adventure.showShop(player,id)
            }
            "quest" -> when(args.getOrNull(1)) {
                "start" -> { require(player.hasPermission("etherealchronicle.admin")) { "依頼はNPCとの会話から受注してください。" }; game.startQuest(p,args.getOrNull(2) ?: error("/ec quest start <id>")); save(p); player.sendMessage(Component.text("依頼を受注しました。")) }
                "complete" -> { require(player.hasPermission("etherealchronicle.admin")) { "依頼はNPCに報告してください。" }; adventure.transaction(player,{ inv -> game.completeQuest(p,args.getOrNull(2) ?: error("/ec quest complete <id>"),inv) }) { player.sendMessage(Component.text("依頼を達成しました！")) } }
                else -> {
                    if(p.quests.isEmpty()) player.sendMessage(Component.text("受注中の依頼はありません。"))
                    p.quests.forEach { (id,q) ->
                        val definition=snapshot.world.quests[id]
                        player.sendMessage(Component.text("${definition?.name ?: id}: ${if(q.completed) "達成済み" else if(game.quests.ready(p,id,inventory(player))) "報告できます" else "進行中"}"))
                        if(!q.completed) definition?.objectives?.forEach { o -> player.sendMessage(Component.text("・${o.target} ${if(o.kind == ObjectiveKind.COLLECT) inventory(player).count(o.target).coerceAtMost(o.amount) else q.objectives.getOrDefault(o.id,0)}/${o.amount}")) }
                    }
                }
            }
            "party" -> when(args.getOrNull(1)) {
                "invite" -> {
                    val target=args.getOrNull(2)?.let(server::getPlayerExact) ?: error("仲間が見つかりません。")
                    adventure.parties.invite(p.uuid,target.uniqueId)
                    target.sendMessage(Component.text("${player.name} からパーティー招待です。/ec party accept（60秒以内）")); player.sendMessage(Component.text("招待を送りました。"))
                }
                "accept" -> { adventure.parties.accept(p.uuid); player.sendMessage(Component.text("パーティーに参加しました。")) }
                "leave" -> { dungeons.leave(player); adventure.parties.leave(p.uuid); player.sendMessage(Component.text("パーティーを離れました。")) }
                else -> player.sendMessage(Component.text("仲間: ${adventure.parties.members(p.uuid).joinToString { Bukkit.getPlayer(it)?.name ?: "離席中" }}"))
            }
        }
    }
    private fun debug(sender: CommandSender, args: List<String>) {
        if(args.firstOrNull()=="fish") {
            val action=args.getOrNull(1) ?: error("/ec debug fish <start|status|cancel> <player> [魚種ID]")
            val playerName=args.getOrNull(2) ?: error("/ec debug fish $action <player> [魚種ID]")
            val player=server.getPlayerExact(playerName) ?: error("プレイヤーが見つかりません: $playerName")
            val message=when(action) {
                "start" -> fishing.debugStart(player,args.getOrNull(3))
                "status" -> fishing.debugStatus(player.uniqueId)
                "cancel" -> if(fishing.debugCancel(player)) "釣りセッションを終了しました。" else "釣りセッションはありません。"
                else -> error("/ec debug fish <start|status|cancel> <player> [魚種ID]")
            }
            sender.sendMessage(Component.text("FISH ${player.name}: $message")); return
        }
        if(args.firstOrNull()=="gather") {
            require(args.getOrNull(1)=="status") { "/ec debug gather status <player>" }
            val playerName=args.getOrNull(2) ?: error("/ec debug gather status <player>")
            val player=server.getPlayerExact(playerName) ?: error("プレイヤーが見つかりません: $playerName")
            sender.sendMessage(Component.text("GATHER ${player.name}: ${gathering.debugStatus(player)}")); return
        }
        require(args.size >= 3 && args[0] == "sim") { "/ec debug sim <操作> <name> [引数] — join/class/kill/skill/accept/event/complete/dialogue/choice/give/inventory/currency/level/flag/buy/sell/party/spawn/hit/dump/save/load" }
        val operation = args[1]; val name = args[2]
        val uuid = UUID.nameUUIDFromBytes("ec:simulation:$name".toByteArray())
        if(operation == "join") {
            require(name !in simulated) { "既に参加しています。" }
            simulated[name] = PlayerProfile(uuid)
            simSessions[name] = SimulationSession(content::snapshot,game)
        } else if(operation == "load") {
            simStore.load(uuid).whenComplete { p,error -> sync {
                if(error != null) sender.sendMessage(Component.text("SIM load 失敗: ${error.message}"))
                else { simulated[name] = p; simSessions[name]=SimulationSession(content::snapshot,game); sender.sendMessage(Component.text("SIM load $name: $p (仮想持ち物はリセット)")) }
            } }; return
        } else {
            val p = simulated[name] ?: error("先に sim join を実行してください。")
            val session=simSessions.getOrPut(name) { SimulationSession(content::snapshot,game) }
            when(operation) {
                "class" -> { game.chooseClass(p,args[3]); session.starter(p) }
                "party" -> {
                    val parties=adventure.parties
                    when(args.getOrNull(3)) {
                        "invite" -> parties.invite(p.uuid,(simulated[args.getOrNull(4)] ?: error("相手が未参加です。")).uuid)
                        "accept" -> parties.accept(p.uuid)
                        "leave" -> parties.leave(p.uuid)
                        "split" -> sender.sendMessage(Component.text("SIM XP配分=${parties.splitXp(args[4].toLong(),parties.members(p.uuid))}"))
                    }
                    sender.sendMessage(Component.text("SIM party=${parties.members(p.uuid)}"))
                }
                "spawn" -> {
                    val world=server.getWorld("ec_world") ?: error("専用ワールドがありません。")
                    val id=args[3]; val definition=snapshot.world.enemies[id] ?: error("魔物が見つかりません。")
                    val pos=definition.spawns.firstOrNull()?.position
                    val location=pos?.let { Location(world,it.x,it.y,it.z) } ?: world.spawnLocation
                    val mob=enemies.spawn(id,location,"sim:$name:$id",1,setOf(p.uuid),pinForSimulation=true) ?: error("同じ魔物が既に存在するか、出現上限に達しています。")
                    sender.sendMessage(Component.text("SIM spawn=${mob.uniqueId} hp=${enemies.state(mob.uniqueId)?.boss?.hp}"))
                }
                "hit" -> {
                    val entity=UUID.fromString(args[3]); val damage=args[4].toDouble(); require(damage.isFinite() && damage > 0 && damage <= snapshot.balance.damageCap) { "ダメージは0より大きく ${snapshot.balance.damageCap} 以下で指定してください。" }
                    val state=enemies.state(entity) ?: error("対象が見つかりません。")
                    require(state.boss.hp > 0) { "対象は討伐済みです。" }
                    require(state.participants.isEmpty() || p.uuid in state.participants) { "参加者が一致しません。" }
                    val killed=enemies.applyVirtualDamage(entity,p.uuid,damage)
                    if(killed) sender.sendMessage(Component.text("SIM ${session.execute(p,"kill",listOf(state.boss.definition.id))}"))
                    sender.sendMessage(Component.text("SIM hit hp=${state.boss.hp} killed=$killed phase=${state.boss.phase()?.id} (実Playerの討伐報酬は発生しません)"))
                }
                "despawn" -> { enemies.removeSimulation(p.uuid); sender.sendMessage(Component.text("SIM 出現対象とチャンク保持を解除しました。")) }
                "skill" -> {
                    val c = content.snapshot().classes[p.classId ?: error("職業未選択")]
                    val skill = content.snapshot().skills[args.getOrNull(3) ?: c.skills[p.selectedSkill]]
                    require(skill.id in c.skills)
                    sender.sendMessage(Component.text("SIM cast: ${game.skills.cast(p,skill)}"))
                }
                "save" -> { simStore.save(p).whenComplete { _,error -> sync { sender.sendMessage(Component.text(if(error == null) "SIM save 成功 $name" else "SIM save 失敗: ${error.message}")) } }; return }
                "dump" -> Unit
                else -> sender.sendMessage(Component.text("SIM ${session.execute(p,operation,args.drop(3))}"))
            }
        }
        sender.sendMessage(Component.text("SIM $operation $name: ${simulated[name]}"))
    }
}
























