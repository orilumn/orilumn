package orilumn.reader.engine

import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.css.CssBundle
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.text.TypographicProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R26 回归：`LightPrepare` 的 float 前导表**有 float 那条分支**。
 *
 * ## 为什么必须有这个测试
 *
 * R26 给结构层加了 `anyFloat` 标志，让 `computeFloatLeads` 在"确定本章无 float"时
 * 直接短路、跳过开头那次**全章** `blockStyleFor` 扫描（Rust 书 ch7 169 叶实测 275ms，
 * 且它被第一次 `block(i)` 触发而落在开书关键路径上）。
 *
 * 危险方向是**单向**的：若某本书**有** float 而 `anyFloat` 被算成 `false`，
 * 短路会返回全 null 表，**环绕排版静默消失**——不抛异常，只是文字爬上去盖住图。
 *
 * 而当时的验证只跑过 Rust 书 ch7——那一章**恰好没有 float**，
 * 等于只测了"无 float"分支，"有 float"分支一次都没执行过。
 * 仓库里原有的 5 个 float 测试（`P6aFloatTest` 等）**全部走重路径**，
 * 轻路径的 `computeFloatLeads` 无任何测试引用。
 *
 * ## 覆盖的两条分支
 *  1. 本章**有** float → 前导表/宽度表必须非空（标志算错就会在这里露馅）
 *  2. 本章**无** float → 两表全 null（短路生效，行为与短路前一致）
 */
class LightFloatLeadTest {

    private fun prepare(html: String, css: String = ""): LightPrepare {
        val root = HtmlTreeConverter().convert(html)!!
        val profile = TypographicProfile.build(ReaderSettings.DEFAULT)
        return BoxChapterLayouter().prepareLight(
            root, CssBundle(listOf(css)), profile, 800, ChapterStructureCache(), 1000,
        )
    }

    @Test
    fun `floated image registers a width so following text wraps`() {
        val p = prepare(
            "<html><body><img src=\"a.png\" style=\"float:left\" width=\"40\" height=\"20\"/>" +
                "<p>紧跟图片的这段文字应当被挤到图片右边。</p></body></html>",
        )
        val widths = p.floatWidths
        val leads = p.floatLeads
        assertTrue("本章有 float，anyFloat 必须为真（否则短路抹掉环绕）", widths.any { it != null })
        // 图片本身是替换叶：宽度进表、前导为 null（P6-a v1 单 img 前驱是其特例）。
        assertTrue("float 图的实宽应被记录", widths.filterNotNull().isNotEmpty())
        assertTrue("宽度必须为正", widths.filterNotNull().all { it > 0 })
        // 后续文本叶若落在 float 影响范围内应拿到前导。
        val leadCount = leads.count { it != null }
        assertTrue("至少应有一个前导记录（宽度 $widths, 前导 $leads）", leadCount + widths.count { it != null } > 0)
    }

    @Test
    fun `floated text block produces a lead with positive geometry`() {
        val p = prepare(
            "<html><body><p style=\"float:left\">侧栏文字</p><p>正文应当绕开侧栏。</p></body></html>",
        )
        val lead = p.floatLeads.filterNotNull().firstOrNull()
        assertNotNull("浮动文本块必须产出 FloatLead，实际 leads=${p.floatLeads}", lead)
        assertTrue("前导宽度必须为正，实际 ${lead!!.widthPx}", lead.widthPx > 0)
        assertTrue("前导行数必须为正，实际 ${lead.lines}", lead.lines > 0)
    }

    @Test
    fun `chapter without float leaves both tables empty`() {
        val p = prepare(
            "<html><body><p>普通段落。</p><p>另一段普通文字。</p></body></html>",
        )
        assertEquals("无 float 时宽度表必须全 null", emptyList<Int?>(), p.floatWidths.filterNotNull())
        assertEquals("无 float 时前导表必须全 null", 0, p.floatLeads.count { it != null })
    }

    @Test
    fun `a float only on a later block is still detected`() {
        // 标志是全章任一叶子命中即为真——测"float 只出现在靠后位置"这个最容易漏的形状。
        val p = prepare(
            "<html><body><p>甲</p><p>乙</p><p>丙</p>" +
                "<p style=\"float:right\">丁</p><p>戊</p></body></html>",
        )
        assertTrue(
            "章末的 float 也必须被 anyFloat 捕获，实际 widths=${p.floatWidths}",
            p.floatLeads.any { it != null } || p.floatWidths.any { it != null },
        )
    }

    @Test
    fun `KNOWN GAP float on a block container is not picked up by the light path`() {
        // 这条**不是 R26 的回归**，是原本就有的缺口。实测取证：把 R26 的 anyFloat 改动
        // （07f6666 之前）取回来重跑本测试，失败完全相同。
        //
        // 原因：`enumerateBlockLeaves` 只把**叶**放进 leaves，`<div style="float:left">`
        // 自己不是叶（它有块级子节点），所以叶表里根本没有这个 float。
        // `computeFloatLeads` 的前向透传只对叶注册 float，祖先只 `preClear(clearSide)`，
        // 不注册祖先自身的 float——重路径的递归是能处理的（见 P6aFloatTest，走重路径）。
        //
        // 后果：这类内容在临时表/增量路径上环绕不生效，要等磁盘表就绪才正确。
        // 本测试**锁定现状**而非认可它：一旦有人补上容器 float，这条会失败并提醒更新。
        val p = prepare(
            "<html><body><div style=\"float:left\"><p>容器里的文字</p></div><p>正文</p></body></html>",
        )
        assertEquals("容器 float 当前不被轻路径拾取", 0, p.floatWidths.count { it != null })
        assertEquals("容器 float 当前不被轻路径拾取", 0, p.floatLeads.count { it != null })
    }

    @Test
    fun `float directly on a leaf that also has inline children is detected`() {
        // anyFloat 的口径：非 `#text` 叶取自身样式；`#text` 叶取父级。
        // 这条锁住「叶自身带 float」时标志为真——短路若在此误判为假，环绕直接消失。
        val p = prepare(
            "<html><body><p style=\"float:left\"><span>带 span 的浮动段</span></p>" +
                "<p>正文</p></body></html>",
        )
        assertTrue(
            "叶自身 float 必须被 anyFloat 捕获，实际 widths=${p.floatWidths}",
            p.floatLeads.any { it != null } || p.floatWidths.any { it != null },
        )
    }
}
