# Balance — Chapter 1

All tunable progression, damage caps, rarity budgets and class/skill/item numbers are YAML. Source defaults are in `src/main/resources/content/`; installed server files take priority and are never overwritten. `/ec validate` checks disk content without activation; `/ec reload` validates and atomically replaces the snapshot. Existing active class/quest references cannot be removed by reload.

- Level requirement: `floor(xp_base * level ^ xp_exponent)`, minimum 1. Shipped curve is POWER, `100 * level^1.575`; cap 20. XP carries across levels and becomes zero at the cap. Each award is limited by `xp_gain_cap` (10,000). Currency awards are capped by `currency_gain_cap` (5,000), with overflow-safe arithmetic and a 100,000,000 wallet cap. Optional POLYNOMIAL curves use the separately configured linear/quadratic/rounding fields.
- Damage: `(ATK * skillMultiplier + weapon) * defense_scale/(defense_scale + DEF) * crit * elementModifier`. Default defense scale 100. Attack/defense/weapon are bounded by `stat_cap`; final damage is bounded by `damage_cap`. Crit chance, crit multiplier and elemental multiplier use explicit caps. Equipped item attack is part of ATK, so the Paper adapter supplies weapon=0 to avoid double counting. Skills multiply their base multiplier by `1 + (rank-1)*skill_growth`, then by SKILL_POWER.
- Paper RPG damage clears vanilla armor/resistance/blocking damage modifiers and retains absorption, so RPG DEF is applied once. This adapter uses a deprecated Paper damage-modifier API and needs live-server verification. Vanilla environmental and PvP damage keep vanilla rules.
- Warrior rage starts at 0 and gains 10 on a successful RPG hit. Archer focus starts full and regenerates 5/second while stationary. Mage mana starts full and regenerates 8/second during movement too. Resource maxima include eligible equipment. No per-tick entity scans.
- Rarity multipliers: common 1.0/0 affixes, uncommon 1.2/1, rare 1.5/2, epic 1.8/3, legendary 2.2/4. Values live in balance YAML. Budget consumption uses absolute stat values: HP/10 + resource/10 + ATK + DEF + crit*100 + critDamage*10 + elemental resistance*100 + skillPower*10. An item must fit its base budget times its rarity multiplier. Affix counts are definition metadata; random affix generation is not part of Task 1.

Chapter 1 bundles three classes, twelve skills, twenty quests, fourteen enemies, two bosses, NPCs, dialogue and shops. Normal slimes, spawners, spawn eggs and slime splits never receive RPG rewards. Explicit plugin spawn points and `/ec spawn <enemy-id> <player>` create tagged RPG mobs. Legacy foundation content remains only in test fixtures.

Basic attacks have a configurable 550 ms gate and do not multiply by SKILL_POWER. Skills share a shipped 1,000 ms global cooldown in addition to their individual cooldowns. Gear ATTACK_PROC amounts are flat damage, mitigated by defense/element and bounded by damage_cap, with a re-entrancy guard. Player HP is capped at 1,024 and displayed on a 20-point health scale; bosses use virtual HP (Void Warden 55,000). Element resistance is capped at 0.5. Sell ratio is 0.3; bound/quest items cannot be sold. Party XP and currency totals are divided among eligible members; item drops rotate between recipients.

---

## Chapter 1 Tuned Balance Summary

Tuned against the real core/content engine via `BalanceSimulationTest` (550 ms basic attack cadence without `skill_power`, 1 s skill GCD, 70% telegraph dodge, 8 s potion cooldown). Full breakdown lives in `docs/content/balance_report.md`.

| Metric / Parameter | Tuned Value / Simulation Result | Target Band |
|---|---|---|
| XP Curve (`balance/default.yml`) | `xp_base: 100`, `xp_exponent: 1.575` (Lv15 = 37,958 / Lv16 = 45,075 / Lv18 = 61,622 / Lv20 = 81,435) | Typical Lv15–16 pre-boss; completionist < Lv20 pre-boss |
| Chapter 1 Pre-Boss Level | Typical (Main + 7 sides): **Lv15** (Lv16 w/ +10 kills/zone); All 19 quests: **Lv18** (Lv20 post-M08) | Lv15–16 typical / < Lv20 completionist |
| Class Growth (`classes/*.yml`) | WAR `sp: 0.02`; ARC `hp: 20, def: 2.5, sp: 0.02`; MAG `hp: 19, def: 2.4, sp: 0.025` | All 3 solo-viable; reduced Mage SP runaway |
| Regular Mob TTK (Lv4–15) | **2.0 s – 6.2 s** (WAR 3.1–6.2 s, ARC 2.0–3.8 s, MAG 1.4–3.2 s; Lv1 slime 0.8–1.3 s) | ~2–6 s at target level |
| Elite Mob TTK (`treant` / `sentinel`) | `corrupted_treant` (2,500 HP): **8.6–12.2 s**; `void_sentinel` (6,800 HP, 95 G): **7.1–13.2 s** | 6–15 s |
| Mid-Boss (`iron_grinder`, 15,000 HP) | Lv12 Solo: **33.8–45.8 s** (3–5 pots) / 4P: **24.1–34.2 s** | 35–60 s solo |
| Final Boss (`void_warden`, 55,000 HP, 108 ATK, `coop_hp_per_player: 0.9`) | Lv16 Solo: **87.2–119.0 s** (8–13 pots); Lv18 Solo: **82.4–113.6 s** (7–13 pots); 4P: **75.8–110.0 s** | 60–120 s solo; Mage survivable non-trivially (10 pots @ Lv16) |
| Economy (Ch1 Sinks vs Income) | Full Warrior shop + consumables `11,190 G` vs `Typical+10` `11,876 G` (All 20 quests `19,920 G`) | Balanced w/ side quests |
| Key Drop Pacing (`N = 1,000`) | `starshard_fragment` (`0.35–0.40`): **79.8–88.2 kills** for 32; Void Warden Legendary pity: **5.0 clears** | ~80–90 kills / 5-clear pity |

The table reports the balance author's simulation assumptions, not measured client combat. Independent per-entry drop rolls are implemented; the table's modeled five-clear pity is not an implemented pity counter. Real telegraph avoidance, movement and network timing remain unverified without a compatible client.
