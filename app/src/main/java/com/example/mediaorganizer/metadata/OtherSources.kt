package com.example.mediaorganizer.metadata

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
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * TMDB 之外、可以直接当主数据源的站点。结果套用 TMDB 的数据结构（source 标明来源），
 * 命名、刮削不用区分；图片路径是完整 URL。
 * 豆瓣和 Bangumi 每一季是单独的条目，所以 season() 不管季号，返回这个条目的全部集。
 */
interface MetadataSource {
    val source: MetaSource
    suspend fun search(query: String, tv: Boolean): List<TmdbSearchResult>
    suspend fun byId(type: MediaType, id: Int): TmdbSearchResult
    suspend fun movie(id: Int): TmdbMovie
    suspend fun tv(id: Int): TmdbTvShow
    suspend fun season(id: Int, season: Int): List<TmdbEpisode>
}

private fun JSONObject.str(key: String): String? =
    if (isNull(key)) null else optString(key).trim().takeIf { it.isNotEmpty() && it != "null" }

private fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

private fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).trim().ifEmpty { null } }

private fun yearOf(s: String?) = s?.let { Regex("""\d{4}""").find(it)?.value?.toIntOrNull() }

private abstract class HttpSource(client: OkHttpClient?) : MetadataSource {
    protected val http = (client ?: OkHttpClient()).newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    protected suspend fun get(url: String, vararg headers: Pair<String, String>): String = call(
        Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build())

    protected suspend fun post(url: String, body: JSONObject, vararg headers: Pair<String, String>): String = call(
        Request.Builder().url(url).post(body.toString().toRequestBody("application/json".toMediaType()))
            .apply { headers.forEach { (k, v) -> header(k, v) } }.build())

    private suspend fun call(r: Request): String = withContext(Dispatchers.IO) {
        http.newCall(r).execute().use {
            when {
                it.isSuccessful -> it.body!!.string()
                it.code == 404 -> throw IOException("${source.label} 没有这个条目")
                else -> throw IOException("${source.label} 请求失败 HTTP ${it.code}")
            }
        }
    }

    override suspend fun byId(type: MediaType, id: Int): TmdbSearchResult =
        if (type == MediaType.TV) tv(id).toResult() else movie(id).toResult()

    protected fun TmdbMovie.toResult() = TmdbSearchResult(id, MediaType.MOVIE, title, originalTitle, year, overview,
        posterPath, backdropPath, popularity = 0.0, source = source)

    protected fun TmdbTvShow.toResult() = TmdbSearchResult(id, MediaType.TV, name, originalName, firstAirYear, overview,
        posterPath, backdropPath, popularity = 0.0, source = source)
}

// ============================ 豆瓣 ============================

/** 豆瓣：联想搜索 + 移动端接口，不用登录。没有分集数据 */
class DoubanSource(client: OkHttpClient? = null) : MetadataSource by DoubanImpl(client)

private class DoubanImpl(client: OkHttpClient?) : HttpSource(client) {
    override val source = MetaSource.DOUBAN

    // 豆瓣没有官方接口，请求太密会被限流：串行 + 间隔
    private val lock = Mutex()
    private var last = 0L
    private val details = java.util.concurrent.ConcurrentHashMap<Int, JSONObject>()

    private suspend fun <T> paced(block: suspend () -> T): T = lock.withLock {
        val wait = 1200 - (System.currentTimeMillis() - last)
        if (wait > 0) delay(wait)
        try { block() } finally { last = System.currentTimeMillis() }
    }

    /**
     * 移动端搜索：和豆瓣 App「影视」一栏一样全（暗芝居能搜到 17 季）；
     * 失败时退回联想搜索（只有前几条）
     */
    override suspend fun search(query: String, tv: Boolean): List<TmdbSearchResult> {
        val items = runCatching {
            val q = URLEncoder.encode(query, "UTF-8")
            JSONObject(paced {
                get("https://m.douban.com/rexxar/api/v2/search/movie?q=$q&count=50", "User-Agent" to MOBILE_UA, "Referer" to "https://m.douban.com/")
            }).optJSONArray("items").objects()
        }.getOrNull()?.filter { it.optString("target_type") == "tv" || it.optString("target_type") == "movie" }
            ?: return suggest(query, tv)
        // 选的类型排前面，但都列出来，类型以选的为准
        return items.sortedBy { (it.optString("target_type") == "tv") != tv }.mapNotNull { o ->
            val t = o.optJSONObject("target") ?: return@mapNotNull null
            val id = t.optString("id").toIntOrNull() ?: return@mapNotNull null
            TmdbSearchResult(
                id = id, type = if (tv) MediaType.TV else MediaType.MOVIE,
                title = t.str("title") ?: "", originalTitle = null,
                year = t.optString("year").toIntOrNull(),
                overview = listOfNotNull(
                    t.optJSONObject("rating")?.optDouble("value", 0.0)?.takeIf { it > 0 }?.let { "豆瓣 $it" },
                    t.str("card_subtitle"),
                ).joinToString(" · ").ifEmpty { null },
                posterPath = t.str("cover_url"), backdropPath = null, popularity = 0.0, source = source,
            )
        }
    }

    private suspend fun suggest(query: String, tv: Boolean): List<TmdbSearchResult> {
        val list = JSONArray(paced {
            get("https://movie.douban.com/j/subject_suggest?q=" + URLEncoder.encode(query, "UTF-8"), "User-Agent" to DESKTOP_UA)
        }).objects().filter { it.optString("type") == "movie" }
        // 联想结果不分电影剧集（有集数的是剧集）；按选的类型排前面，但都列出来，类型以选的为准
        return list.sortedBy { (it.optString("episode").isNotBlank()) != tv }.mapNotNull { o ->
            val id = o.optString("id").toIntOrNull() ?: return@mapNotNull null
            TmdbSearchResult(
                id = id, type = if (tv) MediaType.TV else MediaType.MOVIE,
                title = o.str("title") ?: "", originalTitle = o.str("sub_title"),
                year = o.optString("year").toIntOrNull(),
                overview = o.str("episode")?.let { "共 $it 集" },
                posterPath = o.str("img"), backdropPath = null, popularity = 0.0, source = source,
            )
        }
    }

    /** 移动端详情：剧集和电影是两个接口，不确定是哪种就都试 */
    private suspend fun detail(id: Int, tv: Boolean): JSONObject = details[id] ?: run {
        var err: Exception? = null
        for (kind in listOf(if (tv) "tv" else "movie", if (tv) "movie" else "tv")) {
            try {
                val j = JSONObject(paced {
                    get("https://m.douban.com/rexxar/api/v2/$kind/$id", "User-Agent" to MOBILE_UA, "Referer" to "https://m.douban.com/")
                })
                details[id] = j
                return j
            } catch (e: Exception) { err = e }
        }
        throw err ?: IOException("豆瓣没有这个条目")
    }

    private fun people(a: JSONArray?, role: String? = null) = a.objects().mapIndexed { i, p ->
        TmdbPerson(0, p.str("name") ?: "", role ?: p.optJSONArray("roles").strings().firstOrNull(),
            p.optJSONObject("avatar")?.str("large"), order = i)
    }

    override suspend fun movie(id: Int): TmdbMovie {
        val j = detail(id, tv = false)
        val date = j.optJSONArray("pubdate").strings().firstOrNull()?.substringBefore('(')
        return TmdbMovie(
            id = id, title = j.str("title") ?: "", originalTitle = j.str("original_title"),
            year = j.optString("year").toIntOrNull() ?: yearOf(date), releaseDate = date,
            overview = j.str("intro"),
            runtime = j.optJSONArray("durations").strings().firstOrNull()?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() },
            genres = j.optJSONArray("genres").strings(), imdbId = null,
            voteAverage = j.optJSONObject("rating")?.optDouble("value", 0.0) ?: 0.0,
            posterPath = j.optJSONObject("pic")?.str("large"), backdropPath = null,
            countries = j.optJSONArray("countries").strings(),
            cast = people(j.optJSONArray("actors")).take(TmdbClient.MAX_CAST),
            directors = people(j.optJSONArray("directors"), "Director"),
            source = source,
        )
    }

    override suspend fun tv(id: Int): TmdbTvShow {
        val j = detail(id, tv = true)
        val date = j.optJSONArray("pubdate").strings().firstOrNull()?.substringBefore('(')
        return TmdbTvShow(
            id = id, name = j.str("title") ?: "", originalName = j.str("original_title"),
            firstAirYear = j.optString("year").toIntOrNull() ?: yearOf(date), firstAirDate = date,
            overview = j.str("intro"), genres = j.optJSONArray("genres").strings(), imdbId = null,
            voteAverage = j.optJSONObject("rating")?.optDouble("value", 0.0) ?: 0.0,
            posterPath = j.optJSONObject("pic")?.str("large"), backdropPath = null,
            seasons = emptyList(),
            countries = j.optJSONArray("countries").strings(),
            creators = people(j.optJSONArray("directors"), "Director"),
            cast = people(j.optJSONArray("actors")).take(TmdbClient.MAX_CAST),
            source = source,
        )
    }

    override suspend fun season(id: Int, season: Int): List<TmdbEpisode> = emptyList()   // 豆瓣没有分集标题
}

// ============================ Bangumi ============================

/** Bangumi 番组计划：动画最全，不用 key */
class BangumiSource(client: OkHttpClient? = null) : MetadataSource by BangumiImpl(client)

private class BangumiImpl(client: OkHttpClient?) : HttpSource(client) {
    override val source = MetaSource.BANGUMI
    private val ua = "User-Agent" to BGM_UA

    private fun JSONObject.title() = str("name_cn") ?: str("name") ?: ""
    private fun JSONObject.image() = optJSONObject("images")?.let { it.str("large") ?: it.str("common") }

    override suspend fun search(query: String, tv: Boolean): List<TmdbSearchResult> {
        val body = JSONObject().put("keyword", query).put("filter", JSONObject().put("type", JSONArray().put(2)))
        val data = JSONObject(post("$API/v0/search/subjects?limit=20", body, ua)).optJSONArray("data").objects()
        // 剧场版排在「电影」里，其余（TV、OVA、WEB）排在「剧集」里；类型以选的为准
        return data.sortedBy { isMovie(it) == tv }.map {
            TmdbSearchResult(
                id = it.getInt("id"), type = if (tv) MediaType.TV else MediaType.MOVIE,
                title = it.title(), originalTitle = it.str("name"), year = yearOf(it.str("date")),
                overview = it.str("summary"), posterPath = it.image(), backdropPath = null,
                popularity = 0.0, source = source,
            )
        }
    }

    private fun isMovie(o: JSONObject) = o.optString("platform").let { it.contains("剧场") || it.contains("电影") }

    private suspend fun subject(id: Int) = JSONObject(get("$API/v0/subjects/$id", ua))

    /** 角色和声优：演员名用声优，角色名用角色 */
    private suspend fun cast(id: Int): List<TmdbPerson> = runCatching {
        JSONArray(get("$API/v0/subjects/$id/characters", ua)).objects().mapIndexedNotNull { i, c ->
            val actor = c.optJSONArray("actors").objects().firstOrNull() ?: return@mapIndexedNotNull null
            TmdbPerson(0, actor.str("name") ?: "", c.str("name"), actor.image(), order = i)
        }.take(TmdbClient.MAX_CAST)
    }.getOrDefault(emptyList())

    private suspend fun staff(id: Int, vararg relations: String): List<TmdbPerson> = runCatching {
        JSONArray(get("$API/v0/subjects/$id/persons", ua)).objects()
            .filter { it.optString("relation") in relations }
            .map { TmdbPerson(0, it.str("name") ?: "", it.optString("relation"), it.image()) }
    }.getOrDefault(emptyList())

    private fun JSONObject.genres() = listOf("动画") + optJSONArray("meta_tags").strings().filter { it != "动画" }.take(5)

    override suspend fun movie(id: Int): TmdbMovie {
        val j = subject(id)
        return TmdbMovie(
            id = id, title = j.title(), originalTitle = j.str("name"), year = yearOf(j.str("date")), releaseDate = j.str("date"),
            overview = j.str("summary"), runtime = null, genres = j.genres(), imdbId = null,
            voteAverage = j.optJSONObject("rating")?.optDouble("score", 0.0) ?: 0.0,
            posterPath = j.image(), backdropPath = null,
            cast = cast(id), directors = staff(id, "导演", "监督"), writers = staff(id, "脚本", "系列构成"),
            source = source,
        )
    }

    override suspend fun tv(id: Int): TmdbTvShow {
        val j = subject(id)
        return TmdbTvShow(
            id = id, name = j.title(), originalName = j.str("name"), firstAirYear = yearOf(j.str("date")), firstAirDate = j.str("date"),
            overview = j.str("summary"), genres = j.genres(), imdbId = null,
            voteAverage = j.optJSONObject("rating")?.optDouble("score", 0.0) ?: 0.0,
            posterPath = j.image(), backdropPath = null, seasons = emptyList(),
            creators = staff(id, "导演", "监督"), cast = cast(id),
            source = source,
        )
    }

    override suspend fun season(id: Int, season: Int): List<TmdbEpisode> {
        val data = JSONObject(get("$API/v0/episodes?subject_id=$id&type=0&limit=200", ua)).optJSONArray("data").objects()
        return data.map {
            // ep 是本条目内的集数，sort 是整部作品的连续编号；优先用 ep
            val n = it.optDouble("ep", Double.NaN).takeIf { d -> !d.isNaN() && d > 0 } ?: it.optDouble("sort", 0.0)
            TmdbEpisode(
                seasonNumber = season, episodeNumber = n.toInt(), name = it.str("name_cn") ?: it.str("name") ?: "",
                overview = it.str("desc"), airDate = it.str("airdate"), stillPath = null, voteAverage = 0.0,
            )
        }
    }

    companion object { private const val API = "https://api.bgm.tv" }
}

// ============================ TheTVDB ============================

/** TheTVDB v4：要在刮削设置里填 API key；中文标题和简介取 zho 翻译 */
class TvdbSource(apiKey: String, pin: String = "", client: OkHttpClient? = null) : MetadataSource by TvdbImpl(apiKey, pin, client)

private class TvdbImpl(private val apiKey: String, private val pin: String, client: OkHttpClient?) : HttpSource(client) {
    override val source = MetaSource.TVDB
    private var token: String? = null

    private suspend fun auth(): Pair<String, String> {
        if (apiKey.isBlank()) throw IOException("没有填 TheTVDB API key（刮削设置 → 补充来源）")
        val t = token ?: JSONObject(post("$API/login",
            JSONObject().put("apikey", apiKey.trim()).apply { if (pin.isNotBlank()) put("pin", pin.trim()) }))
            .getJSONObject("data").getString("token").also { token = it }
        return "Authorization" to "Bearer $t"
    }

    private suspend fun data(path: String): JSONObject = JSONObject(get("$API/$path", auth())).getJSONObject("data")

    private fun img(s: String?) = s?.takeIf { it.isNotBlank() }?.let { if (it.startsWith("http")) it else "https://artworks.thetvdb.com$it" }

    /** 中文名 / 简介：翻译里有 zho 就用，没有用原文 */
    private fun JSONObject.zh(kind: String, field: String): String? =
        optJSONObject("translations")?.optJSONArray(kind).objects().firstOrNull { it.optString("language") == "zho" }?.str(field)

    override suspend fun search(query: String, tv: Boolean): List<TmdbSearchResult> {
        val url = "$API/search".toHttpUrl().newBuilder()
            .addQueryParameter("query", query).addQueryParameter("type", if (tv) "series" else "movie")
            .addQueryParameter("limit", "20").build().toString()
        return JSONObject(get(url, auth())).optJSONArray("data").objects().mapNotNull { o ->
            val id = o.optString("tvdb_id").toIntOrNull() ?: return@mapNotNull null
            val name = o.optJSONObject("translations")?.str("zho") ?: o.str("name") ?: ""
            TmdbSearchResult(
                id = id, type = if (tv) MediaType.TV else MediaType.MOVIE, title = name,
                originalTitle = o.str("name")?.takeIf { it != name }, year = o.optString("year").toIntOrNull(),
                overview = o.optJSONObject("overviews")?.str("zho") ?: o.str("overview"),
                posterPath = img(o.str("image_url")), backdropPath = null, popularity = 0.0, source = source,
            )
        }
    }

    private fun JSONObject.imdb() = optJSONArray("remoteIds").objects()
        .firstOrNull { it.optString("sourceName").contains("IMDB", true) }?.str("id")

    /** 海报用条目自带的；背景图取 artworks 里的 background（type 3 剧集 / 15 电影） */
    private fun JSONObject.background(vararg types: Int) = optJSONArray("artworks").objects()
        .filter { it.optInt("type") in types }.maxByOrNull { it.optDouble("score", 0.0) }?.str("image")?.let(::img)

    private fun JSONObject.cast() = optJSONArray("characters").objects()
        .filter { it.optString("peopleType") == "Actor" || it.optInt("type") == 3 }
        .sortedBy { it.optInt("sort", 999) }.take(TmdbClient.MAX_CAST)
        .mapIndexed { i, c -> TmdbPerson(0, c.str("personName") ?: "", c.str("name"), img(c.str("personImgURL")), order = i) }

    private fun JSONObject.crew(vararg kinds: String) = optJSONArray("characters").objects()
        .filter { it.optString("peopleType") in kinds }
        .map { TmdbPerson(0, it.str("personName") ?: "", it.optString("peopleType"), img(it.str("personImgURL"))) }

    private fun JSONObject.genres() = optJSONArray("genres").objects().mapNotNull { it.str("name") }

    override suspend fun movie(id: Int): TmdbMovie {
        val j = data("movies/$id/extended?meta=translations")
        val date = j.optJSONObject("first_release")?.str("date")
        val name = j.zh("nameTranslations", "name") ?: j.str("name") ?: ""
        return TmdbMovie(
            id = id, title = name, originalTitle = j.str("name"), year = j.optString("year").toIntOrNull() ?: yearOf(date),
            releaseDate = date, overview = j.zh("overviewTranslations", "overview"),
            runtime = j.optInt("runtime").takeIf { it > 0 }, genres = j.genres(), imdbId = j.imdb(),
            voteAverage = 0.0, posterPath = img(j.str("image")), backdropPath = j.background(15, 3),
            studios = j.optJSONArray("studios").objects().mapNotNull { it.str("name") },
            cast = j.cast(), directors = j.crew("Director"), writers = j.crew("Writer"),
            source = source,
        )
    }

    override suspend fun tv(id: Int): TmdbTvShow {
        val j = data("series/$id/extended?meta=translations")
        val name = j.zh("nameTranslations", "name") ?: j.str("name") ?: ""
        val seasons = j.optJSONArray("seasons").objects()
            .filter { it.optJSONObject("type")?.optString("type") == "official" }
            .map { TmdbSeasonSummary(it.optInt("number"), "", it.optString("year").toIntOrNull(), 0, img(it.str("image"))) }
            .distinctBy { it.seasonNumber }
        return TmdbTvShow(
            id = id, name = name, originalName = j.str("name"),
            firstAirYear = yearOf(j.str("firstAired")) ?: j.optString("year").toIntOrNull(), firstAirDate = j.str("firstAired"),
            overview = j.zh("overviewTranslations", "overview") ?: j.str("overview"), genres = j.genres(), imdbId = j.imdb(),
            voteAverage = 0.0, posterPath = img(j.str("image")), backdropPath = j.background(3),
            seasons = seasons,
            studios = listOfNotNull(j.optJSONObject("originalNetwork")?.str("name")),
            countries = listOfNotNull(j.str("originalCountry")),
            cast = j.cast(), status = j.optJSONObject("status")?.str("name"),
            tvdbId = id, source = source,
        )
    }

    override suspend fun season(id: Int, season: Int): List<TmdbEpisode> {
        val out = mutableListOf<TmdbEpisode>()
        var page = 0
        while (page < 10) {
            val url = "$API/series/$id/episodes/default/zho".toHttpUrl().newBuilder()
                .addQueryParameter("page", page.toString()).addQueryParameter("season", season.toString()).build().toString()
            val j = JSONObject(get(url, auth()))
            j.optJSONObject("data")?.optJSONArray("episodes").objects().filter { it.optInt("seasonNumber") == season }.forEach {
                out += TmdbEpisode(
                    seasonNumber = season, episodeNumber = it.optInt("number"), name = it.str("name") ?: "",
                    overview = it.str("overview"), airDate = it.str("aired"), stillPath = img(it.str("image")), voteAverage = 0.0,
                )
            }
            if (j.optJSONObject("links")?.isNull("next") != false) break
            page++
        }
        return out
    }

    companion object { private const val API = "https://api4.thetvdb.com/v4" }
}

private const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36"
private const val MOBILE_UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
private const val BGM_UA = "clusflah/MediaOrganizer (https://github.com/clusflah/MediaOrganizer)"
