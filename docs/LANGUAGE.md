# 言語ファイルと翻訳キー

表示文言はMiniMessage形式です。`plugins/EtherealChronicle/lang/ja_jp.yml` と `en_us.yml` は初回配置後、サーバー編集を優先します。未編集の同梱ファイルだけ次の配布版へ更新します。

設定 `language: auto` はプレイヤーのクライアント言語に追従し、未対応言語は日本語を選びます。`ja_jp` / `en_us` を指定するとサーバー全体で固定します。存在しないキーは日本語、それも無い場合はキー名を表示し、警告はキーごとに一度だけ記録します。

| キー | 日本語 | English | 使用箇所 | プレースホルダ |
| --- | --- | --- | --- | --- |
| `ec.class.choose` | 職業を選んでください | Choose a class | src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt |  |
| `ec.codex.back_areas` | エリア一覧へ戻る | Back to Areas | src/main/kotlin/net/nakaymi/etherealchronicle/paper/CodexController.kt |  |
| `ec.codex.tab_fish` | 魚類図鑑 | Fish Codex | src/main/kotlin/net/nakaymi/etherealchronicle/paper/CodexController.kt |  |
| `ec.codex.tab_gathering` | 採取図鑑 | Gathering Codex | src/main/kotlin/net/nakaymi/etherealchronicle/paper/CodexController.kt |  |
| `ec.codex.tab_items` | 発見したアイテム | Discovered Items | src/main/kotlin/net/nakaymi/etherealchronicle/paper/CodexController.kt |  |
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
