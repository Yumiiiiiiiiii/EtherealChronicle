# Overworld QA: pick a class, set day/clear weather, spectator tour of Lumina and the other sites (Minecraft-window-only shots).
$Out = 'C:\Users\Ymii\.traycer\epics\132bc8e7-97a8-4d19-af01-669c4ba69b04\artifacts\overworld-live-qa'
. "$PSScriptRoot\mcwin.ps1"; . "$PSScriptRoot\qa-helpers.ps1"
function OTp($n, $x, $y, $z, $yaw, $pitch) { C "tp $n $x $y $z $yaw $pitch" }
$gp = Get-Gp; Pin-Mc $gp; Focus-Mc $gp
Click-McAt $gp 692 237 'left'; Start-Sleep 3
Focus-Mc $gp; Click-McAt $gp 327 670 'left'; Start-Sleep 6
C 'op QaOw'; C 'weather clear'; C 'time set 6000'; C 'gamerule advance_time false'; C 'gamerule advance_weather false'
Start-Sleep 2
Focus-Mc $gp; Save-McShot $gp "$Out\01_lumina_first_join.png" | Out-Null
C 'data get entity QaOw Pos'
C 'gamemode spectator QaOw'; Start-Sleep 2
$script:VK['F1'] = 0x70; Focus-Mc $gp; Press-McKey $gp 'F1'
$shots = @(
    @('02_lumina_overview', -150, 100, -226, 180, 30),
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
    OTp QaOw $s[1] $s[2] $s[3] $s[4] $s[5]
    Start-Sleep 8
    Focus-Mc $gp
    Save-McShot $gp "$Out\$($s[0]).png" | Out-Null
}

