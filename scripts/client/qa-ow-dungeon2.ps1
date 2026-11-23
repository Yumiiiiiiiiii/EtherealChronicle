# Overworld QA: leave -> cleaning -> re-enter (slot reuse), then quit-mid-dungeon is done separately.
$Out = 'C:\Users\Ymii\.traycer\epics\132bc8e7-97a8-4d19-af01-669c4ba69b04\artifacts\overworld-live-qa'
. "$PSScriptRoot\mcwin.ps1"; . "$PSScriptRoot\qa-helpers.ps1"
function FocusRetry($gp) { for ($i = 0; $i -lt 10; $i++) { try { Focus-Mc $gp; return } catch { Start-Sleep 4 } }; throw 'focus failed' }
$gp = Get-Gp; Pin-Mc $gp
FocusRetry $gp; Chat '/ec dungeon leave'; Start-Sleep 10
C 'data get entity QaOw Pos'; C 'ec sites'; Start-Sleep 1
FocusRetry $gp; Save-McShot $gp "$Out\16_dungeon_left_back_in_overworld.png" | Out-Null
Start-Sleep 20
FocusRetry $gp; Chat '/ec dungeon ec:dungeon_novice_arena'; Start-Sleep 10
C 'data get entity QaOw Pos'; Start-Sleep 1
FocusRetry $gp; Save-McShot $gp "$Out\17_dungeon_second_entry.png" | Out-Null
C 'tps'
