package com.four.toolboxmobile.ui.steamchat

import android.annotation.SuppressLint
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.four.toolboxmobile.ui.components.ToolboxMotion
import com.four.toolboxmobile.ui.dsh.DshOutlineButton
import com.four.toolboxmobile.ui.dsh.DshPrimaryButton
import com.four.toolboxmobile.ui.theme.ToolboxColors
import kotlinx.coroutines.delay

private const val STEAM_CHAT_URL = "https://steamcommunity.com/chat/"

/**
 * Steam Mchat（全屏覆盖层）：WebView 直载 Steam 网页版聊天。
 * 复用 DSH 容器的全部实测经验：
 * - MATCH_PARENT LayoutParams + doOnLayout 拿到非零尺寸后再 loadUrl（防 0 高视口白屏）
 * - imePadding 消费 WindowInsets.ime——聊天输入框不被键盘遮挡，Chromium 自动滚入可视区
 * - 返回键先退网页历史，退无可退才关闭工具
 * - 登录态靠 WebView Cookie 持久化，一次登录长期有效
 * 启动遮罩：全屏遮盖 WebView 冷启动，实时展示加载百分比，首页 onPageFinished 后淡出。
 */
@Composable
fun SteamChatToolScreen(onClose: () -> Unit) {
    var reloadTick by remember { mutableIntStateOf(0) }

    Column(modifier = Modifier.fillMaxSize().background(ToolboxColors.Bg)) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            SteamChatTopBar(onBack = onClose, onReload = { reloadTick++ })
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                SteamChatWebView(reloadTick = reloadTick, onClose = onClose)
            }
        }
    }
}

/** 顶栏：返回 + 标题 + 刷新 */
@Composable
private fun SteamChatTopBar(onBack: () -> Unit, onReload: () -> Unit) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().height(52.dp).padding(start = 4.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = ToolboxColors.Text,
                )
            }
            Text(
                text = "Steam Mchat",
                color = ToolboxColors.Text,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onReload) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = "刷新",
                    tint = ToolboxColors.TextDim,
                )
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(ToolboxColors.Border))
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SteamChatWebView(reloadTick: Int, onClose: () -> Unit) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var pageError by remember { mutableStateOf<String?>(null) }
    // 配置变更（旋转等）重建后恢复网页状态：Bundle 可 saveable，
    // saveState/restoreState 保住当前页与后退栈，不再冷启动重载（2026-09-06 修复）
    val webViewState = rememberSaveable { android.os.Bundle() }
    // 启动遮罩：首页首次加载完成后淡出；顶栏刷新时重新出现
    var maskVisible by remember { mutableStateOf(true) }
    // 加载看门狗状态：每次加载开始 +1（重启 12s 计时）；slowHint = 超时提示可见
    var loadSession by remember { mutableIntStateOf(0) }
    var slowHint by remember { mutableStateOf(false) }

    // 移动化补丁 v7-lite（assets/steamchat/mobile.js）：只隐藏 Steam 顶部横栏。
    // 安全红线：脚本内只改已有元素 inline style，零节点增删（灰屏事故定案）
    val context = LocalContext.current
    val patchJs = remember {
        runCatching {
            context.assets.open("steamchat/mobile.js").bufferedReader().use { it.readText() }
        }.getOrNull()
    }

    /** 注入补丁（幂等，脚本内有 __mchatPatch 守卫） */
    fun injectPatch(view: WebView) {
        val js = patchJs ?: return
        view.evaluateJavascript(js, null)
    }

    BackHandler {
        val wv = webView
        if (wv?.canGoBack() == true) wv.goBack() else onClose()
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, webView) {
        val wv = webView ?: return@DisposableEffect onDispose {}
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> wv.onPause()
                Lifecycle.Event.ON_RESUME -> wv.onResume()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(reloadTick) {
        if (reloadTick > 0) {
            maskVisible = true
            progress = 0
            loadSession++
            webView?.reload()
        }
    }

    // 加载看门狗：不赌 Chromium 错误回调（GFW 静默丢包时可能永不触发），
    // 改为确定性超时——真实页面 12 秒未加载完，遮罩上给代理提示与重试入口
    LaunchedEffect(loadSession) {
        if (loadSession == 0) return@LaunchedEffect
        slowHint = false
        delay(12_000)
        // 读的是 State 的当前值：已完成/已报错则不打扰
        if (maskVisible && pageError == null) slowHint = true
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    // 关键①：显式 MATCH_PARENT LayoutParams——缺失时 vh/dvh 视口单位恒为 0
                    //（真机实测），html/body 高度链坍塌 → 白屏
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    if (com.four.toolboxmobile.BuildConfig.DEBUG) {
                        WebView.setWebContentsDebuggingEnabled(true)
                    }
                    setBackgroundColor(0xFF1C1C1C.toInt()) // 防加载白闪
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        textZoom = 100 // 不跟随系统字体缩放，防撑坏固定布局
                        // Steam 聊天是桌面端页面：允许双指缩放
                        builtInZoomControls = true
                        displayZoomControls = false
                    }
                    this.webViewClient = object : WebViewClient() {
                        override fun onReceivedError(
                            view: WebView,
                            request: WebResourceRequest,
                            error: WebResourceError,
                        ) {
                            if (request.isForMainFrame) {
                                pageError = when (error.errorCode) {
                                    // steamcommunity.com 国内被阻断：连接类错误给明确指引
                                    ERROR_CONNECT, ERROR_TIMEOUT, ERROR_HOST_LOOKUP ->
                                        "无法连接 Steam 社区\n该域名在国内无法直连，请开启代理/VPN 后点下方重试"
                                    else ->
                                        "页面加载失败（错误码 ${error.errorCode}）\n请检查网络后重试"
                                }
                            }
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            // chrome-error:// 是 Chromium 自己的错误页：它的"加载完成"
                            // 不能清掉我们的错误层，否则自绘错误提示永远被它盖住
                            if (!url.startsWith("chrome-error://")) {
                                pageError = null
                                slowHint = false
                                maskVisible = false // 首页加载完成 → 遮罩淡出
                                // 只对 Steam 域注入补丁（含登录跳转后的每次完整加载）
                                if (url.startsWith("https://steamcommunity.com")) {
                                    injectPatch(view)
                                }
                            }
                        }
                    }
                    webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onProgressChanged(view: WebView, newProgress: Int) {
                            progress = newProgress
                        }
                    }
                    // 关键②：等首次非零尺寸布局后再加载
                    var loaded = false
                    doOnLayout {
                        if (!loaded && it.width > 0 && it.height > 0) {
                            loaded = true
                            loadSession++ // 启动加载看门狗
                            // 有存档（配置变更重建）→ 恢复页面与后退栈；否则冷启动加载
                            if (webViewState.isEmpty) loadUrl(STEAM_CHAT_URL)
                            else restoreState(webViewState)
                        }
                    }
                    webView = this
                }
            },
            onRelease = {
                runCatching { it.saveState(webViewState) }
                it.destroy()
                webView = null
            },
            modifier = Modifier.fillMaxSize(),
        )

        pageError?.let { msg ->
            Column(
                modifier = Modifier.fillMaxSize().background(ToolboxColors.Bg).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(text = "⚠️", fontSize = 40.sp)
                Spacer(modifier = Modifier.height(14.dp))
                Text(text = msg, color = ToolboxColors.Text, fontSize = 13.5.sp)
                Spacer(modifier = Modifier.height(24.dp))
                DshPrimaryButton(
                    text = "重新加载",
                    onClick = {
                        pageError = null
                        maskVisible = true
                        progress = 0
                        loadSession++
                        webView?.reload()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // 启动遮罩：盖住 WebView 冷启动阶段，首页加载完成后淡出
        AnimatedVisibility(
            visible = maskVisible && pageError == null,
            enter = fadeIn(tween(ToolboxMotion.ENTER_MS, easing = ToolboxMotion.EASING)),
            exit = fadeOut(tween(ToolboxMotion.EXIT_MS, easing = ToolboxMotion.EASING)),
        ) {
            LoadingMask(
                progress = progress,
                slowHint = slowHint,
                onRetry = {
                    slowHint = false
                    progress = 0
                    loadSession++
                    webView?.reload()
                },
                onKeepWaiting = {
                    // 继续等：收起提示并再给看门狗 12 秒（之后仍不通会再次提示）
                    slowHint = false
                    loadSession++
                },
            )
        }
    }
}

/** 启动加载遮罩：Steam 标识 + 环形指示 + 实时百分比 + 进度条；超时时给代理提示 */
@Composable
private fun LoadingMask(
    progress: Int,
    slowHint: Boolean,
    onRetry: () -> Unit,
    onKeepWaiting: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().background(ToolboxColors.Bg).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = "💬", fontSize = 44.sp)
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = "Steam Mchat",
            color = ToolboxColors.Text,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "正在连接 Steam…",
            color = ToolboxColors.TextDim,
            fontSize = 12.5.sp,
        )
        Spacer(modifier = Modifier.height(28.dp))
        CircularProgressIndicator(
            color = ToolboxColors.Accent,
            modifier = Modifier.size(40.dp),
            strokeWidth = 3.dp,
        )
        Spacer(modifier = Modifier.height(18.dp))
        LinearProgressIndicator(
            progress = { progress / 100f },
            modifier = Modifier.fillMaxWidth(0.6f).height(3.dp),
            color = ToolboxColors.Accent,
            trackColor = ToolboxColors.Card,
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "$progress%",
            color = ToolboxColors.TextDim,
            fontSize = 12.sp,
        )

        // 看门狗超时：12 秒未完成加载 → 代理指引（GFW 阻断可能永不报错，必须主动提示）
        if (slowHint) {
            Spacer(modifier = Modifier.height(28.dp))
            Text(
                text = "加载时间有点长…",
                color = ToolboxColors.Warning,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Steam 社区在国内无法直连\n请确认代理 / VPN 已开启",
                color = ToolboxColors.TextDim,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(18.dp))
            Row(modifier = Modifier.fillMaxWidth(0.8f)) {
                DshOutlineButton(
                    text = "继续等待",
                    onClick = onKeepWaiting,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(12.dp))
                DshPrimaryButton(
                    text = "重试",
                    onClick = onRetry,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
