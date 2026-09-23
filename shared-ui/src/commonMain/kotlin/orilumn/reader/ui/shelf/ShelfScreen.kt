package orilumn.reader.ui.shelf

import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import kotlinx.coroutines.launch

/** 书架顶部/底部栏配色：白底 + 深黑前景（与阅读面工具栏的深灰拉开，保持书架清爽）。 */
private val BarGray = Color.White
private val BarGrayFg = Color(0xFF1F1F1F)

/** 「网格」切换图标：对齐原 app res/drawable/ic_grid.xml（24dp 四宫格，深色填充）。 */
private val GridIcon: ImageVector by lazy {
    val nodes = addPathNodes("M3,3h8v8h-8z M13,3h8v8h-8z M3,13h8v8h-8z M13,13h8v8h-8z")
    ImageVector.Builder(
        name = "Grid",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).addPath(nodes, fill = SolidColor(Color(0xFF000000))).build()
}

/** 「重复图书」弹窗数据：聚合的重复项 + 此前已完成的成功/失败计数。 */
private data class DupPromptState(
    val items: List<Pair<PlatformFile, String>>,
    val priorOk: Int,
    val priorFail: Int,
)

/**
 * 书架主界面（自含状态）：书目列表/封面/删除/排序 + 导入入口（FileKit 跨平台文件选择）。
 *
 * 数据与宿主绑定能力经 [ShelfRepository]/[ShelfHost] 注入，本组件全部代码位于 commonMain，
 * Android/桌面宿主均可直接挂载。
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
fun ShelfScreen(
    repository: ShelfRepository,
    host: ShelfHost,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    initialSort: ShelfSort = ShelfSort.Added,
    initialView: ShelfView = ShelfView.Grid,
    onPersistSort: (ShelfSort) -> Unit = {},
    onPersistView: (ShelfView) -> Unit = {},
) {
    var viewName by rememberSaveable { mutableStateOf(initialView.name) }
    var sortName by rememberSaveable { mutableStateOf(initialSort.name) }
    var editMode by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf(emptySet<Long>()) }
    // 导入中（逐本导入时驱动顶部进度提示；多选导入不再“首本无响应”）
    var importing by remember { mutableStateOf(false) }
    var importProgress by remember { mutableStateOf("") }
    // 导入重复聚合弹窗
    var dupPrompt by remember { mutableStateOf<DupPromptState?>(null) }

    val scope = rememberCoroutineScope()
    val importer = remember(repository, host) { ShelfImporter(repository, host) }

    val view = ShelfView.valueOf(viewName)
    val sort = ShelfSort.valueOf(sortName)
    val books by remember(sort) { repository.books(sort) }
        .collectAsState(initial = emptyList())

    // FileKit 0.15：title 是 JVM-only（dialogSettings 各平台 actual 不同），commonMain 只用
    // createDefault()；Android SAF 本来就没有标题，桌面标题缺失可接受。
    val picker = rememberFilePickerLauncher(
        type = FileKitType.File(extensions = listOf("epub")),
        mode = FileKitMode.Multiple(),
        dialogSettings = FileKitDialogSettings.createDefault(),
    ) { files: List<PlatformFile>? ->
        val picked = files.orEmpty()
        if (picked.isNotEmpty()) {
            scope.launch {
                importing = true
                val outcome = importer.run(picked) { i, n -> importProgress = "正在导入 $i/$n" }
                val ok = outcome.imported
                val fail = outcome.failed
                importing = false
                if (outcome.duplicates.isNotEmpty()) {
                    dupPrompt = DupPromptState(outcome.duplicates, priorOk = ok, priorFail = fail)
                } else {
                    snackbarHostState.showSnackbar(importSummary(ok, fail))
                }
            }
        }
    }

    // 「覆盖」确认：逐个删除同名旧书后重新导入
    fun confirmOverwrite() {
        val prompt = dupPrompt ?: return
        dupPrompt = null
        scope.launch {
            importing = true
            val (ok, fail) = importer.overwrite(prompt.items) { i, n ->
                importProgress = "正在覆盖 $i/$n"
            }
            importing = false
            snackbarHostState.showSnackbar(importSummary(prompt.priorOk + ok, prompt.priorFail + fail))
        }
    }

    // 「跳过」确认：跳过本次重复，仅汇总此前已完成部分
    fun skipOverwrite() {
        val prompt = dupPrompt ?: return
        dupPrompt = null
        scope.launch {
            snackbarHostState.showSnackbar(skipDuplicatesSummary(prompt.priorOk, prompt.priorFail, prompt.items.size))
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = BarGray,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column(modifier = Modifier.fillMaxWidth().background(BarGray)) {
                Spacer(modifier = Modifier.windowInsetsTopHeight(WindowInsets.statusBars).background(BarGray))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start,
                ) {
                    if (editMode) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回",
                                tint = BarGrayFg,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        editMode = false
                                        selected = emptySet()
                                    }
                                    .padding(8.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "返回书架",
                                fontSize = 16.sp,
                                color = BarGrayFg,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        editMode = false
                                        selected = emptySet()
                                    }
                                    .padding(vertical = 6.dp),
                            )
                        }
                    } else {
                        Text(
                            text = "书架",
                            fontSize = 19.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = BarGrayFg,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = sort.label() + " ▼",
                            fontSize = 14.sp,
                            color = BarGrayFg,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    val next = sort.next()
                                    sortName = next.name
                                    onPersistSort(next)
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                    if (editMode) {
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = "已选 ${selected.size}",
                            fontSize = 13.sp,
                            color = BarGrayFg,
                            textAlign = TextAlign.End,
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                }
            }
        },
        bottomBar = {
            Column(modifier = Modifier.fillMaxWidth().background(BarGray)) {
                Row(modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    if (editMode) {
                        ToolItem(
                            icon = if (selected.size == books.size) Icons.Default.Check else Icons.Default.Menu,
                            label = "全选",
                            onClick = {
                                if (selected.size != books.size) {
                                    selected = books.mapTo(mutableSetOf()) { it.id }
                                }
                            },
                        )
                        ToolItem(
                            icon = Icons.Default.Refresh,
                            label = "反选",
                            onClick = { books.forEach { b -> selected = if (b.id in selected) selected - b.id else selected + b.id } },
                        )
                        ToolItem(icon = Icons.Default.Menu, label = "分组", onClick = {
                            scope.launch { snackbarHostState.showSnackbar("分组（占位）") }
                        })
                        ToolItem(icon = Icons.Default.Delete, label = "删除", onClick = {
                            val toDelete = books.filter { it.id in selected }
                            scope.launch { toDelete.forEach { repository.delete(it.id) } }
                            selected = emptySet()
                            editMode = false
                        })
                    } else {
                        ToolItem(
                            icon = if (view == ShelfView.Grid) GridIcon else Icons.Default.List,
                            label = if (view == ShelfView.Grid) "网格" else "列表",
                            onClick = {
                                val next = if (view == ShelfView.Grid) ShelfView.CoverList else ShelfView.Grid
                                viewName = next.name
                                onPersistView(next)
                            },
                        )
                        ToolItem(icon = Icons.Default.Add, label = "导入", onClick = { picker.launch() })
                        ToolItem(icon = Icons.Default.Edit, label = "编辑", onClick = {
                            editMode = !editMode
                            if (!editMode) selected = emptySet()
                        })
                    }
                }
                val navBottom = WindowInsets.navigationBars.getBottom(LocalDensity.current)
                Spacer(modifier = Modifier.height((navBottom / 2).dp))
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (books.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "书架上还没有书", style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val columns = if (maxWidth >= 600.dp) 4 else 2
                    when (view) {
                        ShelfView.Grid -> LazyVerticalGrid(
                            columns = GridCells.Fixed(columns),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            items(books, key = { it.id }) { book ->
                                val sel = book.id in selected
                                BookCoverGridItem(
                                    book = book,
                                    selected = sel,
                                    loadCover = host::loadCover,
                                    onClick = {
                                        if (editMode) {
                                            selected = if (sel) selected - book.id else selected + book.id
                                        } else {
                                            scope.launch {
                                                repository.touchRead(book.id)
                                                host.openBook(book)
                                            }
                                        }
                                    },
                                    onLongClick = {
                                        if (!editMode) editMode = true
                                        selected = selected + book.id
                                    },
                                )
                            }
                        }
                        ShelfView.CoverList -> LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(books, key = { it.id }) { book ->
                                val sel = book.id in selected
                                CoverListRow(
                                    book = book,
                                    selected = sel,
                                    loadCover = host::loadCover,
                                    onClick = {
                                        if (editMode) {
                                            selected = if (sel) selected - book.id else selected + book.id
                                        } else {
                                            scope.launch {
                                                repository.touchRead(book.id)
                                                host.openBook(book)
                                            }
                                        }
                                    },
                                    onLongClick = {
                                        if (!editMode) editMode = true
                                        selected = selected + book.id
                                    },
                                )
                            }
                        }
                    }
                }
            }

            // 顶部导入进度提示：纯透明覆盖层，不拦截触摸
            if (importing) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 16.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xCC000000))
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text(importProgress, color = Color.White)
                    }
                }
            }

            // 重复图书弹窗
            dupPrompt?.let { p ->
                AlertDialog(
                    onDismissRequest = { skipOverwrite() },
                    title = { Text("重复图书") },
                    text = { Text("${p.items.joinToString("、") { it.second }}已存在，覆盖吗？") },
                    confirmButton = { TextButton(onClick = { confirmOverwrite() }) { Text("覆盖") } },
                    dismissButton = { TextButton(onClick = { skipOverwrite() }) { Text("跳过") } },
                )
            }
        }
    }
}

/**
 * 底部工具栏按钮：上图标下文字，各占一行等分；「图标+文字」作为一个圆角按钮，按压整体高亮。
 * 对齐阅读面 .tool（上图标下文字、:active 高亮）的外观与交互。
 */
@Composable
private fun RowScope.ToolItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(if (pressed) Color(0x12000000) else Color.Transparent)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = BarGrayFg,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(text = label, fontSize = 12.sp, color = BarGrayFg, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/** 封面缩略图：宿主解码失败或无封面时，回退到「灰底 + 书名首字」占位。 */
@Composable
private fun CoverThumb(
    book: ShelfBook,
    contentScale: ContentScale,
    loadCover: suspend (coverRef: String?) -> ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    val key = book.coverRef
    val bmp by produceState<ImageBitmap?>(null, book, loadCover) {
        value = if (key == null) null else loadCover(key)
    }
    val b = bmp
    if (b != null) {
        Image(
            bitmap = b,
            contentDescription = book.title,
            modifier = modifier,
            contentScale = contentScale,
        )
    } else {
        // 占位：灰底 + 书名的第一个字
        Box(
            modifier = modifier.background(Color(0xFFEFECE4)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = book.title.take(1),
                fontSize = 28.sp,
                color = Color(0xFF888888),
            )
        }
    }
}

/** 网格条目：2:3 封面 + 下方书名；编辑模式下选中项封面有高亮边框。 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun BookCoverGridItem(
    book: ShelfBook,
    selected: Boolean,
    loadCover: suspend (coverRef: String?) -> ImageBitmap?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        CoverThumb(
            book = book,
            contentScale = ContentScale.Crop,
            loadCover = loadCover,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(6.dp))
                .then(if (selected) Modifier.border(3.dp, Color(0xFF3B82F6), RoundedCornerShape(6.dp)) else Modifier),
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = book.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = BarGrayFg,
        )
    }
}

/** 图片列表行：左侧 2:3 封面缩略 + 右侧书名/作者；编辑模式右侧显示选中/未选中标记。 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun CoverListRow(
    book: ShelfBook,
    selected: Boolean,
    loadCover: suspend (coverRef: String?) -> ImageBitmap?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .then(if (selected) Modifier.background(Color(0x142563F7)) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverThumb(
            book = book,
            contentScale = ContentScale.Crop,
            loadCover = loadCover,
            modifier = Modifier
                .size(52.dp, 78.dp)
                .clip(RoundedCornerShape(4.dp)),
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = book.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = BarGrayFg,
            )
            book.author?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (selected) {
            Text(
                text = "✓",
                fontSize = 22.sp,
                color = Color(0xFF2563F7),
            )
        }
    }
}