package orilumn.reader.engine.skia

import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.ImageBoundsReader
import orilumn.reader.engine.css.LightCssParser
import orilumn.reader.engine.css.ReaderUiSheet
import orilumn.reader.engine.css.StyleComputer
import orilumn.reader.engine.css.themeSheetFromProfile
import orilumn.reader.engine.css.usedReplacedSize
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.laying.BoxLayouter
import orilumn.reader.engine.laying.LayoutBox
import orilumn.reader.engine.laying.NormalFlowLayout
import orilumn.reader.engine.text.TypographicProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * 《摄影的艺术》ch44 图页回归（真书链路，共享引擎，无平台代码）——**P3a 后改基**
 * （原名 `PhotoPersistFullWidthRegressionTest`）。
 *
 * 背景：章节 persist 加载路径此前不绑 `chapterHref` → [NormalFlowLayout.intrinsicSizeOf]
 * 跳过二进制探测 ⇒ 图片拿不到真实内在尺寸。旧口径下主题还会把独图拉全宽，于是回退被放大成
 * 「半高图盒」（旧平板实测盒高 795 = 1590/2）；修复 = persist 加载时绑定 `bindChapterFor`
 * → 探测命中 915×1105。
 *
 * **P3a（2026-10-11）**：主题那条 `.orilumn-fullwidth-image{width:100%}` 已退役（见 `traditional.css`
 * 编者注 / `docs/分页高度约束与断页规则-方案.md` §4.5）⇒ 图片一律按自然尺寸。本类锁的不变式不变
 * （persist 路径必须绑 `chapterHref` 让探图发生），只是数字从「1590 宽拉伸」回到 intrinsic：
 *
 *  - 传统主题 + href 已绑：915×1105 —— 正确路径
 *  - 传统主题 + href 空白：无 intrinsic ⇒ §10.3.2 的「无信息回落」300×150（原为 1590×795）
 *  - 原书设置（无主题层）：915×1105 —— 与主题模式同值（主题不再动图宽）
 *
 * 另加一条 P3a 契约断言：两个主题都**不得**再给独图注入 `width`。
 */
@RunWith(Parameterized::class)
class PhotoPersistIntrinsicRegressionTest(private val variant: String) {

    companion object {
        /**
         * S3：两个变体都要成立。
         *
         * 本类锁的是「persist 探图 + 盒高按标准尺寸」这条不变式，断行器只是同页文本的**邻居**：
         * 断点一变，图片所在段的宽度就可能变，进而动到盒高。所以它不是「与断行无关」，
         * 但也不是主题 —— 两侧都跑一遍即可，不为它单开断行专属断言。
         */
        @JvmStatic
        @Parameterized.Parameters(name = "breaker={0}")
        fun variants() = breakerVariantParams()
    }

    @Before fun applyVariant() = applyBreakerVariant(variant)

    @After fun resetVariant() = resetBreakerVariant()


    /** 真实版心宽：设备 view 1840，左右边距各 125 → 1590（与旧实测几何同）。 */
    private val contentW = 1590

    /** ch44 part0009_split_004.html 图块的真实结构：<p class="picture"><img class="calibre2"/></p>。 */
    private val html = """
        <html><body>
        <p class="normaltext1">通过把无数种光照條件浓缩为一种，这种光线条件有待提高。</p>
        <p class="picture"><img src="../images/00048.jpeg" class="calibre2" /></p>
        <p class="caption">图 5-2 光线的类型和特性</p>
        </body></html>
    """.trimIndent()

    /** 原书 stylesheet.css 中对图块生效的真实规则（未声明宽度 ⇒ 今天不再被任何主题拉伸）。 */
    private val authorCss = """
        img { border: none; margin: 0.5rem auto; display: block; }
        img + .caption, pre + .caption { margin-top: 0rem; margin-bottom: 1rem; page-break-before: avoid; }
    """.trimIndent()

    /** 00048.jpeg 真实二进制尺寸 915×1105。 */
    private val imgLoader: ImageBoundsReader = ImageBoundsReader { _, _ -> 915 to 1105 }

    private fun traditionalProfile() =
        TypographicProfile.build(ReaderSettings.DEFAULT.copy(layoutTheme = "traditional"))

    private fun originalProfile() =
        TypographicProfile.build(ReaderSettings.DEFAULT) // default layoutTheme = "original"

    /** 原始解析树（P3a 后不再有预处理打类；persist 持久化的标记即此形态）。 */
    private fun converted(): MarkupElement = HtmlTreeConverter().convert(html)!!

    private fun styleEngine(profile: TypographicProfile): StyleComputer {
        val author = listOf(LightCssParser().parse(authorCss))
        return StyleComputer(
            profile.bodyPx,
            orilumn.reader.engine.css.uaSheetFromProfile(profile),
            author,
            orilumn.reader.engine.css.themeSheetFromProfile(profile),
            null,
            ReaderUiSheet.build(profile),
            paragraphScale = profile.paragraphSpacingScale, gapScale = profile.paragraphGapScale,
        )
    }

    private fun stylesOf(root: MarkupElement, profile: TypographicProfile): Map<MarkupElement, orilumn.reader.engine.css.ComputedStyle> =
        styleEngine(profile).compute(root)

    private fun firstImg(root: MarkupElement): MarkupElement {
        var hit: MarkupElement? = null
        fun walk(el: MarkupElement) {
            if (hit != null) return
            if (el.tag == "img") { hit = el; return }
            for (c in el.children) walk(c)
        }
        walk(root)
        return hit!!
    }

    private fun imgBoxHeight(href: String): Int {
        val root = converted()
        val profile = traditionalProfile()
        val engine = styleEngine(profile)
        val styles = engine.compute(root)
        val classify = NormalFlowLayout.heavyClassify(styles, engine.hasDisplayDeclaration())
        val result = BoxLayouter(profile.bodyPx, variantBreaker())
            .layoutBoxes(root, contentW, styles, classify, imageLoader = imgLoader, chapterHref = href)
        var boxHeight = -1
        fun walkBox(b: LayoutBox) {
            if (b.el?.tag == "img" && boxHeight == -1) boxHeight = b.contentBottom - b.contentTop
            for (c in b.childBoxes) walkBox(c)
        }
        for (b in result.boxes) walkBox(b)
        return boxHeight
    }

    @Test
    fun `传统主题不再给独图注入全宽百分比(P3a 退役)`() {
        val root = converted()
        val st = stylesOf(root, traditionalProfile())[firstImg(root)]!!
        assertNull("P3a 后主题不得再给独图注入 width:100%", st.widthPct)
        assertNull("P3a 后主题不得再给独图注入 width:100%", st.widthPx)
        assertEquals(915 to 1105, st.usedReplacedSize(915, 1105, contentW))
    }

    @Test
    fun `已知href+探图成功 → 自然尺寸盒 915x1105`() {
        assertEquals(1105, imgBoxHeight(href = "text/part0009_split_004.html"))
    }

    @Test
    fun `persist空白href → 探图跳过，无信息回落 300x150`() {
        // 无 intrinsic 时按 CSS 2.1 §10.3.2 的「300px 或装得下的最大 2:1 矩形」⇒ 300×150
        // （旧口径因主题拉全宽而放大成 1590×795，P3a 后回到这条标准回落）。
        assertEquals(150, imgBoxHeight(href = ""))
    }

    @Test
    fun `原书设置无主题层 → 自然尺寸 915x1105 不拉伸`() {
        val root = converted()
        val st = stylesOf(root, originalProfile())[firstImg(root)]!!
        assertNull("原书设置下也不应有全宽百分比", st.widthPct)
        val (w, h) = st.usedReplacedSize(915, 1105, contentW)
        assertEquals(915 to 1105, w to h)
    }

    @Test
    fun `href绑定与否决定探测是否发生(修复机关)`() {
        val root = converted()
        val img = firstImg(root)
        val profile = traditionalProfile()
        val stylesA = stylesOf(root, profile)
        // href 空白 → 不探测；href 绑定 → 命中 915x1105。
        assertNull(NormalFlowLayout.intrinsicSizeOf(img, imgLoader, ""))
        assertEquals(915 to 1105, NormalFlowLayout.intrinsicSizeOf(img, imgLoader, "text/part0009_split_004.html"))
        // 级联尺寸路径同源：usedReplacedSize 直接对 same style 生效（自然高 1105）。
        val st = stylesA[img]!!
        assertEquals(1105, st.usedReplacedSize(915, 1105, contentW).second)
    }

    @Test
    fun `原书未设宽度的独图在传统主题下仍不越版心宽`() {
        // P3a 的可见结果：915 < 1590 ⇒ 既不拉伸也不左挪（居中交给书/主题的 margin auto）。
        val root = converted()
        val st = stylesOf(root, traditionalProfile())[firstImg(root)]!!
        val (w, h) = st.usedReplacedSize(915, 1105, contentW)
        assertTrue("自然宽应等于 intrinsic 且不越版心：$w", w == 915 && w <= contentW)
        assertEquals(1105, h)
    }
}
