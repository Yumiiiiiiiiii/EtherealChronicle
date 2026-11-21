# 第2章 ストーリーバイブル — 「凍てつく蒼穹の星片」

> **メタ情報**
> - `chapter_id`: `ec:chapter_2`
> - タイトル: `第2章「凍てつく蒼穹の星片」`
> - 推奨レベル帯: `Lv16〜20`（現行 `BALANCE.md` 上限 `level_cap: 20` 内の第1章クリア後エンドゲーム進行帯）
> - 前提条件: 第1章完了フラグ `ec:flag_ch1_completed`

---

## 1. 第1章からの接続と物語のあらすじ

第1章の終盤、虚核の聖堂で敗れた〈虚ろの手〉幹部カインは、次なる標的が北の果て **〈蒼穹の氷嶺（Azure Frostpeak）〉** に眠る **〈第二の星片（蒼氷の核）〉** であることを告げて姿を消した。

第1章の最終選択（選択3）に応じ、ルミナ港からの出航経緯が変化する：
- **ギルド防衛ルート (`ec:flag_choice3_guild`)**: ギルド長ヴァルカンが編成した北方遠征隊の先鋒として、遠征隊長 **フレイヤ (`ec:npc_freya`)** に見送られ出航する。
- **賢者調査ルート (`ec:flag_choice3_observatory`)**: 賢者リラが解析した星図座標を携え、観測官 **オルフェ (`ec:npc_orphe`)** と合流するため北へ飛ぶ。

蒼穹の氷嶺では、教団の氷嶺統括官 **ヴェスパー (`ec:npc_vesper`)** が古代のエーテル冷却機構を暴走させ、浮島全体を絶対零度へ封じ込めようとしていた。プレイヤーは前哨基地の仲間や、教団の非道さに疑問を抱いて離反した元書記官 **セラフィナ (`ec:npc_seraphina`)** の協力を得て、暴走した星片導管を巡る決断（選択4）、氷窟の古代番兵 **フロスト・コロッサス (`ec:boss_frost_colossus`)** の撃破を経て、氷城最奥の **氷核の執行者クライオ・アルコン (`ec:boss_cryo_archon`)** へと挑む。

決戦後、ヴェスパーは氷城の裂け目から退却しつつ、次なる舞台 **〈第三の星片〉と灼熱の〈紅蓮火口〉** への伏線を残す。プレイヤーは純化した〈蒼氷の核〉の処遇を決断し（選択5）、第2章が幕を閉じる。

---

## 2. ストーリービートとクエスト構成（メイン7本＋サブ4本）

| Quest ID | 種別 | クエスト名 | 舞台エリア | 主な内容・分岐 |
|---|---|---|---|---|
| `ec:quest_ch2_m01_northward` | メイン1 | 蒼穹の氷嶺への出航 | `ec:area_lumina_port` → `ec:area_frostpeak_outpost` | フレイヤから受注。選択3に応じた出航物資と会話分岐 |
| `ec:quest_ch2_m02_frost_beacons` | メイン2 | 霧氷の尾根の航路復旧 | `ec:area_glacier_ridge` | 霧氷オオカミ討伐と毛皮回収で飛空艇中継灯を復旧 |
| `ec:quest_ch2_m03_frozen_conduit` | メイン3 | 凍てつく星片導管 | `ec:area_glacier_ridge` | **重大選択4**：導管を低温安定化 (`stabilize`) vs 過熱解凍 (`overload`) |
| `ec:quest_ch2_m04_cavern_infiltration` | メイン4 | 蒼晶の氷窟への潜入 | `ec:area_crystalline_cavern` | 離反書記官セラフィナと協力し氷結術師から暗号表を奪取 |
| `ec:quest_ch2_m05_frost_colossus` | メイン5 | 凍鉄の番兵を砕け | `ec:area_crystalline_cavern` | 中ボス **フロスト・コロッサス** 撃破＆凍結封鍵を回収（職別エピックオフハンド報酬） |
| `ec:quest_ch2_m06_citadel_gate` | メイン6 | 氷城の門と統括官ヴェスパー | `ec:area_glacial_citadel` | 氷城の近衛騎士を突破し、ヴェスパーと対峙して氷城ダンジョンを開放 |
| `ec:quest_ch2_m07_cryo_archon` | メイン7 | 氷核の執行者 | `ec:area_glacial_citadel` | ラスボス **クライオ・アルコン** 討伐＋**重大選択5**＋第3章伏線 |
| `ec:quest_ch2_s01_frost_pelt` | サブ1 | 極寒を凌ぐ毛皮と氷晶 | `ec:area_frostpeak_outpost` | 鍛冶師ボルカの依頼。防寒兜 `ec:item_arm_frostpeak_helm` を獲得 |
| `ec:quest_ch2_s02_ice_resonance` | サブ2 (4A限定) | 氷晶の共鳴観測 | `ec:area_glacier_ridge` | 選択4A専用。オルフェと氷晶の安定共鳴を記録 |
| `ec:quest_ch2_s03_defector_ledger` | サブ3 (4B限定) | 教団の氷嶺収支台帳 | `ec:area_crystalline_cavern` | 選択4B専用。セラフィナと教団の補給網を断つ |
| `ec:quest_ch2_s04_citadel_echo` | サブ4 (反復) | 氷城の残響鎮圧 | `ec:area_glacial_citadel` | 第2章クリア後の24時間反復依頼。氷核のエッセンス＆蒼氷の欠片を獲得 |

---

## 3. 重大な選択肢（選択4・選択5）

### 選択4：凍てつく星片導管の処遇（`ec:quest_ch2_m03_frozen_conduit`）
- **4A (`ec:flag_ch2_choice4_stabilize`) — 低温のまま安定制御する（オルフェ案）**:
  - 報酬アクセサリ：`ec:item_acc_glacier_ward_pendant`（氷嶺守護のペンダント：HP/DEF/属性耐性）
  - 氷嶺装備店 (`ec:shop_frostpeak_outfitter`) のゴールド価格 **常時10%割引**
  - 専用サブクエスト `ec:quest_ch2_s02_ice_resonance` 解放
- **4B (`ec:flag_ch2_choice4_overload`) — 過熱解凍して教団の防衛網を断つ（セラフィナ案）**:
  - 報酬アクセサリ：`ec:item_acc_frostbreaker_signet`（破氷の戦印：ATK/会心/スキル威力）
  - 専用サブクエスト `ec:quest_ch2_s03_defector_ledger` 解放

### 選択5：〈第二の星片（蒼氷の核）〉の帰属（`ec:quest_ch2_m07_cryo_archon`）
- **5A (`ec:flag_ch2_choice5_anchor`) — 氷嶺の重力錨として現地再封印する（フレイヤ案）**:
  - 即時報酬：`ec:item_mat_glacial_core_essence`×2＋`ec:item_con_frostguard_brew`×3
  - 第3章で「蒼穹艦隊防衛ルート」へ接続
- **5B (`ec:flag_ch2_choice5_resonance`) — 共鳴羅針盤として〈第三の星片〉解析に託す（オルフェ案）**:
  - 即時報酬：`ec:item_mat_glacial_shard`×10＋`ec:item_con_aether_crystal_tonic`×3
  - `ec:shop_frostpeak_outfitter` のレジェンダリー交換で `ec:item_mat_glacial_shard` 必要数を **-5個優遇**
  - 第3章で「星片共鳴探索ルート」へ接続

---

## 4. 新規NPC一覧（5名）

| NPC ID | 名前 / 肩書 | 配置エリア | 口調・役割 |
|---|---|---|---|
| `ec:npc_freya` | **フレイヤ** / 北方遠征隊長 | `ec:area_lumina_port` `(18, 65, -12)` | 規律正しく頼もしい飛空艇先鋒隊長。第2章開始・最終報告・反復依頼窓口 |
| `ec:npc_orphe` | **オルフェ** / 氷嶺観測官 | `ec:area_frostpeak_outpost` `(474, 65, -6)` | リラ門下の真面目な若手学者。尾根調査・選択4A・氷城ダンジョン入場窓口 |
| `ec:npc_borka` | **ボルカ** / 氷嶺の鍛冶師 | `ec:area_frostpeak_outpost` `(486, 65, -6)` | 豪快な北国の鍛冶師。ショップ `ec:shop_frostpeak_outfitter` と防寒装備依頼 |
| `ec:npc_seraphina` | **セラフィナ** / 元教団書記官 | `ec:area_glacier_ridge` `(552, 65, -18)` | 冷静で皮肉屋な離反者。氷窟潜入・中ボス討伐・選択4B専用依頼を担当 |
| `ec:npc_vesper` | **ヴェスパー** / 氷嶺統括官 | `ec:area_glacial_citadel` `(746, 65, 0)` | 氷のように冷徹な〈虚ろの手〉幹部。氷城前の対峙・ダンジョン開放・第3章伏線 |

---

## 5. エリア配置（`ec_world` 第1章東端から96ブロック間隔で延長）

- `ec:area_frostpeak_outpost`（蒼氷の前哨基地 / `safe: true`）：`x: 440..520, z: -40..40`（中心 `480, 65, 0`）
- `ec:area_glacier_ridge`（霧氷の尾根 / `safe: false`）：`x: 536..616, z: -40..40`（中心 `576, 65, 0`）
- `ec:area_crystalline_cavern`（蒼晶の氷窟 / `safe: false`）：`x: 632..712, z: -40..40`（中心 `672, 65, 0`）
- `ec:area_glacial_citadel`（蒼穹の氷城 / `safe: false`）：`x: 728..808, z: -40..40`（中心 `768, 65, 0`）
- 協力ダンジョンID：`ec:dungeon_glacial_citadel`
