package orilumn.reader.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import orilumn.reader.engine.skia.NoteTextLayout
import orilumn.reader.engine.skia.NoteTextSpec
import orilumn.reader.io.Logger
import kotlin.math.roundToInt

/**
 * 页**覆盖层**（旁注 + 页码）的绘制与离屏栅格（用户层·渲染缝）。
 *
 * ## 为什么覆盖层单独成一张图
 *
 * 页栅格（`PageRaster.kt`）只装正文/插图/盒背景——那是**排版层**的产物，可被后台预栅格、
 * 按页身份键复用。旁注与页码是**用户层**的数据（标注集合、全书页码），来得晚、变得勤
 * （拖批注字号每挪一格都换一套布点）。把它们烤进页栅格会让页缓存反复作废，
 * 预栅格也永远打不中（后台线程拿不到布点结果，那是一台 Compose 尺子）。
 * 所以分两层各管各的：
 *
 *  - 页栅格：只认排版，贵但可预栅格；
 *  - 覆盖层：栅格很便宜（几百个字形），按「旁注 + 页码 + 样式」键缓存，随到随换。
 *
 * 翻页卷曲时两层**各过一遍同一个卷曲着色器**（覆盖层那一遍把纸背/纸卷区返回透明），
 * 于是旁注与页码跟着纸一起弯，而不是浮在上面不动。
 *
 * ## 旁注文字走自研引擎，页码仍用 Compose
 *
 * 旁注的断行 / 对齐 / 字距 / 混排间距与正文共用自研断行引擎（[NoteTextLayout] 出断行与
 * 行窗口，[drawNoteTextLines] 落墨），所以读者调字间距时正文与批注一起变，不会「正文在动、
 * 批注不动」。页码只有一行数字，仍用 [TextMeasurer] 直画。
 *
 * 像素从旧 `NoteBubbles` 覆盖层搬来：比例/颜色/内缩逐项照抄；交互那一侧只剩
 * [NoteBubbleHitAreas]（透明热区）。
 */
internal data class PageOverlayStyle(
    /** 正文墨色（页码与「不用标注色」的旁注都用它）。 */
    val fgArgb: Int,
    /** 纸面底色（同色字体按它的明暗决定往哪边调）。 */
    val pageBgArgb: Int,
    /**
     * 旁注文字的引擎参数（字族 / 字重 / 字距 / 混排间距）。
     *
     * **必须**与布点用的 [NoteTextSizer] 是同一个实例：量宽和画字一旦不是同一套字形与字距，
     * 摆位判「整句放得下」而画出来会多折一行、被省略号截掉。
     */
    val noteSpec: NoteTextSpec = NoteTextSpec(emptyList()),
    val showBorder: Boolean,
    val showBackground: Boolean,
    val textSameColor: Boolean,
)

/** 页脚页码的绘制参数（几何在 [pageNumberLayoutOf] 里算好，这里只管画）。 */
internal data class PageNumberSpec(
    val text: String,
    val fontPx: Float,
    val linePx: Float,
    val bottomPx: Float,
    val endInsetPx: Float,
    val argb: Int,
)

/** 一页覆盖层的全部输入（[PageOverlayLayer] 的缓存键就是它 + 尺寸 + 密度）。 */
internal data class PageOverlayContent(
    val notes: List<NoteBubble>,
    val style: PageOverlayStyle,
    val pageNumber: PageNumberSpec?,
    /**
     * 共用字体池代次（[NoteFontPool]）：池换过面，旁注字形就换了。
     *
     * 必须进缓存键——布点几何可能恰好一模一样（同样的宽度、同样的行数），
     * 但画出来的字形已经不是那一张，只按内容比会把旧字形一直留在缓存里。
     */
    val fontEpoch: Long = 0L,
) {
    val isEmpty: Boolean get() = notes.isEmpty() && pageNumber == null
}

/**
 * 覆盖层离屏图（**按内容键缓存，键不变不重画**）。
 *
 * 缓存是必须的：翻页动画每一帧都要这张图当纹理，重画一遍就是每帧重排一遍文字。
 * 只留一张——同一时刻一页只需要它（另一个页槽有自己的实例，见 `rememberPageOverlayLayer`）。
 */
internal class PageOverlayLayer {
    private var key: Any? = null
    private var image: ImageBitmap? = null

    /**
     * 内容代数：**每次真正重画 +1**，重画前后值必不同。
     *
     * 为什么需要它：尺寸不变时本类**复用同一张 [ImageBitmap] 原地重画**（拖动批注字号时不
     * 每步新分配 20MB 整页图）。位图实例因此不再等价于「同一份像素」，任何按实例判废的
     * 下游缓存都必须改看这个代数，否则永远拿着第一版的快照——症状就是「静态页没有批注、
     * 动画里永远是同一页的批注」。
     *
     * 本类的出口 [obtain] 返回 [RasterTexture]（图 + 代数），不交出裸位图：消费方想只拿图、
     * 忘掉版本也做不到。
     */
    var revision: Int = 0
        private set

    /**
     * @return 透明底整页图 + 它的内容代数（见 [RasterTexture]）；[key] 为 null 或
     * 分配/绘制失败时返回 null（调用方按「没有覆盖层」处理）。
     */
    fun obtain(
        key: Any?,
        widthPx: Int,
        heightPx: Int,
        density: Density,
        layoutDirection: LayoutDirection,
        draw: DrawScope.() -> Unit,
    ): RasterTexture? {
        if (key == null || widthPx <= 0 || heightPx <= 0) return null
        if (key == this.key) return image?.let { RasterTexture(it, revision) }
        val w = widthPx
        val h = heightPx
        // 尺寸没变就**复用同一张**再画一遍：拖批注字号时键每一步都变，每步新分配一张
        // 整页图（20MB 级）会一路把 GC 拖死。
        val reuse = image?.takeIf { it.width == w && it.height == h }
        val img = reuse ?: runCatching {
            ImageBitmap(w, h, ImageBitmapConfig.Argb8888, hasAlpha = true)
        }.getOrNull() ?: return null
        val ok = runCatching {
            CanvasDrawScope().draw(
                density = density,
                layoutDirection = layoutDirection,
                canvas = Canvas(img),
                size = Size(w.toFloat(), h.toFloat()),
            ) {
                // 复用旧图必须先清干净（透明底），否则上一版的旁注会残留在下面。
                drawRect(Color.Transparent, size = Size(w.toFloat(), h.toFloat()), blendMode = BlendMode.Clear)
                draw()
            }
        }.isSuccess
        this.key = key
        this.image = if (ok) img else null
        // 代数只增不减：这张位图（可能是复用的同一实例）此刻的像素已经换了。
        if (ok) revision++
        if (!ok) {
            // 覆盖层是装饰：画坏了就当这页没有旁注/页码，别把阅读面拖崩。
            Logger.w("Orilumn.Curl", "overlay layer draw FAILED ${w}x$h")
        }
        return this.image?.let { RasterTexture(it, revision) }
    }
}

/** 一页一份覆盖层缓存（两个页槽各一份）。 */
@Composable
internal fun rememberPageOverlayLayer(): PageOverlayLayer = remember { PageOverlayLayer() }

/** 覆盖层整页绘制（顺序与旧覆盖层一致：引线在下，框与文字在上，页码最后）。 */
internal fun DrawScope.drawPageOverlay(
    content: PageOverlayContent,
    textMeasurer: TextMeasurer,
) {
    drawNoteBubbles(content.notes, content.style)
    content.pageNumber?.let { drawPageNumberLabel(it, textMeasurer) }
}

/**
 * 旁注视觉（与旧 Compose 覆盖层逐项同形：圆角 3dp、底色 16%、描边 50%、
 * 小椭圆实底 + 三点、引线在下）。
 */
internal fun DrawScope.drawNoteBubbles(
    bubbles: List<NoteBubble>,
    style: PageOverlayStyle,
) {
    if (bubbles.isEmpty()) return
    // 1) 引线先画（框压在线上，与旧覆盖层同序）。
    bubbles.forEach { b ->
        val arrow = b.arrow ?: return@forEach
        drawNoteArrow(arrow, Color(b.argb).copy(alpha = 0.9f))
    }
    for (b in bubbles) {
        if (b.compact) {
            drawCompactNote(b, style)
            continue
        }
        val padLeft = b.fontPx * NOTE_BUBBLE_PAD_RATIO
        // 行宽与布点量宽同一个口径：框宽减两侧内缩，**取整像素**（见 `EngineNoteTextSizer`）。
        val textWidthPx = (b.widthPx - padLeft - b.padRightPx).roundToInt().coerceAtLeast(1)
        // 断行 / 对齐 / 字距 / 混排间距全部由自研引擎出（与布点同一个断行器）。
        val draw = NoteTextLayout.drawLines(
            spec = style.noteSpec,
            text = b.text,
            fontPx = b.fontPx,
            lineHeightRatio = NOTE_BUBBLE_LINE_RATIO,
            widthPx = textWidthPx,
            maxLines = b.maxLines,
            inkArgb = noteInkArgb(b, style),
        )
        // 框高 = 文字实测高（旧覆盖层不设高度上限，框跟着文字长），
        // 这样「行高向上取整」带来的那一两个像素也不会把框画短。
        val boxH = draw.heightPx.toFloat()
        val tint = Color(b.argb)
        if (style.showBackground) {
            drawRoundRect(
                color = tint.copy(alpha = NOTE_BUBBLE_BG_ALPHA),
                topLeft = Offset(b.leftPx, b.topPx),
                size = Size(b.widthPx, boxH),
                cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
            )
        }
        if (style.showBorder) {
            // Compose `border` 把描边画在**形状内侧**：往里缩半个笔宽才是同一圈。
            val strokeW = 1.dp.toPx()
            val half = strokeW / 2f
            drawRoundRect(
                color = tint.copy(alpha = 0.5f),
                topLeft = Offset(b.leftPx + half, b.topPx + half),
                size = Size(
                    (b.widthPx - strokeW).coerceAtLeast(0f),
                    (boxH - strokeW).coerceAtLeast(0f),
                ),
                cornerRadius = CornerRadius((3.dp.toPx() - half).coerceAtLeast(0f)),
                style = Stroke(width = strokeW),
            )
        }
        drawNoteTextLines(
            lines = draw.lines,
            leftPx = b.leftPx + padLeft,
            topPx = b.topPx,
            widthPx = textWidthPx,
            heightPx = draw.heightPx,
        )
    }
}

/**
 * 旁注文字墨色：默认跟随正文墨色；「使用标注同色字体」时按标注色调成同色相，
 * 且对比度按**实际压在什么底上**算（开了底色就是纸面 + 16% 标注色）。
 */
private fun noteInkArgb(b: NoteBubble, style: PageOverlayStyle): Int {
    if (!style.textSameColor) return style.fgArgb
    val backdrop = if (style.showBackground) {
        blendOver(style.pageBgArgb, b.argb or 0xFF000000.toInt(), NOTE_BUBBLE_BG_ALPHA)
    } else {
        style.pageBgArgb
    }
    return noteInkColor(b.argb, backdrop)
}

/** 小椭圆（§1.6）：贴着划线右端的固定大小记号，实底色 + 三点。 */
private fun DrawScope.drawCompactNote(b: NoteBubble, style: PageOverlayStyle) {
    val h = if (b.heightPx > 0f) b.heightPx else b.widthPx * 0.64f
    // 底色必须是**实色**（见 [noteIconFillArgb]）：高亮色自带透明度，照原样铺在记号上
    // 会让底下的划线/正文透上来，看着像「高亮压在图标上面」。
    val fill = noteIconFillArgb(style.pageBgArgb, b.argb)
    // `clip(RoundedCornerShape(50))` 是**胶囊**（两端半圆），不是椭圆：圆角取高的一半。
    drawRoundRect(
        color = Color(fill),
        topLeft = Offset(b.leftPx, b.topPx),
        size = Size(b.widthPx, h),
        cornerRadius = CornerRadius(h / 2f, h / 2f),
    )
    val dotColor = Color(noteIconDotArgb(fill, style.fgArgb, style.pageBgArgb))
    val dot = h * 0.11f
    val step = b.widthPx * 0.22f
    val cy = b.topPx + h / 2f
    val cx = b.leftPx + b.widthPx / 2f
    for (k in -1..1) {
        drawCircle(color = dotColor, radius = dot, center = Offset(cx + k * step, cy))
    }
}

/** 页脚页码：`. 12 .`，右沿对齐正文右沿，竖直在下边距带里居中。 */
internal fun DrawScope.drawPageNumberLabel(spec: PageNumberSpec, textMeasurer: TextMeasurer) {
    val layout = textMeasurer.measure(
        text = spec.text,
        style = TextStyle(
            color = Color(spec.argb),
            fontSize = spec.fontPx.toSp(),
            lineHeight = spec.linePx.toSp(),
        ),
        softWrap = false,
        maxLines = 1,
    )
    drawText(
        layout,
        topLeft = Offset(
            x = size.width - spec.endInsetPx - layout.size.width,
            y = size.height - spec.bottomPx - spec.linePx,
        ),
    )
}

/**
 * 页码几何（与旧 `PageNumberLabel` 同一套规格，只是产出数据、不产出 Composable）。
 *
 * @param index 全书页码；null（本章还没有正典分页）⇒ 不画，宁可留白也不显示会跳变的数。
 * @param contentBottomPx 版心下沿；[pageHeightPx] − 它就是页脚那条边距带。
 * @param endInsetPx 右侧内缩：页码右沿对齐正文右沿，不贴屏幕边。
 */
internal fun pageNumberLayoutOf(
    index: Int?,
    pageHeightPx: Float,
    contentBottomPx: Float,
    bodyFontPx: Float,
    inkArgb: Int,
    endInsetPx: Float,
): PageNumberSpec? {
    if (index == null) return null
    val bandPx = (pageHeightPx - contentBottomPx).coerceAtLeast(0f)
    val fontPx = minOf(bodyFontPx * PAGE_NUMBER_SCALE, bandPx * 0.5f)
    if (fontPx < PAGE_NUMBER_MIN_PX) return null
    val linePx = fontPx * 1.25f
    val bottomPx = ((bandPx - linePx) / 2f).coerceAtLeast(1f)
    val argb = (inkArgb and 0x00FFFFFF) or (PAGE_NUMBER_ALPHA_TIMES_255 shl 24)
    return PageNumberSpec(
        text = ". $index .",
        fontPx = fontPx,
        linePx = linePx,
        bottomPx = bottomPx,
        endInsetPx = endInsetPx.coerceAtLeast(0f),
        argb = argb,
    )
}

/** 页脚字号占正文的比例：够看清又明显小于正文，不与正文抢注意力。 */
private const val PAGE_NUMBER_SCALE = 0.62f

/** 墨色透明度 0.55 的 8bit 值。 */
private const val PAGE_NUMBER_ALPHA_TIMES_255 = 140

/** 再小就不画了（px）：下边距被压到极限时页码自动消失，不压正文。 */
private const val PAGE_NUMBER_MIN_PX = 10f
