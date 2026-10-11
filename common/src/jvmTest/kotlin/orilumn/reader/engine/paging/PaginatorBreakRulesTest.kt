package orilumn.reader.engine.paging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P4a — [Paginator] 对 `BreakAwareBookLayout` 的两条断页约束：
 *
 * 1. **forced**（[BreakAwareBookLayout.forcedBreakBeforeLines]）：先把行流按强制行**切段**，段首必为页首；
 * 2. **avoid**（[BreakAwareBookLayout.avoidSplitRanges]）：段内整行填充后，整簇装得下就整体顺延，不撕裂。
 *
 * 两条约束都**默认空** ⇒ 不认识它们的旧布局逐字节不变（[PaginatorTest] 就是那份非回归守卫）。
 *
 * 行高统一 20px，页高 40px ⇒ 每页 2 行，页码可以按行下标直读。
 */
class PaginatorBreakRulesTest {

    private fun fourLines() = FakeBookLayout.ofLines("aa", "bb", "cc", "dd")

    private fun eightLines() =
        FakeBookLayout.ofLines("aa", "bb", "cc", "dd", "ee", "ff", "gg", "hh")

    /** 页区间（首行含, 尾行不含）列表，便于直读断言。 */
    private fun bounds(pages: List<PageSlice>): List<Pair<Int, Int>> =
        pages.map { it.firstLine to it.lastLineExclusive }

    /** 不变量：逐页前进、无缝无叠、覆盖全章。 */
    private fun assertTiles(pages: List<PageSlice>, lineCount: Int) {
        assertTrue(pages.isNotEmpty())
        assertEquals(0, pages.first().firstLine)
        assertEquals(lineCount, pages.last().lastLineExclusive)
        for (i in pages.indices) {
            val p = pages[i]
            assertTrue("page $i must span at least one line", p.lastLineExclusive > p.firstLine)
            if (i > 0) assertEquals("page $i must start where the previous ended", pages[i - 1].lastLineExclusive, p.firstLine)
            assertTrue("page $i charEnd must advance", p.charEnd > p.charStart)
        }
        for (i in 1 until pages.size) assertEquals(pages[i - 1].charEnd, pages[i].charStart)
    }

    @Test
    fun `无强制行时整章连续填满`() {
        val pages = Paginator.paginate(fourLines(), contentH = 40)
        assertEquals(listOf(0 to 2, 2 to 4), bounds(pages))
        assertTiles(pages, 4)
    }

    @Test
    fun `强制行成为页首_允许上一页不满`() {
        // 页高 2 行；强制断页行 3 ⇒ 第二页只装到第 2 行（不满页），第 3 行另起一页。
        val pages = Paginator.paginate(
            FakeBookLayout.ofLines("aa", "bb", "cc", "dd", "ee", "ff", "gg", "hh", forcedLines = setOf(3)),
            contentH = 40,
        )
        assertEquals(listOf(0 to 2, 2 to 3, 3 to 5, 5 to 7, 7 to 8), bounds(pages))
        assertTiles(pages, 8)
    }

    @Test
    fun `强制行恰在原有页界时不改变分页`() {
        val plain = Paginator.paginate(fourLines(), contentH = 40)
        val forced = Paginator.paginate(
            FakeBookLayout.ofLines("aa", "bb", "cc", "dd", forcedLines = setOf(2)),
            contentH = 40,
        )
        assertEquals(bounds(plain), bounds(forced))
    }

    @Test
    fun `范围外的强制行被忽略`() {
        // 0 = 首行（本来就是页首）、4 = 末行之后 ⇒ 都不该改变分页。
        val plain = Paginator.paginate(fourLines(), contentH = 40)
        val forced = Paginator.paginate(
            FakeBookLayout.ofLines("aa", "bb", "cc", "dd", forcedLines = setOf(0, 4, 99)),
            contentH = 40,
        )
        assertEquals(bounds(plain), bounds(forced))
    }

    @Test
    fun `avoid 簇装得下时整体顺延到下一页`() {
        // 页高 2 行；簇 = 行 [1,3)（2 行，正好一页）。第 0 页只能装行 0，簇整体顺延。
        val pages = Paginator.paginate(
            FakeBookLayout.ofLines("aa", "bb", "cc", "dd", avoidRanges = listOf(1 until 3)),
            contentH = 40,
        )
        assertEquals(listOf(0 to 1, 1 to 3, 3 to 4), bounds(pages))
        assertTiles(pages, 4)
    }

    @Test
    fun `超出整页的 avoid 簇允许撕裂`() {
        // 簇 3 行（60px）> 页高 40px ⇒ 与浏览器一致：允许按页撕裂，不能被无限顺延。
        val pages = Paginator.paginate(
            FakeBookLayout.ofLines("aa", "bb", "cc", "dd", "ee", avoidRanges = listOf(1 until 4)),
            contentH = 40,
        )
        assertEquals(listOf(0 to 2, 2 to 4, 4 to 5), bounds(pages))
        assertTiles(pages, 5)
    }

    @Test
    fun `强制断页压过 avoid_簇不跨强制行`() {
        // 簇 = 行 [1,3)（正好一页），强制行 = 2 落在簇内部。
        // 强制边是硬边界：第 2 页只到行 2（簇被强制行切开），而不是把簇整体推到行 3 之后。
        val pages = Paginator.paginate(
            FakeBookLayout.ofLines(
                "aa", "bb", "cc", "dd", "ee", "ff", "gg", "hh",
                avoidRanges = listOf(1 until 3),
                forcedLines = setOf(2),
            ),
            contentH = 40,
        )
        assertEquals(listOf(0 to 1, 1 to 2, 2 to 4, 4 to 6, 6 to 8), bounds(pages))
        assertTiles(pages, 8)
    }

    @Test
    fun `从中间行开始分页时强制行同样生效`() {
        // 增量窗口：从行 1 起分页（磁盘表锚点），强制行 3 仍必须是页首。
        val layout = FakeBookLayout.ofLines(
            "aa", "bb", "cc", "dd", "ee", "ff", "gg", "hh",
            forcedLines = setOf(3),
        )
        val pages = Paginator.paginateFrom(layout, startLine = 1, contentH = 40)
        assertEquals(listOf(1 to 3, 3 to 5, 5 to 7, 7 to 8), bounds(pages))
        assertEquals(1, pages.first().firstLine) // 起点被尊重（行 1 而非 0）
    }

    @Test
    fun `游标永不停滞_单行高过整页时前进`() {
        // 每行 20px、页高 10px ⇒ 单行就高过整页：强收一行，绝不产生零前进页。
        val pages = Paginator.paginate(FakeBookLayout.ofLines("aa", "bb", "cc"), contentH = 10)
        assertEquals(listOf(0 to 1, 1 to 2, 2 to 3), bounds(pages))
        assertTiles(pages, 3)
    }
}
