package com.example.mediaorganizer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mediaorganizer.*
import com.example.mediaorganizer.metadata.MediaMatcher
import com.example.mediaorganizer.naming.Conflict
import com.example.mediaorganizer.naming.ConflictAction
import com.example.mediaorganizer.naming.ConflictKind
import com.example.mediaorganizer.metadata.MediaType
import com.example.mediaorganizer.metadata.MetaSource
import com.example.mediaorganizer.metadata.TmdbSearchResult
import com.example.mediaorganizer.parser.MediaNameParser
import com.example.mediaorganizer.storage.RemoteFile
import com.example.mediaorganizer.settings.AppSettings
import com.example.mediaorganizer.settings.sourceMissing
import com.example.mediaorganizer.settings.ThemeMode
import com.example.mediaorganizer.storage.CleanupMode
import com.example.mediaorganizer.storage.CleanupTarget
import com.example.mediaorganizer.storage.StorageFactory
import com.example.mediaorganizer.storage.StorageType
import com.example.mediaorganizer.storage.normalizePath
import com.example.mediaorganizer.storage.parentPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val Green: Color @Composable get() = LocalStatusColors.current.ok
private val Orange: Color @Composable get() = LocalStatusColors.current.warn
private val Red: Color @Composable get() = LocalStatusColors.current.bad
private val Gray: Color @Composable get() = LocalStatusColors.current.muted

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainScreen(
    vm: MainViewModel, openNaming: () -> Unit, openScrape: () -> Unit,
    /** 「撤销上次整理」的确认框：菜单在首页共用的标题栏里，状态由首页传进来 */
    askUndo: Boolean, onAskUndo: (Boolean) -> Unit,
) {
    val s = vm.settings
    var editConn by rememberSaveable { mutableStateOf(StorageFactory.missing(s) != null || s.sourceMissing() != null) }
    var detailKey by remember { mutableStateOf<String?>(null) }
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
        scope.launch { listState.animateScrollToItem(3) }     // 跳到筛选栏：前面是连接、目录、设置三张卡片
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        // 标题栏在首页（两页共用），这里只有多选时的操作栏
        topBar = { if (selecting) SelectionBar(vm, visibleKeys = visible.map { it.key }) },
        bottomBar = {
            BottomBar(
                vm, counts,
                onOrganize = { vm.startOrganize() },
                onShowPending = { showFilter(it) },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(top = 6.dp, bottom = 16.dp),
        ) {
            item(key = "conn") {
                ConnectionCard(vm, expanded = editConn, onToggle = { editConn = !editConn }, openScrape = openScrape)
            }
            item(key = "dirs") { DirectoryCard(vm) }
            item(key = "links") { SettingsLinks(vm, openNaming, openScrape) }

            if (vm.rows.isEmpty() && vm.task != Task.SCAN) {
                item(key = "empty") { EmptyState(vm) }
                return@LazyColumn
            }

            stickyHeader(key = "filters") { FilterBar(vm, counts) }
            vm.filter?.let { f -> item(key = "hint") { FilterHint(f) } }
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
    vm.qr?.let { q ->
        QrLoginDialog(q, onCancel = vm::cancelQr, onRetry = {
            if (vm.settings.storageType == StorageType.PAN115) vm.login115() else vm.loginAliyun()
        })
    }
    vm.leftovers?.let { found ->
        LeftoversDialog(found, vm.settings.cleanupMode, vm.settings.scanDir, onDelete = vm::deleteLeftovers, onDismiss = vm::dismissLeftovers)
    }
    if (askUndo) vm.undoSummary()?.let { sum ->
        UndoConfirmDialog(sum, onConfirm = { onAskUndo(false); vm.undo() }, onDismiss = { onAskUndo(false) })
    }
    // 预检页在最外层显示（见 AppPages），从那里点某个文件时在这里打开它的详情
    LaunchedEffect(vm.openDetail) {
        vm.openDetail?.let { detailKey = it; vm.openDetail = null }
    }
}

// ============================ 顶栏 ============================

@Composable
internal fun MainMenu(vm: MainViewModel, openNaming: () -> Unit, openScrape: () -> Unit, onUndo: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, contentDescription = "更多") }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        fun go(action: () -> Unit) { open = false; action() }
        if (vm.rows.isNotEmpty())
            DropdownMenuItem(text = { Text("重新扫描") }, enabled = !vm.busy, onClick = { go(vm::scan) })
        if (!vm.settings.autoScrape && vm.rows.any { it.category == RowCategory.DONE })
            DropdownMenuItem(text = { Text("刮削已整理的文件") }, enabled = !vm.busy, onClick = { go { vm.scrapeOnly() } })
        val undo = if (vm.canUndo) vm.undoSummary() else null
        DropdownMenuItem(
            text = {
                Column {
                    Text("撤销上次整理")
                    if (undo == null) Text("暂无可撤销的操作", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline)
                    else Text(
                        "${formatTime(undo.time)} · ${undo.moved} 个文件",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                    )
                }
            },
            enabled = undo != null && !vm.busy,
            onClick = { go(onUndo) },
        )
        DropdownMenuItem(
            text = {
                Column {
                    Text("清理残留文件夹")
                    Text("找出扫描目录里没有视频的旧文件夹", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline)
                }
            },
            enabled = !vm.busy && StorageFactory.missing(vm.settings) == null,
            onClick = { go(vm::findLeftovers) },
        )
        HorizontalDivider()
        DropdownMenuItem(text = { Text("命名规则") }, onClick = { go(openNaming) })
        DropdownMenuItem(text = { Text("刮削设置") }, onClick = { go(openScrape) })
        // 点一下切换到下一种，菜单不关，方便直接看效果
        val theme = vm.settings.themeMode
        DropdownMenuItem(
            text = { Text("外观 · ${theme.label}") },
            onClick = { vm.updateSettings(vm.settings.copy(themeMode = ThemeMode.entries[(theme.ordinal + 1) % ThemeMode.entries.size])) },
        )
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

// ============================ 媒体库连接卡片 ============================

/**
 * 媒体库连接：平时只显示摘要（名称、连接状态、地址、最近测试），点「连接设置」才展开完整表单。
 * 连上以后不用每次进来都面对整套配置；没配好时默认展开。
 */
@Composable
private fun ConnectionCard(vm: MainViewModel, expanded: Boolean, onToggle: () -> Unit, openScrape: () -> Unit) {
    val s = vm.settings
    fun up(n: AppSettings) = vm.updateSettings(n)
    val missing = StorageFactory.missing(s)
    val st = LocalStatusColors.current
    val (stateText, stateColor) = when {
        missing != null -> "未配置" to st.muted
        vm.task == Task.TEST -> "测试中" to st.warn
        vm.connState == ConnState.OK -> "已连接" to st.ok
        vm.connState == ConnState.FAIL -> "连接失败" to st.bad
        else -> "未测试" to st.muted
    }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var askRemove by remember { mutableStateOf(false) }

    SectionCard {
        // 标题行：媒体库名称（点开切换）+ 状态标签
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                Row(
                    Modifier.clip(RoundedCornerShape(8.dp)).clickable { menu = true }.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(s.libraryName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Icon(Icons.Default.ArrowDropDown, contentDescription = "切换媒体库", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    vm.libraries.forEach { lib ->
                        DropdownMenuItem(
                            leadingIcon = { if (lib.id == s.activeLibrary) Icon(Icons.Default.Check, null) else Spacer(Modifier.size(24.dp)) },
                            text = {
                                Column {
                                    Text(lib.name)
                                    Text(
                                        lib.storageType.label + when (lib.lastTestOk) { true -> " · 已连接"; false -> " · 连接失败"; null -> "" },
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                            },
                            enabled = !vm.busy,
                            onClick = { menu = false; vm.switchLibrary(lib.id) },
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("添加媒体库") }, enabled = !vm.busy,
                        onClick = { menu = false; vm.addLibrary(); if (!expanded) onToggle() })
                    DropdownMenuItem(text = { Text("重命名当前媒体库") }, onClick = { menu = false; renaming = true })
                    if (vm.libraries.size > 1) DropdownMenuItem(
                        text = { Text("删除当前媒体库", color = Red) }, enabled = !vm.busy,
                        onClick = { menu = false; askRemove = true },
                    )
                }
            }
            StatusBadge(stateText, stateColor, loading = vm.task == Task.TEST)
        }

        // 摘要：类型 · 地址；失败时一句话原因
        Text(
            s.storageType.label + (if (missing == null) " · " + StorageFactory.describe(s) else " · 还缺 $missing"),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp),
        )
        if (vm.connState == ConnState.FAIL && missing == null && vm.connMessage.isNotEmpty()) Text(
            vm.connMessage, style = MaterialTheme.typography.bodySmall, color = Red, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        vm.connChecks.forEach { ConnCheckRow(it) }
        s.sourceMissing()?.let { m ->
            Text(
                "未填写 $m，无法识别影片 · 去设置",
                color = Orange, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp).clickable(onClick = openScrape),
            )
        }

        // 操作行：最近测试时间作为辅助文字在左，「重新测试」「连接设置」是轻量的文字按钮
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (s.lastTestAt > 0 && missing == null) "最近测试 · " + formatTime(s.lastTestAt) else "",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.weight(1f),
            )
            if (!expanded) TextButton(onClick = vm::testConnection, enabled = !vm.busy && missing == null) {
                Text(if (vm.connState == ConnState.UNTESTED) "测试连接" else "重新测试")
            }
            TextButton(onClick = onToggle) {
                Text(if (expanded) "收起" else "连接设置")
                Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, Modifier.size(18.dp))
            }
        }

        if (expanded) {
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            StorageFields(vm, s, ::up)
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                OutlinedButton(onClick = vm::testConnection, enabled = !vm.busy && missing == null) { Text("测试连接") }
                FilledTonalButton(onClick = onToggle) { Text("保存并收起") }
            }
        }
    }

    if (renaming) {
        var name by remember { mutableStateOf(s.libraryName) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("重命名媒体库") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("名称") }) },
            confirmButton = { TextButton(onClick = { vm.renameLibrary(name); renaming = false }, enabled = name.isNotBlank()) { Text("保存") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("取消") } },
        )
    }
    if (askRemove) AlertDialog(
        onDismissRequest = { askRemove = false },
        title = { Text("删除「${s.libraryName}」？") },
        text = { Text("只删除 App 里保存的这个媒体库配置，不会动媒体库里的任何文件。") },
        confirmButton = { TextButton(onClick = { vm.removeLibrary(); askRemove = false }) { Text("删除", color = Red) } },
        dismissButton = { TextButton(onClick = { askRemove = false }) { Text("取消") } },
    )
}

/** 状态标签：小圆点 + 文字，颜色之外还有文字说明 */
@Composable
internal fun StatusBadge(text: String, color: Color, loading: Boolean = false) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 1.5.dp, color = color)
        else Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.Medium)
    }
}

// ============================ 媒体目录 ============================

/** 扫描来源和整理目标分开写清楚：来源是要整理的下载目录，目标是整理后放进去的库 */
@Composable
private fun DirectoryCard(vm: MainViewModel) {
    val s = vm.settings
    val canBrowse = StorageFactory.missing(s) == null
    SectionCard {
        SectionTitle("媒体目录", "从哪里找视频、整理到哪里" + if (s.storageType == StorageType.LOCAL) "（相对所选文件夹，/ 就是它本身）" else "")
        Text("扫描来源", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
        DirRow(MainViewModel.DirSetting.SCAN, s.scanDir, AppIcons.FolderOpen, canBrowse, vm::openDirPicker) { vm.updateSettings(s.copy(scanDir = it)) }
        Text("整理目标", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = 10.dp))
        DirRow(MainViewModel.DirSetting.MOVIE, s.movieRoot, AppIcons.Movie, canBrowse, vm::openDirPicker) { vm.updateSettings(s.copy(movieRoot = it)) }
        DirRow(MainViewModel.DirSetting.TV, s.tvRoot, AppIcons.Tv, canBrowse, vm::openDirPicker) { vm.updateSettings(s.copy(tvRoot = it)) }
    }
}

/**
 * 一个目录：图标 + 名称 + 单行路径（长路径截断，不撑高）。点整行在媒体库里选文件夹，
 * 右边的笔可以手动输入；还没连上媒体库时点整行也是手动输入。
 */
@Composable
internal fun DirRow(
    field: MainViewModel.DirSetting, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, canBrowse: Boolean,
    onBrowse: (MainViewModel.DirSetting) -> Unit, onChange: (String) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
            .clickable { if (canBrowse) onBrowse(field) else editing = true }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(field.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                value.ifBlank { "未设置，点这里选择" }, style = MaterialTheme.typography.bodySmall,
                color = if (value.isBlank()) Orange else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = { editing = true }) {
            Icon(Icons.Default.Edit, contentDescription = "手动输入${field.label}", tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(20.dp))
        }
    }
    if (editing) {
        var text by remember { mutableStateOf(value) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(field.label) },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, singleLine = true, label = { Text("路径") },
                    shape = MaterialTheme.shapes.small, colors = appFieldColors(),
                )
            },
            confirmButton = { TextButton(onClick = { onChange(text.trim()); editing = false }) { Text("保存") } },
            dismissButton = {
                Row {
                    if (canBrowse) TextButton(onClick = { editing = false; onBrowse(field) }) { Text("浏览…") }
                    TextButton(onClick = { editing = false }) { Text("取消") }
                }
            },
        )
    }
}

// ============================ 设置入口 ============================

/** 命名规则、刮削设置：图标 + 标题 + 一句说明的紧凑列表，不和主操作抢视线 */
@Composable
private fun SettingsLinks(vm: MainViewModel, openNaming: () -> Unit, openScrape: () -> Unit) {
    SectionCard {
        SettingLink(AppIcons.Tune, "命名规则 · ${vm.settings.player}", "整理文件时使用的命名规范", openNaming)
        HorizontalDivider(Modifier.padding(start = 48.dp), color = MaterialTheme.colorScheme.outlineVariant)
        SettingLink(AppIcons.Image, "刮削设置", "元数据来源、海报与 NFO", openScrape)
    }
}

@Composable
internal fun SettingLink(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f).padding(start = 26.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline)
    }
}

/** 媒体库类型选择 + 该类型的连接参数 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun StorageFields(vm: MainViewModel, s: AppSettings, up: (AppSettings) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val pickTree = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            // 持久授权，App 重启后仍可访问
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            up(s.copy(localTreeUri = uri.toString()))
        }
    }

    // 存储类型：一个下拉框代替一排描边按钮，选中的一目了然
    var typeMenu by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = typeMenu, onExpandedChange = { typeMenu = it }) {
        OutlinedTextField(
            value = s.storageType.label, onValueChange = {}, readOnly = true, singleLine = true,
            label = { Text("存储类型") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeMenu) },
            shape = MaterialTheme.shapes.small, colors = appFieldColors(),
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth().padding(vertical = 3.dp),
        )
        ExposedDropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
            StorageType.entries.filter { it.ready }.forEach { t ->
                DropdownMenuItem(
                    text = { Text(t.label) },
                    leadingIcon = { if (s.storageType == t) Icon(Icons.Default.Check, null) else Spacer(Modifier.size(24.dp)) },
                    onClick = { typeMenu = false; up(s.copy(storageType = t)) },
                )
            }
        }
    }
    when (s.storageType) {
        StorageType.WEBDAV -> {
            SettingField("WebDAV 地址", s.webdavUrl) { up(s.copy(webdavUrl = it)) }
            SettingField("用户名", s.username) { up(s.copy(username = it)) }
            SettingField("密码", s.password, password = true) { up(s.copy(password = it)) }
            Text("Alist / OpenList / CloudDrive2 挂载的网盘也可以用 WebDAV 连接",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
        StorageType.SMB -> {
            SettingField("主机（IP 或名称，可带端口）", s.smbHost) { up(s.copy(smbHost = it)) }
            SettingField("共享名（如 video）", s.smbShare) { up(s.copy(smbShare = it)) }
            SettingField("用户名（留空为访客）", s.smbUser) { up(s.copy(smbUser = it)) }
            SettingField("密码", s.smbPassword, password = true) { up(s.copy(smbPassword = it)) }
            SettingField("域（一般留空）", s.smbDomain) { up(s.copy(smbDomain = it)) }
        }
        StorageType.LOCAL -> {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                Text(
                    if (s.localTreeUri.isBlank()) "未选择" else StorageFactory.describe(s),
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                OutlinedButton(onClick = { pickTree.launch(null) }) { Text(if (s.localTreeUri.isBlank()) "选择文件夹" else "重新选择") }
            }
            Text("可选手机里的文件夹，或插着的 U 盘 / 移动硬盘。选包含下载和影视库的上层文件夹。",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
        StorageType.PAN115 -> {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                Text(if (s.pan115Cookie.isBlank()) "未登录" else "已登录", modifier = Modifier.weight(1f))
                if (s.pan115Cookie.isNotBlank()) TextButton(onClick = { up(s.copy(pan115Cookie = "")) }) { Text("退出") }
                OutlinedButton(onClick = vm::login115) { Text(if (s.pan115Cookie.isBlank()) "扫码登录" else "重新登录") }
            }
            Text("非官方接口，115 改版后可能需要更新 App。删除的文件会进 115 回收站。",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
        StorageType.ALIYUN -> {
            SettingField("开放平台 AppID", s.aliClientId) { up(s.copy(aliClientId = it)) }
            SettingField("开放平台 App Secret", s.aliClientSecret, password = true) { up(s.copy(aliClientSecret = it)) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                FilterChip(selected = s.aliResourceDrive, onClick = { up(s.copy(aliResourceDrive = true)) }, label = { Text("资源库") })
                FilterChip(selected = !s.aliResourceDrive, onClick = { up(s.copy(aliResourceDrive = false)) }, label = { Text("备份盘") })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (s.aliRefreshToken.isBlank()) "未授权" else "已授权", modifier = Modifier.weight(1f))
                if (s.aliRefreshToken.isNotBlank()) TextButton(onClick = { up(s.copy(aliRefreshToken = "")) }) { Text("取消授权") }
                OutlinedButton(
                    onClick = vm::loginAliyun,
                    enabled = s.aliClientId.isNotBlank() && s.aliClientSecret.isNotBlank(),
                ) { Text(if (s.aliRefreshToken.isBlank()) "扫码授权" else "重新授权") }
            }
            Text("需要先在阿里云盘开放平台（www.alipan.com/developer）创建应用，拿到 AppID 和 Secret。删除的文件会进回收站。",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

/** 网盘扫码登录：显示二维码，可保存到相册给同一台手机上的 App 识别 */
@Composable
private fun QrLoginDialog(qr: MainViewModel.QrLogin, onCancel: () -> Unit, onRetry: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val bitmap = remember(qr.image) {
        qr.image?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
    }
    var saved by remember(qr.image) { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(qr.title) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                if (bitmap != null) androidx.compose.foundation.Image(
                    bitmap, contentDescription = "登录二维码",
                    modifier = Modifier.size(220.dp).alpha(if (qr.failed) 0.25f else 1f),
                ) else if (!qr.failed) CircularProgressIndicator(Modifier.padding(32.dp))
                Text(qr.hint, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                    color = if (qr.failed) Red else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                saved?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Green) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    if (qr.image != null && !qr.failed && android.os.Build.VERSION.SDK_INT >= 29) OutlinedButton(onClick = {
                        saved = if (saveToGallery(context, qr.image)) "已保存到相册「影视整理」" else "保存失败"
                    }) { Text("保存到相册") }
                    if (qr.openUrl != null && !qr.failed) OutlinedButton(onClick = {
                        runCatching {
                            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(qr.openUrl)))
                        }
                    }) { Text("在本机打开") }
                }
            }
        },
        confirmButton = {
            if (qr.failed) Button(onClick = onRetry) { Text("重新获取") } else TextButton(onClick = onCancel) { Text("取消") }
        },
        dismissButton = { if (qr.failed) TextButton(onClick = onCancel) { Text("关闭") } },
    )
}

private fun saveToGallery(context: android.content.Context, png: ByteArray): Boolean = runCatching {
    val values = android.content.ContentValues().apply {
        put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "login-qr-${System.currentTimeMillis()}.png")
        put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/影视整理")
    }
    val uri = context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
    context.contentResolver.openOutputStream(uri)!!.use { it.write(png) }
    true
}.getOrDefault(false)

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

/**
 * 还没扫描时：图标 + 一句话说明能做什么 + 开始前的两项检查（媒体库连上没有、扫描哪个目录），
 * 主按钮「开始扫描」在底栏，随时够得着。
 */
@Composable
private fun EmptyState(vm: MainViewModel) {
    val s = vm.settings
    val st = LocalStatusColors.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) { Icon(AppIcons.Movie, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(34.dp)) }
        Spacer(Modifier.height(16.dp))
        Text("准备整理你的媒体库", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            "扫描目录里的视频，识别影片后先列出来给你核对，确认后才会移动、改名并刮削元数据。",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        val connected = vm.connState == ConnState.OK
        val missing = StorageFactory.missing(s)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            CheckLine(
                ok = connected,
                text = when {
                    missing != null -> "媒体库还没配置：$missing"
                    connected -> "媒体库已连接"
                    vm.connState == ConnState.FAIL -> "媒体库连接失败，先检查连接设置"
                    else -> "媒体库还没测试连接（扫描时会自动连接）"
                },
                color = if (connected) st.ok else if (vm.connState == ConnState.FAIL || missing != null) st.bad else st.muted,
            )
            CheckLine(
                ok = s.scanDir.isNotBlank(),
                text = if (s.scanDir.isBlank()) "还没设置扫描目录" else "扫描 ${s.scanDir}",
                color = if (s.scanDir.isBlank()) st.warn else st.ok,
            )
        }
        if (vm.status.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(vm.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun CheckLine(ok: Boolean, text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (ok) "✓" else "•", color = color, fontWeight = FontWeight.Bold, modifier = Modifier.width(20.dp), textAlign = TextAlign.Center)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            // 类型筛选紧跟「全部」放第一行，状态筛选在下面
            for ((t, label) in listOf(MediaType.MOVIE to "只看电影", MediaType.TV to "只看剧集")) {
                FilterChip(
                    selected = vm.typeFilter == t,
                    onClick = { vm.typeFilter = if (vm.typeFilter == t) null else t },
                    label = { Text(label) },
                )
            }
            for (c in listOf(RowCategory.PENDING, RowCategory.READY, RowCategory.UNMATCHED, RowCategory.FAILED, RowCategory.DONE, RowCategory.EXCLUDED, RowCategory.DELETED)) {
                val n = counts[c] ?: 0
                if (n == 0 && vm.filter != c && c !in setOf(RowCategory.PENDING, RowCategory.READY)) continue
                // 数字按状态着色（待确认橙、可整理绿、未匹配 / 失败红），一眼看出哪些要处理
                val color = when (c) {
                    RowCategory.PENDING -> Orange
                    RowCategory.READY -> Green
                    RowCategory.UNMATCHED, RowCategory.FAILED -> Red
                    else -> Color.Unspecified
                }
                FilterChip(selected = vm.filter == c, onClick = { vm.filter = c }, label = {
                    Row {
                        Text(c.label)
                        Text(" $n", color = if (n > 0) color else Color.Unspecified, fontWeight = FontWeight.SemiBold)
                    }
                })
            }
        }
    }
}

/** 选中某个状态筛选时，说明这类条目是什么、能怎么处理 */
@Composable
private fun FilterHint(c: RowCategory) {
    val text = when (c) {
        RowCategory.PENDING -> "识别出了影片，但把握不大（百分比是可信度）。点「确认匹配」采用，点「更换」挑别的结果或手动搜索；" +
            "确认后进入「可整理」。长按可多选，批量确认。"
        RowCategory.READY -> "已确认匹配。点底部「整理」会按命名规则移动、改名（开了自动刮削会一起刮削）。" +
            "点条目可看目标路径或改匹配；长按多选后可只整理选中的。"
        RowCategory.UNMATCHED -> "没在 TMDB 找到对应影片，整理时不会处理。点「手动搜索」输入中文名、原名或 tmdb:ID；确实不需要的可以排除。"
        RowCategory.FAILED -> "整理时出错，红字是原因。点条目查看详情：可重试、改匹配；「目标已存在」的可以对比后决定保留哪个。"
        RowCategory.DONE -> "已移动、改名到影视库。点条目可重新刮削、打开所在文件夹，或改匹配后重新整理；" +
            "整批可在右上角菜单「撤销上次整理」。"
        RowCategory.EXCLUDED -> "你选择了不整理（包括预检里选「跳过」的），点「整理」时会跳过这些。" +
            "点条目选「取消排除」，或长按多选后点「恢复」，可放回待处理。"
        RowCategory.DELETED -> "源文件已在本应用里删除（预检「删除此文件」、对比后删除或文件管理里删除），这里只是记录。" +
            "115 / 阿里云盘删除的可在网盘回收站找回；重新扫描后不再显示。"
    }
    Surface(
        shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
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

@Composable
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
        RowCategory.DELETED -> "已删除" to Gray
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
                Task.PRECHECK, Task.ORGANIZE, Task.SCRAPE, Task.UNDO, Task.CLEAN -> {
                    Text(vm.status, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                    val p = vm.progress
                    if (p != null && p.second > 0) LinearProgressIndicator(progress = { p.first.toFloat() / p.second }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                else -> if (vm.selected.isNotEmpty()) {
                    // 多选时：只整理 / 刮削选中的这部分，其余不动
                    val sel = vm.selected
                    val ready = vm.organizableCount(sel)
                    val done = vm.scrapableCount(sel)
                    val other = sel.size - ready - done
                    Text(
                        "已选 ${sel.size} 项：可整理 $ready 个，已整理 $done 个" +
                            if (other > 0) "；另 $other 个未确认或未匹配，不会处理" else "",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val m = Modifier.weight(1f).height(48.dp)
                        OutlinedButton(onClick = { vm.scrapeOnly(sel) }, enabled = !vm.busy && done > 0, modifier = m) {
                            Text(if (done > 0) "刮削所选 $done" else "刮削所选")
                        }
                        Button(onClick = { vm.startOrganize(sel) }, enabled = !vm.busy && ready > 0, modifier = m) {
                            Text(if (ready > 0) "整理所选 $ready" else "整理所选")
                        }
                    }
                } else if (vm.rows.isEmpty()) {
                    Button(onClick = vm::scan, enabled = !vm.busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Icon(Icons.Default.Search, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("开始扫描", fontWeight = FontWeight.SemiBold)
                    }
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

private const val LIST_LIMIT = 200   // 每组最多列这么多条，避免几千条撑爆页面


internal fun sizeText(bytes: Long?): String = when {
    bytes == null || bytes <= 0 -> ""
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
    else -> "%.0f KB".format(bytes / 1024.0)
}

/** 目录树里的一行 */
private data class TreeLine(val depth: Int, val name: String, val isDir: Boolean, val isNew: Boolean, val generated: Boolean)

/** 把一堆完整路径排成缩进的目录树；videos 是视频，generated 是刮削会写的文件 */
private fun buildTree(videos: List<String>, generated: List<String>, newDirs: Set<String>, limit: Int = 2000): List<TreeLine> {
    val gen = generated.toSet()
    val all = (videos + generated).distinct().sorted()
    val seenDirs = HashSet<String>()
    val out = mutableListOf<TreeLine>()
    for (p in all) {
        val segs = p.trim('/').split('/')
        var cur = ""
        for ((i, seg) in segs.withIndex()) {
            cur += "/$seg"
            val last = i == segs.lastIndex
            if (!last) {
                if (seenDirs.add(cur)) out += TreeLine(i, seg, isDir = true, isNew = cur in newDirs, generated = false)
            } else out += TreeLine(i, seg, isDir = false, isNew = false, generated = p in gen)
            if (out.size >= limit) return out
        }
    }
    return out
}

/** 整理前预检：概览 / 问题（逐项选择处理方式）/ 整理后目录树 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PrecheckScreen(
    vm: MainViewModel, pc: MainViewModel.PrecheckState, s: AppSettings,
    onOpenRow: (String) -> Unit,
) {
    val conflicts = pc.result.conflicts
    var tab by remember { mutableIntStateOf(if (conflicts.isNotEmpty()) 1 else 0) }
    var open by remember { mutableStateOf("") }
    fun toggle(id: String) { open = if (open == id) "" else id }
    val sum = pc.summary
    val cloud = s.storageType == StorageType.PAN115 || s.storageType == StorageType.ALIYUN

    // 按当前选择估算：会整理多少、跳过多少、删除多少
    val chosen = conflicts.groupingBy { vm.actionFor(it) }.eachCount()
    val skipCount = chosen[ConflictAction.SKIP] ?: 0
    val deleteCount = chosen[ConflictAction.DELETE_SOURCE] ?: 0
    val runCount = sum.total - skipCount - deleteCount

    // 盖在主界面上的全屏页，不用 Dialog：对话框窗口在部分系统上高度算错，底部按钮会被挤出屏幕
    Surface(Modifier.fillMaxSize()) {
        BackHandler(onBack = vm::dismissPrecheck)
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = vm::dismissPrecheck) { Icon(Icons.Default.Close, "取消") } },
                    title = { Text("整理前预检") },
                )
            },
            bottomBar = {
                Surface(tonalElevation = 3.dp) {
                    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text(
                            buildString {
                                append("将整理 $runCount 个")
                                if (skipCount > 0) append("，跳过 $skipCount 个")
                                if (deleteCount > 0) append("，删除 $deleteCount 个")
                                if (sum.notIncluded > 0) append(
                                    if (pc.only != null) "；未选中的 ${sum.notIncluded} 个不受影响" else "；待确认等 ${sum.notIncluded} 个不受影响"
                                )
                            },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        Button(onClick = vm::executeChecked, enabled = runCount + deleteCount > 0, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                            Text(if (runCount > 0) "开始整理 $runCount 项" else "执行")
                        }
                    }
                }
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                TabRow(selectedTabIndex = tab) {
                    Tab(tab == 0, { tab = 0 }, text = { Text("概览") })
                    Tab(tab == 1, { tab = 1 }, text = { Text(if (conflicts.isEmpty()) "问题" else "问题 ${conflicts.size}") })
                    Tab(tab == 2, { tab = 2 }, text = { Text("目录树") })
                }
                when (tab) {
                    0 -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
                        item {
                            Text(
                                if (conflicts.isEmpty()) "✓ 没有发现冲突，目标位置都可以安全写入"
                                else "⚠ 发现 ${conflicts.size} 个问题，已按「不动你的文件」的方式预选处理，可在「问题」里逐项修改",
                                color = if (conflicts.isEmpty()) Green else Orange,
                                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp),
                            )
                            if (pc.result.newDirs.isNotEmpty()) Text("将新建 ${pc.result.newDirs.size} 个文件夹",
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 4.dp))
                            if (s.autoScrape) Text(
                                "整理后刮削，预计写入 ${pc.scrapeFiles.size}${if (pc.scrapeCapped) "+" else ""} 个 NFO / 图片（见「目录树」）",
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 4.dp),
                            )
                        }
                        moveGroup("移动到影视库", "move", sum.moves, s, open, ::toggle, onOpenRow)
                        moveGroup("原地改名", "rename", sum.renames, s, open, ::toggle, onOpenRow)
                        moveGroup("已在目标位置，不动", "same", sum.unchanged, s, open, ::toggle, onOpenRow)
                        item {
                            Text(
                                when (s.cleanupMode) {
                                    CleanupMode.OFF -> "原文件夹保留不动。"
                                    CleanupMode.EMPTY_ONLY -> "整理后删除变空的原文件夹。"
                                    CleanupMode.LEFTOVERS -> "整理后删除不再包含视频的原文件夹（连同字幕、NFO、图片等）；还有其他视频的文件夹保留。"
                                },
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 12.dp),
                            )
                            Text("整理后可在菜单里撤销（删除的文件除外）。", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 4.dp))
                        }
                    }

                    1 -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
                        if (conflicts.isEmpty()) item { Text("没有问题需要处理", color = MaterialTheme.colorScheme.outline) }
                        for (kind in ConflictKind.entries) {
                            val list = conflicts.filter { it.kind == kind }
                            if (list.isEmpty()) continue
                            item(key = "h-$kind") {
                                Column(Modifier.padding(top = 8.dp, bottom = 4.dp)) {
                                    Text("${kind.label} · ${list.size}", fontWeight = FontWeight.Bold,
                                        color = if (kind == ConflictKind.MISSING_META) MaterialTheme.colorScheme.onSurface else Orange)
                                    Text(
                                        when (kind) {
                                            ConflictKind.EXISTS -> "影视库里已经有同名文件，可能是重复下载或更好 / 更差的版本"
                                            ConflictKind.DUPLICATE -> "和本批次另一个文件整理到同一个名字，第一个照常整理，下面这些需要处理"
                                            ConflictKind.MISSING_META -> "能整理，但名称里可能缺少年份或集数"
                                        },
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    // 整组一键设置；按钮多时换行，不挤成一条
                                    @OptIn(ExperimentalLayoutApi::class)
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                                        list.first().actions.forEach { a ->
                                            AssistChip(
                                                onClick = { list.forEach { vm.conflictActions[it.key] = a } },
                                                label = {
                                                    Text(if (a == ConflictAction.DELETE_SOURCE) "全部删除" else "全部${a.label}", fontSize = 12.sp,
                                                        color = if (a == ConflictAction.DELETE_SOURCE) Red else Color.Unspecified)
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                            items(list.take(LIST_LIMIT), key = { "c-${it.key}" }) { c -> ConflictRow(vm, c, s, cloud, onOpenRow) }
                            if (list.size > LIST_LIMIT) item {
                                Text("…还有 ${list.size - LIST_LIMIT} 个，可用上面的「全部…」统一处理",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                            }
                        }
                    }

                    else -> {
                        val tree = remember(pc) {
                            buildTree(
                                videos = (sum.moves + sum.renames + sum.unchanged).map { it.to },
                                generated = pc.scrapeFiles, newDirs = pc.result.newDirs,
                            )
                        }
                        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                            item {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                                    Text("■ 视频", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                                    Text("■ 刮削生成", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                                    Text("新 = 将新建的文件夹", fontSize = 11.sp, color = Green)
                                }
                            }
                            items(tree.size) { i ->
                                val t = tree[i]
                                Row(Modifier.padding(start = (t.depth * 14).dp, top = 1.dp, bottom = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        (if (t.isDir) "▾ " else "  ") + t.name,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 12.sp,
                                        fontWeight = if (t.isDir) FontWeight.Medium else FontWeight.Normal,
                                        color = when {
                                            t.isDir -> MaterialTheme.colorScheme.onSurface
                                            t.generated -> MaterialTheme.colorScheme.outline
                                            else -> MaterialTheme.colorScheme.primary
                                        },
                                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                                    )
                                    if (t.isNew) {
                                        Spacer(Modifier.width(6.dp))
                                        Pill("新", Green)
                                    }
                                }
                            }
                            if (tree.size >= 2000 || pc.scrapeCapped) item {
                                Text("文件较多，只显示了一部分", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.padding(top = 8.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConflictRow(vm: MainViewModel, c: Conflict, s: AppSettings, cloud: Boolean, onOpenRow: (String) -> Unit) {
    val action = vm.actionFor(c)
    val src = c.plan.item.file
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(src.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable { onOpenRow(c.key) })
        Text(
            listOf(sizeText(src.size), src.path.substringBeforeLast('/')).filter { it.isNotEmpty() }.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text("→ " + shortTarget(c.plan.targetPath, s), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            c.detail + (c.existingSize?.let { "（已有 ${sizeText(it)}，本文件 ${sizeText(src.size)}）" } ?: ""),
            style = MaterialTheme.typography.bodySmall, color = Orange,
        )
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            c.actions.forEach { a ->
                FilterChip(selected = action == a, onClick = { vm.conflictActions[c.key] = a }, label = { Text(a.label, fontSize = 12.sp) })
            }
        }
        when (action) {
            ConflictAction.OVERWRITE -> Text(
                "会先删除影视库里的旧文件" + if (cloud) "（进网盘回收站）" else "，无法撤销",
                style = MaterialTheme.typography.labelSmall, color = Red,
            )
            ConflictAction.DELETE_SOURCE -> Text(
                "会删除这个源文件" + if (cloud) "（进网盘回收站）" else "，无法撤销",
                style = MaterialTheme.typography.labelSmall, color = Red,
            )
            ConflictAction.KEEP_BOTH -> Text("新文件名后加 (2)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            else -> {}
        }
        HorizontalDivider(Modifier.padding(top = 6.dp))
    }
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

/** 手动清理前确认：列出每个要删的文件夹和里面的东西，可逐个取消勾选 */
@Composable
private fun LeftoversDialog(
    found: List<CleanupTarget>, mode: CleanupMode, scanDir: String,
    onDelete: (List<CleanupTarget>) -> Unit, onDismiss: () -> Unit,
) {
    var chosen by remember(found) { mutableStateOf(found.map { it.dir }.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (found.isEmpty()) "没有可清理的文件夹" else "清理 ${found.size} 个残留文件夹？") },
        text = {
            LazyColumn {
                item {
                    Text(
                        if (found.isEmpty() && mode == CleanupMode.EMPTY_ONLY) "扫描目录里没有空文件夹。"
                        else if (found.isEmpty()) "扫描目录里的文件夹都还有视频（或正在下载）。"
                        else if (mode == CleanupMode.EMPTY_ONLY) "这些文件夹是空的（系统缓存文件不算）。"
                        else "这些文件夹里没有视频（样片不算），里面的字幕、NFO、图片等会一起删除。删除后无法撤销。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    if (found.isNotEmpty()) Text(
                        "扫描范围：${normalizePath(scanDir)}。下列文件夹都在范围内；扫描目录本身和影视库目录不会删除。",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                items(found, key = { it.dir }) { t ->
                    Row(
                        Modifier.fillMaxWidth().clickable { chosen = if (t.dir in chosen) chosen - t.dir else chosen + t.dir }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Checkbox(checked = t.dir in chosen, onCheckedChange = null, modifier = Modifier.padding(top = 2.dp, end = 8.dp))
                        Column {
                            Text(t.dir.removePrefix(normalizePath(scanDir).trimEnd('/')).ifEmpty { t.dir },
                                style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                            Text(
                                if (t.files == 0) "空文件夹" else "${t.files} 个文件：" + t.sample.joinToString("、") +
                                    if (t.files > t.sample.size) " …" else "",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (found.isNotEmpty()) Button(
                onClick = { onDelete(found.filter { it.dir in chosen }) }, enabled = chosen.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) { Text("删除 ${chosen.size} 个") }
            else TextButton(onClick = onDismiss) { Text("好") }
        },
        dismissButton = { if (found.isNotEmpty()) TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

internal fun formatTime(ms: Long): String =
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

/** 「目标已存在」时：并排对比两个文件，列出目标位置同一部（剧集：同一集）的其他版本，直接删除或决定怎么整理 */
@Composable
private fun TargetComparePanel(vm: MainViewModel, row: MediaRow, onClose: () -> Unit) {
    var reload by remember { mutableIntStateOf(0) }
    var info by remember(row.key) { mutableStateOf<MainViewModel.TargetInfo?>(null) }
    var error by remember(row.key) { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    LaunchedEffect(row.key, reload) {
        error = null
        info = try { vm.inspectTarget(row.key) } catch (e: Exception) {
            if (e is CancellationException) throw e
            error = "读取目标文件夹失败：${e.message}"; null
        }
    }
    val cloud = vm.settings.storageType == StorageType.PAN115 || vm.settings.storageType == StorageType.ALIYUN
    val gone = if (cloud) "（进网盘回收站，可恢复）" else "（直接删除，无法恢复）"
    val src = row.match.file ?: return
    val p = row.match.parsed

    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("对比后决定保留哪个", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            CompareLine("本文件", src.name, listOfNotNull(sizeText(src.size), p.resolution, p.source, p.videoCodec).filter { it.isNotEmpty() }.joinToString(" · "))
            val i = info
            when {
                error != null -> Text(error!!, color = Red, style = MaterialTheme.typography.bodySmall)
                i == null -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                i.existing == null -> Text(
                    if (i.dirMissing) "目标文件夹已不存在，现在可以直接整理" else "目标位置已经没有同名文件了，现在可以直接整理",
                    color = Green, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp),
                )
                else -> {
                    CompareLine(
                        "已有", i.existing.name,
                        listOf(sizeText(i.existing.size), if (i.existing.modified > 0) "修改于 " + formatTime(i.existing.modified) else "")
                            .filter { it.isNotEmpty() }.joinToString(" · "),
                    )
                    if (src.size > 0 && src.size == i.existing.size) Text(
                        "两个文件大小完全相同，很可能是重复的同一个文件",
                        color = Orange, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            // 目标文件夹里同一部（剧集：同一集）的其他版本；剧集的其他集不冲突，不列出来
            val others = i?.related.orEmpty().filter { it.name != i?.existing?.name }
            if (others.isNotEmpty()) {
                Text(if (row.plan?.input?.isEpisode == true) "这一集还有其他版本：" else "这个文件夹里还有：",
                    style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                others.forEach { v ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(v.name, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(sizeText(v.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        }
                        TextButton(onClick = { confirm = "删除 ${v.name}？$gone" to { vm.deleteRemote(v) { reload++ } } }, enabled = !vm.busy) {
                            Text("删除", color = Red)
                        }
                    }
                }
            }

            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                OutlinedButton(onClick = { confirm = "删除本文件 ${src.name}？$gone" to { vm.deleteSource(row.key); onClose() } }, enabled = !vm.busy) {
                    Text("删除本文件", color = Red)
                }
                if (i?.existing != null) {
                    OutlinedButton(onClick = {
                        confirm = "删除已有的 ${i.existing.name}，再把本文件整理过去？$gone" to { vm.overwriteAndOrganize(row.key); onClose() }
                    }, enabled = !vm.busy) { Text("覆盖已有文件") }
                    OutlinedButton(onClick = {
                        vm.keepBothAndOrganize(row.key, i.videos.map { it.name }.toSet()); onClose()
                    }, enabled = !vm.busy) { Text("保留两者") }
                    if (i.existing.size > 0) TextButton(onClick = {
                        confirm = "删除已有的 ${i.existing.name}？$gone" to { vm.deleteRemote(i.existing) { reload++ } }
                    }, enabled = !vm.busy) { Text("只删除已有文件", color = Red) }
                }
            }
        }
    }

    // 同剧其他集的重复版本：和本文件无关，单独一块，避免和上面的对比混在一起
    val dups = info?.dupEpisodes.orEmpty()
    if (dups.isNotEmpty()) DuplicateEpisodesPanel(dups, enabled = !vm.busy) { picked ->
        confirm = "删除选中的 ${picked.size} 个文件？$gone" to { vm.deleteRemoteAll(picked) { reload++ } }
    }

    confirm?.let { (msg, action) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("确认删除") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { confirm = null; action() }) { Text("删除", color = Red) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } },
        )
    }
}

/** 同剧其他集里有多个版本的，按集分组，勾选后批量删除 */
@Composable
private fun DuplicateEpisodesPanel(groups: List<List<RemoteFile>>, enabled: Boolean, onDelete: (List<RemoteFile>) -> Unit) {
    var picked by remember(groups) { mutableStateOf(emptySet<String>()) }
    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("同剧其他集的重复版本", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
            Text("和本文件无关，只是顺便发现的：${groups.size} 集各有多个文件", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            groups.forEach { files ->
                val p = MediaNameParser.parse(files.first().name)
                Text("S%02dE%02d · %d 个版本".format(p.season ?: 1, p.episode ?: 0, files.size),
                    style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                files.forEach { f ->
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = enabled) { picked = if (f.path in picked) picked - f.path else picked + f.path },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = f.path in picked, onCheckedChange = null, enabled = enabled, modifier = Modifier.padding(end = 8.dp))
                        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                            Text(f.name, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOf(sizeText(f.size), if (f.modified > 0) "修改于 " + formatTime(f.modified) else "")
                                    .filter { it.isNotEmpty() }.joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                // 快捷勾选：每集保留最大的那个，其余勾上
                TextButton(onClick = {
                    picked = groups.flatMap { files -> files.sortedByDescending { it.size }.drop(1) }.map { it.path }.toSet()
                }, enabled = enabled) { Text("每集只留最大的") }
                val chosen = groups.flatten().filter { it.path in picked }
                Button(
                    onClick = { onDelete(chosen) }, enabled = enabled && chosen.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text("删除选中 ${chosen.size} 个") }
            }
        }
    }
}

@Composable
private fun CompareLine(label: String, name: String, meta: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline, modifier = Modifier.width(48.dp))
        Column {
            Text(name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun sameMatch(a: TmdbSearchResult?, b: TmdbSearchResult?) =
    a != null && b != null && a.id == b.id && a.type == b.type && a.source == b.source

private fun TmdbSearchResult.idLabel() = "${source.label} $id"

/** 条目在来源网站上的页面，用来对照海报、简介确认是不是这部 */
private fun TmdbSearchResult.webUrl() = when (source) {
    MetaSource.TMDB -> "https://www.themoviedb.org/${if (type == MediaType.TV) "tv" else "movie"}/$id"
    MetaSource.DOUBAN -> "https://movie.douban.com/subject/$id/"
    MetaSource.TVDB -> "https://thetvdb.com/dereferrer/${if (type == MediaType.TV) "series" else "movie"}/$id"
    MetaSource.BANGUMI -> "https://bgm.tv/subject/$id"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RowDetailDialog(vm: MainViewModel, row: MediaRow, onDismiss: () -> Unit) {
    val p = row.match.parsed
    val scope = rememberCoroutineScope()
    var query by remember(row.key) { mutableStateOf(p.title) }
    var tv by remember(row.key) { mutableStateOf(row.isTv) }
    var searchSource by remember(row.key) { mutableStateOf(row.chosen?.source ?: vm.settings.scrape.extra.primary) }
    var results by remember(row.key) { mutableStateOf<List<TmdbSearchResult>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    val sameCount = remember(row.key) { vm.sameGroupPendingCount(row.key) }
    var applySame by remember(row.key) { mutableStateOf(true) }
    // 点候选只是选中，真正生效要点底部按钮；null = 沿用当前匹配
    var picked by remember(row.key) { mutableStateOf<TmdbSearchResult?>(null) }
    val pickChanged = picked != null && !sameMatch(picked, row.chosen)
    val effective = if (pickChanged) picked else row.chosen
    val scrapeSuffix = if (vm.settings.autoScrape) "+刮削" else ""
    // 已完成的也能改匹配（选错了可以纠正），改完回到「可整理」再整理一次
    val canChange = !vm.busy && row.category != RowCategory.DELETED
    val canExclude = (row.outcome == null || row.category == RowCategory.FAILED) && !vm.busy

    // 手动季集：只在按剧集匹配时显示；和识别结果不同才算改了
    var seasonText by remember(row.key) { mutableStateOf((p.season ?: 1).toString()) }
    var episodeText by remember(row.key) { mutableStateOf(p.episode?.toString().orEmpty()) }
    val effectiveTv = effective?.let { it.type == MediaType.TV } ?: tv
    val seInput = seasonText.toIntOrNull()?.let { s -> episodeText.toIntOrNull()?.takeIf { it > 0 }?.let { s to it } }
    val seChanged = effectiveTv && seInput != null &&
        (p.episode == null || seInput.first != (p.season ?: 1) || seInput.second != p.episode)
    val episodeArg = if (seChanged) seInput else null

    // 同文件夹批量：开了之后底部按钮作用于整个文件夹
    val folderList = remember(row.key) { vm.folderRows(row.key) }
    val folderCount = folderList.size
    val folderAllRecognized = folderList.all { it.match.parsed.episode != null }
    var folderMode by remember(row.key) { mutableStateOf(false) }
    val folderOn = folderMode && effectiveTv && folderCount >= 2
    val folderPreview = remember(folderOn, seInput) {
        if (folderOn && seInput != null) vm.folderPlan(row.key, seInput.first, seInput.second) else emptyList()
    }
    val folderReady = folderOn && effective != null && folderPreview.isNotEmpty()
    // 「同时用于其他同名待确认文件」勾选且有这样的文件时，确认 / 整理按钮一起处理它们
    val applyOthers = applySame && sameCount > 0 && !folderOn
    val withOthers = sameCount + 1

    fun doSearch() {
        if (query.isBlank()) return
        searching = true; searchError = null
        scope.launch {
            try {
                results = vm.search(query.trim(), tv, searchSource)
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
                        InfoLine("当前匹配", "${it.title} (${it.year ?: "?"}) · ${it.idLabel()}" +
                            if (it == row.match.best) " · 置信度 %.0f%%".format(row.match.score * 100) else "")
                    }
                    if (pickChanged) InfoLine("改为", "${picked!!.title} (${picked!!.year ?: "?"}) · ${picked!!.idLabel()}",
                        MaterialTheme.colorScheme.primary)
                    else row.plan?.let { InfoLine("整理到", it.targetPath) }
                    if (row.category == RowCategory.FAILED) InfoLine("错误", row.outcome.orEmpty(), Red)
                    // 打开文件所在的文件夹：删除、改名、移动等
                    val here = row.currentPath
                    if (here != null && row.category != RowCategory.DELETED) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onDismiss(); vm.openBrowser(parentPath(here), here.substringAfterLast('/')) }) {
                            Text("打开所在文件夹")
                        }
                        val target = row.plan?.targetDir?.let(::normalizePath)
                        if (row.category != RowCategory.DONE && target != null && target != parentPath(here))
                            TextButton(onClick = { onDismiss(); vm.openBrowser(target, row.plan!!.targetName) }) { Text("目标文件夹") }
                    }
                    // 目标已存在 / 本批次重名：直接在这里对比、删除或决定怎么整理
                    if (row.category == RowCategory.FAILED && !pickChanged &&
                        (row.outcome.orEmpty().contains("已存在") || row.outcome.orEmpty().contains("重复"))
                    ) TargetComparePanel(vm, row, onClose = onDismiss)
                    Text(
                        "单项整理会并入「撤销上次整理」的记录；单项刮削一律全部重新生成 NFO 和图片",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                }
                if (canChange) item {
                    if (row.category == RowCategory.DONE) Text(
                        "这个文件已整理过。改选匹配或季集后会回到「可整理」，再点整理会从当前位置移到新目标；" +
                            "原文件夹里已生成的 NFO 和图片不会自动删除。",
                        style = MaterialTheme.typography.bodySmall, color = Orange,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    // 剧集：文件名没认出季集或认错时手动填（只改这一个文件）
                    if (effectiveTv) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = seasonText, onValueChange = { seasonText = it.filter(Char::isDigit).take(2) },
                                label = { Text("季") }, singleLine = true, isError = seasonText.toIntOrNull() == null,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = episodeText, onValueChange = { episodeText = it.filter(Char::isDigit).take(4) },
                                label = { Text("集") }, singleLine = true, isError = episodeText.toIntOrNull()?.takeIf { it > 0 } == null,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Text(
                            if (p.episode == null) "没识别出集数，请手动填写" else "识别不对时可手动改季集，只改这一个文件",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (p.episode == null) Orange else MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(top = 2.dp, bottom = if (folderCount >= 2) 0.dp else 12.dp),
                        )
                        // 同文件夹的其他视频按文件名排序，用同样的匹配和季、递推的集数一起处理
                        if (folderCount >= 2) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = folderMode, onCheckedChange = { folderMode = it })
                                Text("同文件夹 $folderCount 个视频一起处理", style = MaterialTheme.typography.bodySmall)
                            }
                            if (folderMode) {
                                Text(
                                    when {
                                        seInput == null -> "先填好季和集"
                                        seInput.second == p.episode && folderAllRecognized -> "季统一改为 ${seInput.first}，集数用各自文件名里认出的："
                                        else -> "按文件名排序，从这一集往前后递推："
                                    },
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
                                )
                                folderPreview.take(30).forEach { fe ->
                                    Text(
                                        "S%02dE%02d  ${fe.name}".format(fe.season, fe.episode),
                                        style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        fontWeight = if (fe.key == row.key) FontWeight.Bold else FontWeight.Normal,
                                    )
                                }
                                if (folderPreview.size > 30) Text("…还有 ${folderPreview.size - 30} 个", style = MaterialTheme.typography.labelSmall)
                                if (seInput != null && folderPreview.size < folderCount) Text(
                                    "排在前面、推算不到第 1 集的 ${folderCount - folderPreview.size} 个不处理",
                                    style = MaterialTheme.typography.labelSmall, color = Orange,
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                        }
                    }
                    // 数据来源：选了豆瓣 / TheTVDB / Bangumi 的条目，整理和刮削都用那边的数据
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetaSource.entries.forEach { s ->
                            FilterChip(
                                selected = searchSource == s,
                                onClick = { if (searchSource != s) { searchSource = s; if (results != null) doSearch() } },
                                label = { Text(s.label) },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        label = { Text("在 ${searchSource.label} 搜索") },
                        supportingText = {
                            Text(when (searchSource) {
                                MetaSource.TMDB -> "别名会再查豆瓣 / TheTVDB；也可直接输 tmdb:ID"
                                MetaSource.DOUBAN -> "也可直接输 douban:ID；豆瓣没有分集标题和背景图，每季是单独的条目"
                                MetaSource.TVDB -> "要在刮削设置里填 TheTVDB API key；也可直接输 tvdb:ID"
                                MetaSource.BANGUMI -> "动画最全；也可直接输 bgm:ID，每季是单独的条目"
                            } + if (searchSource != MetaSource.TMDB) "。不加 TMDB 标签、没有 clearlogo" else "")
                        },
                        trailingIcon = { IconButton(onClick = ::doSearch) { Icon(Icons.Default.Search, contentDescription = "搜索") } },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { doSearch() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !tv, onClick = { tv = false; if (results != null) doSearch() }, label = { Text("电影") })
                        FilterChip(selected = tv, onClick = { tv = true; if (results != null) doSearch() }, label = { Text("剧集") })
                    }
                    if (sameCount > 0 && !folderOn) Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = applySame, onCheckedChange = { applySame = it })
                        Text("同时用于其他 $sameCount 个同名待确认文件（一起确认、整理）", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        (if (results == null) "候选结果" else "搜索结果") + "（点选后用底部按钮整理或选用）",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                    )
                    when {
                        searching -> LinearProgressIndicator(Modifier.fillMaxWidth())
                        searchError != null -> Text(searchError!!, color = Red, style = MaterialTheme.typography.bodySmall)
                        options.isEmpty() -> Text("没有结果，换个名字试试（中文名、英文 / 日文原名都可以），或直接输 ${if (searchSource == MetaSource.BANGUMI) "bgm" else searchSource.key}:ID", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (canChange && !searching) items(options, key = { "${it.source}-${it.type}-${it.id}" }) { opt ->
                    val context = androidx.compose.ui.platform.LocalContext.current
                    val isEffective = sameMatch(opt, effective)
                    Column(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isEffective) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                            .clickable { picked = if (sameMatch(opt, row.chosen)) null else opt }
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                Text("${opt.title} (${opt.year ?: "?"})", fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f, fill = false))
                                if (isEffective) {
                                    Spacer(Modifier.width(6.dp))
                                    Pill(if (pickChanged) "已选" else "当前", if (pickChanged) MaterialTheme.colorScheme.primary else Gray)
                                }
                            }
                            // 跳到来源网站的条目页对照，不影响选中
                            TextButton(
                                onClick = {
                                    runCatching {
                                        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(opt.webUrl())))
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp),
                                modifier = Modifier.height(32.dp),
                            ) { Text("查看 ↗", fontSize = 12.sp) }
                        }
                        Text(
                            listOfNotNull(if (opt.type == MediaType.TV) "剧集" else "电影", opt.originalTitle, opt.idLabel())
                                .joinToString(" · "),
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.outline,
                        )
                        opt.via?.let {
                            Text("经$it 找到", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        }
                        opt.overview?.takeIf { it.isNotBlank() }?.let {
                            Text(it, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        // 主按钮：选了别的候选 → 用它整理；否则按当前状态整理 / 刮削
        confirmButton = {
            val cat = row.category
            val hasFile = row.match.file != null
            when {
                folderReady -> Button(onClick = {
                    vm.applyFolder(row.key, effective!!, seInput!!.first, seInput.second, organize = true); onDismiss()
                }, enabled = canChange) { Text("整理这 ${folderPreview.size} 个$scrapeSuffix") }
                pickChanged && hasFile -> Button(onClick = {
                    vm.chooseAndOrganize(row.key, picked!!, applyOthers, episodeArg); onDismiss()
                }, enabled = canChange) { Text(if (applyOthers) "用此匹配整理 $withOthers 个" else "用此匹配整理$scrapeSuffix") }
                // 勾了「同时用于其他同名文件」：当前匹配也套用到它们，一起预检、整理
                applyOthers && row.chosen != null && hasFile && cat != RowCategory.DONE -> Button(onClick = {
                    vm.chooseAndOrganize(row.key, row.chosen!!, true, episodeArg); onDismiss()
                }, enabled = canChange) { Text("确认并整理 $withOthers 个") }
                seChanged && row.chosen != null && hasFile -> Button(onClick = { vm.organizeOne(row.key, episodeArg); onDismiss() }, enabled = canChange) {
                    Text("按 S%02dE%02d 整理".format(episodeArg!!.first, episodeArg.second) + scrapeSuffix)
                }
                cat == RowCategory.DONE -> Button(onClick = { vm.scrapeOne(row.key); onDismiss() }, enabled = canChange) {
                    Text(if (vm.settings.autoScrape) "重新刮削" else "刮削此项")
                }
                row.chosen != null && hasFile -> Button(onClick = { vm.organizeOne(row.key); onDismiss() }, enabled = canChange) {
                    Text(when (cat) {
                        RowCategory.PENDING -> "确认并整理"
                        RowCategory.FAILED -> "重试整理"
                        else -> "整理此项"
                    } + scrapeSuffix)
                }
                else -> TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
        dismissButton = {
            Row {
                if (canExclude && !pickChanged && !seChanged && !folderOn) TextButton(onClick = { vm.setExcluded(setOf(row.key), !row.excluded); onDismiss() }) {
                    Text(if (row.excluded) "取消排除" else "排除")
                }
                if (folderReady) TextButton(onClick = {
                    vm.applyFolder(row.key, effective!!, seInput!!.first, seInput.second, organize = false); onDismiss()
                }, enabled = canChange) { Text("仅确认") }
                else if (pickChanged) TextButton(onClick = { vm.choose(row.key, picked!!, applyOthers, episodeArg); onDismiss() }, enabled = canChange) {
                    Text(if (applyOthers) "仅选用 $withOthers 个" else "仅选用")
                } else if (applyOthers && row.chosen != null && row.category != RowCategory.DONE) TextButton(onClick = {
                    vm.choose(row.key, row.chosen!!, true, episodeArg); onDismiss()
                }, enabled = canChange) {
                    Text("仅确认 $withOthers 个")
                } else if (seChanged) TextButton(onClick = { vm.setEpisode(row.key, episodeArg!!); onDismiss() }, enabled = canChange) {
                    Text("仅改季集")
                } else if (row.category == RowCategory.PENDING) TextButton(onClick = { vm.confirm(setOf(row.key)); onDismiss() }, enabled = canChange) {
                    Text("仅确认")
                }
                if (row.chosen != null || pickChanged) TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
    )
}
