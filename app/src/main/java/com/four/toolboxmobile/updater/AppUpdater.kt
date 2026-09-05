package com.four.toolboxmobile.updater

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.four.toolboxmobile.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 应用内更新（2026-09-01 新增）：检查 GitHub Releases → 4 线程分段下载 → 合并落盘 → 拉起系统安装器。
 * 流程语义参考桌面端 dsh-app 的 AppUpdater.cs：
 * - 检查：GET releases/latest，tag 严格 vX.Y.Z；版本比较先行（Release 不高于本地 = 无更新）；
 *   404 无 Release 视为「成功且无更新」；tag 非法/资产缺失/网络失败 = 检查失败（不误报）
 * - 网络兜底：直连失败且 127.0.0.1:7890 可达（手机上 Clash 在跑）→ 显式代理重试一次
 * - 下载：API 给的资产 size 均分 4 段 Range 并行 → cacheDir/update/ 下的 .partN 临时文件 →
 *   顺序合并（同遍 SHA-256，资产带 digest 则校验）→ 写入系统下载目录（29+ MediaStore，26~28 公共目录）
 * - 取消：置标志位，线程在 64KB 粒度内响应；临时文件与未完成的目标文件全清
 * - 「可安装」检测：检查到新版时顺手查系统下载目录，同名同大小 APK 已存在则直接进 Downloaded 态
 */
object AppUpdater {

    private const val REPO = "Number444/Toolbox-Mobile"
    private const val API_URL = "https://api.github.com/repos/$REPO/releases/latest"
    private const val APK_MIME = "application/vnd.android.package-archive"
    private const val THREADS = 4
    private const val BUFFER = 64 * 1024
    private const val TIMEOUT_S = 30L
    private const val PROGRESS_INTERVAL_MS = 250L
    private val TAG_REGEX = Regex("^v\\d+\\.\\d+\\.\\d+$")

    /** 更新状态机（设置页 collectAsState 驱动行展示与对话框） */
    sealed interface UpdateState {
        data object Idle : UpdateState
        data object Checking : UpdateState
        data object UpToDate : UpdateState
        data class Failed(val message: String, val needsStoragePermission: Boolean = false) : UpdateState
        data class Available(val version: String, val sizeBytes: Long, val notes: String?) : UpdateState
        data class Downloading(val progress: Float, val doneBytes: Long, val totalBytes: Long) : UpdateState
        data class Downloaded(val version: String, val fileName: String, val uri: Uri) : UpdateState
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .build()

    /** 代理兜底专用实例（静态复用，仿 dsh-app：低频创建 + 流读取期不能 Dispose） */
    private val proxyClient by lazy {
        client.newBuilder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 7890)))
            .build()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runLock = Mutex()

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    // 最近一次检查到的新版信息（下载动作用；UpToDate 时清空）
    private var latestVersion: String? = null
    private var latestUrl: String? = null
    private var latestName: String? = null
    private var latestSize: Long = -1
    private var latestDigest: String? = null

    @Volatile
    private var cancelRequested = false

    // ---------------- 检查 ----------------

    /**
     * 检查最新版。[force]=false 时运行期内只自动查一次（已有结果直接跳过）；手动重查传 true。
     */
    suspend fun check(context: Context, force: Boolean = false) {
        if (!force && _state.value !is UpdateState.Idle) return
        if (!runLock.tryLock()) return
        try {
            // cleanupParts（文件 IO）与后续 findDownloadedApk（MediaStore binder 查询）
            // 是磁盘/IPC 操作，而调用方可能在主线程（设置页 LaunchedEffect）——整体切 IO（2026-09-06 修复）
            withContext(Dispatchers.IO) {
                cleanupParts(context)
            _state.value = UpdateState.Checking
            when (val fetch = fetchRelease()) {
                // 网络/HTTP 失败不清 latest*：已知的更新仍可经「重试」下载（瞬断不丢已查到的结果）
                is FetchResult.NetworkFail -> {
                    _state.value = UpdateState.Failed("无法访问 GitHub Releases（网络不可用或代理异常）")
                }
                is FetchResult.HttpError -> {
                    _state.value = UpdateState.Failed("GitHub API 返回 ${fetch.code}")
                }
                is FetchResult.BadData -> {
                    _state.value = UpdateState.Failed("GitHub 返回数据异常")
                }
                FetchResult.NotFound -> {
                    clearLatest()
                    _state.value = UpdateState.UpToDate
                }
                is FetchResult.Ok -> handleReleaseJson(context, fetch.json)
            }
            }
        } finally {
            runLock.unlock()
        }
    }

    private fun handleReleaseJson(context: Context, json: JSONObject) {
        val tag = json.optString("tag_name", "")
        if (!TAG_REGEX.matches(tag)) {
            clearLatest()
            _state.value = UpdateState.Failed("Release 版本号格式异常")
            return
        }
        val version = tag.removePrefix("v")
        val cmp = compareVersions(version, BuildConfig.VERSION_NAME)
        if (cmp == null) {
            clearLatest()
            _state.value = UpdateState.Failed("Release 版本号格式异常")
            return
        }
        if (cmp <= 0) {
            // 版本比较先行：不高于本地即无更新，资产是否完整无关紧要（仿 dsh-app）
            clearLatest()
            _state.value = UpdateState.UpToDate
            return
        }
        // 有新版：找 .apk 资产（现有发布流程资产名为 ToolboxMobile-vX.Y.Z-release.apk）
        val assets = json.optJSONArray("assets")
        var url: String? = null
        var name: String? = null
        var size = -1L
        var digest: String? = null
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                val n = a.optString("name", "")
                if (!n.endsWith(".apk")) continue
                url = a.optString("browser_download_url", "").ifEmpty { null }
                name = n
                size = a.optLong("size", -1)
                // GitHub API 较新资产带 digest 字段（"sha256:hex"）；只认 sha256 前缀，
                // 未来换算法（如 sha512）宁可放弃校验也不永久误报「校验失败」
                val d = a.optString("digest", "")
                digest = if (d.startsWith("sha256:", ignoreCase = true)) {
                    d.substring(7).ifEmpty { null }
                } else {
                    null
                }
                break
            }
        }
        if (url == null || name == null || size <= 0) {
            clearLatest()
            _state.value = UpdateState.Failed("发布不完整（缺少安装包资产）")
            return
        }
        latestVersion = version
        latestUrl = url
        latestName = name
        latestSize = size
        latestDigest = digest

        // 「可安装」检测：系统下载目录已有同名同大小 APK → 直接就绪
        val existing = findDownloadedApk(context, name, size)
        if (existing != null) {
            _state.value = UpdateState.Downloaded(version, name, existing)
            return
        }
        val notes = json.optString("body", "").trim().ifEmpty { null }?.let {
            // 截断 300 字符：对话框无滚动兜底，过长会把按钮挤出屏幕
            if (it.length > 300) it.take(300) + "\n…" else it
        }
        _state.value = UpdateState.Available(version, size, notes)
    }

    /** 直连 → 失败且本机代理可达 → 代理重试一次 */
    private suspend fun fetchRelease(): FetchResult {
        val direct = doFetch(client)
        if (direct !is FetchResult.NetworkFail) return direct
        if (!isProxyAlive()) return direct
        return doFetch(proxyClient)
    }

    private suspend fun doFetch(http: OkHttpClient): FetchResult = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(API_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "toolboxmobile-updater")
            .build()
        try {
            http.newCall(req).execute().use { resp ->
                when {
                    resp.code == 404 -> FetchResult.NotFound
                    !resp.isSuccessful -> FetchResult.HttpError(resp.code)
                    else -> {
                        val text = resp.body?.string() ?: return@use FetchResult.NetworkFail
                        FetchResult.Ok(JSONObject(text))
                    }
                }
            }
        } catch (e: JSONException) {
            FetchResult.BadData // 2xx 但 body 不是 JSON：数据异常，别误报成网络失败
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            FetchResult.NetworkFail
        }
    }

    // ---------------- 下载 ----------------

    /** 检查请求结果：Ok=解析后的 JSON；NotFound=仓库尚无 Release；HttpError=非 2xx；
     *  BadData=2xx 但 JSON 解析失败；NetworkFail=网络层失败 */
    private sealed interface FetchResult {
        data class Ok(val json: JSONObject) : FetchResult
        data object NotFound : FetchResult
        data class HttpError(val code: Int) : FetchResult
        data object BadData : FetchResult
        data object NetworkFail : FetchResult
    }

    /** 发起 4 线程下载（状态须为 Available；Failed 重试也走这里，前提是已检查到新版）。
     *  下载中重复触发直接忽略（防连点排队整包重下） */
    fun download(context: Context) {
        if (_state.value is UpdateState.Downloading) return
        val appContext = context.applicationContext
        scope.launch { doDownload(appContext) }
    }

    /** 取消下载：置标志位，分段线程在缓冲粒度内退出，临时文件全清，状态回到 Available */
    fun cancelDownload() {
        cancelRequested = true
    }

    /** UI 侧权限申请被拒时调用：落到 Failed 态给用户可见反馈（仅 API 26~28 路径可达） */
    fun notifyStoragePermissionDenied() {
        _state.value = UpdateState.Failed(
            "已拒绝存储权限，请在系统设置中授予后重试", needsStoragePermission = true,
        )
    }

    private suspend fun doDownload(context: Context) {
        runLock.withLock {
            // 锁内复查：download() 的锁外「下载中忽略」检查与 Downloading 置位之间有时间窗，
            // 连点两次会让第二次排队整包重下（2026-09-06 修复）
            if (_state.value is UpdateState.Downloading || _state.value is UpdateState.Downloaded) return
            // 快照必须在锁内读取：与 force check() 的 latest* 写入互斥，防撕裂（🔴 审查修复）
            val url = latestUrl ?: return
            val version = latestVersion ?: return
            val name = latestName ?: return
            val total = latestSize
            val digestExpected = latestDigest
            if (total <= 0) {
                _state.value = UpdateState.Failed("安装包大小未知，无法分段下载")
                return
            }
            // API 26~28：写公共下载目录需要运行时存储权限（29+ 走 MediaStore 免权限）
            if (Build.VERSION.SDK_INT <= 28 && ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                _state.value = UpdateState.Failed("需要存储权限才能保存安装包", needsStoragePermission = true)
                return
            }
            val dir = File(context.cacheDir, "update").apply { mkdirs() }
            val free = StatFs(dir.absolutePath).availableBytes
            if (free < (total * 1.2).toLong()) {
                _state.value = UpdateState.Failed("存储空间不足（需要约 ${total / 1048576} MB）")
                return
            }
            val parts = (0 until THREADS).map { File(dir, "update.$version.part$it") }
            cancelRequested = false

            // 直连下载；整体失败且非用户取消 → 代理兜底重试一轮
            var ok = downloadAllSegments(client, url, total, parts)
            if (!ok && !cancelRequested && isProxyAlive()) {
                parts.forEach { it.delete() }
                ok = downloadAllSegments(proxyClient, url, total, parts)
            }
            when {
                cancelRequested -> {
                    deleteParts(parts)
                    _state.value = UpdateState.Available(version, total, null)
                    return
                }
                !ok -> {
                    deleteParts(parts)
                    _state.value = UpdateState.Failed("下载失败，请检查网络后重试")
                    return
                }
            }

            // 合并 + 校验 + 写入系统下载目录
            mergeAndStore(context, parts, version, name, total, digestExpected)
            deleteParts(parts)
        }
    }

    /** 4 段 Range 并行下载；返回是否全部成功（用户取消也算失败，由 cancelRequested 区分）。
     *  用 coroutineScope 结构化并发：异常/取消会级联取消全部兄弟分段，
     *  杜绝孤儿任务与下一轮下载并发写同一批 .part 文件（🔴 审查修复） */
    private suspend fun downloadAllSegments(
        http: OkHttpClient, url: String, total: Long, parts: List<File>,
    ): Boolean = coroutineScope {
        val downloaded = Array(THREADS) { AtomicLong(0) }
        val lastEmit = AtomicLong(0)
        _state.value = UpdateState.Downloading(0f, 0, total)
        val jobs = parts.mapIndexed { i, part ->
            async {
                downloadSegment(http, url, total, i, part, downloaded[i]) {
                    emitProgress(downloaded, total, lastEmit)
                }
            }
        }
        val ok = jobs.awaitAll().all { it }
        if (ok) {
            // 节流会丢掉最后一次上报，合并前补齐 100%（合并本身有耗时，进度条不应卡在 99%）
            _state.value = UpdateState.Downloading(1f, total, total)
        }
        ok
    }

    private fun downloadSegment(
        http: OkHttpClient, url: String, total: Long, index: Int,
        partFile: File, downloaded: AtomicLong, onProgress: () -> Unit,
    ): Boolean {
        val chunk = (total + THREADS - 1) / THREADS
        val start = index * chunk
        if (start >= total) { // 极小文件容错：空段
            partFile.writeBytes(ByteArray(0))
            return true
        }
        val end = minOf((index + 1) * chunk, total) - 1
        val req = Request.Builder()
            .url(url)
            .header("Range", "bytes=$start-$end")
            .header("User-Agent", "toolboxmobile-updater")
            .build()
        return try {
            partFile.delete()
            http.newCall(req).execute().use { resp ->
                // 必须 206：服务器忽略 Range 回 200 会返回全量，提前拒绝（短读校验兜不住超长浪费）
                if (resp.code != 206) return false
                val body = resp.body ?: return false
                partFile.outputStream().buffered().use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(BUFFER)
                        while (true) {
                            if (cancelRequested) return false
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            downloaded.addAndGet(n.toLong())
                            onProgress()
                        }
                    }
                }
                downloaded.get() == end - start + 1 // 短读/超长都判失败
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun emitProgress(downloaded: Array<AtomicLong>, total: Long, lastEmit: AtomicLong) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastEmit.get() < PROGRESS_INTERVAL_MS) return
        lastEmit.set(now)
        val done = downloaded.sumOf { it.get() }
        _state.value = UpdateState.Downloading(
            (done.toFloat() / total).coerceIn(0f, 1f), done, total,
        )
    }

    /** 合并分段 → 同遍 SHA-256 → 写入系统下载目录；成功置 Downloaded，失败清理并置 Failed */
    private fun mergeAndStore(
        context: Context, parts: List<File>,
        version: String, name: String, total: Long, digestExpected: String?,
    ) {
        var outUri: Uri? = null
        var outFile: File? = null
        try {
            val sink: OutputStream
            if (Build.VERSION.SDK_INT >= 29) {
                // 先清同名残留：损坏/半截的同名文件不删，insert 会被系统自动改名成 "(1).apk"，
                // 而查重按原名精确匹配永远命中旧文件 → 垃圾累积（🟡 审查修复）
                deleteStaleDownloadEntries(context, name)
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, APK_MIME)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(
                    MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values,
                ) ?: throw IllegalStateException("创建下载条目失败")
                outUri = uri
                sink = resolver.openOutputStream(uri) ?: throw IllegalStateException("打开下载条目失败")
            } else {
                val file = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), name,
                )
                if (file.exists()) file.delete()
                outFile = file
                sink = file.outputStream()
            }

            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            sink.buffered().use { out ->
                val buf = ByteArray(BUFFER)
                for (part in parts) {
                    part.inputStream().use { input ->
                        while (true) {
                            // 缓冲粒度内响应取消（🟡：只在分段边界查会导致最后一段期间取消无效仍落盘）
                            if (cancelRequested) throw InterruptedException("cancelled")
                            val n = input.read(buf)
                            if (n < 0) break
                            digest.update(buf, 0, n)
                            out.write(buf, 0, n)
                            written += n
                        }
                    }
                }
            }
            if (written != total) throw IllegalStateException("合并大小不符")
            if (digestExpected != null) {
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(digestExpected, ignoreCase = true)) {
                    throw IllegalStateException("文件校验失败")
                }
            }
            // 收尾：MediaStore 条目转正；状态置就绪
            if (Build.VERSION.SDK_INT >= 29 && outUri != null) {
                val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                context.contentResolver.update(outUri!!, done, null, null)
            }
            val finalUri = outUri ?: FileProvider.getUriForFile(
                context, "${context.packageName}.updatefiles", outFile!!,
            )
            _state.value = UpdateState.Downloaded(version, name, finalUri)
        } catch (e: InterruptedException) {
            // 合并期取消：删未完成的目标文件
            cleanupOutput(context, outUri, outFile)
            _state.value = UpdateState.Available(version, total, null)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            cleanupOutput(context, outUri, outFile)
            _state.value = UpdateState.Failed(e.message ?: "保存安装包失败")
        }
    }

    /** 删除系统下载目录里所有同名条目（下载前调用；此刻同名同大小的合法文件已被查重拦下，剩的都是残留） */
    private fun deleteStaleDownloadEntries(context: Context, name: String) {
        try {
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            context.contentResolver.query(
                collection, arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME} = ?", arrayOf(name), null,
            )?.use { c ->
                while (c.moveToNext()) {
                    context.contentResolver.delete(
                        ContentUris.withAppendedId(collection, c.getLong(0)), null, null,
                    )
                }
            }
        } catch (_: Exception) { }
    }

    private fun cleanupOutput(context: Context, uri: Uri?, file: File?) {
        try {
            if (uri != null) context.contentResolver.delete(uri, null, null)
        } catch (_: Exception) { }
        try {
            file?.delete()
        } catch (_: Exception) { }
    }

    // ---------------- 查询与安装 ----------------

    /** 系统下载目录是否已有同名同大小的安装包；有则返回可用于安装的 Uri。
     *  排除 IS_PENDING=1（进程崩溃泄漏的未完成条目，安装器读不到，不能误判可安装） */
    fun findDownloadedApk(context: Context, name: String, expectedSize: Long): Uri? {
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                context.contentResolver.query(
                    collection,
                    arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.SIZE),
                    "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.IS_PENDING} = 0",
                    arrayOf(name), null,
                )?.use { c ->
                    while (c.moveToNext()) {
                        val size = c.getLong(1)
                        if (expectedSize > 0 && size != expectedSize) continue
                        return ContentUris.withAppendedId(collection, c.getLong(0))
                    }
                    null
                }
            } else {
                val file = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), name,
                )
                if (file.exists() && (expectedSize <= 0 || file.length() == expectedSize)) {
                    FileProvider.getUriForFile(context, "${context.packageName}.updatefiles", file)
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 拉起系统安装器（content Uri + 读授权；安装本身由用户确认） */
    fun installIntent(uri: Uri): Intent =
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /** 是否已获准安装未知来源应用（API 26+ 的 REQUEST_INSTALL_PACKAGES 运行时开关） */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    /** 跳「允许安装未知应用」设置页（canInstall=false 时的主动引导，HyperOS 上入口很深） */
    fun unknownSourcesSettingsIntent(context: Context): Intent =
        Intent(
            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** 检查/启动时顺手清理上次残留的临时分段 */
    private fun cleanupParts(context: Context) {
        try {
            File(context.cacheDir, "update").listFiles()
                ?.filter { it.name.contains(".part") }
                ?.forEach { it.delete() }
        } catch (_: Exception) { }
    }

    private fun deleteParts(parts: List<File>) {
        parts.forEach {
            try { it.delete() } catch (_: Exception) { }
        }
    }

    private fun clearLatest() {
        latestVersion = null
        latestUrl = null
        latestName = null
        latestSize = -1
        latestDigest = null
    }

    /** 127.0.0.1:7890 是否可连通（代理兜底前置条件，仿 dsh-app） */
    private suspend fun isProxyAlive(): Boolean = withContext(Dispatchers.IO) {
        try {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", 7890), 1500) }
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 数值 semver 比较：a<b → 负数；解析失败返回 null（判检查失败，不误报） */
    fun compareVersions(a: String, b: String): Int? {
        val pa = a.split('.').map { it.toIntOrNull() ?: return null }
        val pb = b.split('.').map { it.toIntOrNull() ?: return null }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        return 0
    }
}
