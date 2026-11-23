# Overworld QA: restart the client with pauseOnLostFocus disabled, rejoin as QaOw (class already chosen), spectator + F1.
$repo = Resolve-Path "$PSScriptRoot\..\.."
. "$PSScriptRoot\mcwin.ps1"; . "$PSScriptRoot\qa-helpers.ps1"
function FocusRetry($gp) { for ($i = 0; $i -lt 10; $i++) { try { Focus-Mc $gp; return } catch { Start-Sleep 4 } }; throw 'focus failed after retries' }
Get-Process java | Where-Object { $_.MainWindowTitle -like 'Minecraft*' } | Stop-Process -Force
Start-Sleep 3
$o = 'D:\test\mcmmo-client-qa\game\options.txt'
(Get-Content -LiteralPath $o) -replace '^pauseOnLostFocus:true', 'pauseOnLostFocus:false' | Set-Content -LiteralPath $o
Get-ChildItem D:\test\mcmmo-client-qa\game -Filter 'servers.dat*' -ErrorAction SilentlyContinue | ForEach-Object { Remove-Item -LiteralPath $_.FullName -Force }
if (Test-Path -LiteralPath 'D:\test\mcmmo-client-qa\game\downloads') { Remove-Item -LiteralPath 'D:\test\mcmmo-client-qa\game\downloads' -Recurse -Force }
Start-Process pwsh -ArgumentList '-NoProfile', '-File', "$repo\scripts\client\launch-client.ps1", '-Name', 'QaOw', '-Server', 'localhost:25565' -WindowStyle Hidden
Start-Sleep 50
$gp = Get-Gp; Pin-Mc $gp; FocusRetry $gp
Click-McAt $gp 405 515 'left'; Start-Sleep 14
C 'op QaOw'; C 'gamemode spectator QaOw'; C 'weather clear'; C 'time set 6000'; Start-Sleep 2
$script:VK['F1'] = 0x70; FocusRetry $gp; Press-McKey $gp 'F1'
