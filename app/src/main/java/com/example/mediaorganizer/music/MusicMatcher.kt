package com.example.mediaorganizer.music

import com.example.mediaorganizer.storage.RemoteFile
import com.example.mediaorganizer.storage.StorageProvider
import com.example.mediaorganizer.storage.normalizePath
import com.example.mediaorganizer.storage.parentPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/** 从标签和文件名、文件夹名合起来猜出的歌曲信息 */
data class SongGuess(
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val track: Int? = null,
    val disc: Int? = null,
    val year: Int? = null,
)

data class SongFile(val file: RemoteFile, val tags: AudioTags, val guess: SongGuess)

/**
 * 一张专辑（同一文件夹、含 CD1/CD2 子文件夹）或一首零散的单曲。
 * @param dir 专辑所在的文件夹（整理后原文件夹可清理）
 */
data class AlbumGroup(
    val key: String,
    val dir: String,
    val files: List<SongFile>,
    val album: String?,
    val artist: String?,
    val year: Int?,
    val mbAlbumId: String? = null,
) {
    val single: Boolean get() = album == null
    val label: String get() = album ?: files.first().guess.title
}

data class TrackMatch(val song: SongFile, val track: MusicTrack?)

data class AlbumMatch(
    val group: AlbumGroup,
    val release: MusicRelease?,
    val score: Double,
    val alternatives: List<ReleaseCandidate>,
    val tracks: List<TrackMatch>,
) {
    val needsConfirm: Boolean get() = release == null || score < MusicMatcher.AUTO_ACCEPT
}

// ============================ 文件名解析 ============================

object MusicNameParser {
    /** 括号里的音质 / 格式说明：[FLAC]、(24bit-96kHz)、【无损】、{320K} */
    private val QUALITY = Regex(
        """(?i)[\[(【{（][^\])】}）]*(flac|wav|ape|mp3|aac|alac|dsd|dsf|hi-?res|\d+\s*bit|\d+(\.\d+)?\s*khz|\d{3}\s*k(bps)?|lossless|无损|整轨|分轨|web|sacd|vinyl|24-?\d{2}|cd\s*rip)[^\])】}）]*[\])】}）]""")
    private val YEAR_BRACKET = Regex("""[\[(（]\s*((?:19|20)\d{2})(?:[.\-]\d{1,2}){0,2}\s*[\])）]""")
    private val YEAR_LEAD = Regex("""^((?:19|20)\d{2})(?:[.\-]\d{1,2}){0,2}\s*[-._ ]\s*""")
    private val DISC_DIR = Regex("""(?i)^(?:cd|disc|disk|dvd)\s*[-_ .]?\s*(\d{1,2})(?:\s*[-_ ].*)?$|^第\s*(\d{1,2})\s*[张碟]$""")
    private val DISC_TRACK = Regex("""^(\d)[-.](\d{2})(?:\s*[-._、]\s*|\s+)(.+)$""")
    private val TRACK = Regex("""^(?:track\s*)?(\d{1,3})(?:\s*[-._、)\]]\s*|\s+)(.+)$""", RegexOption.IGNORE_CASE)
    private val BAD_VALUE = Regex("""(?i)^(unknown( artist| album)?|未知(艺术家|歌手|专辑)?|track\s*\d+|音轨\s*\d+|various|\s*)$""")

    fun discOf(dirName: String): Int? = DISC_DIR.find(dirName.trim())?.let { m ->
        (m.groupValues[1].ifEmpty { m.groupValues[2] }).toIntOrNull()
    }

    fun clean(s: String): String = s.replace(QUALITY, " ").replace(Regex("""\s+"""), " ").trim().trim('-', '_', '.', ' ')

    data class Folder(val album: String, val artist: String?, val year: Int?)

    /** 「周杰伦 - 叶惠美 (2003) [FLAC]」「2003 - 叶惠美」「[2003] 叶惠美」「周杰伦 - 2003 - 叶惠美」 */
    fun parseAlbumFolder(name: String): Folder {
        var s = clean(name)
        var year: Int? = null
        YEAR_BRACKET.find(s)?.let { year = it.groupValues[1].toInt(); s = s.removeRange(it.range).trim() }
        var artist: String? = null
        val parts = s.split(Regex("""\s+[-–—]\s+""")).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size >= 2) {
            val yi = parts.indexOfFirst { Regex("""^(19|20)\d{2}$""").matches(it) }
            if (yi >= 0) { year = year ?: parts[yi].toInt() }
            val rest = parts.filterIndexed { i, _ -> i != yi }
            if (rest.size >= 2) { artist = rest.first(); s = rest.drop(1).joinToString(" - ") } else s = rest.joinToString(" - ")
        }
        YEAR_LEAD.find(s)?.let { year = year ?: it.groupValues[1].toInt(); s = s.removeRange(it.range).trim() }
        return Folder(s.ifEmpty { clean(name) }, artist, year)
    }

    /** 文件名里的音轨号、艺人和歌名；knownArtist 用来判断「艺人 - 歌名」还是歌名本身带「-」 */
    fun parseFileName(fileName: String, knownArtist: String?): SongGuess {
        val base = clean(fileName.substringBeforeLast('.'))
        var disc: Int? = null
        var track: Int? = null
        var rest = base
        DISC_TRACK.find(base)?.let { m ->
            disc = m.groupValues[1].toInt(); track = m.groupValues[2].toInt(); rest = m.groupValues[3]
        } ?: TRACK.find(base)?.let { m ->
            val n = m.groupValues[1].toInt()
            if (n in 1..199) { track = n; rest = m.groupValues[2] }
        }
        var artist: String? = null
        val parts = rest.split(Regex("""\s+[-–—]\s+"""), limit = 2)
        if (parts.size == 2) {
            val first = parts[0].trim()
            val second = parts[1].trim()
            if (knownArtist == null || MusicMatcher.similarity(first, knownArtist) >= 0.8) {
                artist = first; rest = second
            } else if (MusicMatcher.similarity(second, knownArtist) >= 0.8) {
                artist = second; rest = first           // 「01 - 歌名 - 艺人」
            }
        }
        return SongGuess(title = rest.trim().ifEmpty { base }, artist = artist, track = track, disc = disc)
    }

    fun valid(s: String?): String? = s?.trim()?.takeIf { it.isNotEmpty() && !BAD_VALUE.matches(it) }
}

// ============================ 扫描 ============================

class MusicScanner(
    private val storage: StorageProvider,
    private val readTags: Boolean = true,
) {
    private var tagsSupported = true

    /** 递归扫描，按专辑分组；onProgress 报告已发现的歌曲数 */
    suspend fun scan(root: String, maxDepth: Int = 6, onProgress: (Int) -> Unit = {}): List<AlbumGroup> {
        val rootPath = normalizePath(root)
        val songs = mutableListOf<Pair<SongFile, List<String>>>()   // 歌曲 + 相对扫描目录的各级文件夹名

        suspend fun walk(dir: String, ancestors: List<String>, depth: Int) {
            val list = storage.list(dir)
            for (f in list) {
                if (storage.minRequestIntervalMs > 0) delay(storage.minRequestIntervalMs)
                if (f.isDirectory) {
                    if (depth < maxDepth && !f.name.startsWith(".") && !f.name.startsWith("@") && !f.name.startsWith("#"))
                        walk(f.path, ancestors + f.name, depth + 1)
                    continue
                }
                if (f.name.substringAfterLast('.', "").lowercase() !in AudioTagReader.AUDIO_EXTENSIONS) continue
                songs += SongFile(f, tags(f), SongGuess(f.name)) to ancestors
                onProgress(songs.size)
            }
        }
        walk(rootPath, emptyList(), 0)
        return group(rootPath, songs)
    }

    private suspend fun tags(f: RemoteFile): AudioTags {
        if (!readTags || !tagsSupported) return AudioTags()
        return try {
            var b = storage.readHead(f, AudioTagReader.HEAD_BYTES) ?: run { tagsSupported = false; return AudioTags() }
            AudioTagReader.needBytes(b)?.let { n -> storage.readHead(f, n)?.let { b = it } }
            AudioTagReader.read(b, f.size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AudioTags()                       // 读不了就按文件名
        }
    }

    companion object {
        private val GENERIC = Regex("""(?i)(音乐|歌曲|下载|downloads?|music|songs?|mp3|flac|无损|lossless|share|media|\d+)""")

        /** 按文件夹（CD1/CD2 合到上级）分组；文件夹里标签写着多张专辑的，再按专辑标签拆开 */
        internal fun group(root: String, songs: List<Pair<SongFile, List<String>>>): List<AlbumGroup> {
            val out = mutableListOf<AlbumGroup>()
            val byDir = LinkedHashMap<String, MutableList<Triple<SongFile, List<String>, Int?>>>()
            for ((song, anc) in songs) {
                val dir = parentPath(song.file.path)
                val disc = anc.lastOrNull()?.let(MusicNameParser::discOf)
                val albumDir = if (disc != null) parentPath(dir) else dir
                val albumAnc = if (disc != null) anc.dropLast(1) else anc
                byDir.getOrPut(albumDir) { mutableListOf() } += Triple(song, albumAnc, disc)
            }
            for ((dir, list) in byDir) {
                val anc = list.first().second
                val folderName = anc.lastOrNull()
                val isAlbumDir = dir != root && folderName != null && !GENERIC.matches(folderName.trim())
                val folder = folderName?.takeIf { isAlbumDir }?.let(MusicNameParser::parseAlbumFolder)
                // 专辑文件夹上一级当艺人：「周杰伦/叶惠美」
                val artistDir = anc.dropLast(1).lastOrNull()?.takeIf { isAlbumDir && !GENERIC.matches(it.trim()) }
                    ?.let(MusicNameParser::clean)
                val folderArtist = folder?.artist ?: artistDir

                val songs2 = list.map { (s, _, disc) -> withGuess(s, folder, folderArtist, disc) }
                val albums = songs2.mapNotNull { it.guess.album?.let(::normKey) }.distinct()
                if (isAlbumDir && albums.size <= 1) {
                    out += makeGroup(dir, dir, songs2, folder)
                    continue
                }
                // 杂乱的文件夹：同专辑标签的放一起，其余每首单独一组
                songs2.groupBy { s -> s.tags.album?.let(MusicNameParser::valid)?.let(::normKey) }.forEach { (k, ss) ->
                    if (k != null) out += makeGroup("$dir|$k", dir, ss, null)
                    else ss.forEach { s -> out += makeGroup(s.file.path, dir, listOf(s), null, single = true) }
                }
            }
            return out
        }

        private fun withGuess(s: SongFile, folder: MusicNameParser.Folder?, folderArtist: String?, discDir: Int?): SongFile {
            val t = s.tags
            val tagArtist = MusicNameParser.valid(t.albumArtist) ?: MusicNameParser.valid(t.artist)
            val name = MusicNameParser.parseFileName(s.file.name, tagArtist ?: folderArtist)
            return s.copy(guess = SongGuess(
                title = MusicNameParser.valid(t.title) ?: name.title,
                artist = MusicNameParser.valid(t.artist) ?: name.artist ?: folderArtist,
                album = MusicNameParser.valid(t.album) ?: folder?.album,
                track = t.track ?: name.track,
                disc = t.disc ?: discDir ?: name.disc,
                year = t.year ?: folder?.year,
            ))
        }

        private fun makeGroup(key: String, dir: String, songs: List<SongFile>, folder: MusicNameParser.Folder?, single: Boolean = false): AlbumGroup {
            fun <T> majority(f: (SongFile) -> T?): T? = songs.mapNotNull(f).groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            val albumArtist = majority { MusicNameParser.valid(it.tags.albumArtist) }
            return AlbumGroup(
                key = key, dir = dir,
                files = songs.sortedWith(compareBy({ it.guess.disc ?: 1 }, { it.guess.track ?: 999 }, { it.file.name })),
                album = if (single) null else majority { it.guess.album } ?: folder?.album,
                artist = albumArtist ?: majority { it.guess.artist } ?: folder?.artist,
                year = majority { it.guess.year },
                mbAlbumId = majority { it.tags.mbAlbumId },
            )
        }

        private fun normKey(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
    }
}

// ============================ 匹配 ============================

class MusicMatcher(
    private val mb: MusicBrainzClient,
    private val itunes: ItunesClient?,     // null = 不用 iTunes 兜底
) {
    private val releases = ConcurrentHashMap<String, MusicRelease>()

    suspend fun load(c: ReleaseCandidate): MusicRelease = load(c.source, c.id)

    suspend fun load(source: MusicSource, id: String): MusicRelease = releases.getOrPut("$source/$id") {
        when (source) {
            MusicSource.MUSICBRAINZ -> mb.release(id)
            MusicSource.ITUNES -> itunes?.album(id) ?: throw IllegalStateException("没有开启 iTunes")
        }
    }

    suspend fun search(album: String, artist: String?): List<ReleaseCandidate> {
        val fromMb = runCatching { mb.searchReleases(album, artist) }.getOrDefault(emptyList())
        val fromIt = itunes?.let { runCatching { it.searchAlbums(album, artist) }.getOrDefault(emptyList()) } ?: emptyList()
        return fromMb + fromIt
    }

    suspend fun match(g: AlbumGroup): AlbumMatch {
        // Picard 等工具写过 MusicBrainz ID 的，直接按 ID 取
        g.mbAlbumId?.let { id ->
            runCatching { load(MusicSource.MUSICBRAINZ, id) }.getOrNull()?.let { r ->
                return AlbumMatch(g, r, 1.0, emptyList(), assign(g, r))
            }
        }
        val candidates = mutableListOf<Pair<ReleaseCandidate, Double>>()
        if (g.single) {
            val song = g.files.first().guess
            mb.searchRecording(song.title, song.artist).take(10).forEach { candidates += it to singleScore(song, it) }
        } else {
            mb.searchReleases(g.album!!, g.artist).forEach { candidates += it to score(g, it) }
            // MusicBrainz 没有像样的结果时，用 iTunes 再搜一次（常见的商业专辑基本都有）
            val apple = itunes
            if (apple != null && (candidates.maxOfOrNull { it.second } ?: 0.0) < AUTO_ACCEPT)
                runCatching { apple.searchAlbums(g.album, g.artist) }.getOrDefault(emptyList())
                    .forEach { candidates += it to score(g, it) }
        }
        val ranked = candidates.sortedByDescending { it.second }.distinctBy { it.first.source to it.first.id }
        val best = ranked.firstOrNull() ?: return AlbumMatch(g, null, 0.0, emptyList(), g.files.map { TrackMatch(it, null) })
        val release = runCatching { load(best.first) }.getOrNull()
            ?: return AlbumMatch(g, null, 0.0, ranked.map { it.first }.take(10), g.files.map { TrackMatch(it, null) })
        val tracks = assign(g, release)
        return AlbumMatch(g, release, adjust(best.second, tracks), ranked.drop(1).map { it.first }.take(10), tracks)
    }

    /** 手动改选专辑 */
    suspend fun rematch(g: AlbumGroup, c: ReleaseCandidate, alternatives: List<ReleaseCandidate>): AlbumMatch {
        val r = load(c)
        return AlbumMatch(g, r, 1.0, alternatives.filter { it.id != c.id }, assign(g, r))
    }

    /** 曲目对不上的多，可信度打折 */
    private fun adjust(score: Double, tracks: List<TrackMatch>): Double {
        val matched = tracks.count { it.track != null }.toDouble() / tracks.size.coerceAtLeast(1)
        return when {
            matched >= 0.9 -> score
            matched >= 0.5 -> score * 0.9
            else -> score * 0.7
        }
    }

    companion object {
        const val AUTO_ACCEPT = 0.75

        fun score(g: AlbumGroup, c: ReleaseCandidate): Double {
            val n = g.files.size
            val title = similarity(g.album ?: "", c.title)
            val artist = g.artist?.let { similarity(it, c.artist) } ?: 0.5
            val count = when {
                c.trackCount == n -> 1.0
                c.trackCount > n -> 0.5 + 0.4 * n / c.trackCount      // 只有一部分曲目
                else -> 0.0                                          // 文件比专辑的歌还多，多半不是这张
            }
            val year = when {
                g.year == null || c.year == null -> 0.0
                g.year == c.year -> 0.05
                abs(g.year - c.year) <= 1 -> 0.02
                else -> -0.05
            }
            // 同一张专辑在 MusicBrainz 有很多版本：CD / 数字版优先于黑胶、磁带
            val format = if (c.format == null || c.format.contains("CD") || c.format.contains("Digital")) 0.03 else 0.0
            return (title * 0.55 + artist * 0.25 + count * 0.15 + year + format).coerceIn(0.0, 1.0)
        }

        private fun singleScore(s: SongGuess, c: ReleaseCandidate): Double {
            val artist = s.artist?.let { similarity(it, c.artist) } ?: 0.4
            val type = when (c.type) { "Album" -> 1.0; "Single", "EP" -> 0.8; else -> 0.5 }
            // 歌名已经由 MusicBrainz 搜索保证，这里看艺人和专辑类型；曲目对不上时 adjust 会再打折
            return (0.55 + artist * 0.35 + type * 0.1).coerceIn(0.0, 1.0)
        }

        /** 文件对应到专辑里的哪一首：先看音轨号（核对歌名），再按歌名找 */
        fun assign(g: AlbumGroup, r: MusicRelease): List<TrackMatch> {
            val discs = r.discCount
            val picks = g.files.map { s ->
                val gs = s.guess
                val byNumber = gs.track?.let { n ->
                    r.tracks.firstOrNull { it.number == n && it.disc == (gs.disc ?: 1).coerceAtMost(discs) }
                        ?: r.tracks.firstOrNull { it.number == n && discs == 1 }
                }
                val numberSim = byNumber?.let { similarity(gs.title, it.title) } ?: 0.0
                val byTitle = r.tracks.map { it to similarity(gs.title, it.title) }.maxByOrNull { it.second }
                val pick = when {
                    byNumber != null && (numberSim >= 0.5 || (byTitle?.second ?: 0.0) < 0.8) -> byNumber to maxOf(numberSim, 0.5)
                    byTitle != null && byTitle.second >= 0.6 -> byTitle
                    // 单曲：专辑里只有一首和它像
                    else -> null
                }
                s to pick
            }
            // 两个文件对到同一首时，留更像的那个
            val owner = HashMap<MusicTrack, Pair<SongFile, Double>>()
            for ((s, p) in picks) {
                if (p == null) continue
                val cur = owner[p.first]
                if (cur == null || p.second > cur.second) owner[p.first] = s to p.second
            }
            val bySong = owner.entries.associate { it.value.first.file.path to it.key }
            return g.files.map { TrackMatch(it, bySong[it.file.path]) }
        }

        /** 0~1，去掉标点空格、全角转半角后比较；括号里的版本说明（Live、Remastered）不影响太多 */
        fun similarity(a: String, b: String): Double {
            val x = norm(a)
            val y = norm(b)
            if (x.isEmpty() || y.isEmpty()) return 0.0
            val full = sim(x, y)
            if (full == 1.0) return 1.0
            val xs = norm(stripParens(a))
            val ys = norm(stripParens(b))
            val stripped = if (xs.isNotEmpty() && ys.isNotEmpty()) sim(xs, ys) * 0.95 else 0.0
            return maxOf(full, stripped)
        }

        private fun sim(x: String, y: String): Double = when {
            x == y -> 1.0
            x.contains(y) || y.contains(x) -> 0.4 + 0.5 * minOf(x.length, y.length) / maxOf(x.length, y.length)
            else -> dice(x, y)
        }

        private fun stripParens(s: String) = s.replace(Regex("""\s*[(\[（【][^)\]）】]*[)\]）】]"""), "")

        private fun norm(s: String): String {
            val sb = StringBuilder()
            for (ch in s.lowercase()) {
                val c = when (ch) {
                    in '！'..'～' -> ch - 0xFEE0    // 全角字母数字
                    '　' -> ' '
                    else -> ch
                }
                if (c.isLetterOrDigit()) sb.append(c)
            }
            return sb.toString()
        }

        private fun dice(a: String, b: String): Double {
            if (a.length < 2 || b.length < 2) return if (a == b) 1.0 else 0.0
            val ba = a.windowed(2).groupingBy { it }.eachCount().toMutableMap()
            var overlap = 0
            for (g in b.windowed(2)) {
                val n = ba[g] ?: 0
                if (n > 0) { overlap++; ba[g] = n - 1 }
            }
            return 2.0 * overlap / (a.length - 1 + b.length - 1)
        }
    }
}
