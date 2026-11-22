# 統合修正・リファクタリングリスト（2026-11-22）

- **統合元レビュー**:
  - `docs/reviews/2026-11-22_master_claude-qa.md`（Claude-QA / `9b0cb4b`）
  - `docs/reviews/2026-11-22_master_antigravity.md`（Antigravity-QA-3 / `67cb2a0`）
  - `docs/reviews/2026-11-22_task20_antigravity.md`（Antigravity-QA-3 / `8565cc0` — Task 20 差分 `8f3acfa`）
  - `docs/reviews/2026-11-22_task20_claude-qa.md`（Claude-QA / `a55b5ad` — Task 20 差分 `8f3acfa`）
  - `docs/reviews/2026-11-22_batch1_recheck_claude-qa.md`（Claude-QA Batch 1 再確認 / `d4dd791`）
  - `docs/reviews/2026-11-22_batch1_recheck_antigravity.md`（Antigravity-QA-3 Batch 1 再確認 / `820e792`）
  - `docs/reviews/2026-11-22_task22_claude-qa.md`（Claude-QA Task 22 再確認 / `e5ddf74`）
  - `docs/reviews/2026-11-22_task22_antigravity.md`（Antigravity-QA-3 Task 22 再確認 / `a8950c2`）
- **運用ルール**: `AGENTS.md` の「Cross-model review loop」に従い、Wave 単位でコミット＋全テスト実行し、完了後に実装者以外の全モデル（Claude / Antigravity）が再確認して全項目クローズ（または理由付き受容）となるまで完了としない。
- **実行計画**:
  - **Batch 1（Wave 1〜3）**: 実装・マージ済み（`65e62bf`）→ 再確認完了
  - **Batch 2（Wave 3B + Wave 3C + Wave 4〜6）**: 実装・マージ済み（`9bd6d89`、276 tests pass）→ **両モデル再確認完了**（26 件両モデル OK / 3 件理由付き受容・保留 / 20 件 Batch 3 で仕上げ＋実機確認）
  - **Batch 3（Wave 7 — 最終追補）**: Task 22 両モデル再確認で見つかった実機ブロッカー（High 5 件）＋ Med/Low/結合テスト残件を一括修正 → 両モデル最終確認＆実クライアント QA へ

---

## Wave 1 — Security / Resource Pack / Netty / HTTP（6/7 クローズ・1 件ドキュメント残）

- [x] **FIX-01 `[High][Security]`** (`AG H-01` / ✅ 両モデル OK `ec5ceb8`) `GamePortPackServer.kt:132-162`
  - `SNIFFING` で先頭 `'G'`/`'H'` を 1 バイト保留した時点で `idleTimeout` を開始し、確実にキャンセル完了。
- [x] **FIX-02 `[High][Security]`** (`AG H-02`, `B1-M01` / ✅ 両モデル OK `7682700`) `GamePortPackServer.kt:72-106`
  - `serverRootFromDataFolder` 化と二重呼出し解消完了（※`parent` null ガードと `worldContainer` フォールバックは Batch 3 `FIX-47` に合流）。
- [x] **FIX-03 `[High][Security]`** (`AG H-08`, `CL M9, N3` / ✅ 両モデル OK `7682700`) `ResourcePackDistribution.kt:84-86,186,212`
  - パックサイズ比例の書き込み期限と `writeWatchdog` の `close()` 終了を完了（※最低速度 512 KB/s → 128 KB/s 緩和は Batch 3 `FIX-47` に合流）。
- [x] **FIX-04 `[Med][Security/Perf]`** (`AG M-01, L-05`, `CL M10, P8` / ✅ 両モデル OK `ec5ceb8`) `GamePortPackServer.kt`
  - `PackConnectionLimiter` のグローバル共有ペース制御と O(1) シフトレジスタ終端検出完了。
- [x] **FIX-05 `[High][Bug]`** (`AG H-03`, `B1-M02` / ✅ 両モデル OK `7682700`) `ResourcePackController.kt:145`
  - `decide(...)` 順序修正と `!decision.send` 時の `removeResourcePacks(requestId)` 完了（※実コントローラ結合テストは `FIX-49`）。
- [x] **FIX-06 `[Med][Bug]`** (`AG M-02`, `T20-M03` / ✅ 両モデル OK `ec5ceb8`) `ResourcePackController.kt`
  - `close()` クリア、`ACCEPTED`/`DOWNLOADED` 維持、`bundledInfo` キャッシュ完了。
- [ ] **FIX-07 `[High][Bug/Style]`** (`CL M8, L8, S3, N4, N5`, `B1-H01, B1-M03` / コード修正済み・ドキュメント追記のみ残) `ResourcePackSettings.kt`, `docs/RESOURCEPACK_HOSTING.md`
  - `loadSafely` フォールバック・`effectiveBindHost`・`0.0.0.0`/`::ffff:127.*` 判定は両モデル OK。
  - **【Batch 3 残件】** `docs/RESOURCEPACK_HOSTING.md` にローカル検証向け `bind_host: 127.0.0.1` / `public_host: 127.0.0.1` 設定例を明記する。

---

## Wave 2 — Transactions / Locks / Economy / Persistence Integrity（7/7 両モデル OK）

- [x] **FIX-08 `[High][Bug]`** (`CL H1, N1`, `B1-H02` / ✅ 両モデル OK `7682700`) `AdventureServices.kt:87-96`, `AdventureController.kt:61,85-93`
  - `completeReadyAuto` のインベントリ満杯保留・会話フォールバック・MockBukkit 回帰テスト完了。
- [x] **FIX-09 `[Med][Bug]`** (`CL M1` / ✅ 両モデル OK `58de839`) `AdventureController.kt`, `Game.kt`
  - `PlayerProfile.returnToMailbox`（`Math.addExact` 加算マージ）へ全箇所統一完了。
- [x] **FIX-10 `[Med][Bug]`** (`CL M2` / ✅ 両モデル OK `58de839`) `DungeonController.kt`
  - ダンジョン初回クリア報酬の `adventure.profileTransaction` 化完了。
- [x] **FIX-11 `[High][Bug/Idiom]`** (`CL M3, I5, N2`, `B1-H03` / ✅ 両モデル OK `7682700`) `AdventureController.kt:474-478,532`
  - `LockToken` / `withTxLock` および `isCurrentDelivery(deliveries[p.uuid], claim)` 参照同一性ガード完了（※実コントローラ結合テストは `FIX-49`）。
- [x] **FIX-12 `[Med][Bug]`** (`CL M4` / ✅ 両モデル OK `58de839`) `AdventureController.kt`, `PaperInventoryAccess.kt`
  - クリック元スロット優先売却・`0..35` 検証・`exchange` 重複統合完了。
- [x] **FIX-13 `[Med][Bug]`** (`CL M7`, `B1-M06` / ✅ 両モデル OK `7682700`) `RpgPlugin.kt:342-361`
  - 初期装備メールボックス退避時も `discoverItem`/`skillSlot`/`applyStats`/`p.health`/`p.closeInventory()`/`claimPending` 実行完了。
- [x] **FIX-14 `[Med][Bug]`** (`AG M-05`, `B1-M07` / ✅ 両モデル OK `7682700`) `FishingController.kt:297,375`
  - `beginBite()` 失敗キャンセルと `tick()` 旧無料バイト復帰ブロック削除完了。

---

## Wave 3 — UI / Lang / Reload / World & Entity Correctness（10/12 両モデル OK・2 件残）

- [ ] **FIX-15 `[High][Bug/Style]`** (`AG H-04, T22-H02`, `CL N6, H1` / **Batch 3 P0 ブロッカー**) `ClassSelectionUi.kt:71-88`
  - `ClassConfirmationTracker`・`onQuit` クリア・斜体解除＆Lore は完了。
  - **【Batch 3 必須修正】** `canCloseWithEscape(true)` にしたが Esc 閉じコールバックが無いため、Esc で閉じると `confirming` が `true` のまま残り未就職プレイヤーがメニューへ戻れない → `canCloseWithEscape(false)` に戻して `ClickCallback.Options` の `lifetime` を十分長く（例: 24 時間）し、2 分放置ソフトロックと Esc 離脱を両方防ぐ（実クライアント要確認）。
- [x] **FIX-16 `[High][Bug]`** (`AG H-05`, `CL L7` / ✅ 両モデル OK `9de04bb`) `RpgPlugin.kt`, `GatheringController.kt`, `FishingController.kt`
  - `@Volatile language`・`/ec reload` の採取/釣り再構築完了（※非同期言語読込順序は `FIX-48` で対応）。
- [x] **FIX-17 `[Low][Bug]`** (`AG H-06`, `CL FIX-17` / ✅ 両モデル OK `7682700`) `GatheringController.kt:328`, `CodexController.kt:124-137`
  - `Component.toString()` ダンプ解消、`reward_failed` の `plugin.diagnostic(...)` 統一、図鑑弱点 7 種の翻訳キー化完了。
- [x] **FIX-18 `[High][Bug]`** (`AG H-07`, `CL L4` / ✅ 両モデル OK `7682700`) `LangCatalog.kt:69-70,88-97`
  - リテラル置換・`(?=>)` 境界強化・短縮タグ除外完了（※実 `lang/*.yml` 全キー走査テストは `FIX-49`）。
- [x] **FIX-19 `[Med][Bug]`** (`AG M-03` / ✅ 両モデル OK `9de04bb`) `CodexPagination.kt`, `CodexController.kt`
  - 図鑑エリア一覧の `page * 45 + slot` 計算と未使用 `codexTabs` 削除完了。
- [x] **FIX-20 `[Med][Bug/Idiom]`** (`AG M-04`, `CL L6` / ✅ 両モデル OK・実機要確認 `9de04bb`) `AdventureController.kt`, `InvUiInventoryAdapter.kt`
  - ショップ詰め・`onClose` クリーンアップ・9 の倍数チェック・`.take(54)` 完了（リフレッシュ後右クリック売却は実クライアントで最終確認）。
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
- [ ] **FIX-26 `[Med][Bug/Style — コード修正済・実機要確認]`** (`AG M-09, B1-M08`, `CL S4`) `UiThemeProvider.kt:68-70`, `UiQAFixesTest.kt:52-54`
  - `-8px` の `spaceFont` 先頭配置とユニットテストは完了。実クライアント `ui.theme.mode: full` のスクショ確認をもってクローズ。

---

## Wave 3B — Task 20 必須パックゲート修正（2/7 両モデル OK・5 件 Batch 3）

- [ ] **FIX-39 `[High][Bug/Perf/Style]`** (`T20-H01`, `T22-H01` / **Batch 3 P0 ブロッカー**) `FishingController.kt:141-142`, `RequiredPackLockListener.kt`, `config.yml:17-18`
  - `RequiredPackLockListener` 分離・`LOWEST` 優先度・`hasWaiters()`・条件付き登録は完了。
  - **【Batch 3 必須修正】** `FishingController.interact` の `ignoreCancelled = true` が実機 Paper で `RIGHT_CLICK_AIR`/`LEFT_CLICK_AIR`（初期値 `useInteractedBlock=DENY` により `isCancelled()==true`）を全件スキップし**釣り不能化**する → `ignoreCancelled = false` に戻し先頭で `if (e.useItemInHand() == Event.Result.DENY) return` をチェックする。`config.yml:17-18` に `10..600` 範囲コメントを追記。
- [x] **FIX-40 `[High][Security]`** (`T20-H02` / ✅ 両モデル OK `237986c`) `RequiredPackLockListener.kt`, `RpgPlugin.kt`, `AdventureController.kt`, `EnemyController.kt`
  - 追加イベント 7 種、`Projectile.shooter` 判定、各種 `tick()` 待機除外を完了（※余力あれば `CL L2` のバケツ/釣り/ベッド等のイベント補強も可）。
- [ ] **FIX-41 `[High][Bug]`** (`T20-H03`, `T22-H04`, `CL M3` / **Batch 3 P0 ブロッカー**) `ResourcePackController.kt:166-176,358-369`
  - **【Batch 3 必須修正】** ① `send()` の `catch` 節で `mustDisconnect == false` 時にも必ず `releaseRequiredWait(player.uniqueId, continueJoin = true)` を呼びデッドロック防止。② dispatch Runnable 自体を `try/catch` で保護し、タイムアウトはゲート開始時に事前武装（送信成功時に再武装）して例外時永久凍結を防ぐ。
- [x] **FIX-42 `[High][Bug/Idiom/Perf]`** (`T20-H04` / ✅ 両モデル OK `237986c`) `ResourcePackController.kt`
  - `JoinState` 集約・タスクキャンセル・`inline fun debugLog`・範囲定数化完了（※`JoinState` 通常 class 化・dead code・`terminalStatuses` 重複削除は `FIX-47`）。
- [ ] **FIX-43 `[Med][Bug]`** (`T20-M01..M06`, `T22-M01`, `T22-H04` / **Batch 3 P1**) `ResourcePackController.kt:174,267-268`
  - 延長上限 3 回・`bypass` 再評価・`DISCARDED` 処理は完了。
  - **【Batch 3 必須修正】** ① `hasResourcePack(playerId)` が `required: true` 強制適用された保存済み `OFF` プレイヤーにも `true` を返すように修正（`enforcedLoadedPlayers` 等で記録）。② `send()` 例外切断時は `ec.resource_pack.required_declined` ではなく `ec.resource_pack.required_apply_failed` を使う。
- [ ] **FIX-44 `[High][Bug/Security]`** (`T20-M02`, `T22-H03`, `CL M1, M2` / **Batch 3 P0 ブロッカー**) `ResourcePackController.kt:187-191,258-260`
  - 未入場者プロファイル非保存は完了。
  - **【Batch 3 必須修正】** ① `beginRequiredJoin` の無条件ルミナ転送を修正し、既存プレイヤーのログアウト座標を失わないようにする（`!player.hasPlayedBefore()` のみルミナ転送、または待機前 `originalLocation` を保存して解放/切断時に復元）。② `player.isInvulnerable = true` はクラッシュで永続化するため廃止し、`RequiredPackLockListener.damage` キャンセルに委ねる（または PDC マーカーで起動/参加時に復旧）。
- [ ] **FIX-45 `[Med][Test]`** (`T20-M05`, `T22-M05`, `CL M6` / **Batch 3 P1**) `src/test/kotlin/net/nakaymi/etherealchronicle/paper/`
  - **【Batch 3 必須修正】** `ResourcePackController` 経由の MockBukkit 結合テストを追加（`PlayerResourcePackStatusEvent` 発火による `SUCCESSFULLY_LOADED` 解除、`ACCEPTED`/`DOWNLOADED` 延長上限、`DECLINED`/タイムアウト/送信例外/高速再ログイン/`bypass` 変化、`OFF`＋`required:true` の `hasResourcePack() == true`、既存プレイヤー位置保全、未入場プロファイル非保存）＋ `FishingController.interact` の空中クリック（`useInteractedBlock = DENY`）回帰テスト。

---

## Wave 3C — Batch 1 再確認追補（1/4 両モデル OK・3 件 Batch 3）

- [x] **FIX-46 `[Med][Bug/Perf]`** (`B1-M04, B1-M05`, `CL N10` / ✅ 両モデル OK `7682700`) `AdventureController.kt:400-408,487-494,535-540`
  - `beforeStorage` 遅延複製と `ProfileTransactionGuard` 導入完了。
- [ ] **FIX-47 `[Low][Idiom/Style/Perf]`** (`T22-L02, T22-L03`, `CL L1, L10, L11` / **Batch 3**) `ResourcePackController.kt`, `GamePortPackServer.kt`, `ResourcePackDistribution.kt`
  - **【Batch 3 残件】** ① `ResourcePackController.kt:15-41` の 27 個の `settings.*` 別名フィールド・到達不能 `if (!player.isOnline)`・重複 `terminalStatuses` を削除し、`JoinState` を通常 `class` 化。② `GamePortPackServer.kt` の重複 `matchesPrefix` 削除、`serverRootFromDataFolder` の null 安全＋`worldContainer` フォールバック追加。③ `EmbeddedPackServer` の書き込み最低レートを 512 KB/s → 128 KB/s に緩和。
- [ ] **FIX-48 `[Med][Bug]`** (`T22-M02`, `CL L12` / **Batch 3 P1**) `RpgPlugin.kt:952-967`
  - 404 `no-store`・`DETAIL_PLACEHOLDER`・`.take(54)` は完了。
  - **【Batch 3 必須修正】** `/ec reload` の非同期 `LangCatalog.load` 完了後に `sync { language = loaded; reloadAdvancementPack(); ...; sender.sendMessage(reload_success) }` を実行するよう順序を修正する。
- [ ] **FIX-49 `[Med][Test]`** (`T22-M05`, `CL N8` / **Batch 3 P1**) `src/test/kotlin/net/nakaymi/etherealchronicle/paper/`
  - **【Batch 3 必須修正】** 1 行ヘルパー単体ではなく実コントローラ/実 YAML 経路の回帰テストを追加：① `ResourcePackController.send()` の `OFF` 解除 ② `AdventureController.deliverLocked` の切断→再接続保護 ③ `FishingController.tick()` の餌消費失敗非復帰 ④ 実リソース `lang/ja_jp.yml` / `lang/en_us.yml` 全キーの `DETAIL_PLACEHOLDER` 誤除外なし検証 ⑤ `/ec setlevel`・パーティ経験値での称号同期テスト。

---

## Wave 4 — Performance & Hot-Path Optimization（1/5 受容済・4 件 Batch 3）

- [ ] **FIX-27 `[Med][Perf/Bug]`** (`T22-M04`, `CL L7, M8` / **Batch 3 P1**) `AdventureController.kt:54-63`, `RpgPlugin.kt`
  - `applyStats` 再利用・`PlayerMoveEvent` 同一ブロック早期 return・毎秒称号同期削除は完了。
  - **【Batch 3 必須修正】** ① `AdventureController.tick()` で `transaction()` に入る前に read-only 事前判定（進行中クエストに探索/所持/自動完了対象があるか）を入れ、無変化プレイヤーの毎秒 `p.snapshot()` ディープコピーを完全排除する。② 毎秒 `syncTitleAdvancements` 削除に伴い、`/ec setlevel`・フラグ変更・パーティ経験値付与経路でも `syncTitleAdvancements` を呼ぶようにする。
- [ ] **FIX-28 `[Med][Perf]`** (`CL M4, M5` / **Batch 3 P1・一部受容**) `AdventureController.kt:305-315`, `PaperInventoryAccess.kt:10-55`
  - `safeAreas` 索引化は完了。
  - **【Batch 3 必須修正】** ① `tradeBound` で `bound()` を全クリック先行評価してしまった回帰を修正（`external` 等で短絡または遅延評価し、死んだ `bundleSwap ||` も削除）。② `PaperInventoryAccess.plan` の `remove.isEmpty()` 時・`canExchange` 時の不要複製を省き、`Plan` を通常 `class` 化。（※プレイヤー別 `effectiveStats` キャッシュは下記「受容・保留記録」へ）
- [x] **FIX-29 `[Med][Perf]`** (`AG M-07`, `CL M5, L7` / **【受容・保留】** 下記記録参照) `RpgPlugin.kt`, `AdventureController.kt`
  - `AdvancementDatapack.install` は起動/リロード時限定、`player.saveData()` は損失耐性優先の設計トレードオフとして現バージョン受容。
- [ ] **FIX-30 `[Med][Perf/Bug]`** (`T22-M03`, `CL L5` / **Batch 3 P1**) `RpgPlugin.kt:55-56,619-629`
  - **【Batch 3 必須修正】** `enhancementLoreLines.size != cap * 2` を `enhancementLoreCap != cap` へ変更し、固定言語設定時の 100% キャッシュミスを解消する。
- [ ] **FIX-31 `[Low][Perf/Bug]`** (`T22-L01`, `CL L4, L6` / **Batch 3・一部受容**) `TreasureController.kt:23-41`, `AdventureController.kt:101-125`
  - `enemiesByArea`・`sitesByChunk`・`chestsByChunk`・`dialogueComponents` は完了。
  - **【Batch 3 必須修正】** ① `TreasureController.chestsByChunk` で負座標の `toInt() shr 4` 切り捨てバグを `floor(x).toInt() shr 4` へ修正。② `dialogueComponents` キーから不要な `locale` を外し、`showDialogueDialog` でキャッシュ済み `textComponent` を再利用し、`paperUiEnabled` 分岐を `RpgMenu` 生成より前へ移動する。（※他ファイル散在の未接触 `Regex`/`Yaml` 定数化は下記「受容・保留記録」へ）

---

## Wave 5 — Idiomatic Kotlin, Structure Refactoring & Visual Polish（2/6 受容済・4 件 Batch 3）

- [ ] **FIX-32 `[Med][Idiom]`** (`CL I1, L3` / **Batch 3 P1**) `AdventureServices.kt`, `AdventureController.kt:268`
  - `TradeEngine` は完了。
  - **【Batch 3 必須修正】** `QuestEngine.complete` 等にも `requireGame` / `GameException` を徹底し、`AdventureController` の `error.message?.startsWith("ec." + "error.")` 文字列照合を `catch (e: GameException)` へ完全移行する（`GameException.arguments` も `diagnostic` へ渡す）。
- [ ] **FIX-33 `[Low][Idiom]`** (`CL I2..I7` / **Batch 3 で軽微整理・大規模再編は受容**) `ComponentText.kt`, `FishingController.kt`, `GatheringController.kt`, `DungeonController.kt`, `PlayerStore.kt`
  - `Component.plainText()`・`monotonicMillis()`・`boundedInt` は完了。残り未接触箇所の全面 enum/セッション再編はリリース後保守へ保留。Batch 3 では `PlayerStore.kt` の他整数項目への `boundedInt` 適用を補完する。
- [x] **FIX-34 `[Low][Style]`** (`AG L-01`, `CL L11, S2` / **【部分完了・残件保留】** 下記記録参照) `RpgPlugin.kt`
  - `RequiredPackLockListener` 分離は完了。管理コマンド/強化処理の大規模ファイル分割は実機 QA 前のチャーン回避のため次フェーズ保守へ保留。
- [x] **FIX-35 `[Low][Style]`** (`AG L-02, L-03`, `CL L2, L9, S5` / **【保留】** 下記記録参照) `PlayerStore.kt`, `DemoWorld.kt`
  - スキーマ/ワールド移行ロジックの全面パイプライン書き換えは既存セーブ互換リスク回避のため次フェーズ保守へ保留。
- [ ] **FIX-36 `[Low][Style]`** (`T22-L03`, `CL L8` / **Batch 3**) `WorldValidation.kt`, `NpcController.kt`, `SimulationCommandController.kt`, `RpgPlugin.kt`
  - `WorldIds.DEMO` と `tradeBound` 述語化は完了。
  - **【Batch 3 残件】** インデント崩れ 4 箇所（`WorldValidation.kt` / `NpcController.kt` / `SimulationCommandController.kt` / `RpgPlugin.kt:965`）を修正する。
- [ ] **FIX-37 `[Polish][Idiom/Style]`** (`T22-L03` / **Batch 3**) `RpgPlugin.kt:882` ほか 6 ファイル
  - 未使用 `areaCodexPercent` 削除は完了。
  - **【Batch 3 残件】** `RpgPlugin.kt:882` の `}    private var lootSequence=0L` と 6 ファイルの二重 KDoc を解消する。

---

## Wave 6 — Regression Tests & Real-Paper Test-Gap Closing（0/1 — Batch 3 P0）

- [ ] **FIX-38 `[High][Test]`** (`T22-H05`, `CL L9` / **Batch 3 P0 ブロッカー**) `scripts/smoke-test.ps1:56,88`, `PaperContentValidator.kt`
  - `PaperContentValidator` の起動時・`/ec validate` 検証は完了。
  - **【Batch 3 必須修正】** ① `scripts/smoke-test.ps1:88` の `'コンテンツ検証成功\.'`（半角）を `'コンテンツ検証成功。|Content validation succeeded\.'`（全角句点対応）へ修正し、56 行も `'準備が完了|Adventure systems are ready\.'` に揃える。② `PaperContentValidator` にダンジョン `palette.size >= 3` およびオオカミ `visual.variant` 検証を追加し、実 Paper ヘッドレススモーク実行で PASS を確認する。

---

## 受容・保留記録（Accepted / Deferred）
- **`FIX-28` のうち `effectiveStats` プレイヤー別キャッシュ**: `applyStats` の計算再利用で毎秒の二重計算は解消済み。全装備/強化/バフ/スキル変更経路でのキャッシュ無効化漏れによるステータス不整合リスクを避けるため、現リリースでは見送り（負荷計測上ボトルネックになった場合に再検討）。
- **`FIX-29`（`AdvancementDatapack.install` の同期書出および `player.saveData()`）**: `AdvancementDatapack.install` は起動時と管理者 `/ec reload` のみ実行され通常プレイの tick を阻害しないため受容。`player.saveData()` はアイテム消費・取引時のクラッシュ巻き戻り／増殖防止を優先した設計トレードオフとして受容（`docs/AGENT_STATUS.md` 記載済み）。
- **`FIX-31`/`FIX-33`/`FIX-34`/`FIX-35`/`FIX-36`/`FIX-37` の未接触ファイル大規模リファクタリング**（`RpgPlugin` 管理/強化分割、`PlayerStore`/`DemoWorld` マイグレーション全面パイプライン化、未接触箇所の一括 `Regex`/`enum`/セミコロン整形）: 実クライアント最終 QA 直前の大規模チャーンとデグレリスクを避けるためリリース後保守へ保留。機能不具合・ホットパス回帰・触った箇所の整形（`T22-L01..L03` / `CL L3, L6, L8`）はすべて Batch 3 で解消する。
