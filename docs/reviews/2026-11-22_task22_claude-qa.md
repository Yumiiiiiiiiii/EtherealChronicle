# Task 22 cross-model review (Claude-QA) — codex/foundation 237986c, 7682700, 595f96c, ee13c75, 14e1be8 (master 9bd6d89), 2026-11-22

Method: read each commit's `src/main` diff (and surrounding code where state is shared). No code changed, no real-client run. Findings were first sent to Codex by message (docs/reviews is not edited by the author); this file is the audit copy.
Lenses: Correctness/Safety, Performance, Idiom, Style, Test (AGENTS.md cross-model review loop).

## Result summary
- **High 1 · Med 8 · Low 12** (Perf 4, Idiom 4, Style 2, Test 2 within Low/Med).
- Needs a real-client run: H1, M1, M2, M3.
- Batch 1 re-check items (see `2026-11-22_batch1_recheck_claude-qa.md`): N1, N2, N3, N4 and Low items LockToken / exchange dedupe / lang regex are **verified fixed** by 7682700 (details below). Partial items that remain open in `AGENT_STATUS.md` are commented at the end.

## Verified fixed (OK)
| Batch 1 item | Fixed by | Check |
|---|---|---|
| N1 full-inventory auto-complete | 7682700 `QuestEngine.completeReadyAuto` + `interact` fallback | swallows only `ec.error.inventory_exchange`; the failing quest stays ready; the dialogue is shown on any transaction failure; `transaction` captures `beforeStorage` lazily so the no-change path no longer clones storage |
| N2 delivery cleanup race | 7682700 `isCurrentDelivery` guard in `deliverLocked` `finally` | only the owning claim removes `deliveries`/`delivering`; `releaseLock(token)` stays idempotent |
| N3 fixed write deadline | 7682700 `resourcePackWriteDeadlineMillis` | scales with size at 512 KB/s, capped 180 s (see L11) |
| N4 settings typo disables plugin | 7682700 `ResourcePackSettings.loadSafely` | invalid config → `safeDisabled()` + WARN; `required=false` |
| N5 loopback check | 7682700 `effectiveBindHost` for game_port, wildcard/`::ffff:127.*` handling | embedded_http with `server-ip=127.0.0.1` + default bind still rejected → QA/docs must set `bind_host`/`public_host` |
| N6 Dialog soft-lock | 7682700 (Esc re-enabled, `clear()` on quit) | **introduces H1 below** |
| L LockToken data class, exchange dedupe | 7682700 | plain class, `exchange = exchangeFromSlot(null,…)` |
| Lang placeholder prefix block | 7682700 `DETAIL_PLACEHOLDER` with `(?=>)` | `<redeemed>` no longer blocked; `<gold>` style names still reserved |
| AG-H01/H02/H03/H04, Task 20 C1-C10 | 237986c | `RequiredPackLockListener` at LOWEST registered only when required+available, `hasWaiters()` fast path, `JoinState`, invulnerable/hub teleport, extend-on-progress, bypass re-evaluation, `debugLog {}` lazy |

## Findings

### H1 `[Bug]` `[High]` Esc on the class-confirmation Dialog leaves a new player without a class menu (7682700 `ClassSelectionUi.kt` confirm ~l.81)
`canCloseWithEscape(true)` but only the two buttons call `confirming.finish()` / `onCancel`. Esc closes the dialog; the InvUI window was already closed when the dialog opened, `confirming` stays true so `onClose` never reopens the list → a brand-new player has no menu until relog (`open()` resets the flag). Fix: Paper Dialog has no close callback, so either keep Esc disabled and drop/extend the 2-minute lifetime (re-show before expiry), or add a reopen path (`/ec` subcommand or a repeating check while `classId == null`). Needs real client.

### M1 `[Bug]` `[Med]` Hub teleport for every gated join (237986c `beginRequiredJoin`)
The teleport to `DemoWorld.luminaSpawn` is unconditional, while `finishLoadedJoin` only teleports first-time/classless players → returning players always log in at Lumina and lose their logout position. Store `originalLocation` in `JoinState` and restore on release (except first join) or do not teleport.

### M2 `[Bug/Security]` `[Med]` `setInvulnerable(true)` persists through crashes (237986c `JoinState.originalInvulnerable`)
Player data stores `Invulnerable`; a crash/kill during the gate leaves it true and the next join records "original = true" → permanent invulnerability. Damage is already cancelled by `RequiredPackLockListener.damage`, so remove the flag, or tag with a PDC marker and repair on join.

### M3 `[Bug]` `[Med]` No timeout if the dispatch task throws (237986c l.358-369)
The timeout is armed only after "request sent"; if `send()` throws before its `try` (prompt creation, `url(hash)`) the player is frozen forever. Arm the timeout at gate start (re-arm on sent) and wrap the dispatch in try/catch.

### M4 `[Perf]` `[Med]` `tradeBound` calls `bound()` on every click (595f96c, kept by ee13c75)
`currentBound`/`cursorBound` are computed eagerly (each = `itemMeta` clone + lookup); before, own-inventory clicks (`external == false`) never called `bound()`. Make them lazy or restore the short-circuit order.

### M5 `[Perf]` `[Med]` `PaperInventoryAccess.plan` double-clones (595f96c)
`afterRemoval` is cloned even when `remove.isEmpty()` and for `canExchange`; `storageContents` already returns copies so the first `map { clone }` is redundant; `Plan` is a `data class` with `Array` fields. Use `if (remove.isEmpty()) slots else copy`, plain class.

### M6 `[Test]` `[Med]` No behaviour test for the join state machine
`RequiredPackLockListenerTest` and the policy tests are pure. Add MockBukkit tests firing `PlayerResourcePackStatusEvent` (dispatch → EXTEND/COMPLETE/DISCONNECT → timeout → quit/relog) and cover M1-M3; plus a real-Paper run.

### M7 `[Bug]` `[Med→Low]` Early return in `move()` (595f96c l.834)
Fine for safe areas; confirm no other sub-block logic lived in that handler (gathering/fishing cancel are separate listeners → looks OK). Kept as a check item.

### M8 `[Bug]` `[Med]` Per-second `syncTitleAdvancements` removed (595f96c l.668)
Titles now unlock only on transaction/deliverLocked/gather/fish paths. Admin `/ec setlevel`, flag changes and party-XP members without a transaction no longer toast until the next event. Add a test per title-condition type.

### Low
- **L1 `[Idiom]`** `JoinState` is a `data class` with tasks/lists (identity is what is used) → plain class; `terminalStatuses` duplicated in the controller companion and `RequiredPackGatePolicy`.
- **L2 `[Style]`** 20 one-line lock handlers → table-driven `registerEvent`; consider also `PlayerBucketEmpty/Fill`, `PlayerShearEntity`, `PlayerFishEvent`, `PlayerBedEnter`, `SignChange`; document that chat is intentionally allowed.
- **L3 `[Idiom]`** `GameException` migration is partial: `runExpectedTrade` still matches `message.startsWith("ec.error.")`, `completeReadyAuto` compares the message string, `arguments` is unused by `diagnostic`; finish it (`requireGame` in `QuestEngine.complete`, catch `GameException` everywhere).
- **L4 `[Perf]`** `dialogueComponents` key includes locale though text is not localized; lore chunks/choice labels/Dialog body still `mm.deserialize` per show; clearing the cache in `reloadNpcs()` is an odd place.
- **L5 `[Idiom]`** `enhancementLoreLines`: `size != cap * 2` is a wrong staleness test (identical ja/en strings dedupe in the Set → recomputed every time); store the cap.
- **L6 `[Bug]`** `TreasureController.chestsByChunk` uses `position.x.toInt() shr 4` (truncation): wrong chunk for negative fractional coordinates → `floor`.
- **L7 `[Perf]`** `QuestEngine.event` returns Boolean but `tick()` still deep-snapshots + `skipIfUnchanged`; FIX-27 is half done.
- **L8 `[Style]`** Mis-indented `require(...)` leftovers: `WorldValidation.kt:145`, `NpcController.kt:423`, `SimulationCommandController.kt:476`.
- **L9 `[Test]`** `smoke-test.ps1` greps localized text (`コンテンツ検証成功.` / `Content validation succeeded.`) → emit a stable marker; `PaperContentValidator` does not check dungeon palette size ≥ 3 (`DungeonController` indexes `palette[2]`) nor wolf `visual.variant`.
- **L10 `[Bug]`** `serverRootFromDataFolder(parent.parent)` assumes `plugins/<name>`; fall back to `worldContainer` when `spigot.yml` is absent.
- **L11 `[Perf]`** Write deadline minimum rate 512 KB/s is demanding for a 20 MB pack on 1–3 Mbps links (180 s cap) → ~128 KB/s or a no-progress watchdog.
- **L12 `[Bug]`** Language reload message is sent before the async catalog swap (the success message uses the old language).

## Opinion on remaining partial items (from `docs/AGENT_STATUS.md`)
- **Wave 4 partial (event-driven quest progress / per-second transaction)**: acceptable for now **only if** M4/M5 are fixed (they are regressions of the same wave) and the per-player `p.snapshot()` + `storageContents` clone in `tick()` is gated by a cheap "has EXPLORE/COLLECT work" pre-check; otherwise record as accepted with a measured cost (players × profile size) before release.
- **Wave 5 partial (RpgPlugin split, full `GameException` migration)**: acceptable to defer the file split; do not defer L3 (mixed string/typed errors make later refactors risky).
- **Wave 6 partial (enum/content validation)**: acceptable; add palette-size and `visual.variant` checks (L9) when convenient.
- Real-client final QA should include: H1 (Esc on dialog), M1 (returning player position), M2 (invulnerable restore after kill -9), M3 (forced dispatch failure), `ui.theme.mode: full` title offset, N5 local-config (`bind_host`/`public_host`), and the Batch 1 carry-over list.
