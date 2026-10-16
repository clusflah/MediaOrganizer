package com.example.mediaorganizer

import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.example.mediaorganizer.storage.normalizePath
import com.example.mediaorganizer.storage.parentPath
import com.example.mediaorganizer.ui.MainScreen
import com.example.mediaorganizer.ui.NamingSettingsScreen
import com.example.mediaorganizer.ui.ScrapeSettingsScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        Page.NAMING -> NamingSettingsScreen(vm.settings, vm.saved, vm::updateSettings) { page = Page.MAIN; vm.refreshPreviewAsync() }
        Page.SCRAPE -> ScrapeSettingsScreen(
            vm.settings, vm.saved, vm::updateSettings,
            tmdbChecks = vm.tmdbChecks, testing = vm.task == Task.TEST, onTestTmdb = vm::testTmdb,
        ) { page = Page.MAIN }
    }
}

// ============================ ViewModel ============================

/** 列表里每个文件所处的状态，枚举顺序 = 「全部」视图里的排序（要处理的排前面） */
enum class RowCategory(val label: String) {
    PENDING("待确认"), UNMATCHED("未匹配"), FAILED("失败"), READY("可整理"), DONE("已完成"), EXCLUDED("已排除"),
}

data class MediaRow(
    val match: MatchResult,
    val chosen: TmdbSearchResult?,
    val confirmed: Boolean,
    val plan: RenamePlan? = null,
    val outcome: String? = null,     // null=未执行, "ok", "无需改动", 或错误信息
    val excluded: Boolean = false,   // 用户选择不整理
) {
    val key: String get() = match.file?.path ?: match.parsed.rawName

    val category: RowCategory get() = when {
        outcome == "ok" || outcome == "无需改动" -> RowCategory.DONE
        outcome != null -> RowCategory.FAILED
        excluded -> RowCategory.EXCLUDED
        chosen == null -> RowCategory.UNMATCHED
        !confirmed -> RowCategory.PENDING
        else -> RowCategory.READY
    }

    val isTv: Boolean get() = chosen?.let { it.type == MediaType.TV } ?: match.parsed.isEpisode

    /** 同一部剧的各集、同一部电影的不同版本解析出的片名相同，可以一起改匹配 */
    val groupKey: String get() =
        (if (match.parsed.isEpisode) "tv|" else "mv|") + match.parsed.title.lowercase().filter { it.isLetterOrDigit() }
}

/** 连接测试的一项结果 */
data class ConnCheck(val label: String, val level: Level, val detail: String) {
    enum class Level { OK, WARN, FAIL }
}

enum class ConnState { UNTESTED, OK, FAIL }

enum class Task { SCAN, TEST, MATCH, ORGANIZE, SCRAPE, UNDO }

/** 撤销前给用户看的汇总 */
data class UndoSummary(
    val time: Long,
    val moved: Int,
    val created: Int,                        // 新写入的 NFO / 图片
    val samples: List<Pair<String, String>>, // 现在位置 -> 还原到
)

/** 整理计划里的一条：from -> to */
data class PlannedMove(val key: String, val from: String, val to: String)

/** 「开始整理」前给用户看的汇总 */
data class OrganizeSummary(
    val moves: List<PlannedMove>,                         // 换目录
    val renames: List<PlannedMove>,                       // 同目录改名
    val unchanged: List<PlannedMove>,                     // 已经在目标位置
    val duplicates: List<Pair<String, List<PlannedMove>>>, // 目标路径 -> 撞名的文件（第一个会整理）
    val notIncluded: Int,
) {
    val total: Int get() = moves.size + renames.size + unchanged.size
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
    var canUndo by mutableStateOf(false)

    /** 当前在跑的任务，null = 空闲 */
    var task by mutableStateOf<Task?>(null)
        private set
    val busy: Boolean get() = task != null
    /** 整理 / 刮削的进度 done to total */
    var progress by mutableStateOf<Pair<Int, Int>?>(null)
        private set

    var connState by mutableStateOf(ConnState.UNTESTED)
        private set
    var connMessage by mutableStateOf("")
        private set
    var connChecks by mutableStateOf<List<ConnCheck>>(emptyList())
        private set
    var tmdbChecks by mutableStateOf<List<ConnCheck>>(emptyList())
        private set
    /** 设置是否已写盘（改动后有 300ms 防抖） */
    var saved by mutableStateOf(true)
        private set

    // 列表筛选与多选
    var filter by mutableStateOf<RowCategory?>(null)     // null = 全部
    var typeFilter by mutableStateOf<MediaType?>(null)   // null = 电影和剧集
    var selected by mutableStateOf<Set<String>>(emptySet())
        private set

    private var storage: WebDavStorage? = null
    private var tmdb: TmdbClient? = null
    private var details: TmdbDetails? = null
    private var lastOps: List<FileOperation> = emptyList()
    private var saveJob: Job? = null
    private var taskJob: Job? = null

    init {
        viewModelScope.launch {
            settings = runCatching { repo.load() }.getOrDefault(AppSettings())
            lastOps = opLog.load()               // 上次没撤销的批次，重启后仍可撤销
            canUndo = lastOps.isNotEmpty()
            if (canUndo) status = "上次整理了 ${undoSummary()?.moved ?: 0} 个文件，可在右上角菜单里撤销"
            loaded = true
        }
    }

    /** 每次修改立即生效，300ms 防抖后写入 DataStore（输入框打字不会频繁写盘） */
    fun updateSettings(new: AppSettings) {
        val old = settings
        if (new.webdavUrl != old.webdavUrl || new.username != old.username || new.password != old.password ||
            new.scanDir != old.scanDir || new.movieRoot != old.movieRoot || new.tvRoot != old.tvRoot
        ) {                                  // 连接信息改了，旧的测试结果作废
            connChecks = emptyList()
            connState = ConnState.UNTESTED
            connMessage = ""
        }
        if (new.tmdbToken != old.tmdbToken || new.tmdbApiBase != old.tmdbApiBase || new.tmdbImageBase != old.tmdbImageBase)
            tmdbChecks = emptyList()
        settings = new
        saved = false
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(300)
            repo.save(new)
            saved = true
        }
    }

    private val storageId get() = "webdav:" + settings.webdavUrl.trim().trimEnd('/')

    private fun newStorage() = settings.let {
        WebDavStorage(it.webdavUrl.trim(), it.username.ifBlank { null }, it.password.ifBlank { null })
    }

    private fun newTmdb() = settings.let {
        TmdbClient(it.tmdbToken.trim(), apiBase = it.tmdbApiBase.trimEnd('/'), imageBase = it.tmdbImageBase.trimEnd('/'))
    }

    private fun rememberOps(ops: List<FileOperation>, append: Boolean) {
        lastOps = if (append) lastOps + ops else ops
        opLog.save(lastOps)
        canUndo = lastOps.isNotEmpty()
    }

    private fun launchTask(t: Task, block: suspend () -> Unit) {
        if (task != null) return
        task = t
        progress = null
        taskJob = viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = "出错：${e.message ?: e.javaClass.simpleName}"
            } finally {
                task = null; progress = null; taskJob = null
            }
        }
    }

    // ---------- 扫描 ----------

    fun scan() = launchTask(Task.SCAN) {
        val s0 = settings
        if (s0.tmdbToken.isBlank()) { status = "请先在「刮削设置」里填写 TMDB 令牌"; return@launchTask }
        status = "连接中…"
        val s = try {
            newStorage().also { it.connect() }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            connState = ConnState.FAIL
            connMessage = connErrorText(e)
            status = "连接失败：$connMessage"
            return@launchTask
        }
        connState = ConnState.OK
        connMessage = ""
        val t = newTmdb()
        storage = s; tmdb = t; details = TmdbDetails(t)
        rows.clear()
        selected = emptySet()
        filter = null

        var stopped = false
        try {
            MediaScanner(s, MediaMatcher(t)).scan(s0.scanDir).collect { m ->
                rows += MediaRow(m, m.best, confirmed = !m.needsConfirm)
                status = "已扫描 ${rows.size} 个视频…"
            }
        } catch (e: CancellationException) {
            stopped = true                   // 用户点了「停止」，保留已扫到的
        }
        withContext(NonCancellable) {
            refreshPreview()
            val pending = rows.count { it.category == RowCategory.PENDING }
            filter = if (pending > 0) RowCategory.PENDING else null
            status = (if (stopped) "已停止扫描，" else "") + "共 ${rows.size} 个视频" +
                if (pending > 0) "，$pending 个待确认" else ""
        }
    }

    fun stopScan() {
        if (task == Task.SCAN) taskJob?.cancel()
    }

    // ---------- 匹配确认 ----------

    fun sameGroupPendingCount(key: String): Int {
        val row = rows.firstOrNull { it.key == key } ?: return 0
        return rows.count { it.key != key && it.groupKey == row.groupKey && it.isOpenForMatch() }
    }

    private fun MediaRow.isOpenForMatch() = outcome == null && !excluded && !confirmed

    /** 选定匹配；applyToSame 时同名的其他待确认文件一并改成这个结果 */
    fun choose(key: String, result: TmdbSearchResult, applyToSame: Boolean) = launchTask(Task.MATCH) {
        val i = rows.indexOfFirst { it.key == key }
        if (i < 0) return@launchTask
        val group = rows[i].groupKey
        var n = 0
        for (j in rows.indices) {
            val r = rows[j]
            if (j == i || (applyToSame && r.groupKey == group && r.isOpenForMatch())) {
                rows[j] = r.copy(chosen = result, confirmed = true, plan = null, outcome = null)   // 失败的改匹配后可重试
                n++
            }
        }
        status = if (n > 1) "已将 $n 个文件匹配为「${result.title}」" else "已匹配为「${result.title}」"
        refreshPreview()
    }

    /** 接受当前推荐的匹配 */
    fun confirm(keys: Set<String>) = launchTask(Task.MATCH) {
        var n = 0
        for (i in rows.indices) {
            val r = rows[i]
            if (r.key in keys && r.chosen != null && r.isOpenForMatch()) {
                rows[i] = r.copy(confirmed = true, plan = null)
                n++
            }
        }
        selected = emptySet()
        status = "已确认 $n 个"
        refreshPreview()
    }

    fun setExcluded(keys: Set<String>, excluded: Boolean) {
        if (busy) return
        var n = 0
        for (i in rows.indices) {
            val r = rows[i]
            if (r.key in keys && (r.outcome == null || r.category == RowCategory.FAILED) && r.excluded != excluded) {
                rows[i] = r.copy(excluded = excluded, plan = null, outcome = null)
                n++
            }
        }
        selected = emptySet()
        status = if (excluded) "已排除 $n 个，整理时会跳过" else "已恢复 $n 个"
        if (!excluded) refreshPreviewAsync(all = false)
    }

    suspend fun searchTmdb(query: String, tv: Boolean): List<TmdbSearchResult> {
        val t = tmdb ?: newTmdb().also { tmdb = it }
        return if (tv) t.searchTv(query) else t.searchMovie(query)
    }

    // ---------- 多选 ----------

    fun toggleSelect(key: String) {
        selected = if (key in selected) selected - key else selected + key
    }

    fun selectAll(keys: Collection<String>) { selected = selected + keys }

    fun clearSelection() { selected = emptySet() }

    // ---------- 预览 ----------

    fun refreshPreviewAsync(all: Boolean = true) = launchTask(Task.MATCH) { refreshPreview(all) }

    /** 为已确认的条目生成目标路径；all=false 时只算还没有路径的（改匹配后不必重算上千条） */
    private suspend fun refreshPreview(all: Boolean = false) {
        val d = details ?: return
        val s = settings
        val planner = RenamePlanner(d, LibraryRoots(s.movieRoot, s.tvRoot))
        val ready = rows.withIndex().filter { (_, r) ->
            r.category == RowCategory.READY && r.match.file != null && (all || r.plan == null)
        }
        if (ready.isEmpty()) return
        val plans = planner.plan(
            ready.map { (_, r) -> RenameItem(r.match.file!!, r.match.parsed, r.chosen!!) },
            movieConfig = s.movieNaming,
            tvConfig = s.tvNaming,
        )
        ready.zip(plans).forEach { (iv, plan) -> rows[iv.index] = rows[iv.index].copy(plan = plan) }
    }

    // ---------- 整理 / 刮削 / 撤销 ----------

    private fun organizable() = rows.withIndex().filter { it.value.category == RowCategory.READY && it.value.plan != null }

    fun organizableCount() = rows.count { it.category == RowCategory.READY && it.plan != null }

    fun organizeSummary(): OrganizeSummary {
        val items = organizable().map { (_, r) -> r.plan!!.let { r.key to it } }
        fun toMove(p: Pair<String, RenamePlan>) = PlannedMove(p.first, p.second.item.file.path, p.second.targetPath)
        val (unchanged, changed) = items.partition { it.second.unchanged }
        val (moves, renames) = changed.partition { parentPath(it.second.item.file.path) != normalizePath(it.second.targetDir) }
        return OrganizeSummary(
            moves = moves.map(::toMove),
            renames = renames.map(::toMove),
            unchanged = unchanged.map(::toMove),
            duplicates = changed.groupBy { it.second.targetPath }.filterValues { it.size > 1 }
                .map { (target, list) -> target to list.map(::toMove) },
            notIncluded = rows.count { it.category in setOf(RowCategory.PENDING, RowCategory.UNMATCHED, RowCategory.EXCLUDED) },
        )
    }

    fun execute() = launchTask(Task.ORGANIZE) { runOrganize(organizable(), appendUndo = false) }

    /** 详情里单独整理一个文件；待确认的先按当前匹配确认，失败的可重试 */
    fun organizeOne(key: String) = launchTask(Task.ORGANIZE) {
        val i = rows.indexOfFirst { it.key == key }
        if (i < 0 || rows[i].chosen == null || rows[i].match.file == null) return@launchTask
        rows[i] = rows[i].copy(confirmed = true, excluded = false, outcome = null, plan = null)
        refreshPreview()
        // 并入上一批的撤销记录，不覆盖之前的批量整理
        runOrganize(listOf(IndexedValue(i, rows[i])).filter { it.value.plan != null }, appendUndo = true)
    }

    private suspend fun runOrganize(indexed: List<IndexedValue<MediaRow>>, appendUndo: Boolean) {
        val s = storage ?: return
        if (indexed.isEmpty()) return
        val report = RenameExecutor(s, storageId).execute(indexed.map { it.value.plan!! }) { done, total, _ ->
            progress = done to total
            status = "整理中 $done/$total"
        }
        indexed.zip(report.outcomes).forEach { (iv, o) ->
            rows[iv.index] = rows[iv.index].copy(outcome = if (o.skipped) "无需改动" else o.error ?: "ok")
        }
        rememberOps(report.operations, append = appendUndo)
        status = if (indexed.size == 1) {
            val o = report.outcomes.single()
            when {
                o.error != null -> "整理失败：${o.error}"
                o.skipped -> "已在目标位置，无需改动"
                else -> "已整理到 ${o.plan.targetPath}"
            }
        } else "整理完成：成功 ${report.succeeded}，失败 ${report.failed}"
        if (indexed.size > 1 && report.failed > 0) filter = RowCategory.FAILED

        if (settings.autoScrape) {
            task = Task.SCRAPE
            runScrape(s, report.outcomes.filter { it.error == null }.map { it.plan }, prefix = "$status；")
        }
    }

    fun scrapeOne(key: String) = launchTask(Task.SCRAPE) {
        val s = storage ?: return@launchTask
        val plan = rows.firstOrNull { it.key == key && it.category == RowCategory.DONE }?.plan ?: return@launchTask
        runScrape(s, listOf(plan))
    }

    fun scrapeOnly() = launchTask(Task.SCRAPE) {
        val s = storage ?: return@launchTask
        val plans = rows.filter { it.category == RowCategory.DONE }.mapNotNull { it.plan }
        if (plans.isEmpty()) { status = "没有已整理的条目可刮削"; return@launchTask }
        runScrape(s, plans)
    }

    private suspend fun runScrape(s: WebDavStorage, plans: List<RenamePlan>, prefix: String = "") {
        val d = details ?: return
        val t = tmdb ?: return
        progress = null
        val report = Scraper(s, d, TmdbScrapeSource(t), storageId).scrape(plans, settings.scrape) { done, total ->
            progress = done to total
            status = "${prefix}刮削中 $done/$total"
        }
        rememberOps(report.operations, append = true)     // 撤销时一并删除新写入的 NFO 和图片
        status = "${prefix}刮削：写入 ${report.written}，跳过 ${report.skipped}" +
            if (report.errors.isNotEmpty()) "，失败 ${report.errors.size}（${report.errors.first()}）" else ""
    }

    fun undoSummary(): UndoSummary? {
        val ops = lastOps.takeIf { it.isNotEmpty() } ?: return null
        val moves = ops.filter { it.type == FileOperation.Type.MOVE || it.type == FileOperation.Type.RENAME }
        return UndoSummary(
            time = ops.maxOf { it.timestamp },
            moved = moves.size,
            created = ops.count { it.type == FileOperation.Type.UPLOAD },
            samples = moves.take(5).map { it.toPath to it.fromPath },
        )
    }

    fun undo() = launchTask(Task.UNDO) {
        val ops = lastOps
        if (ops.isEmpty()) return@launchTask
        val opsStorage = ops.first().storageId
        if (opsStorage != storageId) {
            status = "撤销记录属于 ${opsStorage.removePrefix("webdav:")}，与当前连接不一致，请先改回原连接"
            return@launchTask
        }
        status = "撤销中…"
        val s = storage ?: newStorage().also { it.connect(); storage = it }   // 重启后还没扫描也能撤销
        val r = RenameExecutor(s, storageId).rollback(ops)
        rememberOps(emptyList(), append = false)
        for (i in rows.indices) rows[i] = rows[i].copy(outcome = null)
        if (details != null) refreshPreview(all = true)
        status = buildString {
            append(if (r.failed == 0) "已全部撤销" else "撤销完成，${r.failed} 项失败（${r.errors.first()}）")
            if (r.keptDirs > 0) append("；${r.keptDirs} 个文件夹里有其他文件，已保留")
        }
    }

    // ---------- 连接测试 ----------

    /** 依次检查：服务器能否登录 → 扫描目录可读 → 电影库/剧集库可写 */
    fun testConnection() = launchTask(Task.TEST) {
        val s0 = settings
        val checks = mutableListOf<ConnCheck>()
        fun add(c: ConnCheck) { checks += c; connChecks = checks.toList() }
        fun finish() {
            val firstFail = checks.firstOrNull { it.level == ConnCheck.Level.FAIL }
            connState = if (firstFail == null) ConnState.OK else ConnState.FAIL
            connMessage = firstFail?.let { "${it.label}：${it.detail}" } ?: ""
            val warns = checks.count { it.level == ConnCheck.Level.WARN }
            status = when {
                firstFail != null -> "连接测试：${checks.count { it.level == ConnCheck.Level.FAIL }} 项失败"
                warns > 0 -> "连接测试通过，$warns 项需留意"
                else -> "连接测试全部通过"
            }
        }
        connChecks = emptyList()
        status = "测试连接中…"

        val s = try { newStorage() } catch (e: IllegalArgumentException) {
            add(ConnCheck("服务器", ConnCheck.Level.FAIL, "地址格式不对，应类似 http://192.168.1.10:5005"))
            finish()
            return@launchTask
        }
        val t0 = SystemClock.elapsedRealtime()
        try {
            s.connect()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            add(ConnCheck("服务器", ConnCheck.Level.FAIL, connErrorText(e)))
            finish()
            return@launchTask
        }
        add(ConnCheck("服务器", ConnCheck.Level.OK, "已登录（${SystemClock.elapsedRealtime() - t0} ms）"))

        add(checkDir(s, "扫描目录", s0.scanDir, library = false))
        add(checkDir(s, "电影库", s0.movieRoot, library = true))
        if (s0.tvRoot.trim().trimEnd('/') != s0.movieRoot.trim().trimEnd('/'))
            add(checkDir(s, "剧集库", s0.tvRoot, library = true))
        finish()
    }

    /** 检查 TMDB 令牌、API 地址和图片地址（填了反代时尤其有用） */
    fun testTmdb() = launchTask(Task.TEST) {
        val checks = mutableListOf<ConnCheck>()
        fun add(c: ConnCheck) { checks += c; tmdbChecks = checks.toList() }
        tmdbChecks = emptyList()
        if (settings.tmdbToken.isBlank()) {
            add(ConnCheck("API", ConnCheck.Level.FAIL, "未填写令牌"))
            return@launchTask
        }
        val t = newTmdb()
        val t0 = SystemClock.elapsedRealtime()
        val results = try {
            t.searchMovie("Inception")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            add(ConnCheck("API", ConnCheck.Level.FAIL, connErrorText(e)))
            return@launchTask
        }
        add(ConnCheck("API", ConnCheck.Level.OK, "令牌有效（${SystemClock.elapsedRealtime() - t0} ms）"))

        val poster = results.firstNotNullOfOrNull { it.posterPath }
        if (poster == null) {
            add(ConnCheck("图片", ConnCheck.Level.WARN, "没拿到测试图片，无法检查图片地址"))
            return@launchTask
        }
        try {
            val bytes = TmdbScrapeSource(t).download(t.imageUrl(poster, "w92")!!)
            add(ConnCheck("图片", ConnCheck.Level.OK, "图片地址可用（${bytes.size / 1024} KB 测试图）"))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            add(ConnCheck("图片", ConnCheck.Level.FAIL, "图片下载失败：${connErrorText(e)}"))
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
            if (e is CancellationException) throw e
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
            if (e is CancellationException) throw e
            val why = if (e is StorageException && (e.code == 401 || e.code == 403)) "没有写入权限" else connErrorText(e)
            return ConnCheck(label, ConnCheck.Level.FAIL, "可读取，但$why")
        }
        return try {
            s.delete(probe)
            ConnCheck(label, ConnCheck.Level.OK, "可读写")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
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
        is IllegalArgumentException -> "地址格式不对，应类似 http://192.168.1.10:5005"
        is UnknownHostException -> "找不到主机，检查地址"
        is ConnectException -> "连不上服务器，检查 IP、端口，以及 WebDAV 服务是否开启"
        is SocketTimeoutException -> "连接超时，检查网络或是否在同一局域网"
        is SSLException -> "HTTPS 握手失败（证书问题？局域网可改用 http）"
        else -> e.message ?: e.javaClass.simpleName
    }
}
