package com.example.mediaorganizer.metadata

import com.example.mediaorganizer.metadata.MediaScanner.Companion.parseWithFolderHint
import org.junit.Assert.assertEquals
import org.junit.Test

class FolderHintTest {
    private fun se(file: String, vararg dirs: String) =
        parseWithFolderHint(file, dirs.toList()).let { Triple(it.title, it.season, it.episode) }

    @Test fun seasonFolderWithYearAndNotes() {
        assertEquals(Triple("暗芝居", 12, 2), se("E02.mp4", "downloads", "影视", "电视剧", "暗芝居", "S12(2024)[13集全]"))
        assertEquals(Triple("暗芝居", 12, 3), se("03.mp4", "暗芝居", "S12(2024)[13集全]"))
    }

    @Test fun seasonFolderWinsOverDefaultSeasonOne() {
        assertEquals(Triple("老友记", 2, 5), se("老友记.E05.mkv", "老友记", "Season 2 1080p"))
        assertEquals(Triple("老友记", 2, 5), se("EP05.mkv", "老友记", "第二季 1080p"))
        assertEquals(Triple("老友记", 12, 1), se("第1集.mkv", "老友记", "第十二季"))
    }

    @Test fun bracketEpisodeInSeasonFolder() {
        val dirs = arrayOf("downloads", "影视", "电视剧", "暗芝居", "Season13")
        assertEquals(Triple("Yami Shibai", 13, 1), se("[UHA-WINGS&YUI-7][Yami Shibai 13][01][x264 1080p][CHS](1).mp4", *dirs))
        assertEquals(Triple("Yami Shibai", 13, 12), se("[UHA-WINGS&YUI-7][Yami Shibai 13][12][x264 1080p][CHS].mp4", *dirs))
        // 不在季文件夹里：片名里的数字保留，集数照样认
        assertEquals(Triple("Mob Psycho 100", 1, 5), se("[Group][Mob Psycho 100][05v2][1080p].mkv", "动漫"))
    }

    @Test fun explicitSeasonInFileNameIsKept() {
        assertEquals(3, se("S03E02.mkv", "老友记", "S12(2024)").second)
        assertEquals(3, se("老友记.S03E02.mkv", "老友记", "Season 12").second)
    }

    @Test fun nonSeasonFoldersAreNotSeasons() {
        assertEquals(1, se("E02.mp4", "Sherlock", "S1mple").second)
    }

    private fun hint(file: String, dirs: List<String>, videos: Int = 1) = parseWithFolderHint(file, dirs, videos)

    @Test fun numberOnlyEpisodeInShowFolderUnderTvCategory() {
        val p = hint("07.mp4", listOf("downloads", "影视", "电视剧", "爱情怎么翻译？（2026）"))
        assertEquals("爱情怎么翻译？", p.title)
        assertEquals(1, p.season)
        assertEquals(7, p.episode)
    }

    @Test fun numberOnlyEpisodeWhenFolderHasSeveralVideos() {
        assertEquals(3, hint("03.mp4", listOf("Show Name"), videos = 5).episode)
        assertEquals(null, hint("03.mp4", listOf("Show Name"), videos = 1).episode)
    }

    @Test fun trailingNumberIsEpisodeUnderTvCategory() {
        val p = hint("爱情怎么翻译 07.mp4", listOf("电视剧", "爱情怎么翻译"))
        assertEquals(Triple("爱情怎么翻译", 1, 7), Triple(p.title, p.season, p.episode))
    }

    @Test fun movieUsesFolderWhenFileNameIsWeak() {
        hint("movie.mkv", listOf("downloads", "小丑 (2019)")).let { assertEquals("小丑" to 2019, it.title to it.year) }
        hint("01.mp4", listOf("电影", "小丑 (2019) 1080p")).let {
            assertEquals("小丑" to 2019, it.title to it.year)
            assertEquals(null, it.episode)
        }
        hint("Joker.1080p.mkv", listOf("电影", "小丑 (2019)")).let { assertEquals("小丑" to "1080p", it.title to it.resolution) }
    }

    @Test fun movieKeepsInformativeFileName() {
        assertEquals("Joker", hint("Joker.2019.mkv", listOf("电影", "小丑")).title)
        assertEquals("movie", hint("movie.mkv", listOf("合集 (2019)"), videos = 3).title)   // 多部电影放在一起的不借用
    }
}
