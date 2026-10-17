package com.example.mediaorganizer.storage

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class SmbStorageTest {
    /** 没有 SMB 服务器时：客户端和 BouncyCastle 能正常初始化，连接失败抛 IOException 而不是崩溃 */
    @Test fun refusedConnectionIsAnIOException() = runBlocking {
        val s = SmbStorage("127.0.0.1:1", "video", "u", "p")
        try {
            s.connect()
            fail("不应连接成功")
        } catch (e: IOException) {
            println("OK: ${e.javaClass.simpleName}: ${e.message}")
            assertTrue(true)
        } finally {
            s.close()
        }
    }

    @Test fun hostParsing() {
        // 构造不联网，只验证不会因为 smb:// 前缀或端口崩溃
        SmbStorage("smb://192.168.1.10:445/ignored", "video", null, null)
        SmbStorage("nas.local", "video", "", "")
    }
}
