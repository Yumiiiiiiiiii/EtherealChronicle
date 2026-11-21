# Task 20 スポット・クロスモデルコードレビュー（Antigravity）

- **対象差分**: Codex 実装 `8f3acfa`（`master` マージ済み `872b666` / 作業ブランチ: `ag/review-2`）
- **レビュー日**: 2026-11-22
- **担当モデル**: Antigravity
- **対象ファイル**: `ResourcePackController.kt`, `RpgPlugin.kt`, `ExternalManagedPackTest.kt`, `ResourcePackRequiredPolicyTest.kt`, `config.yml`, `lang/ja_jp.yml`, `lang/en_us.yml`（および相互作用する各 Controller リスナー）
- **方針**: 指摘のみ（コード変更なし）。実機 Paper 26.3 挙動に依存する項目は `要確認` と明記。

---

## サマリー

### 重大度別件数（計 16 件）

| 重大度 | 件数 | 主な内容 |
| :--- | ---: | :--- |
| **High** | **4** | 全 12 ロックハンドラの `EventPriority.HIGHEST` 逆転、エンティティ操作/アイテム拾得/飛び道具/ポータル/各種 `tick()` のロック漏れ、`send()` の `decision.send == false && deliveryAvailable == true` 永久デッドロック、次 tick `runTaskLater` 未キャンセルによる高速リログ競合 |
| **Med** | **6** | `required: true` 下で過去 `OFF` 設定プレイヤーが `hasResourcePack() == false` になる矛盾、拒否切断初回プレイヤーのプロファイル書き出し＆待機中バニラ初期スポーン（要確認）、`debug: true` で中間ステータス無音＆`close()` クリア漏れ、待機者 0 人時・非必須設定時の 12 リスナー常時負荷、純粋ヘルパーのみのテスト盲点＋重複、送信例外時の誤案内キック＆`gamePortFailed` 二重読取 |
| **Low** | **4** | `is Player && as Player` 冗長キャスト＆`finishLoadedJoin` 未使用引数 `snapshot`、4 並列コレクション＆タイムアウト範囲定数の二重定義、`RpgPlugin.kt` 肥大化（1,209 行）、誤記 KDoc＆`config.yml` クランプ範囲未記載 |
| **Polish** | **2** | 差分内の書式・`player.uniqueId` 反復の不統一、`PlayerMoveEvent` ブロック移動判定のヘルパー化（ワールド跨ぎ含む） |

### カテゴリ別件数

| カテゴリ | 件数 | 該当 ID |
| :--- | ---: | :--- |
| `Bug` | 7 | T20-H01, T20-H03, T20-H04, T20-M01, T20-M02, T20-M03, T20-M06 |
| `Security` | 1 | T20-H02 |
| `Perf` | 2 | T20-M04, T20-P02 |
| `Idiom` | 3 | T20-L01, T20-L02, T20-L04 |
| `Style` | 2 | T20-L03, T20-P01 |
| `Test` | 1 | T20-M05 |

---

## High（最優先で修正推奨）

### T20-H01 `[Bug]` `[High]` 全 12 ロックハンドラが `EventPriority.HIGHEST` で登録されており、`NORMAL`/`HIGH`/先登録 `HIGHEST` リスナーのゲーム処理がキャンセル前に実行される
- **箇所**: `src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt:270-342`（競合先: `RpgPlugin.kt:167-178, 374, 386, 724-742, 805-818, 854`, `AdventureController.kt:151, 233, 236`, `EnemyController.kt:179`, `FishingController.kt:154`, `GatheringController.kt:198`）
- **何が問題か**:
  Bukkit/Paper のイベント実行順は **`LOWEST` → `LOW` → `NORMAL` → `HIGH` → `HIGHEST` → `MONITOR`** であるにもかかわらず、Task 20 で追加された 12 個の `lock*UntilPack` がすべて `@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)` で登録されている。
- **なぜ問題か**:
  再接続プレイヤーは `store.load` が数 ms で完了して `profiles[uuid]` が入るため、パック適用待ちの数秒〜最大 120 秒間に操作すると、ロックによるキャンセルが走る**前に**既存ハンドラの副作用がすべて実行される：
  1. `RpgPlugin.interact` (`NORMAL`, line 726) が `lockInteractionUntilPack` (`HIGHEST`, line 302) より先に走り、**消耗品消費 (`adventure.consume` → トランザクション・回復・帰還)**・**スキル発動 (`castSkill` → 回復・バフ・挑発・攻撃)**・スキル切替が通る。
  2. `RpgPlugin.swap` (`NORMAL`, line 724) が `lockOffhandUntilPack` (`HIGHEST`, line 314) より先に走り `cycle(player)` が実行される。
  3. `RpgPlugin.damage` (`HIGH`, line 805) が `lockDamageUntilPack` (`HIGHEST`, line 338) より先に走り `basicAttack()` が発火する。
  4. `EnemyController` (`line 168`)・`GatheringController` (`line 172`)・`FishingController` (`line 173`) は `RpgPlugin` 自身 (`line 178`) より**先に** `registerEvents` されるため、同じ `HIGHEST` の `EnemyController.virtualDamage` (`line 179`)・`FishingController.interact` (`line 154`, しかも `ignoreCancelled = false`)・`GatheringController.interact` (`line 198`, しかも `ignoreCancelled = false`) が `RpgPlugin` のロックより先に走り、釣り開始・採取開始・仮想ダメージ適用が素通りする。
  5. `AdventureController.click` (`LOWEST`, line 151) / `drag` (`LOWEST`, line 233) / `drop` (`NORMAL`, line 236)、`RpgPlugin.move` (`NORMAL`, line 854) もすべてロックより先に実行される。
- **修正案**:
  ロックハンドラを独立リスナー `RequiredPackLockListener` に切り出し、全ハンドラを **`EventPriority.LOWEST`**（`ignoreCancelled = false`）で他の Controller より先に登録する。また `FishingController.interact` / `GatheringController.interact` / `GatheringController.interactDisplay` 等 `ignoreCancelled = false`（または未指定）のハンドラでも `if (e.isCancelled) return`（または `ignoreCancelled = true`）を統一する。
```kotlin
// Before (RpgPlugin.kt:302-305)
@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
fun lockInteractionUntilPack(event: PlayerInteractEvent) {
    if (isPackLocked(event.player)) event.isCancelled = true
}

// After (RequiredPackLockListener — 全Controllerより先に登録)
@EventHandler(priority = EventPriority.LOWEST)
fun lockInteractionUntilPack(event: PlayerInteractEvent) {
    if (isLocked(event.player)) event.isCancelled = true
}
```

---

### T20-H02 `[Security]` `[High]` 必須パック待機ロックの抜け漏れ（エンティティ操作・アイテム拾得・飛び道具・ポータル・チャット・定期 `tick()`）
- **箇所**: `src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt:270-342, 697-713`（漏れ先: `AdventureController.kt:46-58, 74-82`, `TreasureController.kt:57-76`, `GatheringController.kt:215-221`, `EnemyController.kt:103-142`, `RpgPlugin.kt:908-912`）
- **何が問題か**:
  `EventPriority` を `LOWEST` に直しても、以下の操作・システム経路が 12 ハンドラから完全に漏れている：
  1. **`PlayerInteractEntityEvent` / `PlayerInteractAtEntityEvent` の欠落**:
     - `AdventureController.interact` (`line 74`): NPC 右クリックで **Paper Dialog (`showDialogueDialog`)** が開き（`InventoryOpenEvent` を通らないためロック不可）、`ObjectiveKind.TALK` クエスト進行や選択肢アクション（ショップ・ダンジョン入場・アイテム付与）まで到達できる。
     - `TreasureController.interact` (`line 57`): 宝箱 `Interaction` を右クリックして通貨報酬・取引が成立する。
     - `GatheringController.interactDisplay` (`line 215`): 採取ノード `Interaction` ヒットボックスから採取を開始できる。
     - バニラ村人取引・騎乗（馬/ボート/トロッコ）・額縁/防具立て操作も未抑止。
  2. **`EntityPickupItemEvent`（または `PlayerAttemptPickupItemEvent`）の欠落**:
     - 待機中の足元アイテム拾得と `RpgPlugin.itemPickup` (`line 908`, `discoverItem` + `save`) が通る。
  3. **`lockDamageUntilPack` (`RpgPlugin.kt:341`) の飛び道具漏れ＆ターゲット未抑止**:
     - `event.damager is Player` のみ検査しており、`(event.damager as? Projectile)?.shooter as? Player`（弓・トライデント・ポーション等）が漏れる（`RpgPlugin.damage:807` 等と不整合）。`ProjectileLaunchEvent`・`EntityTargetLivingEntityEvent`・`FoodLevelChangeEvent` も未抑止。
  4. **`PlayerPortalEvent` / `PlayerTeleportEvent` / チャット (`AsyncChatEvent`) の欠落**:
     - ポータル突入（サブブロック移動で接触可能）やチャット発言がブロックされない（チャットを許可する仕様なら明示コメント要）。
  5. **定期 `tick()` 3 箇所が待機中プレイヤーを除外していない**:
     - `EnemyController.tick()` (`line 103`): `profiles[uuid]` がある待機中プレイヤーを敵がターゲットにし、`attack()` (`lines 140-141`) の `POISON`/`SLOWNESS` や `applySpecialEffect()` (`lines 154-162`) の `KNOCKUP`（速度変更）・`FREEZE`（`EntityDamageEvent` 経由ではないためキャンセルされない）＋ボス警告/BossBar を付与する。
     - `RpgPlugin.tick()` (`lines 697-713`): 毎秒 `ec.hud.main` ActionBar で待機中表示を上書きし、資源回復・`syncTitleAdvancements` を回す。
     - `AdventureController.tick()` (`lines 46-58`): 待機中プレイヤーの `EXPLORE`/`COLLECT` クエストを進行させる。
- **修正案**:
  ロックリスナーに `PlayerInteractEntityEvent`・`PlayerInteractAtEntityEvent`・`EntityPickupItemEvent`・`PlayerPortalEvent`・`ProjectileLaunchEvent`・`FoodLevelChangeEvent`・`EntityTargetLivingEntityEvent`（および仕様上必要なら `AsyncChatEvent`）を追加し、`lockDamageUntilPack` で `Projectile.shooter` も判定する。さらに `RpgPlugin.tick()`・`AdventureController.tick()`・`EnemyController.tick()` で `isPackLocked(player)` を除外する。
```kotlin
// After (RequiredPackLockListener 追加例)
@EventHandler(priority = EventPriority.LOWEST)
fun lockEntityInteractUntilPack(event: PlayerInteractEntityEvent) {
    if (isLocked(event.player)) event.isCancelled = true
}
@EventHandler(priority = EventPriority.LOWEST)
fun lockItemPickupUntilPack(event: EntityPickupItemEvent) {
    if (isLocked(event.entity)) event.isCancelled = true
}
@EventHandler(priority = EventPriority.LOWEST)
fun lockDamageUntilPack(event: EntityDamageEvent) {
    if (!hasWaiters()) return
    val attacker = (event as? EntityDamageByEntityEvent)?.damager?.let { (it as? Projectile)?.shooter ?: it }
    if (isLocked(event.entity) || isLocked(attacker)) event.isCancelled = true
}
```

---

### T20-H03 `[Bug]` `[High]` `ResourcePackController.send()`: `decision.send == false` かつ `deliveryAvailable == true` の経路で待機ゲートが永久デッドロックする
- **箇所**: `src/main/kotlin/net/nakaymi/etherealchronicle/paper/ResourcePackController.kt:109, 115-121, 131`
- **何が問題か**:
  1. `beginRequiredJoin`（tick `T`）で `requiredPackGate.begin(playerId)` を立てた時点ではまだタイムアウト (`scheduleRequiredTimeout`) が予約されず、tick `T+1` の `send()` 成功時（line 131）に初めて予約される。
  2. もし tick `T`〜`T+1` の間に権限プラグインの非同期読込等で `bypass` が `true` に変わり、かつプロフィール設定が `PackPreference.OFF` だった場合（または `deliveryAvailable == true` のまま `deliveryEndpoint == null || artifact == null` に達した場合）、line 115 の `if (!decision.send || deliveryEndpoint == null || artifact == null)` に入る。
  3. ところが line 119 は
     `if (requiredPackGate.isWaiting(player.uniqueId) && !deliveryAvailable) releaseRequiredWait(player.uniqueId, continueJoin = true)`
     と **`&& !deliveryAvailable`** に限定しているため、`deliveryAvailable == true` のまま `!decision.send` になると `releaseRequiredWait` が**呼ばれない**。
  4. 同様に line 109（`if (pendingRequests[playerId]?.required == true || playerId in loadedPlayers) return`）で `loadedPlayers` により早期 return した場合も、`requiredPackGate` が解放されずタイムアウトも貼られない。
- **なぜ問題か**:
  タイムアウトタスク未登録のまま `requiredPackGate` が解放されないため、プレイヤーは**永久に操作ロック＋`finishLoadedJoin` 未実行のデッドロック状態**に陥り、手動切断するまで抜け出せない。
- **修正案**:
  line 119 の `&& !deliveryAvailable` を削除し、要求を送信せずに return するすべての経路（`!decision.send` / 配信不能 / 既に適用済み `loadedPlayers`）で必ず `if (requiredPackGate.isWaiting(playerId)) releaseRequiredWait(playerId, continueJoin = true)` を呼ぶ。
```kotlin
// Before (ResourcePackController.kt:115-121)
if (!decision.send || deliveryEndpoint == null || artifact == null) {
    pendingRequests.remove(player.uniqueId)
    loadedPlayers -= player.uniqueId
    plugin.refreshPackItemModels(player, false)
    if (requiredPackGate.isWaiting(player.uniqueId) && !deliveryAvailable) releaseRequiredWait(player.uniqueId, continueJoin = true)
    return
}

// After
if (!decision.send || deliveryEndpoint == null || artifact == null) {
    pendingRequests.remove(playerId)
    loadedPlayers -= playerId
    plugin.refreshPackItemModels(player, false)
    if (requiredPackGate.isWaiting(playerId)) releaseRequiredWait(playerId, continueJoin = true)
    return
}
```

---

### T20-H04 `[Bug]` `[High]` `ResourcePackController.beginRequiredJoin`: 次 tick `runTaskLater` が未追跡・未キャンセルで高速リログ時に新セッション状態を破壊する
- **箇所**: `src/main/kotlin/net/nakaymi/etherealchronicle/paper/ResourcePackController.kt:46, 151-157, 188-194, 246-266`
- **何が問題か**:
  `beginRequiredJoin`（line 152）で予約した 1-tick 後の `BukkitTask` 戻り値を保持していないため、`onQuit` / `releaseRequiredWait` / `close` でキャンセルされない。さらにタスク本体（line 153）が `player.isOnline` やセッション一致を確認する**前に**無条件で `scheduledJoinRequests -= playerId` を実行している。
- **なぜ問題か**:
  プレイヤーが tick `T` で接続→即切断→同一 tick 内に再接続（または MockBukkit テスト・プロキシ再接続）すると、旧セッションの 1-tick タスクが tick `T+1` に発火し、新セッションの `scheduledJoinRequests` エントリを消去してしまう。これにより新セッション側の `store.load` からの早すぎる `send()` 抑止が破れ、要求二重送信や送信タイミング崩れを招く。
- **修正案**:
  1-tick 送信タスクの `BukkitTask` を `RequiredWaitSession` に保持し、`releaseRequiredWait` / `onQuit` / `close` で `cancel()` する。タスク内でも現在のセッション参照 (`requiredSessions[playerId] === session`) を検証してから状態変更する。
```kotlin
// Before (ResourcePackController.kt:151-157)
scheduledJoinRequests += playerId
plugin.server.scheduler.runTaskLater(plugin, Runnable {
    scheduledJoinRequests -= playerId
    if (!player.isOnline || !requiredPackGate.isWaiting(playerId)) return@Runnable
    debugLog("dispatching required request after join tick player=$playerId")
    send(player, preferences[playerId] ?: PackPreference.AUTO)
}, 1L)

// After
val session = RequiredWaitSession()
requiredSessions[playerId] = session
session.dispatchTask = plugin.server.scheduler.runTaskLater(plugin, Runnable {
    if (requiredSessions[playerId] !== session) return@Runnable
    session.dispatchTask = null
    if (!player.isOnline) return@Runnable
    debugLog("dispatching required request after join tick player=$playerId")
    send(player, preferences[playerId] ?: PackPreference.AUTO)
}, 1L)
```

---

## Med（次サイクルで修正推奨）

### T20-M01 `[Bug]` `[Med]` `hasResourcePack` と `required: true` の矛盾: 過去に `OFF` 保存済みの非 bypass プレイヤーは必須適用後も未適用扱いになる
- **箇所**: `src/main/kotlin/net/nakaymi/etherealchronicle/paper/ResourcePackController.kt:106, 197-198`, `src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt:243, 1106-1108`
- **何が問題か**:
  非 OP プレイヤーが以前 `resourcePackPreference = "OFF"` を保存した状態で `required: true` サーバーへ接続すると、`join()` line 243 → `send(player, PackPreference.OFF)` の line 106 で `preferences[playerId] = PackPreference.OFF` が記憶される。その後 `ResourcePackPolicy.decide` は必須強制により `send = true, required = true` でパックを強制適用し `loadedPlayers += playerId` となるが、`hasResourcePack()`（lines 197-198）は `preferences[playerId] != PackPreference.OFF` を要求するため **`false`** を返す。
- **なぜ問題か**:
  クライアントに必須パックを強制適用したにもかかわらず、`UiThemeProvider` と `createItemForPlayer` が「パック未適用」と判定し、アイコンやカスタムモデルが表示されない。
- **修正案**:
  必須強制適用中（`required && isDeliveryAvailable() && !bypass`）は `PackPreference.OFF` を無視して `hasResourcePack` を `true` とする（あるいは必須強制が成功した `loadedPlayers` に対して必須適用フラグを保持し、`externalManaged || enforced || preferences[playerId] != PackPreference.OFF` とする）。

---

### T20-M02 `[Bug]` `[Med]` 必須パック拒否/タイムアウトで未プレイ切断した初回プレイヤーのプロファイル書き出し＆待機中の初期スポーン問題（`要確認`）
- **箇所**: `src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt:242-261, 344-350`
- **何が問題か**:
  1. `join()`（line 242）が `whenRequiredPackReady` より**前**に `profiles[player.uniqueId] = profile` を登録しているため、必須パックを拒否・タイムアウトして 1 秒もプレイせずキックされた新規接続者であっても、`onQuit`（line 348）が `save(profile)` を呼び出して `playerdata/<uuid>.yml` をディスクに新規作成する。また待機中に管理者コマンド（`/ec give`, `/ec class`, `/ec starter`）の対象にもなり得る。
  2. 初回プレイヤーの `DemoWorld.luminaSpawn` テレポート（line 256）は `finishLoadedJoin` まで遅れるため、待機中の最大 120 秒間、新規プレイヤーはデフォルトのバニラワールドの初期スポーン地点（夜間・空中・水中など安全地帯外になり得る）に置かれる。空中スポーン時に `lockMovementUntilPack` が `blockY` 変化をキャンセルし続けると、バニラ/Paper の `Flying is not enabled on this server` キックを誘発する恐れがある（`要確認`）。
- **修正案**:
  1. 必須パック完了前（`finishLoadedJoin` 実行前）は `profiles` に登録しない（または `joinFinished: MutableSet<UUID>` 等で未入場プレイヤーの無変更 `save(profile)` を抑止する）。
  2. `lockMovementUntilPack` は水平移動 (`blockX`/`blockZ`) と上昇 (`to.y > from.y`) のみを止めて落下着地を許容するか、待機中の一時的な位置固定方法を実機 Paper 26.3 で確認する。

---

### T20-M03 `[Bug]` `[Med]` `ResourcePackController`: `debug: true` で中間ステータス (`ACCEPTED`/`DOWNLOADED`) がログ出力されない＋`close()` の `observedManagedStatuses` 解放漏れ
- **箇所**: `src/main/kotlin/net/nakaymi/etherealchronicle/paper/ResourcePackController.kt:220-244, 255-266`
- **何が問題か**:
  1. `config.yml` や `docs/RESOURCEPACK_HOSTING.md` では `resource_pack.debug: true` で「判定・送信・ステータス・タイムアウト」をログ出力するとあるが、`onStatus()`（lines 226-243）は `SUCCESSFULLY_LOADED` と `terminalStatuses` のみ `debugLog` しており、`ACCEPTED` や `DOWNLOADED` が無音で無視される。120 秒タイムアウトの切り分け時に「プロンプト放置」か「HTTP ダウンロード詰まり」か「クライアント再読込詰まり」かが判別できない。
  2. Task 20 で `close()`（lines 255-266）へ待機系フィールドの解放を追加した際、`observedManagedStatuses.clear()` が依然として漏れている（既存 FIX-06 と連動）。
- **修正案**:
  `onStatus()` に `else -> debugLog("request status player=${event.player.uniqueId} id=${event.id} status=${event.status}")` を追加し、`close()` に `observedManagedStatuses.clear()` を追加する。

---

### T20-M04 `[Perf]` `[Med]` 待機者 0 人時・非必須設定時にも `PlayerMoveEvent` / `EntityDamageEvent` を含む 12 ハンドラが常時ディスパッチ・HashSet 検索される
- **箇所**: `src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt:266-342`, `src/main/kotlin/net/nakaymi/etherealchronicle/paper/ResourcePackController.kt:173, 367-374`
- **何が問題か**:
  1. `PlayerMoveEvent`（全オンラインプレイヤー×最大 20 回/秒）と `EntityDamageEvent`（全ワールドの全モブ・動物・環境ダメージ）のたびに、`isPackLocked()` → `::resourcePacks.isInitialized` → `HashSet.contains(UUID)` が実行される。
  2. `lockDamageUntilPack`（lines 340-341）は 1 行目で `event.isCancelled = true` を設定した後も、2 行目の `if` を必ず再評価している。
  3. `required: false` / `enabled: false` / `mode: external_managed` / 配信不能の環境では 1 度も待機状態にならないのに、12 個のリスナーが常に登録されたままになる。
- **修正案**:
  1. `RequiredPackGate` に `fun hasWaiters(): Boolean = waiting.isNotEmpty()` を追加し、高頻度イベント冒頭で O(1) 短絡 return する（`if (!hasWaiters()) return`）。
  2. ロックハンドラを独立リスナー `RequiredPackLockListener` に分離し、`required && !externalManaged && isDeliveryAvailable()` のときだけ `registerEvents` する。
```kotlin
// After
@EventHandler(priority = EventPriority.LOWEST)
fun lockMovementUntilPack(event: PlayerMoveEvent) {
    if (!hasWaiters()) return
    if (isLocked(event.player) && event.movedBlock()) event.isCancelled = true
}
```

---

### T20-M05 `[Test]` `[Med]` Task 20 の新設テストが純粋ヘルパーのみで結合テストがゼロ（かつ両ファイルで同一 5 ケース重複）
- **箇所**: `src/test/kotlin/net/nakaymi/etherealchronicle/paper/ResourcePackRequiredPolicyTest.kt:73-100`, `src/test/kotlin/net/nakaymi/etherealchronicle/paper/ExternalManagedPackTest.kt:28-34`
- **何が問題か**:
  1. Task 20 で追加されたテストは `RequiredPackGate`（`MutableSet` の薄いラッパー）と `RequiredPackGatePolicy`（1 行の `&&` と `coerceIn * 20`）の単体検証のみで、`ResourcePackController` の状態遷移（`beginRequiredJoin` → 次 tick `send` → `onStatus` → `whenRequiredPackReady` → タイムアウト / `onQuit`）や、`RpgPlugin` の 12 ロックハンドラと他 Controller リスナーの優先順位競合を検証するテストが 1 本もない。結果として **T20-H01 / H02 / H03 / H04** がすべてテストを素通りした。
  2. `ExternalManagedPackTest.kt:28-34` と `ResourcePackRequiredPolicyTest.kt:86-93` が完全に同一の `RequiredPackGatePolicy.shouldWait(...)` 5 ケースを重複検証している。
- **修正案**:
  MockBukkit 上で以下を検証する結合テストを追加し、重複している `shouldWait` テストを 1 箇所に統合する：
  - 待機中の移動・スキル (`PlayerInteractEvent`)・攻撃 (`EntityDamageByEntityEvent`)・NPC クリック (`PlayerInteractEntityEvent`)・アイテム拾得が他リスナーより先にブロックされること。
  - 次 tick 送信 → `SUCCESSFULLY_LOADED` で `whenRequiredPackReady` が 1 回だけ呼ばれロック解除されること。
  - `DECLINED` / タイムアウト / `onQuit` / 高速リログ / `bypass` 変化時にデッドロックやコールバック二重実行・リークが起きないこと。

---

### T20-M06 `[Bug]` `[Med]` `ResourcePackController.send()`: 送信例外時のキック理由誤案内と `gamePortFailed` 二重読み取り
- **箇所**: `src/main/kotlin/net/nakaymi/etherealchronicle/paper/ResourcePackController.kt:110-115, 133-140`
- **何が問題か**:
  1. Line 133-140 の `catch (error: Exception)` はサーバー側での `sendResourcePacks` 例外にもかかわらず、プレイヤーを `"ec.resource_pack.required_declined"`（「サーバーリストの設定で『サーバーリソースパック: 有効』にして再接続してください」）でキックしている。プレイヤー側設定とは無関係なため誤案内となる。
  2. Line 110 で `val deliveryEndpoint = if (gamePortFailed.get()) endpointFallback else endpoint` を評価した後、line 112 の `isDeliveryAvailable()` が再度 `gamePortFailed.get()` を読み直している（Netty スレッドから非同期に `true` になり得るため、2 回の読み取りで異なるエンドポイントを見得る）。
- **修正案**:
  `deliveryEndpoint` を 1 回だけ確定して `ResourcePackPolicy.deliveryAvailable(enabled, artifact != null, deliveryEndpoint?.mode, externalSha1)` へ渡し、送信例外時は専用の汎用エラー/再接続案内キー（または `required_timeout` 相当のサーバー側失敗メッセージ）を使用する。

---

## Low（改善推奨）

### T20-L01 `[Idiom]` `[Low]` `is Player && ...(as Player)` の冗長キャストと `finishLoadedJoin` の未使用引数 `snapshot`
- **箇所**: `src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt:253, 286, 340-341`
- **何が問題か**:
  1. `RpgPlugin.kt:286, 340, 341` が `if (event.player is Player && isPackLocked(event.player as Player))` のように Java 風の `is` + 明示 `as` キャストをしている一方、直下の lines 292, 298 は `event.whoClicked as? Player ?: return` を使っており 20 行内で流儀が混在している。
  2. `finishLoadedJoin(player: Player, profile: PlayerProfile, snapshot: ContentSnapshot)`（line 253）の `snapshot` 引数がメソッド本体で一切使用されていない。
- **修正案**:
  `isLocked(entity: Any?): Boolean = entity is Player && isPackLocked(entity)` のヘルパーに統一し、`finishLoadedJoin` から未使用引数 `snapshot` を削除する。
```kotlin
// Before (RpgPlugin.kt:286, 340-341)
if (event.player is Player && isPackLocked(event.player as Player)) event.isCancelled = true
...
if (event.entity is Player && isPackLocked(event.entity as Player)) event.isCancelled = true
if (event is EntityDamageByEntityEvent && event.damager is Player && isPackLocked(event.damager as Player)) event.isCancelled = true

// After
private fun isLocked(entity: Any?): Boolean = entity is Player && isPackLocked(entity)

// InventoryOpenEvent:
if (isLocked(event.player)) event.isCancelled = true
// EntityDamageEvent:
val attacker = (event as? EntityDamageByEntityEvent)?.damager?.let { (it as? Projectile)?.shooter ?: it }
if (isLocked(event.entity) || isLocked(attacker)) event.isCancelled = true
```

---

### T20-L02 `[Idiom]` `[Low]` 4 つの並列ミュータブルコレクションとタイムアウト定数の二重定義・冗長条件
- **箇所**: `src/main/kotlin/net/nakaymi/etherealchronicle/paper/ResourcePackController.kt:18, 45-48, 238, 363`
- **何が問題か**:
  1. 必須パック待機中の 1 プレイヤー状態が `requiredPackGate` / `scheduledJoinRequests` / `requiredWaiters` / `requiredTimeouts` の 4 つの独立コレクションに分散しており、状態遷移ごとに 4 箇所を同期させる必要がある（T20-H04 の温床）。
  2. クランプ範囲 `10..600` が line 18 (`coerceIn(10, 600)`) と line 363 (`RequiredPackGatePolicy.timeoutTicks`) に重複記載されている。
  3. `onStatus()` line 238 は `else if (event.status in terminalStatuses)`（line 232）のブロック内なのに、第 3 引数へ再度 `event.status in terminalStatuses` を渡している（常に `true`）。
- **修正案**:
  1 プレイヤー分の待機状態を `RequiredWaitSession(var dispatchTask: BukkitTask?, var timeoutTask: BukkitTask?, val waiters: MutableList<() -> Unit>)` の 1 マップへ集約し、タイムアウト上下限を `RequiredPackGatePolicy` の定数（`MIN_TIMEOUT_SECONDS = 10`, `MAX_TIMEOUT_SECONDS = 600`）に一本化する。line 238 の第 3 引数は `terminalFailure = true` にする。

---

### T20-L03 `[Style]` `[Low]` `RpgPlugin.kt` のさらなる肥大化（1,209 行）— 12 ロックハンドラの分離
- **箇所**: `src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt:265-343`
- **何が問題か**:
  Task 20 で 12 個のロックハンドラと `isPackLocked`（約 80 行）が `RpgPlugin.kt` へ直接追加され、既に巨大だったクラスが 1,209 行に達している（既存 FIX-34 を悪化）。
- **修正案**:
  `RequiredPackLockListener` として独立クラスへ分離し、`ResourcePackController`（または `RpgPlugin.onEnable`）から条件付きで登録する。これにより `RpgPlugin.kt` から約 80 行削減でき、優先順位 `LOWEST`・抜け漏れ管理・テスト容易性がすべて向上する。

---

### T20-L04 `[Idiom]` `[Low]` 誤記 KDoc (`lockBlockBreakUntilPack`) と `config.yml` のタイムアウト範囲未記載
- **箇所**: `src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt:325`, `src/main/resources/config.yml:16-17`
- **何が問題か**:
  1. `RpgPlugin.kt:325` の KDoc が `/** パック適用待ち中はブロック変更と受ける/与えるダメージを止めます。 */` と誤記されている（実際は `BlockBreakEvent` のみで、ブロック設置とダメージは下部の別メソッド）。
  2. `config.yml:16-17` の `required_timeout_seconds: 120` コメントに実装上のクランプ範囲（10〜600 秒）が書かれておらず、運用者が範囲外の値を設定した際の挙動が分かりにくい。
- **修正案**:
  KDoc を責務どおりに修正し、`config.yml` のコメントへ `（10〜600秒の範囲へ丸められます）` と追記する。

---

## Polish（余力があれば）

### T20-P01 `[Style]` `[Polish]` Task 20 差分内の書式（演算子・カンマ周りのスペース）と `player.uniqueId` 反復の不統一
- **箇所**: `src/main/kotlin/net/nakaymi/etherealchronicle/paper/ResourcePackController.kt:103-141`, `src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt:243-260`
- **何が問題か**:
  同一メソッド内で `val deliveryAvailable=isDeliveryAvailable()` / `decide(required,deliveryAvailable,preference,bypass)` と標準スペースあり記述が混在し、`send()` 内で `player.uniqueId` を 12 回反復している（直下の `beginRequiredJoin` では `val playerId = player.uniqueId` に抽出済み）。
- **修正案**:
  `send()` 冒頭で `val playerId = player.uniqueId` を取り出し、差分箇所のスペースを Kotlin 標準書式へ揃える。

---

### T20-P02 `[Perf]` `[Polish]` `lockMovementUntilPack` のブロック移動判定ヘルパー化（ワールド跨ぎ含む）
- **箇所**: `src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt:271-274`
- **何が問題か**:
  `event.from.blockX != event.to.blockX || event.from.blockY != event.to.blockY || event.from.blockZ != event.to.blockZ` は `event.from.world != event.to.world` が抜けており、`RpgPlugin` 内の他ハンドラ（既存 FIX-27）と類似の座標比較が分散している。
- **修正案**:
  `private fun PlayerMoveEvent.movedBlock(): Boolean = from.world != to.world || from.blockX != to.blockX || from.blockY != to.blockY || from.blockZ != to.blockZ` の拡張関数へ共通化する（MockBukkit 互換性も維持できる）。
