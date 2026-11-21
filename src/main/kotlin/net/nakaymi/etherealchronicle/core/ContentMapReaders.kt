package net.nakaymi.etherealchronicle.core

import net.nakaymi.etherealchronicle.data.int
import net.nakaymi.etherealchronicle.data.long
import net.nakaymi.etherealchronicle.data.mapping
import net.nakaymi.etherealchronicle.data.num
import net.nakaymi.etherealchronicle.data.string
import net.nakaymi.etherealchronicle.data.strings

/** YAMLの任意値を文字列キーのマップとして扱います。型違いを空値にせず、定義元を検証エラーにします。 */
@Suppress("UNCHECKED_CAST")
fun Any?.asMapping(): Map<String, Any?> = this as? Map<String, Any?> ?: error("マッピングが必要です。")

internal fun Map<String, Any?>.mappingOrEmpty(key: String): Map<String, Any?> =
    if (key !in this) emptyMap() else mapping(key)

/** YAML内のマップ一覧を読み、要素ごとにマップ型を確認します。 */
fun Map<String, Any?>.maps(key: String): List<Map<String, Any?>> =
    if (key !in this) emptyList() else (this[key] as? List<*> ?: error("リストが必要です: $key")).map { it.asMapping() }

fun Map<String, Any?>.number(key: String, default: Double): Double = if (key in this) num(key) else default
fun Map<String, Any?>.integer(key: String, default: Int): Int = if (key in this) int(key) else default
fun Map<String, Any?>.whole(key: String, default: Long): Long = if (key in this) long(key) else default
fun Map<String, Any?>.text(key: String, default: String): String = this[key] as? String ?: default

fun Map<String, Any?>.bool(key: String, default: Boolean): Boolean =
    if (key in this) this[key] as? Boolean ?: error("真偽値が必要です: $key") else default

/** ワールド位置を読み取ります。yawが省略されたときは0度にします。 */
fun Map<String, Any?>.position(): Position = Position(string("world"), num("x"), num("y"), num("z"), number("yaw", 0.0).toFloat())

fun Map<String, Any?>.condition(): Condition =
    if ("condition" in this) mapping("condition").conditionValue() else Condition()

fun Map<String, Any?>.conditionValue(): Condition = Condition(
    integer("min_level", 1),
    strings("has_flags").toSet(),
    strings("not_flags").toSet(),
    strings("completed_quests").toSet(),
    strings("active_quests").toSet(),
    strings("not_started_quests").toSet(),
    strings("classes").toSet(),
    maps("any").map { it.conditionValue() },
)

fun Map<String, Any?>.rewards(): Rewards = Rewards(
    whole("xp", 0),
    whole("currency", 0),
    maps("items").map { ItemReward(it.string("item"), it.integer("amount", 1), it.condition()) },
    strings("set_flags").toSet(),
)

fun Map<String, Any?>.tokens(key: String): List<TokenCost> = maps(key).map { TokenCost(it.string("item"), it.int("amount")) }
