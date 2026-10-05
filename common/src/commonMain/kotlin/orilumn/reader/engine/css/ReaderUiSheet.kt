package orilumn.reader.engine.css

import orilumn.reader.engine.text.TypographicProfile
import kotlin.math.roundToInt

/**
 * 阅读器 UI 层（最高书覆盖 tier）样式表**唯一单源**（Z4，`docs/平台一致性整改方案.md`）。
 *
 * 平板 `BoxChapterLayouter.uiSheetFromProfile` 与桌面 `DesktopReaderHost.uiSheet` 原本各自内联
 * 同一套规则，现统一委托 [build]：行距 line-height 覆盖所有含文本的块级元素；首行缩进
 * 只作用于正文段落 p。纵边距（margin）UI 层一律不发声明 —— 段间距（只乘 p/li）与疏密
 * （乘其余一切块）两个百分比滑块随版式乘算，100 = 书/主题节奏，0 = 对应域清零；
 * 书（作者/UA/主题）的 margin 原样参与折叠。
 *
 * 双滑块分工（`docs/KMP迁移-功能架构.md` §6 覆盖层）：没有绝对值替换，没有基线清零。
 *
 * 原书设置 只是把预览值重置为中性默认，启动 UI 层照常按存储值渲染（滑块值绝对，0 = 无首行缩进）。
 * 两 scale 都在 StyleComputer 里统一乘算作者/UA 计算后的外边距，这里写固定基线会"替换"
 * 作者/UA 的间距，违背调节语义。
 *
 * 字体槽（用户显式选字体，空 = 该域跟随原书）：槽位按 `fontSlotFor` 同一路由分域——正文槽
 * 只覆盖非标题、非代码的文本块（h1..h6→标题槽，pre/code/kbd/samp→代码槽），标题/代码标签
 * 由各自的槽按标签覆盖；某槽清空即该域不写 font-family（书自己的字体直接生效，正文槽经继承
 * 兜底没有自己字体声明的标题/代码块），标题/代码一旦点选即压过书内字体。
 */
object ReaderUiSheet {

    /** 构建 UI 层样式表。 */
    fun build(profile: TypographicProfile): StyleSheet {
        // 行距 (line-height) 覆盖所有含文本的块级元素 (含标题/引用/代码/表格/列表文字等).
        val lineHeight = profile.lineSpacing
        // 首行缩进只作用于正文段落 p 的 text-indent; li 由列表自身的沟槽缩进表达, 绝不套用.
        // 纵边距无声明（段间距即疏密，版式侧乘算）：p/li 的 margin 全听书（作者/UA/主题）折叠。
        val paraRule = "p{text-indent:${fmtEm(profile.firstLineIndentEm)}em}\n" +
            "li p{text-indent:0em}"
        val textBlocks = textBlockSelectors
        return LightCssParser().parse("$textBlocks{line-height:$lineHeight}\n$paraRule\n${fontRules(profile)}")
    }

    /**
     * 字体槽规则：非空槽才出规则（空槽跟随原书）。族名恒加引号（CJK/空格族安全），
     * 逗号/引号剔除（族名含逗号本就不合法；防注入破坏后继规则）。
     * **字重与 font-family 同规则同选择器表**（`hWeight`/`bodyWeight`/`codeWeight`）——
     * 用户字重经 UI 层（tier 44）进级联，与行距/段间距/首行缩进同一条路，**不在渲染层事后改写**
     * （`Cascade` 决策 6 明令「upper layers … without any post-hoc mutation」）。
     * 只有用户在该槽显式选过字重才写 `font-weight`；没选就不写，书自己的 `font-weight`
     * （UA `h1{bold}`、作者 `.fm-head{bold}`）原样生效。
     *
     * 槽位与 `fontSlotFor` 同一路由分域覆盖（正文槽=非标题非代码块，标题槽=h1..h6，代码槽=pre/code/kbd/samp）；
     * UI tier 高于一切作者声明（含 `!important`）。写 `body` 而非只靠继承：继承会被书的直接规则盖掉。
     * 标题/代码标签不在正文槽作用域内——某槽清空即该域无规则，书自己的字体直接生效（没有自身字体
     * 声明的标题/代码块仍经正文 `body` 继承兜底），避免"改正文连带标题/代码"的槽域串扰。
     * **选择器表刻意不含 `strong/b/em/i`**：`font-weight` 是继承属性，而 `strong` 自身有 UA 声明
     * （`ua.css:59 strong,b{font-weight:bold}`），本槽的 tier-44 声明作用在 `p` 上不影响 `strong`，
     * 加粗语义因此完整保留（反之若把 `strong` 收进 UI 选择器表就会把加粗压成正文档）。
     */
    fun fontRules(profile: TypographicProfile): String {
        val sb = StringBuilder()
        val bodyW = weightFor(profile, "fontBody", profile.fontBody)
        val titleW = weightFor(profile, "fontTitle", profile.fontTitle)
        val codeW = weightFor(profile, "fontCode", profile.fontCode)
        if (profile.fontBody.isNotBlank()) sb.append("$bodyFontSelectors{font-family:${fam(profile.fontBody)}${wDecl(bodyW)}}\n")
        if (profile.fontTitle.isNotBlank()) sb.append("h1,h2,h3,h4,h5,h6{font-family:${fam(profile.fontTitle)}${wDecl(titleW)}}\n")
        if (profile.fontCode.isNotBlank()) sb.append("pre,code,kbd,samp{font-family:${fam(profile.fontCode)}${wDecl(codeW)}}\n")
        return sb.toString()
    }

    /**
     * 该 (槽位, 族) 的用户字重；bySlot 优先、legacy 表兜底（旧设置没有槽位键），
     * 非法值（不在 100..900）丢弃 ⇒ 视为"没选"，不写 `font-weight`。
     * 键的族名口径与 UI 层写锚点处一致（去空白原样）。
     */
    private fun weightFor(profile: TypographicProfile, slot: String, family: String): Int? {
        if (family.isBlank()) return null
        val famTrim = family.trim()
        return profile.fontWeightAnchorsBySlot["$slot|$famTrim"]?.takeIf { it in 100..900 }
            ?: profile.fontWeightAnchors[famTrim]?.takeIf { it in 100..900 }
    }

    /** `;font-weight:W` 或空串。 */
    private fun wDecl(w: Int?): String = if (w == null) "" else ";font-weight:$w"

    /** 行距规则覆盖的全部文本块选择器表（含标题/代码/表格/列表）。 */
    private const val textBlockSelectors =
        "body,p,div,li,dd,dt,td,th,address,blockquote,pre,section,article,aside,header,footer,nav,figure,figcaption,h1,h2,h3,h4,h5,h6"

    /** 正文槽覆盖的文本块选择器表：排除标题（h1..h6）与代码（pre/code/kbd/samp），各自交给专用槽。 */
    private const val bodyFontSelectors =
        "body,p,div,li,dd,dt,td,th,address,blockquote,section,article,aside,header,footer,nav,figure,figcaption," +
        ".co-summary-head,.co-summary-bullet,.co-summary-bullet-last,.fm-list-bullet,.fm-list-bullet-last,.fm-list-bullet-last1,.fm-list-bullet1,.fm-list-bullet2,.list,.list-item,.bullet"

    private fun fam(name: String): String =
        "\"" + name.trim().replace("\"", "").replace("'", "").replace(",", "") + "\""

    /** Formats a fractional em value with minimal trailing decimals (e.g. 0.9, 1.35), never a bare minus. */
    fun fmtEm(v: Float): String {
        val r = (v * 100f).roundToInt() / 100f
        return if (r == r.toInt().toFloat()) r.toInt().toString() else r.toString()
    }
}