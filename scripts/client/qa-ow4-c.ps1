# Overworld QA #4 part C: gathering at the projected Lumina pebble ore (offset (-24,-4) from the site centre (-114,329)).
$Out = 'C:\Users\Ymii\.traycer\epics\132bc8e7-97a8-4d19-af01-669c4ba69b04\artifacts\overworld-live-qa-gameplay'
. "$PSScriptRoot\mcwin.ps1"; . "$PSScriptRoot\qa-helpers.ps1"
function FocusRetry($gp) { for ($i = 0; $i -lt 10; $i++) { try { Focus-Mc $gp; return } catch { Start-Sleep 4 } }; throw 'focus failed' }
$gp = Get-Gp; Pin-Mc $gp
C 'ec give QaOw ec:item_tool_pick_iron 1'; Start-Sleep 2
C 'effect give QaOw resistance 3000 10 true'
C 'tp QaOw -138.5 90 321.5 0 15'; C 'gamemode survival QaOw'; Start-Sleep 6
C 'data get entity QaOw Pos'; Start-Sleep 1
FocusRetry $gp; Press-McKey $gp 'D2'; Start-Sleep 1
FocusRetry $gp; Save-McShot $gp "$Out\05_gather_node_aim.png" | Out-Null
FocusRetry $gp; RClick; Start-Sleep -Milliseconds 800
C 'ec debug gather status QaOw'; Start-Sleep 1
FocusRetry $gp; Save-McShot $gp "$Out\06_gather_channel.png" | Out-Null
Start-Sleep 4
C 'ec debug gather status QaOw'; Start-Sleep 1
FocusRetry $gp; Save-McShot $gp "$Out\07_gather_done.png" | Out-Null
