# Chapter 2 Engine Requests (For Codex Implementation)

Chapter 2 (`ec:chapter_2`「凍てつく蒼穹の星片」) is fully authored using the existing v1 YAML schema (`docs/CONTENT_SCHEMA.md`) and passes `ContentService` / `WorldValidation` / `Balance` cap checks as-is. The following runtime extensions are **not** implemented on `ag/chapter2` (content-only branch) and are requested for Codex to implement so Chapter 2 plays end-to-end in-world:

---

## 1. [Required] Data-Driven Multi-Dungeon Routing in `DungeonController`
- **Current limitation**: `DungeonController.enter(player)` (`src/main/kotlin/net/nakaymi/etherealchronicle/paper/DungeonController.kt`) ignores the `ENTER_DUNGEON` action's `target` ID and hardcodes Chapter 1's sanctum:
  - unlock flag: `ec:flag_sanctum_unlocked`
  - boss: `ec:boss_void_warden`
  - guards: `ec:enemy_void_sentinel`
  - explore area: `ec:area_void_sanctum`
  - arena palette: `DEEPSLATE_TILES` / `POLISHED_BLACKSTONE_BRICKS`
- **Requested change**:
  - Pass `dungeonId: String` from `AdventureController` (`ENTER_DUNGEON` `a.target`) and `/ec dungeon enter [dungeonId]` into `DungeonController.enter(player, dungeonId)`.
  - Support per-dungeon definitions (either in `chapters/*.yml` or a `dungeons/*.yml` registry) so `ec:dungeon_glacial_citadel` resolves:
    - unlock flag: `ec:flag_ch2_citadel_unlocked`
    - boss: `ec:boss_cryo_archon`
    - guards: `ec:enemy_glacial_knight` (×2)
    - explore area: `ec:area_glacial_citadel`
    - arena palette: `PACKED_ICE` / `SNOW_BLOCK` / `PRISMARINE_BRICKS`
    - localized entry/clear messages ("蒼穹の氷城へ入場しました" / "氷核の執行者を撃破しました！").

## 2. [Required] `DemoWorld` Terrain & Bridge Generation for Chapter 2 Islands
- **Current limitation**: `DemoGenerator` (`src/main/kotlin/net/nakaymi/etherealchronicle/paper/DemoWorld.kt`) only generates islands `0..4` (`x = 0, 96, 192, 288, 384`, bridge `x in -36..420`).
- **Requested change**:
  - Extend `DemoGenerator` to generate islands `5..8` at `x = 480, 576, 672, 768` (`z = 0`, `±36` width) and bridge `x in -36..804` (`abs(z) <= 3`) using icy materials (`SNOW_BLOCK` / `PACKED_ICE` / `BLUE_ICE` / `PRISMARINE_BRICKS`).
  - Bump `.etherealchronicle-built` marker (`v1` -> `v2`) so `DemoWorld.build()` builds the Chapter 2 outpost/ridge/cavern/citadel landmarks idempotently on existing `ec_world` saves without overwriting Chapter 1 islands.

## 3. [Optional / Design Decision] Future Level Cap Expansion (`Lv20 -> Lv30`)
- **Current design**: Chapter 2 strictly complies with `docs/BALANCE.md` (`level_cap: 20`, `stat_cap: 100000`, `player_hp_cap: 1024`, `global_enemy_cap: 100`) as a **Lv16–20 post-Chapter 1 endgame progression chapter** (enemies Lv17–19, Mid-Boss Lv19 28,000 HP, Final Boss Lv20 76,000 HP, gear Lv17–20; combined Ch1+Ch2 spawn cap `52 + 22 = 74 <= 100`).
- **Requested coordination (only if lead chooses Lv21–30 later)**:
  - Raising `level_cap` beyond 20 requires coordinated updates to `balance/default.yml` (`level_cap`, `stat_cap`, `player_hp_cap`, `global_enemy_cap`) and existing tests asserting `levelCap` / `(1..19).sumOf(requiredXp) == 81435` (`FoundationTest`, `RegressionTest`, `BalanceSimulationTest`).

## 4. [Nice-to-Have] Fast-Travel Dialogue Action (`TELEPORT_AREA`)
- **Current limitation**: `RETURN` consumable effect teleports only to `ec_world` spawn `(0, 65, 0)`; travelling from `ec:npc_freya` in Lumina Port `(18, 65, -12)` to `ec:area_frostpeak_outpost` `(480, 65, 0)` requires walking across all Chapter 1 bridges.
- **Requested change**:
  - Add a `TELEPORT_AREA` dialogue action (`target: area_id`, optional destination coords) so `ec:npc_freya` and `ec:npc_orphe` can ferry players by airship between Lumina Port and Frostpeak Outpost once `ec:flag_ch2_started` is set.

## 5. [Nice-to-Have] Elemental Telegraph Particles & `FREEZE` Effect
- **Current limitation**: `EnemyController.draw()` renders all telegraphs with red `Particle.DUST`, and `SpecialAttack.effect` only supports `NONE|SLOW|POISON|KNOCKUP` (Chapter 2 currently uses `SLOW` and `KNOCKUP`).
- **Requested change**:
  - Color telegraph dust by enemy/attack element (e.g., cyan/white for `ICE`, purple for `VOID`) and optionally add a short `FREEZE` (root/frost-tick) effect kind to `WorldContent.kt` and `EnemyController.kt`.
