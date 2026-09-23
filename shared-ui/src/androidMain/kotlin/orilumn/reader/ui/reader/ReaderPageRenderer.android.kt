package orilumn.reader.ui.reader

import android.graphics.Canvas as AndroidCanvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.nativeCanvas
import orilumn.reader.engine.skia.DecodedImage
import orilumn.reader.engine.skia.DrawLine
import orilumn.reader.engine.skia.LineWindowDrawer
import orilumn.reader.engine.skia.PageBackground
import orilumn.reader.engine.skia.drawPageBackground
import kotlin.math.roundToInt
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface as SkiaSurface

/**
 * Android actual：CMP 的 DrawScope 在 Android 上暴露的是
 * `android.graphics.Canvas`，无法直接取出 skia Canvas，故用 skia 离屏
 * [SkiaSurface] 走同一套 [LineWindowDrawer] 光栅化，再把像素桥回
 * `android.graphics.Bitmap` 之后 drawBitmap 到 Compose 画布。
 *
 * 像素桥（m144）：`Bitmap.readPixels` 在真机回 null 且无诊断，改单跳 PNG 编解码。
 *
 * 页缓存：调用方 `ReaderScreen` 经 `remember(pos)` 给出同页同一 List 实例；内容不变的
 * 重组（点工具栏/设置/目录）直接复用成品位图，不再逐行整形+编解码——否则主线程每帧
 * 几百毫秒，全部交互都慢一截。翻页（新实例）才走全量栅格化。
 */
@Composable
actual fun rememberReaderPageRenderer(): ReaderPageRenderer = remember { AndroidReaderPageRenderer() }

private class AndroidReaderPageRenderer : ReaderPageRenderer {
    private val drawer = LineWindowDrawer()

    private var surface: SkiaSurface? = null
    private var width = -1
    private var height = -1
    private var cachedLines: List<DrawLine>? = null
    private var cachedBg: Int = 0
    private var cachedBgs: List<PageBackground>? = null
    private var cachedBgImgKeys: Set<String>? = null
    private var cachedPage: android.graphics.Bitmap? = null

    override fun drawLines(
        canvas: Canvas,
        contentLeft: Float,
        lines: List<DrawLine>,
        contentRectLeft: Float,
        contentRectTop: Float,
        contentRectRight: Float,
        contentRectBottom: Float,
        pageBg: Int,
        backgrounds: List<PageBackground>,
        bgImages: Map<String, DecodedImage>,
    ) {
        val w = (contentRectRight - contentRectLeft).toInt().coerceAtLeast(1)
        val h = (contentRectBottom - contentRectTop).toInt().coerceAtLeast(1)
        if (surface == null || w != width || h != height) {
            width = w
            height = h
            surface?.close()
            surface = SkiaSurface.makeRasterN32Premul(w, h)
            cachedLines = null
            cachedPage?.recycle()
            cachedPage = null
        }
        // 同页命中：行、底色、背景矩形都相等才复用（背景画进成品位图，不比对即串页）。
        // shiftToPageFrame 每次 map 出新 List，=== 永不命中；data class == 按值比对是微秒级。
        // 内容不变的重组零栅格化。P3-b 背景图只比键集（DecodedImage 无值相等，按实例比恒 miss）。
        val bgKeys = backgrounds.mapNotNullTo(HashSet()) { it.bgKey()?.takeIf { bgImages.containsKey(it) } }
        val hit = cachedPage?.takeIf { pageBg == cachedBg && backgrounds == cachedBgs && lines == cachedLines && bgKeys == cachedBgImgKeys }
        if (hit != null) {
            (canvas.nativeCanvas as AndroidCanvas).drawBitmap(
                hit,
                android.graphics.Rect(0, 0, hit.width, hit.height),
                android.graphics.RectF(contentRectLeft, contentRectTop, contentRectRight, contentRectBottom),
                null,
            )
            return
        }

        val t0 = android.os.SystemClock.uptimeMillis()
        val s = surface!!
        // JPEG 无 alpha：先按主题底色打底（与外层 Compose 背景同色，无缝），再落墨。
        s.canvas.clear(pageBg)
        // 离屏 surface 原点 = 内容区左上（0,0 即 contentRectLeft/Top）：X 已在参数侧
        // 归一（contentLeft - contentRectLeft），Y 同理须减 contentRectTop——[lines]
        // 是页坐标系（含上边距 contentTop），不减则文本被整体下压一个上边距
        // （双倍上边距），而 Compose 层直画的插图是单倍，图文错位、图“无视上边距”。
        val yOff = contentRectTop.roundToInt()
        val localLines = if (yOff != 0) {
            lines.map { it.copy(yTop = it.yTop - yOff, yBottom = it.yBottom - yOff) }
        } else {
            lines
        }
        // 盒背景/边框：成品位图不透明，只能画进离屏 surface 且在文字之下（与行同一归一：
        // 行画在 (contentLeft-contentRectLeft)+xLeft，背景同式，xLeft 换成盒左/右缘）。
        // P3-a 单源（圆角/描边环/阴影/alpha，见 drawPageBackground）；P3-b 背景图同形平铺。
        if (backgrounds.isNotEmpty()) {
            val xOff = contentLeft - contentRectLeft
            for (bg in backgrounds) {
                s.canvas.drawPageBackground(bg, xOff, -yOff.toFloat(), bg.bgKey()?.let { bgImages[it]?.image })
            }
        }
        drawer.drawLines(
            canvas = s.canvas,
            contentLeft = contentLeft - contentRectLeft,
            lines = localLines,
            clip = Rect.makeLTRB(0f, 0f, w.toFloat(), h.toFloat()),
        )
        val t1 = android.os.SystemClock.uptimeMillis()
        // 像素桥（m144）：Bitmap.readPixels 真机回 null，PNG 编码又要 ~800ms，只能 JPEG 单跳；
        // 白底黑字 q85 无可见损失，编解码合计 ~100ms；同页命中缓存后为 0。
        val img = s.makeImageSnapshot()
        val jpg: ByteArray? = try {
            img.encodeToData(org.jetbrains.skia.EncodedImageFormat.JPEG, 85)?.bytes
        } finally {
            img.close()
        }
        val t2 = android.os.SystemClock.uptimeMillis()
        if (jpg == null) {
            android.util.Log.w("Orilumn.SkiaBridge", "encodeToData null w=$w h=$h")
            return
        }
        val bmp = android.graphics.BitmapFactory.decodeByteArray(jpg, 0, jpg.size)
        val t3 = android.os.SystemClock.uptimeMillis()
        if (bmp == null) {
            android.util.Log.w("Orilumn.SkiaBridge", "decodeByteArray null jpgBytes=${jpg.size}")
            return
        }
        android.util.Log.w("Orilumn.SkiaBridge", "raster n=${lines.size} shape=${t1 - t0}ms enc=${t2 - t1}ms dec=${t3 - t2}ms jpg=${jpg.size / 1024}KB")
        cachedLines = lines
        cachedBg = pageBg
        cachedBgs = backgrounds
        cachedBgImgKeys = bgKeys
        cachedPage?.recycle()
        cachedPage = bmp
        (canvas.nativeCanvas as AndroidCanvas).drawBitmap(
            bmp,
            android.graphics.Rect(0, 0, bmp.width, bmp.height),
            android.graphics.RectF(contentRectLeft, contentRectTop, contentRectRight, contentRectBottom),
            null,
        )
    }
}