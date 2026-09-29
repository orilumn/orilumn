package orilumn.reader.engine

import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.css.CssBundle
import orilumn.reader.engine.html.ChapterPreprocessor
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.text.TypographicProfile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Import-built structure: extract→relink equivalence, profile-independence (the soundness
 * assumption behind persisting), media gating, fail-closed relink.
 */
class PersistedStructureRelinkTest {

    private val layouter = BoxChapterLayouter()
    private val converter = HtmlTreeConverter()

    private fun chapterHtml(): String = """
        <!DOCTYPE html><html><head><style>p{color:#111;} blockquote{background:#eee;} h2{break-inside:avoid;}</style></head><body>
        <h2>第五十七章 三分</h2>
        <p>史老师很淡定地笑了笑。<span>旁白</span>继续正文。</p>
        <blockquote><p>引用块。</p></blockquote>
        <p>第二段正文内容，稍微长一点以便分行。</p>
        </body></html>
    """.trimIndent()

    private fun profileOf(bodyPx: Float, fontBody: String, original: Boolean): TypographicProfile =
        TypographicProfile.build(
            ReaderSettings.DEFAULT.copy(fontBody = fontBody, useOriginalStyle = original),
            2f,
        ).copy(bodyPx = bodyPx)

    private object EmptyReader : orilumn.reader.data.epub.EpubResourceReader {
        override fun entries(): Sequence<String> = emptySequence()
        override fun readText(path: String): String? = null
        override fun readBytes(path: String): ByteArray? = null
    }

    private fun treeAndBundle(): Pair<MarkupElement, CssBundle> {
        val parsed = converter.convertWithStyles(chapterHtml()) ?: error("parse failed")
        // This chapter has embedded styles only — the reader is never consulted for linked sheets.
        val bundle = ImportStructures.collectChapterCssTexts(EmptyReader, "ch.html", parsed, null)
        return parsed.tree to bundle
    }

    private fun payloadOf(profile: TypographicProfile): PersistedChapterStructure {
        val (tree0, bundle) = treeAndBundle()
        val sheets = bundle.cssTexts.map { orilumn.reader.engine.css.LightCssParser().parse(it, null) }
        val tree = ChapterPreprocessor.preprocess(tree0, sheets)
        val cache = ChapterStructureCache()
        layouter.prepareLight(tree, bundle, profile, 1600, cache, 2400)
        val cssHash = ChapterStructureCodec.cssHashOf(bundle.cssTexts)
        return ChapterStructurePersist.extract(7, cssHash, tree, cache) ?: error("extract failed")
    }

    @Test
    fun `same chapter under different profiles yields identical payload bytes`() {
        // The persistence soundness lock: body size/font/theme/original must not leak into structure.
        val a = payloadOf(profileOf(40f, "屏显臻宋", true))
        val b = payloadOf(profileOf(60f, "sans-serif", true))
        val c = payloadOf(profileOf(40f, "屏显臻宋", false))
        assertEquals(ChapterStructureCodec.encode(a).toList(), ChapterStructureCodec.encode(b).toList())
        assertEquals(ChapterStructureCodec.encode(a).toList(), ChapterStructureCodec.encode(c).toList())
    }

    @Test
    fun `relink reproduces leaves starts owners and gen strings`() {
        val profile = profileOf(46f, "屏显臻宋", true)
        val (tree0, bundle) = treeAndBundle()
        val sheets = bundle.cssTexts.map { orilumn.reader.engine.css.LightCssParser().parse(it, null) }
        val cssHash = ChapterStructureCodec.cssHashOf(bundle.cssTexts)
        // Reference: compute directly.
        val refTree = ChapterPreprocessor.preprocess(tree0, sheets)
        val refCache = ChapterStructureCache()
        layouter.prepareLight(refTree, bundle, profile, 1600, refCache, 2400)
        val payload = ChapterStructurePersist.extract(7, cssHash, refTree, refCache) ?: error("extract")
        // Relink onto a freshly parsed tree (simulates open-time re-parse).
        val (tree02, _) = treeAndBundle()
        val freshTree = ChapterPreprocessor.preprocess(tree02, sheets)
        val relinked = ChapterStructureCache()
        assertTrue(ChapterStructurePersist.apply(freshTree, payload, cssHash, relinked))
        assertEquals(refCache.leaves.size, relinked.leaves.size)
        assertArrayEquals(refCache.globalCharStarts, relinked.globalCharStarts)
        // Same node identities by path (text content equal at each leaf).
        refCache.leaves.forEachIndexed { i, leaf ->
            assertEquals(leaf.text, relinked.leaves[i].text)
            assertEquals(leaf.tag, relinked.leaves[i].tag)
        }
        assertEquals(refCache.leafToBackgroundOwner.size, relinked.leafToBackgroundOwner.size)
        assertEquals(refCache.leafToBreakInsideAvoidOwner.size, relinked.leafToBreakInsideAvoidOwner.size)
        // Owner identity by path as well.
        for ((leaf, owner) in refCache.leafToBackgroundOwner) {
            val i = refCache.leaves.indexOf(leaf)
            val relOwner = relinked.leafToBackgroundOwner[relinked.leaves[i]]
            assertNotNull(relOwner)
            assertEquals(owner.text, relOwner!!.text)
        }
    }

    @Test
    fun `relink fails closed on css change and bad paths`() {
        val (tree0, bundle) = treeAndBundle()
        val sheets = bundle.cssTexts.map { orilumn.reader.engine.css.LightCssParser().parse(it, null) }
        val tree = ChapterPreprocessor.preprocess(tree0, sheets)
        val cache = ChapterStructureCache()
        val profile = profileOf(46f, "屏显臻宋", true)
        layouter.prepareLight(tree, bundle, profile, 1600, cache, 2400)
        val cssHash = ChapterStructureCodec.cssHashOf(bundle.cssTexts)
        val payload = ChapterStructurePersist.extract(7, cssHash, tree, cache) ?: error("extract")
        val out = ChapterStructureCache()
        assertFalse(ChapterStructurePersist.apply(tree, payload, cssHash + 1, out)) // css changed
        val tampered = payload.copy(leafPaths = payload.leafPaths + intArrayOf(9999))
        // Count mismatch vs starts → reject.
        assertFalse(ChapterStructurePersist.apply(tree, tampered, cssHash, ChapterStructureCache()))
    }

    @Test
    fun `hasMediaRules detects media and conditional import`() {
        assertFalse(ChapterStructurePersist.hasMediaRules(listOf("p{color:red;}")))
        assertFalse(ChapterStructurePersist.hasMediaRules(listOf("@import \"a.css\";")))
        assertFalse(ChapterStructurePersist.hasMediaRules(listOf("@charset \"utf-8\";p{}")))
        assertTrue(ChapterStructurePersist.hasMediaRules(listOf("@media (max-width:600px){p{}}")))
        assertTrue(ChapterStructurePersist.hasMediaRules(listOf("@MEDIA screen{p{}}")))
        assertTrue(ChapterStructurePersist.hasMediaRules(listOf("@import \"a.css\" screen;")))
        assertTrue(ChapterStructurePersist.hasMediaRules(listOf("@import url(a.css) (max-width:100px);")))
        assertTrue(ChapterStructurePersist.hasMediaRules(listOf("/* @media in comment */p{}")))
    }
}
