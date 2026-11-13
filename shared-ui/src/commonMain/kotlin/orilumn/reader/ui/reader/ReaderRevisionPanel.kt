package orilumn.reader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import orilumn.reader.data.revision.TextRevision
import orilumn.reader.engine.revision.CharDiff
import orilumn.reader.engine.revision.ReviewOverlay
import orilumn.reader.engine.revision.RevisionBlocks

/**
 * 编辑清单的列表体（用户层，v3.2 的清单形态）：由 [ReaderSidePanel] 的抽屉壳承载，
 * 与目录 / 笔记同一个抽屉的第三页「编辑」。
 *
 * 按书内章序、章内起点升序平铺（不画章标题）：一条修订**一行**，就是它在正文审阅视图里
 * 长的那一段——原文照排，删掉的字红字 + 删除线，新写的字绿字，中间没动的字照常。
 * 一行排不下就折到第二行，再多用省略号收住。
 * 文字取提交时存下的整段原文 / 新文字，与内部「最小修订」无关：就算只加了几个字，
 * 被选中的那段原文也照样在行首照排。老修订会在首次打开清单时按原书补回原文（区间非空的行）；
 * 补不回的只可能是提交时选区已被裁光的老行，这时显示当时改成的文字。
 * 点行跳到该修订的起点（宿主负责原书→渲染换算）；每行左滑露出「删除」按钮，删掉的
 * 是那一条修订本身，该区间随即恢复原书文字（与笔记清单同一套手势物理）。
 *
 * 键盘沿用面板共享的 [PanelNav]（↑↓ + Enter），与目录 / 笔记同口径。
 */
@Composable
fun ReaderRevisionPanel(
    scheme: String,
    /** 读全书修订：**null = 读取失败**（与「真没有修改」必须分开显示）。 */
    load: suspend () -> List<TextRevision>?,
    onDelete: suspend (TextRevision) -> Boolean,
    currentChapter: Int,
    onJump: (TextRevision) -> Unit,
    onDismiss: () -> Unit,
    /** 宿主缓存的整本修订快照：给了就**首帧**直接排列表，抽屉不会先滑出半屏空白。 */
    initial: List<TextRevision>? = null,
    /** 「全部恢复原文」：成功返回 true。null = 宿主不支持，按钮不出现。 */
    onRestoreAll: (suspend () -> Boolean)? = null,
    /**
     * 一条修订在**审阅空间**里的样子（宿主口，与正文审阅视图同一份数据）。
     *
     * 给了就按它排行摘要——清单画出来的就是这条修订在审阅视图里长的那一段（绿 `**`、划掉的
     * 红 `## `、¶ 都在）；null / 返回 null（本章结构未载入、老数据没有原文投影）时退回
     * 「原文 / 改文」快照做字符级对齐。
     */
    excerptOf: ((TextRevision) -> ReviewOverlay.Excerpt?)? = null,
) {
    val p = paletteFor(scheme)
    var loaded by remember { mutableStateOf(initial) }
    // 读取失败是**独立状态**：以前回退成空表，读者看到「还没有修改过正文」，以为记录丢了。
    var loadFailed by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadTick by remember { mutableStateOf(0) }
    // 「全部恢复」是破坏性操作：点一下只展开确认条，再点「恢复」才真删。
    var confirmRestoreAll by remember { mutableStateOf(false) }
    // 左滑展开的行键：同时只展开一行（SwipeRevealRow 的手势物理与它共用一套口径）。
    var openKey by remember { mutableStateOf<String?>(null) }
    val currentLoad by rememberUpdatedState(load)

    // 列表体随抽屉打开而组合，所以首次组合就是「打开」；切到别的页签会卸载，切回来重新读。
    // 已有快照时（首帧就有内容）照样对齐一次库里的最新结果，只是不再让抽屉空着等它。
    LaunchedEffect(reloadTick) {
        loadFailed = false
        val fresh = try {
            currentLoad()
        } catch (c: CancellationException) {
            throw c
        } catch (_: Throwable) {
            null
        }
        if (fresh == null) {
            // 手里有旧快照就继续用（失败只是没刷新）；手里什么都没有才是真失败。
            loadFailed = loaded == null
        } else {
            if (fresh != loaded) loaded = fresh
        }
    }

    val all = loaded.orEmpty()
    val itemCount = all.size
    // 打开就落在当前阅读章的第一条：LazyColumn 直接从目标位起排，不再先排一屏第 0 条、
    // 再 scrollToItem 跳过去——那屏白排是打开清单最主要的卡顿来源（行高固定，跳过完全等价）。
    val firstInChapter = remember(all, currentChapter) {
        all.indexOfFirst { it.chapter == currentChapter }
    }
    val listState = remember(loaded != null) {
        LazyListState(firstVisibleItemIndex = (firstInChapter - 1).coerceAtLeast(0))
    }
    val scope = rememberCoroutineScope()
    val nav = remember { PanelNav() }
    val bodyFr = remember { FocusRequester() }
    var positioned by remember { mutableStateOf(false) }

    fun moveActive(dir: Int) = nav.move(dir, 1, itemCount, { true }) {
        scope.ensureListVisible(listState, it, dir)
    }

    fun activeRevision(): TextRevision? = nav.activeIdx?.let { all.getOrNull(it) }

    /**
     * 删一条修订：宿主删库并重排后，清单里同步移除；失败就地提示（行不消失）。
     * 与笔记清单同一口径：删完把焦点还给列表体，↑↓/Enter 继续可用。
     */
    fun delete(revision: TextRevision) {
        error = null
        scope.launch {
            val ok = try {
                onDelete(revision)
            } catch (c: CancellationException) {
                throw c
            } catch (_: Throwable) {
                false
            }
            if (ok) {
                val next = loaded.orEmpty().filterNot { it.id == revision.id }
                loaded = next
                val a = nav.activeIdx
                nav.activeIdx = when {
                    next.isEmpty() -> null
                    a == null -> null
                    a !in next.indices -> a.coerceIn(0, next.lastIndex)
                    else -> a
                }
                if (openKey == "rev:${revision.id}") openKey = null
                bodyFr.requestFocus()
            } else {
                error = "删除失败"
            }
        }
    }

    /**
     * 全部恢复原文：宿主一次删光并重排，清单随即清空。失败保留列表、就地提示。
     * 删完把焦点还给列表体（此时是空态，↑↓ 不再动作，Esc 照旧可关）。
     */
    fun restoreAll() {
        val action = onRestoreAll ?: return
        error = null
        confirmRestoreAll = false
        scope.launch {
            val ok = try {
                action()
            } catch (c: CancellationException) {
                throw c
            } catch (_: Throwable) {
                false
            }
            if (ok) {
                loaded = emptyList()
                nav.activeIdx = null
                openKey = null
                error = null
            } else {
                error = "恢复失败"
            }
        }
    }

    // 打开时把高亮落到当前阅读章的第一条修改（滚动位置已由初始下标就位，与笔记清单同口径）。
    LaunchedEffect(loaded) {
        if (loaded == null || positioned) return@LaunchedEffect
        positioned = true
        nav.activeIdx = if (firstInChapter >= 0) firstInChapter else all.indices.firstOrNull()
        nav.releaseHold()
        bodyFr.requestFocus()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(bodyFr)
            .focusTarget()
            .panelKeyEvents(
                onUp = { moveActive(-1) },
                onDown = { moveActive(1) },
                onEnter = { activeRevision()?.let(onJump) },
                onEscape = onDismiss,
            )
            // 点按消费：杂散点按不穿透到遮罩关闭层。
            .pointerInput(Unit) { detectTapGestures(onTap = {}) },
    ) {
        // 「全部恢复」只在真有修改、且宿主支持时出现；破坏性操作先展开确认条。
        if (onRestoreAll != null && all.isNotEmpty() && !confirmRestoreAll) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "全部恢复",
                    color = PanelGold,
                    fontSize = 13.sp,
                    modifier = Modifier.clickable { confirmRestoreAll = true },
                )
            }
        }
        if (confirmRestoreAll) {
            RevisionRestoreAllConfirm(
                palette = p,
                count = all.size,
                onConfirm = ::restoreAll,
                onCancel = { confirmRestoreAll = false },
            )
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                loadFailed && loaded == null -> Column(modifier = Modifier.padding(24.dp)) {
                    Text("读取失败", color = p.muted, fontSize = 14.sp)
                    Text(
                        "点这里重试",
                        color = PanelGold,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .clickable { reloadTick++ },
                    )
                }

                loaded == null -> Text(
                    "加载中…", color = p.muted2, fontSize = 14.sp,
                    modifier = Modifier.padding(24.dp),
                )

                all.isEmpty() -> Text(
                    "还没有修改过正文",
                    color = p.muted2, fontSize = 14.sp,
                    modifier = Modifier.padding(24.dp),
                )

                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().clearKbHoldOnMove { nav.releaseHold() },
                ) {
                    items(all.size, key = { "r${all[it].id}" }) { i ->
                        val revision = all[i]
                        RevisionListRow(
                            revision = revision,
                            excerpt = excerptOf?.let { of -> remember(revision) { of(revision) } },
                            active = nav.activeIdx == i,
                            palette = p,
                            nav = nav,
                            index = i,
                            openKey = openKey,
                            onOpenChange = { openKey = it },
                            onJump = {
                                openKey = null
                                onJump(revision)
                            },
                            onDelete = { delete(revision) },
                        )
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

/** 「全部恢复」的确认条：破坏性操作不给一键误触的机会。 */
@Composable
private fun RevisionRestoreAllConfirm(
    palette: Palette,
    count: Int,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.rowActive)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "把 $count 条修改全部恢复成原文？",
            color = palette.text,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        Text(
            "恢复",
            color = Color(REVIEW_DELETED_ARGB),
            fontSize = 13.sp,
            modifier = Modifier.padding(start = 12.dp).clickable(onClick = onConfirm),
        )
        Text(
            "取消",
            color = palette.muted,
            fontSize = 13.sp,
            modifier = Modifier.padding(start = 12.dp).clickable(onClick = onCancel),
        )
    }
}

/**
 * 一条修订的摘录：换行与连续空白折成单个空格（清单是一条流式文字，不撑成多段）；
 * 段界痕迹字符（`U+E000`）在正文里不显字，这里也折成空格。
 */
internal fun revisionExcerpt(text: String): String =
    text.replace(WhitespaceRun, " ").trim()

private val WhitespaceRun = Regex("[\\s\\u00A0\\uE000]+")

/** 清单一行最多排两行字，摘录截到这么长就够：再长也只是被省略号吞掉，白白多排几千个字。 */
private const val ExcerptMaxChars = 200

/** 摘要里一段字的身份：没动的原文 / 被删掉的原文 / 新写进去的字。 */
internal enum class ReviewSpanKind { Kept, Deleted, Inserted }

/** 摘要里连续同身份的一段字。 */
internal data class ReviewSpan(val text: String, val kind: ReviewSpanKind)

/**
 * 一条修订在清单里的单行审阅摘要：与正文审阅视图同口径——原文照排，只有**真正变了**的字
 * 才上色（删掉的字红字 + 删除线，新写的字绿字），中间没动的字照常。
 *
 * 比对的两端取自提交时存下的整段原文 / 新文字（[TextRevision.originalText] /
 * [TextRevision.editedText]），按字符对齐后拼成一串。加快照之前写下的老修订没有这两列，
 * 退回当时的替换文字（[TextRevision.text]）：区间非空的行补一句「（原文未记录）」占位，
 * 补不回选区的纯插入只显示新文字。
 */
internal fun revisionReviewSpans(
    revision: TextRevision,
    /** 宿主给的审阅空间那一段（与正文审阅视图同一份数据）；null = 取不到。 */
    excerpt: ReviewOverlay.Excerpt? = null,
): List<ReviewSpan> {
    // 有审阅空间那一段就以它为准：清单一行画出来的就是审阅视图里那一段，红 / 绿、¶、
    // 标记痕迹全在里面。只改了标记的修订（字一个没改）按快照做差异本来就是一片空白，
    // 只有这份数据画得出那对绿 `**`。
    if (excerpt != null) {
        val reviewed = reviewExcerptSpans(excerpt)
        if (reviewed.isNotEmpty()) return reviewed
    }
    // 只有两列都为空才是「加快照之前写的老修订」；新修订的改文为空就是纯删除。
    val legacy = revision.originalText.isEmpty() && revision.editedText.isEmpty()
    // 两端各自先截到摘要长度再对齐：被截掉的尾巴注定排不出来，不必让对齐算法为它多跑。
    val oldFlat = revisionExcerpt(revision.originalText).take(ExcerptMaxChars)
    val newFlat = revisionExcerpt(if (legacy) revision.text else revision.editedText)
        .take(ExcerptMaxChars)

    val out = ArrayList<ReviewSpan>(3)
    if (oldFlat.isEmpty()) {
        when {
            // 选区里只有空白（空格 / 换行）：有快照，只是折叠后看不见。
            revision.originalText.isNotEmpty() -> out += ReviewSpan("（空白）", ReviewSpanKind.Kept)
            // 区间非空却没有原文快照：正文里那块字也是被换掉的，照样写成删除。
            revision.originalLength > 0 -> out += ReviewSpan("（原文未记录）", ReviewSpanKind.Deleted)
        }
    }
    for (op in CharDiff.diff(oldFlat, newFlat)) {
        val span = when (op) {
            is CharDiff.Op.Keep -> ReviewSpan(
                oldFlat.substring(op.oldStart, op.oldStart + op.length),
                ReviewSpanKind.Kept,
            )
            is CharDiff.Op.Delete -> ReviewSpan(
                oldFlat.substring(op.oldStart, op.oldStart + op.length),
                ReviewSpanKind.Deleted,
            )
            is CharDiff.Op.Insert -> ReviewSpan(op.text, ReviewSpanKind.Inserted)
        }
        if (span.text.isEmpty()) continue
        val last = out.lastOrNull()
        // 相邻同身份的字并成一段：减少文本样式区间，也让「整段删除」只有一段。
        if (last != null && last.kind == span.kind) {
            out[out.lastIndex] = last.copy(text = last.text + span.text)
        } else {
            out += span
        }
    }
    return truncateSpans(out)
}

/**
 * 审阅空间里的一段（[ReviewOverlay.Excerpt]）→ 清单行摘要：与正文审阅视图同一套颜色。
 *
 * 段界（[RevisionBlocks.SEPARATOR]）不占字符、不显示；换行与连续空白折成一个空格（清单是一条
 * 流式文字）；其余照抄——红字带删除线、绿字是读者新写的、¶ 与标记痕迹照排。多出来的截掉，
 * 与快照那条路同一个上限（两行排不下）。
 */
internal fun reviewExcerptSpans(excerpt: ReviewOverlay.Excerpt): List<ReviewSpan> {
    val text = excerpt.text
    if (text.isEmpty()) return emptyList()
    // 每个字的身份：0 = 原文照排，1 = 红字（删掉 / 换掉），2 = 绿字（新写 / 标记痕迹）。
    val kinds = ByteArray(text.length)
    fun paint(spans: IntArray, kind: Byte) {
        for (k in spans.indices step 2) {
            val s = spans[k].coerceIn(0, text.length)
            val e = spans[k + 1].coerceIn(s, text.length)
            for (i in s until e) kinds[i] = kind
        }
    }
    paint(excerpt.deleted, 1)
    paint(excerpt.inserted, 2)

    val chars = StringBuilder(text.length)
    val kindsOfChars = ArrayList<Int>(text.length)
    var prevSpace = false
    for (i in text.indices) {
        val c = text[i]
        // 段界（不占字符）与换行、连续空白一样折成一个空格：清单是一条流式文字。
        if (c == RevisionBlocks.SEPARATOR || c.isWhitespace() || c == '\u00A0') {
            if (prevSpace) continue
            chars.append(' ')
            kindsOfChars += kinds[i].toInt()
            prevSpace = true
        } else {
            chars.append(c)
            kindsOfChars += kinds[i].toInt()
            prevSpace = false
        }
    }
    var from = 0
    var to = chars.length
    while (from < to && chars[from] == ' ') from++
    while (to > from && chars[to - 1] == ' ') to--

    val out = ArrayList<ReviewSpan>(4)
    var i = from
    while (i < to) {
        var j = i
        while (j < to && kindsOfChars[j] == kindsOfChars[i]) j++
        val kind = when (kindsOfChars[i]) {
            1 -> ReviewSpanKind.Deleted
            2 -> ReviewSpanKind.Inserted
            else -> ReviewSpanKind.Kept
        }
        val piece = chars.substring(i, j)
        val last = out.lastOrNull()
        // 相邻同身份的字并成一段（折空白后同一侧的字仍挨在一起）。
        if (last != null && last.kind == kind) {
            out[out.lastIndex] = last.copy(text = last.text + piece)
        } else {
            out += ReviewSpan(piece, kind)
        }
        i = j
    }
    return truncateSpans(out)
}

/** 只留前 [ExcerptMaxChars] 个字：两行排不下，多出来的注定被省略号吞掉。 */
private fun truncateSpans(spans: List<ReviewSpan>): List<ReviewSpan> {
    var left = ExcerptMaxChars
    val out = ArrayList<ReviewSpan>(spans.size)
    for (s in spans) {
        if (left <= 0) break
        if (s.text.length <= left) {
            out += s
            left -= s.text.length
        } else {
            out += s.copy(text = s.text.take(left))
            left = 0
        }
    }
    return out
}


/**
 * 一条修订：一行审阅摘要——原文照排，删掉的字红字 + 删除线，新写的字绿字。
 * 最多折两行，再多用省略号；与正文审阅视图同一套颜色。
 */
@Composable
private fun RevisionListRow(
    revision: TextRevision,
    /** 这条修订在审阅空间里的样子（与审阅视图同一份数据）；null = 取不到，按快照对齐。 */
    excerpt: ReviewOverlay.Excerpt?,
    active: Boolean,
    palette: Palette,
    nav: PanelNav,
    index: Int,
    /** 当前展开左滑按钮的行键（同时只展开一行，见 [SwipeRevealRow]）。 */
    openKey: String?,
    onOpenChange: (String?) -> Unit,
    onJump: () -> Unit,
    onDelete: () -> Unit,
) {
    val clickSrc = remember { MutableInteractionSource() }
    val spans = remember(revision, excerpt) { revisionReviewSpans(revision, excerpt) }
    val line = remember(spans) {
        buildAnnotatedString {
            for (span in spans) {
                when (span.kind) {
                    ReviewSpanKind.Kept -> append(span.text)
                    ReviewSpanKind.Deleted -> withStyle(
                        SpanStyle(
                            color = Color(REVIEW_DELETED_ARGB),
                            textDecoration = TextDecoration.LineThrough,
                        ),
                    ) { append(span.text) }
                    ReviewSpanKind.Inserted -> withStyle(
                        SpanStyle(color = Color(REVIEW_INSERTED_ARGB)),
                    ) { append(span.text) }
                }
            }
        }
    }
    SwipeRevealRow(
        rowKey = "rev:${revision.id}",
        openKey = openKey,
        onOpenChange = onOpenChange,
        modifier = Modifier
            .background(if (active) palette.rowActive else Color.Transparent)
            .panelHover(nav, index),
        action = SwipeAction("删除", SwipeActionTone.Delete, onDelete),
    ) {
        Text(
            text = line,
            color = palette.text,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(interactionSource = clickSrc, indication = null, onClick = onJump)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}
