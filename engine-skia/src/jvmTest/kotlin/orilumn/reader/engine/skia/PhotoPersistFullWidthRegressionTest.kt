package orilumn.reader.engine.skia

import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.ImageBoundsReader
import orilumn.reader.engine.css.LightCssParser
import orilumn.reader.engine.css.ReaderUiSheet
import orilumn.reader.engine.css.StyleComputer
import orilumn.reader.engine.css.themeSheetFromProfile
import orilumn.reader.engine.css.usedReplacedSize
import orilumn.reader.engine.html.ChapterPreprocessor
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
 * 《摄影的艺术》ch44 图页回归（真书链路，共享引擎，无平台代码）。
 *
 * 背景：章节 persist 加载路径此前不绑 `chapterHref` → [NormalFlowLayout.intrinsicSizeOf]
 * 跳过二进制探测 → `.orilumn-fullwidth-image{width:100%}` 的主题规则把图片拉全宽时
 * 高度回退成 `宽/2`（旧平板实测盒高 795 = 1590/2）。修复 = persist 加载时绑定
 * `bindChapterFor` → 探测命中 915×1105 → 同参数下盒高应还原为 1590×1920（旧正确同为 1920）。
 *
 * 三态对照（同一 ch44 图块、同一 traditional 排版主题）：
 *  - 传统主题 + href 已绑（修复后）：1590×1920 —— 修复目标
 *  - 传统主题 + href 空白（修复前 persist）：1590×795 —— 旧 bug（宽/2 回退）
 *  - 原书设置（无主题层，当前新装默认）：915×1105 —— 设计行为，非 bug
 */
@RunWith(Parameterized::class)
class PhotoPersistFullWidthRegressionTest(private val variant: String) {

    companion object {
        /**
         * S3：两个变体都要成立。
         *
         * 本类锁的是「图片拿全宽类 + 盒高等比」这条不变式，断行器只是同页文本的**邻居**：
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

    /** 原书 stylesheet.css 中对图块生效的真实规则（无宽度约束 → img 有资格自动全宽）。 */
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

    /** 预处理后的树（persist 加载的标记即此形态）：img 应带 FULLWIDTH_CLASS。 */
    private fun preprocessed(authorCss: String = this.authorCss): MarkupElement {
        val root = HtmlTreeConverter().convert(html)!!
        return ChapterPreprocessor.preprocess(root, listOf(LightCssParser().parse(authorCss)))
    }

    private fun styleEngine(profile: TypographicProfile): StyleComputer {
        val author = listOf(LightCssParser().parse(authorCss))
        return StyleComputer(
            profile.bodyPx,
            orilumn.reader.engine.css.uaSheetFromProfile(profile),
            author,
            orilumn.reader.engine.css.themeSheetFromProfile(profile),
            null,
            ReaderUiSheet.build(profile),
            gapScale = profile.paragraphGapScale,
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
        val root = preprocessed()
        val profile = traditionalProfile()
        val engine = styleEngine(profile)
        val styles = engine.compute(root)
        val classify = NormalFlowLayout.heavyClassify(styles, engine.hasDisplayDeclaration())
        val result = BoxLayouter(profile.bodyPx, variantBreaker())
            .layoutBoxes(root, contentW, styles, classify, imageLoader = imgLoader, chapterHref = href)
        var boxHeight = -1
        fun walkBox(b: LayoutBox) {
            if (b.el?.tag == "img" && boxHeight == -1) boxHeight = (b.contentBottom - b.contentTop).toInt()
            for (c in b.childBoxes) walkBox(c)
        }
        for (b in result.boxes) walkBox(b)
        return boxHeight
    }

    @Test
    fun `预处理交给持久化的树带全宽类`() {
        assertTrue("图片未获得全宽类", firstImg(preprocessed()).attrs["class"].orEmpty().contains(ChapterPreprocessor.FULLWIDTH_CLASS))
    }

    @Test
    fun `传统主题级联给图片注入 width 100 百分比`() {
        val root = preprocessed()
        val st = stylesOf(root, traditionalProfile())[firstImg(root)]!!
        assertEquals(100f, st.widthPct ?: -1f, 0.001f)
    }

    @Test
    fun `修复后已知href+探图成功 → 全宽等比图盒 1590x1920`() {
        // 与旧正确会话实测盒高 1920 一致（1590 × 1105/915）。
        assertEquals(1920, imgBoxHeight(href = "text/part0009_split_004.html"))
    }

    @Test
    fun `修复前persist空白href → 宽减半回退 1590x795`() {
        // 与旧错误会话实测盒高 795（= 1590/2）一致。
        assertEquals(795, imgBoxHeight(href = ""))
    }

    @Test
    fun `原书设置无主题层 → 自然尺寸 915x1105 不拉伸`() {
        val root = preprocessed()
        val st = stylesOf(root, originalProfile())[firstImg(root)]!!
        assertNull("原书设置下不应注入全宽百分比", st.widthPct)
        val (w, h) = st.usedReplacedSize(915, 1105, contentW)
        assertEquals(915 to 1105, w to h)
    }

    @Test
    fun `href绑定与否决定探测是否发生(修复机关)`() {
        val root = preprocessed()
        val img = firstImg(root)
        val profile = traditionalProfile()
        val stylesA = stylesOf(root, profile)
        // href 空白 → 不探测；href 绑定 → 命中 915x1105。
        assertNull(NormalFlowLayout.intrinsicSizeOf(img, imgLoader, ""))
        assertEquals(915 to 1105, NormalFlowLayout.intrinsicSizeOf(img, imgLoader, "text/part0009_split_004.html"))
        // 级联尺寸路径同源：usedReplacedSize 直接对 same style 生效。
        val st = stylesA[img]!!
        assertEquals(1920, st.usedReplacedSize(915, 1105, contentW).second)
    }
}