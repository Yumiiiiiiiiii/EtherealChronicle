$ErrorActionPreference = 'Stop'

# NBTはBig Endianです。DataVersion 4189は旧形式としてPaper側DataFixerで更新されます。
function Write-BEInt32 {
    param($writer, [int]$value)
    $b0 = [Math]::Floor($value / 16777216)
    $b1 = [Math]::Floor($value / 65536) -band 255
    $b2 = [Math]::Floor($value / 256) -band 255
    $b3 = $value -band 255
    $writer.Write([byte]$b0)
    $writer.Write([byte]$b1)
    $writer.Write([byte]$b2)
    $writer.Write([byte]$b3)
}

function Write-NbtString {
    param($writer, [string]$value)
    $bytes = [Text.Encoding]::UTF8.GetBytes($value)
    $high = [Math]::Floor($bytes.Length / 256)
    $low = $bytes.Length -band 255
    $writer.Write([byte]$high)
    $writer.Write([byte]$low)
    $writer.Write($bytes)
}

function Write-TagName {
    param($writer, [byte]$type, [string]$name)
    $writer.Write($type)
    Write-NbtString $writer $name
}

function Write-CompoundPaletteEntry {
    param($writer, [string]$blockName)
    Write-TagName $writer 8 'Name'
    Write-NbtString $writer $blockName
    $writer.Write([byte]0)
}

function Write-Block {
    param($writer, [int]$x, [int]$y, [int]$z, [int]$state)
    Write-TagName $writer 9 'pos'
    $writer.Write([byte]3)
    Write-BEInt32 $writer 3
    Write-BEInt32 $writer $x
    Write-BEInt32 $writer $y
    Write-BEInt32 $writer $z
    Write-TagName $writer 3 'state'
    Write-BEInt32 $writer $state
    $writer.Write([byte]0)
}

$raw = [IO.MemoryStream]::new()
$writer = [IO.BinaryWriter]::new($raw)
$writer.Write([byte]10)
Write-NbtString $writer ''
Write-TagName $writer 3 'DataVersion'
Write-BEInt32 $writer 4189
Write-TagName $writer 8 'author'
Write-NbtString $writer 'EtherealChronicle'
Write-TagName $writer 9 'size'
$writer.Write([byte]3)
Write-BEInt32 $writer 3
Write-BEInt32 $writer 5
Write-BEInt32 $writer 4
Write-BEInt32 $writer 1
Write-TagName $writer 9 'palette'
$writer.Write([byte]10)
Write-BEInt32 $writer 2
Write-CompoundPaletteEntry $writer 'minecraft:stone_bricks'
Write-CompoundPaletteEntry $writer 'minecraft:lantern'
Write-TagName $writer 9 'blocks'
$blocks = [Collections.Generic.List[object]]::new()
foreach ($x in 0..4) { $blocks.Add(@($x, 0, 0, 0)) }
foreach ($y in 1..2) { $blocks.Add(@(0, $y, 0, 0)); $blocks.Add(@(4, $y, 0, 0)) }
foreach ($x in 0..4) { $blocks.Add(@($x, 3, 0, 0)) }
$blocks.Add(@(2, 1, 0, 1))
$writer.Write([byte]10)
Write-BEInt32 $writer $blocks.Count
foreach ($block in $blocks) { Write-Block $writer $block[0] $block[1] $block[2] $block[3] }
Write-TagName $writer 9 'entities'
$writer.Write([byte]10)
Write-BEInt32 $writer 0
$writer.Write([byte]0)
$writer.Flush()

$compressed = [IO.MemoryStream]::new()
$gzip = [IO.Compression.GZipStream]::new($compressed, [IO.Compression.CompressionLevel]::Optimal, $true)
$raw.ToArray() | ForEach-Object { $gzip.WriteByte($_) }
$gzip.Dispose()

$output = Join-Path (Get-Location) 'src/main/resources/structures/lumina_waystone.nbt'
New-Item -ItemType Directory -Path (Split-Path -Parent $output) -Force | Out-Null
[IO.File]::WriteAllBytes($output, $compressed.ToArray())
$writer.Dispose()
$raw.Dispose()
$compressed.Dispose()
Write-Output "Wrote $output"
