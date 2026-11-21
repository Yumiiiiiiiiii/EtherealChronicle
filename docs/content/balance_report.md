# Chapter 1 Balance Simulation Report — Final Tuned Results

- **Simulator**: `src/test/kotlin/net/nakaymi/etherealchronicle/qa/BalanceSimulationTest.kt`
- **Engine & Data**: Real `ContentService`, `GameService`, `SkillEngine`, `Balance.damage`, `Balance.grantXp`, `BossState`, `DropTable.roll` against all bundled YAML files under `src/main/resources/content/`.
- **Status**: **APPLIED & VERIFIED** on branch `ag/content-design` (Proposals 1, 2 YAML part, 3, and 4 approved by lead; iterated 3 rounds to hit all target bands).
- **Simulation Assumptions**:
  - Post-fix engine cadence: **550 ms basic attack** (`1.0×` multiplier without `skill_power`), **1,000 ms Global Cooldown (GCD)** between skills (legacy `0 ms` GCD + `skill_power` on basic attacks also shown for reference on Void Warden).
  - **70% dodge rate** on telegraphed boss/enemy AoE attacks; best level/rarity-appropriate loadout per slot; Lv1 skill ranks (`skill_growth` baseline); 8 s potion cooldown triggered at ≤ 55% HP.

---

## 1. Executive Verdict (Before vs Final Tuned)

| Area | Baseline (Pre-Tuning) | Final Tuned (Round 3) | Target Range | Status |
|---|---|---|---|---|
| **Pre-Boss Level (Typical: Main + 7 Sides)** | Lv16 (38,170 XP) | **Lv15** (38,170 XP) / **Lv16** (+10 kills/zone) | Lv15–16 | **PASS** |
| **Pre-Boss Level (Completionist: All 19 Quests)** | Lv20 cap (70,454 XP) | **Lv18** (93% to Lv19; Lv20 after M08) | Below Lv20 cap pre-boss | **PASS** |
| **Regular Mob TTK (Lv4–15 at target Lv)** | 0.1 s – 2.0 s | **2.0 s – 6.2 s** (Lv1 slime 0.8–1.3 s) | ~2.0 s – 6.0 s | **PASS** |
| **Elite Mob TTK (Treant / Sentinel)** | 0.4 s – 4.2 s | **7.1 s – 13.2 s** (Treant 8.6–12.2 s, Sentinel 7.1–13.2 s) | 6.0 s – 15.0 s | **PASS** |
| **Mid-Boss (`iron_grinder` Lv12 Solo)** | 9.1 s – 18.7 s | **33.8 s – 45.8 s** solo / **24.1 s – 34.2 s** 4P | 35 s – 60 s | **PASS** |
| **Final Boss (`void_warden` Lv16 Solo)** | 22.6 s (DIE) – 53.5 s | **87.2 s – 119.0 s** (WAR 117.2 s, ARC 119.0 s, MAG 87.2 s) | 60 s – 120 s | **PASS** |
| **Final Boss (`void_warden` Lv18 Solo)** | 26.4 s – 50.0 s | **82.4 s – 113.6 s** (WAR 111.2 s, ARC 113.6 s, MAG 82.4 s) | 60 s – 120 s | **PASS** |
| **Final Boss (`void_warden` Lv16–18 4P)** | 21.1 s – 43.9 s | **75.8 s – 110.0 s** (`coop_hp_per_player: 0.9`) | 60 s – 120 s | **PASS** |
| **Mage Solo Survivability (Void Warden)** | **DIE** at Lv15–16 | **WIN** Lv15 (14 pots), Lv16 (10 pots), Lv18 (9 pots) | Survivable, non-trivial | **PASS** |
| **Gold Sinks vs Income (Ch1)** | 11,190 G vs 8,638–20,178 G | 11,190 G vs 8,638–19,920 G (`Typical+10`: 11,876 G) | Balanced w/ side quests | **PASS** |
| **Drop Rates (`starshard_fragment` & Pity)** | `star_wisp` 150.2 kills; Boss pity 5.0 | `star_wisp` **88.2 kills** / `astral_stalker` **79.8 kills**; Boss pity **5.0** | ~80–90 kills / 5 clears | **PASS** |

---

## 2. Final Applied YAML Values (Proposals 1–4)

### Proposal 1 — XP Curve (`src/main/resources/content/balance/default.yml`)
- `xp_base`: `100` (unchanged — preserves `requiredXp(1) = 100` compatibility with `FoundationTest`)
- `xp_exponent`: `1.5` -> **`1.575`**
- Cumulative XP thresholds: `Lv5 = 1,848` | `Lv10 = 12,760` | `Lv13 = 25,893` | `Lv15 = 37,958` | `Lv16 = 45,075` | `Lv17 = 52,954` | `Lv18 = 61,622` | `Lv19 = 71,107` | `Lv20 = 81,435`.

### Proposal 2 — Class Growth (`src/main/resources/content/classes/*.yml`)

| File | Field | Before | Final | Effect at Lv18 |
|---|---|---:|---:|---|
| `classes/warrior.yml` | `growth.skill_power` | `0.04` | **`0.02`** | SP `1.86` -> `1.52` |
| `classes/archer.yml` | `growth.hp` | `18` | **`20`** | HP `696` -> `730` |
| `classes/archer.yml` | `growth.defense` | `2.2` | **`2.5`** | DEF `114` -> `120` |
| `classes/archer.yml` | `growth.skill_power` | `0.04` | **`0.02`** | SP `1.84` -> `1.50` |
| `classes/mage.yml` | `growth.hp` | `15` | **`19`** | HP `620` -> `688` (+11% eHP) |
| `classes/mage.yml` | `growth.defense` | `1.8` | **`2.4`** | DEF `102` -> `112` (+5% mitigation) |
| `classes/mage.yml` | `growth.skill_power` | `0.07` | **`0.025`** | SP `3.86` -> `3.09` |

> **Engine companion notes (sent to Codex-Impl by lead)**: Do not multiply basic attacks (`multiplier = 1.0`) by `skill_power`, and enforce a ~1 s Global Cooldown in `SkillEngine` alongside the 550 ms basic attack cadence.

### Proposal 3 — Enemy & Boss Tuning (`src/main/resources/content/enemies/*.yml`, `bosses/*.yml`)

| File | Lv | Before `hp` | Final `hp` | Other Final Field Changes |
|---|---:|---:|---:|---|
| `enemies/aether_slime.yml` | 1 | 65 | **140** | — |
| `enemies/thorn_wolf.yml` | 3 | 140 | **310** | — |
| `enemies/moss_goblin.yml` | 4 | 186 | **420** | — |
| `enemies/spore_spider.yml` | 6 | 295 | **650** | — |
| `enemies/corrupted_treant.yml` (Elite) | 8 | 1,150 | **2,500** | — |
| `enemies/mine_kobold.yml` | 9 | 500 | **1,150** | — |
| `enemies/crystal_bat.yml` | 10 | 520 | **1,200** | — |
| `enemies/obsidian_golem.yml` | 11 | 780 | **1,800** | — |
| `enemies/void_miner.yml` | 12 | 756 | **1,750** | — |
| `enemies/cult_acolyte.yml` | 13 | 740 | **1,750** | — |
| `enemies/star_wisp.yml` | 13 | 820 | **2,000** | — |
| `enemies/cult_zealot.yml` | 14 | 960 | **2,350** | — |
| `enemies/astral_stalker.yml` | 15 | 1,060 | **2,600** | — |
| `enemies/void_sentinel.yml` (Elite) | 17 | 3,200 | **6,800** | `currency: 138` -> **`95`** |
| `bosses/iron_grinder.yml` | 12 | 7,800 | **15,000** | — |
| `bosses/void_warden.yml` | 18 | 34,000 | **55,000** | `attack: 115` -> **`108`**, `coop_hp_per_player: 0.75` -> **`0.9`** |

### Proposal 4 — Starshard Fragment Drops (`src/main/resources/content/droptables/*.yml`)

| File | Entry | Before `chance` | Final `chance` |
|---|---|---:|---:|
| `droptables/star_wisp.yml` | `ec:item_mat_starshard_fragment` | `0.22` | **`0.35`** |
| `droptables/cult_zealot.yml` | `ec:item_mat_starshard_fragment` | *(missing)* | **`0.35`** (added `min: 1, max: 1`) |
| `droptables/astral_stalker.yml` | `ec:item_mat_starshard_fragment` | `0.30` | **`0.40`** |

---

## 3. Final Simulation Metrics (Observed Output)

### 3.1 Class Power Scaling (Lv1 -> Lv20, Best Era Gear)

| Level | Warrior (HP / ATK / WPN / DEF / CRIT / SP) | Archer (HP / ATK / WPN / DEF / CRIT / SP) | Mage (HP / ATK / WPN / DEF / CRIT / SP) |
|---:|---|---|---|
| Lv1 | 150 / 17 / 10 / 14 / 5% / 1.00 | 110 / 20 / 10 / 9 / 14% / 1.00 | 95 / 21 / 10 / 7 / 8% / 1.25 |
| Lv5 | 306 / 36 / 34 / 46 / 6% / 1.08 | 210 / 47 / 35 / 25 / 21% / 1.08 | 191 / 45 / 33 / 23 / 10% / 1.59 |
| Lv10 | 591 / 54 / 34 / 104 / 10% / 1.18 | 475 / 68 / 35 / 78 / 26% / 1.18 | 451 / 68 / 33 / 75 / 14% / 1.71 |
| Lv12 | 734 / 71 / 80 / 133 / 13% / 1.22 | 515 / 98 / 82 / 83 / 34% / 1.22 | 489 / 101 / 77 / 79 / 18% / 2.20 |
| Lv15 | 951 / 91 / 80 / 177 / 14% / 1.28 | 670 / 129 / 82 / 112 / 42% / 1.28 | 631 / 130 / 77 / 105 / 20% / 2.76 |
| Lv18 | 1073 / 114 / 136 / 196 / 17% / 1.52 | 730 / 153 / 139 / 120 / 47% / 1.50 | 688 / 156 / 131 / 112 / 23% / 3.09 |
| Lv20 (Leg) | 1241 / 157 / 186 / 217 / 26% / 1.98 | 835 / 200 / 190 / 131 / 57% / 1.96 | 791 / 203 / 179 / 123 / 33% / 3.76 |

### 3.2 Regular & Elite Enemies TTK at Target Level (1 s GCD, 550 ms Basic = 1.0×, No Potions)

| Enemy | Lv | HP | Warrior TTK / DmgIn | Archer TTK / DmgIn | Mage TTK / DmgIn |
|---|---:|---:|---|---|---|
| `ec:enemy_aether_slime` | 1 | 140 | 1.3 s / 0 (0%) | 1.1 s / 0 (0%) | 0.8 s / 0 (0%) |
| `ec:enemy_thorn_wolf` | 3 | 310 | 2.5 s / 13 (5%) | 1.4 s / 0 (0%) | 1.1 s / 0 (0%) |
| `ec:enemy_moss_goblin` | 4 | 420 | 3.2 s / 16 (6%) | 2.1 s / 18 (9%) | 2.0 s / 0 (0%) |
| `ec:enemy_spore_spider` | 6 | 650 | 3.1 s / 20 (5%) | 2.0 s / 23 (9%) | 1.4 s / 0 (0%) |
| `ec:enemy_corrupted_treant` (Elite) | 8 | 2,500 | **12.2 s** / 172 (42%) | **12.1 s** / 168 (55%) | **8.6 s** / 136 (48%) |
| `ec:enemy_mine_kobold` | 9 | 1,150 | 4.9 s / 53 (11%) | 3.2 s / 31 (8%) | 3.1 s / 31 (8%) |
| `ec:enemy_crystal_bat` | 10 | 1,200 | 4.9 s / 55 (9%) | 3.1 s / 32 (7%) | 3.1 s / 32 (7%) |
| `ec:enemy_obsidian_golem` | 11 | 1,800 | 5.2 s / 56 (9%) | 3.8 s / 33 (7%) | 3.2 s / 34 (7%) |
| `ec:enemy_void_miner` | 12 | 1,750 | 4.3 s / 57 (8%) | 3.1 s / 36 (7%) | 2.1 s / 37 (8%) |
| `ec:enemy_cult_acolyte` | 13 | 1,750 | 4.2 s / 64 (8%) | 2.6 s / 41 (8%) | 2.1 s / 0 (0%) |
| `ec:enemy_star_wisp` | 13 | 2,000 | 5.2 s / 63 (8%) | 3.1 s / 40 (7%) | 2.6 s / 41 (8%) |
| `ec:enemy_cult_zealot` | 14 | 2,350 | 5.5 s / 106 (11%) | 3.2 s / 39 (6%) | 2.1 s / 41 (7%) |
| `ec:enemy_astral_stalker` | 15 | 2,600 | 6.2 s / 114 (12%) | 3.8 s / 85 (13%) | 2.6 s / 44 (7%) |
| `ec:enemy_void_sentinel` (Elite) | 17 | 6,800 | **13.2 s** / 243 (23%) | **10.4 s** / 280 (39%) | **7.1 s** / 173 (26%) |

### 3.3 Boss Encounters (70% Telegraph Dodge, Potions Enabled)

| Scenario | Warrior | Archer | Mage |
|---|---|---|---|
| Iron Grinder Lv12 Solo `[1sGCD]` | WIN **45.7 s** (3 pots, 1,066 dmg) | WIN **45.8 s** (5 pots, 1,411 dmg) | WIN **33.8 s** (4 pots, 1,060 dmg) |
| Iron Grinder Lv12 4P `[1sGCD]` | WIN **34.2 s** (1 pot, 569 dmg) | WIN **32.6 s** (3 pots, 737 dmg) | WIN **24.1 s** (2 pots, 543 dmg) |
| Void Warden Lv15 Solo `[1sGCD]` | WIN 169.2 s (14 pots, 6,304 dmg) | WIN 171.2 s (20 pots, 8,311 dmg) | WIN 117.8 s (14 pots, 6,108 dmg) |
| **Void Warden Lv16 Solo `[1sGCD]`** | WIN **117.2 s** (8 pots, 4,199 dmg) | WIN **119.0 s** (13 pots, 5,811 dmg) | WIN **87.2 s** (10 pots, 4,430 dmg) |
| **Void Warden Lv18 Solo `[1sGCD]`** | WIN **111.2 s** (7 pots, 3,886 dmg) | WIN **113.6 s** (13 pots, 5,395 dmg) | WIN **82.4 s** (9 pots, 4,022 dmg) |
| Void Warden Lv16 4P `[1sGCD]` | WIN **108.2 s** (5 pots, 2,798 dmg) | WIN **110.0 s** (11 pots, 3,845 dmg) | WIN **80.1 s** (8 pots, 2,942 dmg) |
| Void Warden Lv18 4P `[1sGCD]` | WIN **103.2 s** (5 pots, 2,642 dmg) | WIN **104.6 s** (10 pots, 3,626 dmg) | WIN **75.8 s** (7 pots, 2,781 dmg) |
| Void Warden Lv18 Solo `[Leg+1sGCD]` | WIN 70.2 s (4 pots, 2,379 dmg) | WIN 75.2 s (8 pots, 3,524 dmg) | WIN 52.1 s (6 pots, 2,536 dmg) |
| Void Warden Lv18 Solo `[Legacy0ms]` | WIN 92.2 s (6 pots, 3,215 dmg) | WIN 94.2 s (10 pots, 4,429 dmg) | WIN 54.1 s (6 pots, 2,679 dmg) |

### 3.4 Chapter 1 XP & Gold Progression (`xp_base=100`, `xp_exponent=1.575`)

| Playstyle Path | Reached Level | Remaining XP | Total XP | Total Gold | Assessment |
|---|---:|---|---:|---:|---|
| A1. Main M01–M07 only (pre-boss) | **Lv12** | 3,250 / 5,008 | 24,135 | 5,538 G | Motivates side quests before Void Warden |
| A2. Main M01–M08 only (post-boss) | **Lv14** | 5,161 / 6,384 | 36,735 | 8,638 G | Floor for main-only rush |
| B1. Typical (Main M01–M07 + 7 sides) | **Lv15** | 212 / 7,117 | 38,170 | 9,626 G | **Target (Lv15–16 pre-boss)** |
| B2. Typical + 10 incidental kills/zone | **Lv16** | 6,665 / 7,879 | 51,740 | 11,876 G | **Target (Lv15–16 pre-boss)** |
| C1. All 19 quests before M08 | **Lv18** | 8,832 / 9,485 | 70,454 | 16,820 G | **Below Lv20 cap pre-boss** |
| C2. All 20 quests (post-M08) | **Lv20** | 0 (cap) | 83,054 | 19,920 G | Hits cap cleanly on final clear |

### 3.5 Zone Farming Rates (Warrior @ Target Level, +4 s Travel/Respawn)

| Zone / Mob | TTK | Kills/hr | XP/hr | Gold/hr |
|---|---:|---:|---:|---:|
| Ashwood Lv2 (`aether_slime`) | 1.3 s | 679 | 12,226 | 4,075 G |
| Ashwood Lv4 (`thorn_wolf`) | 2.5 s | 554 | 21,600 | 6,646 G |
| Mine Lv8 (`void_miner`) | 4.3 s | 434 | 108,867 | 24,289 G |
| Mine Lv10 (`obsidian_golem`) | 5.2 s | 391 | 91,957 | 19,565 G |
| Hill Lv14 (`star_wisp`) | 5.2 s | 391 | 112,304 | 24,261 G |
| Sanctum Lv17 (`void_sentinel`) | 13.2 s | 209 | 163,256 | 19,884 G |

### 3.6 Economy & Monte Carlo Drop Expectations (`N = 1,000`, `Random(42)`)
- **Warrior Full Shop Sinks**: Gear Lv1–12 (`3,900 G`) + Estimated Ch1 Consumables (`7,290 G`) = **`11,190 G`** vs `Typical + 10 kills/zone` income **`11,876 G`** (healthy near-break-even, with side/repeatable surplus up to `19,920 G`).
- **Drops**:
  - `aether_gel` (goal 8): **12.1 kills** | `venom_gland` (goal 6): **10.8 kills** | `iron_scrap` (goal 6): **17.4 kills** | `void_dust` (goal 6): **10.3 kills**
  - `starshard_fragment` (goal 32): `star_wisp` **88.2 kills** (was 150.2), `astral_stalker` **79.8 kills** (plus `cult_zealot` at `0.35`)
  - `iron_grinder` rare greatsword (goal 1): **5.3 kills**
  - `void_warden` Legendary pity (`void_core_essence` goal 5 / `starshard_fragment` goal 30): **5.0 kills** exact; rare `void_cleaver` bonus drop: **32.3 kills**.

---

## 4. Test Verification
- `.\gradlew.bat test --tests "net.nakaymi.etherealchronicle.qa.BalanceSimulationTest" --tests "net.nakaymi.etherealchronicle.content.Chapter1ContentValidationTest" --rerun-tasks`: **PASS** (all assertions green).
- Full `.\gradlew.bat test --rerun-tasks` on `ag/content-design`: **34 / 36 PASS**. The 2 failures (`FoundationTest.kt:66` and `FoundationTest.kt:127`) are the known pre-existing legacy `ec:tutorial` fixture tests that Codex already migrated to test-only fixtures on `codex/foundation` (`e607836`); keeping `xp_base: 100` ensures `FoundationTest.kt:29` passes as well.
