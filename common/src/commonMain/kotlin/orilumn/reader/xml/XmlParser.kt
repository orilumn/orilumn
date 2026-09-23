package orilumn.reader.xml

/**
 * Minimal XML parser for EPUB document structures.
 *
 * Produces an [XmlDocument] from well-formed (or near-well-formed) XML.
 * This parser:
 * - Resolves standard XML entities (`&amp;`, `&lt;`, `&gt;`, `&apos;`, `&quot;`) and numeric character references (`&#NNN;`, `&#xHH;`).
 * - Strips XML declarations (`<?xml ...?>`), processing instructions (`<?target ...?>`), comments (`<!-- ... -->`), and CDATA sections (`<![CDATA[...]]>`).
 * - Silently skips `<!DOCTYPE ...>` declarations without fetching external DTDs.
 * - Does NOT resolve external or internal entity references (XXE-safe).
 */
class XmlParser {

    fun parse(xml: String): XmlDocument {
        val doc = XmlParseState(xml)
        doc.skipPrologAndWhitespace()
        val root = doc.parseElement() ?: throw XmlFormatException("XML document contains no root element")
        return XmlDocument(root)
    }
}

class XmlFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

// ------------------------------------------------------------------
//  Internal stateful parser
// ------------------------------------------------------------------

private class XmlParseState(private val input: String) {
    private var pos = 0

    private fun peek(): Char = if (pos < input.length) input[pos] else '\u0000'
    private fun advance() { pos++ }
    private fun atEnd(): Boolean = pos >= input.length

    private fun expect(ch: Char) {
        if (peek() != ch) throw XmlFormatException("Expected '$ch' at position $pos, found '${peek()}'")
        advance()
    }

    fun skipWhitespace() {
        while (!atEnd() && isXmlSpace(peek())) advance()
    }

    /** Skip BOM + declarations / PIs before the root element. */
    fun skipPrologAndWhitespace() {
        if (!atEnd() && input[pos] == '\uFEFF') advance()
        skipWhitespace()
        while (!atEnd()) {
            if (peek() == '<') {
                when {
                    looksAt(pos, "<?") -> skipProcessingInstruction()
                    looksAt(pos, "<!--") -> skipComment()
                    looksAt(pos, "<!DOCTYPE") || looksAt(pos, "<!doctype") -> skipDoctype()
                    else -> break // start of root element
                }
            } else {
                // Stray text before root — skip silently
                advance()
            }
            skipWhitespace()
        }
    }

    /** Parse an element.  Current position must be at `<`.  Returns null if not an element. */
    fun parseElement(): XmlElement? {
        if (peek() != '<') return null
        advance() // skip '<'
        if (peek() == '/') throw XmlFormatException("Unexpected closing tag at position $pos")

        // Element name
        val name = readName()
        if (name.isEmpty()) throw XmlFormatException("Empty element name at position $pos")

        // Attributes
        val attributes = mutableMapOf<String, String>()
        skipWhitespace()
        while (!atEnd() && peek() != '>' && peek() != '/') {
            val attrName = readName()
            if (attrName.isEmpty()) throw XmlFormatException("Empty attribute name at position $pos")
            skipWhitespace()
            expect('=')
            skipWhitespace()
            val attrValue = readQuotedValue()
            attributes[attrName] = attrValue
            skipWhitespace()
        }

        val selfClosing = peek() == '/'
        if (selfClosing) advance()
        expect('>')

        if (selfClosing) {
            return XmlElement(name, attributes, emptyList())
        }

        // Parse children
        val children = mutableListOf<XmlNode>()
        var closed = false
        while (!atEnd()) {
            if (peek() == '<') {
                if (looksAt(pos, "</")) {
                    // Closing tag
                    advance() // '<'
                    advance() // '/'
                    val closingName = readName()
                    if (closingName != name) {
                        throw XmlFormatException("Mismatched closing tag: expected '</$name>', found '</$closingName>' at position $pos")
                    }
                    skipWhitespace()
                    expect('>')
                    closed = true
                    break
                } else if (looksAt(pos, "<!--")) {
                    skipComment()
                } else if (looksAt(pos, "<![CDATA[")) {
                    children.add(XmlTextNode(readCData()))
                } else if (looksAt(pos, "<!DOCTYPE") || looksAt(pos, "<!doctype")) {
                    skipDoctype()
                } else if (looksAt(pos, "<?")) {
                    skipProcessingInstruction()
                } else {
                    // Child element
                    val child = parseElement()
                    if (child != null) children.add(child)
                }
            } else if (peek() == '&') {
                // Entity reference
                children.add(XmlTextNode(readEntity()))
            } else {
                // Text node
                children.add(XmlTextNode(readText()))
            }
        }
        if (!closed) {
            throw XmlFormatException("Unclosed element '<$name>' at position $pos")
        }

        // Link nextSibling for all children
        for (i in 0 until children.size - 1) {
            children[i].nextSibling = children[i + 1]
        }

        return XmlElement(name, attributes, children)
    }

    // ---- Helpers ----

    private fun readName(): String {
        val start = pos
        while (!atEnd() && isNameChar(peek())) advance()
        return input.substring(start, pos)
    }

    private fun readQuotedValue(): String {
        val quote = peek()
        if (quote != '"' && quote != '\'') {
            throw xmlFormatExceptionAt("Expected quoted attribute value, found '${peek()}'")
        }
        advance()
        val sb = StringBuilder()
        while (!atEnd() && peek() != quote) {
            if (peek() == '&') {
                sb.append(readEntity())
            } else {
                sb.append(peek())
                advance()
            }
        }
        if (!atEnd()) advance() // closing quote
        return sb.toString()
    }

    private fun readEntity(): String {
        expect('&')
        val start = pos
        while (!atEnd() && peek() != ';') advance()
        val entityName = input.substring(start, pos)
        if (!atEnd()) advance() // skip ';'
        return decodeEntity(entityName)
    }

    private fun decodeEntity(name: String): String {
        return when (name) {
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "apos" -> "'"
            "quot" -> "\""
            "nbsp" -> "\u00A0"
            else -> {
                if (name.startsWith("#x") || name.startsWith("#X")) {
                    val hex = name.substring(2)
                    val cp = hex.toIntOrNull(16) ?: return "&$name;"
                    codePointToString(cp)
                } else if (name.startsWith("#")) {
                    val dec = name.substring(1)
                    val cp = dec.toIntOrNull() ?: return "&$name;"
                    codePointToString(cp)
                } else {
                    "&$name;" // Unknown entity — leave as-is (lenient)
                }
            }
        }
    }

    private fun codePointToString(cp: Int): String {
        return if (cp in 0xD800..0xDFFF || cp > 0x10FFFF) {
            "&#$cp;" // Invalid code point — leave as-is
        } else if (cp > 0xFFFF) {
            val high = 0xD800 + ((cp - 0x10000) shr 10)
            val low = 0xDC00 + ((cp - 0x10000) and 0x3FF)
            charArrayOf(high.toChar(), low.toChar()).concatToString()
        } else {
            cp.toChar().toString()
        }
    }

    private fun readText(): String {
        val start = pos
        while (!atEnd() && peek() != '<' && peek() != '&') advance()
        return input.substring(start, pos)
    }

    private fun skipComment() {
        // skip "<!--"
        for (c in "<!--") advance()
        while (!atEnd()) {
            if (looksAt(pos, "-->")) {
                for (c in "-->") advance()
                return
            }
            advance()
        }
    }

    private fun readCData(): String {
        for (c in "<![CDATA[") advance()
        val start = pos
        while (!atEnd()) {
            if (looksAt(pos, "]]>")) {
                val result = input.substring(start, pos)
                for (c in "]]>") advance()
                return result
            }
            advance()
        }
        return input.substring(start, pos)
    }

    private fun skipProcessingInstruction() {
        advance() // '<'
        advance() // '?'
        while (!atEnd()) {
            if (looksAt(pos, "?>")) {
                advance(); advance()
                return
            }
            advance()
        }
    }

    private fun skipDoctype() {
        // skip "<!DOCTYPE"
        while (!atEnd() && peek() != '>' && peek() != '[') advance()
        // Handle internal subset [...]
        var depth = 0
        while (!atEnd()) {
            when (peek()) {
                '[' -> { depth++; advance() }
                ']' -> { if (depth > 0) depth--; advance() }
                '>' -> { if (depth == 0) { advance(); return } else advance() }
                '"' -> { advance(); while (!atEnd() && peek() != '"') advance(); if (!atEnd()) advance() }
                '\'' -> { advance(); while (!atEnd() && peek() != '\'') advance(); if (!atEnd()) advance() }
                else -> advance()
            }
        }
    }

    private fun looksAt(offset: Int, s: String): Boolean {
        if (offset + s.length > input.length) return false
        for (i in s.indices) if (input[offset + i] != s[i]) return false
        return true
    }

    private fun xmlFormatExceptionAt(msg: String): XmlFormatException =
        XmlFormatException("$msg at position $pos")

    private fun isXmlSpace(ch: Char): Boolean =
        ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r'

    private fun isNameChar(ch: Char): Boolean =
        ch.isLetterOrDigit() || ch in "-_:."
}
