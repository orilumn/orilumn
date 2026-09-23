package orilumn.reader.engine.css

/**
 * In-house [CssParser] (zero third-party dependency, pure JVM, unit-testable).
 *
 * A deliberately small tokenizer tuned for CSS2.1 plus the CSS modules EPUB2/EPUB3 profiles use. It is
 * not a full CSS3 parser and intentionally doesn't need to be: for a reflowable reader we only need to
 * reliably recover selector lists and declaration blocks. It handles:
 *  - C-style block comments (also inside values and between tokens),
 *  - string literals (single/double quotes, backslash escapes, newline-terminated unclosed strings)
 *    so braces/semicolons/parens inside a quoted value never confuse the scanner,
 *  - url(..) and other function parentheses (nested parentheses) inside values,
 *  - top-level at-rules (@media, @font-face, @import, @namespace, @page, @keyframes,
 *    @supports, @charset, ...) skipped with correctly nested-brace bodies,
 *  - selector-list splitting on the top-level comma (respecting pseudo-class parens),
 *  - [!important] detection on each declaration,
 *  - unbalanced-brace / malformed fail-open: bad fragments are skipped, never thrown.
 *
 * Declarations are kept as raw property/value strings - which properties EPUB honors is a
 * cascade/compute-time concern, not a parse-time filter - so nothing is filtered here.
 */
class LightCssParser : CssParser {

    override fun parse(css: String): StyleSheet = parse(css, null)

    /** P2: `@font-face` 记录、`@import` 记录、条件 `@media` 按视口内联（null 视口即历史行为）。 */
    override fun parse(css: String, viewport: CssViewport?): StyleSheet {
        val rules = ArrayList<StyleRule>()
        val faces = ArrayList<CssFontFace>()
        val imports = ArrayList<CssImport>()
        parseInto(css, viewport, rules, faces, imports)
        return StyleSheet(rules, faces, imports)
    }

    private fun parseInto(
        css: String,
        viewport: CssViewport?,
        rules: MutableList<StyleRule>,
        faces: MutableList<CssFontFace>,
        imports: MutableList<CssImport>,
    ) {
        val n = css.length
        var i = 0
        while (i < n) {
            i = skipWsAndComments(css, i)
            if (i >= n) break
            when (css[i]) {
                '@' -> i = parseAtRule(css, i, viewport, rules, faces, imports)
                '{' -> i = skipBalancedBlock(css, i + 1) // orphan open-brace: discard its block
                '}' -> i++ // stray close-brace: recover
                else -> {
                    val open = indexOfTopLevel(css, i, '{')
                    if (open < 0) break // no selector block ahead - nothing left to parse
                    val selectorText = cleanSelector(css.substring(i, open))
                    i = open + 1
                    val (decls, next) = parseDeclarations(css, i)
                    i = next
                    if (selectorText.isNotEmpty() && decls.isNotEmpty()) {
                        rules.add(StyleRule(splitSelectors(selectorText), decls))
                    }
                }
            }
        }
    }

    /** Parses a declaration block starting just after an open brace; returns (declarations, index after the close brace). */
    private fun parseDeclarations(css: String, start: Int): Pair<List<Declaration>, Int> {
        val n = css.length
        var i = skipWsAndComments(css, start)
        val decls = ArrayList<Declaration>()
        while (i < n) {
            val c = css[i]
            when (c) {
                '}' -> return decls to (i + 1)
                ';' -> i = skipWsAndComments(css, i + 1)
                '{' -> i = skipBalancedBlock(css, i + 1) // stray nested block inside a declaration block: discard
                else -> {
                    // Find the property separator colon that precedes any top-level terminator.
                    var p = i
                    var depth = 0
                    var colonAt = -1
                    while (p < n) {
                        val cp = css[p]
                        when {
                            cp == '"' || cp == '\'' -> p = skipString(css, p)
                            cp == '/' && p + 1 < n && css[p + 1] == '*' -> p = endOfComment(css, p + 2)
                            cp == '(' -> { depth++; p++ }
                            cp == ')' -> { if (depth > 0) depth--; p++ }
                            cp == ':' && depth == 0 -> { colonAt = p; break }
                            // A terminator before the colon means a malformed / empty-property fragment.
                            (cp == ';' || cp == '}' || cp == '{') && depth == 0 -> break
                            else -> p++
                        }
                    }
                    if (colonAt < 0) {
                        // No valid colon: skip this garbage fragment up to the next top-level terminator.
                        var q = i
                        var d = 0
                        while (q < n) {
                            val cq = css[q]
                            when {
                                cq == '"' || cq == '\'' -> q = skipString(css, q)
                                cq == '/' && q + 1 < n && css[q + 1] == '*' -> q = endOfComment(css, q + 2)
                                (cq == ';' || cq == '}') && d == 0 -> break
                                cq == '(' -> d++
                                cq == ')' -> if (d > 0) d--
                                else -> q++
                            }
                        }
                        i = if (q < n && css[q] == ';') skipWsAndComments(css, q + 1) else q
                        continue
                    }
                    val property = css.substring(i, colonAt).trim()
                    // Read the value up to the next top-level terminator (strings / parens handled).
                    var v = colonAt + 1
                    depth = 0
                    while (v < n) {
                        val cv = css[v]
                        if (depth == 0 && (cv == ';' || cv == '}')) break
                        when (cv) {
                            '(' -> depth++
                            ')' -> if (depth > 0) depth--
                            '"', '\'' -> v = skipString(css, v) - 1
                            '/' -> if (v + 1 < n && css[v + 1] == '*') v = endOfComment(css, v + 2) - 1
                        }
                        v++
                    }
                    val rawValue = css.substring(colonAt + 1, v).trim()
                    if (property.isNotEmpty() && rawValue.isNotEmpty()) {
                        val (value, important) = extractImportant(rawValue)
                        if (value.isNotEmpty()) decls.add(Declaration(property, value, important))
                    }
                    i = if (v < n && css[v] == ';') skipWsAndComments(css, v + 1) else v
                }
            }
        }
        return decls to i
    }

    /** Returns the index just after the closing quote (or line end / end of text). */
    private fun skipString(css: String, i: Int): Int {
        val q = css[i]
        val n = css.length
        var j = i + 1
        while (j < n) {
            when (css[j]) {
                '\\' -> j += 2 // skip escaped character
                q -> return j + 1
                '\n' -> return j + 1 // unclosed string: stop at the line break
                else -> j++
            }
        }
        return n
    }

    private fun skipWsAndComments(css: String, start: Int): Int {
        val n = css.length
        var j = start
        while (j < n) {
            val c = css[j]
            when {
                c.isWhitespace() -> j++
                c == '/' && j + 1 < n && css[j + 1] == '*' -> j = endOfComment(css, j + 2)
                else -> return j
            }
        }
        return j
    }

    /** Returns the index just after the closing block-comment marker (or end of text when never closed). */
    private fun endOfComment(css: String, start: Int): Int {
        val n = css.length
        var j = start
        while (j + 1 < n) {
            if (css[j] == '*' && css[j + 1] == '/') return j + 2
            j++
        }
        return n
    }

    /** Index of [target] at paren-depth 0 (ignoring strings/comments); -1 when absent. */
    private fun indexOfTopLevel(css: String, start: Int, target: Char): Int {
        val n = css.length
        var j = start
        var depth = 0
        while (j < n) {
            when (css[j]) {
                '"', '\'' -> j = skipString(css, j)
                '(' -> { depth++; j++ }
                ')' -> { if (depth > 0) depth--; j++ }
                '/' -> if (j + 1 < n && css[j + 1] == '*') j = endOfComment(css, j + 2) else j++
                else -> {
                    if (depth == 0 && css[j] == target) return j
                    j++
                }
            }
        }
        return -1
    }

    /** Dispatches an at-rule: P2 records `@font-face`/`@import`, inlines matching `@media`. */
    private fun parseAtRule(
        css: String,
        start: Int,
        viewport: CssViewport?,
        rules: MutableList<StyleRule>,
        faces: MutableList<CssFontFace>,
        imports: MutableList<CssImport>,
    ): Int {
        val n = css.length
        var j = start + 1
        while (j < n && (css[j].isLetterOrDigit() || css[j] == '-' || css[j] == '_')) j++
        val keyword = css.substring(start + 1, j).lowercase()
        return when (keyword) {
            "font-face" -> parseCssFontFace(css, j, faces)
            "import" -> parseImport(css, j, imports)
            "media" -> parseMedia(css, j, viewport, rules, faces, imports)
            else -> skipAtRule(css, start)
        }
    }

    /** `@font-face { ... }`: declaration block → [CssFontFace]（无有效族名/src 即丢弃）。 */
    private fun parseCssFontFace(css: String, afterKeyword: Int, faces: MutableList<CssFontFace>): Int {
        val open = indexOfTopLevel(css, afterKeyword, '{')
        if (open < 0) return skipAtRule(css, afterKeyword)
        val (decls, next) = parseDeclarations(css, open + 1)
        var family: String? = null
        var weight: Int? = null
        var italic: Boolean? = null
        var sources: List<CssFontFaceSource> = emptyList()
        for (d in decls) {
            when (d.property.lowercase()) {
                "font-family" -> family = unquote(d.value.substringBefore(',').trim()).takeIf { it.isNotEmpty() }
                "font-weight" -> weight = parseFaceWeight(d.value)
                "font-style" -> italic = parseFaceStyle(d.value)
                "src" -> sources = parseFaceSrc(d.value)
            }
        }
        if (family != null && sources.isNotEmpty()) faces.add(CssFontFace(family, weight, italic, sources))
        return next
    }

    /** `@import url|"..."" [media];`：记录 url＋媒体条件原文。 */
    private fun parseImport(css: String, afterKeyword: Int, imports: MutableList<CssImport>): Int {
        val semi = indexOfTopLevel(css, afterKeyword, ';')
        val end = if (semi < 0) css.length else semi
        val stmt = css.substring(afterKeyword, end)
        var url: String? = null
        var media = ""
        val urlIdx = stmt.indexOf("url(", ignoreCase = true)
        if (urlIdx >= 0) {
            // 配对右括号（引号感知），媒体条件取其后原文。
            var j = urlIdx + 4
            var depth = 1
            while (j < stmt.length && depth > 0) {
                val c = stmt[j]
                if (c == '"' || c == '\'') {
                    var k = j + 1
                    while (k < stmt.length && stmt[k] != c) {
                        if (stmt[k] == '\\') k++
                        k++
                    }
                    j = k + 1
                    continue
                }
                if (c == '(') depth++
                if (c == ')') depth--
                j++
            }
            url = unquote(stmt.substring(urlIdx + 4, (j - 1).coerceAtLeast(urlIdx + 4)).trim())
            media = stmt.substring(j.coerceAtMost(stmt.length)).trim()
        } else {
            val t = stmt.trim()
            if (t.startsWith('"') || t.startsWith('\'')) {
                val e = t.indexOf(t[0], 1)
                if (e > 1) {
                    url = t.substring(1, e)
                    media = t.substring(e + 1).trim()
                }
            }
        }
        if (!url.isNullOrBlank()) imports.add(CssImport(url.trim(), media))
        return if (semi < 0) css.length else semi + 1
    }

    /** `@media <cond> { ... }`：视口命中即递归内联，否则丢弃（null 视口即历史行为：丢弃）。 */
    private fun parseMedia(
        css: String,
        afterKeyword: Int,
        viewport: CssViewport?,
        rules: MutableList<StyleRule>,
        faces: MutableList<CssFontFace>,
        imports: MutableList<CssImport>,
    ): Int {
        val open = indexOfTopLevel(css, afterKeyword, '{')
        if (open < 0) return skipAtRule(css, afterKeyword)
        val cond = css.substring(afterKeyword, open).trim()
        val close = endOfBalancedBlock(css, open)
        if (viewport != null && matchesMedia(cond, viewport)) {
            parseInto(css.substring(open + 1, close), viewport, rules, faces, imports)
        }
        return close + 1
    }

    /** Index of the `}` matching the `{` at [open] (block content end); length when unbalanced. */
    private fun endOfBalancedBlock(css: String, open: Int): Int {
        val n = css.length
        var j = open + 1
        var depth = 1
        while (j < n) {
            when (css[j]) {
                '"', '\'' -> j = skipString(css, j)
                '/' -> if (j + 1 < n && css[j + 1] == '*') j = endOfComment(css, j + 2) else j++
                '{' -> { depth++; j++ }
                '}' -> { depth--; j++; if (depth == 0) return j - 1 }
                else -> j++
            }
        }
        return n
    }

    private fun unquote(s: String): String {
        val t = s.trim()
        return if (t.length >= 2 && ((t.startsWith('"') && t.endsWith('"')) || (t.startsWith('\'') && t.endsWith('\'')))) {
            t.substring(1, t.length - 1)
        } else t
    }

    private fun parseFaceWeight(raw: String): Int? {
        val first = raw.trim().split(Regex("\\s+")).firstOrNull()?.lowercase() ?: return null
        return when (first) {
            "normal" -> 400
            "bold" -> 700
            else -> first.toIntOrNull()?.takeIf { it in 1..1000 }
        }
    }

    private fun parseFaceStyle(raw: String): Boolean? {
        return when (raw.trim().lowercase().substringBefore(' ')) {
            "italic", "oblique" -> true
            "normal" -> false
            else -> null
        }
    }

    /** `src` 逗号列表 → url 源（`local(...)` 跳过，无 url 即空）。 */
    private fun parseFaceSrc(raw: String): List<CssFontFaceSource> {
        val out = ArrayList<CssFontFaceSource>()
        for (item in splitTopLevelCommas(raw)) {
            val url = extractUrlFn(item) ?: continue
            if (url.isBlank()) continue
            val format = extractFormatFn(item).lowercase()
            out.add(CssFontFaceSource(url.trim(), format))
        }
        return out
    }

    /** `url(...)` 取链（引号/裸链均可；无即 null）。 */
    private fun extractUrlFn(item: String): String? {
        val idx = item.indexOf("url(", ignoreCase = true)
        if (idx < 0) return null
        var j = idx + 4
        while (j < item.length && item[j].isWhitespace()) j++
        return if (j < item.length && (item[j] == '"' || item[j] == '\'')) {
            val q = item[j]
            val end = item.indexOf(q, j + 1)
            if (end < 0) null else item.substring(j + 1, end)
        } else {
            var e = j
            while (e < item.length && item[e] != ')' && !item[e].isWhitespace()) e++
            item.substring(j, e).takeIf { it.isNotEmpty() }
        }
    }

    /** `format("...")` 提示（无即空串）。 */
    private fun extractFormatFn(item: String): String {
        val idx = item.indexOf("format(", ignoreCase = true)
        if (idx < 0) return ""
        var j = idx + 7
        while (j < item.length && item[j].isWhitespace()) j++
        if (j < item.length && (item[j] == '"' || item[j] == '\'')) {
            val q = item[j]
            val end = item.indexOf(q, j + 1)
            if (end >= 0) return item.substring(j + 1, end)
        }
        return ""
    }

    /** 顶层逗号切分（括号/引号/注释感知）。 */
    private fun splitTopLevelCommas(s: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        var start = 0
        var j = 0
        while (j < s.length) {
            when (s[j]) {
                '"', '\'' -> j = skipString(s, j)
                '/' -> if (j + 1 < s.length && s[j + 1] == '*') j = endOfComment(s, j + 2) else j++
                '(' -> { depth++; j++ }
                ')' -> { if (depth > 0) depth--; j++ }
                ',' -> if (depth == 0) {
                    out.add(s.substring(start, j))
                    start = j + 1
                    j++
                } else j++
                else -> j++
            }
        }
        out.add(s.substring(start))
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** P2 `@media` 求值：逗号＝或；组内 `and` 连接；仅版心宽/高查询，其余恒 false（安全丢弃）。 */
    fun matchesMedia(condition: String, viewport: CssViewport): Boolean {
        val cond = condition.trim()
        if (cond.isEmpty()) return true
        return splitTopLevelCommas(cond).any { matchesMediaGroup(it, viewport) }
    }

    private fun matchesMediaGroup(group: String, viewport: CssViewport): Boolean {
        val parts = group.split(Regex("(?i)\\band\\b")).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return true
        for (p in parts) {
            if (p.startsWith('(')) {
                if (!matchesMediaFeature(p, viewport)) return false
            } else {
                var t = p.lowercase()
                if (t.startsWith("only ")) t = t.removePrefix("only ").trim()
                if (t.startsWith("not ")) {
                    val inner = t.removePrefix("not ").trim()
                    // not print/speech → 本机成立；not screen/all → 不成立；not 未知 → 不成立。
                    if (inner == "print" || inner == "speech") continue
                    return false
                }
                // screen/all/空 → 成立；print/speech/未知 → 丢弃。
                if (t != "" && t != "all" && t != "screen") return false
            }
        }
        return true
    }

    private fun matchesMediaFeature(feature: String, viewport: CssViewport): Boolean {
        val inner = feature.trim().removePrefix("(").removeSuffix(")").trim()
        val colon = inner.indexOf(':')
        if (colon < 0) return false // (color) 等无值查询：无法求值 → 丢弃
        val name = inner.substring(0, colon).trim().lowercase()
        // 高度未知（排版 parse 点只有版心宽）：高度查询恒不命中（重轻两路同值，一致优先）。
        if (name.contains("height") && viewport.heightPx < 0) return false
        val px = parseMediaPx(inner.substring(colon + 1).trim()) ?: return false
        return when (name) {
            "min-width" -> viewport.widthPx >= px
            "max-width" -> viewport.widthPx <= px
            "width" -> viewport.widthPx == px
            "min-height" -> viewport.heightPx >= px
            "max-height" -> viewport.heightPx <= px
            "height" -> viewport.heightPx == px
            else -> false
        }
    }

    /** 媒体查询长度：px 或纯数字；em/rem/% 等无法求值 → null。 */
    private fun parseMediaPx(raw: String): Int? {
        val t = raw.trim().lowercase()
        if (t.endsWith("px")) return t.removeSuffix("px").trim().toDoubleOrNull()?.toInt()
        if (t.toDoubleOrNull() != null) return t.toDouble().toInt()
        return null
    }
    private fun skipAtRule(css: String, start: Int): Int {
        val n = css.length
        var j = start
        var braceDepth = 0
        while (j < n) {
            when (css[j]) {
                '"', '\'' -> j = skipString(css, j)
                '/' -> if (j + 1 < n && css[j + 1] == '*') j = endOfComment(css, j + 2) else j++
                '{' -> { braceDepth++; j++ }
                '}' -> if (braceDepth > 0) { braceDepth--; j++; if (braceDepth == 0) return j } else return j + 1
                ';' -> if (braceDepth == 0) return j + 1 else j++
                else -> j++
            }
        }
        return n
    }

    /** Skips a balanced brace-block, returning the index just after its matching close brace. */
    private fun skipBalancedBlock(css: String, startAfterOpen: Int): Int {
        val n = css.length
        var j = startAfterOpen
        var depth = 1
        while (j < n) {
            when (css[j]) {
                '"', '\'' -> j = skipString(css, j)
                '/' -> if (j + 1 < n && css[j + 1] == '*') j = endOfComment(css, j + 2) else j++
                '{' -> { depth++; j++ }
                '}' -> { depth--; j++; if (depth == 0) return j }
                else -> j++
            }
        }
        return n
    }

    /** Splits a selector list on top-level commas (respecting parens/brackets and strings). */
    private fun splitSelectors(text: String): List<String> {
        val out = ArrayList<String>()
        var start = 0
        var depth = 0
        var j = 0
        val n = text.length
        while (j < n) {
            when (text[j]) {
                '(', '[' -> depth++
                ')', ']' -> if (depth > 0) depth--
                '"', '\'' -> j = skipString(text, j) - 1
                ',' -> if (depth == 0) { out.add(text.substring(start, j).trim()); start = j + 1 }
            }
            j++
        }
        out.add(text.substring(start).trim())
        return out.filter { it.isNotEmpty() }
    }

    /** Strips C-style block comments from a selector text (kept as a space to keep token separation). */
    private fun cleanSelector(s: String): String = s.replace(COMMENT_REGEX, " ")

    /** Extracts a trailing [!important] (any casing), returning the cleaned value and the flag. */
    private fun extractImportant(raw: String): Pair<String, Boolean> {
        val t = raw.trimEnd()
        val bang = t.lastIndexOf('!')
        if (bang >= 0) {
            val after = t.substring(bang + 1).trim()
            if (after.equals("important", ignoreCase = true)) {
                return t.substring(0, bang).trim() to true
            }
        }
        return raw.trim() to false
    }

    private companion object {
        /** Matches a C-style block comment token inside selector text (no real descendant of this class). */
        val COMMENT_REGEX = Regex("/\\*.*?\\*/")
    }
}