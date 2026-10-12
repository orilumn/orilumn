package orilumn.reader.engine.paging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P4a — [Paginator] 对 `BreakAwareBookLayout` 的三条断页约束（CSS Fragmentation §4.4）：
 *
 * 1. **forced**（[BreakAwareBookLayout.forcedBreakBeforeLines]）：先把行流按强制行**切段**，段首必为页首；
 * 2. **原子区间**（[BreakAwareBookLayout.avoidSplitRanges]，`break-inside: avoid`）：段内整行填充后，
 *    整块装得下就整体顺延，不撕裂；装不下允许撕裂（oversized）。
 * 3. **禁止切点**（[BreakAwareBookLayout.forbiddenCutLines]，`break-*: avoid` 边）：命中时只退到**前一个
 *    合法断点**（规范只否掉那一条 class A 边，左侧块内部的行缝仍合法）；退无可退时按规范放弃
 *    discourage，仍切在自然填充点。
 *
 * 三条约束都**默认空** ⇒ 不认识它们的旧布局逐字节不变（[PaginatorTest] 就是那份非回归守卫）。
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
    fun `原子区间装得下时整体顺延到下一页`() {
        // 页高 2 行；原子区间 = 行 [1,3)（2 行，正好一页）。第 0 页只能装行 0，整块顺延。
        val pages = Paginator.paginate(
            FakeBookLayout.ofLines("aa", "bb", "cc", "dd", avoidRanges = listOf(1 until 3)),
            contentH = 40,
        )
        assertEquals(listOf(0 to 1, 1 to 3, 3 to 4), bounds(pages))
        assertTiles(pages, 4)
    }

    @Test
    fun `超出整页的原子区间允许撕裂`() {
        // 区间 3 行（60px）> 页高 40px ⇒ 与浏览器一致：允许按页撕裂，不能被无限顺延。
        val pages = Paginator.paginate(
            FakeBookLayout.ofLines("aa", "bb", "cc", "dd", "ee", avoidRanges = listOf(1 until 4)),
            contentH = 40,
        )
        assertEquals(listOf(0 to 2, 2 to 4, 4 to 5), bounds(pages))
        assertTiles(pages, 5)
    }

    @Test
    fun `强制断页压过原子区间_不跨强制行`() {
        // 原子区间 = 行 [1,3)（正好一页），强制行 = 2 落在区间内部。
        // 强制边是硬边界：第 2 页只到行 2（区间被强制行切开），而不是把整块推到行 3 之后。
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

    // ---- P4e：break-*: avoid 边的**禁止切点**（edge-only 口径） ----

    @Test
    fun `禁止切点未落在自然页界时零影响`() {
        // 行 1 不得成为页首，但自然填充切在行 2/4 ⇒ 没有任何页边界撞上它。
        val plain = Paginator.paginate(eightLines(), contentH = 40)
        val withEdge = Paginator.paginate(
            FakeBookLayout.ofLines("aa", "bb", "cc", "dd", "ee", "ff", "gg", "hh", forbiddenLines = setOf(1)),
            contentH = 40,
        )
        assertEquals(bounds(plain), bounds(withEdge))
    }

    @Test
    fun `命中禁止切点时退到前一个合法断点`() {
        // 页高 3 行；行 3 不得成为页首。第 1 页自然填充切到行 3（=禁止切点）⇒ 退到行 2。
        val pages = Paginator.paginate(
            FakeBookLayout.ofLines("aa", "bb", "cc", "dd", "ee", "ff", "gg", "hh", forbiddenLines = setOf(3)),
            contentH = 60,
        )
        assertEquals(listOf(0 to 2, 2 to 5, 5 to 8), bounds(pages))
        assertTiles(pages, 8)
        // 不变量：没有任何页边界落在禁止切点上。
        for (p in pages) assertTrue(p.firstLine != 3)
    }

    @Test
    fun `退到的是块内行缝_不禁左块自身`() {
        // 块 A = 行 [1,4)（3 行）、块 B = 行 [4,6)，A 后写 avoid ⇒ **只**禁止切点 4。
        // 页高 2 行：第 2 页自然切到行 4（=禁止切点）⇒ 退到行 3。行 3 是 A 内部的行缝（class B），
        // 仍然合法 ⇒ 页边界落在 A 里面，而不是把 A 整条搬到下一页（这正是 edge-only 与旧口径之别）。
        val pages = Paginator.paginate(
            FakeBookLayout.ofLines("aa", "bb", "cc", "dd", "ee", "ff", forbiddenLines = setOf(4)),
            contentH = 40,
        )
        assertEquals(listOf(0 to 2, 2 to 3, 3 to 5, 5 to 6), bounds(pages))
        assertTiles(pages, 6)
        // A 被拆到三页上：avoid 不锁 A 的内部。
        assertTrue(pages[1].firstLine == 2 && pages[1].lastLineExclusive == 3)
    }

    @Test
    fun `连串禁止切点被整串顶到下一页`() {
        // 一串 1 行 avoid 条目：禁止切点 {3, 4}（行 3、4 都不得当页首）。页高 3 行：
        // 第 1 页切到行 3 ⇒ 逐行回退 3→2（合法）⇒ 整串「3,4」与后继一起留在下一页。
        val pages = Paginator.paginate(
            FakeBookLayout.ofLines("aa", "bb", "cc", "dd", "ee", "ff", "gg", "hh", forbiddenLines = setOf(3, 4)),
            contentH = 60,
        )
        assertEquals(listOf(0 to 2, 2 to 5, 5 to 8), bounds(pages))
        assertTiles(pages, 8)
    }

    @Test
    fun `页内退无可退时按规范放弃discourage`() {
        // 行 1、2 都不许当页首，但页高只有 2 行 ⇒ 任何断点都被否掉。
        // 规范 §4.4 兜底：用尽 rule 3 后 drop rule 1/2/4，仍切在自然填充点（不丢内容、不死循环）。
        val pages = Paginator.paginate(
            FakeBookLayout.ofLines("aa", "bb", "cc", "dd", forbiddenLines = setOf(1, 2)),
            contentH = 40,
        )
        assertEquals(listOf(0 to 2, 2 to 4), bounds(pages))
        assertTiles(pages, 4)
    }

    @Test
    fun `禁止切点与原子区间正交_各自独立生效`() {
        // 两条约束互不抵消：第 1 页被**原子区间** [1,3) 挡回区间首 1；第 3 页填充到切点 5 撞上
        // **禁止切点** 5 ⇒ 退到 4。同一章里两条约束各自独立生效。
        val pages = Paginator.paginate(
            FakeBookLayout.ofLines(
                "aa", "bb", "cc", "dd", "ee", "ff", "gg", "hh",
                avoidRanges = listOf(1 until 3), forbiddenLines = setOf(5),
            ),
            contentH = 40,
        )
        // 页1: 填充到 2 落在原子区间内 ⇒ 退到 1；页2: [1,3) 整块；页3: 填充到 5 命中禁止切点 ⇒ 退到 4。
        assertEquals(listOf(0 to 1, 1 to 3, 3 to 4, 4 to 6, 6 to 8), bounds(pages))
        assertTiles(pages, 8)
    }
}
