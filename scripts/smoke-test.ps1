<#
.SYNOPSIS  Headless smoke test: build -> install -> start Paper -> wait for 'Done' -> run console commands -> stop -> analyse logs.
.EXAMPLE   ./scripts/smoke-test.ps1 -Commands 'ec validate','ec reload'
  Exit codes: 0 pass, 1 test failure, 2 EULA not accepted, 3 jar missing, 4 wrong Java, 10 build failed.
#>
param(
    [string]$JarPath = 'D:\test\mcmmo\dev\server\paper-26.3-257.jar',
    [string]$ServerDir,
    [switch]$SkipBuild,
    # a command may contain {uuid}: replaced by the UUID of the last `SIM spawn=<uuid>` log line
    [string[]]$Commands = @('ec validate'),
    [int]$StartTimeoutSec = 180,
    [int]$HoldSeconds = 0,          # keep the server up after the commands (e.g. so scripts/bot can join)
    [int]$CommandWaitSec = 3,
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
$log = Join-Path $logDir ("smoke-{0:yyyyMMdd-HHmmss}.log" -f (Get-Date))
New-Item -ItemType File -Force $log | Out-Null
$psi = [System.Diagnostics.ProcessStartInfo]::new('java')
foreach ($a in @('-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', '-Xms512M', "-Xmx$($MemoryMb)M", '-jar', $JarPath, '--nogui')) { $psi.ArgumentList.Add($a) }
$psi.WorkingDirectory = $ServerDir
$psi.RedirectStandardInput = $true; $psi.RedirectStandardOutput = $true; $psi.RedirectStandardError = $true
$psi.UseShellExecute = $false
$psi.StandardOutputEncoding = [System.Text.Encoding]::UTF8; $psi.StandardErrorEncoding = [System.Text.Encoding]::UTF8
# PowerShell scriptblocks cannot be event handlers on thread-pool threads (crashes pwsh), so use a compiled sink.
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
$p = [System.Diagnostics.Process]::new(); $p.StartInfo = $psi
$sink.Attach($p)
[void]$p.Start(); $p.BeginOutputReadLine(); $p.BeginErrorReadLine()
Write-Step "server pid $($p.Id), log $log"

function Flush-Log { $l = $null; while ($queue.TryDequeue([ref]$l)) { Add-Content $log $l -Encoding utf8 } }
function Wait-Log([string]$regex, [int]$sec) {
    $end = (Get-Date).AddSeconds($sec)
    while ((Get-Date) -lt $end -and -not $p.HasExited) {
        Start-Sleep -Milliseconds 500; Flush-Log
        if (Select-String -Path $log -Pattern $regex -Quiet) { return $true }
    }
    Flush-Log; return (Select-String -Path $log -Pattern $regex -Quiet)
}
$failed = $false
try {
    if (-not (Wait-Log 'Done \(' $StartTimeoutSec)) { Write-Host '[qa] server did not reach Done' -ForegroundColor Red; $failed = $true }
    else {
        # plugin startup is async: wait until it reports ready so early commands are not answered with 起動中
        [void](Wait-Log '準備が完了|Adventure systems are ready\.' 30)
        foreach ($c in $Commands) {
            if ($c -match '^sleep:(\d+)$') { Start-Sleep -Seconds ([int]$Matches[1]); Flush-Log; continue }   # pseudo-command
            if ($c -match '\{uuid\}') { Flush-Log; $last = (Select-String -Path $log -Pattern 'SIM spawn=([0-9a-f-]{36})' | Select-Object -Last 1); if ($last) { $c = $c -replace '\{uuid\}', $last.Matches[0].Groups[1].Value } }
            Write-Step "> $c"; $p.StandardInput.WriteLine($c); Start-Sleep -Seconds $CommandWaitSec; Flush-Log
        }
        $mem = Get-Process java -ErrorAction SilentlyContinue | Where-Object { $_.Id -eq $p.Id -or (Get-CimInstance Win32_Process -Filter "ProcessId=$($_.Id)").ParentProcessId -eq $p.Id } | Sort-Object WorkingSet64 -Descending | Select-Object -First 1
        if ($mem) { Write-Step ('server JVM working set: {0:N0} MB (pid {1})' -f ($mem.WorkingSet64 / 1MB), $mem.Id) }
        if ($HoldSeconds -gt 0) { Write-Step "holding server for $HoldSeconds s"; for ($i = 0; $i -lt $HoldSeconds; $i++) { Start-Sleep -Seconds 1; Flush-Log } }
    }
} finally {
    if (-not $p.HasExited) {
        $p.StandardInput.WriteLine('stop')
        if (-not $p.WaitForExit(60000)) { Write-Host '[qa] stop timed out, killing' -ForegroundColor Yellow; $p.Kill($true); $failed = $true }
    }
    $p.WaitForExit()
    Start-Sleep -Milliseconds 500; Flush-Log
}
$r = Analyze-Log $log
$validateCommands = @($Commands | Where-Object { $_ -match '(?i)^\s*ec\s+validate(\s|$)' })
if ($validateCommands.Count -gt 0 -and
    -not (Select-String -Path $log -Pattern 'EC_CONTENT_VALIDATION_SUCCESS|コンテンツ検証成功。|Content validation succeeded\.' -Quiet)) {
    Write-Host '[qa] ec validate was requested, but its success reply was not present in the captured log' -ForegroundColor Red
    $failed = $true
}
Write-Step ("exit={0} done={1} pluginEnabled={2} lines={3}" -f $p.ExitCode, $r.ServerDone, $r.PluginEnabled, $r.Lines)
if ($r.Problems.Count) { Write-Host '[qa] problems found:' -ForegroundColor Red; $r.Problems | Select-Object -First 40 | ForEach-Object { Write-Host $_ }; $failed = $true }
if (-not $r.PluginEnabled) { Write-Host "[qa] '$script:PluginName' was never enabled" -ForegroundColor Red; $failed = $true }
if ($failed) { Write-Host '[qa] SMOKE TEST: FAIL' -ForegroundColor Red; exit 1 }
Write-Host '[qa] SMOKE TEST: PASS' -ForegroundColor Green
