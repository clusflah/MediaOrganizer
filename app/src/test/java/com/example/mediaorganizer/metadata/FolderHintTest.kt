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

    @Test fun explicitSeasonInFileNameIsKept() {
        assertEquals(3, se("S03E02.mkv", "老友记", "S12(2024)").second)
        assertEquals(3, se("老友记.S03E02.mkv", "老友记", "Season 12").second)
    }

    @Test fun nonSeasonFoldersAreNotSeasons() {
        assertEquals(1, se("E02.mp4", "Sherlock", "S1mple").second)
    }
}
