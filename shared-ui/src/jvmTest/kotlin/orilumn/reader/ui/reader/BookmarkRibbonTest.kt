package orilumn.reader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/** 右上角长书签：路径必须能解析，视口保持 2:3，星形才不会被拉扁。 */
class BookmarkRibbonTest {

    @Test
    fun longBookmarkKeepsTwoByThreeViewport() {
        assertEquals(24f, BookmarkRibbon.viewportWidth, 0f)
        assertEquals(36f, BookmarkRibbon.viewportHeight, 0f)
    }
}
