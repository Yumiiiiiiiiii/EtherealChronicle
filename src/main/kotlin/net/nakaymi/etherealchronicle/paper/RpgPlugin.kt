package net.nakaymi.etherealchronicle.paper

import net.nakaymi.etherealchronicle.core.*
import net.nakaymi.etherealchronicle.data.*
import net.kyori.adventure.text.Component
import org.bukkit.*
import org.bukkit.attribute.Attribute
import org.bukkit.command.*
import org.bukkit.entity.*
import org.bukkit.event.*
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.*
import org.bukkit.event.inventory.*
import org.bukkit.event.player.*
import org.bukkit.inventory.*
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** Paper APIとpure core/dataサービスを初期化し、プレイヤーイベントを接続するプラグイン入口です。 */
open class RpgPlugin: JavaPlugin(), Listener, CommandExecutor {
    private var ready = false
    private val paperInventoryUi by lazy { !server.javaClass.name.startsWith("org.mockbukkit.") }
    internal val paperUiEnabled get() = paperInventoryUi
    private val codexController by lazy { CodexController(this) }
    private val simulationCommands by lazy { SimulationCommandController(this) }
    private val invUiInventoryAdapter by lazy { InvUiInventoryAdapter(uiTheme) { stack -> item(stack)?.itemModel } }
    internal lateinit var adventure: AdventureController
    internal lateinit var enemies: EnemyController
    internal lateinit var dungeons: DungeonController
    internal lateinit var gathering: GatheringController
    internal lateinit var fishing: FishingController
    internal lateinit var treasures: TreasureController
    internal lateinit var resourcePacks: ResourcePackController
    private lateinit var uiTheme: UiThemeProvider
    private lateinit var classSelectionUi: ClassSelectionUi
    private lateinit var content: ContentService
    @Volatile private lateinit var language: LangCatalog
    private lateinit var store: PlayerStore
    internal lateinit var game: GameService
    internal val profiles = mutableMapOf<UUID,PlayerProfile>()
    private val sessions = mutableMapOf<UUID,UUID>()
    private val choosing = mutableSetOf<UUID>()
    private val lastMoved = mutableMapOf<UUID,Int>()
    private val dirty = mutableSetOf<UUID>()
    private var autosaveSeconds = 0
    private var safeAreaSnapshot: ContentSnapshot? = null
    private var safeAreasByWorld: Map<String, List<Area>> = emptyMap()
    internal lateinit var simStore: PlayerStore
    internal val simulated = mutableMapOf<String,PlayerProfile>()
    internal val simSessions = mutableMapOf<String,SimulationSession>()
    private data class EnhancementFlight(val token:String,val material:String,val currency:Long)
    private val enhancementFlights=mutableMapOf<UUID,EnhancementFlight>()
    private var enhancementLoreLanguage: LangCatalog? = null
    private var enhancementLoreLines: Set<String> = emptySet()
    private var advancementWarningLogged=false
    private lateinit var itemKey: NamespacedKey
    private lateinit var enhancementKey: NamespacedKey
    private lateinit var enhancementTransactionKey: NamespacedKey
    private lateinit var enemyKey: NamespacedKey
    private lateinit var slotKey: NamespacedKey
    private lateinit var starterClassKey: NamespacedKey
    /** 現在の不変コンテンツSnapshotをControllerへ公開します。 */
    internal val snapshot get() = content.snapshot()
    /** コンテンツ版ごとに安全地帯をワールド別へ索引化します。 */
    internal fun safeAreas(world: String): List<Area> {
        val current = snapshot
        if (safeAreaSnapshot !== current) {
            safeAreasByWorld = current.world.areas.values.filter { it.safe }.groupBy { it.world }
            safeAreaSnapshot = current
        }
        return safeAreasByWorld[world].orEmpty()
    }
    /** クライアントlocaleに合わせて翻訳済みComponentを生成します。 */
    internal fun message(player: Player, key: String, vararg values: Pair<String, Any?>): Component =
        language.component(player.locale().toString(), key, values.toMap())
    /** プレイヤー以外の管理コマンドは設定言語、または日本語へフォールバックします。 */
    internal fun message(sender: CommandSender, key: String, vararg values: Pair<String, Any?>): Component =
        language.component((sender as? Player)?.locale()?.toString(), key, values.toMap())
    /** 指定localeのデバッグ表示を翻訳し、対象がオンラインでない場合も設定言語へ戻します。 */
    internal fun message(locale: String?, key: String, vararg values: Pair<String, Any?>): Component =
        language.component(locale, key, values.toMap())
    /** map形式のプレースホルダーを指定localeで翻訳します。 */
    internal fun message(locale: String?, key: String, values: Map<String, Any?>): Component =
        language.component(locale, key, values)
    /** 例外診断中の安定キーを翻訳し、ファイル名やIDなどの診断情報を保ちます。 */
    internal fun diagnostic(sender: CommandSender, text: String): Component =
        language.diagnostic((sender as? Player)?.locale()?.toString(),text)
    private fun plain(component: Component): String =
        net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(component)
    /** クラスの資源種別を表示用の翻訳へ変換します。 */
    private fun resourceName(player: Player,kind: ResourceKind): Component = message(player, when(kind) {
        ResourceKind.RAGE -> "ec.resource.rage"
        ResourceKind.FOCUS -> "ec.resource.focus"
        ResourceKind.MANA -> "ec.resource.mana"
    })
    internal fun persist(profile: PlayerProfile) = store.save(profile)
    internal fun identify(stack: ItemStack?) = stack?.itemMeta?.persistentDataContainer?.get(itemKey,PersistentDataType.STRING)
    internal fun inventory(player: Player,persistRemoval: Boolean = true): PaperInventoryAccess = PaperInventoryAccess(player,::identify,{ id,amount -> createItemForPlayer(id,amount,player) },persistRemoval) { logger.fine("Persisted consumed transaction items: ${player.uniqueId}") }
    internal fun markDirty(player: UUID) { dirty.add(player) }
    /** リソースパック状態に合わせて所持品のモデル指定を付け外しし、未対応クライアントの欠損表示を防ぎます。 */
    internal fun refreshPackItemModels(player: Player, hasPack: Boolean) {
        fun refresh(stack: ItemStack?) {
            val held = stack ?: return
            val definition = item(held) ?: return
            val model = UiThemeProvider.modelKey(hasPack, definition.itemModel)
            held.editMeta { it.setItemModel(model) }
        }
        player.inventory.contents.forEach(::refresh)
        player.inventory.armorContents.forEach(::refresh)
        refresh(player.inventory.itemInOffHand)
    }
    /** YAMLを非同期検証した後、メインスレッドで各ゲームControllerを登録します。 */
    override fun onEnable() {
        saveDefaultConfig()
        itemKey = NamespacedKey(this,"item_id"); enhancementKey=NamespacedKey(this,"enhancement_level"); enhancementTransactionKey=NamespacedKey(this,"enhancement_transaction"); enemyKey = NamespacedKey(this,"enemy_id"); slotKey = NamespacedKey(this,"skill_slot"); starterClassKey=NamespacedKey(this,"starter_class")
        val languageDefaults = listOf("ja_jp.yml", "en_us.yml").associate { name ->
            "lang/$name" to getResource("lang/$name")!!.use { it.readBytes() }
        }
        language = LangCatalog.fromBundledDefaults(config.getString("language", "auto") ?: "auto", logger, languageDefaults)
        // Paperプラグインはライフサイクル登録のみを使い、MockBukkitではテスト用コマンドを登録します。
        if (paperInventoryUi) registerPaperCommand()
        else getCommand("ec")?.setExecutor(this) ?: registerMockCommand()
        server.scheduler.runTaskAsynchronously(this,Runnable {
            try {
                val oldLanguageDefaults = getResource("language-defaults.properties")
                    ?.use { DefaultContentInstaller.manifest(it.readBytes()) } ?: emptyMap()
                val languageInstall = DefaultContentInstaller.installTracked(
                    dataFolder.toPath(), languageDefaults, oldLanguageDefaults, "lang-manifest.properties"
                )
                languageInstall.updated.forEach { logger.info("Updated unchanged bundled language file: $it") }
                languageInstall.changed.forEach { logger.info("Preserving server-edited language file: $it") }
                val loadedLanguage = LangCatalog.load(dataFolder.toPath(), config.getString("language", "auto") ?: "auto", logger)
                val bundled = getResource("content-index.txt")!!.bufferedReader().use { reader ->
                    reader.readLines().filter { it.isNotBlank() }.associate { path ->
                        val clean = path.removePrefix("\uFEFF")
                        clean to getResource(clean)!!.use { it.readBytes() }
                    }
                }
                val legacy=getResource("retired-content.properties")?.use { DefaultContentInstaller.manifest(it.readBytes()) } ?: emptyMap()
                val oldDefaults=getResource("foundation-content.properties")?.use { DefaultContentInstaller.manifest(it.readBytes()) } ?: emptyMap()
                val retired=DefaultContentInstaller.retire(dataFolder.toPath(),bundled.keys,legacy)
                retired.retired.forEach { logger.info("Moved retired bundled content: $it") }
                retired.preserved.forEach { logger.warning("Preserved edited retired content; review its definition: $it") }
                val installation=DefaultContentInstaller.installTracked(dataFolder.toPath(),bundled,oldDefaults)
                installation.updated.forEach { name -> logger.info("Updated unchanged bundled content (old copy saved to content-history): $name") }
                installation.changed.forEach { name ->
                    logger.info("Preserving server-edited content differing from bundled defaults: $name")
                }
                listOf("balance","classes","skills","items","enemies","bosses","npcs","quests","dialogues","chapters","droptables","shops","titles","tackle","fishing_spots","fish_species","gathering_nodes","treasure_chests").forEach { java.nio.file.Files.createDirectories(dataFolder.toPath().resolve("content").resolve(it)) }
                val startup=ContentBootstrap.initialize(dataFolder.toPath(),bundled,::validatePaperContent)
                content=startup.service
                if(startup.usedFallback) logger.warning("Invalid server content; starting with bundled defaults and keeping /ec validate and /ec reload available: ${startup.problem}")
                store = PlayerStore(dataFolder.toPath().resolve("playerdata"))
                simStore = PlayerStore(dataFolder.toPath().resolve("simulation"))
                sync {
                    language = loadedLanguage
                    game = GameService(content::snapshot,SkillEngine(Clock { System.nanoTime()/1_000_000 },content.snapshot().balance))
                    resourcePacks = ResourcePackController(this)
                    if (resourcePacks.shouldRegisterLockListener()) {
                        RequiredPackLockListener(this, resourcePacks::isAwaitingRequiredPack, resourcePacks::hasWaiters)
                    }
                    uiTheme = UiThemeProvider(
                        this,
                        resourcePacks,
                        UiThemeProvider.mode(config.getString("ui.theme.mode", "icons_only")),
                    )
                    classSelectionUi = ClassSelectionUi(
                        theme=uiTheme,
                        title={ p -> message(p,"ec.class.choose") },
                        confirmationTitle={ p,c -> message(p,"ec.class.confirm_title","class" to c.name) },
                        confirmationText={ p,c -> message(p,"ec.class.confirm_body","class" to c.name) },
                        classLore={ p,c ->
                            val skills=c.skills.mapNotNull(content.snapshot().skills::find).joinToString(" / ") { it.name }
                            listOf(
                                message(p,"ec.class.selection.role","resource" to resourceName(p,c.resource)),
                                message(p,"ec.class.selection.stats","hp" to c.stats.hp.toInt(),"attack" to c.stats.attack.toInt(),"defense" to c.stats.defense.toInt()),
                                message(p,"ec.class.selection.skills","skills" to skills),
                            )
                        },
                        confirmLabel={ p -> message(p,"ec.class.confirm_start") },
                        cancelLabel={ p -> message(p,"ec.class.confirm_cancel") },
                        onSelected={ p,id -> profiles[p.uniqueId]?.let { starter(p,it,id) } },
                        onCancel={ p -> menu(p) },
                        onClose={ p -> if(profiles[p.uniqueId]?.classId == null && p.uniqueId !in choosing && !classSelectionUi.isConfirming(p.uniqueId)) sync { if(p.isOnline) menu(p) } },
                    )
                    if (paperInventoryUi) xyz.xenondevs.invui.InvUI.getInstance().setPlugin(this)
                    adventure=AdventureController(this)
                    enemies=EnemyController(this,adventure)
                    dungeons=DungeonController(this,adventure,enemies)
                    try { if(DemoWorld.ensure(this) != null) adventure.reloadNpcs() }
                    catch(error: Exception) { logger.severe("Could not prepare the dedicated world; foundation features remain enabled: ${error.message}") }
                    gathering=GatheringController(this)
                    fishing=FishingController(this)
                    treasures=TreasureController(this).also { it.reload() }
                    runCatching { reloadAdvancementPack() }.onFailure { advancementWarning(it) }
                    server.scheduler.runTaskTimer(this,Runnable { enemies.tick() },10,10)
                    ready = true
                    server.pluginManager.registerEvents(this,this)
                    if (!paperInventoryUi) getCommand("ec")?.setExecutor(this)
                    server.scheduler.runTaskTimer(this,Runnable { tick() },20,20)
                    server.onlinePlayers.forEach { join(it) }
                    logger.info("Adventure systems are ready.")
                }
            } catch(e: Exception) {
                logger.severe("Failed to load content: ${e.message}")
                sync { server.pluginManager.disablePlugin(this) }
            }
        })
    }
    /** Controllerを停止してから保留状態を直列保存し、タスクとプロフィールを閉じます。 */
    override fun onDisable() {
        ready = false
        enhancementFlights.keys.toList().forEach { id ->
            val player=server.getPlayer(id)
            val profile=profiles[id]
            if(player != null && profile != null) settleEnhancement(player,profile)
            else enhancementFlights.remove(id)
        }
        if(::adventure.isInitialized) adventure.prepareShutdown()
        if(::fishing.isInitialized) runCatching { fishing.close() }.onFailure { logger.severe("Fishing shutdown failed: ${it.message}") }
        if(::gathering.isInitialized) runCatching { gathering.close() }.onFailure { logger.severe("Gathering shutdown failed: ${it.message}") }
        if(::dungeons.isInitialized) runCatching { dungeons.shutdown() }.onFailure { logger.severe("Dungeon shutdown failed: ${it.message}") }
        if(::enemies.isInitialized) runCatching { enemies.close() }.onFailure { logger.severe("Enemy shutdown failed: ${it.message}") }
        if(::adventure.isInitialized) runCatching { adventure.close() }.onFailure { logger.severe("NPC shutdown failed: ${it.message}") }
        if(::treasures.isInitialized) runCatching { treasures.close() }.onFailure { logger.severe("Treasure shutdown failed: ${it.message}") }
        if(::resourcePacks.isInitialized) runCatching { resourcePacks.close() }.onFailure { logger.severe("Resource pack shutdown failed: ${it.message}") }
        server.scheduler.cancelTasks(this)
        if (::store.isInitialized) {
            profiles.values.forEach { store.save(it) }
            if (::simStore.isInitialized) runCatching { simStore.close() }.onFailure { logger.severe("Simulation store shutdown failed: ${it.message}") }
            runCatching { store.close() }.onFailure { logger.severe("Player store shutdown failed: ${it.message}") }
        }
        profiles.clear(); sessions.clear()
    }
    /** async callbackのPaper操作を有効なプラグイン期間中だけメインスレッドへ戻します。 */
    internal fun sync(action: () -> Unit) {
        if(!isEnabled) return
        try { server.scheduler.runTask(this,Runnable { if(isEnabled) action() }) }
        catch(_: org.bukkit.plugin.IllegalPluginAccessException) { /* disabled between the check and scheduling */ }
    }
    /** 保存失敗時にdirty状態を戻し、次回autosaveで再試行します。 */
    internal fun save(p: PlayerProfile) { store.save(p).exceptionally { logger.severe("Save failed ${p.uuid}: ${it.message}"); sync { if(p.uuid in profiles) dirty.add(p.uuid) }; null } }
    /** 接続世代tokenで古い非同期loadを捨て、完了したプロフィールだけを反映します。 */
    private fun join(player: Player) {
        if (!ready) return
        val token = UUID.randomUUID(); sessions[player.uniqueId] = token
        // 接続直後から適用結果まで操作を止め、要求送信後のプロフィール処理も待機させます。
        resourcePacks.beginRequiredJoin(player)
        player.sendMessage(message(player,"ec.startup.loading"))
        store.load(player.uniqueId).whenComplete { profile,error -> sync {
            if(sessions[player.uniqueId] != token || !player.isOnline) return@sync
            if(error != null) { logger.severe("Profile load failed: ${error.message}"); player.kick(message(player,"ec.profile.read_failed")); return@sync }
            val snapshot = content.snapshot()
            ProfileContentMigration.apply(profile,snapshot)
            if(profile.classId != null && snapshot.classes.find(profile.classId!!) == null) { player.kick(message(player,"ec.profile.class_data_missing")); return@sync }
            profile.level = profile.level.coerceIn(1,snapshot.balance.levelCap)
            profile.skillLevels.replaceAll { _,level -> level.coerceIn(1,snapshot.balance.skillLevelCap) }
            profile.classId = profile.classId?.let { snapshot.classes[it].id }
            for(slot in 0 until player.inventory.size) {
                val old=player.inventory.getItem(slot); val id=identify(old) ?: continue
                profile.discoverItem(id)
                if(snapshot.items.find(id) == null) ProfileContentMigration.oldItems[id]?.takeIf { snapshot.items.find(it) != null }?.let { player.inventory.setItem(slot,createItem(it,old!!.amount)) }
            }
            resourcePacks.send(player,runCatching { PackPreference.valueOf(profile.resourcePackPreference) }.getOrDefault(PackPreference.AUTO))
            resourcePacks.applyObservedStatus(player,profile)
            resourcePacks.whenRequiredPackReady(player) {
                if (!player.isOnline || sessions[player.uniqueId] != token) return@whenRequiredPackReady
                profiles[player.uniqueId] = profile
                finishLoadedJoin(player,profile)
            }
        } }
    }

    /** 必須パック適用後に、ワールド移動・HUD装備・職業メニューを開始します。 */
    private fun finishLoadedJoin(player: Player, profile: PlayerProfile) {
        syncTitleAdvancements(player,profile)
        if(::dungeons.isInitialized) dungeons.recover(player)
        if(!player.hasPlayedBefore() || profile.classId == null) Bukkit.getWorld(DemoWorld.NAME)?.let { player.teleport(DemoWorld.luminaSpawn(it)) }
        Bukkit.getWorld(DemoWorld.NAME)?.takeIf { player.world==it }?.let { DemoWorld.clearNearbySafeHostiles(this,it,player.location) }
        if(profile.classId == null || !profile.starterGranted) menu(player) else {
            skillSlot(player); applyStats(player,profile)
            adventure.whenAvailable(player.uniqueId) { if(player.isOnline && profiles[player.uniqueId] === profile) adventure.claimPending(player) }
        }
    }
    @EventHandler fun onJoin(e: PlayerJoinEvent) = join(e.player)

    @EventHandler fun onQuit(e: PlayerQuitEvent) {
        if(::adventure.isInitialized) adventure.beforeQuit(e.player)
        if(ready) codexController.clear(e.player.uniqueId)
        sessions.remove(e.player.uniqueId); choosing.remove(e.player.uniqueId)
        if(ready) classSelectionUi.clear(e.player.uniqueId)
        profiles.remove(e.player.uniqueId)?.let { profile -> settleEnhancement(e.player,profile); save(profile) }
        lastMoved.remove(e.player.uniqueId); dirty.remove(e.player.uniqueId)

    }
    private fun menu(player: Player) {
        if (paperInventoryUi) {
            classSelectionUi.open(player,content.snapshot().classes.all().toList())
            return
        }
        // MockBukkitはCraftItemStackを提供しないため、イベント回帰テストだけ旧API表示にします。
        val inventory = ClassMenu(message(player,"ec.class.choose")).inventory
        content.snapshot().classes.all().take(9).forEachIndexed { index, definition ->
            val icon = definition.icon?.let(Material::valueOf) ?: when (definition.resource) {
                ResourceKind.RAGE -> Material.IRON_SWORD
                ResourceKind.FOCUS -> Material.BOW
                ResourceKind.MANA -> Material.BLAZE_ROD
            }
            inventory.setItem(index, ItemStack(icon).apply {
                itemMeta = itemMeta.apply {
                    displayName(Component.text(definition.name))
                    persistentDataContainer.set(itemKey, PersistentDataType.STRING, definition.id)
                }
            })
        }
        player.openInventory(inventory)
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
            choosing.remove(p.uniqueId); p.sendMessage(message(p,"ec.profile.inventory_space")); return
        }
        if(profile.classId == null) game.chooseClass(profile,id)
        profile.starterGranted = true
        // アイテムを配る前に権利消費を保存し、切断・再起動時の二重配布を防ぎます。
        store.save(profile).whenComplete { _,error -> sync {
            choosing.remove(p.uniqueId)
            if(error != null) { profile.starterGranted = false; p.sendMessage(message(p,"ec.profile.save_retry")); return@sync }
            if(!p.isOnline || profiles[p.uniqueId] !== profile) return@sync
            val deferredToMailbox = c.starterItems.size > (0..7).count { p.inventory.getItem(it) == null }
            if(deferredToMailbox) {
                // 先行保存済みのstarterGrantedを維持し、枠不足分は耐久メールボックスへ戻します。
                val counts = c.starterItems.groupingBy { it }.eachCount()
                profile.returnToMailbox(counts)
                counts.keys.forEach(profile::discoverItem)
                save(profile)
                p.sendMessage(message(p,"ec.profile.starter_space"))
            } else {
                c.starterItems.forEach { item ->
                    val slot = (0..7).first { p.inventory.getItem(it) == null }
                    p.inventory.setItem(slot,createStarterItem(item,id,p))
                    profile.discoverItem(item)
                }
                save(profile)
            }
            skillSlot(p); applyStats(p,profile); p.health=p.getAttribute(Attribute.MAX_HEALTH)!!.value
            p.closeInventory()
            if (deferredToMailbox) adventure.claimPending(p)
            else p.sendMessage(message(p,"ec.profile.welcome"))
        } }
    }
    internal fun createItem(id: String, amount: Int = 1): ItemStack = createItemInternal(id, amount, null)

    /** 適用済みリソースパックに合わせたアイテム表現を生成します。 */
    internal fun createItemForPlayer(id: String, amount: Int, packRecipient: Player): ItemStack =
        createItemInternal(id, amount, packRecipient)

    private fun createItemInternal(id: String, amount: Int, packRecipient: Player?): ItemStack {
        val d = content.snapshot().items[id]
        return ItemStack(Material.valueOf(d.material),amount).apply { itemMeta = itemMeta.apply {
            val rarity=language.component(null,d.rarity.displayKey)
            displayName(Component.text(d.name)); lore(listOf(language.component(null,"ec.item.required_level", mapOf("level" to d.minLevel, "rarity" to rarity))))
            snapshot.world.itemProperties[id]?.lore?.takeIf { it.isNotBlank() }?.let { text -> lore((lore() ?: emptyList())+text.chunked(28).map { Component.text(it) }) }
            persistentDataContainer.set(itemKey,PersistentDataType.STRING,id)
            val hasPack=packRecipient?.let { ::resourcePacks.isInitialized && resourcePacks.hasResourcePack(it.uniqueId) } == true
            UiThemeProvider.modelKey(hasPack,d.itemModel)?.let(::setItemModel)
            if(d.material == "POTION") addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ADDITIONAL_TOOLTIP)
        } }
    }
    /** 初期支給品の所属クラスをPDCへ記録し、切替時に既存品と重複付与を区別します。 */
    private fun createStarterItem(id:String,classId:String,player:Player):ItemStack = createItemForPlayer(id,1,player).also { stack ->
        stack.itemMeta=stack.itemMeta.apply { persistentDataContainer.set(starterClassKey,PersistentDataType.STRING,classId) }
    }
    /** クラス切替時に対象クラスの初期武器を手元へ移し、余剰は保持したまま二重付与を避けます。 */
    private fun reconcileStarterWeapon(player:Player,profile:PlayerProfile,oldClassId:String?,newClass:ClassDefinition):Boolean {
        val newWeapon=newClass.starterItems.firstOrNull() ?: return false
        val inventory=player.inventory
        val heldSlot=inventory.heldItemSlot
        val ownedSlot=(0 until inventory.storageContents.size).firstOrNull { slot ->
            slot != 8 && identify(inventory.getItem(slot)) == newWeapon
        }
        if(ownedSlot != null) {
            val held=inventory.getItem(heldSlot)
            inventory.setItem(heldSlot,inventory.getItem(ownedSlot))
            inventory.setItem(ownedSlot,held)
            inventory.getItem(heldSlot)?.let { switched ->
                switched.itemMeta=switched.itemMeta.apply { persistentDataContainer.set(starterClassKey,PersistentDataType.STRING,newClass.id) }
            }
            profile.discoverItem(newWeapon)
            return false
        }

        val held=inventory.getItem(heldSlot)
        val heldId=identify(held)
        val oldStarterIds=oldClassId?.let(snapshot.classes::find)?.starterItems.orEmpty().toSet()
        val canReplaceHeld=held == null || heldId in oldStarterIds
        val destination=if(canReplaceHeld) heldSlot else
            (0 until inventory.storageContents.size).firstOrNull { it != 8 && inventory.getItem(it) == null }
        if(destination == null) {
            profile.pendingRewards.merge(newWeapon,1,Int::plus)
            profile.discoverItem(newWeapon)
            return true
        }

        if(destination == heldSlot && held != null && heldId != null) {
            val spare=(0 until inventory.storageContents.size).firstOrNull { it != 8 && it != heldSlot && inventory.getItem(it) == null }
            if(spare != null) inventory.setItem(spare,held)
            else profile.pendingRewards.merge(heldId,held.amount,Int::plus)
        }
        inventory.setItem(destination,createStarterItem(newWeapon,newClass.id,player))
        profile.discoverItem(newWeapon)
        return false
    }
    internal fun item(stack: ItemStack?): ItemDefinition? = stack?.itemMeta?.persistentDataContainer?.get(itemKey,PersistentDataType.STRING)?.let { content.snapshot().items.find(it) }
    /** コンテンツIDを画面用の名前へ変換し、未登録時も内部IDを露出しません。 */
    internal fun contentName(player: Player, id: String): String = snapshot.items.find(id)?.name
        ?: (snapshot.documents[id]?.get("name") as? String)
        ?: plain(message(player, "ec.content.unknown_name"))
    private fun isSkill(stack: ItemStack?) = stack?.itemMeta?.persistentDataContainer?.has(slotKey,PersistentDataType.BYTE) == true
    private fun skillSlot(p: Player) {
        if(!isSkill(p.inventory.getItem(8))) {
            val old = p.inventory.getItem(8)
            if(old != null && adventure.bound(old)) {
                val free=(0 until p.inventory.storageContents.size).firstOrNull { it != 8 && p.inventory.getItem(it) == null }
                if(free == null) { p.sendMessage(message(p,"ec.profile.skill_slot_space")); return }
                p.inventory.setItem(free,old)
            } else if(old != null) p.world.dropItemNaturally(p.location,old)
            p.inventory.setItem(8,ItemStack(Material.NETHER_STAR).apply { itemMeta = itemMeta.apply {
                displayName(message(p,"ec.profile.skill_slot")); persistentDataContainer.set(slotKey,PersistentDataType.BYTE,1)
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
    private fun applyStats(p: Player, profile: PlayerProfile, calculated: Stats = effectiveStats(p,profile)) {
        val max = calculated.hp.coerceIn(1.0,content.snapshot().balance.statCap)
        p.getAttribute(Attribute.MAX_HEALTH)?.baseValue = max
        p.isHealthScaled = true; p.healthScale = 20.0
        if(p.health > max) p.health = max
    }
    private fun hasEnhancementToken(player:Player,token:String):Boolean = player.inventory.contents.filterNotNull().any { stack ->
        stack.itemMeta.persistentDataContainer.get(enhancementTransactionKey,PersistentDataType.STRING) == token
    }
    /** 周辺取引がロールバックした場合に、強化保留記録を解除します。 */
    internal fun rollbackEnhancement(id:UUID) { enhancementFlights.remove(id) }
    /** 保留強化の対象装備を見失った場合に、消費素材・通貨を一度だけ返却します。 */
    private fun settleEnhancement(player:Player,profile:PlayerProfile):Boolean {
        val flight=enhancementFlights.remove(profile.uuid) ?: return true
        if(hasEnhancementToken(player,flight.token)) return true
        profile.currency=(profile.currency+flight.currency).coerceAtMost(snapshot.world.tuning.maxCurrency)
        val restored=runCatching { inventory(player).exchange(emptyMap(),mapOf(flight.material to 1)) }.getOrDefault(false)
        if(restored) {
            profile.discoverItem(flight.material)
            runCatching { player.saveData() }.onFailure { logger.warning("Failed to persist refunded enhancement items for ${profile.uuid}: ${it.message}") }
        } else profile.pendingRewards.merge(flight.material,1,Int::plus)
        save(profile)
        return false
    }
    private fun advancementWarning(error:Throwable) {
        if(advancementWarningLogged) return
        advancementWarningLogged=true
        logger.warning("Could not register title advancement data pack; profile unlock flags remain authoritative: ${error.message}")
    }
    /** 所有するデータパックだけを生成し、PaperのDatapackManagerで進捗定義を再読込します。 */
    private fun reloadAdvancementPack() {
        val world=server.worlds.firstOrNull { it.environment==World.Environment.NORMAL && it.name!=DemoWorld.NAME }
            ?: server.worlds.firstOrNull() ?: error("No world is available for the advancement data pack.")
        val rootTitle=plain(language.component(null,"ec.advancement.root_title"))
        val rootDescription=plain(language.component(null,"ec.advancement.root_description"))
        val packDescription=plain(language.component(null,"ec.advancement.pack_description"))
        val installation=AdvancementDatapack.install(world.worldFolder.toPath(),content.snapshot().world.titles.values,rootTitle,rootDescription,packDescription)
        val manager=server.datapackManager
        manager.refreshPacks()
        val datapack=manager.getPack(installation.packName) ?: error("Advancement data pack was not detected: ${installation.packName}")
        val enabled=datapack.isEnabled
        if(!enabled) datapack.isEnabled=true else if(installation.changed) server.reloadData()
        server.updateResources()
        server.onlinePlayers.forEach { player -> profiles[player.uniqueId]?.let { syncTitleAdvancements(player,it) } }
    }
    /** 永続プロフィールflagを正本とし、Minecraft進捗の付与・取消を同期します。 */
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
    internal fun openCodex(player: Player) = codexController.openCodex(player)
    internal fun openCodexArea(player: Player, areaId: String) = codexController.openCodexArea(player, areaId)
    internal fun openCodexItems(player: Player) = codexController.openCodexItems(player)
    internal fun openCodexFish(player: Player) = codexController.openCodexFish(player)
    internal fun openCodexGathering(player: Player) = codexController.openCodexGathering(player)
    /** MockBukkitでは旧Inventory経路を残し、Paperでは同じ内容をInvUIへ描画します。 */
    internal fun openRpgMenu(player: Player, inventory: Inventory, onClose: () -> Unit = {}, onClick: (Int, org.bukkit.event.inventory.ClickType) -> Unit = { _, _ -> }): Boolean {
        if (!paperInventoryUi) {
            player.openInventory(inventory)
            return false
        }
        val holder = inventory.holder as? RpgMenu ?: return false
        invUiInventoryAdapter.open(player, inventory, holder.title, holder.kind, { slot, click ->
            handleInvUiAction(player) { onClick(slot,click) }
        }, onClose)
        return true
    }
    /** InvUIのコールバック例外を画面操作の失敗として処理し、PaperのERRORログへ漏らしません。 */
    internal fun handleInvUiAction(player: Player, action: () -> Unit) {
        try {
            action()
        } catch (error: Exception) {
            logger.fine("InvUI action rejected for ${player.uniqueId}: ${error.message}")
            player.sendMessage(message(player,"ec.command.failed", "reason" to diagnostic(player,error.message.orEmpty())))
        }
    }
    /** 図鑑の画面移動をInvUI/旧Inventoryのどちらからも同じ処理へ接続します。 */
    internal fun openCodexMenu(player: Player, inventory: Inventory) {
        val holder = inventory.holder as? RpgMenu ?: return
        openRpgMenu(player, inventory) { slot, _ -> codexAction(player,holder.kind,holder.contentId,slot) }
    }
    internal fun openEnhancement(player:Player) {
        val stack=player.inventory.itemInMainHand
        val definition=item(stack) ?: error("ec.error.enhance_hold_gear")
        require(stack.amount == 1 && definition.slot in setOf(GearSlot.HAND,GearSlot.HEAD,GearSlot.CHEST,GearSlot.LEGS,GearSlot.FEET)) { "ec.error.enhance_one_gear" }
        require(definition.canEquip(profiles[player.uniqueId] ?: error("ec.error.profile_loading"))) { "ec.error.equipment_requirements" }
        val level=stack.itemMeta.persistentDataContainer.get(enhancementKey,PersistentDataType.INTEGER) ?: 0
        val tuning=snapshot.world.tuning
        val menu=RpgMenu("enhance",definition.id,level.toString(),message(player,"ec.menu.enhance")).inventory
        menu.setItem(4,stack.clone())
        menu.setItem(13,ItemStack(Material.ANVIL).apply { itemMeta=itemMeta.apply {
            displayName(message(player,"ec.enhance.button", "level" to (level+1)))
            lore(listOf(message(player,"ec.enhance.material", "item" to contentName(player,tuning.enhancementMaterial)),message(player,"ec.enhance.cost", "cost" to tuning.enhancementBaseCurrency*(level+1)),message(player,"ec.enhance.cap", "level" to tuning.enhancementCap)))
        } })
        openRpgMenu(player, menu) { slot, _ -> if (slot == 13) runCatching { enhance(player, menu.holder as RpgMenu) }.onFailure { player.sendMessage(message(player,"ec.command.failed", "reason" to diagnostic(player,it.message.orEmpty()))) } }
    }
    internal fun enhance(player:Player,menu:RpgMenu) {
        val p=profiles[player.uniqueId] ?: error("ec.error.profile_loading")
        val stack=player.inventory.itemInMainHand
        require(identify(stack) == menu.contentId) { "ec.error.enhance_item_changed" }
        val definition=item(stack) ?: error("ec.error.enhance_item_missing")
        val current=stack.itemMeta.persistentDataContainer.get(enhancementKey,PersistentDataType.INTEGER) ?: 0
        require(current == menu.nodeId.toInt()) { "ec.error.enhance_level_changed" }
        val tuning=snapshot.world.tuning
        require(current < tuning.enhancementCap) { "ec.error.enhance_cap" }
        val cost=Math.multiplyExact(tuning.enhancementBaseCurrency,(current+1).toLong())
        val next=definition.enhancedStats(current+1,snapshot.balance,tuning.enhancementRate)
        val now=definition.enhancedStats(current,snapshot.balance,tuning.enhancementRate)
        require(next != now) { "ec.error.enhance_budget_cap" }
        val flight=EnhancementFlight(UUID.randomUUID().toString(),tuning.enhancementMaterial,cost)
        adventure.transaction(player,{ inventory ->
            require(p.currency >= cost) { "ec.error.currency_low" }
            require(inventory.exchange(mapOf(tuning.enhancementMaterial to 1),emptyMap())) { "ec.error.enhance_material_missing" }
            p.currency -= cost
            p.bestEnhancementLevel=maxOf(p.bestEnhancementLevel,current+1)
            game.quests.event(p,ObjectiveKind.ENHANCE_GEAR,(current+1).toString(),1,inventory)
        },beforePersist={
            val held=player.inventory.itemInMainHand
            require(identify(held) == definition.id && (held.itemMeta.persistentDataContainer.get(enhancementKey,PersistentDataType.INTEGER) ?: 0) == current) { "ec.error.enhance_item_changed_no_cost" }
            val meta=held.itemMeta
            meta.persistentDataContainer.set(enhancementKey,PersistentDataType.INTEGER,current+1)
            meta.persistentDataContainer.set(enhancementTransactionKey,PersistentDataType.STRING,flight.token)
            val enhancementLore = enhancementLoreLines(language, tuning.enhancementCap)
            val lore = meta.lore() ?: emptyList()
            meta.lore(lore.filterNot { plain(it) in enhancementLore } + message(player,"ec.enhance.level_lore", "level" to (current+1)))
            held.itemMeta=meta; player.inventory.setItemInMainHand(held)
            player.saveData()
            enhancementFlights[player.uniqueId]=flight
        },success={
            if(settleEnhancement(player,p)) {
                syncTitleAdvancements(player,p)
                applyStats(player,p)
                player.sendMessage(message(player,"ec.enhance.success", "level" to (current+1)))
            } else player.sendMessage(message(player,"ec.enhance.refunded"))
        })
    }
    /** 言語・強化上限が変わった時だけ既存強化loreを再生成します。 */
    private fun enhancementLoreLines(catalog: LangCatalog, cap: Int): Set<String> {
        if (enhancementLoreLanguage !== catalog || enhancementLoreLines.size != cap * 2) {
            enhancementLoreLines = (1..cap).flatMap { level ->
                listOf("ja_jp", "en_us").map { locale ->
                    plain(catalog.component(locale, "ec.enhance.level_lore", mapOf("level" to level)))
                }
            }.toSet()
            enhancementLoreLanguage = catalog
        }
        return enhancementLoreLines
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false) fun codexClick(e:InventoryClickEvent) {
        val player=e.whoClicked as? Player ?: return
        val menu=e.view.topInventory.holder as? RpgMenu ?: return
        if(!menu.kind.startsWith("codex-") && menu.kind != "enhance") return
        e.isCancelled=true
        if(e.rawSlot !in 0 until e.view.topInventory.size) return
        if(menu.kind == "enhance") { if(e.rawSlot == 13) runCatching { enhance(player,menu) }.onFailure { player.sendMessage(message(player,"ec.command.failed", "reason" to diagnostic(player,it.message.orEmpty()))) }; return }
        codexAction(player,menu.kind,menu.contentId,e.rawSlot)
    }
    private fun codexAction(player: Player, kind: String, contentId:String, slot: Int) {
        when(slot) {
            45 -> codexController.turnPage(player,kind,contentId,-1)
            46 -> if(kind!="codex-areas") openCodex(player)
            47 -> openCodexFish(player)
            48 -> openCodexGathering(player)
            49 -> openCodexItems(player)
            53 -> codexController.turnPage(player,kind,contentId,1)
            else -> if(kind=="codex-areas") codexController.openAreaFromSlot(player,slot)
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
            if(resourcePacks.isAwaitingRequiredPack(p.uniqueId)) return@forEach
            val c = profile.classId?.let { content.snapshot().classes.find(it) } ?: return@forEach
            if(adventure.locked(p.uniqueId)) return@forEach
            val gatheringActive=::gathering.isInitialized && gathering.active(p.uniqueId)
            val fishingActive=::fishing.isInitialized && fishing.active(p.uniqueId)
            val s = effectiveStats(p,profile); applyStats(p,profile,s); applyTitle(p,profile)
            val beforeResource = profile.resource
            game.regenerate(profile,s.resource,Bukkit.getCurrentTick()-(lastMoved[p.uniqueId] ?: Int.MIN_VALUE/2) < 20)
            if(profile.resource != beforeResource) dirty.add(p.uniqueId)
            profile.selectedSkill = profile.selectedSkill.coerceIn(0,c.skills.lastIndex)
            val skill = c.skills[profile.selectedSkill]
            if(shouldSendClassHud(gatheringActive,fishingActive)) p.sendActionBar(message(p,"ec.hud.main", "hp" to p.health.toInt(), "max_hp" to s.hp.toInt(), "resource_name" to resourceName(p,c.resource), "resource" to profile.resource.toInt(), "max_resource" to s.resource.toInt(), "skill" to content.snapshot().skills[skill].name, "cooldown" to "%.1f".format(game.skills.remaining(p.uniqueId,skill)/1000.0), "party" to adventure.parties.members(p.uniqueId).size))
          } catch(error: Exception) { logger.warning("HUD update failed ${p.uniqueId}: ${error.message}") }
        }
        if(++autosaveSeconds >= 60) {
            autosaveSeconds = 0; dirty.toList().forEach { profiles[it]?.let(::save) }; dirty.clear()
        }
    }
    private fun cycle(p: Player) {
        val profile = profiles[p.uniqueId] ?: return
        val c = profile.classId?.let { content.snapshot().classes.find(it) } ?: return
        profile.selectedSkill = (profile.selectedSkill+1)%c.skills.size; dirty.add(p.uniqueId)
        p.sendMessage(message(p,"ec.skill.selected", "skill" to content.snapshot().skills[c.skills[profile.selectedSkill]].name))
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
        if(!weapon.canEquip(profile)) { p.sendMessage(message(p,"ec.skill.weapon_requirements")); return }
        val c = profile.classId?.let { content.snapshot().classes.find(it) } ?: return
        val skill = content.snapshot().skills[c.skills[profile.selectedSkill.coerceIn(0,c.skills.lastIndex)]]
        val target = p.world.rayTraceEntities(p.eyeLocation,p.eyeLocation.direction,skill.range,0.8) { it is LivingEntity && it !is Player && it.persistentDataContainer.has(enemyKey) }?.hitEntity as? LivingEntity
        if(target != null && !p.hasLineOfSight(target)) { p.sendMessage(message(p,"ec.skill.obstructed")); return }
        val result=castSkill(p,target)
        if(result != CastResult.SUCCESS) p.sendMessage(message(p,when(result) { CastResult.RESOURCE -> "ec.skill.resource_low"; CastResult.INVALID_TARGET -> "ec.skill.target_missing"; else -> "ec.skill.cooldown" }))
    }
    /** 実イベントとテストが共有するスキル経路です。照準対象を渡しray traceを分離できます。 */
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
            val recipients=player.world.players.filter { recipient ->
                recipient.uniqueId == player.uniqueId ||
                    (recipient.uniqueId in recipientIds && recipient.location.distanceSquared(player.location) <= skill.allyRadius*skill.allyRadius)
            }
            recipients.forEach { recipient ->
                val recipientProfile=profiles[recipient.uniqueId] ?: return@forEach
                if(skill.heal > 0 && (recipient.uniqueId == player.uniqueId || skill.partyHeal)) recipient.health=(recipient.health+effectiveStats(recipient,recipientProfile).hp*skill.heal).coerceAtMost(recipient.getAttribute(Attribute.MAX_HEALTH)!!.value)
                if(skill.defenseBuff > 0 && (recipient.uniqueId == player.uniqueId || skill.partyDefenseBuff)) defenseBuffs[recipient.uniqueId]=skill.defenseBuff to (System.nanoTime()/1_000_000+skill.buffTicks*50L)
            }
        }
        if(skill.tauntTicks > 0) enemies.taunt(player,skill.radius.coerceAtLeast(skill.allyRadius).coerceAtLeast(skill.range),skill.tauntTicks,skill.threatMultiplier)
        if(target != null) {
            val targets=if(skill.radius > 0) {
                target.getNearbyEntities(skill.radius,skill.radius,skill.radius)
                    .filterIsInstance<LivingEntity>()
                    .filter { candidate ->
                        candidate.persistentDataContainer.has(enemyKey) &&
                            enemyAllowsPlayer(player.uniqueId,enemies.state(candidate.uniqueId)?.participants.orEmpty())
                    } + target
            } else listOf(target)
            val uniqueTargets=targets.distinctBy { it.uniqueId }
            uniqueTargets.forEach { hit(player,it,game.skills.multiplier(profile,skill),skill.element,useSkillPower=true) }
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
    /** 実プレイヤー攻撃と同じゲート・ダメージ計算を、攻撃packetなしで呼び出せます。 */
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
                player.sendMessage(message(player,"ec.combat.revive")); return
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
        if (e.from.world == e.to.world && e.from.blockX == e.to.blockX && e.from.blockY == e.to.blockY && e.from.blockZ == e.to.blockZ) return
        val safeAreas=safeAreas(e.to.world.name)
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
    private fun spawnEnemy(player: Player,id: String) { require(enemies.spawn(id,player.location) != null) { "ec.error.spawn_duplicate_or_cap" } }
    @EventHandler fun death(e: EntityDeathEvent) {
        if(!e.entity.persistentDataContainer.has(enemyKey)) return
        e.drops.clear(); e.droppedExp=0
        val killer=e.entity.killer ?: if(::enemies.isInitialized) enemies.killer(e.entity.uniqueId) else null
        if(killer != null) rewardKill(killer,e.entity)
        if(::dungeons.isInitialized) dungeons.defeated(e.entity.uniqueId)
    }
    /** 実死亡イベントと共有する報酬経路で、MockBukkitのkiller取得に依存しません。 */
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
                    codexUnlocks=profile.recordEnemyKillIndexed(id,snapshot.world.areas,snapshot.world.enemiesByArea)
                    drops=game.kill(profile,id,inv,xp[playerId] ?: 0,currency[playerId] ?: 0,playerId == loot)
                    drops.forEach { (item,amount) -> profile.pendingRewards.merge(item,amount,Int::plus) }
                }) {
                    player.sendMessage(message(player,"ec.combat.enemy_defeated", "level" to profile.level, "xp" to profile.xp))
                    if(codexUnlocks.isNotEmpty()) player.sendMessage(message(player,"ec.codex.new_discoveries", "items" to codexUnlocks.joinToString { contentName(player,it) }))
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
        c.items.all().forEach { item -> validate(item.id) { require(Material.valueOf(item.material).isItem) { "ec.error.invalid_content: material" } } }
        c.classes.all().filter { it.icon != null }.forEach { definition -> validate(definition.id) { require(Material.valueOf(definition.icon!!).isItem) { "ec.error.invalid_content: icon" } } }
        c.world.titles.values.filter { it.advancement != null }.forEach { title -> validate(title.id) { require(Material.valueOf(title.advancement!!.icon).isItem) { "ec.error.invalid_content: advancement.icon" } } }
        c.world.gatheringNodes.values.forEach { node -> validate(node.id) { require(Material.valueOf(node.displayMaterial).isBlock && Material.valueOf(node.depletedMaterial).isBlock) { "ec.error.invalid_content: gathering display material" } } }
        c.documents.values.filter { it["type"] in setOf("enemy","boss") }.forEach { d -> validate(d.string("id")) { require(EntityType.valueOf(d.string("entity")).isAlive) { "ec.error.invalid_content: entity" } } }
    }
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        return executeCommand(sender,args)
    }
    /** Paperのコマンドライフサイクルへ、Bukkitコマンドと同じ処理を登録します。 */
    private fun registerPaperCommand() {
        lifecycleManager.registerEventHandler(io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents.COMMANDS) { event ->
            event.registrar().register("ec", io.papermc.paper.command.brigadier.BasicCommand { source, args ->
                executeCommand(source.sender, args)
            })
        }
    }
    /** plugin.ymlを読まないテストサーバー用に同じコマンド処理を接続します。 */
    private fun registerMockCommand() {
        val commandMap = server.javaClass.getMethod("getCommandMap").invoke(server) as org.bukkit.command.CommandMap
        val command = object : Command("ec") {
            override fun execute(sender: CommandSender, label: String, args: Array<out String>): Boolean = executeCommand(sender, args)
            override fun tabComplete(sender: CommandSender, alias: String, args: Array<out String>): List<String> = onTabComplete(sender, this, alias, args).orEmpty()
        }
        command.setDescription("EtherealChronicle commands")
        commandMap.register(name, command)
    }
    private fun executeCommand(sender: CommandSender, args: Array<out String>): Boolean {
        if(!ready) {
            sender.sendMessage(message(sender,"ec.startup.starting"))
            return true
        }
        if(sender is Player && args.firstOrNull() in setOf("quest","party","equipment","dungeon","shop","claim","title","codex","enhance","fish","gather","ui")) {
            try { playerCommand(sender,args.toList()) } catch(error: Exception) {
                val reason=error.message?.let { diagnostic(sender,it) } ?: Component.empty()
                sender.sendMessage(message(sender,"ec.command.failed", "reason" to reason))
            }
            return true
        }
        if(!sender.hasPermission("etherealchronicle.admin")) { sender.sendMessage(message(sender,"ec.command.no_permission")); return true }
        try {
            when(args.firstOrNull()) {
                "reload", "validate" -> {
                    val reload = args[0] == "reload"
                    CompletableFuture.supplyAsync { content.load().also(::validatePaperContent) }.whenComplete { snapshot,error -> sync {
                        if(error != null) sender.sendMessage(message(sender,"ec.command.validation_failed", "reason" to diagnostic(sender,error.cause?.message ?: error.message.orEmpty())))
                        else {
                            if(reload) {
                                if(adventure.hasTransactions() || choosing.isNotEmpty() || dungeons.activeCount() > 0) { sender.sendMessage(message(sender,"ec.command.reload_busy")); return@sync }
                                val all = profiles.values + simulated.values
                                if(all.any { p -> p.classId?.let { snapshot.classes.find(it) == null } == true || p.quests.any { (id,q) -> !q.completed && snapshot.documents[id]?.get("type") != "quest" } }) {
                                    sender.sendMessage(message(sender,"ec.command.reload_content_in_use")); return@sync
                                }
                                content.install(snapshot); game.skills.updateBalance(snapshot.balance)
                                reloadConfig()
                                val languageSetting=config.getString("language","auto") ?: "auto"
                                CompletableFuture.supplyAsync { LangCatalog.load(dataFolder.toPath(),languageSetting,logger) }
                                    .whenComplete { loadedLanguage,languageError -> sync {
                                        if(languageError == null && isEnabled) language=loadedLanguage
                                        else if(languageError != null) logger.warning("Language reload failed: ${languageError.message}")
                                    } }
                                uiTheme.updateMode(UiThemeProvider.mode(config.getString("ui.theme.mode", "icons_only")))
                                all.forEach { p -> p.level=p.level.coerceAtMost(snapshot.balance.levelCap); if(p.level == snapshot.balance.levelCap) p.xp=0; p.skillLevels.replaceAll { _,level -> level.coerceIn(1,snapshot.balance.skillLevelCap) } }
                                dirty.addAll(profiles.keys)
                                runCatching { reloadAdvancementPack() }.onFailure { advancementWarning(it) }
                                enemies.close(); adventure.reloadNpcs()
                                if(::gathering.isInitialized) gathering.reload()
                                if(::fishing.isInitialized) fishing.reload()
                    if(::treasures.isInitialized) treasures.reload()
                            }
                            sender.sendMessage(message(sender,if(reload) "ec.command.reload_success" else "ec.command.validate_success"))
                        }
                    } }
                }
                "world" -> {
                    val p=(args.getOrNull(1)?.let(server::getPlayerExact) ?: sender as? Player) ?: error("ec.error.player_required")
                    p.teleport(Bukkit.getWorld(DemoWorld.NAME)?.spawnLocation ?: error("ec.error.world_unavailable"))
                }
                "currency" -> {
                    require(args.size == 3) { "ec.error.usage: /ec currency <player> <balance>" }
                    val p=server.getPlayerExact(args[1]) ?: error("ec.error.player_missing")
                    val profile=profiles[p.uniqueId] ?: error("ec.error.profile_loading")
                    require(!adventure.locked(profile.uuid)); val balance=args[2].toLong(); require(balance in 0..snapshot.world.tuning.maxCurrency)
                    profile.currency=balance; save(profile); logger.info("Admin currency adjustment: ${sender.name} -> ${p.name} balance=$balance")
                    sender.sendMessage(message(sender,"ec.command.currency_set"))
                }
                "flag" -> {
                    require(args.size in 3..4) { "ec.error.usage: /ec flag <player> <flag> [set|clear]" }
                    val p=server.getPlayerExact(args[1]) ?: error("ec.error.player_missing")
                    val profile=profiles[p.uniqueId] ?: error("ec.error.profile_loading")
                    require(!adventure.locked(profile.uuid)) { "ec.error.transaction_busy" }
                    val flag=args[2]; require(Regex("ec:flag_[a-z][a-z0-9_]*").matches(flag)) { "ec.error.flag_format" }
                    val operation=args.getOrNull(3) ?: "set"
                    require(operation in setOf("set","clear")) { "ec.error.flag_operation" }
                    val declared=snapshot.world.chapters.values.flatMap { it.strings("flags") }.toSet()
                    require(flag in declared || flag in profile.flags) { "ec.error.flag_not_declared: $flag" }
                    val changed=if(operation == "set") profile.flags.add(flag) else profile.flags.remove(flag)
                    save(profile)
                    logger.info("Admin flag update: ${sender.name} -> ${p.name} $flag $operation")
                    sender.sendMessage(message(sender,if(changed) "ec.command.flag_changed" else "ec.command.flag_unchanged", "flag" to flag, "operation" to message(sender,if(operation == "set") "ec.command.flag_set" else "ec.command.flag_clear")))
                }
                "pack" -> {
                    require(args.size == 2 && args[1] == "info") { "ec.resource_pack.pack_info_usage" }
                    val info = resourcePacks.buildInfo() ?: error("ec.resource_pack.pack_info_missing")
                    sender.sendMessage(message(sender,"ec.resource_pack.pack_info", "sha1" to info.sha1, "size" to info.sizeBytes))
                }
                "admin" -> {
                    require(args.size == 5 && args[1] == "lifeskill") { "ec.error.usage: /ec admin lifeskill <player> <FISHING|GATHERING> <level>" }
                    val p=server.getPlayerExact(args[2]) ?: error("ec.error.player_missing")
                    val profile=profiles[p.uniqueId] ?: error("ec.error.profile_loading")
                    require(!adventure.locked(profile.uuid)) { "ec.error.transaction_busy" }
                    val kind=LifeSkillKind.valueOf(args[3].uppercase()); val level=args[4].toInt()
                    require(level in 1..snapshot.world.lifeBalance.levelCap) { "ec.error.life_level_range: ${snapshot.world.lifeBalance.levelCap}" }
                    profile.lifeSkills[kind]=LifeSkillProgress(level,0); save(profile)
                    sender.sendMessage(message(sender,"ec.command.life_level_set", "kind" to message(sender,if(kind == LifeSkillKind.FISHING) "ec.life.fishing" else "ec.life.gathering"), "level" to level))
                }
                "spawn" -> {
                    require(args.size == 3) { "ec.error.usage: /ec spawn <enemy> <player>" }
                    val p = server.getPlayerExact(args[2]) ?: error("ec.error.player_missing")
                    spawnEnemy(p,args[1]); sender.sendMessage(message(sender,"ec.command.enemy_spawned"))
                }
                "starter" -> {
                    require(args.size == 2) { "ec.error.usage: /ec starter <player>" }
                    val p = server.getPlayerExact(args[1]) ?: error("ec.error.player_missing")
                    val profile = profiles[p.uniqueId] ?: error("ec.error.profile_loading")
                    require(p.uniqueId !in choosing && profile.classId != null && !adventure.locked(p.uniqueId))
                    profile.starterGranted = false; starter(p,profile,profile.classId!!)
                }
                "give" -> {
                    require(args.size in 3..4) { "ec.error.usage: /ec give <player> <item> [amount]" }
                    val p = server.getPlayerExact(args[1]) ?: error("ec.error.player_missing")
                    val profile = profiles[p.uniqueId] ?: error("ec.error.profile_loading")
                    val amount = args.getOrNull(3)?.toInt() ?: 1
                    require(amount in 1..64)
                    require(!adventure.locked(p.uniqueId))
                    require(inventory(p).exchange(emptyMap(),mapOf(args[2] to amount))) { "ec.error.inventory_full" }
                    profile.discoverItem(args[2]); save(profile)
                    sender.sendMessage(message(sender,"ec.command.item_given"))
                }
                "setlevel" -> {
                    require(args.size == 3)
                    val p = server.getPlayerExact(args[1]) ?: error("ec.error.player_missing")
                    val profile = profiles[p.uniqueId] ?: error("ec.error.profile_loading")
                    require(!adventure.locked(profile.uuid))
                    val level = args[2].toInt(); require(level in 1..content.snapshot().balance.levelCap)
                    profile.level = level; profile.xp = 0; save(profile)
                    sender.sendMessage(message(sender,"ec.command.level_set"))
                }
                "class" -> {
                    require(args.size == 3)
                    val p = server.getPlayerExact(args[1]) ?: error("ec.error.player_missing")
                    val profile = profiles[p.uniqueId] ?: error("ec.error.profile_loading")
                    val c = content.snapshot().classes[args[2]]
                    require(!adventure.locked(profile.uuid))
                    val oldClassId=profile.classId
                    if(oldClassId != c.id && profile.starterGranted) {
                        require(choosing.add(p.uniqueId)) { "ec.error.class_busy" }
                        val queued=try { reconcileStarterWeapon(p,profile,oldClassId,c) }
                        finally { choosing.remove(p.uniqueId) }
                        game.changeClass(profile,c.id); save(profile); applyStats(p,profile)
                        if(queued) p.sendMessage(message(p,"ec.class.weapon_queued"))
                    } else {
                        game.changeClass(profile,c.id); save(profile); applyStats(p,profile)
                        if(!profile.starterGranted) starter(p,profile,c.id)
                    }
                    sender.sendMessage(message(sender,"ec.command.class_set"))
                }
                "debug" -> debug(sender,args.drop(1))
                else -> sender.sendMessage(message(sender,"ec.command.help"))
            }
        } catch(e: Exception) {
            val reason=e.message?.let { diagnostic(sender,it) } ?: Component.empty()
            sender.sendMessage(message(sender,"ec.command.failed", "reason" to reason))
        }
        return true
    }
    private fun playerCommand(player: Player,args: List<String>) {
        val p=profiles[player.uniqueId] ?: error("ec.error.profile_loading")
        require(!adventure.locked(p.uuid)) { "ec.error.transaction_busy" }
        when(args[0]) {
            "codex" -> openCodex(player)
            "ui" -> {
                require(args.getOrNull(1) == "pack") { "ec.resource_pack.usage" }
                if(resourcePacks.isExternallyManaged()) {
                    player.sendMessage(message(player,"ec.resource_pack.external_managed"))
                    return
                }
                val preference = when (args.getOrNull(2)?.lowercase()) {
                    "auto" -> PackPreference.AUTO
                    "on" -> PackPreference.ON
                    "off" -> PackPreference.OFF
                    else -> error("ec.resource_pack.usage")
                }
                p.resourcePackPreference = preference.name
                save(p)
                resourcePacks.send(player, preference)
                val labelKey = when (preference) {
                    PackPreference.AUTO -> "ec.resource_pack.preference_auto"
                    PackPreference.ON -> "ec.resource_pack.preference_on"
                    PackPreference.OFF -> "ec.resource_pack.preference_off"
                }
                player.sendMessage(message(player,"ec.resource_pack.preference_set", "preference" to message(player,labelKey)))
            }
            "enhance" -> openEnhancement(player)
            "fish" -> {
                if(args.getOrNull(1)=="bait") {
                    val value=args.getOrNull(2) ?: error("ec.error.usage: /ec fish bait <item_id|auto|none>")
                    val selected=fishing.selectBait(p,value); save(p)
                    val response=when(selected) {
                        "AUTO" -> message(player,"ec.fishing.bait_enabled_auto")
                        "NONE" -> message(player,"ec.fishing.bait_disabled")
                        else -> message(player,"ec.fishing.bait_selected","name" to snapshot.items[selected].name)
                    }
                    player.sendMessage(response)
                } else {
                    val life=p.lifeSkills.getValue(LifeSkillKind.FISHING); val required=LifeSkillEngine(snapshot.world.lifeBalance).requiredXp(life.level)
                    val bait=when(p.fishingBait){"AUTO"->message(player,"ec.fishing.bait_auto");"NONE"->message(player,"ec.fishing.bait_none");else->Component.text(p.fishingBait)}
                    player.sendMessage(message(player,"ec.life.fishing_status", "level" to life.level, "xp" to life.xp, "required" to required, "score" to FishingEngine(snapshot.world.lifeBalance).score(p), "found" to p.fishRecords.values.count { it.count>0 }, "total" to snapshot.world.fishSpecies.size, "bait" to bait))
                    player.sendMessage(message(player,"ec.life.fishing_bait_help"))
                }
            }
            "gather" -> {
                val life=p.lifeSkills.getValue(LifeSkillKind.GATHERING); val required=LifeSkillEngine(snapshot.world.lifeBalance).requiredXp(life.level)
                player.sendMessage(message(player,"ec.life.gathering_status", "level" to life.level, "xp" to life.xp, "required" to required, "count" to p.gatheringRecords.values.sum(), "found" to p.gatheringRecords.count { it.value>0 }, "total" to snapshot.world.gatheringNodes.size))
            }
            "title" -> {
                syncTitleAdvancements(player,p)
                val id=args.getOrNull(1)
                if(id == null) {
            snapshot.world.titles.values.forEach { title -> player.sendMessage(message(player,"ec.title.list_entry", "status" to if(title.unlockFlag in p.flags) "✓" else "○", "name" to title.name, "equipped" to if(title.id == p.selectedTitle) message(player,"ec.title.equipped_mark") else Component.empty())) }
                    player.sendMessage(message(player,"ec.title.help"))
                } else {
                    val selected=if(id == "none") null else id
                    require(p.equipTitle(selected,snapshot.world.titles)) { "ec.title.locked" }
                    save(p); applyTitle(player,p)
                    player.sendMessage(message(player,if(selected == null) "ec.title.cleared" else "ec.title.equipped", "name" to selected?.let { snapshot.world.titles.getValue(it).name }))
                }
            }
            "claim" -> adventure.claimPending(player)
            "equipment" -> adventure.equipment(player)
            "dungeon" -> when(args.getOrNull(1)) {
                "leave" -> dungeons.leave(player)
                "records" -> if(p.challengeRecords.isEmpty()) player.sendMessage(message(player,"ec.dungeon.no_records")) else p.challengeRecords.forEach { (id,record) -> player.sendMessage(message(player,"ec.dungeon.record", "name" to contentName(player,id), "seconds" to record.bestSeconds, "rank" to record.bestRank)) }
                "enter" -> dungeons.enter(player,args.getOrNull(2) ?: "ec:dungeon_void_sanctum")
                null -> dungeons.enter(player)
                else -> dungeons.enter(player,args[1])
            }
            "shop" -> {
                val id=args.getOrNull(1) ?: error("ec.error.usage: /ec shop <id>")
                val npc=snapshot.world.npcs.values.firstOrNull { it.shop == id } ?: error("ec.error.shop_npc_missing")
                require(npc.position.world == player.world.name && Location(player.world,npc.position.x,npc.position.y,npc.position.z).distanceSquared(player.location) <= 64) { "ec.error.shop_too_far" }
                adventure.showShop(player,id)
            }
            "quest" -> when(args.getOrNull(1)) {
                "start" -> { require(player.hasPermission("etherealchronicle.admin")) { "ec.error.quest_npc_start" }; game.startQuest(p,args.getOrNull(2) ?: error("ec.error.usage: /ec quest start <id>")); save(p); player.sendMessage(message(player,"ec.quest.accepted")) }
                "complete" -> { require(player.hasPermission("etherealchronicle.admin")) { "ec.error.quest_npc_complete" }; adventure.transaction(player,{ inv -> game.completeQuest(p,args.getOrNull(2) ?: error("ec.error.usage: /ec quest complete <id>"),inv) }) { player.sendMessage(message(player,"ec.quest.completed")) } }
                else -> {
                    if(p.quests.isEmpty()) player.sendMessage(message(player,"ec.quest.none_active"))
                    p.quests.forEach { (id,q) ->
                        val definition=snapshot.world.quests[id]
                        // 依頼名はYAMLの表示データ、進行状態はLang文言として分け、1行での言語混在を防ぎます。
                        player.sendMessage(message(player,"ec.quest.name", "name" to (definition?.name ?: plain(message(player,"ec.quest.unknown_name")))))
                        player.sendMessage(message(player,"ec.quest.status", "status" to message(player,when { q.completed -> "ec.quest.status_complete"; game.quests.ready(p,id,inventory(player)) -> "ec.quest.status_ready"; else -> "ec.quest.status_active" })))
                        if(!q.completed) definition?.objectives?.forEach { o -> player.sendMessage(message(player,"ec.quest.objective_progress", "target" to questObjectiveLabel(o.target,snapshot,plain(message(player,"ec.quest.unknown_target"))), "progress" to (if(o.kind == ObjectiveKind.COLLECT) inventory(player).count(o.target).coerceAtMost(o.amount) else q.objectives.getOrDefault(o.id,0)), "amount" to o.amount)) }
                    }
                }
            }
            "party" -> when(args.getOrNull(1)) {
                "invite" -> {
                    val target=args.getOrNull(2)?.let(server::getPlayerExact) ?: error("ec.error.party_member_missing")
                    adventure.parties.invite(p.uuid,target.uniqueId)
                    target.sendMessage(message(target,"ec.party.invite_received", "name" to player.name)); player.sendMessage(message(player,"ec.party.invite_sent", "name" to target.name))
                }
                "accept" -> { adventure.parties.accept(p.uuid); player.sendMessage(message(player,"ec.party.joined")) }
                "leave" -> { dungeons.leave(player); adventure.parties.leave(p.uuid); player.sendMessage(message(player,"ec.party.left")) }
                else -> player.sendMessage(message(player,"ec.party.members", "members" to adventure.parties.members(p.uuid).joinToString { Bukkit.getPlayer(it)?.name ?: plain(message(player,"ec.party.offline")) }))
            }
        }
    }
    private fun debug(sender: CommandSender, args: List<String>) {
        if(args.firstOrNull()=="fish") {
            val action=args.getOrNull(1) ?: error("ec.error.usage: /ec debug fish <start|status|cancel> <player> [species-id]")
            val playerName=args.getOrNull(2) ?: error("ec.error.usage: /ec debug fish $action <player> [species-id]")
            val player=server.getPlayerExact(playerName) ?: error("ec.error.player_missing: $playerName")
            val message=when(action) {
                "start" -> fishing.debugStart(player,args.getOrNull(3))
                "status" -> fishing.debugStatus(player.uniqueId)
                "cancel" -> plain(message(player,if(fishing.debugCancel(player)) "ec.fishing.debug_cancel" else "ec.fishing.no_session"))
                else -> error("ec.error.usage: /ec debug fish <start|status|cancel> <player> [species-id]")
            }
            sender.sendMessage(message(sender,"ec.debug.fish_prefix","name" to player.name,"detail" to message)); return
        }
        if(args.firstOrNull()=="gather") {
            require(args.getOrNull(1)=="status") { "ec.error.usage: /ec debug gather status <player>" }
            val playerName=args.getOrNull(2) ?: error("ec.error.usage: /ec debug gather status <player>")
            val player=server.getPlayerExact(playerName) ?: error("ec.error.player_missing: $playerName")
            sender.sendMessage(message(sender,"ec.debug.gather_prefix","name" to player.name,"detail" to gathering.debugStatus(player))); return
        }
        simulationCommands.run(sender, args)
    }
}

