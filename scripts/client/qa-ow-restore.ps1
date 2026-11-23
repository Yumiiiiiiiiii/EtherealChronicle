# Overworld QA teardown: stop everything and put the backed-up world/plugin data and configs back. -KeepRun saves the QA world as world.qa-run-<tag>.
param([string]$Tag = '')
$s = 'D:\test\mcmmo\dev\server'
. "$PSScriptRoot\qa-helpers.ps1"
Get-Process java -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowTitle -like 'Minecraft*' } | Stop-Process -Force
Start-Sleep 2
if (Test-Path -LiteralPath 'D:\test\mcmmo\dev\qa-console.txt') { C 'stop'; Start-Sleep 25 }
Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -match 'paper-26' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
Get-CimInstance Win32_Process -Filter "Name='pwsh.exe'" | Where-Object { $_.CommandLine -match 'server-host|launch-client' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
Start-Sleep 3
foreach ($n in 'world', 'plugins\EtherealChronicle') {
    $bak = "$s\$n.qa-bak-ow"
    if (-not (Test-Path -LiteralPath $bak)) { continue }
    if (Test-Path -LiteralPath "$s\$n") {
        $keep = if ($Tag) { (Split-Path $n -Leaf) + ".qa-run-$Tag" } else { $null }
        if ($keep) { Rename-Item -LiteralPath "$s\$n" -NewName $keep } else { Remove-Item -LiteralPath "$s\$n" -Recurse -Force }
    }
    Rename-Item -LiteralPath $bak -NewName (Split-Path $n -Leaf)
}
& "$PSScriptRoot\qa-setup-config.ps1" -Restore
if (Test-Path -LiteralPath "$s\usercache.json.qa-orig") { Copy-Item -LiteralPath "$s\usercache.json.qa-orig" "$s\usercache.json" -Force; Remove-Item -LiteralPath "$s\usercache.json.qa-orig" -Force }
'java(paper) left: ' + @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -match 'paper-26' }).Count
'ports: ' + @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object { $_.LocalPort -in 25565, 25580 }).Count
'bak left: ' + @(Get-ChildItem $s, "$s\plugins" -Filter '*.qa-bak-ow' -ErrorAction SilentlyContinue).Count
