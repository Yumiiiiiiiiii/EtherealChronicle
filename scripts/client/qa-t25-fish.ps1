# Task 25: real cast -> poll status until BITE_ALERT -> hook -> pull until the session ends.
. "$PSScriptRoot\mcwin.ps1"; . "$PSScriptRoot\qa-helpers.ps1"
$repo = Resolve-Path "$PSScriptRoot\..\.."
$gp = Get-Gp; Pin-Mc $gp
$log = (Get-ChildItem "$repo\scripts\logs" -Filter server-host-* | Sort-Object LastWriteTime | Select-Object -Last 1).FullName
C 'ec debug fish cancel QaT25'; Start-Sleep 2
Tp QaT25 10.5 65 22.5 15 12; Start-Sleep 2
Focus-Mc $gp; RClick
$hooked = $false
for ($i = 0; $i -lt 40 -and -not $hooked; $i++) {
    C 'ec debug fish status QaT25'; Start-Sleep -Milliseconds 700
    $last = Get-Content -LiteralPath $log -Tail 3 | Select-String 'phase=BITE_ALERT'
    if ($last) { Focus-Mc $gp; RClick; $hooked = $true }
}
"hooked=$hooked"
Start-Sleep -Milliseconds 1500; C 'ec debug fish status QaT25'; Start-Sleep 1
$endLine = $null
for ($i = 0; $i -lt 150 -and -not $endLine; $i++) {
    Focus-Mc $gp; Click-Mc $gp 'right' 600; Start-Sleep -Milliseconds 200
    $endLine = Get-Content -LiteralPath $log -Tail 4 | Select-String 'ended reason=(?!CANCELLED)'
}
Shot t25_u_result | Out-Null
$endLine | ForEach-Object Line

