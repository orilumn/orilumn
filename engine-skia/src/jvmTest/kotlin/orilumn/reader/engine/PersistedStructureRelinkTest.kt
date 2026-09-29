package orilumn.reader.engine

import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.css.CssBundle
import orilumn.reader.engine.html.ChapterPreprocessor
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.text.TypographicProfile
import okio.Path.Companion.toPath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        val tampered = payload.copy(leafRefs = payload.leafRefs + LeafRef(intArrayOf(9999), null))
        // Count mismatch vs starts → reject.
        assertFalse(ChapterStructurePersist.apply(tree, tampered, cssHash, ChapterStructureCache()))
    }

    @Test
    fun `heavy prepare reuses bound gen strings identically`() {
        val html = """
            <!DOCTYPE html><html><head><style>li:before{content:"• ";} ul{margin:0;}</style></head><body>
            <ul><li>第一项</li><li>第二项</li></ul>
            <p>正文段落。</p>
            </body></html>
        """.trimIndent()
        val parsed = converter.convertWithStyles(html) ?: error("parse failed")
        val bundle = ImportStructures.collectChapterCssTexts(EmptyReader, "ch.html", parsed, null)
        val sheets = bundle.cssTexts.map { orilumn.reader.engine.css.LightCssParser().parse(it, null) }
        val tree = ChapterPreprocessor.preprocess(parsed.tree, sheets)
        val profile = profileOf(46f, "屏显臻宋", true)
        // Light path binds the strings (the persisted source in production).
        val cache = ChapterStructureCache()
        layouter.prepareLight(tree, bundle, profile, 1600, cache, 2400)
        assertTrue("fixture must trigger the gen gate", cache.genStrings.isNotEmpty())
        // Heavy without and with the bound strings must produce identical char streams.
        val a = layouter.prepare(tree, bundle, profile, 1600, 2400)
        val b = layouter.prepare(tree, bundle, profile, 1600, 2400, genStrings = cache.genStrings)
        assertEquals(a.totalBlocks, b.totalBlocks)
        assertEquals(a.totalChars, b.totalChars)
        assertArrayEquals(a.globalCharStarts, b.globalCharStarts)
    }

    @Test
    fun `synthetic anonymous leaves round-trip through file`() {
        // Mixed inline/block content forces flowChildren synthetics (detached by design):
        // extract must reference them by owner+text, relink must recreate them verbatim.
        // (The span must itself be block-level, as in real books' .chapter_num{display:block}.)
        val html = """
            <!DOCTYPE html><html><head><style>.chapter_num{display:block;}</style></head><body>
            <h2><span class="chapter_num">第五十七章</span> 三分</h2>
            <p>正文段落。</p>
            </body></html>
        """.trimIndent()
        val parsed = converter.convertWithStyles(html) ?: error("parse failed")
        val bundle = ImportStructures.collectChapterCssTexts(EmptyReader, "ch.html", parsed, null)
        val tree = ChapterPreprocessor.preprocess(parsed.tree, emptyList())
        val profile = profileOf(46f, "屏显臻宋", true)
        val cache = ChapterStructureCache()
        layouter.prepareLight(tree, bundle, profile, 1600, cache, 2400)
        val cssHash = ChapterStructureCodec.cssHashOf(bundle.cssTexts)
        val payload = ChapterStructurePersist.extract(3, cssHash, tree, cache) ?: error("extract")
        assertTrue("mixed content must yield a synthetic leaf", payload.leafRefs.any { it.syntheticText != null })
        val file = PersistedChapterFile(3, cssHash, emptyList(), emptyList(), tree, payload)
        val read = ChapterStructureCodec.decodeFile(ChapterStructureCodec.encodeFile(file)) ?: error("file codec")
        // Fresh re-parse (simulates open): relink must reproduce every leaf's text in order.
        val parsed2 = converter.convertWithStyles(html) ?: error("re-parse failed")
        val tree2 = ChapterPreprocessor.preprocess(parsed2.tree, emptyList())
        val relinked = ChapterStructureCache()
        assertTrue(ChapterStructurePersist.apply(tree2, read.structure, read.cssHash, relinked))
        assertEquals(cache.leaves.size, relinked.leaves.size)
        assertArrayEquals(cache.globalCharStarts, relinked.globalCharStarts)
        cache.leaves.forEachIndexed { i, leaf ->
            assertEquals(leaf.text, relinked.leaves[i].text)
            assertEquals(leaf.tag, relinked.leaves[i].tag)
        }
    }

    @Test
    fun `hasMediaRules detects only viewport-variant conditions`() {
        assertFalse(ChapterStructurePersist.hasMediaRules(listOf("p{color:red;}")))
        assertFalse(ChapterStructurePersist.hasMediaRules(listOf("@import \"a.css\";")))
        assertFalse(ChapterStructurePersist.hasMediaRules(listOf("@charset \"utf-8\";p{}")))
        // Bare types are viewport-invariant (screen→always inline, print→always drop).
        assertFalse(ChapterStructurePersist.hasMediaRules(listOf("@media screen{p{}}")))
        assertFalse(ChapterStructurePersist.hasMediaRules(listOf("@MEDIA screen{p{}}")))
        assertFalse(ChapterStructurePersist.hasMediaRules(listOf("@media print{p{}}")))
        assertFalse(ChapterStructurePersist.hasMediaRules(listOf("@import \"a.css\" screen;")))
        assertFalse(ChapterStructurePersist.hasMediaRules(listOf("/* @media in comment */p{}")))
        // Feature queries are genuinely viewport-dependent.
        assertTrue(ChapterStructurePersist.hasMediaRules(listOf("@media (max-width:600px){p{}}")))
        assertTrue(ChapterStructurePersist.hasMediaRules(listOf("@media screen and (max-width:600px){p{}}")))
        assertTrue(ChapterStructurePersist.hasMediaRules(listOf("@import url(a.css) (max-width:100px);")))
        assertTrue(ChapterStructurePersist.hasMediaRules(listOf("@import \"a.css\" screen and (min-width:1px);")))
    }

    @Test
    fun `persisted file loads tree and relinks without epub`() {
        // Simulates import→open: prepareLight computes, persistChapter writes the full file,
        // open loads tree + sheets from disk only and relinks — no XML re-parse.
        val profile = profileOf(46f, "屏显臻宋", true)
        val (tree0, bundle) = treeAndBundle()
        val sheets = bundle.cssTexts.map { orilumn.reader.engine.css.LightCssParser().parse(it, null) }
        val tree = ChapterPreprocessor.preprocess(tree0, sheets)
        val cache = ChapterStructureCache()
        layouter.prepareLight(tree, bundle, profile, 1600, cache, 2400)
        val dir = java.nio.file.Files.createTempDirectory("struct-e2e").toString()
        val store = ChapterStructureStore(okio.FileSystem.SYSTEM, dir.toPath())
        assertTrue(ImportStructures.persistChapter(store, "book_9", 3, tree, bundle, cache))
        // Open side: resolve sheets, rebuild bundle, relink onto the loaded tree.
        val file = store.readChapter("book_9", 3) ?: error("no file")
        val sheetMap = store.readSheets("book_9") ?: error("no sheets")
        val texts = file.sheetHashes.map { sheetMap[it] ?: error("missing sheet") }
        assertEquals(bundle.cssTexts, texts)
        assertEquals(bundle.baseHrefs, file.baseHrefs)
        val relinked = ChapterStructureCache()
        assertTrue(ChapterStructurePersist.apply(file.tree, file.structure, file.cssHash, relinked))
        assertEquals(cache.leaves.size, relinked.leaves.size)
        assertArrayEquals(cache.globalCharStarts, relinked.globalCharStarts)
        cache.leaves.forEachIndexed { i, leaf ->
            assertEquals(leaf.text, relinked.leaves[i].text)
        }
    }

    private class FakeReader(val chapters: Map<String, String>) : orilumn.reader.data.epub.EpubResourceReader {
        override fun entries(): Sequence<String> = chapters.keys.asSequence()
        override fun readText(path: String): String? = chapters[path]
        override fun readBytes(path: String): ByteArray? = null
    }

    @Test
    fun `import job persists media-free chapters and skips media ones`() {
        val css = "p{color:#111;}"
        val mediaCss = "@media (max-width:600px){p{color:red;}}"
        val bareScreenCss = "@media screen{p{color:red;}}"
        fun ch(body: String, style: String) =
            "<html><head><style>$style</style></head><body>$body</body></html>"
        val reader = FakeReader(mapOf(
            "a.html" to ch("<p>one</p>", css),
            "b.html" to ch("<p>two</p>", mediaCss),
            "c.html" to ch("<p>three</p>", css),
            "d.html" to ch("<p>four</p>", bareScreenCss),
        ))
        val dir = java.nio.file.Files.createTempDirectory("struct-import").toString()
        val store = ChapterStructureStore(okio.FileSystem.SYSTEM, dir.toPath())
        val stats = ImportStructures.buildAllChapterStructures(
            reader, listOf("a.html", "b.html", "c.html", "d.html"), store, "book_1",
        )
        assertEquals(4, stats.chapters)
        assertEquals(3, stats.persisted)
        assertEquals(1, stats.skippedMedia)
        assertEquals(0, stats.failed)
        assertNotNull(store.readChapter("book_1", 0))
        assertNull(store.readChapter("book_1", 1)) // viewport-variant media: no file, runtime path at open
        assertNotNull(store.readChapter("book_1", 2))
        assertNotNull(store.readChapter("book_1", 3)) // bare screen: viewport-invariant, persisted
        assertEquals(2, store.readSheets("book_1")!!.size) // css + bare-screen sheet
    }
}
