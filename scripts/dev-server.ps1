<#
.SYNOPSIS  Build the plugin, install it into the dev Paper server and run the server in the foreground.
.DESCRIPTION
  - Offline-mode, Java 25 required. Console stdin is inherited, so type `ec validate`, `stop`, etc.
  - Output is tee'd to scripts/logs/dev-server-<timestamp>.log and analysed after exit
    (plugin load errors / exceptions => exit code 1).
  - Never writes eula=true. If not accepted: prints instructions, exits 2.
.EXAMPLE   ./scripts/dev-server.ps1 -SkipBuild -MemoryMb 3072
#>
param(
    [string]$JarPath = 'D:\test\mcmmo\dev\server\paper-26.3-257.jar',
    [string]$ServerDir,                # default: directory containing the Paper jar
    [switch]$SkipBuild,
    [int]$MemoryMb = 2048
)
. "$PSScriptRoot/lib.ps1"
if (-not $ServerDir) { $ServerDir = Split-Path $JarPath -Parent }
Test-JavaVersion
Initialize-Server $JarPath $ServerDir
$pj = if ($SkipBuild) { Get-BuiltJar } else { Build-Plugin }
Install-Plugin $pj $ServerDir
Assert-Eula $ServerDir

$logDir = Join-Path $PSScriptRoot 'logs'; New-Item -ItemType Directory -Force $logDir | Out-Null
$log = Join-Path $logDir ("dev-server-{0:yyyyMMdd-HHmmss}.log" -f (Get-Date))
Write-Step "starting Paper (log: $log)"
Push-Location $ServerDir
try {
    & java '-Xms512M' "-Xmx$($MemoryMb)M" -jar $JarPath --nogui 2>&1 | Tee-Object -FilePath $log
    $exit = $LASTEXITCODE
} finally { Pop-Location }

$r = Analyze-Log $log
Write-Step ("server exit={0} done={1} pluginEnabled={2} lines={3}" -f $exit, $r.ServerDone, $r.PluginEnabled, $r.Lines)
if ($r.Problems.Count) { Write-Host '[qa] problems:' -ForegroundColor Red; $r.Problems | Select-Object -First 40 | ForEach-Object { Write-Host $_ } }
if ($r.Problems.Count -or -not $r.PluginEnabled -or $exit -ne 0) { exit 1 }
