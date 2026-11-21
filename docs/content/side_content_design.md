# 横の広がりコンテンツ拡張 設計書 — 「蒼穹の冒険者録」

> **メタ情報**
> - ブランチ: `ag/side-content`
> - 定義チャプターID: `ec:chapter_side_chronicles`（メインストーリーは延長せず、第1章・第2章の全域を横に広げるサイド・エンドゲーム拡張）
> - バランス方針: 現行 `docs/BALANCE.md` 上限（`level_cap: 20`, `stat_cap: 100000`, `player_hp_cap: 1024`, `xp_gain_cap: 10000`, `currency_gain_cap: 5000`, `global_enemy_cap: 100`）を厳守

---

## A. 新職業 3種（既存3職と役割を明確に分離）

既存の戦士（近接バランス）・弓使い（遠距離会心）・魔法使い（遠距離属性火力）に対し、以下の3職を追加する（合計6職、9枠職業選択メニュー `ClassMenu` およびヴァルカンの職業選択会話に収容）。

| Class ID | 職業名 | 役割 | リソース | 初期値 (HP / Res / ATK / DEF / Crit / SP) | 成長/Lv | 専用初期武器 |
|---|---|---|---|---|---|---|
| `ec:class_paladin` | **聖騎士** | 盾役・タンク（最高HP/DEF/属性耐性＋自己回復＆防御バフ） | `RAGE` (regen 2, hit 8) | 165 / 100 / 11 / 18 / 4% / 1.00 | HP+29, ATK+2.9, DEF+4.4, Res+0.5%, SP+0.02 | `ec:item_wpn_recruit_mace` |
| `ec:class_cleric` | **星導士** | 支援ヒーラー（高マナ・高回復・防御支援＋聖光/雷属性魔法） | `MANA` (regen 10, hit 0) | 115 / 130 / 13 / 11 / 6% / 1.10 | HP+21, MP+14, ATK+3.6, DEF+2.9, Res+0.4%, SP+0.024 | `ec:item_wpn_novice_censer` |
| `ec:class_assassin` | **影刃** | 暗殺・高機動会心近接（最高会心率/会心倍率＋虚空/氷連撃） | `FOCUS` (regen 3, hit 3) | 105 / 100 / 16 / 9 / 14% / 1.00 | HP+19, ATK+4.1, DEF+2.3, Crit+0.7%, CritDmg+0.3%, SP+0.02 | `ec:item_wpn_iron_dirk` |

### スキル構成（各4スキル＝計12スキル）
- **聖騎士 (`ec:class_paladin`)**: `paladin_bash`（聖盾打撃：AETHER＋防御+14）、`paladin_aegis`（蒼穹の聖壁：範囲＋12%回復＋防御+32）、`paladin_judgment`（星光の審判：AETHER範囲）、`paladin_sanctuary`（不滅の星盾：範囲＋25%回復＋防御+45）
- **星導士 (`ec:class_cleric`)**: `cleric_smite`（星光の導矢：AETHER遠距離）、`cleric_mend`（星片の癒し：28%回復＋防御+16）、`cleric_radiance`（天光の聖環：LIGHTNING範囲＋14%回復）、`cleric_blessing`（蒼穹の祈り：AETHER範囲＋38%回復＋防御+30）
- **影刃 (`ec:class_assassin`)**: `assassin_slash`（影縫いの双閃：低CD物理）、`assassin_shadowstep`（虚影の急襲：VOID中距離強襲）、`assassin_fan`（凍刃の乱舞：ICE範囲連撃）、`assassin_execution`（絶影の断罪：VOID高倍率フィニッシュ）

### 実測戦闘シミュレーション結果（`SideContentValidationTest`）
| 職業 | Void Warden Lv18 ソロ (EPIC装備) | Aether Chimera Lv20 4P (LEG装備) | 役割特性 |
|---|---|---|---|
| `ec:class_paladin` (聖騎士) | **WIN 130.4s** | **WIN 114.0s** | 最高耐久・自己防御バフ＆回復で安定攻略 |
| `ec:class_cleric` (星導士) | **WIN 115.0s** | **WIN 98.2s** | 豊富なマナと高割合回復で長期戦に強い |
| `ec:class_assassin` (影刃) | **WIN 76.4s** | **WIN 58.5s** | 高会心＆虚空・氷連撃の近接バースト火力 |

### 専用装備（24種＝各職8種、Lv1〜20・全5レアリティ網羅）
- 各職：武器5種（Lv1 COMMON / Lv5 UNCOMMON / Lv11 RARE / Lv16 EPIC / Lv20 LEGENDARY）＋オフハンド2種（Lv3 COMMON / Lv18 EPIC）＋専用胴防具1種（Lv16 EPIC）。
- `ec:shop_chronicle_exchange`（レンの功績交換所）でLv1〜16武具を購入・功績印交換可能、`ec:shop_artisan_forge`（カエデの工房）で素材＋研磨砥石/試練勲章を用いてLv18〜20武具を鍛造・強化可能。

---

## B. サブクエスト 18本（第1章・第2章全域＋選択分岐・護衛風・隠し・やり込み）

| Quest ID | 分類 | 担当NPC | 推奨Lv | 概要・ギミック |
|---|---|---|---|---|
| `ec:quest_side_01_port_smugglers` | 短編・分岐 | ピップ | Lv4 | 盗まれた積み荷奪還。**分岐**：港へ返還 (`flag_side_pip_return`) vs 開拓民へ分配 (`flag_side_pip_donate`) |
| `ec:quest_side_02_mira_moonherb` | 短編・採集 | ミラ | Lv5 | 毒腺とアッシュウッド調査で星露の蓮花調合を支援 |
| `ec:quest_side_03_elena_escort` | 護衛風 | エレナ→トマス | Lv7 | 森〜廃坑の負傷斥候帰還ルート掃討＋連絡引き継ぎ |
| `ec:quest_side_04_garudo_whetstone` | クラフト導線 | ガルド | Lv9 | 鉄くず＋雷晶石で名匠の研磨砥石を試作 |
| `ec:quest_side_05_daren_lost_pick` | 短編・探索 | ダレン | Lv10 | 崩落坑道の亡き相棒の形見回収と魔物鎮魂 |
| `ec:quest_side_06_tomas_supply_escort` | 護衛風 | トマス→リラ | Lv12 | 廃坑→星見の丘の補給馬車護衛ルート掃討 |
| `ec:quest_side_07_lyra_astral_survey` | 探索・伝承 | リラ | Lv14 | 星片落下点の魔力観測（聖堂外縁踏破＋ウィスプ/狩人討伐） |
| `ec:quest_side_08_nora_glacier_ecology` | 短編・分岐 | ノラ | Lv17 | 氷嶺の生態調査。**分岐**：保護記録 (`flag_side_nora_preserve`) vs 標本採集 (`flag_side_nora_specimen`) |
| `ec:quest_side_09_nora_cavern_escort` | 護衛風 | ノラ→オルフェ | Lv18 | 蒼晶の氷窟碑文調査の護衛と記録提出 |
| `ec:quest_side_10_borka_masterwork` | クラフト・装備 | ボルカ | Lv19 | 研磨砥石＋蒼氷の欠片で名匠脚甲 `ec:item_arm_artisan_greaves` を鍛造 |
| `ec:quest_side_h01_forgotten_diary` | **隠し1** | レン | Lv13 | 要 `flag_iron_grinder_defeated`。`q_sealed_diary` を回収し称号フラグ `flag_title_truth_seeker` 解放 |
| `ec:quest_side_h02_frozen_shrine` | **隠し2** | ノラ | Lv19 | 要 `flag_ch2_colossus_defeated`。氷嶺4島踏破で称号フラグ `flag_title_frost_walker`＋`acc_veteran_emblem` 解放 |
| `ec:quest_side_g01_sky_fishing` | 釣り/採集 (12h反復) | カエデ | Lv4 | 蒼穹サケの水揚げ＆水辺の魔物掃討（`mat_sky_salmon`×3＋料理報酬） |
| `ec:quest_side_g02_starlight_herb` | 採集/調合 (12h反復) | カエデ | Lv12 | 星露の蓮花＆霊樹採取（`mat_starlight_lotus`×3＋研磨砥石報酬） |
| `ec:quest_side_d01_daily_hunt` | **デイリー1** (24h) | レン | Lv6 | ギルド日替わり討伐手配（`mat_chronicle_seal`×2＋研磨砥石） |
| `ec:quest_side_d02_daily_frost` | **デイリー2** (24h) | レン | Lv16 | 北方遠征隊デイリー掃討（`mat_chronicle_seal`×3＋蒼氷の欠片×3） |
| `ec:quest_side_w01_weekly_bounty` | **ウィークリー** (7日) | レン | Lv14 | 週間強敵大討伐（`mat_chronicle_seal`×8＋`mat_crucible_medal`×2＋研磨砥石×3） |
| `ec:quest_side_c01_crucible_gauntlet` | **チャレンジ** (24h) | ソーレン | Lv18 | 試練場ボス `ec:boss_aether_chimera` 撃破（`mat_crucible_medal`×3＋称号フラグ `flag_title_crucible_victor`） |

---

## C. やり込みコンテンツと現行YAMLでの実装範囲

1. **デイリー／ウィークリー**: `repeatable: true` と `repeat_cooldown_ms`（`86400000` / `604800000`）でデータ駆動実装済み。固定時刻リセット（JST 05:00 等）は `docs/SIDE_ENGINE_REQUESTS.md` に要望化。
2. **実績・称号**: 隠しクエスト・試練踏破で称号フラグ（`flag_title_truth_seeker`, `flag_title_frost_walker`, `flag_title_crucible_victor`）と歴戦徽章 `acc_veteran_emblem` を付与。レンの窓口で取得済み称号を照会可能。チャット接頭辞・GUI一覧はエンジン要望化。
3. **収集図鑑（敵・アイテム）**: 博物学者ノラ (`ec:npc_nora`) とレン (`ec:npc_ren`) の生態・収集依頼および図鑑メモ会話で導線構築。自動記録・達成率ボーナスUIはエンジン要望化。
4. **チャレンジダンジョン／ボスラッシュ**: 新規ダンジョン **`ec:dungeon_aether_crucible`（星片の試練場）** と試練専用ボス **`ec:boss_aether_chimera`（星核の融合体エーテル・キメラ, Lv20, 88,000 HP, 3段階, 護衛は第1章・第2章エリート混成）** を `dungeon_configs` で実装済み。連戦ウェーブ・タイムスコア/S〜Cランク集計はエンジン要望化。
5. **装備強化・素材クラフト**: 名匠カエデの工房 (`ec:shop_artisan_forge`) で「素材＋名匠の研磨砥石＋勲章/欠片」のトークン交換レシピ（砥石自作・料理・秘薬・Lv18〜20装備鍛造）を実装済み。専用金床UI/+1〜+5強化はエンジン要望化。
6. **釣り・採集ミニコンテンツ**: `mat_sky_salmon`・`mat_starlight_lotus`・料理/秘薬 (`con_sky_salmon_grill`, `con_starlight_elixir`, `con_whetstone_tonic`) および12時間反復採集依頼2本を実装済み。実釣り竿ミニゲーム・採取ノード設置はエンジン要望化。
