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
