package orilumn.reader.ui.reader

import orilumn.reader.engine.revision.TableRowScope

/**
 * 点按落在「压着整表修订的表格」上时的去处（用户层点按判定，见 `ReaderScreen.tryOpenReviewEditAt`）。
 *
 * 抽出来是为了能脱离 Compose 直接验：真机复报「点表格里的修改区域没反应」，这条判定正是
 * 决定「有没有反应 / 反应成哪张卡」的那一步。
 */
internal sealed interface ReviewTableTap {
    /** 吃了这次点按但不进编辑（不翻页 / 不切栏）：点在了一行都没改过的表格区域。 */
    data object Ignore : ReviewTableTap

    /** 打开整表卡：普通阅读视图（没有红绿字可认），或审阅视图里拿不到行对照。 */
    data object WholeTable : ReviewTableTap

    /** 打开行卡：审阅视图里点到了改过的那一行（卡片只列这一行，不框整张表）。
     *
     * 点的是**被删掉的行**（红字）时 [scope] 是插入位置：卡片空白（上次编辑的结果就是
     * 这行不存在），读者想补一行就在这里写。
     */
    data class Row(val scope: TableRowScope) : ReviewTableTap
}

/**
 * 整表修订上的点按判定。
 *
 * - **普通阅读视图**（[reviewSpace] = false）：正文就是改后的表，没有红绿字可认 ⇒ 点表上
 *   改动过的字一律打开整表卡片，带出改后的整张表（不框整张表）。没有修订的表压不出整表
 *   修订，调用方根本不会进来，所以这里不会误伤没改过的表格。
 * - **审阅视图**：只认**改过的那一行**。判据是画出来的红绿痕迹 [marksOf] 与该行是否有交集
 *   —— 改一格常常只改一两个字，读者点这一行里其他字也该进编辑；整行都没改过才不响应。
 *   点到删掉的行时打开**空白**行卡（上次编辑的结果就是这行不存在），位置留在原处；
 *   拿不到行对照（[scopeOf] 返回 null）时才退回整表卡片。
 *
 * 三个入参都是「按需取」：普通视图里一次都不必去排版层要行块 / 痕迹，点按路径上不白算。
 *
 * @param rowOf 点按偏移所在的行块区间（章内偏移，与 [reviewSpace] 同一把尺）；null = 拿不到。
 * @param marksOf 本章的审阅痕迹（同一把尺）。
 * @param scopeOf 这条修订在点按处对应的行范围；null = 拿不到（老记录 / 对不上行）。
 */
internal fun reviewTableTapOf(
    reviewSpace: Boolean,
    rowOf: () -> IntRange?,
    marksOf: () -> List<ReviewMark>,
    scopeOf: () -> TableRowScope?,
): ReviewTableTap {
    if (!reviewSpace) return ReviewTableTap.WholeTable
    val row = rowOf()
    val changed = row == null || marksOf().any { m ->
        m.start < row.last + 1 && row.first < m.endExclusive
    }
    if (!changed) return ReviewTableTap.Ignore
    val scope = scopeOf()
    return if (scope != null) ReviewTableTap.Row(scope) else ReviewTableTap.WholeTable
}
