package orilumn.reader.engine

import orilumn.reader.engine.css.CssBundle
import orilumn.reader.engine.html.MarkupElement

/**
 * Import-built chapter structure: extract + relink (open-time), media gating.
 *
 * The cascade behind `computeStructure` consumes only author/UA sheets for the properties it
 * needs (display, white-space, background, break-inside, visibility) — reader theme/UI sheets
 * never set those — so its output is typography- and viewport-independent EXCEPT for `@media`
 * (evaluated against the viewport at parse). [hasMediaRules] detects media-affected chapters;
 * only media-free chapters are persisted (import) and relinked (open). Anything else falls back
 * to computing at open, exactly as today.
 *
 * Node identity crosses the process boundary as child-index paths from the chapter root. Both
 * sides must walk the same post-[ChapterPreprocessor] tree; every resolution is bounds-checked
 * and any failure returns false → the caller computes instead. Trust nothing.
 */
object ChapterStructurePersist {

    /**
     * Whether this chapter's CSS can behave differently per viewport: an `@media` condition or an
     * `@import` media query containing a parenthesized feature query (width/height in this engine —
     * the only viewport-evaluated kind; bare `screen`/`all`/`print` types are viewport-invariant:
     * always inline / always drop, at import and at every open alike).
     *
     * Over-approximates (comments may trip it) — a false positive only costs one runtime compute,
     * never correctness. Chapters without variant media are persisted with a canonical viewport
     * ([ImportStructures.IMPORT_VIEWPORT]) whose value is irrelevant precisely because no
     * viewport-evaluated query can occur in them.
     */
    fun hasMediaRules(cssTexts: List<String>): Boolean {
        for (t in cssTexts) {
            val low = t.lowercase()
            var i = low.indexOf("@media")
            while (i >= 0) {
                if (mediaConditionHasFeatures(low, i + "@media".length)) return true
                i = low.indexOf("@media", i + 1)
            }
            var j = low.indexOf("@import")
            while (j >= 0) {
                if (importHasVariantMedia(low, j + "@import".length)) return true
                j = low.indexOf("@import", j + 1)
            }
        }
        return false
    }

    /** True when the `@media` condition (up to its opening brace) holds a feature query. */
    private fun mediaConditionHasFeatures(low: String, from: Int): Boolean {
        var i = from
        // Skip whitespace/comments to the condition start; the condition ends at the first '{'
        // (conditions never contain braces; strings inside are over-approxed as variant — safe).
        while (i < low.length && low[i] != '{') {
            if (low.startsWith("/*", i)) {
                val end = low.indexOf("*/", i + 2)
                if (end < 0) return true
                i = end + 2
            } else {
                if (low[i] == '(') return true
                i++
            }
        }
        return false
    }

    /** True when the `@import` carries a feature-queried media condition (past its target). */
    private fun importHasVariantMedia(low: String, from: Int): Boolean {
        var i = from
        fun skipWsAndComments(): Boolean {
            while (i < low.length) {
                when {
                    low[i].isWhitespace() -> i++
                    low.startsWith("/*", i) -> {
                        val end = low.indexOf("*/", i + 2)
                        i = if (end < 0) return false else end + 2
                    }
                    else -> return true
                }
            }
            return false
        }
        if (!skipWsAndComments()) return false
        // Skip the target: url(...) or a quoted string.
        if (low.startsWith("url(", i)) {
            val end = low.indexOf(')', i + 4)
            if (end < 0) return false
            i = end + 1
        } else if (i < low.length && (low[i] == '"' || low[i] == '\'')) {
            val q = low[i]
            val end = low.indexOf(q, i + 1)
            if (end < 0) return false
            i = end + 1
        } else {
            return false // malformed: fail closed (caller falls back to computing)
        }
        if (!skipWsAndComments()) return false
        if (i >= low.length || low[i] == ';') return false // unconditional
        // Remainder is the media condition: variant only with a feature query.
        var k = i
        while (k < low.length && low[k] != ';' && low[k] != '{') {
            if (low[k] == '(') return true
            k++
        }
        return false
    }

    /** Assigns parent pointers through the whole tree (idempotent; the converter may not set them). */
    fun assignParents(root: MarkupElement) {
        for (child in root.children) {
            child.parent = root
            assignParents(child)
        }
    }

    /** Child-index path from [root] to [node], or null when [node] isn't under [root]. */
    fun nodePath(root: MarkupElement, node: MarkupElement): IntArray? {
        val rev = ArrayList<Int>()
        var cur: MarkupElement? = node
        while (cur != null && cur !== root) {
            val p = cur.parent ?: return null
            val idx = p.children.indexOf(cur)
            if (idx < 0) return null
            rev.add(idx)
            cur = p
        }
        if (cur !== root) return null
        rev.reverse()
        return rev.toIntArray()
    }

    /** Walks [path] from [root]; null on any out-of-bounds step. */
    fun resolvePath(root: MarkupElement, path: IntArray): MarkupElement? {
        var cur = root
        for (idx in path) {
            if (idx < 0 || idx >= cur.children.size) return null
            cur = cur.children[idx]
        }
        return cur
    }

    /** Extracts a persistable payload from a freshly computed [structure] (import or first touch). */
    fun extract(
        chapterIndex: Int,
        cssHash: Long,
        root: MarkupElement,
        structure: ChapterStructureCache,
    ): PersistedChapterStructure? {
        assignParents(root)
        val leafRefs = ArrayList<LeafRef>(structure.leaves.size)
        val leafIndex = HashMap<MarkupElement, Int>(structure.leaves.size * 2)
        structure.leaves.forEachIndexed { i, leaf ->
            leafIndex[leaf] = i
            leafRefs.add(refOf(root, leaf) ?: return null)
        }
        if (structure.globalCharStarts.size != structure.leaves.size) return null
        fun ownerRefs(src: Map<MarkupElement, MarkupElement>): Map<Int, LeafRef>? {
            val out = HashMap<Int, LeafRef>(src.size)
            for ((leaf, owner) in src) {
                val i = leafIndex[leaf] ?: return null
                out[i] = refOf(root, owner) ?: return null
            }
            return out
        }
        val bg = ownerRefs(structure.leafToBackgroundOwner) ?: return null
        val avoid = ownerRefs(structure.leafToBreakInsideAvoidOwner) ?: return null
        val gen = HashMap<Int, Pair<String?, String?>>(structure.genStrings.size)
        for ((leaf, pair) in structure.genStrings) {
            val i = leafIndex[leaf] ?: return null
            gen[i] = pair
        }
        return PersistedChapterStructure(
            chapterIndex = chapterIndex,
            cssHash = cssHash,
            leafRefs = leafRefs,
            charStarts = structure.globalCharStarts.copyOf(),
            bgOwners = bg,
            avoidOwners = avoid,
            genStrings = gen,
            anyFloat = structure.anyFloat,
        )
    }

    /**
     * A leaf's persistable reference: tree members by path; `flowChildren` synthetic anonymous
     * runs (detached by design — parented but absent from children) by owner path + verbatim text.
     * Anything else (detached non-text) is unpersistable → null → the caller computes instead.
     */
    private fun refOf(root: MarkupElement, node: MarkupElement): LeafRef? {
        nodePath(root, node)?.let { return LeafRef(it, null) }
        if (node.tag != "#text") return null
        val parent = node.parent ?: return null
        val parentPath = nodePath(root, parent) ?: return null
        return LeafRef(parentPath, node.text)
    }

    /** Resolves a [LeafRef] against a freshly parsed tree (synthetics recreated verbatim). */
    private fun resolveRef(root: MarkupElement, ref: LeafRef): MarkupElement? {
        val owner = resolvePath(root, ref.path) ?: return null
        val text = ref.syntheticText ?: return owner
        return MarkupElement("#text", text = text).also { it.parent = owner }
    }

    /**
     * Relinks [payload] onto a freshly parsed+preprocessed [root], filling [out]. Returns false on
     * ANY inconsistency (caller computes instead). Sets [ChapterStructureCache.boundMediaFree]
     * so `prepareLight` skips the cascade while the CSS still matches.
     */
    fun apply(
        root: MarkupElement,
        payload: PersistedChapterStructure,
        cssHash: Long,
        out: ChapterStructureCache,
    ): Boolean {
        if (payload.cssHash != cssHash) return false
        if (payload.leafRefs.size != payload.charStarts.size) return false
        assignParents(root)
        val leaves = ArrayList<MarkupElement>(payload.leafRefs.size)
        for (ref in payload.leafRefs) {
            leaves.add(resolveRef(root, ref) ?: return false)
        }
        fun ownerMap(src: Map<Int, LeafRef>): Map<MarkupElement, MarkupElement>? {
            val result = HashMap<MarkupElement, MarkupElement>(src.size)
            for ((leafIdx, ref) in src) {
                if (leafIdx < 0 || leafIdx >= leaves.size) return null
                result[leaves[leafIdx]] = resolveRef(root, ref) ?: return null
            }
            return result
        }
        val bg = ownerMap(payload.bgOwners) ?: return false
        val avoid = ownerMap(payload.avoidOwners) ?: return false
        val gen = HashMap<MarkupElement, Pair<String?, String?>>(payload.genStrings.size)
        for ((leafIdx, pair) in payload.genStrings) {
            if (leafIdx < 0 || leafIdx >= leaves.size) return false
            gen[leaves[leafIdx]] = pair
        }
        out.leaves = leaves
        out.globalCharStarts = payload.charStarts.copyOf()
        out.leafToBackgroundOwner = bg
        out.leafToBreakInsideAvoidOwner = avoid
        out.genStrings = gen
        out.anyFloat = payload.anyFloat
        out.boundCssHash = cssHash
        out.loadedMediaFree = true
        return true
    }
}
