# QA helper: back up dev server files and apply the QA pack config (embedded_http on 127.0.0.1, debug, given ui.theme mode).
param([string]$ThemeMode = 'full', [switch]$Restore)
$s = 'D:\test\mcmmo\dev\server'; $c = "$s\plugins\EtherealChronicle\config.yml"
$files = @("$s\server.properties", "$s\ops.json", "$s\whitelist.json", $c)
if ($Restore) {
    foreach ($f in $files) { if (Test-Path "$f.qa-orig") { Copy-Item "$f.qa-orig" $f -Force; Remove-Item -LiteralPath "$f.qa-orig" -Force } }
    Remove-Item -LiteralPath 'D:\test\mcmmo\dev\qa-console.txt' -Force -ErrorAction SilentlyContinue
    return
}
foreach ($f in $files) { if (-not (Test-Path "$f.qa-orig")) { Copy-Item $f "$f.qa-orig" -Force } }
(Get-Content "$s\server.properties") -replace '^server-ip=.*', 'server-ip=127.0.0.1' -replace '^online-mode=.*', 'online-mode=false' | Set-Content "$s\server.properties"
$repo = Resolve-Path "$PSScriptRoot\..\.."
$t = Get-Content "$repo\src\main\resources\config.yml" -Raw
$t = $t.Replace('mode: game_port # experimental; requires same_port_netty.enabled: true; use external_managed when another plugin/server.properties sends the pack', 'mode: embedded_http')
$t = [regex]::Replace($t, '(?m)^  mode: game_port.*$', '  mode: embedded_http')
$t = $t.Replace('  debug: false', '  debug: true').Replace('    bind_host: "0.0.0.0"', '    bind_host: "127.0.0.1"').Replace('    public_host: ""', '    public_host: "127.0.0.1"')
$t = [regex]::Replace($t, '(?m)^(    mode: )(full|icons_only|off)\s*$', ('${1}' + $ThemeMode))
Set-Content $c $t
Set-Content "$s\ops.json" '[]'
Select-String -Path $c -Pattern 'mode:|bind_host|public_host|debug:'
