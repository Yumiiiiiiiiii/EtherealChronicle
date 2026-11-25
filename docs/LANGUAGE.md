# 言語ファイルと翻訳キー

表示文言はMiniMessage形式です。`plugins/EtherealChronicle/lang/ja_jp.yml` と `en_us.yml` は初回配置後、サーバー編集を優先します。未編集の同梱ファイルだけ次の配布版へ更新します。

設定 `language: auto` はプレイヤーのクライアント言語に追従し、未対応言語は日本語を選びます。`ja_jp` / `en_us` を指定するとサーバー全体で固定します。存在しないキーは日本語、それも無い場合はキー名を表示し、警告はキーごとに一度だけ記録します。

| キー | 日本語 | English | 使用箇所 | プレースホルダ |
| --- | --- | --- | --- | --- |
| `ec.advancement.pack_description` | エセリアルクロニクル: 称号進捗 | Ethereal Chronicle: title advancements | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.advancement.root_description` | 冒険の記録 | Adventure records | src/main/kotlin/net/nakaymi/etherealchronicle/paper/AdvancementDatapack.kt<br>src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.advancement.root_title` | エセリアルクロニクル | Ethereal Chronicle | src/main/kotlin/net/nakaymi/etherealchronicle/paper/AdvancementDatapack.kt<br>src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.area.enter_field` | <yellow>冒険フィールド（推奨 Lv.<min>〜<max>） | <yellow>Adventure Field (Recommended Lv.<min>-<max>) | src/main/kotlin/net/nakaymi/etherealchronicle/paper/WayfindingController.kt | `<max>`, `<min>` |
| `ec.area.enter_safe` | <green>安全地帯 — 魔物の攻撃を受けません | <green>Safe Zone — Protected from monster attacks | src/main/kotlin/net/nakaymi/etherealchronicle/paper/WayfindingController.kt |  |
| `ec.area.teleported` | <name>へ転送しました。 | Teleported to <name>. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/AdventureController.kt | `<name>` |
| `ec.area.unknown` | 未探索エリア | Wilderness | src/main/kotlin/net/nakaymi/etherealchronicle/paper/WayfindingController.kt |  |
| `ec.boss.bossbar_enrage` |  激昂 |  ENRAGE | src/main/kotlin/net/nakaymi/etherealchronicle/paper/EnemyController.kt |  |
| `ec.boss.bossbar_title` | <name> <hp>/<max_hp>  フェーズ <phase><enrage> | <name> <hp>/<max_hp>  Phase <phase><enrage> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/EnemyController.kt | `<enrage>`, `<hp>`, `<max_hp>`, `<name>`, `<phase>` |
| `ec.boss.telegraph` | 予兆: <attack>！<cue>範囲から離れてください。 | Telegraph: <attack>! Move away from the <cue> area. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/EnemyController.kt | `<attack>`, `<cue>` |
| `ec.boss.telegraph_generic` | 危険な | dangerous | src/main/kotlin/net/nakaymi/etherealchronicle/paper/EnemyController.kt |  |
| `ec.boss.telegraph_ice` | 青白い | icy | src/main/kotlin/net/nakaymi/etherealchronicle/paper/EnemyController.kt |  |
| `ec.boss.telegraph_void` | 紫の | void | src/main/kotlin/net/nakaymi/etherealchronicle/paper/EnemyController.kt |  |
| `ec.class.choose` | 職業を選んでください | Choose a class | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.class.confirm_body` | この職業の初期装備とスキルを受け取ります。選択後も管理者に変更を依頼できます。 | You will receive this class's starter gear and skills. An administrator can change your class later. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.class.confirm_cancel` | 戻る | Go back | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.class.confirm_start` | この職業で始める | Start as this class | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.class.confirm_title` | <class> で冒険を始めますか？ | Begin your adventure as <class>? | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt | `<class>` |
| `ec.class.selection.role` | 資源: <resource> | Resource: <resource> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt | `<resource>` |
| `ec.class.selection.skills` | スキル: <skills> | Skills: <skills> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt | `<skills>` |
| `ec.class.selection.stats` | HP <hp> / 攻撃 <attack> / 防御 <defense> | HP <hp> / Attack <attack> / Defense <defense> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt | `<attack>`, `<defense>`, `<hp>` |
| `ec.class.weapon_queued` | 新しい職業の初期武器は持ち物に送られました。<gold>/ec claim</gold> で受け取れます。 | Your new class starter weapon was sent to your mailbox. Use <gold>/ec claim</gold> to receive it. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.codex.back_areas` | エリア一覧へ戻る | Back to Areas | src/main/kotlin/net/nakaymi/etherealchronicle/paper/CodexController.kt |  |
| `ec.codex.catch_count` | 釣果: <count>匹 | Caught: <count> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/CodexController.kt | `<count>` |
| `ec.codex.crown_none` | なし | None | src/main/kotlin/net/nakaymi/etherealchronicle/paper/CodexController.kt |  |
| `ec.codex.defeated_count` | 討伐数: <count> | Defeated: <count> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/CodexController.kt | `<count>` |
| `ec.codex.drops` | ドロップ: <values> | Drops: <values> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/CodexController.kt | `<values>` |
| `ec.codex.element.aether` | エーテル | Aether | src/main/kotlin/net/nakaymi/etherealchronicle/paper/CodexController.kt |  |
| `ec.codex.element.fire` | 炎 | Fire | src/main/kotlin/net/nakaymi/etherealchronicle/paper/CodexController.kt |  |
| `ec.codex.element.ice` | 氷 | Ice | src/main/kotlin/net/nakaymi/etherealchronicle/paper/CodexController.kt |  |
| `ec.codex.element.lightning` | 雷 | Lightning | src/main/kotlin/net/nakaymi/etherealchronicle/paper/CodexController.kt |  |
| `ec.codex.element.physical` | 物理 | Physical | src/main/kotlin/net/nakaymi/etherealchronicle/paper/CodexController.kt |  |
| `ec.codex.element.unknown` | 未知 | Unknown | src/main/kotlin/net/nakaymi/etherealchronicle/paper/CodexController.kt |  |
| `ec.codex.element.void` | 虚無 | Void | src/main/kotlin/net/nakaymi/etherealchronicle/paper/CodexController.kt |  |
| `ec.codex.found_ratio` | 発見: <found>/<total> (<percent>%) | Discovered: <found>/<total> (<percent>%) | src/main/kotlin/net/nakaymi/etherealchronicle/paper/CodexController.kt | `<found>`, `<percent>`, `<total>` |
| `ec.codex.gathering_count` | 採取回数: <count> | Harvests: <count> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/CodexController.kt | `<count>` |
| `ec.codex.gathering_kind` | 種別: <kind> / 必要道具 T<tier> | Type: <kind> / Required tool T<tier> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/CodexController.kt | `<kind>`, `<tier>` |
| `ec.codex.kind_crystal` | 結晶 | Crystal | src/main/kotlin/net/nakaymi/etherealchronicle/paper/CodexController.kt |  |
| `ec.codex.kind_herb` | 薬草 | Herb | src/main/kotlin/net/nakaymi/etherealchronicle/paper/CodexController.kt |  |
| `ec.codex.kind_ore` | 鉱石 | Ore | src/main/kotlin/net/nakaymi/etherealchronicle/paper/CodexController.kt |  |
| `ec.codex.kind_wood` | 木材 | Wood | src/main/kotlin/net/nakaymi/etherealchronicle/paper/CodexController.kt |  |
| `ec.codex.maximum_size` | 最大: <size>cm / <crown> | Max: <size> cm / <crown> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/CodexController.kt | `<crown>`, `<size>` |
| `ec.codex.new_discoveries` | 図鑑の発見率が上がりました: <items> | New codex discoveries: <items> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt | `<items>` |
| `ec.codex.next_page` | 次のページ | Next page | src/main/kotlin/net/nakaymi/etherealchronicle/paper/CodexController.kt |  |
| `ec.codex.no_weakness` | なし | None | src/main/kotlin/net/nakaymi/etherealchronicle/paper/CodexController.kt |  |
| `ec.codex.page` | ページ <page>/<pages> | Page <page>/<pages> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/CodexController.kt | `<page>`, `<pages>` |
| `ec.codex.previous_page` | 前のページ | Previous page | src/main/kotlin/net/nakaymi/etherealchronicle/paper/CodexController.kt |  |
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
| `ec.combat.enemy_defeated` | 魔物を討伐！ レベル <level> / 経験値 <xp> | Enemy defeated! Level <level> / XP <xp> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt | `<level>`, `<xp>` |
| `ec.combat.hit_crit` | <gold>✦ -<damage> CRIT! | <gold>✦ -<damage> CRIT! | src/main/kotlin/net/nakaymi/etherealchronicle/paper/CombatFeedback.kt | `<damage>` |
| `ec.combat.hit_normal` | <white>-<damage> | <white>-<damage> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/CombatFeedback.kt | `<damage>` |
| `ec.combat.hit_resist` | <gray>▼ -<damage> 耐性 | <gray>▼ -<damage> RESIST | src/main/kotlin/net/nakaymi/etherealchronicle/paper/CombatFeedback.kt | `<damage>` |
| `ec.combat.hit_weak` | <aqua>▲ -<damage> 弱点! | <aqua>▲ -<damage> WEAK! | src/main/kotlin/net/nakaymi/etherealchronicle/paper/CombatFeedback.kt | `<damage>` |
| `ec.combat.revive` | 装備の加護が命を守りました！ | Your equipment's blessing saved your life! | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.combat.target_hp` | <name>  HP <hp>/<max_hp> | <name>  HP <hp>/<max_hp> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/CombatFeedback.kt | `<hp>`, `<max_hp>`, `<name>` |
| `ec.command.class_set` | 職業を変更しました。 | Class updated. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.command.currency_set` | 所持金を設定しました。 | Currency updated. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.command.enemy_spawned` | 魔物を召喚しました。 | Enemy spawned. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.command.failed` | 操作できません: <reason> | Could not complete that action: <reason> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/AdventureController.kt<br>src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt | `<reason>` |
| `ec.command.flag_changed` | フラグを<operation>しました: <flag> | Flag <flag> was <operation>. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt | `<flag>`, `<operation>` |
| `ec.command.flag_clear` | 解除 | cleared | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.command.flag_set` | 設定 | set | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.command.flag_unchanged` | フラグは既に<operation>済みです: <flag> | Flag <flag> was already <operation>. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt | `<flag>`, `<operation>` |
| `ec.command.help` | /ec reload \| validate \| give <player> <item> [amount] \| setlevel <player> <level> \| class <player> <id> \| flag <player> <flag> [set\|clear] \| debug sim … | /ec reload \| validate \| give <player> <item> [amount] \| setlevel <player> <level> \| class <player> <id> \| flag <player> <flag> [set\|clear] \| debug sim … | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt | `<flag>`, `<id>`, `<item>`, `<level>`, `<player>` |
| `ec.command.item_given` | アイテムを付与しました。 | Item granted. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.command.level_set` | レベルを変更しました。 | Level updated. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.command.life_level_set` | <kind> をレベル <level> に設定しました。 | Set <kind> to level <level>. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt | `<kind>`, `<level>` |
| `ec.command.no_permission` | 権限がありません。 | You do not have permission to use this command. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt<br>src/main/kotlin/net/nakaymi/etherealchronicle/paper/SimulationCommandController.kt |  |
| `ec.command.reload_busy` | 取引・職業選択・聖堂攻略中は再読み込みできません。 | Content cannot reload during a transaction, class selection, or trial. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.command.reload_content_in_use` | 使用中の職業または依頼を削除する変更は再読み込みできません。 | Reload cannot remove a class or quest currently in use. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.command.reload_success` | コンテンツを再読み込みしました。resource_pack.* の設定変更は再起動後に反映されます。 | Content reloaded. Changes to resource_pack.* settings require a restart. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.command.validate_success` | コンテンツ検証成功。 | Content validation succeeded. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.command.validation_failed` | 検証失敗: <reason> | Validation failed: <reason> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt | `<reason>` |
| `ec.content.unknown_name` | 名称不明 | Unknown content | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.death.gold_lost` | 死亡したため所持ゴールド <amount>G を失いました。 | You died and lost <amount> gold. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/AdventureController.kt | `<amount>` |
| `ec.debug.fish_prefix` | FISH <name>: <detail> | FISH <name>: <detail> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt | `<detail>`, `<name>` |
| `ec.debug.fishing_active` | 釣りセッション / phase=<phase> / action=<action> / elapsed=<elapsed>s of <limit>s / fish=<fish> / progress=<progress>% / tension=<tension>% / bossbar=<bossbar> | Fishing session / phase=<phase> / action=<action> / elapsed=<elapsed>s of <limit>s / fish=<fish> / progress=<progress>% / tension=<tension>% / bossbar=<bossbar> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingController.kt | `<action>`, `<bossbar>`, `<elapsed>`, `<fish>`, `<limit>`, `<phase>`, `<progress>`, `<tension>` |
| `ec.debug.fishing_inactive` | 釣りセッションはありません。 | No fishing session is active. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingController.kt |  |
| `ec.debug.gather_prefix` | GATHER <name>: <detail> | GATHER <name>: <detail> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt | `<detail>`, `<name>` |
| `ec.debug.gathering_active` | 採取中 / node=<node> / progress=<progress>% / bossbar=<bossbar> / visible=<visible> / duration=<duration>ms | Gathering / node=<node> / progress=<progress>% / bossbar=<bossbar> / visible=<visible> / duration=<duration>ms | src/main/kotlin/net/nakaymi/etherealchronicle/paper/GatheringController.kt | `<bossbar>`, `<duration>`, `<node>`, `<progress>`, `<visible>` |
| `ec.debug.gathering_details` | <active> / world=<world> owned=<owned> pos=<x>,<y>,<z> profile=<profile> level=<level> held=<held> tool=<tool> nearest=<nearest> locked=<locked> | <active> / world=<world> owned=<owned> pos=<x>,<y>,<z> profile=<profile> level=<level> held=<held> tool=<tool> nearest=<nearest> locked=<locked> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/GatheringController.kt | `<active>`, `<held>`, `<level>`, `<locked>`, `<nearest>`, `<owned>`, `<profile>`, `<tool>`, `<world>`, `<x>`, `<y>`, `<z>` |
| `ec.debug.gathering_inactive` | 採取中ではありません。最終試行: <last> | Not gathering. Last attempt: <last> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/GatheringController.kt | `<last>` |
| `ec.debug.gathering_nearest` | <site> distance=<distance> expected=<kind>/T<tier>/Lv<level> cooldown=<cooldown>ms | <site> distance=<distance> expected=<kind>/T<tier>/Lv<level> cooldown=<cooldown>ms | src/main/kotlin/net/nakaymi/etherealchronicle/paper/GatheringController.kt | `<cooldown>`, `<distance>`, `<kind>`, `<level>`, `<site>`, `<tier>` |
| `ec.debug.none` | なし | none | src/main/kotlin/net/nakaymi/etherealchronicle/paper/GatheringController.kt |  |
| `ec.dialogue.close` | 閉じる | Close | src/main/kotlin/net/nakaymi/etherealchronicle/paper/AdventureController.kt |  |
| `ec.dialogue.talk` | お話 | Talk | src/main/kotlin/net/nakaymi/etherealchronicle/paper/AdventureController.kt |  |
| `ec.dungeon.clear` | <message> | <message> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/DungeonController.kt | `<message>` |
| `ec.dungeon.entry` | <message> | <message> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/DungeonController.kt | `<message>` |
| `ec.dungeon.first_rank_reward` | 初回ランク報酬を受け取りました。 | You received the first-clear rank reward. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/DungeonController.kt |  |
| `ec.dungeon.no_records` | 試練の記録はまだありません。 | No trial records yet. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.dungeon.preparing` | <name> を準備しています。 | Preparing <name>. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/AdventureController.kt<br>src/main/kotlin/net/nakaymi/etherealchronicle/paper/DungeonController.kt | `<name>` |
| `ec.dungeon.record` | <name>: <seconds>秒 / ランク<rank> | <name>: <seconds>s / Rank <rank> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt | `<name>`, `<rank>`, `<seconds>` |
| `ec.dungeon.release_active` | ダンジョンスロット <slot> は稼働中または予約中のため解放できません。 | Dungeon slot <slot> is currently active or reserved and cannot be released. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/SimulationCommandController.kt | `<slot>` |
| `ec.dungeon.release_cleaning` | ダンジョンスロット <slot> はすでに撤去処理中です。 | Dungeon slot <slot> is already being cleaned up. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/SimulationCommandController.kt | `<slot>` |
| `ec.dungeon.release_done` | ダンジョンスロット <slot> の保護状態を解放しました。 | Released protected state for dungeon slot <slot>. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/SimulationCommandController.kt | `<slot>` |
| `ec.dungeon.release_invalid_slot` | 無効なダンジョンスロット番号です。 | Invalid dungeon slot number. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/SimulationCommandController.kt |  |
| `ec.dungeon.release_not_found` | ダンジョンスロット <slot> に解放対象の保護状態や残骸記録はありません。 | Dungeon slot <slot> has no protected state or leftover record to release. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/SimulationCommandController.kt | `<slot>` |
| `ec.dungeon.release_started` | ダンジョンスロット <slot> の撤去と解放を開始しました。 | Started cleanup and release for dungeon slot <slot>. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/SimulationCommandController.kt | `<slot>` |
| `ec.dungeon.release_usage` | 使用法: /ec dungeon release <slot> | Usage: /ec dungeon release <slot> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/SimulationCommandController.kt | `<slot>` |
| `ec.dungeon.relogin_required` | 帰還記録の安全確認には再ログインが必要です。確認まで次の試練には入場できません。 | Reconnect to verify your return. You cannot enter another trial until it is confirmed. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/DungeonController.kt |  |
| `ec.dungeon.result` | <message> 記録 <seconds>秒 / ランク<rank>（自己ベスト <best_seconds>秒 / <best_rank>） | <message> Time <seconds>s / Rank <rank> (best <best_seconds>s / <best_rank>) | src/main/kotlin/net/nakaymi/etherealchronicle/paper/DungeonController.kt | `<best_rank>`, `<best_seconds>`, `<message>`, `<rank>`, `<seconds>` |
| `ec.dungeon.timeout` | 聖堂の制限時間を迎えました。 | The trial time limit has been reached. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/DungeonController.kt |  |
| `ec.dungeon.wave` | 試練 第<current>/<total>波が始まります。 | Trial wave <current>/<total> is beginning. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/DungeonController.kt | `<current>`, `<total>` |
| `ec.enhance.button` | +<level> に強化 | Enhance to +<level> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt | `<level>` |
| `ec.enhance.cap` | 上限: +<level> | Maximum: +<level> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt | `<level>` |
| `ec.enhance.cost` | 費用: <cost> G | Cost: <cost> G | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt | `<cost>` |
| `ec.enhance.level_lore` | 強化段階 +<level> | Enhancement +<level> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt | `<level>` |
| `ec.enhance.material` | 必要素材: <item> ×1 | Required material: <item> ×1 | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt | `<item>` |
| `ec.enhance.refunded` | 強化対象が持ち物から見つからず、費用を返却しました。 | The target equipment was missing, so the cost was refunded. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.enhance.success` | 装備を +<level> に強化しました。 | Equipment enhanced to +<level>. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt | `<level>` |
| `ec.equipment.accessory_equipped` | 装飾品を装備しました。 | Accessory equipped. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/AdventureController.kt |  |
| `ec.error.area_missing` | 転送先エリアが見つかりません。 | Destination area not found. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/AdventureController.kt<br>src/main/kotlin/net/nakaymi/etherealchronicle/paper/SimulationCommandController.kt |  |
| `ec.error.argument_missing` | 引数が不足しています: <detail> | Missing argument: <detail> | src/main/kotlin/net/nakaymi/etherealchronicle/core/SimulationSession.kt | `<detail>` |
| `ec.error.class_already_selected` | 職業は既に選択されています。 | A class has already been selected. | src/main/kotlin/net/nakaymi/etherealchronicle/core/GameService.kt<br>src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.error.class_busy` | 職業変更を処理中です。しばらくお待ちください。 | A class change is already in progress. Please wait. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.error.class_missing` | 職業が未選択です。 | No class has been selected. | src/main/kotlin/net/nakaymi/etherealchronicle/core/SimulationSession.kt<br>src/main/kotlin/net/nakaymi/etherealchronicle/paper/SimulationCommandController.kt |  |
| `ec.error.consumable_cooldown` | まだ使用できません。 | That item is not ready yet. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/AdventureController.kt |  |
| `ec.error.currency_cap` | 所持金の上限を超えます。 | This would exceed your currency limit. | src/main/kotlin/net/nakaymi/etherealchronicle/core/AdventureServices.kt<br>src/main/kotlin/net/nakaymi/etherealchronicle/core/WorldDefinitions.kt |  |
| `ec.error.currency_low` | 所持金が足りません。 | You do not have enough currency. | src/main/kotlin/net/nakaymi/etherealchronicle/core/AdventureServices.kt<br>src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.error.daily_limit` | 本日の購入上限に達しています。 | You have reached today's purchase limit. | src/main/kotlin/net/nakaymi/etherealchronicle/core/AdventureServices.kt |  |
| `ec.error.damage_range` | ダメージは0より大きく <cap> 以下で指定してください。 | Damage must be greater than 0 and at most <cap>. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/SimulationCommandController.kt | `<cap>` |
| `ec.error.dialogue_choice` | この選択肢は選べません。 | That dialogue choice is unavailable. | src/main/kotlin/net/nakaymi/etherealchronicle/core/AdventureServices.kt |  |
| `ec.error.dialogue_cycle` | 会話の分岐が循環しています。 | The dialogue branch contains a cycle. | src/main/kotlin/net/nakaymi/etherealchronicle/core/AdventureServices.kt |  |
| `ec.error.dialogue_missing` | 会話が見つかりません。 | Dialogue not found. | src/main/kotlin/net/nakaymi/etherealchronicle/core/AdventureServices.kt<br>src/main/kotlin/net/nakaymi/etherealchronicle/core/SimulationSession.kt |  |
| `ec.error.dialogue_node_missing` | 会話ノードが見つかりません。 | Dialogue node not found. | src/main/kotlin/net/nakaymi/etherealchronicle/core/AdventureServices.kt |  |
| `ec.error.dialogue_too_deep` | 会話の分岐が長すぎます。 | The dialogue branch is too long. | src/main/kotlin/net/nakaymi/etherealchronicle/core/AdventureServices.kt |  |
| `ec.error.dungeon_already_inside` | 既にダンジョン内です。 | You are already in a dungeon. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/DungeonController.kt<br>src/main/kotlin/net/nakaymi/etherealchronicle/paper/SimulationCommandController.kt |  |
| `ec.error.dungeon_full` | 聖堂が満員です。しばらくお待ちください。 | The sanctuary is full. Please wait. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/DungeonController.kt |  |
| `ec.error.dungeon_missing` | ダンジョンが見つかりません: <detail> | Dungeon not found: <detail> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/DungeonController.kt<br>src/main/kotlin/net/nakaymi/etherealchronicle/paper/DungeonEntranceListener.kt | `<detail>` |
| `ec.error.dungeon_required` | 進捗の条件には有効なダンジョンIDが必要です。 | The advancement condition requires a valid dungeon ID. | src/main/kotlin/net/nakaymi/etherealchronicle/core/WorldContent.kt |  |
| `ec.error.dungeon_unlock_required` | 仲間全員がダンジョンへの道を開放してください。 | All party members must unlock access to the dungeon. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/DungeonController.kt |  |
| `ec.error.enemy_missing` | 魔物が見つかりません。 | Enemy not found. | src/main/kotlin/net/nakaymi/etherealchronicle/core/GameService.kt<br>src/main/kotlin/net/nakaymi/etherealchronicle/paper/EnemyController.kt<br>src/main/kotlin/net/nakaymi/etherealchronicle/paper/SimulationCommandController.kt |  |
| `ec.error.enhance_budget_cap` | 装備のステータス予算上限に達しています。 | The equipment stat budget cap has been reached. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.error.enhance_cap` | 強化上限です。 | The enhancement cap has been reached. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.error.enhance_hold_gear` | 強化できる冒険者装備を手に持ってください。 | Hold adventurer equipment that can be enhanced. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.error.enhance_item_changed` | 強化対象の装備が変わりました。もう一度開いてください。 | The target equipment changed. Reopen the menu. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.error.enhance_item_changed_no_cost` | 強化対象の装備が変わりました。費用は発生しません。 | The target equipment changed. No cost was charged. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.error.enhance_item_missing` | 強化対象の装備が見つかりません。 | The target equipment could not be found. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.error.enhance_level_changed` | 強化段階が更新されています。もう一度開いてください。 | The enhancement level changed. Reopen the menu. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.error.enhance_material_missing` | 強化素材が足りません。 | You do not have the required enhancement material. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.error.enhance_one_gear` | 装備品を1個だけ手に持ってください。 | Hold exactly one piece of equipment. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.error.equipment_requirements` | この装備は現在の職業・レベルでは使用できません。 | Your current class or level cannot use this equipment. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.error.fishing_active` | 既に釣りセッション中です。 | A fishing session is already active. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingController.kt |  |
| `ec.error.fishing_bait` | 餌が見つかりません。 | Bait not found. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingController.kt |  |
| `ec.error.fishing_bait_missing` | 選択中の餌を持っていません。 | You do not have the selected bait. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingController.kt |  |
| `ec.error.fishing_capacity` | 釣りセッション数が上限に達しています。 | The fishing session limit has been reached. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingController.kt |  |
| `ec.error.fishing_no_species` | この場所で釣れる魚がありません。 | No fish can be caught at this spot. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingController.kt |  |
| `ec.error.fishing_profile` | プレイヤーデータを読み込み中です。 | The player profile is still loading. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingController.kt |  |
| `ec.error.fishing_rod` | 釣り竿の定義がありません。 | No fishing rod is configured. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingController.kt |  |
| `ec.error.fishing_species` | 魚種が見つかりません: <id> | Fish species not found: <id> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingController.kt | `<id>` |
| `ec.error.fishing_spot` | このワールドに釣り場がありません。 | No fishing spot is configured in this world. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingController.kt |  |
| `ec.error.fishing_world` | 管理ワールド内で実行してください。 | Run this inside the managed world. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingController.kt |  |
| `ec.error.flag_format` | フラグIDの形式が正しくありません。 | The flag ID format is invalid. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.error.flag_not_declared` | 章に定義されていないフラグです: <detail> | This flag is not declared in a chapter: <detail> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt | `<detail>` |
| `ec.error.flag_operation` | 操作は set または clear を指定してください。 | Operation must be set or clear. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.error.guard_spawn_failed` | 護衛魔物を配置できませんでした。 | Could not spawn a guard enemy. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/DungeonController.kt |  |
| `ec.error.invalid_content` | コンテンツ定義が不正です。詳細を確認してください。 | Content definition is invalid. Check the diagnostic details. | src/main/kotlin/net/nakaymi/etherealchronicle/core/ContentMapReaders.kt<br>src/main/kotlin/net/nakaymi/etherealchronicle/core/WorldContent.kt<br>src/main/kotlin/net/nakaymi/etherealchronicle/core/WorldDefinitions.kt<br>src/main/kotlin/net/nakaymi/etherealchronicle/core/WorldValidation.kt<br>src/main/kotlin/net/nakaymi/etherealchronicle/data/Content.kt<br>src/main/kotlin/net/nakaymi/etherealchronicle/data/PlayerStore.kt<br>src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.error.inventory_exchange` | 必要品が不足しているか、持ち物に空きがありません。 | Required items are missing or your inventory is full. | src/main/kotlin/net/nakaymi/etherealchronicle/core/AdventureServices.kt |  |
| `ec.error.inventory_full` | 持ち物に空きがありません。 | Make room in your inventory. | src/main/kotlin/net/nakaymi/etherealchronicle/core/SimulationSession.kt<br>src/main/kotlin/net/nakaymi/etherealchronicle/paper/AdventureController.kt<br>src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.error.item_missing` | アイテムが見つかりません。 | Item not found. | src/main/kotlin/net/nakaymi/etherealchronicle/core/AdventureServices.kt<br>src/main/kotlin/net/nakaymi/etherealchronicle/core/SimulationSession.kt |  |
| `ec.error.item_unsellable` | このアイテムは売れません。 | This item cannot be sold. | src/main/kotlin/net/nakaymi/etherealchronicle/core/AdventureServices.kt |  |
| `ec.error.items_missing` | 必要なアイテムが足りません。 | You do not have the required items. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/AdventureController.kt |  |
| `ec.error.life_level_range` | 生活スキルレベルは1..<detail>で指定してください。 | Life skill level must be between 1 and <detail>. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt | `<detail>` |
| `ec.error.materials_or_space` | 必要な素材が不足しているか、持ち物に空きがありません。 | Required materials are missing or your inventory is full. | src/main/kotlin/net/nakaymi/etherealchronicle/core/AdventureServices.kt<br>src/main/kotlin/net/nakaymi/etherealchronicle/paper/AdventureController.kt |  |
| `ec.error.party_gather` | 仲間全員が近くに集まり、職業を選択してください。 | Gather your party nearby and make sure everyone has selected a class. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/DungeonController.kt |  |
| `ec.error.party_invite_missing` | 招待がありません。 | There is no party invitation. | src/main/kotlin/net/nakaymi/etherealchronicle/core/Cooperation.kt |  |
| `ec.error.party_leader_required` | パーティーリーダーが入場してください。 | The party leader must enter. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/DungeonController.kt |  |
| `ec.error.party_member_missing` | 仲間が見つかりません。 | Party member not found. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.error.party_not_online` | パーティー全員がオンラインになってから入場してください。 | All party members must be online before entering. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/DungeonController.kt |  |
| `ec.error.player_missing` | プレイヤーが見つかりません。 | Player not found. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.error.player_required` | プレイヤーを指定してください。 | Specify a player. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.error.profile_loading` | プロフィールを読み込み中です。 | Your profile is still loading. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.error.quantity_range` | 指定した数量は許容範囲外です。 | The quantity is outside the allowed range. | src/main/kotlin/net/nakaymi/etherealchronicle/core/AdventureServices.kt<br>src/main/kotlin/net/nakaymi/etherealchronicle/core/SimulationSession.kt |  |
| `ec.error.quest_active` | この依頼は受注済みです。 | This quest is already active. | src/main/kotlin/net/nakaymi/etherealchronicle/core/AdventureServices.kt |  |
| `ec.error.quest_incomplete` | 依頼の達成条件を満たしていません。 | You have not met the quest objectives. | src/main/kotlin/net/nakaymi/etherealchronicle/core/AdventureServices.kt<br>src/main/kotlin/net/nakaymi/etherealchronicle/paper/AdventureController.kt |  |
| `ec.error.quest_missing` | 依頼が見つかりません。 | Quest not found. | src/main/kotlin/net/nakaymi/etherealchronicle/core/AdventureServices.kt |  |
| `ec.error.quest_npc_complete` | 依頼はNPCに報告してください。 | Report quests to an NPC. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.error.quest_npc_start` | 依頼はNPCとの会話から受注してください。 | Accept quests through an NPC dialogue. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.error.quest_prerequisite` | 依頼の受注条件を満たしていません。 | You do not meet the quest requirements. | src/main/kotlin/net/nakaymi/etherealchronicle/core/AdventureServices.kt |  |
| `ec.error.reset_schedule` | リセット周期は DAILY または WEEKLY です。 | Reset schedule must be DAILY or WEEKLY. | src/main/kotlin/net/nakaymi/etherealchronicle/core/AdventureServices.kt |  |
| `ec.error.revive_is_passive` | 不死鳥の星羽は装飾品として装備し、致死ダメージ時に自動発動します。/ec equipment から装備してください。 | Equip the Phoenix Star Feather as an accessory. It activates automatically on lethal damage. Use /ec equipment. | src/main/kotlin/net/nakaymi/etherealchronicle/core/Consumable.kt |  |
| `ec.error.reward_once_flag` | 一度限りの配布フラグがありません。 | A one-time reward flag is required. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/AdventureController.kt |  |
| `ec.error.sell_items_missing` | 売却する品が足りません。 | You do not have enough items to sell. | src/main/kotlin/net/nakaymi/etherealchronicle/core/AdventureServices.kt<br>src/main/kotlin/net/nakaymi/etherealchronicle/paper/AdventureController.kt |  |
| `ec.error.shop_amount_cap` | 商品の数量が上限を超えています。 | The quantity exceeds the transaction limit. | src/main/kotlin/net/nakaymi/etherealchronicle/core/AdventureServices.kt |  |
| `ec.error.shop_condition` | この商品は条件を満たしていません。 | You do not meet this item's requirements. | src/main/kotlin/net/nakaymi/etherealchronicle/core/AdventureServices.kt |  |
| `ec.error.shop_entry_missing` | 商品が見つかりません。 | Shop entry not found. | src/main/kotlin/net/nakaymi/etherealchronicle/core/AdventureServices.kt |  |
| `ec.error.shop_missing` | 店が見つかりません。 | Shop not found. | src/main/kotlin/net/nakaymi/etherealchronicle/core/AdventureServices.kt<br>src/main/kotlin/net/nakaymi/etherealchronicle/paper/AdventureController.kt |  |
| `ec.error.shop_no_buyback` | この店は買取できません。 | This shop does not buy items. | src/main/kotlin/net/nakaymi/etherealchronicle/core/AdventureServices.kt |  |
| `ec.error.shop_npc_missing` | この店を開くNPCが見つかりません。 | No NPC was found for this shop. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.error.shop_too_far` | お店の近くに移動してください。 | Move closer to the shop. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.error.sim_exists` | その仮想プレイヤーは既に参加しています。 | That virtual player has already joined. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/SimulationCommandController.kt |  |
| `ec.error.sim_join_first` | 先に ec debug sim join を実行してください。 | Run ec debug sim join first. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/SimulationCommandController.kt |  |
| `ec.error.sim_load_failed` | 仮想プロフィールを読み込めませんでした: <reason> | Could not load virtual profile: <reason> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/SimulationCommandController.kt | `<reason>` |
| `ec.error.sim_operation_unknown` | 不明なシミュレーション操作です: <detail> | Unknown simulation operation: <detail> | src/main/kotlin/net/nakaymi/etherealchronicle/core/SimulationSession.kt | `<detail>` |
| `ec.error.sim_peer_missing` | 相手の仮想プレイヤーが参加していません。 | The other virtual player has not joined. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/SimulationCommandController.kt |  |
| `ec.error.sim_save_failed` | 仮想プロフィールを保存できませんでした: <reason> | Could not save virtual profile: <reason> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/SimulationCommandController.kt | `<reason>` |
| `ec.error.sim_usage` | /ec debug sim <operation> <name> [arguments] を指定してください。 | Use /ec debug sim <operation> <name> [arguments]. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/SimulationCommandController.kt | `<name>`, `<operation>` |
| `ec.error.spawn_cap` | 魔物の出現上限に達しています。 | The enemy spawn limit has been reached. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/DungeonController.kt |  |
| `ec.error.spawn_duplicate_or_cap` | 同じ魔物が既に存在するか、出現上限に達しています。 | That enemy already exists or the spawn limit was reached. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.error.spawn_unavailable` | 同じ魔物が存在するか、出現上限に達しています。 | The enemy already exists or the spawn limit has been reached. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/SimulationCommandController.kt |  |
| `ec.error.target_defeated` | 対象は討伐済みです。 | That target has already been defeated. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/SimulationCommandController.kt |  |
| `ec.error.target_missing` | 対象が見つかりません。 | The target was not found. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/SimulationCommandController.kt |  |
| `ec.error.target_participant` | 対象の参加者ではありません。 | You are not a participant in that target. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/SimulationCommandController.kt |  |
| `ec.error.teleport_condition` | 転送条件を満たしていません。 | You do not meet the teleport requirements. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/AdventureController.kt |  |
| `ec.error.teleport_destination` | 転送座標は対象エリア内で指定してください。 | The teleport destination must be inside the selected area. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/AdventureController.kt<br>src/main/kotlin/net/nakaymi/etherealchronicle/paper/DungeonController.kt<br>src/main/kotlin/net/nakaymi/etherealchronicle/paper/SimulationCommandController.kt |  |
| `ec.error.transaction_busy` | 取引の処理中です。 | A transaction is already in progress. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/AdventureController.kt<br>src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt<br>src/main/kotlin/net/nakaymi/etherealchronicle/paper/SimulationCommandController.kt |  |
| `ec.error.transaction_changed` | 取引の状態が変化しました。 | The transaction state changed. Please try again. | src/main/kotlin/net/nakaymi/etherealchronicle/core/AdventureServices.kt |  |
| `ec.error.transaction_replayed` | 処理済みの取引です。 | This transaction has already been processed. | src/main/kotlin/net/nakaymi/etherealchronicle/core/AdventureServices.kt |  |
| `ec.error.transaction_rolled_back` | 保存に失敗したため取引を戻しました。 | The transaction was rolled back because saving failed. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/AdventureController.kt |  |
| `ec.error.usage` | 使い方: <detail> | Usage: <detail> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt | `<detail>` |
| `ec.error.world_missing` | 転送先ワールドがありません。 | Destination world is unavailable. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/AdventureController.kt<br>src/main/kotlin/net/nakaymi/etherealchronicle/paper/SimulationCommandController.kt |  |
| `ec.error.world_owned_only` | この操作は管理対象ワールド内でのみ利用できます。 | This action is only available in the managed world. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/EnemyController.kt |  |
| `ec.error.world_unavailable` | 専用ワールドの準備ができていません。 | The dedicated world is not ready. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/AdventureController.kt<br>src/main/kotlin/net/nakaymi/etherealchronicle/paper/DungeonController.kt<br>src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt<br>src/main/kotlin/net/nakaymi/etherealchronicle/paper/SimulationCommandController.kt |  |
| `ec.fishing.actionbar_status` | <aqua>張力 <tension>% \| 進捗 <progress>% | <aqua>Tension <tension>% \| Progress <progress>% | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingController.kt | `<progress>`, `<tension>` |
| `ec.fishing.aim_water` | 水面を狙って竿を投げてください。 | Aim at the water before casting. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingController.kt |  |
| `ec.fishing.bait_auto` | 自動 | Auto | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.fishing.bait_disabled` | 餌を使わない設定にしました。 | Bait disabled. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.fishing.bait_enabled_auto` | 自動で餌を選ぶ設定にしました。 | Automatic bait selection enabled. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.fishing.bait_failed` | 餌を消費できませんでした。 | Could not consume the bait. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingController.kt |  |
| `ec.fishing.bait_none` | なし | None | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.fishing.bait_selected` | 餌を選択しました: <name> | Selected bait: <name> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt | `<name>` |
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
| `ec.fishing.crown_gold` | [金冠] | [Gold Crown] | src/main/kotlin/net/nakaymi/etherealchronicle/paper/CodexController.kt<br>src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingController.kt |  |
| `ec.fishing.crown_silver` | [銀冠] | [Silver Crown] | src/main/kotlin/net/nakaymi/etherealchronicle/paper/CodexController.kt<br>src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingController.kt |  |
| `ec.fishing.damage` | 戦闘により釣りを中断しました。 | Fishing was interrupted by combat. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingController.kt |  |
| `ec.fishing.death` | 倒れたため釣りを中断しました。 | Fishing was interrupted because you fell. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingController.kt |  |
| `ec.fishing.debug_bossbar` | <aqua>デバッグ釣り: <fish> | <aqua>Debug fishing: <fish> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingController.kt | `<fish>` |
| `ec.fishing.debug_cancel` | デバッグ釣りを終了しました。 | Debug fishing ended. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingController.kt<br>src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.fishing.debug_started` | デバッグ釣りを開始しました: <fish>。左クリックでいなし、右クリックで引いてください。 | Started debug fishing: <fish>. Left-click to brace and right-click to pull. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/FishingController.kt | `<fish>` |
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
| `ec.fishing.no_session` | 釣りセッションはありません。 | There is no fishing session. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
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
| `ec.gathering.channel_started` | 採取を開始しました。 | Gathering started. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/GatheringController.kt |  |
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
| `ec.gathering.tool_bait` | 釣り餌 | bait | src/main/kotlin/net/nakaymi/etherealchronicle/paper/GatheringController.kt |  |
| `ec.gathering.tool_extractor` | 結晶抽出器 | crystal extractor | src/main/kotlin/net/nakaymi/etherealchronicle/paper/GatheringController.kt |  |
| `ec.gathering.tool_hatchet` | 手斧 | hatchet | src/main/kotlin/net/nakaymi/etherealchronicle/paper/GatheringController.kt |  |
| `ec.gathering.tool_level_low` | この道具を使うには生活スキルLv.<level>以上が必要です。 | This tool requires Life Skill Lv. <level> or higher. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/GatheringController.kt | `<level>` |
| `ec.gathering.tool_pickaxe` | ピッケル | pickaxe | src/main/kotlin/net/nakaymi/etherealchronicle/paper/GatheringController.kt |  |
| `ec.gathering.tool_required` | <name>の採取には<kind>専用道具が必要です。 | A dedicated <kind> tool is required to gather <name>. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/GatheringController.kt | `<kind>`, `<name>` |
| `ec.gathering.tool_rod` | 釣竿 | fishing rod | src/main/kotlin/net/nakaymi/etherealchronicle/paper/GatheringController.kt |  |
| `ec.gathering.tool_sickle` | 採薬鎌 | herb sickle | src/main/kotlin/net/nakaymi/etherealchronicle/paper/GatheringController.kt |  |
| `ec.gathering.tool_tier_low` | 道具の段階が足りません。T<tier>以上が必要です。 | Your tool tier is too low. Tier <tier> or higher is required. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/GatheringController.kt | `<tier>` |
| `ec.gathering.tool_wrong` | <name>には<required>が必要です（現在:<current>）。 | <name> requires <required> (held: <current>). | src/main/kotlin/net/nakaymi/etherealchronicle/paper/GatheringController.kt | `<current>`, `<name>`, `<required>` |
| `ec.gathering.transaction_locked` | 取引処理中は採取できません。 | You cannot gather during a transaction. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/GatheringController.kt |  |
| `ec.gathering.world_only` | 管理対象の採取エリアでのみ採取できます。 | Gathering is only available in the managed gathering areas. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/GatheringController.kt |  |
| `ec.guide.active_quest` | <gold>進行中の依頼:</gold> <white><name> | <gold>Active Quest:</gold> <white><name> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/WayfindingController.kt | `<name>` |
| `ec.guide.current_area` | <gray>現在地:</gray> <white><area> | <gray>Location:</gray> <white><area> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/WayfindingController.kt | `<area>` |
| `ec.guide.dir_e` | 東 | East | src/main/kotlin/net/nakaymi/etherealchronicle/paper/WayfindingController.kt |  |
| `ec.guide.dir_n` | 北 | North | src/main/kotlin/net/nakaymi/etherealchronicle/paper/WayfindingController.kt |  |
| `ec.guide.dir_ne` | 北東 | Northeast | src/main/kotlin/net/nakaymi/etherealchronicle/paper/WayfindingController.kt |  |
| `ec.guide.dir_nw` | 北西 | Northwest | src/main/kotlin/net/nakaymi/etherealchronicle/paper/WayfindingController.kt |  |
| `ec.guide.dir_s` | 南 | South | src/main/kotlin/net/nakaymi/etherealchronicle/paper/WayfindingController.kt |  |
| `ec.guide.dir_se` | 南東 | Southeast | src/main/kotlin/net/nakaymi/etherealchronicle/paper/WayfindingController.kt |  |
| `ec.guide.dir_sw` | 南西 | Southwest | src/main/kotlin/net/nakaymi/etherealchronicle/paper/WayfindingController.kt |  |
| `ec.guide.dir_w` | 西 | West | src/main/kotlin/net/nakaymi/etherealchronicle/paper/WayfindingController.kt |  |
| `ec.guide.header` | <gold>━━━━ 冒険ガイド (/ec guide) ━━━━ | <gold>━━━━ Adventure Guide (/ec guide) ━━━━ | src/main/kotlin/net/nakaymi/etherealchronicle/paper/WayfindingController.kt |  |
| `ec.guide.location_line` | <aqua>場所:</aqua> <gold><arrow></gold> <direction> 約<distance>m <gray>(X:<x> Y:<y> Z:<z>) | <aqua>Destination:</aqua> <gold><arrow></gold> <direction> ~<distance>m <gray>(X:<x> Y:<y> Z:<z>) | src/main/kotlin/net/nakaymi/etherealchronicle/paper/WayfindingController.kt | `<arrow>`, `<direction>`, `<distance>`, `<x>`, `<y>`, `<z>` |
| `ec.guide.next_available` | <green>次のおすすめ依頼:</green> <white><name> | <green>Recommended Next:</green> <white><name> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/WayfindingController.kt | `<name>` |
| `ec.guide.no_target` | <gray>現在進行中または受注可能な依頼はありません。街のNPCに話しかけてみましょう。 | <gray>No active or recommended quests right now. Try talking to NPCs in town. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/WayfindingController.kt |  |
| `ec.guide.objective_line` | <yellow>目標:</yellow> <white><objective> (<progress>/<amount>) | <yellow>Objective:</yellow> <white><objective> (<progress>/<amount>) | src/main/kotlin/net/nakaymi/etherealchronicle/paper/WayfindingController.kt | `<amount>`, `<objective>`, `<progress>` |
| `ec.guide.scoreboard_area` | 現在地: <area> | Area: <area> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/WayfindingController.kt | `<area>` |
| `ec.guide.scoreboard_controls` | /ec guide で道案内 | Use /ec guide | src/main/kotlin/net/nakaymi/etherealchronicle/paper/WayfindingController.kt |  |
| `ec.guide.scoreboard_title` | <gold>Ethereal Chronicle | <gold>Ethereal Chronicle | src/main/kotlin/net/nakaymi/etherealchronicle/paper/WayfindingController.kt |  |
| `ec.guide.trail_hint` | <gold>✦ 目的地方向へ光の道しるべを表示しました。 | <gold>✦ Illuminated a golden trail toward your destination. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/WayfindingController.kt |  |
| `ec.hud.main` | HP <hp>/<max_hp>  <resource_name> <resource>/<max_resource>  <skill> CD <cooldown>秒  仲間 <party>人 | HP <hp>/<max_hp>  <resource_name> <resource>/<max_resource>  <skill> CD <cooldown>s  Party <party> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt | `<cooldown>`, `<hp>`, `<max_hp>`, `<max_resource>`, `<party>`, `<resource>`, `<resource_name>`, `<skill>` |
| `ec.item.cannot_drop` | このアイテムは手放せません。 | You cannot drop this item. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/AdventureController.kt |  |
| `ec.item.required_level` | 必要レベル: <level> / <rarity> | Required level: <level> / <rarity> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt | `<level>`, `<rarity>` |
| `ec.life.fishing` | 釣り | Fishing | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.life.fishing_bait_help` | /ec fish bait <item_id\|auto\|none> | /ec fish bait <item_id\|auto\|none> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.life.fishing_status` | 釣り Lv.<level> / 経験値 <xp>/<required> / スコア <score> / 発見 <found>/<total>種 / 餌 <bait> | Fishing Lv. <level> / XP <xp>/<required> / Score <score> / Discovered <found>/<total> / Bait <bait> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt | `<bait>`, `<found>`, `<level>`, `<required>`, `<score>`, `<total>`, `<xp>` |
| `ec.life.gathering` | 採取 | Gathering | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.life.gathering_status` | 採取 Lv.<level> / 経験値 <xp>/<required> / 累計 <count>回 / 発見 <found>/<total>種 | Gathering Lv. <level> / XP <xp>/<required> / Total <count> / Discovered <found>/<total> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt | `<count>`, `<found>`, `<level>`, `<required>`, `<total>`, `<xp>` |
| `ec.mailbox.inventory_full` | 報酬を保管しています。持ち物に空きを作り /ec claim で受け取ってください。 | Your reward is stored. Make room and use /ec claim to receive it. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/AdventureController.kt |  |
| `ec.mailbox.save_retry` | 受取内容を保存できませんでした。/ec claim で再試行してください。 | Could not save the claim. Retry with /ec claim. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/AdventureController.kt |  |
| `ec.menu.codex_area` | 冒険者の図鑑 — 魔物 | Adventurer Codex — Enemies | src/main/kotlin/net/nakaymi/etherealchronicle/paper/CodexController.kt |  |
| `ec.menu.codex_areas` | 冒険者の図鑑 — エリア | Adventurer Codex — Areas | src/main/kotlin/net/nakaymi/etherealchronicle/paper/CodexController.kt |  |
| `ec.menu.codex_fish` | 生活図鑑 — 魚類 | Life Codex — Fish | src/main/kotlin/net/nakaymi/etherealchronicle/paper/CodexController.kt |  |
| `ec.menu.codex_gathering` | 生活図鑑 — 採取 | Life Codex — Gathering | src/main/kotlin/net/nakaymi/etherealchronicle/paper/CodexController.kt |  |
| `ec.menu.codex_items` | 冒険者の図鑑 — アイテム | Adventurer Codex — Items | src/main/kotlin/net/nakaymi/etherealchronicle/paper/CodexController.kt |  |
| `ec.menu.dialogue` | 会話 | Dialogue | src/main/kotlin/net/nakaymi/etherealchronicle/paper/AdventureController.kt |  |
| `ec.menu.enhance` | 装備強化 | Enhance Gear | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.menu.equipment` | 装備 | Equipment | src/main/kotlin/net/nakaymi/etherealchronicle/paper/AdventureController.kt |  |
| `ec.menu.shop` | お店 | Shop | src/main/kotlin/net/nakaymi/etherealchronicle/paper/AdventureController.kt |  |
| `ec.party.invite_received` | <name> からパーティー招待です。/ec party accept（60秒以内） | <name> invited you to a party. Use /ec party accept within 60 seconds. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt | `<name>` |
| `ec.party.invite_sent` | <name> さんへ招待を送りました。 | Invitation sent to <name>. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt | `<name>` |
| `ec.party.joined` | パーティーに参加しました。 | You joined the party. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.party.left` | パーティーを離れました。 | You left the party. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.party.members` | 仲間: <members> | Party: <members> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt | `<members>` |
| `ec.party.offline` | 離席中 | Offline | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.profile.class_data_missing` | 職業データが見つかりません。管理者にご連絡ください。 | Your class data could not be found. Please contact an administrator. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.profile.inventory_space` | 持ち物に空きを作ってください。 | Make room in your inventory. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.profile.read_failed` | 冒険データを読み込めませんでした。管理者にご連絡ください。 | Could not load your adventure data. Please contact an administrator. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.profile.save_retry` | 保存できませんでした。もう一度お試しください。 | Could not save your profile. Please try again. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.profile.skill_slot` | スキルスロット | Skill Slot | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.profile.skill_slot_space` | 9番のアイテムを空きに移して持ち物を閉じてください。保護品は地面に捨てません。 | Move the item from hotbar slot 9 and close your inventory. Protected items will not be dropped. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.profile.starter_space` | 持ち物の空きがなくなりました。管理者にスターター装備の再付与を依頼してください。 | Your inventory filled up. Ask an administrator to restore your starter gear. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.profile.welcome` | ようこそ！受付嬢セリアに話しかけて冒険を始めましょう。 | Welcome! Talk to Celia at the reception desk to begin your adventure. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.quest.accepted` | 依頼を受注しました。 | Quest accepted. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.quest.completed` | 依頼を達成しました！ | Quest completed! | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.quest.incomplete_hint` | <yellow>未達成の目標:</yellow> <objective> (<progress>/<amount>) | <yellow>Remaining objective:</yellow> <objective> (<progress>/<amount>) | src/main/kotlin/net/nakaymi/etherealchronicle/paper/WayfindingController.kt | `<amount>`, `<objective>`, `<progress>` |
| `ec.quest.name` | <name> | <name> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt | `<name>` |
| `ec.quest.none_active` | 受注中の依頼はありません。 | You have no active quests. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.quest.objective_progress` | ・<target> <progress>/<amount> | - <target> <progress>/<amount> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt | `<amount>`, `<progress>`, `<target>` |
| `ec.quest.progress_update` | <gold>[依頼進行]</gold> <name>: <objective> (<progress>/<amount>) | <gold>[Quest]</gold> <name>: <objective> (<progress>/<amount>) | src/main/kotlin/net/nakaymi/etherealchronicle/paper/WayfindingController.kt | `<amount>`, `<name>`, `<objective>`, `<progress>` |
| `ec.quest.ready_notice` | <green>[報告可能]</green> <name> の条件達成！ <yellow><npc></yellow> に話しかけて報告しましょう。 | <green>[Ready to Report]</green> <name> objectives complete! Talk to <yellow><npc></yellow>. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/WayfindingController.kt | `<name>`, `<npc>` |
| `ec.quest.status` | 状態: <status> | Status: <status> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt | `<status>` |
| `ec.quest.status_active` | 進行中 | In progress | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.quest.status_complete` | 達成済み | Complete | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.quest.status_ready` | 報告できます | Ready to report | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.quest.unknown_name` | 依頼名不明 | Unknown quest | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.quest.unknown_target` | 不明な目標 | Unknown objective | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt<br>src/main/kotlin/net/nakaymi/etherealchronicle/paper/WayfindingController.kt |  |
| `ec.quest.verb_accept` | <target>から次の依頼を受ける | Accept next quest from <target> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/WayfindingController.kt | `<target>` |
| `ec.quest.verb_collect` | <target>を集める | Collect <target> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/WayfindingController.kt | `<target>` |
| `ec.quest.verb_enhance` | 装備を強化する（<target>） | Enhance gear (<target>) | src/main/kotlin/net/nakaymi/etherealchronicle/paper/WayfindingController.kt | `<target>` |
| `ec.quest.verb_explore` | <target>を探索する | Explore <target> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/WayfindingController.kt | `<target>` |
| `ec.quest.verb_fish` | <target>を釣る | Catch <target> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/WayfindingController.kt | `<target>` |
| `ec.quest.verb_gather` | <target>を採取する | Gather <target> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/WayfindingController.kt | `<target>` |
| `ec.quest.verb_kill` | <target>を討伐する | Defeat <target> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/WayfindingController.kt | `<target>` |
| `ec.quest.verb_report` | <target>に報告する | Report to <target> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/WayfindingController.kt | `<target>` |
| `ec.quest.verb_talk` | <target>に話しかける | Talk to <target> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/WayfindingController.kt | `<target>` |
| `ec.rarity.common` | コモン | Common | src/main/kotlin/net/nakaymi/etherealchronicle/core/Game.kt |  |
| `ec.rarity.epic` | エピック | Epic | src/main/kotlin/net/nakaymi/etherealchronicle/core/Game.kt |  |
| `ec.rarity.legendary` | レジェンダリー | Legendary | src/main/kotlin/net/nakaymi/etherealchronicle/core/Game.kt |  |
| `ec.rarity.rare` | レア | Rare | src/main/kotlin/net/nakaymi/etherealchronicle/core/Game.kt |  |
| `ec.rarity.uncommon` | アンコモン | Uncommon | src/main/kotlin/net/nakaymi/etherealchronicle/core/Game.kt |  |
| `ec.resource.focus` | 集中 | Focus | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.resource.mana` | マナ | Mana | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.resource.rage` | 怒り | Rage | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.resource_pack.external_managed` | リソースパックの配信は外部管理です。表示設定は配信側プラグインで変更してください。 | Resource pack delivery is managed externally. Change pack preferences in the delivery plugin. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.resource_pack.pack_info` | 同梱リソースパック: SHA-1 <sha1> / <size> bytes | Bundled resource pack: SHA-1 <sha1> / <size> bytes | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt | `<sha1>`, `<size>` |
| `ec.resource_pack.pack_info_missing` | 生成済みリソースパックがプラグイン内にありません。Gradleの resourcePackSha1 タスクを実行して再ビルドしてください。 | The generated resource pack is missing from the plugin jar. Run the Gradle resourcePackSha1 task and rebuild. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.resource_pack.pack_info_usage` | 使用方法: /ec pack info | Usage: /ec pack info | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.resource_pack.preference_auto` | 自動 | automatic | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.resource_pack.preference_off` | オフ | off | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.resource_pack.preference_on` | オン | on | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.resource_pack.preference_set` | リソースパック表示設定を <preference> にしました。 | Resource pack display is now <preference>. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt | `<preference>` |
| `ec.resource_pack.prompt` | <gold>EtherealChronicle 専用リソースパックを適用しますか？<newline><gray>未適用でもプレイ可能です。 | <gold>Apply the EtherealChronicle resource pack?<newline><gray>You can play without it. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/ResourcePackController.kt |  |
| `ec.resource_pack.required_apply_failed` | リソースパックを適用できませんでした。クライアントを再起動して再接続してください。 | The resource pack could not be applied. Restart your client and reconnect. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/ResourcePackController.kt |  |
| `ec.resource_pack.required_declined` | このサーバーはリソースパックが必須です。サーバーリストの設定で「サーバーリソースパック: 有効」にして再接続してください。 | This server requires its resource pack. Enable Server Resource Packs in the server list settings, then reconnect. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/ResourcePackController.kt |  |
| `ec.resource_pack.required_download_failed` | リソースパックを取得できませんでした。サーバーリストの設定と通信環境を確認して再接続してください。 | The resource pack could not be downloaded. Check the server list setting and network connection, then reconnect. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/ResourcePackController.kt |  |
| `ec.resource_pack.required_prompt` | <gold>このサーバーでは専用リソースパックが必須です。<newline><yellow>サーバーリストの設定で「サーバーリソースパック」を有効にして再接続してください。 | <gold>This server requires the EtherealChronicle resource pack.<newline><yellow>Enable Server Resource Packs in the server list settings, then reconnect. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/ResourcePackController.kt |  |
| `ec.resource_pack.required_timeout` | 制限時間内にリソースパックを適用できなかったため、接続を終了しました。サーバーリストの設定を確認して再接続してください。 | The resource pack was not applied in time, so the connection was closed. Check the server list setting and reconnect. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/ResourcePackController.kt |  |
| `ec.resource_pack.required_unavailable` | 必須リソースパックを配信できないため、必須設定を一時的に無効化しました。配信設定と起動ログを確認してください。 | The required resource pack has no working delivery endpoint, so enforcement is temporarily disabled. Check the distribution settings and startup log. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/ResourcePackController.kt |  |
| `ec.resource_pack.usage` | 使用方法: /ec ui pack <auto\|on\|off> | Usage: /ec ui pack <auto\|on\|off> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.resource_pack.waiting` | 必須リソースパックを適用しています。完了するまでゲーム操作をお待ちください。 | Applying the required resource pack. Gameplay is paused until it finishes. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/ResourcePackController.kt |  |
| `ec.shop.currency` | 所持金: <currency>G | Currency: <currency>G | src/main/kotlin/net/nakaymi/etherealchronicle/paper/AdventureController.kt | `<currency>` |
| `ec.shop.price_buy` | 価格 <price>G / 左クリックで購入 | Price: <price>G / Left-click to buy | src/main/kotlin/net/nakaymi/etherealchronicle/paper/AdventureController.kt | `<price>` |
| `ec.shop.sell_hint` | 売却: 持ち物の品を右クリック | Sell: right-click an item in your inventory | src/main/kotlin/net/nakaymi/etherealchronicle/paper/AdventureController.kt |  |
| `ec.shop.tokens` | 素材: <items> | Materials: <items> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/AdventureController.kt | `<items>` |
| `ec.sim.cast` | スキル結果: <result> | Skill result: <result> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/SimulationCommandController.kt | `<result>` |
| `ec.sim.choice` | 会話: <conversation> / 効果: <effects> | Dialogue: <conversation> / Effects: <effects> | src/main/kotlin/net/nakaymi/etherealchronicle/core/SimulationSession.kt | `<conversation>`, `<effects>` |
| `ec.sim.currency` | 所持金を <amount> に設定しました。 | Set currency to <amount>. | src/main/kotlin/net/nakaymi/etherealchronicle/core/SimulationSession.kt | `<amount>` |
| `ec.sim.despawned` | 仮想魔物とチャンク保持を解除しました。 | Removed simulated enemy and chunk ticket. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/SimulationCommandController.kt |  |
| `ec.sim.dialogue` | 会話 <node>: <text> / 選択肢: <choices> | Dialogue <node>: <text> / Choices: <choices> | src/main/kotlin/net/nakaymi/etherealchronicle/core/SimulationSession.kt | `<choices>`, `<node>`, `<text>` |
| `ec.sim.hit` | 仮想攻撃: HP=<hp> / 討伐=<killed> / フェーズ=<phase>。実プレイヤー報酬は発生しません。 | Simulated hit: HP=<hp> / killed=<killed> / phase=<phase>. No real player rewards are issued. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/SimulationCommandController.kt | `<hp>`, `<killed>`, `<phase>` |
| `ec.sim.level` | レベルを <level> に設定しました。 | Set level to <level>. | src/main/kotlin/net/nakaymi/etherealchronicle/core/SimulationSession.kt | `<level>` |
| `ec.sim.loaded` | 仮想プロフィール <name> を読み込みました。仮想持ち物はリセットされました。 | Loaded virtual profile <name>. Virtual inventory was reset. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/SimulationCommandController.kt | `<name>` |
| `ec.sim.loot` | 戦利品: <items> | Loot: <items> | src/main/kotlin/net/nakaymi/etherealchronicle/core/SimulationSession.kt | `<items>` |
| `ec.sim.operation` | SIM <operation> <name>: <profile> | SIM <operation> <name>: <profile> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/SimulationCommandController.kt | `<name>`, `<operation>`, `<profile>` |
| `ec.sim.party` | 仮想パーティー: <members> | Virtual party: <members> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/SimulationCommandController.kt | `<members>` |
| `ec.sim.party_split` | パーティー経験値配分: <shares> | Party XP split: <shares> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/SimulationCommandController.kt | `<shares>` |
| `ec.sim.quest_completed` | 依頼を達成しました: <quest> | Completed quest: <quest> | src/main/kotlin/net/nakaymi/etherealchronicle/core/SimulationSession.kt | `<quest>` |
| `ec.sim.quest_started` | 依頼を受注しました: <quest> | Started quest: <quest> | src/main/kotlin/net/nakaymi/etherealchronicle/core/SimulationSession.kt | `<quest>` |
| `ec.sim.result` | 結果: <result> | Result: <result> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/SimulationCommandController.kt | `<result>` |
| `ec.sim.saved` | 仮想プロフィール <name> を保存しました。 | Saved virtual profile <name>. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/SimulationCommandController.kt | `<name>` |
| `ec.sim.spawn` | 仮想出現 UUID=<uuid> HP=<hp> | Simulated spawn UUID=<uuid> HP=<hp> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/SimulationCommandController.kt | `<hp>`, `<uuid>` |
| `ec.sim.transaction` | 取引 <id> / 所持金: <currency> / 持ち物: <inventory> | Transaction <id> / Currency: <currency> / Inventory: <inventory> | src/main/kotlin/net/nakaymi/etherealchronicle/core/SimulationSession.kt | `<currency>`, `<id>`, `<inventory>` |
| `ec.sites.cleanup_acknowledging` | 手動片付けの確認を記録しています。完了後に /ec sites repair を実行してください。 | Recording manual cleanup confirmation. Run /ec sites repair after it completes. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/SimulationCommandController.kt |  |
| `ec.sites.failed_position` | 座標未確定（生成失敗） | Position unresolved (failed) | src/main/kotlin/net/nakaymi/etherealchronicle/paper/SimulationCommandController.kt |  |
| `ec.sites.pending_position` | 座標未確定 | Position pending | src/main/kotlin/net/nakaymi/etherealchronicle/paper/SimulationCommandController.kt |  |
| `ec.sites.portal` | <id>: 入口 <entrance> / 退出 <exit> | <id>: entrance <entrance> / exit <exit> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/SimulationCommandController.kt | `<entrance>`, `<exit>`, `<id>` |
| `ec.sites.repair_started` | 拠点の修復を開始しました。進行状況は /ec sites で確認できます。 | Site repair started. Use /ec sites to check progress. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/SimulationCommandController.kt |  |
| `ec.sites.repair_unavailable` | 拠点の修復を開始できません。中断地点を確認し、必要なら手動で片付けてください。 | Site repair could not start. Inspect the interrupted site and clean it up manually if needed. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/SimulationCommandController.kt |  |
| `ec.sites.site` | <id>: <position> / <biome> / <status> | <id>: <position> / <biome> / <status> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/SimulationCommandController.kt | `<biome>`, `<id>`, `<position>`, `<status>` |
| `ec.sites.status_failed` | 失敗（要確認） | Failed (needs inspection) | src/main/kotlin/net/nakaymi/etherealchronicle/paper/SimulationCommandController.kt |  |
| `ec.sites.status_generating` | 建設中 | Building | src/main/kotlin/net/nakaymi/etherealchronicle/paper/SimulationCommandController.kt |  |
| `ec.sites.status_pending` | 待機中 | Pending | src/main/kotlin/net/nakaymi/etherealchronicle/paper/SimulationCommandController.kt |  |
| `ec.sites.status_ready` | 完成 | Ready | src/main/kotlin/net/nakaymi/etherealchronicle/paper/SimulationCommandController.kt |  |
| `ec.sites.travel_success` | <name>へ移動しました。 | Traveled to <name>. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/SimulationCommandController.kt | `<name>` |
| `ec.sites.travel_usage` | 使用法: /ec travel <areaId> | Usage: /ec travel <areaId> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt | `<areaId>` |
| `ec.sites.unknown_biome` | 未確定 | Unknown | src/main/kotlin/net/nakaymi/etherealchronicle/paper/SimulationCommandController.kt |  |
| `ec.sites.usage` | 使用法: /ec sites [repair\|acknowledge-cleanup <areaId>] | Usage: /ec sites [repair\|acknowledge-cleanup <areaId>] | src/main/kotlin/net/nakaymi/etherealchronicle/paper/SimulationCommandController.kt | `<areaId>` |
| `ec.sites.usage_cleanup` | 使用法: /ec sites acknowledge-cleanup <areaId> | Usage: /ec sites acknowledge-cleanup <areaId> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/SimulationCommandController.kt | `<areaId>` |
| `ec.sites.usage_repair` | 使用法: /ec sites repair | Usage: /ec sites repair | src/main/kotlin/net/nakaymi/etherealchronicle/paper/SimulationCommandController.kt |  |
| `ec.skill.cooldown` | まだ使用できません。 | That skill is not ready yet. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.skill.obstructed` | 魔物との間に障害物があります。 | An obstacle blocks your line to the enemy. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.skill.resource_low` | リソースが足りません。 | Not enough resource. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.skill.selected` | 選択: <skill> | Selected: <skill> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt | `<skill>` |
| `ec.skill.target_missing` | 射程内に魔物がいません。 | There is no enemy in range. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.skill.weapon_requirements` | この装備の条件を満たしていません。 | You do not meet this equipment's requirements. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.skill_slot.lore_cast` | <aqua>武器を持って右クリック: スキル発動 | <aqua>Right-click with weapon: Cast Skill | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.skill_slot.lore_cycle` | <yellow>Fキー / しゃがみ＋右クリック: スキル切替 | <yellow>F key / Sneak+Right-click: Cycle Skill | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.skill_slot.lore_guide` | <green>この星を右クリック: 次の目的地へ道案内 | <green>Right-click this star: Show Waypoint | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.startup.loading` | 冒険データを読み込んでいます… | Loading your adventure data… | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.startup.starting` | 冒険システムは起動中です。しばらくお待ちください。 | The adventure system is starting. Please wait. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.title.cleared` | 称号表示を解除しました。 | Displayed title cleared. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.title.equipped` | 称号を装備しました: <name> | Title equipped: <name> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt | `<name>` |
| `ec.title.equipped_mark` |  [装備中] |  [Equipped] | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.title.help` | /ec title <称号ID\|none> で表示を切り替えます。 | Use /ec title <title_id\|none> to change your displayed title. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.title.list_entry` | <status> <name><equipped> | <status> <name><equipped> | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt | `<equipped>`, `<name>`, `<status>` |
| `ec.title.locked` | 称号をまだ解放していません。 | You have not unlocked that title yet. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.treasure.busy` | 処理中です。少し待ってください。 | An operation is in progress. Please wait. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/TreasureController.kt |  |
| `ec.treasure.empty` | この宝箱は空です。 | This treasure chest is empty. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/TreasureController.kt |  |
| `ec.treasure.failed` | 宝箱を開けられません。 | Could not open the treasure chest. | src/main/kotlin/net/nakaymi/etherealchronicle/paper/TreasureController.kt |  |
| `ec.treasure.opened` | 隠し宝箱を開けました。通貨 +<currency>G | Opened a hidden treasure chest. Currency +<currency>G | src/main/kotlin/net/nakaymi/etherealchronicle/paper/TreasureController.kt | `<currency>` |
| `ec.tutorial.controls` | <gold>[操作ガイド]</gold> <white>左クリック: 通常攻撃（弓・杖は遠距離） / 右クリック: スキル発動 / Fキー: スキル切替 / 9番の星を右クリック or /ec guide: 道案内 | <gold>[Controls]</gold> <white>Left-click: Basic Attack (Bows/Wands shoot ranged) \| Right-click weapon: Cast Skill \| F key: Cycle Skill \| Right-click Slot 9 Star or /ec guide: Waypoint | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |

## コンテンツYAMLの多言語化案
クエスト名やアイテム名などの `name` はコンテンツ表示データです。英語用の名前が未定義なら、現行仕様では日本語名をそのまま表示します。状態ラベルや説明文などのUI文言は言語カタログから選びます。

将来は `name_key` / `description_key` を追加し、同じ `ec.*` 翻訳表を参照します。既存の `name` / `description` は後方互換のフォールバックとして維持し、コンテンツschemaと保存形式を一括変更しない段階導入にします。
