package orilumn.reader.ui.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import orilumn.reader.data.font.FontEntry
import orilumn.reader.engine.skia.SkiaFontPool
import orilumn.reader.engine.skia.SkParagraphFactory
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas as SkCanvas
import org.jetbrains.skia.Color as SkColor
import org.jetbrains.skia.paragraph.Paragraph
import org.jetbrains.skia.paragraph.ParagraphBuilder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

/**
 * F4a JVM actual：Skia 段落直画 `nativeCanvas`（桌面即 skia Canvas，真 CoreText 字形）。
 * 样式经 [SkParagraphFactory]（与断行/阅读绘制同一配置源），集合取 [SkiaFontPool.current]
 * （导入字体已在池 + 系统回退），具名族未装即按字形回退——与阅读面同语义。
 * 展示文字走 [FontEntry.display]（系统行落库本地化名，缺字形由字体池系统回退）。
 *
 * 行高 = **真墨迹盒**（[inkBoxOfParagraph] 栅格扫描）：花体/手写/长尾类字体（Zapfino、
 * jpfont-nds、SignPainter…）的字形墨迹远超字体度量行高，skia `getRectsForRange(TIGHT)`
 * 对它们退回格式盒（下缘 ≤ 行高）——按度量行高画会把墨压进下方的字重副标题。
 */
@Composable
actual fun FontPreviewText(
    entry: FontEntry,
    color: Color,
    fontSize: TextUnit,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    val px = with(density) { fontSize.toPx() }.coerceAtLeast(1f)
    val ink = color.toArgb()
    // 取字形永远用原族名，展示文字走 [FontEntry.display]（本地化名或族名本身）。
    val display = remember(entry) { entry.display }
    val paragraph = remember(entry.family, display, px, ink) {
        val style = SkParagraphFactory.paragraphStyle(
            alignment = orilumn.reader.engine.css.TextAlign.LEFT,
            fontSizePx = px,
            lineHeightRatio = 0f,
            tag = "p",
            families = listOf(entry.family),
            weight = 400,
            italic = false,
            monospace = false,
            letterSpacingEm = 0f,
            inkColor = ink,
        )
        ParagraphBuilder(style, SkiaFontPool.current()).addText(display).build()
            .also { it.layout(Float.MAX_VALUE) }
    }
    DisposableEffect(entry.family, display, px, ink) {
        onDispose { paragraph.close() }
    }
    // 真墨迹底缘（栅格真值，按族/展示名/字号缓存一次）；行高与之取最大，
    // 墨永不溢出名字行盒——下方字重副标题天然不再被压到/重叠。
    val inkBox = remember(entry.family, display, px, ink) {
        inkCache.computeIfAbsent("${entry.family}\u0001$display\u0001${px.toInt()}") {
            inkBoxOfParagraph(paragraph, paragraph.height)
        }
    }
    val h = with(density) { inkBox.bottom.toDp() }.coerceAtLeast(1.dp)
    Canvas(modifier.height(h)) {
        paragraph.paint(drawContext.canvas.nativeCanvas as org.jetbrains.skia.Canvas, 0f, 0f)
    }
}

/** 一次量高结果：栅格墨迹的顶/底（段落坐标，底已 ≥ 度量行高）。 */
internal data class InkBox(val top: Float, val bottom: Float)

/** 进程内量高缓存：(family, display, 字号px) → 墨盒。字库目录稳定 → 无上限担忧。 */
internal val inkCache = ConcurrentHashMap<String, InkBox>()

/**
 * 段落墨迹盒真值：把已构好的 [paragraph] 栅格到临时位图并逐行扫描（与面板实际
 * 绘制用**同一个** paragraph 对象 → 字形/回退/度量零差异），返回真墨迹的 [InkBox]。
 * 背景（2026-09-23 实机探针，326 族全量栅格）：仅 4 族真溢墨——Zapfino +32px、
 * jpfont-nds +68px、SignPainter +3px、BM Hanna 11yrs Old +2px（相对 `max(paraH,
 * getRectsForRange·TIGHT)`）；该 4 族里 rects 有 2 族直接报等于行高（假阴性）。
 * 栅格即用户肉眼的像素真值，是唯一可信下缘。空墨（空展示串等）回退度量行高。
 */
internal fun inkBoxOfParagraph(paragraph: Paragraph, paraH: Float, bottomSlackPx: Int = 96): InkBox {
    val w = (ceil(paragraph.maxIntrinsicWidth.toDouble()) + 16).toInt().coerceIn(32, 2048)
    val hBuf = (ceil(paraH.toDouble()).toInt() + bottomSlackPx).coerceAtLeast(64)
    val bmp = Bitmap()
    try {
        bmp.allocN32Pixels(w, hBuf, true)
        val canvas = SkCanvas(bmp)
        try {
            canvas.clear(SkColor.WHITE)
            paragraph.paint(canvas, 0f, 0f)
            val pxm = requireNotNull(bmp.peekPixels())
            try {
                var top = -1
                var bottom = -1
                for (y in 0 until hBuf) {
                    for (x in 0 until w) {
                        if (pxm.getColor(x, y) != SkColor.WHITE) {
                            if (top < 0) top = y
                            bottom = y
                            break
                        }
                    }
                }
                return if (top < 0) InkBox(0f, paraH)
                else InkBox(top.toFloat(), maxOf(paraH, (bottom + 1).toFloat()))
            } finally {
                pxm.close()
            }
        } finally {
            canvas.close()
        }
    } finally {
        bmp.close()
    }
}