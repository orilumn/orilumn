package orilumn.reader.engine.skia

import orilumn.reader.engine.AbSwitch
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
 * ## 表格不在此列（TODO Q6）
 *
 * 表格 auto 分列度量（`BoxChapterLayouter.tableBreaker`）**刻意留在 Skia**：
 * T2f 实测 13 本语料 49 张表全落在 `avail>=totalMax` 段，min-content 从不被读，
 * 这条线对断行器变体零响应；留在 Skia 让 A/B 只隔离正文变化。
 */
fun bodyParagraphBreaker(letterSpacingEm: Float): ParagraphBreaker =
    if (AbSwitch.inhouseBreak()) InhouseParagraphBreaker(letterSpacingEm)
    else SkiaParagraphBreaker(letterSpacingEm)
