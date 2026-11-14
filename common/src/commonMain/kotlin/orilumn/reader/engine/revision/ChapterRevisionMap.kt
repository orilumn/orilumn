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
    /**
     * 修订 → 它盖住的那些块**原来**的形态（按段对齐，与 [TextRevision.blockKinds] 同序）。
     *
     * 与 [originalTexts] 一样是从书里投影出来的，不落库：审阅视图要把「被删掉的标记」
     * 排出来（`## 你好世界` 删掉 `## ` 时留一个红 `## `），就得知道它原来是标题。
     * 取不到（老宿主 / 结构未就绪）时这一段不画标记痕迹。
     */
    val originalKinds: Map<TextRevision, List<BlockKind>> = emptyMap(),
    /**
     * 修订 → 它盖住的那段文字**原来**的行内结构（坐标相对 [TextRevision.charStart]）。
     *
     * 同上，从书里投影、不落库：只删掉 `**` / `` ` `` / 链接这类行内标记的修订，
     * 要把标记本身排进审阅空间。**不在表里**（不是空表）= 取不到，行内痕迹一律不画
     * （空表表示「原来就没有行内结构」，那时读者新写的标记会画成绿字）。
     */
    val originalSpans: Map<TextRevision, List<InlineMarkup.StyleSpan>> = emptyMap(),
    /**
     * **整表结构修订**的 id（首行短码大写 `T`，见 `TableRowInfo.wire`）。
     *
     * 这类修订在审阅空间里**不按「原文 + 就地插入」展开**：整张表按结果直接重建，红绿痕迹不画。
     * 于是它在审阅空间里占的长度就是「新表长度」= 原区间长度 + [TextRevision.delta]，
     * 与渲染空间同一把尺——坐标换算、点按命中、删改判定都照常成立。
     *
     * 给不出 [structuralPlans] 的老宿主 / 对不上的数据走这条退路；能给出时以计划为准。
     */
    val structuralIds: Set<Long> = emptySet(),
    /**
     * 整表结构修订 → 它在审阅视图里的**行级对照**（见 [TableReviewPlan]）。
     *
     * 键是修订本身（同 [originalTexts]，不能用 id：还没落库的修订 id 都是 0）。
     * 给了计划的整表修订按「原文照排 + 新字就地插入」展开：删掉的行留在原处标红、
     * 新加的行标绿、改过的格红删绿增。没给的退回 [structuralIds] 的老口径（直接显示新表）。
     */
    val structuralPlans: Map<TextRevision, ReviewOverlay.Plan> = emptyMap(),
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
     * 必须覆盖**所有会影响重放结果**的输入（区间、替换文本、行内样式、组号与顺序），
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
            // 行内样式也进版本：只改结构不改字（加粗 / 去斜体）同样会改变这一章的排版。
            val styles = r.styles
            mix(if (styles == null) -7L else styles.size.toLong())
            if (styles != null) {
                for (s in styles) {
                    mix(s.start.toLong())
                    mix(s.endExclusive.toLong())
                    for (c in s.style.tag) mix(c.code.toLong())
                    mix(-3L) // 标签与属性分隔
                    for ((k, v) in s.style.attrs) {
                        for (c in k) mix(c.code.toLong())
                        mix(-4L) // 属性名与值分隔
                        for (c in v) mix(c.code.toLong())
                        mix(-5L) // 属性分隔
                    }
                }
            }
            // 块级形态同样改版面（标题字号 / 引用缩进 / 列表项目符号），必须进版本号。
            mix(r.blockKinds.size.toLong())
            for (b in r.blockKinds) {
                for (c in b) mix(c.code.toLong())
                mix(-6L)
            }
            // 每行短码也参与重放：整表修订按它决定行数 / 每行列数（文字相同、结构不同同样改版面）。
            mix(r.editedBlocks.size.toLong())
            for (b in r.editedBlocks) {
                for (c in b) mix(c.code.toLong())
                mix(-8L)
            }
            // 行来源决定哪些行原样保留 / 属性取哪一行（样式随结构走），同样参与版本号。
            val sources = r.rowSources
            mix(sources?.size?.toLong() ?: -1L)
            sources?.forEach { mix(it.toLong()) }
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
            // 有行级对照的整表修订：按「原文照排 + 新字就地插入」展开（删掉的行 / 字留在原处）。
            structuralPlans[r]?.let {
                out[r] = it
                continue
            }
            // 给不出对照的整表修订仍按结果整表重建，不在审阅空间里展开原文 + 插入。
            if (r.id in structuralIds) continue
            val old = originalTexts[r] ?: continue
            val plan = ReviewOverlay.plan(old, r.text, blockEndsIn(r), tracesOf(r, old)) ?: continue
            out[r] = plan
        }
        out
    }

    /**
     * 这条修订的**格式痕迹**：字一个没改、只改了标记时，把被删 / 新写的标记排进审阅空间
     * （见 [FormatTraces]）。改了字的修订不画——红绿已经标在字上了。
     *
     * 块标记只认「一段一条」的修订（`blockKinds` 恰好一项、文字里没有段界）：一次改好几段的
     * 形态时「哪一段长成哪一块」本身就不唯一，宁可不画也不猜。行内标记按样式区间的差集算，
     * 没有这个限制。
     */
    private fun tracesOf(r: TextRevision, old: String): List<ReviewOverlay.Trace> {
        if (r.text.indexOf(RevisionBlocks.SEPARATOR) >= 0) return emptyList()
        val single = r.blockKinds.size == 1
        val oldKind = if (single) originalKinds[r]?.firstOrNull() else null
        val newKind = if (single) BlockKind.fromWire(r.blockKinds[0]) else null
        return FormatTraces.traces(
            oldText = old,
            newText = RevisionBlocks.textOf(r.text),
            oldKind = oldKind,
            newKind = newKind,
            oldSpans = originalSpans[r],
            newSpans = r.styles?.let(InlineMarkup::fromStored),
        )
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
     * 审阅空间里**所有** 痕迹字符的偏移（升序）：段界痕迹 ¶ 与格式痕迹（`## ` / `**` …）。
     *
     * 只给「取文字」用（摘录 / 复制 / 修改卡初值要跳过痕迹）；坐标换算已经把它们算进
     * [ReviewOverlay.Plan.length]，这里只管给宿主一份要跳过的位置表。
     */
    val reviewMarkOffsets: IntArray by lazy {
        val out = ArrayList<Int>(4)
        for (r in revisions) {
            val plan = overlay(r) ?: continue
            if (plan.marks.isEmpty() && plan.traces.isEmpty()) continue
            val base = reviewStart(r)
            for (p in plan.marks.keys) out += base + p
            for (p in plan.traces) out += base + p
        }
        if (out.isEmpty()) return@lazy IntArray(0)
        out.sort()
        out.toIntArray()
    }

    /**
     * 审阅空间里**所有**痕迹字符的位置（升序）：段界痕迹 ¶ 与格式痕迹（`## ` / `**` …）。
     *
     * 与 [reviewMarkOffsets] 同一份；名字保留给「宿主按它跳过痕迹」的那条老通道。
     */
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
     *
     * 「是不是同一条」按**值**认，不能按引用：宿主点按命中 / 卡片用的修订来自另一次读库，
     * 与本章修订表里的是两个实例。按引用找不到自己就会一路累加到底，把排在**后面**的插入
     * 也算进来，整段右移——点按落空、选区框错字（审阅视图下这两处都用本函数）。
     */
    private fun reviewStart(r: TextRevision): Int {
        val idx = revisions.indexOfFirst { it === r || it == r }
        if (idx < 0) {
            // 不是本章这一版修订表里的条目（旧快照 / 别的空间）：退回按位置口径，
            // 只累加严格排在它前面的那些。
            var shift = 0
            for (x in revisions) {
                if (x.charStart < r.charStart || (x.charStart == r.charStart && x.id < r.id)) {
                    shift += reviewExtra(x)
                } else {
                    break
                }
            }
            return r.charStart + shift
        }
        var shift = 0
        for (i in 0 until idx) shift += reviewExtra(revisions[i])
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

    /**
     * 某条修订在审阅视图里**整段**占的区间：提交时那段选区的原文 + 读者新写的字
     * （D-36 补记「点按改动痕迹 = 直接开卡改它」）。
     *
     * 与 [reviewInterval] 的差别只在**被裁掉的公共前后缀**：[TextEditPlanner] 落库的是
     * 「最小修订」（改一个字只动一个字），两端没改的字不在区间里；但修改卡里带出的是
     * `editedText`（整段改后的文字），点按也该认整段——否则「关」→「关系」之后再点「关」
     * 命不中，在卡里删掉「关」时那段字又落在冻结区间之外，提交自然是空操作。
     *
     * 整段范围由修订自己存的两行快照反推：`originalText` / `editedText` 是整段，
     * [originalTexts] 给的最小原文与 [TextRevision.text] 是最小修订，两侧之差就是被裁掉的
     * 公共前后缀。对不上（老数据没有快照、跨叶不裁剪、空白归一后长度不符）时返回最小修订，
     * 行为与修复前一致；候选整段会碰到别的修订时同样退回最小修订（那条快照的选区里还有
     * 别人的改动，按整段开卡会误伤邻条）。
     */
    fun reviewSegmentInterval(r: TextRevision): IntRange {
        val minimal = reviewInterval(r)
        val sides = segmentSides(r) ?: return minimal
        val (prefixLen, suffixLen) = sides
        if (prefixLen == 0 && suffixLen == 0) return minimal
        val origStart = r.charStart - prefixLen
        val origEnd = r.charEnd + suffixLen
        if (origStart < 0 || origEnd < origStart) return minimal
        // 邻条按各自反推的整段判（两条都只是「原文里的一个区间」，半开）。
        for (other in revisions) {
            if (other === r || other == r) continue
            val oSides = segmentSides(other)
            val oStart = other.charStart - (oSides?.first ?: 0)
            val oEnd = other.charEnd + (oSides?.second ?: 0)
            val hits = if (other.isInsertion) {
                // 插入点落在整段里（含两端）都会让两段在审阅空间里重叠，保守退回。
                other.charStart in origStart..origEnd
            } else {
                oStart < origEnd && origStart < oEnd
            }
            if (hits) return minimal
        }
        val start = toReview(origStart, crossInsertions = false)
        val end = toReview(origEnd, crossInsertions = true)
        if (end <= start) return minimal
        // 换算后再按审阅空间核一次：边界上的邻条插入不能被整段吞进来。
        for (other in revisions) {
            if (other === r || other == r) continue
            val oi = reviewInterval(other)
            if (oi.first < end && start < oi.last + 1) return minimal
        }
        return start until end
    }

    /**
     * 这条修订在审阅空间里**整段**的样子（修订清单的行摘要用，与正文审阅视图**同一份**数据）。
     *
     * 整段 = 提交时那段选区里两端没改的公共字 + 这条修订的审阅空间形态（见 [overlay]）。
     * 公共字按 [segmentSides] 从两行快照反推；反推不出来时只有「一裁没裁」（快照就是最小原文）
     * 才当作没有公共字，其余一律返回 null —— 清单宁可退回快照差异，也不把行截短。
     *
     * 之所以从 [ReviewOverlay.Plan] 摊而不是另算一套：只改了标记（字一个没改）的修订在快照里
     * 两端文字相同，按字符差异一条颜色都出不来，清单会显示成普通文字，与审阅视图里的绿 `**`
     * 对不上。红 / 绿、¶、`## ` 都只在审阅空间里有，只能从这一份取。
     *
     * @return null = 取不到原书投影（本章结构未载入 / 老数据）/ 快照对不上，调用方另算。
     */
    fun reviewExcerpt(r: TextRevision): ReviewOverlay.Excerpt? {
        val plan = overlay(r) ?: return null
        val minimalOld = originalTexts[r] ?: return null
        val old = r.originalText
        val sides = when {
            old == minimalOld -> 0 to 0
            else -> segmentSides(r) ?: return null
        }
        val (prefixLen, suffixLen) = sides
        if (prefixLen < 0 || prefixLen + minimalOld.length + suffixLen != old.length) return null
        val prefix = old.substring(0, prefixLen)
        val suffix = old.substring(prefixLen + minimalOld.length)
        return ReviewOverlay.Excerpt(
            text = prefix + plan.text + suffix,
            deleted = shiftSpans(plan.deleted, prefix.length),
            inserted = shiftSpans(plan.inserted, prefix.length),
        )
    }

    /** 区间对整体平移（整段前面接了一截没改的公共字）。 */
    private fun shiftSpans(spans: IntArray, by: Int): IntArray {
        if (by == 0 || spans.isEmpty()) return spans
        return IntArray(spans.size) { spans[it] + by }
    }

    /**
     * 这条修订两端被裁掉的公共字各有多少（原书空间字符数），反推不出来时 null。
     *
     * 依据：`originalText` = 前缀 + 最小原文 + 后缀，`editedText` = 前缀 + [TextRevision.text] + 后缀。
     * 用最小原文与替换文字在各自那一行里对位，要求两侧公共字逐字相同；对位有多解时取最靠前的一处
     * （纯插入同一串重复字时，整段范围本身自洽，读者看到与提交结果一致）。
     */
    private fun segmentSides(r: TextRevision): Pair<Int, Int>? {
        val old = r.originalText
        val new = r.editedText
        if (old.isEmpty() && new.isEmpty()) return null
        val minimalOld = originalTexts[r] ?: return null
        val minimalNew = r.text
        val kept = old.length - minimalOld.length
        // 跨叶不裁剪的修订里 originalText 按段插了 `\n`、text 里是段界字符，两边长度差不同 —— 不反推。
        if (kept <= 0 || new.length - minimalNew.length != kept) return null
        for (p in 0..kept) {
            if (old.length < p + minimalOld.length || new.length < p + minimalNew.length) continue
            if (
                old.substring(p, p + minimalOld.length) != minimalOld ||
                new.substring(p, p + minimalNew.length) != minimalNew ||
                old.substring(0, p) != new.substring(0, p) ||
                old.substring(p + minimalOld.length) != new.substring(p + minimalNew.length)
            ) {
                continue
            }
            return p to (kept - p)
        }
        return null
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
         * 10 = 落在同一片叶 / 同一组上的多处修改一起重塑，不再只保住其中一条（章文字变了）。
         * 11 = 叶尾那一格插入也落进本叶（整段引用句的收尾引号以前会被吞掉，章文字变了）。
         * 12 = 修订可带显式行内样式（`*斜体*` / `<em>` 这类标记落地后按区间重建叶内容）。
         * 13 = 修订可带块级形态（标题 / 引用 / 列表 / 代码块），重放时换标签、增删包裹层。
         * 14 = 只改标记（字一个没改）的修订在审阅空间里排出标记痕迹（`## ` / `**`），章文字变了。
         * 15 = 叶内新插的换行（`<br>`）在断行处上一行末尾多排一个绿 ¶，章文字变了。 */
        const val ALGORITHM_VERSION = 15

        private const val FNV_OFFSET = -3750763034362895579L // 14695981039346656037
        private const val FNV_PRIME = 1099511628211L

        val EMPTY = ChapterRevisionMap(emptyList())
    }
}
