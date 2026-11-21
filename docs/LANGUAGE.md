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
| `ec.gathering.actionbar_progress` | <green>採取中 <percent>% <gray><name> | <green>Gathering <percent>% <gray><name> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/GatheringController.kt | `<name>`, `<percent>` |
| `ec.gathering.actionbar_start` | <green>採取中… 動かずにお待ちください | <green>Gathering… Stay still. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/GatheringController.kt |  |
| `ec.gathering.already_gathering` | 採取中です。完了までお待ちください。 | Gathering is in progress. Please wait until it finishes. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/GatheringController.kt |  |
| `ec.gathering.bossbar` | 採取中: <name> | Gathering: <name> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/GatheringController.kt | `<name>` |
| `ec.gathering.cancel_damage` | 戦闘により採取を中断しました。 | Gathering was interrupted by combat. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/GatheringController.kt |  |
| `ec.gathering.cancel_death` | 倒れたため採取を中断しました。 | Gathering stopped because you fell. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/GatheringController.kt |  |
| `ec.gathering.cancel_generic` | 採取を中断しました。 | Gathering stopped. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/GatheringController.kt |  |
| `ec.gathering.cancel_move` | 移動したため採取を中断しました。 | Gathering stopped because you moved. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/GatheringController.kt |  |
| `ec.gathering.cancel_tool` | 道具を持ち替えたため採取を中断しました。 | Gathering stopped because you changed tools. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/GatheringController.kt |  |
| `ec.gathering.capacity` | 採取が混み合っています。少し待ってください。 | Gathering is busy. Please wait a moment. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/GatheringController.kt |  |
| `ec.gathering.channel_ended` | 採取チャンネルを終了しました。 | Gathering channel ended. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/GatheringController.kt |  |
| `ec.gathering.click_site` | 採取地点 <name> の表示または足元を右クリックしてください。 | Right-click the display or ground at <name>. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/GatheringController.kt | `<name>` |
| `ec.gathering.level_low` | 採取レベルが足りません。採取Lv.<level>以上が必要です。 | Your gathering level is too low. Gathering Lv. <level> or higher is required. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/GatheringController.kt | `<level>` |
| `ec.gathering.node_label` | <green>[採取] <white><name> <gray>(Lv.<level>) | <green>[Gather] <white><name> <gray>(Lv. <level>) | src/main/kotlin/net/nakaymi/etherealchronicle/paper/GatheringController.kt | `<level>`, `<name>` |
| `ec.gathering.profile_loading` | プレイヤーデータを読み込み中です。少し待ってからお試しください。 | Your profile is loading. Please wait and try again. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/GatheringController.kt |  |
| `ec.gathering.protected_terrain` | 採取地点の地形は変更できません。道具で採取してください。 | Gathering site terrain is protected. Use the tool to gather. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/GatheringController.kt |  |
| `ec.gathering.requirements` | この採取地点の条件を満たしていません。 | You do not meet the requirements for this gathering site. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/GatheringController.kt |  |
| `ec.gathering.respawned` | この採取地点は再生中です。 | This gathering site is regenerating. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/GatheringController.kt |  |
| `ec.gathering.respawning` | この採取地点は再生中です。あと <seconds> 秒お待ちください。 | This gathering site is regenerating. Wait <seconds> seconds. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/GatheringController.kt | `<seconds>` |
| `ec.gathering.reward` | 採取しました: <items> / 採取経験値 <xp> | Gathered: <items> / Gathering XP <xp> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/GatheringController.kt | `<items>`, `<xp>` |
| `ec.gathering.reward_empty` | 空振り | Nothing gathered | src/main/kotlin/net/nakaymi/etherealchronicle/paper/GatheringController.kt |  |
| `ec.gathering.reward_failed` | 採取を完了できませんでした: <reason> | Could not complete gathering: <reason> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/GatheringController.kt | `<reason>` |
| `ec.gathering.site_unavailable` | この採取地点の設定を読み込めませんでした。管理者にお知らせください。 | This gathering site could not be loaded. Please contact an administrator. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/GatheringController.kt |  |
| `ec.gathering.start_notice` | <name>の採取を開始しました。完了まで動かずにお待ちください。 | Gathering <name>. Stay still until it finishes. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/GatheringController.kt | `<name>` |
| `ec.gathering.tool_level_low` | この道具を使うには生活スキルLv.<level>以上が必要です。 | This tool requires Life Skill Lv. <level> or higher. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/GatheringController.kt | `<level>` |
| `ec.gathering.tool_required` | <name>の採取には<kind>専用道具が必要です。 | A dedicated <kind> tool is required to gather <name>. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/GatheringController.kt | `<kind>`, `<name>` |
| `ec.gathering.tool_tier_low` | 道具の段階が足りません。T<tier>以上が必要です。 | Your tool tier is too low. Tier <tier> or higher is required. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/GatheringController.kt | `<tier>` |
| `ec.gathering.tool_wrong` | <name>には<required>が必要です（現在:<current>）。 | <name> requires <required> (held: <current>). | src/main/kotlin/net/nakaymi/etherealchronicle/paper/GatheringController.kt | `<current>`, `<name>`, `<required>` |
| `ec.gathering.transaction_locked` | 取引処理中は採取できません。 | You cannot gather during a transaction. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/GatheringController.kt |  |
| `ec.gathering.world_only` | 管理対象の採取エリアでのみ採取できます。 | Gathering is only available in the managed gathering areas. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/GatheringController.kt |  |
| `ec.hud.main` | HP <hp>/<max_hp>  <resource_name> <resource>/<max_resource>  <skill> CD <cooldown>秒  仲間 <party>人 | HP <hp>/<max_hp>  <resource_name> <resource>/<max_resource>  <skill> CD <cooldown>s  Party <party> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt | `<cooldown>`, `<hp>`, `<max_hp>`, `<max_resource>`, `<party>`, `<resource>`, `<resource_name>`, `<skill>` |
| `ec.menu.codex_area` | 冒険者の図鑑 — 魔物 | Adventurer Codex — Enemies | src/main/kotlin/net/nakaymi/etherealchronicle/paper/CodexController.kt |  |
| `ec.menu.codex_areas` | 冒険者の図鑑 — エリア | Adventurer Codex — Areas | src/main/kotlin/net/nakaymi/etherealchronicle/paper/CodexController.kt |  |
| `ec.menu.codex_fish` | 生活図鑑 — 魚類 | Life Codex — Fish | src/main/kotlin/net/nakaymi/etherealchronicle/paper/CodexController.kt |  |
| `ec.menu.codex_gathering` | 生活図鑑 — 採取 | Life Codex — Gathering | src/main/kotlin/net/nakaymi/etherealchronicle/paper/CodexController.kt |  |
| `ec.menu.codex_items` | 冒険者の図鑑 — アイテム | Adventurer Codex — Items | src/main/kotlin/net/nakaymi/etherealchronicle/paper/CodexController.kt |  |
| `ec.menu.dialogue` | 会話 | Dialogue | src/main/kotlin/net/nakaymi/etherealchronicle/paper/AdventureController.kt |  |
| `ec.menu.enhance` | 装備強化 | Enhance Gear | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.menu.equipment` | 装備 | Equipment | src/main/kotlin/net/nakaymi/etherealchronicle/paper/AdventureController.kt |  |
| `ec.menu.shop` | お店 | Shop | src/main/kotlin/net/nakaymi/etherealchronicle/paper/AdventureController.kt |  |
| `ec.profile.inventory_space` | 持ち物に空きを作ってください。 | Make room in your inventory. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.profile.save_retry` | 保存できませんでした。もう一度お試しください。 | Could not save your profile. Please try again. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.profile.skill_slot` | スキルスロット | Skill Slot | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.profile.skill_slot_space` | 9番のアイテムを空きに移して持ち物を閉じてください。保護品は地面に捨てません。 | Move the item from hotbar slot 9 and close your inventory. Protected items will not be dropped. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.profile.starter_space` | 持ち物の空きがなくなりました。管理者にスターター装備の再付与を依頼してください。 | Your inventory filled up. Ask an administrator to restore your starter gear. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.profile.welcome` | ようこそ！受付嬢セリアに話しかけて冒険を始めましょう。 | Welcome! Talk to Celia at the reception desk to begin your adventure. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.resource.focus` | 集中 | Focus | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.resource.mana` | マナ | Mana | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.resource.rage` | 怒り | Rage | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.skill.cooldown` | まだ使用できません。 | That skill is not ready yet. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.skill.obstructed` | 魔物との間に障害物があります。 | An obstacle blocks your line to the enemy. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.skill.resource_low` | リソースが足りません。 | Not enough resource. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.skill.selected` | 選択: <skill> | Selected: <skill> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt | `<skill>` |
| `ec.skill.target_missing` | 射程内に魔物がいません。 | There is no enemy in range. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.skill.weapon_requirements` | この装備の条件を満たしていません。 | You do not meet this equipment's requirements. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.startup.loading` | 冒険データを読み込んでいます… | Loading your adventure data… | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.startup.starting` | 冒険システムは起動中です。しばらくお待ちください。 | The adventure system is starting. Please wait. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |

## コンテンツYAMLの多言語化案
将来は `name_key` / `description_key` を追加し、同じ `ec.*` 翻訳表を参照します。既存の `name` / `description` は後方互換のフォールバックとして維持し、コンテンツschemaと保存形式を一括変更しない段階導入にします。
