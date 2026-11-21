# 第1章 NPC会話ツリーバイブル (`ec:dlg_*`)

> **概要（YAML変換用データ設計）**
> - 全10名のNPCについて、条件分岐・選択肢・フラグ操作・アクション呼び出しを備えた会話ツリーを定義。
> - **共通ノード構造（Codexスキーマ変換目安）**:
>   - `dialogue_id`: `ec:dlg_<npc>_<scene>`
>   - `speaker`: `ec:npc_<name>`
>   - `condition`: 要求フラグ (`has_flag` / `not_flag`), クエスト状態 (`quest_state`), 職業 (`class`), レベル (`min_level`)
>   - `text`: 日本語セリフ（MiniMessage対応）
>   - `choices[]`: `label`（選択肢表示文）, `next_node`, `set_flags[]`, `actions[]`（`select_class`, `start_quest`, `complete_quest`, `open_shop`, `enter_dungeon`, `give_item`）

---

## 1. 受付嬢セリア (`ec:npc_celia`) — 導入・案内・日常会話

### `ec:dlg_celia_hub`（ルミナ港・ギルド本部入口）
- **`root`**（優先条件で自動分岐）
  - 条件: `not_flag: ec:flag_ch1_started` → `intro_welcome`
  - 条件: `has_flag: ec:flag_ch1_completed` → `post_ch1_greeting`
  - デフォルト → `normal_greeting`

- **`intro_welcome`**:
  > 「ようこそルミナ港へ！飛空艇の長旅、お疲れさまでした！あなたが噂の新人冒険者さんですね。まずはギルド登録を済ませましょう！」
  - 選択肢1: **「はい、冒険者登録をお願いします」** → `actions: [start_quest: ec:quest_m01_awakening, give_item: ec:item_q_guild_badge]` → `intro_registered`
  - 選択肢2: **「アエリアと〈星片〉について教えて」** → `lore_explain`
- **`lore_explain`**:
  > 「千年前、空を支えていた〈エーテル核〉が砕けて、その欠片――〈星片〉が各地に降ったんです。私たちの暮らしを支える大切な資源ですが、暴走すると魔物を狂わせてしまうんですよ。……最近は森の方で少し不穏な噂もあるんです。」
  - 選択肢1: **「なるほど。では冒険者登録を！」** → `actions: [start_quest: ec:quest_m01_awakening, give_item: ec:item_q_guild_badge]` → `intro_registered`
- **`intro_registered`**:
  > 「はい、登録完了です！『ルミナ見習い冒険者証』をお渡ししますね。この奥にいらっしゃるヴァルカンギルド長に挨拶して、あなたの戦う職業を決めてきてください！」
  - 終了
- **`normal_greeting`**:
  > 「お疲れさまです！何かお手伝いできることはありますか？街の案内が必要でしたら、いつでも声をかけてくださいね。」
  - 選択肢1（条件: `has_flag: ec:flag_ch1_started` & `quest_not_started: ec:quest_s01_lumina_tour`）: **「ルミナ港の施設を案内してほしい」** → `actions: [start_quest: ec:quest_s01_lumina_tour]`
  - 選択肢2: **「スキルの使い方をもう一度聞きたい」** → `tutorial_skill`
  - 選択肢3: **「また今度にする」** → 終了
- **`tutorial_skill`**:
  > 「ホットバー9番が『スキルスロット』ですよ！武器を持って **`F`キー**（持ち替え）でスキル1〜4を切り替え、**右クリック**で発動できます。しゃがみ＋右クリックでも切り替えられますよ。画面下のActionBarでHPとクールダウンを確認してくださいね！」
  - 選択肢1: **「ありがとう、よく分かった」** → 終了
- **`post_ch1_greeting`**:
  > 「『虚核の聖堂』を制した英雄さん！街のみんながあなたの活躍を噂していますよ。――そうそう、北の〈蒼穹の氷嶺〉方面から新しい定期便の連絡が来ているみたいです。次の旅支度も応援していますね！」
  - 選択肢1: **「ありがとう、セリア」** → 終了

---

## 2. ギルド長ヴァルカン (`ec:npc_vulcan`) — 職業選択・メイン指揮・選択3

### `ec:dlg_vulcan_class_select`（職業選択イベント）
- **条件**: `quest_active: ec:quest_m01_awakening` & `not_flag: ec:flag_class_selected`
- **`node_start`**:
  > 「ハッハッハ！お前がセリアの言ってた新人か。いい目をしてるじゃねぇか！よし、まずは戦場での立ち位置――お前の『職業』を決めようぜ。どの道を選ぶ？」
  - 選択肢1: **「戦士（Warrior）— 盾と大剣で前衛を守る」** → `confirm_warrior`
  - 選択肢2: **「弓使い（Archer）— 機動力と会心の一撃で射抜く」** → `confirm_archer`
  - 選択肢3: **「魔法使い（Mage）— 属性魔法と支援で戦況を制す」** → `confirm_mage`
- **`confirm_warrior`**:
  > 「戦士だな！高いHPと防御力、溜めた『怒り』を叩き込む近接の要だ。協力戦じゃ仲間の盾にもなれる。こいつで決まりか？」
  - 選択肢1: **「戦士になる！」** → `actions: [select_class: ec:class_warrior]` / `set_flags: [ec:flag_class_selected]` → `class_done`
  - 選択肢2: **「他の職業も見たい」** → `node_start`
- **`confirm_archer`**:
  > 「弓使いか！『集中』を研ぎ澄ませ、遠距離から急所を貫く狩人だ。回避と手数で強敵を翻弄できるぜ。こいつで決まりか？」
  - 選択肢1: **「弓使いになる！」** → `actions: [select_class: ec:class_archer]` / `set_flags: [ec:flag_class_selected]` → `class_done`
  - 選択肢2: **「他の職業も見たい」** → `node_start`
- **`confirm_mage`**:
  > 「魔法使いだな！『マナ』を操り、炎・氷・雷・星光の広範囲魔法や味方支援までこなす頭脳派だ。打たれ弱いが火力は随一だぜ。こいつで決まりか？」
  - 選択肢1: **「魔法使いになる！」** → `actions: [select_class: ec:class_mage]` / `set_flags: [ec:flag_class_selected]` → `class_done`
  - 選択肢2: **「他の職業も見たい」** → `node_start`
- **`class_done`**:
  > 「よし、決まったな！それじゃ鍛冶屋のガルドのところへ行って、初期武器を受け取ってきな。戻ってきたら初仕事を任せるぜ、相棒！」

### `ec:dlg_vulcan_ending_choice3`（第1章クライマックス・重大選択3）
- **条件**: `quest_active: ec:quest_m08_void_warden` & `has_flag: ec:flag_void_warden_defeated` & `not_flag: ec:flag_ch1_completed`
- **`node_remnant`**:
  > 「……こいつが聖堂の最奥に残った〈虚核の残滓〉か。カインの野郎が言い残した〈第二の星片〉の件も気がかりだが、まずはこの強力な結晶をどう扱うか決めねぇとな。」
  > *(隣のリラ)*「ヴァルカン殿、これは千年前の純粋な核の欠片じゃ。わしに託してくれれば、〈蒼穹の氷嶺〉に眠る第二の星片の正確な位置を割り出せる。」
  > *(ヴァルカン)*「気持ちは分かるがリラ殿、教団に奪われたら元も子もねぇ。ギルドの金庫に封印すれば、ルミナ港の防衛結界を盤石にできる。……相棒、命を懸けて持ち帰ったのはお前だ。お前が決めてくれ。」
  - 選択肢1: **「ルミナ港ギルドの金庫へ厳重に封印する（街の守りを固める）」** → `set_flags: [ec:flag_choice3_guild, ec:flag_ch1_completed]` → `ending_guild`
  - 選択肢2: **「星見の賢者リラに託す（第二の星片の所在を解析する）」** → `set_flags: [ec:flag_choice3_observatory, ec:flag_ch1_completed]` → `ending_observatory`
- **`ending_guild`**:
  > 「……よし、受け取ったぜ！このルミナ港は俺とお前の手で必ず守り抜いてみせる。『ルミナの盾』の称号を贈ろう。――次に北の氷嶺へ遠征する時も、先頭はあんたに任せるぜ、相棒！」
  - `actions: [complete_quest: ec:quest_m08_void_warden]`
- **`ending_observatory`**:
  > *(リラ)*「感謝するぞ、新星よ。ほれ、さっそく星図が反応した……北の果て〈蒼穹の氷嶺〉、その氷冠の奥に蒼き脈動がある。『星の導き手』の称号と、わしの蔵の特別交換権を授けよう。」
  > *(ヴァルカン)*「ハッ、賢者殿の目がそこまで輝いちゃ敵わねぇな！道が分かったなら準備あるのみだ。いつでも出撃できるよう腕を磨いておけよ、相棒！」
  - `actions: [complete_quest: ec:quest_m08_void_warden]`

---

## 3. 森林レンジャー隊長エレナ (`ec:npc_elena`) — 重大選択1

### `ec:dlg_elena_choice1`（森の星片の処遇）
- **条件**: `quest_active: ec:quest_m03_ashwood_core`（星蝕トレント撃破後・古樹広場または前哨キャンプ）
- **`node_shard`**:
  > 「……星蝕トレントが倒れ、星片の暴走はひとまず収まった。だが、まだ脈動が不安定だ。根元に打ち込まれていた『黒い楔』……間違いなく何者かが意図的に暴走させたものだな。」
  > 「さて、この星片の処遇だが2つ道がある。私と共に森の大地へ還して浄化するか、ヴァルカン殿の指示通り魔導容器へ抽出してギルドへ持ち帰るか。……現場の判断を聞こう。」
  - 選択肢1: **「森の大地へ還して浄化する（自然の調和を守る）」** → `set_flags: [ec:flag_choice1_purify, ec:flag_ashwood_cleared, ec:flag_mine_unlocked]` → `after_purify`
  - 選択肢2: **「魔導容器に封印抽出してギルドへ渡す（教団への戦力にする）」** → `set_flags: [ec:flag_choice1_extract, ec:flag_ashwood_cleared, ec:flag_mine_unlocked]` → `after_extract`
- **`after_purify`**:
  > 「……悪くない判断だ。見ろ、灰木の葉に元の静けさが戻っていく。薬草商のミラにも伝えておこう、森の恵みが守られたとな。この翠の護符を預けよう――お前はもう、この森の友だ。」
  - `actions: [give_item: ec:item_acc_forest_ward_charm]`
- **`after_extract`**:
  > 「……合理的だが、少々危うい選択だな。まあいい、〈虚ろの手〉と戦う力が必要だという主張も理解はできる。鍛冶屋のガルドやヴァルカン殿は喜ぶだろう。抽出した余剰結晶で作った戦紋章だ、無駄にするなよ。」
  - `actions: [give_item: ec:item_acc_starshard_crest]`

---

## 4. 鉱夫長ダレン (`ec:npc_daren`) & 補給官トマス (`ec:npc_tomas`) — 重大選択2

### `ec:dlg_daren_choice2`（密書発覚・問い詰め）
- **条件**: `quest_active: ec:quest_m05_mine_betrayal` & プレイヤーが `ec:item_q_cult_letter` 所持
- **`node_confront`**:
  > 「……っ！その黒封蝋の手紙……あんた、坑道の奥から持ってきちまったのか……。ああ、認めるよ。第3縦坑の封印鍵を〈虚ろの手〉に渡したのは、この俺だ！」
  > 「だが聞いてくれ！先週の落盤で行方不明になった若い鉱夫2人を、教団の連中が人質に取ってたんだ！『鍵を寄越せば命だけは助ける』って……。すまねぇ、俺が弱かったばかりに……。」
  - 選択肢1: **「事情は分かった。これ以上誰も失わないため、俺と一緒に戦って贖罪してくれ」** → `set_flags: [ec:flag_choice2_forgive]` → `daren_forgiven`
  - 選択肢2: **「人質の事情があっても裏切りは裏切りだ。規律通りギルドへ告発する」** → `set_flags: [ec:flag_choice2_report]` → `daren_reported`
- **`daren_forgiven`**:
  > 「……本気か、あんた！？くそっ、涙が出ちまうじゃねぇか……。分かった、この命懸けて償うぜ！まず俺が知ってる情報を全部話す。第3縦坑の『アイアン・グラインダー』は、**背面の冷却弁**が昔の事故で脆くなってるんだ！炎熱レーザーを撃つ隙に背後へ回り込め、必ず効く！」
  - （以降、`ec:quest_s08_daren_atonement` が中ボス撃破後に解放）
- **`daren_reported`**:
  > 「……そうだよな。甘えちゃいけねぇよな。分かった、逃げも隠れもしねぇ。トマス補給官に引き渡してくれ。……ただ一つだけ、奥にいる鉱夫たちを見捨てないでやってくれよな……。」
  - （以降、ダレンは野営地から護送退場し、`ec:quest_s09_tomas_audit` が中ボス撃破後に解放）

### `ec:dlg_tomas_hub`（廃坑補給官トマス）
- **`root`**:
  - 条件: `has_flag: ec:flag_choice1_extract` & 廃坑初到達 → 「ヴァルカンギルド長から森の星片抽出に伴う特別補給物資が届いています。規定通り支給します。」（中級回復薬×3付与）
  - 通常時:
    > 「記録によれば、この先から魔物の耐久力が跳ね上がります。命を繋ぐのは勇気より予備の回復薬です。補給は万全ですか？」
    - 選択肢1: **「補給物資・装備を購入する」** → `actions: [open_shop: ec:shop_mine_quartermaster]`
    - 選択肢2: **「依頼や報告がある」** → クエスト分岐メニュー
    - 選択肢3: **「また後で」** → 終了

---

## 5. ルミナ港の商人トリオ（ガルド・ミラ・ピップ）

### `ec:dlg_garudo_hub`（鍛冶屋ガルド）
- **`root`**:
  - 条件: `has_flag: ec:flag_choice1_extract` → 「おう小僧！森の星片をギルドに持ち帰ったそうじゃねぇか。おかげで炉の調子が絶好調だ、お前には特別に **全品1割引き** で売ってやらぁ！」
  - デフォルト → 「ふん、なまくら振り回して戦場に出るんじゃねえぞ。見合う武器が欲しけりゃ見ていけ。」
  - 選択肢1: **「武器・防具を見る」** → `actions: [open_shop: ec:shop_lumina_blacksmith]`
  - 選択肢2（条件: `has_flag: ec:flag_mine_unlocked` & `quest_not_started: ec:quest_s06_garudo_forge`）: **「廃坑の鍛冶素材について聞く」** → `actions: [start_quest: ec:quest_s06_garudo_forge]`
  - 選択肢3: **「また来るよ」** → 終了

### `ec:dlg_mira_hub`（薬草商ミラ）
- **`root`**:
  - 条件: `has_flag: ec:flag_choice1_purify` → 「あらあら、森の星片を浄化してくださったんですってね！エレナさんから聞きましたわ。薬草たちも喜んでいますぅ――お礼にあなたには **ずっと1割引き** にさせていただきますね！」
  - デフォルト → 「あらあら、お怪我はありませんか？冒険に出る前は、回復薬と解毒の香草を忘れずにどうぞ。」
  - 選択肢1: **「薬品・ポーションを見る」** → `actions: [open_shop: ec:shop_lumina_apothecary]`
  - 選択肢2（条件: `quest_not_started: ec:quest_s02_slime_gel`）: **「困っていることはない？」** → `actions: [start_quest: ec:quest_s02_slime_gel]`
  - 選択肢3: **「ありがとう、また来ます」** → 終了

### `ec:dlg_pip_hub`（行商人ピップ）
- **`root`**:
  > 「へへっ、旦那！ルミナ港名物の海鮮シチューに帰還の巻物、不要な魔物素材の買取まで何でもござれですよ〜。あ、そうそう、ここだけの話、最近は黒いフードの連中が港の荷揚げに紛れてたって噂ですぜ？」
  - 選択肢1: **「雑貨・料理を見る（素材売却も可）」** → `actions: [open_shop: ec:shop_lumina_general]`
  - 選択肢2（条件: `min_level: 4` & `quest_not_started: ec:quest_s04_spider_silk`）: **「儲け話があるって聞いたけど？」** → `actions: [start_quest: ec:quest_s04_spider_silk]`
  - 選択肢3: **「また覗くよ」** → 終了

---

## 6. 星見の賢者リラ (`ec:npc_lyra`) & 教団幹部カイン (`ec:npc_kain`)

### `ec:dlg_lyra_hub`（星見の丘・天文台跡）
- **`root`**:
  - 条件: `has_flag: ec:flag_ch1_completed` → `lyra_post_ch1`
  - 条件: `has_flag: ec:flag_sanctum_unlocked` → `lyra_sanctum_ready`
  - デフォルト → `lyra_intro`
- **`lyra_intro`**:
  > 「星の巡りが激しく乱れておる……。そなたが廃坑の巨兵を退けた冒険者じゃな。わしは星見の賢者リラ。この丘の地下には、千年前より封じられし『虚核の聖堂』が眠っておるのじゃ。」
  - 選択肢1: **「教団〈虚ろの手〉を止めに来た」** → `actions: [start_quest: ec:quest_m07_stargazer_oath]`
  - 選択肢2: **「浄化星片の交換ショップを開く」** → `actions: [open_shop: ec:shop_stargazer_relic]`
- **`lyra_sanctum_ready`**:
  > 「アストロラーベは聖堂への門を繋いでおる。中は1人でも挑めるが、本来は仲間と背中を預け合う協力試練の場じゃ。準備は整っておるか？」
  - 選択肢1: **「協力ダンジョン『虚核の聖堂』へ入場する」** → `actions: [enter_dungeon: ec:area_void_sanctum]`
  - 選択肢2: **「浄化星片・エッセンス交換ショップを見る」** → `actions: [open_shop: ec:shop_stargazer_relic]`
  - 選択肢3: **「聖堂の攻略の助言を聞く」** → `lyra_tips`
  - 選択肢4: **「準備してから来る」** → 終了
- **`lyra_tips`**:
  > 「ヴォイド・ウォーデンは3つの段階を持つ。紫のプリズムが現れたら即座に砕き、味方が虚空鎖に囚われたら全員で解き放つのじゃ。終盤に現れる『黄金の輪』は仲間と集まり、『蒼い光柱』は嵐を避ける避難所じゃと覚えておけ。」
  - 選択肢1: **「心に留めておく」** → `lyra_sanctum_ready`
- **`lyra_post_ch1`**:
  > 「星々は一時的に静けさを取り戻した。だが北の〈蒼穹の氷嶺〉に新たな揺らぎが見える……。旅立ちの日まで、聖堂の残響を鎮めながら至高の武具を鍛えるがよい。」
  - 選択肢1: **「協力ダンジョン『虚核の聖堂』へ再挑戦する」** → `actions: [enter_dungeon: ec:area_void_sanctum]`
  - 選択肢2: **「浄化星片・エッセンス交換ショップを見る」** → `actions: [open_shop: ec:shop_stargazer_relic]`
  - 選択肢3（条件: `quest_available: ec:quest_s12_sanctum_trial`）: **「反復依頼『聖堂の残響』を受ける」** → `actions: [start_quest: ec:quest_s12_sanctum_trial]`

### `ec:dlg_kain_encounters`（教団幹部カイン・イベント専用会話）
- **遭遇1：星見の丘・アストロラーベ前 (`ec:quest_m07_stargazer_oath`)**:
  > 「おや、ご挨拶が遅れましたね。私は〈虚ろの手〉の書記、カインと申します。ルミナ港からわざわざ追い掛けてくださるとは光栄です。」
  - *（条件分岐挿入：`has_flag: ec:flag_choice2_report` の場合）*
    > 「ふふ……聞きましたよ？弱みを見せた鉱夫長を冷徹に切り捨てたとか。見上げた規律の精神ですね――いっそ我らの側へ来ませんか？」
  - *（条件分岐挿入：`has_flag: ec:flag_choice2_forgive` の場合）*
    > 「ふふ……裏切り者すら許すとは、随分と甘い方ですね。その情の深さが、聖堂の闇で仇とならねばよいのですが。」
  > 「残念ながら、聖堂の扉はもうすぐ開きます。私の部下たちと少し遊んでいてくださいな。」
- **遭遇2：虚核の聖堂・ヴォイド・ウォーデン撃破後 (`ec:quest_m08_void_warden`)**:
  > 「……素晴らしい。実に美しい戦いでしたよ、ルミナの新星。まさか虚核を宿した古の守護者すら打ち砕くとは。」
  > 「ですが誤解なさらぬよう。この聖堂は、千年前の〈エーテル核〉を虚無へ還す儀式の『第一の鍵』に過ぎません。我が主はすでに **〈第二の星片〉**――北の果て、**〈蒼穹の氷嶺〉** に眠る凍てつく核へと手を伸ばしております。」
  > 「その残滓はお好きなようになさってください。次に相まみえる時、アエリアの空はもう墜ち始めているのですから……では、また北の空で。」
  - *(カイン、紫の裂け目へ消失 → 宝箱＆`ec:item_q_void_remnant` 出現)*
