package orilumn.reader.engine.css

import orilumn.reader.engine.text.TypographicProfile
import kotlin.math.roundToInt

/**
 * 阅读器 UI 层（最高书覆盖 tier）样式表**唯一单源**（Z4，`docs/平台一致性整改方案.md`）。
 *
 * 平板 `BoxChapterLayouter.uiSheetFromProfile` 与桌面 `DesktopReaderHost.uiSheet` 原本各自内联
 * 同一套规则，现统一委托 [build]：行距 line-height 覆盖所有含文本的块级元素；段间距/首行缩进
 * 只作用于正文段落 p/li。段间距必须用 per-side margin-top/bottom 声明（而非 margin 简写），
 * 否则 parseEdges 里 per-side 优先于简写 base，书设了 p margin，就会盖掉 UI 的段间距；
 * 水平边距完全交给原书（段间距只管垂直）。
 *
 * 段间距口径（用户层语义，`docs/KMP迁移-功能架构.md` §6 覆盖层）：段间距只在 **p/li 相邻对**
 * 之间生效（`p+p/p+li/li+p/li+li` 的后者取 margin-top），其它缝隙（`hn+p`、`p+ul`、
 * `ul+p`、容器首尾等）一律按原书 margin × 疏密结算，段间距不参与。实现只用层叠选择器
 * （`+` 相邻兄弟，内核 `Selector` 已支持且重/轻两路同义），**不改内核 margin 折叠语义**
 * （`NormalFlowLayout` 保持浏览器标准 max 折叠）。
 *
 * 原书设置 只是把预览值重置为中性默认，启动 UI 层照常按存储值渲染（滑块值绝对，0 = 无首行缩进）。
 * 疏密（paragraphGapScale）不在此写 margin：它在 StyleComputer 里统一乘算作者/UA 计算后的外边距，
 * 这里写固定基线会"替换"作者/UA 的间距，违背调节语义。
 *
 * 原书设置 只是把预览值重置为中性默认，启动 UI 层照常按存储值渲染（滑块值绝对，0 = 无首行缩进）。
 * 疏密（paragraphGapScale）不在此写 margin：它在 StyleComputer 里统一乘算作者/UA 计算后的外边距，
 * 这里写固定基线会"替换"作者/UA 的间距，违背调节语义。
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
        // 段间距 (paragraphSpacing): 只在 p/li 相邻对之间生效 —— 基线规则把 p/li 纵边距清零
        // （替换原书 p/li margin），相邻对规则（特异度 0,0,2 > 基线 0,0,1，同 tier 内恒胜）
        // 给后者 margin-top，缝隙即段间距；hn/ul/容器等非 p/li 邻边只剩基线 0，缝隙按原书×疏密。
        // 不受 疏密 缩放.
        val paraEm = if (profile.bodyPx > 0f) profile.paragraphSpacingPx / profile.bodyPx else 0f
        // 首行缩进只作用于正文段落 p 的 text-indent; li 由列表自身的沟槽缩进表达, 绝不套用.
        val paraRule = "p{margin-top:0em;margin-bottom:0em;text-indent:${fmtEm(profile.firstLineIndentEm)}em}\n" +
            "li{margin-top:0em;margin-bottom:0em}\n" +
            "p + p,p + li,li + p,li + li{margin-top:${fmtEm(paraEm)}em}\n" +
            nestedPIndentRule()
        val textBlocks = textBlockSelectors
        return LightCssParser().parse("$textBlocks{line-height:$lineHeight}\n$paraRule\n${fontRules(profile)}")
    }

    /**
     * **容器内 `p` 不吃首行缩进**（`li` / `td` / `th` 里直接摆的段落），以及这类容器内多个 `p`
     * 之间的固定缝隙。**三个模式一律发**，不分叉。
     *
     * ## 为何是 UI 层（tier 44）而不是主题层 asset（tier 42）
     *
     * 主题样式在这个软件里有**两重身份**：①一组高于原书的静态声明；②一个开关——用户点它时用
     * 这组预设去改写**所有 UI 控件的值**，此后用户可随时拖控件覆盖。所以「传统模式 = 2em 首行
     * 缩进」的真正载体是 UI 控件 `firstLineIndent = 2.0`，`traditional.css` 里那行
     * `p{text-indent:2em}` 只是「万一 UI 没介入时的兜底基线」。
     *
     * `firstLineIndent` 是**一个全局滑块**，没有「只对顶层 p 生效」的表达力——发出去就是给所有
     * `p`。于是裸 `p{text-indent}`（类型选择器不看祖先）连 `li p`、`td p` 一起罩住，容器里的
     * 段落也吃到 2em。**这是上面那条 UI 层规则自身选择器过宽，不是层级冲突**：收窄它属于
     * 同一条规则的修正，与它同 tier 同源，不引入任何新的层间压制。
     *
     * 反过来写进 `traditional.css` 也不成立：UI 层**始终**发着相邻对规则（tier 44），
     * `Winner.beats` 先比 tier ⇒ 42 恒被压住，那条会变成永不生效的死代码。所以两段声明同源。
     *
     * ## 为何排除 `li` / `td` / `th`
     *
     * 三者的内容都不从版心左缘起：列表有悬挂 bullet（`ua.css` 的 `ul,ol{padding-left:2em}` +
     * `li{list-style-position:outside}`，符号由 `ListMarkers` 画在沟槽外），表格单元格有列宽与
     * 单元格内边距。再叠一层首行缩进 ⇒ 内容被推得比同级正文还右，缩进反而读成"嵌套"。
     * 量过真书：`li` 有直接子 `p` 的占 24%（6093/25336），其中**≥2 个** `p`（会糊成一团）的
     * 388 个（1.5%）；`td`/`th` 有 ≥2 个直接子 `p` 的 88 个。书自己的态度一致——全库唯一一条涉及
     * `li p` 的作者声明就是 `td p, th p, li p {text-indent:0 !important}`。
     *
     * ## 段落区分度：为何是固定 0.5em 而非段间距滑块
     *
     * 传统模式 `paragraphSpacing = 0`（段落靠缩进分段，这是它的设计前提），而这里恰好把缩进拿掉了
     * ⇒ 容器内多段会彻底糊成一片（实测两个 `p` 都变成 `indent=0, mt=0`）。所以必须补缝隙。
     *
     * 用 `+`（相邻兄弟）而非 `~`（通用兄弟）：`p + div + p`、`p + ul + p` 这类**本来就有缝隙来源**
     * ——中间块自己的 margin 会乘 `gapScale`（疏密）缩放（`GAP_SCALE_EXEMPT` 只豁免 `p`/`li`，
     * `div`/`ul`/`pre` 都在缩放内）。用 `~` 会多出一份重复间距，等于把疏密的调节语义也篡改了。
     *
     * 固定 0.5em 而非跟 `paragraphSpacingPx` 滑块：传统模式该值恒 0，跟它等于没缝；给它单独
     * 一个与滑块无关的常量，才对「传统 = 靠缩进分段」这一前提成立。
     */
    private fun nestedPIndentRule(): String = "$nestedContainerPSelectors{text-indent:0}\n" +
        "li + p,td + p,th + p{margin-top:${fmtEm(NESTED_P_GAP_EM)}em}"

    /**
     * 会把内容推离版心左缘的容器 —— 其**直接子** `p` 不吃首行缩进。
     * 刻意不含 `blockquote`/`dd`：它们的内容本就从版心起，缩进是正文段落该有的样子。
     */
    private const val nestedContainerPSelectors = "li,td,th"

    /**
     * 容器内相邻两段之间的固定缝隙（em）。见 [nestedPIndentRule] 的「段落区分度」段：
     * 刻意**不跟 `paragraphSpacingPx` 滑块**——传统模式该值恒 0。
     */
    private const val NESTED_P_GAP_EM = 0.5f

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
        "body,p,div,li,dd,dt,td,th,address,blockquote,section,article,aside,header,footer,nav,figure,figcaption"

    private fun fam(name: String): String =
        "\"" + name.trim().replace("\"", "").replace("'", "").replace(",", "") + "\""

    /** Formats a fractional em value with minimal trailing decimals (e.g. 0.9, 1.35), never a bare minus. */
    fun fmtEm(v: Float): String {
        val r = (v * 100f).roundToInt() / 100f
        return if (r == r.toInt().toFloat()) r.toInt().toString() else r.toString()
    }
}