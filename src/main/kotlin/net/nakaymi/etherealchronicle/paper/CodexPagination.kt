package net.nakaymi.etherealchronicle.paper

/** 図鑑一覧を45枠単位に分け、ページ移動を範囲内へ制限します。 */
internal object CodexPagination {
    const val DEFAULT_PAGE_SIZE = 45
    internal data class Slice<T>(val items: List<T>, val page: Int, val pageCount: Int)

    /** 指定ページの項目を返し、項目数が変わった場合もページ番号を有効範囲へ丸めます。 */
    fun <T> slice(items: List<T>, requestedPage: Int, pageSize: Int = DEFAULT_PAGE_SIZE): Slice<T> {
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

    /** ページ内スロットを全体リストの添字へ変換します。 */
    fun itemIndex(page: Int, slot: Int, pageSize: Int = DEFAULT_PAGE_SIZE): Int? =
        if (slot !in 0 until pageSize || page < 0) null else page * pageSize + slot

    private fun Int.sign(): Int = when {
        this < 0 -> -1
        this > 0 -> 1
        else -> 0
    }
}
