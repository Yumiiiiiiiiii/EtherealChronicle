package net.nakaymi.etherealchronicle.paper

import net.nakaymi.etherealchronicle.core.OverworldSiteRegistry

/** テスト専用: 建設なしで固定座標の拠点を返すレジストリを差し込む口です。本番では常に null です。 */
internal object TestWorldFixture {
    @Volatile var registryFactory: ((Collection<net.nakaymi.etherealchronicle.core.Area>, Collection<net.nakaymi.etherealchronicle.core.DungeonDefinition>) -> OverworldSiteRegistry)? = null
}

/** オーバーワールド建設処理をプラグイン入口から切り離す接続口です。 */
interface OverworldBootstrapPort : AutoCloseable {
    val registry: OverworldSiteRegistry

    /** 建設を開始し、配置情報の変化を通知します。通知は任意のスレッドで発生し得ます。 */
    fun start(onAnchorsChanged: () -> Unit)

    /** 建設処理を停止し、所有するリソースを解放します。 */
    override fun close()
}
