package com.example.mediaorganizer.storage.pan115

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 115 扫码登录：取二维码 → 轮询状态 → 用户在 115 App 里确认后换取 cookie。
 * 以「支付宝小程序」身份登录，不会把用户手机、电脑上已登录的 115 挤下线。
 */
class Pan115Login(client: OkHttpClient? = null) {

    data class Session(val uid: String, val time: Long, val sign: String, val qrContent: String) {
        val imageUrl get() = "https://qrcodeapi.115.com/api/1.0/mac/1.0/qrcode?uid=$uid"
    }

    enum class Status { WAITING, SCANNED, CONFIRMED, EXPIRED, CANCELED }

    private val http = (client ?: OkHttpClient()).newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)     // 状态接口是长轮询
        .build()

    private suspend fun json(r: Request): JSONObject = withContext(Dispatchers.IO) {
        http.newCall(r).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("115 请求失败（HTTP ${resp.code}）")
            JSONObject(resp.body!!.string())
        }
    }

    suspend fun start(): Session {
        val j = json(Request.Builder().url("https://qrcodeapi.115.com/api/1.0/web/1.0/token/").build())
        val d = j.optJSONObject("data") ?: throw IOException("获取 115 二维码失败")
        return Session(d.getString("uid"), d.getLong("time"), d.getString("sign"), d.getString("qrcode"))
    }

    suspend fun status(s: Session): Status {
        val url = "https://qrcodeapi.115.com/get/status/?uid=${s.uid}&time=${s.time}&sign=${s.sign}&_=${System.currentTimeMillis() / 1000}"
        val d = json(Request.Builder().url(url).build()).optJSONObject("data") ?: return Status.WAITING
        return when (d.optInt("status", 0)) {
            1 -> Status.SCANNED
            2 -> Status.CONFIRMED
            -1 -> Status.EXPIRED
            -2 -> Status.CANCELED
            else -> Status.WAITING
        }
    }

    suspend fun qrImage(s: Session): ByteArray = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(s.imageUrl).build()).execute().use { it.body!!.bytes() }
    }

    /** 确认后换 cookie，返回 "UID=..; CID=..; SEID=..; KID=.." */
    suspend fun finish(s: Session): String {
        val body = FormBody.Builder().add("account", s.uid).add("app", APP).build()
        val j = json(Request.Builder().url("https://passportapi.115.com/app/1.0/$APP/1.0/login/qrcode/").post(body).build())
        val c = j.optJSONObject("data")?.optJSONObject("cookie")
            ?: throw IOException("115 登录失败：" + j.optString("error").ifEmpty { j.optString("message") }.ifEmpty { j.toString().take(200) })
        return listOf("UID", "CID", "SEID", "KID").mapNotNull { k -> c.optString(k).takeIf { it.isNotEmpty() }?.let { "$k=$it" } }
            .joinToString("; ")
    }

    companion object {
        const val APP = "alipaymini"
    }
}
