package orilumn.reader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * 正文旁的批注文字（用户层覆盖层，D-26）。
 *
 * 批注是高亮 / 划线记录上附带的一句话，所以它显示在**那条墨迹旁边**：读者不必打开清单
 * 才知道这条标注写了什么。文字块由 [noteBubblePlacements] 就近找空白放，**不改排版、
 * 不进栅格**——它只是盖在页面之上的一层，翻页即随页重组（[ReaderScreen] 在翻页动画期间隐藏它）。
 *
 * @property annotationId 这条批注属于哪条标注（点文字块 = 回去改这句话）。
 * @property text 批注正文（完整文本，超出可用格数由 Text 自己省略）。
 * @property leftPx/topPx 页面坐标（屏幕像素）。
 * @property widthPx 可用宽度（像素）。
 * @property maxLines 这块空白放得下几行（1 起）。
 * @property argb 所属标注的墨色：文字块用它做淡底与左侧色条，读者一眼看出是哪一条。
 * @property fontPx 批注字号（像素）：比正文小一号，读起来不抢正文。
 */
internal data class NoteBubble(
    val annotationId: Long,
    val text: String,
    val leftPx: Float,
    val topPx: Float,
    val widthPx: Float,
    val maxLines: Int,
    val argb: Int,
    val fontPx: Float,
)

/** 旁注字号相对正文的比例：小一号看得清又不抢正文。 */
internal const val NOTE_BUBBLE_FONT_RATIO: Float = 0.72f

/** 旁注行高相对自身字号的比例（与正文行高无关：旁注自己排自己的）。 */
internal const val NOTE_BUBBLE_LINE_RATIO: Float = 1.38f

/**
 * 一条批注在**本页**的画布事实（用户层 → 旁注层的输入）。
 *
 * [rects] 是这条标注在本页的墨迹矩形（页坐标，由测度引擎逐字算出，与画出来的字同源）：
 * 空 = 本页没有它的墨迹（在别的页上），也就没有「旁边」可言。
 */
internal data class NoteInk(
    val annotationId: Long,
    val text: String,
    val argb: Int,
    val rects: List<Rect>,
)

/**
 * 就近给每条批注找一块空白（纯函数，无 Compose 依赖，便于单测）。
 *
 * 候选顺序 = 读者视线的自然顺序：
 * 1. **锚点末行的右侧**（这句话后面正好空着一截，最贴合「旁批」的读法）；
 * 2. **锚点下方的行间空隙**（多半是段落之间的间距，旁注顺着段落躺下）；
 * 3. **本页正文之下的页脚空白**（页面没排满时）；
 * 4. **左右页边距**（整页排满时的兜底：宽度不够也要显示，至少一个字加省略号）。
 *
 * 任何候选只要压到正文墨迹或已放好的旁注就作废，落到下一个；四个候选都不成立就**不显示**
 * （宁可看不见，也不把正文糊掉——要看全文随时可以打开清单）。
 *
 * **只认墨迹，不认页码**：传进来的 [noteInk] 已经是「本页有墨迹的那几条」，
 * 所以跨页的标注只会在真有字的那一页上找位置——哪页有地方就放哪页。
 *
 * @param noteInk 本页上有墨迹的批注（`note` 非空；[NoteInk.rects] 为本页墨迹矩形）。
 * @param pageInk 本页正文墨迹矩形（页坐标）：旁注不能压到它们。
 * @param contentLeft/contentRight/contentBottom 可视内容区（页坐标）。
 * @param pageRight 页面右边界（页宽）：页边距兜底时用它算边距宽度。
 * @param fontPx 正文主字号（像素）：旁注按 [NOTE_BUBBLE_FONT_RATIO] 缩一号。
 */
internal fun noteBubblePlacements(
    noteInk: List<NoteInk>,
    pageInk: List<Rect>,
    contentLeft: Float,
    contentRight: Float,
    contentBottom: Float,
    pageRight: Float,
    fontPx: Float,
): List<NoteBubble> {
    if (noteInk.isEmpty() || pageInk.isEmpty()) return emptyList()
    val body = fontPx.coerceAtLeast(1f)
    val small = body * NOTE_BUBBLE_FONT_RATIO
    val lineH = small * NOTE_BUBBLE_LINE_RATIO
    val charW = small * 1.05f
    val gap = body * 0.25f
    // 「至少一个字加省略号」：再窄的格子也得容得下一个字和省略号。
    val minWidth = charW * 1.9f

    val placed = mutableListOf<Rect>()
    val out = mutableListOf<NoteBubble>()

    fun offer(x: Float, y: Float, w: Float, maxLines: Int): Boolean {
        if (maxLines < 1 || w < minWidth) return false
        val rect = Rect(x, y, x + w, y + maxLines * lineH)
        if (rect.bottom > contentBottom + 0.5f) return false
        if (rect.right > pageRight + 0.5f) return false
        // 压字或压已放好的旁注都不行。
        if (pageInk.any { it.overlaps(rect) }) return false
        if (placed.any { it.overlaps(rect) }) return false
        placed += rect
        return true
    }

    for (note in noteInk.sortedBy { it.rects.minOfOrNull { r -> r.top } ?: 0f }) {
        val text = note.text.trim()
        if (text.isEmpty()) continue
        // 锚点不在本页（在别的页上）：本页没有它的墨迹，也就没有「旁边」。
        if (note.rects.isEmpty()) continue
        val first = note.rects.minByOrNull { it.top } ?: continue
        val last = note.rects.maxByOrNull { it.bottom } ?: continue

        fun place(x: Float, y: Float, w: Float, maxLines: Int): Boolean {
            if (!offer(x, y, w, maxLines)) return false
            out += NoteBubble(
                annotationId = note.annotationId,
                text = text,
                leftPx = x,
                topPx = y,
                widthPx = w,
                maxLines = maxLines,
                argb = note.argb,
                fontPx = small,
            )
            return true
        }

        // 1) 锚点末行右侧：与这一行同高的一格，视觉上就是「这句话的旁批」。
        val rightW = contentRight - (last.right + gap)
        if (place(last.right + gap, last.top, rightW, 1)) continue
        // 2) 锚点下方到下一块墨迹之间的空隙（段落间距多半藏在这里）。
        val nextTop = pageInk.asSequence()
            .filter { it.top > last.bottom + 0.5f }
            .minOfOrNull { it.top }
            ?: contentBottom
        val belowAvail = nextTop - (last.bottom + gap) - gap
        if (belowAvail >= lineH * 0.9f) {
            val rows = (belowAvail / lineH).toInt().coerceAtLeast(1)
            if (place(first.left, last.bottom + gap, contentRight - first.left, rows)) continue
        }
        // 3) 页脚空白：本页最后一行之下。
        val lastTextBottom = pageInk.maxOf { it.bottom }
        val footAvail = contentBottom - lastTextBottom - gap * 2
        if (footAvail >= lineH * 0.9f) {
            val rows = (footAvail / lineH).toInt().coerceAtLeast(1)
            if (place(contentLeft, lastTextBottom + gap, contentRight - contentLeft, rows)) continue
        }
        // 4) 页边距兜底：整页排满时贴着锚点所在行，能挤几个字是几个字。
        val rightMargin = pageRight - contentRight - gap * 2
        if (rightMargin >= charW * 2f &&
            place(contentRight + gap, first.top, rightMargin, 2)
        ) {
            continue
        }
        val leftMargin = contentLeft - gap * 2
        if (leftMargin >= charW * 2f) {
            val rows = 2
            if (place(gap, first.top, leftMargin, rows)) continue
        }
        // 四个候选都不成立：这一页真的没地方，宁可不显示。
    }
    return out
}

/**
 * 批注文字覆盖层：把 [noteBubblePlacements] 的结果画在页面之上。
 *
 * 点文字块 = 打开这条标注的批注输入条（[onOpen]）。它盖在正文之上、亮度遮罩之下，
 * 翻页动画期间由调用方隐藏（与选区覆盖层同口径）。
 */
@Composable
internal fun NoteBubbles(
    bubbles: List<NoteBubble>,
    fgColor: Color,
    onOpen: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (bubbles.isEmpty()) return
    val d = LocalDensity.current
    Box(modifier = modifier) {
        bubbles.forEach { b ->
            val tint = Color(b.argb)
            Box(
                modifier = Modifier
                    // 布局位移（不是动画）：位置只跟页面几何走，翻页时整层一起让位。
                    .offset { IntOffset(b.leftPx.roundToInt(), b.topPx.roundToInt()) }
                    .width(with(d) { b.widthPx.toDp() })
                    .heightIn(max = with(d) { (b.maxLines * b.fontPx * NOTE_BUBBLE_LINE_RATIO).toDp() })
                    .clip(RoundedCornerShape(3.dp))
                    .background(tint.copy(alpha = 0.16f))
                    .border(1.dp, tint.copy(alpha = 0.5f), RoundedCornerShape(3.dp))
                    .clickable { onOpen(b.annotationId) },
            ) {
                Text(
                    text = b.text,
                    color = fgColor,
                    fontSize = with(d) { b.fontPx.toSp() },
                    lineHeight = with(d) { (b.fontPx * NOTE_BUBBLE_LINE_RATIO).toSp() },
                    maxLines = b.maxLines,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
    }
}
