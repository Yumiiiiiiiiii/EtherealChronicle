# Overworld QA: sky-box dungeon enter -> shot -> leave (observe CLEANING in the log).
$Out = 'C:\Users\Ymii\.traycer\epics\132bc8e7-97a8-4d19-af01-669c4ba69b04\artifacts\overworld-live-qa'
. "$PSScriptRoot\mcwin.ps1"; . "$PSScriptRoot\qa-helpers.ps1"
function FocusRetry($gp) { for ($i = 0; $i -lt 10; $i++) { try { Focus-Mc $gp; return } catch { Start-Sleep 4 } }; throw 'focus failed' }
$gp = Get-Gp; Pin-Mc $gp
C 'gamemode creative QaOw'; C 'ec flag QaOw ec:flag_early_trial_unlocked set'; C 'effect give QaOw resistance 3000 10 true'
C 'tp QaOw 375 69 -268 180 10'; Start-Sleep 6
FocusRetry $gp; Save-McShot $gp "$Out\14_dungeon_entrance_portal.png" | Out-Null
FocusRetry $gp; Chat '/ec dungeon ec:dungeon_novice_arena'; Start-Sleep 8
C 'data get entity QaOw Pos'; Start-Sleep 1
FocusRetry $gp; Save-McShot $gp "$Out\15_dungeon_inside.png" | Out-Null
C 'tps'
