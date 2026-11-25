<p align="center">
  <img src="resourcepack/pack.png" alt="EtherealChronicle Crest" width="96" height="96" style="image-rendering: pixelated;" />
</p>

<h1 align="center">EtherealChronicle</h1>

<p align="center">
  <strong>Paper 26.3 向けストーリー主導型ファンタジー MMORPG プラグイン &amp; カスタムリソースパック</strong><br />
  6クラス・章仕立てクエスト・ダンジョン・生活コンテンツ・Overworld 拠点自動生成・完全日英ローカライズ・専用ドット絵リソースパックを単一 JAR に統合
</p>

<p align="center">
  <a href="https://github.com/Yumiiiiiiiiii/EtherealChronicle/releases/tag/v0.1.1"><strong>Latest Release: v0.1.1</strong></a>
  &nbsp;·&nbsp;
  <a href="docs/RELEASE.md">Release Notes</a>
  &nbsp;·&nbsp;
  <a href="docs/RESOURCEPACK_HOSTING.md">Resource Pack Hosting</a>
  &nbsp;·&nbsp;
  <a href="docs/OVERWORLD_RECOVERY.md">Overworld Recovery</a>
</p>

---

## 主な機能

- **6 プレイアブルクラス & 爽快な戦闘フィードバック**
  - Warrior / Mage / Archer / Paladin / Assassin / Cleric の各クラス専用スキル・武器・オフハンド・アクセサリー・強化 UI・図鑑（Codex）を搭載。
  - 被弾赤フラッシュ・ノックバック・属性／クリティカル演出・浮遊ダメージ数値・ターゲット HP バー・弓／杖の左クリック遠距離通常攻撃に対応。
- **ストーリー・チュートリアル & ナビゲーション**
  - 第1章・第2章のメインクエスト、サイド・デイリー・ウィークリー依頼、NPC 会話・ショップ・トークン交換・宝箱・釣り＆採集システム（457 YAML コンテンツ）。
  - 常設サイドバー追跡、`/ec guide`（またはホットバー9番の星右クリック）による方角・距離ガイド＆光の道しるべパーティクル、NPC 目印パーティクル、エリア到着 Title バナーを搭載。
- **完全日英ローカライズ対応**
  - クライアント言語（`ja_*` / `en_*`）に応じて全 UI・会話・クエスト・アイテム・NPC・敵・ボスバー・サイドバー・実績を自動切替。リソースパック側にも両言語の翻訳 JSON（839 キー）を同梱。
- **協力ダンジョン & ボス戦**
  - パーティ機能・報酬共有・テレグラフ付きボス戦・スカイアリーナ方式のダンジョンインスタンスを実行。
- **オーバーワールドを旅する形式**
  - 浮島ワールドは廃止。バニラ Overworld へ 9 つの街・拠点（幅 5 ブロック道路・ゆとりある建物間隔）と 4 つのダンジョンポータルを自動統合（`/ec sites` で状態確認・復旧可能）。
- **カスタムリソースパック & コアデータパック内蔵**
  - Minecraft Java Edition 26.3 向けリソースパック（`pack_format [97, 1]`、ドット絵テクスチャ全 60 枚 + 日英翻訳ファイル）を JAR に同梱。
  - コアデータパック（`datapacks/etherealchronicle_core` / `pack_format [121, 0]`）は起動時に `LifecycleEvents.DATAPACK_DISCOVERY` 経由で自動登録。
  - `BetterModel` はオプション連携（未導入時は自動でカスタムオオカミバリアントへフォールバック）。

---

## 動作要件

| 項目 | 要件 |
| --- | --- |
| サーバー | **Paper 26.3**（build 257 で検証済み） |
| Java | **Java 25** |
| クライアント | Minecraft Java Edition **26.3**（サーバーリソースパック有効化を推奨） |
| 任意依存 | `BetterModel`（ソフト依存・なくても全機能動作） |

---

## 導入手順（v0.1.1）

1. [GitHub Releases (`v0.1.1`)](https://github.com/Yumiiiiiiiiii/EtherealChronicle/releases/tag/v0.1.1) から `EtherealChronicle-0.1.1.jar` をダウンロードし、Paper サーバーの `plugins/` へ配置します。
2. 公開サーバーでは `eula=true` および `online-mode=true` を設定します。
3. 本番運用（Overworld 統合 + HTTPS リソースパック配信）を行う場合、`plugins/EtherealChronicle/config.yml` を以下のように設定します：

```yaml
language: auto
world:
  mode: overworld
  overworld_name: "world"
ui:
  theme:
    mode: icons_only
resource_pack:
  enabled: true
  required: true
  required_timeout_seconds: 120
  mode: external_url
  external_url: "https://github.com/Yumiiiiiiiiii/EtherealChronicle/releases/download/v0.1.1/etherealchronicle-resourcepack.zip"
  external_sha1: "ae084a0c5e9d10d4b095e99343fd21dbc2848261"
```

4. サーバーを起動します（`world.mode: overworld` の初回起動時は拠点生成に約 3〜4 分かかります）。
5. コンソールで `/ec sites`（全 9 拠点 `READY`・4 ポータル確定）と `/ec pack info`（SHA-1 一致）を確認してからプレイヤーへ開放してください。

> 詳しい配信方式（`external_url` / `external_managed` / `embedded_http`）は [docs/RESOURCEPACK_HOSTING.md](docs/RESOURCEPACK_HOSTING.md)、拠点復旧手順は [docs/OVERWORLD_RECOVERY.md](docs/OVERWORLD_RECOVERY.md) を参照してください。

---

## 主なコマンドと権限

### 権限ノード

| 権限 | 既定 | 用途 |
| --- | --- | --- |
| `etherealchronicle.admin` | `op` | 管理者コマンド全般（拠点管理・リロード・補填・デバッグ） |
| `etherealchronicle.pack.bypass` | `op` | 必須リソースパックを拒否しても接続できる運用・QA 用バイパス |

### 代表的なコマンド

- **プレイヤー向け**: `/ec guide`（目的地案内・光の道しるべ）, `/ec quest`, `/ec codex`, `/ec claim`（未受取報酬の回収）, `/ec title`, `/ec fishing`, `/ec party`
- **拠点・ダンジョン管理**: `/ec sites`, `/ec sites repair`, `/ec sites acknowledge-cleanup <areaId>`, `/ec dungeon release <slot>`, `/ec travel <areaId>`
- **診断・リロード**: `/ec validate`, `/ec reload`, `/ec pack info`
- **補填・配布**: `/ec starter <player>`, `/ec give <player> <item-id> [amount]`, `/ec currency <player> <amount>`

---

## ソースからのビルド & テスト

Java 25 環境で Gradle Wrapper を実行します：

```bash
# Windows (PowerShell)
.\gradlew.bat test build

# Linux / macOS
./gradlew test build
```

主な出力ファイル：
- プラグイン JAR: `build/libs/EtherealChronicle-0.1.1.jar`
- リソースパック ZIP / SHA-1: `build/generated/resourcepack/etherealchronicle-resourcepack.zip` / `.sha1`

---

## ドキュメント

- [docs/RELEASE.md](docs/RELEASE.md) — リリースノート・実装範囲・検証状況・バックアップ対象
- [docs/RESOURCEPACK_HOSTING.md](docs/RESOURCEPACK_HOSTING.md) — 本番向けリソースパック配信ガイド
- [docs/OVERWORLD_RECOVERY.md](docs/OVERWORLD_RECOVERY.md) — Overworld 拠点・ダンジョン運用および復旧手順
- [docs/THIRD_PARTY_NOTICES.md](docs/THIRD_PARTY_NOTICES.md) — 同梱サードパーティライブラリ（InvUI 2.5.1 / MIT）のライセンス表記
