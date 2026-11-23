package net.nakaymi.etherealchronicle.paper

import net.nakaymi.etherealchronicle.core.OverworldSiteRegistry

/** オーバーワールド建設処理をプラグイン入口から切り離す接続口です。 */
interface OverworldBootstrapPort : AutoCloseable {
    val registry: OverworldSiteRegistry

    /** 建設を開始し、配置情報の変化を通知します。通知は任意のスレッドで発生し得ます。 */
    fun start(onAnchorsChanged: () -> Unit)

    /** 建設処理を停止し、所有するリソースを解放します。 */
    override fun close()
}
