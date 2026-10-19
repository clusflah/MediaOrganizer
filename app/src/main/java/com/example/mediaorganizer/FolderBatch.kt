package com.example.mediaorganizer

/** 同文件夹里的一个视频：episode = 文件名里认出的集数 */
data class FolderFile(val key: String, val name: String, val episode: Int?)

/** 批量处理时给它定的季集 */
data class FolderEpisode(val key: String, val name: String, val season: Int, val episode: Int)

/**
 * 按某个文件指定的季集，推算同文件夹每个视频的季集（files 已按文件名排好序）：
 * - keepOwn（只改了季、集数没动）且每个文件都认出了集数 → 各用各的集数，季统一改
 * - 否则按文件名顺序递推：排在它后面第 n 个就是第 episode+n 集，前面的往回减，减到 0 以下的不处理
 */
fun folderEpisodes(files: List<FolderFile>, key: String, season: Int, episode: Int, keepOwn: Boolean): List<FolderEpisode> {
    val i = files.indexOfFirst { it.key == key }
    if (i < 0) return emptyList()
    if (keepOwn && files.all { it.episode != null }) return files.map { FolderEpisode(it.key, it.name, season, it.episode!!) }
    return files.mapIndexedNotNull { j, f ->
        (episode + j - i).takeIf { it >= 1 }?.let { FolderEpisode(f.key, f.name, season, it) }
    }
}

/** 文件名自然排序：数字按数值比，「2.mp4」排在「10.mp4」前面 */
object NaturalOrder : Comparator<String> {
    private val chunk = Regex("""\d+|\D+""")

    override fun compare(a: String, b: String): Int {
        val x = chunk.findAll(a.lowercase()).map { it.value }.toList()
        val y = chunk.findAll(b.lowercase()).map { it.value }.toList()
        for (k in 0 until minOf(x.size, y.size)) {
            val p = x[k]; val q = y[k]
            val c = if (p[0].isDigit() && q[0].isDigit())
                p.trimStart('0').length.compareTo(q.trimStart('0').length).takeIf { it != 0 } ?: p.trimStart('0').compareTo(q.trimStart('0'))
            else p.compareTo(q)
            if (c != 0) return c
        }
        return x.size.compareTo(y.size)
    }
}
