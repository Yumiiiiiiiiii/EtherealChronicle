# Overworld QA #4 part D: fishing near the projected Lumina pier spot ((10,28) offset from the site centre -> (-104, 357)).
$Out = 'C:\Users\Ymii\.traycer\epics\132bc8e7-97a8-4d19-af01-669c4ba69b04\artifacts\overworld-live-qa-gameplay'
. "$PSScriptRoot\mcwin.ps1"; . "$PSScriptRoot\qa-helpers.ps1"
function FocusRetry($gp) { for ($i = 0; $i -lt 10; $i++) { try { Focus-Mc $gp; return } catch { Start-Sleep 4 } }; throw 'focus failed' }
$gp = Get-Gp; Pin-Mc $gp
C 'ec give QaOw ec:item_tool_rod_oak 1'; Start-Sleep 2
C 'tp QaOw -104.5 90 350.5 0 35'; Start-Sleep 7
C 'data get entity QaOw Pos'; Start-Sleep 1
FocusRetry $gp; Press-McKey $gp 'D4'; Start-Sleep 1
FocusRetry $gp; Save-McShot $gp "$Out\08_fishing_spot_view.png" | Out-Null
C 'ec debug fish start QaOw'; Start-Sleep 2; C 'ec debug fish status QaOw'; Start-Sleep 1
FocusRetry $gp; Save-McShot $gp "$Out\09_fish_debug_reel.png" | Out-Null
C 'ec debug fish cancel QaOw'; Start-Sleep 2
