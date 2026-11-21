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

Node IDs and `next` are local keys, not content IDs. `next` omitted/empty closes dialogue. `branches` are evaluated in order before displaying a node; first matching condition redirects. Choices have stable local `id`, Japanese `label`, optional `condition`, `next`, `set_flags`, `clear_flags`, and `actions`. Action kinds: START_QUEST, COMPLETE_QUEST, SELECT_CLASS, OPEN_SHOP, ENTER_DUNGEON, GIVE_ITEM. `target` is the corresponding content ID (ENTER_DUNGEON target `ec:dungeon_void_sanctum`, declared in chapter); GIVE_ITEM may have `amount: 1` and `once_flag: ec:flag_*` to prevent repeated grants. Use once_flag on all free one-time gifts. Choice side effects are revalidated server-side; no action is run from arbitrary chat text.

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

Player migration adds flags, objective maps/completion timestamps, daily purchase counts and bounded recent transaction IDs to existing identity/class/level/currency fields. No content author writes playerdata directly. Exact schema number is owned by the loader/migration implementation and tested on each change.
