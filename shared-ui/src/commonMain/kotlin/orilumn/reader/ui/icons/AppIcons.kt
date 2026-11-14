package orilumn.reader.ui.icons

/**
 * 通用小图标（不属于某一栏的零散用图）：官方 Material 路径，24 单位网格。
 *
 * 放在这里而不是各处自己写路径，是为了让「图标库」彻底不参与构建：`add` 与 `arrow_back`
 * 这两个图标的调用点分散在字体面板和书架标题栏，共用一份定义即可。
 */
internal object AppIcons {

    /** 加号：`add`。 */
    val Add = pathIcon("M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6z")

    /** 返回：`arrow_back`。 */
    val ArrowBack = pathIcon("M20 11H7.83l5.59-5.59L12 4l-8 8l8 8l1.41-1.41L7.83 13H20z")
}
