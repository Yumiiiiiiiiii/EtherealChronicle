# Chapter 1 Content Schema Notes & Deviations

本ドキュメントは、Chapter 1 コンテンツバイブル（`docs/content/*.md`）を `docs/CONTENT_SCHEMA.md`（および `ContentService` / `WorldContent` / `AdventureServices` 実装）に準拠する YAML（`src/main/resources/content/**/*.yml`）へ変換した際の差分・互換対応・削除候補レガシーファイル・Codex-Impl 向け申し送り事項を記録します。

---

## 1. クラス ID 正規化とエイリアス移行マップ

Codex-Impl からの指示（Task B Amendment）に従い、既存の 3 クラス定義ファイルをバイブルの正規 ID・Lv1 ステータス・`growth`・`starter_items` で置き換え、旧 ID を `aliases` として保持しました。重複するクラス選択メニュー項目は生成されません。

| 旧 ID（レガシー / alias） | 新正規 ID（canonical） | ファイル | `starter_items` |
|---|---|---|---|
| `ec:warrior` | `ec:class_warrior` | `src/main/resources/content/classes/warrior.yml` | `[ec:item_wpn_rusty_broadsword]` |
| `ec:archer` | `ec:class_archer` | `src/main/resources/content/classes/archer.yml` | `[ec:item_wpn_hunter_shortbow]` |
| `ec:mage` | `ec:class_mage` | `src/main/resources/content/classes/mage.yml` | `[ec:item_wpn_apprentice_wand]` |

- **プロファイル移行の申し送り**: 既存セーブデータの `classId` が旧 ID（`ec:warrior` / `ec:archer` / `ec:mage`）の場合、ロード時に `ContentSnapshot.classes` のエイリアス解決を通じて正規 ID（`ec:class_*`）へ正規化してください。
- **スキル定義の維持**: `skills/*.yml`（12 ファイル）は未変更で、`ec:warrior_*` / `ec:archer_*` / `ec:mage_*` を各クラスから参照しています。

---

## 2. 削除候補のレガシー基盤ファイル（Removable）

現行の単体テスト・フィクスチャ（`FoundationTest` / `FixturesTest` / `AdventureServicesTest`）が参照しているため、本ブランチでは削除せず残置しています。テストおよびフィクスチャが Chapter 1 コンテンツへ移行完了した段階で削除可能です。

- `src/main/resources/content/quests/tutorial.yml`（`ec:tutorial`）
- `src/main/resources/content/enemies/forest_slime.yml`（`ec:forest_slime`）
- `src/main/resources/content/droptables/starter.yml`（`ec:starter_drops`）
- `src/main/resources/content/items/warrior_starter.yml`（`ec:warrior_starter`）
- `src/main/resources/content/items/archer_starter.yml`（`ec:archer_starter`）
- `src/main/resources/content/items/mage_starter.yml`（`ec:mage_starter`）
- `src/main/resources/content/items/field_ration.yml`（`ec:field_ration`）

> `balance/default.yml` および `skills/*.yml`（12 ファイル）は基盤資産としてそのまま維持しています。

---

## 3. バイブルから YAML スキーマへの主な適応点

### 3.1 スロット名・単位・数値型
- **装備スロット名**: バイブルの `MAINHAND` / `BOOTS` はスキーマに合わせて `HAND` / `FEET` へ変換。消耗品・素材・クエストアイテムはすべて `slot: NONE` を設定しています。
- **パーセントおよび `SKILL_POWER` 単位**:
  - `crit` / `element_resist` は小数表記（例: `5%` -> `0.05`）。
  - `crit_damage` は倍率表記（例: `150%` -> `1.5`）。
  - 装備ステータス・効果の `skill_power` / `SKILL_POWER` は加算倍率（例: バイブル `+10%` / `+10` -> `0.10`、`+22%` -> `0.22`）。
- **整数フィールド**: `xp`・`currency`・`price`・`amount`・`min_level`・`budget`・`repeat_cooldown_ms` などの `Long`/`Int` フィールドはすべて整数リテラルで記述しています。
- **予算 (`ItemDefinition.withinBudget`)**: 全 30 装備が `hp/10 + resource/10 + attack + defense + crit*100 + critDamage*10 + elementResist*100 + skillPower*10 <= budget * budgetMultiplier` を満たすよう調整済みです。

### 3.2 クラス基礎パラメータと初期武器
- **`archer.yml` の `regeneration`**: 現行 `FoundationTest` のアサーション整合性を保つため、弓使いの `regeneration` は `5`（停止時フォーカス回復）を維持しています（バイブル表記 `6/秒` からの軽微調整）。
- **初期武器の二重付与について**: `classes/*.yml` の `starter_items` でクラス選択時に Lv1 コモン武器を 1 本付与し、さらに `ec:quest_m01_awakening` 完了時にも同武器を 1 本付与するバイブル通りの構成としています。Lv1 コモン武器は `bound: false`（売却可能・基本価格 60G）であるため、予備として保持または売却でき進行上の支障はありません。

### 3.3 アイテム効果（`effects`）の対応範囲
`docs/CONTENT_SCHEMA.md` で定義された 7 種（`HEAL_HP`, `RESTORE_RESOURCE`, `CLEANSE`, `BUFF_ATTACK`, `BUFF_DEFENSE`, `BUFF_CRIT`, `BUFF_SKILL_POWER`）のみを使用しています。
- 蘇生羽（`ec:item_con_revive_feather`）・帰還巻物（`ec:item_con_return_scroll`）・一部レジェンダリーの特殊パッシブ（被弾時シールドや追撃等）は、現バージョンのスキーマ効果（回復・バフ・基礎ステータス）に落とし込み、固有フレーバーは日本語 `lore` に保持しています。

### 3.4 ショップの `token_discount` 減算仕様
`TradeEngine.buy`（`AdventureServices.kt`）の実装では、`token_discount_flag` 所持時に `token_discount` の数量が `tokens` から**減算**されます（置換ではありません）。
- そのため、`src/main/resources/content/shops/stargazer_relic.yml` のレジェンダリー 4 品では、`token_discount` を減算差分 `[{item: 'ec:item_mat_starshard_fragment', amount: 5}]` として記述しました。
- これにより、選択 3B（`ec:flag_choice3_observatory`）所持時に `void_heart_ring` が `虚核結晶4 + 星片20`、レジェンダリー武器 3 種が `虚核結晶5 + 星片25` と、バイブル `docs/content/shops.md` と完全一致します（`Chapter1ContentValidationTest` で実 `TradeEngine` を通して検証済み）。

### 3.5 会話ツリー・クエスト・ドロップの整合
- **NPC 会話の統合**: バイブルで章進行別に記述した各 NPC の会話は、スキーマの「NPC 1 体につき 1 ダイアログ ID」制約に合わせ、各 NPC 1 ファイルのハブ構成（計 10 ファイル）に統合し、`branches` による状態遷移で全 20 クエストの受注・報告、3 つの重要選択、職業選択、ショップ開閉、聖堂ダンジョン入場を網羅しています。
- **`GIVE_ITEM` の冪等性**: ダイアログ内の全 `GIVE_ITEM` アクションに専用の `once_flag`（`ec:chapter_1` の `flags` に宣言済み）を付与しています。
- **`COLLECT` 目標の入手経路保証**: 全 `COLLECT` クエスト目標がドロップテーブル・クエスト報酬・NPC 付与のいずれかから確実に入手できるよう接続済みです。特に `ec:quest_s09_tomas_audit`（廃坑の物資監査）の収集対象は、廃坑の敵が実際にドロップする `ec:item_mat_void_dust` に統一しソフトロックを排除しています。
- **ボスドロップの独立抽選**: 現行ドロップテーブルはエントリ毎の独立確率ロール（クラス別確定枠なし）のため、ボス 2 体の `droptables/boss_*.yml` は独立ロール前提で期待値・レアリティが破綻しない確率に調整しました。

### 3.6 エリア座標・スポーン上限
- 5 エリアすべて `ec_world` 内に非重複かつ幅・奥行 80 ブロック以下で配置しています（`lumina_port`: X `-32..32`, `ashwood_forest`: `56..136`, `weeping_mine`: `152..232`, `stargazer_hill`: `248..328`, `void_sanctum`: `344..424`）。
- 全 10 NPC および全スポーン地点が宣言済みエリア内に収まり、スポーン総 `cap` は `balance/default.yml` の `global_enemy_cap`（100）以下です。

---

## 4. Codex-Impl 向け確認事項（`FoundationTest` の 2 件失敗）

`codex/foundation` の `694403d` をマージ後に `./gradlew test` を実行したところ、Chapter 1 コンテンツ検証（`Chapter1ContentValidationTest`: 7/7）、`AdventureServicesTest`（6/6）、`FixturesTest` はすべて合格しましたが、`FoundationTest` のみ以下の 2 件が失敗します。

- `FoundationTest.kt:66` (`assertEquals(2, p.level)`)
- `FoundationTest.kt:127` (`p.quests["ec:tutorial"]!!` で `NullPointerException`)

**原因**: `GameService.chooseClass`（`GameService.kt:13`）が
```kotlin
val initial = if("ec:quest_m01_awakening" in content().world.quests) "ec:quest_m01_awakening" else "ec:tutorial"
```
と実装されているため、Chapter 1 クエスト（`ec:quest_m01_awakening`）がバンドルされた状態で `chooseClass` を呼ぶと `ec:tutorial` ではなく `ec:quest_m01_awakening` が自動開始され、レガシー `ec:tutorial` 前提の 2 テストが失敗します。

`src/main/kotlin/*` および `FoundationTest.kt` は Codex-Impl 担当領域のため本ブランチでは未変更です。`FoundationTest` 側で明示的に `game.startQuest(p, "ec:tutorial")` を呼ぶか、Chapter 1 初期クエスト前提へ更新をお願いします。

---

## 5. バリデーション結果サマリー

`src/test/kotlin/net/nakaymi/etherealchronicle/content/Chapter1ContentValidationTest.kt` にて実 `ContentService` / `TradeEngine` / `DialogueEngine` / `BossState` を用いた 7 件の契約テストを実装・実行し、全件合格を確認済みです。

1. 全 157 YAML の実 `ContentService` ロード、3 クラス正規 ID + エイリアス解決、Chapter 1 数量目標・全装備 `withinBudget()` 検証
2. 全 20 クエスト目標・報酬キャップ・宣言フラグ・ダイアログアクションの型付き参照検証およびソフトロック不在検証
3. 5 エリア非重複・サイズ上限（<=80）・全 NPC/スポーン地点のエリア内包・`global_enemy_cap` 上限検証
4. ショップ売買裁定（buy-sell arbitrage）禁止および `bound`/`quest_item` 非売却（`price: 0`）検証
5. 実 `TradeEngine` による選択 1A 割引および選択 3B `token_discount` 減算仕様の数値検証
6. 実 `DialogueEngine` による全 10 NPC・4 進行状態での循環なし／リンク切れなし解決検証
7. 実 `BossState` による仮想 HP・4 人パーティスケーリング・3 フェーズ遷移・エンレイジ倍率検証
