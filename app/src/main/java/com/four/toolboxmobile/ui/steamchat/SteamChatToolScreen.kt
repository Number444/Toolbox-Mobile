package com.four.toolboxmobile.ui.steamchat

import android.annotation.SuppressLint
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.four.toolboxmobile.ui.dsh.DshPrimaryButton
import com.four.toolboxmobile.ui.theme.ToolboxColors

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
    // 启动遮罩：首页首次加载完成后淡出；顶栏刷新时重新出现
    var maskVisible by remember { mutableStateOf(true) }

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
            webView?.reload()
        }
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
                                pageError = "页面加载失败（错误码 ${error.errorCode}）\n请检查网络后重试"
                            }
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            pageError = null
                            maskVisible = false // 首页加载完成 → 遮罩淡出
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
                            loadUrl(STEAM_CHAT_URL)
                        }
                    }
                    webView = this
                }
            },
            onRelease = {
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
                        webView?.reload()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // 启动遮罩：盖住 WebView 冷启动阶段，首页加载完成后淡出
        AnimatedVisibility(
            visible = maskVisible && pageError == null,
            exit = fadeOut(animationSpec = tween(450)),
        ) {
            LoadingMask(progress = progress)
        }
    }
}

/** 启动加载遮罩：Steam 标识 + 环形指示 + 实时百分比 + 进度条 */
@Composable
private fun LoadingMask(progress: Int) {
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
    }
}
