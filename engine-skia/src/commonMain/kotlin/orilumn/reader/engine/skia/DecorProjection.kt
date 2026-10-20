package orilumn.reader.engine.skia

/**
 * 标注装饰类型（渲染层）。
 *
 * 只描述「怎么落墨」，不关心它来自高亮还是划线；颜色 / 粗细由 [DecorRange] 携带。
 * 将来若加删除线 / 上划线，在这里追加，绘制侧按同一套区间几何处理。
 */
enum class DecorKind {
    /** 在文字下方铺一块半透明底色（荧光笔）。 */
    BACKGROUND,

    /** 在基线下方画一条实线（比书内下划线更粗、更靠下）。 */
    UNDERLINE,
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
data class DecorRange(
    val start: Int,
    val endExclusive: Int,
    val kind: DecorKind,
    val argb: Int,
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
 * 把章内装饰区间合进每一行的 [DrawLine.decorRuns]（烘焙前调用）。
 *
 * - 输入区间是章内全局偏移；每行用 [DrawLine.charBase] + [DrawLine.range] 换算自己的全局范围；
 * - 与行相交的区间会被**裁到本行**，不改变颜色 / 类型 / 优先级；
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

    if (sorted.isEmpty()) {
        if (lines.none { it.decorRuns.isNotEmpty() }) return lines
        return lines.map { it.copy(decorRuns = emptyList()) }
    }

    return lines.map { line ->
        val lineStart = line.charBase + line.range.first
        val lineEnd = line.charBase + line.range.last + 1
        val hits = ArrayList<DecorRange>(2)
        for (r in sorted) {
            if (r.endExclusive <= lineStart) continue
            if (r.start >= lineEnd) continue
            val s = maxOf(r.start, lineStart)
            val e = minOf(r.endExclusive, lineEnd)
            if (e > s) hits.add(r.copy(start = s, endExclusive = e))
        }
        if (hits.isEmpty()) {
            if (line.decorRuns.isEmpty()) line else line.copy(decorRuns = emptyList())
        } else if (hits == line.decorRuns) {
            line
        } else {
            line.copy(decorRuns = hits)
        }
    }
}
