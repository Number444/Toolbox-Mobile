package com.four.toolboxmobile.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.four.toolboxmobile.BuildConfig
import com.four.toolboxmobile.DshStore
import com.four.toolboxmobile.DshViewModel
import com.four.toolboxmobile.ui.components.ToolboxConfirmDialog
import com.four.toolboxmobile.ui.components.ToolboxDropdownItem
import com.four.toolboxmobile.ui.components.ToolboxDropdownPopup
import com.four.toolboxmobile.ui.components.ToolboxUpdateDialog
import com.four.toolboxmobile.ui.theme.ToolboxColors
import com.four.toolboxmobile.updater.AppUpdater
import com.four.toolboxmobile.updater.AppUpdater.UpdateState
import kotlinx.coroutines.launch

/** 设置页键名（MainScreen 读取同一键决定初始页） */
private const val PREFS_NAME = "settings"
private const val KEY_DEFAULT_PAGE = "default_page"

private const val REPO_URL = "https://github.com/Number444/Toolbox-Mobile"

private val PAGE_NAMES = listOf("远程连接", "工具", "设置")

/**
 * 设置页（右页）。样式：Android 标准 Preference 列表形态（标题 + 摘要 + 点击弹单选对话框），
 * 配色与卡片语言沿用 Toolbox 色板。
 */
@Composable
fun SettingsPage(dshViewModel: DshViewModel = viewModel()) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    // DSH 绑定（2026-09-06 恢复，协议已切到 dsh-remote-web-ui 插件：配对链接 + 设备书签）
    val dshStore = remember { DshStore(context) }

    var defaultPage by remember { mutableStateOf(prefs.getInt(KEY_DEFAULT_PAGE, 1).coerceIn(0, 2)) }
    var showPageDialog by remember { mutableStateOf(false) }

    // DSH 绑定展示态：与工具内 ViewModel（Activity 级）共享同一实例，清除即全局生效。
    // 监听其状态跃迁刷新展示——在工具里配对成功（Unbound→Connected）后切回本页立即看到新绑定
    fun dshBindingSummary(): String? {
        val url = dshStore.loadDeviceUrl() ?: dshStore.loadPairingUrl() ?: return null
        val uri = Uri.parse(url)
        val port = if (uri.port > 0) ":${uri.port}" else ""
        return "${uri.host ?: "?"}$port"
    }
    var dshBinding by remember { mutableStateOf(dshBindingSummary()) }
    val dshState by dshViewModel.state.collectAsState()
    LaunchedEffect(dshState) { dshBinding = dshBindingSummary() }
    var showClearDshDialog by remember { mutableStateOf(false) }

    // 应用内更新：进入设置页自动检查一次；点击行/对话框驱动后续动作
    val coroutineScope = rememberCoroutineScope()
    val updateState by AppUpdater.state.collectAsState()
    var showUpdateDialog by remember { mutableStateOf(false) }
    var showInstallDialog by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { AppUpdater.check(context.applicationContext, force = false) }

    // API 26~28 下载前缺存储权限：先申请，授予后直接续跑下载（29+ 走 MediaStore 不会触发）；
    // 拒绝时落 Failed 态并重开对话框给出可见反馈（防「点了没反应」）
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            AppUpdater.download(context)
        } else {
            AppUpdater.notifyStoragePermissionDenied()
            showUpdateDialog = true
        }
    }
    val startDownload = {
        if (Build.VERSION.SDK_INT <= 28 && ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            storagePermissionLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            AppUpdater.download(context)
        }
    }
    // 拉起系统安装器；未获「安装未知应用」授权时先引导去系统设置页（HyperOS 入口很深）
    val startInstall: (UpdateState.Downloaded) -> Unit = { d ->
        if (AppUpdater.canInstall(context)) {
            try {
                context.startActivity(AppUpdater.installIntent(d.uri))
            } catch (e: ActivityNotFoundException) {
                // 理论上必有安装器，兜底不崩
            }
        } else {
            context.startActivity(AppUpdater.unknownSourcesSettingsIntent(context))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ToolboxColors.Bg)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 16.dp, bottom = 110.dp),
    ) {
        Text(
            text = "⚙️ 设置",
            color = ToolboxColors.Text,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(16.dp))

        // ===== 启动分组 =====
        SettingsGroup(title = "启动") {
            // 锚点 = 右侧值文本（"工具 ›"）的窗口位置：弹层右上角对齐它并盖住文本
            var valueAnchor by remember { mutableStateOf<IntOffset?>(null) }
            Box {
                SettingsRow(
                    title = "默认打开页面",
                    summary = "启动 App 时首先显示的页面",
                    value = PAGE_NAMES[defaultPage],
                    onClick = { showPageDialog = true },
                    onValueBounds = { rect -> valueAnchor = IntOffset(rect.right, rect.top) },
                )
                ToolboxDropdownPopup(
                    expanded = showPageDialog,
                    onDismissRequest = { showPageDialog = false },
                    anchorPoint = valueAnchor,
                ) { dismiss ->
                    PAGE_NAMES.forEachIndexed { index, name ->
                        ToolboxDropdownItem(
                            text = name,
                            selected = defaultPage == index,
                            onClick = {
                                defaultPage = index
                                prefs.edit().putInt(KEY_DEFAULT_PAGE, index).apply()
                                dismiss()
                            },
                        )
                    }
                }
            }
        }

        // ===== 工具分组 =====
        SettingsGroup(title = "工具") {
            SettingsRow(
                title = "DSH 绑定",
                summary = "远程工具记住的配对链接与设备书签（dsh-remote-web-ui 插件）",
                value = dshBinding ?: "未绑定",
                onClick = if (dshBinding != null) ({ showClearDshDialog = true }) else null,
            )
        }

        // ===== 关于分组 =====
        SettingsGroup(title = "关于") {
            SettingsRow(
                title = "版本",
                summary = "ToolboxMobile",
                value = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                onClick = null,
            )
            // 应用内更新：进入页面自动检查一次（AppUpdater 运行期缓存）；有新版/可安装时绿色高亮
            val recheck: () -> Unit = {
                coroutineScope.launch { AppUpdater.check(context.applicationContext, force = true) }
            }
            val (updateValue, updateColor, updateClick) = when (val s = updateState) {
                is UpdateState.Idle, is UpdateState.Checking ->
                    Triple("检查中…", ToolboxColors.TextDim, null)
                is UpdateState.UpToDate ->
                    Triple("已是最新", ToolboxColors.TextDim, recheck)
                is UpdateState.Failed ->
                    Triple("检查失败 · 点击重试", ToolboxColors.TextDim, recheck)
                is UpdateState.Available ->
                    Triple("发现新版本 v${s.version}", ToolboxColors.Accent, { showUpdateDialog = true })
                is UpdateState.Downloading ->
                    Triple("下载中…", ToolboxColors.Accent, { showUpdateDialog = true })
                is UpdateState.Downloaded ->
                    Triple("可安装 v${s.version}", ToolboxColors.Accent, { showInstallDialog = true })
            }
            SettingsRow(
                title = "检查更新",
                summary = "GitHub Releases 发布通道",
                value = updateValue,
                valueColor = updateColor,
                onClick = updateClick,
            )
            SettingsRow(
                title = "GitHub 仓库",
                summary = "源码 · 问题反馈",
                value = "Toolbox-Mobile",
                valueColor = ToolboxColors.TextDim,
                onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL)))
                },
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "默认页面更改后下次启动 App 时生效",
            color = ToolboxColors.TextDim,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }

    // 清除 DSH 绑定确认弹窗（统一组件：常驻组合 + visible 开关，退出动画才能播完）
    ToolboxConfirmDialog(
        visible = showClearDshDialog,
        title = "清除 DSH 绑定？",
        message = "将删除已保存的配对链接与设备书签，下次打开 DSH 远程需要重新扫码配对。",
        confirmText = "清除",
        danger = true,
        onConfirm = {
            dshViewModel.clearBinding()
            dshBinding = null
            showClearDshDialog = false
        },
        onDismiss = { showClearDshDialog = false },
    )

    // 更新对话框：Available/Downloading/Downloaded/Failed 内容随状态切换（下载中可关掉，点行再开）
    ToolboxUpdateDialog(
        visible = showUpdateDialog,
        state = updateState,
        onDownload = startDownload,
        onCancelDownload = { AppUpdater.cancelDownload() },
        onInstall = {
            (updateState as? UpdateState.Downloaded)?.let(startInstall)
            showUpdateDialog = false
        },
        onDismiss = { showUpdateDialog = false },
    )

    // 可安装态点击行的安装确认（APK 已在系统下载目录，无需再下载）
    ToolboxConfirmDialog(
        visible = showInstallDialog,
        title = "安装更新？",
        message = (updateState as? UpdateState.Downloaded)
            ?.let { "ToolboxMobile v${it.version} 已下载完成，点击安装将拉起系统安装器。" }
            ?: "",
        confirmText = "安装",
        onConfirm = {
            (updateState as? UpdateState.Downloaded)?.let(startInstall)
            showInstallDialog = false
        },
        onDismiss = { showInstallDialog = false },
    )
}

/** 分组卡片：小标题 + 圆角深色容器 */
@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Text(
        text = title,
        color = ToolboxColors.Accent,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(ToolboxColors.Card)
            .border(
                width = 1.dp,
                color = ToolboxColors.Text.copy(alpha = 0.05f),
                shape = RoundedCornerShape(8.dp),
            ),
    ) {
        content()
    }
}

/** 单条设置项：标题 + 摘要，右侧当前值（可点击时带 ›，onClick=null 为纯展示行）。
 *  [onValueBounds] 非空时回报右侧值文本的窗口坐标（供锚点弹层定位）；
 *  [valueColor] 显式指定右侧值颜色（null = 默认：可点击 Accent 绿 / 纯展示 TextDim 灰） */
@Composable
private fun SettingsRow(
    title: String,
    summary: String,
    value: String,
    onClick: (() -> Unit)?,
    onValueBounds: ((IntRect) -> Unit)? = null,
    valueColor: Color? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = ToolboxColors.Text, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = summary, color = ToolboxColors.TextDim, fontSize = 12.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = value,
                color = valueColor
                    ?: if (onClick != null) ToolboxColors.Accent else ToolboxColors.TextDim,
                fontSize = 13.sp,
                modifier = if (onValueBounds != null) {
                    Modifier.onGloballyPositioned { lc ->
                        onValueBounds(IntRect(lc.positionInWindow().round(), lc.size))
                    }
                } else {
                    Modifier
                },
            )
            if (onClick != null) {
                Text(
                    text = " ›",
                    color = ToolboxColors.TextDim,
                    fontSize = 16.sp,
                )
            }
        }
    }
}
