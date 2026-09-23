package orilumn.reader.engine.laying

import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.BoxChapterLayouter
import orilumn.reader.engine.ChapterStructureCache
import orilumn.reader.engine.css.CssBundle
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.text.FontPool
import orilumn.reader.engine.text.TypographicProfile
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression for the cold-open "layout FAIL ch=… null" crash: [BoxChapterLayouter.prepareLight] is
 * called concurrently for the same chapter's [ChapterStructureCache] (open thread vs prewarm vs
 * canonical), and its cache check trusted ONLY the `key` fingerprint. `key` and `parsedAuthorSheets`
 * are stored separately, so a peer thread could write `key` while `parsedAuthorSheets` was still null;
 * a reader then saw the key already matching (rebuild = false), skipped the parse and hit the
 * force-unwrap NPE on the null sheet list. The served snapshot below reproduces exactly that
 * interleaving: key fresh, sheets still null.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrepareLightRaceRegressionTest {

    private val layouter = BoxChapterLayouter()
    private val converter = HtmlTreeConverter()
    private val contentH = 1280

    private fun chapterHtml(): String = """
        <!DOCTYPE html><html><head></head><body>
        <h1>Chapter one</h1>
        <p>Paragraph one: the quick brown fox jumps over the lazy dog. Padding keeps the text flowing.</p>
        <p>Paragraph two with a little more body text so the leaf enumeration has something to work on
        beyond a single line. A second sentence continues the paragraph nicely.</p>
        <p>Paragraph three ends the chapter with a modest amount of content and a trailing sentence.</p>
        </body></html>
    """.trimIndent()

    private fun chapterCss(): String = "body { margin: 0; } p { margin: 0.8em 0; } h1 { margin: 1em 0; }"

    @Test
    fun `key-matching but empty sheet cache rebuilds instead of crashing`() {
        val profile = TypographicProfile.build(ReaderSettings.DEFAULT)
        val root = converter.convert(chapterHtml()) ?: error("chapter parse failed")
        val bundle = CssBundle(listOf(chapterCss()))

        val contentW = 720

        val structure = ChapterStructureCache()
        // First full pass fills key + parsedAuthorSheets together.
        layouter.prepareLight(root, bundle, profile, contentW, structure, contentH)

        // Race snapshot as seen by the open thread: a concurrent prewarm has already written the
        // fingerprint, but the parsed sheets haven't been stored yet (the two writes are not atomic).
        structure.parsedAuthorSheets = null

        // Must rebuild (not NPE): leaves and globalCharStarts must be present for downstream shaping.
        val light = layouter.prepareLight(root, bundle, profile, contentW, structure, contentH)
        assertTrue(light.totalBlocks > 0)
        assertTrue(light.globalCharStarts.isNotEmpty())
    }

    @Test
    fun `fresh cache builds once and is reusable`() {
        val profile = TypographicProfile.build(ReaderSettings.DEFAULT)
        val root = converter.convert(chapterHtml()) ?: error("chapter parse failed")
        val bundle = CssBundle(listOf(chapterCss()))

        val contentW = 720

        val structure = ChapterStructureCache()
        val first = layouter.prepareLight(root, bundle, profile, contentW, structure, contentH)
        // Second call with no structural change: same key, sheets present — served from cache.
        val second = layouter.prepareLight(root, bundle, profile, contentW, structure, contentH)
        assertTrue(first.totalBlocks > 0)
        assertTrue(second.totalBlocks > 0)
    }
}