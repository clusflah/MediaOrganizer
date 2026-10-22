package com.example.mediaorganizer.music

import java.nio.charset.Charset

/** 音乐文件里已有的标签，都可能为空 */
data class AudioTags(
    val title: String? = null,
    val artist: String? = null,
    val albumArtist: String? = null,
    val album: String? = null,
    val track: Int? = null,
    val disc: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    val mbAlbumId: String? = null,     // MusicBrainz 发行 ID（Picard 等工具写入的）
    val durationSec: Int? = null,
) {
    val isEmpty: Boolean get() = title == null && artist == null && album == null && track == null
}

/**
 * 只读文件开头的一段解析标签，不需要下载整个文件：
 * MP3（ID3v2）、FLAC / Ogg / Opus（Vorbis Comment）、M4A（iTunes ilst，moov 在文件开头时）。
 */
object AudioTagReader {
    val AUDIO_EXTENSIONS = setOf("mp3", "flac", "m4a", "aac", "alac", "ogg", "oga", "opus", "wav", "ape", "wv", "dsf", "dff", "wma")

    /** 先读这么多；标签更大（内嵌了大封面）时按 needBytes 再读一次 */
    const val HEAD_BYTES = 128 * 1024
    const val MAX_BYTES = 8 * 1024 * 1024

    /** 标签完整解析需要的字节数，已读够返回 null */
    fun needBytes(b: ByteArray): Int? {
        val need = when {
            isId3(b) -> 10 + syncsafe(b, 6) + 4096        // 多读一点，后面紧跟的 FLAC / MP3 帧头也要用
            isFlac(b, 0) -> flacEnd(b, 0)
            else -> null
        } ?: return null
        return need.takeIf { it > b.size && it <= MAX_BYTES }
    }

    fun read(b: ByteArray, fileSize: Long = 0): AudioTags = runCatching {
        when {
            isId3(b) -> {
                val end = 10 + syncsafe(b, 6)
                val id3 = readId3(b)
                // 有的 FLAC 前面也带了 ID3
                if (isFlac(b, end)) merge(readFlac(b, end), id3) else id3.copy(durationSec = id3.durationSec ?: mp3Duration(b, end, fileSize))
            }
            isFlac(b, 0) -> readFlac(b, 0)
            b.size > 4 && String(b, 0, 4, Charsets.ISO_8859_1) == "OggS" -> readOgg(b)
            b.size > 8 && String(b, 4, 4, Charsets.ISO_8859_1) == "ftyp" -> readMp4(b)
            else -> AudioTags()
        }
    }.getOrDefault(AudioTags())

    private fun merge(a: AudioTags, b: AudioTags) = AudioTags(
        a.title ?: b.title, a.artist ?: b.artist, a.albumArtist ?: b.albumArtist, a.album ?: b.album,
        a.track ?: b.track, a.disc ?: b.disc, a.year ?: b.year, a.genre ?: b.genre, a.mbAlbumId ?: b.mbAlbumId,
        a.durationSec ?: b.durationSec,
    )

    // ---------- ID3v2 ----------

    private fun isId3(b: ByteArray) = b.size >= 10 && b[0] == 'I'.code.toByte() && b[1] == 'D'.code.toByte() && b[2] == '3'.code.toByte()

    private fun syncsafe(b: ByteArray, o: Int) =
        (b[o].toInt() and 0x7f shl 21) or (b[o + 1].toInt() and 0x7f shl 14) or (b[o + 2].toInt() and 0x7f shl 7) or (b[o + 3].toInt() and 0x7f)

    private fun be(b: ByteArray, o: Int, n: Int): Long {
        var v = 0L
        for (i in 0 until n) v = (v shl 8) or (b[o + i].toLong() and 0xff)
        return v
    }

    private fun le(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xff) or (b[o + 1].toInt() and 0xff shl 8) or (b[o + 2].toInt() and 0xff shl 16) or (b[o + 3].toInt() and 0xff shl 24)

    private fun readId3(b: ByteArray): AudioTags {
        val ver = b[3].toInt()
        val flags = b[5].toInt()
        val end = minOf(b.size, 10 + syncsafe(b, 6))
        var p = 10
        if (flags and 0x40 != 0 && ver >= 3) {             // 扩展头
            p += if (ver == 4) syncsafe(b, p) else 4 + be(b, p, 4).toInt()
        }
        val f = HashMap<String, String>()
        val txxx = HashMap<String, String>()
        val idLen = if (ver == 2) 3 else 4
        val headLen = if (ver == 2) 6 else 10
        while (p + headLen <= end) {
            val id = String(b, p, idLen, Charsets.ISO_8859_1)
            if (!id[0].isLetterOrDigit()) break           // 进入填充区
            val size = when (ver) {
                2 -> be(b, p + 3, 3).toInt()
                4 -> syncsafe(b, p + 4)
                else -> be(b, p + 4, 4).toInt()
            }
            val start = p + headLen
            if (size <= 0 || start + size > end) break
            val key = V22[id] ?: id
            if (key.startsWith("T")) {
                val text = decodeText(b, start, size)
                if (key == "TXXX") {
                    val parts = text.split('\u0000', limit = 2)
                    if (parts.size == 2) txxx[parts[0].lowercase()] = parts[1].trim('\u0000').trim()
                } else f.putIfAbsent(key, text.split('\u0000').first().trim())
            }
            p = start + size
        }
        fun num(s: String?) = s?.substringBefore('/')?.trim()?.toIntOrNull()
        return AudioTags(
            title = f["TIT2"].nn(), artist = f["TPE1"].nn(), albumArtist = f["TPE2"].nn(), album = f["TALB"].nn(),
            track = num(f["TRCK"]), disc = num(f["TPOS"]),
            year = yearOf(f["TDRC"] ?: f["TYER"] ?: f["TDRL"] ?: f["TORY"]),
            genre = f["TCON"]?.let(::id3Genre).nn(),
            mbAlbumId = txxx["musicbrainz album id"].nn(),
            durationSec = f["TLEN"]?.toLongOrNull()?.let { (it / 1000).toInt() }?.takeIf { it > 0 },
        )
    }

    /** ID3v2.2 的三字母帧名 */
    private val V22 = mapOf(
        "TT2" to "TIT2", "TP1" to "TPE1", "TP2" to "TPE2", "TAL" to "TALB", "TRK" to "TRCK", "TPA" to "TPOS",
        "TYE" to "TYER", "TCO" to "TCON", "TXX" to "TXXX", "TLE" to "TLEN",
    )

    private fun decodeText(b: ByteArray, o: Int, n: Int): String {
        if (n < 1) return ""
        val cs: Charset = when (b[o].toInt()) {
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> latin1OrGbk(b, o + 1, n - 1)
        }
        return String(b, o + 1, n - 1, cs).trimEnd('\u0000')
    }

    /** 国内老 MP3 常把 GBK 当 Latin-1 写进去：有高位字节且能按 GBK 正常解码就用 GBK */
    private fun latin1OrGbk(b: ByteArray, o: Int, n: Int): Charset {
        if ((o until o + n).none { b[it].toInt() and 0x80 != 0 }) return Charsets.ISO_8859_1
        val gbk = runCatching { Charset.forName("GBK") }.getOrNull() ?: return Charsets.ISO_8859_1
        val s = String(b, o, n, gbk)
        return if (s.contains('�') || s.none { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }) Charsets.ISO_8859_1 else gbk
    }

    /** "(17)"、"17"、"(17)Rock" -> 流派名；ID3v1 的流派表只留常见的 */
    private fun id3Genre(s: String): String {
        Regex("""^\((\d{1,3})\)(.*)$""").find(s)?.let { m ->
            return m.groupValues[2].ifBlank { GENRES.getOrNull(m.groupValues[1].toInt()) ?: s }
        }
        return s.toIntOrNull()?.let { GENRES.getOrNull(it) } ?: s
    }

    private val GENRES = listOf(
        "Blues", "Classic Rock", "Country", "Dance", "Disco", "Funk", "Grunge", "Hip-Hop", "Jazz", "Metal",
        "New Age", "Oldies", "Other", "Pop", "R&B", "Rap", "Reggae", "Rock", "Techno", "Industrial",
        "Alternative", "Ska", "Death Metal", "Pranks", "Soundtrack", "Euro-Techno", "Ambient", "Trip-Hop", "Vocal", "Jazz+Funk",
        "Fusion", "Trance", "Classical", "Instrumental", "Acid", "House", "Game", "Sound Clip", "Gospel", "Noise",
    )

    /** MP3 时长：优先 Xing/Info 帧数，否则按 CBR 码率和文件大小估算 */
    private fun mp3Duration(b: ByteArray, start: Int, fileSize: Long): Int? {
        var p = start
        while (p + 4 < b.size && !(b[p].toInt() and 0xff == 0xff && b[p + 1].toInt() and 0xe0 == 0xe0)) p++
        if (p + 4 >= b.size) return null
        val h = be(b, p, 4).toInt()
        val versionBits = h shr 19 and 3            // 3 = MPEG1, 2 = MPEG2, 0 = MPEG2.5
        val layer = h shr 17 and 3                  // 1 = Layer III
        if (layer != 1) return null
        val brIdx = h shr 12 and 0xf
        val srIdx = h shr 10 and 3
        val mono = (h shr 6 and 3) == 3
        val mpeg1 = versionBits == 3
        val sr = intArrayOf(44100, 48000, 32000).getOrNull(srIdx)?.let { if (mpeg1) it else if (versionBits == 2) it / 2 else it / 4 } ?: return null
        val samples = if (mpeg1) 1152 else 576
        val sideInfo = if (mpeg1) (if (mono) 17 else 32) else (if (mono) 9 else 17)
        val x = p + 4 + sideInfo
        if (x + 12 <= b.size) {
            val tag = String(b, x, 4, Charsets.ISO_8859_1)
            if ((tag == "Xing" || tag == "Info") && be(b, x + 4, 4).toInt() and 1 != 0) {
                val frames = be(b, x + 8, 4)
                if (frames > 0) return (frames * samples / sr).toInt()
            }
        }
        val kbps = (if (mpeg1) MPEG1_L3 else MPEG2_L3).getOrNull(brIdx)?.takeIf { it > 0 } ?: return null
        if (fileSize <= start) return null
        return ((fileSize - start) * 8 / (kbps * 1000L)).toInt()
    }

    private val MPEG1_L3 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)
    private val MPEG2_L3 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0)

    // ---------- FLAC ----------

    private fun isFlac(b: ByteArray, o: Int) = o + 4 <= b.size && String(b, o, 4, Charsets.ISO_8859_1) == "fLaC"

    /** 所有元数据块结束的位置（不在已读部分里的也能算出来，只要块头读到了） */
    private fun flacEnd(b: ByteArray, o: Int): Int? {
        var p = o + 4
        while (true) {
            if (p + 4 > b.size) return p + 4 + 65536
            val last = b[p].toInt() and 0x80 != 0
            p += 4 + be(b, p + 1, 3).toInt()
            if (last) return p
        }
    }

    private fun readFlac(b: ByteArray, o: Int): AudioTags {
        var p = o + 4
        var tags = AudioTags()
        var duration: Int? = null
        while (p + 4 <= b.size) {
            val type = b[p].toInt() and 0x7f
            val last = b[p].toInt() and 0x80 != 0
            val len = be(b, p + 1, 3).toInt()
            val start = p + 4
            if (start + len > b.size) break
            when (type) {
                0 -> if (len >= 18) {                     // STREAMINFO：采样率 20 位，总采样数 36 位
                    val sr = (be(b, start + 10, 3) shr 4).toInt()
                    val total = be(b, start + 13, 5) and 0xFFFFFFFFFL
                    if (sr > 0 && total > 0) duration = (total / sr).toInt()
                }
                4 -> tags = vorbis(b, start, start + len)
            }
            p = start + len
            if (last) break
        }
        return tags.copy(durationSec = duration ?: tags.durationSec)
    }

    // ---------- Ogg Vorbis / Opus ----------

    private fun readOgg(b: ByteArray): AudioTags {
        val text = String(b, Charsets.ISO_8859_1)
        val opus = text.indexOf("OpusTags")
        if (opus >= 0) return vorbis(b, opus + 8, b.size)
        val v = text.indexOf("\u0003vorbis")
        if (v >= 0) return vorbis(b, v + 7, b.size)
        return AudioTags()
    }

    /** Vorbis Comment：小端长度 + 厂商串，条数，每条 "KEY=value" */
    private fun vorbis(b: ByteArray, o: Int, end: Int): AudioTags {
        var p = o
        p += 4 + le(b, p)
        val count = le(b, p); p += 4
        val m = HashMap<String, String>()
        repeat(count.coerceAtMost(1000)) {
            if (p + 4 > end) return@repeat
            val len = le(b, p); p += 4
            if (len < 0 || p + len > end) return@repeat
            val s = String(b, p, len, Charsets.UTF_8); p += len
            val eq = s.indexOf('=')
            if (eq > 0) m.putIfAbsent(s.substring(0, eq).uppercase(), s.substring(eq + 1).trim())
        }
        fun num(k: String) = m[k]?.substringBefore('/')?.trim()?.toIntOrNull()
        return AudioTags(
            title = m["TITLE"].nn(), artist = m["ARTIST"].nn(),
            albumArtist = (m["ALBUMARTIST"] ?: m["ALBUM ARTIST"]).nn(), album = m["ALBUM"].nn(),
            track = num("TRACKNUMBER"), disc = num("DISCNUMBER"),
            year = yearOf(m["DATE"] ?: m["YEAR"] ?: m["ORIGINALDATE"]), genre = m["GENRE"].nn(),
            mbAlbumId = m["MUSICBRAINZ_ALBUMID"].nn(),
        )
    }

    // ---------- MP4 / M4A ----------

    private fun readMp4(b: ByteArray): AudioTags {
        val moov = atom(b, 0, b.size, "moov") ?: return AudioTags()
        var duration: Int? = null
        atom(b, moov.first, moov.second, "mvhd")?.let { (s, _) ->
            val v1 = b[s].toInt() == 1
            val scale = be(b, s + if (v1) 20 else 12, 4)
            val dur = be(b, s + if (v1) 24 else 16, if (v1) 8 else 4)
            if (scale > 0) duration = (dur / scale).toInt()
        }
        val udta = atom(b, moov.first, moov.second, "udta") ?: return AudioTags(durationSec = duration)
        val meta = atom(b, udta.first, udta.second, "meta") ?: return AudioTags(durationSec = duration)
        val ilst = atom(b, meta.first + 4, meta.second, "ilst") ?: return AudioTags(durationSec = duration)
        val m = HashMap<String, ByteArray>()
        var p = ilst.first
        while (p + 8 <= ilst.second) {
            val size = be(b, p, 4).toInt()
            if (size < 8 || p + size > ilst.second) break
            val name = String(b, p + 4, 4, Charsets.ISO_8859_1)
            atom(b, p + 8, p + size, "data")?.let { (s, e) -> if (e - s >= 8) m[name] = b.copyOfRange(s + 8, e) }
            p += size
        }
        fun str(k: String) = m[k]?.let { String(it, Charsets.UTF_8) }.nn()
        fun pair(k: String) = m[k]?.takeIf { it.size >= 4 }?.let { be(it, 2, 2).toInt() }?.takeIf { it > 0 }
        return AudioTags(
            title = str("©nam"), artist = str("©ART"), albumArtist = str("aART"), album = str("©alb"),
            track = pair("trkn"), disc = pair("disk"), year = yearOf(str("©day")), genre = str("©gen"),
            durationSec = duration,
        )
    }

    /** 在 [from, to) 里找名为 name 的子 atom，返回它内容的 [start, end) */
    private fun atom(b: ByteArray, from: Int, to: Int, name: String): Pair<Int, Int>? {
        var p = from
        val end = minOf(to, b.size)
        while (p + 8 <= end) {
            var size = be(b, p, 4)
            var head = 8
            if (size == 1L && p + 16 <= end) { size = be(b, p + 8, 8); head = 16 }
            if (size == 0L) size = (end - p).toLong()
            if (size < head) return null
            if (String(b, p + 4, 4, Charsets.ISO_8859_1) == name)
                return (p + head) to minOf((p + size).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), end)
            p += size.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        return null
    }

    private fun String?.nn() = this?.trim()?.takeIf { it.isNotEmpty() }
    private fun yearOf(s: String?) = s?.let { Regex("""(19|20)\d{2}""").find(it)?.value?.toIntOrNull() }
}
