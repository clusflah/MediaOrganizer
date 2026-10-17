package com.example.mediaorganizer.storage

import android.content.Context
import com.example.mediaorganizer.settings.AppSettings
import java.net.URI

/** 支持的媒体库类型；ready=false 的还在开发，界面上不显示 */
enum class StorageType(val label: String, val ready: Boolean) {
    WEBDAV("WebDAV", true),
    SMB("SMB 共享", true),
    LOCAL("本机 / U 盘", true),
    PAN115("115 网盘", false),
    ALIYUN("阿里云盘", false),
}

object StorageFactory {

    /** 当前设置是否填够了连接所需的信息；没填够返回提示 */
    fun missing(s: AppSettings): String? = when (s.storageType) {
        StorageType.WEBDAV -> if (s.webdavUrl.isBlank()) "请填写 WebDAV 地址" else null
        StorageType.SMB -> when {
            s.smbHost.isBlank() -> "请填写 SMB 主机地址"
            s.smbShare.isBlank() -> "请填写共享名"
            else -> null
        }
        StorageType.LOCAL -> if (s.localTreeUri.isBlank()) "请先选择文件夹" else null
        StorageType.PAN115 -> if (s.pan115Cookie.isBlank()) "请先扫码登录 115" else null
        StorageType.ALIYUN -> if (s.aliRefreshToken.isBlank()) "请先授权阿里云盘" else null
    }

    /** @throws IllegalArgumentException 信息没填够或格式不对 */
    fun create(context: Context, s: AppSettings): StorageProvider {
        missing(s)?.let { throw IllegalArgumentException(it) }
        return when (s.storageType) {
            StorageType.WEBDAV -> WebDavStorage(s.webdavUrl.trim(), s.username.ifBlank { null }, s.password.ifBlank { null })
            StorageType.SMB -> SmbStorage(
                s.smbHost, s.smbShare.trim().trim('/', '\\'), s.smbUser.ifBlank { null }, s.smbPassword, s.smbDomain,
            )
            StorageType.LOCAL -> LocalStorage(context, s.localTreeUri)
            StorageType.PAN115, StorageType.ALIYUN -> throw IllegalArgumentException("${s.storageType.label} 暂不可用")
        }
    }

    /** 撤销记录用它判断「是不是同一个媒体库」；WebDAV 保持旧格式，旧的撤销记录仍然有效 */
    fun id(s: AppSettings): String = when (s.storageType) {
        StorageType.WEBDAV -> "webdav:" + s.webdavUrl.trim().trimEnd('/')
        StorageType.SMB -> "smb://" + s.smbHost.trim() + "/" + s.smbShare.trim().trim('/', '\\')
        StorageType.LOCAL -> "local:" + s.localTreeUri
        StorageType.PAN115 -> "115"
        StorageType.ALIYUN -> "aliyun:" + s.aliClientId
    }

    /** 给界面显示的简短名称 */
    fun describe(s: AppSettings): String = when (s.storageType) {
        StorageType.WEBDAV -> runCatching { URI(s.webdavUrl.trim()).let { u -> u.host + if (u.port > 0) ":${u.port}" else "" } }
            .getOrNull()?.takeIf { !it.startsWith("null") } ?: s.webdavUrl.trim()
        StorageType.SMB -> "${s.smbHost.trim()}/${s.smbShare.trim()}"
        StorageType.LOCAL -> if (s.localTreeUri.isBlank()) "未选择文件夹" else LocalStorage.describe(s.localTreeUri)
        StorageType.PAN115 -> "115 网盘"
        StorageType.ALIYUN -> "阿里云盘"
    }

    /** 这些字段变了就算换了连接（旧的测试结果作废） */
    fun connectionKey(s: AppSettings): List<Any> = listOf(
        s.storageType, s.webdavUrl, s.username, s.password,
        s.smbHost, s.smbShare, s.smbUser, s.smbPassword, s.smbDomain, s.localTreeUri,
        s.pan115Cookie, s.aliClientId, s.aliClientSecret, s.aliRefreshToken,
        s.scanDir, s.movieRoot, s.tvRoot,
    )
}
