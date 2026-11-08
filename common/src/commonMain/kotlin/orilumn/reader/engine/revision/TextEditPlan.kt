package orilumn.reader.engine.revision

import orilumn.reader.data.revision.TextRevision
import orilumn.reader.engine.css.WhiteSpace

/**
 * 一次正文修改的提交计划（方案 §8.4 / §8.9 第 5、7 步）。
 *
 * 纯计算：输入是**渲染空间**的选区（与 `textOf` / `PageSlice` 同尺）和输入区文字，
 * 输出是原书空间的修订（删除哪些旧修订、插入哪些新修订），不碰数据库、不碰章树。
 *
 * 坐标口径：
 * - 渲染空间 = 应用修订之后的文字（用户看到、选中、输入的）；
 * - 原书空间 = 不含修订的文字（修订自身、标注、进度落盘用的）。
 *
 * 跨段（选区跨多个排版叶）时输入区用 `\n` 分隔，**结果段数 = 输入里的换行符数 + 1**
 * （方案 D-34 修订版）：
 * - 行数 = 跨的叶数：每叶一条修订，段数不变（保留行内样式）；
 * - 行数 ≠ 跨的叶数：落**一条跨叶修订**，段界用 [RevisionBlocks.SEPARATOR] 记，
 *   载入重放时按段界把中间的块合并掉 / 把一段拆成几段。
 * 叶内 `\n` 是 `<br>`，与字符流一致。
 */
sealed interface TextEditPlan {

    /** 校验失败（选区跨到图片 / 表格等不能合并的块）：卡片不关、正文不动，就地提示 [reason]。 */
    data class Rejected(val reason: String) : TextEditPlan

    /**
     * @property deletedIds 被本次修改吸收的旧修订 id（与 [inserted] 同一事务删除）；
     * @property inserted 新修订（id = 0，由数据库回填）；段数不变时每叶一条，共用 [groupId]；
     *   段数变了时只有一条（跨叶，段界见 [RevisionBlocks.SEPARATOR]）；
     * @property renderedStart 修改后重新选中区间的起点（渲染空间，等于选区起点）；
     * @property renderedEndExclusive 修改后重新选中区间的终点（渲染空间）；
     * @property noop 新文字与旧文字相同（不写库、不重排）。
     */
    data class Ready(
        val chapter: Int,
        val deletedIds: List<Long>,
        val inserted: List<TextRevision>,
        val renderedStart: Int,
        val renderedEndExclusive: Int,
        val noop: Boolean,
    ) : TextEditPlan
}

/**
 * 修订提交的纯算法（方案 §8.4）。
 *
 * 1. **按叶拆段**：选区跨几个排版叶就拆几段；输入行数等于叶数时每叶一条修订（段数不变），
 *    不等时落一条跨叶修订、结果段数 = 输入里的换行符数 + 1（见 [RevisionBlocks]）；
 * 2. **与旧修订合并**：选区碰到已有修订时整体扩张重写，被吸收的旧修订删除
 *    （不能留下半截重叠）；同点纯插入按「旧插入在前、新插入在后」合并；
 * 3. **公共前后缀裁剪**：只裁没被修订覆盖的字符，保证「改一个字只动一个字」，
 *    同时不会裁掉旧修订替换出来的文字；跨段改段数时**不裁**——裁掉边界字会把跨段缩成叶内，
 *    合并就丢了；
 * 4. **字符级对齐交给重放**：`LeafTextEditor` 在载入 / 重排时按字符归属把未变字留在
 *    原元素里，本类不重复实现。
 */
object TextEditPlanner {

    /**
     * @param bookId 目标书（写进新修订，便于宿主直接落库）；
     * @param chapter 章号；
     * @param chapterText 整章**渲染空间**文本（`textOf(chapter, 0, MAX)` 的那一串）；
     * @param blockEnds 每个排版叶的累计块尾（`textBlockEndsOf` 同尺）；null/长度不符 = 单叶；
     * @param start / [endExclusive] 选区（渲染空间，`[start, endExclusive)`）；
     * @param replacement 输入区文字（跨段时以 `\n` 分隔；结果段数 = 换行符数 + 1）；
     * @param map 本章现有修订；
     * @param groupId 本次提交的组号（段数不变时跨段多条共用）；
     * @param now 写入 `createdAt` / `updatedAt` 的时间戳（测试可传固定值）；
     * @param wsOfLeaf 取某叶的 `white-space`（提交前的替换文本预归一用；缺省按普通模式）。
     * @param mergeableLeaf 该叶能不能参与跨段合并（图片 / 表格行 / 匿名叶不能）。
     * @param toOriginal 当前文字空间 → 原书空间的单点换算。默认按**渲染空间**；审阅视图传入
     *   [ChapterRevisionMap.toOriginalFromReview]——审阅空间里原文与新增文字并列，换算口径不同。
     * @param renderedInterval 某条修订在**当前文字空间**占的区间。默认按渲染空间；审阅视图传入
     *   [ChapterRevisionMap.reviewInterval]（原区间 + 紧随其后的新文字，两半都算碰到）。
     */
    fun plan(
        bookId: Long,
        chapter: Int,
        chapterText: String,
        blockEnds: IntArray?,
        start: Int,
        endExclusive: Int,
        replacement: String,
        map: ChapterRevisionMap,
        groupId: Long,
        now: Long = 0L,
        wsOfLeaf: (Int) -> WhiteSpace = { WhiteSpace.NORMAL },
        mergeableLeaf: (Int) -> Boolean = { true },
        toOriginal: (Int) -> Int = map::toOriginal,
        renderedInterval: (TextRevision) -> IntRange = map::renderedInterval,
    ): TextEditPlan {
        if (start < 0 || endExclusive > chapterText.length || start > endExclusive) {
            return TextEditPlan.Rejected("选区超出本章范围")
        }
        if (endExclusive <= start) {
            return TextEditPlan.Rejected("请先选择要修改的文字")
        }
        val ends = blockEnds?.takeIf { it.isNotEmpty() && it.last() == chapterText.length }
        if (ends == null) {
            return planSegments(
                bookId, chapter, chapterText, map, groupId, now, wsOfLeaf, toOriginal, renderedInterval,
                listOf(Segment(start, endExclusive, replacement, 0, 0, chapterText.length)),
            )
        }
        val firstLeaf = leafOf(ends, start)
        val lastLeaf = leafOf(ends, endExclusive - 1)
        if (firstLeaf == lastLeaf) {
            return planSegments(
                bookId, chapter, chapterText, map, groupId, now, wsOfLeaf, toOriginal, renderedInterval,
                listOf(
                    Segment(
                        start, endExclusive, replacement, firstLeaf,
                        if (firstLeaf == 0) 0 else ends[firstLeaf - 1],
                        ends[firstLeaf],
                    ),
                ),
            )
        }
        // 跨段：结果段数由输入里的换行符决定（方案 D-34 修订版）。
        val lines = splitLines(replacement)
        val leafCount = lastLeaf - firstLeaf + 1
        if (lines.size != leafCount) {
            if ((firstLeaf..lastLeaf).any { !mergeableLeaf(it) }) {
                return TextEditPlan.Rejected("选区跨到图片或表格，不能合并段落")
            }
            return planMerge(
                bookId, chapter, chapterText, map, groupId, now, wsOfLeaf, toOriginal, renderedInterval,
                start, endExclusive, replacement, ends,
            )
        }
        val segments = ArrayList<Segment>(leafCount)
        for (i in firstLeaf..lastLeaf) {
            val leafStart = if (i == 0) 0 else ends[i - 1]
            val leafEnd = ends[i]
            val segStart = maxOf(start, leafStart)
            val segEnd = minOf(endExclusive, leafEnd)
            segments.add(Segment(segStart, segEnd, lines[i - firstLeaf], i, leafStart, leafEnd))
        }
        return planSegments(
            bookId, chapter, chapterText, map, groupId, now, wsOfLeaf, toOriginal, renderedInterval, segments,
        )
    }

    /**
     * 段数变了（输入行数 ≠ 选区跨的叶数）：落**一条跨叶修订**，段界用 [RevisionBlocks.SEPARATOR]。
     *
     * 吸收旧修订后区间可能扩到更多叶，首末叶按扩张后的区间重新定位。跨段改段数**不做公共
     * 前后缀裁剪**：裁掉边界上的字会把区间缩回叶内，合并 / 拆分的信息就丢了。
     */
    private fun planMerge(
        bookId: Long,
        chapter: Int,
        chapterText: String,
        map: ChapterRevisionMap,
        groupId: Long,
        now: Long,
        wsOfLeaf: (Int) -> WhiteSpace,
        toOriginal: (Int) -> Int,
        renderedInterval: (TextRevision) -> IntRange,
        start: Int,
        endExclusive: Int,
        replacement: String,
        ends: IntArray,
    ): TextEditPlan {
        val absorbed = map.revisions.filter { r ->
            val rr = renderedInterval(r)
            if (r.isInsertion) {
                rr.first > start && rr.first < endExclusive
            } else {
                rr.first < endExclusive && rr.last + 1 > start
            }
        }
        val uRenderStart = minOf(start, absorbed.minOfOrNull { renderedInterval(it).first } ?: start)
        val uRenderEnd = maxOf(endExclusive, absorbed.maxOfOrNull { renderedInterval(it).last + 1 } ?: endExclusive)
        val uFirstLeaf = leafOf(ends, uRenderStart)
        val uLastLeaf = leafOf(ends, uRenderEnd - 1)
        val uStart = toOriginal(uRenderStart)
        val uEnd = toOriginal(uRenderEnd)
        val oldText = chapterText.substring(uRenderStart, uRenderEnd)
        val relStart = start - uRenderStart
        val relEnd = endExclusive - uRenderStart
        val prefix = oldText.substring(0, relStart)
        val suffix = oldText.substring(relEnd)
        val firstLeafStart = if (uFirstLeaf == 0) 0 else ends[uFirstLeaf - 1]
        val before = when {
            prefix.isNotEmpty() -> prefix.last()
            uRenderStart <= firstLeafStart -> null
            else -> chapterText.getOrNull(uRenderStart - 1)
        }
        val after = when {
            suffix.isNotEmpty() -> suffix.first()
            uRenderEnd >= ends[uLastLeaf] -> null
            else -> chapterText.getOrNull(uRenderEnd)
        }
        val lines = splitLines(replacement)
        val ws = wsOfLeaf(uFirstLeaf)
        // 逐行预归一：段界就是块边界，两侧空格由 CSS 丢弃（首行看前缀、末行看后缀）。
        val normLines = lines.mapIndexed { i, line ->
            LeafTextEditor.preNormalize(
                line,
                ws,
                if (i == 0) before else null,
                if (i == lines.lastIndex) after else null,
            )
        }
        val newText = prefix + normLines.joinToString(RevisionBlocks.SEPARATOR.toString()) + suffix
        val renderedLen = RevisionBlocks.renderedLength(newText)
        val insertedLen = normLines.sumOf { it.length }
        return TextEditPlan.Ready(
            chapter = chapter,
            deletedIds = absorbed.map { it.id },
            inserted = listOf(
                TextRevision(
                    bookId = bookId,
                    chapter = chapter,
                    charStart = uStart,
                    charEnd = uEnd,
                    text = newText,
                    // 清单「原文」行按段断行显示（正文投影里段间没有分隔符）。
                    originalText = selectionLines(chapterText, ends, start, endExclusive),
                    editedText = replacement,
                    delta = renderedLen - (uEnd - uStart),
                    groupId = groupId,
                    createdAt = now,
                    updatedAt = now,
                ),
            ),
            renderedStart = start,
            renderedEndExclusive = start + insertedLen,
            noop = false,
        )
    }

    /** 选区原文按段断行（只用于审阅清单显示）。 */
    private fun selectionLines(text: String, ends: IntArray, start: Int, endExclusive: Int): String {
        val out = StringBuilder(endExclusive - start + 4)
        var from = start
        for (end in ends) {
            if (end <= from) continue
            if (from >= endExclusive) break
            val segEnd = minOf(end, endExclusive)
            if (out.isNotEmpty()) out.append('\n')
            out.append(text, from, segEnd)
            from = segEnd
        }
        if (out.isEmpty()) out.append(text, start, endExclusive)
        return out.toString()
    }

    private data class Segment(
        val start: Int,
        val endExclusive: Int,
        val text: String,
        /** 该段所在叶（`wsOfLeaf` 的键）与该叶在渲染空间的边界（边界空格按 CSS 丢弃）。 */
        val leaf: Int,
        val leafRenderStart: Int,
        val leafRenderEnd: Int,
    )

    private fun planSegments(
        bookId: Long,
        chapter: Int,
        chapterText: String,
        map: ChapterRevisionMap,
        groupId: Long,
        now: Long,
        wsOfLeaf: (Int) -> WhiteSpace,
        toOriginal: (Int) -> Int,
        renderedInterval: (TextRevision) -> IntRange,
        segments: List<Segment>,
    ): TextEditPlan {
        val deleted = LinkedHashSet<Long>()
        val inserted = ArrayList<TextRevision>(segments.size)
        var renderedLen = 0
        for (seg in segments) {
            val built = planSegment(
                bookId, chapter, chapterText, map, seg, groupId, now, wsOfLeaf, toOriginal, renderedInterval,
            )
            renderedLen += built.renderedLength
            deleted.addAll(built.absorbedIds)
            built.revision?.let { inserted.add(it) }
        }
        val start = segments.firstOrNull()?.start ?: 0
        return TextEditPlan.Ready(
            chapter = chapter,
            deletedIds = deleted.toList(),
            inserted = inserted,
            renderedStart = start,
            renderedEndExclusive = start + renderedLen,
            noop = inserted.isEmpty() && deleted.isEmpty(),
        )
    }

    private class Built(
        val absorbedIds: List<Long>,
        val revision: TextRevision?,
        /** 这一段应用后的渲染文字长度（重新选中区间按它算，含预归一后的空白）。 */
        val renderedLength: Int,
    )

    /**
     * 一段（单叶内）的修订：扩张到覆盖所有被碰到的旧修订，替换文本按「旧渲染文字里
     * 把选区换成新文字」算，再裁掉两端没被修订覆盖的公共字。
     */
    private fun planSegment(
        bookId: Long,
        chapter: Int,
        chapterText: String,
        map: ChapterRevisionMap,
        seg: Segment,
        groupId: Long,
        now: Long,
        wsOfLeaf: (Int) -> WhiteSpace,
        toOriginal: (Int) -> Int,
        renderedInterval: (TextRevision) -> IntRange,
    ): Built {
        val segStart = seg.start
        val segEnd = seg.endExclusive
        val absorbed = map.revisions.filter { r ->
            val rr = renderedInterval(r)
            if (r.isInsertion) {
                if (segStart == segEnd) rr.first == segStart
                else rr.first > segStart && rr.first < segEnd
            } else {
                rr.first < segEnd && rr.last + 1 > segStart
            }
        }
        val origStart = toOriginal(segStart)
        val origEnd = toOriginal(segEnd)
        val uStart = minOf(origStart, absorbed.minOfOrNull { it.charStart } ?: origStart)
        val uEnd = maxOf(origEnd, absorbed.maxOfOrNull { it.charEnd } ?: origEnd)
        val uRenderStart = minOf(segStart, absorbed.minOfOrNull { renderedInterval(it).first } ?: segStart)
        val uRenderEnd = maxOf(segEnd, absorbed.maxOfOrNull { renderedInterval(it).last + 1 } ?: segEnd)
        val oldText = chapterText.substring(uRenderStart, uRenderEnd)
        val relStart = segStart - uRenderStart
        val relEnd = segEnd - uRenderStart
        // 纯插入：保留旧插入文字在前，新文字接在后面（同点纯插入合并）。
        val rawText = if (segStart == segEnd) {
            oldText + seg.text
        } else {
            oldText.substring(0, relStart) + seg.text + oldText.substring(relEnd)
        }
        // 提交前预归一：与载入重放同一套空白规则，delta 才等于实测长度（方案 §8.4 第 6 步）。
        // 前后邻字符取叶内、扩张区间之外的真实字符；叶边界为 null（CSS 丢弃边界空白）。
        val before = if (uRenderStart <= seg.leafRenderStart) null else chapterText.getOrNull(uRenderStart - 1)
        val after = if (uRenderEnd >= seg.leafRenderEnd) null else chapterText.getOrNull(uRenderEnd)
        val newText = LeafTextEditor.preNormalize(rawText, wsOfLeaf(seg.leaf), before, after)

        if (newText == oldText) {
            // 输入的文字与旧渲染文字逐字相同（或只差会被空白归一吃掉的空白）：不写库。
            return Built(emptyList(), null, newText.length)
        }

        // 只能裁「没有被旧修订覆盖」的两端：那一段渲染文字与原文逐字相同，裁掉才不会丢旧替换。
        // 没有旧修订时整段都可裁（改一个字只落一个字，方案 §8.4）。
        val firstRevised = absorbed.minOfOrNull { renderedInterval(it).first } ?: uRenderEnd
        val lastRevisedEnd = absorbed.maxOfOrNull { renderedInterval(it).last + 1 } ?: uRenderStart
        val maxPrefix = (firstRevised - uRenderStart)
            .coerceIn(0, minOf(oldText.length, newText.length))
        var prefix = 0
        while (prefix < maxPrefix && oldText[prefix] == newText[prefix]) prefix++
        val maxSuffix = (uRenderEnd - lastRevisedEnd)
            .coerceIn(0, minOf(oldText.length - prefix, newText.length - prefix))
        var suffix = 0
        while (
            suffix < maxSuffix &&
            suffix < newText.length - prefix &&
            oldText[oldText.length - 1 - suffix] == newText[newText.length - 1 - suffix]
        ) {
            suffix++
        }
        val finalText = newText.substring(prefix, newText.length - suffix)
        val finalStart = uStart + prefix
        val finalEnd = uEnd - suffix
        // 审阅清单两行：整段选区改之前 / 改之后的文字（渲染空间，按叶各存一份）。
        // 与上面的「最小修订」无关：公共前后缀照裁不误，但清单里仍显示整段，
        // 读者才看得出「改之前是什么、改之后是什么」（纯插入也显示被选中的那段原文）。
        val selected = chapterText.substring(segStart, segEnd)
        return Built(
            absorbedIds = absorbed.map { it.id },
            revision = TextRevision(
                bookId = bookId,
                chapter = chapter,
                charStart = finalStart,
                charEnd = finalEnd,
                text = finalText,
                originalText = selected,
                editedText = seg.text,
                delta = finalText.length - (finalEnd - finalStart),
                groupId = groupId,
                createdAt = now,
                updatedAt = now,
            ),
            renderedLength = newText.length,
        )
    }

    /** 累计块尾 → 偏移所在的叶下标（第一个「块尾 > offset」）。 */
    private fun leafOf(ends: IntArray, offset: Int): Int {
        var i = 0
        while (i < ends.size && ends[i] <= offset) i++
        return i.coerceIn(0, ends.size - 1)
    }

    /** 保留空行的 `\n` 切分（`String.split` 的尾部空串行为在跨平台实现里不够直观）。 */
    internal fun splitLines(text: String): List<String> {
        val out = ArrayList<String>()
        var from = 0
        for (i in text.indices) {
            if (text[i] == '\n') {
                out.add(text.substring(from, i))
                from = i + 1
            }
        }
        out.add(text.substring(from))
        return out
    }
}
