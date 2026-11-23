# Overworld QA #4 part A: join, class, then portal walk-in test at the Ashwood novice arena portal.
$repo = Resolve-Path "$PSScriptRoot\..\.."
$Out = 'C:\Users\Ymii\.traycer\epics\132bc8e7-97a8-4d19-af01-669c4ba69b04\artifacts\overworld-live-qa-gameplay'
New-Item -ItemType Directory -Force -Path $Out | Out-Null
. "$PSScriptRoot\mcwin.ps1"; . "$PSScriptRoot\qa-helpers.ps1"
function FocusRetry($gp) { for ($i = 0; $i -lt 10; $i++) { try { Focus-Mc $gp; return } catch { Start-Sleep 4 } }; throw 'focus failed' }
$o = 'D:\test\mcmmo-client-qa\game\options.txt'
(Get-Content -LiteralPath $o) -replace '^pauseOnLostFocus:true', 'pauseOnLostFocus:false' | Set-Content -LiteralPath $o
C 'whitelist add QaOw'; Start-Sleep 1
Get-ChildItem D:\test\mcmmo-client-qa\game -Filter 'servers.dat*' -ErrorAction SilentlyContinue | ForEach-Object { Remove-Item -LiteralPath $_.FullName -Force }
if (Test-Path -LiteralPath 'D:\test\mcmmo-client-qa\game\downloads') { Remove-Item -LiteralPath 'D:\test\mcmmo-client-qa\game\downloads' -Recurse -Force }
Start-Process pwsh -ArgumentList '-NoProfile', '-File', "$repo\scripts\client\launch-client.ps1", '-Name', 'QaOw', '-Server', 'localhost:25565' -WindowStyle Hidden
Start-Sleep 50
$gp = Get-Gp; Pin-Mc $gp; FocusRetry $gp
Click-McAt $gp 405 515 'left'; Start-Sleep 14
FocusRetry $gp; Click-McAt $gp 692 237 'left'; Start-Sleep 3
FocusRetry $gp; Click-McAt $gp 327 670 'left'; Start-Sleep 6
C 'op QaOw'; C 'weather clear'; C 'time set 6000'; C 'gamerule advance_time false'; C 'gamerule advance_weather false'
C 'effect give QaOw resistance 3000 10 true'; C 'gamemode survival QaOw'; Start-Sleep 2
# ポータル (-383, 64..68, 343..345) へ西から歩いて入る
C 'tp QaOw -389.5 65 344.5 -90 5'; Start-Sleep 6
FocusRetry $gp; Save-McShot $gp "$Out\01_portal_before_walk.png" | Out-Null
C 'data get entity QaOw Pos'; Start-Sleep 1
FocusRetry $gp; Press-McKey $gp 'W' 2200; Start-Sleep 7
C 'data get entity QaOw Pos'; Start-Sleep 1
FocusRetry $gp; Save-McShot $gp "$Out\02_portal_walkin_result.png" | Out-Null
