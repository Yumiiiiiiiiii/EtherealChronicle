# TESTING

Owner: Claude-QA (branch `qa/harness`). Status values: **NOT-RUN** (default), PASS, FAIL, BLOCKED. Nothing below may be marked PASS without a dated log/evidence reference.

## Strategy (layers)
1. **Unit tests** (`src/test/kotlin`, JUnit 5 + kotlin.test): all `core.*` logic and `data` loader/validator/migration. No Bukkit. Fixtures in `net.nakaymi.etherealchronicle.qa` (`FakeClock`, `ContentYaml`, `TempDir`, `seededRandom`, `FakePlayers`). Run: `./gradlew test`.
2. **Content validation**: every shipped YAML under `src/main/resources/content` must load and pass the validator (duplicate ids, dangling refs, ranges). Should be a unit test over the real resources plus `ec validate` on a live server.
3. **Server smoke** (`scripts/smoke-test.ps1`): build -> install jar -> boot Paper offline -> wait `Done` -> console commands -> `stop` -> log analysis (plugin load errors, exceptions, plugin never enabled => FAIL).
4. **Interactive / protocol bot** (`scripts/bot`): join, chat, commands. See *Bot feasibility*.
5. **Manual playthrough** by a human for anything needing GUI, combat feel, balance.

## How to run
```
./scripts/smoke-test.ps1                                # build + boot + `ec validate` + stop + analyse
./scripts/smoke-test.ps1 -SkipBuild -Commands 'ec validate','ec reload'
./scripts/dev-server.ps1                                # foreground server, type commands on the console
```
- Paper jar default: `D:\test\mcmmo\dev\server\paper-26.3-257.jar` (`-JarPath` to override); server dir defaults to the jar's folder; Java 25 required; `online-mode=false` is enforced.
- **EULA**: the scripts never write `eula=true`. If not accepted they print instructions and exit 2. A human must accept https://aka.ms/MinecraftEULA by editing `eula.txt`.
- Logs: `scripts/logs/*.log` (gitignored). Exit codes: 0 pass, 1 failure, 2 EULA, 3 jar missing, 4 wrong Java, 10 build failed.

## Bot feasibility (assessed 2026-11-21)
- `scripts/bot` uses `mineflayer@4.39.0` (installs fine, Node 26.12, no credentials; offline mode against localhost only — `bot.js` refuses non-local hosts).
- **Verified 2026-11-21 (BLOCKED)**: with the server running, `node bot.js` autodetect pings the server as 26.3 then throws `No data available for version 26.3`; `--version 26.3` -> `unsupported protocol version: 26.3`; `--version 26.1` -> `ECONNRESET` (server drops the mismatched handshake). No bot join is possible until minecraft-data/minecraft-protocol add 26.3.
- **Known gap**: the newest protocol/data version in `minecraft-data@3.119.0` / `minecraft-protocol` is **26.1**. Target is Paper **26.3**. Mineflayer will most likely fail to handshake/parse (protocol mismatch) until upstream adds 26.3. Not yet verified live because the server could not be started (EULA not accepted by a human). Run `node bot.js --version 26.1` against the server once it is up; if it connects, record it here, otherwise treat bot-driven items below as BLOCKED and cover them via console commands, plugin-side debug commands, and manual play.
- Fallbacks: add an admin/debug command set in the plugin (e.g. `/ec debug simulate <player> ...`) so flows can be driven from console without a client; unit-test the pure core for the logic.
- **GUI / computer-use**: the QA agent has shell, file, an in-app browser (web only) and Chrome devtools tools. No Minecraft client is installed/driven, and no desktop computer-use tools were exposed in this session. Visual/GUI checks (inventory menus, ActionBar, boss bars) are therefore manual-only.

## Real Player paths without a client: MockBukkit (added 2026-11-21, Task 3)
- **Bot options re-checked**: npm `minecraft-data@3.119.0` / `minecraft-protocol@1.68.0` / `mineflayer@4.39.0` are the latest dist-tags; none has 26.3 data (max 26.1). Still BLOCKED.
- **MockBukkit works for 26.3**: `org.mockbukkit.mockbukkit:mockbukkit-v26.3:4.123.0` (Maven Central). Requirements discovered:
  1. test classpath must use the Paper API in the jar manifest (`Paper-Version: 26.3.build.200-stable`), not build 257, else `InternalDataLoadException ... minecraft:world_clock`;
  2. MockBukkit subclasses the plugin class, so `RpgPlugin` must be `open` (Kotlin classes are final) — a one-word change in src/main that Codex should adopt.
- **Scenario** `PlayerFlowSmokeTest.scriptedPlayerScenario` (writes `build/qa-steps.txt`): join -> class GUI click -> starter kit -> spawn mob -> swap-hand -> cast -> kill -> quit -> rejoin. Result 2026-11-21:

| Step | Result |
|---|---|
| 0 plugin enabled (async startup) | PASS |
| 1 join -> class menu opens | PASS |
| 2 GUI click mage -> starter weapon in hotbar + skill star in slot 9 | PASS (only when all 4 armor slots are non-empty, see BUG-1) |
| 3 health scale 20 applied (max HP 100) | PASS |
| 4 `/ec spawn ec:forest_slime` | PASS |
| 5 swap-hand cycles skill (`選択: 星火 2`) | PASS |
| 6 right-click cast | BLOCKED(mock): `World.rayTraceEntities` unimplemented in MockBukkit |
| 7 kill -> reward via EntityDeathEvent | BLOCKED(mock): `getKiller()` is null for mock damage, so the reward path is not reached |
| 8 quit -> rejoin | PASS: profile file saved with `ec:mage`, no class menu on rejoin (vanilla inventory is not persisted by the mock) |

- **BUG-1 (found)**: with empty armor slots `RpgPlugin.stats` throws `NullPointerException: component2(...) must not be null` (RpgPlugin.kt:426, destructuring `(slot, stack)` of `Pair` built from `inventory.helmet` etc.). Real Paper also returns null for empty slots, so every class-select / join / 1 Hz `tick()` for a player without full armor is expected to fail. Repro: `@Disabled` test `emptyArmorSlotsDoNotCrashClassSelection` (enable once fixed). Not yet confirmed on a real client.
- **BUG-2 (found live)**: `ec validate` sent right after `Done` replied `検証失敗: lateinit property content has not been initialized` (async startup race; the command executor is usable before content is ready).
- A Java client **is installed** on this PC (`%APPDATA%\.minecraft`, versions `26.3`, `26.4-snapshot-2`, `1.12.2`; Microsoft Store launcher). Nothing was launched, no account data read. Driving it would need a human-supplied login and no GUI automation tools are available to the QA agent; a human can join `127.0.0.1:25565` (offline mode) while `smoke-test.ps1 -HoldSeconds N` or `dev-server.ps1` runs.

## Checklist
Each item: scope, how to verify, status. Environment for all server items: Paper 26.3-257, offline mode.

| # | Item | Verify by | Status |
|---|---|---|---|
| 0 | Unit tests (`./gradlew test`) | CI-style run | PASS (2026-11-21, codex/foundation b7f13e3 merged): `./gradlew test` = 21 tests, 0 failures (17 Codex + 4 QA fixtures). Real-Paper behavior covered below only where stated |
| 1 | Server start | smoke-test reaches `Done`, no exceptions | PASS: Paper 26.3-257 reaches `Done (6.9s)` offline (smoke-20261121-120318.log) |
| 2 | Plugin load/enable | log has `Enabling EtherealChronicle`, no load errors; `/plugins` green | PASS: `Enabling EtherealChronicle v0.1.0`, `冒険システムの準備が完了しました。`, no WARN/ERROR from plugin; default content copied to `plugins/EtherealChronicle/content` |
| 3 | `ec validate` / `ec reload` | console output reports 0 errors; reload atomic (bad YAML keeps old content) | PASS: `ec validate` -> `コンテンツ検証成功。`; `ec reload` -> `コンテンツを再読み込みしました。`. Atomic-keep-old-on-bad-YAML NOT-RUN live (unit-tested by Codex only) |
| 4 | First join | new UUID gets profile file in `playerdata/`, welcome text (JP), spawn in Lumina | PARTIAL: MockBukkit join -> profile loaded -> class menu opens (step 1). Real client NOT-RUN |
| 5 | Class select | choose each of 3 classes; stats/resource set; cannot re-pick without reset | PARTIAL: simulated `sim class qa ec:mage` -> resource 100, skills ranked 1, tutorial quest assigned; re-select rejected (`職業は既に選択されています`). MockBukkit GUI click -> starter kit PASS (needs BUG-1 workaround) |
| 6 | Tutorial | steps advance in order, skip works, rewards granted once | PARTIAL: simulated kill auto-completes `ec:tutorial` (progress 1/1, completed) and pays 100 XP once; a second `sim quest` is rejected (`依頼の達成条件を満たしていません`). In-game flow NOT-RUN |
| 7 | NPC interaction | dialogue opens, branches, no duplicate spawn after restart | NOT-RUN (no NPCs implemented yet) |
| 8 | Quest | accept -> progress -> complete -> reward once; abandon; prerequisite gating | PARTIAL: only the tutorial quest exists (see 6) |
| 9 | Combat | damage formula, DEF, crit, caps; PvE only in zones; no per-tick entity scans (spark/TPS) | NOT-RUN (needs real mobs/client; damage formula only unit-tested) |
| 10 | Skills | slot 9 / sneak+RClick select, cooldown, resource cost, ActionBar | PARTIAL: simulated cast cost 10 mana (100->90), immediate recast -> `COOLDOWN` with no cost, after restart cast SUCCESS. Slot/ActionBar/GUI NOT-RUN |
| 11 | Level up | XP cap per balance config, stat growth, level cap 20 | PARTIAL: sim kills: forest_slime +25 XP/+2 currency each; tutorial reward took mage to level 2 with remainder 25 XP. Cap-20 / XP-cap live NOT-RUN |
| 12 | Items | generation, rarity/affix budget, lore in JP, drops from tables | NOT-RUN live (drop roll gave {} in 6 sim kills at 10% — consistent, not a proof) |
| 13 | Equip | equip/unequip updates stats, level/class requirements, no stat stacking exploit | NOT-RUN |
| 14 | Shop | buy/sell transactional, insufficient funds, full inventory, no dupe on rapid clicks/disconnect | NOT-RUN (no shop yet) |
| 15 | Party | invite/accept/leave/kick, XP share, disband on leader quit | NOT-RUN (no party yet) |
| 16 | Boss | spawn, phases, loot per participant, reset on wipe, cleanup of tasks/entities | NOT-RUN (no boss yet) |
| 17 | Chapter 1 clear | full chain Lumina -> Ashwood -> mine -> boss -> ending flag set | NOT-RUN (content not present) |
| 18 | Reconnect | quit mid-fight/quest and rejoin: state intact, cooldowns sane, no dupe | PARTIAL: MockBukkit quit -> rejoin restores class and does not reopen menu (step 8). Real client NOT-RUN |
| 19 | Restart persistence | stop server, restart: profile, quests, inventory, currency identical; kill -9 mid-save leaves valid file (tmp+rename) / backup recovers | PASS for simulated profile: save -> stop server -> restart -> `sim load qa` returned identical level/xp/currency/resource/quests/skills; second save kept a valid `.yml` + `.bak`. Real-player persistence and kill -9 mid-save NOT-RUN |
| 20 | Disable/reload cleanliness | `/reload`-less stop: no leaked schedulers, all profiles flushed, no exceptions | PASS (partial): clean `stop` shows `Disabling EtherealChronicle` with no exceptions in 3 runs; leaked-scheduler check NOT-RUN |
| 21 | Performance | 10 simulated players / 100 mobs: tick time within budget | NOT-RUN |

## Regression rules
- Every bug found gets a unit test where it is expressible in `core.*`; otherwise a checklist row/note.
- Reviewer != implementer. Record findings in `docs/AGENT_STATUS.md`.


## Update 2026-11-21 (codex/foundation 743604f merged)
- `./gradlew test`: 29 tests, 0 failures (AdventureServicesTest 6, FoundationTest 17, QA fixtures 4, PlayerFlowSmokeTest 2). BUG-1 (empty armor NPE) is **fixed** (regression test `emptyArmorSlotsDoNotCrashClassSelection` now enabled and passing).
- MockBukkit scenario: all 9 steps PASS, using the new seams `castSkill` (SUCCESS then COOLDOWN) and `rewardKill` (`魔物を討伐！ レベル 2 / 経験値 25`). `/ec spawn` now requires the owned world `ec_world` (test creates a simple world with that name). Real ray-trace/line-of-sight and real `EntityDeathEvent.killer` remain unverified.
- Live Paper smoke (validate / sim join,class,skill,kill,save / reload) PASS.
- **BUG-3 (live, found by smoke)**: `[ERROR] 専用ワールドを準備できませんでした: .\ec_world\.etherealchronicle-owned` on every boot. Paper 26.x stores custom worlds under `world/dimensions/minecraft/ec_world`, so `worldContainer/ec_world` never exists and `Files.writeString(marker)` throws. `DemoWorld.ensure` should use `world.worldFolder` (and create parents) for the marker/built flags; as is, the demo world is created every boot but its marker is never written. NOT-RUN on a real client: `ec world`, party/dungeon/NPC/shop.
- Harness: log analyser now also flags `[hh:mm:ss ERROR]` lines (it previously missed Paper's bracket format).

## Task 4 results (codex/foundation e607836 merged; 2026-11-21)
Totals: `./gradlew test` = **44 tests, 0 failures** (core 6+17, content validation 7, QA: fixtures 4, `Chapter1ChainTest` 7, `PlayerFlowSmokeTest` 2, `AdventurePathsTest` 1, `PerfTickTest` 1).

**Live Paper 26.3-257 (clean plugin data dir):** boot `Done` 5.0 s; plugin ready ~1-2 s after enable (150 content YAML in `content/` = 150 shipped files, `ec validate` -> `コンテンツ検証成功。`); JVM working set ~855-865 MB (`-Xms512M -Xmx2048M`, vanilla world + ec_world); zero ERROR lines; sim join/class(`ec:class_mage`)/skill/kill(`ec:enemy_aether_slime` drops `ec:item_mat_aether_gel`)/save/dump OK; `sim load` after restart returns identical profile (schema 3: quests, flags, transactions, daily_purchases, accessory persisted).
**ec_world**: created under `world/dimensions/minecraft/ec_world` with `.etherealchronicle-owned` + `.etherealchronicle-built` markers and 6 region files; main `world` overworld/nether/end folders untouched; second boot does not rebuild (no errors). BUG-3 is **fixed**. Real client view of the build NOT-RUN.

**Core chain over the real shipped content (`Chapter1ChainTest`)**: class -> m01..m08 for 3 classes x 2 x 2 x 2 branch-flag combinations (24 runs): PASS, every run ends with `ec:flag_ch1_completed`, level <= 17 (cap 20), almost no grinding needed (≤ 1 extra kill). Reward replay (`completeQuest` twice) rejected. Every COLLECT item is obtainable (drop/reward/shop), every objective target exists, every branch flag is settable by a dialogue choice. Trade exploits: replayed txn id rejected, stale plan after double-click rejected, no buy->sell arbitrage in any shop, bound/quest items unsellable, negative/oversized amounts rejected, currency cap and daily limit enforced. Party XP split exact for 1..6 members and totals; party size cap honoured. Boss phases monotonic, enrage never lowers damage, co-op HP scaling. Spawn budget with 20 players x 20 requests per point never exceeds point or global cap (100). Finished-chapter profile survives save/load round trip; torn primary file does not return a wrong/blank profile.

**MockBukkit adventure paths (`AdventurePathsTest`, build/qa-adventure-steps.txt):**
| Step | Result |
|---|---|
| A NPC (Villager tagged `ec:npc_pip`) right-click -> dialogue GUI with 2 choices | PASS (`reloadNpcs()` itself BLOCKED(mock): unimplemented entity flag call; NPC placed by hand) |
| B shop left-click buys exactly 1, currency 1000 -> 972 | PASS |
| C three rapid clicks (left/double/left) | PASS: 1 potion, one debit |
| D number-key/middle/drop/ctrl-drop/swap-offhand/drag | PASS: no purchase, drag cancelled |
| E insufficient funds | PASS: no side effects |
| F respawn | PASS: skill slot restored, class/level kept |
| G quit while a purchase is pending | **FAIL = BUG-5** (build only fails with `-Dqa.strict=true`) |
Still unverified: shift-click *from the player inventory into* the shop (right-click sell path), real death drops, dungeon/party commands, boss fights, live ray-trace, HUD.

**Perf (`PerfTickTest`, mock)**: 20 classed players x 1200 ticks: 5.9 ms total (~0.005 ms/tick) — trivial; real TPS/entity-count over time NOT-RUN (needs clients; spark plugin is present in the server).

### Findings
- **BUG-4 (high, live)**: after updating the plugin on a server that already has the previous content folder, startup fails: `コンテンツの読み込みに失敗: Failed requirement.` -> plugin disables itself, every `/ec` then throws `Cannot execute command ... plugin is disabled`. Cause: `DefaultContentInstaller` never overwrites/removes, so 7 orphan foundation files (`enemies/forest_slime.yml`, `items/{archer,mage,warrior}_starter.yml`, `items/field_ration.yml`, `droptables/starter.yml`, `quests/tutorial.yml`) coexist with chapter-1 content. The error message names no file/id. Works when `plugins/EtherealChronicle` is removed.
- **BUG-2 (residual)**: an `/ec` command sent before the async startup finishes gets no reply at all.
- **BUG-5 (high)**: quitting while a shop/quest transaction is persisting leaves currency debited (and persisted) but the purchased item never delivered (`取引出力の受取前に切断` is only logged). Repro: step G.

## Regression run on bd06830 (2026-11-21)
`./gradlew test` = **50 tests, 0 failures** (step G of `AdventurePathsTest` is now always blocking).
- **BUG-5 FIXED**: quit mid-purchase -> file has currency 972 **and** `pending_rewards` (schema 4); rejoin + `/ec claim` delivers exactly 1 potion; a second claim delivers 0.
- **BUG-2 FIXED (live)**: command right after `Done` replies `冒険システムは起動中です。しばらくお待ちください。`.
- **BUG-4 FIXED (live)**: server data dir copied from the foundation-era install (157 YAMLs): the 7 unchanged legacy files are moved to `retired-content/`, the plugin stays enabled, and a stale server `content/balance/default.yml` (stat_cap 10000) that rejects `boss_void_warden hp=34000` is reported with file + id (`WARN ... void_warden.yml [ec:boss_void_warden]: hp=34000 は 1..10000.0 の範囲外です`), the plugin falls back to bundled content and `/ec validate` keeps working (`検証失敗: <file> [<id>] ...`). Sim join/class/kill OK on the fallback.
- Open observation: older *unmodified* distributed files (e.g. `balance/default.yml`, class YAML from the foundation build) are treated as "server edits" and keep winning, so an upgraded server runs the bundled fallback until an admin fixes/removes them. Consider hash-tracking installed files so unchanged ones auto-update.

## Mailbox / party / bound-item review (codex 3735ab8, MockBukkit; build/qa-mailbox-steps.txt)
- M1 PASS: full inventory -> `/ec claim` keeps `pendingRewards`, nothing lost or dropped; after freeing space it delivers once, a second claim delivers nothing.
- M2 PASS: `rewardKill` with a full inventory still credits currency; drops go to `pendingRewards`; no item entities on the ground.
- P1 PASS: party-member credit queued while that member is mid-transaction, applied exactly once afterwards (currency check; XP was at cap in the test so only currency was asserted).
- B1/B3 PASS: bound item drop and block placement cancelled. B2 PASS: shift-click into a chest, offhand swap, cursor placement and drag cancelled. Caveat: my number-key variant is not a real assertion (MockBukkit hotbar mapping); NOT verified: item frame / armor stand / allay interaction, crafting-grid ingredients, bundle clicks.
- Full suite after merge: see Task totals above; everything green.

## Console simulation (codex eec003f, live Paper; scripts/smoke-test.ps1, `{uuid}` placeholder = last `SIM spawn=` UUID)
Suite after merge: 60 tests, 0 failures.
- PASS: join; class; currency; `event TALK` x4 -> m01 progress 4/4; `complete` -> +90 xp/+60 currency, rewards in virtual bag; second `complete` rejected (`依頼の達成条件を満たしていません。`); `buy return_scroll tx1` (currency 1060 -> 1025); replay tx1 rejected (`処理済みの取引です。`); unknown entry rejected (`商品が見つかりません。`); `sell` returns 10 G; `party invite/accept` -> 2 members; `party split 101` -> 51/50 exact; `spawn` prints UUID + hp (slime 65, void warden 34000); `dialogue ec:dlg_pip_hub` prints text + choices.
- FAIL (reported): `hit <uuid> <dmg>` immediately after `spawn` answers `対象が見つかりません。` for both the slime and the boss, so the virtual-HP seam is unreachable via console; invalid damage (100000, 99999999, -5) and a replayed `sell tx3` / `sell ... 999` answer the opaque `Failed requirement.` (buy replay has a proper message).
- NOT-RUN: choice, give, spawned-enemy kill reward via hit, boss phases via console.

## Console sim re-run (codex d942b22, CLEAN plugin data; 60 tests green)
- Fresh data: shipped numbers visible: slime hp 140, wolf 310, void warden 55000 (the earlier 34000 was stale preserved server content).
- `hit` on the boss: PASS (`hp=45000.0 killed=false phase=phase_awakening`); damage bound 10000 per hit with a Japanese error (0, negative, 1e8 rejected); `despawn` -> subsequent hit `対象が見つかりません。`; `choice open_shop` -> `効果=[OPEN_SHOP:ec:shop_lumina_general]`; `give` 5 potions; `sell` x2 pays 14 G, replay `処理済みの取引です。`, qty 999 `数量は1..64で指定してください。`.
- **FAIL / BUG-6 (high, probably affects real combat)**: `hit` on a slime (`ec:enemy_aether_slime`) throws `Health value (18.57…) must be between 0 and 4.0 (attribute base value: 4.0)`. `EnemyController` sets the Bukkit health from the virtual HP ratio (`20*hp/max`), but Slime size resets `MAX_HEALTH` to size² (4.0) after the spawn code sets it to 20. Every hit on this enemy therefore throws; the slime can never die through the virtual-HP path.
- Open nit: `give ... 999` answers `Failed requirement.`; `give ec:item_nope` answers raw English `Unknown content: ec:item_nope`.
- Operational note: commands sent while the plugin is still starting on a clean data dir get the `起動中` reply; add a warm-up `ec validate` before scripted sims.

## Console sim re-run (codex fa4f068, clean data; 63 tests green) — BUG-6 FIXED
- Slime: spawn hp=140; hits 10 -> 130, 50 -> 80, 1000 (capped lethal) -> `hp=0.0 killed=true`, drops `{ec:item_mat_aether_gel=1}`, fake profile +18 xp/+6 currency exactly once; a further hit -> `対象が見つかりません。`.
- Void warden: 55000 -> 45000 (`phase_awakening`) -> 35000/25000 (`phase_void_resonance`) -> 15000/5000 (`phase_cataclysm_judgment`) -> lethal: `killed=true`, loot `{item_q_void_remnant, item_mat_void_core_essence, item_mat_starshard_fragment x6, item_con_greater_hp_potion x2}`, fake profile level 1 -> 6, currency 756; repeat hits after death rejected.
- `give ... 999` -> `数量は1..64で指定してください。`; unknown item -> `アイテムが見つかりません: ec:item_nope` (both Japanese now).
- Observation (balance, for lead): one boss kill takes a level-1 profile to level 6 (xp 109 left over); fine for a sim, but check that real-player boss XP isn't reachable at level 1 (dungeon level gate).

## master b178a0f live re-verification (clean data; 70 tests green incl. new FlagReviveReconnectTest)
- **boot/validate/reload (live, clean data)**: `Done`, ready, `ec validate` / `ec reload` x2 all OK, zero ERROR.
- **Pity (live sim, `ec:enemy_crystal_bat`, 2% + `pity_after: 50`)**: 130 kills -> rare drop `crystal_recurve` at gaps 50, 50, 7 (guarantee fires exactly at 50 misses), counter 23 saved (`drop_pity` in simulation YAML). After a server restart, `sim load` + kill: with the saved counter edited to 49 the very next kill drops the rare and the one after does not -> counter persisted/loaded and reset correctly.
- **Pity with parties (MockBukkit, `rewardKill`)**: 2-member party, 12 kills with no rare drop -> pity a=6, b=6, sum=12 (one roll per kill, loot recipient alternates; no double accounting). Persistence of party members' pity not separately checked on disk.
- **`/ec flag` (MockBukkit, op player)**: non-op rejected; set/clear works on `ec:flag_sanctum_unlocked`; malformed id, undeclared flag and bad operation rejected without changing the profile.
- **REVIVE accessory (MockBukkit)**: non-lethal hit never triggers it; a lethal hit is intercepted (hp restored, capped by max HP); a second lethal hit within the cooldown is not; no effect if the ring is not in the inventory. (Caveat: inside the Lumina safe zone every damage event is cancelled by the safe-zone handler, so revive tests must run outside chapter areas. Real client NOT-RUN.)
- **Content upgrade (live)**: manifest present + file equals the recorded old hash -> `未編集の旧配布コンテンツを更新しました (旧版はcontent-historyへ保存)` (file == bundled); edited file preserved with `サーバー編集を優先します`; legacy dir without manifest + unchanged foundation file -> updated and manifest recreated. A hand-copied old file that is not recorded in the manifest counts as an edit (expected).
- **Reconnect races (MockBukkit)**: quit then immediate rejoin without letting the scheduler run -> latest currency/level preserved, file contains latest value, player not kicked, one profile entry; three rapid quit/rejoin cycles keep the class and never reopen the class menu.
- Harness: `smoke-test.ps1` now waits for the plugin-ready log line before sending commands and supports `sleep:N` pseudo-commands.

## Chapter 2 live verification (master with Ch2 + Task4 engines; 90 tests green incl. new Chapter2ChainTest, DungeonCoexistTest, IceFreezeTest)
- **ec_world migration (live)**: existing v1 world (markers `built=v1`, 6 regions): boot logs `ec_world 地形を v1 から v2 に移行しました。第1章の地形は変更していません。`, marker becomes `built=v2`, owner marker kept, region count unchanged; a second boot logs no migration and no errors (idempotent). Server's vanilla dimensions are only touched by normal saves. Fresh world (ec_world folder deleted): created + built as v2, markers written, 6 regions, second boot clean. Cosmetic: the fresh creation also prints the "v1 から v2 に移行" line.
- **Boot/validate/reload** on clean data with both world states: OK, zero ERROR.
- **Chapter 1 + Chapter 2 quest chain, live console sim**: 4 profiles (4A/4B x 5A/5B), 608 generated commands (level, branch flags, accept, objective events/kills/give, complete) -> 0 failures; all 4 end with `ec:flag_ch2_completed`, level 20, identical currency 18640. Pure-core `Chapter2ChainTest` additionally asserts branch rewards: 4A -> glacier ward pendant only, 4B -> frostbreaker signet only; 5A -> 2 core essence, 5B -> 1 essence + exactly 10 shards; every Ch2 COLLECT item obtainable.
- **Ch2 enemies/bosses (live sim spawn -> virtual hit -> lethal -> reward once)**: frost wolf 3200, glacier wraith 3600, cryo cultist 3900, glacial knight 8200, Frost Colossus 28000 (phases cryo_overdrive -> absolute_chill), Cryo Archon 76000 (frost_judgment -> glacial_resonance -> absolute_zero): each lethal hit prints the loot once, further hits `対象が見つかりません。`.
- **Shops (live sim, frostpeak_outfitter)**: price 260 -> 234 with `ec:flag_ch2_choice4_stabilize` (10%); flag-gated and token entries: shards consumed exactly (16), insufficient tokens rejected, replayed tx rejected; revive_feather daily limit 5 enforced (6th rejected); buyback sells at 30%.
- **Dungeons (MockBukkit)**: citadel and sanctum both refused without their unlock flags (0 instances); with both flags two players hold the sanctum and the citadel simultaneously; leave cleans each; re-entering works; unknown dungeon id rejected. `/ec flag` itself verified earlier (FlagRevive test). Real dungeon terrain/boss fight NOT-RUN (needs client).
- **Airship (MockBukkit, dialogue GUI)**: choice hidden without `ec:flag_ch2_started`; with it Freya -> (480,65,0), Orphe -> (18,65,-12), clearing the flag hides both choices; sim `choice` before the flag -> `この選択肢は選べません。`.
- **ICE/FREEZE**: Cryo Archon's FREEZE special has telegraph>0 and element ICE; `applySpecialEffect` sets freezeTicks >= duration plus Slowness >= II; a non-FREEZE special does not freeze. Visual telegraph (particles/messages) NOT-RUN.
- Findings (minor): shop entries whose `condition` is unmet answer the opaque `Failed requirement.`; fresh-world creation logs a misleading migration message.

## Task 5 verification (master with schema 9; 115 tests, 0 skipped, 0 failures)
- **The 2 skipped tests were unjustified and are now enabled**: `EnhancementGuiTest` skipped because MockBukkit's `PlayerMock.saveData()` is unimplemented while production code calls it after inventory transactions -> new `QaPlayerMock` (no-op `saveData`); `SupportSkillTest` skipped because `ChunkMock.addPluginChunkTicket` is unimplemented and the test passed `pinForSimulation=true` (console-sim only) -> now `false`. Both pass.
- **Schema 5 -> 9 (live sim + MockBukkit real join)**: a hand-written schema-5 file (class, level 12, 345 xp, 7777 currency, quest progress, flags, pending rewards, drop pity 17) loads, keeps every value, defaults the new fields (`challenge_records`, `selected_title`, `bestiary`, `discovered_items`), and is rewritten as `schema_version: 9` on save. Via a real join the class menu does not reappear and the pending mailbox is kept/delivered.
- **New classes (live sim)**: paladin/cleric/assassin select + level + skill cast + kill + save OK (paladin rage starts at 0 so its first casts answer RESOURCE as designed). Party heal/defense buff and paladin taunt: `SupportSkillTest` (healed party member only, buff limited to party radius, mob taunt target pinned).
- **Side/daily/weekly quests**: 34 non-main quests; every one is completable in some class x branch variant (fixpoint over prerequisites, 6 classes x 2 variants, 32/34 per variant, union 34/34). DAILY and WEEKLY quests are unavailable one second before the reset boundary and available exactly at it (tuning: reset zone/hour/weekly day from balance); the period key changes once per day.
- **Challenge dungeon / `/ec title` / `/ec codex`**: covered by Codex's `ChallengeDungeonTest` (3 waves, rank, personal best), `TitleSystemTest`, `CodexSystemTest`/`CodexGuiTest` (milestone raised once, bestiary survives save/migration); I did not duplicate them and did not run them against a real client.
- **`/ec enhance` (MockBukkit, `EnhanceStressTest`)**: +1..+5 each consume exactly 1 whetstone and 100*n currency, attack never decreases, +6 refused without consumption, PDC level survives `serializeAsBytes` round trip (restart persistence of the item), three back-to-back activations of one menu enhance once. **BUG-7 (open)**: quitting right after clicking enhance (before the async persist completes) leaves currency debited and the whetstone consumed but the weapon never becomes +1 (step E6; build fails only with `-Dqa.strict=true`). Same window if the held item is swapped for a different item (`強化は保留されました`, nothing refunded).

## BUG-7 re-verification (master f068a63; 116 tests, 0 skipped, 0 failures, no `qa.strict` gating left)
Fix design: the upgrade (PDC `enhancement_level` + transaction token) is applied to the item in the same step as the debit; if the tokenised item has left the inventory at completion the costs are refunded once (currency + whetstone, or whetstone to `pending_rewards` when the bag is full).
- Live Paper (clean data): boot, `ec validate`, `ec reload`, sim join/class/save all OK, zero ERROR. The enhance paths themselves need a real `Player` and were exercised in MockBukkit (`EnhanceStressTest` E1-E7 incl. Codex's E6/E7, new `EnhanceFlightTest` F1-F7, report build/qa-enhance-flight.txt).
- E6 quit right after the click: saved currency debited (4900) AND item upgraded (applied=true). E7 plugin disable mid-flight: `gold=4900 upgraded=true` (flushed consistently). E5 swapped item: both costs refunded, replacement not upgraded.
- F1 no whetstone / F2 not enough gold: rejected, nothing consumed. F3 five GUI click types in a row on the anvil: one upgrade, one whetstone, one debit. F4 result item removed before completion: gold and whetstone refunded exactly once (second pump changes nothing), no enhanced item left. F6 quit then rejoin: one debit, currency unchanged by the rejoin. F7 item serialize round trip keeps the level and the next enhancement continues to +2.
- BLOCKED(mock): `/ec reload` right after the click (reload path hits an unimplemented MockBukkit call). Not run on a real client.

## Real client run (offline, no account) — 2026-11-21
First real-client evidence: the official 26.3 client was downloaded from Mojang's official URLs (SHA-1 verified), launched offline as `QaBot`, and joined the loopback dev server. Join -> class menu -> class pick -> HUD, hearts, skill slot -> right-click cast at a Thorn Wolf (resource and cooldown update) -> death outside a safe zone (drops, bound item kept, skill slot) -> respawn behave as designed. See `docs/MANUAL_QA_RESULT.md` (3 findings: spawn/respawn world, weapon lost on death, silent duplicate `ec spawn`) and `docs/CLIENT_OFFLINE.md` for the procedure and safety rules. Client, server and helper processes were stopped afterwards and the `dev/server` configuration restored.

## Task 8 (fishing / gathering) verification — 2026-11-21 (master with schema 10, DemoWorld v3)
Suite after merge and the new QA tests: 146 tests, 0 skipped, 0 failures. New QA tests: `LifeQuestResetTest`, `GatheringLifeTest` (+ `OwnedWorldMock`), `SchemaNineToTenTest`, `FishingInvariantsTest`.
- **Live Paper**: boot clean; `ec_world` v2 -> v3 migration logs `ec_world の釣り場を追加しました (v2→v3)。既存ブロックは変更していません。`, marker becomes `v3`; no ERROR lines. WARN on boot: `称号進捗データパックを登録できませんでした ... データパックが検出されません: file/etherealchronicle_advancements` (titles still work through flags; not re-checked on a second boot).
- **Schema 9 -> 10 (MockBukkit real join)**: progress (class, level, currency, bestiary, discovered items) kept, life skills default to level 1, fish/gathering records empty, file rewritten with schema >= 10.
- **Quests**: all 42 non-main quests (incl. the 8 `life_*`) are completable in some class/branch variant; every scheduled (DAILY/WEEKLY) quest incl. life dailies is unavailable 1 s before its boundary and available exactly at it.
- **Gathering (MockBukkit)**: node block and ground below are protected from vanilla breaking, a far block is not; wrong/no tool starts no channel; correct tool completes a channel (1-2 ash leaves) and puts the node on cooldown for that player; another player can gather the same node (per-player respawn); quit in the middle of a channel removes the session with no reward; `close()` (disable) clears open channels. Note: in MockBukkit the controller's repeating task did not complete the channel by itself (a direct `tick()` call did) — not reproduced on the real server, treated as a mock artefact until a real-client gather is seen to complete.
- **Fishing / economy (pure, shipped content)**: 100 simultaneous anglers x 600 ticks of the state machine cost 24 ms total (0.04 ms per 10 Hz step); `FishingController.tick` and `GatheringController.tick` iterate only their own sessions (no world/entity scans; chunk-load handlers scan one chunk). Size/crown records keep the personal best and crown across later smaller catches and survive `PlayerStore` save/load. No buy->sell arbitrage on any shop. Macro detector: flags an identical-rhythm reeler, not a varied human; **gap**: `bite()` clears the previous pull, so a bot that pulls once per bite with a constant reaction time is never flagged (only the fatigue window limits it).
- **Real client (offline QaFresh)**: fix 1 (Lumina start) PASS — a brand-new player joined and is shown on the Lumina platform with NPC name tags and the new pier water (server log still prints the vanilla overworld spawn before the teleport); fix 2 (starter weapon) PASS — after death and respawn the weapon is back in slot 1 and the player respawns in Lumina; fix 3 (duplicate `ec spawn`) PASS — `同じ魔物が既に存在するか、出現上限に達しています。` Gathering at the Lumina driftwood node: node visual (oak log + `[採取]` label) renders, but see findings.
- **Findings (sent to Codex)**: (1) message spam `戦闘により採取を中断しました。` appears repeatedly (several per second) for a player who is not gathering, and death prints `倒れたため採取を中断しました。` + `倒れたため釣りを中断しました。` even without sessions — `interrupt/cancel` send the message unconditionally; it also floods the chat so real gather feedback is unreadable. (2) The node visual is a `BlockDisplay` spawned at (x+0.5, y, z+0.5) with no translation, so its corner sits there and the visible block is shifted half a block (+x,+z) from the clickable ground tile (x, y-1, z); a right-click on the exact node tile with a correct hatchet showed no boss bar in the client (response may have been hidden by the spam). (3) macro-detector gap above. NOT-RUN on a real client: fishing minigame, boss bar timing, reward pickup.

## Task 9 re-check (master 71f3eab, 149 tests, 0 skipped, 0 failures; offline real client QaFresh, 2026-11-21)
- **Dev server / EULA**: the dev server used by all QA runs is `D:\test\mcmmo\dev\server` in the main checkout (git-ignored, so it does not exist inside agent worktrees such as `codex-foundation`); its `eula.txt` already contains `eula=true` (accepted by the user earlier). Start it with `scripts/client/server-host.ps1` or `scripts/dev-server.ps1 -JarPath D:\test\mcmmo\dev\server\paper-26.3-257.jar`. Nothing was written to `eula.txt` by QA.
- **(1) Chat spam**: PASS on the real client — after joining, standing in Lumina and at the node for over a minute no `戦闘により採取を中断しました。` lines appeared (previously several per second). Death path not re-run.
- **(2) Node visual**: PASS — the oak-log BlockDisplay now sits exactly on the highlighted clickable tile (previously half a block off). Right-clicks with `ec:item_tool_hatchet_iron` finished with a new item stack of 6 in the inventory and the node visual disappearing for the gatherer (per-player depletion), so the reward path works. NOT confirmed visually: the boss bar / `採取中 xx%` action bar — no boss bar was visible in any frame taken 0.35-3 s after the click (the class HUD rewrites the action bar every second and may be hiding the gather action bar). Please check that the channel actually shows progress.
- **(3) Fishing** (`/ec debug fish start`, cast -> bite -> reel, size/crown record) and **(5) anti-macro** on the real client: NOT-RUN (out of budget this session).
- **(4) Title advancement data pack — BUG**: the WARN `称号進捗データパックを登録できませんでした ... データパックが検出されません: file/etherealchronicle_advancements` is still printed on every boot, including repeated boots. Cause: `AdvancementDatapack.install(world.worldFolder ...)` writes to `world/dimensions/minecraft/overworld/datapacks/etherealchronicle_advancements` (Paper 26.x per-dimension world folder), but the server only scans `<level-name>/datapacks` (here `D:\test\mcmmo\dev\server\world\datapacks`), so `getPack(...)` never finds it. Progress tab/toast therefore NOT shown (not verifiable until fixed).
- Observation: vanilla hostile mobs (zombies, creepers, skeleton) spawn at night around Lumina in `ec_world`; consider `difficulty`/spawn rules for the safe hub.
- Processes stopped; `server.properties` / `ops.json` restored; server log had 0 ERROR lines.

## Task 10 real-client check (master 7535d36, 152 tests, 0 skipped, 0 failures; offline QaFresh, 2026-11-21)
- **(1a) Advancement data pack warning**: PASS — two consecutive boots print no `称号進捗データパック…` WARN; the pack now lives in `world/datapacks/etherealchronicle_advancements` (the folder the server scans). 0 ERROR lines.
- **(1b) Progress tab / toast / `/ec title`**: NOT-RUN (out of budget).
- **(2) Fishing, debug session** (`/ec debug fish start QaFresh`): PASS for presentation — the client shows the boss bar `格闘中 [安定] 進捗 0%` at the top and the fishing action bar (tension / progress / controls) in place of the class HUD; the server log prints `デバッグ釣りを開始しました: ec:fish_blizzard_shark (左クリックでいなし、右クリックで引く)。`. NOT completed: a full successful/failed reel with reward and size/crown record. Each attempt ended before my inputs landed (inputs then produced the normal cast messages `水面を狙って竿を投げてください。` / `次の釣りまで少しお待ちください。`); the cause below is the most likely one. `fish_records` stayed empty. Normal cast -> bite -> reel flow and the anti-macro debug path: NOT-RUN.
- **(3) Gathering boss bar / HUD coexistence**: NOT-RUN this session (the fishing action bar did replace the HUD correctly, which suggests the same mechanism works for gathering).
- **(4) Vanilla hostile mobs at night — FAIL**: with `difficulty normal` and `time set night`, `kill @e[type=zombie|skeleton|creeper|spider]` within 80 blocks of Lumina removed 10/8/10/13 entities at first and 11/15/11/12 more only 75 s later; within 24 blocks of the hub centre 5 zombies and 2 skeletons were present after another 60 s. After `difficulty peaceful` a zombie and a spider were still standing on the fishing pier next to the player. RPG enemy spawning (`ec spawn`) was not retested. These mobs interrupt fishing (combat cancels the session).
- Processes stopped; `server.properties` / `ops.json` restored; server log had no ERROR lines.

## Task 10 follow-up: hostile-mob fix + debug fishing (codex d94ca75, offline QaFresh, 2026-11-21)
- **Hostile mobs**: PASS. Boot logs `ec_world のバニラ敵対モブ自然湧きを停止しました (allowMonsters=false)。`. With `difficulty normal` and `time set night` and the player in Lumina, a kill sweep after 75 s found no zombie/skeleton/spider/enderman/witch within 80 blocks and one creeper (likely a leftover); a second sweep a minute later found none. Previously ~11/15/11/12 respawned in the same time.
- **RPG enemy spawning**: PASS — `ec spawn ec:enemy_thorn_wolf` still works (`魔物を召喚しました。`) and the duplicate gives `同じ魔物が既に存在するか、出現上限に達しています。`.
- **Debug fishing UX (real client)**: boss bar `格闘中 [安定] 進捗 6%` and action bar `張力 18% | 進捗 6% 右:引く / 左:いなす` update live and replace the class HUD; a failed reel shows the title `逃げられた…` with subtitle `糸が切れてしまった` (line snapped by spamming right-click every 200 ms); a gentler rhythm (right-click every ~420 ms plus a brace) reached 19% in ~10 s with tension 6%. A full success with reward and size/crown record was NOT achieved: with scripted input timing the sessions ended in the line-snap failure or timed out (`fish_records` stayed empty). Normal cast -> bite -> reel, `/ec title` + advancement toast, gather boss bar, anti-macro debug: NOT-RUN.
- Processes stopped; `server.properties` / `ops.json` restored; server log had no ERROR lines.

## Debug fishing re-check (codex 9631f8f, offline QaFresh, 2026-11-21)
- New UX confirmed on the real client: the debug session now starts with the lowest-difficulty species, the action bar gives a state hint (`張力 0% | 進捗 0% 右クリックで引く`), the timeout produces the chat line `制限時間を過ぎたため、魚を逃しました。`, and `ec debug fish status` reports `行動=…` and elapsed/limit seconds.
- A full successful reel with reward and size/crown record was still NOT achieved by scripted input: (a) a status-polling bot (about 1 s per pull because of focus and console round trips) timed out at 45 s; (b) a faster bot (~300 ms pulls) saw only CALM and the session ended after ~6 s without a visible title/record (`fish_records` stayed empty) — most likely a line snap from too fast pulls, not confirmed. A human needs to verify the win path (pull about every 350-420 ms, rest on THRASH, brace on DIVE). Normal cast -> bite -> reel, `/ec title` + toast, gather boss bar, anti-macro debug: NOT-RUN.
- Processes stopped; `server.properties` / `ops.json` restored; no ERROR lines.
