# Task 25 独立クロスモデルレビュー（Antigravity）

- **対象コミット**: `master` @ `b8c91ee`（差分 `0b1e470..b8c91ee`、38 files、+1435 / -424）
- **レビュー担当**: Antigravity（コード変更ゼロ / findings only）
- **ブランチ**: `ag/review-2`（`b8c91ee` へ fast-forward 後、本レビュー文書のみ追加）
- **ローカル検証**:
  - `./gradlew.bat test --no-daemon`: **BUILD SUCCESSFUL**（`297 tests / 0 failures / 0 errors / 0 skipped`）
  - `core.*` の Bukkit / Paper 依存検査（`git grep "bukkit\|papermc" src/main/kotlin/net/nakaymi/etherealchronicle/core/`）: **0 件（完全非依存を維持）**
  - `DemoWorld.kt` / `DungeonController.kt` / `PaperDomainUnits.kt` 全座標・範囲・Y 座標照合: **座標ドリフト 0 件**
  - `WorldSchedule` 釣り時刻窓統一: 意図どおり統一され、`FishingEngineTest` で境界・24,000 tick 折り返し・負時刻を検証済み

---

## 総合判定: **Go**（High 0 件 / Medium 4 件 / Low 3 件 / Polish 1 件）

ブロッカー（データ破損・クラッシュ・セキュリティ・座標ドリフト・コア破壊）となる **High 指摘は 0 件** です。
`core.*` の Paper 非依存・`DemoWorld` / `DungeonArenaLayout` のジオメトリ一致・297 テスト全通過を確認しました。
以下、次回の軽微クリーンアップで解消しておくと保守性と契約の堅牢性が高まる **Medium 4 件・Low 3 件・Polish 1 件** を 4 観点で整理します。

---

## 1. Bug / Security / Test 観点

### [Medium] M-01: 共通ヘルパー `Location.isWithinBlocks` / `MinecraftTicks.millisToWholeSeconds` の境界契約の欠落
- **箇所**:
  - `src/main/kotlin/net/nakaymi/etherealchronicle/paper/PaperDomainUnits.kt:19, 68-69`
  - `src/main/kotlin/net/nakaymi/etherealchronicle/paper/DungeonController.kt:90`
- **内容**:
  1. `Location.isWithinBlocks(other, blocks)`:
     ```kotlin
     fun Location.isWithinBlocks(other: Location, blocks: Double): Boolean =
         world == other.world && distanceSquared(other) <= blocks * blocks
     ```
     - 両側の `world` が `null` の場合、`null == null` が `true` となり Bukkit の `distanceSquared(other)` が `IllegalArgumentException("Cannot measure distance to a null world")` を送出します。
     - `blocks < 0.0` の場合、`blocks * blocks` が正になるため、距離 `|blocks|` 以内を誤って `true` 判定します。
     - 推奨: `blocks >= 0.0 && world != null && world == other.world && distanceSquared(other) <= blocks * blocks`
  2. `MinecraftTicks.millisToWholeSeconds(millis)`:
     ```kotlin
     fun millisToWholeSeconds(millis: Long): Long = millis / MILLIS_PER_SECOND
     ```
     - リファクタ前の `DungeonController.kt:89` には `((monotonicMillis()-instance.startedAtMillis)/1000).coerceAtLeast(0)` があったものの、共通関数化時に `.coerceAtLeast(0)` が落ちています。
     - 兄弟関数の `millisToSecondsCeil` は `if (millis <= 0) 0 else ...` と負値をクランプしており、両者で契約が不整合です。負値が渡った場合、`firstClearRanks`（`seconds <= entry.maxSeconds`）で全ランク条件を満たしてしまいます。
     - 推奨: `if (millis <= 0) 0 else millis / MILLIS_PER_SECOND`（または `(millis / MILLIS_PER_SECOND).coerceAtLeast(0)`）

### [Medium] M-02: `PaperInventoryAccess.plan` の `preferredSlot` 検証が暗黙に挙動変化（slot 8 非対称）
- **箇所**: `src/main/kotlin/net/nakaymi/etherealchronicle/paper/PaperInventoryAccess.kt:12-21`
- **内容**:
  ```kotlin
  // Before
  if (preferredSlot != null && preferredSlot !in 0..35) return null
  // After
  if (preferredSlot != null && !PlayerInventoryLayout.isUsableStorageSlot(preferredSlot)) return null
  ```
  - `isUsableStorageSlot` は `SKILL_HOTBAR_SLOT`（slot 8）を除外するため、`preferredSlot == 8` を渡すと常に `null` を返す挙動へ変化しています（Task 25 の「釣り時刻窓統一のみ意図的な挙動変更」方針とズレ）。
  - 一方で、直後の消費ループ `for (i in order)` は `slots.indices`（`0..35`、slot 8 を含む）を走査するため、`preferredSlot == null` の場合やフォールバック時は slot 8 からも消費でき、slot 8 保護としても非対称です。
  - 推奨: 既存互換を保つなら `preferredSlot !in PlayerInventoryLayout.STORAGE_SLOT_RANGE` とし、逆に slot 8 からの売却・消費を明確に禁止する設計なら消費側ループも `isUsableStorageSlot(i)` で統一しテストを追加する。

### [Low] L-01: 新規ユニットテストの境界カバレッジ不足・アサーション引数逆転
- **箇所**:
  - `src/test/kotlin/net/nakaymi/etherealchronicle/PaperDomainUnitsTest.kt:9-33, 55-65`
  - `src/test/kotlin/net/nakaymi/etherealchronicle/paper/ResourcePackRequiredPolicyTest.kt:47-48`
- **内容**:
  - `PaperDomainUnitsTest` に以下ケースがありません:
    - `isWithinBlocks`: 異ワールド（`false`）、`world == null`、負の `blocks`。
    - `millisToWholeSeconds`: `0` および負値。
    - `DungeonArenaLayout.isInsideBounds`（現在は `contains` のみ検証）。
  - `ResourcePackRequiredPolicyTest.kt:47-48`:
    `assertEquals(ResourcePackLimits.MAX_CONNECTIONS, 128)` / `assertEquals(ResourcePackLimits.MIN_PORT, 1_024)` は JUnit 5 の `(expected, actual)` 順が逆転しています（正: `assertEquals(128, ResourcePackLimits.MAX_CONNECTIONS)`）。

---

## 2. Perf 観点

- **良好な改善**:
  - `EnemyController.kt:191` で `spawnLocation` が `world.players.none { ... }` の外へホイストされ、スポーン判定ごとの不要な `Location` 生成が削減されました。
  - `Location.isWithinBlocks` は `distanceSquared` ベースで平方根を避けており、ホットパスでの追加オーバーヘッドはありません。
  - `ChunkCoordinates.fromPosition` は `EnemyController.kt:188` の負の小数座標に対する `toInt() shr 4` バグも同時に修正しています。

### [Low] L-02: ホットパス近傍の `distanceSquared` 未統一と `FishingController` の刻み時間ハードコード
- **箇所**:
  - `src/main/kotlin/net/nakaymi/etherealchronicle/paper/EnemyController.kt:141, 242`
  - `src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt:755`
  - `src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingController.kt:303, 306`
- **内容**:
  - `EnemyController.kt:141`（`d.leash*d.leash`）、`EnemyController.kt:242`（`radius*radius`）、`RpgPlugin.kt:755`（`skill.allyRadius*skill.allyRadius`）は `isWithinBlocks` への統一から漏れています。
  - `FishingController.kt:303, 306` で `s.pullRemaining - 0.1` と `engine.tick(s.state, 0.1, input)` が `0.1` 秒を直書きしています。スケジューラ周期は `PaperTaskIntervals.FISHING_UPDATE_TICKS`（`2L`）へ抽出済みのため、もし周期定数を変更すると物理ステップ時間とサイレントに乖離します（`MinecraftTicks.millisToSeconds(MinecraftTicks.ticksToMillis(PaperTaskIntervals.FISHING_UPDATE_TICKS))` から導出すると安全です）。

---

## 3. Idiom 観点

### [Medium] M-03: 異なるドメインの定数流用による意図しない結合（2 箇所）
- **箇所**:
  - `src/main/kotlin/net/nakaymi/etherealchronicle/paper/DemoWorld.kt:230`
  - `src/main/kotlin/net/nakaymi/etherealchronicle/paper/ResourcePackSettings.kt:87`
  - `src/main/kotlin/net/nakaymi/etherealchronicle/paper/GamePortPackServer.kt:70`
- **内容**:
  1. `DemoWorld.kt:230`: `ec_world` 5 広場の出入口幅（`> 2`）に **`DungeonArenaLayout.INNER_FLOOR_RADIUS_BLOCKS`** を流用しています。ダンジョン中央装飾床の半径を変更すると、`DemoWorld` 全広場の門幅がサイレントに壊れます。`DemoWorldLayout.HUB_GATE_HALF_WIDTH_BLOCKS = 2` を独立定義すべきです。
  2. `ResourcePackSettings.kt:87` & `GamePortPackServer.kt:70`: `gamePortHeaderDeadlineMillis`（旧 `500..10_000`）の検証に **`ResourcePackLimits.MIN_WRITE_DEADLINE_MILLIS..ResourcePackLimits.MAX_IDLE_TIMEOUT_MILLIS`** を流用しています。「書込み期限の下限」と「アイドルタイムアウトの上限」を値が偶然一致する理由だけでヘッダー期限範囲に使っており、別設定の上下限変更が波及します。専用の `MIN_GAME_PORT_HEADER_DEADLINE_MILLIS` / `MAX_GAME_PORT_HEADER_DEADLINE_MILLIS` に分離すべきです。

### [Medium] M-04: 新設した定数の未接続（本番コード内 0 参照が 7 件）と同一ファイル内の取り残し
- **箇所**:
  - `src/main/kotlin/net/nakaymi/etherealchronicle/core/GameLimits.kt`
  - `src/main/kotlin/net/nakaymi/etherealchronicle/core/WorldContent.kt:187, 218, 230, 238`
  - `src/main/kotlin/net/nakaymi/etherealchronicle/core/WorldValidation.kt:57`
  - `src/main/kotlin/net/nakaymi/etherealchronicle/core/FishingEngine.kt:187`
  - `src/main/kotlin/net/nakaymi/etherealchronicle/data/PlayerStore.kt:74-79`
  - `src/main/kotlin/net/nakaymi/etherealchronicle/data/Content.kt:141`
  - `src/main/kotlin/net/nakaymi/etherealchronicle/paper/CodexPagination.kt:5`
  - `src/main/kotlin/net/nakaymi/etherealchronicle/paper/ResourcePackDistribution.kt:58-61`
- **内容**:
  定数を定義したにもかかわらず呼び出し側が裸リテラルのまま残っており、単一ソース（Single Source of Truth）が初日から二重化しています:
  - **本番 `src/main/kotlin` 内での参照 0 件（定義のみ）の定数 7 件**:
    - `ContentLimits.MAX_SPECIAL_ATTACK_RADIUS_BLOCKS` (`32.0`), `MAX_SPECIAL_ATTACK_RANGE_BLOCKS` (`128.0`), `MAX_SPECIAL_ATTACK_DURATION_TICKS` (`200`) → `WorldContent.kt:187` が依然 `0.1..32.0`, `0.1..128.0`, `1..200` を直書き。
    - `ContentLimits.MAX_ENEMY_SIZE` (`16`) → `WorldValidation.kt:57` が依然 `e.size in 1..16` を直書き。
    - `CodexLayout.MAX_VISIBLE_ENTRIES` (`45`) → `CodexPagination.kt:5` が依然 `const val DEFAULT_PAGE_SIZE = 45` を直書き。
    - `GameLimits.MAX_PROFILE_LEVEL` (`1_000`)（`ContentLimits.MAX_DECLARED_LEVEL` と重複）および `GameDurations.MILLIS_PER_DAY`（`DEFAULT_QUEST_REPEAT_MILLIS` と重複）が未使用。
  - **既存の定数を繋ぎ忘れた箇所**:
    - `ResourcePackDistribution.kt:58-61`: `EmbeddedPackServer` コンストラクタ既定値が依然 `4`, `500`, `1_500`, `5_000` を直書き（`ResourcePackDefaults` の KDoc「設定・コンストラクタ間で値が食い違わないための配信既定値」と不整合）。
    - `FishingEngine.kt:187`: `TELEGRAPH -> THRASH` 遷移時の `state.behaviorSeconds = 1.2 + random.nextDouble()` が直書きのまま（10 行下の `else if` 内の `FishBehavior.THRASH` 分岐に定数が書かれているが、そこは実質到達不能で実際に実行される 187 行目が未置換）。
    - `WorldContent.kt:218, 230, 238`: `tackle` / `fish_species` / `gathering_node` 検証で `15.0`, `0.5..1.2`, `0.15`, `1..3`, `250`, `86_400`, `64`, `10000` が直書きのまま（`FishingPhysics.*`, `ContentLimits.*`, `GameLimits.*` が既に存在）。
    - `PlayerStore.kt:74-79`: `p.selectedSkill in 0..3`（`0 until ContentLimits.MAX_CLASS_SKILLS`）および `1..1_000_000` / `0..1_000_000`（`GameLimits.MAX_RECORD_COUNT`）が直書き。
    - `Content.kt:141`: `s.threatMultiplier in 1.0..100.0`（`EnemyController` 側は `EnemyBehaviorTuning.TAUNT_MAX_THREAT_MULTIPLIER` を抽出済みだが、YAML 検証側と共有されていない）。

### [Low] L-03: 重複定数・`DemoWorldLayout` モデリング不統一・`PaperDomainUnits` の公開可視性
- **箇所**:
  - `src/main/kotlin/net/nakaymi/etherealchronicle/core/GameLimits.kt:55-57, 94, 152, 190-191`
  - `src/main/kotlin/net/nakaymi/etherealchronicle/core/FishingEngine.kt:33-59`
  - `src/main/kotlin/net/nakaymi/etherealchronicle/paper/PaperDomainUnits.kt:1-283`
  - `src/main/kotlin/net/nakaymi/etherealchronicle/paper/DemoWorld.kt:230-303`
  - `src/main/kotlin/net/nakaymi/etherealchronicle/paper/AdventureController.kt:150`
- **内容**:
  - **3 重定義**: マクロ検出既定値（`8`, `18.0`, `120.0`）が `LifeBalanceDefaults`、`FishingPhysics.DEFAULT_*`、`FishingMacroDefaults` の 3 箇所で重複定義されています（他、`WorldTuningLimits.MAX_ENHANCEMENT_RATE` vs `BalanceDefaults.ENHANCEMENT_RATE_CAP`、`GatheringFormula.MAX_TOOL_TIER` vs `ContentLimits.MAX_TACKLE_TIER`）。
  - **`DemoWorldLayout` の型・描画の不統一**:
    - `Plaza` だけ `wallMaterialName: String` / `floorMaterialName: String` で実行時 `Material.valueOf` を呼んでいる一方、同一オブジェクト内の `Column` / `Canopy` / `Outline` / `Disk` / `BlockPoint` は直接 `Material` 型を保持しています。
    - `Outline` が `LEGACY_LANDMARK_OUTLINES`（`DemoWorld.kt:252`: `zRange.first` のみ参照し枠線を描画）と `CHAPTER_TWO_TOP_BEAMS`（`DemoWorld.kt:299`: `xRange × zRange × yRange` を中身まで全埋め）の 2 通りの意味で使い回されています（将来 `Outline` に複数 Z 範囲を足すと前者は黙って無視されます）。
  - **可視性**: `PaperDomainUnits.kt` の 17 個の `object`/`data class` がすべて `public` ですが、他の Task 25 新設定義（`ResourcePackLimits`, `ResourcePackDefaults`, `HttpStatus`, `EnemyBehaviorTuning`, `PlayerSchema`）は `internal` です。`internal` へ揃えると不要な公開 API 固定を避けられます。
  - `AdventureController.kt:150`: `Duration.ofMinutes(2)` が `UiCallbackLifetimes` へ未集約です。

---

## 4. Style / Polish 観点

### [Polish] P-01: 一括置換に伴うインデント崩れ（11 ファイル）
- 以下の箇所で周囲ブロックとインデントがずれています（機能影響なし、`ktfmt` / IDE 整形で一括解消可能）:
  - `src/main/kotlin/net/nakaymi/etherealchronicle/core/SimulationSession.kt:28`（16 → 8 spaces）
  - `src/main/kotlin/net/nakaymi/etherealchronicle/core/FishingEngine.kt:96, 194-200`（特に `194-200` は `else if` ブロック内なのに外と同インデントになり可読性低下）
  - `src/main/kotlin/net/nakaymi/etherealchronicle/data/Content.kt:129`（12 → 8 spaces）
  - `src/main/kotlin/net/nakaymi/etherealchronicle/core/WorldContent.kt:116`（20 → 8 spaces）
  - `src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt:345`（column 0）、`:417`（12 → 8 spaces）
  - `src/main/kotlin/net/nakaymi/etherealchronicle/paper/DemoWorld.kt:234-236`（12 → 8 spaces）
  - `src/main/kotlin/net/nakaymi/etherealchronicle/paper/EnemyController.kt:201-202, 205`（8 → 12 spaces）
  - `src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingController.kt:123, 332`
  - `src/main/kotlin/net/nakaymi/etherealchronicle/paper/GatheringController.kt:127, 157, 166, 295`
  - `src/main/kotlin/net/nakaymi/etherealchronicle/paper/TreasureController.kt:49-57`
  - `src/main/kotlin/net/nakaymi/etherealchronicle/paper/ResourcePackDistribution.kt:247`

---

## 確認チェックリストサマリー

| 確認項目 | 結果 | 備考 |
|---|---|---|
| `core.*` の Bukkit / Paper 非依存 | OK | `git grep` 0 件 |
| `./gradlew.bat test --no-daemon` | OK | 297 tests / 0 failures / 0 errors / 0 skipped |
| `WorldSchedule` 釣り時刻窓統一 | OK | 境界・折り返し・負時刻テスト済み |
| `DemoWorld` / `DungeonArenaLayout` 座標一致 | OK | 全座標・範囲・Y 座標一致（`M-03` の定数流用のみ要注意） |
| 意図しない挙動変更 | 要確認 2 件 | `M-01`（`millisToWholeSeconds` の非負クランプ欠落）、`M-02`（`preferredSlot == 8`） |
| 未使用・未接続定数 | 要整理 | `M-04`（本番 0 参照 7 件＋近傍取り残し） |

---

## 最終スポット再確認（`master` @ `d53b4c9` / 差分 `3032504..d53b4c9`）

- **対象コミット**: `master` @ `d53b4c9`（Codex-Impl フォローアップ `21eac7a`, `0ea6202`、差分 `3032504..d53b4c9`、25 files）
- **ローカル検証**:
  - `./gradlew.bat test --no-daemon`: **BUILD SUCCESSFUL**（`298 tests / 0 failures / 0 errors / 0 skipped`）
  - `core.*` の Bukkit / Paper 依存検査: **0 件**
  - 本番 `src/main/kotlin` の未使用定数スキャン（`GameLimits.kt`, `PaperDomainUnits.kt`, `ResourcePackSettings.kt`, `EnemyController.kt`）: **0 件**

### 総合判定: **Go**（High 0 件 / Medium 0 件 / Low 2 件・Polish 2 件はいずれも受容可）

前回の **Medium 4 件（M-01〜M-04）・Low 3 件（L-01〜L-03）はすべて解消**され、新規の High / Medium 回帰もありません。Task 25 はクローズ可能な状態です。

### 前回指摘の解消状況

| ID | 状態 | 検証内容 |
|---|---|---|
| **M-01** | **解消** | `Location.isWithinBlocks` が `val currentWorld = world ?: return false` と `blocks < 0.0 || currentWorld != other.world` をガード。`MinecraftTicks.millisToWholeSeconds` が `.coerceAtLeast(0)` を復元し、`PaperDomainUnitsTest` に境界テストを追加済み。 |
| **M-02** | **解消** | `PaperInventoryAccess.plan` が `preferredSlot !in PlayerInventoryLayout.STORAGE_SLOT_RANGE`（`0..35` 互換）を復元し、追加探索ループも `PlayerInventoryLayout.USABLE_STORAGE_SLOTS` に統一済み。 |
| **M-03** | **解消** | `DemoWorldLayout.HUB_GATE_HALF_WIDTH_BLOCKS = 2` を `DungeonArenaLayout` から分離し、`ResourcePackLimits.MIN_GAME_PORT_HEADER_DEADLINE_MILLIS` / `MAX_GAME_PORT_HEADER_DEADLINE_MILLIS` を独立定義済み（他、Claude M2 の `HOTBAR_SLOT_RANGE` / `ENTITY_SPAWN_Y` / `MOVED_RECENTLY_TICKS` / `GatheringSessionTuning` / `MAX_TENSION`・`MAX_PROGRESS` も解消）。 |
| **M-04** | **解消** | 本番 `src/main/kotlin` 内の 0 参照定数 7 件をすべて解消（`MAX_SPECIAL_ATTACK_*` → `WorldContent.kt:187`、`MAX_ENEMY_SIZE` → `WorldValidation.kt:57`、`CodexLayout.MAX_VISIBLE_ENTRIES` → `CodexPagination.kt:5`、重複 `MAX_PROFILE_LEVEL`・`MILLIS_PER_DAY` 削除、`EmbeddedPackServer` 既定値 → `ResourcePackDefaults.*`、`PlayerStore.kt:74,78,79`・`Content.kt:121`・`FishingEngine.kt:178` 接続済み）。 |
| **L-01** | **解消** | `PaperDomainUnitsTest` に負値・null world・異ワールド・`0`/`-1`・`STORAGE_SLOT_RANGE.count() == 36`・`isInsideBounds` を追加、`ResourcePackRequiredPolicyTest` の `assertEquals(expected, actual)` 順を修正、`DemoWorldMigrationTest` でランドマーク特性テストを追加済み。 |
| **L-02** | **解消** | `EnemyController.kt:141, 242`・`RpgPlugin.kt:755` を `isWithinBlocks` へ統一し、`FishingController.kt:303, 306` のステップ時間を `FishingStepTuning.SECONDS_PER_UPDATE` から導出済み。 |
| **L-03** | **解消** | `FishingMacroDefaults` / `FishingPhysics.DEFAULT_*` / `WorldTuningLimits.MAX_ENHANCEMENT_RATE` / `GatheringFormula.MAX_TOOL_TIER` の重複削除、`Plaza` の `Material` 直接保持、`SolidBox` と `Outline` の分離、`PaperDomainUnits.kt` の `internal` 化、`UiCallbackLifetimes.DIALOGUE_CHOICE` 抽出（`AdventureController.kt:150`）、`DungeonController.ArenaPalette` での `Material` 事前解決を完了。 |
| **P-01** | **残存（受容可）** | インデント崩れ（空白のみ・機能影響なし）。次回フォーマット整形でまとめて解消すれば十分。 |

### 新規差分（`3032504..d53b4c9`）の軽微な観察事項（すべて受容可 / 非ブロッカー）

1. **[Low — 受容可（次回タスクで戻すことを推奨）]** `src/main/kotlin/net/nakaymi/etherealchronicle/core/AdventureServices.kt:294`:
   - `partyRadius` の検証下限が `1.0..WorldTuningLimits.MAX_PARTY_XP_RADIUS_BLOCKS` から `0.0..WorldTuningLimits.MAX_PARTY_XP_RADIUS_BLOCKS` へ意図せず緩んでいます（Task 25 前 `0b1e470` では `1.0..128.0`）。同梱 YAML は `32.0` のため実動作影響はありませんが、不正な `party_xp_radius: 0.0` を弾けなくなるため次回クリーンアップ時に下限 `1.0` へ戻すことを推奨します。
2. **[Low — 受容可]** `src/main/kotlin/net/nakaymi/etherealchronicle/core/FishingEngine.kt:12-13, 56-57`:
   - `TELEGRAPH_THRASH_MIN_SECONDS = 1.2` / `TELEGRAPH_THRASH_VARIANCE_SECONDS = 1.0` が既存の同値定数 `THRASH_BEHAVIOR_MIN_SECONDS` / `THRASH_BEHAVIOR_VARIANCE_SECONDS` と並存しています（機能影響なし）。
3. **[Polish — 受容可]** `src/main/kotlin/net/nakaymi/etherealchronicle/paper/PaperDomainUnits.kt:49` & `src/main/kotlin/net/nakaymi/etherealchronicle/paper/AdventureController.kt:22`:
   - `FishingStepTuning.SECONDS_PER_UPDATE` が生の `1_000.0` で除算しています（`MinecraftTicks.MILLIS_PER_SECOND.toDouble()` 参照可）。
   - `AdventureController.kt:22` に `UiCallbackLifetimes` 移行後の未使用 `import java.time.Duration` が 1 行残っています。
