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

