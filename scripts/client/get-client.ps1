<#
.SYNOPSIS  Download the official Minecraft Java client 26.3 (jar, libraries, assets) from Mojang's official URLs into a fresh, empty directory.
.DESCRIPTION
  - No Microsoft/Mojang account, launcher or credentials are used or needed. Only public, hash-addressed files.
  - Every file's SHA-1 is verified against Mojang's manifest. Anything that fails verification is deleted.
  - The target directory must not exist or must be empty; the downloads are treated as untrusted data and never live inside the repository.
  - Use the Minecraft EULA/terms of the downloaded software as given by Mojang; this script only fetches what the official launcher would.
.EXAMPLE   ./scripts/client/get-client.ps1 -Root D:\test\mcmmo-client-qa -Version 26.3
#>
param(
    [string]$Root = 'D:\test\mcmmo-client-qa',
    [string]$Version = '26.3',
    [int]$Parallel = 12
)
$ErrorActionPreference = 'Stop'
$allowedHosts = @('piston-meta.mojang.com', 'piston-data.mojang.com', 'libraries.minecraft.net', 'resources.download.minecraft.net')
function Test-Url([string]$u) { $h = ([Uri]$u).Host; if ($h -notin $allowedHosts) { throw "refusing non-official host: $h ($u)" } }
if ((Test-Path $Root) -and (Get-ChildItem $Root -Force | Select-Object -First 1)) { throw "$Root exists and is not empty; use a new empty directory" }
New-Item -ItemType Directory -Force $Root | Out-Null
function Get-Verified([string]$url, [string]$dest, [string]$sha1) {
    Test-Url $url
    New-Item -ItemType Directory -Force (Split-Path $dest -Parent) | Out-Null
    Invoke-WebRequest -Uri $url -OutFile $dest -UseBasicParsing
    $actual = (Get-FileHash $dest -Algorithm SHA1).Hash.ToLower()
    if ($actual -ne $sha1.ToLower()) { Remove-Item $dest -Force; throw "SHA-1 mismatch for $url ($actual != $sha1)" }
}
Write-Host "[client] version manifest"
$manifestUrl = 'https://piston-meta.mojang.com/mc/game/version_manifest_v2.json'
Test-Url $manifestUrl
$manifest = Invoke-RestMethod -Uri $manifestUrl
$entry = $manifest.versions | Where-Object { $_.id -eq $Version } | Select-Object -First 1
if (-not $entry) { throw "version $Version not in manifest" }
$verJson = Join-Path $Root "versions\$Version\$Version.json"
Get-Verified $entry.url $verJson $entry.sha1
$v = Get-Content $verJson -Raw | ConvertFrom-Json
Write-Host "[client] jar $($v.downloads.client.size) bytes"
Get-Verified $v.downloads.client.url (Join-Path $Root "versions\$Version\$Version.jar") $v.downloads.client.sha1

function Rule-Allows($rules) {
    if (-not $rules) { return $true }
    $allow = $false
    foreach ($r in $rules) {
        $match = $true
        if ($r.os) { if ($r.os.name -and $r.os.name -ne 'windows') { $match = $false }; if ($r.os.arch -and $r.os.arch -ne 'x86_64' -and $r.os.arch -ne 'x64') { $match = $match -and $false } }
        if ($r.features) { $match = $false }
        if ($match) { $allow = ($r.action -eq 'allow') }
    }
    return $allow
}
Write-Host "[client] libraries ($($v.libraries.Count) entries)"
$libCount = 0
foreach ($lib in $v.libraries) {
    if (-not (Rule-Allows $lib.rules)) { continue }
    $art = $lib.downloads.artifact
    if (-not $art) { continue }
    Get-Verified $art.url (Join-Path $Root "libraries\$($art.path)") $art.sha1; $libCount++
}
Write-Host "[client] $libCount libraries verified"
Write-Host "[client] asset index"
$idxPath = Join-Path $Root "assets\indexes\$($v.assetIndex.id).json"
Get-Verified $v.assetIndex.url $idxPath $v.assetIndex.sha1
$index = Get-Content $idxPath -Raw | ConvertFrom-Json
$objects = $index.objects.PSObject.Properties | ForEach-Object { [pscustomobject]@{ Name = $_.Name; Hash = $_.Value.hash } } | Sort-Object Hash -Unique
Write-Host "[client] $($objects.Count) asset objects (parallel $Parallel)"
$assetRoot = Join-Path $Root 'assets\objects'
$objects | ForEach-Object -ThrottleLimit $Parallel -Parallel {
    $h = $_.Hash; $sub = $h.Substring(0, 2); $dest = Join-Path $using:assetRoot "$sub\$h"
    if (-not (Test-Path $dest)) {
        New-Item -ItemType Directory -Force (Split-Path $dest -Parent) | Out-Null
        $url = "https://resources.download.minecraft.net/$sub/$h"
        Invoke-WebRequest -Uri $url -OutFile $dest -UseBasicParsing
        if ((Get-FileHash $dest -Algorithm SHA1).Hash.ToLower() -ne $h) { Remove-Item $dest -Force; throw "SHA-1 mismatch for asset $($_.Name)" }
    }
}
Write-Host "[client] done: $Root"
