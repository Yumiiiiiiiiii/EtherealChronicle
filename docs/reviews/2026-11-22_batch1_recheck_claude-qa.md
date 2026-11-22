# Batch 1 re-check (Claude-QA) — FIX-01…26, master 0423ce9 (Wave 1 `ec5ceb8`, Wave 2 `58de839`, Wave 3 `9de04bb`), 2026-11-22

Method: read the three commits' `src/main` diffs in full (plus surrounding code where a fix touches shared state). No code changed, no real-client run (deferred to Batch 2 as requested). Verdicts: **OK** / **要追加修正** / **実機要確認**.

## Summary
| Verdict | Count | FIX ids |
|---|---:|---|
| OK | 18 | 01, 02, 04, 05, 06, 09, 10, 12, 13, 14, 16, 18, 19, 21, 22, 23, 24, 25 |
| 要追加修正 | 5 | **08 (High regression)**, **11 (High, reward-loss race)**, 03 (Med), 07 (Med), 17 (Low) |
| 実機要確認 | 3 | 15, 20, 26 (+ real-env confirmations noted under 01/02/24) |

**New High findings: 2** (N1, N2 below; both are in Wave 2). Other new findings: Med 3 (N3, N4, N5), Low/Polish 8.
Recommendation: fix N1/N2 before Batch 2 builds on the Wave 2 transaction code (FIX-27/28/29 touch the same functions).

---
## Per-FIX verdicts

### Wave 1
- **FIX-01 OK** (`GamePortPackServer.kt`): idle timer starts when the first `G`/`H` byte is held (`sniffPrefixSize==0 → resetIdleTimeout`), cancelled on every exit (`passThrough*`, HTTP promotion, `channelInactive`, `handlerRemoved0`). The 4-byte terminator is now an int shift register (`HEADER_TERMINATOR`), `GET_PREFIX/HEAD_PREFIX` are constants (also closes my M10/P8 O(n²) copy). Real Paper re-run still advisable (listener hook), see N8.
- **FIX-02 OK**: `detectUnsupportedProxies` reads `spigot.yml settings.bungeecord` and `config/paper-global.yml proxies.velocity.enabled`; missing files fall back to `false` via `runCatching`. File IO at plugin startup only.
- **FIX-03 要追加修正** (Med): watchdog + `no-store` on non-200 + accept back-off are in, but the write deadline is a **fixed** `write_deadline_ms` (default 5 s) regardless of body size → see N3.
- **FIX-04 OK**: single global `globalNextSendNanos` budget; NAT/shared-bandwidth wording matches code. Note a few attacker IPs can still queue ahead of legit downloads (FIFO reservations), acceptable.
- **FIX-05 OK**: `decide(...)` is evaluated before the `loadedPlayers` early return, so `/ec ui pack off` now unloads item models.
- **FIX-06 OK**: `observedManagedStatuses.clear()` in `close()`, `PackVisualTransition` keeps look on ACCEPTED/DOWNLOADED, `buildInfo` cached with `by lazy`.
- **FIX-07 要追加修正** (Med): loopback warning + per-status kick keys + `ResourcePackSettings` are implemented, but (a) `ResourcePackSettings.init` throws from the controller constructor (see N4), and (b) the new `canAdvertise` check disables distribution for the standard local test setup (see N5).

### Wave 2
- **FIX-08 要追加修正 (High)**: auto-complete is now transactional, but it introduces a regression with a full inventory → N1.
- **FIX-09 OK**: `returnToMailbox` merges with `addExact`; used at all four sites (`beforeQuit`, `prepareShutdown`, both `deliverLocked` error paths) and by dungeon/starter paths.
- **FIX-10 OK**: dungeon first-clear + record now go through `adventure.profileTransaction` (lock → mutate → persist → success); `claimPending` from `success` is queued by the held lock and runs after release (checked: `claimPending` sees `p.uuid in locks` → `whenAvailable`). Offline members are skipped as before.
- **FIX-11 要追加修正 (High)**: tokens make `releaseLock` idempotent (double unlock from my M3 is fixed), but the same commit lets `beforeQuit` release the lock early, which exposes a pre-existing unconditional `finally` → N2.
- **FIX-12 OK** for selling: `sourceSlot` flows `InventoryClickEvent.slot → planSell → TradePlan → exchangeFromSlot`, preferred slot is consumed first. Residual: other removals (quest COLLECT consume, bait, enhance materials) still take the first matching stack; acceptable for stackable materials. Style note N7 (duplicate exchange code).
- **FIX-13 OK**: starter items fall back to `returnToMailbox` + `claimPending`; `starterGranted` stays true. (Items arrive without the `starterClassKey` PDC; harmless, `reconcileStarterWeapon` matches by id.)
- **FIX-14 OK**: failed bait consumption cancels the session (`CANCELLED`), `failure` callback added to `transaction`; positional callers unaffected (trailing lambda is still `success`).

### Wave 3
- **FIX-15 実機要確認** (Med): confirmation tracker + enum fallback + lore are right. Concern: the confirmation Dialog now has `canCloseWithEscape(false)` **and** callbacks with `lifetime(2 min)`; if the player idles 2 minutes, both buttons stop working but the dialog cannot be closed → soft-lock until reconnect (N6). Also `confirming` is never cleared on quit (cleared on the next `open`, fine).
- **FIX-16 OK**: `language` volatile + `LangCatalog.load` on reload; Gathering/Fishing rebuild indexes/engine. Minor: the catalog is loaded **inside** `sync{}` on the main thread (file IO), the fix list said load async then swap; Low.
- **FIX-17 要追加修正** (Low): `toString()` dumps fixed (`plain(...)`), contentName used in shop/codex/gather/party. Remaining raw identifiers: `GatheringController` `reward_failed` still passes `error.message.orEmpty()` (raw `ec.error.*` key shown to the player, should go through `plugin.diagnostic`), and codex weaknesses (`enemy.weaknesses.joinToString()` = raw enum names).
- **FIX-18 OK**: literal replacement via lambda, regex hoisted. Caution (Low): `DETAIL_PLACEHOLDER` skips any placeholder name that *starts with* a MiniMessage tag word (`<gold…>`, `<red…>`, `<bold…>`, `<reset…>`, `<white…>`, `<gray…>`, …). A future placeholder such as `<gold_amount>` or `<redeemed>` would silently not be substituted. Add a test that scans `lang/*.yml` placeholders against the exclusion list, or resolve with explicit placeholder names instead of a regex.
- **FIX-19 OK**: `openAreaFromSlot` + `CodexPagination.itemIndex` apply `page*45+slot`; footer slots yield `null`; `codexTabs` removed.
- **FIX-20 実機要確認** (Low): entries are compacted and purchases are keyed by entry id (good: no slot mismatch). `onClose = { invUiShops.remove(uuid) }` runs on every InvUI close; `showDialog/showShop` refresh opens the new window and then sets `invUiShops[uuid]=id`. If InvUI fires the *old* window's close handler after `open()` returns (scheduled), the sell hint (right-click sell) would be silently disabled after the first refresh. Confirm in the real client: buy once, then right-click an inventory item.
- **FIX-21 OK** (Style note): empty choice list now falls back to the legacy chest menu (vanilla inventory on real Paper) instead of a Dialog with a close button; consistent look would be nicer (N9).
- **FIX-22 OK**: hitbox UUIDs are part of `displays` (`displays[k]=listOf(display,label,hitbox)`), so active hitboxes are not removed; orphans are.
- **FIX-23 OK**: precedence fixed, party filter + `distinctBy`.
- **FIX-24 OK / 実機**: `isPersistent=false`, spawn only in loaded chunks; `onChunkLoad` removes stale persistent NPCs from old saves; NPC reappears on the next 1 s `reconcile`. Verify in real client after teleport into an unloaded area (≤1 s delay). Pre-existing: `require(world.name=="ec_world")` at `NpcController.kt:67` would throw out of `tick()` for an NPC defined elsewhere (validation prevents it today).
- **FIX-25 OK**, **FIX-26 実機要確認** (needs `ui.theme.mode: full` screenshots; my Task 18 observation was that full/icons_only/off looked identical).

---
## New findings

### N1 `[Bug]` `[High]` Auto-complete with a full inventory now breaks NPC talk and spams/IOs every second (FIX-08 regression)
- Where: `AdventureController.kt` `tick()` (`readyAutoComplete(...).forEach { completeQuest }` inside `transaction`) and `interact()` (same inside `transaction(...){ showDialogue }`).
- Why: 69 shipped quests have `auto_complete`. `QuestEngine.complete` does `require(inventory.exchange(remove, add)) { "ec.error.inventory_exchange" }`; with no space the `StagedInventory`/`PaperInventoryAccess` returns false → `IllegalArgumentException`. Before Wave 2 this was swallowed in `QuestEngine.event` ("ready quests remain claimable when storage is full"). Now:
  1. `interact`: the exception escapes the `PlayerInteractEntityEvent` handler → Paper logs an ERROR stack trace, the TALK objective is rolled back and **the dialogue never opens** for a player whose inventory is full and who has any ready auto-complete quest with item rewards.
  2. `tick`: the exception is caught and logged with `logger.warning` **every second per player**; and the rolled-back `transaction` runs `runCatching { player.saveData() }` (disk IO on the main thread) every second while the condition holds.
- Fix: only auto-complete quests that fit, and never let one failure abort the whole transaction.
  Before:
  ```kotlin
  plugin.game.quests.readyAutoComplete(p,staged).forEach { quest -> plugin.game.completeQuest(p,quest,staged) }
  ```
  After:
  ```kotlin
  plugin.game.quests.readyAutoComplete(p,staged)
      .filter { plugin.game.quests.canComplete(p,it,staged) }   // pure: staged.canExchange(remove,add)
      .forEach { plugin.game.completeQuest(p,it,staged) }
  ```
  and in `interact` open the dialogue even if the transaction fails: `try { transaction(...) } catch (e: Exception) { logger.fine(...) } finally { showDialogue(...) }` (or pass `failure = { showDialogue(...) }`). Add a MockBukkit test: full inventory + ready auto quest with item reward → no exception, dialogue opens, no per-tick warning.
- Status: verified by reading (`AdventureServices.kt` `complete`, `StagedInventory.exchange`, `AdventureController.transaction`).

### N2 `[Bug]` `[High]` `deliverLocked` `finally` removes the *next* claim after a quit/rejoin (reward loss, narrow race)
- Where: `AdventureController.kt` `deliverLocked` (`finally { deliveries.remove(p.uuid); delivering.remove(p.uuid); releaseLock(token) }`) and `beforeQuit` (now `releaseLock(claim.token)` immediately).
- Sequence: claim A persists (mailbox cleared) → player quits: `beforeQuit` restores A to the mailbox, persists, and (new) releases the lock → player rejoins quickly and `claimPending` creates claim B (clears mailbox, persists) → A's persist callback runs: `deliveries[p.uuid] !== claimA` so it returns early, but the **`finally` still executes `deliveries.remove(p.uuid)`**, deleting B → B's callback sees `deliveries[p.uuid] !== claimB` and returns without delivering, while the mailbox was already cleared and saved → items lost. Before Wave 2 the lock stayed held until A's callback, so B was queued behind it; releasing the token in `beforeQuit` removed that protection.
- Fix: only clean up what this callback owns.
  Before: `} finally { deliveries.remove(p.uuid); delivering.remove(p.uuid); releaseLock(token) }`
  After: `} finally { if (deliveries[p.uuid] === claim) { deliveries.remove(p.uuid); delivering.remove(p.uuid) }; releaseLock(token) }` (and keep `beforeQuit` releasing only after marking the claim cancelled). Add a test: claim → quit → rejoin → claim, complete A's persist after B started.
- Status: verified by reading; timing window is small (quit during a claim persist, immediate rejoin), consequence is silent item loss.

### N3 `[Bug]` `[Med]` Write deadline does not scale with the pack size (FIX-03)
- `withSocketWriteDeadline(socket, writeDeadlineMillis)` (default 5 000 ms) closes the socket after a fixed time. Today's pack is ~47 KB so any client passes; a realistic 5–20 MB pack needs ≥1–4 MB/s to finish in 5 s, so normal home connections would be cut off mid-download and (with `required: true`) kicked.
- Fix: `deadline = max(write_deadline_ms, size / minRate)` with a `min_rate_kb_per_sec` setting (e.g. 32 KB/s), or reset the watchdog on write progress (watchdog per chunk, i.e. "no progress for N s").
  After: `val limit = maxOf(base.toLong(), bytes.size * 1000L / (MIN_RATE_KBPS * 1024))`.

### N4 `[Bug]` `[Med]` A typo in `resource_pack.*` now disables the whole plugin (FIX-07)
- `ResourcePackSettings.init { require(...) }` runs inside `ResourcePackController`'s property initializers, outside any `runCatching`. A wrong `mode`, `port<1024`, `max_per_ip > max_concurrent`, etc. throws from the constructor during `onEnable` ("Failed to load content" path → plugin disabled). The old behaviour was: log a warning and run without distribution.
- Fix: `val settings = runCatching { ResourcePackSettings.from(plugin.config) }.getOrElse { log WARN; ResourcePackSettings.disabled() }` (a safe default with `enabled=false`), and make the controller treat "settings invalid" as "delivery unavailable" (which already relaxes `required`).

### N5 `[Bug]` `[Med]` (needs real run) Loopback check disables the standard local/QA setup
- `canAdvertise(bindHost, publicHost)` is `isLoopback(bind) || !isLoopback(public)`. Defaults are `bind_host: 0.0.0.0`, `public_host: ""` → `publicHost = server.ip` or `127.0.0.1`. My dev setup (and `docs/CLIENT_OFFLINE.md`) uses `server-ip=127.0.0.1` + default bind, which now fails the check: embedded_http/game_port are logged as "disabled by invalid configuration" and `required` is silently relaxed. QA procedures must set `bind_host: 127.0.0.1` (or a real `public_host`) from now on; update `CLIENT_OFFLINE.md`/`MANUAL_QA.md` and mention it in the WARN text. Also `0.0.0.0` / `::ffff:127.x` as `public_host` are not recognised as unusable.

### N6 `[Bug]` `[Med]` (needs real client) Class-confirmation Dialog can soft-lock (FIX-15)
- `ClassSelectionUi.confirm`: `canCloseWithEscape(false)` + both buttons `uses(1)`, `lifetime(Duration.ofMinutes(2))`. After 2 minutes the callbacks expire but the dialog remains and cannot be dismissed; `confirming` stays true so `onClose` will not reopen the menu either.
- Fix: keep Esc enabled (route Esc to `onCancel`), or drop the lifetime / re-show the dialog on expiry; clear `confirming` in a quit handler.

### N7 `[Style]` `[Low]` Duplicate exchange implementation and thin wrappers
- `PaperInventoryAccess.exchange` and `exchangeFromSlot` are copy-pasted (including `saveData()`), `canExchange`/`canExchangeFromSlot` and `StagedInventory`'s pairs likewise; `LockToken` is a `data class` although identity (`!==`) is the semantics.
  Before: two near-identical `exchange*` bodies.
  After: `override fun exchange(remove, add) = exchangeFromSlot(null, remove, add)` with the single implementation in `exchangeFromSlot` (or one `exchange(remove, add, preferredSlot: Int? = null)` in the interface); `private class LockToken` (no fields, identity only). Also compute the preview once (FIX-28 P1).

### N8 `[Test]` `[Med]` New tests are again mostly pure one-line helpers
- `Wave3UiRules.kt` helpers (`paperDialogueHasChoices`, `npcChunkMaySpawn`, `dungeonPaletteNeedsBuild`, …) are one-liners whose tests restate the expression; they would not have caught N1/N2/N6. Add behaviour tests: (1) full inventory + ready auto quest (N1), (2) quit/rejoin during claim (N2), (3) settings typo does not disable the plugin (N4), (4) lang placeholder scan vs `DETAIL_PLACEHOLDER` (FIX-18), (5) real-Paper boot smoke with `game_port` enabled to confirm the proxy-detection file reads and the sniffer still passes Minecraft traffic (FIX-01/02).

### N9 `[Style]` `[Low]` Dialogue fallback and aliases
- `AdventureController.showDialogue` falls back to a vanilla chest for no-choice nodes; use a Dialog with only the close button for a consistent UI.
- `ResourcePackController` now copies ~25 settings into private vals (`private val port = settings.port` …); use `settings.port` directly (or destructure once) and delete the aliases.

### N10 `[Perf]` `[Low→Med]` The per-second quest `transaction` is heavier than before
- Each online player each second now runs `transaction(...)`: `p.snapshot()` (deep copy) **plus** a 36-slot `storageContents` clone (`beforeStorage`) before `skipIfUnchanged` can exit, plus `plugin.inventory(...)`. The earlier code did only the snapshot. FIX-27 (event-driven quests) should remove this; until then add a cheap pre-check (is the player inside any EXPLORE area / any COLLECT objective?) before entering `transaction`, and take the storage clone lazily (only after `mutate` shows a change).
  After: `if (!plugin.game.quests.hasPendingAutoWork(p, pos)) return@forEach` then `transaction(...)`.

### N11 `[Idiom]` `[Polish]` Small items
- `GamePortPackServer` `matches`/`matchesPrefix` use `(0 until n).all { … }` allocations per byte; `java.util.Arrays.equals(a,0,n,b,0,n)` is allocation-free.
- `LangCatalog.DETAIL_PLACEHOLDER` literal is a 300-char alternation; build it from a `listOf(...)` joined with `|` so tags are reviewable.
- `socketWriteWatchdog` is a file-level singleton `ScheduledExecutor` that is never shut down (daemon, so harmless); expose `shutdown()` from `EmbeddedPackServer.close()` if more than one server can be created on reload.

## Real-client items for the Batch 2 session (carry-over)
Full-inventory NPC talk (N1), quit/rejoin claim (N2), game_port + proxy-file detection with `server-ip` and `public_host` variants (N5), large-pack download timing (N3), class confirmation idle 2 min (N6), shop right-click sell after refresh (FIX-20), `ui.theme.mode: full` title offset (FIX-26), NPC respawn after teleport into unloaded chunk (FIX-24).
