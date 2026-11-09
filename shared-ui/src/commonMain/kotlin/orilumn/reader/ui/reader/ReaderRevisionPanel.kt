package orilumn.reader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import orilumn.reader.data.revision.TextRevision

/**
 * 编辑清单的列表体（用户层，v3.2 的清单形态）：由 [ReaderSidePanel] 的抽屉壳承载，
 * 与目录 / 笔记同一个抽屉的第三页「编辑」。
 *
 * 按书内章序、章内起点升序平铺（不画章标题）：一条修订两行——上行是**改之前的整段选区**
 * （红色删除线），下行是**改之后的整段文字**。每行只占一行，超出用省略号。
 * 两行取提交时存下的原文 / 新文字，与内部「最小修订」无关：就算只加了几个字，
 * 上行也照样显示被选中的那段原文（加了删除线，读者不会误会）。纯删除没有下行。
 * 加两列之前写下的老修订会在首次打开清单时按原书补回原文（区间非空的行）；
 * 补不回的只可能是「只剩纯插入」的老行——提交时选区已被裁光，清单没有原文行，
 * 只显示当时改成的文字。
 * 点行跳到该修订的起点（宿主负责原书→渲染换算）；每行左滑露出「删除」按钮，删掉的
 * 是那一条修订本身，该区间随即恢复原书文字（与笔记清单同一套手势物理）。
 *
 * 键盘沿用面板共享的 [PanelNav]（↑↓ + Enter），与目录 / 笔记同口径。
 */
@Composable
fun ReaderRevisionPanel(
    scheme: String,
    load: suspend () -> List<TextRevision>,
    onDelete: suspend (TextRevision) -> Boolean,
    currentChapter: Int,
    onJump: (TextRevision) -> Unit,
    onDismiss: () -> Unit,
    /** 宿主缓存的整本修订快照：给了就**首帧**直接排列表，抽屉不会先滑出半屏空白。 */
    initial: List<TextRevision>? = null,
) {
    val p = paletteFor(scheme)
    var loaded by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    // 左滑展开的行键：同时只展开一行（SwipeRevealRow 的手势物理与它共用一套口径）。
    var openKey by remember { mutableStateOf<String?>(null) }
    val currentLoad by rememberUpdatedState(load)

    // 列表体随抽屉打开而组合，所以首次组合就是「打开」；切到别的页签会卸载，切回来重新读。
    // 已有快照时（首帧就有内容）照样对齐一次库里的最新结果，只是不再让抽屉空着等它。
    LaunchedEffect(Unit) {
        val fresh = try {
            currentLoad()
        } catch (c: CancellationException) {
            throw c
        } catch (_: Throwable) {
            loaded ?: emptyList()
        }
        if (fresh != loaded) loaded = fresh
    }

    val shown = remember(loaded) { loaded.orEmpty() }
    val itemCount = shown.size
    // 打开就落在当前阅读章的第一条：LazyColumn 直接从目标位起排，不再先排一屏第 0 条、
    // 再 scrollToItem 跳过去——那屏白排是打开清单最主要的卡顿来源（行高固定，跳过完全等价）。
    val firstInChapter = remember(shown, currentChapter) {
        shown.indexOfFirst { it.chapter == currentChapter }
    }
    val listState = remember(loaded != null) {
        LazyListState(firstVisibleItemIndex = (firstInChapter - 1).coerceAtLeast(0))
    }
    val scope = rememberCoroutineScope()
    val nav = remember { PanelNav() }
    val bodyFr = remember { FocusRequester() }
    var positioned by remember { mutableStateOf(false) }
    // 原文行：白天用深红、夜间用浅红，保证在两种底色上都看得清。
    val strike = if (scheme == "night") Color(0xFFE07364) else Color(0xFFC0392B)

    fun moveActive(dir: Int) = nav.move(dir, 1, itemCount, { true }) {
        scope.ensureListVisible(listState, it, dir)
    }

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

    // 打开时把高亮落到当前阅读章的第一条修改（滚动位置已由初始下标就位，与笔记清单同口径）。
    LaunchedEffect(loaded) {
        if (loaded == null || positioned) return@LaunchedEffect
        positioned = true
        nav.activeIdx = if (firstInChapter >= 0) firstInChapter else shown.indices.firstOrNull()
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
                onEnter = {
                    nav.activeIdx?.let { i -> shown.getOrNull(i)?.let(onJump) }
                },
                onEscape = onDismiss,
            )
            // 点按消费：杂散点按不穿透到遮罩关闭层。
            .pointerInput(Unit) { detectTapGestures(onTap = {}) },
    ) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                loaded == null -> Text(
                    "加载中…", color = p.muted2, fontSize = 14.sp,
                    modifier = Modifier.padding(24.dp),
                )

                shown.isEmpty() -> Text(
                    "还没有修改过正文", color = p.muted2, fontSize = 14.sp,
                    modifier = Modifier.padding(24.dp),
                )

                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().clearKbHoldOnMove { nav.releaseHold() },
                ) {
                    items(shown.size, key = { "r${shown[it].id}" }) { i ->
                        val revision = shown[i]
                        RevisionListRow(
                            revision = revision,
                            active = nav.activeIdx == i,
                            palette = p,
                            strike = strike,
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

/**
 * 一条修订的摘录：换行与连续空白折成单个空格（清单每行只显示一行，不撑成多段）。
 * 原文与改文用同一套折叠，保证上下两行口径一致。
 */
internal fun revisionExcerpt(text: String): String {
    val flat = text.replace(WhitespaceRun, " ").trim()
    return if (flat.length > ExcerptMaxChars) flat.take(ExcerptMaxChars) + "…" else flat
}

private val WhitespaceRun = Regex("[\\s\\u00A0]+")

/** 清单每行只画一行字，摘录截到这么长就够：再长也只是被省略号吞掉，白白多排几千个字。 */
private const val ExcerptMaxChars = 80

/** 清单一行的内容：上行原文可为空（不画这一行），下行改文空串表示纯删除。 */
internal data class RevisionRowLines(
    /** 上行：改之前的整段选区；`null` 表示这条修订没有原文行。 */
    val original: String?,
    /** 下行：改之后的整段文字；空串表示纯删除，不画这一行。 */
    val updated: String,
)

/**
 * 清单两行怎么取：优先用提交时存下的整段前后快照。
 *
 * 老修订（加快照之前写的）由宿主在打开清单时按原书补回原文；仍补不上的（只剩纯插入、
 * 或该章没有持久化结构）只要它确实替换过原书文字，就占着上行显示「（原文未记录）」，
 * 下行退回当时的替换文字。这样清单的形状一致，不会忽然少一行。
 */
internal fun revisionRowLines(revision: TextRevision): RevisionRowLines {
    // 只有两列都为空才是「加快照之前写的老修订」；新修订的改文为空就是纯删除，
    // 不能退回最小修订的替换文字（那可能只是没被删掉的旧改文）。
    val legacy = revision.originalText.isEmpty() && revision.editedText.isEmpty()
    val original = revisionExcerpt(revision.originalText)
    val updated = revisionExcerpt(if (legacy) revision.text else revision.editedText)
    val originalLine = when {
        original.isNotEmpty() -> original
        // 选区里只有空白（空格 / 换行）：有快照，只是折叠后看不见。
        revision.originalText.isNotEmpty() -> "（空白）"
        revision.originalLength > 0 -> "（原文未记录）"
        else -> null
    }
    return RevisionRowLines(originalLine, updated)
}

/**
 * 一条修订：上行原文（红色删除线）、下行改文；两行都只占一行，超出省略。
 * 原文行按「整段选区」画删除线（只加字也照画，不去算纯插入）；纯删除没有改文行。
 */
@Composable
private fun RevisionListRow(
    revision: TextRevision,
    active: Boolean,
    palette: Palette,
    strike: Color,
    nav: PanelNav,
    index: Int,
    /** 当前展开左滑按钮的行键（同时只展开一行，见 [SwipeRevealRow]）。 */
    openKey: String?,
    onOpenChange: (String?) -> Unit,
    onJump: () -> Unit,
    onDelete: () -> Unit,
) {
    val clickSrc = remember { MutableInteractionSource() }
    val lines = remember(revision) { revisionRowLines(revision) }
    SwipeRevealRow(
        rowKey = "rev:${revision.id}",
        openKey = openKey,
        onOpenChange = onOpenChange,
        modifier = Modifier
            .background(if (active) palette.rowActive else Color.Transparent)
            .panelHover(nav, index),
        action = SwipeAction("删除", SwipeActionTone.Delete, onDelete),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(interactionSource = clickSrc, indication = null, onClick = onJump)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            lines.original?.let { original ->
                Text(
                    text = original,
                    color = strike,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textDecoration = TextDecoration.LineThrough,
                )
            }
            if (lines.updated.isNotEmpty()) {
                Text(
                    text = lines.updated,
                    color = palette.text,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = if (lines.original == null) 0.dp else 2.dp),
                )
            }
        }
    }
}
