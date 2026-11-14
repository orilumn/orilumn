package orilumn.reader.ui.reader

/**
 * 卷曲翻页在当前平台是否可用。
 *
 * 卷曲走逐像素着色器：桌面用 skia `RuntimeEffect`，Android 只有 API 33（Android 13）起
 * 才有等价的 AGSL `RuntimeShader`。低版本不做降级实现（片带/网格是另一套几何，
 * 维护成本不划算），一律按滑动翻页处理；**设置面板也不显示这个选项**——
 * 省得读者选了一个永远不会生效的档。
 *
 * 是 `val` 而不是编译期常量：Android 侧要读系统版本，桌面恒为 true。
 */
expect val curlFlipSupported: Boolean
