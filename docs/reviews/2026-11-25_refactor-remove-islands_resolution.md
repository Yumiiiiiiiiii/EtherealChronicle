# refactor/remove-islands レビュー対応記録 (lead)

統合元: antigravity / codex のレビュー（同名ファイル）。実装者(claude)以外の 2 モデルがレビュー済み。

| ID | 重大度 | 対応 |
| --- | --- | --- |
| H-1 skip されていた QA 4 件 | High | 修正: 不要な `.etherealchronicle-built` 書込を削除。0 skip 化を確認 |
| H-2 偽陽性のダンジョン QA | High | 修正: `seedArenaSchematic` と実入場・実退場の検証を追加（D3 の無敵化も） |
| H-3 / codex Med 死亡ゴールド競合 | High | 修正: `profileTransaction` 経由・`ignoreCancelled`・`beforeQuit` で未適用罰を確定 |
| H-4 遠方チャンク未ロードで転送失敗 | High | 修正: `teleportArea` を `withChunk` 非同期化、RETURN は `requestHubSpawn` へフォールバック |
| M-2 旧設定値 | Med | 修正: 警告ログ・trim/lowercase・config コメント整理・fixture の AfterAll |
| M-3 ダンジョン schem 検証消失 | Med | 修正: `DungeonSchematicIntegrityTest` 追加 |
| M-4 旧文言 | Med | 修正: 言語文言・ログ・ヴァルカン台詞・RELEASE.md |
| L-1 デッドコード | Low | 一部修正: `SchematicPlacement`・`playerDeath` 削除 |
| M-1 ダンジョン死亡リスポーンの未ロード判定 | Med | **受容・次タスク**: 世界スポーンへ縮退するだけで安全。非同期化は別途 |
| L-2 死亡ペナルティ範囲 | Low | 受容: 全ワールド適用は仕様（要望どおり全額） |
| L-3/L-4/P-1 | Low/Polish | 受容・次タスク |
