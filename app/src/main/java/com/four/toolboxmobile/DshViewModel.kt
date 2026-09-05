package com.four.toolboxmobile

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** DSH 远程工具的界面状态机 */
sealed interface DshUiState {
    /** 未绑定：展示扫码 / 手动输入两个绑定入口 */
    data object Unbound : DshUiState

    /** 正在寻找入口：stage 为当前阶段文案，扫描阶段带进度（旧 dsh-app 协议遗留态，插件协议不再产生） */
    data class Searching(val stage: String, val scanned: Int, val total: Int) : DshUiState

    /** 已定位入口：WebView 加载该地址（插件协议：配对链接或设备书签） */
    data class Connected(val url: String, val host: String, val port: Int) : DshUiState {
        companion object {
            /** 从完整 URL 拆解展示用 host/port */
            fun fromUrl(url: String): Connected {
                val uri = Uri.parse(url)
                val port = when {
                    uri.port > 0 -> uri.port
                    uri.scheme == "https" -> 443
                    else -> 80
                }
                return Connected(url, uri.host.orEmpty(), port)
            }
        }
    }

    data class Error(val message: String, val keyInvalid: Boolean = false) : DshUiState
}

/**
 * DSH 远程工具 ViewModel（Activity 级）：连接状态常驻内存，工具页开关不丢状态。
 *
 * 插件协议（2026-09-06 切换，dsh-remote-web-ui 跑在 harness 进程内，自己解决随机 token）：
 * 1. 有设备书签（/pair-app?device=…）→ 直接进 GUI——设备会话服务端持久化，
 *    纯 HTTP 局域网下这是唯一能绕开 harness 401 的重开路径
 * 2. 只有配对链接 → 加载它重走配对链路（令牌在有效期内可重复配对；
 *    已配对设备打开死链也会被服务端 303 送回 /pair-app，天然容错）
 * 3. 都没有 → 绑定页
 * 不再有网段扫描与门禁探测：配对链接自带完整入口，探测是旧 dsh-app 协议的找回手段。
 */
class DshViewModel(app: Application) : AndroidViewModel(app) {
    private val store = DshStore(app)

    private val _state = MutableStateFlow<DshUiState>(DshUiState.Unbound)
    val state: StateFlow<DshUiState> = _state

    /** 手动输入解析失败的一次性提示 */
    private val _bindError = MutableStateFlow<String?>(null)
    val bindError: StateFlow<String?> = _bindError

    /** 工具页打开时调用：设备书签 → 直进；配对链接 → 重走配对；都无 → 绑定页 */
    fun start() {
        if (_state.value is DshUiState.Connected) return
        val deviceUrl = store.loadDeviceUrl()
        val pairingUrl = store.loadPairingUrl()
        when {
            deviceUrl != null -> _state.value = DshUiState.Connected.fromUrl(deviceUrl)
            pairingUrl != null -> _state.value = DshUiState.Connected.fromUrl(pairingUrl)
            else -> _state.value = DshUiState.Unbound
        }
    }

    /** 扫码 / 手动输入后的绑定入口：解析成功即保存并加载配对链接 */
    fun bind(input: String) {
        when (val r = parseBindingInput(input)) {
            is BindingParse.Fail -> _bindError.value = r.message
            is BindingParse.Pairing -> {
                _bindError.value = null
                store.savePairingUrl(r.url)
                _state.value = DshUiState.Connected.fromUrl(r.url)
            }
        }
    }

    /** WebView 配对链路跳转到 /pair-app?device=… 时捕获设备书签（DshWebView 回调） */
    fun onDeviceUrlCaptured(url: String) {
        store.saveDeviceUrl(url)
    }

    /**
     * 设备书签失效（服务端会话被清，/pair-app 返回 200 失效页——不是 404，由 DshWebView
     * 检测标题特征回调）：清掉死书签，回退配对链接自动重配（令牌有效期内可重复配对）；
     * 配对链接也没了 → 回绑定页（2026-09-06 审查修复）
     */
    fun onBookmarkDead() {
        store.clearDeviceUrl()
        val pairingUrl = store.loadPairingUrl()
        _state.value = if (pairingUrl != null) {
            DshUiState.Connected.fromUrl(pairingUrl)
        } else {
            DshUiState.Unbound
        }
    }

    /** 重新配对：清掉旧绑定（含设备书签），回绑定页 */
    fun rebind() {
        _bindError.value = null
        store.clear()
        _state.value = DshUiState.Unbound
    }

    /** 设置页一键清除绑定：同 rebind */
    fun clearBinding() = rebind()

    fun retry() {
        _state.value = DshUiState.Unbound
        start()
    }

    /* ===== 旧 dsh-app 协议（2026-09-06 起停用，保留备查）=====
     *
     * 连接策略（基于门禁探测，harness 随机 token 后 dsh-app 共享失效）：
     * 1. 有绑定 → 先探上次成功的入口（probeDsh 一次请求，三态：KeyOk / KeyMismatch / Miss）
     * 2. 入口不可达 → LanScanner 遍历本机 /24 网段的绑定端口（3081），再对候选做门禁探测
     * 3. 探测结论区分「不是 DSH」「密钥不匹配（提示重新绑定）」「密钥正确」
     * 4. 成功 → store.saveHost(okHost) 记住入口，Connected(url = "http://host:port/?key=…")
     *
     * private var job: Job? = null
     * private fun connect(binding: DshBinding) { …probeDsh / scanSubnet / probeCandidates… }
     * private fun connected(host: String, binding: DshBinding) =
     *     DshUiState.Connected(url = binding.urlFor(host), host = host, port = binding.port)
    ===== 旧协议结束 ===== */
}
