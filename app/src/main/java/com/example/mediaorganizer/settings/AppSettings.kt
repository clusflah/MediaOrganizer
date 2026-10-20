package com.example.mediaorganizer.settings

import com.example.mediaorganizer.metadata.extra.ExtraConfig
import com.example.mediaorganizer.naming.*
import com.example.mediaorganizer.scraper.ScrapeConfig
import com.example.mediaorganizer.scraper.ScrapeTemplate
import com.example.mediaorganizer.storage.CleanupMode
import com.example.mediaorganizer.storage.StorageType

/** 应用全部设置 */
data class AppSettings(
    // 多个媒体库：下面的连接字段是当前媒体库，其余的存在 libraries 里（见 Libraries.kt）
    val libraries: List<LibraryProfile> = emptyList(),
    val activeLibrary: String = "default",
    val libraryName: String = "我的媒体库",
    val lastTestAt: Long = 0,
    val lastTestOk: Boolean? = null,
    val lastTestMsg: String = "",
    // 连接：当前用哪种媒体库，各类型的参数分开保存，切换类型不会丢
    val storageType: StorageType = StorageType.WEBDAV,
    // WebDAV
    val webdavUrl: String = "http://192.168.1.10:5005",
    val username: String = "",
    val password: String = "",
    // SMB
    val smbHost: String = "",
    val smbShare: String = "",
    val smbUser: String = "",
    val smbPassword: String = "",
    val smbDomain: String = "",
    // 本机 / U 盘（SAF 文档树 URI）
    val localTreeUri: String = "",
    // 115（扫码登录拿到的 cookie）
    val pan115Cookie: String = "",
    // 阿里云盘开放平台
    val aliClientId: String = "",
    val aliClientSecret: String = "",
    val aliRefreshToken: String = "",
    val aliResourceDrive: Boolean = true,     // true 资源库，false 备份盘
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
    // 外观
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
)

enum class ThemeMode(val label: String) { SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色") }

/**
 * 把设置拍平成 key -> String，存进 DataStore Preferences。
 * 新增字段只要给默认值，旧版本存的数据也能正常读取；
 * 读到无法识别的值时回落到默认值，不会崩溃。
 */
object SettingsCodec {

    fun encode(s: AppSettings): Map<String, String> = buildMap {
        put("libraries", LibraryCodec.encode(s.allLibraries()))
        put("activeLibrary", s.activeLibrary)
        put("libraryName", s.libraryName)
        put("lastTestAt", s.lastTestAt.toString())
        put("lastTestOk", s.lastTestOk?.toString() ?: "")
        put("lastTestMsg", s.lastTestMsg)
        put("storageType", s.storageType.name)
        put("webdavUrl", s.webdavUrl)
        put("username", s.username)
        put("password", s.password)
        put("smbHost", s.smbHost)
        put("smbShare", s.smbShare)
        put("smbUser", s.smbUser)
        put("smbPassword", s.smbPassword)
        put("smbDomain", s.smbDomain)
        put("localTreeUri", s.localTreeUri)
        put("pan115Cookie", s.pan115Cookie)
        put("aliClientId", s.aliClientId)
        put("aliClientSecret", s.aliClientSecret)
        put("aliRefreshToken", s.aliRefreshToken)
        put("aliResourceDrive", s.aliResourceDrive.toString())
        put("tmdbToken", s.tmdbToken)
        put("tmdbApiBase", s.tmdbApiBase)
        put("tmdbImageBase", s.tmdbImageBase)
        put("scanDir", s.scanDir)
        put("movieRoot", s.movieRoot)
        put("tvRoot", s.tvRoot)
        put("player", s.player)
        put("autoScrape", s.autoScrape.toString())
        put("cleanupMode", s.cleanupMode.name)
        put("themeMode", s.themeMode.name)
        putAll(encodeNaming(s.movieNaming, "movie."))
        putAll(encodeNaming(s.tvNaming, "tv."))
        putAll(encodeScrape(s.scrape, "scrape."))
    }

    fun decode(m: Map<String, String>): AppSettings {
        val d = AppSettings()
        val r = Reader(m, "")
        return AppSettings(
            libraries = LibraryCodec.decode(r.str("libraries", "")),
            activeLibrary = r.str("activeLibrary", d.activeLibrary),
            libraryName = r.str("libraryName", d.libraryName),
            lastTestAt = r.str("lastTestAt", "").toLongOrNull() ?: 0,
            lastTestOk = r.str("lastTestOk", "").toBooleanStrictOrNull(),
            lastTestMsg = r.str("lastTestMsg", ""),
            storageType = r.enum("storageType", d.storageType),
            webdavUrl = r.str("webdavUrl", d.webdavUrl),
            username = r.str("username", d.username),
            password = r.str("password", d.password),
            smbHost = r.str("smbHost", d.smbHost),
            smbShare = r.str("smbShare", d.smbShare),
            smbUser = r.str("smbUser", d.smbUser),
            smbPassword = r.str("smbPassword", d.smbPassword),
            smbDomain = r.str("smbDomain", d.smbDomain),
            localTreeUri = r.str("localTreeUri", d.localTreeUri),
            pan115Cookie = r.str("pan115Cookie", d.pan115Cookie),
            aliClientId = r.str("aliClientId", d.aliClientId),
            aliClientSecret = r.str("aliClientSecret", d.aliClientSecret),
            aliRefreshToken = r.str("aliRefreshToken", d.aliRefreshToken),
            aliResourceDrive = r.bool("aliResourceDrive", d.aliResourceDrive),
            tmdbToken = r.str("tmdbToken", d.tmdbToken),
            tmdbApiBase = r.str("tmdbApiBase", d.tmdbApiBase),
            tmdbImageBase = r.str("tmdbImageBase", d.tmdbImageBase),
            scanDir = r.str("scanDir", d.scanDir),
            movieRoot = r.str("movieRoot", d.movieRoot),
            tvRoot = r.str("tvRoot", d.tvRoot),
            player = r.str("player", d.player),
            autoScrape = r.bool("autoScrape", d.autoScrape),
            cleanupMode = r.enum("cleanupMode", d.cleanupMode),
            themeMode = r.enum("themeMode", d.themeMode),
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
        // 补充来源（键名带 src. 前缀，和上面背景图的 fanart 区分）
        "${p}src.douban" to c.extra.douban.toString(),
        "${p}src.bangumi" to c.extra.bangumi.toString(),
        "${p}src.tvdb" to c.extra.tvdb.toString(),
        "${p}src.fanart" to c.extra.fanart.toString(),
        "${p}src.preferDouban" to c.extra.preferDouban.toString(),
        "${p}src.tvdbApiKey" to c.extra.tvdbApiKey,
        "${p}src.tvdbPin" to c.extra.tvdbPin,
        "${p}src.fanartApiKey" to c.extra.fanartApiKey,
        "${p}src.primary" to c.extra.primary.name,
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
            extra = ExtraConfig(
                douban = r.bool("src.douban", d.extra.douban),
                bangumi = r.bool("src.bangumi", d.extra.bangumi),
                tvdb = r.bool("src.tvdb", d.extra.tvdb),
                fanart = r.bool("src.fanart", d.extra.fanart),
                preferDouban = r.bool("src.preferDouban", d.extra.preferDouban),
                tvdbApiKey = r.str("src.tvdbApiKey", d.extra.tvdbApiKey),
                tvdbPin = r.str("src.tvdbPin", d.extra.tvdbPin),
                fanartApiKey = r.str("src.fanartApiKey", d.extra.fanartApiKey),
                primary = r.enum("src.primary", d.extra.primary),
            ),
        )
    }

    private class Reader(val m: Map<String, String>, val prefix: String) {
        fun str(k: String, d: String) = m[prefix + k] ?: d
        fun bool(k: String, d: Boolean) = m[prefix + k]?.toBooleanStrictOrNull() ?: d
        inline fun <reified E : Enum<E>> enum(k: String, d: E): E =
            m[prefix + k]?.let { v -> enumValues<E>().firstOrNull { it.name == v } } ?: d
    }
}

/** 主数据源缺的必填项（如「TMDB 令牌」），null 表示可以扫描识别 */
fun AppSettings.sourceMissing(): String? = when (scrape.extra.primary) {
    com.example.mediaorganizer.metadata.MetaSource.TMDB -> "TMDB 令牌".takeIf { tmdbToken.isBlank() }
    com.example.mediaorganizer.metadata.MetaSource.TVDB -> "TheTVDB API key".takeIf { scrape.extra.tvdbApiKey.isBlank() }
    else -> null
}
