package orilumn.reader.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * 把 Material 图标官网上的 SVG `d` 路径串成 24 单位网格的 [ImageVector]。
 *
 * 为什么不用 `Icons.*`：项目只依赖 `material-icons-core`（49 个图标），扩展包桌面端 36 MB。
 * 需要的图标直接搬官方路径（Apache-2.0）最划算，新增图标只是多一行字符串。
 * 着色交给 `Icon(tint = ...)` 的 ColorFilter，所以路径本身填黑色即可。
 *
 * @param pathData 一个图标可能由多段路径组成（比如眼睛 = 轮廓 + 瞳孔），逐段传入即可。
 */
internal fun pathIcon(vararg pathData: String): ImageVector = ImageVector.Builder(
    name = "Icon",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    pathData.forEach { addPath(pathData = addPathNodes(it), fill = SolidColor(Color.Black)) }
}.build()
