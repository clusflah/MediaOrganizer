package com.example.mediaorganizer.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.ui.draw.clip
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mediaorganizer.MainViewModel
import com.example.mediaorganizer.MusicRow
import com.example.mediaorganizer.RowCategory
import com.example.mediaorganizer.Task
import com.example.mediaorganizer.music.MusicConfig
import com.example.mediaorganizer.music.ReleaseCandidate
import com.example.mediaorganizer.storage.StorageFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 音乐整理：扫描音乐目录 → 按专辑匹配 MusicBrainz（iTunes 兜底）→
 * 整理成 音乐库/艺人/专辑 (年份)/01 - 歌名 → 写封面、album.nfo、artist.nfo、LRCLIB 歌词
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun MusicScreen(vm: MainViewModel) {
    val s = vm.settings
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf<RowCategory?>(null) }
    var detailKey by remember { mutableStateOf<String?>(null) }
    val counts by remember { derivedStateOf { vm.musicRows.groupingBy { it.category }.eachCount() } }
    val visible by remember {
        derivedStateOf {
            vm.musicRows.filter { filter == null || it.category == filter }
                .let { if (filter == null && vm.task != Task.SCAN) it.sortedBy { r -> r.category.ordinal } else it }
        }
    }

    // 标题栏和「影视 / 音乐」标签在首页，两页共用
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { MusicBottomBar(vm, counts) },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(top = 6.dp, bottom = 16.dp)) {
            item(key = "dirs") { MusicDirCard(vm) }
            item(key = "settings") {
                MusicSettingsCard(vm, expanded = showSettings, onToggle = { showSettings = !showSettings })
            }
            if (vm.musicStatus.isNotEmpty()) item(key = "status") {
                Text(
                    vm.musicStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (vm.musicRows.isEmpty()) {
                if (vm.task != Task.SCAN) item(key = "empty") {
                    Text(
                        "点下方「扫描」，识别「${s.musicScanDir}」里的歌曲。\n" +
                            "已有标签（ID3 / FLAC / M4A）的按标签识别，没有的按「艺人 - 专辑 (年份)/01 - 歌名」这类文件夹和文件名识别。",
                        color = MaterialTheme.colorScheme.outline, textAlign = TextAlign.Center, fontSize = 14.sp,
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                    )
                }
                return@LazyColumn
            }
            stickyHeader(key = "filters") {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("全部 ${vm.musicRows.size}") })
                        for (c in listOf(RowCategory.PENDING, RowCategory.READY, RowCategory.UNMATCHED, RowCategory.FAILED, RowCategory.DONE, RowCategory.EXCLUDED)) {
                            val n = counts[c] ?: 0
                            if (n == 0 && filter != c) continue
                            FilterChip(selected = filter == c, onClick = { filter = c }, label = {
                                Row {
                                    Text(c.label)
                                    Text(" $n", color = categoryColor(c), fontWeight = FontWeight.SemiBold)
                                }
                            })
                        }
                    }
                }
            }
            items(visible, key = { it.key }) { row ->
                MusicRowItem(row, enabled = !vm.busy, onClick = { detailKey = row.key }, onConfirm = { vm.musicConfirm(setOf(row.key)) })
                HorizontalDivider(Modifier.padding(start = 16.dp))
            }
        }
    }

    detailKey?.let { k -> vm.musicRows.firstOrNull { it.key == k } }?.let { row ->
        MusicDetailDialog(vm, row, onDismiss = { detailKey = null })
    }
}

@Composable
private fun categoryColor(c: RowCategory): Color {
    val st = LocalStatusColors.current
    return when (c) {
        RowCategory.PENDING -> st.warn
        RowCategory.READY, RowCategory.DONE -> st.ok
        RowCategory.UNMATCHED, RowCategory.FAILED -> st.bad
        else -> st.muted
    }
}

// ============================ 设置 ============================

/** 音乐目录：和影视页一样，扫描来源和整理目标分开写；点一行在媒体库里选文件夹 */
@Composable
private fun MusicDirCard(vm: MainViewModel) {
    val s = vm.settings
    val canBrowse = StorageFactory.missing(s) == null && !vm.busy
    SectionCard {
        SectionTitle("音乐目录", "整理成「音乐库/艺人/专辑 (年份)/01 - 歌名」，Plex、Jellyfin、Emby、Kodi 都能直接识别")
        Text("扫描来源", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
        DirRow(MainViewModel.DirSetting.MUSIC_SCAN, s.musicScanDir, AppIcons.FolderOpen, canBrowse, vm::openDirPicker) { vm.updateSettings(s.copy(musicScanDir = it)) }
        Text("整理目标", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = 10.dp))
        DirRow(MainViewModel.DirSetting.MUSIC, s.musicRoot, AppIcons.MusicNote, canBrowse, vm::openDirPicker) { vm.updateSettings(s.copy(musicRoot = it)) }
    }
}

/** 刮削选项：开关比较多，默认收起，只显示一行摘要 */
@Composable
private fun MusicSettingsCard(vm: MainViewModel, expanded: Boolean, onToggle: () -> Unit) {
    val s = vm.settings
    val m = s.music
    fun up(c: MusicConfig) = vm.updateSettings(s.copy(music = c))
    SectionCard {
        Row(
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(onClick = onToggle).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(AppIcons.Tune, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Text("刮削选项", style = MaterialTheme.typography.bodyLarge)
                Text(
                    listOfNotNull(
                        "封面".takeIf { m.cover }, "NFO".takeIf { m.albumNfo || m.artistNfo }, "歌词".takeIf { m.lyrics },
                        "iTunes 补充".takeIf { m.itunes },
                    ).joinToString(" · ").ifEmpty { "都没开" },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, tint = MaterialTheme.colorScheme.outline)
        }
        if (!expanded) return@SectionCard
        Spacer(Modifier.height(8.dp))
        SwitchRow("读取歌曲标签", m.readTags, "WebDAV、SMB、本机文件夹只读文件开头一小段；网盘按文件名识别") { up(m.copy(readTags = it)) }
        SwitchRow("iTunes 补充", m.itunes, "MusicBrainz 搜不到的专辑、缺少的封面从 iTunes 找") { up(m.copy(itunes = it)) }
        if (m.itunes) OutlinedTextField(
            value = m.itunesCountry, onValueChange = { v -> up(m.copy(itunesCountry = v.uppercase().filter { it.isLetter() }.take(2))) },
            label = { Text("iTunes 商店地区（CN / US / JP …）") }, singleLine = true,
            shape = MaterialTheme.shapes.small, colors = appFieldColors(),
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        )
        SwitchRow("专辑封面", m.cover, "cover.jpg，来自 Cover Art Archive") { up(m.copy(cover = it)) }
        if (m.cover) SwitchRow("同时写 folder.jpg", m.folderJpg, "Windows 资源管理器、Infuse 用的文件夹封面") { up(m.copy(folderJpg = it)) }
        SwitchRow("album.nfo", m.albumNfo, "专辑名、厂牌、流派、曲目表和 MusicBrainz ID") { up(m.copy(albumNfo = it)) }
        SwitchRow("artist.nfo", m.artistNfo, "艺人类型、地区、成立 / 出生年份") { up(m.copy(artistNfo = it)) }
        SwitchRow("歌词", m.lyrics, "LRCLIB 逐行同步歌词，保存为与歌曲同名的 .lrc；已有歌词的不覆盖") { up(m.copy(lyrics = it)) }
        if (m.lyrics) SwitchRow("没有同步歌词时写普通歌词", m.plainLyrics, "保存为 .txt") { up(m.copy(plainLyrics = it)) }
        SwitchRow("多碟专辑分文件夹", m.discFolders, if (m.discFolders) "Disc 1/01 - 歌名" else "1-01 - 歌名") { up(m.copy(discFolders = it)) }
        Hint("「整理后自动刮削」「已有文件的处理方式」「整理后清理原文件夹」和影视共用刮削设置。" +
            "封面、歌词版权归原作者，公开分发前请核对各数据源的使用条款。")
    }
}


// ============================ 列表 ============================

@Composable
private fun MusicRowItem(row: MusicRow, enabled: Boolean, onClick: () -> Unit, onConfirm: () -> Unit) {
    val m = row.match
    val r = m.release
    val matched = m.tracks.count { it.track != null }
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                r?.title ?: m.group.label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    r?.artist ?: m.group.artist,
                    (r?.year ?: m.group.year)?.toString(),
                    if (m.group.single) "单曲" else "${m.group.files.size} 首",
                    r?.let { "对上 $matched/${m.tracks.size}" },
                    r?.source?.label,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                when (row.category) {
                    RowCategory.FAILED -> row.outcome ?: ""
                    RowCategory.DONE -> row.plan?.albumDir ?: "已整理"
                    else -> m.group.dir
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (row.category == RowCategory.FAILED) LocalStatusColors.current.bad else MaterialTheme.colorScheme.outline,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                if (r != null && row.category != RowCategory.DONE) "${(m.score * 100).toInt()}%" else row.category.label,
                color = categoryColor(row.category), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            )
            if (row.category == RowCategory.PENDING) TextButton(onClick = onConfirm, enabled = enabled) { Text("确认") }
        }
    }
}

@Composable
private fun MusicBottomBar(vm: MainViewModel, counts: Map<RowCategory, Int>) {
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            when (vm.task) {
                null -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val ready = counts[RowCategory.READY] ?: 0
                    val done = (counts[RowCategory.DONE] ?: 0) + (counts[RowCategory.FAILED] ?: 0)
                    OutlinedButton(onClick = vm::musicScan, enabled = StorageFactory.missing(vm.settings) == null) {
                        Text(if (vm.musicRows.isEmpty()) "扫描" else "重新扫描")
                    }
                    if (vm.musicRows.isNotEmpty()) OutlinedButton(onClick = { vm.musicScrape() }, enabled = ready + done > 0) {
                        Text("刮削")
                    }
                    Spacer(Modifier.weight(1f))
                    if (vm.musicRows.isNotEmpty()) Button(onClick = { vm.musicOrganize() }, enabled = ready > 0) { Text("整理 $ready") }
                }
                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(vm.musicStatus, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(6.dp))
                            val p = vm.progress
                            if (p != null && p.second > 0) LinearProgressIndicator(progress = { p.first.toFloat() / p.second }, modifier = Modifier.fillMaxWidth())
                            else LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        if (vm.task == Task.SCAN) {
                            Spacer(Modifier.width(12.dp))
                            OutlinedButton(onClick = vm::stopScan) { Text("停止") }
                        }
                    }
                }
            }
            if (StorageFactory.missing(vm.settings) != null && vm.task == null)
                Hint("先在主页面填好媒体库连接")
        }
    }
}

// ============================ 详情 ============================

@Composable
private fun MusicDetailDialog(vm: MainViewModel, row: MusicRow, onDismiss: () -> Unit) {
    val m = row.match
    val r = m.release
    val g = m.group
    val scope = rememberCoroutineScope()
    var album by remember(row.key) { mutableStateOf(g.album ?: g.files.first().guess.title) }
    var artist by remember(row.key) { mutableStateOf(g.artist ?: "") }
    var results by remember(row.key) { mutableStateOf<List<ReleaseCandidate>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val open = row.outcome == null
    val plan = remember(row) { vm.musicPreview(row) }

    fun search() {
        if (album.isBlank()) return
        searching = true; error = null
        scope.launch {
            try {
                results = vm.musicSearch(album, artist)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            } finally {
                searching = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(r?.title ?: g.label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            LazyColumn(Modifier.heightIn(max = 520.dp)) {
                item {
                    if (r != null) Text(
                        listOfNotNull(r.artist, r.date ?: r.year?.toString(), r.label, r.country, r.type, r.source.label).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                    ) else Text("没有找到匹配的专辑，可以在下面手动搜索", color = LocalStatusColors.current.bad, fontSize = 14.sp)
                    Text("来源：${g.dir}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    plan?.takeIf { row.outcome == null }?.let { Text("整理到：${it.albumDir}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) }
                    Spacer(Modifier.height(8.dp))
                    Text("曲目", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                // 有整理计划时显示新文件名（下面一行是原文件名），否则只列文件
                if (plan != null) items(plan.tracks) { t ->
                    Column(Modifier.padding(vertical = 3.dp)) {
                        Text(t.move.targetName, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (t.track == null) LocalStatusColors.current.warn else Color.Unspecified)
                        Text(t.song.file.name + if (t.track == null) "（专辑里没对上）" else "",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (plan == null) items(g.files) { f ->
                    Text(f.file.name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 2.dp))
                }
                if (open) {
                    item {
                        Spacer(Modifier.height(12.dp))
                        Text("改选专辑", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        OutlinedTextField(
                            value = album, onValueChange = { album = it }, label = { Text("专辑名（也可粘贴 MusicBrainz 链接）") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { search() }),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = artist, onValueChange = { artist = it }, label = { Text("艺人（可空）") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { search() }),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { search() }, enabled = !searching && !vm.busy) { Text(if (searching) "搜索中…" else "搜索") }
                            error?.let { Text(it, color = LocalStatusColors.current.bad, fontSize = 12.sp) }
                        }
                    }
                    val list = results ?: m.alternatives
                    if (results != null && list.isEmpty()) item { Hint("没有搜到") }
                    items(list, key = null) { c ->
                        CandidateLine(c, current = r?.id == c.id, enabled = !vm.busy) { vm.musicChoose(row.key, c) }
                    }
                }
            }
        },
        confirmButton = {
            if (row.category == RowCategory.PENDING) TextButton(onClick = { vm.musicConfirm(setOf(row.key)); onDismiss() }) { Text("确认") }
            else TextButton(onClick = onDismiss) { Text("关闭") }
        },
        dismissButton = {
            if (open) TextButton(onClick = { vm.musicSetExcluded(setOf(row.key), !row.excluded); onDismiss() }, enabled = !vm.busy) {
                Text(if (row.excluded) "恢复" else "排除")
            }
        },
    )
}

@Composable
private fun CandidateLine(c: ReleaseCandidate, current: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(enabled = enabled && !current, onClick = onClick).padding(vertical = 6.dp)) {
        Text(
            c.title + if (current) "（当前）" else "", fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
        )
        Text(
            listOfNotNull(c.artist, c.year?.toString(), "${c.trackCount} 首", c.format, c.country, c.label, c.source.label).joinToString(" · "),
            fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}
