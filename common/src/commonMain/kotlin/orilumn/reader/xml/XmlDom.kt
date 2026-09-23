package orilumn.reader.xml

/**
 * Minimal XML DOM subset sufficient for EPUB parsing (container.xml / OPF / NCX / nav).
 *
 * External entities are never resolved; DTD and external declarations are silently skipped.
 */
class XmlDocument(val documentElement: XmlElement) {
    /** Convenience: search the entire document (starting from root). */
    fun getElementsByTagNameNS(ns: String, local: String): List<XmlElement> =
        documentElement.getElementsByTagNameNS(ns, local)
}

sealed class XmlNode {
    /** Node name (tag name for elements, `#text` for text nodes). */
    open val nodeName: String get() = "#text"

    /** The next sibling node in document order, or null. */
    open var nextSibling: XmlNode? = null
        internal set

    /** First child node, or null. */
    open val firstChild: XmlNode? get() = null

    /** Concatenated text content of all descendant text nodes. */
    open val textContent: String get() = ""
}

class XmlElement(
    override val nodeName: String,
    val attributes: Map<String, String>,
    private val _children: List<XmlNode>,
) : XmlNode() {
    /** Direct child nodes (elements + text). */
    override val firstChild: XmlNode? get() = _children.firstOrNull()

    val children: List<XmlNode> get() = _children

    /** Local name without namespace prefix. */
    val localName: String get() = nodeName.substringAfter(':')

    /**
     * Returns the value of the attribute with the given [name], or an empty string if absent.
     * Matches the w3c DOM `getAttribute` convention (empty string, not null, for missing).
     */
    fun getAttribute(name: String): String = attributes[name] ?: ""

    override val textContent: String
        get() {
            val sb = StringBuilder()
            appendTextContent(this, sb)
            return sb.toString()
        }

    /**
     * Returns all descendant elements (NOT this element itself) whose [localName] matches [local]
     * and whose namespace prefix matches [ns] — unless [ns] is `"*"` (wildcard), in which case
     * any prefix is accepted.  Results are in document order.
     */
    fun getElementsByTagNameNS(ns: String, local: String): List<XmlElement> {
        val result = mutableListOf<XmlElement>()
        collectDescendants(this, ns, local, result)
        return result
    }

    /** Returns the first descendant element whose [localName] matches [tag], or null. */
    fun firstDescendant(tag: String): XmlElement? {
        for (c in _children) {
            if (c is XmlElement) {
                if (c.localName == tag) return c
                c.firstDescendant(tag)?.let { return it }
            }
        }
        return null
    }

    /** Returns direct child elements whose [localName] matches [tag]. */
    fun elementChildren(tag: String): List<XmlElement> =
        _children.filterIsInstance<XmlElement>().filter { it.localName == tag }

    override fun toString(): String = "<$nodeName>"
}

class XmlTextNode(val data: String) : XmlNode() {
    override val textContent: String get() = data
}

// ---------- helpers ----------

private fun appendTextContent(node: XmlNode, sb: StringBuilder) {
    when (node) {
        is XmlTextNode -> sb.append(node.data)
        is XmlElement -> {
            for (c in node.children) appendTextContent(c, sb)
        }
        else -> Unit
    }
}

private fun collectDescendants(
    element: XmlElement,
    ns: String,
    local: String,
    out: MutableList<XmlElement>,
) {
    for (c in element.children) {
        if (c is XmlElement) {
            if (c.localName == local && (ns == "*" || ns == c.nodeName.substringBefore(':', c.nodeName))) {
                out.add(c)
            }
            collectDescendants(c, ns, local, out)
        }
    }
}
