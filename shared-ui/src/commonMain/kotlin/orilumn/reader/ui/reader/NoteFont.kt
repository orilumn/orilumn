package orilumn.reader.ui.reader

import androidx.compose.ui.text.font.FontWeight
import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.css.ReaderStylesheets
import orilumn.reader.engine.text.FontSlots
import orilumn.reader.engine.text.TypographicProfile

/**
 * 批注字族栈（作者顺序，通用关键字原样保留由引擎展开）；空 = 不覆盖，用平台默认。
 *
 * 批注字体解析（渲染层·平台缝 → 排版层接缝）：批注文字由**自研断行引擎**排
 * （与正文同一个量宽/断行/对齐出口），引擎只认**字族名字符串**、不认平台字体对象。
 * 所以这里只回答「用哪几个族名、哪一档字重」，由引擎在自己的字体池里解析到真实字形面
 * （导入字体已由宿主装进池，见 `FontPoolSync` 的 `extraFamilies`）。
 *
 * 「批注单独挑过字」优先；没挑（`noteFont` 空）时**跟随正文**：正文槽挑过族就用那一族，
 * 否则用排版主题给正文声明的通用族（现代 = 无衬线、传统 = 衬线）；
 * 「原书设置」下正文由书的 CSS 决定，覆盖层无从得知 ⇒ 空栈（引擎用平台默认字体）。
 */
internal fun noteFontStackOf(settings: ReaderSettings, profile: TypographicProfile): List<String> {
    if (settings.noteFont.isNotBlank()) return listOf(settings.noteFont.trim())
    if (profile.fontBody.isNotBlank()) return listOf(profile.fontBody)
    val generic = ReaderStylesheets.themeBodyFamily(profile.layoutTheme) ?: return emptyList()
    // 通用族替换（读者在「衬线 / 无衬线（全局）」里挑的族）。替换族放栈首、原关键字留尾兜底，
    // 与正文那条声明同形（见 `ReaderStylesheets` 的通用族替换）。
    val alias = profile.genericFontAliases[generic]
    return if (alias != null) listOf(alias, generic) else listOf(generic)
}

/**
 * 设置里的批注字重 → 交给引擎的数值字重（100–900）。
 *
 * **批注单独挑过字体时为读者选的那一档；没挑（跟随正文）时与正文同档**——正文槽的字重
 * 锚点，或主题给正文的角色字重（常规 400）。否则会出现「正文是粗体、旁边批注是常规」的错位。
 *
 * 没配（`null`）时给 400：这就是字体自身的默认档，与旧 Compose 路径的 `FontWeight.Normal`
 * 同值，不会因为「不写字重」而落成别的档。
 */
internal fun noteFontWeightIntOf(settings: ReaderSettings, profile: TypographicProfile): Int =
    noteFontWeightOf(settings, profile)?.weight ?: 400

/** 主题给正文声明的角色字重（正文常规）：三个全局字体槽只挑字族，字重由排版主题决定。 */
private const val THEME_BODY_WEIGHT = 400

/**
 * 设置里的批注字重 → [FontWeight]（`null` = 没配，跟随字体自身默认）。
 *
 * 保留这个中间形态是因为面板与单测都按「有没有配」判语义（`null` ≠ 400）：
 * 面板换字体时把档位清零，不能悄悄变成「显式常规」。
 */
internal fun noteFontWeightOf(settings: ReaderSettings, profile: TypographicProfile): FontWeight? {
    val weight = if (settings.noteFont.isNotBlank()) {
        settings.noteFontWeight.takeIf { it in 100..900 }
    } else {
        bodyWeightOf(profile)
    }
    return weight?.let { FontWeight(it) }
}

/** 正文当前的读者字重（跟随正文时批注照搬）：正文槽锚点，或主题给正文的角色字重。 */
private fun bodyWeightOf(profile: TypographicProfile): Int? {
    if (profile.fontBody.isBlank()) {
        ReaderStylesheets.themeBodyFamily(profile.layoutTheme) ?: return null
        return THEME_BODY_WEIGHT
    }
    return FontSlots.weightOf(
        profile.fontWeightAnchorsBySlot,
        profile.fontWeightAnchors,
        "fontBody",
        profile.fontBody,
    )
}
