package orilumn.reader.engine.laying

import orilumn.reader.engine.css.ComputedStyle
import orilumn.reader.engine.css.WhiteSpace
import orilumn.reader.engine.html.MarkupElement
import kotlin.math.roundToInt

/**
 * P6-b 注音叠排 run 模型（pure JVM，双路单源）。
 *
 * 一个 [RubyRun] 把注音文本钉在基字区间上：字符流不变（rt 文本仍 inline 进叶文本，
 * 选择/进度/双路 `globalCharStarts` 恒稳），几何只长行高（基行高 + 注音高），绘制把
 * rt 居中画在基字上方（双端同式，见 P6-b2）。水平方向允许溢出（与浏览器同式，不挪邻字）。
 *
 * 配对（同一 `ruby` 内按序）：
 * - mono/group：`[base+ rt]` 逐对（rbc/rb 分组亦逐段）；
 * - 熟字训（单 rt 跨多 base）：整段 base 配一个 rt，居中；
 * - rtc 多 rt 配一段 base：按字数均分（CJK 单字注音恒 1:1）；
 * - 无 base 的 rt（非法输入）：丢弃 run（回退行内上标旧路径，不崩版式）；
 * - `rp` 永不配对（`display:none` 下根本不在字符流里；无 UA 时按普通文本顺排）。
 */
data class RubyRun(
    /** 基字区间（叶文本坐标，半开）。 */
    val start: Int,
    val endExclusive: Int,
    /** 注音文本。 */
    val rtText: String,
    /** 注音字号 px（行高增长＋绘制字号同源）。 */
    val rtFontSizePx: Float,
    /** 注音源文区间（叶文本坐标，半开；绘制侧隐藏内联 rt 用，字符流不变）。 */
    val rtStart: Int = -1,
    val rtEndExclusive: Int = -1,
)

/** 注音行高增量（px，注音字号上取整；与绘制侧同值）。 */
fun RubyRun.extraHeightPx(): Int = rtFontSizePx.roundToInt().coerceAtLeast(1)

private sealed interface RubyItem {
    class Base(val start: Int, val endExclusive: Int) : RubyItem
    class Rt(val text: String, val fontSizePx: Float, val start: Int, val endExclusive: Int, val el: MarkupElement?) : RubyItem
}

/**
 * 收集叶内叠排 runs（与 [collectBaselineShifts] 同一 `styledSegments` 遍历，坐标恒对齐叶文本）。
 *
 * @param styleOf 任一元素的计算样式（重路径喂整章表，轻路径喂懒级联/内联表）。
 */
fun collectRubyRuns(
    root: MarkupElement,
    styles: Map<MarkupElement, ComputedStyle>,
    isExcluded: (MarkupElement) -> Boolean,
    isBlock: (MarkupElement) -> Boolean,
    rootWs: WhiteSpace = WhiteSpace.NORMAL,
    /** P3-c 生成内容查找（空即无，旧路径）。 */
    genOf: GenOf = EmptyGen,
    /** P3-c 样式回退（默认读 [styles] 表）。 */
    styleOf: ((MarkupElement) -> ComputedStyle?)? = null,
): List<RubyRun> {
    if (root.tag == "table" || root.tag == "img") return emptyList()
    if (root.isText) return emptyList()
    val resolve: (MarkupElement) -> ComputedStyle? = styleOf ?: { styles[it] }
    val wsOf: (MarkupElement) -> WhiteSpace = { wsOfNode(it, styles, root, rootWs) }
    val leafWs = styles[root]?.whiteSpace ?: root.parent?.let { styles[it]?.whiteSpace } ?: rootWs
    val segs = styledSegments(root, wsOf, isExcluded, isBlock, leafWs, genOf, resolve).segments
    val out = ArrayList<RubyRun>()
    var pos = 0
    var group: MarkupElement? = null
    val bases = ArrayList<RubyItem.Base>()
    val rts = ArrayList<RubyItem.Rt>()

    fun rubyOf(node: MarkupElement?): MarkupElement? {
        var n = node
        while (n != null && n !== root) {
            if (n.tag == "ruby") return n
            n = n.parent
        }
        return null
    }

    /** node 相对其 ruby 的角色：Rt / Rp / Base（rb/裸文本/rbc-r tc 杂项一律 Base）。 */
    fun roleOf(node: MarkupElement?, ruby: MarkupElement): String {
        var n = node
        while (n != null && n !== ruby) {
            when (n.tag) {
                "rt" -> return "rt"
                "rp" -> return "rp"
            }
            n = n.parent
        }
        return "base"
    }

    fun nearestRt(node: MarkupElement?, ruby: MarkupElement): MarkupElement? {
        var n = node
        while (n != null && n !== ruby) {
            if (n.tag == "rt") return n
            n = n.parent
        }
        return null
    }

    fun flush() {
        if (rts.isEmpty() || bases.isEmpty()) {
            bases.clear()
            rts.clear()
            return
        }
        // 同一 rt 元素被 styledSegments 切成多段时先按元素合并为逻辑注音（mono/group/熟字训单 rt）。
        val groups = ArrayList<RubyItem.Rt>()
        for (rt in rts) {
            val last = groups.lastOrNull()
            if (last != null && last.el != null && last.el === rt.el) {
                groups[groups.lastIndex] = RubyItem.Rt(
                    last.text + rt.text, last.fontSizePx,
                    last.start, rt.endExclusive, last.el,
                )
            } else {
                groups.add(rt)
            }
        }
        if (groups.size == 1) {
            val rt = groups[0]
            out.add(RubyRun(bases.first().start, bases.last().endExclusive, rt.text, rt.fontSizePx, rt.start, rt.endExclusive))
        } else {
            // rtc 多 rt 配一段 base：按字数均分（CJK 单字注音恒 1:1）。
            val total = bases.last().endExclusive - bases.first().start
            val from = bases.first().start
            for (k in groups.indices) {
                val s = from + total * k / groups.size
                val e = if (k + 1 < groups.size) from + total * (k + 1) / groups.size else from + total
                if (e > s) {
                    val rt = groups[k]
                    out.add(RubyRun(s, e, rt.text, rt.fontSizePx, rt.start, rt.endExclusive))
                }
            }
        }
        bases.clear()
        rts.clear()
    }

    for (seg in segs) {
        val n = seg.text.length
        if (n <= 0) continue
        val ruby = if (seg.node == null) null else rubyOf(seg.node)
        if (ruby == null || ruby !== group) {
            flush()
            group = ruby
        }
        if (ruby == null) {
            pos += n
            continue
        }
        when (roleOf(seg.node!!, ruby)) {
            "rp" -> Unit // 永不配对，只占字符位。
            "rt" -> {
                val rtEl = nearestRt(seg.node, ruby)
                val baseSize = resolve(ruby)?.fontSizePx ?: 10f
                val size = rtEl?.let { resolve(it)?.fontSizePx } ?: baseSize * 0.6f
                rts.add(RubyItem.Rt(seg.text, size, pos, pos + n, rtEl))
            }
            else -> bases.add(RubyItem.Base(pos, pos + n))
        }
        pos += n
    }
    flush()
    return out
}

/**
 * P6-b 叠排行高（与行内图抬升同式）：含注音基字的行，行高 += 注音高（取交叠 run 的最大增量）。
 * 无 run 即原文 faithfully（零回归）。
 */
fun adjustLineHeightsForRuby(
    broken: List<BrokenLine>,
    baseHeights: List<Int>,
    rubyRuns: List<RubyRun>,
): List<Int> {
    if (rubyRuns.isEmpty()) return baseHeights
    return baseHeights.mapIndexed { i, h ->
        val r = broken.getOrNull(i) ?: return@mapIndexed h
        var extra = 0
        for (run in rubyRuns) {
            if (run.start < r.range.last + 1 && run.endExclusive > r.range.first) {
                extra = maxOf(extra, run.extraHeightPx())
            }
        }
        if (extra > 0) h + extra else h
    }
}
