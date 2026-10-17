package com.example.mediaorganizer.storage.aliyun

import com.example.mediaorganizer.storage.RemoteFile
import com.example.mediaorganizer.storage.StorageException
import com.example.mediaorganizer.storage.StorageProvider
import com.example.mediaorganizer.storage.joinPath
import com.example.mediaorganizer.storage.normalizePath
import com.example.mediaorganizer.storage.parentPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

private const val BASE = "https://openapi.alipan.com"
private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

private fun http() = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .build()

/** 阿里云盘开放平台的错误：{"code":"...","message":"..."} */
private fun aliError(code: Int, body: String): StorageException {
    val j = runCatching { JSONObject(body) }.getOrNull()
    val c = j?.optString("code").orEmpty()
    val msg = j?.optString("message").orEmpty().ifEmpty { body.take(200) }
    return when {
        c == "AppNotExists" -> StorageException(401, "AppID 不存在，请检查阿里云盘开放平台的应用信息")
        c.startsWith("AccessToken") || c == "InvalidRefreshToken" || c == "RefreshTokenExpired" ->
            StorageException(401, "阿里云盘授权已失效，请重新授权")
        c.startsWith("NotFound") -> StorageException(404, "路径不存在")
        c == "Forbidden" || code == 403 -> StorageException(403, "没有权限：$msg")
        code == 429 || c == "TooManyRequests" -> StorageException(429, "请求太频繁，请稍后再试")
        else -> StorageException(code, "阿里云盘：$msg（$c）")
    }
}

/**
 * 扫码授权：用开放平台应用的 AppID / Secret 取二维码，
 * 用户用阿里云盘 App 扫码同意后拿到 refresh_token。
 */
class AliyunLogin(private val clientId: String, private val clientSecret: String) {
    private val http = http()

    data class Session(val sid: String, val qrUrl: String)
    enum class Status { WAITING, SCANNED, CONFIRMED, EXPIRED }

    private suspend fun post(path: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(BASE + path).post(body.toString().toRequestBody(JSON_TYPE)).build()).execute().use {
            val text = it.body!!.string()
            if (!it.isSuccessful) throw aliError(it.code, text)
            JSONObject(text)
        }
    }

    suspend fun start(): Session {
        val j = post("/oauth/authorize/qrcode", JSONObject()
            .put("client_id", clientId).put("client_secret", clientSecret)
            .put("scopes", JSONArray(listOf("user:base", "file:all:read", "file:all:write")))
            .put("width", 430).put("height", 430))
        return Session(j.getString("sid"), j.getString("qrCodeUrl"))
    }

    /** 返回状态和（确认后的）authCode */
    suspend fun status(s: Session): Pair<Status, String?> = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url("$BASE/oauth/qrcode/${s.sid}/status").build()).execute().use {
            val j = JSONObject(it.body!!.string())
            when (j.optString("status")) {
                "ScanSuccess" -> Status.SCANNED to null
                "LoginSuccess" -> Status.CONFIRMED to j.optString("authCode")
                "QRCodeExpired" -> Status.EXPIRED to null
                else -> Status.WAITING to null
            }
        }
    }

    suspend fun qrImage(s: Session): ByteArray = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(s.qrUrl).build()).execute().use { it.body!!.bytes() }
    }

    /** authCode -> refresh_token */
    suspend fun finish(authCode: String): String =
        post("/oauth/access_token", JSONObject()
            .put("client_id", clientId).put("client_secret", clientSecret)
            .put("grant_type", "authorization_code").put("code", authCode))
            .optString("refresh_token").ifEmpty { throw IOException("阿里云盘没有返回 refresh_token") }
}

/**
 * 阿里云盘（开放平台 openapi）。
 * @param onTokenRotated 每次刷新 access_token 时 refresh_token 也会换新，必须存回设置，否则下次启动就失效
 * @param useResourceDrive true 用「资源库」，false 用「备份盘」
 */
class AliyunStorage(
    private val clientId: String,
    private val clientSecret: String,
    private var refreshToken: String,
    private val useResourceDrive: Boolean,
    private val onTokenRotated: (String) -> Unit,
) : StorageProvider {

    override val displayName = "阿里云盘"
    override val minRequestIntervalMs: Long = 150

    private val http = http()
    private var accessToken = ""
    private var tokenExpiry = 0L
    private var driveId = ""
    private val ids = HashMap<String, String>().apply { put("/", "root") }

    private suspend fun ensureToken() {
        if (accessToken.isNotEmpty() && System.currentTimeMillis() < tokenExpiry - 60_000) return
        val j = withContext(Dispatchers.IO) {
            val body = JSONObject().put("client_id", clientId).put("client_secret", clientSecret)
                .put("grant_type", "refresh_token").put("refresh_token", refreshToken)
            http.newCall(Request.Builder().url("$BASE/oauth/access_token").post(body.toString().toRequestBody(JSON_TYPE)).build())
                .execute().use {
                    val text = it.body!!.string()
                    if (!it.isSuccessful) throw aliError(it.code, text)
                    JSONObject(text)
                }
        }
        accessToken = j.getString("access_token")
        tokenExpiry = System.currentTimeMillis() + j.optLong("expires_in", 7200) * 1000
        j.optString("refresh_token").takeIf { it.isNotEmpty() && it != refreshToken }?.let {
            refreshToken = it
            onTokenRotated(it)
        }
    }

    private suspend fun api(path: String, body: JSONObject): JSONObject {
        ensureToken()
        if (driveId.isEmpty() && path != "/adrive/v1.0/user/getDriveInfo") loadDrive()
        if (path != "/adrive/v1.0/user/getDriveInfo") body.put("drive_id", driveId)
        return withContext(Dispatchers.IO) {
            http.newCall(
                Request.Builder().url(BASE + path).header("Authorization", "Bearer $accessToken")
                    .post(body.toString().toRequestBody(JSON_TYPE)).build()
            ).execute().use {
                val text = it.body!!.string()
                if (!it.isSuccessful) throw aliError(it.code, text)
                JSONObject(text)
            }
        }
    }

    private suspend fun loadDrive() {
        val j = api("/adrive/v1.0/user/getDriveInfo", JSONObject())
        driveId = (if (useResourceDrive) j.optString("resource_drive_id") else j.optString("backup_drive_id"))
            .ifEmpty { j.optString("default_drive_id") }
        if (driveId.isEmpty()) throw StorageException(500, "没找到阿里云盘的盘符")
    }

    private fun toRemote(base: String, o: JSONObject): RemoteFile {
        val name = o.getString("name")
        val isDir = o.optString("type") == "folder"
        val path = joinPath(base, name)
        if (isDir) ids[path] = o.getString("file_id")
        return RemoteFile(
            path, name, isDir,
            size = o.optLong("size", 0), modified = parseTime(o.optString("updated_at")), id = o.getString("file_id"),
        )
    }

    private suspend fun idOf(path: String): String? {
        val p = normalizePath(path)
        ids[p]?.let { return it }
        return try {
            api("/adrive/v1.0/openFile/get_by_path", JSONObject().put("file_path", p)).getString("file_id").also {
                ids[p] = it
            }
        } catch (e: StorageException) {
            if (e.code == 404) null else throw e
        }
    }

    private fun forget(path: String) {
        val p = normalizePath(path)
        ids.keys.removeAll { it == p || it.startsWith("$p/") }
    }

    // ---------- StorageProvider ----------

    override suspend fun connect() { ensureToken(); loadDrive() }

    override suspend fun list(dir: String): List<RemoteFile> {
        val base = normalizePath(dir)
        val id = idOf(base) ?: throw StorageException(404, "路径不存在：$base")
        val out = mutableListOf<RemoteFile>()
        var marker = ""
        do {
            val body = JSONObject().put("parent_file_id", id).put("limit", 100)
            if (marker.isNotEmpty()) body.put("marker", marker)
            val j = api("/adrive/v1.0/openFile/list", body)
            val items = j.optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) out += toRemote(base, items.getJSONObject(i))
            marker = j.optString("next_marker")
        } while (marker.isNotEmpty())
        return out.sortedWith(compareBy<RemoteFile>({ !it.isDirectory }, { it.name.lowercase() }))
    }

    override suspend fun exists(path: String) = idOf(path) != null

    override suspend fun statDir(path: String): RemoteFile? {
        val p = normalizePath(path)
        if (p == "/") return RemoteFile("/", "", true, id = "root")
        return try {
            val j = api("/adrive/v1.0/openFile/get_by_path", JSONObject().put("file_path", p))
            toRemote(parentPath(p), j)
        } catch (e: StorageException) {
            if (e.code == 404) null else throw e
        }
    }

    override suspend fun mkdirs(path: String) { mkdirsId(path) }

    private suspend fun mkdirsId(path: String): String {
        var cur = "/"
        var curId = "root"
        for (seg in normalizePath(path).split('/').filter { it.isNotEmpty() }) {
            val next = joinPath(cur, seg)
            curId = idOf(next) ?: api("/adrive/v1.0/openFile/create", JSONObject()
                .put("parent_file_id", curId).put("name", seg).put("type", "folder").put("check_name_mode", "refuse"))
                .getString("file_id").also { ids[next] = it }
            cur = next
        }
        return curId
    }

    override suspend fun rename(file: RemoteFile, newName: String): RemoteFile {
        require(!newName.contains('/')) { "新名称不能包含 /" }
        val dest = joinPath(parentPath(file.path), newName)
        if (dest == normalizePath(file.path)) return file
        val id = file.id ?: idOf(file.path) ?: throw StorageException(404, "文件不存在：${file.path}")
        api("/adrive/v1.0/openFile/update", JSONObject().put("file_id", id).put("name", newName).put("check_name_mode", "refuse"))
        forget(file.path)
        return file.copy(path = dest, name = newName, id = id)
    }

    override suspend fun move(file: RemoteFile, targetDir: String): RemoteFile = moveAndRename(file, targetDir, file.name)

    /** 开放平台的 move 支持 new_name，一步完成 */
    override suspend fun moveAndRename(file: RemoteFile, targetDir: String, newName: String): RemoteFile {
        require(!newName.contains('/')) { "新名称不能包含 /" }
        val target = normalizePath(targetDir)
        if (parentPath(file.path) == target) return rename(file, newName)
        val id = file.id ?: idOf(file.path) ?: throw StorageException(404, "文件不存在：${file.path}")
        val targetId = mkdirsId(target)
        val j = api("/adrive/v1.0/openFile/move", JSONObject()
            .put("file_id", id).put("to_parent_file_id", targetId).put("new_name", newName).put("check_name_mode", "refuse"))
        if (j.optBoolean("exist")) throw StorageException(412, "目标已存在：${joinPath(target, newName)}")
        forget(file.path)
        return file.copy(path = joinPath(target, newName), name = newName, id = j.optString("file_id", id))
    }

    override suspend fun upload(targetPath: String, data: InputStream, size: Long) {
        val path = normalizePath(targetPath)
        val name = path.substringAfterLast('/')
        val parentId = mkdirsId(parentPath(path))
        val bytes = data.use { it.readBytes() }
        idOf(path)?.let { old ->                     // 覆盖：旧文件先进回收站
            api("/adrive/v1.0/openFile/recyclebin/trash", JSONObject().put("file_id", old))
            forget(path)
        }
        val created = api("/adrive/v1.0/openFile/create", JSONObject()
            .put("parent_file_id", parentId).put("name", name).put("type", "file").put("check_name_mode", "refuse")
            .put("size", bytes.size).put("part_info_list", JSONArray().put(JSONObject().put("part_number", 1))))
        if (!created.optBoolean("rapid_upload")) {
            val url = created.getJSONArray("part_info_list").getJSONObject(0).getString("upload_url")
            withContext(Dispatchers.IO) {
                // 预签名地址：不能带 Content-Type，否则签名对不上
                http.newCall(Request.Builder().url(url).put(bytes.toRequestBody(null)).build()).execute().use {
                    if (!it.isSuccessful) throw StorageException(it.code, "阿里云盘上传失败（HTTP ${it.code}）")
                }
            }
            api("/adrive/v1.0/openFile/complete", JSONObject()
                .put("file_id", created.getString("file_id")).put("upload_id", created.getString("upload_id")))
        }
    }

    override suspend fun delete(file: RemoteFile) {
        val id = file.id ?: idOf(file.path) ?: return
        api("/adrive/v1.0/openFile/recyclebin/trash", JSONObject().put("file_id", id))
        forget(file.path)
    }

    private fun parseTime(s: String): Long = runCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            .parse(s.take(19))!!.time
    }.getOrDefault(0)
}
