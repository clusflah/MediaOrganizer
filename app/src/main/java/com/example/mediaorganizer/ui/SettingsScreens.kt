package com.example.mediaorganizer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mediaorganizer.ConnCheck
import com.example.mediaorganizer.naming.*
import com.example.mediaorganizer.scraper.ImageQuality
import com.example.mediaorganizer.scraper.ScrapeConfig
import com.example.mediaorganizer.scraper.ScrapeStrategy
import com.example.mediaorganizer.scraper.ScrapeTemplate
import com.example.mediaorganizer.settings.AppSettings
import com.example.mediaorganizer.storage.CleanupMode

private val Accent = Color(0xFFFF7A00)
private val Recommend = Color(0xFF1FA463)
private val Warn = Color(0xFFEF6C00)
private val Bad = Color(0xFFC62828)

// =====================================================================
//  命名规则设置
// =====================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NamingSettingsScreen(
    settings: AppSettings,
    saved: Boolean,
    onChange: (AppSettings) -> Unit,
    onBack: () -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }          // 0 剧集  1 电影
    var showPlayerPicker by remember { mutableStateOf(false) }
    var askReset by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    val isTv = tab == 0
    val config = if (isTv) settings.tvNaming else settings.movieNaming
    fun update(c: NamingConfig) = onChange(if (isTv) settings.copy(tvNaming = c) else settings.copy(movieNaming = c))

    val preset = PlayerPresets.all[settings.player] ?: NamingConfig()
    val modified = settings.tvNaming != preset || settings.movieNaming != preset
    val tagOn = config.tmdbTagMode != TmdbTagMode.OFF

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("命名规则") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            )
        },
        bottomBar = { SettingsBottomBar(saved, onReset = { askReset = true }, onDone = onBack) },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("适配播放器", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                    Text(settings.player + if (modified) "（已自定义）" else "", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(onClick = { showPlayerPicker = true }) { Text("切换播放器") }
            }

            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("剧集命名") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("电影命名") })
            }

            LivePreview(settings, isTv, showFiles = false)

            // ---------------- 常用 ----------------

            Section("标题语言") {
                OptionGrid(
                    options = TitleLanguage.entries,
                    selected = config.titleLanguage,
                    label = { if (it == TitleLanguage.CHINESE_FIRST) "中文优先" else "英文优先" },
                    recommended = TitleLanguage.CHINESE_FIRST,
                ) { update(config.copy(titleLanguage = it)) }
                CheckOption("中英文都显示", config.multiLanguage, desc = "例如「老友记 Friends」，按上面的顺序排列") {
                    update(config.copy(multiLanguage = it))
                }
            }

            Section("年份", trailing = {
                Switch(config.yearStyle != YearStyle.NONE, { update(config.copy(yearStyle = if (it) YearStyle.PAREN else YearStyle.NONE)) })
            }) {
                if (config.yearStyle == YearStyle.NONE) Off("关闭：名称里不带年份")
                else {
                    OptionGrid(
                        options = listOf(YearStyle.PAREN, YearStyle.PLAIN),
                        selected = config.yearStyle,
                        label = { if (it == YearStyle.PLAIN) "1994" else "(1994)" },
                        recommended = YearStyle.PAREN,
                    ) { update(config.copy(yearStyle = it)) }
                    if (isTv) {
                        SubTitle("剧集用哪一年")
                        OptionGrid(
                            options = EpisodeYearSource.entries,
                            selected = config.episodeYearSource,
                            label = { if (it == EpisodeYearSource.FIRST_SEASON) "首播年份" else "当季年份" },
                            recommended = EpisodeYearSource.FIRST_SEASON,
                        ) { update(config.copy(episodeYearSource = it)) }
                    }
                }
            }

            if (isTv) {
                Section("季集格式") {
                    OptionGrid(
                        options = SeasonEpisodeStyle.entries,
                        selected = config.seasonEpisodeStyle,
                        label = { it.sample },
                        recommended = SeasonEpisodeStyle.UPPER,
                    ) { update(config.copy(seasonEpisodeStyle = it)) }
                    SwitchRow("带集标题", config.includeEpisodeTitle, desc = "例如「S02E03 罗斯的新女友」") {
                        update(config.copy(includeEpisodeTitle = it))
                    }
                }
            }

            Section("文件夹") {
                if (isTv) {
                    SubTitle("季文件夹")
                    OptionGrid(
                        options = listOf("Season %02d", "S%02d", "第%d季", ""),
                        selected = config.seasonFolderFormat,
                        label = { if (it.isEmpty()) "不建季文件夹" else it.format(2) },
                        recommended = "Season %02d",
                    ) { update(config.copy(seasonFolderFormat = it)) }
                } else {
                    SwitchRow("每部电影一个文件夹", config.movieFolder,
                        desc = if (config.movieFolder) null else "关闭：所有电影直接放在电影库根目录") {
                        update(config.copy(movieFolder = it))
                    }
                    if (config.movieFolder) SwitchRow("文件夹名与文件名一致", config.folderSameAsFile) {
                        update(config.copy(folderSameAsFile = it))
                    }
                }
                SubTitle("整理后的原文件夹（电影和剧集共用）")
                RadioOption("删除，连同残留的 NFO、图片等", settings.cleanupMode == CleanupMode.LEFTOVERS, recommended = true,
                    desc = "还有其他视频、字幕或认不出的文件时整个保留；扫描目录和影视库目录本身不会删") {
                    onChange(settings.copy(cleanupMode = CleanupMode.LEFTOVERS))
                }
                RadioOption("只删除变空的文件夹", settings.cleanupMode == CleanupMode.EMPTY_ONLY) {
                    onChange(settings.copy(cleanupMode = CleanupMode.EMPTY_ONLY))
                }
                RadioOption("保留不动", settings.cleanupMode == CleanupMode.OFF) {
                    onChange(settings.copy(cleanupMode = CleanupMode.OFF))
                }
            }

            Section("TMDB ID 标签", trailing = {
                Switch(tagOn, { update(config.copy(tmdbTagMode = if (it) TmdbTagMode.RENAME_AND_TAG else TmdbTagMode.OFF)) })
            }) {
                if (!tagOn) Off("关闭：不加标签。加上后 Plex / Emby / Jellyfin 能按 ID 精确识别，不会认错同名作品")
                else {
                    RadioOption("改名并加标签", config.tmdbTagMode == TmdbTagMode.RENAME_AND_TAG, recommended = true,
                        desc = "按上面的规则改名、移到影视库，再加标签") {
                        update(config.copy(tmdbTagMode = TmdbTagMode.RENAME_AND_TAG))
                    }
                    RadioOption("只加标签，不改名不移动", config.tmdbTagMode == TmdbTagMode.TAG_ONLY,
                        desc = "保留原文件名和位置，只在末尾追加标签") {
                        update(config.copy(tmdbTagMode = TmdbTagMode.TAG_ONLY))
                    }
                    if (config.tmdbTagMode == TmdbTagMode.RENAME_AND_TAG) {
                        SubTitle("加在哪里")
                        CheckOption(if (isTv) "剧集文件夹名" else "电影文件夹名", config.tmdbTagOnFolder,
                            desc = "媒体服务器主要看这里，Plex 只认文件夹上的标签") { update(config.copy(tmdbTagOnFolder = it)) }
                        CheckOption(if (isTv) "每集的文件名" else "视频文件名", config.tmdbTagOnFile,
                            desc = "文件名会变长；不建文件夹时才需要") { update(config.copy(tmdbTagOnFile = it)) }
                    }
                    Hint("标签格式在下方「高级设置」里修改，当前：${config.tmdbTagFormat.format(if (isTv) 1668 else 27205)}")
                }
            }

            // ---------------- 高级 ----------------

            Surface(
                onClick = { advanced = !advanced },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("高级设置", fontWeight = FontWeight.Bold)
                        Text("标签格式、附加信息、评分、分隔符、发布组、显示顺序", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline)
                    }
                    Icon(if (advanced) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null)
                }
            }

            if (advanced) {
                if (tagOn) Section("TMDB 标签格式") {
                    val formats = buildList {
                        for (b in TagBracket.entries) for (k in listOf("tmdbid", "tmdb")) for (j in listOf("=", "-")) add(TmdbTagFormat(b, k, j))
                    }
                    OptionGrid(
                        options = formats,
                        selected = config.tmdbTagFormat,
                        label = { it.format(0).replace("0", "xxxx") },
                        recommended = TmdbTagFormat(TagBracket.CURLY, "tmdb", "-"),
                    ) { update(config.copy(tmdbTagFormat = it)) }
                }

                Section("附加信息") {
                    SwitchRow("分辨率 / 片源 / 编码", config.extraInfo, desc = "例如 2160p BluRay x265") { update(config.copy(extraInfo = it)) }
                    SwitchRow("评分", config.imdbRating, desc = "TMDB 评分，例如 TMDB8.4") { update(config.copy(imdbRating = it)) }
                    SwitchRow("发布组", config.keepReleaseGroup, desc = "末尾加「-EVO」这样的发布组名") { update(config.copy(keepReleaseGroup = it)) }
                }

                Section("分隔符") {
                    OptionGrid(
                        options = Separator.entries,
                        selected = config.separator,
                        label = { if (it == Separator.SPACE) "空格" else it.value },
                        recommended = Separator.SPACE,
                    ) { update(config.copy(separator = it)) }
                }

                Section("显示顺序") {
                    val names = mapOf(
                        Part.TITLE to "标题", Part.YEAR to "年份", Part.SEASON_EPISODE to "季集",
                        Part.EPISODE_TITLE to "集标题", Part.EXTRA_INFO to "附加信息",
                        Part.RATING to "评分", Part.TMDB_TAG to "TMDB 标签",
                    )
                    Hint("关闭的项目不会出现在名称里，排序只影响开启的项目")
                    val visible = config.order.filter { isTv || (it != Part.SEASON_EPISODE && it != Part.EPISODE_TITLE) }
                    visible.forEachIndexed { i, part ->
                        Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${i + 1}", color = MaterialTheme.colorScheme.outline, fontSize = 13.sp, modifier = Modifier.width(24.dp))
                            Text(names[part].orEmpty(), Modifier.weight(1f))
                            IconButton(enabled = i > 0, modifier = Modifier.size(40.dp),
                                onClick = { update(config.copy(order = config.order.swap(part, visible[i - 1]))) }) {
                                Icon(Icons.Default.KeyboardArrowUp, "上移")
                            }
                            IconButton(enabled = i < visible.lastIndex, modifier = Modifier.size(40.dp),
                                onClick = { update(config.copy(order = config.order.swap(part, visible[i + 1]))) }) {
                                Icon(Icons.Default.KeyboardArrowDown, "下移")
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (showPlayerPicker) {
        AlertDialog(
            onDismissRequest = { showPlayerPicker = false },
            title = { Text("选择要适配的播放器") },
            text = {
                Column {
                    Hint("切换后电影和剧集的命名规则都会换成该播放器的预设，你之前的自定义会被覆盖。")
                    PlayerPresets.all.forEach { (name, cfg) ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                onChange(settings.copy(player = name, movieNaming = cfg, tvNaming = cfg))
                                showPlayerPicker = false
                            }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = settings.player == name, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Text(name)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showPlayerPicker = false }) { Text("取消") } },
        )
    }

    if (askReset) {
        val current = if (isTv) "剧集" else "电影"
        AlertDialog(
            onDismissRequest = { askReset = false },
            title = { Text("恢复默认命名？") },
            text = { Text("恢复为 ${settings.player} 的预设规则。") },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { update(preset); askReset = false }) { Text("只恢复${current}命名") }
                    TextButton(onClick = { onChange(settings.copy(tvNaming = preset, movieNaming = preset)); askReset = false }) {
                        Text("剧集和电影都恢复")
                    }
                    TextButton(onClick = { askReset = false }) { Text("取消") }
                }
            },
        )
    }
}

private fun List<Part>.swap(a: Part, b: Part): List<Part> = map { if (it == a) b else if (it == b) a else it }

/**
 * 实时预览：用示例文件按当前设置真正跑一遍命名和刮削（不联网），
 * 显示整理后的目录结构和会生成的文件。
 */
@Composable
fun LivePreview(settings: AppSettings, isTv: Boolean, showFiles: Boolean) {
    var view by remember { mutableIntStateOf(if (showFiles) 1 else 0) }
    val preview by produceState<SamplePreview?>(null, settings, isTv) {
        value = runCatching { SamplePreviews.build(settings, isTv) }.getOrNull()
    }
    val mono = FontFamily.Monospace

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(if (isTv) "剧集预览" else "电影预览", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            // 独占一行、去掉勾选图标：两个按钮等宽，文字不会被挤成两行
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().height(36.dp)) {
                listOf("整理后路径", "生成文件").forEachIndexed { i, label ->
                    SegmentedButton(
                        selected = view == i, onClick = { view = i },
                        shape = SegmentedButtonDefaults.itemShape(i, 2),
                        icon = {},
                    ) { Text(label, fontSize = 13.sp, maxLines = 1) }
                }
            }
            Spacer(Modifier.height(10.dp))
            val p = preview
            if (p == null) {
                Text("当前设置无法生成名称", color = Bad, style = MaterialTheme.typography.bodySmall)
                return@Column
            }
            PreviewLabel("原文件")
            Text(p.original, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            if (view == 0) {
                PreviewLabel("整理后")
                val inLibrary = p.targetPath.startsWith(p.libraryRoot.trimEnd('/') + "/")
                val base = if (inLibrary) p.libraryRoot else p.targetPath.substringBeforeLast('/')
                val segments = p.targetPath.removePrefix(base).trim('/').split('/')
                Text(base, fontFamily = mono, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                segments.forEachIndexed { i, seg ->
                    val last = i == segments.lastIndex
                    Text(
                        "  ".repeat(i) + "└ " + seg,
                        fontFamily = mono, fontSize = 12.sp,
                        fontWeight = if (last) FontWeight.Bold else FontWeight.Normal,
                        color = if (last) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
            } else {
                PreviewLabel("刮削会写入 ${p.files.size} 个文件（按刮削设置）")
                if (p.files.isEmpty()) Text("不生成任何附属文件", style = MaterialTheme.typography.bodySmall)
                p.files.forEach {
                    Text(it, fontFamily = mono, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            p.warnings.forEach {
                Text("⚠ $it", color = Warn, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
            Text(
                "示例 · 按当前设置实时生成，不会改动任何文件",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

@Composable
private fun PreviewLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(bottom = 2.dp))
}

// =====================================================================
//  刮削 & TMDB 设置
// =====================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScrapeSettingsScreen(
    settings: AppSettings,
    saved: Boolean,
    onChange: (AppSettings) -> Unit,
    tmdbChecks: List<ConnCheck>,
    testing: Boolean,
    onTestTmdb: () -> Unit,
    sourceChecks: List<ConnCheck>,
    onTestSources: () -> Unit,
    onBack: () -> Unit,
) {
    val c = settings.scrape
    fun update(n: ScrapeConfig) = onChange(settings.copy(scrape = n))
    var editTmdb by remember { mutableStateOf(settings.tmdbToken.isBlank()) }
    var showToken by remember { mutableStateOf(false) }
    var askReset by remember { mutableStateOf(false) }
    var previewTv by remember { mutableStateOf(true) }
    val defaults = AppSettings()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("刮削设置") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            )
        },
        bottomBar = { SettingsBottomBar(saved, onReset = { askReset = true }, onDone = onBack) },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---------------- 元数据来源 ----------------
            Section("元数据来源 · TMDB", trailing = {
                TextButton(onClick = { editTmdb = !editTmdb }) { Text(if (editTmdb) "收起" else "修改") }
            }) {
                val proxied = settings.tmdbApiBase != defaults.tmdbApiBase || settings.tmdbImageBase != defaults.tmdbImageBase
                Text(
                    if (settings.tmdbToken.isBlank()) "未填写令牌，无法识别影片" else "令牌已填写" + if (proxied) " · 使用自定义地址" else " · 官方地址",
                    color = if (settings.tmdbToken.isBlank()) Bad else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (editTmdb) {
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = settings.tmdbToken, onValueChange = { onChange(settings.copy(tmdbToken = it)) },
                        label = { Text("API 读访问令牌（eyJ 开头）") }, singleLine = true,
                        visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = { TextButton(onClick = { showToken = !showToken }) { Text(if (showToken) "隐藏" else "显示") } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SettingField("API 地址（可填反代）", settings.tmdbApiBase) { onChange(settings.copy(tmdbApiBase = it)) }
                    SettingField("图片地址（可填反代）", settings.tmdbImageBase) { onChange(settings.copy(tmdbImageBase = it)) }
                    if (proxied) TextButton(onClick = {
                        onChange(settings.copy(tmdbApiBase = defaults.tmdbApiBase, tmdbImageBase = defaults.tmdbImageBase))
                    }) { Text("改回官方地址") }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    OutlinedButton(onClick = onTestTmdb, enabled = !testing && settings.tmdbToken.isNotBlank()) {
                        if (testing) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("测试 API 和图片")
                    }
                }
                tmdbChecks.forEach { CheckLine(it) }
            }

            // ---------------- 补充来源 ----------------
            Section("补充刮削来源") {
                val x = c.extra
                fun upX(n: com.example.mediaorganizer.metadata.extra.ExtraConfig) = update(c.copy(extra = n))
                Hint("识别影片仍用 TMDB；下面的来源只补充信息，查不到或出错时自动跳过，不影响刮削。")
                SwitchRow("豆瓣", x.douban, desc = "中文简介、豆瓣评分和豆瓣 ID。非官方接口，有频率限制，大量刮削时会慢一些") {
                    upX(x.copy(douban = it))
                }
                SwitchRow("Bangumi 番组计划", x.bangumi, desc = "只用于动画：中文简介、Bangumi 评分和 ID") { upX(x.copy(bangumi = it)) }
                if (x.douban || x.bangumi) CheckOption(
                    "简介和评分优先用豆瓣 / Bangumi", x.preferDouban,
                    desc = "关闭则用 TMDB 的；无论哪种，各家评分都会写进 NFO",
                ) { upX(x.copy(preferDouban = it)) }
                SwitchRow("TheTVDB", x.tvdb, desc = "写入 TVDB ID（无需 key）；填 key 后，TMDB 缺集标题 / 简介时用 TVDB 中文补上") {
                    upX(x.copy(tvdb = it))
                }
                if (x.tvdb) {
                    SettingField("TheTVDB API key（可选）", x.tvdbApiKey, password = true) { upX(x.copy(tvdbApiKey = it)) }
                    SettingField("订阅 PIN（个人 key 需要）", x.tvdbPin, password = true) { upX(x.copy(tvdbPin = it)) }
                }
                SwitchRow("Fanart.tv", x.fanart, desc = "高清 logo、clearart、横幅、横版海报、光盘图（Infuse / Emby / Kodi 模板）") {
                    upX(x.copy(fanart = it))
                }
                if (x.fanart) {
                    SettingField("Fanart.tv API key（免费申请）", x.fanartApiKey, password = true) { upX(x.copy(fanartApiKey = it)) }
                    if (x.fanartApiKey.isBlank()) Text("未填写 key 时不会下载 Fanart.tv 图片", color = Warn, style = MaterialTheme.typography.bodySmall)
                }
                if (x.douban || x.bangumi || x.tvdb || x.fanart) OutlinedButton(
                    onClick = onTestSources, enabled = !testing, modifier = Modifier.padding(top = 6.dp),
                ) {
                    if (testing) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("测试已开启的来源")
                }
                sourceChecks.forEach { CheckLine(it) }
            }

            // ---------------- 播放器兼容 ----------------
            Section("播放器兼容模板") {
                Hint("决定海报、背景图等用什么文件名。选一个主要用的；多选可同时兼容多个播放器，但同一张图会存多份。")
                fun toggle(t: ScrapeTemplate, on: Boolean) =
                    update(c.copy(templates = (if (on) c.templates + t else c.templates - t).ifEmpty { setOf(t) }))
                CheckOption("Infuse / VidHub", ScrapeTemplate.INFUSE in c.templates,
                    desc = "poster.jpg、fanart.jpg，另加 folder.jpg 让文件夹直接显示封面") { toggle(ScrapeTemplate.INFUSE, it) }
                CheckOption("Emby / Jellyfin / Kodi", ScrapeTemplate.KODI_EMBY in c.templates,
                    desc = "poster.jpg、fanart.jpg、seasonXX-poster.jpg 等社区标准命名") { toggle(ScrapeTemplate.KODI_EMBY, it) }
                CheckOption("Plex / 老播放器", ScrapeTemplate.PLEX in c.templates,
                    desc = "图片与视频同名（视频名.jpg），老播放器也能认出海报") { toggle(ScrapeTemplate.PLEX, it) }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = previewTv, onClick = { previewTv = true }, label = { Text("剧集示例") })
                FilterChip(selected = !previewTv, onClick = { previewTv = false }, label = { Text("电影示例") })
            }
            LivePreview(settings, previewTv, showFiles = true)

            // ---------------- 文件生成 ----------------
            Section("NFO 信息文件", trailing = { Switch(c.nfo, { update(c.copy(nfo = it)) }) }) {
                if (!c.nfo) Off("关闭：不写 NFO，播放器只能自己联网识别")
                else {
                    Hint("包含简介、类型、评分等，媒体服务器可直接读取，不必再联网刮削。")
                    SubTitle("演职人员")
                    OptionGrid(
                        options = listOf(0, 10, 20, 30),
                        selected = if (c.cast) c.maxActors else 0,
                        label = { if (it == 0) "不写入" else "前 $it 位" },
                        recommended = 20,
                    ) { n -> update(if (n == 0) c.copy(cast = false) else c.copy(cast = true, maxActors = n)) }
                    Hint("写入越多 NFO 越大；演员信息已包含在详情请求里，不会增加网络请求次数。")
                }
            }

            Section("图片") {
                CheckOption("海报", c.poster) { update(c.copy(poster = it)) }
                CheckOption("背景图", c.fanart) { update(c.copy(fanart = it)) }
                CheckOption("Logo", c.logo, desc = "每部作品多一次请求") { update(c.copy(logo = it)) }
                CheckOption("季海报", c.seasonPoster) { update(c.copy(seasonPoster = it)) }
                CheckOption("单集缩略图", c.episodeThumb, desc = "每集一张，剧集多时文件数量最多") { update(c.copy(episodeThumb = it)) }
                SubTitle("图片质量")
                OptionGrid(
                    options = ImageQuality.entries,
                    selected = c.quality,
                    label = { when (it) { ImageQuality.ORIGINAL -> "原图"; ImageQuality.HIGH -> "高清"; ImageQuality.STANDARD -> "标准" } },
                ) { update(c.copy(quality = it)) }
                Hint("具体文件名随上方模板变化，见预览的「生成文件」。")
            }

            // ---------------- 执行策略 ----------------
            Section("执行策略") {
                SwitchRow(
                    "整理后自动刮削", settings.autoScrape,
                    desc = if (settings.autoScrape) "整理完立即为成功的文件刮削"
                    else "只整理文件；需要时在主界面右上角菜单「刮削已整理的文件」",
                ) { onChange(settings.copy(autoScrape = it)) }
                SubTitle("目标位置已有同名文件时")
                RadioOption("只补缺少的", c.strategy == ScrapeStrategy.ONLY_MISSING,
                    desc = "已有的 NFO 和图片全部保留，最省流量") { update(c.copy(strategy = ScrapeStrategy.ONLY_MISSING)) }
                RadioOption("更新 NFO，保留图片", c.strategy == ScrapeStrategy.UPDATE_NFO, recommended = true,
                    desc = "重写 NFO 拿到最新信息；已有的海报等图片不动，保留你自己换过的图") {
                    update(c.copy(strategy = ScrapeStrategy.UPDATE_NFO))
                }
                RadioOption("全部重新生成", c.strategy == ScrapeStrategy.OVERWRITE,
                    desc = "已有的 NFO 和图片都会被替换") { update(c.copy(strategy = ScrapeStrategy.OVERWRITE)) }
                Hint("自动和手动刮削都按这里的策略。撤销时只删除本次新建的文件，被替换的旧文件无法找回。")
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (askReset) {
        AlertDialog(
            onDismissRequest = { askReset = false },
            title = { Text("恢复默认刮削设置？") },
            text = { Text("模板、NFO、图片和执行策略恢复为默认值。TMDB 令牌和地址不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    onChange(settings.copy(scrape = ScrapeConfig(), autoScrape = defaults.autoScrape))
                    askReset = false
                }) { Text("恢复") }
            },
            dismissButton = { TextButton(onClick = { askReset = false }) { Text("取消") } },
        )
    }
}

// =====================================================================
//  通用小组件
// =====================================================================

/** 设置页底栏：保存状态 + 恢复默认 + 完成 */
@Composable
private fun SettingsBottomBar(saved: Boolean, onReset: () -> Unit, onDone: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                if (saved) "✓ 修改已自动保存" else "保存中…",
                style = MaterialTheme.typography.labelSmall,
                color = if (saved) Recommend else MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onReset, modifier = Modifier.weight(1f).height(48.dp)) { Text("恢复默认") }
                Button(onClick = onDone, modifier = Modifier.weight(1f).height(48.dp)) { Text("完成") }
            }
        }
    }
}

@Composable
private fun CheckLine(c: ConnCheck) {
    val (mark, color) = when (c.level) {
        ConnCheck.Level.OK -> "✓" to Recommend
        ConnCheck.Level.WARN -> "!" to Warn
        ConnCheck.Level.FAIL -> "✗" to Bad
    }
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(mark, color = color, fontWeight = FontWeight.Bold, modifier = Modifier.width(18.dp))
        Text(c.label, fontWeight = FontWeight.Medium, fontSize = 13.sp, modifier = Modifier.width(40.dp))
        Text(c.detail, fontSize = 13.sp, color = if (c.level == ConnCheck.Level.OK) Color.Unspecified else color)
    }
}

@Composable
fun Section(title: String, trailing: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(Modifier.heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(width = 3.dp, height = 14.dp).background(Accent, RoundedCornerShape(2.dp)))
                Spacer(Modifier.width(8.dp))
                Text(title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                trailing?.invoke()
            }
            content()
        }
    }
}

/** 开关关闭时的说明，淡显 */
@Composable
private fun Off(text: String) {
    Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.alpha(0.8f).padding(vertical = 2.dp))
}

@Composable
private fun SubTitle(text: String) {
    Text(text, fontWeight = FontWeight.Medium, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(vertical = 2.dp))
}

@Composable
private fun RecommendBadge() {
    Text(
        "推荐", color = Recommend, fontSize = 10.sp,
        modifier = Modifier.padding(start = 4.dp).background(Recommend.copy(alpha = 0.10f), RoundedCornerShape(3.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

@Composable
private fun RadioOption(label: String, selected: Boolean, recommended: Boolean = false, desc: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.width(8.dp))
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label)
                if (recommended) RecommendBadge()
            }
            desc?.let { Hint(it) }
        }
    }
}

@Composable
private fun CheckOption(label: String, checked: Boolean, desc: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(label)
            desc?.let { Hint(it) }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, desc: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label)
            desc?.let { Hint(it) }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** 两列单选网格 */
@Composable
private fun <T> OptionGrid(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    recommended: T? = null,
    onSelect: (T) -> Unit,
) {
    options.chunked(2).forEach { pair ->
        Row(Modifier.fillMaxWidth()) {
            pair.forEach { opt ->
                Row(
                    Modifier.weight(1f).clickable { onSelect(opt) }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = opt == selected, onClick = null)
                    Spacer(Modifier.width(6.dp))
                    Text(label(opt), fontSize = 14.sp)
                    if (opt == recommended) RecommendBadge()
                }
            }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
fun SettingField(label: String, value: String, password: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
    )
}
