# Overworld QA: relaunch the client after an abnormal quit inside the dungeon; record where the server puts the player.
$repo = Resolve-Path "$PSScriptRoot\..\.."
$Out = 'C:\Users\Ymii\.traycer\epics\132bc8e7-97a8-4d19-af01-669c4ba69b04\artifacts\overworld-live-qa'
. "$PSScriptRoot\mcwin.ps1"; . "$PSScriptRoot\qa-helpers.ps1"
function FocusRetry($gp) { for ($i = 0; $i -lt 10; $i++) { try { Focus-Mc $gp; return } catch { Start-Sleep 4 } }; throw 'focus failed' }
Get-ChildItem D:\test\mcmmo-client-qa\game -Filter 'servers.dat*' -ErrorAction SilentlyContinue | ForEach-Object { Remove-Item -LiteralPath $_.FullName -Force }
if (Test-Path -LiteralPath 'D:\test\mcmmo-client-qa\game\downloads') { Remove-Item -LiteralPath 'D:\test\mcmmo-client-qa\game\downloads' -Recurse -Force }
Start-Process pwsh -ArgumentList '-NoProfile', '-File', "$repo\scripts\client\launch-client.ps1", '-Name', 'QaOw', '-Server', 'localhost:25565' -WindowStyle Hidden
Start-Sleep 50
$gp = Get-Gp; Pin-Mc $gp; FocusRetry $gp
Click-McAt $gp 405 515 'left'; Start-Sleep 14
C 'data get entity QaOw Pos'; Start-Sleep 1
FocusRetry $gp; Save-McShot $gp "$Out\18_after_quit_rescue.png" | Out-Null
