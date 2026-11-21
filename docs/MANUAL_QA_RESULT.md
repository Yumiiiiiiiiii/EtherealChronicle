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
