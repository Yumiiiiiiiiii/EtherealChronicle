# QA scenario 1: full-inventory NPC talk with a ready auto-complete quest.
$repo = Resolve-Path "$PSScriptRoot\..\.."
. "$PSScriptRoot\mcwin.ps1"; . "$PSScriptRoot\qa-helpers.ps1"
C "whitelist add QaG1"; Start-Sleep 1
Get-ChildItem D:\test\mcmmo-client-qa\game -Filter 'servers.dat*' -ErrorAction SilentlyContinue | ForEach-Object { Remove-Item -LiteralPath $_.FullName -Force }
Start-Process pwsh -ArgumentList '-NoProfile', '-File', "$repo\scripts\client\launch-client.ps1", '-Name', 'QaG1', '-Server', 'localhost:25565' -WindowStyle Hidden
Start-Sleep 50
C "op QaG1"; C "gamemode survival QaG1"; C "effect give QaG1 resistance 900 10 true"
Tp QaG1 0.5 65 0.5 0 0; Start-Sleep 3
$gp = Get-Gp; Pin-Mc $gp
Chat '/ec quest start ec:quest_qa_auto_full'; Start-Sleep 2
C "give QaG1 minecraft:dirt 2300"; Start-Sleep 3
Tp QaG1 2.5 65 -1.5 0 0; Start-Sleep 6
Shot z1_full_inv | Out-Null
