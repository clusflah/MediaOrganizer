package com.example.mediaorganizer.storage.pan115

import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.crypto.agreement.ECDHBasicAgreement
import org.bouncycastle.crypto.generators.ECKeyPairGenerator
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECKeyGenerationParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 115 上传初始化接口（initupload 4.0）的加密：P-224 ECDH 协商密钥，
 * 请求体用 AES 自定义链式加密，响应是 AES-CBC + LZ4 块压缩。
 * 移植自开源的 115driver（pkg/crypto/ec115）。
 */
class Ec115Cipher {
    private val key: ByteArray
    private val iv: ByteArray
    private val pubKey: ByteArray      // 1 字节长度 + 压缩格式公钥

    init {
        val curve = SECNamedCurves.getByName("secp224r1")
        val domain = ECDomainParameters(curve.curve, curve.g, curve.n, curve.h)
        val gen = ECKeyPairGenerator().apply { init(ECKeyGenerationParameters(domain, SecureRandom())) }
        val pair = gen.generateKeyPair()
        val priv = pair.private as ECPrivateKeyParameters
        val pub = (pair.public as ECPublicKeyParameters).q.normalize()

        val x = BigInteger(1, REMOTE_PUB.copyOfRange(0, 28))
        val y = BigInteger(1, REMOTE_PUB.copyOfRange(28, 56))
        val remote = ECPublicKeyParameters(curve.curve.createPoint(x, y), domain)

        val secret = ECDHBasicAgreement().apply { init(priv) }.calculateAgreement(remote).toFixed(28)
        key = secret.copyOfRange(0, 16)
        iv = secret.copyOfRange(secret.size - 16, secret.size)

        val px = pub.affineXCoord.toBigInteger().toFixed(28)
        val odd = pub.affineYCoord.toBigInteger().testBit(0)
        pubKey = byteArrayOf(29, if (odd) 3 else 2) + px
    }

    /** PKCS7 填充后逐块：明文 ^ 上一块密文（首块用 iv），再 AES-ECB */
    fun encrypt(plain: ByteArray): ByteArray {
        val padLen = 16 - plain.size % 16
        val data = plain + ByteArray(padLen) { padLen.toByte() }
        val aes = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")) }
        var xorKey = iv.copyOf()
        val out = ByteArray(data.size)
        for (b in data.indices step 16) {
            val block = ByteArray(16) { (data[b + it].toInt() xor xorKey[it].toInt()).toByte() }
            xorKey = aes.doFinal(block)
            System.arraycopy(xorKey, 0, out, b, 16)
        }
        return out
    }

    fun decrypt(cipherText: ByteArray): ByteArray {
        val usable = cipherText.copyOf(cipherText.size - cipherText.size % 16)
        val aes = Cipher.getInstance("AES/CBC/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(this@Ec115Cipher.iv))   // 不写全会取到 Cipher 自己的 iv（null）
        }
        val block = aes.doFinal(usable)
        val length = (block[0].toInt() and 0xff) or ((block[1].toInt() and 0xff) shl 8)
        return Lz4.decompressBlock(block.copyOfRange(2, 2 + length), maxOut = 0x2000)
    }

    /** 查询参数 k_ec：公钥和时间戳混淆后加 CRC，base64 */
    fun encodeToken(timestamp: Long): String {
        val rnd = SecureRandom()
        val r1 = rnd.nextInt(256).toByte()
        val r2 = rnd.nextInt(256).toByte()
        val time = ByteBuffer.allocate(4).putInt(timestamp.toInt()).array()
        val buf = ArrayList<Byte>(48)
        for (i in 0 until 15) buf += (pubKey[i].toInt() xor r1.toInt()).toByte()
        buf += r1; buf += (0x73 xor r1.toInt()).toByte()
        repeat(3) { buf += r1 }
        for (i in 0 until 4) buf += (r1.toInt() xor time[3 - i].toInt()).toByte()
        for (i in 15 until pubKey.size) buf += (pubKey[i].toInt() xor r2.toInt()).toByte()
        buf += r2; buf += (0x01 xor r2.toInt()).toByte()
        repeat(3) { buf += r2 }
        val tmp = buf.toByteArray()
        val crc = CRC32().apply { update(CRC_SALT.toByteArray()); update(tmp) }.value
        val crcBytes = ByteBuffer.allocate(4).putInt(crc.toInt()).array()
        return Base64.getEncoder().encodeToString(tmp + byteArrayOf(crcBytes[3], crcBytes[2], crcBytes[1], crcBytes[0]))
    }

    private fun BigInteger.toFixed(len: Int): ByteArray {
        val raw = toByteArray()
        return when {
            raw.size == len -> raw
            raw.size > len -> raw.copyOfRange(raw.size - len, raw.size)
            else -> ByteArray(len - raw.size) + raw
        }
    }

    companion object {
        private const val CRC_SALT = "^j>WD3Kr?J2gLFjD4W2y@"
        private val REMOTE_PUB = intArrayOf(
            0x57, 0xA2, 0x92, 0x57, 0xCD, 0x23, 0x20, 0xE5, 0xD6, 0xD1, 0x43, 0x32, 0x2F, 0xA4, 0xBB, 0x8A,
            0x3C, 0xF9, 0xD3, 0xCC, 0x62, 0x3E, 0xF5, 0xED, 0xAC, 0x62, 0xB7, 0x67, 0x8A, 0x89, 0xC9, 0x1A,
            0x83, 0xBA, 0x80, 0x0D, 0x61, 0x29, 0xF5, 0x22, 0xD0, 0x34, 0xC8, 0x95, 0xDD, 0x24, 0x65, 0x24,
            0x3A, 0xDD, 0xC2, 0x50, 0x95, 0x3B, 0xEE, 0xBA,
        ).map { it.toByte() }.toByteArray()
    }
}

/** LZ4 块格式解压（无帧头），只用于 115 的小响应 */
object Lz4 {
    fun decompressBlock(src: ByteArray, maxOut: Int): ByteArray {
        val out = ByteArray(maxOut)
        var si = 0
        var di = 0
        while (si < src.size) {
            val token = src[si++].toInt() and 0xff
            var litLen = token ushr 4
            if (litLen == 15) {
                var b: Int
                do { b = src[si++].toInt() and 0xff; litLen += b } while (b == 255)
            }
            System.arraycopy(src, si, out, di, litLen)
            si += litLen; di += litLen
            if (si >= src.size) break                    // 最后一段只有字面量
            val offset = (src[si].toInt() and 0xff) or ((src[si + 1].toInt() and 0xff) shl 8)
            si += 2
            var matchLen = (token and 0x0f)
            if (matchLen == 15) {
                var b: Int
                do { b = src[si++].toInt() and 0xff; matchLen += b } while (b == 255)
            }
            matchLen += 4
            var from = di - offset
            repeat(matchLen) { out[di++] = out[from++] }  // 可能重叠，逐字节复制
        }
        return out.copyOf(di)
    }
}
