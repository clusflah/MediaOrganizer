package com.example.mediaorganizer.metadata.extra

import com.example.mediaorganizer.metadata.MetaSource
import com.example.mediaorganizer.metadata.TmdbMovie
import com.example.mediaorganizer.metadata.TmdbTvShow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** 刮削来源设置（TMDB 之外的补充来源） */
data class ExtraConfig(
    val douban: Boolean = false,
    val bangumi: Boolean = false,
    val tvdb: Boolean = false,
    val fanart: Boolean = false,
    val preferDouban: Boolean = true,          // 简介、评分优先用豆瓣（动画开了 Bangumi 时优先 Bangumi）
    val tvdbApiKey: String = "",
    val tvdbPin: String = "",
    val fanartApiKey: String = "",
)

/** Fanart.tv 的高清图，已选好语言（中文 > 英文 > 无文字） */
data class FanartArt(
    val logo: String? = null,
    val clearart: String? = null,
    val banner: String? = null,
    val landscape: String? = null,
    val disc: String? = null,
)

/** 作品级的补充信息 */
data class ExtraInfo(
    val doubanId: String? = null,
    val doubanRating: Double? = null,
    val doubanVotes: Int? = null,
    val doubanPlot: String? = null,
    val bangumiId: String? = null,
    val bangumiRating: Double? = null,
    val bangumiVotes: Int? = null,
    val bangumiPlot: String? = null,
    val tvdbId: String? = null,
    val art: FanartArt? = null,
    val preferDouban: Boolean = true,
) {
    /** 按偏好选简介：Bangumi（动画）> 豆瓣 > null（用 TMDB） */
    val plot: String? get() = if (!preferDouban) null else bangumiPlot?.takeIf { it.isNotBlank() } ?: doubanPlot?.takeIf { it.isNotBlank() }

    /** 首选评分来源名和分数；null 用 TMDB */
    val preferredRating: Pair<String, Double>? get() = if (!preferDouban) null else
        bangumiRating?.takeIf { it > 0 }?.let { "bangumi" to it } ?: doubanRating?.takeIf { it > 0 }?.let { "douban" to it }
}

/**
 * 别的站点里查到的同一作品：TMDB 不搜别名（如「怪化猫」在 TMDB 叫「物怪」），
 * 手动搜索时用它的原名 / ID 回 TMDB 找
 */
data class AltTitle(
    val source: String,            // 豆瓣 / TheTVDB
    val title: String,
    val original: String?,
    val year: Int?,
    val tv: Boolean?,              // null = 不确定
    val tvdbId: Int? = null,
    val tmdbId: Int? = null,
)

/** 单集的补充信息（TheTVDB） */
data class EpisodeExtra(val name: String?, val plot: String?)

/** 刮削时查询补充来源；失败一律忽略，不影响 TMDB 的刮削 */
interface MetadataExtras {
    suspend fun forMovie(m: TmdbMovie): ExtraInfo
    suspend fun forShow(s: TmdbTvShow): ExtraInfo
    suspend fun forEpisode(s: TmdbTvShow, season: Int, episode: Int): EpisodeExtra?
}

class OnlineExtras(private val c: ExtraConfig, client: OkHttpClient? = null) : MetadataExtras {

    private val http = (client ?: OkHttpClient()).newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val movies = ConcurrentHashMap<String, ExtraInfo>()
    private val shows = ConcurrentHashMap<String, ExtraInfo>()
    private val tvdbEpisodes = ConcurrentHashMap<String, List<JSONObject>>()

    // 豆瓣没有官方接口，请求太密会被限流：串行 + 间隔
    private val doubanLock = Mutex()
    private var lastDouban = 0L

    // 主数据源本身是豆瓣 / Bangumi 时不再去那边补查
    override suspend fun forMovie(m: TmdbMovie): ExtraInfo = movies["${m.source}/${m.id}"] ?: run {
        val anime = isAnime(m.genres)
        val douban = if (c.douban && m.source != MetaSource.DOUBAN)
            runCatching { douban(m.title, m.originalTitle, m.year, tv = false) }.getOrNull() else null
        val bgm = if (c.bangumi && anime && m.source != MetaSource.BANGUMI)
            runCatching { bangumi(m.title, m.originalTitle, m.year) }.getOrNull() else null
        // Fanart.tv 电影认 TMDB ID 或 IMDb ID
        val fanartId = if (m.source == MetaSource.TMDB) m.id.toString() else m.imdbId
        val art = if (c.fanart && c.fanartApiKey.isNotBlank() && fanartId != null)
            runCatching { fanart("movies/$fanartId", movie = true) }.getOrNull() else null
        merge(douban, bgm, null, art).also { movies["${m.source}/${m.id}"] = it }
    }

    override suspend fun forShow(s: TmdbTvShow): ExtraInfo = shows["${s.source}/${s.id}"] ?: run {
        val anime = isAnime(s.genres)
        val douban = if (c.douban && s.source != MetaSource.DOUBAN)
            runCatching { douban(s.name, s.originalName, s.firstAirYear, tv = true) }.getOrNull() else null
        val bgm = if (c.bangumi && anime && s.source != MetaSource.BANGUMI)
            runCatching { bangumi(s.name, s.originalName, s.firstAirYear) }.getOrNull() else null
        val art = if (c.fanart && c.fanartApiKey.isNotBlank() && s.tvdbId != null)
            runCatching { fanart("tv/${s.tvdbId}", movie = false) }.getOrNull() else null
        merge(douban, bgm, s.tvdbId?.toString(), art).also { shows["${s.source}/${s.id}"] = it }
    }

    override suspend fun forEpisode(s: TmdbTvShow, season: Int, episode: Int): EpisodeExtra? {
        if (!c.tvdb || c.tvdbApiKey.isBlank() || s.tvdbId == null) return null
        return runCatching {
            val eps = tvdbEpisodes[ "${s.tvdbId}/$season" ] ?: tvdbSeason(s.tvdbId, season).also { tvdbEpisodes["${s.tvdbId}/$season"] = it }
            eps.firstOrNull { it.optInt("number") == episode }?.let {
                EpisodeExtra(it.optString("name").ifBlank { null }, it.optString("overview").ifBlank { null })
            }
        }.getOrNull()
    }

    private fun merge(douban: JSONObject?, bgm: JSONObject?, tvdbId: String?, art: FanartArt?) = ExtraInfo(
        doubanId = douban?.optString("id")?.ifBlank { null },
        doubanRating = douban?.optJSONObject("rating")?.optDouble("value")?.takeIf { !it.isNaN() },
        doubanVotes = douban?.optJSONObject("rating")?.optInt("count"),
        doubanPlot = douban?.optString("intro")?.trim()?.ifBlank { null },
        bangumiId = bgm?.optString("id")?.ifBlank { null },
        bangumiRating = bgm?.optJSONObject("rating")?.optDouble("score")?.takeIf { !it.isNaN() },
        bangumiVotes = bgm?.optJSONObject("rating")?.optInt("total"),
        bangumiPlot = bgm?.optString("summary")?.trim()?.ifBlank { null },
        tvdbId = tvdbId,
        art = art,
        preferDouban = c.preferDouban,
    )

    private fun isAnime(genres: List<String>) = genres.any { it == "动画" || it.equals("Animation", true) }

    private suspend fun getJson(url: String, vararg headers: Pair<String, String>): String = withContext(Dispatchers.IO) {
        val r = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        http.newCall(r).execute().use {
            if (!it.isSuccessful) throw IllegalStateException("HTTP ${it.code}")
            it.body!!.string()
        }
    }

    // ---------- 豆瓣 ----------

    /** 先用联想搜索按片名 + 年份找到条目，再取移动端详情（评分、简介） */
    private suspend fun douban(title: String, original: String?, year: Int?, tv: Boolean): JSONObject? = doubanLock.withLock {
        suspend fun pace() {
            val wait = 1200 - (System.currentTimeMillis() - lastDouban)
            if (wait > 0) delay(wait)
            lastDouban = System.currentTimeMillis()
        }
        pace()
        val list = JSONArray(getJson(
            "https://movie.douban.com/j/subject_suggest?q=" + URLEncoder.encode(title, "UTF-8"),
            "User-Agent" to DESKTOP_UA,
        ))
        val cands = (0 until list.length()).map { list.getJSONObject(it) }.filter { it.optString("type") == "movie" }
        val best = cands.minByOrNull { o ->
            val y = o.optString("year").toIntOrNull()
            val yearPenalty = if (year == null || y == null) 1 else abs(year - y).coerceAtMost(5) * 2
            val titlePenalty = if (o.optString("title") == title || o.optString("sub_title") == original) 0 else 3
            yearPenalty + titlePenalty
        } ?: return@withLock null
        val y = best.optString("year").toIntOrNull()
        if (year != null && y != null && abs(year - y) > 1) return@withLock null      // 年份差太多，宁可不要
        pace()
        // 联想结果不区分电影和剧集，按预期类型先试，不行再换另一个
        val id = best.getString("id")
        val detail = listOf(if (tv) "tv" else "movie", if (tv) "movie" else "tv").firstNotNullOfOrNull { kind ->
            runCatching {
                JSONObject(getJson("https://m.douban.com/rexxar/api/v2/$kind/$id", "User-Agent" to MOBILE_UA, "Referer" to "https://m.douban.com/"))
            }.getOrNull()
        } ?: return@withLock null
        detail.put("id", id)
    }

    // ---------- 别名搜索（手动搜索用） ----------

    /** 豆瓣联想搜索：不用登录也不用 key，中文别名最全 */
    suspend fun doubanAliases(query: String): List<AltTitle> {
        val list = doubanLock.withLock {
            val wait = 1200 - (System.currentTimeMillis() - lastDouban)
            if (wait > 0) delay(wait)
            lastDouban = System.currentTimeMillis()
            JSONArray(getJson(
                "https://movie.douban.com/j/subject_suggest?q=" + URLEncoder.encode(query, "UTF-8"),
                "User-Agent" to DESKTOP_UA,
            ))
        }
        return (0 until list.length()).map { list.getJSONObject(it) }.filter { it.optString("type") == "movie" }.map {
            AltTitle(
                source = "豆瓣",
                title = it.optString("title"),
                original = it.optString("sub_title").ifBlank { null },
                year = it.optString("year").toIntOrNull(),
                tv = it.optString("episode").isNotBlank(),      // 有集数的是剧集
            )
        }
    }

    /** TheTVDB 搜索（含别名和各语言译名）；没填 API key 时返回空 */
    suspend fun tvdbAliases(query: String, tv: Boolean): List<AltTitle> {
        if (c.tvdbApiKey.isBlank()) return emptyList()
        val url = "$TVDB/search".toHttpUrl().newBuilder()
            .addQueryParameter("query", query).addQueryParameter("type", if (tv) "series" else "movie")
            .addQueryParameter("limit", "10").build().toString()
        val data = JSONObject(getJson(url, "Authorization" to "Bearer ${tvdbLogin()}")).optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).map { data.getJSONObject(it) }.map { o ->
            val remote = o.optJSONArray("remote_ids")
            val tmdb = (0 until (remote?.length() ?: 0)).map { remote!!.getJSONObject(it) }
                .firstOrNull { it.optString("sourceName").contains("TheMovieDB", true) }?.optString("id")?.toIntOrNull()
            AltTitle(
                source = "TheTVDB",
                title = o.optString("name"),
                original = o.optString("name"),
                year = o.optString("year").toIntOrNull(),
                tv = tv,
                tvdbId = o.optString("tvdb_id").toIntOrNull(),
                tmdbId = tmdb,
            )
        }
    }

    // ---------- Bangumi ----------

    private suspend fun bangumi(title: String, original: String?, year: Int?): JSONObject? {
        for (q in listOfNotNull(original?.takeIf { it.isNotBlank() }, title).distinct()) {
            val j = JSONObject(getJson(
                "https://api.bgm.tv/search/subject/" + URLEncoder.encode(q, "UTF-8").replace("+", "%20") +
                    "?type=2&responseGroup=small&max_results=10",
                "User-Agent" to BGM_UA,
            ))
            val list = j.optJSONArray("list") ?: continue
            val cands = (0 until list.length()).map { list.getJSONObject(it) }
            val best = cands.firstOrNull { o ->
                val y = o.optString("air_date").take(4).toIntOrNull()
                (o.optString("name_cn") == title || o.optString("name") == original || o.optString("name") == title) &&
                    (year == null || y == null || abs(year - y) <= 1)
            } ?: continue
            return JSONObject(getJson("https://api.bgm.tv/subject/${best.getInt("id")}?responseGroup=medium", "User-Agent" to BGM_UA))
        }
        return null
    }

    // ---------- Fanart.tv ----------

    private suspend fun fanart(path: String, movie: Boolean): FanartArt {
        val j = JSONObject(getJson("https://webservice.fanart.tv/v3/$path?api_key=" + c.fanartApiKey.trim()))
        fun pick(vararg keys: String): String? {
            val all = keys.flatMap { k -> j.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList() }
            fun rank(o: JSONObject) = when (o.optString("lang")) { "zh" -> 0; "en" -> 1; "00", "" -> 2; else -> 3 }
            return all.sortedWith(compareBy<JSONObject>({ rank(it) }, { -(it.optString("likes").toIntOrNull() ?: 0) }))
                .firstOrNull()?.optString("url")?.ifBlank { null }
        }
        return if (movie) FanartArt(
            logo = pick("hdmovielogo", "movielogo"),
            clearart = pick("hdmovieclearart", "movieart"),
            banner = pick("moviebanner"),
            landscape = pick("moviethumb"),
            disc = pick("moviedisc"),
        ) else FanartArt(
            logo = pick("hdtvlogo", "clearlogo"),
            clearart = pick("hdclearart", "clearart"),
            banner = pick("tvbanner"),
            landscape = pick("tvthumb"),
        )
    }

    // ---------- TheTVDB v4 ----------

    private var tvdbToken: String? = null

    private suspend fun tvdbLogin(): String {
        tvdbToken?.let { return it }
        val body = JSONObject().put("apikey", c.tvdbApiKey.trim()).apply { if (c.tvdbPin.isNotBlank()) put("pin", c.tvdbPin.trim()) }
        val text = withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url("$TVDB/login").post(body.toString().toRequestBody("application/json".toMediaType())).build())
                .execute().use {
                    if (!it.isSuccessful) throw IllegalStateException("TheTVDB 登录失败 HTTP ${it.code}")
                    it.body!!.string()
                }
        }
        return JSONObject(text).getJSONObject("data").getString("token").also { tvdbToken = it }
    }

    /** 中文翻译的整季剧集（标题、简介） */
    private suspend fun tvdbSeason(seriesId: Int, season: Int): List<JSONObject> {
        val token = tvdbLogin()
        val out = mutableListOf<JSONObject>()
        var page = 0
        while (page < 10) {
            val url = "$TVDB/series/$seriesId/episodes/default/zho".toHttpUrl().newBuilder()
                .addQueryParameter("page", page.toString()).addQueryParameter("season", season.toString()).build().toString()
            val j = JSONObject(getJson(url, "Authorization" to "Bearer $token"))
            val eps = j.optJSONObject("data")?.optJSONArray("episodes") ?: break
            for (i in 0 until eps.length()) eps.getJSONObject(i).takeIf { it.optInt("seasonNumber") == season }?.let { out += it }
            if (j.optJSONObject("links")?.isNull("next") != false) break
            page++
        }
        return out
    }

    /** 设置页的「测试」：逐个检查已开启的来源，返回 来源 -> 结果 */
    suspend fun test(): List<Pair<String, Result<String>>> = buildList {
        if (c.douban) add("豆瓣" to runCatching {
            val d = douban("盗梦空间", "Inception", 2010, tv = false) ?: error("没找到测试条目")
            "盗梦空间 豆瓣 ${d.optJSONObject("rating")?.optDouble("value")}"
        })
        if (c.bangumi) add("Bangumi" to runCatching {
            val b = bangumi("进击的巨人", "進撃の巨人", 2013) ?: error("没找到测试条目")
            "进击的巨人 Bangumi ${b.optJSONObject("rating")?.optDouble("score")}"
        })
        if (c.tvdb) add("TheTVDB" to runCatching {
            if (c.tvdbApiKey.isBlank()) error("未填写 API key")
            tvdbLogin(); "API key 有效"
        })
        if (c.fanart) add("Fanart.tv" to runCatching {
            if (c.fanartApiKey.isBlank()) error("未填写 API key")
            val a = fanart("movies/27205", movie = true)
            if (a.logo == null) error("没拿到图片") else "盗梦空间 logo 可用"
        })
    }

    companion object {
        private const val TVDB = "https://api4.thetvdb.com/v4"
        private const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36"
        private const val MOBILE_UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
        private const val BGM_UA = "clusflah/MediaOrganizer (https://github.com/clusflah/MediaOrganizer)"
    }
}
