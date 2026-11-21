<#
.SYNOPSIS  Headless smoke test: build -> install -> start Paper -> wait for 'Done' -> run console commands -> stop -> analyse logs.
.EXAMPLE   ./scripts/smoke-test.ps1 -Commands 'ec validate','ec reload'
  Exit codes: 0 pass, 1 test failure, 2 EULA not accepted, 3 jar missing, 4 wrong Java, 10 build failed.
#>
param(
    [string]$JarPath = 'D:\test\mcmmo\dev\server\paper-26.3-257.jar',
    [string]$ServerDir,
    [switch]$SkipBuild,
    [string[]]$Commands = @('ec validate'),
    [int]$StartTimeoutSec = 180,
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
foreach ($a in @('-Xms512M', "-Xmx$($MemoryMb)M", '-jar', $JarPath, '--nogui')) { $psi.ArgumentList.Add($a) }
$psi.WorkingDirectory = $ServerDir
$psi.RedirectStandardInput = $true; $psi.RedirectStandardOutput = $true; $psi.RedirectStandardError = $true
$psi.UseShellExecute = $false
$queue = [System.Collections.Concurrent.ConcurrentQueue[string]]::new()
$p = [System.Diagnostics.Process]::new(); $p.StartInfo = $psi
$handler = [System.Diagnostics.DataReceivedEventHandler] { param($s, $e) if ($null -ne $e.Data) { $queue.Enqueue($e.Data) } }
$p.add_OutputDataReceived($handler); $p.add_ErrorDataReceived($handler)
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
        foreach ($c in $Commands) {
            Write-Step "> $c"; $p.StandardInput.WriteLine($c); Start-Sleep -Seconds $CommandWaitSec; Flush-Log
        }
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
Write-Step ("exit={0} done={1} pluginEnabled={2} lines={3}" -f $p.ExitCode, $r.ServerDone, $r.PluginEnabled, $r.Lines)
if ($r.Problems.Count) { Write-Host '[qa] problems found:' -ForegroundColor Red; $r.Problems | Select-Object -First 40 | ForEach-Object { Write-Host $_ }; $failed = $true }
if (-not $r.PluginEnabled) { Write-Host "[qa] '$script:PluginName' was never enabled" -ForegroundColor Red; $failed = $true }
if ($failed) { Write-Host '[qa] SMOKE TEST: FAIL' -ForegroundColor Red; exit 1 }
Write-Host '[qa] SMOKE TEST: PASS' -ForegroundColor Green
