package orilumn.reader.engine.revision

import orilumn.reader.engine.css.ComputedStyle
import orilumn.reader.engine.css.WhiteSpace
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.html.MarkupPaths
import orilumn.reader.engine.laying.StyledSegments

/**
 * 载入重放：把一章的修订套到章树**副本**上（方案 §8.5）。
 *
 * 只在内存里做，**绝不写回磁盘**：磁盘上的原树与结构保持原样，否则下次开书会在已改的树上
 * 再套一遍修订（双重修改，方案 §8.10 第二风险）。
 *
 * 叶级重建由 [LeafTextEditor] 完成；本类负责「章级」的四件事：
 * 1. 按叶顺序重放，并修正后面所有叶的字符起点（前叶长度变了，后叶整体平移）；
 * 2. 叶是树里的真实节点时按路径换回父节点（路径不变，只换节点）；
 * 3. 匿名 run 叶（结构里的合成文本，不在树里）被改到时，由调用方回调把它对应的真实子节点
 *    span 重建后换回属主（[AnonymousRun]）；回调给不出结果才退回「放弃结构、重算」信号；
 * 4. **跨叶修订**（段数变了）：按修订文字里的段界把中间块合并掉 / 把一段拆成几段，
 *    叶表随之增减（见 [Merge]）。
 *
 * 属于排版层（上）：纯逻辑，样式由调用方以回调注入，可 JVM 单测。
 */
object ChapterRevisionReplay {

    /**
     * 一条跨叶修订（段数变了，方案 D-34 修订版）。
     *
     * 结果段数 = [text] 里的段界（[RevisionBlocks.SEPARATOR]）个数 + 1，与选区跨了几叶无关：
     * - 首块 = 首叶前缀 + 第一行（只有一段时还要接末叶后缀）；
     * - 中间各行各占一块（能对上叶的用原叶，对不上就新建同 tag 的块）；
     * - 末块 = 最后一行 + 末叶后缀（用末叶承载，保住后缀的行内样式）；
     * - 没被用到的叶整块删掉。
     *
     * @property start / [endExclusive] 选区在本段虚拟叶里的区间（原书偏移）。单独一条时相对
     *   `charStarts[firstLeaf]`；同一片叶 / 同一组上的几处改动要**共用组的首叶**（`firstLeaf`
     *   都填组的最小叶号），起点统一相对 `charStarts[组首叶]`——重放按 [firstLeaf] 归组，
     *   各报各的首叶会让后面那条落进另一个组、被整组覆盖。
     */
    data class Merge(
        val firstLeaf: Int,
        val lastLeaf: Int,
        val start: Int,
        val endExclusive: Int,
        val text: String,
        val revisionId: Long,
        /**
         * 显式行内样式区间（偏移相对 [text]，含段界字符）。null = 没有样式信息。
         * 块与块之间按段界切开后各自保留自己那一段。
         */
        val styles: List<InlineMarkup.StyleSpan>? = null,
        /**
         * 每块的**目标块形态**（对齐 [text] 按段界切开后的每一块；null = 这一块不改）。
         *
         * 与 [styles] 同一把尺：段界数 + 1 个元素。数量对不上时按「没有块信息」处理。
         */
        val kinds: List<BlockKind?>? = null,
    )

    /**
     * 一条**整表结构修订**（读者改了这张表的行 / 列 / 合并方式，见 `TextEditPlanner.planTable`）。
     *
     * 与 [Merge] 的区别：它不按段界拆块，而是按 [rows] 的格结构重建一整张 `table`：
     * - `rows` 为空 = 删掉整张表；
     * - 否则按 `text` 依次切出每一行的文字（行界由 [rows] 的 `textLength` 累加给出），
     *   在原位建新的 `tr` / `td` / `th`，旧的整行叶全部撤掉；
     * - 旧格的 `class` / `style` 等属性按位置尽量沿用（`id` 去掉），合并属性按新结构写。
     *
     * @property firstLeaf / [lastLeaf] 被替换的旧行叶（连续的叶号，首末都含）；
     * @property start / [endExclusive] 修订区间，相对 `charStarts[firstLeaf]`；
     * @property text 各行文字**直接相接**（没有段界字符）；
     * @property rows 新表的格结构（`textLength` 之和必须等于 `text.length`）。
     */
    data class TableEdit(
        val firstLeaf: Int,
        val lastLeaf: Int,
        val start: Int,
        val endExclusive: Int,
        val text: String,
        val rows: List<TableRowInfo>,
        val revisionId: Long,
        /** 行内样式区间（偏移相对 [text]）。null = 没有样式信息。 */
        val styles: List<InlineMarkup.StyleSpan>? = null,
        /**
         * 每行来自旧表哪一行（与 [rows] 按序对齐；`-1` = 读者新加的行）。
         * null = 老修订没存过，重放按行文字 / 格结构自己对齐。
         */
        val rowSources: List<Int>? = null,
        /**
         * 审阅视图的行级对照（见 [TableReviewPlan]）；null = 正常视图，按结果整表重建。
         *
         * 给了它时，删掉的行留在原处、新加的行插在原位、改过的格就地插入新字——重放出来的
         * 行序与文字必须与 [TableReviewPlan.overlay] 逐字一致，修订表才能按同一把尺换算。
         */
        val review: TableReviewPlan? = null,
    )

    /**
     * 一次匿名 run 的叶内重建（方案 §8.5 第 3 步的落地）：匿名 run 叶是结构里合成的
     * `#text`（不在章树 children 里），改字时由调用方把它对应的**真实**子节点 span
     * `[start, endExclusive)` 换成一串重建后的行内节点 [nodes]，并给出新的 run 原文与实测长度。
     *
     * @property owner run 的属主容器（匿名叶的 `parent`）。
     * @property rawText 重建后的原文（`<br>`→'\n'、`<img>`→U+FFFC，未归一）；落成合成叶的 `text`。
     * @property textLength 重建后 run 在字符流里的格数（渲染空间，已按容器 white-space 归一）。
     * @property oldTextLength 重建前 run 在字符流里的格数。
     */
    class AnonymousRun(
        val owner: MarkupElement,
        val start: Int,
        val endExclusive: Int,
        val nodes: List<MarkupElement>,
        val rawText: String,
        val textLength: Int,
        val oldTextLength: Int,
    )

    /**
     * 匿名 run 叶的重建回调：[ordinal] 是该属主下第几个匿名叶（文档序，0 起）。
     * 返回 null = 这次不猜（保留原叶，[Result.needsRecompute] 置真）。
     */
    typealias AnonymousRunEditor = (MarkupElement, Int, List<LeafTextEditor.Edit>) -> AnonymousRun?

    /**
     * 表格行叶的一次重建结果。
     *
     * @property leaf 重建后的 `tr`（各格原样保留、格内文字按修订改过）；
     * @property textLength 重建后行文本的格数（各格文本之和，与字符流同尺）；
     * @property degraded 实测与应有文本不等（结构复杂 / 修订跨格；调用方记日志）。
     */
    class RowEdit(
        val leaf: MarkupElement,
        val textLength: Int,
        val degraded: Boolean = false,
    )

    /**
     * @property root 重放后的章树（未改动的子树原样复制；parent 已重链）；
     * @property leaves 重放后的叶表（文档序；匿名 run 叶按重建后的原文换新，删空则移除）；
     * @property charStarts 修正后的叶起点（**渲染空间**，可直接给排版用）；
     * @property degradedLeaves 实测与应有文本不等的叶数（空白边界；调用方记日志）；
     * @property measuredLeafDeltas 被改到的叶「重放后长度 − 重放前长度」的实测值（叶下标 → 增量）。
     *   库里的修订 `delta` 是提交时算的，节点边界空白折叠可能让两者不等；调用方拿它做换算修正，
     *   保证内存坐标与排版实测一致（方案 §8.10 最大风险）。
     * @property needsRecompute 匿名 run 叶重建失败 / 不能合并的块 ⇒ 调用方放弃持久化结构重算。
     * @property leafOrigin 新叶下标 → 继承装饰（背景 / break-inside / 生成内容）用的旧叶下标；
     * @property measuredRevisionDeltas 跨叶修订的实测增量（修订 id → 增量）；
     * @property elementMap 旧树节点 → 新树节点（被删掉 / 被换掉的叶内部节点不在表里）。
     */
    data class Result(
        val root: MarkupElement,
        val leaves: List<MarkupElement>,
        val charStarts: LongArray,
        val degradedLeaves: Int,
        val measuredLeafDeltas: Map<Int, Int>,
        val needsRecompute: Boolean,
        val leafOrigin: IntArray,
        val measuredRevisionDeltas: Map<Long, Int>,
        val elementMap: Map<MarkupElement, MarkupElement>,
    )

    /**
     * @param leaves 叶表（与 [charStarts] 同序）；
     * @param leafPaths 每个叶在章树里的路径；匿名 run 叶为 null（不在 children 里）；
     * @param charStarts 每个叶在**原书空间**的起点；
     * @param editsForLeaf 返回该叶的叶内修订（叶内原书偏移）。空表 = 这一叶没被改到；
     * @param anonymousEdit 匿名 run 叶被改到时的重建回调（null = 保持旧行为，放弃结构重算）；
     * @param merges 跨叶修订（段数变了）；与 [editsForLeaf] 不重叠；
     * @param tableEdits 整表结构修订；与 [editsForLeaf] 不重叠；
     * @param segmentsOf 取叶的 [StyledSegments]（必须与排版同一组 wsOf/styleOf/genOf 输入）。
     */
    fun replay(
        root: MarkupElement,
        leaves: List<MarkupElement>,
        leafPaths: List<IntArray?>,
        charStarts: LongArray,
        editsForLeaf: (leafIndex: Int, leafStart: Int) -> List<LeafTextEditor.Edit>,
        segmentsOf: (MarkupElement) -> StyledSegments,
        wsOf: (MarkupElement) -> WhiteSpace,
        styleOf: ((MarkupElement) -> ComputedStyle?)?,
        merges: List<Merge> = emptyList(),
        tableEdits: List<TableEdit> = emptyList(),
        anonymousEdit: AnonymousRunEditor? = null,
        /**
         * 表格行叶被改到时的重建回调（null = 保持旧行为，放弃结构重算）。
         *
         * 表格行的文字是「各格文字按序拼接」，格是结构边界：通用的叶内重建会把新字插到
         * `td` 外面。宿主按格拆开重建（见 `BoxChapterLayouter`），这里只认结果与长度。
         *
         * @param target 卡片提交时落库的**目标格结构**（含新文字的各格长度；null = 拿不到，
         *   按旧格界推断）。重放据此把新文字落回它该在的格子，边界插入才不会被塞进前一格。
         */
        rowEdit: ((MarkupElement, List<LeafTextEditor.Edit>, TableRowInfo?) -> RowEdit?)? = null,
        /** 取某一行叶的目标格结构（[rowEdit] 的第三个参数）。 */
        tableOfRow: (MarkupElement) -> TableRowInfo? = { null },
        /**
         * 叶的目标块形态（[BlockKind]；null = 这一叶不改块级形态）。只在与当前形态不同时才动结构，
         * 见 [BlockRestructure]。
         */
        blockOfLeaf: (leafIndex: Int) -> BlockKind? = { null },
    ): Result {
        require(leaves.size == charStarts.size && leafPaths.size == charStarts.size) {
            "叶表 / 路径 / 起点长度不一致：${leaves.size} / ${leafPaths.size} / ${charStarts.size}"
        }
        // 级联取样式要沿 parent 走；持久化树可能还没重链过（幂等）。
        MarkupPaths.assignParents(root)
        // 同一片叶上可能落着几处改动（一段里插了两次段界、或者又改了字）：按首叶归组，
        // 由 [mergeLeaves] 合成一次拆块，别让后一条把前一条顶掉。
        val mergeByFirst = HashMap<Int, MutableList<Merge>>(merges.size)
        for (m in merges) mergeByFirst.getOrPut(m.firstLeaf) { ArrayList(1) }.add(m)
        val tableByFirst = HashMap<Int, TableEdit>(tableEdits.size)
        for (t in tableEdits) tableByFirst[t.firstLeaf] = t
        val replacements = HashMap<MarkupElement, List<MarkupElement>>()
        val removed = HashSet<MarkupElement>()
        // 匿名 run 的源节点 span 要等所有叶跑完再一次性换（同一属主可能有多个 run，
        // 各 run 的下标都相对**原** children，边跑边换会错位）。
        val childOverrides = HashMap<MarkupElement, List<MarkupElement>>()
        val anonymousRuns = LinkedHashMap<MarkupElement, MutableList<AnonymousRun>>()
        val anonymousLeafOwner = HashMap<MarkupElement, MarkupElement>()
        // 每个匿名叶在「它所属主的 run 序列」里的序号（叶表按文档序；未改到的 run 也占号）。
        // 先整体数一遍：跨叶合并组会整段跳过主循环，边跑边数会漏掉组里的匿名叶。
        val anonOrdinalOf = IntArray(leaves.size) { -1 }
        run {
            val counters = HashMap<MarkupElement, Int>()
            for (k in leaves.indices) {
                val l = leaves[k]
                if (leafPaths[k] == null && l.tag == "#text") {
                    val owner = l.parent ?: continue
                    val n = counters.getOrDefault(owner, 0)
                    counters[owner] = n + 1
                    anonOrdinalOf[k] = n
                }
            }
        }
        val newLeaves = ArrayList<MarkupElement>(leaves.size)
        val newStarts = ArrayList<Long>(leaves.size)
        val origins = ArrayList<Int>(leaves.size)
        val measured = LinkedHashMap<Int, Int>()
        val measuredRev = LinkedHashMap<Long, Int>()
        // 需要改块级形态的叶（新叶 → 目标形态）：只收「与现在不同」的，相同的块一个节点都不动。
        val wantBlocks = LinkedHashMap<MarkupElement, BlockKind>()
        // 还没落到 wantBlocks 的目标形态：叶可能在主循环里被换掉 / 被合并组撤掉，按节点跟过去。
        // 只在主循环里现算是不行的——审阅视图里「只改块形态、文字一字未动」的修订不产生任何
        // 文字修订，主循环整片叶直接跳过（`edits.isEmpty()`），现算就漏了。
        val pendingKinds = LinkedHashMap<MarkupElement, BlockKind>()
        for (index in leaves.indices) {
            val leaf = leaves[index]
            val kind = blockOfLeaf(index) ?: continue
            wantKindOf(leaf, kind)?.let { pendingKinds[leaf] = it }
        }
        var degraded = 0
        var needsRecompute = false
        var shift = 0L

        var i = 0
        while (i < leaves.size) {
            val tableEdit = tableByFirst[i]
            if (tableEdit != null) {
                val lastIndex = tableEdit.lastLeaf.coerceAtMost(leaves.size - 1)
                val oldRows = (i..lastIndex).map { leaves[it] }
                val oldLength = (i..lastIndex).sumOf { segmentsOf(leaves[it]).text.length }
                val table = oldRows.firstOrNull()?.let { ancestorTable(it) }
                val rebuilt = if (table == null) {
                    null
                } else {
                    buildTableRows(tableEdit, oldRows, table, segmentsOf, wsOf, styleOf)
                }
                if (table == null || rebuilt == null) {
                    // 结构对不上（不是同一张表的连续行 / 行码与文字不等）：不猜，原样保留。
                    needsRecompute = true
                    for (k in i..lastIndex) {
                        origins += k
                        newLeaves += leaves[k]
                        newStarts += charStarts[k] + shift
                    }
                    i = lastIndex + 1
                    continue
                }
                // 树：没改的行原样留在原位；改过的行原地换掉，删掉的行撤掉，新加的行挂到相邻行。
                if (rebuilt.rows.isEmpty()) {
                    removed += table
                } else {
                    applyTableRowEdits(replacements, removed, oldRows, rebuilt)
                }
                var rowOffset = 0L
                for (k in rebuilt.rows.indices) {
                    newStarts += charStarts[i] + shift + rowOffset
                    newLeaves += rebuilt.rows[k]
                    val src = rebuilt.sources[k]
                    // 装饰（背景 / break-inside / 生成内容）按**这一行自己**的来源叶继承，
                    // 不能一律指向表首行。
                    origins += if (src in oldRows.indices) i + src else i
                    rowOffset += rebuilt.lengths[k]
                }
                if (rebuilt.lengths.sum() != tableEdit.text.length) degraded++
                val delta = rebuilt.lengths.sum() - oldLength
                measured[i] = delta
                shift += delta
                measuredRev[tableEdit.revisionId] = delta
                i = lastIndex + 1
                continue
            }
            val group = mergeByFirst[i]
            if (group != null) {
                val out = mergeLeaves(group, leaves, leafPaths, charStarts, segmentsOf, wsOf, styleOf)
                if (out.needsRecompute) needsRecompute = true
                degraded += out.degraded
                val firstLeaf = group.minOf { it.firstLeaf }
                val lastLeaf = group.maxOf { it.lastLeaf }
                if (out.blocks.isEmpty()) {
                    // 不能合并（匿名叶 / 图片 / 表格）：整段原样保留，交给调用方放弃结构重算。
                    for (k in firstLeaf..lastLeaf) {
                        origins += k
                        newLeaves += leaves[k]
                        newStarts += charStarts[k] + shift
                    }
                } else {
                    // 块内偏移累积在 blocks 自己的区间里；`shift` 只记**增量**（新长度 − 旧长度），
                    // 不能把合并后这一整段的长度也加进去：那会让合并段之后的所有叶各多算一整段，
                    // 整章字符流与正文投影从此错位（实测 53 字的并段把后面每个偏移都推后 53）。
                    var blockOffset = 0L
                    for (b in out.blocks) {
                        newStarts += charStarts[firstLeaf] + shift + blockOffset
                        newLeaves += b.element
                        origins += firstLeaf
                        blockOffset += b.textLength
                        b.kind?.let { wantBlocks[b.element] = it }
                    }
                    shift += out.delta
                    // 组里的叶整段换成新块：第一片叶原地换成一串块，其余叶撤掉
                    // （它们的内容已经由那串块承载；留着就是原文重复一遍）。
                    //
                    // 叶各自住在一层纯包装里（`ul > li > p`：列表项的文字挂在 `li` 下的 `p`）时，
                    // 换的必须是**包装**：只换第一片叶的话，几块会全塞进第一个列表项，剩下的
                    // 列表项被撤掉内容变成空壳（读者复报：补一项列表，其余项全成了普通段落）。
                    val units = mergeUnits(leaves, firstLeaf, lastLeaf)
                    if (units == null) {
                        replacements[leaves[firstLeaf]] = out.blocks.map { it.element }
                        for (k in firstLeaf + 1..lastLeaf) removed += leaves[k]
                    } else {
                        replacements[units[0]] = out.blocks.mapIndexed { j, b ->
                            // 复用的包装沿用自己的属性（class / id 都不会撞）；多出来的块
                            // （读者新加的行）照第一个包装的样子造，去掉 id。
                            val host = units.getOrNull(j) ?: units[0]
                            MarkupElement(
                                tag = host.tag,
                                attrs = if (j < units.size) host.attrs else host.attrs - "id",
                                children = listOf(b.element),
                            )
                        }
                        for (k in 1 until units.size) removed += units[k]
                    }
                    // 合成的组（同一片叶上几处改动）量不出逐条的增量，跳过修正、沿用库里的值。
                    if (group.size == 1) measuredRev[group[0].revisionId] = out.delta
                }
                i = lastLeaf + 1
                continue
            }

            val leaf = leaves[i]
            val originalStart = charStarts[i]
            newStarts += originalStart + shift
            val path = leafPaths[i]
            // 匿名 run 叶的序号（预先按属主数好；-1 = 不是匿名叶）。
            val anonOrdinal = anonOrdinalOf[i]
            val edits = editsForLeaf(i, originalStart.toInt())
            if (edits.isEmpty()) {
                origins += i
                newLeaves += leaf
                i++
                continue
            }
            if (path == null) {
                val run = if (anonOrdinal >= 0) anonymousEdit?.invoke(leaf, anonOrdinal, edits) else null
                if (run != null) {
                    anonymousRuns.getOrPut(run.owner) { ArrayList(1) }.add(run)
                    val delta = run.textLength - run.oldTextLength
                    measured[i] = delta
                    shift += delta
                    if (run.textLength > 0) {
                        val newLeaf = MarkupElement("#text", text = run.rawText)
                        anonymousLeafOwner[newLeaf] = run.owner
                        origins += i
                        newLeaves += newLeaf
                    } else {
                        // run 被删空：这一叶不再存在，刚计入的起点也要收回。
                        newStarts.removeAt(newStarts.size - 1)
                    }
                    i++
                    continue
                }
                // 匿名 run：重建回调没给结果（结构复杂 / 文本对不上）。保持旧行为：原样返回，
                // 叫调用方放弃结构重算。
                needsRecompute = true
                origins += i
                newLeaves += leaf
                i++
                continue
            }
            val before = segmentsOf(leaf)
            // 整块图片：字符流里只有一个 U+FFFC 占位。占位被删 = 整张图删掉（叶不再存在）；
            // 占位留着 = 图原样保留。换成普通文字在提交侧已被拒绝，这里不猜。
            if (leaf.tag == "img") {
                val after = LeafTextEditor.applyEdits(before.text, edits)
                if (after.isEmpty()) {
                    // 整张图删掉：叶从树里去掉，刚计入的起点收回。
                    removed += leaf
                    newStarts.removeAt(newStarts.size - 1)
                    measured[i] = -before.text.length
                    shift -= before.text.length
                } else {
                    origins += i
                    newLeaves += leaf
                }
                i++
                continue
            }
            // 表格行：格是结构边界，按格重建（宿主回调）。回调给不出结果就保持原样、
            // 叫调用方放弃结构重算——绝不把新字写到 `td` 外面。
            if (leaf.tag == "tr" && rowEdit != null) {
                val rr = rowEdit(leaf, edits, tableOfRow(leaf))
                if (rr == null) {
                    needsRecompute = true
                    origins += i
                    newLeaves += leaf
                    i++
                    continue
                }
                if (rr.degraded) degraded++
                val leafDelta = rr.textLength - before.text.length
                measured[i] = leafDelta
                shift += leafDelta
                replacements[leaf] = listOf(rr.leaf)
                origins += i
                newLeaves += rr.leaf
                pendingKinds.remove(leaf)?.let { wantBlocks[rr.leaf] = it }
                i++
                continue
            }
            val r = LeafTextEditor.rebuild(
                leaf = leaf,
                segments = before,
                edits = edits,
                wsOf = wsOf,
                styleOf = styleOf,
                rebuildSegments = segmentsOf,
            )
            if (r.degraded) degraded++
            val leafDelta = r.text.length - before.text.length
            measured[i] = leafDelta
            shift += leafDelta
            replacements[leaf] = listOf(r.leaf)
            origins += i
            newLeaves += r.leaf
            // 块级形态：这一叶换成了新节点，形态跟着新节点走。
            pendingKinds.remove(leaf)?.let { wantBlocks[r.leaf] = it }
            i++
        }

        if (anonymousRuns.isNotEmpty()) {
            for ((owner, runs) in anonymousRuns) {
                val kids = owner.children.toMutableList()
                // 同一属主的多个 run：下标都相对原 children，按起点倒序换，前面的下标不受影响。
                for (run in runs.sortedByDescending { it.start }) {
                    require(run.start >= 0 && run.start <= run.endExclusive && run.endExclusive <= kids.size) {
                        "匿名 run 区间越界：[${run.start}, ${run.endExclusive})（children=${kids.size}）"
                    }
                    kids.subList(run.start, run.endExclusive).clear()
                    kids.addAll(run.start, run.nodes)
                }
                childOverrides[owner] = kids
            }
        }

        // 没被替换、也没被合并组撤掉的叶：目标形态直接挂原节点（见 pendingKinds 的说明）。
        for ((leaf, kind) in pendingKinds) {
            if (leaf in replacements || leaf in removed) continue
            wantBlocks[leaf] = kind
        }
        val rebuilt = rebuildTree(root, replacements, removed, childOverrides)
        var tree = rebuilt.root
        MarkupPaths.assignParents(tree)
        // 块级重塑（标题 / 引用 / 列表 / 代码块）：树形状会变，必须在重链之后做——它按父链认容器。
        var nodeMap: Map<MarkupElement, MarkupElement> = emptyMap()
        if (wantBlocks.isNotEmpty()) {
            val restructured = BlockRestructure.apply(tree, wantBlocks)
            nodeMap = restructured.nodeMap
            if (restructured.changed) {
                tree = restructured.root
                MarkupPaths.assignParents(tree)
                for (n in newLeaves.indices) newLeaves[n] = nodeMap[newLeaves[n]] ?: newLeaves[n]
            }
        }
        // 合成匿名叶不在 children 里，assignParents 够不到：按重建后的属主补 parent；
        // 属主若在重塑里被换掉（容器拆分 / 重新成组），跟着换到新节点。
        for ((leaf, owner) in anonymousLeafOwner) {
            val mapped = rebuilt.map[owner] ?: owner
            leaf.parent = nodeMap[mapped] ?: mapped
        }
        // 旧树 → 新树：重塑换掉 / 复制过的节点按重塑表再换一道。
        val elementMap = if (nodeMap.isEmpty()) {
            rebuilt.map
        } else {
            rebuilt.map.mapValues { nodeMap[it.value] ?: it.value }
        }
        return Result(
            root = tree,
            leaves = newLeaves,
            charStarts = newStarts.toLongArray(),
            degradedLeaves = degraded,
            measuredLeafDeltas = measured,
            needsRecompute = needsRecompute,
            leafOrigin = origins.toIntArray(),
            measuredRevisionDeltas = measuredRev,
            elementMap = elementMap,
        )
    }

    /** 一块的节点、实测文本长度（渲染空间）与目标块形态（null = 不改块级形态）。 */
    private class Block(
        val element: MarkupElement,
        val textLength: Int,
        val kind: BlockKind? = null,
    )

    /**
     * 把同一组（同一片叶，或首末叶之间）的几处改动拼成一串文字：按起点把各自的替换文字
     * 替换进去（区间互不重叠），段界字符原样保留，交给 [RevisionBlocks.split] 一次拆块。
     */
    private fun composeLeaf(base: String, group: List<Merge>): String {
        val out = StringBuilder(base.length + 16)
        var pos = 0
        for (m in group.sortedBy { it.start }) {
            val s = m.start.coerceIn(0, base.length)
            val e = m.endExclusive.coerceIn(s, base.length)
            if (s > pos) out.append(base, pos, s)
            out.append(m.text)
            pos = e
        }
        if (pos < base.length) out.append(base, pos, base.length)
        return out.toString()
    }

    private class MergeOut(
        val blocks: List<Block>,
        val delta: Int,
        val degraded: Int,
        val needsRecompute: Boolean,
    )

    /**
     * 一块的目标形态（null = 不动结构）。
     *
     * 两个不动的口径：目标与现在相同（块一个节点都不换），或者这一块**不归 markdown 管**
     * （`div` / 表格行这类叶没有对应的块标记，卡片也就不该改它的形态——哪怕提交里带了什么）。
     */
    private fun wantKindOf(leaf: MarkupElement, want: BlockKind?): BlockKind? {
        if (want == null) return null
        val cur = BlockKind.ofElement(leaf) ?: return null
        return want.takeIf { it != cur }
    }

    /** 离 [el] 最近的 `table` 祖先（`tr` 所在的表）；没有返回 null。 */
    private fun ancestorTable(el: MarkupElement): MarkupElement? =
        generateSequence(el.parent) { it.parent }.firstOrNull { it.tag == "table" }

    /** [buildTableRows] 的结果：新行节点 + 每行重放后的实测长度（与字符流同尺）+ 每行来自旧表哪一行。 */
    private class RebuiltTable(
        val rows: List<MarkupElement>,
        val lengths: IntArray,
        /** 与 [rows] 按序对齐：0 起旧行下标；`-1` = 读者新加的行。 */
        val sources: IntArray,
    )

    /**
     * 按 [TableEdit] 的格结构重建整张表的行节点。
     *
     * **没被改到的行原样留下**（同一个 `tr` 节点）：行 / 格属性、格内行内元素、格内块结构、
     * `thead` / `tbody` 分组一个都不动 —— 删掉一行不会连累其余行的样式。改过的行只重建
     * 真正变了的格：格里的字没变就整格沿用，变了才重建内容（[LeafTextEditor] 保住行内元素）。
     *
     * 行码与文字对不上（长度之和不等 / 不是同一张表的行）返回 null —— 调用方保持原样、不猜。
     */
    private fun buildTableRows(
        edit: TableEdit,
        oldRows: List<MarkupElement>,
        table: MarkupElement,
        segmentsOf: (MarkupElement) -> StyledSegments,
        wsOf: (MarkupElement) -> WhiteSpace,
        styleOf: ((MarkupElement) -> ComputedStyle?)?,
    ): RebuiltTable? {
        if (oldRows.isEmpty()) return null
        if (oldRows.any { it.tag != "tr" || ancestorTable(it) !== table }) return null
        val anchor = oldRows.first().parent ?: return null
        // 先按行码切出每行的新文字（行与行直接相接）。
        val newTexts = splitRowTexts(edit) ?: return null
        edit.review?.let { review ->
            return buildReviewTableRows(edit, review, newTexts, oldRows, anchor, segmentsOf, wsOf, styleOf)
        }
        val sources = rowSourcesOf(edit, oldRows, newTexts, segmentsOf)
        val spans = edit.styles
        val rowStarts = IntArray(newTexts.size)
        run {
            var at = 0
            for (i in newTexts.indices) {
                rowStarts[i] = at
                at += newTexts[i].length
            }
        }
        val rows = ArrayList<MarkupElement>(edit.rows.size)
        val lengths = IntArray(edit.rows.size)
        for (ri in edit.rows.indices) {
            val row = edit.rows[ri]
            val rowText = newTexts[ri]
            val oldRow = oldRows.getOrNull(sources[ri])
            // 没改到的行：整行原样留下，连节点身份都不换（祖先不复制，分组与继承都不动）。
            if (oldRow != null) {
                val oldRowText = segmentsOf(oldRow).text
                if (oldRowText == rowText && rowShapeOf(oldRow) == rowShapeOf(row)) {
                    rows += oldRow
                    lengths[ri] = oldRowText.length
                    continue
                }
            }
            val oldCells = oldRow?.children.orEmpty().filter { it.tag == "td" || it.tag == "th" }
            val cells = ArrayList<MarkupElement>(row.cells.size)
            for ((ci, cell) in row.cells.withIndex()) {
                val from = cell.start.coerceIn(0, rowText.length)
                val to = cell.endExclusive.coerceIn(from, rowText.length)
                val cellText = rowText.substring(from, to)
                val tag = if (cell.header) "th" else "td"
                val oldCell = oldCells.getOrNull(ci)
                // 格里的字没变：整格沿用（连属性、行内元素、格内块结构）。
                if (oldCell != null && oldCell.tag == tag && segmentsOf(oldCell).text == cellText) {
                    cells += oldCell
                    continue
                }
                val cellSpans = spans?.mapNotNull { sp ->
                    val s = maxOf(sp.start, rowStarts[ri] + from)
                    val e = minOf(sp.endExclusive, rowStarts[ri] + to)
                    if (e <= s) {
                        null
                    } else {
                        InlineMarkup.StyleSpan(s - rowStarts[ri] - from, e - rowStarts[ri] - from, sp.sig)
                    }
                }
                // 沿用旧格的书自己的样式 / 属性；合并属性按新结构重写，`id` 不复制（会撞）。
                val attrs = LinkedHashMap<String, String>()
                oldCell?.attrs?.forEach { (k, v) ->
                    if (k != "id" && k != "rowspan" && k != "colspan") attrs[k] = v
                }
                if (cell.rowspan > 1) attrs["rowspan"] = cell.rowspan.toString()
                if (cell.colspan > 1) attrs["colspan"] = cell.colspan.toString()
                val children = rebuildCellContent(oldCell, cellText, cellSpans, segmentsOf, wsOf, styleOf)
                    ?: LeafTextEditor.inlineNodes(cellText, cellSpans.orEmpty())
                cells += MarkupElement(tag = tag, attrs = attrs, children = children)
            }
            val rowEl = MarkupElement(
                tag = "tr",
                attrs = (oldRow?.attrs ?: emptyMap()).filterKeys { it != "id" },
                children = cells,
            )
            // 量长度要按真实父链级联（格继承表 / 行的字体与 white-space）。
            rowEl.parent = oldRow?.parent ?: anchor
            rows += rowEl
            lengths[ri] = segmentsOf(rowEl).text.length
        }
        return RebuiltTable(rows, lengths, sources)
    }

    /** 按行码把修订文字切成每行的新文字；行码与文字对不上返回 null。 */
    private fun splitRowTexts(edit: TableEdit): List<String>? {
        val out = ArrayList<String>(edit.rows.size)
        var cursor = 0
        for (row in edit.rows) {
            val rowLength = row.textLength
            if (rowLength < 0 || cursor + rowLength > edit.text.length) return null
            out += edit.text.substring(cursor, cursor + rowLength)
            cursor += rowLength
        }
        return out.takeIf { cursor == edit.text.length }
    }

    /**
     * 审阅视图的整表重建：按 [TableReviewPlan] 把旧行 / 新行排成「原文照排 + 改动就地插入」。
     *
     * 删掉的行用**原来的 `tr` 节点**留在原处（红字由修订表按区间上色）；新加的行建新节点；
     * 改过字的行在旧格上就地插入新字（[LeafTextEditor] 保住 class / style / 嵌套元素）。
     * 行序必须与 [TableReviewPlan.rows] 一致，文字长度必须与 [TableReviewPlan.overlay] 同尺。
     */
    private fun buildReviewTableRows(
        edit: TableEdit,
        review: TableReviewPlan,
        newTexts: List<String>,
        oldRows: List<MarkupElement>,
        anchor: MarkupElement,
        segmentsOf: (MarkupElement) -> StyledSegments,
        wsOf: (MarkupElement) -> WhiteSpace,
        styleOf: ((MarkupElement) -> ComputedStyle?)?,
    ): RebuiltTable? {
        if (review.rows.sumOf { it.textLength } != review.overlay.length) return null
        val reviewStyles = edit.styles
            ?.takeIf { it.isNotEmpty() }
            ?.let { review.overlay.stylesInText(edit.text, it) }
            .orEmpty()
        val rows = ArrayList<MarkupElement>(review.rows.size)
        val lengths = IntArray(review.rows.size)
        val sources = IntArray(review.rows.size) { -1 }
        var reviewAt = 0
        for ((pi, part) in review.rows.withIndex()) {
            when (part.kind) {
                TableReviewPlan.Kind.KEEP, TableReviewPlan.Kind.DELETE -> {
                    val oldRow = oldRows.getOrNull(part.oldIndex) ?: return null
                    rows += oldRow
                    lengths[pi] = part.textLength
                    sources[pi] = part.oldIndex
                }
                TableReviewPlan.Kind.ADD -> {
                    val ni = part.newIndex
                    val info = edit.rows.getOrNull(ni) ?: return null
                    val text = newTexts.getOrNull(ni) ?: return null
                    val rowEl = buildReviewNewRow(info, text, reviewAt, reviewStyles, anchor)
                    rows += rowEl
                    lengths[pi] = part.textLength
                    sources[pi] = -1
                }
                TableReviewPlan.Kind.CHANGE -> {
                    val oldRow = oldRows.getOrNull(part.oldIndex) ?: return null
                    val info = edit.rows.getOrNull(part.newIndex) ?: return null
                    val cellTexts = part.cellTexts ?: return null
                    val oldCells = oldRow.children.filter { it.tag == "td" || it.tag == "th" }
                    if (oldCells.size != cellTexts.size || info.cells.size != cellTexts.size) return null
                    val cells = ArrayList<MarkupElement>(cellTexts.size)
                    var cellAt = reviewAt
                    for (ci in cellTexts.indices) {
                        val oldCell = oldCells[ci]
                        val infoCell = info.cells[ci]
                        val target = cellTexts[ci]
                        if (segmentsOf(oldCell).text == target) {
                            cells += oldCell
                        } else {
                            val spans = sliceSpans(reviewStyles, cellAt, cellAt + target.length)
                            val children = rebuildCellContent(oldCell, target, spans, segmentsOf, wsOf, styleOf)
                                ?: LeafTextEditor.inlineNodes(target, spans)
                            val attrs = LinkedHashMap<String, String>(oldCell.attrs)
                            attrs.remove("id")
                            if (infoCell.rowspan > 1) {
                                attrs["rowspan"] = infoCell.rowspan.toString()
                            } else {
                                attrs.remove("rowspan")
                            }
                            if (infoCell.colspan > 1) {
                                attrs["colspan"] = infoCell.colspan.toString()
                            } else {
                                attrs.remove("colspan")
                            }
                            val tag = if (infoCell.header) "th" else "td"
                            cells += MarkupElement(tag, attrs, children)
                        }
                        cellAt += target.length
                    }
                    val rowEl = MarkupElement("tr", oldRow.attrs.filterKeys { it != "id" }, cells)
                    rowEl.parent = oldRow.parent
                    rows += rowEl
                    lengths[pi] = part.textLength
                    sources[pi] = part.oldIndex
                }
            }
            reviewAt += part.textLength
        }
        if (reviewAt != review.overlay.length) return null
        return RebuiltTable(rows, lengths, sources)
    }

    /** 审阅视图里新加的一行：按格结构建节点，文字直接用修订文字（绿字由修订表上色）。 */
    private fun buildReviewNewRow(
        info: TableRowInfo,
        text: String,
        reviewStart: Int,
        styles: List<InlineMarkup.StyleSpan>,
        anchor: MarkupElement,
    ): MarkupElement {
        val cells = ArrayList<MarkupElement>(info.cells.size)
        for (cell in info.cells) {
            val from = cell.start.coerceIn(0, text.length)
            val to = cell.endExclusive.coerceIn(from, text.length)
            val cellText = text.substring(from, to)
            val spans = sliceSpans(styles, reviewStart + from, reviewStart + to)
            val attrs = LinkedHashMap<String, String>()
            if (cell.rowspan > 1) attrs["rowspan"] = cell.rowspan.toString()
            if (cell.colspan > 1) attrs["colspan"] = cell.colspan.toString()
            val tag = if (cell.header) "th" else "td"
            cells += MarkupElement(tag, attrs, LeafTextEditor.inlineNodes(cellText, spans))
        }
        return MarkupElement("tr", children = cells).also { it.parent = anchor }
    }

    /** 把审阅空间的样式区间裁到一格里（[from] / [to] 是相对整段审阅文字的偏移）。 */
    private fun sliceSpans(
        spans: List<InlineMarkup.StyleSpan>,
        from: Int,
        to: Int,
    ): List<InlineMarkup.StyleSpan> {
        if (spans.isEmpty() || to <= from) return emptyList()
        val out = ArrayList<InlineMarkup.StyleSpan>(2)
        for (sp in spans) {
            val s = maxOf(sp.start, from)
            val e = minOf(sp.endExclusive, to)
            if (e > s) out += InlineMarkup.StyleSpan(s - from, e - from, sp.sig)
        }
        return out
    }

    /** 改过的格：在旧格上**就地替换文字**（[LeafTextEditor] 保住 `class` / `style` / 嵌套元素），
     * 拿不到旧格或结构对不上时返回 null，由调用方退回按文字重建。
     */
    private fun rebuildCellContent(
        oldCell: MarkupElement?,
        newText: String,
        spans: List<InlineMarkup.StyleSpan>?,
        segmentsOf: (MarkupElement) -> StyledSegments,
        wsOf: (MarkupElement) -> WhiteSpace,
        styleOf: ((MarkupElement) -> ComputedStyle?)?,
    ): List<MarkupElement>? {
        if (oldCell == null) return null
        val seg = segmentsOf(oldCell)
        if (seg.text == newText) return null
        return runCatching {
            LeafTextEditor.rebuild(
                leaf = oldCell,
                segments = seg,
                edits = listOf(LeafTextEditor.Edit(0, seg.text.length, newText, spans)),
                wsOf = wsOf,
                styleOf = styleOf,
                rebuildSegments = segmentsOf,
            ).leaf.children
        }.getOrNull()
    }

    /**
     * 每行来自旧表哪一行（[TableEdit.rowSources]；老修订没存过就按内容对齐）。
     *
     * 老修订的对齐规则：行文字 + 形状相同的行一定对上，缺口再按数量配对
     * （见 [alignRowsByKey]）；没改过的行一定完全相同。
     */
    private fun rowSourcesOf(
        edit: TableEdit,
        oldRows: List<MarkupElement>,
        newTexts: List<String>,
        segmentsOf: (MarkupElement) -> StyledSegments,
    ): IntArray {
        val stored = edit.rowSources
        if (stored != null && stored.size == newTexts.size) {
            var prev = -1
            var ok = true
            for (s in stored) {
                if (s < -1 || s >= oldRows.size || (s >= 0 && s <= prev)) {
                    ok = false
                    break
                }
                if (s >= 0) prev = s
            }
            if (ok) return IntArray(stored.size) { stored[it] }
        }
        val oldKeys = oldRows.map { rowKey(segmentsOf(it).text, rowShapeOf(it)) }
        val newKeys = newTexts.mapIndexed { i, t -> rowKey(t, rowShapeOf(edit.rows[i])) }
        return alignRowsByKey(oldKeys, newKeys)
    }

    private fun rowKey(text: String, shape: String): String = text + "\u0000" + shape

    private fun rowShapeOf(row: MarkupElement): String =
        row.children.filter { it.tag == "td" || it.tag == "th" }.joinToString("|") {
            "${it.tag}:${it.attrs["rowspan"]?.toIntOrNull() ?: 1}:${it.attrs["colspan"]?.toIntOrNull() ?: 1}"
        }

    private fun rowShapeOf(row: TableRowInfo): String =
        row.cells.joinToString("|") {
            "${if (it.header) "th" else "td"}:${it.rowspan}:${it.colspan}"
        }

    /**
     * 把重建结果落到树上：没改的行原地不动，改过的行原地换掉，新加的行挂到相邻行旁边，
     * 删掉的行撤掉。行节点留在它原来的 `thead` / `tbody` 里，不整表搬进第一行那一组。
     */
    private fun applyTableRowEdits(
        replacements: MutableMap<MarkupElement, List<MarkupElement>>,
        removed: MutableSet<MarkupElement>,
        oldRows: List<MarkupElement>,
        rebuilt: RebuiltTable,
    ) {
        val slot = arrayOfNulls<MutableList<MarkupElement>>(oldRows.size)
        val leading = ArrayList<MarkupElement>()
        var lastUsed = -1
        for (ri in rebuilt.rows.indices) {
            val src = rebuilt.sources[ri]
            if (src !in oldRows.indices) {
                if (lastUsed >= 0) slot[lastUsed]!!.add(rebuilt.rows[ri]) else leading += rebuilt.rows[ri]
                continue
            }
            val list = ArrayList<MarkupElement>(1 + leading.size)
            list.addAll(leading)
            leading.clear()
            list += rebuilt.rows[ri]
            slot[src] = list
            lastUsed = src
        }
        if (leading.isNotEmpty()) {
            // 旧行一个都没留下（整表换成读者新写的行）：占第一行的位置，其余旧行撤掉。
            slot[0] = leading
        }
        for (idx in oldRows.indices) {
            val s = slot[idx]
            if (s == null) {
                removed += oldRows[idx]
                continue
            }
            // 原样留下的行不进替换表：树里还是同一个节点（属性与分组都不动）。
            if (s.size == 1 && s[0] === oldRows[idx]) continue
            replacements[oldRows[idx]] = s
        }
    }

    /** 合并时能整层替换的「纯包装」标签：里面只装这一片叶，拆成多项时跟着复制。 */
    private val PURE_WRAPPERS = setOf("li", "dd", "dt")

    /**
     * 合并组里各叶的「替换单位」：叶是同一个父节点下的兄弟就一叶一个单位（返回 null，走老路）；
     * 每片叶各住在一层纯包装里（`ul > li > p`、`dl > dd > p`）就返回那层包装 —— 换单位时
     * 连包装一起换，拆出来的几块才能各占一个列表项。
     *
     * 包装里只要还装着别的东西（项目标签、脚注），复制一份就等于把那东西也抄上几遍；
     * 这种情形宁可按老路走，也不凭空造出重复内容。
     */
    private fun mergeUnits(
        leaves: List<MarkupElement>,
        first: Int,
        last: Int,
    ): List<MarkupElement>? {
        val units = ArrayList<MarkupElement>(last - first + 1)
        for (k in first..last) {
            val parent = leaves[k].parent ?: return null
            if (parent.tag !in PURE_WRAPPERS) return null
            if (parent.children.size != 1 || parent.children[0] !== leaves[k]) return null
            if (units.isNotEmpty() && (parent.tag != units[0].tag || parent.parent !== units[0].parent)) return null
            units.add(parent)
        }
        return units.takeIf { it.isNotEmpty() }
    }

    /**
     * 把一条跨叶修订落成若干块：能对上叶的复用原叶（保住行内样式与 `class`），
     * 对不上（拆分）就新建同 tag 的块；末块用末叶承载，后缀原样留在里面。
     */
    private fun mergeLeaves(
        group: List<Merge>,
        leaves: List<MarkupElement>,
        leafPaths: List<IntArray?>,
        charStarts: LongArray,
        segmentsOf: (MarkupElement) -> StyledSegments,
        wsOf: (MarkupElement) -> WhiteSpace,
        styleOf: ((MarkupElement) -> ComputedStyle?)?,
    ): MergeOut {
        val first = group.minOf { it.firstLeaf }
        val last = group.maxOf { it.lastLeaf }
        val simple = (first..last).all { k ->
            leafPaths[k] != null && RevisionBlocks.mergeableLeaf(leaves[k])
        }
        if (!simple) return MergeOut(emptyList(), 0, 0, needsRecompute = true)

        // 同一片叶（或首末叶之间）上的几处改动：按起点把它们各自的替换文字拼回**整段**原叶文字
        // （段界原样留着），合成一条「整段重塑」，再照常按段界拆块。跨叶修订与旁边的叶内修订
        // 落在同一组时，各条修订的起点都要先换算到「组的首叶起点」这把尺上（本组共用一份 base）。
        val merge = if (group.size == 1) {
            group[0]
        } else {
            val base = StringBuilder()
            for (k in first..last) base.append(segmentsOf(leaves[k]).text)
            val baseText = base.toString()
            val rebased = group.map { m ->
                val offset = (charStarts[m.firstLeaf] - charStarts[first]).toInt()
                m.copy(
                    firstLeaf = first,
                    lastLeaf = last,
                    start = m.start + offset,
                    endExclusive = m.endExclusive + offset,
                )
            }
            Merge(
                firstLeaf = first,
                lastLeaf = last,
                start = 0,
                endExclusive = baseText.length,
                text = composeLeaf(baseText, rebased),
                revisionId = group[0].revisionId,
            )
        }

        val lines = RevisionBlocks.split(merge.text)
        val n = last - first + 1
        val firstLeaf = leaves[first]
        val lastLeaf = leaves[last]
        val oldTexts = IntArray(n) { segmentsOf(leaves[first + it]).text.length }
        val oldLength = oldTexts.sum()
        val firstLen = oldTexts[0]
        val lastText = segmentsOf(lastLeaf).text
        val selStartInFirst = merge.start.coerceIn(0, firstLen)
        val selEndInLast = (merge.endExclusive - (charStarts[last] - charStarts[first]).toInt())
            .coerceIn(0, lastText.length)

        // 每行在 merge.text 里的起点，用来把样式区间切到块内。
        val lineStarts = IntArray(lines.size)
        run {
            var cursor = 0
            for (j in lines.indices) {
                lineStarts[j] = cursor
                cursor += lines[j].length + 1
            }
        }

        fun blockStyles(j: Int): List<InlineMarkup.StyleSpan>? {
            val all = merge.styles ?: return null
            val from = lineStarts[j]
            val to = from + lines[j].length
            return all.filter { it.start < to && it.endExclusive > from }
                .map { InlineMarkup.StyleSpan(maxOf(0, it.start - from), minOf(lines[j].length, it.endExclusive - from), it.sig) }
        }

        val blocks = ArrayList<Block>(lines.size)
        var degraded = 0
        // 每块的目标形态（数量对不上就当没带块信息，宁可不动结构也不猜）。
        val kinds = merge.kinds?.takeIf { it.size == lines.size }

        fun emitRebuilt(index: Int, edit: LeafTextEditor.Edit, kind: BlockKind?) {
            val leaf = leaves[index]
            val r = LeafTextEditor.rebuild(
                leaf = leaf,
                segments = segmentsOf(leaf),
                edits = listOf(edit),
                wsOf = wsOf,
                styleOf = styleOf,
                rebuildSegments = segmentsOf,
            )
            if (r.degraded) degraded++
            // 只记「与来源叶不同」的目标形态：相同的块一个节点都不动。
            blocks += Block(r.leaf, r.text.length, wantKindOf(leaf, kind))
        }

        fun emitNew(text: String, spans: List<InlineMarkup.StyleSpan>? = null, kind: BlockKind?) {
            val el = MarkupElement(
                tag = firstLeaf.tag,
                attrs = firstLeaf.attrs - "id",
                children = LeafTextEditor.inlineNodes(text, spans.orEmpty()),
            )
            // 量长度要按同一套级联（继承父容器的字体 / white-space）；重链在最后统一做。
            el.parent = firstLeaf.parent
            blocks += Block(el, segmentsOf(el).text.length, wantKindOf(firstLeaf, kind))
        }

        if (lines.size == 1) {
            // 并成一段：首叶承载前缀 + 新文字 + 末叶后缀，中间的叶全删。
            val suffix = if (last == first) "" else lastText.substring(selEndInLast)
            emitRebuilt(
                first,
                LeafTextEditor.Edit(selStartInFirst, firstLen, lines[0] + suffix, blockStyles(0)),
                kinds?.getOrNull(0),
            )
        } else if (first == last) {
            // 单叶拆块：选区在一段之内，却输入了段界（读者新插的换行）。前缀 + 第一行留在原叶，
            // 中间各行新建同 tag / 同属性的兄弟块，最后一行 + 后缀交给「原叶的另一份重建」——
            // 一片叶只能承载一块，前缀和后缀要分两处，就只能各复制一份（复制的那份去掉 id）。
            val at = selStartInFirst
            val end = selEndInLast
            emitRebuilt(first, LeafTextEditor.Edit(at, firstLen, lines.first(), blockStyles(0)), kinds?.getOrNull(0))
            for (j in 1 until lines.size - 1) emitNew(lines[j], blockStyles(j), kinds?.getOrNull(j))
            val tail = LeafTextEditor.rebuild(
                leaf = lastLeaf,
                segments = segmentsOf(lastLeaf),
                edits = listOf(LeafTextEditor.Edit(0, end, lines.last(), blockStyles(lines.lastIndex))),
                wsOf = wsOf,
                styleOf = styleOf,
                rebuildSegments = segmentsOf,
            )
            if (tail.degraded) degraded++
            val tailLeaf = if ("id" in lastLeaf.attrs) {
                MarkupElement(
                    tag = tail.leaf.tag,
                    attrs = tail.leaf.attrs - "id",
                    children = tail.leaf.children,
                    text = tail.leaf.text,
                ).also { it.parent = lastLeaf.parent }
            } else {
                tail.leaf
            }
            blocks += Block(tailLeaf, tail.text.length, kinds?.getOrNull(lines.lastIndex))
        } else {
            // 前 m-1 行：能对上叶的复用原叶，多出来的行新建块。
            for (j in 0 until lines.size - 1) {
                val index = first + j
                if (j <= n - 2 && index < last) {
                    emitRebuilt(
                        index,
                        LeafTextEditor.Edit(
                            start = if (j == 0) selStartInFirst else 0,
                            endExclusive = oldTexts[j],
                            text = lines[j],
                            styles = blockStyles(j),
                        ),
                        kinds?.getOrNull(j),
                    )
                } else {
                    emitNew(lines[j], blockStyles(j), kinds?.getOrNull(j))
                }
            }
            // 末行接末叶后缀：用末叶承载，后缀的行内样式不动。
            emitRebuilt(
                last,
                LeafTextEditor.Edit(0, selEndInLast, lines.last(), blockStyles(lines.lastIndex)),
                kinds?.getOrNull(lines.lastIndex),
            )
        }
        return MergeOut(
            blocks = blocks,
            delta = blocks.sumOf { it.textLength } - oldLength,
            degraded = degraded,
            needsRecompute = false,
        )
    }

    private class Rebuilt(
        val root: MarkupElement,
        val map: Map<MarkupElement, MarkupElement>,
    )

    /**
     * 一次性把替换 / 删除落到树上：没变的子树原样复用（身份不变），只有换了孩子的祖先才复制。
     * 返回旧节点 → 新节点的对照表（被删掉 / 被换掉的叶内部节点不在表里）。
     */
    private fun rebuildTree(
        root: MarkupElement,
        replacements: Map<MarkupElement, List<MarkupElement>>,
        removed: Set<MarkupElement>,
        /**
         * 属主 → 新的 children（匿名 run 重建）：其余孩子仍走 [visit] 正常替换，
         * 不能整节点替换，否则同一属主下的真块叶修订会被跳过。
         */
        childOverrides: Map<MarkupElement, List<MarkupElement>> = emptyMap(),
    ): Rebuilt {
        val map = HashMap<MarkupElement, MarkupElement>()
        fun visit(node: MarkupElement): List<MarkupElement> {
            replacements[node]?.let { return it }
            if (node in removed) return emptyList()
            val override = childOverrides[node]
            val source = override ?: node.children
            if (source.isEmpty()) {
                if (override == null) {
                    map[node] = node
                    return listOf(node)
                }
                // 覆盖成空孩子也要复制（文字被删光的匿名 run）。
                val copy = MarkupElement(node.tag, node.attrs, emptyList(), node.text)
                map[node] = copy
                return listOf(copy)
            }
            var changed = override != null
            val out = ArrayList<MarkupElement>(source.size)
            for (c in source) {
                val r = visit(c)
                if (r.size == 1 && r[0] === c) {
                    out += c
                } else {
                    changed = true
                    out += r
                }
            }
            if (!changed) {
                map[node] = node
                return listOf(node)
            }
            val copy = MarkupElement(node.tag, node.attrs, out, node.text)
            map[node] = copy
            return listOf(copy)
        }
        val rootOut = visit(root).firstOrNull() ?: root
        return Rebuilt(rootOut, map)
    }

    /**
     * 把 [path] 指向的节点换成 [replacement]，返回新根（路径上的祖先按原 attrs 复制，其余子树不动）。
     * 路径在替换后保持不变（只换节点、不改兄弟数量）。
     */
    fun replaceAt(root: MarkupElement, path: IntArray, replacement: MarkupElement): MarkupElement {
        if (path.isEmpty()) return replacement
        fun walk(node: MarkupElement, depth: Int): MarkupElement {
            val idx = path[depth]
            require(idx in node.children.indices) { "路径越界：$depth/$idx（children=${node.children.size}）" }
            val children = node.children.toMutableList()
            children[idx] = if (depth == path.size - 1) {
                replacement
            } else {
                walk(children[idx], depth + 1)
            }
            return MarkupElement(node.tag, node.attrs, children)
        }
        return walk(root, 0)
    }
}
