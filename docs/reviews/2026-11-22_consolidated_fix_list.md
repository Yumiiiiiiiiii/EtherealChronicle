# 統合修正・リファクタリングリスト（2026-11-22）

- **統合元レビュー**:
  - `docs/reviews/2026-11-22_master_claude-qa.md`（Claude-QA / `9b0cb4b`）
  - `docs/reviews/2026-11-22_master_antigravity.md`（Antigravity-QA-3 / `67cb2a0`）
  - `docs/reviews/2026-11-22_task20_antigravity.md`（Antigravity-QA-3 / `8565cc0` — Task 20 差分 `8f3acfa`）
  - `docs/reviews/2026-11-22_task20_claude-qa.md`（Claude-QA / `a55b5ad` — Task 20 差分 `8f3acfa`）
  - `docs/reviews/2026-11-22_batch1_recheck_claude-qa.md`（Claude-QA Batch 1 再確認 / `d4dd791`）
  - `docs/reviews/2026-11-22_batch1_recheck_antigravity.md`（Antigravity-QA-3 Batch 1 再確認 / `820e792`）
- **運用ルール**: `AGENTS.md` の「Cross-model review loop」に従い、Wave 単位でコミット＋全テスト実行し、完了後に実装者以外の全モデル（Claude / Antigravity）が再確認して全項目クローズ（または理由付き受容）となるまで完了としない。
- **実行計画**:
  - **Batch 1（Wave 1〜3）**: 実装・マージ済み（`65e62bf`）→ **両モデル再確認完了**（15 件両モデル OK / 11 件要追加修正・新規 High デグレ 3 件検出）
  - **Batch 2（Wave 3B + Wave 3C + Wave 4〜6）**: Task 20 ゲート修正（`FIX-39`〜`45`）＋ Batch 1 再確認追補（要追加修正 11 件＋`FIX-46`〜`49`）＋ 高速化 (`Perf`) ＋ 書き方の違和感/見栄え/分割 (`Idiom`/`Style`/`Polish`) ＋ テスト拡充 → **Codex 実装中**

---

## Wave 1 — Security / Resource Pack / Netty / HTTP（Batch 1 — 3/7 両モデル OK・4 件追補）

- [x] **FIX-01 `[High][Security]`** (`AG H-01` / ✅ 両モデル OK `ec5ceb8`) `GamePortPackServer.kt:132-162`
  - `SNIFFING` で先頭 `'G'`/`'H'` を 1 バイト保留した時点で `idleTimeout` を開始し、`MINECRAFT` パススルー・`channelInactive`・`handlerRemoved` で確実にキャンセルする（1 バイト Slowloris DoS 対策）。
- [ ] **FIX-02 `[High][Security]`** (`AG H-02`, `B1-M01` / 要追加修正) `GamePortPackServer.kt:71-73,91-103`
  - `unsupportedProxies()` で BungeeCord / Velocity 設定と `TCPShield` プラグインを検知する。
  - **【再確認・要追加修正】** `worldContainer` 基準だと `--world-dir` / `settings.world-container` 環境で検知漏れする → `server.spigot().spigotConfig` / `paperConfig`（不可時のみサーバールート `dataFolder.parent.parent`）を参照し、71-73 行目の二重呼出しを解消する。
- [ ] **FIX-03 `[High][Security]`** (`AG H-08`, `CL M9`, `CL N3` / 要追加修正) `ResourcePackDistribution.kt:58-70,128-140,211-235`
  - `EmbeddedPackServer.writeResponse()` に書き込みデッドライン・非 200 `Cache-Control: no-store`・accept バックオフを追加（基本実装済）。
  - **【再確認・要追加修正】** `write_deadline_ms` が固定 5 秒だと 5〜20MB パックで通常回線も切断される → パックサイズ比例（`max(baseMs, bytes * 1000 / minRateBps)` 等）またはチャンク進捗リセットにする。
- [x] **FIX-04 `[Med][Security/Perf]`** (`AG M-01, L-05`, `CL M10, P8` / ✅ 両モデル OK `ec5ceb8`) `GamePortPackServer.kt:139,164-175,272-324`
  - `PackConnectionLimiter` にグローバル共有の `globalNextSendNanos` を導入し、`\r\n\r\n` 終端を `Int` シフトレジスタの O(1) ゼロアロケーション化・定数化完了。
- [ ] **FIX-05 `[High][Bug]`** (`AG H-03`, `B1-M02` / 要追加修正) `ResourcePackController.kt:112-122`, `RpgPlugin.kt:1003-1024`
  - `send()` の `decide(...)` 順序修正は完了。
  - **【再確認・要追加修正】** `!decision.send` 時に適用済みだったプレイヤーへ `runCatching { player.removeResourcePacks(requestId) }` を呼んでクライアント側パック本体も解除し、`send()` 経路の回帰テストを追加する。
- [x] **FIX-06 `[Med][Bug]`** (`AG M-02`, `T20-M03` / ✅ 両モデル OK `ec5ceb8`) `ResourcePackController.kt:85-92,261,304-316`
  - `close()` の `observedManagedStatuses.clear()`、`ACCEPTED`/`DOWNLOADED` 維持、`buildInfo()` キャッシュ完了（※ZIP バイト配列ではなく `ResourcePackBuildInfo` のみキャッシュへ軽量化は `FIX-47`）。
- [ ] **FIX-07 `[High][Bug/Style]`** (`CL M8, L8, S3, N4, N5`, `B1-H01, B1-M03` / 要追加修正 — 新規 High デグレ) `ResourcePackController.kt:14,58-63`, `ResourcePackSettings.kt:34-47`
  - ループバック警告・ステータス別キック文言・`ResourcePackSettings` 抽出は実装済。
  - **【再確認・High デグレ修正】** `ResourcePackSettings.from(plugin.config)` が `runCatching` 外で例外を投げ、設定 typo 1 箇所でプラグイン全体が起動停止する → `runCatching` で WARN 出力＋安全無効設定へフォールバックする。
  - **【再確認・要追加修正】** `mode: game_port` 時は `embedded_http.bind_host` ではなく `plugin.server.ip.ifBlank { "0.0.0.0" }` で `canAdvertise` を判定する。`0.0.0.0` / `::ffff:127.x` 公開不可判定と、ローカル検証手順（`bind_host: 127.0.0.1` 等）のドキュメント/WARN 案内を補完する。

---

## Wave 2 — Transactions / Locks / Economy / Persistence Integrity（Batch 1 — 3/7 両モデル OK・4 件追補）

- [ ] **FIX-08 `[High][Bug]`** (`CL H1, N1`, `B1-H02` / 要追加修正 — 新規 High デグレ) `AdventureController.kt:54-60,84-87`
  - クエスト自動完了をトランザクション内へ移動済み。
  - **【再確認・High デグレ修正】** インベントリ満杯＋アイテム報酬あり自動完了依頼（69 件該当）の状態で `completeQuest` が例外を投げ、全 NPC 会話/ショップがソフトロック＋`tick()` が毎秒ロールバック＆`saveData()` スパムする → `canComplete` / `try-catch(IllegalArgumentException)` で空き不足時は進行のみ保存し、`interact()` はトランザクション失敗でも会話を開く。
- [x] **FIX-09 `[Med][Bug]`** (`CL M1` / ✅ 両モデル OK `58de839`) `AdventureController.kt:405,413,443,447`, `Game.kt:172-177`
  - `PlayerProfile.returnToMailbox`（`Math.addExact` 加算マージ）へ全箇所統一完了。
- [x] **FIX-10 `[Med][Bug]`** (`CL M2` / ✅ 両モデル OK `58de839`) `DungeonController.kt:90-114`
  - ダンジョン初回クリア報酬の `adventure.profileTransaction` 化完了（※保存失敗ロールバックのセッション同一性チェック追加は `FIX-46`）。
- [ ] **FIX-11 `[High][Bug/Idiom]`** (`CL M3, I5, N2`, `B1-H03` / 要追加修正 — 新規 High デグレ) `AdventureController.kt:404-411,436-455`
  - `LockToken` / `withTxLock` で二重アンロックは解消済み。
  - **【再確認・High デグレ修正】** `beforeQuit` の早期 `releaseLock`＋`deliverLocked` の無条件 `finally { deliveries.remove(p.uuid); delivering.remove(p.uuid) }` により、切断→即再接続時に新セッションの `claim2` を旧 `claim1` の `finally` が消して報酬が永久消失する → `if (deliveries[p.uuid] === claim)` のときだけ削除するよう修正し回帰テストを追加する。
- [x] **FIX-12 `[Med][Bug]`** (`CL M4` / ✅ 両モデル OK `58de839`) `AdventureController.kt:148,165,258`, `PaperInventoryAccess.kt:11-65`
  - クリック元スロット優先売却を実装完了（※オフハンド/防具スロット拒否と `exchange` 重複統合は `FIX-47`）。
- [ ] **FIX-13 `[Med][Bug]`** (`CL M7`, `B1-M06` / 要追加修正) `RpgPlugin.kt:407-419`
  - ホットバー不足時のメールボックス退避を実装済み。
  - **【再確認・要追加修正】** 退避分岐の早期 `return@sync` で `discoverItem`・`skillSlot`・`applyStats`・`p.health`・`p.closeInventory()` が飛ばされる → 退避後もこれらを通常経路同様に実行する。
- [ ] **FIX-14 `[Med][Bug]`** (`AG M-05`, `B1-M07` / 要追加修正) `FishingController.kt:241-254,275-282`
  - `beginBite()` の失敗時キャンセルは実装済み。
  - **【再確認・要追加修正】** `FishingController.tick()`（275-282 行目）に旧 `bitePending` 無料バイト復帰ブロックが残存しているため削除して `if (s.bitePending) return@forEach` へ一本化し、回帰テストを追加する。

---

## Wave 3 — UI / Lang / Reload / World & Entity Correctness（Batch 1 — 9/12 両モデル OK・3 件追補）

- [ ] **FIX-15 `[High][Bug/Style]`** (`AG H-04, L-07`, `CL N6`, `B1-L06` / 要追加修正＋実機要確認) `ClassSelectionUi.kt:33-85`, `RpgPlugin.kt:153-173,349`
  - `ClassConfirmationTracker`・enum 網羅 `fallback`・斜体解除＆Lore 付与は実装済み。
  - **【再確認・要追加修正】** 確認 Dialog が `canCloseWithEscape(false)`＋ボタン寿命 2 分のため 2 分放置でソフトロックしうる → Esc を許可してキャンセルへ戻す（または寿命撤廃/再表示）＋`onQuit` で `confirming` をクリアする。
- [x] **FIX-16 `[High][Bug]`** (`AG H-05`, `CL L7` / ✅ 両モデル OK `9de04bb`) `RpgPlugin.kt:40,999`, `GatheringController.kt`, `FishingController.kt`
  - `@Volatile language`・`/ec reload` での言語/採取/釣り再構築・再起動注記を完了（※起動時/リロード時の `LangCatalog.load` を async 側へ寄せる軽微整理は `FIX-48`）。
- [ ] **FIX-17 `[Low][Bug]`** (`AG H-06`, `CL FIX-17` / 要追加修正) `GatheringController.kt:323,328`, `CodexController.kt`
  - 4 箇所の `Component.toString()` ダンプと主な内部 ID 露出は解消済み。
  - **【再確認・要追加修正】** `GatheringController` の `reward_failed` に生 `error.message.orEmpty()`（`ec.error.*` キー生表示）が残っているため `plugin.diagnostic(...)` を通す。図鑑の弱点表示（`enemy.weaknesses.joinToString()` の生 enum 名）もローカライズ表示にする。
- [x] **FIX-18 `[High][Bug]`** (`AG H-07`, `CL L4` / ✅ 両モデル OK `9de04bb`) `LangCatalog.kt:50-53,69`
  - リテラル置換＋定数化完了（※否定先読み境界と短縮タグ除外の精緻化は `FIX-48`）。
- [x] **FIX-19 `[Med][Bug]`** (`AG M-03` / ✅ 両モデル OK `9de04bb`) `CodexPagination.kt`, `CodexController.kt:140-147`, `RpgPlugin.kt:703`
  - 図鑑エリア一覧の `page * 45 + slot` 計算と未使用 `codexTabs` 削除完了。
- [x] **FIX-20 `[Med][Bug/Idiom]`** (`AG M-04`, `CL L6` / ✅ 両モデル OK・実機要確認 `9de04bb`) `AdventureController.kt:133-146`, `InvUiInventoryAdapter.kt:18-49`
  - ショップ詰め・`onClose` クリーンアップ・9 の倍数チェック完了（※54 件超ガードは `FIX-48`、リフレッシュ後右クリック売却は実クライアントで最終確認）。
- [x] **FIX-21 `[Med][Bug]`** (`CL M11` / ✅ 両モデル OK `9de04bb`) `AdventureController.kt:102`, `Wave3UiRules.kt:20`
  - 選択肢 0 件時の空 Dialog 例外回避完了。
- [x] **FIX-22 `[Med][Bug]`** (`AG M-06` / ✅ 両モデル OK `9de04bb`) `GatheringController.kt:193-199`
  - `ChunkLoadEvent` の孤立 `Interaction` ヒットボックス掃除完了。
- [x] **FIX-23 `[Med][Bug]`** (`AG M-08` / ✅ 両モデル OK `9de04bb`) `RpgPlugin.kt:771-793`
  - 範囲スキルの `participants` フィルタ・`distinctBy`・括弧明示完了。
- [x] **FIX-24 `[Med][Bug]`** (`AG M-12` / ✅ 両モデル OK `9de04bb`) `NpcController.kt:76-82`
  - 管理 NPC の `isPersistent = false`・ロード済みチャンク限定スポーン完了。
- [x] **FIX-25 `[Med][Bug]`** (`AG M-13` / ✅ 両モデル OK `9de04bb`) `DungeonController.kt:15,36-44`
  - スロット再利用時の `builtPalette` 再建築完了。
- [ ] **FIX-26 `[Med][Bug/Style — 修正後に実機要確認]`** (`AG M-09, L-07`, `CL S4`, `B1-M08` / 要追加修正) `UiThemeProvider.kt:68-71`, `Wave3RegressionTest.kt:90-93`
  - `UI_MODEL_RULES` 整理は完了。
  - **【再確認・要追加修正】** `TITLE_LEFT_OFFSET = -8` が背景 `glyph` の**後**に足されており背景が +8px ズレたままタイトルだけ左ズレしている → `Component.text(spaces(TITLE_LEFT_OFFSET)).font(spaceFont)` を `Component.text(glyph)` の**前**に配置し、生成 `Component` 構造を検証するテストを追加する（修正後に実クライアント `ui.theme.mode: full` スクショ確認必須）。

---

## Wave 3B — Task 20 必須パックゲート修正（Batch 2）

- [ ] **FIX-39 `[High][Bug/Perf/Style]`** (`T20-H01, T20-M04, T20-L01, T20-L03, T20-L04, T20-P02`, `T20-C9, T20-C10`) `RpgPlugin.kt:253-342`
  - 12 個のロックハンドラを独立クラス `RequiredPackLockListener`（必要イベントのテーブル駆動登録＋非必須/配信不能時は未登録）へ分離し、全ハンドラを **`EventPriority.LOWEST`**（他 Controller より先頭登録）へ変更する。
  - `hasWaiters()` による O(1) 早期 return・`isLocked(entity: Any?)` 統一・ブロック移動判定（`event.hasChangedBlock()` 互換ヘルパー、ワールド跨ぎ含む）共通化。
  - `FishingController.interact` / `GatheringController.interact` 等の `ignoreCancelled` を統一する。`finishLoadedJoin` の未使用引数 `snapshot` 削除、誤記 KDoc と `config.yml` の `10..600` 範囲コメント修正。
- [ ] **FIX-40 `[High][Security]`** (`T20-H02`) `RequiredPackLockListener`, `RpgPlugin.kt`, `AdventureController.kt`, `EnemyController.kt`
  - `PlayerInteractEntityEvent` / `PlayerInteractAtEntityEvent` / `EntityPickupItemEvent` / `PlayerPortalEvent` / `ProjectileLaunchEvent` / `FoodLevelChangeEvent` / `EntityTargetLivingEntityEvent` をロック対象に追加し、`EntityDamageByEntityEvent` で `Projectile.shooter` も判定する。
  - `RpgPlugin.tick()` / `AdventureController.tick()` / `EnemyController.tick()` で必須パック待機中プレイヤーを除外する。
- [ ] **FIX-41 `[High][Bug]`** (`T20-H03`) `ResourcePackController.kt:109,115-121,131`
  - `send()` で要求を送信せずに return する全経路（`!decision.send` / 配信不能 / 既に適用済み `loadedPlayers`）で、待機中なら必ず `releaseRequiredWait(playerId, continueJoin = true)` を呼び、永久デッドロックを防ぐ。
- [ ] **FIX-42 `[High][Bug/Idiom/Perf]`** (`T20-H04, T20-L02, T20-P01`, `T20-C7, T20-C8`) `ResourcePackController.kt:18,45-48,151-157,188-194,238,246-266,363`
  - 4 並列コレクションを `JoinState(var dispatchTask, var timeoutTask, val waiters, var extensions)` の 1 マップへ集約し、1-tick `runTaskLater` タスクも `releaseRequiredWait` / `onQuit` / `close` で確実に `cancel()` し、セッション同一性を検証して高速リログ競合を解消する。
  - `debugLog` を `private inline fun debugLog(message: () -> String)` にして `debug=false` 時の文字列生成・FINE ログを省く。
  - `10..600` 範囲定数を `RequiredPackGatePolicy` へ一本化し、`onStatus()` の冗長な `terminalFailure` 判定と `player.uniqueId` 反復を整理する。
- [ ] **FIX-43 `[Med][Bug]`** (`T20-M01, T20-M03, T20-M06`, `T20-C2, T20-C3, T20-C4, T20-C5`) `ResourcePackController.kt:106-140,197-198,220-244`
  - `required: true` 下で過去に `OFF` 保存済みの非 bypass プレイヤーが必須適用後も `hasResourcePack() == true` になるよう修正する。
  - `onStatus()` で中間ステータス（`ACCEPTED` / `DOWNLOADED`）を `debugLog` 出力するとともに、ダウンロード進行中のタイムアウトを上限付き（例: 最大 3 回）で延長再武装する。
  - `bypass` を dispatch tick とタイムアウトキック直前にも再評価し、`request sent` デバッグログへ `bypass` を含める（LuckPerms 等の非同期権限読込対策）。
  - `DISCARDED` は待機中のみ扱い、適用済みプレイヤーを他プラグインのパック差し替えで誤キックしないようにする。終端ステータス別/送信例外別に適切なキック案内キーを使う。
- [ ] **FIX-44 `[Med][Bug]`** (`T20-M02`, `T20-C1`) `RpgPlugin.kt:242-261,344-350`
  - 必須パック待機開始時に安全ハブ（`DemoWorld.luminaSpawn`）へ移動＋一時的無敵（`isInvulnerable = true`、解放・切断時に必ず復元）にし、バニラ初期ワールドのランダム座標放置と空中スポーンの `Flying is not enabled` キックを防ぐ。
  - 必須パック拒否/タイムアウトで未プレイ切断した初回プレイヤーのプロファイル新規書き出しを抑止する（`finishLoadedJoin` まで `profiles` 未登録、または未入場保存ガード）。
- [ ] **FIX-45 `[Med][Test]`** (`T20-M05`, `T20-C6`) `src/test/kotlin/net/nakaymi/etherealchronicle/paper/`
  - 重複している `RequiredPackGatePolicy.shouldWait` テストを 1 箇所へ統合し、状態遷移決定テーブル（`status × required × bypass → release/kick/extend/ignore`）を純粋単体テストで網羅する。
  - MockBukkit 結合テスト（優先順位 `LOWEST` ブロック・NPC/アイテム拾得ブロック・`SUCCESSFULLY_LOADED` 解除・`ACCEPTED`/`DOWNLOADED` 延長・`DECLINED`/タイムアウト/高速リログ/`bypass` 変化でデッドロック無し・未入場者プロファイル非保存）を追加する。

---

## Wave 3C — Batch 1 再確認で見つかった追加項目（Batch 2）

- [ ] **FIX-46 `[Med][Bug/Perf]`** (`B1-M04, B1-M05`, `CL N10`) `AdventureController.kt:384-391,464-472`
  - `transaction()` で `beforeStorage` の 36 スロット複製を `skipIfUnchanged` 通過後へ遅延し、毎秒の無変化 tick で不要な 36 スロットクローンを発生させない（`FIX-27` と連動）。
  - `profileTransaction()` の保存失敗ロールバックに `sameSession || offlineWithoutReconnect` チェックと `success()` 時の `plugin.profiles[id] === profile` 確認を追加する。
- [ ] **FIX-47 `[Low][Idiom/Style/Perf]`** (`B1-L02, B1-L03, B1-L04, B1-L05, B1-P01, B1-P02, B1-P03`, `CL N7, N9, N11`)
  - `ResourcePackController` の `settings.*` 26 行エイリアスを削除して `settings.xxx` を直接参照し、`bundledArtifact` ではなく `ResourcePackBuildInfo(sha1, sizeBytes)` のみキャッシュする。
  - `LockToken` を `data class` から通常 `class`（参照同一性のみ）へ変更、`PaperInventoryAccess.exchange` / `exchangeFromSlot` の重複を 1 実装へ統合、ショップ売却で `sourceSlot !in 0..35`（オフハンド/防具スロット）を拒否する。
  - `GamePortPackServer`: `sniffPrefix` のサイズ検証修正＋`Arrays.equals` によるゼロアロケーション化・未使用 `method` 引数と no-op `TERMINATOR_MASK` 削除。`EmbeddedPackServer` の watchdog を適切に管理する。
- [ ] **FIX-48 `[Low][Bug]`** (`B1-L01, B1-L06, B1-L07`, `CL FIX-16/18`)
  - `GamePortPackServer.respond()` でも共通の `cacheControlHeader(status)` を使い 404 を `no-store` にする。
  - `LangCatalog.DETAIL_PLACEHOLDER` を保守しやすいリスト構築＋完全一致境界 `(?=>)`＋短縮タグ（`b|i|u|st|obf|br`）除外へ改善する。
  - `AdventureController.showShop()` の `compactVisibleShopEntries` に `.take(54)` を追加、`RpgPlugin` の起動/リロード時 `LangCatalog.load` を async 側で読んで `sync {}` で差し替える。
- [ ] **FIX-49 `[Med][Test]`** (`CL N8`, `B1-H01..H03, B1-M02, B1-M07, B1-M08`)
  - Batch 1 再確認で指摘された振る舞い回帰テストを追加：① 設定 typo でも `ResourcePackController` が例外を出さず安全無効化される ② 満杯＋自動完了依頼ありで NPC 会話が開き `tick()` がロールバックしない ③ `claim1` 切断→即再接続 `claim2` で旧 `finally` が `claim2` を消さない ④ 適用済みからの `/ec ui pack off` ⑤ `FishingController` 餌消費失敗後の tick 非復帰 ⑥ `UiThemeProvider` の `-8px` 先頭配置 ⑦ `lang/*.yml` 全プレースホルダーが `DETAIL_PLACEHOLDER` で誤除外されない検証。

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
- [ ] **FIX-34 `[Low][Style]`** (`AG L-01`, `CL L11, S2`, `T20-L03`) `RpgPlugin.kt`
  - 1,200 行超になった `RpgPlugin.kt` を責務分割する：`RequiredPackLockListener`（FIX-39）、`AdminCommandHandler`（共通 `target()` ヘルパー付き）、`EnhancementService` / `StarterGearService` 等を分離して見通しを改善する。
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
  - Batch 1/2 の各修正に対応する単体・結合回帰テストを追加。
  - コンテンツ検証時に YAML 由来の `Material` / `Particle` 名を事前検証する（実行時 `Material.valueOf` 墜落防止）。
  - ヘッドレス Paper 起動スモーク（`scripts/smoke-test.ps1`）で `ec validate` と許容外 ERROR/WARN ゼロを自動判定できるようにする。

---

## 受容・保留記録（Accepted / Deferred）
- （現時点なし。見送る項目が出た場合は理由を明記してここに記録する）
