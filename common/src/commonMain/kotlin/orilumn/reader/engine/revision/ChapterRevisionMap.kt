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
    /**
     * 原书空间的**块尾**（每段结束处的原书偏移，升序；末块可以不给）。
     *
     * 用来定位「这一段里有哪几道段界」：落在修订区间**内部**的块尾就是这道段界，
     * 审阅视图据此决定这一段是并段（¶ 落在原断行处）、还是拆段（¶ 落在断行处上一行末尾）。
     * 不传（默认空）时审阅空间里没有块边界可动 —— 那只会发生在修订表还没参与重放的时候，
     * 此时审阅视图也不会用到这些换算。
     */
    val originalBlockEnds: IntArray = IntArray(0),
    /**
     * 修订 → 它盖住的**原书文字**（原书空间；段与段之间直接相连，不含段界字符）。
     *
     * 键是修订本身而不是 id：刚提交、还没落库的那几条 id 都是 0，按 id 会撞在一起。
     *
     * 审阅视图的**全部口径**都从它来：和替换文字对齐一次，就知道新写的字该插在哪、
     * 哪几个字是真正被删掉的（见 [ReviewOverlay]）。没给时这些修订按渲染空间落字
     * （正常视图的样子、没有红绿），坐标换算也随之退回渲染口径。
     * 不是落库字段。
     */
    val originalTexts: Map<TextRevision, String> = emptyMap(),
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

    /**
     * 这条修订在**审阅空间**里多占的长度：就地插进去的字数（含段界痕迹 ¶）。
     *
     * 审阅空间 = 原文照排 + 改动就地插入（见 [ReviewOverlay]），所以「多出来」的只有读者新写的字：
     * 原文一个字都不删，也不另排一份副本。拿不到原书文字时退回渲染增量（那一段按正常视图落字）。
     */
    private fun reviewExtra(r: TextRevision): Int =
        overlay(r)?.let { it.length - r.originalLength } ?: r.delta

    /**
     * 这条修订的对齐结果（审阅空间文字 / 红绿区间 / 位置表）；拿不到原书文字时为 null。
     *
     * 排版（拆块 / 就地插入）、上色、坐标换算都从这一份结果走——**单一来源**：
     * 三处各算一遍必然对不上，实测踩过（副本长度多一格就把后面一个字染绿）。
     */
    fun overlay(r: TextRevision): ReviewOverlay.Plan? = overlays[r]

    private val overlays: Map<TextRevision, ReviewOverlay.Plan> by lazy {
        if (originalTexts.isEmpty()) return@lazy emptyMap()
        val out = HashMap<TextRevision, ReviewOverlay.Plan>(4)
        for (r in revisions) {
            val old = originalTexts[r] ?: continue
            val plan = ReviewOverlay.plan(old, r.text, blockEndsIn(r)) ?: continue
            out[r] = plan
        }
        out
    }

    /** 原书段界里落在 [r] 区间**内部**的那几个（局部偏移，升序）。 */
    private fun blockEndsIn(r: TextRevision): IntArray {
        if (originalBlockEnds.isEmpty() || r.originalLength <= 0) return IntArray(0)
        val out = ArrayList<Int>(2)
        for (e in originalBlockEnds) {
            if (e <= r.charStart) continue
            if (e >= r.charEnd) break
            out += e - r.charStart
        }
        return out.toIntArray()
    }

    /**
     * 已被删除的段界所在的痕迹位（红 ¶），升序。
     *
     * ¶ 是排版里的普通字符（占一格）：被删掉的段界画成红字 + 删除线，新插进去的画成绿字。
     * 位置 = 该段在审阅空间的起点 + 痕迹在段内的位置。
     */
    val reviewDeletedBreakMarkers: IntArray by lazy { markPositions(inserted = false) }

    /** 新插进去的段界所在的痕迹位（绿 ¶），升序。 */
    val reviewInsertedBreakMarkers: IntArray by lazy { markPositions(inserted = true) }

    private fun markPositions(inserted: Boolean): IntArray {
        val out = ArrayList<Int>(4)
        for (r in revisions) {
            val plan = overlay(r) ?: continue
            if (plan.marks.isEmpty()) continue
            val base = reviewStart(r)
            for ((pos, isNew) in plan.marks) if (isNew == inserted) out += base + pos
        }
        if (out.isEmpty()) return IntArray(0)
        out.sort()
        return out.toIntArray()
    }

    /**
     * 审阅空间里**所有** ¶ 的字符偏移（升序）。
     *
     * 只给「取文字」用（摘录 / 复制 / 修改卡初值要跳过痕迹）；坐标换算已经把它们算进
     * [ReviewOverlay.Plan.length]，这里只管给宿主一份要跳过的位置表。
     */
    val reviewMarkOffsets: IntArray by lazy {
        val out = ArrayList<Int>(4)
        for (r in revisions) {
            val plan = overlay(r) ?: continue
            if (plan.marks.isEmpty()) continue
            val base = reviewStart(r)
            for (p in plan.marks.keys) out += base + p
        }
        if (out.isEmpty()) return@lazy IntArray(0)
        out.sort()
        out.toIntArray()
    }

    /** 审阅空间里段界痕迹 ¶ 的位置（升序）；与 [reviewMarkOffsets] 同一份。 */
    val reviewBreakMarkers: IntArray get() = reviewMarkOffsets

    /**
     * 审阅空间里**真正**被删掉的字（红字 + 删除线），按修订 id：
     * 每项是升序的 `[start0, end0, start1, end1, …]` 字符位对（含被删段界的红 ¶）。
     *
     * 与 [reviewInterval] 分工不同：那个是「这条修订整段占的位置」（交互判定要用它），
     * 这里是「其中确实变了的那几个字」（上色用它）。原文里保留下来的字不标色。
     */
    val paintedDeleted: Map<TextRevision, IntArray> by lazy { paintedSpans(deleted = true) }

    /**
     * 审阅空间里**真正**新写进去的字（绿字），升序字符位对，口径同 [paintedDeleted]；
     * 新插段界前那个 ¶ 也算新写的（段界是读者敲进去的，不是原文里的）。
     */
    val paintedInserted: Map<TextRevision, IntArray> by lazy { paintedSpans(deleted = false) }

    private fun paintedSpans(deleted: Boolean): Map<TextRevision, IntArray> {
        if (overlays.isEmpty()) return emptyMap()
        val out = HashMap<TextRevision, IntArray>(4)
        for (r in revisions) {
            val plan = overlay(r) ?: continue
            val local = if (deleted) plan.deleted else plan.inserted
            if (local.isEmpty()) continue
            val base = reviewStart(r)
            out[r] = IntArray(local.size) { base + local[it] }
        }
        return out
    }

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

    // ─────────────────────────────────────────────────────────────────
    // 审阅空间（v3.3 审阅视图）：原文照排 + 改动就地插入
    // ─────────────────────────────────────────────────────────────────

    /**
     * 原书偏移 → **审阅空间**偏移。
     *
     * 审阅空间与渲染空间只差一件事：修订不再「原地换掉」原区间，而是**原文一字不删**、
     * 新写的字就地插进原区间（见 [ReviewOverlay]）。于是「删了什么、加了什么」都看得见，
     * 代价是这一版排版更长、页码与正常模式不同（只存在于内存，不落分页表）。
     *
     * **边界口径与 [toRendered] 不同**：渲染空间里替换是「原地换掉」，边界点必然越过整段新文字；
     * 审阅空间里插入只影响它自己那个点，所以边界点默认停在插入之前：
     * 区间起点用 `true`（越过该点的插入）⇒ 紧贴起点的插入算进后面那段；
     * 区间终点用 `false` ⇒ 绿色不算进原来那一段。
     *
     * @param crossInsertions true = 越过正好落在该点的插入（该修订区间内部也算）。
     */
    fun toReview(original: Int, crossInsertions: Boolean = true): Int {
        var shift = 0
        for (r in revisions) {
            val extra = reviewExtra(r)
            if (r.charEnd < original) {
                shift += extra
                continue
            }
            if (r.charEnd == original) {
                // 正好在该修订末尾：插入排在原文字之后，默认不越过（= 停在插入之前）。
                if (crossInsertions) shift += extra
                continue
            }
            if (r.charStart > original) break
            // 落在原区间内部：按这一段的对齐表定位（原文仍按原位排，新写的字就地插在前面）。
            val plan = overlay(r)
            val start = r.charStart + shift
            if (plan == null) return start + minOf(original - r.charStart, r.originalLength)
            val local = (original - r.charStart).coerceIn(0, r.originalLength)
            return start + (if (crossInsertions) plan.afterOf(local) else plan.beforeOf(local))
        }
        return original + shift
    }

    /** 审阅空间偏移 → 原书偏移；落在就地插入的字上时夹回插入点。 */
    fun toOriginalFromReview(review: Int): Int {
        var shift = 0
        for (r in revisions) {
            val extra = reviewExtra(r)
            val rs = r.charStart + shift
            val plan = overlay(r)
            val len = plan?.length ?: (r.originalLength + r.delta)
            if (review < rs) return review - shift
            if (review < rs + len) {
                if (plan == null) return r.charStart + minOf(review - rs, maxOf(0, r.originalLength))
                return r.charStart + plan.oldAt(review - rs)
            }
            shift += extra
        }
        return review - shift
    }

    /**
     * 这条修订在审阅空间里的**起点**。
     *
     * 不能直接用 `toReview(r.charStart, crossInsertions = false)`：那条口径是给「区间终点」用的
     * （新字不算进旧区间），当前一条修订正好在这一处结束、并且它的新文字就排在这个边界之前时，
     * 它会把前一处的插入也排除掉，于是后一条修订的整段（红绿上色、点按命中、取文字）都前移了
     * 前一处的长度——同一段里相邻的两处修改就是这样错位的。
     * 审阅空间里每条修订的正文都是「原书那一段 + 它的插入」按顺序首尾相接，所以本条的起点
     * = 原书起点 + 排在它前面所有修订的插入长度（[reviewExtra]）。
     */
    private fun reviewStart(r: TextRevision): Int {
        var shift = 0
        for (x in revisions) {
            if (x === r) break
            shift += reviewExtra(x)
        }
        return r.charStart + shift
    }

    /**
     * 某条修订在审阅空间里**整段**占的区间（原文 + 就地插进去的字）。
     *
     * 修订在审阅空间里就是这一段：选到哪一处都算「碰到这条修订」，点按、修改的提交、
     * 「删除这条修改」的判定都用它。
     */
    fun reviewInterval(r: TextRevision): IntRange {
        val s = reviewStart(r)
        val len = overlay(r)?.length ?: (r.originalLength + r.delta)
        // 空区间（退化修订）也给一格：点得中才删得掉。
        return s until (s + maxOf(len, 1))
    }

    /** 审阅空间 [start, endExclusive) 碰到的修订（删掉的原文、新添的文字任一相交都算）。 */
    fun revisionsTouchingReview(start: Int, endExclusive: Int): List<TextRevision> =
        revisions.filter { r ->
            val rr = reviewInterval(r)
            rr.first < endExclusive && rr.last + 1 > start
        }

    /**
     * 审阅空间的选区 → 可提交的**原书区间**（审阅视图里改字用）。
     *
     * 审阅视图原文一字不删，所以「改这段」只能表达成「把原书这段换成新文字」：端点各自换算回
     * 原书空间，再与撞上的修订（[revisionsTouchingReview]）的原区间取并集——只选到新添的文字
     * 时端点会夹在原区间末尾，区间是空的，必须抬到那条修订的原区间，否则等于在原地加字、
     * 旧的新文字还留着。
     */
    fun originalEditRangeFromReview(start: Int, endExclusive: Int): IntRange {
        val a = toOriginalFromReview(start)
        val b = toOriginalFromReview(endExclusive)
        var s = minOf(a, b)
        var e = maxOf(a, b)
        for (r in revisionsTouchingReview(start, endExclusive)) {
            s = minOf(s, r.charStart)
            e = maxOf(e, r.charEnd)
        }
        return s until maxOf(s, e)
    }

    /**
     * 审阅空间文本 → 原书文本：把每条修订就地插进去的字（含 ¶）剪掉。
     *
     * 审阅视图里改字要在**原书空间**算（新文字替换的是原区间，不是「原文 + 新文字」的展示串），
     * 而章文本缓存只有审阅版；按每条修订的原书文字换回去就还原出原书这一串
     * （块尾同理按 [toOriginalFromReview] 换算）。拿不到原书文字的那几条只能原样留着。
     */
    fun originalTextFromReview(reviewText: String): String {
        if (revisions.isEmpty()) return reviewText
        val out = StringBuilder(reviewText.length)
        var pos = 0
        for (r in revisions) {
            val iv = reviewInterval(r)
            val s = iv.first.coerceIn(0, reviewText.length)
            val e = (iv.last + 1).coerceIn(s, reviewText.length)
            if (s > pos) out.append(reviewText, pos, s)
            val old = originalTexts[r]
            if (old != null) out.append(old) else out.append(reviewText, s, e)
            pos = maxOf(pos, e)
        }
        if (pos < reviewText.length) out.append(reviewText, pos, reviewText.length)
        return out.toString()
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
        /**
         * 审阅视图里「段界痕迹」用的字符：被删掉的段界显示成红色带删除线的 ¶，
         * 新插入的段界在换行前显示成绿色 ¶（用户 2026-11-08 定口：像普通字符一样排在行内）。
         *
         * 它就是排版里的一个普通字符（占一格），不是覆盖层：审阅空间的换算里把它算进去，
         * 谁都不必为它另开几何通道。取 U+00B6 是因为各字体都有这个字形；
         * 正文里真有 ¶ 的书极少，只有审阅视图的「取文字」路径会把它当痕迹跳过。
         */
        const val BREAK_MARK_CHAR: Char = '\u00B6'

        /** 重放 / 预归一算法版本：进 `contentRev`。规则一改就要 +1。
         *  2 = 跨叶修订（段数由输入换行数决定，段界见 `RevisionBlocks.SEPARATOR`）。
         *  3 = 只改段界的跨叶修订在审阅视图里也按合并落结构（不再排两遍同一段字）。
         *  4 = 合并段之后的叶只按增量平移（4 以前多算了一整段，字符流与正文投影错位）。
         *  5 = 审阅空间在段界处多排一个 ¶ 痕迹（删掉的段界 / 插入的段界），章文字变了。
         *  6 = 段内新敲的换行也落成真块（单叶拆段）；同一片叶上的几处段界改动合成一次拆块。
         *  7 = 审阅视图的「新文字副本」里的段界也落成真块（不再排成 `¶` + `<br>`）。
         *  8 = 副本里的段界只占一格（¶），不再按「¶ + 换行」两格算长度。
         *  9 = 审阅视图改成「原文照排 + 改动就地插入」，不再另排新文字副本（章文字变了）。
         * 10 = 落在同一片叶 / 同一组上的多处修改一起重塑，不再只保住其中一条（章文字变了）。 */
        const val ALGORITHM_VERSION = 10

        private const val FNV_OFFSET = -3750763034362895579L // 14695981039346656037
        private const val FNV_PRIME = 1099511628211L

        val EMPTY = ChapterRevisionMap(emptyList())
    }
}
