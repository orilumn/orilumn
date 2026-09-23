package orilumn.reader.ui.reader

import orilumn.reader.engine.skia.DrawLine
import org.junit.Assert.assertEquals
import org.junit.Test

/** S28 阅读面纯逻辑单测（画布窗口平移 / 手势判定 / 进度 / 亮度遮罩 / 字体切换路由）。 */
class ReaderLogicTest {

    private fun line(yTop: Int, yBottom: Int = yTop + 20): DrawLine =
        DrawLine(
            text = "测",
            range = 0..1,
            yTop = yTop,
            yBottom = yBottom,
            alignment = orilumn.reader.engine.css.TextAlign.LEFT,
            fontSizePx = 18f,
            lineHeightRatio = 1.5f,
            tag = null,
            families = emptyList(),
            weight = 400,
            italic = false,
            monospace = false,
            letterSpacingEm = 0f,
            lineWidthPx = 300,
        )

    // ---- 三区点按 ----

    @Test
    fun linkTapDebounce_swallowsZoneOnlyInsideWindow() {
        // 手抖：链接跳转后 500ms 内的三区点按吞掉；窗外恢复；从未点过链接不吞。
        assertEquals(true, ReaderMath.linkTapDebounced(1000L, 900L))
        assertEquals(true, ReaderMath.linkTapDebounced(1000L, 501L))
        assertEquals(false, ReaderMath.linkTapDebounced(1000L, 500L))
        assertEquals(false, ReaderMath.linkTapDebounced(1000L, 0L))
        assertEquals(false, ReaderMath.linkTapDebounced(1000L, 2000L))
    }

    @Test
    fun tapZone_adoptsOldThreeZones() {
        assertEquals(-1, ReaderMath.tapZone(0f, 900f))
        assertEquals(-1, ReaderMath.tapZone(299f, 900f))
        assertEquals(0, ReaderMath.tapZone(300f, 900f))
        assertEquals(0, ReaderMath.tapZone(600f, 900f))
        assertEquals(1, ReaderMath.tapZone(601f, 900f))
        assertEquals(1, ReaderMath.tapZone(899f, 900f))
    }

    @Test
    fun downInBars_yieldsBarGesturesToBars() {        // 栏隐藏：一切落点都归翻页层。
        assertEquals(false, ReaderMath.downInBars(false, 10f, 800f, 120f, 200f))
        assertEquals(false, ReaderMath.downInBars(false, 790f, 800f, 120f, 200f))
        // 栏显露：顶/底栏带内落点归栏，内容区归翻页层。
        assertEquals(true, ReaderMath.downInBars(true, 10f, 800f, 120f, 200f))
        assertEquals(true, ReaderMath.downInBars(true, 790f, 800f, 120f, 200f))
        assertEquals(false, ReaderMath.downInBars(true, 400f, 800f, 120f, 200f))
        // 未测到栏高（0）即不门控该侧。
        assertEquals(false, ReaderMath.downInBars(true, 10f, 800f, 0f, 0f))
    }

    @Test
    fun keyAction_mapsDesktopKeys() {
        assertEquals(
            ReaderMath.ReaderKeyAction.Prev,
            ReaderMath.keyAction(androidx.compose.ui.input.key.Key.DirectionLeft),
        )
        assertEquals(
            ReaderMath.ReaderKeyAction.Next,
            ReaderMath.keyAction(androidx.compose.ui.input.key.Key.DirectionRight),
        )
        assertEquals(
            ReaderMath.ReaderKeyAction.MiddleTap,
            ReaderMath.keyAction(androidx.compose.ui.input.key.Key.Escape),
        )
        assertEquals(null, ReaderMath.keyAction(androidx.compose.ui.input.key.Key.Enter))
    }

    @Test
    fun keyAction_reservesUpDownForFutureScroll() {
        // 阅读面不绑上下箭头（将来滚动模式用）；面板内上下另走焦点遍历，不经此表。
        assertEquals(null, ReaderMath.keyAction(androidx.compose.ui.input.key.Key.DirectionUp))
        assertEquals(null, ReaderMath.keyAction(androidx.compose.ui.input.key.Key.DirectionDown))
    }

    // ---- 手势主轴 ----

    @Test
    fun gestureAxis_requiresSlopAndDominance_likeOldDetector() {
        assertEquals(ReaderMath.Axis.HORIZONTAL, ReaderMath.gestureAxis(40f, 5f, slop = 24f))
        assertEquals(ReaderMath.Axis.VERTICAL, ReaderMath.gestureAxis(5f, 40f, slop = 24f))
        assertEquals(ReaderMath.Axis.NONE, ReaderMath.gestureAxis(10f, 10f, slop = 24f))
        // |dy| 未满足 > |dx|*1.2 → 不判为垂直
        assertEquals(ReaderMath.Axis.NONE, ReaderMath.gestureAxis(30f, 30f, slop = 24f))
        // |dx| 恰好过 slop 但纵向不占优 → 保持未定型
        assertEquals(ReaderMath.Axis.NONE, ReaderMath.gestureAxis(26f, 30f, slop = 24f))
    }

    @Test
    fun flipDirection_leftDragNext_rightDragPrev() {
        assertEquals(1, ReaderMath.flipDirection(-100f))
        assertEquals(-1, ReaderMath.flipDirection(100f))
        assertEquals(-1, ReaderMath.flipDirection(0f))
    }

    @Test
    fun verticalBrightnessAllowed_onlyInEnabledEdgeZones() {
        assertEquals(true, ReaderMath.verticalBrightnessAllowed(-1, leftEnabled = true, rightEnabled = false))
        assertEquals(true, ReaderMath.verticalBrightnessAllowed(1, leftEnabled = false, rightEnabled = true))
        assertEquals(false, ReaderMath.verticalBrightnessAllowed(-1, leftEnabled = false, rightEnabled = true))
        assertEquals(false, ReaderMath.verticalBrightnessAllowed(0, leftEnabled = true, rightEnabled = true))
        assertEquals(false, ReaderMath.verticalBrightnessAllowed(1, leftEnabled = false, rightEnabled = false))
    }

    @Test
    fun brightnessFrac_upIsPositive_scaledByViewHeight() {
        assertEquals(0.1f, ReaderMath.brightnessFrac(-100f, 1000f), 1e-6f)
        assertEquals(-0.25f, ReaderMath.brightnessFrac(250f, 1000f), 1e-6f)
        assertEquals(0f, ReaderMath.brightnessFrac(123f, 0f), 1e-6f)
    }

    // ---- 亮度手势落位 ----

    @Test
    fun brightnessFromDelta_accumulatesAndClamps() {
        assertEquals(100, ReaderMath.brightnessFromDelta(50, 0.5f))
        assertEquals(75, ReaderMath.brightnessFromDelta(50, 0.25f))
        assertEquals(-50, ReaderMath.brightnessFromDelta(-30, -1f))
        assertEquals(-50, ReaderMath.brightnessFromDelta(-40, -1f))
        assertEquals(100, ReaderMath.brightnessFromDelta(90, 0.3f))
    }

    // ---- 亮度/护眼遮罩 ----

    @Test
    fun dimAlphaOf_mapsNegativeBrightnessToMask() {
        assertEquals(0.8f, ReaderMath.dimAlphaOf(-50), 1e-6f)
        assertEquals(0.5f, ReaderMath.dimAlphaOf(-25), 1e-6f)
        assertEquals(0f, ReaderMath.dimAlphaOf(0), 1e-6f)
        assertEquals(0f, ReaderMath.dimAlphaOf(100), 1e-6f)
    }

    @Test
    fun warmAlphaOf_mapsEyeProtectionLevel() {
        assertEquals(0f, ReaderMath.warmAlphaOf(0), 1e-6f)
        assertEquals(0.22f, ReaderMath.warmAlphaOf(100), 1e-6f)
        assertEquals(0.088f, ReaderMath.warmAlphaOf(40), 1e-6f)
    }

    @Test
    fun warmColor_matchesAndroidColor_rgb() {
        // Android 旧实现 Color.rgb(255, 178, 125) = 0xFFFFB27D
        assertEquals(0xFFFFB27DL, ReaderMath.ReaderWarmColor)
    }

    // ---- 进度百分比 ----

    @Test
    fun progressPercent_roundsDownAndClamps() {
        assertEquals(23, ReaderMath.progressPercent(0.2345f))
        assertEquals(0, ReaderMath.progressPercent(-0.2f))
        assertEquals(100, ReaderMath.progressPercent(2f))
        assertEquals(50, ReaderMath.progressPercent(0.5f))
    }

    // ---- 字体切换路由 ----

    @Test
    fun fontSlotFor_routesBodyTitleAndCode() {
        assertEquals("body", ReaderMath.fontSlotFor("p", monospace = false, body = "body", title = "title", code = "code"))
        assertEquals("title", ReaderMath.fontSlotFor("h2", monospace = false, body = "body", title = "title", code = "code"))
        assertEquals("title", ReaderMath.fontSlotFor("h6", monospace = false, body = "body", title = "title", code = "code"))
        assertEquals("code", ReaderMath.fontSlotFor("pre", monospace = false, body = "body", title = "title", code = "code"))
        assertEquals("code", ReaderMath.fontSlotFor("div", monospace = true, body = "body", title = "title", code = "code"))
        assertEquals("code", ReaderMath.fontSlotFor(null, monospace = true, body = "body", title = "title", code = "code"))
        assertEquals("body", ReaderMath.fontSlotFor(null, monospace = false, body = "body", title = "title", code = "code"))
        // 非 h 标签（如 hsts）不误判为标题
        assertEquals("body", ReaderMath.fontSlotFor("hsts", monospace = false, body = "body", title = "title", code = "code"))
    }

    // ---- 画布行窗口平移 ----

    @Test
    fun shiftToPageFrame_shiftsAbsoluteYIntoPageFrame() {
        val lines = listOf(line(120), line(140), line(160))
        val shifted = ReaderMath.shiftToPageFrame(lines, shift = 70)
        assertEquals(listOf(50, 70, 90), shifted.map { it.yTop })
        assertEquals(listOf(70, 90, 110), shifted.map { it.yBottom })
    }

    @Test
    fun shiftToPageFrame_zeroOrEmptyIsNoop() {
        assertEquals(emptyList<DrawLine>(), ReaderMath.shiftToPageFrame(emptyList(), shift = 70))
        val lines = listOf(line(120))
        assertEquals(lines, ReaderMath.shiftToPageFrame(lines, shift = 0))
    }

    @Test
    fun shiftToPageFrame_preservesTextFields() {
        val l = line(120, yBottom = 136)
        val shifted = ReaderMath.shiftToPageFrame(listOf(l), shift = 20).single()
        assertEquals(l.text, shifted.text)
        assertEquals(l.range, shifted.range)
        assertEquals(l.fontSizePx, shifted.fontSizePx, 1e-6f)
        assertEquals(l.lineWidthPx, shifted.lineWidthPx)
        assertEquals(100, shifted.yTop)
        assertEquals(116, shifted.yBottom)
    }

    // ---- P4-c2u 点按锚点/行命中 ----

    @Test
    fun pageAnchorY_takesMinAndIgnoresNulls() {
        assertEquals(100, ReaderMath.pageAnchorY(120, 100, null))
        assertEquals(90, ReaderMath.pageAnchorY(120, 100, 90))
        assertEquals(120, ReaderMath.pageAnchorY(120, null, null))
        assertEquals(null, ReaderMath.pageAnchorY(null, null, null))
    }

    @Test
    fun tapLineAt_hitsLineAndComputesParagraphCoords() {
        val l1 = line(100, 120).copy(xLeft = 10, firstLineIndentPx = 5f)
        val l2 = line(120, 140).copy(xLeft = 10)
        val lines = listOf(l1, l2)
        // 页坐标 y=10 落首行 [0,20)：段落 x = 30-10-5 = 15，行内 y = 10。
        val hit = ReaderMath.tapLineAt(lines, shift = 100, tapX = 30f, tapY = 10f)
        assertEquals(l1, hit?.first)
        assertEquals(15f, hit?.second ?: Float.NaN, 1e-6f)
        assertEquals(10f, hit?.third ?: Float.NaN, 1e-6f)
        // y=20 是次行起点（含首不含尾）。
        assertEquals(l2, ReaderMath.tapLineAt(lines, shift = 100, tapX = 30f, tapY = 20f)?.first)
    }

    @Test
    fun tapLineAt_missesGapsAndOutside() {
        val lines = listOf(line(0, 20), line(30, 50))
        assertEquals(null, ReaderMath.tapLineAt(lines, shift = 0, tapX = 10f, tapY = 25f))
        assertEquals(null, ReaderMath.tapLineAt(lines, shift = 0, tapX = 10f, tapY = 60f))
        assertEquals(null, ReaderMath.tapLineAt(lines, shift = 0, tapX = 10f, tapY = -1f))
    }

    @Test
    fun tapLineAt_negativeIndentClampsToZero() {
        val l = line(0, 20).copy(xLeft = 10, firstLineIndentPx = -3f)
        assertEquals(20f, ReaderMath.tapLineAt(listOf(l), shift = 0, tapX = 30f, tapY = 10f)?.second ?: Float.NaN, 1e-6f)
    }
}