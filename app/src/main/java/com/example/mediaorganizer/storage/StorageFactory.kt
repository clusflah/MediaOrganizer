package com.example.mediaorganizer.storage

import android.content.Context
import com.example.mediaorganizer.settings.AppSettings
import com.example.mediaorganizer.storage.aliyun.AliyunStorage
import com.example.mediaorganizer.storage.pan115.Pan115Storage
import java.net.URI

/** 支持的媒体库类型；ready=false 的还在开发，界面上不显示 */
enum class StorageType(val label: String, val ready: Boolean) {
    WEBDAV("WebDAV", true),
    SMB("SMB 共享", true),
    LOCAL("本机 / U 盘", true),
    PAN115("115 网盘", true),
    ALIYUN("阿里云盘", true),
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
        StorageType.ALIYUN -> when {
            s.aliClientId.isBlank() || s.aliClientSecret.isBlank() -> "请填写阿里云盘开放平台的 AppID 和 Secret"
            s.aliRefreshToken.isBlank() -> "请先扫码授权阿里云盘"
            else -> null
        }
    }

    /**
     * @param onAliToken 阿里云盘刷新令牌后会换新 refresh_token，交给调用方存回设置
     * @throws IllegalArgumentException 信息没填够或格式不对
     */
    fun create(context: Context, s: AppSettings, onAliToken: (String) -> Unit = {}): StorageProvider {
        missing(s)?.let { throw IllegalArgumentException(it) }
        return when (s.storageType) {
            StorageType.WEBDAV -> WebDavStorage(s.webdavUrl.trim(), s.username.ifBlank { null }, s.password.ifBlank { null })
            StorageType.SMB -> SmbStorage(
                s.smbHost, s.smbShare.trim().trim('/', '\\'), s.smbUser.ifBlank { null }, s.smbPassword, s.smbDomain,
            )
            StorageType.LOCAL -> LocalStorage(context, s.localTreeUri)
            StorageType.PAN115 -> Pan115Storage(s.pan115Cookie)
            StorageType.ALIYUN -> AliyunStorage(s.aliClientId.trim(), s.aliClientSecret.trim(), s.aliRefreshToken, s.aliResourceDrive, onAliToken)
        }
    }

    /** 撤销记录用它判断「是不是同一个媒体库」；WebDAV 保持旧格式，旧的撤销记录仍然有效 */
    fun id(s: AppSettings): String = when (s.storageType) {
        StorageType.WEBDAV -> "webdav:" + s.webdavUrl.trim().trimEnd('/')
        StorageType.SMB -> "smb://" + s.smbHost.trim() + "/" + s.smbShare.trim().trim('/', '\\')
        StorageType.LOCAL -> "local:" + s.localTreeUri
        StorageType.PAN115 -> "115:" + Regex("""UID=([^;]+)""").find(s.pan115Cookie)?.groupValues?.get(1)?.substringBefore('_').orEmpty()
        StorageType.ALIYUN -> "aliyun:" + s.aliClientId.trim() + if (s.aliResourceDrive) ":resource" else ":backup"
    }

    /** 给界面显示的简短名称 */
    fun describe(s: AppSettings): String = when (s.storageType) {
        StorageType.WEBDAV -> runCatching { URI(s.webdavUrl.trim()).let { u -> u.host + if (u.port > 0) ":${u.port}" else "" } }
            .getOrNull()?.takeIf { !it.startsWith("null") } ?: s.webdavUrl.trim()
        StorageType.SMB -> "${s.smbHost.trim()}/${s.smbShare.trim()}"
        StorageType.LOCAL -> if (s.localTreeUri.isBlank()) "未选择文件夹" else LocalStorage.describe(s.localTreeUri)
        StorageType.PAN115 -> if (s.pan115Cookie.isBlank()) "未登录" else "已登录"
        StorageType.ALIYUN -> when {
            s.aliRefreshToken.isBlank() -> "未授权"
            s.aliResourceDrive -> "资源库"
            else -> "备份盘"
        }
    }

    /** 这些字段变了就算换了连接（旧的测试结果作废） */
    fun connectionKey(s: AppSettings): List<Any> = listOf(
        s.storageType, s.webdavUrl, s.username, s.password,
        s.smbHost, s.smbShare, s.smbUser, s.smbPassword, s.smbDomain, s.localTreeUri,
        s.pan115Cookie, s.aliClientId, s.aliClientSecret, s.aliResourceDrive,
        s.scanDir, s.movieRoot, s.tvRoot,
    )
}
