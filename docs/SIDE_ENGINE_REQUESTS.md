# Side Content Engine Requests (For Codex Implementation)

横の広がりコンテンツ拡張 (`ag/side-content` / `ec:chapter_side_chronicles`) は、現行 v1 YAML スキーマ（`docs/CONTENT_SCHEMA.md`）および `BALANCE.md` 上限の範囲内で完結する部分をすべて YAML 化・検証済みです。  
以下は本ブランチでは実装せず、今後の Codex 実装向けに残すランタイム拡張要望一覧です（優先度: **必須 / 推奨 / 任意**）。

---

## 1. [必須] 支援・タンク・暗殺ロール向けスキル挙動拡張 (`SkillEngine` / `RpgPlugin`)
- **現状の制約**:
  - `SkillDefinition` の `heal` / `defense_buff` は詠唱者自身にのみ適用される。
  - 敵のヘイト管理（挑発/タウント）や、ステルス・背後会心・コンボポイントなどの暗殺固有メカニクスが存在しない。
  - `ClassMenu` の職業アイコンは `ResourceKind`（`RAGE`/`FOCUS`/`MANA`）の3種で固定分岐しているため、同リソースの複数職が同じアイコンになる。
- **要望内容**:
  1. **パーティ範囲回復・防御バフ (`party_heal` / `party_defense_buff` / `ally_radius`)**: 星導士 (`ec:class_cleric`) の `cleric_mend` / `cleric_radiance` / `cleric_blessing` および聖騎士 (`ec:class_paladin`) の `paladin_aegis` / `paladin_sanctuary` が周囲のパーティメンバーにも回復・防御バフを付与できるようにする。
  2. **挑発・ヘイト倍率 (`taunt_ticks` / `threat_multiplier`)**: 聖騎士の `paladin_bash` / `paladin_aegis` が周囲のRPGモブのターゲットを一定時間自身に引き付け、Co-op でのタンク役割を成立させる。
  3. **職業メニューアイコンのYAML指定 (`icon: MATERIAL`)**: `classes/*.yml` に任意 `icon` フィールドを追加し、6職（戦士/弓/魔導士/聖騎士/星導士/影刃）を視覚的に区別できるようにする。
  4. **影刃固有ボーナス（推奨）**: `backstab_multiplier`（敵背後からの攻撃倍率）または連続スキル命中時の短時間会心バフ。

## 2. [必須] デイリー／ウィークリー固定時刻リセット (`QuestEngine` / `PlayerProfile`)
- **現状の制約**:
  - 現行の `repeatable: true` は完了時刻からの相対クールダウン (`repeat_cooldown_ms`) のみ対応（現在はデイリー `86400000ms` / ウィークリー `604800000ms` で代替）。
- **要望内容**:
  - クエストYAMLに任意の `reset_schedule: DAILY | WEEKLY`（および `balance/default.yml` に基準タイムゾーン/時刻、例 `daily_reset_hour_utc: 20` = JST 05:00、週次リセット曜日 `MONDAY`）を追加。
  - ショップの `daily_limit` と共通の暦日・暦週キーでリセットし、プレイヤーが毎日決まった時間にデイリー/ウィークリーを受け直せるようにする。

## 3. [推奨] チャレンジダンジョン：ボスラッシュ連戦＆スコア・ランク集計 (`DungeonController`)
- **現状の制約**:
  - `dungeon_configs` は単一ボス＋護衛構成のみ対応（現在は `ec:dungeon_aether_crucible` を「第1章・第2章エリート混成護衛＋試練ボス `ec:boss_aether_chimera`」として実装済み）。クリアタイム計測やランク別報酬がない。
- **要望内容**:
  - `dungeon_configs` に任意フィールド `waves: [{boss, guards, ...}, ...]`（複数ウェーブ連戦）と `score_ranks: [{rank: S, max_seconds: 120, bonus_items: [...]}]` を追加。
  - 撃破タイム・被弾回数・パーティ戦闘不能数からスコアと `S / A / B / C` ランクを算出し、自己ベストを `PlayerProfile` に保存（`/ec dungeon records` 表示）。

## 4. [推奨] 実績・称号システムと表示切替 (`PlayerProfile` / チャット・ネームプレート)
- **現状の制約**:
  - 現在は隠しクエスト・試練の報酬フラグ（`ec:flag_title_truth_seeker`, `ec:flag_title_frost_walker`, `ec:flag_title_crucible_victor`）として獲得状態のみ保持。
- **要望内容**:
  - `content/titles/*.yml`（`id`, `name`, `condition`, `description`, 任意の軽微な非戦闘/称号表示メタデータ）を追加。
  - `/ec title` GUI（またはコマンド）で解放済み称号一覧を確認・装備し、チャット接頭辞や ActionBar/TabList に表示。

## 5. [推奨] 収集図鑑（敵ベストiary・アイテム図鑑） (`PlayerProfile` / GUI)
- **現状の制約**:
  - 討伐数や初入手アイテムの履歴は `PlayerProfile` に永続化されていない（現在はノラ／レンの依頼テキストと報酬 `ec:item_acc_veteran_emblem` で雰囲気を表現）。
- **要望内容**:
  - `PlayerProfile` に `bestiary: Map<String, Int>`（敵ID→討伐数）と `discovered_items: Set<String>`（入手済みアイテムID）を記録。
  - `/ec codex` GUI でエリア別の発見率・弱点属性・ドロップ一覧を閲覧でき、達成率マイルストーン（25%/50%/75%/100%）で報酬フラグや称号を自動解放。

## 6. [推奨] 装備強化（+1〜+5）・専用素材クラフトUI (`TradeEngine` / GUI)
- **現状の制約**:
  - 現在は名匠カエデの工房 (`ec:shop_artisan_forge`) でトークン交換ショップとして上位武具・料理・秘薬のクラフトを代替している。既存アイテム個体への強化値付与 (`+1`〜`+5`) や上位変換時のPDC引き継ぎは未対応。
- **要望内容**:
  - `content/recipes/*.yml`（入力アイテム＋素材＋ゴールド→出力アイテム）または金床型強化GUI (`/ec craft`, `/ec enhance`) を導入。
  - `ec:item_mat_artisan_whetstone` を消費して装備の PDC `enhance_level`（上限 `+5`、1段階ごとに基礎ステ+3%、必ず `balance.stat_cap`・予算上限内でキャップ）を強化可能にする（失敗ロストなし・安全設計）。

## 7. [任意] 釣り・採集ミニコンテンツのワールド実装 (`Paper` リスナー)
- **現状の制約**:
  - 現在はカエデの反復依頼（`ec:quest_side_g01_sky_fishing`, `ec:quest_side_g02_starlight_herb`）およびドロップ/交換経由で `ec:item_mat_sky_salmon`・`ec:item_mat_starlight_lotus` を入手する構成。
- **要望内容**:
  - `PlayerFishEvent` をエリア判定と連動させ、`ec:area_lumina_port` や `ec:area_frostpeak_outpost` の水辺で確率により `ec:item_mat_sky_salmon` 等のRPG魚・宝箱を釣り上げられるようにする（既存バニラドロップの乗っ取り防止・AFK釣り対策のクールダウン付き）。
  - 各エリアにリスポーン型の採集ノード（花・鉱石ブロック）を配置し、右クリック採取で `ec:item_mat_starlight_lotus` や鉱石素材を得られる仕組みを追加。
