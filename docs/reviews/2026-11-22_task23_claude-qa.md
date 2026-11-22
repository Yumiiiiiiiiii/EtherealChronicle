# Task 23 / Batch 3 review (Claude-QA) — codex/foundation → master 559daef (`60bd57a`), 2026-11-22

Method: read the Task 23 diff (identical to the working-tree diff I reviewed before it was committed: 15 `src/main` files, +226/-108) plus new tests; no code changed, no real-client run. Findings were first sent to Codex by message; this is the audit copy.
Lenses: Correctness/Safety · Perf · Idiom · Style · Test.

## Go / No-Go
**GO** for the real-Paper smoke (`scripts/smoke-test.ps1`) and the real-client final QA: **0 High, 0 blocking Med.** Two Med items are not blockers but must be exercised in the real run (M3 spawn/gate behaviour, M1 perf measurement). Smoke caveat: the script still matches the localized text, now with the full-width `。` (T22-H05 fixed); `EC_CONTENT_VALIDATION_SUCCESS` is logged but not used — Low (L9b).

## Re-check of earlier findings

### My Task 22 review
| Id | Status | Evidence |
|---|---|---|
| H1 Esc on class-confirm dialog | **OK** | `canCloseWithEscape(false)`, callbacks 24 h, `/ec class` reopens when `classId == null` (`classSelectionCanReopen`), `clear()` on quit; real client still to confirm (no Esc path, dialog modal) |
| M1 hub teleport for every join | **OK** | teleport removed from `beginRequiredJoin` |
| M2 persisted invulnerability | **OK** | `originalInvulnerable` and `isInvulnerable` handling removed |
| M3 no timeout if dispatch throws | **OK** | `scheduleRequiredTimeout` at gate start; dispatch wrapped in try/catch → release/kick |
| M4 `tradeBound` eager `bound()` | **OK** | bundle check first, early return for non-external non-drop clicks, `bound()` lazy |
| M5 `plan()` clones | **OK** | `afterRemoval` skipped when `remove.isEmpty()`, `Plan` plain class |
| M6 join-state tests | **Partially** | `ResourcePackControllerIntegrationTest` (accept→loaded override OFF, decline→kick) added; timeout/extend, dispatch failure, quit/relog, bypass change, listener priority still untested; one assertion vacuous (see L7) |
| M7 `move()` early return | **OK** | no other sub-block logic affected |
| M8 title toasts removed from tick | **Mostly OK** | sync added after kill/party XP and admin level/flag; remaining paths only via transactions (acceptable, covered by `deliverLocked`) |
| L1 JoinState / duplicate status sets | **OK** | plain class; one `terminalStatuses` in `RequiredPackGatePolicy` |
| L2 20 handlers | **Open (Style)** | unchanged, acceptable |
| L3 GameException migration | **Partial** | quest/trade/dialogue/teleport now typed; `error("ec.error.transaction_busy")` and some `require` remain (L1 below) |
| L4 dialogue cache | **Partial** | key simplified to `(id,node)`, body reuses component; lore/choice labels still parsed per show |
| L5 lore cap staleness | **OK** | `enhancementLoreCap` |
| L6 negative chunk coords | **OK** | `chunkCoordinates()` with `floor` |
| L7 event() Boolean unused / tick cost | **Partial** | pre-check added (`mayNeedPeriodicUpdate`/`needsPeriodicUpdate`); see M1 |
| L8 mis-indents | **Open (Polish)** | `WorldValidation.kt`, `NpcController.kt`, `SimulationCommandController.kt` untouched |
| L9 smoke marker / palette / variant | **OK** | `dungeonPalette` (≥3 + blocks), `wolfVariant` check, `EC_CONTENT_VALIDATION_SUCCESS` logged |
| L10 server root | **OK** | conventional root must contain `spigot.yml`, else `worldContainer` |
| L11 write deadline rate | **OK** | 128 KB/s |
| L12 reload message order | **OK** | success/failure sent from the language callback |

### Antigravity Task 22
| Id | Status | Evidence |
|---|---|---|
| T22-H01 fishing air-click routing | **OK (needs real client)** | `ignoreCancelled=false` + `shouldProcessFishingInteract(useItemInHand, hand)`; lock listener now sets `useItemInHand/useInteractedBlock = DENY`; unit tests for the predicate and the lock; no test drives `FishingController.interact` itself (L8) |
| T22-H02 Esc / `confirming` residue | **OK** | see H1 |
| T22-H03 forced teleport for returning players | **OK** | see M1 |
| T22-H04 `send()` catch deadlock | **OK** | catch releases with `continueJoin = !mustDisconnect`; dispatch Runnable also guarded |
| T22-H05 smoke full-width period | **OK** | script matches `コンテンツ検証成功。` |

## New findings (Task 23 diff)

### Med
- **M1 `[Perf]`** `QuestEngine.mayNeedPeriodicUpdate` / `needsPeriodicUpdate` (`AdventureServices.kt`, `AdventureController.tick`): a quest with only COLLECT objectives still passes the pre-check (`filter{!=COLLECT}.all{}` over an empty list is true), so every player with an active auto-complete collect quest runs `ready()` each second, and `PaperInventoryAccess.count()` clones 36 slots per call. Compute one item→count map per tick behind a counting wrapper, and iterate an "active auto quests" set instead of every quest. Measure players × quests in the real run.
- **M3 `[Bug/real-client]`** `ResourcePackController.beginRequiredJoin`: with the hub teleport removed, waiting players stand at the vanilla-world join point; damage is locked, but an elevated/uneven spawn plus cancelled `PlayerMoveEvent` may trigger the vanilla "Flying is not enabled" kick. Verify in the real run (spawn on trees/caves, full 120 s wait); fallback: allow falling/vertical moves while still cancelling block changes horizontally.

### Low
- **L1 `[Bug]`** `AdventureController.transaction`: `error("ec.error.transaction_busy")` is an `IllegalStateException`, so the typed `runExpectedTrade` (GameException only) no longer maps it (InvUI handler still does). Throw `GameException` there and for remaining `error("ec.error.*")`.
- **L2 `[Bug]`** `transaction()` reuses `ec.error.sell_items_missing` for every consumption failure (quest consume, enhance, bait) — add a generic `ec.error.items_missing`.
- **L3 `[Perf]`** `needsPeriodicUpdate` ignores objective order (`event()` advances only the first unsatisfied objective) → no-op transactions for quests like [talk, explore] while standing in the area; EXPLORE `*`/`ANY` targets (supported by `event()`) are not handled by the pre-check (no shipped quest uses them).
- **L4 `[Perf]`** `AdventureController.tick` still allocates a `Position` and scans all areas per player per second; use a per-world index as for safe areas.
- **L5 `[Bug/Check]`** `validatePaperContent` (incl. `Registry.WOLF_VARIANT.get`) runs inside `CompletableFuture.supplyAsync` in `/ec reload`/`validate`; do the Paper-registry checks on the main thread. `wolfVariant` does not check `entity == WOLF`.
- **L6 `[Bug]`** `ClassSelectionUi`: 24 h × 2 callbacks per confirm; each "no" creates a new pair while the old "yes" stays registered (registry growth per cancel cycle). Acceptable; note.
- **L7 `[Test]`** `ResourcePackControllerIntegrationTest.declineReleasesWaiter…`: `assertFalse(plugin.profiles.containsKey(uuid))` is vacuous (no join event → no profile). Drive the real join path; add timeout-extend/kick, dispatch exception, quit-while-waiting, relog, bypass-change and lock-listener-priority cases.
- **L8 `[Test]`** `FishingInteractRoutingTest` covers the predicate and the lock listener only; add a test that a `RIGHT_CLICK_AIR` with block DENY reaches `FishingController.interact` (cast started) and that a gate-locked player does not.
- **L9 `[Style/Idiom]`** dead `RequiredPackGatePolicy.shouldDispatch`; mis-indented `if(::treasures…)`; language swap happens after `content.install`, so a few messages in between may log "Missing language key"; **L9b** `smoke-test.ps1` should match `EC_CONTENT_VALIDATION_SUCCESS` instead of localized text.
- **L10 `[Check]`** fishing with `ignoreCancelled=false` also casts on a `RIGHT_CLICK_BLOCK` that a region plugin denied only for the block (`useInteractedBlock=DENY`, item DEFAULT); fine for water casting, confirm intent.
- **L11 `[Bug-latent]`** `PlayerStore` bounds `skill_levels` to 1..100 (hard-coded) while the real cap is `balance.skill_level_cap` (currently 5): harmless today, but a content change above 100 or one corrupt entry fails the whole profile load — clamp at runtime instead.

## Totals
High 0 · Med 2 (M1 perf, M3 real-client check) · Low 11. By lens: Perf 3, Bug/Check 5, Test 2, Style/Idiom 1.

## Real-run checklist (carry into the final QA)
1. Smoke (`scripts/smoke-test.ps1`, `ec validate`, no WARN/ERROR beyond allow-list).
2. Fresh non-op first join: prompt → accept → class menu; decline → kick; timeout → kick; op bypass; **long wait (>60 s) on the prompt screen without flying kick (M3)**.
3. Esc on class dialog impossible; `/ec class` reopens; `Esc` on NPC Dialog works.
4. Fishing cast/reel on real Paper (T22-H01) with and without gate; held-click reel timing.
5. Returning player keeps logout position (M1).
6. Shop buy/sell with refresh, full-inventory NPC talk (N1), claim/quit race (N2) where feasible.
7. `ui.theme.mode: full` title offset screenshots (FIX-26), pack on/off GUI comparison.
8. TPS/MSPT with several simulated players holding an active collect quest (M1).
