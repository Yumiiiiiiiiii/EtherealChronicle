# 第1章 アイテム・装備バイブル (`ec:item_*`)

> **概要（YAML変換用データ設計）**
> - **装備品**: 全 **30点**（戦士専用8 / 弓使い専用8 / 魔法使い専用8 / 全職共通防具・アクセサリ6）
> - **消耗品**: 全 **10点**（回復薬・リソース霊薬・状態異常対策・料理バフ・帰還/蘇生アイテム）
> - **素材・クエストアイテム**: 全 **16点**（討伐ドロップ素材10 / クエストキーアイテム6）
> - **合計**: **56アイテム**
> - レアリティ係数・アフィックス数・ステータス予算は `docs/content/balance_proposal.md` と完全一致する。

---

## 1. レアリティ・ステータス予算ルール

| レアリティ | 日本語表記 | カラーコード（MiniMessage） | 予算倍率 | ランダムアフィックス数 | 特殊効果（パッシブ/発動） |
|---|---|---|---|---|---|
| `COMMON` | コモン | `<white>` | `1.00x` | 0 | なし |
| `UNCOMMON` | アンコモン | `<green>` | `1.20x` | 1 | なし |
| `RARE` | レア | `<aqua>` | `1.45x` | 2 | あり（中位効果） |
| `EPIC` | エピック | `<light_purple>` | `1.75x` | 3 | あり（上位効果） |
| `LEGENDARY` | レジェンダリー | `<gold>` | `2.15x` | 4 | あり（固有シグネチャ効果） |

- **武器威力基準**: `base_wpn(L) = round(10 + 4.5 * (L - 1))` にレアリティ倍率を乗算し、クラス補正（戦士1.00 / 弓使い1.02 / 魔法使い0.96＋SKILL_POWER加算）を適用。
- **装備スロット**: `MAINHAND`（主武器）, `OFFHAND`（盾・矢筒・魔導書）, `HEAD`, `CHEST`, `LEGS`, `BOOTS`, `ACCESSORY`。
- **売却価格原則**: 店舗購入価格の **30%**（端数切捨て）。ショップでの転売ループ（アービトラージ）を完全防止。

---

## 2. 装備品一覧（全30点）

### 2-1. 戦士専用装備 (`ec:class_warrior` / 8点)

#### 1. `ec:item_wpn_rusty_broadsword` — 鉄の訓練剣
- **スロット / 職業 / 要求Lv / レアリティ**: `MAINHAND` / 戦士 / `Lv1` / `COMMON` (`1.00x`, Affix 0)
- **MCベースアイテム**: `IRON_SWORD`
- **ステータス**: 武器威力 `10`, `ATK +3`, `HP +10`
- **特殊効果**: なし
- **価格（買 / 売）**: `60G` / `18G`
- **入手経路**: クエスト `ec:quest_m01_awakening` 初期支給、店 `ec:shop_lumina_blacksmith`
- **フレーバーテキスト**: *「ルミナ港のギルドが新人に貸与する無骨な直剣。刃こぼれは多いが、重心は素直で扱いやすい。」*

#### 2. `ec:item_wpn_ashwood_blade` — 灰木の大剣
- **スロット / 職業 / 要求Lv / レアリティ**: `MAINHAND` / 戦士 / `Lv5` / `UNCOMMON` (`1.20x`, Affix 1)
- **MCベースアイテム**: `IRON_SWORD`
- **ステータス**: 武器威力 `34`, `ATK +8`, `HP +25`, `DEF +4`
- **特殊効果**: なし
- **価格（買 / 売）**: `320G` / `96G`
- **入手経路**: 店 `ec:shop_lumina_blacksmith`、クエスト `ec:quest_s03_wolf_pack` 報酬選択、ドロップ `ec:enemy_corrupted_treant`
- **フレーバーテキスト**: *「硬質な灰木の芯材を柄に組み込み、衝撃吸収を高めた大剣。森の魔物の外皮を断ち割るために鍛えられた。」*

#### 3. `ec:item_wpn_minebreaker_greatsword` — 破岩の鉱山大剣
- **スロット / 職業 / 要求Lv / レアリティ**: `MAINHAND` / 戦士 / `Lv11` / `RARE` (`1.45x`, Affix 2)
- **MCベースアイテム**: `DIAMOND_SWORD`
- **ステータス**: 武器威力 `80`, `ATK +18`, `HP +55`, `DEF +10`, `CRIT% +3%`
- **特殊効果 — 【破岩撃】**: 通常攻撃3回ごとに次のスキルダメージ `+18%`、さらに相手の `DEF` を4秒間 `-10%` 低下させる（CD 8秒）。
- **価格（買 / 売）**: `1,250G` / `375G`
- **入手経路**: 店 `ec:shop_mine_quartermaster`（`ec:quest_s06_garudo_forge` 完了でガルドにも入荷）、中ボス `ec:boss_iron_grinder` ドロップ
- **フレーバーテキスト**: *「黒曜石の岩盤すら砕く鉱山用の重合金を転用した大剣。振り下ろすたび、鈍い地鳴りが響く。」*

#### 4. `ec:item_wpn_aether_vanguard_sword` — エーテル先鋒の剛剣
- **スロット / 職業 / 要求Lv / レアリティ**: `MAINHAND` / 戦士 / `Lv16` / `EPIC` (`1.75x`, Affix 3)
- **MCベースアイテム**: `DIAMOND_SWORD`
- **ステータス**: 武器威力 `136`, `ATK +30`, `HP +105`, `DEF +18`, `CRIT% +5%`, `SKILL_POWER +18`
- **特殊効果 — 【蒼穹の怒号】**: 怒りゲージが80以上の間、`ATK +12%` と受けるダメージ `-8%` を得る。
- **価格（買 / 売）**: 交換品（売 `980G`）
- **入手経路**: 店 `ec:shop_stargazer_relic`（浄化星片×18）、ドロップ `ec:enemy_void_sentinel`（低確率）、`ec:boss_void_warden`
- **フレーバーテキスト**: *「蒼き星片の粉末を鋼に練り込んだ先鋒隊の象徴。戦意が昂ぶるほど刀身が澄んだ光を帯びる。」*

#### 5. `ec:item_wpn_void_cleaver` — 虚空断ちの聖剣「エクリプス」
- **スロット / 職業 / 要求Lv / レアリティ**: `MAINHAND` / 戦士 / `Lv18` / `LEGENDARY` (`2.15x`, Affix 4)
- **MCベースアイテム**: `NETHERITE_SWORD`
- **ステータス**: 武器威力 `186`, `ATK +42`, `HP +160`, `DEF +26`, `CRIT% +7%`, `CRIT_DMG +18%`, `SKILL_POWER +28`
- **特殊効果 — 【日蝕の断罪】**: スキル命中時に「星光印」を付与（最大3スタック）。3スタック到達時、対象と周囲3.5mへ `ATK × 1.8` の `AETHER` 属性衝撃波を放ち、自身のHPを最大HPの `6%` 回復する（CD 10秒）。
- **価格（買 / 売）**: 非売（売 `2,400G`）
- **入手経路**: ラスボス `ec:boss_void_warden` レアドロップ、または店 `ec:shop_stargazer_relic`（虚核のエッセンス×5＋浄化星片×30）
- **フレーバーテキスト**: *「虚無の守護者を打ち倒した証として再鋳造された聖剣。振るわれた軌跡には、千年前の蒼空が一瞬だけ蘇る。」*

#### 6. `ec:item_off_oak_buckler` — 樫の丸盾
- **スロット / 職業 / 要求Lv / レアリティ**: `OFFHAND` / 戦士 / `Lv3` / `COMMON` (`1.00x`, Affix 0)
- **MCベースアイテム**: `SHIELD`
- **ステータス**: `HP +25`, `DEF +8`, `ELEMENT_RES +2%`
- **特殊効果**: なし
- **価格（買 / 売）**: `120G` / `36G`
- **入手経路**: 店 `ec:shop_lumina_blacksmith`、クエスト `ec:quest_m02_first_hunt`
- **フレーバーテキスト**: *「鉄輪で縁取った軽量な丸盾。狼の突進を受け止めるには十分だ。」*

#### 7. `ec:item_off_obsidian_aegis` — 黒曜の鉄壁盾
- **スロット / 職業 / 要求Lv / レアリティ**: `OFFHAND` / 戦士 / `Lv12` / `RARE` (`1.45x`, Affix 2)
- **MCベースアイテム**: `SHIELD`
- **ステータス**: `HP +90`, `DEF +24`, `ELEMENT_RES +8%`
- **特殊効果 — 【反響の護り】**: ガードまたは防御系スキル発動時、周囲の敵のヘイト（敵視）を `+35%` 引き付け、3秒間 `DEF +15`（CD 12秒）。
- **価格（買 / 売）**: `1,100G` / `330G`
- **入手経路**: 中ボス `ec:boss_iron_grinder` ドロップ、店 `ec:shop_mine_quartermaster`
- **フレーバーテキスト**: *「黒曜ゴーレムの装甲板を鍛え直した大盾。協力戦で仲間の盾となる戦士の要。」*

#### 8. `ec:item_arm_vanguard_plate` — 暁光騎士の胸甲
- **スロット / 職業 / 要求Lv / レアリティ**: `CHEST` / 戦士 / `Lv14` / `EPIC` (`1.75x`, Affix 3)
- **MCベースアイテム**: `DIAMOND_CHESTPLATE`
- **ステータス**: `HP +145`, `DEF +34`, `ATK +10`, `ELEMENT_RES +10%`
- **特殊効果 — 【不屈の鼓動】**: HPが35%未満になった時、最大HPの `18%` 分の吸収シールドを6秒間展開する（CD 45秒）。
- **価格（買 / 売）**: `2,200G` / `660G`
- **入手経路**: クエスト `ec:quest_m07_stargazer_oath` 選択報酬、店 `ec:shop_stargazer_relic`（浄化星片×14）
- **フレーバーテキスト**: *「ルミナ港の黎明を象った白銀の重鎧。どれほど深い闇の中でも、前衛は決して退かない。」*

---

### 2-2. 弓使い専用装備 (`ec:class_archer` / 8点)

#### 9. `ec:item_wpn_hunter_shortbow` — 狩人の短弓
- **スロット / 職業 / 要求Lv / レアリティ**: `MAINHAND` / 弓使い / `Lv1` / `COMMON` (`1.00x`, Affix 0)
- **MCベースアイテム**: `BOW`
- **ステータス**: 武器威力 `10`, `ATK +4`, `CRIT% +2%`
- **特殊効果**: なし
- **価格（買 / 売）**: `60G` / `18G`
- **入手経路**: クエスト `ec:quest_m01_awakening` 初期支給、店 `ec:shop_lumina_blacksmith`
- **フレーバーテキスト**: *「しなやかな枝を削り出した軽弓。引き絞りが軽く、動きながらでも素早く射抜ける。」*

#### 10. `ec:item_wpn_ash_longbow` — 灰木の長弓
- **スロット / 職業 / 要求Lv / レアリティ**: `MAINHAND` / 弓使い / `Lv5` / `UNCOMMON` (`1.20x`, Affix 1)
- **MCベースアイテム**: `BOW`
- **ステータス**: 武器威力 `35`, `ATK +10`, `CRIT% +4%`, `CRIT_DMG +6%`
- **特殊効果**: なし
- **価格（買 / 売）**: `320G` / `96G`
- **入手経路**: 店 `ec:shop_lumina_blacksmith`、クエスト `ec:quest_s03_wolf_pack` 報酬選択、ドロップ `ec:enemy_spore_spider`
- **フレーバーテキスト**: *「アッシュウッドの若木を乾燥させて仕上げた長弓。森林レンジャー隊の制式装備と同型。」*

#### 11. `ec:item_wpn_crystal_recurve` — 結晶弦のリカーブボウ
- **スロット / 職業 / 要求Lv / レアリティ**: `MAINHAND` / 弓使い / `Lv11` / `RARE` (`1.45x`, Affix 2)
- **MCベースアイテム**: `BOW`
- **ステータス**: 武器威力 `82`, `ATK +22`, `CRIT% +7%`, `CRIT_DMG +12%`
- **特殊効果 — 【帯電貫通】**: 会心発生時、矢が `LIGHTNING` 属性の追加ダメージ（`ATK × 0.45`）を与え、自身の「集中」を `10` 回復（CD 4秒）。
- **価格（買 / 売）**: `1,250G` / `375G`
- **入手経路**: 店 `ec:shop_mine_quartermaster`、ドロップ `ec:enemy_crystal_bat`（レア）、中ボス `ec:boss_iron_grinder`
- **フレーバーテキスト**: *「帯電結晶の繊維を撚り込んだ反り弓。放たれた矢は青い火花を尾のように引く。」*

#### 12. `ec:item_wpn_starlight_bow` — 星光の狙撃弓
- **スロット / 職業 / 要求Lv / レアリティ**: `MAINHAND` / 弓使い / `Lv16` / `EPIC` (`1.75x`, Affix 3)
- **MCベースアイテム**: `BOW`
- **ステータス**: 武器威力 `139`, `ATK +34`, `CRIT% +10%`, `CRIT_DMG +20%`, `SKILL_POWER +16`
- **特殊効果 — 【遠距離照準】**: 対象との距離が10m以上の時、与ダメージ `+14%`・会心率 `+5%`。
- **価格（買 / 売）**: 交換品（売 `980G`）
- **入手経路**: 店 `ec:shop_stargazer_relic`（浄化星片×18）、ドロップ `ec:enemy_astral_stalker`（低確率）、`ec:boss_void_warden`
- **フレーバーテキスト**: *「星見の丘の照準鏡を組み込んだ優美な弓。夜空の星を射抜くかのような精度を誇る。」*

#### 13. `ec:item_wpn_astral_piercer` — 星穿ちの霊弓「アルタイル」
- **スロット / 職業 / 要求Lv / レアリティ**: `MAINHAND` / 弓使い / `Lv18` / `LEGENDARY` (`2.15x`, Affix 4)
- **MCベースアイテム**: `BOW`
- **ステータス**: 武器威力 `190`, `ATK +48`, `CRIT% +13%`, `CRIT_DMG +30%`, `SKILL_POWER +26`, `Resource +20`
- **特殊効果 — 【流星の連射】**: 会心攻撃が3回命中するたび、次の射撃またはスキルが星光の追尾矢を2本同時発射する（各 `ATK × 0.85`, `AETHER` 属性、CD 7秒）。
- **価格（買 / 売）**: 非売（売 `2,400G`）
- **入手経路**: ラスボス `ec:boss_void_warden` レアドロップ、または店 `ec:shop_stargazer_relic`（虚核のエッセンス×5＋浄化星片×30）
- **フレーバーテキスト**: *「天空の鷲座を冠する霊弓。弦を引けば、砕けた星片の光そのものが矢となって宙を駆ける。」*

#### 14. `ec:item_off_leather_quiver` — 革の矢筒
- **スロット / 職業 / 要求Lv / レアリティ**: `OFFHAND` / 弓使い / `Lv3` / `COMMON` (`1.00x`, Affix 0)
- **MCベースアイテム**: `BUNDLE`
- **ステータス**: `ATK +4`, `CRIT% +2%`, `Resource +10`
- **特殊効果**: なし
- **価格（買 / 売）**: `120G` / `36G`
- **入手経路**: 店 `ec:shop_lumina_blacksmith`、クエスト `ec:quest_m02_first_hunt`
- **フレーバーテキスト**: *「腰に斜め掛けする丈夫な革の矢筒。素早く次の矢を番えられるよう口が斜めに裁断されている。」*

#### 15. `ec:item_off_windrunner_quiver` — 疾風の星羽矢筒
- **スロット / 職業 / 要求Lv / レアリティ**: `OFFHAND` / 弓使い / `Lv12` / `RARE` (`1.45x`, Affix 2)
- **MCベースアイテム**: `BUNDLE`
- **ステータス**: `ATK +14`, `CRIT% +5%`, `CRIT_DMG +10%`, `Resource +20`
- **特殊効果 — 【追い風の歩法】**: 回避・移動スキル使用後、4秒間移動速度 `+15%` かつ次の射撃が確定会心（CD 14秒）。
- **価格（買 / 売）**: `1,100G` / `330G`
- **入手経路**: 中ボス `ec:boss_iron_grinder` ドロップ、店 `ec:shop_mine_quartermaster`
- **フレーバーテキスト**: *「エーテルの風を纏った羽根で編まれた矢筒。矢を放つたび、足取りまで軽くなる。」*

#### 16. `ec:item_arm_ranger_jerkin` — 星見レンジャーの外套
- **スロット / 職業 / 要求Lv / レアリティ**: `CHEST` / 弓使い / `Lv14` / `EPIC` (`1.75x`, Affix 3)
- **MCベースアイテム**: `LEATHER_CHESTPLATE`（濃緑染色）
- **ステータス**: `HP +95`, `DEF +22`, `ATK +18`, `CRIT% +6%`, `ELEMENT_RES +6%`
- **特殊効果 — 【狩人の見切り】**: 攻撃を受けてから3秒間、回避行動の消費「集中」が `-50%` になり、反撃ダメージ `+10%`（CD 15秒）。
- **価格（買 / 売）**: `2,200G` / `660G`
- **入手経路**: クエスト `ec:quest_m07_stargazer_oath` 選択報酬、店 `ec:shop_stargazer_relic`（浄化星片×14）
- **フレーバーテキスト**: *「エレナ直伝の仕立てに星見の布を合わせた軽装外套。防寒と隠密性を兼ね備える。」*

---

### 2-3. 魔法使い専用装備 (`ec:class_mage` / 8点)

#### 17. `ec:item_wpn_apprentice_wand` — 見習いの樫杖
- **スロット / 職業 / 要求Lv / レアリティ**: `MAINHAND` / 魔法使い / `Lv1` / `COMMON` (`1.00x`, Affix 0)
- **MCベースアイテム**: `STICK`（または `BLAZE_ROD` 基礎モデル）
- **ステータス**: 武器威力 `10`, `ATK +4`, `SKILL_POWER +10`, `Resource +15`
- **特殊効果**: なし
- **価格（買 / 売）**: `60G` / `18G`
- **入手経路**: クエスト `ec:quest_m01_awakening` 初期支給、店 `ec:shop_lumina_blacksmith`
- **フレーバーテキスト**: *「先端に小さなエーテル石をはめ込んだ入門用の杖。魔力の暴発を抑える安全構造になっている。」*

#### 18. `ec:item_wpn_lumina_staff` — ルミナ水鏡の杖
- **スロット / 職業 / 要求Lv / レアリティ**: `MAINHAND` / 魔法使い / `Lv5` / `UNCOMMON` (`1.20x`, Affix 1)
- **MCベースアイテム**: `BLAZE_ROD`
- **ステータス**: 武器威力 `33`, `ATK +10`, `SKILL_POWER +22`, `Resource +30`
- **特殊効果**: なし
- **価格（買 / 売）**: `320G` / `96G`
- **入手経路**: 店 `ec:shop_lumina_blacksmith`、クエスト `ec:quest_s03_wolf_pack` 報酬選択、ドロップ `ec:enemy_corrupted_treant`
- **フレーバーテキスト**: *「ルミナ港の澄んだ湧水結晶を冠した杖。氷とエーテルの詠唱を滑らかに導く。」*

#### 19. `ec:item_wpn_quartz_scepter` — 雷晶の魔導錫
- **スロット / 職業 / 要求Lv / レアリティ**: `MAINHAND` / 魔法使い / `Lv11` / `RARE` (`1.45x`, Affix 2)
- **MCベースアイテム**: `BREEZE_ROD`
- **ステータス**: 武器威力 `77`, `ATK +22`, `SKILL_POWER +42`, `CRIT% +4%`, `Resource +50`
- **特殊効果 — 【連鎖放電】**: 属性スキルが敵に命中した時、半径4m以内の別ターゲット最大2体へ `ATK × 0.5` の `LIGHTNING` 連鎖ダメージを与える（CD 6秒）。
- **価格（買 / 売）**: `1,250G` / `375G`
- **入手経路**: 店 `ec:shop_mine_quartermaster`、ドロップ `ec:enemy_cult_acolyte`、中ボス `ec:boss_iron_grinder`
- **フレーバーテキスト**: *「嘆きの廃坑から採掘された高純度の帯電水晶を削り出した錫杖。唱える前に雷鳴が走る。」*

#### 20. `ec:item_wpn_stargazer_rod` — 天球儀の星杖
- **スロット / 職業 / 要求Lv / レアリティ**: `MAINHAND` / 魔法使い / `Lv16` / `EPIC` (`1.75x`, Affix 3)
- **MCベースアイテム**: `END_ROD`
- **ステータス**: 武器威力 `131`, `ATK +34`, `SKILL_POWER +68`, `CRIT% +6%`, `Resource +75`
- **特殊効果 — 【星辰の循環】**: スキル消費マナ `-12%`。範囲魔法で2体以上に同時命中すると、全スキルのクールダウンを `1.0秒` 短縮（CD 6秒）。
- **価格（買 / 売）**: 交換品（売 `980G`）
- **入手経路**: 店 `ec:shop_stargazer_relic`（浄化星片×18）、ドロップ `ec:enemy_star_wisp`（低確率）、`ec:boss_void_warden`
- **フレーバーテキスト**: *「賢者リラが設計したミニチュア天球儀が杖頭で静かに回転する。星々の軌道が魔力を増幅させる。」*

#### 21. `ec:item_wpn_aether_chronicle_staff` — 蒼穹年代記の聖杖「アエリア」
- **スロット / 職業 / 要求Lv / レアリティ**: `MAINHAND` / 魔法使い / `Lv18` / `LEGENDARY` (`2.15x`, Affix 4)
- **MCベースアイテム**: `TRIDENT`（魔法杖モデル置換）または `END_ROD`
- **ステータス**: 武器威力 `179`, `ATK +48`, `SKILL_POWER +98`, `CRIT% +9%`, `CRIT_DMG +20%`, `Resource +110`
- **特殊効果 — 【エーテルの響鳴】**: スキル発動時、周囲6mの味方全員の `SKILL_POWER +12%`・移動速度 `+10%`（5秒間）を付与し、敵には `AETHER` 共鳴爆発（`ATK × 1.35`）を起こす（CD 10秒）。
- **価格（買 / 売）**: 非売（売 `2,400G`）
- **入手経路**: ラスボス `ec:boss_void_warden` レアドロップ、または店 `ec:shop_stargazer_relic`（虚核のエッセンス×5＋浄化星片×30）
- **フレーバーテキスト**: *「砕け散る前の〈エーテル核〉の波長を再現した至高の聖杖。掲げられた瞬間、世界は歌い出す。」*

#### 22. `ec:item_off_worn_grimoire` — 古びた魔導書
- **スロット / 職業 / 要求Lv / レアリティ**: `OFFHAND` / 魔法使い / `Lv3` / `COMMON` (`1.00x`, Affix 0)
- **MCベースアイテム**: `BOOK`
- **ステータス**: `SKILL_POWER +12`, `Resource +25`, `ELEMENT_RES +2%`
- **特殊効果**: なし
- **価格（買 / 売）**: `120G` / `36G`
- **入手経路**: 店 `ec:shop_lumina_blacksmith`、クエスト `ec:quest_m02_first_hunt`
- **フレーバーテキスト**: *「基礎的な炎・氷・雷の詠唱式が書き込まれた手引書。余白には先人の走り書きが残る。」*

#### 23. `ec:item_off_astral_tome` — 星詠みの魔導書
- **スロット / 職業 / 要求Lv / レアリティ**: `OFFHAND` / 魔法使い / `Lv12` / `RARE` (`1.45x`, Affix 2)
- **MCベースアイテム**: `ENCHANTED_BOOK`
- **ステータス**: `ATK +12`, `SKILL_POWER +36`, `Resource +55`, `ELEMENT_RES +6%`
- **特殊効果 — 【マナ還流】**: マナが40%未満になった時、即座に最大マナの `25%` を回復し、5秒間詠唱威力 `+10%`（CD 30秒）。
- **価格（買 / 売）**: `1,100G` / `330G`
- **入手経路**: 中ボス `ec:boss_iron_grinder` ドロップ、店 `ec:shop_mine_quartermaster`
- **フレーバーテキスト**: *「銀のインクで星図が描かれた魔導書。頁をめくるたび、乾いた紙面から淡い光粉がこぼれ落ちる。」*

#### 24. `ec:item_arm_sage_robe` — 星見賢者の法衣
- **スロット / 職業 / 要求Lv / レアリティ**: `CHEST` / 魔法使い / `Lv14` / `EPIC` (`1.75x`, Affix 3)
- **MCベースアイテム**: `LEATHER_CHESTPLATE`（濃紺染色）
- **ステータス**: `HP +85`, `DEF +18`, `ATK +16`, `SKILL_POWER +48`, `Resource +60`, `ELEMENT_RES +12%`
- **特殊効果 — 【星幕の結界】**: 支援・回復・防御スキル使用時、自身と最もHPの低い味方1人に `SKILL_POWER × 0.8` のバリアを5秒間付与する（CD 18秒）。
- **価格（買 / 売）**: `2,200G` / `660G`
- **入手経路**: クエスト `ec:quest_m07_stargazer_oath` 選択報酬、店 `ec:shop_stargazer_relic`（浄化星片×14）
- **フレーバーテキスト**: *「天文台の織物師が星片の糸で織り上げた法衣。虚空の瘴気を柔らかに弾き返す。」*

---

### 2-4. 全職業共通防具・アクセサリ (`ALL` / 6点)

#### 25. `ec:item_arm_lumina_helm` — ルミナ見習いの兜
- **スロット / 職業 / 要求Lv / レアリティ**: `HEAD` / 全職 / `Lv2` / `COMMON` (`1.00x`, Affix 0)
- **MCベースアイテム**: `IRON_HELMET`
- **ステータス**: `HP +20`, `DEF +6`
- **特殊効果**: なし
- **価格（買 / 売）**: `90G` / `27G`
- **入手経路**: クエスト `ec:quest_s01_lumina_tour` 報酬、店 `ec:shop_lumina_blacksmith`
- **フレーバーテキスト**: *「ルミナ港ギルドの紋章が刻まれた軽兜。視界が広く、どの職種でも使いやすい。」*

#### 26. `ec:item_arm_ashwood_boots` — 灰木林の軽足具
- **スロット / 職業 / 要求Lv / レアリティ**: `BOOTS` / 全職 / `Lv6` / `UNCOMMON` (`1.20x`, Affix 1)
- **MCベースアイテム**: `CHAINMAIL_BOOTS`
- **ステータス**: `HP +35`, `DEF +10`, `CRIT% +2%`
- **特殊効果**: なし
- **価格（買 / 売）**: `340G` / `102G`
- **入手経路**: クエスト `ec:quest_s05_lost_scout` 報酬、店 `ec:shop_lumina_blacksmith`
- **フレーバーテキスト**: *「湿った苔や棘の上でも足を取られないよう加工されたブーツ。森の踏破に重宝する。」*

#### 27. `ec:item_arm_miner_greaves` — 坑夫の補強脚甲
- **スロット / 職業 / 要求Lv / レアリティ**: `LEGS` / 全職 / `Lv10` / `UNCOMMON` (`1.20x`, Affix 1)
- **MCベースアイテム**: `IRON_LEGGINGS`
- **ステータス**: `HP +65`, `DEF +18`, `ELEMENT_RES +4%`
- **特殊効果**: なし
- **価格（買 / 売）**: `680G` / `204G`
- **入手経路**: 店 `ec:shop_mine_quartermaster`、クエスト `ec:quest_s07_golem_bounty` 報酬
- **フレーバーテキスト**: *「落石から脚を守るために鉄板を当てた頑丈な脚甲。重みの分だけ踏ん張りが効く。」*

#### 28. `ec:item_acc_forest_ward_charm` — 森霊の護符（選択1A限定）
- **スロット / 職業 / 要求Lv / レアリティ**: `ACCESSORY` / 全職 / `Lv7` / `RARE` (`1.45x`, Affix 2)
- **MCベースアイテム**: `EMERALD`
- **ステータス**: `HP +65`, `DEF +12`, `ELEMENT_RES +8%`
- **特殊効果 — 【森の息吹】**: 非戦闘時の自然HP回復量 `+40%`。戦闘中も10秒ごとに最大HPの `2.5%` を回復する。
- **価格（買 / 売）**: 非売（売 `280G`）
- **入手経路**: クエスト `ec:quest_m03_ashwood_core` で選択1A（`ec:flag_choice1_purify`）を選択
- **フレーバーテキスト**: *「浄化され大地へ還った星片から零れ落ちた翠の結晶。森があなたを友と認めた証。」*

#### 29. `ec:item_acc_starshard_crest` — 星片の戦紋章（選択1B限定）
- **スロット / 職業 / 要求Lv / レアリティ**: `ACCESSORY` / 全職 / `Lv7` / `RARE` (`1.45x`, Affix 2)
- **MCベースアイテム**: `AMETHYST_SHARD`
- **ステータス**: `ATK +14`, `CRIT% +4%`, `SKILL_POWER +16`
- **特殊効果 — 【星片過負荷】**: 敵を撃破するたび、8秒間 `ATK +8%`（最大2スタック）。
- **価格（買 / 売）**: 非売（売 `280G`）
- **入手経路**: クエスト `ec:quest_m03_ashwood_core` で選択1B（`ec:flag_choice1_extract`）を選択
- **フレーバーテキスト**: *「魔導容器に封じた森の星片を加工した戦紋章。微かに脈打つ光が持ち主の闘志を煽る。」*

#### 30. `ec:item_acc_void_heart_ring` — 虚核封じの指輪
- **スロット / 職業 / 要求Lv / レアリティ**: `ACCESSORY` / 全職 / `Lv18` / `LEGENDARY` (`2.15x`, Affix 4)
- **MCベースアイテム**: `NETHER_STAR`（アクセサリ枠）
- **ステータス**: `HP +130`, `ATK +24`, `DEF +18`, `CRIT% +6%`, `SKILL_POWER +32`, `ELEMENT_RES +12%`
- **特殊効果 — 【虚無への抗い】**: `VOID` 属性ダメージを `-18%` 軽減。致死ダメージを受けた際、HP1で踏みとどまり2秒間無敵＋HP `25%` 回復（ダンジョン内CD 180秒）。
- **価格（買 / 売）**: 非売（売 `2,200G`）
- **入手経路**: ラスボス `ec:boss_void_warden` レアドロップ、または店 `ec:shop_stargazer_relic`（虚核のエッセンス×4＋浄化星片×25）
- **フレーバーテキスト**: *「虚核の聖堂を制した証。指にはめる者の魂を虚無の浸蝕から永遠に護る。」*

---

## 3. 消耗品一覧（全10点）

| ID | アイテム名 | 要求Lv | MCベース | 効果 | CD | 買値 / 売値 | 主な入手先 |
|---|---|---|---|---|---|---|---|
| `ec:item_con_minor_hp_potion` | 小さな回復薬 | Lv1 | `POTION`（赤） | HPを `110` 即時回復 | 8秒 | `25G` / `7G` | ミラ・トマス、序盤クエスト |
| `ec:item_con_medium_hp_potion` | 中級回復薬 | Lv7 | `POTION`（深紅） | HPを `280` 即時回復 | 8秒 | `85G` / `25G` | ミラ・トマス、廃坑ドロップ |
| `ec:item_con_greater_hp_potion` | 上級回復薬 | Lv14 | `POTION`（輝紅） | HPを `560` 即時回復 | 8秒 | `210G` / `63G` | ミラ（解放後）・リラ、聖堂ドロップ |
| `ec:item_con_resource_elixir` | エーテル霊薬 | Lv5 | `POTION`（青） | クラスリソース（怒り/集中/マナ）を `60%` 即時回復 | 20秒 | `95G` / `28G` | ミラ・リラ、クエスト |
| `ec:item_con_antidote_herb` | 解毒の香草 | Lv4 | `FERN` | 毒・胞子・虚空侵蝕のデバフを即時解除＋5秒間耐性+20% | 12秒 | `40G` / `12G` | ミラ・トマス |
| `ec:item_con_lumina_stew` | ルミナ港の海鮮シチュー | Lv1 | `RABBIT_STEW` | 15分間、最大HP `+40`・`ATK +6`（死亡時継続・料理枠1つ） | 3秒 | `55G` / `16G` | ピップ、導入クエスト |
| `ec:item_con_miner_ration` | 坑夫のスタミナ焼き肉 | Lv8 | `COOKED_BEEF` | 15分間、最大HP `+95`・`DEF +14`（料理枠1つ） | 3秒 | `130G` / `39G` | トマス・ピップ |
| `ec:item_con_starlight_tea` | 星見のハーブティー | Lv12 | `HONEY_BOTTLE` | 15分間、`CRIT% +5%`・`SKILL_POWER +24`・リソース自然回復+15%（料理枠1つ） | 3秒 | `190G` / `57G` | リラ・ミラ（星見解放後） |
| `ec:item_con_return_scroll` | ルミナ帰還の巻物 | Lv1 | `PAPER` | 3秒詠唱後（被弾中断）、ルミナ港の帰還石碑へテレポート | 60秒 | `35G` / `10G` | ピップ・トマス |
| `ec:item_con_revive_feather` | 不死鳥の星羽 | Lv12 | `FEATHER` | 協力ダンジョン内で戦闘不能の味方1名をHP50%で蘇生（ソロ時は自動復活枠として1回消費） | 120秒 | `380G` / `114G` | リラ・トマス、ボス報酬 |

---

## 4. 素材・クエストアイテム一覧（全16点）

### 4-1. 討伐ドロップ素材・交換トークン（10点）
| ID | アイテム名 | レアリティ | MCベース | 売値 | 主な入手先 | 用途 |
|---|---|---|---|---|---|---|
| `ec:item_mat_aether_gel` | エーテルゼリー | `COMMON` | `SLIME_BALL` | `8G` | `ec:enemy_aether_slime` | クエスト `ec:quest_s02_slime_gel`・換金 |
| `ec:item_mat_thorn_fang` | イバラの牙 | `COMMON` | `BONE` | `12G` | `ec:enemy_thorn_wolf` | 換金・装備素材フレーバー |
| `ec:item_mat_moss_hide` | 苔むした毛皮 | `COMMON` | `LEATHER` | `15G` | `ec:enemy_moss_goblin` | 換金 |
| `ec:item_mat_venom_gland` | 胞子の毒腺 | `UNCOMMON` | `SPIDER_EYE` | `24G` | `ec:enemy_spore_spider` | クエスト `ec:quest_s04_spider_silk`・換金 |
| `ec:item_mat_star_bark` | 星蝕の樹皮 | `RARE` | `STRIPPED_OAK_LOG` | `65G` | `ec:enemy_corrupted_treant` | 換金・エリート戦利品 |
| `ec:item_mat_iron_scrap` | 廃坑の古鉄片 | `COMMON` | `RAW_IRON` | `26G` | `ec:enemy_mine_kobold`, `ec:enemy_obsidian_golem` | クエスト `ec:quest_s06_garudo_forge`・換金 |
| `ec:item_mat_spark_crystal` | 帯電結晶 | `UNCOMMON` | `PRISMARINE_SHARD` | `42G` | `ec:enemy_crystal_bat` | クエスト `ec:quest_s06_garudo_forge`・換金 |
| `ec:item_mat_void_dust` | 虚空の塵 | `UNCOMMON` | `GUNPOWDER` | `48G` | `ec:enemy_void_miner`, `ec:enemy_cult_acolyte`, `ec:enemy_cult_zealot` | 換金・星見研究用 |
| `ec:item_mat_starshard_fragment` | **浄化星片**（交換通貨） | `RARE` | `AMETHYST_SHARD` | 売却不可 | 中ボス/ラスボス/エリート/反復クエスト | 店 `ec:shop_stargazer_relic` / `ec:shop_mine_quartermaster` での装備交換通貨 |
| `ec:item_mat_void_core_essence` | **虚核のエッセンス** | `EPIC` | `ECHO_SHARD` | 売却不可 | ラスボス `ec:boss_void_warden`（確定1個＋クエスト報酬） | レジェンダリー装備（エクリプス/アルタイル/アエリア/虚核封じの指輪）の確定交換素材（天井救済） |

### 4-2. クエストキーアイテム（6点・売却/破棄不可）
| ID | アイテム名 | MCベース | 関連クエスト | 説明 |
|---|---|---|---|---|
| `ec:item_q_guild_badge` | ルミナ見習い冒険者証 | `NAME_TAG` | `ec:quest_m01_awakening` | ルミナ港ギルド正規登録の銅章。 |
| `ec:item_q_forest_starshard` | 暴走した森の星片 | `AMETHYST_CLUSTER` | `ec:quest_m03_ashwood_core` | 不安定に脈打つ蒼紫の星片。選択1で浄化または封印される。 |
| `ec:item_q_cult_letter` | 〈虚ろの手〉の密書 | `WRITTEN_BOOK` | `ec:quest_m05_mine_betrayal` | 鉱夫長ダレンとの取引が記された黒封蝋の書簡。 |
| `ec:item_q_mine_seal_key` | 第3縦坑の封印鍵 | `TRIPWIRE_HOOK` | `ec:quest_m06_iron_grinder` | アイアン・グラインダーの眠る機械炉を開く重厚な鍵。 |
| `ec:item_q_star_astrolabe` | 古のアストロラーベ | `CLOCK` | `ec:quest_m07_stargazer_oath` | 星見の丘の封門を起動するための星図盤。 |
| `ec:item_q_void_remnant` | 虚核の残滓 | `HEART_OF_THE_SEA` | `ec:quest_m08_void_warden` | ヴォイド・ウォーデン撃破後に残った澄んだ結晶核。選択3で帰属を決定する。 |
