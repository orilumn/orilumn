package orilumn.reader.ui.reader

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.dp
import orilumn.reader.engine.paging.PageSlice
import orilumn.reader.engine.skia.DrawLine
import orilumn.reader.engine.skia.LineWindowDrawer
import orilumn.reader.engine.skia.PageBackground
import orilumn.reader.engine.skia.PageImage

/** 选区底色（ARGB）：半透明蓝，压不住文字。 */
internal const val SELECTION_FILL_ARGB: Int = 0x405A9BFF

/** 选区手柄颜色；白边在 [SelectionOverlay] 里单独画。 */
internal const val SELECTION_HANDLE_ARGB: Int = 0xFF2F6FED.toInt()

internal const val SELECTION_HANDLE_RADIUS_DP: Float = 6f
internal const val SELECTION_HANDLE_HIT_RADIUS_DP: Float = 22f

/** 当前页上选区的几何结果（屏幕坐标，已含内容区原点与页面位移）。 */
internal data class SelectionPageGeometry(
    val rects: List<Rect>,
    val startHandle: Offset?,
    val endHandle: Offset?,
)

/**
 * 把章内选区投到当前页（用户层轻量覆盖层，**不重烤栅格**）。
 *
 * `decorRects` 与标注装饰同一份逐字几何；这里只做页面平移与手柄端点判定。
 * 选区跨页时只返回本页交集，端点不在本页则对应手柄为 null（D-8：不画假手柄）。
 */
internal fun selectionPageGeometry(
    drawer: LineWindowDrawer,
    lines: List<DrawLine>,
    chapter: Int,
    start: Int,
    endExclusive: Int,
    pageSlice: PageSlice,
    frameLead: Int,
    pageImages: List<PageImage>?,
    pageBackgrounds: List<PageBackground>?,
    contentLeft: Float,
    contentTop: Float,
    contentRight: Float,
    contentBottom: Float,
    handleRadiusPx: Float,
): SelectionPageGeometry? {
    if (endExclusive <= start || lines.isEmpty()) return null
    val shift = readerPageShift(lines, pageImages, pageBackgrounds, frameLead, contentTop) ?: return null
    val raw = drawer.decorRects(lines, chapter, start..(endExclusive - 1))
    if (raw.isEmpty()) return null
    val rects = raw.map {
        Rect(
            left = contentLeft + it.left,
            top = it.top - shift,
            right = contentLeft + it.right,
            bottom = it.bottom - shift,
        )
    }
    val endChar = endExclusive - 1
    val startOnPage = start in pageSlice.charStart until pageSlice.charEnd
    val endOnPage = endChar in pageSlice.charStart until pageSlice.charEnd
    val minX = contentLeft + handleRadiusPx
    val maxX = maxOf(minX, contentRight - handleRadiusPx)
    val minY = contentTop + handleRadiusPx
    val maxY = maxOf(minY, contentBottom - handleRadiusPx)
    fun clampX(x: Float): Float = x.coerceIn(minX, maxX)
    fun clampY(y: Float): Float = y.coerceIn(minY, maxY)
    val first = rects.first()
    val last = rects.last()
    return SelectionPageGeometry(
        rects = rects,
        startHandle = if (startOnPage) Offset(clampX(first.left), clampY(first.bottom)) else null,
        endHandle = if (endOnPage) Offset(clampX(last.right), clampY(last.bottom)) else null,
    )
}

/**
 * 选区覆盖层：拖选/手柄移动时即时画，不参与页栅格。
 *
 * 画在页面画布之上、亮度遮罩之下；翻页动画期间由调用方隐藏，避免盖在滑动中的两页上。
 */
@Composable
internal fun SelectionOverlay(
    drawer: LineWindowDrawer,
    lines: List<DrawLine>,
    chapter: Int,
    start: Int,
    endExclusive: Int,
    pageSlice: PageSlice,
    frameLead: Int,
    pageImages: List<PageImage>?,
    pageBackgrounds: List<PageBackground>?,
    contentLeft: Float,
    contentTop: Float,
    contentRight: Float,
    contentBottom: Float,
    modifier: Modifier = Modifier,
) {
    val radiusPx = with(androidx.compose.ui.platform.LocalDensity.current) {
        SELECTION_HANDLE_RADIUS_DP.dp.toPx()
    }
    val geometry = remember(
        drawer,
        lines,
        chapter,
        start,
        endExclusive,
        pageSlice,
        frameLead,
        pageImages,
        pageBackgrounds,
        contentLeft,
        contentTop,
        contentRight,
        contentBottom,
        radiusPx,
    ) {
        selectionPageGeometry(
            drawer = drawer,
            lines = lines,
            chapter = chapter,
            start = start,
            endExclusive = endExclusive,
            pageSlice = pageSlice,
            frameLead = frameLead,
            pageImages = pageImages,
            pageBackgrounds = pageBackgrounds,
            contentLeft = contentLeft,
            contentTop = contentTop,
            contentRight = contentRight,
            contentBottom = contentBottom,
            handleRadiusPx = radiusPx,
        )
    } ?: return

    Canvas(modifier = modifier) {
        if (geometry.rects.isEmpty()) return@Canvas
        clipRect(contentLeft, contentTop, contentRight, contentBottom) {
            val fill = Color(SELECTION_FILL_ARGB)
            for (r in geometry.rects) {
                drawRect(
                    color = fill,
                    topLeft = Offset(r.left, r.top),
                    size = Size(r.width, r.height),
                )
            }
            val handle = Color(SELECTION_HANDLE_ARGB)
            val white = Color.White
            for (center in listOfNotNull(geometry.startHandle, geometry.endHandle)) {
                drawCircle(color = white, radius = radiusPx + 2f, center = center)
                drawCircle(color = handle, radius = radiusPx, center = center)
            }
        }
    }
}
