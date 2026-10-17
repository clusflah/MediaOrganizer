package com.example.mediaorganizer.ui

import com.example.mediaorganizer.metadata.*
import com.example.mediaorganizer.metadata.extra.EpisodeExtra
import com.example.mediaorganizer.metadata.extra.ExtraInfo
import com.example.mediaorganizer.metadata.extra.FanartArt
import com.example.mediaorganizer.metadata.extra.MetadataExtras
import com.example.mediaorganizer.naming.*
import com.example.mediaorganizer.parser.MediaNameParser
import com.example.mediaorganizer.scraper.Scraper
import com.example.mediaorganizer.scraper.ScrapeSource
import com.example.mediaorganizer.scraper.ScrapeTemplate
import com.example.mediaorganizer.settings.AppSettings
import com.example.mediaorganizer.storage.RemoteFile
import com.example.mediaorganizer.storage.NoOpStorage
import com.example.mediaorganizer.storage.normalizePath
import java.io.InputStream

/**
 * 设置页的实时预览：用一个内置示例文件走一遍真正的命名和刮削流程（不联网），
 * 得到的路径和附属文件清单与实际整理时一致。
 */
data class SamplePreview(
    val original: String,
    val libraryRoot: String,
    val targetPath: String,       // 完整目标路径
    val files: List<String>,      // 刮削会写入的文件，相对库根目录
    val warnings: List<String>,
)

object SamplePreviews {
    private const val TV_FILE = "老友记.1994.S02E03.2160p.Dolby.BluRay.DC.PROPER.x265.AC3-EVO.mp4"
    private const val MOVIE_FILE = "盗梦空间.2010.2160p.Dolby.BluRay.DC.PROPER.x265.AC3-EVO.mp4"

    suspend fun build(s: AppSettings, isTv: Boolean): SamplePreview {
        val name = if (isTv) TV_FILE else MOVIE_FILE
        val root = normalizePath(if (isTv) s.tvRoot else s.movieRoot)
        val file = RemoteFile(path = "/下载/$name", name = name, isDirectory = false, size = 4L shl 30)
        val match = if (isTv) TmdbSearchResult(1668, MediaType.TV, "老友记", "Friends", 1994, null, "/p", "/b", 100.0)
        else TmdbSearchResult(27205, MediaType.MOVIE, "盗梦空间", "Inception", 2010, null, "/p", "/b", 100.0)

        val plan = RenamePlanner(FakeDetails, LibraryRoots(s.movieRoot, s.tvRoot))
            .plan(listOf(RenameItem(file, MediaNameParser.parse(name), match)), s.movieNaming, s.tvNaming)
            .single()
        val extras = if (s.scrape.extra.fanart) FakeExtras else null
        val files = Scraper(NoOpStorage, FakeDetails, FakeSource, extras = extras).collectOutputs(plan, s.scrape)
            .map { normalizePath(it.path) }.distinct()
            .map { it.removePrefix("$root/") }

        return SamplePreview(
            original = name,
            libraryRoot = root,
            targetPath = plan.targetPath,
            files = files,
            warnings = warnings(s, isTv),
        )
    }

    /** 容易踩坑的设置组合 */
    private fun warnings(s: AppSettings, isTv: Boolean): List<String> = buildList {
        val c = if (isTv) s.tvNaming else s.movieNaming
        val tagOff = c.tmdbTagMode == TmdbTagMode.OFF || (!c.tmdbTagOnFolder && !c.tmdbTagOnFile)
        if (c.yearStyle == YearStyle.NONE && tagOff)
            add("没有年份也没有 TMDB 标签：同名不同年份的作品（如两部《沙丘》）会整理到同一个位置，后一个会因重名被跳过。")
        if (c.tmdbTagMode == TmdbTagMode.TAG_ONLY)
            add("「只加标签，不改名不移动」模式下文件留在原目录，只在原文件名后加标签，不会进影视库。")
        if (!isTv && !c.movieFolder)
            add("电影不放独立文件夹时，所有电影都直接放在电影库根目录，海报等只能命名为「文件名-poster.jpg」，部分播放器认不出来。")
        if (isTv && c.seasonFolderFormat.isEmpty() && ScrapeTemplate.PLEX in s.scrape.templates)
            add("不建季文件夹时，Plex 模板不会生成季海报。")
        if (s.scrape.templates.size > 1)
            add("同时勾选了 ${s.scrape.templates.size} 个刮削模板，同一张海报会以多个文件名各存一份。")
    }

    private object FakeDetails : MediaDetails {
        override suspend fun movie(id: Int) = TmdbMovie(
            id = id, title = "盗梦空间", originalTitle = "Inception", year = 2010, releaseDate = "2010-07-15",
            overview = null, runtime = 148, genres = emptyList(), imdbId = null, voteAverage = 8.4,
            posterPath = "/p", backdropPath = "/b",
        )

        override suspend fun tv(id: Int) = TmdbTvShow(
            id = id, name = "老友记", originalName = "Friends", firstAirYear = 1994, firstAirDate = "1994-09-22",
            overview = null, genres = emptyList(), imdbId = null, voteAverage = 8.4, posterPath = "/p", backdropPath = "/b",
            seasons = (1..10).map { TmdbSeasonSummary(it, "第 $it 季", 1993 + it, 24, "/s$it") },
        )

        override suspend fun episodes(tvId: Int, season: Int) = (1..24).map {
            TmdbEpisode(season, it, if (season == 2 && it == 3) "罗斯的新女友" else "第 $it 集", null, null, "/e", 8.0)
        }
    }

    private object FakeExtras : MetadataExtras {
        private val art = FanartArt("p:logo", "p:clearart", "p:banner", "p:landscape", "p:disc")
        override suspend fun forMovie(m: TmdbMovie) = ExtraInfo(art = art)
        override suspend fun forShow(s: TmdbTvShow) = ExtraInfo(art = art.copy(disc = null))
        override suspend fun forEpisode(s: TmdbTvShow, season: Int, episode: Int): EpisodeExtra? = null
    }

    private object FakeSource : ScrapeSource {
        override fun imageUrl(path: String, size: String) = "preview:$size$path"
        override suspend fun logoPath(type: MediaType, id: Int) = "/logo"
        override suspend fun download(url: String) = ByteArray(0)
    }

}
