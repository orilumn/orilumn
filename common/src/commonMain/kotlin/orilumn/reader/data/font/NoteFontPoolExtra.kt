package orilumn.reader.data.font

/**
 * 旁注字体要装进共用字体池的「族名 → 字重」（没单独挑 = 空表）。
 *
 * 旁注文字由自研引擎排，字形**只从共用池取**（排版层不认平台字体对象，见
 * `NoteTextSpec` 的字段口径）。池里没有读者挑的那个族，画出来就是平台默认字体——
 * 读者看到的是「批注字体选了没反应」。
 *
 * 旁注跟随正文（[noteFont] 空）时返回空表：正文字族本来就在池里（槽位族），
 * 不必重复装；那时旁注用的族与字重都由正文那条通道保证有面。
 *
 * @param noteFont 读者为旁注挑的族名
 * @param noteFontWeight 那一档字重（100–900；0 / 越界按常规 400，与
 *   `noteFontWeightIntOf` 同口径——「没配」等于字体自身的默认档）
 */
fun noteFontPoolExtra(noteFont: String, noteFontWeight: Int): Map<String, Int> {
    val family = noteFont.trim()
    if (family.isEmpty()) return emptyMap()
    return mapOf(family to (noteFontWeight.takeIf { it in 100..900 } ?: 400))
}
