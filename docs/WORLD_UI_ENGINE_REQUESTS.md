# EtherealChronicle UI・ワールド・リソースパック・独自Mob エンジン実装依頼書（Codex向け）

- **対象設計書**: `docs/WORLD_UI_DESIGN.md`
- **対象アセットソース**: `resourcepack/`（Minecraft 26.3 / pack format `97.1`、PackSquash不要の単純ZIPで動作）
- **対象技術方針**:
  - UI: Paper Dialog API + Adventure / MiniMessage + **InvUI v2**（MIT、シェード）
  - Mob: 同梱データパック（`paper-plugin.yml` + `DatapackRegistrar`）＋ **BetterModel**（MIT、`softdepend` 任意連携、未導入フォールバック必須、**GPL系不採用**）
  - World: Paper `StructureManager`（`.nbt`）＋ `DemoWorld.kt` ハイブリッド
  - Resource Pack 配布: 既定＝別ポート内蔵 HTTP（または外部ホスト）＋ Adventure `ResourcePackRequest`（SHA-1・Configuration Phase）、同一ポート Netty 注入は「実験的オプトイン（config 既定 OFF）」、**コアシェーダ不使用**

---

## 優先度ロードマップ一覧

| 優先度 | ID | 項目 | 依存 |
|---|---|---|---|
| **P0（最優先）** | `REQ-WUI-01` | `paper-plugin.yml` 移行＋`PluginBootstrap`＋`DatapackRegistrar` 基盤 | なし |
| **P0（最優先）** | `REQ-WUI-02` | リソースパック単純 ZIP ビルド（Gradle）＋配信基盤（別ポート内蔵HTTP / 外部URL / SHA-1 / Configuration Phase）＋プレイヤー別 `hasResourcePack` 追跡 | なし |
| **P1（高）** | `REQ-WUI-03` | UI 刷新（InvUI v2 導入＋Paper Dialog API＋リソパ有無フォールバック＋HUD/BossBar拡張） | `REQ-WUI-02` |
| **P1（高）** | `REQ-WUI-04` | コンテンツ YAML 任意ビジュアルフィールド拡張（`item_model`, `variant`, `bettermodel_id`, `scale`, `equipment`, `biome` 等、後方互換） | `REQ-WUI-01` |
| **P1（高）** | `REQ-WUI-05` | 同梱データパック生成・登録（`wolf_variant` 3種＋カスタムバイオーム 6種＋現行 `AdvancementDatapack` 協調） | `REQ-WUI-01`, `04` |
| **P2（中）** | `REQ-WUI-06` | `StructureManager`（`.nbt`）ローダー＋`DemoWorld.kt` ハイブリッド移行（`v4 → v5`）＋ダンジョンアリーナ構造体配置＋個人時間/天候上書き | `REQ-WUI-01` |
| **P2（中）** | `REQ-WUI-07` | BetterModel（MIT、`softdepend`）任意連携アダプタ＋バニラフォールバックレンダラー | `REQ-WUI-04`, `05` |
| **P3（低・実験）** | `REQ-WUI-08` | 同一ポート HTTP 配信（Netty パイプライン注入、`same_port_netty` 実験的オプトイン、既定 OFF） | `REQ-WUI-02` |

---

## P0: 基盤・リソースパックビルド＆配布

### `REQ-WUI-01`: `paper-plugin.yml` 移行 & `DatapackRegistrar` 基盤

- **目的**: プラグイン JAR 内に同梱したデータパック（`wolf_variant`・カスタムバイオーム・将来の Dialog/構造体等）をワールドフォルダを汚さずに登録できるようにする。
- **実装要件**:
  1. `src/main/resources/paper-plugin.yml` を作成（`api-version: '26.3'`、`bootstrapper` に `EtherealBootstrap : PluginBootstrap` を指定、`dependencies.server.BetterModel` を `load: BEFORE, required: false` で宣言）。
     - **注意**: MockBukkit `mockbukkit-v26.3` が `paper-plugin.yml` と既存 `plugin.yml` をどう解決するか確認し、全既存テスト（165+件）が壊れない構成を維持すること（必要なら `plugin.yml` 併存またはテストブート調整）。
  2. `EtherealBootstrap#bootstrap(BootstrapContext)` 内で `LifecycleEvents.DATAPACK_DISCOVERY` を購読し、JAR 内 `datapacks/etherealchronicle_core` を `event.registrar().discoverPack(uri, "provided")` で登録する。
  3. 現行 `AdvancementDatapack.kt`（YAML 由来の動的進捗書き出し）は段階移行とし、初回は既存挙動を壊さず共存させる（将来的に一時生成パス経由の `discoverPack` 統合を任意検討）。
- **受入条件**:
  - Paper 26.3 実サーバー起動ログに同梱データパック認識が出てエラーゼロ。
  - 既存の `./gradlew test` が全件 PASS。

### `REQ-WUI-02`: リソースパック単純 ZIP ビルド & 配布基盤（既定＝別ポート内蔵 HTTP / 外部 URL）

- **目的**: `resourcepack/` ソースを PackSquash 不要の単純 ZIP として再現可能にビルドし、SHA-1 付きで安全にクライアントへ配布する。
- **ビルド要件（`build.gradle.kts`）**:
  1. Gradle タスク `packageResourcePack`（`Zip` タスク）を追加：`resourcepack/pack.mcmeta`, `resourcepack/pack.png`, `resourcepack/assets/**` をルート直下に格納し、`build/generated/resourcepack/etherealchronicle-resourcepack.zip` と `etherealchronicle-resourcepack.sha1`（40桁小文字 hex）を出力（再現性のため `isPreserveFileTimestamps = false`, `isReproducibleFileOrder = true`）。
  2. 生成された ZIP と `.sha1` をプラグイン JAR 内 `resourcepack/` リソースにもバンドルするか、`plugins/EtherealChronicle/resourcepack.zip` へ自動展開して配信可能にする。
- **設定スキーマ（`config.yml` 追加案）**:
  ```yaml
  resource_pack:
    enabled: true
    required: false                 # 既定 false（拒否してもフォールバックUIで遊べる）
    mode: embedded_http             # embedded_http | external_url | same_port_netty (実験)
    external_url: ""                # mode=external_url 時の HTTPS URL
    external_sha1: ""               # 空なら同梱パックの SHA-1 を自動参照
    prompt: "<gold>EtherealChronicle 専用リソースパックを適用しますか？\n<gray>未適用でもプレイ可能です。"
    embedded_http:
      bind_host: "0.0.0.0"
      public_host: ""               # 配信先ホスト名/IP（空ならサーバー接続先アドレスまたは警告）
      port: 25580                   # ゲームポートと別の既定ポート
      max_concurrent: 32
    same_port_netty:
      enabled: false                # 実験的オプトイン（既定 OFF）
      max_header_bytes: 4096
      max_concurrent: 16
      bandwidth_kb_per_sec: 1024
  ```
- **ランタイム配布・状態管理要件**:
  1. **別ポート内蔵 HTTP（既定）**: JDK 標準 `com.sun.net.httpserver.HttpServer` を使用（追加依存ゼロ・NMS ゼロ）。
     - 許可メソッドは **`GET` / `HEAD` のみ**、許可パスは **`/pack/<sha1>.zip` の1パス固定**（それ以外は `404`、パストラバーサル完全遮断）。
     - プラグイン `onDisable` 時に必ず `server.stop(0)` でポート解放（タスク/ソケットリーク禁止）。
  2. **クライアント送信 API**: Paper 26.3 の **Configuration Phase**（`AsyncPlayerConnectionConfigureEvent` またはログイン直後）にて、Adventure `ResourcePackInfo`（決定論的 UUID + URL + SHA-1）＋ `ResourcePackRequest`（`required`, `prompt`, `replace(false)`）で送信する（非推奨の `Player#setResourcePack` は使用しない）。
  3. **適用状態追跡**: `ResourcePackCallback` / `PlayerResourcePackStatusEvent` で `SUCCESSFULLY_LOADED` を受け取ったプレイヤーを `hasResourcePack = true`、それ以外（`DECLINED` / `FAILED_DOWNLOAD` / `INVALID_URL` / `DISCARDED`）を `false` として保持。ログアウト時に確実に解放。
  4. **プレイヤー個人切替コマンド**: `/ec ui pack <auto|on|off>`（`off` なら強制的にバニラフォールバックUI描画、`on` は再送信またはリソパモード強制）。
- **受入条件**:
  - `packageResourcePack` 出力 ZIP が単純展開で `pack.mcmeta` をルートに持ち、SHA-1 が一致する。
  - `embedded_http` が起動・配信・`onDisable` 停止をリークなく実行でき、許可外パスに `404` を返す単体テスト PASS。
  - リソパ拒否クライアントで PUA 文字（`U+E000`–`U+E2FF`）が一切送信されないことを単体テストで検証。

---

## P1: UI 刷新・YAML ビジュアル拡張・同梱データパック

### `REQ-WUI-03`: InvUI v2 導入＋Paper Dialog API＋デュアルモード UI & HUD

- **依存追加（`build.gradle.kts`）**:
  - リポジトリ: `https://repo.xenondevs.xyz/releases`
  - 依存: `xyz.xenondevs.invui:invui:<2.5.1+>`、`xyz.xenondevs.invui:invui-kotlin:<2.5.1+>`（**MIT**、ShadowJar で `net.nakaymi.etherealchronicle.libs.invui` へリロケートしてシェード）。
  - Paper Dialog API・Adventure・MiniMessage は Paper 26.3 標準のため追加依存なし。
- **実装要件**:
  1. **`UiThemeProvider` 抽象化**: `player: Player` の `hasResourcePack` に応じて、GUI タイトル（`<font:ec:ui>` 背景グリフ vs MiniMessage テキスト）およびボタンアイコン（`minecraft:item_model="ec:ui_*"` vs バニラ素材）を透過的に切り替える単一窓口を作る。
  2. **対象8画面の移行（`docs/WORLD_UI_DESIGN.md` 第2章準拠）**:
     - ① 職業選択（InvUI 3行＋確定確認 **Paper Dialog API**）
     - ② ステータス/装備（InvUI 6行）
     - ③ スキル管理（InvUI 3行＋既存ホットバー `isSkill` 連動）
     - ④ 図鑑統合（InvUI 6行タブ＋ページング：`CodexController` の5種を統合）
     - ⑤ 装備強化（InvUI 5行＋高強化確認 Dialog＋クローズ/切断時の確実なアイテム返却＆満杯時 `pendingRewards` 退避）
     - ⑥ ショップ（InvUI 6行＋まとめ買い Dialog＋シフトクリック/数字キー/ドラッグ搾取防止）
     - ⑦ NPC 会話・クエスト（**Paper Dialog API** 主系＋InvUI フォールバック）
     - ⑧ 常時 HUD（ActionBar 0.5秒周期＋BossBar 最大2本：Slot A=ボスHP属性色/釣り進行度、Slot B=エリア＋星時計、`/ec ui hud` で Slot B 切替可能）
  3. **セキュリティ不変条件**: すべての InvUI 画面でアイテム増殖（シフトクリック・数字キー・オフハンドスワップ・ドラッグ・切断時ロス）が発生しないことをテストで担保する。

### `REQ-WUI-04`: Content YAML 任意ビジュアルフィールド拡張（後方互換）

既存の全 YAML を無変更で読み込めるよう、以下フィールドを**すべてオプショナル**で `ContentLoader` / ドメインモデルへ追加する。

- **Item / Tackle YAML**:
  ```yaml
  visual:
    item_model: ec:wpn_void_cleaver   # 任意。付与時は ItemMeta#setItemModel(NamespacedKey)
  ```
  ※ リソパ OFF プレイヤーにはアイテム生成時またはパケット干渉なしでそのままでもバニラフォールバック挙動を確認すること（注：MC 26.3 では `item_model` がクライアントに無い場合ミッシングモデル（黒紫市松）になるため、**`item_model` は「サーバーでリソースパック有効時」またはプレイヤーへ渡すUIアイテムは `hasResourcePack` 判定して付与**する。装備アイテム自体はサーバー全体設定 `resource_pack.apply_item_models: true/false`［既定 `false`、パック運用開始時に `true`］で安全切替できるようにすること！）。
- **Enemy / Boss YAML**:
  ```yaml
  visual:
    variant: ec:thorn_wolf            # 任意。WOLF 等のバニラデータ駆動バリアント
    bettermodel_id: ec_ashwood_alpha  # 任意。BetterModel 導入時に使用
    scale: 1.55                       # 任意。Attribute.SCALE (0.25..4.0 範囲制限)
    ambient_particle: AETHER          # 任意。低頻度オーラ演出
    equipment:                        # 任意。頭/主手/副手/防具の見た目指定
      head: IRON_HELMET
      main_hand: IRON_PICKAXE
      armor_color: "#2E5A38"          # 革防具染色
  ```
- **NPC YAML**:
  ```yaml
  visual:
    bettermodel_id: ec_npc_celia      # 任意
    villager_profession: LIBRARIAN    # 任意
    villager_type: PLAINS             # 任意
  ```
- **Area YAML（`chapters/*.yml`）**:
  ```yaml
  biome: ec:lumina_sky                # 任意。同梱データパックバイオームID
  ambient_sound: BLOCK_AMETHYST_BLOCK_CHIME
  ```

### `REQ-WUI-05`: 同梱データパック（`wolf_variant` 3種＋カスタムバイオーム 6種）

- **配置**: `src/main/resources/datapacks/etherealchronicle_core/`
  - `pack.mcmeta`: `{"pack": {"description": "EtherealChronicle Core Datapack", "min_format": [121, 0], "max_format": [121, 0]}}`
  - `data/ec/wolf_variant/thorn_wolf.json`, `frost_wolf.json`, `ashwood_alpha.json`（`assets`＋26.1+ `baby_assets`、`spawn_conditions: []`）
  - `data/ec/worldgen/biome/*.json`: `lumina_sky`, `ashwood_grove`, `weeping_mine`, `stargazer_hill`, `void_sanctum`, `frostpeak_expanse`（`docs/WORLD_UI_DESIGN.md` 4.4 のカラー値準拠）
- **適用**: `EnemyController#spawn` で `visual.variant` が指定され対象が `Wolf` の場合、レジストリから `Wolf.Variant` を解決して適用（見つからない場合は警告ログのみでクラッシュさせずバニラ継続）。

---

## P2: ワールド構造体ハイブリッド & BetterModel 任意連携

### `REQ-WUI-06`: `StructureManager`（`.nbt`）ローダー＋`DemoWorld.kt` ハイブリッド移行

- **目的**: 現行 `DemoWorld.kt`（v4）の安全なバージョン移行と所有確認を維持しつつ、建築物・ランドマーク・ダンジョンアリーナを `.nbt` 構造体で高品位化する。
- **実装要件**:
  1. `StructureLoader`: `src/main/resources/structures/ec/**/*.nbt` を `Bukkit.getStructureManager().loadStructure(inputStream)` で読み込み、キャッシュする。存在しない `.nbt` はログ警告でスキップ（段階的な建築追加に耐える設計）。
  2. `DemoWorld.kt` に **`v4 → v5` 移行（`migrateToV5`）** を追加：
     - `.etherealchronicle-owned` 存在必須。
     - `BlockTransformer` を実装し、**NPC座標・スポーン座標・釣り場座標・宝箱座標の保護クリアランス（半径2m×高さ3m）を上書き禁止**＋既存ブロック保護ルール（空気または初期生成床のみ置換）を適用。
     - エリア別バイオーム適用（`world.setBiome`）も管理対象チャンク内のみで安全に実行。
  3. **ダンジョンアリーナ配置**: `DungeonController.kt` のインスタンス生成時に、対応する `.nbt` アリーナが存在すれば `StructureManager` で展開、未配置なら現行の `palette` ベース生成へ自動フォールバック。
  4. **ダンジョン個人時間・天候上書き**: 入場プレイヤーへ `setPlayerTime` / `setPlayerWeather` を設定し、退場・切断・ワールド移動時に必ず `resetPlayerTime()` / `resetPlayerWeather()` で復帰させる。

### `REQ-WUI-07`: BetterModel（MIT・`softdepend`）任意連携アダプタ

- **依存追加（`build.gradle.kts`）**:
  - `compileOnly("io.github.toxicity188:bettermodel-bukkit-api:<3.7.0+>")`（**MIT**、シェード禁止・サーバー側へ別途 BetterModel 導入時のみ有効化）。
  - **GPL系（FreeMinecraftModels / PacketEvents / FAWE）は一切導入・シェードしない**。
- **設計要件**:
  1. `ModelRenderer` インターフェースを定義（`attach(entity, modelId)`, `playAnimation(entity, anim)`, `detach(entity)`）。
  2. 起動時に `Bukkit.getPluginManager().isPluginEnabled("BetterModel")` を確認し、有効なら `BetterModelRenderer`、無効なら `NoOpModelRenderer`（ログ1行出力のみ）へ自動切替。
  3. `BetterModelRenderer` のクラスロードが BetterModel 未導入環境で `NoClassDefFoundError` を起こさないよう、別クラスに隔離して遅延ロードする。
  4. `EnemyController` の攻撃・特殊攻撃予兆・フェーズ遷移・撃破時に `playAnimation`（`attack` / `cast` / `phase2` / `death`）を呼び、`remove` / チャンクアンロード / `onDisable` 時に必ず `detach` する。
  5. **未導入時フォールバック保証**: BetterModel 無し環境でも `wolf_variant`（アッシュウッド・アルファ）・`scale`・`equipment`・`ambient_particle` が自動適用され、全6ボス・全18敵・全26NPCが破綻なく動作する。

---

## P3: 実験的オプトイン機能

### `REQ-WUI-08`: 同一ポート HTTP 配信（`same_port_netty`、既定 OFF）

- **前提**: `config.yml` で `resource_pack.mode: same_port_netty` かつ `resource_pack.same_port_netty.enabled: true` が明示指定された場合のみ有効化する**実験的オプション**。
- **起動時警告**: 有効化時は起動ログに「Velocity / BungeeCord / TCPShield 等のプロキシ配下では動作しない」「HTTPS 非対応」「Paper 内部 API 依存」の警告を明示出力し、フック登録に失敗した場合は自動で `embedded_http` または `external_url` へフォールバックする。
- **実装・安全制約**:
  1. Paper 内部 `io.papermc.paper.network.ChannelInitializeListenerHolder` をリフレクション（または隔離モジュール）でフックし、パイプライン先頭へ `ByteToMessageDecoder` を挿入。
  2. 先頭 4バイトを覗き見て `GET ` または `HEAD ` の場合のみ Netty HTTP ハンドラへ切り替え、それ以外はデコーダ自身を除去して通常の Minecraft パケット処理を一切妨害しない。
  3. **セキュリティ必須制約**:
     - パスは **`/pack/<sha1>.zip` の完全一致1本のみ**（それ以外は即 `404` 切断）。
     - ヘッダサイズ上限（既定 `4096` bytes）・アイドルタイムアウト（`5` 秒）・同時接続上限（既定 `16`）・帯域上限（既定 `1024 KB/s`）を強制し、ゲーム Netty EventLoop の枯渇・DoS を防止。
     - `onDisable` 時に登録リスナーを必ず解除する。

---

## テスト・QA 要件まとめ

| 層 | 検証内容 |
|---|---|
| **Gradle / 自動テスト（MockBukkit）** | ① `packageResourcePack` ZIP 整合性＆SHA-1検証 ② `embedded_http` の `GET /pack/<sha1>.zip` 200 / 不正パス 404 / 停止リーク無し ③ `hasResourcePack=false` 時に PUA 文字・`item_model` 漏れ無し ④ InvUI 強化/ショップのシフトクリック・数字キー・切断返却安全性 ⑤ BetterModel 未導入環境での全敵/ボス/NPCスポーン＆フォールバック ⑥ `DemoWorld` v4→v5 移行の NPC/釣り場座標保護 ⑦ 既存 165+ テスト全件回帰 PASS |
| **実サーバー手動 QA（`MANUAL_QA.md` 追記対象）** | ① Minecraft 26.3 実クライアントでリソパ自動適用（ON）＆拒否（OFF）双方の GUI・HUD・`thorn_wolf`/`frost_wolf`/`ashwood_alpha` 見た目確認 ② GUI Scale 2/3/4 での背景ズレ・ActionBar折り返し確認 ③ Paper Dialog API の NPC 会話・職業選択・強化確認 ④ 同梱データパックバイオームの空/霧色変化確認 ⑤ BetterModel 導入時/未導入時の切替確認 ⑥ 実験 `same_port_netty` の直結動作確認 |
