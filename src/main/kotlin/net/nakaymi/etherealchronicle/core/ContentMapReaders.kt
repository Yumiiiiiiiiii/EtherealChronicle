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

/** 未指定時だけ空マップを返し、指定値が不正なら検証を失敗させます。 */
internal fun Map<String, Any?>.mappingOrEmpty(key: String): Map<String, Any?> =
    if (key !in this) emptyMap() else mapping(key)

/** YAML内のマップ一覧を読み、要素ごとにマップ型を確認します。 */
fun Map<String, Any?>.maps(key: String): List<Map<String, Any?>> =
    if (key !in this) emptyList() else (this[key] as? List<*> ?: error("リストが必要です: $key")).map { it.asMapping() }

/** 指定数値を読み、省略時は明示した既定値を返します。 */
fun Map<String, Any?>.number(key: String, default: Double): Double = if (key in this) num(key) else default
/** 指定整数を読み、省略時は明示した既定値を返します。 */
fun Map<String, Any?>.integer(key: String, default: Int): Int = if (key in this) int(key) else default
/** 指定Long値を読み、省略時は明示した既定値を返します。 */
fun Map<String, Any?>.whole(key: String, default: Long): Long = if (key in this) long(key) else default
/** 指定文字列を読み、省略または文字列以外なら既定値を返します。 */
fun Map<String, Any?>.text(key: String, default: String): String = this[key] as? String ?: default

/** 真偽値を読み、存在するキーの型違いはデータエラーとして扱います。 */
fun Map<String, Any?>.bool(key: String, default: Boolean): Boolean =
    if (key in this) this[key] as? Boolean ?: error("真偽値が必要です: $key") else default

/** ワールド位置を読み取ります。yawが省略されたときは0度にします。 */
fun Map<String, Any?>.position(): Position = Position(string("world"), num("x"), num("y"), num("z"), number("yaw", 0.0).toFloat())

/** ネストしたconditionを読み、省略時は条件なしを返します。 */
fun Map<String, Any?>.condition(): Condition =
    if ("condition" in this) mapping("condition").conditionValue() else Condition()

/** マップ自体を依頼・選択条件に変換します。 */
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

/** 経験値・通貨・アイテム・flag報酬を読み取ります。 */
fun Map<String, Any?>.rewards(): Rewards = Rewards(
    whole("xp", 0),
    whole("currency", 0),
    maps("items").map { ItemReward(it.string("item"), it.integer("amount", 1), it.condition()) },
    strings("set_flags").toSet(),
)

/** アイテムIDと数量の並びをトークン費用として読み取ります。 */
fun Map<String, Any?>.tokens(key: String): List<TokenCost> = maps(key).map { TokenCost(it.string("item"), it.int("amount")) }
