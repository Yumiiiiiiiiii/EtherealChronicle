# 序盤やり込みコンテンツ向け エンジン改修リクエスト一覧

本ドキュメントは `docs/content/early_game_design.md`（ブランチ `ag/early-content`）の設計・実装過程で、**現行 YAML スキーマのみでは実現できない／代替手段で実装した項目**を Codex / エンジン担当向けに優先度順でまとめたものです。

> 注: `ag/early-content` ブランチでは `src/main/kotlin/*` を一切変更せず、現行仕様で完結するコンテンツのみを実装しています。以下の項目がエンジンへ実装された際、YAML 側をそのまま拡張・接続できる ID 設計にしています。

---

## P0（優先度：高 — 序盤やり込みの達成感・進捗連動に直結）

### 1. クエスト目標 `ObjectiveKind` の生活・強化対応
- **現状**: `ObjectiveKind` は `KILL`, `COLLECT`, `EXPLORE`, `TALK` の4種のみ。
  - 強化入門クエスト（`ec:quest_early_e01_first_temper`）では「素材を揃えて報告」までしか検証できず、実際に `/ec enhance` で `+1` 強化を実行した事実を目標にできない。
  - 釣り・採取の序盤依頼（`d03`, `h01`, `c01`, `c02`）では「指定アイテムを所持（`COLLECT`）」で代替しているため、市場購入や既存在庫での即時納品と区別できない。
- **要望**: 以下の `ObjectiveKind` を追加し、各コントローラから `QuestEngine.event(...)` を発火してほしい。
  - `CATCH_FISH`（`target`: `ec:fish_xxx` または `ANY`、`amount`: 匹数）
  - `GATHER_NODE`（`target`: `ec:node_xxx` または `HERB`/`ORE`/`WOOD`/`CRYSTAL`、`amount`: 採取回数）
  - `ENHANCE_GEAR`（`target`: 最低到達段階 e.g. `1` / `3` または `ANY`、`amount`: 強化成功回数）

### 2. 称号・進捗 `TitleConditionType` の拡張（生活Lv・図鑑カテゴリ・強化段階）
- **現状**: `TitleConditionType` は `FLAG`, `BESTIARY`（単一敵の討伐数）, `COLLECTION`（`discoveredItems.size` 総数）の3種のみ。
- **要望**: 序盤の「集める・育てる」手応えを直接 Minecraft 進捗ツリーへ反映するため、以下を追加してほしい。
  - `FISH_SPECIES_COUNT`: 釣り上げた固有魚種数（`profile.fishRecords.size >= count`）
  - `LIFE_LEVEL`: 指定生活スキル（`FISHING` / `GATHERING`）のレベル到達（`>= count`）
  - `ENHANCE_LEVEL`: いずれかの装備を `+count` 以上に強化した実績
  - `CHALLENGE_RANK`: 指定ダンジョン（例: `ec:dungeon_novice_arena`）でランク `S` 以上を達成

---

## P1（優先度：中 — 探索の驚き・隠し要素・水辺バリエーション強化）

### 3. ワールド設置型の「隠し宝箱（インタラクト可能チェスト／ブロック）」スキーマ
- **現状**: 隠し宝箱を直接ワールド座標へ配置するデータ駆動スキーマがないため、`ag/early-content` ではエリア端の隠しNPC（ルナ／バルド）の `GIVE_ITEM` + `once_flag` 会話で代替している。
- **要望**: `gathering_nodes` と同様の `BlockDisplay` + `Interaction`（またはチェスト実体）で、1プレイヤー1回限りの隠し宝箱（`treasure_chests/*.yml`: `id`, `world/x/y/z`, `once_flag`, `items`, `currency`, `hint_particle`）を YAML 定義できるようにしてほしい。

### 4. `DemoWorld` v3 → v4 の隠し序盤水辺追加＆重複釣り場の優先度制御
- **現状**:
  - `DemoWorld` v3 で生成される水辺はルミナ港 `(10,64,28)`・アッシュウッド `(92,64,30)`・嘆きの廃坑 `(190,64,28)` の3箇所（各エリア1箇所）のみ。
  - `FishingController.cast` は `fishingSpots.values.firstOrNull { 半径内 }` で最初に一致したスポットを返すため、同一エリア内に「隠し釣り場」を追加しても水辺が存在せず、また重複半径のスポットはマップ順序依存になる。
- **要望**:
  1. `DemoWorld` v4 でアッシュウッド北東の隠れ泉 `(124,64,-24)` などに隠し水辺プールを追加。
  2. `fishing_spots/*.yml` に `priority`（数値降順で狭い隠しスポットを優先）と `unlock_flag`（例: `ec:flag_early_luna_found` 解放後に隠し魚枠解禁）を追加。

### 5. 時間帯・天候限定の隠しNPC / レア敵スポーン条件
- **現状**: 魚種（`fish_species/*.yml`）は `time_windows` / `weather` に完全対応しているが、NPC や敵スポーン点には時間帯・天候条件がない。
- **要望**: `spawn_points` や `npcs/*.yml` に任意の `time_windows: [NIGHT, DAWN_DUSK]`・`weather: [RAIN, THUNDER]` を追加し、「雨夜だけ現れる灰森の希少敵」「薄明だけ現れる行商人」を YAML で組めるようにしてほしい。

---

## P2（優先度：低 — QoL・周回モチベーション向上）

### 6. チャレンジダンジョンのランク別初回ボーナス報酬（S/A/B/C）
- **現状**: `DungeonController` は `score_ranks` に基づいて S/A/B/C ランクと自己ベスト秒数を保存・表示するが、ランク到達に応じた初回報酬アイテム付与（例: `ec:dungeon_novice_arena` 初回Sランクで名匠の研磨砥石×2）は YAML 定義できない。
- **要望**: `score_ranks` エントリへ任意の `first_clear_flag` / `first_clear_items` を持たせ、Sランク更新を目指す動機を直接報酬化してほしい。

### 7. ギルド掲示板（デイリー・ウィークリー）の一覧GUI／ショートカット
- **現状**: レイラの会話選択肢から個別受注・報告する形式（現行仕様で完全動作）。
- **要望**: 依頼数が増えてきた段階（現在デイリー3＋ウィークリー1＋反復1＋収集2＋強化1）で、NPC の `OPEN_BOARD` アクションまたは `/ec board` から残りリセット時刻付きで一括確認・受注できる軽量 GUI があると序盤の導線がさらに快適になる。
