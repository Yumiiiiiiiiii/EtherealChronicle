# 統合修正・リファクタリングリスト（2026-11-22）

- **統合元レビュー**:
  - `docs/reviews/2026-11-22_master_claude-qa.md`（Claude-QA / `9b0cb4b`）
  - `docs/reviews/2026-11-22_master_antigravity.md`（Antigravity-QA-3 / `67cb2a0`）
  - Task 20 差分（`8f3acfa`）のリード確認メモ
- **運用ルール**: `AGENTS.md` の「Cross-model review loop」に従い、Wave 単位でコミット＋全テスト実行し、完了後に実装者以外の全モデル（Claude / Antigravity）が再確認して全項目クローズ（または理由付き受容）となるまで完了としない。
- **実行計画**:
  - **Batch 1（Wave 1〜3）**: High / Med のセキュリティ・正しさバグ修正＋回帰テスト
  - **Batch 2（Wave 4〜6）**: 高速化 (`Perf`)・書き方の違和感 (`Idiom`)・見栄え/構成分割 (`Style` / `Polish`)・残テスト拡充

---

## Wave 1 — Security / Resource Pack / Netty / HTTP（Batch 1）

- [ ] **FIX-01 `[High][Security]`** (`AG H-01`) `GamePortPackServer.kt:132-162`
  - `SNIFFING` で先頭 `'G'`/`'H'` を 1 バイト保留した時点で `idleTimeout` を開始し、`MINECRAFT` パススルー・`channelInactive`・`handlerRemoved` で確実にキャンセルする（1 バイト Slowloris DoS 対策）。
- [ ] **FIX-02 `[High][Security]`** (`AG H-02`) `GamePortPackServer.kt:90-94`
  - `unsupportedProxies()` で `server.spigot().spigotConfig`（`settings.bungeecord`）および `paperConfig`（`proxies.velocity.enabled`）を検査し、既存の `TCPShield` プラグイン検知と併用する。
- [ ] **FIX-03 `[High][Security]`** (`AG H-08`, `CL M9`) `ResourcePackDistribution.kt:92-96,170-180`
  - `EmbeddedPackServer.writeResponse()` に書き込み全体デッドライン（watchdog 強制 close）を追加し Slow-Reader DoS を防止。
  - 非 200 応答（404 等）には `Cache-Control: no-store` を返す。
  - `acceptConnections` の `IOException` リトライに短いバックオフを入れる（EMFILE 時の CPU スピン防止）。
- [ ] **FIX-04 `[Med][Security/Perf]`** (`AG M-01, L-05`, `CL M10, P8`) `GamePortPackServer.kt:139,164-175,272-324`
  - `PackConnectionLimiter` にグローバル共有の `globalNextSendNanos` を導入し、IP 切断で帯域予算がリセットされないようにする（共有帯域の KDoc と一致させる）。
  - `\r\n\r\n` 終端判定を 1 バイト毎 `toByteArray()` から `Int` シフトレジスタ（`0x0D0A0D0A`）の O(1) ゼロアロケーションに変更し、`GET_PREFIX` / `HEAD_PREFIX` を `companion object` 定数化する。
  - NAT 共有上限と Paper 内部 API 依存について起動ログ/KDoc を明記する。
- [ ] **FIX-05 `[High][Bug]`** (`AG H-03`) `ResourcePackController.kt:96-112`, `RpgPlugin.kt:1003-1024`
  - `ResourcePackController.send()` で `ResourcePackPolicy.decide(...)` を `loadedPlayers` 早期 return より前に評価し、`/ec ui pack off`（`!decision.send`）で適用済みプレイヤーも必ず `loadedPlayers` 解除＋`refreshPackItemModels(player, false)` されるようにする。
- [ ] **FIX-06 `[Med][Bug]`** (`AG M-02`) `ResourcePackController.kt:139-147,180-186,211-231`
  - `close()` に `observedManagedStatuses.clear()` を追加。
  - `applyManagedStatus()` で `SUCCESSFULLY_LOADED` のみ `loaded = true`、終端失敗ステータスのみ `loaded = false` とし、`ACCEPTED`/`DOWNLOADED` 中間ステータスでカスタムモデルが剥がれないようにする。
  - `buildInfo()` の SHA-1 / サイズ結果をキャッシュし、`/ec pack info` のたびにメインスレッドで JAR を再読込しないようにする。
- [ ] **FIX-07 `[Med][Bug/Style]`** (`CL M8, L8, S3`) `ResourcePackController.kt:20-37,152-171,199`
  - `bind_host` が非ループバックなのに公開 URL が `127.0.0.1` 等ループバックへ解決される場合、起動時に WARN を出して配信不能扱い（必須強制を緩和）にする。
  - 終端ステータス別にキック文言を分ける（`FAILED_DOWNLOAD` / `INVALID_URL` と `DECLINED` を区別）。
  - 20 行の `settings.getX(...)` を `data class ResourcePackSettings`（バリデーション付き）へまとめる。

---

## Wave 2 — Transactions / Locks / Economy / Persistence Integrity（Batch 1）

- [ ] **FIX-08 `[High][Bug]`** (`CL H1`) `AdventureController.kt:55-57,79-80`, `AdventureServices.kt:55,74-94`
  - `tick()` / `interact()` 経由のクエスト自動完了がトランザクション・ロック外でアイテム/通貨を付与する問題を修正（`transaction(...)` 経由に統一、または `allowAutoComplete` をトランザクション内のみに限定し、必ず永続化してから報酬付与する）。
- [ ] **FIX-09 `[Med][Bug]`** (`CL M1`) `AdventureController.kt:373,381,411,415`
  - `pendingRewards.putAll(output)` を加算マージ（`PlayerProfile.returnToMailbox(output)` ヘルパー）へ統一し、メールボックス報酬の上書きロストを防止する。
- [ ] **FIX-10 `[Med][Bug]`** (`CL M2`) `DungeonController.kt:93-106`
  - ダンジョン初回クリア報酬（`flags` / `pendingRewards` 更新＋保存）を `adventure.whenAvailable(member)` / ロック境界内で実行する。
- [ ] **FIX-11 `[Med][Bug/Idiom]`** (`CL M3, I5`) `AdventureController.kt:393-459`
  - `transaction()` と `deliverLocked()` の潜在的二重アンロックを解消（トークン付き `LockToken` / `withTxLock` で冪等化）。
- [ ] **FIX-12 `[Med][Bug]`** (`CL M4`) `AdventureController.kt:243-259`, `PaperInventoryAccess.kt:13-21`
  - 売却時に同一アイテム ID の先頭スタック（強化済み等）を誤売却する問題を修正（未強化/最小強化スタック優先、またはクリックしたスロット基準・強化済み誤売却ガード）。
- [ ] **FIX-13 `[Med][Bug]`** (`CL M7`) `RpgPlugin.kt:311-322`
  - 初回スターター装備付与時にホットバー変化で失敗した場合、`starterGranted = true` のまま消失させず `pendingRewards` へフォールバックする。
- [ ] **FIX-14 `[Med][Bug]`** (`AG M-05`) `FishingController.kt:241-251,281-288`
  - バイト時の餌消費失敗でそのまま `BITE_ALERT` を続行できる無料餌エクスプロイトを修正（失敗時は `FishingEndReason.CANCELLED` でセッション中断）。`bitePending` 解除をトランザクションコールバック側へ一本化し二重 `showBite` を防ぐ。

---

## Wave 3 — UI / Lang / Reload / World & Entity Correctness（Batch 1）

- [ ] **FIX-15 `[High][Bug/Style]`** (`AG H-04, L-07`) `ClassSelectionUi.kt:40-79`, `RpgPlugin.kt:151-161`
  - 確認 `Dialog` 表示中の `confirming: MutableSet<UUID>` 追跡を導入し、InvUI `onClose` による再オープン競合を防止。キャンセル時は明示的に職業選択メニューへ戻す。
  - `fallback(resource: ResourceKind)` を enum 網羅分岐にし、職業アイコンの斜体解除＋ロール/ステータス/スキル概要 Lore を付与する。
- [ ] **FIX-16 `[High][Bug]`** (`AG H-05`, `CL L7`) `RpgPlugin.kt:38,120,881-891`, `GatheringController.kt:47-51,130`, `FishingController.kt:41,44,106`
  - `RpgPlugin.language` を `@Volatile` 化し、非同期読込後は `sync {}` 内で差し替える。
  - `/ec reload` で `LangCatalog` の再読込、`GatheringController` の `sites`/`sitesById` 再構築、`FishingController` の `engine`/`capacity` 再生成を行う。`resource_pack.*` 変更には再起動が必要である旨をリロードメッセージへ明記する。
- [ ] **FIX-17 `[High][Bug]`** (`AG H-06`) `GatheringController.kt:314,319`, `RpgPlugin.kt:1097,1109`, `AdventureController.kt:129`
  - 4 箇所の `Component.toString()` チャット生ダンプを修正（`Component` プレースホルダーまたは `plainText()` 使用）。
  - プレイヤー向け表示に残っていた内部アイテム ID (`ec:item_*`) を `plugin.contentName(player, it.item)` へ統一する。
- [ ] **FIX-18 `[High][Bug]`** (`AG H-07`, `CL L4`) `LangCatalog.kt:49-53`
  - `diagnostic()` の `Regex.replace(template, detail)` による `\` / `$` クラッシュを修正（リテラル置換へ変更）し、MiniMessage 装飾タグがプレースホルダー正規表現で破壊されないようにする（正規表現は `companion object` 定数化）。
- [ ] **FIX-19 `[Med][Bug]`** (`AG M-03`) `RpgPlugin.kt:589-599`, `CodexController.kt:26-34,74-79`
  - 図鑑エリア一覧のクリック解決に現在ページ（`currentPage * 45 + slot`）を加算し、未使用の `codexTabs` を削除する。
- [ ] **FIX-20 `[Med][Bug/Idiom]`** (`AG M-04`, `CL L6`) `AdventureController.kt:127-136,152-158,186-191`, `InvUiInventoryAdapter.kt:46`
  - ショップ表示時に条件一致エントリを `visible` へ詰めて空白歯抜けを解消し、`InvUiInventoryAdapter.open` に `onClose` を追加して `invUiShops` をウィンドウ単位でクリーンアップする。`InventoryGrid.forInventorySize` に `require(size % 9 == 0)` を追加する。
- [ ] **FIX-21 `[Med][Bug]`** (`CL M11`) `AdventureController.kt:118`
  - Paper `Dialog` で選択肢ボタンが空の場合でも例外にならないガード（閉じるボタン等へのフォールバック）を入れる。
- [ ] **FIX-22 `[Med][Bug]`** (`AG M-06`) `GatheringController.kt:158-163,186-190`
  - `ChunkLoadEvent` の孤立エンティティ掃除対象に `hitboxKey`（`Interaction`）を追加する。
- [ ] **FIX-23 `[Med][Bug]`** (`AG M-08`) `RpgPlugin.kt:666,674-677`
  - `castSkill` の範囲巻き込み対象にも `participants` フィルタと `distinctBy { it.uniqueId }` を適用し、味方判定の `&&`/`||` を括弧で明示する。
- [ ] **FIX-24 `[Med][Bug]`** (`AG M-12`) `NpcController.kt:70-82`
  - 管理 NPC を `entity.isPersistent = false` に統一し、対象チャンクがロード済みのときだけスポーンする。
- [ ] **FIX-25 `[Med][Bug]`** (`AG M-13`) `DungeonController.kt:15,35-40`
  - スロット再利用時に別パレットのダンジョンへ切り替わったらアリーナを再建築する（`builtPalette: MutableMap<Int, List<String>>`）。
- [ ] **FIX-26 `[Med][Bug/Style — 実クライアント要確認]`** (`AG M-09, L-07`, `CL S4`) `UiThemeProvider.kt:58-91`
  - `ThemeMode.FULL` の先頭 `-8px` 補正（`TITLE_LEFT_OFFSET`）を入れ、スロット番号ハードコードを宣言的ルールまたは PDC ロールタグ判定へ整理する（※実クライアント `ui.theme.mode: full` スクショでズレ確認必須）。

---

## Wave 4 — Performance & Hot-Path Optimization（Batch 2）

- [ ] **FIX-27 `[Med][Perf]`** (`AG M-10`, `CL M6`) `AdventureController.kt:42-60`, `RpgPlugin.kt:416-418,607-621,763-770`
  - `QuestEngine.event()` が変化有無 `Boolean` を返すようにして毎秒の `p.snapshot()` ディープコピーを廃止する。
  - `applyStats(p, stats)` で計算済み `Stats` を受け取り毎秒の `effectiveStats` 二重計算を解消する。
  - `syncTitleAdvancements()` を毎秒全実行から状態変化イベント駆動へ移す。
  - `PlayerMoveEvent` でブロック座標・ワールド未変化なら即 return する。
- [ ] **FIX-28 `[Med][Perf]`** (`CL P1, P2, P4, P6`) `PaperInventoryAccess.kt:37-49`, `AdventureController.kt:150-160,285-290,306-310`, `RpgPlugin.kt:405-415`
  - `PaperInventoryAccess.exchange()` で 36 スロット複製プレビューを 1 回（`Plan(afterRemove, afterAll)`）に統合する。
  - クリックイベント内の `bound()` 呼び出し結果をスタック単位で再利用し `itemMeta` クローン連打を削減する。
  - `EntityDamageEvent` 等の安全地帯判定を `safeAreasByWorld: Map<String, List<Area>>` 事前インデックス参照にする。
  - `effectiveStats` をプレイヤー単位でキャッシュし装備/スロット/強化変更時に無効化する。
- [ ] **FIX-29 `[Med][Perf]`** (`AG M-07`, `CL M5, L7`) `RpgPlugin.kt:435,446-462,570`, `PaperInventoryAccess.kt:38`, `AdventureController.kt:425,434`
  - `AdvancementDatapack.install(...)` のファイル書き出しを非同期側へ移し、メインスレッドでは `changed` 時のみ反映する。
  - メインスレッドの毎回 `player.saveData()` を重要取引限定または設定化・設計見直しし、クラッシュ時トレードオフを明記する。
- [ ] **FIX-30 `[Med][Perf]`** (`AG M-11`) `RpgPlugin.kt:562-568`
  - 強化ロア照合セットを言語/設定読込時に 1 回だけキャッシュし、強化ごとの全レベル×全言語 MiniMessage 再生成を廃止する。
- [ ] **FIX-31 `[Low][Perf]`** (`AG L-06`, `CL P3, P5, P7, L5`) `WorldContent.kt`, `Game.kt`, `GatheringController.kt`, `PlayerStore.kt`, `TreasureController.kt`, `AdventureController.kt`, `Content.kt`
  - `WorldContent.enemiesByArea` 事前インデックス化、`recordEnemyKill()` の不要な `Position` 再生成削除。
  - `GatheringController` の `renderAll()` / `chunk()` / `isProtected()` で `sitesById.values`（重複なし）を使用。
  - 全ファイルの `Regex` / `Set` を `companion object` / トップレベル定数へ事前コンパイル。
  - `PlayerStore` の単一 IO スレッドで `Yaml` インスタンスを再利用。
  - `TreasureController` の所有マーカーをワールド単位でキャッシュし、`ChunkLoadEvent` 内で未ロードチャンクを強制ロードしないようにする。
  - ダイアログ `Component` を `(node, locale)` でキャッシュ（reload 時クリア）。

---

## Wave 5 — Idiomatic Kotlin, Structure Refactoring & Visual Polish（Batch 2）

- [ ] **FIX-32 `[Med][Idiom]`** (`CL I1`) `AdventureServices.kt:170-180`, `AdventureController.kt:265`
  - 型付き例外 `GameException(val key: String, val args: Map<String, Any?> = emptyMap())` と `fail(...)` を導入し、`"ec." + "error."` 分割や正規表現によるエラーキー往復を廃止する。
- [ ] **FIX-33 `[Low][Idiom]`** (`CL I2, I3, I4, I6, I7`, `AG L-04`)
  - `Component.plainText()` 拡張関数を `paper/` 共通ファイルに 1 箇所定義し、5 ファイルの重複 `plain()` を統合する。
  - 経過時間・デバウンス・チャネル計測を単調増加クロック（`System.nanoTime() / 1_000_000`）へ統一する（`FishingController`, `GatheringController`, `DungeonController`）。
  - 文字列ベースの種別分岐（アクション/エフェクト/メニュー/パックモード）を可能な範囲で enum / sealed 化する。
  - `Bukkit.getPlayer(uuid)` 後の冗長な `isOnline` チェックと重複キャスト/`!!` を解消する。
  - `PlayerStore.read` に `Any?.boundedInt(range, field)` ヘルパーを導入しエラー時のフィールド名を明示する。
  - UUID キーの並列一時状態マップを可能な範囲でセッションオブジェクトへ整理する。
- [ ] **FIX-34 `[Low][Style]`** (`AG L-01`, `CL L11, S2`, Task 20 差分) `RpgPlugin.kt`
  - 1,200 行超になった `RpgPlugin.kt` を責務分割する：Task 20 で増えた 12 個のロックイベントハンドラを `RequiredPackLockListener` へ切り出し、`AdminCommandHandler`（共通 `target()` ヘルパー付き）、`EnhancementService` / `StarterGearService` 等を分離して見通しを改善する。
- [ ] **FIX-35 `[Low][Style]`** (`AG L-02, L-03`, `CL L2, L9, S5`) `PlayerStore.kt:99-119`, `DemoWorld.kt:64-95,120-191`
  - `PlayerStore` マイグレーションを段階パイプライン化（v0→v13 を各ステップ 1 関数で順次適用）。
  - `DemoWorld` の `migrateToV2..V5` を共通ヘルパー `migrateStep(...)` へ統合し、新規作成ログのバージョン表記を正確化する。
- [ ] **FIX-36 `[Low][Style]`** (`AG L-07`, `CL (c),(d), L10, S1, S7`)
  - `"ec_world"` を `WorldIds.DEMO` 等へ統一、既定ダンジョン ID・折返し幅・履歴上限・ダンジョン座標定数を集約。
  - 400 文字 1 行の `tradeBound` を名前付き述語（`bundleSwap`, `touchesExternal`, `movesBound`）へ分解。
  - `AdventureServices.kt:186-187` 等の 20 条件 1 行 `require` 鎖を条件別メッセージ付きバリデータへ分解。
  - 不正確/欠落した日本語 KDoc を補完（`AdventureController.transaction` の実際の順序と失敗マトリクス、`TradeEngine.buy/sell`、`PlayerStore.migrate` バージョン表、`GamePortPackServer` の `'G'`/`'H'` Minecraft ハンドシェイク通過理由）。
- [ ] **FIX-37 `[Polish][Idiom/Style]`** (`AG P-01, P-02`, `CL L1, L11, S6`)
  - 二重 KDoc（5 ファイル）、未使用 `RpgPlugin.areaCodexPercent`、空 `if` ブロック、インデント崩れ（`RpgPlugin.kt:890`）を削除・修正。
  - 触ったファイルを中心にセミコロン `;` 1 行詰め込みを解消し、Kotlin 標準フォーマットへ統一する。

---

## Wave 6 — Regression Tests & Real-Paper Test-Gap Closing（Batch 2 + 継続）

- [ ] **FIX-38 `[High/Med][Test]`** (`CL H2`, `AG M-14`)
  - Batch 1/2 の各修正に対応する単体・結合回帰テストを追加（`GamePortPackServer` 1 バイトタイムアウト・`0x47 0x00` 通過・グローバル帯域制限・プロキシ設定検知、`ResourcePackController` ロード済み `pack off`・`external_managed` 中間/終端遷移、`ClassSelectionUi` 状態機械、`LangCatalog.diagnostic` の `\`/`$`/MiniMessage、`tradeBound` 述語、クエスト自動完了トランザクション、メールボックス加算マージ、図鑑 2 ページ目クリック、釣り餌失敗キャンセル、ダンジョンパレット再利用、`PlayerStore` マイグレーションパイプライン）。
  - コンテンツ検証時に YAML 由来の `Material` / `Particle` 名を事前検証する（実行時 `Material.valueOf` 墜落防止）。
  - ヘッドレス Paper 起動スモーク（`scripts/smoke-test.ps1`）で `ec validate` と許容外 ERROR/WARN ゼロを自動判定できるようにする。

---

## 受容・保留記録（Accepted / Deferred）
- （現時点なし。見送る項目が出た場合は理由を明記してここに記録する）
