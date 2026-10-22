package com.example.mediaorganizer.music

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

enum class MusicSource(val label: String) { MUSICBRAINZ("MusicBrainz"), ITUNES("iTunes") }

/** 搜索结果里的一张专辑（还没取曲目） */
data class ReleaseCandidate(
    val source: MusicSource,
    val id: String,
    val title: String,
    val artist: String,
    val year: Int?,
    val trackCount: Int,
    val country: String? = null,
    val format: String? = null,       // CD / Digital Media / Vinyl …
    val type: String? = null,         // Album / Single / EP
    val label: String? = null,
)

data class MusicTrack(
    val disc: Int,
    val number: Int,
    val title: String,
    val artist: String,
    val lengthSec: Int?,
    val recordingId: String? = null,
    val isrc: String? = null,
)

/** 一张专辑的完整信息 */
data class MusicRelease(
    val source: MusicSource,
    val id: String,
    val title: String,
    val artist: String,
    val artistId: String? = null,      // MusicBrainz 艺人 ID
    val date: String? = null,
    val year: Int? = null,
    val country: String? = null,
    val label: String? = null,
    val catalogNo: String? = null,
    val barcode: String? = null,
    val releaseGroupId: String? = null,
    val type: String? = null,
    val genres: List<String> = emptyList(),
    val tracks: List<MusicTrack> = emptyList(),
    val covers: List<String> = emptyList(),   // 依次尝试
) {
    val discCount: Int get() = tracks.maxOfOrNull { it.disc } ?: 1

    fun candidate() = ReleaseCandidate(source, id, title, artist, year, tracks.size, country, type = type, label = label)
}

data class ArtistInfo(
    val id: String,
    val name: String,
    val sortName: String? = null,
    val type: String? = null,          // Person / Group
    val country: String? = null,
    val begin: String? = null,
    val end: String? = null,
    val disambiguation: String? = null,
    val genres: List<String> = emptyList(),
)

data class Lyrics(val synced: String?, val plain: String?, val instrumental: Boolean = false)

// ============================ HTTP ============================

private const val USER_AGENT = "MediaOrganizer/2.21 ( https://github.com/clusflah/MediaOrganizer )"

private fun JSONObject.str(key: String): String? =
    if (isNull(key)) null else optString(key).trim().takeIf { it.isNotEmpty() && it != "null" }

private fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

private fun yearOf(s: String?) = s?.let { Regex("""\d{4}""").find(it)?.value?.toIntOrNull() }

open class MusicHttp(client: OkHttpClient? = null) {
    protected val http: OkHttpClient = (client ?: OkHttpClient()).newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    /** 返回 null 表示 404 */
    protected suspend fun get(url: HttpUrl, label: String): String? = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).header("Accept", "application/json").build())
            .execute().use {
                when {
                    it.isSuccessful -> it.body!!.string()
                    it.code == 404 -> null
                    else -> throw IOException("$label 请求失败 HTTP ${it.code}")
                }
            }
    }

    suspend fun download(url: String): ByteArray = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute().use {
            if (!it.isSuccessful) throw IOException("下载失败 HTTP ${it.code}")
            it.body!!.bytes()
        }
    }
}

// ============================ MusicBrainz + Cover Art Archive ============================

/**
 * MusicBrainz 要求每秒不超过 1 次请求、带上能联系到作者的 User-Agent。
 * 封面来自 Cover Art Archive，用发行 ID 直接拼地址。
 */
class MusicBrainzClient(client: OkHttpClient? = null, private val base: String = "https://musicbrainz.org/ws/2") : MusicHttp(client) {
    private val lock = Mutex()
    private var last = 0L

    private suspend fun mb(path: String, vararg params: Pair<String, String>): JSONObject? = lock.withLock {
        val url = "$base/$path".toHttpUrl().newBuilder().apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
            addQueryParameter("fmt", "json")
        }.build()
        var attempt = 0
        var body: String?
        while (true) {
            val wait = last + 1100 - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            last = System.currentTimeMillis()
            try {
                body = get(url, "MusicBrainz")
                break
            } catch (e: IOException) {
                // 503 = 超过频率限制，歇一会再试
                if (++attempt >= 3) throw e
                delay(1500L * attempt)
            }
        }
        body?.let(::JSONObject)
    }

    suspend fun searchReleases(album: String, artist: String?, limit: Int = 10): List<ReleaseCandidate> {
        val q = buildString {
            append("release:\"").append(lucene(album)).append('"')
            if (!artist.isNullOrBlank()) append(" AND artist:\"").append(lucene(artist)).append('"')
        }
        val o = mb("release", "query" to q, "limit" to limit.toString()) ?: return emptyList()
        return o.optJSONArray("releases").objects().map(::candidate)
    }

    /** 按单曲名搜：返回包含这首歌的发行（优先正式专辑） */
    suspend fun searchRecording(title: String, artist: String?, limit: Int = 10): List<ReleaseCandidate> {
        val q = buildString {
            append("recording:\"").append(lucene(title)).append('"')
            if (!artist.isNullOrBlank()) append(" AND artist:\"").append(lucene(artist)).append('"')
        }
        val o = mb("recording", "query" to q, "limit" to limit.toString()) ?: return emptyList()
        return o.optJSONArray("recordings").objects().flatMap { rec ->
            val artistName = credit(rec.optJSONArray("artist-credit"))
            rec.optJSONArray("releases").objects().map { r ->
                candidate(r).let { if (it.artist.isEmpty()) it.copy(artist = artistName) else it }
            }
        }.sortedWith(compareBy<ReleaseCandidate>(
            { if (it.type == "Album") 0 else if (it.type == "EP" || it.type == "Single") 1 else 2 },
            { it.year ?: 9999 },
        )).distinctBy { it.id }
    }

    suspend fun release(id: String): MusicRelease {
        val o = mb("release/$id", "inc" to "recordings+artist-credits+release-groups+labels+genres+isrcs")
            ?: throw IOException("MusicBrainz 没有这张专辑")
        val artist = credit(o.optJSONArray("artist-credit"))
        val artistId = o.optJSONArray("artist-credit").objects().firstOrNull()?.optJSONObject("artist")?.str("id")
        val rg = o.optJSONObject("release-group")
        val tracks = o.optJSONArray("media").objects().flatMapIndexed { i, m ->
            val disc = m.optInt("position", i + 1)
            m.optJSONArray("tracks").objects().mapIndexed { j, t ->
                val rec = t.optJSONObject("recording")
                MusicTrack(
                    disc = disc,
                    number = t.optInt("position", j + 1),
                    title = t.str("title") ?: rec?.str("title") ?: "",
                    artist = credit(t.optJSONArray("artist-credit")).ifEmpty { artist },
                    lengthSec = (t.optLong("length", 0).takeIf { it > 0 } ?: rec?.optLong("length", 0))?.let { (it / 1000).toInt() }?.takeIf { it > 0 },
                    recordingId = rec?.str("id"),
                    isrc = rec?.optJSONArray("isrcs")?.optString(0)?.takeIf { it.isNotBlank() },
                )
            }
        }
        val label = o.optJSONArray("label-info").objects().firstOrNull()
        val genres = (o.optJSONArray("genres").objects() + (rg?.optJSONArray("genres").objects()))
            .sortedByDescending { it.optInt("count") }.mapNotNull { it.str("name") }.distinct().take(5)
        val covers = buildList {
            if (o.optJSONObject("cover-art-archive")?.optBoolean("front") != false) add("https://coverartarchive.org/release/$id/front-1200")
            rg?.str("id")?.let { add("https://coverartarchive.org/release-group/$it/front-1200") }
        }
        val date = o.str("date")
        return MusicRelease(
            source = MusicSource.MUSICBRAINZ, id = id, title = o.str("title") ?: "", artist = artist, artistId = artistId,
            date = date ?: rg?.str("first-release-date"), year = yearOf(date ?: rg?.str("first-release-date")),
            country = o.str("country"), label = label?.optJSONObject("label")?.str("name"), catalogNo = label?.str("catalog-number"),
            barcode = o.str("barcode"), releaseGroupId = rg?.str("id"), type = rg?.str("primary-type"),
            genres = genres, tracks = tracks, covers = covers,
        )
    }

    suspend fun artist(id: String): ArtistInfo? {
        val o = mb("artist/$id", "inc" to "genres") ?: return null
        val life = o.optJSONObject("life-span")
        return ArtistInfo(
            id = id, name = o.str("name") ?: "", sortName = o.str("sort-name"), type = o.str("type"),
            country = o.optJSONObject("area")?.str("name") ?: o.str("country"),
            begin = life?.str("begin"), end = life?.str("end"), disambiguation = o.str("disambiguation"),
            genres = o.optJSONArray("genres").objects().sortedByDescending { it.optInt("count") }.mapNotNull { it.str("name") }.take(5),
        )
    }

    private fun candidate(r: JSONObject): ReleaseCandidate {
        val media = r.optJSONArray("media").objects()
        return ReleaseCandidate(
            source = MusicSource.MUSICBRAINZ,
            id = r.str("id") ?: "",
            title = r.str("title") ?: "",
            artist = credit(r.optJSONArray("artist-credit")),
            year = yearOf(r.str("date")),
            trackCount = r.optInt("track-count").takeIf { it > 0 } ?: media.sumOf { it.optInt("track-count") },
            country = r.str("country"),
            format = media.mapNotNull { it.str("format") }.distinct().joinToString("+").ifEmpty { null },
            type = r.optJSONObject("release-group")?.str("primary-type"),
            label = r.optJSONArray("label-info").objects().firstOrNull()?.optJSONObject("label")?.str("name"),
        )
    }

    /** 合作艺人："周杰伦 feat. 方文山" */
    private fun credit(a: JSONArray?): String = a.objects().joinToString("") {
        (it.str("name") ?: it.optJSONObject("artist")?.str("name") ?: "") + (if (it.isNull("joinphrase")) "" else it.optString("joinphrase"))
    }.trim()

    private fun lucene(s: String) = s.replace(Regex("""([+\-!(){}\[\]^"~*?:\\/]|&&|\|\|)"""), "\\\\$1")
}

// ============================ iTunes Search API ============================

/** MusicBrainz 搜不到时的备用来源，也补封面（商业音乐基本都有） */
class ItunesClient(client: OkHttpClient? = null, private val country: String = "CN") : MusicHttp(client) {

    suspend fun searchAlbums(album: String, artist: String?, limit: Int = 10): List<ReleaseCandidate> {
        val url = "https://itunes.apple.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("term", listOfNotNull(artist, album).joinToString(" "))
            .addQueryParameter("entity", "album").addQueryParameter("limit", limit.toString())
            .addQueryParameter("country", country).build()
        val o = get(url, "iTunes")?.let(::JSONObject) ?: return emptyList()
        return o.optJSONArray("results").objects().filter { it.optLong("collectionId") > 0 }.map {
            ReleaseCandidate(
                source = MusicSource.ITUNES, id = it.optLong("collectionId").toString(),
                title = it.str("collectionName") ?: "", artist = it.str("artistName") ?: "",
                year = yearOf(it.str("releaseDate")), trackCount = it.optInt("trackCount"),
                country = it.str("country"), format = "Digital Media",
                type = it.str("collectionType"),
            )
        }
    }

    suspend fun album(id: String): MusicRelease {
        val url = "https://itunes.apple.com/lookup".toHttpUrl().newBuilder()
            .addQueryParameter("id", id).addQueryParameter("entity", "song").addQueryParameter("country", country).build()
        val results = get(url, "iTunes")?.let(::JSONObject)?.optJSONArray("results").objects()
        val c = results.firstOrNull { it.str("wrapperType") == "collection" } ?: throw IOException("iTunes 没有这张专辑")
        val tracks = results.filter { it.str("wrapperType") == "track" }.map {
            MusicTrack(
                disc = it.optInt("discNumber", 1).coerceAtLeast(1), number = it.optInt("trackNumber"),
                title = it.str("trackName") ?: "", artist = it.str("artistName") ?: "",
                lengthSec = it.optLong("trackTimeMillis").takeIf { ms -> ms > 0 }?.let { ms -> (ms / 1000).toInt() },
            )
        }.sortedWith(compareBy({ it.disc }, { it.number }))
        return MusicRelease(
            source = MusicSource.ITUNES, id = id, title = c.str("collectionName") ?: "", artist = c.str("artistName") ?: "",
            date = c.str("releaseDate")?.take(10), year = yearOf(c.str("releaseDate")), country = c.str("country"),
            label = c.str("copyright")?.replace(Regex("""^[℗©]\s*\d{4}\s*"""), "")?.takeIf { it.length < 60 },
            genres = listOfNotNull(c.str("primaryGenreName")), tracks = tracks,
            covers = listOfNotNull(c.str("artworkUrl100")?.let(::bigArtwork)),
        )
    }

    /** 给 MusicBrainz 的专辑找 iTunes 封面：标题、艺人都要对得上 */
    suspend fun coverFor(album: String, artist: String): String? =
        runCatching { searchAlbums(album, artist, 5) }.getOrDefault(emptyList())
            .firstOrNull { MusicMatcher.similarity(it.title, album) >= 0.8 && MusicMatcher.similarity(it.artist, artist) >= 0.6 }
            ?.let { c ->
                // 搜索结果的封面地址在 lookup 里才有，换个尺寸参数即可
                runCatching { album(c.id).covers.firstOrNull() }.getOrNull()
            }

    private fun bigArtwork(u: String) = u.replace(Regex("""/\d+x\d+bb\."""), "/1200x1200bb.")
}

// ============================ LRCLIB 歌词 ============================

class LrclibClient(client: OkHttpClient? = null) : MusicHttp(client) {

    suspend fun lyrics(title: String, artist: String, album: String?, durationSec: Int?): Lyrics? {
        if (durationSec != null) {
            val url = "https://lrclib.net/api/get".toHttpUrl().newBuilder()
                .addQueryParameter("track_name", title).addQueryParameter("artist_name", artist)
                .apply { if (album != null) addQueryParameter("album_name", album) }
                .addQueryParameter("duration", durationSec.toString()).build()
            get(url, "LRCLIB")?.let { parse(JSONObject(it)) }?.let { return it }
        }
        val url = "https://lrclib.net/api/search".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", title).addQueryParameter("artist_name", artist).build()
        val list = get(url, "LRCLIB")?.let(::JSONArray).objects()
            .filter { durationSec == null || kotlin.math.abs(it.optDouble("duration", 0.0) - durationSec) <= 3 }
        val best = list.firstOrNull { it.str("syncedLyrics") != null } ?: list.firstOrNull() ?: return null
        return parse(best)
    }

    private fun parse(o: JSONObject) = Lyrics(o.str("syncedLyrics"), o.str("plainLyrics"), o.optBoolean("instrumental"))
        .takeIf { it.synced != null || it.plain != null || it.instrumental }
}
