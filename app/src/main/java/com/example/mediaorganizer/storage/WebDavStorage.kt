package com.example.mediaorganizer.storage

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * WebDAV 适配器（群晖、威联通、Alist、坚果云、Nextcloud 等都支持）。
 * 只依赖 OkHttp，XML 用 Android 自带的 XmlPullParser 解析。
 *
 * @param baseUrl 例如 http://192.168.1.10:5005 或 https://dav.example.com/dav
 *                路径参数都是相对它的，如 "/影视/电影"
 */
class WebDavStorage(
    baseUrl: String,
    private val username: String? = null,
    private val password: String? = null,
    override val displayName: String = "WebDAV",
    client: OkHttpClient? = null,
) : StorageProvider {

    private val base: HttpUrl = baseUrl.trimEnd('/').toHttpUrl()
    private val baseSegments: List<String> = base.pathSegments.filter { it.isNotEmpty() }

    private val http: OkHttpClient = (client ?: OkHttpClient()).newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        // 预先带上 Basic 认证：上传是一次性流，不能等 401 后重发
        .addInterceptor { chain ->
            val req = chain.request()
            if (username == null) chain.proceed(req)
            else chain.proceed(
                req.newBuilder()
                    .header("Authorization", Credentials.basic(username, password ?: "", Charsets.UTF_8))
                    .build()
            )
        }
        .build()

    // ---------- StorageProvider ----------

    override suspend fun connect() {
        propfind("/", depth = 0, dir = true) { resp ->
            if (resp.code == 401) throw StorageException(401, "用户名或密码错误")
            resp.ensure("连接失败")
        }
    }

    override suspend fun list(dir: String): List<RemoteFile> {
        val self = normalize(dir)
        return propfind(self, depth = 1, dir = true) { resp ->
            resp.ensure("读取目录失败：$self")
            parseMultiStatus(resp.body!!.byteStream())
        }.filter { it.path != self }
            .sortedWith(compareBy<RemoteFile>({ !it.isDirectory }, { it.name.lowercase() }))
    }

    override suspend fun exists(path: String): Boolean =
        propfind(normalize(path), depth = 0, dir = false) { resp ->
            when {
                resp.code == 404 -> false
                resp.isSuccessful -> true
                else -> resp.ensure("检查文件失败").let { true }
            }
        }

    override suspend fun mkdirs(path: String) {
        var current = ""
        for (seg in normalize(path).split('/').filter { it.isNotEmpty() }) {
            current += "/$seg"
            if (exists(current)) continue
            execute(Request.Builder().url(url(current, dir = true)).method("MKCOL", null).build()) { resp ->
                // 405 = 已存在
                if (resp.code != 405) resp.ensure("创建文件夹失败：$current")
            }
        }
    }

    override suspend fun rename(file: RemoteFile, newName: String): RemoteFile {
        require(!newName.contains('/')) { "新名称不能包含 /" }
        return moveTo(file, parentOf(file.path) + "/" + newName)
    }

    override suspend fun move(file: RemoteFile, targetDir: String): RemoteFile {
        mkdirs(targetDir)
        return moveTo(file, normalize(targetDir).trimEnd('/') + "/" + file.name)
    }

    override suspend fun moveAndRename(file: RemoteFile, targetDir: String, newName: String): RemoteFile {
        require(!newName.contains('/')) { "新名称不能包含 /" }
        mkdirs(targetDir)
        return moveTo(file, normalize(targetDir).trimEnd('/') + "/" + newName)   // 一次 MOVE 完成
    }

    override suspend fun upload(targetPath: String, data: InputStream, size: Long) {
        val path = normalize(targetPath)
        mkdirs(parentOf(path))
        val body = object : RequestBody() {
            override fun contentType(): MediaType = "application/octet-stream".toMediaType()
            override fun contentLength(): Long = size
            override fun isOneShot(): Boolean = true
            override fun writeTo(sink: BufferedSink) {
                data.source().use { sink.writeAll(it) }
            }
        }
        execute(Request.Builder().url(url(path)).put(body).build()) { it.ensure("上传失败：$path") }
    }

    override suspend fun delete(file: RemoteFile) {
        execute(Request.Builder().url(url(file.path, file.isDirectory)).delete().build()) {
            it.ensure("删除失败：${file.path}")
        }
    }

    /** 读取单个文件夹的信息，不存在返回 null（连接测试用） */
    suspend fun statDir(path: String): RemoteFile? =
        propfind(normalize(path), depth = 0, dir = true) { resp ->
            if (resp.code == 404) return@propfind null
            resp.ensure("读取失败：$path")
            parseMultiStatus(resp.body!!.byteStream()).firstOrNull()
        }

    /** 下载小文件（读取已有 NFO 等），大文件请用流式读取 */
    suspend fun readBytes(path: String): ByteArray =
        execute(Request.Builder().url(url(normalize(path))).get().build()) { resp ->
            resp.ensure("读取失败：$path")
            resp.body!!.bytes()
        }

    // ---------- 内部实现 ----------

    private suspend fun moveTo(file: RemoteFile, newPath: String): RemoteFile {
        val dest = normalize(newPath)
        if (dest == file.path) return file
        val req = Request.Builder()
            .url(url(file.path, file.isDirectory))
            .method("MOVE", null)
            .header("Destination", url(dest, file.isDirectory).toString())
            .header("Overwrite", "F")   // 目标已存在时不覆盖
            .build()
        execute(req) { resp ->
            if (resp.code == 412) throw StorageException(412, "目标已存在：$dest")
            resp.ensure("移动失败：${file.path} → $dest")
        }
        return file.copy(path = dest, name = dest.substringAfterLast('/'))
    }

    private suspend fun <T> propfind(path: String, depth: Int, dir: Boolean, handle: (Response) -> T): T =
        execute(
            Request.Builder()
                .url(url(path, dir))
                .method("PROPFIND", PROPFIND_BODY.toRequestBody("application/xml; charset=utf-8".toMediaType()))
                .header("Depth", depth.toString())
                .build(),
            handle,
        )

    /** 发请求并在 IO 线程里处理、关闭响应：读 body 和 close（会读完剩余数据）都是网络操作，不能回到主线程做 */
    private suspend fun <T> execute(req: Request, handle: (Response) -> T): T = withContext(Dispatchers.IO) {
        http.newCall(req).execute().use(handle)
    }

    private fun Response.ensure(msg: String) {
        if (!isSuccessful) throw StorageException(code, "$msg（HTTP $code）")
    }

    /** "/影视/老友记" -> http://host/dav/%E5%BD%B1%E8%A7%86/... ，每段单独编码 */
    private fun url(path: String, dir: Boolean = false): HttpUrl {
        val b = base.newBuilder()
        path.split('/').filter { it.isNotEmpty() }.forEach { b.addPathSegment(it) }
        if (dir) b.addPathSegment("")
        return b.build()
    }

    /** 服务器返回的 href（可能是完整 URL 或绝对路径，已编码）-> 相对 baseUrl 的解码路径 */
    private fun hrefToPath(href: String): String {
        val encodedPath = if (href.startsWith("http://") || href.startsWith("https://"))
            href.toHttpUrl().encodedPath else href.substringBefore('?')
        var segs = encodedPath.split('/').filter { it.isNotEmpty() }.map(::decode)
        if (segs.take(baseSegments.size) == baseSegments) segs = segs.drop(baseSegments.size)
        return "/" + segs.joinToString("/")
    }

    private fun parseMultiStatus(stream: InputStream): List<RemoteFile> {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(stream, null)
        }
        val result = mutableListOf<RemoteFile>()
        var href: String? = null
        var isDir = false
        var size = 0L
        var modified = 0L

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "response" -> { href = null; isDir = false; size = 0; modified = 0 }
                    "href" -> href = parser.nextText().trim()
                    "collection" -> isDir = true
                    "getcontentlength" -> size = parser.nextText().trim().toLongOrNull() ?: 0
                    "getlastmodified" -> modified = parseDate(parser.nextText().trim())
                }
                XmlPullParser.END_TAG -> if (parser.name == "response") {
                    href?.let {
                        val path = hrefToPath(it)
                        result += RemoteFile(
                            path = path,
                            name = path.substringAfterLast('/'),
                            isDirectory = isDir,
                            size = size,
                            modified = modified,
                        )
                    }
                }
            }
        }
        return result
    }

    private fun parseDate(s: String): Long = try {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(s)?.time ?: 0
    } catch (e: Exception) { 0 }

    companion object {
        private const val PROPFIND_BODY =
            """<?xml version="1.0" encoding="utf-8"?>""" +
            """<d:propfind xmlns:d="DAV:"><d:prop>""" +
            """<d:resourcetype/><d:getcontentlength/><d:getlastmodified/>""" +
            """</d:prop></d:propfind>"""

        fun normalize(path: String): String =
            "/" + path.split('/').filter { it.isNotEmpty() }.joinToString("/")

        fun parentOf(path: String): String =
            normalize(path).substringBeforeLast('/', "").ifEmpty { "/" }.let { if (it == "/") "" else it }

        private fun decode(s: String): String = URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
    }
}
