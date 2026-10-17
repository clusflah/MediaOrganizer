package com.example.mediaorganizer.storage.pan115

import com.example.mediaorganizer.storage.StorageException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 115 网盘网页接口（非官方，参考开源 115driver）。
 * 登录靠扫码拿到的 cookie（UID / CID / SEID / KID）。
 */
class Pan115Client(private val cookie: String, client: OkHttpClient? = null) {

    private val http = (client ?: OkHttpClient()).newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    data class Entry(val id: String, val parentId: String, val name: String, val isDir: Boolean, val size: Long, val modified: Long)

    // ---------- 通用 ----------

    private fun req(url: String) = Request.Builder().url(url)
        .header("Cookie", cookie)
        .header("User-Agent", UA)

    private suspend fun call(r: Request): String = withContext(Dispatchers.IO) {
        http.newCall(r).execute().use { resp ->
            if (resp.code == 401 || resp.code == 403) throw StorageException(401, "115 登录已失效，请重新扫码")
            if (!resp.isSuccessful) throw StorageException(resp.code, "115 请求失败（HTTP ${resp.code}）")
            resp.body!!.string()
        }
    }

    private suspend fun getJson(url: String) = JSONObject(call(req(url).get().build()))

    private suspend fun postJson(url: String, form: Map<String, String>): JSONObject {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        return JSONObject(call(req(url).post(body).build()))
    }

    /** 115 的通用返回：state=true 成功，否则 error / msg 里是原因 */
    private fun JSONObject.check(what: String): JSONObject {
        if (optBoolean("state", false)) return this
        val errno = optString("errno", optString("errNo", ""))
        val msg = optString("error").ifEmpty { optString("msg") }.ifEmpty { "未知错误" }
        if (errno == "990001" || msg.contains("登录")) throw StorageException(401, "115 登录已失效，请重新扫码")
        throw StorageException(500, "$what：$msg" + if (errno.isNotEmpty()) "（$errno）" else "")
    }

    // ---------- 账号 ----------

    /** 检查 cookie 是否有效，返回用户名 */
    suspend fun userName(): String {
        val j = getJson("https://my.115.com/?ct=ajax&ac=nav")
        if (!j.optBoolean("state")) throw StorageException(401, "115 登录已失效，请重新扫码")
        return j.optJSONObject("data")?.optString("user_name").orEmpty().ifEmpty { "115 用户" }
    }

    // ---------- 目录 / 文件 ----------

    /** 路径 -> 文件夹 ID；根目录是 "0"，不存在返回 null */
    suspend fun dirId(path: String): String? {
        if (path == "/" || path.isEmpty()) return "0"
        val j = getJson("https://webapi.115.com/files/getid?path=" + URLEncoder.encode(path, "UTF-8"))
        val id = j.opt("id")?.toString().orEmpty()
        return id.takeIf { it.isNotEmpty() && it != "0" }
    }

    suspend fun list(dirId: String): List<Entry> {
        val out = mutableListOf<Entry>()
        var offset = 0
        while (true) {
            val url = "https://webapi.115.com/files".toHttpUrl().newBuilder()
                .addQueryParameter("aid", "1").addQueryParameter("cid", dirId)
                .addQueryParameter("o", "file_name").addQueryParameter("asc", "1")
                .addQueryParameter("offset", offset.toString()).addQueryParameter("limit", PAGE.toString())
                .addQueryParameter("show_dir", "1").addQueryParameter("snap", "0").addQueryParameter("natsort", "1")
                .addQueryParameter("record_open_time", "1").addQueryParameter("format", "json")
                .build().toString()
            val j = getJson(url).check("读取目录失败")
            // cid 不存在时 115 会悄悄返回根目录，必须核对
            if (j.opt("cid")?.toString() != dirId) throw StorageException(404, "目录不存在")
            val data = j.optJSONArray("data") ?: JSONArray()
            for (i in 0 until data.length()) {
                val o = data.getJSONObject(i)
                val isDir = !o.has("fid")
                out += Entry(
                    id = if (isDir) o.optString("cid") else o.optString("fid"),
                    parentId = if (isDir) o.optString("pid") else o.optString("cid"),
                    name = o.optString("n"),
                    isDir = isDir,
                    size = o.opt("s")?.toString()?.toLongOrNull() ?: 0,
                    modified = (o.opt("te") ?: o.opt("tp"))?.toString()?.toLongOrNull()?.times(1000) ?: 0,
                )
            }
            val count = j.optInt("count", 0)
            offset += data.length()
            if (data.length() == 0 || offset >= count) break
        }
        return out
    }

    suspend fun mkdir(parentId: String, name: String): String {
        val j = postJson("https://webapi.115.com/files/add", mapOf("pid" to parentId, "cname" to name)).check("创建文件夹失败")
        return j.opt("cid")?.toString() ?: j.opt("file_id")?.toString() ?: throw StorageException(500, "创建文件夹失败：没有返回 ID")
    }

    suspend fun move(ids: List<String>, targetDirId: String) {
        val form = mutableMapOf("pid" to targetDirId)
        ids.forEachIndexed { i, id -> form["fid[$i]"] = id }
        postJson("https://webapi.115.com/files/move", form).check("移动失败")
    }

    suspend fun rename(id: String, newName: String) {
        postJson("https://webapi.115.com/files/batch_rename", mapOf("files_new_name[$id]" to newName)).check("改名失败")
    }

    /** 删除到 115 回收站，可在 115 里恢复 */
    suspend fun delete(ids: List<String>) {
        val form = mutableMapOf<String, String>()
        ids.forEachIndexed { i, id -> form["fid[$i]"] = id }
        postJson("https://webapi.115.com/rb/delete", form).check("删除失败")
    }

    // ---------- 上传（刮削写 NFO / 图片用） ----------

    private var userId = 0L
    private var userKey = ""

    private suspend fun ensureUploadInfo() {
        if (userId != 0L && userKey.isNotEmpty()) return
        val j = JSONObject(call(req("https://proapi.115.com/app/uploadinfo").post(FormBody.Builder().build()).build()))
        userId = j.optLong("user_id")
        userKey = j.optString("userkey")
        if (userId == 0L || userKey.isEmpty()) throw StorageException(401, "115 登录已失效，请重新扫码")
    }

    suspend fun upload(dirId: String, name: String, data: ByteArray) {
        ensureUploadInfo()
        val fileId = sha1Hex(data)
        val target = "U_1_$dirId"
        val cipher = Ec115Cipher()
        var signKey = ""
        var signVal = ""
        var init: JSONObject
        while (true) {
            val t = System.currentTimeMillis()
            val form = sortedMapOf(
                "appid" to "0", "appversion" to APP_VER, "userid" to userId.toString(),
                "filename" to name, "filesize" to data.size.toString(), "fileid" to fileId,
                "target" to target, "sig" to signature(fileId, target), "topupload" to "true",
                "t" to t.toString(), "token" to token(fileId, data.size.toString(), signKey, signVal, t.toString()),
            )
            if (signKey.isNotEmpty()) { form["sign_key"] = signKey; form["sign_val"] = signVal }
            val encoded = form.entries.joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8") }
            val url = "https://uplb.115.com/4.0/initupload.php?k_ec=" + URLEncoder.encode(cipher.encodeToken(t), "UTF-8")
            val raw = withContext(Dispatchers.IO) {
                http.newCall(
                    req(url).post(cipher.encrypt(encoded.toByteArray()).toRequestBody("application/x-www-form-urlencoded".toMediaType())).build()
                ).execute().use { it.body!!.bytes() }
            }
            init = JSONObject(String(cipher.decrypt(raw)))
            if (init.optInt("status") == 7) {          // 要求校验文件某一段的 sha1
                signKey = init.optString("sign_key")
                val (a, b) = init.optString("sign_check").split('-').map { it.toInt() }
                signVal = sha1Hex(data.copyOfRange(a, b + 1))
                continue
            }
            break
        }
        when (init.optInt("status")) {
            2 -> return                                   // 秒传成功
            1 -> ossPut(init, data)
            else -> throw StorageException(500, "115 上传初始化失败：" + init.optString("statusmsg").ifEmpty { init.toString() })
        }
    }

    /** 上传到 115 背后的阿里云 OSS（带回调，回调成功即入库） */
    private suspend fun ossPut(init: JSONObject, data: ByteArray) {
        val tok = getJson("https://uplb.115.com/3.0/gettoken.php")
        if (tok.optString("StatusCode") != "200") throw StorageException(500, "115 上传凭证获取失败")
        val bucket = init.optString("bucket")
        val obj = init.optString("object")
        val cb = init.optJSONObject("callback") ?: throw StorageException(500, "115 上传缺少回调参数")
        val callback = b64(cb.optString("callback").toByteArray())
        val callbackVar = b64(cb.optString("callback_var").toByteArray())
        val security = tok.optString("SecurityToken")
        val date = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }.format(Date())
        val type = "application/octet-stream"
        val ossHeaders = sortedMapOf(
            "x-oss-callback" to callback, "x-oss-callback-var" to callbackVar, "x-oss-security-token" to security,
        )
        val toSign = "PUT\n\n$type\n$date\n" + ossHeaders.entries.joinToString("") { "${it.key}:${it.value}\n" } + "/$bucket/$obj"
        val sig = b64(Mac.getInstance("HmacSHA1").apply {
            init(SecretKeySpec(tok.optString("AccessKeySecret").toByteArray(), "HmacSHA1"))
        }.doFinal(toSign.toByteArray()))
        val encodedObj = obj.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        val r = Request.Builder().url("https://$bucket.$OSS_ENDPOINT/$encodedObj")
            .put(data.toRequestBody(type.toMediaType()))
            .header("Date", date)
            .header("Authorization", "OSS ${tok.optString("AccessKeyID")}:$sig")
            .header("User-Agent", "aliyun-sdk-android/2.9.1")
            .apply { ossHeaders.forEach { (k, v) -> header(k, v) } }
            .build()
        withContext(Dispatchers.IO) {
            http.newCall(r).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.code != 200) throw StorageException(resp.code, "115 上传失败（OSS ${resp.code}）：${body.take(200)}")
                // 回调结果由 115 返回，state=false 说明没入库
                runCatching { JSONObject(body) }.getOrNull()?.let { if (it.has("state") && !it.optBoolean("state")) it.check("115 上传失败") }
            }
        }
    }

    private fun signature(fileId: String, target: String): String {
        val inner = sha1Hex("$userId$fileId${target}0".toByteArray()).lowercase()
        return sha1Hex("$userKey${inner}000000".toByteArray())
    }

    private fun token(fileId: String, size: String, signKey: String, signVal: String, t: String): String {
        val uid = userId.toString()
        return md5Hex(SALT + fileId + size + signKey + signVal + uid + t + md5Hex(uid.toByteArray()) + APP_VER)
    }

    companion object {
        const val UA = "Mozilla/5.0 115Browser/27.0.5.7"
        private const val APP_VER = "27.0.5.7"
        private const val SALT = "Qclm8MGWUv59TnrR0XPg"
        private const val OSS_ENDPOINT = "oss-cn-shenzhen.aliyuncs.com"
        private const val PAGE = 1000

        fun sha1Hex(b: ByteArray) = MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02X".format(it) }
        fun md5Hex(s: String) = md5Hex(s.toByteArray())
        fun md5Hex(b: ByteArray) = MessageDigest.getInstance("MD5").digest(b).joinToString("") { "%02x".format(it) }
        private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
    }
}
