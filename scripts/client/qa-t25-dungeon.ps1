# Task 25: dungeon entry (DungeonArenaLayout), arena bounds, leave, void recovery.
. "$PSScriptRoot\mcwin.ps1"; . "$PSScriptRoot\qa-helpers.ps1"
$gp = Get-Gp; Pin-Mc $gp
C 'ec flag QaT25 ec:flag_early_trial_unlocked set'; Start-Sleep 1
C 'effect give QaT25 minecraft:resistance 3000 10 true'
Tp QaT25 0.5 65 0.5 0 0; Start-Sleep 3
Focus-Mc $gp; Chat '/ec dungeon ec:dungeon_novice_arena'; Start-Sleep 5
Shot t25_dg_a_enter | Out-Null
C 'data get entity QaT25 Pos'; Start-Sleep 1
# 上を向いてアリーナ全体 (床・壁・敵) を確認
C 'execute in minecraft:ec_world run tp QaT25 1024 70 1006 0 35'; Start-Sleep 3
Shot t25_dg_b_arena | Out-Null
Focus-Mc $gp; Chat '/ec dungeon leave'; Start-Sleep 4
Shot t25_dg_c_leave | Out-Null
C 'data get entity QaT25 Pos'
