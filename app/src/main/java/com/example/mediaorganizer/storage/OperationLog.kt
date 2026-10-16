package com.example.mediaorganizer.storage

import java.io.File

/**
 * 把最近一批操作写到应用私有目录，App 被杀掉或重启后仍然可以撤销。
 * 每行一条：类型 \t 存储ID \t 原路径 \t 新路径 \t 时间戳
 */
class OperationLog(private val file: File) {

    fun save(ops: List<FileOperation>) {
        if (ops.isEmpty()) { clear(); return }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(ops.joinToString("\n") { op ->
            listOf(op.type.name, op.storageId, op.fromPath, op.toPath, op.timestamp.toString())
                .joinToString("\t") { escape(it) }
        }, Charsets.UTF_8)
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }   // 先写临时文件再替换，避免写一半
    }

    fun load(): List<FileOperation> {
        if (!file.exists()) return emptyList()
        return runCatching {
            file.readLines(Charsets.UTF_8).filter { it.isNotBlank() }.mapNotNull { line ->
                val f = line.split('\t').map { unescape(it) }
                if (f.size < 5) return@mapNotNull null
                val type = FileOperation.Type.entries.firstOrNull { it.name == f[0] } ?: return@mapNotNull null
                FileOperation(f[1], type, f[2], f[3], f[4].toLongOrNull() ?: 0)
            }
        }.getOrDefault(emptyList())
    }

    fun clear() { file.delete() }

    private fun escape(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")

    private fun unescape(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                sb.append(when (s[i + 1]) { 't' -> '\t'; 'n' -> '\n'; else -> s[i + 1] })
                i += 2
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }
}
