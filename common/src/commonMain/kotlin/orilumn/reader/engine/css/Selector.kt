package orilumn.reader.engine.css

import orilumn.reader.engine.html.MarkupElement

/**
 * A parsed CSS selector with its specificity and element matching (pure JVM, unit-testable).
 *
 * 支持范围（对齐浏览器语义，以 EPUB 作者样式表实际会写的为准）：
 * - 简单选择器：类型、`*`、`.class`、`#id`、属性谓词 `[a]` / `[a=v]`；
 * - 组合器：后代（空白）、`>`、`+`、`~`（`+`/`~` 的相邻性口径见 [previousSibling]）；
 * - 动态伪类：`:link`/`:any-link`（`<a href>` 恒真，无访问历史）、`:visited`/`:hover`/`:active`/
 *   `:focus*`/`:target` 等有状态伪类**恒假**（无状态重排阅读器）；
 * - 结构伪类：`:root`/`:scope`/`:empty`/`:first-child`/`:last-child`/`:only-child`/
 *   `:first-of-type`/`:last-of-type`/`:only-of-type`/`:nth-child()`/`:nth-last-child()`/
 *   `:nth-of-type()`/`:nth-last-of-type()`（`odd`/`even`/`An+B`），以及 `:lang()`；
 * - 函数式伪类：`:not()`/`:is()`/`:matches()`/`:where()`（参数可为含组合器的选择器）、
 *   `:has()`（相对选择器：后代 / `>` / `+` / `~`）；
 * - 伪元素：`::before`/`::after`/`::first-letter`（含单冒号写法）有绘制路径，经 [pseudoElement] 分流；
 *   其余伪元素（`::first-line`/`::marker`/`::selection` …）**整条不匹配**。
 *
 * **未知伪类一律不匹配**。旧行为对未列出的伪名恒真 ⇒ 过度匹配：`:not(.x)` 曾把规则套到全部同类
 * 元素、`::first-letter` 曾让整段放大 3 倍。伪类条件不是可选项：匹配不上最多丢一条规则（回落默认），
 * 匹配过宽会直接改错版面。分流后 `::first-letter` 只作用于**首字**那一个段（见
 * `orilumn.reader.engine.laying.styledSegments` 的 `firstLetter` 形参），整段尺寸不受影响。
 *
 * 结构伪类按 CSS 口径只数**元素**兄弟（`#text` 不计数），依据 `MarkupElement.parent` 的真实树，
 * 与调用方传入 [matches] 的祖先链无关。
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

        /**
         * 伪类 / 伪元素。
         * - [arg]：函数式伪类的原始参数文本（`:nth-child(2n+1)`、`:lang(en)`）；
         * - [list]：`:not()`/`:is()`/`:matches()`/`:where()` 的参数选择器表；
         * - [rel]：`:has()` 的相对选择器表（首组合器 + 选择器）；
         * - [isElement]：伪元素（`::x`，或单冒号写法 `:before`/`:first-line`）。
         *
         * `before`/`after` 恒真（由 [Selector.pseudoElement] 按伪元素分流，见 [pseudoMatches]）。
         */
        data class Pseudo(
            val name: String,
            val arg: String? = null,
            val list: List<Selector>? = null,
            val rel: List<Pair<Combinator, Selector>>? = null,
            val isElement: Boolean = false,
        ) : Simple()

        object Ignored : Simple() // 空伪名等解析残留：恒真
        object Never : Simple() // 无效函数式伪类参数（`:is()` 无有效参数等）：必不匹配
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
     * P3-c: 最右复合中的伪元素（`::before/:before` → `"before"`，`::after/:after` → `"after"`，
     * `::first-letter/:first-letter` → `"first-letter"`，其余/无即 null）。级联按它把伪元素规则
     * 从元素自身匹配中分流；同一复合内多个伪名取最后一个已支持者。
     */
    val pseudoElement: String?
        get() {
            val last = compounds.lastOrNull() ?: return null
            var out: String? = null
            for (s in last) {
                if (s is Simple.Pseudo && (s.name == "before" || s.name == "after" || s.name == "first-letter")) out = s.name
            }
            return out
        }

    /** Whether this element (with its [ancestors], nearest last) matches the selector. The rightmost
     *  compound must match [el] itself; the chain then walks left across [edges]. */
    fun matches(el: MarkupElement, ancestors: List<MarkupElement>): Boolean {
        if (compounds.isEmpty()) return false
        if (!compoundMatches(compounds[compounds.size - 1], el, ancestors, ancestors.size)) return false
        return matchesChain(compounds.size - 1, el, ancestors, ancestors.size)
    }

    /**
     * After [compounds][ci] matched on [el], decides where the next-left compound [compounds][ci-1] must
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
                    if (compoundMatches(compounds[ci - 1], anc, ancestors, i) && matchesChain(ci - 1, anc, ancestors, i)) return true
                    i--
                }
                false
            }
            Combinator.CHILD -> {
                val p = el.parent
                p != null && compoundMatches(compounds[ci - 1], p, ancestors, ancLimit - 1) &&
                    matchesChain(ci - 1, p, ancestors, ancLimit - 1)
            }
            Combinator.ADJACENT -> {
                val sib = previousSibling(el)
                sib != null && compoundMatches(compounds[ci - 1], sib, ancestors, ancLimit) &&
                    matchesChain(ci - 1, sib, ancestors, ancLimit)
            }
            Combinator.GENERAL_SIBLING -> {
                var sib = previousSibling(el)
                while (sib != null) {
                    if (compoundMatches(compounds[ci - 1], sib, ancestors, ancLimit) &&
                        matchesChain(ci - 1, sib, ancestors, ancLimit)
                    ) {
                        return true
                    }
                    sib = previousSibling(sib)
                }
                false
            }
        }
    }

    /**
     * 上一个兄弟节点。空白 `#text` 跳过（浏览器里元素间源码换行不成盒，`+`/`~` 仍算相邻）；
     * 非空白 `#text` 仍然阻断 —— 浏览器里它成形一个行内盒，隔过它就不是「紧邻」。
     */
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

    /**
     * 下一个**元素**兄弟（`#text` 全跳过）。`:has(+ …)`/`:has(~ …)` 用 CSS 的元素相邻口径
     * （不像 [previousSibling] 那样让非空白文本"挡住"）—— 结构判定只认元素。
     */
    private fun nextElementSibling(el: MarkupElement): MarkupElement? {
        val p = el.parent ?: return null
        var i = p.children.indexOf(el) + 1
        while (i < p.children.size) {
            val c = p.children[i]
            if (isElementNode(c)) return c
            i++
        }
        return null
    }

    private fun compoundMatches(compound: List<Simple>, el: MarkupElement, ancestors: List<MarkupElement>, ancLimit: Int): Boolean {
        for (s in compound) {
            when (s) {
                is Simple.Tag -> if (el.tag != s.name) return false
                is Simple.Clazz -> if (!hasClass(el, s.name)) return false
                is Simple.Id -> if (el.attrs["id"] != s.name) return false
                is Simple.Attr -> if (!s.matchesAttr(el)) return false
                is Simple.Pseudo -> if (!pseudoMatches(s, el, ancestors, ancLimit)) return false
                is Simple.Never -> return false
                is Simple.Any, is Simple.Ignored -> Unit
            }
        }
        return true
    }

    /**
     * 伪类 / 伪元素匹配（浏览器标准，无状态重排阅读器口径）：
     * - `before`/`after`/`first-letter`：恒真（伪元素分流见 [pseudoElement]，此处不拦截）；其余伪元素恒假；
     * - `link`/`any-link`：`<a href>` 恒真；`visited`/`hover`/`active`/`focus*`/`target` 等恒假；
     * - 结构伪类按文档序判定；`:not()` 取反、`:is()`/`:where()` 取并集、`:has()` 查后代/兄弟；
     * - **未知伪名一律恒假**（旧行为恒真 ⇒ 过度匹配）。
     */
    private fun pseudoMatches(p: Simple.Pseudo, el: MarkupElement, ancestors: List<MarkupElement>, ancLimit: Int): Boolean {
        if (p.isElement) return p.name == "before" || p.name == "after" || p.name == "first-letter"
        return when (p.name) {
            "link", "any-link" -> el.tag == "a" && el.attrs.containsKey("href")
            "root", "scope" -> el.parent == null
            "empty" -> el.children.isEmpty()
            "first-child" -> elementIndex(el) == 1
            "last-child" -> elementIndexFromEnd(el) == 1
            "only-child" -> elementIndex(el) == 1 && elementIndexFromEnd(el) == 1
            "first-of-type" -> typeIndex(el) == 1
            "last-of-type" -> typeIndexFromEnd(el) == 1
            "only-of-type" -> typeIndex(el) == 1 && typeIndexFromEnd(el) == 1
            "nth-child" -> nthMatches(p.arg, elementIndex(el))
            "nth-last-child" -> nthMatches(p.arg, elementIndexFromEnd(el))
            "nth-of-type" -> nthMatches(p.arg, typeIndex(el))
            "nth-last-of-type" -> nthMatches(p.arg, typeIndexFromEnd(el))
            "lang" -> langMatches(p.arg, el)
            "not" -> !p.list.orEmpty().any { it.matches(el, subAncestors(ancestors, ancLimit)) }
            "is", "matches", "where" -> p.list.orEmpty().any { it.matches(el, subAncestors(ancestors, ancLimit)) }
            "has" -> hasMatches(p.rel.orEmpty(), el, ancestors, ancLimit)
            else -> false
        }
    }

    /**
     * `:has(rel)`：至少一个相对选择器在 [el] 的对应方向命中。
     * 相对选择器按首组合器决定搜索方向（缺省 = 后代，`>` 子代，`+` 相邻兄弟，`~` 后续兄弟）；
     * 兄弟方向按 CSS 只数元素（`#text` 不计数）。
     */
    private fun hasMatches(
        rel: List<Pair<Combinator, Selector>>,
        el: MarkupElement,
        ancestors: List<MarkupElement>,
        ancLimit: Int,
    ): Boolean {
        if (rel.isEmpty()) return false
        val own = subAncestors(ancestors, ancLimit)
        for ((comb, sel) in rel) {
            when (comb) {
                Combinator.CHILD -> {
                    val chain = own + el
                    for (c in el.children) if (isElementNode(c) && sel.matches(c, chain)) return true
                }
                Combinator.DESCENDANT -> {
                    if (walkDescendants(el, own + el, sel)) return true
                }
                Combinator.ADJACENT -> {
                    val next = nextElementSibling(el)
                    if (next != null && sel.matches(next, own)) return true
                }
                Combinator.GENERAL_SIBLING -> {
                    var next = nextElementSibling(el)
                    while (next != null) {
                        if (sel.matches(next, own)) return true
                        next = nextElementSibling(next)
                    }
                }
            }
        }
        return false
    }

    /** [node] 的子树里是否有节点匹配 [sel]；[nodeChain] 是 [node] 的祖先链（nearest last）。 */
    private fun walkDescendants(node: MarkupElement, nodeChain: List<MarkupElement>, sel: Selector): Boolean {
        for (c in node.children) {
            if (!isElementNode(c)) continue
            if (sel.matches(c, nodeChain)) return true
            if (walkDescendants(c, nodeChain + c, sel)) return true
        }
        return false
    }

    /** [ancestors] 的前 [ancLimit] 项（[ancLimit] 已覆盖全表时不再切视图）。 */
    private fun subAncestors(ancestors: List<MarkupElement>, ancLimit: Int): List<MarkupElement> =
        if (ancLimit >= ancestors.size) ancestors else ancestors.subList(0, ancLimit.coerceAtLeast(0))

    /** 结构计数只认元素：`#text` 叶子不算兄弟（CSS 口径；`<p>a<b>x</b></p>` 里 `b` 是 first-child）。 */
    private fun isElementNode(e: MarkupElement): Boolean = e.tag != "#text"

    /** 1-based 元素序号（含自身），无父节点 → -1。 */
    private fun elementIndex(el: MarkupElement): Int {
        val p = el.parent ?: return -1
        var idx = 0
        for (c in p.children) {
            if (!isElementNode(c)) continue
            idx++
            if (c === el) return idx
        }
        return -1
    }

    /** 从末尾数的 1-based 元素序号（含自身），无父节点 → -1。 */
    private fun elementIndexFromEnd(el: MarkupElement): Int {
        val p = el.parent ?: return -1
        var idx = 0
        for (i in p.children.indices.reversed()) {
            val c = p.children[i]
            if (!isElementNode(c)) continue
            idx++
            if (c === el) return idx
        }
        return -1
    }

    /** 同标签元素的 1-based 序号（含自身），无父节点 → -1。 */
    private fun typeIndex(el: MarkupElement): Int {
        val p = el.parent ?: return -1
        var idx = 0
        for (c in p.children) {
            if (!isElementNode(c) || c.tag != el.tag) continue
            idx++
            if (c === el) return idx
        }
        return -1
    }

    /** 同标签元素从末尾数的 1-based 序号（含自身），无父节点 → -1。 */
    private fun typeIndexFromEnd(el: MarkupElement): Int {
        val p = el.parent ?: return -1
        var idx = 0
        for (i in p.children.indices.reversed()) {
            val c = p.children[i]
            if (!isElementNode(c) || c.tag != el.tag) continue
            idx++
            if (c === el) return idx
        }
        return -1
    }

    /** `An+B` 是否命中 1-based 序号 [index]（`index < 1` 恒假）。 */
    private fun nthMatches(arg: String?, index: Int): Boolean {
        if (index < 1) return false
        val ab = parseNth(arg) ?: return false
        val a = ab.first
        val b = ab.second
        if (a == 0) return index == b
        val d = index - b
        return d % a == 0 && d / a >= 0
    }

    /**
     * 解析 `odd`/`even`/`An+B`/纯整数为 `(a, b)`；无法解析（含 Selectors 4 的 `of S` 子句）
     * 返回 null ⇒ 该伪类恒假（宁可丢规则也不过度匹配）。
     */
    private fun parseNth(raw: String?): Pair<Int, Int>? {
        val text = raw?.trim()?.lowercase() ?: return null
        if (text.isEmpty()) return null
        if (text.split(' ', '\t', '\n').any { it == "of" }) return null
        val s = text.filterNot { it.isWhitespace() }
        if (s == "odd") return 2 to 1
        if (s == "even") return 2 to 0
        val nIdx = s.indexOf('n')
        if (nIdx < 0) return s.toIntOrNull()?.let { 0 to it }
        val aPart = s.substring(0, nIdx)
        val bPart = s.substring(nIdx + 1)
        val a = when (aPart) {
            "", "+" -> 1
            "-" -> -1
            else -> aPart.toIntOrNull() ?: return null
        }
        val b = if (bPart.isEmpty()) 0 else bPart.toIntOrNull() ?: return null
        return a to b
    }

    /**
     * `:lang(x)` 是否命中 [el]：取最近一层有 `lang` 的元素（HTML 的 `xml:lang` 已在解析层
     * 归一成 `lang`），按 `x` 或 `x-…` 前缀匹配（RFC 4647 basic filtering）。
     */
    private fun langMatches(arg: String?, el: MarkupElement): Boolean {
        var want = arg?.trim()?.lowercase() ?: return false
        want = want.removeSurrounding("\"").removeSurrounding("'")
        if (want.isEmpty()) return false
        var n: MarkupElement? = el
        while (n != null) {
            val v = n.attrs["lang"]
            if (!v.isNullOrBlank()) {
                val got = v.trim().lowercase()
                return got == want || got.startsWith("$want-")
            }
            n = n.parent
        }
        return false
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
        /**
         * Parses a single selector string (a comma group belongs to separate Selectors). Returns
         * null for empty/invalid selectors. Supports the combinators: whitespace (descendant),
         * `>` (child), `+` (adjacent sibling) and `~` (general sibling).
         *
         * 括号内的空白与 `>`/`+`/`~` 不参与组合器切分 —— 不这样 `:nth-child(2n + 1)` 与
         * `:not(p a)` 会被拦腰切断（括号深度与属性方括号一起计入 bracket 判定）。
         */
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
            // all at bracket/paren level 0 (attribute brackets and pseudo-function parens).
            var segStart = 0
            var bracket = 0
            var paren = 0
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
                    c == '(' -> { if (bracket == 0) paren++; i++ }
                    c == ')' -> { if (bracket == 0 && paren > 0) paren--; i++ }
                    bracket == 0 && paren == 0 && (c == '>' || c == '+' || c == '~') -> {
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
                    c.isWhitespace() && bracket == 0 && paren == 0 -> {
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
                        // pseudo-class or pseudo-element: 名字与参数都留下（分流与匹配见 pseudoMatches）。
                        val j = k + 1
                        val doubleColon = j < n && s[j] == ':'
                        val start = if (doubleColon) j + 1 else j
                        k = skipPseudo(s, start)
                        val name = s.substring(start, k).lowercase()
                        if (name.isEmpty()) {
                            // 空伪名（孤立冒号）：残留 Ignored 保解析，其余照旧。
                            if (k < n && s[k] == '(') {
                                val close = matchingParen(s, k)
                                if (close >= n) return null
                                k = close + 1
                            }
                            out.add(Simple.Ignored)
                        } else {
                            var arg: String? = null
                            if (k < n && s[k] == '(') {
                                val close = matchingParen(s, k)
                                if (close >= n) return null
                                arg = s.substring(k + 1, close)
                                k = close + 1
                            }
                            val isElement = doubleColon || name == "before" || name == "after" ||
                                name == "first-line" || name == "first-letter"
                            val pseudo = buildPseudo(name, arg, isElement)
                            val sp = pseudoSpecificity(pseudo)
                            id += sp.id; clazz += sp.clazz; typ += sp.type
                            out.add(pseudo)
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

        /**
         * 函数式伪类 → [Simple]：
         * - `:not()`/`:is()`/`:matches()`/`:where()`：参数按顶层逗号切分各自 [parse]（容错：无效参数丢弃）；
         *   一个有效参数都没有 ⇒ [Simple.Never]（整条不匹配，不外溢）；
         * - `:has()`：参数按相对选择器切分（首组合器可为 `>`/`+`/`~`，缺省后代）。
         */
        private fun buildPseudo(name: String, arg: String?, isElement: Boolean): Simple {
            if (isElement) return Simple.Pseudo(name, arg, isElement = true)
            return when (name) {
                "not", "is", "matches", "where" -> {
                    val list = splitTopLevel(arg.orEmpty()).mapNotNull { parse(it) }
                    if (list.isEmpty()) Simple.Never else Simple.Pseudo(name, arg, list = list)
                }
                "has" -> {
                    val rel = splitTopLevel(arg.orEmpty()).mapNotNull { parseRelative(it) }
                    if (rel.isEmpty()) Simple.Never else Simple.Pseudo(name, arg, rel = rel)
                }
                else -> Simple.Pseudo(name, arg)
            }
        }

        /** 按顶层逗号切分（括号/方括号内不切），去空白、丢空段。 */
        private fun splitTopLevel(raw: String): List<String> {
            val out = ArrayList<String>()
            var depth = 0
            var start = 0
            var i = 0
            while (i < raw.length) {
                when (raw[i]) {
                    '(', '[' -> depth++
                    ')', ']' -> if (depth > 0) depth--
                    ',' -> if (depth == 0) { out.add(raw.substring(start, i)); start = i + 1 }
                }
                i++
            }
            out.add(raw.substring(start))
            return out.map { it.trim() }.filter { it.isNotEmpty() }
        }

        /** `:has()` 的相对选择器：可选首组合器（`>`/`+`/`~`，缺省后代）+ 选择器。 */
        private fun parseRelative(raw: String): Pair<Combinator, Selector>? {
            val t = raw.trim()
            if (t.isEmpty()) return null
            val comb = when (t[0]) {
                '>' -> Combinator.CHILD
                '+' -> Combinator.ADJACENT
                '~' -> Combinator.GENERAL_SIBLING
                else -> null
            }
            val rest = if (comb == null) t else t.substring(1).trim()
            val sel = parse(rest) ?: return null
            return (comb ?: Combinator.DESCENDANT) to sel
        }

        /**
         * 伪类的特异度增量（Selectors 4）：
         * 伪元素 = 类型级；`:where()` = 0；`:is()`/`:matches()`/`:not()` = 参数中最大者；
         * `:has()` = 相对选择器参数中最大者；其余伪类 = 类级。
         */
        private fun pseudoSpecificity(p: Simple): Specificity = when (p) {
            is Simple.Pseudo -> when {
                p.isElement -> Specificity(0, 0, 1)
                p.name == "where" -> Specificity(0, 0, 0)
                p.name == "not" || p.name == "is" || p.name == "matches" ->
                    p.list.orEmpty().maxOfOrNull { it.specificity } ?: Specificity(0, 0, 0)
                p.name == "has" -> p.rel.orEmpty().maxOfOrNull { it.second.specificity } ?: Specificity(0, 0, 0)
                else -> Specificity(0, 1, 0)
            }
            else -> Specificity(0, 0, 0)
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

        /** Index of the `)` matching the `(` at [from], or [String.length] when unbalanced. */
        private fun matchingParen(s: String, from: Int): Int {
            var e = from
            var depth = 0
            while (e < s.length) {
                val c = s[e]
                if (c == '(') depth++
                else if (c == ')') {
                    depth--
                    if (depth == 0) return e
                }
                e++
            }
            return s.length
        }
    }
}
