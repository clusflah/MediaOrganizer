package com.example.mediaorganizer.scraper

import com.example.mediaorganizer.metadata.MediaType
import com.example.mediaorganizer.metadata.TmdbClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class TmdbScrapeSource(
    private val tmdb: TmdbClient,
    client: OkHttpClient? = null,
) : ScrapeSource {

    private val http = (client ?: OkHttpClient()).newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val logos = ConcurrentHashMap<String, Result<String?>>()

    override fun imageUrl(path: String, size: String): String = tmdb.imageUrl(path, size)!!

    override suspend fun logoPath(type: MediaType, id: Int): String? {
        val key = "$type/$id"
        logos[key]?.let { return it.getOrNull() }
        val r = runCatching { tmdb.logoPath(type, id) }
        logos[key] = r
        return r.getOrNull()
    }

    override suspend fun download(url: String): ByteArray {
        var last: Exception? = null
        repeat(3) { attempt ->
            try {
                return withContext(Dispatchers.IO) {
                    // 豆瓣图床防盗链，要带豆瓣的 Referer
                    val req = Request.Builder().url(url).apply { if (url.contains("doubanio.com")) header("Referer", "https://movie.douban.com/") }.build()
                    http.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) throw IOException("下载图片失败 HTTP ${resp.code}")
                        resp.body!!.bytes()
                    }
                }
            } catch (e: Exception) {
                last = e
                delay(800L * (attempt + 1))
            }
        }
        throw last ?: IOException("下载图片失败")
    }
}
