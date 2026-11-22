# Task 22（Batch 2: Wave 3B / 3C / 4 / 5 / 6）独立クロスモデル再確認・レビュー（Antigravity）

- **レビュー担当**: Antigravity（コード変更ゼロ・指摘のみ）
- **対象ブランチ / 基準コミット**: `ag/review-2`（`master` @ `9bd6d89` を fast-forward マージ済み）
- **レビュー対象コミット範囲**: `062e744..14e1be8`
  - Wave 3B (`237986c`): `Harden required resource pack gate`（`FIX-39`〜`45`）
  - Wave 3C (`7682700`): `Fix Batch 1 recheck regressions`（Batch 1 要追加修正 11 件＋`FIX-46`〜`49`）
  - Wave 4 (`595f96c`): `Optimize Paper gameplay hot paths`（`FIX-27`〜`31`）
  - Wave 5 (`ee13c75`): `Refine core and Paper service idioms`（`FIX-32`〜`37`）
  - Wave 6 (`14e1be8`): `Add content enum and smoke regressions`（`FIX-38`）
- **参照仕様**: `docs/reviews/2026-11-22_consolidated_fix_list.md` / `docs/AGENT_STATUS.md`

---

## 1. エグゼクティブサマリー

### 1.1 Batch 2 全 34 FIX 判定集計

| 区分 | 対象件数 | OK | 実機要確認 | 要追加修正 | 未完了(部分対応) |
|---|---:|---:|---:|---:|---:|
| Batch 1 再確認（Wave 3C） | 11 | 8 | 1 | 1 | 1 |
| Wave 3B（Task 20 必須ゲート） | 7 | 2 | 0 | 4 | 1 |
| Wave 3C（追加 4 件） | 4 | 1 | 0 | 1 | 2 |
| Wave 4（ホットパス最適化） | 5 | 0 | 0 | 1 | 4 |
| Wave 5（イディオム・構造・見栄え） | 6 | 0 | 0 | 0 | 6 |
| Wave 6（回帰・スモーク） | 1 | 0 | 0 | 1 | 0 |
| **合計** | **34** | **11** | **1** | **8** | **14** |

### 1.2 新規・回帰指摘サマリー（4 観点合計 13 件）

| Severity | 件数 | ID | 主なカテゴリ |
|---|---:|---|---|
| **High** | 5 | `T22-H01`〜`T22-H05` | `Bug`（実機 Paper で釣り不能化・職業確認 Dialog 離脱・再訪者強制テレポート・`send()` 例外デッドロック・日本語環境スモーク常時 FAIL） |
| **Med** | 5 | `T22-M01`〜`T22-M05` | `Bug` / `Perf` / `Test`（`hasResourcePack` 強制適用時 `false`・`/ec reload` 言語非同期競合・強化 lore キャッシュ常時ミス・`QuestEngine.event()` 戻り値未使用・純粋ヘルパー偏重テスト） |
| **Low** | 3 | `T22-L01`〜`T22-L03` | `Perf` / `Idiom` / `Style` / `Polish`（Dialog キャッシュ未使用・`settings.*` 27 フィールド残存・重複判定/インデント崩れ/二重 KDoc） |

> **最重要ブロッカー（実機テスト前に必ず修正すべき 5 件）**:
> 1. **`T22-H01` (`FIX-39` 回帰)**: `FishingController.interact` の `ignoreCancelled=true` により、実機 Paper の空中クリック（`RIGHT_CLICK_AIR` / `LEFT_CLICK_AIR`）が**誕生時から `isCancelled()==true`** 扱いで全件スキップされ、**釣りのキャストとリール操作が完全に機能しなくなる**。
> 2. **`T22-H02` (`FIX-15` 回帰)**: `ClassSelectionUi.confirm` が `canCloseWithEscape(true)` に変更されたが Esc 閉じコールバックが無いため、Esc で確認 Dialog を閉じると `confirming` が `true` のまま残り、未就職プレイヤーがメニュー再表示を回避して離脱できる。
> 3. **`T22-H03` (`FIX-44` 回帰)**: `ResourcePackController.beginRequiredJoin` が既存プレイヤー含め全員を毎回ルミナ初期スポーンへ無条件テレポートし、元のログアウト座標を復元しない。
> 4. **`T22-H04` (`FIX-41`/`FIX-43`)**: `ResourcePackController.send()` の `catch` 節で `mustDisconnect == false` 時に `releaseRequiredWait(..., continueJoin = true)` が呼ばれず待機デッドロック。`mustDisconnect == true` 時も原因不明のまま `required_declined` で切断する。
> 5. **`T22-H05` (`FIX-38` 回帰)**: `scripts/smoke-test.ps1:88` が `コンテンツ検証成功\.`（半角ピリオド）を照合しているが、`lang/ja_jp.yml:135` は `コンテンツ検証成功。`（全角句点）のため日本語デフォルト環境で `ec validate` 検証が常に FAIL する。

---

## 2. Batch 1 再確認 11 件の判定（Wave 3C: `7682700`）

| FIX | 判定 | 根拠 (`file:line`) | 所見・残課題 |
|---|---|---|---|
| `FIX-02` | **OK** | `GamePortPackServer.kt:72-75, 92-106, 376-377` | `worldContainer` 依存を `serverRootFromDataFolder` へ移行し、`unsupportedProxies()` の起動時二重走査を 1 回へ集約済み。なお `spigotConfig` / `paperConfig` 直接参照は未実施かつ `parent` null ガード不足（軽微、`T22-L03`）。 |
| `FIX-03` | **OK** | `ResourcePackDistribution.kt:84-86, 186, 212, 238-244` | ZIP サイズ比例の送信期限（5s〜180s）と、インスタンス単位 `writeWatchdog` の `close()` シャットダウンを確認。 |
| `FIX-05` | **OK** | `ResourcePackController.kt:145`, `ResourcePackRequiredPolicyTest.kt:197-201` | `OFF` 変更かつ適用済み時に `runCatching { player.removeResourcePacks(requestId) }` を実行。ただしテストは純粋関数 `shouldRemoveAppliedPack` 単体のみ（`T22-M05`）。 |
| `FIX-07` | **未完了(部分対応)** | `ResourcePackSettings.kt:50-85, 126-139`, `ResourcePackController.kt:14-16, 77-83` | `ResourcePackSettings.loadSafely` による初期化例外クラッシュ（Batch 1 最重大回帰）、`game_port` の `effectiveBindHost`、および `0.0.0.0` / `::ffff:127.x` 判定は修正済み。ただし `FIX-07` 要件にある**ローカル検証向け `bind_host: 127.0.0.1` の設定例ドキュメント追記**が未着手。 |
| `FIX-08` | **OK** | `AdventureServices.kt:87-96`, `AdventureController.kt:61, 85-93`, `RegressionTest.kt:112-142` | `completeReadyAuto` がインベントリ満杯例外 `ec.error.inventory_exchange` を保留し、`interact` もダイアログへフォールバック。MockBukkit 回帰テスト追加済み。 |
| `FIX-11` | **OK** | `AdventureController.kt:474-478, 532`, `Wave3CRegressionTest.kt:10-16` | `isCurrentDelivery(deliveries[p.uuid], claim)` の参照同一性チェックにより `finally` での後続 `deliver` ロック誤解放を防止。テストは純粋ヘルパーのみ（`T22-M05`）。 |
| `FIX-13` | **OK** | `RpgPlugin.kt:342-361` | `deferredToMailbox` 分岐でも `counts.keys.forEach(profile::discoverItem)`、`skillSlot`、`applyStats`、`p.health`、`p.closeInventory()`、`adventure.claimPending(p)` を実行するよう統一済み。 |
| `FIX-14` | **OK** | `FishingController.kt:297, 375`, `FishingUiFeedbackTest.kt:41-44` | `bitePending` を強制解除して餌無料バイパスしていた旧復旧ブロックを削除し、`shouldAdvanceFishingSession(s.bitePending)` へ一本化。 |
| `FIX-15` | **要追加修正** | `ClassSelectionUi.kt:39-40, 84`, `RpgPlugin.kt:281` | `onQuit` の `classSelectionUi.clear(e.player.uniqueId)` は実装済み。しかし `ClassSelectionUi.kt:84` を `.canCloseWithEscape(true)` に変更したことで **Esc 離脱時に `confirming` が残留し職業選択から抜けられる回帰**（`T22-H02`）が発生。 |
| `FIX-17` | **OK** | `GatheringController.kt:328`, `CodexController.kt:124-137`, `lang/ja_jp.yml`, `lang/en_us.yml`, `docs/LANGUAGE.md` | `GatheringController` の `plugin.diagnostic(...)` 統一と、図鑑弱点属性 7 種の翻訳キー化・ドキュメント追記を確認。 |
| `FIX-26` | **実機要確認** | `UiThemeProvider.kt:68-70`, `UiQAFixesTest.kt:52-54` | `-8px` の `spaceFont` を `glyph`（`uiFont`）の**前**へ移動し、Component 構成順をユニットテストで固定済み。実クライアントの `ui.theme.mode: full` 描画スクショ確認が最終ゲート。 |

---

## 3. Task 22（Wave 3B / 3C / 4 / 5 / 6）全 FIX 判定

### 3.1 Wave 3B: Task 20 必須リソースパックゲート（`237986c`）

| FIX | 判定 | 根拠 (`file:line`) | 所見・残課題 |
|---|---|---|---|
| `FIX-39` | **要追加修正** | `RequiredPackLockListener.kt:1-162`, `RpgPlugin.kt:148-150`, `FishingController.kt:141-142`, `config.yml:17-18` | 専用リスナー分離・`EventPriority.LOWEST`・`hasWaiters()` O(1) 短絡・条件付き登録・不要 `snapshot` 引数除去は良好。しかし **`FishingController.interact` を `ignoreCancelled=true` にしたことで実機 Paper の空中クリック釣りが全滅する回帰（`T22-H01`）**。また `config.yml:17-18` に `10..600` 範囲コメントが未追記。 |
| `FIX-40` | **OK** | `RequiredPackLockListener.kt:86-161`, `RpgPlugin.kt:670`, `AdventureController.kt:52`, `EnemyController.kt:97, 146` | 7 種の追加イベント（`PlayerItemConsumeEvent` / `PlayerInteractEntityEvent` / `EntityPickupItemEvent` / `PlayerSwapHandItemsEvent` / `PlayerPortalEvent` / `PlayerTeleportEvent` / `EntityTargetLivingEntityEvent`）、`Projectile.shooter` 判定、および `RpgPlugin.tick` / `AdventureController.tick` / `EnemyController` の待機中除外を確認。 |
| `FIX-41` | **要追加修正** | `ResourcePackController.kt:142-154, 166-176` | `!decision.send` / 既適用経路での `releaseRequiredWait(..., continueJoin = true)` は実装済み。しかし **`send()` の `catch` 節（166-176 行）で `mustDisconnect == false` の場合に待機解除されず永続デッドロック（`T22-H04`）**。 |
| `FIX-42` | **OK** | `ResourcePackController.kt:48-55, 193-204, 226-264, 412-414, 467-510` | `JoinState` 集約、`dispatchTask` / `timeoutTask` キャンセルとセッション同一性チェック、`inline fun debugLog`、`MIN/MAX_TIMEOUT_SECONDS` 定数化を確認。軽微な残件として 198 行の dead code と `terminalStatuses` 重複あり（`T22-L02`）。 |
| `FIX-43` | **要追加修正** | `ResourcePackController.kt:174, 234-247, 267-268, 287-327` | `ACCEPTED` / `DOWNLOADED` の `debugLog` と上限 3 回延長、タイムアウト時 `bypass` 再評価、`DISCARDED` の `bypass` 尊重は実装済み。しかし **`hasResourcePack()`（267-268 行）が `required: true` 強制適用＋保存済み `OFF` プレイヤーに `false` を返すバグ（`T22-M01`）**、および **`send()` 失敗時の `required_declined` 誤メッセージ切断（`T22-H04`）** が未対応。 |
| `FIX-44` | **要追加修正** | `ResourcePackController.kt:187-191, 258-260`, `RpgPlugin.kt:252-265` | `originalInvulnerable` 保存/復元、および `profiles[player.uniqueId] = profile` を `whenRequiredPackReady` 内へ移して未適用拒否時の新規プロフィール未保存化は OK。しかし **`beginRequiredJoin`（188-190 行）が既存プレイヤー全員を毎回ルミナ初期スポーンへ無条件テレポートし、元の位置を復元しない回帰（`T22-H03`）**。 |
| `FIX-45` | **未完了(部分対応)** | `ExternalManagedPackTest.kt`, `ResourcePackRequiredPolicyTest.kt`, `RequiredPackLockListenerTest.kt` | `FIX-45` の重複テスト整理、`RequiredPackGatePolicy.statusAction` 純粋テーブル、および `RequiredPackLockListener` の MockBukkit テスト 2 件は追加済み。しかし **要求された MockBukkit 結合テスト（`SUCCESSFULLY_LOADED` 解除、`ACCEPTED`/`DOWNLOADED` 延長、`DECLINED`/タイムアウト/高速再ログイン/`bypass` 切替、拒否時新規プロフィール未保存）が未実装（`T22-M05`）**。 |

### 3.2 Wave 3C: Batch 1 追加指摘 4 件（`7682700`）

| FIX | 判定 | 根拠 (`file:line`) | 所見・残課題 |
|---|---|---|---|
| `FIX-46` | **OK** | `AdventureController.kt:400-408, 487-494, 535-540`, `Wave3CRegressionTest.kt:18-24` | `beforeStorage` クローンを `skipIfUnchanged` 後へ遅延し、`profileTransaction` へ `ProfileTransactionGuard` を導入済み。 |
| `FIX-47` | **未完了(部分対応)** | `AdventureController.kt:31`, `PaperInventoryAccess.kt:12, 41`, `ResourcePackController.kt:15-41, 107-115`, `GamePortPackServer.kt:160-162, 182, 258-274`, `ResourcePackDistribution.kt:84-86, 212` | `bundledInfo` の `lazy` 化、`LockToken` 型化、`exchange` 重複排除、`0..35` チェック、`GamePortPackServer` 整理・`writeWatchdog` 終了は対応済み。しかし **`ResourcePackController.kt:15-41` の 27 個の `settings.*` 別名フィールド削除が未実施（`T22-L02`）**。 |
| `FIX-48` | **要追加修正** | `GamePortPackServer.kt:250`, `LangCatalog.kt:69-70, 88-97`, `AdventureController.kt:139`, `RpgPlugin.kt:109, 122, 146, 952-967` | 404 の `Cache-Control: no-store`、`DETAIL_PLACEHOLDER` 境界強化＋短縮タグ除外、`.take(54)`、起動時 `LangCatalog.load` 非同期化は OK。しかし **`/ec reload`（`RpgPlugin.kt:952-967`）の非同期 `LangCatalog.load` が `reloadAdvancementPack()` と `reload_success` 返信と競合する回帰（`T22-M02`）**。 |
| `FIX-49` | **未完了(部分対応)** | `ResourcePackRequiredPolicyTest.kt`, `RegressionTest.kt`, `Wave3CRegressionTest.kt`, `FishingUiFeedbackTest.kt`, `UiQAFixesTest.kt`, `LangCatalogTest.kt` | 7 領域すべてに何らかのテストは追加されたが、②`send()` / ③`deliverLocked()`・`profileTransaction()` / ④`FishingController.tick()` / ⑦実 `lang/*.yml` 全キー走査が**1 行ヘルパー単体検証に留まり、実コントローラや実 YAML を通していない（`T22-M05`）**。 |

### 3.3 Wave 4: ホットパス最適化（`595f96c`）

| FIX | 判定 | 根拠 (`file:line`) | 所見・残課題 |
|---|---|---|---|
| `FIX-27` | **未完了(部分対応)** | `AdventureServices.kt:61, 78, 82, 85`, `AdventureController.kt:54-63`, `RpgPlugin.kt:468, 668-675, 837` | `applyStats` 再利用、`tick()` からの `syncTitleAdvancements` 除去、`PlayerMoveEvent` の同一ブロック早期 return は OK。しかし **`QuestEngine.event()` の `Boolean` 戻り値が全呼び出し元で未使用で、`AdventureController.tick()` が毎秒 `transaction` → `p.snapshot()` ディープコピーを継続実行している（`T22-M04`）**。 |
| `FIX-28` | **未完了(部分対応)** | `PaperInventoryAccess.kt:10-55`, `AdventureController.kt:305-312, 332`, `RpgPlugin.kt:68-75` | `PaperInventoryAccess` の単一 `Plan(afterRemoval, finalContents)`、`tradeBound` の `bound()` 再利用、`safeAreas(world)` 索引化は OK。一方 **プレイヤー別 `effectiveStats` キャッシュ（装備/強化/レベル/バフ変更時無効化）が未実装**。 |
| `FIX-29` | **未完了(部分対応)** | `RpgPlugin.kt:497-510`, `docs/AGENT_STATUS.md:358` | Codex 自己申告通り、`AdvancementDatapack.install` の非同期 I/O 分離および `player.saveData()` 整理・方針記載が未実施。 |
| `FIX-30` | **要追加修正** | `RpgPlugin.kt:619-629` | `enhancementLoreLines` キャッシュ自体は追加されたが、**`enhancementLoreLines.size != cap * 2` 条件が `language: ja_jp` / `en_us` 固定設定時に常に `true` となり 100% キャッシュミスするバグ（`T22-M03`）**。 |
| `FIX-31` | **未完了(部分対応)** | `WorldContent.kt:230-237`, `Game.kt:279-297`, `GatheringController.kt:47, 125, 201`, `TreasureController.kt:23, 30, 39-41, 55`, `AdventureController.kt:36, 43, 101, 125` | `enemiesByArea`、`recordEnemyKillIndexed`、`sitesByChunk`、`chestsByChunk`＋`isChunkLoaded`、`dialogueComponents` は導入済み。ただし **インライン `Regex(...)` 約 20 箇所の定数化、`PlayerStore` の `Yaml` 再利用、`TreasureController` の所有ワールド判定キャッシュが未着手**であり、`showDialogueDialog` もキャッシュ済み `textComponent` を再利用していない（`T22-L01`）。 |

### 3.4 Wave 5: イディオム・構造・見栄え（`ee13c75`）

| FIX | 判定 | 根拠 (`file:line`) | 所見・残課題 |
|---|---|---|---|
| `FIX-32` | **未完了(部分対応)** | `AdventureServices.kt:142-203`, `AdventureController.kt:268`, `GameExceptionTest.kt` | `GameException` / `requireGame` は `TradeEngine` のみに導入済み。他エンジンは従来の `require`/`error` のままで、`AdventureController.kt:268` の `error.message?.takeIf { it.startsWith("ec." + "error.") }` 文字列判定も残存。 |
| `FIX-33` | **未完了(部分対応)** | `ComponentText.kt`, `FishingController.kt`, `GatheringController.kt`, `DungeonController.kt`, `PlayerStore.kt:16-21, 55` | `Component.plainText()`、`monotonicMillis()`、`boundedInt`（ただし `best_enhancement_level` 1 項目のみ適用）は対応。文字列管理の `enum`/`sealed` 化、冗長な `isOnline`/キャスト除去、UUID マップのセッションオブジェクト集約は未実施。 |
| `FIX-34` | **未完了(部分対応)** | `RpgPlugin.kt`（1,200 行超）, `docs/AGENT_STATUS.md:359` | Codex 自己申告通り、`RequiredPackLockListener` 以外の責務分割（`AdminCommandHandler` / `EnhancementService` / `StarterGearService` 等）は未実施。 |
| `FIX-35` | **未完了(部分対応)** | `PlayerStore.kt:99-125`, `DemoWorld.kt:95-191`, `docs/AGENT_STATUS.md:359` | Codex 自己申告通り、`PlayerStore` のステップパイプライン化・`DemoWorld.migrateStep`・新規生成時ログ `"v4"`/`"v5"` 整合は未実施。 |
| `FIX-36` | **未完了(部分対応)** | `WorldDefinitions.kt:4`, `AdventureController.kt:307-315`, `AdventureServices.kt:210` | `WorldIds.DEMO` 抽出と `tradeBound` 述語化は実施済み（ただし 3 ファイルでインデント崩れ、`T22-L03`）。既定ダンジョン ID・折り返し幅・履歴上限・ダンジョン座標の定数化、`AdventureServices.kt:210` の 20 条件 `require` 分割、主要メソッド KDoc 補完は未実施。 |
| `FIX-37` | **未完了(部分対応)** | `RpgPlugin.kt:882`, `AdventureServices.kt`, `Cooperation.kt`, `FishingEngine.kt`, `Content.kt`, `DefaultContentInstaller.kt`, `PlayerStore.kt` | 未使用 `areaCodexPercent` 削除のみ確認。6 ファイルの二重 KDoc、`RpgPlugin.kt:882` の `}    private var lootSequence=0L`、1 行 `;` 詰め込み整形は未解消。 |

### 3.5 Wave 6: 回帰テスト・スモーク（`14e1be8`）

| FIX | 判定 | 根拠 (`file:line`) | 所見・残課題 |
|---|---|---|---|
| `FIX-38` | **要追加修正** | `PaperContentValidator.kt`, `RpgPlugin.kt:895-906`, `PaperContentValidatorTest.kt`, `scripts/smoke-test.ps1:87-91`, `lang/ja_jp.yml:135` | `Material`/`Block`/`Particle`/`EntityType` の起動時・`/ec validate` 検証と単体テストは良好。しかし **`scripts/smoke-test.ps1:88` の正規表現が半角 `\.` のため日本語既定環境で常時 FAIL するバグ（`T22-H05`）** があり、実機スモーク実行も未完了。 |

---

## 4. 新規・回帰指摘の詳細（4 観点）

### High（実機テスト前ブロッカー: 5 件）

#### `T22-H01` `[High][Bug]` `FishingController.interact` の `ignoreCancelled=true` が実機 Paper の釣り操作を全滅させる（Wave 3B / `FIX-39` 回帰）
- **箇所**: `src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingController.kt:141-142`
- **原因**: Wave 3B で必須パックロックを尊重するため `ignoreCancelled=false` → `ignoreCancelled=true` に変更された。しかし Bukkit / Paper（`CraftEventFactory.callPlayerInteractEvent`）の仕様上、`Action.RIGHT_CLICK_AIR` と `Action.LEFT_CLICK_AIR` は**イベント生成直後から `useInteractedBlock = Event.Result.DENY`** で初期化されるため、非推奨の `PlayerInteractEvent.isCancelled()` は**どのプラグインもキャンセルしていなくても常に `true`** を返す。
- **影響**: 実機 Paper 上で水辺に向けて釣り竿を右クリック（キャスト / リール引き）または左クリック（いなす）しても、`FishingController.interact` が**一度も呼ばれず釣り不能**になる（MockBukkit テストは `isCancelled=false` でイベントを生成するため検出されない）。
- **修正案（Before / After）**:
```kotlin
// Before (FishingController.kt:141-143)
@EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
fun interact(e:PlayerInteractEvent) {
    if(e.hand!=EquipmentSlot.HAND) return

// After (RequiredPackLockListener は event.isCancelled=true で useItemInHand も DENY にする)
@EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false)
fun interact(e:PlayerInteractEvent) {
    if(e.useItemInHand()==org.bukkit.event.Event.Result.DENY) return
    if(e.hand!=EquipmentSlot.HAND) return
```
  ※ `GatheringController.interact` は `Action.RIGHT_CLICK_BLOCK` 専用のため `ignoreCancelled=true` で問題ない。

---

#### `T22-H02` `[High][Bug]` 職業確認 Dialog の Esc 離脱で `confirming` が残留し未就職プレイヤーが自由行動できる（Wave 3C / `FIX-15` 回帰）
- **箇所**: `src/main/kotlin/net/nakaymi/etherealchronicle/paper/ClassSelectionUi.kt:71-88`
- **原因**: `FIX-15` 対応として `.canCloseWithEscape(true)`（84 行）へ変更されたが、Esc 閉じを検知して `confirming.finish(player.uniqueId)` と `onCancel(player)` を呼ぶ経路が無い。
- **影響**: 初回プレイヤーが職業をクリックして確認 Dialog を開き、Esc キーを押すと:
  1. `yes` / `no` いずれの `customClick` も発火せず `confirming` が `true` のまま固定される。
  2. 職業選択画面が再表示されず、`classId == null` のままワールドを自由に歩ける状態になる。
  3. 次に何らかの経路で `open()` を呼ばない限り `confirm()` も `if(!confirming.begin(...)) return` でブロックされ続ける。
- **修正案（Before / After）**:
```kotlin
// Before (ClassSelectionUi.kt:81-86)
entry.empty().base(DialogBase.builder(confirmationTitle(player, definition))
    .body(listOf(DialogBody.plainMessage(confirmationText(player, definition))))
    .canCloseWithEscape(true)
    .build())

// After: (案A) Esc を閉じさせず ClickCallback 期限切れロックだけ防ぐ（lifetime を十分長くし、onClose/tick 監視も併用）
val options = ClickCallback.Options.builder().uses(1).lifetime(Duration.ofHours(24)).build()
entry.empty().base(DialogBase.builder(confirmationTitle(player, definition))
    .body(listOf(DialogBody.plainMessage(confirmationText(player, definition))))
    .canCloseWithEscape(false)
    .build())
// または (案B) Paper の Dialog 閉じ/タイムアウト検知が無い前提なら、Esc 許可する代わりに
// onCancel 用のタイムアウト/移動・操作フックで confirming を解除し menu(player) を再表示する。
```

---

#### `T22-H03` `[High][Bug]` `beginRequiredJoin` が既存プレイヤー全員を毎ログイン時にルミナへ強制テレポートし元位置を失う（Wave 3B / `FIX-44` 回帰）
- **箇所**: `src/main/kotlin/net/nakaymi/etherealchronicle/paper/ResourcePackController.kt:187-191`
- **原因**: 待機中の安全確保のため、`beginRequiredJoin` が `player.hasPlayedBefore()` や現在位置に関係なく毎回 `player.teleport(DemoWorld.luminaSpawn(world))` を実行しており、移動前の座標を保存・復元していない。
- **影響**: 必須リソースパック有効時、第 2 章エリアや遠方でログアウトした既存プレイヤーが再ログインするたびにルミナ初期地点 `(0.5, 65.0, 0.5)` へ強制送還され、拒否/タイムアウトで切断されると元のログアウト座標もルミナで上書きされる。
- **修正案（Before / After）**:
```kotlin
// Before (ResourcePackController.kt:187-191)
val state = JoinState(originalInvulnerable = player.isInvulnerable)
plugin.server.getWorld(DemoWorld.NAME)?.let { world ->
    player.teleport(DemoWorld.luminaSpawn(world))
}

// After: 待機前の現在座標を JoinState に保存し、releaseRequiredWait(continueJoin = true) 時に
// 既存プレイヤーかつ安全確認後（または quit 前に戻して保存）に復元する。
// もしくは待機中は isInvulnerable + 移動/ターゲット/ダメージキャンセル（RequiredPackLockListener で既に保護済み）
// に委ね、強制テレポートは !player.hasPlayedBefore() のみ行う。
```

---

#### `T22-H04` `[High][Bug]` `ResourcePackController.send()` の `catch` 節で非切断ケースがデッドロック＋誤切断メッセージ（Wave 3B / `FIX-41`, `FIX-43`）
- **箇所**: `src/main/kotlin/net/nakaymi/etherealchronicle/paper/ResourcePackController.kt:166-176`
- **原因**: `player.sendResourcePacks(...)` が例外を投げた際、`mustDisconnect` が `true` の分岐しか `releaseRequiredWait` を呼ばない。また `mustDisconnect == true` の切断理由が固定で `"ec.resource_pack.required_declined"` になっている。
- **影響**:
  1. 例外発生の瞬間に `bypass` 付与済みまたは配信不能化（`!isDeliveryAvailable()`）になっていた場合、`mustDisconnect == false` かつ `isAwaitingRequiredPack == true` のまま抜け、タイムアウトタスクも予約されていないため**ログアウトするまで永続ロック**。
  2. サーバー側送信エラーでキックされたプレイヤーへ「拒否された」という誤案内が表示される。
- **修正案（Before / After）**:
```kotlin
// Before (ResourcePackController.kt:169-175)
val mustDisconnect = decision.required &&
    !player.hasPermission("etherealchronicle.pack.bypass") &&
    isDeliveryAvailable()
if (mustDisconnect && isAwaitingRequiredPack(player.uniqueId)) {
    releaseRequiredWait(player.uniqueId, continueJoin = false)
    if (player.isOnline) player.kick(plugin.message(player, "ec.resource_pack.required_declined"))
}

// After
val mustDisconnect = decision.required &&
    !player.hasPermission("etherealchronicle.pack.bypass") &&
    isDeliveryAvailable()
if (isAwaitingRequiredPack(player.uniqueId)) {
    releaseRequiredWait(player.uniqueId, continueJoin = !mustDisconnect)
    if (mustDisconnect && player.isOnline) {
        player.kick(plugin.message(player, "ec.resource_pack.required_apply_failed"))
    }
}
```

---

#### `T22-H05` `[High][Bug]` `scripts/smoke-test.ps1` の `ec validate` 成功判定が日本語句点と不一致で常時 FAIL（Wave 6 / `FIX-38` 回帰）
- **箇所**: `scripts/smoke-test.ps1:88` / `src/main/resources/lang/ja_jp.yml:135`
- **原因**: `lang/ja_jp.yml:135` は `validate_success: "コンテンツ検証成功。"`（全角句点 `。`）だが、`scripts/smoke-test.ps1:88` は `'コンテンツ検証成功\.|Content validation succeeded\.'`（半角ピリオド `\.`）を検索している。
- **影響**: コンソール既定言語（日本語）で `./scripts/smoke-test.ps1` を実行すると、`ec validate` が正常終了しても必ず `SMOKE TEST: FAIL` になる。
  ※ 既存の 56 行 `Wait-Log '準備が完了' 30` も `RpgPlugin.kt:200` の英語ログ `"Adventure systems are ready."` と一致しておらず、毎回 30 秒フル待機している点も併せて修正推奨。
- **修正案（Before / After）**:
```powershell
# Before (scripts/smoke-test.ps1:56, 88)
[void](Wait-Log '準備が完了' 30)
...
-not (Select-String -Path $log -Pattern 'コンテンツ検証成功\.|Content validation succeeded\.' -Quiet)

# After
[void](Wait-Log '準備が完了|Adventure systems are ready\.' 30)
...
-not (Select-String -Path $log -Pattern 'コンテンツ検証成功。|Content validation succeeded\.' -Quiet)
```

---

### Med（機能不具合・最適化未効・テスト不足: 5 件）

#### `T22-M01` `[Med][Bug]` `hasResourcePack()` が `required: true` 強制適用された `OFF` 設定プレイヤーに `false` を返す（`FIX-43` 未対応）
- **箇所**: `src/main/kotlin/net/nakaymi/etherealchronicle/paper/ResourcePackController.kt:267-268`
- **原因**: `preferences[playerId] == PackPreference.OFF` のプレイヤーに `required: true` で強制配信して `SUCCESSFULLY_LOADED` になっても、`hasResourcePack` が `preferences[playerId] != PackPreference.OFF` を要求するため `false` を返す。
- **影響**: パック適用済みにも関わらず `UiThemeProvider` / `createItemForPlayer` / `refreshPackItemModels` がパック未使用扱いになり、UI やアイテムモデルが欠落する。
- **修正案**: `loadedPlayers` に加え `enforcedLoadedPlayers` を記録するか、`fun hasResourcePack(playerId: UUID): Boolean = playerId in loadedPlayers && (externalManaged || preferences[playerId] != PackPreference.OFF || isEnforcedFor(playerId))` とする。

---

#### `T22-M02` `[Med][Bug]` `/ec reload` の非同期 `LangCatalog.load` が `reloadAdvancementPack()`・完了返信と競合する（Wave 3C / `FIX-48` 回帰）
- **箇所**: `src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt:952-967`
- **原因**: `CompletableFuture.supplyAsync { LangCatalog.load(...) }` を待たずに直後の 961 行で `reloadAdvancementPack()`（内部で `language.component(...)` を参照）、967 行で `ec.command.reload_success` 返信を実行している。
- **影響**: `lang/*.yml` や `config.yml` の `language:` を編集して `/ec reload` を実行しても、直後の進捗データパック再生成と完了メッセージには旧言語カタログが使われ、もう 1 回 `/ec reload` するまで反映されない。
- **修正案**: 既存の `content.reloadAsync` 側（または `reloadConfig()` 後の `supplyAsync` チェーン）で `LangCatalog.load` を先に完了させてから `sync { language = loadedLanguage; reloadAdvancementPack(); ...; sender.sendMessage(...) }` を実行する。

---

#### `T22-M03` `[Med][Perf/Bug]` `enhancementLoreLines` キャッシュが `language: ja_jp` / `en_us` 固定設定で 100% ミスする（Wave 4 / `FIX-30`）
- **箇所**: `src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt:619-629`
- **原因**: `if (enhancementLoreLanguage !== catalog || enhancementLoreLines.size != cap * 2)` と判定しているが、`config.yml` で `language: ja_jp` や `en_us` を固定している場合、`LangCatalog.component(locale, ...)` は引数 `locale` ではなく固定言語を返すため `ja_jp` と `en_us` が同一文字列になり、`Set.size == cap`（`!= cap * 2`）となって**毎回キャッシュを再生成**する。
- **修正案（Before / After）**:
```kotlin
// Before (RpgPlugin.kt:55-56, 620)
private var enhancementLoreLanguage: LangCatalog? = null
private var enhancementLoreLines: Set<String> = emptySet()
if (enhancementLoreLanguage !== catalog || enhancementLoreLines.size != cap * 2) {

// After
private var enhancementLoreLanguage: LangCatalog? = null
private var enhancementLoreCap: Int = -1
private var enhancementLoreLines: Set<String> = emptySet()
if (enhancementLoreLanguage !== catalog || enhancementLoreCap != cap) {
    ...
    enhancementLoreLanguage = catalog
    enhancementLoreCap = cap
}
```

---

#### `T22-M04` `[Med][Perf]` `QuestEngine.event()` の `Boolean` 戻り値が未使用で毎秒の `p.snapshot()` ディープコピーが残存（Wave 4 / `FIX-27`）
- **箇所**: `src/main/kotlin/net/nakaymi/etherealchronicle/paper/AdventureController.kt:54-63`
- **原因**: Wave 4 で `QuestEngine.event()` に変更有無を返す `Boolean` 戻り値を追加したが、`AdventureController.tick()` を含め全呼び出し元が戻り値を受け取っていない。`AdventureController.tick()` はオンラインプレイヤー全員に対し毎秒 `transaction(player, ..., skipIfUnchanged = true)` を呼び、`transaction` 入口の `val before = p.snapshot()`（全 Map/Set コピー）を毎秒実行し続けている。
- **修正案**: `transaction` に入る前に「進行中クエストに `VISIT_AREA` / `INVENTORY` / 自動完了可能目標があるか」を read-only 判定して早期スキップするか、`QuestEngine.hasPendingAutoCheck(profile, areaId)` のような軽量ガードを導入する。

---

#### `T22-M05` `[Med][Test]` `FIX-45` / `FIX-49` のテストが 1 行純粋ヘルパー偏重で実コントローラ・実 YAML を検証していない
- **箇所**: `src/test/kotlin/net/nakaymi/etherealchronicle/paper/Wave3CRegressionTest.kt`, `ResourcePackRequiredPolicyTest.kt`, `RequiredPackLockListenerTest.kt`, `LangCatalogTest.kt`
- **詳細**:
  - `FIX-45`: `RequiredPackLockListenerTest` は 2 ケース（interact / pickup）のみ。`ResourcePackController` 経由の MockBukkit 結合テスト（`SUCCESSFULLY_LOADED` 解除、`ACCEPTED`/`DOWNLOADED` 延長上限、`DECLINED`/タイムアウト/高速再ログイン/`bypass` 変更、未適用拒否時の新規プロフィール未保存）が無い。
  - `FIX-49`: `shouldRemoveAppliedPack` / `isCurrentDelivery` / `ProfileTransactionGuard` / `shouldAdvanceFishingSession` は 1〜3 行の切り出し関数をテストしているだけで、`ResourcePackController.send()`・`AdventureController.deliverLocked()`・`FishingController.tick()` の実経路を通っていない。また `LangCatalogTest` は実リソース `src/main/resources/lang/ja_jp.yml` / `en_us.yml` の全キー走査をしていない。
  - 実際、MockBukkit 結合テストが不足していた結果、`T22-H01`〜`T22-H04` や `T22-M01`〜`T22-M03` がすり抜けている。

---

### Low（パフォーマンス微改善・イディオム・スタイル: 3 件）

#### `T22-L01` `[Low][Perf]` 会話 Dialog 表示時のキャッシュ未活用と不要なメニュー生成（Wave 4 / `FIX-31`）
- **箇所**: `src/main/kotlin/net/nakaymi/etherealchronicle/paper/AdventureController.kt:101-108, 125`
- **詳細**:
  1. `dialogueComponents` のキーに `player.locale().toString()` を含めているが、`definition.text` はロケール非依存の固定 YAML 文字列。
  2. `showDialogueDialog`（125 行）が既にキャッシュ済みの `textComponent` を使わず `mm.deserialize(definition.text)` を再実行している。
  3. Paper Dialog を使う場合でも 102-108 行で `RpgMenu` インベントリと `ItemStack` 群を毎回生成してから捨てている。
- **修正案**: `dialogueComponents` キーを `id to at` にして `showDialogueDialog` へ `textComponent` を渡し、`if (plugin.paperUiEnabled)` を `RpgMenu` 生成より前に置く。

---

#### `T22-L02` `[Low][Idiom]` `ResourcePackController.kt` の残存ボイラープレート・dead code・重複定数（Wave 3B / `FIX-42`, Wave 3C / `FIX-47`）
- **箇所**: `src/main/kotlin/net/nakaymi/etherealchronicle/paper/ResourcePackController.kt:15-41, 198-201, 416-423`
- **詳細**:
  1. `FIX-47` で削減対象だった 27 個の `settings.*` 別名プロパティ（15-41 行）がそのまま残存。
  2. 194 行で `RequiredPackGatePolicy.shouldDispatch(..., player.isOnline, ...)` が `false` なら `return` した直後の 198 行に到達不能な `if (!player.isOnline)` がある（しかもオフライン切断時は `onQuit` が既に `releaseRequiredWait` 済み）。
  3. 416-423 行の `private companion object { val terminalStatuses = ... }` が 474-480 行の `RequiredPackGatePolicy.terminalStatuses` と完全重複。

---

#### `T22-L03` `[Low][Idiom/Style/Polish]` Wave 3C / Wave 5 で生じた重複判定・インデント崩れ・残存整形課題
- **箇所**:
  - `GamePortPackServer.kt:154-162`: `isGetPrefix` / `isHeadPrefix` を 154-155 行で計算した直後に 160-161 行で同じ `matchesPrefix` を再計算している。また `serverRootFromDataFolder`（72 行）は `parent` null をガードしておらず、`server.spigot().spigotConfig` / `paperConfig` 参照も未利用。
  - `AdventureController.kt:307-315`: 308 行で `if (bundleSwap) { e.isCancelled = true; return }` と早期 return しているため、315 行の `if (bundleSwap || touchesExternal || dropsBound)` の `bundleSwap ||` は常に `false`。
  - `NpcController.kt:68` / `SimulationCommandController.kt:41` / `WorldValidation.kt:42` / `RpgPlugin.kt:965`: インデント幅が前後ブロックとズレている。
  - `RpgPlugin.kt:882`: `}    private var lootSequence=0L` が閉じ波括弧と同一行に残存。
  - 二重 KDoc 残存（6 ファイル）: `AdventureServices.kt` / `Cooperation.kt` / `FishingEngine.kt` / `Content.kt` / `DefaultContentInstaller.kt` / `PlayerStore.kt`。

---

## 5. 次フェーズへの優先度推奨

1. **P0（実機 Paper・スモーク実行前に必須）**: `T22-H01`（釣り空中クリック復帰）、`T22-H02`（職業確認 Esc 離脱防止）、`T22-H03`（既存プレイヤー座標保全）、`T22-H04`（`send()` 例外デッドロック＋誤メッセージ）、`T22-H05`（`smoke-test.ps1` 全角句点・起動ログ照合）。
2. **P1（実機品質・安定性）**: `T22-M01`（`hasResourcePack` 必須強制時 `true`）、`T22-M02`（`/ec reload` 言語非同期順序）、`T22-M03`（`enhancementLoreLines` キャッシュ修正）、`T22-M05`（`FIX-45`/`FIX-49` の MockBukkit 実経路テスト補完）。
3. **P2（スコープ整理・残タスク判断）**: Wave 4/5 の未完了 10 件（`FIX-27` の `AdventureController.tick()` read-only ガード、`FIX-28` の `effectiveStats` キャッシュ、`FIX-29` の `AdvancementDatapack` 非同期化、`FIX-31`〜`37`）について、次バッチで実装するか「現状受容（WontFix/Deferred）」とするかをリード側でトリアージ推奨。
