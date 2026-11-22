package net.nakaymi.etherealchronicle.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

/** Adventure Componentから書式を除いた表示文字列を共通の方法で取り出します。 */
internal fun Component.plainText(): String = PlainTextComponentSerializer.plainText().serialize(this)

/** サーバー内だけで使う経過時間を、時計補正の影響を受けない単調時刻で表します。 */
internal fun monotonicMillis(): Long = System.nanoTime() / 1_000_000
