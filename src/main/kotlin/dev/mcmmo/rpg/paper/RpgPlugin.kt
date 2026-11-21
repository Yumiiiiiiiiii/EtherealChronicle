package dev.mcmmo.rpg.paper

import dev.mcmmo.rpg.core.*
import dev.mcmmo.rpg.data.*
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

class RpgPlugin: JavaPlugin(), Listener, CommandExecutor {
    private lateinit var content: ContentService
    private lateinit var store: PlayerStore
    private lateinit var game: GameService
    private val profiles = mutableMapOf<UUID,PlayerProfile>()
    private val sessions = mutableMapOf<UUID,UUID>()
    private val choosing = mutableSetOf<UUID>()
    private val simulated = mutableMapOf<String,PlayerProfile>()
    private lateinit var itemKey: NamespacedKey
    private lateinit var enemyKey: NamespacedKey
    private lateinit var slotKey: NamespacedKey
    override fun onEnable() {
        itemKey = NamespacedKey(this,"item_id"); enemyKey = NamespacedKey(this,"enemy_id"); slotKey = NamespacedKey(this,"skill_slot")
        getResource("content-index.txt")!!.bufferedReader().useLines { lines -> lines.filter { it.isNotBlank() }.forEach { path ->
            val clean = path.removePrefix("\uFEFF"); if (!dataFolder.resolve(clean).exists()) saveResource(clean,false)
        } }
        content = ContentService(dataFolder.toPath().resolve("content"))
        try { content.reload(); validatePaperContent(content.snapshot()) } catch(e: Exception) {
            logger.severe("コンテンツの読み込みに失敗: ${e.message}"); server.pluginManager.disablePlugin(this); return
        }
        store = PlayerStore(dataFolder.toPath().resolve("playerdata"))
        game = GameService(content::snapshot,SkillEngine(Clock { System.nanoTime()/1_000_000 },content.snapshot().balance))
        server.pluginManager.registerEvents(this,this)
        getCommand("ec")!!.setExecutor(this)
        server.scheduler.runTaskTimer(this,Runnable { tick() },20,20)
        server.onlinePlayers.forEach { join(it) }
    }
    override fun onDisable() {
        server.scheduler.cancelTasks(this)
        if (::store.isInitialized) {
            profiles.values.forEach { store.save(it) }
            try { store.close() } catch(e: Exception) { logger.severe("保存失敗: ${e.message}") }
        }
        profiles.clear(); sessions.clear()
    }
    private fun sync(action: () -> Unit) { if (isEnabled) server.scheduler.runTask(this,Runnable(action)) }
    private fun save(p: PlayerProfile) { store.save(p).exceptionally { logger.severe("保存失敗 ${p.uuid}: ${it.message}"); null } }
    private fun join(player: Player) {
        val token = UUID.randomUUID(); sessions[player.uniqueId] = token
        player.sendMessage(Component.text("冒険データを読み込んでいます…"))
        store.load(player.uniqueId).whenComplete { profile,error -> sync {
            if(sessions[player.uniqueId] != token || !player.isOnline) return@sync
            if(error != null) { logger.severe("読み込み失敗: ${error.message}"); player.kick(Component.text("冒険データを読み込めませんでした。管理者にご連絡ください。")); return@sync }
            val snapshot = content.snapshot()
            if(profile.classId != null && snapshot.classes.find(profile.classId!!) == null) { player.kick(Component.text("職業データが見つかりません。管理者にご連絡ください。")); return@sync }
            profile.level = profile.level.coerceIn(1,snapshot.balance.levelCap)
            profiles[player.uniqueId] = profile
            if(profile.classId == null || !profile.starterGranted) menu(player) else { skillSlot(player); applyStats(player,profile) }
        } }
    }
    @EventHandler fun onJoin(e: PlayerJoinEvent) = join(e.player)
    @EventHandler fun onQuit(e: PlayerQuitEvent) {
        sessions.remove(e.player.uniqueId); choosing.remove(e.player.uniqueId)
        profiles.remove(e.player.uniqueId)?.let(::save)

    }
    private fun menu(player: Player) {
        val menu = ClassMenu().inventory
        content.snapshot().classes.all().take(9).forEachIndexed { index,c ->
            menu.setItem(index,ItemStack(when(c.resource) { ResourceKind.RAGE -> Material.IRON_SWORD; ResourceKind.FOCUS -> Material.BOW; ResourceKind.MANA -> Material.BLAZE_ROD }).apply {
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
        if(p.uniqueId in choosing || e.slot == 8 && e.clickedInventory == p.inventory || e.hotbarButton == 8 || isSkill(e.currentItem)) e.isCancelled = true
    }
    @EventHandler fun drag(e: InventoryDragEvent) {
        if(e.view.topInventory.holder is ClassMenu || e.rawSlots.any { e.view.convertSlot(it) == 8 && it >= e.view.topInventory.size }) e.isCancelled = true
    }
    @EventHandler fun closeMenu(e: InventoryCloseEvent) {
        val p = e.player as? Player ?: return
        if(e.inventory.holder is ClassMenu && profiles[p.uniqueId]?.starterGranted == false && p.uniqueId !in choosing) sync { if(p.isOnline) menu(p) }
    }
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
            c.starterItems.forEach { item -> val slot = (0..7).first { p.inventory.getItem(it) == null }; p.inventory.setItem(slot,createItem(item)) }
            skillSlot(p); applyStats(p,profile); p.closeInventory(); p.sendMessage(Component.text("ようこそ！魔物を一体倒して冒険の第一歩を達成しましょう。"))
        } }
    }
    private fun createItem(id: String, amount: Int = 1): ItemStack {
        val d = content.snapshot().items[id]
        return ItemStack(Material.valueOf(d.material),amount).apply { itemMeta = itemMeta.apply {
            displayName(Component.text(d.name)); lore(listOf(Component.text("必要レベル: ${d.minLevel} / ${d.rarity.name}")))
            persistentDataContainer.set(itemKey,PersistentDataType.STRING,id)
        } }
    }
    private fun item(stack: ItemStack?): ItemDefinition? = stack?.itemMeta?.persistentDataContainer?.get(itemKey,PersistentDataType.STRING)?.let { content.snapshot().items.find(it) }
    private fun isSkill(stack: ItemStack?) = stack?.itemMeta?.persistentDataContainer?.has(slotKey,PersistentDataType.BYTE) == true
    private fun skillSlot(p: Player) {
        if(!isSkill(p.inventory.getItem(8))) {
            val old = p.inventory.getItem(8)
            if(old != null) p.world.dropItemNaturally(p.location,old)
            p.inventory.setItem(8,ItemStack(Material.NETHER_STAR).apply { itemMeta = itemMeta.apply {
                displayName(Component.text("スキルスロット")); persistentDataContainer.set(slotKey,PersistentDataType.BYTE,1)
            } })
        }
    }
    private fun stats(p: Player, profile: PlayerProfile): Stats = game.stats(profile,(p.inventory.armorContents.toList()+p.inventory.itemInMainHand).mapNotNull(::item))
    private fun applyStats(p: Player, profile: PlayerProfile) {
        val max = stats(p,profile).hp.coerceIn(1.0,content.snapshot().balance.statCap)
        p.getAttribute(Attribute.MAX_HEALTH)?.baseValue = max
        if(p.health > max) p.health = max
    }
    private fun tick() {
        game.skills.cleanup()
        server.onlinePlayers.forEach { p ->
            val profile = profiles[p.uniqueId] ?: return@forEach
            val c = profile.classId?.let { content.snapshot().classes.find(it) } ?: return@forEach
            val s = stats(p,profile); applyStats(p,profile)
            game.regenerate(profile,s.resource,p.isSprinting || p.velocity.lengthSquared() > 0.01)
            profile.selectedSkill = profile.selectedSkill.coerceIn(0,c.skills.lastIndex)
            val skill = c.skills[profile.selectedSkill]
            p.sendActionBar(Component.text("HP ${p.health.toInt()}/${s.hp.toInt()}  ${when(c.resource){ResourceKind.RAGE -> "怒り";ResourceKind.FOCUS -> "集中";ResourceKind.MANA -> "マナ"}} ${profile.resource.toInt()}/${s.resource.toInt()}  ${content.snapshot().skills[skill].name} CD ${game.skills.remaining(p.uniqueId,skill)/1000.0}秒"))
        }
        if(Bukkit.getCurrentTick() % 1200 == 0) profiles.values.forEach(::save)
    }
    private fun cycle(p: Player) {
        val profile = profiles[p.uniqueId] ?: return
        val c = profile.classId?.let { content.snapshot().classes.find(it) } ?: return
        profile.selectedSkill = (profile.selectedSkill+1)%c.skills.size
        p.sendMessage(Component.text("選択: ${content.snapshot().skills[c.skills[profile.selectedSkill]].name}"))
    }
    @EventHandler fun swap(e: PlayerSwapHandItemsEvent) { e.isCancelled = true; cycle(e.player) }
    @EventHandler fun drop(e: PlayerDropItemEvent) { if(isSkill(e.itemDrop.itemStack) || e.player.uniqueId !in profiles) e.isCancelled = true }
    @EventHandler fun interact(e: PlayerInteractEvent) {
        if(e.hand != EquipmentSlot.HAND || !e.action.isRightClick) return
        val p = e.player; val profile = profiles[p.uniqueId] ?: run { e.isCancelled = true; return }
        if(p.isSneaking && p.inventory.heldItemSlot == 8) { e.isCancelled = true; cycle(p); return }
        val weapon = item(p.inventory.itemInMainHand) ?: return
        e.isCancelled = true
        if(!weapon.canEquip(profile)) { p.sendMessage(Component.text("この装備の条件を満たしていません。")); return }
        val c = profile.classId?.let { content.snapshot().classes.find(it) } ?: return
        val skill = content.snapshot().skills[c.skills[profile.selectedSkill.coerceIn(0,c.skills.lastIndex)]]
        val target = p.world.rayTraceEntities(p.eyeLocation,p.eyeLocation.direction,skill.range,0.8) { it is LivingEntity && it !is Player && it.persistentDataContainer.has(enemyKey) }?.hitEntity as? LivingEntity
        if(target == null) { p.sendMessage(Component.text("射程内に魔物がいません。")); return }
        val result = game.skills.cast(profile,skill)
        if(result != CastResult.SUCCESS) { p.sendMessage(Component.text(if(result == CastResult.RESOURCE) "リソースが足りません。" else "まだ使用できません。")); return }
        hit(p,target,game.skills.multiplier(profile,skill))
    }
    private val casting = mutableSetOf<UUID>()
    private fun hit(p: Player,target: LivingEntity,multiplier: Double) {
        val profile = profiles[p.uniqueId] ?: return
        val d = target.persistentDataContainer.get(enemyKey,PersistentDataType.STRING)?.let { content.snapshot().documents[it] } ?: return
        val s = stats(p,profile)
        val amount = content.snapshot().balance.damage(s,d.num("defense"),multiplier*s.skillPower,0.0,kotlin.random.Random.nextDouble() < s.crit.coerceIn(0.0,content.snapshot().balance.critCap))
        casting.add(p.uniqueId)
        try { target.damage(amount,p) } finally { casting.remove(p.uniqueId) }
    }
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true) fun damage(e: EntityDamageByEntityEvent) {
        val target = e.entity as? LivingEntity ?: return
        val attacker = (e.damager as? Player) ?: ((e.damager as? Projectile)?.shooter as? Player)
        if(attacker != null && attacker.uniqueId !in casting && target.persistentDataContainer.has(enemyKey)) {
            e.isCancelled = true
            val profile = profiles[attacker.uniqueId] ?: return
            if(item(attacker.inventory.itemInMainHand)?.canEquip(profile) == false) return
            hit(attacker,target,1.0)
        } else if(target is Player && e.damager.persistentDataContainer.has(enemyKey)) {
            val profile = profiles[target.uniqueId] ?: run { e.isCancelled = true; return }
            val d = content.snapshot().documents[e.damager.persistentDataContainer.get(enemyKey,PersistentDataType.STRING)] ?: return
            e.damage = content.snapshot().balance.damage(Stats(attack=d.num("attack")),stats(target,profile).defense,1.0,0.0,false)
        }
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) fun successfulHit(e: EntityDamageByEntityEvent) {
        val p = (e.damager as? Player) ?: ((e.damager as? Projectile)?.shooter as? Player) ?: return
        if(e.finalDamage > 0 && e.entity.persistentDataContainer.has(enemyKey)) profiles[p.uniqueId]?.let(game::onHit)
    }
    @EventHandler fun spawn(e: CreatureSpawnEvent) {
        val d = content.snapshot().documents.values.firstOrNull { it["type"] == "enemy" && it["entity"] == e.entityType.name } ?: return
        e.entity.persistentDataContainer.set(enemyKey,PersistentDataType.STRING,d.string("id"))
        e.entity.customName(Component.text(d.string("name"))); e.entity.isCustomNameVisible = true
        e.entity.getAttribute(Attribute.MAX_HEALTH)?.baseValue = d.num("hp").coerceIn(1.0,content.snapshot().balance.statCap)
        e.entity.health = d.num("hp").coerceIn(1.0,content.snapshot().balance.statCap)
    }
    @EventHandler fun death(e: EntityDeathEvent) {
        val id = e.entity.persistentDataContainer.get(enemyKey,PersistentDataType.STRING) ?: return
        e.drops.clear(); e.droppedExp = 0
        val p = e.entity.killer ?: return
        val profile = profiles[p.uniqueId] ?: return
        game.kill(profile,id).forEach { (item,amount) -> e.drops.add(createItem(item,amount)) }
        save(profile); p.sendMessage(Component.text("魔物を討伐！ レベル ${profile.level} / 経験値 ${profile.xp}"))
    }
    @EventHandler fun playerDeath(e: PlayerDeathEvent) { e.drops.removeIf(::isSkill) }
    @EventHandler fun respawn(e: PlayerRespawnEvent) { sync { skillSlot(e.player); profiles[e.player.uniqueId]?.let { applyStats(e.player,it) } } }
    private fun validatePaperContent(c: ContentSnapshot) { c.items.all().forEach { Material.valueOf(it.material) } }
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if(!sender.hasPermission("etherealchronicle.admin")) { sender.sendMessage(Component.text("権限がありません。")); return true }
        try {
            when(args.firstOrNull()) {
                "reload", "validate" -> {
                    val reload = args[0] == "reload"
                    CompletableFuture.supplyAsync { content.load().also(::validatePaperContent) }.whenComplete { snapshot,error -> sync {
                        if(error != null) sender.sendMessage(Component.text("検証失敗: ${error.cause?.message ?: error.message}"))
                        else {
                            if(reload) { content.install(snapshot); game.skills.updateBalance(snapshot.balance) }
                            sender.sendMessage(Component.text(if(reload) "コンテンツを再読み込みしました。" else "コンテンツ検証成功。"))
                        }
                    } }
                }
                "give" -> {
                    require(args.size in 3..4) { "/ec give <player> <item> [amount]" }
                    val p = server.getPlayerExact(args[1]) ?: error("プレイヤーが見つかりません。")
                    val amount = args.getOrNull(3)?.toInt() ?: 1
                    require(amount in 1..64)
                    val stack = createItem(args[2],amount)
                    require(p.inventory.storageContents.any { it == null }) { "持ち物に空きがありません。" }
                    val leftovers = p.inventory.addItem(stack)
                    leftovers.values.forEach { p.world.dropItemNaturally(p.location,it) }
                    sender.sendMessage(Component.text("アイテムを付与しました。"))
                }
                "setlevel" -> {
                    require(args.size == 3)
                    val p = server.getPlayerExact(args[1]) ?: error("プレイヤーが見つかりません。")
                    val profile = profiles[p.uniqueId] ?: error("読み込み中です。")
                    val level = args[2].toInt(); require(level in 1..content.snapshot().balance.levelCap)
                    profile.level = level; profile.xp = 0; save(profile)
                    sender.sendMessage(Component.text("レベルを変更しました。"))
                }
                "class" -> {
                    require(args.size == 3)
                    val p = server.getPlayerExact(args[1]) ?: error("プレイヤーが見つかりません。")
                    val profile = profiles[p.uniqueId] ?: error("読み込み中です。")
                    val c = content.snapshot().classes[args[2]]
                    profile.classId = c.id; profile.selectedSkill = 0
                    profile.resource = if(c.resource == ResourceKind.RAGE) 0.0 else c.stats.resource
                    c.skills.forEach { profile.skillLevels.putIfAbsent(it,1) }; save(profile); applyStats(p,profile)
                    sender.sendMessage(Component.text("職業を変更しました。"))
                }
                "debug" -> debug(sender,args.drop(1))
                else -> sender.sendMessage(Component.text("/ec reload | validate | give <player> <item> [amount] | setlevel <player> <level> | class <player> <id> | debug sim …"))
            }
        } catch(e: Exception) { sender.sendMessage(Component.text("操作失敗: ${e.message}")) }
        return true
    }
    private fun debug(sender: CommandSender, args: List<String>) {
        require(args.size >= 3 && args[0] == "sim") { "/ec debug sim join|class|kill|skill|quest|dump|save|load <name> [id]" }
        val operation = args[1]; val name = args[2]
        val uuid = UUID.nameUUIDFromBytes("ec:simulation:$name".toByteArray())
        if(operation == "join") {
            require(name !in simulated) { "既に参加しています。" }
            simulated[name] = PlayerProfile(uuid)
        } else if(operation == "load") {
            store.load(uuid).whenComplete { p,error -> sync {
                if(error != null) sender.sendMessage(Component.text("SIM load 失敗: ${error.message}"))
                else { simulated[name] = p; sender.sendMessage(Component.text("SIM load $name: $p")) }
            } }; return
        } else {
            val p = simulated[name] ?: error("先に sim join を実行してください。")
            when(operation) {
                "class" -> { game.chooseClass(p,args[3]); p.starterGranted = true }
                "kill" -> sender.sendMessage(Component.text("SIM drops: ${game.kill(p,args[3])}"))
                "skill" -> {
                    val c = content.snapshot().classes[p.classId ?: error("職業未選択")]
                    val skill = content.snapshot().skills[args.getOrNull(3) ?: c.skills[p.selectedSkill]]
                    require(skill.id in c.skills)
                    sender.sendMessage(Component.text("SIM cast: ${game.skills.cast(p,skill)}"))
                }
                "quest" -> game.completeQuest(p,args[3])
                "save" -> { store.save(p).whenComplete { _,error -> sync { sender.sendMessage(Component.text(if(error == null) "SIM save 成功 $name" else "SIM save 失敗: ${error.message}")) } }; return }
                "dump" -> Unit
                else -> error("不明な操作")
            }
        }
        sender.sendMessage(Component.text("SIM $operation $name: ${simulated[name]}"))
    }
}


