package com.example.mediaorganizer

import org.junit.Assert.assertEquals
import org.junit.Test

class FolderBatchTest {
    private fun files(vararg names: String, eps: List<Int?>? = null) =
        names.sortedWith(NaturalOrder).mapIndexed { i, n -> FolderFile(n, n, eps?.get(i)) }

    @Test fun naturalOrderSortsNumbersByValue() {
        assertEquals(listOf("1.mp4", "2.mp4", "10.mp4", "E2.mkv", "E10.mkv"),
            listOf("10.mp4", "E10.mkv", "2.mp4", "E2.mkv", "1.mp4").sortedWith(NaturalOrder))
    }

    @Test fun episodesFollowFileOrder() {
        val r = folderEpisodes(files("a.mp4", "b.mp4", "c.mp4", "d.mp4"), "b.mp4", 12, 2, keepOwn = false)
        assertEquals(listOf("a.mp4" to 1, "b.mp4" to 2, "c.mp4" to 3, "d.mp4" to 4), r.map { it.name to it.episode })
        assertEquals(setOf(12), r.map { it.season }.toSet())
    }

    @Test fun filesBeforeEpisodeOneAreLeftOut() {
        val r = folderEpisodes(files("01.mp4", "02.mp4", "03.mp4"), "02.mp4", 1, 1, keepOwn = false)
        assertEquals(listOf("02.mp4" to 1, "03.mp4" to 2), r.map { it.name to it.episode })
    }

    @Test fun onlySeasonChangedKeepsRecognizedEpisodes() {
        // 缺了第 3 集：按各自认出的集数，不按顺序递推
        val r = folderEpisodes(files("E01.mp4", "E02.mp4", "E04.mp4", eps = listOf(1, 2, 4)), "E01.mp4", 12, 1, keepOwn = true)
        assertEquals(listOf(1, 2, 4), r.map { it.episode })
        assertEquals(setOf(12), r.map { it.season }.toSet())
    }
}
