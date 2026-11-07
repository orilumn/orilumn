package orilumn.reader.engine.revision

import orilumn.reader.engine.css.ComputedStyle
import orilumn.reader.engine.css.FontVariant
import orilumn.reader.engine.css.WhiteSpace
import orilumn.reader.engine.css.WhiteSpaceNormalize
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.laying.GeneratedContent
import orilumn.reader.engine.laying.StyledSegments

/**
 * 叶级重建：把一条（或几条）修订套到**排版叶**上，逐段保留行内元素的类与样式（方案 D-33 / §8.5）。
 *
 * 一次重建只动这一片叶：
 * - 没被改到的文本节点原样保留（连原始空白都不重写）；
 * - 被改到的节点只改文字，`class` / `style` / 其他属性一个都不动；
 * - 中段与旧中段做字符级对齐（[CharDiff]）：没变的字留在原节点，新字挂到**前一个字**所在节点，
 *   删掉的字从原节点移除；`\n` 落成 `<br>`（插在同一文本节点的字符之间时会把该节点切开）；
 * - 元素本身永不剪枝：文字被删光时元素留空，伪元素 / 边框照旧。
 *
 * 输入坐标是**叶内原书偏移**（不含修订；与 `StyledSegments.text` 同一串文字）。
 * [rebuildSegments] 用于重建后再取一次段做校验：若结果与「原文本按区间替换」不等
 * （只可能是节点边界空白折叠），返回 `degraded = true`，由调用方记日志并以实测长度为准。
 *
 * 本类属于排版层（上）的纯逻辑：不碰 Android、不碰渲染，可 JVM 单测。
 */
object LeafTextEditor {

    /** 一次叶内替换：`[start, endExclusive)` → [text]（叶内原书偏移，已预归一）。 */
    data class Edit(val start: Int, val endExclusive: Int, val text: String)

    /**
     * @property leaf 重建后的叶（未改动的子树按原样复制，parent 已重链）；
     * @property text 重建后实测的叶文本（= [rebuildSegments] 的结果）；
     * @property expected 按区间替换应有的文本；
     * @property degraded 实测与应有不等（空白边界导致；调用方记日志并以实测为准）。
     */
    data class Result(
        val leaf: MarkupElement,
        val text: String,
        val expected: String,
        val degraded: Boolean,
    )

    /**
     * 提交时的替换文本预归一：按叶的 `white-space` 归一，再按前后邻字符 / `<br>` 去边界空格，
     * 使「渲染文本 = 原文本按区间替换」尽量严格成立（§8.4）。
     *
     * @param before 中段前一个字符（叶首为 null）；@param after 中段后一个字符（叶尾为 null）。
     *   `\n` 表示 `<br>`。只合并浏览器本来就会合并掉的空白，不改用户可见的字。
     */
    fun preNormalize(text: String, ws: WhiteSpace, before: Char?, after: Char?): String {
        // 输入里的 `\n` 是 `<br>`，不是可折叠的源码空白：逐行归一后再拼回。
        // （`normalizeNode` 只处理单个文本节点，`<br>` 在重放里是独立节点，不能在这里被吃掉。）
        var t = if (text.indexOf('\n') < 0) {
            WhiteSpaceNormalize.normalizeNode(text, ws)
        } else {
            text.split('\n').joinToString("\n") { WhiteSpaceNormalize.normalizeNode(it, ws) }
        }
        if (ws == WhiteSpace.NORMAL || ws == WhiteSpace.NOWRAP || ws == WhiteSpace.PRE_LINE) {
            if (t.startsWith(' ') && (before == null || before.isWhitespace() || before == '\n')) {
                t = t.substring(1)
            }
            if (t.endsWith(' ') && (after == null || after.isWhitespace() || after == '\n')) {
                t = t.dropLast(1)
            }
        }
        return t
    }

    /**
     * 把 [edits] 套到 [leaf] 上。
     *
     * @param segments 该叶当前的 [StyledSegments]（与排版、字符投影同一个单源）；
     * @param wsOf / styleOf 必须与生成 [segments] 时用的**同一组**回调，否则归一化映射会错位；
     * @param rebuildSegments 重建后重新取段（用于校验与量长度），通常就是同一个 styledSegments 调用。
     */
    fun rebuild(
        leaf: MarkupElement,
        segments: StyledSegments,
        edits: List<Edit>,
        wsOf: (MarkupElement) -> WhiteSpace,
        styleOf: ((MarkupElement) -> ComputedStyle?)?,
        rebuildSegments: (MarkupElement) -> StyledSegments,
    ): Result {
        val oldText = segments.text
        val sorted = edits.sortedBy { it.start }
        var prevEnd = 0
        for (e in sorted) {
            require(e.start in prevEnd..oldText.length && e.endExclusive in e.start..oldText.length) {
                "叶内修订越界或重叠：[${e.start}, ${e.endExclusive})（叶长 ${oldText.length}，上一条止于 $prevEnd）"
            }
            prevEnd = e.endExclusive
        }

        // 1) 逐字归属：叶文本的每个字符来自哪个文本节点 / <br> / 生成内容。
        val owners = ownersOf(segments, leaf, wsOf, styleOf)
        check(owners.size == oldText.length) {
            "段文本与字符归属长度不一致：${owners.size} vs ${oldText.length}"
        }

        // 2) 应用修订：保留的字符沿用归属；删掉的记下原文区间；新字符先记为 New。
        val out = ArrayList<OutChar>(oldText.length + 16)
        val deleted = LinkedHashMap<MarkupElement, MutableList<IntRange>>()
        var cursor = 0
        for (e in sorted) {
            for (i in cursor until e.start) out.add(OutChar.Kept(owners[i]))
            val old = oldText.substring(e.start, e.endExclusive)
            for (op in CharDiff.diff(old, e.text)) {
                when (op) {
                    is CharDiff.Op.Keep -> {
                        for (i in op.oldStart until op.oldStart + op.length) {
                            out.add(OutChar.Kept(owners[e.start + i]))
                        }
                    }
                    is CharDiff.Op.Delete -> {
                        for (i in op.oldStart until op.oldStart + op.length) {
                            val owner = owners[e.start + i]
                            if (owner is Owner.Text) {
                                deleted.getOrPut(owner.node) { ArrayList() }
                                    .add(owner.rawStart until owner.rawEnd)
                            }
                        }
                    }
                    is CharDiff.Op.Insert -> op.text.forEach { out.add(OutChar.New(it)) }
                }
            }
            cursor = e.endExclusive
        }
        for (i in cursor until owners.size) out.add(OutChar.Kept(owners[i]))

        // 3) 新字符归属：前一个字符所在的文本节点；叶首挂到后一个文本节点；都没有就新建一个。
        val prevTextNode = arrayOfNulls<MarkupElement>(out.size)
        var last: MarkupElement? = null
        for (i in out.indices) {
            val o = out[i]
            if (o is OutChar.Kept && o.owner is Owner.Text) last = o.owner.node
            prevTextNode[i] = last
        }
        var next: MarkupElement? = null
        for (i in out.indices.reversed()) {
            val o = out[i]
            if (o is OutChar.Kept && o.owner is Owner.Text) next = o.owner.node
            if (prevTextNode[i] == null) prevTextNode[i] = next
        }
        // 整段换新字（没有任何保留字）时退化：全部落在中段起点那个文本节点（§8.5）。
        val fallbackText = owners.firstOrNull { it is Owner.Text }?.node
        var synthetic: MarkupElement? = null
        val nodeItems = LinkedHashMap<MarkupElement, MutableList<NodeItem>>()
        val siblingMarkers = ArrayList<MarkupElement>()
        val markerAnchors = HashMap<MarkupElement, MarkupElement?>()
        val prependNodes = ArrayList<MarkupElement>()
        for (i in out.indices) {
            when (val o = out[i]) {
                is OutChar.Kept -> {
                    val owner = o.owner
                    if (owner is Owner.Text) {
                        nodeItems.getOrPut(owner.node) { ArrayList() }
                            .add(NodeItem.KeptRaw(owner.rawStart, owner.rawEnd))
                    }
                }
                is OutChar.New -> {
                    val textNode = prevTextNode[i] ?: fallbackText
                    if (textNode != null) {
                        nodeItems.getOrPut(textNode) { ArrayList() }
                            .add(if (o.ch == '\n') NodeItem.Newline else NodeItem.Text(o.ch))
                    } else if (o.ch == '\n') {
                        // 叶首或前一个字符不是文本节点：<br> 作为兄弟插到前一个节点之后。
                        val br = MarkupElement("br")
                        siblingMarkers.add(br)
                        val anchor = previousAnchor(out, i)
                        markerAnchors[br] = anchor
                        if (anchor == null) prependNodes.add(br)
                    } else {
                        val node = synthetic ?: MarkupElement("#text").also { synthetic = it }
                        nodeItems.getOrPut(node) { ArrayList() }.add(NodeItem.Text(o.ch))
                    }
                }
            }
        }
        synthetic?.let { s ->
            val nodes = materialize(s, nodeItems[s].orEmpty(), deleted[s].orEmpty())
            if (nodes.isNotEmpty()) prependNodes.addAll(nodes)
        }

        // 4) 重链：文本节点按字符归属重建（可能被 <br> 切成多段）、br 删/增、其余子树原样复制。
        val insertAfter = HashMap<MarkupElement, MutableList<MarkupElement>>()
        for (br in siblingMarkers) {
            val anchor = markerAnchors[br]
            if (anchor != null) insertAfter.getOrPut(anchor) { ArrayList() }.add(br)
        }
        val dropped = visibleBrNodes(owners) - keptBrNodes(out)

        fun rebuildNodes(node: MarkupElement): List<MarkupElement> {
            if (node.isText) {
                if (node !in nodeItems && node !in deleted) return listOf(MarkupElement("#text", text = node.text))
                return materialize(node, nodeItems[node].orEmpty(), deleted[node].orEmpty())
            }
            if (node.tag == "br") return if (node in dropped) emptyList() else listOf(MarkupElement("br"))
            if (node.tag == "img") return listOf(MarkupElement("img", node.attrs))
            val children = ArrayList<MarkupElement>(node.children.size + 2)
            if (node === leaf) children.addAll(prependNodes)
            for (c in node.children) {
                children.addAll(rebuildNodes(c))
                insertAfter[c]?.let { children.addAll(it) }
            }
            if (node === leaf) insertAfter[node]?.let { children.addAll(it) }
            return listOf(MarkupElement(node.tag, node.attrs, children))
        }

        val rebuiltNodes = rebuildNodes(leaf)
        val rebuilt = when {
            rebuiltNodes.size == 1 -> rebuiltNodes[0]
            leaf.isText -> leaf // 匿名文本叶被 <br> 切开：没有父容器可挂，退化（调用方记日志）
            else -> MarkupElement(leaf.tag, leaf.attrs, rebuiltNodes)
        }
        assignParents(rebuilt, null)

        val expected = applyEdits(oldText, sorted)
        val actual = rebuildSegments(rebuilt).text
        return Result(leaf = rebuilt, text = actual, expected = expected, degraded = actual != expected)
    }

    /** 按区间替换得到应有的叶文本（校验用）。 */
    fun applyEdits(text: String, edits: List<Edit>): String {
        if (edits.isEmpty()) return text
        val sb = StringBuilder(text.length + 16)
        var cursor = 0
        for (e in edits.sortedBy { it.start }) {
            sb.append(text, cursor, e.start)
            sb.append(e.text)
            cursor = e.endExclusive
        }
        sb.append(text, cursor, text.length)
        return sb.toString()
    }

    // ---- 内部模型 ----

    private sealed interface Owner {
        val node: MarkupElement

        class Text(override val node: MarkupElement, val rawStart: Int, val rawEnd: Int) : Owner
        class Marker(override val node: MarkupElement) : Owner
        class Generated(override val node: MarkupElement) : Owner
    }

    private sealed interface OutChar {
        class Kept(val owner: Owner) : OutChar
        class New(val ch: Char) : OutChar
    }

    private sealed interface NodeItem {
        class KeptRaw(val rawStart: Int, val rawEnd: Int) : NodeItem
        class Text(val text: Char) : NodeItem
        object Newline : NodeItem
    }

    /** 单个文本节点的归一化 + 变换结果，供段文本反查原文下标。 */
    private class NodeInfo(
        node: MarkupElement,
        wsOf: (MarkupElement) -> WhiteSpace,
        styleOf: ((MarkupElement) -> ComputedStyle?)?,
    ) {
        val normalized = WhiteSpaceNormalize.normalizeNodeWithMap(node.text, wsOf(node))
        val transformed: String = run {
            val st = styleOf?.invoke(node)
            val smallCaps = (st?.fontVariant ?: FontVariant.NORMAL) == FontVariant.SMALL_CAPS
            when {
                smallCaps -> GeneratedContent.applySmallCaps(normalized.text).first
                st != null -> GeneratedContent.applyTransform(normalized.text, st.textTransform)
                else -> normalized.text
            }
        }
        private var cursor = 0

        /** 在变换后的文本里定位这一段的起点，并推进游标（段文本是它的一段连续子串）。 */
        fun take(segmentText: String): Int {
            val found = transformed.indexOf(segmentText, cursor)
            val at = if (found >= 0) found else cursor.coerceAtMost(transformed.length)
            cursor = at + segmentText.length
            return at
        }
    }

    private fun ownersOf(
        segments: StyledSegments,
        leaf: MarkupElement,
        wsOf: (MarkupElement) -> WhiteSpace,
        styleOf: ((MarkupElement) -> ComputedStyle?)?,
    ): List<Owner> {
        val out = ArrayList<Owner>(segments.text.length)
        val info = HashMap<MarkupElement, NodeInfo>()
        for (seg in segments.segments) {
            if (seg.text.isEmpty()) continue
            val node = seg.node
            if (node != null && node.isText) {
                val n = info.getOrPut(node) { NodeInfo(node, wsOf, styleOf) }
                val at = n.take(seg.text)
                for (j in seg.text.indices) {
                    val k = at + j
                    if (k in n.normalized.text.indices) {
                        out.add(Owner.Text(node, n.normalized.rawStart[k], n.normalized.rawEnd[k]))
                    } else {
                        // 理论上不可达；保守起见按整节点归属，宁可退化也不越界。
                        out.add(Owner.Text(node, 0, node.text.length))
                    }
                }
            } else if (node != null && (node.tag == "br" || node.tag == "img")) {
                repeat(seg.text.length) { out.add(Owner.Marker(node)) }
            } else {
                repeat(seg.text.length) { out.add(Owner.Generated(node ?: leaf)) }
            }
        }
        return out
    }

    /** 新 `<br>` 的兄弟锚点：前一个字符所在的节点；叶首则 null（挂到叶最前）。 */
    private fun previousAnchor(out: List<OutChar>, index: Int): MarkupElement? {
        for (i in index - 1 downTo 0) {
            val o = out[i]
            if (o is OutChar.Kept) return o.owner.node
        }
        return null
    }

    /** 参与叶文本的 `<br>` 节点（只这些才可能被删；排除/块外的 br 不参与）。 */
    private fun visibleBrNodes(owners: List<Owner>): Set<MarkupElement> =
        owners.mapNotNullTo(HashSet()) {
            val node = (it as? Owner.Marker)?.node
            if (node?.tag == "br") node else null
        }

    private fun keptBrNodes(out: List<OutChar>): Set<MarkupElement> =
        out.mapNotNullTo(HashSet()) {
            val node = ((it as? OutChar.Kept)?.owner as? Owner.Marker)?.node
            if (node?.tag == "br") node else null
        }

    /**
     * 把一个文本节点的新内容落成节点列表：保留的原文区间原样拼回、删掉的区间跳过、
     * 新字符 / 换行按锚点插入；插入的换行把文本切成多个 `#text` 兄弟 + `<br>`。
     */
    private fun materialize(
        node: MarkupElement,
        items: List<NodeItem>,
        deleted: List<IntRange>,
    ): List<MarkupElement> {
        val raw = node.text
        val kept = items.filterIsInstance<NodeItem.KeptRaw>()
        val out = ArrayList<MarkupElement>(2)
        val sb = StringBuilder(raw.length)
        fun flush() {
            if (sb.isNotEmpty()) {
                out.add(MarkupElement("#text", text = sb.toString()))
                sb.clear()
            }
        }
        if (kept.isEmpty()) {
            for (it in items) {
                when (it) {
                    is NodeItem.Text -> sb.append(it.text)
                    NodeItem.Newline -> {
                        flush()
                        out.add(MarkupElement("br"))
                    }
                    is NodeItem.KeptRaw -> Unit
                }
            }
            flush()
            return out
        }

        // 新字符 / 换行的锚点：该节点内前一个保留字符的原文末尾；前面没有就取后一个保留字符的起点。
        val events = ArrayList<Pair<Int, NodeItem>>(items.size)
        var lastEnd = -1
        for ((idx, it) in items.withIndex()) {
            when (it) {
                is NodeItem.KeptRaw -> lastEnd = it.rawEnd
                is NodeItem.Text, NodeItem.Newline -> {
                    val anchor = if (lastEnd >= 0) lastEnd else nextKeptStart(items, idx)
                    events.add(anchor to it)
                }
            }
        }
        events.sortBy { it.first }

        val drops = deleted.sortedBy { it.first }
        var i = 0
        var di = 0
        var ei = 0
        while (i <= raw.length) {
            while (ei < events.size && events[ei].first <= i) {
                when (val it = events[ei].second) {
                    is NodeItem.Text -> sb.append(it.text)
                    NodeItem.Newline -> {
                        flush()
                        out.add(MarkupElement("br"))
                    }
                    is NodeItem.KeptRaw -> Unit
                }
                ei++
            }
            if (i == raw.length) break
            if (di < drops.size && i >= drops[di].first) {
                i = maxOf(i, drops[di].last + 1)
                di++
                continue
            }
            sb.append(raw[i])
            i++
        }
        flush()
        return out
    }

    private fun nextKeptStart(items: List<NodeItem>, from: Int): Int {
        for (i in from + 1 until items.size) {
            val it = items[i]
            if (it is NodeItem.KeptRaw) return it.rawStart
        }
        return 0
    }

    private fun assignParents(node: MarkupElement, parent: MarkupElement?) {
        node.parent = parent
        for (c in node.children) assignParents(c, node)
    }
}
