package com.example.mediaorganizer.ui

import com.example.mediaorganizer.naming.PlayerPresets
import com.example.mediaorganizer.naming.TmdbTagMode
import com.example.mediaorganizer.naming.YearStyle
import com.example.mediaorganizer.scraper.ScrapeTemplate
import com.example.mediaorganizer.settings.AppSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SamplePreviewTest {
    private val base = AppSettings(movieRoot = "/影视库/电影", tvRoot = "/影视库/剧集")

    @Test fun tvPreviewUsesLibraryAndSeasonFolder() = runBlocking {
        val p = SamplePreviews.build(base, isTv = true)
        println(p)
        assertTrue(p.targetPath, p.targetPath.startsWith("/影视库/剧集/老友记 (1994)"))
        assertTrue(p.targetPath, p.targetPath.contains("/Season 02/"))
        assertTrue(p.files.toString(), p.files.any { it.endsWith("tvshow.nfo") })
    }

    @Test fun movieWithoutOwnFolderGoesToLibraryRoot() = runBlocking {
        val s = base.copy(movieNaming = base.movieNaming.copy(movieFolder = false))
        val p = SamplePreviews.build(s, isTv = false)
        println(p)
        assertEquals("/影视库/电影", p.targetPath.substringBeforeLast('/'))
        assertTrue(p.files.toString(), p.files.none { it.endsWith("/poster.jpg") || it == "poster.jpg" })
    }

    @Test fun tagOnlyStaysInPlace() = runBlocking {
        val s = base.copy(movieNaming = base.movieNaming.copy(tmdbTagMode = TmdbTagMode.TAG_ONLY))
        val p = SamplePreviews.build(s, isTv = false)
        println(p)
        assertTrue(p.targetPath, p.targetPath.startsWith("/下载/"))
    }

    @Test fun warnsOnMissingYearAndTag() = runBlocking {
        val s = base.copy(movieNaming = base.movieNaming.copy(yearStyle = YearStyle.NONE, tmdbTagMode = TmdbTagMode.OFF))
        val p = SamplePreviews.build(s, isTv = false)
        assertTrue(p.warnings.toString(), p.warnings.any { it.contains("同名不同年份") })
    }

    @Test fun multipleTemplatesListMoreFiles() = runBlocking {
        val one = SamplePreviews.build(base, isTv = false).files
        val many = SamplePreviews.build(
            base.copy(scrape = base.scrape.copy(templates = ScrapeTemplate.entries.toSet())), isTv = false,
        ).files
        println(many)
        assertTrue("$one vs $many", many.size > one.size)
    }

    @Test fun allPresetsBuild() = runBlocking {
        for ((name, cfg) in PlayerPresets.all) for (tv in listOf(true, false)) {
            val p = SamplePreviews.build(base.copy(movieNaming = cfg, tvNaming = cfg), tv)
            println("$name tv=$tv -> ${p.targetPath}")
        }
    }
}
