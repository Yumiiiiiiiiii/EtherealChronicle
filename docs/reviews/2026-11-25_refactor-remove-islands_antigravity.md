# Code Review: `refactor/remove-islands` (Antigravity)

- **Reviewer**: Antigravity (independent cross-model review; did not read sibling review files)
- **Diff Reviewed**: `master...refactor/remove-islands` (HEAD `ae4e59e`, 6 commits: `cc6adc8`, `1a438af`, `0329dea`, `45d0f35`, `df06e49`, `ae4e59e`; 70 files changed, `+261 / -1478`)
- **Test Verification**: `.\gradlew.bat test --no-daemon` (`BUILD SUCCESSFUL`) + inspection of JUnit XML reports in `build/test-results/test/`
- **Note on Working Tree**: During review, `git status` showed an uncommitted local edit to `AdventureController.kt` (changing `death()` to `whenAvailable` + `transaction`). Primary findings below target committed `HEAD` (`ae4e59e`), with an explicit analysis of the uncommitted working-tree change in **H-3**.

## Summary

| Severity | Count | Categories |
| --- | ---: | --- |
| **High** | 4 | 2 Test, 1 Bug/Security, 1 Bug |
| **Med** | 4 | 2 Bug, 1 Test, 1 Bug/Style |
| **Low** | 4 | 2 Idiom/Dead Code, 1 Bug/Design, 1 Perf |
| **Polish** | 1 | 1 Style |

The core removal of `DemoWorld` / `DemoGenerator` / floating-island schematics and consolidation onto the overworld-only runtime is directionally clean (`DungeonController.quit` / `shutdown` / `move` / `recover` cleanly preserve journal-backed recovery). However, the refactor introduced **silent test skips (4 QA suites)**, **2 false-positive dungeon QA tests**, **a death gold-loss transaction race (and a death-quit exploit in the uncommitted fix)**, and **unloaded-chunk failures in airship/return teleports** that were previously masked by `ec_world` and `QaWorldMock`.

---

## High Findings

### H-1 `[High / Test]` 4 QA test suites silently SKIPPED because `QaWorldMock` does not override `getWorldFolder()` and leftover `.etherealchronicle-built` calls remain

- **Files**:
  - `src/test/kotlin/net/nakaymi/etherealchronicle/qa/QaPlayerMock.kt:14-26`
  - `src/test/kotlin/net/nakaymi/etherealchronicle/qa/DialogueTeleportTest.kt:34`
  - `src/test/kotlin/net/nakaymi/etherealchronicle/qa/EnhanceFlightTest.kt:34`
  - `src/test/kotlin/net/nakaymi/etherealchronicle/qa/EnhanceStressTest.kt:33`
  - `src/test/kotlin/net/nakaymi/etherealchronicle/qa/EnhancementGuiTest.kt:24`
- **Lens**: Correctness & Safety (Test)
- **Why**:
  Replacing `OwnedWorldMock` (which overrode `getWorldFolder()`) with `QaWorldMock` left behind `File(world.worldFolder, ".etherealchronicle-built").writeText("v4\n")` in 4 QA test suites. MockBukkit's `WorldMock.getWorldFolder()` throws `UnimplementedOperationException`, which extends JUnit's `TestAbortedException`. As a result, Gradle reports `BUILD SUCCESSFUL` while all 4 suites are silently marked `skipped=1` in `build/test-results/test/TEST-*.xml`:
  - `DialogueTeleportTest` (`tests=1 skipped=1`) — airship teleport regression test never ran.
  - `EnhanceFlightTest` (`tests=1 skipped=1`) — enhancement flight rollback/settlement never ran.
  - `EnhanceStressTest` (`tests=1 skipped=1`) — enhancement stress test never ran.
  - `EnhancementGuiTest` (`tests=1 skipped=1`) — enhancement GUI test never ran.
  Moreover, `.etherealchronicle-built` was a `DemoWorld` marker file that no longer exists in production.
- **Suggested Alternative**:
  Remove the dead `.etherealchronicle-built` lines in all 4 tests (and optionally override `getWorldFolder()` in `QaWorldMock` returning a temporary directory if any future test exercises `AdvancementDatapack` or world-folder I/O).

```kotlin
// Before (DialogueTeleportTest.kt:33-34, EnhanceFlightTest.kt:33-34, EnhanceStressTest.kt:32-33, EnhancementGuiTest.kt:23-24)
val world = QaWorldMock.add(server, WorldIds.DEMO)
File(world.worldFolder, ".etherealchronicle-built").writeText("v4\n")

// After
val world = QaWorldMock.add(server, WorldIds.DEMO)
```

---

### H-2 `[High / Test]` `DungeonCoexistTest.sanctumAndCitadel` and `NoviceArenaTest.arenaGateEnterLeaveReenter` are false positives — dungeon entry crashes on all 8 slots and `leave()` is a no-op

- **Files**:
  - `src/test/kotlin/net/nakaymi/etherealchronicle/qa/DungeonCoexistTest.kt:50-88`
  - `src/test/kotlin/net/nakaymi/etherealchronicle/qa/NoviceArenaTest.kt:13-33`
  - `src/main/kotlin/net/nakaymi/etherealchronicle/paper/DungeonController.kt:174`
- **Lens**: Correctness & Safety (Test)
- **Why**:
  Neither test calls `seedArenaSchematic(...)`. Inspecting `build/test-results/test/TEST-net.nakaymi.etherealchronicle.qa.DungeonCoexistTest.xml` and `TEST-net.nakaymi.etherealchronicle.qa.NoviceArenaTest.xml` shows every entry failing across all 8 candidate slots (`0..7`):
  - `DungeonCoexistTest`: `Dungeon placement could not start for slot 0..7: Can not apply block data key to 'minecraft:soul_lantern': hanging` (16 warnings).
  - `NoviceArenaTest`: `Dungeon placement could not start for slot 0..7: Can not apply block data key to 'minecraft:lantern': hanging` (8 warnings).

  Both tests still pass for the wrong reason:
  1. `DungeonController.activeCount()` (`DungeonController.kt:174`) returns `(instances.keys + slotTracker.activeSlots()).size`, which includes `RESERVED` slots before schematic placement finishes.
  2. `qaPump { plugin.dungeons.activeCount() == 2 }` (and `== 1` in `NoviceArenaTest`) returns `true` immediately while the slots are still `RESERVED` (no `Instance` exists yet, no players were teleported).
  3. `plugin.dungeons.leave(p)` runs while `instanceForPlayer(p.uniqueId) == null`, so `leave()` returns immediately as a complete no-op.
  4. `qaPump { plugin.dungeons.activeCount() == 0 }` succeeds solely because MockBukkit crashes on slot `7` and rolls back the failed reservation with `ec.error.dungeon_full`.
  5. Additionally, `NoviceArenaTest` never waits for `plugin.dungeons.isJournalReady()` before calling `enter()`, relying on timing luck.
- **Suggested Alternative**:
  Seed arena schematics via `seedArenaSchematic(...)`, wait for `isJournalReady()`, assert `containsPlayer(p.uniqueId)` and actual arena teleport (`p.location.y > 150.0`) before calling `leave()`, and verify the player returned to the portal exit afterward.

```kotlin
// Before (NoviceArenaTest.kt:19-33)
plugin.dungeons.enter(p, "ec:dungeon_novice_arena")
assertTrue(server.qaPump { plugin.dungeons.activeCount() == 1 }, "novice arena did not open")
plugin.dungeons.leave(p)
assertTrue(server.qaPump { plugin.dungeons.activeCount() == 0 }, "novice arena did not close")

// After
seedArenaSchematic(plugin.dungeons, "ec:dungeon_novice_arena")
assertTrue(server.qaPump { plugin.dungeons.isJournalReady() }, "return journal did not become ready")
plugin.dungeons.enter(p, "ec:dungeon_novice_arena")
assertTrue(
    server.qaPump { plugin.dungeons.containsPlayer(p.uniqueId) && plugin.dungeons.activeCount() == 1 },
    "novice arena did not become active",
)
assertTrue(p.location.y > 150.0, "player was not teleported to sky arena")
plugin.dungeons.leave(p)
assertTrue(
    server.qaPump { !plugin.dungeons.containsPlayer(p.uniqueId) && plugin.dungeons.activeCount() == 0 },
    "novice arena did not close cleanly",
)
assertTrue(p.location.y < 100.0, "player did not return to overworld exit")
```

---

### H-3 `[High / Bug / Security]` `AdventureController.death` gold loss races with in-flight transactions (rollback/dupe), ignores event cancellation, and has zero test coverage

- **Files**:
  - `src/main/kotlin/net/nakaymi/etherealchronicle/paper/AdventureController.kt:402-412` (`HEAD` `ae4e59e`)
  - `src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt:827-837` (`settleEnhancement`)
  - `src/test/kotlin/net/nakaymi/etherealchronicle/qa/ManualClientFindingsTest.kt:50-54`
- **Lens**: Correctness & Safety (Bug / Security / Test)
- **Why**:
  1. **Unserialized mutation (`HEAD` `ae4e59e`)**: `death()` sets `profile.currency = 0` and calls `plugin.save(profile)` without acquiring `locks` / `profileTransaction`.
     - If a `transaction(...)` or `profileTransaction(...)` is in-flight when the player dies and fails persistence, its rollback `p.restore(before)` overwrites `profile.currency` back to pre-death gold (bypassing the death penalty).
     - If `deliverLocked` (`AdventureController.kt:549`) is in-flight, `profile.pendingRewards` is transiently empty while async persistence runs; an unlocked `plugin.save(profile)` can race with the persistence queue and write inconsistent mailbox state.
     - If an enhancement flight is pending, `RpgPlugin.settleEnhancement` (`RpgPlugin.kt:830`) later adds `flight.currency` back onto `profile.currency` after death.
  2. **Missing `ignoreCancelled = true`**: `@EventHandler fun death(e: PlayerDeathEvent)` runs even if another plugin cancels `PlayerDeathEvent` (which is `Cancellable` in Paper), wiping the player's gold even when they did not die.
  3. **Zero test coverage for gold loss**: `ManualClientFindingsTest.kt:50-54` only asserts `death.drops.isEmpty()` and `death.keepInventory && death.keepLevel`; it never sets `profile.currency > 0`, never checks `profile.currency == 0`, and never verifies persistence or `ec.death.gold_lost`.
  4. **Warning about the uncommitted working-tree edit (`AdventureController.kt:403-417`)**: The uncommitted change wraps death gold loss in `whenAvailable(player.uniqueId) { transaction(player, { profile.currency = 0 }, persistConsumption = false) { ... } }`. That approach introduces **three new issues**:
     - **Death-quit gold-keep exploit**: `whenAvailable` runs via `resume()` on the next tick after a lock releases; if the player dies while locked (e.g., mid-shop/combat reward) and immediately disconnects, `resume()` / `transaction()` either skips offline players or fails when `plugin.profiles[id]` is removed on quit — letting players dodge the gold penalty by combat-logging on death.
     - **Stale captured references**: capturing `player` and `profile` across `whenAvailable` operates on stale instances if the player reconnects before the callback fires (unlike `RpgPlugin.rewardKill`, which re-fetches `Bukkit.getPlayer(id)` and `profiles[id]`).
     - **Unnecessary inventory cloning**: calling `transaction(player, ...)` clones all 36 storage slots and triggers `deliverLocked` for a profile-only currency mutation (`profileTransaction` exists for profile-only updates).
- **Suggested Alternative**:
  Add `ignoreCancelled = true`, use `profileTransaction(playerId, ...)` (or settle synchronously on `beforeQuit` if a death penalty is queued), re-fetch `profiles[id]` inside `whenAvailable`, and ensure a pending death penalty cannot be evaded by disconnecting before the lock releases (e.g., track a `pendingDeathPenalties: MutableSet<UUID>` applied in `beforeQuit` / lock release before final save). Add a test asserting `profile.currency == 0`, persistence, and `ec.death.gold_lost`.

```kotlin
// Before (AdventureController.kt:403-412, HEAD ae4e59e)
@EventHandler fun death(e: PlayerDeathEvent) {
    e.keepInventory = true; e.keepLevel = true
    e.drops.clear(); e.droppedExp = 0
    val profile = plugin.profiles[e.entity.uniqueId] ?: return
    val lost = profile.currency
    if (lost <= 0) return
    profile.currency = 0
    plugin.save(profile)
    e.entity.sendMessage(plugin.message(e.entity, "ec.death.gold_lost", "amount" to lost))
}

// After
@EventHandler(ignoreCancelled = true)
fun death(e: PlayerDeathEvent) {
    e.keepInventory = true; e.keepLevel = true
    e.drops.clear(); e.droppedExp = 0
    val playerId = e.entity.uniqueId
    if (playerId !in plugin.profiles) return
    applyDeathGoldPenalty(playerId)
}

private fun applyDeathGoldPenalty(playerId: UUID) {
    val profile = plugin.profiles[playerId] ?: return
    if (profile.currency <= 0) return
    if (locked(playerId)) {
        pendingDeathPenalties.add(playerId)
        whenAvailable(playerId) {
            if (pendingDeathPenalties.remove(playerId)) applyDeathGoldPenalty(playerId)
        }
        return
    }
    var lost = 0L
    profileTransaction(playerId, { current ->
        lost = current.currency
        current.currency = 0
    }) {
        if (lost > 0) {
            Bukkit.getPlayer(playerId)?.let { p ->
                p.sendMessage(plugin.message(p, "ec.death.gold_lost", "amount" to lost))
            }
        }
    }
}
// And in beforeQuit(player): if (pendingDeathPenalties.remove(id)) { profile.currency = 0; plugin.save(profile) }
```

---

### H-4 `[High / Bug]` `AdventureController.teleportArea` and `"RETURN"` consumable fail when distant overworld hub chunks are unloaded

- **Files**:
  - `src/main/kotlin/net/nakaymi/etherealchronicle/paper/AdventureController.kt:104-114` (`teleportArea`), `:314-316`, `:326`, `:443` (`"RETURN"`)
  - `src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt:210-219` (`projectedPosition`)
  - `src/test/kotlin/net/nakaymi/etherealchronicle/qa/QaPlayerMock.kt:16`
- **Lens**: Correctness & Safety (Bug)
- **Why**:
  Now that Overworld is the sole production mode, hub towns (e.g., Lumina Port ↔ Frostpeak Village) are separated by hundreds/thousands of blocks in the natural overworld:
  1. `teleportArea` (`AdventureController.kt:108`) synchronously calls `plugin.projectedPosition(...)`, which immediately returns `null` at `RpgPlugin.kt:219` whenever `!world.isChunkLoaded(chunk.x, chunk.z)`. Unless another player happens to be keeping the destination town's chunk loaded, **airship travel throws `GameException("ec.error.world_unavailable")`** on every attempt.
  2. Worse, `teleportArea` is queued into `after` (`AdventureController.kt:316`) and executed inside `transaction`'s `success` callback (`AdventureController.kt:326`), which runs asynchronously via `plugin.sync { ... }` outside `click()`'s `try/catch`. An unhandled `GameException` inside a Bukkit task logs a stack trace to the server console and sends **no feedback to the player**.
  3. Similarly, the `"RETURN"` consumable (`AdventureController.kt:443`) consumes the scroll inside `transaction` and then executes `plugin.luminaSpawn()?.let(player::teleport)`. Because `luminaSpawn()` requires `SafeStandingSpot.isSafe(location)` (which requires the chunk to be loaded), using a return scroll far from Lumina Port **consumes the item and silently does nothing** instead of falling back to `plugin.requestHubSpawn(player)`.
  4. All QA tests masked this because `QaWorldMock.isChunkLoaded` unconditionally returns `true` (`QaPlayerMock.kt:16`).
- **Suggested Alternative**:
  - In `teleportArea`, resolve horizontal coordinates via `activeWorldProjection.projector.projectHorizontal(...)`, load the destination chunk asynchronously with `plugin.withChunk(world, chunkX, chunkZ)`, then compute `projectedPosition` and teleport (catching `GameException` to message the player cleanly).
  - In `"RETURN"` consumable (`AdventureController.kt:443`), fall back to `plugin.requestHubSpawn(player)` when `plugin.luminaSpawn()` is `null`.

```kotlin
// Before (AdventureController.kt:108-113)
val target = plugin.projectedPosition(destination ?: Position(area.world, x, OverworldAnchorProjector.BASE_SURFACE_Y.toDouble(), z), area.id)
    ?: throw GameException("ec.error.world_unavailable")
val projectedArea = plugin.activeWorldProjection.projector.projectArea(area) ?: throw GameException("ec.error.world_unavailable")
requireGame(projectedArea.contains(target), "ec.error.teleport_destination")
player.teleport(org.bukkit.Location(world, target.x, target.y, target.z, target.yaw, 0f))

// After
val source = destination ?: Position(area.world, x, OverworldAnchorProjector.BASE_SURFACE_Y.toDouble(), z)
val horizontal = plugin.activeWorldProjection.projector.projectHorizontal(source, area.id)
    ?: throw GameException("ec.error.world_unavailable")
val chunk = ChunkCoordinates.fromPosition(horizontal.x, horizontal.z)
plugin.withChunk(world, chunk.x, chunk.z) { error ->
    if (!player.isOnline) return@withChunk
    if (error != null) {
        player.sendMessage(plugin.diagnostic(player, "ec.error.world_unavailable"))
        return@withChunk
    }
    val target = plugin.projectedPosition(source, area.id)
    val projectedArea = plugin.activeWorldProjection.projector.projectArea(area)
    if (target == null || projectedArea == null || !projectedArea.contains(target)) {
        player.sendMessage(plugin.diagnostic(player, "ec.error.world_unavailable"))
        return@withChunk
    }
    player.teleport(org.bukkit.Location(world, target.x, target.y, target.z, target.yaw, 0f))
    player.sendMessage(plugin.message(player, "ec.area.teleported", "name" to plugin.contentText(player, "area", area.id, "name", area.name)))
}

// Before (AdventureController.kt:443)
"RETURN" -> plugin.luminaSpawn()?.let(player::teleport)

// After
"RETURN" -> plugin.luminaSpawn()?.let(player::teleport) ?: plugin.requestHubSpawn(player)
```

---

## Medium Findings

### M-1 `[Med / Bug]` `DungeonController.respawn` checks `SafeStandingSpot.isSafe(destination)` synchronously on an unloaded portal chunk, diverting dungeon deaths away from the dungeon exit

- **Files**:
  - `src/main/kotlin/net/nakaymi/etherealchronicle/paper/DungeonController.kt:1562-1577`, `:1368-1434`
  - `src/main/kotlin/net/nakaymi/etherealchronicle/paper/PaperDomainUnits.kt:103-115`
  - `src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt:1314-1322`
- **Lens**: Correctness & Safety (Bug)
- **Why**:
  With the legacy `ec_world` branch removed, every dungeon death runs `DungeonController.respawn`. The sky arena is located at `(100000, 220, 100000)`, while `destination` (`instance.returns[id]`) is at the town portal (~100,000 blocks away). Unless another player keeps that town's chunk loaded:
  1. `SafeStandingSpot.isSafe(destination)` returns `false` at `PaperDomainUnits.kt:108` (`if (!world.isChunkLoaded(...)) return false`).
  2. `e.respawnLocation` falls back to `resolveForcedSafeFallback(e.player.world)` → `world.spawnLocation` (since `luminaSpawn()` also requires chunk-loaded), stranding dead players at vanilla world spawn instead of the dungeon exit or Lumina Port.
  3. **Disconnect-while-dead edge case**: If a player dies in a dungeon and disconnects on the death screen before clicking Respawn, `quit` detaches them (`memberToSlot.remove(id)`). When they relog while still dead, `recoverOverworld` runs `player.teleport(rescueTarget)` (which fails on a dead player in Paper) → falls back to `plugin.requestHubSpawn(player)` (which immediately aborts on `player.isDead` at `RpgPlugin.kt:302`). When they then click Respawn, `DungeonController.respawn` returns early (`instanceForPlayer == null`), `pendingReturns[id]` is never cleared, and the dungeon slot remains stuck in `PROTECTED` until another relogin.
- **Suggested Alternative**:
  - In `DungeonController.respawn`, set an immediate safe fallback respawn location (`luminaSpawn() ?: resolveForcedSafeFallback(world)`), then schedule an async `plugin.withChunk(destination.world, ...)` post-respawn teleport to `destination` if safe (clearing the return record only once the player arrives at a safe surface).
  - In `RpgPlugin.respawn`, if `!inDungeon && dungeons.hasPendingReturn(e.player.uniqueId)`, trigger `dungeons.recover(e.player)` on the next tick so relog-while-dead players clear `pendingReturns`.

---

### M-2 `[Med / Bug]` Legacy `world.mode` migration is silent & fragile on whitespace/casing, and `TestWorldFixture` coupling to `config.yml` comments leaks state across tests

- **Files**:
  - `src/main/kotlin/net/nakaymi/etherealchronicle/core/OverworldAnchorProjection.kt:6-22`
  - `src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt:92-104`
  - `src/main/resources/config.yml:4-9`
  - `src/test/kotlin/net/nakaymi/etherealchronicle/paper/TestWorldFixtureExtension.kt:8-11`
- **Lens**: Correctness & Safety (Config / Test Isolation)
- **Why**:
  1. **Silent legacy migration**: `WorldMode.fromConfig("dedicated_ec_world")` silently returns `OVERWORLD` with no `logger.warning` in `RpgPlugin.initializeWorldProjection()`. Operators upgrading an existing server with `world.mode: dedicated_ec_world` get no notice that `ec_world` was retired and that 9 towns are now being generated into `"world"`.
  2. **Strict string handling**: `fromConfig` does not `.trim().lowercase()` or treat blank strings as default; `world.mode: ""` or `"OVERWORLD"` throws `IllegalArgumentException` and disables the plugin on startup.
  3. **Contradictory `config.yml` comments & fragile test seam**: `config.yml:5` still says `# 実機プレイで自然生成オーバーワールドへ拠点・ダンジョン入口を統合する場合は overworld へ変更してください。` (contradicting line 4), and lines 7/9 still qualify keys with `mode: overworld 時に`. Commenting out `# mode: overworld` in production `config.yml` solely so `!config.isSet("world.mode")` triggers `TestWorldFixture` (`RpgPlugin.kt:94`) is brittle — any operator or test uncommenting `mode: overworld` silently disables the fixture.
  4. **Fixture state leak**: `TestWorldFixtureExtension` implements only `BeforeAllCallback` and never clears `TestWorldFixture.registryFactory = null` in `AfterAllCallback`. Any test expecting production `PendingOverworldSiteRegistry` / `EngineOverworldBootstrap` without explicitly setting `world.mode` inherits the fixture from an earlier test class.
- **Suggested Alternative**:
  - Log a deprecation warning in `RpgPlugin.initializeWorldProjection()` when raw `world.mode` equals `"dedicated_ec_world"`, and normalize via `value?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }`.
  - Update `config.yml` comments to reflect overworld-only operation.
  - Have `TestWorldFixtureExtension` also implement `AfterAllCallback` (or gate `TestWorldFixture` via an explicit opt-out flag on `TestWorldFixture` rather than relying on `# mode: overworld` being commented out in production config).

```kotlin
// Before (OverworldAnchorProjection.kt:14-20)
fun fromConfig(value: String?): WorldMode {
    val normalized = value ?: OVERWORLD.configValue
    if (normalized == LEGACY_DEDICATED) return OVERWORLD
    return entries.firstOrNull { it.configValue == normalized }
        ?: throw IllegalArgumentException("Unknown world.mode: $normalized")
}

// After
fun fromConfig(value: String?): WorldMode {
    val normalized = value?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: OVERWORLD.configValue
    if (normalized == LEGACY_DEDICATED) return OVERWORLD
    return entries.firstOrNull { it.configValue == normalized }
        ?: throw IllegalArgumentException("Unknown world.mode: $normalized")
}
```

---

### M-3 `[Med / Test]` Deleting `WorldSchematicIntegrityTest` removed all validation of the 4 bundled dungeon `.schem` files, while `DungeonRoutingTest` only validates its own seeded floor

- **Files**:
  - Deleted: `src/test/kotlin/net/nakaymi/etherealchronicle/paper/WorldSchematicIntegrityTest.kt`
  - `src/test/kotlin/net/nakaymi/etherealchronicle/qa/DungeonRoutingTest.kt:29-56`
- **Lens**: Correctness & Safety (Test)
- **Why**:
  `WorldSchematicIntegrityTest` previously verified not only the deleted island/bridge schematics, but also that all 4 bundled dungeon schematics (`novice_arena`, `void_sanctum`, `glacial_citadel`, `ember_crucible`) exist in `src/main/resources`, parse via `SchematicReader`, match `55x34x55` dimensions, and contain valid blockstate syntax. After its deletion, `WorldSchematicLayoutTest` checks only string path formatting — no test parses any bundled dungeon `.schem` resource anymore.
  Meanwhile, `DungeonRoutingTest` now injects `seedArenaSchematic(..., "minecraft:calcite")` (`line 35`) and asserts the arena floor is `Material.CALCITE`, which verifies only the test helper's own argument rather than the bundled glacial citadel schematic (and its test XML also logs `Dungeon blocks retained at 99973,210,99973` because `MockBukkit.unmock()` runs before async slot cleanup completes).
- **Suggested Alternative**:
  Restore a focused `DungeonSchematicIntegrityTest` (pure JUnit, no MockBukkit needed) that reads all 4 `WorldSchematicLayout.dungeonPath(...)` resources via `SchematicReader`, asserts `width == 55 && height == 34 && length == 55`, and verifies non-empty palettes / valid blockstate format. In `DungeonRoutingTest`, pump until `slotState(0) == null` (cleanup complete) before `MockBukkit.unmock()`.

---

### M-4 `[Med / Bug / Style]` Stale `dedicated world` / `専用ワールド` user-facing messages, severe logs, `m01` weapon dialogue text, and release doc counts

- **Files**:
  - `src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt:453`
  - `src/main/resources/lang/en_us.yml:261`
  - `src/main/resources/lang/ja_jp.yml:250`
  - `src/main/resources/content/dialogues/vulcan_hub.yml:168`
  - `src/main/resources/lang/content_en_us.yml:709`
  - `docs/RELEASE.md:50`
- **Lens**: Correctness & Safety / Style
- **Why**:
  - `en_us.yml:261` (`"The dedicated world is not ready."`) and `ja_jp.yml:250` (`"専用ワールドの準備ができていません。"`) are displayed directly to players whenever an overworld site is `PENDING` or a dungeon/teleport check fails (`DungeonController.kt:224..612`, `AdventureController.kt:109`). The concept of a "dedicated world" no longer exists.
  - `RpgPlugin.kt:453` logs `logger.severe("Could not prepare the dedicated world; foundation features remain enabled: ...")` in a `catch` block that now wraps **only** `adventure.reloadNpcs()`.
  - `m01_awakening.yml` removed the duplicate starter weapon rewards, yet Vulcan's completion dialogue in `vulcan_hub.yml:168` (`「よし、初期武装と回復薬を受け取れ――」`) and `content_en_us.yml:709` (`"All right, take your starting gear and healing potions."`) still tells players they are receiving starting weapons/gear.
  - `docs/RELEASE.md:50` still claims `425 JUnit/MockBukkit tests PASS (0 failures, 0 errors; 2 pre-existing skips)` even though multiple test classes were deleted and 4 QA tests are currently skipped.
- **Suggested Alternative**:
  Update `ec.error.world_unavailable` to `"The RPG world sites are not ready yet."` / `"冒険拠点の準備がまだ完了していません。"`, change `RpgPlugin.kt:453` to `"Could not reload NPCs during startup; foundation features remain enabled: ..."`, update `m01_done` text in both `vulcan_hub.yml` and `content_en_us.yml` to mention healing potions/supplies only, and refresh `docs/RELEASE.md:50` after fixing H-1/H-2.

---

## Low Findings

### L-1 `[Low / Idiom]` Dead code and leftover symbols from the removed floating-island / procedural-arena systems

- **Files**:
  - `src/main/kotlin/net/nakaymi/etherealchronicle/paper/PaperDomainUnits.kt:187` — `internal data class SchematicPlacement(...)` has **0 references** across `src/main` and `src/test`.
  - `src/main/kotlin/net/nakaymi/etherealchronicle/paper/DungeonController.kt:23` — unused `import org.bukkit.Material`.
  - `src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt:1308` — `@EventHandler fun playerDeath(e: PlayerDeathEvent) { e.drops.removeIf(::isSkill) }` is now redundant with `AdventureController.death` (`keepInventory = true; e.drops.clear()`).
  - `src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt:846` — `reloadAdvancementPack()` still filters `it.name != WorldIds.DEMO` (leftover exclusion of deleted `ec_world`).
  - `src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt:271-274` — `isManagedWorld(world)` is now an identical 1-line alias for `isActiveWorld(world)`, yet controllers mix both (`GatheringController`, `TreasureController`, `FishingController`, `EnemyController`, `SafeZoneSpawnPolicy`).
  - `src/main/kotlin/net/nakaymi/etherealchronicle/paper/PaperContentValidator.kt:42-45` + `RpgPlugin.kt:1366` — still enforces `dungeon.palette` (≥ 3 materials) for the deleted procedural dungeon arena builder.
- **Lens**: Idiom
- **Suggested Alternative**:
  Remove `SchematicPlacement`, unused `Material` import, `RpgPlugin.playerDeath` (or keep only if a non-RPG world opt-out is introduced in L-2), the `!= WorldIds.DEMO` filter, and consolidate `isManagedWorld` into `isActiveWorld`. Keep or relax `dungeonPalette` validation depending on whether `palette` stays in YAML schema.

---

### L-2 `[Low / Bug / Design]` `AdventureController.death` applies globally across all worlds and wipes 100% of gold unconditionally (including dungeon deaths)

- **Files**:
  - `src/main/kotlin/net/nakaymi/etherealchronicle/paper/AdventureController.kt:402-412`
- **Lens**: Correctness & Safety
- **Why**:
  1. Unlike `GatheringController`, `FishingController`, and `EnemyController`, `AdventureController.death` does not check `plugin.isActiveWorld(e.entity.world)`. Dying in the Nether/End (or another multi-world dimension on a Paper server) still triggers `keepInventory = true` and wipes 100% of RPG gold. If intentional for a vanilla-integrated overworld server (where players explore Nether/End), document it explicitly in KDoc; if unintended, gate on `plugin.isActiveWorld` (or normal/nether/end RPG worlds).
  2. Dying inside an RPG dungeon also wipes 100% of gold with no configurable rate or cap — verify whether full 100% wipe on dungeon death is intended (since `tuning` has no death-penalty config key).
- **Suggested Alternative**:
  Clarify and document world scope in KDoc (or gate by managed worlds) and consider sourcing the gold-loss ratio from `tuning` if partial loss is desired later.

---

### L-3 `[Low / Perf]` Linear scans on `PlayerFishEvent` hot path and `FixedSiteRegistry.portalForDungeon`

- **Files**:
  - `src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingController.kt:155-159`
  - `src/test/kotlin/net/nakaymi/etherealchronicle/core/FixedSiteRegistry.kt:34-39`
- **Lens**: Performance & Efficiency
- **Why**:
  - `FishingController.suppressVanilla` runs on every `PlayerFishEvent` (including every vanilla fishing rod cast/reel across the entire overworld now that vanilla fishing is allowed) and linearly scans `plugin.snapshot.world.tackle.values.any { it.item == id && it.kind == TackleKind.ROD }`.
  - `FixedSiteRegistry.portalForDungeon` (`line 39`) linearly scans `portals.firstOrNull { it.dungeonId == dungeonId }` even though `sites` is already indexed in a `Map`; also `dungeonsByArea[area.id]?.first()` (`line 27`) silently drops a second dungeon if two ever share an area.
- **Suggested Alternative**:
  Cache `rodItemIds: Set<String>` per `ContentSnapshot` (or precompute on `WorldContent`) for O(1) lookup in `suppressVanilla`, and index `portalsByDungeonId = portals.associateBy { it.dungeonId }` in `FixedSiteRegistry`.

```kotlin
// Before (FishingController.kt:157)
val customRod = plugin.identify(e.player.inventory.itemInMainHand)?.let { id ->
    plugin.snapshot.world.tackle.values.any { it.item == id && it.kind == TackleKind.ROD }
} == true

// After (using cached snapshot-scoped set rodItemIds: Set<String>)
val customRod = plugin.identify(e.player.inventory.itemInMainHand) in rodItemIds()
```

---

### L-4 `[Low / Idiom]` Residual forwarding wrappers, stale KDoc, and duplicate check in `DungeonController`

- **Files**:
  - `src/main/kotlin/net/nakaymi/etherealchronicle/paper/DungeonController.kt:123`, `:206`, `:887`, `:1359`, `:1531-1536`
- **Lens**: Idiom
- **Why**:
  After deleting the legacy `ec_world` branches, several methods remain thin single-line delegators (`enter` → `enterOverworld` at `206`, `leaveInternal` → `leaveOverworld` at `887`, `recover` → `recoverOverworld` at `1359`), line `123` still has KDoc `"オーバーワールド稼働時のみ..."`, and `teleport()` computes `to.world?.name == plugin.activeWorldProjection.worldName()` twice (`lines 1531` and `1536`).
- **Suggested Alternative**:
  Inline the single-line wrappers or drop the `*Overworld` suffix, update line `123`'s KDoc, and extract `val sameWorld = to.world?.name == plugin.activeWorldProjection.worldName()` once before computing `inside`.

---

## Polish Findings

### P-1 `[Polish / Style]` Repetitive fully-qualified `QaWorldMock` / `WorldIds` references in 18+ test files

- **Files**:
  - `src/test/kotlin/net/nakaymi/etherealchronicle/qa/*.kt` (e.g., `DialogueTeleportTest.kt`, `DungeonCoexistTest.kt`, `EnhanceFlightTest.kt`, `NoviceArenaTest.kt`, etc.) and `src/test/kotlin/net/nakaymi/etherealchronicle/paper/*.kt`
- **Lens**: Style
- **Why**:
  Automated replacement inserted `net.nakaymi.etherealchronicle.qa.QaWorldMock.add(server, net.nakaymi.etherealchronicle.core.WorldIds.DEMO)` inline across 18+ test files — including files already in `package net.nakaymi.etherealchronicle.qa` — reducing readability.
- **Suggested Alternative**:
  Replace with normal imports (`import net.nakaymi.etherealchronicle.core.WorldIds` and `import net.nakaymi.etherealchronicle.qa.QaWorldMock`) and call `QaWorldMock.add(server, WorldIds.DEMO)`.

---

## Verified Safe Paths (No Issues Found)

- **`DungeonController.quit` (`:1553-1560`) & `shutdown` (`:1617-1626`)**: Detaching players without forcing synchronous `leave()` preserves `pendingReturns` so players stranded in sky arenas are safely rescued on next login via `recoverOverworld`, and `close()` properly delegates to `abandonSlotOnShutdown`.
- **`OverworldDungeonLifecycleTest` (`:138-216`)**: Overwrites `plugin.activeWorldProjection` with its own `AsyncChunkWorldMock`-backed `ActiveWorldProjection` and `resolveProjectionSurfaceY`, so its lifecycle/chunk-loading assertions are not compromised by `TestWorldFixture`.
- **`GatheringController` / `FishingController` mode removal**: Removing `isOverworldMode()` branches preserves pending-chunk registration on `ChunkLoadEvent` and allows vanilla fishing rods in the overworld while cancelling custom RPG rods.
