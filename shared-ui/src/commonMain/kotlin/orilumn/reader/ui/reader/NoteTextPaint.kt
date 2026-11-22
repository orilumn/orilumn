package orilumn.reader.ui.reader

import androidx.compose.ui.graphics.drawscope.DrawScope
import orilumn.reader.engine.skia.DrawLine
import orilumn.reader.engine.skia.LineWindowDrawer

/**
 * 旁注文字的落墨出口（用户层·渲染缝）：把自研引擎产出的行窗口画进覆盖层。
 *
 * 批注文字**不经过 Compose 文本栈**：断行（`NoteTextLayout.breakText`）、对齐（`LineAligner`）、
 * 字距 / 混排间距（`SkiaRunMeasurer`）与落墨（[LineWindowDrawer]）全部由同一个引擎完成。
 * 量宽与画字因此是同一套字形与字距，不会出现「摆位判整句放得下、画出来多折一行」。
 *
 * 绘制器是常驻实例、**只在 UI 线程使用**（覆盖层栅格在绘制相位重画）。它内部有对齐器 /
 * 落墨器 / kerning 表这些可变态缓存，不能与后台布点车道（`PageNoteLane` 自己持有一只）共用。
 */
internal val noteLineDrawer: LineWindowDrawer = LineWindowDrawer()

/**
 * 在覆盖层画布上画一段旁注文字。
 *
 * @param lines 引擎产出的行（`NoteTextLayout.drawLines` 的 `lines`，Y 从 0 起、xLeft 为 0）
 * @param leftPx/topPx 文本块左上角（页面坐标）
 * @param widthPx/heightPx 文本块尺寸（整数像素；与布点量宽时同一个取整口径）
 */
internal expect fun DrawScope.drawNoteTextLines(
    lines: List<DrawLine>,
    leftPx: Float,
    topPx: Float,
    widthPx: Int,
    heightPx: Int,
)
