package orilumn.reader.engine.skia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 波浪几何锁：正文落墨与浮动菜单的线型样张**共用同一份**（[decorWaveGeometry]）。
 *
 * 之前样张自己按「固定四个半波」画，波长是正文的五倍多 —— 菜单里是一道长弧、正文里是密集小锯齿，
 * 看上去完全是两种线。这里把 Chromium 口径的数值钉死，谁想改都得先看这个测试。
 */
class DecorWaveGeometryTest {

    @Test
    fun `波长与控制点按 Chromium 口径随线宽线性放大`() {
        // 正文 20px 字号：线宽 20 * 0.08 = 1.6px
        // 波长 = 1 + 2*round(2*1.6+0.5) = 9；控制点 = 0.5 + round(3*1.6+0.5) = 5.5
        val small = decorWaveGeometry(20f * ANNOTATION_UNDERLINE_THICKNESS_EM)
        assertEquals(9f, small.wavelength, 0.001f)
        assertEquals(5.5f, small.controlOffset, 0.001f)

        // 正文 44px 字号：线宽 3.52px ⇒ 波长 17、控制点 11.5
        val body = decorWaveGeometry(44f * ANNOTATION_UNDERLINE_THICKNESS_EM)
        assertEquals(17f, body.wavelength, 0.001f)
        assertEquals(11.5f, body.controlOffset, 0.001f)
    }

    @Test
    fun `线宽很细时仍给得出可画的波形`() {
        val g = decorWaveGeometry(0f)
        assertEquals(1f, g.thickness, 0.001f)
        assertTrue("波长必须为正（否则画不出一个波）", g.wavelength > 0f)
        assertTrue("控制点必须为正（否则是一条直线）", g.controlOffset > 0f)
    }

    @Test
    fun `半高把整条波形连同笔宽一起包住`() {
        for (thickness in 1..12) {
            val g = decorWaveGeometry(thickness.toFloat())
            // 每段的三次贝塞尔两端控制点上下对称，曲线峰值只有控制点的 0.289 倍（不是 0.75），
            // 再加半个笔宽，必须落在半高之内 —— 否则波峰会画出色带被裁掉。
            val peak = 0.289f * g.controlOffset + g.thickness / 2f
            assertTrue(
                "线宽 $thickness ⇒ 半高 ${g.halfHeight} < 波峰 $peak（会画出色带、被裁掉）",
                g.halfHeight > peak,
            )
        }
    }
}
