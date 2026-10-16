package com.example.mediaorganizer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mediaorganizer.*
import com.example.mediaorganizer.metadata.MediaMatcher
import com.example.mediaorganizer.metadata.MediaType
import com.example.mediaorganizer.metadata.TmdbSearchResult
import com.example.mediaorganizer.settings.AppSettings
import com.example.mediaorganizer.storage.normalizePath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.net.URI

private val Green = Color(0xFF2E7D32)
private val Orange = Color(0xFFEF6C00)
private val Red = Color(0xFFC62828)
private val Gray = Color(0xFF757575)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainScreen(vm: MainViewModel, openNaming: () -> Unit, openScrape: () -> Unit) {
    val s = vm.settings
    var editConn by rememberSaveable { mutableStateOf(s.webdavUrl.isBlank() || s.tmdbToken.isBlank()) }
    var detailKey by remember { mutableStateOf<String?>(null) }
    var askOrganize by remember { mutableStateOf(false) }
    var askUndo by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val counts by remember { derivedStateOf { vm.rows.groupingBy { it.category }.eachCount() } }
    val visible by remember {
        derivedStateOf {
            val f = vm.filter
            val t = vm.typeFilter
            vm.rows.filter { (f == null || it.category == f) && (t == null || it.isTv == (t == MediaType.TV)) }
                .let { list -> if (f == null && vm.task != Task.SCAN) list.sortedBy { it.category.ordinal } else list }
        }
    }
    val selecting = vm.selected.isNotEmpty()
    BackHandler(enabled = selecting) { vm.clearSelection() }

    fun showFilter(c: RowCategory?) {
        vm.filter = c
        scope.launch { listState.animateScrollToItem(2) }
    }

    Scaffold(
        topBar = {
            if (selecting) SelectionBar(vm, visibleKeys = visible.map { it.key })
            else TopAppBar(
                title = { Text("影视整理") },
                actions = { MainMenu(vm, openNaming, openScrape, onUndo = { askUndo = true }) },
            )
        },
        bottomBar = {
            BottomBar(
                vm, counts,
                onOrganize = { askOrganize = true },
                onShowPending = { showFilter(it) },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            item(key = "conn") {
                ConnectionCard(vm, expanded = editConn, onToggle = { editConn = !editConn }, openScrape = openScrape)
            }
            item(key = "chips") {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = openNaming, label = { Text("命名规则 · ${s.player}") })
                    AssistChip(onClick = openScrape, label = { Text("刮削设置") })
                }
            }

            if (vm.rows.isEmpty() && vm.task != Task.SCAN) {
                item(key = "empty") { EmptyState(vm) }
                return@LazyColumn
            }

            item(key = "stats") { StatsRow(vm.rows.size, counts, vm.filter, ::showFilter) }
            stickyHeader(key = "filters") { FilterBar(vm, counts) }
            if (vm.status.isNotEmpty()) item(key = "status") {
                Text(
                    vm.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            items(visible, key = { it.key }) { row ->
                ResultRow(
                    row, s,
                    selecting = selecting,
                    checked = row.key in vm.selected,
                    enabled = !vm.busy,
                    onClick = { if (selecting) vm.toggleSelect(row.key) else detailKey = row.key },
                    onLongClick = { vm.toggleSelect(row.key) },
                    onConfirm = { vm.confirm(setOf(row.key)) },
                    onChange = { detailKey = row.key },
                )
                HorizontalDivider(Modifier.padding(start = 68.dp))
            }
            if (visible.isEmpty() && vm.task != Task.SCAN) item(key = "none") {
                Text(
                    "这个分类下没有文件", color = MaterialTheme.colorScheme.outline, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                )
            }
        }
    }

    detailKey?.let { key -> vm.rows.firstOrNull { it.key == key } }?.let { row ->
        RowDetailDialog(vm, row, onDismiss = { detailKey = null })
    }
    if (askUndo) vm.undoSummary()?.let { sum ->
        UndoConfirmDialog(sum, onConfirm = { askUndo = false; vm.undo() }, onDismiss = { askUndo = false })
    }
    if (askOrganize) {
        val summary = remember { vm.organizeSummary() }
        OrganizeConfirmDialog(
            summary, s,
            onConfirm = { askOrganize = false; vm.execute() },
            onDismiss = { askOrganize = false },
            onOpenRow = { askOrganize = false; detailKey = it },
        )
    }
}

// ============================ 顶栏 ============================

@Composable
private fun MainMenu(vm: MainViewModel, openNaming: () -> Unit, openScrape: () -> Unit, onUndo: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, contentDescription = "更多") }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        fun go(action: () -> Unit) { open = false; action() }
        if (vm.rows.isNotEmpty())
            DropdownMenuItem(text = { Text("重新扫描") }, enabled = !vm.busy, onClick = { go(vm::scan) })
        if (!vm.settings.autoScrape && vm.rows.any { it.category == RowCategory.DONE })
            DropdownMenuItem(text = { Text("刮削已整理的文件") }, enabled = !vm.busy, onClick = { go(vm::scrapeOnly) })
        val undo = if (vm.canUndo) vm.undoSummary() else null
        DropdownMenuItem(
            text = {
                Column {
                    Text("撤销上次整理")
                    if (undo != null) Text(
                        "${formatTime(undo.time)} · ${undo.moved} 个文件",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                    )
                }
            },
            enabled = undo != null && !vm.busy,
            onClick = { go(onUndo) },
        )
        HorizontalDivider()
        DropdownMenuItem(text = { Text("命名规则") }, onClick = { go(openNaming) })
        DropdownMenuItem(text = { Text("刮削设置") }, onClick = { go(openScrape) })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionBar(vm: MainViewModel, visibleKeys: List<String>) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = vm::clearSelection) { Icon(Icons.Default.Close, contentDescription = "取消选择") }
        },
        title = { Text("已选 ${vm.selected.size}") },
        actions = {
            TextButton(onClick = { vm.selectAll(visibleKeys) }) { Text("全选") }
            TextButton(onClick = { vm.confirm(vm.selected) }, enabled = !vm.busy) { Text("确认") }
            val allExcluded = vm.rows.filter { it.key in vm.selected }.all { it.excluded }
            TextButton(onClick = { vm.setExcluded(vm.selected, !allExcluded) }, enabled = !vm.busy) {
                Text(if (allExcluded) "恢复" else "排除")
            }
        },
    )
}

// ============================ 媒体服务器卡片 ============================

private fun hostOf(url: String): String =
    runCatching { URI(url.trim()).let { u -> u.host + if (u.port > 0) ":${u.port}" else "" } }
        .getOrNull()?.takeIf { !it.startsWith("null") } ?: url.trim()

@Composable
private fun ConnectionCard(vm: MainViewModel, expanded: Boolean, onToggle: () -> Unit, openScrape: () -> Unit) {
    val s = vm.settings
    fun up(n: AppSettings) = vm.updateSettings(n)
    val host = hostOf(s.webdavUrl)
    val (dot, title) = when {
        s.webdavUrl.isBlank() -> Gray to "尚未配置媒体服务器"
        vm.connState == ConnState.OK -> Green to "已连接 · $host"
        vm.connState == ConnState.FAIL -> Red to "连接失败 · $host"
        else -> Gray to "尚未测试 · $host"
    }

    ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth().clickable(onClick = onToggle), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("媒体服务器", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = onToggle) { Text(if (expanded) "收起" else "编辑") }
            }
            if (vm.connState == ConnState.FAIL && vm.connMessage.isNotEmpty())
                Text(vm.connMessage, color = Red, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            if (!expanded) {
                Spacer(Modifier.height(8.dp))
                PathLine("扫描", s.scanDir)
                PathLine("电影", s.movieRoot)
                PathLine("剧集", s.tvRoot)
            }
            if (s.tmdbToken.isBlank()) {
                Text(
                    "未填写 TMDB 令牌，无法识别影片 · 去设置",
                    color = Orange, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp).clickable(onClick = openScrape),
                )
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                SettingField("WebDAV 地址", s.webdavUrl) { up(s.copy(webdavUrl = it)) }
                SettingField("用户名", s.username) { up(s.copy(username = it)) }
                SettingField("密码", s.password, password = true) { up(s.copy(password = it)) }
                SettingField("扫描目录", s.scanDir) { up(s.copy(scanDir = it)) }
                SettingField("电影库目录", s.movieRoot) { up(s.copy(movieRoot = it)) }
                SettingField("剧集库目录", s.tvRoot) { up(s.copy(tvRoot = it)) }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = vm::testConnection, enabled = !vm.busy && s.webdavUrl.isNotBlank()) {
                        if (vm.task == Task.TEST) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("测试连接")
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onToggle) { Text("完成") }
                }
                vm.connChecks.forEach { ConnCheckRow(it) }
            }
        }
    }
}

@Composable
private fun PathLine(label: String, path: String) {
    Row(Modifier.padding(vertical = 1.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.width(36.dp))
        Text(path.ifBlank { "未填写" }, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ConnCheckRow(c: ConnCheck) {
    val (mark, color) = when (c.level) {
        ConnCheck.Level.OK -> "✓" to Green
        ConnCheck.Level.WARN -> "!" to Orange
        ConnCheck.Level.FAIL -> "✗" to Red
    }
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(mark, color = color, fontWeight = FontWeight.Bold, modifier = Modifier.width(18.dp))
        Text(c.label, fontWeight = FontWeight.Medium, fontSize = 13.sp, modifier = Modifier.width(64.dp))
        Text(c.detail, fontSize = 13.sp, color = if (c.level == ConnCheck.Level.OK) Color.Unspecified else color)
    }
}

// ============================ 空状态 / 统计 / 筛选 ============================

@Composable
private fun EmptyState(vm: MainViewModel) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("还没有扫描结果", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "点「扫描」查找 ${vm.settings.scanDir.ifBlank { "扫描目录" }} 里的视频。" +
                "识别结果会先列出来给你核对，确认后才会移动和改名。",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (vm.status.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(vm.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun StatsRow(total: Int, counts: Map<RowCategory, Int>, filter: RowCategory?, onPick: (RowCategory?) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatTile("扫描文件", total, MaterialTheme.colorScheme.onSurface, filter == null) { onPick(null) }
        StatTile("待确认", counts[RowCategory.PENDING] ?: 0, Orange, filter == RowCategory.PENDING) { onPick(RowCategory.PENDING) }
        StatTile("可整理", counts[RowCategory.READY] ?: 0, Green, filter == RowCategory.READY) { onPick(RowCategory.READY) }
        StatTile("未匹配", counts[RowCategory.UNMATCHED] ?: 0, Red, filter == RowCategory.UNMATCHED) { onPick(RowCategory.UNMATCHED) }
    }
}

@Composable
private fun RowScope.StatTile(label: String, value: Int, color: Color, active: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.weight(1f),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 10.dp)) {
            Text(value.toString(), color = color, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

/** 换行排列而不是横向滚动：标签都能完整看到，不会被屏幕边缘截断 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FilterBar(vm: MainViewModel, counts: Map<RowCategory, Int>) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(selected = vm.filter == null, onClick = { vm.filter = null }, label = { Text("全部 ${vm.rows.size}") })
            for (c in listOf(RowCategory.PENDING, RowCategory.READY, RowCategory.UNMATCHED, RowCategory.FAILED, RowCategory.DONE, RowCategory.EXCLUDED)) {
                val n = counts[c] ?: 0
                if (n == 0 && vm.filter != c && c !in setOf(RowCategory.PENDING, RowCategory.READY)) continue
                FilterChip(selected = vm.filter == c, onClick = { vm.filter = c }, label = { Text("${c.label} $n") })
            }
            for ((t, label) in listOf(MediaType.MOVIE to "只看电影", MediaType.TV to "只看剧集")) {
                FilterChip(
                    selected = vm.typeFilter == t,
                    onClick = { vm.typeFilter = if (vm.typeFilter == t) null else t },
                    label = { Text(label) },
                )
            }
        }
    }
}

// ============================ 结果列表 ============================

private fun displayTitle(row: MediaRow): String {
    val p = row.match.parsed
    val base = row.chosen?.let { "${it.title} (${it.year ?: "?"})" } ?: p.title
    val ep = if (p.isEpisode && row.isTv) " · S%02dE%02d".format(p.season ?: 1, p.episode) else ""
    return base + ep
}

/** 目标路径去掉库根目录前缀，主列表里短一点 */
private fun shortTarget(path: String, s: AppSettings): String {
    for ((root, label) in listOf(s.movieRoot to "电影库", s.tvRoot to "剧集库")) {
        val r = normalizePath(root)
        if (r != "/" && path.startsWith("$r/")) return "$label/" + path.removePrefix("$r/")
    }
    return path
}

private fun pillOf(row: MediaRow): Pair<String, Color> {
    val pct = "%.0f%%".format(row.match.score * 100)
    return when (row.category) {
        RowCategory.READY ->
            if (row.chosen == row.match.best && row.match.score >= MediaMatcher.AUTO_ACCEPT) "置信度 $pct" to Green
            else "已确认" to Green
        RowCategory.PENDING -> "待确认 · $pct" to Orange
        RowCategory.UNMATCHED -> "未匹配" to Red
        RowCategory.FAILED -> "失败" to Red
        RowCategory.DONE -> if (row.outcome == "无需改动") "无需改动" to Gray else "已完成" to Green
        RowCategory.EXCLUDED -> "已排除" to Gray
    }
}

@Composable
private fun Pill(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.12f)) {
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResultRow(
    row: MediaRow, s: AppSettings,
    selecting: Boolean, checked: Boolean, enabled: Boolean,
    onClick: () -> Unit, onLongClick: () -> Unit, onConfirm: () -> Unit, onChange: () -> Unit,
) {
    val cat = row.category
    Row(
        Modifier.fillMaxWidth()
            .background(if (checked) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 6.dp),
    ) {
        if (selecting) {
            Checkbox(checked = checked, onCheckedChange = { onClick() }, modifier = Modifier.size(40.dp))
        } else {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (row.isTv) "剧" else "影", color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    displayTitle(row),
                    style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                    color = if (row.chosen == null) MaterialTheme.colorScheme.outline else Color.Unspecified,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                val (text, color) = pillOf(row)
                Pill(text, color)
            }
            Text(
                row.match.file?.name ?: row.match.parsed.rawName,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            row.plan?.takeIf { cat != RowCategory.EXCLUDED }?.let {
                Text(
                    "→ " + shortTarget(it.targetPath, s),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (cat == RowCategory.FAILED) Text(row.outcome.orEmpty(), color = Red, fontSize = 12.sp, maxLines = 2)
            if (!selecting && (cat == RowCategory.PENDING || cat == RowCategory.UNMATCHED)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (cat == RowCategory.PENDING)
                        TextButton(onClick = onConfirm, enabled = enabled, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("确认匹配") }
                    TextButton(onClick = onChange, enabled = enabled, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text(if (cat == RowCategory.PENDING) "更换" else "手动搜索")
                    }
                }
            } else Spacer(Modifier.height(4.dp))
        }
    }
}

// ============================ 底栏 ============================

@Composable
private fun BottomBar(
    vm: MainViewModel, counts: Map<RowCategory, Int>,
    onOrganize: () -> Unit, onShowPending: (RowCategory) -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            when (vm.task) {
                Task.SCAN -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("正在扫描… 已找到 ${vm.rows.size} 个视频", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.width(12.dp))
                    OutlinedButton(onClick = vm::stopScan) { Text("停止") }
                }
                Task.ORGANIZE, Task.SCRAPE, Task.UNDO -> {
                    Text(vm.status, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                    val p = vm.progress
                    if (p != null && p.second > 0) LinearProgressIndicator(progress = { p.first.toFloat() / p.second }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                else -> if (vm.rows.isEmpty()) {
                    Button(onClick = vm::scan, enabled = !vm.busy, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("扫描") }
                } else {
                    val pending = counts[RowCategory.PENDING] ?: 0
                    val unmatched = counts[RowCategory.UNMATCHED] ?: 0
                    val ready = vm.organizableCount()
                    if (ready > 0 && pending + unmatched > 0)
                        Text(
                            "本次整理 $ready 个已确认文件；另有 " +
                                listOfNotNull(
                                    if (pending > 0) "$pending 个待确认" else null,
                                    if (unmatched > 0) "$unmatched 个未匹配" else null,
                                ).joinToString("、") + "，可稍后处理",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val m = Modifier.weight(1f).height(48.dp)
                        when {
                            pending > 0 && vm.filter != RowCategory.PENDING ->
                                OutlinedButton(onClick = { onShowPending(RowCategory.PENDING) }, modifier = m) { Text("处理待确认 $pending") }
                            unmatched > 0 && vm.filter != RowCategory.UNMATCHED ->
                                OutlinedButton(onClick = { onShowPending(RowCategory.UNMATCHED) }, modifier = m) { Text("处理未匹配 $unmatched") }
                            else -> OutlinedButton(onClick = vm::scan, enabled = !vm.busy, modifier = m) { Text("重新扫描") }
                        }
                        Button(onClick = onOrganize, enabled = !vm.busy && ready > 0, modifier = m) {
                            Text(if (ready > 0) "整理 $ready 项" else "开始整理")
                        }
                    }
                }
            }
        }
    }
}

// ============================ 对话框 ============================

private const val LIST_LIMIT = 200   // 每组最多列这么多条，避免几千条撑爆对话框

/** 整理前确认：每一类都能展开看具体文件，点文件进详情改匹配或排除 */
@Composable
private fun OrganizeConfirmDialog(
    sum: OrganizeSummary, s: AppSettings,
    onConfirm: () -> Unit, onDismiss: () -> Unit, onOpenRow: (String) -> Unit,
) {
    var open by remember { mutableStateOf(if (sum.duplicates.isNotEmpty()) "dup" else "") }
    fun toggle(id: String) { open = if (open == id) "" else id }
    val dupCount = sum.duplicates.sumOf { it.second.size }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("整理 ${sum.total} 个文件？") },
        text = {
            LazyColumn {
                if (sum.duplicates.isNotEmpty()) {
                    item { GroupHeader("目标重名", dupCount, Orange, open == "dup") { toggle("dup") } }
                    if (open == "dup") {
                        item {
                            Text("每组只有第一个会整理，其余标为失败。点文件可改匹配或排除。",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 4.dp))
                        }
                        sum.duplicates.take(LIST_LIMIT).forEach { (target, list) ->
                            item {
                                Text("→ " + shortTarget(target, s), style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
                            }
                            items(list) { m ->
                                MoveLine(m, s, showTarget = false, mark = if (m == list.first()) "整理" else "跳过") { onOpenRow(m.key) }
                            }
                        }
                    }
                }
                moveGroup("移动到影视库", "move", sum.moves, s, open, ::toggle, onOpenRow)
                moveGroup("原地改名", "rename", sum.renames, s, open, ::toggle, onOpenRow)
                moveGroup("已在目标位置，不动", "same", sum.unchanged, s, open, ::toggle, onOpenRow)
                item {
                    if (sum.notIncluded > 0) Text(
                        "待确认、未匹配和已排除的 ${sum.notIncluded} 个不受影响",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Text("目标已存在的文件不会被覆盖；整理后可在菜单里撤销。", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 6.dp))
                }
            }
        },
        confirmButton = { Button(onClick = onConfirm) { Text("开始整理") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun androidx.compose.foundation.lazy.LazyListScope.moveGroup(
    title: String, id: String, list: List<PlannedMove>, s: AppSettings,
    open: String, toggle: (String) -> Unit, onOpenRow: (String) -> Unit,
) {
    if (list.isEmpty()) return
    item(key = "h-$id") { GroupHeader(title, list.size, Color.Unspecified, open == id) { toggle(id) } }
    if (open != id) return
    items(list.take(LIST_LIMIT), key = { "$id-${it.key}" }) { m -> MoveLine(m, s, showTarget = id != "same", mark = null) { onOpenRow(m.key) } }
    if (list.size > LIST_LIMIT) item {
        Text("…还有 ${list.size - LIST_LIMIT} 个", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun GroupHeader(title: String, count: Int, color: Color, expanded: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$title：$count 个", color = color, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Text(if (expanded) "收起" else "查看", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun MoveLine(m: PlannedMove, s: AppSettings, showTarget: Boolean, mark: String?, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick)
            .padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (mark != null) {
                Pill(mark, if (mark == "整理") Green else Gray)
                Spacer(Modifier.width(6.dp))
            }
            Text(m.from, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (showTarget) Text("→ " + shortTarget(m.to, s), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

private fun formatTime(ms: Long): String =
    java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.CHINA).format(java.util.Date(ms))

@Composable
private fun UndoConfirmDialog(sum: UndoSummary, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("撤销 ${formatTime(sum.time)} 的整理？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${sum.moved} 个文件移回原位置")
                if (sum.created > 0) Text("删除当时新写入的 ${sum.created} 个 NFO / 图片")
                Text("当时新建、现已为空的文件夹会删除；原有文件和文件夹不受影响。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (sum.samples.isNotEmpty()) {
                    Text("例如：", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
                    sum.samples.forEach { (from, to) ->
                        Text(from.substringAfterLast('/'), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("  ↩ $to", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (sum.moved > sum.samples.size) Text("…等 ${sum.moved} 个", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline)
                }
            }
        },
        confirmButton = { Button(onClick = onConfirm) { Text("撤销") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun InfoLine(label: String, value: String, color: Color = Color.Unspecified) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.width(56.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

/** 详情里对单个文件直接整理 / 刮削 */
@Composable
private fun RowActions(vm: MainViewModel, row: MediaRow, onDone: () -> Unit) {
    val cat = row.category
    val canOrganize = row.chosen != null && row.match.file != null &&
        cat in setOf(RowCategory.READY, RowCategory.PENDING, RowCategory.FAILED, RowCategory.EXCLUDED)
    val canScrape = cat == RowCategory.DONE
    if (!canOrganize && !canScrape) return
    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (canOrganize) Button(onClick = { vm.organizeOne(row.key); onDone() }, enabled = !vm.busy) {
            Text(when (cat) {
                RowCategory.PENDING -> "确认并整理"
                RowCategory.FAILED -> "重试整理"
                else -> "整理此项"
            } + if (vm.settings.autoScrape) "+刮削" else "")
        }
        if (canScrape) OutlinedButton(onClick = { vm.scrapeOne(row.key); onDone() }, enabled = !vm.busy) {
            Text(if (vm.settings.autoScrape) "重新刮削" else "刮削此项")
        }
    }
    if (canOrganize) Text(
        "单独整理会并入「撤销上次整理」的记录",
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RowDetailDialog(vm: MainViewModel, row: MediaRow, onDismiss: () -> Unit) {
    val p = row.match.parsed
    val scope = rememberCoroutineScope()
    var query by remember(row.key) { mutableStateOf(p.title) }
    var tv by remember(row.key) { mutableStateOf(row.isTv) }
    var results by remember(row.key) { mutableStateOf<List<TmdbSearchResult>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    val sameCount = remember(row.key) { vm.sameGroupPendingCount(row.key) }
    var applySame by remember(row.key) { mutableStateOf(true) }
    // 已完成的也能改匹配（选错了可以纠正），改完回到「可整理」再整理一次
    val canChange = !vm.busy
    val canExclude = (row.outcome == null || row.category == RowCategory.FAILED) && !vm.busy

    fun doSearch() {
        if (query.isBlank()) return
        searching = true; searchError = null
        scope.launch {
            try {
                results = vm.searchTmdb(query.trim(), tv)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                searchError = "搜索失败：${e.message}"
            } finally {
                searching = false
            }
        }
    }

    val options = results ?: (listOfNotNull(row.match.best) + row.match.alternatives)
    val parsedSummary = listOfNotNull(
        if (p.isEpisode) "剧集 S%02dE%02d".format(p.season ?: 1, p.episode) else "电影",
        p.year?.toString(), p.resolution, p.source, p.releaseGroup,
    ).joinToString(" · ")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(displayTitle(row), maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            LazyColumn {
                item {
                    InfoLine("原文件", row.match.file?.path ?: p.rawName)
                    InfoLine("识别为", "${p.title} · $parsedSummary")
                    row.chosen?.let {
                        InfoLine("当前匹配", "${it.title} (${it.year ?: "?"}) · TMDB ${it.id}" +
                            if (it == row.match.best) " · 置信度 %.0f%%".format(row.match.score * 100) else "")
                    }
                    row.plan?.let { InfoLine("整理到", it.targetPath) }
                    if (row.category == RowCategory.FAILED) InfoLine("错误", row.outcome.orEmpty(), Red)
                    RowActions(vm, row, onDone = onDismiss)
                    Spacer(Modifier.height(12.dp))
                }
                if (canChange) item {
                    if (row.category == RowCategory.DONE) Text(
                        "这个文件已整理过。改选匹配后会回到「可整理」，再点整理会从当前位置移到新目标；" +
                            "原文件夹里已生成的 NFO 和图片不会自动删除。",
                        style = MaterialTheme.typography.bodySmall, color = Orange,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    OutlinedTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        label = { Text("在 TMDB 搜索") },
                        trailingIcon = { IconButton(onClick = ::doSearch) { Icon(Icons.Default.Search, contentDescription = "搜索") } },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { doSearch() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !tv, onClick = { tv = false; if (results != null) doSearch() }, label = { Text("电影") })
                        FilterChip(selected = tv, onClick = { tv = true; if (results != null) doSearch() }, label = { Text("剧集") })
                    }
                    if (sameCount > 0) Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = applySame, onCheckedChange = { applySame = it })
                        Text("同时用于其他 $sameCount 个同名待确认文件", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        if (results == null) "候选结果（点击选用）" else "搜索结果（点击选用）",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                    )
                    when {
                        searching -> LinearProgressIndicator(Modifier.fillMaxWidth())
                        searchError != null -> Text(searchError!!, color = Red, style = MaterialTheme.typography.bodySmall)
                        options.isEmpty() -> Text("没有结果，换个名字试试（中文名、英文原名都可以）", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (canChange && !searching) items(options, key = { "${it.type}-${it.id}" }) { opt ->
                    val current = row.chosen?.let { it.id == opt.id && it.type == opt.type } == true
                    Column(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (current) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                            .clickable { vm.choose(row.key, opt, applySame); onDismiss() }
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                    ) {
                        Text("${opt.title} (${opt.year ?: "?"})", fontWeight = FontWeight.Medium)
                        Text(
                            listOfNotNull(if (opt.type == MediaType.TV) "剧集" else "电影", opt.originalTitle, "TMDB ${opt.id}")
                                .joinToString(" · "),
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.outline,
                        )
                        opt.overview?.takeIf { it.isNotBlank() }?.let {
                            Text(it, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (canChange && row.category == RowCategory.PENDING)
                Button(onClick = { vm.confirm(setOf(row.key)); onDismiss() }) { Text("确认当前匹配") }
            else TextButton(onClick = onDismiss) { Text("关闭") }
        },
        dismissButton = {
            Row {
                if (canExclude) TextButton(onClick = { vm.setExcluded(setOf(row.key), !row.excluded); onDismiss() }) {
                    Text(if (row.excluded) "取消排除" else "排除")
                }
                if (canChange && row.category == RowCategory.PENDING) TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
    )
}
