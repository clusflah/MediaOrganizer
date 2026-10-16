package com.example.mediaorganizer

import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mediaorganizer.metadata.*
import com.example.mediaorganizer.naming.*
import com.example.mediaorganizer.scraper.*
import com.example.mediaorganizer.settings.AppSettings
import com.example.mediaorganizer.settings.SettingsRepository
import com.example.mediaorganizer.storage.FileOperation
import com.example.mediaorganizer.storage.OperationLog
import com.example.mediaorganizer.storage.RemoteFile
import com.example.mediaorganizer.storage.StorageException
import com.example.mediaorganizer.storage.WebDavStorage
import com.example.mediaorganizer.ui.NamingSettingsScreen
import com.example.mediaorganizer.ui.ScrapeSettingsScreen
import com.example.mediaorganizer.ui.SettingField
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface { AppRoot() } } }
    }
}

enum class Page { MAIN, NAMING, SCRAPE }

@Composable
fun AppRoot(vm: MainViewModel = viewModel()) {
    var page by rememberSaveable { mutableStateOf(Page.MAIN) }
    BackHandler(enabled = page != Page.MAIN) { page = Page.MAIN }

    if (!vm.loaded) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    when (page) {
        Page.MAIN -> MainScreen(vm, openNaming = { page = Page.NAMING }, openScrape = { page = Page.SCRAPE })
        Page.NAMING -> NamingSettingsScreen(vm.settings, vm::updateSettings) { page = Page.MAIN; vm.refreshPreviewAsync() }
        Page.SCRAPE -> ScrapeSettingsScreen(vm.settings, vm::updateSettings) { page = Page.MAIN }
    }
}

// ============================ ViewModel ============================

data class MediaRow(
    val match: MatchResult,
    val chosen: TmdbSearchResult?,
    val confirmed: Boolean,
    val plan: RenamePlan? = null,
    val outcome: String? = null,     // null=未执行, "ok", "无需改动", 或错误信息
)

/** 连接测试的一项结果 */
data class ConnCheck(val label: String, val level: Level, val detail: String) {
    enum class Level { OK, WARN, FAIL }
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = SettingsRepository(app)
    private val opLog = OperationLog(File(app.filesDir, "last_batch.log"))

    var settings by mutableStateOf(AppSettings())
        private set
    var loaded by mutableStateOf(false)
        private set

    val rows = mutableStateListOf<MediaRow>()
    var status by mutableStateOf("")
    var busy by mutableStateOf(false)
    var canUndo by mutableStateOf(false)
    var connChecks by mutableStateOf<List<ConnCheck>>(emptyList())
        private set

    private var storage: WebDavStorage? = null
    private var tmdb: TmdbClient? = null
    private var details: TmdbDetails? = null
    private var lastOps: List<FileOperation> = emptyList()
    private var saveJob: Job? = null

    init {
        viewModelScope.launch {
            settings = runCatching { repo.load() }.getOrDefault(AppSettings())
            lastOps = opLog.load()               // 上次没撤销的批次，重启后仍可撤销
            canUndo = lastOps.isNotEmpty()
            if (canUndo) status = "上次整理了 ${lastOps.count { it.type == FileOperation.Type.MOVE }} 个文件，可点「撤销」还原"
            loaded = true
        }
    }

    /** 每次修改立即生效，300ms 防抖后写入 DataStore（输入框打字不会频繁写盘） */
    fun updateSettings(new: AppSettings) {
        val old = settings
        if (new.webdavUrl != old.webdavUrl || new.username != old.username || new.password != old.password ||
            new.scanDir != old.scanDir || new.movieRoot != old.movieRoot || new.tvRoot != old.tvRoot
        ) connChecks = emptyList()          // 连接信息改了，旧的测试结果作废
        settings = new
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(300)
            repo.save(new)
        }
    }

    private val storageId get() = "webdav:" + settings.webdavUrl.trim().trimEnd('/')

    private fun newStorage() = settings.let {
        WebDavStorage(it.webdavUrl.trim(), it.username.ifBlank { null }, it.password.ifBlank { null })
    }

    private fun rememberOps(ops: List<FileOperation>, append: Boolean) {
        lastOps = if (append) lastOps + ops else ops
        opLog.save(lastOps)
        canUndo = lastOps.isNotEmpty()
    }

    private fun launchBusy(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try { block() } catch (e: Exception) { status = "出错：${e.message}" } finally { busy = false }
        }
    }

    fun scan() = launchBusy {
        val s0 = settings
        if (s0.tmdbToken.isBlank()) { status = "请先在「刮削设置」里填写 TMDB 令牌"; return@launchBusy }
        status = "连接中…"
        val s = newStorage()
        s.connect()
        val t = TmdbClient(s0.tmdbToken.trim(), apiBase = s0.tmdbApiBase.trimEnd('/'), imageBase = s0.tmdbImageBase.trimEnd('/'))
        storage = s; tmdb = t; details = TmdbDetails(t)
        rows.clear()

        MediaScanner(s, MediaMatcher(t)).scan(s0.scanDir).collect { m ->
            rows += MediaRow(m, m.best, confirmed = !m.needsConfirm)
            status = "已扫描 ${rows.size} 个视频…"
        }
        refreshPreview()
        val pending = rows.count { !it.confirmed }
        status = "共 ${rows.size} 个视频" + if (pending > 0) "，$pending 个待确认（点击条目选择）" else ""
    }

    /** 依次检查：服务器能否登录 → 扫描目录可读 → 电影库/剧集库可写 */
    fun testConnection() = launchBusy {
        val s0 = settings
        val checks = mutableListOf<ConnCheck>()
        fun add(c: ConnCheck) { checks += c; connChecks = checks.toList() }
        connChecks = emptyList()
        status = "测试连接中…"

        val s = try { newStorage() } catch (e: IllegalArgumentException) {
            add(ConnCheck("服务器", ConnCheck.Level.FAIL, "地址格式不对，应类似 http://192.168.1.10:5005"))
            status = "连接测试失败"
            return@launchBusy
        }
        val t0 = SystemClock.elapsedRealtime()
        try {
            s.connect()
        } catch (e: Exception) {
            add(ConnCheck("服务器", ConnCheck.Level.FAIL, connErrorText(e)))
            status = "连接测试失败"
            return@launchBusy
        }
        add(ConnCheck("服务器", ConnCheck.Level.OK, "已登录（${SystemClock.elapsedRealtime() - t0} ms）"))

        add(checkDir(s, "扫描目录", s0.scanDir, library = false))
        add(checkDir(s, "电影库", s0.movieRoot, library = true))
        if (s0.tvRoot.trim().trimEnd('/') != s0.movieRoot.trim().trimEnd('/'))
            add(checkDir(s, "剧集库", s0.tvRoot, library = true))

        val fails = checks.count { it.level == ConnCheck.Level.FAIL }
        val warns = checks.count { it.level == ConnCheck.Level.WARN }
        status = when {
            fails > 0 -> "连接测试：$fails 项失败"
            warns > 0 -> "连接测试通过，$warns 项需留意"
            else -> "连接测试全部通过"
        }
    }

    private suspend fun checkDir(s: WebDavStorage, label: String, dir: String, library: Boolean): ConnCheck {
        if (dir.isBlank()) return ConnCheck(label, ConnCheck.Level.WARN, "未填写")
        val path = WebDavStorage.normalize(dir)
        return try {
            val info = s.statDir(path)
            when {
                info == null && library -> ConnCheck(label, ConnCheck.Level.WARN, "$path 不存在，整理时会自动创建")
                info == null -> ConnCheck(label, ConnCheck.Level.FAIL, "$path 不存在")
                !info.isDirectory -> ConnCheck(label, ConnCheck.Level.FAIL, "$path 是文件，不是文件夹")
                !library -> ConnCheck(label, ConnCheck.Level.OK, "可读取，含 ${s.list(path).size} 项")
                else -> checkWritable(s, label, path)
            }
        } catch (e: Exception) {
            ConnCheck(label, ConnCheck.Level.FAIL, connErrorText(e))
        }
    }

    /** 写一个空的临时文件再删掉，确认有写权限 */
    private suspend fun checkWritable(s: WebDavStorage, label: String, dir: String): ConnCheck {
        val probe = RemoteFile(
            path = dir.trimEnd('/') + "/.mediaorganizer-test-${System.currentTimeMillis()}",
            name = "", isDirectory = false,
        )
        try {
            s.upload(probe.path, ByteArray(0).inputStream(), 0)
        } catch (e: Exception) {
            val why = if (e is StorageException && (e.code == 401 || e.code == 403)) "没有写入权限" else connErrorText(e)
            return ConnCheck(label, ConnCheck.Level.FAIL, "可读取，但$why")
        }
        return try {
            s.delete(probe)
            ConnCheck(label, ConnCheck.Level.OK, "可读写")
        } catch (e: Exception) {
            ConnCheck(label, ConnCheck.Level.WARN, "可写入，但测试文件没删掉，请手动删除 ${probe.path}")
        }
    }

    private fun connErrorText(e: Exception): String = when (e) {
        is StorageException -> when (e.code) {
            401 -> "用户名或密码错误"
            403 -> "没有访问权限（HTTP 403）"
            404 -> "路径不存在（HTTP 404），检查地址里的路径部分"
            405 -> "该地址不是 WebDAV 服务（HTTP 405），检查端口和路径"
            else -> e.message ?: "HTTP ${e.code}"
        }
        is UnknownHostException -> "找不到主机，检查地址"
        is ConnectException -> "连不上服务器，检查 IP、端口，以及 WebDAV 服务是否开启"
        is SocketTimeoutException -> "连接超时，检查网络或是否在同一局域网"
        is SSLException -> "HTTPS 握手失败（证书问题？局域网可改用 http）"
        else -> e.message ?: e.javaClass.simpleName
    }

    fun choose(index: Int, result: TmdbSearchResult) = launchBusy {
        rows[index] = rows[index].copy(chosen = result, confirmed = true)
        refreshPreview()
    }

    fun refreshPreviewAsync() = launchBusy { refreshPreview() }

    private suspend fun refreshPreview() {
        val d = details ?: return
        val s = settings
        val planner = RenamePlanner(d, LibraryRoots(s.movieRoot, s.tvRoot))
        val ready = rows.withIndex().filter { (_, r) ->
            r.confirmed && r.chosen != null && r.match.file != null && r.outcome == null
        }
        val plans = planner.plan(
            ready.map { (_, r) -> RenameItem(r.match.file!!, r.match.parsed, r.chosen!!) },
            movieConfig = s.movieNaming,
            tvConfig = s.tvNaming,
        )
        ready.zip(plans).forEach { (iv, plan) -> rows[iv.index] = rows[iv.index].copy(plan = plan) }
    }

    fun execute() = launchBusy {
        val s = storage ?: return@launchBusy
        val indexed = rows.withIndex().filter { it.value.plan != null && it.value.outcome == null }
        val report = RenameExecutor(s, storageId).execute(indexed.map { it.value.plan!! }) { done, total, _ ->
            status = "整理中 $done/$total"
        }
        indexed.zip(report.outcomes).forEach { (iv, o) ->
            rows[iv.index] = rows[iv.index].copy(outcome = if (o.skipped) "无需改动" else o.error ?: "ok")
        }
        rememberOps(report.operations, append = false)    // 新批次，覆盖上一批的撤销记录
        status = "整理完成：成功 ${report.succeeded}，失败 ${report.failed}"

        if (settings.autoScrape) {
            runScrape(s, report.outcomes.filter { it.error == null }.map { it.plan }, prefix = "$status；")
        }
    }

    fun scrapeOnly() = launchBusy {
        val s = storage ?: return@launchBusy
        val plans = rows.filter { it.outcome == "ok" || it.outcome == "无需改动" }.mapNotNull { it.plan }
        if (plans.isEmpty()) { status = "没有已整理的条目可刮削"; return@launchBusy }
        runScrape(s, plans)
    }

    private suspend fun runScrape(s: WebDavStorage, plans: List<RenamePlan>, prefix: String = "") {
        val d = details ?: return
        val t = tmdb ?: return
        val report = Scraper(s, d, TmdbScrapeSource(t), storageId).scrape(plans, settings.scrape) { done, total ->
            status = "${prefix}刮削中 $done/$total"
        }
        rememberOps(report.operations, append = true)     // 撤销时一并删除新写入的 NFO 和图片
        status = "${prefix}刮削：写入 ${report.written}，跳过 ${report.skipped}" +
            if (report.errors.isNotEmpty()) "，失败 ${report.errors.size}（${report.errors.first()}）" else ""
    }

    fun undo() = launchBusy {
        val ops = lastOps
        if (ops.isEmpty()) return@launchBusy
        val opsStorage = ops.first().storageId
        if (opsStorage != storageId) {
            status = "撤销记录属于 ${opsStorage.removePrefix("webdav:")}，与当前连接不一致，请先改回原连接"
            return@launchBusy
        }
        status = "撤销中…"
        val s = storage ?: newStorage().also { it.connect(); storage = it }   // 重启后还没扫描也能撤销
        val r = RenameExecutor(s, storageId).rollback(ops)
        rememberOps(emptyList(), append = false)
        for (i in rows.indices) rows[i] = rows[i].copy(outcome = null)
        if (details != null) refreshPreview()
        status = buildString {
            append(if (r.failed == 0) "已全部撤销" else "撤销完成，${r.failed} 项失败（${r.errors.first()}）")
            if (r.keptDirs > 0) append("；${r.keptDirs} 个文件夹里有其他文件，已保留")
        }
    }
}

// ============================ 主界面 ============================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel, openNaming: () -> Unit, openScrape: () -> Unit) {
    var showSettings by rememberSaveable { mutableStateOf(vm.settings.tmdbToken.isBlank()) }
    var pickIndex by remember { mutableStateOf<Int?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("影视整理") },
                actions = { TextButton(onClick = { showSettings = !showSettings }) { Text(if (showSettings) "收起" else "连接") } },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = vm::scan, enabled = !vm.busy, modifier = Modifier.weight(1f)) { Text("扫描") }
                    if (vm.canUndo) OutlinedButton(onClick = vm::undo, enabled = !vm.busy, modifier = Modifier.weight(1f)) { Text("撤销") }
                    if (!vm.settings.autoScrape && vm.rows.any { it.outcome == "ok" || it.outcome == "无需改动" })
                        OutlinedButton(onClick = vm::scrapeOnly, enabled = !vm.busy, modifier = Modifier.weight(1f)) { Text("刮削") }
                    Button(
                        onClick = vm::execute,
                        enabled = !vm.busy && vm.rows.any { it.plan != null && it.outcome == null },
                        modifier = Modifier.weight(1f),
                    ) { Text("开始整理") }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (showSettings) ConnectionPanel(vm)

            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = openNaming, label = { Text("命名规则 · ${vm.settings.player}") })
                AssistChip(onClick = openScrape, label = { Text("刮削设置") })
            }

            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (vm.busy) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                Text(vm.status, style = MaterialTheme.typography.bodySmall)
            }

            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                itemsIndexed(vm.rows, key = { _, r -> r.match.file?.path ?: r.match.parsed.rawName }) { i, row ->
                    PreviewRow(row, onClick = { pickIndex = i })
                    HorizontalDivider()
                }
            }
        }
    }

    pickIndex?.let { i ->
        val row = vm.rows.getOrNull(i)
        if (row != null) PickDialog(row, onDismiss = { pickIndex = null }, onPick = { vm.choose(i, it); pickIndex = null })
    }
}

@Composable
private fun ConnectionPanel(vm: MainViewModel) {
    val s = vm.settings
    fun up(n: AppSettings) = vm.updateSettings(n)
    Column(
        Modifier.padding(horizontal = 16.dp).heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SettingField("WebDAV 地址", s.webdavUrl) { up(s.copy(webdavUrl = it)) }
        SettingField("用户名", s.username) { up(s.copy(username = it)) }
        SettingField("密码", s.password, password = true) { up(s.copy(password = it)) }
        SettingField("扫描目录", s.scanDir) { up(s.copy(scanDir = it)) }
        SettingField("电影库目录", s.movieRoot) { up(s.copy(movieRoot = it)) }
        SettingField("剧集库目录", s.tvRoot) { up(s.copy(tvRoot = it)) }
        OutlinedButton(
            onClick = vm::testConnection,
            enabled = !vm.busy && s.webdavUrl.isNotBlank(),
            modifier = Modifier.padding(top = 4.dp),
        ) { Text("测试连接") }
        vm.connChecks.forEach { ConnCheckRow(it) }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ConnCheckRow(c: ConnCheck) {
    val (mark, color) = when (c.level) {
        ConnCheck.Level.OK -> "✓" to Color(0xFF2E7D32)
        ConnCheck.Level.WARN -> "!" to Color(0xFFEF6C00)
        ConnCheck.Level.FAIL -> "✗" to Color(0xFFC62828)
    }
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(mark, color = color, fontWeight = FontWeight.Bold, modifier = Modifier.width(18.dp))
        Text(c.label, fontWeight = FontWeight.Medium, fontSize = 13.sp, modifier = Modifier.width(64.dp))
        Text(c.detail, fontSize = 13.sp, color = if (c.level == ConnCheck.Level.OK) Color.Unspecified else color)
    }
}

@Composable
private fun PreviewRow(row: MediaRow, onClick: () -> Unit) {
    val m = row.match
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(m.file?.name ?: m.parsed.rawName, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (tag, color) = when {
                row.outcome == "ok" -> "完成" to Color(0xFF2E7D32)
                row.outcome == "无需改动" -> "无需" to Color(0xFF757575)
                row.outcome != null -> "失败" to Color(0xFFC62828)
                row.chosen == null -> "未匹配" to Color(0xFFC62828)
                !row.confirmed -> "待确认" to Color(0xFFEF6C00)
                else -> "%.0f%%".format(m.score * 100) to Color(0xFF2E7D32)
            }
            Text(tag, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(44.dp))
            Text(
                row.plan?.targetPath ?: row.chosen?.let { "${it.title} (${it.year ?: "?"})" } ?: "点击手动选择",
                style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        val outcome = row.outcome
        if (outcome != null && outcome != "ok" && outcome != "无需改动") {
            Text(outcome, color = Color(0xFFC62828), fontSize = 11.sp)
        }
    }
}

@Composable
private fun PickDialog(row: MediaRow, onDismiss: () -> Unit, onPick: (TmdbSearchResult) -> Unit) {
    val options = listOfNotNull(row.match.best) + row.match.alternatives
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(row.match.parsed.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            if (options.isEmpty()) Text("TMDB 没有搜到结果")
            else LazyColumn {
                itemsIndexed(options) { _, opt ->
                    Column(Modifier.fillMaxWidth().clickable { onPick(opt) }.padding(vertical = 8.dp)) {
                        Text("${opt.title} (${opt.year ?: "?"})", fontWeight = FontWeight.Medium)
                        Text(
                            listOfNotNull(if (opt.type == MediaType.TV) "剧集" else "电影", opt.originalTitle, "TMDB ${opt.id}")
                                .joinToString(" · "),
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
