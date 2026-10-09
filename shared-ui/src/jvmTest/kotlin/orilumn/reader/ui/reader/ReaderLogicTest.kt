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
    fun gestureAxis_横向走快速通道_不等slop() {
        // 真机 FLIPLAT 实测：按下→定轴要 67~184ms，而定轴时 dx 只有 22~47px
        // （slop≈20 刚过）。手指「刚要划」到「划出 30px」的物理起动就是几十毫秒，
        // 所以横向不等 slop：只要动了 ≥2px 且纵向不占优就接管（moon+ 的体感）。
        assertEquals(ReaderMath.Axis.HORIZONTAL, ReaderMath.gestureAxis(3f, 0f, slop = 24f))
        assertEquals(ReaderMath.Axis.HORIZONTAL, ReaderMath.gestureAxis(8f, 2f, slop = 24f))
        assertEquals(ReaderMath.Axis.HORIZONTAL, ReaderMath.gestureAxis(40f, 5f, slop = 24f))
        // 纵向占优时不抢手势（否则横滑时轻微纵向抖动会抢走）
        assertEquals(ReaderMath.Axis.NONE, ReaderMath.gestureAxis(26f, 30f, slop = 24f))
        // dx=2 未达快速通道的「占优」要求（2 > 10*1.2 不成立），dy=10 未过 slop
        // ⇒ 双向都未定型，交给点按判定。
        assertEquals(ReaderMath.Axis.NONE, ReaderMath.gestureAxis(2f, 10f, slop = 24f))
        // 纯抖动（0 / 1px）不接管
        assertEquals(ReaderMath.Axis.NONE, ReaderMath.gestureAxis(0f, 0f, slop = 24f))
        assertEquals(ReaderMath.Axis.NONE, ReaderMath.gestureAxis(1f, 0f, slop = 24f))
    }

    @Test
    fun gestureAxis_纵向仍走slop通道防抖() {
        // 纵向亮度手势要的是明确竖划，早判定会让横滑的纵向抖动抢走手势。
        assertEquals(ReaderMath.Axis.VERTICAL, ReaderMath.gestureAxis(5f, 40f, slop = 24f))
        assertEquals(ReaderMath.Axis.VERTICAL, ReaderMath.gestureAxis(2f, 30f, slop = 24f))
        // 未过 slop 的纵向移动不接管
        assertEquals(ReaderMath.Axis.NONE, ReaderMath.gestureAxis(2f, 10f, slop = 24f))
        // 双向都未过/势均力敌 → 未定型（点按）
        assertEquals(ReaderMath.Axis.NONE, ReaderMath.gestureAxis(10f, 10f, slop = 24f))
        assertEquals(ReaderMath.Axis.NONE, ReaderMath.gestureAxis(30f, 30f, slop = 24f))
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