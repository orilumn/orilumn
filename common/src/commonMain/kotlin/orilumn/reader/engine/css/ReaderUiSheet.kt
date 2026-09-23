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
            "p + p,p + li,li + p,li + li{margin-top:${fmtEm(paraEm)}em}"
        val textBlocks = textBlockSelectors
        return LightCssParser().parse("$textBlocks{line-height:$lineHeight}\n$paraRule\n${fontRules(profile)}")
    }

    /**
     * 字体槽规则：非空槽才出规则（空槽跟随原书）。族名恒加引号（CJK/空格族安全），
     * 逗号/引号剔除（族名含逗号本就不合法；防注入破坏后继规则）。
     *
     * 槽位与 `fontSlotFor` 同一路由分域覆盖（正文槽=非标题非代码块，标题槽=h1..h6，代码槽=pre/code/kbd/samp）；
     * UI tier 高于一切作者声明（含 `!important`）。写 `body` 而非只靠继承：继承会被书的直接规则盖掉。
     * 标题/代码标签不在正文槽作用域内——某槽清空即该域无规则，书自己的字体直接生效（没有自身字体
     * 声明的标题/代码块仍经正文 `body` 继承兜底），避免"改正文连带标题/代码"的槽域串扰。
     */
    fun fontRules(profile: TypographicProfile): String {
        val sb = StringBuilder()
        if (profile.fontBody.isNotBlank()) sb.append("$bodyFontSelectors{font-family:${fam(profile.fontBody)}}\n")
        if (profile.fontTitle.isNotBlank()) sb.append("h1,h2,h3,h4,h5,h6{font-family:${fam(profile.fontTitle)}}\n")
        if (profile.fontCode.isNotBlank()) sb.append("pre,code,kbd,samp{font-family:${fam(profile.fontCode)}}\n")
        return sb.toString()
    }

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