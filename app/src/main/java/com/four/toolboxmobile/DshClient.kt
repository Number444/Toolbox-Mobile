package com.four.toolboxmobile

import android.content.Context
import android.net.Uri

/**
 * DSH 绑定信息（dsh-remote-web-ui 插件协议，2026-09-06 切换）。
 *
 * 背景：旧方案走 dsh-app 的局域网共享代理（?key= 门禁），因 DeepSeek 把 harness 启动改为
 * 每次随机 token 而整体失效归档。新方案里插件跑在 harness 进程内部，自己经官方接缝
 * 兑换启动令牌，手机侧完全不接触随机 token，只需要两个 URL：
 * - pairingUrl：扫码得到的一次性配对链接（/pair-accept?pair=…），WebView 直接加载即完成配对
 * - deviceUrl：配对完成后 303 跳转的 /pair-app?device=…（设备会话凭据，服务端默认持久化）。
 *   纯 HTTP 局域网下导航 / 会撞 harness 401，必须用它当「重开书签」
 */

/** DSH 绑定的持久化（SharedPreferences "dsh"）：配对链接 + 设备书签 */
class DshStore(context: Context) {
    private val prefs = context.getSharedPreferences("dsh", Context.MODE_PRIVATE)

    /** 配对链接（扫码/手动输入原文，配对失败重试时用） */
    fun loadPairingUrl(): String? =
        prefs.getString("pairing_url", null)?.takeIf { it.isNotBlank() }

    fun savePairingUrl(url: String) = prefs.edit().putString("pairing_url", url).apply()

    /** 设备书签（配对完成捕获的 /pair-app?device=…）：之后打开工具直接进 GUI */
    fun loadDeviceUrl(): String? =
        prefs.getString("device_url", null)?.takeIf { it.isNotBlank() }

    fun saveDeviceUrl(url: String) = prefs.edit().putString("device_url", url).apply()

    /** 仅清除设备书签（书签失效回退配对链接时用，保留配对链接可自动重配） */
    fun clearDeviceUrl() = prefs.edit().remove("device_url").apply()

    /** 一键清除绑定（设置页用）：配对链接与设备书签全清，下次打开工具回绑定页 */
    fun clear() = prefs.edit().clear().apply()

    companion object {
        const val DEFAULT_PORT = 3080 // dsh web 默认端口（插件直连 harness 自身服务，不再走 dsh-app 3081 代理）
    }

    /* ===== 旧 dsh-app 协议（2026-09-06 起停用，保留备查）=====
    fun load(): DshBinding? {
        val key = prefs.getString("key", null)?.takeIf { it.isNotBlank() } ?: return null
        return DshBinding(key, prefs.getString("host", null), prefs.getInt("port", DEFAULT_PORT))
    }

    fun save(binding: DshBinding) = prefs.edit()
        .putString("key", binding.key)
        .putString("host", binding.host)
        .putInt("port", binding.port)
        .apply()

    fun saveHost(host: String) = prefs.edit().putString("host", host).apply()

    companion object {
        const val DEFAULT_PORT = 3081 // 对齐 dsh-app AppSettings.LanSharePort 默认值
    }
    ===== 旧协议结束 ===== */
}

/** 绑定输入的解析结果 */
sealed interface BindingParse {
    /** 插件配对链接：WebView 直接加载走配对链路 */
    data class Pairing(val url: String) : BindingParse
    data class Fail(val message: String) : BindingParse
}

/** 插件前提提醒（未安装 dsh-remote-web-ui 时的统一文案） */
const val DSH_PLUGIN_HINT =
    "电脑端需安装 dsh-remote-web-ui 插件并开启局域网访问：\n" +
        "dsh plugin --profile web add @linxin666/dsh-remote-web-ui@latest"

/**
 * 解析绑定输入：只接受 dsh-remote-web-ui 插件的配对链接——
 * 完整形态 http://192.168.5.12:3080/pair-accept?pair=xxx（二维码内容同格式，
 * https 隧道地址亦可）；pair 参数缺失即非配对链接，提示去插件面板扫码。
 */
fun parseBindingInput(raw: String): BindingParse {
    val input = raw.trim()
    if (input.isEmpty()) return BindingParse.Fail("请输入配对链接")
    val uri = runCatching { Uri.parse(if ("://" in input) input else "http://$input") }.getOrNull()
        ?: return BindingParse.Fail("无法解析该链接")
    val host = uri.host?.takeIf { it.isNotBlank() }
        ?: return BindingParse.Fail("链接中缺少主机地址")
    val pairToken = uri.getQueryParameter("pair")?.takeIf { it.isNotBlank() }
    if (pairToken == null || !(uri.path.orEmpty().startsWith("/pair-accept"))) {
        return BindingParse.Fail(
            "这不是配对链接。请在电脑端 DSH 侧栏点 📱 打开远程访问面板，扫描其中的二维码。\n$DSH_PLUGIN_HINT",
        )
    }
    // 归一化重建：只保留 scheme/host/port + 配对路径与令牌，丢弃多余路径与参数。
    // getQueryParameter 返回的是已解码值，拼回 URL 必须重新编码（令牌含 %26/%23 等会截断）；
    // scheme 白名单防 javascript:/file: 伪协议输入（2026-09-06 审查修复）
    val scheme = uri.scheme?.lowercase()?.takeIf { it == "http" || it == "https" } ?: "http"
    val portPart = if (uri.port > 0) ":${uri.port}" else ""
    return BindingParse.Pairing("$scheme://$host$portPart/pair-accept?pair=${Uri.encode(pairToken)}")
}

/* ===== 旧 dsh-app 协议（2026-09-06 起停用，保留备查）=====
 *
 * 协议对应 dsh-app 的 LanShareProxy（Kestrel + YARP，默认端口 3081）：
 * 无 key / 错 key → 403 特征页（GATE_MARKER = "需要访问密钥"）；正确 ?key= → 302 种 Cookie。
 * 302 也可能是别的服务，故 302 后追加一次无 key 请求验证 403 特征页，双确认。
 * harness 改随机 token 后 dsh-app 共享链路失效，以下代码整体退役。

data class DshBinding(val key: String, val host: String?, val port: Int) {
    fun urlFor(host: String): String = "http://$host:$port/?key=$key"
}

// 旧 parseBindingInput 三分支：完整链接 ?key= / 省略协议链接 / 纯密钥（16+ 位字母数字，
// host 未知走 /24 网段扫描找回，端口默认 3081）。

sealed interface DshProbe {
    data object KeyOk : DshProbe       // 是 dsh-app 且密钥正确（302 跳转）
    data object KeyMismatch : DshProbe // 是 dsh-app 但密钥不匹配（403 特征页）
    data object Miss : DshProbe        // 不可达，或端口上不是 dsh-app 局域网共享
}

// probeDsh(host, port, key)：followRedirects(false)，connect 800ms / read 1500ms；
// isDshGate(host, port)：无 key 请求 403 + 特征页确认；
// probeCandidates(hosts, port, key)：Semaphore(8) 并发探测，返回 (首个 KeyOk 主机, KeyMismatch 数)。
===== 旧协议结束 ===== */
