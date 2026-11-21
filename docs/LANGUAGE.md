# 言語ファイルと翻訳キー

表示文言はMiniMessage形式です。`plugins/EtherealChronicle/lang/ja_jp.yml` と `en_us.yml` は初回配置後、サーバー編集を優先します。未編集の同梱ファイルだけ次の配布版へ更新します。

設定 `language: auto` はプレイヤーのクライアント言語に追従し、未対応言語は日本語を選びます。`ja_jp` / `en_us` を指定するとサーバー全体で固定します。存在しないキーは日本語、それも無い場合はキー名を表示し、警告はキーごとに一度だけ記録します。

| キー | 日本語 | English | 使用箇所 | プレースホルダ |
| --- | --- | --- | --- | --- |
| `ec.class.choose` | 職業を選んでください | Choose a class | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.codex.back_areas` | エリア一覧へ戻る | Back to Areas | src/main/kotlin/net/nakaymi/etherealchronicle/paper/CodexController.kt |  |
| `ec.codex.catch_count` | 釣果: <count>匹 | Caught: <count> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/CodexController.kt | `<count>` |
| `ec.codex.defeated_count` | 討伐数: <count> | Defeated: <count> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/CodexController.kt | `<count>` |
| `ec.codex.drops` | ドロップ: <values> | Drops: <values> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/CodexController.kt | `<values>` |
| `ec.codex.found_ratio` | 発見: <found>/<total> (<percent>%) | Discovered: <found>/<total> (<percent>%) | src/main/kotlin/net/nakaymi/etherealchronicle/paper/CodexController.kt | `<found>`, `<percent>`, `<total>` |
| `ec.codex.gathering_count` | 採取回数: <count> | Harvests: <count> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/CodexController.kt | `<count>` |
| `ec.codex.gathering_kind` | 種別: <kind> / 必要道具 T<tier> | Type: <kind> / Required tool T<tier> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/CodexController.kt | `<kind>`, `<tier>` |
| `ec.codex.maximum_size` | 最大: <size>cm / <crown> | Max: <size> cm / <crown> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/CodexController.kt | `<crown>`, `<size>` |
| `ec.codex.no_weakness` | なし | None | src/main/kotlin/net/nakaymi/etherealchronicle/paper/CodexController.kt |  |
| `ec.codex.search_area` | このエリアで魔物を探しましょう。 | Explore this area to find enemies. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/CodexController.kt |  |
| `ec.codex.seek_fishing_spot` | 釣り場で竿を出して探しましょう。 | Cast your rod at a fishing spot to search. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/CodexController.kt |  |
| `ec.codex.seek_gathering_node` | 専用道具で採取地を調べましょう。 | Inspect gathering sites with a specialized tool. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/CodexController.kt |  |
| `ec.codex.tab_fish` | 魚類図鑑 | Fish Codex | src/main/kotlin/net/nakaymi/etherealchronicle/paper/CodexController.kt |  |
| `ec.codex.tab_gathering` | 採取図鑑 | Gathering Codex | src/main/kotlin/net/nakaymi/etherealchronicle/paper/CodexController.kt |  |
| `ec.codex.tab_items` | 発見したアイテム | Discovered Items | src/main/kotlin/net/nakaymi/etherealchronicle/paper/CodexController.kt |  |
| `ec.codex.undiscovered_enemy` | 未発見の魔物 | Undiscovered enemy | src/main/kotlin/net/nakaymi/etherealchronicle/paper/CodexController.kt |  |
| `ec.codex.undiscovered_fish` | 未発見の魚 | Undiscovered fish | src/main/kotlin/net/nakaymi/etherealchronicle/paper/CodexController.kt |  |
| `ec.codex.undiscovered_gathering` | 未発見の採取地 | Undiscovered gathering site | src/main/kotlin/net/nakaymi/etherealchronicle/paper/CodexController.kt |  |
| `ec.codex.weaknesses` | 弱点: <values> | Weaknesses: <values> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/CodexController.kt | `<values>` |
| `ec.fishing.actionbar_status` | <aqua>張力 <tension>% \| 進捗 <progress>% | <aqua>Tension <tension>% \| Progress <progress>% | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingController.kt | `<progress>`, `<tension>` |
| `ec.fishing.aim_water` | 水面を狙って竿を投げてください。 | Aim at the water before casting. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingController.kt |  |
| `ec.fishing.bait_failed` | 餌を消費できませんでした。 | Could not consume the bait. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingController.kt |  |
| `ec.fishing.behavior_calm` | 安定 | Steady | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingController.kt |  |
| `ec.fishing.behavior_dive` | 潜り | Diving | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingController.kt |  |
| `ec.fishing.behavior_telegraph` | 予告 | Telegraph | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingController.kt |  |
| `ec.fishing.behavior_thrash` | 暴れ | Thrashing | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingController.kt |  |
| `ec.fishing.bite_subtitle` | 右クリックで合わせろ！ | Right-click to hook it! | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingController.kt |  |
| `ec.fishing.bite_title` | ヒット！ | Bite! | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingController.kt |  |
| `ec.fishing.bossbar_reeling` | 格闘中 [<behavior>] 進捗 <progress>% | Fighting [<behavior>] Progress <progress>% | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingController.kt | `<behavior>`, `<progress>` |
| `ec.fishing.bossbar_search` | 魚影を探しています… | Searching for fish… | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingController.kt |  |
| `ec.fishing.cancel` | 釣りを中断しました。 | Fishing was interrupted. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingController.kt |  |
| `ec.fishing.capacity` | 釣り人が多く、竿を出せません。少し待ってください。 | Too many anglers are fishing. Please wait a moment. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingController.kt |  |
| `ec.fishing.cast_started` | 釣り糸を垂らしました。再度右クリックで回収できます。 | Your line is out. Right-click again to reel it in. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingController.kt |  |
| `ec.fishing.catch_pending` | 魚を掛けました。釣果を確定しています。 | Hooked! Confirming your catch… | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingController.kt |  |
| `ec.fishing.catch_title` | 釣り上げた！ | Caught! | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingController.kt |  |
| `ec.fishing.cooldown` | 次の釣りまで少しお待ちください。 | Please wait before casting again. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingController.kt |  |
| `ec.fishing.crown_gold` | [金冠] | [Gold Crown] | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingController.kt |  |
| `ec.fishing.crown_silver` | [銀冠] | [Silver Crown] | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingController.kt |  |
| `ec.fishing.damage` | 戦闘により釣りを中断しました。 | Fishing was interrupted by combat. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingController.kt |  |
| `ec.fishing.death` | 倒れたため釣りを中断しました。 | Fishing was interrupted because you fell. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingController.kt |  |
| `ec.fishing.debug_cancel` | デバッグ釣りを終了しました。 | Debug fishing ended. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingController.kt |  |
| `ec.fishing.distance` | 水面が近すぎるか、遠すぎます。 | The water is too close or too far away. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingController.kt |  |
| `ec.fishing.dive` | 潜り！左クリックでいなす | Diving! Left-click to brace. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingUiFeedback.kt |  |
| `ec.fishing.hand_swap` | 釣りを中断しました。 | Fishing was interrupted. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingController.kt |  |
| `ec.fishing.high_tension` | 張力が高い！手を離す | Tension is high! Release. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingUiFeedback.kt |  |
| `ec.fishing.level_low` | 釣りレベルが足りません。 | Your fishing level is too low. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingController.kt |  |
| `ec.fishing.line_left` | 釣り場から離れたため中断しました。 | Fishing was interrupted because you left the spot. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingController.kt |  |
| `ec.fishing.low_tension` | 張力が低い！右クリックで引く | Tension is low! Right-click to pull. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingUiFeedback.kt |  |
| `ec.fishing.macro_detected` | 機械的な操作間隔を検出しました。希少魚の出現率を調整します。 | Mechanical timing detected. Rare fish odds have been adjusted. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingController.kt |  |
| `ec.fishing.new_record` | NEW! | NEW! | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingController.kt |  |
| `ec.fishing.no_eligible_fish` | この竿と釣り場では狙える魚がいません。 | No fish can be caught with this rod at this spot. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingController.kt |  |
| `ec.fishing.no_shadow` | この水辺には魚影が見当たりません。 | No fish are visible in these waters. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingController.kt |  |
| `ec.fishing.profile_loading` | 冒険データを準備中です。 | Adventure data is still loading. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingController.kt |  |
| `ec.fishing.profile_missing` | 冒険データを読み込めず、釣果を確定できませんでした。 | Could not load adventure data to confirm your catch. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingController.kt |  |
| `ec.fishing.pull` | 右クリックで引く | Right-click to pull. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingUiFeedback.kt |  |
| `ec.fishing.quit` | 釣りを終了しました。 | Fishing ended. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingController.kt |  |
| `ec.fishing.reward_failed` | 魚の受け取りに失敗しました。所持品を整理して再度お試しください。 | Could not receive the fish. Clear inventory space and try again. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingController.kt |  |
| `ec.fishing.rod_changed` | 竿を持ち替えたため釣りを中断しました。 | Fishing was interrupted because you changed rods. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingController.kt |  |
| `ec.fishing.selected_bait_missing` | 選択中の餌を持っていません。 | You do not have the selected bait. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingController.kt |  |
| `ec.fishing.subtitle_inventory` | 所持品を整理してください | Clear some inventory space. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingController.kt |  |
| `ec.fishing.subtitle_keep_tension` | 張力を保とう | Keep the tension steady. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingUiFeedback.kt |  |
| `ec.fishing.subtitle_lower_tension` | 張力を下げよう | Lower the tension. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingUiFeedback.kt |  |
| `ec.fishing.subtitle_next_bite` | 次のヒットを待とう | Wait for the next bite. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingUiFeedback.kt |  |
| `ec.fishing.subtitle_retry` | 次は張力を調整しよう | Adjust the tension next time. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingUiFeedback.kt |  |
| `ec.fishing.subtitle_timeout` | 魚を逃してしまった… | The fish got away… | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingUiFeedback.kt |  |
| `ec.fishing.telegraph` | 予兆中：次の動きを見る | Watch for the next move. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingUiFeedback.kt |  |
| `ec.fishing.thrash` | 暴れ中：手を離して張力を逃がす | Thrashing! Release to lower tension. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingUiFeedback.kt |  |
| `ec.fishing.tired` | 魚影が薄くなりました。別の釣り場へ移動してください。 | Fish have become scarce here. Try another spot. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingController.kt |  |
| `ec.fishing.title_escape` | 魚に逃げられた… | The fish escaped… | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingUiFeedback.kt |  |
| `ec.fishing.title_line_snap` | 糸が切れた！ | The line snapped! | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingUiFeedback.kt |  |
| `ec.fishing.title_missed` | アタリを逃した！ | Missed the bite! | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingUiFeedback.kt |  |
| `ec.fishing.title_reward_failed` | 受け取り失敗 | Reward failed | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingController.kt |  |
| `ec.fishing.title_timeout` | 時間切れ！ | Time is up! | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingUiFeedback.kt |  |
| `ec.fishing.world_only` | 釣りは管理された冒険ワールドでのみ行えます。 | Fishing is only available in the managed adventure world. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingController.kt |  |
| `ec.menu.codex_area` | 冒険者の図鑑 — 魔物 | Adventurer Codex — Enemies | src/main/kotlin/net/nakaymi/etherealchronicle/paper/CodexController.kt |  |
| `ec.menu.codex_areas` | 冒険者の図鑑 — エリア | Adventurer Codex — Areas | src/main/kotlin/net/nakaymi/etherealchronicle/paper/CodexController.kt |  |
| `ec.menu.codex_fish` | 生活図鑑 — 魚類 | Life Codex — Fish | src/main/kotlin/net/nakaymi/etherealchronicle/paper/CodexController.kt |  |
| `ec.menu.codex_gathering` | 生活図鑑 — 採取 | Life Codex — Gathering | src/main/kotlin/net/nakaymi/etherealchronicle/paper/CodexController.kt |  |
| `ec.menu.codex_items` | 冒険者の図鑑 — アイテム | Adventurer Codex — Items | src/main/kotlin/net/nakaymi/etherealchronicle/paper/CodexController.kt |  |
| `ec.menu.dialogue` | 会話 | Dialogue | src/main/kotlin/net/nakaymi/etherealchronicle/paper/AdventureController.kt |  |
| `ec.menu.enhance` | 装備強化 | Enhance Gear | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.menu.equipment` | 装備 | Equipment | src/main/kotlin/net/nakaymi/etherealchronicle/paper/AdventureController.kt |  |
| `ec.menu.shop` | お店 | Shop | src/main/kotlin/net/nakaymi/etherealchronicle/paper/AdventureController.kt |  |
| `ec.startup.loading` | 冒険データを読み込んでいます… | Loading your adventure data… | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.startup.starting` | 冒険システムは起動中です。しばらくお待ちください。 | The adventure system is starting. Please wait. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |

## コンテンツYAMLの多言語化案
将来は `name_key` / `description_key` を追加し、同じ `ec.*` 翻訳表を参照します。既存の `name` / `description` は後方互換のフォールバックとして維持し、コンテンツschemaと保存形式を一括変更しない段階導入にします。
