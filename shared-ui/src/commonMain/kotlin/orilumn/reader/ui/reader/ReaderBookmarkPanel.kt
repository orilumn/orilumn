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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import orilumn.reader.data.bookmark.Bookmark

/**
 * 书签清单的列表体（用户层）：由 [ReaderSidePanel] 的抽屉壳承载，与目录 / 笔记 / 修订同一抽屉。
 *
 * 顶部一行「添加当前页书签」：书签记的是**正在读的这一页**，读者不必先翻目录再找位置。
 * 同一页重复添加不会长出第二条（宿主返回已有那条，这里只提示一句）。
 *
 * 下面按书内章序、章内页首升序平铺：每行先标章名，再排两行本页摘录；点行跳到该页，
 * 整行左滑露出「删除」。章名用目录那份标题，缺项回退「第 N 章」。
 * 读取失败与「真的还没有书签」分开显示（失败给重试，不假装空态）。
 *
 * 键盘沿用面板共享的 [PanelNav]（↑↓ + Enter），与目录 / 笔记 / 修订同口径。
 */
@Composable
fun ReaderBookmarkPanel(
    scheme: String,
    /** 读全书书签：**null = 读取失败**（与「真没有书签」必须分开显示）。 */
    load: suspend () -> List<Bookmark>?,
    /** 把当前阅读页加入书签；返回落库后的记录（已有同页书签时就是那条），失败 null。 */
    onAddCurrent: suspend () -> Bookmark?,
    onDelete: suspend (Bookmark) -> Boolean,
    onJump: (Bookmark) -> Unit,
    chapterTitles: Map<Int, String>,
    currentChapter: Int,
    onDismiss: () -> Unit,
    /** 宿主缓存的整本快照：给了就**首帧**直接排列表，抽屉不会先滑出半屏空白。 */
    initial: List<Bookmark>? = null,
) {
    val p = paletteFor(scheme)
    var loaded by remember { mutableStateOf(initial?.let(::sortBookmarks)) }
    // 读取失败是独立状态：不能回退成空表，否则读者会以为书签全丢了。
    var loadFailed by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var reloadTick by remember { mutableStateOf(0) }
    // 左滑展开的行键：同时只展开一行（SwipeRevealRow 的手势物理与它共用一套口径）。
    var openKey by remember { mutableStateOf<String?>(null) }
    val currentLoad by rememberUpdatedState(load)

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
            val sorted = sortBookmarks(fresh)
            if (sorted != loaded) loaded = sorted
        }
    }

    val all = loaded.orEmpty()
    val itemCount = all.size
    // 打开就落在当前阅读章的第一条：LazyColumn 直接从目标位起排（与笔记 / 修订清单同口径）。
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

    fun activeBookmark(): Bookmark? = nav.activeIdx?.let { all.getOrNull(it) }

    /** 把当前页加入书签：成功即插进清单并滚到它；同页已有只提示一句。 */
    fun addCurrent() {
        if (adding) return
        adding = true
        notice = null
        scope.launch {
            val saved = try {
                onAddCurrent()
            } catch (c: CancellationException) {
                throw c
            } catch (_: Throwable) {
                null
            } finally {
                adding = false
            }
            if (saved == null) {
                notice = "添加失败"
                return@launch
            }
            val existed = loaded.orEmpty().any { it.id == saved.id }
            val next = sortBookmarks(loaded.orEmpty().filterNot { it.id == saved.id } + saved)
            loaded = next
            val idx = next.indexOfFirst { it.id == saved.id }
            if (idx >= 0) {
                nav.activeIdx = idx
                nav.releaseHold()
                listState.animateScrollToItem(idx)
            }
            notice = if (existed) "这一页已经加过书签了" else "已添加书签"
            bodyFr.requestFocus()
        }
    }

    /** 删一条书签：清单里同步移除；失败就地提示（行不消失）。 */
    fun delete(bookmark: Bookmark) {
        notice = null
        scope.launch {
            val ok = try {
                onDelete(bookmark)
            } catch (c: CancellationException) {
                throw c
            } catch (_: Throwable) {
                false
            }
            if (ok) {
                val next = loaded.orEmpty().filterNot { it.id == bookmark.id }
                loaded = next
                val a = nav.activeIdx
                nav.activeIdx = when {
                    next.isEmpty() -> null
                    a == null -> null
                    a !in next.indices -> a.coerceIn(0, next.lastIndex)
                    else -> a
                }
                if (openKey == "bm:${bookmark.id}") openKey = null
                bodyFr.requestFocus()
            } else {
                notice = "删除失败"
            }
        }
    }

    // 打开时把高亮落到当前阅读章的第一条（滚动位置已由初始下标就位）。
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
                onEnter = { activeBookmark()?.let(onJump) },
                onEscape = onDismiss,
            )
            // 点按消费：杂散点按不穿透到遮罩关闭层。
            .pointerInput(Unit) { detectTapGestures(onTap = {}) },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (adding) "添加中…" else "＋ 添加当前页书签",
                color = if (adding) p.muted2 else PanelGold,
                fontSize = 13.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (adding) Color.Transparent else p.rowActive, RoundedCornerShape(7.dp))
                    .clickable(enabled = !adding) { addCurrent() }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
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
                    "还没有书签",
                    color = p.muted2,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(24.dp),
                )

                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().clearKbHoldOnMove { nav.releaseHold() },
                ) {
                    items(all.size, key = { "bm${all[it].id}" }) { i ->
                        val bookmark = all[i]
                        BookmarkListRow(
                            bookmark = bookmark,
                            chapterTitle = chapterTitles[bookmark.chapter]
                                ?: "第 ${bookmark.chapter + 1} 章",
                            active = nav.activeIdx == i,
                            palette = p,
                            nav = nav,
                            index = i,
                            openKey = openKey,
                            onOpenChange = { openKey = it },
                            onJump = {
                                openKey = null
                                onJump(bookmark)
                            },
                            onDelete = { delete(bookmark) },
                        )
                    }
                }
            }
            notice?.let {
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

/** 清单顺序：章序 → 章内页首 → id（与数据库查询同序，纯函数便于单测）。 */
internal fun sortBookmarks(bookmarks: List<Bookmark>): List<Bookmark> =
    bookmarks.sortedWith(compareBy({ it.chapter }, { it.charStart }, { it.id }))

/**
 * 一条书签：上行章名（小字），下行本页摘录（最多两行）。
 * 摘录先折空白（清单是一条流式文字）；没有摘录（页首落在图片等无字位置）时给一句占位。
 */
@Composable
private fun BookmarkListRow(
    bookmark: Bookmark,
    chapterTitle: String,
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
    val excerpt = remember(bookmark.quote) {
        bookmark.quote?.let(::revisionExcerpt)?.ifBlank { null }
    }
    SwipeRevealRow(
        rowKey = "bm:${bookmark.id}",
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
                .focusProperties { canFocus = false }
                .clickable(interactionSource = clickSrc, indication = null, onClick = onJump)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(
                text = chapterTitle,
                color = palette.muted2,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = excerpt ?: "（本页无文字）",
                color = if (excerpt == null) palette.muted2 else palette.text,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
