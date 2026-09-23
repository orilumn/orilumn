package orilumn.reader.engine.laying

import orilumn.reader.engine.ImageBoundsReader
import orilumn.reader.engine.css.ClearSide
import orilumn.reader.engine.css.ColorRun
import orilumn.reader.engine.css.ComputedStyle
import orilumn.reader.engine.css.FloatSide
import orilumn.reader.engine.css.FontRun
import orilumn.reader.engine.css.WhiteSpace
import orilumn.reader.engine.css.WhiteSpaceNormalize
import orilumn.reader.engine.css.cssHexToArgb
import orilumn.reader.engine.css.usedReplacedSize
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.layout.ListMarkers
import kotlin.math.roundToInt

/** Result of a box-model layout: the continuous line stream plus the box tree for background/border drawing. */
class BoxLayoutResult(
    val lines: List<FlowedLine>,
    val boxes: List<LayoutBox>,
)

/** Element-level block classification predicate: decides whether an element is laid out as a block. */
fun interface BlockClassify {
    fun isBlock(el: MarkupElement): Boolean
}

/**
 * Box-model vertical layout (pure logic, JVM unit-testable).
 *
 * Builds a [LayoutBox] tree from the markup (inline text absorbed into text blocks, block children
 * nested as container boxes), then flows it bottom-up-ordered into a continuous [FlowedLine] stream
 * applying real box geometry: vertical **margin collapsing** between sibling blocks, and
 * border/padding offsets around each block's content lines.
 *
 * The box tree doubles as the input for [BoxDrawer]: each node retains its border-box geometry and
 * carries the background/border color needed to paint it.
 */
object NormalFlowLayout {

    /**
     * Lays [root] out as a box tree + a continuous line stream.
     *
     * @param root the body tree.
     * @param styles per-node computed styles from the cascade.
     * @param breaker the text shaper (the engine-skia `SkiaParagraphBreaker` in production, a fake in tests).
     * @param widthPx content width (px) available to the root.
     * @param imageLoader optional EPUB image loader so block `<img>` replaceables can resolve real
     *   intrinsic aspect ratios instead of falling back to [DEFAULT_IMG_RATIO].
     * @param chapterHref current chapter's spine href for resolving relative `src` paths.
     */
    fun layout(
        root: MarkupElement,
        styles: Map<MarkupElement, ComputedStyle>,
        breaker: ParagraphBreaker,
        widthPx: Int,
        classify: BlockClassify,
        hidden: HiddenCheck = HIDDEN_NONE,
        imageLoader: ImageBoundsReader? = null,
        chapterHref: String = "",
        /** P3-c 生成内容查找（空即无，旧路径）。 */
        genOf: GenOf = EmptyGen,
    ): BoxLayoutResult {
        val boxes = buildBoxTree(root, styles, breaker, widthPx, left = 0, classify, hidden, imageLoader, chapterHref, genOf)
        val lines = ArrayList<FlowedLine>()
        flowBoxes(boxes, lines)
        return BoxLayoutResult(lines, boxes)
    }

    /**
     * The single shared vertical-geometry pass: flows an already-built box list (leaves + containers)
     * into a continuous line stream [out], filling each box's `contentTop`/`contentBottom` (leaves =
     * their border box, containers = their padding box). Both the full chapter ([layout]) and an
     * incremental window drive geometry through this one function, so the two paths can never drift.
     *
     * The box list must be in document order; a container's `childBoxes` must already be nested. The
     * caller shapes text before calling (fills leaf `ranges`/`lineHeights`), or supplies replaceable
     * leaves which need no shaping.
     */
    fun flowBoxes(boxes: List<LayoutBox>, out: MutableList<FlowedLine>) {
        val st = FlowState()
        for (box in boxes) emit(box, st, true, 0f, out)
    }

    /**
     * Builds the **heavy-path** [BlockClassify] from a full-cascade style map: default tag blocks, plus
     * CSS `display:block`. `useDisplay` is the same sheet gate ([StyleComputer.hasDisplayDeclaration])
     * the light path honors, so inline-only `display` (no stylesheet rule) is ignored on BOTH paths
     * and the two paths' leaf sets / `globalCharStarts` can never drift.
     */
    fun heavyClassify(styles: Map<MarkupElement, ComputedStyle>, useDisplay: Boolean): BlockClassify =
        if (useDisplay) BlockClassify { el -> defaultBlock(el) || styles[el]?.displayBlock == true }
        else DEFAULT_CLASSIFY

    /**
     * Enumerates block-level **leaf** elements in document order, mirroring exactly [buildBoxTree]'s
     * leaf definition (an element with no block-level children) — but without touching styles or widths.
     * This is the cheap structural pass that yields `globalCharStarts`/`totalBlocks` for the lazy-cascade
     * foreground (block-cut temp pages + disk-hit incremental) without ever cascading the whole chapter.
     *
     * [classify] decides block-ness; the lazy path passes a display-aware predicate only when the chapter
     * declares `display` ([StyleComputer.hasDisplayDeclaration]), else the default keeps this zero-cost.
     */
    fun enumerateBlockLeaves(
        root: MarkupElement,
        out: MutableList<MarkupElement>,
        classify: BlockClassify = DEFAULT_CLASSIFY,
        hidden: HiddenCheck = HIDDEN_NONE,
        /**
         * P1-2: caption 叶相对行的位置（`caption-side: bottom` 时行后，否则行前）。
         * 重路径喂整章样式表，轻路径喂懒级联；缺省行前（CSS 默认 top）。
         */
        captionFirst: (MarkupElement) -> Boolean = { true },
    ) {
        if (hidden.isHidden(root)) return
        if (root.tag == "table") {
            // The heavy path expands a table to its ROW leaves (not per-cell), matching buildBoxTree.
            // P1-2: caption 展开为自己的叶（递归分解，与重路径同序）。
            val model = TableGridModel.build(root)
            val cap = model.caption
            if (cap != null && !hidden.isHidden(cap) && captionFirst(root)) {
                enumerateBlockLeaves(cap, out, classify, hidden, captionFirst)
            }
            out.addAll(model.rows.map { it.el })
            if (cap != null && !hidden.isHidden(cap) && !captionFirst(root)) {
                enumerateBlockLeaves(cap, out, classify, hidden, captionFirst)
            }
            return
        }
        val blockChildren = root.children.filter { !hidden.isHidden(it) && classify.isBlock(it) }
        if (blockChildren.isEmpty()) {
            // Mirror buildBoxTree: a sole-figure block enumerates as its image, so the light path's
            // leaf set / char starts can never drift from the heavy path's unwrapped leaves.
            out.add(soleFigureImage(root, classify, hidden) ?: root)
        } else {
            // Mirror buildBoxTree: recurse block children interleaved with anonymous inline-text runs.
            for (c in flowChildren(root, classify, hidden)) enumerateBlockLeaves(c, out, classify, hidden, captionFirst)
        }
    }

    /** Mutable state threaded through the flow pass: absolute y cursor + absolute char cursor. */
    private class FlowState {
        var y: Int = 0
        var char: Int = 0
        /** P6-a: 双侧生效悬浮底边（章节流内绝对 Y）；null = 该侧无悬浮。同侧后者堆叠，异侧并存。 */
        var floatBottomL: Int? = null
        var floatBottomR: Int? = null
        /** P6-a: 双侧跨度顶边（首个注册时的 y；回填环绕用）与最后实高行底（防回填交叠文本）。 */
        var floatTopL: Int? = null
        var floatTopR: Int? = null
        var lastTextBottom: Int = 0
    }

    /**
     * Single-source global char-start cursor: for document-ordered leaf lengths, returns each leaf's
     * chapter-wide char offset by accumulating length. Both the heavy ([buildPrepareResult]) and the
     * light ([prepareLight]) prepare passes generate their `globalCharStarts` from this, so the two
     * cursor accumulations can never drift.
     */
    fun accumulateCharStarts(lengths: List<Long>): LongArray {
        val starts = LongArray(lengths.size)
        var running = 0L
        for (i in lengths.indices) { starts[i] = running; running += lengths[i] }
        return starts
    }

    /**
     * Emits the lines of one box (and, recursively, its descendants) onto [out].

     * @param first whether [box] is the first sibling of its parent list (its top margin is not collapsed).
     * @param prevBottomMargin the collapsed bottom margin of the previous sibling (0 for first).
     * @param skipOwnTop when true, [box]'s top margin was already consumed (collapsed) by an ancestor's
     *   parent↔first-child top-margin chain (CSS 2.1 §8.3.1); [box] starts flush at `st.y` and must not
     *   add its own margin.
     * @return this box's bottom margin (for the caller to collapse with the next sibling).
     */
    private fun emit(box: LayoutBox, st: FlowState, first: Boolean, prevBottomMargin: Float, out: MutableList<FlowedLine>, skipOwnTop: Boolean = false): Float {
        if (!box.isContainer) {
            val s = box.style
            // P6-a: 悬浮注册——任何 floatSide != NONE 的非表格叶（img/文本块）。
            // 零行高推进（字符槽照占，行流不断），登录双侧悬浮跨度：同侧后者堆叠、异侧并存；
            // 显式 clear 先越过指定侧（CSS 2.1 §9.5）。
            if (box.table == null && s.floatSide != FloatSide.NONE &&
                (box.replaceableHeight > 0 || box.ranges.isNotEmpty())
            ) {
                // 显式 clear 只越指定侧（lead 叶不可能带 BOTH，见 build 期规则）。
                if ((s.clearSide == ClearSide.LEFT || s.clearSide == ClearSide.BOTH)) {
                    val b = st.floatBottomL
                    if (b != null && b > st.y) st.y = b
                }
                if ((s.clearSide == ClearSide.RIGHT || s.clearSide == ClearSide.BOTH)) {
                    val b = st.floatBottomR
                    if (b != null && b > st.y) st.y = b
                }
                // 相邻同侧悬浮纵向堆叠：先越过本侧旧悬浮（异侧并存不碰）。
                val ownBottom = if (s.floatSide == FloatSide.LEFT) st.floatBottomL else st.floatBottomR
                if (ownBottom != null && ownBottom > st.y) st.y = ownBottom
                val min = when {
                    skipOwnTop -> 0f
                    first -> s.margin.top
                    else -> collapseMargins(s.margin.top, prevBottomMargin)
                }
                st.y += min.roundToInt()
                box.contentTop = st.y
                val contentY = st.y + (s.border.top + s.padding.top).roundToInt()
                val spanH = if (box.replaceableHeight > 0) box.replaceableHeight
                else box.lineHeights.sum()
                box.contentBottom = contentY + spanH +
                    (if (box.replaceableHeight > 0) 0 else (s.padding.bottom + s.border.bottom).roundToInt())
                box.firstLineIndex = out.size
                // 零高行：字符槽连续（分页不断），几何不占竖空间（环绕叶贴着排）。
                out.add(
                    FlowedLine(
                        charStart = st.char,
                        charEnd = st.char + box.textLength,
                        yTop = contentY,
                        yBottom = contentY,
                        paragraphStart = true,
                    ),
                )
                box.lastLineExclusive = out.size
                st.char += box.textLength
                val spanBottom = box.contentBottom
                if (s.floatSide == FloatSide.LEFT) {
                    if (st.floatBottomL == null) st.floatTopL = st.y
                    st.floatBottomL = maxOf(st.floatBottomL ?: spanBottom, spanBottom)
                } else {
                    if (st.floatBottomR == null) st.floatTopR = st.y
                    st.floatBottomR = maxOf(st.floatBottomR ?: spanBottom, spanBottom)
                }
                return s.margin.bottom
            }
            // P6-a: 环绕叶（floatLead != null）贴着排（显式单侧 clear 已在 build 期按剩余侧收窄，
            // 这里只执行指定侧的跳跃）；其余（窄列回退/table/img/匿名）越过重叠侧，不重叠。
            // P6-a 回填：仅当跨度顶之下无实高内容（空域，零高悬浮行除外）才回填到跨度顶，
            // 否则保持自然位置（前文占位）。回填带自身上边距（与下文 margin 消费同值，防双记）。
            var rewound = false
            if (box.floatLead != null) {
                if (s.clearSide == ClearSide.LEFT || s.clearSide == ClearSide.BOTH) {
                    val b = st.floatBottomL
                    if (b != null && b > st.y) st.y = b
                }
                if (s.clearSide == ClearSide.RIGHT || s.clearSide == ClearSide.BOTH) {
                    val b = st.floatBottomR
                    if (b != null && b > st.y) st.y = b
                }
                val marginPart = (if (skipOwnTop) 0f else if (first) s.margin.top else collapseMargins(s.margin.top, prevBottomMargin)).roundToInt()
                // P6-a 回填：环绕行自被环绕跨度顶端起排（CSS 行盒行为）。只回填被本 lead
                // 收窄的侧（由 lead 几何反推），且仅跨度顶之下为空域时。
                val lead = box.floatLead!!
                if (lead.xOffPx > 0.5f) {
                    val t = st.floatTopL
                    if (t != null && t < st.y && st.lastTextBottom <= t) {
                        st.y = t + marginPart
                        rewound = true
                    }
                }
                if (box.contentWidth - lead.widthPx - lead.xOffPx > 0.5f) {
                    val t = st.floatTopR
                    if (t != null && t < st.y && st.lastTextBottom <= t) {
                        st.y = t + marginPart
                        rewound = true
                    }
                }
            } else {
                val bl = st.floatBottomL
                if (bl != null && bl > st.y) st.y = bl
                val br = st.floatBottomR
                if (br != null && br > st.y) st.y = br
            }
            val expiredL = st.floatBottomL
            if (expiredL != null && expiredL <= st.y) {
                st.floatBottomL = null
                st.floatTopL = null
            }
            val expiredR = st.floatBottomR
            if (expiredR != null && expiredR <= st.y) {
                st.floatBottomR = null
                st.floatTopR = null
            }
            val min = if (rewound) 0f else when {
                skipOwnTop -> 0f
                first -> s.margin.top
                else -> collapseMargins(s.margin.top, prevBottomMargin)
            }
            st.y += min.roundToInt()
            // Border-box top: right after the (collapsed) margin.
            box.contentTop = st.y
            val contentY = st.y + (s.border.top + s.padding.top).roundToInt()
            box.firstLineIndex = out.size
            var y = contentY
            if (box.replaceableHeight > 0) {
                // Replaceable (img) / table-row leaf: a single un-splittable line of the pixel height.
                out.add(
                    FlowedLine(
                        charStart = st.char,
                        charEnd = st.char + box.textLength,
                        yTop = contentY,
                        yBottom = contentY + box.replaceableHeight,
                        paragraphStart = true,
                    ),
                )
                y = contentY + box.replaceableHeight
            } else {
                box.ranges.forEachIndexed { i, r ->
                    val h = box.lineHeights.getOrElse(i) { box.lineHeights.lastOrNull() ?: 1 }
                    out.add(
                        FlowedLine(
                            charStart = st.char + r.first,
                            charEnd = st.char + r.last + 1,
                            yTop = y,
                            yBottom = y + h,
                            paragraphStart = i == 0,
                        ),
                    )
                    y += h
                }
            }
            box.lastLineExclusive = out.size
            val boxBottom = y + (s.padding.bottom + s.border.bottom).roundToInt()
            box.contentBottom = boxBottom
            st.y = boxBottom
            // P6-a: 实高行底记账（回填不上越它，防与前文交叠）。
            if (boxBottom > contentY) st.lastTextBottom = maxOf(st.lastTextBottom, boxBottom)
            st.char += box.textLength
            return s.margin.bottom
        }

        // Container: its children collapse with each other; the container adds no line but, like a
        // leaf, participates in the vertical box model with its OWN margins plus its top/bottom
        // border+padding spacing. Base CSS box-model behavior the light path mirrors in
        // [consecutiveLeafAdvance]:
        //  - The container's top margin collapses with the preceding sibling (exactly like a leaf);
        //    it is consumed here so a `pre` / `note`/`blockquote` box drops to its CSS-correct offset
        //    instead of being "entered top-aligned".
        //  - CSS 2.1 §8.3.1: when the container has NO top border and NO top padding, its top margin
        //    additionally collapses with the FIRST in-flow block-level child's top margin — and that
        //    collapses transitively through every edge-free container down the first-child chain. The
        //    whole chain is consumed as ONE margin (the max, rounded once) above the outermost box, so
        //    `<h1><span class="sec-num">章 n </span>标题</h1>` with h1{margin-top:1rem} and
        //    .sec-num{margin-top:1rem} yields 1rem (not the old 1rem+1rem stack). The first child of
        //    each edge-free container is then emitted with [skipOwnTop] (flush, margin already paid).
        //  - Its top border+padding then push its first child down within the box (so the background
        //    never overlaps the block above).
        //  - Its bottom border+padding extend the flow cursor past its last child, and it returns its
        //    own bottom margin so the following sibling collapses with it.
        //  - CSS 2.1 §8.3.1 (bottom): when the container has NO bottom border and NO bottom padding,
        //    owns in-flow children, and its specified height is auto, its bottom margin additionally
        //    collapses with the LAST in-flow block-level child's bottom margin — again transitively
        //    through every edge-free container down the last-child chain ([lastChildBottomMargin]).
        //    The whole chain is consumed as ONE collapsed group by the following sibling, so
        //    `div{margin-bottom:1rem}` whose last `p{margin-bottom:2rem}` yields 2rem (not 1rem+2rem
        //    stacked between the leaves). With bottom border/padding (or a specified height) there is
        //    NO collapse: the container returns its own bottom margin and the last child's is dropped.
        //    Both behaviors are mirrored byte-for-byte by [consecutiveLeafAdvance].
        val sty = box.style
        val edgesTop = (sty.border.top + sty.padding.top).roundToInt()
        val edgesBottom = (sty.border.bottom + sty.padding.bottom).roundToInt()
        val hasChildren = box.childBoxes.isNotEmpty()
        // P6-a: 容器入口只执行显式 clear（NONE 不动：文本子女经 pending 环绕，
        // 不可环绕子女在 emit 期自越）。
        if (sty.clearSide == ClearSide.LEFT || sty.clearSide == ClearSide.BOTH) {
            val entryBL = st.floatBottomL
            if (entryBL != null && entryBL > st.y) st.y = entryBL
        }
        if (sty.clearSide == ClearSide.RIGHT || sty.clearSide == ClearSide.BOTH) {
            val entryBR = st.floatBottomR
            if (entryBR != null && entryBR > st.y) st.y = entryBR
        }
        if (skipOwnTop) {
            // Ancestor's parent↔first-child margin chain already paid this box's top margin: flush.
            box.contentTop = st.y
        } else {
            var min = if (first) sty.margin.top else collapseMargins(sty.margin.top, prevBottomMargin)
            if (edgesTop == 0 && hasChildren) {
                // Collapse the top margin with the first-child descent (edge-free chain), rounded once.
                var firstChild = box.childBoxes.first()
                while (firstChild.isContainer &&
                    (firstChild.style.border.top + firstChild.style.padding.top).roundToInt() == 0 &&
                    firstChild.childBoxes.isNotEmpty()
                ) {
                    min = collapseMargins(min, firstChild.style.margin.top)
                    firstChild = firstChild.childBoxes.first()
                }
                min = collapseMargins(min, firstChild.style.margin.top)
            }
            st.y += min.roundToInt()
            box.contentTop = st.y
        }
        if (edgesTop > 0) st.y += edgesTop
        var subFirst = true
        var subPrev = 0f
        var firstChildLine = Int.MAX_VALUE
        var lastChildLine = -1
        for (child in box.childBoxes) {
            subPrev = emit(child, st, subFirst, subPrev, out, skipOwnTop = subFirst && hasChildren && edgesTop == 0)
            if (child.firstLineIndex >= 0) firstChildLine = minOf(firstChildLine, child.firstLineIndex)
            lastChildLine = maxOf(lastChildLine, child.lastLineExclusive)
            subFirst = false
        }
        // P6-a: 收尾不包悬浮（CSS 默认浮动逃逸容器；包住只在 BFC/clearfix，本引擎无此概念）。
        // 跨度存活供后随兄弟跨容器环绕；容器背景/高度只计 in-flow 内容。
        box.contentBottom = st.y + edgesBottom
        st.y = box.contentBottom
        box.firstLineIndex = if (firstChildLine == Int.MAX_VALUE) -1 else firstChildLine
        box.lastLineExclusive = lastChildLine
        // CSS 2.1 §8.3.1 (bottom): a bottom-edge-free container whose specified height is auto
        // collapses its OWN bottom margin with the bottom margin of its last in-flow block-level
        // child — transitively down every edge-free last-child container (the exact mirror of the
        // top-side first-child chain). The single collapsed group is what the following sibling
        // consumes; in that case the child's margin does not add anything inside the container.
        // A container with bottom border/padding (or a specified height) does NOT collapse: it
        // returns its own bottom margin unchanged (the last child's bottom margin is then dropped,
        // matching the prior box-flow behavior).
        val bottomGroup = if (hasChildren) lastChildBottomMargin(box) else 0f
        return if (hasChildren && edgesBottom == 0 && sty.heightPx == null)
            collapseMargins(sty.margin.bottom, bottomGroup)
        else sty.margin.bottom
    }

    /**
     * The collapsed bottom-margin group of [box]'s last-child descent (§8.3.1 bottom side): folds
     * every edge-free container's `margin.bottom` down the last-child chain, then folds the deepest
     * box's (a leaf, or the first bottom-edge container that terminates the chain) margin in last.
     *
     * Mirrors the top-side first-child descent that the container branch performs inline — descending
     * only through containers that carry no bottom border/padding and have children, folding each of
     * those containers' margins into one group, then closing the group with the terminator's margin.
     * (Intermediate heights are deliberately not consulted — the flow pass does not honor specified
     * heights, so [consecutiveLeafAdvance]'s mirror must gate identically, and specified-height
     * containers are only excluded at the OUTER eligibility check above.)
     */
    private fun lastChildBottomMargin(box: LayoutBox): Float {
        var child = box.childBoxes.last()
        var acc = 0f
        while (child.isContainer &&
            (child.style.border.bottom + child.style.padding.bottom).roundToInt() == 0 &&
            child.childBoxes.isNotEmpty()
        ) {
            acc = collapseMargins(acc, child.style.margin.bottom)
            child = child.childBoxes.last()
        }
        return collapseMargins(acc, child.style.margin.bottom)
    }

    /**
     * Builds the box tree for [el]'s in-flow content. Returns the list of top-level boxes (for the
     * root this is a single container; a block's child elements may also yield several siblings).
     *
     * @param left the absolute left edge that the produced border-boxes start at.
     */
    private fun buildBoxTree(
        el: MarkupElement,
        styles: Map<MarkupElement, ComputedStyle>,
        breaker: ParagraphBreaker,
        widthPx: Int,
        left: Int,
        classify: BlockClassify,
        hidden: HiddenCheck = HIDDEN_NONE,
        imageLoader: ImageBoundsReader? = null,
        chapterHref: String = "",
        /** P3-c 生成内容查找（空即无，旧路径）。 */
        genOf: GenOf = EmptyGen,
        /** P6-a 待环绕透传（文档序；容器递归共享同一份）。 */
        pending: FloatPending = FloatPending(),
    ): List<LayoutBox> {
        // display:none → this element (and its whole subtree) contributes no box, no text.
        if (hidden.isHidden(el)) return emptyList()
        val style = styles[el] ?: DEFAULT_STYLE
        val blockChildren = el.children.filter { classify.isBlock(it) }
        return if (el.tag == "table") {
            // Table 不可环绕：emit 期越过跨度，后续文本必在跨度之下，待环绕清零。
            pending.dropAll()
            // Table: a container whose "children" are its resolved row leaves (each a single
            // un-splittable line of the row's height, drawn as a 2D grid). Rows, not cells, are the
            // pagination/char leaves, so a page can split the table between rows only.
            // P1-2: caption 展开为自己的盒（递归分解；`caption-side: bottom` 时沉底）。
            // 表外盒几何（指定宽/margin auto）走重轻单源 [TableGridModel.tableOuterGeometry]。
            val g = TableGridModel.tableOuterGeometry(widthPx, left, style)
            val innerW = g.contentW
            // The table's own border-box left is its children's baseline; rows start after its left edge.
            val rowLeft = g.tableLeft + (style.border.left + style.padding.left).roundToInt()
            val rows = buildTableRows(el, styles, breaker, innerW, rowLeft, classify, hidden, imageLoader, chapterHref, genOf)
            val cap = TableGridModel.build(el).caption
            val capBoxes = if (cap != null && !hidden.isHidden(cap)) {
                buildBoxTree(cap, styles, breaker, innerW, rowLeft, classify, hidden, imageLoader, chapterHref, genOf, pending)
            } else emptyList()
            val children = if (capBoxes.isNotEmpty() && captionIsBottom(el) { styles[it] }) rows + capBoxes else capBoxes + rows
            listOf(LayoutBox(el, style, g.tableLeft, g.outerW, emptyList(), 0, emptyList(), children))
        } else if (blockChildren.isEmpty() && isReplaceable(el)) {
            // Replaceable leaf (img): no text, fixed pixel height, one char slot. Never broken by a
            // shaper — the box flow emits it as a single un-splittable line.
            // P6-a: 悬浮 img 注册跨度（右对齐 x，双端绘制同吃 contentLeft）；非悬浮 img 块清零待环绕。
            val contentW = widthPx.coerceAtLeast(1)
            val breakW = innerBreakWidth(style, contentW)
            val (floatW, repH) = replacedUsedSize(el, style, breakW, imageLoader, chapterHref)
            val side = style.floatSide
            val (boxLeft, boxW) = if (side != FloatSide.NONE) {
                val w = floatW.coerceAtLeast(1)
                pending.preClear(style.clearSide)
                pending.register(side, w, repH)
                (left + maxOf(0, contentW - w)) to w
            } else {
                pending.dropAll()
                left to contentW
            }
            listOf(
                LayoutBox(
                    el, style, boxLeft, boxW,
                    ranges = emptyList(),
                    textLength = 1,
                    lineHeights = emptyList(),
                    childBoxes = emptyList(),
                    replaceableHeight = repH,
                ),
            )
        } else if (blockChildren.isEmpty() && soleFigureImage(el, classify, hidden) != null) {
            // Sole-figure block (`<p><img/></p>`, `<div><img/></div>`, span-wrapped variants): the
            // block carries no text of its own, so promote the image to a replaceable leaf instead
            // of a one-U+FFFC text leaf. A text leaf would go through the shaper/drawer as the
            // U+FFFC object-replacement glyph (a tiny dashed "obj" box) with no bitmap ever painted
            // over it — the illustration is lost. Unwrapping keeps heavy/light char accounting
            // identical (both see the img leaf, one slot) and reuses the standard replaced sizing.
            val img = soleFigureImage(el, classify, hidden)!!
            val imgStyle = styles[img] ?: style
            val contentW = widthPx.coerceAtLeast(1)
            val breakW = innerBreakWidth(imgStyle, contentW)
            // P6-a: 与 img 分支同式（悬浮注册 + 右对齐；非悬浮清零）。
            val (floatW, repH) = replacedUsedSize(img, imgStyle, breakW, imageLoader, chapterHref)
            val side = imgStyle.floatSide
            val (boxLeft, boxW) = if (side != FloatSide.NONE) {
                val w = floatW.coerceAtLeast(1)
                pending.preClear(style.floatSide.let { style.clearSide })
                pending.register(side, w, repH)
                (left + maxOf(0, contentW - w)) to w
            } else {
                pending.dropAll()
                left to contentW
            }
            listOf(
                LayoutBox(
                    img, imgStyle, boxLeft, boxW,
                    ranges = emptyList(),
                    textLength = 1,
                    lineHeights = emptyList(),
                    childBoxes = emptyList(),
                    replaceableHeight = repH,
                ),
            )
        } else if (blockChildren.isEmpty()) {
            // Leaf text block: absorb all inline/text descendants, break into lines.
            // Lines containing an inline <img> (U+FFFC slot) are raised to the image's used
            // height (CSS 2.1 §10.8: the line box must contain the tallest inline box; the
            // breaker's plain-text shaping only yields the strut height). Without this the box
            // geometry thinks the image line is one text row tall while the draw shape paints
            // the full bitmap — the "tiny illustration" bug.
            // P1-2: 塑形输入是样式化归一文本（white-space 折叠/保留/制表符展开＋叶级收尾），
            // 断行经 white-space 单源（NOWRAP/PRE 不换行）。
            // P6-a: 文本悬浮（floatSide != NONE）按悬浮宽塑形 + 右对齐 + 注册跨度，自身不环绕；
            // 其余吃 pending（首叶 lead + 后叶延续；clear 按剩余侧收窄；匿名恒空），塑形高度回记账。
            // 断行/几何/绘制三方同吃这一份，重轻双路经同一 helper 恒一致。
            val text = absorbStyled(el, styles, classify, hidden, genOf).text
            val contentW = widthPx.coerceAtLeast(1)
            val styleOf: (MarkupElement) -> ComputedStyle = { styles[it] ?: DEFAULT_STYLE }
            val runs = leafFontRuns(el, styles, classify, hidden, genOf)
            val shifts = leafBaselineShifts(el, styles, classify, hidden, genOf)
            val side = style.floatSide
            if (side != FloatSide.NONE) {
                val floatW = floatTextWidth(style, text, contentW, breaker, runs)
                val boxLeft = if (side == FloatSide.RIGHT) left + maxOf(0, contentW - floatW) else left
                val breakWf = innerBreakWidth(style, floatW)
                val brokenF = breakWrappedLines(breaker, text, style, breakWf, null, el.tag, runs, style.textIndentPx.coerceAtLeast(0f), shifts)
                val heightsF = adjustLineHeightsForInlineImages(text, brokenF, el, styles, classify, hidden, breakWf, imageLoader, chapterHref)
                    .map { it.coerceAtLeast(1) }
                // P6-b: 叠排注音行增高（与行内图抬升同式；无注音零回归）。
                val grownF = adjustLineHeightsForRuby(brokenF, heightsF, leafRubyRuns(el, styles, classify, hidden, genOf))
                val h = grownF.sum()
                pending.preClear(style.clearSide)
                pending.register(side, floatW, h)
                listOf(
                    LayoutBox(
                        el, style, boxLeft, floatW,
                        ranges = brokenF.map { it.range },
                        textLength = text.length,
                        lineHeights = grownF,
                        childBoxes = emptyList(),
                        floatLead = null,
                    ),
                )
            } else {
                // 显式 clear 先剔除被清侧（emit 期 Y 跳跃后它们已在下方），再按剩余侧收窄。
                pending.preClear(style.clearSide)
                val lead = if (el.tag == "#text") null else pendingLeadFor(pending, style, contentW)
                val breakW = innerBreakWidth(style, contentW)
                val broken = breakWrappedLines(breaker, text, style, breakW, lead, el.tag, runs, style.textIndentPx.coerceAtLeast(0f), shifts)
                val lineHeights = adjustLineHeightsForInlineImages(text, broken, el, styles, classify, hidden, breakW, imageLoader, chapterHref)
                    .map { it.coerceAtLeast(1) }
                // P6-b: 叠排注音行增高（与行内图抬升同式；无注音零回归）。
                val grownHeights = adjustLineHeightsForRuby(broken, lineHeights, leafRubyRuns(el, styles, classify, hidden, genOf))
                // 回记账（含自身上边距，与 K 公式的 mTop 对称；只多不少，漂移永朝安全方向）。
                pending.consume(grownHeights.sum() + style.margin.top.coerceAtLeast(0f).roundToInt())
                listOf(
                    LayoutBox(
                        el, style, left, contentW,
                        ranges = broken.map { it.range },
                        textLength = text.length,
                        lineHeights = grownHeights,
                        childBoxes = emptyList(),
                        floatLead = lead,
                    ),
                )
            }
        } else {
            // Container: interleave its block children with synthetic anonymous text runs for its own
            // stray inline content (CSS anonymous-block substitution), so text like `第xx章`.
            // P6-a: 入口显式 clear 先剔除被清侧（NONE 不动，子女自理：文本环绕、不可环绕者自越）。
            pending.preClear(style.clearSide)
            val innerW = innerBreakWidth(style, widthPx)
            // Consume this container's own left edges: each child's border-box left = this box's
            // border-box left + its left border/padding + the child's own left margin.
            val edgesL = (style.border.left + style.padding.left).roundToInt()
            val children = flowChildren(el, classify, hidden).flatMap { ch ->
                val marginL = if (ch.tag == "#text") 0 else (styles[ch]?.margin?.left ?: 0f).roundToInt()
                val childLeft = left + edgesL + marginL
                if (ch.tag == "#text") {
                    // P6-a: 匿名 run 不环绕（v1 规则保留），但高度回记账（Y 记账不漂移）。
                    buildAnonymousTextLeaf(ch, style, breaker, innerW, childLeft).also { bs ->
                        pending.consume(bs.sumOf { it.lineHeights.sum() })
                    }
                } else buildBoxTree(ch, styles, breaker, innerW, childLeft, classify, hidden, imageLoader, chapterHref, genOf, pending)
            }
            // P6-a: 容器自身纵向边（border+padding 永不折叠；margin 会折叠，已由子女记账）回记账。
            pending.consume(
                (style.border.top + style.padding.top + style.padding.bottom + style.border.bottom).roundToInt(),
            )
            listOf(LayoutBox(el, style, left, widthPx, emptyList(), 0, emptyList(), children))
        }
    }

    /**
     * The container's flow children in document order: real block children interleaved with synthetic
     * `#text` runs for its own inline content (each a [MarkupElement] so both the heavy and light paths
     * share one representation). Whitespace-only runs are dropped, matching prior behavior for stray
     * whitespace between block children, so pagination/char counts don't shift for typical chapters.
     */
    private fun flowChildren(el: MarkupElement, classify: BlockClassify, hidden: HiddenCheck = HIDDEN_NONE): List<MarkupElement> {
        val out = ArrayList<MarkupElement>()
        val sb = StringBuilder()
        fun flush() {
            if (sb.isNotBlank()) out.add(MarkupElement("#text", text = sb.toString()).also { it.parent = el })
            sb.setLength(0)
        }
        for (c in el.children) {
            if (hidden.isHidden(c)) continue // display:none child contributes nothing
            if (classify.isBlock(c)) { flush(); out.add(c) }
            // An inline-level replaceable (img) among block siblings becomes its own leaf, exactly like
            // a block-level one: <p>前</p><img/><p>后</p> would otherwise be silently dropped here — a
            // container is not a text leaf, so absorbedText's U+FFFC path never runs for it.
            else if (isReplaceable(c)) { flush(); out.add(c) }
            else appendInlineText(c, classify, sb, hidden)
        }
        flush()
        return out
    }

    /** Appends [el]'s inline/text subtree text to [sb] (non-block only; `<br>` becomes a newline). */
    private fun appendInlineText(el: MarkupElement, classify: BlockClassify, sb: StringBuilder, hidden: HiddenCheck = HIDDEN_NONE) {
        when {
            hidden.isHidden(el) -> Unit // display:none inline run contributes no text
            el.isText -> sb.append(el.text)
            el.tag == "br" -> sb.append('\n')
            classify.isBlock(el) -> Unit
            else -> for (c in el.children) appendInlineText(c, classify, sb, hidden)
        }
    }

    /** An anonymous text leaf box for a stray inline run, styled like its container. */
    private fun buildAnonymousTextLeaf(
        textEl: MarkupElement,
        style: ComputedStyle,
        breaker: ParagraphBreaker,
        widthPx: Int,
        left: Int,
    ): List<LayoutBox> {
        val contentW = widthPx.coerceAtLeast(1)
        val breakW = innerBreakWidth(style, contentW)
        // P1-2: 匿名 run 整段按容器 white-space 一次归一＋收尾（与轻路径计数同式）。
        val text = normalizeAnonymousRun(textEl.text, style.whiteSpace)
        // 匿名 stray 叶按构造就是单 face 叶（不在级联表里、无行内子树），无行内字体段。
        val broken = breakLeafLines(breaker, text, style, breakW, textEl.parent?.tag ?: textEl.tag, emptyList(), 0f)
        return listOf(
            LayoutBox(
                el = textEl, style = style, contentLeft = left, contentWidth = contentW,
                ranges = broken.map { it.range }, textLength = text.length,
                lineHeights = broken.map { it.heightPx.coerceAtLeast(1) },
                childBoxes = emptyList(),
            ),
        )
    }

    /**
     * Resolves a `table` into its row leaves. Column widths come from `table-layout`
     * (fixed = equal split, auto = content measure); each cell's text is shaped
     * (via [breaker]) within its column width to determine the row height; a row leaf emits one
     * un-splittable line of that height and carries a [TableRowLayout] for the 2D grid drawing.
     */
    private fun buildTableRows(
        el: MarkupElement,
        styles: Map<MarkupElement, ComputedStyle>,
        breaker: ParagraphBreaker,
        widthPx: Int,
        left: Int,
        classify: BlockClassify,
        hidden: HiddenCheck = HIDDEN_NONE,
        imageLoader: ImageBoundsReader? = null,
        chapterHref: String = "",
        /** P3-c 生成内容查找（空即无，旧路径）。 */
        genOf: GenOf = EmptyGen,
    ): List<LayoutBox> {
        val model = TableGridModel.build(el)
        if (model.columnCount <= 0 || model.rows.isEmpty()) return emptyList()
        // P1-2: 表格族几何——`border-collapse: collapse` 强制间距 0；separate 模型下
        // 列间＋两侧各留 `border-spacing`（`cellspacing` 属性经级联已映射到该字段）；
        // 每行高含底部 `border-spacing` 纵向间隔（表顶外间隔为已知近似，未单列）。
        // `table-layout: fixed` 均分，`auto`（默认）按单元格内容测宽分列。
        val tableStyle = styles[el] ?: DEFAULT_STYLE
        val collapse = tableStyle.borderCollapse
        val spH = if (collapse) 0f else tableStyle.borderSpacingH
        val spV = if (collapse) 0 else tableStyle.borderSpacingV.roundToInt()
        val hideEmpty = tableStyle.emptyCellsHide
        val tableContentW = widthPx.coerceAtLeast(1)
        val gapH = spH.coerceAtLeast(0f).roundToInt()
        // 第一遍：吸收各单元格归一文本＋内容需求（塑形与分列共用一次吸收，runs 同样只收集一次）。
        class CellShape(val cell: TableGridModel.Cell, val style: ComputedStyle, val text: String, val runs: List<FontRun>)
        val rowCells = ArrayList<List<CellShape>>(model.rows.size)
        val prefs = ArrayList<TableGridModel.CellPref>()
        for (row in model.rows) {
            val list = ArrayList<CellShape>(row.cells.size)
            for (cell in row.cells) {
                val cs = styles[cell.el] ?: DEFAULT_STYLE
                val t = absorbStyled(cell.el, styles, classify, hidden, genOf).text
                // P1-2: 行内 face 段只收集一次，分列度量与断行塑形同喂（重/轻两路同源）。
                val runs = collectFontRuns(cell.el, styles, hidden::isHidden, classify::isBlock, fontBaseOf(cell.el, styles, cs), genOf = genOf)
                list.add(CellShape(cell, cs, t, runs))
                prefs.add(tableCellPref(breaker, cell.col, cell.colSpan, t, cs, cell.el.tag, runs))
            }
            rowCells.add(list)
        }
        val (colXs, colWs) = if (tableStyle.tableLayoutFixed) {
            TableGridModel.columnLayout(tableContentW, model.columnCount, spH, left)
        } else {
            // 指定表宽拉伸下限：表有指定宽时列填满表内容宽，否则保持三段式不拉伸。
            val tSpecified = styles[el]?.let { it.widthPct != null || it.widthPx != null } ?: false
            TableGridModel.autoColumnLayout(
                tableContentW, model.columnCount, spH, left, prefs,
                minTableW = if (tSpecified) tableContentW else 0,
            )
        }
        fun cellOuter(col: Int, colSpan: Int): Int {
            var w = 0
            for (i in col until col + colSpan) w += colWs.getOrElse(i) { 0 }
            return (w + (colSpan - 1) * gapH).coerceAtLeast(1)
        }
        val out = ArrayList<LayoutBox>(model.rows.size)
        // 行高必须含跨行分摊（[TableGridModel.resolveRowHeights]），故分两遍：先塑形各格并记录
        // 其高度贡献，解析出各行高后再发射行盒。跨行格不再把整格高压进「首行」。
        val cellsPerRow = ArrayList<List<TableCellLayout>>(model.rows.size)
        val heightSpecs = ArrayList<List<TableGridModel.CellHeight>>(model.rows.size)
        val rowCharLens = IntArray(model.rows.size)
        for ((ri, row) in model.rows.withIndex()) {
            val columnXs = colXs
            val cells = ArrayList<TableCellLayout>(row.cells.size)
            val specs = ArrayList<TableGridModel.CellHeight>(row.cells.size)
            var rowChars = 0
            for (entry in rowCells[ri]) {
                val cell = entry.cell
                val cellStyle = entry.style
                // 跨列单元格盖住中间间隔。
                val cellOuter = cellOuter(cell.col, cell.colSpan)
                val cellBreak = innerBreakWidth(cellStyle, cellOuter)
                // P1-2: 单元格文本同叶归一；行字符长度 = 各单元格归一长度之和
                //（与轻路径 styledCharAdvance(tr) 同式）。
                val cellText = entry.text
                rowChars += cellText.length
                val lines = breakLeafLines(
                    breaker, cellText, cellStyle, cellBreak, cell.el.tag,
                    entry.runs,
                    0f,
                    leafBaselineShifts(cell.el, styles, classify, hidden, genOf),
                )
                // Same §10.8 image-line adjustment as text leaves (cell images share the bug).
                val adjHeights = adjustLineHeightsForInlineImages(cellText, lines, cell.el, styles, classify, hidden, cellBreak, imageLoader, chapterHref)
                // P6-b: 叠排注音行增高（与文本叶同式；无注音零回归）。
                val grownHeights = adjustLineHeightsForRuby(lines, adjHeights, leafRubyRuns(cell.el, styles, classify, hidden, genOf))
                val cellH = (grownHeights.sum().coerceAtLeast(1) +
                    (cellStyle.padding.vertical + cellStyle.border.vertical).roundToInt()).coerceAtLeast(1)
                cells.add(TableCellLayout(cell.el, cell.col, cell.colSpan, columnXs.getOrElse(cell.col) { left }, cellOuter, cellH, cell.isHeader, null, cell.rowSpan))
                specs.add(TableGridModel.CellHeight(cell.rowSpan, cellH))
            }
            cellsPerRow.add(cells)
            heightSpecs.add(specs)
            rowCharLens[ri] = rowChars
        }
        val resolvedRowHs = TableGridModel.resolveRowHeights(heightSpecs)
        for ((ri, row) in model.rows.withIndex()) {
            val rowStyle = styles[row.el] ?: DEFAULT_STYLE
            // P1-2: 行字符长度 = 各单元格归一文本长度之和（轻路径 styledCharAdvance(tr) 同式；
            // 旧 `row.el.textLength` 为原始长度，空白归一后不再一致）。
            out.add(
                LayoutBox(
                    el = row.el, style = rowStyle, contentLeft = left, contentWidth = tableContentW,
                    ranges = emptyList(), textLength = rowCharLens[ri],
                    lineHeights = emptyList(), childBoxes = emptyList(),
                    replaceableHeight = (resolvedRowHs[ri] + spV).coerceAtLeast(1),
                    table = TableRowLayout(colXs, colWs, cellsPerRow[ri], hideEmpty),
                ),
            )
        }
        return out
    }

    /** Concatenates all inline/text descendant text, ignoring nested blocks (they are separate boxes).
     *  Inline `<img>` (replacement element) contributes a U+FFFC OBJECT REPLACEMENT CHARACTER so it has
     *  a text position the shaper can later adorn with an ImageSpan. */
    private fun absorbedText(el: MarkupElement, classify: BlockClassify, hidden: HiddenCheck = HIDDEN_NONE): String {
        val sb = StringBuilder()
        for (c in el.children) {
            when {
                hidden.isHidden(c) -> Unit // display:none subtree contributes no text
                c.isText -> sb.append(c.text)
                c.tag == "br" -> sb.append('\n')
                classify.isBlock(c) -> Unit // handled as a nested box, not text
                isReplaceable(c) -> sb.append('\uFFFC') // object replacement char for ImageSpan
                else -> sb.append(absorbedText(c, classify, hidden))
            }
        }
        return sb.toString()
    }

    /**
     * P1-2: 样式化叶文本吸收（[styledSegments] 单源）：每个文本节点按其继承
     * `white-space` 归一，叶级按叶块样式收尾。与塑形输入、run 收集、轻路径计数同构。
     */
    fun absorbStyled(
        el: MarkupElement,
        styles: Map<MarkupElement, ComputedStyle>,
        classify: BlockClassify,
        hidden: HiddenCheck = HIDDEN_NONE,
        /** P3-c 生成内容查找（空即无，旧路径）。 */
        genOf: GenOf = EmptyGen,
    ): StyledSegments {
        val wsOf: (MarkupElement) -> WhiteSpace = { wsOfNode(it, styles, el) }
        val leafWs = styles[el]?.whiteSpace ?: WhiteSpace.NORMAL
        return StyledSegments(styledSegments(el, wsOf, hidden::isHidden, classify::isBlock, leafWs, genOf, { styles[it] }).segments)
    }

    /**
     * P1-2: 样式化字符步长（轻路径 `globalCharStarts` 单源）：与重路径盒 `textLength`
     * 同一分解——替换叶 1 槽、独图块 1 槽、表格按单元格归一求和、文本叶吸收归一、
     * 容器按 `flowChildren` 结构递归（匿名 run 整段按容器样式归一）。
     *
     * @param styleOf 任一元素的计算样式（重路径喂整章表，轻路径喂懒级联）。
     */
    fun styledCharAdvance(
        el: MarkupElement,
        styleOf: (MarkupElement) -> ComputedStyle,
        classify: BlockClassify = DEFAULT_CLASSIFY,
        hidden: HiddenCheck = HIDDEN_NONE,
        /** P3-c 生成内容查找（空即无；容器 stray 分支同旧式跳过，重轻同跳）。 */
        genOf: GenOf = EmptyGen,
    ): Long {
        if (hidden.isHidden(el)) return 0
        if (isReplaceable(el)) return 1
        if (el.tag == "table") {
            val model = TableGridModel.build(el)
            var n = 0L
            for (row in model.rows) {
                for (cell in row.cells) {
                    n += styledCharAdvance(cell.el, styleOf, classify, hidden, genOf)
                }
            }
            // P1-2: caption 字符计入表内（顺序无关求和；叶序由 enumerate 保证）。
            val cap = model.caption
            if (cap != null) n += styledCharAdvance(cap, styleOf, classify, hidden, genOf)
            return n
        }
        if (soleFigureImage(el, classify, hidden) != null) return 1
        val blockChildren = el.children.filter { !hidden.isHidden(it) && classify.isBlock(it) }
        if (blockChildren.isEmpty()) {
            if (el.isText) return normalizeAnonymousRun(el.text, styleOf(el.parent ?: el).whiteSpace).length.toLong()
            val wsOf: (MarkupElement) -> WhiteSpace = { n -> styleOf(n).whiteSpace }
            val leafWs = styleOf(el).whiteSpace
            return styledSegments(el, wsOf, hidden::isHidden, classify::isBlock, leafWs, genOf, styleOf).text.length.toLong()
        }
        // 容器：与 flowChildren 同结构（raw 判空），匿名 run 按容器样式整段归一。
        var n = 0L
        val sb = StringBuilder()
        fun flush() {
            if (sb.isNotBlank()) {
                n += normalizeAnonymousRun(sb.toString(), styleOf(el).whiteSpace).length
            }
            sb.setLength(0)
        }
        for (c in el.children) {
            if (hidden.isHidden(c)) continue
            if (classify.isBlock(c)) {
                flush()
                n += styledCharAdvance(c, styleOf, classify, hidden)
            } else if (isReplaceable(c)) {
                flush()
                n += 1
            } else {
                appendInlineText(c, classify, sb, hidden)
            }
        }
        flush()
        return n
    }

    /**
     * S32 — public leaf-text projection: the exact string [buildBoxTree] fed the shaper for a text
     * leaf ([LayoutBox.ranges] index into it). Synthetic anonymous `#text` leaves carry their
     * container-normalized run ([buildAnonymousTextLeaf]); every other text leaf's input is
     * [absorbStyled] (P1-2 归一化).
     * Non-text leaves (table rows / replaceables) return "" — they own no shaped text.
     *
     * Single-source: delegates to the same styled absorption the layout used, so external DrawLine
     * bridges (desktop shell) can never drift from the breaker's input. Callers must pass the SAME
     * [styles]/[classify]/[hidden] the layout was built with.
     */
    fun leafText(
        el: MarkupElement?,
        styles: Map<MarkupElement, ComputedStyle>,
        classify: BlockClassify,
        hidden: HiddenCheck = HIDDEN_NONE,
        /** P3-c 生成内容查找（空即无，旧路径）。 */
        genOf: GenOf = EmptyGen,
    ): String {
        if (el == null) return ""
        if (el.isText) {
            val ws = styles[el]?.whiteSpace ?: el.parent?.let { styles[it]?.whiteSpace } ?: WhiteSpace.NORMAL
            return normalizeAnonymousRun(el.text, ws)
        }
        if (el.tag == "table" || isReplaceable(el)) return ""
        return absorbStyled(el, styles, classify, hidden, genOf).text
    }

    /**
     * S32 伴生：[leafText] 同一字符串上的显式着色区间（[ColorRun]，叶子全文本坐标系）。
     *
     * 引擎单源委托 [collectColorRuns]（与 [absorbedText] 同一套跳过规则）；无着色叶回空表
     * （绘制零开销、行为与此前逐字同墨完全一致）；解析失败的颜色按无色处理，永不崩版式。
     * 调用方必须传与塑形同一套 [styles]/[classify]/[hidden]。
     */
    fun leafColorRuns(
        el: MarkupElement?,
        styles: Map<MarkupElement, ComputedStyle>,
        classify: BlockClassify,
        hidden: HiddenCheck = HIDDEN_NONE,
        /** P3-c 生成内容查找（空即无，旧路径）。 */
        genOf: GenOf = EmptyGen,
    ): List<ColorRun> {
        if (el == null) return emptyList()
        val seed = styles[el]?.colorHex?.let(::cssHexToArgb)
        val rootWs = styles[el]?.whiteSpace ?: el.parent?.let { styles[it]?.whiteSpace } ?: WhiteSpace.NORMAL
        return collectColorRuns(el, styles, hidden::isHidden, classify::isBlock, seed, rootWs, genOf)
    }

    /**
     * S32 伴生：[leafText] 同一字符串上的行内**字体**段（[FontRun]，叶子全文本坐标系）。
     *
     * 引擎单源委托 [collectFontRuns]（与 [absorbedText] 同一套跳过规则）；face/字号与叶基底相同时
     * 不产生 run（测度/绘制零开销、与整叶单 face 完全一致）。调用方必须传与塑形同一套
     * [styles]/[classify]/[hidden]。
     */
    fun leafFontRuns(
        el: MarkupElement?,
        styles: Map<MarkupElement, ComputedStyle>,
        classify: BlockClassify,
        hidden: HiddenCheck = HIDDEN_NONE,
        /** P3-c 生成内容查找（空即无，旧路径）。 */
        genOf: GenOf = EmptyGen,
    ): List<FontRun> {
        if (el == null) return emptyList()
        val rootWs = styles[el]?.whiteSpace ?: el.parent?.let { styles[it]?.whiteSpace } ?: WhiteSpace.NORMAL
        return collectFontRuns(el, styles, hidden::isHidden, classify::isBlock, fontBaseOf(el, styles, styles[el]), rootWs, genOf)
    }

    /**
     * P1-2 伴生：[leafText] 同一字符串上的基线偏移区间（[BaselineShift]，叶子全文本坐标系）。
     * 只发非基线段；纯基线叶回空表。调用方必须传与塑形同一套 [styles]/[classify]/[hidden]。
     */
    fun leafBaselineShifts(
        el: MarkupElement?,
        styles: Map<MarkupElement, ComputedStyle>,
        classify: BlockClassify,
        hidden: HiddenCheck = HIDDEN_NONE,
        /** P3-c 生成内容查找（空即无，旧路径）。 */
        genOf: GenOf = EmptyGen,
    ): List<BaselineShift> {
        if (el == null) return emptyList()
        val rootWs = styles[el]?.whiteSpace ?: el.parent?.let { styles[it]?.whiteSpace } ?: WhiteSpace.NORMAL
        return collectBaselineShifts(el, styles, hidden::isHidden, classify::isBlock, rootWs, genOf)
    }

    /**
     * P6-b 伴生：[leafText] 同一字符串上的叠排注音区间（[RubyRun]，叶子全文本坐标系）。
     * 无注音叶回空表（行高/断行零回归）。调用方必须传与塑形同一套 [styles]/[classify]/[hidden]。
     */
    fun leafRubyRuns(
        el: MarkupElement?,
        styles: Map<MarkupElement, ComputedStyle>,
        classify: BlockClassify,
        hidden: HiddenCheck = HIDDEN_NONE,
        /** P3-c 生成内容查找（空即无，旧路径）。 */
        genOf: GenOf = EmptyGen,
    ): List<RubyRun> {
        if (el == null) return emptyList()
        val rootWs = styles[el]?.whiteSpace ?: el.parent?.let { styles[it]?.whiteSpace } ?: WhiteSpace.NORMAL
        return collectRubyRuns(el, styles, hidden::isHidden, classify::isBlock, rootWs, genOf)
    }

    /**
     * 下划线伴生：[leafText] 同一字符串上的下划线区间（[UnderlineRun]，叶子全文本坐标系）。
     * 无下划线叶回空表（绘制零回归）。调用方必须传与塑形同一套 [styles]/[classify]/[hidden]。
     */
    fun leafUnderlineRuns(
        el: MarkupElement?,
        styles: Map<MarkupElement, ComputedStyle>,
        classify: BlockClassify,
        hidden: HiddenCheck = HIDDEN_NONE,
        /** P3-c 生成内容查找（空即无，旧路径）。 */
        genOf: GenOf = EmptyGen,
    ): List<UnderlineRun> {
        if (el == null) return emptyList()
        val rootWs = styles[el]?.whiteSpace ?: el.parent?.let { styles[it]?.whiteSpace } ?: WhiteSpace.NORMAL
        return collectUnderlineRuns(el, styles, hidden::isHidden, classify::isBlock, rootWs, genOf)
    }

    /** 叶基底 face（行内 run 比对基准）：匿名叶不在表里时回传入后备，无表时回空基底。 */
    fun fontBaseOf(el: MarkupElement, styles: Map<MarkupElement, ComputedStyle>, fallback: ComputedStyle?): FontRun {
        val s = fallback ?: styles[el]
        return s?.let {
            FontRun(0, 0, it.fontFamilies, if (el.isText) el.parent?.tag else el.tag, it.fontWeight, it.italic, it.monospace, it.fontSizePx)
        } ?: FontRun(0, 0, emptyList(), null, 0, false, false)
    }

    /**
     * P1-2: caption 是否沉底（`caption-side: bottom`）。表样式优先，其次 caption 自身；
     * 缺省置顶（CSS 默认）。重/轻两路各喂自己的样式源（整章表/懒级联），顺序恒一致。
     */
    fun captionIsBottom(tableEl: MarkupElement, styleOf: (MarkupElement) -> ComputedStyle?): Boolean {
        if (styleOf(tableEl)?.captionSideBottom == true) return true
        val cap = TableGridModel.build(tableEl).caption
        return cap?.let { styleOf(it)?.captionSideBottom } == true
    }

    /**
     * Canonical, **single-source** width formula: the line-break width a block gets inside its
     * border-box width, i.e. that border-box width minus its own horizontal padding/border. Both the
     * canonical ([buildBoxTree], top-down) and the light lazy path ([descendContentWidth] feeding the
     * light shaper) route through this, so the folded-line width can never drift between the two.
     */
    fun innerBreakWidth(style: ComputedStyle, borderBoxW: Int): Int =
        innerFromEdges((style.padding.horizontal + style.border.horizontal).roundToInt(), borderBoxW)

    /** Same as [innerBreakWidth] but for an already-computed horizontal-edge value; the single integer
     *  rule both width paths share. */
    private fun innerFromEdges(edges: Int, borderBoxW: Int): Int =
        (borderBoxW - edges).coerceAtLeast(1)

    /**
     * The vertical background/border band a box covers for drawing, given the aggregated vertical extent
     * of its text lines and whether the box is itself a background-bearing **leaf** (self-owned).
     *
     * Mirrors [emit]'s leaf geometry exactly so both the heavy and light paths paint one, correct band:
     *  - A **self-owned** leaf (e.g. `pre`, `p.filename` carrying its own background): its aggregated
     *    lines already span the leaf's own top+bottom border/padding (counted once in [build box]),
     *    so the band IS that extent — extending it again by the same edges is the classic double-count
     *    that pushed a `pre` band 18px above its border-box top (overlapping the block above / `.filename`).
     *  - A **container** owner (e.g. `blockquote`, `.rust-example-rendered`): its text lines span only
     *    the content area, so the band must additionally extend up by the container's top border+padding
     *    and down by its bottom border+padding so the padding visually carries the fill.
     *
     * Always returns the band as `(top, bottom)` with `top < bottom`.
     */
    fun backgroundBandExtent(
        ownerTop: Int,
        ownerBottom: Int,
        ownerEdgesTop: Int,
        ownerEdgesBottom: Int,
        selfOwned: Boolean,
    ): Pair<Int, Int> =
        if (selfOwned) (ownerTop to ownerBottom)
        else (ownerTop - ownerEdgesTop) to (ownerBottom + ownerEdgesBottom)

    /**
     * The border-box (content) width [el] receives during box layout: the reader width [rootW] reduced
     * by the horizontal padding+border of every ancestor container down to (but not including) [el].
     * This is byte-identical to the recursive descent [buildBoxTree] performs top-down, and is what the
     * light (lazy-cascade) path uses instead of re-deriving it locally. [edgesOf] supplies each
     * element's horizontal padding+border in px (heavy passes the cascade map; light passes its lazy
     * style resolver).
     */
    fun descendContentWidth(el: MarkupElement, rootW: Int, edgesOf: (MarkupElement) -> Int): Int {
        val p = el.parent ?: return rootW
        return innerFromEdges(edgesOf(p), descendContentWidth(p, rootW, edgesOf))
    }

    /**
     * The border-box left x [el] receives in block flow (0-based content coordinates): the accumulated
     * left border+padding of every ancestor container plus each node's own left margin — byte-identical
     * to the recursive descent `buildBoxTree` performs top-down (`childLeft = left + edgesL + marginL`).
     * This is what the light (lazy-cascade) path uses instead of re-deriving it locally, so heavy and
     * light agree on every leaf's horizontal position. rootLeft is the top-level starting x (0).
     *
     * A `#text` leaf has no box of its own: its margin-left is 0 (matching the heavy path).
     */
    fun descendContentLeft(
        el: MarkupElement,
        rootLeft: Int,
        leftEdgesOf: (MarkupElement) -> Int,
        marginLeftOf: (MarkupElement) -> Int,
    ): Int {
        val p = el.parent ?: return rootLeft
        val parentLeft = descendContentLeft(p, rootLeft, leftEdgesOf, marginLeftOf)
        val ownMargin = if (el.tag == "#text") 0 else marginLeftOf(el)
        return parentLeft + leftEdgesOf(p) + ownMargin
    }

    /**
     * The vertical advance from [prevLeaf]'s content-bottom to [nextLeaf]'s content-top, where the two
     * are **consecutive** in document order. Reproduces exactly what [emit] produces for the gap
     * between those two leaves: the sibling margin collapse at the lowest common ancestor, plus the
     * CSS box-model spacing every container on each leaf's path contributes (containers, like leaves,
     * consume their own collapsed top margin and introduce their own top/bottom border+padding). The
     * light (lazy-cascade) path therefore spaces page blocks byte-for-byte as the canonical flow does.
     *
     * CSS 2.1 §8.3.1's parent↔first-child top collapse is folded in on [nextLeaf]'s side (mirror of
     * [emit]'s edge-free chain merge), and the parent↔last-child BOTTOM collapse on [prevLeaf]'s side
     * (mirror of the container branch's [lastChildBottomMargin] fold). With `LCA` the deepest common
     * ancestor, `B_n = pathB[ib-1]` the LCA's child on [nextLeaf]'s side and `A_m = pathA[ia-1]` the
     * LCA's child on [prevLeaf]'s side:
     *
     * ```
     * gap = collapse-chain reached by folding {A_m's collapsed bottom margin, B_n.margin.top, and every
     *       intermediate container's margin.top down the first-child chain, up to the first box that
     *       has its own top border+padding or the leaf}  → rounded ONCE at that box (each such top-edge
     *       container rounds the chain above it then starts a fresh group below)
     *      + every top-edge container's top border+padding along B_n's descent
     *      + Σ bottomEdges(A_k)  // k = 1..m — every container above prevLeaf extends the flow
     *                             // past prevLeaf by its bottom border+padding
     * ```
     * A_m's collapsed bottom margin is [emit]'s return value for A_m: the raw margin when A_m is a leaf,
     * a bottom-edge-full container or a specified-height container; otherwise the parent↔last-child
     * collapse group (A_m's margin folded together with every edge-free container's margin down A_m's
     * last-child chain, closed by the terminator's own margin).
     * When [nextLeaf] is the LCA's direct child (`B_n === nextLeaf`), this reduces to
     * `collapse(nextLeaf.margin.top, m1)` (rounded once), i.e. plain sibling collapse — the chain has
     * no intermediate containers to merge.
     */
    fun consecutiveLeafAdvance(
        prevLeaf: MarkupElement,
        nextLeaf: MarkupElement,
        styleOf: (MarkupElement) -> ComputedStyle,
    ): Int {
        val pathA = ancestorChain(prevLeaf)
        val pathB = ancestorChain(nextLeaf)
        var ia = pathA.size - 1
        var ib = pathB.size - 1
        while (ia >= 0 && ib >= 0 && pathA[ia] === pathB[ib]) { ia--; ib-- }
        ia++; ib++ // LCA = pathA[ia] == pathB[ib]; its child on prev's side = pathA[ia-1], on next's = pathB[ib-1].
        var m1 = styleOf(pathA[ia - 1]).margin.bottom
        // CSS 2.1 §8.3.1's parent↔last-child BOTTOM collapse (mirror of [emit]'s container branch):
        // A_m's contribution is not its raw margin-bottom when A_m is a bottom-edge-free, auto-height
        // container - then its margin collapses with its last-child chain ([lastChildBottomMargin]).
        // prevLeaf is A_m's last in-flow leaf, so folding A_m's own branch downward folds exactly the
        // margins of the `.last()`-child descent: each edge-free ancestor's margin joins the group, and
        // an edge-full container stops the fold with its own margin folding in last (its children's
        // margins are dropped). Skipped entirely when A_m carries bottom border/padding or a specified
        // height (emit then returns its raw margin-bottom).
        val aStyle = styleOf(pathA[ia - 1])
        if ((aStyle.border.bottom + aStyle.padding.bottom).roundToInt() == 0 && aStyle.heightPx == null) {
            var j = ia - 2
            while (j >= 0) {
                val parentStyle = styleOf(pathA[j + 1])
                if ((parentStyle.border.bottom + parentStyle.padding.bottom).roundToInt() > 0) break
                m1 = collapseMargins(m1, styleOf(pathA[j]).margin.bottom)
                j--
            }
        }
        // Round exactly where [emit] rounds: emit consumes a collapsed margin with a single
        // `.roundToInt()` (`st.y += min.roundToInt()`), then each border/padding pair as its own int
        // (`(border + padding).roundToInt()`). Summing floats and rounding once here instead would
        // drift ±1px from the canonical flow per fractional-margin pair — this is what makes the
        // incremental/temp geometry disagree with the canonical pagination that wrote the disk tables
        // (cumulative page under-fill / slight overflow).
        //
        // CSS 2.1 §8.3.1 mirrors [emit]'s parent↔first-child top collapse: the boundary between the two
        // leaf subtrees is ONE collapsing margin group that reaches from A_m's bottom margin down B_n's
        // first-child chain through every edge-free container to the first box that carries its own top
        // border/padding (or the leaf). It is rounded ONCE where that box consumes the chain; any such
        // top edge then breaks the chain and the subtree below it starts a fresh margin group.
        var advance = 0
        var merged = m1
        var k = ib - 1 // B_n = pathB[n], n = ib-1 (the LCA's child on nextLeaf's side)
        while (k > 0) { // the leaf (k == 0) is folded in last
            val s = styleOf(pathB[k])
            merged = collapseMargins(merged, s.margin.top)
            val topEdges = (s.border.top + s.padding.top).roundToInt()
            if (topEdges > 0) {
                advance += merged.roundToInt()
                advance += topEdges
                merged = 0f // content below this container's top edge starts a fresh margin group
            }
            k--
        }
        val leafStyle = styleOf(pathB[0])
        merged = collapseMargins(merged, leafStyle.margin.top)
        advance += merged.roundToInt()
        // Containers above prevLeaf extend the flow past it by their bottom edges.
        for (k in 1..(ia - 1)) { val s = styleOf(pathA[k]); advance += (s.border.bottom + s.padding.bottom).roundToInt() }
        return advance
    }

    /** [el] and its ancestors, root-last (used by [consecutiveLeafAdvance]). */
    private fun ancestorChain(el: MarkupElement): List<MarkupElement> {
        val out = ArrayList<MarkupElement>()
        var n: MarkupElement? = el
        while (n != null) { out.add(n); n = n.parent }
        return out
    }

    /**
     * CSS vertical margin collapsing between two adjacent in-flow boxes' side-by-side margins: when both
     * are positive take the larger; when both negative take the more negative; when signs differ sum
     * them (the classic negative-margin result, e.g. `pre + .caption { margin-top: -0.5rem }` collapsing
     * a code block's +0.5rem bottom margin to 0). Used where sibling totals collapse in [emit] and on the
     * light path's [consecutiveLeafAdvance].
     */
    private fun collapseMargins(a: Float, b: Float): Float = when {
        a >= 0f && b >= 0f -> maxOf(a, b)
        a < 0f && b < 0f -> minOf(a, b)
        else -> a + b
    }

    /**
     * Default block classification: an element is a block iff its tag is a known default block tag.
     * `<img>` is NOT a default block — CSS defaults to inline-block (inline-level replaceable), so it
     * lives inside its container's text flow. Only explicit `display:block` on the img promotes it to
     * a block leaf.
     */
    fun defaultBlock(el: MarkupElement): Boolean = el.tag in BOX_BLOCK_TAGS

    /** The default [BlockClassify] (tag-only); used in every hot path where the chapter declares no `display`. */
    val DEFAULT_CLASSIFY: BlockClassify = BlockClassify { el -> defaultBlock(el) }

    /**
     * Whether [el] is a **replaceable element** (img) — regardless of block/inline semantics. Used for
     * char-accounting: both block-level and inline-level img occupy exactly one char slot in the global
     * char stream so pagination stays consistent. Box-tree construction further consults the block
     * classifier to decide whether the replaceable becomes its own leaf or lives inside a text block.
     */
    fun isReplaceable(el: MarkupElement): Boolean = el.tag == "img"

    /**
     * The **visible** character advance of a light-path leaf: 1 for a replaceable (img), else the leaf's
     * subtree text length with `display:none` content excluded — byte-identical to the heavy path's box
     * `textLength` (which is `absorbedText(...).length` with the same hidden check). Both paths derive
     * their `globalCharStarts` from this so display:none is excluded identically on both.
     */
    fun visibleCharAdvance(el: MarkupElement, hidden: HiddenCheck = HIDDEN_NONE): Long {
        if (isReplaceable(el)) return 1L
        var n = 0L
        fun walk(node: MarkupElement) {
            if (hidden.isHidden(node)) return
            if (node.isText) { n += node.text.length; return }
            if (node.tag == "br") { n++; return }
            // A nested inline <img> occupies the same single U+FFFC slot absorbedText gives it,
            // so the light path's advance stays byte-identical to the heavy path's textLength.
            if (isReplaceable(node)) { n++; return }
            for (c in node.children) walk(c)
        }
        walk(el)
        return n
    }

    /**
     * The sole image of a figure-like block (`<p><img/></p>`, `<div><img/></div>`, `<br>`-terminated
     * or whitespace-padded or span-wrapped variants), or null when [el] carries any real text, more
     * than one image, or a block-level image (the latter is already its own leaf via the classifier).
     *
     * Single-source with the text absorption the layout shapes: the block qualifies iff its absorbed
     * text trims down to exactly one U+FFFC slot and its inline image walk finds exactly one image.
     * Both [buildBoxTree] and [enumerateBlockLeaves] route through this, so heavy and light agree on
     * which blocks unwrap to image leaves.
     */
    fun soleFigureImage(
        el: MarkupElement,
        classify: BlockClassify,
        hidden: HiddenCheck = HIDDEN_NONE,
    ): MarkupElement? {
        if (el.isText || el.tag == "table" || isReplaceable(el)) return null
        if (hidden.isHidden(el)) return null
        if (absorbedText(el, classify, hidden).trim { it <= ' ' || it == '\n' } != "\uFFFC") return null
        val imgs = inlineImageEls(el, classify, hidden)
        if (imgs.size != 1) return null
        val img = imgs[0]
        if (classify.isBlock(img)) return null
        return img
    }

    /**
     * **Single-source char step** for a leaf in the global char stream. A text leaf advances its own
     * [MarkupElement.textLength]; a replaceable (img) leaf occupies exactly one slot `[k,k+1)`. Both
     * the heavy and the light path derive their `globalCharStarts` from this so img/display:block
     * leaves can never drift between them.
     */
    fun leafCharAdvance(el: MarkupElement): Long = if (isReplaceable(el)) 1 else el.textLength

    /**
     * P4-a2: clear 是否越过给定侧的生效悬浮（v1 保守规则；P6-a 起主路不再使用，
     * 本函数仅 light 临时窗（P6-a2 前）沿用）。
     *
     * `both` 恒越过；`none` 也越过（v1 不做块盒与悬浮的重叠——保守取空隙，不重叠）；
     * 显式单侧只越过同侧（`clear:left` 不越右悬浮，浏览器同式）。
     */
    fun clearsActive(clear: orilumn.reader.engine.css.ClearSide, activeSide: FloatSide?): Boolean = when (clear) {
        orilumn.reader.engine.css.ClearSide.BOTH -> true
        orilumn.reader.engine.css.ClearSide.NONE -> true
        orilumn.reader.engine.css.ClearSide.LEFT -> activeSide == null || activeSide == FloatSide.LEFT
        orilumn.reader.engine.css.ClearSide.RIGHT -> activeSide == null || activeSide == FloatSide.RIGHT
    }

    /**
     * [el] 的上一个可见原文兄弟（跳过 `display:none`；合成匿名 `#text` 不在 children 里，
     * 恒回 null——v1 不环绕匿名 run，与其 emit 期 clear 同式）。
     */
    fun prevVisibleSibling(el: MarkupElement, hidden: HiddenCheck = HIDDEN_NONE): MarkupElement? {
        val p = el.parent ?: return null
        val sibs = p.children
        var i = sibs.indexOf(el)
        if (i <= 0) return null
        while (i > 0) {
            i--
            if (!hidden.isHidden(sibs[i])) return sibs[i]
        }
        return null
    }

    /**
     * P6-a: 文档序透传的待环绕状态（per-side build 期记账，emit 期 Y 跨度是另一套）。
     *
     * 悬浮叶注册（侧/宽/高 px），后随文本叶按塑形高度消耗；不可环绕叶（table/img 块）
     * 到达即清零（它们在 emit 期越过跨度，后续文本必在跨度之下）。同侧新悬浮覆盖
     * （纵向堆叠，前跨度已被身旁叶消耗），异侧互不干扰（并排双浮动）。
     */
    class FloatPending {
        var lW: Int = 0
        var lH: Int = 0
        private var lUsed: Int = 0
        var rW: Int = 0
        var rH: Int = 0
        private var rUsed: Int = 0

        fun activeL(): Boolean = lW > 0 && lH > lUsed
        fun activeR(): Boolean = rW > 0 && rH > rUsed
        fun remL(): Int = (lH - lUsed).coerceAtLeast(0)
        fun remR(): Int = (rH - rUsed).coerceAtLeast(0)

        /** 注册本侧跨度：同侧叠加（CSS 纵向堆叠：旧余量 + 新高，宽取最大防交叠），异侧覆盖本侧。
         * 悬浮盒零行高不占 Y，异侧不消耗（并排双浮动同顶），Y 前进只由后随文本记账。 */
        fun register(side: FloatSide, floatW: Int, floatH: Int) {
            if (side == FloatSide.LEFT) {
                lW = maxOf(lW, floatW); lH = remL() + floatH; lUsed = 0
            } else if (side == FloatSide.RIGHT) {
                rW = maxOf(rW, floatW); rH = remR() + floatH; rUsed = 0
            }
        }

        /** 文本叶消耗（环绕/clear/回退一律计 Y 前进）。 */
        fun consume(h: Int) {
            lUsed += h
            rUsed += h
        }

        /** 显式 clear 预处理：被清侧从待环绕剔除（emit 期 Y 跳跃后它们已在下方）。 */
        fun preClear(clear: ClearSide) {
            if (clear == ClearSide.BOTH) dropAll()
            else if (clear == ClearSide.LEFT) dropLeft()
            else if (clear == ClearSide.RIGHT) dropRight()
        }

        fun dropLeft() {
            lW = 0; lUsed = 0; lH = 0
        }

        fun dropRight() {
            rW = 0; rUsed = 0; rH = 0
        }

        fun dropAll() {
            dropLeft(); dropRight()
        }
    }

    /**
     * P6-a 收窄单源（重/轻双路 + v1 兼容壳同式）：按生效侧收窄断行宽。
     *
     * 显式单侧 clear 只屏蔽该侧（另一侧仍环绕，CSS 2.1 §9.5）；BOTH 恒空（emit 期必越双侧）。
     * 过窄列退回块式（双路同退，emit 期越过）。v1 单 img 输入下与旧公式逐字节一致。
     */
    fun narrowLead(
        remH: Int,
        lW: Int,
        rW: Int,
        clearSide: ClearSide,
        leafStyle: ComputedStyle,
        contentW: Int,
    ): FloatLead? {
        val useL = lW > 0 && clearSide != ClearSide.LEFT && clearSide != ClearSide.BOTH
        val useR = rW > 0 && clearSide != ClearSide.RIGHT && clearSide != ClearSide.BOTH
        if (!useL && !useR) return null
        val cw = contentW.coerceAtLeast(1)
        val lineH = lineHeightPx(leafStyle.fontSizePx, leafStyle.lineHeightRatio).coerceAtLeast(1)
        // K 行覆盖悬浮高（含叶上边距 + 1 行保守余量，只多不少；v1 同式）。
        val mTop = leafStyle.margin.top.coerceAtLeast(0f).roundToInt()
        val lines = (remH + mTop + lineH - 1) / lineH + 1
        val gap = ListMarkers.markerGapPx(leafStyle.fontSizePx)
        var narrowW = cw
        var xOff = 0f
        if (useL) {
            narrowW -= lW + gap
            xOff = (lW + gap).toFloat()
        }
        if (useR) narrowW -= rW + gap
        // 过窄列退回块式（双路同退；emit 期 clear 越过悬浮）。
        if (narrowW < maxOf(48, cw * 4 / 10)) return null
        return FloatLead(lines.coerceAtLeast(1), narrowW, xOff)
    }

    /** P6-a: pending 快照→lead（双路同调；匿名合成叶恒空，调用方保证）。 */
    fun pendingLeadFor(pending: FloatPending, leafStyle: ComputedStyle, contentW: Int): FloatLead? {
        val lW = if (pending.activeL()) pending.lW else 0
        val rW = if (pending.activeR()) pending.rW else 0
        if (lW <= 0 && rW <= 0) return null
        val remH = maxOf(pending.remL(), pending.remR())
        return narrowLead(remH, lW, rW, leafStyle.clearSide, leafStyle, contentW)
    }

    /**
     * auto 分列的单元格度量（**重/轻两路单源**）：border-box 口径的 max/min-content。
     *
     *  - max-content = 整段不换行时的最宽行（[ParagraphBreaker.preferredWidth]，硬换行 `\n` 处自身的
     *    度量已分行取最大）；
     *  - min-content = 最长不可断单元（[ParagraphBreaker.minContentWidth]）；
     *  - 两者都加单元格自身横向 padding＋border（列宽即 border-box 宽）。
     *
     * 塑形参数与断行侧 `breakLeafLines` 同式（`monospace || tag == "pre"`；`white-space` 不换行的格
     * 其 min-content 即 max-content——浏览器同式），故「量」与「排」不会分家。
     */
    fun tableCellPref(
        breaker: ParagraphBreaker,
        col: Int,
        colSpan: Int,
        text: String,
        style: ComputedStyle,
        tag: String?,
        fontRuns: List<FontRun>,
    ): TableGridModel.CellPref {
        val edges = style.padding.horizontal + style.border.horizontal
        // 指定宽下限（`th width=100px` 等表示型属性经级联已进 widthPx，content 口径，
        // 故加边距成 border-box；百分比在度量期无容器可解，暂略）。
        val specified = ((style.widthPx ?: 0f).coerceAtLeast(0f) + edges).takeIf { style.widthPx != null && style.widthPx > 0f } ?: 0f
        if (text.isEmpty()) return TableGridModel.cellPref(col, colSpan, 0f, 0f, edges, specified)
        val mono = style.monospace || tag == "pre"
        val maxContent = breaker.preferredWidth(text, style.fontSizePx, style.fontFamilies, style.fontWeight, style.italic, mono, fontRuns)
        val minContent = if (WhiteSpaceNormalize.wraps(style.whiteSpace)) {
            breaker.minContentWidth(text, style.fontSizePx, style.fontFamilies, style.fontWeight, style.italic, mono, fontRuns)
        } else {
            maxContent
        }
        return TableGridModel.cellPref(col, colSpan, maxContent, minContent, edges, specified)
    }

    /**
     * P6-a: 文本悬浮塑形宽（CSS 2.1 §10.3.5 简化）：指定宽（px / % 解容器）优先，
     * 否则 shrink-to-fit（breaker 首选测宽，分段取最大，上限版心）。
     */
    fun floatTextWidth(
        style: ComputedStyle,
        text: String,
        contentW: Int,
        breaker: ParagraphBreaker,
        fontRuns: List<FontRun>,
    ): Int {
        val cw = contentW.coerceAtLeast(1)
        style.widthPx?.let { return it.roundToInt().coerceIn(1, cw) }
        style.widthPct?.let { return (it / 100f * cw).roundToInt().coerceIn(1, cw) }
        var pref = 0f
        for (para in text.split('\n')) {
            if (para.isEmpty()) continue
            pref = maxOf(pref, breaker.preferredWidth(para, style.fontSizePx, emptyList(), 400, false, false, fontRuns))
        }
        if (pref <= 0f) pref = cw.toFloat()
        return pref.roundToInt().coerceIn(1, cw)
    }

    /**
     * P4-a2: 文本叶相对其原文前兄弟的悬浮环绕前导（v1 冻结语义：仅 img 前驱；P6-a 起重路径改走
     * [FloatPending] 透传，本函数留作轻路径（P6-a2 前）与单测的单源收窄口）。
     *
     * 仅当 [prev] 是带 `float: left/right` 的 `<img>`、[leaf] 是同容器可环绕文本叶时返回非空；
     * 其余（悬浮过宽/列过窄/表格/替换叶/合成匿名叶/双侧 clear）回 null。单侧 clear 按剩余侧收窄（P6-a R4）。
     *
     * @param contentW 叶与悬浮像同处的容器内宽（同级兄弟恒同值；调用方喂叶 contentW 即可）。
     */
    fun floatLeadFor(
        prev: MarkupElement?,
        leaf: MarkupElement,
        styleOf: (MarkupElement) -> ComputedStyle,
        contentW: Int,
        imageLoader: ImageBoundsReader? = null,
        chapterHref: String = "",
    ): FloatLead? {
        if (prev == null || prev.tag != "img") return null
        val side = styleOf(prev).floatSide
        if (side == FloatSide.NONE) return null
        // 可环绕：真文本叶（替换/表格/匿名合成/容器一律不包；`#text` 真节点永不为叶）。
        if (isReplaceable(leaf) || leaf.tag == "table" || leaf.tag == "tr" || leaf.tag == "#text") return null
        if (leaf.parent?.children?.contains(leaf) != true) return null
        val lstyle = styleOf(leaf)
        val cw = contentW.coerceAtLeast(1)
        val (floatW, floatH) = replacedUsedSize(
            prev, styleOf(prev), innerBreakWidth(styleOf(prev), cw), imageLoader, chapterHref,
        )
        if (floatW <= 0 || floatH <= 0 || floatW >= cw) return null
        val (lW, rW) = if (side == FloatSide.LEFT) floatW to 0 else 0 to floatW
        return narrowLead(floatH, lW, rW, lstyle.clearSide, lstyle, contentW)
    }

    /** Default aspect ratio (width/height) when an image has no attrs and the binary is unreadable. */
    const val DEFAULT_IMG_RATIO = 1.6f

    /**
     * Used pixel size of a replaced element (`img`) per CSS 2.1 §10.3.2 + §10.6.2 + §10.4.
     * Single source behind both the block-replaceable path ([replaceableHeightOf]) and the
     * inline-`<img>` draw path, so measurement and painting can never size the same image
     * differently. See [usedReplacedSize].
     *
     * @param containingW containing-block width px (the block's line-break width); `max-width:%`
     *   and `width:%` resolve against it.
     */
    fun replacedUsedSize(
        el: MarkupElement,
        style: orilumn.reader.engine.css.ComputedStyle,
        containingW: Int,
        imageLoader: ImageBoundsReader? = null,
        chapterHref: String = "",
    ): Pair<Int, Int> {
        val intrinsic = intrinsicSizeOf(el, imageLoader, chapterHref)
        return style.usedReplacedSize(intrinsic?.first, intrinsic?.second, containingW)
    }

    /**
     * Resolved pixel height of a replaceable (img) block. Delegates to [replacedUsedSize], i.e.
     * standard CSS sizing: `auto/auto` yields the intrinsic height (CSS 2.1 §10.3.4 sizes
     * block-level replaced exactly like inline replaced), with only `max-width`/`min-*` able
     * to shrink/grow it via the §10.4 table. No figure-fit here — that belongs to the user
     * layer's style themes, never the render-layer box model.
     */
    fun replaceableHeightOf(
        el: MarkupElement,
        style: orilumn.reader.engine.css.ComputedStyle,
        contentWidthPx: Int,
        imageLoader: ImageBoundsReader? = null,
        chapterHref: String = "",
    ): Int = replacedUsedSize(el, style, contentWidthPx.coerceAtLeast(1), imageLoader, chapterHref).second

    /** Intrinsic (w, h) px of an `<img>`: HTML attrs (both numeric) → binary bounds → null.
     *  A single HTML attr is NOT an intrinsic side (the ratio is unknown) — it already reaches
     *  sizing through the cascade ([Cascade.htmlAttrOrigin] → `widthPx`/`heightPx`), so reporting
     *  it here as well would double-count. CSS is deliberately excluded (it flows through
     *  [replacedUsedSize]'s style params). */
    fun intrinsicSizeOf(
        el: MarkupElement,
        imageLoader: ImageBoundsReader? = null,
        chapterHref: String = "",
    ): Pair<Int, Int>? {
        val aw = el.attrs["width"]?.toFloatOrNull()
        val ah = el.attrs["height"]?.toFloatOrNull()
        if (aw != null && aw > 0f && ah != null && ah > 0f) return aw.roundToInt() to ah.roundToInt()
        if (imageLoader != null && chapterHref.isNotBlank()) {
            val src = el.attrs["src"]
            if (src != null) {
                val bounds = runCatching { imageLoader.decodeBounds(chapterHref, src) }.getOrNull()
                if (bounds != null && bounds.first > 0 && bounds.second > 0) {
                    return bounds.first to bounds.second
                }
            }
        }
        return null
    }


    private val BOX_BLOCK_TAGS = setOf(
        "p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "pre", "section",
        "article", "aside", "header", "footer", "nav", "ul", "ol", "li", "figure", "figcaption",
        "table", "thead", "tbody", "tfoot", "tr", "td", "th", "caption", "dl", "dt", "dd", "address", "hr",
        "main", "hgroup", "details", "summary",
    )

    internal val DEFAULT_STYLE = ComputedStyle(fontSizePx = 16f, lineHeightRatio = 1.5f)
}