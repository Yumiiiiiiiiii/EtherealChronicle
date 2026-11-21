# 第1章 ショップ設計バイブル (`ec:shop_*`)

> **概要（YAML変換用データ設計）**
> - **総ショップ数**: **5店舗**（ルミナ港3 / 嘆きの廃坑1 / 星見の丘1）
> - **通貨体系**:
>   - `GOLD`: 標準通貨（クエスト・敵ドロップ・素材売却）
>   - `TOKEN` (`ec:item_mat_starshard_fragment` 浄化星片 / `ec:item_mat_void_core_essence` 虚核のエッセンス): ボス・エリート・反復クエストから獲得する進行トークン（レジェンダリー天井交換対応）
> - **経済不正防止（Anti-Exploit）ルール**:
>   1. **売却係数一律 `0.30x`**（割引適用時の買値 `0.90x` を大幅に下回るため、買戻し転売ループが構造的に不可能）。
>   2. ショップ取引はコア層で **原子的（Atomic）トランザクション** とし、インベントリ満杯時・残高不足時は即ロールバック。
>   3. レア消耗品（`ec:item_con_revive_feather` 等）には1日あたり購入上限または所持推奨目安を設定しインフレを防止。

---

## 1. 鍛冶屋ガルドの武具店 (`ec:shop_lumina_blacksmith`)
- **担当NPC**: `ec:npc_garudo`（`ec:area_lumina_port`・鍛冶場）
- **買取対応**: 装備品・鉱石系素材（基本売却率 `30%`）
- **ストーリー連動特典**: `ec:flag_choice1_extract` 保有時、**全品10%割引**（端数切上げ）。

| 商品ID (`ec:item_*`) | アイテム名 | レアリティ | 通貨 | 通常価格 | 割引価格(1B) | 解放条件 |
|---|---|---|---|---|---|---|
| `ec:item_wpn_rusty_broadsword` | 鉄の訓練剣 | `COMMON` | `GOLD` | `60G` | `54G` | 初期 |
| `ec:item_wpn_hunter_shortbow` | 狩人の短弓 | `COMMON` | `GOLD` | `60G` | `54G` | 初期 |
| `ec:item_wpn_apprentice_wand` | 見習いの樫杖 | `COMMON` | `GOLD` | `60G` | `54G` | 初期 |
| `ec:item_arm_lumina_helm` | ルミナ見習いの兜 | `COMMON` | `GOLD` | `90G` | `81G` | 初期 |
| `ec:item_off_oak_buckler` | 樫の丸盾 | `COMMON` | `GOLD` | `120G` | `108G` | 初期 |
| `ec:item_off_leather_quiver` | 革の矢筒 | `COMMON` | `GOLD` | `120G` | `108G` | 初期 |
| `ec:item_off_worn_grimoire` | 古びた魔導書 | `COMMON` | `GOLD` | `120G` | `108G` | 初期 |
| `ec:item_wpn_ashwood_blade` | 灰木の大剣 | `UNCOMMON` | `GOLD` | `320G` | `288G` | `Lv4` 以上 |
| `ec:item_wpn_ash_longbow` | 灰木の長弓 | `UNCOMMON` | `GOLD` | `320G` | `288G` | `Lv4` 以上 |
| `ec:item_wpn_lumina_staff` | ルミナ水鏡の杖 | `UNCOMMON` | `GOLD` | `320G` | `288G` | `Lv4` 以上 |
| `ec:item_arm_ashwood_boots` | 灰木林の軽足具 | `UNCOMMON` | `GOLD` | `340G` | `306G` | `Lv5` 以上 |
| `ec:item_wpn_minebreaker_greatsword` | 破岩の鉱山大剣 | `RARE` | `GOLD` | `1,250G` | `1,125G` | `ec:quest_s06_garudo_forge` 完了 |
| `ec:item_wpn_crystal_recurve` | 結晶弦のリカーブボウ | `RARE` | `GOLD` | `1,250G` | `1,125G` | `ec:quest_s06_garudo_forge` 完了 |
| `ec:item_wpn_quartz_scepter` | 雷晶の魔導錫 | `RARE` | `GOLD` | `1,250G` | `1,125G` | `ec:quest_s06_garudo_forge` 完了 |

---

## 2. 薬草商ミラの調合屋 (`ec:shop_lumina_apothecary`)
- **担当NPC**: `ec:npc_mira`（`ec:area_lumina_port`・市場通り）
- **買取対応**: 薬草・魔物素材（基本売却率 `30%`）
- **ストーリー連動特典**: `ec:flag_choice1_purify` 保有時、**全品10%割引**（端数切上げ）。

| 商品ID (`ec:item_*`) | アイテム名 | 通貨 | 通常価格 | 割引価格(1A) | 解放条件 |
|---|---|---|---|---|---|
| `ec:item_con_minor_hp_potion` | 小さな回復薬 | `GOLD` | `25G` | `23G` | 初期 |
| `ec:item_con_antidote_herb` | 解毒の香草 | `GOLD` | `40G` | `36G` | 初期 |
| `ec:item_con_resource_elixir` | エーテル霊薬 | `GOLD` | `95G` | `86G` | `Lv4` 以上 |
| `ec:item_con_medium_hp_potion` | 中級回復薬 | `GOLD` | `85G` | `77G` | `ec:flag_mine_unlocked` または `Lv7` 以上 |
| `ec:item_con_starlight_tea` | 星見のハーブティー | `GOLD` | `190G` | `171G` | `ec:flag_stargazer_unlocked` |
| `ec:item_con_greater_hp_potion` | 上級回復薬 | `GOLD` | `210G` | `189G` | `ec:flag_stargazer_unlocked` または `Lv13` 以上 |

---

## 3. 行商人ピップの露店 (`ec:shop_lumina_general`)
- **担当NPC**: `ec:npc_pip`（`ec:area_lumina_port`・桟橋広場）
- **買取対応**: **全カテゴリの不要アイテム・素材を一括買取**（売却率 `30%`、冒険者のメイン換金窓口）

| 商品ID (`ec:item_*`) | アイテム名 | 通貨 | 価格 | 解放条件 |
|---|---|---|---|---|
| `ec:item_con_return_scroll` | ルミナ帰還の巻物 | `GOLD` | `35G` | 初期 |
| `ec:item_con_lumina_stew` | ルミナ港の海鮮シチュー | `GOLD` | `55G` | 初期 |
| `ec:item_con_minor_hp_potion` | 小さな回復薬 | `GOLD` | `28G` | 初期（ミラより少し割高な利便価格） |
| `ec:item_con_miner_ration` | 坑夫のスタミナ焼き肉 | `GOLD` | `135G` | `ec:flag_mine_unlocked` |

---

## 4. 廃坑補給官トマスの兵站所 (`ec:shop_mine_quartermaster`)
- **担当NPC**: `ec:npc_tomas`（`ec:area_weeping_mine`・入口野営地）
- **買取対応**: 素材・装備品（基本売却率 `30%`）
- **特徴**: 嘆きの廃坑攻略中の前線補給基地。Lv10〜12帯の中級装備と消耗品を供給。

| 商品ID (`ec:item_*`) | アイテム名 | レアリティ | 通貨 | 価格 | 解放条件 |
|---|---|---|---|---|---|
| `ec:item_con_medium_hp_potion` | 中級回復薬 | 消耗品 | `GOLD` | `85G` | 初期 |
| `ec:item_con_antidote_herb` | 解毒の香草 | 消耗品 | `GOLD` | `40G` | 初期 |
| `ec:item_con_miner_ration` | 坑夫のスタミナ焼き肉 | 消耗品 | `GOLD` | `130G` | 初期 |
| `ec:item_con_return_scroll` | ルミナ帰還の巻物 | 消耗品 | `GOLD` | `35G` | 初期 |
| `ec:item_con_revive_feather` | 不死鳥の星羽 | 消耗品 | `GOLD` | `380G` | `ec:quest_m05_mine_betrayal` 完了（1日上限3個） |
| `ec:item_arm_miner_greaves` | 坑夫の補強脚甲 | `UNCOMMON` | `GOLD` | `680G` | 初期 |
| `ec:item_wpn_minebreaker_greatsword` | 破岩の鉱山大剣 | `RARE` | `GOLD` | `1,250G` | `Lv10` 以上 |
| `ec:item_wpn_crystal_recurve` | 結晶弦のリカーブボウ | `RARE` | `GOLD` | `1,250G` | `Lv10` 以上 |
| `ec:item_wpn_quartz_scepter` | 雷晶の魔導錫 | `RARE` | `GOLD` | `1,250G` | `Lv10` 以上 |
| `ec:item_off_obsidian_aegis` | 黒曜の鉄壁盾 | `RARE` | `GOLD` | `1,100G` | `ec:flag_iron_grinder_defeated`（または浄化星片×8） |
| `ec:item_off_windrunner_quiver` | 疾風の星羽矢筒 | `RARE` | `GOLD` | `1,100G` | `ec:flag_iron_grinder_defeated`（または浄化星片×8） |
| `ec:item_off_astral_tome` | 星詠みの魔導書 | `RARE` | `GOLD` | `1,100G` | `ec:flag_iron_grinder_defeated`（または浄化星片×8） |

---

## 5. 星見の賢者リラの星片交換所 (`ec:shop_stargazer_relic`)
- **担当NPC**: `ec:npc_lyra`（`ec:area_stargazer_hill`・天文台跡）
- **買取対応**: なし（専用交換・準備ショップ）
- **役割**: 協力ダンジョン「虚核の聖堂」の準備品供給、および `ec:item_mat_starshard_fragment`（浄化星片）・`ec:item_mat_void_core_essence`（虚核のエッセンス）による **エピック〜レジェンダリー装備の確定入手（天井システム）** を担う。
- **ストーリー連動特典**: 選択3B（`ec:flag_choice3_observatory`）保有時、レジェンダリー交換に必要な浄化星片コストが **-5個** 優遇される。

| 商品ID (`ec:item_*`) | アイテム名 | レアリティ | 必要コスト | 選択3B優遇コスト | 解放条件 |
|---|---|---|---|---|---|
| `ec:item_con_greater_hp_potion` | 上級回復薬 | 消耗品 | `210G` | 同左 | 初期 |
| `ec:item_con_resource_elixir` | エーテル霊薬 | 消耗品 | `95G` | 同左 | 初期 |
| `ec:item_con_starlight_tea` | 星見のハーブティー | 消耗品 | `190G` | 同左 | 初期 |
| `ec:item_con_revive_feather` | 不死鳥の星羽 | 消耗品 | `380G`（1日上限5個） | 同左 | 初期 |
| `ec:item_arm_vanguard_plate` | 暁光騎士の胸甲 | `EPIC` | 浄化星片×14（または `2,200G`） | 同左 | `ec:flag_sanctum_unlocked` |
| `ec:item_arm_ranger_jerkin` | 星見レンジャーの外套 | `EPIC` | 浄化星片×14（または `2,200G`） | 同左 | `ec:flag_sanctum_unlocked` |
| `ec:item_arm_sage_robe` | 星見賢者の法衣 | `EPIC` | 浄化星片×14（または `2,200G`） | 同左 | `ec:flag_sanctum_unlocked` |
| `ec:item_wpn_aether_vanguard_sword` | エーテル先鋒の剛剣 | `EPIC` | 浄化星片×18 | 同左 | `ec:flag_sanctum_unlocked` |
| `ec:item_wpn_starlight_bow` | 星光の狙撃弓 | `EPIC` | 浄化星片×18 | 同左 | `ec:flag_sanctum_unlocked` |
| `ec:item_wpn_stargazer_rod` | 天球儀の星杖 | `EPIC` | 浄化星片×18 | 同左 | `ec:flag_sanctum_unlocked` |
| `ec:item_acc_void_heart_ring` | 虚核封じの指輪 | `LEGENDARY` | 虚核のエッセンス×4 ＋ 浄化星片×25 | エッセンス×4 ＋ 浄化星片×20 | `ec:flag_void_warden_defeated` |
| `ec:item_wpn_void_cleaver` | 虚空断ちの聖剣「エクリプス」 | `LEGENDARY` | 虚核のエッセンス×5 ＋ 浄化星片×30 | エッセンス×5 ＋ 浄化星片×25 | `ec:flag_void_warden_defeated` |
| `ec:item_wpn_astral_piercer` | 星穿ちの霊弓「アルタイル」 | `LEGENDARY` | 虚核のエッセンス×5 ＋ 浄化星片×30 | エッセンス×5 ＋ 浄化星片×25 | `ec:flag_void_warden_defeated` |
| `ec:item_wpn_aether_chronicle_staff` | 蒼穹年代記の聖杖「アエリア」 | `LEGENDARY` | 虚核のエッセンス×5 ＋ 浄化星片×30 | エッセンス×5 ＋ 浄化星片×25 | `ec:flag_void_warden_defeated` |
