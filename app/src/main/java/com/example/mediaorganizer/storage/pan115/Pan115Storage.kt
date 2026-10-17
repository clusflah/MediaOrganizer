package com.example.mediaorganizer.storage.pan115

import com.example.mediaorganizer.storage.RemoteFile
import com.example.mediaorganizer.storage.StorageException
import com.example.mediaorganizer.storage.StorageProvider
import com.example.mediaorganizer.storage.joinPath
import com.example.mediaorganizer.storage.normalizePath
import com.example.mediaorganizer.storage.parentPath
import java.io.InputStream

/**
 * 115 网盘。115 按 ID 操作文件，这里维护 路径 -> ID 的缓存；
 * 115 允许同一目录下重名，上传前会先删掉同名旧文件（进回收站）。
 */
class Pan115Storage(cookie: String) : StorageProvider {

    override val displayName = "115 网盘"
    override val minRequestIntervalMs: Long = 300      // 115 对频繁请求会限流

    private val api = Pan115Client(cookie)
    private val dirIds = HashMap<String, String>().apply { put("/", "0") }
    private val listings = HashMap<String, List<RemoteFile>>()

    private suspend fun dirIdOf(path: String): String? {
        val p = normalizePath(path)
        dirIds[p]?.let { return it }
        return api.dirId(p)?.also { dirIds[p] = it }
    }

    private fun invalidate(dir: String) {
        val d = normalizePath(dir)
        listings.remove(d)
    }

    private fun forget(path: String) {
        val p = normalizePath(path)
        dirIds.keys.removeAll { it == p || it.startsWith("$p/") }
        listings.keys.removeAll { it == p || it.startsWith("$p/") }
    }

    private suspend fun entry(path: String): RemoteFile? {
        val p = normalizePath(path)
        if (p == "/") return RemoteFile("/", "", isDirectory = true, id = "0")
        val parent = parentPath(p)
        if (dirIdOf(parent) == null) return null
        return list(parent).firstOrNull { it.name == p.substringAfterLast('/') }
    }

    private suspend fun idOf(file: RemoteFile): String =
        file.id ?: entry(file.path)?.id ?: throw StorageException(404, "文件不存在：${file.path}")

    // ---------- StorageProvider ----------

    override suspend fun connect() { api.userName() }

    override suspend fun list(dir: String): List<RemoteFile> {
        val base = normalizePath(dir)
        listings[base]?.let { return it }
        val id = dirIdOf(base) ?: throw StorageException(404, "路径不存在：$base")
        val files = api.list(id).map {
            val path = joinPath(base, it.name)
            if (it.isDir) dirIds[path] = it.id
            RemoteFile(path, it.name, it.isDir, size = it.size, modified = it.modified, id = it.id)
        }.sortedWith(compareBy<RemoteFile>({ !it.isDirectory }, { it.name.lowercase() }))
        listings[base] = files
        return files
    }

    override suspend fun exists(path: String) = entry(path) != null

    override suspend fun statDir(path: String): RemoteFile? = entry(path)

    override suspend fun mkdirs(path: String) { mkdirsId(path) }

    private suspend fun mkdirsId(path: String): String {
        var cur = "/"
        var curId = "0"
        for (seg in normalizePath(path).split('/').filter { it.isNotEmpty() }) {
            val next = joinPath(cur, seg)
            curId = dirIdOf(next) ?: api.mkdir(curId, seg).also { dirIds[next] = it; invalidate(cur) }
            cur = next
        }
        return curId
    }

    override suspend fun rename(file: RemoteFile, newName: String): RemoteFile {
        require(!newName.contains('/')) { "新名称不能包含 /" }
        val dest = joinPath(parentPath(file.path), newName)
        if (dest == normalizePath(file.path)) return file
        val id = idOf(file)
        api.rename(id, newName)
        forget(file.path)
        invalidate(parentPath(file.path))
        return file.copy(path = dest, name = newName, id = id)
    }

    override suspend fun move(file: RemoteFile, targetDir: String): RemoteFile {
        val target = normalizePath(targetDir)
        if (parentPath(file.path) == target) return file
        val id = idOf(file)
        val targetId = mkdirsId(target)
        api.move(listOf(id), targetId)
        forget(file.path)
        invalidate(parentPath(file.path)); invalidate(target)
        return file.copy(path = joinPath(target, file.name), id = id)
    }

    // moveAndRename 用默认实现：先移动再改名

    override suspend fun upload(targetPath: String, data: InputStream, size: Long) {
        val path = normalizePath(targetPath)
        val dir = parentPath(path)
        val name = path.substringAfterLast('/')
        val dirId = mkdirsId(dir)
        val bytes = data.use { it.readBytes() }
        // 覆盖：先把同名旧文件删到回收站
        list(dir).filter { it.name == name && !it.isDirectory }.mapNotNull { it.id }.takeIf { it.isNotEmpty() }?.let { api.delete(it) }
        api.upload(dirId, name, bytes)
        invalidate(dir)
    }

    override suspend fun delete(file: RemoteFile) {
        val id = entry(file.path)?.id ?: file.id ?: return
        api.delete(listOf(id))
        forget(file.path)
        invalidate(parentPath(file.path))
    }
}
