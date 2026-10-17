package com.example.mediaorganizer.storage.pan115

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket

class Ec115CipherTest {
    @Test fun lz4LiteralAndMatch() {
        // "abcabcabcabc"：字面量 abc + 回溯 offset=3 长度 9
        val block = byteArrayOf(0x35, 'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(), 3, 0)
        assertEquals("abcabcabcabc", String(Lz4.decompressBlock(block, 64)))
    }

    /** 不登录直接请求 115 上传初始化：服务器会回一个加密的错误，能解出 JSON 就说明加解密都对 */
    @Test fun serverAcceptsOurEncryption() {
        val proxyUp = runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", 7890), 500) } }.isSuccess
        val http = OkHttpClient.Builder().apply { if (proxyUp) proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 7890))) }.build()
        val c = Ec115Cipher()
        val t = System.currentTimeMillis()
        val form = "appid=0&appversion=27.0.5.7&userid=1&filename=a.nfo&filesize=1&fileid=X&target=U_1_0&sig=X&topupload=true&t=$t&token=x"
        val req = Request.Builder()
            .url("https://uplb.115.com/4.0/initupload.php?k_ec=" + java.net.URLEncoder.encode(c.encodeToken(t / 1000), "UTF-8"))
            .post(c.encrypt(form.toByteArray()).toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build()
        val body = runCatching { http.newCall(req).execute().use { it.body!!.bytes() } }.getOrNull()
        assumeTrue("网络不可用，跳过", body != null && body.isNotEmpty())
        val json = String(c.decrypt(body!!))
        println("115 initupload 响应（解密后）：$json")
        JSONObject(json)       // 解不出合法 JSON 会抛异常
    }
}
