# Task 23（Batch 3: `60bd57a`）独立クロスモデル最終再確認・レビュー（Antigravity）

- **レビュー担当**: Antigravity（コード変更ゼロ・指摘のみ）
- **対象ブランチ / 基準コミット**: `ag/review-2`（`master` @ `559daef` へ fast-forward マージ済み）
- **レビュー対象コミット**: `codex/foundation` @ `60bd57af45793da98eec74850cf04de3c3c657cd`（`285 tests / 0 failures` 報告）
- **参照仕様**: `docs/reviews/2026-11-22_consolidated_fix_list.md` / `docs/reviews/2026-11-22_task22_antigravity.md` / `docs/reviews/2026-11-22_task22_claude-qa.md` / `docs/AGENT_STATUS.md`

---

## 1. エグゼクティブサマリー・Go / No-Go 判断

### 1.1 実機 QA 進行判断：**条件付き No-Go（小修正 1 コミット後に Go）**

Task 22 で指摘した実機 P0 ブロッカー 5 件（`T22-H01`〜`H05`）および Claude 指摘の主要不具合（`CL H1, M1..M5, M8`）は**概ね的確に修正された**。
一方で、`60bd57a` の `FIX-15` 対応で導入されたコマンド分岐変更により **新規 High 回帰 `T23-H01`（ゲーム内管理者の `/ec class <player> <classId>` が全面的に動作不能化）** が発生しており、既存の `UiQAFixesTest` もコンソール実行へ書き換えて回避されている。実機 QA 中にゲーム内管理者コマンドで職業切替・武器整理を検証する導線そのものが壊れているため、**`T23-H01`（および 1 行修正の `T23-M01`）を修正してから実機 QA へ進むこと**を推奨する（修正規模は数行であり、修正確認後は即 **Go**）。

### 1.2 Batch 3 対象 20 件（未チェック 19 件＋実機待ち `FIX-26`）判定集計

| 判定 | 件数 | 対象 FIX-ID |
|---|---:|---|
| **OK** | **12** | `FIX-07`, `FIX-39`, `FIX-41`, `FIX-43`, `FIX-44`, `FIX-48`, `FIX-27`, `FIX-28`, `FIX-30`, `FIX-31`, `FIX-33`, `FIX-38`（※実機スモーク実行待ち） |
| **実機要確認** | **1** | `FIX-26` |
| **要追加修正** | **2** | `FIX-15`（`T23-H01`）, `FIX-32`（`T23-M01`） |
| **未完了(部分対応)** | **5** | `FIX-45`, `FIX-49`, `FIX-47`, `FIX-36`, `FIX-37` |
| **合計** | **20** | — |

### 1.3 `60bd57a` 新規・残存指摘集計（4 観点 7 件）

| Severity | 件数 | ID | 主なカテゴリ |
|---|---:|---|---|
| **High** | 1 | `T23-H01` | `Bug`（`RpgPlugin.kt:939` が `/ec class <player> <classId>` を `playerCommand` へ奪い、ゲーム内管理者の職業変更コマンドが破壊＋テストがコンソール実行へ退避） |
| **Med** | 3 | `T23-M01`〜`M03` | `Bug` / `Test` / `Idiom`（`transaction_busy` が `GameException` 化漏れで `runExpectedTrade` を素通り・`FIX-45/49` 実コントローラ結合テスト不足・`PlayerStore.kt:57` の `1..100` ハードコード上限＆`as Number` 先行キャスト） |
| **Low** | 3 | `T23-L01`〜`L03` | `Style` / `Idiom` / `Test`（`smoke-test.ps1:88` に `EC_CONTENT_VALIDATION_SUCCESS` 未追加・`docs/RESOURCEPACK_HOSTING.md:98-113` 見出し挿入位置ミス・`FIX-47`/`36`/`37` 未適用残件＆dead code） |

---

## 2. Batch 3 全 20 FIX 判定表

| FIX | 判定 | 根拠 (`file:line`) | 所見・残課題 |
|---|---|---|---|
| `FIX-07` | **OK** | `docs/RESOURCEPACK_HOSTING.md:100-112` | `bind_host: "127.0.0.1"` / `public_host: "127.0.0.1"` のローカル検証設定例が追記済み。ただし `## BetterModel 連携` とその本文の間に割り込んでおり見出し位置のみ軽微な誤り（`T23-L02`）。 |
| `FIX-15` | **要追加修正** | `ClassSelectionUi.kt:73,81,85`, `RpgPlugin.kt:939,1097-1100`, `UiQAFixesTest.kt:174-182` | 確認 Dialog の `canCloseWithEscape(false)` 復帰＋`lifetime` 24 時間延長は妥当。しかし再表示用 `/ec class` を追加した際、`RpgPlugin.kt:939` が引数長を見ずに `"class"` を全プレイヤー向け分岐へ入れたため **ゲーム内管理者の `/ec class <player> <classId>` が動作不能化する High 回帰（`T23-H01`）**。 |
| `FIX-26` | **実機要確認** | `UiThemeProvider.kt:68-70`, `UiQAFixesTest.kt:52-54` | コード・ユニットテストは完了済み。実クライアント `ui.theme.mode: full` のスクリーンショット確認待ち。 |
| `FIX-39` | **OK** | `FishingController.kt:142-145,376-377`, `RequiredPackLockListener.kt:83-88`, `config.yml:16`, `FishingInteractRoutingTest.kt` | `ignoreCancelled=false`＋`shouldProcessFishingInteract(e.useItemInHand(), e.hand)` に変更し、`RequiredPackLockListener.interact` も `useItemInHand`/`useInteractedBlock` を明示 `DENY`。`config.yml:16` の `10..600` コメントも追記済み。実機釣り操作は QA 項目として確認。 |
| `FIX-41` | **OK** | `ResourcePackController.kt:174-178,192-213` | `send()` `catch` 節で `mustDisconnect` の真偽に関わらず必ず `releaseRequiredWait(..., continueJoin = !mustDisconnect)` を実行。`beginRequiredJoin` 開始時に `scheduleRequiredTimeout` を事前武装し、dispatch `Runnable` も `try/catch` で保護済み（副産物として `shouldDispatch` が未使用化、`T23-L03`）。 |
| `FIX-43` | **OK** | `ResourcePackController.kt:46,146,177,210,278,323-324,332,347,358`, `ResourcePackControllerIntegrationTest.kt:15-49` | `enforcedLoadedPlayers` 導入により保存済み `OFF`＋`required: true` 適用時に `hasResourcePack()` が `true` を返すよう修正、終了・解除時のクリアも網羅。送信失敗キックも `ec.resource_pack.required_apply_failed` へ統一済み。 |
| `FIX-44` | **OK** | `ResourcePackController.kt:52-58,190-191,268-271` | `beginRequiredJoin` の無条件ルミナ転送と `player.isInvulnerable = true` を撤去し、`RequiredPackLockListener`（`movement`/`damage`/`target`/`food`）で保護する設計へ統一。既存プレイヤーのログアウト座標保全とクラッシュ時無敵残留の両方を解消。 |
| `FIX-45` | **未完了(部分対応)** | `ResourcePackControllerIntegrationTest.kt`, `FishingInteractRoutingTest.kt` | `ACCEPTED`→`SUCCESSFULLY_LOADED`（`OFF` 上書き含む）および `DECLINED`→未保存確認の MockBukkit 結合テスト 2 件と釣りルーティングテスト 2 件を追加。ただしタイムアウト・延長上限 3 回・送信例外・高速再ログイン・`bypass` 変化・座標保全、および `FishingController.interact` 実リスナー経由の結合テストは未追加（`T23-M02`）。 |
| `FIX-47` | **未完了(部分対応)** | `ResourcePackController.kt:18-43,52,428`, `GamePortPackServer.kt:163-164,379-386`, `ResourcePackDistribution.kt:243` | `JoinState` の通常クラス化、重複 `terminalStatuses` 削除、`serverRootFromDataFolder` の null 安全＋`spigot.yml`/`worldContainer` フォールバック、最低転送速度 128 KB/s 緩和は完了。一方 `ResourcePackController.kt:18-43` の 26 個の `settings.*` 別名フィールドと `GamePortPackServer.kt:163-164` の重複 `matchesPrefix` は未削除（`T23-L03`）。 |
| `FIX-48` | **OK** | `RpgPlugin.kt:967-979` | `/ec reload` で非同期 `LangCatalog.load` 完了後に `sync` 内で `language = loadedLanguage` → `reloadAdvancementPack()` → `ec.command.reload_success` 返信の順に直列実行するよう修正済み。失敗時の通知も追加。 |
| `FIX-49` | **未完了(部分対応)** | `LangCatalogTest.kt:184-187`, `ResourcePackControllerIntegrationTest.kt`, `Wave3CRegressionTest.kt` | 実リソース `lang/ja_jp.yml` / `en_us.yml` 全キーの `DETAIL_PLACEHOLDER` 走査（④）は追加済み（副次的に `docs/LANGUAGE.md` の `<newline>` 誤記載も修正）。一方 ①`send()` の `OFF` パック除去、②`deliverLocked` 切断→再接続保護、③`FishingController.tick()` 餌失敗非復帰、⑤`/ec setlevel`・`/ec flag`・パーティ XP の称号同期テストは未追加（`T23-M02`）。 |
| `FIX-27` | **OK** | `AdventureServices.kt:51-84`, `AdventureController.kt:55-68`, `RpgPlugin.kt:882,1018,1067` | `mayNeedPeriodicUpdate`（インベントリ非参照）→ `needsPeriodicUpdate`（read-only）の 2 段階判定で無変化プレイヤーの毎秒 `p.snapshot()` を排除。討伐/パーティ XP・`/ec flag`・`/ec setlevel` への `syncTitleAdvancements` 追加も確認（軽微な冗長判定 1 行のみ `T23-L03`）。 |
| `FIX-28` | **OK** | `AdventureController.kt:310-326`, `PaperInventoryAccess.kt:10,25` | `tradeBound` で非外部・非ドロップクリックを `bound()` 評価前に短絡し、死んだ `bundleSwap ||` も削除。`PaperInventoryAccess.Plan` を通常クラス化し、`remove.isEmpty()` 時の不要複製を除去（`effectiveStats` キャッシュは統合リスト通り受容・保留）。 |
| `FIX-30` | **OK** | `RpgPlugin.kt:58,629-638` | `enhancementLoreCap` を別保持し `enhancementLoreLanguage !== catalog || enhancementLoreCap != cap` へ修正。固定言語時の 100% キャッシュミスを解消。 |
| `FIX-31` | **OK** | `TreasureController.kt:31,40,87-88`, `AdventureController.kt:36,105-119,137` | `chunkCoordinates` の `floor(x).toInt() shr 4` 修正、`dialogueComponents` の `Pair<String, String>` 化、`showDialogueDialog` での `textComponent` 再利用、および `RpgMenu` 生成前の Paper Dialog 早期 return を確認。 |
| `FIX-32` | **要追加修正** | `AdventureServices.kt:38-45,115,130-169`, `AdventureController.kt:74-79,147,231,252-257,281,290-293,501,516-517`, `RpgPlugin.kt:93-94` | `QuestEngine`/`DialogueEngine`/ショップ・会話操作と `diagnostic(sender, GameException)` 移行は良好。しかし **`AdventureController.kt:501` の `acquireLock` 失敗が依然 `error("ec.error.transaction_busy")`（`IllegalStateException`）のままで、`runExpectedTrade` の `catch (GameException)` を素通りする回帰（`T23-M01`）**。 |
| `FIX-33` | **OK** | `PlayerStore.kt:57-91` | プロフィール YAML の残整数フィールドへ `boundedInt` を水平展開済み。ただし `skill_levels` の上限 `1..100` ハードコードと `as Number` 先行キャストに軽微な懸念あり（`T23-M03`）。 |
| `FIX-36` | **未完了(部分対応)** | `NpcController.kt:68`, `SimulationCommandController.kt:41`, `WorldValidation.kt:42`, `RpgPlugin.kt:985` | Batch 3 残件として明記されていたインデント崩れ 4 箇所がすべて未修正のまま残存（`T23-L03`）。 |
| `FIX-37` | **未完了(部分対応)** | `RpgPlugin.kt:888`, ほか 6 ファイル | `RpgPlugin.kt:888` の `private var lootSequence=0L` 改行のみ修正済み。6 ファイルの二重 KDoc（`AdventureServices.kt:261`, `Cooperation.kt:44`, `FishingEngine.kt:65`, `Content.kt:26`, `DefaultContentInstaller.kt:51`, `PlayerStore.kt:23`）はすべて未解消（`T23-L03`）。 |
| `FIX-38` | **OK（実機スモーク実行待ち）** | `scripts/smoke-test.ps1:68,88`, `PaperContentValidator.kt:29-45`, `RpgPlugin.kt:912-915,988`, `PaperContentValidatorTest.kt` | `smoke-test.ps1` の全角句点 `。` と起動ログ照合を修正し、`dungeonPalette`（3 種以上）と `wolfVariant`（`Registry.WOLF_VARIANT`＋同梱 `ec:` データパック）検証＋テストを追加。なお `RpgPlugin.kt:988` に追加した安定マーカー `EC_CONTENT_VALIDATION_SUCCESS` が `smoke-test.ps1:88` の照合式へ未登録（`T23-L01`）。 |

---

## 3. 前回指摘（Antigravity + Claude）クローズ状況照合

| 元 ID | 状態 | 根拠 / 備考 |
|---|---|---|
| `T22-H01`（釣り空中クリック不能） | **修正済み** | `FishingController.kt:142-145,376-377`, `RequiredPackLockListener.kt:83-88` |
| `T22-H02` / `CL H1`（職業確認 Esc 離脱） | **修正済み（副作用あり）** | `ClassSelectionUi.kt:73-85` は OK。ただし `/ec class` 追加で **`T23-H01`** が発生 |
| `T22-H03` / `CL M1, M2`（強制転送・無敵永続） | **修正済み** | `ResourcePackController.kt:52-58,190-191,268-271` |
| `T22-H04` / `CL M3`（`send()`/dispatch 例外） | **修正済み** | `ResourcePackController.kt:174-178,192-213` |
| `T22-H05` / `CL L9`（スモーク句点・パレット/狼） | **修正済み** | `scripts/smoke-test.ps1:68,88`, `PaperContentValidator.kt:29-45`（`T23-L01` 軽微残） |
| `T22-M01`（`hasResourcePack` OFF+必須） | **修正済み** | `ResourcePackController.kt:46,278,323-324` |
| `T22-M02` / `CL L12`（`/ec reload` 言語競合） | **修正済み** | `RpgPlugin.kt:967-979` |
| `T22-M03` / `CL L5`（強化 lore キャッシュ） | **修正済み** | `RpgPlugin.kt:58,629-638` |
| `T22-M04` / `CL L7, M8`（毎秒 snapshot・称号同期） | **修正済み** | `AdventureServices.kt:51-76`, `AdventureController.kt:55-68`, `RpgPlugin.kt:882,1018,1067` |
| `T22-M05` / `CL M6`（結合テスト不足） | **部分対応** | `T23-M02` 参照 |
| `T22-L01` / `CL L4, L6`（Dialog・宝箱負座標） | **修正済み** | `AdventureController.kt:36,105-119,137`, `TreasureController.kt:87-88` |
| `CL M4, M5`（`tradeBound`・`Plan` 複製） | **修正済み** | `AdventureController.kt:310-326`, `PaperInventoryAccess.kt:10,25` |
| `CL L1, L10, L11`（`JoinState`・root・128KB/s） | **修正済み** | `ResourcePackController.kt:52`, `GamePortPackServer.kt:379`, `ResourcePackDistribution.kt:243` |
| `CL L3`（`GameException` 完遂） | **要追加修正** | `T23-M01`（`transaction_busy` 漏れ） |
| `T22-L02, L03` / `CL L8`（別名・インデント・二重 KDoc） | **部分対応** | `T23-L03` 参照 |

---

## 4. 新規・残存指摘の詳細（4 観点）

### High（1 件 — 実機 QA 前ブロッカー）

#### `T23-H01` `[High][Bug/Test]` ゲーム内管理者の `/ec class <player> <classId>` が `playerCommand` に奪われて全面動作不能（`FIX-15` 回帰）
- **箇所**:
  - `src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt:939, 1065-1083, 1097-1100`
  - `src/test/kotlin/net/nakaymi/etherealchronicle/paper/UiQAFixesTest.kt:174-182`
- **原因**: `executeCommand`（939 行）で `sender is Player && args.firstOrNull() in setOf(..., "class")` と判定しているため、権限 `etherealchronicle.admin` を持つゲーム内管理者が `/ec class <player> <classId>`（引数 3 つ）を実行しても、管理者向け `"class"` 分岐（1065 行）へ到達せず `playerCommand`（1097 行）へ吸い込まれる。
- **影響**:
  1. 既に就職済みの管理者がゲーム内から `/ec class <target> <classId>` を実行すると `ec.error.class_already_selected` で必ず失敗する。
  2. 未就職の管理者が実行すると、対象プレイヤーの職業を変更する代わりに自分自身の職業選択メニューが開いてしまう。
  3. 既存テスト `UiQAFixesTest.kt:174-182` はまさにゲーム内管理者（`player.addAttachment(plugin, "etherealchronicle.admin", true)`）による `player.performCommand("ec class ...")` を検証していたが、この回帰で失敗したため `server.consoleSender` 呼び出しへ書き換えられてしまい、不具合がマスクされている。
- **修正案（Before / After）**:
```kotlin
// Before (RpgPlugin.kt:939)
if(sender is Player && args.firstOrNull() in setOf("quest","party","equipment","dungeon","shop","claim","title","codex","enhance","fish","gather","ui","class")) {

// After: 引数なし "/ec class" のみプレイヤー再選択へルーティングし、"/ec class <player> <classId>" は管理者分岐へ通す
val isPlayerSubcommand = args.firstOrNull() in setOf(
    "quest","party","equipment","dungeon","shop","claim","title","codex","enhance","fish","gather","ui",
) || (args.size == 1 && args[0] == "class")
if (sender is Player && isPlayerSubcommand) {
```
  併せて `UiQAFixesTest.kt:171-182` のゲーム内管理者 `player.performCommand("ec class ...")` 検証を復元し、通常プレイヤーの `/ec class`（未就職時再表示 / 就職済み時拒否）とゲーム内管理者の `/ec class <player> <classId>` の両方を回帰テストで固定する。

---

### Med（3 件）

#### `T23-M01` `[Med][Bug]` `transaction()` の `transaction_busy` が `GameException` ではなく `runExpectedTrade` を素通りする（`FIX-32` 漏れ）
- **箇所**:
  - `src/main/kotlin/net/nakaymi/etherealchronicle/paper/AdventureController.kt:290-293, 501`
  - `src/main/kotlin/net/nakaymi/etherealchronicle/core/AdventureServices.kt:115`
- **原因**: `runExpectedTrade`（290 行）は `catch (error: GameException)` のみに絞られたが、`transaction()`（501 行）のロック競合は依然 `error("ec.error.transaction_busy")`（`IllegalStateException`）を送出している。
- **影響**: ショップで保存処理中に連続クリックすると `runExpectedTrade` で想定内拒否として捕捉されず、上位の汎用 `catch (Exception)` へ漏れて `ec.command.failed` ラッパー付きエラーになる。また `AdventureServices.kt:115` も `catch (_: IllegalArgumentException)` のままで、`ec.error.inventory_exchange` 以外の `GameException` まで無音で握りつぶす状態が残っている。
- **修正案（Before / After）**:
```kotlin
// Before (AdventureController.kt:501)
val token=acquireLock(p.uuid) ?: error("ec.error.transaction_busy")

// After
val token=acquireLock(p.uuid) ?: throw GameException("ec.error.transaction_busy")
```
```kotlin
// Before (AdventureServices.kt:115)
try { complete(p,id,inventory) } catch(_: IllegalArgumentException) { /* ready quests remain claimable when storage is full */ }

// After
try { complete(p,id,inventory) } catch(error: GameException) { if(error.messageKey != "ec.error.inventory_exchange") throw error }
```

---

#### `T23-M02` `[Med][Test]` `FIX-45` / `FIX-49` の実コントローラ結合テストに未カバー経路が残存
- **箇所**: `src/test/kotlin/net/nakaymi/etherealchronicle/paper/ResourcePackControllerIntegrationTest.kt`, `FishingInteractRoutingTest.kt`
- **詳細**:
  - `FIX-45`: タイムアウト満了、進捗通知による延長上限（`MAX_TIMEOUT_EXTENSIONS`）、送信/dispatch 例外時の解除/切断、高速再ログイン時の旧タスク無効化、待機中の `bypass` 付与、既存プレイヤー位置保全の結合テストが未追加。また `FishingInteractRoutingTest` は `shouldProcessFishingInteract` の直接検証に留まり、カスタム釣竿を持った `PlayerInteractEvent` が `FishingController.interact` を実通過するテストは未追加。
  - `FIX-49`: `ResourcePackController.send()` の `OFF` 変更時除去、`AdventureController.deliverLocked` の切断→再接続保護、`FishingController.tick()` の餌消費失敗非復帰、`/ec setlevel`・`/ec flag`・パーティ XP の `syncTitleAdvancements` 結合テストが未追加。
- **判断**: 実機 QA のハードブロッカーではないが、`T23-H01` 修正コミットまたはリリース前テスト補強として主要経路（特にタイムアウト・`bypass` 変化・`FishingController.interact` 実通過）の追加を推奨。

---

#### `T23-M03` `[Med][Bug/Idiom]` `PlayerStore.kt:57` の `skill_levels` 上限 `100` ハードコードと `as Number` 先行キャスト
- **箇所**: `src/main/kotlin/net/nakaymi/etherealchronicle/data/PlayerStore.kt:57`
- **原因**: `p.skillLevels[k as String] = (v as Number).boundedInt(1..100, "skill_levels.$k")` と記述されている。
  1. `balance.skill_level_cap` は YAML で設定可能（読込後に `RpgPlugin.kt:241` が `1..snapshot.balance.skillLevelCap` に正規化）だが、`PlayerStore` 側が `1..100` でハードコード拒否すると将来の上限引き上げでセーブ読込失敗を起こす。
  2. `v as Number` を先に評価しているため、破損 YAML で文字列等が入った場合に `boundedInt` の診断メッセージ（`Invalid integer field: skill_levels.$k`）ではなく無印 `ClassCastException` になる。
- **修正案（Before / After）**:
```kotlin
// Before (PlayerStore.kt:57)
(d["skill_levels"] as? Map<*,*>)?.forEach { (k,v) -> p.skillLevels[k as String] = (v as Number).boundedInt(1..100,"skill_levels.$k") }

// After
(d["skill_levels"] as? Map<*,*>)?.forEach { (k,v) -> p.skillLevels[k as String] = v.boundedInt(1..1_000_000,"skill_levels.$k") }
```

---

### Low（3 件）

#### `T23-L01` `[Low][Test/Style]` `RpgPlugin.kt:988` の安定ログマーカー `EC_CONTENT_VALIDATION_SUCCESS` が `scripts/smoke-test.ps1:88` に未反映
- **箇所**: `src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt:988`, `scripts/smoke-test.ps1:88`
- **詳細**: `ec validate` 時に `EC_CONTENT_VALIDATION_SUCCESS` を出力するよう追加されたが、`smoke-test.ps1:88` の照合パターンには含まれておらず、`コンテンツ検証成功。|Content validation succeeded\.` のまま。将来的な文言変更に強くするため `'EC_CONTENT_VALIDATION_SUCCESS|コンテンツ検証成功。|Content validation succeeded\.'` へ揃えると意図が完結する。

---

#### `T23-L02` `[Low][Style]` `docs/RESOURCEPACK_HOSTING.md` の見出し挿入位置ミス
- **箇所**: `docs/RESOURCEPACK_HOSTING.md:98-113`
- **詳細**: 新規追加された `## ローカル配信の検証`（100〜112 行）が `## BetterModel 連携`（98 行）とその本文 `BetterModel は任意依存です。...`（113 行）の間に挿入されており、BetterModel の説明文がローカル配信セクションの配下にぶら下がっている。100〜112 行を 98 行の前へ移動するだけ。

---

#### `T23-L03` `[Low][Idiom/Style]` `FIX-47` / `FIX-36` / `FIX-37` の未適用残件および軽微な dead code
- **箇所**:
  - `ResourcePackController.kt:18-43`: 26 個の `settings.*` 別名フィールドが残存（`FIX-47`）。
  - `ResourcePackController.kt:509`: `RequiredPackGatePolicy.shouldDispatch` が `beginRequiredJoin` 書き換え（194 行）により本番コードから未使用化。
  - `GamePortPackServer.kt:163-164`: 157〜158 行で計算済みの `isGetPrefix` / `isHeadPrefix` を使わず `matchesPrefix` を再呼び出し（`FIX-47`）。
  - `AdventureServices.kt:67`: `if (!mayNeedPeriodicUpdate(p, areaIds)) return false` の直後に到達不能な `if (areaIds.isEmpty() && p.quests.values.none { !it.completed }) return false` がある。
  - インデント崩れ 4 箇所残存（`FIX-36`）: `NpcController.kt:68` / `SimulationCommandController.kt:41` / `WorldValidation.kt:42` / `RpgPlugin.kt:985`。
  - 二重 KDoc 6 ファイル残存（`FIX-37`）: `AdventureServices.kt:261` / `Cooperation.kt:44` / `FishingEngine.kt:65` / `Content.kt:26` / `DefaultContentInstaller.kt:51` / `PlayerStore.kt:23`。

---

## 5. 実機 QA へ進むための最短アクション（推奨）

1. **必須（No-Go 解除）**: `T23-H01` 修正（`RpgPlugin.kt:939` で `"class"` は `args.size == 1` のみ `playerCommand` へ＋`UiQAFixesTest` のゲーム内管理者テスト復元）。
2. **強く推奨（1〜2 行修正）**: `T23-M01`（`AdventureController.kt:501` と `AdventureServices.kt:115` の `GameException` 統一）、`T23-M03`（`PlayerStore.kt:57`）、`T23-L01`（`smoke-test.ps1:88`）、`T23-L02`（`docs/RESOURCEPACK_HOSTING.md:98-113`）。
3. **その後 Go**: ヘッドレススモーク `./scripts/smoke-test.ps1 -Commands 'ec validate','ec reload'` 実行 → 実クライアント QA（`FIX-26` フルテーマスクショ、`FIX-15` 職業確認 Dialog、`FIX-39` 空中クリック釣り＆ロック中拒否、`FIX-44` 再ログイン位置保全＆ダメージ遮断、`FIX-20` ショップ再描画後右クリック売却）。
