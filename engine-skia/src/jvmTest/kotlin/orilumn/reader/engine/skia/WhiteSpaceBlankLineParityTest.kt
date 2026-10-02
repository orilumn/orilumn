package orilumn.reader.engine.skia

import orilumn.reader.engine.css.ComputedStyle
import orilumn.reader.engine.css.TextAlign
import orilumn.reader.engine.css.WhiteSpace
import orilumn.reader.engine.laying.BrokenLine
import orilumn.reader.engine.laying.ParagraphBreaker
import orilumn.reader.engine.laying.breakLeafLines
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * **Q16 正向锁**：空行（连续硬换行之间的空段）在 `white-space` 的**两个分支**里都必须保留。
 *
 * ## 缺陷本体（用户真机报「`pre code` 块里的空行全部消失」）
 *
 * [breakLeafLines] 按 [orilumn.reader.engine.css.WhiteSpaceNormalize.wraps] 分两条路：
 *  - **不折行**（`pre`/`nowrap`）：本函数自己逐 `\n` 切段，**一直**为连续换行之间的空段产零宽行
 *    （`:44` 的 `else if (i < n)` 分支）—— 这份实现是对的；
 *  - **折行**（`normal`/`pre-wrap`/`pre-line`）：整段交给断行器，而断行器**两侧都把空行丢了**
 *    （自建 `greedy` 行首 `'\n'` 直接 `s++; continue`；Skia 侧归一化后的 `if (e > s)`）。
 *
 * 而 `pre` 元素恰好**总是**走第二条路 —— [orilumn.reader.engine.css.StyleComputer.resolveWhiteSpace]
 * 把 `pre` 子树里不可折行的值降级成 `PRE_WRAP`（分页阅读器无横向滚动，见 Q13），
 * `PRE_WRAP` 的 `wraps()` 是 true。⇒ **正确的那份实现永远轮不到，丢空行的那份永远在跑。**
 *
 * ⇒ 本锁不钉「`a\n\nb` 是几行」这种孤立期望值（那正是早前把 bug 钉成规格的方式），
 * 而钉**两条分支行数一致** —— 分叉即红，且与哪一侧是「对」无关。
 *
 * 规格来源不是外部裁决：仓内已有正确实现（上面第一条路），本锁是把它推广到第二条路。
 */
class WhiteSpaceBlankLineParityTest {

    private val fam = listOf("Georgia", "STSong", "serif")

    private fun style(ws: WhiteSpace) = ComputedStyle(fontSizePx = 20f, lineHeightRatio = 1.5f, whiteSpace = ws)

    private fun lines(breaker: ParagraphBreaker, text: String, ws: WhiteSpace, tag: String? = "pre", w: Int = 1000) =
        breakLeafLines(breaker, text, style(ws), w, tag, emptyList(), 0f)

    /** 三个断行器口：`pre` 走的那条（PRE_WRAP→断行器）与真·不折行那条（PRE→本函数自切）。 */
    private fun breakers(): List<Pair<String, ParagraphBreaker>> = listOf(
        "inhouse" to InhouseParagraphBreaker(0f),
        "skia" to SkiaParagraphBreaker(0f),
    )

    /**
     * 每行的可见文本。**抹掉行尾 `'\n'`**：两条分支的区间口径本就不同 ——
     * 不折行分支的段是 `[start, i)`（**含**终止换行），断行器分支的区间按契约**不含**换行
     * （见 `InhouseParagraphBreakerTest.assertWellFormed` 的「区间不含 `\n`」不变量）。
     * 那是既有差异、且绘制侧两边都画得对，本锁不比它；比它的是**行数与逐行可见文本**。
     */
    private fun readable(text: String, l: BrokenLine): String =
        if (l.range.isEmpty()) "" else text.substring(l.range.first, minOf(l.range.last + 1, text.length)).trimEnd('\n')

    @Test
    fun `两个 white-space 分支的空行行数一致`() {
        val cases = listOf("a\n\nb", "a\n\n\nb", "\na", "a\n\n", "a\n", "a\n\nb\n\nc", "\n")
        for (t in cases) {
            val nowrap = lines(InhouseParagraphBreaker(0f), t, WhiteSpace.PRE, tag = "div").map { readable(t, it) }
            for ((name, b) in breakers()) {
                val wrap = lines(b, t, WhiteSpace.PRE_WRAP).map { readable(t, it) }
                assertEquals(
                    "[$name] «${t.replace("\n", "\\n")}»：PRE_WRAP 分支必须与 PRE 分支行数一致",
                    nowrap.size,
                    wrap.size,
                )
                assertEquals(
                    "[$name] «${t.replace("\n", "\\n")}»：PRE_WRAP 分支必须与 PRE 分行逐行同文",
                    nowrap,
                    wrap,
                )
            }
        }
    }

    /**
     * 空行是**真实行盒**：占一整行高，且行高与非折行分支同值（CSS `pre-wrap` / `<br><br>` 都如此）。
     * 少了这条，「保留空行」可能退化成「产出一个 0 高的行」—— 版面上照样看不出来，但几何已经错了。
     */
    @Test
    fun `空行占一整行高且与非折行分支同高`() {
        val t = "a\n\nb"
        val lh = (20f * 1.5f).toInt()
        for ((name, b) in breakers()) {
            val wrap = lines(b, t, WhiteSpace.PRE_WRAP)
            assertEquals("[$name] 应 3 行", 3, wrap.size)
            for ((i, l) in wrap.withIndex()) {
                assertEquals("[$name] 第 ${i + 1} 行行高", lh, l.heightPx)
            }
            val nowrap = lines(InhouseParagraphBreaker(0f), t, WhiteSpace.PRE, tag = "div")
            assertEquals("[$name] 行高与非折行分支同值", nowrap.map { it.heightPx }, wrap.map { it.heightPx })
        }
    }

    /** `normal` 语境下的 `<br><br>` 同样保留空行（`styledSegments` 把 `<br>` 折成 `\n`，断行器只认字符）。 */
    @Test
    fun `normal 语境的连续硬换行也保留空行`() {
        val t = "a\n\nb"
        val lh = (20f * 1.5f).toInt()
        for ((name, b) in breakers()) {
            val ls = lines(b, t, WhiteSpace.NORMAL, tag = "p")
            assertEquals("[$name] NORMAL 下 `a\\n\\nb` 应 3 行（两个 br 之间是一个空行）", 3, ls.size)
            assertEquals("[$name] 第 2 行是空行", "", readable(t, ls[1]))
            assertEquals("[$name] 空行也占一整行高", lh, ls[1].heightPx)
        }
    }
}