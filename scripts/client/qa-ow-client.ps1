# Overworld QA: join with a fresh offline client, accept the pack, show the class menu.
$repo = Resolve-Path "$PSScriptRoot\..\.."
. "$PSScriptRoot\mcwin.ps1"; . "$PSScriptRoot\qa-helpers.ps1"
$Out = 'C:\Users\Ymii\.traycer\epics\132bc8e7-97a8-4d19-af01-669c4ba69b04\artifacts\overworld-live-qa'
New-Item -ItemType Directory -Force -Path $Out | Out-Null
C 'whitelist add QaOw'; Start-Sleep 1
Get-ChildItem D:\test\mcmmo-client-qa\game -Filter 'servers.dat*' -ErrorAction SilentlyContinue | ForEach-Object { Remove-Item -LiteralPath $_.FullName -Force }
if (Test-Path -LiteralPath 'D:\test\mcmmo-client-qa\game\downloads') { Remove-Item -LiteralPath 'D:\test\mcmmo-client-qa\game\downloads' -Recurse -Force }
Start-Process pwsh -ArgumentList '-NoProfile', '-File', "$repo\scripts\client\launch-client.ps1", '-Name', 'QaOw', '-Server', 'localhost:25565' -WindowStyle Hidden
Start-Sleep 50
$gp = Get-Gp; Pin-Mc $gp; Focus-Mc $gp
Click-McAt $gp 405 515 'left'; Start-Sleep 15
Save-McShot $gp "$Out\00_first_join_menu.png" | Out-Null
