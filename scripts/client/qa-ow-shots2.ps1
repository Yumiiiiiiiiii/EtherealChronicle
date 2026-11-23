# Overworld QA: resume the spectator tour (focus retried because the OS sometimes steals the foreground).
param([int]$From = 3)
$Out = 'C:\Users\Ymii\.traycer\epics\132bc8e7-97a8-4d19-af01-669c4ba69b04\artifacts\overworld-live-qa'
. "$PSScriptRoot\mcwin.ps1"; . "$PSScriptRoot\qa-helpers.ps1"
function FocusRetry($gp) { for ($i = 0; $i -lt 10; $i++) { try { Focus-Mc $gp; return } catch { Start-Sleep 4 } }; throw 'focus failed after retries' }
$gp = Get-Gp; Pin-Mc $gp
$shots = @(
    @('03_lumina_plaza_ground', -150, 66, -238, 180, 5),
    @('04_lumina_houses', -120, 78, -256, 90, 20),
    @('05_ashwood_forest', 410, 95, -270, 180, 30),
    @('06_ashwood_dungeon_portal', 372, 72, -262, 180, 10),
    @('07_weeping_mine', -3, 140, 480, 180, 35),
    @('08_stargazer_hill', -392, 118, 425, 180, 30),
    @('09_void_sanctum', -533, 108, 325, 180, 30),
    @('10_frostpeak_outpost', -1339, 88, -975, 180, 30),
    @('11_glacier_ridge', -1517, 88, -1213, 180, 30),
    @('12_crystalline_cavern', -1102, 88, -988, 180, 30),
    @('13_glacial_citadel', -1358, 90, -1304, 180, 30)
)
foreach ($s in $shots) {
    if ([int]$s[0].Substring(0, 2) -lt $From) { continue }
    C "tp QaOw $($s[1]) $($s[2]) $($s[3]) $($s[4]) $($s[5])"
    Start-Sleep 9
    FocusRetry $gp
    Save-McShot $gp "$Out\$($s[0]).png" | Out-Null
}
