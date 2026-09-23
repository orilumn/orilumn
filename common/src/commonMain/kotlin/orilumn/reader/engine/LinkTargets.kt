package orilumn.reader.engine

/**
 * P4-c1: in-book link target resolution (pure JVM).
 *
 * Resolves a raw `<a href>` value to a ([chapterIndex], [fragment]) pair using the same
 * path semantics the EPUB parser uses ([normalizePath]/[joinPath]/`..` collapse), so link
 * navigation and TOC jumps agree on chapter identity. Fragment (the `#id` part) is
 * case-sensitive and never lowercased — only the *path* part is normalized.
 *
 * Out of scope (P4-c2, platform wiring): tap hit-testing and the actual navigation call.
 */
data class LinkTarget(val chapterIndex: Int, val fragment: String?)

object LinkTargets {

    /**
     * Resolves [href] against the current chapter.
     *
     * @param href raw `href` attribute value.
     * @param currentChapterIndex spine index of the chapter containing the link.
     * @param currentSpineHref spine href of that chapter (e.g. `OEBPS/ch1.xhtml`).
     * @param indexByNormalizedHref normalized spine-href → chapter index (same normalization
     *   as [normalizePath]; build once per book).
     * @return the target, or null when [href] is blank, external (any `scheme:` URL), or points
     *   at an unknown resource.
     */
    fun resolveLinkTarget(
        href: String,
        currentChapterIndex: Int,
        currentSpineHref: String,
        indexByNormalizedHref: Map<String, Int>,
    ): LinkTarget? {
        val h = href.trim()
        if (h.isEmpty()) return null
        if (hasUrlScheme(h)) return null
        val (pathPart, fragment) = splitFragment(h)
        if (pathPart.isEmpty()) return LinkTarget(currentChapterIndex, fragment)
        val resolved = normalizePath(resolveRelative(currentSpineHref, pathPart))
        val idx = indexByNormalizedHref[resolved] ?: return null
        return LinkTarget(idx, fragment)
    }

    /** Split `a#b` → (a, b); fragment is null when there is no `#` or it is empty. */
    fun splitFragment(href: String): Pair<String, String?> {
        val idx = href.indexOf('#')
        return if (idx >= 0) href.substring(0, idx) to href.substring(idx + 1).ifEmpty { null }
        else href to null
    }

    /** Unified path normalization (mirrors `EpubParser`/`EpubResourceReader`): `\` → `/`,
     *  strip leading/trailing `/`, lowercase. Applied to the *path* part only. */
    fun normalizePath(p: String): String = p.replace('\\', '/').trim('/').lowercase()

    /** Resolves a possibly-relative [relative] against the directory of [basePath], collapsing
     *  `.`/`..` segments (mirrors `EpubResourceReader.resolveRelative`; keeps original casing —
     *  callers normalize before map lookup). */
    fun resolveRelative(basePath: String, relative: String): String {
        if (relative.startsWith("/")) return collapsePath(relative)
        val baseDir = basePath.substringBeforeLast('/', "")
        val joined = if (baseDir.isEmpty()) relative else "$baseDir/$relative"
        return collapsePath(joined)
    }

    /** Collapses `.` and `..` segments in a `/`-separated path. */
    fun collapsePath(p: String): String {
        val parts = ArrayDeque<String>()
        for (seg in p.replace('\\', '/').split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeLast()
                else -> parts.add(seg)
            }
        }
        return parts.joinToString("/")
    }

    /** True when [href] carries a URL scheme (`xxx:` before any `/`, `?` or `#`) — i.e. it is an
     *  external link (http/https/mailto/data/…) rather than an in-book path. */
    private fun hasUrlScheme(href: String): Boolean {
        var i = 0
        if (i < href.length && href[i].isLetter()) {
            i++
            while (i < href.length && (href[i].isLetterOrDigit() || href[i] == '+' || href[i] == '-' || href[i] == '.')) i++
            if (i < href.length && href[i] == ':') return true
        }
        return false
    }
}
