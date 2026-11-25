<#
.SYNOPSIS  Overworld E2E: 専用テストサーバーを起動 -> 全拠点 READY を待つ -> (-Bot 指定時) mineflayer ボットが移動/死亡/ダンジョンを自動検証 -> 停止 -> ログ解析。
.DESCRIPTION
  - 開発サーバー(dev/server)には触れず、使い捨ての dev/e2e-server（ポート 25599）を使います。-Clean で毎回作り直し（このディレクトリのみ削除）。
  - EULA は自動承認しません。dev/server/eula.txt が eula=true の場合に限りその内容をコピーします。
  - ボット(scripts/bot/e2e.js)は offline-mode のローカル専用です。コンソールから `op EcE2E` を実行して管理コマンドを使います。
  終了コード: 0 全 PASS / 1 失敗 / 2 EULA 未承認 / 3 jar なし / 4 Java 違い / 10 ビルド失敗 / 11 Node・npm 失敗
.EXAMPLE   ./scripts/e2e-overworld.ps1 -Clean            # ビルド込み・新規ワールドで全項目
.EXAMPLE   ./scripts/e2e-overworld.ps1 -SkipBuild        # 既存ワールドを再利用（街生成を待たない）
#>
param(
    [string]$JarPath = 'D:\test\mcmmo\dev\server\paper-26.3-257.jar',
    [string]$ServerDir = 'D:\test\mcmmo\dev\e2e-server',
    [switch]$SkipBuild,
    [switch]$Clean,
    [switch]$Bot,                           # ボット検証（要: 対象サーバーのプロトコルに対応した mineflayer）
    [int]$Port = 25599,
    [string]$BotName = 'EcE2E',
    [string]$ProtocolVersion = '',          # mineflayer に渡すバージョン（空なら自動）
    [int]$StartTimeoutSec = 240,
    [int]$SitesTimeoutSec = 900,            # 街の生成完了待ち
    [int]$MemoryMb = 3072
)
. "$PSScriptRoot/lib.ps1"
$sourceDir = Split-Path $JarPath -Parent
$expectedSites = 9

Test-JavaVersion
if (-not (Test-Path $JarPath)) { Fail "Paper jar not found: $JarPath" 3 }
if (-not (Get-Command node -ErrorAction SilentlyContinue)) { Fail 'node not found (Node.js が必要です)' 11 }

# --- テストサーバーの用意（このスクリプトが削除してよいのは $ServerDir だけ） ---
if ($Clean -and (Test-Path $ServerDir)) {
    if ((Resolve-Path $ServerDir).Path -notmatch '[\\/]e2e-server$') { Fail "refusing to delete unexpected dir: $ServerDir" 1 }
    Write-Step "cleaning $ServerDir"; Remove-Item $ServerDir -Recurse -Force
}
New-Item -ItemType Directory -Force $ServerDir | Out-Null
$serverJar = Join-Path $ServerDir (Split-Path $JarPath -Leaf)
if (-not (Test-Path $serverJar)) { Copy-Item $JarPath $serverJar }
foreach ($d in 'libraries', 'versions', 'cache') {      # 初回ダウンロード済みの Paper 依存を流用して起動を速くする
    if ((Test-Path (Join-Path $sourceDir $d)) -and -not (Test-Path (Join-Path $ServerDir $d))) { Copy-Item (Join-Path $sourceDir $d) (Join-Path $ServerDir $d) -Recurse }
}
$props = Join-Path $ServerDir 'server.properties'
if (-not (Test-Path $props)) {
    "online-mode=false`nserver-ip=127.0.0.1`nserver-port=$Port`nlevel-name=world`nview-distance=6`nspawn-protection=0`nenable-rcon=false`nmotd=EC E2E`n" | Set-Content $props -Encoding ascii
}
$eula = Join-Path $ServerDir 'eula.txt'
$srcEula = Join-Path $sourceDir 'eula.txt'
if (-not (Test-Path $eula) -and (Test-Path $srcEula) -and ((Get-Content $srcEula -Raw) -match '(?im)^\s*eula\s*=\s*true\s*$')) { Copy-Item $srcEula $eula }
Assert-Eula $ServerDir

$pj = if ($SkipBuild) { Get-BuiltJar } else { Build-Plugin }
Install-Plugin $pj $ServerDir

# --- ボット依存 ---
$botDir = Join-Path $PSScriptRoot 'bot'
if (-not (Test-Path (Join-Path $botDir 'node_modules'))) {
    Write-Step 'npm ci (scripts/bot)'
    Push-Location $botDir; try { & npm ci --no-audit --no-fund | Out-Host; if ($LASTEXITCODE -ne 0) { Fail 'npm ci failed' 11 } } finally { Pop-Location }
}

# --- サーバー起動（smoke-test.ps1 と同じ方式で標準入出力を保持） ---
$logDir = Join-Path $PSScriptRoot 'logs'; New-Item -ItemType Directory -Force $logDir | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$log = Join-Path $logDir "e2e-$stamp.log"; $botLog = Join-Path $logDir "e2e-bot-$stamp.log"
New-Item -ItemType File -Force $log | Out-Null
$psi = [System.Diagnostics.ProcessStartInfo]::new('java')
foreach ($a in @('-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', '-Xms512M', "-Xmx$($MemoryMb)M", '-jar', $serverJar, '--nogui')) { $psi.ArgumentList.Add($a) }
$psi.WorkingDirectory = $ServerDir
$psi.RedirectStandardInput = $true; $psi.RedirectStandardOutput = $true; $psi.RedirectStandardError = $true; $psi.UseShellExecute = $false
$psi.StandardOutputEncoding = [System.Text.Encoding]::UTF8; $psi.StandardErrorEncoding = [System.Text.Encoding]::UTF8
if (-not ('QaLineSink' -as [type])) {
    Add-Type -TypeDefinition @'
using System.Collections.Concurrent;
using System.Diagnostics;
public class QaLineSink {
    public readonly ConcurrentQueue<string> Queue = new ConcurrentQueue<string>();
    public void Attach(Process p) {
        p.OutputDataReceived += (s, e) => { if (e.Data != null) Queue.Enqueue(e.Data); };
        p.ErrorDataReceived += (s, e) => { if (e.Data != null) Queue.Enqueue(e.Data); };
    }
}
'@
}
$sink = [QaLineSink]::new(); $queue = $sink.Queue
$p = [System.Diagnostics.Process]::new(); $p.StartInfo = $psi; $sink.Attach($p)
[void]$p.Start(); $p.BeginOutputReadLine(); $p.BeginErrorReadLine()
Write-Step "server pid $($p.Id) port $Port, log $log"

function Flush-Log { $l = $null; while ($queue.TryDequeue([ref]$l)) { Add-Content $log $l -Encoding utf8 } }
function Wait-Log([string]$regex, [int]$sec) {
    $end = (Get-Date).AddSeconds($sec)
    while ((Get-Date) -lt $end -and -not $p.HasExited) {
        Start-Sleep -Milliseconds 500; Flush-Log
        if (Select-String -Path $log -Pattern $regex -Quiet) { return $true }
    }
    Flush-Log; return (Select-String -Path $log -Pattern $regex -Quiet)
}
function Send-Console([string]$cmd) { Write-Step "> $cmd"; $p.StandardInput.WriteLine($cmd) }

$failed = $false; $botExit = -1
try {
    if (-not (Wait-Log 'Done \(' $StartTimeoutSec)) { Write-Host '[e2e] server did not reach Done' -ForegroundColor Red; $failed = $true }
    elseif (-not (Wait-Log '準備が完了|Adventure systems are ready\.' 60)) { Write-Host '[e2e] plugin never reported ready' -ForegroundColor Red; $failed = $true }
    else {
        # 全拠点 READY まで `ec sites` をポーリング（出力行: "<id>: <position> / <biome> / Ready"）
        $deadline = (Get-Date).AddSeconds($SitesTimeoutSec); $ready = 0
        while ((Get-Date) -lt $deadline -and -not $p.HasExited) {
            $mark = (Get-Content $log).Count
            Send-Console 'ec sites'; Start-Sleep -Seconds 4; Flush-Log
            $new = @(Get-Content $log | Select-Object -Skip $mark)
            $ready = @($new | Where-Object { $_ -match 'ec:area_[a-z0-9_]+: .* / (Ready|完成)\s*$' }).Count
            $bad = @($new | Where-Object { $_ -match 'ec:area_[a-z0-9_]+: .*(Failed|失敗)' }).Count
            Write-Step "sites READY: $ready / $expectedSites (failed: $bad)"
            if ($bad -gt 0) { Write-Host '[e2e] a site FAILED to build' -ForegroundColor Red; $failed = $true; break }
            if ($ready -ge $expectedSites) { break }
            Start-Sleep -Seconds 10
        }
        if ($ready -lt $expectedSites) { Write-Host "[e2e] sites not all READY ($ready/$expectedSites)" -ForegroundColor Red; $failed = $true }
        else {
            if (-not $Bot) { Write-Step 'bot scenario skipped (use -Bot; mineflayer must support the server protocol)'; $botExit = 0 }
            else {
            Send-Console "op $BotName"; Start-Sleep -Seconds 2
            $nodeArgs = @('e2e.js', '--port', $Port, '--name', $BotName)
            if ($ProtocolVersion) { $nodeArgs += @('--version', $ProtocolVersion) }
            Write-Step 'running bot scenario'
            Push-Location $botDir
            try { & node @nodeArgs 2>&1 | Tee-Object -FilePath $botLog | Out-Host; $botExit = $LASTEXITCODE } finally { Pop-Location }
            Flush-Log
            if ($botExit -ne 0) { $failed = $true }
            }
        }
    }
} finally {
    if (-not $p.HasExited) {
        $p.StandardInput.WriteLine('stop')
        if (-not $p.WaitForExit(90000)) { Write-Host '[e2e] stop timed out, killing' -ForegroundColor Yellow; $p.Kill($true); $failed = $true }
    }
    $p.WaitForExit(); Start-Sleep -Milliseconds 500; Flush-Log
}

# --- 集計 ---
$r = Analyze-Log $log
$steps = @(); if (Test-Path $botLog) { $steps = @(Select-String -Path $botLog -Pattern '^RESULT (PASS|FAIL) ' | ForEach-Object { $_.Line }) }
Write-Host ''; Write-Host '===== E2E RESULT =====' -ForegroundColor Cyan
$steps | ForEach-Object { Write-Host $_ -ForegroundColor $(if ($_ -match '^RESULT PASS') { 'Green' } else { 'Red' }) }
Write-Step ("bot exit={0} pluginEnabled={1} serverLog={2}" -f $botExit, $r.PluginEnabled, $log)
if ($r.Problems.Count) { Write-Host '[e2e] server log problems:' -ForegroundColor Red; $r.Problems | Select-Object -First 30 | ForEach-Object { Write-Host $_ }; $failed = $true }
if (-not $r.PluginEnabled) { $failed = $true }
if ($Bot -and $steps.Count -eq 0) { $failed = $true }
if ($failed) { Write-Host '[e2e] FAIL' -ForegroundColor Red; exit 1 }
Write-Host '[e2e] PASS' -ForegroundColor Green
