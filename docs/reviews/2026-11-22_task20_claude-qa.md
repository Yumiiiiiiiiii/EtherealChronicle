# Task 20 spot review (Claude-QA) — `git show 8f3acfa`, 2026-11-22

Scope: `ResourcePackController.kt`, `RpgPlugin.kt` (join gate + lock handlers), tests, `config.yml`, `lang/*.yml`. No code changed.
Companion: `docs/reviews/2026-11-22_task20_antigravity.md` (T20-H01…P02). I read it and the diff; below, "AG-x" means I agree with that finding and do not repeat it. New findings are `C1…C10`. Real-client facts come from my Task 20 run (`docs/MANUAL_QA_RESULT.md`, ff32ab0): accept → loaded in ~5 s, no response → kick at the configured timeout, op bypass → optional prompt.

## Agreement with Antigravity (not new)
- **AG-H01 (priority HIGHEST is too late)** — agree. Bukkit runs LOWEST→…→HIGHEST→MONITOR, so a HIGHEST cancel arrives after NORMAL/HIGH handlers (`RpgPlugin.interact`, `swap`, `damage`) have already applied their side effects. Verified by reading the handler priorities; use LOWEST (and `ignoreCancelled=false` so you also see already-cancelled events by earlier listeners).
- **AG-H02 (lock gaps: entity interact, pickup, projectile, portal, tick loops)** — agree; most important: `AdventureController.interact` / `TreasureController.interact` / `GatheringController.interactDisplay` use `PlayerInteractEntityEvent`, which none of the 12 locks cover, and the Paper Dialog bypasses `InventoryOpenEvent`.
- **AG-H03/H04 (dead-lock path in `send`, un-cancelled next-tick task)** — agree; I reproduced neither (my runs used one clean join each), so keep them as "verified by reading".
- **AG-M05 (tests only cover pure helpers)** — agree; see C6 for the real-client matrix.

## New findings

### C1 `[Bug]` `[Med]` The waiting player stands in the vanilla overworld for up to the whole timeout (observed)
- Evidence: server log in every gate run, e.g. `QaGate1 … logged in with entity id 1461 at ([minecraft:overworld]-2.5, 66.0, -6.5)`; coordinates differ per join (66–73 Y, random-looking XZ). The Lumina teleport only happens in `finishLoadedJoin` (`RpgPlugin.kt`, after the pack is loaded).
- Problem: a player who sits on the pack prompt (default 120 s) is in an unmanaged vanilla world: hostile mobs at night, drowning/lava/fall damage, other players. Movement is cancelled per block, but gravity/knockback/mob targeting are not, and the existing damage lock does not cover non-player damage sources reliably (AG-H02 §3/§5).
- Fix: teleport to the safe hub (or a void-less holding box) at gate start, and mark the player invulnerable + hidden from mob targeting (`setInvulnerable(true)` until release; restore afterwards, also on quit).
  Before: `beginRequiredJoin` → request only.
  After: `player.teleport(DemoWorld.luminaSpawn(world)); player.isInvulnerable = true` in `beginRequiredJoin`, and `isInvulnerable = false` in `releaseRequiredWait` (and `onQuit`).

### C2 `[Bug]` `[Med]` The timeout is not extended while the client is actually downloading
- `scheduleRequiredTimeout` is armed once at "request sent" (`ResourcePackController.kt` diff lines 114-125). `ACCEPTED`/`DOWNLOADED` events do not re-arm it. A slow link (pack grows beyond today's 47 KB) or a user who reads the prompt for a while can be kicked mid-download with the generic timeout text, even though progress is being made.
- Fix: on `ACCEPTED`/`DOWNLOADED` (and optionally `SUCCESSFULLY_LOADED` pending reload) re-arm once with `requiredTimeoutSeconds`; keep a hard cap (e.g. 3×). Also log those intermediate statuses at debug (AG-M03).
  After: `if (event.status == ACCEPTED || event.status == DOWNLOADED) scheduleRequiredTimeout(event.player, extend = true)`.

### C3 `[Bug]` `[Med]` (needs check) Bypass is evaluated at join time, before permission plugins finish loading
- `beginRequiredJoin` (diff line 83) and `send` (`:103`) call `player.hasPermission("etherealchronicle.pack.bypass")` at `PlayerJoinEvent`. With LuckPerms/other permission plugins, group permissions can be attached after the join event (async load); vanilla `op` works (what I tested), group-based bypass may not. Result: staff are gated/kicked (`bypass=false` in the debug line) although they have the node.
- Fix: re-evaluate `bypass` at the dispatch tick (already one tick later, still early) and again right before the timeout kick; document that the node must be granted via `default: op` or a permission plugin that loads before join. Add the bypass value to the "request sent" debug line (it is only in the eligibility line today).

### C4 `[Bug]` `[Low]` (needs check) Terminal-status list kicks required players for non-failure events and uses the "declined" text for all
- `onStatus` (`terminalStatuses`) treats every terminal status the same: `DECLINED`, `FAILED_DOWNLOAD`, `INVALID_URL`, `DISCARDED`, `FAILED_RELOAD`. `DISCARDED` is what the client sends when the pack is replaced/removed by another plugin (e.g. a ForcePack/other manager pushing a pack with the same UUID or `removeResourcePacks`), so a correctly loaded player could be kicked. All cases show `ec.resource_pack.required_declined` ("declined").
- Fix: kick only on `DECLINED`/`FAILED_*`/`INVALID_URL`; ignore `DISCARDED` unless the gate is still waiting; map failure → `ec.resource_pack.required_failed` text (also fixes AG-M06's misleading message).

### C5 `[Bug]` `[Low]` Config values are captured once; `/ec reload` does not apply the new gate settings
- `required`, `required_timeout_seconds`, `debug` are `val`s read in the constructor (`ResourcePackController.kt:16-19`). `/ec reload` (`RpgPlugin.kt:882-883`) re-reads `ui.theme.mode` only. Changing `required`/timeout/debug needs a restart, but the command reports success. Also `join()` now calls `resourcePacks.beginRequiredJoin` without the `::resourcePacks.isInitialized` guard the old code had (diff line 246) — safe only because `ready` is set after construction; keep an assertion/comment.
- Fix: say "restart required for resource_pack.*" in `ec.command.reload_success` (or rebuild the controller on reload when idle).

### C6 `[Test]` `[Med]` Real-client matrix is manual; what is and is not covered
- Verified manually (ff32ab0): fresh non-op accept, no-response timeout, op bypass (embedded_http only).
- Not verified: explicit decline inside the gate; quick relog while waiting (AG-H04); relog with pack already cached (instant load before `whenRequiredPackReady` registered — logic path `!isWaiting → continuation()`); game_port and external_managed with the gate; permission-plugin bypass (C3); server shutdown during wait; `/ec reload` during wait; two players waiting at once.
- Fix: automate with a protocol bot (join → expect `ClientboundResourcePackPush` → reply LOADED/DECLINED/nothing → assert kick text/timing and that no gameplay packets are accepted while waiting). Unit-level: extract the decision table (`status × required × bypass → {release, kick, ignore}`) into the pure `RequiredPackGatePolicy` and test it exhaustively (it currently tests only `shouldWait`/`timeoutTicks`).

### C7 `[Perf]` `[Low]` `debugLog` builds its string even when debug is off
- `debugLog("request sent player=${…} id=… sha1=$hash")` (diff lines 66,86,94,121,140,146) interpolates before the call; the `else logger.fine(...)` branch also logs at FINE for every join/status event.
  Before: `private fun debugLog(message: String) { if (debug) logger.info(…) else logger.fine(…) }`
  After: `private inline fun debugLog(message: () -> String) { if (debug) plugin.logger.info("Resource pack debug: ${message()}") }` (drop the FINE branch or guard with `logger.isLoggable(FINE)`).

### C8 `[Idiom]` `[Low]` Per-player state is spread over four collections plus a wrapper class
- `requiredPackGate` (a `MutableSet` in a class), `scheduledJoinRequests`, `requiredWaiters`, `requiredTimeouts`, plus `pendingRequests`/`loadedPlayers`/`preferences`/`observedManagedStatuses` (AG-L02). Each needs separate cleanup in `onQuit`/`close` (and one was already missed, AG-M03).
  Before: `releaseRequiredWait` touches 4 maps.
  After:
  ```kotlin
  private class JoinState(var dispatch: BukkitTask?, var timeout: BukkitTask?, val waiters: MutableList<() -> Unit> = mutableListOf()) {
      fun cancelAll() { dispatch?.cancel(); timeout?.cancel() }
  }
  private val joinStates = mutableMapOf<UUID, JoinState>()   // present == waiting
  ```
  `isWaiting = id in joinStates`; release = `joinStates.remove(id)?.also { it.cancelAll() }`. Removes `RequiredPackGate` and fixes the untracked `runTaskLater` (AG-H04) by storing the task.

### C9 `[Style]` `[Low]` Twelve near-identical lock handlers → one table, registered only when needed
- `RpgPlugin.kt` +90 lines of one-line handlers. Register them programmatically from a list so adding an event is one line, and skip registration entirely when `required` is false/no delivery (also removes AG-M04 per-event cost).
  Before: `@EventHandler(priority=HIGHEST, ignoreCancelled=true) fun lockCommandsUntilPack(e: PlayerCommandPreprocessEvent) { if (isPackLocked(e.player)) e.isCancelled = true }` ×12
  After:
  ```kotlin
  private fun <E> lock(type: Class<E>, who: (E) -> Player?) where E : Event, E : Cancellable =
      server.pluginManager.registerEvent(type, lockListener, EventPriority.LOWEST,
          { _, e -> if (type.isInstance(e)) who(type.cast(e))?.takeIf(::isPackLocked)?.let { (e as Cancellable).isCancelled = true } }, this)
  lock(PlayerCommandPreprocessEvent::class.java) { it.player }
  lock(PlayerInteractEntityEvent::class.java) { it.player }
  // …
  ```

### C10 `[Idiom]` `[Polish]` Use Paper's `hasChangedBlock()` for the move lock
- `lockMovementUntilPack` compares three block coordinates manually (and ignores a world change).
  Before: `event.from.blockX != event.to.blockX || event.from.blockY != event.to.blockY || event.from.blockZ != event.to.blockZ`
  After: `if (isPackLocked(event.player) && event.hasChangedBlock()) event.isCancelled = true` (rotation-only moves stay allowed, cheaper, world change counts as changed). Consider `event.setTo(event.from)` only for the gate to avoid rubber-band spam.

## Counts (new, excluding Antigravity's)
High 0 · Med 4 (C1, C2, C3, C6) · Low 5 (C4, C5, C7, C8, C9) · Polish 1 (C10).
By category: Bug 5 (C1-C5) · Test 1 (C6) · Perf 1 (C7) · Idiom 2 (C8, C10) · Style 1 (C9).
Recommendation: fix AG-H01/H02 (priority + lock coverage) together with C1 (holding location + invulnerability) before enabling `required: true` by default in production.
