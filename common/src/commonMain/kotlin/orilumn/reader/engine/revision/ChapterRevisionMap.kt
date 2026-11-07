package orilumn.reader.engine.revision

import orilumn.reader.data.revision.TextRevision

/**
 * 一章的修订表 + 两个坐标空间的换算（方案 D-32 / §8.3）。
 *
 * 两个空间只差在修订上，换算是**纯算术**，不需要排版就绪：
 * - 原书空间：不含任何修订，落盘锚点（标注 / 阅读进度 / 修订自身）一律存这里；
 * - 渲染空间：应用修订之后的文字，排版、分页、选区、`textOf`、`PageSlice`、标注装饰都在这里。
 *
 * 约定：
 * - 修订同章互不重叠、按 `charStart` 升序；[of] 会校验，越界或重叠直接抛（提交侧的 bug 要炸出来，
 *   不能悄悄排出错页）；
 * - 渲染区间 = `[charStart + 前面的 delta 之和, 同左 + 原区间长度 + delta)`；
 * - **区间**换算的边界口径：起点越过该处的纯插入（插入的字不算进选区），终点不越过该处的纯插入
 *   ——「在标注末尾插入」不吞进旧标注，「在标注开头插入」包含进来（§8.3）；
 * - 纯插入的 `delta` 为替换文本长度，删除的 `delta` 为负。
 */
class ChapterRevisionMap(
    source: List<TextRevision>,
) {
    val revisions: List<TextRevision> = source.sortedWith(compareBy({ it.charStart }, { it.id }))

    init {
        var prevEnd = -1
        for (r in this.revisions) {
            require(r.charStart >= prevEnd) {
                "同章修订不得重叠：[$prevEnd, …) 与 [${r.charStart}, ${r.charEnd})"
            }
            prevEnd = r.charEnd
        }
    }

    val isEmpty: Boolean get() = revisions.isEmpty()

    /**
     * 内容版本：本章修订表 + 重放算法版本号的稳定哈希（0 = 无修订）。
     *
     * 分页表头、页数清单、当前页快照都拿它做失效判据：只改一章就只作废那一章。
     * 必须覆盖**所有会影响重放结果**的输入（区间、替换文本、组号与顺序），
     * 以及对齐 / 预归一算法的版本（见 [ALGORITHM_VERSION]、[CharDiff.VERSION]）。
     *
     * 刻意**不含 `delta`**：重放结果只由区间与替换文本决定，`delta` 是提交时算的换算缓存，
     * 载入时若与实测长度不符会被内存修正（见 `MeasuredDeltaFix`）；把它算进版本会让
     * 同一份内容在修正前后得到两个版本号，冷开书时刚写好的分页表立刻又判失效。
     */
    val contentRev: Long by lazy {
        if (revisions.isEmpty()) return@lazy 0L
        var h = FNV_OFFSET
        fun mix(value: Long) {
            var x = value
            repeat(8) {
                h = (h xor (x and 0xFF)) * FNV_PRIME
                x = x ushr 8
            }
        }
        mix(ALGORITHM_VERSION.toLong())
        mix(CharDiff.VERSION.toLong())
        mix(revisions.size.toLong())
        for (r in revisions) {
            mix(r.charStart.toLong())
            mix(r.charEnd.toLong())
            mix(r.groupId)
            for (c in r.text) mix(c.code.toLong())
            mix(-1L) // 文本分隔，避免 ["ab","c"] 与 ["a","bc"] 撞哈希
        }
        h
    }

    /** 某条修订的渲染区间（原书区间经前面的修订平移）。 */
    fun renderedInterval(r: TextRevision): IntRange {
        val shift = shiftBefore(r)
        return (r.charStart + shift) until (r.charStart + shift + r.originalLength + r.delta)
    }

    /** 排在 [r] 前面的所有修订的 delta 之和。 */
    fun shiftBefore(r: TextRevision): Int = shiftBeforeStart(r.charStart, r.id)

    private fun shiftBeforeStart(charStart: Int, id: Long): Int {
        var shift = 0
        for (x in revisions) {
            if (x.charStart < charStart || (x.charStart == charStart && x.id < id)) {
                shift += x.delta
            } else {
                break
            }
        }
        return shift
    }

    /** 整章渲染后的长度。 */
    fun renderedLength(originalLength: Int): Int =
        revisions.fold(originalLength) { acc, r -> acc + r.delta }

    /** 原书偏移 → 渲染偏移。单点不区分纯插入，一律按「越过」处理（调用处注明）。 */
    fun toRendered(original: Int): Int = toRendered(original, crossInsertions = true)

    /**
     * 原书偏移 → 渲染偏移。
     *
     * @param crossInsertions true = 越过该点处的纯插入（插入的文字排在结果之前）；
     *   false = 停在插入之前。只有落在纯插入点上才有差别。
     */
    fun toRendered(original: Int, crossInsertions: Boolean): Int {
        var shift = 0
        for (r in revisions) {
            if (r.charEnd < original) {
                shift += r.delta
                continue
            }
            if (r.charEnd == original) {
                // 正好在该修订末尾：非插入算「之后」（含其 delta）；纯插入看是否越过。
                if (!r.isInsertion || crossInsertions) shift += r.delta
                continue
            }
            if (r.charStart > original) break
            // 落在修订内部（含起点）：夹到该修订的渲染区间。
            val inside = minOf(original - r.charStart, r.originalLength)
            return r.charStart + shift + inside
        }
        return original + shift
    }

    /**
     * 原书区间 → 渲染区间。起点越过该处的纯插入、终点不越过（§8.3）。
     * 区间为空（`start == end`）时返回空区间。
     */
    fun toRenderedRange(start: Int, endExclusive: Int): IntRange {
        val s = toRendered(start, crossInsertions = true)
        val e = toRendered(endExclusive, crossInsertions = false)
        return s until maxOf(s, e)
    }

    /** 渲染偏移 → 原书偏移；落在替换文本内部时夹回该修订的原书区间。 */
    fun toOriginal(rendered: Int): Int {
        var shift = 0
        for (r in revisions) {
            val rs = r.charStart + shift
            val re = rs + r.originalLength + r.delta
            if (rendered <= rs) return rendered - shift
            if (rendered < re) return r.charStart + minOf(rendered - rs, r.originalLength)
            shift += r.delta
        }
        return rendered - shift
    }

    /** 渲染区间 → 原书区间（端点各自换算，落在替换内部时夹回边界）。 */
    fun toOriginalRange(start: Int, endExclusive: Int): IntRange {
        val s = toOriginal(start)
        val e = toOriginal(endExclusive)
        return s until maxOf(s, e)
    }

    /** 落在原书 [start, endExclusive) 内（含边界插入）的修订。 */
    fun revisionsTouching(start: Int, endExclusive: Int): List<TextRevision> =
        revisions.filter { r ->
            if (r.isInsertion) {
                r.charStart in start until endExclusive ||
                    (start == endExclusive && r.charStart == start)
            } else {
                r.charStart < endExclusive && r.charEnd > start
            }
        }

    /** 与渲染区间 [start, endExclusive) 相交的修订（纯插入只算严格落在替换内部）。 */
    fun revisionsTouchingRendered(start: Int, endExclusive: Int): List<TextRevision> =
        revisions.filter { r ->
            val rr = renderedInterval(r)
            if (r.isInsertion) rr.first > start && rr.first < endExclusive
            else rr.first < endExclusive && rr.last + 1 > start
        }

    companion object {
        /** 重放 / 预归一算法版本：进 `contentRev`。规则一改就要 +1。
         *  2 = 跨叶修订（段数由输入换行数决定，段界见 `RevisionBlocks.SEPARATOR`）。 */
        const val ALGORITHM_VERSION = 2

        private const val FNV_OFFSET = -3750763034362895579L // 14695981039346656037
        private const val FNV_PRIME = 1099511628211L

        val EMPTY = ChapterRevisionMap(emptyList())
    }
}
