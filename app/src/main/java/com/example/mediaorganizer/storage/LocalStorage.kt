package com.example.mediaorganizer.storage

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * 本机文件夹或 OTG U 盘：用户在系统文件选择器里授权一个文件夹（SAF 文档树），
 * 路径相对这个文件夹，如 "/电影/盗梦空间"。
 */
class LocalStorage(context: Context, treeUri: String) : StorageProvider {

    override val displayName = "本机"

    private val resolver: ContentResolver = context.contentResolver
    private val tree: Uri = Uri.parse(treeUri)
    private val rootId: String = DocumentsContract.getTreeDocumentId(tree)
    private val ids = HashMap<String, String>().apply { put("/", rootId) }   // 路径 -> documentId
    private val lock = Mutex()

    private fun docUri(id: String) = DocumentsContract.buildDocumentUriUsingTree(tree, id)

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) {
        lock.withLock {
            try {
                block()
            } catch (e: SecurityException) {
                throw StorageException(403, "没有这个文件夹的访问权限，请重新选择文件夹")
            } catch (e: FileNotFoundException) {
                throw StorageException(404, "路径不存在")
            }
        }
    }

    private data class Child(val id: String, val name: String, val isDir: Boolean, val size: Long, val modified: Long)

    private fun children(parentId: String): List<Child> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val cols = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED,
        )
        val out = mutableListOf<Child>()
        resolver.query(uri, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                out += Child(
                    id = c.getString(0), name = c.getString(1),
                    isDir = c.getString(2) == Document.MIME_TYPE_DIR,
                    size = if (c.isNull(3)) 0 else c.getLong(3),
                    modified = if (c.isNull(4)) 0 else c.getLong(4),
                )
            }
        } ?: throw StorageException(404, "无法读取文件夹")
        return out
    }

    /** 路径 -> documentId，逐级查找并缓存；不存在返回 null */
    private fun resolve(path: String): String? {
        val p = normalizePath(path)
        ids[p]?.let { return it }
        val parentId = resolve(parentPath(p)) ?: return null
        val name = p.substringAfterLast('/')
        val hit = children(parentId).firstOrNull { it.name == name } ?: return null
        ids[p] = hit.id
        return hit.id
    }

    private fun forget(path: String) {
        val p = normalizePath(path)
        ids.keys.removeAll { it == p || it.startsWith("$p/") }
    }

    private fun mkdirsLocked(path: String): String {
        var cur = "/"
        var curId = rootId
        for (seg in normalizePath(path).split('/').filter { it.isNotEmpty() }) {
            val next = joinPath(cur, seg)
            curId = resolve(next) ?: DocumentsContract.createDocument(resolver, docUri(curId), Document.MIME_TYPE_DIR, seg)
                ?.let { DocumentsContract.getDocumentId(it) }
                ?.also { ids[next] = it }
                ?: throw StorageException(500, "创建文件夹失败：$next")
            cur = next
        }
        return curId
    }

    // ---------- StorageProvider ----------

    override suspend fun connect() = io {
        children(rootId)
        Unit
    }

    override suspend fun list(dir: String): List<RemoteFile> = io {
        val base = normalizePath(dir)
        val id = resolve(base) ?: throw StorageException(404, "路径不存在：$base")
        children(id).map { c ->
            val path = joinPath(base, c.name)
            ids[path] = c.id
            RemoteFile(path, c.name, c.isDir, size = c.size, modified = c.modified, id = c.id)
        }.sortedWith(compareBy<RemoteFile>({ !it.isDirectory }, { it.name.lowercase() }))
    }

    override suspend fun exists(path: String): Boolean = io { resolve(path) != null }

    override suspend fun mkdirs(path: String) = io { mkdirsLocked(path); Unit }

    override suspend fun rename(file: RemoteFile, newName: String): RemoteFile = io {
        require(!newName.contains('/')) { "新名称不能包含 /" }
        val id = resolve(file.path) ?: throw StorageException(404, "文件不存在：${file.path}")
        val dest = joinPath(parentPath(file.path), newName)
        if (dest == normalizePath(file.path)) return@io file
        if (resolve(dest) != null) throw StorageException(412, "目标已存在：$dest")
        val newUri = DocumentsContract.renameDocument(resolver, docUri(id), newName)
            ?: throw StorageException(500, "改名失败：${file.name}")
        forget(file.path)
        ids[dest] = DocumentsContract.getDocumentId(newUri)
        file.copy(path = dest, name = newName)
    }

    override suspend fun move(file: RemoteFile, targetDir: String): RemoteFile = io {
        val target = normalizePath(targetDir)
        val src = normalizePath(file.path)
        if (parentPath(src) == target) return@io file
        val dest = joinPath(target, file.name)
        if (resolve(dest) != null) throw StorageException(412, "目标已存在：$dest")
        val id = resolve(src) ?: throw StorageException(404, "文件不存在：$src")
        val parentId = resolve(parentPath(src))!!
        val targetId = mkdirsLocked(target)
        val moved = try {
            DocumentsContract.moveDocument(resolver, docUri(id), docUri(parentId), docUri(targetId))
        } catch (e: UnsupportedOperationException) {
            throw StorageException(501, "这个存储不支持移动文件（部分 U 盘 / SD 卡），只能原地改名")
        } ?: throw StorageException(500, "移动失败：${file.name}")
        forget(src)
        ids[dest] = DocumentsContract.getDocumentId(moved)
        file.copy(path = dest)
    }

    // 默认实现（先移动再改名）即可；目标重名时 rename 会报错，不会覆盖

    override suspend fun upload(targetPath: String, data: InputStream, size: Long) = io {
        val path = normalizePath(targetPath)
        val parentId = mkdirsLocked(parentPath(path))
        val name = path.substringAfterLast('/')
        val existing = resolve(path)
        val uri = if (existing != null) docUri(existing) else {
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
                ?: "application/octet-stream"
            DocumentsContract.createDocument(resolver, docUri(parentId), mime, name)
                ?.also { ids[path] = DocumentsContract.getDocumentId(it) }
                ?: throw StorageException(500, "创建文件失败：$name")
        }
        (resolver.openOutputStream(uri, "wt") ?: throw StorageException(500, "无法写入：$name"))
            .use { out -> data.use { it.copyTo(out) } }
        Unit
    }

    override suspend fun delete(file: RemoteFile) = io {
        val id = resolve(file.path) ?: return@io
        DocumentsContract.deleteDocument(resolver, docUri(id))
        forget(file.path)
    }

    companion object {
        /** 给界面显示用：content://...tree/primary%3AMovies -> 内部存储/Movies */
        fun describe(treeUri: String): String = runCatching {
            val id = DocumentsContract.getTreeDocumentId(Uri.parse(treeUri))
            val volume = id.substringBefore(':')
            val rel = id.substringAfter(':', "")
            (if (volume == "primary") "内部存储" else "外部存储 $volume") + if (rel.isNotEmpty()) "/$rel" else ""
        }.getOrDefault(treeUri)
    }
}
