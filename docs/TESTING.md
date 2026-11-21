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

## Checklist
Each item: scope, how to verify, status. Environment for all server items: Paper 26.3-257, offline mode.

| # | Item | Verify by | Status |
|---|---|---|---|
| 0 | Unit tests (`./gradlew test`) | CI-style run | PASS (2026-11-21, codex/foundation b7f13e3 merged): `./gradlew test` = 21 tests, 0 failures (17 Codex + 4 QA fixtures). Real-Paper behavior covered below only where stated |
| 1 | Server start | smoke-test reaches `Done`, no exceptions | PASS: Paper 26.3-257 reaches `Done (6.9s)` offline (smoke-20261121-120318.log) |
| 2 | Plugin load/enable | log has `Enabling EtherealChronicle`, no load errors; `/plugins` green | PASS: `Enabling EtherealChronicle v0.1.0`, `冒険システムの準備が完了しました。`, no WARN/ERROR from plugin; default content copied to `plugins/EtherealChronicle/content` |
| 3 | `ec validate` / `ec reload` | console output reports 0 errors; reload atomic (bad YAML keeps old content) | PASS: `ec validate` -> `コンテンツ検証成功。`; `ec reload` -> `コンテンツを再読み込みしました。`. Atomic-keep-old-on-bad-YAML NOT-RUN live (unit-tested by Codex only) |
| 4 | First join | new UUID gets profile file in `playerdata/`, welcome text (JP), spawn in Lumina | NOT-RUN (needs real client; bot BLOCKED). Only simulated: `sim join qa` creates a profile |
| 5 | Class select | choose each of 3 classes; stats/resource set; cannot re-pick without reset | PARTIAL: simulated `sim class qa ec:mage` -> resource 100, skills ranked 1, tutorial quest assigned; re-select rejected (`職業は既に選択されています`). GUI menu NOT-RUN |
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
| 18 | Reconnect | quit mid-fight/quest and rejoin: state intact, cooldowns sane, no dupe | NOT-RUN (needs client) |
| 19 | Restart persistence | stop server, restart: profile, quests, inventory, currency identical; kill -9 mid-save leaves valid file (tmp+rename) / backup recovers | PASS for simulated profile: save -> stop server -> restart -> `sim load qa` returned identical level/xp/currency/resource/quests/skills; second save kept a valid `.yml` + `.bak`. Real-player persistence and kill -9 mid-save NOT-RUN |
| 20 | Disable/reload cleanliness | `/reload`-less stop: no leaked schedulers, all profiles flushed, no exceptions | PASS (partial): clean `stop` shows `Disabling EtherealChronicle` with no exceptions in 3 runs; leaked-scheduler check NOT-RUN |
| 21 | Performance | 10 simulated players / 100 mobs: tick time within budget | NOT-RUN |

## Regression rules
- Every bug found gets a unit test where it is expressible in `core.*`; otherwise a checklist row/note.
- Reviewer != implementer. Record findings in `docs/AGENT_STATUS.md`.

