package orilumn.reader.ui.shelf

import orilumn.reader.ui.icons.pathIcon

/**
 * 书架底栏的四个工具图标：Material 图标官方路径（实心风格，与这一栏另外几个实心图标一致）。
 *
 * 网格用 Material Symbols 的 `grid_view`（四块方砖）；列表 / 导入 / 编辑沿用手边的
 * Material Icons：`list`（小方点 + 横线）、`add`（十字）、`layers`（叠层，代表「整理书架」）。
 */
internal object ShelfIcons {

    /** 网格视图：`grid_view`。 */
    val GridView = pathIcon("M3 11V3h8v8zm0 10v-8h8v8zm10-10V3h8v8zm0 10v-8h8v8z")

    /** 列表视图：`list`。 */
    val ListView = pathIcon("M3 13h2v-2H3zm0 4h2v-2H3zm0-8h2V7H3zm4 4h14v-2H7zm0 4h14v-2H7zM7 7v2h14V7z")

    /** 导入：`add`。 */
    val Import = pathIcon("M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6z")

    /** 编辑（多选整理）：`layers`。 */
    val EditMode = pathIcon("m11.99 18.54l-7.37-5.73L3 14.07l9 7l9-7l-1.63-1.27zM12 16l7.36-5.73L21 9l-9-7l-9 7l1.63 1.27z")
}
