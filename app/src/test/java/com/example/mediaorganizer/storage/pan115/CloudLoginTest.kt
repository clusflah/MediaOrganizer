package com.example.mediaorganizer.storage.pan115

import com.example.mediaorganizer.storage.StorageException
import com.example.mediaorganizer.storage.aliyun.AliyunLogin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Socket

/** 不需要账号的那部分登录流程，直接打真实服务器 */
class CloudLoginTest {
    @Before fun proxy() {
        val up = runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", 7890), 500) } }.isSuccess
        if (up) {
            System.setProperty("https.proxyHost", "127.0.0.1"); System.setProperty("https.proxyPort", "7890")
            System.setProperty("http.proxyHost", "127.0.0.1"); System.setProperty("http.proxyPort", "7890")
        }
    }

    @Test fun pan115QrFlowStarts() = runBlocking {
        val login = Pan115Login()
        val s = runCatching { login.start() }.getOrNull()
        assumeTrue("网络不可用，跳过", s != null)
        println("115 二维码内容：${s!!.qrContent}")
        val png = login.qrImage(s)
        assertTrue("二维码图片应是 PNG", png.size > 100 && png[1] == 'P'.code.toByte() && png[2] == 'N'.code.toByte())
        assertEquals(Pan115Login.Status.WAITING, login.status(s))
    }

    @Test fun aliyunBadAppIdIsReadable() = runBlocking {
        val r = runCatching { AliyunLogin("bogus", "bogus").start() }
        val e = r.exceptionOrNull()
        assumeTrue("网络不可用，跳过", e !is java.io.IOException || e is StorageException)
        println("阿里云盘错误：${e?.message}")
        assertTrue(e is StorageException && e.message!!.contains("AppID"))
    }
}
