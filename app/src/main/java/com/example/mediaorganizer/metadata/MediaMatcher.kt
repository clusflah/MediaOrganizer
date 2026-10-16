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

    suspend fun match(parsed: ParsedMedia, file: RemoteFile? = null): MatchResult {
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

    companion object {
        const val AUTO_ACCEPT = 0.75

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
        walk(root, emptyList(), 0)
    }

    /**
     * 文件名只有 "S02E03.mkv" / "03.mp4" 这种时，借用上层文件夹名，
     * 跳过 "Season 2"、"S02"、"第二季" 这类季文件夹。
     */
    private fun parseWithFolderHint(fileName: String, ancestors: List<String>): ParsedMedia {
        val seasonDir = ancestors.lastOrNull()?.takeIf { SEASON_DIR.matches(it.trim()) }
        val bareSe = BARE_SE.containsMatchIn(fileName)
        val bareNumber = seasonDir != null && BARE_NUMBER.matches(fileName)   // 纯数字只在季文件夹里才当集数
        if (!bareSe && !bareNumber) return MediaNameParser.parse(fileName)

        val showFolder = ancestors.lastOrNull { !SEASON_DIR.matches(it.trim()) }
            ?: return MediaNameParser.parse(fileName)
        val parsed = MediaNameParser.parse("$showFolder.$fileName")
        val seasonFromDir = seasonDir?.let(::seasonNumber)

        return when {
            bareNumber -> parsed.copy(
                season = seasonFromDir ?: 1,
                episode = Regex("""\d+""").find(fileName)!!.value.toInt(),
            )
            parsed.season == null && seasonFromDir != null -> parsed.copy(season = seasonFromDir)
            else -> parsed
        }
    }

    private fun seasonNumber(dir: String): Int? {
        Regex("""\d+""").find(dir)?.let { return it.value.toInt() }
        if (dir.contains("special", true)) return 0
        val cn = Regex("""第\s*([一二三四五六七八九十]+)\s*季""").find(dir)?.groupValues?.get(1) ?: return null
        val digits = "一二三四五六七八九"
        return when {
            cn == "十" -> 10
            cn.startsWith("十") -> 10 + digits.indexOf(cn[1]) + 1
            cn.contains("十") -> (digits.indexOf(cn[0]) + 1) * 10 + (cn.getOrNull(2)?.let { digits.indexOf(it) + 1 } ?: 0)
            else -> digits.indexOf(cn[0]) + 1
        }
    }

    private fun isJunkDir(name: String) =
        name.startsWith(".") || name.startsWith("@") || name.startsWith("#") ||
            name.equals("\$RECYCLE.BIN", true) || name.equals("lost+found", true) ||
            name.equals("extras", true) || name.equals("featurettes", true)

    companion object {
        private val BARE_SE = Regex("""(?i)^(S\d{1,2}[ ._-]?E\d{1,4}|EP?\d{1,4}(?!\d)|第\s*\d+\s*[集话話])""")
        private val BARE_NUMBER = Regex("""^\d{1,4}\.[A-Za-z0-9]+$""")
        private val SEASON_DIR = Regex("""(?i)(season\s*\d+|s\d{1,2}|第\s*[0-9一二三四五六七八九十]+\s*季|specials)""")
    }
}
