# クロスモデルコードレビュー（Antigravity）

- **対象ブランチ / コミット**: `master` @ `77f9aea`（レビュー作業ブランチ: `ag/review-2`）
- **レビュー日**: 2026-11-22
- **担当モデル**: Antigravity
- **対象範囲**: `src/main/kotlin` 全体（特に `paper/` の InvUI・Paper Dialog・UiThemeProvider・ResourcePackController・内蔵HTTP・`game_port` Netty・LangCatalog・DemoWorld v5・BetterModelBridge・各コントローラ、および `data/`・`core/` のローダ/バリデータ/移行・テスト群）
- **方針**: 指摘のみ（コード変更なし）。実機 Paper 26.3 の挙動に依存する推測事項は `要確認` と明記。

---

## サマリー

### 重大度別件数（計 31 件）

| 重大度 | 件数 | 主な内容 |
| :--- | ---: | :--- |
| **High** | **8** | `game_port` 1バイト Slowloris DoS、プロキシ検知不能、`/ec ui pack off` 効かない、職業選択 Dialog×InvUI 閉鎖競合（要確認）、非同期 `language` 競合＆`/ec reload` 反映漏れ、`Component.toString()` 生ダンプ、`LangCatalog.diagnostic` 正規表現クラッシュ、内蔵HTTP Slow-Reader DoS |
| **Med** | **14** | 帯域制限の IP 分離、`external_managed` モードの複数不具合、図鑑2ページ目クリックずれ、InvUI ショップ空きスロット＆クローズ競合、釣り餌無料続行エクスプロイト、採取 `Interaction` 残留、メインスレッド同期 I/O、範囲スキル対象判定、FULL テーマ 8px ずれ（要確認）、毎秒ホットパス負荷、強化ロア再生成、NPC 永続化残留、ダンジョンパレット再利用バグ、MockBukkit テスト盲点 |
| **Low** | **7** | `RpgPlugin` God Class 化、移行コードのコピペ階段・重複、`plain()`/`Regex`/時刻API の不統一、Netty 1バイト配列コピー、線形走査の事前インデックス化、ハードコード残存 |
| **Polish** | **2** | 二重 KDoc・デッドコード・空分岐、セミコロン1行詰め込み |

### カテゴリ別件数

| カテゴリ | 件数 | 該当 ID |
| :--- | ---: | :--- |
| `Bug` | 14 | H-03, H-04, H-05, H-06, H-07, M-02, M-03, M-04, M-05, M-06, M-08, M-09, M-12, M-13 |
| `Security` | 4 | H-01, H-02, H-08, M-01 |
| `Perf` | 5 | M-07, M-10, M-11, L-05, L-06 |
| `Idiom` | 4 | L-01, L-03, L-04, P-01 |
| `Style` | 3 | L-02, L-07, P-02 |
| `Test` | 1 | M-14 |

---

## High（最優先で修正推奨）

### H-01 `[Security]` `[High]` `GamePortPackServer`: `SNIFFING` 状態の 1 バイト Slowloris DoS（タイムアウト・接続上限なし）
- **箇所**: `src/main/kotlin/net/nakaymi/etherealchronicle/paper/GamePortPackServer.kt:132-162`
- **何が問題か**:
  `PackRequestSniffer.decode()` は先頭 1 バイトが `'G'` (0x47) または `'H'` (0x48) だった場合、`prefix` に 1 バイト格納してループを抜けるが、`limiter.acquire(ip)`・`startHeaderTimeout(context)`・`resetIdleTimeout(context)` は `"GET "` または `"HEAD "` の全バイトが揃った後（lines 148-161）まで一切呼ばれない。
- **なぜ問題か**:
  攻撃者がゲームポートへ TCP 接続し `'G'` を 1 バイトだけ送って放置すると、同一 IP 上限 (`maxPerIp`)・総接続上限 (`maxConnections`)・ヘッダタイムアウト (`headerDeadline` / `idleTimeout`) のすべてを素通りし、ゲームポートのソケット/FD と Netty チャネルを無制限に占有できる。
- **修正案**:
  `SNIFFING` で 1 バイト目を保留した時点で `idleTimeout` を開始し、`MINECRAFT` へパススルーするときまたは `channelInactive` / `handlerRemoved` で確実にキャンセルする。
```kotlin
// Before (GamePortPackServer.kt:138-148)
prefix.write(input.readUnsignedByte().toInt())
val candidate = prefix.toByteArray()
...
if (candidate.contentEquals(getPrefix) || candidate.contentEquals(headPrefix)) {
    ...
    startHeaderTimeout(context)
    resetIdleTimeout(context)
}

// After
if (prefix.size() == 0) resetIdleTimeout(context) // 1バイト目の保留時点でタイムアウト開始
prefix.write(input.readUnsignedByte().toInt())
...
// passThroughMinecraft / passBufferedMinecraft 内でも cancelTimeouts() を呼ぶ
```

---

### H-02 `[Security]` `[High]` `GamePortPackServer`: Velocity / BungeeCord / Waterfall を Bukkit プラグイン名で誤判定している
- **箇所**: `src/main/kotlin/net/nakaymi/etherealchronicle/paper/GamePortPackServer.kt:90-94`
- **何が問題か**:
  `unsupportedProxies()` が `listOf("Velocity", "BungeeCord", "Waterfall", "TCPShield").filter { plugin.server.pluginManager.isPluginEnabled(it) }` でプロキシ環境を検知しようとしている。
- **なぜ問題か**:
  Velocity・BungeeCord・Waterfall は別プロセスのプロキシであり、バックエンドの Paper サーバー上に Bukkit プラグインとして読み込まれることはない（`isPluginEnabled` は常に `false`）。結果としてプロキシ配下の実運用サーバーでガードが完全に素通りし、プロキシ-バックエンド間の通信に HTTP スニッファを差し込んだり、外部から到達不能なバックエンドポート URL を配布してしまう。
- **修正案**:
  Paper / Spigot の設定（`server.spigot().paperConfig` の `proxies.velocity.enabled` や `server.spigot().spigotConfig` の `settings.bungeecord`）を検査対象に加える（`TCPShield` のプラグイン名チェックと併用）。
```kotlin
// Before (GamePortPackServer.kt:90-94)
private fun unsupportedProxies(): List<String> {
    val proxies = listOf("Velocity", "BungeeCord", "Waterfall", "TCPShield")
        .filter { plugin.server.pluginManager.isPluginEnabled(it) }
    return proxies
}

// After
private fun unsupportedProxies(): List<String> = buildList {
    val spigot = plugin.server.spigot()
    if (spigot.spigotConfig.getBoolean("settings.bungeecord", false)) add("BungeeCord/Waterfall")
    if (spigot.paperConfig.getBoolean("proxies.velocity.enabled", false)) add("Velocity")
    if (plugin.server.pluginManager.isPluginEnabled("TCPShield")) add("TCPShield")
}
```

---

### H-03 `[Bug]` `[High]` `ResourcePackController`: 適用済みプレイヤーの `/ec ui pack off` が早期 return で無視される
- **箇所**: `src/main/kotlin/net/nakaymi/etherealchronicle/paper/ResourcePackController.kt:96-112`, `src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt:1003-1024`
- **何が問題か**:
  `/ec ui pack off` は `resourcePacks.send(player, PackPreference.OFF)` を呼び出す（`RpgPlugin.kt:1017`）。しかし `ResourcePackController.send()` の line 101 に
  `if (pendingRequests[player.uniqueId]?.required == true || player.uniqueId in loadedPlayers) return`
  があり、すでにパック適用済み (`player.uniqueId in loadedPlayers`) のプレイヤーは lines 107-111（`loadedPlayers -= player.uniqueId` と `refreshPackItemModels(player, false)`）に到達する前に早期 return される。
- **なぜ問題か**:
  1. `required: false` 環境または `etherealchronicle.pack.bypass` 権限を持つ OP が、一度適用したパックを `/ec ui pack off` で無効化しようとしても `loadedPlayers` に残ったままになり、`refreshPackItemModels(player, false)` が呼ばれずアイテムモデルも元に戻らない。
  2. `RpgPlugin.kt:1023` はそのまま「設定を変更しました」と通知するため、見た目と設定が食い違う。
- **修正案**:
  `ResourcePackPolicy.decide(...)` を早期 return より先に評価し、`!decision.send` のとき（`OFF` を選んだとき）は必ず `loadedPlayers` 解除と `refreshPackItemModels(player, false)`（必要なら `player.removeResourcePacks(requestId)`）を実行する。
```kotlin
// Before (ResourcePackController.kt:99-112)
preferences[player.uniqueId] = preference
if (pendingRequests[player.uniqueId]?.required == true || player.uniqueId in loadedPlayers) return
...
val decision = ResourcePackPolicy.decide(required, deliveryAvailable, preference, bypass)
if (!decision.send || deliveryEndpoint == null || artifact == null) {
    pendingRequests.remove(player.uniqueId)
    loadedPlayers -= player.uniqueId
    plugin.refreshPackItemModels(player, false)
    return
}

// After
preferences[player.uniqueId] = preference
val bypass = player.hasPermission("etherealchronicle.pack.bypass")
val deliveryAvailable = isDeliveryAvailable()
val decision = ResourcePackPolicy.decide(required, deliveryAvailable, preference, bypass)
if (!decision.send || deliveryEndpoint == null || artifact == null) {
    pendingRequests.remove(player.uniqueId)
    loadedPlayers -= player.uniqueId
    plugin.refreshPackItemModels(player, false)
    return
}
if (pendingRequests[player.uniqueId]?.required == true || player.uniqueId in loadedPlayers) return
```

---

### H-04 `[Bug]` `[High]` `ClassSelectionUi`: 確認 `Dialog` 表示時に InvUI `onClose` が職業 GUI を再オープンする競合リスク（`要確認`）
- **箇所**: `src/main/kotlin/net/nakaymi/etherealchronicle/paper/ClassSelectionUi.kt:41-53`, `src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt:151-161`
- **何が問題か**:
  `ClassSelectionUi.open()` は `window.addCloseHandler { onClose(player) }` を登録しており、`RpgPlugin.kt:160` の `onClose` は `if (profiles[p.uniqueId]?.classId == null && p.uniqueId !in choosing) sync { if (p.isOnline) menu(p) }` となっている。一方、職業アイコンのクリック時（`ClassSelectionUi.kt:41-43`）は InvUI `Window` を開いたまま `confirm(player, definition)` → `player.showDialog(dialog)` を呼ぶ。
- **なぜ問題か**:
  `confirm()` 呼び出し時点ではまだ `choosing` に追加されておらず `classId == null` のままである。実機 Paper 26.3 で `player.showDialog()` が現在のコンテナ画面を閉じて `InventoryCloseEvent`（→ InvUI `closeHandler`）を発火させた場合、次 tick に `menu(p)` が確認ダイアログを上書きして職業選択 GUI を再表示してしまい、初回職業選択が完了不能になる（`要確認`: Paper 26.3 での `showDialog()` と `InventoryCloseEvent` の発火有無）。また確認ダイアログで「キャンセル」を押したときの `onCancel = {}` が空のため、逆にダイアログを閉じた後に職業選択画面へ戻れず詰む恐れもある。MockBukkit では `paperInventoryUi == false` のため一切検証されていない。
- **修正案**:
  確認ダイアログ表示中のプレイヤーを追跡する集合（例: `confirming: MutableSet<UUID>`）を `ClassSelectionUi` 内に持ち、`confirm()` 開始時に追加して `onClose` での再オープンを抑止する。`onSelected` / `onCancel`（およびキャンセルボタン）で `confirming` から除去し、キャンセル時は明示的に `menu(player)` を再表示する。
```kotlin
// Before (ClassSelectionUi.kt:41-52)
.addClickHandler { click -> confirm(player, definition) }
...
window.addCloseHandler { onClose(player) }

// After
.addClickHandler {
    confirming += player.uniqueId
    confirm(player, definition)
}
...
window.addCloseHandler { if (player.uniqueId !in confirming) onClose(player) }
// confirm の yes/no ハンドラで confirming -= player.uniqueId し、no 側では onCancel(player) で menu(player) を再表示
```

---

### H-05 `[Bug]` `[High]` `RpgPlugin` / `GatheringController` / `FishingController`: 非同期 `language` 再代入のデータ競合と `/ec reload` の更新漏れ
- **箇所**: `src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt:38, 120, 881-891`, `src/main/kotlin/net/nakaymi/etherealchronicle/paper/GatheringController.kt:47-51, 130`, `src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingController.kt:41, 44, 106`
- **何が問題か**:
  1. `RpgPlugin.kt:38` の `private lateinit var language: LangCatalog` がメインスレッド（line 107）で代入された後、非同期タスク（line 120）で `@Volatile` なしに再代入されている。
  2. `/ec reload`（`RpgPlugin.kt:881-891`）で `reloadConfig()` は呼ぶが、`language`（`lang/*.yml` や `config.yml` の `language`）は再読込されない。
  3. `GatheringController.kt:47-51` の `sites` / `sitesById` はコンストラクタ時の `val` 固定で、`reload()`（line 130）で再構築されない。
  4. `FishingController.kt:41, 44` の `engine`（古い `LifeBalance` を保持）と `capacity` も `val` 固定で、`reload()`（line 106）で更新されない。
- **なぜ問題か**:
  1. メインスレッド（コマンド・イベント）から非同期代入直後の `language` を読む際にメモリ可視性が保証されない。
  2. 管理者が `/ec reload` で翻訳ファイル・採取ノード座標・釣りバランス (`life_balance.yml`) を更新して「リロード成功」と表示されても、実行中のゲームロジックには旧定義が使われ続ける。
- **修正案**:
  `language` を `@Volatile`（または `AtomicReference`）にして非同期で読み込み `sync {}` 内で差し替え、`/ec reload` でも `LangCatalog.load` を実行する。`GatheringController.reload()` で `sites` / `sitesById` を再構築し、`FishingController.reload()` で `engine` と `capacity` を新 `snapshot.world.lifeBalance` から作り直す。
```kotlin
// Before (GatheringController.kt:47-51, 130)
private val sites = plugin.snapshot.world.gatheringNodes.values.flatMap { ... }.toMap()
private val sitesById = sites.values.distinctBy(::siteKey).associateBy(::siteKey)
fun reload() { close(); renderAll(); task = ... }

// After
private var sites: Map<String, Site> = emptyMap()
private var sitesById: Map<String, Site> = emptyMap()
private fun rebuildIndex() {
    val built = plugin.snapshot.world.gatheringNodes.values.flatMap { n -> n.locations.map { Site(n, it) } }
    sitesById = built.associateBy(::siteKey)
    sites = built.flatMap { s -> listOf(key(s.location.world, s.location.x, s.location.y - 1, s.location.z) to s, key(s.location.world, s.location.x, s.location.y, s.location.z) to s) }.toMap()
}
fun reload() { close(); rebuildIndex(); renderAll(); task = ... }
```

---

### H-06 `[Bug]` `[High]` `GatheringController` / `RpgPlugin`: `Component.toString()` がプレイヤー向けチャットへ生ダンプされる（＋内部アイテム ID 露出）
- **箇所**: `src/main/kotlin/net/nakaymi/etherealchronicle/paper/GatheringController.kt:314, 319`, `src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt:1097, 1109`, `src/main/kotlin/net/nakaymi/etherealchronicle/paper/AdventureController.kt:129`
- **何が問題か**:
  以下の 4 箇所で Adventure `Component` に対して `plain(...)` ではなく `.toString()` を呼んでプレースホルダーに渡している。
  - `GatheringController.kt:314`: `.ifEmpty { plugin.message(player, "ec.gathering.reward_empty").toString() }`
  - `GatheringController.kt:319`: `plugin.message(player, "ec.gathering.respawned").toString()`
  - `RpgPlugin.kt:1097`: `Bukkit.getPlayer(it)?.name ?: message(player, "ec.party.offline").toString()`
  - `RpgPlugin.kt:1109`: `message(player, if (fishing.debugCancel(player)) ...).toString()`
  加えて `GatheringController.kt:314` (`"${it.item} ×${it.amount}"`) と `AdventureController.kt:129` (`"${it.item} ×${it.amount}"`) ではアイテム表示名ではなく内部 ID (`ec:item_...`) をそのままプレイヤーに表示している。
- **なぜ問題か**:
  `Component.toString()` は Adventure のデバッグ表現 `TextComponentImpl{content="...", style=StyleImpl{...}}` を返すため、採取空振り時・リスポーン中エラー時・オフラインパーティ員表示時・釣りデバッグキャンセル時にチャット欄が生クラスダンプで壊れる。
- **修正案**:
  `Component` をそのままプレースホルダーに渡すか（`LangCatalog.component` は `Component` 値を `Placeholder.component` で扱える）、文字列結合が必要なら `plain(...)` を使う。アイテム名も `plugin.contentName(player, it.item)` に統一する。
```kotlin
// Before (GatheringController.kt:314, 319)
val items = result.joinToString { "${it.item} ×${it.amount}" }
    .ifEmpty { plugin.message(player, "ec.gathering.reward_empty").toString() }
...
val reason = if (error.message == "ec.gathering.respawned") plugin.message(player, "ec.gathering.respawned").toString() else error.message.orEmpty()

// After
val items = if (result.isEmpty()) plugin.message(player, "ec.gathering.reward_empty")
else Component.text(result.joinToString { "${plugin.contentName(player, it.item)} ×${it.amount}" })
...
val reason = if (error.message == "ec.gathering.respawned") plugin.message(player, "ec.gathering.respawned")
else plugin.diagnostic(player, error.message.orEmpty())
```

---

### H-07 `[Bug]` `[High]` `LangCatalog.diagnostic`: `Regex.replace` のエスケープ漏れによる例外クラッシュと MiniMessage タグ破壊
- **箇所**: `src/main/kotlin/net/nakaymi/etherealchronicle/paper/LangCatalog.kt:49-53`
- **何が問題か**:
  ```kotlin
  val detail = match.groups[2]?.value?.takeIf(String::isNotBlank) ?: return@replace template
  val placeholder = Regex("<(\\w+)>")
  if (placeholder.containsMatchIn(template)) placeholder.replace(template, detail) else "$template: $detail"
  ```
  1. `Regex.replace(input, replacement: String)` は `replacement` 内の `\` と `$` を正規表現の後方参照として解釈するため、`detail` に Windows パス (`C:\...`) や `$1` 等が含まれると `IllegalArgumentException` / `IndexOutOfBoundsException` でクラッシュする。
  2. `<(\w+)>` は MiniMessage の装飾タグ（`<red>`, `<gold>`, `<gray>` 等）にもマッチするため、テンプレートに色タグがあるとタグ自体が `detail` に書き換わってしまう。また最後に `Component.text(translated)` としているため、そもそも MiniMessage タグが装飾されず生文字列で表示される。
- **なぜ問題か**:
  `/ec validate` や `/ec reload` のエラーメッセージ（ファイルパスを含む）をプレイヤーへ返そうとした瞬間に二次例外でコマンド応答が失敗する。
- **修正案**:
  `Regex.escapeReplacement(detail)` を使うか、ラムダ版 `replace(template) { detail }`（置換文字列をリテラル扱いする）を使用し、MiniMessage 形式のテンプレートなら `MiniMessage.deserialize` と `Placeholder.unparsed` を通す（また `Regex` は `companion object` に定数化する）。
```kotlin
// Before (LangCatalog.kt:50-51)
val placeholder = Regex("<(\\w+)>")
if (placeholder.containsMatchIn(template)) placeholder.replace(template, detail) else "$template: $detail"

// After
// プレーンテキスト置換する場合（$ や \ を安全にリテラル展開）
val matchPlaceholder = PLACEHOLDER_KEY.find(template)
if (matchPlaceholder != null) template.replace(matchPlaceholder.value, detail) else "$template: $detail"
```

---

### H-08 `[Security]` `[High]` `EmbeddedPackServer`: 書き込みタイムアウト・帯域制限の欠如による Slow-Reader スレッドプール枯渇 DoS
- **箇所**: `src/main/kotlin/net/nakaymi/etherealchronicle/paper/ResourcePackDistribution.kt:170-180`
- **何が問題か**:
  `EmbeddedPackServer` はブロッキング `Socket` ＋固定スレッドプール (`maxConcurrent = 32`) を使用し、`writeResponse()` で `output.write(bytes)` を一括実行している。`socket.soTimeout` は読み取り (`InputStream.read`) にのみ作用し、TCP 送信バッファ詰まりによる `OutputStream.write` のブロックには効かない。
- **なぜ問題か**:
  攻撃者が `GET /pack/<sha1>.zip` を送った後、TCP 受信ウィンドウを 0 にして読み込みを停止すると、ワーカースレッドが OS の TCP タイムアウト（数分〜数時間）まで解放されない。`maxPerIp = 4` でも 8 IP から接続するだけで 32 スレッド全部が詰まり、正規プレイヤーのリソパ配布が完全停止する（`required: true` なら新規接続全員がキックされる）。
- **修正案**:
  チャンク単位で書き出しつつ、送信全体の上限時間（例: `writeDeadlineMs`）を監視し超過したらソケットを閉じる監視タスクを設けるか、Java NIO / Netty ベースの非同期送信に切り替える。あわせて公開環境向けガイドにリバースプロキシ・外部ホスト利用を明記する。
```kotlin
// Before (ResourcePackDistribution.kt:178-179)
if (!headOnly && bytes.isNotEmpty()) output.write(bytes)
output.flush()

// After（簡易対策: 書き込み全体デッドラインを別スケジューラで強制 close）
val writeGuard = watchdog.schedule({ runCatching { socket.close() } }, writeDeadlineMillis, TimeUnit.MILLISECONDS)
try {
    if (!headOnly && bytes.isNotEmpty()) output.write(bytes)
    output.flush()
} finally {
    writeGuard.cancel(false)
}
```

---

## Med（次サイクルで修正推奨）

### M-01 `[Security]` `[Med]` `GamePortPackServer`: 帯域制限がグローバル共有ではなく IP 単位＋切断で即リセットされる
- **箇所**: `src/main/kotlin/net/nakaymi/etherealchronicle/paper/GamePortPackServer.kt:281-324`
- **何が問題か**:
  `PackConnectionLimiter` の KDoc（line 281）は「共有帯域予算を適用します」とあるが、実装は `nextSendNanos = ConcurrentHashMap<String, AtomicLong>()` で IP ごとに別スケジュールを持っている。さらに `release()`（line 312）でその IP の接続が 0 になると `nextSendNanos.remove(ip)` で履歴を破棄する。
- **なぜ問題か**:
  1. `maxConnections = 16, maxPerIp = 2, bandwidth_kb_per_sec = 1024` の設定でも 8 IP 同時接続で最大 `8 MB/s` 送信され、ゲームトラフィック保護の前提が崩れる。
  2. 同一 IP でも短い接続を繰り返すと `nextSendNanos` が毎回消えて帯域制限がリセットされる。
- **修正案**:
  グローバル共有の `private val globalNextSendNanos = AtomicLong()` を追加し、IP 予算とグローバル予算の大きい方（またはグローバル単一トークンバケット）で遅延を計算する。
```kotlin
// Before (GamePortPackServer.kt:290, 318-324)
private val nextSendNanos = ConcurrentHashMap<String, AtomicLong>()
fun reserveDelayNanos(ip: String, bytes: Int): Long {
    ...
    val schedule = nextSendNanos.computeIfAbsent(ip) { AtomicLong() }
    val start = schedule.getAndUpdate { previous -> maxOf(previous, now) + duration }
    return (start - now).coerceAtLeast(0)
}

// After
private val globalNextSendNanos = AtomicLong()
fun reserveDelayNanos(ip: String, bytes: Int): Long {
    val duration = bytes.toLong() * NANOS_PER_SECOND / (bandwidthKbPerSecond.toLong() * 1024)
    val now = System.nanoTime()
    val start = globalNextSendNanos.getAndUpdate { previous -> maxOf(previous, now) + duration }
    return (start - now).coerceAtLeast(0)
}
```

---

### M-02 `[Bug]` `[Med]` `ResourcePackController`: `external_managed` モードの複数不具合（リーク・中間ステータスによるモデル剥離・同期 JAR 読込）
- **箇所**: `src/main/kotlin/net/nakaymi/etherealchronicle/paper/ResourcePackController.kt:139-147, 180-186, 211-231`
- **何が問題か**:
  1. `close()`（lines 180-186）が `observedManagedStatuses.clear()` を呼び忘れている。
  2. `applyManagedStatus()`（lines 228-230）が `ACCEPTED` / `DOWNLOADED` などの中間ステータスでも `loaded = false` と扱い、`loadedPlayers -= player.uniqueId` と `refreshPackItemModels(player, false)` を毎回実行している。`expected_pack_id` が空（既定値）の状態で他プラグイン（例: BetterModel 等）がパックを送信すると、既にロード済みだった本プラグインのアイテムモデルが剥がれる。
  3. `buildInfo()`（lines 139-147）は `external_managed` 時に `artifact == null` のため、`/ec pack info` を呼ぶたびにメインスレッドで JAR から ZIP 全体を同期読み込みする。
- **なぜ問題か**:
  他プラグインと複数パック併用する `external_managed` の主用途で、中間ステータスや別パック拒否のたびにカスタムモデルが点滅・消失する。また `/ec pack info` 連打でメインスレッド I/O が繰り返される。
- **修正案**:
  1. `close()` に `observedManagedStatuses.clear()` を追加。
  2. `applyManagedStatus()` では `SUCCESSFULLY_LOADED` のとき `loaded = true`、終端失敗ステータス (`terminalStatuses`) のときだけ `loaded = false` とし、`ACCEPTED`/`DOWNLOADED` では既存の `loadedPlayers` を維持する。
  3. `buildInfo()` の結果は遅延キャッシュするか、SHA-1 とサイズだけを保持する。
```kotlin
// Before (ResourcePackController.kt:228-230)
val loaded = ManagedPackPolicy.isLoaded(status)
if (loaded) loadedPlayers += player.uniqueId else loadedPlayers -= player.uniqueId
plugin.refreshPackItemModels(player, loaded)

// After
when (status) {
    PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED.name -> {
        loadedPlayers += player.uniqueId
        plugin.refreshPackItemModels(player, true)
    }
    in terminalStatusNames -> {
        loadedPlayers -= player.uniqueId
        plugin.refreshPackItemModels(player, false)
    }
}
```

---

### M-03 `[Bug]` `[Med]` `RpgPlugin` / `CodexController`: 図鑑エリア一覧の 2 ページ目以降のクリックで常に 1 ページ目のエリアが開かれる（＋未使用メソッド残存）
- **箇所**: `src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt:589-599`, `src/main/kotlin/net/nakaymi/etherealchronicle/paper/CodexController.kt:26-34, 74-79`
- **何が問題か**:
  `CodexController.openCodex()`（line 26）は 45 件単位でページング描画しているが、`RpgPlugin.codexAction()`（line 597）は
  `snapshot.world.areas.values.sortedBy { it.name }.getOrNull(slot)`
  と、現在のページ番号 (`page * 45`) を加算せずに `slot` をそのままインデックスに使っている。また `CodexController.kt:74-79` の `private fun codexTabs(...)` はどこからも呼ばれていないデッドコードである。
- **なぜ問題か**:
  エリア数が 45 を超えた場合、2 ページ目以降のスロットをクリックしても 1 ページ目のエリアが開かれる。
- **修正案**:
  `CodexController` に `openAreaAtSlot(player, slot)` を持たせ、現在の `pageStates[player.uniqueId]?.page ?: 0` を考慮した `page * 45 + slot` でエリアを引く（`codexTabs` は削除）。
```kotlin
// Before (RpgPlugin.kt:597)
else -> if (kind == "codex-areas") snapshot.world.areas.values.sortedBy { it.name }.getOrNull(slot)?.let { openCodexArea(player, it.id) }

// After (CodexController へ委譲)
fun selectAreaSlot(player: Player, slot: Int) {
    if (slot !in 0 until 45) return
    val currentPage = pageStates[player.uniqueId]?.takeIf { it.key == "codex-areas:" }?.page ?: 0
    val area = plugin.snapshot.world.areas.values.sortedBy { it.name }.getOrNull(currentPage * 45 + slot) ?: return
    openCodexArea(player, area.id)
}
```

---

### M-04 `[Bug]` `[Med]` `AdventureController`: ショップ条件不成立時の空きスロット発生と `invUiShops` のウィンドウ非紐付けクリーンアップ
- **箇所**: `src/main/kotlin/net/nakaymi/etherealchronicle/paper/AdventureController.kt:127-136, 152-158, 186-191`
- **何が問題か**:
  1. `showShop()`（line 127-132）が `shop.entries.forEachIndexed { index, e -> if (e.condition.matches(p)) menu.setItem(index, ...) }` としているため、条件を満たさない商品が途中にあるとショップ GUI に空白スロットが歯抜けで並ぶ。
  2. InvUI ショップ画面の売却判定を `invUiShops: MutableMap<UUID, String>` と `InventoryCloseEvent` で別管理しており（lines 134, 152, 186-188）、`openRpgMenu()` が `Window.addCloseHandler` を使わず Bukkit の汎用 `InventoryCloseEvent` に依存している。
- **なぜ問題か**:
  1. 条件付き商品が多いショップほど見た目が歯抜けになり、購入クリック側（line 133, 195）も `shop.entries.getOrNull(slot)` に強く結合している。
  2. 別のメニューからショップを開いたりショップから別 InvUI 画面へ遷移する際のクローズイベント順序に依存し、右クリック売却が誤作動または無効化される脆弱な構造になっている。
- **修正案**:
  表示する商品を先に `val visible = shop.entries.filter { it.condition.matches(p) }` で詰めて `visible.getOrNull(slot)` で購入対象を解決し、`InvUiInventoryAdapter.open` に `onClose: () -> Unit = {}` を追加してウィンドウ単位で `invUiShops` を安全に管理する。
```kotlin
// Before (AdventureController.kt:127-134)
shop.entries.forEachIndexed { index, e ->
    if (e.condition.matches(p)) menu.setItem(index, ...)
}
val migrated = plugin.openRpgMenu(player, menu) { slot, click -> if (click.isLeftClick) buyShopSlot(player, id, slot) }

// After
val visible = shop.entries.filter { it.condition.matches(p) }
visible.forEachIndexed { index, e -> menu.setItem(index, ...) }
val migrated = plugin.openRpgMenu(player, menu, onClose = { invUiShops.remove(player.uniqueId, id) }) { slot, click ->
    if (click.isLeftClick) visible.getOrNull(slot)?.let { buyShopEntry(player, id, it.id) }
}
```

---

### M-05 `[Bug]` `[Med]` `FishingController`: 餌消費失敗時に餌補正付き魚を無料で釣れるエクスプロイト＆`bitePending` 二重 `showBite`
- **箇所**: `src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingController.kt:241-251, 281-288`
- **何が問題か**:
  1. `beginBite()`（lines 246-250）では、キャスト時に餌のレア度補正・待ち時間短縮を適用して魚を抽選済み（lines 193-200）にもかかわらず、バイト時の餌消費トランザクションが失敗した場合に `catch` ブロックがそのまま `s.state.phase = FishingPhase.BITE_ALERT; showBite(player)` を実行して続行している。
  2. `tick()`（lines 281-286）の `if (s.bitePending)` 内で `!plugin.adventure.locked(id) && count(bait) >= baitCountBefore` をチェックしているが、トランザクション成功直後にプレイヤーが餌を拾う等して所持数が元に戻ると、`transaction` の `success` コールバックと二重に `showBite(player)` が走る。
- **なぜ問題か**:
  キャスト直後に餌をチェストへ移す・ドロップする等で消費を意図的に失敗させれば、高級餌のレア魚抽選と短縮効果を**餌を1個も消費せずに**無限利用できる。
- **修正案**:
  餌消費のトランザクションが失敗した場合はセッションをキャンセル（`cancel(player, "ec.fishing.bait_failed")`）して魚を逃がす。また `bitePending` の解除は `transaction` の成功/失敗コールバック（および `onFailure`）だけに一本化する。
```kotlin
// Before (FishingController.kt:250)
} catch (error: Exception) {
    s.bitePending = false
    s.state.phase = FishingPhase.BITE_ALERT
    player.sendMessage(plugin.message(player, "ec.fishing.bait_failed"))
    showBite(player)
}

// After
} catch (error: Exception) {
    cancel(player, "ec.fishing.bait_failed", FishingEndReason.CANCELLED)
}
```

---

### M-06 `[Bug]` `[Med]` `GatheringController`: `ChunkLoadEvent` で孤立した `Interaction` ヒットボックスが掃除されず残留する
- **箇所**: `src/main/kotlin/net/nakaymi/etherealchronicle/paper/GatheringController.kt:158-163, 186-190`
- **何が問題か**:
  `renderSite()` では `BlockDisplay`・`TextDisplay` に `displayKey` (`gather_node`) を、`Interaction` に `hitboxKey` (`gather_node_hitbox`) を付与している（lines 151, 156, 161）。しかし `chunk()`（line 187）の孤立エンティティ掃除は `displayKey` のみを確認しており、`hitboxKey` を持つ `Interaction` エンティティを対象外にしている。
- **なぜ問題か**:
  異常終了後やチャンクアンロード・リロードのタイミングで管理外の `Interaction` エンティティが残留・蓄積し、見た目とズレたクリック判定やエンティティ肥大化を引き起こす。
- **修正案**:
  `chunk()` のフィルタで `displayKey` または `hitboxKey` を持つエンティティの両方を対象にし、管理中 UUID セット (`activeDisplayIds`) に含まれないものをすべて除去する。
```kotlin
// Before (GatheringController.kt:187-189)
e.chunk.entities.filter { it.persistentDataContainer.has(displayKey, PersistentDataType.STRING) }.forEach { entity ->
    if (displays.values.flatten().none { it == entity.uniqueId }) entity.remove()
}

// After
val activeIds = displays.values.flatten().toHashSet()
e.chunk.entities.filter { entity ->
    val pdc = entity.persistentDataContainer
    (pdc.has(displayKey, PersistentDataType.STRING) || pdc.has(hitboxKey, PersistentDataType.STRING)) &&
        entity.uniqueId !in activeIds
}.forEach(org.bukkit.entity.Entity::remove)
```

---

### M-07 `[Perf]` `[Med]` `PaperInventoryAccess` / `RpgPlugin` / `AdventureController`: メインスレッド同期 `player.saveData()` と起動/リロード時の同期 `reloadData()`
- **箇所**: `src/main/kotlin/net/nakaymi/etherealchronicle/paper/PaperInventoryAccess.kt:38`, `src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt:435, 446-462, 570`, `src/main/kotlin/net/nakaymi/etherealchronicle/paper/AdventureController.kt:425, 434`
- **何が問題か**:
  1. ショップ売買・クエスト完了・強化・ロールバックのたびにメインスレッドで `player.saveData()`（プレイヤー `.dat` の同期ファイル書き込み）を呼び出している。
  2. `reloadAdvancementPack()`（`RpgPlugin.kt:446-462`）がメインスレッドで全進捗 JSON のファイル書き込み (`AdvancementDatapack.install`) と `server.reloadData()` / `server.updateResources()` を同期実行している。
- **なぜ問題か**:
  1. 多人数サーバーで同時にショップ売買・採取・強化が行われるとメインスレッドのディスク I/O 待ちで TPS が急落する（SSD でも fsync/リネーム待ちが tick 予算 50ms を食い潰す）。
  2. `/ec reload` 時に `server.reloadData()` が走ると全チャンク・全レシピ・全進捗がメインスレッドで再読込され、全プレイヤーに数秒のラグスパイクが発生する。
- **修正案**:
  1. `AdvancementDatapack.install(...)` のファイル書き出し部分は `onEnable` / `/ec reload` の既存非同期ブロック側で実行し、メインスレッドでは `installation.changed` のときだけ `datapackManager` 反映を行う。
  2. `player.saveData()` は設定フラグまたは重要取引（強化・高価取引）に限定するか、Paper のプレイヤー自動保存間隔と二重発行防止設計（`pendingRewards` メールボックス）の役割分担を見直す。

---

### M-08 `[Bug]` `[Med]` `RpgPlugin.castSkill`: 範囲スキルがダンジョン専用敵の `participants` 判定を素通りして `hit()` を呼ぶ＋味方抽出条件の括弧不足
- **箇所**: `src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt:666, 674-677`
- **何が問題か**:
  1. Line 675: `val targets = if (skill.radius > 0) target.getNearbyEntities(...).filterIsInstance<LivingEntity>().filter { it.persistentDataContainer.has(enemyKey) } + target else listOf(target)`
     主対象 `target` には line 661 で `participants` 検証を行っているが、範囲巻き込み (`skill.radius > 0`) の周辺敵には `participants` フィルタと `distinctBy { it.uniqueId }` がない。
  2. Line 666: `it.uniqueId in recipientIds && it.location.distanceSquared(player.location) <= skill.allyRadius * skill.allyRadius || it.uniqueId == player.uniqueId` — `&&` と `||` が括弧なしで並んでいるため意図が一見で読み取りにくい。
- **なぜ問題か**:
  範囲スキルで無関係なパーティ専有の敵にも `hit()`（ステータス再計算・ダメージ計算・`target.damage()` 発火）が毎回実行される（`EnemyController.virtualDamage` で最終キャンセルはされるが、ダメージイベント発火とステータス計算が無駄に走り、他リスナーの副作用も誘発する）。
- **修正案**:
  `participants` フィルタと `distinctBy` を `targets` 構築時に適用し、line 666 も括弧または `if` で明示する。
```kotlin
// Before (RpgPlugin.kt:675)
val targets = if (skill.radius > 0) target.getNearbyEntities(skill.radius, skill.radius, skill.radius)
    .filterIsInstance<LivingEntity>().filter { it.persistentDataContainer.has(enemyKey) } + target else listOf(target)

// After
val targets = buildList {
    add(target)
    if (skill.radius > 0) {
        target.getNearbyEntities(skill.radius, skill.radius, skill.radius)
            .asSequence()
            .filterIsInstance<LivingEntity>()
            .filter { it.uniqueId != target.uniqueId && it.persistentDataContainer.has(enemyKey) }
            .filter { enemies.state(it.uniqueId)?.participants?.let { p -> p.isEmpty() || player.uniqueId in p } != false }
            .forEach(::add)
    }
}
```

---

### M-09 `[Bug]` `[Med]` `UiThemeProvider`: `ThemeMode.FULL` の背景グリフにチェスト GUI タイトル開始位置 (+8px) 補正がない（`要確認`）
- **箇所**: `src/main/kotlin/net/nakaymi/etherealchronicle/paper/UiThemeProvider.kt:58-73`
- **何が問題か**:
  `title()` が `Component.text(glyph).font(uiFont).append(Component.text(spaces(-BACKGROUND_WIDTH + sidePadding)).font(spaceFont))...` としており、先頭にチェストタイトル原点（左端から `+8px`）を相殺する負スペース（通常 `-8px`）を入れていない。
- **なぜ問題か**:
  Minecraft クライアントのチェストコンテナ画面ではタイトル文字の描画開始位置がコンテナ左端 +8px にあるため、176px 幅の背景テクスチャを先頭 `-8px` 補正なしで描画すると背景全体が右に 8px ずれ、9×6 スロットと枠が噛み合わなくなる（`要確認`: 実クライアントで `ui.theme.mode: full` に設定して目視確認が必要）。
- **修正案**:
  背景グリフの前に `spaces(-TITLE_LEFT_OFFSET)`（`TITLE_LEFT_OFFSET = 8`）を挿入し、後続のタイトル開始位置もそれに合わせて調整する（実機スクショで要確認）。
```kotlin
// Before (UiThemeProvider.kt:68-72)
return Component.text(glyph).font(uiFont)
    .append(Component.text(spaces(-BACKGROUND_WIDTH + sidePadding)).font(spaceFont))
    .append(localizedTitle.font(defaultFont))
    .append(Component.text(spaces(sidePadding)).font(spaceFont))

// After（実クライアントでオフセット値を要確認）
return Component.text(spaces(-TITLE_LEFT_OFFSET)).font(spaceFont)
    .append(Component.text(glyph).font(uiFont))
    .append(Component.text(spaces(-BACKGROUND_WIDTH + TITLE_LEFT_OFFSET + sidePadding)).font(spaceFont))
    .append(localizedTitle.font(defaultFont))
```

---

### M-10 `[Perf]` `[Med]` `RpgPlugin` / `AdventureController` / `EnemyController`: 毎秒・毎移動・毎 10tick ホットパスでの過剰コピーと二重計算
- **箇所**: `src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt:607-621, 763-770`, `src/main/kotlin/net/nakaymi/etherealchronicle/paper/AdventureController.kt:42-60`, `src/main/kotlin/net/nakaymi/etherealchronicle/paper/EnemyController.kt:159-166`
- **何が問題か**:
  1. `AdventureController.tick()`（毎秒・全プレイヤー）: `val before = p.snapshot()`（line 52）で全マップ・全リストをディープコピーした後に `before != p` で全フィールド比較している。クエスト変化がない平常時でも毎秒全員分のディープコピーが発生する。
  2. `RpgPlugin.tick()`（毎秒・全プレイヤー）: line 614 で `val s = effectiveStats(p, profile); applyStats(p, profile)` を呼んでいるが、`applyStats()`（line 417）の内部でもう一度 `effectiveStats(p, profile)` を呼んでおり**完全に二重計算**。しかも `equippedDefinitions()` は毎回 `enhancedStats()`（40回二分探索）×装備数＋インベントリ全36スロット走査 (`inventory(p).count(id)`) を行っている。さらに毎秒全プレイヤーに `syncTitleAdvancements()`（全タイトル数分の `NamespacedKey` 生成＋`getAdvancement`＋`getAdvancementProgress`）を回している。
  3. `RpgPlugin.move()`（`PlayerMoveEvent`・毎 tick 発火しうる）: 視点回転のみ（座標移動なし）でも `Position` を2つ生成して全エリアを線形走査している。
- **なぜ問題か**:
  オンラインプレイヤーが 50〜100 人規模になると、毎秒の数千〜数万オブジェクト生成による GC 停止とメインスレッド CPU 消費が無視できなくなる。
- **修正案**:
  1. `QuestEngine.event()` が `Boolean`（変化有無）を返すようにし、`AdventureController.tick()` の `p.snapshot()` ディープコピーを廃止する。
  2. `applyStats(p, s)` に計算済み `Stats` を渡して二重計算を解消し、`syncTitleAdvancements()` は状態変化時（討伐・採取・強化・アイテム取得・ログイン時）のみ実行する。
  3. `PlayerMoveEvent` ではブロック座標（`from.blockX == to.blockX && from.blockY == to.blockY && from.blockZ == to.blockZ && from.world == to.world`）が変わらない限りエリア判定を即スキップする。
```kotlin
// Before (RpgPlugin.kt:614, 416-418)
val s = effectiveStats(p, profile); applyStats(p, profile); applyTitle(p, profile)
...
private fun applyStats(p: Player, profile: PlayerProfile) {
    val max = effectiveStats(p, profile).hp.coerceIn(1.0, content.snapshot().balance.statCap)
    ...
}

// After
val s = effectiveStats(p, profile)
applyStats(p, s)
applyTitle(p, profile)
...
private fun applyStats(p: Player, stats: Stats) {
    val max = stats.hp.coerceIn(1.0, content.snapshot().balance.statCap)
    ...
}
```

---

### M-11 `[Perf]` `[Med]` `RpgPlugin.enhance`: 強化のたびに全レベル × 全言語のロア文字列を毎回生成している
- **箇所**: `src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt:562-568`
- **何が問題か**:
  `enhance()` の `beforePersist` 内で、毎回 `(1..tuning.enhancementCap).flatMap { level -> listOf("ja_jp", "en_us").map { locale -> plain(language.component(...)) } }.toSet()` を生成し、既存ロアから一致行を除去している。
- **なぜ問題か**:
  1. 強化のたびに MiniMessage パース＋プレーン化を `cap × 言語数` 回繰り返す無駄がある。
  2. サーバー管理者が `lang/*.yml` の `ec.enhance.level_lore` 文言を変更すると、既存アイテムに付いた旧文言の行が除去できず強化行が2行・3行と増殖する。
- **修正案**:
  `ec.enhance.level_lore` の既知セットは言語読込時に1回だけキャッシュするか、強化値行をロア末尾の固定フォーマット（またはアイテム再生成ベース）で管理して古い文言変更にも耐えるようにする。
```kotlin
// Before (RpgPlugin.kt:562-567)
val enhancementLore = (1..tuning.enhancementCap).flatMap { level ->
    listOf("ja_jp", "en_us").map { locale ->
        plain(language.component(locale, "ec.enhance.level_lore", mapOf("level" to level)))
    }
}.toSet()

// After（言語カタログ/設定変更時のみ生成するキャッシュを参照）
val enhancementLore = cachedEnhancementLoreLines(tuning.enhancementCap)
```

---

### M-12 `[Bug]` `[Med]` `NpcController`: 管理 NPC だけ `isPersistent = true` で異常終了時にチャンク残留リスク
- **箇所**: `src/main/kotlin/net/nakaymi/etherealchronicle/paper/NpcController.kt:70-82`
- **何が問題か**:
  `EnemyController`・`TreasureController`・`GatheringController`・`FishingController` はすべて管理エンティティに `isPersistent = false` を設定しているが、`NpcController.kt:81` だけ `entity.isPersistent = true` になっている。
- **なぜ問題か**:
  サーバー強制終了時に `close()` が走らないと村人エンティティがチャンクデータへ保存され、再起動後に `reconcile()` がチャンク未ロードのままスポーンして一時的な二重 NPC になる恐れがある。他コントローラとライフサイクル契約も不統一。
- **修正案**:
  `entity.isPersistent = false` に統一し、`reconcile()` は対象チャンクがロード済みのときだけスポーン（未ロードなら `ChunkLoadEvent` でスポーン）する。
```kotlin
// Before (NpcController.kt:81)
entity.isPersistent = true

// After
entity.isPersistent = false
```

---

### M-13 `[Bug]` `[Med]` `DungeonController`: 別種ダンジョンで同じスロットを再利用すると前ダンジョンの建築パレットが残る
- **箇所**: `src/main/kotlin/net/nakaymi/etherealchronicle/paper/DungeonController.kt:15, 35-40`
- **何が問題か**:
  `private val built = mutableSetOf<Int>()` を使い、line 35 で `if (built.add(slot))` のときだけ `definition.palette` でアリーナ床・壁を建築している。
- **なぜ問題か**:
  スロット `0` で最初にダンジョン A（例: 深層岩系パレット）をプレイして終了した後、別パーティがスロット `0` でダンジョン B（例: 氷晶系パレット）へ入場すると、`built` に `0` が登録済みのためパレットが張り替えられず、ダンジョン A のアリーナ見た目のままダンジョン B が始まる。
- **修正案**:
  `built` を `mutableMapOf<Int, List<String>>()`（または `mutableMapOf<Int, String>()` で `dungeonId` 保持）に変更し、前回とパレットが異なるときは再建築する。
```kotlin
// Before (DungeonController.kt:15, 35)
private val built = mutableSetOf<Int>()
...
if (built.add(slot)) for (dx in -24..24) ...

// After
private val builtPalette = mutableMapOf<Int, List<String>>()
...
if (builtPalette[slot] != definition.palette) {
    for (dx in -24..24) ...
    builtPalette[slot] = definition.palette
}
```

---

### M-14 `[Test]` `[Med]` テスト盲点: MockBukkit が Paper 26.3 実機 UI 経路を全面バイパスしており結合テストが欠落
- **箇所**: `src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt:23`, `src/test/kotlin/net/nakaymi/etherealchronicle/paper/GamePortPackServerTest.kt`, `src/test/kotlin/net/nakaymi/etherealchronicle/paper/ExternalManagedPackTest.kt`, `src/test/kotlin/net/nakaymi/etherealchronicle/paper/BetterModelBridgeTest.kt`
- **何が問題か**:
  1. `RpgPlugin.kt:23`: `private val paperInventoryUi by lazy { !server.javaClass.name.startsWith("org.mockbukkit.") }` のため、全 MockBukkit テストで `InvUiInventoryAdapter`・`ClassSelectionUi`・`showDialogueDialog`・`registerPaperCommand` が一度も実行されない。実際に H-04・M-04 のような実機専用 UI バグが素通りしている。
  2. `GamePortPackServerTest.kt`: `"GET "` 完了後のストールしか検証しておらず、1 バイト `'G'` 送信の Slowloris（H-01）、2 バイト目で Minecraft パケットへフォールバックする境界（例: パケット長 71 = `'G'` の実 Minecraft ハンドシェイク `0x47, 0x00, ...`）、`GamePortPackServer.unsupportedProxies()` が未テスト。
  3. `ExternalManagedPackTest.kt`: 静的ヘルパー `ManagedPackPolicy` の単体テストのみで、`ResourcePackController` のイベントハンドラ（`onStatus` で中間ステータスや別パック ID が来た場合、`/ec ui pack off` の実挙動 = H-03 / M-02）が未テスト。
  4. `BetterModelBridgeTest.kt`: 設定マップの正規表現フィルタ `configuredModels()` だけで、実リフレクション呼び出し (`attach` / `detach` / `close` / モデル不在時) の検証がない。
- **なぜ問題か**:
  MockBukkit で緑になっても実 Paper 26.3 サーバーで初めて壊れる領域（UI・Netty・リソパ状態遷移・BetterModel 連携）が丸ごと回帰テストの網から抜け落ちている。
- **修正案**:
  1. `GamePortPackServerTest` に 1 バイト `'G'` ストール・`0x47 0x00` Minecraft パケット通過・共有帯域制限テストを追加。
  2. `ResourcePackController` のモック/MockBukkit 結合テストを追加し、ロード済みからの `/ec ui pack off`、`external_managed` の `ACCEPTED` / `DECLINED` 遷移を検証。
  3. `ClassSelectionUi` の状態機械（開く→確認中→決定/キャンセル→再オープン）を Paper 依存から分離した純粋テスト対象として切り出し、単体テスト化する。
  4. Paper 実機 E2E（Testcontainers / テストサーバー起動スクリプト）でのスモーク手順に InvUI・Dialog・`game_port` を組み込む。

---

## Low（品質・保守性・効率の改善）

### L-01 `[Idiom]` `[Low]` `RpgPlugin.kt` (1122行) の God Class 化と責務混在
- **箇所**: `src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt:21-1122`
- **何が問題か**:
  プラグイン初期化、戦闘（ダメージ・スキル詠唱・プロック・ガーディアン蘇生）、強化（GUI・トランザクション・返金決済）、進捗データパック、図鑑クリック中継、管理/プレイヤーコマンド（`executeCommand` 145行＋`playerCommand` 102行）が 1 クラスに同居している。
- **なぜ問題か**:
  変更時の影響範囲が読みづらく、`profiles`・`dirty`・`choosing`・`casting`・`proccing`・`defenseBuffs` 等の可変状態が同じスコープに散らばりバグの温床になる。
- **修正案**:
  既に切り出されている `CodexController` / `SimulationCommandController` と同様に、`CombatController`・`EnhancementController`・`CommandController` へ責務分割する。

---

### L-02 `[Style]` `[Low]` `PlayerStore.migrateToSchema11`: v0〜v10 の巨大なコピペ階段
- **箇所**: `src/main/kotlin/net/nakaymi/etherealchronicle/data/PlayerStore.kt:100-118`
- **何が問題か**:
  `version == 2..10` の各 `if` 分岐が、それ以降すべてのスキーマで追加されたフィールドを手書きで重複記述している。
- **なぜ問題か**:
  1 行あたり 200〜400 文字の長い式が 10 本並び、どのバージョンでどのフィールドが増えたかが追いにくく、今後のスキーマ拡張時に記載漏れが起きやすい。
- **修正案**:
  バージョンを 1 段階ずつ上げるパイプライン（順次加算）に書き換える。
```kotlin
// Before (PlayerStore.kt:104-112)
if (version == 10) return raw + mapOf("best_enhancement_level" to 0, "schema_version" to 11)
if (version == 9) return (raw + lifeDefaults + mapOf("schema_version" to 10)) + mapOf("best_enhancement_level" to 0, "schema_version" to 11)
if (version == 8) return ((raw + mapOf("schema_version" to 9, "bestiary" to ...)) + lifeDefaults + ...) + ...

// After
var current = raw
if (version < 2) current = migrateV0V1ToV2(current)
if (version < 3) current += mapOf("flags" to emptyList<String>(), "transactions" to emptyList<String>(), "daily_purchases" to emptyMap<String, Int>())
if (version < 4) current += mapOf("pending_rewards" to emptyMap<String, Int>())
if (version < 5) current += mapOf("drop_pity" to emptyMap<String, Int>())
if (version < 7) current += mapOf("challenge_records" to emptyMap<String, Any?>())
if (version < 8) current += mapOf("selected_title" to null)
if (version < 9) current += mapOf("bestiary" to emptyMap<String, Int>(), "discovered_items" to emptyList<String>())
if (version < 10) current += lifeDefaults
if (version < 11) current += mapOf("best_enhancement_level" to 0)
return current + ("schema_version" to 11)
```

---

### L-03 `[Idiom]` `[Low]` `DemoWorld`: `migrateToV2..V5` の 4 重ボイラープレートと新規生成時の冗長移行
- **箇所**: `src/main/kotlin/net/nakaymi/etherealchronicle/paper/DemoWorld.kt:82-92, 120-191`
- **何が問題か**:
  `migrateToV2` / `migrateToV3` / `migrateToV4` / `migrateToV5` が毎回 `.etherealchronicle-owned` と `.etherealchronicle-built` の確認・読み込み・書き戻しを 4 回コピペしている。また新規作成直後（line 85）に `"v4"` を書いた直後に `migrateToV2..V5` をすべて呼び出している。
- **なぜ問題か**:
  バージョンが増えるたびに同じガード処理が増殖し、`migrateToV2` だけ既適用時に `true` を返し `V3..V5` は `false` を返すなど戻り値の契約も不統一になっている。
- **修正案**:
  共通ヘルパー `migrateVersion(plugin, folder, from, to, action)` へ抽出する。
```kotlin
// Before (DemoWorld.kt:140-169)
internal fun migrateToV3(...): Boolean {
    if (!Files.exists(folder.resolve(".etherealchronicle-owned"))) return false
    val marker = folder.resolve(".etherealchronicle-built")
    if (!Files.exists(marker)) return false
    when (Files.readString(marker).trim()) { "v2" -> { ... } ... }
}

// After
private fun migrateStep(
    plugin: RpgPlugin,
    folder: Path,
    from: String,
    to: String,
    alreadyMigrated: Set<String>,
    apply: () -> String,
): Boolean {
    if (!Files.exists(folder.resolve(OWNER_MARKER))) return false
    val marker = folder.resolve(BUILT_MARKER)
    if (!Files.exists(marker)) return false
    return when (Files.readString(marker).trim()) {
        from -> {
            val log = apply()
            Files.writeString(marker, "$to\n")
            plugin.logger.info(log)
            true
        }
        in alreadyMigrated -> false
        else -> { plugin.logger.warning("ec_world $from is required before upgrading to $to: $marker"); false }
    }
}
```

---

### L-04 `[Idiom]` `[Low]` 横断的な流儀の不統一（`plain()` 5 重重複、`Regex`/`MiniMessage` の都度生成、時刻 API 混在）
- **箇所**:
  - `plain(Component)` 重複: `RpgPlugin.kt:64`, `CodexController.kt:20`, `FishingController.kt:108`, `GatheringController.kt:112`（さらに line 247 で FQCN 直書き）, `ResourcePackController.kt:240`
  - `Regex(...)` 都度コンパイル: `Content.kt:46, 114, 117, 134`, `PlayerStore.kt:59, 62`, `WorldContent.kt:31, 77, 94, 115, 133, 142, 165, 175`, `RpgPlugin.kt:913`, `LangCatalog.kt:50`
  - 時刻 API 混在: `System.nanoTime() / 1_000_000`（`RpgPlugin`, `AdventureController` のクールダウン）vs `System.currentTimeMillis()`（`FishingController.kt:214` デバウンス、`GatheringController.kt:278` チャネル経過時間、`DungeonController.kt:66` クリア秒数）
- **何が問題か**:
  同じ用途のユーティリティや定数がファイルごとに再定義されており、さらに経過時間計測に壁時計 (`currentTimeMillis`) を使っている箇所は NTP 時刻補正で計測値が逆転・ジャンプしうる。
- **なぜ問題か**:
  保守コスト増大に加え、ループ内 `Regex(...)` 生成はリロード・読み込み時の不要な CPU/GC 負荷となる。
- **修正案**:
  1. `Component.plainText(): String` 拡張関数を `paper/` の共通ファイルに 1 箇所だけ定義して全コントローラから利用する。
  2. バリデーション用 `Regex` を `companion object`（または `ContentPatterns` object）に事前コンパイル定数化する。
  3. 経過時間・デバウンス・チャネルタイマーは単調増加クロック（`System.nanoTime() / 1_000_000` または注入 `Clock`）に統一し、永続タイムスタンプ（`completedAt` / `gatheringReadyAt`）だけ壁時計を使う。
```kotlin
// Before（5ファイルに重複）
private fun plain(component: Component): String =
    net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(component)

// After（共通拡張関数として1箇所に集約）
internal fun Component.toPlainText(): String =
    PlainTextComponentSerializer.plainText().serialize(this)
```

---

### L-05 `[Perf]` `[Low]` `GamePortPackServer`: `PackRequestSniffer` の 1 バイトごと `toByteArray()` による $O(N^2)$ アロケーション
- **箇所**: `src/main/kotlin/net/nakaymi/etherealchronicle/paper/GamePortPackServer.kt:139-142, 165-170, 272-276`
- **何が問題か**:
  1. `SNIFFING` ループ内で 1 バイト読むたびに `prefix.toByteArray()`、`"GET ".toByteArray(...)`、`"HEAD ".toByteArray(...)` を毎回生成している。
  2. `HTTP` ヘッダ読み込みループ内で **1 バイト読むたびに** `request.endsWithHeaderTerminator()` が `request.toByteArray()` でバッファ全体（最大 4KB）をコピーしている。4KB のヘッダ受信で合計約 8MB の一時配列が発生する。
- **なぜ問題か**:
  Netty I/O イベントループスレッド上で不要な GC 負荷が発生し、同時接続時にゲームパケット処理のレイテンシへ影響しうる。
- **修正案**:
  `GET_PREFIX` / `HEAD_PREFIX` を `companion object` 定数にし、`\r\n\r\n` 終端は `headerMatchState: Int`（0→`\r`→`\n`→`\r`→`\n` の 4 状態）または直近 4 バイトの `Int` シフトレジスタで $O(1)$ ゼロアロケーション判定する。
```kotlin
// Before (GamePortPackServer.kt:164-175, 272-276)
while (input.isReadable() && state == State.HTTP) {
    request.write(input.readUnsignedByte().toInt())
    if (request.size() > maxHeaderBytes) { reject(context); return }
    if (request.endsWithHeaderTerminator()) { ... }
}
private fun ByteArrayOutputStream.endsWithHeaderTerminator(): Boolean {
    val bytes = toByteArray() // 毎回全コピー
    return bytes.size >= 4 && ...
}

// After
private var lastFourBytes: Int = 0
...
while (input.isReadable() && state == State.HTTP) {
    val b = input.readUnsignedByte().toInt()
    request.write(b)
    if (request.size() > maxHeaderBytes) { reject(context); return }
    lastFourBytes = (lastFourBytes shl 8) or (b and 0xFF)
    if (lastFourBytes == CRLF_CRLF) { // 0x0D0A0D0A
        cancelTimeouts()
        respond(context)
        return
    }
}
```

---

### L-06 `[Perf]` `[Low]` `WorldContent` / `Game` / `GatheringController`: 事前インデックス可能な線形走査の反復と 2 倍走査
- **箇所**: `src/main/kotlin/net/nakaymi/etherealchronicle/core/WorldContent.kt:230-233`, `src/main/kotlin/net/nakaymi/etherealchronicle/core/Game.kt:192-206`, `src/main/kotlin/net/nakaymi/etherealchronicle/paper/GatheringController.kt:133, 190, 195`
- **何が問題か**:
  1. `WorldContent.enemiesInArea(areaId)`（line 230）は不変データに対する問い合わせなのに毎回全敵×全スポーン点を走査＋ソートしている。
  2. `PlayerProfile.recordEnemyKill()`（`Game.kt:192-206`）は 1 討伐ごとに全エリア×全敵×全スポーン点を走査し、しかも既に `Position` である `s.position` から毎回 `Position(s.position.world, ...)` を再生成している。
  3. `GatheringController.kt:47-51` の `sites` は 1 地点につき地面 (`y-1`) と本体 (`y`) の 2 エントリを持つため、`renderAll()`（line 133）・`chunk()`（line 190）・`isProtected()`（line 195）が `sites.values` を回すと**全採取地点を毎回 2 回ずつ処理**している（特に `isProtected()` はブロック破壊・爆発・ピストンイベントで毎回 2 倍の線形走査）。
- **なぜ問題か**:
  不変コンテンツの参照と高頻度ブロックイベントで無駄な計算が繰り返されている。
- **修正案**:
  1. `WorldContent` 構築時に `private val enemiesByArea: Map<String, List<EnemyDefinition>>` を事前計算して `enemiesInArea(areaId)` は `enemiesByArea[areaId].orEmpty()` を返す。
  2. `GatheringController` の `renderAll()`・`chunk()`・`isProtected()` は重複のない `sitesById.values`（さらにチャンク単位マップ `sitesByChunk`）を走査する。
```kotlin
// Before (GatheringController.kt:133, 190, 195)
sites.values.forEach { site -> ... } // 同一Siteが2回ずつ出る

// After
sitesById.values.forEach { site -> ... } // 各Site 1回のみ
```

---

### L-07 `[Style]` `[Low]` ハードコード残存（`"ec_world"`、スロット番号、`ResourceKind` 文字列、職業 GUI 装飾・Lore、既定ダンジョン ID）
- **箇所**:
  - `"ec_world"` 直書き: `NpcController.kt:68`, `GatheringController.kt:192`, `SimulationCommandController.kt:38`, `WorldValidation.kt:42`, `WorldContent.kt:142, 174, 178`
  - UI スロット番号直書き: `UiThemeProvider.kt:85-91`（`slot in 47..49`, `slot == 53`, `slot == 13`）
  - `ClassSelectionUi.kt:40, 75-79`: `displayName(Component.text(definition.name))`（斜体未解除・説明 Lore なし）、`fallback(resource: String)`（`ResourceKind` enum ではなく文字列比較）
  - 既定ダンジョン ID: `DungeonController.kt:21`, `RpgPlugin.kt:1065`（`"ec:dungeon_void_sanctum"`）
  - 折返し幅・記号: `RpgPlugin.kt:386, 1051`, `AdventureController.kt:92`（`chunked(28)` や称号解放記号）
- **何が問題か**:
  同一の定数やレイアウト前提が複数ファイルへ暗黙に分散しており、図鑑レイアウト変更や新規職業/ダンジョン追加時に追従漏れを起こしやすい。
- **なぜ問題か**:
  特に `ClassSelectionUi` では斜体デフォルトの職業名のみでロール・資源・スキル概要が一切見えず初回体験が損なわれる。また `UiThemeProvider` のスロット番号ハードコードは `CodexController` のフッター変更と静かにズレる。
- **修正案**:
  - `DemoWorld.NAME`（core 側には共通定数 `MANAGED_WORLD_NAME`）へ統一。
  - `ClassSelectionUi.fallback(resource: ResourceKind)` を `when (resource)` の網羅分岐にし、アイテム表示名は `decoration(TextDecoration.ITALIC, false)` ＋ロール/初期ステータス/スキル名 Lore を付与する。
  - `UiThemeProvider` のボタンモデルはスロット番号ではなくアイテムの PDC タグ（例: `ui_role = "codex_tab" | "page_next" | "page_prev" | "confirm"`）で判定する。
```kotlin
// Before (ClassSelectionUi.kt:40, 75-79)
itemStack.editMeta { it.displayName(Component.text(definition.name)) }
...
private fun fallback(resource: String): Material = when (resource) {
    "RAGE" -> Material.IRON_SWORD
    "FOCUS" -> Material.BOW
    else -> Material.BLAZE_ROD
}

// After
itemStack.editMeta {
    it.displayName(Component.text(definition.name).decoration(TextDecoration.ITALIC, false))
    it.lore(classSummaryLore(player, definition))
}
...
private fun fallback(resource: ResourceKind): Material = when (resource) {
    ResourceKind.RAGE -> Material.IRON_SWORD
    ResourceKind.FOCUS -> Material.BOW
    ResourceKind.MANA -> Material.BLAZE_ROD
}
```

---

## Polish（細部の仕上げ）

### P-01 `[Idiom]` `[Polish]` KDoc 二重記載・未使用メソッド・空の `if` 分岐の整理
- **箇所**:
  - KDoc 二重記載: `PlayerStore.kt:15-16`, `Content.kt:26-27`, `DefaultContentInstaller.kt:51-52`, `AdventureServices.kt:204-205`, `FishingEngine.kt:65-66`
  - 未使用デッドコード: `RpgPlugin.kt:485-489`（`areaCodexPercent` — `CodexController.kt:17-20` へ移設済みの残骸）
  - 空の `if` ブロック: `RpgPlugin.kt:605`（`if (::gathering.isInitialized) { /* its bounded 2-tick controller owns the life-skill HUD */ }`）
- **何が問題か**:
  リファクタリング時の名残としてほぼ同内容の KDoc が 2 行連続していたり、使われないメソッドや何もしない分岐が残っている。
- **修正案**:
  KDoc を 1 つに統合し、未使用の `RpgPlugin.areaCodexPercent` と空 `if` ブロックを削除する。

---

### P-02 `[Style]` `[Polish]` セミコロン `;` による 1 行複数文の詰め込み解消
- **箇所**: `RpgPlugin.kt`（特に lines 102, 232, 315, 420, 602, 624, 967 等）, `AdventureController.kt`, `EnemyController.kt`, `GatheringController.kt`, `WorldContent.kt`
- **何が問題か**:
  Kotlin の標準スタイルに反して 1 行に 2〜4 文を `;` で連結し、さらに演算子前後のスペースも省略された箇所が多数ある。
- **なぜ問題か**:
  1. 例外スタックトレースが同じ行番号を指し、どの文で `NullPointerException` / `IllegalArgumentException` が起きたか即断できない。
  2. 差分レビュー時に 1 文だけの変更でも行全体が差分になりノイズが増える。
- **修正案**:
  新規・改修箇所から順次 1 文 1 行＋標準スペース（ktlint / IntelliJ Kotlin 規約準拠）へ整形する。
```kotlin
// Before (RpgPlugin.kt:602)
val now=System.nanoTime()/1_000_000; procReady.entries.removeIf { it.value <= now }; defenseBuffs.entries.removeIf { it.value.second <= now }; basicAttacks.cleanup()

// After
val now = System.nanoTime() / 1_000_000
procReady.entries.removeIf { it.value <= now }
defenseBuffs.entries.removeIf { it.value.second <= now }
basicAttacks.cleanup()
```
