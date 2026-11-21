# Agent Status
| Agent | Model | Task | State |
|---|---|---|---|
| Claude | sonnet 5.5 | lead | active |
| Claude-QA | sonnet 5.5 | QA harness (branch qa/harness) | Task 1 done: scripts/{lib,dev-server,smoke-test}.ps1, scripts/bot (mineflayer, 26.1 max), docs/TESTING.md, qa fixtures (4 unit tests pass). Verified: PS syntax, gradle build/test, EULA-block path. NOT verified: actual server boot (EULA not accepted by human), bot join, log-analysis against real output. |

## Codex-Impl — Task 1 foundation
- Branch: codex/foundation. Implemented typed content registry/validation/migration hooks and atomic reload, generated default-content index/non-overwriting install, core combat/XP/levels/classes/resource/skills/items/drops, schema-2 async player persistence, join/quit handling, class GUI/starter/tutorial flow, HUD, F/right-click casting, explicit RPG mob spawning/combat/drops, equipment stats and permission-gated admin/simulation commands.
- Verification: `./gradlew.bat test` and `./gradlew.bat build` passed; 17 JUnit tests cover shipped content/index, schema errors/type refs, migration hooks, damage/caps, XP overflow, skill cost/cooldown/ranks, deterministic drops, quest counts/reward-once, resource mechanics/stat caps, no-overwrite defaults and save/load/backup/recovery/migration/precision/reconnect ordering. Paper API compilation passed.
- Independent static review: Claude-QA's 18 findings addressed through code changes or explicit behavior decisions in DECISIONS/BALANCE. Live Paper smoke is blocked: EULA is not accepted and no Minecraft client is available. No server/client behavior is claimed tested.
- Remaining risks: starter at-most-once delivery can require explicit admin recovery after disconnect/crash in the durable-grant window; Paper damage modifiers are deprecated and need server verification. GUI/slot protection, health scaling, damage event interactions and shutdown scheduling need live QA. No full Chapter 1 content/shops/dialogue/NPC/party/dungeon/boss engine yet; those are Task 2.

## Claude-QA — Task 2 (live Paper smoke of codex/foundation b7f13e3)
- Verified on real Paper 26.3-257 (offline): boot, plugin enable, `ec validate`/`reload`, sim join/class/skill(cooldown+cost)/kill/level-up/quest-once/save, restart + `sim load` identical, clean shutdown. 21 unit tests pass. Evidence: scripts/logs (local, gitignored) excerpts in docs/TESTING.md.
- BLOCKED: bot join (mineflayer lacks 26.3 protocol data; 26.1 -> ECONNRESET). Real-player paths (GUI, spawn/combat events, HP scale, slot protection, starter grant) remain NOT-RUN.
- Harness fixes: smoke-test sink crash (pwsh scriptblock handler), UTF-8 console, false-positive on normal "Disabling", `-HoldSeconds`.

## Claude-QA — Task 3 (MockBukkit player-path harness)
- Bot still blocked (no 26.3 data upstream). MockBukkit 26.3 (4.123.0, paper-api build.200 on test classpath) works; PlayerFlowSmokeTest runs join -> GUI -> starter -> spawn -> swap -> quit -> rejoin. Cast/kill-reward steps BLOCKED(mock: rayTraceEntities / getKiller). Needs `open class RpgPlugin` + test deps in build.gradle.kts (done on qa/harness).
- Bugs: BUG-1 empty-armor NPE in RpgPlugin.stats; BUG-2 `ec validate` racing async startup. Java client present on PC (26.3), not launched.

## Claude-QA — Task 4
- e607836 merged; 44 tests pass; live Paper clean-boot smoke PASS; ec_world verified (BUG-3 fixed); chapter-1 chain 24 combos PASS; trade exploit suite PASS; MockBukkit NPC/shop paths PASS except BUG-5; BUG-4 (stale content dir disables plugin) and BUG-2 residual open. Perf only mock-level. See docs/TESTING.md.
