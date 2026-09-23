package orilumn.reader.data.epub

import org.junit.Assert.assertEquals
import org.junit.Test

class PathResolutionTest {

    private val reader: EpubResourceReader = FakeEpubResourceReader(emptyMap())

    @Test
    fun `resolveRelative 同级与上级目录`() {
        // same-directory sibling vs parent-dir stylesheet
        assertEquals("Text/style.css", reader.resolveRelative("Text/ch1.xhtml", "style.css"))
        assertEquals("OEBPS/Styles/main.css", reader.resolveRelative("OEBPS/Text/ch1.xhtml", "../Styles/main.css"))
    }

    @Test
    fun `collapsePath 折叠点与双点`() {
        assertEquals("a/b/c", reader.collapsePath("a/./b/./c"))
        assertEquals("a/c", reader.collapsePath("a/b/../c"))
        assertEquals("c", reader.collapsePath("a/b/../../c"))
        assertEquals("a/b", reader.collapsePath("a/b/../b/"))
    }

    @Test
    fun `根目录章节的相对路径`() {
        assertEquals("style.css", reader.resolveRelative("ch1.xhtml", "style.css"))
        // backslashes normalized to forward slashes
        assertEquals("Styles/m.css", reader.collapsePath("Styles\\m.css"))
    }
}