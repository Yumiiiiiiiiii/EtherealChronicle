<#
.SYNOPSIS  Run the dev Paper server in the background with a file-based console: append a line to <ServerDir>\..\qa-console.txt to send it to the server console.
.DESCRIPTION
  Writes the server log to scripts/logs/server-host-<ts>.log (gitignored). Stops when the console receives `stop` (or the file contains it).
  Does not touch eula.txt (the dev server must already have it accepted) and refuses to start if it is not.
#>
param(
    [string]$JarPath = 'D:\test\mcmmo\dev\server\paper-26.3-257.jar',
    [string]$ServerDir,
    [int]$MemoryMb = 2048
)
. "$PSScriptRoot/../lib.ps1"
if (-not $ServerDir) { $ServerDir = Split-Path $JarPath -Parent }
Test-JavaVersion; Assert-Eula $ServerDir
$pj = Get-BuiltJar; Install-Plugin $pj $ServerDir
$logDir = Join-Path $PSScriptRoot '..\logs'; New-Item -ItemType Directory -Force $logDir | Out-Null
$log = Join-Path $logDir ("server-host-{0:yyyyMMdd-HHmmss}.log" -f (Get-Date)); New-Item -ItemType File -Force $log | Out-Null
$cmdFile = Join-Path (Split-Path $ServerDir -Parent) 'qa-console.txt'; Set-Content $cmdFile '' -Encoding utf8
$psi = [System.Diagnostics.ProcessStartInfo]::new('java')
foreach ($a in @('-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', '-Xms512M', "-Xmx$($MemoryMb)M", '-jar', $JarPath, '--nogui')) { $psi.ArgumentList.Add($a) }
$psi.WorkingDirectory = $ServerDir; $psi.RedirectStandardInput = $true; $psi.RedirectStandardOutput = $true; $psi.RedirectStandardError = $true; $psi.UseShellExecute = $false
$psi.StandardOutputEncoding = [Text.Encoding]::UTF8; $psi.StandardErrorEncoding = [Text.Encoding]::UTF8
if (-not ('QaLineSink' -as [type])) {
    Add-Type -TypeDefinition @'
using System.Collections.Concurrent; using System.Diagnostics;
public class QaLineSink { public readonly ConcurrentQueue<string> Queue = new ConcurrentQueue<string>();
  public void Attach(Process p) { p.OutputDataReceived += (s, e) => { if (e.Data != null) Queue.Enqueue(e.Data); }; p.ErrorDataReceived += (s, e) => { if (e.Data != null) Queue.Enqueue(e.Data); }; } }
'@
}
$sink = [QaLineSink]::new(); $p = [System.Diagnostics.Process]::new(); $p.StartInfo = $psi; $sink.Attach($p)
[void]$p.Start(); $p.BeginOutputReadLine(); $p.BeginErrorReadLine()
Write-Host "server pid $($p.Id); log $log; console file $cmdFile"
$seen = 0
while (-not $p.HasExited) {
    $l = $null; while ($sink.Queue.TryDequeue([ref]$l)) { Add-Content $log $l -Encoding utf8 }
    $lines = @(Get-Content $cmdFile -Encoding utf8 | Where-Object { $_ -ne '' })
    while ($seen -lt $lines.Count) { $p.StandardInput.WriteLine($lines[$seen]); $seen++ }
    Start-Sleep -Milliseconds 300
}
$l = $null; while ($sink.Queue.TryDequeue([ref]$l)) { Add-Content $log $l -Encoding utf8 }
Write-Host "server exited $($p.ExitCode)"
