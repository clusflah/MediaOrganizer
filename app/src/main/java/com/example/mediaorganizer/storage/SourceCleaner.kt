package com.example.mediaorganizer.storage

import com.example.mediaorganizer.parser.MediaNameParser

/** 整理后怎么处理视频原来所在的文件夹 */
enum class CleanupMode {
    OFF,          // 不动
    EMPTY_ONLY,   // 只删空文件夹（系统自动生成的 .DS_Store、@eaDir 等不算内容）
    LEFTOVERS,    // 连同残留的 NFO、图片、txt、样片等一起删
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
 * 本次整理的目标目录。文件夹里只要还有视频、字幕或认不出的文件就整个保留。
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
                if (mode == CleanupMode.LEFTOVERS && isLeftover(f)) { files += f.name; continue }
                return Verdict.Keep("还有 ${f.name}")
            }
        }
        return Verdict.Remove(files)
    }

    companion object {
        /** 整理/刮削工具和下载站常留下的附属文件；字幕不在其中，宁可保留 */
        private val LEFTOVER_EXT = setOf(
            "nfo", "jpg", "jpeg", "png", "webp", "gif", "bmp", "tbn",
            "txt", "url", "nzb", "torrent", "sfv", "md5", "srr", "xml",
        )

        fun isSystemJunk(name: String) =
            name == "@eaDir" || name == ".DS_Store" || name == "Thumbs.db" || name == "desktop.ini" ||
                name == "#recycle" || name.startsWith("._")

        fun isLeftover(f: RemoteFile): Boolean {
            val ext = f.name.substringAfterLast('.', "").lowercase()
            if (ext in LEFTOVER_EXT) return true
            // 样片：文件名带 sample 的视频
            return ext in MediaNameParser.VIDEO_EXTENSIONS && f.name.contains("sample", ignoreCase = true)
        }
    }
}
