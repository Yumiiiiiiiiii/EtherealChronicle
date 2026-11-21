# 第1章 クエストバイブル (`ec:quest_*`)

> **概要（YAML変換用データ設計）**
> - **総クエスト数**: **20本**（メインクエスト8 / サイド・討伐・収集・探索・会話・分岐・反復クエスト12）
> - **クエスト種別カバー**: `main`, `side`, `hunt`, `collect`, `explore`, `talk`（および `repeatable` 属性）
> - **経験値・ゴールド設計**: 全メイン＋主要サイド完了で自然かつ無理なく `Lv1 → Lv18〜20` に到達するよう `docs/content/balance_proposal.md` と厳密同期。

---

## クエスト全体インデックス

| # | Quest ID | クエスト名 | 種別 | 推奨Lv | エリア | 依頼NPC |
|---|---|---|---|---|---|---|
| 1 | `ec:quest_m01_awakening` | 蒼穹の港町 | `main` (`talk`) | Lv1 | `ec:area_lumina_port` | `ec:npc_celia` |
| 2 | `ec:quest_m02_first_hunt` | 森の異変と初陣 | `main` (`hunt`) | Lv1–3 | `ec:area_ashwood_forest` | `ec:npc_vulcan` |
| 3 | `ec:quest_m03_ashwood_core` | 暴走する星片 | `main` (`explore`/`hunt`) | Lv5–8 | `ec:area_ashwood_forest` | `ec:npc_elena` |
| 4 | `ec:quest_m04_road_to_mine` | 嘆きの廃坑へ | `main` (`explore`/`talk`) | Lv8–10 | `ec:area_weeping_mine` | `ec:npc_vulcan` |
| 5 | `ec:quest_m05_mine_betrayal` | 黒き密書と裏切り | `main` (`collect`/`talk`) | Lv10–12 | `ec:area_weeping_mine` | `ec:npc_tomas` |
| 6 | `ec:quest_m06_iron_grinder` | 暴走する採掘巨兵 | `main` (`hunt`) | Lv11–13 | `ec:area_weeping_mine` | `ec:npc_tomas` |
| 7 | `ec:quest_m07_stargazer_oath` | 星見の丘の賢者 | `main` (`explore`/`collect`) | Lv13–16 | `ec:area_stargazer_hill` | `ec:npc_lyra` |
| 8 | `ec:quest_m08_void_warden` | 虚核の守護者 | `main` (`hunt`/`talk`) | Lv15–20 | `ec:area_void_sanctum` | `ec:npc_lyra` |
| 9 | `ec:quest_s01_lumina_tour` | 港町ルミナ案内 | `talk` (`explore`) | Lv1–2 | `ec:area_lumina_port` | `ec:npc_celia` |
| 10 | `ec:quest_s02_slime_gel` | ミラの薬草調合 | `collect` | Lv2–4 | `ec:area_ashwood_forest` | `ec:npc_mira` |
| 11 | `ec:quest_s03_wolf_pack` | 街道を脅かす牙 | `hunt` | Lv3–5 | `ec:area_ashwood_forest` | `ec:npc_vulcan` |
| 12 | `ec:quest_s04_spider_silk` | ピップの極秘仕入れ | `collect` | Lv5–7 | `ec:area_ashwood_forest` | `ec:npc_pip` |
| 13 | `ec:quest_s05_lost_scout` | 霧深い林の斥候 | `explore` | Lv6–8 | `ec:area_ashwood_forest` | `ec:npc_elena` |
| 14 | `ec:quest_s06_garudo_forge` | 頑固鍛冶のこだわり | `collect` | Lv9–11 | `ec:area_weeping_mine` | `ec:npc_garudo` |
| 15 | `ec:quest_s07_golem_bounty` | 坑道掃除の懸賞金 | `hunt` | Lv10–13 | `ec:area_weeping_mine` | `ec:npc_tomas` |
| 16 | `ec:quest_s08_daren_atonement` | 鉱夫長の贖罪（分岐A） | `side` (`explore`/`hunt`) | Lv12–14 | `ec:area_weeping_mine` | `ec:npc_daren` |
| 17 | `ec:quest_s09_tomas_audit` | 補給隊の緊急監査（分岐B） | `side` (`collect`/`talk`) | Lv12–14 | `ec:area_weeping_mine` | `ec:npc_tomas` |
| 18 | `ec:quest_s10_star_constellation` | 古の星碑巡り | `explore` | Lv13–15 | `ec:area_stargazer_hill` | `ec:npc_lyra` |
| 19 | `ec:quest_s11_cult_purge` | 黒き影の掃討（反復可） | `hunt` (`repeatable`) | Lv14–16 | `ec:area_stargazer_hill` | `ec:npc_vulcan` |
| 20 | `ec:quest_s12_sanctum_trial` | 聖堂の残響（協力反復） | `side` (`hunt`/`repeatable`) | Lv17–20 | `ec:area_void_sanctum` | `ec:npc_lyra` |

---

## 1. メインクエスト詳細（全8本）

### 1. `ec:quest_m01_awakening` — 蒼穹の港町
- **種別 / 推奨Lv / エリア**: `main` (`talk`) / `Lv1`（最低Lv1） / `ec:area_lumina_port`
- **依頼 / 報告**: `ec:npc_celia` → `ec:npc_vulcan`
- **前提条件**: なし（初回ログイン時自動案内）
- **目標**:
  1. `talk`: `ec:npc_celia` と話し冒険者登録を行う（`ec:item_q_guild_badge` 取得）
  2. `talk`: `ec:npc_vulcan` と面会し、職業（`ec:class_warrior` / `ec:class_archer` / `ec:class_mage`）を選択する（フラグ `ec:flag_class_selected`）
  3. `talk`: `ec:npc_garudo` から選択クラスに応じた初期武器を受け取り、`ec:npc_vulcan` へ戻る
- **報酬**: XP `90`, Gold `60G`, クラス初期武器（`ec:item_wpn_rusty_broadsword` / `ec:item_wpn_hunter_shortbow` / `ec:item_wpn_apprentice_wand` のいずれか）＋`ec:item_con_minor_hp_potion`×3＋`ec:item_con_lumina_stew`×1、フラグ `ec:flag_ch1_started`
- **台詞**:
  - **受注（セリア）**: 「ようこそルミナ港へ！あなたが飛空艇で着いた新人さんですね。まずはこの冒険者証をお渡しします。奥のヴァルカンギルド長に挨拶して、あなたの戦う道――職業を決めてきてくださいね！」
  - **進行中（セリア）**: 「ヴァルカンギルド長は本部の奥にいらっしゃいますよ。職業が決まったら鍛冶屋のガルドさんから初期装備を受け取ってくださいね。」
  - **完了（ヴァルカン）**: 「ハッハッハ！いい面構えじゃねぇか、相棒！よし、スキル切替の調子も確認できたら――さっそく初仕事だ。町の外の鐘が聞こえただろ？森の様子がおかしいんだ。」

---

### 2. `ec:quest_m02_first_hunt` — 森の異変と初陣
- **種別 / 推奨Lv / エリア**: `main` (`hunt`) / `Lv1–3`（最低Lv1） / `ec:area_ashwood_forest`
- **依頼 / 報告**: `ec:npc_vulcan` → `ec:npc_elena`
- **前提条件**: `ec:quest_m01_awakening` 完了
- **目標**:
  1. `hunt`: `ec:enemy_aether_slime` ×5 討伐
  2. `hunt`: `ec:enemy_thorn_wolf` ×3 討伐
  3. `talk`: アッシュウッドの森・前哨キャンプの `ec:npc_elena` に報告
- **報酬**: XP `240`, Gold `120G`, クラス初期オフハンド（`ec:item_off_oak_buckler` / `ec:item_off_leather_quiver` / `ec:item_off_worn_grimoire`）＋`ec:item_con_minor_hp_potion`×2
- **台詞**:
  - **受注（ヴァルカン）**: 「北門を出てすぐの『アッシュウッドの森』で魔物が急に凶暴化したらしい。レンジャー隊長のエレナが現地で踏ん張ってる。まずは街道のスライムと狼を片付けて、エレナと合流してくれ！」
  - **進行中（ヴァルカン）**: 「焦るなよ。ホットバーのスキルをうまく回して、危なくなったら回復薬を使うんだぜ！」
  - **完了（エレナ）**: 「……ギルドからの増援か。思ったより動きが整っているな。だが気を引き締めろ。魔物が狂ったのは偶然じゃない――森の最奥で、星片が暴走している。」

---

### 3. `ec:quest_m03_ashwood_core` — 暴走する星片
- **種別 / 推奨Lv / エリア**: `main` (`explore`/`hunt`) / `Lv5–8`（最低Lv4） / `ec:area_ashwood_forest`
- **依頼 / 報告**: `ec:npc_elena` → `ec:npc_vulcan`
- **前提条件**: `ec:quest_m02_first_hunt` 完了
- **目標**:
  1. `explore`: アッシュウッドの森最奥「星片の古樹広場」へ到達
  2. `hunt`: エリート `ec:enemy_corrupted_treant` ×1 討伐
  3. `collect`: `ec:item_q_forest_starshard` を調べる → **【重大選択1：浄化(`ec:flag_choice1_purify`) vs 封印抽出(`ec:flag_choice1_extract`)】**
  4. `talk`: 黒き教団の楔について `ec:npc_vulcan` へ報告
- **報酬**: XP `1,150`, Gold `320G`, 選択報酬（選択1A: `ec:item_acc_forest_ward_charm` / 選択1B: `ec:item_acc_starshard_crest`）＋`ec:item_mat_starshard_fragment`×2、フラグ `ec:flag_ashwood_cleared`, `ec:flag_mine_unlocked`
- **台詞**:
  - **受注（エレナ）**: 「古樹広場の巨木が星片を呑み込み、怪物へと変貌した。奴を鎮め、暴走する星片を止めてくれ。……止めた後の処遇は、現場に立つお前の判断に委ねよう。森へ還すか、ギルドへ持ち帰るか、よく考えろ。」
  - **進行中（エレナ）**: 「星蝕トレントは炎と虚空に弱く、足元の根の隆起が危険だ。赤い予兆を見たら即座に離れろ。」
  - **完了（ヴァルカン）**: 「……ほう、星片に黒い楔が打ち込まれていたか。間違いねぇ、噂の『虚ろの手』の連中だ！お前の判断は尊重するぜ。次は北東の『嘆きの廃坑』だ――奴らの本拠に近い反応が出てる！」

---

### 4. `ec:quest_m04_road_to_mine` — 嘆きの廃坑へ
- **種別 / 推奨Lv / エリア**: `main` (`explore`/`talk`) / `Lv8–10`（最低Lv7） / `ec:area_weeping_mine`
- **依頼 / 報告**: `ec:npc_vulcan` → `ec:npc_tomas`
- **前提条件**: `ec:quest_m03_ashwood_core` 完了
- **目標**:
  1. `explore`: `ec:area_weeping_mine` 入口野営地へ到達
  2. `talk`: `ec:npc_tomas` と `ec:npc_daren` から現地の状況を聴取
  3. `hunt`: 第1水平坑道で `ec:enemy_mine_kobold` ×6 と `ec:enemy_crystal_bat` ×4 を討伐し、教団の篝火を調査
  4. `talk`: `ec:npc_tomas` に調査報告
- **報酬**: XP `1,750`, Gold `420G`, `ec:item_con_medium_hp_potion`×3（さらに `ec:flag_choice1_extract` 保有時はギルド追加支援として `ec:item_con_medium_hp_potion`×3 追加）
- **台詞**:
  - **受注（ヴァルカン）**: 「嘆きの廃坑は十年前に閉鎖された古鉱山だが、最近やけに魔力反応が濃い。入口野営地の補給官トマスと鉱夫長ダレンに合流し、坑内の異変を調べてきてくれ。」
  - **進行中（トマス）**: 「記録によれば第1坑道の奥に何者かが侵入した痕跡があります。コボルドや帯電コウモリに注意し、無理な深追いは禁物です。」
  - **完了（トマス）**: 「やはり教団〈虚ろの手〉が坑道に入り込んでいましたか……。しかし解せません。第3縦坑の厳重な封印を、連中はどうやって抜けたのでしょう？」

---

### 5. `ec:quest_m05_mine_betrayal` — 黒き密書と裏切り
- **種別 / 推奨Lv / エリア**: `main` (`collect`/`talk`) / `Lv10–12`（最低Lv9） / `ec:area_weeping_mine`
- **依頼 / 報告**: `ec:npc_tomas` → `ec:npc_tomas`
- **前提条件**: `ec:quest_m04_road_to_mine` 完了
- **目標**:
  1. `hunt`: 教団の採掘拠点で `ec:enemy_cult_acolyte` ×5 討伐
  2. `collect`: 信徒から `ec:item_q_cult_letter` ×1 を入手
  3. `talk`: `ec:npc_daren` を問い詰める → **【重大選択2：許しと共闘(`ec:flag_choice2_forgive`) vs 厳正な告発(`ec:flag_choice2_report`)】**
  4. `talk`: `ec:npc_tomas` へ最終報告し `ec:item_q_mine_seal_key` を受領
- **報酬**: XP `2,300`, Gold `520G`（選択2B時は告発報奨金 `+350G`＋`ec:item_con_miner_ration`×2 / 選択2A時は中ボス弱点解析バフ＋`ec:quest_s08_daren_atonement` 解放）、`ec:item_q_mine_seal_key`
- **台詞**:
  - **受注（トマス）**: 「教団の見習い信徒たちが第2坑道奥で採掘拠点を築いているようです。連中の指揮系統を探るため、信徒を排除し連絡文書を押収してください。」
  - **進行中（ダレン）**: 「……お、おい冒険者。あんまり奥まで首を突っ込むんじゃねぇよ。あそこはもう、俺たちの手に負える場所じゃ……」
  - **完了（トマス）**: 「……なるほど、あなたの決断は記録しました。いずれにせよ、第3縦坑の奥で巨大な魔力炉が起動しています。封印鍵を持って、直ちに制圧へ向かってください！」

---

### 6. `ec:quest_m06_iron_grinder` — 暴走する採掘巨兵
- **種別 / 推奨Lv / エリア**: `main` (`hunt`) / `Lv11–13`（最低Lv10） / `ec:area_weeping_mine`
- **依頼 / 報告**: `ec:npc_tomas` → `ec:npc_vulcan`（または現地伝令経由で `ec:npc_lyra` 紹介状取得）
- **前提条件**: `ec:quest_m05_mine_betrayal` 完了
- **目標**:
  1. `explore`: `ec:item_q_mine_seal_key` を使用し第3縦坑・機械炉へ入場
  2. `hunt`: 中ボス `ec:boss_iron_grinder` ×1 撃破
  3. `talk`: 廃坑上層への昇降路を抜け、`ec:npc_tomas` へ報告（星見の丘への道解放）
- **報酬**: XP `3,200`, Gold `720G`, `ec:item_mat_starshard_fragment`×4, `ec:item_con_revive_feather`×1、フラグ `ec:flag_iron_grinder_defeated`, `ec:flag_stargazer_unlocked`
- **台詞**:
  - **受注（トマス）**: 「教団は古代の採掘巨兵『アイアン・グラインダー』に星片を無理やり注ぎ込み、我々の足止めを図っているようです。強敵です、必要なら仲間とパーティーを組んで挑んでください！」
  - **進行中（トマス）**: 「突進を壁に誘導すれば機体が過熱して隙ができるという報告があります。回復薬と食料は十分に携行してください。」
  - **完了（トマス）**: 「見事です！巨兵を沈黙させるとは……。しかし教団幹部『カイン』はすでに星片を持ち出し、北の高台『星見の丘』へ向かった模様です。丘の天文台跡にいる賢者リラ殿と合流してください！」

---

### 7. `ec:quest_m07_stargazer_oath` — 星見の丘の賢者
- **種別 / 推奨Lv / エリア**: `main` (`explore`/`collect`) / `Lv13–16`（最低Lv12） / `ec:area_stargazer_hill`
- **依頼 / 報告**: `ec:npc_lyra` → `ec:npc_lyra`
- **前提条件**: `ec:quest_m06_iron_grinder` 完了
- **目標**:
  1. `talk`: 天文台跡の `ec:npc_lyra` と面会（カインとの対峙イベント発生）
  2. `hunt`: `ec:enemy_cult_zealot` ×6、`ec:enemy_astral_stalker` ×4 討伐
  3. `collect`: 丘の祭壇から奪われた `ec:item_q_star_astrolabe` を取り戻し、聖堂封門を起動
  4. `talk`: `ec:npc_lyra` へ報告
- **報酬**: XP `4,600`, Gold `950G`, クラス別エピック胸鎧選択（`ec:item_arm_vanguard_plate` / `ec:item_arm_ranger_jerkin` / `ec:item_arm_sage_robe`）、`ec:item_con_starlight_tea`×2、フラグ `ec:flag_sanctum_unlocked`
- **台詞**:
  - **受注（リラ）**: 「ようやく来たか、ルミナの新星よ。黒き衣の男・カインは千年前に封じた『虚核の聖堂』の門をこじ開けようとしておる。奪われた古のアストロラーベを取り戻し、狂信者どもを退けておくれ。」
  - **進行中（リラ）**: 「虚空の裂け目に潜むストーカーは背後へ瞬時に回り込む。紫の閃光を見たら迷わず横へ跳ぶのじゃよ。」
  - **完了（リラ）**: 「よくぞやり遂げた！アストロラーベは再び星光を宿した。さあ、聖堂の扉は開かれたぞ。――中では古の守護者が虚無に染まり待っておる。信頼できる仲間と力を合わせ、挑むのじゃ。」

---

### 8. `ec:quest_m08_void_warden` — 虚核の守護者（第1章クライマックス＆エンディング）
- **種別 / 推奨Lv / エリア**: `main` (`hunt`/`talk`) / `Lv15–20`（最低Lv14） / `ec:area_void_sanctum`
- **依頼 / 報告**: `ec:npc_lyra` → `ec:npc_vulcan`
- **前提条件**: `ec:quest_m07_stargazer_oath` 完了
- **目標**:
  1. `explore`: 協力ダンジョン `ec:area_void_sanctum` へ入場（1〜4人）
  2. `hunt`: ラスボス `ec:boss_void_warden` ×1 撃破（フラグ `ec:flag_void_warden_defeated`）
  3. `talk`: 撃破後に出現する `ec:npc_kain` の予言を見届け、`ec:item_q_void_remnant` を回収
  4. `talk`: ルミナ港へ帰還し `ec:npc_vulcan` と `ec:npc_lyra` の前で **【重大選択3：ギルド封印(`ec:flag_choice3_guild`) vs 賢者研究(`ec:flag_choice3_observatory`)】** を決定
- **報酬**: XP `6,200`, Gold `1,600G`, `ec:item_mat_void_core_essence`×1, 選択3ボーナス（選択3A: 浄化星片×5＋`ec:item_con_revive_feather`×2 / 選択3B: 浄化星片×8）、称号授与、フラグ `ec:flag_ch1_completed`
- **台詞**:
  - **受注（リラ）**: 「カインは聖堂最奥で〈虚核の守護者ヴォイド・ウォーデン〉を覚醒させてしまった。このままではルミナ港ごと浮島が虚無に呑まれる。どうか、あの哀れな守護者を解き放ってやっておくれ。」
  - **進行中（リラ）**: 「守護者がプリズムを掲げたら即座に破壊するのじゃ。黄金の輪が現れたら仲間と集まり、蒼い光柱が立ったら迷わず飛び込め！」
  - **完了（ヴァルカン）**: 「やりやがったな、相棒……！ルミナ港はあんたのおかげで救われた。だがカインの奴が言い残した『第二の星片』――北の〈蒼穹の氷嶺〉が気にかかるな。しっかり休んで装備を磨いておけ。俺たちの戦いは、まだ始まったばかりだぜ！」

---

## 2. サイド・討伐・収集・探索・会話・分岐・反復クエスト詳細（全12本）

### 9. `ec:quest_s01_lumina_tour` — 港町ルミナ案内
- **種別 / 推奨Lv / エリア**: `talk` (`explore`) / `Lv1–2` / `ec:area_lumina_port`
- **依頼 / 報告**: `ec:npc_celia` → `ec:npc_celia`
- **前提条件**: `ec:quest_m01_awakening` 完了
- **目標**: `ec:npc_garudo`（鍛冶場）、`ec:npc_mira`（市場通り）、`ec:npc_pip`（桟橋広場）の3名と会話する
- **報酬**: XP `110`, Gold `70G`, `ec:item_arm_lumina_helm`×1, `ec:item_con_return_scroll`×2
- **台詞**:
  - **受注（セリア）**: 「冒険に出る前に、ルミナ港の頼もしいお店の方々へ挨拶しておきましょう！鍛冶屋のガルドさん、薬草商のミラさん、桟橋の行商人ピップさんを回ってきてくださいね。」
  - **完了（セリア）**: 「お疲れさまでした！みなさん癖は強いですけど、腕は確かですから。この見習いの兜と帰還の巻物をお渡ししますね！」

---

### 10. `ec:quest_s02_slime_gel` — ミラの薬草調合
- **種別 / 推奨Lv / エリア**: `collect` / `Lv2–4` / `ec:area_ashwood_forest`
- **依頼 / 報告**: `ec:npc_mira` → `ec:npc_mira`
- **前提条件**: `ec:quest_m01_awakening` 完了
- **目標**: `ec:item_mat_aether_gel` ×6 を収集して納品
- **報酬**: XP `220`, Gold `130G`, `ec:item_con_minor_hp_potion`×4, `ec:item_con_antidote_herb`×2
- **台詞**:
  - **受注（ミラ）**: 「あらあら、ちょうどいいところに！最近ケガ人さんが増えてしまって、回復薬のつなぎに使う『エーテルゼリー』が足りないんですの。森のスライムさんから6つほど分けてもらってきませんか？」
  - **完了（ミラ）**: 「まあ、こんなにプルプルで新鮮なゼリー！ありがとうございますぅ。お礼に出来立ての回復薬と解毒の香草をどうぞ！」

---

### 11. `ec:quest_s03_wolf_pack` — 街道を脅かす牙
- **種別 / 推奨Lv / エリア**: `hunt` / `Lv3–5` / `ec:area_ashwood_forest`
- **依頼 / 報告**: `ec:npc_vulcan` → `ec:npc_vulcan`
- **前提条件**: `ec:quest_m02_first_hunt` 完了
- **目標**: `ec:enemy_thorn_wolf` ×6、`ec:enemy_moss_goblin` ×4 討伐
- **報酬**: XP `480`, Gold `240G`, クラス別アンコモン武器選択（`ec:item_wpn_ashwood_blade` / `ec:item_wpn_ash_longbow` / `ec:item_wpn_lumina_staff`）
- **台詞**:
  - **受注（ヴァルカン）**: 「森の街道で商人の荷車がイバラオオカミとコケゴブリンに襲われた。これ以上被害が広がる前に、連中の数を間引いてきてくれ！」
  - **完了（ヴァルカン）**: 「よし、街道の安全は確保できたな！ガルドに頼んで作らせた上等な武器だ、あんたの職に合うやつを持っていきな！」

---

### 12. `ec:quest_s04_spider_silk` — ピップの極秘仕入れ
- **種別 / 推奨Lv / エリア**: `collect` / `Lv5–7` / `ec:area_ashwood_forest`
- **依頼 / 報告**: `ec:npc_pip` → `ec:npc_pip`
- **前提条件**: `Lv4` 以上、`ec:quest_m02_first_hunt` 完了
- **目標**: `ec:item_mat_venom_gland` ×5 を収集して納品
- **報酬**: XP `620`, Gold `360G`, `ec:item_con_lumina_stew`×2, `ec:item_con_return_scroll`×2
- **台詞**:
  - **受注（ピップ）**: 「へへっ、旦那！ここだけの話なんですけどね、胞子グモの毒腺が隣の浮島の錬金術師に高値で売れるんですよ〜。5つ集めてくれたら、相場の倍は弾みますぜ！」
  - **完了（ピップ）**: 「おおっ、傷ひとつない極上品じゃないですか！まいどあり〜！またうまい話があったら真っ先に声をかけますよ、旦那！」

---

### 13. `ec:quest_s05_lost_scout` — 霧深い林の斥候
- **種別 / 推奨Lv / エリア**: `explore` / `Lv6–8` / `ec:area_ashwood_forest`
- **依頼 / 報告**: `ec:npc_elena` → `ec:npc_elena`
- **前提条件**: `ec:quest_m02_first_hunt` 完了
- **目標**:
  1. `explore`: アッシュウッドの森の「東の見張り台」「湿原の古橋」「北西の監視塔」の3地点を巡回調査
  2. `hunt`: 途中で襲来する `ec:enemy_spore_spider` ×3 を排除し、斥候の観測日誌を回収
- **報酬**: XP `880`, Gold `300G`, `ec:item_arm_ashwood_boots`×1, `ec:item_con_antidote_herb`×3
- **台詞**:
  - **受注（エレナ）**: 「3つの見張り台へ派遣した斥候のうち、定時連絡が途絶えている箇所がある。各拠点を巡回し、残された観測記録を回収してほしい。」
  - **完了（エレナ）**: 「……斥候は無事に撤退していたか、安堵した。それより日誌にある『黒いローブの人影』――ギルド長にも早急に共有せねばな。この軽足具を受け取れ、森を駆ける助けになる。」

---

### 14. `ec:quest_s06_garudo_forge` — 頑固鍛冶のこだわり
- **種別 / 推奨Lv / エリア**: `collect` / `Lv9–11` / `ec:area_weeping_mine`
- **依頼 / 報告**: `ec:npc_garudo` → `ec:npc_garudo`
- **前提条件**: `ec:flag_mine_unlocked` 保有
- **目標**: `ec:item_mat_iron_scrap` ×8、`ec:item_mat_spark_crystal` ×4 を収集して納品
- **報酬**: XP `1,550`, Gold `480G`, `ec:item_mat_starshard_fragment`×2、**特典**: 鍛冶屋ガルド (`ec:shop_lumina_blacksmith`) に Lv11レア武器3種が入荷
- **台詞**:
  - **受注（ガルド）**: 「おい小僧、嘆きの廃坑へ入るって聞いたぜ。あそこの古鉄片と帯電結晶は極上の鍛冶素材だ。古鉄片8つと結晶4つ持ってきやがれ。見違える業物を打ってやらぁ！」
  - **完了（ガルド）**: 「ふん、思ったより筋がいいじゃねぇか。よし、これで俺の炉も本気が出せる。これからは廃坑向けの業物も店頭に並べておくから、しっかり稼いで買いに来い！」

---

### 15. `ec:quest_s07_golem_bounty` — 坑道掃除の懸賞金
- **種別 / 推奨Lv / エリア**: `hunt` / `Lv10–13` / `ec:area_weeping_mine`
- **依頼 / 報告**: `ec:npc_tomas` → `ec:npc_tomas`
- **前提条件**: `ec:quest_m04_road_to_mine` 完了
- **目標**: `ec:enemy_obsidian_golem` ×4、`ec:enemy_void_miner` ×6 討伐
- **報酬**: XP `2,150`, Gold `580G`, `ec:item_arm_miner_greaves`×1, `ec:item_con_miner_ration`×2
- **台詞**:
  - **受注（トマス）**: 「補給路の確保が急務です。第2坑道の黒曜ゴーレムと虚ろの坑夫にギルド懸賞金が掛けられました。規定数を討伐していただければ、相応の報奨と脚甲を支給します。」
  - **完了（トマス）**: 「討伐確認完了。これで輸送隊の損失率が4割は下がる計算です。規定通り報奨金と補強脚甲をお渡しします。」

---

### 16. `ec:quest_s08_daren_atonement` — 鉱夫長の贖罪（選択2A限定分岐）
- **種別 / 推奨Lv / エリア**: `side` (`explore`/`hunt`) / `Lv12–14` / `ec:area_weeping_mine`（隠し縦坑）
- **依頼 / 報告**: `ec:npc_daren` → `ec:npc_daren`
- **前提条件**: `ec:flag_choice2_forgive` 保有、`ec:quest_m06_iron_grinder` 完了
- **目標**:
  1. `explore`: ダレンが教えた「隠し縦坑」の最奥へ到達
  2. `hunt`: 教団に囚われて変質した `ec:enemy_void_miner` ×5 と `ec:enemy_cult_acolyte` ×3 を鎮め、鉱夫の形見を回収
- **報酬**: XP `2,800`, Gold `620G`, `ec:item_mat_starshard_fragment`×3, `ec:item_con_revive_feather`×1
- **台詞**:
  - **受注（ダレン）**: 「あんたが俺を信じてくれたおかげで、もう一度つるはしを握る気になれたんだ……。隠し縦坑の奥に、助けられなかった仲間の形見が残ってる。俺の罪を終わらせるために、力を貸してくれねぇか。」
  - **完了（ダレン）**: 「……ありがとうよ。あいつらも、ようやく眠れるだろう。この浄化星片は俺が若い頃に掘り当てた宝だ。あんたが持って行ってくれ――星見の丘で、あの教団の連中をぶっ飛ばしてきな！」

---

### 17. `ec:quest_s09_tomas_audit` — 補給隊の緊急監査（選択2B限定分岐）
- **種別 / 推奨Lv / エリア**: `side` (`collect`/`talk`) / `Lv12–14` / `ec:area_weeping_mine`
- **依頼 / 報告**: `ec:npc_tomas` → `ec:npc_vulcan`
- **前提条件**: `ec:flag_choice2_report` 保有、`ec:quest_m06_iron_grinder` 完了
- **目標**:
  1. `explore`/`collect`: 坑内の教団拠点3箇所から横流しされた「ギルド封印資材箱」を回収
  2. `hunt`: 護衛の `ec:enemy_cult_acolyte` ×4 を排除
  3. `talk`: ルミナ港の `ec:npc_vulcan` へ監査報告書を提出
- **報酬**: XP `2,800`, Gold `880G`, `ec:item_mat_starshard_fragment`×2, `ec:item_con_greater_hp_potion`×2
- **台詞**:
  - **受注（トマス）**: 「ダレンの告発により、横流しされていたギルド物資の帳簿が発覚しました。教団に利用される前に、坑内3箇所の資材箱を回収し、ギルド長へ報告書を届けてください。」
  - **完了（ヴァルカン）**: 「厳しい決断だったが、規律を守ったあんたの仕事は見事だ。回収した物資はルミナ防衛に回すぜ。こいつはギルドからの特別査定報酬だ、受け取れ！」

---

### 18. `ec:quest_s10_star_constellation` — 古の星碑巡り
- **種別 / 推奨Lv / エリア**: `explore` / `Lv13–15` / `ec:area_stargazer_hill`
- **依頼 / 報告**: `ec:npc_lyra` → `ec:npc_lyra`
- **前提条件**: `ec:flag_stargazer_unlocked` 保有
- **目標**:
  1. `explore`: 星見の丘にある4基の古代星碑（東・西・南・北）を調べ、千年前の〈エーテル核〉崩壊の碑文を記録
  2. `hunt`: 星碑を守る `ec:enemy_star_wisp` ×5 を鎮静化
- **報酬**: XP `3,400`, Gold `720G`, `ec:item_mat_starshard_fragment`×3, `ec:item_con_starlight_tea`×2
- **台詞**:
  - **受注（リラ）**: 「この丘には千年前、エーテル核の崩壊を目撃した賢者たちが刻んだ4つの星碑がある。教団に砕かれる前に、すべての碑文を写し取ってきてもらえぬかの？」
  - **完了（リラ）**: 「……やはりそうじゃったか。核は自然に砕けたのではない、『虚無を呼ぶ者』に裂かれたと記されておる。そなたのおかげで千年越しの謎に光が差したぞ。」

---

### 19. `ec:quest_s11_cult_purge` — 黒き影の掃討（反復可能・デイリー枠）
- **種別 / 推奨Lv / エリア**: `hunt` (`repeatable`, CD 20時間) / `Lv14–16` / `ec:area_stargazer_hill`
- **依頼 / 報告**: `ec:npc_vulcan` → `ec:npc_vulcan`
- **前提条件**: `ec:flag_stargazer_unlocked` 保有
- **目標**: `ec:enemy_cult_zealot` ×6、`ec:enemy_astral_stalker` ×3 討伐
- **報酬**: XP `2,400`, Gold `540G`, `ec:item_mat_starshard_fragment`×3
- **台詞**:
  - **受注（ヴァルカン）**: 「星見の丘じゃ、まだ〈虚ろの手〉の連中がしぶとく動き回ってる。毎日少しずつでも奴らの勢力を削いでおく必要があるんだ。腕試しついでに掃討を頼めるか？」
  - **完了（ヴァルカン）**: 「上出来だぜ、相棒！こいつが今日の討伐報酬と浄化星片だ。また明日も頼むぞ！」

---

### 20. `ec:quest_s12_sanctum_trial` — 聖堂の残響（協力ダンジョン反復）
- **種別 / 推奨Lv / エリア**: `side` (`hunt`, `repeatable`, CD 12時間) / `Lv17–20` / `ec:area_void_sanctum`
- **依頼 / 報告**: `ec:npc_lyra` → `ec:npc_lyra`
- **前提条件**: `ec:quest_m08_void_warden` 完了（または `ec:flag_sanctum_unlocked`＋Lv16以上）
- **目標**: `ec:enemy_void_sentinel` ×6、`ec:boss_void_warden` ×1 撃破
- **報酬**: XP `3,800`, Gold `780G`, `ec:item_mat_starshard_fragment`×5, `ec:item_mat_void_core_essence`×1
- **台詞**:
  - **受注（リラ）**: 「聖堂の奥では今も虚核の残響が周期的に実体化しておる。放っておけば再び星片が暴走しかねん。仲間を募り、守護者の残響を鎮めてきておくれ。」
  - **完了（リラ）**: 「見事な連携じゃったな。持ち帰ったエッセンスと浄化星片は、いずれ第2章の厳しい旅路を支える至高の武具へと変わるじゃろう。」
