# Overworld QA #4 part B: portal walk-in with the unlock flag, then /ec dungeon leave.
$Out = 'C:\Users\Ymii\.traycer\epics\132bc8e7-97a8-4d19-af01-669c4ba69b04\artifacts\overworld-live-qa-gameplay'
. "$PSScriptRoot\mcwin.ps1"; . "$PSScriptRoot\qa-helpers.ps1"
function FocusRetry($gp) { for ($i = 0; $i -lt 10; $i++) { try { Focus-Mc $gp; return } catch { Start-Sleep 4 } }; throw 'focus failed' }
$gp = Get-Gp; Pin-Mc $gp
C 'ec flag QaOw ec:flag_early_trial_unlocked set'; C 'gamemode creative QaOw'
C 'tp QaOw -389.5 65 344.5 -90 5'; Start-Sleep 6
FocusRetry $gp; Press-McKey $gp 'W' 2200; Start-Sleep 9
C 'data get entity QaOw Pos'; Start-Sleep 1
FocusRetry $gp; Save-McShot $gp "$Out\03_portal_walkin_entered_dungeon.png" | Out-Null
FocusRetry $gp; Chat '/ec dungeon leave'; Start-Sleep 9
C 'data get entity QaOw Pos'; Start-Sleep 1
FocusRetry $gp; Save-McShot $gp "$Out\04_portal_leave_back.png" | Out-Null

