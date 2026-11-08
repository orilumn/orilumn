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
     * 「只挪段界、一个字没动」的修订 id（把两段之间的换行删掉 ⇒ 两段并成一段）。
     *
     * 这类修订的替换文字与它盖住的原书文字逐字相同，删掉的是**块边界**、不是一个字，
     * 所以审阅空间里没有红绿可标：它既不产生「新文字」副本（[reviewCopyLength] = 0），
     * 也不进 [reviewDeletedRange] / [reviewInsertedRange]。载入重放照常按合并落结构
     * （见 `BookDocumentController.applyTextRevisions`），两种视图看到的都是合并后的那一段。
     *
     * 由重放侧按「替换文字 == 覆盖的原书文字」判定后塞回来；不是落库字段。
     */
    val structureOnly: Set<Long> = emptySet(),
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
     * 这条修订在**审阅空间**里多占的长度（= 排在原文之后的那份「新文字」副本）。
     *
     * 只改段界的修订没有副本：原书文字就是改后的文字，多排一份只会把同一段字显示两遍。
     */
    private fun reviewCopyLength(r: TextRevision): Int =
        if (r.id in structureOnly) 0 else r.renderedLength

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
    // 审阅空间（v3.2 审阅视图）：原文一字不删，新文字插在原区间之后
    // ─────────────────────────────────────────────────────────────────

    /**
     * 原书偏移 → **审阅空间**偏移。
     *
     * 审阅空间与渲染空间只差一件事：修订不再替换原区间，而是把新文字**插在原区间末尾之后**，
     * 原文照排（删除的部分仍在版面上，画删除线）。于是「删了什么、加了什么」都看得见，
     * 代价是这一版排版更长、页码与正常模式不同（只存在于内存，不落分页表）。
     *
     * **边界口径与 [toRendered] 不同**：渲染空间里替换是「原地换掉」，边界点必然越过整段新文字；
     * 审阅空间里新文字排在原区间**之后**，所以边界点默认停在插入之前。于是：
     * 区间终点用 `crossInsertions = false` ⇒ 删除线只划原文、绿色只染新文字；
     * 区间起点用 `true` ⇒ 紧贴在起点处的插入算进后面那段。
     *
     * @param crossInsertions true = 越过正好落在该点的插入（原文写法：边界之前的修订整段算在前）。
     */
    fun toReview(original: Int, crossInsertions: Boolean = true): Int {
        var shift = 0
        for (r in revisions) {
            val copy = reviewCopyLength(r)
            if (r.charEnd < original) {
                shift += copy
                continue
            }
            if (r.charEnd == original) {
                // 正好在该修订末尾：插入排在原区间之后，默认不越过（= 停在插入之前）。
                if (crossInsertions) shift += copy
                continue
            }
            if (r.charStart > original) break
            // 落在原区间内部：原文仍按原位排，不夹到边界（新文字排在区间末尾之后）。
            return original + shift
        }
        return original + shift
    }

    /** 审阅空间偏移 → 原书偏移；落在新文字里时夹回原区间末尾。 */
    fun toOriginalFromReview(review: Int): Int {
        var shift = 0
        for (r in revisions) {
            val copy = reviewCopyLength(r)
            val rs = r.charStart + shift
            val re = rs + r.originalLength
            val insertedEnd = re + copy
            if (review < rs) return review - shift
            if (review < re) return r.charStart + (review - rs)
            if (review < insertedEnd) return r.charEnd
            shift += copy
        }
        return review - shift
    }

    /** 被替换掉的原区间在审阅空间的位置（红字 + 红色删除线）；纯插入为空区间。 */
    fun reviewDeletedRange(r: TextRevision): IntRange {
        // 只改段界的修订：原文一个字都没删，红字删除线无处可画。
        if (r.id in structureOnly) return IntRange.EMPTY
        val s = toReview(r.charStart, crossInsertions = true)
        val e = toReview(r.charEnd, crossInsertions = false)
        return s until maxOf(s, e)
    }

    /** 新文字在审阅空间的位置（绿字）；纯插入同样有区间。 */
    fun reviewInsertedRange(r: TextRevision): IntRange {
        // 只改段界的修订没有「新文字」副本，也就没有绿字。
        if (r.id in structureOnly) return IntRange.EMPTY
        val s = toReview(r.charEnd, crossInsertions = false)
        return s until (s + r.renderedLength)
    }

    /**
     * 某条修订在审阅空间里**整段**占的区间（原区间 + 紧随其后的新文字）。
     *
     * 审阅视图里这两段是同一处改动的两半：选到删掉的原文、选到新添的文字、或跨过两者，都算「碰到这条修订」。
     * 修改的提交与「删除这条修改」的判定都用它，免得只选到新文字时看不到任何修订。
     */
    fun reviewInterval(r: TextRevision): IntRange {
        // 只改段界的修订：审阅空间里它就占合并后的那一段（没有红绿两半），
        // 点按这段文字照样要能把它认出来（开卡改它 / 删它）。
        if (r.id in structureOnly) {
            val s = toReview(r.charStart, crossInsertions = true)
            val e = toReview(r.charEnd, crossInsertions = false)
            return s until maxOf(s, e)
        }
        val del = reviewDeletedRange(r)
        val ins = reviewInsertedRange(r)
        if (del.isEmpty() && ins.isEmpty()) {
            val p = toReview(r.charStart, crossInsertions = false)
            return p until p
        }
        val s = when {
            del.isEmpty() -> ins.first
            ins.isEmpty() -> del.first
            else -> minOf(del.first, ins.first)
        }
        val e = when {
            del.isEmpty() -> ins.last + 1
            ins.isEmpty() -> del.last + 1
            else -> maxOf(del.last, ins.last) + 1
        }
        return s until e
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
     * 审阅空间文本 → 原书文本：把每条修订插进来的新文字剪掉。
     *
     * 审阅视图里改字要在**原书空间**算（新文字替换的是原区间，不是「原文 + 新文字」的展示串），
     * 而章文本缓存只有审阅版；剪掉新文字就还原出原书这一串，块尾同理按 [toOriginalFromReview] 换算。
     */
    fun originalTextFromReview(reviewText: String): String {
        if (revisions.isEmpty()) return reviewText
        val out = StringBuilder(reviewText.length)
        var pos = 0
        for (r in revisions) {
            val ins = reviewInsertedRange(r)
            if (ins.isEmpty()) continue
            if (ins.first > pos) out.append(reviewText, pos, minOf(ins.first, reviewText.length))
            pos = maxOf(pos, ins.last + 1)
            if (pos >= reviewText.length) break
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
        /** 重放 / 预归一算法版本：进 `contentRev`。规则一改就要 +1。
         *  2 = 跨叶修订（段数由输入换行数决定，段界见 `RevisionBlocks.SEPARATOR`）。
         *  3 = 只改段界的跨叶修订在审阅视图里也按合并落结构（不再排两遍同一段字）。
         *  4 = 合并段之后的叶只按增量平移（4 以前多算了一整段，字符流与正文投影错位）。 */
        const val ALGORITHM_VERSION = 4

        private const val FNV_OFFSET = -3750763034362895579L // 14695981039346656037
        private const val FNV_PRIME = 1099511628211L

        val EMPTY = ChapterRevisionMap(emptyList())
    }
}
