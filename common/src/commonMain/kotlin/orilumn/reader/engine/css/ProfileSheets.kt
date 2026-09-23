package orilumn.reader.engine.css

import orilumn.reader.engine.text.TypographicProfile

/**
 * C2-P2b-4: profile → 样式表构建（从 `:app CssLayouter` 原样搬运，纯 CSS 构造，无平台代码）。
 *
 * UA 基线与排版主题预设都 single-sourced 在 common（`ReaderStylesheets`，Z2），
 * 这里只是按当前 profile 实例化。`:app CssLayouter` 的同名方法保留作委托（兼容其单测）。
 */
fun uaSheetFromProfile(profile: TypographicProfile): StyleSheet =
    LightCssParser().parse(
        ReaderStylesheets.ua().replace(ReaderStylesheets.LINK_COLOR_TOKEN, profile.linkColorHex),
    )

/**
 * 排版主题样式表（tier 42，阅读器主题层）。原书设置（original）回 null —— 书自有 CSS 当立；
 * 预设的缩进/段间距由 [TypographicProfile] 构建器镜像进 UI 层，此处只出主题层。
 */
fun themeSheetFromProfile(profile: TypographicProfile): StyleSheet? {
    if (profile.useOriginalStyle) return null
    val css = ReaderStylesheets.theme(profile.layoutTheme) ?: return null
    return LightCssParser().parse(css)
}
