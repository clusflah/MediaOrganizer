package com.example.mediaorganizer.naming

import com.example.mediaorganizer.metadata.MediaType
import com.example.mediaorganizer.metadata.MetaSource
import com.example.mediaorganizer.metadata.MetadataSource
import com.example.mediaorganizer.metadata.TmdbClient
import com.example.mediaorganizer.metadata.TmdbEpisode
import com.example.mediaorganizer.metadata.TmdbMovie
import com.example.mediaorganizer.metadata.TmdbSearchResult
import com.example.mediaorganizer.metadata.TmdbTvShow
import com.example.mediaorganizer.parser.ParsedMedia
import com.example.mediaorganizer.storage.FileOperation
import com.example.mediaorganizer.storage.RemoteFile
import com.example.mediaorganizer.storage.StorageException
import com.example.mediaorganizer.storage.StorageProvider
import com.example.mediaorganizer.storage.joinPath
import com.example.mediaorganizer.storage.normalizePath
import com.example.mediaorganizer.storage.parentPath
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

/** 一个待改名的文件：扫描得到的文件 + 解析结果 + 用户确认的 TMDB 条目 */
data class RenameItem(
    val file: RemoteFile,
    val parsed: ParsedMedia,
    val match: TmdbSearchResult,
)

data class RenamePlan(
    val item: RenameItem,
    val input: NamingInput,
    val targetDir: String,
    val targetName: String,
    val showDir: String = targetDir,     // 剧集根文件夹（放 tvshow.nfo、海报）；电影同 targetDir
    val ownFolder: Boolean = false,      // 是否在独占的文件夹里（决定能否用 poster.jpg 这类通用名）
) {
    val targetPath: String get() = joinPath(targetDir, targetName)
    val unchanged: Boolean get() = normalizePath(item.file.path) == targetPath
}

/** 媒体库目录：电影和剧集分别放到哪里 */
data class LibraryRoots(val movieRoot: String, val tvRoot: String)

/** 详情来源（带缓存）。抽成接口方便测试；id 是 source 那个站点的 ID */
interface MediaDetails {
    suspend fun movie(id: Int, source: MetaSource = MetaSource.TMDB): TmdbMovie
    suspend fun tv(id: Int, source: MetaSource = MetaSource.TMDB): TmdbTvShow
    suspend fun episodes(tvId: Int, season: Int, source: MetaSource = MetaSource.TMDB): List<TmdbEpisode>
}

/** TMDB 详情；手动选了豆瓣 / TheTVDB / Bangumi 条目的，交给 others 里对应的来源 */
class TmdbDetails(
    private val tmdb: TmdbClient,
    private val others: (MetaSource) -> MetadataSource? = { null },
) : MediaDetails {
    private val movies = ConcurrentHashMap<String, TmdbMovie>()
    private val shows = ConcurrentHashMap<String, TmdbTvShow>()
    private val seasons = ConcurrentHashMap<String, List<TmdbEpisode>>()

    private fun other(source: MetaSource) = others(source) ?: throw IllegalStateException("${source.label} 来源不可用")

    override suspend fun movie(id: Int, source: MetaSource) = movies["$source/$id"]
        ?: (if (source == MetaSource.TMDB) tmdb.movie(id) else other(source).movie(id)).also { movies["$source/$id"] = it }

    override suspend fun tv(id: Int, source: MetaSource) = shows["$source/$id"]
        ?: (if (source == MetaSource.TMDB) tmdb.tv(id) else other(source).tv(id)).also { shows["$source/$id"] = it }

    override suspend fun episodes(tvId: Int, season: Int, source: MetaSource): List<TmdbEpisode> {
        val key = "$source/$tvId/$season"
        seasons[key]?.let { return it }
        val list = runCatching {
            if (source == MetaSource.TMDB) tmdb.season(tvId, season) else other(source).season(tvId, season)
        }.getOrDefault(emptyList())
        seasons[key] = list
        return list
    }
}

class RenamePlanner(
    private val details: MediaDetails,
    private val roots: LibraryRoots,
) {
    suspend fun plan(items: List<RenameItem>, config: NamingConfig): List<RenamePlan> =
        plan(items, movieConfig = config, tvConfig = config)

    /** 电影和剧集分别使用各自的命名规则 */
    suspend fun plan(items: List<RenameItem>, movieConfig: NamingConfig, tvConfig: NamingConfig): List<RenamePlan> {
        val movieEngine = NamingEngine(movieConfig)
        val tvEngine = NamingEngine(tvConfig)
        return items.map { item ->
            val input = buildInput(item)
            val config = if (input.isEpisode) tvConfig else movieConfig
            val result = (if (input.isEpisode) tvEngine else movieEngine).build(input)
            val dir = when {
                config.tmdbTagMode == TmdbTagMode.TAG_ONLY -> parentPath(item.file.path)   // 原地改名
                // 电影不建独立文件夹时 folders 为空，直接放进电影库根目录
                input.isEpisode -> joinPath(roots.tvRoot, result.folders.joinToString("/"))
                else -> joinPath(roots.movieRoot, result.folders.joinToString("/"))
            }
            val showDir = if (input.isEpisode && result.folders.size >= 2) parentPath(dir) else dir
            RenamePlan(item, input, dir, result.fileName, showDir, ownFolder = result.folders.isNotEmpty())
        }
    }

    private suspend fun buildInput(item: RenameItem): NamingInput {
        val p = item.parsed
        val m = item.match
        val ext = item.file.name.substringAfterLast('.', "mkv")

        return if (m.type == MediaType.TV) {
            val show = details.tv(m.id, m.source)
            val season = p.season ?: 1
            val ep = p.episode?.let { e -> details.episodes(m.id, season, m.source).firstOrNull { it.episodeNumber == e } }
            NamingInput(
                isEpisode = true,
                chineseTitle = show.name.ifEmpty { m.title },
                englishTitle = latinOrNull(show.originalName),
                year = show.firstAirYear ?: m.year,
                seasonYear = show.seasons.firstOrNull { it.seasonNumber == season }?.airYear,
                season = season,
                episode = p.episode,
                episodeEnd = p.episodeEnd,
                imdbRating = show.voteAverage,
                episodeTitle = ep?.name?.takeIf { !it.matches(Regex("""第\s*\d+\s*集|Episode \d+""")) },
                tmdbId = m.id.takeIf { m.source == MetaSource.TMDB },
                ratingLabel = m.source.label,
                resolution = p.resolution, source = p.source,
                videoCodec = p.videoCodec, audioCodec = p.audioCodec,
                releaseGroup = p.releaseGroup,
                extension = ext,
                originalFileName = item.file.name,
            )
        } else {
            val movie = details.movie(m.id, m.source)
            NamingInput(
                isEpisode = false,
                chineseTitle = movie.title.ifEmpty { m.title },
                englishTitle = latinOrNull(movie.originalTitle),
                year = movie.year ?: m.year,
                tmdbId = m.id.takeIf { m.source == MetaSource.TMDB },
                ratingLabel = m.source.label,
                imdbRating = movie.voteAverage,
                resolution = p.resolution, source = p.source,
                videoCodec = p.videoCodec, audioCodec = p.audioCodec,
                releaseGroup = p.releaseGroup,
                extension = ext,
                originalFileName = item.file.name,
            )
        }
    }

    /** 原名是英文才当英文名用（日剧、韩剧的原名不是英文） */
    private fun latinOrNull(s: String?): String? =
        s?.takeIf { it.isNotBlank() && it.count { c -> c.isLetter() && c.code < 0x250 } >= it.count { c -> c.isLetter() } * 0.8 }
}

// ------------------------------------------------------------------

data class PlanOutcome(val plan: RenamePlan, val error: String? = null, val skipped: Boolean = false)

/** 一次移动：把 file 移到 targetDir 并改名为 targetName */
data class FileMove(val file: RemoteFile, val targetDir: String, val targetName: String) {
    val targetPath: String get() = joinPath(targetDir, targetName)
    val unchanged: Boolean get() = normalizePath(file.path) == targetPath
}

data class MoveOutcome(val error: String? = null, val skipped: Boolean = false)

data class ExecutionReport(
    val outcomes: List<PlanOutcome>,
    val operations: List<FileOperation>,     // 持久化后用于撤销
) {
    val succeeded get() = outcomes.count { it.error == null && !it.skipped }
    val failed get() = outcomes.count { it.error != null }
}

class RenameExecutor(
    private val storage: StorageProvider,
    private val storageId: String,
) {
    private val subtitleExt = setOf("srt", "ass", "ssa", "sub", "idx", "vtt", "sup")

    suspend fun execute(
        plans: List<RenamePlan>,
        onProgress: (done: Int, total: Int, outcome: PlanOutcome) -> Unit = { _, _, _ -> },
    ): ExecutionReport {
        val (results, ops) = executeMoves(plans.map { FileMove(it.item.file, it.targetDir, it.targetName) }, subtitleExt) { done, total, o ->
            onProgress(done, total, PlanOutcome(plans[done - 1], o.error, o.skipped))
        }
        return ExecutionReport(plans.zip(results) { p, o -> PlanOutcome(p, o.error, o.skipped) }, ops)
    }

    /**
     * 按顺序移动并改名；companions 是跟着主文件一起走的同名附属文件扩展名（字幕、歌词）：
     * xxx.chs.srt -> 新名.chs.srt
     */
    suspend fun executeMoves(
        moves: List<FileMove>,
        companions: Set<String>,
        onProgress: (done: Int, total: Int, outcome: MoveOutcome) -> Unit = { _, _, _ -> },
    ): Pair<List<MoveOutcome>, List<FileOperation>> {
        val ops = mutableListOf<FileOperation>()
        val outcomes = mutableListOf<MoveOutcome>()
        val dirCache = HashMap<String, List<RemoteFile>>()
        val claimed = HashSet<String>()      // 同一批里两个文件撞同一个目标名
        val knownDirs = HashSet<String>()    // 已确认存在（或本批次创建）的目录

        for ((i, m) in moves.withIndex()) {
            val outcome = runCatching {
                when {
                    m.unchanged -> MoveOutcome(skipped = true)
                    !claimed.add(m.targetPath) -> MoveOutcome(error = "与本批次另一个文件的新名称重复")
                    storage.exists(m.targetPath) -> MoveOutcome(error = "目标已存在")
                    else -> {
                        val srcDir = parentPath(m.file.path)
                        val siblings = dirCache.getOrPut(srcDir) { storage.list(srcDir) }

                        recordNewDirs(m.targetDir, knownDirs, ops)
                        storage.moveAndRename(m.file, m.targetDir, m.targetName)
                        ops += FileOperation(storageId, FileOperation.Type.MOVE, m.file.path, m.targetPath)

                        val oldBase = m.file.name.substringBeforeLast('.')
                        val newBase = m.targetName.substringBeforeLast('.')
                        siblings.filter {
                            !it.isDirectory && it.name.startsWith("$oldBase.") &&
                                it.name.substringAfterLast('.').lowercase() in companions
                        }.forEach { sub ->
                            val newName = newBase + sub.name.removePrefix(oldBase)
                            storage.moveAndRename(sub, m.targetDir, newName)
                            ops += FileOperation(storageId, FileOperation.Type.MOVE, sub.path, joinPath(m.targetDir, newName))
                        }
                        MoveOutcome()
                    }
                }
            }.getOrElse { MoveOutcome(error = it.message ?: it.toString()) }

            outcomes += outcome
            onProgress(i + 1, moves.size, outcome)
            if (storage.minRequestIntervalMs > 0) delay(storage.minRequestIntervalMs)
        }
        return outcomes to ops
    }

    /**
     * 记下这次要新建的目录（自下而上找到第一个已存在的为止），
     * 撤销时只删除这些由我们创建、且已经空了的目录，绝不碰原有目录。
     */
    private suspend fun recordNewDirs(targetDir: String, known: MutableSet<String>, ops: MutableList<FileOperation>) {
        val missing = mutableListOf<String>()
        var d = normalizePath(targetDir)
        while (d != "/" && d !in known && !storage.exists(d)) {
            missing += d
            d = parentPath(d)
        }
        known += d
        for (dir in missing.asReversed()) {
            ops += FileOperation(storageId, FileOperation.Type.MKDIR, "", dir)
            known += dir
        }
    }

    /**
     * 倒序撤销一批操作（整理 + 刮削）：
     * 删除刮削新写入的文件 → 文件移回原处 → 删除本次新建且已空的目录
     */
    suspend fun rollback(ops: List<FileOperation>): RollbackReport {
        val errors = mutableListOf<String>()
        var keptDirs = 0
        for (op in ops.asReversed()) {
            val name = op.toPath.substringAfterLast('/')
            try {
                when (op.type) {
                    FileOperation.Type.UPLOAD ->
                        storage.delete(RemoteFile(op.toPath, name, isDirectory = false))
                    FileOperation.Type.MOVE, FileOperation.Type.RENAME ->
                        storage.moveAndRename(
                            RemoteFile(op.toPath, name, isDirectory = false),
                            parentPath(op.fromPath), op.fromPath.substringAfterLast('/'),
                        )
                    FileOperation.Type.MKDIR -> {
                        val children = storage.list(op.toPath)
                        if (children.all { isJunk(it.name) }) storage.delete(RemoteFile(op.toPath, name, isDirectory = true))
                        else keptDirs++      // 里面有别的东西（用户后来放进去的），保留
                    }
                }
            } catch (e: StorageException) {
                // 要删的东西本来就不在了，视为成功
                if (e.code == 404 && op.type != FileOperation.Type.MOVE && op.type != FileOperation.Type.RENAME) continue
                errors += "$name：${e.message}"
            } catch (e: Exception) {
                errors += "$name：${e.message ?: e}"
            }
        }
        return RollbackReport(errors.size, keptDirs, errors)
    }

    /** NAS / 系统自动生成的文件，不算“目录里有东西” */
    private fun isJunk(name: String) =
        name == "@eaDir" || name == ".DS_Store" || name == "Thumbs.db" || name == "desktop.ini" ||
            name == "#recycle" || name.startsWith("._")
}

data class RollbackReport(val failed: Int, val keptDirs: Int, val errors: List<String>)
