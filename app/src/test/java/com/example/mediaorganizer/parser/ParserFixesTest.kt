package com.example.mediaorganizer.parser

import com.example.mediaorganizer.metadata.MediaMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParserFixesTest {
    @Test fun futureYearInTitleIsNotTheYear() {
        val p = MediaNameParser.parse("银翼杀手2049 (2017) {tmdb-335984}.mkv")
        assertEquals(2017, p.year)
        assertEquals("银翼杀手2049", p.title)
    }

    @Test fun englishDottedFutureYear() {
        val p = MediaNameParser.parse("Blade.Runner.2049.2017.2160p.BluRay.x265.mkv")
        assertEquals(2017, p.year)
    }

    @Test fun numericTitlesStillWork() {
        assertEquals(2019, MediaNameParser.parse("1917.2019.1080p.mkv").year)
        assertEquals(2004, MediaNameParser.parse("2046.2004.1080p.mkv").year)
    }

    @Test fun tmdbTagFromPath() {
        assertEquals(335984, MediaMatcher.tagId("/影视/电影/银翼杀手2049 (2017) {tmdb-335984}/银翼杀手2049 (2017).mkv"))
        assertEquals(1668, MediaMatcher.tagId("/tv/Friends [tmdbid=1668]/Season 02/S02E03.mkv"))
        assertEquals(42, MediaMatcher.tagId("/a {tmdb-1}/b {tmdb-42}/c.mkv"))   // 离文件最近的
        assertNull(MediaMatcher.tagId("/downloads/Some.Movie.2010.mkv"))
    }
}
