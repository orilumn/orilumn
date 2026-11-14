package orilumn.reader.engine.skia

import orilumn.reader.engine.laying.ParagraphBreaker

/**
 * **正文断行器的唯一入口**（排版层·上 → 渲染层实现侧的接缝）。
 *
 * ## 为什么要单源
 *
 * 正文断行在生产里有三个接线点，形状不同、位置分散：
 * [orilumn.reader.engine.BoxChapterLayouter] 重路径整章塑形、
 * 同文件轻路径浮动 lead 度量、以及 `ShapeGeometry` 的叶级 `breakWrappedLines`。
 * 若各处各自构造断行器，任何一侧漏改就是**两侧分家** ——
 * 量宽用一套、绘制按另一套断点，症状是「同一段文字高度随翻页方式变化」这类
 * 极难归因的漂移。故三处一律调本函数。
 *
 * ## 表格也在此列（2026-10-02，TODO Q6 消解）
 *
 * 表格 auto 分列的**列宽度量**曾经刻意另钉一套实现，理由是自建侧**没有真 `preferredWidth`** ——
 * 落回接口默认桩 `text.length * fontSizePx`，实测 fs=44.4 下 Latin/URL 高估 2.0~2.6 倍。
 *
 * 该前提已解除：[InhouseParagraphBreaker.preferredWidth] 接
 * `SkiaRunMeasurer.naturalWidth`（与断行**同一个 `advances` 单源**，只是不施加版心宽）。
 * 实测两侧差（fs=16 / 44.4，`STSong,serif`）：CJK / URL / code / 混排 **0.0000%**，
 * 含 kern 对的 Latin **+1.104%**（恒为一个 kern 对的像素量），方向**永不反向**。
 *
 * ⇒ 本函数成为**唯一**断行器口：重路径 `BoxLayouter` 与轻路径 `LightPrepare`（表格测宽）由
 * `BoxChapterLayouter.breakerFor` 接到**同一个实例**上（缓存键 `(profile, 字距)`）。
 * 那句「变体只该改变行，不该改变列」如今由「**两侧共用同一个实例**」保证。
 * ⚠️ 教训（`docs/自建断行引擎-测试计划.md` 教训 ⑮）：「同一个函数」还不够，
 * 两处各调一次工厂时，改其中一处另一处的锁**照样绿**（实测 MUT-I 两轮 BUILD SUCCESSFUL）。
 */
fun bodyParagraphBreaker(letterSpacingEm: Float = 0f, cjkLatinSpacingEm: Float = 0f): ParagraphBreaker =
    InhouseParagraphBreaker(letterSpacingEm, cjkLatinSpacingEm)
