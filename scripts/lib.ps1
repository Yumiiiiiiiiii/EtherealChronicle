# Shared helpers for dev-server.ps1 / smoke-test.ps1. Dot-source: . "$PSScriptRoot/lib.ps1"
Set-StrictMode -Version Latest
$script:RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$script:PluginName = 'EtherealChronicle'

function Write-Step([string]$m) { Write-Host "[qa] $m" -ForegroundColor Cyan }
function Fail([string]$m, [int]$code = 1) { Write-Host "[qa] FAIL: $m" -ForegroundColor Red; exit $code }

function Get-BuiltJar {
    $jar = Get-ChildItem "$script:RepoRoot\build\libs\$script:PluginName-*.jar" -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $jar) { Fail 'plugin jar not found in build/libs (run without -SkipBuild)' 10 }
    return $jar.FullName
}

function Build-Plugin {
    Write-Step 'gradlew build (tests included)'
    Push-Location $script:RepoRoot
    try {
        & "$script:RepoRoot\gradlew.bat" build --console=plain | Out-Host
        if ($LASTEXITCODE -ne 0) { Fail 'gradle build failed' 10 }
    } finally { Pop-Location }
    return Get-BuiltJar
}

function Install-Plugin([string]$pluginJar, [string]$serverDir) {
    $plugins = Join-Path $serverDir 'plugins'
    New-Item -ItemType Directory -Force $plugins | Out-Null
    Get-ChildItem $plugins -Filter "$script:PluginName-*.jar" -ErrorAction SilentlyContinue | Remove-Item -Force
    Copy-Item $pluginJar $plugins
    Write-Step "installed $(Split-Path $pluginJar -Leaf) -> $plugins"
}

function Assert-Eula([string]$serverDir) {
    # Never writes eula.txt. The human operator must accept https://aka.ms/MinecraftEULA themselves.
    $eula = Join-Path $serverDir 'eula.txt'
    $ok = (Test-Path $eula) -and ((Get-Content $eula -Raw) -match '(?im)^\s*eula\s*=\s*true\s*$')
    if (-not $ok) {
        Write-Host "[qa] BLOCKED: Minecraft EULA not accepted ($eula)." -ForegroundColor Yellow
        Write-Host '[qa] Read https://aka.ms/MinecraftEULA ; if you agree, set eula=true in that file yourself, then re-run.' -ForegroundColor Yellow
        Write-Host '[qa] This script deliberately never writes eula=true.' -ForegroundColor Yellow
        exit 2
    }
}

function Initialize-Server([string]$jar, [string]$serverDir) {
    if (-not (Test-Path $jar)) { Fail "Paper jar not found: $jar" 3 }
    New-Item -ItemType Directory -Force $serverDir | Out-Null
    $props = Join-Path $serverDir 'server.properties'
    if (-not (Test-Path $props)) {
        # Seed a minimal offline-mode config on a fresh dir only; Paper fills in the rest.
        "online-mode=false`nserver-port=25565`nlevel-name=world`nview-distance=6`nspawn-protection=0`nenable-rcon=false`n" |
            Set-Content $props -Encoding ascii
    } else {
        $c = Get-Content $props -Raw
        if ($c -notmatch '(?m)^online-mode=false') {
            if ($c -match '(?m)^online-mode=.*$') { $c = $c -replace '(?m)^online-mode=.*$', 'online-mode=false' } else { $c += "`nonline-mode=false`n" }
            Set-Content $props $c -Encoding ascii -NoNewline
            Write-Step 'forced online-mode=false in server.properties (offline test server)'
        }
    }
}

function Test-JavaVersion {
    $v = (& java -version 2>&1 | Select-Object -First 1) -join ''
    if ($v -notmatch '"(2[5-9]|[3-9]\d)\.') { Fail "Java 25+ required, found: $v" 4 }
}

# Patterns that mean "something is wrong" in captured server output.
$script:BadPatterns = @(
    "Could not load 'plugins[/\\]EtherealChronicle",
    'Error occurred while enabling EtherealChronicle',
    'Disabling EtherealChronicle',
    'Exception',
    '\[ERROR\]',
    '/ERROR\]',
    'Unhandled exception',
    'Failed to (start|bind|load)',
    'Encountered an unexpected exception'
)

function Analyze-Log([string]$logFile) {
    $lines = @(Get-Content $logFile)
    $problems = @()
    for ($i = 0; $i -lt $lines.Count; $i++) {
        foreach ($p in $script:BadPatterns) {
            if ($lines[$i] -match $p) { $problems += ('{0,5}: {1}' -f ($i + 1), $lines[$i]); break }
        }
    }
    $enabled = [bool]($lines | Where-Object { $_ -match "Enabling $script:PluginName" })
    $done = [bool]($lines | Where-Object { $_ -match 'Done \(' })
    [pscustomobject]@{ Problems = $problems; PluginEnabled = $enabled; ServerDone = $done; Lines = $lines.Count }
}
