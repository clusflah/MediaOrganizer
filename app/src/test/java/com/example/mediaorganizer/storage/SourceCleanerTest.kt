package com.example.mediaorganizer.storage

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream

/** 内存里的假存储：files 是所有文件路径，目录由路径推出来 */
private class MemStorage(vararg paths: String) : StorageProvider {
    val files = paths.toMutableSet()
    val dirs = mutableSetOf<String>()

    init { paths.forEach { var d = parentPath(it); while (d != "/") { dirs += d; d = parentPath(d) } } }

    fun mkdir(d: String) { dirs += d }
    fun has(p: String) = p in files || p in dirs

    override val displayName = "mem"
    override suspend fun connect() = Unit
    override suspend fun list(dir: String): List<RemoteFile> {
        val d = normalizePath(dir)
        return (files.filter { parentPath(it) == d }.map { RemoteFile(it, it.substringAfterLast('/'), false, size = 1) } +
            dirs.filter { parentPath(it) == d && it != d }.map { RemoteFile(it, it.substringAfterLast('/'), true) })
    }
    override suspend fun exists(path: String) = has(normalizePath(path))
    override suspend fun mkdirs(path: String) { mkdir(normalizePath(path)) }
    override suspend fun rename(file: RemoteFile, newName: String) = file
    override suspend fun move(file: RemoteFile, targetDir: String) = file
    override suspend fun upload(targetPath: String, data: InputStream, size: Long) = Unit
    override suspend fun delete(file: RemoteFile) {
        files.removeAll { it == file.path || it.startsWith(file.path + "/") }
        dirs.removeAll { it == file.path || it.startsWith(file.path + "/") }
    }
}

class SourceCleanerTest {
    private val scan = "/downloads"
    private val movies = "/downloads/影视/电影"
    private val tv = "/downloads/影视/电视剧"

    private fun cleaner(s: StorageProvider, mode: CleanupMode = CleanupMode.LEFTOVERS, extra: List<String> = emptyList()) =
        SourceCleaner(s, mode, scan, listOf(movies, tv) + extra)

    @Test fun removesFolderWithOnlyLeftovers() = runBlocking {
        val s = MemStorage("$movies/小丑(2019)/小丑.nfo", "$movies/小丑(2019)/poster.jpg", "$movies/小丑(2019)/@eaDir/x.jpg")
        s.mkdir("$movies/小丑(2019)/@eaDir")
        val r = cleaner(s).cleanAfterMove(listOf("$movies/小丑(2019)"))
        assertEquals(1, r.removed.size)
        assertFalse(s.has("$movies/小丑(2019)"))
        assertTrue("库目录本身不能删", s.has(movies))
    }

    @Test fun keepsFolderWithAnotherVideo() = runBlocking {
        val s = MemStorage("$movies/A/other.mkv", "$movies/A/a.nfo", "$movies/B/sub/VIDEO_TS.VOB")
        val r = cleaner(s).cleanAfterMove(listOf("$movies/A", "$movies/B"))
        assertEquals(0, r.removed.size)
        assertEquals(2, r.kept.size)
        assertTrue(s.has("$movies/A/other.mkv") && s.has("$movies/B/sub/VIDEO_TS.VOB"))
    }

    @Test fun removesFolderWithoutVideoIncludingSubtitlesAndUnknownFiles() = runBlocking {
        val s = MemStorage("/downloads/A/a.chs.srt", "/downloads/A/a.ass", "/downloads/A/readme.rar")
        cleaner(s).cleanAfterMove(listOf("/downloads/A"))
        assertFalse(s.has("/downloads/A"))
    }

    @Test fun keepsFolderStillDownloading() = runBlocking {
        val s = MemStorage("/downloads/A/a.nfo", "/downloads/A/movie.mkv.!qb")
        val r = cleaner(s).cleanAfterMove(listOf("/downloads/A"))
        assertTrue(s.has("/downloads/A/movie.mkv.!qb"))
        assertEquals(1, r.kept.size)
    }

    @Test fun samplesCountAsLeftovers() = runBlocking {
        val s = MemStorage("/downloads/X.2010/x-sample.mkv", "/downloads/X.2010/info.txt")
        cleaner(s).cleanAfterMove(listOf("/downloads/X.2010"))
        assertFalse(s.has("/downloads/X.2010"))
    }

    @Test fun emptyOnlyKeepsNfoButRemovesSystemJunk() = runBlocking {
        val s = MemStorage("/downloads/A/a.nfo", "/downloads/B/.DS_Store", "/downloads/B/Thumbs.db")
        val r = cleaner(s, CleanupMode.EMPTY_ONLY).cleanAfterMove(listOf("/downloads/A", "/downloads/B"))
        assertTrue(s.has("/downloads/A/a.nfo"))
        assertFalse(s.has("/downloads/B"))
        assertEquals(1, r.removed.size)
    }

    @Test fun cascadesToParentButStopsAtScanRoot() = runBlocking {
        val s = MemStorage("/downloads/pack/Movie.2010/movie.nfo")
        cleaner(s).cleanAfterMove(listOf("/downloads/pack/Movie.2010"))
        assertFalse(s.has("/downloads/pack"))
        assertTrue(s.has("/downloads") || s.dirs.isEmpty())   // 扫描目录本身绝不删
        assertFalse(cleaner(s).isRemovable("/downloads"))
    }

    @Test fun neverTouchesProtectedOrOutside() {
        val s = MemStorage()
        val c = cleaner(s, extra = listOf("$movies/新片 (2020)"))
        assertFalse(c.isRemovable(movies))
        assertFalse(c.isRemovable("/downloads/影视"))          // 库目录的上级
        assertFalse(c.isRemovable("$movies/新片 (2020)"))      // 本次目标
        assertFalse(c.isRemovable("/other/place"))             // 扫描目录之外
        assertTrue(c.isRemovable("$movies/小丑(2019)"))
    }

    @Test fun findLeftoversInsideLibrary() = runBlocking {
        val s = MemStorage(
            "$movies/小丑(2019)/movie.nfo", "$movies/小丑(2019)/fanart.jpg",
            "$movies/小丑 (2019) {tmdb-475557}/小丑 (2019).mkv",
            "$tv/老友记/Season 01/old.nfo",
            "$tv/老友记 (1994) {tmdb-1668}/Season 01/老友记 S01E01.mkv",
        )
        val found = cleaner(s).findLeftovers().map { it.dir }.toSet()
        assertEquals(setOf("$movies/小丑(2019)", "$tv/老友记"), found)
    }
}
