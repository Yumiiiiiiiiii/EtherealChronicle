# リソースパック配布 既存プラグイン・ホスティング調査レポート

- **調査日**: 2026-11-22
- **対象環境**: Paper **26.3** / Java **25** / Kotlin **2.4.21**
- **現行コードベース前提（`master` @ `1c49a95`）**:
  - `ResourcePackController.kt` が既に `resource_pack.mode`（`embedded_http` / `external_url` / 実験的 `game_port`）と Adventure `ResourcePackRequest(replace=false)` を実装済み。
  - **最重要制約**: 現行 `ResourcePackController.onStatus` は固定 UUID `requestId = UUID.nameUUIDFromBytes("etherealchronicle-resource-pack-v1")` と一致する `PlayerResourcePackStatusEvent` のみで `loadedPlayers`（`hasResourcePack(playerId)`）を更新し、GUI背景・HUDフォント・`item_model` の ON/OFF を切り替えている。
- **調査ポリシー**: 調査・選定のみ（コード変更・依存追加・外部バイナリ取得なし）。すべての外部プラグイン・ホスティングは **untrusted** として扱い、導入前にソース・SHA・通信先・権限境界の監査を必須とする。

---

## エグゼクティブサマリー（結論先出し）

1. **「軽量・成熟・OSS・Paper 26.3対応・同一ポート配信」をすべて満たす既存専用プラグインは 2026年11月時点で存在しない**。
   - 実績ある無料OSS配信プラグイン（**ForcePack** / **ResourcePackManager** / **ResourceLoader**）および有料大手（**Nexo** / **Oraxen** / **ItemsAdder**）の自己ホスト機能は、いずれも**別ポート開放が必須**（ポート追加を避けるには外部ホスト／無料リモートホストを利用する設計）。
   - Paper 26.3 対応で同一ポート配信（`host.mode: same-port`）を持つ唯一の確認済みプラグインは **RP Engine** だが、ItemsAdder/ModelEngine 代替の**巨大なオールインワン基盤**かつ `v0.2.x`（公開2ヶ月）の初期段階であり、単なるパック配信用途での導入は過剰かつリスクが高い。
2. **ライセンス影響**: 既存プラグインを `plugins/` へ**別プラグインとして併用する限り、GPL-3.0 / LGPL-3.0 でも `EtherealChronicle` 本体のライセンスへは影響しない**（コード依存・シェードをしなければ安全）。
3. **最終推奨**: **「併用（役割分担）」— 本番は「自前 `external_url`（Cloudflare R2 / GitHub Releases + CI PackSquash）」または「既存プラグイン（`ForcePack` / `ResourcePackManager`）へ配信委譲＋当プラグインに軽量な `external_managed` 監視モードを追加」の選択式を推奨**。
   - **単一パック・低保守・ポート追加なし最優先**: **自前 `external_url`＋GitHub Releases / Cloudflare R2（+ CI PackSquash）**（既存実装そのまま、追加プラグイン不要）。
   - **複数パック強制・権限バイパス・ライブ再配信・Velocity運用を既存プラグインに任せたい**: **`ForcePack`（別プラグイン併用）＋外部静的ホスト**（＋当プラグインに `external_managed` モード追加）。
   - **将来 BetterModel や他プラグイン多数のパックを自動マージしたい・ポート開放不可で無料リモートに頼りたい**: **`ResourcePackManager`（別プラグイン併用）**（ただし権限バイパス非対応・Folia非対応・`magmaguy.com` 51.2MB上限に留意）。

---

## 1. 候補別 総合比較表（Paper 26.3 / Java 25・機能・ライセンス）

> 凡例: ◎＝標準で充実対応、○＝対応（設定/条件あり）、△＝部分的・制限あり、×＝非対応、?＝公開ドキュメント未確認

| 候補 | 出典 URL | 価格 / ライセンス（※別導入時の当方影響） | 最新版・最終更新 / Paper 26.3 & Java 25 | メンテ・Issue 状況 | ポート要件（追加なし可否） | 必須化 | SHA-1 | 複数パック / 自動マージ | 更新時再配信 | 拒否時キック | 権限バイパス |
|---|---|---|---|---|---|---|---|---|---|---|---|
| **ForcePack** (SamB440) | [GitHub](https://github.com/SamB440/ForcePack) / [Modrinth](https://modrinth.com/plugin/forcepack) | 無料 / **GPL-3.0-only**（別導入なら影響なし） | `1.3.75-SNAPSHOT+325` (2026-11) / **26.3・Folia・Velocity対応** | 非常に活発（48.7K DL）。Paper特化（Spigot終了）。偽装対策Issue対応継続 | 自己ホストは**別ポート必須**（既定`8080`）。外部URL併用なら追加不要 | ◎ | ◎（自動生成・検証） | 複数同時配信◎ / **自動マージ×** | ◎（`/forcepack reload send`） | ◎（状態別設定可） | ◎（`ForcePack.bypass` + Geyser除外） |
| **ResourcePackManager** (MagmaGuy) | [GitHub](https://github.com/MagmaGuy/ResourcePackManager) / [Modrinth](https://modrinth.com/plugin/resourcepackmanager) | 無料 / **LGPL-3.0-only**（Modrinth表記、GitHubに`LICENSE`なし。別導入なら影響なし） | `v2.4.12` (2026-11-07) / **26.3対応**（※**Folia非対応**） | 活発（39K DL）。2.4.xでGeyser変換/外部障害フォールバック修正継続 | 自己ホストは**別ポート**（既定`mcPort+1`）だが、不可時は**無料リモート（51.2MB迄）へ自動フォールバック（追加ポート不要）** | ○（`forceResourcePack`） | ◎（自動） | **自動マージ◎**（20+プラグイン+`mixer/`、※BetterModelは手動登録） | ○（自動検知＋`/rspm reload`） | ○（強制画面切断） | **×**（公式バイパス権限なし） |
| **ResourceLoader** (DefectiveVortex) | [GitHub](https://github.com/DefectiveVortex/Resourceloader) / [Modrinth](https://modrinth.com/plugin/resourceloader) | 無料 / **GPL-3.0-only**（別導入なら影響なし） | `v2.5.2` (2026) / **26.3対応** | 継続更新（4.7K DL・小規模コミュニティ） | 内蔵HTTPは**別ポート必須**（既定`40021`）。URLモードなら不要 | ○ | ○ | 手動コマンド合成○（`/mergepack`） | ○（`/load`） | ○（`kick` / `restrict`） | ○（`resourceloader.bypass`） |
| **Force Resourcepacks** (Phoenix616) | [SpigotMC](https://www.spigotmc.org/resources/force-resourcepacks.10499/) / [Docs](https://docs.phoenix616.dev/) | **有料** / GPLv3（購入者にソース提供） | `v1.21.41` (2026-09-17) / **26.3・Velocity対応** | 非常に安定・長期運用実績あり | **内蔵HTTPなし**（外部URL必須＝追加ポート不要） | ◎ | ◎ | 複数配信・ワールド/リージョン別◎ / マージ× | ◎ | ◎（詳細アクション） | ◎（権限・Geyser対応） |
| **RP Engine** (ResourcePackAI) | [GitHub](https://github.com/resourcepack-ai/rp-engine) / [Hangar](https://hangar.papermc.io/ResourcePackAI/RPEngine) | 無料 / **GPL-3.0**（別導入なら影響なし） | `v0.2.90` (2026-11-18) / **26.3・Folia対応** | 極めて高頻度更新だが**2026-09公開のv0.2系**（7.1K DL / Hangar 238 DL） | **同一ポート対応**（`host.mode: same-port`）＋無料自動upload（追加ポート不要） | ?（詳細未索引） | ○ | コンテンツ統合ビルド○ | ○ | ? | ? |
| **PackLoader** (natan307) | [GitHub](https://github.com/natan307/PackLoader) / [Hangar](https://hangar.papermc.io/) | 無料 / **MIT**（※Hangar/Spigot一部表記に旧ARR残存） | `v1.1.1` (2026-11-18) / **26.3対応**（※Folia不可） | 2026-11公開直後で実績極小 | **別ポート必須**（既定`8163`、25565使用禁止と明記）＋CDNモード | ? | ○ | 自動マージ○ | ○ | ? | ? |
| **RoseResourcepack (RoseRP)** | [GitHub](https://github.com/ladakx/RoseResourcepack) / [Modrinth](https://modrinth.com/plugin/roseresourcepack) | 無料 / **MIT** | `v3.5.0` (2026-04-16) / **26.1.2まで（26.3未記載）** | 更新停滞気味（7ヶ月更新なし） | **別ポート必須**（例`8443`、変更に再起動要） | ○（強制画面） | ○ | 複数配信○ / マージ× | ○ | **×**（ロードマップ段階） | **×** |
| **Nexo / Oraxen / ItemsAdder** | [Nexo](https://docs.nexomc.com/) / [Oraxen](https://docs.oraxen.com/) / [ItemsAdder](https://itemsadder.devs.beer/) | **有料**（$22.99 / €19.99 / $24.99）独自商用ライセンス（再配布不可） | 2026年継続更新（26.x順次対応） | 大手商用で活発（ただし大規模フレームワーク依存） | 自己ホストは**3種とも別ポート必須**（`8082`/`8080`/`8163`）。Polymath/Hermes/LobFile連携なら追加ポート不要 | ○ | ◎ | 自社エコシステム内マージ◎ | ○ | ○ | ○（権限設定あり） |
| **Paper標準 `server.properties`** | [Paper Docs](https://docs.papermc.io/) | 無料 / Paper標準 | Paper **26.3 標準** | 公式・最も安定 | **内蔵HTTPなし**（外部URL必須＝追加ポート不要） | ○（`require-resource-pack`） | ◎（`resource-pack-sha1`） | **×**（単一パックのみ） | **×**（**フル再起動必須**） | ○（拒否時切断） | **×**（全員一律） |
| **外部無料ホスト＋設定のみ**（GitHub Releases / Cloudflare R2 / `mcpacks.dev` / LobFile） | [R2](https://developers.cloudflare.com/r2/) / [mcpacks.dev](https://mcpacks.dev/) / [LobFile](https://lobfile.com/) | 無料枠あり（R2: 10GB・転送費0 / GitHub: 公開無料 / mcpacks.dev: 無料250MB / LobFile: 無料25Mbps） | プラットフォーム非依存（HTTPS静的配信） | R2・GitHubは超高信頼。`mc-packs.net`（旧）は**非メンテ・95MB上限・429エラー多発で非推奨** | **追加ポート不要（HTTPS 443）** | 配信側依存（当プラグインor ForcePack） | 配信側設定 | **CI + PackSquash**で事前合成・最適化可 | URL/ハッシュ更新＋再送信 | 配信側依存 | 配信側依存 |

---

## 2. 主要候補の深掘り評価（長所・短所・落とし穴）

### 2.1 ForcePack（SamB440）— 配信制御・運用機能では最強の無料OSS
- **強み**: Paper 26.3 / Folia / Velocity まで正式追従。状態別（`DECLINED` / `FAILED_DOWNLOAD` / `FAILED_RELOAD` / `DISCARDED`）のキック・コマンド実行、`ForcePack.bypass` 権限、Geyser 除外、偽装パケット対策、`/forcepack reload send` によるオンライン再配信、CI向け `{hash}` プレースホルダ＋`/vforcepack update <hash>` まで実運用に必要な機能が完備されている。
- **弱み**:
  - 自己ホスト（`forcepack://pack.zip`）は**別ポート必須**（既定 `8080`）。1ポート環境では外部静的ホスト（Cloudflare R2 / GitHub Releases 等）との組み合わせが前提。
  - 複数パックの **ZIP 自動マージ機能はない**（MC 1.20.3+ の複数パック同時積載機能で解決する設計。ただし同じフォント/アセットIDが衝突しない設計であれば、むしろマージ不要で透明性が高い）。

### 2.2 ResourcePackManager（MagmaGuy）— 自動マージ＋ポート不要フォールバックが強み
- **強み**: 20以上のプラグインのパックと `plugins/ResourcePackManager/mixer/*.zip` を優先度付きで自動マージ。自己ホスト（既定 `mcPort + 1`）を最初に試行し、外部到達性チェックに失敗すると自動で無料リモートホスト（`https://magmaguy.com/rsp/<uuid>`、**51.2 MB 上限**）へフォールバックするため、**ポートを開けられない環境でも動く**。Geyser 向け Bedrock 自動変換も強力。
- **弱み・注意点**:
  - **権限バイパス（`bypass`）が公式ドキュメントに存在しない**（管理者が一時的にスキップする等の柔軟性に欠ける）。
  - **BetterModel のネイティブ統合が未搭載**（`mixer/` または `compatible_plugins/` での手動定義が必要）。
  - **Folia 非対応**。
  - 無料リモートホスト（`magmaguy.com`）は第三者個人運営サービスであり、過去にも到達不能障害（v2.4.8 でフォールバック修正）や利用規約・EUデータ規約が絡む。本番の単一障害点にするのは慎重であるべき。
  - GitHub リポジトリに `LICENSE` ファイルが置かれていない点（Modrinth では LGPL-3.0-only 表記）は、別プラグイン運用なら実害はないが透明性で ForcePack に劣る。

### 2.3 RP Engine / PackLoader / ResourceLoader — 新興・ニッチ候補の評価
- **RP Engine (`resourcepack-ai/rp-engine`)**: Paper 26.3 で `host.mode: same-port` と無料自動アップロードを持つ唯一の確認済み OSS だが、カスタムアイテム・ブロック・家具・モデル・GUI・HUD・Dialog を丸ごと抱える **ItemsAdder 代替の総合エンジン**であり、2026年9月公開の `v0.2.x`（初期開発版）。すでに独自UI・アイテム基盤を持つ EtherealChronicle にパック配信だけのために載せるのは過剰結合・競合リスクが大きい。
- **PackLoader**: 2026年11月17〜18日公開の超新興プラグイン（実績不足、別ポート `8163` 必須、ライセンス表記に一部旧ARR残存）。現時点での採用は時期尚早。
- **ResourceLoader**: 26.3 対応・別ポート・`/mergepack`・`kick`・`resourceloader.bypass` と一通り揃うが、小規模（4.7K DL）であり、強制配信重視なら ForcePack、自動マージ重視なら ResourcePackManager が上位互換。

### 2.4 Nexo / Oraxen / ItemsAdder — 有料コンテンツ基盤の自己ホスト機能
- いずれも **有料（$20〜25前後）・再配布禁止ライセンス** であり、しかも**内蔵自己ホストはすべて別ポート必須**（Nexo `8082` / Oraxen `8080` / ItemsAdder `8163`）。
- ポート追加を避けるには付属の **Polymath / Hermes / Atlas**（Oraxen Atlas は一部地域ブロックあり）または **LobFile**（無料 25 Mbps / 100 GB/月）を使う構造であり、パック配信機能だけのために有料フレームワークを導入する合理性はない。

### 2.5 無料外部ホスティング + PackSquash CI 連携
- **Cloudflare R2（最推奨）** / **GitHub Releases（最も手軽）**:
  - 追加ポート不要・標準 HTTPS（443）・Velocity / TCPShield / Cloudflare Spectrum 配下でも 100% 動作・ゲームサーバーの帯域とCPUを一切消費しない。
  - GitHub Actions の **`ComunidadAylas/PackSquash-action@v4`**（Action自体は MIT、出力 ZIP に AGPL 波及なし）と組み合わせれば、push / リリース時に自動圧縮（PNG/JSON/OGG 最適化）→ SHA-1 生成 → R2 または GitHub Releases 公開まで完全自動化できる。
- **MC専用無料ホストの注意点**:
  - **`mc-packs.net`（旧）**: 運営者自身が「非メンテの趣味サイト」と言明、95 MB 上限、`HTTP 429` レート制限、PackSquash 厳格ZIP非互換などトラブルが多いため**非推奨**。
  - **`mcpacks.dev`（新・Obsidian Servers 運営）**: 250 MB 上限・REST API あり・6ヶ月無アクセスで自動削除。検証・一時公開用には実用になるが、本番永続運用は自前管理できる **Cloudflare R2 / GitHub Releases** が確実。

---

## 3. 導入時に必要な作業・当プラグインとの連携と競合分析

### 3.1 現行 `EtherealChronicle` との決定的な結合ポイント

現行コード（`src/main/kotlin/net/nakaymi/etherealchronicle/paper/ResourcePackController.kt`）の挙動を確認した結果、**既存プラグインを単純に追加するだけでは以下の2つの競合/不整合が必ず発生する**:

1. **二重送信・競合リスク**:
   - 当プラグインの `config.yml` で `resource_pack.enabled: true` のまま外部プラグイン（ForcePack 等）や `server.properties` を有効化すると、プレイヤーログイン時に **2回の `ResourcePackRequest`（二重プロンプト・二重ダウンロード）** が発生する。外部プラグイン側が `replace(true)` で送信した場合、当プラグインのパックが上書き除去される。
2. **`hasResourcePack(playerId)` 喪失問題（最重要）**:
   - 当プラグインの UI 背景グリフ・HUD アイコン・`refreshPackItemModels()` は、`ResourcePackController.hasResourcePack(playerId)` が `true` のプレイヤーにのみ PUA 文字とカスタム `item_model` を有効化する安全設計になっている。
   - 現行実装では `if (event.id != requestId) return` で**自前固定 UUID（`etherealchronicle-resource-pack-v1`）のみを監視**し、さらに `resource_pack.enabled: false` にするとリスナー自体を登録しない。
   - したがって、**外部プラグインへ配信を任せる場合は、`ResourcePackController` に「自前送信は停止するが、`PlayerResourcePackStatusEvent` だけは監視して `hasResourcePack` を更新する」軽量モード（例: `mode: external_managed` + 任意の `expected_pack_id` または全成功監視）を約30〜50行追加する必要がある**。

### 3.2 候補別：導入作業・既存 `resource_pack.mode`・Adventure `ResourcePackRequest` との競合表

| 導入方式 | 当プラグイン側設定・改修 | 既存 `resource_pack.mode` との関係 | Adventure `ResourcePackRequest` 競合有無 | 外部側（サーバー/CI）の導入作業 |
|---|---|---|---|---|
| **ForcePack 併用** | `mode: external_managed` 追加（送信停止＋Status監視のみ。`expected_pack_id` に ForcePack のパックUUIDを設定 or 任意成功扱い） | 既存 `embedded_http` / `game_port` は停止。`external_managed` へ切替 | **要回避**: `enabled: true` 併存だと二重送信。送信を ForcePack 一本に統一すれば競合ゼロ（MC 1.20.3+ の複数パック積載で BetterModel とも共存可） | ForcePack JAR 配置、ZIP 配置（`forcepack://`＋別ポート開放）または外部URL設定、SHA-1/権限(`ForcePack.bypass`)/キック設定 |
| **ResourcePackManager 併用** | 同上（`mode: external_managed` 追加。RSPM のマージ後パックは UUID が変わるため UUID 指定せず `SUCCESSFULLY_LOADED` 監視または `pack_id` 自動追従） | 同上（自前送信・自前HTTPを停止） | **要回避**: 送信を RSPM 一本に統一。RSPM が全プラグインパックを1本に統合送信するため衝突しない | RSPM JAR 配置、`plugins/ResourcePackManager/mixer/` へ当方パックZIP配置（将来 BetterModel 出力も同配置）、`forceResourcePack` 設定、`/rspm reload` |
| **ResourceLoader 併用** | 同上（`mode: external_managed`） | 同上 | 同上（送信を ResourceLoader 一本に統一） | ResourceLoader JAR 配置、`packs/` へZIP配置、`server-port`（既定 `40021`）開放またはURL指定、kick/bypass 設定 |
| **RP Engine 併用** | 同上（`mode: external_managed`） | 同上 | **高注意**: RP Engine 自体が GUI/HUD/アイテム/ダイアログ機能を持つためコマンド・イベント・アセット競合の精査が必要 | RP Engine JAR 配置、contentフォルダへ展開、`host.mode: same-port` または `upload` 設定 |
| **Paper標準 `server.properties`** | `mode: external_managed`＋`expected_pack_id` に `server.properties` の **`resource-pack-id`** を一致させる（完全1対1判定可能！） | 自前送信・HTTPを停止 | **要回避**: `enabled: true` のままだとログイン直後に2回プロンプトが出る。`external_managed` で完全解消 | 外部ホストへZIP配置、`server.properties` の5項目（`resource-pack`, `resource-pack-id`, `resource-pack-sha1`, `require-resource-pack`, `resource-pack-prompt`）記入＋サーバー再起動 |
| **自前 `external_url` + 外部静的ホスト（R2 / GitHub Releases + CI PackSquash）** | **コード改修ほぼ不要**（現行 `config.yml` の `mode: external_url`、`external_url`、`external_sha1` を設定するだけ。必要に応じて権限バイパス/リロードコマンドのみ小追加） | **現行設計の主系そのまま** | **競合なし**（当プラグインが `replace(false)` で単一管理。BetterModel 等は別パックIDで積載共存可能） | GitHub Actions に `PackSquash-action@v4` + R2 / GitHub Releases アップロードを組むのみ |

---

## 4. 自前実装を残す場合 vs 既存プラグインへ移行する場合の比較

現行 `master`（`1c49a95`）には、すでにテスト済みの `EmbeddedPackServer`（別ポート）と `GamePortPackServer`（実験的同一ポート）と `external_url` モードが存在する点を踏まえ、純粋な維持コスト・安全性・ポート要件を比較する。

| 比較軸 | ① 自前 `external_url` 運用（HTTP配信はR2/GitHubへ外出し、送信制御のみ自前） | ② 自前 `embedded_http` / `game_port` 維持 | ③ 既存プラグイン採用（ForcePack / ResourcePackManager）＋自前は `external_managed` 監視のみ |
|---|---|---|---|
| **追加ポート要件** | **不要（0）**（HTTPS 443） | `embedded_http`: **別ポート1本**<br>`game_port`: **不要（0）**（※プロキシ不可・HTTP平文） | **ForcePack**: 外部URL併用なら**0**（自己ホストは別ポート1本）<br>**RSPM**: 無料リモート併用なら**0**（51.2MB迄、自己ホストは別ポート1本） |
| **保守コスト** | **極低**（`ResourcePackController` 約120行のみ、Paper API標準の Adventure だけなので MC 26.4+ 追従が極めて楽） | **中〜高**（特に `GamePortPackServer` は Paper 内部 `ChannelInitializeListenerHolder` リフレクション依存のため Paper メジャー更新のたびに検証・保守が必要） | **低〜中**（配信・強制・偽装対策・Velocity対応の保守を外部作者へ委譲できる。一方、Paper 26.4+ 対応待ちや `external_managed` 連携テストは必要） |
| **セキュリティ・安定性** | **最高**（ゲームサーバーにHTTP口を開けない、HTTPS、CDNキャッシュ、DDoS耐性、Velocity/TCPShield完全互換） | **中**（Codex実装は per-IP/ヘッダ期限/帯域制限まで堅牢化済だが、同一回線帯域共有・HTTP平文・`game_port` はプロキシ非互換） | **高（ForcePack + 外部URL）〜中（RSPM無料リモート）**（ForcePack は偽装対策・Velocity実績が厚い。RSPM は第三者リモート障害/51.2MB上限リスクあり） |
| **機能充実度（バイパス・キック・再配信・合成）** | 基本機能のみ（権限バイパス・状態別キック・`/reload send`・複数パック合成は未実装。必要なら自前追加が必要） | 同左（配信ソケット周りは完結しているが運用コマンド系は最小限） | **ForcePack**: バイパス/キック/ライブ再配信/Velocity/偽装対策が即戦力<br>**RSPM**: 20+プラグイン自動マージ＋Geyser変換が即戦力 |
| **当プラグインUI連動（`hasResourcePack`）** | **完璧**（自前 `requestId` で 100% 厳密判定、`/ec ui pack` と自然連動） | **完璧** | **小改修で対応可**（`mode: external_managed` で `PlayerResourcePackStatusEvent` を受けるだけで UI/HUD/item_model 切替を維持可能） |
| **ライセンス・サプライチェーン** | 自前コードのみ（外部JAR監査不要） | 自前コードのみ | GPL-3.0 / LGPL-3.0 でも別プラグイン併用なら影響なし。ただし外部JAR更新時の検証は必須 |

---

## 5. 最終推奨：採用方針と理由

### 結論：**「併用（外部委譲可能な `external_managed` 追加 ＋ 本番は用途別に『自前 `external_url`』または『`ForcePack` / `ResourcePackManager`』を選択）」**

単純に「自前実装を全削除して既存プラグインへ一本化」するのではなく、以下の**3原則**で整理するのが最も合理的である。

#### 推奨 1（本番運用の本命 — ポート追加なし・最高安定・最小保守）
- **構成**:
  - **単一サーバー / EtherealChronicle 主体**: 現行の **自前 `external_url`（Cloudflare R2 または GitHub Releases + CI `PackSquash-action@v4`）** を第一推奨とする。
  - **運用機能（権限バイパス・拒否時キック・ライブ再配信・Velocity連携・偽装対策）も既存プラグインに任せたい場合**: **`ForcePack`（無料・GPL-3.0・Paper 26.3/Folia/Velocity対応）を別プラグイン併用**し、配信元URLは同じく Cloudflare R2 / GitHub Releases を指定する（追加ポート 0、HTTPS 完備）。当プラグイン側は `mode: external_managed` に切り替えてステータス監視のみ行う。
- **理由**:
  - すでに安定動作している `ResourcePackController` と外部静的ホストの組み合わせは、ポート追加ゼロ・HTTPS・プロキシ完全互換・Paper更新への耐性すべてで最高評価。
  - ForcePack を併用すれば、今後必要になる「権限バイパス・拒否時キック・オンライン再配信・Velocity 統合」を自前で書き足さずに即座に手に入れられる。

#### 推奨 2（他プラグインとのパック自動マージが必要になった場合）
- **構成**: **`ResourcePackManager`（無料・LGPL-3.0・Paper 26.3対応）を別プラグイン併用**＋当プラグインは `mode: external_managed`。
- **採用トリガー**: 将来的に BetterModel（生成パック）＋複数サードパーティプラグインのリソースパックを**単一ZIPへ自動統合**したい場合、または Geyser（Bedrock 統合版）対応を行う場合、もしくは「どうしても外部ホストを用意できず 51.2 MB 以内で無料リモート自動フォールバックを使いたい」場合。
- **注意点**: `bypass` 権限がない点・Folia 非対応・BetterModel は `mixer/` 手動連携が必要・`magmaguy.com` 外部依存リスクを許容できる場合のみ選ぶ。

#### 推奨 3（自前 `GamePortPackServer` / `EmbeddedPackServer` の扱い）
- **方針**:
  - **同一ポート配信の代替となる軽量・成熟な既存専用プラグインは現時点で存在しない**ため（`RP Engine` は巨大かつ v0.2 系）、既に実装・テスト済みの **`embedded_http`（ローカル開発・別ポート検証用）** はそのまま残す価値が高い。
  - 一方、Paper 内部クラス依存の **`same_port_netty`（`GamePortPackServer`）** は実験的オプトインのまま隔離維持し、**本番環境では使わない方針（外部URL または ForcePack/RSPM 推奨）**を明文化する。Paper 26.4+ 等で内部 API が破壊された場合は無理に追従せず廃止できる設計にしておけば保守負債にならない。

#### 導入アクションまとめ（もし既存プラグイン併用へ進む場合の最小変更）
1. **当プラグイン側（小改修のみ）**: `ResourcePackController` に `mode: external_managed`（任意で `expected_pack_id`）を追加し、自前 `ResourcePackRequest` 送信と内蔵HTTP起動を抑止しつつ、`PlayerResourcePackStatusEvent` 監視と `/ec ui pack` によるデュアルモード UI 切替だけを生かす。
2. **外部運用側（運用フェーズに応じて選択）**:
   - 最小構成（ポート0・追加プラグイン0）: GitHub Actions `PackSquash-action@v4` → Cloudflare R2 / GitHub Releases → `config.yml` `mode: external_url`
   - 配信制御を既存プラグインへ委譲（ポート0）: 上記 URL を **ForcePack** に設定 ＋ 当プラグイン `mode: external_managed`
   - 複数プラグイン自動マージ重視: **ResourcePackManager** の `mixer/` に当方パックZIPを配置 ＋ 当プラグイン `mode: external_managed`
