# Code Review 01 — Paper Layer & Core/Data Integration (`codex/foundation` @ `cdf01e9`)

- **Reviewer**: Antigravity (independent read-only audit; `src/main` untouched)
- **Target commit**: `codex/foundation` tip `cdf01e9` (`merge: integrate authored Chapter 1 YAML and contract tests` on top of `743604f`)
- **Reviewed files**:
  - `src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt` (537 lines)
  - `src/main/kotlin/net/nakaymi/etherealchronicle/paper/AdventureController.kt` (243 lines)
  - `src/main/kotlin/net/nakaymi/etherealchronicle/paper/EnemyController.kt` (179 lines)
  - `src/main/kotlin/net/nakaymi/etherealchronicle/paper/DungeonController.kt` (61 lines)
  - `src/main/kotlin/net/nakaymi/etherealchronicle/paper/DemoWorld.kt` (73 lines)
  - `src/main/kotlin/net/nakaymi/etherealchronicle/paper/PaperInventoryAccess.kt` (48 lines)
  - `src/main/kotlin/net/nakaymi/etherealchronicle/data/PlayerStore.kt` (92 lines)
  - `src/main/kotlin/net/nakaymi/etherealchronicle/data/Content.kt` & `src/main/resources/plugin.yml`

## Verified Strengths
- **YAML deserialization safety**: Both `ContentService` (`Content.kt:30`) and `PlayerStore` (`PlayerStore.kt:36`) use SnakeYAML `SafeConstructor` with `isAllowDuplicateKeys = false` (and `maxAliasesForCollections = 20` in `ContentService`), blocking arbitrary class instantiation and YAML alias-bomb attacks.
- **Atomic playerdata writes**: `PlayerStore.write()` writes to `.tmp`, calls `FileChannel.force(true)`, rotates `.bak` atomically, and atomically replaces the target YAML on a single-threaded IO queue (`ec-player-io`).
- **Demo world safety**: `DemoWorld.ensure()` refuses to touch an existing `ec_world` directory lacking `.etherealchronicle-owned`.
- **Admin permission gating**: `RpgPlugin.onCommand()` enforces `etherealchronicle.admin` (default `op` in `plugin.yml`) before all administrative subcommands (`reload`, `validate`, `world`, `currency`, `spawn`, `starter`, `give`, `setlevel`, `class`, `debug`).
- **Party XP/currency conservation**: `PartyService.splitXp()` conserves total XP/currency with deterministic remainder distribution, preventing party XP/gold duplication.

---

## Findings Summary

| ID | Severity | Area | Location | Summary |
|---|---|---|---|---|
| HIGH-01 | **HIGH** | Combat / Exploit | `RpgPlugin.kt:296-305`, `EnemyController.kt:142-153` | Cancelled `EntityDamageEvent` removes i-frames & attack cooldown -> uncapped CPS auto-attack DPS & Rage farming |
| HIGH-02 | **HIGH** | Combat / Correctness | `RpgPlugin.kt:275-295` | `ATTACK_PROC` passes flat `effect.amount` as damage multiplier -> 10,000-dmg one-shot procs; `HEAL`/`RESOURCE` item procs ignored |
| HIGH-03 | **HIGH** | Drops / Boss Credit | `RpgPlugin.kt:320-344` | Uncaught `transaction` failure on full inventory aborts party reward loop, wipes boss kill flag, and skips `dungeons.defeated()`; locked players silently skipped |
| HIGH-04 | **HIGH** | Dupe / Bound Bypass | `AdventureController.kt:172-177` | Bound & quest items can be placed in chests via `ClickType.SWAP_OFFHAND` (`F`), Bundles, or Item Frames / Armor Stands |
| HIGH-05 | **HIGH** | Lifecycle / Item Loss | `RpgPlugin.kt:94-103, 132-136`, `AdventureController.kt:211-238`, `PlayerStore.kt:88` | `onDisable()` leaks dungeons/enemies/BossBars/NPCs; quit/shutdown mid-transaction loses gold/tokens permanently; non-blocking `store.shutdown()` risks reload IO race |
| HIGH-06 | **HIGH** | Quest / Class Wiring | `RpgPlugin.kt:356-359, 434-436, 129`, `AdventureController.kt:139` | `/ec quest start\|complete` bypasses NPC proximity & dialogue gating; dialogue `SELECT_CLASS` never grants starter items, skill slot, or stats |
| MED-01 | **MEDIUM** | Stability / TPS | `EnemyController.kt:78-79` | `FLEE` trait calls `.normalize()` on potential zero vector -> `NaN` velocity throws and aborts entire 10-tick mob/boss loop |
| MED-02 | **MEDIUM** | TPS / Dungeon Leak | `DungeonController.kt:28-32`, `AdventureController.kt:202`, `RpgPlugin.kt:382-385` | Synchronous 3,185-block rebuild on every dungeon entry; Return Scroll & `/ec world` leave ghost dungeon instances locked for 30 min |
| MED-03 | **MEDIUM** | TPS / GC Churn | `AdventureController.kt:52-63, 195-204`, `PaperInventoryAccess.kt:41-44`, `RpgPlugin.kt:199-201` | Every potion drink calls blocking `player.saveData()` on main thread; per-second `p.snapshot()` deep-copy & double `effectiveStats()` |
| MED-04 | **MEDIUM** | Memory Leak | `RpgPlugin.kt:272-273`, `AdventureController.kt:186` | `defenseBuffs`, `procReady`, `consumableReady` never pruned on quit or tick |
| MED-05 | **MEDIUM** | Safe Zone / AI | `EnemyController.kt:65, 105-110, 116-121` | Enemies target Creative/Spectator players and apply Poison/Slow/Knockup even when damage is cancelled in safe zones |
| MED-06 | **MEDIUM** | Injection / Admin | `AdventureController.kt:64, 165, 206`, `RpgPlugin.kt:398-422` | `MiniMessage.deserialize` called on exception messages; `/ec give` can drop bound items on ground; `/ec class`/`setlevel` skip lock & skill cleanup |
| LOW-01 | **LOW** | Reload / Startup | `RpgPlugin.kt:47-74, 363-379` | Listeners registered after async startup; `/ec reload` leaves stale `RpgMenu` GUIs open |
| LOW-02 | **LOW** | Code Hygiene / UX | `EnemyController.kt:42, 46`, `AdventureController.kt:107-111` | Duplicate `Slime.size` assignment; shop GUI shows raw `ec:item_*` token IDs and undiscounted price |
| LOW-03 | **LOW** | Code Hygiene | `AdventureController.kt:38`, `RpgPlugin.kt:116` | Hardcoded `"ec_world"` instead of `DemoWorld.NAME`; `ClassMenu` stores class IDs under `itemKey` |

---

## Detailed Findings

### [HIGH-01] Uncapped Auto-Attack DPS & Rage Generation Exploit (Zero i-frames / No Attack Cooldown)
- **Locations**:
  - `src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt:296-305`
  - `src/main/kotlin/net/nakaymi/etherealchronicle/paper/EnemyController.kt:142-153`
- **Problem**:
  `EnemyController.virtualDamage` (`HIGHEST`) unconditionally sets `e.isCancelled = true` to manage virtual HP. Because the Bukkit `EntityDamageEvent` is cancelled, Paper/CraftBukkit never sets `noDamageTicks` (`invulnerableTime`) on the target mob. Meanwhile `RpgPlugin.damage` intercepts every melee hit without checking `player.attackCooldown` (vanilla swing recharge) or enforcing a minimum attack interval, calling `hit(attacker, target, 1.0)` at 100% full weapon damage every click—and `virtualDamage` calls `game.onHit(...)` on every single hit.
- **Repro**:
  1. Approach any tracked enemy or boss (e.g., `ec:boss_void_warden`).
  2. Spam left-click at 10–20 CPS (or hold an autoclicker).
  3. Every click deals 100% unscaled RPG attack damage (10–20× intended DPS) and grants +10 Rage per click (100–200 Rage/sec), melting bosses in seconds.
- **Recommended Fix**:
  - Enforce a per-player melee attack cooldown (e.g., track `lastBasicAttackMs[attacker.uniqueId]` with a weapon/global interval such as 500–600 ms, or multiply damage by `attacker.attackCooldown` and ignore hits below a threshold like `0.4f` for `onHit`/procs).
  - Separately throttle virtual hits per `(attacker, entity)` or apply explicit virtual `noDamageTicks` tracking inside `MobState` for basic attacks (while allowing distinct skills to land cleanly).

---

### [HIGH-02] `ATTACK_PROC` Uses Flat Damage `effect.amount` as Multiplier -> 10,000-Damage One-Shot Procs
- **Locations**:
  - `src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt:275-285`
  - `src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt:287-295`
- **Problem**:
  `procEffects()` executes:
  ```kotlin
  if(effect.kind == "ATTACK_PROC" && !target.isDead) hit(player,target,effect.amount,effect.element)
  ```
  `hit()` treats its 3rd argument as a **damage multiplier** (`stats.attack * (multiplier * s.skillPower)`). In Chapter 1 Epic/Legendary weapons (e.g., `wpn_void_cleaver.yml` with `amount: 95`, `wpn_astral_piercer.yml` with `amount: 110`, `wpn_aether_vanguard_sword.yml` with `amount: 55`), `amount` is authored as flat bonus damage. Passing `95.0` as `multiplier` produces `150 * 95 * 1.5 = 21,375` (clamped to `damage_cap: 10000`) on a single proc—killing a 34,000 HP boss in 4 swings.
  Additionally:
  1. `procEffects()` filters `effect.kind !in setOf("ATTACK_PROC", "DEFENSE_BUFF")` (`RpgPlugin.kt:279`), silently ignoring `HEAL` and `RESOURCE` passive effects authored on Legendary items (`wpn_void_cleaver`, `wpn_aether_chronicle_staff`, `acc_void_heart_ring`).
  2. Calling `hit()` inside `procEffects()` re-enters `virtualDamage` -> `procEffects()` recursively; while `procReady` stops the same item from re-proccing immediately, two equipped items with `ATTACK_PROC` can chain-trigger each other on a single swing.
- **Repro**:
  1. `/ec give <player> ec:item_wpn_void_cleaver` at Lv18.
  2. Basic-attack `ec:boss_void_warden` until the 35% proc triggers -> boss loses ~10,000 HP instantly.
- **Recommended Fix**:
  - Clarify and enforce the contract: either compute `ATTACK_PROC` as flat bonus damage (`effect.amount * defenseScale / (defenseScale + defense) * elementMod`, not multiplied by `stats.attack`), **or** document `ATTACK_PROC.amount` as a multiplier (`0.1`–`3.0`), validate `amount <= 3.0` in `ContentService`, and update Chapter 1 weapon YAMLs accordingly.
  - Add a re-entrancy guard `private val proccing = mutableSetOf<UUID>()` so proc hits never trigger secondary procs.
  - Handle `HEAL` and `RESOURCE` passive triggers in `procEffects()`.

---

### [HIGH-03] Full Inventory or Locked Player in `rewardKill` Aborts Party Reward Loop, Wipes Boss Flags & Skips `dungeons.defeated()`
- **Locations**:
  - `src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt:320-344`
  - `src/main/kotlin/net/nakaymi/etherealchronicle/paper/AdventureController.kt:211-238`
- **Problem**:
  In `RpgPlugin.death()` -> `rewardKill()`:
  ```kotlin
  eligible.forEach { player ->
      val profile=profiles.getValue(player.uniqueId)
      adventure.transaction(player,{ inv ->
          val drops=game.kill(profile,id,inv,xp[player.uniqueId] ?: 0,currency[player.uniqueId] ?: 0,player.uniqueId == loot)
          require(inv.exchange(emptyMap(),drops)) { "戦利品を受け取る空きがありません。" }
      }) { ... }
  }
  ```
  1. If the loot recipient's inventory is full when a boss/mob dies, `require(inv.exchange(emptyMap(), drops))` throws `IllegalArgumentException`. `AdventureController.transaction()` rolls back `profile` (wiping that player's XP, currency, quest KILL progress, and boss kill flag `ec:flag_void_warden_defeated`) and **rethrows**.
  2. Because neither `rewardKill()` nor `death()` catches the exception, `eligible.forEach` terminates immediately—**all remaining party members receive zero XP, zero currency, zero quest credit, and zero boss kill flags**, and `dungeons.defeated(e.entity.uniqueId)` on line 325 is **never called**.
  3. On line 331, `eligible` filters `!adventure.locked(p.uniqueId)`. Any party member who drank a potion a few milliseconds before the killing blow is silently excluded from `eligible` and permanently misses boss kill credit and flags.
- **Repro**:
  1. Enter `ec:dungeon_void_sanctum` with a 2-player party; fill Player A's inventory completely (all 36 slots occupied).
  2. Defeat `ec:boss_void_warden` with Player A selected as `lootRecipient`.
  3. `IllegalArgumentException: 戦利品を受け取る空きがありません。` is thrown in `EntityDeathEvent`; Player A gets rolled back, Player B gets nothing, and the dungeon never marks `completed = true`.
- **Recommended Fix**:
  - Do not fail `game.kill` on full inventory: commit XP, currency, kill flags, and quest progress unconditionally, and deliver drops with natural ground-drop fallback (matching `AdventureController.kt:228-231`!).
  - Wrap each player's reward block in `runCatching { ... }.onFailure { logger.severe(...) }` so one player can never abort rewards for other party members or skip `dungeons.defeated()`.
  - If a player is temporarily `locked` at kill time, defer their kill reward by 1–5 ticks via `sync` (or decouple non-item-removing kill credit from the trade lock).

---

### [HIGH-04] Bound & Quest Items Bypass via `ClickType.SWAP_OFFHAND` (`F`), Bundles, and Item Frames
- **Locations**:
  - `src/main/kotlin/net/nakaymi/etherealchronicle/paper/AdventureController.kt:172-177`
- **Problem**:
  `tradeBound()` blocks moving bound/quest items into external inventories by checking `bound(e.currentItem)`, `bound(e.cursor)`, and `e.hotbarButton >= 0`. It misses three standard Survival transfer paths:
  1. **`ClickType.SWAP_OFFHAND` (`F` key)**: Put a bound/quest item in the offhand slot, open any chest/barrel/hopper (`external == true`), hover an empty container slot, and press `F`. `currentItem` is `null`, `cursor` is air, `hotbarButton` is `-1`, so `tradeBound()` allows the swap and places the bound item directly into the chest.
  2. **Vanilla Bundles**: Inside the player's own inventory (`external == false`), right-clicking a bound item onto a Bundle stores it inside the Bundle; `bound(bundleStack)` returns `false`, so the Bundle can then be dropped or placed in chests/hoppers freely.
  3. **Item Frames / Armor Stands / Allays**: Right-clicking an Item Frame or Armor Stand with a bound item places it into the world without triggering `PlayerDropItemEvent` or `InventoryClickEvent`.
- **Repro**:
  1. Equip any `bound: true` or `quest_item: true` item (e.g., `ec:item_key_guild_badge`) in offhand.
  2. Open a chest, point cursor at an empty chest slot, press `F`.
  3. Item transfers into the chest; a second player can take it.
- **Recommended Fix**:
  - In `tradeBound()`: also check `e.click == ClickType.SWAP_OFFHAND && bound((e.whoClicked as Player).inventory.itemInOffHand)` when `external` is true (and check `e.click == ClickType.SWAP_OFFHAND` on slot 8 in `RpgPlugin.click` for full defense-in-depth).
  - Cancel bundle insertion whenever either `e.cursor` or `e.currentItem` is a bundle (`Tag.ITEMS_BUNDLES`) and the other is `bound(...)`.
  - In `PlayerInteractEntityEvent` / `PlayerInteractAtEntityEvent`, cancel placing `bound` items into `ItemFrame`, `GlowItemFrame`, `ArmorStand`, or `Allay`.

---

### [HIGH-05] Incomplete `onDisable()` Cleanup, Quit/Shutdown Transaction Item Loss, and `PlayerStore` Reload Race
- **Locations**:
  - `src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt:94-103, 132-136`
  - `src/main/kotlin/net/nakaymi/etherealchronicle/paper/AdventureController.kt:211-238`
  - `src/main/kotlin/net/nakaymi/etherealchronicle/data/PlayerStore.kt:88-92`
- **Problem**:
  1. **Resource & Entity Leaks on Disable**: `RpgPlugin.onDisable()` cancels scheduler tasks and shuts down stores, but **never calls** `dungeons.shutdown()`, `enemies.close()`, or `adventure.close()`. Active dungeon players remain stranded at `(1024+slot*192, 65, 1024)`, BossBars stay attached, spawned mobs/NPCs remain orphaned in `ec_world`, and in-memory maps (`dirty`, `choosing`, `lastMoved`, `defenseBuffs`, `procReady`, `simulated`) are not cleared.
  2. **Guaranteed Item/Gold Loss on Disconnect or Disable Mid-Transaction**: In `AdventureController.transaction()`, `p.currency` and `staged.remove` are mutated and `player.saveData()` is called **before** `plugin.persist(p)` finishes asynchronously. If the player disconnects during the async write window, `RpgPlugin.onQuit()` removes `p` from `profiles` and calls `save(p)` with the already-deducted currency, and when `persist(p)` returns, `player.isOnline` is `false` so `staged.add` is **never delivered** (only logged as a warning). The exact same loss occurs on `onDisable()` because `cancelTasks(this)` drops the `plugin.sync { ... }` delivery callback while `onDisable()` saves the already-deducted profile.
  3. **Non-Blocking `PlayerStore.shutdown()`**: `onDisable()` calls `store.shutdown()` (which only calls `executor.shutdown()` without `awaitTermination`), unlike `PlayerStore.close()`. On plugin reload or fast process exit, queued IO writes can race with a newly enabled `PlayerStore` instance or be truncated.
- **Repro**:
  1. Spawn NPCs and enter a dungeon with a boss; disable/reload the plugin -> players stay in the distant arena, BossBars & entities remain, and `dungeons.shutdown()` never ran.
  2. Start a shop purchase (or quest turn-in) and disconnect while `ec-player-io` is flushing -> player rejoins with gold/tokens gone and zero purchased/rewarded items.
- **Recommended Fix**:
  - In `onDisable()`, call `if (::dungeons.isInitialized) dungeons.shutdown()`, `if (::enemies.isInitialized) enemies.close()`, `if (::adventure.isInitialized) adventure.close()` **before** `server.scheduler.cancelTasks(this)`.
  - Call `store.close()` / `simStore.close()` (with bounded timeout) instead of fire-and-forget `shutdown()`.
  - To make transactions crash/disconnect-safe for `staged.add`, persist undelivered `pendingRewards: MutableMap<String, Int>` inside `PlayerProfile` atomically with the currency/token mutation, clear `pendingRewards` once delivered to inventory, and drain `pendingRewards` on next login in `RpgPlugin.join()`.

---

### [HIGH-06] `/ec quest start|complete` Bypasses NPC/Dialogue Gating, and Dialogue `SELECT_CLASS` Omits Starter Setup
- **Locations**:
  - `src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt:129, 162-165, 356-359, 434-436`
  - `src/main/kotlin/net/nakaymi/etherealchronicle/paper/AdventureController.kt:139`
- **Problem**:
  1. Any player without admin permissions can invoke `/ec quest start <id>` and `/ec quest complete <id>` from anywhere in the world (compare `/ec shop <id>` on lines 429–431, which requires 8-block proximity to the shop NPC). This bypasses NPC travel, dialogue branching conditions (such as mutually exclusive Choice 1/2/3 flags set on dialogue choices), and lets players turn in `COLLECT` quests remotely mid-dungeon.
  2. When a player selects a class via Vulcan's NPC dialogue (`SELECT_CLASS` in `AdventureController.kt:139`), it only calls `plugin.game.chooseClass(p, a.target)`—it does **not** grant `c.starterItems`, set `p.starterGranted = true`, equip the slot-8 skill star (`skillSlot(player)`), or call `applyStats(player, p)`. Conversely, `RpgPlugin.join()` + `closeMenu()` forces the popup `ClassMenu` on join so new players cannot reach Celia/Vulcan before picking a class.
- **Repro**:
  1. As a non-op player anywhere in `ec_world`, run `/ec quest start ec:quest_s02_slime_gel` and `/ec quest complete ec:quest_s02_slime_gel` without ever visiting Mira.
  2. Trigger a `SELECT_CLASS` dialogue choice on a player with `classId == null` -> player gets no starter weapon, no slot-8 skill item, stays at 20 HP, and gets forced into `ClassMenu` on next login because `starterGranted` remained `false`.
- **Recommended Fix**:
  - Keep `/ec quest` (list/progress) available to players, but remove or admin-gate `/ec quest start|complete` (or enforce NPC proximity + dialogue eligibility).
  - Route `SELECT_CLASS` in `AdventureController.click` through a shared `plugin.grantClassAndStarter(player, p, a.target)` helper that atomically sets `classId`, `starterGranted = true`, grants `starterItems` via `staged`, sets `skillSlot`, and calls `applyStats`.

---

### [MEDIUM-01] `FLEE` Trait Zero-Vector `.normalize()` Produces `NaN` Velocity and Crashes `EnemyController.tick()`
- **Location**: `src/main/kotlin/net/nakaymi/etherealchronicle/paper/EnemyController.kt:78-79`
- **Problem**:
  ```kotlin
  if("FLEE" in d.traits && state.boss.hp/state.boss.maximumHp < 0.25 && tick%80 < 40) {
      entity.velocity=entity.location.toVector().subtract(target.location.toVector()).normalize().multiply(d.speed).setY(0.1)
  }
  ```
  If `entity.location` equals `target.location`, `.normalize()` returns `(NaN, NaN, NaN)`. Paper's `Entity.setVelocity()` rejects non-finite coordinates with `IllegalArgumentException`, aborting the entire `EnemyController.tick()` run (skipping remaining mobs, boss telegraphs, damage warnings, and spawns for that tick).
- **Recommended Fix**:
  Guard `lengthSquared() > 1e-6` before calling `.normalize()` (same pattern already used on line 88).

---

### [MEDIUM-02] Synchronous 3,185-Block Dungeon Rebuild & Ghost Instances on Return Scroll / `/ec world`
- **Locations**:
  - `src/main/kotlin/net/nakaymi/etherealchronicle/paper/DungeonController.kt:28-32`
  - `src/main/kotlin/net/nakaymi/etherealchronicle/paper/AdventureController.kt:202`
  - `src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt:382-385`
- **Problem**:
  1. `DungeonController.enter()` writes 3,185 blocks synchronously on the main thread every single time a party enters a slot (`49*49` floor + 4-high perimeter walls), causing avoidable TPS spikes if repeatedly entered/exited.
  2. Using `ec:item_con_return_scroll` (`RETURN` effect) or `/ec world` teleports a player out of the dungeon without calling `dungeons.leave(player)`. The player remains in `instance.members`, cannot re-enter (`"既にダンジョン内です"`), pins the instance slot & boss `SpawnBudget` for 30 minutes (`36000` ticks), and gets unexpectedly teleported back to their old return location when the timeout triggers.
- **Recommended Fix**:
  - Track `builtSlots: MutableSet<Int>` (only build each arena slot once per server lifecycle, or persist a world marker).
  - Call `dungeons.leave(player)` when executing `RETURN` or `/ec world`, or handle cross-region exit in `PlayerTeleportEvent` when leaving the dungeon zone (`x >= 900 && z >= 900`).

---

### [MEDIUM-03] Blocking `player.saveData()` on Every Consumable Use + Per-Second `PlayerProfile.snapshot()` & Double `effectiveStats()`
- **Locations**:
  - `src/main/kotlin/net/nakaymi/etherealchronicle/paper/AdventureController.kt:52-63, 195-204`
  - `src/main/kotlin/net/nakaymi/etherealchronicle/paper/PaperInventoryAccess.kt:41-44`
  - `src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt:199-201`
- **Problem**:
  1. `AdventureController.consume()` uses `transaction()` -> `PaperInventoryAccess.exchange(mapOf(id to 1), emptyMap())`, which calls synchronous main-thread `player.saveData()` on **every potion/food consumption**—turning a rare trade safeguard into frequent main-thread disk I/O during combat.
  2. `AdventureController.tick()` allocates a full deep copy `p.snapshot()` every second for every online player just to detect whether `EXPLORE`/`COLLECT` changed quest state, and calls `quests.event(p, COLLECT, "", 1, inventory)` scanning all 36 slots per collect objective every second.
  3. `RpgPlugin.tick()` calls `effectiveStats(p, profile)` on line 200 and then calls `applyStats(p, profile)` which calls `effectiveStats(p, profile)` a second time (each scanning armor + hand + offhand + accessory storage).
- **Recommended Fix**:
  - Pass a `saveInventoryOnConsume: Boolean` flag (true for currency-paying/token trades & quest turn-ins, false for routine potion drinking).
  - Have `QuestEngine.event()` return `Boolean` (changed) instead of diffing `p.snapshot()` every second, and compute `applyStats(p, s)` using the already-computed `Stats`.

---

### [MEDIUM-04] Unbounded Memory Leaks in Cooldown / Buff Maps
- **Locations**:
  - `src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt:272-273`
  - `src/main/kotlin/net/nakaymi/etherealchronicle/paper/AdventureController.kt:186`
- **Problem**:
  `defenseBuffs`, `procReady`, and `consumableReady` are never pruned on `PlayerQuitEvent` or `tick()`. Over prolonged uptime, entries accumulate unboundedly for every unique player/item pair.
- **Recommended Fix**:
  Remove expired entries during the 1-second `tick()` cleanup (`removeIf { it.value <= now }`) and clear player-scoped entries on quit (for `defenseBuffs`).

---

### [MEDIUM-05] Enemies Target Creative/Spectator Players and Apply Status Effects Through Cancelled Safe-Zone Damage
- **Locations**:
  - `src/main/kotlin/net/nakaymi/etherealchronicle/paper/EnemyController.kt:65, 105-110, 116-121`
- **Problem**:
  `eligible` and `warning` player filters do not exclude `GameMode.CREATIVE` / `GameMode.SPECTATOR` (or safe-zone players for `warning`), and `attack()` / `warning` apply `POISON`, `SLOW`, and `KNOCKUP` unconditionally even when `player.damage(...)` was cancelled by `AdventureController.safe()`.
- **Recommended Fix**:
  Filter `p.gameMode in setOf(GameMode.SURVIVAL, GameMode.ADVENTURE)` and `!adventure.isSafe(p)` before targeting or applying attack/telegraph damage and status effects.

---

### [MEDIUM-06] `MiniMessage.deserialize` on Exception Strings, `/ec give` Dropping Bound Items, and Missing Lock Checks in Admin Commands
- **Locations**:
  - `src/main/kotlin/net/nakaymi/etherealchronicle/paper/AdventureController.kt:64, 165, 206`
  - `src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt:398-422`
- **Problem**:
  1. `AdventureController.message()` routes all messages—including `"操作できません: ${error.message}"`—through `MiniMessage.deserialize()`, stripping `<id>` placeholders from usage strings and opening tag-injection risk if any exception message ever contains player input.
  2. `/ec give` drops overflow items onto the ground (`p.world.dropItemNaturally(...)`). If an admin gives >1 unstackable item or gives a `bound`/`quest_item` when storage overflows, the leftover drops as a public world item anyone can pick up.
  3. `/ec class` (`RpgPlugin.kt:414-422`) leaves old class skill IDs in `profile.skillLevels` (unlike `game.changeClass`) and neither `/ec class` nor `/ec setlevel` checks `!adventure.locked(profile.uuid)` (unlike `/ec currency`).
- **Recommended Fix**:
  Use `Component.text(...)` for system/error strings; validate capacity via `inventory(p).canExchange(emptyMap(), mapOf(args[2] to amount))` in `/ec give` (never drop bound items on ground); call `game.changeClass()` and enforce `!adventure.locked(profile.uuid)` in `/ec class` and `/ec setlevel`.

---

### [LOW-01] Async Startup Listener Gap & Stale Open GUIs Across `/ec reload`
- **Locations**: `RpgPlugin.kt:47-74, 363-379`
- **Problem**: Events are only registered inside `sync {}` after async content loading finishes, and `/ec reload` does not close open `RpgMenu` inventories (leaving stale shop/dialogue entries clickable).
- **Recommended Fix**: Close open `RpgMenu` inventories during `/ec reload` and guard joins while `!ready`.

### [LOW-02] Duplicate `Slime.size` Assignment & Shop GUI Display Polish
- **Locations**: `EnemyController.kt:42, 46`, `AdventureController.kt:107-111`
- **Problem**: `if (entity is Slime) entity.size = d.size` is written twice; `showShop()` prints raw `ec:item_*` IDs for token requirements, prints undiscounted `e.price` even when `shop.discount_flag` is set, and does not cap `entries.take(54)`.
- **Recommended Fix**: Remove duplicate line 46; display `snapshot.items[it.item].name` and effective discounted price/tokens in `showShop()` with `.take(54)`.

### [LOW-03] Minor Code Hygiene
- **Locations**: `AdventureController.kt:38`, `RpgPlugin.kt:116`
- **Problem**: `AdventureController.reloadNpcs()` hardcodes `"ec_world"` instead of `DemoWorld.NAME`; `ClassMenu` stores class IDs under `itemKey` (`item_id`) rather than a dedicated key.
- **Recommended Fix**: Reference `DemoWorld.NAME` and use a dedicated `classKey` or `entryKey` in `ClassMenu`.
