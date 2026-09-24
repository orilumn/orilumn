package orilumn.reader.engine.text

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 用户字体三槽路由（R4 归位自 `shared-ui ReaderMath.fontSlotFor`，行为逐字保留）。
 */
class FontSlotsTest {

    @Test
    fun slotFor_routesBodyTitleAndCode() {
        assertEquals("body", FontSlots.slotFor("p", monospace = false, body = "body", title = "title", code = "code"))
        assertEquals("title", FontSlots.slotFor("h2", monospace = false, body = "body", title = "title", code = "code"))
        assertEquals("title", FontSlots.slotFor("h6", monospace = false, body = "body", title = "title", code = "code"))
        assertEquals("code", FontSlots.slotFor("pre", monospace = false, body = "body", title = "title", code = "code"))
        assertEquals("code", FontSlots.slotFor("div", monospace = true, body = "body", title = "title", code = "code"))
        assertEquals("code", FontSlots.slotFor(null, monospace = true, body = "body", title = "title", code = "code"))
        assertEquals("body", FontSlots.slotFor(null, monospace = false, body = "body", title = "title", code = "code"))
        // 非 h 标签（如 hsts）不误判为标题
        assertEquals("body", FontSlots.slotFor("hsts", monospace = false, body = "body", title = "title", code = "code"))
    }
}
