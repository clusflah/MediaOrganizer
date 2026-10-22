package com.example.mediaorganizer.storage

import com.example.mediaorganizer.music.AudioTagReader
import com.example.mediaorganizer.parser.MediaNameParser

/** 整理后怎么处理视频原来所在的文件夹 */
enum class CleanupMode {
    OFF,          // 不动
    EMPTY_ONLY,   // 只删空文件夹（系统自动生成的 .DS_Store、@eaDir 等不算内容）
    LEFTOVERS,    // 文件夹里没有视频就整个删（NFO、图片、字幕、txt、样片等一起删）
}

/** 一个可以删除的文件夹，files = 里面会被一起删掉的文件数 */
data class CleanupTarget(val dir: String, val files: Int, val sample: List<String>)

data class CleanupReport(
    val removed: List<CleanupTarget>,
    val kept: List<Pair<String, String>>,     // 文件夹 -> 保留原因
    val errors: List<String>,
)

/**
 * 只在扫描目录内部动手，且永远不删：扫描目录本身、影视库目录、它们的上级目录、
 * 本次整理的目标目录。文件夹里只要还有视频（样片不算）或正在下载的文件就整个保留。
 */
class SourceCleaner(
    private val storage: StorageProvider,
    private val mode: CleanupMode,
    scanRoot: String,
    protectedPaths: Collection<String>,
) {
    private val root = normalizePath(scanRoot)
    private val protected = (protectedPaths + scanRoot).map(::normalizePath).toSet()
    private val listings = HashMap<String, List<RemoteFile>>()

    private sealed interface Verdict {
        data class Remove(val files: List<String>) : Verdict
        data class Keep(val reason: String) : Verdict
    }

    /** 能不能考虑删这个目录（还没看里面的内容） */
    fun isRemovable(dir: String): Boolean {
        val d = normalizePath(dir)
        if (d == "/" || d == root) return false
        if (root != "/" && !d.startsWith("$root/")) return false
        return protected.none { it == d || it.startsWith("$d/") }
    }

    /** 整理后：检查这些原文件夹，能删的删掉，删完再看上一级是否也空了 */
    suspend fun cleanAfterMove(sourceDirs: Collection<String>): CleanupReport {
        if (mode == CleanupMode.OFF) return CleanupReport(emptyList(), emptyList(), emptyList())
        val removed = mutableListOf<CleanupTarget>()
        val kept = mutableListOf<Pair<String, String>>()
        val errors = mutableListOf<String>()
        val queue = sourceDirs.map(::normalizePath).distinct().sortedByDescending { it.count { c -> c == '/' } }.toMutableList()
        val seen = HashSet<String>()

        while (queue.isNotEmpty()) {
            val dir = queue.removeAt(0)
            if (!seen.add(dir) || !isRemovable(dir)) continue
            listings.remove(dir)                       // 刚删过子目录，重新列
            when (val v = runCatching { evaluate(dir) }.getOrElse { Verdict.Keep("读取失败：${it.message}") }) {
                is Verdict.Keep -> kept += dir to v.reason
                is Verdict.Remove -> runCatching {
                    storage.delete(RemoteFile(dir, dir.substringAfterLast('/'), isDirectory = true))
                    removed += CleanupTarget(dir, v.files.size, v.files.take(5))
                    val parent = parentPath(dir)
                    seen.remove(parent)
                    queue.add(0, parent)
                }.onFailure { errors += "${dir.substringAfterLast('/')}：${it.message}" }
            }
        }
        return CleanupReport(removed, kept.filter { (d, _) -> d in sourceDirs.map(::normalizePath) }, errors)
    }

    /** 手动清理：找出扫描目录里所有可以整个删掉的文件夹（只取最上层的那个），不删除 */
    suspend fun findLeftovers(): List<CleanupTarget> {
        val out = mutableListOf<CleanupTarget>()
        suspend fun walk(dir: String) {
            for (child in list(dir).filter { it.isDirectory }) {
                if (!isRemovable(child.path)) {
                    // 影视库目录本身不能删，但它下面的残留文件夹可以
                    if (protected.any { it == child.path || it.startsWith(child.path + "/") }) walk(child.path)
                    continue
                }
                when (val v = evaluate(child.path)) {
                    is Verdict.Remove -> out += CleanupTarget(child.path, v.files.size, v.files.take(5))
                    is Verdict.Keep -> walk(child.path)
                }
            }
        }
        walk(root)
        return out
    }

    suspend fun delete(targets: List<CleanupTarget>): CleanupReport {
        val removed = mutableListOf<CleanupTarget>()
        val errors = mutableListOf<String>()
        for (t in targets) {
            if (!isRemovable(t.dir)) continue
            runCatching { storage.delete(RemoteFile(t.dir, t.dir.substringAfterLast('/'), isDirectory = true)) }
                .onSuccess { removed += t }
                .onFailure { errors += "${t.dir.substringAfterLast('/')}：${it.message}" }
        }
        return CleanupReport(removed, emptyList(), errors)
    }

    private suspend fun list(dir: String) = listings.getOrPut(dir) { storage.list(dir) }

    /** 递归判断：整个目录里是不是只剩可删的东西 */
    private suspend fun evaluate(dir: String): Verdict {
        val files = mutableListOf<String>()
        for (f in list(dir)) {
            if (f.isDirectory) {
                if (isSystemJunk(f.name)) { files += f.name; continue }
                if (!isRemovable(f.path)) return Verdict.Keep("包含受保护的目录 ${f.name}")
                when (val v = evaluate(f.path)) {
                    is Verdict.Keep -> return v
                    is Verdict.Remove -> files += v.files.map { "${f.name}/$it" }
                }
            } else {
                if (isSystemJunk(f.name)) continue
                if (isDownloading(f.name)) return Verdict.Keep("${f.name} 还在下载")
                if (mode == CleanupMode.LEFTOVERS && !isVideo(f) && !isAudio(f)) { files += f.name; continue }
                return Verdict.Keep("还有 ${f.name}")
            }
        }
        return Verdict.Remove(files)
    }

    companion object {
        /** 视频格式，比解析用的列表多认一些（DVD 的 vob 等），宁可多保留 */
        private val VIDEO_EXT = MediaNameParser.VIDEO_EXTENSIONS +
            setOf("vob", "m4v", "mts", "3gp", "f4v", "divx", "ogv", "asf")

        /** 下载软件的临时文件，有它说明还没下完 */
        private val DOWNLOADING_EXT = setOf("part", "!qb", "!ut", "aria2", "downloading", "td", "bc!", "crdownload")

        fun isSystemJunk(name: String) =
            name == "@eaDir" || name == ".DS_Store" || name == "Thumbs.db" || name == "desktop.ini" ||
                name == "#recycle" || name.startsWith("._")

        fun isDownloading(name: String) = name.substringAfterLast('.', "").lowercase() in DOWNLOADING_EXT

        /** 音乐和视频一样算内容：电影和专辑放在同一个下载目录时，不能把专辑当残留删掉 */
        fun isAudio(f: RemoteFile) = f.name.substringAfterLast('.', "").lowercase() in AudioTagReader.AUDIO_EXTENSIONS

        /** 正片视频；文件名带 sample 的样片不算 */
        fun isVideo(f: RemoteFile): Boolean {
            val ext = f.name.substringAfterLast('.', "").lowercase()
            return ext in VIDEO_EXT && !f.name.contains("sample", ignoreCase = true)
        }
    }
}
