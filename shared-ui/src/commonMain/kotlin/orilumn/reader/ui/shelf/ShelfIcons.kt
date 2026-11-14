package orilumn.reader.ui.shelf

import orilumn.reader.ui.icons.pathIcon

/**
 * 书架底栏的图标：Material 图标官方路径（实心风格，与这一栏整体一致）。
 *
 * 浏览态四个：网格 `grid_view`、列表 `list`、导入 `add`、编辑 `layers`（多选整理书架）。
 * 编辑态四个：全选 `select_all`、反选 `toggle_on`、分组 `ad_group`、删除 `delete`。
 */
internal object ShelfIcons {

    // ---- 浏览态 ----

    /** 网格视图：`grid_view`。 */
    val GridView = pathIcon("M3 11V3h8v8zm0 10v-8h8v8zm10-10V3h8v8zm0 10v-8h8v8z")

    /** 列表视图：`list`。 */
    val ListView = pathIcon("M3 13h2v-2H3zm0 4h2v-2H3zm0-8h2V7H3zm4 4h14v-2H7zm0 4h14v-2H7zM7 7v2h14V7z")

    /** 导入：`add`。 */
    val Import = pathIcon("M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6z")

    /** 编辑（多选整理）：`layers`。 */
    val EditMode = pathIcon(
        "m11.99 18.54l-7.37-5.73L3 14.07l9 7l9-7l-1.63-1.27z" +
            "M12 16l7.36-5.73L21 9l-9-7l-9 7l1.63 1.27z",
    )

    // ---- 编辑态（多选） ----

    /** 全选：`select_all`。 */
    val SelectAll = pathIcon(
        "M7 17V7h10v10zm2-2h6V9H9zm-4 4v2q-.825 0-1.412-.587T3 19zm-2-2v-2h2v2zm0-4v-2h2v2z" +
            "m0-4V7h2v2zm2-4H3q0-.825.588-1.412T5 3zm2 16v-2h2v2zM7 5V3h2v2zm4 16v-2h2v2z" +
            "m0-16V3h2v2zm4 16v-2h2v2zm0-16V3h2v2zm4 16v-2h2q0 .825-.587 1.413T19 21" +
            "m0-4v-2h2v2zm0-4v-2h2v2zm0-4V7h2v2zm0-4V3q.825 0 1.413.588T21 5z",
    )

    /** 反选：`toggle_on`。 */
    val Invert = pathIcon(
        "M7 18q-2.5 0-4.25-1.75T1 12t1.75-4.25T7 6h10q2.5 0 4.25 1.75T23 12t-1.75 4.25T17 18z" +
            "m12.125-3.875Q20 13.25 20 12t-.875-2.125T17 9t-2.125.875T14 12t.875 2.125T17 15t2.125-.875",
    )

    /** 分组：`ad_group`。 */
    val Group = pathIcon(
        "M8 18q-.825 0-1.412-.587T6 16V4q0-.825.588-1.412T8 2h12q.825 0 1.413.588T22 4v12" +
            "q0 .825-.587 1.413T20 18zM8 6h12V4H8zM4 22q-.825 0-1.412-.587T2 20V6h2v14h14v2z",
    )

    /** 删除：`delete`。 */
    val Delete = pathIcon(
        "M7 21q-.825 0-1.412-.587T5 19V6H4V4h5V3h6v1h5v2h-1v13q0 .825-.587 1.413T17 21z" +
            "m2-4h2V8H9zm4 0h2V8h-2z",
    )
}
