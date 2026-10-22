package com.example.mediaorganizer.music

import com.example.mediaorganizer.naming.FileMove
import com.example.mediaorganizer.scraper.Content
import com.example.mediaorganizer.scraper.ScrapeReport
import com.example.mediaorganizer.scraper.ScrapeStrategy
import com.example.mediaorganizer.scraper.writeOutputs
import com.example.mediaorganizer.storage.StorageProvider
import com.example.mediaorganizer.storage.joinPath
import com.example.mediaorganizer.storage.normalizePath
import com.example.mediaorganizer.storage.parentPath
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap

data class MusicConfig(
    val readTags: Boolean = true,        // 读文件里已有的标签（WebDAV / SMB / 本机）
    val itunes: Boolean = true,          // MusicBrainz 搜不到时用 iTunes，也补封面
    val itunesCountry: String = "CN",    // 商店地区，影响歌名是中文还是英文
    val cover: Boolean = true,           // cover.jpg
    val folderJpg: Boolean = true,       // 再写一份 folder.jpg（Windows / Infuse 的文件夹封面）
    val albumNfo: Boolean = true,
    val artistNfo: Boolean = true,
    val lyrics: Boolean = true,          // LRCLIB 同步歌词 .lrc
    val plainLyrics: Boolean = true,     // 没有同步歌词时写普通歌词 .txt
    val discFolders: Boolean = false,    // 多碟专辑：Disc 1/01 - 歌名，否则 1-01 - 歌名
)

/** 一首歌整理到哪里 */
data class TrackPlan(val song: SongFile, val track: MusicTrack?, val move: FileMove)

data class AlbumPlan(
    val match: AlbumMatch,
    val release: MusicRelease,
    val albumDir: String,
    val artistDir: String?,              // null = 不写 artist.nfo（原地刮削、上级不是艺人文件夹）
    val tracks: List<TrackPlan>,
)

// ============================ 命名 ============================

object MusicPlanner {
    /** 歌词跟着歌走 */
    val COMPANIONS = setOf("lrc", "txt")

    /**
     * 音乐库/艺人/专辑 (年份)/01 - 歌名.flac，Plex / Jellyfin / Emby / Kodi 都按这个结构识别。
     * @param inPlace 只刮削不移动：文件留在原处
     */
    fun plan(m: AlbumMatch, root: String, c: MusicConfig, inPlace: Boolean = false): AlbumPlan? {
        val r = m.release ?: return null
        val artistDir = if (inPlace) null else joinPath(root, safe(r.artist.ifBlank { m.group.artist ?: "未知艺人" }))
        val albumDir = if (inPlace) m.group.dir
            else joinPath(artistDir!!, safe(r.title) + (r.year?.let { " ($it)" } ?: ""))
        val multiDisc = r.discCount > 1
        val tracks = m.tracks.map { tm ->
            val f = tm.song.file
            val ext = f.name.substringAfterLast('.', "mp3")
            val t = tm.track
            if (inPlace) return@map TrackPlan(tm.song, t, FileMove(f, parentPath(f.path), f.name))
            val disc = t?.disc ?: tm.song.guess.disc ?: 1
            val number = t?.number ?: tm.song.guess.track
            val title = t?.title ?: tm.song.guess.title
            val name = when {
                number == null -> f.name                    // 对不上又没有音轨号：保留原名
                multiDisc && !c.discFolders -> "$disc-%02d - %s.%s".format(number, safe(title), ext)
                else -> "%02d - %s.%s".format(number, safe(title), ext)
            }
            val dir = if (multiDisc && c.discFolders) joinPath(albumDir, "Disc $disc") else albumDir
            TrackPlan(tm.song, t, FileMove(f, dir, name))
        }
        return AlbumPlan(m, r, albumDir, artistDir, tracks)
    }

    /** 文件名里不能用的字符换成全角，去掉首尾的点和空格 */
    fun safe(s: String): String {
        val map = mapOf('/' to '／', '\\' to '＼', ':' to '：', '*' to '＊', '?' to '？', '"' to '＂', '<' to '＜', '>' to '＞', '|' to '｜')
        return s.map { map[it] ?: it }.joinToString("").filter { it >= ' ' }.trim().trim('.', ' ').take(120).ifEmpty { "_" }
    }
}

// ============================ 刮削 ============================

class MusicScraper(
    private val storage: StorageProvider,
    private val storageId: String,
    private val mb: MusicBrainzClient,
    private val itunes: ItunesClient?,
    private val lrclib: LrclibClient,
) {
    private val artists = ConcurrentHashMap<String, Result<ArtistInfo?>>()

    /**
     * @param onProgress stage = 「获取歌词」「写入」，done/total 是这个阶段的进度
     */
    suspend fun scrape(
        plans: List<AlbumPlan>,
        c: MusicConfig,
        strategy: ScrapeStrategy,
        onProgress: (stage: String, done: Int, total: Int) -> Unit = { _, _, _ -> },
    ): ScrapeReport {
        val outputs = LinkedHashMap<String, Content>()
        val errors = mutableListOf<String>()
        fun add(path: String, content: Content?) { if (content != null) outputs.putIfAbsent(normalizePath(path), content) }

        val totalTracks = plans.sumOf { it.tracks.size }
        var done = 0
        for (p in plans) {
            val r = p.release
            try {
                if (c.cover) cover(r)?.let { img ->
                    add(joinPath(p.albumDir, "cover.jpg"), img)
                    if (c.folderJpg) add(joinPath(p.albumDir, "folder.jpg"), img)
                }
                if (c.albumNfo) add(joinPath(p.albumDir, "album.nfo"), Content.Text(MusicNfo.album(r)))
                if (c.artistNfo && p.artistDir != null) {
                    val info = r.artistId?.let { id -> artists.getOrPut(id) { runCatching { mb.artist(id) } }.getOrNull() }
                    add(joinPath(p.artistDir, "artist.nfo"), Content.Text(MusicNfo.artist(r.artist, info)))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errors += "${r.title}：${e.message}"
            }
            if (c.lyrics) {
                // 已经有歌词的（用户自己的、跟着歌一起移过来的）不再下载
                val existing = HashMap<String, Set<String>>()
                for (t in p.tracks) {
                    done++
                    onProgress("获取歌词", done, totalTracks)
                    val dir = t.move.targetDir
                    val names = existing.getOrPut(dir) { runCatching { storage.list(dir).map { it.name }.toSet() }.getOrDefault(emptySet()) }
                    val base = t.move.targetName.substringBeforeLast('.')
                    if ("$base.lrc" in names || "$base.txt" in names) continue
                    val title = t.track?.title ?: t.song.guess.title
                    val artist = t.track?.artist ?: r.artist
                    val ly = runCatching { lrclib.lyrics(title, artist, r.title, t.track?.lengthSec ?: t.song.tags.durationSec) }.getOrNull()
                        ?: continue
                    when {
                        ly.synced != null -> add(joinPath(dir, "$base.lrc"), Content.Text(ly.synced))
                        ly.plain != null && c.plainLyrics -> add(joinPath(dir, "$base.txt"), Content.Text(ly.plain))
                    }
                }
            }
        }
        val report = writeOutputs(storage, outputs, strategy, storageId, mb::download) { d, t -> onProgress("写入", d, t) }
        return report.copy(errors = errors + report.errors)
    }

    /** Cover Art Archive 没有这张发行的封面时，依次试专辑组封面、iTunes */
    private suspend fun cover(r: MusicRelease): Content.Image? {
        val list = r.covers.toMutableList()
        if (itunes != null && r.source == MusicSource.MUSICBRAINZ && list.none { "/release/" in it })
            itunes.coverFor(r.title, r.artist)?.let { list += it }
        return list.firstOrNull()?.let { Content.Image(it, list.drop(1)) }
    }
}

// ============================ NFO（Kodi 音乐格式，Jellyfin / Emby 也读） ============================

object MusicNfo {
    fun album(r: MusicRelease): String = doc("album") {
        tag("title", r.title)
        if (r.source == MusicSource.MUSICBRAINZ) {
            tag("musicbrainzalbumid", r.id)
            tag("musicbrainzreleasegroupid", r.releaseGroupId)
        }
        tag("artistdesc", r.artist)
        raw("  <albumArtistCredits>\n")
        tag("artist", r.artist, indent = "    ")
        tag("musicbrainzartistid", r.artistId, indent = "    ")
        raw("  </albumArtistCredits>\n")
        r.genres.forEach { tag("genre", it) }
        tag("releasetype", r.type?.lowercase())
        tag("label", r.label)
        tag("catalognumber", r.catalogNo)
        tag("barcode", r.barcode)
        tag("year", r.year)
        tag("releasedate", r.date)
        val multi = r.discCount > 1
        for (t in r.tracks) {
            raw("  <track>\n")
            if (multi) tag("disc", t.disc, indent = "    ")
            tag("position", t.number, indent = "    ")
            tag("title", t.title, indent = "    ")
            t.lengthSec?.let { tag("duration", "%d:%02d".format(it / 60, it % 60), indent = "    ") }
            tag("musicbrainztrackid", t.recordingId, indent = "    ")
            raw("  </track>\n")
        }
    }

    fun artist(name: String, a: ArtistInfo?): String = doc("artist") {
        tag("name", a?.name?.ifBlank { null } ?: name)
        tag("sortname", a?.sortName)
        tag("musicbrainzartistid", a?.id)
        tag("type", a?.type)
        tag("disambiguation", a?.disambiguation)
        a?.genres?.forEach { tag("genre", it) }
        val group = a?.type == "Group" || a?.type == "Orchestra" || a?.type == "Choir"
        tag(if (group) "formed" else "born", a?.begin)
        tag(if (group) "disbanded" else "died", a?.end)
        tag("country", a?.country)
    }

    private class B {
        val sb = StringBuilder()
        fun raw(s: String) { sb.append(s) }
        fun tag(name: String, value: Any?, indent: String = "  ") {
            val v = value?.toString()?.trim()
            if (!v.isNullOrEmpty()) sb.append(indent).append("<$name>").append(escape(v)).append("</$name>\n")
        }
    }

    private fun doc(root: String, body: B.() -> Unit): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<$root>\n${B().apply(body).sb}</$root>\n"

    private fun escape(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")
        .replace(Regex("""[\u0000-\u0008\u000B\u000C\u000E-\u001F]"""), "")
}
