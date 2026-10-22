package com.example.mediaorganizer.storage

import java.io.IOException
import java.io.InputStream

/** 存储操作失败，code 一般是 HTTP 状态码（404 = 不存在） */
class StorageException(val code: Int, message: String) : IOException(message)

/** 存储里的一个文件或文件夹 */
data class RemoteFile(
    val path: String,          // 完整路径，如 /影视/老友记/S02/xxx.mkv
    val name: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    val modified: Long = 0,
    val id: String? = null,    // 网盘通常用文件 ID 而不是路径操作
)

/**
 * 所有存储（SMB / WebDAV / FTP / 各家网盘）都实现这个接口，
 * 上层的扫描、改名、刮削逻辑只依赖它，不关心具体是哪种存储。
 */
interface StorageProvider {
    val displayName: String

    suspend fun connect()
    suspend fun list(dir: String): List<RemoteFile>
    suspend fun exists(path: String): Boolean
    suspend fun mkdirs(path: String)
    suspend fun rename(file: RemoteFile, newName: String): RemoteFile
    suspend fun move(file: RemoteFile, targetDir: String): RemoteFile

    /** 移动并改名。默认两步完成，支持一步到位的存储（如 WebDAV MOVE）可覆盖 */
    suspend fun moveAndRename(file: RemoteFile, targetDir: String, newName: String): RemoteFile {
        val moved = if (parentPath(file.path) == normalizePath(targetDir)) file else move(file, targetDir)
        return if (moved.name == newName) moved else rename(moved, newName)
    }
    suspend fun upload(targetPath: String, data: InputStream, size: Long)
    suspend fun delete(file: RemoteFile)

    /** 网盘接口有限流，批量操作时上层据此控制节奏 */
    val minRequestIntervalMs: Long get() = 0

    /** 读取单个文件夹的信息，不存在返回 null（连接测试用）；默认从上级目录的列表里找 */
    suspend fun statDir(path: String): RemoteFile? {
        val p = normalizePath(path)
        if (p == "/") return RemoteFile("/", "", isDirectory = true)
        return try {
            list(parentPath(p)).firstOrNull { it.name == p.substringAfterLast('/') }
        } catch (e: StorageException) {
            if (e.code == 404) null else throw e
        }
    }

    /**
     * 读文件开头最多 length 字节（读音乐标签用）；不支持的存储返回 null，上层改按文件名识别。
     * 文件不足 length 时返回整个文件
     */
    suspend fun readHead(file: RemoteFile, length: Int): ByteArray? = null

    /** 不再使用时释放连接（SMB 等长连接） */
    suspend fun close() {}
}

/** 从流里读最多 length 字节，流提前结束就返回已读到的 */
fun readUpTo(input: InputStream, length: Int): ByteArray {
    val buf = ByteArray(length)
    var n = 0
    while (n < length) {
        val r = input.read(buf, n, length - n)
        if (r < 0) break
        n += r
    }
    return if (n == length) buf else buf.copyOf(n)
}

fun normalizePath(path: String): String =
    "/" + path.split('/').filter { it.isNotEmpty() }.joinToString("/")

fun parentPath(path: String): String =
    normalizePath(path).substringBeforeLast('/', "").ifEmpty { "/" }

fun joinPath(dir: String, name: String): String =
    normalizePath(normalizePath(dir).trimEnd('/') + "/" + name)

/** 每次改名/移动都记一条，用于预览和回滚 */
data class FileOperation(
    val storageId: String,
    val type: Type,
    val fromPath: String,
    val toPath: String,
    val timestamp: Long = System.currentTimeMillis(),
) {
    enum class Type { RENAME, MOVE, MKDIR, UPLOAD }
}
