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
 * ## 变体默认是自建（2026-10-01）
 *
 * 接线时默认 off（Skia 生产、自建仅供 `ab="inhouseBreak=1"` 量测）。现改为默认 on：
 * 装包即跑自建断行器，Skia 侧降级为**回退阀**（真机 `--es ab "inhouseBreak=0"` 退回）。
 * 本函数**不需要改动一行** —— 默认值在 [AbSwitch] 里，真值单一，
 * 键（[orilumn.reader.engine.text.LayoutParamKey.fromProfile] 的默认形参）也读同一处，
 * 于是「键按哪侧算」与「实际按哪侧断行」不可能漂移。
 * 这正是当初把它收成单源工厂的原因：**换默认只改一个常量，不改三处接线。**
 *
 * ## 表格**也在**此列了（2026-10-02，TODO Q6 消解）
 *
 * 接线时表格 auto 分列的**列宽度量**刻意留在 Skia（轻路径恒 Skia 的 `tableBreaker` +
 * 重路径由 `heavyPathBreaker` 钉回），当时的理由是自建侧**没有真 `preferredWidth`** ——
 * 落回接口默认桩 `text.length * fontSizePx`，实测 fs=44.4 下 Latin/URL 高估 2.0~2.6 倍。
 *
 * 该前提已于 2026-10-02 解除：[InhouseParagraphBreaker.preferredWidth] 改接
 * `SkiaRunMeasurer.naturalWidth`（与断行**同一个 `advances` 单源**，只是不施加版心宽）。
 * 实测两侧差（fs=16 / 44.4，`STSong,serif`）：CJK / URL / code / 混排 **0.0000%**，
 * 含 kern 对的 Latin **+1.104%**（恒为一个 kern 对的像素量），方向**永不反向**。
 * `minContentWidth` 无需另写——接口默认实现本身就逐段调 `preferredWidth`，
 * 实测与 Skia 侧**逐值相等**（181.6404）。
 *
 * ⇒ `tableBreaker` 与 `autoColumnMeasurePinnedToSkia` 一并删除，本函数成为**唯一**断行器口；
 * 重路径 `BoxLayouter` 与轻路径 `LightPrepare`（表格测宽）由
 * `BoxChapterLayouter.breakerFor` 接到**同一个实例**上（缓存键 `(profile, 变体)`）。
 * 那句「变体只该改变行，不该改变列」如今改由「**两侧共用同一个实例**」保证，
 * 而不是由「把列宽钉回另一套实现」保证—— 后者才是当初重轻分叉的真正来源。
 * ⚠️ 教训（`docs/自建断行引擎-测试计划.md` 教训 ⑮）：「同一个函数」还不够，
 * 两处各调一次工厂时，改其中一处另一处的锁**照样绿**（实测 MUT-I 两轮 BUILD SUCCESSFUL）。
 *
 * ## 混排字距为什么也由这个开关决定（2026-10-02）
 *
 * [cjkLatinSpacingEm] 只在**自建**侧才传得进去：Skia 断行器（`SkParagraph`）自己量版心，
 * 本仓无法让它为「注入的间隙」预留宽度 —— 而绘制侧照样会按 [SkiaRunMeasurer.advances] 画出间隙
 * ⇒ 断行器以为放得下、画出来超出版心被裁（`NoLineExceedsContentWidthTest` 钉的硬约束）。
 * 故开关 off ⇒ 间隙**整条关掉**（量宽与绘制两侧同为 0，见 [orilumn.reader.engine.text.TypographicProfile.cjkLatinSpacingEmApplied]），
 * 不是「画了但没预留」。开关是回退阀，两侧行为必须一致地退干净。
 */
fun bodyParagraphBreaker(letterSpacingEm: Float, cjkLatinSpacingEm: Float = 0f): ParagraphBreaker =
    if (AbSwitch.inhouseBreak()) InhouseParagraphBreaker(letterSpacingEm, cjkLatinSpacingEm)
    else SkiaParagraphBreaker(letterSpacingEm)

