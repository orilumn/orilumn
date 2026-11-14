package orilumn.reader.ui.reader

import orilumn.reader.engine.revision.InlineMarkup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「这一行本身就是书里那个行内元素」（书里 `span { display:block }` 之类）在卡片里的口径。
 *
 * 卡片按整行包一层 `<tag>` 显示它（读者复报「标题开头的 `<span>` 不显示」）。这层包装与块标记
 * 同一待遇：**只活在卡片里**。提交时摘掉（叶元素本来就留着，进修订的只能是叶里的字与结构），
 * 读者删 / 换掉它则拒绝——把块级元素摘掉等于把这一行并回上一行，那是结构改写。
 */
class OwnTagCardTest {

    private fun show(lines: List<String>, ownTags: List<String?>?): String {
        val segments = lines.map { InlineMarkup.Segment(it) }
        return InlineMarkup.show(segments, ownTagSpans(lines, ownTags))
    }

    private fun parse(card: String): InlineMarkup.Parsed = InlineMarkup.parse(card)

    @Test
    fun eachOwnLineIsWrappedByItsTag() {
        val tags = listOf("span", null, "a")
        assertEquals(
            listOf(0 to 2 to "span", 6 to 8 to "a"),
            ownTagSpans(listOf("章一", "风起", "链接"), tags).map {
                it.start to it.endExclusive to it.sig.tag
            },
        )
    }

    @Test
    fun ownTagLineShowsItsTagButPlainLineDoesNot() {
        val card = show(listOf("第四十八章", "夜话"), listOf("span", null))
        assertEquals("<span>第四十八章</span>\n夜话", card)
        // 行内元素本人那层包装在白名单里，卡片能原样解析回来。
        assertTrue(parse(card).errors.toString(), parse(card).ok)
    }

    @Test
    fun ownTagWrapperIsStrippedBeforeCommit() {
        // 读者把标签留着（改的是字）：包装摘掉，叶里没有多出任何结构（否则重放会套一层）。
        val parsed = parse("<span>第<em>四十</em>八章</span>\n夜话")
        val (spans, error) = withoutOwnTags(
            parsed.segments,
            parsed.spans,
            listOf("span", null),
        )
        assertNull(error)
        assertEquals(listOf("em"), spans.map { it.sig.tag })
        assertEquals(listOf(1 to 3), spans.map { it.start to it.endExclusive })
    }

    @Test
    fun deletingTheOwnTagIsRejected() {
        val parsed = parse("第四十八章\n夜话")
        val (_, error) = withoutOwnTags(parsed.segments, parsed.spans, listOf("span", null))
        assertTrue("删掉整行标签要拒绝：$error", error != null && error.contains("<span>"))
    }

    @Test
    fun renamingTheOwnTagIsRejected() {
        val parsed = parse("<em>第四十八章</em>\n夜话")
        val (_, error) = withoutOwnTags(parsed.segments, parsed.spans, listOf("span", null))
        assertTrue("换成别的标签要拒绝：$error", error != null && error.contains("<span>"))
    }

    @Test
    fun emptiedLineIsNotChecked() {
        // 把这一行的字删光是在删内容，不是在删元素：不拦（空的块级元素留不留由重放自己判）。
        val parsed = parse("\n夜话")
        val (spans, error) = withoutOwnTags(parsed.segments, parsed.spans, listOf("span", null))
        assertNull(error)
        assertTrue(spans.isEmpty())
    }

    @Test
    fun blockLevelLinkIsStillWritableAndStrippable() {
        // `<a>` 自己成了一行（少数书会这么排目录）：卡片写不出属性，但仍得是能解析回来的写法，
        // 提交时照样按「叶自己」摘掉。
        val card = show(listOf("链接"), listOf("a"))
        val parsed = parse(card)
        assertTrue("卡片：$card，错误：${parsed.errors}", parsed.ok)
        val (spans, error) = withoutOwnTags(parsed.segments, parsed.spans, listOf("a"))
        assertNull(error)
        assertTrue(spans.isEmpty())
    }

    @Test
    fun reopenShowsTheSameOwnTagAsAFreshCard() {
        // 重开旧修订（按整段重建卡片）时标签不能凭空消失：与第一次开卡的输入区逐字相同。
        val reopened = editCardOf(
            editedText = "第四十八章\n夜话",
            blocks = listOf("", ""),
            stored = null,
            oldSpans = null,
            ownTags = listOf("span", null),
        )
        assertEquals(show(listOf("第四十八章", "夜话"), listOf("span", null)), reopened.text)
        // 那层包装不进「原件结构」：提交时摘掉、no-op 比较也不看它。
        assertNull(reopened.originalSpans)
        assertTrue(reopened.ambientSpans.isEmpty())
    }
}
