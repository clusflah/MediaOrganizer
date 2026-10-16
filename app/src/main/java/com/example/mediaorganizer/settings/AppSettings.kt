package com.example.mediaorganizer.settings

import com.example.mediaorganizer.naming.*
import com.example.mediaorganizer.scraper.ScrapeConfig
import com.example.mediaorganizer.scraper.ScrapeTemplate
import com.example.mediaorganizer.storage.CleanupMode

/** 应用全部设置 */
data class AppSettings(
    // 连接
    val webdavUrl: String = "http://192.168.1.10:5005",
    val username: String = "",
    val password: String = "",
    // TMDB
    val tmdbToken: String = "",
    val tmdbApiBase: String = "https://api.themoviedb.org/3",
    val tmdbImageBase: String = "https://image.tmdb.org/t/p",
    // 目录
    val scanDir: String = "/下载",
    val movieRoot: String = "/影视库/电影",
    val tvRoot: String = "/影视库/剧集",
    // 命名
    val player: String = "Plex",
    val movieNaming: NamingConfig = PlayerPresets.PLEX,
    val tvNaming: NamingConfig = PlayerPresets.PLEX,
    // 刮削
    val scrape: ScrapeConfig = ScrapeConfig(),
    val autoScrape: Boolean = true,
    // 整理后
    val cleanupMode: CleanupMode = CleanupMode.LEFTOVERS,
)

/**
 * 把设置拍平成 key -> String，存进 DataStore Preferences。
 * 新增字段只要给默认值，旧版本存的数据也能正常读取；
 * 读到无法识别的值时回落到默认值，不会崩溃。
 */
object SettingsCodec {

    fun encode(s: AppSettings): Map<String, String> = buildMap {
        put("webdavUrl", s.webdavUrl)
        put("username", s.username)
        put("password", s.password)
        put("tmdbToken", s.tmdbToken)
        put("tmdbApiBase", s.tmdbApiBase)
        put("tmdbImageBase", s.tmdbImageBase)
        put("scanDir", s.scanDir)
        put("movieRoot", s.movieRoot)
        put("tvRoot", s.tvRoot)
        put("player", s.player)
        put("autoScrape", s.autoScrape.toString())
        put("cleanupMode", s.cleanupMode.name)
        putAll(encodeNaming(s.movieNaming, "movie."))
        putAll(encodeNaming(s.tvNaming, "tv."))
        putAll(encodeScrape(s.scrape, "scrape."))
    }

    fun decode(m: Map<String, String>): AppSettings {
        val d = AppSettings()
        val r = Reader(m, "")
        return AppSettings(
            webdavUrl = r.str("webdavUrl", d.webdavUrl),
            username = r.str("username", d.username),
            password = r.str("password", d.password),
            tmdbToken = r.str("tmdbToken", d.tmdbToken),
            tmdbApiBase = r.str("tmdbApiBase", d.tmdbApiBase),
            tmdbImageBase = r.str("tmdbImageBase", d.tmdbImageBase),
            scanDir = r.str("scanDir", d.scanDir),
            movieRoot = r.str("movieRoot", d.movieRoot),
            tvRoot = r.str("tvRoot", d.tvRoot),
            player = r.str("player", d.player),
            autoScrape = r.bool("autoScrape", d.autoScrape),
            cleanupMode = r.enum("cleanupMode", d.cleanupMode),
            movieNaming = decodeNaming(m, "movie.", d.movieNaming),
            tvNaming = decodeNaming(m, "tv.", d.tvNaming),
            scrape = decodeScrape(m, "scrape.", d.scrape),
        )
    }

    // ---------- 命名规则 ----------

    private fun encodeNaming(c: NamingConfig, p: String) = mapOf(
        "${p}titleLanguage" to c.titleLanguage.name,
        "${p}multiLanguage" to c.multiLanguage.toString(),
        "${p}yearStyle" to c.yearStyle.name,
        "${p}episodeYearSource" to c.episodeYearSource.name,
        "${p}seasonEpisodeStyle" to c.seasonEpisodeStyle.name,
        "${p}separator" to c.separator.name,
        "${p}includeEpisodeTitle" to c.includeEpisodeTitle.toString(),
        "${p}extraInfo" to c.extraInfo.toString(),
        "${p}imdbRating" to c.imdbRating.toString(),
        "${p}keepReleaseGroup" to c.keepReleaseGroup.toString(),
        "${p}tmdbTagMode" to c.tmdbTagMode.name,
        "${p}tagBracket" to c.tmdbTagFormat.bracket.name,
        "${p}tagKey" to c.tmdbTagFormat.key,
        "${p}tagJoiner" to c.tmdbTagFormat.joiner,
        "${p}tmdbTagOnFile" to c.tmdbTagOnFile.toString(),
        "${p}tmdbTagOnFolder" to c.tmdbTagOnFolder.toString(),
        "${p}movieFolder" to c.movieFolder.toString(),
        "${p}folderSameAsFile" to c.folderSameAsFile.toString(),
        "${p}seasonFolderFormat" to c.seasonFolderFormat,
        "${p}order" to c.order.joinToString(",") { it.name },
    )

    private fun decodeNaming(m: Map<String, String>, p: String, d: NamingConfig): NamingConfig {
        val r = Reader(m, p)
        val order = r.str("order", "").split(',').mapNotNull { n -> Part.entries.firstOrNull { it.name == n } }.distinct()
        return NamingConfig(
            titleLanguage = r.enum("titleLanguage", d.titleLanguage),
            multiLanguage = r.bool("multiLanguage", d.multiLanguage),
            yearStyle = r.enum("yearStyle", d.yearStyle),
            episodeYearSource = r.enum("episodeYearSource", d.episodeYearSource),
            seasonEpisodeStyle = r.enum("seasonEpisodeStyle", d.seasonEpisodeStyle),
            separator = r.enum("separator", d.separator),
            includeEpisodeTitle = r.bool("includeEpisodeTitle", d.includeEpisodeTitle),
            extraInfo = r.bool("extraInfo", d.extraInfo),
            imdbRating = r.bool("imdbRating", d.imdbRating),
            keepReleaseGroup = r.bool("keepReleaseGroup", d.keepReleaseGroup),
            tmdbTagMode = r.enum("tmdbTagMode", d.tmdbTagMode),
            tmdbTagFormat = TmdbTagFormat(
                bracket = r.enum("tagBracket", d.tmdbTagFormat.bracket),
                key = r.str("tagKey", d.tmdbTagFormat.key).takeIf { it == "tmdb" || it == "tmdbid" } ?: d.tmdbTagFormat.key,
                joiner = r.str("tagJoiner", d.tmdbTagFormat.joiner).takeIf { it == "-" || it == "=" } ?: d.tmdbTagFormat.joiner,
            ),
            tmdbTagOnFile = r.bool("tmdbTagOnFile", d.tmdbTagOnFile),
            tmdbTagOnFolder = r.bool("tmdbTagOnFolder", d.tmdbTagOnFolder),
            movieFolder = r.bool("movieFolder", d.movieFolder),
            folderSameAsFile = r.bool("folderSameAsFile", d.folderSameAsFile),
            seasonFolderFormat = r.str("seasonFolderFormat", d.seasonFolderFormat),
            // 新版本增加了 Part 时，把缺的补到末尾
            order = if (order.isEmpty()) d.order else order + Part.entries.filter { it !in order },
        )
    }

    // ---------- 刮削 ----------

    private fun encodeScrape(c: ScrapeConfig, p: String) = mapOf(
        "${p}templates" to c.templates.joinToString(",") { it.name },
        "${p}strategy" to c.strategy.name,
        "${p}nfo" to c.nfo.toString(),
        "${p}poster" to c.poster.toString(),
        "${p}fanart" to c.fanart.toString(),
        "${p}logo" to c.logo.toString(),
        "${p}seasonPoster" to c.seasonPoster.toString(),
        "${p}episodeThumb" to c.episodeThumb.toString(),
        "${p}quality" to c.quality.name,
        "${p}cast" to c.cast.toString(),
        "${p}maxActors" to c.maxActors.toString(),
    )

    private fun decodeScrape(m: Map<String, String>, p: String, d: ScrapeConfig): ScrapeConfig {
        val r = Reader(m, p)
        val templates = r.str("templates", "").split(',')
            .mapNotNull { n -> ScrapeTemplate.entries.firstOrNull { it.name == n } }.toSet()
        return ScrapeConfig(
            templates = templates.ifEmpty { d.templates },
            strategy = r.enum("strategy", d.strategy),
            nfo = r.bool("nfo", d.nfo),
            poster = r.bool("poster", d.poster),
            fanart = r.bool("fanart", d.fanart),
            logo = r.bool("logo", d.logo),
            seasonPoster = r.bool("seasonPoster", d.seasonPoster),
            episodeThumb = r.bool("episodeThumb", d.episodeThumb),
            quality = r.enum("quality", d.quality),
            cast = r.bool("cast", d.cast),
            maxActors = r.str("maxActors", "").toIntOrNull()?.coerceIn(0, 100) ?: d.maxActors,
        )
    }

    private class Reader(val m: Map<String, String>, val prefix: String) {
        fun str(k: String, d: String) = m[prefix + k] ?: d
        fun bool(k: String, d: Boolean) = m[prefix + k]?.toBooleanStrictOrNull() ?: d
        inline fun <reified E : Enum<E>> enum(k: String, d: E): E =
            m[prefix + k]?.let { v -> enumValues<E>().firstOrNull { it.name == v } } ?: d
    }
}
