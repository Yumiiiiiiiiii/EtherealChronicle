<#
.SYNOPSIS  Start the downloaded official client OFFLINE (no account) and join the local dev server directly.
.DESCRIPTION
  Uses a throwaway offline identity (name + deterministic offline UUID) with accessToken 0. Refuses any server address that is not loopback.
  The game directory is created under -Root (never inside the repository). Prints the process id so callers can stop it.
.EXAMPLE   ./scripts/client/launch-client.ps1 -Root D:\test\mcmmo-client-qa -Name QaBot
#>
param(
    [string]$Root = 'D:\test\mcmmo-client-qa',
    [string]$Version = '26.3',
    [string]$Name = 'QaBot',
    [string]$Server = '127.0.0.1:25565',
    [int]$Width = 1280, [int]$Height = 720,
    [switch]$NoQuickPlay
)
$ErrorActionPreference = 'Stop'
$serverHost = $Server.Split(':')[0]
if ($serverHost -notin @('127.0.0.1', 'localhost', '::1')) { throw "refusing non-local server $Server" }
$verJson = Join-Path $Root "versions\$Version\$Version.json"
if (-not (Test-Path $verJson)) { throw "client not downloaded: run get-client.ps1 first ($verJson missing)" }
$v = Get-Content $verJson -Raw | ConvertFrom-Json
$gameDir = Join-Path $Root 'game'; New-Item -ItemType Directory -Force $gameDir | Out-Null
# offline UUID = UUID v3 of "OfflinePlayer:<name>" (what an offline-mode server assigns)
$md5 = [System.Security.Cryptography.MD5]::Create().ComputeHash([Text.Encoding]::UTF8.GetBytes("OfflinePlayer:$Name"))
$md5[6] = ($md5[6] -band 0x0f) -bor 0x30; $md5[8] = ($md5[8] -band 0x3f) -bor 0x80
$hex = ($md5 | ForEach-Object { $_.ToString('x2') }) -join ''
$uuid = "$($hex.Substring(0,8))-$($hex.Substring(8,4))-$($hex.Substring(12,4))-$($hex.Substring(16,4))-$($hex.Substring(20,12))"

function Rule-Allows($rules) {
    if (-not $rules) { return $true }
    $allow = $false
    foreach ($r in $rules) {
        $match = $true
        if ($r.os) { if ($r.os.name -and $r.os.name -ne 'windows') { $match = $false }; if ($r.os.arch -and $r.os.arch -notin @('x86_64', 'x64')) { $match = $false } }
        if ($r.features) { foreach ($f in $r.features.PSObject.Properties) { if ($f.Name -eq 'has_quick_plays_support' -and $f.Value -and -not $NoQuickPlay) { } elseif ($f.Name -eq 'is_demo_user' -or $f.Name -eq 'has_custom_resolution') { if ($f.Name -ne 'has_custom_resolution') { $match = $false } } else { $match = $false } } }
        if ($match) { $allow = ($r.action -eq 'allow') }
    }
    return $allow
}
$cp = @()
foreach ($lib in $v.libraries) { if ((Rule-Allows $lib.rules) -and $lib.downloads.artifact) { $p = Join-Path $Root "libraries\$($lib.downloads.artifact.path)"; if (Test-Path $p) { $cp += $p } } }
$cp += (Join-Path $Root "versions\$Version\$Version.jar")
$vars = @{
    auth_player_name = $Name; version_name = $Version; game_directory = $gameDir; assets_root = (Join-Path $Root 'assets'); assets_index_name = $v.assetIndex.id
    auth_uuid = $uuid; auth_access_token = '0'; clientid = ''; auth_xuid = ''; user_type = 'legacy'; version_type = $v.type
    launcher_name = 'ec-qa'; launcher_version = '1'; classpath = ($cp -join ';'); natives_directory = (Join-Path $Root 'natives')
    resolution_width = "$Width"; resolution_height = "$Height"; quick_play_multiplayer = $Server; quick_play_path = ''; quick_play_singleplayer = ''; quick_play_realms = ''
}
function Expand([string]$s) { foreach ($k in $vars.Keys) { $s = $s.Replace('${' + $k + '}', $vars[$k]) }; return $s }
function Collect($list) {
    $out = @()
    foreach ($a in $list) {
        if ($a -is [string]) { $out += (Expand $a) }
        elseif (Rule-Allows $a.rules) { foreach ($x in @($a.value)) { $out += (Expand $x) } }
    }
    return $out
}
$jvm = Collect $v.arguments.jvm
$game = Collect $v.arguments.game
if (-not $NoQuickPlay) { $game += @('--quickPlayMultiplayer', $Server) }
$java = (Get-Command java).Source
$logs = Join-Path $Root 'logs'; New-Item -ItemType Directory -Force $logs | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$argList = @('-Xmx2G') + $jvm + @($v.mainClass) + $game
Write-Host "[client] java=$java name=$Name uuid=$uuid server=$Server"
$p = Start-Process -FilePath $java -ArgumentList $argList -WorkingDirectory $gameDir -PassThru -RedirectStandardOutput "$logs\client-$stamp.out" -RedirectStandardError "$logs\client-$stamp.err"
Write-Host "[client] pid=$($p.Id)"
$p.Id
