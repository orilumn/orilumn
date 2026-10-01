package orilumn.reader.engine.skia

import orilumn.reader.engine.AbSwitch
import orilumn.reader.engine.css.FontRun
import orilumn.reader.engine.css.TextAlign
import orilumn.reader.engine.laying.BaselineShift
import orilumn.reader.engine.laying.BrokenLine
import orilumn.reader.engine.laying.ParagraphBreaker

/**
 * S3 接线：**正文断行器的唯一入口**（排版层·上 → 渲染层实现侧的接缝）。
 *
 * ## 为什么要单源
 *
 * 正文断行在生产里有三个接线点，形状不同、位置分散：
 * [orilumn.reader.engine.BoxChapterLayouter] 重路径整章塑形、
 * 同文件轻路径浮动 lead 度量、以及 `ShapeGeometry` 的叶级 `breakWrappedLines`。
 * 若各处各自 `if (开关) 自建 else Skia`，任何一侧漏改就是**两侧分家** ——
 * 量宽用自建、绘制按 Skia 断点，或反之，症状是「同一段文字高度随翻页方式变化」这类
 * 极难归因的漂移。故三处一律调本函数。
 *
 * ## 变体为何由开关决定、且进 `paramHash`
 *
 * 见 [AbSwitch.inhouseBreak] 与 [orilumn.reader.engine.text.LayoutParamKey.inhouseBreak]：
 * 变体必须进缓存键，否则拨开关会命中按另一侧断点算出的旧磁盘表。
 *
 * ## 表格不在此列（TODO Q6）—— 本函数只管「断行」
 *
 * 表格 auto 分列的**列宽度量**刻意留在 Skia：轻路径用恒 Skia 的
 * `BoxChapterLayouter.tableBreaker`，重路径由 [heavyPathBreaker] 把度量方法钉回 Skia。
 * 两处都在，因为重路径的 `tableCellPref` 收的是**透传**下来的正文断行器
 * （`NormalFlowLayout.kt:746`），只钉轻路径会留下重轻不一致。
 * 换句话说：**变体只该改变行，不该改变列**。
 */
fun bodyParagraphBreaker(letterSpacingEm: Float): ParagraphBreaker =
    if (AbSwitch.inhouseBreak()) InhouseParagraphBreaker(letterSpacingEm)
    else SkiaParagraphBreaker(letterSpacingEm)

/**
 * **重路径 `BoxLayouter` 的断行器接线本体。生产与回归锁共用这一个口。**
 *
 * ## 为什么必须具名、而不是把表达式留在调用点
 *
 * 第一版的守卫锁在测试里**自己重写了一遍**这个表达式，于是它测的是「测试自己搭的接线」，
 * 不是生产接线：把 `BoxChapterLayouter:177` 的钉扎整段删掉，锁**照样绿**（变异验证才发现）。
 * 这类「测试与生产各写一份接线」的漂移，结构性解法只有一个——**同一个函数**。
 * 同 [bodyParagraphBreaker] 的「单源」理由，这里是第二层：单源保证三处不分家，
 * 具名接线保证生产与锁不分家。
 */
fun heavyPathBreaker(letterSpacingEm: Float): ParagraphBreaker =
    autoColumnMeasurePinnedToSkia(bodyParagraphBreaker(letterSpacingEm), letterSpacingEm)

/**
 * 把 auto 分列的**度量**钉回 Skia，断行仍走变体。**S3 接线的第三处配套**，不是可选项。
 *
 * ## 为什么必须有它（接线时漏了、被回归锁逼出来的）
 *
 * 表格 auto 分列在**重路径**里并不用 `BoxChapterLayouter.tableBreaker`，而是把 `BoxLayouter`
 * 收到的那个断行器一路透传进 [orilumn.reader.engine.laying.NormalFlowLayout.buildTableRows]
 * 的 `tableCellPref`（`NormalFlowLayout.kt:746`）。而 `BoxChapterLayouter:177` 喂进去的正是
 * 变体断行器 —— 所以**正文一接线，重路径的表格列宽就跟着变体走了**，轻路径却还钉在 Skia。
 * 两个后果：
 *
 * 1. **重轻不一致**：开到自建时，同一张表在重路径（变体）与轻路径（Skia）分列不同，
 *    破坏本仓反复钉过的那条重轻等价不变量。
 * 2. **自建侧的度量是桩**：`InhouseParagraphBreaker` 没有覆写 [ParagraphBreaker.preferredWidth]，
 *    用的是接口默认 `text.length * fontSizePx`。实测 fs=44.4 下 Latin/URL 高估
 *    **2.0~2.6 倍**（CJK 恰好 1.000 是汉字 advance 正好 1em 的巧合）。经
 *    `TableGridModel.autoColumnLayout` 的 `avail>=totalMax` 段 `w[i]=pref[i]` 直通成最终列宽，
 *    于是 Latin 列会宽出一倍。
 *
 * ## 为什么用包装而不是给 `NormalFlowLayout` 加第二个断行器形参
 *
 * `buildBoxTree` 是递归的，把一个新形参穿到每一个 `buildTableRows` 调用点改动面过大。
 * 而语义上真正要说的是一句：**断行变体只该改变行，不该改变列**。
 * 生产侧 `preferredWidth`/`minContentWidth` 的唯一消费者就是 `tableCellPref`（已核），
 * 所以把这两个方法钉回 Skia、断行三个重载全部转发，与「加第二个形参」**完全等价**。
 *
 * ## 为什么三个 `breakLines` 重载都要覆写
 *
 * 接口里只有 10 参那个是抽象的，11 参（缩进）与 12 参（`fontRuns`/`baselineShifts`）都是
 * **向下委托**的默认实现。若只覆写 10 参，生产实际走的 12 参会一路降到 10 参 ——
 * `fontRuns` 与 `firstLineIndentPx` 双双丢失，行内换面段的量画一致直接破掉。
 * 这与 S2 冻结契约（三个重载都必须在语义上正确）直接冲突。
 */
internal fun autoColumnMeasurePinnedToSkia(
    body: ParagraphBreaker,
    letterSpacingEm: Float,
): ParagraphBreaker {
    val skia = SkiaParagraphBreaker(letterSpacingEm)
    return object : ParagraphBreaker {
        // —— 度量：钉回 Skia（auto 分列表列宽的唯一来源）——
        override fun preferredWidth(
            text: CharSequence, fontSizePx: Float, families: List<String>,
            weight: Int, italic: Boolean, monospace: Boolean, fontRuns: List<FontRun>,
        ): Float = skia.preferredWidth(text, fontSizePx, families, weight, italic, monospace, fontRuns)

        override fun minContentWidth(
            text: CharSequence, fontSizePx: Float, families: List<String>,
            weight: Int, italic: Boolean, monospace: Boolean, fontRuns: List<FontRun>,
        ): Float = skia.minContentWidth(text, fontSizePx, families, weight, italic, monospace, fontRuns)

        // —— 断行：逐个重载转发给变体（不能靠默认委托，见上文）——
        override fun breakLines(
            text: CharSequence, fontSizePx: Float, lineHeightRatio: Float, widthPx: Int,
            alignment: TextAlign, tag: String?, families: List<String>,
            weight: Int, italic: Boolean, monospace: Boolean,
        ): List<BrokenLine> =
            body.breakLines(text, fontSizePx, lineHeightRatio, widthPx, alignment, tag, families, weight, italic, monospace)

        override fun breakLines(
            text: CharSequence, fontSizePx: Float, lineHeightRatio: Float, widthPx: Int,
            alignment: TextAlign, tag: String?, families: List<String>,
            weight: Int, italic: Boolean, monospace: Boolean, firstLineIndentPx: Float,
        ): List<BrokenLine> =
            body.breakLines(text, fontSizePx, lineHeightRatio, widthPx, alignment, tag, families, weight, italic, monospace, firstLineIndentPx)

        override fun breakLines(
            text: CharSequence, fontSizePx: Float, lineHeightRatio: Float, widthPx: Int,
            alignment: TextAlign, tag: String?, families: List<String>,
            weight: Int, italic: Boolean, monospace: Boolean, firstLineIndentPx: Float,
            fontRuns: List<FontRun>, baselineShifts: List<BaselineShift>,
        ): List<BrokenLine> =
            body.breakLines(text, fontSizePx, lineHeightRatio, widthPx, alignment, tag, families, weight, italic, monospace, firstLineIndentPx, fontRuns, baselineShifts)
    }
}
