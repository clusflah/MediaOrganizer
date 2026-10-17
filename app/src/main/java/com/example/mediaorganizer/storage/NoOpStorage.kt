package com.example.mediaorganizer.storage

import java.io.InputStream

/** 什么都不做的存储：只用来让刮削器算出「会写哪些文件」，不碰真实存储 */
object NoOpStorage : StorageProvider {
    override val displayName = "dry-run"
    override suspend fun connect() = Unit
    override suspend fun list(dir: String) = emptyList<RemoteFile>()
    override suspend fun exists(path: String) = false
    override suspend fun mkdirs(path: String) = Unit
    override suspend fun rename(file: RemoteFile, newName: String) = file
    override suspend fun move(file: RemoteFile, targetDir: String) = file
    override suspend fun upload(targetPath: String, data: InputStream, size: Long) = Unit
    override suspend fun delete(file: RemoteFile) = Unit
}
