# AGENTS.md — EtherealChronicle (PaperMC MMORPG plugin)

Target: Paper 26.3 (build 257 STABLE), Java 25, Kotlin 2.4.21, Gradle 9.9.2 (wrapper). Plugin id `EtherealChronicle`, base package `net.nakaymi.etherealchronicle`.
Build: `./gradlew build` -> `build/libs/EtherealChronicle-<ver>.jar`. Test server: `dev/server` (Paper jar present; EULA NOT auto-accepted).

## Roles & Balanced Harness Rotation
- Distribute work evenly across **`codex`**, **`claude`**, and **`antigravity`** so no single harness dominates.
  - **All three harnesses** share implementation, refactoring, test writing, QA, and review via rotation rather than pinning all implementation to one harness.
  - When running parallel tasks, assign each concurrent task to a **different harness** (`codex`, `claude`, `antigravity`) and check rate-limit headroom before choosing.
  - Typical balanced model configs:
    - `codex`: `gpt-6-sol` (`low`/`medium`), `gpt-6-luna` (`high` for tests), or `gpt-6.1-terra` / `gpt-6.1-sol` for heavy work.
    - `claude`: `sonnet` (`medium`/`high`), or `opus` for heavy work.
    - `antigravity`: `gemini-4-neon` (`medium`/`high`), `gemini-4-hydrogen` (`high`), or `gemini-3.8-flash` for light tasks.
- Lead coordinates integration, balance, release judgement, and `docs/*` consolidation.
- `Implementer != Reviewer` (mandatory). Work on separate git branches/worktrees; merge via lead. Never edit files owned by another active task.

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

## Code readability (all agents)
- Write comments in Japanese so a human can follow the code: a short KDoc/doc comment on every public class and non-trivial function (what it does and why), and inline comments for non-obvious rules (transaction ordering, locks, balance caps, migration steps). Do not narrate trivial code.
- Keep functions and files human-sized: split long functions, name things by intent, avoid deep nesting and duplicated logic. Refactors must preserve behavior: run the full test suite before and after and compare results; commit per package/area so a regression is easy to bisect.

## Cross-model review loop (all agents, mandatory)
- After any agent writes or changes code, EVERY model that did not write it must review it (findings only, no edits to the author's files). Look beyond critical bugs and cover ALL of the following lenses:
  1. **Correctness & Safety**: bugs, race conditions, transaction/dupe/loss risks, main-thread IO, security/permissions, test gaps (incl. MockBukkit vs real Paper).
  2. **Performance & Efficiency**: not just TPS spikes, also micro-optimizations and algorithmic improvements — unnecessary allocations/copies in hot paths or event handlers, repeated parsing (MiniMessage/regex/config), O(n) linear scans that should be Map/Set lookups, cacheable computations, redundant serialization/IO.
  3. **Idiomatic Writing & Awkwardness (書き方の違和感)**: code that works without bugs yet feels clunky, roundabout, or un-idiomatic for Kotlin/Paper — unnecessary mutable state, awkward null handling/casts, verbose loops instead of standard Kotlin idioms, inconsistent patterns across similar files, missing or misleading Japanese comments, leftover hardcoded values.
  4. **Visual Cleanliness & Better Alternatives (見栄え・より良い書き方)**: ways to rewrite working code so it reads cleaner and communicates intent at a glance — early returns, expression bodies, declarative/table-driven style, helper extraction, simplifying conditionals, unifying duplicate UI/builder patterns.
- Each reviewer writes findings to `docs/reviews/<date>_<branch>_<reviewer>.md` with: severity (`High` / `Med` / `Low` / `Polish`), category (`Bug` / `Perf` / `Idiom` / `Style` / `Security` / `Test`), `file:line`, what feels wrong or suboptimal, why, and a concrete suggested alternative (with a short Before/After snippet when proposing a cleaner or faster way to write it).
- The lead consolidates all review docs into one prioritized fix list.
- The fix list goes back to the author (or another implementer) to fix in a new commit; then the reviewers re-check the fixed items. A change is not done until the fix list is empty or each remaining item is recorded as accepted with a reason.
