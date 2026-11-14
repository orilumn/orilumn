package orilumn.reader.ui.reader

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/** 底栏七个图标：路径字符串必须都能被解析成 24 单位网格上的矢量（拼错一个字符，取图标时就会抛异常）。 */
class BarIconsTest {

    private val icons: List<Pair<String, ImageVector>> = listOf(
        "menu" to BarIcons.Menu,
        "bookmark" to BarIcons.Bookmark,
        "note" to BarIcons.Note,
        "edit" to BarIcons.Edit,
        "review" to BarIcons.Review,
        "moon" to BarIcons.Moon,
        "gear" to BarIcons.Gear,
    )

    @Test
    fun everyIconIsTheSameTwentyFourGrid() {
        icons.forEach { (name, icon) ->
            assertEquals(name, 24.dp, icon.defaultWidth)
            assertEquals(name, 24.dp, icon.defaultHeight)
            assertEquals(name, 24f, icon.viewportWidth, 0f)
            assertEquals(name, 24f, icon.viewportHeight, 0f)
        }
    }
}
