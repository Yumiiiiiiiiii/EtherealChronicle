# EtherealChronicle UI・ワールド・独自Mob・リソースパック・開発支援 調査レポート

- **調査日**: 2026-11-22
- **対象プロジェクト環境**:
  - サーバーAPI: `Paper 26.3.build.257-stable`（`api-version: '26.3'`）
  - 言語 / ランタイム: `Kotlin 2.4.21` / `Java 25`（`jvmToolchain(25)`）
  - 現行構成: `plugin.yml`（`paper-plugin.yml` 未移行）、`SnakeYAML 2.2`、`MockBukkit mockbukkit-v26.3:4.123.0`、`JUnit 5.13.4`
  - 関連実装: `AdvancementDatapack.kt`（`<level>/datapacks/` 書き出し）、`DemoWorld.kt`（`ec_world` プロシージャル生成＋v1→v3移行）、`MenuHolders.kt`、`EnemyController.kt`、`RpgPlugin.kt`
- **調査ポリシー**: 本ドキュメントは**調査・技術選定のみ**を目的とする。依存追加・コード変更・外部バイナリ取得は一切行っていない。外部リポジトリ・MCP・プラグインはすべて **untrusted** として扱い、導入前にライセンス・コード・権限境界の監査を必須とする。

---

## エグゼクティブサマリー（結論先出し）

| 領域 | 最優先推奨（Adopt） | 条件付き・段階採用（Defer / Conditional） | 見送り・非推奨（Reject） |
|---|---|---|---|
| **UI / メニュー** | Paper標準 **Dialog API** + **Adventure / MiniMessage** + 自作最小 `space` フォントプロバイダ + **InvUI v2**（`invui-kotlin`） | **Triumph GUI**（`triumph-gui-paper`、軽量代替） | **IF**（NMS内部依存）、**NegativeSpaceFont** そのまま同梱（CC-BY表記管理より自作 `space` 定義が簡潔） |
| **独自Mob / 同梱DP** | **BetterModel**（MIT、Paper 26.3対応）＋ **Paper `DatapackRegistrar`**（`paper-plugin.yml` 移行）＋ バニラ `variant` / `item_model` | **Animated Java**（固定ギミック演出のみ） | **FreeMinecraftModels**（GPL-3.0汚染リスク）、バニラDP単体での3Dジオメトリ自作（**仕様上不可**）、OptiFine/EMF前提（クライアントMOD必須） |
| **ワールド作り込み** | **Paper `StructureManager`（`.nbt`）** ＋ 現行 `DemoWorld.kt` の**ハイブリッド方式**、26.1+ cutout対応ブロックステート再利用 | **FAWE / WorldEdit**（建築制作サーバー専用、プラグインへはシェード不可） | **Terra**（Paper 26.3公式未対応・座標管理不整合）、**バニラコアシェーダ依存**（26.1〜26.3で連続破壊＋Sodium非互換） |
| **リソースパック配布** | **本番**: 外部静的ホスト/CDN（Cloudflare R2 等）＋ CI **PackSquash**<br>**開発**: JDK内蔵 `HttpServer`（別ポート）＋ Paper **Configuration Phase** 配布 | **同一ポートHTTP（Netty注入）**: 「1ポート制約環境」専用の隔離オプトイン機能としてのみ | `mcbrawls/inject` 直接依存（**アーカイブ済**）、`Apisium/Uniporter`（2022年停止）、`Player#setResourcePack`（非推奨） |
| **MCP / スキル** | `Jahrome907/minecraft-agent-skills`（MIT、26.3対応、内容精査後）＋ `ValentinTarnovsky/MCP-MCP`（ドキュメント検索） | `sosadly/blockbench-mcp`（MIT、隔離環境でのモデル試作）、`mcdev-mcp`（検索専用・Groovy実行無効化時のみ） | ライブサーバーRCE系MCPの本番・非隔離利用（`minecraft-mcp` / `MinecraftDeveloperMCP` 等） |
| **その他ライブラリ** | **Paper標準 Brigadier**、**MockBukkit (`v26.3`)** 継続、現行 **SnakeYAML + 型検証** 継続 | **Incendo/cloud**（コマンド肥大化時）、**PacketEvents**（外部プラグイン `compileOnly` 依存のみ） | **ProtocolLib**（26.3安定版なし）、**PacketEvents のシェード**（GPL-3.0違反/汚染）、**Configurate** 今すぐ全面移行 |

---

## 0. プラットフォーム前提（Minecraft 26.1–26.3 / Paper 26.3 / Java 25）

2026年に Mojang は年号ベースのバージョニングへ移行し、`26.1`（Tiny Takeover）→ `26.2`（Chaos Cubed）→ `26.3`（Wilderness Bound）と短期間で大きな基盤変更が入った。調査対象ライブラリの「Paper 26.3 / Java 25 対応可否」を判定するうえで、以下4点が決定的な前提となる。

1. **Java 25 必須化**: Minecraft / Paper 26.1+ は Java 25 を要求する。古いバイトコード操作・リフレクション依存ライブラリ（特に 2024 年以前で停止したプロジェクト）は動作しない可能性が高い。
2. **Unobfuscated Server JAR（難読化解除）**: Minecraft 26.1 以降、Mojang は難読化なしのサーバー JAR を配布し、Paper は内部プラグインリマッパーを廃止した。Spigot マッピング前提や旧 NMS リフレクション前提のライブラリはそのままでは動かず、26.1+ 明示対応版が必要になる。
3. **Pack Format の更新**:
   - **Data Pack**: `101.1`（26.1）→ `107.1`（26.2）→ **`121.0`（26.3）**（現行 `AdvancementDatapack.kt` も `[121, 0]` を使用中）
   - **Resource Pack**: `84.0`（26.1）→ `88.0`（26.2）→ **`97.1`（26.3）**（`pack.mcmeta` で `min_format` / `max_format` 指定が必須）
4. **プラグイン定義（`plugin.yml` vs `paper-plugin.yml`）**: Paper の `DatapackRegistrar` や `PluginBootstrap`、`RegistryEvents` によるデータパック同梱・レジストリ拡張は **`paper-plugin.yml`** を必要とする。現行 `EtherealChronicle` は `plugin.yml` のみのため、同梱データパック機能を採用する段階で `paper-plugin.yml` への移行（または段階切替）が前提となる。

---

## 1. GUI / メニューライブラリ & UI 表現

### 1.1 ライブラリ・ネイティブ機能比較表

| 候補 | 出典 URL | ライセンス | 最新版 / 最終更新 | Paper 26.3 / Java 25 対応 | メンテ状況 | 採否推奨 |
|---|---|---|---|---|---|---|
| **Paper Dialog API**（標準内蔵） | https://docs.papermc.io/paper/dev/dialogs/ | Paper API (MIT/GPL混在サーバーAPI、プラグイン利用は制限なし) | Paper 1.21.7+ / 26.3 標準搭載 | **完全対応（標準）** | 活発（Paper公式） | **最優先採用** |
| **Adventure / MiniMessage**（標準内蔵） | https://docs.advntr.dev/minimessage/ | MIT | Paper 26.3 に標準バンドル | **完全対応（標準）** | 活発（Kyori公式） | **最優先採用** |
| **InvUI v2** (`invui` + `invui-kotlin`) | https://github.com/NichtStudioCode/InvUI | MIT | **v2.5.1**（2026-09-26） | **明示対応**（v2.4.0〜2.5.x で Paper 26.3 / 実験的 Folia 対応） | 非常に活発 | **採用最有力**（複雑なRPG GUI向け） |
| **Triumph GUI** (`triumph-gui-paper`) | https://github.com/TriumphTeam/triumph-gui | MIT | Stable **3.1.13** / `4.0.0-SNAPSHOT` 開発中 | **対応**（Paper専用アーティファクトで標準Adventure直接利用、NMS非依存） | 活発（v4リライト中） | **条件付き採用**（軽量・バージョン追従重視時） |
| **DevNatan/inventory-framework** | https://github.com/DevNatan/inventory-framework | MIT | **v3.7.1**（2026） | **対応**（`inventory-framework-platform-paper`） | 活発 | **代替候補**（InvUI v2の方が26.3特化・Kotlin親和性高） |
| **IF (InventoryFramework)** | https://github.com/stefvanschie/IF | The Unlicense | **v0.12.2**（2026） | 対応（1.16.5〜26.x、ただし内部でNMS使用） | 活発 | **見送り**（NMS依存＋XML/Pane設計が現行Kotlin基盤と不整合） |
| **NegativeSpaceFont** | https://github.com/AmberWat/NegativeSpaceFont | **CC-BY-4.0** | **v5.2**（2023-03-17） | `pack.mcmeta` を `[97, 1]` へ更新すれば仕様互換 | 安定・更新停止気味 | **参考のみ（自作推奨）** |

### 1.2 詳細評価と EtherealChronicle での使い分け

1. **Paper Dialog API（`io.papermc.paper.dialog.Dialog`）— 最優先で活用**
   - 外部依存ゼロで、クライアントネイティブ描画のモーダル画面（通知・確認・複数ボタン選択・テキスト入力・スライダー・チェックボックス）を表示できる（`player.showDialog(...)` / `RegistryEvents.DIALOG`）。
   - チェストGUIで無理に作っていた「職業選択」「NPC会話の選択肢」「装備強化の最終確認」「設定トグル」「利用規約/チュートリアル」「リソースパック案内」を圧倒的に見やすく安全なネイティブUIへ置き換えられる。
   - **Configuration Phase（ワールド参加前）** でも表示可能なため、ログイン導線にも使える。
   - 現行 `MenuHolders.kt` 周辺のインベントリ操作バグ（シフトクリック・数字キー・ドラッグ等のアイテム増殖リスク）を、そもそもインベントリを開かない設計で根絶できる点も大きい。

2. **InvUI v2 vs Triumph GUI — アイテム操作を伴うRPGメニューの選定**
   - **InvUI v2**（Maven: `https://repo.xenondevs.xyz/releases`、`xyz.xenondevs.invui:invui` + `invui-kotlin`）は v2 で Paper 特化リライトされ、ページング・スクロール・タブ・検索・Anvil入力・Kotlin DSL を標準装備する。図鑑（`CodexController`）・ショップ・装備スロット・ギルド掲示板のような多画面RPGメニューの記述量が激減する。ただし v2 は MC マイナーバージョン結合が強いため、MC 26.4 等への更新時に InvUI のバージョン追従が必要になる点だけ把握しておく。
   - **Triumph GUI**（`dev.triumphteam:triumph-gui-paper`）は非NMSで軽量、Paper 同梱の Adventure をそのまま使うためシャドウ肥大がない。まずはシンプルに保つなら有力な代替。

3. **Adventure / MiniMessage ＋ ボスバー / HUD / カスタムフォント・ネガティブスペースUI**
   - **MiniMessage 統一**: 現行の文字列装飾を Paper 標準の `MiniMessage` に寄せることで、グラデーション・`<font:ec:hud>`・`<sprite>`・ホバー/クリックアクション・i18n を YAML から直接書けるようになる。
   - **カスタムHUD（アクションバー & ボスバー）**:
     - リソースパックの `assets/ec/font/hud.json` に `bitmap` プロバイダ（HPバー・MPバー・スタミナ・釣りゲージ・コンパス・状態異常アイコン）と `space` プロバイダ（負幅・正幅の精密移動、`advances` で `-1, -2, -4, -8, -16, -32, -64, -128` 等を定義）を用意する。
     - `AmberWat/NegativeSpaceFont` は **CC-BY-4.0** でクレジット表記義務が生じるが、MC 1.19+ 標準の `space` フォントプロバイダ JSON は 30 行程度で自前定義できるため、**自社リソースパック内に最小限の `space` フォントを自作する方が権利管理も軽量性も優れる**。
     - アクションバーは「HP/MP/スキルCDの常時HUD（1行精密配置）」、複数 BossBar は「エリア名・ボスHP＋ブレイクゲージ・釣りミニゲームのテンションバー（現行 `FishingUiFeedback.kt` の視覚化）」に割り当てると競合しにくい。
   - **カスタムメニュー背景（テクスチャ付きチェストGUI）**:
     - チェスト GUI のタイトル `Component` に「負のスペース文字 → カスタム背景テクスチャ用グリフ（例: 256x256 PNG を `ascent`/`height` 調整） → 負のスペースで文字位置を戻す → タイトル文字列」を並べる定石手法が MC 26.3 でも有効。
     - ボタン用スロットには `minecraft:item_model`（1.21.4+ 標準コンポーネント）で透過アイコンやカスタムボタンテクスチャを割り当て、枠外の不要スロットを透明アイテムで埋めると、バニラのチェスト枠を意識させないRPG専用ウィンドウが作れる。

---

## 2. 独自Mob表示 & プラグイン同梱データパック

### 2.1 手法・ライブラリ比較表

| 候補 / 手法 | 出典 URL | ライセンス | 最新版 / 最終更新 | Paper 26.3 / Java 25 対応 | メンテ状況 | 採否推奨 |
|---|---|---|---|---|---|---|
| **BetterModel** | https://github.com/toxicity188/BetterModel | **MIT** | **v3.7.0**（2026-11-19） | **完全対応**（Paper 1.21.4〜26.3 / Folia / Java 25） | 非常に活発 | **採用最有力**（独自3Dボス・エリートMobの本命） |
| **FreeMinecraftModels** | https://github.com/MagmaGuy/FreeMinecraftModels | **GPL-3.0**（※GitHubに独立`LICENSE`ファイルなし、README/Modrinth宣言） | GitHub pre `2.1.1-SNAPSHOT-1` (2025-05) / 外部Maven `2.3.17` (2026-11) | 対応（1.21.4+ `items/display` ベース） | 活発（外部配布中心） | **見送り**（GPL-3.0汚染リスク・BetterModel MITが上位互換） |
| **Animated Java** | https://github.com/Animated-Java/animated-java | カスタム独自ライセンス（一部周辺リポMIT） | 継続更新（1.21.4+ `item_model` 対応） | バニラデータパック＋RP生成のため互換 | 活発 | **補助採用**（固定ギミック・演出プロップ向け） |
| **Display Entity + `minecraft:item_model`（自作）** | https://docs.papermc.io/paper/dev/ | Paper標準API | Paper 26.3 標準 | **完全対応（標準）** | 公式 | **採用**（軽量な武器エフェクト・装飾・簡易Mob） |
| **バニラ データ駆動Mobバリアント** | https://minecraft.wiki/w/Data_pack | バニラ仕様 | MC 26.1〜26.3 で拡充 | **完全対応（標準）** | 公式 | **採用**（動物/既存骨格Mobのテクスチャ差し替え） |
| **Paper `DatapackRegistrar`** | https://docs.papermc.io/paper/dev/getting-started/paper-plugins/ | Paper標準API (`@Experimental`) | Paper 1.21.4〜26.3 標準 | **完全対応（要 `paper-plugin.yml`）** | 公式 | **採用**（静的データパック同梱） |

### 2.2 重要検証：「データパック同梱だけで独自Mobを作れるか？」の実現性境界

ユーザー要望の「テクスチャでmobの見た目を変える」「プラグイン内にデータパックを同梱して独自mobを作る」について、**Minecraft Java Edition 26.3 時点の仕様上の境界線**を明確にしておく。

| やりたいこと | バニラ Data Pack + Resource Pack のみ（純バニラクライアント） | プラグイン併用（Paper API / BetterModel） |
|---|---|---|
| 狼・猫・牛・豚・鶏・カエル・ゾンビオウムガイの**見た目・子ども姿・鳴き声の独自バリアント追加** | **可能**（`wolf_variant`, `cat_variant`, `cow_variant`, `pig_variant`, `chicken_variant`, `frog_variant`, `zombie_nautilus_variant` ＋ 26.1+ `baby_assets` / `*_sound_variant`） | **可能**（さらに Paper 26.3 の `RegistryEvents` でも登録・スポーン制御可能） |
| 既存人型Mob（ゾンビ・スケルトン・防具立て等）の**装備/頭モデルによる見た目変更** | **可能**（`minecraft:equipment` モデル＋カスタム `minecraft:item_model` を頭/手に装備） | **可能**（現行 `EnemyController.kt` から即適用でき負荷が極小） |
| **独自3Dジオメトリ（新規骨格・多関節・翼・巨大ドラゴン・独自モーション）** | **不可**（※バニラ JE 26.3 のバリアント `model` は `normal`/`cold`/`warm` 等のハードコードプリセット選択のみ。OptiFine CEM / Entity Model Features (EMF) は**クライアントMOD必須**のため純バニラ不可） | **可能**（**BetterModel** 等が Blockbench `.bbmodel` を `item_display` パーツ＋パケットアニメーションへ変換して純バニラクライアントに描画） |
| **独自AI・スキル詠唱・当たり判定・フェーズ遷移** | 限定的（ファンクション＋スコアボードで擬似実装は可能だが高負荷・保守困難） | **可能**（既存の `EnemyController` / `CombatEngine` で完全制御＋BetterModelのカスタムヒットボックス/ボーン連動） |

> **結論**: 「データパック単体で Bedrock Edition の Behavior Pack のような完全独自Mob（新エンティティID・新3Dモデル・新AI）を作る」ことは Java Edition 26.3 では依然として不可能。**Java Edition で純バニラクライアント対応の独自Mobを作る標準解は、以下の3階層使い分け**になる。

- **Tier 1（最軽量・雑魚Mob向け）**: データパックの **Mobバリアント**（狼・猫・農畜等）＋ **カスタム装備モデル（`equipment` + `item_model` 頭/武器/盾）**。リソースパックのテクスチャ差し替えだけでコストほぼゼロ。
- **Tier 2（中量・浮遊霊/結晶/ゴーレム亜種向け）**: 透明化したベースMob（移動・当たり判定・AI担当）＋ プラグイン制御の **`ItemDisplay` / `BlockDisplay`** 1〜3パーツ。外部ライブラリ不要。
- **Tier 3（リッチ・章ボス/中ボス/看板NPC向け）**: **BetterModel（MIT）**＋ **Blockbench（`.bbmodel`）**。
  - パケットベース `item_display` 描画のためサーバーエンティティ負荷が低く、Molang アニメーション・IK リギング・ヒットボックス追従・自動リソースパック（`item_model` JSON）生成まで揃う。
  - 開発者API（`io.github.toxicity188:bettermodel-api` / `bettermodel-bukkit-api`）が MIT で提供され、Paper 26.3 / Folia / Java 25 に明示対応（2026-11-19 時点 v3.7.0）。
  - **Animated Java** はコマンド関数ベースで大量の戦闘Mobを動かすとティック負荷が跳ね上がるため、ダンジョンの動く扉・封印クリスタル等の**少数固定ギミック限定**の補助ツールとするのが妥当。

### 2.3 プラグインJAR内へのデータパック同梱（`DatapackRegistrar`）と現行 `AdvancementDatapack.kt` の関係

- Paper 1.21.4〜26.3 では、`paper-plugin.yml` に `bootstrapper`（`PluginBootstrap` 実装）を宣言し、`LifecycleEvents.DATAPACK_DISCOVERY` のハンドラ内で `event.registrar().discoverPack(uri, "provided")` を呼ぶことで、**プラグインJAR内のリソースフォルダをデータパックとして自動登録**できる（`@Experimental` 注釈付きだが Paper 公式推奨手段）。
- **現行 `AdvancementDatapack.kt` との整理**:
  - 現行実装は起動時に `<level>/datapacks/etherealchronicle_advancements` へ YAML から進捗 JSON を書き出している（初回反映や更新時にワールド再読込/リロード配慮が必要）。
  - 静的データ（Mob バリアント、カスタムバイオーム、構造体 `.nbt`、Dialog 定義、ダメージタイプ等）は **`paper-plugin.yml` + `DatapackRegistrar`** で JAR 内からクリーンに同梱する。
  - コンテンツ YAML から動的に生成する進捗（`AdvancementDatapack.kt`）も、起動前 Bootstrap 段階で一時ディレクトリに生成して `discoverPack(Path, ...)` に渡す構成へ寄せれば、ワールドフォルダを汚さず一元管理できる（ただしテスト時 MockBukkit が Bootstrapper をどう扱うかは事前検証が必要）。

---

## 3. ワールド作り込み（`ec_world` の島・村・ダンジョン・環境演出）

### 3.1 ツール・API比較表

| 候補 / 手法 | 出典 URL | ライセンス | 最新版 / 最終更新 | Paper 26.3 / Java 25 対応 | メンテ状況 | 採否推奨 |
|---|---|---|---|---|---|---|
| **Paper `StructureManager` API**（`.nbt`） | https://docs.papermc.io/paper/dev/ | Paper標準API | Paper 26.3 標準（`BlockTransformer` / `EntityTransformer` 対応） | **完全対応（標準）** | 活発（Paper公式） | **最優先採用**（ランタイム配置の主軸） |
| **FastAsyncWorldEdit (FAWE)** | https://github.com/IntellectualSites/FastAsyncWorldEdit | **GPL-3.0-only** | **v2.16.2**（2026-11） | **完全対応**（Paper 1.21.4〜26.3 専用JARあり） | 非常に活発 | **制作ツール / 外部連携のみ**（シェード禁止、`compileOnly` 任意連携） |
| **WorldEdit** | https://github.com/EngineHub/WorldEdit | **GPL-3.0-only** | **v7.4.6**（2026） | **完全対応**（Paper 1.21.4〜26.3 / JDK 25+） | 非常に活発 | **制作ツール**（大規模非同期配置が要るならFAWE優先） |
| **Terra** | https://github.com/PolyhedralDev/Terra | Core **MIT** / Bukkit-Paper JAR **GPLv3**（公式パック CC-BY-4.0） | **6.6.6-BETA**（MC 1.21.8 まで） | **未確認 / 公式26.x未対応** | 活発だが26.x追従待ち | **見送り**（26.3未対応＋RPG座標制御と不整合） |

### 3.2 `ec_world` 作り込み手法の比較（Procedural vs Schematic/NBT vs ハイブリッド）

現行 `DemoWorld.kt` は `DemoGenerator : ChunkGenerator()` によるプロシージャル地形生成＋バージョン管理された段階マイグレーション（v1→v2→v3）で、スポーン位置・NPC座標・クエスト範囲・ダンジョン入口の整合性を保っている。ここを作り込む場合の3案を比較する。

| 方式 | メリット | デメリット | EtherealChronicle への適性 |
|---|---|---|---|
| **完全プロシージャル（現行拡張）** | 座標不変・Git差分が見やすい・外部ファイル不要 | 建築ディテール（屋根の装飾・内装・市場・遺跡の崩れ表現）をコードで組むコストが膨大 | 地形骨格・道・植生・橋には最適だが建築には限界 |
| **ワールド丸ごと配布 / 大規模 `.schem` 上書き** | 建築クオリティを最大限に引き上げやすい | ワールド更新時に既存プレイヤーの建築/探索状態を壊しやすい。Git肥大・テスト再現性低下 | 本番長期運営・継続アップデートと相性が悪い |
| **ハイブリッド方式（推奨）** | **地形骨格・座標規約は `DemoWorld.kt` が管理**し、村の家屋・ギルド酒場・遺跡・ダンジョン部屋・ボスマップは建築サーバー（FAWE/WorldEdit＋ストラクチャーブロック）で制作して **`.nbt` 構造体としてJAR同梱**。`DemoWorld` のバージョン移行（v4, v5...）で **`Paper StructureManager` 経由で配置**し、`EntityTransformer`/`BlockTransformer` で風化・季節・NPCアンカーを注入 | 構造体とコード座標の境界契約（設置原点・回転・バウンディングボックス）をドキュメント管理する必要がある | **最適解**（外部依存ゼロ・Gitで `.nbt` バージョン管理可能・ダンジョンインスタンス再生成にもそのまま転用可能） |

> **実務ポイント**: 建築制作はローカルのクリエイティブ建築サーバーに **FAWE** を入れて行い、完成した区画をストラクチャーブロック（または FAWE の構造体エクスポート）でバニラ `.nbt` として出力し、プラグインの `src/main/resources/structures/` に格納する。これにより**本番サーバーに FAWE（GPL-3.0）を必須依存させずに高品質な建築を自動展開できる**。

### 3.3 カスタムブロック表現（家具・鉱石・採取オブジェクト・街装飾）

| 表現方式 | 仕組み | 強み | 注意点・限界 | 推奨用途 |
|---|---|---|---|---|
| **再利用ブロックステート**（`note_block` / `mushroom_block` / `tripwire`） | 未使用ブロックステートに独自 `blockstates` モデルを割り当て。Paper `paper-global.yml` で `disable-note-block-updates` / `disable-tripwire-updates` / `disable-mushroom-block-updates` を有効化してサーバー側の状態変化を抑制 | **チャンクメッシュに統合されクライアントFPS負荷が極小**。自然な光影（Ambient Occlusion）・当たり判定・大量設置に強い。さらに **MC 26.1+ で全ブロックモデルがテクスチャのアルファ値から自動で cutout / translucent を判別**するようになり、透過ブロック表現の制約が大幅に緩和された | 設置・破壊・右クリック（音符ブロック再生等）・ピストン・アイテムドロップの制御をプラグイン側で処理する必要あり（既存の `GatheringController` と好相性） | **採取鉱石・水晶・薬草ブロック・街の舗装石・看板柱**など大量配置するもの |
| **`ItemDisplay`（+ `BARRIER` / `Interaction`）** | カスタム `minecraft:item_model` を持つ `ItemDisplay` を配置し、当たり判定に `BARRIER` や `Interaction` を併用 | **1ブロック枠を超えるサイズ・任意回転・スケール・アニメーション**が可能。ブロックステート枠を消費しない | エンティティ描画のため**1エリアに数百個並べるとクライアントGPU負荷が急増**。光影がブロックほど自然でない | **酒場のシャンデリア・看板・宝箱（開閉アニメ）・祭壇・NPC横の小物**など低密度の見せ場 |

### 3.4 環境演出（パーティクル・音・バイオーム・シェーダ）

- **推奨（安全・高コスパ・長寿命）**:
  - **データパック同梱のカスタムバイオーム**: バイオーム定義で**空色・霧色・水色・草木色・環境パーティクル（舞う胞子/灰/雪）・環境音**をエリア単位で変えられる。第1章ルミナ周辺（澄んだ蒼）、遺跡/ダンジョン（紫霧）、第2章エリアの差別化に極めて効果的。
  - **Display Entity + パーティクル + カスタムサウンド**: 浮遊ルーン（`TextDisplay` / `ItemDisplay` の緩やかな回転）、滝しぶき・松明火の粉（プレイヤー近傍のみスポーン制限付きパーティクル）、リソースパックのカスタムBGM/環境SE（`sounds.json`）。
- **非推奨（バニラ コアシェーダ依存）**:
  - Minecraft 26.1〜26.3 では**マイナー更新のたびにコアシェーダの破壊的変更**が発生している（26.1: `core/item` 分離・`terrain.vsh`/`block.vsh` から `Normal` 属性削除、26.2: `core/text` 統合、26.3: Order-Independent Transparency (OIT) 導入による `post_effect/transparency.json` 削除）。
  - さらにプレイヤー利用率の高い軽量化MOD **Sodium はバニラコアシェーダをバイパス**するため、コアシェーダ前提のUIや視覚効果は多くの環境で壊れる。**コアシェーダには依存しない設計を徹底すべき**。

---

## 4. リソースパック配布（Minecraft同一ポートHTTP配信 vs 代替手法）

### 4.1 同一ポートHTTP配信の仕組みと既存プロジェクト調査

Minecraft サーバーの TCP ポート（既定 `25565`）に着信したコネクションの Netty パイプライン先頭にカスタム `ByteToMessageDecoder` を挿入し、**先頭数バイトを覗き見て（peek）** HTTP リクエスト（`GET ` / `HEAD ` 等）なら Netty の HTTP コーデックへ分岐、Minecraft の VarInt ハンドシェイクパケットなら既存パイプラインへ素通しする多重化（Protocol Multiplexing）技術である。

| プロジェクト / 手法 | 出典 URL | ライセンス | 最新版 / 最終更新 | Paper 26.3 / Java 25 対応 | メンテ状況 | 採否推奨 |
|---|---|---|---|---|---|---|
| **mcbrawls/inject** | https://github.com/mcbrawls/inject | MIT | v3.2.0（2026-06-17、Paper 26.2 まで） | 26.3未検証・**2026-09-12 アーカイブ済** | **終了（Archived）** | **参考実装のみ**（新規依存不可） |
| **Apisium/Uniporter** | https://github.com/Apisium/Uniporter | MIT | v1.3.4（2022-07-05、最終コミット 2022-09-26） | **非対応**（MC 1.19止まり） | **放置（4年停止）** | **不採用** |
| **MrNavaStar/ProtoWeaver** | https://github.com/MrNavaStar/ProtoWeaver | CC0-1.0 | v1.5.1（2026-04-01） | 26.1.1 まで（スタンドアロン、汎用HTTP API未完成） | 緩やか | **不採用**（プロキシ間通信用途が主） |
| **Strikeless/MCBootstrapHook** | https://github.com/Strikeless/MCBootstrapHook | GitHub参照 | Netty Bootstrap フック参考実装 | 26.3未保証 | 参考リポ | **参考実装のみ** |
| **Paper 内部 `ChannelInitializeListenerHolder`** | Paper 内部クラス (`io.papermc.paper.network.ChannelInitializeListenerHolder`) | Paper 内部API | Paper 26.3 に存在（**非公開API**） | 動作可能性あり（要検証） | 内部API（互換保証なし） | **限定オプトインのみ検討** |

### 4.2 Paper 26.3 での同一ポートHTTP配信の実現性・リスク評価

1. **NMS / 内部API・マッピングリスク**:
   - `ChannelInitializeListenerHolder#addListener` は `paper-api` に公開されていない Paper サーバー内部クラスである。利用には `paperweight-userdev` またはリフレクションが必要。
   - MC 26.1+ の Unobfuscated JAR 化により旧来の難読化マッピング破綻リスクは減ったものの、**Paper の内部リファクタで予告なく破壊されるリスク**は残る。また MockBukkit ではテストできないため専用結合テストが必要。
2. **プロキシ・ネットワーク環境での破綻リスク（最重要）**:
   - **Velocity / BungeeCord / TCPShield / Cloudflare Spectrum / 一部DDoS防御プロキシ** の配下では、ポート 25565 に来た非 Minecraft パケット（HTTP）はプロキシ層で破棄・エラー切断される。同一ポート配信が成立するのは「Minecraft サーバーへ直接 TCP 接続する構成」または「プロキシ自体に多重化プラグインを入れる構成」に限られる。
3. **セキュリティ・性能リスク**:
   - **HTTPS（TLS）非対応**: 25565 上で平文 HTTP を返す構成になる（TLS まで多重化すると証明書管理・SNI 処理が複雑化しバグ温床になる）。クライアントは SHA-1 検証を行うため改ざん検出は可能だが、盗聴や企業/ISPプロキシでのブロック余地は残る。
   - **EventLoop 帯域圧迫・DoS リスク**: ゲーム通信と同じ Netty EventLoop / 同一 NIC で数十MBのZIPを同時配信すると、ログインラッシュ時にゲームパケットの遅延（ラグ・切断）や帯域枯渇を引き起こす。実装する場合は「**固定1パス（例: `/pack/<sha1>.zip`）のみ許可（パストラバーサル完全遮断）、`GET`/`HEAD` 限定、レスポンスボディを別スレッド/ChunkedWriteでストリーミング、同時接続数・帯域・リクエストサイズ上限（例: 4KBヘッダ上限）とタイムアウトを厳格設定**」が必須条件となる。

### 4.3 リソースパック配信3方式の比較

| 観点 | ① 外部静的ホスト / CDN（Cloudflare R2・GitHub Releases・S3） | ② 別ポート内蔵 HTTP（JDK `com.sun.net.httpserver.HttpServer` / `creative-server`） | ③ Minecraft 同一ポート HTTP（Netty パイプライン注入） |
|---|---|---|---|
| **安定性・保守性** | **最高**（Paper更新の影響ゼロ） | **高**（JDK標準クラスのみ、NMS/内部APIゼロ、実装数十行） | **低〜中**（Paper内部API依存、`mcbrawls/inject` もアーカイブ済） |
| **Velocity / DDoS防御互換** | **完全対応** | ポート開放可能なら対応 | **非対応（プロキシで破綻）** |
| **HTTPS 対応** | **標準対応** | リバースプロキシ（Caddy/Nginx）併用で容易 | 困難（実質 HTTP のみ） |
| **サーバー負荷・DoS耐性** | **ゲームサーバー負荷ゼロ** | 別スレッドだが同一回線帯域を消費 | ゲームNettyと帯域/ソケットを共有（要厳格レート制限） |
| **ポート要件** | 開放不要 | ゲームポート＋HTTPポート（例 `8080`） | **25565 のみ（1ポート制約環境で唯一の解）** |
| **推奨用途** | **本番運用の第一推奨** | **ローカル開発・テスト・ポート2つ開けられる小規模サーバー** | **「1ポートしか開けられないレンタルサーバー/NAT環境」限定のオプトイン機能** |

> **設計提案**: プラグインのリソースパック配信モジュールは抽象化し、**設定で `external_url`（本番推奨）/ `embedded_http_port`（開発推奨）/ `same_port_netty`（1ポート制約環境向け実験オプション）を切り替えられる構成**にしておくと、ユーザーの要望を満たしつつ本番の安全性とプロキシ対応も両立できる。

### 4.4 ハッシュ（SHA-1）・強制適用・Paper 26.3 配布API

- **SHA-1 ハッシュの重要性**: クライアントは `UUID` + `SHA-1`（40桁16進）でキャッシュと整合性を判定する。URL 末尾にもハッシュ（例 `ec-pack-<sha1prefix>.zip`）を含めると CDN キャッシュ不整合も防げる。
- **Paper 26.3 の推奨配布 API（Configuration Phase）**:
  - **`Player#setResourcePack` は非推奨（`@Deprecated`）**。複数パック積載に対応した `Player#addResourcePack` もあるが、Paper 26.3 での本命は **Adventure `ResourcePackInfo` + `ResourcePackRequest`** を **`AsyncPlayerConnectionConfigureEvent` / `PlayerConnectionInitialConfigureEvent`（Configuration Phase）** で送信する方式。
  - Configuration Phase で送ると、**プレイヤーがワールドへスポーンする前**にロード画面でリソースパック適用が完了するため、「読み込み前にバニラテクスチャが一瞬見える」「ロード中にMobに殴られる」事故を防げる。
  - `ResourcePackRequest.resourcePackRequest().packs(info).required(true).prompt(Component).replace(false).callback(...)` で強制適用・拒否時のキック案内・複数パック共存（例: サーバー基本パック＋BetterModel生成パック）を綺麗に制御できる。
  - **`server.properties`（`resource-pack`, `resource-pack-sha1`, `require-resource-pack`）との違い**: 静的1パックだけなら最も簡単だが、プラグインによるパック自動更新・多パック合成・言語別プロンプト・拒否時フォールバック制御ができない。開発初期の検証には便利だが、本実装は Configuration Phase API が優位。

### 4.5 リソースパック生成・圧縮・運用ツール比較

| ツール | 出典 URL | ライセンス | 最新版 / 最終更新 | 用途・特徴 | 採否推奨 |
|---|---|---|---|---|---|
| **PackSquash** | https://github.com/ComunidadAylas/PackSquash | **AGPL-3.0**（GitHub Action `PackSquash-action` は **MIT**、生成ZIPにAGPLは及ばない） | 継続更新（Rust製CLI） | PNG/JSON/OGGをロスレス最適化＋ZIP圧縮率最大化。配布サイズを30〜60%削減 | **CIビルドツールとして採用推奨**（サーバーJARには絶対同梱しない） |
| **unnamed/creative** | https://github.com/unnamed/creative | **MIT** | Maven Central `1.7.3` (2024-07) / GitHub 2026-11 まで活発（1.12〜1.14系は外部リポ） | JavaコードからリソースパックJSON/フォント/モデルをプログラマティック生成＋内蔵サーバー `creative-server` | **条件付き採用**（自動生成パイプライン構築時に最新供給元と26.3フォーマット対応を確認） |
| **ForcePack** | https://github.com/SamB440/ForcePack | **GPL-3.0-only** | v1.3.74 (2026-01) / Modrinth dev 2026-11 | Velocity / Paper 向け強制配布・複数サーバー管理プラグイン | **運用プラグイン候補**（マルチサーバー化時のみ検討、コード依存不要） |
| **ResourcePackManager** | https://github.com/MagmaGuy/ResourcePackManager | LGPL-3.0（Modrinth表記、GitHubに `LICENSE` なし） | 継続更新 | 複数プラグインのリソースパック自動マージ | **様子見**（BetterModel単体＋自前パック程度ならCI統合の方が透明で安全） |

---

## 5. Paper / Minecraft 開発向け MCP サーバー & Claude / Codex スキル

### 5.1 候補一覧と評価

| 種別 | プロジェクト | 出典 URL | ライセンス | 最終更新 / 状態 | 機能概要 | セキュリティ / 権限リスク | 採否推奨 |
|---|---|---|---|---|---|---|---|
| **Skill** | **minecraft-agent-skills** | https://github.com/Jahrome907/minecraft-agent-skills | **MIT** | **v2.5.4**（2026-10-30） | Claude Code / Codex 両対応。**MC 26.3 / Java 25**、Paperプラグイン・データパック・リソースパック指針を網羅 | **低**（テキスト指示のみ。ただしプロンプト注入や既存規約との矛盾を事前精査要） | **採用検討**（必要スキルだけ抜粋監査して導入） |
| **Skill** | **papermc-skill** | https://github.com/ardianryan/papermc-skill | GitHub参照 | 2025〜2026（Java 21前提） | Paper / Folia 開発ガイド | **低**（ただし Java 21 / 旧API記述が残るため 26.3 では誤誘導注意） | **参考のみ** |
| **Skill** | **awesome-copilot (`minecraft-plugin-development`)** | https://github.com/github/awesome-copilot | MIT | 継続更新 | Paper / Spigot 一般規約 | **低**（汎用的で26.3特化ではない） | **参考のみ** |
| **Docs MCP** | **MCP-MCP** | https://github.com/ValentinTarnovsky/MCP-MCP | **MIT** | 2026-07 時点活発 | Paper / PacketEvents / WorldEdit 等の Javadoc・Maven バージョン検索 | **低〜中**（外部ドキュメント取得＋読み取り中心。通信先ドメイン確認推奨） | **採用候補**（API調査の誤認防止に有効） |
| **Decompile MCP** | **mcdev-mcp** | https://github.com/use-ai-for-mc/mcdev-mcp | GitHub参照（要確認） | 2026（MC 26.x 対応） | Vineflower 逆コンパイル＋シンボル検索（`mc_search`）＋ライブ Groovy 実行（`mc_execute`） | **中〜高**（`mc_execute` は**任意コード実行＝RCE**。検索専用構成かつEULA/逆コンパイル規約遵守が前提） | **条件付き検討**（`mc_execute` 完全無効化が絶対条件） |
| **Live MCP** | **InventivetalentDev/minecraft-mcp** | https://github.com/InventivetalentDev/minecraft-mcp | **MIT** | 2025〜2026 | WebSocket 経由で操作・スクショ・**Javaリフレクション呼び出し (`reflect_invoke`)** | **極めて高**（任意メソッド実行＝ホスト権限RCE。ゲーム内チャット経由のプロンプトインジェクション危険） | **本番厳禁**（ローカル隔離テスト環境でも慎重扱い） |
| **Live MCP** | **MinecraftDeveloperMCP** | https://github.com/center2055/MinecraftDeveloperMCP | GitHub参照 | 2025〜2026 | コンソール `execute_command`、ファイルR/W、**base64 JARアップロード** | **極めて高**（任意JAR配置＋コンソール実行＝完全サーバー乗っ取り可能） | **不採用**（既存の Gradle + ローカルQA手順の方が安全） |
| **Live MCP** | **PaperMCP** | https://github.com/BadgersMC/PaperMCP | GitHub参照 | 2025〜2026 | Kotlin / Javalin WebSocket コンソールブリッジ | **高**（認証・バインド不備でRCE） | **不採用** |
| **Debug MCP** | **qore-games/foreman** | https://github.com/qore-games/foreman | GitHub参照 | 2026 | Fabric クライアント＋Paper プラグイン連携デバッガ＋MCP | **高**（クライアント＋サーバー双方の自動操作権限） | **様子見**（手動QA自動化が本当にボトルネックになった時のみ隔離検証） |
| **Blockbench MCP** | **sosadly/blockbench-mcp** / **enfp-dev-studio/blockbench-mcp** | **MIT** | 2026 | AI から Blockbench 上でボーン・キューブ・UV・アニメーションを操作（86ツール等） | **中**（Blockbench公式プラグインストアはAIプラグインを禁止しており**非公式サイドロード必須**。コード監査前提） | **隔離環境で試作検証可**（MIT版優先） |
| **Blockbench MCP** | **jasonjgardner/blockbench-mcp-plugin** | **GPL-3.0** | v1.10.0（2026-10-01、~629 stars） | 同上（最も知名度が高い） | **中**（同上。制作ツールとして使う限り出力 `.bbmodel` にGPLは波及しないがコード監査必須） | **隔離環境で試作検証可** |

### 5.2 MCP / スキル導入時の安全運用ルール

1. **原則**: 本プロジェクトはすでに `MockBukkit (v26.3)`＋ 165本超の自動テスト＋ `MANUAL_QA.md` 体制を持つため、**危険なライブサーバーRCE系 MCP を無理に導入するメリットは薄い**。
2. **導入するなら階層を分ける**:
   - **安全層**: スキル（Markdown テキストを人間が全行レビューしてから取り込む）＋ 読み取り専用ドキュメント検索 MCP（`MCP-MCP`）。
   - **隔離層（必要な場合のみ）**: Blockbench MCP はローカル制作端末の専用プロファイルでコード監査後に利用。ライブ実行系 MCP を使う場合は必ず `127.0.0.1` バインド、認証トークン必須、本番ワールド・秘密情報のない使い捨てサンドボックスに限定する。
3. **プロンプトインジェクション対策**: チャットログ・看板・アイテム名など**プレイヤー入力を読み取る MCP ツールを自動コマンド実行ツールと同時にエージェントへ渡さない**。

---

## 6. そのほか有用なライブラリ

### 6.1 比較表

| カテゴリ | 候補 | 出典 URL | ライセンス | 最新版 / 最終更新 | Paper 26.3 / Java 25 対応 | メンテ状況 | 採否推奨 |
|---|---|---|---|---|---|---|---|
| **コマンドAPI** | **Paper Brigadier**（標準内蔵 `io.papermc.paper.command.brigadier`） | https://docs.papermc.io/paper/dev/command-api/basics/introduction/ | Paper標準API | Paper 26.3 標準 | **完全対応（標準）** | 活発（Paper公式） | **最優先採用**（まず既存 `/ec` の移行先） |
| **コマンドAPI** | **Incendo/cloud** (`cloud-paper`) | https://github.com/Incendo/cloud / `cloud-minecraft` | **MIT** | Core **2.1.0** (2026-07-20) / `cloud-paper` **2.0.1** (2026-09-18) | **明示対応**（`cloud-paper 2.0.1` で Paper 26.3 修正済） | 非常に活発 | **将来採用候補**（コマンド群がさらに肥大化した場合） |
| **コマンドAPI** | **Revxrsal/Lamp** | https://github.com/Revxrsal/Lamp | **MIT** | **v4.0.1**（2026-11-01） | **確認済**（Paper 26.1〜26.3） | 活発 | **代替候補**（アノテーション記述派向け） |
| **コマンドAPI** | **CommandAPI** | https://github.com/CommandAPI/CommandAPI | **MIT** | **v12.1.0**（2026-09-26） | **明示対応**（Paper 1.20.6〜26.3） | 活発 | **代替候補**（Paper専業なら標準Brigadier/Cloudの方が軽量） |
| **パケット** | **PacketEvents** | https://github.com/retrooper/packetevents | **GPL-3.0** | **v2.14.0**（2026-09-23） | **明示対応**（MC / Paper 26.3） | 非常に活発 | **条件付き採用**（必要時のみ**外部プラグイン `compileOnly` 依存**。シェード禁止） |
| **パケット** | **ProtocolLib** | https://github.com/dmulloy2/ProtocolLib | **GPL-2.0** | Stable 5.4.0 (2025-08, 1.21.8止まり) / 26.3は `5.5.0-SNAPSHOT` のみ | 安定版未対応（devビルド頼み） | 追従遅延気味 | **非推奨**（新規なら PacketEvents 一択） |
| **設定** | **Configurate** | https://github.com/SpongePowered/Configurate | **Apache-2.0** | **v4.2.0**（2025-02-25 / `4.3.0-SNAPSHOT`） | プラットフォーム非依存（Java 25 動作） | 安定 | **保留**（現行 SnakeYAML＋型検証で十分機能中） |
| **テスト** | **MockBukkit** | https://github.com/MockBukkit/MockBukkit | **MIT** | `mockbukkit-v26.3`（2026-11-19 まで継続更新、現行 `4.123.0`） | **完全対応** | 非常に活発 | **継続採用**（既存QA基盤の中核） |

### 6.2 補足所見

- **コマンドAPI**: 現行 `/ec` 系コマンドはまず**外部依存ゼロの Paper 標準 Brigadier** へ移行するだけで、クライアント側リアルタイム構文チェック・型付き引数補完・権限による候補非表示が手に入る。将来的に管理/デバッグ/ギルド/取引など数十系統へ増えたら Kotlin DSL と相性のよい **Incendo/cloud (`cloud-paper`)** へ乗せる判断が合理的。
- **パケット操作**: Paper 26.3 標準API（Dialog / Display Entity / Adventure / Configuration Phase）と BetterModel を使えば、**自前プラグインで直接パケットを触る必要はほぼ無い**。もし「プレイヤー個別に看板・採取ノード・NPC状態だけを切り替えたい（Per-Player Entity/Block 演出）」要件が出た時のみ、**PacketEvents をサーバー側外部プラグインとして導入し、`compileOnly` + `softdepend` で参照**する（シェード禁止）。
- **Configurate**: 現行の `SnakeYAML 2.2`＋厳格スキーマ検証テストがすでに安定稼働しているため、移行コストに見合うリターンが薄い。コメント付き管理設定ファイルを自動生成したくなった段階でのみ検討すれば十分。

---

## 7. 結び

### 7.1 推奨スタックと導入順（段階ロードマップ）

一度に全部入れず、**依存ゼロの Paper 26.3 標準機能 → リソースパック基盤 → UI・ワールド → 独自3D Mob** の順で段階導入するのが最もリスクが低い。

| Phase | 導入対象 | 目的・得られる効果 | 依存・前提 |
|---|---|---|---|
| **Phase 0: 基盤整備** | `paper-plugin.yml` 移行（`PluginBootstrap` 対応）＋ **Paper 標準 Brigadier** ＋ **MiniMessage** 統一 | 同梱データパック・Dialog・レジストリ拡張の土台を作る。外部依存増ゼロ | 現行コード改修のみ（MockBukkit互換を事前確認） |
| **Phase 1: ネイティブUI刷新** | **Paper Dialog API** ＋ **InvUI v2 (`invui-kotlin`)** | NPC会話・確認画面・職業選択をネイティブモーダル化、ショップ/図鑑/掲示板を安全で保守しやすいGUIへ刷新 | `invui` / `invui-kotlin`（MIT） |
| **Phase 2: リソースパック基盤 & カスタムHUD** | 自前リソースパック（format `97.1`、最小 `space` フォント＋HUD/GUI背景＋`item_model`）＋ **Configuration Phase 配布** ＋ CI **PackSquash**<br>配信: 本番=**Cloudflare R2 等CDN** / 開発=**JDK `HttpServer`（別ポート）**（※1ポート制約時のみ Netty オプション検討） | バニラ枠を超えた RPG HUD・釣りゲージ・メニュー背景・武器/アイコンの見た目刷新 | 外部ランタイム依存ゼロ（CIのみPackSquash） |
| **Phase 3: ワールド & 環境作り込み** | **Paper `StructureManager`（`.nbt`）** ＋ `DemoWorld.kt` ハイブリッド移行 ＋ **同梱データパック（`DatapackRegistrar`）でカスタムバイオーム＆Mobバリアント** ＋ 26.1+ cutout対応ブロックステート採取ノード | 村・遺跡・ダンジョン部屋の建築品質向上、エリアごとの空/霧/環境演出、既存Mobのバリエーション拡充 | 制作時のみローカル建築サーバーに FAWE（本番依存ゼロ） |
| **Phase 4: フル3D独自Mob（ボス/看板NPC）** | **BetterModel（MIT）** ＋ Blockbench `.bbmodel` パイプライン | 章ボス・中ボス・固有NPCに3Dモデルとアニメーションを導入し、RPGのビジュアル格を一段引き上げる | BetterModel サーバー導入＋`bettermodel-bukkit-api`（MIT、`compileOnly`） |

### 7.2 ライセンス・改変配布上の注意（テクスチャ素材・Minecraft EULA 含む）

1. **コピーレフト（GPL / AGPL）の境界管理**:
   - **MIT / Apache-2.0**（InvUI, Triumph GUI, Cloud, Lamp, CommandAPI, BetterModel, Configurate, MockBukkit, Adventure）: 商用・非商用・改変配布すべて扱いやすい（著作権表示＋ライセンス文の同梱義務は遵守）。
   - **GPL-3.0**（PacketEvents, FAWE, WorldEdit, ForcePack, FreeMinecraftModels）: **プラグインJARへシェード（fat-jar同梱）すると、EtherealChronicle 本体にも GPL-3.0 公開義務が生じる（汚染リスク）**。連携する場合は必ず **`compileOnly`＋サーバー側へ別プラグイン配置（`depend`/`softdepend`）** とし、可能なら MIT 代替（例: BetterModel）を選ぶ。
   - **AGPL-3.0**（PackSquash）: ネットワーク経由サービスへ組み込むとソース公開義務が発生し得るが、**開発・CI環境でビルドCLIとして実行して生成されたリソースパック ZIP ファイル自体には AGPL は波及しない**。サーバーへ同梱せず CI パイプライン専用に徹すること。
2. **テクスチャ・3Dモデル・フォント・音声素材の権利**:
   - **CC-BY-4.0 / CC-BY-SA-4.0**: クレジット表記が必須（`-SA` は派生パック全体へ同一ライセンス継承義務があるため避けるのが無難）。`NegativeSpaceFont`（CC-BY-4.0）を丸ごと入れるより、自前 JSON で `space` フォントプロバイダを書く方がクリーン。
   - **バニラテクスチャの改変・再配布**: Minecraft 標準アセットをそのままコピー改変して公開配布する場合は **Minecraft Usage Guidelines / EULA** の範囲内にとどめる。独自テイスト＋将来的な独立配布を見据えるなら、オリジナル制作または **CC0 / 商用利用可・改変可の明記された素材** で統一し、`assets/THIRD_PARTY_LICENSES.md` に出典・ライセンス・URL を台帳管理する。
   - **AI生成・マーケットプレイス購入素材**: 利用規約で「ゲームサーバーのリソースパックとして不特定多数クライアントへ配布すること（実質的なアセットダウンロード）」が許可されているか必ず確認する（再配布禁止のストック素材は Minecraft リソースパックに使えない）。
   - **Minecraft EULA（商用利用ガイドライン）**: サーバーで課金要素を設ける場合、ゲームプレイ有利になる Pay-to-Win 販売は禁止。コスメティック（見た目装備・ペット・HUD装飾等）であっても Mojang ガイドラインの最新条項に照らして運用すること。

### 7.3 やらない方がよいもの（アンチパターン）

1. **バニラデータパック単体での「完全独自3D Mob」実装を試みること**: Java Edition 26.3 の仕様上不可能であり、時間浪費になる。バリアント/装備で足りる範囲と BetterModel を使う範囲を最初に割り切る。
2. **OptiFine CEM / EMF / クライアントMOD前提の見た目設計**: 純バニラクライアント参加者を切り捨てることになるため、サーバーサイド完結（Resource Pack + Display Entity / BetterModel）を厳守する。
3. **バニラ コアシェーダに依存したUI・画面演出**: MC 26.1〜26.3 で毎回破壊されており、Sodium ユーザーにも描画されない。保守コストが破綻する。
4. **アーカイブ済み／放置された Netty 多重化ライブラリ（`mcbrawls/inject` / `Apisium/Uniporter`）への直接依存、およびプロキシ環境での同一ポートHTTP前提設計**: メンテ不能＋Velocity/DDoS防御で即破綻する。本番は外部静的ホスト/CDNを基本とし、同一ポートは限定オプトインに留める。
5. **GPL-3.0 ライブラリ（PacketEvents / FAWE / FreeMinecraftModels）のプラグイン内シェード**: ライセンス汚染リスクが高く、かつ代替手段がある。
6. **高密度な `ItemDisplay` での地形・街全体の構築**: エンティティ描画負荷でクライアント FPS が急落する。地形・鉱石・大量装飾は 26.1+ のブロックステート再利用、家具・見せ場のみ `ItemDisplay` と明確に線引きする。
7. **ライブサーバー RCE 系 MCP（`reflect_invoke`・任意コマンド・JARアップロード・Groovy実行）の無制限接続**: プロンプトインジェクションや誤操作一発でホスト環境ごと侵害され得る。自動検証は既存の `MockBukkit` テスト＋明示的 QA 手順を主軸にする。
8. **現行 `SnakeYAML` や `DemoWorld.kt` の理由なき全面書き換え**: すでに安定稼働しテストで守られている基盤を壊すより、増分（`StructureManager` ハイブリッド化、Paper Brigadier、Dialog API）で価値を積み上げる方が安全かつ速い。
