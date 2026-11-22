# QA scenario 3 (C/D): pull-only vs pull+left-click-air brace, to see whether the left click changes tension.
. "$PSScriptRoot\mcwin.ps1"; . "$PSScriptRoot\qa-helpers.ps1"
$gp = Get-Gp; Pin-Mc $gp
Focus-Mc $gp; Press-McKey $gp 'D1'
foreach ($trial in 'C_pull_only', 'D_pull_plus_left', 'C2_pull_only', 'D2_pull_plus_left') {
    C "ec debug fish cancel QaG1"; Start-Sleep 2
    C "say === TRIAL $trial ==="; C "ec debug fish start QaG1"; Start-Sleep -Milliseconds 600
    1..14 | ForEach-Object {
        Focus-Mc $gp; Click-Mc $gp 'right' 60
        if ($trial -like 'D*') { Start-Sleep -Milliseconds 150; Click-Mc $gp 'left' 60 }
        C "ec debug fish status QaG1"; Start-Sleep -Milliseconds 300
    }
    Start-Sleep 2
}
