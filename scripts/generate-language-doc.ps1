param([string]$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path)
$ErrorActionPreference = 'Stop'
function Read-Language([string]$Path) {
    $values = [ordered]@{}
    $stack = [System.Collections.Generic.List[string]]::new()
    foreach ($line in Get-Content -LiteralPath $Path) {
        if ($line -match '^( *)([a-z0-9_]+):(?:\s*"(.*)"\s*)?$') {
            $indent = $Matches[1].Length
            $name = $Matches[2]
            $value = $Matches[3]
            while ($stack.Count -gt ($indent / 2)) { $stack.RemoveAt($stack.Count - 1) }
            if ($null -eq $value -or $value -eq '') { $stack.Add($name); continue }
            $key = (@($stack.ToArray()) + $name) -join '.'
            $values[$key] = $value
        }
    }
    return $values
}
$ja = Read-Language (Join-Path $Root 'src/main/resources/lang/ja_jp.yml')
$en = Read-Language (Join-Path $Root 'src/main/resources/lang/en_us.yml')
$references = @{}
Get-ChildItem (Join-Path $Root 'src/main/kotlin') -Recurse -Filter *.kt | ForEach-Object {
    $relative = [IO.Path]::GetRelativePath($Root, $_.FullName).Replace('\','/')
    $text = Get-Content -Raw -LiteralPath $_.FullName
    foreach ($match in [regex]::Matches($text, '(ec(?:\.[a-z0-9_]+)+)')) {
        if (!$references.ContainsKey($match.Groups[1].Value)) { $references[$match.Groups[1].Value] = [System.Collections.Generic.SortedSet[string]]::new() }
        [void]$references[$match.Groups[1].Value].Add($relative)
    }
}
$header = @(
    '# 言語ファイルと翻訳キー', '',
    '表示文言はMiniMessage形式です。`plugins/EtherealChronicle/lang/ja_jp.yml` と `en_us.yml` は初回配置後、サーバー編集を優先します。未編集の同梱ファイルだけ次の配布版へ更新します。', '',
    '設定 `language: auto` はプレイヤーのクライアント言語に追従し、未対応言語は日本語を選びます。`ja_jp` / `en_us` を指定するとサーバー全体で固定します。存在しないキーは日本語、それも無い場合はキー名を表示し、警告はキーごとに一度だけ記録します。', '',
    '| キー | 日本語 | English | 使用箇所 | プレースホルダ |', '| --- | --- | --- | --- | --- |'
)
$orderedKeys = [string[]]@($ja.Keys)
[Array]::Sort($orderedKeys, [StringComparer]::Ordinal)
$rows = foreach ($key in $orderedKeys) {
    $j = $ja[$key].Replace('|','\|').Replace("`n",'<br>')
    if (!$en.Contains($key)) { throw "Missing English translation for $key" }
    $e = $en[$key].Replace('|','\|').Replace("`n",'<br>')
    $tokenNames = @([regex]::Matches($ja[$key], '<(?!aqua|black|blue|dark_aqua|dark_blue|dark_gray|dark_green|dark_purple|dark_red|gold|gray|green|italic|light_purple|newline|red|reset|white|yellow)([a-zA-Z][a-zA-Z0-9_]*)>') | ForEach-Object { $_.Groups[1].Value } | Select-Object -Unique)
    $tokenNames = [string[]]$tokenNames
    if ($tokenNames.Count -gt 1) { [Array]::Sort($tokenNames, [StringComparer]::Ordinal) }
    $tokens = $tokenNames | ForEach-Object { '`<' + $_ + '>`' }
    $usage = @($references[$key]) -join '<br>'
    "| ``$key`` | $j | $e | $usage | $($tokens -join ', ') |"
}
$footer = @('', '## コンテンツYAMLの多言語化案', 'クエスト名やアイテム名などの `name` はコンテンツ表示データです。英語用の名前が未定義なら、現行仕様では日本語名をそのまま表示します。状態ラベルや説明文などのUI文言は言語カタログから選びます。', '', '将来は `name_key` / `description_key` を追加し、同じ `ec.*` 翻訳表を参照します。既存の `name` / `description` は後方互換のフォールバックとして維持し、コンテンツschemaと保存形式を一括変更しない段階導入にします。')
$output = @($header) + @($rows) + @($footer)
[IO.File]::WriteAllText((Join-Path $Root 'docs/LANGUAGE.md'), (($output -join "`n") + "`n"), [Text.UTF8Encoding]::new($false))
