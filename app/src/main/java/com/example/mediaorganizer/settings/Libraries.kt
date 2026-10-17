package com.example.mediaorganizer.settings

import com.example.mediaorganizer.storage.StorageType
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一个媒体库的全部连接信息、目录和最近一次连接测试结果。
 * AppSettings 顶层的连接字段始终是「当前媒体库」，切换时和这里互相拷贝，
 * 所以扫描、整理、存储的代码不需要知道有多个媒体库。
 */
data class LibraryProfile(
    val id: String,
    val name: String,
    val storageType: StorageType = StorageType.WEBDAV,
    val webdavUrl: String = "",
    val username: String = "",
    val password: String = "",
    val smbHost: String = "",
    val smbShare: String = "",
    val smbUser: String = "",
    val smbPassword: String = "",
    val smbDomain: String = "",
    val localTreeUri: String = "",
    val pan115Cookie: String = "",
    val aliClientId: String = "",
    val aliClientSecret: String = "",
    val aliRefreshToken: String = "",
    val aliResourceDrive: Boolean = true,
    val scanDir: String = "/下载",
    val movieRoot: String = "/影视库/电影",
    val tvRoot: String = "/影视库/剧集",
    val lastTestAt: Long = 0,
    val lastTestOk: Boolean? = null,       // null = 没测过
    val lastTestMsg: String = "",
)

/** 当前顶层字段 -> 媒体库 */
fun AppSettings.snapshot(id: String = activeLibrary, name: String = libraryName) = LibraryProfile(
    id = id, name = name, storageType = storageType,
    webdavUrl = webdavUrl, username = username, password = password,
    smbHost = smbHost, smbShare = smbShare, smbUser = smbUser, smbPassword = smbPassword, smbDomain = smbDomain,
    localTreeUri = localTreeUri, pan115Cookie = pan115Cookie,
    aliClientId = aliClientId, aliClientSecret = aliClientSecret, aliRefreshToken = aliRefreshToken, aliResourceDrive = aliResourceDrive,
    scanDir = scanDir, movieRoot = movieRoot, tvRoot = tvRoot,
    lastTestAt = lastTestAt, lastTestOk = lastTestOk, lastTestMsg = lastTestMsg,
)

/** 媒体库 -> 顶层字段 */
fun AppSettings.applyProfile(p: LibraryProfile) = copy(
    activeLibrary = p.id, libraryName = p.name, storageType = p.storageType,
    webdavUrl = p.webdavUrl, username = p.username, password = p.password,
    smbHost = p.smbHost, smbShare = p.smbShare, smbUser = p.smbUser, smbPassword = p.smbPassword, smbDomain = p.smbDomain,
    localTreeUri = p.localTreeUri, pan115Cookie = p.pan115Cookie,
    aliClientId = p.aliClientId, aliClientSecret = p.aliClientSecret, aliRefreshToken = p.aliRefreshToken, aliResourceDrive = p.aliResourceDrive,
    scanDir = p.scanDir, movieRoot = p.movieRoot, tvRoot = p.tvRoot,
    lastTestAt = p.lastTestAt, lastTestOk = p.lastTestOk, lastTestMsg = p.lastTestMsg,
)

/** 所有媒体库，当前那个用顶层的最新值 */
fun AppSettings.allLibraries(): List<LibraryProfile> {
    val current = snapshot()
    return if (libraries.any { it.id == activeLibrary }) libraries.map { if (it.id == activeLibrary) current else it }
    else listOf(current) + libraries
}

fun AppSettings.switchLibrary(id: String): AppSettings {
    val all = allLibraries()
    val target = all.firstOrNull { it.id == id } ?: return this
    return copy(libraries = all).applyProfile(target)
}

fun AppSettings.addLibrary(name: String): AppSettings {
    val id = "lib" + System.currentTimeMillis().toString(36)
    val all = allLibraries() + LibraryProfile(id = id, name = name)
    return copy(libraries = all).applyProfile(all.last())
}

/** 删除当前媒体库并切到剩下的第一个；只剩一个时不删 */
fun AppSettings.removeActiveLibrary(): AppSettings {
    val rest = allLibraries().filter { it.id != activeLibrary }
    if (rest.isEmpty()) return this
    return copy(libraries = rest).applyProfile(rest.first())
}

object LibraryCodec {
    fun encode(list: List<LibraryProfile>): String = JSONArray().apply {
        list.forEach { p ->
            put(JSONObject()
                .put("id", p.id).put("name", p.name).put("storageType", p.storageType.name)
                .put("webdavUrl", p.webdavUrl).put("username", p.username).put("password", p.password)
                .put("smbHost", p.smbHost).put("smbShare", p.smbShare).put("smbUser", p.smbUser)
                .put("smbPassword", p.smbPassword).put("smbDomain", p.smbDomain)
                .put("localTreeUri", p.localTreeUri).put("pan115Cookie", p.pan115Cookie)
                .put("aliClientId", p.aliClientId).put("aliClientSecret", p.aliClientSecret)
                .put("aliRefreshToken", p.aliRefreshToken).put("aliResourceDrive", p.aliResourceDrive)
                .put("scanDir", p.scanDir).put("movieRoot", p.movieRoot).put("tvRoot", p.tvRoot)
                .put("lastTestAt", p.lastTestAt).put("lastTestOk", p.lastTestOk?.toString() ?: "")
                .put("lastTestMsg", p.lastTestMsg))
        }
    }.toString()

    /** 解析失败的条目直接丢掉，不让一个坏数据拖垮整个设置 */
    fun decode(text: String): List<LibraryProfile> {
        if (text.isBlank()) return emptyList()
        val arr = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val o = arr.getJSONObject(i)
                val d = LibraryProfile(id = "", name = "")
                LibraryProfile(
                    id = o.getString("id"), name = o.optString("name", "媒体库"),
                    storageType = StorageType.entries.firstOrNull { it.name == o.optString("storageType") } ?: d.storageType,
                    webdavUrl = o.optString("webdavUrl"), username = o.optString("username"), password = o.optString("password"),
                    smbHost = o.optString("smbHost"), smbShare = o.optString("smbShare"), smbUser = o.optString("smbUser"),
                    smbPassword = o.optString("smbPassword"), smbDomain = o.optString("smbDomain"),
                    localTreeUri = o.optString("localTreeUri"), pan115Cookie = o.optString("pan115Cookie"),
                    aliClientId = o.optString("aliClientId"), aliClientSecret = o.optString("aliClientSecret"),
                    aliRefreshToken = o.optString("aliRefreshToken"), aliResourceDrive = o.optBoolean("aliResourceDrive", true),
                    scanDir = o.optString("scanDir", d.scanDir), movieRoot = o.optString("movieRoot", d.movieRoot),
                    tvRoot = o.optString("tvRoot", d.tvRoot),
                    lastTestAt = o.optLong("lastTestAt"),
                    lastTestOk = o.optString("lastTestOk").toBooleanStrictOrNull(),
                    lastTestMsg = o.optString("lastTestMsg"),
                )
            }.getOrNull()
        }
    }
}
