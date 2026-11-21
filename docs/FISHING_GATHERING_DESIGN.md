# 釣り・採取システム設計書（Fishing & Gathering Design）

- **対象プロジェクト**: EtherealChronicle（Paper 1.21+ / Kotlin）
- **対象ブランチ**: `ag/fishing-gathering`（コンテンツ先行実装）→ Codex 実装フェーズ
- **設計方針**:
  1. **バニラ完全非依存**: マイクラ標準の `PlayerFishEvent` による釣り報酬・ウキ挙動、およびバニラのブロック破壊・ドロップを一切使用しない独自 RPG システムとする。
  2. **データ駆動**: 釣り場・魚種・釣具・餌・採取ノード・生活スキル曲線をすべて YAML 定義し、既存の `ContentService` / `WorldContent` / `validateWorldReferences` と同一ポリシーで検証・ホットリロード可能にする。
  3. **TPS 安全・アンチ自動化**: ワールド／チャンク全走査を禁止し、O(1) 座標・エリア判定と有界セッション管理で 100 人同時実行でもサーバー TPS を劣化させない。
  4. **経済・戦闘バランス整合**: `balance/default.yml`（`level_cap: 20`, `xp_gain_cap: 10000`, `currency_gain_cap: 5000`, `sell_ratio: 0.3`）および `docs/BALANCE.md` の上限を厳守し、売買アービトラージを完全に防ぐ。生活道具は `slot: NONE` として既存の戦闘装備比較・シミュレーションへ影響させない。

---

## 1. 全体アーキテクチャと既存基盤との統合

### 1.1 モジュール配置案（Codex 実装ガイド）

```text
src/main/kotlin/net/nakaymi/etherealchronicle/
├─ core/
│  ├─ LifeSkill.kt            # LifeSkillKind(FISHING, GATHERING), 経験値曲線, サイズ・王冠計算, 魚抽選, 採取抽選（純粋ロジック・単体テスト可）
│  ├─ FishingMinigame.kt      # 状態機械（IDLE..CAUGHT/ESCAPED）, テンション/進捗/スイートスポット/暴れフェーズ更新, アンチマクロ統計
│  ├─ GatheringEngine.kt      # 採取チャネリング検証, パーソナル再出現管理, パーティ近接シナジー計算
│  └─ WorldContent.kt         # fishing_spots / fish_species / gathering_nodes / tackle / life_balance の読み込みと参照検証追加
├─ data/
│  └─ PlayerStore.kt          # schema_version 9 -> 10 マイグレーション（life_skills / fish_records / gathering_records）
└─ paper/
   ├─ FishingController.kt    # PlayerFishEvent抑止, キャスト判定, 仮想ウキ(ItemDisplay), BossBar/ActionBar/Title UX, 2-tick 駆動
   ├─ GatheringController.kt  # ec_worldブロック保護, 仮想ノード(BlockDisplay/TextDisplay), 右クリック採取, 1-sec 表示更新
   ├─ DemoWorld.kt            # 任意 v2 -> v3 追加マイグレーション（各エリアに浅い釣り池・桟橋水辺を追加、既存地形破壊なし）
   └─ RpgPlugin.kt            # /ec fish, /ec gather, /ec codex(魚類/採取タブ拡張), 終了時クリーンアップ
```

### 1.2 既存システムとの連携ポイント

| 既存コンポーネント | 連携内容 |
| :--- | :--- |
| `AdventureController.transaction` | 魚・採取素材の付与、餌の消費、インベントリ満杯時の `pendingRewards`（`/ec claim`）退避を既存の耐久トランザクション経路に統一 |
| `QuestEngine` | `ObjectiveKind.COLLECT` は即時動作。将来的に `CATCH_FISH` / `GATHER_NODE` を追加可能（現行 YAML では `COLLECT` + `TALK` + `EXPLORE` を使用） |
| `RpgPlugin.openCodex` | 図鑑メニューに「魚類図鑑 (`codex-fish`)」「採取図鑑 (`codex-gathering`)」ボタンを追加（スロット 51 / 52、既存アイテム図鑑 53 と併存） |
| `TitleDefinition` (`titles/*.yml`) | 釣りスコア・採取実績の到達時に章宣言済みフラグ（`ec:flag_title_*`）を付与し、既存の `/ec title` で装着可能 |
| `RpgPlugin.tick()` ActionBar | 釣りセッション中または採取チャネリング中のプレイヤーに対しては戦闘 HUD ActionBar の送信をスキップし、ミニゲームゲージを上書きしない |
| `ResetPeriods` (`DAILY`/`WEEKLY`) | 釣り・採取のデイリー依頼は既存の `reset_schedule: DAILY`（UTC 20:00 = JST 05:00）をそのまま活用 |

---

## 2. 独自釣りシステム（Custom RPG Fishing）

### 2.1 バニラ釣り完全抑止仕様

- `PlayerFishEvent` を `EventPriority.LOWEST` で監視し、以下の条件のいずれかを満たす場合 `e.isCancelled = true` とする:
  - プレイヤーが `ec_world` に存在する。
  - メインハンドまたはオフハンドのアイテムが `ec:item_tool_rod_*`（または `tackle_kind == ROD`）の PDC タグを持つ。
- `PlayerInteractEvent`（`Action.RIGHT_CLICK_AIR` / `RIGHT_CLICK_BLOCK`, `EquipmentSlot.HAND`）にて、保持アイテムが RPG 釣竿（`tackle.kind == ROD`）である場合、`e.isCancelled = true` としてバニラ浮きの発射を物理的に防止し、`FishingController.handleRodRightClick(player)` へディスパッチする。
- これにより、バニラ戦利品テーブル（エンチャント本・サドル・バニラ魚・バニラ経験値オーブ）は一切生成されない。

### 2.2 釣具と餌の役割

現行コンテンツでは `slot: NONE` の通常アイテムとして実装し、将来のエンジン拡張でアイテムメタデータまたは `tackle` 定義を紐づける。

| 釣竿 ID | ランク (`tier`) | 必要釣りLv | 待ち時間短縮 | スイートスポット幅補正 | テンション安定度 | 大物サイズ補正 |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: |
| `ec:item_tool_rod_oak`（樫の蒼穹釣竿） | 1 | 1 | 0% | +0% | 1.00× | +0% |
| `ec:item_tool_rod_starwood`（星木の精密釣竿） | 2 | 7 | -12% | +5% | 0.88× | +3% |
| `ec:item_tool_rod_astral`（星核の蒼穹大釣竿） | 3 | 14 | -22% | +10% | 0.78× | +6% |

| 餌 ID | 消費タイミング | 待ち時間短縮 | 対象魚重み倍率 | レア度重み補正 |
| :--- | :--- | :---: | :---: | :---: |
| （餌なし） | — | 0% | 1.0× | COMMON/UNCOMMON のみ通常、RARE以上は 0.35× |
| `ec:item_bait_earthworm`（蒼穹ミミズ） | `BITE_ALERT` 発生時に 1 個消費 | -15% | 淡水・港魚 1.5× | UNCOMMON 1.25× |
| `ec:item_bait_aether_lure`（星片疑似餌） | `BITE_ALERT` 発生時に 1 個消費 | -20% | 星核・幻想魚 1.8× | RARE 1.5× / EPIC 1.35× |
| `ec:item_bait_frost_krill`（氷海オキアミ） | `BITE_ALERT` 発生時に 1 個消費 | -25% | 寒冷・深淵魚 2.0× | RARE 1.6× / EPIC 1.6× / LEGENDARY 1.4× |

> **餌選択と消費ルール**: プレイヤーは `/ec fish bait <item_id|none>` またはインベントリ内の最優先餌（オフハンド優先 → ホットバー左から順）を自動装着する。**消費は `BITE_ALERT` 突入時**に行い、待ち時間中のキャンセルでは消費しない一方、アタリ確認後の厳選リロール（即キャンセルによる餌温存）を防止する。

### 2.3 キャスト・水辺判定（O(1) 設計）

1. プレイヤーが RPG 釣竿を右クリック（`IDLE` 状態）。
2. `player.world.rayTraceBlocks(player.eyeLocation, player.eyeLocation.direction, 12.0, FluidCollisionMode.ALWAYS, true)` を 1 回だけ実行。
3. ヒットブロックが `Material.WATER`（または水没ブロック `Waterlogged` かつ `isWaterlogged == true`）であり、直上 1 ブロックが空気であることを確認。
4. ヒット座標 `(world, x, y, z)` から該当する `fishing_spot` を検索（エリア数 ≤ 20 のため O(1) 定数時間）。スポットが見つからない場合はエリアのデフォルト水辺テーブル、それも未定義なら「ここには魚影が見当たらない…」と通知して終了。
5. プレイヤーとヒット地点の水平距離が `2.0..12.0` ブロックであることを確認。
6. 仮想ウキ（`ItemDisplay` + `Material.RED_CONCRETE` / `HEART_OF_THE_SEA` 相当の小型モデル、scale `0.35`, `isPersistent = false`, PDC `ec:bobber_owner = player.uuid`）を水面 `y + 0.85` に配置し、`CASTING` 状態へ遷移。

### 2.4 釣りミニゲーム状態機械（State Machine）

```text
   [右クリック/水面ヒット]
IDLE ───────────────────► CASTING (400ms)
  ▲                          │ (着水波紋 + SPLASH音)
  │                          ▼
  │                       WAITING (2.5s〜8.5s、餌/竿/魚影疲労で補正)
  │   [再右クリック=回収]      │ (抽選した魚がヒット！餌1個消費)
  │◄─────────────────────────┤
  │                          ▼
  │                      BITE_ALERT (反応猶予 1.2s〜2.2s、魚レア度依存)
  │   [タイムアウト=逃走]      │ [右クリックで合わせ成功！]
  │◄─────────────────────────┤
  │                          ▼
  │                       REELING (テンション＆進捗管理ミニゲーム)
  │                          │
  │         ┌────────────────┴────────────────┐
  │         ▼                                 ▼
  │     [progress >= 100]           [tension >= 100 or slack >= 2.5s]
  │       CAUGHT                           ESCAPED
  │  (サイズ/王冠決定・報酬付与・図鑑記録)   (糸切れ/バラシ通知)
  └─────────┴─────────────────────────────────┘
```

#### 中断（`CANCELLED`）条件
- プレイヤーが着水地点から 15m 超離れる / キャスト位置から 5m 超移動する
- 被ダメージを受ける（戦闘割り込み）
- メインハンドのホットバースロットを釣竿以外に持ち替える
- 切断・死亡・ワールド移動・プラグイン無効化

### 2.5 テンション＆進捗ミニゲーム数理仕様（`REELING`）

2 tick（`dt = 0.1s`）ごとにサーバー側で決定的に更新する。クライアント予測は不要。

#### パラメータ（魚種ごとに YAML 定義）
- `difficulty` (`1.0..5.0`): 暴れ頻度・スイートスポット移動速度の基準
- `sweet_width` (`22.0..36.0`): スイートスポット幅（竿の `sweet_bonus` を加算、上限 `46.0`）
- `progress_per_sec` (`11.0..24.0`): スイートスポット内で引き上げ時の毎秒進捗上昇量
- `tension_decay_per_sec` (`18.0..28.0`): 非操作時のテンション自然低下量
- `thrash_chance_per_sec`: 魚が「暴れ (`THRASH`)」または「潜り (`DIVE`)」状態へ移行する確率

#### 魚の行動サブ状態（`FishBehavior`）
1. **`CALM`（安定・緑）**: 2.0〜4.0s 継続。スイートスポットが緩やかに移動（±4%/s）。右クリック引き上げで安全に進捗が伸びる。
2. **`THRASH`（暴れ・赤）**: 0.4s の事前予告（`TELEGRAPH`・黄）の後に 1.2〜2.2s 継続。右クリック引き上げ時のテンション上昇が **2.4×** になり、進捗効率は **0.3×** に低下。**手を離してテンションを逃がす**判断が求められる。
3. **`DIVE`（深潜り・青）**: 1.0〜1.6s 継続。テンションが自動で `+12/s` 上昇しつつ進捗が `-3/s` 押し戻される。左クリック「いなす（CD 1.5s）」を入力するとテンションを `-15` 緩和し、潜りを即時解除して進捗ボーナス `+6%` を得る。

#### 入力と更新式（`dt = 0.1s`）
- **右クリック（引き上げパルス）**: `ActionGate(150ms)` で受付。入力があると `pullBoostTimer = 0.35s` を設定（連打しても単発タップでも自然な操作感）。
  - `pullBoostTimer > 0` のとき:
    - `tension += (basePullTension * rodStability * behaviorTensionMult) * dt`
    - スイートスポット内（`tension in sweetMin..sweetMax`）: `progress += progressPerSec * dt`
    - スイートスポット外: `progress += progressPerSec * 0.28 * dt`
  - `pullBoostTimer <= 0` のとき:
    - `tension -= tensionDecayPerSec * dt`
    - `progress -= 2.5 * dt`（下限 0）
- **失敗判定**:
  - `tension >= 100.0` → 即座に `ESCAPED`（「糸が張りすぎて切れてしまった！」）
  - `tension <= 2.0` が累計 `2.5s` 連続 → `ESCAPED`（「糸が緩みすぎて針が外れた！」）
  - `reelingElapsed >= 45.0s`（ハード上限）→ `ESCAPED`（「体力が尽きて逃がしてしまった…」）
- **成功判定**:
  - `progress >= 100.0` → `CAUGHT`

#### UI 表示（Adventure API）
- **BossBar**（セッション中のみプレイヤーへ表示）:
  - 進捗率: `(progress / 100.0).toFloat().coerceIn(0f, 1f)`
  - 色: `CALM` + スイート内 = `GREEN` / `TELEGRAPH` or スイート外 = `YELLOW` / `THRASH` or `tension >= 80` = `RED` / `DIVE` = `BLUE`
  - タイトル例: `🎣 蒼穹の獲物と格闘中！ [暴れ！手を離せ！] 進捗 64%`
- **ActionBar**（200ms 間隔で更新、戦闘 HUD を一時抑止）:
  - 20 マスのテンションゲージを描画:
    `張力 [━━━|🟩🟩🟩🟩|━━●━━━] 68%  |  右:引く / 左:いなす`
- **Title**（イベント遷移時のみ、fadeIn=0, stay=18tick, fadeOut=6tick）:
  - `BITE_ALERT`: メイン `§e⚡ ヒット！` / サブ `§f右クリックで合わせろ！` + `ENTITY_FISHING_BOBBER_SPLASH` 音
  - `CAUGHT`: メイン `§b★ 釣り上げた！` / サブ `§f蒼穹サケ 71.2cm §6[金冠] §aNEW!` + `ENTITY_PLAYER_LEVELUP`（軽量ピッチ）
  - `ESCAPED`: メイン `§c逃げられた…` / サブ `§7テンションが高すぎた（糸切れ）`

### 2.6 魚種抽選・サイズ（cm）・王冠・スコア仕様

#### 抽選条件と重み式
候補魚は以下をすべて満たすもののみ抽出する:
1. `spot.area`（または `spot.id`）が魚の `habitats` に含まれる
2. `rod.tier >= fish.min_rod_tier`
3. `profile.lifeSkills["FISHING"].level >= fish.min_fishing_level`
4. `world.time` が魚の `time_windows` に一致（`ANY` / `DAY: 0..11999` / `DUSK_DAWN: 12000..13500, 22500..23999` / `NIGHT: 13501..22499`）
5. ワールド天候が魚の `weather` に一致（`ANY` / `CLEAR` / `RAIN` / `THUNDER`）

各候補の最終重み:
$$
W(\text{fish}) = \text{base\_weight} \times M_{\text{bait}}(\text{fish}) \times M_{\text{rarity}}(\text{bait}, \text{rarity}) \times M_{\text{fatigue}}(\text{spotCount})
$$

#### サイズ（cm）と王冠判定
- 平均 $\mu = \frac{\text{min\_cm} + \text{max\_cm}}{2}$、標準偏差 $\sigma = \frac{\text{max\_cm} - \text{min\_cm}}{5.2}$ の正規乱数を `[min_cm, max_cm]` にクランプ。
- 釣竿の大物補正 + 釣りレベル補正（`min(0.06, rodSizeBonus + (fishingLv - 1) * 0.002)`）を乗算し上限 `max_cm` に収める（小数第 1 位まで丸め）。
- 王冠判定:
  - `NONE`: `< silver_crown_cm`
  - `SILVER`（銀冠）: `>= silver_crown_cm`（おおむね上位 15%）
  - `GOLD`（金冠）: `>= gold_crown_cm`（おおむね上位 4%）
- 釣りアイテムの Lore にはサイズ表記を任意付与可能だが、**スタック可能性・ショップ/クエストの `COLLECT` 判定互換を保つため、付与されるアイテム実体の PDC `item_id` は純粋な魚アイテム ID（例: `ec:item_mat_sky_salmon`）とし、最大サイズ・王冠は `PlayerProfile.fishRecords` に永続記録する**（インベントリ圧迫・スタック分裂事故を防止）。

#### 釣りスコア算出式
$$
\text{FishingScore} = (\text{発見魚種数} \times 100) + (\text{銀冠数} \times 150) + (\text{金冠数} \times 400) + \min(1000, \text{累計釣果数} \times 2)
$$
- スコア `500` 到達 → `ec:flag_title_sky_angler` 解放（称号「蒼穹の釣り師」）
- スコア `1800` 到達（または全魚種コンプリート＋金冠3種以上） → `ec:flag_title_abyssal_fisher` 解放（称号「星海の大公」）

### 2.7 アンチ AFK・アンチ自動化・TPS 安全設計

| 脅威 | 対策仕様 |
| :--- | :--- |
| 固定間隔オートクリッカー / マクロ | 1) 待ち時間（2.5〜8.5s）・`THRASH` 発生タイミング・スイートスポット移動をサーバー側乱数で非決定的に変動。<br>2) `THRASH` 中に連打し続けるとテンションが即 100% に達し必ず糸切れ失敗する。<br>3) セッション単位の直近 12 回リングバッファで「ヒット反応時間 (ms)」と「巻き取りクリック間隔 (ms)」の標準偏差を計測。8 回以上連続で反応遅延 `stddev < 12ms` かつクリック間隔 `stddev < 10ms` の極端な機械挙動を検出した場合、ソフトスロットル（レア重み 0.1× + 警告メッセージ + 場所移動要求）を適用。 |
| 同一座標放置ファーム | プレイヤー×釣り場ごとの直近 10 分間の釣果数をメモリ管理。1〜12 匹: 100%、13〜20 匹: 待ち時間 +40%・レア率 0.7×、21 匹以降: 「魚影が薄くなった。別の釣り場へ移動しよう！」と表示し 5 分間休止（12m 以上離れた別スポットなら即時通常効率）。 |
| 当たり厳選（餌消費回避） | 餌は `BITE_ALERT` 開始時に消費。さらに `WAITING` 中の即時キャンセル連打を防ぐため、キャスト間に `1500ms` の `ActionGate` を設ける。 |
| エンティティ／タスクリーク | 仮想ウキは `isPersistent = false` + `ChunkLoadEvent` で孤立ウキを掃除。`onDisable` / `onQuit` / `onDeath` で BossBar・仮想ウキ・セッションを必ず解放。 |
| TPS 劣化 | ブロック探索はキャスト時 1 回のレイトレース（距離 12m）のみ。アクティブセッションの上限を `100` とし、1 つの 2-tick 共通タイマーで `activeSessions` のみ走査。パーティクルは 4-tick 間隔・最大 4 個/セッションに制限。 |

---

## 3. 独自採取システム（Custom RPG Gathering）

### 3.1 ワールド保護とバニラブロック破壊抑止

- `ec_world` 内の登録済み採取ノード座標（および半径 1 ブロック）は、`BlockBreakEvent` / `BlockPlaceEvent` / `EntityExplodeEvent` / `BlockPistonExtendEvent` / `BlockPistonRetractEvent` で完全に保護し、バニラ破壊・バニラドロップを一切発生させない。
- ノードの見た目はサーバー管理の仮想表示（`BlockDisplay` + 上部 `TextDisplay` ホログラム、`isPersistent = false`, PDC `ec:gather_node_id`）で描画する。
  - **採取可能時**: 対象ブロック素材（例: `AMETHYST_CLUSTER`, `IRON_ORE`, `SWEET_BERRY_BUSH`, `OAK_LOG`）の `BlockDisplay`（フルサイズ）+ ホログラム `§a[採取可能] §f星鉄鉱脈 (Lv.1 / ピッケルT1)`
  - **再出現待ち時**: 縮小/半透明相当の枯渇表示（例: `COBBLESTONE` / `DEAD_BUSH` 小サイズ `0.45`）+ ホログラム `§7[再出現待ち] あと 42秒`（プレイヤーごとのパーソナル再出現モデルでは、他プレイヤーにも見える共通ベース表示は常時設置し、個別クールダウン中のみ右クリック時に ActionBar で残り秒数を通知する方式が最も軽量で TPS に優しい）。

### 3.2 採取ノード種別と専用ツール

| ノード種別 (`node_kind`) | 必要ツール種別 (`tool_kind`) | 現行 YAML ツール例（T1 / T2） | 主な生息エリア |
| :--- | :--- | :--- | :--- |
| `ORE`（鉱脈） | `PICKAXE` | `ec:item_tool_pick_iron` (T1) / `ec:item_tool_pick_astral` (T2) | 嘆きの廃坑・星見の丘・蒼晶の氷窟 |
| `HERB`（薬草） | `SICKLE` | `ec:item_tool_sickle_silver` (T1) / `ec:item_tool_sickle_moon` (T2) | アッシュウッドの森・星見の丘・霧氷の尾根 |
| `WOOD`（古木・星木） | `HATCHET` | `ec:item_tool_hatchet_iron` (T1) / `ec:item_tool_hatchet_starwood` (T2) | ルミナ港外縁・アッシュウッドの森・霧氷の尾根 |
| `CRYSTAL`（星片結晶） | `EXTRACTOR` | `ec:item_tool_extractor_aether` (T1) / `ec:item_tool_extractor_void` (T2) | 星見の丘・虚核の聖堂外縁・蒼晶の氷窟 |

### 3.3 採取フロー・チャネリング・レア判定

1. プレイヤーがノードを右クリック（距離 `<= 4.5` ブロック、トランザクション非ロック）。
2. O(1) の座標マップ `nodesByBlock[Triple(world, x, y, z)]`（または `TextDisplay`/`BlockDisplay` の `ec:gather_node_id` PDC）からノード定義を取得。
3. 前提チェック:
   - メインハンドアイテムが要求 `tool_kind` を満たし、`tool.tier >= node.min_tool_tier`
   - `profile.lifeSkills["GATHERING"].level >= node.min_gathering_level`
   - パーソナル再出現クールダウン（`now >= personalReadyAt[uuid to nodeId]`）を経過済み
4. **チャネリング開始**（基本 `2.0s`〜`3.2s`、要求を上回るツール Tier ごとに `-15%`、最低 `1.2s`）:
   - 2-tick 間隔で ActionBar に `[採取中 ██████░░░░ 60%]` を表示、0.4s ごとに採掘音＋パーティクル（最大 3 個）。
   - 1.5 ブロック以上の移動・被ダメージ・持ち替えでキャンセル（クールダウン消費なし）。
5. **採取完了**:
   - 通常収穫（`min..max` 個）を確定抽選。
   - レア収穫判定:
     $$
     P_{\text{rare}} = \min\!\left(0.45,\; \text{base\_rare\_chance} + (\text{toolTier} - \text{minTier}) \times 0.05 + (\text{gatherLv} - 1) \times 0.005 + \text{partyBonus}\right)
     $$
   - `AdventureController.transaction` 経由でアイテム付与（満杯時は `pendingRewards` へ安全保管）、`profile.discoverItem`、`gatheringRecords[nodeType]` 加算、生活スキル XP＋冒険者 XP 付与、パーソナルクールダウン記録。
   - 一定累計数・全ノード発見で `ec:flag_title_master_gatherer`（称号「蒼穹の採集匠」）を解放。

### 3.4 ソロ／パーティ分配と荒らし防止

- **既定方式: パーソナル再出現（Per-Player Instanced Respawn）**
  - 各プレイヤーが独立した再出現タイマーを持つため、他プレイヤーに鉱脈や薬草を横取りされるストレスが一切ない。
  - **パーティ近接ボーナス**: 同一パーティの仲間が `party_radius`（24 ブロック）以内にいる場合、採取したプレイヤーのレア収穫率に `+0.08`（8% 加算、1 名以上で上限固定、AFK 寄生による自動配布は行わず実際に採取操作した本人のみ獲得）。
- **希少ノード向けオプション (`shared_node: true`)**:
  - 将来的に高価値なイベント結晶を追加する場合のみ有効化可能。最初の採取から `10 秒` のパーティ・タップ猶予ウィンドウを設け、その間に操作したメンバー全員が個別抽選を受けたのちワールド全体で再出現待ちへ移行する。

---

## 4. `ec_world` への水辺・採取ノード配置計画

既存の `DemoWorld.kt`（9 島構成、`center = island * 96`, `y = 64` 地表, `y = 65` 歩行面）の既存構造物・NPC 座標と衝突しない安全な座標を指定する。

### 4.1 釣りスポット定義（7 箇所）

`DemoWorld.kt` に非破壊な **v2 → v3 地形マイグレーション**（既存プレイヤーブロックを上書きせず、未建築の指定外縁座標にのみ 5×5〜7×5 深さ 2 の水辺プールを生成）を追加することで、`ec_world` 上ですぐに釣りが可能になる。カスタムワールド運用時は任意の自然水域＋エリア判定でそのまま動作する。

| スポット ID | エリア | 推奨座標（水面中心 `y=64`） | 特徴・主な魚種 |
| :--- | :--- | :--- | :--- |
| `ec:spot_lumina_pier` | `ec:area_lumina_port` | `(10, 64, 28)` | ルミナ港南桟橋の入り江。初心者向け。蒼穹サケ・銀鱗イワシ・港の黄金タイ |
| `ec:spot_ashwood_brook` | `ec:area_ashwood_forest` | `(114, 64, -22)` | 森の清流池。木漏れ日ヤマメ・蒼苔ウナギ・月影コイ（夜限定） |
| `ec:spot_mine_subterranean` | `ec:area_weeping_mine` | `(214, 64, 20)` | 廃坑の地下水脈。鉄鱗ナマズ・蛍光洞窟エビ |
| `ec:spot_stargazer_spring` | `ec:area_stargazer_hill` | `(270, 64, 22)` | 星見の泉。星影メダカ・天の川ウオ（夜/星片餌）・蒼穹龍魚（LEGENDARY） |
| `ec:spot_frostpeak_icehole` | `ec:area_frostpeak_outpost` | `(460, 64, 24)` | 前哨基地の氷穴。凍氷ワカサギ・蒼氷タラ |
| `ec:spot_glacier_rift` | `ec:area_glacier_ridge` | `(596, 64, -20)` | 霧氷の氷裂湖。氷晶ヒラメ・吹雪サメ（雨/雷・氷海餌） |
| `ec:spot_cavern_abyss` | `ec:area_crystalline_cavern` | `(694, 64, 22)` | 氷窟の深淵池。深淵アングラー・氷核のリヴァイアサン幼魚（LEGENDARY） |

### 4.2 採取ノード配置計画（各エリア 2〜3 箇所、計 16 ノード）

| ノード ID | 種別 | エリア | 座標 (`ec_world`) | 必要Tier | 再出現(秒) | 通常素材 / レア素材 |
| :--- | :--- | :--- | :--- | :---: | :---: | :--- |
| `ec:node_port_driftwood_1` | `WOOD` | `ec:area_lumina_port` | `(-24, 65, 22)` | 1 | 90 | 蒼穹の流木 / 星木の樹脂 |
| `ec:node_ashwood_herb_1` | `HERB` | `ec:area_ashwood_forest` | `(76, 65, -22)` | 1 | 90 | アッシュリーフ / 星光の蓮 |
| `ec:node_ashwood_herb_2` | `HERB` | `ec:area_ashwood_forest` | `(120, 65, 14)` | 1 | 90 | アッシュリーフ / 星光の蓮 |
| `ec:node_ashwood_wood_1` | `WOOD` | `ec:area_ashwood_forest` | `(84, 65, 24)` | 1 | 120 | 星木の原木 / 星木の樹脂 |
| `ec:node_ashwood_wood_2` | `WOOD` | `ec:area_ashwood_forest` | `(108, 65, 24)` | 1 | 120 | 星木の原木 / 星木の樹脂 |
| `ec:node_mine_ore_1` | `ORE` | `ec:area_weeping_mine` | `(172, 65, -24)` | 1 | 120 | 鉄くず・星鉄鉱石 / 熟練職人の砥石 |
| `ec:node_mine_ore_2` | `ORE` | `ec:area_weeping_mine` | `(214, 65, -20)` | 1 | 120 | 星鉄鉱石 / 蒼輝石の原石 |
| `ec:node_mine_ore_3` | `ORE` | `ec:area_weeping_mine` | `(174, 65, 22)` | 2 | 150 | 蒼輝石の原石 / 星片の欠片 |
| `ec:node_hill_herb_1` | `HERB` | `ec:area_stargazer_hill` | `(308, 65, -22)` | 2 | 150 | 星光の蓮 / 月露の霊草 |
| `ec:node_hill_crystal_1` | `CRYSTAL` | `ec:area_stargazer_hill` | `(266, 65, -22)` | 1 | 180 | 星片の欠片 / 蒼穹結晶核 |
| `ec:node_hill_crystal_2` | `CRYSTAL` | `ec:area_stargazer_hill` | `(310, 65, 20)` | 2 | 180 | 蒼穹結晶核 / 虚空の残滓 |
| `ec:node_sanctum_crystal_1` | `CRYSTAL` | `ec:area_void_sanctum` | `(362, 65, -24)` | 2 | 240 | 蒼穹結晶核 / 虚空の残滓 |
| `ec:node_ridge_herb_1` | `HERB` | `ec:area_glacier_ridge` | `(554, 65, 22)` | 2 | 150 | 霜花の薬草 / 月露の霊草 |
| `ec:node_ridge_wood_1` | `WOOD` | `ec:area_glacier_ridge` | `(594, 65, 24)` | 2 | 150 | 凍星木の古材 / 星木の樹脂 |
| `ec:node_cavern_ore_1` | `ORE` | `ec:area_crystalline_cavern` | `(652, 65, -24)` | 2 | 180 | 蒼氷の欠片・蒼輝石の原石 / 熟練職人の砥石 |
| `ec:node_cavern_crystal_1` | `CRYSTAL` | `ec:area_crystalline_cavern` | `(692, 65, -22)` | 2 | 240 | 蒼氷の欠片 / 氷核結晶 |

---

## 5. データ駆動 YAML スキーマ案（新規コンテンツ型）

> **重要**: 以下の新規 `type`（`fishing_spot`, `fish_species`, `gathering_node`, `tackle`, `life_balance`）は Codex のエンジン実装時に `ContentService` / `WorldContent` へ追加する。**先行ブランチ `ag/fishing-gathering` のライブ YAML には既存スキーマ（`item`, `npc`, `dialogue`, `shop`, `quest`, `chapter`, `title`）のみを配置し、現行 `ContentService` の起動・テスト互換性を 100% 維持する。**

### 5.1 生活バランス定義 (`balance/life_skills.yml` / `type: life_balance`)

```yaml
schema_version: 1
id: ec:life_balance
type: life_balance
life_level_cap: 20
life_xp_base: 80
life_xp_exponent: 1.45
cast_range_blocks: 12.0
cast_cooldown_ms: 1500
max_concurrent_fishing_sessions: 100
fatigue_window_seconds: 600
fatigue_soft_threshold: 12
fatigue_hard_threshold: 20
fatigue_cooldown_seconds: 300
reeling_timeout_seconds: 45
slack_fail_seconds: 2.5
party_gather_rare_bonus: 0.08
fishing_score_milestones:
  - {score: 500, flag: 'ec:flag_title_sky_angler'}
  - {score: 1800, flag: 'ec:flag_title_abyssal_fisher'}
gathering_milestones:
  - {total_harvests: 60, unique_nodes: 8, flag: 'ec:flag_title_master_gatherer'}
```

### 5.2 釣具・採集具・餌メタ定義 (`tackle/*.yml` / `type: tackle`)

既存の `items/*.yml` を参照し、生活システム専用パラメータのみを外出しすることで、戦闘アイテム定義を汚さない設計。

```yaml
schema_version: 1
id: ec:tackle_rod_starwood
type: tackle
item: ec:item_tool_rod_starwood
kind: ROD                 # ROD | BAIT | PICKAXE | SICKLE | HATCHET | EXTRACTOR
tier: 2                   # 1..3
min_life_level: 7
wait_reduction: 0.12      # 0.0..0.5
sweet_spot_bonus: 5.0     # 0.0..15.0
tension_stability: 0.88   # 0.5..1.2 (低いほど暴れ時の張力上昇を抑制)
size_bonus: 0.03          # 0.0..0.15
channel_speed_bonus: 0.0  # 採集具用 (0.0..0.4)
rare_chance_bonus: 0.0    # 採集具用 (0.0..0.2)
```

```yaml
schema_version: 1
id: ec:tackle_bait_frost_krill
type: tackle
item: ec:item_bait_frost_krill
kind: BAIT
tier: 3
min_life_level: 10
wait_reduction: 0.25
rarity_weights:
  COMMON: 0.8
  UNCOMMON: 1.1
  RARE: 1.6
  EPIC: 1.6
  LEGENDARY: 1.4
preferred_tags: [FROST, ABYSSAL]
preferred_tag_multiplier: 2.0
```

### 5.3 釣り場定義 (`fishing_spots/*.yml` / `type: fishing_spot`)

```yaml
schema_version: 1
id: ec:spot_lumina_pier
type: fishing_spot
name: ルミナ港・南桟橋の入り江
area: ec:area_lumina_port
world: ec_world
center: {x: 10, y: 64, z: 28}
radius: 14.0
default_for_area: true
tags: [PORT, FRESHWATER]
```

### 5.4 魚種定義 (`fish_species/*.yml` / `type: fish_species`)

```yaml
schema_version: 1
id: ec:fish_sky_salmon
type: fish_species
name: 蒼穹サケ
item: ec:item_mat_sky_salmon
rarity: COMMON              # COMMON | UNCOMMON | RARE | EPIC | LEGENDARY
tags: [PORT, FRESHWATER]
habitats: [ec:area_lumina_port, ec:area_ashwood_forest]
time_windows: [ANY]         # ANY | DAY | NIGHT | DAWN_DUSK
weather: [ANY]              # ANY | CLEAR | RAIN | THUNDER
min_rod_tier: 1
min_fishing_level: 1
base_weight: 45
min_cm: 35.0
max_cm: 85.0
silver_crown_cm: 72.0
gold_crown_cm: 80.5
life_xp: 22
adventurer_xp: 12
minigame:
  bite_window_ms: 2100
  difficulty: 1.2
  sweet_width: 34.0
  progress_per_sec: 22.0
  tension_decay_per_sec: 22.0
  thrash_chance_per_sec: 0.14
  dive_chance_per_sec: 0.05
lore_hint: 'ルミナ港の清流を回遊する銀鱗の魚。時間帯を問わず初心者の竿でも狙える。'
```

### 5.5 採取ノード定義 (`gathering_nodes/*.yml` / `type: gathering_node`)

```yaml
schema_version: 1
id: ec:node_type_stariron_vein
type: gathering_node
name: 星鉄鉱脈
node_kind: ORE              # ORE | HERB | WOOD | CRYSTAL
display_material: IRON_ORE
depleted_material: COBBLESTONE
required_tool_kind: PICKAXE
min_tool_tier: 1
min_gathering_level: 1
channel_ms: 2200
respawn_seconds: 120
per_player_respawn: true
life_xp: 24
adventurer_xp: 14
yields:
  - {item: 'ec:item_mat_stariron_ore', min: 1, max: 2, chance: 1.0}
  - {item: 'ec:item_mat_iron_scrap', min: 1, max: 2, chance: 0.55}
rare_yields:
  - {item: 'ec:item_mat_artisan_whetstone', min: 1, max: 1, chance: 0.12, pity_after: 12}
locations:
  - {id: mine_ore_1, area: ec:area_weeping_mine, world: ec_world, x: 172, y: 65, z: -24}
  - {id: mine_ore_2, area: ec:area_weeping_mine, world: ec_world, x: 214, y: 65, z: -20}
```

---

## 6. 経済・経験値バランス設計（`BALANCE.md` 準拠）

### 6.1 上限・時間効率ガイドライン

| 指標 | `balance/default.yml` 上限 | 釣り目標レンジ | 採取目標レンジ | 戦闘周回（参考） |
| :--- | :---: | :---: | :---: | :---: |
| 単発冒険者 XP | `xp_gain_cap: 10000` | 10〜55 XP / 匹 | 10〜45 XP / 回 | 45〜180 XP / 体 |
| 冒険者 XP / 時間 | — | ~450〜1,200 XP/h | ~500〜1,300 XP/h | ~3,500〜7,000 XP/h |
| 単発 G 報酬（クエスト） | `currency_gain_cap: 5000` | 220〜650 G | 220〜650 G | 300〜1,200 G |
| 売却換金効率 / 時間 | `sell_ratio: 0.3` | ~600〜1,250 G/h | ~550〜1,150 G/h | ~1,500〜2,500 G/h |

### 6.2 魚種・採取素材の価格と売買アービトラージ防止

- 全アイテムのショップ売却額は `floor(item.price * 0.3)`。
- ショップで直接販売する釣具・餌・採集具・食材・料理は、**購入価格（割引適用後） > `floor(item.price * 0.3) * amount`** をすべてのエントリで厳密に保証する。
- トークン交換（魚→料理・採取素材→エリクサー/砥石）は、素材売却額の合計より完成品売却額が低くなるよう設定し、**交換→即売却による無限金策を物理的に不可能**にする。
  - 例: `蒼穹サケ` (`price: 45`, 売却 `13G`) ×2 = 売却計 `26G` → 料理 `蒼穹サケの香草焼き` (`price: 60`, 売却 `18G`) ×2 = `36G` の場合、ショップは `buyback: true` だが `ec:item_con_*` の単価を適切に設定する（例: 新料理 `price: 55..90`、売却 `16..27G` で常に素材売却合計未満または手数料ゴールドを添える）。
- **既存アイテムへの影響**: 既存の `ec:item_mat_sky_salmon`, `ec:item_mat_starlight_lotus`, `ec:item_mat_artisan_whetstone`, `ec:shop_artisan_forge` の値は変更しない。

---

## 7. 永続化・マイグレーション仕様（`PlayerProfile` Schema v9 → v10）

`PlayerStore.kt` の既存アトミック保存（`.tmp` + `fsync` + `.bak` + `ATOMIC_MOVE`）をそのまま維持し、`schema_version` を `9 → 10` へ拡張する。

### 7.1 追加フィールド（`PlayerProfile`）

```kotlin
data class LifeSkillProgress(var level: Int = 1, var xp: Long = 0)
data class FishRecord(var count: Int = 0, var maxCm: Double = 0.0, var crown: String = "NONE")
```

```yaml
schema_version: 10
# ... 既存 v9 フィールド ...
life_skills:
  FISHING: {level: 4, xp: 145}
  GATHERING: {level: 3, xp: 60}
fish_records:
  ec:fish_sky_salmon: {count: 14, max_cm: 78.4, crown: SILVER}
  ec:fish_moon_carp: {count: 2, max_cm: 91.2, crown: GOLD}
gathering_records:
  ec:node_type_stariron_vein: 18
  ec:node_type_ashwood_herb: 24
```

### 7.2 マイグレーション＆バリデーション不変条件
- `v0..v9 -> v10`: `life_skills` は `{FISHING: {level: 1, xp: 0}, GATHERING: {level: 1, xp: 0}}`、`fish_records` は `{}`、`gathering_records` は `{}` で初期化。
- 読込時検証:
  - `life_skills` のキーは `FISHING` / `GATHERING`、`level in 1..lifeLevelCap`、`xp >= 0`
  - `fish_records`: `count in 0..1_000_000`, `max_cm.isFinite() && max_cm in 0.0..10000.0`, `crown in setOf("NONE", "SILVER", "GOLD")`
  - `gathering_records`: `count in 0..1_000_000`
- `PlayerProfile.snapshot()` で全追加マップをディープコピーし、非同期 IO スレッド（`ec-player-io`）への共有ミューテーションを禁止する。

---

## 8. コマンド・図鑑 UI・ユーザー導線

| コマンド | 権限 | 動作 |
| :--- | :--- | :--- |
| `/ec fish` | 一般 | 現在の釣り Lv / XP / 釣りスコア / 発見魚種数 / 装着中の餌を表示 |
| `/ec fish bait <item_id\|auto\|none>` | 一般 | 優先使用する餌を切替 |
| `/ec gather` | 一般 | 現在の採取 Lv / XP / 発見ノード数 / 累計採取数を表示 |
| `/ec codex` | 一般 | 既存エリア図鑑に加え、下部バーに「魚類図鑑 (slot 51)」「採取図鑑 (slot 52)」「アイテム図鑑 (slot 53)」を配置 |
| `/ec admin lifeskill <player> <FISHING\|GATHERING> <level>` | `ec.admin` | QA / 管理者用レベル設定（既存の権限チェック踏襲） |

### 新規 NPC・ショップ・クエスト導線（現行スキーマで先行実装済み）
- **蒼穹の釣り師ミナト (`ec:npc_minato`)**: ルミナ港南桟橋 `(12, 65, 22)`。釣りギルド商店 (`ec:shop_angler_guild`: 釣竿 3 種・餌 3 種・魚料理レシピ) と釣り進行クエスト＋デイリー釣り依頼を担当。
- **採集案内人ハナ (`ec:npc_hana`)**: ルミナ港西広場 `(-20, 65, -14)`。採集交換所 (`ec:shop_gatherer_exchange`: 採集具 4 種＋上位 2 種・採取調合レシピ) と採集進行クエスト＋デイリー採集依頼を担当。
- **新規章定義 (`ec:chapter_life_professions` / `chapters/life_professions.yml`)**: 既存章（Ch1 / Ch2 / 外伝）のクエスト数・フラグ数アサーションに影響を与えずに、生活クエスト・NPC・称号フラグを宣言。

---

## 9. Codex 実装チェックリスト

1. **Core (`LifeSkill.kt`, `FishingMinigame.kt`, `GatheringEngine.kt`)**:
   - 純粋ロジックとして切り出し、モック不要の JUnit テストで状態遷移・テンション糸切れ/緩み失敗・サイズ/王冠分布・疲労減衰・アンチマクロ標準偏差判定・採取レア率/ピティを単体検証する。
2. **Content Loader (`WorldContent.kt`, `WorldValidation.kt`)**:
   - `life_balance`, `tackle`, `fishing_spot`, `fish_species`, `gathering_node` を任意読み込み（未配置ワールドでも起動可）＋厳格参照検証（存在しない `item` / `area` / 範囲外パラメータを拒否）。
3. **Persistence (`PlayerStore.kt`, `Game.kt`)**:
   - `schema_version` 9 → 10 マイグレーション + `snapshot()` ディープコピー + ラウンドトリップテスト。
4. **Paper Controllers (`FishingController.kt`, `GatheringController.kt`, `RpgPlugin.kt`, `DemoWorld.kt`)**:
   - `PlayerFishEvent` 完全キャンセル、`ec_world` ノード保護、`BlockDisplay`/`ItemDisplay` の `isPersistent = false` と `onDisable`/`onQuit`/`ChunkLoadEvent` での確実な掃除。
   - 2-tick 釣りタイマー・1-sec 採取表示タイマーの TPS 安全確認。
   - `RpgPlugin.openCodex` への魚類・採取タブ追加と `/ec fish` / `/ec gather` コマンド追加。
   - `DemoWorld.kt` v2 → v3 非破壊マイグレーションで 7 釣りスポットの水辺プールを追加。
