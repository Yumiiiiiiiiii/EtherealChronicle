# QA scenario 2: gated relog with a mid-air saved position (non-op, required pack wait). Part 1: save the mid-air position and kill the client.
$repo = Resolve-Path "$PSScriptRoot\..\.."
. "$PSScriptRoot\mcwin.ps1"; . "$PSScriptRoot\qa-helpers.ps1"
C "ec debug fish cancel QaG1"; Start-Sleep 1
C "deop QaG1"; C "effect clear QaG1"; Start-Sleep 1
C "gamemode survival QaG1"
# 高度 140 に tp し、保存位置が空中になるよう直後にクライアントを終了する
Tp QaG1 10.5 140 22.5 0 0; Start-Sleep -Milliseconds 700
Stop-Process -Id (Get-Gp) -Force
Start-Sleep 4
Get-ChildItem D:\test\mcmmo-client-qa\game -Filter 'servers.dat*' -ErrorAction SilentlyContinue | ForEach-Object { Remove-Item -LiteralPath $_.FullName -Force }
if (Test-Path -LiteralPath 'D:\test\mcmmo-client-qa\game\downloads') { Remove-Item -LiteralPath 'D:\test\mcmmo-client-qa\game\downloads' -Recurse -Force }
Start-Process pwsh -ArgumentList '-NoProfile', '-File', "$repo\scripts\client\launch-client.ps1", '-Name', 'QaG1', '-Server', 'localhost:25565' -WindowStyle Hidden
Start-Sleep 45
$gp = Get-Gp; Pin-Mc $gp; Focus-Mc $gp
Shot z2_gate_wait_10s | Out-Null
