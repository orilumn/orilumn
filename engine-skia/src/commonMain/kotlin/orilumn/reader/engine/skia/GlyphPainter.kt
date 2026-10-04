package orilumn.reader.engine.skia

import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Typeface
import orilumn.reader.engine.css.FontRun
import kotlin.math.roundToInt

/**
 * S5 逐字绘制器（**渲染层**）：按 [LineAligner] 落好的 x 把一行字画出来，
 * 不再让 `ParagraphBuilder` 二次整形。
 *
 * ## 为什么能逐字画（不是近似）
 *
 * 量宽与取面走 [SkiaRunMeasurer] —— 断行侧算断点用的是它，本类落位用的是它，
 * **同一出口、同一口径**（`x_i = x_0 + Σ(w_j + lsPx_j)`）。而 [SkiaRunMeasurer] 用
 * `Font.getWidths`（裸 cmap 查表）、**不整形**，所以「逐字量」与「逐字画」天然自洽：
 * 每次 `drawString` 只含一个字符，Skia 无从做跨字 kerning/liga，
 * 与量宽口径一致。**这正是当初选择裸 cmap 量宽换来的红利。**
 *
 * ## 代价（已量化，见 `docs/TODO-未尽事宜.md` Q5）
 *
 * 失去跨字 kerning 与 fi/fl 连字 ⇒ 英文技术书的西文排版有细微差异，中文无感知。
 * 这是自建管线的既定取舍，不是缺陷。
 *
 * ## 与 S4 的分工（层级内分工，不越界）
 *
 * - [LineAligner]：**几何**。逐字 x、可见右边界、行末尾随空白位置。它不知道颜色、不落墨。
 * - 本类：**落墨**。把 x 换算成画布坐标、按 run 选面与墨色、逐字 `drawString`。
 *
 * 两者都在渲染层，不碰排版层（断点/页切点）、不碰用户层（样式由 ComputedStyle 决定）。
 */
internal class GlyphPainter(
    private val measurer: SkiaRunMeasurer = SkiaRunMeasurer(),
) {

    /** 一个字要画什么：面、墨色、字间距附加量（供 S4 落位复用）。 */
    data class GlyphStyle(
        val font: Font,
        val argb: Int,
        /** 行内基线位移（em，相对行基底字号；Skia 正值下移故落墨时取反）。 */
        val shiftEm: Float,
        /**
         * **合成斜体**（渲染层·视觉模拟）：级联要 `italic` 而实到那张面**不是**斜体面时为真
         * （判据 [SkParagraphFactory.synthesisFor]，与 `font.isEmboldened` 同源）。
         *
         * 落墨方式是**基线处剪切**（[drawOblique]），**不改水平 advance、不改基线**
         * ⇒ 断行与分页几何零影响，且与量宽侧（`getWidths` 裸 cmap）天然无失配。
         */
        val oblique: Boolean = false,
    )

    /**
     * 逐字画 `text[start, endExcl)`，字 x 取自 [placement]。
     *
     * @param placement [LineAligner] 的产出；`xs` 与 `range` 同坐标系。
     * @param baseY 本行基线 y（画布坐标，已含 half-leading / 平台基线差）。
     * @param alpha 祖先 opacity（P3-a：统一乘进墨色）。
     * @param bandFor 段内样式解析：给定本地区间 `[from, to)`（相对 range）返回该段样式。
     *   由 [LineWindowDrawer] 用现成的 `mergeBands` 提供 —— 切段逻辑**不重写一份**（教训 11）。
     */
    fun paintLine(
        canvas: Canvas,
        text: CharSequence,
        start: Int,
        endExcl: Int,
        placement: LineAligner.Placement,
        baseY: Float,
        originX: Float,
        fs: Float,
        alpha: Int,
        bandFor: (from: Int, to: Int) -> GlyphStyle?,
    ) {
        val rangeStart = placement.xs.indexOfFirst { it >= 0f }.let { if (it < 0) start else start + it }
        for (i in start until endExcl) {
            val local = i - rangeStart
            val x = placement.xs.getOrNull(local) ?: continue
            val st = bandFor(i, i + 1) ?: continue
            val alphaArgb = withAlpha(st.argb, alpha)
            if (alphaArgb ushr 24 == 0) continue
            // 行内基线位移：Skia 正值下移，故绘制原点上移。
            val y = baseY - st.shiftEm * fs
            val paint = Paint().apply { color = alphaArgb }
            // 逐字一个字符：Skia 无从整形，量宽（裸 cmap）与落位天然自洽（见类 KDoc）。
            drawOblique(canvas, y, st.oblique) {
                canvas.drawString(text.subSequence(i, i + 1).toString(), x, y, st.font, paint)
            }
        }
    }

    /** 段内样式解析的默认实现：整段一个面 + 一个墨色（S5 快径，run 为空时用）。 */
    fun baseGlyphStyle(
        fontSizePx: Float,
        families: List<String>,
        weight: Int,
        italic: Boolean,
        monospace: Boolean,
        argb: Int,
        cp: Int = 'H'.code,
        tag: String? = null,
    ): GlyphStyle {
        // 取面一律走 [SkiaRunMeasurer.faceForCp]（复用整栈回退面表），不另写 matchFamilyStyle。
        val font = measurer.faceForCp(cp, tag, families, weight, italic, monospace, fontSizePx.coerceAtLeast(1f))
        // 无面 ⇒ 空 Font（Skia 用默认面绘制），绝不崩（与段落侧「无族回退」同态度）。
        // 合成粗体已在 `faceForCp` 内部随 Font 落好（`isEmboldened`），这里只补**斜体**标志（画布变换）。
        val oblique = font != null && SkParagraphFactory.needsOblique(font, weight, italic)
        return GlyphStyle(
            font = font ?: Font(),
            argb = argb,
            shiftEm = 0f,
            oblique = oblique,
        )
    }

    private fun withAlpha(argb: Int, alpha: Int): Int =
        (argb and 0x00FFFFFF) or ((argb ushr 24 and 0xFF) * alpha / 255 shl 24)

    /**
     * 面缓存键（族栈 + 字重 + 斜体 + 字号 bits + **码本**），避免逐字重复取面（native 调用）。
     *
     * **码本必须进键**：逐字绘制下不同码本会落到族栈里不同的面（CJK 宋体 / Latin Times）。
     * 键里少了它 ⇒ 一个字用错面，肉眼可见且**不报错**（与教训 ⑩ 同源的分叉，只是这次在缓存层）。
     */
    fun cacheKey(families: List<String>, weight: Int, italic: Boolean, sizePx: Float, cp: Int): Any =
        listOf(families, weight, italic, sizePx.toBits(), cp)
}

/** `FontStyle` 的可读别名（避免调用方 import 两处）。 */
internal typealias PainterFontStyle = FontStyle

/**
 * **合成斜体落墨**（渲染层·视觉模拟）：在基线 [baseY] 处做水平剪切。
 *
 * ## 为什么剪切、不改字形
 *
 * Skia 的 [Font] 没有斜体属性（没有可用的斜体面时也就无从选面），浏览器对「要斜体但没斜体面」
 * 的做法就是几何剪切。这里同式：`x' = x + shear × (y − baseY)`。
 *
 * ## 为什么切在基线上、而不是原点
 *
 * 切在原点（`Canvas.skew` 直接调用）会把**整行**沿 y 方向平移 —— 上半行向右下歪、下半行向左上歪，
 * 基线不再水平，字与下一行的相对位置全错。必须 `translate(0,baseY) → skew → translate(0,−baseY)`
 * 把剪切中心钉在基线上：**基线行不动、字腰以上向右倾**，这才是斜体。
 *
 * ## 方向（`SYNTHETIC_OBLIQUE_SHEAR` 是负数的原因在那个常量的 KDoc 里）
 *
 * `x' = x + shear × (y − baseY)`，而**画布 y 向下** ⇒ 基线上方 `y − baseY < 0` ⇒ `shear` 必须为负，
 * 字顶才**右**倾。第一版取 +0.203 得到的是**反斜**（Arial 80px 字顶 x：直立 61 → 49 左移），
 * 而且当时的探针断言恰好写成「顶左移」，把 bug 一起钉住了 —— **断言必须独立于实现推导**。
 *
 * ## 几何零影响
 *
 * 剪切只改 x、**不改水平 advance、不改基线 y** ⇒ 断行（`getWidths` 裸 cmap 量宽）与分页几何
 * 完全不受影响，不存在「量画失配」，也无需为此触发重排（对比合成粗体：`isEmboldened` 同样实测
 * 不改 advance —— 两者都不动几何，这正是选它们而不是改字形的原因）。
 *
 * 视觉上字形顶端会略微右出字身框（`shear × ascent`），与浏览器合成斜体同款溢出，行框与页边界不变。
 *
 * [oblique] 为假时**直接回调、零额外开销**（绝大多数正文都不是斜体）。
 */
internal inline fun drawOblique(canvas: Canvas, baseY: Float, oblique: Boolean, block: () -> Unit) {
    if (!oblique) {
        block()
        return
    }
    canvas.save()
    canvas.translate(0f, baseY)
    canvas.skew(SkParagraphFactory.SYNTHETIC_OBLIQUE_SHEAR, 0f)
    canvas.translate(0f, -baseY)
    block()
    canvas.restore()
}

/** 取整工具（roundToInt 复用，避免各处自己写）。 */
internal fun Float.roundPx(): Int = this.roundToInt()

/** 供调用方显式声明 FontRun 已按本地区间夹紧（防御性；不夹紧会在 bandFor 里查错）。 */
internal fun FontRun.clampTo(from: Int, to: Int): FontRun? {
    val s = start.coerceIn(from, to)
    val e = endExclusive.coerceIn(from, to)
    return if (e > s) copy(start = s, endExclusive = e) else null
}