# Overworld QA #4 part E: real cast near Lumina's natural water, hook and reel, plus a debug reel session.
$Out = 'C:\Users\Ymii\.traycer\epics\132bc8e7-97a8-4d19-af01-669c4ba69b04\artifacts\overworld-live-qa-gameplay'
. "$PSScriptRoot\mcwin.ps1"; . "$PSScriptRoot\qa-helpers.ps1"
function FocusRetry($gp) { for ($i = 0; $i -lt 10; $i++) { try { Focus-Mc $gp; return } catch { Start-Sleep 4 } }; throw 'focus failed' }
$script:VK['D5'] = 0x35
$repo = Resolve-Path "$PSScriptRoot\..\.."
$log = (Get-ChildItem "$repo\scripts\logs" -Filter server-host-* | Sort-Object LastWriteTime | Select-Object -Last 1).FullName
$gp = Get-Gp; Pin-Mc $gp
C 'tp QaOw -118.5 63 345.5 -40 30'; Start-Sleep 4
FocusRetry $gp; Press-McKey $gp 'D5'; Start-Sleep 1
FocusRetry $gp; Save-McShot $gp "$Out\08_fishing_aim.png" | Out-Null
FocusRetry $gp; RClick
$hooked = $false
for ($i = 0; $i -lt 40 -and -not $hooked; $i++) {
    C 'ec debug fish status QaOw'; Start-Sleep -Milliseconds 700
    if (Get-Content -LiteralPath $log -Tail 3 | Select-String 'phase=BITE_ALERT') { FocusRetry $gp; RClick; $hooked = $true }
}
"hooked=$hooked"
FocusRetry $gp; Save-McShot $gp "$Out\09_fishing_cast_bite.png" | Out-Null
Start-Sleep -Milliseconds 1500
$endLine = $null
for ($i = 0; $i -lt 120 -and -not $endLine; $i++) {
    FocusRetry $gp; Click-Mc $gp 'right' 600; Start-Sleep -Milliseconds 200
    $endLine = Get-Content -LiteralPath $log -Tail 4 | Select-String 'ended reason=(?!CANCELLED)'
}
FocusRetry $gp; Save-McShot $gp "$Out\10_fishing_result.png" | Out-Null
$endLine | ForEach-Object Line
