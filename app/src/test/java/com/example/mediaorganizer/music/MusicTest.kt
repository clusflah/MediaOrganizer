package com.example.mediaorganizer.music

import com.example.mediaorganizer.storage.RemoteFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket

class MusicTest {

    // ---------- 标签 ----------

    private fun id3Frame(id: String, text: String, enc: Int = 3): ByteArray {
        val body = byteArrayOf(enc.toByte()) + text.toByteArray(if (enc == 1) Charsets.UTF_16 else Charsets.UTF_8)
        val n = body.size
        return id.toByteArray() + byteArrayOf((n shr 24).toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte(), 0, 0) + body
    }

    private fun id3(vararg frames: ByteArray): ByteArray {
        val body = frames.fold(ByteArray(0)) { a, b -> a + b } + ByteArray(64)
        val n = body.size
        val size = byteArrayOf((n shr 21 and 0x7f).toByte(), (n shr 14 and 0x7f).toByte(), (n shr 7 and 0x7f).toByte(), (n and 0x7f).toByte())
        return "ID3".toByteArray() + byteArrayOf(3, 0, 0) + size + body
    }

    @Test fun readsId3v23() {
        val b = id3(
            id3Frame("TIT2", "晴天"), id3Frame("TPE1", "周杰伦", enc = 1), id3Frame("TALB", "叶惠美"),
            id3Frame("TRCK", "3/11"), id3Frame("TYER", "2003"), id3Frame("TCON", "(13)"),
            id3Frame("TXXX", "MusicBrainz Album Id\u0000abc-123"),
        )
        val t = AudioTagReader.read(b)
        assertEquals("晴天", t.title)
        assertEquals("周杰伦", t.artist)
        assertEquals("叶惠美", t.album)
        assertEquals(3, t.track)
        assertEquals(2003, t.year)
        assertEquals("Pop", t.genre)
        assertEquals("abc-123", t.mbAlbumId)
    }

    @Test fun readsFlacVorbisAndDuration() {
        fun le(n: Int) = byteArrayOf(n.toByte(), (n shr 8).toByte(), (n shr 16).toByte(), (n shr 24).toByte())
        val comments = listOf("TITLE=Clocks", "ARTIST=Coldplay", "ALBUM=A Rush of Blood to the Head", "TRACKNUMBER=5", "DISCNUMBER=1", "DATE=2002-08-26")
        val vc = ByteArrayOutputStream().apply {
            val vendor = "ref".toByteArray(); write(le(vendor.size)); write(vendor)
            write(le(comments.size))
            comments.forEach { val c = it.toByteArray(); write(le(c.size)); write(c) }
        }.toByteArray()
        // STREAMINFO：44100Hz，总采样数 44100*307
        val si = ByteArray(34)
        val sr = 44100
        val total = 44100L * 307
        si[10] = (sr shr 12).toByte(); si[11] = (sr shr 4).toByte()
        si[12] = ((sr and 0xf) shl 4 or 0x02).toByte()
        si[13] = (0xF0 or ((total shr 32).toInt() and 0x0f)).toByte()
        si[14] = (total shr 24).toByte(); si[15] = (total shr 16).toByte(); si[16] = (total shr 8).toByte(); si[17] = total.toByte()
        val b = "fLaC".toByteArray() + byteArrayOf(0, 0, 0, 34) + si +
            byteArrayOf(0x84.toByte(), (vc.size shr 16).toByte(), (vc.size shr 8).toByte(), vc.size.toByte()) + vc
        val t = AudioTagReader.read(b)
        assertEquals("Clocks", t.title)
        assertEquals("A Rush of Blood to the Head", t.album)
        assertEquals(5, t.track)
        assertEquals(2002, t.year)
        assertEquals(307, t.durationSec)
    }

    @Test fun garbageIsEmpty() {
        assertTrue(AudioTagReader.read(ByteArray(100) { it.toByte() }).isEmpty)
        assertTrue(AudioTagReader.read(ByteArray(0)).isEmpty)
    }

    // ---------- 文件名 ----------

    @Test fun albumFolders() {
        val a = MusicNameParser.parseAlbumFolder("周杰伦 - 叶惠美 (2003) [FLAC]")
        assertEquals("叶惠美", a.album); assertEquals("周杰伦", a.artist); assertEquals(2003, a.year)
        val b = MusicNameParser.parseAlbumFolder("2002 - A Rush of Blood to the Head")
        assertEquals("A Rush of Blood to the Head", b.album); assertNull(b.artist); assertEquals(2002, b.year)
        val c = MusicNameParser.parseAlbumFolder("[2016] 林俊杰 - 和自己对话 (24bit-96kHz)")
        assertEquals("和自己对话", c.album); assertEquals("林俊杰", c.artist); assertEquals(2016, c.year)
        val d = MusicNameParser.parseAlbumFolder("Radiohead - 1997 - OK Computer")
        assertEquals("OK Computer", d.album); assertEquals("Radiohead", d.artist); assertEquals(1997, d.year)
        assertEquals(2, MusicNameParser.discOf("CD2"))
        assertEquals(1, MusicNameParser.discOf("Disc 1"))
        assertNull(MusicNameParser.discOf("CD Extra Stuff"))
    }

    @Test fun fileNames() {
        MusicNameParser.parseFileName("03 - 晴天.flac", "周杰伦").let { assertEquals(3, it.track); assertEquals("晴天", it.title) }
        MusicNameParser.parseFileName("03. 周杰伦 - 晴天.mp3", "周杰伦").let { assertEquals("晴天", it.title); assertEquals("周杰伦", it.artist) }
        MusicNameParser.parseFileName("周杰伦 - 晴天.mp3", null).let { assertNull(it.track); assertEquals("晴天", it.title); assertEquals("周杰伦", it.artist) }
        MusicNameParser.parseFileName("2-05 Clocks.flac", null).let { assertEquals(2, it.disc); assertEquals(5, it.track); assertEquals("Clocks", it.title) }
        MusicNameParser.parseFileName("1999.mp3", null).let { assertNull(it.track); assertEquals("1999", it.title) }
        // 歌名本身带「-」，又不是已知艺人：保持原样
        MusicNameParser.parseFileName("07 - Song 2 - Live.flac", "Blur").let { assertEquals("Song 2 - Live", it.title) }
    }

    // ---------- 分组 ----------

    private fun song(path: String, tags: AudioTags = AudioTags()) =
        SongFile(RemoteFile(path, path.substringAfterLast('/'), false, 5_000_000), tags, SongGuess(path.substringAfterLast('/')))

    @Test fun groupsAlbumWithDiscFolders() {
        val songs = listOf(
            song("/音乐/Coldplay/2002 - A Rush of Blood/CD1/01 - Politik.flac") to listOf("Coldplay", "2002 - A Rush of Blood", "CD1"),
            song("/音乐/Coldplay/2002 - A Rush of Blood/CD2/01 - Clocks (Live).flac") to listOf("Coldplay", "2002 - A Rush of Blood", "CD2"),
            song("/音乐/周杰伦 - 晴天.mp3") to emptyList(),
            song("/音乐/稻香.mp3", AudioTags(title = "稻香", artist = "周杰伦", album = "魔杰座")) to emptyList(),
        )
        val groups = MusicScanner.group("/音乐", songs)
        assertEquals(3, groups.size)
        val album = groups.first { it.album == "A Rush of Blood" }
        assertEquals("Coldplay", album.artist)
        assertEquals(2002, album.year)
        assertEquals(listOf(1, 2), album.files.map { it.guess.disc })
        val single = groups.first { it.single }
        assertEquals("晴天", single.files.single().guess.title)
        assertEquals("周杰伦", single.artist)
        assertEquals("魔杰座", groups.first { it.album == "魔杰座" }.album)
    }

    // ---------- 匹配 ----------

    private val release = MusicRelease(
        source = MusicSource.MUSICBRAINZ, id = "r1", title = "叶惠美", artist = "周杰伦", year = 2003,
        tracks = listOf("以父之名", "懦夫", "晴天", "三年二班").mapIndexed { i, t -> MusicTrack(1, i + 1, t, "周杰伦", 240) },
    )

    @Test fun scoresAndAssigns() {
        val files = listOf(
            song("/a/03 晴天.mp3").copy(guess = SongGuess("晴天", track = 3)),
            song("/a/x.mp3").copy(guess = SongGuess("三年二班")),           // 没有音轨号，按歌名
            song("/a/02 错误的名字.mp3").copy(guess = SongGuess("完全对不上的名字", track = 2)),
        )
        val g = AlbumGroup("k", "/a", files, "叶惠美", "周杰伦", 2003)
        val right = MusicMatcher.score(g, release.candidate())
        val wrong = MusicMatcher.score(g, release.candidate().copy(title = "范特西", year = 2001))
        assertTrue("$right", right >= MusicMatcher.AUTO_ACCEPT)
        assertTrue("$wrong", wrong < right - 0.3)

        val a = MusicMatcher.assign(g, release)
        assertEquals("晴天", a[0].track?.title)
        assertEquals("三年二班", a[1].track?.title)
        assertEquals("懦夫", a[2].track?.title)                      // 音轨号对上、歌名又不像别的歌
    }

    @Test fun similarity() {
        assertEquals(1.0, MusicMatcher.similarity("OK Computer", "ok computer"), 0.0)
        assertTrue(MusicMatcher.similarity("Clocks", "Clocks (Remastered 2008)") > 0.9)
        assertTrue(MusicMatcher.similarity("晴天", "三年二班") < 0.3)
        assertEquals(1.0, MusicMatcher.similarity("ＡＢＣ", "abc"), 0.0)
    }

    @Test fun plansNames() {
        val files = listOf(song("/dl/a/03 晴天.mp3").copy(guess = SongGuess("晴天", track = 3)))
        val g = AlbumGroup("k", "/dl/a", files, "叶惠美", "周杰伦", 2003)
        val m = AlbumMatch(g, release, 1.0, emptyList(), MusicMatcher.assign(g, release))
        val p = MusicPlanner.plan(m, "/音乐库", MusicConfig())!!
        assertEquals("/音乐库/周杰伦/叶惠美 (2003)", p.albumDir)
        assertEquals("03 - 晴天.mp3", p.tracks.single().move.targetName)
        val multi = release.copy(tracks = release.tracks + MusicTrack(2, 1, "AC/DC: 演唱会?", "周杰伦", 200))
        val f2 = listOf(song("/dl/a/x.flac").copy(guess = SongGuess("AC/DC: 演唱会?", track = 1, disc = 2)))
        val g2 = g.copy(files = f2)
        val p2 = MusicPlanner.plan(AlbumMatch(g2, multi, 1.0, emptyList(), MusicMatcher.assign(g2, multi)), "/音乐库", MusicConfig())!!
        assertEquals("2-01 - AC／DC： 演唱会？.flac", p2.tracks.single().move.targetName)
        val p3 = MusicPlanner.plan(AlbumMatch(g2, multi, 1.0, emptyList(), MusicMatcher.assign(g2, multi)), "/音乐库", MusicConfig(discFolders = true))!!
        assertEquals("/音乐库/周杰伦/叶惠美 (2003)/Disc 2", p3.tracks.single().move.targetDir)
    }

    @Test fun albumNfo() {
        val nfo = MusicNfo.album(release.copy(genres = listOf("Mandopop"), label = "Alfa & Music"))
        assertTrue(nfo.contains("<musicbrainzalbumid>r1</musicbrainzalbumid>"))
        assertTrue(nfo.contains("<label>Alfa &amp; Music</label>"))
        assertTrue(nfo.contains("<position>3</position>"))
        assertTrue(nfo.contains("<duration>4:00</duration>"))
        val artist = MusicNfo.artist("Coldplay", ArtistInfo("a1", "Coldplay", type = "Group", begin = "1996"))
        assertTrue(artist.contains("<formed>1996</formed>"))
    }

    // ---------- 联网（网络不通时跳过） ----------

    @Before fun proxy() {
        val up = runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", 7890), 500) } }.isSuccess
        if (up) listOf("http", "https").forEach { System.setProperty("$it.proxyHost", "127.0.0.1"); System.setProperty("$it.proxyPort", "7890") }
    }

    @Test fun musicBrainzLive() = runBlocking {
        val mb = MusicBrainzClient()
        val found = runCatching { mb.searchReleases("OK Computer", "Radiohead") }.getOrNull()
        assumeTrue("网络不可用，跳过", !found.isNullOrEmpty())
        val g = AlbumGroup("k", "/a", (1..12).map { song("/a/$it.flac").copy(guess = SongGuess("Track", track = it)) }, "OK Computer", "Radiohead", 1997)
        val m = MusicMatcher(mb, null).match(g)
        println("MusicBrainz：${m.release?.title} / ${m.release?.artist} / ${m.release?.date} 曲目 ${m.release?.tracks?.size} 分数 ${m.score}")
        assertEquals("OK Computer", m.release!!.title)
        assertEquals(12, m.release!!.tracks.size)
        assertTrue("${m.score}", m.score >= MusicMatcher.AUTO_ACCEPT)
        assertTrue(m.release!!.covers.isNotEmpty())
        assertNotNull(m.release!!.artistId)
    }

    @Test fun lrclibLive() = runBlocking {
        val ly = runCatching { LrclibClient().lyrics("Clocks", "Coldplay", "A Rush of Blood to the Head", 307) }.getOrNull()
        assumeTrue("网络不可用，跳过", ly != null)
        println("LRCLIB：${ly!!.synced?.lines()?.take(2)}")
        assertTrue(ly.synced != null || ly.plain != null)
    }

    @Test fun itunesLive() = runBlocking {
        val list = runCatching { ItunesClient(country = "US").searchAlbums("A Rush of Blood to the Head", "Coldplay") }.getOrNull()
        assumeTrue("网络不可用，跳过", !list.isNullOrEmpty())
        val r = ItunesClient(country = "US").album(list!!.first().id)
        println("iTunes：${r.title} 曲目 ${r.tracks.size} 封面 ${r.covers}")
        assertTrue(r.tracks.isNotEmpty())
        assertTrue(r.covers.single().contains("1200x1200"))
    }
}
