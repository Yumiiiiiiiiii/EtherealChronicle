# Content schema — Task 2 authoring contract

One UTF-8 YAML mapping per file under `content/<plural-type>/*.yml`. Common required fields: `schema_version: 1`, globally unique stable `id: ec:snake_case`, `type` (singular), `name` (Japanese, except balance). Preserve bible IDs (`ec:class_warrior`, `ec:quest_m01_awakening`, `ec:boss_void_warden`, etc.). Percentages are fractions (12% = 0.12). Timings use ticks where suffixed `_ticks`, otherwise milliseconds where `_ms`. All local node/objective/spawn-point IDs are snake_case without `ec:`. Do not put an entire catalog in one document. Defaults below may be omitted only where explicitly stated.

## Shared condition and reward mappings

`condition` is optional; omitted means true. Fields are ANDed: `min_level` (default 1), `has_flags: [ec:flag_*]`, `not_flags: [...]`, `completed_quests: [ec:quest_*]`, `active_quests: [...]`, `not_started_quests: [...]`, `classes: [ec:class_*]` (empty = any). Optional `any: [<condition>, ...]` adds an OR group. Flags and areas are declared in chapter documents and do not need separate files.

`rewards` defaults to `{}`: `xp: 0`, `currency: 0`, `items: [{item: ec:item_*, amount: 1, condition: {...}}]`, `set_flags: [...]`. Item reward conditions choose branch/class rewards. All grants use balance caps. Reward items and flags only apply once per quest completion; repeatable quests have a configured cooldown.

## Classes, skills and items

Classes retain Task 1 fields: `resource: RAGE|FOCUS|MANA`, `regeneration`, `hit_gain`, `stats`, `skills: [up to four skill IDs]`, `starter_items: [item IDs]`. Optional `growth` uses the same keys as stats and adds per level after level 1. Use bible class IDs. Stat keys: `hp`, `resource`, `attack`, `defense`, `crit`, `crit_damage`, `element_resist`, `skill_power`. SKILL_POWER is a multiplier (100 in the bible becomes 1.0; +4 becomes +0.04). Armor, crit and resource percentages are fractions.

Skills: `cost`, `cooldown_ms`, `multiplier`, `range`; optional `element: PHYSICAL|FIRE|ICE|LIGHTNING|AETHER|VOID` (PHYSICAL), `radius` (0, single target), `heal` (0, fraction of max HP), `defense_buff` (0), `buff_ticks` (0). Skill rank scaling remains in balance. All effect numbers are nonnegative.

Items: `material` (valid Paper Material), `rarity: COMMON|UNCOMMON|RARE|EPIC|LEGENDARY`, `slot: HAND|OFFHAND|HEAD|CHEST|LEGS|FEET|ACCESSORY|NONE`, `min_level`, `classes: []`, `stats: {}`, `budget` (base stat budget; must cover absolute weighted stats / rarity multiplier). Optional `bound: false`, `quest_item: false`, `price: 0` (base gold valuation), `weapon: 0`, `lore: Japanese text`, `effects: []` (supported effect definitions below). Custom item PDC keeps the stable ID, not display text. `ACCESSORY` uses an explicit equipment selection in the RPG equipment interface; it does not grant stats merely by being in storage.

Item `effects` entries: `{kind: HEAL|RESOURCE|CLEANSE|RETURN|REVIVE|ATTACK_PROC|DEFENSE_BUFF, amount: 0, cooldown_ms: 0, duration_ticks: 0, chance: 1.0, element: PHYSICAL}`. HEAL/RESOURCE amounts are flat; RETURN goes to the demo world's safe port spawn; REVIVE restores health to a living player in v1 (no offline resurrection). Passive effects require equipped items; consumables use slot NONE. Narrative-only mechanics should be represented in lore until an engine effect exists; do not invent effect kind strings.

## Quests

```yaml
schema_version: 1
id: ec:quest_m02_first_hunt
type: quest
name: 森の異変と初陣
condition:
  completed_quests: [ec:quest_m01_awakening]
objectives:
  - {id: slimes, kind: KILL, target: 'ec:enemy_aether_slime', amount: 5}
  - {id: wolves, kind: KILL, target: 'ec:enemy_thorn_wolf', amount: 3}
  - {id: report, kind: TALK, target: 'ec:npc_elena', amount: 1}
rewards:
  xp: 240
  currency: 120
  set_flags: []
  items: []
auto_complete: false
repeatable: false
repeat_cooldown_ms: 86400000
```

Objectives execute in listed order. Kinds KILL (enemy/boss ID), COLLECT (item ID, `consume: true` default), EXPLORE (declared area ID), TALK (NPC ID). `amount` is a positive integer. COLLECT checks current inventory and consumes only during successful completion. EXPLORE/TALK usually use amount 1. `auto_complete` defaults false: dialogue/command must turn it in after objectives are ready; starter/tutorial may set true. `condition` gates acceptance; branching flags can gate side quests. A complete quest never rewards twice; repeatable quests can be explicitly accepted again after cooldown. Legacy Task 1 `required_kills`/`xp`/`currency` fields are supported by adapter during transition, but new YAML should use objectives/rewards.

## Dialogues

```yaml
schema_version: 1
id: ec:dlg_celia_hub
type: dialogue
name: セリアの案内
speaker: ec:npc_celia
start: root
nodes:
  root:
    text: ようこそルミナ港へ！
    branches:
      - {condition: {has_flags: ['ec:flag_ch1_completed']}, next: ending}
    choices:
      - id: register
        label: 冒険者登録をお願いします
        condition: {not_started_quests: ['ec:quest_m01_awakening']}
        next: registered
        set_flags: []
        actions:
          - {kind: START_QUEST, target: 'ec:quest_m01_awakening'}
  registered: {text: 登録完了です。ヴァルカンに挨拶してください！, choices: []}
  ending: {text: おかえりなさい、英雄さん！, choices: []}
```

Node IDs and `next` are local keys, not content IDs. `next` omitted/empty closes dialogue. `branches` are evaluated in order before displaying a node; first matching condition redirects. Choices have stable local `id`, Japanese `label`, optional `condition`, `next`, `set_flags`, `clear_flags`, and `actions`. Action kinds: START_QUEST, COMPLETE_QUEST, SELECT_CLASS, OPEN_SHOP, ENTER_DUNGEON, GIVE_ITEM. `target` is the corresponding content ID (ENTER_DUNGEON target `ec:dungeon_void_sanctum`, declared in chapter); GIVE_ITEM may have `amount: 1` and `once_flag: ec:flag_*` to prevent repeated grants. Use once_flag on all free one-time gifts. `global_choices` (optional, dialogue top level) are choices appended to every node, each with the same fields as a node choice (`id`, `label`, `condition`, `actions`, ...); conditions filter them per player (e.g. airship travel after `ec:flag_ch2_started`). Action kind `TELEPORT_AREA`: `target` is an area ID, optional `required_flag` (`ec:flag_*`) gates it, optional `destination: {world: ec_world, x, y, z}` picks a bounded position; every destination must lie inside an `ec_world` area. `ENTER_DUNGEON` `target` selects which declared dungeon to enter (e.g. `ec:dungeon_glacial_citadel`). Choice side effects are revalidated server-side; no action is run from arbitrary chat text.

## NPCs and chapters/areas

NPC: `type: npc`, `entity: VILLAGER` (v1 Citizens-free implementation), `chapter: ec:chapter_1`, `area: ec:area_lumina_port`, `location: {world: ec_world, x: 0, y: 65, z: 0, yaw: 0}`, `dialogue: ec:dlg_celia_hub`, optional `shop: ec:shop_*`. NPCs are tagged, invulnerable, AI-disabled and noncolliding. `world` must be the plugin-managed demo world; no terrain is overwritten in user worlds.

Chapter: `type: chapter`, `min_level`, `max_level`, `quests: [IDs]`, `npcs: [IDs]`, `flags: [ec:flag_*]`, `dungeons: [ec:dungeon_void_sanctum]`, `areas: [{id: ec:area_lumina_port, name: ルミナ港, world: ec_world, min_x: -32, max_x: 32, min_y: 0, max_y: 256, min_z: -32, max_z: 32, safe: true}]`. Bounds are inclusive. These nested IDs are declarations for reference validation. Add later chapter files without modifying the loader. Locations/areas in the demo layout: port centered (0,65,0); forest (96,65,0); mine (192,65,0); hill (288,65,0); sanctum lobby (384,65,0). Keep area widths <=80 to avoid overlap. Dungeon arenas use reserved distant regions of ec_world per party, not user worlds.

## Shops

Shop: `type: shop`, `npc` optional, `buyback: true|false` (true), `discount_flag` optional, `discount: 0.1` (0), `entries: [...]` (max 45 for v1 page). Each entry: `id` local, `item`, `amount: 1`, `price: 0`, `tokens: [{item, amount}]`, `condition: {...}`, `daily_limit: 0` (unlimited), optional `token_discount_flag`, `token_discount: [{item, amount}]`. Prices nonnegative integer gold, token quantities positive integers. Gold sell value is floor(item.price * balance.sell_ratio), and items with price 0 cannot be sold. Bound/quest items cannot be sold/traded/dropped. All shops use the same sell ratio; discounts must remain below the buy/sell spread. Buy is left click; sell is right click on an eligible item in the player's inventory. Transactions use locks, recent IDs, rollback and immediate async currency save; item-consuming actions additionally call Paper saveData before payout (lead-approved narrow main-thread IO exception). Residual crash gaps are documented in RELEASE/DECISIONS.

## Enemies, drops and bosses

Enemy fields: `type: enemy|boss`, `entity` valid living EntityType, `level`, `hp`, `attack`, `defense`, `xp`, `currency` (fixed payout; use midpoint of bible range), `droptable` optional. Optional `element` (PHYSICAL), `weaknesses: []`, `resistances: []`, `speed: 0.23`, `size: 1`, `elite: false`, `aggro_range: 12`, `leash_range: 24`, `attack_interval_ticks: 40`, `traits: [KNOCKBACK_RESIST|PACK|FLEE|POISON|SLOW]`, `specials: [...]`, `spawn_points: [...]`, `set_flags_on_kill: []`.

Special entries: `{id: local_id, name: Japanese, shape: CIRCLE|LINE|CONE|TARGET, cooldown_ticks: 200, telegraph_ticks: 26, radius: 3, range: 12, multiplier: 1.5, min_hp_ratio: 0.0, max_hp_ratio: 1.0, effect: NONE|SLOW|POISON|KNOCKUP}`. Circle targets position at warning time (dodgeable); line/cone lock their direction before damage. Min/max HP ratios gate availability. Boss phase attacks use the same specials list.

Spawn point: `{id: local_id, world: ec_world, x: 96, y: 65, z: 0, cap: 2, respawn_ticks: 200}`. No natural/spawner/split tagging. Only explicit plugin spawn points pay RPG rewards. Spawn caps are per point and include unloaded/tracked entities until removed/reconciled; a global cap is in balance.

Boss adds `phases: [{id: phase_one, below_hp_ratio: 1.0, damage_multiplier: 1.0, interval_multiplier: 1.0}, ...]`, `enrage_ticks: 7200`, `enrage_multiplier: 1.5`, `coop_hp_per_player: 0.75`, `coop_damage_per_player: 0.04`. Ratios must descend, final phase may be 0.35. HP scale `1 + extraPlayers*coop_hp_per_player`; damage similarly. Bosses use virtual RPG HP where Paper attribute maxima cannot represent the bible HP. BossBar and particles display actual virtual health/telegraphs. Dungeon boss membership comes from the instance participant set.

Drop table retains `type: droptable`, `entries: [{item, chance, min, max}]` with chance 0..1 and 1<=min<=max<=64. Multiple independent rolls; no duplicate item entries. Class-biased guaranteed pools/pity are future extensions, so author explicit independent entries for v1 and record balance differences.

## Balance extensions and player schema

Keep the Task 1 balance fields/rarities; add `sell_ratio: 0.3`, `max_currency: 100000000`, `transaction_amount_cap: 64`, `party_size_cap: 4`, `party_xp_radius: 32`, `global_enemy_cap: 100`, `instance_cap: 8`, `instance_timeout_ticks: 36000`, `weakness_multiplier: 1.25`, `resistance_multiplier: 0.8`. Additional economy/AI/boss tuning lives here, not in code. Foundation caps must accommodate the authored boss damage/virtual HP. Class growth SKILL_POWER uses multiplier units, as above.

Player migration adds flags, objective maps/completion timestamps, daily purchase counts, bounded recent transaction IDs and `drop_pity` attempt counters to existing identity/class/level/currency fields. No content author writes playerdata directly. Current player schema is 5.

## Implemented runtime additions

Current profile schema is 5. `pending_rewards` maps stable item IDs to quantities; it is a durable mailbox used by transactions, quest/dialogue outputs and kill drops. `drop_pity` maps table/item keys to bounded consecutive failed-roll counts. `/ec claim` delivers only when inventory capacity permits. Core typed validation checks objective target kinds (KILL enemy/boss, COLLECT item, TALK NPC, EXPLORE area), condition quest/class references, action target kinds, unique token costs and positive item quantities. Errors include source file and owning ID. Paper additionally validates Material and living EntityType values before activation. Optional list/mapping fields reject malformed values instead of silently becoming empty defaults.

Balance also supports `skill_gcd_ms` (shipped 1000), `basic_attack_cooldown_ms` (550), `player_hp_cap` (1024), `element_res_cap` (0.5), `xp_curve` (POWER or POLYNOMIAL), and optional `xp_linear`, `xp_quadratic`, `xp_rounding` for POLYNOMIAL. Shipped POWER exponent is 1.575. Boss virtual HP uses stat_cap (100000), separate from the player's attribute cap. ATTACK_PROC effect amount is flat damage, not an attack multiplier.

Core skill fields optionally include `element`, `radius`, `heal`, `defense_buff`, `buff_ticks`; defaults preserve existing single-target damage skills. Supported item effects and their amount units are HEAL/REVIVE (HP), RESOURCE (resource points), ATTACK_PROC (flat damage), DEFENSE_BUFF (defense points), CLEANSE and RETURN (no damage amount). Chance is 0..1; cooldown is milliseconds, duration is ticks. Equipment passives and consumable execution are handled separately.

## Side-content engine additions (Task 5)

- **Classes:** optional `icon` (Paper Material name) shown in menus.
- **Skills:** optional `party_heal` / `party_defense_buff` (bool, apply to online party members within `ally_radius`), `taunt_ticks` (timed taunt on hit mobs), `threat_multiplier` (bounded). Active RPG mobs keep bounded per-player threat and honor taunts.
- **Calendar resets (balance):** `calendar_reset_zone` (default UTC), `daily_reset_hour` (0-23), `weekly_reset_day` (e.g. MONDAY). Quests opt in with `reset_schedule: DAILY|WEEKLY`; shop daily limits use the same calendar day. Profile stores each quest's completed reset-period key.
- **Challenge dungeons:** a chapter `dungeon_configs` entry may declare ordered `waves: [{boss, guards, guard_offsets}]` (`boss` resolves to an enemy or boss ID) and `score_ranks: [{rank: S|A|B|C, max_seconds}]` completion-time thresholds. A wave advances only when its boss is defeated; elapsed seconds, rank and each member's personal best are persisted (`/ec dungeon records`).
- **Titles:** content type `title` (`titles/*.yml`): `id`, `name`, `description`, `unlock_flag` (declared `ec:flag_*`). `/ec title` lists, equips and clears; the selected title prefixes chat/tab names and reverts if the flag is absent.
- **Codex:** profile persists enemy kill counts and discovered item IDs; `/ec codex` shows area completion, enemy detail (weaknesses/drops) and item pages. Area milestones at 25/50/75/100% set flag `ec:flag_codex_<area>_<percent>` once.
- **Enhancement (balance):** `enhancement_cap` (5), `enhancement_rate` (0.03 per level), `enhancement_base_currency` (cost = base x next level), `enhancement_material` (item ID, one per level). `/ec enhance` stores the level in item PDC; stats are clamped to the rarity budget and global caps. Costs persist before the PDC write (loss-biased, no duplication).
- Profile schema is now 9 (migrations from 1..8).

### Title advancements (Task 7)

`titles/*.yml` may add an optional `advancement` block: `title`, `description`, `icon` (Material), `parent`, `frame` (TASK/GOAL/CHALLENGE) and `condition: {type: FLAG|BESTIARY|COLLECTION, target, count}`. `unlock_flag` stays the authoritative persisted unlock; a met advancement condition promotes that flag into the profile, and advancement progress is re-synced on join, transaction completion, once per second and content reload. The plugin generates an owned datapack with an `EtherealChronicle` advancement tab (supported Paper `DatapackManager` API, no `UnsafeValues.loadAdvancement`); if registration fails, profile-backed titles still work. Not yet verified on a live server or client.

## Life professions: fishing and gathering (Task 8)

Five content types (all `schema_version: 1`, `ec:` IDs) plus a balance document. Fishing is a fully custom system (vanilla `PlayerFishEvent` rewards are suppressed); gathering uses fixed nodes with vanilla block breaking blocked. Life items use `slot: NONE`. Profile schema is 10.

- **`tackle`** (`tackle/*.yml`): `item`, `kind` (ROD|BAIT|...), `tier`, `min_life_level`, `wait_reduction`, `sweet_spot_bonus`, `tension_stability`, `size_bonus`, `channel_speed_bonus`, `rare_chance_bonus`, `preferred_tags`, `preferred_tag_multiplier`, `rarity_weights` (per rarity multiplier).
- **`fishing_spot`**: `name`, `area`, `world`, `center {x,y,z}`, `radius`, `default_for_area`, `tags`.
- **`fish_species`**: `name`, `item`, `rarity`, `tags`, `habitats` (area IDs), `time_windows` (e.g. NIGHT, DAWN_DUSK), `weather`, `min_rod_tier`, `min_fishing_level`, `base_weight`, `min_cm`/`max_cm`, `silver_crown_cm`/`gold_crown_cm`, `life_xp`, `adventurer_xp`, `lore_hint`, and `minigame {bite_window_ms, difficulty, sweet_width, progress_per_sec, tension_decay_per_sec, thrash_chance_per_sec, dive_chance_per_sec}`.
- **`gathering_node`**: `name`, `node_kind` (ORE|HERB|WOOD|CRYSTAL), `display_material`, `depleted_material`, `required_tool_kind`, `min_tool_tier`, `min_gathering_level`, `channel_ms`, `respawn_seconds`, `per_player_respawn`, `life_xp`, `adventurer_xp`, `yields [{item,min,max,chance}]`, `rare_yields [{item,min,max,chance,pity_after}]`, `locations [{id,area,world,x,y,z}]` (every location must lie inside an `ec_world` area).
- **`life_balance`** (`balance/life_skills.yml`): `life_level_cap`, `life_xp_base`, `life_xp_exponent`, `cast_range_blocks`, `cast_cooldown_ms`, `max_concurrent_fishing_sessions`, fatigue (`fatigue_window_seconds`, `fatigue_soft_threshold`, `fatigue_hard_threshold`, `fatigue_cooldown_seconds`), `reeling_timeout_seconds`, `slack_fail_seconds`, `party_gather_rare_bonus`, `fishing_score_milestones [{score,flag}]`, `gathering_milestones [{total_harvests,unique_nodes,flag}]`.

Sessions are bounded and ticked only for active anglers; no per-tick world scans. Repeated casts follow an anti-macro check and fatigue. Fish records (size, crowns), fishing score and harvest counts persist in the profile and feed `/ec codex`, titles and advancements.

Anti-macro settings in `life_balance`: `macro_min_samples` (8), `macro_response_deviation_ms` (18.0), `macro_cast_interval_deviation_ms` (120.0), `macro_rare_weight_multiplier` (0.1). A player whose reaction or cast-interval deviation stays below the thresholds over the sample window is flagged and gets reduced rare-fish weight and fatigue. Admin QA commands: `/ec debug fish start|status|cancel <player> [species]` (starts at the reel phase through the real UI/reward path) and `/ec debug gather status <player>`.

## Early-game engine additions (Task 11)

- **Quest objectives:** `CATCH_FISH` (fish species), `GATHER_NODE` (gathering node) and `ENHANCE_GEAR` join KILL/COLLECT/TALK/EXPLORE. Unknown objective strings fail typed parsing.
- **Title conditions:** `FISH_SPECIES_COUNT`, `LIFE_LEVEL`, `ENHANCE_LEVEL` and `CHALLENGE_RANK` join FLAG/BESTIARY/COLLECTION. Profile schema is now 11.
- **`treasure_chest`** (`treasure_chests/*.yml`): `world`/`x`/`y`/`z` (or `location`), `once_flag`, `items` (map or list), `currency`, `hint_particle`. Shown as an invulnerable BlockDisplay with an Interaction; claimed once per player through the transactional pending-reward mailbox. Item rewards require a declared once flag.
- **Fishing spots:** optional `priority` and `unlock_flag`; selection is deterministic by priority, then radius.
- **Time/weather conditions:** NPCs and enemy `spawn_points` accept `time_windows` (`ANY|DAY|NIGHT|DAWN_DUSK`) and `weather` (`ANY|CLEAR|RAIN|THUNDER`). NPCs reconcile once per second; spawn checks only touch definitions and active point budgets.
- **Dungeon ranks:** `score_ranks[]` entries accept `first_clear_flag` and `first_clear_items`; rewards are awarded once (idempotency flag and mailbox saved together).
- **World:** `ec_world` v3 to v4 adds the Ashwood hidden spring (additive, idempotent, air-only).

## Resource pack, datapack and UI additions (Task 15)

- **Config:** `config.yml` `language` (auto|ja_jp|en_us) and `resource_pack` (`enabled`, `required`, `mode: embedded_http|external_url`, `external_url`, `external_sha1`, `embedded_http {bind_host, public_host, port, max_concurrent}`). The embedded server only serves the built pack (GET/HEAD, validated paths, bounded concurrency) with SHA-1 sent through Adventure `ResourcePackRequest`; per-player accept/decline status is tracked in the profile (schema 12).
- **Datapack:** the plugin registers a bundled datapack through the Paper lifecycle API (custom wolf variants and six biomes).
- **Content visuals (optional):** class/item `item_model` (namespaced model ID from `resourcepack/`) and enemy `visual.variant` (namespaced wolf-variant ID). Without a confirmed pack the UI falls back to built-in Materials, so content never requires the pack.
- **UI:** class selection, shops, codex and enhancement use InvUI (shaded, relocated, MIT notice); dialogue uses the Paper Dialog API; the HUD stays on the existing ActionBar.
