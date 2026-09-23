package orilumn.reader.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Canvas
import orilumn.reader.engine.skia.DecodedImage
import orilumn.reader.engine.skia.DrawLine
import orilumn.reader.engine.skia.PageBackground

/**
 * S28 阅读画布「光栅化缝」：把「行窗口如何变成像素」与阅读面几何收口成一个平台 actual。
 *
 * CMP 1.7 的 [Canvas.nativeCanvas] 在不同目标暴露不同底层画布：Desktop 上是 skia
 * [org.jetbrains.skia.Canvas]（可直接交给 [orilumn.reader.engine.skia.LineWindowDrawer]），
 * Android 上仍是 `android.graphics.Canvas`（无法从中取出 skia Canvas）。因此：
 *  - jvmMain（Desktop）：直接用 skia Canvas 走 [orilumn.reader.engine.skia.LineWindowDrawer]。
 *  - androidMain：用 skia 离屏 [org.jetbrains.skia.Surface] 光栅化，再把像素桥回
 *    `android.graphics.Bitmap` 绘制到 Compose 画布——两条路径共用同一 FontCollection/断行。
 *
 * 语义约定（与 [ReaderPageCanvas] 相同）：[lines] 已是**页面坐标系**（章节绝对 Y 已平移），
 * 画进内容区并裁剪在 [contentRectLeft/Top/Right/Bottom]（物理 px）内。
 */
interface ReaderPageRenderer {
    /**
     * @param pageBg 页面底色（ARGB Int，与 Compose `Color(profile.bgColor)` 同一值）：
     *   Android 离屏 surface 用它打底（JPEG 无 alpha，不打底透明区变黑）；Desktop 直画忽略。
     * @param backgrounds 盒背景/边框（已是页面坐标系，与 [lines] 同一平移）：渲染器画在文字之下；
     *   Android 必须画进离屏 surface（成品位图不透明，画在 Compose 层会被盖住）。
     * @param bgImages P3-b 背景图（键为 [PageBackground.bgKey]，缺失即該幅只留底色）。
     */
    fun drawLines(
        canvas: Canvas,
        contentLeft: Float,
        lines: List<DrawLine>,
        contentRectLeft: Float,
        contentRectTop: Float,
        contentRectRight: Float,
        contentRectBottom: Float,
        pageBg: Int,
        backgrounds: List<PageBackground> = emptyList(),
        bgImages: Map<String, DecodedImage> = emptyMap(),
    )
}

@Composable
expect fun rememberReaderPageRenderer(): ReaderPageRenderer