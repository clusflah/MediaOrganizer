package com.example.mediaorganizer.settings

import com.example.mediaorganizer.storage.StorageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibrariesTest {
    private val nas = AppSettings(
        webdavUrl = "http://nas:5005", username = "u", password = "p", scanDir = "/dl",
        lastTestAt = 123, lastTestOk = true,
    )

    @Test fun oldSettingsBecomeOneLibrary() {
        val all = nas.allLibraries()
        assertEquals(1, all.size)
        assertEquals("我的媒体库", all[0].name)
        assertEquals("http://nas:5005", all[0].webdavUrl)
    }

    @Test fun addSwitchAndKeepEachLibrarysFields() {
        val added = nas.addLibrary("115")
            .copy(storageType = StorageType.PAN115, pan115Cookie = "UID=1", scanDir = "/云下载")
        assertEquals(2, added.allLibraries().size)
        assertEquals("115", added.libraryName)
        assertNull("新媒体库没测过", added.lastTestOk)

        val back = added.switchLibrary("default")
        assertEquals(StorageType.WEBDAV, back.storageType)
        assertEquals("/dl", back.scanDir)
        assertEquals(true, back.lastTestOk)                  // 测试结果跟着媒体库走

        val again = back.switchLibrary(added.activeLibrary)
        assertEquals(StorageType.PAN115, again.storageType)
        assertEquals("UID=1", again.pan115Cookie)
        assertEquals("/云下载", again.scanDir)
    }

    @Test fun removeKeepsAtLeastOne() {
        assertEquals(nas, nas.removeActiveLibrary())          // 只有一个时不删
        val two = nas.addLibrary("B")
        val left = two.removeActiveLibrary()
        assertEquals("default", left.activeLibrary)
        assertEquals(1, left.allLibraries().size)
    }

    @Test fun codecRoundTrip() {
        val s = nas.addLibrary("SMB").copy(storageType = StorageType.SMB, smbHost = "10.0.0.2", smbPassword = "x")
        val back = SettingsCodec.decode(SettingsCodec.encode(s))
        assertEquals(s.activeLibrary, back.activeLibrary)
        assertEquals(s.allLibraries(), back.allLibraries())
        assertEquals("10.0.0.2", back.smbHost)
    }
}
