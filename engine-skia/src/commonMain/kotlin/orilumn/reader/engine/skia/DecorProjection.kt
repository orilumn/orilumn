package orilumn.reader.engine.skia

import kotlinx.serialization.Serializable
import orilumn.reader.engine.css.ColorRun

/**
 * 标注装饰类型（渲染层）。
 *
 * 只描述「怎么落墨」，不关心它来自高亮还是划线；颜色 / 粗细由 [DecorRange] 携带。
 * 线型（实线 / 虚线 / 波浪）见 [DecorStroke]。
 */
enum class DecorKind {
    /** 在文字下方铺一块半透明底色（荧光笔）。 */
    BACKGROUND,

    /** 在基线下方画一条线（比书内下划线更粗、更靠下）。线型见 [DecorRange.stroke]。 */
    UNDERLINE,

    /** 穿过字身的删除线（位置比下划线高，几何同下划线）。 */
    STRIKETHROUGH,

    /**
     * 换这套字的墨色（审阅视图的增删标记：删除的红字、新增的绿字）。
     *
     * 只改字色，不改字形、不改几何、不动断行 —— 和书内 `color` 是同一件事。故这种区间
     * **不进 [DrawLine.decorRuns]**（那里只放「额外落墨」），由 [projectDecor] 直接并进
     * [DrawLine.colorRuns]（逐字上色那条已有通道），绘制侧不需要任何新分支。
     */
    TEXT_COLOR,
}

/** 划线线型（渲染层）：实线 / 虚线 / 波浪线。删除线也走同一套，默认实线。 */
enum class DecorStroke {
    Solid,
    Dashed,
    Wavy,
}

/**
 * 章内装饰区间：`[start, endExclusive)`，与 `PageSlice` / `ReadingLocator` 同源。
 *
 * [argb] 含 alpha；高亮默认约 45% 不透明度。`thicknessPx` / `offsetPx` 为空时按行字号取默认值
 * （见 [LineWindowDrawer]），给将来「划线粗细可调」留口。
 *
 * **区间是章内全局字符偏移**，不是某一行的局部下标；[projectDecor] 会按行裁切，
 * 绘制侧再用 [DrawLine.charBase] 换算回行文本坐标。
 */
@Serializable
data class DecorRange(
    val start: Int,
    val endExclusive: Int,
    val kind: DecorKind,
    val argb: Int,
    val stroke: DecorStroke = DecorStroke.Solid,
    val thicknessPx: Float? = null,
    val offsetPx: Float? = null,
    val priority: Int = 0,
) {
    init {
        require(start >= 0) { "装饰起点不能为负：$start" }
        require(endExclusive > start) { "装饰区间必须非空：[$start, $endExclusive)" }
    }
}

/**
 * 把章内装饰区间合进每一行：划线/底色进 [DrawLine.decorRuns]，改字色进 [DrawLine.colorRuns]。
 *
 * - 输入区间是章内全局偏移；每行用 [DrawLine.charBase] + [DrawLine.range] 换算自己的全局范围；
 * - 与行相交的区间会被**裁到本行**，不改变颜色 / 类型 / 优先级；
 * - [DecorKind.TEXT_COLOR] 是另一条通道（改字色而非额外落墨）：并进 `colorRuns` 时排在**前面**，
 *   于是同几个字上书的 `color` 与审阅标记撞车时审阅标记赢（`mergeBands` 取第一条覆盖该字的 run）；
 * - 输出按 `priority → start → endExclusive` 排序，绘制时低优先级先落墨；
 * - `ranges` 为空时清空所有行的装饰 run（调用方每次传当前完整列表，投影是幂等的）。
 *
 * [chapter] 只用于调用方表达「这批区间属于哪一章」；函数本身不跨章，也不读章节表。
 */
@Suppress("UNUSED_PARAMETER")
fun projectDecor(
    lines: List<DrawLine>,
    chapter: Int,
    ranges: List<DecorRange>,
): List<DrawLine> {
    if (lines.isEmpty()) return lines
    val sorted = ranges
        .filter { it.endExclusive > it.start }
        .sortedWith(compareBy<DecorRange>({ it.priority }, { it.start }, { it.endExclusive }))
    // 改字色与「额外落墨」分两条通道：前者并进 colorRuns，后者留在 decorRuns。
    val inkRanges = sorted.filter { it.kind == DecorKind.TEXT_COLOR }
    val markRanges = if (inkRanges.isEmpty()) sorted else sorted.filter { it.kind != DecorKind.TEXT_COLOR }

    if (sorted.isEmpty()) {
        if (lines.none { it.decorRuns.isNotEmpty() }) return lines
        return lines.map { it.copy(decorRuns = emptyList()) }
    }

    return lines.map { line ->
        val lineStart = line.charBase + line.range.first
        val lineEnd = line.charBase + line.range.last + 1
        // 改字色：章内全局 → 本行文本坐标（`text[k]` ↔ `charBase + k`），排在书内颜色前面。
        val inkRuns = if (inkRanges.isEmpty()) {
            emptyList()
        } else {
            inkRanges.mapNotNull { r ->
                val s = maxOf(r.start, lineStart)
                val e = minOf(r.endExclusive, lineEnd)
                if (e <= s) null else ColorRun(s - line.charBase, e - line.charBase, r.argb)
            }
        }
        val colorRuns = if (inkRuns.isEmpty()) line.colorRuns else inkRuns + line.colorRuns

        val hits = ArrayList<DecorRange>(2)
        for (r in markRanges) {
            if (r.endExclusive <= lineStart) continue
            if (r.start >= lineEnd) continue
            val s = maxOf(r.start, lineStart)
            val e = minOf(r.endExclusive, lineEnd)
            if (e > s) hits.add(r.copy(start = s, endExclusive = e))
        }
        if (hits == line.decorRuns && colorRuns == line.colorRuns) {
            line
        } else {
            line.copy(decorRuns = hits, colorRuns = colorRuns)
        }
    }
}
