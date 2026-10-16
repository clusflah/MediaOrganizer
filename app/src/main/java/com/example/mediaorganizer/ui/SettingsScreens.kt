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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mediaorganizer.naming.*
import com.example.mediaorganizer.scraper.ImageQuality
import com.example.mediaorganizer.scraper.ScrapeConfig
import com.example.mediaorganizer.scraper.ScrapeStrategy
import com.example.mediaorganizer.scraper.ScrapeTemplate
import com.example.mediaorganizer.settings.AppSettings

private val Accent = Color(0xFFFF7A00)
private val Recommend = Color(0xFF1FA463)

// =====================================================================
//  命名规则设置
// =====================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NamingSettingsScreen(
    settings: AppSettings,
    onChange: (AppSettings) -> Unit,
    onBack: () -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }          // 0 剧集  1 电影
    var showPlayerPicker by remember { mutableStateOf(false) }
    val isTv = tab == 0
    val config = if (isTv) settings.tvNaming else settings.movieNaming
    fun update(c: NamingConfig) = onChange(if (isTv) settings.copy(tvNaming = c) else settings.copy(movieNaming = c))

    val preset = PlayerPresets.all[settings.player]
    val modified = preset != null && (settings.tvNaming != preset || settings.movieNaming != preset)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("个性化命名显示设置") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            )
        },
        bottomBar = {
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { update(preset ?: NamingConfig()) },
                    modifier = Modifier.weight(1f),
                ) { Text("恢复默认设置") }
                Button(onClick = onBack, modifier = Modifier.weight(1f)) { Text("完成") }
            }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 播放器
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("需要适配的播放器：", fontWeight = FontWeight.Medium)
                Text(settings.player + if (modified) "（已修改）" else "", fontWeight = FontWeight.Bold)
                TextButton(onClick = { showPlayerPicker = true }) { Text("切换播放器") }
            }

            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("剧集命名") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("电影命名") })
            }

            PreviewCard(isTv, config)

            // ---- TMDB ID 标签 ----
            Section("TMDB ID 标签", trailing = {
                Switch(
                    checked = config.tmdbTagMode != TmdbTagMode.OFF,
                    onCheckedChange = { update(config.copy(tmdbTagMode = if (it) TmdbTagMode.RENAME_AND_TAG else TmdbTagMode.OFF)) },
                )
            }) {
                if (config.tmdbTagMode != TmdbTagMode.OFF) {
                    RadioOption("改名后并添加 TMDB ID 标签", config.tmdbTagMode == TmdbTagMode.RENAME_AND_TAG,
                        desc = "在名称末尾添加 TMDB ID 标签，以提高识别率。") {
                        update(config.copy(tmdbTagMode = TmdbTagMode.RENAME_AND_TAG))
                    }
                    RadioOption("不改名仅添加 TMDB ID 标签", config.tmdbTagMode == TmdbTagMode.TAG_ONLY,
                        desc = "保留原始文件名，仅在末尾增加 ID 标签，不移动文件。") {
                        update(config.copy(tmdbTagMode = TmdbTagMode.TAG_ONLY))
                    }
                    SubTitle("标签模板")
                    val formats = buildList {
                        for (b in TagBracket.entries) for (k in listOf("tmdbid", "tmdb")) for (j in listOf("=", "-")) add(TmdbTagFormat(b, k, j))
                    }
                    OptionGrid(
                        options = formats,
                        selected = config.tmdbTagFormat,
                        label = { it.format(0).replace("0", "xxxx") },
                        recommended = TmdbTagFormat(TagBracket.CURLY, "tmdb", "-"),
                    ) { update(config.copy(tmdbTagFormat = it)) }
                    SubTitle("添加位置")
                    CheckOption("文件夹名", config.tmdbTagOnFolder) { update(config.copy(tmdbTagOnFolder = it)) }
                    CheckOption("文件名", config.tmdbTagOnFile) { update(config.copy(tmdbTagOnFile = it)) }
                }
            }

            // ---- 文件名偏好 ----
            Section("文件名偏好") {
                RadioOption("中文优先", config.titleLanguage == TitleLanguage.CHINESE_FIRST, recommended = true) {
                    update(config.copy(titleLanguage = TitleLanguage.CHINESE_FIRST))
                }
                RadioOption("英文优先", config.titleLanguage == TitleLanguage.ENGLISH_FIRST) {
                    update(config.copy(titleLanguage = TitleLanguage.ENGLISH_FIRST))
                }
                CheckOption("同时使用多语言", config.multiLanguage, desc = "勾选后，将按偏好依次展示中英文标题。") {
                    update(config.copy(multiLanguage = it))
                }
            }

            // ---- 年份 ----
            Section("年份显示", trailing = {
                Switch(
                    checked = config.yearStyle != YearStyle.NONE,
                    onCheckedChange = { update(config.copy(yearStyle = if (it) YearStyle.PAREN else YearStyle.NONE)) },
                )
            }) {
                if (config.yearStyle != YearStyle.NONE) {
                    OptionGrid(
                        options = listOf(YearStyle.PLAIN, YearStyle.PAREN),
                        selected = config.yearStyle,
                        label = { if (it == YearStyle.PLAIN) "1999" else "(1999)" },
                        recommended = YearStyle.PAREN,
                    ) { update(config.copy(yearStyle = it)) }
                    if (isTv) {
                        SubTitle("剧集年份")
                        OptionGrid(
                            options = EpisodeYearSource.entries,
                            selected = config.episodeYearSource,
                            label = { if (it == EpisodeYearSource.FIRST_SEASON) "首季年份" else "季发行年份" },
                            recommended = EpisodeYearSource.FIRST_SEASON,
                        ) { update(config.copy(episodeYearSource = it)) }
                    }
                }
            }

            // ---- 季集 ----
            if (isTv) {
                Section("季集样式显示") {
                    OptionGrid(
                        options = SeasonEpisodeStyle.entries,
                        selected = config.seasonEpisodeStyle,
                        label = { it.sample },
                        recommended = SeasonEpisodeStyle.UPPER,
                    ) { update(config.copy(seasonEpisodeStyle = it)) }
                }
                Section("集标题", trailing = {
                    Switch(config.includeEpisodeTitle, { update(config.copy(includeEpisodeTitle = it)) })
                }) { Hint("例如：老友记 (1994) S02E03 罗斯的新女友") }
            }

            // ---- 其它开关 ----
            Section("附加信息显示", trailing = { Switch(config.extraInfo, { update(config.copy(extraInfo = it)) }) }) {
                Hint("分辨率、片源、编码，例如 2160p BluRay x265")
            }
            Section("评分显示", trailing = { Switch(config.imdbRating, { update(config.copy(imdbRating = it)) }) }) {
                Hint("TMDB 评分，例如 TMDB8.4")
            }

            Section("分割符号显示") {
                OptionGrid(
                    options = Separator.entries,
                    selected = config.separator,
                    label = { if (it == Separator.SPACE) "符号 空格" else "符号 ${it.value}" },
                    recommended = Separator.SPACE,
                ) { update(config.copy(separator = it)) }
                SubTitle("发布组符号显示")
                CheckOption("保留发布组（符号 -）", config.keepReleaseGroup) { update(config.copy(keepReleaseGroup = it)) }
            }

            // ---- 文件夹 ----
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
                    SwitchRow("电影放入独立文件夹", config.movieFolder) { update(config.copy(movieFolder = it)) }
                    if (config.movieFolder) {
                        SwitchRow("文件夹名称与文件名称保持一致", config.folderSameAsFile) { update(config.copy(folderSameAsFile = it)) }
                    }
                }
            }

            // ---- 显示顺序 ----
            Section("显示顺序自定义") {
                val names = mapOf(
                    Part.TITLE to "标题", Part.YEAR to "年份", Part.SEASON_EPISODE to "季集",
                    Part.EPISODE_TITLE to "集标题", Part.EXTRA_INFO to "附加信息",
                    Part.RATING to "评分", Part.TMDB_TAG to "TMDB 标签",
                )
                val visible = config.order.filter { isTv || (it != Part.SEASON_EPISODE && it != Part.EPISODE_TITLE) }
                visible.forEachIndexed { i, part ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}. ${names[part]}", Modifier.weight(1f))
                        IconButton(enabled = i > 0, onClick = { update(config.copy(order = config.order.swap(part, visible[i - 1]))) }) {
                            Icon(Icons.Default.KeyboardArrowUp, "上移")
                        }
                        IconButton(enabled = i < visible.lastIndex, onClick = { update(config.copy(order = config.order.swap(part, visible[i + 1]))) }) {
                            Icon(Icons.Default.KeyboardArrowDown, "下移")
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
            title = { Text("请选择需要适配的播放器") },
            text = {
                Column {
                    Hint("不同播放器的命名规则不同，切换后会覆盖电影和剧集的命名设置。")
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
            confirmButton = { TextButton(onClick = { showPlayerPicker = false }) { Text("关闭") } },
        )
    }
}

/** 顶部深色示例卡片，随设置实时变化 */
@Composable
private fun PreviewCard(isTv: Boolean, config: NamingConfig) {
    val original = if (isTv) "老友记.1994.S02E03.2160p.Dolby.BluRay.DC.PROPER.x265.AC3-EVO.mp4"
    else "盗梦空间.2010.2160p.Dolby.BluRay.DC.PROPER.x265.AC3-EVO.mp4"
    val input = if (isTv) NamingInput(
        isEpisode = true, chineseTitle = "老友记", englishTitle = "Friends", year = 1994, seasonYear = 1995,
        season = 2, episode = 3, episodeTitle = "罗斯的新女友", tmdbId = 1668, imdbRating = 8.4,
        resolution = "2160p", source = "BluRay", videoCodec = "x265", audioCodec = "AC3", releaseGroup = "EVO",
        extension = "mp4", originalFileName = original,
    ) else NamingInput(
        isEpisode = false, chineseTitle = "盗梦空间", englishTitle = "Inception", year = 2010, tmdbId = 27205, imdbRating = 8.4,
        resolution = "2160p", source = "BluRay", videoCodec = "x265", audioCodec = "AC3", releaseGroup = "EVO",
        extension = "mp4", originalFileName = original,
    )
    val result = remember(config, isTv) { runCatching { NamingEngine(config).build(input) }.getOrNull() }

    Column(
        Modifier.fillMaxWidth().background(Color(0xFF1F222B), RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(if (isTv) "剧集命名示例" else "电影命名示例", color = Color(0xFF9AA0AA), fontSize = 11.sp)
        LabeledLine("原名", original, Color(0xFF9AA0AA), Color.White)
        result?.folders?.forEach { LabeledLine("文件夹", it, Accent, Color(0xFFFFC46B)) }
        LabeledLine("标准名", result?.fileName ?: "（设置有误）", Accent, Color.White)
    }
}

@Composable
private fun LabeledLine(label: String, text: String, labelColor: Color, textColor: Color) {
    Row {
        Text(label, color = labelColor, fontSize = 11.sp, modifier = Modifier.width(44.dp).padding(top = 2.dp))
        Text(text, color = textColor, fontSize = 13.sp)
    }
}

private fun List<Part>.swap(a: Part, b: Part): List<Part> = map { if (it == a) b else if (it == b) a else it }

// =====================================================================
//  刮削 & TMDB 设置
// =====================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScrapeSettingsScreen(
    settings: AppSettings,
    onChange: (AppSettings) -> Unit,
    onBack: () -> Unit,
) {
    val c = settings.scrape
    fun update(n: ScrapeConfig) = onChange(settings.copy(scrape = n))

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("刮削设置") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            )
        },
        bottomBar = {
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { update(ScrapeConfig()) }, modifier = Modifier.weight(1f)) { Text("恢复默认设置") }
                Button(onClick = onBack, modifier = Modifier.weight(1f)) { Text("完成") }
            }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section("TMDB") {
                SettingField("API 读访问令牌", settings.tmdbToken, password = true) { onChange(settings.copy(tmdbToken = it)) }
                SettingField("API 地址（可填反代）", settings.tmdbApiBase) { onChange(settings.copy(tmdbApiBase = it)) }
                SettingField("图片地址（可填反代）", settings.tmdbImageBase) { onChange(settings.copy(tmdbImageBase = it)) }
            }

            Section("刮削文件策略") {
                SwitchRow("整理后自动刮削", settings.autoScrape) { onChange(settings.copy(autoScrape = it)) }
                OptionGrid(
                    options = ScrapeStrategy.entries,
                    selected = c.strategy,
                    label = { if (it == ScrapeStrategy.OVERWRITE) "重新刮削" else "仅刮削缺少文件" },
                ) { update(c.copy(strategy = it)) }
            }

            Section("刮削模板配置") {
                Hint("多选模板时可以同时适配更多播放器，但写入的文件也会更多。")
                fun toggle(t: ScrapeTemplate, on: Boolean) =
                    update(c.copy(templates = (if (on) c.templates + t else c.templates - t).ifEmpty { setOf(t) }))
                CheckOption("Infuse/VidHub 播放器直连（默认）", ScrapeTemplate.INFUSE in c.templates,
                    desc = "额外生成 folder.jpg，在 Apple TV 或电脑文件夹中直接显示海报。") { toggle(ScrapeTemplate.INFUSE, it) }
                CheckOption("Emby/Jellyfin/Kodi 媒体服务器", ScrapeTemplate.KODI_EMBY in c.templates,
                    desc = "文件结构整洁，符合开源社区标准。") { toggle(ScrapeTemplate.KODI_EMBY, it) }
                CheckOption("Plex/高兼容方式", ScrapeTemplate.PLEX in c.templates,
                    desc = "强制图片与视频同名，解决 Plex 或老旧播放器无法识别海报的问题。") { toggle(ScrapeTemplate.PLEX, it) }
            }

            Section("NFO 设置", trailing = { Switch(c.nfo, { update(c.copy(nfo = it)) }) }) {
                if (c.nfo) {
                    SwitchRow("写入演职人员", c.cast) { update(c.copy(cast = it)) }
                    if (c.cast) {
                        SubTitle("演员数量上限")
                        OptionGrid(options = listOf(10, 20, 30), selected = c.maxActors, label = { "$it 人" }, recommended = 20) {
                            update(c.copy(maxActors = it))
                        }
                    }
                }
            }

            Section("刮削文件设置") {
                CheckOption("海报：poster.jpg", c.poster) { update(c.copy(poster = it)) }
                CheckOption("背景图：fanart.jpg", c.fanart) { update(c.copy(fanart = it)) }
                CheckOption("logo 图：clearlogo.png", c.logo) { update(c.copy(logo = it)) }
                CheckOption("季海报：season01-poster.jpg", c.seasonPoster) { update(c.copy(seasonPoster = it)) }
                CheckOption("单集缩略图：xxx-thumb.jpg", c.episodeThumb) { update(c.copy(episodeThumb = it)) }
                SubTitle("图片质量")
                OptionGrid(
                    options = ImageQuality.entries,
                    selected = c.quality,
                    label = { when (it) { ImageQuality.ORIGINAL -> "原图"; ImageQuality.HIGH -> "高清"; ImageQuality.STANDARD -> "标准" } },
                ) { update(c.copy(quality = it)) }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

// =====================================================================
//  通用小组件
// =====================================================================

@Composable
fun Section(title: String, trailing: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(width = 3.dp, height = 14.dp).background(Accent, RoundedCornerShape(2.dp)))
                Spacer(Modifier.width(8.dp))
                Text(title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                trailing?.invoke()
            }
            content()
        }
    }
}

@Composable
private fun SubTitle(text: String) {
    Text(text, fontWeight = FontWeight.Medium, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(vertical = 2.dp))
}

@Composable
private fun RecommendBadge() {
    Text(
        "推荐", color = Recommend, fontSize = 10.sp,
        modifier = Modifier.padding(start = 4.dp).background(Recommend.copy(alpha = 0.12f), RoundedCornerShape(3.dp))
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
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
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
        visualTransformation = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation()
        else androidx.compose.ui.text.input.VisualTransformation.None,
    )
}
