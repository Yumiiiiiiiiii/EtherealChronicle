# リソースパックの配布

## 推奨: 外部 HTTPS 配布

公開サーバーでは GitHub Releases、オブジェクトストレージ、または自分の HTTPS サイトを使います。別ポートの開放やゲーム通信への介入が不要です。

1. `./gradlew packageResourcePack resourcePackSha1` を実行します。`resourcePackSha1` はZIPを生成し、Gradle出力にSHA-1も表示します。
2. `build/generated/resourcepack/etherealchronicle-resourcepack.zip` を HTTPS で取得できる場所へアップロードします。
3. 同じ出力ディレクトリの `.sha1` を読み、SHA-1 値を控えます。
4. `plugins/EtherealChronicle/config.yml` を次のように設定し、サーバーを再起動します。

```yaml
resource_pack:
  enabled: true
  required: true
  mode: external_url
  external_url: "https://downloads.example.net/etherealchronicle-resourcepack.zip"
  external_sha1: "生成された40桁のSHA-1"
```

リソースパックは標準設定で必須です。外部 HTTPS 配布では `external_url` と、配布したZIPと一致する40桁の `external_sha1` の両方を設定してください。必須リクエストを拒否・ダウンロード失敗したプレイヤーには、再接続設定の案内が表示されます。

初回ログインでも、プロフィールの非同期読み込みや職業選択GUIより先に必須要求を送ります。Paper/クライアントの標準接続フロー上、拒否時はクライアントが先に接続を切り、サーバーが指定した日本語の切断理由が表示されないことがあります。必須化するサーバーはサーバーリストのMOTDにも「サーバーリソースパックを有効にして再接続」と案内してください。

安全のため同梱 `config.yml` の `game_port` 注入はオプトインのままで、外部URLも初期値は空です。そのままの初回起動では配信先がないため、必須化が警告付きで一時解除されます。公開サーバーで必須にするには、上記HTTPS設定を行うか、実験的な同一ポート配信を明示的に有効化してください。

配信ZIPがJARに含まれない、URL/SHA-1が不足している、HTTPサーバーの起動や実験的なゲームポート注入に失敗した場合は、誤って全員を締め出さないよう必須化を一時解除し、日本語または設定言語で起動警告を出します。配信設定を直し、サーバーを再起動してください。運用・QA担当者には権限 `etherealchronicle.pack.bypass`（既定はOPのみ）を付与すると、拒否しても接続できます。

同梱パックのSHA-1が変わると、次回接続時に新しいSHA-1を要求へ含め、クライアントのキャッシュ更新判定を促します。要求IDは他プラグインのパックを置き換えないよう固定しています。`/ec ui pack off` はユーザー設定として保存されますが、必須配信が利用可能な場合は必須リクエストが優先されます。

ゲーム内で `/ec ui pack on` を使うと、現在オンラインのプレイヤーへ再送信できます。既定の `AUTO` は初回参加時に送信し、各プレイヤーの選択はプロフィールに保存されます。

## 実験的: ゲームポート同一ポート配信

`game_port` は Paper 26.3 の内部 `ChannelInitializeListenerHolder` をリフレクションで使い、新規 TCP 接続の最初のバイトが `GET ` または `HEAD ` の場合だけ固定パスの HTTP 配信に切り替えます。その他のバイト列は Minecraft の Netty パイプラインへ戻します。これは Paper 内部 API への依存があり、Paper の更新で壊れる可能性があります。既定では無効です。

有効化する場合は、バンドル済み ZIP を使う開発・検証サーバーに限り、以下を指定します。

```yaml
resource_pack:
  enabled: true
  mode: game_port
  same_port_netty:
    enabled: true
    max_concurrent: 16
    max_per_ip: 2
    max_header_bytes: 4096
    header_deadline_ms: 2000
    idle_timeout_ms: 500
    bandwidth_kb_per_sec: 1024
```

DNS名が `server.properties` の `server-ip` と異なる場合は、`resource_pack.embedded_http.public_host` に外部から解決できるホスト名を指定してください。通常のゲームポートだけを開放します。Velocity、BungeeCord、Waterfall、TCPShield 等のプロキシプラグインが検出された場合は同一ポート機能を無効にし、`external_url` があれば HTTPS へフォールバックします。

固定パス以外、過大ヘッダー、期限超過、同一IP接続上限超過は接続を閉じます。HTTP読み取りにはアイドル期限と全体期限を設けています。注入や要求処理に失敗した場合も Minecraft の接続パイプラインには要求を渡さず、機能を無効にします。

## モード一覧

| モード | 用途 | ポート |
| --- | --- | --- |
| `external_url` | 公開サーバー向け推奨 | 外部 HTTPS |
| `embedded_http` | 開発・検証用。別ポートを設定可能 | `embedded_http.port` |
| `game_port` | 実験的な同一ポート配信。明示的な二重 opt-in が必要 | Minecraft ゲームポート |
| `external_managed` | ForcePack、ResourcePackManager、server.properties等が送信・必須化を担当。当プラグインは状態監視だけ | 管理側の配信方式 |

`external_url` が設定されていれば、同一ポート機能の起動に失敗したときの配布先にもなります。

`/ec reload` は `config.yml` も再読込し、`ui.theme.mode` を以後開くGUIへ適用します。すでに開いている画面は自動更新されないため、閉じてから開き直してください。

## 推奨構成: 既存プラグイン管理・追加ポートなし

CIでPackSquash等を使ってパックを最適化し、GitHub ReleasesまたはCloudflare R2などの静的HTTPS URLに配置します。ForcePackを別プラグインとして導入し、URL・SHA-1・拒否時の必須化を管理させます。EtherealChronicle側は `external_managed` にすると、自前の `ResourcePackRequest` を送らず、外部配信結果だけを監視します。これでポート追加や二重プロンプトを避けられます。

ForcePack公式Paper設定例です。`packs.all` の `urls` と `hashes` は同じ順序・個数にし、生成したZIPのSHA-1を設定します。拒否・ダウンロード失敗等は `Actions` でキックし、`bypass-permission` を有効にすると `forcepack.bypass` を持つ運用担当者が回避できます。ForcePackの設定形式やステータス名は利用バージョンの生成済み `plugins/ForcePack/config.yml` と公式設定ページで再確認してください。

```yaml
# plugins/ForcePack/config.yml の該当箇所
Server:
  packs:
    all:
      urls: ["https://downloads.example.net/etherealchronicle-resourcepack.zip"]
      generate-hash: false
      hashes: ["生成ZIPのSHA-1"]
  Actions:
    ACCEPTED:
      kick: false
      Commands: []
    DOWNLOADED:
      kick: false
      Commands: []
    SUCCESSFULLY_LOADED:
      kick: false
      Commands: []
    DECLINED:
      kick: true
      Commands: []
    FAILED_DOWNLOAD:
      kick: true
      Commands: []
    FAILED_RELOAD:
      kick: true
      Commands: []
    DISCARDED:
      kick: true
      Commands: []
  bypass-permission: true
```

上記は公式Paper設定ファイルの階層に合わせた抜粋です。ForcePackは大文字小文字を区別する設定キー (`Server`, `Actions`, `Commands`) を使います。拒否時の切断を必須とするため `DECLINED`、`FAILED_DOWNLOAD`、`FAILED_RELOAD`、`DISCARDED` を `kick: true` にしています。`forcepack.bypass` は ForcePack の権限ノードで、公式設定の `Server.bypass-permission: true` がバイパス権限判定を有効にします。既定の権限割当は利用中の権限プラグインで確認してください。

このサーバー側は次のようにします。`expected_pack_id` を空にすると受け取ったリソースパック状態を監視し、UUIDを指定するとそのIDのイベントだけ反映します。UUIDが分かる管理構成では指定を推奨します。外部管理モードでは `/ec ui pack on|off` は配信要求を出さず、配信側プラグインで設定するよう案内します。`/ec pack info` は管理者向けにJAR同梱ZIPのSHA-1とサイズを表示します。

```yaml
resource_pack:
  enabled: true
  required: true # 必須化はForcePack側が担当。当プラグインは拒否者を切断しません。
  mode: external_managed
  expected_pack_id: "" # 任意UUID。空なら全pack statusイベントを監視
```

ForcePack公式Paper設定では `Server.packs.all.urls`, `generate-hash`, `hashes` を設定し、`Server.Actions` でstatusごとのkick/commandを設定します。バイパス権限は `forcepack.bypass`、設定は `Server.bypass-permission` です。[ForcePack公式設定](https://fortitude.islandearth.net/forcepack/configuration/)・[ForcePack公式設定ファイル](https://github.com/SamB440/ForcePack/blob/master/paper/src/main/resources/config.yml)。

外部ホストのZIPを更新したら、新しいSHA-1と配布URLをForcePack側へ反映し、ForcePackの再配信/リロード手順を使います。`/ec pack info` はEtherealChronicleのJARに同梱されたZIPを表示するため、マージ済みの総合パックを配布する場合はCIまたは配布プラグインが計算した最終ZIPのSHA-1を正としてください。

## BetterModel 連携

BetterModel は任意依存です。Paper のソフト依存として先に読み込まれますが、導入しなくてもバニラ/`wolf_variant` の見た目で動作します。BetterModel と対応するクライアント用モデル・リソースパックを別途導入し、設定後に再起動してください。

```yaml
bettermodel:
  enabled: true
  entity_models:
    "ec:enemy_thorn_wolf": "thorn_wolf"
    "ec:boss_ashwood_alpha": "ashwood_alpha"
```

`entity_models` のキーは魔物・ボスの安定ID、値は BetterModel 側のモデル名です。登録されていないモデル、API互換性エラー、プラグイン未導入の場合は警告を一度出し、通常の魔物表示を維持します。連携は BetterModel の公開 renderer/adapter API を反射で呼び出すため、BetterModel のAPI更新後は再検証してください。
