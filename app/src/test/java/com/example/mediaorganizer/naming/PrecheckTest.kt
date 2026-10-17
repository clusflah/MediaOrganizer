package com.example.mediaorganizer.naming

import com.example.mediaorganizer.metadata.MediaType
import com.example.mediaorganizer.metadata.TmdbSearchResult
import com.example.mediaorganizer.parser.MediaNameParser
import com.example.mediaorganizer.storage.RemoteFile
import com.example.mediaorganizer.storage.StorageException
import com.example.mediaorganizer.storage.StorageProvider
import com.example.mediaorganizer.storage.parentPath
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream

private class Mem(vararg files: Pair<String, Long>) : StorageProvider {
    val f = files.toMap()
    val dirs = f.keys.flatMap { p -> generateSequence(parentPath(p)) { if (it == "/") null else parentPath(it) }.toList() }.toSet()
    override val displayName = "mem"
    override suspend fun connect() = Unit
    override suspend fun list(dir: String): List<RemoteFile> {
        if (dir !in dirs) throw StorageException(404, "nope")
        return f.filterKeys { parentPath(it) == dir }.map { (p, sz) -> RemoteFile(p, p.substringAfterLast('/'), false, sz) }
    }
    override suspend fun exists(path: String) = path in f || path in dirs
    override suspend fun mkdirs(path: String) = Unit
    override suspend fun rename(file: RemoteFile, newName: String) = file
    override suspend fun move(file: RemoteFile, targetDir: String) = file
    override suspend fun upload(targetPath: String, data: InputStream, size: Long) = Unit
    override suspend fun delete(file: RemoteFile) = Unit
}

class PrecheckTest {
    private fun plan(src: String, dir: String, name: String, year: Int? = 2010) = RenamePlan(
        item = RenameItem(
            RemoteFile(src, src.substringAfterLast('/'), false, 5L shl 30),
            MediaNameParser.parse(src.substringAfterLast('/')),
            TmdbSearchResult(1, MediaType.MOVIE, "盗梦空间", null, year, null, null, null, 0.0),
        ),
        input = NamingInput(
            isEpisode = false, chineseTitle = "盗梦空间", englishTitle = null, year = year, tmdbId = 1,
            extension = "mkv", originalFileName = src.substringAfterLast('/'),
        ),
        targetDir = dir, targetName = name,
    )

    @Test fun findsExistingDuplicateAndMissingYear() = runBlocking {
        val lib = "/lib/电影/盗梦空间 (2010)"
        val storage = Mem("$lib/盗梦空间 (2010).mkv" to (8L shl 30))
        val items = listOf(
            "a" to plan("/dl/Inception.2010.2160p.mkv", lib, "盗梦空间 (2010).mkv"),          // 库里已有
            "b" to plan("/dl/新片.mkv", "/lib/电影/新片 (2020)", "新片 (2020).mkv"),
            "c" to plan("/dl/新片.1080p.mkv", "/lib/电影/新片 (2020)", "新片 (2020).mkv"),     // 和 b 撞名
            "d" to plan("/dl/无年份.mkv", "/lib/电影/无年份", "无年份.mkv", year = null),
        )
        val r = Precheck(storage).run(items)
        val byKey = r.conflicts.associateBy { it.key }
        assertEquals(ConflictKind.EXISTS, byKey["a"]!!.kind)
        assertEquals(8L shl 30, byKey["a"]!!.existingSize)
        assertTrue("b 是第一个，照常整理", "b" !in byKey)
        assertEquals(ConflictKind.DUPLICATE, byKey["c"]!!.kind)
        assertEquals(ConflictKind.MISSING_META, byKey["d"]!!.kind)
        // 默认动作都不动用户的文件
        assertEquals(ConflictAction.SKIP, byKey["a"]!!.actions.first())
        assertEquals(ConflictAction.SKIP, byKey["c"]!!.actions.first())
        assertEquals(ConflictAction.PROCEED, byKey["d"]!!.actions.first())
        assertTrue(r.newDirs.containsAll(listOf("/lib/电影/新片 (2020)", "/lib/电影/无年份")))
        assertTrue(lib !in r.newDirs)
        assertTrue("盗梦空间 (2010).mkv" in r.existingNames[lib]!!)
    }

    @Test fun uniqueNameSkipsTaken() {
        assertEquals("a (2).mkv", Precheck.uniqueName("a.mkv", setOf("a.mkv")))
        assertEquals("a (3).mkv", Precheck.uniqueName("a.mkv", setOf("a.mkv", "a (2).mkv")))
    }

    @Test fun sameItemOnlyKeepsSameEpisodeForTv() {
        fun f(n: String) = RemoteFile("/lib/$n", n, false, 1L)
        val videos = listOf(
            f("少年法庭 (2022) S01E01.mkv"), f("少年法庭 (2022) S01E02.mkv"),
            f("少年法庭 (2022) S01E02 (2).mkv"), f("少年法庭 (2022) S01E03.mkv"), f("少年法庭 (2022) S02E02.mkv"),
        )
        val ep = NamingInput(
            isEpisode = true, chineseTitle = "少年法庭", englishTitle = null, year = 2022, season = 1, episode = 2, tmdbId = 1,
            extension = "mkv", originalFileName = "x.mkv",
        )
        assertEquals(
            listOf("少年法庭 (2022) S01E02.mkv", "少年法庭 (2022) S01E02 (2).mkv"),
            Precheck.sameItem(videos, ep).map { it.name },
        )
        assertEquals(videos, Precheck.sameItem(videos, ep.copy(isEpisode = false)))   // 电影：整个文件夹
    }
}
