package com.four.toolboxmobile.ui.dsh

import android.annotation.SuppressLint
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.view.doOnLayout
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.four.toolboxmobile.DshUiState
import com.four.toolboxmobile.DshViewModel
import com.four.toolboxmobile.ui.components.ToolboxMotion
import com.four.toolboxmobile.ui.theme.ToolboxColors

/**
 * DSH 远程工具（全屏覆盖层）：绑定（扫码/手动）→ 找入口 → WebView 加载 DSH GUI。
 * WebView 强制深色模式（algorithmic darkening 优先，旧 WebView 降级 force dark）。
 */
@Composable
fun DshToolScreen(onClose: () -> Unit, viewModel: DshViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    var showScanner by remember { mutableStateOf(false) }
    var reloadTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) { viewModel.start() }

    Box(modifier = Modifier.fillMaxSize().background(ToolboxColors.Bg)) {
        // imePadding：edge-to-edge 下系统不再为输入法压缩窗口（adjustResize 失效），
        // 必须手动消费 WindowInsets.ime——键盘弹出时整列缩进，WebView 视口随之变矮，
        // Chromium 会自动把聚焦的输入框滚进可视区（绑定页 TextField 同理受益）
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            DshTopBar(
                state = state,
                onBack = onClose,
                onReload = { reloadTick++ },
                onRebind = { viewModel.rebind() },
            )
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (val s = state) {
                    is DshUiState.Unbound -> BindView(
                        viewModel = viewModel,
                        onScanQr = { showScanner = true },
                    )
                    is DshUiState.Searching -> SearchingView(s)
                    is DshUiState.Error -> ErrorView(
                        state = s,
                        onRetry = { viewModel.retry() },
                        onRebind = { viewModel.rebind() },
                    )
                    is DshUiState.Connected -> DshWebView(
                        url = s.url,
                        reloadTick = reloadTick,
                        onClose = onClose,
                        onDeviceUrl = viewModel::onDeviceUrlCaptured,
                    )
                }
            }
        }

        // 扫码层盖在整屏之上（含顶栏），覆盖层过渡（运动常量见 ToolboxMotion）
        AnimatedVisibility(
            visible = showScanner,
            enter = ToolboxMotion.overlayEnter,
            exit = ToolboxMotion.overlayExit,
        ) {
            QrScannerView(
                onResult = { content ->
                    showScanner = false
                    viewModel.bind(content)
                },
                onCancel = { showScanner = false },
            )
        }
    }
}

/** 顶栏：返回 + 标题/入口信息 + （连接后）刷新、重新绑定 */
@Composable
private fun DshTopBar(
    state: DshUiState,
    onBack: () -> Unit,
    onReload: () -> Unit,
    onRebind: () -> Unit,
) {
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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "DSH 远程",
                    color = ToolboxColors.Text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (state is DshUiState.Connected) {
                    Text(
                        text = "${state.host}:${state.port}",
                        color = ToolboxColors.TextDim,
                        fontSize = 11.sp,
                    )
                }
            }
            if (state is DshUiState.Connected) {
                IconButton(onClick = onReload) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "刷新",
                        tint = ToolboxColors.TextDim,
                    )
                }
                DshTextButton(text = "重新绑定", onClick = onRebind)
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(ToolboxColors.Border))
    }
}

/** 绑定页：扫码 / 手动输入两个入口 + 协议说明 */
@Composable
private fun BindView(viewModel: DshViewModel, onScanQr: () -> Unit) {
    val bindError by viewModel.bindError.collectAsState()
    var manualOpen by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(56.dp))
        Text(text = "🛰️", fontSize = 44.sp)
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = "连接 DSH",
            color = ToolboxColors.Text,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "在电脑端 DSH 侧栏点 📱 打开「远程访问」面板\n扫码或粘贴配对链接，即可在手机上远程使用",
            color = ToolboxColors.TextDim,
            fontSize = 12.5.sp,
            lineHeight = 19.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(6.dp))
        // 插件前提提醒（2026-09-06 Four 要求：连接时提示需装对应插件）
        Text(
            text = "前提：电脑端已安装 dsh-remote-web-ui 插件\n并开启局域网访问",
            color = ToolboxColors.TextDim,
            fontSize = 11.5.sp,
            lineHeight = 17.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(32.dp))

        DshPrimaryButton(
            text = "📷  扫码绑定",
            onClick = onScanQr,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(12.dp))
        DshOutlineButton(
            text = "⌨️  手动输入地址 / 密钥",
            onClick = { manualOpen = !manualOpen },
            modifier = Modifier.fillMaxWidth(),
        )

        AnimatedVisibility(visible = manualOpen) {
            Column {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = {
                        Text(
                            text = "http://192.168.5.12:3080/pair-accept?pair=…",
                            fontSize = 13.sp,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    maxLines = 3,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = ToolboxColors.Text,
                        unfocusedTextColor = ToolboxColors.Text,
                        focusedBorderColor = ToolboxColors.Accent,
                        unfocusedBorderColor = ToolboxColors.Border,
                        cursorColor = ToolboxColors.Accent,
                        focusedPlaceholderColor = ToolboxColors.GrayDim,
                        unfocusedPlaceholderColor = ToolboxColors.GrayDim,
                    ),
                )
                Spacer(modifier = Modifier.height(10.dp))
                DshPrimaryButton(
                    text = "绑定并连接",
                    onClick = { viewModel.bind(input) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = input.isNotBlank(),
                )
            }
        }

        bindError?.let {
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = it, color = ToolboxColors.Danger, fontSize = 12.5.sp)
        }
        Spacer(modifier = Modifier.height(32.dp))
    }
}

/** 找入口中：阶段文案 + （网段扫描时）进度 */
@Composable
private fun SearchingView(state: DshUiState.Searching) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            color = ToolboxColors.Accent,
            modifier = Modifier.size(44.dp),
            strokeWidth = 3.dp,
        )
        Spacer(modifier = Modifier.height(20.dp))
        Text(text = state.stage, color = ToolboxColors.Text, fontSize = 14.sp)
        if (state.total > 1) {
            Spacer(modifier = Modifier.height(14.dp))
            LinearProgressIndicator(
                progress = { state.scanned.toFloat() / state.total },
                modifier = Modifier.fillMaxWidth(0.6f).height(3.dp),
                color = ToolboxColors.Accent,
                trackColor = ToolboxColors.Card,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "${state.scanned} / ${state.total}",
                color = ToolboxColors.TextDim,
                fontSize = 12.sp,
            )
        }
    }
}

/** 错误页：重试 / 重新绑定（密钥失效时主按钮为重新绑定） */
@Composable
private fun ErrorView(
    state: DshUiState.Error,
    onRetry: () -> Unit,
    onRebind: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = "⚠️", fontSize = 40.sp)
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = state.message,
            color = ToolboxColors.Text,
            fontSize = 13.5.sp,
            lineHeight = 21.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(28.dp))
        if (state.keyInvalid) {
            DshPrimaryButton(text = "重新绑定", onClick = onRebind, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(12.dp))
            DshOutlineButton(text = "重试", onClick = onRetry, modifier = Modifier.fillMaxWidth())
        } else {
            DshPrimaryButton(text = "重试", onClick = onRetry, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(12.dp))
            DshOutlineButton(text = "重新绑定", onClick = onRebind, modifier = Modifier.fillMaxWidth())
        }
    }
}

/**
 * DSH GUI 的 WebView 容器：
 * - 深色：App 全局 DayNight + MODE_NIGHT_YES（MainActivity）→ WebView 上报
 *   prefers-color-scheme: dark → DSH GUI 切原生深色主题。
 *   ⚠️ 真机实测：只要允许 algorithmic darkening，WebView 就向页面谎报浅色
 *   （准备自己反色），而 DSH 声明了 color-scheme 又跳过反色 → 净效果浅色。
 *   故彻底移除 darkening，深色完全交给页面原生主题
 * - 加载时机：doOnLayout 拿到非零尺寸后才 loadUrl——AndroidView 工厂阶段视图是 0×0，
 *   此时加载会让页面以 0 高度视口首排版，vh/dvh 视口单位恒为 0（真机实测），
 *   html/body/#root 高度链坍塌 → 整页白屏只剩 fixed 弹层
 * - textZoom 固定 100：WebView 默认跟随系统字体缩放（Edge 不跟随）
 * - 背景预设深色防白闪；顶部 2dp 加载进度条；主框架加载失败给重试入口
 * - 返回键先退网页历史，退无可退才关闭工具
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun DshWebView(
    url: String,
    reloadTick: Int,
    onClose: () -> Unit,
    onDeviceUrl: (String) -> Unit,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var pageError by remember { mutableStateOf<String?>(null) }
    // 配置变更（旋转等）重建后恢复网页状态：saveState/restoreState 保住页面与后退栈（2026-09-06 修复）
    val webViewState = rememberSaveable { android.os.Bundle() }

    BackHandler {
        val wv = webView
        if (wv?.canGoBack() == true) wv.goBack() else onClose()
    }

    // 跟随生命周期暂停/恢复（切后台停渲染省电）
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

    // 顶栏刷新按钮：tick 变化触发 reload
    LaunchedEffect(reloadTick) {
        if (reloadTick > 0) webView?.reload()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    // 关键①：显式 MATCH_PARENT LayoutParams——WebView 按 LayoutParams 推导
                    // 视口尺寸，缺失时 vh/dvh 等视口单位恒为 0（真机实测，Qiita/CSDN 同案例），
                    // html/body/#root 高度链随之坍塌 → 整页白屏只剩 fixed 弹层
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    // 关键②：文档起始脚本把 prefers-color-scheme 钉死为 dark——
                    // App 级夜间模式实测传不进 WebView 143，DSH 的 ThemeRuntime
                    // 只认 matchMedia，在页面任何脚本执行前改写它，GUI 即切原生深色
                    if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                        WebViewCompat.addDocumentStartJavaScript(
                            this,
                            """
                            (function(){var o=window.matchMedia.bind(window);window.matchMedia=function(q){if(q&&q.indexOf('prefers-color-scheme')>=0){var d=q.indexOf('dark')>=0;return{matches:d,media:q,onchange:null,addEventListener:function(){},removeEventListener:function(){},addListener:function(){},removeListener:function(){},dispatchEvent:function(){return false}};}return o(q);};})();
                            """.trimIndent(),
                            setOf("*"),
                        )
                    }
                    if (com.four.toolboxmobile.BuildConfig.DEBUG) {
                        WebView.setWebContentsDebuggingEnabled(true) // debug 包可 chrome://inspect
                    }
                    setBackgroundColor(0xFF1C1C1C.toInt()) // 对齐 Toolbox Bg，防加载白闪
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        // 锁定文字缩放：WebView 默认跟随系统字体大小（Edge 不跟随），
                        // 系统大字体会撑坏桌面端 SPA 的固定高度布局
                        textZoom = 100
                        // 桌面端 GUI 在手机上允许双指缩放
                        builtInZoomControls = true
                        displayZoomControls = false
                    }
                    this.webViewClient = object : WebViewClient() {
                        // 主框架本次导航是否失败：HTTP 错误页也会触发 onPageFinished，
                        // 不加标志会被它立刻把错误层清掉
                        var loadFailed = false

                        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                            loadFailed = false
                            // 配对链路 303 跳到 /pair-app?device=… → 捕获为「设备书签」持久化，
                            // 之后打开工具直接加载它进 GUI（纯 HTTP 下导航 / 会撞 harness 401）
                            if (url != null && "/pair-app" in url && "device=" in url) {
                                onDeviceUrl(url)
                            }
                            super.onPageStarted(view, url, favicon)
                        }

                        override fun onReceivedError(
                            view: WebView,
                            request: WebResourceRequest,
                            error: WebResourceError,
                        ) {
                            if (request.isForMainFrame) {
                                loadFailed = true
                                pageError = "页面加载失败（错误码 ${error.errorCode}）"
                            }
                        }

                        override fun onReceivedHttpError(
                            view: WebView,
                            request: WebResourceRequest,
                            errorResponse: android.webkit.WebResourceResponse,
                        ) {
                            // 配对路径 404 = 电脑端没装 dsh-remote-web-ui 插件（或 harness 版本过低），
                            // 给出针对性提醒而不是裸错误码（2026-09-06 Four 要求）
                            if (request.isForMainFrame && errorResponse.statusCode == 404) {
                                loadFailed = true
                                val path = request.url?.path.orEmpty()
                                pageError = if (path.startsWith("/pair-")) {
                                    "电脑端没有找到配对服务（HTTP 404）\n请确认已安装 dsh-remote-web-ui 插件并开启局域网访问"
                                } else {
                                    "页面不存在（HTTP 404）"
                                }
                            }
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            // chrome-error:// 是 Chromium 自己的错误页：它的"加载完成"
                            // 不能清掉我们的错误层，否则自绘错误提示永远被它盖住
                            if (!url.startsWith("chrome-error://") && !loadFailed) pageError = null
                        }
                    }
                    webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onProgressChanged(view: WebView, newProgress: Int) {
                            progress = newProgress
                        }
                    }
                    // 关键：等首次非零尺寸布局后再加载——AndroidView 工厂阶段视图是 0×0，
                    // 此时加载会让页面以 0 高度视口首排版，vh/dvh 等视口单位恒为 0
                    // （真机实测），html/body/#root 高度链坍塌 → 整页白屏只剩 fixed 弹层
                    var loaded = false
                    doOnLayout {
                        if (!loaded && it.width > 0 && it.height > 0) {
                            loaded = true
                            // 有存档（配置变更重建）→ 恢复页面与后退栈；否则冷启动加载
                            if (webViewState.isEmpty) loadUrl(url)
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

        if (progress in 1..99) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().height(2.dp),
                color = ToolboxColors.Accent,
                trackColor = Color.Transparent,
            )
        }

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
                        webView?.reload()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

// ---------- 本工具内复用的小按钮（Toolbox 配色） ----------

@Composable
internal fun DshPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(46.dp),
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = ToolboxColors.Accent,
            contentColor = ToolboxColors.OnAccent,
            disabledContainerColor = ToolboxColors.GrayDim,
            disabledContentColor = ToolboxColors.TextDim,
        ),
    ) {
        Text(text = text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun DshOutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(46.dp),
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, ToolboxColors.Border),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = ToolboxColors.Text),
    ) {
        Text(text = text, fontSize = 14.sp)
    }
}

@Composable
internal fun DshTextButton(text: String, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onClick) {
        Text(text = text, color = ToolboxColors.Accent, fontSize = 13.sp)
    }
}
