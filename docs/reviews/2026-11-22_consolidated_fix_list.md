# 統合修正・リファクタリングリスト（2026-11-22）

- **統合元レビュー・検証ログ**:
  - `docs/reviews/2026-11-22_master_claude-qa.md`（Claude-QA / `9b0cb4b`）
  - `docs/reviews/2026-11-22_master_antigravity.md`（Antigravity-QA-3 / `67cb2a0`）
  - `docs/reviews/2026-11-22_task20_antigravity.md`（Antigravity-QA-3 / `8565cc0`）
  - `docs/reviews/2026-11-22_task20_claude-qa.md`（Claude-QA / `a55b5ad`）
  - `docs/reviews/2026-11-22_batch1_recheck_claude-qa.md`（Claude-QA Batch 1 再確認 / `d4dd791`）
  - `docs/reviews/2026-11-22_batch1_recheck_antigravity.md`（Antigravity-QA-3 Batch 1 再確認 / `820e792`）
  - `docs/reviews/2026-11-22_task22_claude-qa.md`（Claude-QA Task 22 再確認 / `e5ddf74`）
  - `docs/reviews/2026-11-22_task22_antigravity.md`（Antigravity-QA-3 Task 22 再確認 / `a8950c2`）
  - `docs/reviews/2026-11-22_task23_claude-qa.md`（Claude-QA Task 23 最終確認 / `ab59684`, `d10fe24`）
  - `docs/reviews/2026-11-22_task23_antigravity.md`（Antigravity-QA-3 Task 23〜24 最終確認 / `957e36d`, `65163a7`, `3697159`）
  - `docs/MANUAL_QA_RESULT.md`（Claude-QA Task 24 実 Paper スモーク＆実クライアント最終 QA PASS / `e1994bb`）
- **運用ルール**: `AGENTS.md` の「Cross-model review loop」に従い、全修正を実装者以外の全モデル（Claude / Antigravity）で再確認し、さらに実 Paper 26.3＋実クライアント QA で検証。
- **最終ステータス（`master` @ `1ccaef8`）**:
  - **全 49 項目クローズ（49 / 49）** — 修正完了＆両モデル OK（実機確認含む）＋理由付き受容・保留項目を末尾に明記
  - **自動テスト**: `./gradlew.bat build test --no-daemon` **289 tests / 0 failures / 0 errors / 0 skipped**
  - **クロスモデル最終判定**: Antigravity-QA-3 **GO（High 0 / Med 0）**、Claude-QA **GO（High 0 / Med 0）**
  - **実 Paper スモーク＆実クライアント最終 QA**: **PASS（0 ERROR / TPS 20.0）**（未実施 3 境界ケースのみ Claude-QA が追試中）

---

## Wave 1 — Security / Resource Pack / Netty / HTTP（7/7 クローズ）

- [x] **FIX-01 `[High][Security]`** (`AG H-01` / ✅ 両モデル OK `ec5ceb8`) `GamePortPackServer.kt`
  - `SNIFFING` で先頭 `'G'`/`'H'` を 1 バイト保留した時点で `idleTimeout` を開始し、確実にキャンセル完了。
- [x] **FIX-02 `[High][Security]`** (`AG H-02`, `B1-M01` / ✅ 両モデル OK `7682700`, `60bd57a`) `GamePortPackServer.kt`
  - `serverRootFromDataFolder`（`spigot.yml` 確認＋`worldContainer` フォールバック）化と二重呼出し解消完了。
- [x] **FIX-03 `[High][Security]`** (`AG H-08`, `CL M9, N3, L11` / ✅ 両モデル OK `7682700`, `60bd57a`) `ResourcePackDistribution.kt`
  - パックサイズ比例の書き込み期限（最低速度 128 KB/s・最大 180s）と `writeWatchdog` の `close()` 終了を完了。
- [x] **FIX-04 `[Med][Security/Perf]`** (`AG M-01, L-05`, `CL M10, P8` / ✅ 両モデル OK `ec5ceb8`) `GamePortPackServer.kt`
  - `PackConnectionLimiter` のグローバル共有ペース制御と O(1) シフトレジスタ終端検出完了。
- [x] **FIX-05 `[High][Bug]`** (`AG H-03`, `B1-M02` / ✅ 両モデル OK `7682700`) `ResourcePackController.kt`
  - `decide(...)` 順序修正と `!decision.send` 時の `removeResourcePacks(requestId)` 完了。
- [x] **FIX-06 `[Med][Bug]`** (`AG M-02`, `T20-M03` / ✅ 両モデル OK `ec5ceb8`) `ResourcePackController.kt`
  - `close()` クリア、`ACCEPTED`/`DOWNLOADED` 維持、`bundledInfo` キャッシュ完了。
- [x] **FIX-07 `[High][Bug/Style]`** (`CL M8, L8, S3, N4, N5`, `B1-H01, B1-M03` / ✅ 両モデル OK・実機 PASS `7682700`, `60bd57a`, `f06194c`, `e1994bb`) `ResourcePackSettings.kt`, `docs/RESOURCEPACK_HOSTING.md`
  - `loadSafely` フォールバック・`effectiveBindHost`・`0.0.0.0`/`::ffff:127.*` 判定・ローカル検証手順ドキュメント追記完了、実 Paper で `127.0.0.1` 配信 PASS。

---

## Wave 2 — Transactions / Locks / Economy / Persistence Integrity（7/7 クローズ）

- [x] **FIX-08 `[High][Bug]`** (`CL H1, N1`, `B1-H02` / ✅ 両モデル OK `7682700`) `AdventureServices.kt`, `AdventureController.kt`
  - `completeReadyAuto` のインベントリ満杯保留・会話フォールバック・MockBukkit 回帰テスト完了。
- [x] **FIX-09 `[Med][Bug]`** (`CL M1` / ✅ 両モデル OK `58de839`) `AdventureController.kt`, `Game.kt`
  - `PlayerProfile.returnToMailbox`（`Math.addExact` 加算マージ）へ全箇所統一完了。
- [x] **FIX-10 `[Med][Bug]`** (`CL M2` / ✅ 両モデル OK `58de839`) `DungeonController.kt`
  - ダンジョン初回クリア報酬の `adventure.profileTransaction` 化完了。
- [x] **FIX-11 `[High][Bug/Idiom]`** (`CL M3, I5, N2`, `B1-H03` / ✅ 両モデル OK `7682700`) `AdventureController.kt`
  - `LockToken` / `withTxLock` および `isCurrentDelivery(deliveries[p.uuid], claim)` 参照同一性ガード完了。
- [x] **FIX-12 `[Med][Bug]`** (`CL M4` / ✅ 両モデル OK `58de839`, `7682700`) `AdventureController.kt`, `PaperInventoryAccess.kt`
  - クリック元スロット優先売却・`0..35` 検証・`exchange` 重複統合完了。
- [x] **FIX-13 `[Med][Bug]`** (`CL M7`, `B1-M06` / ✅ 両モデル OK `7682700`) `RpgPlugin.kt`
  - 初期装備メールボックス退避時も `discoverItem`/`skillSlot`/`applyStats`/`p.health`/`p.closeInventory()`/`claimPending` 実行完了。
- [x] **FIX-14 `[Med][Bug]`** (`AG M-05`, `B1-M07` / ✅ 両モデル OK `7682700`) `FishingController.kt`
  - `beginBite()` 失敗キャンセルと `tick()` 旧無料バイト復帰ブロック削除完了。

---

## Wave 3 — UI / Lang / Reload / World & Entity Correctness（12/12 クローズ）

- [x] **FIX-15 `[High][Bug/Style]`** (`AG H-04, T22-H02, T23-H01`, `CL N6, H1` / ✅ 両モデル OK・実機 PASS `60bd57a`, `f06194c`, `3f9bafd`, `e1994bb`) `ClassSelectionUi.kt`, `RpgPlugin.kt`
  - 確認 Dialog の Esc 無効化＋24h 寿命、未就職 `/ec class` 再表示と管理者 `/ec class <player> <id>` 分離、差し替えウィンドウ追跡＋次 tick 再開ガード（ハング修正）完了。実クライアントで承認→選択→Esc 無視→戻る→選択→開始→`/ec class`・管理者変更すべて PASS（0 ERROR）。
- [x] **FIX-16 `[High][Bug]`** (`AG H-05`, `CL L7` / ✅ 両モデル OK `9de04bb`) `RpgPlugin.kt`, `GatheringController.kt`, `FishingController.kt`
  - `@Volatile language`・`/ec reload` の採取/釣り再構築完了。
- [x] **FIX-17 `[Low][Bug]`** (`AG H-06`, `CL FIX-17` / ✅ 両モデル OK `7682700`) `GatheringController.kt`, `CodexController.kt`
  - `Component.toString()` ダンプ解消、`reward_failed` の `plugin.diagnostic(...)` 統一、図鑑弱点 7 種の翻訳キー化完了。
- [x] **FIX-18 `[High][Bug]`** (`AG H-07`, `CL L4` / ✅ 両モデル OK `7682700`, `60bd57a`) `LangCatalog.kt`, `LangCatalogTest.kt`
  - リテラル置換・`(?=>)` 境界強化・短縮タグ除外・実 `lang/*.yml` 全キー走査テスト完了。
- [x] **FIX-19 `[Med][Bug]`** (`AG M-03` / ✅ 両モデル OK `9de04bb`) `CodexPagination.kt`, `CodexController.kt`
  - 図鑑エリア一覧の `page * 45 + slot` 計算と未使用 `codexTabs` 削除完了。
- [x] **FIX-20 `[Med][Bug/Idiom]`** (`AG M-04`, `CL L6` / ✅ 両モデル OK・実機 PASS `9de04bb`, `7682700`, `e1994bb`) `AdventureController.kt`, `InvUiInventoryAdapter.kt`
  - ショップ詰め・`onClose` クリーンアップ・9 の倍数チェック・`.take(54)` 完了。実クライアントで購入（5000→4944G）＋リフレッシュ後右クリック売却（+7/+7G）PASS。
- [x] **FIX-21 `[Med][Bug]`** (`CL M11` / ✅ 両モデル OK `9de04bb`) `AdventureController.kt`, `Wave3UiRules.kt`
  - 選択肢 0 件時の空 Dialog 例外回避完了。
- [x] **FIX-22 `[Med][Bug]`** (`AG M-06` / ✅ 両モデル OK `9de04bb`) `GatheringController.kt`
  - `ChunkLoadEvent` の孤立 `Interaction` ヒットボックス掃除完了。
- [x] **FIX-23 `[Med][Bug]`** (`AG M-08` / ✅ 両モデル OK `9de04bb`) `RpgPlugin.kt`
  - 範囲スキルの `participants` フィルタ・`distinctBy`・括弧明示完了。
- [x] **FIX-24 `[Med][Bug]`** (`AG M-12` / ✅ 両モデル OK `9de04bb`) `NpcController.kt`
  - 管理 NPC の `isPersistent = false`・ロード済みチャンク限定スポーン完了。
- [x] **FIX-25 `[Med][Bug]`** (`AG M-13` / ✅ 両モデル OK `9de04bb`) `DungeonController.kt`
  - スロット再利用時の `builtPalette` 再建築完了。
- [x] **FIX-26 `[Med][Bug/Style]`** (`AG M-09, B1-M08`, `CL S4` / ✅ 両モデル OK・実機 PASS `3f9bafd`, `e1994bb`) `UiThemeProvider.kt`, `resourcepack/assets/ec/textures/gui/bg_*.png`, `font/ui.json`, `UiPackAssetsTest.kt`
  - 全 6 背景 PNG の全幅 176px 全面不透明化（`176×132` / `176×222`）・白色乗算・タイトル色・`-177` 戻し補正・PNG 境界テスト完了。実クライアントで `full` 全幅表示＆タイトル可読・`/ec reload` による `icons_only` / `off` 切替 PASS（※下段インベントリ微細グリッド調整・バニラ `Inventory` ラベル輝度はデザイン磨き込みとして保留）。

---

## Wave 3B — Task 20 必須パックゲート修正（7/7 クローズ）

- [x] **FIX-39 `[High][Bug/Perf/Style]`** (`T20-H01`, `T22-H01` / ✅ 両モデル OK・実機 PASS `237986c`, `60bd57a`, `f06194c`, `e1994bb`) `RequiredPackLockListener.kt`, `FishingController.kt`, `config.yml`
  - `RequiredPackLockListener` 分離・`LOWEST` 優先度・`hasWaiters()`・条件付き登録・`FishingController.interact` の `ignoreCancelled=false`＋`shouldProcessFishingInteract` 完了。実 Paper クライアントでキャスト→バイト→リール→`CAUGHT` PASS。
- [x] **FIX-40 `[High][Security]`** (`T20-H02` / ✅ 両モデル OK `237986c`, `f06194c`, `a46cf25`) `RequiredPackLockListener.kt`, `RpgPlugin.kt`, `AdventureController.kt`, `EnemyController.kt`
  - 追加イベント 7 種、`Projectile.shooter` 判定、各種 `tick()` 待機除外、落下時視点保全クランプ完了。
- [x] **FIX-41 `[High][Bug]`** (`T20-H03`, `T22-H04`, `CL M3` / ✅ 両モデル OK `60bd57a`) `ResourcePackController.kt`
  - `send()` `catch` 節の全経路解放、ゲート開始時タイムアウト事前武装、dispatch `Runnable` 例外保護完了。
- [x] **FIX-42 `[High][Bug/Idiom/Perf]`** (`T20-H04` / ✅ 両モデル OK `237986c`, `60bd57a`) `ResourcePackController.kt`
  - `JoinState`（通常 class）集約・タスクキャンセル・`inline fun debugLog`・範囲定数化・重複 `terminalStatuses` 削除完了。
- [x] **FIX-43 `[Med][Bug]`** (`T20-M01..M06`, `T22-M01`, `T22-H04` / ✅ 両モデル OK `237986c`, `60bd57a`) `ResourcePackController.kt`
  - 延長上限 3 回・`bypass` 再評価・`DISCARDED` 処理・`enforcedLoadedPlayers`（`OFF`＋必須適用時 `hasResourcePack() == true`）・送信例外時 `required_apply_failed` キック完了。
- [x] **FIX-44 `[High][Bug/Security]`** (`T20-M02`, `T22-H03`, `CL M1, M2` / ✅ 両モデル OK・実機 PASS `60bd57a`, `f06194c`, `a46cf25`, `e1994bb`) `ResourcePackController.kt`, `RequiredPackLockListener.kt`
  - 無条件ルミナ転送と `isInvulnerable` 永続化を撤去し、垂直落下のみ許可・ダメージ/移動遮断へ移行。実クライアントで既存プレイヤー再ログイン座標保全（`(102.96, 65, -3.32)` 維持）および初回 75 秒以上待機 PASS。
- [x] **FIX-45 `[Med][Test]`** (`T20-M05`, `T22-M05`, `CL M6` / ✅ 主要結合テスト完了・残ケース保留 `237986c`, `60bd57a`, `f06194c`, `a46cf25`) `ResourcePackControllerIntegrationTest.kt`, `FishingInteractRoutingTest.kt`, `RequiredPackLockListenerTest.kt`
  - `ACCEPTED`→`SUCCESSFULLY_LOADED`（`OFF` 上書き含む）・実 join 経路 `DECLINED` 未保存・釣りルーティング・落下視点保全テスト追加完了（タイムアウト/延長/高速リログ等の追加結合テストは下記受容・保留記録へ）。

---

## Wave 3C — Batch 1 再確認追補（4/4 クローズ）

- [x] **FIX-46 `[Med][Bug/Perf]`** (`B1-M04, B1-M05`, `CL N10` / ✅ 両モデル OK `7682700`) `AdventureController.kt`
  - `beforeStorage` 遅延複製と `ProfileTransactionGuard` 導入完了。
- [x] **FIX-47 `[Low][Idiom/Style/Perf]`** (`T22-L02, L03`, `CL L1, L10, L11` / ✅ 主要項目完了・軽微残保留 `7682700`, `60bd57a`, `f06194c`) `ResourcePackController.kt`, `GamePortPackServer.kt`, `ResourcePackDistribution.kt`
  - `JoinState` 通常 class 化・重複 `terminalStatuses` / 未使用 `shouldDispatch` 削除・`serverRootFromDataFolder` 修正・128 KB/s 緩和完了（※26 個の `settings.*` 別名プロパティ等 cosmetic 残件は受容・保留記録へ）。
- [x] **FIX-48 `[Med][Bug]`** (`T22-M02`, `CL L12` / ✅ 両モデル OK `7682700`, `60bd57a`) `RpgPlugin.kt`, `GamePortPackServer.kt`, `LangCatalog.kt`, `AdventureController.kt`
  - 404 `no-store`・`DETAIL_PLACEHOLDER`・`.take(54)`・`/ec reload` 非同期言語読込後の直列 `reloadAdvancementPack()`＋完了返信順序修正完了。
- [x] **FIX-49 `[Med][Test]`** (`T22-M05`, `CL N8` / ✅ 主要テスト完了・残ケース保留 `7682700`, `60bd57a`, `f06194c`, `a46cf25`, `3f9bafd`) `src/test/kotlin/net/nakaymi/etherealchronicle/`
  - 設定 typo フォールバック、満杯＋自動完了依頼 NPC 会話、実 `lang/*.yml` 全キー走査、クラスメニュー再入ガード、`COLLECT`→`EXPLORE`、管理者 `/ec class`・補完等の回帰テスト追加完了（合計 289 tests）。

---

## Wave 4 — Performance & Hot-Path Optimization（5/5 クローズ）

- [x] **FIX-27 `[Med][Perf/Bug]`** (`T22-M04`, `T23F-M01`, `CL L7, M8` / ✅ 両モデル OK `595f96c`, `60bd57a`, `f06194c`, `a46cf25`, `3f9bafd`) `AdventureServices.kt`, `AdventureController.kt`, `RpgPlugin.kt`
  - `applyStats` 再利用・`PlayerMoveEvent` 同一ブロック早期 return・毎秒称号同期削除＋討伐/パーティ XP/管理者コマンドでの同期追加・`mayNeedPeriodicUpdate`/`needsPeriodicUpdate`（順序・ワイルドカード・`COLLECT`→`EXPLORE` 対応＋1 パスカウント）完了。
- [x] **FIX-28 `[Med][Perf]`** (`CL M4, M5` / ✅ 両モデル OK・一部受容 `595f96c`, `60bd57a`) `AdventureController.kt`, `PaperInventoryAccess.kt`, `RpgPlugin.kt`
  - `safeAreas` 索引化・`tradeBound` の短絡評価化・`PaperInventoryAccess.Plan` 通常 class 化＆不要複製削減完了（`effectiveStats` プレイヤー別キャッシュは受容・保留）。
- [x] **FIX-29 `[Med][Perf]`** (`AG M-07`, `CL M5, L7` / **【受容・保留】**) `RpgPlugin.kt`, `AdventureController.kt`
  - 起動/リロード限定の `AdvancementDatapack.install` 同期書出、および損失耐性優先の `player.saveData()` は設計トレードオフとして現バージョン受容。
- [x] **FIX-30 `[Med][Perf/Bug]`** (`T22-M03`, `CL L5` / ✅ 両モデル OK `595f96c`, `60bd57a`) `RpgPlugin.kt`
  - `enhancementLoreCap` 個別保持により固定言語設定時のキャッシュミスを解消完了。
- [x] **FIX-31 `[Low][Perf/Bug]`** (`T22-L01`, `CL L4, L6` / ✅ 両モデル OK・一部受容 `595f96c`, `60bd57a`) `TreasureController.kt`, `AdventureController.kt`, `WorldContent.kt`, `GatheringController.kt`
  - `enemiesByArea`・`sitesByChunk`・`chestsByChunk`（`floor` 負座標修正含む）・`dialogueComponents` キャッシュ活用＆Paper Dialog 早期 return 完了（未接触箇所の散在 `Regex`/`Yaml` 一括整理は受容・保留）。

---

## Wave 5 — Idiomatic Kotlin, Structure Refactoring & Visual Polish（6/6 クローズ）

- [x] **FIX-32 `[Med][Idiom]`** (`CL I1, L3`, `T23-M01` / ✅ 両モデル OK `ee13c75`, `60bd57a`, `f06194c`) `AdventureServices.kt`, `AdventureController.kt`, `RpgPlugin.kt`
  - `GameException` / `requireGame` 導入、`transaction_busy`・`items_missing` を含む型付き例外移行および `diagnostic(sender, GameException)` 統一完了。
- [x] **FIX-33 `[Low][Idiom]`** (`T23-M03`, `CL I2..I7, L11` / ✅ 両モデル OK・一部受容 `ee13c75`, `60bd57a`, `f06194c`) `ComponentText.kt`, `PlayerStore.kt`, `FishingController.kt`, `GatheringController.kt`, `DungeonController.kt`
  - `Component.plainText()`・`monotonicMillis()`・`PlayerStore` 全整数フィールドの `boundedInt` 化（`skill_levels` ハード上限 100 解除含む）完了。
- [x] **FIX-34 `[Low][Style]`** (`AG L-01`, `CL L11, S2` / **【部分完了・残件保留】**) `RpgPlugin.kt`, `RequiredPackLockListener.kt`
  - `RequiredPackLockListener` 分離完了。管理コマンド/強化処理の大規模分割はリリース後保守へ保留。
- [x] **FIX-35 `[Low][Style]`** (`AG L-02, L-03`, `CL L2, L9, S5` / **【保留】**) `PlayerStore.kt`, `DemoWorld.kt`
  - 既存セーブ互換リスク回避のため、マイグレーション全面パイプライン書き換えはリリース後保守へ保留。
- [x] **FIX-36 `[Low][Style]`** (`AG L-07`, `T22-L03` / **【部分完了・残件保留】** `ee13c75`, `a46cf25`) `WorldDefinitions.kt`, `AdventureController.kt`, `WorldValidation.kt`
  - `WorldIds.DEMO`・`tradeBound` 述語化完了。軽微なインデント統一残件はリリース後保守へ保留。
- [x] **FIX-37 `[Polish][Idiom/Style]`** (`AG P-01, P-02`, `T22-L03` / **【部分完了・残件保留】** `ee13c75`, `60bd57a`) `RpgPlugin.kt` ほか
  - 未使用 `areaCodexPercent` 削除・`lootSequence` 改行完了。6 ファイルの二重 KDoc・セミコロン整形はリリース後保守へ保留。

---

## Wave 6 — Regression Tests & Real-Paper Test-Gap Closing（1/1 クローズ）

- [x] **FIX-38 `[High/Med][Test]`** (`CL H2, L9`, `T22-H05`, `T23-L01`, `T23F-M02, M03` / ✅ 両モデル OK・実機スモーク PASS `14e1be8`, `60bd57a`, `f06194c`, `a46cf25`, `3f9bafd`, `e1994bb`) `PaperContentValidator.kt`, `RpgPlugin.kt`, `scripts/smoke-test.ps1`
  - `Material`/`Block`/`Particle`/`EntityType`/ダンジョンパレット(≥3)/オオカミ `visual.variant` 検証のメインスレッド安全実行（15 秒タイムアウト＋終了時キャンセル）、`BasicCommand.suggest` 接続＆権限フィルタ、`smoke-test.ps1` の `EC_CONTENT_VALIDATION_SUCCESS`・全角句点・StrictMode `@(...)` 修正完了。実 Paper スモーク PASS。

---

## 受容・保留記録（Accepted / Deferred）
- **`FIX-28` の `effectiveStats` プレイヤー別キャッシュ**: `applyStats` 計算再利用で毎秒二重計算は解消済み。全装備/強化/バフ変更でのキャッシュ無効化漏れリスクを避け、現リリースでは見送り。
- **`FIX-29`（`AdvancementDatapack.install` 同期書出・`player.saveData()`）**: 前者は起動/リロード時限定、後者はアイテム消費・取引時のクラッシュ巻き戻り／増殖防止を優先した設計トレードオフとして受容。
- **`FIX-26` の軽微な見た目磨き込み**: `ui.theme.mode: full` での全幅背景＆タイトル中央・可読性は実クライアントで PASS。下段プレイヤーインベントリ枠の微細なグリッドずれおよび暗い背景上のバニラ `"Inventory"` ラベル輝度は次期アート調整として保留（既定モードは安定の `icons_only`）。
- **`FIX-31`/`FIX-33`〜`FIX-37`/`FIX-45`/`FIX-47`/`FIX-49` の低重要度・未接触箇所リファクタおよび追加エッジ結合テスト**: 実クライアント QA 通過後のコードチャーンとデグレリスクを避けるため、以下はリリース後保守へ保留：
  - `RpgPlugin` 管理/強化ファイル分割、`PlayerStore`/`DemoWorld` 全面マイグレーションパイプライン化、`ResourcePackController` 26 個の `settings.*` 別名整理、未接触箇所の `Regex`/`Yaml` 一括整理・6 ファイル二重 KDoc・インデント/セミコロン整形
  - Task 24 レビューの極軽微 Low 2 件（`ClassSelectionWindowTracker.open` の `window.open()` 例外時 `current` 掃除、超低速コールドスタート時の 15 秒上限）
  - タイムアウト延長上限/高速再ログイン/送信例外等の追加 MockBukkit 結合テスト（実機・単体ポリシーテストで主要経路は担保済み）
