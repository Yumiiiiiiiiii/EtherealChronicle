# Batch 1 クロスモデル再確認＆新規差分レビュー（Antigravity 担当）

- **レビュー日時**: 2026-11-22
- **対象ブランチ / HEAD**: `ag/review-2`（`master` 最新 `0423ce9` マージ済み）
- **対象コミット**:
  - Wave 1: `ec5ceb8` (*Harden resource pack delivery and status handling* — `FIX-01`〜`FIX-07`)
  - Wave 2: `58de839` (*Make reward and quest flows transactional* — `FIX-08`〜`FIX-14`)
  - Wave 3: `9de04bb` (*Fix UI and world correctness edge cases* — `FIX-15`〜`FIX-26`)
- **基準リスト**: `docs/reviews/2026-11-22_consolidated_fix_list.md`（`FIX-01`〜`FIX-26`）
- **前提**: 本レビューではコードを一切変更せず、全 26 件の修正確認と新規差分で混入したデグレ・不足・改善点（`Bug` / `Security` / `Perf` / `Idiom` / `Style` / `Test`）を報告する。なお Wave 3B 以降に既に予定されている既知項目（例: `FIX-41` の `requiredPackGate` 早期 return 解放漏れ）は新規指摘として重複計上せず注記に留めている。

---

## 1. サマリー

### 1-1. FIX-01〜FIX-26 判定集計（全 26 件）

| 判定 | 件数 | 該当 FIX-ID |
|---|---:|---|
| **OK** | **18** | `FIX-01`, `FIX-03`, `FIX-04`, `FIX-06`, `FIX-09`, `FIX-10`, `FIX-12`, `FIX-15`, `FIX-16`, `FIX-17`, `FIX-18`, `FIX-19`, `FIX-20`, `FIX-21`, `FIX-22`, `FIX-23`, `FIX-24`, `FIX-25` |
| **要追加修正** | **8** | `FIX-02`, `FIX-05`, `FIX-07`, `FIX-08`, `FIX-11`, `FIX-13`, `FIX-14`, `FIX-26`（※修正後に実機要確認） |
| **実機要確認（単独）** | **0** | （`FIX-26` はコード側にオフセット位置のバグがあるため「要追加修正＋修正後に実機要確認」へ分類） |

### 1-2. Batch 1 差分からの新規指摘件数

| Severity | 件数 | 主な内訳 |
|---|---:|---|
| **High** | **3** | `B1-H01`（設定誤記でプラグイン起動全体がクラッシュ）, `B1-H02`（満杯＋自動完了依頼で NPC 会話ソフトロック＆毎秒ロールバック）, `B1-H03`（高速リログで旧 `deliverLocked` の `finally` が新配送を削除し報酬永久消失） |
| **Med** | **8** | `B1-M01`〜`B1-M08`（プロキシ設定パス、`/ec ui pack off` パック未解除、`game_port` bindHost 誤判定、`tick()` 毎秒 36 スロット複製、`profileTransaction` リログ上書き窓、スターター退避時 UI/ステータス未初期化、`FishingController.tick()` 旧 `bitePending` 残存、`UiThemeProvider` `-8px` 適用位置誤り） |
| **Low** | **7** | `B1-L01`〜`B1-L07`（`GamePortPackServer` 404 キャッシュ、`sniffPrefix.matches` 長さ未確認、26 フィールド手動コピー、`LockToken` data class、オフハンド右クリック売却と重複 `exchange`、軽微な状態掃除/非同期読込/54 件上限、`DETAIL_PLACEHOLDER` 境界） |
| **Polish** | **3** | `B1-P01`〜`B1-P03`（`bundledArtifact` ZIP 保持、no-op マスク＆1 件ずつ `mapOf`、常駐 watchdog executor） |

---

## 2. FIX-01〜FIX-26 個別再確認結果

### Wave 1（`ec5ceb8` — Security / Resource Pack / Netty / HTTP）

| FIX-ID | 判定 | 確認根拠・コメント |
|---|---|---|
| **FIX-01** | **OK** | `GamePortPackServer.kt:149, 192, 199, 286-292` で `'G'`/`'H'` 1 バイト保留時の `resetIdleTimeout` 開始と全離脱経路での `cancelTimeouts()` を確認。`GamePortPackServerTest.kt:69-95` で 1 バイト放置切断と `0x47 0x00` 通過を検証済み。（軽微指摘: `B1-L01`, `B1-L02`） |
| **FIX-02** | **要追加修正** | `GamePortPackServer.kt:91-103` が `plugin.server.worldContainer` 配下の `spigot.yml` / `config/paper-global.yml` を読んでいるため、`--world-dir` や `bukkit.yml` の `settings.world-container` 分離環境で BungeeCord/Velocity を検知できない。また `71-73` 行目で `unsupportedProxies()` を二重呼出ししている（`B1-M01`）。 |
| **FIX-03** | **OK** | `ResourcePackDistribution.kt:58-70, 128-140, 211-235` で `withSocketWriteDeadline` による書き込み強制切断、`cacheControlHeader(status)` による非 200 `no-store`、`acceptConnections` の 25ms バックオフと `BlockingSocket` テストを確認。（軽微指摘: `B1-L01`, `B1-P03`） |
| **FIX-04** | **OK** | `GamePortPackServer.kt:177, 182, 308-359` で `PackConnectionLimiter` のグローバル単一 `globalNextSendNanos`（`release` 非リセット・テスト済み）、`Int` シフトレジスタ `0x0D0A0D0A`、`GET_PREFIX`/`HEAD_PREFIX` 定数化、NAT/内部 API の KDoc＆ログを確認。（軽微指摘: `B1-P02`） |
| **FIX-05** | **要追加修正** | `ResourcePackController.kt:112-122` で `ResourcePackPolicy.decide` が `loadedPlayers` 早期 return より前へ移り、`/ec ui pack off` でアイテムモデル解除まで届くようになった。ただし `player.removeResourcePacks(requestId)` を呼んでおらずクライアント側のパック（GUI/フォント等）自体はリログまで解除されない。また `ResourcePackRequiredPolicyTest.kt:122-132` は `decide()` のみで `send()` 経路を検証していない（`B1-M02`）。（※`requiredPackGate` の待機解放漏れは Wave 3B `FIX-41` 既計上） |
| **FIX-06** | **OK** | `ResourcePackController.kt:85-92, 261, 304-316, 366-370` で `close()` の `observedManagedStatuses.clear()`、`ManagedPackPolicy.visualTransition` による `ACCEPTED`/`DOWNLOADED` 維持（テスト済み）、`bundledArtifact` の `by lazy` キャッシュを確認。（軽微指摘: `B1-P01`） |
| **FIX-07** | **要追加修正** | `ResourcePackSettings` 抽出、`canAdvertise`、`requiredFailureMessageKey`（＋`ja_jp.yml`/`en_us.yml`）は実装済み。しかし **`ResourcePackController.kt:14` が `runCatching` 外で `ResourcePackSettings.from(plugin.config)` を呼ぶため、設定誤記でプラグイン全体が起動クラッシュする重大デグレ（`B1-H01`）** が混入。加えて `game_port` 時の `bindHost` 誤判定（`B1-M03`）と 26 フィールド重複コピー残存（`B1-L03`）がある。 |

### Wave 2（`58de839` — Transactions / Locks / Economy / Persistence Integrity）

| FIX-ID | 判定 | 確認根拠・コメント |
|---|---|---|
| **FIX-08** | **要追加修正** | `AdventureController.kt:54-60, 84-87` と `AdventureServices.kt:55-82` で `tick()`/`interact()` の自動完了が `transaction(..., skipIfUnchanged = true)` へ入った。しかし **`readyAutoComplete` 後の `completeQuest` に満杯時 `try/catch` がなく、インベントリ満杯＋自動完了依頼ありで NPC 会話/ショップがクラッシュ・ソフトロックし `tick()` が毎秒ロールバック＆`saveData()` する重大デグレ（`B1-H02`）** と、毎秒全プレイヤーの 36 スロットを毎回クローンする性能悪化（`B1-M04`）が混入した。 |
| **FIX-09** | **OK** | `Game.kt:172-177` の `PlayerProfile.returnToMailbox`（`Math.addExact` 加算マージ）を `AdventureController.kt:405, 413, 443, 447` の 4 箇所すべてへ適用し、`RegressionTest.kt:82-90` で加算・オーバーフロー保護を確認済み。 |
| **FIX-10** | **OK** | `DungeonController.kt:90-114` が `adventure.profileTransaction` を経由してロック・永続化境界内で初回報酬フラグ・メールボックス・記録を保存するよう修正された。（軽微〜中指摘: `profileTransaction` 保存失敗時の再接続上書き窓 `B1-M05`） |
| **FIX-11** | **要追加修正** | `LockToken` と `withTxLock`・古いトークン誤解放防止テスト（`AdventurePathsTest.kt:94-114`）は導入された。しかし **`beforeQuit`（`AdventureController.kt:409`）の `releaseLock(claim.token)` と `deliverLocked`（`441-451`）の無条件 `finally { deliveries.remove(p.uuid); delivering.remove(p.uuid) }` が噛み合い、高速リログ時に新セッションのメールボックス報酬を永久消失させる重大バグ（`B1-H03`）** が発生している。（軽微指摘: `B1-L04`） |
| **FIX-12** | **OK** | `AdventureController.kt:148, 165, 258, 470` / `AdventureServices.kt:13-15, 162, 229-240` / `PaperInventoryAccess.kt:11-65` でクリック元スロット優先売却を実装し、単体＋MockBukkit テストで確認済み。（軽微指摘: オフハンド/防具スロット売却と `exchange` 重複 `B1-L05`） |
| **FIX-13** | **要追加修正** | `RpgPlugin.kt:408-415` でホットバー枠不足時に `returnToMailbox` へ退避する処理は入ったが、直後の `return@sync` により `skillSlot(p)` / `applyStats(p, profile)` / `p.health` / `p.closeInventory()` / `discoverItem` が一切実行されず中途半端な状態になる（`B1-M06`）。（軽微指摘: `B1-P02`） |
| **FIX-14** | **要追加修正** | `FishingController.kt:241-254` の `beginBite()` は同期/非同期失敗時に `CANCELLED` で中断するようになった。しかし **`FishingController.kt:275-282` の `tick()` に旧 `bitePending` 無料バイト復帰ブロックが丸ごと残存しており、`bitePending` 解除のコールバック一本化が未実施（`B1-M07`）**。回帰テストも未追加。 |

### Wave 3（`9de04bb` — UI / Lang / Reload / World & Entity Correctness）

| FIX-ID | 判定 | 確認根拠・コメント |
|---|---|---|
| **FIX-15** | **OK** | `ClassSelectionUi.kt:33-85`, `Wave3UiRules.kt:6-17`, `RpgPlugin.kt:153-173` で `ClassConfirmationTracker`、キャンセル時 `menu(p)` 復帰、enum 網羅 `fallback`、斜体解除＋ロール/ステータス/スキル Lore 付与を確認。（軽微指摘: `B1-L06`） |
| **FIX-16** | **OK** | `RpgPlugin.kt:40, 999`, `GatheringController.kt:45-46, 120-126`, `FishingController.kt:41, 44, 118-123`, `lang/*.yml` で `@Volatile`・`/ec reload` 時の言語/採取/釣り再構築・`resource_pack.*` 再起動注記を確認。（軽微指摘: 起動時 121 行目はまだ async 代入 `B1-L06`） |
| **FIX-17** | **OK** | `GatheringController.kt:323, 328`, `RpgPlugin.kt:928, 1215, 1227`, `AdventureController.kt:135`, `CodexController.kt:48, 52` で 4 箇所の `Component.toString()` 生ダンプと内部アイテム ID 露出をすべて解消済み（`paper/` 内の `Component.toString()` 残存ゼロ確認）。 |
| **FIX-18** | **OK** | `LangCatalog.kt:50-53, 69` で `DETAIL_PLACEHOLDER` 定数化と置換コールバックによる `$` / `\` リテラル化・`<red>` タグ保持を確認（`Wave3RegressionTest.kt:49-62`）。（軽微指摘: 否定先読みの単語境界不足 `B1-L07`） |
| **FIX-19** | **OK** | `CodexPagination.kt:5, 24-25`, `CodexController.kt:140-147`, `RpgPlugin.kt:703` で現在ページ加算（`pageStates` キー `"codex-areas:"` 一致確認）と未使用 `codexTabs` 削除・テスト追加を確認。 |
| **FIX-20** | **OK** | `AdventureController.kt:133-146, 182, 202`, `InvUiInventoryAdapter.kt:18-49` で条件一致エントリ詰め、`onClose` クリーンアップ、`InventoryGrid.forInventorySize` の 9 の倍数検証＋テストを確認。（軽微指摘: 54 件超ガード `B1-L06`） |
| **FIX-21** | **OK** | `AdventureController.kt:102`, `Wave3UiRules.kt:20` で選択肢 0 件時にクラシックインベントリへフォールバックし、Paper `DialogType.multiAction` の空リスト例外を回避（テスト済み）。 |
| **FIX-22** | **OK** | `GatheringController.kt:193-199` で `ChunkLoadEvent` の孤立エンティティ掃除対象に `hitboxKey`（`Interaction`）を追加済み（`displays[k]` に `hitbox.uniqueId` が記録されていることも確認）。 |
| **FIX-23** | **OK** | `RpgPlugin.kt:771-793`, `Wave3UiRules.kt:29` で範囲スキル対象への `enemyAllowsPlayer`（`participants` フィルタ）と `distinctBy { it.uniqueId }`、味方条件の括弧明示を確認（テスト済み）。 |
| **FIX-24** | **OK** | `NpcController.kt:76-82`, `Wave3UiRules.kt:26` で `world.isChunkLoaded(...)` チェックと `entity.isPersistent = false` を確認。 |
| **FIX-25** | **OK** | `DungeonController.kt:15, 36-44`, `Wave3UiRules.kt:23` で `builtPalette` によるパレット変化時の再建築とテストを確認。 |
| **FIX-26** | **要追加修正（※修正後に実機要確認）** | `UI_MODEL_RULES` の宣言的整理は OK。しかし **`UiThemeProvider.kt:68-71` が `TITLE_LEFT_OFFSET = -8` を背景 `glyph` の「前」ではなく「後」に足しており、`resourcepack/assets/ec/font/ui.json` の背景 bitmap 自体は依然 +8px 右にズレたままタイトル文字だけ左へズレる（`B1-M08`）**。テストも定数比較のみ。オフセット位置修正後に `ui.theme.mode: full` の実クライアントスクショ確認が必要。 |

---

## 3. 新規指摘・要追加修正詳細（Batch 1 差分）

### High（3 件）

#### `B1-H01` `[High][Bug]` `ResourcePackSettings.from(plugin.config)` が `runCatching` 外で例外送出しプラグイン起動全体がクラッシュする（`FIX-07`）
- **場所**: `src/main/kotlin/net/nakaymi/etherealchronicle/paper/ResourcePackController.kt:14`, `ResourcePackSettings.kt:34-47`, `RpgPlugin.kt:145-190`
- **What / Why**: Wave 1 で導入された `ResourcePackSettings` の `init { require(...) }` が、`ResourcePackController` のコンストラクタ先頭（`runCatching` の外側）で評価されるようになった。さらに `ResourcePackController(this)` は `RpgPlugin.onEnable()` の非同期ブロック内の `sync { ... }` で実行される（外側の `try/catch` は非同期スレッド側であり、`sync` ラムダ内には `try/catch` が無い）。そのため、管理者が `config.yml` の `resource_pack.mode` を typo したり `port` / `write_deadline_ms` を範囲外にしただけで **`ResourcePackController` 生成が `IllegalArgumentException` で墜落し、`ready = true`・各 Controller・イベントリスナー登録が一切行われずプラグイン全体が起動不能になる**（以前は `configuredEndpoint` の `runCatching` が捕捉し、パック配信だけを安全に無効化して本体は起動継続していた）。
- **Before**:
```kotlin
// ResourcePackController.kt:14
class ResourcePackController(private val plugin: RpgPlugin) : Listener, AutoCloseable {
    private val settings = ResourcePackSettings.from(plugin.config)
```
- **After**:
```kotlin
// ResourcePackSettings.from(...) 側で安全にフォールバックするか、Controller 側で runCatching して無効化設定へ落とす
private val settings: ResourcePackSettings = runCatching { ResourcePackSettings.from(plugin.config) }
    .onFailure { plugin.logger.warning("Invalid resource_pack configuration; disabling pack delivery: ${it.message}") }
    .getOrElse { ResourcePackSettings.disabled() }
```
- **追加テスト案**: 不正な `resource_pack.mode` や `port: 80` を含む `config` で `ResourcePackController` を初期化しても例外を投げず、`isDeliveryAvailable() == false` で起動継続する回帰テスト。

---

#### `B1-H02` `[High][Bug]` インベントリ満杯＋自動完了依頼ありの状態で NPC 会話がソフトロック＆`tick()` が毎秒ロールバック・`saveData()` スパムする（`FIX-08`）
- **場所**: `src/main/kotlin/net/nakaymi/etherealchronicle/paper/AdventureController.kt:55-60, 84-87`
- **What / Why**: 旧 `QuestEngine.event()`（`AdventureServices.kt:71-73`）には `try { complete(p, id, inventory) } catch (_: IllegalArgumentException) { /* ready quests remain claimable when storage is full */ }` があった。しかし Wave 2 で `allowAutoComplete = false`＋`readyAutoComplete(...).forEach { plugin.game.completeQuest(...) }` へ分離した際、この `try/catch` が欠落した。アイテム報酬を持つ自動完了依頼の条件を満たした状態でプレイヤーのインベントリが満杯になると：
  1. `interact()`（84-87 行目）が `ec.error.inventory_exchange` の `IllegalArgumentException` を投げて未捕捉クラッシュし、**ショップ NPC を含む全 NPC の会話・ショップ画面が一切開かなくなる**（不要品を売って空きを作ることもできない完全ソフトロック）。
  2. `tick()`（55-60 行目）では `transaction()` の `catch` が毎回 `EXPLORE` 進行をロールバックし、`runCatching { player.saveData() }`（メインスレッド I/O）と `WARNING` ログを **毎秒** 繰り返す。
- **Before**:
```kotlin
// AdventureController.kt:59, 86
plugin.game.quests.readyAutoComplete(p, staged).forEach { quest ->
    plugin.game.completeQuest(p, quest, staged)
}
```
- **After**:
```kotlin
plugin.game.quests.readyAutoComplete(p, staged).forEach { quest ->
    try {
        plugin.game.completeQuest(p, quest, staged)
    } catch (_: IllegalArgumentException) {
        // インベントリ満杯時は進行だけを保存し、自動完了は空きができてから再試行する
    }
}
```
（※`completeQuest` は `inventory.exchange(remove, add)` 検証をプロフィール変更より先に行うため、`IllegalArgumentException` 時に副作用を残さない。）
- **追加テスト案**: インベントリ満杯＋自動完了依頼 ready 状態で `interact()` を呼んでも NPC 会話が開き、`tick()` も例外/ロールバックを起こさない結合テスト。

---

#### `B1-H03` `[High][Bug]` 高速リログ時に旧 `deliverLocked` の `finally` が新セッションの配信を破壊しメールボックス報酬が永久消失する（`FIX-11`）
- **場所**: `src/main/kotlin/net/nakaymi/etherealchronicle/paper/AdventureController.kt:404-411, 436-455`
- **What / Why**: Wave 2 で `beforeQuit(player)` に `releaseLock(claim.token)` が追加された結果、以下のレースが発生する：
  1. プレイヤーが `claim1`（`token1`）を開始：`deliveries[uuid] = claim1`, `p1.pendingRewards.clear()`, `persist(p1)` 開始。
  2. プレイヤー切断 → `beforeQuit` が `deliveries.remove(uuid)`、`p1.returnToMailbox(output)`、`persist(p1)` 積み直し、`releaseLock(token1)` を実行。
  3. プレイヤーが即再接続（`claim1` の `persist` コールバックより先） → `claimPending` が新トークン `token2` で `claim2` を開始：`deliveries[uuid] = claim2`, `p2.pendingRewards.clear()`, `persist(p2)` 開始。
  4. `claim1` の `whenComplete -> sync` が到着：`if (deliveries[p.uuid] !== claim1) return@sync` で抜けるが、**直後の `finally { deliveries.remove(p.uuid); delivering.remove(p.uuid); releaseLock(token1) }` が無条件に実行され、`claim2` を `deliveries` から消してしまう**。
  5. 続いて `claim2` の `whenComplete -> sync` が到着すると `deliveries[p.uuid]` が `null`（`!== claim2`）になっているため **アイテム配布もメールボックス復元も行わず終了し、空保存された `p2.pendingRewards` の報酬が永久消失する**。
- **Before**:
```kotlin
// AdventureController.kt:441-454
try {
    if (deliveries[p.uuid] !== claim) return@sync
    ...
} finally {
    deliveries.remove(p.uuid)
    delivering.remove(p.uuid)
    releaseLock(token)
}
```
- **After**:
```kotlin
try {
    if (deliveries[p.uuid] !== claim) return@sync
    ...
} finally {
    if (deliveries[p.uuid] === claim) {
        deliveries.remove(p.uuid)
        delivering.remove(p.uuid)
    }
    releaseLock(token)
}
```
- **追加テスト案**: `claim1` を `beforeQuit` で戻した後に同一 UUID で `claim2` を開始し、旧 `claim1` の完了コールバックが先に走っても `claim2` が削除されず 1 回だけ配布される回帰テスト。

---

### Med（8 件）

#### `B1-M01` `[Med][Bug/Security]` `unsupportedProxies()` の設定ファイル探索先が `worldContainer` 基準で誤り＋失敗時二重読込（`FIX-02`）
- **場所**: `src/main/kotlin/net/nakaymi/etherealchronicle/paper/GamePortPackServer.kt:71-73, 91-103`
- **What / Why**: `spigot.yml` と `config/paper-global.yml` はサーバールート（または `--config` / `--paper-settings` 指定先）に置かれるが、実装は `plugin.server.worldContainer` を基準にしている。`bukkit.yml` の `settings.world-container` や `--world-dir worlds` を使う一般的な本番構成ではファイルが存在せず `false` が返り、BungeeCord / Velocity 検知を素通りする。`server.spigot().spigotConfig` / `paperConfig`（利用不可時のみサーバールート `Path.of(".")` フォールバック）を使うべき。また 71-73 行目で `unsupportedProxies()` を 2 回呼び YAML を二重読込している。
- **Before**:
```kotlin
require(unsupportedProxies().isEmpty()) {
    "Game-port pack delivery is not supported behind proxy plugins: ${unsupportedProxies().joinToString()}"
}
...
plugin.server.worldContainer.toPath().resolve("spigot.yml").toFile()
```
- **After**:
```kotlin
val proxies = unsupportedProxies()
require(proxies.isEmpty()) {
    "Game-port pack delivery is not supported behind proxy plugins: ${proxies.joinToString()}"
}
// unsupportedProxies() 内では plugin.server.spigot().spigotConfig / paperConfig を優先参照し、
// フォールバック時は plugin.dataFolder.toPath().toAbsolutePath().parent.parent（サーバールート）を参照
```

---

#### `B1-M02` `[Med][Bug/Test]` `/ec ui pack off` でクライアント適用済みリソースパック自体が解除されず、テストも `send()` を通していない（`FIX-05`）
- **場所**: `src/main/kotlin/net/nakaymi/etherealchronicle/paper/ResourcePackController.kt:112-122`, `ResourcePackRequiredPolicyTest.kt:122-132`
- **What / Why**: `!decision.send` 時に `loadedPlayers -= player.uniqueId` と `refreshPackItemModels(player, false)` は呼ばれるが、`player.removeResourcePacks(requestId)` を呼んでいない。そのため `/ec ui pack off` を実行しても、クライアント側に適用済みのパック本体（カスタム GUI フォントやテクスチャ）は再接続するまで外れない。また追加されたテスト `turning pack off overrides an existing applied state` は純粋関数 `ResourcePackPolicy.decide` しか呼んでおらず、`ResourcePackController.send()` の「適用済みプレイヤーが OFF に切り替える」経路の回帰を防げていない。
- **Before**:
```kotlin
if (!decision.send || deliveryEndpoint == null || artifact == null) {
    pendingRequests.remove(player.uniqueId)
    loadedPlayers -= player.uniqueId
    plugin.refreshPackItemModels(player, false)
```
- **After**:
```kotlin
if (!decision.send || deliveryEndpoint == null || artifact == null) {
    pendingRequests.remove(player.uniqueId)
    val wasLoaded = loadedPlayers.remove(player.uniqueId)
    if (wasLoaded && !decision.send) runCatching { player.removeResourcePacks(requestId) }
    plugin.refreshPackItemModels(player, false)
```

---

#### `B1-M03` `[Med][Bug]` `mode: game_port` 時のループバック公開チェックが `embedded_http.bind_host` を参照している（`FIX-07`）
- **場所**: `src/main/kotlin/net/nakaymi/etherealchronicle/paper/ResourcePackController.kt:58-63, 279-286`
- **What / Why**: `createEndpoint("game_port")` でも `bindHost = settings.bindHost`（=`resource_pack.embedded_http.bind_host`、既定 `"0.0.0.0"`）を使っている。`game_port` モードが実際にバインドしているのは Minecraft サーバーの `plugin.server.ip` であり、`embedded_http.bind_host` ではない。ローカルテストで `server.ip = 127.0.0.1` かつ `embedded_http.bind_host` 未変更（`"0.0.0.0"`）だと `canAdvertise("0.0.0.0", "127.0.0.1")` が誤拒否し、逆に `embedded_http.bind_host: 127.0.0.1` かつ公開サーバー（`server.ip: ""`）だと誤通過する。
- **Before**:
```kotlin
bindHost = bindHost,
```
- **After**:
```kotlin
bindHost = if (modeOverride == "game_port") plugin.server.ip.ifBlank { "0.0.0.0" } else bindHost,
```

---

#### `B1-M04` `[Med][Perf]` `AdventureController.tick()` が毎秒全オンラインプレイヤーの 36 スロットを毎回クローンする（`FIX-08`）
- **場所**: `src/main/kotlin/net/nakaymi/etherealchronicle/paper/AdventureController.kt:54-60, 464-472`
- **What / Why**: `tick()` が `transaction(..., skipIfUnchanged = true)` を毎秒・全プレイヤーに対して呼ぶようになった結果、`skipIfUnchanged` の無変化判定（468 行目）より手前の 464 行目で `player.inventory.storageContents.map { it?.clone() }.toTypedArray()` が毎回実行される。依頼が 0 件・進行変化 0 の通常時でも、毎秒 `プレイヤー数 × 36` 個の `ItemStack`＋`ItemMeta`（PDC/Lore 含む）をメインスレッドで複製し GC 圧を悪化させている。
- **Before**:
```kotlin
val before = p.snapshot()
val inventory = plugin.inventory(player, persistConsumption)
val staged = StagedInventory(inventory)
val beforeStorage: Array<ItemStack?> = player.inventory.storageContents.map { it?.clone() }.toTypedArray()
try {
    mutate(staged)
    if (skipIfUnchanged && before == p && staged.remove.isEmpty() && staged.add.isEmpty()) {
        releaseLock(token); success(); return
    }
```
- **After**:
```kotlin
// mutate(staged) 中は実インベントリを触らない（StagedInventory に記録するだけ）ので、
// skipIfUnchanged 判定を通過した後にのみ beforeStorage をクローンする
try {
    mutate(staged)
    if (skipIfUnchanged && before == p && staged.remove.isEmpty() && staged.add.isEmpty()) {
        releaseLock(token); success(); return
    }
    val beforeStorage: Array<ItemStack?> = player.inventory.storageContents.map { it?.clone() }.toTypedArray()
    ...
```
（※`beforeStorage` を `catch` で参照する場合は `var beforeStorage: Array<ItemStack?>? = null` として非 null 時のみ復元する。）

---

#### `B1-M05` `[Med][Bug]` `profileTransaction()` の保存失敗ロールバックにセッション同一性チェックがない（`FIX-10`）
- **場所**: `src/main/kotlin/net/nakaymi/etherealchronicle/paper/AdventureController.kt:384-391`
- **What / Why**: `transaction()`（475-476 行目）は保存失敗時に `sameSession || offlineWithoutReconnect` を確認してから `p.restore(before); plugin.save(p)` を実行しているが、新設された `profileTransaction()` にはこのチェックが無い。ダンジョン報酬保存中にプレイヤーが切断→即再接続して新しい `PlayerProfile` インスタンスが読み込まれた後に旧保存が失敗すると、旧 `profile` を `before` へ戻して `plugin.save(profile)` し、新セッションのデータを古いスナップショットで上書きしてしまう。
- **Before**:
```kotlin
if (error != null) {
    profile.restore(before)
    plugin.save(profile)
    plugin.logger.severe("Profile transaction failed $id: ${error.message}")
} else success()
```
- **After**:
```kotlin
if (error != null) {
    val sameSession = plugin.profiles[id] === profile
    val offlineWithoutReconnect = Bukkit.getPlayer(id)?.isOnline != true
    if (sameSession || offlineWithoutReconnect) {
        profile.restore(before)
        plugin.save(profile)
    }
    plugin.logger.severe("Profile transaction failed $id: ${error.message}")
} else if (plugin.profiles[id] === profile) {
    success()
}
```

---

#### `B1-M06` `[Med][Bug]` スターター装備のメールボックス退避経路でメニュー閉鎖・スキルスロット・ステータス・図鑑登録が抜ける（`FIX-13`）
- **場所**: `src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt:407-419`
- **What / Why**: ホットバー枠不足でメールボックスへ退避する `if` ブロック（408-415 行目）がそのまま `return@sync` するため、通常経路（416-419 行目）で行われる `profile.discoverItem(item)`、`skillSlot(p)`、`applyStats(p, profile)`、`p.health = max`、`p.closeInventory()`、`ec.profile.welcome` がすべてスキップされる。結果、プレイヤーは職業決定後も選択画面が開いたまま残り、スキルスロットも HP スケーリングも反映されない。さらに 407 行目の `!p.isOnline` 離脱時も `starterGranted = true` 保存済みでスターター装備がメールボックスへ入らない（保存前に `pendingRewards` へ入れておく設計にすれば切断・満杯の両方を一括で防げる）。
- **Before**:
```kotlin
if (c.starterItems.size > (0..7).count { p.inventory.getItem(it) == null }) {
    c.starterItems.groupingBy { it }.eachCount().forEach { (item, amount) -> profile.returnToMailbox(mapOf(item to amount)) }
    save(profile)
    p.sendMessage(message(p, "ec.profile.starter_space"))
    adventure.claimPending(p)
    return@sync
}
```
- **After**:
```kotlin
if (c.starterItems.size > (0..7).count { p.inventory.getItem(it) == null }) {
    val counts = c.starterItems.groupingBy { it }.eachCount()
    profile.returnToMailbox(counts)
    counts.keys.forEach(profile::discoverItem)
    save(profile)
    skillSlot(p); applyStats(p, profile); p.health = p.getAttribute(Attribute.MAX_HEALTH)!!.value
    p.closeInventory()
    p.sendMessage(message(p, "ec.profile.starter_space"))
    adventure.claimPending(p)
    return@sync
}
```

---

#### `B1-M07` `[Med][Bug/Test]` `FishingController.tick()` に旧 `bitePending` 無料バイト復帰ブロックが残存（`FIX-14`）
- **場所**: `src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingController.kt:275-282`
- **What / Why**: `FIX-14` の要件「`bitePending` 解除をトランザクションコールバック側へ一本化し二重 `showBite` を防ぐ」に対し、`beginBite()` 側（241-254 行目）だけ修正され、`tick()` の 275-282 行目に旧フォールバックがそのまま残っている。`s.bitePending == true` の間はトランザクションの `success` / `failure` コールバックだけが状態遷移を行うべきであり、275-282 行目を残すと、非同期エラーや餌拾得のタイミングでロック解除された際に再び無料 `BITE_ALERT`／二重 `showBite` を引き起こす。また FIX-14 の回帰テストも未追加。
- **Before**:
```kotlin
// FishingController.kt:275-282
if (s.bitePending) {
    // 餌の消費と釣果は同じ永続取引で確定します。失敗時は消費されず、セッションを再試行できます。
    if (!plugin.adventure.locked(id) && plugin.inventory(player).count(s.bait ?: "") >= s.baitCountBefore) {
        s.bitePending = false; s.state.phase = FishingPhase.BITE_ALERT; showBite(player)
    } else return@forEach
}
```
- **After**:
```kotlin
// bitePending の解除・キャンセルは beginBite() の transaction コールバックへ一本化する
if (s.bitePending) return@forEach
```

---

#### `B1-M08` `[Med][Bug/Test]` `UiThemeProvider` の `-8px` オフセット位置が背景 `glyph` の後になっており背景ズレが直っていない（`FIX-26`）
- **場所**: `src/main/kotlin/net/nakaymi/etherealchronicle/paper/UiThemeProvider.kt:68-71`, `Wave3RegressionTest.kt:90-93`
- **What / Why**: `resourcepack/assets/ec/font/ui.json` を確認したところ、背景 bitmap グリフ（`\uE100`〜`\uE105`）自体には水平シフトがない。バニラチェスト画面のタイトル開始座標は `x = 8` なので、176px 背景をチェスト左端 `x = 0` に合わせるには **`spaces(TITLE_LEFT_OFFSET)`（`-8px`）を `Component.text(glyph)` の「前」に置く必要がある**。現行の 68-69 行目は `glyph` を描画した**後**の巻き戻し量へ `TITLE_LEFT_OFFSET` を足しているため、背景画像は依然 `x = 8..184` に 8px 右ズレしたまま、タイトル文字だけが背景中央より 8px 左へズレてしまう。また `Wave3RegressionTest.kt:91` は `assertEquals(-8, UiThemeProvider.TITLE_LEFT_OFFSET)` しか検証しておらず、この誤配置を検知できていない。
- **Before**:
```kotlin
return Component.text(glyph).font(uiFont)
    .append(Component.text(spaces(-BACKGROUND_WIDTH + sidePadding + TITLE_LEFT_OFFSET)).font(spaceFont))
    .append(localizedTitle.font(defaultFont))
    .append(Component.text(spaces(sidePadding)).font(spaceFont))
```
- **After**:
```kotlin
return Component.text(spaces(TITLE_LEFT_OFFSET)).font(spaceFont)
    .append(Component.text(glyph).font(uiFont))
    .append(Component.text(spaces(-BACKGROUND_WIDTH + sidePadding)).font(spaceFont))
    .append(localizedTitle.font(defaultFont))
    .append(Component.text(spaces(sidePadding)).font(spaceFont))
```
（※修正後、`ui.theme.mode: full` の実クライアントスクショでグリフの 1px 文字間隔含むズレ有無を確認すること。）

---

### Low（7 件）

#### `B1-L01` `[Low][Bug]` `GamePortPackServer.respond()` が 404 応答でも `Cache-Control: immutable` を返す（`FIX-01` / `FIX-03`）
- **場所**: `src/main/kotlin/net/nakaymi/etherealchronicle/paper/GamePortPackServer.kt:244-247`
- **What / Why**: `FIX-03` で `EmbeddedPackServer` には `cacheControlHeader(status)` が導入されたが、`GamePortPackServer.respond()` には適用されておらず、404 応答にも `Cache-Control: public, max-age=3600, immutable` が返る。
- **After**: `cacheControlHeader(status)` を `internal` ヘルパーとして共通利用する。

#### `B1-L02` `[Low][Idiom]` `PackRequestSniffer.matches()` が `sniffPrefixSize` を参照せず固定長配列全体を比較＋未使用引数 `method`（`FIX-01`）
- **場所**: `src/main/kotlin/net/nakaymi/etherealchronicle/paper/GamePortPackServer.kt:157, 253, 258, 304-305`
- **What / Why**: `sniffPrefix` は長さ 5 のゼロ初期化配列であり、`sniffPrefix.matches(GET_PREFIX)` は `sniffPrefixSize` ではなく常に `5 >= 4` を判定する（初期値 `0` がスペースでないため偶然動くが意図と乖離）。`sniffPrefixSize == GET_PREFIX.size && sniffPrefix.matchesPrefix(sniffPrefixSize, GET_PREFIX)` 等にすべき。また `streamBody(..., method: String, ...)` の `method` 引数が未使用。

#### `B1-L03` `[Low][Style/Idiom]` `ResourcePackController` に `settings.*` の 26 フィールド手動コピーが残存（`FIX-07`）
- **場所**: `src/main/kotlin/net/nakaymi/etherealchronicle/paper/ResourcePackController.kt:15-40`
- **What / Why**: `ResourcePackSettings` へ集約したにもかかわらず、直後に `private val enabled = settings.enabled` 等 26 行をコピーしており、見通し改善の効果が薄い。直接 `settings.xxx` を参照すれば 25 行削減できる。

#### `B1-L04` `[Low][Idiom]` `AdventureController.LockToken` が `data class` のため値等価 `==` で衝突する（`FIX-11`）
- **場所**: `src/main/kotlin/net/nakaymi/etherealchronicle/paper/AdventureController.kt:26`
- **What / Why**: `internal data class LockToken(val playerId: UUID)` は異なるロック取得でも `token1 == token2` が `true` になる（現在は `===` / `!==` で辛うじて区別）。通常の `internal class LockToken(val playerId: UUID)`（または単調連番 `val id: Long` 付き）にすれば `==` の誤用でも壊れない。

#### `B1-L05` `[Low][Bug/Idiom]` ショップ中のオフハンド/防具スロット右クリック売却で別スロットを消費＋`PaperInventoryAccess.exchange` の重複実装（`FIX-12`）
- **場所**: `src/main/kotlin/net/nakaymi/etherealchronicle/paper/AdventureController.kt:148, 165`, `PaperInventoryAccess.kt:16, 39-65`
- **What / Why**: ショップ画面中にオフハンド（`slot == 40`）や防具スロット（`36..39`）を右クリックすると `preferredSlot in slots.indices`（`0..35`）が `false` になり、無関係なストレージ先頭スタック（強化済み含む）が売却される。`sourceSlot !in 0..35` は拒否すべき。また `PaperInventoryAccess.exchange()` と `exchangeFromSlot()` の中身が完全重複しているため、`override fun exchange(remove, add) = exchangeFromSlot(null, remove, add)` に統合すべき。

#### `B1-L06` `[Low][Bug]` UI / リロード周辺の軽微なエッジケース 3 点（`FIX-15` / `FIX-16` / `FIX-20`）
- **場所**:
  1. `ClassSelectionUi.kt:33` / `RpgPlugin.kt:349`: 確認 Dialog 表示中のプレイヤーが切断しても `confirming` から UUID が除去されない（`onQuit` で `classSelectionUi.clear(uuid)` を呼ぶべき。また `player.showDialog(dialog)` 例外時にも `confirming.finish` があると安全）。
  2. `RpgPlugin.kt:121`: `FIX-16` で `language` を `@Volatile` 化したが、起動時の `language = LangCatalog.load(...)` 代入および `config.getString(...)` 読取が依然 async スレッド上にある。async 側で `val loadedLanguage = ...` を作り `sync {}` 内で `language = loadedLanguage` とするのがより確実。
  3. `AdventureController.kt:133`: `compactVisibleShopEntries(...)` に `.take(54)` が無いため、条件一致商品が 55 件以上あるショップで `menu.setItem(54, ...)` が例外を送出する。

#### `B1-L07` `[Low][Bug]` `LangCatalog.DETAIL_PLACEHOLDER` の否定先読みに終了境界がなく色名接頭辞プレースホルダーが不置換・短縮タグが誤置換される（`FIX-18`）
- **場所**: `src/main/kotlin/net/nakaymi/etherealchronicle/paper/LangCatalog.kt:69`
- **What / Why**: `(?!/?(?:...|gold|red|reset|...)[^>]*)` に `(?=>)` 等の境界がないため、`<gold_cost>` や `<reset_time>` のような色名/装飾名で始まるプレースホルダーが除外されて置換されない。一方で `<b>`, `<i>`, `<br>` のような MiniMessage 短縮タグは除外リストに含まれず `detail` で上書きされる。
- **After**: `(?=>)` を付けて完全一致のタグ名のみ除外し、`b|i|u|st|obf|br` も除外リストへ追加する。

---

### Polish（3 件）

- **`B1-P01` `[Polish][Perf]`** (`FIX-06`, `ResourcePackController.kt:85-92`): `bundledArtifact` を `by lazy` 化したが、`external_managed` モードで `/ec pack info` を 1 回呼ぶと ZIP バイト配列全体がヒープに永続保持される。`ResourcePackBuildInfo(sha1, sizeBytes)` のみキャッシュすれば十分。
- **`B1-P02` `[Polish][Idiom]`** (`FIX-04` / `FIX-13`): `GamePortPackServer.kt:310` の `TERMINATOR_MASK = 0xFFFFFFFF.toInt()`（`-1`）とのビット積は 32bit `Int` に対して完全な no-op。`RpgPlugin.kt:410` の `eachCount().forEach { (item, amount) -> profile.returnToMailbox(mapOf(item to amount)) }` は `profile.returnToMailbox(c.starterItems.groupingBy { it }.eachCount())` の 1 呼び出しで済む。
- **`B1-P03` `[Polish][Perf]`** (`FIX-03`, `ResourcePackDistribution.kt:58-63`): `socketWriteWatchdog` がトップレベルの常駐 `ScheduledExecutorService` のまま（デーモンスレッドなので終了は阻害しないが、プラグイン `/reload` やテスト繰り返し時に解放されない）。`EmbeddedPackServer` 単位または明示的クローズ対象にすると綺麗。

---

## 4. 推奨アクション（Batch 1 クローズに向けて）

1. **最優先（High 3 件）**: `B1-H01`（`ResourcePackSettings` 例外で起動クラッシュ）、`B1-H02`（満杯＋自動完了依頼での NPC ソフトロック）、`B1-H03`（高速リログでの `deliverLocked` 報酬消失）を修正し、各ケースの回帰テストを追加する。
2. **要追加修正（Med 5 件 — `FIX-02`, `05`, `13`, `14`, `26` ほか）**: `B1-M01`〜`B1-M08` を解消する（特に `FIX-14` の `FishingController.tick()` 旧ブロック削除と `FIX-26` の `TITLE_LEFT_OFFSET` 位置修正＋テスト改善）。
3. **実機確認**: `FIX-26` 修正後に実 Paper クライアントで `ui.theme.mode: full` のスクリーンショット確認を行い、背景・タイトル位置ズレがないことを最終確認する。
