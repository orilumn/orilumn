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
import org.jetbrains.skia.paragraph.FontCollection
import org.jetbrains.skia.paragraph.Paragraph
import org.jetbrains.skia.paragraph.ParagraphBuilder
import java.io.File
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
    // 导入行直读文件建专属集合（与 android `createFromFile` 同义，渲染层 jvm last-mile）：
    // 预览不依赖共用池状态——池里只有槽位族 + 整形按需族，未选入槽位的导入族走池必 miss
    // 而回退系统字形（方正等中文名全挤成同一系统字形即此）；读不到/解析失败回退共用池。
    val collection = remember(entry) { previewCollection(entry) }
    val paragraph = remember(entry.family, display, px, ink, collection) {
        val style = SkParagraphFactory.paragraphStyle(
            alignment = orilumn.reader.engine.css.TextAlign.LEFT,
            fontSizePx = px,
            // 自然行高（0 = 跟字体度量）：Adobe 宋体 Std 这类 3em 度量的空隙
            // 由下方真墨盒裁掉，真高的字形（Zapfino 类溢墨）墨有多高行多高。
            lineHeightRatio = 0f,
            tag = "p",
            families = listOf(entry.family),
            weight = 400,
            italic = false,
            monospace = false,
            letterSpacingEm = 0f,
            inkColor = ink,
        )
        ParagraphBuilder(style, collection).addText(display).build()
            .also { it.layout(Float.MAX_VALUE) }
    }
    DisposableEffect(paragraph) {
        onDispose { paragraph.close() }
    }
    // 行高 = 真墨盒高度（裁掉字体度量的上下空隙）：Adobe 宋体 Std 的 3em 行盒里
    // 墨只占中段，裁后名行与其余行同高；真溢墨（Zapfino 类）墨盒本就含溢出部分，
    // 行照样撑高——画布按墨顶偏移，保证各行墨顶对齐。
    val inkBox = remember(entry.family, display, px, ink, collection) {
        // key 拼集合身份：同族重导新文件（collection 换实例）即重算，不吃过期墨盒。
        // 存紧墨盒（见 inkTightBoxOfParagraph）：行即墨包络，度量空隙不撑行。
        val key = "tight\u0001${entry.family}\u0001$display\u0001${px.toInt()}\u0001${System.identityHashCode(collection)}"
        inkCache.computeIfAbsent(key) { inkTightBoxOfParagraph(paragraph, paragraph.height) }
    }
    val h = with(density) { (inkBox.bottom - inkBox.top).toDp() }.coerceAtLeast(1.dp)
    Canvas(modifier.height(h)) {
        paragraph.paint(drawContext.canvas.nativeCanvas as org.jetbrains.skia.Canvas, 0f, -inkBox.top)
    }
}

/** 一次量高结果：栅格墨迹的顶/底（段落坐标，底已 ≥ 度量行高）。 */
internal data class InkBox(val top: Float, val bottom: Float)

/** 导入行专属预览集合缓存：路径+长度+mtime → 集合（同文件滚动复用，不重复读 MB 级字节）。 */
private val previewCollections = ConcurrentHashMap<String, FontCollection>()

/**
 * 导入行预览集合：直读私有文件註冊族名 + 别名（与共用池 `forFace` 双注册同口径），
 * 系统行/读不到/解析失败回共用池。文件消失时顺手清掉该路径的过期缓存。
 */
private fun previewCollection(entry: FontEntry): FontCollection {
    val face = (entry as? FontEntry.Imported)?.face ?: return SkiaFontPool.current()
    val path = face.path ?: return SkiaFontPool.current()
    val file = runCatching { File(path) }.getOrNull()
    if (file == null || !file.isFile) {
        previewCollections.keys.removeIf { it.startsWith("$path|") }
        return SkiaFontPool.current()
    }
    val key = "$path|${file.length()}|${file.lastModified()}"
    previewCollections[key]?.let { return it }
    val bytes = runCatching { file.readBytes() }.getOrNull()
        ?.takeIf { it.isNotEmpty() } ?: return SkiaFontPool.current()
    val built = runCatching {
        SkParagraphFactory.embeddedFontCollection(
            listOf(SkiaFontPool.EmbeddedFont.forFace(face.familyName, face.displayName, bytes)),
        )
    }.getOrNull() ?: return SkiaFontPool.current()
    previewCollections[key] = built
    return built
}

/** 进程内量高缓存：(family, display, 字号px) → 墨盒。字库目录稳定 → 无上限担忧。 */
internal val inkCache = ConcurrentHashMap<String, InkBox>()

/**
 * 段落墨迹盒真值：把已构好的 [paragraph] 栅格到临时位图并逐行扫描（与面板实际
 * 绘制用**同一个** paragraph 对象 → 字形/回退/度量零差异），返回真墨迹的 [InkBox]。
 * 背景（2026-09-23 实机探针，326 族全量栅格）：仅 4 族真溢墨——Zapfino +32px、
 * jpfont-nds +68px、SignPainter +3px、BM Hanna 11yrs Old +2px（相对 `max(paraH,
 * getRectsForRange·TIGHT)`）；该 4 族里 rects 有 2 族直接报等于行高（假阴性）。
 * 栅格即用户肉眼的像素真值，是唯一可信下缘。空墨（空展示串等）回退度量行高。
 *
 * 下缘含 `max(度量行高)` 保底（溢墨族行盒不塌）；预览裁行用不含保底的
 * [inkTightBoxOfParagraph]（Adobe 宋体 Std 这类 3em 度量的空隙不许撑行）。
 */
internal fun inkBoxOfParagraph(paragraph: Paragraph, paraH: Float, bottomSlackPx: Int = 96): InkBox {
    val (top, bottom) = scanInkRows(paragraph, paraH, bottomSlackPx)
    return if (top < 0) InkBox(0f, paraH)
    else InkBox(top.toFloat(), maxOf(paraH, (bottom + 1).toFloat()))
}

/**
 * 紧墨盒：栅格真墨的上下界（无度量保底，空墨回退度量行盒）。
 * 预览行按此裁（`h = bottom - top` + 按 `-top` 偏移绘制）：行即墨的精确包络，
 * 度量空隙（上/下）全裁，真溢墨（下）全留——与保底版零复用，各走各的语义。
 */
internal fun inkTightBoxOfParagraph(paragraph: Paragraph, paraH: Float, bottomSlackPx: Int = 96): InkBox {
    val (top, bottom) = scanInkRows(paragraph, paraH, bottomSlackPx)
    return if (top < 0) InkBox(0f, paraH)
    else InkBox(top.toFloat(), (bottom + 1).toFloat())
}

private fun scanInkRows(paragraph: Paragraph, paraH: Float, bottomSlackPx: Int): Pair<Int, Int> {
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
                return top to bottom
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