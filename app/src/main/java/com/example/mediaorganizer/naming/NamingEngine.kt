package com.example.mediaorganizer.naming

/** 季集样式 */
enum class SeasonEpisodeStyle(val sample: String) {
    UPPER("S01E02"), LOWER("s01e02"), SHORT("s1e2"), X("1x02"),
    SE_EP("se1.ep2"), WORDS("season1.episode2"), DOTTED("s01.e02");

    fun format(season: Int, episode: Int, episodeEnd: Int? = null): String {
        fun p2(n: Int) = n.toString().padStart(2, '0')
        val base = when (this) {
            UPPER -> "S${p2(season)}E${p2(episode)}"
            LOWER -> "s${p2(season)}e${p2(episode)}"
            SHORT -> "s${season}e$episode"
            X -> "${season}x${p2(episode)}"
            SE_EP -> "se$season.ep$episode"
            WORDS -> "season$season.episode$episode"
            DOTTED -> "s${p2(season)}.e${p2(episode)}"
        }
        if (episodeEnd == null || episodeEnd == episode) return base
        // 连集：S01E01-E02 / 1x01-02
        return when (this) {
            UPPER -> "$base-E${p2(episodeEnd)}"
            LOWER, DOTTED -> "$base-e${p2(episodeEnd)}"
            SHORT, SE_EP, WORDS -> "$base-$episodeEnd"
            X -> "$base-${p2(episodeEnd)}"
        }
    }
}

enum class YearStyle { NONE, PLAIN, PAREN }               // 不显示 / 1999 / (1999)
enum class Separator(val value: String) { DOT("."), UNDERSCORE("_"), SPACE(" "), DASH("-") }
enum class TitleLanguage { CHINESE_FIRST, ENGLISH_FIRST }
enum class EpisodeYearSource { FIRST_SEASON, SEASON_AIR }   // 首季年份 / 季发行年份
enum class TmdbTagMode { OFF, RENAME_AND_TAG, TAG_ONLY }
enum class TagBracket(val open: String, val close: String) { CURLY("{", "}"), SQUARE("[", "]") }

/** TMDB ID 标签模板，组合出截图里的 8 种：{tmdbid=xxxx} {tmdb-xxxx} [tmdbid-xxxx] … */
data class TmdbTagFormat(
    val bracket: TagBracket = TagBracket.CURLY,
    val key: String = "tmdb",       // "tmdb" 或 "tmdbid"
    val joiner: String = "-",       // "-" 或 "="
) {
    fun format(id: Int) = "${bracket.open}$key$joiner$id${bracket.close}"
}

/** 文件名各组成部分，顺序可自定义（显示顺序自定义） */
enum class Part { TITLE, YEAR, SEASON_EPISODE, EPISODE_TITLE, EXTRA_INFO, RATING, TMDB_TAG }

data class NamingConfig(
    val titleLanguage: TitleLanguage = TitleLanguage.CHINESE_FIRST,
    val multiLanguage: Boolean = false,                       // 同时使用多语言：老友记 Friends
    val yearStyle: YearStyle = YearStyle.PAREN,
    val episodeYearSource: EpisodeYearSource = EpisodeYearSource.FIRST_SEASON,
    val seasonEpisodeStyle: SeasonEpisodeStyle = SeasonEpisodeStyle.UPPER,
    val separator: Separator = Separator.SPACE,
    val includeEpisodeTitle: Boolean = false,
    val extraInfo: Boolean = false,                           // 附加信息：2160p BluRay x265
    val imdbRating: Boolean = false,                          // 评分显示（数据来自 TMDB）
    val keepReleaseGroup: Boolean = false,                    // 发布组：-EVO
    val tmdbTagMode: TmdbTagMode = TmdbTagMode.OFF,
    val tmdbTagFormat: TmdbTagFormat = TmdbTagFormat(),
    val tmdbTagOnFile: Boolean = true,
    val tmdbTagOnFolder: Boolean = true,
    val movieFolder: Boolean = true,                          // 电影放进同名文件夹
    val folderSameAsFile: Boolean = false,                    // 文件夹名称与文件名称保持一致
    val seasonFolderFormat: String = "Season %02d",           // 也可以是 "S%02d"、"第%d季"；留空表示不建季文件夹
    val order: List<Part> = DEFAULT_ORDER,
) {
    companion object {
        val DEFAULT_ORDER = listOf(
            Part.TITLE, Part.YEAR, Part.SEASON_EPISODE, Part.EPISODE_TITLE,
            Part.EXTRA_INFO, Part.RATING, Part.TMDB_TAG,
        )
    }
}

/** 各播放器预设 */
object PlayerPresets {
    val PLEX = NamingConfig(
        tmdbTagMode = TmdbTagMode.RENAME_AND_TAG,
        tmdbTagFormat = TmdbTagFormat(TagBracket.CURLY, "tmdb", "-"),
        tmdbTagOnFile = false,           // Plex 只需要文件夹上的标签
    )
    val EMBY_JELLYFIN = NamingConfig(
        tmdbTagMode = TmdbTagMode.RENAME_AND_TAG,
        tmdbTagFormat = TmdbTagFormat(TagBracket.SQUARE, "tmdbid", "="),
        tmdbTagOnFile = false,
    )
    val INFUSE = NamingConfig()          // Infuse 认 "片名 (年份)" + SxxEyy 即可
    val VIDHUB = NamingConfig()
    val KODI = NamingConfig()
    val NETEASE_POPCORN = NamingConfig()

    val all = linkedMapOf(
        "Infuse" to INFUSE, "Vidhub" to VIDHUB, "Emby/Jellyfin" to EMBY_JELLYFIN,
        "Plex" to PLEX, "Kodi" to KODI, "网易爆米花" to NETEASE_POPCORN,
    )
}

/** 命名所需的全部信息，由 TMDB 详情 + 文件名解析结果组装（不含网络请求） */
data class NamingInput(
    val isEpisode: Boolean,
    val chineseTitle: String,
    val englishTitle: String?,
    val year: Int?,                 // 电影上映年 / 剧集首播年
    val seasonYear: Int? = null,    // 当季首播年
    val season: Int? = null,
    val episode: Int? = null,
    val episodeEnd: Int? = null,
    val episodeTitle: String? = null,
    val tmdbId: Int,
    val imdbRating: Double? = null,
    val resolution: String? = null,
    val source: String? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val releaseGroup: String? = null,
    val extension: String,
    val originalFileName: String,
)

data class NamingResult(
    val folders: List<String>,      // 相对媒体库根目录，如 ["老友记 (1994) {tmdb-1668}", "Season 02"]
    val fileName: String,
) {
    val relativePath: String get() = (folders + fileName).joinToString("/")
}

class NamingEngine(private val config: NamingConfig) {

    fun build(input: NamingInput): NamingResult {
        val c = config
        val fileBase = if (c.tmdbTagMode == TmdbTagMode.TAG_ONLY) {
            // 不改名，仅在末尾加标签
            val original = input.originalFileName.substringBeforeLast('.')
            "$original ${c.tmdbTagFormat.format(input.tmdbId)}"
        } else {
            join(fileParts(input, withTag = c.tmdbTagMode == TmdbTagMode.RENAME_AND_TAG && c.tmdbTagOnFile))
        }
        val fileName = sanitize(fileBase, reserve = input.extension.length + 1) + "." + input.extension.lowercase()

        val folders = mutableListOf<String>()
        // 仅加标签模式：原地改名，不动文件夹结构
        if (c.tmdbTagMode == TmdbTagMode.TAG_ONLY) return NamingResult(folders, fileName)
        val folderTag = c.tmdbTagMode != TmdbTagMode.OFF && c.tmdbTagOnFolder
        if (input.isEpisode) {
            folders += sanitize(showFolderName(input, folderTag))
            if (c.seasonFolderFormat.isNotBlank()) input.season?.let { folders += sanitize(c.seasonFolderFormat.format(it)) }
        } else if (c.movieFolder) {
            folders += if (c.folderSameAsFile) fileName.substringBeforeLast('.')
            else sanitize(showFolderName(input, folderTag))
        }
        return NamingResult(folders, fileName)
    }

    private fun title(input: NamingInput): String {
        val zh = input.chineseTitle.trim()
        val en = input.englishTitle?.trim()?.takeIf { it.isNotEmpty() && !it.equals(zh, true) }
        val (first, second) = when (config.titleLanguage) {
            TitleLanguage.CHINESE_FIRST -> zh to en
            TitleLanguage.ENGLISH_FIRST -> (en ?: zh) to (if (en != null) zh else null)
        }
        return if (config.multiLanguage && second != null) "$first ${second}" else first
    }

    private fun year(input: NamingInput): String? {
        val y = if (input.isEpisode && config.episodeYearSource == EpisodeYearSource.SEASON_AIR)
            input.seasonYear ?: input.year else input.year
        return when {
            y == null -> null
            config.yearStyle == YearStyle.NONE -> null
            config.yearStyle == YearStyle.PAREN -> "($y)"
            else -> y.toString()
        }
    }

    private fun fileParts(input: NamingInput, withTag: Boolean): List<String?> {
        val parts = config.order.map { part ->
            when (part) {
                Part.TITLE -> title(input).let {
                    if (config.separator == Separator.SPACE) it else it.replace(" ", config.separator.value)
                }
                Part.YEAR -> year(input)
                Part.SEASON_EPISODE -> if (input.isEpisode && input.season != null && input.episode != null)
                    config.seasonEpisodeStyle.format(input.season, input.episode, input.episodeEnd) else null
                Part.EPISODE_TITLE -> if (config.includeEpisodeTitle) input.episodeTitle else null
                Part.EXTRA_INFO -> if (config.extraInfo)
                    listOfNotNull(input.resolution, input.source, input.videoCodec, input.audioCodec)
                        .joinToString(config.separator.value).ifEmpty { null } else null
                Part.RATING -> if (config.imdbRating) input.imdbRating?.takeIf { it > 0 }?.let { "TMDB%.1f".format(java.util.Locale.US, it) } else null
                Part.TMDB_TAG -> if (withTag) config.tmdbTagFormat.format(input.tmdbId) else null
            }
        }.toMutableList()
        // 发布组用 "-" 接在最后一个非标签、非评分部分后面：… AC3-EVO {tmdb-1668}
        if (config.keepReleaseGroup && input.releaseGroup != null) {
            val i = config.order.indices.lastOrNull { parts[it] != null && config.order[it] != Part.TMDB_TAG && config.order[it] != Part.RATING }
            if (i != null) parts[i] = parts[i] + "-" + input.releaseGroup
        }
        return parts
    }

    private fun join(parts: List<String?>): String =
        parts.filterNotNull().filter { it.isNotBlank() }.joinToString(config.separator.value)

    private fun showFolderName(input: NamingInput, withTag: Boolean): String {
        val parts = listOfNotNull(
            title(input),
            input.year?.let { if (config.yearStyle == YearStyle.PLAIN) "$it" else "($it)" },
            if (withTag) config.tmdbTagFormat.format(input.tmdbId) else null,
        )
        return parts.joinToString(" ")
    }

    /**
     * 去掉各系统/网盘不允许的字符，限制长度（多数网盘上限 255 字节）。
     */
    private fun sanitize(name: String, reserve: Int = 0): String {
        var s = name
            .replace(':', '：').replace('?', '？').replace('*', '＊')
            .replace('"', '\'').replace('<', '《').replace('>', '》')
            .replace('|', '｜').replace('/', '／').replace('\\', '＼')
            .replace(Regex("""[\u0000-\u001F]"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim().trimEnd('.', ' ')
        val maxBytes = 240 - reserve
        while (s.toByteArray(Charsets.UTF_8).size > maxBytes) s = s.dropLast(1)
        return s.trimEnd('.', ' ', '-', '_').ifEmpty { "untitled" }
    }

    init {
        require(config.order.toSet().size == config.order.size) { "显示顺序里有重复项" }
    }
}
