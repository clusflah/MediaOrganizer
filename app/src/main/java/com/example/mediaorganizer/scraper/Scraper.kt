package com.example.mediaorganizer.scraper

import com.example.mediaorganizer.metadata.MediaType
import com.example.mediaorganizer.metadata.TmdbEpisode
import com.example.mediaorganizer.metadata.TmdbPerson
import com.example.mediaorganizer.metadata.TmdbMovie
import com.example.mediaorganizer.metadata.TmdbTvShow
import com.example.mediaorganizer.naming.MediaDetails
import com.example.mediaorganizer.naming.RenamePlan
import com.example.mediaorganizer.storage.FileOperation
import com.example.mediaorganizer.storage.StorageProvider
import com.example.mediaorganizer.storage.joinPath
import com.example.mediaorganizer.storage.normalizePath
import com.example.mediaorganizer.storage.parentPath
import java.io.ByteArrayInputStream
import java.util.Locale

// ============================ 配置 ============================

/** 刮削模板，可多选（截图里的三种） */
enum class ScrapeTemplate { INFUSE, KODI_EMBY, PLEX }

/** 目标位置已有同名 NFO / 图片时怎么处理 */
enum class ScrapeStrategy {
    OVERWRITE,      // 全部重新生成
    UPDATE_NFO,     // 重写 NFO，已有图片保留（用户自己换过的海报不会被冲掉）
    ONLY_MISSING,   // 只补缺少的文件
}

enum class ImageQuality(val poster: String, val backdrop: String) {
    ORIGINAL("original", "original"),
    HIGH("w780", "w1280"),
    STANDARD("w500", "w780"),
}

data class ScrapeConfig(
    val templates: Set<ScrapeTemplate> = setOf(ScrapeTemplate.INFUSE),
    val strategy: ScrapeStrategy = ScrapeStrategy.OVERWRITE,
    val nfo: Boolean = true,
    val poster: Boolean = true,
    val fanart: Boolean = true,
    val logo: Boolean = true,
    val seasonPoster: Boolean = true,
    val episodeThumb: Boolean = true,
    val quality: ImageQuality = ImageQuality.ORIGINAL,
    val cast: Boolean = true,            // NFO 写入演职人员
    val maxActors: Int = 20,
)

/** 图片来源，抽成接口方便测试；正式实现见 TmdbScrapeSource */
interface ScrapeSource {
    fun imageUrl(path: String, size: String): String
    suspend fun logoPath(type: MediaType, id: Int): String?
    suspend fun download(url: String): ByteArray
}

// ============================ 输出清单 ============================

sealed interface Content {
    data class Text(val text: String) : Content
    data class Image(val url: String) : Content
}

data class OutputFile(val path: String, val content: Content)

data class ScrapeReport(
    val written: Int,
    val skipped: Int,
    val errors: List<String>,
    val operations: List<FileOperation> = emptyList(),   // 新建的文件，撤销时删除
)

class Scraper(
    private val storage: StorageProvider,
    private val details: MediaDetails,
    private val source: ScrapeSource,
    private val storageId: String = "",
) {
    /**
     * @param plans 已经整理到位（或无需改名）的文件
     */
    suspend fun scrape(
        plans: List<RenamePlan>,
        config: ScrapeConfig,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): ScrapeReport {
        // 1. 先汇总所有要写的文件（剧集级、季级的只算一次）
        val outputs = LinkedHashMap<String, Content>()
        val errors = mutableListOf<String>()
        for (plan in plans) {
            runCatching { collectOutputs(plan, config).forEach { outputs.putIfAbsent(normalizePath(it.path), it.content) } }
                .onFailure { errors += "${plan.targetName}：${it.message}" }
        }

        // 2. 每个目录列一次：「仅刮削缺少文件」据此跳过；同时区分新建/覆盖，
        //    撤销时只删除新建的，不会误删用户原有的 NFO 和图片
        val existing = HashMap<String, Set<String>>()
        for (dir in outputs.keys.map(::parentPath).distinct()) {
            existing[dir] = runCatching { storage.list(dir).map { it.name }.toSet() }.getOrDefault(emptySet())
        }
        val ops = mutableListOf<FileOperation>()

        // 3. 下载 + 上传
        var written = 0
        var skipped = 0
        val imageCache = HashMap<String, ByteArray>()   // 同一张图写多份（poster.jpg + folder.jpg）只下载一次
        for ((i, entry) in outputs.entries.withIndex()) {
            val (path, content) = entry
            val alreadyThere = existing[parentPath(path)]?.contains(path.substringAfterLast('/')) == true
            val keep = when (config.strategy) {
                ScrapeStrategy.OVERWRITE -> false
                ScrapeStrategy.UPDATE_NFO -> content is Content.Image
                ScrapeStrategy.ONLY_MISSING -> true
            }
            if (alreadyThere && keep) {
                skipped++
            } else {
                runCatching {
                    val bytes = when (content) {
                        is Content.Text -> content.text.toByteArray(Charsets.UTF_8)
                        is Content.Image -> imageCache.getOrPut(content.url) { source.download(content.url) }
                    }
                    storage.upload(path, ByteArrayInputStream(bytes), bytes.size.toLong())
                    written++
                    if (!alreadyThere) ops += FileOperation(storageId, FileOperation.Type.UPLOAD, "", path)
                }.onFailure { errors += "${path.substringAfterLast('/')}：${it.message}" }
            }
            onProgress(i + 1, outputs.size)
        }
        return ScrapeReport(written, skipped, errors, ops)
    }

    /** 根据模板算出一个视频需要哪些附属文件 */
    suspend fun collectOutputs(plan: RenamePlan, c: ScrapeConfig): List<OutputFile> {
        val input = plan.input
        val dir = plan.targetDir
        val base = plan.targetName.substringBeforeLast('.')
        val out = mutableListOf<OutputFile>()
        fun add(path: String, content: Content?) { if (content != null) out += OutputFile(path, content) }
        fun poster(p: String?) = p?.let { Content.Image(source.imageUrl(it, c.quality.poster)) }
        fun backdrop(p: String?) = p?.let { Content.Image(source.imageUrl(it, c.quality.backdrop)) }
        val t = c.templates
        val nfo = NfoWriter(
            thumb = { source.imageUrl(it, "w185") },
            maxActors = if (c.cast) c.maxActors else 0,
        )

        if (!input.isEpisode) {
            val movie = details.movie(input.tmdbId)
            val logo = if (c.logo) source.logoPath(MediaType.MOVIE, movie.id)?.let { Content.Image(source.imageUrl(it, "original")) } else null
            if (c.nfo) add(joinPath(dir, "$base.nfo"), Content.Text(nfo.movie(movie)))

            // 独占文件夹用通用名；和别的电影混放时只能用“视频名-xxx”
            val generic = plan.ownFolder
            if (ScrapeTemplate.INFUSE in t || ScrapeTemplate.KODI_EMBY in t) {
                if (c.poster) add(joinPath(dir, if (generic) "poster.jpg" else "$base-poster.jpg"), poster(movie.posterPath))
                if (c.fanart) add(joinPath(dir, if (generic) "fanart.jpg" else "$base-fanart.jpg"), backdrop(movie.backdropPath))
                if (c.logo) add(joinPath(dir, if (generic) "clearlogo.png" else "$base-clearlogo.png"), logo)
            }
            if (ScrapeTemplate.INFUSE in t && generic && c.poster) {
                add(joinPath(dir, "folder.jpg"), poster(movie.posterPath))   // 资源管理器/访达的文件夹封面
            }
            if (ScrapeTemplate.PLEX in t) {
                if (c.poster) add(joinPath(dir, "$base.jpg"), poster(movie.posterPath))
                if (c.fanart) add(joinPath(dir, "$base-fanart.jpg"), backdrop(movie.backdropPath))
            }
            return out
        }

        // ---------- 剧集 ----------
        val show = details.tv(input.tmdbId)
        val showDir = plan.showDir
        val season = input.season ?: 1
        val ep = input.episode?.let { e -> details.episodes(show.id, season).firstOrNull { it.episodeNumber == e } }

        // 剧集级（多集共用，汇总时自动去重）
        if (c.nfo) add(joinPath(showDir, "tvshow.nfo"), Content.Text(nfo.tvShow(show)))
        if (c.poster) add(joinPath(showDir, "poster.jpg"), poster(show.posterPath))
        if (c.fanart) add(joinPath(showDir, "fanart.jpg"), backdrop(show.backdropPath))
        if (c.logo) add(joinPath(showDir, "clearlogo.png"),
            source.logoPath(MediaType.TV, show.id)?.let { Content.Image(source.imageUrl(it, "original")) })
        if (ScrapeTemplate.INFUSE in t && c.poster) add(joinPath(showDir, "folder.jpg"), poster(show.posterPath))

        // 季海报
        if (c.seasonPoster) {
            val sp = poster(show.seasons.firstOrNull { it.seasonNumber == season }?.posterPath)
            val kodiName = if (season == 0) "season-specials-poster.jpg" else "season%02d-poster.jpg".format(season)
            if (ScrapeTemplate.INFUSE in t || ScrapeTemplate.KODI_EMBY in t) add(joinPath(showDir, kodiName), sp)
            if (ScrapeTemplate.PLEX in t && dir != showDir) add(joinPath(dir, "poster.jpg"), sp)
            if (ScrapeTemplate.INFUSE in t && dir != showDir) add(joinPath(dir, "folder.jpg"), sp)
        }

        // 单集
        if (c.nfo) add(joinPath(dir, "$base.nfo"), Content.Text(nfo.episode(show, season, input.episode ?: 0, ep)))
        if (c.episodeThumb) {
            val still = backdrop(ep?.stillPath)
            if (ScrapeTemplate.INFUSE in t || ScrapeTemplate.KODI_EMBY in t) add(joinPath(dir, "$base-thumb.jpg"), still)
            if (ScrapeTemplate.PLEX in t) add(joinPath(dir, "$base.jpg"), still)
        }
        return out
    }
}

// ============================ NFO（Kodi 格式，Emby/Jellyfin/Infuse 通用） ============================

/**
 * @param thumb     把 TMDB 图片路径转成完整 URL（演员头像）
 * @param maxActors 0 表示不写演职人员
 */
class NfoWriter(
    private val thumb: (String) -> String? = { null },
    private val maxActors: Int = 20,
) {

    fun movie(m: TmdbMovie): String = doc("movie") {
        tag("title", m.title)
        tag("originaltitle", m.originalTitle)
        tag("sorttitle", m.title)
        tag("year", m.year)
        tag("premiered", m.releaseDate)
        tag("releasedate", m.releaseDate)
        tag("tagline", m.tagline)
        tag("plot", m.overview)
        tag("outline", m.overview)
        tag("runtime", m.runtime)
        m.genres.forEach { tag("genre", it) }
        m.countries.forEach { tag("country", it) }
        m.studios.take(3).forEach { tag("studio", it) }
        rating(m.voteAverage)
        uniqueId("tmdb", m.id.toString(), default = true)
        uniqueId("imdb", m.imdbId)
        tag("tmdbid", m.id)
        tag("imdbid", m.imdbId)
        if (maxActors > 0) {
            m.directors.forEach { person("director", it) }
            m.writers.forEach { person("credits", it) }
            actors(m.cast)
        }
    }

    fun tvShow(s: TmdbTvShow): String = doc("tvshow") {
        tag("title", s.name)
        tag("originaltitle", s.originalName)
        tag("showtitle", s.name)
        tag("year", s.firstAirYear)
        tag("premiered", s.firstAirDate)
        tag("status", s.status)
        tag("plot", s.overview)
        s.genres.forEach { tag("genre", it) }
        s.countries.forEach { tag("country", it) }
        s.studios.take(3).forEach { tag("studio", it) }
        rating(s.voteAverage)
        uniqueId("tmdb", s.id.toString(), default = true)
        uniqueId("imdb", s.imdbId)
        tag("tmdbid", s.id)
        tag("imdbid", s.imdbId)
        tag("season", -1)
        tag("episode", -1)
        if (maxActors > 0) {
            s.creators.forEach { person("credits", it) }
            actors(s.cast)
        }
    }

    fun episode(s: TmdbTvShow, season: Int, episode: Int, e: TmdbEpisode?): String = doc("episodedetails") {
        tag("title", e?.name ?: "第 $episode 集")
        tag("showtitle", s.name)
        tag("season", season)
        tag("episode", episode)
        tag("plot", e?.overview)
        tag("aired", e?.airDate)
        tag("premiered", e?.airDate)
        if (e != null && e.voteAverage > 0) rating(e.voteAverage)
        if (maxActors > 0 && e != null) {
            e.directors.forEach { person("director", it) }
            e.writers.forEach { person("credits", it) }
            actors(e.guestStars)
        }
    }

    // ---------- 小工具 ----------

    private inner class Builder {
        val sb = StringBuilder()
        fun tag(name: String, value: Any?) {
            val v = value?.toString()?.trim()
            if (!v.isNullOrEmpty()) sb.append("  <$name>").append(escape(v)).append("</$name>\n")
        }
        fun uniqueId(type: String, value: String?, default: Boolean = false) {
            if (value.isNullOrBlank()) return
            sb.append("  <uniqueid type=\"$type\"").append(if (default) " default=\"true\"" else "")
                .append(">").append(escape(value)).append("</uniqueid>\n")
        }
        fun rating(value: Double) {
            if (value <= 0) return
            val v = "%.1f".format(Locale.US, value)
            sb.append("  <ratings>\n    <rating name=\"themoviedb\" max=\"10\" default=\"true\">\n")
                .append("      <value>").append(v).append("</value>\n")
                .append("    </rating>\n  </ratings>\n")
            tag("rating", v)
        }
        /** Emby 能识别 tmdbid 属性，直接关联到人物页 */
        fun person(name: String, p: TmdbPerson) {
            if (p.name.isBlank()) return
            sb.append("  <$name tmdbid=\"${p.id}\">").append(escape(p.name)).append("</$name>\n")
        }
        fun actors(list: List<TmdbPerson>) {
            list.sortedBy { it.order }.take(maxActors).forEachIndexed { i, p ->
                if (p.name.isBlank()) return@forEachIndexed
                sb.append("  <actor>\n")
                sb.append("    <name>").append(escape(p.name)).append("</name>\n")
                p.role?.takeIf { it.isNotBlank() }?.let { sb.append("    <role>").append(escape(it)).append("</role>\n") }
                sb.append("    <type>Actor</type>\n")
                sb.append("    <order>").append(i).append("</order>\n")
                p.profilePath?.let(thumb)?.let { sb.append("    <thumb>").append(escape(it)).append("</thumb>\n") }
                sb.append("    <tmdbid>").append(p.id).append("</tmdbid>\n")
                sb.append("  </actor>\n")
            }
        }
    }

    private fun doc(root: String, body: Builder.() -> Unit): String {
        val b = Builder().apply(body)
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<$root>\n${b.sb}</$root>\n"
    }

    private fun escape(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")
        .replace(Regex("""[\u0000-\u0008\u000B\u000C\u000E-\u001F]"""), "")
}
