package orilumn.reader.engine.css

import orilumn.reader.engine.html.MarkupElement

/**
 * A parsed CSS selector with its specificity and element matching (pure JVM, unit-testable).
 *
 * Supports the selector breadth found in EPUB author stylesheets: type, universal `*`, `.class`,
 * `#id`, one attribute predicate `[a]` / `[a=v]`, and the **descendant** combinator (whitespace).
 * Pseudo-classes/pseudo-elements (`:hover`, `::before`, ...) and `:not(...)` are parsed;
 * link dynamic pseudo-classes follow the browser standard for a stateless reflowable reader:
 * `:link` matches `<a href>` (no visited history ⇒ unvisited); `:visited` never matches;
 * `:hover`/`:active`/`:focus*` never match (no pointer/activation/focus state is retained);
 * `::before/::after`（含单冒号写法）记在 [pseudoElement] 供级联按伪元素分流（P3-c 生成内容），
 * [matches] 本身仍忽略它们。其它结构伪类（`:first-child` 等）暂恒真，为已知缺口，非本轮范围。
 * Because [orilumn.reader.engine.html.MarkupElement] only stores children (no parent back-pointer),
 * [matches] requires the element's ancestor chain ([ancestors], nearest last) which the tree walker
 * provides.
 */
class Selector private constructor(
    /** The text the selector was parsed from (for debugging / order-independent equality). */
    val text: String,
    /** Right-to-left chain of compound selectors. */
    private val compounds: List<List<Simple>>,
    /** Combinator between consecutive compounds: [edges][i] connects [compounds][i] and
     *  [compounds][i+1]. Size is always [compounds].size - 1. */
    private val edges: List<Combinator>,
    /** Specificity `(id, class, type)` — exposed for the cascade's tie-break. */
    val specificity: Specificity,
) {
    /** Combinator between two compound selectors. */
    enum class Combinator {
        /** whitespace: an element anywhere down the ancestor chain. */
        DESCENDANT,
        /** `>`: a direct child. */
        CHILD,
        /** `+`: the immediately preceding sibling. */
        ADJACENT,
        /** `~`: any preceding sibling. */
        GENERAL_SIBLING,
    }

    /** Simple-selector kind. */
    private sealed class Simple {
        data class Tag(val name: String) : Simple()
        data class Clazz(val name: String) : Simple()
        data class Id(val name: String) : Simple()
        data class Attr(val name: String, val value: String?) : Simple()
        object Any : Simple() // universal *
        /** 伪类/伪元素名：`before/after` 恒真（由 [Selector.pseudoElement] 分流）；
         *  动态伪类按浏览器标准（见 [Selector.matchesPseudo]），其余结构伪类暂恒真（已知缺口）。 */
        data class Pseudo(val name: String) : Simple()
        object Ignored : Simple() // 空伪名等解析残留：恒真
    }

    data class Specificity(val id: Int, val clazz: Int, val type: Int) : Comparable<Specificity> {
        override fun compareTo(other: Specificity): Int = compareValuesBy(this, other, { it.id }, { it.clazz }, { it.type })
    }

    /** The (single) tag in the rightmost compound, or null when that compound has no tag (e.g. a
     *  universal/class/id-only selector applies to any element). Used by [orilumn.reader.engine.css.Cascade]
     *  to cheaply skip tag-incompatible matchers before the full match. */
    val rightmostTag: String?
        get() {
            val last = compounds.lastOrNull() ?: return null
            for (s in last) if (s is Simple.Tag) return s.name
            return null
        }

    /**
     * P3-c: 最右复合中的生成伪元素（`::before/:before` → `"before"`，
     * `::after/:after` → `"after"`，其余/无即 null）。级联按它把伪元素规则
     * 从元素自身匹配中分流；同一复合内多个伪名取最后一个 before/after。
     */
    val pseudoElement: String?
        get() {
            val last = compounds.lastOrNull() ?: return null
            var out: String? = null
            for (s in last) {
                if (s is Simple.Pseudo && (s.name == "before" || s.name == "after")) out = s.name
            }
            return out
        }

    /** Whether this element (with its [ancestors], nearest last) matches the selector. The rightmost
     *  compound must match [el] itself; the chain then walks left across [edges]. */
    fun matches(el: MarkupElement, ancestors: List<MarkupElement>): Boolean {
        if (compounds.isEmpty()) return false
        if (!compoundMatches(compounds[compounds.size - 1], el)) return false
        return matchesChain(compounds.size - 1, el, ancestors, ancestors.size)
    }

    /**
     * After [compounds][ci] matched on [el], decides where the next-left compound [compound][ci-1] must
     * be found (per [edges][ci-1]): an ancestor (descendant/child) or a previous sibling (adjacent/
     * general). [ancLimit] is the number of [ancestors] visible for the current node's level.
     */
    private fun matchesChain(ci: Int, el: MarkupElement, ancestors: List<MarkupElement>, ancLimit: Int): Boolean {
        if (ci == 0) return true
        return when (edges[ci - 1]) {
            Combinator.DESCENDANT -> {
                var i = ancLimit - 1
                while (i >= 0) {
                    val anc = ancestors[i]
                    if (compoundMatches(compounds[ci - 1], anc) && matchesChain(ci - 1, anc, ancestors, i)) return true
                    i--
                }
                false
            }
            Combinator.CHILD -> {
                val p = el.parent
                p != null && compoundMatches(compounds[ci - 1], p) && matchesChain(ci - 1, p, ancestors, ancLimit - 1)
            }
            Combinator.ADJACENT -> {
                val prev = previousSibling(el)
                prev != null && compoundMatches(compounds[ci - 1], prev) && matchesChain(ci - 1, prev, ancestors, ancLimit)
            }
            Combinator.GENERAL_SIBLING -> {
                var s = previousSibling(el)
                while (s != null) {
                    if (compoundMatches(compounds[ci - 1], s) && matchesChain(ci - 1, s, ancestors, ancLimit)) return true
                    s = previousSibling(s)
                }
                false
            }
        }
    }

    /** The immediately preceding **element** sibling of [el], or null. Whitespace-only text nodes are
     *  skipped: per CSS, the `+`/`~` adjacency combinator is defined over elements, and the
     *  whitespace jsoup preserves between block tags must not break it. A `#text` node with
     *  non-whitespace content still blocks — in a browser such content forms an intervening inline
     *  box, so an element that far back is not "immediately" preceding. */
    private fun previousSibling(el: MarkupElement): MarkupElement? {
        val p = el.parent ?: return null
        var i = p.children.indexOf(el) - 1
        while (i >= 0) {
            val c = p.children[i]
            if (c.tag != "#text" || !c.text.isBlank()) return c
            i--
        }
        return null
    }

    private fun compoundMatches(compound: List<Simple>, el: MarkupElement): Boolean {
        for (s in compound) {
            when (s) {
                is Simple.Tag -> if (el.tag != s.name) return false
                is Simple.Clazz -> if (!hasClass(el, s.name)) return false
                is Simple.Id -> if (el.attrs["id"] != s.name) return false
                is Simple.Attr -> if (!s.matchesAttr(el)) return false
                is Simple.Pseudo -> if (!matchesPseudo(s.name, el)) return false
                is Simple.Any, is Simple.Ignored -> Unit
            }
        }
        return true
    }

    /**
     * 动态伪类匹配（浏览器标准，无状态重排阅读器口径）：
     * - `link`：`<a href>` 恒真（无访问历史 ⇒ 未访问）；无 href 的 `a`/其它元素恒假；
     * - `visited`：恒假（无访问历史）；
     * - `hover`/`active`：恒假（不保留指针悬停/激活瞬态）；
     * - `focus`/`focus-visible`/`focus-within`：恒假（无焦点模型）；
     * - `before`/`after`：恒真（伪元素分流见 [pseudoElement]，此处不拦截）；
     * - 其余（结构伪类等）：暂恒真（已知缺口：`:first-child` 等尚未实现，过度匹配）。
     */
    private fun matchesPseudo(name: String, el: MarkupElement): Boolean = when (name) {
        "link" -> el.tag == "a" && el.attrs.containsKey("href")
        "visited", "hover", "active", "focus", "focus-visible", "focus-within" -> false
        else -> true
    }

    private fun hasClass(el: MarkupElement, name: String): Boolean {
        val cls = el.attrs["class"] ?: return false
        // Manual whitespace-token scan (no Regex/split): hasClass runs for every `.class` selector test
        // on every node, so any per-call allocation or Pattern compile would be pathologically slow on a
        // large chapter.
        var i = 0
        val n = cls.length
        while (i < n) {
            while (i < n && cls[i].isWhitespace()) i++
            val start = i
            while (i < n && !cls[i].isWhitespace()) i++
            if (i > start && cls.substring(start, i) == name) return true
        }
        return false
    }

    private fun Simple.Attr.matchesAttr(el: MarkupElement): Boolean {
        val actual = el.attrs[name] ?: return false
        return value == null || actual == value
    }

    companion object {
        /** Parses a single selector string (a comma group belongs to separate Selectors). Returns
         * null for empty/invalid selectors. Supports the combinators: whitespace (descendant),
         * `>` (child), `+` (adjacent sibling) and `~` (general sibling). */
        fun parse(raw: String): Selector? {
            val text = raw.trim()
            if (text.isEmpty() || text == "*") {
                return if (text.isEmpty()) null else Selector(text, listOf(listOf(Simple.Any)), emptyList(), Specificity(0, 0, 0))
            }
            val compounds = ArrayList<List<Simple>>()
            val edges = ArrayList<Combinator>()
            var specId = 0
            var specClass = 0
            var specType = 0
            // Scan compounds separated by combinators (`>`, `+`, `~`) or a whitespace descendant gap,
            // all at bracket level 0 (attribute brackets are the only bracket-balanced context here).
            var segStart = 0
            var bracket = 0
            var i = 0
            val n = text.length
            // Appends the compound in [segStart..end) when it holds content (trailing/leading gaps are
            // skipped). Returns false on malformed compound content.
            fun flush(end: Int): Boolean {
                val seg = text.substring(segStart, end).trim()
                if (seg.isEmpty()) return true
                return parseCompound(seg)?.let { c ->
                    compounds.add(c.first)
                    specId += c.second.id; specClass += c.second.clazz; specType += c.second.type
                    true
                } ?: false
            }
            while (i < n) {
                val c = text[i]
                when {
                    c == '[' -> { bracket++; i++ }
                    c == ']' -> { if (bracket > 0) bracket--; i++ }
                    bracket == 0 && (c == '>' || c == '+' || c == '~') -> {
                        if (!flush(i)) return null
                        edges.add(
                            when (c) {
                                '>' -> Combinator.CHILD
                                '+' -> Combinator.ADJACENT
                                else -> Combinator.GENERAL_SIBLING
                            },
                        )
                        i++
                        while (i < n && text[i].isWhitespace()) i++
                        segStart = i
                        continue
                    }
                    c.isWhitespace() && bracket == 0 -> {
                        if (!flush(i)) return null
                        while (i < n && text[i].isWhitespace()) i++
                        // A descendant gap only if what follows is not itself a combinator (which the
                        // next iteration handles); an orphan combinator makes the selector invalid below.
                        if (i < n && (text[i] == '>' || text[i] == '+' || text[i] == '~')) {
                            segStart = i
                        } else {
                            edges.add(Combinator.DESCENDANT)
                            segStart = i
                        }
                        continue
                    }
                    else -> i++
                }
            }
            if (!flush(n)) return null
            if (compounds.isEmpty()) return null
            // A selector must have exactly one fewer combinator than compounds (leading/trailing
            // combinators are invalid).
            if (edges.size != compounds.size - 1) return null
            return Selector(text, compounds, edges, Specificity(specId, specClass, specType))
        }

        /** Parses one compound into its simple selectors plus its specificity contribution. */
        private fun parseCompound(seg: String): Pair<List<Simple>, Specificity>? {
            val s = seg.trim()
            if (s.isEmpty()) return null
            val out = ArrayList<Simple>()
            var id = 0; var clazz = 0; var typ = 0
            var k = 0
            val n = s.length
            while (k < n) {
                val c = s[k]
                when (c) {
                    '*' -> { out.add(Simple.Any); k++ }
                    '.', '#' -> {
                        val name = readIdentifier(s, k + 1)
                        if (name.isEmpty()) return null
                        if (c == '.') { out.add(Simple.Clazz(name)); clazz++ } else { out.add(Simple.Id(name)); id++ }
                        k += 1 + name.length
                    }
                    '[' -> {
                        val close = s.indexOf(']', k)
                        if (close < 0) return null
                        val body = s.substring(k + 1, close).trim()
                        val eq = body.indexOf('=')
                        val attrName = (if (eq >= 0) body.substring(0, eq) else body).trim().lowercase()
                        val attrVal = if (eq >= 0) body.substring(eq + 1).trim().trim('"', '\'') else null
                        if (attrName.isEmpty()) return null
                        out.add(Simple.Attr(attrName, attrVal))
                        clazz++
                        k = close + 1
                    }
                    ':' -> {
                        // pseudo-class or pseudo-element: keep the name (before/after分流用),
                        // 动态伪类匹配口径见 matchesPseudo（link/visited/hover/active/focus 按浏览器标准）。
                        val j = k + 1
                        // skip optional leading ':' for pseudo-elements
                        val start = if (j < n && s[j] == ':') j + 1 else j
                        val nameEnd = start
                        k = skipPseudo(s, nameEnd)
                        val name = s.substring(start, k).lowercase()
                        if (name.isEmpty()) {
                            // 空伪名（孤立冒号）：残留 Ignored 保解析，其余照旧。
                            if (k < n && s[k] == '(') k = matchingParen(s, k) + 1
                            out.add(Simple.Ignored)
                            if (s.substring(start, k).isEmpty()) k++
                        } else {
                            // ::before/::after etc. consume to end of the parenthesized args if any
                            if (k < n && s[k] == '(') k = matchingParen(s, k) + 1
                            out.add(Simple.Pseudo(name))
                        }
                    }
                    else -> {
                        val nameEnd = readIdentifierEnd(s, k)
                        if (nameEnd <= k) return null
                        out.add(Simple.Tag(s.substring(k, nameEnd).lowercase()))
                        typ++
                        k = nameEnd
                    }
                }
            }
            if (out.isEmpty()) return null
            return out to Specificity(id, clazz, typ)
        }

        private fun readIdentifier(s: String, from: Int): String {
            var e = from
            while (e < s.length && (s[e].isLetterOrDigit() || s[e] == '-' || s[e] == '_')) e++
            return s.substring(from, e)
        }

        private fun readIdentifierEnd(s: String, from: Int): Int {
            var e = from
            while (e < s.length && (s[e].isLetterOrDigit() || s[e] == '-' || s[e] == '_')) e++
            return e
        }

        private fun skipPseudo(s: String, from: Int): Int {
            var e = from
            while (e < s.length && (s[e].isLetterOrDigit() || s[e] == '-' || s[e] == '_')) e++
            return e
        }

        private fun matchingParen(s: String, open: Int): Int {
            var depth = 0
            var k = open
            while (k < s.length) {
                when (s[k]) {
                    '(' -> depth++
                    ')' -> { depth--; if (depth == 0) return k }
                }
                k++
            }
            return k
        }
    }
}