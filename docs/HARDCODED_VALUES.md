# コード内の数値・設定値調査

Task 14 の調査結果です。既存の balance / life_balance YAML にある値はサーバー編集で調整できます。下記のコード定数は残件として記録し、今回変更した既定動作は従来値のままです。

## 既にデータ化されている主な調整値

| 領域 | 設定例 | 用途 |
| --- | --- | --- |
| 戦闘・成長 | `content/balance/default.yml` の `damage_cap`, `stat_cap`, `xp_gain_cap`, `currency_gain_cap`, `level_cap`, `basic_attack_cooldown_ms`, `skill_gcd_ms` | 戦闘上限、報酬上限、レベル、攻撃・スキル間隔 |
| 経済・パーティ | `sell_ratio`, `max_currency`, `transaction_amount_cap`, `party_size_cap`, `party_xp_radius` | 売却率、通貨・取引上限、共有範囲 |
| ダンジョン | `instance_cap`, `instance_timeout_ticks` | 同時instance数と制限時間 |
| 釣り・採取 | `content/balance/life_skills.yml` のキャスト距離、待ち時間、セッション上限、疲労・再挑戦、採取補正 | 生活スキルのプレイヤー調整値 |
| 言語 | `config.yml` の `language` | `auto`, `ja_jp`, `en_us` |

## コードに残る候補

| 値 | 現在の既定値 | 所在 | 判定 |
| --- | ---: | --- | --- |
| 採取セッション同時数 | 100 | `paper/GatheringController.kt` | 調整可能にするなら `life_balance.max_concurrent_gathering_sessions` へ移す。専用controllerの初期化順を含むschema変更が必要なため今回未変更。 |
| 採取状態更新間隔 | 2 ticks | `paper/GatheringController.kt` | ゲーム難度ではなくTPS負荷と進捗精度のトレードオフ。利用者設定にすると性能劣化を招くため固定。 |
| ダンジョン入場パーティ集合半径 | 32 blocks | `paper/DungeonController.kt` | `party_xp_radius` は経験値共有範囲であり意味が異なる。設定を共有せず固定。将来は別の `dungeon_party_radius` 候補。 |
| 近接ボーナス距離 | 16 blocks（二乗距離256） | `paper/GatheringController.kt` | `party_gather_radius` 候補。現在の小規模ボーナス仕様に紐づく。 |
| UI / 表示数 | 45会話選択肢、54装備枠、1000診断履歴 | `paper/AdventureController.kt`, `paper/GatheringController.kt` | Bukkit GUI枠数とメモリ保護用の実装上限。ユーザー向けバランス値ではない。 |
| 表示座標・建築寸法 | チャンク境界16、instance間隔192、部屋外周±24、拠点保護半径 | `paper/DemoWorld.kt`, `paper/DungeonController.kt`, controllers | ワールド生成と衝突防止の構造値。balance設定には適さず、生成方式変更と一体で扱う。 |
| 一時猶予・ポーリング周期 | scheduler tick間隔、transaction callback猶予 | `paper/*Controller.kt`, `RpgPlugin.kt` | 非同期整合性やTPS負荷に関わる実装値。安易に設定化しない。 |

## 方針

レベル・報酬・戦闘・経済・クールダウンなどプレイヤー体験を変える値は既存 balance YAML を優先します。ワールド寸法、GUI最大枠、非同期整合性の猶予、短周期schedulerのような構造・安全値はコード側に置き、変更時には対応するテストで既定動作を保護します。上表の候補を設定化する場合は、先にスキーマと上限を追加し、初期値は記載の現行値を維持してください。
