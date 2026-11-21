# EtherealChronicle Official Resource Pack Source

Minecraft: Java Edition **26.3**（Resource Pack Format `97.1`）向けのリソースパックソースです。  
PackSquash などの外部最適化ツールを使わず、本ディレクトリ直下を**単純 ZIP 化するだけでそのまま動作**します。

## 権利・ライセンス（権利クリーン宣言）

- 本ディレクトリ配下の全テクスチャ（PNG）・フォント定義・モデル定義 JSON は、EtherealChronicle プロジェクト向けに**完全自作（オリジナル生成）**された素材です。
- 外部フリー素材・バニラテクスチャの改変コピー・CC-BY 等の外部権利素材は一切含んでいません。
- 将来的に外部素材を追加する場合は、必ずライセンス（商用・改変・サーバー配布可否）を確認し、`THIRD_PARTY_LICENSES.md` に出典・ライセンス・URL を記録してください。

## ディレクトリ構成

```text
resourcepack/
├── pack.mcmeta                          # min_format/max_format: [97, 1] (MC 26.3)
├── pack.png                             # 64x64 パックアイコン
├── README.md
└── assets/ec/
    ├── font/
    │   ├── space.json                   # 自作スペースフォントプロバイダ (U+E001–U+E018)
    │   ├── ui.json                      # GUI 背景パネルグリフ (U+E100–U+E105)
    │   └── hud.json                     # HUD アイコン・属性・分割ゲージ (U+E200–U+E22E)
    ├── items/                           # MC 1.21.4+ / 26.3 Item Model Definitions (30種)
    ├── models/item/                     # 各カスタムアイテムモデル (30種)
    └── textures/
        ├── entity/wolf/                 # wolf_variant 3種 × 成体(64x32)/幼体(32x32) × wild/tame/angry (18枚)
        ├── gui/                         # GUI 背景パネル 6種 (256x256) + 透明スロット (16x16)
        ├── hud/                         # HUD アイコン帯・属性アイコン帯・分割バー3種
        └── item/                        # 伝説武器・アクセ・副手・釣竿・UIボタン・6職業紋章 (29枚)
```

## 単純 ZIP ビルド手順（PackSquash 不要）

`pack.mcmeta` と `assets/` が ZIP ルート直下になるように圧縮します。

```powershell
# PowerShell (Windows)
Compress-Archive -Path resourcepack\pack.mcmeta, resourcepack\pack.png, resourcepack\assets -DestinationPath build\etherealchronicle-resourcepack.zip -Force
Get-FileHash build\etherealchronicle-resourcepack.zip -Algorithm SHA1
```

```bash
# Linux / macOS
(cd resourcepack && zip -r ../build/etherealchronicle-resourcepack.zip pack.mcmeta pack.png assets)
sha1sum build/etherealchronicle-resourcepack.zip
```

## フォント PUA コードポイント一覧

| Font ID | 範囲 | 用途 |
|---|---|---|
| `ec:space` | `U+E001`–`U+E009` | 負スペース（`-1, -2, -4, -8, -16, -32, -64, -128, -256`） |
| `ec:space` | `U+E011`–`U+E018` | 正スペース（`+1, +2, +4, +8, +16, +32, +64, +128`） |
| `ec:ui` | `U+E100`–`U+E105` | GUI 背景（職業選択 / ステータス / 図鑑 / 強化 / ショップ / 汎用） |
| `ec:hud` | `U+E200`–`U+E207` | HUD アイコン（HP / MP / 憤怒 / 盾 / 剣 / 星 / 釣針 / 星時計） |
| `ec:hud` | `U+E210`–`U+E215` | 属性アイコン（物理 / エーテル / 虚無 / 氷 / 炎 / 雷） |
| `ec:hud` | `U+E220`–`U+E224` | HP 分割バー（0% / 25% / 50% / 75% / 100%） |
| `ec:hud` | `U+E225`–`U+E229` | MP・リソース分割バー（0% / 25% / 50% / 75% / 100%） |
| `ec:hud` | `U+E22A`–`U+E22E` | 釣り張力・進行バー（0% / 25% / 50% / 75% / 100%） |
