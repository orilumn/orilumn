package orilumn.reader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import orilumn.reader.data.annotation.Annotation
import orilumn.reader.data.annotation.AnnotationKind
import orilumn.reader.data.epub.TocItem
import orilumn.reader.engine.skia.ANNOTATION_DASH_OFF_RATIO
import orilumn.reader.engine.skia.ANNOTATION_DASH_ON_RATIO
import orilumn.reader.engine.skia.ANNOTATION_STRIKE_OFFSET_EM
import orilumn.reader.engine.skia.ANNOTATION_UNDERLINE_OFFSET_EM
import orilumn.reader.engine.skia.ANNOTATION_UNDERLINE_THICKNESS_EM
import orilumn.reader.engine.skia.DecorKind
import orilumn.reader.engine.skia.DecorStroke
import orilumn.reader.engine.skia.decorWaveGeometry

/**
 * 笔记清单的列表体（用户层，D-23）：由 [ReaderSidePanel] 的抽屉壳承载，与目录同一抽屉。
 *
 * 入参只收「读 / 删 / 跳」三个动作与章标题表，加载逻辑自己管（平台壳不写）；
 * 按书内章序分组、章内按区间起点升序（与 `annotationsForChapter` 同序）。
 * 行内容 = 两行原文摘录（可空回退「（无摘录）」）+ 章标题分组头 + 行尾删除；
 * 摘录按标注本来的颜色与线型直接画在字上（与正文同口径），不另加类别图标。
 *
 * 键盘沿用面板共享的 [PanelNav]（↑↓ + Enter）；页签切换先只做点按（D-23 §9）。
 */
@Composable
fun ReaderAnnotationPanel(
    scheme: String,
    load: suspend () -> List<Annotation>,
    onDelete: suspend (Annotation) -> Boolean,
    onJump: (Annotation) -> Unit,
    chapterTitles: Map<Int, String>,
    currentChapter: Int,
    onDismiss: () -> Unit,
) {
    val p = paletteFor(scheme)
    var loaded by remember { mutableStateOf<List<Annotation>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val currentLoad by rememberUpdatedState(load)

    // 列表体随抽屉打开而组合，所以首次组合就是「打开」；只在点行进正文时卸载。
    LaunchedEffect(Unit) {
        loaded = try {
            currentLoad()
        } catch (c: CancellationException) {
            throw c
        } catch (_: Throwable) {
            emptyList()
        }
    }

    val rows = remember(loaded, chapterTitles) { buildAnnotationRows(loaded.orEmpty(), chapterTitles) }
    val itemCount = remember(rows) { rows.count { it is AnnotationPanelRow.Item } }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val nav = remember { PanelNav() }
    val bodyFr = remember { FocusRequester() }
    var positioned by remember { mutableStateOf(false) }

    fun displayIndexOfSeq(seq: Int): Int =
        rows.indexOfFirst { it is AnnotationPanelRow.Item && it.seq == seq }

    fun clampActive() {
        val a = nav.activeIdx ?: return
        nav.activeIdx = when {
            itemCount == 0 -> null
            a !in 0 until itemCount -> a.coerceIn(0, itemCount - 1)
            else -> a
        }
    }

    fun moveActive(dir: Int) = nav.move(dir, 1, itemCount, { true }) {
        scope.ensureListVisible(listState, displayIndexOfSeq(it), dir)
    }

    // 打开时滚到当前阅读章所在组（与目录「定位当前章」同口径）；只在首次拿到数据时做一次，
    // 之后删一条引起的列表变化不再把滚动位置拽回来。
    LaunchedEffect(loaded) {
        if (loaded == null || positioned) return@LaunchedEffect
        positioned = true
        val header = rows.indexOfFirst { it is AnnotationPanelRow.Header && it.chapter == currentChapter }
        if (header >= 0) {
            try { listState.scrollToItem((header - 1).coerceAtLeast(0)) } catch (_: Exception) {}
            val firstItem = rows.drop(header).firstOrNull { it is AnnotationPanelRow.Item }
            if (firstItem is AnnotationPanelRow.Item) nav.activeIdx = firstItem.seq
        } else {
            nav.activeIdx = rows.firstOrNull { it is AnnotationPanelRow.Item }
                ?.let { (it as AnnotationPanelRow.Item).seq }
        }
        nav.releaseHold()
        bodyFr.requestFocus()
    }

    fun delete(annotation: Annotation) {
        error = null
        scope.launch {
            val ok = try {
                onDelete(annotation)
            } catch (c: CancellationException) {
                throw c
            } catch (_: Throwable) {
                false
            }
            if (ok) {
                loaded = loaded?.filterNot { it.id == annotation.id }
                clampActive()
                // 点删除按钮会把焦点从列表体抢走：还回去，↑↓/Enter 继续可用。
                bodyFr.requestFocus()
            } else {
                error = "删除失败"
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(bodyFr)
            .focusTarget()
            .panelKeyEvents(
                onUp = { moveActive(-1) },
                onDown = { moveActive(1) },
                onEnter = {
                    val a = nav.activeIdx
                    if (a != null) rows.firstOrNull { it is AnnotationPanelRow.Item && it.seq == a }
                        ?.let { onJump((it as AnnotationPanelRow.Item).annotation) }
                },
                onEscape = onDismiss,
            )
            // 点按消费：杂散点按不穿透到遮罩关闭层。
            .pointerInput(Unit) { detectTapGestures(onTap = {}) },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                loaded == null -> Text(
                    "加载中…", color = p.muted2, fontSize = 14.sp,
                    modifier = Modifier.padding(24.dp),
                )

                rows.isEmpty() -> Text(
                    "还没有高亮、划线或批注", color = p.muted2, fontSize = 14.sp,
                    modifier = Modifier.padding(24.dp),
                )

                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().clearKbHoldOnMove { nav.releaseHold() },
                ) {
                    items(rows.size, key = { i ->
                        when (val r = rows[i]) {
                            is AnnotationPanelRow.Header -> "h${r.chapter}"
                            is AnnotationPanelRow.Item -> "a${r.annotation.id}"
                        }
                    }) { i ->
                        when (val r = rows[i]) {
                            is AnnotationPanelRow.Header -> ChapterHeader(r.title, p)
                            is AnnotationPanelRow.Item -> AnnotationListRow(
                                annotation = r.annotation,
                                active = nav.activeIdx == r.seq,
                                palette = p,
                                nav = nav,
                                index = r.seq,
                                onJump = { onJump(r.annotation) },
                                onDelete = { delete(r.annotation) },
                            )
                        }
                    }
                }
            }
            error?.let {
                Text(
                    text = it,
                    color = Color(0xFFFFFFFF),
                    fontSize = 13.sp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xB3222222))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** 一枚清单行：分组头（章）或一条标注（[seq] = 键盘导航用的标注序）。 */
internal sealed interface AnnotationPanelRow {
    data class Header(val chapter: Int, val title: String) : AnnotationPanelRow
    data class Item(val annotation: Annotation, val seq: Int) : AnnotationPanelRow
}

/**
 * 按书内章序把全书标注排成「分组头 + 行」的扁平表；[annotations] 已按章序、章内起点升序。
 * 章标题缺项回退「第 N 章」（章号 +1，与宿主 `unitTitle` 的回退同口径）。
 */
internal fun buildAnnotationRows(
    annotations: List<Annotation>,
    chapterTitles: Map<Int, String>,
): List<AnnotationPanelRow> {
    val out = ArrayList<AnnotationPanelRow>(annotations.size + 8)
    var seq = 0
    var lastChapter = Int.MIN_VALUE
    for (a in annotations) {
        if (a.chapter != lastChapter) {
            lastChapter = a.chapter
            out.add(
                AnnotationPanelRow.Header(
                    chapter = a.chapter,
                    title = chapterTitles[a.chapter] ?: "第 ${a.chapter + 1} 章",
                ),
            )
        }
        out.add(AnnotationPanelRow.Item(a, seq++))
    }
    return out
}

/**
 * 目录树拍平成「章号 → 标题」（章标题 = 该章最先出现的目录项；子项不覆盖）。
 * 笔记清单的分组头与目录跳转共用同一份标题，缺项由面板回退「第 N 章」。
 */
fun chapterTitlesOf(toc: List<TocItem>): Map<Int, String> {
    val out = LinkedHashMap<Int, String>()
    fun walk(items: List<TocItem>) {
        for (t in items) {
            val idx = t.index
            if (idx != null && idx !in out) out[idx] = t.label
            walk(t.children)
        }
    }
    walk(toc)
    return out
}

@Composable
private fun ChapterHeader(title: String, palette: Palette) {
    Text(
        text = title,
        color = palette.muted2,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 6.dp),
    )
}

@Composable
private fun AnnotationListRow(
    annotation: Annotation,
    active: Boolean,
    palette: Palette,
    nav: PanelNav,
    index: Int,
    onJump: () -> Unit,
    onDelete: () -> Unit,
) {
    val clickSrc = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (active) palette.rowActive else Color.Transparent)
            .clickable(interactionSource = clickSrc, indication = null, onClick = onJump)
            .panelHover(nav, index)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            QuoteText(annotation, palette)
            val note = annotation.note?.takeIf { it.isNotBlank() }
            if (annotation.kind == AnnotationKind.Note && note != null) {
                Text(
                    text = note,
                    color = palette.muted2,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .padding(start = 8.dp)
                .size(30.dp)
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onDelete),
        ) {
            Text("✕", color = palette.muted2, fontSize = 14.sp)
        }
    }
}

/** 清单摘录的字号；装饰几何（线宽 / 线心偏移 / 波形）都按这个字号换算，与正文同一套口径。 */
private val QuoteFontSize = 13.sp

/** 一条标注在清单里该怎么画：高亮垫底色，划线按自己的线型与颜色画在字下/字中，批注不画装饰。 */
internal class QuoteDecor(
    val kind: DecorKind,
    val color: Color,
    val stroke: DecorStroke,
)

/**
 * 清单行的装饰**直接沿用正文那份投影**（[annotationDecorRanges]）：同一处颜色回退、
 * 同一处删除线换落墨位置、同一处批注不落墨。面板不再自己判一遍类型，
 * 才不会出现「清单里是虚线、正文里是波浪」这种两套口径。
 */
internal fun quoteDecorOf(annotation: Annotation): QuoteDecor? =
    annotationDecorRanges(listOf(annotation)).firstOrNull()?.let {
        QuoteDecor(kind = it.kind, color = Color(it.argb), stroke = it.stroke)
    }

/**
 * 摘录按标注**本来面貌**直接画在字上：高亮 = 同色底垫在文字后；划线 = 同色同线型画在文字后
 * （波浪用正文那一份 [decorWaveGeometry]）。不再在左侧另立类别图标——颜色和线型本身就是类别。
 */
@Composable
private fun QuoteText(annotation: Annotation, palette: Palette) {
    val quote = annotation.quote?.takeIf { it.isNotBlank() }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val decor = remember(annotation) { quoteDecorOf(annotation) }
    val em = with(LocalDensity.current) { QuoteFontSize.toPx() }
    Text(
        text = quote ?: "（无摘录）",
        color = if (quote == null) palette.muted2 else palette.text,
        fontSize = QuoteFontSize,
        lineHeight = 18.sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { layout = it },
        modifier = Modifier.drawWithContent {
            val lr = layout
            val d = decor
            val decorated = quote != null && lr != null && d != null
            if (decorated && d.kind == DecorKind.BACKGROUND) drawQuoteHighlight(lr, d.color)
            drawContent()
            if (decorated && d.kind != DecorKind.BACKGROUND) {
                drawQuoteLines(lr, d.kind, d.stroke, d.color, em)
            }
        },
    )
}

/** 高亮：逐行铺满该行行盒（与正文一致），四角不圆、不跨行合并。 */
private fun DrawScope.drawQuoteHighlight(lr: TextLayoutResult, color: Color) {
    for (i in 0 until lr.lineCount) {
        val left = lr.getLineLeft(i)
        val right = lr.getLineRight(i)
        val top = lr.getLineTop(i)
        val bottom = lr.getLineBottom(i)
        if (right <= left || bottom <= top) continue
        drawRect(color = color, topLeft = Offset(left, top), size = Size(right - left, bottom - top))
    }
}

/** 划线 / 删除线：线宽与线的位置按正文字号口径换算（[ANNOTATION_UNDERLINE_THICKNESS_EM] 等）。 */
private fun DrawScope.drawQuoteLines(
    lr: TextLayoutResult,
    kind: DecorKind,
    stroke: DecorStroke,
    color: Color,
    em: Float,
) {
    val thickness = (em * ANNOTATION_UNDERLINE_THICKNESS_EM).coerceAtLeast(1f)
    for (i in 0 until lr.lineCount) {
        val left = lr.getLineLeft(i)
        val right = lr.getLineRight(i)
        if (right <= left) continue
        val baseline = lr.getLineBaseline(i)
        val centerY = if (kind == DecorKind.STRIKETHROUGH) {
            baseline - em * ANNOTATION_STRIKE_OFFSET_EM
        } else {
            baseline + em * ANNOTATION_UNDERLINE_OFFSET_EM
        }
        when (stroke) {
            DecorStroke.Wavy -> drawQuoteWave(left, right, centerY, thickness, color)
            DecorStroke.Dashed -> drawLine(
                color = color,
                start = Offset(left, centerY),
                end = Offset(right, centerY),
                strokeWidth = thickness,
                cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(
                    floatArrayOf(
                        thickness * ANNOTATION_DASH_ON_RATIO,
                        thickness * ANNOTATION_DASH_OFF_RATIO,
                    ),
                ),
            )
            else -> drawRect(
                color = color,
                topLeft = Offset(left, centerY - thickness / 2f),
                size = Size(right - left, thickness),
            )
        }
    }
}

/**
 * 波浪线：与正文落墨**同一条曲线**——几何取 [decorWaveGeometry]，每个波长一段三次贝塞尔，
 * 起点早一个波长、横向裁到片段（Chromium 的 overhang + 裁剪口径）。
 */
private fun DrawScope.drawQuoteWave(
    left: Float,
    right: Float,
    centerY: Float,
    thickness: Float,
    color: Color,
) {
    val g = decorWaveGeometry(thickness)
    val path = Path().apply {
        val startX = left - g.wavelength
        moveTo(startX, centerY)
        var x = startX
        val endX = right + g.wavelength
        while (x < endX) {
            val xe = x + g.wavelength
            val cx = x + g.wavelength * 0.5f
            cubicTo(cx, centerY + g.controlOffset, cx, centerY - g.controlOffset, xe, centerY)
            x = xe
        }
    }
    val ext = g.halfHeight
    clipRect(left, centerY - ext, right, centerY + ext) {
        drawPath(path, color, style = Stroke(width = g.thickness.coerceAtLeast(1f)))
    }
}
