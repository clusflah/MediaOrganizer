package com.example.mediaorganizer.storage

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.commons.EnumWithValue
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * SMB 共享（群晖、威联通、Windows 共享文件夹），SMB2/3。
 * 路径相对共享根目录，如共享 video 下的 "/电影/盗梦空间"。
 *
 * @param host  主机名或 IP，可带端口 192.168.1.10:445
 * @param share 共享名，如 video
 */
class SmbStorage(
    host: String,
    private val share: String,
    private val username: String?,
    private val password: String?,
    private val domain: String? = null,
) : StorageProvider {

    override val displayName = "SMB"

    private val hostName = host.trim().removePrefix("smb://").substringBefore('/').substringBefore(':')
    private val port = host.trim().removePrefix("smb://").substringBefore('/').substringAfter(':', "").toIntOrNull() ?: 445

    private val client = SMBClient(
        SmbConfig.builder()
            .withSecurityProvider(BCSecurityProvider())   // Android 自带的 JCE 没有 MD4，NTLM 需要它
            .withTimeout(30, TimeUnit.SECONDS)
            .withSoTimeout(60, TimeUnit.SECONDS)
            .build()
    )
    private var connection: Connection? = null
    private var session: Session? = null
    private var disk: DiskShare? = null
    private val lock = Mutex()

    /** 所有操作串行执行，断线后自动重连一次 */
    private suspend fun <T> io(block: (DiskShare) -> T): T = withContext(Dispatchers.IO) {
        lock.withLock {
            try {
                block(shareOrConnect())
            } catch (e: SMBApiException) {
                throw translate(e)
            } catch (e: com.hierynomus.smbj.common.SMBRuntimeException) {
                // 连接断了：丢掉旧连接再试一次
                reset()
                try { block(shareOrConnect()) } catch (e2: SMBApiException) { throw translate(e2) }
            }
        }
    }

    private fun shareOrConnect(): DiskShare {
        disk?.takeIf { it.isConnected }?.let { return it }
        reset()
        val c = client.connect(hostName, port).also { connection = it }
        val auth = if (username.isNullOrBlank()) AuthenticationContext.guest()
        else AuthenticationContext(username, (password ?: "").toCharArray(), domain?.ifBlank { null })
        val s = try { c.authenticate(auth) } catch (e: SMBApiException) { throw translate(e) }
        session = s
        val d = try {
            s.connectShare(share) as? DiskShare ?: throw StorageException(400, "「$share」不是文件共享")
        } catch (e: SMBApiException) { throw translate(e) }
        disk = d
        return d
    }

    private fun reset() {
        runCatching { disk?.close() }
        runCatching { session?.close() }
        runCatching { connection?.close() }
        disk = null; session = null; connection = null
    }

    private fun translate(e: SMBApiException): StorageException = when (e.status) {
        NtStatus.STATUS_LOGON_FAILURE, NtStatus.STATUS_PASSWORD_EXPIRED, NtStatus.STATUS_ACCOUNT_DISABLED ->
            StorageException(401, "用户名或密码错误")
        NtStatus.STATUS_ACCESS_DENIED -> StorageException(403, "没有访问权限")
        NtStatus.STATUS_BAD_NETWORK_NAME, NtStatus.STATUS_BAD_NETWORK_PATH -> StorageException(404, "共享「$share」不存在")
        NtStatus.STATUS_OBJECT_NAME_NOT_FOUND, NtStatus.STATUS_OBJECT_PATH_NOT_FOUND, NtStatus.STATUS_NO_SUCH_FILE ->
            StorageException(404, "路径不存在")
        NtStatus.STATUS_OBJECT_NAME_COLLISION -> StorageException(412, "目标已存在")
        else -> StorageException(500, "SMB 错误：${e.status}")
    }

    /** "/电影/a.mkv" -> "电影\a.mkv"，共享根目录是 "" */
    private fun smb(path: String) = normalizePath(path).trim('/').replace('/', '\\')

    // ---------- StorageProvider ----------

    override suspend fun connect() { io { } }

    override suspend fun close() = withContext(Dispatchers.IO) { lock.withLock { reset(); client.close() } }

    override suspend fun list(dir: String): List<RemoteFile> = io { d ->
        val base = normalizePath(dir)
        d.list(smb(base))
            .filter { it.fileName != "." && it.fileName != ".." }
            .map {
                val isDir = EnumWithValue.EnumUtils.isSet(it.fileAttributes, FileAttributes.FILE_ATTRIBUTE_DIRECTORY)
                RemoteFile(
                    path = joinPath(base, it.fileName),
                    name = it.fileName,
                    isDirectory = isDir,
                    size = if (isDir) 0 else it.endOfFile,
                    modified = it.lastWriteTime.toEpochMillis(),
                )
            }
            .sortedWith(compareBy<RemoteFile>({ !it.isDirectory }, { it.name.lowercase() }))
    }

    override suspend fun exists(path: String): Boolean = io { d ->
        val p = smb(path)
        p.isEmpty() || d.fileExists(p) || d.folderExists(p)
    }

    override suspend fun statDir(path: String): RemoteFile? = io { d ->
        val p = normalizePath(path)
        when {
            p == "/" || d.folderExists(smb(p)) -> RemoteFile(p, p.substringAfterLast('/'), isDirectory = true)
            d.fileExists(smb(p)) -> RemoteFile(p, p.substringAfterLast('/'), isDirectory = false)
            else -> null
        }
    }

    override suspend fun mkdirs(path: String) = io { d ->
        var cur = ""
        for (seg in normalizePath(path).split('/').filter { it.isNotEmpty() }) {
            cur = if (cur.isEmpty()) seg else "$cur\\$seg"
            if (!d.folderExists(cur)) d.mkdir(cur)
        }
    }

    override suspend fun rename(file: RemoteFile, newName: String): RemoteFile {
        require(!newName.contains('/')) { "新名称不能包含 /" }
        return moveTo(file, joinPath(parentPath(file.path), newName))
    }

    override suspend fun move(file: RemoteFile, targetDir: String): RemoteFile {
        mkdirs(targetDir)
        return moveTo(file, joinPath(targetDir, file.name))
    }

    override suspend fun moveAndRename(file: RemoteFile, targetDir: String, newName: String): RemoteFile {
        require(!newName.contains('/')) { "新名称不能包含 /" }
        mkdirs(targetDir)
        return moveTo(file, joinPath(targetDir, newName))     // SMB 的 rename 可以直接换目录，一步完成
    }

    private suspend fun moveTo(file: RemoteFile, newPath: String): RemoteFile {
        val dest = normalizePath(newPath)
        if (dest == normalizePath(file.path)) return file
        io { d ->
            val access = EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_READ)
            val shareAccess = EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE, SMB2ShareAccess.FILE_SHARE_DELETE)
            val entry = if (file.isDirectory)
                d.openDirectory(smb(file.path), access, null, shareAccess, SMB2CreateDisposition.FILE_OPEN, null)
            else
                d.openFile(smb(file.path), access, null, shareAccess, SMB2CreateDisposition.FILE_OPEN, null)
            entry.use { it.rename(smb(dest), false) }     // 目标已存在时不覆盖
        }
        return file.copy(path = dest, name = dest.substringAfterLast('/'))
    }

    override suspend fun upload(targetPath: String, data: InputStream, size: Long) {
        val path = normalizePath(targetPath)
        mkdirs(parentPath(path))
        io { d ->
            d.openFile(
                smb(path),
                EnumSet.of(AccessMask.GENERIC_WRITE),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                SMB2CreateDisposition.FILE_OVERWRITE_IF,
                EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
            ).use { f -> f.outputStream.use { out -> data.use { it.copyTo(out) } } }
        }
    }

    override suspend fun delete(file: RemoteFile) = io { d ->
        val p = smb(file.path)
        if (file.isDirectory) d.rmdir(p, true) else d.rm(p)
    }
}
