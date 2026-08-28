package com.four.toolboxmobile

import android.content.Context
import android.net.Uri
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * DSH 绑定信息：密钥必填；host 为上次成功的入口（可空，丢了靠网段扫描找回）。
 * 协议对应 dsh-app 的 LanShareProxy（Kestrel + YARP，默认端口 3081）。
 */
data class DshBinding(val key: String, val host: String?, val port: Int) {
    /** 带密钥的完整入口地址：命中门禁后服务端种 Cookie 并 302 到干净地址 */
    fun urlFor(host: String): String = "http://$host:$port/?key=$key"
}

/** DSH 绑定的持久化（SharedPreferences "dsh"）：首次连接记住密钥，密钥更新可重新绑定覆盖 */
class DshStore(context: Context) {
    private val prefs = context.getSharedPreferences("dsh", Context.MODE_PRIVATE)

    fun load(): DshBinding? {
        val key = prefs.getString("key", null)?.takeIf { it.isNotBlank() } ?: return null
        return DshBinding(key, prefs.getString("host", null), prefs.getInt("port", DEFAULT_PORT))
    }

    fun save(binding: DshBinding) = prefs.edit()
        .putString("key", binding.key)
        .putString("host", binding.host)
        .putInt("port", binding.port)
        .apply()

    /** 仅更新入口地址（扫描找回新入口时调用，不动密钥） */
    fun saveHost(host: String) = prefs.edit().putString("host", host).apply()

    companion object {
        const val DEFAULT_PORT = 3081 // 对齐 dsh-app AppSettings.LanSharePort 默认值
    }
}

/** 绑定输入的解析结果 */
sealed interface BindingParse {
    data class Ok(val binding: DshBinding) : BindingParse
    data class Fail(val message: String) : BindingParse
}

/**
 * 解析绑定输入，支持三种形态：
 * - 完整链接（二维码内容同格式）：http://192.168.1.7:3081/?key=xxx
 * - 省略协议的链接：192.168.1.7:3081/?key=xxx
 * - 纯密钥（host 未知 → 走 /24 网段扫描找回，端口按默认 3081）
 */
fun parseBindingInput(raw: String): BindingParse {
    val input = raw.trim()
    if (input.isEmpty()) return BindingParse.Fail("请输入链接或密钥")
    // 纯密钥：dsh-app 的 token 是 32 位十六进制，放宽为 16+ 位字母数字
    if (Regex("^[A-Za-z0-9]{16,}$").matches(input)) {
        return BindingParse.Ok(DshBinding(key = input, host = null, port = DshStore.DEFAULT_PORT))
    }
    val uri = runCatching { Uri.parse(if ("://" in input) input else "http://$input") }.getOrNull()
        ?: return BindingParse.Fail("无法解析该链接")
    val host = uri.host?.takeIf { it.isNotBlank() }
        ?: return BindingParse.Fail("链接中缺少主机地址")
    val key = uri.getQueryParameter("key")?.takeIf { it.isNotBlank() }
        ?: return BindingParse.Fail("链接中缺少 ?key= 密钥参数")
    val port = if (uri.port > 0) uri.port else DshStore.DEFAULT_PORT
    return BindingParse.Ok(DshBinding(key = key, host = host, port = port))
}

/** 单个入口的探测结果 */
sealed interface DshProbe {
    /** 是 dsh-app 且密钥正确（?key= 命中门禁 → 种 Cookie 的 302 跳转） */
    data object KeyOk : DshProbe

    /** 是 dsh-app 但密钥不匹配（门禁 403 特征页） */
    data object KeyMismatch : DshProbe

    /** 不可达，或端口上不是 dsh-app 局域网共享 */
    data object Miss : DshProbe
}

/** dsh-app 门禁 403 页面特征字（见 LanShareProxy.cs token 门禁分支） */
private const val GATE_MARKER = "需要访问密钥"

private val probeClient = OkHttpClient.Builder()
    // 关键：不跟随重定向——302 本身就是「密钥正确」的判定信号
    .followRedirects(false)
    .followSslRedirects(false)
    .connectTimeout(800, TimeUnit.MILLISECONDS)
    .readTimeout(1500, TimeUnit.MILLISECONDS)
    .build()

/**
 * 探测 host:port 是否为 dsh-app 局域网共享、以及 [key] 是否有效。
 * 门禁协议：无 key / 错 key → 403 特征页；正确 ?key= → 302（种 Cookie）。
 * 注意 302 也可能是别的服务，因此 302 后追加一次无 key 请求验证 403 特征页，双确认。
 */
suspend fun probeDsh(host: String, port: Int, key: String): DshProbe = withContext(Dispatchers.IO) {
    val first = runCatching {
        probeClient.newCall(Request.Builder().url("http://$host:$port/?key=$key").build()).execute()
    }.getOrNull() ?: return@withContext DshProbe.Miss

    first.use { resp ->
        when (resp.code) {
            in 300..399 -> {
                val confirmed = runCatching { isDshGate(host, port) }.getOrDefault(false)
                if (confirmed) DshProbe.KeyOk else DshProbe.Miss
            }
            403 -> {
                val isDsh = runCatching { resp.body?.string().orEmpty().contains(GATE_MARKER) }
                    .getOrDefault(false)
                if (isDsh) DshProbe.KeyMismatch else DshProbe.Miss
            }
            else -> DshProbe.Miss
        }
    }
}

/** 无 key 请求：403 + 特征页 → 确认是 dsh-app 门禁 */
private fun isDshGate(host: String, port: Int): Boolean =
    probeClient.newCall(Request.Builder().url("http://$host:$port/").build()).execute().use {
        it.code == 403 && it.body?.string().orEmpty().contains(GATE_MARKER)
    }

/**
 * 对 TCP 可达的候选主机做门禁探测（并发上限 8）。
 * 返回 (首个密钥正确的主机, 密钥不匹配的主机数)。
 */
suspend fun probeCandidates(
    hosts: List<String>,
    port: Int,
    key: String,
): Pair<String?, Int> = coroutineScope {
    val semaphore = Semaphore(8)
    val results = hosts.map { host ->
        async(Dispatchers.IO) { semaphore.withPermit { host to probeDsh(host, port, key) } }
    }.map { it.await() }
    val ok = results.firstOrNull { it.second == DshProbe.KeyOk }?.first
    val mismatch = results.count { it.second == DshProbe.KeyMismatch }
    ok to mismatch
}
