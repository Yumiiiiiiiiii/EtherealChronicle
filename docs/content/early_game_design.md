# 序盤やり込みコンテンツ設計書（Lv1〜8 / 第1章エリア拡張）

- **対象ブランチ**: `ag/early-content`（`master` 最新ベース）
- **チャプターID**: `ec:chapter_early_adventures`（外伝「ルミナ見習い冒険録」、推奨 Lv1〜8 / 上限 Lv10、`areas: []` で既存エリアへ配置）
- **設計方針**:
  1. メインストーリー（第1章・第2章）を引き延ばさず、**序盤1〜3時間（Lv1〜8、ルミナ港〜アッシュウッドの森〜嘆きの廃坑入口）**の寄り道・周回・収集・強化・挑戦ループを厚くする。
  2. **現行エンジン仕様で YAML 完結**できる要素をすべて実装し、エンジン改修が必要な要素は `docs/EARLY_ENGINE_REQUESTS.md` へ分離する。
  3. 既存経済（`sell_ratio: 0.3` / `xp_gain_cap: 10000` / `currency_gain_cap: 5000` / `global_enemy_cap: 100`）および `BalanceSimulationTest` の戦闘バランスを一切破壊しない。

---

## 1. 序盤1〜3時間の体験フロー（Lv1〜8）

| 時間帯 | 想定Lv | メイン進行 | 追加される序盤やり込みループ |
| --- | --- | --- | --- |
| **0:00〜0:35** | Lv1〜3 | ルミナ港で職業選択・スライム初討伐 | 依頼掲示板係**レイラ**から序盤デイリー（スライム掃討 / 朝露・星鉄納品）受注 → 港西端の星鉄礫を採取・桟橋で**港ハゼ**釣り → 隠しNPC**ルナ**発見＆初回隠し箱回収 |
| **0:35〜1:20** | Lv3〜5 | アッシュウッドの森探索（狼・ゴブリン・クモ） | **強化入門クエスト**で粗砥の欠片→名匠の研磨砥石を生成し、武器/見習いの星守りを **`+1〜+2` 強化** → 朝露苔・琥珀樹・表層星鉄脈を採取し、**柳の精密入門竿**＆**朝露ミミズ虫**で薄明の**朝焼けハヤ**狙い → 称号「港町の新鋭討伐者」「ルミナ収集見習い」自動解放 |
| **1:20〜2:15** | Lv5〜7 | アッシュウッド深部〜星蝕トレント | 試練教官**ルーク**から **3ウェーブ制タイムアタック『見習い試練の闘技場』** 解放 → 灰森の北東端で隠しNPC**バルド**発見＆秘録クエスト → 雨夜の**雨夜ウナギ**／薄明・夜の**琥珀ニシキゴイ**狙い |
| **2:15〜3:00+** | Lv6〜8 | 嘆きの廃坑へ進む前の仕上げ | **獣王アッシュウッド・アルファ（Lv7）**撃破で **`+3` 強化**＆**獣王星牙の護符**入手 → Sランク（120秒以内）記録更新、序盤ウィークリー「灰森の大掃討作戦」、称号「見習い闘技場の勝者」「灰森の熟練猟師」「灰森の秘録発見者」「蒼穹博物収集家」コンプ |

---

## 2. 6本柱の詳細設計

### (1) 序盤ギルド掲示板（デイリー・ウィークリー・4時間反復）
配置NPC: **`ec:npc_leila`**（依頼掲示板係レイラ / ルミナ港 `(-6, 65, -14)`、`ec:dlg_leila_board`、`ec:shop_rookie_board`）

| クエストID | 種別 / リセット | 推奨Lv | 達成条件 | 報酬（上限内・裁定取引ゼロ） |
| --- | --- | --- | --- | --- |
| `ec:quest_early_d01_slime_patrol` | DAILY (UTC 20:00) | Lv1 | エーテルスライム×5討伐＋報告 | 180 XP / 90 G / 蒼穹見習いの証×2 / 粗砥の欠片×1 |
| `ec:quest_early_d02_forest_bounty` | DAILY (UTC 20:00) | Lv3 | イバラオオカミ×4＋コケゴブリン×4＋報告 | 360 XP / 150 G / 蒼穹見習いの証×2 / 粗砥の欠片×2 |
| `ec:quest_early_d03_supply_gather` | DAILY (UTC 20:00) | Lv2 | 朝露苔×2＋川底の星鉄礫×2納品＋報告 | 260 XP / 110 G / 蒼穹見習いの証×2 / 朝露ミミズ虫×2 |
| `ec:quest_early_w01_ashwood_campaign` | WEEKLY (月曜 UTC 20:00) | Lv5 | イバラオオカミ×8＋胞子グモ×6＋星蝕トレント×1＋報告 | 1400 XP / 480 G / 蒼穹見習いの証×6 / 名匠の研磨砥石×2 |
| `ec:quest_early_r01_spider_silk_rush` | 4時間反復 (`14400000ms`) | Lv4 | 胞子グモ×3討伐＋川底の星鉄礫×2納品＋報告 | 280 XP / 95 G / 蒼穹見習いの証×1 / 粗砥の欠片×1 |

- **納品裁定防止検証**:
  - `d03_supply_gather`: 朝露苔×2(140G)＋星鉄礫×2(140G) = 280G > 報酬110G＋売却価値(証9×2＋虫6×2=30G) = 140G。
  - `r01_spider_silk_rush`: 星鉄礫×2(140G) > 報酬95G＋売却価値(証9＋欠片7=16G) = 111G。

### (2) 序盤収集図鑑の拡張・コンプ報酬と進捗付き称号（6称号）
- **収集図鑑クエスト（レイラ窓口）**:
  - `ec:quest_early_c01_port_atlas`（Lv2 / ルミナ港の恵み図鑑）: 港ハゼ×2＋川底の星鉄礫×2＋朝露苔×2 → 320 XP / 160 G / 証×2 / 港ハゼの香草揚げ×2
  - `ec:quest_early_c02_ashwood_atlas`（Lv5 / 灰森の珍魚と琥珀図鑑）: 苔ドジョウ×2＋朝焼けハヤ×1＋森の琥珀樹液×2 → 620 XP / 280 G / 証×3 / 名匠の研磨砥石×1
- **新設称号 6種（全件 `advancement` 定義・親子ツリー対応・`FLAG` / `BESTIARY` / `COLLECTION` 全3条件を活用）**:

| 称号ID | 表示名 | 条件種別 | 条件詳細 | 親進捗 (`parent`) | 枠 (`frame`) |
| --- | --- | --- | --- | --- | --- |
| `ec:title_rookie_slayer` | 港町の新鋭討伐者 | `BESTIARY` | `ec:enemy_aether_slime` 10体討伐 | なし（ルート直下） | `TASK` |
| `ec:title_ashwood_hunter` | 灰森の熟練猟師 | `BESTIARY` | `ec:enemy_corrupted_treant` 3体討伐 | `ec:title_rookie_slayer` | `GOAL` |
| `ec:title_novice_gladiator` | 見習い闘技場の勝者 | `FLAG` | `ec:flag_early_trial_cleared` | `ec:title_rookie_slayer` | `CHALLENGE` |
| `ec:title_early_collector` | ルミナ収集見習い | `COLLECTION` | 発見アイテム数 15種 | なし（ルート直下） | `TASK` |
| `ec:title_lumina_curator` | 蒼穹博物収集家 | `COLLECTION` | 発見アイテム数 35種 | `ec:title_early_collector` | `GOAL` |
| `ec:title_grove_secret_finder` | 灰森の秘録発見者 | `FLAG` | `ec:flag_early_hermit_secret` | `ec:title_early_collector` | `GOAL` |

### (3) 序盤ミニチャレンジ『見習い試練の闘技場』（Lv5〜8・3ウェーブ＋タイムランク）
- 配置NPC: **`ec:npc_rook`**（見習い試練教官ルーク / ルミナ港 `(18, 65, 14)`、`ec:dlg_rook_arena`、`ec:shop_novice_arena`）
- ダンジョンID: **`ec:dungeon_novice_arena`**（解放条件: Lv5＋ルーク会話で `ec:flag_early_trial_unlocked` 付与）
- **ウェーブ構成**:
  - Wave 1: `ec:enemy_thorn_wolf`（ボス枠）＋ `ec:enemy_aether_slime`×2（護衛）
  - Wave 2: `ec:enemy_spore_spider`（ボス枠）＋ `ec:enemy_moss_goblin`×2（護衛）
  - Wave 3: **`ec:boss_ashwood_alpha`**（灰森の獣王アッシュウッド・アルファ / Lv7 インスタンス専用ボス、`spawn_points: []` で `global_enemy_cap` 消費0）＋ `ec:enemy_thorn_wolf`×1
- **タイムアタック・スコアランク**（`profile.recordChallenge` で自己ベスト自動永続化）:
  - **S**: 120秒以内 / **A**: 210秒以内 / **B**: 330秒以内 / **C**: 600秒以内
- **ボス設計（`ec:boss_ashwood_alpha`、Lv7）**:
  - HP `7600` / ATK `26` / DEF `26` / 攻撃間隔 `44t` / 弱点 `[FIRE, AETHER]` / 耐性 `[ICE]` / 2フェーズ（HP45%以下で与ダメ1.15倍・間隔0.88倍）
  - 技3種: `alpha_lunge`（直線突進・予兆24t）、`thorn_howl`（周囲スロウ咆哮・予兆28t）、`feral_pounce`（HP45%以下解禁の跳躍衝撃・予兆30t）
  - **Lv7ソロ実測TTK（全6職業・RARE以下装備＋序盤回復80HP/10s）**:
    - 戦士 `33.1s` / 弓師 `36.2s` / 魔導士 `27.1s` / 聖騎士 `37.1s` / 星導士 `32.8s` / 影刃 `26.6s`（全6職ソロ勝利、Wave1〜3通算約65〜110秒でSランク120秒が好目標）
  - ドロップ（`ec:drop_ashwood_alpha`）: `ec:item_mat_alpha_fang`（確定1〜2）、`ec:item_mat_whetstone_shard`（85%）、`ec:item_mat_rookie_crest`（60%）、レアドロップ `ec:item_acc_alpha_fang_talisman`（15% / 天井8回）
- **関連クエスト**:
  - `ec:quest_early_t01_arena_trial`（初回制覇 / Lv5〜）
  - `ec:quest_early_t02_arena_daily`（デイリー挑戦 / Lv6〜）

### (4) 序盤釣り・採取の深化（Life Lv1〜10 導線・時間/天候限定魚・料理）
- **新設序盤魚種 5種**（既存の水辺プール `ec:area_lumina_port` / `ec:area_ashwood_forest` / `ec:area_weeping_mine` に対応、`ec:fish_sunbeam_trout` の `difficulty: 1.1` を最易のまま維持するため Lv1 COMMON は `difficulty >= 1.25`）:
  1. `ec:fish_harbor_goby`（港ハゼ / COMMON / Lv1 / 港 / ANY / difficulty 1.25）
  2. `ec:fish_moss_loach`（苔ドジョウ / COMMON / Lv2 / 灰森 / ANY / difficulty 1.30）
  3. `ec:fish_dawn_minnow`（朝焼けハヤ / UNCOMMON / Lv3 / 港・灰森 / **DAWN_DUSK** / difficulty 1.55）
  4. `ec:fish_rain_eel`（雨夜ウナギ / RARE / Lv5 / 灰森・廃坑 / **NIGHT × RAIN/THUNDER** / difficulty 2.00）
  5. `ec:fish_amber_koi`（琥珀ニシキゴイ / EPIC / Lv7 / 灰森 / **DAWN_DUSK, NIGHT** / difficulty 2.55 — 序盤の憧れ大物枠）
- **新設序盤釣具 2種**:
  - `ec:tackle_rod_willow`（柳の精密入門竿 / T1 / Life Lv3 / 待ち-8%, スイート+3.0, テンション安定0.96 — 樫の竿Lv1と星木竿Lv7の橋渡し）
  - `ec:tackle_bait_dew_grub`（朝露ミミズ虫 / T1 / Life Lv2 / `FRESHWATER, FOREST` ×1.65, UNCOMMON/RARE倍率UP）
- **新設序盤採取ノード 4種（6座標、既存ノードと座標衝突ゼロ）**:
  - `ec:node_port_pebble_ore`（川底の星鉄礫 / ORE / T1 / Lv1 / ルミナ港 `(-24,65,-4)`）→ 鉄礫＋レア粗砥の欠片
  - `ec:node_ashwood_dew_moss`（朝露苔の群生 / HERB / T1 / Lv1 / 灰森 `(68,65,-24)`, `(118,65,-26)`）→ 朝露苔＋レア琥珀樹液
  - `ec:node_ashwood_amber_tree`（琥珀の星木 / WOOD / T1 / Lv3 / 灰森 `(70,65,22)`, `(126,65,22)`）→ 星木丸太＋琥珀樹液＋レア星木樹脂
  - `ec:node_ashwood_surface_ore`（灰森の表層星鉄脈 / ORE / T1 / Lv3 / 灰森 `(128,65,-10)`）→ 鉄礫＋鉄くず＋レア粗砥の欠片（廃坑到達前に鉱石採取＋砥石確保が可能に）
- **新設序盤料理 3種**（レイラの掲示板商店で魚・採取素材から物々交換調理、すべて `slot: NONE`・裁定ゼロ）:
  - `ec:item_con_goby_fritter`（港ハゼの香草揚げ / HEAL 55）
  - `ec:item_con_loach_broth`（苔ドジョウと朝露苔の滋養汁 / HEAL 70 + RESOURCE 22）
  - `ec:item_con_amber_koi_glaze`（琥珀コイの照り焼き / HEAL 135 + DEFENSE_BUFF 12）

### (5) 隠し要素（隠しNPC・初回隠し宝箱相当報酬・時間/天候限定魚ヒント）
- **隠しNPC 1: `ec:npc_luna`**（星灯りの番人ルナ / ルミナ港 北西端 `(-28, 65, -28)`）
  - 発見会話で `ec:flag_early_luna_found` 付与。
  - 初回限定の隠し支給（`GIVE_ITEM` + `once_flag: ec:flag_early_luna_cache`）で `ec:item_bait_dew_grub`×3 を贈呈。
  - 薄明（DAWN_DUSK）の朝焼けハヤ・雨夜（NIGHT＋RAIN/THUNDER）の雨夜ウナギ・夜の琥珀ニシキゴイの出現条件ヒントを語り、隠し釣り依頼 `ec:quest_early_h01_luna_twilight_catch`（報酬: 柳の精密入門竿＋朝露ミミズ虫×3）を発行。
- **隠しNPC 2: `ec:npc_baldo`**（森の隠者バルド / アッシュウッドの森 北東端 `(130, 65, -32)`）
  - 発見会話で `ec:flag_early_baldo_found` 付与。
  - 初回限定の隠し宝箱（`GIVE_ITEM` + `once_flag: ec:flag_early_baldo_cache`）で `ec:item_mat_hermit_scroll`（古木の秘録断簡）を贈呈（図鑑コレクション加算）。
  - 隠し依頼 `ec:quest_early_h02_baldo_grove_secret` を完了すると `ec:flag_early_hermit_secret` が立ち、称号「灰森の秘録発見者」＋名匠の研磨砥石×2＋琥珀コイの照り焼きを獲得。

### (6) 序盤の装備強化（`+1〜+3`）＆素材クラフト導線
- 現行強化仕様（`balance/default.yml`）: 最大 `+5`、1段階ごとに全ステータス `+3%`、基礎費用 `100G × 次段階`＋`ec:item_mat_artisan_whetstone`（名匠の研磨砥石）。
- 従来の課題: 研磨砥石の入手法が Lv6+ デイリーや廃坑素材（Lv10〜）中心で、Lv3〜6 の序盤プレイヤーが `/ec enhance` に触れにくかった。
- **新設する序盤導線**:
  1. Lv1〜3 の港・灰森の鉱石ノード（`port_pebble_ore` / `ashwood_surface_ore`）と序盤デイリー/反復から **`ec:item_mat_whetstone_shard`（粗砥の欠片）** と **`ec:item_mat_river_pebble_ore`（川底の星鉄礫）** を安定入手。
  2. レイラの商店 `craft_early_whetstone` で **粗砥の欠片×4 ＋ 星鉄礫×2 → 名匠の研磨砥石×1** に精錬（また蒼穹見習いの証×3＋40Gでも交換可）。
  3. 強化入門クエスト `ec:quest_early_e01_first_temper`（Lv3）で `/ec enhance` の手順と `+1〜+3`（+3%〜+9%）の恩恵を解説し、達成報酬で **名匠の研磨砥石×2＋180G** を即時支給。
  4. 序盤アクセサリ2種（`ec:item_acc_rookie_charm` Lv4 UNCOMMON / `ec:item_acc_alpha_fang_talisman` Lv7 RARE）は予算内（`withinBudget()` 準拠）かつ第1章終盤装備（EPIC/LEGENDARY）を下回る設計とし、序盤強化の最初のターゲットとして機能させる。

---

## 3. 経済・バランス安全チェック一覧

| チェック項目 | 設計値 / 基準 | 検証方法 |
| --- | --- | --- |
| ショップ金銭・トークン裁定 | 全エントリで `sell(output) < buy_price` または `sell(output) < sum(sell(tokens))` | `EarlyGameContentValidationTest` で全件自動検証 |
| デイリー/反復納品裁定 | 市場購入額 > 報酬G＋報酬アイテム売却額 | 同上で全納品クエストを検証 |
| 装備予算 & 既存ボス影響 | 新規装備2種のみ（Lv4 UNCOMMON 12.5/14.4、Lv7 RARE 25.0/27.0）、他17種は `slot: NONE` | `withinBudget()`＋既存 `BalanceSimulationTest` 無改変パス |
| `global_enemy_cap` | `ec:boss_ashwood_alpha` は `spawn_points: []`（消費0、全体74/100維持） | `EarlyGameContentValidationTest` で上限確認 |
| `defaultDebugFish` 互換 | Lv1 COMMON 新規魚は `difficulty >= 1.25`（`sunbeam_trout` 1.1 を維持） | 既存 `FishingUiFeedbackTest` パス |
| NPC・採取ノード座標衝突 | 新規NPC 4体・採取ノード6点とも既存全座標と重複なし | `EarlyGameContentValidationTest`＋`FishingGatheringContentValidationTest` |
