package com.four.toolboxmobile

import android.app.Application
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/** 左页连接状态机 */
sealed interface ConnUiState {
    /** 扫描中：实时进度 + 已发现设备（可随时点选） */
    data class Scanning(
        val scanned: Int,
        val total: Int,
        val prefix: String?,
        val found: List<String>,
    ) : ConnUiState

    /** 扫描结束：展示最终设备列表（可为空，提示重扫） */
    data class DevicesFound(val found: List<String>) : ConnUiState

    /** 正在连接指定设备（探测 /api/status） */
    data class Connecting(val ip: String) : ConnUiState

    /** 设备要求认证：弹密钥输入（[error] 非空时显示上次失败原因） */
    data class AuthRequired(val ip: String, val error: String? = null) : ConnUiState

    /** 已连接：轮询系统状态；[reachable] 为 false 表示最近一次轮询失败、重试中 */
    data class Connected(val ip: String, val status: SystemStatus?, val reachable: Boolean) : ConnUiState

    /** 致命错误（拿不到本机 IP / 连接彻底断开等），可重新扫描 */
    data class Error(val message: String) : ConnUiState
}

/** 顶部一次性提示（对齐 HTML 面板 flash：4s 后褪色消失） */
data class Flash(val text: String, val ok: Boolean, val id: Long)

/** 待确认的敏感操作（对齐 HTML 自绘二次确认弹窗） */
data class ConfirmRequest(
    val title: String,
    val text: String,
    val danger: Boolean = true,
    val action: () -> Unit,
)

/** 上传项状态 */
data class UploadItem(
    val id: Long,
    val name: String,
    val sizeText: String,
    val percent: Int,
    val done: Boolean = false,
    val error: String? = null,
)

/**
 * 全局远程连接 ViewModel（Activity 级单例）。
 *
 * 核心设计：连接与轮询的生命周期 **不跟随页面**——
 * App 启动即扫描，连接后持续轮询（状态 1.2s / 日志与设备 5s / 共享清单 3s，对齐 HTML 节奏），
 * 状态常驻内存，左右滑动切页是纯 Compose 重组，零加载。
 */
class RemoteViewModel(app: Application) : AndroidViewModel(app) {

    private val client = ToolboxClient()

    private val _uiState = MutableStateFlow<ConnUiState>(ConnUiState.Scanning(0, 254, null, emptyList()))
    val uiState: StateFlow<ConnUiState> = _uiState.asStateFlow()

    // ===== 已连接面板的附加数据 =====
    private val _events = MutableStateFlow<List<EventItem>>(emptyList())
    val events: StateFlow<List<EventItem>> = _events.asStateFlow()

    private val _devices = MutableStateFlow<List<DeviceEntry>>(emptyList())
    val devices: StateFlow<List<DeviceEntry>> = _devices.asStateFlow()

    private val _shares = MutableStateFlow<List<ShareItem>?>(null) // null = 加载中
    val shares: StateFlow<List<ShareItem>?> = _shares.asStateFlow()

    private val _uploads = MutableStateFlow<List<UploadItem>>(emptyList())
    val uploads: StateFlow<List<UploadItem>> = _uploads.asStateFlow()

    private val _flash = MutableStateFlow<Flash?>(null)
    val flash: StateFlow<Flash?> = _flash.asStateFlow()

    private val _confirm = MutableStateFlow<ConfirmRequest?>(null)
    val confirm: StateFlow<ConfirmRequest?> = _confirm.asStateFlow()

    private var scanJob: Job? = null
    private var pollJob: Job? = null
    private var lastProgressEmit = 0L
    private val flashSeq = AtomicLong(0)
    private val uploadSeq = AtomicLong(0)

    init {
        startScan()
    }

    // ==================== 扫描与连接 ====================

    /** 启动（或重新启动）一轮全网段扫描 */
    fun startScan() {
        scanJob?.cancel()
        pollJob?.cancel()
        lastProgressEmit = 0L
        _uiState.value = ConnUiState.Scanning(0, 254, null, emptyList())
        scanJob = viewModelScope.launch {
            val prefix = LanScanner.localIpv4Prefix()
            if (prefix == null) {
                _uiState.value = ConnUiState.Error("未获取到本机 IPv4 地址，请检查网络连接")
                return@launch
            }
            val found = LanScanner.scanSubnet(prefix) { scanned, total, f ->
                // 节流：254 次探测回调若全部直通 StateFlow，会在 1 秒内触发 254 次整页重组，
                // 此时滑动必掉帧。限 80ms 一次（最后一次必发），重组降到约 12 次。
                val now = System.currentTimeMillis()
                if (scanned < total && now - lastProgressEmit < 80) return@scanSubnet
                lastProgressEmit = now
                // 回调发生在 IO 线程，StateFlow 赋值线程安全
                val cur = _uiState.value
                if (cur is ConnUiState.Scanning) {
                    _uiState.value = cur.copy(scanned = scanned, total = total, prefix = prefix, found = f)
                }
            }
            // 扫描结束时用户可能已点选设备进入其他状态，仅当仍处于 Scanning 才收尾
            if (_uiState.value is ConnUiState.Scanning) {
                _uiState.value = ConnUiState.DevicesFound(found)
            }
        }
    }

    /** 点选设备：先探测 /api/status，200 直连，401 转密钥认证 */
    fun connect(ip: String) {
        scanJob?.cancel()
        pollJob?.cancel()
        _uiState.value = ConnUiState.Connecting(ip)
        viewModelScope.launch {
            when (val r = client.fetchStatus(ip)) {
                is ApiResult.Ok -> onConnected(ip, r.status)
                is ApiResult.Unauthorized -> _uiState.value = ConnUiState.AuthRequired(ip)
                is ApiResult.Failure -> _uiState.value =
                    ConnUiState.Error("无法连接 $ip:${LanScanner.DEFAULT_PORT}（${r.message}）")
            }
        }
    }

    /** 密钥登录 */
    fun login(ip: String, token: String) {
        if (token.isBlank()) {
            _uiState.value = ConnUiState.AuthRequired(ip, "请输入密钥")
            return
        }
        viewModelScope.launch {
            val ok = client.auth(ip, token.trim())
            if (ok) onConnected(ip, null)
            else _uiState.value = ConnUiState.AuthRequired(ip, "密钥错误或认证失败")
        }
    }

    /** 断开连接：清会话，回到重新扫描 */
    fun disconnect() {
        pollJob?.cancel()
        client.clearSession()
        startScan()
    }

    private fun onConnected(ip: String, status: SystemStatus?) {
        _uiState.value = ConnUiState.Connected(ip, status, true)
        _events.value = emptyList()
        _devices.value = emptyList()
        _shares.value = null
        startPolling(ip)
    }

    // ==================== 轮询（对齐 HTML 节奏） ====================

    private fun startPolling(ip: String) {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            var consecutiveFails = 0
            var tick = 0
            while (true) {
                val cur = _uiState.value
                if (cur !is ConnUiState.Connected) break // 离开已连接态即停止全部轮询
                delay(1200)
                tick++

                when (val r = client.fetchStatus(ip)) {
                    is ApiResult.Ok -> {
                        consecutiveFails = 0
                        _uiState.value = ConnUiState.Connected(ip, r.status, true)
                    }
                    is ApiResult.Unauthorized -> {
                        _uiState.value = ConnUiState.AuthRequired(ip, "登录状态已失效，请重新输入密钥")
                        break
                    }
                    is ApiResult.Failure -> {
                        consecutiveFails++
                        val c = _uiState.value
                        if (c is ConnUiState.Connected) _uiState.value = c.copy(reachable = false)
                        if (consecutiveFails >= 4) {
                            _uiState.value =
                                ConnUiState.Error("与 $ip 的连接已断开，请确认 Toolbox 仍在运行")
                            break
                        }
                    }
                }

                if (tick % 3 == 0) refreshShares(ip)   // ≈3.6s
                if (tick % 4 == 0) { refreshEvents(ip); refreshDevices(ip) } // ≈4.8s
            }
        }
    }

    private suspend fun refreshEvents(ip: String) {
        when (val r = client.fetchEvents(ip)) {
            is CallResult.Ok -> _events.value = r.value
            is CallResult.Unauthorized -> onSessionExpired(ip)
            is CallResult.Fail -> Unit // 失败不打断轮询（对齐 HTML 行为）
        }
    }

    private suspend fun refreshDevices(ip: String) {
        when (val r = client.fetchDevices(ip)) {
            is CallResult.Ok -> _devices.value = r.value
            is CallResult.Unauthorized -> onSessionExpired(ip)
            is CallResult.Fail -> Unit
        }
    }

    private suspend fun refreshShares(ip: String) {
        when (val r = client.fetchShares(ip)) {
            is CallResult.Ok -> _shares.value = r.value
            is CallResult.Unauthorized -> onSessionExpired(ip)
            is CallResult.Fail -> Unit
        }
    }

    private fun onSessionExpired(ip: String) {
        if (_uiState.value is ConnUiState.Connected) {
            _uiState.value = ConnUiState.AuthRequired(ip, "登录状态已失效，请重新输入密钥")
        }
    }

    // ==================== 指令操作 ====================

    /** 低风险一键指令（快捷操作/取消关机）：直发 */
    fun quick(command: String) {
        val ip = connectedIp() ?: return
        viewModelScope.launch {
            when (val r = client.sendCommand(ip, command)) {
                is CallResult.Ok -> { flash("✅ $command 已执行", ok = true); refreshEvents(ip) }
                is CallResult.Fail -> flash("❌ ${r.message}", ok = false)
                is CallResult.Unauthorized -> onSessionExpired(ip)
            }
        }
    }

    /** 危险指令：先弹自绘确认（对齐 HTML 行为），确认后恒带 confirm:true */
    fun requestShutdown(delaySeconds: Int) {
        askConfirm(
            title = "确认执行「关机」？",
            text = if (delaySeconds > 0) "将在 $delaySeconds 秒后执行定时关机。" else "此操作将直接影响电脑，请确认。",
        ) {
            val args = JSONObject().put("delaySeconds", delaySeconds).put("confirm", true)
            sendConfirmed("shutdown", args)
        }
    }

    fun requestRestart() {
        askConfirm(title = "确认执行「重启电脑」？", text = "此操作将直接影响电脑，请确认。") {
            sendConfirmed("restart", JSONObject().put("confirm", true))
        }
    }

    fun customShutdown(minutesText: String) {
        val minutes = minutesText.toIntOrNull()
        if (minutes == null || minutes < 1 || minutes > 1440) {
            flash("❌ 请输入 1~1440 分钟", ok = false)
            return
        }
        requestShutdown(minutes * 60)
    }

    fun requestShutdownApp() {
        askConfirm(
            title = "确认关闭 Toolbox？",
            text = "关闭后本控制页将无法访问，需在电脑上重新启动 Toolbox。",
        ) {
            val ip = connectedIp() ?: return@askConfirm
            viewModelScope.launch {
                when (val r = client.appShutdown(ip)) {
                    is CallResult.Ok -> flash("✅ 已请求关闭 Toolbox，稍后连接将断开", ok = true)
                    is CallResult.Fail -> flash("❌ ${r.message}", ok = false)
                    is CallResult.Unauthorized -> onSessionExpired(ip)
                }
            }
        }
    }

    private fun sendConfirmed(command: String, args: JSONObject) {
        val ip = connectedIp() ?: return
        viewModelScope.launch {
            when (val r = client.sendCommand(ip, command, args)) {
                is CallResult.Ok -> { flash("✅ $command 已执行", ok = true); refreshEvents(ip) }
                is CallResult.Fail -> flash("❌ ${r.message}", ok = false)
                is CallResult.Unauthorized -> onSessionExpired(ip)
            }
        }
    }

    // ==================== 设备管理 ====================

    fun kick(ip: String) {
        val self = connectedIp() ?: return
        viewModelScope.launch {
            when (val r = client.kickDevice(self, ip)) {
                is CallResult.Ok -> { flash("✅ 已处理 $ip", ok = true); refreshDevices(self) }
                is CallResult.Fail -> flash("❌ ${r.message}", ok = false)
                is CallResult.Unauthorized -> onSessionExpired(self)
            }
        }
    }

    // ==================== 文件传输 ====================

    /** 上传所选文件到电脑（逐个进行，进度实时更新） */
    fun uploadFiles(uris: List<Uri>) {
        val ip = connectedIp() ?: return
        val resolver = getApplication<Application>().contentResolver
        for (uri in uris) {
            val (name, size) = queryFileInfo(uri)
            val id = uploadSeq.incrementAndGet()
            updateUpload(UploadItem(id, name, formatSize(size), 0))
            viewModelScope.launch {
                val result = client.upload(
                    ip = ip,
                    fileName = name,
                    contentLength = size,
                    openStream = { resolver.openInputStream(uri) },
                    onProgress = { p -> patchUpload(id) { it.copy(percent = p) } },
                )
                when (result) {
                    is CallResult.Ok -> patchUpload(id) { it.copy(percent = 100, done = true) }
                    is CallResult.Fail -> patchUpload(id) { it.copy(error = result.message) }
                    is CallResult.Unauthorized -> {
                        patchUpload(id) { it.copy(error = "登录失效") }
                        onSessionExpired(ip)
                    }
                }
            }
        }
    }

    /** 下载电脑共享的文件（29+ 存系统下载目录，26-28 存应用私有下载目录） */
    fun download(share: ShareItem) {
        val ip = connectedIp() ?: return
        viewModelScope.launch {
            when (val r = client.openDownload(ip, share.id)) {
                is CallResult.Ok -> {
                    try {
                        val path = saveDownload(r.value.fileName ?: share.name, r.value)
                        flash("✅ 已下载：$path", ok = true)
                    } catch (e: Exception) {
                        flash("❌ 下载失败：${e.message}", ok = false)
                    }
                }
                is CallResult.Fail -> flash("❌ 下载失败：${r.message}", ok = false)
                is CallResult.Unauthorized -> onSessionExpired(ip)
            }
        }
    }

    private fun saveDownload(name: String, dl: DownloadStream): String {
        val app = getApplication<Application>()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ToolboxMobile")
            }
            val uri = app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw java.io.IOException("无法创建下载文件")
            app.contentResolver.openOutputStream(uri)?.use { out ->
                dl.body.byteStream().use { it.copyTo(out) }
            } ?: throw java.io.IOException("无法写入下载文件")
            "下载/ToolboxMobile/$name"
        } else {
            val dir = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: throw java.io.IOException("存储不可用")
            val file = File(dir, name)
            FileOutputStream(file).use { out -> dl.body.byteStream().use { it.copyTo(out) } }
            file.absolutePath
        }
    }

    private fun queryFileInfo(uri: Uri): Pair<String, Long> {
        var name = "file"
        var size = -1L
        runCatching {
            getApplication<Application>().contentResolver
                .query(uri, null, null, null, null)?.use { c ->
                    val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (c.moveToFirst()) {
                        if (nameIdx >= 0) c.getString(nameIdx)?.let { name = it }
                        if (sizeIdx >= 0 && !c.isNull(sizeIdx)) size = c.getLong(sizeIdx)
                    }
                }
        }
        if (name == "file") name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        return name to size
    }

    // ==================== 确认弹窗 / 提示 ====================

    private fun askConfirm(title: String, text: String, action: () -> Unit) {
        _confirm.value = ConfirmRequest(title, text, action = action)
    }

    fun confirmNow() {
        val req = _confirm.value ?: return
        _confirm.value = null
        req.action()
    }

    fun dismissConfirm() {
        _confirm.value = null
    }

    private fun flash(text: String, ok: Boolean) {
        val id = flashSeq.incrementAndGet()
        _flash.value = Flash(text, ok, id)
        viewModelScope.launch {
            delay(4000)
            if (_flash.value?.id == id) _flash.value = null
        }
    }

    // ==================== 工具 ====================

    private fun connectedIp(): String? =
        (_uiState.value as? ConnUiState.Connected)?.ip

    private fun updateUpload(item: UploadItem) {
        _uploads.value = _uploads.value + item
    }

    private fun patchUpload(id: Long, patch: (UploadItem) -> UploadItem) {
        _uploads.value = _uploads.value.map { if (it.id == id) patch(it) else it }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 0 -> ""
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes.toDouble() / (1L shl 30))
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes.toDouble() / (1L shl 20))
        bytes >= 1024 -> "%.1f KB".format(bytes.toDouble() / 1024)
        else -> "$bytes B"
    }
}
