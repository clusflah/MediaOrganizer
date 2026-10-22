package com.example.mediaorganizer.scraper

import com.example.mediaorganizer.metadata.MediaType
import com.example.mediaorganizer.metadata.MetaSource
import com.example.mediaorganizer.metadata.extra.EpisodeExtra
import com.example.mediaorganizer.metadata.extra.ExtraConfig
import com.example.mediaorganizer.metadata.extra.ExtraInfo
import com.example.mediaorganizer.metadata.extra.MetadataExtras
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
    val extra: ExtraConfig = ExtraConfig(),   // 豆瓣、Bangumi、TheTVDB、Fanart.tv
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
    /** fallbacks：前一个下载失败（如 404）时依次尝试 */
    data class Image(val url: String, val fallbacks: List<String> = emptyList()) : Content
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
    private val extras: MetadataExtras? = null,    // 豆瓣 / Bangumi / TheTVDB / Fanart.tv，可选
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

        val r = writeOutputs(storage, outputs, config.strategy, storageId, source::download, onProgress)
        return r.copy(errors = errors + r.errors)
    }

    /** 根据模板算出一个视频需要哪些附属文件 */
    suspend fun collectOutputs(plan: RenamePlan, c: ScrapeConfig): List<OutputFile> {
        val input = plan.input
        val dir = plan.targetDir
        val base = plan.targetName.substringBeforeLast('.')
        val out = mutableListOf<OutputFile>()
        fun add(path: String, content: Content?) { if (content != null) out += OutputFile(path, content) }
        fun poster(p: String?) = p?.let { Content.Image(img(it, c.quality.poster)) }
        fun backdrop(p: String?) = p?.let { Content.Image(img(it, c.quality.backdrop)) }
        val t = c.templates
        val nfo = NfoWriter(
            thumb = { img(it, "w185") },
            maxActors = if (c.cast) c.maxActors else 0,
        )

        if (!input.isEpisode) {
            val movie = details.movie(plan.item.match.id, plan.item.match.source)
            val ex = extras?.let { runCatching { it.forMovie(movie) }.getOrNull() }
            val logo = if (!c.logo) null else ex?.art?.logo?.let { Content.Image(it) }
                ?: tmdbLogo(MediaType.MOVIE, movie.id, movie.source)?.let { Content.Image(source.imageUrl(it, "original")) }
            if (c.nfo) add(joinPath(dir, "$base.nfo"), Content.Text(nfo.movie(movie, ex)))

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
            if (ScrapeTemplate.INFUSE in t || ScrapeTemplate.KODI_EMBY in t) addFanart(ex, dir, if (generic) "" else "$base-", ::add)
            if (ScrapeTemplate.PLEX in t) {
                if (c.poster) add(joinPath(dir, "$base.jpg"), poster(movie.posterPath))
                if (c.fanart) add(joinPath(dir, "$base-fanart.jpg"), backdrop(movie.backdropPath))
            }
            return out
        }

        // ---------- 剧集 ----------
        val show = details.tv(plan.item.match.id, plan.item.match.source)
        val showDir = plan.showDir
        val season = input.season ?: 1
        val ep = input.episode?.let { e -> details.episodes(show.id, season, show.source).firstOrNull { it.episodeNumber == e } }

        val ex = extras?.let { runCatching { it.forShow(show) }.getOrNull() }

        // 剧集级（多集共用，汇总时自动去重）
        if (c.nfo) add(joinPath(showDir, "tvshow.nfo"), Content.Text(nfo.tvShow(show, ex)))
        if (c.poster) add(joinPath(showDir, "poster.jpg"), poster(show.posterPath))
        if (c.fanart) add(joinPath(showDir, "fanart.jpg"), backdrop(show.backdropPath))
        if (c.logo) add(joinPath(showDir, "clearlogo.png"), ex?.art?.logo?.let { Content.Image(it) }
            ?: tmdbLogo(MediaType.TV, show.id, show.source)?.let { Content.Image(source.imageUrl(it, "original")) })
        if (ScrapeTemplate.INFUSE in t || ScrapeTemplate.KODI_EMBY in t) addFanart(ex, showDir, "", ::add)
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
        if (c.nfo) {
            // TMDB 没有这一集或只有「第 N 集」这种占位标题时，用 TheTVDB 的中文标题和简介补上
            val epEx = if (ep == null || ep.name.isBlank() || GENERIC_EP.matches(ep.name) || ep.overview.isNullOrBlank())
                extras?.let { runCatching { it.forEpisode(show, season, input.episode ?: 0) }.getOrNull() } else null
            add(joinPath(dir, "$base.nfo"), Content.Text(nfo.episode(show, season, input.episode ?: 0, ep, epEx)))
        }
        if (c.episodeThumb) {
            val still = backdrop(ep?.stillPath)
            if (ScrapeTemplate.INFUSE in t || ScrapeTemplate.KODI_EMBY in t) add(joinPath(dir, "$base-thumb.jpg"), still)
            if (ScrapeTemplate.PLEX in t) add(joinPath(dir, "$base.jpg"), still)
        }
        return out
    }

    /** Fanart.tv 的高清图：clearart、横幅、横版海报、光盘图（logo 已替换上面的 clearlogo） */
    /** TMDB 给的是图片路径，要拼尺寸；豆瓣 / TheTVDB / Bangumi 给的已是完整地址 */
    private fun img(path: String, size: String) = if (path.startsWith("http")) path else source.imageUrl(path, size)

    /** clearlogo 只有 TMDB 有；别的来源的 ID 不能拿去查 TMDB */
    private suspend fun tmdbLogo(type: MediaType, id: Int, from: MetaSource) =
        if (from == MetaSource.TMDB) source.logoPath(type, id) else null

    private fun addFanart(ex: ExtraInfo?, dir: String, prefix: String, add: (String, Content?) -> Unit) {
        val a = ex?.art ?: return
        a.clearart?.let { add(joinPath(dir, "${prefix}clearart.png"), Content.Image(it)) }
        a.banner?.let { add(joinPath(dir, "${prefix}banner.jpg"), Content.Image(it)) }
        a.landscape?.let { add(joinPath(dir, "${prefix}landscape.jpg"), Content.Image(it)) }
        a.disc?.let { add(joinPath(dir, "${prefix}disc.png"), Content.Image(it)) }
    }

    companion object {
        val GENERIC_EP = Regex("""第\s*\d+\s*集|Episode \d+""")
    }
}

/**
 * 把汇总好的文件写到存储（影视、音乐刮削共用）。
 * 每个目录列一次：「仅刮削缺少文件」据此跳过；同时区分新建/覆盖，
 * 撤销时只删除新建的，不会误删用户原有的 NFO 和图片。
 */
suspend fun writeOutputs(
    storage: StorageProvider,
    outputs: Map<String, Content>,
    strategy: ScrapeStrategy,
    storageId: String,
    download: suspend (String) -> ByteArray,
    onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
): ScrapeReport {
    val errors = mutableListOf<String>()
    val existing = HashMap<String, Set<String>>()
    for (dir in outputs.keys.map(::parentPath).distinct()) {
        existing[dir] = runCatching { storage.list(dir).map { it.name }.toSet() }.getOrDefault(emptySet())
    }
    val ops = mutableListOf<FileOperation>()

    var written = 0
    var skipped = 0
    val imageCache = HashMap<String, ByteArray>()   // 同一张图写多份（poster.jpg + folder.jpg）只下载一次
    suspend fun image(c: Content.Image): ByteArray {
        var last: Throwable? = null
        for (url in listOf(c.url) + c.fallbacks) {
            imageCache[url]?.let { return it }
            runCatching { download(url) }.onSuccess { imageCache[url] = it; return it }.onFailure { last = it }
        }
        throw last ?: IllegalStateException("没有可用的图片")
    }
    for ((i, entry) in outputs.entries.withIndex()) {
        val (path, content) = entry
        val alreadyThere = existing[parentPath(path)]?.contains(path.substringAfterLast('/')) == true
        val keep = when (strategy) {
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
                    is Content.Image -> image(content)
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

// ============================ NFO（Kodi 格式，Emby/Jellyfin/Infuse 通用） ============================

/**
 * @param thumb     把 TMDB 图片路径转成完整 URL（演员头像）
 * @param maxActors 0 表示不写演职人员
 */
class NfoWriter(
    private val thumb: (String) -> String? = { null },
    private val maxActors: Int = 20,
) {

    fun movie(m: TmdbMovie, ex: ExtraInfo? = null): String = doc("movie") {
        tag("title", m.title)
        tag("originaltitle", m.originalTitle)
        tag("sorttitle", m.title)
        tag("year", m.year)
        tag("premiered", m.releaseDate)
        tag("releasedate", m.releaseDate)
        tag("tagline", m.tagline)
        val plot = ex?.plot ?: m.overview
        tag("plot", plot)
        tag("outline", plot)
        tag("runtime", m.runtime)
        m.genres.forEach { tag("genre", it) }
        m.countries.forEach { tag("country", it) }
        m.studios.take(3).forEach { tag("studio", it) }
        rating(m.voteAverage, ex, m.source)
        uniqueId(m.source.key, m.id.toString(), default = true)
        uniqueId("imdb", m.imdbId)
        extraIds(ex, m.source)
        if (m.source == MetaSource.TMDB) tag("tmdbid", m.id)
        tag("imdbid", m.imdbId)
        tmdbPeople = m.source == MetaSource.TMDB
        if (maxActors > 0) {
            m.directors.forEach { person("director", it) }
            m.writers.forEach { person("credits", it) }
            actors(m.cast)
        }
    }

    fun tvShow(s: TmdbTvShow, ex: ExtraInfo? = null): String = doc("tvshow") {
        tag("title", s.name)
        tag("originaltitle", s.originalName)
        tag("showtitle", s.name)
        tag("year", s.firstAirYear)
        tag("premiered", s.firstAirDate)
        tag("status", s.status)
        tag("plot", ex?.plot ?: s.overview)
        s.genres.forEach { tag("genre", it) }
        s.countries.forEach { tag("country", it) }
        s.studios.take(3).forEach { tag("studio", it) }
        rating(s.voteAverage, ex, s.source)
        uniqueId(s.source.key, s.id.toString(), default = true)
        uniqueId("imdb", s.imdbId)
        val tvdb = ex?.tvdbId ?: s.tvdbId?.toString()
        if (s.source != MetaSource.TVDB) uniqueId("tvdb", tvdb)
        extraIds(ex, s.source)
        if (s.source == MetaSource.TMDB) tag("tmdbid", s.id)
        tag("imdbid", s.imdbId)
        tag("tvdbid", tvdb)
        tag("season", -1)
        tag("episode", -1)
        tmdbPeople = s.source == MetaSource.TMDB
        if (maxActors > 0) {
            s.creators.forEach { person("credits", it) }
            actors(s.cast)
        }
    }

    fun episode(s: TmdbTvShow, season: Int, episode: Int, e: TmdbEpisode?, extra: EpisodeExtra? = null): String = doc("episodedetails") {
        val tmdbTitle = e?.name?.takeIf { it.isNotBlank() && !Scraper.GENERIC_EP.matches(it) }
        tag("title", tmdbTitle ?: extra?.name ?: e?.name ?: "第 $episode 集")
        tag("showtitle", s.name)
        tag("season", season)
        tag("episode", episode)
        tag("plot", e?.overview?.takeIf { it.isNotBlank() } ?: extra?.plot)
        tag("aired", e?.airDate)
        tag("premiered", e?.airDate)
        if (e != null && e.voteAverage > 0) rating(e.voteAverage, from = s.source)
        tmdbPeople = s.source == MetaSource.TMDB
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
        /** 演职人员的 id 是 TMDB 人物 ID 时才写（别的来源没有） */
        var tmdbPeople = true

        /**
         * 多来源评分都写进 <ratings>；default 和 <rating> 用首选来源（豆瓣 / Bangumi 开启并优先时）。
         * @param main 主数据源的评分，名字按 from 来
         */
        fun rating(main: Double, ex: ExtraInfo? = null, from: MetaSource = MetaSource.TMDB) {
            val mainName = if (from == MetaSource.TMDB) "themoviedb" else from.key
            val list = buildList {
                if (main > 0) add(Triple(mainName, main, null as Int?))
                ex?.doubanRating?.takeIf { it > 0 }?.let { add(Triple("douban", it, ex.doubanVotes)) }
                ex?.bangumiRating?.takeIf { it > 0 }?.let { add(Triple("bangumi", it, ex.bangumiVotes)) }
            }.distinctBy { it.first }
            if (list.isEmpty()) return
            val preferred = ex?.preferredRating?.first?.takeIf { p -> list.any { it.first == p } } ?: list.first().first
            sb.append("  <ratings>\n")
            for ((name, v, votes) in list) {
                sb.append("    <rating name=\"$name\" max=\"10\"").append(if (name == preferred) " default=\"true\"" else "").append(">\n")
                sb.append("      <value>").append("%.1f".format(Locale.US, v)).append("</value>\n")
                votes?.takeIf { it > 0 }?.let { sb.append("      <votes>").append(it).append("</votes>\n") }
                sb.append("    </rating>\n")
            }
            sb.append("  </ratings>\n")
            tag("rating", "%.1f".format(Locale.US, list.first { it.first == preferred }.second))
        }
        /** 主数据源自己的 ID 已经写过，不重复 */
        fun extraIds(ex: ExtraInfo?, from: MetaSource = MetaSource.TMDB) {
            if (from != MetaSource.DOUBAN) uniqueId("douban", ex?.doubanId)
            if (from != MetaSource.BANGUMI) uniqueId("bangumi", ex?.bangumiId)
        }
        /** Emby 能识别 tmdbid 属性，直接关联到人物页 */
        fun person(name: String, p: TmdbPerson) {
            if (p.name.isBlank()) return
            sb.append(if (tmdbPeople) "  <$name tmdbid=\"${p.id}\">" else "  <$name>").append(escape(p.name)).append("</$name>\n")
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
                if (tmdbPeople) sb.append("    <tmdbid>").append(p.id).append("</tmdbid>\n")
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

/** 不联网的图片来源：只为算出文件清单，logo 一律当作有 */
object DryScrapeSource : ScrapeSource {
    override fun imageUrl(path: String, size: String) = "dry:$size$path"
    override suspend fun logoPath(type: MediaType, id: Int) = "/logo"
    override suspend fun download(url: String) = ByteArray(0)
}
