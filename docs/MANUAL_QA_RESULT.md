# MANUAL_QA_RESULT — real client run (offline QaBot, Paper 26.3-257 + master plugin 0.1.0), 2026-11-21

Method: `docs/CLIENT_OFFLINE.md`. Screenshots are in `D:\test\mcmmo-client-qa\shots` (not in the repo). Only a subset of MANUAL_QA.md A-I was driven (time-boxed); everything else is NOT-RUN.

| # | Item | Result | Evidence |
|---|---|---|---|
| A1 | Join -> class menu | PASS | 6 classes shown in `職業を選んでください` (bow, sword, orb, rod, shield, axe); Japanese renders correctly |
| A2 | Esc on the menu | PASS | the menu is shown again immediately |
| A3 | Pick a class | PASS | starter weapon in slot 1, `スキルスロット` (nether star) in slot 9, message `ようこそ！受付嬢セリアに話しかけて冒険を始めましょう。` |
| B1 | HUD | PASS | action bar `HP 105/105  集中 100/100  影縫いの双閃 CD 0.0秒  仲間 1人`, updating, no garbled text |
| B2 | Hearts | PASS | 10 hearts at 105 max HP (health scale applied) |
| B3 | Cost and CD after a cast | PASS | resource 100 -> 91, `CD 2.072秒` counting down, back to 0.0 |
| C1 | Right-click cast at a mob | PASS | Thorn Wolf `イバラオオカミ` (name tag visible) hit by the skill; HUD updated |
| C2-C8 | no-target / wall / range / cooldown messages, swap-hand, bow class, kill reward | NOT-RUN | |
| D1 | Death outside a safe zone | PASS | normal items (starter weapon, 3 potions) dropped; bound `ec:item_mat_starshard_fragment` kept; skill slot not dropped |
| D2 | Respawn | PASS | HP full, skill slot restored, bound item kept |
| D-extra | `/kill` inside Lumina | OBSERVATION | `Killed QaBot` is printed but nothing happens: the safe-zone handler cancels every damage event, including `/kill` |
| E, F, G, H, I | bound-protection GUI cases, telegraphs, dungeon/party, NPC/shop GUI, spark | NOT-RUN | |

## Findings (sent to Codex)
1. **Spawn/respawn world**: the first join was at `[minecraft:overworld] 4.5, 64.0, 4.5`; after dying in `ec_world` the player respawned in the vanilla overworld forest, not at the Lumina spawn. Expected: join and respawn in the ec_world Lumina spawn.
2. **No weapon after death**: the starter weapon is not bound, so it drops on death; the respawned player has no weapon (only the skill slot and bound items). Expected: keep or auto-regrant the starter weapon.
3. **Silent duplicate `ec spawn` (minor)**: a second `ec spawn` of the same enemy is ignored without any message while the first is alive (cap 1 per admin point).

## Task 8 real-client run (offline QaFresh, 2026-11-21)
| Item | Result | Evidence |
|---|---|---|
| Fix 1: new player starts in Lumina | PASS | a fresh player sees the Lumina platform, NPC name tags and the new pier water (server log still shows the vanilla spawn coordinates before the plugin teleport) |
| Fix 2: starter weapon survives death | PASS | weapon back in slot 1 after respawn, respawn in Lumina |
| Fix 3: duplicate `ec spawn` message | PASS | `同じ魔物が既に存在するか、出現上限に達しています。` |
| Gathering node visual | PARTIAL | oak-log BlockDisplay + `[採取]` label visible; displayed half a block off the clickable tile |
| Gathering channel/boss bar/reward | NOT CONFIRMED | right-click with an iron hatchet on the node tile showed no boss bar; chat was flooded (see below) |
| Chat spam | FAIL | `戦闘により採取を中断しました。` repeats several times per second for a non-gathering player; death adds `倒れたため採取を中断しました。` and `倒れたため釣りを中断しました。` |
| Fishing minigame | NOT-RUN | |
Processes stopped; `server.properties` / `ops.json` restored.

## Task 9 real-client re-check (offline QaFresh, 2026-11-21)
| Item | Result | Evidence |
|---|---|---|
| Chat spam when not gathering | PASS | no repeated `戦闘により採取を中断しました。` while standing in Lumina / at the node (death path not re-run) |
| Node BlockDisplay position | PASS | log sits exactly on the highlighted clickable tile |
| Gather reward | PASS | stack of 6 appeared after right-clicks, node visual depleted for the gatherer |
| Gather boss bar / progress UI | NOT CONFIRMED | no boss bar visible 0.35-3 s after the click |
| Fishing minigame, anti-macro | NOT-RUN | |
| Advancement data pack | FAIL | WARN every boot; pack written to `world/dimensions/minecraft/overworld/datapacks`, server reads `world/datapacks` |

## Task 10 real-client check (offline QaFresh, 2026-11-21)
| Item | Result | Evidence |
|---|---|---|
| Datapack WARN on boot | PASS | none on two consecutive boots; pack in `world/datapacks` |
| Progress tab / toast / `/ec title` | NOT-RUN | |
| Debug fishing UI | PASS (presentation) | boss bar `格闘中 [安定] 進捗 0%`, fishing action bar replaces the class HUD |
| Full reel, reward, size/crown record | NOT-RUN | sessions ended before input (hostile mobs at the pier) |
| Gather boss bar / HUD coexistence | NOT-RUN | |
| No vanilla hostile mobs at Lumina (night) | FAIL | 5 zombies + 2 skeletons within 24 blocks of the hub after 60 s; zombie + spider on the pier even under Peaceful |
| Anti-macro debug | NOT-RUN | |

## Task 10 follow-up (codex d94ca75)
| Item | Result | Evidence |
|---|---|---|
| Vanilla hostile mobs near Lumina at night | PASS | `allowMonsters=false` logged; no zombie/skeleton/spider after 75 s at night |
| `ec spawn` still works | PASS | wolf summoned; duplicate message shown |
| Debug fishing UI (boss bar, action bar, failure title) | PASS | `逃げられた…` / `糸が切れてしまった` after line snap |
| Debug fishing success + record | NOT-RUN | scripted timing failed twice; `fish_records` empty |

## Task 11 real-client check (offline QaFresh, 2026-11-21)
| Item | Result | Evidence |
|---|---|---|
| Title toast on `ec:flag_title_rookie_slayer` | PASS | `Advancement Made! 港町の新鋭討伐者` |
| `/ec title` list | PASS | check mark on rookie_slayer |
| Gather boss bar | NOT CONFIRMED | still no boss bar; see TESTING.md |
| Hidden chest, time-window NPC, arena on client | NOT-RUN | no shipped content (chest/time) / time-boxed (arena, MockBukkit only) |

## Task 12 real-client check (offline QaFresh, 2026-11-21)
| Item | Result | Evidence |
|---|---|---|
| Gather boss bar / channel / reward / records / cooldown text | PASS | see TESTING.md; earlier misses were aiming at the wrong tile |
| Personal chest once + reconnect | PASS | +35G once, `この宝箱は空です。` after rejoin |
| Luna night, herbalist rain/dusk, rare goblin night+rain | PASS | appear/disappear with `time set` / `weather` |
| Arena entry | PASS (entry only) | wave-1 slimes shown; waves/rank/reward NOT-RUN |

## Final real-client pass (48beca1, 2026-11-22)
See docs/TESTING.md. PASS: join/class/HUD/NPC+shop GUI/quest/codex/enhance/title/party, chests, Baldo/Luna, debug fish CAUGHT+record, dungeon entry, 0 ERROR, TPS 20. PARTIAL: arena (wave 1->2). NOT-RUN: death/respawn, other classes, advancement tab, boss fights, normal-cast fishing, night-thunder goblin.


## Remaining items pass (master ea87cdb, offline QaMage, 2026-11-22)
Shots: `D:\test\mcmmo-client-qa\shots\g01..g46*.png` (not in repo).
| Item | Result | Evidence |
|---|---|---|
| Death -> respawn | PASS | g05-g07: normal items dropped, bound `starshard_fragment` kept, weapon + skill slot present, HP full, respawn in Lumina |
| Class menu pick (cleric) + cast | PASS | g04 (`星光の導矢`, mana HUD, skill hits slime) |
| Other classes | PARTIAL | warrior/mage/archer HUD + skill name switch via admin class command (g34); starter gear via the menu NOT verified (admin switch keeps old weapon -> `この装備の条件を満たしていません`) |
| Quest chain m01 (Celia, Vulcan, Garudo, Vulcan report) | PASS | g11-g30: profile objectives `talk_celia, talk_vulcan_class, talk_garudo, return_vulcan` all 1, `completed: true`; reward 60G + 90xp + 3 potions + stew; next quest offered |
| Shop purchase | PASS | g31-g33: 60G -> 32G -> 4G, 2 potions received, repeat purchase works |
| Advancement tab | PARTIAL/FAIL | tab "EtherealChronicle" and icons shown, background is the missing-texture checkerboard (g16_adv.png). Toast verified earlier |
| Normal fishing cast, wait, bite, reel | PASS | g37-g42: cast shows `魚影を探しています...` + bobber; bite window ~2 s (missed once = ESCAPED_BITE); clicking during the wait cancels (`釣りを中断しました`); held right-click after bite -> CAUGHT, record `sunbeam_trout 43.9 cm SILVER`, fishing xp 18, fish item in hotbar |
| Arena 3 waves / rank / first reward / record | NOT achieved | entry and spawns fine; scripted fight killed only 1 slime in ~250 s. Cause: operation (my aim/teleport loop), not shown to be balance or timeout. Records empty |
| Night + thunder goblin | PASS | present at night+thunder, absent at night+clear and day+thunder |
| Raw ids in quest log | FAIL (sent to Codex) | quest list shows `ec:npc_celia 1/1` instead of NPC names |
| Potion tooltip | MINOR (sent to Codex) | vanilla `No Effects` line |
Processes stopped, server.properties/ops.json restored, port closed.


## Task 15 real-client check (master 6f8d7bc, offline QaPack, en_us client, 2026-11-22)
Shots: `D:\test\mcmmo-client-qa\shots\h01..h27*.png` (not in repo).
| Item | Result | Evidence |
|---|---|---|
| Build | PASS | `gradlew build` green |
| Boot, datapack, 6 biomes, wolf variants | PASS | no WARN/ERROR in server log (only offline-mode notice, "Skipping Entity" legacy mobs) |
| Embedded HTTP (25580): valid GET/HEAD | PASS | 200, downloaded SHA-1 = jar-embedded pack SHA-1 `594ce9b8...` |
| Path traversal, wrong hash, query, POST, `/`, `/pack/`, encoded `%2e%2e`, `%5c`, `%00`, raw `/pack/../..` | PASS | all 404 |
| 300 parallel GETs | PASS | 300x200 in 1.3 s |
| 100 KB URL / 70 KB header | OK | 404 / 200 for a valid path (no crash); 5000 headers: connection dropped |
| Slowloris (100 partial requests held open) | WEAKNESS | valid GET returned 000 (timeout) for 40 s while 100 partial-request sockets were open (maxConcurrent 32 permits taken, no read timeout); recovered after closing. 200 silent idle sockets did NOT block |
| bind_host default 0.0.0.0 | OBSERVATION | 25580 listens on `::` (all interfaces); only the fixed pack path serves content |
| Client prompt + decline/accept | PASS | prompt localized (en) "Apply the EtherealChronicle resource pack?"; accept -> download, hash verified, reload (`server/.../a7c7ef70...`); `/ec ui pack on` re-prompts. After a decline the client does not re-prompt on the same address (client behaviour, not plugin) |
| Preference persistence (schema 12) | PASS | `schema_version: 12`, `resource_pack_preference: 'ON'` |
| Resource pack content | FAIL (minor) | client WARN `Missing textures in model ec:item/ui_transparent` although `textures/gui/transparent_slot.png` is in the zip (texture is probably invalid/oversized or atlas path) |
| InvUI class selection + Paper Dialog confirm | PASS | h01, h04 (class list in InvUI, Start/Go back dialog, English text) |
| InvUI Codex / Enhance / Equipment / Shop | **FAIL (blocker, sent to Codex)** | `/ec codex|enhance|equipment|shop` -> `Could not complete that action: Illegal width: 6`, no GUI (h14, h15) |
| NPC dialogue via Paper Dialog, quest accept | NOT-RUN | blocked by time after the blocker |
| Advancement tab background | PASS | sand background (h25), no checkerboard. Tab title still Japanese on an en_us client |
| Title toast | NOT-RUN | verified in an earlier task |
| Wolf variant visuals | PARTIAL | server data `variant: ec:thorn_wolf` set; visual not captured clearly (h27). frost_wolf / ashwood_alpha NOT-RUN |
| `/ec quest` display names, potion tooltip, class-change weapon | NOT-RUN | |
| Language: client en_us -> English UI (HUD "Focus/Party", dialogs, pack prompt) | PASS (partial) | content names (class/skill/NPC) stay Japanese; auto-selection works. ja_jp/en_us config forcing NOT-RUN |
Processes stopped, server.properties/ops.json restored, ports closed.

## Task 16 real-client check (master 2aaebcb, offline QaPack, en_us client, 2026-11-22)
Shots: `D:\test\mcmmo-client-qa\shots\i01..i09*.png, j01..j08*.png, v_*.png` (not in repo).
| Item | Result | Evidence |
|---|---|---|
| InvUI Codex / Enhance / Equipment / Shop open on real Paper | PASS | i06_*, i02_shop (blocker "Illegal width: 6" fixed); codex area click opens enemy page (i07); enhance item click shows tooltip + currency message (i09) |
| Shop purchase success / enhance success / paging | NOT-RUN | test player had 0G (sim currency does not affect a real player) |
| Shop insufficient funds | BUG (sent) | message shown, but server logs ERROR + stack (`IllegalArgumentException ec.error.currency_low`, AdventureController.buyShopSlot) on each click |
| UI with vs without pack | NO DIFFERENCE | InvUI screens look the same (j05/j06 vs i02/i06); asked Codex if intended |
| game_port mode (config temporarily changed) | PASS | pack served on 25565 (no 25580 listener): GET/HEAD 200, SHA-1 = jar pack; `/`, traversal, `%2e%2e` 404; client prompt, download, reload applied, login and Tp/HUD fine |
| game_port abuse | PASS | 100 partial-request + 100 random-binary sockets open: valid GET still 200 and a client logged in; 200 parallel GETs: 19 x 200, 181 refused (per-IP limit) |
| game_port ping latency | NOT-RUN | |
| Slowloris on embedded_http (separate port) | NOT-RUN | not re-tested after Codex timeouts |
| DemoWorld v4->v5 | PARTIAL | world marker already `v5` at first boot (no v4 fixture); migration and second-boot idempotence not re-verified; hub/NPCs/pier intact (v_hub) |
| Wolf variants | PARTIAL | frost_wolf spawned and killed the test player; visual not inspected; ashwood_alpha NOT-RUN |
| NPC Paper Dialog quest accept, /ec quest names, potion tooltip, class-change weapon, language forcing, en_us advancement title | NOT-RUN | time |
Processes stopped; server.properties/ops.json/config restored; ports closed.

## Task 17 real-client check (master 4b73918, offline QaPack, en_us client, 2026-11-22)
Shots: `D:\test\mcmmo-client-qa\shots\k01..k12*.png, w1..w3*.png, l_*.png` (not in repo).
| Item | Result | Evidence |
|---|---|---|
| Build | PASS | `gradlew build` green |
| `/ec currency` grant | PASS | 5000G set |
| Shop buy success | PASS | 5000 -> 4972 -> 4944 G, 2 potions received (k02b) |
| Shop insufficient funds | PASS | no ERROR/stack in server log (0 ERROR for the whole session) |
| Enhance success | PASS | whetstone 3 -> 2, `Equipment enhanced to +1` message (k06) |
| Codex area -> enemy page | PASS | k09/k08 |
| Codex paging beyond one page | NOT-RUN | |
| Pack-themed GUI | FAIL (sent) | dark panel smaller than container, vanilla grey showing, rows misaligned; menu titles are rows of empty boxes (missing glyphs) (k04, k08) |
| Without-pack fallback screenshots | NOT-CAPTURED | `/ec ui pack off` leaves the loaded pack's look; true decline path needs a fresh client profile |
| Enhance tooltip | MINOR (sent) | raw id `ec:item_mat_artisan_whetstone` |
| `/ec quest` names | PASS | NPC names shown (受付嬢セリア etc.); header mixes languages on en client (minor) |
| Wolf variants (pack applied) | PASS (thorn, alpha) | thorn_wolf green/vines (w1), ashwood_alpha black+cyan (w3); frost_wolf not captured (spawn cap) |
| TPS | PASS | 20.0/20.0/20.0 |
| NPC Paper Dialog quest accept, potion tooltip, class-change weapon, forced ja_jp/en_us, en advancement tab, ping, slowloris on 25580 | NOT-RUN | time (language switch script failed) |
Processes stopped; server.properties/ops.json/config restored; ports closed.

## Task 18 real-client check (master 3564049, offline QaPack/QaPlain, en_us client, 2026-11-22)
Shots: `D:\test\mcmmo-client-qa\shots\m1_*, m2_*, n*, o*.png` (not in repo).
| Item | Result | Evidence |
|---|---|---|
| Build | PASS | green |
| Default config: required=true without delivery | PASS | boot WARN `必須リソースパックを配信できないため、必須設定を一時的に無効化しました` (+ game_port opt-in warn); players join normally |
| GUI icons_only default (class/shop/codex/enhance) | PASS | m1_* clean, titles readable (pack not loaded in that run) |
| Enhance tooltip raw id | FIXED | shows `名匠の研磨砥石` (m2_enh_tip) |
| `/ec quest` header on en client | PARTIAL | `Status: In progress` now English; area name stays Japanese (content name) (m2_quest) |
| required=true with embedded_http: prompt | PASS | client shows "This server requires the use of a custom resource pack" with server message (n5); accept -> download + play (n6) |
| required: decline | PASS | client disconnects "This server requires a custom resource pack" (n9); the plugin's Japanese kick text is not reached because the client leaves first |
| required: OP (bypass) decline | PASS | OP gets optional prompt, declines, stays in (o1, o2) |
| required: new player at first join | OBSERVATION | prompt not shown on the very first join (class menu), only after profile exists/rejoin; first-join players are not forced |
| Client remembers accepted pack | OBSERVATION | quickplay client re-applied silently after earlier accept; I had to reset servers.dat to see the prompt |
| Pack SHA-1 change -> re-request | NOT-RUN | needs rebuild |
| ui.theme full / icons_only / off visual | INCONCLUSIVE | screenshots identical in all three (o3_*); pack may not have been applied in that run (accept click unverified) and config reload may not re-read ui.theme |
| Codex paging, frost_wolf, Paper Dialog quest accept, potion tooltip, class-change weapon, language forcing, ping | NOT-RUN | time |
| TPS / log | PASS | 20.0 x3, 0 ERROR |
Processes stopped; server.properties/ops.json/whitelist/config restored; ports closed.

## Task 19 real-client check (master 9cfb25e, 2026-11-22) - PARTIAL
Shots: `D:\test\mcmmo-client-qa\shots\p1_first.png, p2_first.png, p3-p5.png` (not in repo).
| Item | Result | Evidence |
|---|---|---|
| Build, `/ec pack info` | PASS | prints SHA-1 `9fbd286c...` / 47725 bytes |
| Mandatory pack on first join (new non-op UUIDs, required=true, embedded_http up) | FAIL (sent to Codex) | QaNew1/QaNew2: no prompt, no download, class menu opens directly |
| Decline->disconnect on first join, OP bypass, reconnect | BLOCKED | no prompt to decline |
| MOTD on decline, ui.theme reload comparison, external_managed, SHA-1 change re-request, codex paging, frost_wolf, Paper Dialog quest accept, potion tooltip, class-change weapon, language forcing, ping | NOT-RUN | blocked/time |
Processes stopped; server.properties/ops.json/whitelist/config restored; ports closed.

## Task 20 first-join gate re-check (codex 8f3acfa merged on 867fa5c, 2026-11-22)
Shots: `D:\test\mcmmo-client-qa\shots\q1.png, q2_prompt.png, q4_after.png, q5_op.png`.
Config (temporary): embedded_http, required=true, debug=true, required_timeout_seconds=25.
| Item | Result | Evidence |
|---|---|---|
| Fresh non-op first join: eligibility -> gate -> dispatch -> request sent | PASS | log lines `join eligibility ... bypass=false`, `required pack gate started`, `dispatching required request after join tick`, `request sent ... required=true` |
| Client prompt | PASS | "This server requires the use of a custom resource pack" screen (q2) |
| Accept -> loaded -> class menu | PASS | `request loaded` 5 s after sent; class menu with pack icons (q4) |
| No response -> timeout kick | PASS | `required request timed out after 25s`, client "The resource pack was not applied in time..." (q1) |
| OP bypass | PASS | `bypass=true`, `required=false`, class menu with vanilla icons (q5) |
| Explicit decline path | NOT-RUN | covered by Task 18 (client disconnects) |
| Log | PASS | 0 ERROR |

## Task 23 final QA (master d0b0ba4 / codex a46cf25, offline QaF1, en_us client, 2026-11-22) - STOPPED at a High blocker
Shots: `D:\test\mcmmo-client-qa\shots\r1..r10*.png` (not in repo).
| Item | Result | Evidence |
|---|---|---|
| Quick review of a46cf25 | no High from review alone | see below (latent quest pre-check regression, async validation `result.get()`) |
| Headless smoke `./scripts/smoke-test.ps1 -SkipBuild -Commands 'ec validate','ec reload'` | PASS (script bug) | `EC_CONTENT_VALIDATION_SUCCESS` logged, reload ok, plugin enabled, WARN only the expected default-config loopback warning. The script printed `PropertyNotFoundException ... 'Count'` at smoke-test.ps1:87 (`($Commands | Where-Object {...}).Count` on a single match under StrictMode) so the marker check was skipped; wrap in `@(...)` |
| Local pack config (`bind_host`/`public_host` 127.0.0.1) | PASS | listener on 25580, no loopback WARN (FIX-07) |
| Fresh non-op first join, long wait | PASS | request sent, player stayed 75+ s on the prompt at (-9.5,71,1.5), no flying kick, no timeout kick before accept (M3 at this spawn; mid-air spawn untested) |
| Accept -> loaded | PASS | ACCEPTED/DOWNLOADED/SUCCESSFULLY_LOADED logged, class menu shown |
| Esc on class confirm dialog | PASS | Esc ignored, dialog stays (r6) |
| "Go back" returns to class menu | PASS | r7 |
| `ui.theme.mode: full` title/background | FAIL (FIX-26) | dark pack panel covers only about the left 70 % of the chest window and the title "Choose a class" starts left of the panel (r4, r7, r9): offset/size still wrong |
| **Server main thread hang** | **FAIL - HIGH BLOCKER** | after Go back -> pick class -> Start as this class -> `/ec class`, the server thread spun for 50+ s ("The server has not responded for N seconds", dump in `scripts/logs/server-host-*.log`), client frozen on the class menu. Stack: `CraftScheduler.mainThreadHeartbeat -> RpgPlugin.sync -> RpgPlugin.menu(RpgPlugin.kt:305) -> ClassSelectionUi.open(ClassSelectionUi.kt:52) -> editMeta/classLore` repeating |
| Fishing air click, returning-player position, shop refresh sell, full-inventory NPC talk, admin `/ec class`, theme full/icons_only/off comparison | NOT-RUN | stopped at the blocker |
Processes stopped (server was force-killed), server.properties/ops/whitelist/config restored, ports closed.

### Diagnosis of the hang (needs Codex confirmation)
`ClassSelectionUi.window.addCloseHandler { if (!isConfirming) onClose(player) }` -> `onClose` = `sync { if (online) menu(p) }` -> `ClassSelectionUi.open()` first calls `confirming.finish(uuid)` and then `window.open()`, which closes the viewer's previous InvUI window and fires its close handler again with `isConfirming == false`; that schedules another `sync { menu(p) }`. `runTask` scheduled from inside the scheduler heartbeat is executed in the same heartbeat, so the cycle never yields to the next tick: infinite loop on the main thread. Fix: track the current window per player and ignore close handlers of replaced windows (`if (window !== current[uuid]) return`), set a "replacing" flag around `window.open()`, and schedule the reopen with `runTaskLater(1)` plus a per-player re-entrancy guard.

## Task 24 re-run (master 4572ef4 / codex 3f9bafd, offline QaG1, en_us client, 2026-11-22)
Review gate: 3f9bafd code review found no High/Med blocker (Low: `ClassSelectionWindowTracker.open` leaves `current` set if `window.open()` throws; startup `result.get(15 s)` is fatal on a very slow cold start).
Shots: `D:\test\mcmmo-client-qa\shots\s1..s5, t_*, u1..u3, v1,v2, w1, x1, y1, y2 *.png` (not in repo). Helper: `scripts/client/qa-setup-config.ps1` (apply/restore QA config incl. `-ThemeMode`).
| Item | Result | Evidence |
|---|---|---|
| Build + headless smoke | PASS | `gradlew build` green; `smoke-test.ps1 -SkipBuild -Commands 'ec validate','ec reload'` PASS, `EC_CONTENT_VALIDATION_SUCCESS`, no StrictMode error |
| Class menu cycle: accept pack -> pick -> Esc (ignored) -> Go back -> pick -> Start -> `/ec class` | PASS | no hang (0 ERROR, "not responded" none, TPS 20.0/19.9), class assigned, `/ec class` -> "A class has already been selected." (s1-s5) |
| FIX-26 `ui.theme.mode: full` | PASS (minor) | panels now cover the full chest width and title is centred/readable on class/codex/enhance/shop (s1, t_full_*); small grid misalignment in the lower inventory rows and the vanilla "Inventory" label dimmed over the panel |
| `icons_only` / `off` | PASS | via `/ec reload` switch: vanilla grey chest with pack icons / without themed background (t_icons_only_*, t_off_*) |
| Shop buy + refresh + right-click sell | PASS | 5000 -> 4972 -> 4944 G, then sells +7 +7 G after refresh |
| Fishing cast (rod, aimed at water) -> bite -> reel (held click) | PASS | cast shows "Searching for fish...", BITE_ALERT, `reason=CAUGHT` (v1). Left-click-air reel input and the gate-locked rejection were not separately exercised |
| Returning player keeps position (gated relog, non-op) | PASS | logged in at last position (102.96, 65, -3.32), `data get` identical after load; no hub teleport |
| Admin `/ec class <player> <id>` in game (op) | PASS | class -> warrior, sword given, "Class updated." (y2) |
| Full-inventory NPC talk with ready auto-complete quest | NOT RUN | no ready auto quest was prepared |
| M3 mid-air/uneven spawn during long wait | NOT RUN (earlier run: 75 s wait OK at a ground spawn) | |
| Server log | PASS | 0 ERROR, TPS 20.0 |
Processes stopped; server.properties/ops/whitelist/config restored; ports closed.

## Task 24 follow-up: remaining scenarios (master 4572ef4, offline client, real Paper)

Setup note / correction: all 69 shipped quests have `auto_complete: false`, so no shipped quest can exercise the auto-complete path. A synthetic dev-server-only quest `ec:quest_qa_auto_full` (EXPLORE `ec:area_lumina_port`, `auto_complete: true`, item reward) was added to the dev server content (not the repo) and removed afterwards. Scripts: `scripts/client/qa-fullinv.ps1`, `qa-leftclick3.ps1`, `qa-midair.ps1`, `qa-gatefish.ps1`, `qa-cleanup.ps1`.

| Scenario | Result | Evidence / limits |
|---|---|---|
| Full-inventory NPC talk with a ready auto-complete quest | PASS | 2300 dirt (all slots full), `/ec quest start ec:quest_qa_auto_full` while standing in the port area: profile showed `progress: 1, completed: false` (ready but blocked). Right-click on the NPC opened the Dialogue normally; log had no ERROR/WARN. After `clear`, within ~5 s the quest became `completed: true` and the reward potions appeared in the hotbar (z1_*). |
| Mid-air saved position during the required-pack wait (M3) | PASS (limited) | Saved at y=137.3 (killed client 0.7 s after tp to y=140), non-op, pack prompt left unanswered ~48 s: no "Flying is not enabled" kick or disconnect, player fell and landed (in pier water, so fall damage was NOT exercised). Uneven/cave terrain not tried. |
| LEFT_CLICK_AIR reel input | PASS (behavioural) | `ec debug fish start` (REELING). Right-click pull only: progress rises (~14% in 14 s, 2 trials). Right+left-click alternating: progress stays 0-1% and the session ends `LINE_SLACK` (2 trials). Left-click only: `LINE_SLACK` within ~3 s, same as no input. So left-click air is received and acts as BRACE (suppresses the pull); exact tension numbers were not characterised. |
| Gate-locked fishing rejection | NOT VERIFIED | The pack prompt screen is modal, so no game input is possible before "Proceed", and the pack loaded ~1 s after Proceed; right-click spam after Proceed only produced the normal "Aim at the water"/cooldown messages (crosshair was not on water), which cannot be told apart from the gate. Covered by unit tests only (`FishingInteractRoutingTest`, lock listener). Blocker: no way to inject input into the ~1 s window. |

Cleanup: client/server stopped, `chapter_1.yml` and the synthetic quest reverted, `server.properties`/`ops.json`/`whitelist.json`/`config.yml` restored, ports 25565/25580 closed, 0 `*.qa-orig` left.


## Task 25 (master b8c91ee, `0b1e470..b8c91ee`): build, smoke, real Paper 26.3 + real client

Automated: `gradlew build test --no-daemon` BUILD SUCCESSFUL, 297 tests, 0 failures/skipped. Headless smoke (`smoke-test.ps1 -SkipBuild -Commands 'ec validate','ec reload'`) PASS: `EC_CONTENT_VALIDATION_SUCCESS`, 0 ERROR, no StrictMode error (the only WARN is the expected default-config pack-host warning).

Real run: fresh non-op player `QaT25`, offline client, `ui.theme.mode: full`, embedded_http pack, world was night + rain during the whole run (so night/rain spawn windows were live). Scripts: `scripts/client/qa-t25-*.ps1`.

| Area | Result | Evidence / limits |
|---|---|---|
| Pack gate + class menu (`ClassSelectionUi`) | PASS | prompt -> Proceed -> "Choose a class" 9-slot menu (6 classes) -> confirm dialog -> Start; no hang |
| Starter slots / skill slot | PASS | warrior: sword in slot 0, skill star in slot 8, rest empty (only one starter item, so the 0..7 overflow-to-mailbox branch was NOT exercised) |
| Codex (45/46/47-49/50/53) | PASS (limited) | Areas screen, fish/gathering/items tabs switch via slots 47/48/49, back arrow at 46, page label at 50. Previous/next page (45/53) not exercised: too few entries for a second page |
| Enhance (slots 4/13) | PASS | target item in 4, confirm in 13; click gives "Could not complete that action ... currency" (no crash) |
| NPC dialogue + shop | PASS | Pip dialogue -> shop choice; `/ec shop` 24 blocks away: "Move closer to the shop."; 2 blocks away: opens (distance guard = `SHOP_INTERACTION_BLOCKS`) |
| Fishing (night/rain, `WorldSchedule`) | PASS | real cast -> BITE_ALERT -> hook -> `ended reason=CAUGHT fish=harbor_goby progress=100%`. Note: 100 ms taps at 300 ms spacing did not reel (TIMEOUT, no progress); 600 ms held clicks did. Not compared against the previous build, so treat as harness input behaviour, not a regression |
| Gathering | PASS (complete) / NOT VERIFIED (move-cancel) | dew-moss node with sickle: "Gathering ... Stay still", "Gathered: x2 / Gathering XP 18". The move-cancel (`GATHER_CANCEL_MOVE_BLOCKS`) attempt was inconclusive (first channel start not confirmed), so unverified |
| Combat / enemy AI | PASS | Ashwood spiders/goblins telegraph ("Telegraph: ... Move away"), dealt damage, killed the player ("was slain by 胞子グモ"), respawn at hub with full HP, sword + skill star re-present; no ERROR. Enemy kill XP/drop not checked. Night+rain `moonshower_rare` goblin (WorldSchedule) not located |
| Dungeon (`DungeonArenaLayout`) | PASS (limited) | `/ec dungeon ec:dungeon_novice_arena` after setting the unlock flag: spawn exactly (1024, 65, 1012) = centre x, z-12; oak-plank floor, slimes/wolf spawned at floor level; `/ec dungeon leave` -> (0.5, 65, 0.5). Arena boundary (+-30) push-back, void recovery and a wave clear were not exercised |
| DemoWorld terrain/landmarks | PASS (visual, limited) | hub plaza with walls/lanterns/gate gaps, boardwalk and pier water pool, hub trees, ashwood grass, Lumina quartz floor look intact; the world already existed (v5), so fresh-generation of the new `DemoWorldLayout` code was only checked by reading (see review L8) |
| Server log | PASS | 0 ERROR, 0 "not responding", TPS 20.0/20.0/20.0 |

Observations (no Task 25 regression, for the lead): (1) the shop menu distance is only checked when opening (`/ec shop`); an already-open menu can still be used after walking/teleporting away (a purchase attempt 24 blocks away reached the currency check). (2) The gathering-codex tab button (iron pickaxe) shows vanilla "4 Attack Damage / 1.2 Attack Speed" attribute tooltip. (3) Death removes the non-class items (rod, tools, fish) from the hotbar (drops); sword + skill star are re-granted.

Cleanup: client + server stopped, config/ops/whitelist/server.properties restored, ports 25565/25580 closed, 0 `*.qa-orig`.


## Task 25 follow-up re-check (master d53b4c9)
`gradlew build test --no-daemon` BUILD SUCCESSFUL, 298 tests, 0 failures/skipped. `smoke-test.ps1 -SkipBuild -Commands 'ec validate','ec reload'` PASS: `EC_CONTENT_VALIDATION_SUCCESS`, 0 ERROR, Paper process stopped, ports 25565/25580 closed. One non-blocking script glitch after PASS: `smoke-test.ps1:74` PropertyNotFound on `ParentProcessId` (race with a short-lived java process). No real-client rerun (not requested; first real run on b8c91ee). Audit details in docs/reviews/2026-11-22_task25_claude.md.

## Overworld mode live QA (master b88a0c4, Paper 26.3-257), 2026-11-23
Setup: clean `world` + fresh plugin data (originals moved aside and restored afterwards), jar built from b88a0c4, `world: { mode: overworld }` appended to config. Scripts: `scripts/client/qa-ow-setup.ps1`, `qa-ow-restore.ps1`.

| Item | Result | Evidence |
|---|---|---|
| 1. Clean Overworld first generation (9/9 sites) | **FAIL (High, blocker)** | 12 s after "Adventure systems are ready" the generation aborts: `IllegalStateException: Site sampling chunk repeatedly unloaded at -223,18` (`OverworldSiteSearch.kt:54`, via `loadCandidate` after 6 attempts). No `.etherealchronicle-overworld.json` is written, no site placed, no portals, no Lumina spawn. Reproduced on a second boot (same coordinates, deterministic). Cause: a chunk loaded with `getChunkAtAsync` and no ticket is not loaded one tick later on real Paper; MockBukkit never unloads. Reported to Codex with a suggested fix (sample inside the completion callback or hold a plugin chunk ticket; skip an unloadable candidate instead of failing everything) |
| 2. Restart idempotency | **NOT-RUN** | blocked by item 1 (nothing reaches `COMPLETED`) |
| 3. Real-client Overworld checks (spawn, town heights, dungeon round trip, abnormal-quit recovery, `/ec sites`, `/ec dungeon release`) | **NOT-RUN** | blocked by item 1; no client was started, no screenshots (`overworld-live-qa` folder not created) |
| 4. Default `dedicated_ec_world` regression | **PASS** | clean start with no `world.mode`: `Created ec_world from bundled schematics (terrain v6)`, `Adventure systems are ready`, `EC_CONTENT_VALIDATION_SUCCESS`, 0 ERROR, TPS 20.0 |

Server up time to failure and TPS during the failed run were not meaningful (failure at +12 s, server stayed healthy). Rerun items 1-3 once the sampling fix is on master. Cleanup: server stopped, original `world`, `plugins/EtherealChronicle`, server.properties, ops.json, whitelist, config restored; ports closed; the failed run's world was kept as `world.qa-run-overworld-failed` under dev/server.

## Overworld mode live QA rerun (master 1517a0c jar), 2026-11-23
Harness note: `scripts/client/server-host.ps1` re-installs `<worktree>/build/libs` into `dev/server/plugins` on every start, so the supplied jar (`D:\test\mcmmo\build\libs`) had to be copied into the qa-harness `build/libs` first (the first rerun accidentally ran the old b88a0c4 build and reproduced the old "chunk repeatedly unloaded" error; discarded). Verified by SHA1 5A8396A6 before the runs below.

| Item | Result | Evidence |
|---|---|---|
| 1. Clean Overworld generation (9/9) | **FAIL (High, blocker)** | Chunk-sampling fix works (no unload error), but after ~4.5-5 min of search the town planner aborts: `IllegalArgumentException: Insufficient distinct grounded plots for ec:area_lumina_port` (`OverworldTownAssembler.planBuildings`, line 332: Lumina needs >= 10 `house_` buildings with >= 5 distinct templates). Reproduced on two independent clean worlds (seeds 2697034697951946031 at 10:46:16 after start 10:41:21, and 9212233920172711728 at 10:51:31 after start 10:47:33). Journal `.etherealchronicle-overworld.json` not written, no site placed, no portal, no Lumina spawn. TPS stayed 20.0/20.0/20.0 and no "Can't keep up" lines during the ~5 min search |
| 2. Restart idempotency | **NOT-RUN** | nothing reaches COMPLETED |
| 3. Real-client checks + screenshots | **NOT-RUN** | blocked by item 1 (no client started; `overworld-live-qa` folder not created) |

Observations: generation takes ~5 min of wall time before it can even fail; the log gives no progress lines between "Adventure systems are ready" and the final error, and the failure message does not say which site/plots were rejected or why (candidate count, slope/water rejections). Suggest logging per-site candidate evaluation and the number of grounded plots found, and making the Lumina house minimum fall back to a weaker site candidate rather than failing the whole generation.
Cleanup: server stopped, dev `world`, `plugins/EtherealChronicle`, server.properties, ops.json, whitelist, config restored, failed-run folders deleted, ports closed.

## Overworld mode live QA #3 (master f5b086f jar, SHA1 8BE19C1D0959BC004C73525D5113B34DE91E812D), 2026-11-23
Jar copied into qa-harness `build/libs` (server-host reinstalls from there) and SHA1 verified in `dev/server/plugins` before the run. Clean `world`, seed 4761524742627086163, `world.mode: overworld`.

| Item | Result | Evidence |
|---|---|---|
| 1. Clean generation 9/9 | **PASS** | search 11:19:39 -> 11:20:46 (**67 s**, 9/9 located); build 11:20:46 -> 11:21:40 (**54 s**, 9/9 ready); total ~2 min. Journal `world/dimensions/minecraft/overworld/.etherealchronicle-overworld.json`: `status: COMPLETED`, 9x `READY`, 4 portals (ashwood, stargazer, void sanctum, glacial citadel), Lumina `anchors.spawn` = (-154, 64, -256). Buildings: Lumina 25, Ashwood 5, Weeping Mine 4, Stargazer 4, Void Sanctum 2, Frostpeak 12, Glacier Ridge 3, Crystalline Cavern 3, Glacial Citadel 4. TPS 20.0 / 20.0 / 20.0, 0 ERROR/SEVERE, 0 "Can't keep up". WARN lines: 4x `Could not project position (...) Water surface is not valid solid ground` (3 around Lumina at the start of Ashwood prep, 1 at Void Sanctum) - benign but noisy; no house-count-below-target or pond warnings |
| 2. Restart idempotency | **PASS** | stop + start: `Done (6.995s)`, no regeneration, no Overworld log lines, 0 ERROR/WARN, journal SHA1 unchanged (336C1873...), `/ec sites` reports all 9 sites "完成" with the same coordinates and the 4 dungeon entrances/exits |
| 3a. First join / Lumina | **PASS** | fresh offline client logs in at (-144.5, 68, -260.5) in the Lumina plaza area; pack prompt -> class menu rendered over the village (00, 01); spectator tour shows grounded houses, plaza with stalls/fountain/lanterns, pier and fishing water, hillside, NPC villagers (02, 03, 04) |
| 3b. Other sites | **PASS (visual)** | ashwood, weeping mine, stargazer, void sanctum, frostpeak, glacier ridge, crystalline cavern, glacial citadel all show placed structures on terrain without visible floating/sunken buildings (05, 07-13); weeping-mine shot is mostly hillside (camera not centred on the buildings) |
| 3c. Sky-box dungeon round trip | **PASS** | `/ec dungeon ec:dungeon_novice_arena` at the Ashwood entrance -> (100000.5, 221, 99988.5) arena with slimes/wolf (15); `/ec dungeon leave` -> back at the exit (390.5, 67, -271.5); second entry lands on the identical slot position (17) = slot reused; TPS 20.0 throughout |
| 3d. Abnormal quit inside dungeon | **PASS** | client killed inside the arena; on relogin the player is first placed at (100000.5, 221, 99988.5) and, after the pack gate, rescued to the dungeon exit (390.5, 67, -271.5) with HP 150/150 (18); no ERROR |
| 3e. Admin commands | **PASS (partly)** | `/ec sites` lists sites + dungeon entrances; `/ec dungeon release 0` and `release 7` -> "no protected state or debris record to release" (no stuck slot to release, so the real release path was not exercised). `/ec overworld` does not exist (prints the usage line): no separate admin status command |

Notes: shot 14 (`14_dungeon_entrance_portal.png`, 16 KB) is blank/unusable (taken during a teleport); 06 was re-taken from a higher angle (the first was inside a wall). The client pauses on lost focus, so `pauseOnLostFocus` was set to false for the run and restored to true; Windows intermittently kept `ShellHost` in the foreground, so focus was retried. Screenshots: `C:\Users\Ymii\.traycer\epics\132bc8e7-97a8-4d19-af01-669c4ba69b04\artifacts\overworld-live-qa\` (00-18). Not exercised: treasure chests, gathering nodes and fishing at overworld sites, NPC dialogue, portal-block entry (the command path was used instead of walking into the portal), CLEANING state visibility (no explicit log line at INFO).
Cleanup: client and server stopped, dev `world`, `plugins/EtherealChronicle`, server.properties, ops.json, whitelist, config restored, ports 25565/25580 closed.

## Overworld live QA #4 - gameplay deep check (jar f5b086f, SHA1 8BE19C1D0959BC004C73525D5113B34DE91E812D), 2026-11-23
Fresh world, seed -690295443989269238: search + build finished by ~21:14:13 (started 21:12:21, ~2 min), 9/9 COMPLETED, TPS 20.0, 0 ERROR/SEVERE throughout. Lumina centre (-114, 62, 329) in a SNOWY_BEACH biome with a frozen sea (a poor fishing site, see below). Screenshots: `...\artifacts\overworld-live-qa-gameplay\` (01-17).

| # | Item | Result | Evidence |
|---|---|---|---|
| 1 | Portal walk-in (Ashwood, portal x=-383, y64-68, z343-345) | **PASS** | walking east from (-389.5,65,344.5) into the portal with the unlock flag set enters the sky dungeon (100000.5, 221, 99988.5) with the entry message (03); `/ec dungeon leave` returns to the exit (-367.5, 64, 344.5). Without the flag the walk-in is refused with "All party members must unlock access to the dungeon" (02). (Note: first attempt with the flag unset also let a wolf kill the survival tester at the portal - the portal area has hostile spawns right at the entrance.) |
| 2 | Gathering (port_pebble_ore, projected 2.2 blocks from (-139,64,323)) | **PASS** | node display grounded beside the pier (05); right-click with an iron pickaxe starts the channel (`GATHER ... progress=58% bossbar=true visible=true duration=2058ms`), completes with "Gathered: 川底の星鉄礫 x1, 粗砥の欠片 x1 / Gathering XP 16" (06, 07). Initial aim from 7 blocks away gave "Right-click the display or ground at ..." (distance requirement, expected) |
| 3 | Fishing | **PARTIAL / BLOCKED** | `ec debug fish start` + `status` work in the Overworld (REELING, bossbar=true, then LINE_SLACK without input) (10 log). Real cast was NOT completed: the Lumina pier spot (-104, 357, r14) sits on a frozen sea, "Aim at the water before casting." on ice; casting at the only open pond (-118,345, outside the spot radius) gave "No fish are visible in these waters." Vanilla-rod behaviour was not checked. This is a seed/biome limitation, not necessarily a bug - rerun on a seed with a liquid Lumina |
| 4 | Treasure chest (lumina jetty, projected (-142, 347)) | **PASS** | BlockDisplay chest stands on snow/ground at the edge of the village (11, 12); first open: "Opened a hidden treasure chest. Currency +35G" and 3 dew grubs; flag `ec:flag_early_cache_lumina` set (13); second open: "This treasure chest is empty." (14) |
| 5 | NPC dialogue / shop (Pip at (-120,63,345)) | **PASS** | right-click opens the Japanese dialogue; choice 1 opens the shop menu with 3 entries and the currency line "35G" (16, 17); 0 ERROR |

Cleanup: client and server stopped, dev world/plugin data/server.properties/ops/whitelist/config restored, `pauseOnLostFocus` restored to true, ports closed.

## Overworld live QA #5 - fishing pond and warning regression (jar ea9a364, SHA1 D88578CA0599917DA26A69786CC5B54D490D3AEB), 2026-11-23
SHA1 verified in both build/libs and dev/server/plugins before and after server start. Fresh world, seed 4528502914240555028: search started 22:44:37, 9/9 COMPLETED 22:46:34 (about 2 min), TPS 20.0, 0 ERROR/SEVERE, 0 "Could not project position" WARN (the downgrade is confirmed).

| Item | Result | Evidence |
|---|---|---|
| 1. Generation and log | PASS with 2 WARN | WARN 1: `town ec:area_lumina_port placed 8 houses (8 distinct; target 10/5): bounds=3555 overlap=2704 road=965 water=3135 terrain=3 last='Obstructed foundation at 28,60,221: MAGMA_BLOCK'`. WARN 2: `No safe fishing pool found for ec:area_lumina_port; continuing without a pool`. Lumina is at (-27, 64, 255), biome BEACH; the journal has plaza, spawn, pier (-11,63,217) and the other anchors but NO `fishing_water` anchor |
| 2. Cast fishing in the built pond | NOT REACHABLE on this seed (FAIL for the goal) | no pond was built (WARN 2), so there is no fishing_water. A real cast at the natural water beside the Lumina pier (-10.5, 66, 217.5, facing south) gave "No fish are visible in these waters." (01_pond_cast.png): no session started, so bite and reel were not reached. That natural water is about 66 blocks from the projected spot centre. Debug reel was verified in QA #4 |

Verdict: NO-GO for "cast fishing works at Overworld Lumina" on this seed. When the pool fallback leaves Lumina without fishing water, the fishing spot (tied to the projected centre) has no usable water, and natural water elsewhere reports "no fish visible". It needs either a seed where a pool is placed, or the spot projected onto real water, or a guaranteed pond. Screenshots are in `C:\Users\Ymii\.traycer\epics\132bc8e7-97a8-4d19-af01-669c4ba69b04\artifacts\overworld-live-qa-fishing\` (01_pond_cast, 04_spot_topdown (dark underground view), 05_pier_water_view).
Cleanup: server and client stopped, dev files and pauseOnLostFocus restored, ports closed.


## Overworld live QA #6 - BEACH seed pond fix (jar 4b8f4f7, SHA1 00D43823D93D4A378C2BDA84F5A400E5ADA96CF9), 2026-11-23
SHA1 verified in build/libs and dev/server/plugins before and after server start. `level-seed=4528502914240555028` (the QA #5 BEACH seed), `world.mode: overworld`, clean world.

| Item | Result | Evidence |
|---|---|---|
| 1. Generation + log | PASS on run 2, **run 1 FAILED (flake)** | Run 2: search 23:27:46, 9/9 COMPLETED 23:29:49 (about 2 min), 9 READY, 4 portals, TPS 20.0/19.9/19.9, 0 ERROR, 0 "Could not project position", 0 "No safe fishing pool". Remaining WARN: the Lumina house shortfall (`placed 8 houses (8 distinct; target 10/5) ... last='Obstructed foundation at 28,60,221: MAGMA_BLOCK'`). Lumina (-27,64,255) BEACH, journal `anchors.fishing_water` = (-7, 63, 245) (artificial pond; no fallback log needed). **Run 1 (identical seed and jar)**: ERROR `IllegalStateException: Road terrain changed at -480,63,44` (`OverworldTownAssembler.kt:446` via `OverworldWorldService.applyOperations`) while building Ashwood; journal stayed IN_PROGRESS (Lumina READY, Ashwood GENERATING, 7 PENDING) and generation stopped. A restart on a fresh world with the same seed completed, so the road check is intermittent (a block changed between planning and applying, e.g. a natural update such as water/vegetation or another chunk load) |
| 2. Real cast at `fishing_water` | **PASS** | (tested on run 1's world, where Lumina was READY) the pond is built and grounded beside the plaza (01_pond_overview). Right-click on the water: bobber `ItemDisplay` appears, BossBar "Searching for fish...", ActionBar "Tension 32% / Progress 0% Right-click to pull." (02_cast_active), `ec debug fish status`: `phase=WAITING ... bossbar=true`. Bite: title "Bite! Right-click to hook it!" then BossBar "Fighting [Steady] Progress 4%" (03). First attempt escaped (late hook, `ESCAPED_BITE`); second attempt reeled to `ended reason=CAUGHT fish=ec:fish_sky_salmon progress=100%`. No vanilla bobber/catch seen; the vanilla-fishing suppression was not tested separately. |

Verdict: GO for the pond fix (fishing_water now exists and casting works end to end on the BEACH seed), with one open issue: intermittent `Road terrain changed` aborts generation and leaves the journal IN_PROGRESS (needs retry/tolerance and a recovery message). Screenshots: `C:\Users\Ymii\.traycer\epics\132bc8e7-97a8-4d19-af01-669c4ba69b04\artifacts\overworld-live-qa-fishing-v2\` (01_pond_overview, 02_cast_active, 03_bite_or_reeling).
Cleanup: server/client stopped, dev world/plugins/server.properties (level-seed restored)/ops/whitelist/config and pauseOnLostFocus restored, ports closed.


## Overworld generation smoke QA #7 (master 4bd75c1, jar SHA1 82FC49831E0BD44C124A54E2EFB736641377CD08), 2026-11-24
Server only, clean world per run, `world.mode: overworld`; SHA1 matched before start, after start and after restore. Script: `scripts/client/qa-ow-genrun.ps1`.

| Run | Seed | Result | Details |
|---|---|---|---|
| 1 | 4528502914240555028 (BEACH) | **PASS** | 01:52:38 -> 01:54:32 (~1m54s), COMPLETED, 9 READY, 4 portals, 0 ERROR, 0 "Could not project", 0 "No safe fishing pool", TPS 20.0/19.9/19.9. Lumina (-27,64,255) BEACH, fishing_water (-7,63,245). Only WARN: Lumina 8 houses (target 10/5) |
| 2 | random (-4201517772407501229) | **FAIL (intermittent abort)** | after ~1m41s: `ERROR Overworld construction failed: Road clearance changed at 364,61,-242: expected LEAF_LITTER, got WATER`; journal IN_PROGRESS, 3 READY, 2 portals. Lumina (93,62,160) SPARSE_JUNGLE with fishing_water (113,64,140). A different re-check ("road clearance", not "road terrain") with a natural block transition (leaf litter replaced by water, i.e. flow/replacement), which the new transition list does not cover |
| 3 | random (339731399320507952) | **PASS** | 01:58:16 -> 02:00:14 (~1m58s), COMPLETED, 9 READY, 4 portals, 0 ERROR, 0 WARN of interest, TPS 20.0/19.9/19.9. Lumina (338,63,66) PLAINS, fishing_water (328,62,96) |

Verdict: PARTIAL / NO-GO for "no more intermittent aborts": the QA #6 repro seed now passes, but a new random seed hit another actions re-check (`Road clearance changed ... expected LEAF_LITTER, got WATER`). Suggest treating decorative/replaceable blocks (leaf litter, flowers, snow layers, vegetation) vs water/air as tolerable in the clearance check, or re-planning that road tile instead of aborting the whole generation. Run 2's world was not kept, so the failing site/stack is not recorded (only the message).
Cleanup: server stopped, dev world, plugin data, server.properties (level-seed blank), ops, whitelist, config restored; ports closed.

## Overworld generation smoke QA #8 (master 6922c90, jar SHA1 6C22BFCD988F8FC353EBFAA64B0330767DB117EC), 2026-11-24
Server only, clean world per run, `world.mode: overworld`; SHA1 matched before start, after start and after restore (`scripts/client/qa-ow-genrun.ps1`).

| Run | Seed | Result | Details |
|---|---|---|---|
| 1 | -4201517772407501229 (QA #7 failing seed) | **PASS** | 02:26:41 -> 02:28:49 (~2m08s), COMPLETED, 9 READY, 4 portals, 0 ERROR, 0 "Could not project", 0 "No safe fishing pool", no Road clearance/terrain errors, TPS 20.0/19.9/19.9. Lumina (93,62,160) SPARSE_JUNGLE, fishing_water (113,64,140) |
| 2 | random (-9169136779061013934) | **PASS** | 02:29:50 -> 02:31:45 (~1m55s), COMPLETED, 9 READY, 4 portals, 0 ERROR, 0 projection/pool WARN, TPS 20.0/19.9/19.9. Lumina (73,64,96) PLAINS, fishing_water (83,65,76) |

Verdict: GO (2/2). Combined with QA #7 run 3 this is 3 consecutive clean generations after the transition fixes; the sample is small, so intermittent aborts are not ruled out.
Cleanup: server stopped, dev world/plugin data/server.properties (level-seed blank)/ops/whitelist/config restored, ports closed.

## Overworld generation smoke QA #9 — 8 random seeds batch (master 6922c90, jar SHA1 6C22BFCD988F8FC353EBFAA64B0330767DB117EC), 2026-11-24
Server only, clean world per run, world.mode: overworld; 8 fresh random 64-bit seeds excluding previously tested seeds (qa-ow-genrun.ps1).

| Run | Seed | Result | Elapsed | Lumina | TPS (1m/5m/15m) | Details |
|---:|---|---|---:|---|---|---|
| 1 | -4731718650747399906 | **PASS** | 179.3s | (-171,63,-283) PLAINS, pond (-181,63,-253) | 20.0 / 19.9 / 19.9 | COMPLETED, 9 READY, 4 portals, 0 ERROR, 0 WARN |
| 2 | 7953258711525688605 | **PASS** | 167.7s | (23,62,318) FOREST, pond (31,64,356) | 20.0 / 20.0 / 20.0 | COMPLETED, 9 READY, 4 portals, 0 ERROR, 0 WARN |
| 3 | -1416912842524785962 | **PASS** | 177.6s | (-85,63,-330) RIVER, pond (-65,63,-340) | 20.0 / 20.0 / 20.0 | COMPLETED, 9 READY, 4 portals, 0 ERROR, 0 WARN |
| 4 | 4146423064076284222 | **PASS** | 177.6s | (-176,68,286) PLAINS, pond (-186,63,256) | 20.0 / 19.9 / 19.9 | COMPLETED, 9 READY, 4 portals, 0 ERROR, 0 WARN |
| 5 | -6109928817052901113 | **PASS** | 187.6s | (-182,68,316) TAIGA, pond (-192,69,296) | 20.0 / 20.0 / 20.0 | COMPLETED, 9 READY, 4 portals, 0 ERROR, 0 WARN |
| 6 | -2819269622671616182 | **PASS** | 187.6s | (261,63,-37) RIVER, pond (271,64,-57) | 20.0 / 20.0 / 20.0 | COMPLETED, 9 READY, 4 portals, 0 ERROR, 0 WARN |
| 7 | -4167603268941691095 | **FAIL** | 167.8s | (145,64,1) BEACH, pond (163,63,9) | 20.0 / 19.9 / 19.9 | IN_PROGRESS, 4 READY, 2 portals; ec:area_void_sanctum at (-510,88,-800) DARK_FOREST failed during planning: No grounded plot for entrance (entrance_void_sanctum) at ec:area_void_sanctum: bounds=0 overlap=0 road=0 water=0 terrain=169 portal=0 last='Obstructed foundation at -498,87,-788: DARK_OAK_PLANKS' (also Lumina house shortfall WARN: 8/10) |
| 8 | -8424225939849412953 | **PASS** | 197.7s | (-69,63,-29) BEACH, pond (-51,62,-51) | 20.0 / 19.9 / 19.9 | COMPLETED, 9 READY, 4 portals, 0 ERROR, 0 projection/pool WARN (Lumina house shortfall WARN: 7/10) |

Verdict: **7/8 PASS** (0 Road terrain changed / Road clearance changed / projection / pool errors across all 8 runs; 11 consecutive runs with 0 natural-drift aborts since QA #7 run 3).
New finding from Run 7 (repro seed -4167603268941691095):
1. OverworldSiteSearch.sample() checks surface.isSolid && surface != ICE/PACKED_ICE/BLUE_ICE without requiring natural ground (OverworldMaterials.ground), so a flat vanilla structure roof (e.g. Woodland Mansion DARK_OAK_PLANKS at Y=88 in DARK_FOREST) is accepted as 	ier=1 dry land.
2. OverworldTownAssembler.planBuildings() restricts the first building (index == 0, e.g. entrance_void_sanctum) exclusively to centerCandidates(site) (±24 blocks, Rotation.NONE only = 169 tries) with no fallback to plotCandidates(site, random).
Cleanup verified: 0 Paper java processes, ports 25565/25580 closed, 0 *.qa-* leftovers, level-seed= blank, source JAR SHA1 unchanged.

## Overworld live QA #10 - site search natural-surface filter + first-building plot fallback (master f200eb3, JAR SHA1 ED8A05D5CD5D4F6BCD20A8B39A33AE0F6DF55700), 2026-11-24
Automated verification: `./gradlew.bat test build --no-daemon` PASS (418 tests, 0 failures, 0 errors, 2 pre-existing skips); independent Codex review (`gpt-6-luna` / `low`, commits `82d3837` + `f200eb3`) verdict **GO**.
Method: 2 clean `world.mode: overworld` runs on real Paper 26.3-257 via `qa-ow-genrun.ps1` (Run 1: QA #9 Run 7 repro seed `-4167603268941691095`; Run 2: fresh random 64-bit seed `-314835210179884212`), full server/world/config restore after each run. Source JAR SHA1 `ED8A05D5CD5D4F6BCD20A8B39A33AE0F6DF55700` verified.

| Run | Seed | Result | Wall Time | Lumina / Fishing Water | Void Sanctum (5/9) | TPS (1m/5m/15m) | Evidence |
|---|---|---|---|---|---|---|---|
| 1 (repro) | `-4167603268941691095` | **PASS** | 179.2s | (145,64,1) BEACH, pond (163,63,9) | (158,67,899) FOREST, buildings=2, portals=1 | 20.0 / 19.9 / 19.9 | COMPLETED, 9 READY, 4 portals, 0 ERROR, 0 projection/pool WARN (Lumina house shortfall WARN: 8/10, 8 distinct). Previous QA #9 Woodland Mansion chunk `(-510,88,-800)` was skipped by `OverworldSiteSearch.sample()`. |
| 2 (random) | `-314835210179884212` | **PASS** | 177.7s | (-327,63,-65) SUNFLOWER_PLAINS, pond (-347,63,-55) | (349,71,-156) DARK_FOREST, buildings=2, portals=1 | 20.0 / 19.9 / 19.9 | COMPLETED, 9 READY, 4 portals, 0 ERROR, 0 WARN. |

Verdict: **2/2 PASS** ? QA #9 Run 7 repro seed `-4167603268941691095` now completes 9/9 READY with 4 portals and 0 ERROR.
Cleanup verified: 0 Paper java processes, ports 25565/25580 closed, 0 `*.qa-*` leftovers, `level-seed=` blank, source JAR SHA1 unchanged (`ED8A05D5CD5D4F6BCD20A8B39A33AE0F6DF55700`).

## Overworld QA #11 / #11b & P2 fixes (master e49eecf, SHA1 AA840F82E15A6B7CA1972BFD2D3354A9577B3D91)

Date: 2026-11-24
Commit: `e49eecf` (`fix(p2): enforce shop distance guard, verify resourcepack assets, flush overworld chunks before READY`)
Automated suite: `./gradlew.bat test build --no-daemon` PASS (421 tests, 0 failures, 0 errors, 2 pre-existing skips); Codex spot review (`gpt-6-luna` / `low`, archived) verdict **GO**.
Resource pack: removed orphaned `resourcepack/assets/ec/textures/gui/transparent_slot.png` (new pack SHA-1: `797520bd4ca3eea30f46c83a98b58042dde5853d`); full asset integrity verified by `WorldUiFoundationTest`.

Live Paper 26.3 smoke (`world.mode: overworld`, verifying `world.save(true)` before `READY`):

| Run | Seed | Verdict | Elapsed | Lumina | TPS (1m/5m/15m) | Notes |
|---|---|---|---|---|---|---|
| QA #11 (random) | `8550623988298585625` | **FAIL (pre-existing site edge case)** | 199.2s | (341,70,-138) PLAINS, pond (361,67,-118) | 19.8 / 19.8 / 19.8 | `world.save(true)` succeeded across all 8 generated sites (1/9-8/9, 3 portals, 0 projection/pool WARN; Paper logged expected one-time manual-save WARN). Site 9/9 `ec:area_glacial_citadel` selected `BEACH` at `y=62` `(914,62,-1381)` and failed with `No grounded plot for entrance (entrance_glacial_citadel): bounds=188 water=246 terrain=3 portal=12 last='Unsafe portal exit'` (unrelated to P2). |
| QA #11b (A/B baseline) | `-314835210179884212` | **PASS** | 119s gen | (-327,63,-65) SUNFLOWER_PLAINS, pond (-347,63,-55) | 19.8 / 19.8 / 19.8 | COMPLETED, 9/9 READY, 4 portals, 0 ERROR, 0 projection/pool WARN. `world.save(true)` completed cleanly before every `READY` write without TPS regression vs QA #10 baseline. |

Cleanup verified after both runs: 0 Paper java processes, ports 25565/25580 closed, 0 `*.qa-*` leftovers, `level-seed=` blank.

## Overworld QA #12 - coastal dungeon & Lumina house packing fixes (master 14e7f60 + ce08cb4)

Date: 2026-11-24
Commits: `14e7f60` (`fix(overworld): avoid sea-level coastal sites, add entrance rotation and fine-grid house fallbacks`) + `ce08cb4` (`fix(overworld): isolate palette RNG on fallback candidate batches`)
Automated suite: `./gradlew.bat test build --no-daemon` PASS (425 tests, 0 failures, 0 errors, 2 pre-existing skips); Codex spot review (`gpt-6-luna` / `low`, archived) verdict **GO**.
Live Paper 26.3 smoke ran on `14e7f60` (JAR SHA-1 `EB7B9AC8831C86149DA76BE74D35ACAB10619088`; follow-up `ce08cb4` isolated fallback palette RNG only, JAR SHA-1 `95768833048638956A94338B1112E57127194366`):

| Run | Seed | Verdict | Elapsed | Lumina | Glacial Citadel | TPS (1m/5m/15m) | Notes |
|---|---|---|---|---|---|---|---|
| 1 (QA #11 repro) | `8550623988298585625` | **PASS** | 209.1s | (341,70,-138) PLAINS, buildings=23, pond (371,65,-118) | (-727,77,114) tier=2 FOREST, buildings=4, portals=1 | 19.9 / 19.9 / 19.9 | COMPLETED, 9/9 READY, 4 portals, 0 ERROR, 0 projection/pool/house WARN. Cold cluster avoided sea-level `BEACH (y=62)`. |
| 2 (Lumina BEACH repro) | `-4167603268941691095` | **PASS** | 187.5s | (145,64,1) tier=3 BEACH, buildings=20, pond (163,63,9) | (1176,105,755) tier=1 GROVE, buildings=4, portals=1 | 19.8 / 19.8 / 19.8 | COMPLETED, 9/9 READY, 4 portals, 0 ERROR, 0 projection/pool/house WARN. Same `(145,64,1) BEACH` coordinate improved from 8/10 houses (QA #9/#10 WARN) to 10/10 houses via 4-block fine-grid fallback. |

Verdict: **2/2 PASS** - both QA #11 `BEACH (y=62)` dungeon failure and QA #9/#10 `Lumina` coastal house shortfall WARN resolved with 0 ERROR and 0 WARN.
Cleanup verified: 0 Paper java processes, ports 25565/25580 closed, 0 `*.qa-*` leftovers, `level-seed=` blank.

## Overworld QA #13 - 5-random-seed regression batch (master ce08cb4, SHA1 95768833048638956A94338B1112E57127194366)

Date: 2026-11-24
Commit: `ce08cb4` (includes `14e7f60` coastal dungeon/Lumina fixes + fallback palette RNG isolation + `e49eecf` P2 `world.save(true)`)
Method: 5 clean `world.mode: overworld` runs on real Paper 26.3-257 via `qa-ow-genrun.ps1` with fresh 64-bit random seeds.

| Run | Seed | Verdict | Elapsed | Lumina | Glacial Citadel | TPS (1m/5m/15m) | Notes |
|---|---|---|---|---|---|---|---|
| 1 | `-9071878686750665752` | **PASS** | ~178s | (157,66,258) JUNGLE, buildings=22 | (67,63,984) tier=2 FOREST, buildings=4, portals=1 | 19.7 / 19.8 / 19.8 | COMPLETED, 9/9 READY, 4 portals, 0 ERROR, 0 WARN. |
| 2 | `4703186492793982268` | **PASS** | 198.1s | (156,70,11) SNOWY_PLAINS, buildings=25, pond (146,69,-19) | (-112,63,903) tier=1 SNOWY_PLAINS, buildings=4, portals=1 | 19.9 / 19.9 / 19.9 | COMPLETED, 9/9 READY, 4 portals, 0 ERROR, 0 WARN. |
| 3 | `-1187416165039992445` | **PASS** | 177.6s | (-141,63,232) TAIGA, buildings=24, pond (-163,67,210) | (-631,97,-791) tier=2 FOREST, buildings=4, portals=1 | 19.6 / 19.8 / 19.8 | COMPLETED, 9/9 READY, 4 portals, 0 ERROR, 0 WARN. |
| 4 | `2827251538875287594` | **PASS** | 177.6s | (203,63,-51) OLD_GROWTH_BIRCH_FOREST, buildings=22, pond (173,67,-31) | (380,101,-755) tier=2 MEADOW, buildings=4, portals=1 | 19.7 / 19.8 / 19.8 | COMPLETED, 9/9 READY, 4 portals, 0 ERROR, 0 WARN. |
| 5 | `1181984613099147861` | **PASS** | 167.6s | (-29,68,-234) MUSHROOM_FIELDS, buildings=22, pond (-101,70,-256) | (-1347,66,-400) tier=2 SAVANNA, buildings=4, portals=1 | 19.8 / 19.8 / 19.8 | COMPLETED, 9/9 READY, 4 portals, 0 ERROR, 0 WARN. |

Verdict: **5/5 PASS** (7/7 consecutive PASS combined with QA #12 repro seeds) - 9/9 READY, 4 portals, 0 ERROR, 0 house/pool/projection WARN across all 5 seeds, TPS 19.6-19.9.
Cleanup verified: 0 Paper java processes, ports 25565/25580 closed, 0 `*.qa-*` leftovers, `level-seed=` blank, source JAR SHA1 unchanged (`95768833048638956A94338B1112E57127194366`).
