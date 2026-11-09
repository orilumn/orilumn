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
        anonymousEdit: AnonymousRunEditor? = null,
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
        var degraded = 0
        var needsRecompute = false
        var shift = 0L

        var i = 0
        while (i < leaves.size) {
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
                    }
                    shift += out.delta
                    replacements[leaves[firstLeaf]] = out.blocks.map { it.element }
                    for (k in firstLeaf..lastLeaf) {
                        if (k !in out.usedLeaves) removed += leaves[k]
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

        val rebuilt = rebuildTree(root, replacements, removed, childOverrides)
        val tree = rebuilt.root
        MarkupPaths.assignParents(tree)
        // 合成匿名叶不在 children 里，assignParents 够不到：按重建后的属主补 parent。
        for ((leaf, owner) in anonymousLeafOwner) {
            leaf.parent = rebuilt.map[owner] ?: owner
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
            elementMap = rebuilt.map,
        )
    }

    /** 一块的节点与实测文本长度（渲染空间）。 */
    private class Block(val element: MarkupElement, val textLength: Int)

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
        val usedLeaves: Set<Int>,
        val delta: Int,
        val degraded: Int,
        val needsRecompute: Boolean,
    )

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
        if (!simple) return MergeOut(emptyList(), emptySet(), 0, 0, needsRecompute = true)

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

        val used = LinkedHashSet<Int>()
        val blocks = ArrayList<Block>(lines.size)
        var degraded = 0

        fun emitRebuilt(index: Int, edit: LeafTextEditor.Edit) {
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
            used += index
            blocks += Block(r.leaf, r.text.length)
        }

        fun emitNew(text: String) {
            val el = MarkupElement(
                tag = firstLeaf.tag,
                attrs = firstLeaf.attrs - "id",
                children = if (text.isEmpty()) emptyList() else listOf(MarkupElement("#text", text = text)),
            )
            // 量长度要按同一套级联（继承父容器的字体 / white-space）；重链在最后统一做。
            el.parent = firstLeaf.parent
            blocks += Block(el, segmentsOf(el).text.length)
        }

        if (lines.size == 1) {
            // 并成一段：首叶承载前缀 + 新文字 + 末叶后缀，中间的叶全删。
            val suffix = if (last == first) "" else lastText.substring(selEndInLast)
            emitRebuilt(first, LeafTextEditor.Edit(selStartInFirst, firstLen, lines[0] + suffix))
        } else if (first == last) {
            // 单叶拆块：选区在一段之内，却输入了段界（读者新插的换行）。前缀 + 第一行留在原叶，
            // 中间各行新建同 tag / 同属性的兄弟块，最后一行 + 后缀交给「原叶的另一份重建」——
            // 一片叶只能承载一块，前缀和后缀要分两处，就只能各复制一份（复制的那份去掉 id）。
            val at = selStartInFirst
            val end = selEndInLast
            emitRebuilt(first, LeafTextEditor.Edit(at, firstLen, lines.first()))
            for (j in 1 until lines.size - 1) emitNew(lines[j])
            val tail = LeafTextEditor.rebuild(
                leaf = lastLeaf,
                segments = segmentsOf(lastLeaf),
                edits = listOf(LeafTextEditor.Edit(0, end, lines.last())),
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
            blocks += Block(tailLeaf, tail.text.length)
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
                        ),
                    )
                } else {
                    emitNew(lines[j])
                }
            }
            // 末行接末叶后缀：用末叶承载，后缀的行内样式不动。
            emitRebuilt(last, LeafTextEditor.Edit(0, selEndInLast, lines.last()))
        }
        return MergeOut(
            blocks = blocks,
            usedLeaves = used,
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
