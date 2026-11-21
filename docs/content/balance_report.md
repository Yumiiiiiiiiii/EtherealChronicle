# Chapter 1 Balance Simulation Report & YAML Tuning Proposals

- **Simulator**: `src/test/kotlin/net/nakaymi/etherealchronicle/qa/BalanceSimulationTest.kt`
- **Engine & Data**: Real `ContentService`, `GameService`, `SkillEngine`, `Balance.damage`, `Balance.grantXp`, `BossState`, `DropTable.roll` against all 157 bundled YAML files under `src/main/resources/content/`.
- **Simulation Assumptions**:
  - Realistic **600 ms basic-attack cadence** (post-HIGH-01 fix; without HIGH-01 fix, real TTK collapses by 10–20× via autoclicking).
  - **70% dodge rate** on telegraphed boss/enemy AoE attacks; best level/rarity-appropriate loadout per slot; Lv1 skill ranks (`skill_growth` baseline); 8 s potion cooldown triggered at <=55% HP.
  - No modifications were made to `src/main/resources/content/balance/default.yml` or any other `src/main` file — all changes below are **concrete numeric proposals** for lead approval.

---

## 1. Executive Verdict

| Area | Current Result | Target Range | Status |
|---|---|---|---|
| **Pre-Boss Level (Typical: Main + 7 Sides)** | **Lv16** (38,170 XP) | Lv15–18 | **PASS** |
| **Pre-Boss Level (Completionist: All 19 Quests)** | **Lv20 cap** (70,454 XP) | Lv17–18 (below cap) | **OUT OF RANGE (Too Fast)** |
| **Regular Mob TTK (Lv1–15)** | **0.1 s – 2.0 s** (1–2 shots) | 4.0 s – 8.0 s | **OUT OF RANGE (Too Fast)** |
| **Elite Mob TTK (Treant / Sentinel)** | **0.4 s – 4.2 s** | 12.0 s – 20.0 s | **OUT OF RANGE (Too Fast)** |
| **Mid-Boss (`iron_grinder` Lv12)** | **9.1 s – 18.7 s** solo / **6.4 s – 13.3 s** 4P | 35 s – 60 s | **OUT OF RANGE (Too Fast)** |
| **Final Boss (`void_warden` Lv16–18)** | **26 s – 54 s** solo / **21 s – 44 s** 4P | 90 s – 180 s | **OUT OF RANGE (Too Fast)** |
| **Class Parity (DPS & Survivability)** | Mage **~2.0× DPS** of War/Arc, yet **dies solo at Lv15–16** | Within ±20% DPS; all 3 viable | **OUT OF RANGE (Skewed)** |
| **Gold Sinks vs Income (Ch1)** | Sinks ~11,190 G vs Income 8,638–20,178 G | Balanced w/ side quests | **HEALTHY (Minor Late-Zone Trim)** |
| **Drop Rates (Quest & Boss Pity)** | Quest mats 10–17 kills; Boss Legendary pity = 5 kills | 8–20 kills / 5 clears | **HEALTHY (`star_wisp` slightly low)** |

---

## 2. Observed Simulation Metrics

### 2.1 Class Power Scaling (Lv1 -> Lv20, Best Era Gear)

| Level | Warrior (HP / ATK / WPN / DEF / CRIT / SP) | Archer (HP / ATK / WPN / DEF / CRIT / SP) | Mage (HP / ATK / WPN / DEF / CRIT / SP) |
|---:|---|---|---|
| Lv1 | 150 / 17 / 10 / 14 / 5% / 1.00 | 110 / 20 / 10 / 9 / 14% / 1.00 | 95 / 21 / 10 / 7 / 8% / 1.25 |
| Lv5 | 306 / 36 / 34 / 46 / 6% / 1.16 | 202 / 47 / 35 / 24 / 21% / 1.16 | 175 / 45 / 33 / 20 / 10% / 1.77 |
| Lv10 | 591 / 54 / 34 / 104 / 10% / 1.36 | 457 / 68 / 35 / 75 / 26% / 1.36 | 415 / 68 / 33 / 69 / 14% / 2.12 |
| Lv12 | 734 / 71 / 80 / 133 / 13% / 1.44 | 493 / 98 / 82 / 79 / 34% / 1.44 | 445 / 101 / 77 / 73 / 18% / 2.70 |
| Lv15 | 951 / 91 / 80 / 177 / 14% / 1.56 | 642 / 129 / 82 / 108 / 42% / 1.56 | 575 / 130 / 77 / 96 / 20% / **3.39** |
| Lv18 | 1073 / 114 / 136 / 196 / 17% / 1.86 | 696 / 153 / 139 / 114 / 47% / 1.84 | 620 / 156 / 131 / 102 / 23% / **3.86** |
| Lv20 (Leg) | 1241 / 157 / 186 / 217 / 26% / 2.36 | 797 / 200 / 190 / 125 / 57% / 2.34 | 715 / 203 / 179 / 111 / 33% / **4.62** |

> **Root cause of Mage skew**: `RpgPlugin.hit` multiplies **both skills and basic attacks** by `stats.skillPower`. Because `classes/mage.yml` starts at `1.15` with `+0.07/lv` growth (`2.48` naked at Lv20) and Mage gear stacks another `+1.4–2.1` `skill_power`, Mage reaches `SP = 3.86` at Lv18 (`4.62` at Lv20) — **more than double** Warrior/Archer `SP = 1.84–1.86`!

### 2.2 Regular & Elite Enemies TTK at Target Level (No Potions)

| Enemy | Lv | HP | Warrior TTK / DmgIn | Archer TTK / DmgIn | Mage TTK / DmgIn |
|---|---:|---:|---|---|---|
| `ec:enemy_aether_slime` | 1 | 65 | 0.7 s / 0 (0%) | 0.1 s / 0 (0%) | 0.1 s / 0 (0%) |
| `ec:enemy_thorn_wolf` | 3 | 140 | 0.8 s / 0 (0%) | 0.2 s / 0 (0%) | 0.2 s / 0 (0%) |
| `ec:enemy_moss_goblin` | 4 | 186 | 0.8 s / 0 (0%) | 0.2 s / 0 (0%) | 0.2 s / 0 (0%) |
| `ec:enemy_spore_spider` | 6 | 295 | 0.8 s / 0 (0%) | 0.2 s / 0 (0%) | 0.2 s / 0 (0%) |
| `ec:enemy_corrupted_treant` (Elite) | 8 | 1,150 | 4.2 s / 29 (7%) | 2.9 s / 34 (12%) | 1.1 s / 0 (0%) |
| `ec:enemy_mine_kobold` | 9 | 500 | 1.9 s / 0 (0%) | 0.3 s / 0 (0%) | 0.2 s / 0 (0%) |
| `ec:enemy_obsidian_golem` | 11 | 780 | 1.9 s / 0 (0%) | 0.3 s / 0 (0%) | 0.2 s / 0 (0%) |
| `ec:enemy_void_miner` | 12 | 756 | 1.9 s / 0 (0%) | 0.2 s / 0 (0%) | 0.2 s / 0 (0%) |
| `ec:enemy_star_wisp` | 13 | 820 | 1.9 s / 0 (0%) | 0.2 s / 0 (0%) | 0.2 s / 0 (0%) |
| `ec:enemy_astral_stalker` | 15 | 1,060 | 2.0 s / 32 (3%) | 0.3 s / 0 (0%) | 0.2 s / 0 (0%) |
| `ec:enemy_void_sentinel` (Elite) | 17 | 3,200 | 4.2 s / 70 (7%) | 1.7 s / 0 (0%) | 0.4 s / 0 (0%) |

> **Why Archer & Mage kill regular mobs in 0.1–0.3 s**: `SkillEngine` has no Global Cooldown (GCD) across the 4 skills (`strike` 1.5×, `burst` 2.0×, `wave` 2.5×, `ultimate` 3.0×), and Focus/Mana start at 100–120, allowing 3–4 skills to burst back-to-back instantly, while regular mob HP is too low for the real `attack * multiplier * skillPower + weapon` formula.

### 2.3 Boss Encounters (70% Telegraph Dodge, Potions Enabled)

| Scenario | Warrior | Archer | Mage |
|---|---|---|---|
| Iron Grinder Lv12 Solo (Rare) | WIN 18.7 s (1 pot, 451 dmg) | WIN 18.0 s (2 pots, 565 dmg) | WIN 9.1 s (1 pot, 305 dmg) |
| Iron Grinder Lv12 4P (Rare) | WIN 13.3 s (0 pot, 226 dmg) | WIN 12.1 s (1 pot, 304 dmg) | WIN 6.4 s (0 pot, 145 dmg) |
| Void Warden Lv15 Solo (Epic) | WIN 78.2 s (6 pots, 3,129 dmg) | WIN 79.8 s (9 pots, 4,307 dmg) | **DIE** 31.6 s (3 pots, 1,784 dmg) |
| Void Warden Lv16 Solo (Epic) | WIN 53.5 s (4 pots, 2,035 dmg) | WIN 53.4 s (6 pots, 2,806 dmg) | **DIE** 22.6 s (2 pots, 1,374 dmg) |
| Void Warden Lv18 Solo (Epic) | WIN 50.0 s (3 pots, 1,886 dmg) | WIN 49.2 s (5 pots, 2,616 dmg) | WIN 26.4 s (3 pots, 1,544 dmg) |
| Void Warden Lv18 Solo (1 s GCD) | WIN 54.2 s (3 pots, 1,976 dmg) | WIN 48.8 s (5 pots, 2,546 dmg) | WIN 26.6 s (3 pots, 1,549 dmg) |
| Void Warden Lv16 4P (Epic) | WIN 43.9 s (2 pots, 1,245 dmg) | WIN 43.2 s (4 pots, 1,715 dmg) | WIN 23.1 s (2 pots, 1,079 dmg) |
| Void Warden Lv18 4P (Epic) | WIN 40.3 s (2 pots, 1,162 dmg) | WIN 39.6 s (4 pots, 1,611 dmg) | WIN 21.1 s (2 pots, 927 dmg) |
| Void Warden Lv18 Solo (Leg) | WIN 29.5 s (2 pots, 1,083 dmg) | WIN 28.8 s (3 pots, 1,469 dmg) | WIN 16.1 s (2 pots, 981 dmg) |

- **Observation**: Even with a 1 s GCD, Void Warden solo lasts only 26–54 s (and 21–40 s in a 4-player party because `coop_hp_per_player: 0.75` gives 3.25× HP against 4.0× player DPS). Neither boss ever comes close to its 6-minute (`7200` tick) enrage. Meanwhile, Lv15–16 Solo Mage dies because 575–620 max HP cannot survive back-to-back Phase 2 hits during potion cooldown.

### 2.4 Chapter 1 XP Progression (`balance/default.yml`: `xp_base: 100`, `xp_exponent: 1.5`)

Cumulative XP thresholds under current `balance/default.yml`:
- Lv13: `22,068` | Lv15: `31,993` | Lv16: `37,802` | Lv17: `44,202` | Lv18: `51,211` | Lv20: `67,128`

| Playstyle Path | Reached Level | Total XP | Total Gold | Assessment |
|---|---:|---:|---:|---|
| A1. Main M01–M07 only (pre-boss) | **Lv13** | 24,135 | 5,538 G | Slightly below Lv15–18 (encourages side quests) |
| A2. Main M01–M08 only (post-boss) | **Lv15** | 36,735 | 8,638 G | Good floor for main-only rush |
| B1. Main M01–M07 + 7 early/mid Sides | **Lv16** | 38,170 | 9,626 G | **Ideal** (inside Lv15–18 pre-boss target) |
| C1. All 19 quests before M08 | **Lv20** (CAP!) | 70,454 | 17,078 G | **Overshoots**: hits Lv20 cap *before* final boss |
| C2. All 20 quests (post-M08) | **Lv20** (CAP!) | 83,054 | 20,178 G | 15,926 XP wasted past cap |
| C3. All 20 quests + 25 kills/zone | **Lv20** (CAP!) | 116,979 | 26,878 G | 49,851 XP wasted past cap |

### 2.5 Zone Farming Rates (Warrior @ Target Level, +4 s Travel/Respawn)

| Zone / Mob | TTK | Kills/hr | XP/hr | Gold/hr |
|---|---:|---:|---:|---:|
| Ashwood Lv2 (`aether_slime`) | 0.7 s | 766 | 13,787 | 4,596 G |
| Ashwood Lv4 (`thorn_wolf`) | 0.8 s | 750 | 29,250 | 9,000 G |
| Mine Lv8 (`void_miner`) | 1.9 s | 610 | 153,153 | 34,169 G |
| Mine Lv10 (`obsidian_golem`) | 1.9 s | 610 | 143,390 | 30,508 G |
| Hill Lv14 (`star_wisp`) | 1.9 s | 610 | 175,119 | 37,831 G |
| Sanctum Lv17 (`void_sentinel`) | 4.2 s | 439 | 342,439 | 60,585 G |

- **Observation**: Because current mob TTK is <2 s, farming `void_miner` at Lv12 earns `153k XP/hr` — enough to jump from Lv1 to Lv20 in **26 minutes**, and `void_sentinel` earns `60.6k G/hr`. Increasing regular/elite HP 2.0–2.5× immediately cuts these rates into healthy MMORPG pacing.

### 2.6 Gold Sinks vs Income (Warrior Full Shop Upgrade Path)
- **Full Lv1–12 Shop Gear Sinks** (`lumina_helm` 90 + `oak_buckler` 120 + `ashwood_blade` 320 + `ashwood_boots` 340 + `miner_greaves` 680 + `minebreaker_greatsword` 1,250 + `obsidian_aegis` 1,100): **`3,900 G`**
- **Estimated Chapter 1 Consumables** (15 Minor Pot + 20 Med Pot + 15 Greater Pot + 5 Stew + 5 Ration + 3 Revive Feather): **`7,290 G`**
- **Total Sinks**: **`11,190 G`**
- **Income**: Main-only `8,638 G` | Typical `9,626 G` (+ ~1,500 G vendor drops ≈ `11,100 G` break-even) | All 20 Quests `20,178 G` (+9k G surplus for repeatables/pots).

### 2.7 Drop Rate Expectations (Monte Carlo `N = 1,000`, `Random(42)`)

| Drop Table | Target Item | Goal | Avg / Kill | Expected Kills | Assessment |
|---|---|---:|---:|---:|---|
| `ec:drop_aether_slime` | `ec:item_mat_aether_gel` | 8 | 0.662 | 12.1 | Healthy |
| `ec:drop_spore_spider` | `ec:item_mat_venom_gland` | 6 | 0.557 | 10.8 | Healthy |
| `ec:drop_void_miner` | `ec:item_mat_iron_scrap` | 6 | 0.344 | 17.4 | Healthy |
| `ec:drop_void_miner` | `ec:item_mat_void_dust` | 6 | 0.581 | 10.3 | Healthy |
| `ec:drop_star_wisp` | `ec:item_mat_starshard_fragment` | 32 | 0.213 | **150.2** | Slightly grindy for pre-boss Epic set |
| `ec:drop_iron_grinder` | `ec:item_wpn_minebreaker_greatsword` | 1 | 0.192 | 5.2 | Healthy |
| `ec:drop_void_warden` | `ec:item_mat_void_core_essence` | 5 | 1.000 | **5.0** | Exact 5-clear Legendary pity |
| `ec:drop_void_warden` | `ec:item_mat_starshard_fragment` | 30 | 6.000 | **5.0** | Exact 5-clear Legendary pity |
| `ec:drop_void_warden` | `ec:item_wpn_void_cleaver` | 1 | 0.034 | 29.4 | Healthy rare bonus drop |

---

## 3. Concrete Numeric YAML Change Proposals (Awaiting Lead Decision)

> None of the files below have been modified. Apply whichever subsets you approve.

### Proposal 1 — XP Curve (`src/main/resources/content/balance/default.yml`, Codex-owned)
Prevents completionists from hitting Lv20 cap *before* entering the final boss while keeping typical players right at Lv15–17.

```yaml
# Current:
xp_base: 100
xp_exponent: 1.5
# -> Cum XP: Lv15=31,993 | Lv17=44,202 | Lv18=51,211 | Lv20=67,128

# Proposed:
xp_base: 115
xp_exponent: 1.58
# -> Cum XP: Lv15=44,154 | Lv17=61,638 | Lv18=71,749 | Lv20=94,868
```
**Projected result**:
- Main M01–M07 only (24,135 XP) -> Lv11–12 (motivates side quests before Void Warden).
- Typical (Main + 7 sides + ~15 incidental kills ≈ 44.5k XP) -> **Lv15** pre-boss.
- Completionist (All 19 quests before M08 = 70,454 XP) -> **Lv17 (98% to Lv18)** pre-boss, **Lv19** post-M08, leaving Lv20 for post-clear farming!

### Proposal 2 — Class `skill_power` & Mage Survivability (`src/main/resources/content/classes/*.yml`)
Reins in Mage's `3.86–4.62×` skill_power runaway while fixing Lv15–16 Solo Mage deaths against Void Warden.

| File | Field | Current | Proposed | Effect at Lv18 |
|---|---|---:|---:|---|
| `classes/warrior.yml` | `growth.skill_power` | `0.04` | `0.02` | SP `1.86` -> `1.52` |
| `classes/archer.yml` | `growth.skill_power` | `0.04` | `0.02` | SP `1.84` -> `1.50` |
| `classes/mage.yml` | `growth.skill_power` | `0.07` | `0.025` | SP `3.86` -> `3.09` (pairs with engine note below) |
| `classes/mage.yml` | `growth.hp` | `15` | `19` | HP `620` -> `688` (+11% eHP) |
| `classes/mage.yml` | `growth.defense` | `1.8` | `2.4` | DEF `102` -> `112` (+5% mitigation) |

- **Engine companion recommendation (for Codex)**: Do **not** multiply basic attacks by `s.skillPower` in `RpgPlugin.hit()` (apply `s.skillPower` only to skills, not `multiplier = 1.0` melee/bow/wand swings), and add a ~1.0 s global skill cooldown in `SkillEngine`. That alone closes most of the Mage vs Warrior/Archer gap and eliminates 0.2 s instant skill dumps.

### Proposal 3 — Enemy & Boss HP Scaling (`src/main/resources/content/enemies/*.yml`, `bosses/*.yml`)
Brings regular mob TTK into 4–8 s, elites into 12–20 s, Iron Grinder to ~40 s, and Void Warden to ~100–130 s (making all 3 phases playable).

| File | Current `hp` | Proposed `hp` | Current `currency` | Proposed `currency` |
|---|---:|---:|---:|---:|
| `enemies/aether_slime.yml` | 65 | **140** | 6 | 6 |
| `enemies/thorn_wolf.yml` | 140 | **310** | 12 | 12 |
| `enemies/moss_goblin.yml` | 186 | **420** | 16 | 16 |
| `enemies/spore_spider.yml` | 295 | **650** | 24 | 24 |
| `enemies/corrupted_treant.yml` (Elite) | 1,150 | **2,500** | 58 | 58 |
| `enemies/mine_kobold.yml` | 500 | **1,150** | 38 | 38 |
| `enemies/crystal_bat.yml` | 520 | **1,200** | 44 | 44 |
| `enemies/obsidian_golem.yml` | 780 | **1,800** | 50 | 50 |
| `enemies/void_miner.yml` | 756 | **1,750** | 56 | 56 |
| `enemies/cult_acolyte.yml` | 740 | **1,750** | 62 | 62 |
| `enemies/star_wisp.yml` | 820 | **2,000** | 62 | 62 |
| `enemies/cult_zealot.yml` | 960 | **2,350** | 72 | 72 |
| `enemies/astral_stalker.yml` | 1,060 | **2,600** | 82 | 82 |
| `enemies/void_sentinel.yml` (Elite) | 3,200 | **6,800** | 138 | **95** |
| `bosses/iron_grinder.yml` | 7,800 | **15,000** | 450 | 450 |
| `bosses/void_warden.yml` | 34,000 | **62,000** | 1,200 | 1,200 |

- Optional boss co-op tweak: raise `bosses/void_warden.yml` `coop_hp_per_player` from `0.75` -> `0.90` so 4-player parties (`3.7×` HP) have a similar ~100–120 s fight duration to solo rather than melting the boss faster than solo.

### Proposal 4 — Starshard Fragment Drop Rate (`src/main/resources/content/droptables/*.yml`)
Reduces pre-boss Epic token grind on Stargazer Hill from ~150 kills down to ~90 kills (and ~45–50 kills when combined with quest rewards).

| File | Entry | Current `chance` | Proposed `chance` |
|---|---|---:|---:|
| `droptables/star_wisp.yml` | `ec:item_mat_starshard_fragment` | `0.22` | **`0.35`** |
| `droptables/cult_zealot.yml` | `ec:item_mat_starshard_fragment` | `0.24` | **`0.35`** |
| `droptables/astral_stalker.yml` | `ec:item_mat_starshard_fragment` | `0.28` | **`0.40`** |
