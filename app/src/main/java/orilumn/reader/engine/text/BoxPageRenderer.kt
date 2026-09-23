package orilumn.reader.engine.text

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import orilumn.reader.engine.ImageLoader
import orilumn.reader.engine.css.TextAlign
import orilumn.reader.engine.skia.DecodedImage
import orilumn.reader.engine.skia.DrawLine
import orilumn.reader.engine.skia.LineWindowDrawer
import orilumn.reader.engine.layout.DrawableBookLayout
import orilumn.reader.engine.layout.ParagraphShape
import orilumn.reader.engine.laying.BoxDrawer
import orilumn.reader.engine.laying.LayoutBox
import orilumn.reader.engine.laying.NormalFlowLayout
import orilumn.reader.engine.laying.extraHeightPx
import orilumn.reader.engine.render.DebugDraw
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface as SkiaSurface
import kotlin.math.roundToInt

/**
 * Shared Android drawing for the two box-page renderers ([BoxDrawableLayout] and the incremental
 * `PartialDrawableLayout` in [orilumn.reader.engine.BoxChapterLayouter]). This is the single source
 * that used to be duplicated between the two, so the full and incremental paths cannot drift.
 *
 * Painting order mirrors a browser: box backgrounds/borders over the whole [boxesForDraw] tree
 * (clipped by the page viewport) first, then the paragraph glyphs, each leaf clipped to its own
 * on-page line band. A leaf is looked up from a line via [leavesByShape] in O(1).
 *
 * C2-P1: implements common [orilumn.reader.engine.skia.LayoutReadback] so the controller
 * reads pages back through the interface, never this Android class.
 */
/**
 * C2-P2b-3: 纯 canvas 桥。窗口数据实现已抽到 engine-skia [WindowedBookLayout]（本类继承它），
 * 这里只剩 Android Canvas 绘制（调用方仅剩休眠的卷曲路径；live 面走 skia 窗口）。
 */
abstract class BoxPageRenderer : orilumn.reader.engine.skia.WindowedBookLayout() {

    /** One shaped paragraph per leaf, narrowed to the paint-carrying type for canvas drawing. */
    protected abstract override val shapes: List<ParagraphShape>

    private val boxPaint = Paint()
    private val imgPlaceholderPaint = Paint().apply {
        color = Color.parseColor("#ffdddddd")
        style = Paint.Style.FILL
    }
    private val tableBorderPaint = Paint().apply {
        color = Color.parseColor("#ff999999")
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    private val debugBoxStroke = Paint().apply {
        color = Color.argb(255, 255, 40, 40)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val debugLineEdgePaint = Paint().apply {
        color = Color.argb(190, 60, 255, 150)
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    /** Q1-a：engine-skia 文本窗口光栅器（离屏 surface + Bitmap 像素桥，视口尺寸复用缓存）。 */
    private val skiaTextWindow = SkiaPageTextWindow()

    fun drawPageSlice(canvas: Canvas, firstLine: Int, lastLineExclusive: Int, contentW: Int, contentH: Int) {
        // Safety net for an unshaped page (firstLine=-1 from an incremental window that skipped it).
        if (firstLine < 0 || lastLineExclusive <= firstLine) return
        val pageEnd = minOf(lastLineExclusive, lineCount)
        if (firstLine >= pageEnd) return
        val save = canvas.save()
        canvas.clipRect(0f, 0f, contentW.toFloat(), contentH.toFloat())
        alignPageTop(canvas, firstLine)

        // Box backgrounds/borders — only boxes whose lines intersect this page, clipped to
        // the page's line band: torn blocks stop at the last line, pushed blocks are excluded.
        // P3-a: 圆角/描边环/阴影/alpha（硬件画布 setShadowLayer 不可靠，阴影走位移叠画确定性路径）。
        val bandTop = getLineTop(firstLine)
        val bandBottom = getLineBottom(pageEnd - 1)
        for (r in BoxDrawer.drawOnPage(boxesForDraw, firstLine, pageEnd, bandTop, bandBottom)) {
            runCatching { Color.parseColor(r.colorHex) }.getOrNull()?.let { argb ->
                drawBgRect(canvas, r, argb)
            }
        }

        // Q1-a：skia 窗口（engine-skia DrawLine 像素桥）渲染本页文本，一次整页纹理合成；
        // box 背景/边框已在上面上，替换块（img/table）仍在下面逐叶附加绘制。
        val skiaWindow = skiaLines
        if (skiaWindow != null) skiaTextWindow.draw(canvas, contentW, contentH, firstLine, pageEnd, this, skiaWindow)
        // Draw exactly the slice's own lines; each leaf's glyphs clipped to its on-page band so a
        // paragraph crossing the page boundary never overdraws the next page's first lines.
        val painted = BooleanArray(leaves.size)
        for (g in firstLine until pageEnd) {
            val si = leavesByShape[g]
            if (si < 0 || si >= leaves.size || painted[si]) continue
            painted[si] = true
            val leaf = leaves[si]
            val shape = shapes[si]
            // 窗口模式：文本叶已由 skia 窗口绘制（含 list marker），只补绘替换块。
            if (skiaLines != null && !shape.isReplaceable) continue
            val leafLo = maxOf(leafLocalFirstLine[si], firstLine)
            val leafHi = minOf(leafLocalFirstLine[si] + shape.lineCount, pageEnd)
            if (leafLo >= leafHi) continue
            drawLeaf(canvas, leaf, shape, si, leafLo, leafHi, contentW)
        }
        if (DebugDraw.DEBUG_DRAW && DebugDraw.enabled) debugDrawOverlay(canvas, firstLine, pageEnd, contentW, contentH)
        canvas.restoreToCount(save)
    }

    /**
     * 调试叠加层（需 [DebugDraw.DEBUG_DRAW] 为 true 且 [DebugDraw.enabled] 为 true 才调用；前者为
     * const，为 false 时整段在编译期裁剪）。
     *
     * 只画行/盒级图形（绿线、红框），它们与正文同一坐标系（已被 [alignPageTop] 平移），y 直接用
     * getLineTop/Bottom。页面内容区边界（🔵 蓝框）不在这里画——它属于内容区坐标系，
     * 由 [orilumn.reader.engine.render.PageRenderer] 在 alignPageTop 之前绘制，保证每一页位置一致。
     */
    /** 首行顶对齐（C2-P2b-3 前身在 `DrawableBookLayout` 默认实现里；canvas 操作留桥内）。 */
    private fun alignPageTop(canvas: Canvas, firstLine: Int): Int {
        val pageTop = getLineTop(firstLine)
        canvas.translate(0f, -pageTop.toFloat())
        return pageTop
    }

    private fun debugDrawOverlay(canvas: Canvas, firstLine: Int, pageEnd: Int, contentW: Int, contentH: Int) {
        // 🟢 绿：每行文字的 top/bottom 边界
        val pw = contentW.toFloat()
        for (g in firstLine until pageEnd) {
            val top = getLineTop(g).toFloat()
            val bot = getLineBottom(g).toFloat()
            canvas.drawLine(0f, top, pw, top, debugLineEdgePaint)
            canvas.drawLine(0f, bot, pw, bot, debugLineEdgePaint)
        }

        // 🔴 红：盒模型边界（所有盒子，仅描边）。叶子盒 = 行范围 ± 自身 border/padding（与 emit 的
        // bbox 一致）；容器/背景拥有者直接取其 border-box
        // （contentLeft..+contentWidth × contentTop..contentBottom）。
        for (i in leaves.indices) {
            val lo = leafLocalFirstLine[i].coerceAtLeast(0)
            val hi = lo + shapes[i].lineCount.coerceAtLeast(0)
            if (lo >= pageEnd || hi <= firstLine || lo >= lineCount) continue
            val leaf = leaves[i]
            val top = getLineTop(lo) - (leaf.style.border.top + leaf.style.padding.top).roundToInt()
            val bottom = getLineBottom(minOf(hi, lineCount) - 1) + (leaf.style.border.bottom + leaf.style.padding.bottom).roundToInt()
            val left = leaf.contentLeft
            val right = leaf.contentLeft + leaf.contentWidth
            if (right <= left || bottom <= top) continue
            canvas.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), debugBoxStroke)
        }
        for (box in boxesForDraw) {
            if (box.contentWidth <= 0 || box.contentBottom <= box.contentTop) continue
            if (box.firstLineIndex >= 0 &&
                (box.lastLineExclusive <= firstLine || box.firstLineIndex >= pageEnd)
            ) continue
            val left = box.contentLeft
            val right = box.contentLeft + box.contentWidth
            if (right <= left) continue
            canvas.drawRect(left.toFloat(), box.contentTop.toFloat(), right.toFloat(), box.contentBottom.toFloat(), debugBoxStroke)
        }
    }

    /** Draws [leaf]'s paragraph shape, clipped to the leaf's lines within `[leafLo, leafHi)`. */
    private fun drawLeaf(canvas: Canvas, leaf: LayoutBox, shape: ParagraphShape, si: Int, leafLo: Int, leafHi: Int, contentW: Int) {
        val gearIndex = leafLocalFirstLine[si]
        if (gearIndex < 0 || shape.lineCount <= 0) return
        // Text content x = the leaf's border-box left + its own left border/padding (block edges are
        // consumed here; the page's reading margin is applied outside drawPageSlice).
        val xOff = (leaf.contentLeft + (leaf.style.border.left + leaf.style.padding.left)).toFloat()
        if (shape.isReplaceable) {
            if (leaf.table != null) {
                drawTableRow(canvas, leaf, gearIndex, contentW)
                return
            }
            drawImageOrPlaceholder(canvas, leaf, shape, gearIndex, xOff, contentW)
            return
        }
        // Canvas fallback (only when skiaLines == null, i.e. text-less windows): one drawText per
        // line from the shape's own text at skia-break geometry. Production text goes through the
        // skia window above and never reaches here.
        val paint = shape.drawPaint ?: return
        val k0 = (leafLo - gearIndex).coerceIn(0, shape.lineCount - 1)
        val k1 = (leafHi - 1 - gearIndex).coerceIn(0, shape.lineCount - 1)
        val save = canvas.save()
        canvas.translate(xOff, getLineTop(gearIndex).toFloat())
        canvas.clipRect(shape.listMarkerClipLeft, shape.lineTop(k0).toFloat(), contentW.toFloat(), shape.lineBottom(k1).toFloat())
        drawShapeLines(canvas, shape, NormalFlowLayout.innerBreakWidth(leaf.style, leaf.contentWidth).toFloat(), k0, k1, paint)
        shape.drawListMarker(canvas) // list marker overlay (no-op when the leaf has none)
        canvas.restoreToCount(save)
    }

    /**
     * P3-a: 单个盒背景/边框绘制（android.graphics 口径，与 skia 单源同语义）。
     * 方形即旧矩形路径；圆角走 Path（8 半径）；均匀边框圆角环走描边；
     * 阴影走位移叠画（blur>0 时 3 层阶梯；硬件画布 setShadowLayer 不可靠，不用）；
     * alpha 盖入 paint（调用方 paint 状态不保留，复原）。
     */
    private fun drawBgRect(canvas: Canvas, r: orilumn.reader.engine.laying.DrawRect, argb: Int) {
        val a = (Color.alpha(argb) * r.alpha).roundToInt().coerceIn(0, 255)
        val paint = Paint(boxPaint).apply { color = Color.argb(a, Color.red(argb), Color.green(argb), Color.blue(argb)) }
        val radii = floatArrayOf(
            r.radii.topLeft, r.radii.topLeft, r.radii.topRight, r.radii.topRight,
            r.radii.bottomRight, r.radii.bottomRight, r.radii.bottomLeft, r.radii.bottomLeft,
        )
        val square = radii.all { it == 0f }
        fun pathOf(l: Float, t: Float, rr: Float, b: Float, rad: FloatArray): android.graphics.Path =
            android.graphics.Path().apply {
                if (rad.all { it == 0f }) {
                    addRect(l, t, rr, b, android.graphics.Path.Direction.CW)
                } else {
                    addRoundRect(android.graphics.RectF(l, t, rr, b), rad, android.graphics.Path.Direction.CW)
                }
            }
        fun fillRect(l: Float, t: Float, rr: Float, b: Float, p: Paint, rad: FloatArray, strokeW: Float) {
            if (rad.all { it == 0f } && strokeW <= 0f) {
                canvas.drawRect(l, t, rr, b, p)
            } else if (strokeW > 0f) {
                p.style = Paint.Style.STROKE
                p.strokeWidth = strokeW.coerceAtLeast(1f)
                canvas.drawPath(pathOf(l, t, rr, b, rad), p)
                p.style = Paint.Style.FILL
            } else {
                canvas.drawPath(pathOf(l, t, rr, b, rad), p)
            }
        }
        // 阴影先画（形下）。
        r.shadow?.let { sh ->
            val sc = runCatching { Color.parseColor(sh.colorHex ?: "#000000") }.getOrNull() ?: return@let
            val sa = (Color.alpha(sc) * r.alpha).roundToInt().coerceIn(0, 255)
            val sp = Paint(boxPaint).apply { color = Color.argb(sa, Color.red(sc), Color.green(sc), Color.blue(sc)) }
            if (sh.blur <= 0f) {
                fillRect(r.left + sh.dx, r.top + sh.dy, r.right + sh.dx, r.bottom + sh.dy, sp, radii, 0f)
            } else {
                for (k in 3 downTo 1) {
                    val e = sh.blur * k / 6f
                    val lp = Paint(sp).apply { alpha = (sa * 0.3f / k).roundToInt().coerceIn(0, 255) }
                    fillRect(r.left + sh.dx - e, r.top + sh.dy - e, r.right + sh.dx + e, r.bottom + sh.dy + e, lp, grownRadii(radii, e), 0f)
                }
            }
        }
        fillRect(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat(), paint, radii, r.strokeWidthPx)
        // P3-b: 背景图（底色之上；ImageLoader LRU 缓存 1:1 直解，失败即纯色旧路径）。
        r.bgImage?.takeIf { it.url.isNotBlank() && chapterHref.isNotBlank() }?.let { bg ->
            val decoded = runCatching { imageLoader?.decode(chapterHref, bg.url, 0) }.getOrNull()
            val bitmap = decoded?.let { runCatching { orilumn.reader.engine.skiaImageToAndroidBitmap(it.image) }.getOrNull() }
            if (bitmap != null) {
                // 跨页撕裂以未裁剪锚盒定原点（退化即本 rect）。
                val aTop = if (r.bgBoxBottom > r.bgBoxTop) r.bgBoxTop else r.top
                val aBottom = if (r.bgBoxBottom > r.bgBoxTop) r.bgBoxBottom else r.bottom
                val tiles = orilumn.reader.engine.laying.BackgroundTiles.tiles(
                    r.left, aTop, r.right, aBottom,
                    bitmap.width, bitmap.height, bg.repeat, bg.position,
                )
                if (tiles.isNotEmpty()) {
                    val save = canvas.save()
                    canvas.clipPath(pathOf(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat(), radii))
                    val ip = Paint(boxPaint).apply { alpha = (255f * r.alpha).roundToInt().coerceIn(0, 255) }
                    for (tile in tiles) {
                        if (tile.right <= r.left || tile.left >= r.right || tile.bottom <= r.top || tile.top >= r.bottom) continue
                        canvas.drawBitmap(
                            bitmap, null,
                            android.graphics.RectF(tile.left.toFloat(), tile.top.toFloat(), tile.right.toFloat(), tile.bottom.toFloat()), ip,
                        )
                    }
                    canvas.restoreToCount(save)
                }
            }
        }
    }

    private fun grownRadii(radii: FloatArray, e: Float): FloatArray =
        FloatArray(radii.size) { (radii[it] + e).coerceAtLeast(0f) }

    /** Canvas fallback for one shape's lines `[k0, k1]`: drawText per line at break geometry with
     *  block alignment (center/right measured offsets; justify draws left-aligned).
     *  P1-2: 与行区间相交的基线位移段逐段偏置基线绘制（位移 em × 画笔字号，上移为负）；
     *  无位移行走旧整行路径（逐字节一致）。
     *  P3-a: 祖先 alpha 盖画笔（复原）、行阴影偏置复画一行、着重号逐字置点/圈。 */
    private fun drawShapeLines(canvas: Canvas, shape: ParagraphShape, blockW: Float, k0: Int, k1: Int, paint: Paint) {
        val savedAlpha = paint.alpha
        paint.alpha = (savedAlpha * shape.alpha).roundToInt().coerceIn(0, 255)
        // 行阴影画笔（颜色已解；alpha 同行）。
        val shadowPaint = shape.textShadow?.let { sh ->
            val sc = runCatching { Color.parseColor(sh.colorHex ?: "#000000") }.getOrNull() ?: return@let null
            val a = (Color.alpha(sc) * shape.alpha).roundToInt().coerceIn(0, 255)
            Paint(paint).apply { color = Color.argb(a, Color.red(sc), Color.green(sc), Color.blue(sc)) }
        }
        try {
            for (k in k0..k1) {
                drawFallbackLine(canvas, shape, blockW, k, paint, shadowPaint)
            }
        } finally {
            paint.alpha = savedAlpha
        }
    }

    /** 回退单行：基线位移分段＋阴影复画＋着重号（调用方已盖 alpha）。 */
    private fun drawFallbackLine(
        canvas: Canvas,
        shape: ParagraphShape,
        blockW: Float,
        k: Int,
        paint: Paint,
        shadowPaint: Paint?,
    ) {
        val s = shape.lineStart(k)
        val e = shape.lineEnd(k)
        if (s < 0 || e <= s || e > shape.text.length) return
        // P6-b: 本行相交注音（叶坐标）；基文下移注音高，内联 rt 源文占宽不画，叠排 rt 居中画上方。
        val rubyHits = shape.rubyRuns.filter { it.start < e && it.endExclusive > s }
        val lineExtra = rubyHits.maxOfOrNull { it.extraHeightPx() } ?: 0
        val rtHidden = rubyHits.mapNotNull { run ->
            val hs = run.rtStart.coerceIn(s, e)
            val he = run.rtEndExclusive.coerceIn(s, e)
            if (he > hs && run.rtStart >= 0) hs until he else null
        }
        fun isRtHidden(from: Int, to: Int): Boolean {
            for (r in rtHidden) if (from >= r.first && to <= r.last) return true
            return false
        }
        val base = shape.lineBaseline(k).toFloat() + lineExtra
        val shifts = shape.baselineShifts
        var si = 0
        while (si < shifts.size && shifts[si].endExclusive <= s) si++
        // 字形段：(子串, x, y, 下划线)，总量测宽后按对齐起绘。
        // P6-b: 段再按 rt 隐形边界切分（跨 rt 的段拆成可见/隐藏子段，避免整段误画）。
        // 下划线：段再按下划线边界切分并随段标记（CSS 标准：声明元素整段向后代传播）。
        data class Seg(val from: Int, val to: Int, val y: Float, val ul: Boolean = false)
        val ulHits = shape.underlineRuns.mapNotNull { r ->
            val hs = r.start.coerceIn(s, e)
            val he = r.endExclusive.coerceIn(s, e)
            if (he > hs) hs until he else null
        }
        fun isUnderlined(from: Int, to: Int): Boolean {
            for (r in ulHits) if (from >= r.first && to <= r.last) return true
            return false
        }
        val rawSegs = ArrayList<Seg>()
        if (si >= shifts.size || shifts[si].start >= e) {
            rawSegs.add(Seg(s, e, base))
        } else {
            var cur = s
            var j = si
            while (cur < e) {
                val r = if (j < shifts.size) shifts[j] else null
                if (r == null || r.start >= e) {
                    rawSegs.add(Seg(cur, e, base))
                    break
                }
                if (r.start > cur) rawSegs.add(Seg(cur, minOf(r.start, e), base))
                val segEnd = minOf(r.endExclusive, e)
                if (segEnd > cur) rawSegs.add(Seg(maxOf(cur, r.start), segEnd, base - r.shiftEm * paint.textSize))
                cur = segEnd
                if (r.endExclusive <= e) j++
            }
        }
        val segs = ArrayList<Seg>(rawSegs.size + rtHidden.size * 2 + ulHits.size * 2)
        for (seg in rawSegs) {
            val cuts = ArrayList<Int>(6)
            cuts.add(seg.from)
            for (r in rtHidden) {
                if (r.first > seg.from && r.first < seg.to) cuts.add(r.first)
                if (r.last > seg.from && r.last < seg.to) cuts.add(r.last)
            }
            for (r in ulHits) {
                if (r.first > seg.from && r.first < seg.to) cuts.add(r.first)
                if (r.last > seg.from && r.last < seg.to) cuts.add(r.last)
            }
            cuts.add(seg.to)
            val sorted = cuts.distinct().sorted()
            for (k2 in 0 until sorted.size - 1) {
                val a = sorted[k2]
                val b = sorted[k2 + 1]
                if (b > a) segs.add(Seg(a, b, seg.y, isUnderlined(a, b)))
            }
        }
        var totalW = 0f
        for (seg in segs) totalW += paint.measureText(shape.text, seg.from, seg.to)
        var x = when (shape.alignment) {
            TextAlign.CENTER -> ((blockW - totalW) / 2f).coerceAtLeast(0f)
            TextAlign.RIGHT -> (blockW - totalW).coerceAtLeast(0f)
            else -> 0f
        }
        // P3-a: 阴影先画（形下偏置复画，无模糊——硬件画布确定性路径；内联 rt 占宽不画）。
        // 下划线段的阴影同样带下划线（调用方 paint 状态复原）。
        val savedUl = paint.isUnderlineText
        val savedShadowUl = shadowPaint?.isUnderlineText
        try {
            shape.textShadow?.let { sh ->
                val sp = shadowPaint ?: return@let
                var sx = x
                for (seg in segs) {
                    val sub = shape.text.substring(seg.from, seg.to)
                    val w = paint.measureText(sub)
                    if (!isRtHidden(seg.from, seg.to)) {
                        sp.setUnderlineText(seg.ul)
                        canvas.drawText(sub, sx + sh.dx, seg.y + sh.dy, sp)
                    }
                    sx += w
                }
            }
            for (seg in segs) {
                val sub = shape.text.substring(seg.from, seg.to)
                val w = paint.measureText(sub)
                // P6-b: 内联 rt 源文透明占位（不断字符流/断行，只隐形）。
                if (!isRtHidden(seg.from, seg.to)) {
                    paint.setUnderlineText(seg.ul)
                    canvas.drawText(sub, x, seg.y, paint)
                }
                x += w
            }
        } finally {
            paint.setUnderlineText(savedUl)
            if (shadowPaint != null && savedShadowUl != null) shadowPaint.setUnderlineText(savedShadowUl)
        }
        // P6-b: 叠排 rt 居中画于基字上方（与 Skia 端同式；回退按单画笔测宽定心）。
        if (rubyHits.isNotEmpty()) {
            // 行首 x0（含对齐），与上文 segs 起绘一致。
            val x0 = when (shape.alignment) {
                TextAlign.CENTER -> ((blockW - totalW) / 2f).coerceAtLeast(0f)
                TextAlign.RIGHT -> (blockW - totalW).coerceAtLeast(0f)
                else -> 0f
            }
            // 注意：上文循环已把 x 累加到行尾，这里重算 x0，不复用累加后的 x。
            for (run in rubyHits) {
                val bs = maxOf(run.start, s)
                val be = minOf(run.endExclusive, e)
                if (be <= bs || run.rtText.isEmpty()) continue
                val preW = if (bs > s) paint.measureText(shape.text, s, bs) else 0f
                val baseW = paint.measureText(shape.text, bs, be)
                if (baseW <= 0f) continue
                val baseCenter = x0 + preW + baseW / 2f
                val rtSize = run.rtFontSizePx.coerceAtLeast(1f)
                val rtPaint = Paint(paint).apply { textSize = rtSize }
                val rtW = rtPaint.measureText(run.rtText)
                canvas.drawText(run.rtText, baseCenter - rtW / 2f, shape.lineTop(k) + rtSize, rtPaint)
            }
        }
        // P3-a: 着重号逐字置点/圈（空格亦置点）。
        if (shape.emphasis != orilumn.reader.engine.css.EmphasisStyle.NONE) {
            val filled = shape.emphasis == orilumn.reader.engine.css.EmphasisStyle.DOT
            val r = paint.textSize * (if (filled) 0.09f else 0.11f)
            if (r > 0f) {
                val dot = Paint(paint).apply {
                    if (!filled) {
                        style = Paint.Style.STROKE
                        strokeWidth = (r * 0.3f).coerceAtLeast(1f)
                    }
                }
                val dy = if (shape.emphasisUnder) {
                    (shape.lineBottom(k) - shape.lineTop(k)) - r - 1f
                } else {
                    r + 1f
                }
                var cx = when (shape.alignment) {
                    TextAlign.CENTER -> ((blockW - totalW) / 2f).coerceAtLeast(0f)
                    TextAlign.RIGHT -> (blockW - totalW).coerceAtLeast(0f)
                    else -> 0f
                }
                for (seg in segs) {
                    for (i in seg.from until seg.to) {
                        val w = paint.measureText(shape.text, i, i + 1)
                        canvas.drawCircle(cx + w / 2f, shape.lineTop(k) + dy, r, dot)
                        cx += w
                    }
                }
            }
        }
    }

    /** Draws an `<img>` replaceable block; real bitmap when decodable, else the gray placeholder. */
    private fun drawImageOrPlaceholder(
        canvas: Canvas,
        leaf: LayoutBox,
        shape: ParagraphShape,
        gearIndex: Int,
        xOff: Float,
        contentW: Int,
    ) {
        val save = canvas.save()
        val yOff = (getLineTop(gearIndex) - shape.originTop()).toFloat()
        canvas.translate(xOff, yOff)
        val boxH = shape.lineBottom(0)
        val src = leaf.el?.attrs?.get("src")
        val loader = imageLoader
        // Decode at the standard used width (same single source the box flow measured with),
        // not the page width — otherwise a max-width-shrunk image would decode (and draw) big
        // while its box stays small, or vice versa.
        val el = leaf.el
        val leafBreakW = orilumn.reader.engine.laying.NormalFlowLayout.innerBreakWidth(leaf.style, leaf.contentWidth)
        val usedW = if (el != null) {
            orilumn.reader.engine.laying.NormalFlowLayout.replacedUsedSize(
                el, leaf.style, leafBreakW, loader, chapterHref,
            ).first
        } else contentW
        val decoded: DecodedImage? = if (src != null && loader != null && chapterHref.isNotBlank()) {
            runCatching { loader.decode(chapterHref, src, usedW.coerceAtLeast(1)) }.getOrNull()
        } else null
        // Clip to the taller of the placeholder box and the actual image so an image is never cropped.
        val clipBottom = if (decoded != null) maxOf(boxH, decoded.height) else boxH
        canvas.clipRect(0f, 0f, contentW.toFloat(), clipBottom.toFloat())
        val bitmap = decoded?.let { runCatching { orilumn.reader.engine.skiaImageToAndroidBitmap(it.image) }.getOrNull() }
        if (bitmap != null) {
            canvas.drawBitmap(bitmap, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        } else {
            canvas.drawRect(0f, 0f, contentW.toFloat(), boxH.toFloat(), imgPlaceholderPaint)
        }
        canvas.restoreToCount(save)
    }

    /** Draws a table row's cells side by side at their placed x positions, clipped to the row's band. */
    private fun drawTableRow(canvas: Canvas, leaf: LayoutBox, gearIndex: Int, contentW: Int) {
        val t = leaf.table ?: return
        val rowTop = getLineTop(gearIndex)
        val rowBottom = getLineBottom(gearIndex)
        val rowH = rowBottom - rowTop
        val save = canvas.save()
        canvas.clipRect(0f, rowTop.toFloat(), contentW.toFloat(), rowBottom.toFloat())
        for (cell in t.cells) {
            val cs = cell.shape as? ParagraphShape ?: continue
            // P1-2 `empty-cells: hide`：空单元格跳过边框（内容本就为空）。
            if (t.emptyCellsHide && cs.text.isEmpty()) continue
            val save2 = canvas.save()
            canvas.translate(cell.x.toFloat(), rowTop.toFloat())
            canvas.clipRect(0f, 0f, cell.width.toFloat(), rowH.toFloat())
            // Table cells are outside the skia DrawLine window: draw their skia-break lines
            // directly (C1-0 fallback, same geometry the pagination used).
            cs.drawPaint?.let { paint ->
                if (!cs.isReplaceable && cs.lineCount > 0) {
                    drawShapeLines(canvas, cs, cell.width.toFloat(), 0, cs.lineCount - 1, paint)
                }
                cs.drawListMarker(canvas)
            }
            canvas.drawRect(0f, 0f, cell.width.toFloat(), rowH.toFloat(), tableBorderPaint)
            canvas.restoreToCount(save2)
        }
        canvas.restoreToCount(save)
    }
}

/**
 * Q1-a：把一页的 [DrawLine] 窗口用 [LineWindowDrawer] 光栅进 skia 离屏 [SkiaSurface]，再经
 * `skiaImageToAndroidBitmap` 像素桥回 Bitmap 后 drawBitmap 到页面画布——与 shared-ui
 * `ReaderPageRenderer.android` 同一桥（引擎侧统一 engine-skia 绘制，Android 画布只收像素）。
 *
 * 坐标：页面画布已由 [alignPageTop] 把首行对齐到 0；窗口内 [DrawLine.yTop] 是章节绝对 Y，
 * 故 surface 先平移 `-getLineTop(firstLine)` 再剪裁到视口，逐行整形绘制后取快照。
 */
private class SkiaPageTextWindow {
    private val drawer = LineWindowDrawer()
    private var surface: SkiaSurface? = null
    private var width = -1
    private var height = -1

    fun draw(
        canvas: Canvas,
        contentW: Int,
        contentH: Int,
        firstLine: Int,
        pageEnd: Int,
        host: BoxPageRenderer,
        skiaLines: Map<Int, DrawLine>,
    ) {
        val w = contentW.coerceAtLeast(1)
        val h = contentH.coerceAtLeast(1)
        if (surface == null || w != width || h != height) {
            width = w
            height = h
            surface?.close()
            surface = SkiaSurface.makeRasterN32Premul(w, h)
        }
        val s = surface!!
        s.canvas.clear(0)
        val align = host.getLineTop(firstLine)
        val saved = s.canvas.save()
        s.canvas.translate(0f, -align.toFloat())
        s.canvas.clipRect(Rect.makeLTRB(0f, 0f, w.toFloat(), h.toFloat()))
        drawer.drawLines(s.canvas, 0f, (firstLine until pageEnd).mapNotNull { skiaLines[it] })
        s.canvas.restoreToCount(saved)
        // 与 shared-ui Android 桥同口径：m144 起 Bitmap.readPixels 真机回 null，改 PNG 单跳。
        val img = s.makeImageSnapshot()
        val png: ByteArray? = try {
            img.encodeToData()?.bytes
        } finally {
            img.close()
        }
        if (png == null) return
        val bmp = BitmapFactory.decodeByteArray(png, 0, png.size) ?: return
        canvas.drawBitmap(
            bmp,
            android.graphics.Rect(0, 0, bmp.width, bmp.height),
            android.graphics.RectF(0f, 0f, w.toFloat(), h.toFloat()),
            Paint(Paint.FILTER_BITMAP_FLAG),
        )
    }
}