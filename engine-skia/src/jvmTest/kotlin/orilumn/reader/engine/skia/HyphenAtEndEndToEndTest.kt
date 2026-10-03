package orilumn.reader.engine.skia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import orilumn.reader.engine.css.LightCssParser
import orilumn.reader.engine.css.StyleComputer
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.laying.BoxLayouter
import orilumn.reader.engine.laying.HIDDEN_NONE
import orilumn.reader.engine.laying.NormalFlowLayout

/**
 * **连字符端到端锁**：断行侧定了断词点 ⇒ 绘制侧 [orilumn.reader.engine.skia.DrawLine.hyphenAtEnd]
 * 必须逐行对上，且「拉丁词在行末被切开」的行**必须**带连字符。
 *
 * ## 为什么 `SoftHyphenTest` 证不了这件事（它全绿却漏了 100% 的真机缺陷）
 *
 * `SoftHyphenTest` 直接调 [orilumn.reader.engine.skia.InhouseParagraphBreaker.breakLines]
 * 拿 [orilumn.reader.engine.laying.BrokenLine]，**不经过** [DrawLineBuilder] ——
 * 而真机缺陷正在那一段传递里：`hyphenAtEnd = leaf.hyphenAtEnd.getOrElse(lineIdx) { false }`
 * 用了**章内全局行号** [orilumn.reader.engine.laying.LayoutBox.firstLineIndex] 去索引
 * **叶内局部**的 `hyphenAtEnd` 列表，只有首行号恰等于叶内行号的叶才对得上。
 * 单段小用例的 `firstLineIndex` 从 0 起 ⇒ 恰好重合 ⇒ 全绿。
 *
 * ⇒ **本锁必须走完整链路**（`BoxLayouter` → [DrawLineBuilder]）且语料必须是
 * **多叶**（`firstLineIndex > 0`），否则验的还是空气。
 *
 * 语料用中文 + 拉丁混排的多个 `<p>`：中文使每段产生多行，叶随文档序推进，
 * `firstLineIndex` 必然 > 0 —— 这正是缺陷的触发条件。
 */
class HyphenAtEndEndToEndTest {

    /** ASCII 字母数字（汉字 `isLetter` 也为真，会把「中文词内断行」混进来）。 */
    private fun asc(c: Char) = (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9')

    /** 排一串 `<p>`，返回每叶的 [orilumn.reader.engine.skia.DrawLine.hyphenAtEnd] 实际取值。 */
    private fun hyphenFlagsOf(ps: List<String>, measure: Int): List<Triple<Boolean, Int, Int>> {
        val css = "html{font-size:18px} body{font-family:serif;font-size:0.95rem} p{text-align:justify}"
        val html = "<html><body>" + ps.joinToString("") { "<p>$it</p>" } + "</body></html>"
        val root = HtmlTreeConverter().convert(html)!!
        val eng = StyleComputer(44.4f, LightCssParser().parse(css), emptyList())
        val styles = eng.compute(root)
        val cls = NormalFlowLayout.heavyClassify(styles, eng.hasDisplayDeclaration())
        applyBreakerVariant("inhouse")
        val res = try {
            BoxLayouter(44.4f, bodyParagraphBreaker(0f)).layoutBoxes(root, measure, styles, cls)
        } finally {
            resetBreakerVariant()
        }
        val out = ArrayList<Triple<Boolean, Int, Int>>()
        for ((_, dl) in DrawLineBuilder.build(res, styles, cls, HIDDEN_NONE, 0f).toList()) {
            out.add(Triple(dl.hyphenAtEnd, dl.range.first, dl.range.last))
        }
        return out
    }

    /** 一行的末字与下一行首字都是 ASCII 字母数字 ⇒ 拉丁词在行末被切开。 */
    private fun isLatinSplit(dl: orilumn.reader.engine.skia.DrawLine): Boolean {
        val e = dl.range.last + 1
        val t = dl.text
        if (e >= t.length) return false
        return asc(t[e]) && asc(t[e - 1])
    }

    /**
     * ① 传递锁：**存在** `hyphenAtEnd=true` 的行（否则这把锁验的是空气 —— 语料/版心必须
     * 真的逼出断词），且**存在** `firstLineIndex > 0` 的叶（缺陷触发条件）。
     *
     * 变异验证：把 `DrawLineBuilder` 的下标改回 `lineIdx` → 本锁红（`got 0 but was > 0`）。
     */
    @Test
    fun `hyphen flag survives the DrawLineBuilder projection in a multi leaf layout`() {
        // 语料必须**长拉丁词连续成句**：短词（`Rust`/`crate`）K-L 无断点，中文又逐字可断，
        // 两者都会让「拉丁词在行末被切开」这件事根本不发生 ⇒ 锁验的是空气。
        val ps = listOf(
            "the configuration of dependencies is written in the manifest of every single crate",
            "when the compilation extraordinarily succeeds the implementation is unquestionably correct",
            "an implementation of the interoperability requires understanding the characteristics",
        )
        var withHyphen = 0
        var sawNonZeroFirstLine = false
        for (w in listOf(150, 190, 240, 300)) {
            applyBreakerVariant("inhouse")
            val css = "html{font-size:18px} body{font-family:serif;font-size:0.95rem} p{text-align:justify}"
            val html = "<html><body>" + ps.joinToString("") { "<p>$it</p>" } + "</body></html>"
            val root = HtmlTreeConverter().convert(html)!!
            val eng = StyleComputer(44.4f, LightCssParser().parse(css), emptyList())
            val styles = eng.compute(root)
            val cls = NormalFlowLayout.heavyClassify(styles, eng.hasDisplayDeclaration())
            val res = try {
                BoxLayouter(44.4f, bodyParagraphBreaker(0f)).layoutBoxes(root, w, styles, cls)
            } finally {
                resetBreakerVariant()
            }
            val leaves = ArrayList<orilumn.reader.engine.laying.LayoutBox>()
            fun collect(bs: List<orilumn.reader.engine.laying.LayoutBox>) {
                for (b in bs) if (b.isContainer) collect(b.childBoxes) else leaves.add(b)
            }
            collect(res.boxes)
            if (leaves.any { it.firstLineIndex > 0 }) sawNonZeroFirstLine = true
            withHyphen += DrawLineBuilder.build(res, styles, cls, HIDDEN_NONE, 0f)
                .count { it.value.hyphenAtEnd }
        }
        assertTrue(
            "至少要有一个 firstLineIndex>0 的叶，否则验不到下标错位（缺陷触发条件缺失）",
            sawNonZeroFirstLine,
        )
        assertTrue("语料+版心必须真的逼出断词行，否则这把锁验的是空气（withHyphen=$withHyphen）", withHyphen > 0)
    }

    /**
     * ② 落墨锁（缺陷 B）：**拉丁词在行末被切开**的行必须 `hyphenAtEnd = true`。
     *
     * 缺陷 B 的形态是「在断词点断开、却没给连字符」（词看起来被硬切）。
     * 例外：K-L 表对该词**没给断点**的（如标识符 `borrow_mut`、`worker.thread`）
     * —— 那种是 R1 core 硬切，**不该**补连字符，故按词逐个核对 K-L 断点而不一刀切。
     *
     * 变异验证：把 `greedy` 的回退环改回单槽 `lastOpp`（不往回退）→ 本锁红。
     */
    @Test
    fun `a latin word split at a line end carries a hyphen whenever K-L offers one`() {
        val ps = listOf(
            "the Result of an implementation is returned as an Option of the configuration",
            "the interoperability of the characteristics requires an extraordinarily careful implementation",
            "the correctness of the compilation depends on the characteristics of the manifest",
        )
        val latin = { c: Char -> (c in 'a'..'z') || (c in 'A'..'Z') }
        var checked = 0
        for (w in listOf(140, 170, 200, 230, 260, 300, 360)) {
            val css = "html{font-size:18px} body{font-family:serif;font-size:0.95rem} p{text-align:justify}"
            val html = "<html><body>" + ps.joinToString("") { "<p>$it</p>" } + "</body></html>"
            val root = HtmlTreeConverter().convert(html)!!
            val eng = StyleComputer(44.4f, LightCssParser().parse(css), emptyList())
            val styles = eng.compute(root)
            val cls = NormalFlowLayout.heavyClassify(styles, eng.hasDisplayDeclaration())
            applyBreakerVariant("inhouse")
            val res = try {
                BoxLayouter(44.4f, bodyParagraphBreaker(0f)).layoutBoxes(root, w, styles, cls)
            } finally {
                resetBreakerVariant()
            }
            for ((_, dl) in DrawLineBuilder.build(res, styles, cls, HIDDEN_NONE, 0f).toList()) {
                if (dl.tag in setOf("pre", "code", "kbd", "samp", "tt")) continue
                if (!isLatinSplit(dl)) continue
                val t = dl.text
                val e = dl.range.last + 1
                // 取被切开的那个拉丁子词（按 ASCII 字母边界）
                var ws = e
                while (ws > 0 && latin(t[ws - 1])) ws--
                var we = e
                while (we < t.length && latin(t[we])) we++
                val word = t.substring(ws, we)
                val rel = e - ws
                val pts = orilumn.reader.engine.laying.Hyphenator.hyphenate(word, lang = "en")
                if (rel <= 0 || rel !in pts) continue // K-L 没给断点 ⇒ R1 core 硬切，不该补连字符
                checked++
                assertTrue(
                    "版心 $w 行 ${dl.range}「${word}」在词内第 $rel 位断开（K-L 断点 $pts），" +
                        "却没有 hyphenAtEnd —— 词被无声硬切",
                    dl.hyphenAtEnd,
                )
            }
        }
        assertTrue("至少要核到几行，否则这把锁验的是空气（checked=$checked）", checked >= 3)
    }

    /**
     * ③ 无回归：回退只影响「最近断点装不下」那条冷路径，正常行**逐行相同**。
     *
     * 判据取「每行行尾下标集合」—— 版心/断点集/判定宽都不变时，回退环与单槽对
     * 「最近断点装得下」的行给出同一个 `brk`，故下标必须逐个相等。
     * 变异验证：把 `OPP_HISTORY` 改成恒定返回 `lastOpp`（不回退）→ 本锁**仍绿**
     * （它在验「没引入额外行」，正是回退**应该**做到的事）；反过来把回退写成
     * 「无条件取最早断点」→ 本锁红（行数暴涨）。
     */
    @Test
    fun `regression - lines stay dense - no premature break`() {
        val css = "html{font-size:18px} body{font-family:serif;font-size:0.95rem} p{text-align:justify}"
        val text = "Rust 是一种预先编译语言，这意味着你可以先将程序编译好，再把可执行文件交给其他人使用。"
        for (w in listOf(150, 200, 260, 320, 400, 500, 600)) {
            val html = "<html><body><p>$text</p></body></html>"
            val root = HtmlTreeConverter().convert(html)!!
            val eng = StyleComputer(44.4f, LightCssParser().parse(css), emptyList())
            val styles = eng.compute(root)
            val cls = NormalFlowLayout.heavyClassify(styles, eng.hasDisplayDeclaration())
            applyBreakerVariant("inhouse")
            val res = try {
                BoxLayouter(44.4f, bodyParagraphBreaker(0f)).layoutBoxes(root, w, styles, cls)
            } finally {
                resetBreakerVariant()
            }
            val lines = DrawLineBuilder.build(res, styles, cls, HIDDEN_NONE, 0f).size
            // 贪心上界：整段宽 / 版心 + 1（最后一行放不满）+ 1（首行缩进）
            val whole = 44.4f * text.length
            val ub = (whole / w).toInt() + 2
            assertTrue(
                "版心 $w 排出 $lines 行，超出贪心上界 $ub（回退退太狠 = 提前断行）",
                lines <= ub,
            )
        }
        // 版心放大到整段装下 ⇒ 必须恰好 1 行
        val html = "<html><body><p>$text</p></body></html>"
        val root = HtmlTreeConverter().convert(html)!!
        val eng = StyleComputer(44.4f, LightCssParser().parse(css), emptyList())
        val styles = eng.compute(root)
        val cls = NormalFlowLayout.heavyClassify(styles, eng.hasDisplayDeclaration())
        applyBreakerVariant("inhouse")
        val res = try {
            BoxLayouter(44.4f, bodyParagraphBreaker(0f)).layoutBoxes(root, 100000, styles, cls)
        } finally {
            resetBreakerVariant()
        }
        assertEquals("整段装下必须恰好 1 行", 1, DrawLineBuilder.build(res, styles, cls, HIDDEN_NONE, 0f).size)
    }
}