# 実機 E2E（scripts/e2e-overworld.ps1）

使い捨てサーバー `dev/e2e-server`（ポート 25599）を起動し、全 9 拠点の READY を待ってから、mineflayer ボットで主要フローを自動検証します。

```powershell
./scripts/e2e-overworld.ps1 -Clean            # 新規ワールドで街生成まで確認（ビルド含む）
./scripts/e2e-overworld.ps1 -SkipBuild -Via   # 既存ワールド + ViaProxy 経由でボット検証
```

| オプション | 内容 |
| --- | --- |
| `-Clean` | `dev/e2e-server` を作り直す（このディレクトリのみ削除） |
| `-SkipBuild` | ビルドを省略して `build/libs` の最新 JAR を使う |
| `-Bot` | ボット検証（対象サーバーのプロトコルに対応した mineflayer が必要） |
| `-Via` | ViaProxy 経由で接続（`-Bot` を含む）。26.1 のボットが 26.3 サーバーへ入る |

## サーバー内自己診断（`/ec selftest`）
管理者権限またはコンソールから実行でき、プレイヤー無しで実サーバーの前提を検査します（E2E でも自動実行）。
- 全拠点が READY / 全ダンジョンに入口ポータルがある
- ダンジョンアリーナのスキーマが存在し、全ブロック状態が現行サーバーで有効
- NPC・敵スポーン・宝箱・採集地点・釣り場（約 100 地点）が地表へ投影できる（チャンクを非同期で順に読み込む）
- 各ポータルの出口が安全な足場
結果はコンソールと、ログの `EC_SELFTEST_PASS` / `EC_SELFTEST_FAIL` に出ます。

## ボットの検証項目
1. 職業設定と初期武器の配布
2. 拠点（ルミナ港・アッシュウッド・フロストピーク）への転送（遠方のチャンク未ロードでも成功）
3. 危険地帯での死亡: アイテムを保持し、ゴールドのみ失う（ゴールド 0 なら通知なし）
4. 入口から遠い場所での入場は「入口から遠すぎる」メッセージで拒否される
5. ノービスアリーナへの入退場（空中アリーナへ入り、入口の出口へ戻る）

## 前提・注意
- Java 25 / Node.js が必要。EULA は自動承認しません（`dev/server/eula.txt` が承認済みの場合のみコピー）。
- ViaProxy は `dev/tools/viaproxy/ViaProxy-3.4.14.jar` を使用（`gh release download v3.4.14 --repo ViaVersion/ViaProxy --pattern ViaProxy-3.4.14.jar --dir dev/tools/viaproxy`）。
- ViaProxy 経由の 26.1→26.3 変換では、ボットの移動パケットが「Invalid move player packet」になるため、ボットは移動パケットを送りません（位置はサーバーのテレポート命令で動く）。
- 街（安全地帯）ではダメージが無効のため、死亡検証は危険地帯で行います。
- ログは `scripts/logs/e2e-*.log` / `e2e-bot-*.log` / `e2e-via-*.log`。
