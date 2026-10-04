package orilumn.reader.engine.skia

import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Color
import org.jetbrains.skia.Data
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontSlant
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Pixmap
import org.jetbrains.skia.paragraph.TypefaceFontProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * **视觉模拟（合成粗体 / 合成斜体）**（渲染层·字体能力）。
 *
 * ## 这锁守的是哪条用户可见的行为
 *
 * 书里声明了 `font-style: italic` / `font-weight: bold`，级联也确实让它赢了 —— 但**中文字体
 * 几乎一个斜体面都没有**（思源黑体 / 思源宋体 / Noto Sans SC / 阿里巴巴普惠体 3.0 实测均 0 个
 * Italic 面），单字重字体更是满书架都是。于是长期断裂的是「**声明赢了、字形没变**」。
 *
 * 这不是层叠问题（声明从来没丢过），是**字体能力**问题。补法与浏览器一致（CSS
 * `font-synthesis: weight style`）：设备画不出就合成。
 *
 * ## 三条必须钉死的不变式
 *
 * 1. **不双重合成**：真有粗体面/斜体面时**绝不**补（否则真斜体被再斜一次）。
 * 2. **零几何影响**：`isEmboldened` 与基线剪切都**不改水平 advance、不改基线** ⇒ 断行与分页
 *    完全不动，也无需为此触发重排。这是「选合成而不是改字形/改度量」的根本理由。
 * 3. **判据必须是「实到那张面」**，不是请求值 —— 否则会把「族里没有那个面」误判成「有」。
 *
 * ## 实测依据（不是 Skia 语义推测）
 *
 * - `Typeface.fontStyle` 读回的是**真实面**：单面族请求 `700/ITALIC` ⇒ 读回 `400/UPRIGHT`。
 * - `Font.isEmboldened` 使墨量 +18.5%（8105 → 9616 px）而 **advance 逐值不变**（263.552 前后同值）。
 * - `Canvas.skew(a, b)` = `x' = x + a·y`、`y' = y + b·x`（**第一个参数**才是 x-by-y 系数）；
 *   画布 y 向下 ⇒ 字顶右倾必须 `a < 0`（Arial 80px 字顶 x：直立 61 / +0.203→49 反斜 / −0.203→72 正斜）。
 * - `Canvas.skew` 在基线处：基线行不动、字顶右移；切在原点则整行平移 |shear|×baseY ≈ 24px。
 */
class VisualSynthesisTest {

    private val air = "/System/Library/Fonts/Supplemental"

    /** 一张位图的墨迹统计：量 + 形状。所有形状断言都从这里取数，不靠肉眼。 */
    private data class Ink(
        val total: Int,
        /** 全图有墨的最小 x / 最大 x。 */
        val xLo: Int,
        val xHi: Int,
        /** 字身顶缘（最高的有墨行）的最小 x —— 正斜合成后它必须**变大**（斜体 = 字顶右倾）。 */
        val topLo: Int,
        /** **最低那条有墨行**的左右缘。`A` 的脚正落在基线上，故这一行是「剪切锚点」的探针：
         *  切在基线上 ⇒ 几乎不动；切在原点 ⇒ 整行平移 shear×baseline ≈ 24px。 */
        val botLo: Int,
        val botHi: Int,
    )

    // ---- ① 决策纯函数：阈值取自 CSS 惯例，可直接断言 ----

    @Test
    fun `embolden only when requested is clearly bolder than the face we got`() {
        val up = FontSlant.UPRIGHT
        val f = SkParagraphFactory::synthesisFor
        // 缺面：请求 700 只拿到 400 ⇒ 补。
        assertTrue(f(400, up, 700, false).embolden)
        assertTrue(f(400, up, 600, false).embolden)
        // 正常匹配：500 落到 400 是 CSS 惯例，**不是缺面** ⇒ 不补（否则每个只装 Regular 的族都被糊粗）。
        assertFalse(f(400, up, 500, false).embolden)
        // 缺口未达 EMBOLDEN_MIN_GAP：请求 700 拿到 600，差 100 ⇒ 不补。
        assertFalse(f(600, up, 700, false).embolden)
        // 实到面比请求更粗（请求 600 落到 Black）⇒ 再补就是画蛇添足。
        assertFalse(f(1000, up, 600, false).embolden)
        // 请求本来就轻 ⇒ 永不补。
        assertFalse(f(1000, up, 400, false).embolden)
    }

    @Test
    fun `oblique only when the face we got is not actually italic`() {
        val f = SkParagraphFactory::synthesisFor
        // 要斜体、面是正体 ⇒ 补。
        assertTrue(f(400, FontSlant.UPRIGHT, 400, true).oblique)
        // 要斜体、面**本身就是**斜体面 ⇒ 绝不补（否则双重倾斜）。
        assertFalse(f(400, FontSlant.ITALIC, 400, true).oblique)
        // 不要斜体 ⇒ 永不补。
        assertFalse(f(400, FontSlant.ITALIC, 400, false).oblique)
        assertFalse(f(400, FontSlant.UPRIGHT, 700, false).oblique)
    }

    @Test
    fun `weight and style are decided independently and can both fire`() {
        // 粗 + 斜 都缺 ⇒ 两个都补（规格已拍板：粗斜都合成）。
        val both = SkParagraphFactory.synthesisFor(400, FontSlant.UPRIGHT, 700, true)
        assertTrue(both.embolden)
        assertTrue(both.oblique)
        assertFalse(both.none)
        // 族里有真斜体面 ⇒ 只补粗。
        val wOnly = SkParagraphFactory.synthesisFor(400, FontSlant.ITALIC, 700, true)
        assertTrue(wOnly.embolden)
        assertFalse(wOnly.oblique)
        assertEquals(SkParagraphFactory.FontSynthesis.NONE, SkParagraphFactory.FontSynthesis())
    }

    // ---- ② 判据落在真字体上（不是假想的字体元数据） ----

    private fun mgrOf(vararg files: String): org.jetbrains.skia.FontMgr {
        val provider = TypefaceFontProvider()
        val loader = SkParagraphFactory.defaultFontMgr()
        for (n in files) {
            val tf = loader.makeFromData(Data.makeFromBytes(File("$air/$n").readBytes()), 0)
            provider.registerTypeface(tf!!, "SynthFam")
        }
        return provider
    }

    @Test
    fun `synthFont emboldens a single-face family but not a family with a real bold`() {
        val soloTf = mgrOf("Arial.ttf")
            .matchFamilyStyle("SynthFam", SkParagraphFactory.runFontStyle(400, false))!!
        assertTrue(
            "单面族请求 700 必须合成粗体（没有可换的粗体面）",
            SkParagraphFactory.synthFont(soloTf, 64f, 700, false).isEmboldened,
        )
        assertFalse(
            "请求 400 拿到 Regular 不得合成（否则满屏假粗）",
            SkParagraphFactory.synthFont(soloTf, 64f, 400, false).isEmboldened,
        )

        val boldTf = mgrOf("Arial.ttf", "Arial Bold.ttf", "Arial Italic.ttf", "Arial Bold Italic.ttf")
            .matchFamilyStyle("SynthFam", SkParagraphFactory.runFontStyle(700, false))!!
        assertFalse(
            "族里有真 Bold 面时绝不合成",
            SkParagraphFactory.synthFont(boldTf, 64f, 700, false).isEmboldened,
        )
    }

    @Test
    fun `needsOblique is false exactly when a real italic face was matched`() {
        val upright = SkParagraphFactory.synthFont(
            mgrOf("Arial.ttf").matchFamilyStyle("SynthFam", SkParagraphFactory.runFontStyle(400, true))!!,
            64f, 400, true,
        )
        assertTrue("族里没斜体面 ⇒ 合成斜体", SkParagraphFactory.needsOblique(upright, 400, true))

        val realIt = SkParagraphFactory.synthFont(
            mgrOf("Arial.ttf", "Arial Bold.ttf", "Arial Italic.ttf", "Arial Bold Italic.ttf")
                .matchFamilyStyle("SynthFam", SkParagraphFactory.runFontStyle(700, true))!!,
            64f, 700, true,
        )
        assertFalse("族里有真 Bold Italic 面 ⇒ 不合成（不许双重倾斜）", SkParagraphFactory.needsOblique(realIt, 700, true))
        assertFalse("不要斜体 ⇒ 即使面不是斜体也不切", SkParagraphFactory.needsOblique(upright, 400, false))
    }

    // ---- ③ 不变式 2：合成零几何影响（量与画都不动） ----

    @Test
    fun `synthesised weight adds ink without changing advances`() {
        val tf = mgrOf("Arial.ttf").matchFamilyStyle("SynthFam", SkParagraphFactory.runFontStyle(400, false))!!
        val s = "Widen"
        fun adv(f: Font) = f.getWidths(s.map { it.code.toShort() }.toShortArray()).toList()
        val plain = Font(tf, 64f)
        val bold = SkParagraphFactory.synthFont(tf, 64f, 700, false)
        assertEquals(
            "合成粗体**不得**改 advance —— 改了就是量画失配，且要整书重排",
            adv(plain), adv(bold),
        )
        assertTrue("embolden 标志确实落上了", bold.isEmboldened)
    }

    @Test
    fun `synthesised oblique is anchored at the baseline not at the canvas origin`() {
        val tf = mgrOf("Arial.ttf").matchFamilyStyle("SynthFam", SkParagraphFactory.runFontStyle(400, false))!!
        val f = Font(tf, 72f)
        val baseY = 120f
        // 三趟对照：直立 / 基线处剪切（[drawOblique]，生产路径）/ **原点处剪切**（错误做法，负对照）。
        fun ink(mode: Int): Ink {
            val bmp = Bitmap()
            bmp.allocN32Pixels(300, 200, true)
            val c = Canvas(bmp)
            c.clear(Color.WHITE)
            val paint = Paint().apply { color = Color.BLACK }
            when (mode) {
                0 -> c.drawString("A", 40f, baseY, f, paint)
                1 -> drawOblique(c, baseY, true) { c.drawString("A", 40f, baseY, f, paint) }
                else -> {
                    c.skew(SkParagraphFactory.SYNTHETIC_OBLIQUE_SHEAR, 0f)
                    c.drawString("A", 40f, baseY, f, paint)
                }
            }
            return measure(bmp, baseY.toInt())
        }
        val up = ink(0)
        val atBase = ink(1)
        val atOrigin = ink(2)

        // ① 合成斜体真的生效，且方向是**正斜**（字顶右倾）。
        //    ⚠ 方向必须**独立于实现**推导：斜体 = 字顶向右。反斜（顶左倾）第一版就写错了，
        //    而当时的断言恰好写成 `topLo < up.topLo`，等于把 bug 一起钉住、测试全绿。
        assertTrue("基线处剪切必须让字顶**右**移（正斜），实测 ${atBase.topLo - up.topLo}px", atBase.topLo > up.topLo)

        // ② **切在基线上** ⇒ 字脚行（落在基线上的那条）几乎不动。
        //    （不能断言「逐像素相等」：AA 采样点在该行内被亚像素平移最多 |shear|×1px。）
        assertTrue("字脚行左缘不得位移", abs(atBase.botLo - up.botLo) <= 1)
        assertTrue("字脚行右缘不得位移", abs(atBase.botHi - up.botHi) <= 1)

        // ③ **负对照**：切在原点会把整行沿基线平移 |shear|×baseY ≈ 24px —— 正是要防的失败模式。
        //    没有这条，上面两条断言就没有牙齿（先前一版探针把「基线行」写死成 y=baseY，
        //    而 `A` 的脚恰好落在 y=baseY−1 ⇒ 那一行没墨 ⇒ 负对照测出 0，假阴性）。
        val shift = abs(atOrigin.botLo - up.botLo)
        assertTrue("负对照：原点剪切应把字脚行推移 >8px（实测 $shift）", shift > 8)
    }

    /**
     * 方向锁（**独立于实现**）：skiko `Canvas.skew(a, b)` = `x' = x + a·y`、`y' = y + b·x`，
     * 画布 y 向下 ⇒ 字顶右倾必须 `a < 0`。若有人把常量符号改回正，这条立刻红。
     *
     * 判据用「矩形在 y=100 处被推到哪里」这种与文字无关的裸几何，不受字形影响。
     */
    @Test
    fun `skew first arg is the x-by-y coefficient so a forward slant needs a negative value`() {
        fun xOf(sx: Float): Int {
            val bmp = Bitmap()
            bmp.allocN32Pixels(300, 300, true)
            val c = Canvas(bmp)
            c.clear(Color.WHITE)
            c.skew(sx, 0f)
            c.drawRect(org.jetbrains.skia.Rect.makeXYWH(100f, 100f, 4f, 4f), Paint().apply { color = Color.BLACK })
            val px = requireNotNull(bmp.peekPixels())
            for (x in 0 until bmp.width) for (y in 0 until bmp.height) {
                if (px.getColor(x, y) != Color.WHITE) return x
            }
            return -1
        }
        // 容差 1px：栅格化有进位（实测 0.25 正向恰为 125、负向 74 而非 75）。
        assertTrue("skew(0.25,0) 应把 x 推到 ≈100+0.25*100=125（实测 ${xOf(0.25f)}）", abs(xOf(0.25f) - 125) <= 1)
        assertTrue("skew(-0.25,0) 应把 x 推到 ≈100-0.25*100=75（实测 ${xOf(-0.25f)}）", abs(xOf(-0.25f) - 75) <= 1)
        assertTrue(
            "合成斜体常量必须为负，否则是反斜（第一版就是这个 bug）",
            SkParagraphFactory.SYNTHETIC_OBLIQUE_SHEAR < 0f,
        )
    }

    // ---- ④ 端到端：逐字落墨轨真的按合成结果画（缺面 ⇒ 变；有真面 ⇒ 不变） ----
    //
    // 文本用单个 `A`：无降部 ⇒ 全图左右缘与基线行都落在字身脚上，
    // 「顶缘左移」这一条断言不会被降部干扰（先前用 `Ay` 时 `y` 的尾巴把右缘拉变了，探针自身有错）。

    @Test
    fun `drawLines synthesises italic when the family has no italic face`() {
        withSoloFamily {
            val up = inkOf(400, false)
            val it = inkOf(400, true)
            assertTrue("单面族 + font-style:italic ⇒ 字顶必须**右**移（正斜合成生效）", it.topLo > up.topLo)
            // 字身脚在基线上 ⇒ 剪切不得移动全图右缘（`A` 无降部，这是精确等式而非容差）。
            assertEquals("合成斜体不得移动字脚（右缘在基线上）", up.xHi, it.xHi)
        }
    }

    @Test
    fun `drawLines synthesises bold when the family has no bold face`() {
        withSoloFamily {
            val up = inkOf(400, false)
            val bold = inkOf(700, false)
            assertTrue("单面族 + font-weight:700 ⇒ 墨量必须增加（合成粗体生效）", bold.total > up.total)
            // **advance 不变**，但合成描边会向外溢出约 stroke/2（浏览器同款），
            // 故右缘允许差 1px 的 AA 进位。advance 本身逐值相等由上面那条单测钉死。
            assertTrue(
                "合成粗体不得改 advance ⇒ 右缘至多差 1px（实测 ${bold.xHi - up.xHi}）",
                abs(bold.xHi - up.xHi) <= 1,
            )
        }
    }

    @Test
    fun `drawLines synthesises both when weight and style are both missing`() {
        withSoloFamily {
            val up = inkOf(400, false)
            val both = inkOf(700, true)
            assertTrue("粗 + 斜 同时缺 ⇒ 粗生效（墨量增）", both.total > up.total)
            assertTrue("粗 + 斜 同时缺 ⇒ 斜生效（字顶右移）", both.topLo > up.topLo)
        }
    }

    @Test
    fun `does not synthesise when the family really has the faces (through the production face lookup)`() {
        val bytes = listOf("Arial.ttf", "Arial Bold.ttf", "Arial Italic.ttf", "Arial Bold Italic.ttf")
            .map { File("$air/$it").readBytes() }
        try {
            SkiaFontPool.setEmbedded(
                bytes.map { SkiaFontPool.EmbeddedFont.forFace("FourFam", "FourFam", it) },
            )
            val m = SkiaRunMeasurer()
            val stack = listOf("FourFam")
            // 走**生产取面出口** [SkiaRunMeasurer.faceForCp]（绘制侧逐字轨的唯一取面路径），
            // 而不是直接调 synthFont —— 否则「哪条路径漏了合成」这类分叉测不出来。
            val bold = requireNotNull(m.faceForCp('A'.code, "p", stack, 700, false, false, 64f))
            assertFalse("族里有真 Bold 面 ⇒ 生产取面路径也不许合成粗体", bold.isEmboldened)
            val it = requireNotNull(m.faceForCp('A'.code, "p", stack, 700, true, false, 64f))
            assertFalse("族里有真 Bold Italic 面 ⇒ 生产取面路径也不许合成斜体", SkParagraphFactory.needsOblique(it, 700, true))
        } finally {
            SkiaFontPool.setEmbedded(emptyList())
        }
    }

    @Test
    fun `synthesises through the production face lookup when the faces are missing`() {
        withSoloFamily {
            val m = SkiaRunMeasurer()
            val stack = listOf("SoloFam")
            val bold = requireNotNull(m.faceForCp('A'.code, "p", stack, 700, false, false, 64f))
            assertTrue("单面族 + 700 ⇒ 生产取面路径必须已合成粗体", bold.isEmboldened)
            val it = requireNotNull(m.faceForCp('A'.code, "p", stack, 400, true, false, 64f))
            assertTrue("单面族 + italic ⇒ 生产取面路径必须标记合成斜体", SkParagraphFactory.needsOblique(it, 400, true))
            // 三槽隔离：同一进程里「单面族 + 正文常规」不得被上面的合成污染（不串槽）。
            val plain = requireNotNull(m.faceForCp('A'.code, "p", stack, 400, false, false, 64f))
            assertFalse("正文 400 不得被合成", plain.isEmboldened)
            assertFalse("正文不得斜切", SkParagraphFactory.needsOblique(plain, 400, false))
        }
    }

    /** 只装一张 Regular 的族（Arial.ttf 无斜体面、无粗体面），跑完复原字库。 */
    private inline fun withSoloFamily(body: () -> Unit) {
        try {
            SkiaFontPool.setEmbedded(
                listOf(SkiaFontPool.EmbeddedFont.forFace("SoloFam", "SoloFam", File("$air/Arial.ttf").readBytes())),
            )
            body()
        } finally {
            SkiaFontPool.setEmbedded(emptyList())
        }
    }

    /** 走真实落墨轨画一行并统计墨迹。 */
    private fun inkOf(weight: Int, italic: Boolean, fam: String = "SoloFam"): Ink {
        val bmp = Bitmap()
        bmp.allocN32Pixels(400, 140, true)
        val c = Canvas(bmp)
        c.clear(Color.WHITE)
        LineWindowDrawer().drawLines(
            c, 0f,
            listOf(
                DrawLine(
                    text = "A", range = 0..1, yTop = 0, yBottom = 120,
                    alignment = orilumn.reader.engine.css.TextAlign.LEFT, fontSizePx = 72f, lineHeightRatio = 1.4f,
                    tag = "p", families = listOf(fam), weight = weight, italic = italic, monospace = false,
                    letterSpacingEm = 0f, lineWidthPx = 400, hyphenAtEnd = false,
                ),
            ), null,
        )
        // 基线 = yTop + halfLeading + ascent（与 [LineAligner.baselineOffset] 同式）。
        val base = alignerBaselineFor(72f, 1.4f, fam, weight, italic)
        return measure(bmp, base)
    }

    /**
     * 该行基线的整数行号：走**生产同一口径**（`baseGlyphStyle` 取面 → `baselineOffset` 算位），
     * 不在测试里另抄一份几何公式（抄一份 = 两份公式各自漂移）。
     */
    private fun alignerBaselineFor(fs: Float, ratio: Float, fam: String, weight: Int, italic: Boolean): Int {
        val font = SkiaRunMeasurer().faceForCp('A'.code, "p", listOf(fam), weight, italic, false, fs)
        val fm = requireNotNull(font).metrics
        val lineH = (fs * ratio).roundToInt()
        val boxH = -fm.ascent + fm.descent
        return (((lineH - boxH) / 2f) + (-fm.ascent)).toInt()
    }

    private fun measure(bmp: Bitmap, baseY: Int): Ink {
        val px: Pixmap = requireNotNull(bmp.peekPixels())
        var total = 0
        var lo = 9999
        var hi = -1
        var topLo = 9999
        var topY = -1
        var botY = -1
        for (y in 0 until bmp.height) {
            var row = 0
            for (x in 0 until bmp.width) {
                if (px.getColor(x, y) != Color.WHITE) {
                    row++
                    if (x < lo) lo = x
                    if (x > hi) hi = x
                }
            }
            total += row
            if (row > 0) {
                if (topY < 0) topY = y
                botY = y
                if (y == topY) {
                    for (x in 0 until bmp.width) {
                        if (px.getColor(x, y) != Color.WHITE) { if (x < topLo) topLo = x; break }
                    }
                }
            }
        }
        // 最低那条有墨行的左右缘（`A` 的脚行）。逐行扫出，不猜基线在哪一行。
        var botLo = -1
        var botHi = -1
        if (botY >= 0) {
            for (x in 0 until bmp.width) {
                if (px.getColor(x, botY) != Color.WHITE) {
                    if (botLo < 0) botLo = x
                    botHi = x
                }
            }
        }
        return Ink(total, lo, hi, topLo, botLo, botHi)
    }

    // ---- ⑤ 边界：不许有人把合成偷偷改成改级联结果 ----

    @Test
    fun `synthesis does not touch the cascade output`() {
        // 合成是「设备画不画得出」的答案，不是对级联结论的改写：同一请求恒得同一决策（纯函数）。
        assertEquals(
            SkParagraphFactory.synthesisFor(400, FontSlant.UPRIGHT, 700, true),
            SkParagraphFactory.synthesisFor(400, FontSlant.UPRIGHT, 700, true),
        )
        // 映射本身仍是一一对应（上一轮「渲染层不许私自改字重」的锁不许被回退）。
        assertEquals(700, SkParagraphFactory.runFontStyle(700, true).weight)
        assertEquals(FontSlant.ITALIC, SkParagraphFactory.runFontStyle(400, true).slant)
    }
}