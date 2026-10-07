package orilumn.reader.engine

import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.css.CssBundle
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.text.TypographicProfile
import kotlin.math.max
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 问题 4 真书回归锁：《长生界》（辰东）第一章章首＋真实 stylesheet.css。
 *
 * 真书 CSS 与合成测试书（`TempVsFullTitlePageEquivalenceTest`）的关键差异——
 * 章标题→正文间距**不是纯折叠 margin**：
 *  `h2 { padding-bottom: 6rem; margin: 0.8em 0 }`、
 *  `span.sec_num { display: block; margin-bottom: 6rem }`、`p { margin: 0 }`
 *  ⇒ 间距 = h2 底 padding（6rem）＋折叠 margin（0.8em）。合成书的 h2 无
 *  padding，从未覆盖这条路径。
 *
 * 历史缺陷：轻路径（大章 TEMP 锚页 / WIN 磁盘命中增量塑形）按约定给文本叶盒
 * 继承容器样式（`LightPrepare.blockStyleFor`），而 `rebuildLocalLines` 与三处
 * 页高累加（锚页/正页/回页）把「叶自身边」又加了一次——容器底边已由
 * `NormalFlowLayout.consecutiveLeafAdvance` 的 A 侧祖先链（`pathA[1..ia-1]`
 * 含直接父容器）计入 advance，文本叶再计即重复：真书章标题→正文间距翻倍
 * （真机 630px vs 正确 352px），且页高累加虚高令 TEMP 页提前切断
 * （真机 31 行 vs FULL 35 行）。重路径（`BoxLayouter.layoutBoxes`）给文本叶盒
 * 清零盒属性，emit 叶分支 `s = box.style` 因而对文本叶加 0 边、容器边由容器
 * 分支恰好加一次——本锁即钉死两路径同几何。
 *
 * 锁：
 *  1. 章首页切片（行区间/字符区间）两路径一致；
 *  2. 逐行相对几何一致（唯一分歧 = 章首折叠顶 margin 的绝对位置，逐页渲染
 *     归一化后不可见，与合成书锁同口径）；
 *  3. h2→正文间距 == CSS 推导值（折叠 margin 与底边各自单次取整，emit 同式），
 *     两路径均等。
 */
class RealBookTitleGapEquivalenceTest {

    private val layouter = BoxChapterLayouter()
    private val converter = HtmlTreeConverter()
    private val contentW = 720
    private val contentH = 640
    private val profile = TypographicProfile.build(ReaderSettings.DEFAULT)

    /** 真书样式表（`h2` 底 padding 6rem＋margin 0.8em；`p` margin 0）。 */
    private val bookCss = """
body {
  margin: 0;
  padding: 0;
  /*    display: block;*/
}

h1 {
  display: block;
  font-family: STYuan, "Yuanti SC", san-serif;
  font-size: 2.2rem;
  letter-spacing: 0.05em;
  margin: 0;
  text-align: right;
  page-break-after: always;
}

span.vol_num {
  display: block;
  font-size: .8rem;
  line-height: 1em;
  margin-top: 200px;
  padding-bottom: 0.5rem;
  border-bottom: 1px solid;
  margin-bottom: 0.5rem;
  text-align: right;
}

h2 {
  font-family: STHeiti, san-serif;
  font-size: 2em;
  font-weight: bold;
  letter-spacing: 0.02em;
  line-height: 1.2;
  padding-bottom: 6rem;
  text-align: center;
  margin: 0.8em 0;
}

span.sec_num {
  display: block;
  font-size: 0.45em;
  margin-bottom: 6rem;
  text-align: left;
}

hr {
  height: 1px;
  border: none;
  border-top: 3px solid #000;
  margin-top: 100px;
}

p {
  display: block;
  margin: 0;
  padding: 0;
  border: 0;
  font-family: STSong, serif;
  font-size: 1em;
  text-indent: 2em;
  line-height: 1.25em;
  text-align: justify;
}
    """.trimIndent()

    /** 真书第一章章首（`第1章 章号 h2`＋32 段正文）。 */
    private val chapterHead = """
<h2><span class="sec_num">第1章 </span>武破虚空</h2>
<p>世上谁人能不死？</p>
<p>任你风华绝代，艳冠天下，到头来也是红粉骷髅；任你一代天骄，坐拥万里江山，到头来也终将化成一g黄土！</p>
<p>长生不老，是所有人都渴望的。但是没有不老的红颜，也没有不朽的帝王，红颜天骄与芸芸众生一般无二，都难以逃脱生老病死，没有人能够永生于这个世间。</p>
<p>不过，关于长生不死的传说却始终流传于世。</p>
<p>老子、庄子、达摩、陈抟、张三丰……</p>
<p>一个个名传千古的名字，像古老的魔咒不断的激励着后人，让人们相信长生不死并非绝对荒谬，有些人是可以达到那一领域的。</p>
<p>只是，时间最是无情，随着岁月的流逝，曾经不朽的传说，也渐渐磨灭在时间的长河中。</p>
<p>直至沉寂无尽岁月后，奇迹在平淡中再次爆发！</p>
<p>十五月圆之夜，一代天骄神女兰诺，将在昆仑红尘峰斩断尘缘，破碎虚空而去，天下修者莫不震惊，长生之说再成热论。</p>
<p>近几日，昆仑山涌来十几万人，上至王公贵族，下至贩夫走卒，遍及三教九流，他们都拥有同样一个目的，将要见证一场千古难得一现的神迹。</p>
<p>终于到了月圆之日，巍巍昆仑，壮阔秀丽，在皎洁的月色下，仿佛笼罩上了一层朦胧的轻纱，让这片圣山如同仙境一般飘渺。</p>
<p>月夜中，萧晨奔跑如风，满身都是血迹，就连乌黑的长发都被血水染红了。但是如刀削般的英俊面容满是不屈之色，一双如星辰般明亮的眸子，更是透射着坚毅的光芒。</p>
<p>他正在进行生死大逃亡！</p>
<p>皇家天女赵琳儿誓要诛灭他，率数十名修者四方围剿。天女面遮轻纱，身材婀娜，曲线曼妙，眸若秋水，翩若惊鸿，似浮光掠影一般轻灵，如谪仙临尘一般飘逸。</p>
<p>无路可逃，萧晨向红尘峰冲去！</p>
<p>月夜下，红尘峰附近人山人海，满山遍野皆是人影，不过十几万人聚集在一起，却是如此的安静，所有人都在静静的仰望着红尘峰上那个白衣女子。</p>
<p>绝巅之上，兰诺一身白衣胜雪，在月华的笼罩下，她的仙躯仿佛透发着淡淡圣洁的光辉，白色衣裙随风拂动，真如那不食人间烟火的广寒仙子一般。</p>
<p>这半个月以来，她两次试破虚空，不过都在功成的刹那，收回了迈出的那只脚。</p>
<p>一步之遥，她将永生天地间！</p>
<p>但是，如若跨出那一步，漫漫红尘，都将永离她而去，从此断绝一切尘缘！</p>
<p>拔慧剑斩尘缘，这需要莫大的勇气！因为走出那一步，在以后无尽的长生岁月中，等待她的也许将是无尽的孤寂。</p>
<p>天心难测，仙情如霜！</p>
<p>今日，已经从清晨站到现在，红尘种种一一浮现于她心间，终于到了挥别尘世的时候。一道道炽烈的神光，突然爆发于绝巅之上，整片山巅都笼罩上了一层无比圣洁的光辉。</p>
<p>兰诺冰肌玉骨，在圣洁的霞光中，她是如此的出尘与高洁，在万众仰视中，虚空破碎了，她纵容而坚定的向前迈步而去。</p>
<p>在最后的一刹那，她回眸向红尘望了最后一眼，那如梦似幻的仙颜，永远的留在了世人的心间，十几万人齐声呼喊兰诺的名字。</p>
<p>不过整齐的呼喊声很快散乱了，人们发现山巅之上两条快速奔跑的身影，竟然随着兰诺一同破碎虚空而去！</p>
<p>九州史记载，七三一六年，一代天骄神女兰诺武破虚空而去，皇家天女赵琳儿有幸结仙缘，随同进入长生界。</p>
<p>至于萧晨，则无缘载入史册中。</p>
<p>在破碎虚空而去的刹那，萧晨当真是震惊到了极点！</p>
<p>他从来没有想到过有这样一天，竟然会以这种方式通往长生界。在那一瞬间他想到了很多，家人、朋友……都将永别了，他将永远的离开这个尘世。</p>
<p>生死大逃亡，竟然会是这样一个结果。对于许多人来说，破碎虚空进入长生界，那是千古荣耀。但是萧晨却情愿放弃这种机会，他是如此的眷恋这个尘世，父母、亲人……永别了！他无言挥别红尘。</p>
<p>萧晨并不知道，皇家天女赵琳儿也同样破碎虚空而去。</p>
    """.trimIndent()

    private fun markup(): MarkupElement =
        converter.convert("<html><body>$chapterHead</body></html>")!!

    private fun allElements(root: MarkupElement): List<MarkupElement> {
        val out = ArrayList<MarkupElement>()
        fun walk(el: MarkupElement) {
            out.add(el)
            for (c in el.children) walk(c)
        }
        walk(root)
        return out
    }

    @Test
    fun `real book head page is identical on temp and full paths`() {
        val markup = markup()
        val css = CssBundle(listOf(bookCss))

        // FULL 路径（小章 bindFull / 磁盘表写入：prepare + 整章 emit 排版）
        val heavy = layouter.prepare(markup, css, profile, contentW, contentH)
        val full = layouter.fullLayout(heavy, profile, contentW, contentH)
        val fullLayout = full.layout
        val fullPage = full.slices[0]

        // TEMP 路径（大章锚页塑形：prepareLight + shapeTempPageForward）
        val structure = ChapterStructureCache()
        val light = layouter.prepareLight(markup, css, profile, contentW, structure, contentH)
        val fwd = layouter.shapeTempPageForward(light, profile, contentW, contentH, 0, cache = null)
            ?: error("temp head page failed")
        val tempLayout = fwd.page.layout
        val tempPage = fwd.page.slice

        // (1) 章首页切片一致（页高累加对文本叶自身边计 0，切断点与 emit 对齐）
        assertEquals("page0 firstLine", fullPage.firstLine, tempPage.firstLine)
        assertEquals("page0 lastLineExclusive", fullPage.lastLineExclusive, tempPage.lastLineExclusive)
        assertEquals("page0 charStart", fullPage.charStart, tempPage.charStart)
        assertEquals("page0 charEnd", fullPage.charEnd, tempPage.charEnd)

        // (2) 逐行相对几何一致（归一化掉章首折叠顶 margin 的绝对位置差）
        val fullBase = fullLayout.getLineTop(0)
        val tempBase = tempLayout.getLineTop(0)
        val pageLines = tempPage.lastLineExclusive - tempPage.firstLine
        for (i in 0 until pageLines) {
            assertEquals(
                "line $i relative top",
                fullLayout.getLineTop(i) - fullBase,
                tempLayout.getLineTop(i) - tempBase,
            )
            assertEquals(
                "line $i relative bottom",
                fullLayout.getLineBottom(i) - fullBase,
                tempLayout.getLineBottom(i) - tempBase,
            )
        }

        // (3) h2→正文间距 == CSS 推导值，两路径均等。
        //  emit 口径：折叠 margin 单次取整 ＋ 底边（border+padding）单次取整。
        val h2El = allElements(markup).first { it.tag == "h2" }
        val firstPEl = allElements(markup).first { it.tag == "p" }
        val h2Style = heavy.styleMap[h2El] ?: error("h2 style missing")
        val pStyle = heavy.styleMap[firstPEl] ?: error("first p style missing")
        val expectedGap = max(h2Style.margin.bottom, pStyle.margin.top).roundToInt() +
            (h2Style.border.bottom + h2Style.padding.bottom).roundToInt()

        // h2 的末行 = 标题文本叶（#text，h2 的最后一个叶）的末行；首段首行 = 首 p 叶首行。
        val titleLeaf = heavy.leaves.first { it.el?.tag == "#text" }
        val firstPLeaf = heavy.leaves.first { it.el?.tag == "p" }
        val h2LastLine = titleLeaf.lastLineExclusive - 1
        val pFirstLine = firstPLeaf.firstLineIndex
        val fullGap = fullLayout.getLineTop(pFirstLine) - fullLayout.getLineBottom(h2LastLine)
        val tempGap = tempLayout.getLineTop(pFirstLine) - tempLayout.getLineBottom(h2LastLine)
        assertEquals("FULL h2->body gap vs CSS", expectedGap, fullGap)
        assertEquals("TEMP h2->body gap vs CSS", expectedGap, tempGap)
    }
}
