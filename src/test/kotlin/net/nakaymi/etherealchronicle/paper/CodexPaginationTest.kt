package net.nakaymi.etherealchronicle.paper

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** 図鑑一覧が45枠を越えた時のページ境界と移動を検証します。 */
class CodexPaginationTest {
    @Test
    fun `codex pages cover every item and clamp previous next movement`() {
        val items=(0 until 101).toList()
        val first=CodexPagination.slice(items,0)
        val second=CodexPagination.slice(items,1)
        val last=CodexPagination.slice(items,20)

        assertEquals(3,first.pageCount)
        assertEquals(45,first.items.size)
        assertEquals(0,first.items.first())
        assertEquals(44,first.items.last())
        assertEquals(45,second.items.first())
        assertEquals(89,second.items.last())
        assertEquals(2,last.page)
        assertEquals(listOf(90,91,92,93,94,95,96,97,98,99,100),last.items)
        assertEquals(0,CodexPagination.move(0,-1,3))
        assertEquals(2,CodexPagination.move(1,1,3))
        assertEquals(2,CodexPagination.move(2,1,3))
    }
}
