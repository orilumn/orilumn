package orilumn.reader.ui.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/** 放大镜直径（D-10 / §3.4）。 */
internal const val MAGNIFIER_DIAMETER_DP = 96f

/** 放大倍数。 */
internal const val MAGNIFIER_ZOOM = 1.5f

/**
 * 圆心相对手指上移的距离。
 *
 * 手指接触面直径约 9~10mm（≈44dp），半径 48dp 的圆若只抬 56dp，圆的下沿离指尖只有 8dp
 * ⇒ 放大镜下缘被手指盖住一角，正好压住放大镜里最该看清的那行字。抬到 76dp 后圆下沿离
 * 指尖 28dp，指腹不再压住镜面；贴顶时 [magnifierCenter] 仍会翻到手指下方。
 */
internal const val MAGNIFIER_OFFSET_DP = 76f

/** 贴屏幕边时的内移量。 */
internal const val MAGNIFIER_EDGE_INSET_DP = 8f

/**
 * 放大镜圆心（屏幕坐标 px）：优先在手指上方 [offsetPx]，贴顶时翻到手指下方；
 * 左右/上下再按 [insetPx] 内移，保证整个圆落在 [bounds] 内。
 *
 * 抽成纯函数以便单测，避免「贴边被裁掉一半」只能靠真机发现。
 */
internal fun magnifierCenter(
    finger: Offset,
    bounds: Rect,
    radiusPx: Float,
    offsetPx: Float,
    insetPx: Float,
): Offset {
    val minX = bounds.left + insetPx + radiusPx
    val maxX = bounds.right - insetPx - radiusPx
    val x = if (minX <= maxX) finger.x.coerceIn(minX, maxX) else bounds.center.x

    val minY = bounds.top + insetPx + radiusPx
    val maxY = bounds.bottom - insetPx - radiusPx
    val y = when {
        minY > maxY -> bounds.center.y
        finger.y - offsetPx >= minY -> (finger.y - offsetPx).coerceAtMost(maxY)
        else -> (finger.y + offsetPx).coerceIn(minY, maxY)
    }
    return Offset(x, y)
}

/**
 * 放大镜内容平移：把页面坐标 [finger] 映射到圆心 [center]。
 *
 * 图层以左上角为缩放原点（`TransformOrigin(0, 0)`），因此缩放后的点 = zoom * p + translation；
 * 令其等于圆心即得本式。
 */
internal fun magnifierTranslation(center: Offset, finger: Offset, zoom: Float): Offset =
    Offset(center.x - zoom * finger.x, center.y - zoom * finger.y)

/**
 * 移动端选区放大镜（用户层，D-10 / §3.4）。
 *
 * 内容**复用同一页栅格**：把同一个 [PageContent] / [ReaderPageRenderer] 再画一遍，
 * 由渲染器的多页位图缓存直接命中，不另开位图、不重排版；外层只做圆形裁剪与 1.5 倍缩放。
 * 因此静止页与放大镜里的像素同源，标注/高亮也一起被放大。
 *
 * 选区底色与手柄**和正文叠在同一个缩放层里**（同一份 [selection] 几何）：放大镜里看得见
 * 选到哪儿、手柄被拉到哪儿——只放大素字而看不到选区，等于没放大。
 */
@Composable
internal fun SelectionMagnifier(
    content: PageContent,
    finger: Offset,
    bounds: Rect,
    contentLeft: Float,
    contentTop: Float,
    contentRight: Float,
    contentBottom: Float,
    pageBg: Int,
    inkColor: Int,
    contentRevision: Int,
    renderer: ReaderPageRenderer,
    onMiss: (() -> Unit)?,
    /** 当前页的选区几何（与正文覆盖层共用一份）；为空时只放大正文。 */
    selection: SelectionPageGeometry?,
    selectionHandleRadiusPx: Float,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val radiusPx = with(density) { (MAGNIFIER_DIAMETER_DP / 2f).dp.toPx() }
    val offsetPx = with(density) { MAGNIFIER_OFFSET_DP.dp.toPx() }
    val insetPx = with(density) { MAGNIFIER_EDGE_INSET_DP.dp.toPx() }
    val center = remember(finger, bounds, radiusPx, offsetPx, insetPx) {
        magnifierCenter(finger, bounds, radiusPx, offsetPx, insetPx)
    }
    val translation = remember(center, finger) {
        magnifierTranslation(center, finger, MAGNIFIER_ZOOM)
    }

    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    val circle = Path().apply {
                        addOval(
                            Rect(
                                left = center.x - radiusPx,
                                top = center.y - radiusPx,
                                right = center.x + radiusPx,
                                bottom = center.y + radiusPx,
                            ),
                        )
                    }
                    clipPath(circle) { this@drawWithContent.drawContent() }
                },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = MAGNIFIER_ZOOM
                        scaleY = MAGNIFIER_ZOOM
                        transformOrigin = TransformOrigin(0f, 0f)
                        translationX = translation.x
                        translationY = translation.y
                    },
            ) {
                ReaderPageCanvas(
                    content = content,
                    contentLeft = contentLeft,
                    contentTop = contentTop,
                    contentRectLeft = contentLeft,
                    contentRectTop = contentTop,
                    contentRectRight = contentRight,
                    contentRectBottom = contentBottom,
                    pageBg = pageBg,
                    inkColor = inkColor,
                    modifier = Modifier.fillMaxSize(),
                    contentRevision = contentRevision,
                    onMiss = onMiss,
                    renderer = renderer,
                )
                // 选区跟着一起放大：只放大素字、看不见选到哪儿，放大镜就失去意义。
                if (selection != null) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawSelectionGeometry(
                            geometry = selection,
                            handleRadiusPx = selectionHandleRadiusPx,
                            clip = Rect(contentLeft, contentTop, contentRight, contentBottom),
                        )
                    }
                }
            }
        }
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                color = Color.Black.copy(alpha = 0.28f),
                radius = radiusPx + 2f,
                center = center,
                style = Stroke(width = 4f),
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.9f),
                radius = radiusPx,
                center = center,
                style = Stroke(width = 2f),
            )
        }
    }
}
