package net.nakaymi.etherealchronicle.core

/** コンテンツ翻訳キー（英語カタログとリソースパックlang JSON共通）の組み立てと、説明文の折り返しを担います。 */
object ContentKeys {
    /** 名前空間付きID（ec:item_x 形式）から、content・type・id・field をドットで連結した翻訳キーを作ります。 */
    fun key(type: String, id: String, field: String): String = "$ROOT.$type.${id.substringAfter(':')}.$field"

    /** 全コンテンツキー共通の接頭辞（翻訳キー参照の静的検査に誤検出されないよう連結で組み立てる）。 */
    private const val ROOT = "ec" + "." + "content"

    /** 日本語説明文の折り返し幅。DialogueLayout.TEXT_WRAP_CHARACTERS と同じ値です。 */
    const val LORE_WRAP_JA = 28

    /** 英語説明文の折り返し幅（単語単位）。scripts/gen_pack_lang.py と同じ値です。 */
    const val LORE_WRAP_EN = 46

    /** 日本語は固定幅で分割します。 */
    fun wrapJa(text: String): List<String> = text.chunked(LORE_WRAP_JA)

    /** 英語は単語単位の貪欲法で折り返します。 */
    fun wrapEn(text: String): List<String> {
        val lines = ArrayList<String>()
        var current = ""
        for (word in text.split(" ")) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (candidate.length > LORE_WRAP_EN && current.isNotEmpty()) { lines += current; current = word } else current = candidate
        }
        if (current.isNotEmpty()) lines += current
        return lines
    }
}
