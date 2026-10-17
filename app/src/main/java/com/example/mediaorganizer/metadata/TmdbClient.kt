package com.example.mediaorganizer.metadata

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

enum class MediaType { MOVIE, TV }

data class TmdbSearchResult(
    val id: Int,
    val type: MediaType,
    val title: String,
    val originalTitle: String?,
    val year: Int?,
    val overview: String?,
    val posterPath: String?,
    val backdropPath: String?,
    val popularity: Double,
)

/** 演职人员 */
data class TmdbPerson(
    val id: Int,
    val name: String,
    val role: String? = null,        // 演员饰演的角色 / 职务
    val profilePath: String? = null,
    val order: Int = 0,
)

data class TmdbMovie(
    val id: Int,
    val title: String,
    val originalTitle: String?,
    val year: Int?,
    val releaseDate: String?,
    val overview: String?,
    val runtime: Int?,
    val genres: List<String>,
    val imdbId: String?,
    val voteAverage: Double,
    val posterPath: String?,
    val backdropPath: String?,
    val tagline: String? = null,
    val studios: List<String> = emptyList(),
    val countries: List<String> = emptyList(),
    val cast: List<TmdbPerson> = emptyList(),
    val directors: List<TmdbPerson> = emptyList(),
    val writers: List<TmdbPerson> = emptyList(),
)

data class TmdbTvShow(
    val id: Int,
    val name: String,
    val originalName: String?,
    val firstAirYear: Int?,
    val firstAirDate: String?,
    val overview: String?,
    val genres: List<String>,
    val imdbId: String?,
    val voteAverage: Double,
    val posterPath: String?,
    val backdropPath: String?,
    val seasons: List<TmdbSeasonSummary>,
    val studios: List<String> = emptyList(),       // 电视网 / 出品公司
    val countries: List<String> = emptyList(),
    val creators: List<TmdbPerson> = emptyList(),
    val cast: List<TmdbPerson> = emptyList(),
    val status: String? = null,
    val tvdbId: Int? = null,
)

data class TmdbSeasonSummary(val seasonNumber: Int, val name: String, val airYear: Int?, val episodeCount: Int, val posterPath: String?)

data class TmdbEpisode(
    val seasonNumber: Int,
    val episodeNumber: Int,
    val name: String,
    val overview: String?,
    val airDate: String?,
    val stillPath: String?,
    val voteAverage: Double,
    val directors: List<TmdbPerson> = emptyList(),
    val writers: List<TmdbPerson> = emptyList(),
    val guestStars: List<TmdbPerson> = emptyList(),
)

/**
 * TMDB 客户端。
 * @param credential 推荐填 v4「API 读访问令牌」（eyJ 开头）；也兼容 v3 API Key
 * @param apiBase    国内访问不畅时可换成自建反代地址
 * @param imageBase  同上，图片域名
 */
class TmdbClient(
    private val credential: String,
    private val apiBase: String = "https://api.themoviedb.org/3",
    private val imageBase: String = "https://image.tmdb.org/t/p",
    var language: String = "zh-CN",
    client: OkHttpClient? = null,
) {
    private val http = (client ?: OkHttpClient()).newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val isV4Token = credential.startsWith("eyJ")

    companion object { const val MAX_CAST = 30 }

    fun imageUrl(path: String?, size: String = "original"): String? =
        path?.let { "$imageBase/$size$it" }

    // ---------- 搜索 ----------

    suspend fun searchMovie(query: String, year: Int? = null): List<TmdbSearchResult> {
        val json = get("search/movie") {
            addQueryParameter("query", query)
            addQueryParameter("include_adult", "false")
            year?.let { addQueryParameter("year", it.toString()) }
        }
        return json.optJSONArray("results").objects().map { it.toSearchResult(MediaType.MOVIE) }
    }

    suspend fun searchTv(query: String, year: Int? = null): List<TmdbSearchResult> {
        val json = get("search/tv") {
            addQueryParameter("query", query)
            addQueryParameter("include_adult", "false")
            year?.let { addQueryParameter("first_air_date_year", it.toString()) }
        }
        return json.optJSONArray("results").objects().map { it.toSearchResult(MediaType.TV) }
    }

    // ---------- 详情 ----------

    suspend fun movie(id: Int): TmdbMovie {
        val j = get("movie/$id") { addQueryParameter("append_to_response", "credits") }
        val credits = j.optJSONObject("credits")
        return TmdbMovie(
            id = j.getInt("id"),
            title = j.str("title") ?: "",
            originalTitle = j.str("original_title"),
            year = j.str("release_date")?.take(4)?.toIntOrNull(),
            releaseDate = j.str("release_date"),
            overview = j.str("overview"),
            runtime = j.optInt("runtime").takeIf { it > 0 },
            genres = j.genres(),
            imdbId = j.str("imdb_id"),
            voteAverage = j.optDouble("vote_average", 0.0),
            posterPath = j.str("poster_path"),
            backdropPath = j.str("backdrop_path"),
            tagline = j.str("tagline"),
            studios = j.optJSONArray("production_companies").objects().mapNotNull { it.str("name") },
            countries = j.optJSONArray("production_countries").objects().mapNotNull { it.str("name") },
            cast = credits?.optJSONArray("cast").cast(),
            directors = credits?.optJSONArray("crew").crew("Director"),
            writers = credits?.optJSONArray("crew").crew("Screenplay", "Writer", "Story", "Novel"),
        )
    }

    suspend fun tv(id: Int): TmdbTvShow {
        val j = get("tv/$id") { addQueryParameter("append_to_response", "external_ids,aggregate_credits") }
        return TmdbTvShow(
            id = j.getInt("id"),
            name = j.str("name") ?: "",
            originalName = j.str("original_name"),
            firstAirYear = j.str("first_air_date")?.take(4)?.toIntOrNull(),
            firstAirDate = j.str("first_air_date"),
            overview = j.str("overview"),
            genres = j.genres(),
            imdbId = j.optJSONObject("external_ids")?.str("imdb_id"),
            tvdbId = j.optJSONObject("external_ids")?.optInt("tvdb_id", 0)?.takeIf { it > 0 },
            voteAverage = j.optDouble("vote_average", 0.0),
            posterPath = j.str("poster_path"),
            backdropPath = j.str("backdrop_path"),
            seasons = j.optJSONArray("seasons").objects().map {
                TmdbSeasonSummary(
                    seasonNumber = it.optInt("season_number"),
                    name = it.str("name") ?: "",
                    airYear = it.str("air_date")?.take(4)?.toIntOrNull(),
                    episodeCount = it.optInt("episode_count"),
                    posterPath = it.str("poster_path"),
                )
            },
            studios = (j.optJSONArray("networks").objects() + j.optJSONArray("production_companies").objects())
                .mapNotNull { it.str("name") }.distinct(),
            countries = j.optJSONArray("origin_country").let { a -> (0 until (a?.length() ?: 0)).map { a!!.getString(it) } },
            creators = j.optJSONArray("created_by").objects().map {
                TmdbPerson(it.optInt("id"), it.str("name") ?: "", "Creator", it.str("profile_path"))
            },
            // aggregate_credits：全部季的演员，角色在 roles 数组里
            cast = j.optJSONObject("aggregate_credits")?.optJSONArray("cast").objects().take(MAX_CAST).mapIndexed { i, p ->
                TmdbPerson(
                    id = p.optInt("id"),
                    name = p.str("name") ?: "",
                    role = p.optJSONArray("roles").objects().firstOrNull()?.str("character"),
                    profilePath = p.str("profile_path"),
                    order = i,
                )
            },
            status = j.str("status"),
        )
    }

    suspend fun season(tvId: Int, seasonNumber: Int): List<TmdbEpisode> {
        val j = get("tv/$tvId/season/$seasonNumber")
        return j.optJSONArray("episodes").objects().map {
            TmdbEpisode(
                seasonNumber = it.optInt("season_number", seasonNumber),
                episodeNumber = it.optInt("episode_number"),
                name = it.str("name") ?: "",
                overview = it.str("overview"),
                airDate = it.str("air_date"),
                stillPath = it.str("still_path"),
                voteAverage = it.optDouble("vote_average", 0.0),
                directors = it.optJSONArray("crew").crew("Director"),
                writers = it.optJSONArray("crew").crew("Writer", "Screenplay", "Teleplay"),
                guestStars = it.optJSONArray("guest_stars").cast(),
            )
        }
    }

    /** clearlogo：优先中文，其次英文，再次无语言 */
    suspend fun logoPath(type: MediaType, id: Int): String? {
        val j = get("${if (type == MediaType.MOVIE) "movie" else "tv"}/$id/images") {
            addQueryParameter("include_image_language", "zh,en,null")
            // images 接口不能带 language，否则只返回该语言
        }
        val logos = j.optJSONArray("logos").objects()
        return listOf("zh", "en", null).firstNotNullOfOrNull { lang ->
            logos.firstOrNull { it.str("iso_639_1") == lang }?.str("file_path")
        }
    }

    // ---------- 网络 ----------

    private suspend fun get(path: String, params: HttpUrl.Builder.() -> Unit = {}): JSONObject {
        val url = "$apiBase/$path".toHttpUrl().newBuilder().apply {
            if (!path.endsWith("/images")) addQueryParameter("language", language)
            if (!isV4Token) addQueryParameter("api_key", credential)
            params()
        }.build()

        val req = Request.Builder().url(url).apply {
            if (isV4Token) header("Authorization", "Bearer $credential")
            header("Accept", "application/json")
        }.build()

        repeat(3) { attempt ->
            val (code, body, retryAfter) = withContext(Dispatchers.IO) {
                http.newCall(req).execute().use {
                    Triple(it.code, it.body?.string().orEmpty(), it.header("Retry-After")?.toLongOrNull())
                }
            }
            when {
                code == 200 -> return JSONObject(body)
                code == 429 -> delay((retryAfter ?: (attempt + 1L)) * 1000)   // 限流，等待后重试
                code == 401 -> throw IOException("TMDB 认证失败，请检查 API Key / Token")
                code == 404 -> throw IOException("TMDB 未找到：$path")
                else -> throw IOException("TMDB 请求失败 HTTP $code")
            }
        }
        throw IOException("TMDB 请求过于频繁，请稍后再试")
    }

    // ---------- JSON 工具 ----------

    private fun JSONObject.str(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private fun JSONArray?.cast(): List<TmdbPerson> = objects().take(MAX_CAST).map {
        TmdbPerson(
            id = it.optInt("id"),
            name = it.str("name") ?: "",
            role = it.str("character"),
            profilePath = it.str("profile_path"),
            order = it.optInt("order"),
        )
    }

    private fun JSONArray?.crew(vararg jobs: String): List<TmdbPerson> = objects()
        .filter { it.str("job") in jobs }
        .map { TmdbPerson(it.optInt("id"), it.str("name") ?: "", it.str("job"), it.str("profile_path")) }
        .distinctBy { it.id }

    private fun JSONObject.genres(): List<String> =
        optJSONArray("genres").objects().mapNotNull { it.str("name") }

    private fun JSONObject.toSearchResult(type: MediaType): TmdbSearchResult {
        val isMovie = type == MediaType.MOVIE
        return TmdbSearchResult(
            id = getInt("id"),
            type = type,
            title = str(if (isMovie) "title" else "name") ?: "",
            originalTitle = str(if (isMovie) "original_title" else "original_name"),
            year = str(if (isMovie) "release_date" else "first_air_date")?.take(4)?.toIntOrNull(),
            overview = str("overview"),
            posterPath = str("poster_path"),
            backdropPath = str("backdrop_path"),
            popularity = optDouble("popularity", 0.0),
        )
    }
}
