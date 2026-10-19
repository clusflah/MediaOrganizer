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

class MediaMatcher(private val tmdb: TmdbClient) {

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
        val r = if (isTv) tmdb.searchTv(query, year) else tmdb.searchMovie(query, year)
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
        suspend fun walk(dir: String, ancestors: List<String>, depth: Int) {
            for (f in storage.list(dir)) {
                if (storage.minRequestIntervalMs > 0) delay(storage.minRequestIntervalMs)
                if (f.isDirectory) {
                    if (depth < maxDepth && !isJunkDir(f.name)) walk(f.path, ancestors + f.name, depth + 1)
                    continue
                }
                val ext = f.name.substringAfterLast('.', "").lowercase()
                if (ext !in MediaNameParser.VIDEO_EXTENSIONS) continue
                if (f.size in 1 until minVideoSize || f.name.contains("sample", ignoreCase = true)) continue

                emit(matcher.match(parseWithFolderHint(f.name, ancestors), f))
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

        private fun isSeasonDir(name: String) = SEASON_DIR.containsMatchIn(name.trim())

        /**
         * 文件名只有 "S02E03.mkv" / "E02.mp4" / "03.mp4" 这种时，借用上层文件夹名当片名；
         * 跳过 "Season 2"、"S12(2024)"、"第二季" 这类季文件夹，季数以季文件夹为准（除非文件名自己写了季）。
         */
        internal fun parseWithFolderHint(fileName: String, ancestors: List<String>): ParsedMedia {
            val seasonDir = ancestors.lastOrNull()?.takeIf(::isSeasonDir)
            val seasonFromDir = seasonDir?.let(::seasonNumber)
            val showFolder = ancestors.lastOrNull { !isSeasonDir(it) }
            val bareSe = BARE_SE.containsMatchIn(fileName)
            val bareNumber = seasonDir != null && BARE_NUMBER.matches(fileName)   // 纯数字只在季文件夹里才当集数

            if ((bareSe || bareNumber) && showFolder != null) {
                // 纯数字文件名拼上去会混进片名（「暗芝居 03」），只解析剧名文件夹
                val parsed = if (bareNumber) MediaNameParser.parse(showFolder).copy(rawName = fileName, extension = fileName.substringAfterLast('.').lowercase())
                else MediaNameParser.parse("$showFolder.$fileName")
                return when {
                    bareNumber -> parsed.copy(season = seasonFromDir ?: 1, episode = Regex("""\d+""").find(fileName)!!.value.toInt())
                    seasonFromDir != null && !EXPLICIT_SEASON.containsMatchIn(fileName) -> parsed.copy(season = seasonFromDir)
                    else -> parsed
                }
            }
            val parsed = MediaNameParser.parse(fileName)
            return if (parsed.isEpisode && seasonFromDir != null && !EXPLICIT_SEASON.containsMatchIn(fileName))
                parsed.copy(season = seasonFromDir) else parsed
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
