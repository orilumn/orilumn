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
 * - 剪枝：字被删光的元素整个走掉，不留空壳（规格 §4.3）。`br` / 行内 `img` 同理：占位字
 *   （`\n` / `U+FFFC`）被删掉、新文字里也没留住，节点就跟着走（读者删掉图那一格就是要删这张图，
 *   不能「字没了、图还在」）。**书里本来就写成空的元素（没有子节点）不碰**，那是原样；
 *   排版叶自己（`p` / `li` / 标题）永不剪枝。
 *
 * 输入坐标是**叶内原书偏移**（不含修订；与 `StyledSegments.text` 同一串文字）。
 * [rebuildSegments] 用于重建后再取一次段做校验：若结果与「原文本按区间替换」不等
 * （只可能是节点边界空白折叠），返回 `degraded = true`，由调用方记日志并以实测长度为准。
 *
 * 本类属于排版层（上）的纯逻辑：不碰 Android、不碰渲染，可 JVM 单测。
 */
object LeafTextEditor {

    /**
     * 一次叶内替换：`[start, endExclusive)` → [text]（叶内原书偏移，已预归一）。
     *
     * @param styles 显式行内样式区间，偏移相对 [text]（含段界字符）。
     *   `null` = 没有样式信息（老修订 / 规划器没给），沿用「新字挂左邻」的旧规则；
     *   非 null（可以是空表）= 读者声明过这段文字的行内结构，**整段按区间重建**：
     *   区间里套着的字落在对应元素里，没套着的落在叶级（不会被左边那个元素吞掉）。
     */
    data class Edit(
        val start: Int,
        val endExclusive: Int,
        val text: String,
        val styles: List<InlineMarkup.StyleSpan>? = null,
    )

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
        //    带显式样式的修订不走字符级对齐：整段文字都由区间重建（§4.4 的「没写标记沿用旧规则」
        //    只对**没有**样式信息的修订成立）。
        val out = ArrayList<OutChar>(oldText.length + 16)
        val deleted = LinkedHashMap<MarkupElement, MutableList<IntRange>>()
        val regions = ArrayList<ExplicitRegion>()
        val regionOf = ArrayList<Int>(oldText.length + 16)
        var cursor = 0
        for (e in sorted) {
            for (i in cursor until e.start) {
                out.add(OutChar.Kept(owners[i]))
                regionOf.add(-1)
            }
            if (e.styles != null) {
                // 整段旧字全部让位：它们的原文区间记为删除，新字由样式区间重建。
                for (i in e.start until e.endExclusive) {
                    val owner = owners[i]
                    if (owner is Owner.Text) {
                        deleted.getOrPut(owner.node) { ArrayList() }
                            .add(owner.rawStart until owner.rawEnd)
                    }
                }
                val first = out.size
                e.text.forEach {
                    out.add(OutChar.New(it))
                    regionOf.add(regions.size)
                }
                regions.add(ExplicitRegion(first, out.size, e.styles))
            } else {
                val old = oldText.substring(e.start, e.endExclusive)
                for (op in CharDiff.diff(old, e.text)) {
                    when (op) {
                        is CharDiff.Op.Keep -> {
                            for (i in op.oldStart until op.oldStart + op.length) {
                                out.add(OutChar.Kept(owners[e.start + i]))
                                regionOf.add(-1)
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
                        is CharDiff.Op.Insert -> op.text.forEach {
                            out.add(OutChar.New(it))
                            regionOf.add(-1)
                        }
                    }
                }
            }
            cursor = e.endExclusive
        }
        for (i in cursor until owners.size) {
            out.add(OutChar.Kept(owners[i]))
            regionOf.add(-1)
        }

        // 2.5) 行内图：新文字里还留着 `U+FFFC` 占位（读者没删那一格）就把原图节点绑回这个位置。
        //      不绑的话，这一格会落成一个只有占位符、没有图的字符，等于把图弄丢了。
        bindKeptImages(out, owners)

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
        // 每个位置之后最近的一个「保留字」所在文本节点（显式样式区间定位锚点用）。
        val nextKeptText = arrayOfNulls<MarkupElement>(out.size)
        var nextKept: MarkupElement? = null
        for (i in out.indices.reversed()) {
            val o = out[i]
            if (o is OutChar.Kept && o.owner is Owner.Text) nextKept = o.owner.node
            nextKeptText[i] = nextKept
        }
        // 整段换新字（没有任何保留字）时退化：全部落在中段起点那个文本节点（§8.5）。
        val fallbackText = owners.firstOrNull { it is Owner.Text }?.node
        var synthetic: MarkupElement? = null
        val nodeItems = LinkedHashMap<MarkupElement, MutableList<NodeItem>>()
        val siblingMarkers = ArrayList<MarkupElement>()
        val markerAnchors = HashMap<MarkupElement, MarkupElement?>()
        val prependNodes = ArrayList<MarkupElement>()
        val insertAfter = HashMap<MarkupElement, MutableList<MarkupElement>>()
        // 新字符落点：前面紧挨着一个保留的替换元素（行内图 / `<br>`）时，在**它后面**新开一个
        // 文本节点。否则新字会跨过它落进更靠后的文本节点里，图片就从两个字之间跳到了前面。
        val afterMarkerText = HashMap<MarkupElement, MarkupElement>()
        val prevKeptIdx = IntArray(out.size) { -1 }
        run {
            var last = -1
            for (k in out.indices) {
                prevKeptIdx[k] = last
                if (out[k] is OutChar.Kept) last = k
            }
        }
        val nextKeptIdx = IntArray(out.size) { -1 }
        run {
            var next = -1
            for (k in out.indices.reversed()) {
                nextKeptIdx[k] = next
                if (out[k] is OutChar.Kept) next = k
            }
        }

        /** 一个新增字 / 换行该落进哪个文本节点；null = 叶首那一串（[synthetic] / 兄弟节点）。 */
        fun nodeForNewChar(index: Int): MarkupElement? {
            val p = prevKeptIdx[index]
            if (p >= 0) {
                val owner = (out[p] as OutChar.Kept).owner
                if (owner is Owner.Text) return owner.node
                val marker = owner.node
                return afterMarkerText.getOrPut(marker) {
                    MarkupElement("#text").also { insertAfter.getOrPut(marker) { ArrayList() }.add(it) }
                }
            }
            val n = nextKeptIdx[index]
            if (n >= 0) {
                val owner = (out[n] as OutChar.Kept).owner
                if (owner is Owner.Text) return owner.node
                // 后面先碰到的是替换元素：新字要排在它前面，只能走叶首那一串。
                return null
            }
            return fallbackText
        }

        // 显式样式区间先切成「同链连续段」：一段新字落一次，位置在下面按锚点定。
        val runs = explicitRuns(out, regionOf, regions)

        /** 一段带样式的新字落地：能复用多深的祖先就复用多深，剩下的包新元素。 */
        fun placeRun(run: ExplicitRun) {
            val sb = StringBuilder(run.endExclusive - run.start)
            for (k in run.start until run.endExclusive) sb.append((out[k] as OutChar.New).ch)
            val prevText = if (run.start > 0) prevTextNode[run.start] else null
            val nextText = if (run.endExclusive < out.size) nextKeptText[run.endExclusive] else null
            val chainEls = chainWithin(leaf, prevText ?: nextText)
            var p = 0
            while (
                p < chainEls.size && p < run.chain.size &&
                InlineSpans.sigOf(chainEls[p]) == run.chain[p]
            ) {
                p++
            }
            val nodes = materializeRun(sb.toString(), run.chain.drop(p))
            if (nodes.isEmpty()) return
            when {
                // 叶首（或前面只剩一个保留的替换元素）：前者前置到叶最前；后者接在**那个元素之后**，
                // 不能一律前置——否则图片 / `<br>` 后面的新字会跑到它前面去（整段重写时图夹在中间）。
                prevText == null -> {
                    val anchor = previousAnchor(out, run.start)
                    if (anchor != null) {
                        insertAfter.getOrPut(anchor) { ArrayList() }.addAll(nodes)
                    } else {
                        prependNodes.addAll(nodes)
                    }
                }
                // 链已完全对上：接在锚点字之后（同一个文本节点里按字符位置切开）。
                p >= chainEls.size -> nodeItems.getOrPut(prevText) { ArrayList() }
                    .add(NodeItem.Elements(nodes))
                // 只能复用前 p 层：挂到第 p 层的元素之后。
                else -> insertAfter.getOrPut(chainEls[p]) { ArrayList() }.addAll(nodes)
            }
        }

        var runIndex = 0
        var i = 0
        while (i < out.size) {
            val run = runs.getOrNull(runIndex)
            if (run != null && run.start == i) {
                placeRun(run)
                runIndex++
                i = run.endExclusive
                continue
            }
            when (val o = out[i]) {
                is OutChar.Kept -> {
                    val owner = o.owner
                    if (owner is Owner.Text) {
                        nodeItems.getOrPut(owner.node) { ArrayList() }
                            .add(NodeItem.KeptRaw(owner.rawStart, owner.rawEnd))
                    }
                }
                is OutChar.New -> {
                    val textNode = nodeForNewChar(i)
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
            i++
        }
        // 贴着替换元素新开的文本节点：字符收齐了才落成节点（新字里的换行会把它切成几段），
        // 就地替换占位节点，位置仍是当初插进去的那一处。
        for ((marker, slot) in afterMarkerText) {
            val nodes = materialize(slot, nodeItems.remove(slot).orEmpty(), emptyList())
            val list = insertAfter[marker] ?: continue
            val at = list.indexOfFirst { it === slot }
            if (at < 0) continue
            list.removeAt(at)
            list.addAll(at, nodes)
        }
        synthetic?.let { s ->
            val nodes = materialize(s, nodeItems[s].orEmpty(), deleted[s].orEmpty())
            if (nodes.isNotEmpty()) prependNodes.addAll(nodes)
        }

        // 4) 重链：文本节点按字符归属重建（可能被 <br> 切成多段）、br 删/增、其余子树原样复制。
        for (br in siblingMarkers) {
            val anchor = markerAnchors[br]
            if (anchor != null) insertAfter.getOrPut(anchor) { ArrayList() }.add(br)
        }
        val droppedBrs = visibleBrNodes(owners) - keptBrNodes(out)
        val droppedImages = visibleImageNodes(owners) - keptImageNodes(out)

        fun rebuildNodes(node: MarkupElement): List<MarkupElement> {
            if (node.isText) {
                if (node !in nodeItems && node !in deleted) return listOf(MarkupElement("#text", text = node.text))
                return materialize(node, nodeItems[node].orEmpty(), deleted[node].orEmpty())
            }
            if (node.tag == "br") return if (node in droppedBrs) emptyList() else listOf(MarkupElement("br"))
            // 行内图在叶文本里是一个 U+FFFC 占位：占位被删 ⇒ 图跟着删（读者选中的就是它）。
            if (node.tag == "img") {
                return if (node in droppedImages) emptyList() else listOf(MarkupElement("img", node.attrs))
            }
            val children = ArrayList<MarkupElement>(node.children.size + 2)
            if (node === leaf) children.addAll(prependNodes)
            for (c in node.children) {
                children.addAll(rebuildNodes(c))
                insertAfter[c]?.let { children.addAll(it) }
            }
            if (node === leaf) insertAfter[node]?.let { children.addAll(it) }
            // 字被删光的行内元素整个走掉，不留空壳（规格 §4.3「元素整个消失」）。整段换字时新字
            // 落在别处，旧元素只剩空壳；`code` 这类带内边距的元素留空会渲染成一小块灰底。
            // 只剪「原来有内容、现在空了」的：书里本来就写成空的元素（没有子节点）不碰，那是原样。
            // 表格格（`td` / `th`）是结构边界：格子被改空也要留着，剪掉就是偷偷改了列数。
            if (
                node !== leaf && node.tag != "td" && node.tag != "th" &&
                node.children.isNotEmpty() && children.isEmpty()
            ) {
                return emptyList()
            }
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

    internal sealed interface Owner {
        val node: MarkupElement

        class Text(override val node: MarkupElement, val rawStart: Int, val rawEnd: Int) : Owner
        class Marker(override val node: MarkupElement) : Owner
        class Generated(override val node: MarkupElement) : Owner
    }

    private sealed interface OutChar {
        class Kept(val owner: Owner) : OutChar
        class New(val ch: Char) : OutChar
    }

    /** 带显式样式的一条修订在输出流里占的区间（[start] / [endExclusive] 是 out 下标）。 */
    private class ExplicitRegion(
        val start: Int,
        val endExclusive: Int,
        val spans: List<InlineMarkup.StyleSpan>,
    )

    /** 输出流里一段「同一条修订、同一条元素链」的新字。 */
    private class ExplicitRun(
        val start: Int,
        val endExclusive: Int,
        val chain: List<InlineMarkup.StyleSig>,
    )

    /** 把显式区间切成连续段：链一变就断（没套元素的字自成一段）。 */
    private fun explicitRuns(
        out: List<OutChar>,
        regionOf: List<Int>,
        regions: List<ExplicitRegion>,
    ): List<ExplicitRun> {
        if (regions.isEmpty()) return emptyList()
        val runs = ArrayList<ExplicitRun>()
        var start = -1
        var region = -1
        var chain: List<InlineMarkup.StyleSig> = emptyList()
        for (i in out.indices) {
            // 被绑回来的行内图占住这一格：它有自己的节点，不能并进新字那一段（新字要在它两侧断开）。
            val r = if (out[i] is OutChar.New) regionOf[i] else -1
            val c = if (r < 0) {
                emptyList()
            } else {
                val reg = regions[r]
                val off = i - reg.start
                reg.spans.filter { off >= it.start && off < it.endExclusive }.map { it.sig }
            }
            if (start >= 0 && (r != region || c != chain)) {
                runs.add(ExplicitRun(start, i, chain))
                start = -1
            }
            if (r >= 0 && start < 0) {
                start = i
                region = r
                chain = c
            }
        }
        if (start >= 0) runs.add(ExplicitRun(start, out.size, chain))
        return runs
    }

    /** 从 [node] 起到叶（不含叶）的元素链，外层在前。 */
    private fun chainWithin(leaf: MarkupElement, node: MarkupElement?): List<MarkupElement> {
        if (node == null) return emptyList()
        val out = ArrayList<MarkupElement>(4)
        var p = node.parent
        while (p != null && p !== leaf) {
            out.add(p)
            p = p.parent
        }
        out.reverse()
        return out
    }

    /**
     * 一段纯文本 + 样式区间 → 行内节点（块内新建 / 跨段重放用）。
     *
     * 相邻同链的字合成一个元素；链一变就断开成兄弟（视觉等价，不追求合并成一层）。
     */
    internal fun inlineNodes(text: String, spans: List<InlineMarkup.StyleSpan>): List<MarkupElement> {
        if (text.isEmpty()) return emptyList()
        if (spans.isEmpty()) return materializeRun(text, emptyList())
        val out = ArrayList<MarkupElement>(4)
        var i = 0
        while (i < text.length) {
            val chain = spans.filter { i >= it.start && i < it.endExclusive }.map { it.sig }
            var j = i + 1
            while (j < text.length) {
                val next = spans.filter { j >= it.start && j < it.endExclusive }.map { it.sig }
                if (next != chain) break
                j++
            }
            out.addAll(materializeRun(text.substring(i, j), chain))
            i = j
        }
        return out
    }

    /** 把一段新文字按元素链套起来：`\n` 落成 `<br>`，[wrap] 由外到内（外层在前）。 */
    private fun materializeRun(text: String, wrap: List<InlineMarkup.StyleSig>): List<MarkupElement> {
        val inner = ArrayList<MarkupElement>(2)
        var from = 0
        for (i in text.indices) {
            if (text[i] == '\n') {
                if (i > from) inner.add(MarkupElement("#text", text = text.substring(from, i)))
                inner.add(MarkupElement("br"))
                from = i + 1
            }
        }
        if (from < text.length) inner.add(MarkupElement("#text", text = text.substring(from)))
        if (inner.isEmpty()) return emptyList()
        var nodes: List<MarkupElement> = inner
        for (sig in wrap.asReversed()) nodes = listOf(MarkupElement(sig.tag, sig.attrs, nodes))
        return nodes
    }

    private sealed interface NodeItem {
        class KeptRaw(val rawStart: Int, val rawEnd: Int) : NodeItem
        class Text(val text: Char) : NodeItem
        object Newline : NodeItem

        /** 显式样式区间重建出来的整段节点（按锚点插进文本流，见 [materialize]）。 */
        class Elements(val nodes: List<MarkupElement>) : NodeItem
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

    internal fun ownersOf(
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

    /** 参与叶文本的替换元素节点（`<br>` / `<img>`：只这些才可能被删；不参与的不动）。 */
    private fun visibleMarkerNodes(owners: List<Owner>, tag: String): Set<MarkupElement> =
        owners.mapNotNullTo(HashSet()) {
            val node = (it as? Owner.Marker)?.node
            if (node?.tag == tag) node else null
        }

    private fun keptMarkerNodes(out: List<OutChar>, tag: String): Set<MarkupElement> =
        out.mapNotNullTo(HashSet()) {
            val node = ((it as? OutChar.Kept)?.owner as? Owner.Marker)?.node
            if (node?.tag == tag) node else null
        }

    /** 参与叶文本的 `<br>` 节点（只这些才可能被删；排除/块外的 br 不参与）。 */
    private fun visibleBrNodes(owners: List<Owner>): Set<MarkupElement> = visibleMarkerNodes(owners, "br")

    private fun keptBrNodes(out: List<OutChar>): Set<MarkupElement> = keptMarkerNodes(out, "br")

    /** 参与叶文本的行内 `<img>` 节点（同上）。 */
    private fun visibleImageNodes(owners: List<Owner>): Set<MarkupElement> = visibleMarkerNodes(owners, "img")

    private fun keptImageNodes(out: List<OutChar>): Set<MarkupElement> = keptMarkerNodes(out, "img")

    /**
     * 新文字里仍带着 `U+FFFC` 占位的那些位置，把原文里的行内图节点绑回去（按文档序一一对应）。
     *
     * 读者在卡片里看得见这个占位字：没删它就是「这张图留着」，删掉它才是「连图一起删」。
     * 多出来的旧图（读者删掉了那一格）不绑，随后按「没保留」处理。
     */
    private fun bindKeptImages(out: MutableList<OutChar>, owners: List<Owner>) {
        val images = ArrayList<MarkupElement>(2)
        for (o in owners) {
            val node = (o as? Owner.Marker)?.node ?: continue
            if (node.tag == "img" && images.lastOrNull() !== node) images.add(node)
        }
        if (images.isEmpty()) return
        var next = 0
        for (i in out.indices) {
            if (next >= images.size) return
            val o = out[i]
            if (o is OutChar.New && o.ch == '\uFFFC') {
                out[i] = OutChar.Kept(Owner.Marker(images[next]))
                next++
            }
        }
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
                    is NodeItem.Elements -> {
                        flush()
                        out.addAll(it.nodes)
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
                is NodeItem.Text, NodeItem.Newline, is NodeItem.Elements -> {
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
                    is NodeItem.Elements -> {
                        flush()
                        out.addAll(it.nodes)
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
