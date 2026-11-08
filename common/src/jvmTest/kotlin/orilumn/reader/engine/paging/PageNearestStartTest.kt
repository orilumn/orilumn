package orilumn.reader.engine.paging

import org.junit.Assert.assertEquals
import org.junit.Test

/** 换空间落位：选「页首最接近」的那一页（正常 ↔ 审阅两套分页页界不齐时不能退到上一页）。 */
class PageNearestStartTest {

    private fun slices(vararg starts: Int) = starts.map { PageSlice(charStart = it, charEnd = it + 10) }

    @Test
    fun emptyListHasNoPage() {
        assertEquals(-1, pageNearestStart(emptyList(), 100))
    }

    @Test
    fun takesTheClosestStartNotTheContainingPage() {
        // 真机形态：正常视图页首 1241，审阅视图页界后挪了几个字（[526,1244)）。
        // 「包含」会选 526（整整一页之前），「最接近」选 1244。
        val s = slices(0, 526, 1244, 2033, 2742)
        assertEquals(2, pageNearestStart(s, 1241))
    }

    @Test
    fun exactStartWins() {
        val s = slices(0, 526, 1244, 2033)
        assertEquals(2, pageNearestStart(s, 1244))
        assertEquals(1, pageNearestStart(s, 526))
        assertEquals(0, pageNearestStart(s, 0))
    }

    @Test
    fun tiePrefersTheEarlierPage() {
        // 750 距 500 与 1000 一样远：取靠前的一页，结果不随扫描方向漂。
        val s = slices(0, 500, 1000)
        assertEquals(1, pageNearestStart(s, 750))
    }

    @Test
    fun beyondTheLastPagePinsToLast() {
        val s = slices(0, 526, 1244)
        assertEquals(2, pageNearestStart(s, 99999))
    }
}
