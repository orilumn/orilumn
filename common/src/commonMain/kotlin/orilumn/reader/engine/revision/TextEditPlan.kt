package orilumn.reader.engine.revision

import orilumn.reader.data.revision.TextRevision
import orilumn.reader.engine.css.WhiteSpace
import orilumn.reader.engine.text.preprocess.CjkLatinSpacing

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

    /** 校验失败（选区跨到表格 / 整块图片这类不能合并的块）：卡片不关、正文不动，就地提示 [reason]。 */
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

    /** 逐叶校验时的提示：图片只能整张删；表格行不能和段落混着一起改。 */
    private const val SPECIAL_LEAF_REASON =
        "图片只能整张删除、不能改成文字；表格行不能和别的段落混在一起改，" +
            "要改行列或合并方式请只选中表格的整行"

    /** 表格行列不自洽时的提示（整表修改只在网格能铺成矩形时放行）。 */
    private const val TABLE_GRID_REASON =
        "表格的行列对不上：每一行的列数必须与整张表一致（合并格也不能重叠或留空）"

    /**
     * 图片叶 / 表格行叶在提交前的形状校验（段数不变、逐叶编辑时走这里）。
     *
     * - 图片叶：这一段文字只能是原样的 `U+FFFC` 占位（保留）或空串（整张删除）；
     *   换成任何别的文字都会把图悄悄变成正文，直接拒绝。
     * - 表格行：这一段必须仍能被认成表格行，且格数 / 表头 / 合并方式与原来相同；
     *   **格里的文字长度可以变**（读者就是在改格子里的字），列数、合并方式一变就拒绝。
     */
    private fun checkSpecialLeaf(
        leaf: Int,
        piece: InlineMarkup.Segment,
        imageLeaf: (Int) -> Boolean,
        tableOfLeaf: (Int) -> TableRowInfo?,
    ): Boolean {
        if (imageLeaf(leaf)) return piece.text.isEmpty() || piece.text == "\uFFFC"
        val original = tableOfLeaf(leaf) ?: return true
        val now = piece.table ?: return false
        return original.sameShape(now)
    }

    /**
     * @param bookId 目标书（写进新修订，便于宿主直接落库）；
     * @param chapter 章号；
     * @param chapterText 整章**渲染空间**文本（`textOf(chapter, 0, MAX)` 的那一串）；
     * @param blockEnds 每个排版叶的累计块尾（`textBlockEndsOf` 同尺）；null/长度不符 = 单叶；
     * @param start / [endExclusive] 选区（渲染空间，`[start, endExclusive)`）；
     * @param replacement 输入区文字（跨段时以 `\n` 分隔；结果段数 = 换行符数 + 1）；
     * @param blocks 输入区**按块切好**的分段（正文修改卡：一行一块，块自带块级形态）；
     *   null = 老调用方，按 [replacement] 里的 `\n` 切，且把新敲的换行当段界（[promoteTypedBreaks]）。
     *   给了它就以它为准：块内的 `\n` 一律是书里本来就有的换行（`<br>` / 代码块内换行）。
     * @param map 本章现有修订；
     * @param groupId 本次提交的组号（段数不变时跨段多条共用）；
     * @param now 写入 `createdAt` / `updatedAt` 的时间戳（测试可传固定值）；
     * @param wsOfLeaf 取某叶的 `white-space`（提交前的替换文本预归一用；缺省按普通模式）。
     * @param mergeableLeaf 该叶能不能参与跨段合并（整块图片 / 表格行 / 匿名叶不能）。
     * @param toOriginal 当前文字空间 → 原书空间的单点换算。默认按**渲染空间**；审阅视图传入
     *   [ChapterRevisionMap.toOriginalFromReview]——审阅空间里原文与新增文字并列，换算口径不同。
     * @param renderedInterval 某条修订在**当前文字空间**占的区间。默认按渲染空间；审阅视图传入
     *   [ChapterRevisionMap.reviewInterval]（原区间 + 紧随其后的新文字，两半都算碰到）。
     * @param currentKindOf 某一叶**现在**的块形态（卡片提交时由宿主给；null = 调用方不给，
     *   按老口径照写）。目标形态与它相同时不写进修订——否则每一块都会白落一条「什么也没改」的
     *   修订，编辑清单里全是空条目；这一叶**没有**块形态（`div` / 表格行）时也不写：它不归
     *   markdown 管，卡片也就不该改它。
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
        replacementStyles: List<InlineMarkup.StyleSpan>? = null,
        wsOfLeaf: (Int) -> WhiteSpace = { WhiteSpace.NORMAL },
        mergeableLeaf: (Int) -> Boolean = { true },
        toOriginal: (Int) -> Int = map::toOriginal,
        renderedInterval: (TextRevision) -> IntRange = map::renderedInterval,
        blocks: List<InlineMarkup.Segment>? = null,
        currentKindOf: ((leafIndex: Int) -> BlockKind?)? = null,
        /** 该叶是**整块图片**（章字符流里只占一个 `U+FFFC` 占位）。 */
        imageLeaf: (leafIndex: Int) -> Boolean = { false },
        /** 该叶是表格行时的当前格结构（null = 不是表格行）。 */
        tableOfLeaf: (leafIndex: Int) -> TableRowInfo? = { null },
        /** 表格行叶 → 它所属**整张表**在章字符流里的区间（渲染空间；null = 不是表格行）。 */
        tableRangeOf: (leafIndex: Int) -> IntRange? = { null },
        /**
         * 这张表上压着的**整表修订**（审阅视图提交时由宿主单独给）。
         *
         * 审阅视图的规划在**原书空间**算，`map` 里放不下它（放进去会按渲染区间错位）；
         * 但行来源必须沿用它，否则重开卡片再改一次时，第一次改过的行会被当成新行，
         * 原有的行 / 格样式就丢了。null = 没有（普通视图 / 首次改这张表）。
         */
        previousTable: TextRevision? = null,
    ): TextEditPlan {
        if (start < 0 || endExclusive > chapterText.length || start > endExclusive) {
            return TextEditPlan.Rejected("选区超出本章范围")
        }
        if (endExclusive <= start) {
            return TextEditPlan.Rejected("请先选择要修改的文字")
        }
        val explicit = blocks != null && blocks.isNotEmpty()
        val pieces = blocks?.takeIf { it.isNotEmpty() }
            ?: splitLines(replacement).map { InlineMarkup.Segment(it) }
        // 选区两端贴着「接缝上被吃成零宽的分隔空白」时，先把它们吃进替换区间。
        // 否则读者看不见的那半个空格会在新文字把接缝改没之后突然现形（`val 的` → `声明 的`）。
        // 只有真的改了可见文字、且新接缝不再吃它时才吃；纯改样式 / 原样确认不动它。
        // 整张表（全是表格行 / 删空）例外：表格边界是块界，吃进表外的字会把整表选法破掉。
        val tableish = pieces.isNotEmpty() &&
            (pieces.all { it.table != null } || pieces.all { it.text.isEmpty() })
        val edit = if (tableish) {
            start to endExclusive
        } else {
            absorbVisibleSeamSpaces(chapterText, start, endExclusive, replacement)
        }
        val editStart = edit.first
        val editEnd = edit.second
        val lines = pieces.map { it.text }
        val perLine = lineSpans(replacementStyles, lines)
        // 老调用方只有一整串文字：段界只能靠「新敲的换行」去猜，单叶内多行仍按一整段交给
        // [planSegment]，由它用字符级对齐把 `\n` 升成段界（[promoteTypedBreaks]）。
        val legacySolo = !explicit && pieces.size > 1
        val solo = if (legacySolo) {
            InlineMarkup.Segment(InlineMarkup.joinSegments(pieces))
        } else {
            pieces.first()
        }
        val ends = blockEnds?.takeIf { it.isNotEmpty() && it.last() == chapterText.length }
        if (ends == null) {
            return planSegments(
                bookId, chapter, chapterText, map, groupId, now, wsOfLeaf, toOriginal, renderedInterval,
                listOf(
                    Segment(
                        editStart, editEnd, solo, !legacySolo, 0, 0, chapterText.length, perLine?.first(),
                    ),
                ),
                currentKindOf,
            )
        }
        val firstLeaf = leafOf(ends, editStart)
        val lastLeaf = leafOf(ends, editEnd - 1)
        // 整张表一次改：卡片把选区扩到整张表时，行数 / 每行列数都可以变 —— 只要网格自洽就放行
        // （删整行、加整行、删整表、每行增删列、改合并方式都走这一条）。
        val wholeTable = wholeTableRange(tableRangeOf, firstLeaf, lastLeaf, editStart, editEnd)
        if (wholeTable != null && (pieces.all { it.table != null } || pieces.all { it.text.isEmpty() })) {
            return planTable(
                bookId, chapter, chapterText, map, groupId, now, toOriginal, renderedInterval,
                editStart, editEnd, pieces, replacementStyles,
                firstLeaf, lastLeaf, tableOfLeaf, wholeTable, tableRangeOf, previousTable,
            )
        }
        // 选区**整段落在同一张表里**（不要求覆盖整张表）：只选中其中几行也能删行 / 加行 / 改格数。
        // 纯改格里的字且不涉及表头时仍走下面的逐行小修订；结构一变就按**整张表**重建并校验网格。
        val tableRange = tableRangeOf(firstLeaf)?.takeIf { tableRangeOf(lastLeaf) == it }
        val tableOnly = tableRange != null && tableish &&
            editStart >= tableRange.first && editEnd <= tableRange.last + 1
        if (tableOnly) {
            val table = tableRange
            val selectedRows = (firstLeaf..lastLeaf).mapNotNull(tableOfLeaf)
            val deletingRows = pieces.all { it.text.isEmpty() } && pieces.none { it.table != null }
            val shapeChanged = deletingRows || pieces.size != selectedRows.size ||
                pieces.indices.any { i ->
                    val now = pieces[i].table ?: return@any true
                    val original = selectedRows.getOrNull(i) ?: return@any true
                    !original.sameShape(now, ignoreHeader = !now.html)
                }
            // 管道行写不出 `<th>`：表头行只改字也要走整表重建，按原位把表头补回去。
            val pipeHeader = !deletingRows && pieces.indices.any { i ->
                val now = pieces[i].table
                now != null && !now.html && selectedRows.getOrNull(i)?.cells?.any { it.header } == true
            }
            // 表上已经压着一处整表修订时，连只改字也按整表重写（旧修订整段吸收）。
            val hasWholeTableRevision = map.revisions.any { r ->
                isWholeTableRevision(r) && renderedInterval(r).let { rr ->
                    rr.first < table.last + 1 && rr.last + 1 > table.first
                }
            }
            if (shapeChanged || pipeHeader || hasWholeTableRevision || previousTable != null) {
                return planTable(
                    bookId, chapter, chapterText, map, groupId, now, toOriginal, renderedInterval,
                    editStart, editEnd, pieces, replacementStyles,
                    firstLeaf, lastLeaf, tableOfLeaf, table, tableRangeOf, previousTable,
                )
            }
        }
        // 这张表上已经压着一处**整表修订**时，本次编辑必须也正好是这张表：否则吸收 / 分段落字
        // 都拿不准该按哪把尺（整表修订的文字不是逐行可切的）。
        if (map.revisions.any { r ->
                isWholeTableRevision(r) && renderedInterval(r).let { rr ->
                    if (r.isInsertion) rr.first > editStart && rr.first < editEnd
                    else rr.first < editEnd && rr.last + 1 > editStart
                }
            }
        ) {
            return TextEditPlan.Rejected("这张表上已经有一处整表修改；请只选中这张表再改")
        }
        if (firstLeaf == lastLeaf && (legacySolo || pieces.size == 1)) {
            if (!checkSpecialLeaf(firstLeaf, pieces.first(), imageLeaf, tableOfLeaf)) {
                return TextEditPlan.Rejected(SPECIAL_LEAF_REASON)
            }
            return planSegments(
                bookId, chapter, chapterText, map, groupId, now, wsOfLeaf, toOriginal, renderedInterval,
                listOf(
                    Segment(
                        editStart, editEnd, solo, !legacySolo, firstLeaf,
                        if (firstLeaf == 0) 0 else ends[firstLeaf - 1],
                        ends[firstLeaf],
                        perLine?.first(),
                    ),
                ),
                currentKindOf,
            )
        }
        // 跨段：结果段数由输入区分成了几块决定（方案 D-34 修订版）。
        val leafCount = lastLeaf - firstLeaf + 1
        if (pieces.size != leafCount) {
            // 图片叶、表格行叶、匿名文本叶没有可合并的块结构：只有**真的要合并 / 拆段**时才拒绝。
            // 段数不变（一叶一段）时它们照常逐叶编辑：图片可以整张删掉，表格可以改格里的字。
            if ((firstLeaf..lastLeaf).any { !mergeableLeaf(it) }) {
                return TextEditPlan.Rejected("选区跨到整块图片或表格，不能合并或拆段")
            }
            return planMerge(
                bookId, chapter, chapterText, map, groupId, now, wsOfLeaf, toOriginal, renderedInterval,
                editStart, editEnd, pieces, ends, perLine, replacementStyles,
            )
        }
        val segments = ArrayList<Segment>(leafCount)
        for (i in firstLeaf..lastLeaf) {
            val piece = pieces[i - firstLeaf]
            if (!checkSpecialLeaf(i, piece, imageLeaf, tableOfLeaf)) {
                return TextEditPlan.Rejected(SPECIAL_LEAF_REASON)
            }
            val leafStart = if (i == 0) 0 else ends[i - 1]
            val leafEnd = ends[i]
            val segStart = maxOf(editStart, leafStart)
            val segEnd = minOf(editEnd, leafEnd)
            segments.add(
                Segment(
                    segStart, segEnd, piece, true, i, leafStart, leafEnd,
                    perLine?.get(i - firstLeaf),
                ),
            )
        }
        return planSegments(
            bookId, chapter, chapterText, map, groupId, now, wsOfLeaf, toOriginal, renderedInterval, segments,
            currentKindOf,
        )
    }

    /**
     * 选区是否**正好是一整张表**：首末叶都是同一张表的行，且选区区间与整张表的字符区间逐字相等。
     *
     * 返回表的字符区间（渲染空间）；null = 不是整表选法（半张表、表格前后还带着段落…）。
     */
    private fun wholeTableRange(
        tableRangeOf: (Int) -> IntRange?,
        firstLeaf: Int,
        lastLeaf: Int,
        start: Int,
        endExclusive: Int,
    ): IntRange? {
        if (lastLeaf < firstLeaf) return null
        val range = tableRangeOf(firstLeaf) ?: return null
        if (tableRangeOf(lastLeaf) != range) return null
        if (range.first != start || range.last + 1 != endExclusive) return null
        return range
    }

    /** 这条修订是不是**整表结构修订**（首行短码是大写 `T`，见 [TableRowInfo.wire]）。 */
    private fun isWholeTableRevision(r: TextRevision): Boolean =
        r.editedBlocks.firstOrNull()?.startsWith("T") == true

    /**
     * 审阅空间的纯插入提交（方案 v3.1「添加文字」的最小落地）：在**原书** [at] 处插入 [replacement]，
     * 原位置一个字都不替换。
     *
     * 为什么需要单独一条：[plan] 的选区必须非空（替换语义），而审阅视图里改「一条纯插入的修订」
     * 时选区只盖得住新添的那几个字——换算回原书空间是空区间，按替换提交会被当成「没选字」拒掉。
     * 这里给的正是这条修订的本义：在原位置落一条新插入（旧插入由调用方一并作废）。
     *
     * 叶归属按插入点算（`leafOf` 取「块尾 > offset」的第一个叶），空白预归一与公共前后缀裁剪
     * 仍走 [planSegment] 同一条路径，`\n` 在叶内是 `<br>`（与 [plan] 一致）。
     */
    fun planInsertion(
        bookId: Long,
        chapter: Int,
        chapterText: String,
        at: Int,
        replacement: String,
        blockEnds: IntArray?,
        groupId: Long,
        now: Long = 0L,
        replacementStyles: List<InlineMarkup.StyleSpan>? = null,
        wsOfLeaf: (Int) -> WhiteSpace = { WhiteSpace.NORMAL },
        blocks: List<InlineMarkup.Segment>? = null,
        currentKindOf: ((leafIndex: Int) -> BlockKind?)? = null,
    ): TextEditPlan {
        if (at < 0 || at > chapterText.length) return TextEditPlan.Rejected("选区超出本章范围")
        val ends = blockEnds?.takeIf { it.isNotEmpty() && it.last() == chapterText.length }
        val leaf = if (ends == null) 0 else leafOf(ends, at)
        val leafStart = if (ends == null || leaf == 0) 0 else ends[leaf - 1]
        val leafEnd = if (ends == null) chapterText.length else ends[leaf]
        val pieces = blocks?.takeIf { it.isNotEmpty() }
            ?: splitLines(replacement).map { InlineMarkup.Segment(it) }
        val solo = if (pieces.size == 1) {
            pieces.first()
        } else {
            InlineMarkup.Segment(InlineMarkup.joinSegments(pieces))
        }
        return planSegments(
            bookId, chapter, chapterText, ChapterRevisionMap.EMPTY, groupId, now, wsOfLeaf,
            { it }, { r -> r.charStart until r.charEnd },
            listOf(
                Segment(
                    at, at, solo, pieces.size == 1, leaf, leafStart, leafEnd,
                    // 纯插入只带一段样式：多行（并段 / 拆段）留给整段重放去按叶切，这里不猜。
                    if (pieces.size == 1) {
                        lineSpans(replacementStyles, listOf(solo.text))?.firstOrNull()
                    } else {
                        null
                    },
                ),
            ),
            currentKindOf,
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
        pieces: List<InlineMarkup.Segment>,
        ends: IntArray,
        perLine: List<List<InlineMarkup.StyleSpan>>?,
        replacementStyles: List<InlineMarkup.StyleSpan>?,
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
        val lines = pieces.map { it.text }
        val leafCount = uLastLeaf - uFirstLeaf + 1
        // 逐块预归一：段界就是块边界，两侧空格由 CSS 丢弃（首块看前缀、末块看后缀）。
        // 块数正好对得上叶数时逐叶取空白模式 —— 代码块里的空格 / 换行不能被并成普通段落那套。
        val normLines = lines.mapIndexed { i, line ->
            val ws = if (lines.size == leafCount) wsOfLeaf(uFirstLeaf + i) else wsOfLeaf(uFirstLeaf)
            LeafTextEditor.preNormalize(
                line,
                ws,
                if (i == 0) before else null,
                if (i == lines.lastIndex) after else null,
            )
        }
        val newText = prefix + normLines.joinToString(RevisionBlocks.SEPARATOR.toString()) + suffix
        // 样式区间按行搬进合并后的文字（段界占一位；每行各自夹到自己那一段）。
        val mergedStyles = if (perLine == null) {
            null
        } else {
            val out = ArrayList<InlineMarkup.StyleSpan>()
            var cursor = prefix.length
            for (j in normLines.indices) {
                val lineSpans = perLine.getOrNull(j).orEmpty()
                if (lineSpans.isNotEmpty()) {
                    for (sp in lineSpans) {
                        val s0 = cursor + sp.start
                        val e0 = cursor + sp.endExclusive
                        if (e0 > s0) out.add(InlineMarkup.StyleSpan(s0, e0, sp.sig))
                    }
                }
                cursor += normLines[j].length + 1
            }
            out
        }
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
                    editedText = InlineMarkup.joinSegments(pieces),
                    editedBlocks = editedBlocksOf(pieces),
                    blockKinds = pieces.map { it.block?.wire ?: "" },
                    styles = mergedStyles?.let(InlineMarkup::toStored),
                    // 合并后的 styles 是「前缀 + 新文字 + 后缀」的坐标；卡片要的是输入区原文的坐标。
                    editedStyles = replacementStyles?.let(InlineMarkup::toStored),
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

    /**
     * **一整张表**一次改：行数、每行格数、合并方式都可以与原来不同，只要 [TableGrid] 判它们
     * 能铺成一个矩形网格就放行。0 行 = 删掉整张表。
     *
     * 选区可以覆盖整张表，也可以只覆盖其中连续几行：未选中的行原样保留，和卡片里的新行拼成
     * 整张表后再校验。落库仍是一条修订：区间 = **整张表**的字符区间（不裁公共前后缀——行界
     * 不是普通文字），`text` = 各行文字**直接相接**（行界由 `editedBlocks` 的 `t…` 短码长度
     * 给出，不用 [RevisionBlocks.SEPARATOR]）；首行短码用大写 `T` 标记「这是整表结构修订」。
     * 重放按它整表重建（见 `ChapterRevisionReplay.TableEdit`）。
     */
    private fun planTable(
        bookId: Long,
        chapter: Int,
        chapterText: String,
        map: ChapterRevisionMap,
        groupId: Long,
        now: Long,
        toOriginal: (Int) -> Int,
        renderedInterval: (TextRevision) -> IntRange,
        start: Int,
        endExclusive: Int,
        pieces: List<InlineMarkup.Segment>,
        replacementStyles: List<InlineMarkup.StyleSpan>?,
        firstLeaf: Int,
        lastLeaf: Int,
        tableOfLeaf: (Int) -> TableRowInfo?,
        tableRange: IntRange,
        tableRangeOf: (Int) -> IntRange?,
        previousTable: TextRevision? = null,
    ): TextEditPlan {
        // 整张表被删空：输入区一个表格行都不剩（读者把行全删了）。留着空 `<tr>` 标签不算删表。
        val deleting = pieces.all { it.text.isEmpty() } && pieces.none { it.table != null }
        val rows = if (deleting) emptyList() else pieces.mapNotNull { it.table }
        if (!deleting && rows.size != pieces.size) {
            return TextEditPlan.Rejected("表格里混进了非表格行；一行要么是表格行，要么整张表一起删掉")
        }
        // 结构修订必须覆盖整张表：向两侧扩到同一张表的所有行。重放按区间整表重建，
        // 漏掉任何一行都会把未选中的行重复 / 丢掉。
        var firstRowLeaf = firstLeaf
        while (firstRowLeaf > 0 && tableRangeOf(firstRowLeaf - 1) == tableRange) firstRowLeaf--
        var lastRowLeaf = lastLeaf
        while (tableRangeOf(lastRowLeaf + 1) == tableRange) lastRowLeaf++
        val originalRows = (firstRowLeaf..lastRowLeaf).mapNotNull(tableOfLeaf)
        if (originalRows.size != lastRowLeaf - firstRowLeaf + 1) {
            return TextEditPlan.Rejected("表格结构尚未就绪，请稍后再试")
        }
        val selFirst = firstLeaf - firstRowLeaf
        val selLast = lastLeaf - firstRowLeaf
        if (selFirst < 0 || selLast < selFirst || selLast >= originalRows.size) {
            return TextEditPlan.Rejected("表格边界对不上，请重新选中后再改")
        }
        val selectedOriginal = originalRows.subList(selFirst, selLast + 1)
        // 新行 → 选中旧行的真实来源：删了一行时不能按位置配，否则删行后面的行全被当成改字。
        val selectedTexts = rowSegments(chapterText, start, selectedOriginal).map { it.text }
        val newTexts = if (deleting) emptyList() else pieces.map { it.text }
        // 表内已有修订整段吸收：整表重写之后，按行落字的旧修订不再有意义。
        val absorbed = map.revisions.filter { r ->
            val rr = renderedInterval(r)
            if (r.isInsertion) {
                rr.first > tableRange.first && rr.first < tableRange.last + 1
            } else {
                rr.first < tableRange.last + 1 && rr.last + 1 > tableRange.first
            }
        }
        // 这张表上已经压着一条整表修订：本次卡片里的行是**上一次改完的结果**，不是原书行。
        // 先按行内容对齐到上一次的新表，再顺着它的行来源折回原书行；否则第一次改过字的行
        // 对不上原书文字，会被当成新加的行，行 / 格样式全丢。
        val prevWhole = previousTable ?: absorbed.firstOrNull(::isWholeTableRevision)
        val prevRows = prevWhole?.let(::tableRowsOfRevision)
        // 老修订没存过行来源时按内容对齐反推一次（改过字的行由 LCS 空档按位置配对），
        // 否则重开卡片时它们会被当成新行，原来的行 / 格样式就丢了。
        val prevSources: List<Int>? = prevWhole?.rowSources ?: prevRows?.let { pr ->
            val oldTexts = rowSegments(chapterText, tableRange.first, originalRows).map { it.text }
            alignTableRows(oldTexts, originalRows, pr.map { it.text }, pr.map { it.table!! }).toList()
        }
        val newSources = if (previousTable != null && prevRows != null && prevSources != null &&
            prevRows.size == prevSources.size
        ) {
            val toPrev = alignTableRows(
                prevRows.map { it.text },
                prevRows.map { it.table!! },
                newTexts,
                rows,
            )
            IntArray(toPrev.size) { i ->
                val p = toPrev[i]
                if (p in prevSources.indices) prevSources[p] else -1
            }
        } else {
            alignTableRows(selectedTexts, selectedOriginal, newTexts, rows)
        }
        // 管道行写不出「哪一格是 `<th>`」：按**来源行**把原表的表头补回（读者改过的
        // `<th>` / `<td>` 形式自己带着，不动）。
        val restoredRows = rows.mapIndexed { i, row ->
            if (row.html) return@mapIndexed row
            val src = newSources.getOrNull(i) ?: -1
            if (src < 0) return@mapIndexed row
            val original = selectedOriginal.getOrNull(src) ?: return@mapIndexed row
            if (original.cells.size != row.cells.size) return@mapIndexed row
            row.copy(
                cells = row.cells.mapIndexed { k, cell ->
                    if (cell.header) cell else cell.copy(header = original.cells[k].header)
                },
            )
        }
        val beforeRows = originalRows.subList(0, selFirst)
        val afterRows = originalRows.subList(selLast + 1, originalRows.size)
        val combined = beforeRows + restoredRows + afterRows
        if (!TableGrid.consistent(combined)) return TextEditPlan.Rejected(TABLE_GRID_REASON)
        // 未选中的行按原样拼回去；选区必须正好贴住这几行的整行文字，前缀 / 后缀才切得开。
        val prefix = chapterText.substring(tableRange.first, start)
        val suffix = chapterText.substring(endExclusive, tableRange.last + 1)
        if (prefix.length != beforeRows.sumOf { it.textLength } ||
            suffix.length != afterRows.sumOf { it.textLength }
        ) {
            return TextEditPlan.Rejected("表格边界对不上，请重新选中后再改")
        }
        val beforeSegments = rowSegments(chapterText, tableRange.first, beforeRows)
        val afterSegments = rowSegments(chapterText, endExclusive, afterRows)
        val newSegments = if (deleting) {
            emptyList()
        } else {
            pieces.mapIndexed { i, p ->
                InlineMarkup.Segment(p.text, p.block, restoredRows.getOrNull(i) ?: p.table)
            }
        }
        val allSegments = beforeSegments + newSegments + afterSegments
        val oldText = chapterText.substring(tableRange.first, tableRange.last + 1)
        val newText = allSegments.joinToString("") { it.text }
        // 每行来自旧表哪一行（`-1` = 读者新加）：重放按它原样留下没选中的行，
        // 删行后同一位置也不会错拿前一行的样式。
        // 序号是**当前渲染表**的行号；若这张表上还压着一条整表修订（本次要吸收它），
        // 重放却是从原书那棵树起排的，得按那条修订自己的行来源再折算回原书。
        val renderedSources = ArrayList<Int>(allSegments.size)
        beforeSegments.indices.forEach { renderedSources += it }
        newSegments.indices.forEach { i ->
            val src = newSources.getOrNull(i) ?: -1
            renderedSources += if (src >= 0) selFirst + src else -1
        }
        afterSegments.indices.forEach { i -> renderedSources += selLast + 1 + i }
        val rowSources: List<Int>? = when {
            // 审阅视图的规划已经在原书空间：`newSources` 已顺着上一条整表修订折回原书行。
            previousTable != null -> renderedSources
            prevWhole == null -> renderedSources
            prevSources == null -> null
            prevSources.size != originalRows.size -> null
            else -> renderedSources.map { r -> if (r < 0) -1 else prevSources[r] }
        }
        val uRenderStart = minOf(tableRange.first, absorbed.minOfOrNull { renderedInterval(it).first } ?: tableRange.first)
        val uRenderEnd = maxOf(
            tableRange.last + 1,
            absorbed.maxOfOrNull { renderedInterval(it).last + 1 } ?: (tableRange.last + 1),
        )
        if (uRenderStart != tableRange.first || uRenderEnd != tableRange.last + 1) {
            return TextEditPlan.Rejected("表格边界上压着另一处修改，请先撤掉它再改这张表")
        }
        val uStart = toOriginal(uRenderStart)
        val uEnd = toOriginal(uRenderEnd)
        val sameRows = originalRows.size == combined.size &&
            combined.indices.all { originalRows[it].sameStructure(combined[it]) }
        if (newText == oldText && sameRows && absorbed.isEmpty()) {
            return TextEditPlan.Ready(
                chapter = chapter,
                deletedIds = emptyList(),
                inserted = emptyList(),
                renderedStart = tableRange.first,
                renderedEndExclusive = tableRange.first + newText.length,
                noop = true,
            )
        }
        // 样式：卡片文字行与行之间隔一个 `\n`，正文里各行直接相接，按行的起点平移过去。
        val mappedStyles = if (replacementStyles == null) {
            null
        } else {
            val out = ArrayList<InlineMarkup.StyleSpan>()
            var cursor = 0
            // 未选中的前行在整表文字里占的偏移：卡片样式只落在选中行上，整体后移这一段。
            var rowStart = prefix.length
            for (p in pieces) {
                val lineEnd = cursor + p.text.length
                for (sp in replacementStyles) {
                    val from = maxOf(sp.start, cursor)
                    val to = minOf(sp.endExclusive, lineEnd)
                    if (to > from) {
                        out.add(
                            InlineMarkup.StyleSpan(rowStart + from - cursor, rowStart + to - cursor, sp.sig),
                        )
                    }
                }
                cursor = lineEnd + 1
                rowStart += p.text.length
            }
            out
        }
        // 每行一个 `t…` 短码，首行大写 `T`；格内硬换行（`<br>`）的续行照常记上，
        // 重开卡片时才能把行文字原样拼回去。
        val blocks = ArrayList<String>(allSegments.size + 2)
        allSegments.forEachIndexed { i, p ->
            val row = p.table
            if (row != null) {
                blocks.add(row.wire(wholeTable = i == 0))
            } else if (i == 0) {
                // 删表：只留一个标记，没有行可描述。
                blocks.add("T")
            }
            repeat(p.text.count { it == '\n' }) { blocks.add(RevisionBlocks.CONTINUATION) }
        }
        if (blocks.isEmpty()) blocks.add("T")
        val renderedLen = newText.length
        // `editedStyles` 是**输入区**坐标（行与行之间有 `\n`）：未选中的前行整体后移
        // （文字 + 每行一个换行）。不后移的话，重开这张整表修订时样式会落到别的行上。
        val cardShift = prefix.length + beforeRows.size
        val editedStyles = replacementStyles?.map { sp ->
            InlineMarkup.StyleSpan(sp.start + cardShift, sp.endExclusive + cardShift, sp.sig)
        }
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
                    originalText = oldText,
                    editedText = InlineMarkup.joinSegments(allSegments),
                    editedBlocks = blocks,
                    // 表格结构走自己的通道（`editedBlocks` 的大写 `T`），不混 markdown 块形态。
                    blockKinds = emptyList(),
                    styles = mappedStyles?.let(InlineMarkup::toStored),
                    editedStyles = editedStyles?.let(InlineMarkup::toStored),
                    rowSources = rowSources,
                    delta = renderedLen - (uEnd - uStart),
                    groupId = groupId,
                    createdAt = now,
                    updatedAt = now,
                ),
            ),
            renderedStart = tableRange.first,
            renderedEndExclusive = tableRange.first + renderedLen,
            noop = false,
        )
    }

    /** 把整表里未选中的行按各自文字长度切成卡片分段（一行一段）。 */
    private fun rowSegments(
        chapterText: String,
        from: Int,
        rows: List<TableRowInfo>,
    ): List<InlineMarkup.Segment> {
        val out = ArrayList<InlineMarkup.Segment>(rows.size)
        var at = from
        for (row in rows) {
            val end = at + row.textLength
            out.add(InlineMarkup.Segment(chapterText.substring(at, end), table = row))
            at = end
        }
        return out
    }

    /**
     * 卡片输入区每**行**的块形态（重开卡片时按它把行拼回块）。
     *
     * 一行一块，块内换行（`<br>` / 代码块）的后续行记 `[CONTINUATION]`：重开时按它并回上一块。
     * 表格行格内的`<br>`也一样：行文字里的 `\n` 在卡片里是一行 `<br>`，但落库的
     * `editedText` 里仍是真换行，不记续行会把后面的行短码整体错位。
     */
    private fun editedBlocksOf(pieces: List<InlineMarkup.Segment>): List<String> {
        val out = ArrayList<String>(pieces.size)
        for (p in pieces) {
            val table = p.table?.takeIf { it.covers(p.text) }
            out.add(table?.wire() ?: p.block?.wire ?: "")
            repeat(p.text.count { it == '\n' }) { out.add(RevisionBlocks.CONTINUATION) }
        }
        return out
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
        /** 这一段的新文字（多块时按 `\n` 拼；[literalBreaks] 决定这些 `\n` 怎么算）。 */
        val text: String,
        /** 这一段该长成什么块（null = 不改块级形态）。 */
        val block: BlockKind?,
        /** 这一段是表格行时的格结构（null = 不是表格行）；提交后随 `editedBlocks` 回显。 */
        val table: TableRowInfo?,
        /** true = [text] 里的 `\n` 是书里本来就有的换行（`<br>` / 代码块内换行），不是段界。 */
        val literalBreaks: Boolean,
        /** 该段所在叶（`wsOfLeaf` 的键）与该叶在渲染空间的边界（边界空格按 CSS 丢弃）。 */
        val leaf: Int,
        val leafRenderStart: Int,
        val leafRenderEnd: Int,
        /** 这段新文字的行内样式区间（相对 [text]）；null = 没有样式信息（老路径）。 */
        val styles: List<InlineMarkup.StyleSpan>?,
    ) {
        constructor(
            start: Int,
            endExclusive: Int,
            piece: InlineMarkup.Segment,
            literalBreaks: Boolean,
            leaf: Int,
            leafRenderStart: Int,
            leafRenderEnd: Int,
            styles: List<InlineMarkup.StyleSpan>?,
        ) : this(
            start, endExclusive, piece.text, piece.block, piece.table, literalBreaks,
            leaf, leafRenderStart, leafRenderEnd, styles,
        )
    }

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
        currentKindOf: ((leafIndex: Int) -> BlockKind?)?,
    ): TextEditPlan {
        val deleted = LinkedHashSet<Long>()
        val inserted = ArrayList<TextRevision>(segments.size)
        var renderedLen = 0
        for (seg in segments) {
            val built = planSegment(
                bookId, chapter, chapterText, map, seg, groupId, now, wsOfLeaf, toOriginal, renderedInterval,
                currentKindOf,
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
        currentKindOf: ((leafIndex: Int) -> BlockKind?)?,
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
        val ws = wsOfLeaf(seg.leaf)
        val normalized = LeafTextEditor.preNormalize(rawText, ws, before, after)
        // 样式区间：先按同一套空白规则映射到预归一后的新文字，再随公共前后缀裁剪平移。
        // 段首 / 段尾的邻字必须与 [normalized] 用**同一套**：段起点落在叶中间时，前面那个
        // 真实的字决定段首空白会不会被吃掉；拿 null 当叶边界会把 ` {}内` 段首那个空格误当成
        // 边界吃掉，样式区间整体前移一格（代码段盖住空格与 `{`、`}` 掉出代码）。
        val beforeSeg = if (relStart == 0) before else oldText.getOrNull(relStart - 1)
        val afterSeg = if (relEnd >= oldText.length) after else oldText.getOrNull(relEnd)
        val segSpans = if (seg.styles == null) {
            null
        } else {
            val normSeg = LeafTextEditor.preNormalize(seg.text, ws, beforeSeg, afterSeg)
            remapSpans(seg.styles, seg.text, normSeg)
                .map { InlineMarkup.StyleSpan(relStart + it.start, relStart + it.endExclusive, it.sig) }
        }
        // 读者**新敲**的换行是段界（拆成真块），原文里本来就有的 `\n` 仍是 `<br>`。
        // 卡片按块切好交上来时（[Segment.literalBreaks]）这条已经由切分保证，不再猜；
        // 老调用方只有一整串文字，只能靠字符级对齐分开（方案 D-34 修订版）。
        val newText = if (seg.literalBreaks) normalized else promoteTypedBreaks(oldText, normalized)

        // 目标形态与现在相同 = 没改块形态（卡片每行都带形态，不筛掉的话每块都会白落一条修订）。
        // 这一叶没有块形态（`div` / 表格行）时也不改：它不归 markdown 管。
        val changedBlock = if (seg.block == null) {
            null
        } else if (currentKindOf == null) {
            seg.block
        } else {
            val cur = currentKindOf(seg.leaf)
            if (cur == null) null else seg.block.takeIf { it != cur }
        }
        if (newText == oldText && segSpans.isNullOrEmpty() && changedBlock == null) {
            // 输入的文字与旧渲染文字逐字相同（或只差会被空白归一吃掉的空白）、块形态也没改：不写库。
            return Built(emptyList(), null, RevisionBlocks.renderedLength(newText))
        }

        // 只能裁「没有被旧修订覆盖」的两端：那一段渲染文字与原文逐字相同，裁掉才不会丢旧替换。
        // 没有旧修订时整段都可裁（改一个字只落一个字，方案 §8.4）。
        val firstRevised = absorbed.minOfOrNull { renderedInterval(it).first } ?: uRenderEnd
        val lastRevisedEnd = absorbed.maxOfOrNull { renderedInterval(it).last + 1 } ?: uRenderStart
        // 文字一个字都没改、只是改了行内结构或块形态：整段重写（两头一裁，样式就只剩个空壳）。
        val restructureOnly = newText == oldText
        val maxPrefix = if (restructureOnly) {
            0
        } else {
            (firstRevised - uRenderStart).coerceIn(0, minOf(oldText.length, newText.length))
        }
        var prefix = 0
        while (prefix < maxPrefix && oldText[prefix] == newText[prefix]) prefix++
        val maxSuffix = if (restructureOnly) {
            0
        } else {
            (uRenderEnd - lastRevisedEnd)
                .coerceIn(0, minOf(oldText.length - prefix, newText.length - prefix))
        }
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
        val finalStyles = segSpans?.mapNotNull { sp ->
            val s0 = (sp.start - prefix).coerceAtLeast(0)
            val e0 = (sp.endExclusive - prefix).coerceAtMost(finalText.length)
            if (e0 > s0) InlineMarkup.StyleSpan(s0, e0, sp.sig) else null
        }
        // 审阅清单那一行摘要的两端：整段选区改之前 / 改之后的文字（渲染空间，按叶各存一份）。
        // 与上面的「最小修订」无关：公共前后缀照裁不误，但清单里仍按整段比对，
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
                // 卡片那一行（含块内换行的续行）的形态：重开卡片时按它把行拼回块。
                editedBlocks = if (seg.block != null || seg.table != null) {
                    editedBlocksOf(listOf(InlineMarkup.Segment(seg.text, seg.block, seg.table)))
                } else {
                    emptyList()
                },
                // 块级形态随修订落库：重放时按它换叶子标签 / 增删包裹层。
                blockKinds = if (changedBlock != null) {
                    listOf(changedBlock.wire) + List(RevisionBlocks.split(finalText).size - 1) { "" }
                } else {
                    emptyList()
                },
                styles = finalStyles?.let(InlineMarkup::toStored),
                // 卡片再打开时要看到**整段输入文字**的结构，不能只有裁剩的最小改动那截。
                // 坐标必须按**输入区原文**（[seg].text）记，不能拿空白归一之后的那版：两者在段首 /
                // 连续空白处可能差字，重开卡片会把标记整体挪一格。跨段那条（[planMerge]）一直
                // 就是这么记的，这里跟上。
                editedStyles = seg.styles?.let(InlineMarkup::toStored),
                delta = RevisionBlocks.renderedLength(finalText) - (finalEnd - finalStart),
                groupId = groupId,
                createdAt = now,
                updatedAt = now,
            ),
            renderedLength = RevisionBlocks.renderedLength(newText),
        )
    }

    /**
     * 把读者**新敲**的换行升成段界（[RevisionBlocks.SEPARATOR]）。
     *
     * 输入框里两种换行长得一样：原书本来就有的 `<br>`（字符流里就是 `\n`）与读者刚按下的回车。
     * 用字符级对齐分开：能对上前一个字面量（[CharDiff.Op.Keep]）的 `\n` 是留下来的 `<br>`，
     * 对不上的就是新添的换行 ⇒ 段界，重放时按它拆出真正的块（`</p><p>` 或插入点所在的那个标签）。
     *
     * 对齐退化（整段换新字）时会把选区里的 `\n` 全当新换行，代价是原有的 `<br>` 变成段界；
     * 只影响超长段落（[CharDiff] 的长度上限）里的情形。
     */
    internal fun promoteTypedBreaks(oldText: String, newText: String): String {
        if (newText.indexOf('\n') < 0) return newText
        val kept = BooleanArray(newText.length)
        var ni = 0
        for (op in CharDiff.diff(oldText, newText)) {
            when (op) {
                is CharDiff.Op.Keep -> {
                    repeat(op.length) {
                        if (ni < kept.size) kept[ni] = true
                        ni++
                    }
                }
                is CharDiff.Op.Delete -> Unit
                is CharDiff.Op.Insert -> ni += op.text.length
            }
        }
        val out = StringBuilder(newText.length)
        for (i in newText.indices) {
            val c = newText[i]
            out.append(if (c == '\n' && !kept[i]) RevisionBlocks.SEPARATOR else c)
        }
        return out.toString()
    }

    /**
     * 选区两端若紧贴**接缝上被吃成零宽的分隔空白**，判断替换后它会不会「现形」；会的话把它吃进替换区间。
     *
     * 书里 `使用 <code>val</code> 的只读变量` 在 `val` 与 `的` 之间有一个分隔空格，排版时按中西接缝
     * 吃成零宽。读者把 `使用 val` 改成 `用 val 声明` 后，这个空格夹在 `明` 与 `的` 之间，
     * 不再命中接缝，于是渲染成一个肉眼可见的空格。它本来就在原文里，不是多打的字，
     * 但读者从来看不见它，编辑也不该让它凭空出现 —— 所以这里把它并入替换区间（等价于删掉）。
     *
     * 判据不另写一份：先把这次替换落成结果文字，再拿 [CjkLatinSpacing.isEatenSpaceSlot] 问那串空白
     * **在结果里**还会不会被吃掉。不猜左右邻字是必要的：纯删除时两端空白会并成一串，左右邻字要跨过
     * 整段选区去取（曾经按邻字猜，把「删除后仍被吃掉」的空格也一并删了）。
     *
     * 只做两件收窄：
     * - 输入文字与选区文字完全相同（原样确认 / 纯改样式）时不动，免得凭空多一条「删空格」修订；
     * - 结果里那串空白仍被吃掉时不动（如 `val` 改成 `values`，仍是西文贴中文），一个字节都不改。
     *
     * @return 调整后的 `[start, endExclusive)`；没有可吃 / 不该吃时原样返回。
     */
    private fun absorbVisibleSeamSpaces(
        text: String,
        start: Int,
        endExclusive: Int,
        replacement: String,
    ): Pair<Int, Int> {
        if (start >= endExclusive) return start to endExclusive
        // 跨段替换的边界是块界，不在这里处理；单行替换才是本规则的对象。
        if (replacement.indexOf('\n') >= 0) return start to endExclusive
        if (replacement == text.substring(start, endExclusive)) return start to endExclusive
        // 两端各看**当前就被吃掉**的那一串空白；本来就看得见的不归本规则管。
        var headStart = -1
        if (start > 0 && CjkLatinSpacing.isEatenSpaceSlot(text, start - 1)) {
            headStart = start
            while (headStart > 0 && CjkLatinSpacing.isEatenSpaceSlot(text, headStart - 1)) headStart--
        }
        var tailEnd = -1
        if (endExclusive < text.length && CjkLatinSpacing.isEatenSpaceSlot(text, endExclusive)) {
            tailEnd = endExclusive
            while (tailEnd < text.length && CjkLatinSpacing.isEatenSpaceSlot(text, tailEnd)) tailEnd++
        }
        if (headStart < 0 && tailEnd < 0) return start to endExclusive
        val result = buildString(text.length - (endExclusive - start) + replacement.length) {
            append(text, 0, start)
            append(replacement)
            append(text, endExclusive, text.length)
        }
        var s = start
        var e = endExclusive
        // 结果里这两串空白的位置：尾端隔着替换文字，首端不动（替换从 start 起写）。
        if (tailEnd >= 0 && !CjkLatinSpacing.isEatenSpaceSlot(result, start + replacement.length)) e = tailEnd
        if (headStart >= 0 && !CjkLatinSpacing.isEatenSpaceSlot(result, headStart)) s = headStart
        return s to e
    }

    /** 累计块尾 → 偏移所在的叶下标（第一个「块尾 > offset」）。 */
    private fun leafOf(ends: IntArray, offset: Int): Int {
        var i = 0
        while (i < ends.size && ends[i] <= offset) i++
        return i.coerceIn(0, ends.size - 1)
    }

    /**
     * 每行的样式区间（相对该行文字）。[spans] 为 null（没有样式信息）时整表返回 null，
     * 让下游走「老路径」（新字挂左邻），老修订 / 纯文本编辑的行为一个字节都不变。
     */
    private fun lineSpans(
        spans: List<InlineMarkup.StyleSpan>?,
        lines: List<String>,
    ): List<List<InlineMarkup.StyleSpan>>? {
        if (spans == null) return null
        val out = ArrayList<List<InlineMarkup.StyleSpan>>(lines.size)
        var cursor = 0
        for (line in lines) {
            val from = cursor
            val to = from + line.length
            out.add(
                spans.filter { it.start < to && it.endExclusive > from }.map {
                    InlineMarkup.StyleSpan(
                        maxOf(0, it.start - from),
                        minOf(line.length, it.endExclusive - from),
                        it.sig,
                    )
                },
            )
            cursor = to + 1
        }
        return out
    }

    /** 把区间从 [from] 文字映射到 [to] 文字（对齐保留的字跟着走；被空白归一吃掉的字丢掉）。 */
    private fun remapSpans(
        spans: List<InlineMarkup.StyleSpan>,
        from: String,
        to: String,
    ): List<InlineMarkup.StyleSpan> {
        if (from == to) return spans
        val map = IntArray(from.length) { -1 }
        var oi = 0
        var ni = 0
        for (op in CharDiff.diff(from, to)) {
            when (op) {
                is CharDiff.Op.Keep -> {
                    for (k in 0 until op.length) {
                        if (oi < map.size) map[oi] = ni
                        oi++
                        ni++
                    }
                }
                is CharDiff.Op.Delete -> oi += op.length
                is CharDiff.Op.Insert -> ni += op.text.length
            }
        }
        return spans.mapNotNull { sp ->
            var ns = -1
            var ne = -1
            for (k in sp.start.coerceAtLeast(0) until sp.endExclusive.coerceAtMost(map.size)) {
                val m = map[k]
                if (m < 0) continue
                if (ns < 0) ns = m
                ne = m + 1
            }
            if (ns < 0 || ne <= ns) null else InlineMarkup.StyleSpan(ns, ne, sp.sig)
        }
    }

    /** 保留空行的 `\n` 切分（与卡片同一套，见 [InlineMarkup.splitLines]）。 */
    internal fun splitLines(text: String): List<String> = InlineMarkup.splitLines(text)
}
