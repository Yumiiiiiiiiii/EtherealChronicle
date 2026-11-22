# Task 25 real-client run, phase 1: QA config, start server + fresh client (new player => pack gate + class menu).
$repo = Resolve-Path "$PSScriptRoot\..\.."
. "$PSScriptRoot\mcwin.ps1"; . "$PSScriptRoot\qa-helpers.ps1"
& "$PSScriptRoot\qa-setup-config.ps1" -ThemeMode full | Out-Null
Remove-Item -LiteralPath 'D:\test\mcmmo\dev\qa-console.txt' -Force -ErrorAction SilentlyContinue
Start-Process pwsh -ArgumentList '-NoProfile', '-File', "$repo\scripts\client\server-host.ps1" -WindowStyle Hidden
Start-Sleep 55
C 'whitelist add QaT25'; Start-Sleep 1
Get-ChildItem D:\test\mcmmo-client-qa\game -Filter 'servers.dat*' -ErrorAction SilentlyContinue | ForEach-Object { Remove-Item -LiteralPath $_.FullName -Force }
if (Test-Path -LiteralPath 'D:\test\mcmmo-client-qa\game\downloads') { Remove-Item -LiteralPath 'D:\test\mcmmo-client-qa\game\downloads' -Recurse -Force }
Start-Process pwsh -ArgumentList '-NoProfile', '-File', "$repo\scripts\client\launch-client.ps1", '-Name', 'QaT25', '-Server', 'localhost:25565' -WindowStyle Hidden
Start-Sleep 50
$gp = Get-Gp; Pin-Mc $gp; Focus-Mc $gp
Shot t25_a_prompt | Out-Null
Click-McAt $gp 405 515 'left'; Start-Sleep 12
Shot t25_b_classmenu | Out-Null
