package com.example.mediaorganizer.metadata.extra

import com.example.mediaorganizer.metadata.TmdbMovie
import com.example.mediaorganizer.metadata.TmdbTvShow
import com.example.mediaorganizer.scraper.NfoWriter
import com.example.mediaorganizer.settings.AppSettings
import com.example.mediaorganizer.ui.SamplePreviews
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Socket

class ExtraSourcesTest {
    @Before fun proxy() {
        val up = runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", 7890), 500) } }.isSuccess
        if (up) listOf("http", "https").forEach { System.setProperty("$it.proxyHost", "127.0.0.1"); System.setProperty("$it.proxyPort", "7890") }
    }

    private val inception = TmdbMovie(
        id = 27205, title = "盗梦空间", originalTitle = "Inception", year = 2010, releaseDate = "2010-07-15",
        overview = "TMDB 简介", runtime = 148, genres = listOf("科幻"), imdbId = "tt1375666", voteAverage = 8.4,
        posterPath = null, backdropPath = null,
    )

    @Test fun doubanLive() = runBlocking {
        val ex = runCatching { OnlineExtras(ExtraConfig(douban = true)).forMovie(inception) }.getOrNull()
        assumeTrue("网络不可用，跳过", ex != null && ex.doubanId != null)
        println("豆瓣：id=${ex!!.doubanId} 评分=${ex.doubanRating} 人数=${ex.doubanVotes} 简介=${ex.doubanPlot?.take(30)}")
        assertTrue(ex.doubanRating!! > 7)
        assertTrue(ex.doubanPlot!!.isNotBlank())
    }

    @Test fun bangumiLive() = runBlocking {
        val show = TmdbTvShow(
            id = 1429, name = "进击的巨人", originalName = "進撃の巨人", firstAirYear = 2013, firstAirDate = "2013-04-07",
            overview = "", genres = listOf("动画"), imdbId = null, voteAverage = 8.7, posterPath = null, backdropPath = null,
            seasons = emptyList(), tvdbId = 267440,
        )
        val ex = runCatching { OnlineExtras(ExtraConfig(bangumi = true)).forShow(show) }.getOrNull()
        assumeTrue("Bangumi 不可达，跳过", ex?.bangumiId != null)
        println("Bangumi：id=${ex!!.bangumiId} 评分=${ex.bangumiRating} 简介=${ex.bangumiPlot?.take(30)}")
        assertEquals("267440", ex.tvdbId)
    }

    @Test fun nfoUsesPreferredSource() {
        val ex = ExtraInfo(doubanId = "3541415", doubanRating = 9.4, doubanVotes = 2000000, doubanPlot = "豆瓣简介", preferDouban = true)
        val xml = NfoWriter().movie(inception, ex)
        assertTrue(xml, xml.contains("<plot>豆瓣简介</plot>"))
        assertTrue(xml, xml.contains("<rating name=\"douban\" max=\"10\" default=\"true\">"))
        assertTrue(xml, xml.contains("<rating name=\"themoviedb\" max=\"10\">"))
        assertTrue(xml, xml.contains("<rating>9.4</rating>"))
        assertTrue(xml, xml.contains("<uniqueid type=\"douban\">3541415</uniqueid>"))

        val tmdbFirst = NfoWriter().movie(inception, ex.copy(preferDouban = false))
        assertTrue(tmdbFirst.contains("<plot>TMDB 简介</plot>") && tmdbFirst.contains("<rating>8.4</rating>"))
    }

    @Test fun previewListsFanartFiles() = runBlocking {
        val s = AppSettings(movieRoot = "/影视库/电影", tvRoot = "/影视库/剧集")
        val on = s.copy(scrape = s.scrape.copy(extra = ExtraConfig(fanart = true)))
        val files = SamplePreviews.build(on, isTv = false).files
        println(files)
        assertTrue(files.any { it.endsWith("clearart.png") } && files.any { it.endsWith("disc.png") })
        assertTrue(SamplePreviews.build(s, isTv = false).files.none { it.endsWith("clearart.png") })
    }
}
