package net.nakaymi.etherealchronicle.core

import net.nakaymi.etherealchronicle.data.*

/** 検証済みYAML文書を型付きのゲーム定義へ変換し、文書間参照を確認します。 */
class WorldContent(private val documents: Map<String,Map<String,Any?>>) {
    val tuning = documents.values.single { it["type"] == "balance" }.tuning()
    val quests = linkedMapOf<String,QuestDefinition>()
    val dialogues = linkedMapOf<String,DialogueDefinition>()
    val npcs = linkedMapOf<String,NpcDefinition>()
    val shops = linkedMapOf<String,ShopDefinition>()
    val areas = linkedMapOf<String,Area>()
    val dungeons = linkedMapOf<String,DungeonDefinition>()
    val titles = linkedMapOf<String,TitleDefinition>()
    val enemies = linkedMapOf<String,EnemyDefinition>()
    val itemProperties = linkedMapOf<String,ItemProperties>()
    val lifeBalance: LifeBalance
    val tackle = linkedMapOf<String,TackleDefinition>()
    val fishingSpots = linkedMapOf<String,FishingSpotDefinition>()
    val fishSpecies = linkedMapOf<String,FishSpeciesDefinition>()
    val gatheringNodes = linkedMapOf<String,GatheringNodeDefinition>()
    val treasureChests = linkedMapOf<String,TreasureChestDefinition>()
    val chapters = documents.filterValues { it["type"] == "chapter" }
    init {
        // 各文書を型ごとに構築し、後段で章・ダンジョン・生活コンテンツの参照をまとめて検証します。
        val lifeBalances=documents.values.filter { it["type"] == "life_balance" }
        require(lifeBalances.size == 1) { "ec.error.invalid_content" }
        lifeBalance=lifeBalances.single().let { d ->
            val fishing=d.maps("fishing_score_milestones").map { LifeMilestone(it.int("score"),0,it.string("flag")) }
            val gathering=d.maps("gathering_milestones").map { LifeMilestone(it.int("total_harvests"),it.integer("unique_nodes",0),it.string("flag")) }
            LifeBalance(
                levelCap = d.integer("life_level_cap", LifeBalanceDefaults.LEVEL_CAP),
                xpBase = d.whole("life_xp_base", LifeBalanceDefaults.XP_BASE),
                xpExponent = d.number("life_xp_exponent", LifeBalanceDefaults.XP_EXPONENT),
                castRange = d.number("cast_range_blocks", LifeBalanceDefaults.CAST_RANGE_BLOCKS),
                castCooldownMillis = d.whole("cast_cooldown_ms", LifeBalanceDefaults.CAST_COOLDOWN_MILLIS),
                maxFishingSessions = d.integer("max_concurrent_fishing_sessions", LifeBalanceDefaults.MAX_FISHING_SESSIONS),
                fatigueWindowSeconds = d.whole("fatigue_window_seconds", LifeBalanceDefaults.FATIGUE_WINDOW_SECONDS),
                fatigueSoftThreshold = d.integer("fatigue_soft_threshold", LifeBalanceDefaults.FATIGUE_SOFT_THRESHOLD),
                fatigueHardThreshold = d.integer("fatigue_hard_threshold", LifeBalanceDefaults.FATIGUE_HARD_THRESHOLD),
                fatigueCooldownSeconds = d.whole("fatigue_cooldown_seconds", LifeBalanceDefaults.FATIGUE_COOLDOWN_SECONDS),
                reelingTimeoutSeconds = d.whole("reeling_timeout_seconds", LifeBalanceDefaults.REELING_TIMEOUT_SECONDS),
                slackFailSeconds = d.number("slack_fail_seconds", LifeBalanceDefaults.SLACK_FAIL_SECONDS),
                partyGatherRareBonus = d.number("party_gather_rare_bonus", LifeBalanceDefaults.PARTY_GATHER_RARE_BONUS),
                fishingMilestones = fishing,
                gatheringMilestones = gathering,
                macroMinSamples = d.integer("macro_min_samples", LifeBalanceDefaults.MACRO_MIN_SAMPLES),
                macroResponseDeviationMillis = d.number("macro_response_deviation_ms", LifeBalanceDefaults.MACRO_RESPONSE_DEVIATION_MILLIS),
                macroCastIntervalDeviationMillis = d.number("macro_cast_interval_deviation_ms", LifeBalanceDefaults.MACRO_CAST_INTERVAL_DEVIATION_MILLIS),
                macroRareWeightMultiplier = d.number("macro_rare_weight_multiplier", LifeBalanceDefaults.MACRO_RARE_WEIGHT_MULTIPLIER),
            ).also { balance ->
                require(balance.levelCap in 1..100 && balance.xpBase > 0 && balance.xpExponent in 1.0..3.0 && balance.castRange in 2.0..32.0 && balance.castCooldownMillis in 250..60_000 && balance.maxFishingSessions in 1..1000 && balance.fatigueWindowSeconds in 30..86_400 && balance.fatigueSoftThreshold in 1..1000 && balance.fatigueHardThreshold in balance.fatigueSoftThreshold..2000 && balance.fatigueCooldownSeconds in 1..86_400 && balance.reelingTimeoutSeconds in 5..180 && balance.slackFailSeconds in 0.5..10.0 && balance.partyGatherRareBonus in 0.0..0.25 && balance.macroMinSamples in 6..12 && balance.macroResponseDeviationMillis in 1.0..100.0 && balance.macroCastIntervalDeviationMillis in 10.0..1000.0 && balance.macroRareWeightMultiplier in 0.01..1.0) { "ec.error.invalid_content" }
                require((fishing+gathering).all { it.threshold in 1..1_000_000 && it.uniqueNodes in 0..1000 && it.flag.matches(Regex("ec:flag_[a-z0-9_]+")) } && fishing.map { it.threshold }.distinct().size == fishing.size && gathering.map { it.threshold to it.uniqueNodes }.distinct().size == gathering.size) { "ec.error.invalid_content" }
            }
        }
        documents.forEach { (id,d) ->
          try {
            when(d["type"]) {
                "quest" -> {
                    val objectives = d.maps("objectives").map { Objective(it.string("id"),ObjectiveKind.valueOf(it.string("kind")),it.string("target"),it.integer("amount",1),it.bool("consume",true)) }
                        .ifEmpty { listOf(Objective("legacy_kills",ObjectiveKind.KILL,d["kill_target"] as? String ?: "*",d.integer("required_kills",1))) }
                    require(objectives.map { it.id }.distinct().size == objectives.size && objectives.all { it.amount in 1..GameLimits.MAX_RECORD_COUNT }) { "$id: ec.error.invalid_content" }
                    val rewards = if(d["rewards"] != null) d.mapping("rewards").rewards() else Rewards(d.whole("xp",0),d.whole("currency",0))
                    val reset=d["reset_schedule"] as? String
                    require(reset == null || reset in setOf("DAILY","WEEKLY")) { "ec.error.invalid_content" }
                    quests[id] = QuestDefinition(id,d.string("name"),d.condition(),objectives,rewards,d.bool("auto_complete",d["objectives"] == null),d.bool("repeatable",false),d.whole("repeat_cooldown_ms",GameDurations.DEFAULT_QUEST_REPEAT_MILLIS),reset)
                }
                "dialogue" -> {
                    fun parseChoice(c: Map<String,Any?>) = DialogueChoice(c.string("id"),c.string("label"),c.condition(),c["next"] as? String,c.strings("set_flags").toSet(),c.strings("clear_flags").toSet(),c.maps("actions").map { a ->
                        DialogueAction(a.string("kind"),a.string("target"),a.integer("amount",1),a["once_flag"] as? String,a["destination"]?.let { it.asMapping().position() },a["required_flag"] as? String)
                    })
                    val nodes = d.mapping("nodes").mapValues { (_,raw) ->
                        val n = raw.asMapping()
                        DialogueNode(n.text("text",""),n.maps("branches").map { it.condition() to it.string("next") },n.maps("choices").map(::parseChoice))
                    }
                    val globalChoices=d.maps("global_choices").map(::parseChoice)
                    val allChoiceIds=globalChoices.map { it.id }+nodes.values.flatMap { it.choices.map(DialogueChoice::id) }
                    require(globalChoices.map { it.id }.distinct().size == globalChoices.size && allChoiceIds.distinct().size == allChoiceIds.size) { "$id: ec.error.invalid_content" }
                    val start = d.string("start")
                    require(start in nodes && nodes.isNotEmpty()) { "$id: ec.error.invalid_content" }
                    nodes.values.forEach { n ->
                        require(n.choices.map { it.id }.distinct().size == n.choices.size)
                        require(n.branches.all { it.second in nodes } && n.choices.all { it.next.isNullOrEmpty() || it.next in nodes }) { "$id: ec.error.invalid_content" }
                        require(n.choices.flatMap { it.actions }.all { it.kind in setOf("START_QUEST","COMPLETE_QUEST","SELECT_CLASS","OPEN_SHOP","ENTER_DUNGEON","GIVE_ITEM","TELEPORT_AREA") && it.amount in 1..ContentLimits.MAX_ITEM_STACK })
                    }
                    dialogues[id] = DialogueDefinition(id,start,nodes,globalChoices)
                }
                "npc" -> {
                    val definition=NpcDefinition(id,d.string("name"),d.mapping("location").position(),d.string("dialogue"),d["shop"] as? String,d.strings("time_windows").toSet().ifEmpty { setOf("ANY") },d.strings("weather").toSet().ifEmpty { setOf("ANY") })
                    require(WorldSchedule.valid(definition.timeWindows,definition.weather)) { "ec.error.invalid_content" }
                    npcs[id]=definition
                }
                "treasure_chest" -> {
                    val rawItems=d["items"] ?: error("ec.error.invalid_content")
                    val items=when(rawItems) {
                        is Map<*,*> -> rawItems.entries.associate { (item,amount) -> item.toString() to ((amount as? Number)?.toInt() ?: error("ec.error.invalid_content")) }
                        is List<*> -> rawItems.map { it.asMapping() }.also { entries -> require(entries.map { it.string("item") }.distinct().size==entries.size) { "ec.error.invalid_content" } }.associate { it.string("item") to it.int("amount") }
                        else -> error("ec.error.invalid_content")
                    }
                    val position=if("location" in d) d.mapping("location").position() else Position(d.string("world"),d.num("x"),d.num("y"),d.num("z"))
                    val definition=TreasureChestDefinition(id,position,d.string("once_flag"),items,d.whole("currency",0),d.text("hint_particle","END_ROD"))
        val currencyCap=documents.values.single { it["type"]=="balance" }.whole("currency_gain_cap",BalanceDefaults.CURRENCY_GAIN_CAP)
                    require(definition.onceFlag.matches(Regex("ec:flag_[a-z0-9_]+")) && definition.items.isNotEmpty() && definition.items.values.all { it in 1..ContentLimits.MAX_ITEM_STACK } && definition.currency in 0..currencyCap && definition.hintParticle.matches(Regex("[A-Z_]+"))) { "ec.error.invalid_content" }
                    treasureChests[id]=definition
                }
                "title" -> {
                    val unlockFlag=d.string("unlock_flag")
                    val advancement=d["advancement"]?.asMapping()?.let { a ->
                        val raw=a["condition"]?.asMapping() ?: emptyMap()
                        val type=TitleConditionType.valueOf(raw.text("type","FLAG").uppercase())
                        val target=when(type) {
                            TitleConditionType.FLAG -> raw.text("target",unlockFlag)
                            TitleConditionType.BESTIARY -> raw.string("target")
                            TitleConditionType.COLLECTION -> null
                            TitleConditionType.FISH_SPECIES_COUNT -> null
                            TitleConditionType.LIFE_LEVEL, TitleConditionType.CHALLENGE_RANK -> raw.string("target")
                            TitleConditionType.ENHANCE_LEVEL -> null
                        }
                        val condition=TitleProgressCondition(type,target,raw.integer("count",1),raw.text("rank","S").uppercase())
                        val definition=TitleAdvancement(a.text("title",d.string("name")),a.text("description",d.text("description","")),a.string("icon"),a["parent"] as? String,a.text("frame","TASK").uppercase(),condition)
                        val targetValid=when(type) {
                            TitleConditionType.COLLECTION,TitleConditionType.FISH_SPECIES_COUNT,TitleConditionType.ENHANCE_LEVEL -> true
                            TitleConditionType.LIFE_LEVEL -> target in LifeSkillKind.entries.map { it.name }
                            TitleConditionType.CHALLENGE_RANK -> target?.startsWith("ec:")==true && condition.rank in setOf("S","A","B","C")
                            else -> target?.startsWith("ec:")==true
                        }
                        require(definition.title.isNotBlank() && definition.title.length <= ContentLimits.MAX_PUBLIC_TITLE_CHARACTERS && definition.description.length <= ContentLimits.MAX_PUBLIC_DESCRIPTION_CHARACTERS && definition.icon.matches(Regex("[A-Z][A-Z0-9_]*")) && (definition.parent == null || definition.parent.matches(Regex("[a-z0-9_.-]+:[a-z0-9_./-]+"))) && definition.frame in setOf("TASK","CHALLENGE","GOAL") && condition.count in 1..GameLimits.MAX_RECORD_COUNT && targetValid) { "$id: ec.error.invalid_content" }
                        definition
                    }
                    titles[id] = TitleDefinition(id,d.string("name"),d.text("description",""),unlockFlag,advancement)
                }
                "shop" -> {
                    val entries = d.maps("entries").map { e -> ShopEntry(e.string("id"),e.string("item"),e.integer("amount",1),e.whole("price",0),e.tokens("tokens"),e.condition(),e.integer("daily_limit",0),e["token_discount_flag"] as? String,e.tokens("token_discount")) }
                    require(entries.size in 1..ContentLimits.MAX_SHOP_ENTRIES && entries.map { it.id }.distinct().size == entries.size && entries.all { it.amount in 1..ContentLimits.MAX_ITEM_STACK && it.price >= 0 && it.dailyLimit >= 0 && it.tokens.all { t -> t.amount > 0 } })
                    val discount = d.number("discount",0.0); require(discount in 0.0..0.5)
                    shops[id] = ShopDefinition(id,d.string("name"),d.bool("buyback",true),d["discount_flag"] as? String,discount,entries)
                }
                "chapter" -> {
                    d.maps("areas").forEach { a ->
                        val area = Area(a.string("id"),a.string("name"),a.string("world"),a.num("min_x"),a.num("max_x"),a.num("min_y"),a.num("max_y"),a.num("min_z"),a.num("max_z"),a.bool("safe",false))
                        require(area.minX <= area.maxX && area.minY <= area.maxY && area.minZ <= area.maxZ && area.id !in areas)
                        areas[area.id] = area
                    }
                    d.maps("dungeon_configs").forEach { config ->
                        val id=config.string("id")
                        val guards=config.strings("guards")
                        val offsets=config.maps("guard_offsets").map { it.int("x") to it.int("z") }
                        val bossOffset=config.mapping("boss_offset").let { it.int("x") to it.int("z") }
                        val waves=config.maps("waves").map { wave ->
                            val bossOffset=wave.mapping("boss_offset").let { it.int("x") to it.int("z") }
                            val guards=wave.strings("guards"); val offsets=wave.maps("guard_offsets").map { it.int("x") to it.int("z") }
                            DungeonWave(wave.string("boss"),guards,offsets,bossOffset)
                        }
                        val ranks=config.maps("score_ranks").map { rank -> ScoreRank(rank.string("rank"),rank.whole("max_seconds",0),rank["first_clear_flag"] as? String,rank.maps("first_clear_items").associate { it.string("item") to it.int("amount") }) }
                        require(ranks.flatMap { it.firstClearItems.keys }.all { documents[it]?.get("type")=="item" }) { "$id: ec.error.invalid_content" }
                        require(ranks.mapNotNull { it.firstClearFlag }.all { it in d.strings("flags") } && ranks.mapNotNull { it.firstClearFlag }.distinct().size==ranks.mapNotNull { it.firstClearFlag }.size) { "$id: ec.error.invalid_content" }
                        val definition=DungeonDefinition(id,config.string("unlock_flag"),config.string("boss"),guards,offsets,config.string("area"),config.strings("palette"),bossOffset,config.string("entry_message"),config.string("clear_message"),waves,ranks)
                        require(id in d.strings("dungeons") && id !in dungeons && definition.unlockFlag in d.strings("flags") && definition.guards.size == definition.guardOffsets.size && definition.palette.size == ContentLimits.DUNGEON_PALETTE_SIZE)
                        require(definition.waves.all { it.guardOffsets.size == it.guards.size } && definition.scoreRanks.all { it.rank in setOf("S","A","B","C") && it.maxSeconds > 0 && (it.firstClearFlag == null || it.firstClearFlag.matches(Regex("ec:flag_[a-z0-9_]+"))) && (it.firstClearItems.isEmpty() || it.firstClearFlag != null) && it.firstClearItems.values.all { amount -> amount in 1..ContentLimits.MAX_ITEM_STACK } } && definition.scoreRanks.zipWithNext().all { (a,b) -> a.maxSeconds <= b.maxSeconds })
                        dungeons[id]=definition
                    }
                }
                "enemy","boss" -> {
                    val specials = d.maps("specials").map { attack ->
                        val effect = attack.text("effect", "NONE")
                        val defaultEffectDuration = when (effect) {
                            "SLOW" -> EnemyContentDefaults.SLOW_EFFECT_TICKS
                            "POISON" -> EnemyContentDefaults.POISON_EFFECT_TICKS
                            else -> EnemyContentDefaults.DEFAULT_EFFECT_TICKS
                        }
                        SpecialAttack(
                            id = attack.string("id"),
                            name = attack.string("name"),
                            shape = attack.text("shape", "CIRCLE"),
                            cooldown = attack.whole("cooldown_ticks", EnemyContentDefaults.SPECIAL_COOLDOWN_TICKS),
                            telegraph = attack.whole("telegraph_ticks", EnemyContentDefaults.SPECIAL_TELEGRAPH_TICKS),
                            radius = attack.number("radius", EnemyContentDefaults.SPECIAL_RADIUS_BLOCKS),
                            range = attack.number("range", EnemyContentDefaults.SPECIAL_RANGE_BLOCKS),
                            multiplier = attack.number("multiplier", EnemyContentDefaults.SPECIAL_DAMAGE_MULTIPLIER),
                            minHp = attack.number("min_hp_ratio", EnemyContentDefaults.SPECIAL_MIN_HP_RATIO),
                            maxHp = attack.number("max_hp_ratio", EnemyContentDefaults.SPECIAL_MAX_HP_RATIO),
                            effect = effect,
                            element = attack["element"] as? String,
                            effectDuration = attack.integer("effect_duration_ticks", defaultEffectDuration),
                        )
                    }
                    require(specials.all { it.shape in setOf("CIRCLE","LINE","CONE","TARGET") && it.cooldown > 0 && it.telegraph > 0 && it.radius in 0.1..32.0 && it.range in 0.1..128.0 && it.minHp in 0.0..it.maxHp && it.maxHp <= 1.0 && it.effect in setOf("NONE","SLOW","POISON","KNOCKUP","FREEZE") && (it.element == null || it.element in setOf("PHYSICAL","FIRE","ICE","LIGHTNING","AETHER","VOID")) && it.effectDuration in 1..200 })
                    val spawns = d.maps("spawn_points").map { point ->
                        SpawnPoint(
                            id = point.string("id"),
                            position = point.position(),
                            cap = point.integer("cap", EnemyContentDefaults.SPAWN_POINT_CAP),
                            respawnTicks = point.whole("respawn_ticks", EnemyContentDefaults.SPAWN_RESPAWN_TICKS),
                            timeWindows = point.strings("time_windows").toSet().ifEmpty { setOf("ANY") },
                            weather = point.strings("weather").toSet().ifEmpty { setOf("ANY") },
                        ).also { require(WorldSchedule.valid(it.timeWindows, it.weather)) { "ec.error.invalid_content" } }
                    }
                    require(spawns.all { it.cap in 1..ContentLimits.MAX_ENEMY_SPAWN_POINT_CAP && it.respawnTicks > 0 } && spawns.map { it.id }.distinct().size == spawns.size)
                    val phases = d.maps("phases").map { p -> BossPhase(p.string("id"),p.num("below_hp_ratio"),p.number("damage_multiplier",1.0),p.number("interval_multiplier",1.0)) }
                    require(phases.all { it.below in 0.0..1.0 && it.damage > 0 && it.interval > 0 } && phases.zipWithNext().all { it.first.below > it.second.below })
                    val variant = d.mappingOrEmpty("visual").text("variant", "").ifBlank { null }
                    require(variant == null || (variant.matches(Regex("[a-z0-9_.-]+:[a-z0-9_./-]+")) && d.string("entity") == "WOLF")) { "ec.error.invalid_content: visual.variant" }
                    enemies[id] = EnemyDefinition(
                        id = id,
                        name = d.string("name"),
                        entity = d.string("entity"),
                        level = d.int("level"),
                        hp = d.num("hp"),
                        attack = d.num("attack"),
                        defense = d.num("defense"),
                        element = d.text("element", "PHYSICAL"),
                        weaknesses = d.strings("weaknesses").toSet(),
                        resistances = d.strings("resistances").toSet(),
                        speed = d.number("speed", EnemyContentDefaults.SPEED_BLOCKS_PER_TICK),
                        size = d.integer("size", EnemyContentDefaults.SIZE),
                        aggro = d.number("aggro_range", EnemyContentDefaults.AGGRO_RADIUS_BLOCKS),
                        leash = d.number("leash_range", EnemyContentDefaults.LEASH_RADIUS_BLOCKS),
                        attackInterval = d.whole("attack_interval_ticks", EnemyContentDefaults.ATTACK_INTERVAL_TICKS),
                        traits = d.strings("traits").toSet(),
                        specials = specials,
                        spawns = spawns,
                        flags = d.strings("set_flags_on_kill").toSet(),
                        boss = d["type"] == "boss",
                        phases = phases,
                        enrageTicks = d.whole("enrage_ticks", EnemyContentDefaults.ENRAGE_TICKS),
                        enrageMultiplier = d.number("enrage_multiplier", EnemyContentDefaults.ENRAGE_MULTIPLIER),
                        coopHp = d.number("coop_hp_per_player", EnemyContentDefaults.COOP_HP_PER_ADDITIONAL_PLAYER),
                        coopDamage = d.number("coop_damage_per_player", EnemyContentDefaults.COOP_DAMAGE_PER_ADDITIONAL_PLAYER),
                        visualVariant = variant,
                    )
                }
                "item" -> itemProperties[id] = ItemProperties(d.bool("bound",false),d.bool("quest_item",false),d.whole("price",0),d.number("weapon",0.0),d.text("lore",""),d.maps("effects").map { e ->
                    ItemEffect(e.string("kind"),e.number("amount",0.0),e.whole("cooldown_ms",0),e.integer("duration_ticks",0),e.number("chance",1.0),e.text("element","PHYSICAL")).also { require(it.kind in setOf("HEAL","RESOURCE","CLEANSE","RETURN","REVIVE","ATTACK_PROC","DEFENSE_BUFF") && it.chance in 0.0..1.0) }
                })
                "tackle" -> {
                    val definition=TackleDefinition(id,d.string("item"),TackleKind.valueOf(d.string("kind")),d.int("tier"),d.integer("min_life_level",1),d.number("wait_reduction",0.0),d.number("sweet_spot_bonus",0.0),d.number("tension_stability",1.0),d.number("size_bonus",0.0),d.number("channel_speed_bonus",0.0),d.number("rare_chance_bonus",0.0),d.mappingOrEmpty("rarity_weights").mapValues { (_,v) -> (v as? Number)?.toDouble() ?: error("ec.error.invalid_content") },d.strings("preferred_tags").toSet(),d.number("preferred_tag_multiplier",1.0))
                    require(definition.tier in 1..ContentLimits.MAX_TACKLE_TIER && definition.minLifeLevel in 1..lifeBalance.levelCap && definition.waitReduction in 0.0..0.5 && definition.sweetSpotBonus in 0.0..15.0 && definition.tensionStability in 0.5..1.2 && definition.sizeBonus in 0.0..0.15 && definition.channelSpeedBonus in 0.0..0.4 && definition.rareChanceBonus in 0.0..0.2 && definition.preferredTagMultiplier in 1.0..5.0 && definition.rarityWeights.all { (rarity,weight) -> rarity in Rarity.entries.map { it.name } && weight in 0.0..5.0 }) { "ec.error.invalid_content" }
                    tackle[id]=definition
                }
                "fishing_spot" -> {
                    val center=d.mapping("center")
                    val spotWorld=d.string("world")
                    val definition=FishingSpotDefinition(id,d.string("name"),d.string("area"),spotWorld,Position(spotWorld,center.num("x"),center.num("y"),center.num("z"),center.number("yaw",0.0).toFloat()),d.num("radius"),d.bool("default_for_area",false),d.strings("tags").toSet(),d.integer("priority",0),d["unlock_flag"] as? String)
                    require(definition.name.isNotBlank() && definition.world == WorldIds.DEMO && definition.radius in 1.0..128.0 && definition.tags.isNotEmpty() && definition.priority in -1000..1000 && (definition.unlockFlag == null || definition.unlockFlag.matches(Regex("ec:flag_[a-z0-9_]+"))) ) { "ec.error.invalid_content" }
                    fishingSpots[id]=definition
                }
                "fish_species" -> {
                    val mini=d.mapping("minigame")
                    val game=FishMinigame(mini.whole("bite_window_ms",1800),mini.num("difficulty"),mini.num("sweet_width"),mini.num("progress_per_sec"),mini.num("tension_decay_per_sec"),mini.num("thrash_chance_per_sec"),mini.num("dive_chance_per_sec"))
                    require(game.biteWindowMillis in 500..5000 && game.difficulty in 1.0..5.0 && game.sweetWidth in 22.0..46.0 && game.progressPerSecond in 5.0..40.0 && game.tensionDecayPerSecond in 5.0..50.0 && game.thrashChancePerSecond in 0.0..1.0 && game.diveChancePerSecond in 0.0..1.0 && game.thrashChancePerSecond+game.diveChancePerSecond <= 1.0) { "ec.error.invalid_content" }
                    val definition=FishSpeciesDefinition(id,d.string("name"),d.string("item"),Rarity.valueOf(d.string("rarity")),d.strings("tags").toSet(),d.strings("habitats").toSet(),d.strings("time_windows").toSet(),d.strings("weather").toSet(),d.int("min_rod_tier"),d.int("min_fishing_level"),d.num("base_weight"),d.num("min_cm"),d.num("max_cm"),d.num("silver_crown_cm"),d.num("gold_crown_cm"),d.whole("life_xp",0),d.whole("adventurer_xp",0),game,d.text("lore_hint",""))
                    require(definition.tags.isNotEmpty() && definition.habitats.isNotEmpty() && definition.timeWindows.isNotEmpty() && definition.timeWindows.all { it in setOf("ANY","DAY","NIGHT","DAWN_DUSK") } && definition.weather.isNotEmpty() && definition.weather.all { it in setOf("ANY","CLEAR","RAIN","THUNDER") } && definition.minRodTier in 1..3 && definition.minFishingLevel in 1..lifeBalance.levelCap && definition.baseWeight in 0.001..100_000.0 && definition.minCm in 0.1..10_000.0 && definition.maxCm in definition.minCm..10_000.0 && definition.silverCrownCm in definition.minCm..definition.maxCm && definition.goldCrownCm in definition.silverCrownCm..definition.maxCm && definition.lifeXp in 0..100_000 && definition.adventurerXp in 0..100_000) { "ec.error.invalid_content" }
                    fishSpecies[id]=definition
                }
                "gathering_node" -> {
                    fun yields(key: String) = d.maps(key).map { raw -> NodeYield(raw.string("item"),raw.int("min"),raw.int("max"),raw.num("chance"),if("pity_after" in raw) raw.int("pity_after") else null) }
                    val locations=d.maps("locations").map { raw -> GatheringLocation(raw.string("id"),raw.string("area"),raw.string("world"),raw.int("x"),raw.int("y"),raw.int("z")) }
                    val definition=GatheringNodeDefinition(id,d.string("name"),NodeKind.valueOf(d.string("node_kind")),d.string("display_material"),d.string("depleted_material"),TackleKind.valueOf(d.string("required_tool_kind")),d.int("min_tool_tier"),d.int("min_gathering_level"),d.whole("channel_ms",0),d.whole("respawn_seconds",0),d.bool("per_player_respawn",true),d.whole("life_xp",0),d.whole("adventurer_xp",0),yields("yields"),yields("rare_yields"),locations)
                    require(definition.toolKind in setOf(TackleKind.PICKAXE,TackleKind.SICKLE,TackleKind.HATCHET,TackleKind.EXTRACTOR) && definition.minToolTier in 1..ContentLimits.MAX_TACKLE_TIER && definition.minGatheringLevel in 1..lifeBalance.levelCap && definition.channelMillis in 250..60_000 && definition.respawnSeconds in 1..86_400 && definition.lifeXp in 0..100_000 && definition.adventurerXp in 0..100_000 && definition.yields.isNotEmpty() && definition.locations.isNotEmpty() && definition.locations.map { it.id }.distinct().size == definition.locations.size && (definition.yields+definition.rareYields).all { it.min in 1..64 && it.max in it.min..64 && it.chance in 0.0..1.0 && (it.pityAfter == null || it.pityAfter in 1..10000) }) { "ec.error.invalid_content" }
                    gatheringNodes[id]=definition
                }
            }
          } catch(error: Exception) { throw IllegalArgumentException("$id: ${error.message}",error) }
        }
        require(chapters.values.flatMap { it.strings("dungeons") }.all { it in dungeons }) { "ec.error.invalid_content" }
        dungeons.values.forEach { dungeon ->
            require(dungeon.area in areas && dungeon.boss in enemies && enemies[dungeon.boss]?.boss == true && dungeon.guards.all { enemies[it] != null } && dungeon.waves.all { wave -> wave.boss in enemies && wave.guards.all { it in enemies } }) { "${dungeon.id}: ec.error.invalid_content" }
            require(dungeon.unlockFlag.startsWith("ec:flag_") && dungeon.palette.all { it.matches(Regex("[A-Z_]+")) }) { "${dungeon.id}: ec.error.invalid_content" }
        }
        titles.values.forEach { title ->
            require(title.unlockFlag.matches(Regex("ec:flag_[a-z0-9_]+"))) { "${title.id}: ec.error.invalid_content" }
            val advancement=title.advancement ?: return@forEach
            if(advancement.parent?.startsWith("ec:title_") == true) require(titles[advancement.parent]?.advancement != null) { "${title.id}: ec.error.invalid_content" }
            if(advancement.condition.type == TitleConditionType.BESTIARY) require(advancement.condition.target in enemies) { "${title.id}: ec.error.invalid_content" }
            if(advancement.condition.type == TitleConditionType.FISH_SPECIES_COUNT) require(advancement.condition.count <= fishSpecies.size) { "${title.id}: ec.error.invalid_content" }
            if(advancement.condition.type == TitleConditionType.ENHANCE_LEVEL) require(advancement.condition.count <= tuning.enhancementCap) { "${title.id}: ec.error.invalid_content" }
            if(advancement.condition.type == TitleConditionType.CHALLENGE_RANK) require(advancement.condition.target in dungeons) { "${title.id}: ec.error.dungeon_required" }
        }
        validateWorldReferences(documents)
        validateLifeReferences()
    }
    private fun validateLifeReferences() {
        fun ref(id: String,type: String,field: String) { require(documents[id]?.get("type") == type) { "$field -> $id: ec.error.invalid_content" } }
        lifeBalance.fishingMilestones.plus(lifeBalance.gatheringMilestones).forEach { milestone -> require(chapters.values.any { milestone.flag in it.strings("flags") }) { "ec.error.invalid_content: ${milestone.flag}" } }
        tackle.values.forEach { ref(it.item,"item","${it.id}.item") }
        fishingSpots.values.forEach { spot ->
            require(spot.area in areas && areas.getValue(spot.area).world == spot.world && areas.getValue(spot.area).contains(spot.center)) { "${spot.id}: ec.error.invalid_content" }
            require(spot.unlockFlag == null || chapters.values.any { spot.unlockFlag in it.strings("flags") }) { "${spot.id}: ec.error.invalid_content: ${spot.unlockFlag}" }
        }
        fishSpecies.values.forEach { fish ->
            ref(fish.item,"item","${fish.id}.item")
            require(fish.habitats.all { it in areas || it in fishingSpots } && fishingSpots.values.any { spot -> fish.habitats.contains(spot.area) || fish.habitats.contains(spot.id) }) { "${fish.id}: ec.error.invalid_content" }
        }
        gatheringNodes.values.forEach { node ->
            (node.yields+node.rareYields).forEach { ref(it.item,"item","${node.id}.yields") }
            node.locations.forEach { location -> require(location.world == WorldIds.DEMO && location.area in areas && areas.getValue(location.area).contains(Position(location.world,location.x.toDouble(),location.y.toDouble(),location.z.toDouble()))) { "${node.id}/${location.id}: ec.error.invalid_content" } }
        }
        treasureChests.values.forEach { chest ->
            require(chest.position.world == WorldIds.DEMO && areas.values.any { it.contains(chest.position) }) { "${chest.id}: ec.error.invalid_content" }
            require(chest.items.keys.all { documents[it]?.get("type") == "item" }) { "${chest.id}: ec.error.invalid_content" }
            require(chapters.values.any { chest.onceFlag in it.strings("flags") }) { "${chest.id}: ec.error.invalid_content: ${chest.onceFlag}" }
        }
        val nodeCoordinates=gatheringNodes.values.flatMap { node -> node.locations.map { "${it.world}:${it.x},${it.y},${it.z}" } }
        require(nodeCoordinates.distinct().size == nodeCoordinates.size) { "ec.error.invalid_content" }
        fishingSpots.values.filter { it.defaultForArea }.groupBy { it.area }.forEach { (area,spots) -> require(spots.size == 1) { "$area: ec.error.invalid_content" } }
    }
    /** 指定エリアと敵配置を一度だけ照合し、一覧画面での繰り返し走査を避けます。 */
    val enemiesByArea: Map<String, List<EnemyDefinition>> by lazy {
        areas.values.associate { area ->
            area.id to enemies.values.filter { enemy -> enemy.spawns.any { area.contains(it.position) } }.sortedBy { it.id }
        }
    }
    /** 指定エリア内に出現点を持つ魔物をID順で返します。 */
    fun enemiesInArea(areaId:String):List<EnemyDefinition> {
        return enemiesByArea[areaId].orEmpty()
    }
}
