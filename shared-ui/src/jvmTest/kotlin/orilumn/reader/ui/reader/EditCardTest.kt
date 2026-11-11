package orilumn.reader.ui.reader

import orilumn.reader.data.revision.RevisionStyle
import orilumn.reader.data.revision.RevisionStyleSpan
import orilumn.reader.engine.revision.InlineMarkup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 打开旧修改时按**输入区全文结构**回显（`TextRevision.editedStyles`）。
 *
 * 背景：落库的最小修订被公共前后缀裁过，`styles` 只剩「改了一个字」那截，元素结构往往整个
 * 落在被裁掉的两端里。只按它回显会把 `<code>` 丢掉，读者再确认一次就真的删掉了。
 */
class EditCardTest {

    private val code = RevisionStyle("code", mapOf("class" to "code", "id" to "-nubh3s_43"))

    @Test
    fun cardKeepsStructureThatTheTrimmedRevisionLost() {
        // 「使用 var」→「用 var 声明」：最小修订只留下「使」，`var` 的 code 结构只剩输入区这一份。
        val edited = listOf(RevisionStyleSpan(2, 5, code))
        val card = editCardOf("用 var 声明", edited)
        assertEquals("用 `var` 声明", card.text)
        assertEquals(1, card.originalSpans?.size)
        assertEquals(InlineMarkup.StyleSig("code", code.attrs), card.originalSpans?.single()?.sig)
        assertTrue(card.ambientSpans.isEmpty())
    }

    @Test
    fun reConfirmsWithoutLosingOrDuplicatingStructure() {
        // 卡片回显 → 解析回来 → 按原件补回属性：结构与落库那一份逐字段相同（原样确认即 no-op）。
        val stored = listOf(RevisionStyleSpan(2, 5, code))
        val card = editCardOf("用 var 声明", stored)
        val parsed = InlineMarkup.parse(card.text)
        assertTrue(parsed.ok)
        assertEquals("用 var 声明", parsed.text)
        val restored = InlineMarkup.restore(card.originalSpans.orEmpty(), parsed.spans)
        assertEquals(InlineMarkup.fromStored(stored), restored)
    }

    @Test
    fun elementCoveringTheWholeEditedTextStaysHidden() {
        // 读者在元素内部改了一个字：整段仍是同一个元素，卡片里不显示标记（隐形套回）。
        val card = editCardOf("值", listOf(RevisionStyleSpan(0, 1, code)))
        assertEquals("值", card.text)
        assertEquals(1, card.ambientSpans.size)
    }

    @Test
    fun oldRevisionWithoutEditedStylesOpensAsPlainText() {
        // 老数据没存过输入区结构：按纯文本开卡，一个标记都不凭空加。
        val card = editCardOf("用 val 声明", null)
        assertEquals("用 val 声明", card.text)
        assertNull(card.originalSpans)
        assertTrue(card.ambientSpans.isEmpty())
    }
}
