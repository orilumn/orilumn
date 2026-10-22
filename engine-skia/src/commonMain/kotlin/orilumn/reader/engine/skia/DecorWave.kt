package orilumn.reader.engine.skia

/**
 * 读者标注划线的默认粗细（em）：比书内下划线更粗，避免两线糊在一起。
 *
 * 浮动菜单里的线型样张也按这条规则取线宽（见 `AnnotationMenu.drawLineStyleGlyph`）：
 * 样张只有用**真迹的粗细**画，波长与振幅才和正文里那条线同比例。
 */
const val ANNOTATION_UNDERLINE_THICKNESS_EM = 0.08f

/** 虚线：实线段 / 空档 长度相对线粗的倍数（Chromium `dash_on = 3 * thickness`）。 */
const val ANNOTATION_DASH_ON_RATIO = 3f
const val ANNOTATION_DASH_OFF_RATIO = 2f

/**
 * 波浪装饰的波形几何（Chromium `TextDecoration` 的 wavy 口径）。
 *
 * 正文落墨（[LineWindowDrawer] 的 `drawDecorationWave`）与浮动菜单里的线型样张**共用这一份**：
 * 样张曾经自己按「固定四个半波」来画，波长是正文的五倍多 —— 菜单里看着是一道长弧，
 * 落到正文却是密集的小锯齿，肉眼一看就是两种线。
 *
 * 波长与控制点偏移都随线宽线性放大（Chromium 的口径），所以只要线宽对了，波形就对了。
 */
class DecorWaveGeometry(
    /** 落笔线宽（px，已夹到 1px 下限）。 */
    val thickness: Float,
    /** 一个完整波长（px）。 */
    val wavelength: Float,
    /** 三次贝塞尔控制点的纵向偏移（px）；实际起伏只有 **0.289** 倍。 */
    val controlOffset: Float,
) {
    /** 波形半高（px）：线心上下各留这么多就绝不会画出色带（含笔宽与 1px 余量）。 */
    val halfHeight: Float get() = 0.289f * controlOffset + thickness + 2f
}

/** 按线宽求波形几何，见 [DecorWaveGeometry]。 */
fun decorWaveGeometry(thicknessPx: Float): DecorWaveGeometry {
    val t = if (thicknessPx > 0f) maxOf(thicknessPx, 1f) else 1f
    return DecorWaveGeometry(
        thickness = t,
        wavelength = 1f + 2f * roundHalfUp(2f * t + 0.5f),
        controlOffset = 0.5f + roundHalfUp(3f * t + 0.5f),
    )
}

/**
 * 半边取整（向下取整 + 0.5）：Chromium 用 `round()` 以外的「四舍五入」口径。
 *
 * Kotlin 的 `kotlin.math.round` 是「就近取偶」（`round(2.5) = 2`），照它算会与浏览器差一整个波长
 * （实测 20px 字号：浏览器波长 11px，取偶口径 9px）。
 */
internal fun roundHalfUp(v: Float): Float = kotlin.math.floor(v + 0.5f)
