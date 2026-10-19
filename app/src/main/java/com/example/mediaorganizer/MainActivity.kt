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
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mediaorganizer.metadata.*
import com.example.mediaorganizer.metadata.extra.OnlineExtras
import com.example.mediaorganizer.naming.*
import com.example.mediaorganizer.scraper.*
import com.example.mediaorganizer.settings.AppSettings
import com.example.mediaorganizer.settings.LibraryProfile
import com.example.mediaorganizer.settings.addLibrary
import com.example.mediaorganizer.settings.allLibraries
import com.example.mediaorganizer.settings.removeActiveLibrary
import com.example.mediaorganizer.settings.switchLibrary
import com.example.mediaorganizer.settings.SettingsRepository
import com.example.mediaorganizer.storage.CleanupMode
import com.example.mediaorganizer.storage.CleanupReport
import com.example.mediaorganizer.storage.CleanupTarget
import com.example.mediaorganizer.storage.FileOperation
import com.example.mediaorganizer.storage.NoOpStorage
import com.example.mediaorganizer.parser.MediaNameParser
import com.example.mediaorganizer.storage.SourceCleaner
import com.example.mediaorganizer.storage.OperationLog
import com.example.mediaorganizer.storage.RemoteFile
import com.example.mediaorganizer.storage.StorageException
import com.example.mediaorganizer.storage.StorageFactory
import com.example.mediaorganizer.storage.StorageProvider
import com.example.mediaorganizer.storage.StorageType
import com.example.mediaorganizer.storage.aliyun.AliyunLogin
import com.example.mediaorganizer.storage.pan115.Pan115Client
import com.example.mediaorganizer.storage.pan115.Pan115Login
import com.example.mediaorganizer.storage.joinPath
import com.example.mediaorganizer.storage.normalizePath
import com.example.mediaorganizer.storage.parentPath
import com.example.mediaorganizer.ui.AppTheme
import com.example.mediaorganizer.ui.FileBrowserScreen
import com.example.mediaorganizer.ui.MainScreen
import com.example.mediaorganizer.ui.NamingSettingsScreen
import com.example.mediaorganizer.ui.ScrapeSettingsScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.math.abs
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
        setContent { AppRoot() }
    }
}

enum class Page { MAIN, NAMING, SCRAPE }

@Composable
fun AppRoot(vm: MainViewModel = viewModel()) {
    AppTheme(vm.settings.themeMode) { Surface { AppPages(vm) } }
}

@Composable
private fun AppPages(vm: MainViewModel) {
    var page by rememberSaveable { mutableStateOf(Page.MAIN) }
    BackHandler(enabled = page != Page.MAIN) { page = Page.MAIN }

    if (!vm.loaded) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    Box(Modifier.fillMaxSize()) {
        Pages(vm, page) { page = it }
        // 文件管理盖在当前页上（下面的列表位置、筛选都保留），关掉回到原来的地方
        vm.browse?.let { Surface(Modifier.fillMaxSize()) { FileBrowserScreen(vm, it) } }
    }
}

@Composable
private fun Pages(vm: MainViewModel, page: Page, setPage: (Page) -> Unit) {
    when (page) {
        Page.MAIN -> MainScreen(vm, openNaming = { setPage(Page.NAMING) }, openScrape = { setPage(Page.SCRAPE) })
        Page.NAMING -> NamingSettingsScreen(vm.settings, vm.saved, vm::updateSettings) { setPage(Page.MAIN); vm.refreshPreviewAsync() }
        Page.SCRAPE -> ScrapeSettingsScreen(
            vm.settings, vm.saved, vm::updateSettings,
            tmdbChecks = vm.tmdbChecks, testing = vm.task == Task.TEST, onTestTmdb = vm::testTmdb,
            sourceChecks = vm.sourceChecks, onTestSources = vm::testSources,
        ) { setPage(Page.MAIN) }
    }
}

// ============================ ViewModel ============================

/** 列表里每个文件所处的状态，枚举顺序 = 「全部」视图里的排序（要处理的排前面） */
enum class RowCategory(val label: String) {
    PENDING("待确认"), UNMATCHED("未匹配"), FAILED("失败"), READY("可整理"), DONE("已完成"), EXCLUDED("已排除"),
    DELETED("已删除"),
}

data class MediaRow(
    val match: MatchResult,
    val chosen: TmdbSearchResult?,
    val confirmed: Boolean,
    val plan: RenamePlan? = null,
    val outcome: String? = null,     // null=未执行, "ok", "无需改动", "已删除", 或错误信息
    val excluded: Boolean = false,   // 用户选择不整理
) {
    val key: String get() = match.file?.path ?: match.parsed.rawName

    /** 文件现在在哪：已整理的在目标位置，其余还在原处 */
    val currentPath: String? get() = if (category == RowCategory.DONE) plan?.targetPath else match.file?.path?.let(::normalizePath)

    val category: RowCategory get() = when {
        outcome == "ok" || outcome == "无需改动" -> RowCategory.DONE
        outcome == DELETED_OUTCOME -> RowCategory.DELETED
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

const val DELETED_OUTCOME = "已删除"

/** 连接测试的一项结果 */
data class ConnCheck(val label: String, val level: Level, val detail: String) {
    enum class Level { OK, WARN, FAIL }
}

enum class ConnState { UNTESTED, OK, FAIL }

enum class Task { SCAN, TEST, MATCH, PRECHECK, ORGANIZE, SCRAPE, UNDO, CLEAN }

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

/** 手动搜索框里直接输 TMDB ID：tmdb:16660、tmdb=16660、tmdb 16660 */
private val TMDB_ID_QUERY = Regex("""(?i)tmdb(?:id)?\s*[:=：-]?\s*(\d+)""")

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

    /** 连接状态来自保存的最近一次测试结果：重启后还在，切换媒体库时跟着换 */
    val connState: ConnState get() = when (settings.lastTestOk) {
        true -> ConnState.OK
        false -> ConnState.FAIL
        null -> ConnState.UNTESTED
    }
    val connMessage: String get() = settings.lastTestMsg

    private fun recordConn(ok: Boolean, msg: String = "") =
        updateSettings(settings.copy(lastTestAt = System.currentTimeMillis(), lastTestOk = ok, lastTestMsg = msg))
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

    private var storage: StorageProvider? = null
    private var tmdb: TmdbClient? = null
    private var details: TmdbDetails? = null
    private var lastOps: List<FileOperation> = emptyList()
    private var saveJob: Job? = null
    private var taskJob: Job? = null

    init {
        viewModelScope.launch {
            settings = runCatching { repo.load() }.getOrDefault(AppSettings())
            lastOps = opLog.load()               // 上次没撤销的批次，重启后仍可撤销
            canUndo = undoSummary() != null
            if (canUndo) status = "上次整理了 ${undoSummary()?.moved ?: 0} 个文件，可在右上角菜单里撤销"
            loaded = true
        }
    }

    /** 每次修改立即生效，300ms 防抖后写入 DataStore（输入框打字不会频繁写盘） */
    fun updateSettings(input: AppSettings) {
        val old = settings
        var new = input
        if (StorageFactory.connectionKey(new) != StorageFactory.connectionKey(old)) {
            connChecks = emptyList()
            // 同一个媒体库改了连接信息：旧的测试结果作废；切换媒体库时保留它自己的结果
            if (new.activeLibrary == old.activeLibrary) new = new.copy(lastTestAt = 0, lastTestOk = null, lastTestMsg = "")
        }
        if (new.tmdbToken != old.tmdbToken || new.tmdbApiBase != old.tmdbApiBase || new.tmdbImageBase != old.tmdbImageBase)
            tmdbChecks = emptyList()
        if (new.storageType != old.storageType || StorageFactory.id(new) != StorageFactory.id(old)) {
            storage?.let { old -> viewModelScope.launch { runCatching { old.close() } } }
            storage = null                   // 换了媒体库，下次操作重新连接
        }
        settings = new
        saved = false
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(300)
            repo.save(new)
            saved = true
        }
    }

    // ---------- 多个媒体库 ----------

    val libraries: List<LibraryProfile> get() = settings.allLibraries()

    /** 切换后扫描结果属于旧媒体库，清空 */
    private fun resetForLibrary(new: AppSettings) {
        if (busy) return
        updateSettings(new)
        rows.clear()
        selected = emptySet()
        details = null
        connChecks = emptyList()
        status = "已切换到「${new.libraryName}」"
    }

    fun switchLibrary(id: String) { if (id != settings.activeLibrary) resetForLibrary(settings.switchLibrary(id)) }

    fun addLibrary() = resetForLibrary(settings.addLibrary("媒体库 ${libraries.size + 1}"))

    fun renameLibrary(name: String) {
        if (name.isNotBlank()) updateSettings(settings.copy(libraryName = name.trim()))
    }

    fun removeLibrary() {
        if (libraries.size > 1) resetForLibrary(settings.removeActiveLibrary())
    }

    private val storageId get() = StorageFactory.id(settings)

    private fun newStorage(): StorageProvider = StorageFactory.create(getApplication(), settings) { token ->
        // 阿里云盘每次刷新都会换 refresh_token，不存下来下次启动就失效
        viewModelScope.launch { updateSettings(settings.copy(aliRefreshToken = token)) }
    }

    // ---------- 网盘扫码登录 ----------

    /** 正在显示的登录二维码，null = 没在登录 */
    data class QrLogin(
        val title: String,
        val image: ByteArray?,
        val hint: String,
        val openUrl: String? = null,     // 同一台手机上可尝试直接用 App 打开
        val failed: Boolean = false,
    )

    var qr by mutableStateOf<QrLogin?>(null)
        private set
    private var qrJob: Job? = null

    fun cancelQr() { qrJob?.cancel(); qr = null }

    fun login115() {
        qrJob?.cancel()
        qrJob = viewModelScope.launch {
            val login = Pan115Login()
            try {
                qr = QrLogin("115 扫码登录", null, "正在获取二维码…")
                val s = login.start()
                qr = QrLogin("115 扫码登录", login.qrImage(s), "用 115 App 扫一扫；在这台手机上可以先保存图片，再从相册识别", s.qrContent)
                while (true) {
                    when (login.status(s)) {
                        Pan115Login.Status.WAITING -> {}
                        Pan115Login.Status.SCANNED -> qr = qr?.copy(hint = "已扫码，请在 115 App 里点「确认登录」")
                        Pan115Login.Status.CONFIRMED -> {
                            val cookie = login.finish(s)
                            updateSettings(settings.copy(pan115Cookie = cookie))
                            qr = null
                            status = "115 登录成功：" + runCatching { Pan115Client(cookie).userName() }.getOrDefault("")
                            return@launch
                        }
                        Pan115Login.Status.EXPIRED -> { qr = qr?.copy(hint = "二维码已过期", failed = true); return@launch }
                        Pan115Login.Status.CANCELED -> { qr = qr?.copy(hint = "已在 115 App 里取消", failed = true); return@launch }
                    }
                    delay(1500)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                qr = (qr ?: QrLogin("115 扫码登录", null, "")).copy(hint = "出错：${e.message}", failed = true)
            }
        }
    }

    fun loginAliyun() {
        val st = settings
        qrJob?.cancel()
        qrJob = viewModelScope.launch {
            val login = AliyunLogin(st.aliClientId.trim(), st.aliClientSecret.trim())
            try {
                qr = QrLogin("阿里云盘扫码授权", null, "正在获取二维码…")
                val s = login.start()
                qr = QrLogin("阿里云盘扫码授权", login.qrImage(s), "用阿里云盘 App 扫一扫并同意授权；在这台手机上可以先保存图片，再从相册识别")
                while (true) {
                    val (state, code) = login.status(s)
                    when (state) {
                        AliyunLogin.Status.WAITING -> {}
                        AliyunLogin.Status.SCANNED -> qr = qr?.copy(hint = "已扫码，请在阿里云盘 App 里同意授权")
                        AliyunLogin.Status.CONFIRMED -> {
                            updateSettings(settings.copy(aliRefreshToken = login.finish(code!!)))
                            qr = null
                            status = "阿里云盘授权成功"
                            return@launch
                        }
                        AliyunLogin.Status.EXPIRED -> { qr = qr?.copy(hint = "二维码已过期", failed = true); return@launch }
                    }
                    delay(2000)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                qr = (qr ?: QrLogin("阿里云盘扫码授权", null, "")).copy(hint = "出错：${e.message}", failed = true)
            }
        }
    }

    private fun newTmdb() = settings.let {
        TmdbClient(it.tmdbToken.trim(), apiBase = it.tmdbApiBase.trimEnd('/'), imageBase = it.tmdbImageBase.trimEnd('/'))
    }

    private fun rememberOps(ops: List<FileOperation>, append: Boolean) {
        lastOps = if (append) lastOps + ops else ops
        opLog.save(lastOps)
        canUndo = undoSummary() != null
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
            recordConn(false, connErrorText(e))
            status = "连接失败：$connMessage"
            return@launchTask
        }
        recordConn(true)
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

    /** 已经移动过的文件，把来源路径换成它现在所在的位置，才能再次整理 */
    private fun MediaRow.atCurrentLocation(): MediaRow {
        val p = plan ?: return this
        val f = match.file ?: return this
        if (outcome != "ok") return this
        return copy(match = match.copy(file = f.copy(path = p.targetPath, name = p.targetName)))
    }

    /** 选定匹配；applyToSame 时同名的其他待确认文件一并改成这个结果 */
    fun choose(key: String, result: TmdbSearchResult, applyToSame: Boolean) = launchTask(Task.MATCH) {
        applyChoice(key, result, applyToSame) ?: return@launchTask
        refreshPreview()
    }

    /** 选定匹配后立即整理这一个（同名的其他文件只改匹配，不整理） */
    fun chooseAndOrganize(key: String, result: TmdbSearchResult, applyToSame: Boolean) = launchTask(Task.ORGANIZE) {
        val newKey = applyChoice(key, result, applyToSame) ?: return@launchTask
        organizeSingle(newKey)               // 里面的 refreshPreview 会顺带给同名的其他文件生成目标路径
    }

    /** 改匹配，返回该行的新 key（已整理过的文件位置变了，key 也会变） */
    private fun applyChoice(key: String, result: TmdbSearchResult, applyToSame: Boolean): String? {
        val i = rows.indexOfFirst { it.key == key }
        if (i < 0) return null
        val group = rows[i].groupKey
        var n = 0
        for (j in rows.indices) {
            val r = rows[j]
            if (j == i || (applyToSame && r.groupKey == group && r.isOpenForMatch())) {
                // 失败的改匹配后可重试；已整理的从当前位置出发重新整理
                rows[j] = r.atCurrentLocation().copy(chosen = result, confirmed = true, plan = null, outcome = null)
                n++
            }
        }
        status = if (n > 1) "已将 $n 个文件匹配为「${result.title}」" else "已匹配为「${result.title}」"
        return rows[i].key
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

    /**
     * 手动搜索。TMDB 只搜它自己的译名和原名，像「怪化猫」（TMDB 叫「物怪」）这种别名搜不到，
     * 所以同时去豆瓣（以及填了 key 的 TheTVDB）查这个名字，拿原名 / ID 回 TMDB 找。
     * 也可以直接输「tmdb:16660」。
     */
    suspend fun searchTmdb(query: String, tv: Boolean): List<TmdbSearchResult> = coroutineScope {
        val t = tmdb ?: newTmdb().also { tmdb = it }
        val type = if (tv) MediaType.TV else MediaType.MOVIE
        TMDB_ID_QUERY.matchEntire(query.trim())?.let { m ->
            return@coroutineScope listOf(t.byId(type, m.groupValues[1].toInt()))
        }
        val extras = OnlineExtras(settings.scrape.extra)
        val direct = async { if (tv) t.searchTv(query) else t.searchMovie(query) }
        val douban = async { runCatching { extras.doubanAliases(query) }.getOrDefault(emptyList()) }
        val tvdb = async { runCatching { extras.tvdbAliases(query, tv) }.getOrDefault(emptyList()) }

        val doubanAlts = douban.await().let { all -> all.filter { it.tv == tv }.ifEmpty { all } }.take(3)
        val viaAlias = (tvdb.await().take(3) + doubanAlts).map { alt ->
            async {
                val label = "${alt.source}「${alt.title}」" +
                    (alt.original?.takeIf { it != alt.title }?.let { " → $it" } ?: "")
                runCatching {
                    when {
                        alt.tmdbId != null -> listOf(t.byId(type, alt.tmdbId))
                        alt.tvdbId != null && tv -> listOfNotNull(t.findByTvdb(alt.tvdbId))
                        else -> {
                            val name = alt.original ?: alt.title
                            (if (tv) t.searchTv(name, alt.year) else t.searchMovie(name, alt.year))
                                .filter { r -> alt.year == null || r.year == null || abs(r.year - alt.year) <= 1 }
                                .take(2)
                        }
                    }.map { it.copy(via = label) }
                }.getOrDefault(emptyList())
            }
        }.awaitAll().flatten()

        // 名字完全对上的直接结果最可信，其次是经别名找到的，再其次是其他直接结果
        val (exact, rest) = direct.await().partition { it.title == query || it.originalTitle == query }
        (exact + viaAlias + rest).distinctBy { it.id }
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

    /** @param only 只处理这些条目（多选时），null = 全部 */
    private fun organizable(only: Set<String>? = null) = rows.withIndex().filter {
        it.value.category == RowCategory.READY && it.value.plan != null && (only == null || it.value.key in only)
    }

    fun organizableCount(only: Set<String>? = null) = organizable(only).size

    /** 已整理、可以（重新）刮削的条目数 */
    fun scrapableCount(only: Set<String>? = null) =
        rows.count { it.category == RowCategory.DONE && it.plan != null && (only == null || it.key in only) }

    fun organizeSummary(only: Set<String>? = null): OrganizeSummary {
        val items = organizable(only).map { (_, r) -> r.plan!!.let { r.key to it } }
        fun toMove(p: Pair<String, RenamePlan>) = PlannedMove(p.first, p.second.item.file.path, p.second.targetPath)
        val (unchanged, changed) = items.partition { it.second.unchanged }
        val (moves, renames) = changed.partition { parentPath(it.second.item.file.path) != normalizePath(it.second.targetDir) }
        return OrganizeSummary(
            moves = moves.map(::toMove),
            renames = renames.map(::toMove),
            unchanged = unchanged.map(::toMove),
            duplicates = changed.groupBy { it.second.targetPath }.filterValues { it.size > 1 }
                .map { (target, list) -> target to list.map(::toMove) },
            notIncluded = if (only == null) rows.count { it.category in setOf(RowCategory.PENDING, RowCategory.UNMATCHED, RowCategory.EXCLUDED) }
            else rows.count { it.key !in only && it.category != RowCategory.DONE && it.category != RowCategory.DELETED },
        )
    }

    fun execute() = launchTask(Task.ORGANIZE) { runOrganize(organizable(), single = false) }

    // ---------- 整理前预检 ----------

    /** 预检结果 + 预计生成的刮削文件；非 null 时显示预检页 */
    data class PrecheckState(
        val result: PrecheckResult,
        val summary: OrganizeSummary,
        val scrapeFiles: List<String>,      // 整理后会刮削出的文件（不含 Fanart.tv 等补充来源）
        val scrapeCapped: Boolean,          // 文件太多时只算了前一部分
        val only: Set<String>? = null,      // 只整理多选的这些；null = 全部可整理的
    )

    var precheck by mutableStateOf<PrecheckState?>(null)
        private set
    /** 用户为每个问题文件选的处理方式；没选的用 Conflict.actions 的第一个 */
    val conflictActions = mutableStateMapOf<String, ConflictAction>()

    fun actionFor(c: Conflict) = conflictActions[c.key] ?: c.actions.first()

    /** 点「整理」先预检：查目标位置、撞名、元数据，再给用户看；only 非 null 时只整理多选的这些 */
    fun startOrganize(only: Set<String>? = null) = launchTask(Task.PRECHECK) {
        val s = storage ?: return@launchTask
        val items = organizable(only).map { it.value.key to it.value.plan!! }
        if (items.isEmpty()) return@launchTask
        status = "预检中…"
        val result = Precheck(s).run(items) { done, total ->
            progress = done to total
            status = "预检中：检查目标目录 $done/$total"
        }
        val (files, capped) = if (settings.autoScrape) scrapePreview(items.map { it.second }) else emptyList<String>() to false
        conflictActions.clear()
        precheck = PrecheckState(result, organizeSummary(only), files, capped, only)
        status = if (result.conflicts.isEmpty()) "预检通过，没有冲突" else "预检发现 ${result.conflicts.size} 个问题，请逐项确认"
    }

    fun dismissPrecheck() { precheck = null }

    /** 用 TMDB 详情（整理计划时已缓存）算出会写哪些 NFO / 图片，不联网下载 */
    private suspend fun scrapePreview(plans: List<RenamePlan>): Pair<List<String>, Boolean> {
        val d = details ?: return emptyList<String>() to false
        val limit = 300
        val scraper = Scraper(NoOpStorage, d, DryScrapeSource)
        val files = plans.take(limit).flatMap { p -> runCatching { scraper.collectOutputs(p, settings.scrape) }.getOrDefault(emptyList()) }
            .map { normalizePath(it.path) }.distinct()
        return files to (plans.size > limit)
    }

    /** 按预检页上的选择执行：跳过的标为已排除，删除的删掉源文件，其余照常整理 */
    fun executeChecked() = launchTask(Task.ORGANIZE) {
        val pc = precheck ?: return@launchTask
        precheck = null
        val s = storage ?: return@launchTask
        if (pc.only != null) clearSelection()
        val taken = pc.result.existingNames.mapValues { it.value.toMutableSet() }.toMutableMap()
        val toRun = mutableListOf<IndexedValue<MediaRow>>()
        var deleted = 0
        var skipped = 0
        val errors = mutableListOf<String>()
        for ((i, row) in organizable(pc.only)) {
            val plan = row.plan!!
            val c = pc.result.conflictOf(row.key)
            when (c?.let { actionFor(it) } ?: ConflictAction.PROCEED) {
                ConflictAction.PROCEED -> toRun += IndexedValue(i, row)
                ConflictAction.SKIP -> { rows[i] = row.copy(excluded = true, plan = null); skipped++ }
                ConflictAction.DELETE_SOURCE -> runCatching { s.delete(row.match.file!!) }
                    .onSuccess { rows[i] = row.copy(outcome = DELETED_OUTCOME); deleted++ }
                    .onFailure { errors += "${row.match.file?.name}：${it.message}" }
                ConflictAction.OVERWRITE -> runCatching {
                    s.delete(RemoteFile(plan.targetPath, plan.targetName, isDirectory = false))
                }.onSuccess { toRun += IndexedValue(i, row) }
                    .onFailure { errors += "${plan.targetName}：删除旧文件失败 ${it.message}" }
                ConflictAction.KEEP_BOTH -> {
                    val dir = normalizePath(plan.targetDir)
                    val names = taken.getOrPut(dir) { mutableSetOf() }
                    // 本批次里其他文件占用的名字也要避开
                    toRun.filter { normalizePath(it.value.plan!!.targetDir) == dir }.forEach { names += it.value.plan!!.targetName }
                    val newName = Precheck.uniqueName(plan.targetName, names)
                    names += newName
                    rows[i] = row.copy(plan = plan.copy(targetName = newName))
                    toRun += IndexedValue(i, rows[i])
                }
            }
        }
        runOrganize(toRun, single = false)
        val extra = listOfNotNull(
            if (skipped > 0) "跳过 $skipped" else null,
            if (deleted > 0) "删除 $deleted" else null,
            if (errors.isNotEmpty()) "${errors.size} 项处理失败（${errors.first()}）" else null,
        )
        if (extra.isNotEmpty()) status += "；" + extra.joinToString("，")
    }

    /** 详情里单独整理一个文件；待确认的先按当前匹配确认，失败的可重试 */
    fun organizeOne(key: String) = launchTask(Task.ORGANIZE) { organizeSingle(key) }

    /** @param targetName 指定新文件名（「保留两者」时用），null 按命名规则 */
    private suspend fun organizeSingle(key: String, targetName: String? = null) {
        val i = rows.indexOfFirst { it.key == key }
        if (i < 0 || rows[i].chosen == null || rows[i].match.file == null) return
        rows[i] = rows[i].copy(confirmed = true, excluded = false, outcome = null, plan = null)
        refreshPreview()
        if (targetName != null) rows[i].plan?.let { rows[i] = rows[i].copy(plan = it.copy(targetName = targetName)) }
        // 并入上一批的撤销记录，不覆盖之前的批量整理
        runOrganize(listOf(IndexedValue(i, rows[i])).filter { it.value.plan != null }, single = true)
    }

    /** single：详情里手动整理一个文件——撤销记录并入上一批，刮削一律全部重新生成 */
    private suspend fun runOrganize(indexed: List<IndexedValue<MediaRow>>, single: Boolean) {
        val s = storage ?: return
        if (indexed.isEmpty()) return
        val report = RenameExecutor(s, storageId).execute(indexed.map { it.value.plan!! }) { done, total, _ ->
            progress = done to total
            status = "整理中 $done/$total"
        }
        indexed.zip(report.outcomes).forEach { (iv, o) ->
            rows[iv.index] = rows[iv.index].copy(outcome = if (o.skipped) "无需改动" else o.error ?: "ok")
        }
        rememberOps(report.operations, append = single)
        status = if (indexed.size == 1) {
            val o = report.outcomes.single()
            when {
                o.error != null -> "整理失败：${o.error}"
                o.skipped -> "已在目标位置，无需改动"
                else -> "已整理到 ${o.plan.targetPath}"
            }
        } else "整理完成：成功 ${report.succeeded}，失败 ${report.failed}"
        if (indexed.size > 1 && report.failed > 0) filter = RowCategory.FAILED

        cleanSources(s, report.outcomes.filter { it.error == null && !it.skipped }.map { it.plan })?.let { c ->
            if (c.removed.isNotEmpty()) status += "；清理原文件夹 ${c.removed.size} 个"
            if (c.kept.isNotEmpty()) status += "，${c.kept.size} 个还有其他文件已保留"
            if (c.errors.isNotEmpty()) status += "，${c.errors.size} 个删除失败（${c.errors.first()}）"
        }

        if (settings.autoScrape) {
            task = Task.SCRAPE
            runScrape(s, report.outcomes.filter { it.error == null }.map { it.plan }, prefix = "$status；", overwrite = single)
        }
    }

    // ---------- 清理原文件夹 ----------

    /** 不能删的：扫描目录、两个库目录，以及所有已知的目标目录（含上级） */
    private fun cleaner(s: StorageProvider, mode: CleanupMode): SourceCleaner {
        val st = settings
        val targets = rows.mapNotNull { it.plan }.flatMap { listOf(it.targetDir, it.showDir) }
        return SourceCleaner(s, mode, st.scanDir, listOf(st.movieRoot, st.tvRoot) + targets)
    }

    private suspend fun cleanSources(s: StorageProvider, moved: List<RenamePlan>): CleanupReport? {
        val mode = settings.cleanupMode
        if (mode == CleanupMode.OFF || moved.isEmpty()) return null
        status += "；清理原文件夹…"
        val sources = moved.map { parentPath(it.item.file.path) }
        return cleaner(s, mode).cleanAfterMove(sources).also { status = status.removeSuffix("；清理原文件夹…") }
    }

    /** 手动清理时找到的残留文件夹，null = 没在看 */
    var leftovers by mutableStateOf<List<CleanupTarget>?>(null)
        private set

    private fun manualMode() = settings.cleanupMode.takeIf { it != CleanupMode.OFF } ?: CleanupMode.EMPTY_ONLY

    fun findLeftovers() = launchTask(Task.CLEAN) {
        status = "查找扫描目录里的残留文件夹…"
        val s = storage ?: newStorage().also { it.connect(); storage = it }
        val found = cleaner(s, manualMode()).findLeftovers()
        leftovers = found
        status = if (found.isEmpty()) "没有发现可清理的文件夹" else "发现 ${found.size} 个可清理的文件夹"
    }

    fun deleteLeftovers(targets: List<CleanupTarget>) = launchTask(Task.CLEAN) {
        leftovers = null
        val s = storage ?: return@launchTask
        status = "清理中…"
        val r = cleaner(s, manualMode()).delete(targets)
        status = "已删除 ${r.removed.size} 个文件夹" + if (r.errors.isNotEmpty()) "，${r.errors.size} 个失败（${r.errors.first()}）" else ""
    }

    fun dismissLeftovers() { leftovers = null }

    // ---------- 文件管理 ----------

    /** 正在浏览的文件夹；非 null 时显示文件管理页。highlight = 打开时要标出的文件名 */
    data class BrowseRequest(val dir: String, val highlight: String? = null)

    var browse by mutableStateOf<BrowseRequest?>(null)
        private set

    fun openBrowser(dir: String, highlight: String? = null) { browse = BrowseRequest(normalizePath(dir), highlight) }

    fun closeBrowser() {
        browse = null
        if (browseChanged) { browseChanged = false; refreshPreviewAsync(all = false) }
    }

    private var browseChanged = false

    private suspend fun connected(): StorageProvider = storage ?: newStorage().also { it.connect(); storage = it }

    suspend fun browseList(dir: String): List<RemoteFile> = connected().list(normalizePath(dir))

    /** 逐个删除，返回失败的说明 */
    suspend fun browseDelete(files: List<RemoteFile>): List<String> {
        val s = connected()
        val errors = mutableListOf<String>()
        for (f in files) {
            try { s.delete(f); remapRows(f.path, null) } catch (e: StorageException) { errors += "${f.name}：${e.message}" }
            if (s.minRequestIntervalMs > 0) delay(s.minRequestIntervalMs)
        }
        return errors
    }

    suspend fun browseRename(file: RemoteFile, newName: String) {
        val r = connected().rename(file, newName)
        remapRows(file.path, r.path)
    }

    suspend fun browseMkdir(dir: String, name: String) = connected().mkdirs(joinPath(dir, name))

    /** 逐个移动到 targetDir，返回失败的说明 */
    suspend fun browseMove(files: List<RemoteFile>, targetDir: String): List<String> {
        val s = connected()
        val errors = mutableListOf<String>()
        for (f in files) {
            try { val r = s.move(f, targetDir); remapRows(f.path, r.path) } catch (e: StorageException) { errors += "${f.name}：${e.message}" }
            if (s.minRequestIntervalMs > 0) delay(s.minRequestIntervalMs)
        }
        return errors
    }

    /**
     * 文件管理里删除 / 改名 / 移动后，同步列表里受影响的条目（文件本身或它所在的文件夹）：
     * 删掉的标为已删除；挪了位置的更新路径并回到待整理，关闭文件管理时重新生成目标路径
     */
    private fun remapRows(old: String, new: String?) {
        val o = normalizePath(old)
        for (i in rows.indices) {
            val r = rows[i]
            val cur = r.currentPath ?: continue
            if (r.category == RowCategory.DELETED || (cur != o && !cur.startsWith("$o/"))) continue
            browseChanged = true
            rows[i] = if (new == null) r.copy(outcome = DELETED_OUTCOME)
            else {
                val path = normalizePath(new) + cur.removePrefix(o)
                val file = (r.match.file ?: continue).copy(path = path, name = path.substringAfterLast('/'))
                r.copy(match = r.match.copy(file = file), outcome = null, plan = null)
            }
        }
    }

    // ---------- 目标已存在：对比和处理 ----------

    /** 目标文件夹里的情况：和本文件同名的那个、文件夹里所有视频，以及其中和本文件是同一部/同一集的 */
    data class TargetInfo(
        val dir: String, val existing: RemoteFile?, val videos: List<RemoteFile>, val dirMissing: Boolean,
        val related: List<RemoteFile> = videos,
        val dupEpisodes: List<List<RemoteFile>> = emptyList(),   // 剧集：同剧其他集的重复版本
    )

    /** 不占用 busy，详情弹窗打开时直接查 */
    suspend fun inspectTarget(key: String): TargetInfo? {
        val row = rows.firstOrNull { it.key == key } ?: return null
        val plan = row.plan ?: return null
        val s = storage ?: newStorage().also { it.connect(); storage = it }
        val dir = normalizePath(plan.targetDir)
        val list = try {
            s.list(dir)
        } catch (e: StorageException) {
            if (e.code == 404) return TargetInfo(dir, null, emptyList(), dirMissing = true) else throw e
        }
        fun videosOf(l: List<RemoteFile>) = l.filter { !it.isDirectory && it.name.substringAfterLast('.', "").lowercase() in MediaNameParser.VIDEO_EXTENSIONS }
        val videos = videosOf(list)
        // 剧集：再看看剧集根文件夹和各季文件夹，找出别的集的重复版本
        val showVideos = if (!plan.input.isEpisode) emptyList() else {
            val show = normalizePath(plan.showDir)
            val showList = if (show == dir) list else runCatching { s.list(show) }.getOrDefault(emptyList())
            videos + videosOf(showList) + showList.filter { it.isDirectory && normalizePath(it.path) != dir }
                .flatMap { d -> videosOf(runCatching { s.list(d.path) }.getOrDefault(emptyList())) }
        }
        return TargetInfo(
            dir, list.firstOrNull { !it.isDirectory && it.name == plan.targetName }, videos, dirMissing = false,
            related = Precheck.sameItem(videos, plan.input),
            dupEpisodes = Precheck.duplicateEpisodes(showVideos, plan.input),
        )
    }

    /** 删除影视库里的某个文件（对比后决定不要的旧版本） */
    fun deleteRemote(file: RemoteFile, onDone: () -> Unit = {}) = launchTask(Task.ORGANIZE) {
        val s = storage ?: return@launchTask
        s.delete(file)
        status = "已删除 ${file.name}"
        onDone()
    }

    /** 批量删除影视库里的文件（同剧其他集的重复版本） */
    fun deleteRemoteAll(files: List<RemoteFile>, onDone: () -> Unit = {}) = launchTask(Task.ORGANIZE) {
        val s = storage ?: return@launchTask
        val errors = mutableListOf<String>()
        files.forEachIndexed { i, f ->
            status = "删除中 ${i + 1}/${files.size}"
            try { s.delete(f) } catch (e: StorageException) { errors += "${f.name}：${e.message}" }
            if (s.minRequestIntervalMs > 0) delay(s.minRequestIntervalMs)
        }
        status = "已删除 ${files.size - errors.size} 个文件" + if (errors.isNotEmpty()) "，${errors.size} 个失败（${errors.first()}）" else ""
        onDone()
    }

    /** 删除这个源文件（已有的版本更好） */
    fun deleteSource(key: String) = launchTask(Task.ORGANIZE) {
        val s = storage ?: return@launchTask
        val i = rows.indexOfFirst { it.key == key }
        val file = rows.getOrNull(i)?.match?.file ?: return@launchTask
        s.delete(file)
        rows[i] = rows[i].copy(outcome = DELETED_OUTCOME)
        status = "已删除 ${file.name}"
    }

    /** 先删掉目标位置的旧文件，再整理这个 */
    fun overwriteAndOrganize(key: String) = launchTask(Task.ORGANIZE) {
        val s = storage ?: return@launchTask
        val plan = rows.firstOrNull { it.key == key }?.plan ?: return@launchTask
        s.delete(RemoteFile(plan.targetPath, plan.targetName, isDirectory = false))
        organizeSingle(key)
    }

    /** 新文件名后加 (2)，和旧文件并存 */
    fun keepBothAndOrganize(key: String, taken: Set<String>) = launchTask(Task.ORGANIZE) {
        val plan = rows.firstOrNull { it.key == key }?.plan ?: return@launchTask
        organizeSingle(key, Precheck.uniqueName(plan.targetName, taken + plan.targetName))
    }

    fun scrapeOne(key: String) = launchTask(Task.SCRAPE) {
        val s = storage ?: return@launchTask
        val plan = rows.firstOrNull { it.key == key && it.category == RowCategory.DONE }?.plan ?: return@launchTask
        runScrape(s, listOf(plan), overwrite = true)        // 手动刮削单项：全部重新生成
    }

    /** 刮削已整理的条目；only 非 null 时只刮削多选的这些 */
    fun scrapeOnly(only: Set<String>? = null) = launchTask(Task.SCRAPE) {
        val s = storage ?: return@launchTask
        if (only != null) clearSelection()
        val plans = rows.filter { it.category == RowCategory.DONE && (only == null || it.key in only) }.mapNotNull { it.plan }
        if (plans.isEmpty()) { status = "没有已整理的条目可刮削"; return@launchTask }
        runScrape(s, plans)
    }

    private suspend fun runScrape(s: StorageProvider, plans: List<RenamePlan>, prefix: String = "", overwrite: Boolean = false) {
        val config = if (overwrite) settings.scrape.copy(strategy = ScrapeStrategy.OVERWRITE) else settings.scrape
        val d = details ?: return
        val t = tmdb ?: return
        progress = null
        val extras = config.extra.takeIf { it.douban || it.bangumi || it.tvdb || it.fanart }?.let { OnlineExtras(it) }
        val report = Scraper(s, d, TmdbScrapeSource(t), storageId, extras).scrape(plans, config) { done, total ->
            progress = done to total
            status = "${prefix}刮削中 $done/$total"
        }
        rememberOps(report.operations, append = true)     // 撤销时一并删除新写入的 NFO 和图片
        status = "${prefix}刮削：写入 ${report.written}，跳过 ${report.skipped}" +
            if (report.errors.isNotEmpty()) "，失败 ${report.errors.size}（${report.errors.first()}）" else ""
    }

    /** 没有移动 / 新写入任何文件的批次不算可撤销（只有建目录之类的记录） */
    fun undoSummary(): UndoSummary? {
        val ops = lastOps.takeIf { it.isNotEmpty() } ?: return null
        val moves = ops.filter { it.type == FileOperation.Type.MOVE || it.type == FileOperation.Type.RENAME }
        if (moves.isEmpty() && ops.none { it.type == FileOperation.Type.UPLOAD }) return null
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
            recordConn(firstFail == null, firstFail?.let { "${it.label}：${it.detail}" } ?: "")
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
            add(ConnCheck("服务器", ConnCheck.Level.FAIL, badConfigText(e)))
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

    /** 测试已开启的补充刮削来源（豆瓣 / Bangumi / TheTVDB / Fanart.tv） */
    var sourceChecks by mutableStateOf<List<ConnCheck>>(emptyList())
        private set

    fun testSources() = launchTask(Task.TEST) {
        sourceChecks = emptyList()
        val results = OnlineExtras(settings.scrape.extra).test()
        sourceChecks = if (results.isEmpty()) listOf(ConnCheck("来源", ConnCheck.Level.WARN, "没有开启任何补充来源"))
        else results.map { (name, r) ->
            r.fold(
                { ConnCheck(name, ConnCheck.Level.OK, it) },
                { ConnCheck(name, ConnCheck.Level.FAIL, it.message ?: it.javaClass.simpleName) },
            )
        }
    }

    private suspend fun checkDir(s: StorageProvider, label: String, dir: String, library: Boolean): ConnCheck {
        if (dir.isBlank()) return ConnCheck(label, ConnCheck.Level.WARN, "未填写")
        val path = normalizePath(dir)
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
    private suspend fun checkWritable(s: StorageProvider, label: String, dir: String): ConnCheck {
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

    /** 设置没填够时用工厂给的提示；WebDAV 地址解析失败时 OkHttp 的英文报错换成中文 */
    private fun badConfigText(e: IllegalArgumentException): String =
        StorageFactory.missing(settings)
            ?: if (settings.storageType == StorageType.WEBDAV) "地址格式不对，应类似 http://192.168.1.10:5005"
            else e.message ?: "设置有误"

    private fun connErrorText(e: Exception): String = when (e) {
        is StorageException -> when (e.code) {
            401 -> "用户名或密码错误"
            403 -> "没有访问权限（HTTP 403）"
            404 -> "路径不存在（HTTP 404），检查地址里的路径部分"
            405 -> "该地址不是 WebDAV 服务（HTTP 405），检查端口和路径"
            else -> e.message ?: "HTTP ${e.code}"
        }
        is IllegalArgumentException -> badConfigText(e)
        is UnknownHostException -> "找不到主机，检查地址"
        is ConnectException -> "连不上服务器，检查 IP、端口，以及 WebDAV 服务是否开启"
        is SocketTimeoutException -> "连接超时，检查网络或是否在同一局域网"
        is SSLException -> "HTTPS 握手失败（证书问题？局域网可改用 http）"
        else -> e.message ?: e.javaClass.simpleName
    }
}
