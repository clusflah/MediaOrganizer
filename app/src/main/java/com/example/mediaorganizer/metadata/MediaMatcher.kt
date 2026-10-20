package com.example.mediaorganizer.metadata

import com.example.mediaorganizer.parser.MediaNameParser
import com.example.mediaorganizer.parser.ParsedMedia
import com.example.mediaorganizer.storage.RemoteFile
import com.example.mediaorganizer.storage.StorageProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.log10

data class MatchResult(
    val file: RemoteFile?,
    val parsed: ParsedMedia,
    val best: TmdbSearchResult?,
    val score: Double,                       // 0~1
    val alternatives: List<TmdbSearchResult>, // 供用户手动改选
) {
    /** 低于阈值的需要用户在界面上确认 */
    val needsConfirm: Boolean get() = best == null || score < MediaMatcher.AUTO_ACCEPT
}

/**
 * @param primary 设置里把主数据源改成豆瓣 / TheTVDB / Bangumi 时，按名字在那边搜；
 *                路径里的 TMDB 标签仍按 TMDB 取
 */
class MediaMatcher(private val tmdb: TmdbClient, private val primary: MetadataSource? = null) {

    // 同一部剧几十集会反复搜同一个名字，缓存起来
    private val cache = ConcurrentHashMap<String, List<TmdbSearchResult>>()

    // 按 TMDB 标签直接查到的结果，同一部剧的各集共用
    private val byId = ConcurrentHashMap<String, Result<TmdbSearchResult>>()

    suspend fun match(parsed: ParsedMedia, file: RemoteFile? = null): MatchResult {
        // 路径里已有 TMDB 标签（整理过的库、或用户手动加的）就直接按 ID 取，不再按名字猜
        file?.let { tagId(it.path) }?.let { id ->
            val type = if (parsed.isEpisode) MediaType.TV else MediaType.MOVIE
            lookup(type, id)?.let { return MatchResult(file, parsed, it, score = 1.0, alternatives = emptyList()) }
        }

        val queries = listOfNotNull(parsed.cjkTitle, parsed.latinTitle, parsed.title)
            .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val candidates = LinkedHashMap<Int, TmdbSearchResult>()

        for (q in queries) {
            var results = search(parsed.isEpisode, q, parsed.year)
            // 剧集文件名里的年份常是“当季年份”，带年份搜不到就去掉再搜
            if (results.isEmpty() && parsed.year != null) results = search(parsed.isEpisode, q, null)
            results.forEach { candidates.putIfAbsent(it.id, it) }
            if (results.any { score(parsed, it, queries) >= AUTO_ACCEPT }) break
        }

        val ranked = candidates.values
            .map { it to score(parsed, it, queries) }
            .sortedByDescending { it.second }

        return MatchResult(
            file = file,
            parsed = parsed,
            best = ranked.firstOrNull()?.first,
            score = ranked.firstOrNull()?.second ?: 0.0,
            alternatives = ranked.drop(1).take(8).map { it.first },
        )
    }

    private suspend fun search(isTv: Boolean, query: String, year: Int?): List<TmdbSearchResult> {
        val key = "$isTv|$query|$year"
        cache[key]?.let { return it }
        val r = when {
            // 其他来源的搜索不带年份，年份交给打分
            primary != null -> if (year == null) primary.search(query, isTv) else emptyList()
            isTv -> tmdb.searchTv(query, year)
            else -> tmdb.searchMovie(query, year)
        }
        cache[key] = r
        return r
    }

    private suspend fun lookup(type: MediaType, id: Int): TmdbSearchResult? =
        byId.getOrPut("$type/$id") {
            runCatching {
                if (type == MediaType.TV) tmdb.tv(id).let {
                    TmdbSearchResult(it.id, MediaType.TV, it.name, it.originalName, it.firstAirYear, it.overview,
                        it.posterPath, it.backdropPath, popularity = 0.0)
                } else tmdb.movie(id).let {
                    TmdbSearchResult(it.id, MediaType.MOVIE, it.title, it.originalTitle, it.year, it.overview,
                        it.posterPath, it.backdropPath, popularity = 0.0)
                }
            }
        }.getOrNull()                         // ID 不存在或网络失败就退回按名字搜索

    companion object {
        const val AUTO_ACCEPT = 0.75

        /** {tmdb-123}、[tmdbid=123]、(tmdb=123) 等，取离文件最近的一个 */
        private val TMDB_TAG = Regex("""(?i)[\[{(]\s*tmdb(?:id)?\s*[=-]\s*(\d+)\s*[\]})]""")

        fun tagId(path: String): Int? = TMDB_TAG.findAll(path).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()

        fun score(p: ParsedMedia, c: TmdbSearchResult, queries: List<String>): Double {
            val names = listOfNotNull(c.title, c.originalTitle).map(::norm).filter { it.isNotEmpty() }
            val titleScore = queries.map(::norm).filter { it.isNotEmpty() }.maxOfOrNull { q ->
                names.maxOfOrNull { n ->
                    when {
                        q == n -> 1.0
                        n.contains(q) || q.contains(n) ->
                            0.4 + 0.4 * minOf(q.length, n.length) / maxOf(q.length, n.length)
                        else -> dice(q, n)
                    }
                } ?: 0.0
            } ?: 0.0

            val yearScore = when {
                p.year == null || c.year == null -> 0.0
                p.year == c.year -> 0.15
                abs(p.year - c.year) == 1 -> 0.08
                p.isEpisode -> 0.0          // 剧集年份可能是某一季的
                else -> -0.2
            }
            val popularity = minOf(log10(c.popularity + 1) / 30, 0.08)

            return (titleScore * 0.8 + yearScore + popularity).coerceIn(0.0, 1.0)
        }

        /** 去掉空格和标点，统一小写 */
        private fun norm(s: String) = s.lowercase().replace(Regex("""[\s\p{Punct}：·，。！？、（）《》]"""), "")

        /** 字符二元组相似度，中英文都适用 */
        private fun dice(a: String, b: String): Double {
            if (a.length < 2 || b.length < 2) return if (a == b) 1.0 else 0.0
            val ba = a.windowed(2).groupingBy { it }.eachCount().toMutableMap()
            var overlap = 0
            for (g in b.windowed(2)) {
                val n = ba[g] ?: 0
                if (n > 0) { overlap++; ba[g] = n - 1 }
            }
            return 2.0 * overlap / (a.length - 1 + b.length - 1)
        }
    }
}

/** 递归扫描目录，逐个产出匹配结果，界面用 collect 实时刷新列表 */
class MediaScanner(
    private val storage: StorageProvider,
    private val matcher: MediaMatcher,
    private val minVideoSize: Long = 30L * 1024 * 1024,   // 小于 30MB 视为样片/广告
) {
    fun scan(root: String, maxDepth: Int = 6): Flow<MatchResult> = flow {
        fun isVideo(f: RemoteFile) = !f.isDirectory &&
            f.name.substringAfterLast('.', "").lowercase() in MediaNameParser.VIDEO_EXTENSIONS &&
            f.size !in 1 until minVideoSize && !f.name.contains("sample", ignoreCase = true)

        suspend fun walk(dir: String, ancestors: List<String>, depth: Int) {
            val list = storage.list(dir)
            val videos = list.count(::isVideo)      // 同一文件夹里有几个视频：多个时更像剧集
            for (f in list) {
                if (storage.minRequestIntervalMs > 0) delay(storage.minRequestIntervalMs)
                if (f.isDirectory) {
                    if (depth < maxDepth && !isJunkDir(f.name)) walk(f.path, ancestors + f.name, depth + 1)
                    continue
                }
                if (!isVideo(f)) continue
                emit(matcher.match(parseWithFolderHint(f.name, ancestors, videos), f))
            }
        }
        // 扫描目录本身的各级文件夹名也算上：直接扫某一季的文件夹时，也能借用上面的剧名和季数
        walk(root, root.split('/').filter { it.isNotEmpty() }, 0)
    }

    private fun isJunkDir(name: String) =
        name.startsWith(".") || name.startsWith("@") || name.startsWith("#") ||
            name.equals("\$RECYCLE.BIN", true) || name.equals("lost+found", true) ||
            name.equals("extras", true) || name.equals("featurettes", true)

    companion object {
        private val BARE_SE = Regex("""(?i)^(S\d{1,2}[ ._-]?E\d{1,4}|EP?\d{1,4}(?!\d)|第\s*\d+\s*[集话話])""")
        private val BARE_NUMBER = Regex("""^\d{1,4}\.[A-Za-z0-9]+$""")

        /** 季文件夹：以季标记开头，后面可以带年份、集数说明等，如 "S12(2024)[13集全]"、"Season 2 1080p"、"第二季" */
        private val SEASON_DIR = Regex(
            """(?i)^(season\s*\d+|s\d{1,2}|第\s*[0-9一二三四五六七八九十]+\s*季|specials)(?![a-z0-9])""")

        /** 文件名里自己写了季数（没写的话解析器默认第 1 季，应以季文件夹为准） */
        private val EXPLICIT_SEASON = Regex(
            """(?i)((?<![a-z0-9])s\d{1,2}[ ._-]?e|season[ ._-]?\d|(?<![0-9])\d{1,2}x\d{2,3}(?![0-9])|第\s*[0-9一二三四五六七八九十]+\s*季)""")

        /** 分类文件夹：说明下面是剧集 / 电影，本身不是片名 */
        private val TV_CATEGORY = Regex(
            """(?i)(电视剧|剧集|连续剧|电视|美剧|日剧|韩剧|英剧|泰剧|国产剧|国剧|港剧|台剧|大陆剧|动漫|动画|番剧|新番|tv|tv ?shows?|series|shows|anime)""")
        private val MOVIE_CATEGORY = Regex("""(?i)(电影|影片|华语电影|外语电影|movies?|films?)""")
        /** 常见的上层目录名，既不是分类也不是片名 */
        private val GENERIC_DIR = Regex(
            """(?i)(影视|视频|媒体|媒体库|下载|downloads?|media|videos?|share|public|data|volume\d*|mnt|storage|emulated|sdcard|\d+)""")
        /** 没有信息量的文件名，电影这时用文件夹名 */
        private val GENERIC_FILE = Regex("""(?i)(movie|video|film|main|feature|正片|视频|\d+)""")
        /** 剧集文件名末尾的集数：「爱情怎么翻译 07」「Show_12」 */
        private val TRAILING_EPISODE = Regex("""^(.*?\D)[ ._\-](\d{1,3})$""")

        private fun isSeasonDir(name: String) = SEASON_DIR.containsMatchIn(name.trim())
        private fun isCategory(name: String) = name.trim().let { TV_CATEGORY.matches(it) || MOVIE_CATEGORY.matches(it) }
        private fun isGeneric(name: String) = GENERIC_DIR.matches(name.trim())

        /**
         * 结合所在文件夹解析：
         * - 文件名只有 "S02E03.mkv" / "E02.mp4" / "03.mp4" 时借用剧名文件夹当片名，季数以季文件夹为准（除非文件名自己写了季）；
         *   纯数字文件名在季文件夹、「电视剧」等分类下、或同一文件夹有多个视频时当集数
         * - 「电视剧」分类或季文件夹里，文件名末尾的数字当集数
         * - 电影文件名没有信息（movie.mkv、01.mp4）或没写年份而文件夹写了时，用文件夹名
         * 跳过季文件夹、「电视剧」「电影」等分类文件夹和 downloads 这类通用目录。
         */
        internal fun parseWithFolderHint(fileName: String, ancestors: List<String>, videosInFolder: Int = 1): ParsedMedia {
            val seasonDir = ancestors.lastOrNull()?.takeIf(::isSeasonDir)
            val seasonFromDir = seasonDir?.let(::seasonNumber)
            val showFolder = ancestors.lastOrNull { !isSeasonDir(it) && !isCategory(it) && !isGeneric(it) }
            // 离文件最近的分类文件夹说了算
            val category = ancestors.lastOrNull(::isCategory)?.trim()
            val tvCategory = category != null && TV_CATEGORY.matches(category)
            val movieCategory = category != null && !tvCategory
            val tvContext = !movieCategory && (seasonDir != null || tvCategory || videosInFolder >= 2)
            val ext = fileName.substringAfterLast('.', "").lowercase()

            val bareSe = BARE_SE.containsMatchIn(fileName)
            val bareNumber = tvContext && BARE_NUMBER.matches(fileName)
            if ((bareSe || bareNumber) && showFolder != null) {
                // 纯数字文件名拼上去会混进片名（「暗芝居 03」），只解析剧名文件夹
                val parsed = if (bareNumber) MediaNameParser.parse(showFolder).copy(rawName = fileName, extension = ext)
                else MediaNameParser.parse("$showFolder.$fileName")
                return when {
                    bareNumber -> parsed.copy(season = seasonFromDir ?: 1, episode = Regex("""\d+""").find(fileName)!!.value.toInt())
                    seasonFromDir != null && !EXPLICIT_SEASON.containsMatchIn(fileName) -> parsed.copy(season = seasonFromDir)
                    else -> parsed
                }
            }

            val parsed = MediaNameParser.parse(fileName)
            if (parsed.isEpisode) {
                return if (seasonFromDir != null && !EXPLICIT_SEASON.containsMatchIn(fileName)) parsed.copy(season = seasonFromDir) else parsed
            }

            // 剧集分类 / 季文件夹里：「爱情怎么翻译 07.mp4」
            val base = fileName.substringBeforeLast('.')
            if (tvCategory || seasonDir != null) TRAILING_EPISODE.find(base.trim())?.let { m ->
                val title = MediaNameParser.parse(m.groupValues[1]).title
                if (title.isNotEmpty()) return parsed.copy(title = title, season = seasonFromDir ?: 1, episode = m.groupValues[2].toInt())
            }

            // 电影：独占文件夹（只有这一个视频）且文件名信息不足时，用文件夹名
            val folder = ancestors.lastOrNull()
            if (!tvCategory && videosInFolder <= 1 && folder != null && !isSeasonDir(folder) && !isCategory(folder) && !isGeneric(folder)) {
                val fromFolder = MediaNameParser.parse(folder)
                val weakName = GENERIC_FILE.matches(parsed.title.trim()) || (parsed.year == null && fromFolder.year != null)
                if (weakName && fromFolder.title.isNotEmpty() && !fromFolder.isEpisode) return fromFolder.copy(
                    rawName = fileName, extension = parsed.extension,
                    resolution = parsed.resolution ?: fromFolder.resolution, source = parsed.source ?: fromFolder.source,
                    videoCodec = parsed.videoCodec ?: fromFolder.videoCodec, audioCodec = parsed.audioCodec ?: fromFolder.audioCodec,
                    releaseGroup = parsed.releaseGroup ?: fromFolder.releaseGroup,
                )
            }
            return parsed
        }

        /** 只看开头的季标记，免得 "第二季 1080p" 里的 1080 被当成季数 */
        private fun seasonNumber(dir: String): Int? {
            val mark = SEASON_DIR.find(dir.trim())?.value ?: return null
            Regex("""\d+""").find(mark)?.let { return it.value.toInt() }
            if (mark.contains("special", true)) return 0
            val cn = Regex("""第\s*([一二三四五六七八九十]+)\s*季""").find(mark)?.groupValues?.get(1) ?: return null
            val digits = "一二三四五六七八九"
            return when {
                cn == "十" -> 10
                cn.startsWith("十") -> 10 + digits.indexOf(cn[1]) + 1
                cn.contains("十") -> (digits.indexOf(cn[0]) + 1) * 10 + (cn.getOrNull(2)?.let { digits.indexOf(it) + 1 } ?: 0)
                else -> digits.indexOf(cn[0]) + 1
            }
        }
    }
}
