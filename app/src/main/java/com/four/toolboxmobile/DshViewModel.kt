package com.four.toolboxmobile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** DSH 远程工具的界面状态机 */
sealed interface DshUiState {
    /** 未绑定密钥：展示扫码 / 手动输入两个绑定入口 */
    data object Unbound : DshUiState

    /** 正在寻找入口：stage 为当前阶段文案，扫描阶段带进度 */
    data class Searching(val stage: String, val scanned: Int, val total: Int) : DshUiState

    /** 已定位入口：WebView 加载该地址（门禁种 Cookie 后跳转到 DSH GUI） */
    data class Connected(val url: String, val host: String, val port: Int) : DshUiState

    data class Error(val message: String, val keyInvalid: Boolean = false) : DshUiState
}

/**
 * DSH 远程工具 ViewModel（Activity 级）：连接状态常驻内存，工具页开关不丢连接。
 *
 * 连接策略：
 * 1. 有绑定 → 先探上次成功的入口（一次请求，秒回）
 * 2. 入口不可达 → TCP 遍历本机 /24 网段的绑定端口，再对候选做门禁探测
 * 3. 探测结论区分「不是 DSH」「密钥不匹配（提示重新绑定）」「密钥正确」三态
 */
class DshViewModel(app: Application) : AndroidViewModel(app) {
    private val store = DshStore(app)

    private val _state = MutableStateFlow<DshUiState>(DshUiState.Unbound)
    val state: StateFlow<DshUiState> = _state

    /** 手动输入解析失败的一次性提示 */
    private val _bindError = MutableStateFlow<String?>(null)
    val bindError: StateFlow<String?> = _bindError

    private var job: Job? = null
    private var lastProgressEmit = 0L

    /** 工具页打开时调用：有绑定 → 自动重连；无 → 绑定页 */
    fun start() {
        if (job?.isActive == true) return
        val binding = store.load()
        if (binding == null) {
            _state.value = DshUiState.Unbound
            return
        }
        connect(binding)
    }

    /** 扫码 / 手动输入后的绑定入口：解析成功即保存并发起连接 */
    fun bind(input: String) {
        when (val r = parseBindingInput(input)) {
            is BindingParse.Fail -> _bindError.value = r.message
            is BindingParse.Ok -> {
                _bindError.value = null
                store.save(r.binding)
                connect(r.binding)
            }
        }
    }

    /** 密钥更新后重新绑定：回到绑定页（旧绑定保留，新绑定成功即覆盖） */
    fun rebind() {
        job?.cancel()
        job = null
        _bindError.value = null
        _state.value = DshUiState.Unbound
    }

    fun retry() {
        val binding = store.load()
        if (binding == null) _state.value = DshUiState.Unbound else connect(binding)
    }

    private fun connect(binding: DshBinding) {
        job?.cancel()
        job = viewModelScope.launch {
            // 1. 上次成功的入口优先
            val lastHost = binding.host
            if (lastHost != null) {
                _state.value = DshUiState.Searching("正在连接上次入口 $lastHost…", 0, 1)
                when (probeDsh(lastHost, binding.port, binding.key)) {
                    DshProbe.KeyOk -> {
                        _state.value = connected(lastHost, binding)
                        return@launch
                    }
                    DshProbe.KeyMismatch -> {
                        _state.value = DshUiState.Error(
                            "密钥与 $lastHost 上的 DSH 不匹配（密钥可能已更新），请重新绑定",
                            keyInvalid = true,
                        )
                        return@launch
                    }
                    DshProbe.Miss -> Unit // 入口变迁，转入网段扫描
                }
            }

            // 2. 遍历本机 IPv4 所在 /24 网段找入口（TCP 预筛 → 门禁探测确认）
            // VPN/TUN 会把所有 TCP 连接应答为"已连接"→ 扫描绑定 Wi-Fi 网卡绕过隧道
            LanScanner.preferWifiTransport(getApplication())
            val prefix = LanScanner.localIpv4Prefix()
            if (prefix == null) {
                _state.value = DshUiState.Error("手机未连接到局域网")
                return@launch
            }
            val hosts = LanScanner.scanSubnet(prefix, binding.port) { scanned, total, _ ->
                // 进度回调节流：254 次/秒的回调节流成 ~12fps，避免重组风暴
                val now = System.currentTimeMillis()
                if (now - lastProgressEmit >= 80 || scanned == total) {
                    lastProgressEmit = now
                    _state.value = DshUiState.Searching(
                        "正在扫描局域网（端口 ${binding.port}）…", scanned, total,
                    )
                }
            }
            if (hosts.isEmpty()) {
                _state.value = DshUiState.Error(
                    "局域网内未找到 DSH 入口（端口 ${binding.port}）\n请确认电脑端 dsh-app 已开启「局域网共享」",
                )
                return@launch
            }

            _state.value = DshUiState.Searching("正在验证入口…", 1, 1)
            val (okHost, mismatchCount) = probeCandidates(hosts, binding.port, binding.key)
            when {
                okHost != null -> {
                    store.saveHost(okHost)
                    _state.value = connected(okHost, binding)
                }
                mismatchCount > 0 -> _state.value = DshUiState.Error(
                    "已找到 DSH 入口，但密钥不匹配（密钥可能已更新），请重新绑定",
                    keyInvalid = true,
                )
                else -> _state.value = DshUiState.Error(
                    "端口 ${binding.port} 上的服务不是 DSH\n请在 dsh-app 设置页确认局域网共享端口",
                )
            }
        }
    }

    private fun connected(host: String, binding: DshBinding) =
        DshUiState.Connected(url = binding.urlFor(host), host = host, port = binding.port)
}
