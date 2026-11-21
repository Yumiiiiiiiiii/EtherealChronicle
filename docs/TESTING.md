# TESTING

Owner: Claude-QA (branch `qa/harness`). Status values: **NOT-RUN** (default), PASS, FAIL, BLOCKED. Nothing below may be marked PASS without a dated log/evidence reference.

## Strategy (layers)
1. **Unit tests** (`src/test/kotlin`, JUnit 5 + kotlin.test): all `core.*` logic and `data` loader/validator/migration. No Bukkit. Fixtures in `dev.mcmmo.rpg.qa` (`FakeClock`, `ContentYaml`, `TempDir`, `seededRandom`, `FakePlayers`). Run: `./gradlew test`.
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
- **Known gap**: the newest protocol/data version in `minecraft-data@3.119.0` / `minecraft-protocol` is **26.1**. Target is Paper **26.3**. Mineflayer will most likely fail to handshake/parse (protocol mismatch) until upstream adds 26.3. Not yet verified live because the server could not be started (EULA not accepted by a human). Run `node bot.js --version 26.1` against the server once it is up; if it connects, record it here, otherwise treat bot-driven items below as BLOCKED and cover them via console commands, plugin-side debug commands, and manual play.
- Fallbacks: add an admin/debug command set in the plugin (e.g. `/ec debug simulate <player> ...`) so flows can be driven from console without a client; unit-test the pure core for the logic.
- **GUI / computer-use**: the QA agent has shell, file, an in-app browser (web only) and Chrome devtools tools. No Minecraft client is installed/driven, and no desktop computer-use tools were exposed in this session. Visual/GUI checks (inventory menus, ActionBar, boss bars) are therefore manual-only.

## Checklist
Each item: scope, how to verify, status. Environment for all server items: Paper 26.3-257, offline mode.

| # | Item | Verify by | Status |
|---|---|---|---|
| 0 | Unit tests (`./gradlew test`) | CI-style run | PASS for QA fixtures only (4 tests, 2026-11-21); feature tests NOT-RUN |
| 1 | Server start | smoke-test reaches `Done`, no exceptions | NOT-RUN (blocked: EULA) |
| 2 | Plugin load/enable | log has `Enabling EtherealChronicle`, no load errors; `/plugins` green | NOT-RUN |
| 3 | `ec validate` / `ec reload` | console output reports 0 errors; reload atomic (bad YAML keeps old content) | NOT-RUN |
| 4 | First join | new UUID gets profile file in `playerdata/`, welcome text (JP), spawn in Lumina | NOT-RUN |
| 5 | Class select | choose each of 3 classes; stats/resource set; cannot re-pick without reset | NOT-RUN |
| 6 | Tutorial | steps advance in order, skip works, rewards granted once | NOT-RUN |
| 7 | NPC interaction | dialogue opens, branches, no duplicate spawn after restart | NOT-RUN |
| 8 | Quest | accept -> progress -> complete -> reward once; abandon; prerequisite gating | NOT-RUN |
| 9 | Combat | damage formula, DEF, crit, caps; PvE only in zones; no per-tick entity scans (spark/TPS) | NOT-RUN |
| 10 | Skills | slot 9 / sneak+RClick select, cooldown, resource cost, ActionBar | NOT-RUN |
| 11 | Level up | XP cap per balance config, stat growth, level cap 20 | NOT-RUN |
| 12 | Items | generation, rarity/affix budget, lore in JP, drops from tables | NOT-RUN |
| 13 | Equip | equip/unequip updates stats, level/class requirements, no stat stacking exploit | NOT-RUN |
| 14 | Shop | buy/sell transactional, insufficient funds, full inventory, no dupe on rapid clicks/disconnect | NOT-RUN |
| 15 | Party | invite/accept/leave/kick, XP share, disband on leader quit | NOT-RUN |
| 16 | Boss | spawn, phases, loot per participant, reset on wipe, cleanup of tasks/entities | NOT-RUN |
| 17 | Chapter 1 clear | full chain Lumina -> Ashwood -> mine -> boss -> ending flag set | NOT-RUN |
| 18 | Reconnect | quit mid-fight/quest and rejoin: state intact, cooldowns sane, no dupe | NOT-RUN |
| 19 | Restart persistence | stop server, restart: profile, quests, inventory, currency identical; kill -9 mid-save leaves valid file (tmp+rename) / backup recovers | NOT-RUN |
| 20 | Disable/reload cleanliness | `/reload`-less stop: no leaked schedulers, all profiles flushed, no exceptions | NOT-RUN |
| 21 | Performance | 10 simulated players / 100 mobs: tick time within budget | NOT-RUN |

## Regression rules
- Every bug found gets a unit test where it is expressible in `core.*`; otherwise a checklist row/note.
- Reviewer != implementer. Record findings in `docs/AGENT_STATUS.md`.
