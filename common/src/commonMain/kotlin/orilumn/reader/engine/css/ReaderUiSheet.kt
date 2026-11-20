package orilumn.reader.engine.css

import orilumn.reader.engine.text.TypographicProfile
import kotlin.math.roundToInt

/**
 * 阅读器 UI 层（读者全局偏好）样式表**唯一单源**（Z4，`docs/平台一致性整改方案.md`）。
 *
 * 平板 `BoxChapterLayouter.uiSheetFromProfile` 与桌面 `DesktopReaderHost.uiSheet` 原本各自内联
 * 同一套规则，现统一委托 [build]：行距 line-height 覆盖所有含文本的块级元素；首行缩进
 * 只作用于正文段落 p。纵边距（margin）UI 层一律不发声明 —— 段间距（只乘 p/li）与疏密
 * （乘其余一切块）两个百分比滑块随版式乘算，100 = 书/主题节奏，0 = 对应域清零；
 * 书（作者/UA/主题）的 margin 原样参与折叠。
 *
 * 双滑块分工（`docs/KMP迁移-功能架构.md` §6 覆盖层）：没有绝对值替换，没有基线清零。
 *
 * 原书设置 只是切回「主题层整张不加载」，UI 层照常按存储值渲染（滑块值绝对；`null` = 读者没配
 * ⇒ 不发 `text-indent`/`line-height`，全听书，与「空字体槽跟随原书」同一纪律）。
 * 两 scale 都在 StyleComputer 里统一乘算作者/UA 计算后的外边距，这里写固定基线会"替换"
 * 作者/UA 的间距，违背调节语义。
 *
 * 字体槽（用户显式选字体，空 = 该域跟随原书）：槽位按 `fontSlotFor` 同一路由分域——正文槽
 * 只覆盖非标题、非代码的文本块（h1..h6→标题槽，pre/code/kbd/samp→代码槽），标题/代码标签
 * 由各自的槽按标签覆盖；某槽清空即该域不写 font-family（书自己的字体直接生效），标题/代码
 * 一旦点选即压过书内字体。
 *
 * **正文槽刻意不碰标题（`:not(:has(h1..h6))` 守卫）**：`font-family` 是继承属性，正文槽若落在
 * `<body>`/`<div>` 这类**容器**上，书里没有自己字体声明的标题会顺走正文的字（用户报「有时改正文
 * 连带把标题改掉，得再点一次标题才分开」）。容器里只要含标题就整条规则不命中，标题于是继续
 * 继承**书的**字体（原书设置）或主题层的 `h1..h6` 声明（现代/传统），正文的 `p`/`li` 等叶子
 * 选择器照常命中——容器里的正文段落不受影响。守卫只是「别继承过去」，不是给标题发声明：
 * 读者没点标题槽时 UI 层仍然一条标题族都不写。
 */
object ReaderUiSheet {

    /** 构建 UI 层样式表。 */
    fun build(profile: TypographicProfile): StyleSheet {
        // 行距 (line-height) 覆盖所有含文本的块级元素 (含标题/引用/代码/表格/列表文字等).
        // **null = 读者没配 ⇒ 一条行高声明都不发**，书的 `line-height` 原样生效（书没写时落到
        // 引擎初值 1.5，见 `CssDefaults.DEFAULT_LINE_HEIGHT`）。0 是合法的 0 倍行高，照原样发。
        val lineHeight = profile.lineSpacing?.let { "$textBlockSelectors{line-height:$it}\n" } ?: ""
        // 首行缩进只作用于正文段落 p 的 text-indent; li 由列表自身的沟槽缩进表达, 绝不套用.
        // 纵边距无声明（段间距即疏密，版式侧乘算）：p/li 的 margin 全听书（作者/UA/主题）折叠。
        //
        // **null = 读者没设过 ⇒ 一条声明都不发**，书的 `text-indent` 原样生效（含悬挂缩进的负缩进）。
        // **0 与 N 都发声明**：0 就是「明确不缩进」——读者选 0 必须能压掉书的 2em，这是 0 的语义，
        // 不是「没配」（旧实现用 `!= 0f` 把两者挤在同一个 0，读者选 0 反而压不掉书，已废弃）。
        val indentEm = profile.firstLineIndentEm
        val paraRule = if (indentEm != null) {
            "p{text-indent:${fmtEm(indentEm)}em}\n" +
                "li p{text-indent:0em}"
        } else {
            ""
        }
        return LightCssParser().parse("$lineHeight$paraRule\n${fontRules(profile)}")
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
     * 标题/代码标签不在正文槽作用域内——某槽清空即该域无规则，书自己的字体直接生效，避免
     * "改正文连带标题/代码"的槽域串扰（容器层面的继承串扰由 [bodyFontSelectors] 的守卫切断）。
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
     * 该 (槽位, 族) 的用户字重；**按槽位归属选表**——正文/标题读本书私有的 bySlot，
     * 代码读全局表（代码槽已是全局项），legacy 表兜底（旧设置没有槽位键），
     * 非法值（不在 100..900）丢弃 ⇒ 视为"没选"，不写 `font-weight`。
     * 键的族名口径与 UI 层写锚点处一致（去空白原样）。
     *
     * 为什么不能一律 bySlot 优先：旧版本代码槽按书私有，书文件里可能留着
     * `fontCode|族` 的旧锚点；代码槽改全局后，它若压过全局表，读者在新书里选的代码字重
     * 就会被某本书的旧值顶掉（换书不沿用）。
     *
     * 衬线/无衬线两槽不走这里：它们是 CSS 通用族的替换（`serif`→读者族），没有自己的选择器表，
     * 字重由 `StyleComputer` 的通用族基线字重（`TypographicProfile.genericFontWeights`）补。
     */
    private fun weightFor(profile: TypographicProfile, slot: String, family: String): Int? {
        if (family.isBlank()) return null
        val famTrim = family.trim()
        val bySlot = profile.fontWeightAnchorsBySlot["$slot|$famTrim"]?.takeIf { it in 100..900 }
        val global = profile.fontWeightAnchorsGlobal["$slot|$famTrim"]?.takeIf { it in 100..900 }
        val scoped = if (slot == "fontCode") global ?: bySlot else bySlot ?: global
        return scoped ?: profile.fontWeightAnchors[famTrim]?.takeIf { it in 100..900 }
    }

    /** `;font-weight:W` 或空串。 */
    private fun wDecl(w: Int?): String = if (w == null) "" else ";font-weight:$w"

    /** 行距规则覆盖的全部文本块选择器表（含标题/代码/表格/列表）。 */
    private const val textBlockSelectors =
        "body,p,div,li,dd,dt,td,th,address,blockquote,pre,section,article,aside,header,footer,nav,figure,figcaption,h1,h2,h3,h4,h5,h6"

    /** 标题标签（正文槽守卫清单，单源；顺序无关）。 */
    private const val headingTags = "h1,h2,h3,h4,h5,h6"

    /** 正文槽覆盖的文本块选择器表：排除标题（h1..h6）与代码（pre/code/kbd/samp），各自交给专用槽。 */
    private const val bodyFontSelectorBase =
        "body,p,div,li,dd,dt,td,th,address,blockquote,section,article,aside,header,footer,nav,figure,figcaption," +
        ".co-summary-head,.co-summary-bullet,.co-summary-bullet-last,.fm-list-bullet,.fm-list-bullet-last,.fm-list-bullet-last1,.fm-list-bullet1,.fm-list-bullet2,.list,.list-item,.bullet"

    /**
     * 正文槽的实际选择器表 = [bodyFontSelectorBase] 每条都带 `:not(:has(h1..h6))` 守卫。
     *
     * 见文件 KDoc：只切断「容器 → 没自己字体的标题」的继承串扰，不给标题发任何声明。
     * 结构伪类由 `Selector` 支持（`:has` 的相对选择器 + `:not` 取反），带标题的容器整条不命中；
     * 叶子（`p`/`li`/…）没有子元素，守卫恒真，成本可忽略。
     */
    private val bodyFontSelectors: String =
        bodyFontSelectorBase.split(',').joinToString(",") { "$it:not(:has($headingTags))" }

    private fun fam(name: String): String =
        "\"" + name.trim().replace("\"", "").replace("'", "").replace(",", "") + "\""

    /** Formats a fractional em value with minimal trailing decimals (e.g. 0.9, 1.35), never a bare minus. */
    fun fmtEm(v: Float): String {
        val r = (v * 100f).roundToInt() / 100f
        return if (r == r.toInt().toFloat()) r.toInt().toString() else r.toString()
    }
}