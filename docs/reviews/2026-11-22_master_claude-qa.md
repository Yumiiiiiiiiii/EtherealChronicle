# Cross-model review (Claude-QA) — master 77f9aea, 2026-11-22

Scope: `src/main/kotlin` (read in full: AdventureController, AdventureServices, PlayerStore, PaperInventoryAccess, InvUiInventoryAdapter, ResourcePackController/Distribution, GamePortPackServer, TreasureController, DungeonController, DemoWorld (v1-v5), LangCatalog.diagnostic, command section of RpgPlugin; skimmed Gathering/Fishing/Enemy/Npc). No code was changed.
Focus: (a) transactions/economy/locks/async save, (b) real-Paper-only breakage / test gaps, (c) readability, (d) hard-coded values, (e) security/permissions.
"Verified" = confirmed by reading the cited code (and, where noted, by the QA real-client runs). "Needs check" = plausible from the code but not reproduced.

Totals: **High 3**, Med 11, Low 12.

---
## High

### H1. Quest auto-complete grants items/currency outside the transaction boundary (duplicate reward on crash)
- `paper/AdventureController.kt:55-57` (`tick`, every second) and `:79-80` (`interact`, NPC talk) call `quests.event(...)` with the default `allowAutoComplete = true` (`core/AdventureServices.kt:55,74-75`) and a raw `plugin.inventory(player)`.
- `QuestEngine.complete` (`AdventureServices.kt:80-94`) calls `inventory.exchange(remove, add)` (items go straight into the real inventory) and adds currency/XP/flags to the in-memory profile. Persistence is only `markDirty` (60 s autosave; `interact` never calls `save`) or `save` in `tick` only when the completed-count changed.
- Why: this bypasses the lock + "persist first, then deliver" contract used by `transaction()`. A crash/kill/`/stop` race between the item grant and the profile write leaves the player with the items but the quest still "not completed" → the quest can be completed again (duplicate items, currency, XP). It also runs while no lock is held (the tick path only skips if already locked, it does not take the lock), so it can interleave with an async save callback.
- Fix: route auto-completion through `AdventureController.transaction(...)` (or disable `allowAutoComplete` in `tick`/`interact` and only mark `ready`, completing via the existing NPC/transaction path). At minimum persist before granting items.
- Status: **Verified** in code; whether shipped quests set `auto_complete: true` needs a content grep (Needs check).

### H2. Tests cannot see the real-Paper-only code paths (repeated production blockers)
- Evidence from QA: InvUI `Illegal width: 6` (Task15), InvUI shop click ERROR stack (Task16), advancement background/datapack schema (`carvers`), first-join mandatory pack prompt not shown (Task19), missing-texture warnings. Every one passed MockBukkit/unit tests.
- Cause: `plugin.paperUiEnabled` (InvUI, Paper Dialog, `showDialog`, `sendResourcePacks`, datapack registration, Netty `ChannelInitializeListener`) is false under MockBukkit, so `RpgPlugin.openRpgMenu`, `AdventureController.showDialogueDialog` (`:100-120`), `InvUiInventoryAdapter`, `ClassSelectionUi`, `GamePortPackServer` and `ResourcePackController.send` are effectively untested.
- Fix: (1) add a headless Paper-boot smoke in CI (`scripts/smoke-test.ps1` already exists) that boots the jar, runs `ec validate`, and fails on any ERROR/stack/WARN outside an allow-list; (2) add a scripted bot client (e.g. a protocol bot) for join → class menu → `/ec shop|codex|enhance|equipment` → resource-pack prompt; (3) unit-test the pure adapters directly (e.g. `InventoryGrid`, `UiThemeProvider`) plus a test that forces the InvUI branch behind an interface.
- Status: Verified (QA history).

### H3. Mandatory pack on first join does not trigger on real Paper (known, reported in Task19)
- `paper/RpgPlugin.kt:220-222` → `ResourcePackController.sendRequiredBeforeProfileLoad` (`ResourcePackController.kt:123-126`) → `send` (`:96-120`). Real runs (two fresh non-op UUIDs, `required:true`, embedded_http up): no prompt, no download, class menu opens at once.
- Likely cause (Needs check): the class-selection window / Paper Dialog opened right after the request replaces the client's pack-confirmation screen (a pack request received in the PLAY phase is shown as a screen). A rejoin of an existing profile worked in Task18 because no window was opened in the same tick. Alternatives: request sent before the client is ready, or `send()` early-returns (`:101`). Add an INFO log at `:119` ("sent pack request required=…") to separate the cases.
- Fix: send the request in the configuration phase (`AsyncPlayerConnectionConfigureEvent` + `connection.getAudience().sendResourcePacks` / Paper's config-phase API) so the client resolves it before entering the world; or delay all menus (class menu) until `SUCCESSFULLY_LOADED`/bypass.
- Also note: "required" is enforced only by the client's status event (`:152-171`); a modified client that never answers is never kicked. Add a timeout kick (e.g. 30 s without `SUCCESSFULLY_LOADED` for non-bypass players).

---
## Medium

### M1. Mailbox restore uses `putAll` (overwrites, can lose rewards)
- `AdventureController.kt:373` (`beforeQuit`), `:381` (`prepareShutdown`), `:411` and `:415` (`deliverLocked` error paths) use `pendingRewards.putAll(output)`.
- Between `p.pendingRewards.clear()` (`:405`) and the persist callback, other code adds mailbox entries without the lock: `DungeonController.kt:97`, `core/WorldDefinitions.kt:175` (treasure, inside a transaction so locked, ok), `RpgPlugin.kt:372,380,436`. `putAll` replaces any count that was merged in meanwhile.
- Fix: `output.forEach { (k,v) -> pendingRewards.merge(k,v,Int::plus) }` everywhere (helper `PlayerProfile.returnToMailbox`). Verified.

### M2. Dungeon first-clear rewards bypass the lock/transaction
- `DungeonController.kt:93-106`: mutates `pendingRewards`/`flags` and calls `plugin.save(profile)` directly while the profile may be locked by another transaction/delivery; combined with M1 can be lost, and the persisted snapshot may include half-applied state of a concurrent transaction.
- Fix: wrap in `adventure.whenAvailable(member){ transaction/… }` so the reward flag + mailbox are written inside a lock. Verified.

### M3. `transaction()` can unlock twice
- `AdventureController.kt:449-453`: when `deliverLocked` takes an early return (`:397` empty mailbox, `:399-401` inventory full) it already calls `unlock`; then `return@sync` runs the `finally` at `:453`, where `player.uniqueId !in delivering` is true, so `unlock` runs again. Every sale/consume/enhance (no add) hits the `:397` path.
- Today it is harmless only because `success()` callbacks happen not to take a lock synchronously; a future success callback that starts a transaction would lose its lock to the second unlock → concurrent transactions → dupes.
- Fix: make `unlock` idempotent-by-token (lock owner token) or let `deliverLocked` own the unlock and have the `finally` only unlock when `deliverLocked` was not called. Verified (latent).

### M4. Selling picks the first stack of that id, not the clicked stack
- `AdventureController.kt:243-259` passes only the item id to `planSell`; `PaperInventoryAccess.preview` (`PaperInventoryAccess.kt:13-21`) removes the first matching slot. With two copies of an equipment id (one enhanced +5), the plain copy may be clicked but the enhanced one is sold. Same for `StagedInventory` consumption.
- Fix: carry the slot/stack identity (or prefer the lowest enhancement/unmodified stack) when removing; at least refuse to sell enhanced/modified stacks. Verified in code.

### M5. `saveData()` on the main thread for every consuming exchange
- `PaperInventoryAccess.kt:44` (and `AdventureController.kt:444,457`, `RpgPlugin.kt:435`) call `player.saveData()` synchronously (disk IO on the main thread), contradicting the "never block main thread with IO" rule; each shop sale/consume does it.
- Fix: keep the persisted mailbox/profile as the source of truth (already done) and drop the per-trade `saveData`, or batch it; if the intent is "no dupe on crash", the profile-first ordering already provides it for output but not for consumed input — document the trade-off. Verified.

### M6. Per-second per-player deep snapshot and equality
- `AdventureController.kt:48-58`: every online player every 20 ticks does `p.snapshot()` (deep copy of every map, quests, records), constructs a `PaperInventoryAccess` and compares with `before != p` (data class equals over everything). O(players × profile size) allocation each second.
- Fix: have `QuestEngine.event` return whether it changed state; skip the snapshot; only evaluate EXPLORE when the player moved block/area (PlayerMoveEvent with area cache) and COLLECT on inventory events. Verified.

### M7. Starter weapon can be lost permanently
- `RpgPlugin.kt:311-322`: `starterGranted=true` is saved first, then items are placed in a later tick; if the hotbar changed meanwhile (`:317`) the player only gets a message and `starterGranted` stays true → no weapon, no retry (there is no `pendingRewards` fallback).
- Fix: on that branch put the starter items into `pendingRewards` (like `reconcileStarterWeapon`, `:371-375`). Verified.

### M8. Resource-pack URL silently points at 127.0.0.1
- `ResourcePackController.kt:199`: `publicHost = configuredHost.ifBlank { server.ip.ifBlank → "127.0.0.1" }`, default `bind_host 0.0.0.0`. A default install advertises `http://127.0.0.1:25580/...` to remote clients → download fails → with `required: true` every remote player is kicked (`:167-168` treats FAILED_DOWNLOAD as "declined").
- Fix: when public host resolves to loopback and bind is non-loopback, log a WARN at startup and treat delivery as unavailable (auto-relax required); distinguish FAILED_DOWNLOAD from DECLINED in the kick text. Verified in code.

### M9. Embedded HTTP: no write timeout / slow-read hold; 404 cached for 1 h
- `ResourcePackDistribution.kt:170-180`: `soTimeout` only guards reads; a client that stops reading holds one of 32 workers (4 per IP) until TCP gives up (currently masked because the 47 KB pack fits in the socket send buffer, but breaks as the pack grows). `:175` sends `Cache-Control: public, max-age=3600, immutable` also on 404 (proxy caches a bad response).
- Fix: use `SocketChannel` with a write deadline or `socket.setSoLinger/close` watchdog; send `Cache-Control: no-store` on non-200. `acceptConnections` (`:92-96`) `continue`s on `IOException` without back-off (CPU spin on EMFILE). Verified.

### M10. Game-port sniffer: per-IP limit is shared NAT, O(n²) buffer copies, Paper-internal API
- `GamePortPackServer.kt:149-150` limits by remote IP (default 2): players behind one NAT/LAN share it. `:139` / `:272-275` `toByteArray()` per received byte (≤4096 bytes → ~8 MB of copying per request) is a CPU amplification vector; use a growing `ByteBuf` and compare tail in place.
- `:34-35,75` rely on `io.papermc.paper.network.ChannelInitializeListenerHolder` (internal); a Paper update can break it silently (handled by `failed`, good) — pin with a startup self-test log + a CI smoke on each Paper bump.
- Bandwidth limiter is per IP, but the class/config text says "shared budget" (`PackConnectionLimiter` doc) — document or add a global cap. Verified.

### M11. Dialog edge cases need verification on real Paper
- `AdventureController.kt:118`: `DialogType.multiAction(buttons, close, 2)` with an empty `buttons` list (a node with no choices and no global choices) may throw `IllegalArgumentException` from the vanilla codec inside the NPC interact handler. Legacy path caps at 45 choices (`:92`) but the Dialog path does not. Needs check on a real Paper run; guard by falling back to a single "close" button.

---
## Low

- **L1** `data/PlayerStore.kt:15-16` two stacked KDoc blocks (first is stale); `:137` `shutdown()` and `:138-141` `close()` overlap.
- **L2** `PlayerStore.kt:100-118` migration chain repeats the same literal map for every version (v2…v9 copies, ~20 lines of 300+ chars). Replace with an ordered list of `(from) -> step` lambdas; each step adds only its own fields. Today adding schema 14 means touching every branch. The v12→13 branch (`:91-96`) is the only clean one.
- **L3** `PlayerStore.write` (`:120-136`): the YAML dump is one 1.5 KB line of map building; directory fsync after the atomic move is missing (power-loss durability), `.bak` is a copy taken *before* the replace so a crash between `.bak` move and target move is safe but the `.bak` can be one save older than a corrupted target. Acceptable; document.
- **L4** `LangCatalog.kt:50-51` `placeholder.replace(template, detail)` treats `detail` as a replacement pattern (`$1`, `\` throw / mangle). Use `Regex.escapeReplacement(detail)` or a lambda.
- **L5** `TreasureController.kt:38,53` call `world.getChunkAt(loc)` (can load chunks) from inside `ChunkLoadEvent`/`reload()` and `isOwned` does a file-exists check per spawn; cache the ownership marker per world.
- **L6** `InvUiInventoryAdapter.kt:46` `InventoryGrid.forInventorySize` forces width 9; non-multiple-of-9 inventories (hopper/dispenser) would be mis-sized. Fine today (only 9×N menus); add `require(size % 9 == 0)`.
- **L7** `RpgPlugin.kt:883` `reloadConfig()` plus content install happen on the main thread (disk IO); `ui.theme.mode` reload works but `resource_pack.*` changes need a restart — print that in the reload message.
- **L8** `ResourcePackController.kt:152-171` an unconditional kick message "declined" for any terminal status (FAILED_DOWNLOAD, INVALID_URL, DISCARDED, FAILED_RELOAD). Map statuses to specific messages.
- **L9** `DemoWorld.kt:90` logs "Created ec_world (terrain v4)" and then migrates to v5; keep the log truthful. `ensure` repeats the four `migrateToVn` calls in two branches (`:64-67`, `:92-95`) and the `when(marker)` blocks hard-code `"v2","v3","v4","v5"`; use an ordered `List<Migration>` with the stored version index.
- **L10** `AdventureController.kt:285-290` `tradeBound` is a 400-char single boolean expression; split into named predicates (`isExternalInventory`, `isBoundSwap`, …) and unit-test each.
- **L11** `RpgPlugin.kt:890` mis-indented line inside the reload block; `:1115` lines in one class mixes commands, items, starter, enhancement, advancement, HUD, combat. Candidates to extract: `AdminCommands` (`executeCommand`, ~140 lines), `StarterGearService` (`starter`, `reconcileStarterWeapon`), `EnhancementService` (`settleEnhancement`, flights), `ItemFactory` (`createItem*`).
- **L12** `AdventureController.consume` `consumableReady` map is cleaned in `tick()` (`:47`), fine; but `persistConsumption=false` (`:327`) means a crash right after drinking can restore the potion (document as accepted).

---
## (c) Readability / Japanese comments
- Very long one-line functions: `AdventureServices.kt:186-187` (`tuning()` + 20-condition `require`), `PlayerStore.kt:124-127`, `AdventureController.kt:289`, `WorldContent.kt:166,218,221`. Split into named validators with messages; the `require(...)` chains give no hint which condition failed (all map to the same `ec.error.invalid_content`).
- Missing/inaccurate comments: `AdventureController.transaction` doc says "consume first, then deliver" but the code stages, consumes, writes mailbox, persists, then delivers (`:424-460`) — describe the exact ordering and the failure matrix (persist failure → rollback of inventory + profile). `TradeEngine.buy/sell` (`AdventureServices.kt:129,145`) lack KDoc (the planBuy/planSell wrappers have it). `PlayerStore.migrate` lacks the version table. `GamePortPackServer.PackRequestSniffer` explains states but not why the first byte `G`/`H` cannot collide with a Minecraft length prefix (it can for 71/72-byte handshakes; it is handled by `passBufferedMinecraft`, say so).
- Naming: `p`, `d`, `e`, `q` single-letter locals dominate controllers; `AdventureController` mixes `plugin.sync` with `Bukkit.getScheduler`-style inline lambdas inside `whenComplete`.
- Duplicated logic: dialogue choice display built twice (`:84-98` legacy menu and Dialog path), `chunked(28)` lore wrapping in `AdventureController.kt:90` and `RpgPlugin.kt:335`.

## (d) Remaining hard-coded values
- `"ec_world"` literal: `GatheringController.kt:193`, `NpcController.kt:68`, `SimulationCommandController.kt:41`, `core/WorldValidation.kt:42`, `core/WorldContent.kt:166,218,221` (while `DemoWorld.NAME` exists). Add a `core` constant `WorldIds.DEMO`.
- Dungeon geometry: `DungeonController.kt:73,133` (`1024 + slot*192`, `z=1024`, `±30`) and `:123` (`>=900`) — move to config/tuning.
- Default dungeon id `"ec:dungeon_void_sanctum"` at `DungeonController.kt:23` and `RpgPlugin.kt:1065`; first quest id `"ec:quest_m01_awakening"` in `core/GameService.kt:14`.
- UI slot numbers in `UiThemeProvider.kt:86-89` (47..49, 53, 13) duplicate menu layout constants from `CodexController`/enhance screen; share one layout object.
- Magic numbers: item lore wrap 28, dialogue choice cap 45 (`AdventureController.kt:92`), 54-slot cap (`:141`), `128` transaction history (`PlayerStore.kt:55`, `AdventureServices.kt:167`), `1_000_000` bounds in `PlayerStore.read`, `500/2000` record caps (`:85`). Put them in balance/tuning or named constants.

## (e) Security / permissions
- Commands: `/ec` is registered without a Bukkit permission; admin subcommands are gated by `hasPermission("etherealchronicle.admin")` (`RpgPlugin.kt:867`) and the player-command whitelist (`:860`). The gate is correct, but it is an allow-list by first argument: any new player subcommand added to `playerCommand` without updating the set silently falls into the admin branch (safe default). `quest start/complete` check admin inline (`:1076-1077`) — good. Consider registering the command with `.permission` per subcommand (Brigadier `requires`) so tab-complete does not leak admin subcommands (check `onTabComplete`; Needs check).
- `/ec give` (`:950-960`) validates amount 1..64 but not the item id format before reaching content lookup; fine (content-checked), but it bypasses `transaction`/lock (admin-only) and can exceed currency/dupe caps — acceptable, document as admin-only.
- HTTP/Netty: path handling is exact-match (good, QA fuzzed traversal/encoding/oversize/flood: all rejected). Residual: M8 (loopback URL), M9 (slow read, 404 caching), M10 (shared-NAT limit, byte-wise copies, internal Paper API), H3 (required enforced only client-side). `bind_host` default `0.0.0.0` exposes only the static pack path.
- `external_managed` mode trusts `PlayerResourcePackStatusEvent` from any pack (when `expected_pack_id` empty): a player-installed/other pack's SUCCESS flips `loadedPlayers` and enables pack-themed UI (cosmetic only, no privilege). OK; recommend requiring `expected_pack_id` in docs.
- Profile loading: `read()` uses `SafeConstructor` + duplicate-key rejection and range checks (good). The `Regex("[A-Z_]{1,48}")` etc. are compiled per load; trivial.

## (b) Test-gap checklist (what MockBukkit cannot catch)
InvUI window creation and click routing (`InvUiInventoryAdapter`, `invUiShops` close/open ordering `AdventureController.kt:133-134,186-191`), Paper Dialog creation/callbacks, `sendResourcePacks` timing vs windows, Netty listener reflection, datapack registration (biome/wolf-variant JSON schema), item-model keys with/without pack (`setItemModel`), `PlayerResourcePackStatusEvent` ids, `Player#saveData`, `world.worldFolder` layout (Paper 26 dimension folders), chunk-load-time entity spawns (`TreasureController`, `NpcController`, `GatheringController`), vanilla mob spawn suppression, and every `Material.valueOf(...)` fed from YAML (`RpgPlugin.kt:332`) — validate Materials/Particles at content validation time, not at first use.

---
# Addendum (user-requested viewpoints): Perf / Idiom / Style

Category tags for the items above: H1 Bug, H2 Test, H3 Bug/Security, M1 Bug, M2 Bug, M3 Bug, M4 Bug, M5 Perf, M6 Perf, M7 Bug, M8 Bug, M9 Security, M10 Security/Perf, M11 Test, L1 Style, L2 Style, L3 Bug(Low), L4 Bug(Low), L5 Perf, L6 Idiom, L7 Perf, L8 Style, L9 Style, L10 Style, L11 Style, L12 Bug(Low).

## Perf
- **P1 (Med) `PaperInventoryAccess` clones the whole 36-slot storage on every `count`/`canExchange`/`exchange`.** A single transaction calls `canExchange` + `exchange(remove)` + `exchange(add)` and each runs `preview` (clone + scan), plus `exchange` runs `preview` twice (`PaperInventoryAccess.kt:37-49`). Compute the preview once and apply it.
  Before: `val after=preview(remove,add) ?: return false; ... storageContents=preview(remove,emptyMap()) ?: return false`
  After: `val plan = plan(remove, add) ?: return false` (returns `Plan(afterRemove, afterAll)`), then `storageContents = plan.afterRemove` / `plan.afterAll`.
- **P2 (Med) Click events call `bound()` up to ~6 times per click, each through `itemMeta` (which clones the meta).** `AdventureController.kt:285-290` (`tradeBound`), `:150-160`. Read the id/flag once per stack per event.
  After: `val cursorBound = bound(e.cursor); val currentBound = bound(e.currentItem)` then reuse; or cache `bound` per `ItemStack` identity within the event.
- **P3 (Low) Regexes/sets rebuilt per call.** `LangCatalog.kt:50` `Regex("<(\\w+)>")` per diagnostic; `PlayerStore.kt:67,70` and `ResourcePackEndpoint.url` (`ResourcePackDistribution.kt:46`) compile per load/URL; `WorldContent.kt:166,190` per content entry; `AdventureController.kt:288,318` `setOf(...)` per click/consume; `ResourcePackDistribution.kt:121` and `GamePortPackServer.kt:220` `setOf("GET","HEAD")` per request.
  After: `private companion object { val PLACEHOLDER = Regex("<(\\w+)>"); val HTTP_METHODS = setOf("GET","HEAD") }` (or top-level `private val`).
- **P4 (Med) `EntityDamageEvent` safe-zone check scans all areas and allocates a `Position` per event** (`AdventureController.kt:306-310`; same pattern in `tick` `:55`). High-frequency event on a populated server.
  After: cache `safeAreasByWorld: Map<String, List<Area>>` (rebuilt on reload) and test only that world's list; or keep a per-player "inSafeArea" flag updated on block-change move events.
- **P5 (Low) MiniMessage is re-parsed on every dialogue show, twice** (chat copy + Dialog/lore): `AdventureController.kt:90,93,95,102,115`. Parse once per (node, language) and cache the `Component` in a `ConcurrentHashMap`, cleared on reload.
- **P6 (Med) `effectiveStats` rebuilds equipped definitions (copy + `enhancedStats`) on every call** (`RpgPlugin.kt:405-415`), which is used per hit/HUD. Cache per player, invalidate on equipment/held-slot/inventory-change events and on enhancement.
- **P7 (Low) YAML `Yaml(...)` instances are created per save/load** (`PlayerStore.kt:41,128`). `Yaml` construction is non-trivial; the single IO thread can own one reusable instance (`private val yaml = Yaml(...)` accessed only from the executor).
- **P8 (Low) Byte-at-a-time `ByteArrayOutputStream.toByteArray()` in the game-port sniffer** (`GamePortPackServer.kt:139,272-275`) — O(n²); keep the bytes in a `ByteBuf` and look at the last 4 readable bytes in place.

## Idiom
- **I1 (Med) Errors are strings parsed back with regex.** `require(x){"ec.error.foo"}` + `TRADE_ERROR_KEY` regex (`AdventureServices.kt:170-180`), `runExpectedTrade` using `"ec." + "error."` (`AdventureController.kt:265`, a string split only to dodge the CJK/literal test), `LangCatalog.ERROR_KEY` replace. Use a typed exception.
  Before: `require(p.currency >= cost) { "ec.error.currency_low" }` … `error.message?.takeIf { it.startsWith("ec.error.") }`
  After: `class GameException(val key: String, val args: Map<String,Any?> = emptyMap()) : IllegalArgumentException(key)`; `fun fail(key: String, vararg a: Pair<String,Any?>): Nothing = throw GameException(key, a.toMap())`; catch `GameException` and render `plugin.message(player, e.key, *e.args)`. Removes the regex round-trip and makes placeholders structured.
- **I2 (Low) Stringly-typed kinds**: dialogue action `"START_QUEST"…` (`AdventureController.kt:219-233`), effect kinds (`:318`), menu kinds `"shop"/"dialogue"/"equipment"` (`:164-179`), pack modes `"embedded_http"` etc. Use enums/sealed interfaces so `when` is exhaustive and typos fail at validation.
  Before: `when(action.kind) { "START_QUEST" -> … }`  After: `when(action) { is StartQuest -> …; is OpenShop -> … }` (parsed once in the content reader).
- **I3 (Low) Redundant `isOnline` after `Bukkit.getPlayer`** (`AdventureController.kt:350,414`, `DungeonController.kt:92,102`): `Bukkit.getPlayer(uuid)` only returns online players. `Bukkit.getPlayer(id)?.isOnline != true` → `Bukkit.getPlayer(id) == null`.
- **I4 (Low) Repeated casts and `!!`**: `(e.whoClicked as Player)` three times in one expression (`AdventureController.kt:289`), `getAttribute(MAX_HEALTH)!!.value` (`:329`, `RpgPlugin.kt:320`). 
  After: `val player = e.whoClicked as? Player ?: return` once; `player.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0`.
- **I5 (Low) Manual lock/unlock with try/finally scattered over 6 places** (`AdventureController.kt:393,426,449-453,454-459`). Wrap in `inline fun <T> withTxLock(id: UUID, block: () -> T): T` for the synchronous part and an explicit `LockToken` for the async part (also fixes M3).
- **I6 (Low) `PlayerStore.read` repeats `(n as Number).toInt().also { require(it in a..b) }` ~12 times** (`:49-84`). Add `private fun Any?.boundedInt(range: IntRange, field: String): Int` that throws with the field name (better diagnostics than a bare `require`).
- **I7 (Polish) Mutable state kept in several parallel maps keyed by UUID** (`dialogueSessions`, `invUiShops`, `consumableReady` with `Pair<UUID,String>`, `choosing`, `locks`, `deliveries`, `delivering`, `scheduled`, `waiting`). Group per-player transient state in one `class PlayerSession` stored in one map; removal on quit becomes one call (today each map has its own cleanup site, e.g. `AdventureController.kt:186-191,301,370-378`).

## Style
- **S1 (Polish) `tradeBound` — name the conditions.**
  Before (`AdventureController.kt:287-289`): one boolean expression of ~400 chars.
  After:
  ```kotlin
  val bundleSwap = e.currentItem.isBundle() && bound(e.cursor) || e.cursor.isBundle() && bound(e.currentItem)
  val touchesExternal = e.view.topInventory.type !in PLAYER_ONLY_VIEWS
  val movesBound = bound(e.currentItem) || bound(e.cursor) || offhandSwapBound(e) || hotbarSwapBound(e)
  if (bundleSwap || touchesExternal && (movesBound || e.click.isDrop && bound(e.currentItem))) e.isCancelled = true
  ```
- **S2 (Low) `executeCommand` admin branch: seven copies of "find player → profile → not locked".** (`RpgPlugin.kt:900-985`)
  After: `private fun target(name: String?): Target { val p = server.getPlayerExact(name ?: fail("ec.error.usage")) ?: fail("ec.error.player_missing"); val profile = profiles[p.uniqueId] ?: fail("ec.error.profile_loading"); require(!adventure.locked(p.uniqueId)) {…}; return Target(p, profile) }` and turn the `when` into `val subcommands: Map<String, (CommandSender, List<String>) -> Unit>` so each command is a small named function (also helps tab-complete and permission metadata).
- **S3 (Low) Pack settings: 20 `settings.getX("path", default)` lines duplicating defaults from `config.yml`** (`ResourcePackController.kt:20-37`). Load once into `data class ResourcePackSettings` with `init { require(...) }` validation and one place for defaults; the controller then takes the typed object (testable without a plugin).
- **S4 (Low) Magic slot ladders in `UiThemeProvider.kt:86-89`** — replace the `when` with a declarative table.
  Before: `menuKind.startsWith("codex") && slot in 47..49 -> "ec:ui_tab_codex"`
  After: `private val rules = listOf(Rule("codex", 47..49, null, "ec:ui_tab_codex"), Rule("codex", 53..53, Material.ARROW, "ec:ui_btn_next"), …)` + `rules.firstOrNull { it.matches(menuKind, slot, material) }?.key`.
- **S5 (Low) Migration chains as data** (`PlayerStore.kt:99-119`, `DemoWorld.kt:120-190`).
  Before: `if(version==9) return …; if(version==8) return …` (each repeating the later steps).
  After: `private val steps: List<(Map<String,Any?>) -> Map<String,Any?>> = listOf(::to2, ::to3, …, ::to13)`; `steps.drop(version).fold(raw) { acc, step -> step(acc) }`; one function per schema step, each adding only its fields (same for terrain `v1→v5`).
- **S6 (Polish) Formatting is inconsistent between files** (`if(x)`/`a=b` compact style in `AdventureController`, `RpgPlugin`, `PlayerStore` vs ktlint style in `ResourcePackController`, `GamePortPackServer`). Add spotless/ktlint to the build and do one mechanical format commit so later diffs and reviews are readable (run the full test suite before/after as AGENTS.md requires).
- **S7 (Polish) Early returns / expression bodies**: `TreasureController.interact` can drop the nested try with `runCatching{}.onFailure{}`; `AdventureController.showDialogue` can return early for `paperUiEnabled`; many `fun x() { return … }` blocks could be expression bodies. `Treasure.interact` mapping of exception to message key can use the typed exception from I1.

## Totals (whole document)
High 3 (Bug 2, Test 1) · Med 11+4 (M1-M11 plus Perf P1, P2, P4, P6, I1) · Low/Polish remainder · Perf 8 (P1-P8) + 4 earlier tagged Perf (M5, M6, L5, L7, M10) · Idiom 7 (I1-I7) + L6 · Style 7 (S1-S7) + L1, L2, L8-L11.
