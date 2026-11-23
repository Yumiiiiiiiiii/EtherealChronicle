# Overworld QA: move the dev world + plugin data aside, install the jar, apply QA config and (optionally) world.mode: overworld.
param([string]$Jar, [switch]$DefaultMode)
$s = 'D:\test\mcmmo\dev\server'; $repo = Resolve-Path "$PSScriptRoot\..\.."
if (Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -match 'paper-26' }) { throw 'a Paper server is still running' }
foreach ($n in 'world', 'plugins\EtherealChronicle') {
    $bak = "$s\$n.qa-bak-ow"
    if (Test-Path -LiteralPath $bak) { throw "backup already exists: $bak" }
    if (Test-Path -LiteralPath "$s\$n") { Rename-Item -LiteralPath "$s\$n" -NewName ((Split-Path $bak -Leaf)) }
}
Copy-Item -LiteralPath $Jar -Destination "$s\plugins\EtherealChronicle-0.1.0.jar" -Force
New-Item -ItemType Directory -Force -Path "$s\plugins\EtherealChronicle" | Out-Null
Copy-Item -LiteralPath "$repo\src\main\resources\config.yml" -Destination "$s\plugins\EtherealChronicle\config.yml" -Force
& "$PSScriptRoot\qa-setup-config.ps1" -ThemeMode icons_only | Out-Null
foreach ($f in 'usercache.json') { if (-not (Test-Path -LiteralPath "$s\$f.qa-orig") -and (Test-Path -LiteralPath "$s\$f")) { Copy-Item -LiteralPath "$s\$f" "$s\$f.qa-orig" } }
if (-not $DefaultMode) { Add-Content -LiteralPath "$s\plugins\EtherealChronicle\config.yml" -Value "`nworld:`n  mode: overworld`n" -Encoding utf8 }
Remove-Item -LiteralPath 'D:\test\mcmmo\dev\qa-console.txt' -Force -ErrorAction SilentlyContinue
'setup done; defaultMode=' + [bool]$DefaultMode
