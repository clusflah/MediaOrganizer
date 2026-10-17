package com.example.mediaorganizer.naming

import com.example.mediaorganizer.parser.MediaNameParser
import com.example.mediaorganizer.storage.RemoteFile
import com.example.mediaorganizer.storage.StorageException
import com.example.mediaorganizer.storage.StorageProvider
import com.example.mediaorganizer.storage.normalizePath
import com.example.mediaorganizer.storage.parentPath

/** 整理前预检发现的问题类型，按严重程度排序 */
enum class ConflictKind(val label: String) {
    EXISTS("目标已存在"),
    DUPLICATE("本批次重名"),
    MISSING_META("元数据不全"),
}

/** 每个问题文件可选的处理方式 */
enum class ConflictAction(val label: String) {
    PROCEED("照常整理"),
    SKIP("跳过"),
    KEEP_BOTH("保留两者"),        // 新文件名后加 (2)
    OVERWRITE("覆盖旧文件"),      // 先删掉目标位置的旧文件
    DELETE_SOURCE("删除此文件"),  // 重复的源文件直接删掉
}

data class Conflict(
    val key: String,
    val plan: RenamePlan,
    val kind: ConflictKind,
    val detail: String,
    val existingSize: Long? = null,
) {
    /** 第一个是默认动作：都偏向不动用户的文件 */
    val actions: List<ConflictAction> get() = when (kind) {
        ConflictKind.EXISTS -> listOf(ConflictAction.SKIP, ConflictAction.KEEP_BOTH, ConflictAction.OVERWRITE, ConflictAction.DELETE_SOURCE)
        ConflictKind.DUPLICATE -> listOf(ConflictAction.SKIP, ConflictAction.KEEP_BOTH, ConflictAction.DELETE_SOURCE)
        ConflictKind.MISSING_META -> listOf(ConflictAction.PROCEED, ConflictAction.SKIP)
    }
}

data class PrecheckResult(
    val conflicts: List<Conflict>,
    val newDirs: Set<String>,                      // 整理时会新建的目录
    val existingNames: Map<String, Set<String>>,   // 目标目录 -> 里面已有的文件名（「保留两者」取新名字用）
) {
    fun conflictOf(key: String) = conflicts.firstOrNull { it.key == key }
}

/**
 * 对一批整理计划做预检：目标是否已有文件、本批次是否撞名、元数据是否不全。
 * 每个目标目录只列一次，几千个文件也只需按作品数量发请求。
 */
class Precheck(private val storage: StorageProvider) {

    suspend fun run(items: List<Pair<String, RenamePlan>>, onProgress: (Int, Int) -> Unit = { _, _ -> }): PrecheckResult {
        val dirs = items.filter { !it.second.unchanged }.map { normalizePath(it.second.targetDir) }.distinct()
        val listing = HashMap<String, List<RemoteFile>?>()      // null = 目录不存在
        dirs.forEachIndexed { i, d ->
            listing[d] = try {
                storage.list(d)
            } catch (e: StorageException) {
                if (e.code == 404) null else throw e
            }
            onProgress(i + 1, dirs.size)
        }

        val conflicts = mutableListOf<Conflict>()
        val claimed = HashSet<String>()
        for ((key, plan) in items) {
            if (plan.unchanged) continue
            val target = plan.targetPath
            val existing = listing[normalizePath(plan.targetDir)]?.firstOrNull { !it.isDirectory && it.name == plan.targetName }
            val dup = !claimed.add(target)
            when {
                existing != null -> conflicts += Conflict(key, plan, ConflictKind.EXISTS, "影视库里已有同名文件", existing.size)
                dup -> conflicts += Conflict(key, plan, ConflictKind.DUPLICATE, "和本批次另一个文件整理到同一个名字")
                else -> missingMeta(plan)?.let { conflicts += Conflict(key, plan, ConflictKind.MISSING_META, it) }
            }
        }

        // 不存在的目标目录及其不存在的上级，都是会新建的
        val newDirs = HashSet<String>()
        for (d in dirs) if (listing[d] == null) {
            var p = d
            while (p != "/" && listing[p] == null && p !in newDirs) { newDirs += p; p = parentPath(p) }
        }
        return PrecheckResult(
            conflicts.sortedBy { it.kind.ordinal },
            newDirs,
            listing.mapValues { (_, l) -> l.orEmpty().map { it.name }.toSet() },
        )
    }

    private fun missingMeta(p: RenamePlan): String? {
        val i = p.input
        return when {
            i.year == null -> "缺少年份"
            i.isEpisode && i.episode == null -> "没识别出集数"
            else -> null
        }
    }

    companion object {
        /** 「保留两者」：名字后加 (2)、(3)…，避开目录里已有的和本批次已占用的 */
        fun uniqueName(name: String, taken: Set<String>): String {
            val dot = name.lastIndexOf('.')
            val base = if (dot > 0) name.substring(0, dot) else name
            val ext = if (dot > 0) name.substring(dot) else ""
            var n = 2
            while ("$base ($n)$ext" in taken) n++
            return "$base ($n)$ext"
        }

        /**
         * 目标文件夹里和这个文件「是同一个东西」的视频：电影是整个文件夹，
         * 剧集只算同一季同一集的（别的集不冲突，不该拿来对比）
         */
        fun sameItem(videos: List<RemoteFile>, input: NamingInput): List<RemoteFile> {
            if (!input.isEpisode) return videos
            return videos.filter { v ->
                val p = MediaNameParser.parse(v.name)
                p.episode == input.episode && (p.season ?: 1) == (input.season ?: 1)
            }
        }

        /**
         * 同一部剧里别的集的重复版本（同一季同一集有多个文件），按季、集排序；
         * 本文件这一集不算在内（上面单独对比）
         */
        fun duplicateEpisodes(videos: List<RemoteFile>, input: NamingInput): List<List<RemoteFile>> {
            if (!input.isEpisode) return emptyList()
            val self = (input.season ?: 1) to input.episode
            return videos.distinctBy { it.path }
                .groupBy { v -> MediaNameParser.parse(v.name).let { (it.season ?: 1) to it.episode } }
                .filter { (k, files) -> k.second != null && k != self && files.size > 1 }
                .toSortedMap(compareBy<Pair<Int, Int?>>({ it.first }, { it.second }))
                .values.map { files -> files.sortedBy { it.name } }
        }
    }
}
