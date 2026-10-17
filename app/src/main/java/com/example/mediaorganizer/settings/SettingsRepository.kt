package com.example.mediaorganizer.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.settingsStore by preferencesDataStore(name = "settings")

class SettingsRepository(context: Context) {
    private val store = context.applicationContext.settingsStore

    /** 这些字段加密后再存 */
    private val secretKeys = setOf(
        "password", "tmdbToken", "smbPassword", "pan115Cookie", "aliClientSecret", "aliRefreshToken",
        "scrape.src.tvdbApiKey", "scrape.src.tvdbPin", "scrape.src.fanartApiKey",
        "libraries",   // 里面有各媒体库的密码、cookie，整体加密
    )

    suspend fun load(): AppSettings = withContext(Dispatchers.IO) {
        val (map, hasPlaintext) = readMap(store.data.first().asMap())
        val s = SettingsCodec.decode(map)
        if (hasPlaintext) save(s)      // 旧版本明文存储的，读出后立即加密重存
        s
    }

    suspend fun save(s: AppSettings) = withContext(Dispatchers.IO) {
        val map = SettingsCodec.encode(s).mapValues { (k, v) -> if (k in secretKeys) SecretCipher.encrypt(v) else v }
        store.edit { prefs ->
            prefs.clear()
            map.forEach { (k, v) -> prefs[stringPreferencesKey(k)] = v }
        }
        Unit
    }

    /** @return 解密后的 map，以及是否发现了未加密的敏感字段 */
    private fun readMap(raw: Map<Preferences.Key<*>, Any>): Pair<Map<String, String>, Boolean> {
        var plaintext = false
        val map = raw.entries.mapNotNull { (k, v) ->
            val value = v as? String ?: return@mapNotNull null
            val name = k.name
            if (name in secretKeys) {
                if (value.isNotEmpty() && !SecretCipher.isEncrypted(value)) plaintext = true
                name to SecretCipher.decrypt(value)
            } else name to value
        }.toMap()
        return map to plaintext
    }
}

/**
 * 用 Android Keystore 里的 AES-256-GCM 密钥加解密。
 * 密钥由系统（支持的设备上由安全硬件）保护、无法导出；卸载应用后密钥随之销毁。
 */
object SecretCipher {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "media_organizer_settings_key"
    private const val PREFIX = "enc:v1:"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LEN = 12

    fun isEncrypted(s: String) = s.startsWith(PREFIX)

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.encodeToString(data, Base64.NO_WRAP)
    }

    /** 解不开（比如从备份恢复到新手机，密钥不存在）就返回空串，让用户重新填写，而不是崩溃 */
    fun decrypt(value: String): String {
        if (!isEncrypted(value)) return value          // 旧版明文
        return try {
            val data = Base64.decode(value.removePrefix(PREFIX), Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, IV_LEN))
            String(cipher.doFinal(data, IV_LEN, data.size - IV_LEN), Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    @Synchronized
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }
}
