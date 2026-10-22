package orilumn.reader.engine.laying

import orilumn.reader.engine.ImageBoundsReader
import orilumn.reader.engine.css.BreakRule
import orilumn.reader.engine.css.ClearSide
import orilumn.reader.engine.css.ColorRun
import orilumn.reader.engine.css.ComputedStyle
import orilumn.reader.engine.css.Edges
import orilumn.reader.engine.css.FloatSide
import orilumn.reader.engine.css.FontRun
import orilumn.reader.io.Logger
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
        /**
         * 平台层实测的 dp→物理像素倍率（见 `TypographicProfile.density`）：图片资源自身的
         * 像素尺寸按 CSS px 语义换算到画布像素（×density）。默认 1 保持既有测试/工具不变。
         */
        density: Float = 1f,
        /** P3-c 生成内容查找（空即无，旧路径）。 */
        genOf: GenOf = EmptyGen,
        /** `::first-letter` 伪元素查找（null = 无该规则；与 genOf 同由章节开关决定）。 */
        firstLetterOf: FirstLetterOf? = null,
        /**
         * P3：一页内容高（px）。> 0 时施加「块级替换叶 ≤ 一页 − 簇内其它内容」预算
         * （口径乙，[ReplacedFitBudget]）——**分页层**的约束，不是 CSS 声明；0 = 不约束
         * （旧行为逐字节一致，测试与小工具走这条）。
         */
        contentH: Int = 0,
    ): BoxLayoutResult {
        val boxes = buildBoxTree(root, styles, breaker, widthPx, left = 0, classify, hidden, imageLoader, chapterHref, density, genOf, firstLetterOf)
        val lines = ArrayList<FlowedLine>()
        flowBoxes(boxes, lines)
        if (contentH > 0) applyReplacedFit(boxes, lines, styles, contentH, imageLoader, chapterHref, density)
        // §10.6.7：裁剪盒（`overflow != visible` = 本引擎的 BFC 近似）的高度包住浮动后代，
        // **先于**裁剪几何（裁剪区取自盒高）。
        containFloatsInClippers(boxes)
        // E12：`position: relative` 的绘制偏移（**先**于裁剪几何：裁剪区随盒一起平移）。
        // 只落绘制用字段，不改流位置 ⇒ 占位/分页不受影响；无 `position: relative` 时全零。
        applyRelativeOffsets(boxes, styles::get)
        // E11：`overflow` 祖先裁剪几何——必须在**最终流完之后**算（盒上下沿由流决定，流前算即错位）。
        // 无 `overflow` 声明时每个盒的 clipRect 恒 null，全链零像素回归。
        applyInheritedClips(boxes)
        return BoxLayoutResult(lines, boxes)
    }

    /**
     * CSS 2.1 §10.6.7：`overflow != visible` 的块建立 BFC，自动高度**包住浮动后代** ——
     * 下沿取「在流内容底」与「最深浮动后代的 border-box 底」的较大者。
     *
     * 本引擎的流**有意**不做 BFC 包拢（浮动逃逸容器，见 [emit] 容器收尾注释）：`overflow: visible`
     * 的块高度只计在流内容，与浏览器 §10.6.3 一致。但**裁剪盒**不包住浮动就出事 —— E11 的
     * `overflow` 裁剪按 padding box 裁，浮动被齐根切掉，也就是**丢内容**。真书里的 clearfix 写法
     * （裁剪容器里全是 `float` 子块，在流高 0）会整块消失；实测图片浮动高 120 被切到 61。
     *
     * **流高已由 `emit` 收尾同口径计入**（见那里的 §10.6.7 注释）；本趟只兜底那些不经 `emit`
     * 容器收尾的盒（判定与增量同式、幂等：已经够高就不再动），并保证裁剪区（取自盒高）最大化。
     */
    private fun containFloatsInClippers(boxes: List<LayoutBox>) {
        for (b in boxes) {
            // 先子后父：内层裁剪盒先长高，外层再按同一口径聚合。
            if (b.childBoxes.isNotEmpty()) containFloatsInClippers(b.childBoxes)
            if (!b.style.clipsOverflow()) continue
            val floatBottom = deepestFloatBottom(b.childBoxes)
            // 长的是**内容盒**底（§10.6.7 说的是内容边）：加回自身下边距才能让 padding box 也包住
            // 浮动 —— 裁剪区取的正是 padding box，少算边框会把浮动最下几像素切掉（实测差 2px）。
            val edgesBottom = (b.style.padding.bottom + b.style.border.bottom).roundToInt()
            if (floatBottom > b.contentBottom - edgesBottom) b.contentBottom = floatBottom + edgesBottom
        }
    }

    /**
     * 子树里最深的**浮动**盒（含嵌套）的**外边盒**底（border-box 底 + `margin-bottom`）；
     * 无浮动 = [Int.MIN_VALUE]。
     *
     * CSS 2.1 §10.6.7：BFC 根的高度取子浮动盒的**外边盒**底 —— 少算 `margin-bottom` 的表现是
     * clearfix 灰框比浏览器浅一圈（Chromium 实测 56 CSS px，本仓 54：`.fli{height:40;border:1;margin:2}`
     * 的 40+2 边框有了，2px 下边距丢了）。
     */
    private fun deepestFloatBottom(boxes: List<LayoutBox>): Int {
        var max = Int.MIN_VALUE
        for (b in boxes) {
            if (b.table == null && b.style.floatSide != FloatSide.NONE) {
                max = maxOf(max, b.contentBottom + b.style.margin.bottom.roundToInt())
            }
            if (b.childBoxes.isNotEmpty()) max = maxOf(max, deepestFloatBottom(b.childBoxes))
        }
        return max
    }

    /**
     * P3：重路径的替换叶高度预算。
     *
     * 两趟：第一趟 [buildBoxTree] + [flowBoxes] 流出**自然**几何（图的 used 高、簇里其它成员的
     * 实测高都在里面）；据此算出每个需要缩的图的高度上限，改写盒上的
     * [LayoutBox.replaceableFitH] / [LayoutBox.replaceableHeight]，再重流一次。
     *
     * 之所以「改写 + 重流」而不是重建盒树：块级无浮动替换叶占满版心宽（`contentWidth = 内容宽`，
     * 见 `buildBoxTree` 的 img 分支），它的 used 宽只在绘制时按比例还原 —— 缩高不改变任何盒的
     * 水平几何，故 [flowBoxes] 重跑一次即精确（且它不塑形，成本是线性的一趟）。
     *
     * 悬浮替换叶不参与（浮动几何是另一套，本轮 out of scope，见方案 §4.5）。
     */
    private fun applyReplacedFit(
        boxes: List<LayoutBox>,
        lines: MutableList<FlowedLine>,
        styles: Map<MarkupElement, ComputedStyle>,
        contentH: Int,
        imageLoader: ImageBoundsReader?,
        chapterHref: String,
        density: Float = 1f,
    ) {
        val leaves = collectLeafBoxes(boxes)
        val els = leaves.mapNotNull { it.el }
        if (els.size != leaves.size) return
        val styleOf: (MarkupElement) -> ComputedStyle = { styles.getValue(it) }
        val breaks = HashMap<MarkupElement, ReplacedFitBudget.BreakPair>(els.size)
        for (b in leaves) {
            val el = b.el ?: continue
            breaks[el] = ReplacedFitBudget.BreakPair(b.breakBefore, b.breakAfter)
        }
        val breakOf: (MarkupElement) -> ReplacedFitBudget.BreakPair = { el ->
            breaks[el] ?: ReplacedFitBudget.BreakPair(BreakRule.AUTO, BreakRule.AUTO)
        }
        val plan = ReplacedFitBudget.plan(
            leaves = els,
            lo = 0,
            hi = els.size,
            contentH = contentH,
            naturalH = { i ->
                val b = leaves[i]
                if (b.style.floatSide != FloatSide.NONE) 0 else b.replaceableHeight
            },
            ownHeight = { i -> (leaves[i].contentBottom - leaves[i].contentTop).coerceAtLeast(0) },
            breakOf = breakOf,
            styleOf = styleOf,
        )
        if (plan.isEmpty()) return
        for ((i, fit) in plan) {
            val box = leaves[i]
            val el = box.el ?: continue
            val st = box.style
            box.replaceableFitH = fit
            box.replaceableHeight = replaceableHeightOf(
                el, st, innerBreakWidth(st, box.contentWidth), imageLoader, chapterHref, fit, density,
            )
        }
        lines.clear()
        flowBoxes(boxes, lines)
    }

    /** Flatten a box list into its **leaf** boxes, in document order (containers recursed). */
    fun collectLeafBoxes(boxes: List<LayoutBox>): List<LayoutBox> {
        val out = ArrayList<LayoutBox>()
        for (b in boxes) if (b.isContainer) out.addAll(collectLeafBoxes(b.childBoxes)) else out.add(b)
        return out
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
        // E19: 同 [buildBoxTree] —— 行内子树里埋着块级后代时本元素不是叶（flowChildren 会在块处断开）。
        if (blockChildren.isEmpty() && !hasInlineWrappedBlock(root, classify, hidden)) {
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
            // E23-b：空悬浮盒（无文本、非替换）只要**指定高为正**就是真浮动盒（Chromium 同）；
            // 零尺寸空盒仍走下面的普通叶分支（不占行、不动流）。
            val floatSpan = box.replaceableHeight > 0 || box.ranges.isNotEmpty() ||
                (box.lineHeights.isEmpty() && specifiedContentHeight(s, 0) > 0)
            if (box.table == null && s.floatSide != FloatSide.NONE && floatSpan) {
                // 显式 clear 只越指定侧（lead 叶不可能带 BOTH，见 build 期规则）。
                if ((s.clearSide == ClearSide.LEFT || s.clearSide == ClearSide.BOTH)) {
                    val b = st.floatBottomL
                    if (b != null && b > st.y) st.y = b
                }
                if ((s.clearSide == ClearSide.RIGHT || s.clearSide == ClearSide.BOTH)) {
                    val b = st.floatBottomR
                    if (b != null && b > st.y) st.y = b
                }
                // 相邻同侧悬浮：**另起一行**才越过本侧旧悬浮；同行并排同顶（CSS 2.1 §9.5.1）。
                if (box.floatNewRow) {
                    val ownBottom = if (s.floatSide == FloatSide.LEFT) st.floatBottomL else st.floatBottomR
                    if (ownBottom != null && ownBottom > st.y) st.y = ownBottom
                }
                val min = when {
                    skipOwnTop -> 0f
                    // E23-b：同行并排的第二块起不再加自身顶边距——行顶已由本行首个悬浮抬到
                    // 外边盒上缘（CSS 2.1 §9.5.1：同一行的浮动共用行顶）。
                    !box.floatNewRow -> 0f
                    first -> s.margin.top
                    else -> collapseMargins(s.margin.top, prevBottomMargin)
                }
                st.y += min.roundToInt()
                box.contentTop = st.y
                val contentY = st.y + (s.border.top + s.padding.top).roundToInt()
                val spanH = if (box.replaceableHeight > 0) box.replaceableHeight
                else specifiedContentHeight(s, box.lineHeights.sum())
                box.contentBottom = contentY + spanH +
                    (if (box.replaceableHeight > 0) 0 else (s.padding.bottom + s.border.bottom).roundToInt())
                box.firstLineIndex = out.size
                // 零高行：字符槽连续（分页不断），几何不占竖空间（环绕叶贴着排）。
                // E23-b：**空悬浮盒**（无文本、无图）不发这一行 —— 与轻路径 `rebuildLocalLines` 同判
                // （那边行数为 0、走无行叶记账），否则两路的行计数/切片对不上。
                if (box.ranges.isNotEmpty() || box.replaceableHeight > 0) {
                    out.add(
                        FlowedLine(
                            charStart = st.char,
                            charEnd = st.char + box.textLength,
                            yTop = contentY,
                            yBottom = contentY,
                            paragraphStart = true,
                        ),
                    )
                }
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
            // E23 指定高（CSS 2.1 §10.6.3）：内容盒高 = max(在流内容高, `height`)；替换叶的高
            // 已由替换尺寸接管（`height` 在那条路上消费），不在此重复。
            val contentBottomY =
                if (box.replaceableHeight > 0) y
                else contentY + specifiedContentHeight(s, y - contentY)
            val boxBottom = contentBottomY + (s.padding.bottom + s.border.bottom).roundToInt()
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
        // §10.6.7：BFC 根把浮动关在盒内 —— 盒内注册的跨度不得收窄/回填后随兄弟（浏览器里
        // BFC 根外面看不见这些浮动）。进入时存外层跨度，收尾还原；盒内高度由下方 `floatsContentH` 计。
        val bfc = box.style.clipsOverflow()
        val keptBottomL = st.floatBottomL
        val keptTopL = st.floatTopL
        val keptBottomR = st.floatBottomR
        val keptTopR = st.floatTopR
        for (child in box.childBoxes) {
            subPrev = emit(child, st, subFirst, subPrev, out, skipOwnTop = subFirst && hasChildren && edgesTop == 0)
            if (child.firstLineIndex >= 0) firstChildLine = minOf(firstChildLine, child.firstLineIndex)
            lastChildLine = maxOf(lastChildLine, child.lastLineExclusive)
            subFirst = false
        }
        if (bfc) {
            st.floatBottomL = keptBottomL; st.floatTopL = keptTopL
            st.floatBottomR = keptBottomR; st.floatTopR = keptTopR
        }
        // P6-a: 收尾不包悬浮（CSS 默认浮动逃逸容器；包住只在 BFC/clearfix，见上方 §10.6.7）。
        // 跨度存活供后随兄弟跨容器环绕；容器背景/高度只计 in-flow 内容。
        // E23-b（CSS 2.1 §10.6.3）：容器指定高同样消费 —— 内容盒高 = max(在流内容高, `height`)，
        // 与叶同一条「只多不少」口径（内容更高照排不裁；裁不裁是 overflow 的事）。
        // 容器底边（背景/边框/裁剪区下界）与后随内容的起点因此落在指定高上；游标只下不上 ⇒ 行位
        // 单调，分页容量仍由行位给出（帧底恒等式见轻路径 `interiorGapAbove` 同源差额）。
        val contentTopY = box.contentTop + edgesTop
        // §10.6.7：BFC 根（本引擎以 `overflow != visible` 近似）的自动高**包住浮动后代**，
        // 而且这份高度是**流高**，不是只改绘制：容器的底色/边框下沿、父容器高度、后随兄弟与
        // 分页全吃同一份（Chromium 实测 `.cf` 高 56，父级 `.test-case` 的下内边距从这 56 之后
        // 起算）。曾只改绘制几何、流高仍按在流内容 ⇒ 灰框下沿比浏览器高出 4.5px，
        // 下方虚线框的间距被吃掉一半（用户实测：装四个方块的那个矩形下间距偏小）。
        val floatsContentH = if (box.style.clipsOverflow() && box.childBoxes.isNotEmpty()) {
            val fb = deepestFloatBottom(box.childBoxes)
            if (fb == Int.MIN_VALUE) 0 else (fb - contentTopY).coerceAtLeast(0)
        } else {
            0
        }
        val naturalContentH = maxOf(st.y - contentTopY, floatsContentH)
        // E23-e：`min-height` 只在**真的撑高**时才断掉父↔末子底折叠（Chromium 实测：`min-height`
        // 低于内容 ⇒ 末子下边距照常外塌；高于内容 ⇒ 收进盒内、后随内容贴盒底）。`height` 恒断链
        // （指定高即定高，与内容高不高无关），故两条分开判。
        val floor = specifiedVerticalFloor(sty)
        box.verticalFloorBound = floor != null && floor > naturalContentH
        box.contentBottom = contentTopY + maxOf(naturalContentH, floor ?: 0) + edgesBottom
        st.y = box.contentBottom
        box.firstLineIndex = if (firstChildLine == Int.MAX_VALUE) -1 else firstChildLine
        box.lastLineExclusive = lastChildLine
        // CSS 2.1 §8.3.1 (bottom): a bottom-edge-free container whose specified height is auto
        // collapses its OWN bottom margin with the bottom margin of its last in-flow block-level
        // child — transitively down every edge-free last-child container (the exact mirror of the
        // top-side first-child chain). The single collapsed group is what the following sibling
        // consumes; in that case the child's margin does not add anything inside the container.
        // A container with bottom border/padding (or a specified height, or an **effective** vertical
        // floor / `min-height`) does NOT collapse: it returns its own bottom margin unchanged (the
        // last child's bottom margin is then dropped, matching Chromium).
        val bottomGroup = if (hasChildren) lastChildBottomMargin(box) else 0f
        return if (hasChildren && edgesBottom == 0 && sty.heightPx == null && !box.verticalFloorBound)
            collapseMargins(sty.margin.bottom, bottomGroup)
        else sty.margin.bottom
    }

    /**
     * The collapsed bottom-margin group of [box]'s last-child descent (§8.3.1 bottom side): folds
     * every edge-free container's `margin.bottom` down the last-child chain, then folds the deepest
     * box's (a leaf, or the first bottom-edge container that terminates the chain) margin in last.
     *
     * Mirrors the top-side first-child descent that the container branch performs inline — descending
     * only through containers that carry no bottom border/padding, no specified height, and have
     * children, folding each of those containers' margins into one group, then closing the group with
     * the terminator's margin. §8.3.1 requires a `height` of `auto` for the parent↔last-child collapse,
     * so an intermediate specified height breaks the group exactly like a bottom edge does (E23-b；此前
     * 流程不消费指定高，故只在外层判定处挡，现两处同判据）。
     */
    private fun lastChildBottomMargin(box: LayoutBox): Float {
        var child = box.childBoxes.last()
        var acc = 0f
        while (child.isContainer &&
            (child.style.border.bottom + child.style.padding.bottom).roundToInt() == 0 &&
            child.style.heightPx == null &&
            !child.verticalFloorBound &&
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
        availW: Int,
        left: Int,
        classify: BlockClassify,
        hidden: HiddenCheck = HIDDEN_NONE,
        imageLoader: ImageBoundsReader? = null,
        chapterHref: String = "",
        /** 图片资源固有 px → 画布 px 的倍率（平台层实测；见 `TypographicProfile.density`）。 */
        density: Float = 1f,
        /** P3-c 生成内容查找（空即无，旧路径）。 */
        genOf: GenOf = EmptyGen,
        /** `::first-letter` 伪元素查找（null = 无该规则；行高与塑形同吃它，量画同源）。 */
        firstLetterOf: FirstLetterOf? = null,
        /** P6-a 待环绕透传（文档序；容器递归共享同一份）。 */
        pending: FloatPending = FloatPending(),
    ): List<LayoutBox> {
        // display:none → this element (and its whole subtree) contributes no box, no text.
        if (hidden.isHidden(el)) return emptyList()
        // styles 恒全：StyleComputer.compute 覆盖整树，缺键即调用方传了残表，直接抛（静默 16px 会排错版）。
        val style = styles.getValue(el)
        // B（块级指定宽）：非替换（已单独消费宽）且非 table 且非浮动 → 对块级盒应用指定 width（px/pct）；% 解按包含块内容宽。
        // 浮动元素的宽度语义与浮动排位模型耦合（本轮暂不改浮动面），先跳过以免破坏既有 P6a 浮动测试。
        val widthPx = if (el.tag == "table" || isReplaceable(el) || style.floatSide != orilumn.reader.engine.css.FloatSide.NONE) {
            availW
        } else {
            specifiedBorderBoxWidth(style, availW) ?: availW
        }
        val blockChildren = el.children.filter { classify.isBlock(it) }
        // E19（CSS 2.1 §9.2.1 block-in-inline）：直接块子节点为空、但**行内子树里**埋着块级后代时，
        // 本元素也不是文本叶 —— 必须走容器分支，把行内盒在块级后代处断开（[splitBlockInInline]）。
        // 否则 [appendInlineText] 与 [styledSegments] 的块跳过会把块里的文字**整段丢掉**
        // （实测 `<a href><p>abc</p></a>` 零叶、`<a>zzz<p>abc</p>www</a>` 只剩 `zzz`）。
        // [enumerateBlockLeaves] / [styledCharAdvance] 同判据，重轻两路叶集 / 字符起点不漂移。
        val inlineWrappedBlock = blockChildren.isEmpty() && hasInlineWrappedBlock(el, classify, hidden)
        return if (el.tag == "table") {
            // Table 不可环绕：emit 期越过跨度，后续文本必在跨度之下，待环绕清零。
            pending.dropAll()
            // Table: a container whose "children" are its resolved row leaves (each a single
            // un-splittable line of the row's height, drawn as a 2D grid). Rows, not cells, are the
            // pagination/char leaves, so a page can split the table between rows only.
            // P1-2: caption 展开为自己的盒（递归分解；`caption-side: bottom` 时沉底）。
            // 表外盒几何（指定宽/margin auto）走重轻单源 [TableGridModel.tableOuterGeometry]。
            val g = TableGridModel.tableOuterGeometry(availW, left, style)
            val innerW = g.contentW
            // The table's own border-box left is its children's baseline; rows start after its left edge.
            val rowLeft = g.tableLeft + (style.border.left + style.padding.left).roundToInt()
            val rows = buildTableRows(el, styles, breaker, innerW, rowLeft, classify, hidden, imageLoader, chapterHref, density, genOf, firstLetterOf)
            val cap = TableGridModel.build(el).caption
            val capBoxes = if (cap != null && !hidden.isHidden(cap)) {
                buildBoxTree(cap, styles, breaker, innerW, rowLeft, classify, hidden, imageLoader, chapterHref, density, genOf, firstLetterOf, pending)
            } else emptyList()
            val children = if (capBoxes.isNotEmpty() && captionIsBottom(el) { styles[it] }) rows + capBoxes else capBoxes + rows
            listOf(LayoutBox(el, style, g.tableLeft, g.outerW, emptyList(), 0, emptyList(), children))
        } else if (blockChildren.isEmpty() && isReplaceable(el)) {
            // Replaceable leaf (img): no text, fixed pixel height, one char slot. Never broken by a
            // shaper — the box flow emits it as a single un-splittable line.
            // P6-a: 悬浮 img 注册跨度（右对齐 x，双端绘制同吃 contentLeft）；非悬浮 img 块清零待环绕。
            val contentW = widthPx.coerceAtLeast(1)
            val breakW = innerBreakWidth(style, contentW)
            val (floatW, repH) = replacedUsedSize(el, style, breakW, imageLoader, chapterHref, density = density)
            val side = style.floatSide
            val (boxLeft, boxW, newRow) = if (side != FloatSide.NONE) {
                val w = floatW.coerceAtLeast(1)
                pending.preClear(style.clearSide)
                // E23-b：行装箱按**外边盒**宽记账（左右外边距参与同行排位），容量 = 含边距的包含块内容宽。
                val mH = (style.margin.left + style.margin.right).roundToInt()
                pending.register(side, w + mH, repH, availW + mH)
                // 同行装箱的落点（左浮动自内容左起；右浮动自内容右起、含自身外边盒宽）。
                val x = if (side == FloatSide.RIGHT) availW + mH - pending.lastX else pending.lastX
                Triple(left + x.coerceAtLeast(0), w, pending.lastNewRow)
            } else {
                pending.dropAll()
                Triple(left, contentW, true)
            }
            listOf(
                LayoutBox(
                    el, style, boxLeft, boxW,
                    ranges = emptyList(),
                    textLength = 1,
                    lineHeights = emptyList(),
                    childBoxes = emptyList(),
                    replaceableHeight = repH,
                    floatNewRow = newRow,
                ),
            )
        } else if (blockChildren.isEmpty() && !inlineWrappedBlock && soleFigureImage(el, classify, hidden) != null) {
            // Sole-figure block (`<p><img/></p>`, `<div><img/></div>`, span-wrapped variants): the
            // block carries no text of its own, so promote the image to a replaceable leaf instead
            // of a one-U+FFFC text leaf. A text leaf would go through the shaper/drawer as the
            // U+FFFC object-replacement glyph (a tiny dashed "obj" box) with no bitmap ever painted
            // over it — the illustration is lost. Unwrapping keeps heavy/light char accounting
            // identical (both see the img leaf, one slot) and reuses the standard replaced sizing.
            val img = soleFigureImage(el, classify, hidden)!!
            // 宿主块（`<p class="fm-figure">` 之类「只含一幅图」的块）被摘掉后，**它自己的纵向 margin
            // 不能跟着丢**：轻路径的块边界间距由 [consecutiveLeafAdvance] 沿**标记祖先链**算（含宿主
            // 块：真书 `.fm-figure{margin:1.2em 0 .8em}` 在那里是 79px/52px），本路径 `emit` 只看叶
            // 自身 `style`。两边口径不齐 ⇒ 同一个「图块」磁盘表少一段、临时表多一段 ⇒ 逐页错位
            // （实测真书 ch2：43 页里 39 页页边界不同），页首/页尾缺口（`windowLeadTop` →
            // `PartialDrawableLayout.frameLeadTop`）也随之单源失效。把宿主块（及 img 到宿主块之间的
            // 内联包装）的 margin 折叠进叶 margin，重路径的间距即与轻路径逐像素一致。
            // 只折 margin：纵向 padding/border 一并折进来的话，重路径会把这些边画成「贴着图」的窄框，
            // 而轻路径是按祖先载体（整幅宽）画 —— 反而引入新的不一致（见 `buildBackgroundDrawBoxes`）。
            val imgStyleRaw = styles[img] ?: style
            var mTop = imgStyleRaw.margin.top
            var mBottom = imgStyleRaw.margin.bottom
            var wrap: MarkupElement? = img.parent
            while (wrap != null && wrap !== el) {
                val ws = styles.getValue(wrap)
                mTop = collapseMargins(ws.margin.top, mTop)
                mBottom = collapseMargins(ws.margin.bottom, mBottom)
                wrap = wrap.parent
            }
            val imgStyle = if (wrap === el) {
                val ws = styles.getValue(el)
                imgStyleRaw.copy(
                    margin = Edges(
                        top = collapseMargins(ws.margin.top, mTop),
                        right = imgStyleRaw.margin.right,
                        bottom = collapseMargins(ws.margin.bottom, mBottom),
                        left = imgStyleRaw.margin.left,
                    ),
                )
            } else {
                // 不是 `el` 的后代（不该发生）：不折，保持旧行为。
                imgStyleRaw
            }
            val contentW = widthPx.coerceAtLeast(1)
            // 折进来的只是纵向 margin，横向断行宽/图尺寸仍按 img 自身样式（含横向 padding/border）。
            val breakW = innerBreakWidth(imgStyleRaw, contentW)
            // P6-a: 与 img 分支同式（悬浮注册 + 右对齐；非悬浮清零）。
            // P3：`fitMaxH` 由第一趟流出的几何给出（此趟恒 null = 自然尺寸），见 `applyReplacedFit`。
            val (floatContentW, repH) = replacedUsedSize(img, imgStyleRaw, breakW, imageLoader, chapterHref, density = density)
            val side = imgStyleRaw.floatSide
            val (boxLeft, boxW, newRow) = if (side != FloatSide.NONE) {
                // E24：`replacedUsedSize` 给内容宽；盒宽 / 排位 / 环绕一律 border-box（+ img 自身横边）。
                val w = floatBorderBoxWidth(imgStyleRaw, floatContentW).coerceAtLeast(1)
                pending.preClear(style.clearSide)
                // E23-b：外边盒宽记账（同 img 分支；横向边距取 img 自身样式，宿主块的纵向边距已折进上面）。
                val mH = (imgStyle.margin.left + imgStyle.margin.right).roundToInt()
                pending.register(side, w + mH, repH, contentW + mH)
                // 同行装箱的落点（左浮动自内容左起；右浮动自内容右起、含自身外边盒宽）。
                val x = if (side == FloatSide.RIGHT) contentW + mH - pending.lastX else pending.lastX
                Triple(left + x.coerceAtLeast(0), w, pending.lastNewRow)
            } else {
                pending.dropAll()
                Triple(left, contentW, true)
            }
            listOf(
                LayoutBox(
                    img, imgStyle, boxLeft, boxW,
                    ranges = emptyList(),
                    textLength = 1,
                    lineHeights = emptyList(),
                    childBoxes = emptyList(),
                    replaceableHeight = repH,
                    // P3：叶是 img，块却是 `el`（`<p class="fm-figure">`）——break-* 挂在块上，
                    // 必须带过来，否则「图 + 图注」的 avoid 边丢失（真书 .fm-figure 正是如此）。
                    breakCarrierStyle = style,
                ),
            )
        } else if (blockChildren.isEmpty() && !inlineWrappedBlock) {
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
            val styleOf: (MarkupElement) -> ComputedStyle = { styles.getValue(it) }
            val runs = leafFontRuns(el, styles, classify, hidden, genOf, firstLetterOf)
            val shifts = leafBaselineShifts(el, styles, classify, hidden, genOf, firstLetterOf)
            // 行内盒（横向 padding/border 参与断行宽度）：与绘制侧同一份，量画同源。
            val boxes = leafInlineBoxRuns(el, styles, classify, hidden, genOf, firstLetterOf)
            val side = style.floatSide
            if (side != FloatSide.NONE) {
                // E24：`floatTextWidth` 给内容宽；盒宽 / 排位 / 环绕一律 border-box（+ 自身横边）。
                val floatW = floatBorderBoxWidth(style, floatTextWidth(style, text, contentW, breaker, runs, boxes))
                val breakWf = innerBreakWidth(style, floatW)
                val brokenF = breakWrappedLines(breaker, text, style, breakWf, null, el.tag, runs, style.textIndentPx, shifts, boxes)
                val heightsF = adjustLineHeightsForInlineImages(text, brokenF, el, styles, classify, hidden, breakWf, imageLoader, chapterHref, density = density)
                    .map { it.coerceAtLeast(1) }
                // P6-b: 叠排注音行增高（与行内图抬升同式；无注音零回归）。
                val grownF = adjustLineHeightsForRuby(brokenF, heightsF, leafRubyRuns(el, styles, classify, hidden, genOf, firstLetterOf))
                // E23-b：悬浮跨度高 = max(行高和, `height`)（与 `emit` 的叶盒高同式；空悬浮盒也占位）。
                // E23-f：参与外围记账（包含块高度 / 环绕行数 / 后续内容起点）的是**外边盒**高，
                // 不是内容高 —— CSS 2.1 §10.6.7：BFC 根高度取浮动盒的外边盒底。
                // 只记内容高的表现：`<div class="cf"><div class="fli" ...></div></div>` 的灰框
                // 比 Chromium 浅一圈（实测 42 物理 px，缺上下 border 2 + 上下 margin 4）。
                val spanH = specifiedContentHeight(style, grownF.sum()) +
                    (style.border.vertical + style.padding.vertical).roundToInt() +
                    (style.margin.top + style.margin.bottom).roundToInt()
                pending.preClear(style.clearSide)
                // E23-b：外边盒宽记账（左右外边距参与同行排位），容量 = 含边距的包含块内容宽。
                val mH = (style.margin.left + style.margin.right).roundToInt()
                pending.register(side, floatW + mH, spanH, contentW + mH)
                // 同行装箱的落点（左浮动自内容左起；右浮动自内容右起、含自身外边盒宽）。
                val x = if (side == FloatSide.RIGHT) contentW + mH - pending.lastX else pending.lastX
                listOf(
                    LayoutBox(
                        el, style, left + x.coerceAtLeast(0), floatW,
                        ranges = brokenF.map { it.range },
                        hyphenAtEnd = brokenF.map { it.hyphenAtEnd },
                        squeezeRatios = brokenF.map { it.squeezeRatio },
                        textLength = text.length,
                        lineHeights = grownF,
                        childBoxes = emptyList(),
                        floatLead = null,
                        floatNewRow = pending.lastNewRow,
                    ),
                )
            } else {
                // 显式 clear 先剔除被清侧（emit 期 Y 跳跃后它们已在下方），再按剩余侧收窄。
                pending.preClear(style.clearSide)
                val lead = if (el.tag == "#text") null else pendingLeadFor(pending, style, contentW)
                // E21 残留修复：`::first-letter{float:left}` 首字下沉（出流；首字仍在文本里占首行）。
                val capF = if (el.tag == "#text") null else firstLetterOf?.invoke(el)?.let { capSt ->
                    capFloatOf(
                        capSt, style, text.substring(0, firstLetterUnitLength(text).coerceAtMost(text.length)),
                        breaker,
                    )
                }
                val breakW = innerBreakWidth(style, contentW)
                val broken = breakWrappedLines(breaker, text, style, breakW, lead, el.tag, runs, style.textIndentPx, shifts, boxes, capF)
                val lineHeights = adjustLineHeightsForInlineImages(text, broken, el, styles, classify, hidden, breakW, imageLoader, chapterHref, density = density)
                    .map { it.coerceAtLeast(1) }
                // P6-b: 叠排注音行增高（与行内图抬升同式；无注音零回归）。
                val grownHeights = adjustLineHeightsForRuby(broken, lineHeights, leafRubyRuns(el, styles, classify, hidden, genOf, firstLetterOf))
                // 回记账（含自身上边距，与 K 公式的 mTop 对称；只多不少，漂移永朝安全方向）。
                pending.consume(grownHeights.sum() + style.margin.top.coerceAtLeast(0f).roundToInt())
                listOf(
                    LayoutBox(
                        el, style, left, contentW,
                        ranges = broken.map { it.range },
                        hyphenAtEnd = broken.map { it.hyphenAtEnd },
                        squeezeRatios = broken.map { it.squeezeRatio },
                        textLength = text.length,
                        lineHeights = grownHeights,
                        childBoxes = emptyList(),
                        floatLead = lead,
                        capFloat = capF,
                    ),
                )
            }
        } else {
            // §10.6.7：`overflow != visible` = BFC 根，浮动**关在盒内** —— 盒内浮动不逃出去收窄
            // 后随内容（浏览器里 BFC 根外面看不见这些浮动）。进入时存登记几何，子女建完还原。
            val keptSpans = if (style.clipsOverflow()) pending.snapshotGeometry() else null
            // Container: interleave its block children with synthetic anonymous text runs for its own
            // stray inline content (CSS anonymous-block substitution), so text like `第xx章`.
            // P6-a: 入口显式 clear 先剔除被清侧（NONE 不动，子女自理：文本环绕、不可环绕者自越）。
            pending.preClear(style.clearSide)
            val innerW = innerBreakWidth(style, widthPx)
            // Consume this container's own left edges: each child's border-box left = this box's
            // border-box left + its left border/padding + the child's own left margin.
            val edgesL = (style.border.left + style.padding.left).roundToInt()
            val children = flowChildren(el, classify, hidden).flatMap { ch ->
                val childEdges = if (ch.tag == "#text") null else styles[ch]?.margin
                val marginL = childEdges?.left?.roundToInt() ?: 0
                val marginR = childEdges?.right?.roundToInt() ?: 0
                val childLeft = left + edgesL + marginL
                // CSS 2.1 §10.3.3：块级 auto 宽 = 包含块内容宽 − **自身横向外边距**，右缘不越包含块。
                // 少这一步只右移不缩宽 ⇒ 行尾越出版心的字被页面裁掉（丢内容，不是不好看）；
                // 轻路径 [descendContentWidth] 必须同式（重轻两路逐字节一致）。
                val childW = (innerW - marginL - marginR).coerceAtLeast(1)
                if (ch.tag == "#text") {
                    // P6-a: 匿名 run 不环绕（v1 规则保留），但高度回记账（Y 记账不漂移）。
                    // E19: 匿名 run 的样式取**母节点**（容器、或断开的行内包装）——与轻路径
                    // `blockStyleFor`「`#text` 叶取父级样式」同式，字形/行高随行内链不变；
                    // 容器直挂的匿名 run 此处 `styles[el] === style`，与旧行为逐值同。
                    buildAnonymousTextLeaf(ch, styles[ch.parent ?: el] ?: style, breaker, childW, childLeft).also { bs ->
                        pending.consume(bs.sumOf { it.lineHeights.sum() })
                    }
                } else buildBoxTree(ch, styles, breaker, childW, childLeft, classify, hidden, imageLoader, chapterHref, density, genOf, firstLetterOf, pending)
            }
            if (keptSpans != null) pending.restoreGeometry(keptSpans)
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
            // E19（§9.2.1 block-in-inline）：行内子树里埋着块级后代 ⇒ 行内盒在此断开 —— 内联片段各成
            // 匿名 run（母节点 = 该行内包装），块级后代提升为容器流子项。
            else if (hasInlineWrappedBlock(c, classify, hidden)) { flush(); out.addAll(splitBlockInInline(c, classify, hidden)) }
            else appendInlineText(c, classify, sb, hidden)
        }
        flush()
        return out
    }

    /**
     * Appends [el]'s inline/text subtree text to [sb] (non-block only; `<br>` becomes a newline,
     * `<img>` becomes U+FFFC).
     *
     * `br`/`img` 两条必须与 [styledSegments] 的 `walk` **逐条同形**（同序同字面）：本函数的产物
     * 只被用作「**这一段 run 有没有内容**」的判据（`flowChildren`/`cellFlowItems` 的
     * `sb.isNotBlank()` 门、以及轻路径 `styledCharAdvance` 的计数），而真正落字的文本由
     * `styledSegments` 给出。判据与内容不同形 ⇒ run 被判空而丢块：Q15 的 GIMP 图标格
     * `<td rowspan="2"><img/></td>` 就是这样整格零块（`sb` 空 ⇒ run 丢；实际文本是 `￼`）。
     * 嵌套图（`<p>字<span><img/></span>字</p>`）同理：顶层 `<img>` 走 `flowChildren` 的
     * `isReplaceable` 分支自成一叶，嵌套的走这里，不补就是静默丢字符。
     */
    private fun appendInlineText(el: MarkupElement, classify: BlockClassify, sb: StringBuilder, hidden: HiddenCheck = HIDDEN_NONE) {
        when {
            hidden.isHidden(el) -> Unit // display:none inline run contributes no text
            el.isText -> sb.append(el.text)
            el.tag == "br" -> sb.append('\n')
            el.tag == "img" -> sb.append('￼')
            classify.isBlock(el) -> Unit
            else -> for (c in el.children) appendInlineText(c, classify, sb, hidden)
        }
    }

    /**
     * E19（CSS 2.1 §9.2.1 block-in-inline）：[el] 的**行内子树**里是否埋着块级后代（经任意深度的
     * 行内祖先可达）。行内盒一旦包住块级盒，行内盒必须在块级盒处**断开**（前后内联内容各成匿名块、
     * 块级盒提升到最近的块容器流）；整段吸收（[appendInlineText] 与 [styledSegments] 的块跳过）
     * 会把块里的文字整段丢掉。
     *
     * [buildBoxTree] / [enumerateBlockLeaves] / [styledCharAdvance] 三处叶判定同用本谓词，口径恒一
     * （重轻两路叶集 / `globalCharStarts` 不漂移）。替换元素（img）不成块——行内图只按 CSS §10.8
     * 抬所在行高，拆成独立块会让塑形侧对 `img` 根早退、塑出零行（教训 ⑲）。隐藏子树不参与
     * （与 [enumerateBlockLeaves] 的直接块判据同取 [hidden]）。
     */
    private fun hasInlineWrappedBlock(el: MarkupElement, classify: BlockClassify, hidden: HiddenCheck): Boolean =
        el.children.any { c ->
            when {
                hidden.isHidden(c) -> false
                classify.isBlock(c) -> true
                isReplaceable(c) -> false
                else -> hasInlineWrappedBlock(c, classify, hidden)
            }
        }

    /**
     * E19：[el]（行内子树根，调用方已判 [hasInlineWrappedBlock]）拆成容器流项序列 ——
     * 内联片段各自成匿名 `#text` run（**母节点 = 行内包装 [el]**：字形/颜色/祖先装饰链随行内链
     * 不变，与轻路径 `blockStyleFor`「`#text` 叶取父级样式」同式），块级后代按文档序提升为真元素项。
     *
     * 文本/`br`/`img` 三条必须与 [appendInlineText]、[styledSegments] 的 `walk` **逐条同形**
     * （教训 ⑲：两个遍历无法由编译器保证一致，只有显式对齐 + 变异验证）：本函数只决定**流结构**，
     * 落字仍走同一套吸收；纯空白 run 丢弃的判据也与 [flowChildren] 一致。
     */
    private fun splitBlockInInline(el: MarkupElement, classify: BlockClassify, hidden: HiddenCheck): List<MarkupElement> {
        val out = ArrayList<MarkupElement>()
        val sb = StringBuilder()
        fun flush() {
            if (sb.isNotBlank()) out.add(MarkupElement("#text", text = sb.toString()).also { it.parent = el })
            sb.setLength(0)
        }
        fun walk(x: MarkupElement) {
            when {
                hidden.isHidden(x) -> Unit
                x.isText -> sb.append(x.text)
                x.tag == "br" -> sb.append('\n')
                x.tag == "img" -> sb.append('￼')
                classify.isBlock(x) -> { flush(); out.add(x) }
                else -> for (c in x.children) walk(c)
            }
        }
        walk(el)
        flush()
        return out
    }

    /**
     * An anonymous text leaf box for a stray inline run.
     *
     * 排版层（下）: 匿名块只继承**母节点**（容器、或 E19 断开的行内包装）的字形/排版属性
     * （字号/行高/缩进/对齐/颜色等），自身的盒边
     * （margin/padding/border）恒为 0（CSS 2.1 §9.2.1 匿名块 + §8.3.1 边距折叠：容器的边归容器盒，
     * 流程统一加一次）。此前这里直接复用容器样式，容器的 padding/border/margin 被匿名叶与容器盒
     * 各算一次 —— 长生界 `h2{padding-bottom:6rem}` 的章标题→正文间隙被翻倍（252px 而非 141px）。
     * 与轻路径同形：懒级联给合成 `#text` 算出的样式本来就是零边的（[StyleComputer.resolve] 无匹配
     * 规则即初值），修后重轻两路逐字节一致。
     */
    private fun buildAnonymousTextLeaf(
        textEl: MarkupElement,
        style: ComputedStyle,
        breaker: ParagraphBreaker,
        widthPx: Int,
        left: Int,
    ): List<LayoutBox> {
        val leafStyle = style.copy(margin = Edges(), padding = Edges(), border = Edges())
        val contentW = widthPx.coerceAtLeast(1)
        // 断行宽按叶自身横向边扣（恒 0）：widthPx 已是容器内容宽，再扣容器横向边就是双扣。
        val breakW = innerBreakWidth(leafStyle, contentW)
        // P1-2: 匿名 run 整段按容器 white-space 一次归一＋收尾（与轻路径计数同式）。
        val text = normalizeAnonymousRun(textEl.text, leafStyle.whiteSpace)
        // 匿名 stray 叶按构造就是单 face 叶（不在级联表里、无行内子树），无行内字体段。
        val broken = breakLeafLines(breaker, text, leafStyle, breakW, textEl.parent?.tag ?: textEl.tag, emptyList(), 0f)
        return listOf(
            LayoutBox(
                el = textEl, style = leafStyle, contentLeft = left, contentWidth = contentW,
                ranges = broken.map { it.range }, hyphenAtEnd = broken.map { it.hyphenAtEnd },
                squeezeRatios = broken.map { it.squeezeRatio },
                textLength = text.length,
                lineHeights = broken.map { it.heightPx.coerceAtLeast(1) },
                childBoxes = emptyList(),
            ),
        )
    }

    /** [walkCellBlocks] 的返回：容器**内容**之后的静态间隙 ＋ 容器折叠后的底外边距。 */
    private class CellWalk(val nextGap: Int, val bottomMargin: Float)

    /**
     * Q15：[flowChildren] 的**带下标**版本 —— 容器的一个流项。
     *
     * @property el 真实元素（块级子节点）；匿名 run 恒 null。
     * @property anon 本项是否「一段直接内联内容」合成出的 run（`el` 为 null）。
     * @property span 本项消费的 `container.children` 下标区间（匿名 run = 连续片；命名块恒长度 1）。
     */
    private class CellFlowItem(val el: MarkupElement?, val span: IntRange, val anon: Boolean)

    /**
     * Q15：容器的流项序列（与 [flowChildren] **逐项同式**：块级子节点各自成项、连续内联合成
     * 一个匿名 run、纯空白 run 丢弃），但**额外记录每项消费的 `container.children` 下标区间**。
     *
     * 刻意**不**把行内 `<img>` 拆成独立项（`flowChildren` 也不拆）：拆了就得让匿名块
     * 承载 U+FFFC 之外的独立替换语义（`shapeGeometry` 对 `img` 根早退、塑出零行），而
     * 「一个格内 `<img>` 独占一块」并不是 CSS 的语义（§10.8 它只抬所在行高）。保持 run 完整，
     * 塑形侧与修复前逐字节同式。
     *
     * 为什么必须有 span：匿名 run 要「只吸收这一段」时，得按**原始节点身份**排掉区间外的兄弟；
     * 造一个合成 `#text` 元素去比身份对不上（它是新对象），踩过一次：末尾那段文本被并进首个块。
     */
    private fun cellFlowItems(
        container: MarkupElement,
        classify: BlockClassify,
        hidden: HiddenCheck,
    ): List<CellFlowItem> {
        val out = ArrayList<CellFlowItem>()
        val kids = container.children
        var start = -1
        var sb: StringBuilder? = null
        fun flush(endExclusive: Int) {
            val s = sb
            if (s != null && s.isNotBlank() && start >= 0) {
                out.add(CellFlowItem(null, start until endExclusive, anon = true))
            }
            sb = null
            start = -1
        }
        for ((i, c) in kids.withIndex()) {
            if (hidden.isHidden(c)) continue
            if (classify.isBlock(c)) {
                flush(i)
                out.add(CellFlowItem(c, i until i + 1, anon = false))
            } else {
                if (start < 0) { start = i; sb = StringBuilder() }
                appendInlineText(c, classify, sb!!, hidden)
            }
        }
        flush(kids.size)
        return out
    }

    /**
     * Q15：把单元格内容按 CSS 2.1 §16.3（单元格内容是块级流）拆成**块序列**（静态部分）。
     *
     * - 格内**块级**子节点各自成块；格的**直接内联内容**合成一个匿名块（沿用 [flowChildren] 的
     *   stray `#text` 表示）⇒ `<td>字</td>` 与 `<td><p>字</p></td>` 同构（各一块），
     *   `<td>字<p>段</p></td>` 与普通容器的「前/中/后」三段同构；
     * - 中间容器（`<td><div><p>a</p></div></td>` 的 `div`）自身不产行，只贡献自己的
     *   border/padding（横向折进 [TableCellBlock.edgeH]，纵向折进 [TableCellBlock.gapBefore]）
     *   与外边距（§8.3.1：首子顶、末子底各一次）；
     * - 嵌套 `<table>` 与修复前一样零高（修复前也整块不画），不回归。
     *
     * 纵向只算**静态** [TableCellBlock.gapBefore]，行高由 [stackTableCellBlocks] 在断行后补 ⇒
     * 塑形侧（engine-skia `BoxChapterLayouter.fillTableRowCells`）无需重复这份外边距逻辑，
     * 也能独立重算出一致的 [TableCellBlock.top]。
     *
     * @param styles 覆盖格子树（含格自身）的计算样式；重路径喂整章表，轻路径喂 [cellStyledText]
     *   的懒级联快照。
     */
    fun cellBlocks(
        cellEl: MarkupElement,
        styles: Map<MarkupElement, ComputedStyle>,
        classify: BlockClassify,
        hidden: HiddenCheck = HIDDEN_NONE,
        genOf: GenOf = EmptyGen,
        firstLetterOf: FirstLetterOf? = null,
    ): List<TableCellBlock> {
        val cellStyle = styles.getValue(cellEl)
        val out = ArrayList<TableCellBlock>(2)
        // 格自身的 border/padding **不**进块堆叠（行高预算与 `emitCell` 的 insetTop 单算）⇒
        // `selfEdges = false`，起点 gap/inset 皆 0。
        walkCellBlocks(cellEl, cellStyle, styles, classify, hidden, genOf, firstLetterOf, out, 0, 0, 0f, selfEdges = false)
        return out
    }

    private fun walkCellBlocks(
        container: MarkupElement,
        cs: ComputedStyle,
        styles: Map<MarkupElement, ComputedStyle>,
        classify: BlockClassify,
        hidden: HiddenCheck,
        genOf: GenOf,
        firstLetterOf: FirstLetterOf?,
        out: MutableList<TableCellBlock>,
        /** 本容器内容框相对**格内容框**的横向 inset 累计（不含本容器自身的横向边）。 */
        insetH: Int,
        /** 本容器内容框顶相对格内容框顶的 y（不含本容器自身的纵向顶边）。 */
        gap: Int,
        /** 本容器首子可连接的外部折叠底外边距（有纵向边即隔断，调用方传 0f）。 */
        prevBottom: Float,
        /** 本容器的 border/padding 是否参与堆叠（格 = false；中间容器 = true）。 */
        selfEdges: Boolean,
    ): CellWalk {
        val kids = cellFlowItems(container, classify, hidden)
        if (kids.isEmpty()) return CellWalk(gap + if (selfEdges) (cs.border.bottom + cs.padding.bottom).roundToInt() else 0, cs.margin.bottom)
        val topEdge = if (selfEdges) (cs.border.top + cs.padding.top).roundToInt() else 0
        val botEdge = if (selfEdges) (cs.border.bottom + cs.padding.bottom).roundToInt() else 0
        val insetIn = insetH + if (selfEdges) (cs.border.horizontal + cs.padding.horizontal).roundToInt() else 0
        var cur = gap + topEdge
        // 首子的外边距可与容器外的前兄弟折叠，当且仅当容器没有纵向顶边（§8.3.1）。
        var prevM = if (topEdge > 0) 0f else prevBottom
        var first = true
        for (item in kids) {
            val ch = item.el
            val anon = ch == null
            val chs = if (anon) cs else styles.getValue(ch)
            val ownH = if (anon) 0 else (chs.border.horizontal + chs.padding.horizontal).roundToInt()
            val ownV = if (anon) 0 else (chs.border.vertical + chs.padding.vertical).roundToInt()
            val g = cur + (if (first) (if (anon) 0f else chs.margin.top) else collapseMargins(if (anon) 0f else chs.margin.top, prevM)).roundToInt()
            if (anon) {
                // 匿名 run：吸收**整容器**但把本 run **之外**的流项（块级子节点）也当块跳过 ⇒
                // 恰好是「这一段」的文本；收尾（`finishLeaf`）自然吃掉块边界处的尾随空白
                // （与轻路径 `styledCharAdvance.flush()` 的 `isNotBlank` 门同式）。
                // 块 `el` 就是容器（塑形侧据 [TableCellBlock.outsideSiblings] 复原同一段），行内图
                // 配对扫描根同为容器。
                val after = cellRunAfter(container, item)
                val rd = absorbCellBlock(container, styles, after, classify::isBlock, cs, hidden, genOf, firstLetterOf)
                out.add(
                    TableCellBlock(
                        el = container, style = cs,
                        text = rd.first, runs = rd.second, inlineBoxRuns = rd.third,
                        imageRoot = container, outsideSiblings = after,
                        gapBefore = g, edgeH = insetIn, edgeV = 0,
                    ),
                )
            } else if (ch != null) {
                // `anon == (ch == null)`（见 [CellFlowItem]），此处只是把可空 `ch` 收窄成本地非空。
                val blk = ch
                // 「叶状块」= 本块自身**不再产块**（其流子项要么没有，要么全是匿名 run）。
                // 只看 `isEmpty()` 会把 `<td><p>字</p></td>` 的 `p` 当中间容器 ⇒ 产出 `el=#text`
                // 的匿名块，绘制侧 `tag` 丢成 `#text` ⇒ `<td><pre>` 的等宽判定失效。
                val sub0 = cellFlowItems(blk, classify, hidden)
                val leafish = blk.tag == "table" || sub0.isEmpty() || sub0.all { it.anon }
                if (leafish) {
                    // 独图块（块级 img，如 `img{display:block}` 书的 `<td><img></td>`）：吸收按 img 根
                    // 早退得 ""，此处补一个 U+FFFC 槽 —— 与 `appendInlineText` / 轻路径
                    // `styledCharAdvance`（独图计 1 槽）/ 非表格独图叶（textLength 恒 1）同口径。
                    // 否则重路径字符少 1（整章 char 基址漂移），且塑形 `shapeGeometry` 的
                    // replaceable 形无槽可配、绘制侧 `emitCell` 整块跳过（Rust 简介 Ferris 表即此）。
                    val rd = if (isReplaceable(blk)) {
                        Triple("\uFFFC", emptyList<FontRun>(), emptyList<InlineBoxRun>())
                    } else {
                        absorbCellBlock(blk, styles, emptySet(), { false }, chs, hidden, genOf, firstLetterOf)
                    }
                    out.add(
                        TableCellBlock(
                            el = blk, style = chs,
                            text = rd.first, runs = rd.second, inlineBoxRuns = rd.third, imageRoot = blk,
                            gapBefore = g, edgeH = insetIn + ownH, edgeV = ownV,
                        ),
                    )
                } else {
                    // 中间容器：自身不产块。传 `insetIn` / `g`（容器**边框盒**口径），由递归自己
                    // 加 `selfEdges` 的那一次横向/纵向边 —— 传 `insetIn + ownH` 会双算。
                    // 首子因容器顶边而与外部折叠隔断（传 0f）。
                    val sub = walkCellBlocks(blk, chs, styles, classify, hidden, genOf, firstLetterOf, out, insetIn, g, 0f, selfEdges = true)
                    cur = sub.nextGap
                    prevM = if ((chs.border.bottom + chs.padding.bottom) > 0f) chs.margin.bottom else collapseMargins(chs.margin.bottom, sub.bottomMargin)
                    first = false
                    continue
                }
            }
            // 下一块的 `gapBefore` 只带**间隙**（折叠外边距）—— 块自身纵向边已在本块
            // `height` 里、容器纵向边由 `sub.nextGap`/`botEdge` 各带一次；绝不能把 `g` 累计下来，
            // 否则 [stackTableCellBlocks] 会把前一块的顶偏移重复加一遍。
            cur = 0
            prevM = if (anon) 0f else chs.margin.bottom
            first = false
        }
        val tail = cur + botEdge
        if (!selfEdges && tail > 0) {
            // 根容器（格）的**尾部**容器边（`<td><div style="padding:8px">…</div></td>` 的 div 下内边距）
            // 没有后继块可挂 —— 并进末块的 `edgeV`（纵向边，绘制/塑形两侧同一语义）。
            out.lastOrNull()?.let { it.edgeV += tail }
            return CellWalk(0, collapseMargins(cs.margin.bottom, prevM))
        }
        return CellWalk(tail, collapseMargins(cs.margin.bottom, prevM))
    }

    /**
     * Q15 辅助：匿名 run [item] 之外、要额外排掉的**原始**兄弟节点集合（供
     * [TableCellBlock.outsideSiblings] 与 [absorbCellBlock] 共用，故两侧复原出的文本逐字相同）。
     *
     * **必须按原始 `container.children` 的身份**（不能拿合成 `#text` 去比）：[cellFlowItems] 不再
     * 造合成元素了，run 只记下标区间；塑形侧要拿同一份集合经块判据排掉区间外的兄弟。
     *
     * 区间覆盖全部子节点（纯内联容器）时返回空集 ⇒ 与修复前 `absorbStyled(cell.el)` 逐字节相同。
     */
    private fun cellRunAfter(container: MarkupElement, item: CellFlowItem): Set<MarkupElement> {
        val kids = container.children
        if (item.span.first == 0 && item.span.last == kids.size - 1) return emptySet()
        val skip = HashSet<MarkupElement>()
        for ((j, c) in kids.withIndex()) if (j !in item.span) skip.add(c)
        return skip
    }

    /**
     * Q15 辅助：一个块的 **归一文本 ＋ 行内 font 段 ＋ 行内盒 run**，一次 [styledSegments] 三家用
     * （`segments` 同时喂 [collectFontRuns]/[collectInlineBoxRuns] 的 `precomputed`，与
     * `absorbStyled`/`leafFontRuns` 的「同参同段表」纪律一致，下标恒对齐；E15 靠这一步把
     * [TableCellBlock.inlineBoxRuns] 钉在与 [TableCellBlock.text] 同一坐标系上，匿名块无偏移风险）。
     *
     * @param after 本 run 之外要额外排掉的**原始**兄弟节点（见 [cellRunAfter]；空集 = 整块吸收）。
     * @param isBlockOf 真实块级判据；与 [after] **或**起来才算跳过。命名叶传 `{ false }`
     *   （叶状判定已保证无块级子节点，多余地跳会截断文本）。
     */
    private fun absorbCellBlock(
        root: MarkupElement,
        styles: Map<MarkupElement, ComputedStyle>,
        after: Set<MarkupElement>,
        isBlockOf: (MarkupElement) -> Boolean,
        style: ComputedStyle,
        hidden: HiddenCheck,
        genOf: GenOf,
        firstLetterOf: FirstLetterOf? = null,
    ): Triple<String, List<FontRun>, List<InlineBoxRun>> {
        if (root.tag == "table" || root.tag == "img") return Triple("", emptyList(), emptyList())
        val isExcluded: (MarkupElement) -> Boolean = { hidden.isHidden(it) || it in after }
        val isBlock: (MarkupElement) -> Boolean = { it in after || isBlockOf(it) }
        val wsOf: (MarkupElement) -> WhiteSpace = { wsOfNode(it, styles, root) }
        val leafWs = styles[root]?.whiteSpace ?: WhiteSpace.NORMAL
        val segs = styledSegments(root, wsOf, isExcluded, isBlock, leafWs, genOf, { styles[it] }, firstLetterOf = firstLetterOf).segments
        val runs = collectFontRuns(
            root, styles, isExcluded, isBlock, fontBaseOf(root, styles, style), leafWs, genOf,
            precomputed = segs,
        )
        // E15：行内盒与 [text]/[runs] **同一份段表**（`precomputed`）同参收集 ⇒ 匿名块的
        // 「段表坐标 = 块文本坐标」由构造保证，无容器坐标偏移风险。传 `leafWs` 与 styledSegments
        // 的根参同值（precomputed 非空时内部不再用它分段）。
        val boxes = collectInlineBoxRuns(root, styles, isExcluded, isBlock, leafWs, genOf, precomputed = segs)
        return Triple(StyledSegments(segs).text, runs, boxes)
    }

    /**
     * Resolves a `table` into its row leaves. Column widths come from `table-layout`
     * (fixed = equal split, auto = content measure); each cell's blocks are shaped
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
        density: Float = 1f,
        /** P3-c 生成内容查找（空即无，旧路径）。 */
        genOf: GenOf = EmptyGen,
        /** `::first-letter` 伪元素查找（null = 无该规则）。 */
        firstLetterOf: FirstLetterOf? = null,
    ): List<LayoutBox> {
        val model = TableGridModel.build(el)
        if (model.columnCount <= 0 || model.rows.isEmpty()) return emptyList()
        // P1-2: 表格族几何——`border-collapse: collapse` 强制间距 0；separate 模型下
        // 列间＋两侧各留 `border-spacing`（`cellspacing` 属性经级联已映射到该字段）；
        // 每行高含底部 `border-spacing` 纵向间隔（表顶外间隔为已知近似，未单列）。
        // `table-layout: fixed` 均分，`auto`（默认）按单元格内容测宽分列。
        val tableStyle = styles.getValue(el)
        val collapse = tableStyle.borderCollapse
        val spH = if (collapse) 0f else tableStyle.borderSpacingH
        val spV = if (collapse) 0 else tableStyle.borderSpacingV.roundToInt()
        val hideEmpty = tableStyle.emptyCellsHide
        val tableContentW = widthPx.coerceAtLeast(1)
        val gapH = spH.coerceAtLeast(0f).roundToInt()
        // 第一遍：拆各单元格为块序列（Q15）＋内容需求（塑形与分列共用一次拆分，runs 同样只收集一次）。
        class CellBlocks(val cell: TableGridModel.Cell, val style: ComputedStyle, val blocks: List<TableCellBlock>)
        val rowCells = ArrayList<List<CellBlocks>>(model.rows.size)
        val prefs = ArrayList<TableGridModel.CellPref>()
        for (row in model.rows) {
            val list = ArrayList<CellBlocks>(row.cells.size)
            for (cell in row.cells) {
                val cs = styles.getValue(cell.el)
                val blocks = cellBlocks(cell.el, styles, classify, hidden, genOf, firstLetterOf)
                list.add(CellBlocks(cell, cs, blocks))
                prefs.add(tableCellPref(breaker, cell.col, cell.colSpan, blocks, cs))
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
                // 块可用宽 = 格内容宽 − 逐层容器内缩 − 块自身横向边（Q15；`edgeH` 已含块自身）。
                val cellContentW = (cellOuter - (cellStyle.border.horizontal + cellStyle.padding.horizontal).roundToInt()).coerceAtLeast(1)
                val blocks = entry.blocks
                // P1-2: 行字符长度 = 各单元格各块归一长度之和（与轻路径 `styledCharAdvance(tr)`
                // 同式；Q15 前重路径对 `<td><p>…</p></td>` 得 0、轻路径得 N，整表 char 基址漂移）。
                for (b in blocks) {
                    rowChars += b.textLength
                    val blockBreak = (cellContentW - b.edgeH).coerceAtLeast(1)
                    // **Q15**：匿名块的 `el` 是整个容器，本块只占其中一段 —— 伴生收集（基线位移/
                    // 注音/行内图）必须按「排掉 run 外兄弟」的判据走，否则下标会跨块串位。
                    val bCls = if (b.outsideSiblings.isEmpty()) classify else BlockClassify { it in b.outsideSiblings || classify.isBlock(it) }
                    val lines = breakLeafLines(
                        breaker, b.text, b.style, blockBreak, b.el.tag,
                        b.runs,
                        0f,
                        leafBaselineShifts(b.el, styles, bCls, hidden, genOf, firstLetterOf),
                        // E15：行内盒横向 padding/border 也占断行宽 —— 与塑形侧 `shapeGeometry`
                        // 自收的同源 runs 同账（否则重路径格内行数比实排少，行高预算偏小）。
                        b.inlineBoxRuns,
                    )
                    // Same §10.8 image-line adjustment as text leaves (cell images share the bug).
                    val adjHeights = adjustLineHeightsForInlineImages(b.text, lines, b.imageRoot, styles, bCls, hidden, blockBreak, imageLoader, chapterHref, density = density)
                    // P6-b: 叠排注音行增高（与文本叶同式；无注音零回归）。
                    val grownHeights = adjustLineHeightsForRuby(lines, adjHeights, leafRubyRuns(b.el, styles, bCls, hidden, genOf, firstLetterOf))
                    b.ranges = lines.map { it.range }
                    b.hyphenAtEnd = lines.map { it.hyphenAtEnd }
                    b.squeezeRatios = lines.map { it.squeezeRatio }
                    b.lineHeights = grownHeights
                    b.height = grownHeights.sum().coerceAtLeast(0) + b.edgeV
                }
                val contentH = stackTableCellBlocks(blocks)
                val cellH = (contentH.coerceAtLeast(1) +
                    (cellStyle.padding.vertical + cellStyle.border.vertical).roundToInt()).coerceAtLeast(1)
                cells.add(TableCellLayout(cell.el, cell.col, cell.colSpan, columnXs.getOrElse(cell.col) { left }, cellOuter, cellH, cell.isHeader, blocks, cell.rowSpan))
                specs.add(TableGridModel.CellHeight(cell.rowSpan, cellH))
            }
            cellsPerRow.add(cells)
            heightSpecs.add(specs)
            rowCharLens[ri] = rowChars
        }
        val resolvedRowHs = TableGridModel.resolveRowHeights(heightSpecs)
        for ((ri, row) in model.rows.withIndex()) {
            val rowStyle = styles.getValue(row.el)
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
        /**
         * E19 上下文开关：正常流容器 true（与 [flowChildren] 同断开行内盒）；**表格格内容 false** ——
         * 格的字符口径在重轻两路都由 [cellBlocks]（[walkCellBlocks] → [cellFlowItems]）单源给出，
         * 那里 v1 仍按旧式跳过行内内嵌块（[CellFlowItem] 的 `span` 按 `container.children` 原始身份
         * 认段，行内子树里的块无法用下标表达）。进入格内容的两条路（行叶子元素、`table` 元素自身）
         * 都翻此开关并向下继承；两路同址保持一致，绝不此处拆、彼处不拆。调用方恒用默认值。
         */
        hoist: Boolean = true,
    ): Long {
        if (hidden.isHidden(el)) return 0
        if (isReplaceable(el)) return 1
        if (el.tag == "table") {
            val model = TableGridModel.build(el)
            var n = 0L
            for (row in model.rows) {
                for (cell in row.cells) {
                    n += styledCharAdvance(cell.el, styleOf, classify, hidden, genOf, hoist = false)
                }
            }
            // P1-2: caption 字符计入表内（顺序无关求和；叶序由 enumerate 保证）。
            // caption 是正常流块（重路径走 buildBoxTree 正常容器分支），照传本层 [hoist]。
            val cap = model.caption
            if (cap != null) n += styledCharAdvance(cap, styleOf, classify, hidden, genOf, hoist = hoist)
            return n
        }
        if (soleFigureImage(el, classify, hidden) != null) return 1
        val blockChildren = el.children.filter { !hidden.isHidden(it) && classify.isBlock(it) }
        if (blockChildren.isEmpty() && !(hoist && hasInlineWrappedBlock(el, classify, hidden))) {
            if (el.isText) return normalizeAnonymousRun(el.text, styleOf(el.parent ?: el).whiteSpace).length.toLong()
            val wsOf: (MarkupElement) -> WhiteSpace = { n -> styleOf(n).whiteSpace }
            val leafWs = styleOf(el).whiteSpace
            return styledSegments(el, wsOf, hidden::isHidden, classify::isBlock, leafWs, genOf, styleOf).text.length.toLong()
        }
        // 容器：与 flowChildren 同结构（raw 判空），匿名 run 按其母节点样式整段归一。
        // 行叶（`tr`）的子格切到「格内容」口径：重路径的行长由 `cellBlocks` 给出（`cellFlowItems`
        // 不拆行内内嵌块），轻路径必须同口径（见 [hoist]）。
        var n = 0L
        val childHoist = hoist && el.tag != "tr"
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
                n += styledCharAdvance(c, styleOf, classify, hidden, hoist = childHoist)
            } else if (isReplaceable(c)) {
                flush()
                n += 1
            } else if (hoist && hasInlineWrappedBlock(c, classify, hidden)) {
                // E19: 与 flowChildren 同断开（行内子树埋块级后代）；匿名 run 的归一化按其母节点
                // （行内包装）——递归进 `#text` 分支即走 `el.parent?.whiteSpace`，与重路径同值。
                flush()
                for (item in splitBlockInInline(c, classify, hidden)) {
                    n += styledCharAdvance(item, styleOf, classify, hidden, genOf, hoist = hoist)
                }
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
     * 叶在**章字符流**里的文本（D-18 投影单源）：长度必须等于该叶在 `globalCharStarts`
     * 里占的格数（[styledCharAdvance] / 重路径 `LayoutBox.textLength` 同一口径）。
     *
     * [leafText] 只覆盖「可排文字」，而字符流还占两类格：
     * - **替换叶**（`img` 单独成叶）：占 1 格，文本用一个 U+FFFC（与行内图同槽位）；
     * - **表格行叶**（`tr`）：占「格内各块归一文本之和」，文本按格内块序拼出。
     *
     * 漏掉这两类会让后面的每个字都与 `DrawLine.charBase` 错位——点按/长按就会选中
     * 别的段落（真机报「长按乱选」的根因）。
     *
     * @param resolveStyle 轻路径补样式（[styles] 只覆盖叶自身的内联子树，格内容需要现解）；
     *   null = [styles] 覆盖整章（重路径）。
     */
    fun leafStreamText(
        el: MarkupElement?,
        styles: Map<MarkupElement, ComputedStyle>,
        classify: BlockClassify,
        hidden: HiddenCheck = HIDDEN_NONE,
        genOf: GenOf = EmptyGen,
        resolveStyle: ((MarkupElement) -> ComputedStyle)? = null,
    ): String {
        if (el == null) return ""
        if (isReplaceable(el)) return "\uFFFC"
        if (el.tag == "tr") return tableRowStreamText(el, styles, classify, hidden, genOf, resolveStyle)
        return leafText(el, styles, classify, hidden, genOf)
    }

    /** 表格行叶的文本：格内块序拼接（[cellBlocks] 与重路径 `rowChars` 同账）。 */
    private fun tableRowStreamText(
        row: MarkupElement,
        styles: Map<MarkupElement, ComputedStyle>,
        classify: BlockClassify,
        hidden: HiddenCheck,
        genOf: GenOf,
        resolveStyle: ((MarkupElement) -> ComputedStyle)?,
    ): String {
        val sb = StringBuilder()
        // 口径跟着排版走：重路径 `buildTableRows` 对 `display:none` 的行/格照常算格（行盒仍会
        // 画出来），所以这里也不筛；轻路径 `styledCharAdvance` 会把隐藏行/格计 0，那种书上
        // 会由 `ChapterText.kt` 的 `text-drift` 警告暴露（真实书里没有这种表，实测 36 本为 0）。
        for (cell in row.children) {
            if (cell.tag != "td" && cell.tag != "th") continue
            val cellStyles = if (resolveStyle == null) styles else stylesUnder(cell, resolveStyle)
            for (block in cellBlocks(cell, cellStyles, classify, hidden, genOf)) sb.append(block.text)
        }
        return sb.toString()
    }

    /** [root] 及其整棵子树的计算样式快照（轻路径按需级联；重路径直接用整章表）。 */
    private fun stylesUnder(
        root: MarkupElement,
        resolve: (MarkupElement) -> ComputedStyle,
    ): Map<MarkupElement, ComputedStyle> {
        val map = HashMap<MarkupElement, ComputedStyle>()
        fun reg(el: MarkupElement) {
            map[el] = resolve(el)
            for (c in el.children) reg(c)
        }
        reg(root)
        return map
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
        /** `::first-letter` 伪元素查找（null = 无该规则；章节开关关掉即整个传 null）。 */
        firstLetterOf: FirstLetterOf? = null,
    ): List<ColorRun> {
        if (el == null) return emptyList()
        val seed = styles[el]?.colorHex?.let(::cssHexToArgb)
        val rootWs = styles[el]?.whiteSpace ?: el.parent?.let { styles[it]?.whiteSpace } ?: WhiteSpace.NORMAL
        return collectColorRuns(el, styles, hidden::isHidden, classify::isBlock, seed, rootWs, genOf, firstLetterOf = firstLetterOf)
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
        /** `::first-letter` 伪元素查找（null = 无该规则；章节开关关掉即整个传 null）。 */
        firstLetterOf: FirstLetterOf? = null,
    ): List<FontRun> {
        if (el == null) return emptyList()
        val rootWs = styles[el]?.whiteSpace ?: el.parent?.let { styles[it]?.whiteSpace } ?: WhiteSpace.NORMAL
        return collectFontRuns(el, styles, hidden::isHidden, classify::isBlock, fontBaseOf(el, styles, styles[el]), rootWs, genOf, firstLetterOf = firstLetterOf)
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
        /** `::first-letter` 伪元素查找（null = 无该规则；章节开关关掉即整个传 null）。 */
        firstLetterOf: FirstLetterOf? = null,
    ): List<BaselineShift> {
        if (el == null) return emptyList()
        val rootWs = styles[el]?.whiteSpace ?: el.parent?.let { styles[it]?.whiteSpace } ?: WhiteSpace.NORMAL
        return collectBaselineShifts(el, styles, hidden::isHidden, classify::isBlock, rootWs, genOf, firstLetterOf = firstLetterOf)
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
        /** `::first-letter` 伪元素查找（null = 无该规则；章节开关关掉即整个传 null）。 */
        firstLetterOf: FirstLetterOf? = null,
    ): List<RubyRun> {
        if (el == null) return emptyList()
        val rootWs = styles[el]?.whiteSpace ?: el.parent?.let { styles[it]?.whiteSpace } ?: WhiteSpace.NORMAL
        return collectRubyRuns(el, styles, hidden::isHidden, classify::isBlock, rootWs, genOf, firstLetterOf = firstLetterOf)
    }

    /**
     * 下划线伴生：[leafText] 同一字符串上的下划线区间（[TextDecorationRun]，叶子全文本坐标系）。
     * 无下划线叶回空表（绘制零回归）。调用方必须传与塑形同一套 [styles]/[classify]/[hidden]。
     */
    fun leafTextDecorationRuns(
        el: MarkupElement?,
        styles: Map<MarkupElement, ComputedStyle>,
        classify: BlockClassify,
        hidden: HiddenCheck = HIDDEN_NONE,
        /** P3-c 生成内容查找（空即无，旧路径）。 */
        genOf: GenOf = EmptyGen,
        /** `::first-letter` 伪元素查找（null = 无该规则；章节开关关掉即整个传 null）。 */
        firstLetterOf: FirstLetterOf? = null,
    ): List<TextDecorationRun> {
        if (el == null) return emptyList()
        val rootWs = styles[el]?.whiteSpace ?: el.parent?.let { styles[it]?.whiteSpace } ?: WhiteSpace.NORMAL
        return collectTextDecorationRuns(el, styles, hidden::isHidden, classify::isBlock, rootWs, genOf, firstLetterOf = firstLetterOf)
    }

    /**
     * 行内盒伴生：[leafText] 同一字符串上的行内盒区间（[InlineBoxRun]，叶子全文本坐标系）。
     * 无背景/边框叶回空表（绘制零回归）。调用方必须传与塑形同一套 [styles]/[classify]/[hidden]。
     */
    fun leafInlineBoxRuns(
        el: MarkupElement?,
        styles: Map<MarkupElement, ComputedStyle>,
        classify: BlockClassify,
        hidden: HiddenCheck = HIDDEN_NONE,
        /** P3-c 生成内容查找（空即无，旧路径）。 */
        genOf: GenOf = EmptyGen,
        /** `::first-letter` 伪元素查找（null = 无该规则；章节开关关掉即整个传 null）。 */
        firstLetterOf: FirstLetterOf? = null,
    ): List<InlineBoxRun> {
        if (el == null) return emptyList()
        val rootWs = styles[el]?.whiteSpace ?: el.parent?.let { styles[it]?.whiteSpace } ?: WhiteSpace.NORMAL
        return collectInlineBoxRuns(el, styles, hidden::isHidden, classify::isBlock, rootWs, genOf, firstLetterOf = firstLetterOf)
    }

    /**
     * E10 伴生：[leafText] 同一字符串上的 `visibility: hidden` 覆盖区间（叶文本坐标系）。
     *
     * 隐藏字符**不落墨、但保留占位**（与 [HiddenCheck] 的 `display:none` 去盒语义相反）；
     * 空表 = 全叶可见（绘制零回归）。调用方必须传与塑形同一套 [styles]/[classify]/[hidden]。
     */
    fun leafVisibilityHiddenRuns(
        el: MarkupElement?,
        styles: Map<MarkupElement, ComputedStyle>,
        classify: BlockClassify,
        hidden: HiddenCheck = HIDDEN_NONE,
        /** P3-c 生成内容查找（空即无，旧路径）。 */
        genOf: GenOf = EmptyGen,
        /** `::first-letter` 伪元素查找（null = 无该规则；章节开关关掉即整个传 null）。 */
        firstLetterOf: FirstLetterOf? = null,
    ): List<IntRange> {
        if (el == null) return emptyList()
        val base = styles[el]?.visibilityHidden
            ?: el.parent?.let { styles[it]?.visibilityHidden }
            ?: false
        // 快筛留给调用方（本章/本子树有没有 `visibility` 声明）；这里只做叶自身已知隐藏时的短路。
        val rootWs = styles[el]?.whiteSpace ?: el.parent?.let { styles[it]?.whiteSpace } ?: WhiteSpace.NORMAL
        return collectVisibilityHiddenRuns(el, styles, base, hidden::isHidden, classify::isBlock, rootWs, genOf, firstLetterOf = firstLetterOf)
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
     * B（块级指定宽）单源：CSS 2.1 §10.3.3 —— `width` 非 auto 时，块盒 border-box 宽 = 指定内容宽
     * （content-box 口径）+ 自身横向 padding+border；父给的可用宽（availBorderBoxW，=包含块内容宽
     * − 自身横向外边距）只用于解 `%`（% 按包含块内容宽 = avail + 自身横向外边距）。
     * 返回 null = `width: auto`（沿用父给的可用宽，行为与既有完全一致）。
     */
    fun specifiedBorderBoxWidth(style: ComputedStyle?, availBorderBoxW: Int): Int? {
        val s = style ?: return null
        val spec = s.widthPx
            ?: s.widthPct?.let { pct ->
                val cbW = availBorderBoxW + s.margin.left.roundToInt() + s.margin.right.roundToInt()
                pct / 100f * cbW
            } ?: return null
        return (spec.roundToInt() + (s.padding.horizontal + s.border.horizontal).roundToInt()).coerceAtLeast(1)
    }

    /**
     * E23 / E23-e 单源：CSS 2.1 §10.6.3 / §10.7 —— 盒子的**内容盒高** = max(在流内容高, 竖向下限)，
     * 竖向下限 = max(`height`, `min-height`)（见 [specifiedVerticalFloor]）。
     *
     * 内容比下限高时按内容（溢出照排，不裁；裁剪是 `overflow` 的事）。
     * 两者皆 auto / 百分比（包含块高不定，解析期已丢弃）返回自然高 ⇒ 逐值不变。
     * **只补在流内容的竖向占位**（盒子高 ⇒ 底色/边框/裁剪区与后续内容起点），不动任何行。
     *
     * 注：`max-height` **只解析不消费**（裁定不做 —— 浏览器语义是「裁切 + 溢出」并要连带决定内容被
     * 收掉后游标往哪走，与这里「只多不少」不是一套机理；见 `docs/引擎欠账-对齐浏览器语义.md` §2.1 D 表）。
     */
    fun specifiedContentHeight(style: ComputedStyle?, naturalH: Int): Int =
        specifiedVerticalFloor(style)?.let { maxOf(naturalH, it) } ?: naturalH

    /**
     * E23-e 单源：盒子的**竖向下限**（content-box 口径）= max(`height`, `min-height`) 取整；两者皆
     * 未声明（`auto` / 百分比被解析期丢弃）⇒ null。`height:0` / `min-height:0` 是声明过但下限为 0，
     * 返回 0 而非 null —— 调用方据此区分「没有下限」与「下限为零」。
     *
     * Chromium 实测（2026-10-20，`--headless=new`，内容 20px + 末子 `margin-bottom:20`）：
     * `min-height:120` ⇒ 盒高 120、后随内容贴盒底（末子下边距被收进盒内）；
     * `min-height:10`（低于内容）⇒ 盒高 20、下边距照常外塌。
     * 折叠判据据此**分两档**（见 `emit` 容器分支与 [consecutiveLeafAdvance]）：`height` 声明即断链，
     * `min-height` 只在本下限**大于自然内容高**（真的撑高）时断链。
     */
    fun specifiedVerticalFloor(style: ComputedStyle?): Int? {
        val s = style ?: return null
        val h = s.heightPx?.roundToInt()?.coerceAtLeast(0)
        val m = s.minHeightPx?.roundToInt()?.coerceAtLeast(0)
        if (h == null && m == null) return null
        return maxOf(h ?: 0, m ?: 0)
    }

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
     * by the horizontal padding+border of every ancestor container down to (but not including) [el],
     * **and by [el]'s own horizontal margins** (CSS 2.1 §10.3.3: 块级 auto 宽 = 包含块内容宽 − 自身横向
     * 外边距 ⇒ 右缘不越包含块；[marginsOf] 供**左+右各自取整后求和**，与重路径
     * [`buildBoxTree`] 的 `innerW - marginL - marginR` 逐字节同式)。
     *
     * 少扣外边距的后果不是「不好看」：盒只被右移而不缩宽，行尾越出版心的字被页面裁掉（丢内容）。
     * This is byte-identical to the recursive descent [buildBoxTree] performs top-down, and is what the
     * light (lazy-cascade) path uses instead of re-deriving it locally. [edgesOf] supplies each
     * element's horizontal padding+border in px (heavy passes the cascade map; light passes its lazy
     * style resolver).
     */
    fun descendContentWidth(
        el: MarkupElement,
        rootW: Int,
        edgesOf: (MarkupElement) -> Int,
        marginsOf: (MarkupElement) -> Int,
        styleOf: (MarkupElement) -> ComputedStyle? = { null },
    ): Int {
        val p = el.parent ?: return lightSpecifiedWidth(el, rootW, styleOf) ?: rootW
        val parentW = descendContentWidth(p, rootW, edgesOf, marginsOf, styleOf)
        val ownMargin = if (el.tag == "#text") 0 else marginsOf(el)
        val avail = innerFromEdges(edgesOf(p) + ownMargin, parentW)
        return lightSpecifiedWidth(el, avail, styleOf) ?: avail
    }

    /**
     * 轻路径的指定宽消费：**与重路径 [buildBoxTree] 的闸门严格同判据**，多一个元素类型
     * 消费、少一个不消费都会让两路盒宽分叉（重/轻几何必须逐值一致）。
     *
     * 不在此消费（宽度另有归属）：`table` 与 `tr`/`td`/`th`（表格网格）、替换元素
     * （`img` 的 `usedReplacedSize`）、浮动元素（浮动排位模型）。逐条与 `buildBoxTree` 对齐。
     */
    private fun lightSpecifiedWidth(
        el: MarkupElement,
        availBorderBoxW: Int,
        styleOf: (MarkupElement) -> ComputedStyle?,
    ): Int? {
        if (el.tag == "table" || el.tag == "tr" || el.tag == "td" || el.tag == "th" || isReplaceable(el)) return null
        val s = styleOf(el) ?: return null
        if (s.floatSide != orilumn.reader.engine.css.FloatSide.NONE) return null
        return specifiedBorderBoxWidth(s, availBorderBoxW)
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
        /**
         * E23-e：容器 [el] 的竖向下限是否**真的生效**（撑高了盒子）。生效 ⇒ 末子下边距收进盒内、
         * 父↔末子底折叠断链（重路径 `emit` 读 `LayoutBox.verticalFloorBound`，两路同判据）。
         * 默认 `{ false }` = 无下限（含 `min-height` 低于内容的情形），行为与旧路径逐字节一致。
         */
        floorBinds: (MarkupElement) -> Boolean = { false },
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
        // margins of the `.last()`-child descent: each edge-free, auto-height ancestor's margin joins
        // the group, and an edge-full (or specified-height) container stops the fold with its own margin
        // folding in last (its children's margins are dropped). Skipped entirely when A_m carries bottom
        // border/padding or a specified height (emit then returns its raw margin-bottom).
        val aStyle = styleOf(pathA[ia - 1])
        if ((aStyle.border.bottom + aStyle.padding.bottom).roundToInt() == 0 &&
            aStyle.heightPx == null && !floorBinds(pathA[ia - 1])
        ) {
            var j = ia - 2
            while (j >= 0) {
                val parentStyle = styleOf(pathA[j + 1])
                if ((parentStyle.border.bottom + parentStyle.padding.bottom).roundToInt() > 0) break
                // E23-b/E23-e：§8.3.1 的父↔末子底折叠要求父 `height:auto`（且竖向下限没真的生效）；
                // 指定高 / 生效的下限与底边同权断链（`emit` 侧见 [lastChildBottomMargin]，两处同一判据）。
                if (parentStyle.heightPx != null || floorBinds(pathA[j + 1])) break
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
     * 渲染层：[owner] 内容顶到其首叶 [firstLeaf] 边框顶的距离（首子链逐层 margin＋edge，
     * 与 [consecutiveLeafAdvance] B 侧同门：edge-free 段折叠一次、遇 edge 即结算、
     * 每次结算单次 round）。轻量路径凭它把背景带向上补到容器内容顶，再由
     * [backgroundBandExtent] 去自身 edge 即容器真顶（首叶 margin-top 段本就画容器底，
     * 如 `blockquote > h2` 的 2rem；owner 自身 margin/edge 碰都不碰，更不进底）。
     * [firstLeaf] 不在首子链上（窗口半截、首叶在窗外）即 null，调用方保持旧行为（页带裁剪兜底）。
     * 自属（owner === firstLeaf）即 0。
     */
    fun firstChildDescentTop(
        owner: MarkupElement,
        firstLeaf: MarkupElement,
        styleOf: (MarkupElement) -> ComputedStyle,
        /**
         * true = 首叶按**匿名文本叶**处理：末一步的 `margin.top` 不计（文本叶盒的盒属性已清零，
         * 其样式里本就没有 margin；置位只是防 `styleOf` 换成宿主样式源时把宿主 margin 重复计入）。
         * 绘制侧调它的地方保持 false（窗口页带口径不变）。
         */
        zeroLeafBox: Boolean = false,
    ): Int? {
        if (owner === firstLeaf) return 0
        // 上行收链：leaf→owner，中途断开即非后代（理论上不会发生，防呆）。
        val up = ArrayList<MarkupElement>()
        var n: MarkupElement? = firstLeaf
        while (n != null && n !== owner) {
            up.add(n)
            n = n.parent
        }
        if (n !== owner) return null
        // 注意：只算内容顶以下的链（owner 自身 edge 由 backgroundBandExtent 去，
        // 在这里重复计入会把带子顶进 owner 自身 margin，吃掉与上段的间隙）。
        var dist = 0
        var merged = 0f
        for (i in up.size - 1 downTo 0) {
            val el = up[i]
            val parent = if (i + 1 < up.size) up[i + 1] else owner
            if (!isFirstSignificantChild(parent, el)) return null
            if (i > 0) {
                val s = styleOf(el)
                merged = collapseMargins(merged, s.margin.top)
                val topEdges = (s.border.top + s.padding.top).roundToInt()
                if (topEdges > 0) {
                    dist += merged.roundToInt()
                    dist += topEdges
                    merged = 0f
                }
            } else {
                val leafMargin = if (zeroLeafBox) 0f else styleOf(el).margin.top
                merged = collapseMargins(merged, leafMargin)
                dist += merged.roundToInt()
            }
        }
        return dist
    }

    /**
     * **页首块组的外沿盒**（v4 口径，2026-10-15）：从页首叶 [leaf] 沿「首有效子」链上溯，取链上
     * **最外层的可见盒**（绘制侧真会在页上出矩形的那种：背景色/背景图/边框/盒阴影）。
     *
     * 判据为什么要与**绘制**同源：页首帧若切进这个盒的盒内，绘制侧画在栅格上沿的部分就被裁掉一块
     * ——真书 `.fm-sidebar-block{background:#EEE;padding:10px 20px}` 里
     * `.fm-sidebar-title{margin:1em 0;padding:15px 1px -10px}` 的段前距与容器 padding 就是这么被
     * 吃掉的（用户报「有背景色的父容器里，第一个子元素的 margin-top 被省掉」）。只带 padding 而
     * 不可见（无背景/无边框/无阴影）的容器**不设外沿**：它的 top 边在栅格上沿被吞掉看不出差别，
     * 留着反而白占页高。
     *
     * 链在半途断开（该层不是父的**首有效子**）即止：那时折叠组在更低层结算，没有「外沿」可言。
     * 返回 null = 链上无可见盒（或一路到顶都没有）⇒ 调用方退回「叶自身 top 边」口径（v3）。
     */
    fun pageStartOwner(leaf: MarkupElement, styleOf: (MarkupElement) -> ComputedStyle?): MarkupElement? {
        var child = leaf
        var owner: MarkupElement? = null
        while (true) {
            val parent = child.parent ?: break
            if (!isFirstSignificantChild(parent, child)) break
            val s = styleOf(parent) ?: break // 样式缺项（盒树无该元素）⇒ 无从判可见，止步
            if (s.hasPaintedSlab() || s.boxShadow != null) owner = parent // 越往上越外，取到顶
            child = parent
        }
        return owner
    }

    /**
     * **页首缺口**（v4）：从外沿盒 X 的**边框顶**到叶 [leaf] 首行行顶的距离
     * = `X 自身 top 边 + firstChildDescentTop(X, leaf) + 叶自身 top 边`。
     *
     * X 边框盒**以下**的一切（X 的 padding、中间容器的边距、**首子自己的** `margin-top`/`padding-top`）
     * 一律画在本页页首；**X 之上**的折叠 `margin-top`（X 自身 margin + 与上一块的段间折叠组）并入
     * 页边距、两页都不画（v3「一条缺口不许两页各收一次」不变，只是被吞的那段从「叶的 margin」上移到
     * 「X 的 margin」）。无可见祖先盒（或链在首子处断开）⇒ 退回叶自身 top 边（= v3：整段段前距并入）。
     *
     * 文本叶（匿名块）自身盒属性按重路径约定清零（`emit` 给文本叶盒清零盒属性；轻路径
     * `ownTopEdges` 对 `isText` 同样清零）。
     */
    fun pageTopLead(leaf: MarkupElement, styleOf: (MarkupElement) -> ComputedStyle?): Int {
        val leafStyle = styleOf(leaf)
        val leafEdges =
            if (leaf.isText || leafStyle == null) 0
            else (leafStyle.border.top + leafStyle.padding.top).roundToInt()
        val owner = pageStartOwner(leaf, styleOf) ?: return leafEdges
        if (owner === leaf) return leafEdges
        val ownerStyle = styleOf(owner) ?: return leafEdges
        val ownerEdges = (ownerStyle.border.top + ownerStyle.padding.top).roundToInt()
        // 走到 owner 的整条链在上面上溯时都已取到非空样式，这里的兜底实际不可达。
        // 文本叶：末一步不计宿主 margin（`resolveStyle(#text)` 与盒树上文本叶盒的样式都无 margin，
        // 这里再挡一道，防 `styleOf` 换成宿主样式时把宿主 margin 重复计入）。
        val descent = firstChildDescentTop(owner, leaf, { e -> styleOf(e) ?: UNKNOWN_STYLE }, zeroLeafBox = leaf.isText)
            ?: return leafEdges
        return ownerEdges + descent + leafEdges
    }

    /** 样式缺项兜底：无任何可见盒属性（正常路径取不到它，见 [pageTopLead]）。 */
    private val UNKNOWN_STYLE = ComputedStyle(fontSizePx = 0f, lineHeightRatio = 0f)

    /** 首个有效子（跳过纯空白 #text；与 Selector 相邻语义同口径，保守：行内元素也算阻挡）。 */
    private fun isFirstSignificantChild(parent: MarkupElement, el: MarkupElement): Boolean {
        for (c in parent.children) {
            if (c === el) return true
            if (c.tag == "#text" && c.text.isBlank()) continue
            return false
        }
        return false
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
        // E23-b 同侧**行装箱**（CSS 2.1 §9.5.1）：本行已放悬浮的宽度和（左/右各一）。
        private var lRowW: Int = 0
        private var rRowW: Int = 0
        /** 最近一次 [register] 的落点：本盒在本侧内的 x 偏移 —— 左浮动自内容左起，右浮动自内容右起
         *  **含自身宽**（右浮动依次向左排列）。 */
        var lastX: Int = 0
        /** 最近一次 [register] 的盒是否**另起一行**（false = 与本行已放悬浮并排，同顶）。 */
        var lastNewRow: Boolean = true

        fun activeL(): Boolean = lW > 0 && lH > lUsed
        fun activeR(): Boolean = rW > 0 && rH > rUsed
        fun remL(): Int = (lH - lUsed).coerceAtLeast(0)
        fun remR(): Int = (rH - rUsed).coerceAtLeast(0)

        /**
         * 注册本侧跨度：**同侧能并排就并排**（同一行放得下 ⇒ x 偏移 = 本行已放宽度，同顶），
         * 放不下（或本行已被文本越过）才另起一行（新行顶 = 当前文本游标；旧余量 + 新高＝既有堆叠口径）。
         * 异侧覆盖本侧；悬浮盒零行高不占 Y，Y 前进只由后随文本记账。
         *
         * @param availW 本悬浮包含块的**内容宽**：同行容量上限（还要再减异侧本行已占宽度）。
         * @return 本盒是否另起一行（同 [lastNewRow]）。
         */
        fun register(side: FloatSide, floatW: Int, floatH: Int, availW: Int = Int.MAX_VALUE): Boolean = when (side) {
            FloatSide.LEFT -> {
                val other = if (activeR()) rRowW else 0
                if (lH > 0 && lUsed == 0 && lRowW > 0 && lRowW + floatW + other <= availW) {
                    // 并排：同顶加宽，行高取两者较大。
                    lastX = lRowW; lRowW += floatW
                    lW = maxOf(lW, lRowW); lH = maxOf(lH, floatH)
                    lastNewRow = false
                } else {
                    lW = maxOf(lW, floatW); lH = remL() + floatH; lUsed = 0
                    lRowW = floatW; lastX = 0; lastNewRow = true
                }
                lastNewRow
            }
            FloatSide.RIGHT -> {
                val other = if (activeL()) lRowW else 0
                if (rH > 0 && rUsed == 0 && rRowW > 0 && rRowW + floatW + other <= availW) {
                    rRowW += floatW
                    lastX = rRowW
                    rW = maxOf(rW, rRowW); rH = maxOf(rH, floatH)
                    lastNewRow = false
                } else {
                    rW = maxOf(rW, floatW); rH = remR() + floatH; rUsed = 0
                    rRowW = floatW; lastX = floatW; lastNewRow = true
                }
                lastNewRow
            }
            else -> true
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
            lW = 0; lUsed = 0; lH = 0; lRowW = 0
        }

        fun dropRight() {
            rW = 0; rUsed = 0; rH = 0; rRowW = 0
        }

        fun dropAll() {
            dropLeft(); dropRight()
        }

        /**
         * 进入 BFC 根（`overflow != visible`）前的**登记几何**快照：只存侧/宽/高/行装箱，
         * **不存**纵向消耗（盒内内容照样把外层浮动往下推）。收尾还原 ⇒ 盒内浮动不逃出去收窄后随内容。
         */
        fun snapshotGeometry(): IntArray =
            intArrayOf(lW, lH, rW, rH, lRowW, rRowW, lastX, if (lastNewRow) 1 else 0)

        fun restoreGeometry(s: IntArray) {
            lW = s[0]; lH = s[1]; rW = s[2]; rH = s[3]
            lRowW = s[4]; rRowW = s[5]; lastX = s[6]; lastNewRow = s[7] != 0
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
     * **E15**：块内行内盒（`<span>` 的 padding/border）的横向内边距也折进两者 —— 度量必须与
     * 断行（`buildTableRows` 的 `breakLeafLines` 同传 [TableCellBlock.inlineBoxRuns]）同账，
     * 否则 auto 列比格内实排窄，行内盒被挤（量画分家）。与悬浮盒 [floatTextWidth] 的同传口径一致。
     *
     * 塑形参数与断行侧 `breakLeafLines` 同式（`monospace || tag == "pre"`；`white-space` 不换行的格
     * 其 min-content 即 max-content——浏览器同式），故「量」与「排」不会分家。
     *
     * **Q15**：格内是**块序列**（[cellBlocks]），CSS 2.1 §10.5 的单元格内容宽度取块流各子块贡献的
     * **最大**（min-content 即最长不可断单元在块流里的最大者）⇒ 逐块度量后取 max，不是求和。
     * 每块的边距 = 格自身横向边 ＋ [TableCellBlock.edgeH]（逐层容器内缩＋块自身横向边），
     * 故 `<td><div style="padding:8px"><p>字</p></div></td>` 的 min-content 含那 16px。
     */
    fun tableCellPref(
        breaker: ParagraphBreaker,
        col: Int,
        colSpan: Int,
        blocks: List<TableCellBlock>,
        cellStyle: ComputedStyle,
    ): TableGridModel.CellPref {
        val cellEdges = cellStyle.padding.horizontal + cellStyle.border.horizontal
        // 指定宽下限（`th width=100px` 等表示型属性经级联已进 widthPx，content 口径，
        // 故加边距成 border-box；百分比在度量期无容器可解，暂略）。取**格**自身的声明，
        // 格内块不参与（浏览器同式：列宽下限由格属性给出）。
        val specified = ((cellStyle.widthPx ?: 0f).coerceAtLeast(0f) + cellEdges).takeIf { cellStyle.widthPx != null && cellStyle.widthPx > 0f } ?: 0f
        var maxContent = 0f
        var minContent = 0f
        var any = false
        for (b in blocks) {
            if (b.text.isEmpty()) continue
            any = true
            val s = b.style
            val tag = b.el.tag
            val mono = s.monospace || tag == "pre"
            val mx = breaker.preferredWidth(
                b.text, s.fontSizePx, s.fontFamilies, s.fontWeight, s.italic, mono, b.runs,
                inlineBoxRuns = b.inlineBoxRuns,
            )
            val mn = if (WhiteSpaceNormalize.wraps(s.whiteSpace)) {
                breaker.minContentWidth(
                    b.text, s.fontSizePx, s.fontFamilies, s.fontWeight, s.italic, mono, b.runs,
                    inlineBoxRuns = b.inlineBoxRuns,
                )
            } else {
                mx
            }
            if (mx > maxContent) maxContent = mx
            if (mn > minContent) minContent = mn
        }
        if (!any) return TableGridModel.cellPref(col, colSpan, 0f, 0f, cellEdges, specified)
        // 块自身的横向边（逐层容器内缩 + 块自身）并入「内容宽」，`cellPref` 只再加**格**自身的边。
        val blockInset = blocks.maxOfOrNull { it.edgeH } ?: 0
        return TableGridModel.cellPref(col, colSpan, maxContent + blockInset, minContent + blockInset, cellEdges, specified)
    }

    /**
     * P6-a: 文本悬浮塑形宽（CSS 2.1 §10.3.5 简化）：指定宽（px / % 解容器）优先，
     * 否则 shrink-to-fit（breaker 首选测宽，分段取最大，上限版心）。
     *
     * **E24：返回的是内容宽**（指定 `width` 按 CSS 内容盒口径；shrink-to-fit 的测宽本来就是内容宽）。
     * 排位 / 环绕 / 盒宽都是 border-box 口径，调用方必须过 [floatBorderBoxWidth] 转换
     * （断行宽再由 [innerBreakWidth] 还原成内容宽）。替换型悬浮的 `replacedUsedSize` 同是内容宽。
     */
    fun floatTextWidth(
        style: ComputedStyle,
        text: String,
        contentW: Int,
        breaker: ParagraphBreaker,
        fontRuns: List<FontRun>,
        /** 行内盒（横向 `padding`/`border` 占宽，与断行侧同源）：悬浮盒按含内边距的宽度定尺。 */
        inlineBoxRuns: List<InlineBoxRun> = emptyList(),
    ): Int {
        val cw = contentW.coerceAtLeast(1)
        style.widthPx?.let { return it.roundToInt().coerceIn(1, cw) }
        style.widthPct?.let { return (it / 100f * cw).roundToInt().coerceIn(1, cw) }
        var pref = 0f
        var off = 0
        for (para in text.split('\n')) {
            if (para.isNotEmpty()) {
                pref = maxOf(
                    pref,
                    breaker.preferredWidth(
                        para, style.fontSizePx, emptyList(), 400, false, false, fontRuns,
                        sliceInlineBoxRuns(inlineBoxRuns, off, off + para.length),
                    ),
                )
            }
            off += para.length + 1
        }
        if (pref <= 0f) pref = cw.toFloat()
        return pref.roundToInt().coerceIn(1, cw)
    }

    /**
     * E24（CSS 2.1 §10.3.5 内容盒口径）：悬浮盒的 **border-box 宽** = 内容宽 + 自身横向 padding/border。
     *
     * Chromium 实测（2026-10-20，`.fl{width:30px;border:1px}`）：底色 30px、边框**外画**、外框
     * 32px；并排间距 / 环绕窄宽都按 32px 算（我方原先按 30px，每盒少自身横边 2px）。
     * `LayoutBox.contentWidth` 与 [FloatPending.register] 的记账都是 border-box 口径，故这里是
     * [innerBreakWidth] 的反运算：断行宽再由 `innerBreakWidth(style, borderBox)` 还原成内容宽，
     * 量画同源（[floatTextWidth] 与 `replacedUsedSize` 都只给内容宽）。
     */
    fun floatBorderBoxWidth(style: ComputedStyle, contentW: Int): Int =
        (contentW + (style.padding.horizontal + style.border.horizontal).roundToInt()).coerceAtLeast(1)

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
        density: Float = 1f,
    ): FloatLead? {
        if (prev == null || prev.tag != "img") return null
        val side = styleOf(prev).floatSide
        if (side == FloatSide.NONE) return null
        // 可环绕：真文本叶（替换/表格/匿名合成/容器一律不包；`#text` 真节点永不为叶）。
        if (isReplaceable(leaf) || leaf.tag == "table" || leaf.tag == "tr" || leaf.tag == "#text") return null
        if (leaf.parent?.children?.contains(leaf) != true) return null
        val lstyle = styleOf(leaf)
        val cw = contentW.coerceAtLeast(1)
        val (floatContentW, floatH) = replacedUsedSize(
            prev, styleOf(prev), innerBreakWidth(styleOf(prev), cw), imageLoader, chapterHref, density = density,
        )
        // E24：环绕窄宽按悬浮盒的 **border-box** 宽（+ 自身横边）——与 `FloatPending.register` 同口径。
        val floatW = floatBorderBoxWidth(styleOf(prev), floatContentW)
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
        /** P3：分页层的高度上限（口径乙）；null = 不约束。见 [ReplacedFitBudget]。 */
        fitMaxH: Int? = null,
        density: Float = 1f,
    ): Pair<Int, Int> {
        val intrinsic = intrinsicSizeOf(el, imageLoader, chapterHref, density)
        return style.usedReplacedSize(intrinsic?.first, intrinsic?.second, containingW, fitMaxH)
    }

    /**
     * Resolved pixel height of a replaceable (img) block. Delegates to [replacedUsedSize], i.e.
     * standard CSS sizing: `auto/auto` yields the intrinsic height (CSS 2.1 §10.3.4 sizes
     * block-level replaced exactly like inline replaced), with only `max-width`/`min-*` able
     * to shrink/grow it via the §10.4 table. `fitMaxH` is the pagination layer's page-fit ceiling
     * (口径乙) — it folds into the same §10.4 table (never a CSS declaration).
     */
    fun replaceableHeightOf(
        el: MarkupElement,
        style: orilumn.reader.engine.css.ComputedStyle,
        contentWidthPx: Int,
        imageLoader: ImageBoundsReader? = null,
        chapterHref: String = "",
        /** P3：分页层的高度上限（口径乙）；null = 不约束。见 [ReplacedFitBudget]。 */
        fitMaxH: Int? = null,
        density: Float = 1f,
    ): Int = replacedUsedSize(el, style, contentWidthPx.coerceAtLeast(1), imageLoader, chapterHref, fitMaxH, density).second

    /** Intrinsic (w, h) px of an `<img>`: HTML attrs (both numeric) → binary bounds → null.
     *  A single HTML attr is NOT an intrinsic side (the ratio is unknown) — it already reaches
     *  sizing through the cascade ([Cascade.htmlAttrOrigin] → `widthPx`/`heightPx`), so reporting
     *  it here as well would double-count. CSS is deliberately excluded (it flows through
     *  [replacedUsedSize]'s style params). */
    fun intrinsicSizeOf(
        el: MarkupElement,
        imageLoader: ImageBoundsReader? = null,
        chapterHref: String = "",
        density: Float = 1f,
    ): Pair<Int, Int>? {
        val aw = el.attrs["width"]?.toFloatOrNull()
        val ah = el.attrs["height"]?.toFloatOrNull()
        if (aw != null && aw > 0f && ah != null && ah > 0f) return aw.roundToInt() to ah.roundToInt()
        if (imageLoader != null && chapterHref.isNotBlank()) {
            val src = el.attrs["src"]
            if (src != null) {
                // 图片损坏/解码器 bug/loader 未注入全压成 null（无内在尺寸回退正确）；
                // 与"本来就没有宽高属性"不可区分，记 d 供排查（低频：仅无属性时触发）。
                val bounds = runCatching { imageLoader.decodeBounds(chapterHref, src) }
                    .onFailure { Logger.d("Orilumn.IMG", "decodeBounds FAIL $src ${it.message}") }
                    .getOrNull()
                if (bounds != null && bounds.first > 0 && bounds.second > 0) {
                    // 资源固有像素按 CSS px 语义换算到画布像素（宽高同乘 density，比例不变）。
                    // 画布是物理 px、渲染层不再二次缩放，故密度只在这一处进几何。
                    return (bounds.first * density).roundToInt() to (bounds.second * density).roundToInt()
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
}