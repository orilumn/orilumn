package orilumn.reader.engine.text.preprocess

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LongStringBreakerTest {

    private fun breaks(text: String, vararg overrides: Pair<String, Int>): List<Int> {
        val args = overrides.toMap()
        return LongStringBreaker.breakOpportunities(
            text,
            minLength = args["minLength"] ?: 24,
            chunk = args["chunk"] ?: 16,
            minPrefix = args["minPrefix"] ?: 4,
            minSuffix = args["minSuffix"] ?: 2,
        )
    }

    @Test
    fun `long unbroken latin run breaks by chunk`() {
        assertEquals(listOf(16, 32, 48), breaks("a".repeat(60)))
    }

    @Test
    fun `custom chunk size is honored`() {
        assertEquals(listOf(8, 16, 24, 32, 40, 48, 56), breaks("b".repeat(60), "chunk" to 8))
    }

    @Test
    fun `short runs yield no breaks`() {
        assertTrue(breaks("abc").isEmpty())
        assertTrue(breaks("word word word").isEmpty())
        assertTrue(breaks("a".repeat(16)).isEmpty())
    }

    @Test
    fun `uuid breaks only after hyphens`() {
        val text = "12345678-90ab-cdef-1234-567890abcdef"
        assertEquals(listOf(8, 13, 18, 23), breaks(text))
    }

    @Test
    fun `url yield separator breaks inside the run`() {
        val text = "https://example.com/path/to/page.html?q=1&sort=asc"
        val res = breaks(text)
        assertTrue(res.isNotEmpty())
        // every break is "after a separator"
        assertTrue(res.all { text[it] in LongStringBreaker.SEPARATORS })
        // breaks respect the prefix/suffix guards and are sorted/distinct
        assertTrue(res.all { it >= 4 })
        assertTrue(res.all { it < text.length - 2 })
        assertEquals(res, res.distinct().sorted())
        // the '?' query and '&' params really are offered
        assertTrue(text.indexOf('?') in res)
        assertTrue(text.indexOf('&') in res)
    }

    @Test
    fun `breaks never cross whitespace-run boundaries`() {
        // 30-char run, space, 6-char word: breaks must live entirely inside the first run.
        val text = "a".repeat(30) + " bert" + " ".repeat(3) + "short"
        val res = breaks(text)
        assertTrue(res.isNotEmpty())
        assertTrue(res.all { it < 30 - 2 })
        assertTrue(res.none { it >= 34 })
    }

    @Test
    fun `cjk text produces no long-run breaks`() {
        val text = "这是一段很长的中文文本，不需要在内部断行。".repeat(3)
        assertTrue(breaks(text).isEmpty())
    }
}