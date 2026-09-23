package orilumn.reader.engine.render

/**
 * 全局调试绘制开关。
 *
 * - [DEBUG_DRAW]：编译期总开关。置为 `false` 时
 *   [orilumn.reader.engine.text.BoxPageRenderer] 里整个调试绘制段在编译期被裁剪，零运行时开销；
 *   打包发布时改为 `false`。
 * - [enabled]：运行时开关，由阅读页面底部工具栏的“调试”按钮切换，即时控制线框的显示/关闭，
 *   无需重新编译。
 *
 * 开启时会在每页内容之上叠加调试图形：
 * - 🔴 红色描边矩形：每个 box 的盒模型边界（边框盒）
 * - 🟢 绿色细线：每行文本的 top / bottom 边界
 * - 🔵 蓝色粗线：页面可视区域（内容区）边界，用于一眼看出溢出
 */
object DebugDraw {
    const val DEBUG_DRAW = true

    var enabled: Boolean = false
}