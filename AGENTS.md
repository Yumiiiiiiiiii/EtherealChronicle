# AGENTS.md — EtherealChronicle (PaperMC MMORPG plugin)

Target: Paper 26.3 (build 257 STABLE), Java 25, Kotlin 2.4.21, Gradle 9.9.2 (wrapper). Plugin id `EtherealChronicle`, base package `net.nakaymi.etherealchronicle`.
Build: `./gradlew build` -> `build/libs/EtherealChronicle-<ver>.jar`. Test server: `dev/server` (Paper jar present; EULA NOT auto-accepted).

## Roles
- Claude (lead): design, integration, balance, release judgement. Owns docs/*.
- Codex (gpt-6-luna, low): main implementation.
- Antigravity (gemini-4-neon, medium): QA, tests, server integration scripts, independent review.
Implementer != reviewer. Work on separate git branches/worktrees; merge via lead. Never edit files owned by another active task.

## Architecture rules
- Data-driven: items, classes, skills, enemies, bosses, NPCs, quests, dialogues, chapters, drop tables, shops, balance all live as YAML under `src/main/resources/content/<type>/*.yml` (copied to plugin data folder `content/` on first run; server-side files override). Each file has `schema_version` and stable `id`s (snake_case, namespaced `ec:xxx`). Validator checks duplicate ids, dangling references, ranges.
- Separate pure game logic (package `net.nakaymi.etherealchronicle.core.*`, NO org.bukkit imports, unit-testable) from Paper glue (`net.nakaymi.etherealchronicle.paper.*`).
- Player data: per-UUID YAML/JSON file in `playerdata/`, async save, atomic write (tmp+rename), backup, schema version + migrations. Never block main thread with IO.
- All game text Japanese (Adventure Components, MiniMessage). Identifiers English.
- Economy/inventory ops must be transactional (no dupe). XP/currency gains capped by balance config.
- Keep TPS safe: no per-tick scans of all entities; use scheduler wisely; clean up tasks on disable.
- Every feature needs unit tests in `src/test/kotlin`. Do not claim "tested" if not run.

## Package map
core.stats / core.combat / core.skill / core.item / core.quest / core.economy / core.party / data (loader, validator, migration) / paper.* (listeners, commands, gui, npc, boss, dungeon).

## Status files
docs/AGENT_STATUS.md must be updated by each agent at task end (what done, what verified, what open).

## Context hygiene (all agents)
- When your context/token usage grows large (roughly >60% of the window, or the harness warns), compact proactively instead of waiting for overflow.
- Before compacting: write a checkpoint to docs/AGENT_STATUS.md (done / verified / open / branch+commit hash / next step). Commit WIP locally.
- Compact with a summary that keeps: current task, branch/commit, open findings, exact next action. Drop raw logs, full diffs and file dumps; re-read files from disk afterwards.
- Never paste large logs/diffs into messages; write them to docs/ or the scratchpad and send the path.
