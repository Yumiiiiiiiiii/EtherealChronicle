# QA cleanup: stop client + server, restore dev server files and remove the synthetic quest.
$repo = Resolve-Path "$PSScriptRoot\..\.."
. "$PSScriptRoot\qa-helpers.ps1"
$cdir = 'D:\test\mcmmo\dev\server\plugins\EtherealChronicle\content'
Get-Process java -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowTitle -like 'Minecraft*' } | Stop-Process -Force
Start-Sleep 2
C 'stop'; Start-Sleep 25
Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -match 'paper' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
Get-CimInstance Win32_Process -Filter "Name='pwsh.exe'" | Where-Object { $_.CommandLine -match 'server-host|launch-client' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
Start-Sleep 2
if (Test-Path -LiteralPath "$cdir\chapters\chapter_1.yml.qa-orig") { Copy-Item -LiteralPath "$cdir\chapters\chapter_1.yml.qa-orig" "$cdir\chapters\chapter_1.yml" -Force; Remove-Item -LiteralPath "$cdir\chapters\chapter_1.yml.qa-orig" -Force }
Remove-Item -LiteralPath "$cdir\quests\qa_auto_full.yml" -Force -ErrorAction SilentlyContinue
& "$PSScriptRoot\qa-setup-config.ps1" -Restore
'java left: ' + @(Get-Process java -ErrorAction SilentlyContinue).Count
'ports: ' + @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object { $_.LocalPort -in 25565, 25580 }).Count
'qa-orig left: ' + @(Get-ChildItem D:\test\mcmmo\dev\server -Recurse -Filter '*.qa-orig' -ErrorAction SilentlyContinue).Count
'qa quest file: ' + (Test-Path -LiteralPath "$cdir\quests\qa_auto_full.yml")
