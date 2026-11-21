# Helpers for the final real-client pass (dot-source after mcwin.ps1). Console commands go through qa-console.txt.
$script:QaShots = 'D:\test\mcmmo-client-qa\shots'
function Get-Gp { (Get-Process java | Where-Object { $_.MainWindowTitle -like 'Minecraft*' } | Select-Object -First 1).Id }
function C([string]$cmd) { Add-Content D:\test\mcmmo\dev\qa-console.txt $cmd -Encoding utf8 }
# tp the player inside ec_world (x y z yaw pitch)
function Tp([string]$name, $x, $y, $z, $yaw = 0, $pitch = 0) { C "execute in minecraft:ec_world run tp $name $x $y $z $yaw $pitch" }
function Shot([string]$tag) { $gp = Get-Gp; Pin-Mc $gp; Focus-Mc $gp; Save-McShot $gp "$script:QaShots\$tag.png" | Out-Null; "$script:QaShots\$tag.png" }
function RClick { $gp = Get-Gp; Pin-Mc $gp; Focus-Mc $gp; Click-Mc $gp 'right' 100 }
function Key([string]$k) { $gp = Get-Gp; Focus-Mc $gp; Press-McKey $gp $k }
# type a chat command (starts with '/'), clearing any draft first, then Enter
function Chat([string]$text) {
    $gp = Get-Gp; Pin-Mc $gp; Focus-Mc $gp; Press-McKey $gp 'SLASH'; Start-Sleep -Milliseconds 400
    for ($i = 0; $i -lt 40; $i++) { Press-McKey $gp 'BACK' }
    Focus-Mc $gp
    foreach ($ch in $text.ToCharArray()) { Assert-McFocused $gp; $scan = [McWinLayout]::VkKeyScan($ch); $vk = [uint16]($scan -band 0xFF); $sh = (($scan -shr 8) -band 1) -eq 1; if ($sh) { [McWin]::Key(0x10, $false) }; [McWin]::Key($vk, $false); Start-Sleep -Milliseconds 15; [McWin]::Key($vk, $true); if ($sh) { [McWin]::Key(0x10, $true) }; Start-Sleep -Milliseconds 45 }
    Start-Sleep -Milliseconds 400; Focus-Mc $gp; Press-McKey $gp 'ENTER'
}
function Log([int]$n = 6) { Get-ChildItem D:\test\mcmmo\scripts\logs -ErrorAction SilentlyContinue | Out-Null; Get-ChildItem "$PSScriptRoot\..\logs" -Filter server-host-* | Sort-Object LastWriteTime | Select-Object -Last 1 | ForEach-Object { Get-Content $_.FullName -Tail $n } }
