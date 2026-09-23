package orilumn.reader.engine.css

import orilumn.reader.engine.html.MarkupElement

/**
 * Computes a [ComputedStyle] for every node of a [MarkupElement] tree by resolving the cascade,
 * inheritance and unit contexts (pure JVM, unit-testable).
 *
 * Contract & ordering (important for correctness):
 *  1. font-size resolves **first** (em/% against the parent font-size, rem against root); it is
 *     inherited, so descendants fall back to the parent value.
 *  2. line-height, color, font-weight, font-style inherit from the parent's **computed** value.
 *  3. text-decoration and text-indent do **not** inherit (initial none/0), because a span must not
 *     bleed underline or indent into following inline runs.
 *  em/% for non-font-size properties and line-height resolve against the element's own font-size;
 *  rem against [rootFontPx].
 *
 * @param rootFontPx the base/UA body font size (from the reading profile) — the initial root value
 *   and the `rem` reference.
 * @param ua the UA style sheet (e.g. built from the profiling; see caller side).
 * @param authorSheets the chapter's parsed author stylesheets.
 * @param theme the modern/traditional/user theme stylesheet (reader layer, above the book; empty/null = off).
 * @param settings the per-item reader-settings stylesheet (above theme; empty/null = off).
 * @param ui the reader-app stylesheet (above settings; carries line-height / paragraph spacing).
 * @param gapScale 疏密 (paragraphGapScale): scales the computed top/bottom margin of every block
 *   except `p`/`li` — 作者 css / UA 默认值都被保留, 只被比例调节 (1.0 = 原书排版, 不做改动).
 *   `p`/`li` 的垂直间距由 UI 层的 段间距 完全接管 (替换语义), 不参与缩放.
 */
class StyleComputer(
    private val rootFontPx: Float,
    ua: StyleSheet,
    authorSheets: List<StyleSheet>,
    theme: StyleSheet? = null,
    settings: StyleSheet? = null,
    ui: StyleSheet? = null,
    private val gapScale: Float = 1f,
) {
    private val cascade = Cascade(ua, authorSheets, theme, settings, ui)

    private companion object {
        /** Matches a CSS length token (or `auto`) anywhere inside a mixed shorthand value. */
        val LENGTH_TOKEN = Regex("(-?\\d+(?:\\.\\d+)?(?:px|em|rem|%))|auto")

        /** `display` values treated as block-level for box classification (matches the roadmap). */
        val DISPLAY_BLOCK_VALUES = setOf("block", "list-item", "flex", "grid", "inline-table")

        /** p/li 的垂直间距归 段间距 (UI 层替换语义) 所有, 疏密 (gapScale) 缩放豁免. */
        val GAP_SCALE_EXEMPT = setOf("p", "li")
    }

    /** Whether a raw `display` value lays the element out as a block (block/list-item/flex/grid/table*). */
    private fun isDisplayBlock(value: String): Boolean =
        value in DISPLAY_BLOCK_VALUES || value.startsWith("table")

    /** Computes styles for the whole tree; the root node's own style starts from the root defaults. */
    fun compute(root: MarkupElement): Map<MarkupElement, ComputedStyle> {
        val out = HashMap<MarkupElement, ComputedStyle>()
        val base = ComputedStyle(fontSizePx = rootFontPx, lineHeightRatio = CssDefaults.DEFAULT_LINE_HEIGHT)
        // A single reusable ancestor stack: avoids copying the ancestor list at every child (that would
        // be O(depth) per node — measurable on multi-thousand-node chapters). Descended depth-first,
        // each node sees exactly its ancestors (nearest last) in `stack`.
        val stack = ArrayList<MarkupElement>(16)
        computeFull(root, stack, base, out)
        return out
    }

    private fun computeFull(el: MarkupElement, stack: ArrayList<MarkupElement>, parent: ComputedStyle, out: MutableMap<MarkupElement, ComputedStyle>) {
        val style = computeOne(el, stack, parent)
        out[el] = style
        for (child in el.children) {
            stack.add(el)
            computeFull(child, stack, style, out)
            stack.removeAt(stack.size - 1)
        }
    }

    /**
     * Lazily resolves [el]'s computed style by walking **only its ancestor path** (root → el, via
     * `MarkupElement.parent`) and caching each resolved node in [cache]. Costs O(depth) per distinct
     * node and never touches unrelated subtrees, so a rendering that touches only a few blocks avoids
     * cascading the whole chapter. Results are identical to [compute] (same cascade + inheritance).
     */
    fun resolve(el: MarkupElement, cache: MutableMap<MarkupElement, ComputedStyle>): ComputedStyle {
        val base = ComputedStyle(fontSizePx = rootFontPx, lineHeightRatio = CssDefaults.DEFAULT_LINE_HEIGHT)
        // Path root → el (via MarkupElement.parent), then walk it downward carrying inheritance.
        val fwd = ArrayList<MarkupElement>()
        var n: MarkupElement? = el
        while (n != null) { fwd.add(n); n = n.parent }
        fwd.reverse() // root..el
        var parentStyle = base
        val ancestors = ArrayList<MarkupElement>(fwd.size) // root..prev (the parent chain of the current node)
        for (node in fwd) {
            val cached = cache[node]
            parentStyle = cached ?: computeOne(node, ancestors, parentStyle).also { cache[node] = it }
            ancestors.add(node)
        }
        return cache[el] ?: parentStyle
    }

    /**
     * P3-c: 解析 [el] 的生成伪元素（`"before"`/`"after"`）计算样式。
     *
     * 伪元素没有内联样式与表示属性（只吃样式表规则，[ancestors] 照传供后代选择器用），
     * 未声明的属性从原发元素计算样式 [base] 继承（浏览器同式）。
     */
    fun pseudoStyle(el: MarkupElement, ancestors: List<MarkupElement>, base: ComputedStyle, pseudo: String): ComputedStyle {
        val winners = cascade.winningDeclarations(el, ancestors, emptyList(), pseudo)
        if (winners.isEmpty()) return base
        return computeStyle(winners, base, el.tag)
    }

    private fun computeOne(el: MarkupElement, ancestors: List<MarkupElement>, parent: ComputedStyle): ComputedStyle {
        val inline = cascade.parseInline(el.attrs["style"])
        val winners = cascade.winningDeclarations(el, ancestors, inline)
        val style = computeStyle(winners, parent, el.tag)
        // 读者层裸通用名兜底合并：主题预设（serif/sans-serif）只能缀在书栈后面做最终回退，
        // 不能替换——否则书里点名的导入字体（池中有）在主题模式下永远够不着（传统变黑体）。
        // 具名槽（用户显式选择）照旧全覆盖；书未声明时作者值为空，无事发生。合并后重算一次，
        // fontFamily/monospace 等派生字段与栈一致（token 无逗号，join 再解析无损）。
        val fams = style.fontFamilies
        if (fams.size == 1 && isGenericFontFamily(fams[0])) {
            val author = cascade.authorFontFamily(el, ancestors, inline)
                ?.let { parseFontFamilyList(it) }.orEmpty()
                .filter { it.isNotBlank() && !it.equals(fams[0], ignoreCase = true) }
            if (author.isNotEmpty()) {
                return computeStyle(
                    winners + ("font-family" to (author + fams[0]).joinToString(",")),
                    parent,
                    el.tag,
                )
            }
        }
        return style
    }

    private fun computeStyle(w: Map<String, String>, parent: ComputedStyle, tag: String): ComputedStyle {
        // 1) font-size first (em/% boxes resolve against this final value)
        val fontSize = resolveFontSize(w["font-size"], parent.fontSizePx)

        // Box sizes: split absolute (px/em/rem/unitless → px now) from `%` (kept for layout time,
        // resolved against the containing-block width). Keywords: `auto` → auto, `none` → no
        // constraint, `min-*` initial 0 (`auto` on min-* computes to 0).
        fun parseBoxSize(raw: String?): Pair<Float?, Float?> {
            if (raw == null) return null to null
            val t = raw.trim().lowercase()
            if (t == "auto" || t == "none" || t == "inherit" || t == "initial") return null to null
            val len = parseLength(t) ?: return null to null
            return when (len) {
                is Length.Percent -> null to len.value
                else -> len.resolve(fontSize, parent.fontSizePx, rootFontPx) to null
            }
        }

        val rawMargin = parseEdges(w, "margin", "margin-top", "margin-right", "margin-bottom", "margin-left", fontSize, parent.fontSizePx)
        // 疏密 (gapScale): 调节语义 —— 在 cascade 结果之上按比例缩放 (作者 css / UA 默认值保留, 不替换).
        // 只动垂直 (top/bottom); p/li 豁免 (其垂直间距归 段间距 的替换语义).
        val margin = if (gapScale != 1f && tag.lowercase() !in GAP_SCALE_EXEMPT) {
            rawMargin.copy(top = rawMargin.top * gapScale, bottom = rawMargin.bottom * gapScale)
        } else rawMargin

        val width = parseBoxSize(w["width"])
        val maxWidth = parseBoxSize(w["max-width"])
        val minWidth = parseBoxSize(w["min-width"])
        // P3-a: border-radius px/% 一次解析（% 以 0..1 分数保留，draw 点解）。
        val (radiusPx, radiusPct) = parseBorderRadius(w, fontSize, parent.fontSizePx)
        // P3-b: background 简写一次解析（单属性优先，缺失才回落简写层）。
        val bgShort = w["background"]?.let { parseBackgroundShorthand(it, fontSize, parent.fontSizePx) }

        return ComputedStyle(
            fontSizePx = fontSize,
            lineHeightRatio = w["line-height"]?.let { parseLineHeight(it, fontSize) } ?: parent.lineHeightRatio,
            colorHex = w["color"]?.let { parseCssColor(it) } ?: parent.colorHex,
            bold = w["font-weight"]?.let { parseFontWeightBold(it) } ?: parent.bold,
            italic = w["font-style"]?.let { parseFontStyleItalic(it) } ?: parent.italic,
            underline = w["text-decoration"]?.let { parseUnderline(it) } ?: false,
            textIndentPx = w["text-indent"]?.let { v -> parseLength(v)?.resolve(fontSize, parent.fontSizePx, rootFontPx) } ?: 0f,
            margin = margin,
            padding = parseEdges(w, "padding", "padding-top", "padding-right", "padding-bottom", "padding-left", fontSize, parent.fontSizePx),
            border = parseBorderEdges(w, fontSize, parent.fontSizePx),
            backgroundColorHex = w["background-color"]?.let { parseCssColor(it) }
                ?: w["background"]?.let { extractColorToken(it) },
            // ---- P3-b: background-image / repeat / position (non-inherited; null = none) ----
            backgroundImageUrl = w["background-image"]?.let { extractUrlOrNone(it) }
                ?: bgShort?.first,
            backgroundRepeat = w["background-repeat"]?.let { parseBackgroundRepeat(it) }
                ?: bgShort?.second
                ?: BackgroundRepeat.REPEAT,
            backgroundPosition = w["background-position"]?.let { parseBackgroundPosition(it, fontSize, parent.fontSizePx) }
                ?: bgShort?.third
                ?: BackgroundPosition(),
            borderColors = parseBorderColors(w),
            borderStyles = parseBorderStyles(w),
            borderRadius = radiusPx,
            borderRadiusPct = radiusPct,
            textAlign = w["text-align"]?.let { parseTextAlign(it) } ?: parent.textAlign,
            breakInside = parseBreakAny(w, "break-inside", "page-break-inside"),
            breakAfter = parseBreakAny(w, "break-after", "page-break-after"),
            breakBefore = parseBreakAny(w, "break-before", "page-break-before"),
            displayBlock = w["display"]?.let { isDisplayBlock(it.trim().lowercase()) } ?: false,
            displayNone = w["display"]?.trim()?.lowercase() == "none",
            fontFamily = w["font-family"]?.let { parseFontFamily(it) } ?: parent.fontFamily,
            fontFamilies = w["font-family"]?.let { parseFontFamilyList(it) } ?: parent.fontFamilies,
            fontWeight = w["font-weight"]?.let { parseFontWeight(it) } ?: parent.fontWeight,
            monospace = w["font-family"]?.let { isMonospaceFamily(it) } ?: parent.monospace,
            // List markers: non-inherited, read directly off the `ul/ol` element's own declaration;
            // the `list-style` shorthand splits into type/position when single props are absent.
            listStyleType = w["list-style-type"]?.trim()?.lowercase()
                ?: extractListStyleType(w["list-style"]),
            listStylePosition = w["list-style-position"]?.trim()?.lowercase()
                ?: extractListStylePosition(w["list-style"]),
            // ---- P1 T3: text/box computed layer (defaults = old behavior, §6 inv.3) ----
            whiteSpace = w["white-space"]?.let { parseWhiteSpace(it) } ?: parent.whiteSpace,
            letterSpacingPx = w["letter-spacing"]?.let { parseSpacing(it, fontSize, parent.fontSizePx) }
                ?: parent.letterSpacingPx,
            wordSpacingPx = w["word-spacing"]?.let { parseSpacing(it, fontSize, parent.fontSizePx) }
                ?: parent.wordSpacingPx,
            textTransform = w["text-transform"]?.let { parseTextTransform(it) } ?: parent.textTransform,
            verticalAlign = w["vertical-align"]?.let { parseVerticalAlign(it) } ?: VerticalAlign.BASELINE,
            boxSizing = w["box-sizing"]?.let { parseBoxSizing(it) } ?: BoxSizing.CONTENT_BOX,
            // opacity is NOT inherited — nesting multiplies at compositing (P3); computed stays 1.
            opacity = w["opacity"]?.let { parseOpacity(it) } ?: 1f,
            // ---- P3-a: shadows / emphasis (defaults = none = old rendering) ----
            boxShadow = w["box-shadow"]?.let { parseShadow(it, fontSize, parent.fontSizePx, allowInset = true) }
                ?.let { BoxShadow(it.dx, it.dy, it.blur, it.colorHex) },
            textShadow = (w["text-shadow"] ?: w["-epub-text-shadow"])?.let { parseShadow(it, fontSize, parent.fontSizePx, allowInset = false) }
                ?.let { TextShadow(it.dx, it.dy, it.blur, it.colorHex) } ?: parent.textShadow,
            emphasisStyle = if ((w["text-emphasis-style"] ?: w["-epub-text-emphasis-style"] ?: w["text-emphasis"] ?: w["-epub-text-emphasis"]) != null) {
                parseEmphasisStyle(w)
            } else parent.emphasisStyle,
            emphasisUnder = if ((w["text-emphasis-position"] ?: w["-epub-text-emphasis-position"]) != null) {
                parseEmphasisUnder(w)
            } else parent.emphasisUnder,
            visibilityHidden = w["visibility"]?.trim()?.lowercase()?.let { it == "hidden" || it == "collapse" }
                ?: parent.visibilityHidden,
            overflow = parseOverflow(w) ?: OverflowValue.VISIBLE,
            positionRelative = w["position"]?.trim()?.lowercase() == "relative",
            overflowWrap = w["overflow-wrap"]?.trim()?.lowercase()?.let {
                when (it) { "break-word", "anywhere" -> OverflowWrap.BREAK_WORD; else -> OverflowWrap.NORMAL }
            } ?: parent.overflowWrap,
            wordBreak = w["word-break"]?.trim()?.lowercase()?.let {
                when (it) { "break-all" -> WordBreak.BREAK_ALL; else -> WordBreak.NORMAL }
            } ?: parent.wordBreak,
            directionRtl = w["direction"]?.trim()?.lowercase()?.let { it == "rtl" } ?: parent.directionRtl,
            // ---- P2: font-variant / font-stretch (inherited; computed only) ----
            fontVariant = w["font-variant"]?.let { parseFontVariant(it) } ?: parent.fontVariant,
            fontStretch = w["font-stretch"]?.let { parseFontStretch(it) } ?: parent.fontStretch,
            // ---- P1-2: table family (non-inherited; defaults = old geometry) ----
            borderSpacingH = w["border-spacing"]?.let { parseBorderSpacing(it, fontSize).first } ?: 0f,
            borderSpacingV = w["border-spacing"]?.let { parseBorderSpacing(it, fontSize).second } ?: 0f,
            borderCollapse = w["border-collapse"]?.trim()?.lowercase()?.let { it == "collapse" } ?: false,
            captionSideBottom = w["caption-side"]?.trim()?.lowercase()?.let { it == "bottom" } ?: false,
            emptyCellsHide = w["empty-cells"]?.trim()?.lowercase()?.let { it == "hide" } ?: false,
            tableLayoutFixed = w["table-layout"]?.trim()?.lowercase()?.let { it == "fixed" } ?: false,
            // ---- P4-a1: float / clear (non-inherited; NONE = old block behavior) ----
            floatSide = w["float"]?.trim()?.lowercase()?.let {
                when (it) { "left" -> FloatSide.LEFT; "right" -> FloatSide.RIGHT; else -> FloatSide.NONE }
            } ?: FloatSide.NONE,
            clearSide = w["clear"]?.trim()?.lowercase()?.let {
                when (it) {
                    "left", "inline-start" -> ClearSide.LEFT
                    "right", "inline-end" -> ClearSide.RIGHT
                    "both" -> ClearSide.BOTH
                    else -> ClearSide.NONE
                }
            } ?: ClearSide.NONE,
            // ---- P3-c: content/quotes/counters (computed only; consumption is char-stream level) ----
            content = w["content"]?.let { parseContent(it) },
            quotes = w["quotes"]?.let { parseQuotes(it) } ?: parent.quotes,
            counterReset = w["counter-reset"]?.let { parseCounterMap(it, 0) },
            counterIncrement = w["counter-increment"]?.let { parseCounterMap(it, 1) },
            // width/height/max-*/min-*: non-inherited; px-triple null = auto/none/0.
            // `%` on width/min/max-width is containing-block-relative (kept in *Pct for layout
            // time); `%` on height/max/min-height with an auto-height containing block is
            // indefinite per CSS 2.1 §10.6/§10.7 → treated as auto/none/0 (dropped here).
            widthPx = width.first,
            widthPct = width.second,
            heightPx = parseBoxSize(w["height"]).first,
            // heightPct: indefinite (auto-height CB) → always dropped, no field.
            maxWidthPx = maxWidth.first,
            maxWidthPct = maxWidth.second,
            minWidthPx = minWidth.first,
            minWidthPct = minWidth.second,
            maxHeightPx = parseBoxSize(w["max-height"]).first,
            minHeightPx = parseBoxSize(w["min-height"]).first,
        )
    }

    /** Whether a `font-family` value resolves to a monospace face (generic `monospace` or a common
     *  mono family). Inherited via [ComputedStyle.monospace]. */
    private fun isMonospaceFamily(value: String): Boolean {
        val v = value.lowercase()
        return listOf("monospace", "courier", "consolas", "monaco", "menlo", "mono").any { v.contains(it) }
    }

    /**
     * Resolves only whether [el] (and each ancestor on its path) is laid out as a block via CSS
     * `display`, without computing any other property — the expensive cascade+inheritance on the lazy
     * hot path is confined to this single check. [cache] memoizes each node's block-ness to avoid
     * re-resolving shared ancestors. Mirrors the heavy path's `styleMap[el].displayBlock` exactly.
     */
    fun resolveDisplayOnly(el: MarkupElement, cache: MutableMap<MarkupElement, Boolean>): Boolean {
        // `display` is non-inherited, but cascade selector matching still needs the ancestor chain, so
        // walk root→el to build it (# selector specificity) while resolving only the `display` winner.
        val fwd = ArrayList<MarkupElement>()
        var n: MarkupElement? = el
        while (n != null) { fwd.add(n); n = n.parent }
        fwd.reverse()
        val ancestors = ArrayList<MarkupElement>(fwd.size)
        var result = false
        for (node in fwd) {
            result = cache.getOrPut(node) {
                val winners = cascade.winningDeclarations(node, ancestors, cascade.parseInline(node.attrs["style"]))
                winners["display"]?.let { isDisplayBlock(it.trim().lowercase()) } ?: false
            }
            ancestors.add(node)
        }
        return result
    }

    /** True when any chapter/reader sheet declares `display`; cached after the first query. The box
     *  layouter uses this to decide whether the lazy hot path must consult CSS `display` at all. */
    fun hasDisplayDeclaration(): Boolean {
        var v = _hasDisplay
        if (v == null) { v = cascade.hasDisplayDeclaration(); _hasDisplay = v }
        return v
    }

    /**
     * Resolves whether [el] (and each ancestor on its path) is `display:none` — hidden entirely. Mirrors
     * [resolveDisplayOnly]'s ancestor walk on the same cascade, so the light path and the heavy path
     * (which reads `styleMap[el].displayNone`) agree byte-for-byte on hidden-ness.
     */
    fun resolveHidden(el: MarkupElement, cache: MutableMap<MarkupElement, Boolean>): Boolean {
        val fwd = ArrayList<MarkupElement>()
        var n: MarkupElement? = el
        while (n != null) { fwd.add(n); n = n.parent }
        fwd.reverse()
        val ancestors = ArrayList<MarkupElement>(fwd.size)
        for (node in fwd) {
            if (cache.getOrPut(node) {
                    val winners = cascade.winningDeclarations(node, ancestors, cascade.parseInline(node.attrs["style"]))
                    (winners["display"]?.trim()?.lowercase() ?: "") == "none"
                }) return true
            ancestors.add(node)
        }
        return false
    }

    private var _hasDisplay: Boolean? = null

    /** Break values not declared default to AUTO; `avoid` (any variant of "avoid", incl. `avoid-page`) → AVOID. */
    private fun parseBreakRule(value: String?): BreakRule =
        if (value?.lowercase()?.contains("avoid") == true) BreakRule.AVOID else BreakRule.AUTO

    /** `break-*` with the legacy `page-break-*` alias (declared main wins, else alias). */
    private fun parseBreakAny(w: Map<String, String>, main: String, alias: String): BreakRule =
        parseBreakRule(w[main] ?: w[alias])

    /** Border width keywords (thin/medium/thick) → px per the CSS 2.1 suggested scale. */
    private fun borderWidthKeyword(token: String): Float? = when (token.trim().lowercase()) {
        "thin" -> 1f
        "medium" -> 3f
        "thick" -> 5f
        else -> null
    }

    /**
     * Resolves the four border widths. Per CSS, per-edge longhands win in the order:
     * `border-{side}-width` > `border-{side}` shorthand > `border-width` > `border` shorthand.
     * Lengths resolve against the element's own font-size; `thin/medium/thick` map to 1/3/5px.
     */
    private fun parseBorderEdges(w: Map<String, String>, fontSize: Float, parentFontPx: Float): Edges {
        fun lenOf(v: String?): Float? {
            if (v == null) return null
            val t = v.trim().lowercase()
            return borderWidthKeyword(t) ?: parseLength(t)?.resolve(fontSize, parentFontPx, rootFontPx)
        }
        // `border-width`: 1–4 slots like margin (thin/medium/thick allowed per slot).
        val widthSlots: Edges? = w["border-width"]?.let { v ->
            val toks = v.trim().split(Regex("\\s+")).map { lenOf(it) ?: 0f }
            when (toks.size) {
                1 -> Edges(toks[0], toks[0], toks[0], toks[0])
                2 -> Edges(toks[0], toks[1], toks[0], toks[1])
                3 -> Edges(toks[0], toks[1], toks[2], toks[1])
                else -> Edges(toks[0], toks[1], toks[2], toks[3])
            }
        }
        val borderUniform = w["border"]?.let { extractFirstLength(it, fontSize, parentFontPx) } ?: 0f
        fun side(sideName: String): Float =
            lenOf(w["border-$sideName-width"])
                ?: w["border-$sideName"]?.let { extractFirstLength(it, fontSize, parentFontPx) }
                ?: when (sideName) {
                    "top" -> widthSlots?.top; "right" -> widthSlots?.right
                    "bottom" -> widthSlots?.bottom; else -> widthSlots?.left
                }
                ?: borderUniform
        return Edges(side("top"), side("right"), side("bottom"), side("left"))
    }

    /** First numeric-length token of a mixed shorthand (e.g. `2px solid #ff0000` → 2); 0 when none. */
    private fun extractFirstLength(value: String, fontSize: Float, parentFontPx: Float): Float {
        val m = LENGTH_TOKEN.find(value) ?: return 0f
        if (m.value.lowercase() == "auto") return 0f
        return parseLength(m.value)?.resolve(fontSize, parentFontPx, rootFontPx) ?: 0f
    }

    /** Finds the first parseable CSS color value inside any shorthand. Unlike a naive whitespace
     *  split, this correctly groups parenthesised functions — `hsl(234, 21%, 23%)`,
     *  `rgba(0,0,0,0.5)`, `calc(...)` etc. — so CSS values with commas inside parens are not torn
     *  apart into unparseable fragments. Plain words (`red`, `transparent`) and hex (`#ccc`) still
     *  work as single tokens. */
    private fun extractColorToken(value: String): String? {
        val v = value.trim()
        var i = 0
        val n = v.length
        while (i < n) {
            // skip whitespace
            while (i < n && v[i].isWhitespace()) i++
            if (i >= n) break
            val start = i
            var depth = 0
            while (i < n) {
                val c = v[i]
                when {
                    c == '(' -> depth++
                    c == ')' -> { if (depth > 0) depth-- }
                    depth == 0 && c.isWhitespace() -> break
                }
                i++
            }
            val tok = v.substring(start, i)
            parseCssColor(tok)?.let { return it }
        }
        return null
    }

    /**
     * P3-b `background-image: url(...)` 取链（引号/裸链均可；`none`/无 url 即 null）。
     * 首个 `url()` 生效（多重背景不在范围，只取第一层；`background-size` 后的 `/` 也不解析）。
     */
    private fun extractUrlOrNone(value: String): String? {
        if (value.trim().equals("none", ignoreCase = true)) return null
        return extractUrl(value)
    }

    /** 首个 `url(...)` 内链（去引号/去空白；无即 null）。`data:` 链原样保留，绘制层解不到即跳过。 */
    private fun extractUrl(value: String): String? {
        val idx = value.indexOf("url(", ignoreCase = true)
        if (idx < 0) return null
        val end = value.indexOf(')', idx + 4)
        if (end < 0) return null
        return value.substring(idx + 4, end).trim().trim('\'').trim('"').trim().takeIf { it.isNotEmpty() }
    }

    /** 按空白切分但不撕开括号函数（`url(a b.png)` 内的空白保留；`rgb(...)` 同理）。 */
    private fun splitValueTokens(value: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        var quote = '\u0000'
        val cur = StringBuilder()
        for (c in value) {
            when {
                quote != '\u0000' -> {
                    cur.append(c)
                    if (c == quote) quote = '\u0000'
                }
                c == '\'' || c == '"' -> { quote = c; cur.append(c) }
                c == '(' -> { depth++; cur.append(c) }
                c == ')' -> { if (depth > 0) depth--; cur.append(c) }
                c.isWhitespace() && depth == 0 -> {
                    if (cur.isNotEmpty()) { out.add(cur.toString()); cur.clear() }
                }
                else -> cur.append(c)
            }
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    /**
     * P3-b `background-repeat` 解析（单关键字全覆盖；双值 `repeat no-repeat` 等常用组合映射；
     * 其余非法回初值 `repeat`）。`repeat-x/y` 只在单值时合法，双值混用即回初值。
     */
    private fun parseBackgroundRepeat(value: String): BackgroundRepeat {
        val toks = value.trim().lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (toks.size == 1) return when (toks[0]) {
            "repeat-x" -> BackgroundRepeat.REPEAT_X
            "repeat-y" -> BackgroundRepeat.REPEAT_Y
            "no-repeat" -> BackgroundRepeat.NO_REPEAT
            else -> BackgroundRepeat.REPEAT // "repeat" + 非法值
        }
        if (toks.size == 2) {
            val (h, v) = toks[0] to toks[1]
            val hRep = h == "repeat"
            val hNo = h == "no-repeat"
            val vRep = v == "repeat"
            val vNo = v == "no-repeat"
            if (hRep && vRep) return BackgroundRepeat.REPEAT
            if (hRep && vNo) return BackgroundRepeat.REPEAT_X
            if (hNo && vRep) return BackgroundRepeat.REPEAT_Y
            if (hNo && vNo) return BackgroundRepeat.NO_REPEAT
        }
        return BackgroundRepeat.REPEAT
    }

    /**
     * P3-b `background-position` 解析（关键字 left/center/right/top/bottom、`%`、长度 px/em/rem；
     * 单值另一轴按规范回 `center`；`top left` 类双关键字自动归轴；三值以上偏移语法不支持，
     * 只取前两个位置 token）。
     */
    private fun parseBackgroundPosition(value: String, fontSize: Float, parentFontPx: Float): BackgroundPosition {
        val toks = splitValueTokens(value).take(3)
        var xPct: Float? = null
        var yPct: Float? = null
        var xPx = 0f
        var yPx = 0f
        var xSet = false
        var ySet = false
        var count = 0
        for (tok in toks) {
            val t = tok.trim().lowercase()
            if (t.isEmpty() || count >= 2) continue
            when {
                t == "left" -> { xPct = 0f; xSet = true; count++ }
                t == "right" -> { xPct = 1f; xSet = true; count++ }
                t == "top" -> { yPct = 0f; ySet = true; count++ }
                t == "bottom" -> { yPct = 1f; ySet = true; count++ }
                t == "center" -> {
                    if (!xSet) { xPct = 0.5f; xSet = true } else if (!ySet) { yPct = 0.5f; ySet = true }
                    count++
                }
                else -> {
                    val len = parseLength(t) ?: continue // 颜色/attachment 等非位置 token 跳过
                    if (len is Length.Percent) {
                        val f = (len.value / 100f).coerceIn(-2f, 2f)
                        if (!xSet) { xPct = f; xSet = true } else if (!ySet) { yPct = f; ySet = true }
                    } else {
                        val px = len.resolve(fontSize, parentFontPx, rootFontPx)
                        if (!xSet) { xPx = px; xPct = 0f; xSet = true } else if (!ySet) { yPx = px; yPct = 0f; ySet = true }
                    }
                    count++
                }
            }
        }
        // 单值另一轴回 center（规范）；零值回初值 0% 0%。
        if (count == 1) {
            if (!xSet) { xPct = 0.5f }
            if (!ySet) { yPct = 0.5f }
        }
        return BackgroundPosition(xPct ?: 0f, yPct ?: 0f, xPx, yPx)
    }

    /**
     * P3-b `background` 简写扫描：首个 `url()` 为图层；repeat 关键字（1–2 个）为平铺；
     * 颜色 token 跳过（`background-color` 侧已提）；其余位置类 token（关键字/`%`/长度）
     * 前两个为定位；`scroll`/`border-box`/`cover` 等不支持项忽略。
     */
    private fun parseBackgroundShorthand(value: String, fontSize: Float, parentFontPx: Float): Triple<String?, BackgroundRepeat?, BackgroundPosition?> {
        if (value.trim().equals("none", ignoreCase = true)) return Triple(null, null, null)
        val url = extractUrl(value)
        val toks = splitValueTokens(value)
        val repeats = ArrayList<String>()
        val poss = ArrayList<String>()
        for (tok in toks) {
            val t = tok.trim().lowercase()
            if (t.isEmpty() || t.contains("url(", ignoreCase = true)) continue
            if (t in setOf("repeat", "no-repeat", "repeat-x", "repeat-y")) {
                repeats.add(t)
                continue
            }
            if (t in setOf("left", "center", "right", "top", "bottom")) {
                poss.add(tok)
                continue
            }
            if (parseCssColor(tok) != null) continue // 颜色归 background-color 侧
            if (parseLength(t) != null) poss.add(tok)
            // 其余（scroll/fixed/cover/contain/border-box/…）忽略
        }
        val rep = repeats.takeIf { it.isNotEmpty() }?.let { parseBackgroundRepeat(it.joinToString(" ")) }
        val pos = poss.takeIf { it.isNotEmpty() }?.let { parseBackgroundPosition(it.joinToString(" "), fontSize, parentFontPx) }
        return Triple(url, rep, pos)
    }

    /**
     * P3-c `content` 解析（`none`/`normal` 即 null；字符串/attr()/counter()/counters()/
     * 引号关键字逐 token；任一 token 非法即整声明丢弃，浏览器同式 fail-closed）。
     */
    private fun parseContent(value: String): List<ContentItem>? {
        val t = value.trim()
        if (t.equals("none", ignoreCase = true) || t.equals("normal", ignoreCase = true)) return null
        val out = ArrayList<ContentItem>()
        for (tok in splitValueTokens(t)) {
            val item = parseContentToken(tok) ?: return null
            out.add(item)
        }
        return out.takeIf { it.isNotEmpty() }
    }

    private fun parseContentToken(tok: String): ContentItem? {
        val t = tok.trim()
        if (t.length >= 2 && ((t.startsWith('"') && t.endsWith('"')) || (t.startsWith('\'') && t.endsWith('\'')))) {
            return ContentItem.Str(unescapeCssString(t.substring(1, t.length - 1)))
        }
        val low = t.lowercase()
        when (low) {
            "open-quote" -> return ContentItem.OpenQuote
            "close-quote" -> return ContentItem.CloseQuote
            "no-open-quote" -> return ContentItem.NoOpenQuote
            "no-close-quote" -> return ContentItem.NoCloseQuote
        }
        if (low.startsWith("attr(") && t.endsWith(")")) {
            val name = t.substring(5, t.length - 1).trim().lowercase().takeIf { it.isNotEmpty() } ?: return null
            return ContentItem.Attr(name)
        }
        if (low.startsWith("counter(") && t.endsWith(")")) {
            val args = splitFnArgs(t.substring(8, t.length - 1))
            val name = args.getOrNull(0)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            val style = args.getOrNull(1)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: "decimal"
            return ContentItem.Counter(name, style)
        }
        if (low.startsWith("counters(") && t.endsWith(")")) {
            val args = splitFnArgs(t.substring(9, t.length - 1))
            val name = args.getOrNull(0)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            val sepRaw = args.getOrNull(1)?.trim() ?: return null
            val sep = unquoteToken(sepRaw) ?: return null
            val style = args.getOrNull(2)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: "decimal"
            return ContentItem.Counters(name, sep, style)
        }
        // url()/linear-gradient() 等绘制型 content 在重排阅读器无字符口径，整声明丢弃。
        return null
    }

    /** CSS 字符串转义最小集（`\\`、引号、行继续；`\XX ` 十六进制取首字符近似）。 */
    private fun unescapeCssString(s: String): String {
        if (s.indexOf('\\') < 0) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i + 1 >= s.length) {
                sb.append(c)
                i++
                continue
            }
            val n = s[i + 1]
            when {
                n == '\n' -> i += 2 // 行继续：吞掉
                n == '\\' || n == '"' || n == '\'' -> { sb.append(n); i += 2 }
                else -> { sb.append(n); i += 2 } // \XX 十六进制等：近似取字面
            }
        }
        return sb.toString()
    }

    /** 函数参数按顶层逗号切分（引号内逗号保留，`counters(x, ", ")` 同式）。 */
    private fun splitFnArgs(inner: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        var quote = '\u0000'
        val cur = StringBuilder()
        for (c in inner) {
            when {
                quote != '\u0000' -> {
                    cur.append(c)
                    if (c == quote) quote = '\u0000'
                }
                c == '\'' || c == '"' -> { quote = c; cur.append(c) }
                c == '(' -> { depth++; cur.append(c) }
                c == ')' -> { if (depth > 0) depth--; cur.append(c) }
                c == ',' && depth == 0 -> { out.add(cur.toString()); cur.clear() }
                else -> cur.append(c)
            }
        }
        out.add(cur.toString())
        return out
    }

    /** 去一层引号（非引号 token 即 null；调用方判定合法性）。 */
    private fun unquoteToken(tok: String): String? {
        val t = tok.trim()
        if (t.length >= 2 && ((t.startsWith('"') && t.endsWith('"')) || (t.startsWith('\'') && t.endsWith('\'')))) {
            return unescapeCssString(t.substring(1, t.length - 1))
        }
        return null
    }

    /**
     * P3-c `quotes` 解析（字符串成对；奇数尾项丢弃 fail-open；空即 null）。
     */
    private fun parseQuotes(value: String): List<String>? {
        val out = ArrayList<String>()
        for (tok in splitValueTokens(value)) {
            out.add(unquoteToken(tok) ?: return null)
        }
        if (out.isEmpty()) return null
        if (out.size % 2 == 1) out.removeAt(out.lastIndex)
        return out.takeIf { it.isNotEmpty() }
    }

    /**
     * P3-c `counter-reset`/`counter-increment` 解析（`none` 即 null；`name [int]` 连写，
     * 缺省整数 reset=0/increment=1；孤立整数跳过）。
     */
    private fun parseCounterMap(value: String, defaultInt: Int): Map<String, Int>? {
        val t = value.trim()
        if (t.equals("none", ignoreCase = true)) return null
        val out = LinkedHashMap<String, Int>()
        var pending: String? = null
        fun flush() {
            pending?.let { out[it] = defaultInt }
            pending = null
        }
        for (tok in splitValueTokens(t)) {
            val w = tok.trim().lowercase()
            if (w.isEmpty()) continue
            val num = w.toIntOrNull()
            if (num != null) {
                if (pending != null) {
                    out[pending!!] = num
                    pending = null
                }
                // 孤立整数：跳过
            } else {
                flush()
                pending = w
            }
        }
        flush()
        return out.takeIf { it.isNotEmpty() }
    }
    /**
     * Per-edge border colors: `border-{side}-color` > `border-{side}` shorthand color >
     * `border-color` slots (1–4) > `border` shorthand color. Null object = no color declared
     * anywhere (each edge then falls back to currentColor/text color at draw). Null edges inside
     * a non-null object also fall back to currentColor. A lone per-edge declaration (e.g. only
     * `border-left: 1px solid rgba(...)`) covers the common `blockquote` left-rule pattern.
     */
    private fun parseBorderColors(w: Map<String, String>): BorderColorEdges? {
        // `border-color` shorthand → 1–4 slots (top, right, bottom, left, margin-style).
        val globalSlots: List<String?>? = w["border-color"]?.let { v ->
            val raw = v.split(Regex("\\s+")).map { it.ifBlank { null } }
            when (raw.size) {
                1 -> listOf(raw[0], raw[0], raw[0], raw[0])
                2 -> listOf(raw[0], raw[1], raw[0], raw[1])
                3 -> listOf(raw[0], raw[1], raw[2], raw[1])
                else -> listOf(raw[0], raw[1], raw[2], raw[3])
            }
        }
        val globalColor = w["border"]?.let { extractColorToken(it) }
        fun colorOf(raw: String?): String? = raw?.let { parseCssColor(it.trim()) }
        val sides = listOf("top", "right", "bottom", "left")
        val out = sides.mapIndexed { i, side ->
            colorOf(w["border-$side-color"])
                ?: w["border-$side"]?.let { extractColorToken(it) }
                ?: colorOf(globalSlots?.get(i))
                ?: globalColor
        }
        if (out.all { it == null } && globalSlots == null) return null
        return BorderColorEdges(out[0], out[1], out[2], out[3])
    }

    /** Border style keyword words (unsupported 3D ones degrade to solid at draw; CSS-wide ignored). */
    private val BORDER_STYLE_WORDS = setOf("none", "hidden", "solid", "dashed", "dotted")

    /** First border-style keyword inside a mixed shorthand (`2px solid red` → "solid"). */
    private fun borderStyleWordIn(value: String): String? {
        for (tok in value.split(Regex("\\s+"))) {
            val t = tok.trim().lowercase()
            if (t in BORDER_STYLE_WORDS) return t
        }
        return null
    }

    private fun borderStyleOf(word: String): BorderStyle = when (word) {
        "dashed" -> BorderStyle.DASHED
        "dotted" -> BorderStyle.DOTTED
        else -> BorderStyle.NONE // "none" | "hidden" (hidden draws like none outside collapse)
    }

    /**
     * Per-edge `border-style`: `border-{side}-style` > `border-{side}` shorthand keyword >
     * `border-style` slots (1–4) > `border` shorthand keyword. Null object = nothing declared —
     * the drawer then falls back to solid (old behavior). Explicit `none`/`hidden` edges never draw.
     */
    private fun parseBorderStyles(w: Map<String, String>): BorderStyleEdges? {
        val slotWords: List<String?>? = w["border-style"]?.let { v ->
            val kws = v.split(Regex("\\s+")).map { it.trim().lowercase() }
                .filter { it in BORDER_STYLE_WORDS }
            if (kws.isEmpty()) return@let null
            when (kws.size) {
                1 -> listOf(kws[0], kws[0], kws[0], kws[0])
                2 -> listOf(kws[0], kws[1], kws[0], kws[1])
                3 -> listOf(kws[0], kws[1], kws[2], kws[1])
                else -> listOf(kws[0], kws[1], kws[2], kws[3])
            }
        }
        val borderWord = w["border"]?.let { borderStyleWordIn(it) }
        var declared = false
        val sides = listOf("top", "right", "bottom", "left")
        val out = sides.mapIndexed { i, side ->
            val word = w["border-$side-style"]?.trim()?.lowercase()?.takeIf { it in BORDER_STYLE_WORDS }
                ?: w["border-$side"]?.let { borderStyleWordIn(it) }
                ?: slotWords?.get(i)
                ?: borderWord
            if (word == null) return@mapIndexed BorderStyle.NONE
            declared = true
            if (word == "solid") BorderStyle.SOLID else borderStyleOf(word)
        }
        if (!declared) return null
        return BorderStyleEdges(out[0], out[1], out[2], out[3])
    }

    /**
     * P3-a `box-shadow`/`text-shadow` 解析：`[inset?] dx dy [blur] [spread?] [color]`。
     * `inset`/spread 不支持（内阴影无简单绘制口径）→ 整个声明丢弃；
     * 颜色只认 `#hex`（其余回 currentColor＝null，由绘制点按文本色解）。
     */
    private fun parseShadow(value: String, fontSize: Float, parentFontPx: Float, allowInset: Boolean): BoxShadowParts? {
        val toks = value.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (toks.isEmpty()) return null
        if (toks.any { it.equals("inset", ignoreCase = true) }) return null
        if (toks.firstOrNull()?.equals("none", ignoreCase = true) == true) return null
        val lens = ArrayList<Float>()
        var color: String? = null
        for (t in toks) {
            if (t.startsWith("#") && color == null) {
                color = t
                continue
            }
            val len = parseLength(t.lowercase()) ?: return null // 未知 token 即整声明丢弃
            if (len is Length.Percent) return null
            lens.add(len.resolve(fontSize, parentFontPx, rootFontPx))
        }
        if (lens.size < 2) return null
        if (lens.size > 4) return null // 含 spread（第 4 长度）→ 不支持
        return BoxShadowParts(lens[0], lens[1], lens.getOrElse(2) { 0f }.coerceAtLeast(0f), color)
    }

    private class BoxShadowParts(val dx: Float, val dy: Float, val blur: Float, val colorHex: String?)

    /** P3-a `text-emphasis` 系解析（style＋position；颜色恒走文本色）。 */
    private fun parseEmphasisStyle(w: Map<String, String>): EmphasisStyle {
        val raw = (w["text-emphasis-style"] ?: w["-epub-text-emphasis-style"]
            ?: w["text-emphasis"] ?: w["-epub-text-emphasis"] ?: "").trim().lowercase()
        if (raw.isEmpty() || raw == "none") return EmphasisStyle.NONE
        return when {
            raw.contains("circle") -> EmphasisStyle.CIRCLE
            raw.contains("dot") || raw.contains("sesame") -> EmphasisStyle.DOT
            raw.contains("filled") -> EmphasisStyle.DOT // filled 无形状即点
            else -> EmphasisStyle.NONE // triangle/double-circle 等暂不支持
        }
    }

    private fun parseEmphasisUnder(w: Map<String, String>): Boolean {
        val raw = (w["text-emphasis-position"] ?: w["-epub-text-emphasis-position"] ?: "").trim().lowercase()
        return raw.contains("under")
    }
    /**
     * `border-radius` geometry: shorthand 1–4 lengths (TL TR BR BL) overridden per corner by
     * `border-*-radius`. px/em/rem resolve now; `%` is box-size-relative and returns in the
     * second [CornerRadius] as 0..1 fractions (resolved+clamped at the P3-a draw point).
     */
    private fun parseBorderRadius(w: Map<String, String>, fontSize: Float, parentFontPx: Float): Pair<CornerRadius, CornerRadius> {
        fun pxAndPct(v: String?): Pair<Float, Float> {
            if (v == null) return 0f to 0f
            val first = v.trim().split(Regex("\\s*\\/\\s*|\\s+")).firstOrNull()?.lowercase() ?: return 0f to 0f
            val len = parseLength(first) ?: return 0f to 0f
            // `%` kept as a 0..1 fraction (resolved+clamped against box dims at draw).
            if (len is Length.Percent) return 0f to (len.value / 100f).coerceIn(0f, 10f)
            return len.resolve(fontSize, parentFontPx, rootFontPx).coerceAtLeast(0f) to 0f
        }
        val slots: List<String>? = w["border-radius"]?.split(Regex("\\s+"))?.filter { it.isNotBlank() }
        val quad = when {
            slots == null || slots.isEmpty() -> listOf(null, null, null, null)
            slots.size == 1 -> listOf(slots[0], slots[0], slots[0], slots[0])
            slots.size == 2 -> listOf(slots[0], slots[1], slots[0], slots[1])
            slots.size == 3 -> listOf(slots[0], slots[1], slots[2], slots[1])
            else -> listOf(slots[0], slots[1], slots[2], slots[3])
        }
        fun corner(prop: String, q: String?): Pair<Float, Float> = pxAndPct(w[prop] ?: q)
        val tl = corner("border-top-left-radius", quad[0])
        val tr = corner("border-top-right-radius", quad[1])
        val br = corner("border-bottom-right-radius", quad[2])
        val bl = corner("border-bottom-left-radius", quad[3])
        return CornerRadius(tl.first, tr.first, br.first, bl.first) to
            CornerRadius(tl.second, tr.second, br.second, bl.second)
    }

    /**
     * Resolves a box edge group from its shorthand + per-side properties. Individual `-top/-right/
     * -bottom/-left` win over the shorthand, and per-side values that are absent fall back to the
     * shorthand's matching slot. `margin: auto` is treated as 0 (block horizontal auto-centering is
     * a later refinement). Does not inherit (initial values are 0).
     */
    private fun parseEdges(
        w: Map<String, String>,
        shorthand: String,
        topProp: String, rightProp: String, bottomProp: String, leftProp: String,
        fontSize: Float, parentFontPx: Float,
    ): Edges {
        val split = w[shorthand]
            ?.let { value ->
                val slots = value.split(Regex("\\s+"), limit = 4).map { normalizeEdge(it, fontSize, parentFontPx) }
                when (slots.size) {
                    1 -> Edges(slots[0], slots[0], slots[0], slots[0])
                    2 -> Edges(slots[0], slots[1], slots[0], slots[1])
                    3 -> Edges(slots[0], slots[1], slots[2], slots[1])
                    4 -> Edges(slots[0], slots[1], slots[2], slots[3])
                    else -> null
                }
            }
        val base = split ?: Edges()
        fun slot(prop: String, from: Float): Float =
            w[prop]?.let { v -> normalizeEdge(v, fontSize, parentFontPx) } ?: from
        return Edges(
            slot(topProp, base.top),
            slot(rightProp, base.right),
            slot(bottomProp, base.bottom),
            slot(leftProp, base.left),
        )
    }

    private fun normalizeEdge(value: String, fontSize: Float, parentFontPx: Float): Float {
        val v = value.trim().lowercase()
        if (v == "auto" || v == "inherit" || v == "initial") return 0f
        return parseLength(v)?.resolve(fontSize, parentFontPx, rootFontPx) ?: 0f
    }

    private fun parseTextAlign(value: String): TextAlign = when (value.trim().lowercase()) {
        "center" -> TextAlign.CENTER
        "right" -> TextAlign.RIGHT
        "justify" -> TextAlign.JUSTIFY
        else -> TextAlign.LEFT
    }

    /** `list-style-type` keywords we resolve (image values are ignored by design). */
    private val LIST_STYLE_TYPES = setOf(
        "none", "disc", "circle", "square",
        "decimal", "decimal-leading-zero", "lower-alpha", "upper-alpha", "lower-roman", "upper-roman",
    )

    /** Splits the `list-style` shorthand: first known type keyword, "" when none. */
    private fun extractListStyleType(shorthand: String?): String {
        if (shorthand == null) return ""
        for (tok in shorthand.split(Regex("\\s+"))) {
            val t = tok.trim().lowercase()
            if (t in LIST_STYLE_TYPES) return t
        }
        return ""
    }

    /** Splits the `list-style` shorthand: inside/outside, "" when neither. */
    private fun extractListStylePosition(shorthand: String?): String {
        if (shorthand == null) return ""
        for (tok in shorthand.split(Regex("\\s+"))) {
            val t = tok.trim().lowercase()
            if (t == "inside" || t == "outside") return t
        }
        return ""
    }

    private fun parseWhiteSpace(value: String): WhiteSpace = when (value.trim().lowercase()) {
        "pre" -> WhiteSpace.PRE
        "nowrap" -> WhiteSpace.NOWRAP
        "pre-wrap" -> WhiteSpace.PRE_WRAP
        "pre-line" -> WhiteSpace.PRE_LINE
        else -> WhiteSpace.NORMAL
    }

    /** `letter-spacing`/`word-spacing`: normal → 0; lengths resolve against own font-size. */
    private fun parseSpacing(value: String, fontSize: Float, parentFontPx: Float): Float {
        val t = value.trim().lowercase()
        if (t == "normal") return 0f
        val len = parseLength(t) ?: return 0f
        if (len is Length.Percent) return 0f // invalid for these props
        return len.resolve(fontSize, parentFontPx, rootFontPx)
    }

    private fun parseTextTransform(value: String): TextTransform = when (value.trim().lowercase()) {
        "uppercase" -> TextTransform.UPPERCASE
        "lowercase" -> TextTransform.LOWERCASE
        "capitalize" -> TextTransform.CAPITALIZE
        else -> TextTransform.NONE
    }

    /** `vertical-align`: keywords only; lengths/percentages stay baseline until the shift is consumed. */
    private fun parseVerticalAlign(value: String): VerticalAlign = when (value.trim().lowercase()) {
        "sub" -> VerticalAlign.SUB
        "super" -> VerticalAlign.SUPER
        "middle" -> VerticalAlign.MIDDLE
        "top" -> VerticalAlign.TOP
        "bottom" -> VerticalAlign.BOTTOM
        else -> VerticalAlign.BASELINE
    }

    /** P2 `font-variant`: small-caps 系（含 all/petite 近似）→ SMALL_CAPS，其余 NORMAL。 */
    private fun parseFontVariant(value: String): FontVariant {
        val t = value.trim().lowercase()
        return if (t.contains("small-caps") || t.contains("petite-caps")) FontVariant.SMALL_CAPS
        else FontVariant.NORMAL
    }

    /** P2 `font-stretch`: 关键字/百分比 → 宽度比（1＝normal；非法回 1）。 */
    private fun parseFontStretch(value: String): Float {
        val t = value.trim().lowercase()
        if (t.endsWith("%")) return (t.removeSuffix("%").toFloatOrNull()?.div(100f))?.coerceIn(0.5f, 2f) ?: 1f
        return when (t) {
            "ultra-condensed" -> 0.5f
            "extra-condensed" -> 0.625f
            "condensed" -> 0.75f
            "semi-condensed" -> 0.875f
            "normal" -> 1f
            "semi-expanded" -> 1.125f
            "expanded" -> 1.25f
            "extra-expanded" -> 1.5f
            "ultra-expanded" -> 2f
            else -> 1f
        }
    }

    private fun parseBoxSizing(value: String): BoxSizing =
        if (value.trim().lowercase() == "border-box") BoxSizing.BORDER_BOX else BoxSizing.CONTENT_BOX

    private fun parseOpacity(value: String): Float =
        value.trim().toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f

    /**
     * `overflow-x/y` combined into one enum: both same → that; one visible → the other;
     * mixed non-visible pairs degrade to hidden (the consumption point is clipping only).
     */
    private fun parseOverflow(w: Map<String, String>): OverflowValue {
        fun one(v: String?): OverflowValue = when (v?.trim()?.lowercase()) {
            "hidden", "clip" -> OverflowValue.HIDDEN
            "scroll" -> OverflowValue.SCROLL
            "auto" -> OverflowValue.AUTO
            else -> OverflowValue.VISIBLE
        }
        val x = one(w["overflow-x"])
        val y = one(w["overflow-y"])
        w["overflow"]?.let { return one(it) }
        return when {
            x == y -> x
            x == OverflowValue.VISIBLE -> y
            y == OverflowValue.VISIBLE -> x
            else -> OverflowValue.HIDDEN
        }
    }

    /** `border-spacing`: 1–2 lengths (h [v]); px/em/rem resolve against own font-size.
     *  `%` is invalid for this property → 0. Non-inherited; collapse forces 0 at use site. */
    private fun parseBorderSpacing(raw: String, fontSize: Float): Pair<Float, Float> {
        fun pxOf(tok: String): Float {
            val len = parseLength(tok.trim().lowercase()) ?: return 0f
            if (len is Length.Percent) return 0f
            return len.resolve(fontSize, fontSize, rootFontPx).coerceAtLeast(0f)
        }
        val toks = raw.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        return when (toks.size) {
            0 -> 0f to 0f
            1 -> { val v = pxOf(toks[0]); v to v }
            else -> pxOf(toks[0]) to pxOf(toks[1])
        }
    }

    /** Resolves the computed font-size: em/% → parent, rem → root, px absolute; else inherit parent. */
    private fun resolveFontSize(raw: String?, parentPx: Float): Float {
        val len = raw?.let { parseLength(it) } ?: return parentPx
        return when (len) {
            is Length.Px -> len.value
            is Length.Em -> parentPx * len.value
            is Length.Percent -> parentPx * len.value / 100f
            is Length.Rem -> rootFontPx * len.value
        }
    }

    private fun parseUnderline(value: String): Boolean = when {
        value.contains("underline") || value.contains("line-through") -> true
        else -> false
    }
}