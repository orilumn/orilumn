package orilumn.reader.engine.css

import orilumn.reader.engine.html.MarkupElement

/**
 * The cascade engine: merges declarations from the UA layer, author stylesheets (plus inline
 * styles, passed per element) and the **reader upper layers** (theme → per-item settings → UI) to
 * pick the **winning** raw value for each property (pure JVM, unit-testable).
 *
 * Priority follows CSS2.1: importance first, then origin, then selector specificity, then source
 * order (later wins). `!important` reverses the origin order so UA `!important` beats everything.
 * Only the winning *raw* string per property is produced here; inheritance, unit resolution and enum
 * parsing are [StyleComputer]'s job.
 *
 * **Reader intent (决策 6):** the three upper layers sit *above* the book: theme → per-item settings →
 * UI, each with a fixed tier that beats any author/inline declaration (important included), so the
 * reader is never blocked by the book. Standard CSS origins cannot express this ordering, so our
 * engine assigns numeric tiers. A layer absent/empty simply contributes no rules. In one pass the
 * cascade yields a single winner per property — the upper layers are composed and applied over the
 * book result without any post-hoc mutation.
 *
 * @param ua the user-agent style sheet (defaults, heading scale...).
 * @param authorSheets the parsed author stylesheets for a chapter (from a [CssBundle]).
 * @param theme the modern/traditional/user theme stylesheet (may be empty/null).
 * @param settings the per-item reader settings stylesheet (element+single property; may be empty/null currently).
 * @param ui the reader-app stylesheet (line-height, paragraph spacing...); may be empty/null.
 */
class Cascade(
    ua: StyleSheet?,
    authorSheets: List<StyleSheet>,
    theme: StyleSheet? = null,
    settings: StyleSheet? = null,
    ui: StyleSheet? = null,
) {
    /** One pre-parsed, selector-bearing rule group with a fixed origin and source order. */
    private data class Matcher(
        val matchers: List<Selector>,
        val declarations: List<Declaration>,
        val tierNormal: Int,
        val tierImportant: Int,
        val order: Int,
        /** Rightmost-compound tag names across this rule's selectors; matching nodes are prefiltered by tag. */
        val rightTags: Set<String>,
        /** True when at least one selector's rightmost compound has no tag (applies to any element). */
        val anyTag: Boolean,
    )

    /** Ordinal tiers: normal / important for each origin. Higher tier wins regardless of specificity.
     *  Book authorities: author normal 20, inline 30, author important 40, inline important 41,
     *  UA important 50. Reader layers are placed strictly above inline important (41) and below UA
     *  important (50), in the order theme < settings < UI. */
    private val themeTier = 42 to 46
    private val settingsTier = 43 to 47
    private val uiTier = 44 to 48

    private val matchers: List<Matcher>

    /** 含 font-family 声明的作者层规则（[authorFontFamily] 专用，构造后惰性过滤）。 */
    private val fontFamilyMatchers: List<Matcher> by lazy {
        matchers.filter { m ->
            m.tierNormal < READER_TIER_MIN && m.declarations.any { d -> d.property == "font-family" }
        }
    }

    init {
        val list = ArrayList<Matcher>()
        var order = 0

        /** Adds every rule of [sheet] as [Matcher]s with [tierNormal]/[tierImportant]; skipped when null/empty. */
        fun addSheet(sheet: StyleSheet?, tierNormal: Int, tierImportant: Int) {
            if (sheet == null) return
            for (rule in sheet.rules) {
                val selectors = rule.selectors.mapNotNull { Selector.parse(it) }
                if (selectors.isEmpty() || rule.declarations.isEmpty()) continue
                val decls = expandLogical(rule.declarations)
                if (decls.isEmpty()) continue
                val rightTags = HashSet<String>()
                var anyTag = false
                for (sel in selectors) {
                    sel.rightmostTag?.let { rightTags.add(it) } ?: run { anyTag = true }
                }
                list.add(Matcher(selectors, decls, tierNormal, tierImportant, order++, rightTags, anyTag))
            }
        }

        addSheet(ua, 10, 50)
        for (sheet in authorSheets) addSheet(sheet, 20, 40)
        addSheet(theme, themeTier.first, themeTier.second)
        addSheet(settings, settingsTier.first, settingsTier.second)
        addSheet(ui, uiTier.first, uiTier.second)
        matchers = list
    }

    /**
     * Computes the winning declaration per property for [el] (whose [ancestors], nearest last, are
     * needed for descendant combinators), given its inline `style` [inlineDecls].
     *
     * HTML 表示型属性（presentation attrs）按 [PRESENTATION_ATTRS] 映射成对应 CSS 属性并以
     * low-tier（15，介于 UA=10 与 author=20 之间）进入级联 —— 正是浏览器把 HTML 表示属性抬高
     * 进级联层的方式（HTML4 §14 样式表）。内联样式（30）与任何作者样式表恒胜。
     *
     * P3-c [pseudo] 按生成伪元素分流：null = 元素自身（`::before/::after` 规则不再泄漏进来，
     * 旧行为里它们曾按忽略伪名匹配到元素上）；`"before"`/`"after"` = 对应伪元素
     * （只看同名伪规则；内联样式与表示属性不进伪元素，浏览器同式）。
     */
    fun winningDeclarations(el: MarkupElement, ancestors: List<MarkupElement>, inlineDecls: List<Declaration>, pseudo: String? = null): Map<String, String> {
        val winners = HashMap<String, Winner>()
        for (m in matchers) {
            // Cheap tag prefilter: skip whole rules whose rightmost tags can't include this element.
            if (!m.anyTag && el.tag !in m.rightTags) continue
            var matched: Selector? = null
            for (sel in m.matchers) {
                if (sel.pseudoElement == pseudo && sel.matches(el, ancestors)) { matched = sel; break }
            }
            if (matched == null) continue
            for (d in m.declarations) {
                val tier = if (d.important) m.tierImportant else m.tierNormal
                consider(winners, d.property, d.value, Winner(d.value, d.important, tier, matched, m.order))
            }
        }
        // HTML presentation attrs → low-tier cascade origin (15), all with the same order so the
        // first applicable mapping wins per property; CSS always wins over this layer.
        // P3-c: 表示属性与内联样式只进元素自身，伪元素只吃样式表规则（浏览器同式）。
        if (pseudo == null) {
            val htmlOrder = 0
            for (p in PRESENTATION_ATTRS) {
                htmlAttrOrigin(el, p)?.let { consider(winners, p.property, it, Winner(it, false, 15, null, htmlOrder)) }
            }
            // Inline styles act as a high-tier author origin (importance honored last).
            var inlineOrder = 10
            for (d in inlineDecls) {
                val tier = if (d.important) 41 else 30
                consider(winners, d.property, d.value, Winner(d.value, d.important, tier, null, inlineOrder))
                inlineOrder++
            }
        }
        return winners.mapValues { it.value.value }
    }

    /** Maps an HTML presentation attribute on [el] to a CSS value per [PRESENTATION_ATTRS]; returns
     *  null when the tag or attribute isn't present or the value is unusable (skipped entirely). */
    private fun htmlAttrOrigin(el: MarkupElement, p: PresentationAttr): String? {
        if (p.tags.isNotEmpty() && el.tag !in p.tags) return null
        val raw = el.attrs[p.attr]?.trim() ?: return null
        return p.convert(raw)
    }

    companion object {
        /** 读者层起始 tier（theme 42 / settings 43 / UI 44；作者侧最高 inline important 41）。 */
        const val READER_TIER_MIN = 42

        /** Tags whose HTML width/height attrs participate in the cascade as a low-tier origin. */
        private val HTML_SIZE_TAGS = setOf("img", "table", "td", "th", "col", "iframe", "embed", "object")

        /** 一个 HTML 表示型属性 → CSS 属性映射。空 [tags] 表示不限元素（是否生效由解析层的
         *  属性保留清单决定）；[convert] 返回 null 时该属性不入级联。值语义遵循 HTML4 §14.2.2：
         *  数值属性（width/height/cellpadding/cellspacing/border）即 px；bgcolor 直透；align/valign
         *  取合法关键字。 */
        private data class PresentationAttr(
            val attr: String,
            val property: String,
            val tags: Set<String> = EMPTY_TAGS,
            val convert: (String) -> String?,
        )

        private val EMPTY_TAGS = emptySet<String>()

        private fun numericPxAttr(raw: String): String? {
            // HTML4 数值属性本为纯数字/百分比，但实务常带杂质（本书 `width="100px"` 的 px 后缀、
            // `width="100px;"` 的引号内分号），浏览器取前导数字照收；百分比另行处理，此处略过。
            val t = raw.trim()
            if (t.endsWith("%")) return null
            val n = t.takeWhile { it.isDigit() || it == '.' || it == '-' || it == '+' }.toFloatOrNull()
                ?: return null
            val s = if (n == n.toInt().toFloat()) n.toInt().toString() else n.toString()
            return "${s}px"
        }

        private val PRESENTATION_ATTRS = listOf(
            PresentationAttr("width", "width", HTML_SIZE_TAGS, ::numericPxAttr),
            PresentationAttr("height", "height", HTML_SIZE_TAGS, ::numericPxAttr),
            PresentationAttr("border", "border", HTML_SIZE_TAGS, ::numericPxAttr),
            PresentationAttr("cellpadding", "padding", setOf("table"), ::numericPxAttr),
            PresentationAttr("cellspacing", "border-spacing", setOf("table"), ::numericPxAttr),
            PresentationAttr("align", "text-align") { v ->
                when (v.lowercase()) {
                    "left" -> "left"; "center" -> "center"; "right" -> "right"; "justify" -> "justify"
                    else -> null
                }
            },
            PresentationAttr("bgcolor", "background-color") { v -> v.trim().takeIf { it.isNotEmpty() } },
            // P3-b: HTML `background` 下沉为低层级 `background-image`（同路径复用 img url 解码；
            // 已是 url() 形态的原样透传，避免双重包裹）。
            PresentationAttr("background", "background-image") { v ->
                val t = v.trim().takeIf { it.isNotEmpty() } ?: return@PresentationAttr null
                if (t.startsWith("url(", ignoreCase = true)) t else "url($t)"
            },
            PresentationAttr("valign", "vertical-align", setOf("tr", "td", "th")) { v ->
                when (v.lowercase()) {
                    "top" -> "top"; "middle" -> "middle"; "bottom" -> "bottom"; "baseline" -> "baseline"
                    else -> null
                }
            },
            PresentationAttr("nowrap", "white-space", setOf("td", "th")) { "nowrap" },
            // P4-a1: `br clear`（HTML4: left/right/all/none）→ CSS `clear`（all→both，
            // 与浮动同进 P4-a2 消费；P0 只保留属性）。
            PresentationAttr("clear", "clear", setOf("br")) { v ->
                when (v.lowercase()) {
                    "left" -> "left"; "right" -> "right"; "all", "both" -> "both"; "none" -> "none"
                    else -> null
                }
            },
            // P4-a1: `img align`（HTML4 遗留围排）→ CSS `float`（left/right；center/middle
            // 无浮动等价，回 none；与通用 align→text-align 映射共存，互不干扰）。
            PresentationAttr("align", "float", setOf("img")) { v ->
                when (v.lowercase()) {
                    "left" -> "left"; "right" -> "right"
                    else -> null
                }
            },
        )

        /** Direct (single-to-single) logical → physical property alias for LTR horizontal mode. */
        private data class PhysicalAlias(val physical: String)

        /** Logical shorthand that expands into a top/bottom (block) or left/right (inline) pair. */
        private data class PhysicalTwoWay(val topOrLeft: String, val bottomOrRight: String)

        /** CSS Logical → Physical property alias map (LTR horizontal). Covers margin/padding
         *  block/inline single-edge names and 1–2-token shorthands, border (shorthand + width/color/
         *  style single-edge), and border-radius corner names. Used by [expandLogical] which runs
         *  on both author-sheet declarations and inline `style` strings. */
        private val LOGICAL_MAP: Map<String, Any> = mapOf(
            "margin-block-start" to PhysicalAlias("margin-top"),
            "margin-block-end"   to PhysicalAlias("margin-bottom"),
            "margin-inline-start" to PhysicalAlias("margin-left"),
            "margin-inline-end"  to PhysicalAlias("margin-right"),
            "margin-block"  to PhysicalTwoWay("margin-top", "margin-bottom"),
            "margin-inline" to PhysicalTwoWay("margin-left", "margin-right"),

            "padding-block-start" to PhysicalAlias("padding-top"),
            "padding-block-end"   to PhysicalAlias("padding-bottom"),
            "padding-inline-start" to PhysicalAlias("padding-left"),
            "padding-inline-end"  to PhysicalAlias("padding-right"),
            "padding-block"  to PhysicalTwoWay("padding-top", "padding-bottom"),
            "padding-inline" to PhysicalTwoWay("padding-left", "padding-right"),

            "border-block-start" to PhysicalAlias("border-top"),
            "border-block-end"   to PhysicalAlias("border-bottom"),
            "border-inline-start" to PhysicalAlias("border-left"),
            "border-inline-end"  to PhysicalAlias("border-right"),

            "border-block-start-width"  to PhysicalAlias("border-top-width"),
            "border-block-end-width"    to PhysicalAlias("border-bottom-width"),
            "border-inline-start-width" to PhysicalAlias("border-left-width"),
            "border-inline-end-width"   to PhysicalAlias("border-right-width"),

            "border-block-start-color"  to PhysicalAlias("border-top-color"),
            "border-block-end-color"    to PhysicalAlias("border-bottom-color"),
            "border-inline-start-color" to PhysicalAlias("border-left-color"),
            "border-inline-end-color"   to PhysicalAlias("border-right-color"),

            "border-block-start-style"  to PhysicalAlias("border-top-style"),
            "border-block-end-style"    to PhysicalAlias("border-bottom-style"),
            "border-inline-start-style" to PhysicalAlias("border-left-style"),
            "border-inline-end-style"   to PhysicalAlias("border-right-style"),

            "border-start-start-radius" to PhysicalAlias("border-top-left-radius"),
            "border-start-end-radius"   to PhysicalAlias("border-top-right-radius"),
            "border-end-start-radius"   to PhysicalAlias("border-bottom-left-radius"),
            "border-end-end-radius"     to PhysicalAlias("border-bottom-right-radius"),
        )
    }

    private data class Winner(
        val value: String,
        val important: Boolean,
        val tier: Int,
        val selector: Selector?,
        val order: Int,
    ) {
        /** True when this candidate beats the current winner (higher tier → higher specificity → later order). */
        fun beats(cur: Winner?): Boolean {
            if (cur == null) return true
            if (tier != cur.tier) return tier > cur.tier
            val spec = selector?.specificity
            val curSpec = cur.selector?.specificity
            if (spec != null && curSpec != null && spec != curSpec) return spec > curSpec
            return order > cur.order
        }
    }

    private inline fun consider(out: MutableMap<String, Winner>, prop: String, value: String, w: Winner) {
        val cur = out[prop]
        if (w.beats(cur)) out[prop] = w
    }

    /**
     * 作者层（sheets + 内联，tiers 见 [READER_TIER_MIN] 以下）胜出的 `font-family` 原始值；
     *
     * 给“读者层裸通用名兜底合并”用：主题预设写 `serif`/`sans-serif` 时，不能替换书栈，
     * 只能缀在后面做最终回退——否则书里点名的导入字体（池中有）在传统模式下永远够不着。
     * 只走含 font-family 声明的作者规则（构造时预过滤），元素级开销可忽略。
     */
    fun authorFontFamily(el: MarkupElement, ancestors: List<MarkupElement>, inlineDecls: List<Declaration>): String? {
        var best: Winner? = null
        for (m in fontFamilyMatchers) {
            if (!m.anyTag && el.tag !in m.rightTags) continue
            var matched: Selector? = null
            for (sel in m.matchers) {
                if (sel.matches(el, ancestors)) { matched = sel; break }
            }
            if (matched == null) continue
            for (d in m.declarations) {
                if (d.property != "font-family") continue
                val tier = if (d.important) m.tierImportant else m.tierNormal
                val cand = Winner(d.value, d.important, tier, matched, m.order)
                if (cand.beats(best)) best = cand
            }
        }
        var inlineOrder = 10
        for (d in inlineDecls) {
            if (d.property == "font-family") {
                val tier = if (d.important) 41 else 30
                val cand = Winner(d.value, d.important, tier, null, inlineOrder)
                if (cand.beats(best)) best = cand
            }
            inlineOrder++
        }
        return best?.value
    }

    /** Parses a bare inline `style` string into [Declaration]s (property/value + !important) and
     *  immediately expands any CSS logical-property aliases to physical names (LTR horizontal). */
    fun parseInline(style: String?): List<Declaration> {
        val raw = style ?: return emptyList()
        val out = ArrayList<Declaration>()
        for (part in raw.split(';')) {
            val pair = part.trim().split(':', limit = 2)
            if (pair.size < 2) continue
            val prop = pair[0].trim().lowercase()
            var value = pair[1].trim()
            var important = false
            val bang = value.lastIndexOf('!')
            if (bang >= 0) {
                val after = value.substring(bang + 1).trim()
                if (after.equals("important", ignoreCase = true)) {
                    important = true
                    value = value.substring(0, bang).trim()
                }
            }
            if (prop.isNotEmpty() && value.isNotEmpty()) out.add(Declaration(prop, value, important))
        }
        return expandLogical(out)
    }

    /** True if any sheet/layer rule declares the `display` property. Gates whether block (line)break
     *  classification consults CSS `display` on the lazy hot path, keeping that path zero-cost for
     *  books that never declare it. */
    fun hasDisplayDeclaration(): Boolean = matchers.any { m -> m.declarations.any { it.property == "display" } }

    /** ------------------------------------------------------------------------------------------
     *  CSS Logical Properties → Physical Properties expansion (LTR horizontal writing mode).
     *
     *  EPUB3 author stylesheets and sites generated by mdBook/Tailwind/Pico routinely emit logical
     *  aliases (`border-block-start`, `margin-inline`, ...). Downstream (StyleComputer, box layout,
     *  draw) only understand the historical physical names (`border-top`, `margin-left`, ...), so
     *  we translate them here at the very top of the cascade — once, in one place, before any
     *  specificity or origin-tier logic runs.
     *
     *  The mapping is correct for LTR, horizontal writing (the only mode this reader supports).
     *  Supporting RTL / vertical would require re-running expansion per element, which is future
     *  work; today we simply don't translate direction-neutral names so no harm is done.
     *
     *  Single-token logical shorthands (e.g. `margin-block`, `padding-inline`) that take 1–2 value
     *  tokens are exploded into two physical declarations so their values can be consumed
     *  individually by the existing edge parser.
     *  ------------------------------------------------------------------------------------------ */

    /** Expands every logical declaration in [in] into physical declarations (LTR). Properties
     *  already physical (or unknown) are kept verbatim; shorthands that map to two edges are
     *  split into two declarations with the same [Declaration.important] flag. */
    private fun expandLogical(src: List<Declaration>): List<Declaration> {
        if (src.isEmpty()) return src
        var touched = false
        val out = ArrayList<Declaration>(src.size + 2)
        for (d in src) {
            when (val action = LOGICAL_MAP[d.property]) {
                is PhysicalAlias -> out += Declaration(action.physical, d.value, d.important).also { touched = true }
                is PhysicalTwoWay -> {
                    val split = splitTwoValue(d.value)
                    out += Declaration(action.topOrLeft, split.first, d.important)
                    out += Declaration(action.bottomOrRight, split.second, d.important)
                    touched = true
                }
                null -> out += d
            }
        }
        return if (touched) out else src
    }

    /** Splits a single logical shorthand value (1 or 2 whitespace-separated tokens) into an
     *  (edgeA, edgeB) pair. 1 token → (v, v); 2 tokens → (v1, v2); 0 tokens → ("0", "0") safety. */
    private fun splitTwoValue(raw: String): Pair<String, String> {
        val toks = raw.trim().split(Regex("\\s+"))
        return when (toks.size) {
            0 -> "0" to "0"
            1 -> toks[0] to toks[0]
            else -> toks[0] to toks[1]  // CSS allows more tokens than we need; take first two
        }
    }
}