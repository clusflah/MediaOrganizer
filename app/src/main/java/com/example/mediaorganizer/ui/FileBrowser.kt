package com.example.mediaorganizer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.mediaorganizer.MainViewModel
import com.example.mediaorganizer.parser.MediaNameParser
import com.example.mediaorganizer.storage.RemoteFile
import com.example.mediaorganizer.storage.StorageException
import com.example.mediaorganizer.storage.StorageType
import com.example.mediaorganizer.storage.normalizePath
import com.example.mediaorganizer.storage.parentPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 文件管理：浏览媒体库里的文件夹，删除、改名、新建文件夹、移动。
 * 返回键先退出多选，再回到上一个浏览过的文件夹，最后关闭。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FileBrowserScreen(vm: MainViewModel, req: MainViewModel.BrowseRequest) {
    val scope = rememberCoroutineScope()
    var dir by remember(req) { mutableStateOf(req.dir) }
    val history = remember(req) { mutableStateListOf<String>() }
    var files by remember { mutableStateOf<List<RemoteFile>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var moving by remember { mutableStateOf<List<RemoteFile>>(emptyList()) }   // 待移动的（选好后进目标文件夹点「移到这里」）
    var renaming by remember { mutableStateOf<RemoteFile?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<List<RemoteFile>>(emptyList()) }
    var highlight by remember(req) { mutableStateOf(req.highlight) }
    val listState = rememberLazyListState()
    val cloud = vm.settings.storageType == StorageType.PAN115 || vm.settings.storageType == StorageType.ALIYUN
    val canEdit = !working && !vm.busy

    LaunchedEffect(dir, reload) {
        error = null
        files = null
        files = try {
            vm.browseList(dir)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            error = if (e is StorageException && e.code == 404) "文件夹不存在：$dir" else "读取失败：${e.message}"
            emptyList()
        }
    }

    // 打开时定位到对应的视频（列表第一行是「上一级」）；只定位一次，之后刷新不再跳回去
    var located by remember(req) { mutableStateOf(false) }
    LaunchedEffect(files) {
        if (located) return@LaunchedEffect
        val h = highlight ?: return@LaunchedEffect
        val i = files?.indexOfFirst { it.name == h }?.takeIf { it >= 0 } ?: return@LaunchedEffect
        listState.scrollToItem(i + if (dir != "/") 1 else 0)
        located = true
    }

    fun go(to: String) {
        val t = normalizePath(to)
        if (t == dir) return
        history += dir
        dir = t
        selected = emptySet()
        highlight = null
    }

    /** 执行一个会改动文件的操作，完成后刷新当前文件夹 */
    fun run(done: String, op: suspend () -> List<String>) {
        working = true
        message = null
        scope.launch {
            val errors = try { op() } catch (e: Exception) {
                if (e is CancellationException) throw e
                listOf(e.message ?: e.toString())
            }
            message = if (errors.isEmpty()) done else "${errors.size} 项失败：${errors.first()}"
            working = false
            selected = emptySet()
            reload++
        }
    }

    // 操作进行中不能关：关掉会中途取消，删除 / 移动只做了一半
    BackHandler {
        when {
            working -> Unit
            selected.isNotEmpty() -> selected = emptySet()
            history.isNotEmpty() -> { dir = history.removeAt(history.lastIndex); highlight = null }
            else -> vm.closeBrowser()
        }
    }

    val list = files.orEmpty()
    val picked = list.filter { it.path in selected }

    Scaffold(
        topBar = {
            if (selected.isNotEmpty()) TopAppBar(
                navigationIcon = { IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Default.Close, "取消选择") } },
                title = { Text("已选 ${selected.size}") },
                actions = {
                    TextButton(onClick = { selected = list.map { it.path }.toSet() }) { Text("全选") }
                    TextButton(onClick = { moving = picked; selected = emptySet() }, enabled = canEdit) { Text("移动") }
                    TextButton(onClick = { deleting = picked }, enabled = canEdit) { Text("删除", color = LocalStatusColors.current.bad) }
                },
            ) else TopAppBar(
                navigationIcon = { IconButton(onClick = vm::closeBrowser, enabled = !working) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                title = { Text(dir.substringAfterLast('/').ifEmpty { "根目录" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    IconButton(onClick = { creating = true }, enabled = canEdit) { Icon(Icons.Default.Add, "新建文件夹") }
                    IconButton(onClick = { reload++ }) { Icon(Icons.Default.Refresh, "刷新") }
                },
            )
        },
        bottomBar = {
            if (moving.isNotEmpty()) Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    // 不能移到自己或自己的子文件夹里
                    val invalid = moving.any { it.isDirectory && (dir == normalizePath(it.path) || dir.startsWith(normalizePath(it.path) + "/")) }
                    val same = moving.all { parentPath(it.path) == dir }
                    Text(
                        when {
                            invalid -> "不能移到它自己的文件夹里，请换一个位置"
                            same -> "移动 ${moving.size} 项：进入目标文件夹后点「移到这里」"
                            else -> "把 ${moving.size} 项移到 $dir"
                        },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val m = Modifier.weight(1f).height(48.dp)
                        OutlinedButton(onClick = { moving = emptyList() }, modifier = m) { Text("取消") }
                        Button(onClick = {
                            val items = moving; val target = dir
                            moving = emptyList()
                            run("已移动 ${items.size} 项") { vm.browseMove(items, target) }
                        }, enabled = canEdit && !invalid && !same, modifier = m) { Text("移到这里") }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Breadcrumbs(dir, onGo = ::go)
            if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
            message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            when {
                files == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                error != null -> Column(Modifier.padding(16.dp)) {
                    Text(error!!, color = LocalStatusColors.current.bad)
                    if (dir != "/") TextButton(onClick = { go(parentPath(dir)) }) { Text("去上一级") }
                }
                list.isEmpty() -> Text("空文件夹", color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(16.dp))
                else -> LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
                    if (dir != "/") item(key = "..") {
                        ListItem(
                            headlineContent = { Text("上一级") },
                            leadingContent = { Text("↩") },
                            modifier = Modifier.clickable { go(parentPath(dir)) },
                        )
                    }
                    items(list, key = { it.path }) { f ->
                        FileRow(
                            f,
                            checked = f.path in selected,
                            highlighted = f.name == highlight,
                            enabled = canEdit,
                            onClick = {
                                when {
                                    selected.isNotEmpty() -> selected = if (f.path in selected) selected - f.path else selected + f.path
                                    f.isDirectory -> go(f.path)
                                }
                            },
                            onLongClick = { selected = selected + f.path },
                            onRename = { renaming = f },
                            onMove = { moving = listOf(f) },
                            onDelete = { deleting = listOf(f) },
                        )
                    }
                }
            }
        }
    }

    renaming?.let { f ->
        NameDialog(
            title = "重命名", initial = f.name,
            taken = list.map { it.name }.toSet() - f.name,
            onDismiss = { renaming = null },
        ) { name ->
            renaming = null
            run("已改名为 $name") { vm.browseRename(f, name); emptyList() }
        }
    }
    if (creating) NameDialog(title = "新建文件夹", initial = "", taken = list.map { it.name }.toSet(), onDismiss = { creating = false }) { name ->
        creating = false
        run("已新建 $name") { vm.browseMkdir(dir, name); emptyList() }
    }
    if (deleting.isNotEmpty()) {
        val items = deleting
        val dirs = items.count { it.isDirectory }
        AlertDialog(
            onDismissRequest = { deleting = emptyList() },
            title = { Text("确认删除") },
            text = {
                Text(
                    (if (items.size == 1) "删除 ${items.first().name}？" else "删除选中的 ${items.size} 项？") +
                        (if (dirs > 0) "\n文件夹里的所有内容会一起删除。" else "") +
                        if (cloud) "\n会进网盘回收站，可恢复。" else "\n直接删除，无法恢复。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleting = emptyList()
                    run("已删除 ${items.size} 项") { vm.browseDelete(items) }
                }) { Text("删除", color = LocalStatusColors.current.bad) }
            },
            dismissButton = { TextButton(onClick = { deleting = emptyList() }) { Text("取消") } },
        )
    }
}

/** 路径面包屑：点任一级直接跳过去 */
@Composable
private fun Breadcrumbs(dir: String, onGo: (String) -> Unit) {
    val parts = dir.split('/').filter { it.isNotEmpty() }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { onGo("/") }) { Text("/") }
        parts.forEachIndexed { i, name ->
            if (i > 0) Text("›", color = MaterialTheme.colorScheme.outline)
            TextButton(onClick = { onGo("/" + parts.take(i + 1).joinToString("/")) }) {
                Text(name, maxLines = 1, fontWeight = if (i == parts.lastIndex) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    f: RemoteFile, checked: Boolean, highlighted: Boolean, enabled: Boolean,
    onClick: () -> Unit, onLongClick: () -> Unit,
    onRename: () -> Unit, onMove: () -> Unit, onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val ext = f.name.substringAfterLast('.', "").lowercase()
    val icon = when {
        f.isDirectory -> "📁"
        ext in MediaNameParser.VIDEO_EXTENSIONS -> "🎬"
        ext in setOf("jpg", "jpeg", "png", "webp") -> "🖼"
        ext in setOf("srt", "ass", "ssa", "sub", "vtt", "sup", "idx") -> "💬"
        else -> "📄"
    }
    val bg = when {
        checked -> MaterialTheme.colorScheme.secondaryContainer
        highlighted -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        else -> MaterialTheme.colorScheme.surface
    }
    ListItem(
        headlineContent = { Text(f.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            val meta = listOfNotNull(
                if (f.isDirectory) null else sizeText(f.size).ifEmpty { null },
                if (f.modified > 0) formatTime(f.modified) else null,
            ).joinToString(" · ")
            if (meta.isNotEmpty()) Text(meta)
        },
        leadingContent = { if (checked) Checkbox(checked = true, onCheckedChange = null) else Text(icon) },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }, enabled = enabled) { Icon(Icons.Default.MoreVert, "操作") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("重命名") }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text("移动到…") }, onClick = { menu = false; onMove() })
                    DropdownMenuItem(text = { Text("删除", color = LocalStatusColors.current.bad) }, onClick = { menu = false; onDelete() })
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = bg),
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
    )
}

/** 输入名字：不能为空、不能带 /、不能和同目录已有的重名 */
@Composable
private fun NameDialog(title: String, initial: String, taken: Set<String>, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val trimmed = name.trim()
    val problem = when {
        trimmed.isEmpty() -> "名字不能为空"
        '/' in trimmed || '\\' in trimmed -> "名字里不能有 / 或 \\"
        trimmed in taken -> "这个文件夹里已经有同名的了"
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name, onValueChange = { name = it }, singleLine = true,
                isError = problem != null && name != initial,
                supportingText = { if (problem != null && name != initial) Text(problem) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(trimmed) }, enabled = problem == null && trimmed != initial) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
