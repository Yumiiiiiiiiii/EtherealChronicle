package net.nakaymi.etherealchronicle.paper

/** 図鑑一覧を45枠単位に分け、ページ移動を範囲内へ制限します。 */
internal object CodexPagination {
    internal data class Slice<T>(val items: List<T>, val page: Int, val pageCount: Int)

    /** 指定ページの項目を返し、項目数が変わった場合もページ番号を有効範囲へ丸めます。 */
    fun <T> slice(items: List<T>, requestedPage: Int, pageSize: Int = 45): Slice<T> {
        require(pageSize > 0)
        val pageCount = ((items.size + pageSize - 1) / pageSize).coerceAtLeast(1)
        val page = requestedPage.coerceIn(0,pageCount - 1)
        val start = page * pageSize
        return Slice(items.drop(start).take(pageSize),page,pageCount)
    }

    /** 前後操作後のページ番号を有効範囲に収めます。 */
    fun move(currentPage: Int, direction: Int, pageCount: Int): Int {
        require(pageCount > 0)
        return (currentPage + direction.sign()).coerceIn(0,pageCount - 1)
    }

    private fun Int.sign(): Int = when {
        this < 0 -> -1
        this > 0 -> 1
        else -> 0
    }
}
